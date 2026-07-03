package scalascript.cli

import scalascript.interpreter.Interpreter
import scalascript.interpreter.vm.jit.{AsmJitBackend, JitBackend, JavacJitBackend, JitLint}
import scalascript.parser.Parser

/** `ssc lint-jit <file.ssc>` — reports which top-level `def`s in the module
 *  will not JIT-compile, with the structural reason for each bail and a
 *  suggested refactor when one applies.
 *
 *  Used during development to spot perf cliffs before they show up as
 *  evalCore tree-walk on a JFR profile. Exits non-zero when any reachable
 *  `def` reports a bail reason — CI-friendly.
 *
 *  Phase 2 Commit 1: the analyser walks `interp.globals` after a normal
 *  module load, so any side effect at the top level (init queries, server
 *  binds, network calls) runs as usual. Future commits may add a
 *  `--static` flag that builds FunVs without executing top-level
 *  Expressions, but that needs the parser/typer to expose Def-only
 *  reflection first.
 *
 *  `--include-while`: also report JIT coverage for top-level while loops.
 *  The while-loop reports are derived from `interp.whileJitCache`, which is
 *  populated as loops execute during `runSections`.  Only loops that were
 *  actually executed (i.e. the condition was true at least once) appear in
 *  the cache.  Loops inside dead branches are not visible. */
