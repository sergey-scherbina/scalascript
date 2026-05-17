package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
// Stage 5.4 will phase these direct imports out via HTTP intrinsics +
// concrete ir.Value bridging.  Until then, render / build / serve / repl
// commands need Interpreter + JsRuntime preamble strings directly;
// emit-spa needs ScalaJsBackend.compileSourceToJs for per-segment
// Scala source compilation.
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsRuntime, JsRuntimeAsync, JsRuntimeBrowserPatch, ScalaJsBackend}
import scalascript.ast.*
import scalascript.transform.Normalize
import scalascript.plugin.BackendRegistry
import scalascript.backend.spi.{BackendOptions, CompileResult, Segment}

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
    case "bundle"              => bundleCommand(args.tail.toList)
    case "help" | "--help" | "-h" => printUsage()
    case "--list-backends"     => println(BackendRegistry.describe)
    case _                     => runCommand(args.toList)

// ─── Backend dispatch helpers (Stage 5.3) ─────────────────────────────

private def resolveBackend(id: String): scalascript.backend.spi.Backend =
  BackendRegistry.lookup(id).getOrElse {
    System.err.println(
      s"Unknown backend: '$id'. Available: ${BackendRegistry.all.map(_.id).mkString(", ")}"
    )
    System.exit(2)
    throw new RuntimeException("unreachable")
  }

private def compileViaBackend(
    backendId: String,
    file:      os.Path,
    extras:    Map[String, String] = Map.empty
): CompileResult =
  val module = Parser.parse(os.read(file))
  val ir     = Normalize(module)
  val opts   = BackendOptions(
    baseDir = Some((file / os.up).toNIO),
    extra   = extras
  )
  resolveBackend(backendId).compile(ir, opts)

private def expectText(r: CompileResult, what: String): String = r match
  case CompileResult.TextOutput(code, _, _) => code
  case CompileResult.Failed(diags) =>
    diags.foreach(d => System.err.println(s"[error] $d"))
    System.exit(1); ""
  case other =>
    System.err.println(s"$what: unexpected result ${other.getClass.getSimpleName}")
    System.exit(1); ""

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
    |  bundle                  Pack one or more .ssc files + their transitive .ssc
    |                         imports into a .sscpkg zip archive.  External imports
    |                         (above the entry directory) are flattened into
    |                         `_external/` with path references rewritten.
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

  // Directories that hold non-page `.ssc` content (imported modules,
  // tooling output, caches) — recursed for assets but never walked for
  // page rendering.  `components` is the convention for module imports;
  // the others are standard build / tool dirs.
  val skipDirs = Set("components", "target", "node_modules", "dist", "out", ".scala-build")
  def isSkipped(p: os.Path): Boolean =
    skipDirs.contains(p.last) || p.last.startsWith(".")

  // Recursive walk for `.ssc` files; subdirectories are walked into
  // *unless* they're in the skip set, so a layout like
  //     src/pages/index.ssc
  //     src/pages/blog/post.ssc
  //     src/components/card.ssc       (skipped — non-page)
  // produces  dist/pages/index.html and dist/pages/blog/post.html
  // (component module is imported by the pages, not rendered).
  val files = os.walk(srcDir, skip = isSkipped)
    .filter(p => os.isFile(p) && p.ext == "ssc")
    .sorted

  var rendered = 0
  var skipped  = 0
  var failed   = 0
  // Track which output paths have already been written so the user sees
  // a warning when two source files claim the same URL (e.g. both
  // declaring `GET /`) — last write wins, but it's almost always a bug.
  val written = scala.collection.mutable.Map.empty[String, String]

  for file <- files do
    // Display path is relative to srcDir so nested page files stay
    // legible (`pages/blog/post.ssc` rather than just `post.ssc`).
    val srcRel = file.relativeTo(srcDir).toString
    Routes.clear()
    val nullOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream)
    val interp  = Interpreter(out = nullOut, baseDir = Some(file / os.up), headless = true)
    val ok =
      try { interp.run(scalascript.parser.Parser.parse(os.read(file))); true }
      catch case e: Exception =>
        System.err.println(s"  [fail] $srcRel: ${e.getMessage}")
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
              s"  [warn] $key overwritten by $srcRel ${entry.path} (was ${prior})"
            )
          }
          os.write.over(out, body)
          written(key) = s"$srcRel ${entry.path}"
          println(s"  $srcRel ${entry.path} → ${displayPath(out)}")
          rendered += 1

  // Asset pipeline: mirror every non-.ssc file under `src-dir` into
  // `out-dir`, preserving relative paths.  Walks recursively so
  // subdirectory assets (icons under `assets/`, fonts under `fonts/`,
  // etc.) make it to the build.  `components/` IS walked here — a
  // component module may sit next to its icon — even though the page
  // walker skips it.  Tooling / build-output dirs are skipped.
  val assetSkipDirs = Set("target", "node_modules", "dist", "out", ".scala-build")
  var copied = 0
  os.walk(srcDir, skip = p => assetSkipDirs.contains(p.last) || p.last.startsWith(".")).foreach { p =>
    if os.isFile(p) && p.ext != "ssc" then
      val rel  = p.relativeTo(srcDir)
      val dest = outDir / rel
      os.makeDir.all(dest / os.up)
      os.copy.over(p, dest)
      copied += 1
  }

  println()
  println(s"Done: $rendered rendered, $skipped skipped, $copied assets, $failed failed")
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

