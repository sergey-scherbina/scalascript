package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
// Stage 5.4 will phase these direct imports out via HTTP intrinsics +
// concrete ir.Value bridging.  Until then, render / build / serve / repl
// commands need Interpreter + JsRuntime preamble strings directly;
// emit-spa needs ScalaJsBackend.compileSourceToJs for per-segment
// Scala source compilation.
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync, JsRuntimeV14Effects, JsRuntimeBrowserPatch, JsRuntimeMcp, JsRuntimeDataset, ScalaJsBackend}
import scalascript.ast.*
import scalascript.transform.Normalize
import scalascript.validate.CapabilityCheck
import scalascript.plugin.{BackendRegistry, SourceLanguageRegistry}
import scalascript.backend.spi.{BackendOptions, CompileResult, Segment}
// v2.0 separate-compilation artifact commands
import scalascript.artifact.{InterfaceExtractor, ArtifactIO}

@main def ssc(rawArgs: String*): Unit =
  // Strip global plugin-management flags from anywhere in the
  // argument list before dispatching to a command.  --plugin and
  // --plugin-dir mutate BackendRegistry; --target / --backend are
  // captured into thread-local state for command handlers.
  val (globalFlags, args) = GlobalFlags.parse(rawArgs.toList)
  globalFlags.applyToRegistry()

  // Standalone meta-commands that don't dispatch to a command handler.
  if globalFlags.listBackends then
    println(BackendRegistry.describe)
  else if globalFlags.listSourceLanguages then
    println(SourceLanguageRegistry.describe)
  else if globalFlags.describeBackend.isDefined then
    val id = globalFlags.describeBackend.get
    BackendRegistry.lookup(id) match
      case Some(b) =>
        println(s"id:          ${b.id}")
        println(s"displayName: ${b.displayName}")
        println(s"spiVersion:  ${b.spiVersion}")
        println(s"acceptedSources: ${b.acceptedSources.mkString(", ")}")
        println(s"capabilities.features: ${b.capabilities.features.toList.sortBy(_.toString).mkString(", ")}")
        println(s"capabilities.outputs:  ${b.capabilities.outputs.toList.sortBy(_.toString).mkString(", ")}")
        println(s"intrinsics:  ${b.intrinsics.size} registered")
      case None =>
        System.err.println(s"Unknown backend: $id")
        System.exit(2)
  else if args.isEmpty then
    printUsage()
    System.exit(1)
  else dispatchCommand(args)

private def dispatchCommand(args: List[String]): Unit =
  args.head match
    case "parse"               => parseCommand(args.tail)
    case "check"               => checkCommand(args.tail)
    case "run"                 => runCommand(args.tail)
    case "watch"               => watchCommand(args.tail)
    case "repl"                => replCommand(args.tail)
    case "emit-js"             => emitJsCommand(args.tail)
    case "emit-spa"            => emitSpaCommand(args.tail)
    case "emit-scala"          => emitScalaCommand(args.tail)
    case "emit-wc"             => emitWcCommand(args.tail)
    // v2.0 separate-compilation commands
    case "emit-interface"      => emitInterfaceCommand(args.tail)
    case "emit-ir"             => emitIrCommand(args.tail)
    case "check-with-iface"    => checkWithInterfaceCommand(args.tail)
    case "link"                => linkCommand(args.tail)
    case "compile"             => compileCommand(args.tail)
    case "package"             => packageCommand(args.tail)
    case "serve"               => serveCommand(args.tail)
    case "render"              => renderCommand(args.tail)
    case "build"               => buildCommand(args.tail)
    case "bundle"              => bundleCommand(args.tail)
    case "plugin"              => pluginCommand(args.tail)
    case "lock"                => lockCommand(args.tail)
    case "test"                => testCommand(args.tail)
    case "preview"             => previewCommand(args.tail)
    case "help" | "--help" | "-h" => printUsage()
    case "--list-backends"     => println(BackendRegistry.describe)
    case _                     => runCommand(args)

/** Global, non-command-specific CLI flags. */
case class GlobalFlags(
    pluginJars:          List[os.Path] = Nil,
    pluginDirs:          List[os.Path] = Nil,
    target:              Option[String] = None,
    backend:             Option[String] = None,
    listBackends:        Boolean        = false,
    listSourceLanguages: Boolean        = false,
    describeBackend:     Option[String] = None
):
  def applyToRegistry(): Unit =
    pluginJars.foreach(BackendRegistry.addPluginJar)
    pluginJars.foreach(SourceLanguageRegistry.addPluginJar)
    pluginDirs.foreach(BackendRegistry.addPluginDir)

object GlobalFlags:
  /** Walk the arg list once, consuming any global flag we recognise
   *  and leaving everything else for the command handler. */
  def parse(args: List[String]): (GlobalFlags, List[String]) =
    var flags = GlobalFlags()
    val rest  = scala.collection.mutable.ListBuffer.empty[String]
    var i     = 0
    val arr   = args.toArray
    while i < arr.length do
      arr(i) match
        case "--plugin" if i + 1 < arr.length =>
          flags = flags.copy(pluginJars = flags.pluginJars :+ os.Path(arr(i + 1), os.pwd)); i += 2
        case "--plugin-dir" if i + 1 < arr.length =>
          flags = flags.copy(pluginDirs = flags.pluginDirs :+ os.Path(arr(i + 1), os.pwd)); i += 2
        case "--target" if i + 1 < arr.length =>
          flags = flags.copy(target = Some(arr(i + 1))); i += 2
        case "--backend" if i + 1 < arr.length =>
          flags = flags.copy(backend = Some(arr(i + 1))); i += 2
        case "--list-backends" =>
          flags = flags.copy(listBackends = true); i += 1
        case "--list-source-languages" =>
          flags = flags.copy(listSourceLanguages = true); i += 1
        case "--describe-backend" if i + 1 < arr.length =>
          flags = flags.copy(describeBackend = Some(arr(i + 1))); i += 2
        case other =>
          rest += other; i += 1
    // Stash for handlers that consult the flag (via ActiveFlags).
    ActiveFlags.set(flags)
    (flags, rest.toList)

/** Per-process snapshot of GlobalFlags so command handlers can read
 *  e.g. `ActiveFlags.current.backend` without threading it through
 *  every function. */
