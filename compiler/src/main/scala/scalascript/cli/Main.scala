package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeBrowserPatch, JvmGen, ScalaJsBackend}
import scalascript.ast.*

@main def ssc(args: String*): Unit =
  if args.isEmpty then { printUsage(); System.exit(1) }
  args.head match
    case "parse"               => parseCommand(args.tail.toList)
    case "check"               => checkCommand(args.tail.toList)
    case "run"                 => runCommand(args.tail.toList)
    case "watch"               => watchCommand(args.tail.toList)
    case "repl"                => replCommand(args.tail.toList)
    case "emit-js"             => emitJsCommand(args.tail.toList)
    case "emit-spa"            => emitSpaCommand(args.tail.toList)
    case "emit-scala"          => emitScalaCommand(args.tail.toList)
    case "compile"             => compileCommand(args.tail.toList)
    case "package"             => packageCommand(args.tail.toList)
    case "serve"               => serveCommand(args.tail.toList)
    case "render"              => renderCommand(args.tail.toList)
    case "build"               => buildCommand(args.tail.toList)
    case "help" | "--help" | "-h" => printUsage()
    case _                     => runCommand(args.toList)

def printUsage(): Unit =
  println("""
    |ScalaScript (ssc)
    |
    |Usage: ssc <command> [options] <files...>
    |
    |Commands:
    |  render                 Render a single .ssc as static HTML — runs the file
    |                         in headless mode (serve() is a no-op), invokes the
    |                         registered handler for a path (default `/`), and
    |                         prints the response body.
    |  build                  Batch-render every .ssc in a directory.  Each file's
    |                         literal GET routes become files under <out-dir>
    |                         (default `dist/`); `/` → index.html, `/about` →
    |                         about.html.  Files without GET routes are skipped.
    |  run                    Execute .ssc via tree-walking interpreter (default)
    |  watch                  Run .ssc and re-run on every file change
    |  repl                   Start interactive REPL (blank line runs, :quit exits)
    |  compile                Compile and run .ssc on JVM via scala-cli
    |  package [flags] <f>    Package .ssc via scala-cli package (see flags below)
    |  emit-scala             Print generated Scala 3 script to stdout
    |  emit-js                Transpile .ssc to JavaScript (Node server) and print to stdout
    |  emit-spa               Wrap .ssc as a browser SPA (HTML + embedded JS) and print to stdout
    |  serve                  Start HTTP server serving .ssc files as web pages
    |  parse                  Parse .ssc files and print AST
    |  check                  Type-check .ssc files
    |  help                   Show this help message
    |
    |Package flags (passed through to scala-cli package):
    |  --assembly             Fat JAR with all dependencies bundled
    |  --standalone           Self-contained binary (like the ssc binary itself)
    |  --native               GraalVM native image (requires native-image)
    |  -o, --output <path>    Output file (default: input filename without .ssc)
    |  --force, -f            Overwrite existing output
    |  (any other scala-cli package flag is forwarded as-is)
    |
    |Examples:
    |  ssc examples/hello.ssc
    |  ssc compile examples/hello.ssc
    |  ssc package examples/hello.ssc
    |  ssc package --assembly examples/hello.ssc
    |  ssc package --assembly -o hello.jar examples/hello.ssc
    |  ssc package --standalone -o hello examples/hello.ssc
    |  ssc emit-scala examples/hello.ssc
    |  ssc emit-js examples/hello.ssc | node
    |  ssc emit-spa examples/spa-demo.ssc > spa.html  # open spa.html in a browser
    |  ssc serve 8080
    |  ssc parse examples/typeclass.ssc
    |""".stripMargin)

def serveCommand(args: List[String]): Unit =
  val port = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
  val dir  = args.drop(1).headOption.getOrElse(".")
  scalascript.server.WebServer.start(port, dir, System.out)