/** `ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg]` — packs
 *  one or more `.ssc` entry files together with every transitively-
 *  imported `.ssc` into a zip archive (`.sscpkg`).  A consumer
 *  unzips and uses the entries with relative imports.
 *
 *  Archive layout:
 *    bundle.yaml                 metadata: entries, transitives, name/version
 *    <entry>.ssc                 each entry at the archive root
 *    <relative paths>/...        every imported file under its
 *                                 path relative to the bundle root
 *    _external/<basename>        files that lived ABOVE the bundle
 *                                 root in the source tree — flattened
 *                                 here; references inside the bundle
 *                                 are rewritten so the archive is
 *                                 self-contained.
 *
 *  The bundle root is the common parent directory of every entry's
 *  parent.  Inside-root files keep their relative path; outside-root
 *  files get flattened.
 */
def bundleCommand(args: List[String]): Unit =
  import scalascript.parser.Parser
  import java.util.zip.{ZipOutputStream, ZipEntry}

  // ─── Argument parsing ─────────────────────────────────────────
  var output: Option[String] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => output = Some(it.next())
      case f => files += f
  if files.isEmpty then
    System.err.println("Usage: ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg]")
    System.exit(1)

  val entryPaths = files.toList.map { f =>
    val p = os.Path(f, os.pwd)
    if !os.exists(p) then
      System.err.println(s"Error: $f not found"); System.exit(1)
    if p.ext != "ssc" then
      System.err.println(s"Error: $f is not a .ssc file"); System.exit(1)
    p
  }

  // Bundle root: the common parent directory of every entry's parent.
  // Single entry → its parent dir.  Two entries in the same folder →
  // that folder.  Two entries in sibling folders → their common parent.
  val bundleRoot = commonAncestor(entryPaths.map(_ / os.up))

  // ─── Transitive walk ──────────────────────────────────────────
  // archivePath[abs] = path the file gets under inside the zip.
  // externalNames    = basenames already taken under `_external/`.
  val archivePath    = scala.collection.mutable.LinkedHashMap.empty[os.Path, String]
  val externalNames  = scala.collection.mutable.Set.empty[String]

  def assignPath(abs: os.Path): String =
    if archivePath.contains(abs) then archivePath(abs)
    else if abs.startsWith(bundleRoot) then
      val p = abs.relativeTo(bundleRoot).toString
      archivePath(abs) = p; p
    else
      var name = abs.last
      var n    = 1
      while externalNames.contains(name) do
        name = abs.last.stripSuffix(".ssc") + "_" + n + ".ssc"
        n   += 1
      externalNames += name
      val p = s"_external/$name"
      archivePath(abs) = p; p

  def visit(file: os.Path): Unit =
    if archivePath.contains(file) then return
    val _ = assignPath(file)
    val module = Parser.parse(os.read(file))
    val imports = scala.collection.mutable.ArrayBuffer.empty[scalascript.ast.Content.Import]
    def gatherImports(secs: List[scalascript.ast.Section]): Unit =
      secs.foreach { s =>
        s.content.foreach {
          case imp: scalascript.ast.Content.Import => imports += imp
          case _ => ()
        }
        gatherImports(s.subsections)
      }
    gatherImports(module.sections)
    imports.foreach { imp =>
      val resolved = (file / os.up) / os.RelPath(imp.path)
      if os.exists(resolved) then visit(resolved)
      else System.err.println(s"  [warn] import ${imp.path} from ${file.last} — not found, skipped")
    }

  entryPaths.foreach(visit)

  // ─── Rewrite import paths to match the new archive layout ─────
  //
  // For every `.ssc` we packed: re-scan its imports.  If the resolved
  // target's archive path doesn't match the original source path the
  // import wrote, splice the new one into the file content before
  // writing it to the zip.  We rewrite via a `](old)→](new)` Markdown
  // edit so it's localised to the link destination.
  def rewriteImports(file: os.Path): String =
    val raw = os.read(file)
    val module = Parser.parse(raw)
    val imports = scala.collection.mutable.ArrayBuffer.empty[scalascript.ast.Content.Import]
    def gather(secs: List[scalascript.ast.Section]): Unit =
      secs.foreach { s =>
        s.content.foreach {
          case imp: scalascript.ast.Content.Import => imports += imp
          case _ => ()
        }
        gather(s.subsections)
      }
    gather(module.sections)
    var out = raw
    imports.foreach { imp =>
      val resolved = (file / os.up) / os.RelPath(imp.path)
      if archivePath.contains(resolved) then
        val targetArchive = archivePath(resolved)
        val ownArchive    = archivePath(file)
        // Relative path FROM the importing file's own archive dir
        // TO the imported file's archive location.
        val ownDir = if ownArchive.contains('/') then ownArchive.substring(0, ownArchive.lastIndexOf('/')) else ""
        val rel    = relativeArchivePath(ownDir, targetArchive)
        if rel != imp.path then
          // Markdown link: `](OLD)` → `](NEW)`.  Quote the regex.
          val pat = java.util.regex.Pattern.quote("](" + imp.path + ")")
          out = out.replaceAll(pat, java.util.regex.Matcher.quoteReplacement("](" + rel + ")"))
    }
    out

  // ─── Write the zip ────────────────────────────────────────────
  val outName =
    output.getOrElse(
      if entryPaths.length == 1 then entryPaths.head.last.stripSuffix(".ssc") + ".sscpkg"
      else "bundle.sscpkg"
    )
  val outPath = os.Path(outName, os.pwd)
  os.makeDir.all(outPath / os.up)

  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    // bundle.yaml manifest
    val manifest = new StringBuilder
    manifest.append("# ScalaScript bundle manifest\n")
    manifest.append("entries:\n")
    entryPaths.foreach { e => manifest.append(s"  - ${archivePath(e)}\n") }
    manifest.append("transitive:\n")
    archivePath.values.toList.sorted.foreach { p => manifest.append(s"  - $p\n") }
    zip.putNextEntry(new ZipEntry("bundle.yaml"))
    zip.write(manifest.toString.getBytes("UTF-8"))
    zip.closeEntry()
    // .ssc files
    archivePath.toList.sortBy(_._2).foreach { case (file, archive) =>
      zip.putNextEntry(new ZipEntry(archive))
      zip.write(rewriteImports(file).getBytes("UTF-8"))
      zip.closeEntry()
    }
  finally zip.close()

  val entryList = entryPaths.map(archivePath).mkString(", ")
  val external  = archivePath.values.count(_.startsWith("_external/"))
  println(s"$outName  (${archivePath.size} files, $external external) — entries: $entryList")