object ActiveFlags:
  @volatile private var snapshot: GlobalFlags = GlobalFlags()
  def set(f: GlobalFlags): Unit = snapshot = f
  def current: GlobalFlags      = snapshot

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
  val module  = Parser.parse(os.read(file))
  val ir      = Normalize(module)
  val backend = resolveBackend(backendId)
  val diags   = CapabilityCheck.validate(ir, backend.capabilities, backendId)
  if diags.nonEmpty then CompileResult.Failed(diags)
  else
    val opts = BackendOptions(
      baseDir = Some((file / os.up).toNIO),
      extra   = extras
    )
    backend.compile(ir, opts)

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
    |  build                  Batch-render every .ssc in a directory (or --incremental
    |                         for separate-compilation artifact build).  Each file's
    |                         literal GET routes become files under <out-dir>
    |                         (default `dist/`); `/` → index.html, `/about` →
    |                         about.html.  Files without GET routes are skipped.
    |  bundle                  Pack one or more .ssc files + their transitive .ssc
    |                         imports into a .sscpkg zip archive.  External imports
    |                         (above the entry directory) are flattened into
    |                         `_external/` with path references rewritten.
    |  plugin <sub>           Manage installed .sscpkg plugins:
    |    install <path|name>    Install a .sscpkg from a local path, HTTPS URL, or registry name
    |    list                   List installed plugins
    |    uninstall <id>         Remove an installed plugin by id
    |    check <id>             Verify SPI-version compatibility
    |    pack <dir>             Pack a plugin source tree into a .sscpkg archive
    |    registry list          List entries in ~/.scalascript/registry.yaml
    |    registry add <id> <url> [desc]  Add/update a registry entry
    |    registry remove <id>   Remove a registry entry
    |    registry search <q>    Search registry by id or description
    |  run                    Execute .ssc via tree-walking interpreter (default)
    |  watch                  Run .ssc and re-run on every file change
    |  repl                   Start interactive REPL (blank line runs, :quit exits)
    |  compile                Compile and run .ssc on JVM via scala-cli
    |  package [flags] <f>    Package .ssc via scala-cli package (see flags below)
    |  emit-scala             Print generated Scala 3 script to stdout
    |  emit-js                Transpile .ssc to JavaScript (Node server) and print to stdout
    |  emit-spa               Wrap .ssc as a browser SPA (HTML + embedded JS) and print to stdout
    |  emit-wc                Emit each component object as a W3C Custom Element bundle
    |  emit-interface         Extract module interface to .scim artifact (v2.0)
    |  emit-ir                Emit normalised module IR to .scir artifact (v2.0)
    |  check-with-iface       Type-check .ssc consuming pre-compiled .scim interfaces (v2.0)
    |  link                   Link .scim/.scir artifact pairs into a merged module (v2.0)
    |  serve                  Start HTTP server serving .ssc files as web pages
    |  parse                  Parse .ssc files and print AST
    |  check                  Type-check .ssc files
    |  test <file(s)>         Run component unit tests — each test is (name, () => Boolean).
    |                         Prints PASS/FAIL per test; exits non-zero on any failure.
    |                         Tests are functions registered with test(name, thunk) in the
    |                         file, or in a sibling *-test.ssc file.
    |  preview <file>         Open a browser preview page showing each component variant
    |                         declared in the front-matter variants: list.  Storybook-lite.
    |  lock <file>            Pin all URL/dep imports in ssc.lock (SHA-256 integrity)
    |  lock check <file>      Verify all URL/dep imports match ssc.lock
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
    |  ssc emit-wc  examples/wc-card.ssc  > card.js   # use as <card-component title="…">…</card-component>
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
  import scalascript.interpreter.Interpreter
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