/** `ssc render <file> [path]` — runs the .ssc file in headless mode
 *  (skipping the blocking `serve(port)` call), then invokes the
 *  registered GET handler for `path` (default `/`) with a synthetic
 *  request and prints the response body to stdout.  Useful for
 *  generating static HTML from a server-style `.ssc` page without
 *  booting an HTTP listener. */
def renderCommand(args: List[String]): Unit =
  import scalascript.interpreter.{Interpreter, Value}
  import scalascript.server.Routes
  if args.isEmpty then
    System.err.println("Usage: ssc render <file.ssc> [path]")
    System.exit(1)
  val file = args.head
  val path = args.drop(1).headOption.getOrElse("/")
  val absPath = os.Path(file, os.pwd)
  if !os.exists(absPath) then
    System.err.println(s"Error: File not found: $file"); System.exit(1)
  // Clear any leftover state from a previous render in the same process.
  Routes.clear()
  // A null PrintStream eats `println(...)` output from setup code so it
  // doesn't pollute the rendered HTML on stdout.
  val nullOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream)
  val interp  = Interpreter(out = nullOut, baseDir = Some(absPath / os.up), headless = true)
  try interp.run(scalascript.parser.Parser.parse(os.read(absPath)))
  catch case e: Exception =>
    System.err.println(s"Error running ${file}: ${e.getMessage}")
    System.exit(1)
  Routes.matchRequest("GET", path) match
    case Some((entry, params)) =>
      val req = syntheticRequest("GET", path, params)
      val result = entry.interpreter.invoke(entry.handler, List(req))
      System.out.print(extractResponseBody(result))
    case None =>
      System.err.println(s"No GET route registered for $path in $file")
      System.exit(1)

/** Build a minimal `Request` instance for a static render.  Headers /
 *  cookies / session / files are all empty — handlers that need them
 *  can't be statically rendered without more elaborate plumbing. */
private def syntheticRequest(
    method: String,
    path:   String,
    params: Map[String, String]
): scalascript.interpreter.Value =
  import scalascript.interpreter.Value
  Value.InstanceV("Request", Map(
    "method"      -> Value.StringV(method),
    "path"        -> Value.StringV(path),
    "params"      -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
    "query"       -> Value.MapV(Map.empty),
    "headers"     -> Value.MapV(Map.empty),
    "body"        -> Value.StringV(""),
    "form"        -> Value.MapV(Map.empty),
    "files"       -> Value.MapV(Map.empty),
    "session"     -> Value.MapV(Map.empty),
    "bearerToken" -> Value.OptionV(None),
    "jwtClaims"   -> Value.OptionV(None),
    "basicAuth"   -> Value.OptionV(None)
  ))

private def extractResponseBody(v: scalascript.interpreter.Value): String =
  import scalascript.interpreter.Value
  v match
    case Value.InstanceV("Response", fields) =>
      fields.get("body") match
        case Some(Value.StringV(s)) => s
        case Some(other)            => Value.show(other)
        case None                   => ""
    case Value.StringV(s) => s
    case Value.UnitV      => ""
    case other            => Value.show(other)

/** `ssc build <src-dir> [<out-dir>]` — batch static-site generation.
 *
 *  Walks `src-dir` for top-level `.ssc` files (subdirectories are
 *  treated as imported modules, not pages, and skipped).  For each
 *  file, runs the interpreter in headless mode and renders every
 *  registered literal GET route into `<out-dir>/<route>.html`:
 *
 *    /          →  <out-dir>/index.html
 *    /about     →  <out-dir>/about.html
 *    /blog/x    →  <out-dir>/blog/x.html
 *
 *  Routes with `:capture` segments are skipped (no data source).
 *  Files that register no GET routes are also skipped.  Default
 *  out-dir is `dist/`. */
