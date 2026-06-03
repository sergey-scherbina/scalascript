package scalascript.cli

import scalascript.interpreter.Interpreter
import scalascript.interpreter.vm.jit.JitLint
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
 *  reflection first. */
final class LintJitCmd extends CliCommand:
  def name = "lint-jit"
  override def summary = "Report top-level defs that won't JIT-compile, with suggested fixes"
  override def category = "Diagnostics"
  override def details = List(
    "Flags: --json     emit machine-readable JSON instead of human text",
    "       --quiet    print only files with at least one bail",
    "       --fail-on-bail  exit non-zero on any bail (default: warn only)"
  )

  def run(args: List[String]): Unit =
    val r = runResult(args)
    System.exit(r.exitCode.value)

  override def runResult(args: List[String]): CommandResult =
    var emitJson:   Boolean = false
    var quiet:      Boolean = false
    var failOnBail: Boolean = false
    val files = scala.collection.mutable.ListBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--json"         => emitJson   = true
        case "--quiet"        => quiet      = true
        case "--fail-on-bail" => failOnBail = true
        case other            => files += other
    if files.isEmpty then
      System.err.println("usage: ssc lint-jit [--json] [--quiet] [--fail-on-bail] <file.ssc> [...]")
      return CommandResult.failure()
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    var anyBail = false
    files.foreach { path =>
      val src    = scala.io.Source.fromFile(path).mkString
      val module = Parser.parse(src)
      val interp = Interpreter(devNull)
      interp.runSections(module)
      val reports = JitLint.lintInterpreter(interp)
      val bails   = reports.filterNot(_.willJit)
      if bails.nonEmpty then anyBail = true
      if emitJson then
        // Compact one-line JSON per def. Avoids pulling in a JSON dep.
        bails.foreach { r =>
          println(
            s"""{"file":"${quote(path)}","def":"${quote(r.defName)}",""" +
              s""""line":${r.defLine.fold("null")(_.toString)},""" +
              s""""reasons":[${r.bailReasons.map(b => "\"" + quote(b.description) + "\"").mkString(",")}]}"""
          )
        }
      else if !quiet || bails.nonEmpty then
        println(s"=== $path ===")
        if reports.isEmpty then println("  (no top-level defs)")
        else
          val toShow = if quiet then bails else reports
          toShow.foreach(r => println(r.humanReadable))
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