/** Common-ancestor directory of a non-empty list of paths.
 *  Same as `paths.head`'s parents, narrowed to whichever still
 *  starts every other path. */
private def commonAncestor(paths: List[os.Path]): os.Path =
  paths match
    case Nil          => os.pwd
    case List(single) => single
    case _ =>
      val segs = paths.map(_.segments.toList)
      val zipped = segs.transpose
      val shared = zipped.takeWhile(s => s.distinct.length == 1).map(_.head)
      os.root / os.SubPath(shared.mkString("/"))

/** Path of `target` (archive-relative, e.g. `components/card.ssc`)
 *  expressed RELATIVELY to `fromDir` (also archive-relative, e.g.
 *  `blog`).  Yields the right `./..` form for re-writing
 *  Markdown-import paths into a self-contained archive. */
private def relativeArchivePath(fromDir: String, target: String): String =
  val from = if fromDir.isEmpty then Array.empty[String] else fromDir.split('/')
  val to   = target.split('/')
  var i = 0
  while i < from.length && i < to.length && from(i) == to(i) do i += 1
  val ups   = "../" * (from.length - i)
  val downs = to.drop(i).mkString("/")
  val rel   = ups + downs
  // Markdown import path is the destination of a link; "./" prefix is
  // optional but matches what the user typically writes for siblings.
  if rel.startsWith("../") || rel.contains("/") then rel else "./" + rel

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
      try
        compileViaBackend("int", path) match
          case CompileResult.Executed(_, _, 0)    => ()
          case CompileResult.Executed(_, _, exit) => System.exit(exit)
          case CompileResult.Failed(diags)        =>
            diags.foreach(d => System.err.println(s"[error] $d")); System.exit(1)
          case other =>
            System.err.println(s"Unexpected result: ${other.getClass.getSimpleName}")
            System.exit(1)
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
    try compileViaBackend("int", os.Path(absPath))
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
        val segments = compileViaBackend("js", path, Map("mode" -> "segmented")) match
          case CompileResult.Segmented(segs) => segs
          case CompileResult.Failed(diags) =>
            diags.foreach(d => System.err.println(s"[error] $d")); System.exit(1); Nil
          case other =>
            System.err.println(s"emit-js: unexpected ${other.getClass.getSimpleName}")
            System.exit(1); Nil
        val hasSSBlocks = segments.exists {
          case Segment.Code("javascript", _) => true
          case _                             => false
        }
        if hasSSBlocks then { println(JsRuntime); println(JsRuntimeAsync) }
        for seg <- segments do seg match
          case Segment.Code("javascript", code) =>
            println(code)
            // Flush the ScalaScript output buffer before the next Scala.js segment runs
            println("""process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];""")
          case Segment.Source("scala", src) =>
            val bundle = ScalaJsBackend.compileSourceToJs(src, baseDir)
            if bundle.nonEmpty then println(bundle)
          case _ => ()
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
        val module = Parser.parse(os.read(path))
        val segments = compileViaBackend("js", path, Map("mode" -> "segmented")) match
          case CompileResult.Segmented(segs) => segs
          case CompileResult.Failed(diags) =>
            diags.foreach(d => System.err.println(s"[error] $d")); System.exit(1); Nil
          case other =>
            System.err.println(s"emit-spa: unexpected ${other.getClass.getSimpleName}")
            System.exit(1); Nil
        val title    = module.manifest.flatMap(_.name).getOrElse(path.last.stripSuffix(".ssc"))
        // Concatenate user JS — same segment loop as emit-js but no
        // process.stdout flushes (browser-only output goes to console).
        val userJs = segments.collect {
          case Segment.Code("javascript", code) => code
          case Segment.Source("scala", src)     =>
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
                   |$JsRuntimeAsync
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
      try   println(expectText(compileViaBackend("jvm", path), "emit-scala"))
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
        val script = expectText(compileViaBackend("jvm", path), "compile")
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
        val script = expectText(compileViaBackend("jvm", path), "package")
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