def buildCommand(args: List[String]): Unit =
  import scalascript.interpreter.{Interpreter, Value}
  import scalascript.server.Routes
  if args.isEmpty then
    System.err.println("Usage: ssc build <src-dir> [<out-dir>]")
    System.exit(1)
  val srcArg = args.head
  val outArg = args.drop(1).headOption.getOrElse("dist")
  val srcDir = os.Path(srcArg, os.pwd)
  val outDir = os.Path(outArg, os.pwd)
  if !os.exists(srcDir) || !os.isDir(srcDir) then
    System.err.println(s"Error: $srcArg is not a directory"); System.exit(1)
  os.makeDir.all(outDir)

  val files = os.list(srcDir).filter(p => os.isFile(p) && p.ext == "ssc").sorted

  var rendered = 0
  var skipped  = 0
  var failed   = 0
  // Track which output paths have already been written so the user sees
  // a warning when two source files claim the same URL (e.g. both
  // declaring `GET /`) — last write wins, but it's almost always a bug.
  val written = scala.collection.mutable.Map.empty[String, String]

  for file <- files do
    Routes.clear()
    val nullOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream)
    val interp  = Interpreter(out = nullOut, baseDir = Some(file / os.up), headless = true)
    val ok =
      try { interp.run(scalascript.parser.Parser.parse(os.read(file))); true }
      catch case e: Exception =>
        System.err.println(s"  [fail] ${file.last}: ${e.getMessage}")
        failed += 1
        false
    if ok then
      val literalGets = Routes.all.filter { e =>
        e.method == "GET" && e.pathPattern.forall(_.isInstanceOf[Routes.Segment.Literal])
      }
      if literalGets.isEmpty then
        skipped += 1
      else
        for entry <- literalGets do
          val req    = syntheticRequest("GET", entry.path, Map.empty)
          val result = entry.interpreter.invoke(entry.handler, List(req))
          val body   = extractResponseBody(result)
          val out    = outPathFor(outDir, entry.path)
          os.makeDir.all(out / os.up)
          val key = out.toString
          written.get(key).foreach { prior =>
            System.err.println(
              s"  [warn] $key overwritten by ${file.last} ${entry.path} (was ${prior})"
            )
          }
          os.write.over(out, body)
          written(key) = s"${file.last} ${entry.path}"
          println(s"  ${file.last} ${entry.path} → ${displayPath(out)}")
          rendered += 1

  println()
  println(s"Done: $rendered rendered, $skipped skipped, $failed failed")
  if failed > 0 then System.exit(1)

private def displayPath(p: os.Path): String =
  val cwd = os.pwd.toString
  val s   = p.toString
  if s.startsWith(cwd + "/") then s.substring(cwd.length + 1) else s

private def outPathFor(outDir: os.Path, urlPath: String): os.Path =
  val clean = urlPath.stripPrefix("/").stripSuffix("/")
  if clean.isEmpty then outDir / "index.html"
  else if clean.endsWith(".html") then outDir / os.SubPath(clean)
  else
    val segs = clean.split('/').toIndexedSeq
    val withExt = segs.init :+ (segs.last + ".html")
    outDir / os.SubPath(withExt.mkString("/"))

def parseCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then println(s"Error: File not found: $file")
    else
      println(s"=== Parsing: $file ===")
      try   printModule(Parser.parse(os.read(path)))
      catch case e: Exception => println(s"Parse error: ${e.getMessage}")

def runCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try   Interpreter.run(Parser.parse(os.read(path)), baseDir = Some(path / os.up))
      catch case e: Exception =>
        System.err.println(s"Runtime error: ${e.getMessage}")
        System.exit(1)