// ─────────────────────────────────────────────────────────────────────────────
// ssc build --incremental  —  v2.0 separate compilation build orchestrator
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc build --incremental <src-dir> [--artifact-dir <dir>]`
 *
 *  Walks `src-dir` for `.ssc` files, builds the dependency graph via
 *  `ModuleGraph`, and recompiles only stale modules (i.e. those whose source
 *  hash has changed or whose `.scim` / `.scir` artifacts don't exist yet).
 *
 *  Artifacts are written to `--artifact-dir` (default `<src-dir>/.ssc-artifacts/`).
 *
 *  Cycle detection: if the dependency graph contains a cycle the build errors
 *  immediately listing the cyclic files.
 *
 *  After building, the artifacts can be linked via `ssc link <artifact-dir>`.
 *
 *  v2.0 / Stage 6.
 */
def incrementalBuildCommand(args: List[String]): Unit =
  import scalascript.artifact.{ModuleGraph, InterfaceExtractor, ArtifactIO}
  import scalascript.transform.Normalize

  var artifactDirArg: Option[String] = None
  var srcDirArg:      Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--artifact-dir" if it.hasNext => artifactDirArg = Some(it.next())
      case d => srcDirArg = Some(d)

  if srcDirArg.isEmpty then
    System.err.println("Usage: ssc build --incremental <src-dir> [--artifact-dir <dir>]")
    System.exit(1)

  val srcDir     = os.Path(srcDirArg.get, os.pwd)
  val artDir     = artifactDirArg.map(os.Path(_, os.pwd)).getOrElse(srcDir / ".ssc-artifacts")

  if !os.isDir(srcDir) then
    System.err.println(s"build --incremental: '$srcDir' is not a directory"); System.exit(1)

  os.makeDir.all(artDir)

  // Build dependency graph.
  val graph = ModuleGraph.build(srcDir)

  if graph.cycles.nonEmpty then
    System.err.println("build --incremental: circular dependencies detected:")
    graph.cycles.foreach { cycle =>
      System.err.println("  " + cycle.map(p => p.relativeTo(srcDir).toString).mkString(" → "))
    }
    System.exit(1)

  val nodes = graph.orderedNodes
  println(s"Discovered ${nodes.length} module(s) in ${srcDir.relativeTo(os.pwd)}")

  var compiled = 0
  var skipped  = 0
  var failed   = 0

  for node <- nodes do
    val relPath  = node.relPath(srcDir)
    val baseName = node.path.last.stripSuffix(".ssc")
    val stale    = ModuleGraph.isStale(node.path, artDir)

    if !stale then
      println(s"  [skip]     $relPath (up-to-date)")
      skipped += 1
    else
      print(s"  [compile]  $relPath ... ")
      val ok = scala.util.Try {
        val sourceBytes = os.read.bytes(node.path)
        val src         = new String(sourceBytes, "UTF-8")
        val module      = Parser.parse(src)
        val ir          = Normalize(module)
        val iface       = InterfaceExtractor.extract(module, sourceBytes)
        val sourceHash  = iface.sourceHash

        val scimPath = artDir / (baseName + ".scim")
        val scirPath = artDir / (baseName + ".scir")

        ArtifactIO.writeInterfaceFile(iface, scimPath)
        ArtifactIO.writeIrFile(ir, node.pkg, module.manifest.flatMap(_.name), sourceHash, scirPath)
      }
      ok match
        case scala.util.Success(_) =>
          println("OK")
          compiled += 1
        case scala.util.Failure(e) =>
          println(s"FAIL")
          System.err.println(s"    ${e.getMessage}")
          failed += 1

  println()
  println(s"Done: $compiled compiled, $skipped up-to-date, $failed failed")
  println(s"Artifacts written to ${artDir.relativeTo(os.pwd)}")
  if failed > 0 then System.exit(1)

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
  // v2.0: --incremental flag routes to separate-compilation build orchestrator.
  if args.contains("--incremental") then
    val rest = args.filterNot(_ == "--incremental")
    incrementalBuildCommand(rest)
    return
  import scalascript.interpreter.Interpreter
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
  // backendId -> jar path pairs from --with-backend-jar backendId:path
  val backendJars = scala.collection.mutable.ArrayBuffer.empty[(String, os.Path)]
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => output = Some(it.next())
      case "--with-backend-jar" if it.hasNext =>
        val spec = it.next()
        spec.indexOf(':') match
          case -1 =>
            System.err.println(s"Error: --with-backend-jar requires backendId:path (got '$spec')")
            System.exit(1)
          case i =>
            val bid  = spec.substring(0, i)
            val path = os.Path(spec.substring(i + 1), os.pwd)
            if !os.exists(path) then
              System.err.println(s"Error: backend JAR not found: $path"); System.exit(1)
            backendJars += bid -> path
      case f => files += f
  if files.isEmpty then
    System.err.println("Usage: ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg] [--with-backend-jar backendId:path]")
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

  // Derive bundle id from output name (strip version suffix + extension if present)
  val bundleId = outName.stripSuffix(".sscpkg").replaceAll("-\\d+\\.\\d+.*$", "")
  val isHybrid = backendJars.nonEmpty
  val kind     = if isHybrid then "[library, plugin]" else "[library]"
  val targets  = if isHybrid then backendJars.map(_._1).distinct.mkString("[", ", ", "]") else "[]"

  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    // manifest.yaml (v1.7 Tier 2) — supersedes legacy bundle.yaml
    val manifestYaml = new StringBuilder
    manifestYaml.append("# ScalaScript package manifest\n")
    manifestYaml.append(s"id:         $bundleId\n")
    manifestYaml.append(s"version:    0.1.0\n")
    manifestYaml.append(s"spiVersion: \"0.1.0\"\n")
    manifestYaml.append(s"kind:       $kind\n")
    manifestYaml.append(s"targets:    $targets\n")
    manifestYaml.append("exports:\n")
    manifestYaml.append("  externDefs: []\n")
    zip.putNextEntry(new ZipEntry("manifest.yaml"))
    zip.write(manifestYaml.toString.getBytes("UTF-8"))
    zip.closeEntry()

    // sources/*.ssc — all .ssc files packed under sources/ prefix
    archivePath.toList.sortBy(_._2).foreach { case (file, archive) =>
      zip.putNextEntry(new ZipEntry("sources/" + archive))
      zip.write(rewriteImports(file).getBytes("UTF-8"))
      zip.closeEntry()
    }

    // intrinsics/*.jar — one per --with-backend-jar entry
    backendJars.foreach { case (_, jar) =>
      zip.putNextEntry(new ZipEntry("intrinsics/" + jar.last))
      zip.write(os.read.bytes(jar))
      zip.closeEntry()
    }
  finally zip.close()

  val entryList = entryPaths.map(archivePath).mkString(", ")
  val external  = archivePath.values.count(_.startsWith("_external/"))
  val jarLine   = if backendJars.isEmpty then "" else s", ${backendJars.size} intrinsic JAR(s)"
  println(s"$outName  (${archivePath.size} sources, $external external$jarLine) — entries: $entryList")

// ─────────────────────────────────────────────────────────────────────────────
// ssc plugin <subcommand>  —  v1.7 Tier 5
// ─────────────────────────────────────────────────────────────────────────────

/** Default directory where installed `.sscpkg` files live. */
private def pluginsDir: os.Path = os.home / ".scalascript" / "plugins"

def pluginCommand(args: List[String]): Unit =
  args match
    case "install"   :: rest => pluginInstall(rest)
    case "list"      :: _    => pluginList()
    case "uninstall" :: rest => pluginUninstall(rest)
    case "check"     :: rest => pluginCheck(rest)
    case "pack"      :: rest => pluginPack(rest)
    case "registry"  :: rest => pluginRegistryCommand(rest)
    case sub :: _            =>
      System.err.println(s"Unknown plugin subcommand: '$sub'")
      System.err.println("Usage: ssc plugin install|list|uninstall|check|pack|registry ...")
      System.exit(1)
    case Nil =>
      System.err.println("Usage: ssc plugin install|list|uninstall|check|pack|registry ...")
      System.exit(1)

/** `ssc plugin install <path-or-url-or-name>` — copy/download a
 *  `.sscpkg` to `~/.scalascript/plugins/` and print a confirmation.
 *  Short names (e.g. "redis") are resolved via the local registry
 *  (`~/.scalascript/registry.yaml`). */
def pluginInstall(args: List[String]): Unit =
  val rawSrc = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin install <path-or-url-or-name>"); System.exit(1); ""
  }
  // Resolve short name via local registry if it's not a path or URL.
  val src =
    if rawSrc.startsWith("http://") || rawSrc.startsWith("https://") || os.exists(os.Path(rawSrc, os.pwd)) then rawSrc
    else
      scalascript.plugin.LocalRegistry.resolve(rawSrc) match
        case Some(entry) =>
          println(s"Resolved '$rawSrc' → ${entry.url}  (${entry.description})")
          entry.url
        case None =>
          System.err.println(
            s"plugin install: '$rawSrc' is not a file, URL, or known registry entry"); System.exit(1); ""

  val bytes: Array[Byte] =
    if src.startsWith("http://") || src.startsWith("https://") then
      val req  = java.net.http.HttpRequest.newBuilder(java.net.URI.create(src)).GET().build()
      val resp = java.net.http.HttpClient.newHttpClient()
        .send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
      if resp.statusCode() != 200 then
        System.err.println(s"plugin install: HTTP ${resp.statusCode()} from $src")
        System.exit(1)
      resp.body()
    else
      val p = os.Path(src, os.pwd)
      if !os.exists(p) then
        System.err.println(s"plugin install: not found: $src"); System.exit(1)
      os.read.bytes(p)

  // Parse manifest from the archive bytes to get id + version for the filename.
  val tmp = os.temp(bytes, suffix = ".sscpkg")
  val manifest =
    try scalascript.plugin.SscpkgLoader.load(tmp).manifest
    finally os.remove(tmp)

  os.makeDir.all(pluginsDir)
  val dest = pluginsDir / s"${manifest.id}-${manifest.version}.sscpkg"
  os.write.over(dest, bytes)
  println(s"Installed ${manifest.id} ${manifest.version} → $dest")

/** `ssc plugin list` — print every `.sscpkg` in `~/.scalascript/plugins/`. */
def pluginList(): Unit =
  if !os.isDir(pluginsDir) then
    println("(no plugins installed)"); return
  val pkgs = os.list(pluginsDir).filter(_.ext == "sscpkg").sorted
  if pkgs.isEmpty then println("(no plugins installed)")
  else
    pkgs.foreach { p =>
      val m = scala.util.Try(scalascript.plugin.SscpkgLoader.load(p).manifest)
      m match
        case scala.util.Success(manifest) =>
          val kinds   = manifest.kind.mkString(", ")
          val targets = if manifest.targets.isEmpty then "" else s"  targets=${manifest.targets.mkString(",")}"
          println(f"${manifest.id}%-30s  ${manifest.version}  spi=${manifest.spiVersion}  [$kinds]$targets")
        case scala.util.Failure(e) =>
          println(s"${p.last}  [parse error: ${e.getMessage}]")
    }

/** `ssc plugin uninstall <id>` — remove the installed `.sscpkg` for that id. */
def pluginUninstall(args: List[String]): Unit =
  val id = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin uninstall <id>"); System.exit(1); ""
  }
  if !os.isDir(pluginsDir) then
    System.err.println(s"plugin uninstall: '$id' not installed"); System.exit(1)
  val pkgs = os.list(pluginsDir)
    .filter(p => p.ext == "sscpkg" && p.last.startsWith(id + "-") || p.last == id + ".sscpkg")
  if pkgs.isEmpty then
    System.err.println(s"plugin uninstall: '$id' not installed"); System.exit(1)
  pkgs.foreach { p => os.remove(p); println(s"Removed $p") }

/** `ssc plugin check <id>` — verify that the installed plugin's spiVersion
 *  matches the current compiler's supported SPI version. */
def pluginCheck(args: List[String]): Unit =
  val id = args.headOption.getOrElse {
    System.err.println("Usage: ssc plugin check <id>"); System.exit(1); ""
  }
  if !os.isDir(pluginsDir) then
    System.err.println(s"plugin check: '$id' not installed"); System.exit(1)
  val pkgs = os.list(pluginsDir)
    .filter(p => p.ext == "sscpkg" && (p.last.startsWith(id + "-") || p.last == id + ".sscpkg"))
  if pkgs.isEmpty then
    System.err.println(s"plugin check: '$id' not installed"); System.exit(1)
  pkgs.foreach { p =>
    val m = scalascript.plugin.SscpkgLoader.load(p).manifest
    val supported = "0.1.0"
    if m.spiVersion == supported then
      println(s"${m.id} ${m.version}: OK  (spiVersion=${m.spiVersion})")
    else
      println(s"${m.id} ${m.version}: INCOMPATIBLE  (plugin=${m.spiVersion}, compiler=$supported)")
      System.exit(1)
  }

/** `ssc plugin pack <dir> [-o output.sscpkg]` — pack a source tree containing
 *  a `manifest.yaml` + optional `sources/`, `runtime/`, `intrinsics/` into
 *  a `.sscpkg` archive. */
def pluginPack(args: List[String]): Unit =
  import java.util.zip.{ZipOutputStream, ZipEntry}

  var output: Option[String] = None
  var dirArg: Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => output = Some(it.next())
      case d => dirArg = Some(d)

  val dir = os.Path(dirArg.getOrElse {
    System.err.println("Usage: ssc plugin pack <dir> [-o output.sscpkg]"); System.exit(1); ""
  }, os.pwd)
  if !os.isDir(dir) then
    System.err.println(s"plugin pack: not a directory: $dir"); System.exit(1)

  val manifestPath = dir / "manifest.yaml"
  if !os.exists(manifestPath) then
    System.err.println(s"plugin pack: missing manifest.yaml in $dir"); System.exit(1)

  val manifest = scalascript.plugin.SscpkgManifest.parseString(os.read(manifestPath)).get
  val outName  = output.getOrElse(s"${manifest.id}-${manifest.version}.sscpkg")
  val outPath  = os.Path(outName, os.pwd)

  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    // Walk every file under dir and pack it, preserving the subtree.
    os.walk(dir).filter(os.isFile).foreach { file =>
      val rel = file.relativeTo(dir).toString
      zip.putNextEntry(new ZipEntry(rel))
      zip.write(os.read.bytes(file))
      zip.closeEntry()
    }
  finally zip.close()

  val fileCount = os.walk(dir).count(os.isFile)
  println(s"$outName  ($fileCount files) — id=${manifest.id} version=${manifest.version}")

/** `ssc plugin registry <sub> [args]` — manage local registry.
 *
 *  Subcommands:
 *    list                  — print all registry entries
 *    add <id> <url>        — add/update an entry in ~/.scalascript/registry.yaml
 *    remove <id>           — remove an entry
 *    search <query>        — search entries by id or description substring
 */
def pluginRegistryCommand(args: List[String]): Unit =
  import scalascript.plugin.LocalRegistry
  val regPath = os.home / ".scalascript" / "registry.yaml"
  args match
    case "list" :: _ =>
      val entries = LocalRegistry.loadAll()
      if entries.isEmpty then println("(no registry entries)")
      else entries.foreach { e =>
        val ver  = if e.version.nonEmpty then s"  v${e.version}" else ""
        val desc = if e.description.nonEmpty then s"  — ${e.description}" else ""
        println(f"${e.id}%-40s$ver$desc")
      }
    case "add" :: id :: url :: rest =>
      val description = rest.headOption.getOrElse("")
      val existing    = if os.exists(regPath) then LocalRegistry.loadAll() else Nil
      val updated     = existing.filterNot(_.id == id) :+
                        LocalRegistry.Entry(id, url, description = description)
      os.makeDir.all(regPath / os.up)
      os.write.over(regPath, LocalRegistry.toYaml(updated))
      println(s"Registry: added '$id'  →  $url")
    case "remove" :: id :: _ =>
      if !os.exists(regPath) then
        System.err.println("registry remove: no registry file found"); System.exit(1)
      val entries = LocalRegistry.loadAll()
      val updated = entries.filterNot(_.id == id)
      if updated.size == entries.size then
        System.err.println(s"registry remove: '$id' not found"); System.exit(1)
      os.write.over(regPath, LocalRegistry.toYaml(updated))
      println(s"Registry: removed '$id'")
    case "search" :: query :: _ =>
      val q       = query.toLowerCase
      val matches = LocalRegistry.loadAll().filter { e =>
        e.id.toLowerCase.contains(q) || e.description.toLowerCase.contains(q)
      }
      if matches.isEmpty then println("(no matches)")
      else matches.foreach { e =>
        val desc = if e.description.nonEmpty then s"  — ${e.description}" else ""
        println(s"${e.id}$desc")
      }
    case sub :: _ =>
      System.err.println(s"Unknown registry subcommand: '${sub}'")
      System.err.println("Usage: ssc plugin registry list|add|remove|search ...")
      System.exit(1)
    case Nil =>
      System.err.println("Usage: ssc plugin registry list|add|remove|search ...")
      System.exit(1)

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
  val backendId = ActiveFlags.current.backend.getOrElse("int")
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        compileViaBackend(backendId, path) match
          case CompileResult.Executed(_, _, 0)    => ()
          case CompileResult.Executed(_, _, exit) => System.exit(exit)
          case CompileResult.TextOutput(code, _, _) =>
            // Non-interpreter backends produce text — print it to stdout.
            println(code)
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
        if hasSSBlocks then { println(JsRuntime); println(JsRuntimeAsync); println(JsRuntimeV14Effects); println(JsRuntimeMcp); println(JsRuntimeDataset) }
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
                   |$JsRuntimeV14Effects
                   |$JsRuntimeBrowserPatch
                   |$userJs
                   |</script>
                   |</body>
                   |</html>""".stripMargin)
      catch case e: Exception =>
        System.err.println(s"SPA generation error: ${e.getMessage}")
        System.exit(1)

/** v0.8 — emit a JS bundle that registers each component object in the
 *  file as a W3C Custom Element.  Detection rule: a top-level
 *  `object Foo { val css: String; def render(<params>): String }`
 *  becomes `<foo-component>` (PascalCase → kebab-case + `-component`).
 *  Each render parameter is read from the same-name HTML attribute as
 *  a String; Shadow DOM scopes the CSS automatically. */
private case class WcComponent(name: String, params: List[String], hasJs: Boolean)

/** PascalCase → kebab-case (uppercase letters introduce a hyphen). */
private def wcKebab(s: String): String =
  val sb = StringBuilder()
  s.zipWithIndex.foreach { (c, i) =>
    if c.isUpper then
      if i > 0 then sb.append('-')
      sb.append(c.toLower)
    else sb.append(c)
  }
  sb.toString

/** Inspect a scala.meta `Defn.Object` for the component shape:
 *  `object Foo { val css: …; def render(<params>): … }`.  When that
 *  shape is found, append it (plus an `hasJs` flag if a `val js` is
 *  also present) to `into`. */
private def detectWcComponent(
    d:    scala.meta.Defn.Object,
    into: scala.collection.mutable.ArrayBuffer[WcComponent]
): Unit =
  import scala.meta.{Defn, Pat}
  var cssOk = false
  var jsOk  = false
  var params: Option[List[String]] = None
  d.templ.body.stats.foreach {
    case Defn.Val(_, List(Pat.Var(n)), _, _) if n.value == "css" => cssOk = true
    case Defn.Val(_, List(Pat.Var(n)), _, _) if n.value == "js"  => jsOk = true
    case dd: Defn.Def if dd.name.value == "render" =>
      params = Some(
        dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value))
    case _ => ()
  }
  if cssOk && params.isDefined then
    into += WcComponent(d.name.value, params.get, jsOk)

/** v0.8 — emit a JS bundle that registers each component object in the
 *  file as a W3C Custom Element.  Detection rule: a top-level
 *  `object Foo { val css: String; def render(<params>): String }`
 *  becomes `<foo-component>` (PascalCase → kebab-case + `-component`).
 *  Each render parameter is read from the same-name HTML attribute as
 *  a String; Shadow DOM scopes the CSS automatically. */
def emitWcCommand(args: List[String]): Unit =
  import scala.meta.Defn
  import scalascript.ast.{Content, Lang}
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path    = os.Path(file, os.pwd)
    val baseDir = Some(path / os.up)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module     = Parser.parse(os.read(path))
        val components = scala.collection.mutable.ArrayBuffer.empty[WcComponent]
        module.sections.foreach { section =>
          section.content.foreach {
            case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
              cb.tree.foreach { node =>
                scalascript.ast.ScalaNode.fold(node) {
                  case d: Defn.Object => detectWcComponent(d, components)
                  case scala.meta.Source(stats) =>
                    stats.foreach {
                      case d: Defn.Object => detectWcComponent(d, components)
                      case _              => ()
                    }
                  case _ => ()
                }
              }
            case _ => ()
          }
        }
        val segments = JsGen.generateSegmented(module, baseDir)
        val userJs = segments.collect {
          case JsGen.Segment.ScalaScriptJs(code) => code
          case JsGen.Segment.ScalaSource(src)    =>
            ScalaJsBackend.compileSourceToJs(src, baseDir)
        }.filter(_.nonEmpty).mkString("\n")
        println(JsRuntime); println(JsRuntimeAsync); println(JsRuntimeV14Effects); println(JsRuntimeMcp); println(JsRuntimeDataset)
        println(userJs)
        components.foreach { c =>
          val tag       = wcKebab(c.name) + "-component"
          val paramsArr = c.params.map(p => "'" + p + "'").mkString(", ")
          val argsExpr  =
            if c.params.isEmpty then ""
            else c.params.map(p => s"this.getAttribute('$p') || ''").mkString(", ")
          val jsHook =
            if c.hasJs then
              s"""
      try { if (typeof ${c.name}.js === 'string' && ${c.name}.js.trim().length > 0) new Function(${c.name}.js).call(shadow); }
      catch (e) { console.error('${c.name}.js failed:', e); }"""
            else ""
          // Anonymous class via `customElements.define(tag, class extends … {})`
          // — avoids clashing with the heading-bound `<section>Component` object
          // JsGen synthesises for the markdown section that introduces the
          // component.
          println(s"""
customElements.define('$tag', class extends HTMLElement {
  static get observedAttributes() { return [$paramsArr]; }
  connectedCallback() {
    const shadow = this.shadowRoot || this.attachShadow({mode: 'open'});
    const css  = (typeof ${c.name}.css === 'string') ? ${c.name}.css : '';
    const html = ${c.name}.render($argsExpr);
    shadow.innerHTML = '<style>' + css + '</style>' + _show(html);$jsHook
  }
  attributeChangedCallback() { if (this.isConnected) this.connectedCallback(); }
});""")
        }
      catch case e: Exception =>
        System.err.println(s"emit-wc generation error: ${e.getMessage}")
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

// ─────────────────────────────────────────────────────────────────────────────
// ssc emit-interface  —  v2.0 separate compilation
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc emit-interface <file.ssc> [-o <file.scim>]`
 *
 *  Compiles the `.ssc` file and extracts its module interface (exported
 *  names, extern defs, typeclass instances, capability declarations).
 *  Writes the interface as a `.scim` JSON artifact.
 *
 *  If `-o` is not specified the output is written to `<file>.scim` in the
 *  same directory as the source.  Pass `-` as the output path to print to
 *  stdout instead.
 *
 *  v2.0 / Stage 2.
 */