final class LintJitCmd extends CliCommand:
  def name = "lint-jit"
  override def summary = "Report top-level defs that won't JIT-compile, with suggested fixes"
  override def category = "Diagnostics"
  override def details = List(
    "Flags: --json            emit machine-readable JSON instead of human text",
    "       --quiet           print only files with at least one bail",
    "       --fail-on-bail    exit non-zero on any bail (default: warn only)",
    "       --include-while   also report JIT coverage for top-level while loops.",
    "                         Loops that were not executed during module load are",
    "                         invisible (they don't appear in the cache).",
    "       --backend <b>     backend to lint against: javac (default), asm, or both.",
    "                         'both' shows a side-by-side diff — functions that JIT",
    "                         on one backend but not the other are flagged explicitly.",
    "                         Overrides SSC_JIT_BACKEND for the lint run only."
  )

  def run(args: List[String]): Unit =
    val r = runResult(args)
    System.exit(r.exitCode.value)

  override def runResult(args: List[String]): CommandResult =
    var emitJson:      Boolean         = false
    var quiet:         Boolean         = false
    var failOnBail:    Boolean         = false
    var includeWhile:  Boolean         = false
    var backendArg:    Option[String]  = None
    val files = scala.collection.mutable.ListBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--json"            => emitJson      = true
        case "--quiet"           => quiet         = true
        case "--fail-on-bail"    => failOnBail    = true
        case "--include-while"   => includeWhile  = true
        case "--backend"         =>
          if it.hasNext then backendArg = Some(it.next())
          else
            System.err.println("ssc lint-jit: --backend requires an argument (javac, asm, both)")
            return CommandResult.failure()
        case other if other.startsWith("--backend=") =>
          backendArg = Some(other.stripPrefix("--backend="))
        case other               => files += other

    if files.isEmpty then
      System.err.println(
        "usage: ssc lint-jit [--json] [--quiet] [--fail-on-bail] [--include-while] " +
          "[--backend javac|asm|both] <file.ssc> [...]")
      return CommandResult.failure()

    val compareMode = backendArg.contains("both")
    val backend: JitBackend = backendArg match
      case Some("asm")   => AsmJitBackend
      case Some("javac") => JavacJitBackend
      case Some("both")  => JitBackend.default  // unused in compare mode
      case Some(other)   =>
        System.err.println(s"ssc lint-jit: unknown --backend '$other' (choose javac, asm, or both)")
        return CommandResult.failure()
      case None          => JitBackend.default

    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    var anyBail = false

    files.foreach { path =>
      val src    = scala.io.Source.fromFile(path).mkString
      val module = Parser.parse(src)
      val interp = Interpreter(devNull)
      interp.runSections(module)

      if compareMode then
        val defReports   = JitLint.lintInterpreterCompare(interp)
        val defBails     = defReports.filter(_.anyFail)
        if defBails.nonEmpty then anyBail = true
        val whileReports = if includeWhile then JitLint.lintWhileLoopsCompare(interp) else Nil
        val whileBails   = whileReports.filter(_.anyFail)
        if whileBails.nonEmpty then anyBail = true
        if emitJson then
          defBails.foreach { r =>
            val javacStatus = if r.javac.willJit then "ok" else "fail"
            val asmStatus   = if r.asm.willJit   then "ok" else "fail"
            val reasons     = (r.javac.bailReasons ++ r.asm.bailReasons).distinct
            println(
              s"""{"file":"${quote(path)}","kind":"def","def":"${quote(r.defName)}",""" +
                s""""line":${r.defLine.fold("null")(_.toString)},""" +
                s""""javac":"$javacStatus","asm":"$asmStatus",""" +
                s""""reasons":[${reasons.map(b => "\"" + quote(b.description) + "\"").mkString(",")}]}"""
            )
          }
          (if quiet then whileBails else whileReports).foreach { r =>
            val javacStatus = if r.javac.willJit then "ok" else "fail"
            val asmStatus   = if r.asm.willJit   then "ok" else "fail"
            val reasons     = (r.javac.bailReasons ++ r.asm.bailReasons).distinct
            println(
              s"""{"file":"${quote(path)}","kind":"while",""" +
                s""""line":${r.condLine.fold("null")(_.toString)},""" +
                s""""javac":"$javacStatus","asm":"$asmStatus",""" +
                s""""reasons":[${reasons.map(b => "\"" + quote(b.description) + "\"").mkString(",")}]}"""
            )
          }
        else if !quiet || defBails.nonEmpty || whileBails.nonEmpty then
          println(s"=== $path ===")
          if defReports.isEmpty && whileReports.isEmpty then println("  (no top-level defs or while loops)")
          else
            val toShowDefs = if quiet then defBails else defReports
            toShowDefs.foreach(r => println(r.humanReadable))
            val toShowWhiles = if quiet then whileBails else whileReports
            if toShowWhiles.nonEmpty then
              println("-- while loops --")
              toShowWhiles.foreach(r => println(r.humanReadable))
          println()
      else
        val defReports   = JitLint.lintInterpreter(interp, backend)
        val defBails     = defReports.filterNot(_.willJit)
        if defBails.nonEmpty then anyBail = true
        val whileReports = if includeWhile then JitLint.lintWhileLoops(interp, backend) else Nil
        val whileBails   = whileReports.filterNot(_.willJit)
        if whileBails.nonEmpty then anyBail = true
        val backendLabel = backendArg.getOrElse("default")
        if emitJson then
          defBails.foreach { r =>
            println(
              s"""{"file":"${quote(path)}","kind":"def","def":"${quote(r.defName)}",""" +
                s""""line":${r.defLine.fold("null")(_.toString)},""" +
                s""""backend":"$backendLabel",""" +
                s""""reasons":[${r.bailReasons.map(b => "\"" + quote(b.description) + "\"").mkString(",")}]}"""
            )
          }
          (if quiet then whileBails else whileReports).foreach { r =>
            println(
              s"""{"file":"${quote(path)}","kind":"while",""" +
                s""""line":${r.condLine.fold("null")(_.toString)},""" +
                s""""backend":"$backendLabel",""" +
                s""""reasons":[${r.bailReasons.map(b => "\"" + quote(b.description) + "\"").mkString(",")}]}"""
            )
          }
        else if !quiet || defBails.nonEmpty || whileBails.nonEmpty then
          println(s"=== $path  [backend: $backendLabel] ===")
          if defReports.isEmpty && whileReports.isEmpty then println("  (no top-level defs or while loops)")
          else
            val toShowDefs = if quiet then defBails else defReports
            toShowDefs.foreach(r => println(r.humanReadable))
            val toShowWhiles = if quiet then whileBails else whileReports
            if toShowWhiles.nonEmpty then
              println("-- while loops --")
              toShowWhiles.foreach(r => println(r.humanReadable))
          println()
    }
    if failOnBail && anyBail then CommandResult.failure()
    else CommandResult.Success

  private def quote(s: String): String =
    val sb = new StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c    => sb.append(c)
      i += 1
    sb.toString