def replCommand(@annotation.unused args: List[String]): Unit =
  import scala.io.StdIn
  val interp = Interpreter()
  interp.run(Parser.parse("# REPL\n"))   // initialise builtins, no code runs
  System.err.println("ScalaScript REPL  (blank line to run, :quit to exit)")
  var running = true
  while running do
    Option(StdIn.readLine("ssc> ")) match
      case None                          => running = false
      case Some(":quit" | ":q" | ":exit") => running = false
      case Some(first) =>
        val lines = scala.collection.mutable.ArrayBuffer(first)
        var more = true
        while more do
          Option(StdIn.readLine("   | ")) match
            case None | Some("") => more = false
            case Some(next)      => lines += next
        val code = lines.mkString("\n").trim
        if code.nonEmpty then
          try   interp.runSnippet(code)
          catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")

def watchCommand(args: List[String]): Unit =
  import java.nio.file.{FileSystems, Paths, StandardWatchEventKinds}
  import scala.jdk.CollectionConverters.*
  if args.isEmpty then { println("Error: No file specified"); System.exit(1) }
  val file    = args.head
  val absPath = Paths.get(file).toAbsolutePath.normalize
  val dir     = absPath.getParent

  def runOnce(): Unit =
    try   Interpreter.run(Parser.parse(os.read(os.Path(absPath))), baseDir = Some(os.Path(absPath.getParent)))
    catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")

  runOnce()
  val watcher = FileSystems.getDefault.newWatchService()
  dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
  System.err.println(s"Watching ${absPath.getFileName}... (Ctrl+C to stop)")
  while true do
    val key     = watcher.take()
    val changed = key.pollEvents().asScala.exists { ev =>
      ev.context().asInstanceOf[java.nio.file.Path].getFileName == absPath.getFileName
    }
    if changed then
      System.err.println(s"\n--- ${absPath.getFileName} ---")
      runOnce()
    key.reset()

def emitJsCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path    = os.Path(file, os.pwd)
    val baseDir = Some(path / os.up)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module   = Parser.parse(os.read(path))
        val segments = JsGen.generateSegmented(module, baseDir)
        val hasSSBlocks = segments.exists(_.isInstanceOf[JsGen.Segment.ScalaScriptJs])
        if hasSSBlocks then println(JsRuntime)
        for seg <- segments do seg match
          case JsGen.Segment.ScalaScriptJs(code) =>
            println(code)
            // Flush the ScalaScript output buffer before the next Scala.js segment runs
            println("""process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];""")
          case JsGen.Segment.ScalaSource(src) =>
            val bundle = ScalaJsBackend.compileSourceToJs(src, baseDir)
            if bundle.nonEmpty then println(bundle)
      catch case e: Exception =>
        System.err.println(s"JS generation error: ${e.getMessage}")
        System.exit(1)

def emitSpaCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path    = os.Path(file, os.pwd)
    val baseDir = Some(path / os.up)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module   = Parser.parse(os.read(path))
        val segments = JsGen.generateSegmented(module, baseDir)
        val title    = module.manifest.flatMap(_.name).getOrElse(path.last.stripSuffix(".ssc"))
        // Concatenate user JS — same segment loop as emit-js but no
        // process.stdout flushes (browser-only output goes to console).
        val userJs = segments.collect {
          case JsGen.Segment.ScalaScriptJs(code) => code
          case JsGen.Segment.ScalaSource(src)    =>
            ScalaJsBackend.compileSourceToJs(src, baseDir)
        }.filter(_.nonEmpty).mkString("\n")
        println(s"""<!doctype html>
                   |<html lang="en">
                   |<head>
                   |  <meta charset="utf-8">
                   |  <meta name="viewport" content="width=device-width, initial-scale=1">
                   |  <title>$title</title>
                   |</head>
                   |<body>
                   |<script>
                   |$JsRuntime
                   |$JsRuntimeBrowserPatch
                   |$userJs
                   |</script>
                   |</body>
                   |</html>""".stripMargin)
      catch case e: Exception =>
        System.err.println(s"SPA generation error: ${e.getMessage}")
        System.exit(1)

def emitScalaCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try   println(JvmGen.generate(Parser.parse(os.read(path)), Some(path / os.up)))
      catch case e: Exception =>
        System.err.println(s"Scala generation error: ${e.getMessage}")
        System.exit(1)

def compileCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        val script = JvmGen.generate(module, Some(path / os.up))
        val tmp    = os.temp(script, suffix = ".sc", deleteOnExit = true)
        try
          val result = os.proc("scala-cli", "run", tmp).call(
            stdout = os.Inherit,
            stderr = os.Inherit,
            check  = false
          )
          if result.exitCode != 0 then System.exit(result.exitCode)
        finally
          os.remove(tmp)
      catch case e: Exception =>
        System.err.println(s"Compile error: ${e.getMessage}")
        System.exit(1)

def packageCommand(args: List[String]): Unit =
  // Separate the .ssc input file from scala-cli flags
  val (sscFiles, scalaCliFlags) = args.partition(_.endsWith(".ssc"))
  if sscFiles.isEmpty then
    System.err.println("Error: No .ssc file specified")
    System.err.println("Usage: ssc package [scala-cli-package-flags] <file.ssc>")
    System.exit(1)
  for file <- sscFiles do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { System.err.println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        val script = JvmGen.generate(module, Some(path / os.up))
        val tmp    = os.temp(script, suffix = ".sc")
        try
          // Default output name: same as input file without .ssc extension
          val hasOutput = scalaCliFlags.exists(f => f == "-o" || f == "--output")
          val outputFlags =
            if hasOutput then Nil
            else List("--output", path.last.stripSuffix(".ssc"))
          val result = os.proc(
            "scala-cli", "--power", "package", tmp,
            outputFlags,
            scalaCliFlags
          ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = os.pwd, check = false)
          if result.exitCode != 0 then System.exit(result.exitCode)
        finally
          os.remove(tmp)
      catch case e: Exception =>
        System.err.println(s"Package error: ${e.getMessage}")
        System.exit(1)

def checkCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  var hasErrors = false
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); hasErrors = true }
    else
      println(s"=== Type checking: $file ===")
      try
        val module = Parser.parse(os.read(path))
        val typed  = Typer.typeCheck(module)
        if typed.hasErrors then
          hasErrors = true
          typed.errors.foreach(e => println(s"  Error: ${e.msg}"))
        else
          println("OK")
          println(typed.show)
      catch case e: Exception =>
        hasErrors = true
        println(s"Error: ${e.getMessage}")
  if hasErrors then System.exit(1)

def printModule(module: Module): Unit =
  println("Module:")
  module.manifest.foreach { m =>
    println(s"  name:    ${m.name.getOrElse("<none>")}")
    println(s"  version: ${m.version.getOrElse("<none>")}")
    if m.exports.nonEmpty      then println(s"  exports: ${m.exports.mkString(", ")}")
    if m.dependencies.nonEmpty then println(s"  deps:    ${m.dependencies.keys.mkString(", ")}")
  }
  module.sections.foreach(s => printSection(s, 1))

def printSection(s: Section, indent: Int): Unit =
  val prefix = "  " * indent
  println(s"$prefix${"#" * s.heading.level} ${s.heading.text}")
  s.content.foreach {
    case Content.CodeBlock(lang, src, tree, _) =>
      val lines  = src.linesIterator.length
      val status = tree.map(_ => "parsed").getOrElse(
        if Lang.isParseable(lang) then "PARSE ERROR" else "untyped"
      )
      println(s"$prefix  [code:$lang  $lines lines  $status]")
    case Content.Import(path, bindings, _) =>
      println(s"$prefix  [import $path (${bindings.map(_.name).mkString(", ")})]")
    case Content.Prose(text, _) =>
      println(s"$prefix  [prose: ${text.take(60).replace("\n", " ")}]")
    case Content.DataList(items, ordered, _) =>
      println(s"$prefix  [list: ${items.size} items, ordered=$ordered]")
  }
  s.subsections.foreach(sub => printSection(sub, indent + 1))