def emitInterfaceCommand(args: List[String]): Unit =
  var outputArg: Option[String] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => outputArg = Some(it.next())
      case f => files += f

  if files.isEmpty then
    System.err.println("Usage: ssc emit-interface <file.ssc> [-o <file.scim>]")
    System.exit(1)

  for file <- files.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"Error: File not found: $file"); System.exit(1)
    try
      val sourceBytes = os.read.bytes(path)
      val module      = Parser.parse(new String(sourceBytes, "UTF-8"))
      val iface       = InterfaceExtractor.extract(module, sourceBytes)
      val json        = ArtifactIO.writeInterface(iface)
      outputArg match
        case Some("-") => println(json)
        case Some(out) =>
          val outPath = os.Path(out, os.pwd)
          ArtifactIO.writeInterfaceFile(iface, outPath)
          println(s"Interface written to $outPath")
        case None =>
          val outPath = path / os.up / (path.last.stripSuffix(".ssc") + ".scim")
          ArtifactIO.writeInterfaceFile(iface, outPath)
          println(s"Interface written to ${outPath.relativeTo(os.pwd)}")
    catch case e: Exception =>
      System.err.println(s"emit-interface error: ${e.getMessage}")
      System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc emit-ir  —  v2.0 separate compilation
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc emit-ir <file.ssc> [-o <file.scir>]`
 *
 *  Parses and normalises the `.ssc` file, then writes the resulting
 *  `NormalizedModule` as a `.scir` JSON artifact (wrapped in the ABI
 *  envelope with magic number + version guard + source hash).
 *
 *  If `-o` is not specified the output is written to `<file>.scir` in the
 *  same directory as the source.  Pass `-` as the output path to print to
 *  stdout instead.
 *
 *  v2.0 / Stage 3.
 */
def emitIrCommand(args: List[String]): Unit =
  var outputArg: Option[String] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => outputArg = Some(it.next())
      case f => files += f

  if files.isEmpty then
    System.err.println("Usage: ssc emit-ir <file.ssc> [-o <file.scir>]")
    System.exit(1)

  for file <- files.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"Error: File not found: $file"); System.exit(1)
    try
      val sourceBytes = os.read.bytes(path)
      val module      = Parser.parse(new String(sourceBytes, "UTF-8"))
      val ir          = Normalize(module)
      val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
      val moduleName  = module.manifest.flatMap(_.name)
      val sourceHash  = InterfaceExtractor.sha256(sourceBytes)
      val json        = ArtifactIO.writeIr(ir, pkg, moduleName, sourceHash)
      outputArg match
        case Some("-") => println(json)
        case Some(out) =>
          val outPath = os.Path(out, os.pwd)
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"IR artifact written to $outPath")
        case None =>
          val outPath = path / os.up / (path.last.stripSuffix(".ssc") + ".scir")
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"IR artifact written to ${outPath.relativeTo(os.pwd)}")
    catch case e: Exception =>
      System.err.println(s"emit-ir error: ${e.getMessage}")
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

def lockCommand(args: List[String]): Unit =
  args match
    case "check" :: rest => lockCheckCommand(rest)
    case rest            => lockPinCommand(rest)

private def collectUrlImports(module: scalascript.ast.Module): List[String] =
  def fromSection(s: scalascript.ast.Section): List[String] =
    val direct = s.content.collect {
      case scalascript.ast.Content.Import(path, _, _)
          if path.startsWith("http://") || path.startsWith("https://") || path.startsWith("dep:") => path
    }
    direct ++ s.subsections.flatMap(fromSection)
  module.sections.flatMap(fromSection)

private def lockPinCommand(args: List[String]): Unit =
  if args.isEmpty then { System.err.println("lock: no file specified"); System.exit(1) }
  val file = args.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then { System.err.println(s"lock: file not found: $file"); System.exit(1) }
  val lockPath = path / os.up / "ssc.lock"
  try
    val module  = Parser.parse(os.read(path))
    val urls    = collectUrlImports(module)
    if urls.isEmpty then
      println("lock: no URL/dep imports found")
    else
      import scalascript.imports.{ImportResolver, LockFile}
      val baseDir = path / os.up
      val deps    = module.manifest.map(_.dependencies).getOrElse(Map.empty)
      var lock    = LockFile.read(lockPath).getOrElse(LockFile.empty)
      for url <- urls do
        val resolved = ImportResolver.resolve(url, baseDir, deps, lockPath = Some(lockPath))
        val content  = os.read.bytes(resolved)
        lock = lock.pin(url, content)
        println(s"  pinned $url")
      LockFile.write(lock, lockPath)
      println(s"lock: wrote ${lockPath.relativeTo(os.pwd)}")
  catch case e: Exception =>
    System.err.println(s"lock error: ${e.getMessage}")
    System.exit(1)

private def lockCheckCommand(args: List[String]): Unit =
  if args.isEmpty then { System.err.println("lock check: no file specified"); System.exit(1) }
  val file = args.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then { System.err.println(s"lock check: file not found: $file"); System.exit(1) }
  val lockPath = path / os.up / "ssc.lock"
  if !os.exists(lockPath) then
    System.err.println(s"lock check: no ssc.lock at ${lockPath.relativeTo(os.pwd)}")
    System.exit(1)
  try
    import scalascript.imports.{ImportResolver, LockFile}
    val module  = Parser.parse(os.read(path))
    val urls    = collectUrlImports(module)
    val lock    = LockFile.read(lockPath).fold(e => throw e, identity)
    val baseDir = path / os.up
    val deps    = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    var hasErrors = false
    for url <- urls do
      lock.entries.get(url) match
        case None =>
          System.err.println(s"  MISSING in lock: $url")
          hasErrors = true
        case Some(_) =>
          val resolved = ImportResolver.resolve(url, baseDir, deps, lockPath = Some(lockPath))
          val content  = os.read.bytes(resolved)
          lock.check(url, content) match
            case Left(err)  => System.err.println(s"  FAIL: $err"); hasErrors = true
            case Right(_)   => println(s"  ok $url")
    if hasErrors then System.exit(1) else println("lock check: all OK")
  catch case e: Exception =>
    System.err.println(s"lock check error: ${e.getMessage}")
    System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc test <file(s)>  —  v0.9 component-level unit test runner
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc test <file.ssc> [<file.ssc>...]`
 *
 *  Runs each file through the interpreter with an injected `test(name, thunk)`
 *  builtin.  At runtime a `.ssc` test file calls:
 *
 *    test("renders sm", () => Spinner.render("sm").contains("sm"))
 *    test("renders lg", () => Spinner.render("lg").contains("lg"))
 *
 *  The runner collects all registrations, then after the module finishes it
 *  executes each thunk and prints a coloured PASS / FAIL line.  Exit status is
 *  1 if any test failed, 0 if all passed.
 *
 *  If no file is given the runner looks for `*-test.ssc` siblings next to the
 *  component being tested (same directory).
 *
 *  Backend matrix: interpreter only (cross-backend conformance is handled by
 *  `conformance/`; this runner is for component-authored fast unit tests).
 */
def testCommand(args: List[String]): Unit =
  import scalascript.interpreter.{Interpreter, Value, Computation}

  if args.isEmpty then
    System.err.println("Usage: ssc test <file.ssc> [<file.ssc>...]")
    System.exit(1)

  val files = args.flatMap { f =>
    val p = os.Path(f, os.pwd)
    if !os.exists(p) then
      System.err.println(s"Error: File not found: $f"); System.exit(1); Nil
    else List(p)
  }

  var totalPassed = 0
  var totalFailed = 0

  for file <- files do
    println(s"\n  ${file.last}")

    // Collect (name, thunk) pairs registered via test(name, thunk) calls.
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]

    // Create interpreter and inject the `test` builtin via injectGlobal BEFORE
    // calling run().  initBuiltins() (called inside run) doesn't touch "test"
    // so the injection survives and user code can call test(...) at top level.
    val interp = Interpreter(out = System.out, baseDir = Some(file / os.up))
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ =>
          Value.UnitV
      })
    )

    try interp.run(scalascript.parser.Parser.parse(os.read(file)))
    catch case e: Exception =>
      System.err.println(s"  [error] ${e.getMessage}")
      totalFailed += 1

    // Execute each registered test thunk and report.
    for (name, thunk) <- tests do
      val result =
        try
          interp.invoke(thunk, Nil) match
            case Value.BoolV(true)  => Right(true)
            case Value.BoolV(false) => Right(false)
            case other              => Left(s"expected Boolean, got ${Value.show(other)}")
        catch case e: Exception => Left(e.getMessage)
      result match
        case Right(true)  =>
          println(s"    PASS  $name")
          totalPassed += 1
        case Right(false) =>
          println(s"    FAIL  $name")
          totalFailed += 1
        case Left(msg)    =>
          println(s"    FAIL  $name  ($msg)")
          totalFailed += 1

  println()
  println(s"Results: $totalPassed passed, $totalFailed failed")
  if totalFailed > 0 then System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc preview <file>  —  v0.9 Storybook-lite browser preview
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc preview <file.ssc>`
 *
 *  Reads the `variants:` list from the file's YAML front-matter.  Each
 *  variant is a map of named arguments that will be passed to every
 *  `object Foo { def render(…) }` component found in the file.
 *
 *  Front-matter format:
 *
 *    ---
 *    name: spinner
 *    variants:
 *      - name: "small"
 *        args: {size: "sm"}
 *      - name: "large"
 *        args: {size: "lg"}
 *    ---
 *
 *  The command:
 *    1. Runs the file through the interpreter to collect CSS and component
 *       objects.
 *    2. Builds a self-contained HTML page that shows each variant in its own
 *       labelled section.
 *    3. Starts a one-shot HTTP server on a free port and opens the browser.
 *    4. Shuts down after serving one request (the browser load).
 *
 *  If `variants:` is absent the page shows one "default" section rendering
 *  every component with no arguments.
 */
def previewCommand(args: List[String]): Unit =
  import scalascript.interpreter.{Interpreter, Value}
  import scalascript.ast.{Content, Lang}
  import scala.meta.Defn
  import scala.jdk.CollectionConverters.*

  if args.isEmpty then
    System.err.println("Usage: ssc preview <file.ssc>")
    System.exit(1)

  val file    = args.head
  val absPath = os.Path(file, os.pwd)
  if !os.exists(absPath) then
    System.err.println(s"Error: File not found: $file"); System.exit(1)

  // Parse the module to extract front-matter and component shapes.
  val module   = scalascript.parser.Parser.parse(os.read(absPath))
  val title    = module.manifest.flatMap(_.name).getOrElse(absPath.last.stripSuffix(".ssc"))
  val rawFM    = module.manifest.map(_.raw).getOrElse(Map.empty)

  // Parse `variants:` from front-matter.
  // Each entry: {name: String, args: {key: value, …}}
  case class Variant(label: String, args: Map[String, String])

  val variants: List[Variant] = rawFM.get("variants").collect {
    case xs: java.util.List[?] =>
      xs.asScala.toList.flatMap {
        case m: java.util.Map[?, ?] =>
          val mm = m.asScala.toMap.map((k, v) => k.toString -> v)
          val label = mm.get("name").map(_.toString).getOrElse("default")
          val argMap = mm.get("args").collect {
            case am: java.util.Map[?, ?] =>
              am.asScala.toMap.map((k, v) => k.toString -> v.toString)
          }.getOrElse(Map.empty)
          Some(Variant(label, argMap))
        case _ => None
      }
  }.getOrElse(Nil)

  val effectiveVariants =
    if variants.isEmpty then List(Variant("default", Map.empty))
    else variants

  // Detect component objects (same detection as emit-wc).
  val components = scala.collection.mutable.ArrayBuffer.empty[WcComponent]
  module.sections.foreach { section =>
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
        cb.tree.foreach { node =>
          scalascript.ast.ScalaNode.fold(node) {
            case d: Defn.Object => detectWcComponent(d, components)
            case scala.meta.Source(stats) =>
              stats.foreach {
                case d: Defn.Object => detectWcComponent(d, components)
                case _              => ()
              }
            case _ => ()
          }
        }
      case _ => ()
    }
  }

  // Run the file to get live component instances.
  val nullOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream)
  val interp  = Interpreter(out = nullOut, baseDir = Some(absPath / os.up))
  try interp.run(module)
  catch case e: Exception =>
    System.err.println(s"Error running $file: ${e.getMessage}"); System.exit(1)

  // Snapshot the globals after execution (all objects are InstanceV in the interpreter).
  val interpGlobals = interp.exportedGlobals

  // Collect CSS from component objects.
  val allCss = components.flatMap { c =>
    interpGlobals.get(c.name).collect {
      case Value.InstanceV(_, fields) => fields.get("css").collect { case Value.StringV(css) => css }
    }.flatten
  }.mkString("\n")

  // Render each variant for each component.
  def renderVariant(comp: WcComponent, variant: Variant): String =
    interpGlobals.get(comp.name) match
      case Some(obj) =>
        val renderFn = obj match
          case Value.InstanceV(_, fields) => fields.get("render")
          case _                          => None
        renderFn match
          case Some(fn) =>
            val argVals = comp.params.map { p =>
              variant.args.get(p).map(Value.StringV.apply).getOrElse(Value.StringV(""))
            }
            try Value.show(interp.invoke(fn, argVals))
            catch case e: Exception => s"<em>render error: ${e.getMessage}</em>"
          case None => s"<em>${comp.name} has no render method</em>"
      case None => s"<em>${comp.name} not found</em>"

  // Build the preview HTML page.
  val variantSections = effectiveVariants.map { variant =>
    val rendered = components.map { comp =>
      val html = renderVariant(comp, variant)
      s"""<div class="component-box">
         |  <div class="component-label">${comp.name}</div>
         |  <div class="component-render">$html</div>
         |</div>""".stripMargin
    }.mkString("\n")
    s"""<section class="variant-section">
       |  <h2 class="variant-heading">${variant.label}</h2>
       |  <div class="component-row">$rendered</div>
       |</section>""".stripMargin
  }.mkString("\n")

  val html = s"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Preview: $title</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; }
    body { font-family: system-ui, sans-serif; margin: 0; background: #f8f9fa; color: #212529; }
    header { background: #1e293b; color: #f1f5f9; padding: 1rem 2rem;
             display: flex; align-items: center; gap: 1rem; }
    header h1 { margin: 0; font-size: 1.1rem; font-weight: 600; }
    header .badge { font-size: .75rem; background: #0ea5e9; color: #fff;
                    padding: .15em .55em; border-radius: 999px; }
    main { padding: 2rem; max-width: 1200px; margin: 0 auto; }
    .variant-section { margin-bottom: 2.5rem; }
    .variant-heading { font-size: .85rem; font-weight: 600; text-transform: uppercase;
                       letter-spacing: .08em; color: #64748b; margin: 0 0 1rem;
                       padding-bottom: .5rem; border-bottom: 1px solid #e2e8f0; }
    .component-row { display: flex; flex-wrap: wrap; gap: 1.5rem; }
    .component-box { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px;
                     padding: 1.25rem; min-width: 200px; }
    .component-label { font-size: .7rem; font-weight: 600; text-transform: uppercase;
                       letter-spacing: .06em; color: #94a3b8; margin-bottom: .75rem; }
    .component-render { }
    $allCss
  </style>
</head>
<body>
<header>
  <h1>$title</h1>
  <span class="badge">ssc preview</span>
</header>
<main>
  $variantSections
</main>
</body>
</html>"""

  // Start a one-shot HTTP server on a free port, then open the browser.
  val serverSocket = java.net.ServerSocket(0)
  val port         = serverSocket.getLocalPort
  val url          = s"http://localhost:$port"

  System.err.println(s"Preview: $url")
  System.err.println("(browser will open automatically; Ctrl+C to stop)")

  // Open browser in background.
  val os_name = sys.props.getOrElse("os.name", "").toLowerCase
  val openCmd =
    if os_name.contains("mac") then List("open", url)
    else if os_name.contains("linux") then List("xdg-open", url)
    else List("cmd", "/c", "start", url)
  scala.util.Try(Runtime.getRuntime.exec(openCmd.toArray))

  // Serve the page on each connection until the user hits Ctrl+C.
  // (Single-shot: serve one response then exit.)
  val conn     = serverSocket.accept()
  val in       = java.io.BufferedReader(java.io.InputStreamReader(conn.getInputStream))
  // Drain the HTTP request headers.
  var line = in.readLine()
  while line != null && line.nonEmpty do line = in.readLine()
  val out2     = conn.getOutputStream
  val bodyBytes = html.getBytes("UTF-8")
  val response = s"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bodyBytes.length}\r\nConnection: close\r\n\r\n"
  out2.write(response.getBytes("UTF-8"))
  out2.write(bodyBytes)
  out2.flush()
  conn.close()
  serverSocket.close()

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

// ─────────────────────────────────────────────────────────────────────────────
// ssc link  —  v2.0 separate compilation
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc link <artifact-dir> [-o <output.scir>] [--backend <id>]`
 *
 *  Collects all `.scim` + `.scir` artifact pairs from `<artifact-dir>`,
 *  links them into a single `NormalizedModule`, and either:
 *  - Writes the merged module as a `.scir` JSON artifact (if `-o` is given
 *    and ends with `.scir`), or
 *  - Immediately compiles via `--backend <id>` (default `int`) and prints
 *    or executes the result.
 *
 *  Artifacts are processed in dependency order: the linker reads each `.scim`
 *  to determine the package, then topologically sorts them.  Cycles are
 *  detected and reported as errors.
 *
 *  v2.0 / Stage 5.
 */
def linkCommand(args: List[String]): Unit =
  import scalascript.artifact.Linker
  var outputArg:   Option[String] = None
  var backendArg:  Option[String] = None
  val artifactDirs = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext  => outputArg  = Some(it.next())
      case "--backend"       if it.hasNext  => backendArg = Some(it.next())
      case d                                => artifactDirs += d

  if artifactDirs.isEmpty then
    System.err.println("Usage: ssc link <artifact-dir> [-o <output.scir>] [--backend <id>]")
    System.exit(1)

  // Collect all (interface, ir) pairs from the specified directories.
  val allModules = scala.collection.mutable.ArrayBuffer.empty[Linker.CompiledModule]
  var hasError = false

  for dir <- artifactDirs.toList do
    val dirPath = os.Path(dir, os.pwd)
    if !os.isDir(dirPath) then
      System.err.println(s"link: '$dir' is not a directory"); hasError = true
    else
      val scimFiles = os.list(dirPath).filter(_.ext == "scim").sorted
      for scimPath <- scimFiles do
        val scirPath = scimPath / os.up / (scimPath.last.stripSuffix(".scim") + ".scir")
        ArtifactIO.readInterfaceFile(scimPath) match
          case Left(err) =>
            System.err.println(s"link: failed to read ${scimPath.last}: $err")
            hasError = true
          case Right(iface) =>
            if !os.exists(scirPath) then
              System.err.println(s"link: no matching .scir for ${scimPath.last}")
              hasError = true
            else
              ArtifactIO.readIrFile(scirPath) match
                case Left(err) =>
                  System.err.println(s"link: failed to read ${scirPath.last}: $err")
                  hasError = true
                case Right((nm, _, _, _)) =>
                  allModules += Linker.CompiledModule(iface, nm)

  if hasError then System.exit(1)
  if allModules.isEmpty then
    System.err.println("link: no artifact pairs found")
    System.exit(1)

  // Detect cross-module name collisions and warn.
  val collisions = Linker.detectCollisions(allModules.toList)
  if collisions.nonEmpty then
    System.err.println(s"link: ${collisions.length} name collision(s) detected (FQN mangling applied):")
    collisions.foreach { (name, pkgs) =>
      System.err.println(s"  '$name' exported by: ${pkgs.map(_.mkString(".")).mkString(", ")}")
    }

  val linked = Linker.link(allModules.toList)
  println(s"Linked ${allModules.size} module(s) → ${linked.sections.length} section(s)")

  // Composite hash: SHA-256 of input artifact hashes joined in load order.
  // Deterministic and reproducible; consumers can use it for staleness checks.
  val composedHash =
    val combined = allModules.map(_.iface.sourceHash).mkString("\n")
    InterfaceExtractor.sha256(combined.getBytes("UTF-8"))

  outputArg match
    case Some(out) if out.endsWith(".scir") =>
      val outPath    = os.Path(out, os.pwd)
      ArtifactIO.writeIrFile(linked, Nil, None, composedHash, outPath)
      println(s"Linked IR written to $outPath")
    case Some("-") =>
      val json = ArtifactIO.writeIr(linked, Nil, None, composedHash)
      println(json)
    case Some(out) =>
      System.err.println(s"link: -o output must end with .scir or be '-', got: $out")
      System.exit(1)
    case None =>
      // No output path: compile and execute via backend.
      val bid     = backendArg.orElse(ActiveFlags.current.backend).getOrElse("int")
      val backend = resolveBackend(bid)
      val opts    = BackendOptions()
      val diags   = scalascript.validate.CapabilityCheck.validate(linked, backend.capabilities, bid)
      if diags.nonEmpty then
        diags.foreach(d => System.err.println(s"[error] $d"))
        System.exit(1)
      backend.compile(linked, opts) match
        case CompileResult.Executed(_, _, exit) => if exit != 0 then System.exit(exit)
        case CompileResult.TextOutput(code, _, _) => println(code)
        case CompileResult.Failed(diags) =>
          diags.foreach(d => System.err.println(s"[error] $d")); System.exit(1)
        case other =>
          System.err.println(s"link: unexpected result ${other.getClass.getSimpleName}")
          System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc check-with-iface  —  v2.0 separate compilation
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc check-with-iface --iface-dir <dir> <file.ssc> [<file.ssc>...]`
 *
 *  Type-checks each `.ssc` file against pre-compiled `.scim` interface
 *  artifacts loaded from `--iface-dir`.  The loaded interfaces are available
 *  as cross-module symbols during type-checking so imported names resolve
 *  without re-parsing their source modules.
 *
 *  If no `--iface-dir` is specified the command falls back to the standard
 *  single-module `check` behaviour (backward compat).
 *
 *  v2.0 / Stage 4.
 */
def checkWithInterfaceCommand(args: List[String]): Unit =
  var ifaceDir: Option[os.Path] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--iface-dir" | "-I" if it.hasNext =>
        ifaceDir = Some(os.Path(it.next(), os.pwd))
      case f => files += f

  if files.isEmpty then
    System.err.println("Usage: ssc check-with-iface [--iface-dir <dir>] <file.ssc> [...]")
    System.exit(1)

  // Load all .scim files from the interface directory (if specified).
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    ifaceDir match
      case None => Map.empty
      case Some(dir) =>
        if !os.isDir(dir) then
          System.err.println(s"check-with-iface: --iface-dir '$dir' is not a directory")
          System.exit(1)
        os.list(dir).filter(_.ext == "scim").flatMap { p =>
          ArtifactIO.readInterfaceFile(p) match
            case Right(iface) =>
              val alias = p.last.stripSuffix(".scim")
              System.err.println(s"  [iface] loaded $alias from ${p.last}")
              List(alias -> iface)
            case Left(err) =>
              System.err.println(s"  [warn] skipping ${p.last}: $err")
              Nil
        }.toMap

  var hasErrors = false
  for file <- files.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      println(s"Error: File not found: $file"); hasErrors = true
    else
      println(s"=== Type checking (with interfaces): $file ===")
      try
        val module = Parser.parse(os.read(path))
        val typed  =
          if interfaces.isEmpty then Typer.typeCheck(module)
          else Typer.typeCheckWithInterfaces(module, interfaces)
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
