package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.{Typer, SectionSnapshot, SectionDiff}
// Stage 5.4 will phase these direct imports out via HTTP intrinsics +
// concrete ir.Value bridging.  Until then, render / build / serve / repl
// commands need Interpreter + JsRuntime preamble strings directly;
// emit-spa needs ScalaJsBackend.compileSourceToJs for per-segment
// Scala source compilation.
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch, JsRuntimeMcpBrowser, ScalaJsBackend}
import scalascript.ast.*
import scalascript.transform.Normalize
import scalascript.validate.CapabilityCheck
import scalascript.compiler.plugin.{BackendRegistry, SourceLanguageRegistry}
import scalascript.backend.spi.{BackendOptions, BackendTransportKind, CompileResult, Segment}
// v2.0 separate-compilation artifact commands
import scalascript.artifact.{InterfaceExtractor, ArtifactIO, JvmArtifactIO, JsArtifactIO}
import scalascript.codegen.JvmGen
import scalascript.codegen.SparkGen
import scalascript.codegen.SparkSubmit
import scalascript.codegen.SparkBackend

@main def ssc(rawArgs: String*): Unit =
  // --quiet silences third-party SLF4J library output (commonmark, …)
  // by raising the slf4j-simple threshold to error.  Must run before any SLF4J
  // logger is first touched.
  val (quietFlags, args0) = rawArgs.partition(_ == "--quiet")
  if quietFlags.nonEmpty then
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")

  // --logs.key=value → System.setProperty("scalascript.logger.key", "value").
  // Must run first so the logger reads properties before any first log call.
  // Examples:  --logs.defaultLevel=debug   --logs.scalascript.server.level=info
  val (logFlags, filteredArgs) = args0.partition(_.startsWith("--logs."))
  logFlags.foreach { flag =>
    val kv  = flag.drop("--logs.".length)
    val eq  = kv.indexOf('=')
    if eq > 0 then
      System.setProperty(s"scalascript.logger.${kv.take(eq)}", kv.drop(eq + 1))
  }
  // Auto-load .sscpkg files from lib/compiler/plugins/ next to the install root.
  // Runs before CLI flags so --plugin can still override or supplement.
  scalascript.imports.ImportResolver.libPath
    .map(_ / "bin" / "lib" / "compiler" / "plugins")
    .filter(os.exists)
    .foreach { dir =>
      os.list(dir)
        .filter(_.ext == "sscpkg")
        .foreach(BackendRegistry.loadSscpkg)
    }
  // Strip global plugin-management flags from anywhere in the
  // argument list before dispatching to a command.  --plugin and
  // --plugin-dir mutate BackendRegistry; --target / --backend are
  // captured into thread-local state for command handlers.
  val (globalFlags, args) = GlobalFlags.parse(filteredArgs.toList)
  globalFlags.applyToRegistry()

  // If stdin is a pipe (not a TTY) and the command is not one that owns
  // stdin itself (lsp uses JSON-RPC; repl uses interactive readline),
  // read the piped content as a YAML secrets document — the typical
  // source is `sops -d secrets.enc.yaml | ssc myapp.ssc`.
  val stdinCommand = args.headOption.getOrElse("")
  if System.console() == null && stdinCommand != "lsp" && stdinCommand != "repl" then
    loadSopsSecrets()

  // Standalone meta-commands that don't dispatch to a command handler.
  if globalFlags.listBackends then
    println(BackendRegistry.describe)
  else if globalFlags.listSourceLanguages then
    println(SourceLanguageRegistry.describe)
  else if globalFlags.describeBackend.isDefined then
    val id = globalFlags.describeBackend.get
    BackendRegistry.lookup(id) match
      case Some(b) => println(describeBackend(b))
      case None    =>
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
    case "watch-bench"         => watchBenchCommand(args.tail)
    case "repl"                => replCommand(args.tail)
    case "emit-js"             => emitJsCommand(args.tail)
    case "emit-wasm"           => emitWasmCommand(args.tail)
    case "emit-spa"            => emitSpaCommand(args.tail)
    case "emit-scala"          => emitScalaCommand(args.tail)
    case "emit-spark"          => emitSparkCommand(args.tail)
    case "submit"              => submitCommand(args.tail)
    case "emit-wc"             => emitWcCommand(args.tail)
    // v2.0 separate-compilation commands
    case "emit-interface"      => emitInterfaceCommand(args.tail)
    case "emit-ir"             => emitIrCommand(args.tail)
    case "run-jvm"             => runJvmCommand(args.tail)
    case "run-js"              => runJsCommand(args.tail)
    case "compile-jvm"         => compileJvmCommand(args.tail)
    case "compile-js"          => compileJsCommand(args.tail)
    case "compile-runtime"     => compileRuntimeCommand(args.tail)
    case "check-with-iface"    => checkWithInterfaceCommand(args.tail)
    case "link"                => linkCommand(args.tail)
    case "generate-facade"     => generateFacadeCommand(args.tail)
    case "info"                => infoCommand(args.tail)
    case "clean"               => cleanCommand(args.tail)
    case "verify"              => verifyCommand(args.tail)
    case "deps"                => depsCommand(args.tail)
    case "deploy"              => deployCommand(args.tail)
    case "package"             => packageCommand(args.tail)
    case "publish"             => publishCommand(args.tail)
    case "serve"               => serveCommand(args.tail)
    case "render"              => renderCommand(args.tail)
    case "build"               => buildCommand(args.tail)
    case "bundle"              => bundleCommand(args.tail)
    case "new"                 => newCommand(args.tail)
    case "plugin"              => pluginCommand(args.tail)
    case "install"             =>
      // No args or --prefix flag → install ssc itself; otherwise → plugin install shortcut.
      if args.tail.isEmpty || args.tail.headOption.contains("--prefix") then selfInstallCommand(args.tail)
      else pluginInstall(args.tail)
    case "lock"                => lockCommand(args.tail)
    case "test"                => testCommand(args.tail)
    case "preview"             => previewCommand(args.tail)
    case "fmt"                 => fmtCommand(args.tail)
    case "profile"             => profileCommand(args.tail)
    case "lsp"                 => lspCommand(args.tail)
    case "debug"               => DebugCommand.run(args.tail)
    case "cluster"             => clusterCommand(args.tail)
    case "oauth"               => OAuthCli.run(args.tail)
    case "toolchain"           => ToolchainCommand.run(args.tail)
    case "help" | "--help" | "-h" => printUsage()
    case "--list-backends"     => println(BackendRegistry.describe)
    case cmd                   => scriptCommand(cmd, args.tail)

/** Read stdin as a YAML secrets document and load the flattened key→value
 *  map into [[scalascript.sql.SopsSecrets]].
 *
 *  Nested YAML keys are joined with `.` so the document:
 *  {{{
 *  db:
 *    prod:
 *      password: "s3cr3t"
 *  TOP_SECRET: "value"
 *  }}}
 *  produces `db.prod.password` and `TOP_SECRET`.
 *
 *  List elements are keyed by index (`hosts.0`, `hosts.1`, …).
 *
 *  A blank or non-YAML stdin is silently ignored — no error is raised
 *  so that scripts piped other content don't break unexpectedly. */
private def loadSopsSecrets(): Unit =
  try
    val raw = scala.io.Source.stdin.mkString
    if raw.nonEmpty then
      val doc = scalascript.parser.SimpleYaml.load[Any](raw)
      val flat = flattenYaml("", doc)
      if flat.nonEmpty then
        scalascript.sql.SopsSecrets.load(flat)
  catch case _: Throwable => () // non-YAML or empty stdin — ignore

private def flattenYaml(prefix: String, node: Any): Map[String, String] =
  import scala.jdk.CollectionConverters.*
  node match
    case m: java.util.Map[?, ?] =>
      m.asScala.flatMap { case (k, v) =>
        val key = if prefix.isEmpty then k.toString else s"$prefix.$k"
        flattenYaml(key, v)
      }.toMap
    case l: java.util.List[?] =>
      l.asScala.zipWithIndex.flatMap { case (v, i) =>
        val key = if prefix.isEmpty then i.toString else s"$prefix.$i"
        flattenYaml(key, v)
      }.toMap
    case null  => if prefix.nonEmpty then Map(prefix -> "") else Map.empty
    case other => if prefix.nonEmpty then Map(prefix -> other.toString) else Map.empty

/** Global, non-command-specific CLI flags. */
case class GlobalFlags(
    pluginJars:          List[os.Path] = Nil,
    pluginDirs:          List[os.Path] = Nil,
    target:              Option[String] = None,
    backend:             Option[String] = None,
    listBackends:        Boolean        = false,
    listSourceLanguages: Boolean        = false,
    describeBackend:     Option[String] = None,
    yStats:              Boolean        = false,
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
        case "--Ystats" =>
          flags = flags.copy(yStats = true); i += 1
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

/** The text body printed for `ssc --describe-backend <id>`.
 *
 *  Sorted/deterministic for every enumerable Capabilities field so the
 *  output is stable across JVM HashSet iteration orderings — important
 *  because the value is what user-facing tests and shell scripts
 *  diff against.
 *
 *  Fields shown:
 *    - `id`, `displayName`, `spiVersion` — identity / wire compat.
 *    - `acceptedSources`                 — alternative source dialects.
 *    - `capabilities.features`           — language features the backend
 *                                          declares it can emit / run.
 *    - `capabilities.outputs`            — what the backend's `compile`
 *                                          returns (file vs. text vs.
 *                                          ExecutionResult).
 *    - `capabilities.options`            — `BackendOptions.extra` keys
 *                                          the backend understands —
 *                                          e.g. `sparkVersion`,
 *                                          `sparkMaster` for Spark.
 *    - `capabilities.blockLanguages`     — opaque-executable fenced-
 *                                          block tags consumed — e.g.
 *                                          `sql` on Spark (§ 9.5 C.1),
 *                                          `node.js` on the Node target.
 *                                          What `CapabilityCheck`'s
 *                                          `UnknownBlockLanguage`
 *                                          diagnostic checks against.
 *    - `intrinsics`                      — count of registered runtime
 *                                          intrinsic implementations.
 *
 *  Kept as a pure function (no I/O, no side effects, no exits) so it
 *  can be tested without driving the whole `ssc` entry point. */
def describeBackend(b: scalascript.backend.spi.Backend): String =
  val lines = List(
    s"id:          ${b.id}",
    s"displayName: ${b.displayName}",
    s"spiVersion:  ${b.spiVersion}",
    s"acceptedSources: ${b.acceptedSources.toList.sorted.mkString(", ")}",
    s"capabilities.features: ${b.capabilities.features.toList.sortBy(_.toString).mkString(", ")}",
    s"capabilities.outputs:  ${b.capabilities.outputs.toList.sortBy(_.toString).mkString(", ")}",
    s"capabilities.options:  ${b.capabilities.options.toList.sorted.mkString(", ")}",
    s"capabilities.blockLanguages: ${b.capabilities.blockLanguages.toList.sorted.mkString(", ")}",
    s"intrinsics:  ${b.intrinsics.size} registered"
  )
  lines.mkString("\n")

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
  val module  = loadModule(file)
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

private def compileJsSegments(path: os.Path, noTreeShake: Boolean = false): List[Segment] =
  val module  = loadModule(path)
  val ir      = Normalize(module)
  val backend = resolveBackend("js")
  val diags   = CapabilityCheck.validate(ir, backend.capabilities, "js")
  if diags.nonEmpty then
    diags.foreach(d => System.err.println(s"[error] $d"))
    System.exit(1)
    Nil
  else
    val baseDir  = Some(path / os.up)
    val preamble = if backend.runtimePreamble.isEmpty then "" else backend.runtimePreamble + "\n"
    JsGen.generateSegmented(module, baseDir, backend.intrinsics, noTreeShake = noTreeShake).map {
      case JsGen.Segment.ScalaScriptJs(code) =>
        Segment.Code(language = "javascript", code = preamble + code)
      case JsGen.Segment.ScalaSource(src) =>
        Segment.Source(language = "scala", source = src)
    }

private def expectText(r: CompileResult, what: String): String = r match
  case CompileResult.TextOutput(code, _, _) => code
  case CompileResult.Failed(diags) =>
    diags.foreach(d => System.err.println(s"[error] $d"))
    System.exit(1); ""
  case other =>
    System.err.println(s"$what: unexpected result ${other.getClass.getSimpleName}")
    System.exit(1); ""

/** Inject the HTTP/WS server backend selection into a generated
 *  scala-cli script.  See docs/http-server-spi-plan.md for the SPI
 *  design.
 *
 *  - `"jdk"` (default): no change — the default JdkServerBackend is
 *    already on the script's classpath via the inlined
 *    runtime-server-jvm sources.
 *  - `"jetty"` / `"netty"`: prepend a `//> using dep` directive so
 *    scala-cli pulls the impl jar from Maven (requires the impl
 *    module to be published — until Option A+ lands, `sbt publishLocal`
 *    the relevant runtime-server-jvm-{jetty,netty} module first),
 *    plus an `_ssc_init_backend()` call that ServiceLoader-registers
 *    the impl and selects it via `setHttpServerBackend(name)`.
 *
 *  v1.17.6 / Phase v1.17.6 CLI work. */
private[cli] def injectServerBackend(script: String, backend: String): String =
  backend match
    case "jdk" => script
    case name @ ("jetty" | "netty") =>
      val version  = "0.1.0-SNAPSHOT"
      val libDirective =
        s"//> using dep io.scalascript::scalascript-runtime-server-jvm-$name:$version\n"
      val implClass = name match
        case "jetty" => "scalascript.server.jvm.jetty.JettyServerBackend"
        case "netty" => "scalascript.server.jvm.netty.NettyServerBackend"
      // Init runs before any serve() call.  Registers the impl with
      // HttpServerBackends (in case the script's classpath ServiceLoader
      // doesn't discover it, which would be the case for sbt-published-
      // SNAPSHOT scenarios) and selects by name.  Safe to call twice.
      val initBlock =
        s"""
           |// ssc compile --server-backend $name — auto-injected init
           |scalascript.server.spi.HttpServerBackends.register(new $implClass)
           |scalascript.server.spi.HttpServerBackends.setBackend("$name")
           |""".stripMargin
      // Place the `//> using dep` directive at the very top (scala-cli
      // requires `using` directives before any Scala code) and the
      // init block right after — before any user-defined code.
      libDirective + script + initBlock
    case other =>
      // Caller already validates; this is defense.
      throw new IllegalArgumentException(s"unknown server backend '$other'")

/** Injects a `use(...)` middleware preamble into a generated JVM script that
 *  reads `SSC_DESKTOP_TOKEN` from the environment and rejects requests that
 *  do not carry a matching `X-ScalaScript-Desktop-Token` header.  The token
 *  is set by the CLI before launching the JVM backend process; Electron
 *  bundles receive the same token via `globalThis.__sscDesktopToken`.
 *
 *  The check is skipped when `SSC_DESKTOP_TOKEN` is unset or empty so that
 *  `ssc run --mode server` (no Electron) and test runs are unaffected. */
private[cli] def injectDesktopTokenMiddleware(script: String): String =
  val preamble =
    """|
       |// ssc desktop security token — auto-injected by CLI
       |{
       |  val _sscDesktopToken = sys.env.getOrElse("SSC_DESKTOP_TOKEN", "")
       |  if _sscDesktopToken.nonEmpty then
       |    use { (req, next) =>
       |      val provided = req.headers.getOrElse("x-scalascript-desktop-token", "")
       |      if provided == _sscDesktopToken then next()
       |      else Response(401, Map("Content-Type" -> "text/plain; charset=utf-8"),
       |                    "Unauthorized: missing or invalid desktop token")
       |    }
       |}
       |""".stripMargin
  script + preamble

/** Valid `--frontend` names — fixed list matches the bundled frontend modules.
 *  Adding a new frontend backend means adding it here and to the
 *  `dependsOn(...)` chain in `build.sbt`'s `cli` definition. */
private[cli] val validFrontendNames: Set[String] =
  Set("custom", "react", "solid", "vue", "electron", "swing", "javafx", "swiftui")

private[cli] val browserFrontendNames: Set[String] =
  Set("custom", "react", "solid", "vue")

/** v1.18 / Phase A7 — apply the `--frontend <name>` selection on the
 *  JVM side before any frontend codegen runs.  Mirrors
 *  `injectServerBackend` but for the FrontendFrameworkSpi registry
 *  instead of HttpServerBackends.
 *
 *  Today this only flips the `FrontendFrameworks` choice so downstream
 *  emit code (emit-spa etc.) can route through the right impl.  The
 *  SPA emit path doesn't consume the registry yet (that's A8 work);
 *  wiring it up early keeps the flag stable as A8 lands. */
private[cli] def applyFrontendBackend(name: String): Unit =
  scalascript.frontend.FrontendFrameworks.setBackend(name)

private[cli] def runRequestsSwingFrontend(frontendFlag: Option[String], fileArgs: List[String]): Boolean =
  frontendFlag.contains("swing") ||
    (
      frontendFlag.isEmpty &&
        fileArgs.nonEmpty &&
        fileArgs.forall { file =>
          val path = os.Path(file, os.pwd)
          os.exists(path) &&
            scala.util.Try(loadModule(path).manifest.flatMap(_.frontendFramework).contains("swing")).getOrElse(false)
        }
    )

private[cli] def rejectInterpreterSwingRun(): Unit =
  System.err.println(
    "run --frontend swing uses the interpreter path, but Swing interpreter intrinsics are not implemented yet. " +
      "Use `ssc run-jvm --frontend swing <file.ssc>` for the current Scala-CLI Swing dev path."
  )
  System.exit(1)

/** Load sidecar config files alongside `sscPath`.
 *
 *  Loads ALL of `<base>.conf`, `<base>.yaml`, `<base>.json`, and frontmatter
 *  from `<base>.ssc` that exist, then deep-merges them in ascending priority:
 *  ssc (lowest) → json → yaml → conf (highest).  On key conflicts the higher-
 *  priority file wins; maps are merged recursively via [[ConfigValue.deepMerge]].
 *
 *  Returns `None` when no sidecar files exist at all. */
private[cli] def loadSidecarConfig(sscPath: os.Path): Option[scalascript.config.ConfigValue] =
  val base = sscPath / os.up / sscPath.last.stripSuffix(".ssc")
  // Priority order ascending (last = highest priority = wins conflicts).
  val exts = List("ssc", "json", "yml", "yaml", "hocon", "conf")

  def loadOne(p: os.Path): Option[scalascript.config.ConfigValue] =
    if !os.exists(p) then None
    else
      try
        // .ssc files contain frontmatter + Scala code; extract only the YAML
        // frontmatter (between the first pair of `---` delimiters) before parsing.
        val raw = os.read(p)
        val content =
          if p.ext == "ssc" then
            val lines = raw.linesIterator.toList
            if lines.headOption.exists(_.trim == "---") then
              val body = lines.tail
              val end  = body.indexWhere(_.trim == "---")
              if end >= 0 then body.take(end).mkString("\n") else ""
            else ""
          else raw
        if content.isBlank then None
        else
          val fmt = scalascript.config.ConfigParser.detectFormat(p.last)
          scalascript.config.ConfigParser.parse(content, fmt, java.nio.file.Path.of(p.toString).getParent) match
            case Right(cv) => Some(cv)
            case Left(err) =>
              System.err.println(s"[warn] sidecar config ${p.last}: ${err.getMessage}")
              None
      catch case e: Exception =>
        System.err.println(s"[warn] sidecar config ${p.last}: ${e.getMessage}")
        None

  val loaded = exts.flatMap { ext =>
    loadOne(os.Path(base.toString + "." + ext))
  }
  if loaded.isEmpty then None
  else Some(loaded.reduce((a, b) => a.deepMerge(b)))

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
    |  new <name> --template plugin
    |                         Create a ScalaScript community plugin starter project.
    |  install [--prefix <dir>]
    |                         Install ssc to a system prefix (default: ~/.local).
    |                         Copies runtime libs and std/ to <prefix>/lib/ssc/,
    |                         writes a launcher to <prefix>/bin/ssc.
    |                         Pass a plugin path/name to install a .sscpkg instead:
    |                           ssc install <path|name>  (shortcut for ssc plugin install)
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
    |                         Flags: --frontend <custom|react|solid|vue|electron|swing|javafx|swiftui>  (overrides frontmatter frontend:)
    |                                --backend jvm-rest with --frontend electron starts split JVM REST + Electron mode
    |                                --target desktop-jvm starts split JVM REST + Electron mode
    |                                --mode server starts only the JVM backend/server
    |                                --mode client --frontend electron --server-url <url> starts only the Electron client
    |                                --mode client --frontend <react|solid|vue|custom> --server-url <url> starts a local browser preview
    |                                --transport <http|in-process> selects full-stack transport; in-process uses interpreter dispatch (no HTTP socket)
    |                                --host <addr> / --port <n> controls web preview bind address/port; server mode uses --port for simple serve(port)
    |                                --open-browser / --no-open-browser controls browser auto-open for web preview
    |  watch                  Run .ssc and re-run on every file change
    |                         Flags: --frontend <custom|react|solid|vue|swing>  (overrides frontmatter frontend:)
    |  watch-bench            Benchmark one watch reload cycle on a temp copy
    |                         Flags: --cycles <n>, --target-ms <n>, --require-target
    |  repl                   Start interactive REPL (blank line runs, :quit exits)
    |  compile                Compile and run .ssc on JVM via scala-cli
    |                         Flags: --server-backend <jdk|jetty|netty>
    |                                (picks the HttpServerSpi impl — defaults to jdk;
    |                                 jetty/netty auto-add the //> using dep directive)
    |  package [flags] <f>    Package .ssc via scala-cli package (see flags below)
    |  emit-scala             Print generated Scala 3 script to stdout
    |  emit-spark             Print generated Scala 3 + Spark program to stdout (Phase 1: local)
    |  submit                 Package .ssc as a Spark fat JAR and launch via spark-submit
    |                         Flags: --spark-master <url>, --spark-version <v>, --dry-run
    |                         Pass extra spark-submit args after `--` (e.g. --executor-memory 4g)
    |  emit-js                Transpile .ssc to JavaScript (Node server) and print to stdout
    |                         Flags: --no-tree-shake  (emit all symbols; skip dead-code elimination)
    |                                --stats          (print tree-shaking summary to stderr)
    |  emit-wasm              Compile .ssc scala/scalascript blocks to WebAssembly via Scala.js (writes .wasm + .js)
    |  emit-spa               Wrap .ssc as a browser SPA (HTML + embedded JS) and print to stdout
    |                         Flags: --frontend <custom|react|solid|vue>
    |                                (picks the FrontendFrameworkSpi impl — defaults to first-found;
    |                                 controls which framework SPI downstream codegen targets)
    |  emit-wc                Emit each component object as a W3C Custom Element bundle
    |  emit-interface         Extract module interface to .scim artifact (v2.0)
    |  emit-ir                Emit normalised module IR to .scir artifact (v2.0)
    |  run-jvm                Compile .ssc via JvmGen and run immediately via scala-cli
    |  run-js                 Compile .ssc via JsGen and run immediately via node
    |  compile-jvm            Emit JVM-backend cached Scala source to .scjvm artifact (v2.0)
    |  compile-js             Emit JS-backend cached JS source to .scjs artifact (v2.0)
    |  check-with-iface       Type-check .ssc consuming pre-compiled .scim interfaces (v2.0)
    |  link                   Link .scim/.scir artifact pairs into a merged module (v2.0)
    |  generate-facade <dir> [-o <outDir>]
    |                         Read .scim artifacts; emit Scala 3 facade sources (v2.0 interop Tier 3)
    |  info <artifact>        Inspect a .scim/.scir/.scjvm/.scjs file (envelope + key fields)
    |                         Pass --json to dump the full envelope as pretty-printed JSON
    |  clean <dir>            Remove stale v2.0 artifacts whose source .ssc no longer exists.
    |                         Flags: --dry-run (print, don't delete), --all (wipe everything).
    |  verify <artifact-dir>  Health-check every v2.0 artifact in a directory.
    |                         Validates envelope, sourceHash shape, cross-refs, and runtime
    |                         coverage.  Flags: --strict (also re-hash source files),
    |                         --src-dir <dir> (default: artifact-dir/..), --json.
    |  serve                  Start HTTP server serving .ssc files as web pages
    |  parse                  Parse .ssc files and print AST
    |  check                  Type-check .ssc files (parse + typer only; no codegen)
    |                         Flags: --json (structured JSON output)
    |                                --quiet (no output; exit code only — for CI hooks)
    |                                --watch (re-check on file change; Ctrl-C to stop)
    |                                --iface-dir <dir> / -I <dir> (pre-compiled .scim interfaces)
    |                         Exit codes: 0=clean, 1=type errors, 2=parse errors, 3=file not found
    |                         Accepts files or directories (recursively checks *.ssc)
    |  test <file(s)>         Run component unit tests — each test is (name, () => Boolean).
    |                         Prints PASS/FAIL per test; exits non-zero on any failure.
    |                         Tests are functions registered with test(name, thunk) in the
    |                         file, or in a sibling *-test.ssc file.
    |  preview <file>         Open a browser preview page showing each component variant
    |                         declared in the front-matter variants: list.  Storybook-lite.
    |  lock <file>            Pin all URL/dep imports in ssc.lock (SHA-256 integrity)
    |  lock check <file>      Verify all URL/dep imports match ssc.lock
    |  fmt [--check|--stdout] <file(s)>
    |                         Format .ssc files in-place (default).
    |                         --check  Exit non-zero if any file needs formatting (CI mode).
    |                         --stdout Print formatted output to stdout (single file only).
    |  profile [--top N] [--output <profile.json>] <file.ssc>
    |                         Run with lightweight call-level profiling; print top-N
    |                         hotspots by wall time.  --top defaults to 20.
    |  lsp                    Run the Language Server Protocol server over stdio (v2.0)
    |  run   --frontend electron <f>     Compile .ssc and open in an Electron desktop window
    |  run-jvm --frontend swing <f>      Compile and launch the JDK-only Swing desktop frontend
    |  run-jvm --frontend javafx <f>     Compile and launch the OpenJFX desktop frontend
    |  build --target desktop <f>        Generate Electron bundle; run npm run build to package
    |  build --target ios <f>             Generate SwiftUI iOS Swift package
    |  build --target macos <f>          Generate SwiftUI macOS Swift package
    |  package --target macos <f>        Generate Swift package + run swift build (ready-to-run binary)
    |  package --target ios <f>          Archive + export signed .ipa (requires Xcode + Apple Developer)
    |    --export-method <m>            distribution method: development|ad-hoc|enterprise|app-store (default: development)
    |    --team-id <id>                 Apple Developer Team ID (or set SSC_TEAM_ID env var)
    |  package --target macos --distribution <f>  Codesign + notarize + DMG (requires Developer ID cert)
    |    --no-dmg                       Skip DMG creation (produce signed .app only)
    |    --no-notarize                  Skip notarization (useful for internal distribution)
    |  publish --target ios <f>         Upload to TestFlight or App Store via fastlane
    |    --testflight                   Upload to TestFlight
    |    --appstore                     Submit to App Store
    |    --fastlane                     Use existing Fastfile instead of generating one
    |    --api-key-path <p>            App Store Connect API key path (.p8) or APP_STORE_CONNECT_API_KEY_PATH env
    |    --submit-for-review            Auto-submit for review after upload (App Store only)
    |    --release-notes <text>        What's new text for TestFlight
    |  publish --target macos <f>      Upload to Mac App Store via fastlane
    |    --appstore                     Submit to Mac App Store
    |    --fastlane                     Use existing Fastfile
    |    --submit-for-review            Auto-submit for review
    |  run   --target macos <f>          Build SwiftUI macOS app and launch it
    |  run   --target ios <f>            Build + boot iOS Simulator + install + launch
    |  run   --target ios --device <f>  Build + deploy to USB device via ios-deploy
    |    --device-id <udid>             Target specific device (default: first connected)
    |    --console / --no-console       Stream app logs and block (default: --console)
    |    --rebuild / --no-rebuild       Force full rebuild vs incremental (default: --no-rebuild)
    |  toolchain <sub>        Manage native/desktop/mobile build toolchains:
    |    check  [--target <t>]  Detect installed tools (all targets or a specific one)
    |    install [--target <t>] Auto-install missing tools via Coursier/Homebrew/mise/apt
    |    list   [--target <t>]  Print installed tools and their versions
    |    Targets: web, desktop, mobile-android, ios, macos,
    |             desktop-linux, desktop-windows, all
    |  help                   Show this help message
    |
    |Package flags (passed through to scala-cli package):
    |  --lib                  Pack a library source tree into a .ssclib ZIP archive
    |                           ssc package --lib [<dir>] [-o my-lib-1.0.ssclib] [--manifest ssclib-manifest.yaml]
    |                           Reads <dir>/ssclib-manifest.yaml; falls back to a generated manifest.
    |  --assembly             Fat JAR with all dependencies bundled
    |  --standalone           Self-contained binary (like the ssc binary itself)
    |  --native               GraalVM native image (requires native-image)
    |  -o, --output <path>    Output file (default: input filename without .ssc)
    |  --force, -f            Overwrite existing output
    |  (any other scala-cli package flag is forwarded as-is)
    |
    |Compiler diagnostic flags:
    |  --Ystats               Print per-phase compiler timing table after each build
    |
    |Logging flags:
    |  --quiet                                  Silence third-party library logs (sets SLF4J threshold to error)
    |  --logs.defaultLevel=<level>              ssc logger root level (warn|info|debug|error; default: warn)
    |  --logs.<name>.level=<level>              Per-logger level, e.g. --logs.scalascript.server.level=info
    |  --logs.logFile=System.out                Write ssc logs to stdout instead of stderr
    |  Any --logs.KEY=VALUE maps to -Dscalascript.logger.KEY=VALUE
    |  Third-party lib SLF4J level: edit simplelogger.properties on the classpath
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
  // `ssc serve file.ssc` — run a .ssc server script with hot-reload.
  // `ssc serve [port] [dir]` — serve static files from a directory.
  args.headOption match
    case Some(f) if f.endsWith(".ssc") => watchCommand(args)
    case _ =>
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
    "query"       -> Value.EmptyMap,
    "headers"     -> Value.EmptyMap,
    "body"        -> Value.EmptyStr,
    "form"        -> Value.EmptyMap,
    "files"       -> Value.EmptyMap,
    "session"     -> Value.EmptyMap,
    "bearerToken" -> Value.NoneV,
    "jwtClaims"   -> Value.NoneV,
    "basicAuth"   -> Value.NoneV
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

// ─── Structured parse-error reporting ───────────────────────────────────────
//
// When `Parser.parse` produces a `Content.CodeBlock` whose `tree` is empty AND
// `parseError` is populated, the CLI emits a structured diagnostic with a
// line/column reference and a 3-line snippet (instead of the historical opaque
// "Failed to parse scalascript code block").  `reportCodeBlockParseErrors`
// walks every section of a parsed `Module`, prints one diagnostic per failing
// block to stderr, and returns `true` if any were emitted.  Callers use the
// return value to short-circuit before running expensive codegen passes that
// would otherwise produce a confusing downstream failure.

/** Walk `module.sections` (and subsections) and print one structured parse
 *  diagnostic for each `Content.CodeBlock` whose `tree` is empty and that
 *  carries a `parseError`.  Returns `true` iff at least one diagnostic was
 *  emitted; the caller is then expected to bail out with a non-zero exit code. */
private def reportCodeBlockParseErrors(module: Module, file: String): Boolean =
  var any = false
  def walk(s: Section): Unit =
    s.content.foreach {
      case cb: Content.CodeBlock if cb.tree.isEmpty && cb.parseError.isDefined =>
        printCodeBlockParseError(file, cb.parseError.get)
        any = true
      case _ => ()
    }
    s.subsections.foreach(walk)
  module.sections.foreach(walk)
  any

/** Print one structured parse-error diagnostic to stderr.  Format matches the
 *  spec in the v2.0 parse-error-positions task:
 *
 *      error: failed to parse scalascript block in <file>:<line>:<col>
 *      <message>
 *
 *        <prev line>
 *        <failing line>
 *        <space-padded ^>
 *        <next line>
 */
private def printCodeBlockParseError(file: String, err: CodeBlockParseError): Unit =
  System.err.println(s"error: failed to parse scalascript block in $file:${err.line}:${err.column}")
  System.err.println(err.message)
  if err.snippet.nonEmpty then
    System.err.println()
    System.err.println(err.snippet)

// ─────────────────────────────────────────────────────────────────────────────
// ssc build --incremental  —  v2.0 separate compilation build orchestrator
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc build --incremental <src-dir> [--artifact-dir <dir>] [--backend <id>]`
 *
 *  Walks `src-dir` for `.ssc` files, builds the dependency graph via
 *  `ModuleGraph`, and recompiles only stale modules (i.e. those whose source
 *  hash has changed or whose artifacts don't exist yet).
 *
 *  Artifact set emitted per module depends on `--backend`:
 *  - default / no `--backend`: `.scim` + `.scir` (interface + IR).
 *  - `--backend jvm`:          `.scim` + `.scir` + `.scjvm` (interface + IR +
 *                              JVM-backend cached Scala source).
 *  - `--backend js`:           `.scim` + `.scir` + `.scjs`  (interface + IR +
 *                              JS-backend cached JavaScript source).
 *
 *  The `.scjvm` / `.scjs` artifact carries the backend's emitted source for
 *  the single module so `ssc link --backend jvm|js` can textually splice
 *  per-module sources without re-running codegen — the user-visible win for
 *  incremental builds.
 *
 *  Artifacts are written to `--artifact-dir` (default `<src-dir>/.ssc-artifacts/`).
 *
 *  Cycle detection: if the dependency graph contains a cycle the build errors
 *  immediately listing the cyclic files.
 *
 *  After building, the artifacts can be linked via `ssc link <artifact-dir>`
 *  (with `--backend jvm` to pick up the `.scjvm` cache).
 *
 *  v2.0 / Stage 6.
 */
def incrementalBuildCommand(args: List[String]): Unit =
  import scalascript.artifact.{ModuleGraph, InterfaceExtractor, ArtifactIO}
  import scalascript.transform.Normalize

  var artifactDirArg: Option[String] = None
  var backendArg:     Option[String] = None
  var srcDirArg:      Option[String] = None
  var sectionCache:   Boolean        = false
  var sectionCacheMode: String       = "cumulative" // "cumulative" (Option A) or "interface" (Option B)
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--artifact-dir" if it.hasNext => artifactDirArg = Some(it.next())
      case "--backend"      if it.hasNext => backendArg     = Some(it.next())
      case "--section-cache"                            => sectionCache = true
      case s if s.startsWith("--section-cache=")        =>
        sectionCache = true
        sectionCacheMode = s.stripPrefix("--section-cache=")
        if sectionCacheMode != "cumulative" && sectionCacheMode != "interface" then
          System.err.println(s"build --incremental: --section-cache mode must be 'cumulative' or 'interface', got: $sectionCacheMode")
          System.exit(1)
      case d => srcDirArg = Some(d)

  if srcDirArg.isEmpty then
    System.err.println("Usage: ssc build --incremental <src-dir> [--artifact-dir <dir>] [--backend <id>] [--section-cache]")
    System.exit(1)

  val srcDir     = os.Path(srcDirArg.get, os.pwd)
  val artDir     = artifactDirArg.map(os.Path(_, os.pwd)).getOrElse(srcDir / ".ssc-artifacts")
  val selectedBackend = backendArg.orElse(ActiveFlags.current.backend)
  val emitJvm    = selectedBackend.contains("jvm")
  val emitJs     = selectedBackend.contains("js")

  if !os.isDir(srcDir) then
    System.err.println(s"build --incremental: '$srcDir' is not a directory"); System.exit(1)

  os.makeDir.all(artDir)

  if ActiveFlags.current.yStats then CompileStats.enable()

  // Build dependency graph.
  val graph = CompileStats.time("discover") { ModuleGraph.build(srcDir) }

  if graph.cycles.nonEmpty then
    System.err.println("build --incremental: circular dependencies detected:")
    graph.cycles.foreach { cycle =>
      System.err.println("  " + cycle.map(p => p.relativeTo(srcDir).toString).mkString(" → "))
    }
    System.exit(1)

  if graph.pkgCollisions.nonEmpty then
    System.err.println(s"build --incremental: warning — ${graph.pkgCollisions.length} package(s) shared across files:")
    graph.pkgCollisions.foreach { c =>
      System.err.println(s"  package '${c.pkg.mkString(".")}' claimed by:")
      c.paths.foreach(p => System.err.println(s"    - ${p.relativeTo(srcDir)}"))
    }
    System.err.println("  Sharing a `package:` across files is allowed (it groups a namespace),")
    System.err.println("  but if two files export the same symbol the linker dedup pass will")
    System.err.println("  drop the second occurrence.  Use `ssc link` to surface real collisions.")

  val nodes = graph.orderedNodes
  println(s"Discovered ${nodes.length} module(s) in ${srcDir.relativeTo(os.pwd)}" +
    (if emitJvm then "  (--backend jvm: emitting .scjvm)"
     else if emitJs then "  (--backend js: emitting .scjs)"
     else ""))

  var compiled = 0
  var skipped  = 0
  var failed   = 0

  // ── Parallel stale-check + parse ─────────────────────────────────────────
  // Staleness checks (SHA-256 artifact reads) and Parser.parse are pure with
  // no shared mutable state.  Run them in parallel via CompletableFuture so
  // the IO-bound stale checks and CPU-bound parses overlap.
  // Result type per node: (coreStale, jvmStale, jsStale, srcBytes?, module?)
  type PreScan = (Boolean, Boolean, Boolean, Option[Array[Byte]], Option[scalascript.ast.Module])
  val preParsed: Map[os.Path, PreScan] =
    val futures = nodes.map { node =>
      val f = java.util.concurrent.CompletableFuture.supplyAsync[PreScan](() =>
        val cs    = ModuleGraph.isStale(node.path, artDir)
        val vs    = emitJvm && ModuleGraph.isJvmStale(node.path, artDir)
        val es    = emitJs  && ModuleGraph.isJsStale(node.path, artDir)
        val stale = cs || vs || es
        if !stale then (cs, vs, es, None, None)
        else
          val bytes = scala.util.Try(os.read.bytes(node.path)).getOrElse(Array.emptyByteArray)
          val src   = new String(bytes, "UTF-8")
          val m     = scala.util.Try(CompileStats.time("parse") { Parser.parse(src) }).toOption
          (cs, vs, es, Some(bytes), m)
      )
      node.path -> f
    }
    futures.map((p, f) => p -> f.join()).toMap

  for node <- nodes do
    val relPath  = node.relPath(srcDir)
    val baseName = node.path.last.stripSuffix(".ssc")
    // Use pre-computed stale flags from the parallel phase.
    val (coreStale, jvmStale, jsStale, preSrcBytes, preModule) = preParsed(node.path)
    val stale     = coreStale || jvmStale || jsStale

    if !stale then
      println(s"  [skip]     $relPath (up-to-date)")
      skipped += 1
    else
      // ── Section-level diagnostic (opt-in via --section-cache) ────────
      //
      // When the flag is set, surface a per-module summary of which
      // sections are stale so users can verify the cumulative-hash
      // chain is doing what they expect.  The number does NOT change
      // codegen behaviour for the MVP — JvmGen / JsGen still process
      // the full module because sections share scope — but the line
      // gives operators a window into the cache.  Empty stored hashes
      // (pre-Phase-3 artifact) ⇒ "fully stale" wording.
      val sectionDiag: String =
        if !sectionCache then ""
        else
          val sectionIds =
            if sectionCacheMode == "interface"
            then ModuleGraph.staleSectionsInterfaceBased(node.path, artDir)
            else ModuleGraph.staleSections(node.path, artDir)
          val parsed     =
            preModule.getOrElse(scalascript.ast.Module(manifest = None, sections = Nil))
          val total = parsed.sections.length
          val modeTag = if sectionCacheMode == "interface" then " iface" else ""
          if total == 0 then ""
          else if sectionIds.length == total then s" [$total/$total sections stale$modeTag]"
          else s" [${sectionIds.length}/$total sections stale, ${total - sectionIds.length} cached$modeTag]"
      print(s"  [compile]  $relPath$sectionDiag ... ")
      // Unified-diagnostic capture: inner helpers (e.g.
      // `reportCodeBlockParseErrors`) emit structured diagnostics on
      // `System.err` because the standalone CLI surfaces (`compile-jvm
      // a.ssc`) want them on stderr.  But for `build --incremental`, a
      // CI consumer that pipes only stdout to a log should still see
      // both the "what" (FAIL) and the "why" (the diagnostic) — so we
      // redirect stderr to a buffer for the duration of the per-module
      // work, then on failure splice the buffer onto stdout under the
      // FAIL line (indented by 2 spaces).  MVP: redirect-stderr-to-buf
      // — keeps inner helpers untouched.  Long-term: refactor inner
      // helpers to return `Either[Diagnostic, _]` and format here.
      val capturedErr = new java.io.ByteArrayOutputStream()
      val savedErr    = System.err
      val capturedPs  = new java.io.PrintStream(capturedErr, true, "UTF-8")
      System.setErr(capturedPs)
      val ok =
        try scala.util.Try {
          val sourceBytes = preSrcBytes.getOrElse(os.read.bytes(node.path))
          val src         = new String(sourceBytes, "UTF-8")
          val module      = preModule.getOrElse(CompileStats.time("parse") { Parser.parse(src) })
          // Structured parse-error diagnostic: bail BEFORE Normalize / Typer
          // emit their own opaque secondary messages.  Returning a recognisable
          // exception keeps the `[compile] ... FAIL` line + the structured
          // diagnostic that `reportCodeBlockParseErrors` already wrote.
          if reportCodeBlockParseErrors(module, relPath) then
            throw new RuntimeException("parse error (see diagnostic above)")
          val ir          = CompileStats.time("normalize")  { Normalize(module) }
          val iface       = CompileStats.time("interface")  { InterfaceExtractor.extract(module, sourceBytes) }
          val sourceHash  = iface.sourceHash
          // When --section-cache is on, propagate the section-hash map
          // into every artifact (.scim already carries it from the extract
          // call above; .scir / .scjvm / .scjs need it explicitly).  When
          // off, persist an empty map so consumers see "no section data"
          // and fall through to the full-module-SHA path.
          val sectionHashes: Map[String, String] =
            if sectionCache then iface.sectionHashes else Map.empty

          val scimPath = artDir / (baseName + ".scim")
          val scirPath = artDir / (baseName + ".scir")

          // Always (re)write .scim + .scir if those are stale.
          if coreStale then
            // .scim already has sectionHashes populated by InterfaceExtractor.
            // When --section-cache is OFF, strip them so the on-disk artifact
            // doesn't carry data the user hasn't opted into yet (keeps the
            // wire shape identical to pre-Phase-3 for default builds).
            val ifaceToWrite =
              if sectionCache then iface
              else iface.copy(sectionHashes = Map.empty)
            CompileStats.time("write-scim") { ArtifactIO.writeInterfaceFile(ifaceToWrite, scimPath) }
            CompileStats.time("write-scir") { ArtifactIO.writeIrFile(ir, node.pkg, module.manifest.flatMap(_.name), sourceHash, scirPath, sectionHashes) }

          if emitJvm then
            val scjvmPath = artDir / (baseName + ".scjvm")
            // Only re-run JvmGen if the .scjvm itself is stale; if only the
            // .scim/.scir went stale (impossible without source change here,
            // since both share the SHA-256 check, but defensive) skip the
            // expensive codegen.
            if jvmStale || !os.exists(scjvmPath) then
              val baseDir     = Some(node.path / os.up)
              val scalaSource = CompileStats.time("jvm-codegen") { JvmGen.generate(module, baseDir) }
              val rawImports  = collectImports(module.sections)
              val depAliases  = module.manifest.toList.flatMap(_.dependencies.keys)
              val imports     = (rawImports ++ depAliases).distinct.toList
              val moduleId    = module.manifest.flatMap(_.name).getOrElse(baseName)
              JvmArtifactIO.writeJvmFile(
                moduleId, node.pkg, module.manifest.flatMap(_.name),
                sourceHash, scalaSource, imports, scjvmPath,
                sectionHashes = sectionHashes
              )

          if emitJs then
            val scjsPath = artDir / (baseName + ".scjs")
            // Only re-run JsGen if the .scjs itself is stale.
            if jsStale || !os.exists(scjsPath) then
              val baseDir    = Some(node.path / os.up)
              // v2.0 Phase 2: user-code-only emit; shared runtime ships
              // separately as `_runtime.scjs-runtime` in the artifact dir.
              val jsSource   = CompileStats.time("js-codegen") { JsGen.generateUserOnly(module, baseDir) }
              val rawImports = collectImports(module.sections)
              val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
              val imports    = (rawImports ++ depAliases).distinct.toList
              val moduleId   = module.manifest.flatMap(_.name).getOrElse(baseName)
              val moduleCaps: Set[String] =
                JsGen.detectCapabilities(module, baseDir).map(JsGen.Capability.encode)
              JsArtifactIO.writeJsFile(
                moduleId, node.pkg, module.manifest.flatMap(_.name),
                sourceHash, jsSource, imports, scjsPath,
                capabilities = moduleCaps.toList.sorted,
                sectionHashes = sectionHashes
              )
        } finally
          capturedPs.flush()
          System.setErr(savedErr)
      ok match
        case scala.util.Success(_) =>
          println("OK")
          compiled += 1
        case scala.util.Failure(e) =>
          println("FAIL")
          // Splice both the captured inner-helper stderr (e.g. the
          // YAML / parse diagnostic) and the catch-all exception
          // message onto stdout under the FAIL line, indented by 2
          // spaces so each line visually belongs to that module.
          val msg     = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          val errText = new String(capturedErr.toByteArray, "UTF-8")
          val seen    = new scala.collection.mutable.LinkedHashSet[String]
          // Captured stderr lines first — that's where structured
          // line/col/snippet output lives.
          errText.linesIterator.foreach(seen += _)
          // Then exception message lines (skip the "see diagnostic
          // above" placeholder when the structured diagnostic was
          // already captured).
          val msgLines = msg.linesIterator.toList
          val redundant = msg.contains("parse error (see diagnostic above)") && errText.nonEmpty
          if !redundant then msgLines.foreach(seen += _)
          // Indent each cause line by 4 spaces — 2 more than the
          // FAIL line's leading indent — so it visually nests under
          // the module summary.
          seen.foreach(line => println("    " + line))
          failed += 1

  // v2.0 Phase 2 — ensure the shared `_runtime.scjs-runtime` covers the
  // union of capabilities across every `.scjs` in `artDir`.  Runs after
  // the per-module loop so a single regeneration handles the whole batch.
  if emitJs && failed == 0 then
    val unionCaps = unionDepCapabilitiesJs(artDir)
    try ensureJsRuntimeArtifact(artDir, unionCaps)
    catch case e: Throwable =>
      System.err.println(s"build: shared JS runtime regeneration failed: ${e.getMessage}")
      failed += 1

  println()
  println(s"Done: $compiled compiled, $skipped up-to-date, $failed failed")
  println(s"Artifacts written to ${artDir.relativeTo(os.pwd)}")
  CompileStats.printAndReset()
  if failed > 0 then System.exit(1)

/** v2.0 Phase 5 — programmatic separate-compilation build helper.
 *
 *  Same artifact emission as `build --incremental` but returns a count of
 *  failures instead of calling `System.exit`.  Used by `bundle
 *  --with-artifacts` to refuse on input compile errors without terminating
 *  the outer ZIP-writing process.  Logs progress to `out` (default stdout).
 *
 *  Returns `(compiled, skipped, failed)`. */
private def buildArtifactsInto(
    srcDir:       os.Path,
    artDir:       os.Path,
    backend:      Option[String],
    out:          java.io.PrintStream = System.out
): (Int, Int, Int) =
  import scalascript.artifact.{ModuleGraph, InterfaceExtractor, ArtifactIO}
  import scalascript.transform.Normalize

  val emitJvm = backend.contains("jvm")
  val emitJs  = backend.contains("js")
  os.makeDir.all(artDir)

  if ActiveFlags.current.yStats then CompileStats.enable()

  val graph = CompileStats.time("discover") { ModuleGraph.build(srcDir) }
  if graph.cycles.nonEmpty then
    out.println("build (artifacts): circular dependencies detected:")
    graph.cycles.foreach { cycle =>
      out.println("  " + cycle.map(p => p.relativeTo(srcDir).toString).mkString(" → "))
    }
    return (0, 0, 1)
  if graph.pkgCollisions.nonEmpty then
    out.println(s"build (artifacts): warning — ${graph.pkgCollisions.length} shared `package:` value(s):")
    graph.pkgCollisions.foreach { c =>
      out.println(s"  package '${c.pkg.mkString(".")}' claimed by:")
      c.paths.foreach(p => out.println(s"    - ${p.relativeTo(srcDir)}"))
    }

  val nodes = graph.orderedNodes
  var compiled = 0
  var skipped  = 0
  var failed   = 0

  // Phase 3a: parallel read+parse pre-pass.
  // Parser.parse is stateless and thread-safe; staleness checks are read-only.
  // The main loop below stays sequential: normalize/interface/codegen/write
  // have cross-file ordering deps that prevent parallelism there.
  type PreParsed = (Boolean, Boolean, Boolean, Array[Byte], scalascript.ast.Module)
  val preCache = new java.util.concurrent.ConcurrentHashMap[os.Path, PreParsed]()
  CompileStats.time("par-parse") {
    val nCores = Runtime.getRuntime.availableProcessors().max(1)
    val pool   = new java.util.concurrent.ForkJoinPool(nCores.min(nodes.length.max(1)))
    try
      val tasks = nodes.map { node =>
        pool.submit[Unit] { () =>
          val cSt = ModuleGraph.isStale(node.path, artDir)
          val jSt = emitJvm && ModuleGraph.isJvmStale(node.path, artDir)
          val sSt = emitJs  && ModuleGraph.isJsStale(node.path, artDir)
          if cSt || jSt || sSt then
            val bytes  = os.read.bytes(node.path)
            val module = Parser.parse(new String(bytes, "UTF-8"))
            preCache.put(node.path, (cSt, jSt, sSt, bytes, module))
        }
      }
      tasks.foreach(_.get())
    finally pool.shutdown()
  }

  for node <- nodes do
    val relPath   = node.relPath(srcDir)
    val baseName  = node.path.last.stripSuffix(".ssc")
    val cached    = Option(preCache.get(node.path))
    val coreStale = cached.exists(_._1)
    val jvmStale  = cached.exists(_._2)
    val jsStale   = cached.exists(_._3)
    val stale     = coreStale || jvmStale || jsStale
    if !stale then
      skipped += 1
    else
      val ok = scala.util.Try {
        val (_, _, _, sourceBytes, module) = cached.get
        if reportCodeBlockParseErrors(module, relPath) then
          throw new RuntimeException(s"parse error in $relPath")
        val ir          = CompileStats.time("normalize") { Normalize(module) }
        val iface       = CompileStats.time("interface") { InterfaceExtractor.extract(module, sourceBytes) }
        val sourceHash  = iface.sourceHash
        val scimPath = artDir / (baseName + ".scim")
        val scirPath = artDir / (baseName + ".scir")
        if coreStale then
          CompileStats.time("write-scim") { ArtifactIO.writeInterfaceFile(iface.copy(sectionHashes = Map.empty), scimPath) }
          CompileStats.time("write-scir") { ArtifactIO.writeIrFile(ir, node.pkg, module.manifest.flatMap(_.name), sourceHash, scirPath) }
        if emitJvm then
          val scjvmPath = artDir / (baseName + ".scjvm")
          if jvmStale || !os.exists(scjvmPath) then
            val baseDir     = Some(node.path / os.up)
            val scalaSource = CompileStats.time("jvm-codegen") { JvmGen.generate(module, baseDir) }
            val rawImports  = collectImports(module.sections)
            val depAliases  = module.manifest.toList.flatMap(_.dependencies.keys)
            val imports     = (rawImports ++ depAliases).distinct.toList
            val moduleId    = module.manifest.flatMap(_.name).getOrElse(baseName)
            JvmArtifactIO.writeJvmFile(
              moduleId, node.pkg, module.manifest.flatMap(_.name),
              sourceHash, scalaSource, imports, scjvmPath
            )
        if emitJs then
          val scjsPath = artDir / (baseName + ".scjs")
          if jsStale || !os.exists(scjsPath) then
            val baseDir    = Some(node.path / os.up)
            val jsSource   = CompileStats.time("js-codegen") { JsGen.generateUserOnly(module, baseDir) }
            val rawImports = collectImports(module.sections)
            val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
            val imports    = (rawImports ++ depAliases).distinct.toList
            val moduleId   = module.manifest.flatMap(_.name).getOrElse(baseName)
            val moduleCaps: Set[String] =
              JsGen.detectCapabilities(module, baseDir).map(JsGen.Capability.encode)
            JsArtifactIO.writeJsFile(
              moduleId, node.pkg, module.manifest.flatMap(_.name),
              sourceHash, jsSource, imports, scjsPath,
              capabilities = moduleCaps.toList.sorted
            )
      }
      ok match
        case scala.util.Success(_) => compiled += 1
        case scala.util.Failure(e) =>
          out.println(s"  [compile error] $relPath: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
          failed += 1

  // Regenerate shared JS runtime if any .scjs were produced.
  if emitJs && failed == 0 then
    val unionCaps = unionDepCapabilitiesJs(artDir)
    try ensureJsRuntimeArtifact(artDir, unionCaps)
    catch case e: Throwable =>
      out.println(s"  [runtime error] ${e.getMessage}")
      failed += 1

  CompileStats.printAndReset(out)
  (compiled, skipped, failed)

// ─── Fat-JAR entry point ────────────────────────────────────────────────────

/** Entry point written into *fat* JARs by `ssc package --target ssc`.
 *  Reads the `.ssc` source packed at `META-INF/ssc/main.ssc`, writes it to a
 *  temp file, then calls `runCommand` exactly as `ssc run` would.
 *  (Thin-JAR builds use `SscThinLauncher` instead.) */
object SscJarMain:
  def main(argv: Array[String]): Unit =
    val stream = getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.ssc")
    if stream == null then
      System.err.println("ssc: no embedded main.ssc found in JAR (corrupt build?)")
      System.exit(1)
    val content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val tmp = java.nio.file.Files.createTempFile("ssc-jar-", ".ssc")
    tmp.toFile.deleteOnExit()
    java.nio.file.Files.writeString(tmp, content)
    runCommand(tmp.toString :: argv.toList)

// ─── Thin-JAR launcher (Scala, lives in lib/ssc.jar) ────────────────────────

/** Called by {@code SscThinBootstrap} (Java) after the ssc lib is loaded via
 *  URLClassLoader.  Reads the embedded `.sscc` (pre-compiled AST) or `.ssc`
 *  (source fallback) from the thin JAR and runs via {@code runCommand}. */
object SscThinLauncher:
  def main(argv: Array[String]): Unit =
    // Try .sscc first (pre-compiled AST), fall back to .ssc source
    val (bytes, suffix) =
      Option(getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.sscc"))
        .map(s => (s.readAllBytes(), ".sscc"))
        .orElse(
          Option(getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.ssc"))
            .map(s => (s.readAllBytes(), ".ssc"))
        )
        .getOrElse {
          System.err.println("ssc: no embedded main.sscc or main.ssc found (corrupt thin JAR?)")
          System.exit(1)
          throw new AssertionError()
        }
    val tmp = java.nio.file.Files.createTempFile("ssc-jar-", suffix)
    tmp.toFile.deleteOnExit()
    java.nio.file.Files.write(tmp, bytes)
    runCommand(tmp.toString :: argv.toList)

// ─── Thin-JAR build ──────────────────────────────────────────────────────────

/** Pack `sscFile` + `SscThinBootstrap*.class` (extracted from the live ssc.jar)
 *  into a minimal thin JAR.  The `.ssc` source is parsed at build time and
 *  embedded as a `.sscc` binary (magic + version + msgpack AST) so that the
 *  runtime can skip markdown/YAML/scalameta parsing entirely.
 *  `SscThinBootstrap` is a pure-Java class that locates the ssc lib at
 *  runtime and delegates via URLClassLoader — no Scala runtime bundled. */
private def buildThinJar(sscFile: os.Path, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}
  import scalascript.ast.SsccFormat
  import scalascript.parser.Parser

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val sscJar  = libRoot / "ssc.jar"

  // Parse and serialize the AST now so the runtime skips parsing entirely.
  val module    = Parser.parse(os.read(sscFile))
  val ssccBytes = SsccFormat.write(module)

  os.makeDir.all(outJar / os.up)

  val fos  = new FileOutputStream(outJar.toIO)
  val jos  = new JarOutputStream(fos)
  val seen = scala.collection.mutable.HashSet.empty[String]
  try
    // Extract only SscThinBootstrap*.class from ssc.jar (pure Java, no Scala runtime dep)
    if os.exists(sscJar) then
      val zis = new ZipInputStream(new FileInputStream(sscJar.toIO))
      try
        var entry = zis.getNextEntry()
        while entry != null do
          val n = entry.getName
          if !entry.isDirectory && n.contains("SscThinBootstrap") && seen.add(n) then
            jos.putNextEntry(new ZipEntry(n))
            zis.transferTo(jos)
            jos.closeEntry()
          zis.closeEntry()
          entry = zis.getNextEntry()
      finally zis.close()

    // Embed the pre-compiled AST as .sscc (SscThinBootstrap tries this first)
    jos.putNextEntry(new ZipEntry("META-INF/ssc/main.sscc"))
    jos.write(ssccBytes)
    jos.closeEntry()

    val manifest =
      s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscThinBootstrap
Ssc-Source: ${sscFile.last}
Ssc-Format: sscc/${SsccFormat.CurrentVersion}
"""
    jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
    jos.write(manifest.getBytes("UTF-8"))
    jos.closeEntry()
  finally
    jos.close()

// ─── JVM bootstrap-JAR build ─────────────────────────────────────────────────

/** Compile `sscFile` via JvmGen → scala-cli `--library`, then pack the compiled
 *  classes together with `SscJvmBootstrap.class` into a thin bootstrap JAR.
 *
 *  The `.sc` temp file is named `<sanitizedName>.sc` so the compiled main class
 *  is predictably `<sanitizedName>_sc` (scala-cli derives the class name from
 *  the filename).  The name is stored in `Ssc-Main-Class` in the manifest so
 *  `SscJvmBootstrap` can find it at runtime without scanning. */
private def buildJvmBootstrapJar(sscFile: os.Path, name: String, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}

  val scalaSource   = expectText(compileViaBackend("jvm", sscFile), "build --target jvm")
  val sanitized     = name.replaceAll("[^a-zA-Z0-9_]", "_")
  val mainClass     = s"${sanitized}_sc"

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val sscJar  = libRoot / "ssc.jar"

  val tmpDir      = os.temp.dir()
  val tmpSc       = tmpDir / s"$sanitized.sc"
  val compiledJar = tmpDir / "compiled.jar"
  try
    os.write(tmpSc, scalaSource)
    val r = os.proc(
      "scala-cli", "--power", "package", tmpSc,
      "--library", "--server=false", "-o", compiledJar.toString
    ).call(stdout = os.Pipe, stderr = os.Inherit, cwd = tmpDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)

    os.makeDir.all(outJar / os.up)
    val seen = scala.collection.mutable.HashSet.empty[String]
    val jos  = new JarOutputStream(new FileOutputStream(outJar.toIO))
    try
      // SscJvmBootstrap.class from ssc.jar
      if os.exists(sscJar) then
        val zis = new ZipInputStream(new FileInputStream(sscJar.toIO))
        try
          var entry = zis.getNextEntry()
          while entry != null do
            val n = entry.getName
            if !entry.isDirectory && n.contains("SscJvmBootstrap") && seen.add(n) then
              jos.putNextEntry(new ZipEntry(n))
              zis.transferTo(jos)
              jos.closeEntry()
            zis.closeEntry()
            entry = zis.getNextEntry()
        finally zis.close()

      // Compiled app classes from scala-cli --library output
      val zis2 = new ZipInputStream(new FileInputStream(compiledJar.toIO))
      try
        var entry = zis2.getNextEntry()
        while entry != null do
          val n = entry.getName
          if n != "META-INF/MANIFEST.MF" && seen.add(n) then
            jos.putNextEntry(new ZipEntry(n))
            if !entry.isDirectory then zis2.transferTo(jos)
            jos.closeEntry()
          zis2.closeEntry()
          entry = zis2.getNextEntry()
      finally zis2.close()

      val manifest =
        s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscJvmBootstrap
Ssc-Main-Class: $mainClass
Ssc-Source: ${sscFile.last}
"""
      jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
      jos.write(manifest.getBytes("UTF-8"))
      jos.closeEntry()
    finally jos.close()
    println(s"→ ${displayPath(outJar)}")
  finally
    os.remove.all(tmpDir)

// ─── Fat-JAR packaging ───────────────────────────────────────────────────────

/** Pack `sscFile` together with the full ssc interpreter runtime into
 *  a self-contained fat JAR at `outJar`.  Running `java -jar outJar`
 *  launches the `.ssc` program via the embedded interpreter.
 *
 *  All JARs from `<ssc.lib.path>/bin/lib/jars/` and `bin/lib/ssc.jar`
 *  are merged; duplicate entries (other than `META-INF/MANIFEST.MF`) are
 *  silently deduplicated (first-seen wins — safe for class files). */
private def buildFatJar(sscFile: os.Path, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val runtimeJars =
    os.list(libRoot / "jars").filter(_.ext == "jar").sorted.toList :+
    (libRoot / "ssc.jar")

  os.makeDir.all(outJar / os.up)

  val seen = scala.collection.mutable.HashSet.empty[String]
  val fos  = new FileOutputStream(outJar.toIO)
  val jos  = new JarOutputStream(fos)
  try
    // Merge runtime JARs (skip META-INF/MANIFEST.MF — we write our own)
    for jar <- runtimeJars if os.exists(jar) do
      val zis = new ZipInputStream(new FileInputStream(jar.toIO))
      try
        var entry = zis.getNextEntry()
        while entry != null do
          val name = entry.getName
          if name != "META-INF/MANIFEST.MF" && !seen.contains(name) then
            seen += name
            jos.putNextEntry(new ZipEntry(name))
            if !entry.isDirectory then zis.transferTo(jos)
            jos.closeEntry()
          zis.closeEntry()
          entry = zis.getNextEntry()
      finally zis.close()

    // Embed the .ssc source
    jos.putNextEntry(new ZipEntry("META-INF/ssc/main.ssc"))
    jos.write(os.read.bytes(sscFile))
    jos.closeEntry()

    // Write manifest last so Main-Class wins over any runtime manifest
    val manifest =
      s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscJarMain
Ssc-Source: ${sscFile.last}
"""
    jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
    jos.write(manifest.getBytes("UTF-8"))
    jos.closeEntry()
  finally
    jos.close()

// ─── Project-file build ───────────────────────────────────────────────────────

/** Build a single `.ssc` (or sidecar config) project file.
 *
 *  @param projectFile  The `.ssc` entry point.
 *  @param targetOpt    Explicit `--target` from CLI; `None` = read frontmatter.
 *  @param baseOutDir   Root output directory (`target/` by default).
 *                      Artifacts land in `baseOutDir/<target>/` — e.g. `target/ssc/` or `target/jvm/`.
 *  @param fat          `true` when called from `ssc package` — produces self-contained artifacts. */
private def buildProjectFileCommand(
    projectFile: os.Path,
    targetOpt: Option[String],
    baseOutDir: os.Path,
    fat: Boolean = false
): Unit =
  val manifest =
    scala.util.Try(scalascript.parser.Parser.parse(os.read(projectFile)).manifest)
      .toOption.flatten

  val name    = manifest.flatMap(_.name).getOrElse(projectFile.last.stripSuffix(".ssc"))
  val sidecar = loadSidecarConfig(projectFile)

  val target = targetOpt
    .orElse(manifest.flatMap(_.targets.headOption))
    .orElse(sidecar.flatMap(_.get("target").flatMap(_.getString)))
    .getOrElse("ssc")

  val outDir = baseOutDir / target
  os.makeDir.all(outDir)

  target match
    case "ssc" =>
      val outJar = outDir / s"$name.jar"
      if fat then
        print(s"Building $name.jar (ssc, standalone fat-JAR)... ")
        buildFatJar(projectFile, outJar)
        println(s"→ ${displayPath(outJar)}  (${outJar.toIO.length / 1024 / 1024} MB)")
      else
        print(s"Building $name.jar (ssc, thin launcher)... ")
        buildThinJar(projectFile, outJar)
        println(s"→ ${displayPath(outJar)}  (${outJar.toIO.length / 1024} KB)")

    case "jvm" =>
      val outJar = outDir / s"$name.jar"
      if fat then
        // ssc package: --assembly → standalone fat JAR with all dependencies.
        println(s"Building $name.jar (jvm, fat assembly)...")
        val scalaSource = expectText(compileViaBackend("jvm", projectFile), "package --target jvm")
        val tmp = os.temp(scalaSource, suffix = ".sc")
        try
          val result = os.proc(
            "scala-cli", "--power", "package", tmp,
            "--assembly", "--server=false", "-o", outJar.toString
          ).call(stdout = os.Pipe, stderr = os.Inherit, cwd = os.pwd, check = false)
          if result.exitCode != 0 then System.exit(result.exitCode)
          println(s"→ ${displayPath(outJar)}")
        finally os.remove(tmp)
      else
        // ssc build: bootstrap JAR — compiled bytecode + SscJvmBootstrap; lib resolved at runtime.
        println(s"Building $name.jar (jvm, bootstrap)...")
        buildJvmBootstrapJar(projectFile, name, outJar)

    case "js" =>
      val outJs = outDir / s"$name.js"
      print(s"Building $name.js (js)... ")
      val oldOut = System.out
      val buf    = new java.io.ByteArrayOutputStream
      System.setOut(new java.io.PrintStream(buf))
      try emitJsCommand(List(projectFile.toString))
      finally System.setOut(oldOut)
      os.write.over(outJs, buf.toByteArray)
      println(s"→ ${displayPath(outJs)}")

    case "web" =>
      println(s"Building web (static HTML) → ${displayPath(outDir)}")
      buildSingleFileSite(projectFile, outDir)

    case "desktop" | "desktop-electron" =>
      val bundleDir = outDir
      println(s"Building Electron bundle → ${displayPath(bundleDir)}")
      buildElectronBundle(projectFile, bundleDir)
      println(s"  bundle written.  To package:")
      println(s"    cd ${displayPath(bundleDir)} && npm install && npm run build")

    case "ios" | "mobile-ios" =>
      println(s"Building SwiftUI iOS package → ${displayPath(outDir)}")
      buildSwiftUIPackage(projectFile, outDir, "ios", runSwiftBuild = fat)
      if !fat then
        println(s"  Swift package written.  To build:")
        println(s"    cd ${displayPath(outDir)} && swift build")

    case "macos" | "desktop-macos" =>
      println(s"Building SwiftUI macOS package → ${displayPath(outDir)}")
      buildSwiftUIPackage(projectFile, outDir, "macos", runSwiftBuild = fat)
      if !fat then
        println(s"  Swift package written.  To build:")
        println(s"    cd ${displayPath(outDir)} && swift build")

    case other =>
      System.err.println(s"ssc build: unknown target '$other'  (valid: ssc, jvm, js, web, desktop, ios, macos)")
      System.exit(1)

/** Write an Electron app bundle (index.html, app.js, main.js, preload.js,
 *  package.json) to `outDir`, compiled from the given `.ssc` file. */
private def buildElectronBundle(
    sscFile:        os.Path,
    outDir:         os.Path,
    backendBaseUrl: Option[String] = None,
    desktopToken:   Option[String] = None
): Unit =
  scalascript.frontend.electron.ElectronBundleBuilder.build(
    sscFile, outDir, backendBaseUrl = backendBaseUrl, desktopToken = desktopToken)

/** Compile `sscFile` via JvmGen (frontend=swiftui) and emit a Swift Package
 *  to `outDir`.  `platform` is either `"ios"` or `"macos"`.
 *  When `runSwiftBuild` is true, also invoke `swift build` in `outDir`.
 *  Requires `scala-cli` on PATH. */
private def buildSwiftUIPackage(
    sscFile: os.Path, outDir: os.Path, platform: String, runSwiftBuild: Boolean = false
): Unit =
  if !JvmBytecode.scalaCliAvailable then
    System.err.println(s"build --target mobile-ios/macos: ${JvmBytecode.scalaCliMissingMessage}")
    System.exit(1)
  val module  = Parser.parse(os.read(sscFile))
  val baseDir = Some(sscFile / os.up)
  val raw     = JvmGen.generate(module, baseDir, frontendOverride = Some("swiftui"))
  val jarsDir = scalascript.imports.ImportResolver.libPath.map(_ / "bin" / "lib" / "jars")
  val source  = jarsDir match
    case Some(jars) => patchLocalSscDeps(raw, jars)
    case None       => raw
  os.makeDir.all(outDir)
  val tmp = os.temp(source, suffix = ".sc", deleteOnExit = true)
  try
    val result = os.proc(
      "scala-cli", "run", tmp, "--server=false",
      s"-J-Dssc.build.outdir=${outDir}",
      s"-J-Dssc.build.platform=$platform"
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = os.pwd, check = false)
    if result.exitCode != 0 then System.exit(result.exitCode)
  finally
    scala.util.Try(os.remove(tmp))
  if runSwiftBuild then
    println(s"  Running swift build in ${displayPath(outDir)}...")
    val swiftResult = os.proc("swift", "build")
      .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if swiftResult.exitCode != 0 then System.exit(swiftResult.exitCode)

/** Derive the Swift product name from the .ssc `name:` frontmatter, matching
 *  SwiftUIEmitter.swiftIdent so we know where swift build / xcodebuild puts the binary. */
private def swiftAppName(sscName: Option[String]): String =
  val raw = sscName.getOrElse("ScalaScript App")
  raw.filter(c => c.isLetterOrDigit || c == '_').capitalize match
    case ""  => "App"
    case str => if str.head.isDigit then s"App$str" else str

/** Return (udid, name) of the latest available iPhone simulator, or None. */
private def pickIosSimulator(): Option[(String, String)] =
  val result = os.proc("xcrun", "simctl", "list", "devices", "available", "--json")
    .call(check = false, stderr = os.Pipe)
  if result.exitCode != 0 then None
  else
    scala.util.Try {
      val json   = ujson.read(result.out.text())
      val devMap = json.obj.get("devices").map(_.obj).getOrElse(
        ujson.Obj().obj
      )
      // Sort iOS runtime keys descending so we try the latest SDK first
      val iosKeys = devMap.keys
        .filter(k => k.contains("iOS") && !k.contains("watchOS") && !k.contains("tvOS"))
        .toList.sorted.reverse
      iosKeys.iterator.flatMap { key =>
        val devs = devMap.get(key).map(_.arr).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
        devs.toList
          .filter { d =>
            d.obj.get("isAvailable").exists(_.bool) &&
            d.obj.get("name").map(_.str).exists(_.startsWith("iPhone"))
          }
          .sortBy(_.obj("name").str)
          .reverse
          .headOption
          .map(d => (d.obj("udid").str, d.obj("name").str))
      }.nextOption()
    }.toOption.flatten

/** Full `ssc run --target ios` flow: generate Swift Package → xcodebuild →
 *  boot simulator → install → launch (optionally streaming logs). */
private def runSwiftUIIosSimulator(
    sscFile: os.Path, outDir: os.Path, console: Boolean, forceRebuild: Boolean
): Unit =
  // Pre-flight: xcodebuild must be present
  if os.proc("xcodebuild", "-version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: Xcode is required for --target ios.\n" +
      "Run: ssc toolchain check --target ios"
    )
    System.exit(1)

  val module  = Parser.parse(os.read(sscFile))
  val appName = swiftAppName(module.manifest.flatMap(_.name))
  val bundleId = module.manifest
    .flatMap(_.raw.get("bundle-id").collect { case s: String => s })
    .getOrElse("com.example.app")

  val derivedDataPath = outDir / "derived"
  val appPath = derivedDataPath / "Build" / "Products" / "Debug-iphonesimulator" / s"$appName.app"

  // Pick simulator before building (needed for -destination)
  val (simUdid, simName) = pickIosSimulator().getOrElse {
    System.err.println(
      "Error: No available iOS Simulator found.\n" +
      "Install a simulator runtime via Xcode → Settings → Platforms."
    )
    System.exit(1)
    throw new AssertionError()
  }

  val needsBuild = forceRebuild || !os.exists(appPath / "Info.plist") ||
    os.mtime(sscFile) > os.mtime(appPath / "Info.plist")

  if needsBuild then
    buildSwiftUIPackage(sscFile, outDir, "ios")
    println(s"  Building for iOS Simulator ($simName)...")
    val r = os.proc(
      "xcodebuild", "build",
      "-scheme", appName,
      "-destination", s"platform=iOS Simulator,id=$simUdid",
      "-derivedDataPath", derivedDataPath.toString,
      "CODE_SIGN_IDENTITY=", "CODE_SIGNING_REQUIRED=NO", "CODE_SIGNING_ALLOWED=NO"
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)
    if !os.exists(appPath) then
      System.err.println(s"xcodebuild did not produce ${displayPath(appPath)}")
      System.exit(1)
  else
    println(s"  Skipping build (no .ssc changes since last build). Use --rebuild to force.")

  // Boot simulator (ignore "already booted" error)
  println(s"  Booting $simName...")
  os.proc("xcrun", "simctl", "boot", simUdid)
    .call(check = false, stdout = os.Pipe, stderr = os.Pipe)

  // Open Simulator.app so the user sees it
  os.proc("open", "-a", "Simulator")
    .call(check = false, stdout = os.Pipe, stderr = os.Pipe)

  // Install
  println(s"  Installing $appName...")
  val installResult = os.proc("xcrun", "simctl", "install", "booted", appPath.toString)
    .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if installResult.exitCode != 0 then System.exit(installResult.exitCode)

  // Launch — with or without log streaming
  println(s"  Launching $appName ($bundleId)...")
  val launchArgs =
    if console then List("xcrun", "simctl", "launch", "--console", "booted", bundleId)
    else             List("xcrun", "simctl", "launch",              "booted", bundleId)
  val launchResult = os.proc(launchArgs)
    .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if launchResult.exitCode != 0 then System.exit(launchResult.exitCode)

/** `ssc run --target ios --device` — build for arm64 device via xcodebuild with
 *  automatic signing, then deploy + launch via ios-deploy.
 *
 *  Requires: Apple ID signed into Xcode (for -allowProvisioningUpdates),
 *  ios-deploy on PATH (`brew install ios-deploy`), USB-connected iPhone. */
private def runSwiftUIIosDevice(
    sscFile: os.Path, outDir: os.Path,
    console: Boolean, forceRebuild: Boolean, deviceId: Option[String]
): Unit =
  // Pre-flight: ios-deploy must be present
  if os.proc("ios-deploy", "--version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: ios-deploy is required for --target ios --device.\n" +
      "Run: brew install ios-deploy"
    )
    System.exit(1)

  val module  = Parser.parse(os.read(sscFile))
  val appName = swiftAppName(module.manifest.flatMap(_.name))

  val derivedDataPath = outDir / "derived"
  val appPath = derivedDataPath / "Build" / "Products" / "Debug-iphoneos" / s"$appName.app"

  val needsBuild = forceRebuild || !os.exists(appPath / "Info.plist") ||
    os.mtime(sscFile) > os.mtime(appPath / "Info.plist")

  if needsBuild then
    buildSwiftUIPackage(sscFile, outDir, "ios")
    println(s"  Building for iOS device (arm64)...")
    val r = os.proc(
      "xcodebuild", "build",
      "-scheme", appName,
      "-destination", "generic/platform=iOS",
      "-allowProvisioningUpdates",
      "-derivedDataPath", derivedDataPath.toString
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)
    if !os.exists(appPath) then
      System.err.println(s"xcodebuild did not produce ${displayPath(appPath)}")
      System.exit(1)
  else
    println(s"  Skipping build (no .ssc changes). Use --rebuild to force.")

  // ios-deploy: --debug streams LLDB output (blocks); --justlaunch returns immediately
  println(s"  Deploying $appName to device${deviceId.map(id => s" ($id)").getOrElse("")}...")
  val idArgs     = deviceId.toList.flatMap(id => List("--id", id))
  val modeArgs   = if console then List("--debug") else List("--justlaunch")
  val deployResult = os.proc(
    List("ios-deploy", "--bundle", appPath.toString, "--no-wifi") ++ idArgs ++ modeArgs
  ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
  if deployResult.exitCode != 0 then System.exit(deployResult.exitCode)

/** `ssc package --target ios` — archive + export a signed `.ipa`.
 *
 *  Requires Xcode + an Apple Developer account (automatic signing via
 *  `-allowProvisioningUpdates`).  `teamId` resolves from:
 *    1. `--team-id` CLI flag
 *    2. `team-id:` frontmatter field
 *    3. `SSC_TEAM_ID` environment variable
 *
 *  `exportMethod` is one of `development`, `ad-hoc`, `enterprise`,
 *  `app-store` (default: `development`). */
private def packageIosIpa(
    sscFile:      os.Path,
    outDir:       os.Path,
    exportMethod: String,
    teamId:       Option[String]
): Unit =
  if os.proc("xcodebuild", "-version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println("Error: Xcode is required for ssc package --target ios.\nRun: ssc toolchain check --target ios")
    System.exit(1)

  val module   = Parser.parse(os.read(sscFile))
  val manifest = module.manifest
  val appName  = swiftAppName(manifest.flatMap(_.name))
  val resolvedTeamId = teamId
    .orElse(manifest.flatMap(_.raw.get("team-id").collect { case s: String => s }))
    .orElse(sys.env.get("SSC_TEAM_ID"))

  println(s"  Generating Swift package for iOS...")
  buildSwiftUIPackage(sscFile, outDir, "ios")

  val archivePath = outDir / s"$appName.xcarchive"
  val ipaDir      = outDir / "ipa"
  val exportPlist = outDir / "ExportOptions.plist"

  os.write.over(exportPlist, generateExportOptionsPlist(exportMethod, resolvedTeamId))
  println(s"  ExportOptions.plist → ${displayPath(exportPlist)}")

  println(s"  Archiving $appName (method=$exportMethod)...")
  val archiveArgs = List(
    "xcodebuild", "archive",
    "-scheme", appName,
    "-destination", "generic/platform=iOS",
    "-allowProvisioningUpdates",
    "-archivePath", archivePath.toString
  ) ++ resolvedTeamId.toList.flatMap(id => List("DEVELOPMENT_TEAM=" + id))
  val archResult = os.proc(archiveArgs)
    .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if archResult.exitCode != 0 then System.exit(archResult.exitCode)

  os.makeDir.all(ipaDir)
  println(s"  Exporting .ipa...")
  val exportResult = os.proc(
    "xcodebuild", "-exportArchive",
    "-archivePath",        archivePath.toString,
    "-exportPath",         ipaDir.toString,
    "-exportOptionsPlist", exportPlist.toString,
    "-allowProvisioningUpdates"
  ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if exportResult.exitCode != 0 then System.exit(exportResult.exitCode)

  val ipa = os.list(ipaDir).find(_.ext == "ipa")
  ipa match
    case Some(p) => println(s"  .ipa → ${displayPath(p)}")
    case None    => System.err.println(s"  Warning: no .ipa found in ${displayPath(ipaDir)}")

/** Generate the XML content of ExportOptions.plist for `xcodebuild -exportArchive`. */
private def generateExportOptionsPlist(
    method: String, teamId: Option[String]
): String =
  val teamLine = teamId.map(id =>
    s"  <key>teamID</key>\n  <string>$id</string>\n"
  ).getOrElse("")
  s"""|<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      |<plist version="1.0">
      |<dict>
      |  <key>method</key>
      |  <string>$method</string>
      |$teamLine  <key>uploadSymbols</key>
      |  <true/>
      |  <key>compileBitcode</key>
      |  <false/>
      |  <key>provisioningProfiles</key>
      |  <dict/>
      |</dict>
      |</plist>
      |""".stripMargin

private def buildSingleFileSite(sscFile: os.Path, outDir: os.Path): Unit =
  import scalascript.interpreter.Interpreter
  import scalascript.server.Routes
  Routes.clear()
  val nullOut = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
  val interp  = Interpreter(out = nullOut, baseDir = Some(sscFile / os.up), headless = true)
  try interp.run(scalascript.parser.Parser.parse(os.read(sscFile)))
  catch case e: Exception =>
    System.err.println(s"  [fail] ${e.getMessage}"); System.exit(1)
  val literalGets = Routes.all.filter { e =>
    e.method == "GET" && e.pathPattern.forall(_.isInstanceOf[Routes.Segment.Literal])
  }
  if literalGets.isEmpty then
    println("  (no GET routes registered — nothing to render)")
  for entry <- literalGets do
    val req    = syntheticRequest("GET", entry.path, Map.empty)
    val result = entry.interpreter.invoke(entry.handler, List(req))
    val body   = extractResponseBody(result)
    val out    = outPathFor(outDir, entry.path)
    os.makeDir.all(out / os.up)
    os.write.over(out, body)
    println(s"  ${entry.path} → ${displayPath(out)}")

/** `ssc build [<name|name.ssc>] [--target <t>] [--out <dir>]`
 *
 *  Project-file mode: resolves the project `.ssc` file and builds it for the
 *  requested target.  The first positional arg is optional:
 *
 *    (absent)    →  auto-discover: `<dirname>.ssc` or single `.ssc` in cwd
 *    `myapp`     →  resolves to `myapp.ssc` in cwd
 *    `myapp.ssc` →  that exact file
 *
 *  Targets (`--target`; default from frontmatter `targets:`, else `ssc`):
 *
 *    ssc (default)  →  fat JAR at `<out>/name.jar` — interpreter-based,
 *                      no compiler required, `java -jar` runnable
 *    jvm            →  fully compiled via JvmGen → scala-cli → bytecode JAR
 *                      (requires scala-cli on PATH)
 *    js             →  JS bundle at `<out>/name.js`
 *    web            →  static HTML/assets rendered to `<out>/`
 *
 *  Directory mode (legacy): when the first positional is a *directory*, walk
 *  it for `.ssc` pages and render static HTML (old `ssc build <src-dir>`). */
def buildCommand(args: List[String]): Unit =
  // v2.0: --incremental flag routes to separate-compilation build orchestrator.
  if args.contains("--incremental") then
    val rest = args.filterNot(_ == "--incremental")
    incrementalBuildCommand(rest)
    return

  // Parse --target and --out flags, collect remaining positionals.
  var targetFlag: Option[String] = None
  var outFlag:    Option[String] = None
  val positional = scala.collection.mutable.ListBuffer.empty[String]
  val remaining  = args.iterator
  while remaining.hasNext do
    remaining.next() match
      case "--target" if remaining.hasNext => targetFlag = Some(remaining.next())
      case "--out"    if remaining.hasNext => outFlag    = Some(remaining.next())
      case other                           => positional += other

  // Resolve project file from first positional or auto-discover.
  val projectFile: Option[os.Path] = positional.headOption match
    case Some(arg) =>
      val p = os.Path(arg, os.pwd)
      if os.exists(p) && os.isFile(p) && p.ext == "ssc" then
        Some(p)   // explicit .ssc file
      else if !os.exists(p) || os.isFile(p) then
        // Bare name (no extension or unknown extension): look for <arg>.ssc
        val candidate = os.Path(arg.stripSuffix(".ssc") + ".ssc", os.pwd)
        if os.exists(candidate) && os.isFile(candidate) then Some(candidate) else None
      else None   // it's a directory → fall through to legacy dir mode
    case None =>
      findProjectSsc()

  projectFile match
    case Some(pf) =>
      val outDir    = os.Path(outFlag.getOrElse("target/build"), os.pwd)
      val effective = targetFlag.orElse(ActiveFlags.current.target)
      buildProjectFileCommand(pf, effective, outDir)
      return
    case None => // fall through to legacy dir mode

  // ─── Legacy directory mode ────────────────────────────────────────
  import scalascript.interpreter.Interpreter
  import scalascript.server.Routes
  if positional.isEmpty then
    System.err.println("Usage: ssc build [<name|name.ssc>] [--target ssc|jvm|js|web] [--out <dir>]  |  ssc build <src-dir>")
    System.exit(1)
  val srcArg = positional.head
  val outArg = positional.drop(1).headOption.orElse(outFlag).getOrElse("dist")
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
  var withArtifacts: Boolean = false
  // backendId -> jar path pairs from --with-backend-jar backendId:path
  val backendJars = scala.collection.mutable.ArrayBuffer.empty[(String, os.Path)]
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => output = Some(it.next())
      case "--with-artifacts"              => withArtifacts = true
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
    System.err.println("Usage: ssc bundle <file.ssc> [<file.ssc>...] [-o name.sscpkg] [--with-artifacts] [--with-backend-jar backendId:path]")
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

  // ─── v2.0 Phase 5 — pre-compile artifacts when `--with-artifacts` ─────
  //
  // For every bundled `.ssc`, drive `build --incremental --backend jvm`
  // and `--backend js` into a temporary artifact dir, then splice the
  // produced `.scim` / `.scjvm` / `.scjs` files into the ZIP under
  // `.ssc-artifacts/<basename>.<ext>` so a consumer can use them
  // directly without re-parsing the source.
  //
  // `findArtifactAlongside` (in `ImportResolver`) discovers the layout
  // automatically — no manifest schema change required.
  //
  // Refuses on compile errors: build/compile failure aborts the bundle
  // and removes the partially-written ZIP.
  val artifactStaging: Option[os.Path] =
    if !withArtifacts then None
    else
      // Stage rewritten sources to a temp src dir so the build step sees
      // the same archive layout the consumer will see.  Build JVM + JS
      // artifacts in-process via `buildArtifactsInto` (the exit-safe
      // sibling of `build --incremental`) and refuse the bundle if any
      // input fails to compile.
      val srcStage = os.temp.dir(prefix = "ssc-bundle-src-")
      val artStage = os.temp.dir(prefix = "ssc-bundle-art-")
      try
        archivePath.toList.foreach { case (file, archive) =>
          val dest = srcStage / os.RelPath(archive)
          os.makeDir.all(dest / os.up)
          os.write.over(dest, rewriteImports(file))
        }
        val (_, _, jvmFailed) = buildArtifactsInto(srcStage, artStage, Some("jvm"))
        val (_, _, jsFailed)  = buildArtifactsInto(srcStage, artStage, Some("js"))
        if jvmFailed > 0 || jsFailed > 0 then
          os.remove.all(srcStage)
          os.remove.all(artStage)
          System.err.println(
            s"bundle --with-artifacts: compile errors in inputs " +
            s"(jvm=$jvmFailed js=$jsFailed); refusing to bundle"
          )
          System.exit(1)
        Some(artStage)
      catch case e: Throwable =>
        scala.util.Try(os.remove.all(srcStage))
        scala.util.Try(os.remove.all(artStage))
        System.err.println(s"bundle --with-artifacts: ${e.getMessage}")
        System.exit(1)
        None
      finally
        scala.util.Try(os.remove.all(srcStage))

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

    // .ssc-artifacts/* — pre-compiled .scim / .scjvm / .scjs (v2.0 P5).
    // Layout in the ZIP: one .ssc-artifacts dir at the archive root, with
    // entry names matching the source basenames (no `sources/` prefix).
    // A consumer extracts the archive and finds the artifacts next to
    // the sources under `.ssc-artifacts/`.
    artifactStaging.foreach { artDir =>
      if os.isDir(artDir) then
        os.list(artDir).filter(os.isFile).sortBy(_.last).foreach { p =>
          val ext = p.ext
          if Set("scim", "scjvm", "scjs", "scjvm-runtime", "scjs-runtime").contains(ext) then
            zip.putNextEntry(new ZipEntry(".ssc-artifacts/" + p.last))
            zip.write(os.read.bytes(p))
            zip.closeEntry()
        }
    }

    // intrinsics/*.jar — one per --with-backend-jar entry
    backendJars.foreach { case (_, jar) =>
      zip.putNextEntry(new ZipEntry("intrinsics/" + jar.last))
      zip.write(os.read.bytes(jar))
      zip.closeEntry()
    }
  finally
    zip.close()
    artifactStaging.foreach(d => scala.util.Try(os.remove.all(d)))

  val entryList = entryPaths.map(archivePath).mkString(", ")
  val external  = archivePath.values.count(_.startsWith("_external/"))
  val jarLine   = if backendJars.isEmpty then "" else s", ${backendJars.size} intrinsic JAR(s)"
  val artLine   = if withArtifacts then ", with pre-compiled artifacts" else ""
  println(s"$outName  (${archivePath.size} sources, $external external$jarLine$artLine) — entries: $entryList")

// ─────────────────────────────────────────────────────────────────────────────
// ssc deploy [subverb] [target|group] [--env=<env>] [flags]  —  v1.52
// ─────────────────────────────────────────────────────────────────────────────

def deployCommand(args: List[String]): Unit =
  import scalascript.compiler.plugin.deploy.*
  import scalascript.compiler.plugin.deploy.DeployManifest.{parse => parseDeployManifest, *}
  import scalascript.parser.Parser

  var envFlag:       Option[String] = None
  var dryRun:        Boolean        = false
  var verbose:       Boolean        = false
  var manifestPath:  Option[String] = None
  var slotOverride:  Option[String] = None
  val positional     = scala.collection.mutable.ListBuffer.empty[String]

  val it = args.iterator
  while it.hasNext do
    it.next() match
      case s if s.startsWith("--env=")      => envFlag      = Some(s.stripPrefix("--env="))
      case "--env" if it.hasNext            => envFlag      = Some(it.next())
      case "--dry-run"                      => dryRun       = true
      case "--verbose" | "-v"               => verbose      = true
      case s if s.startsWith("--manifest=") => manifestPath = Some(s.stripPrefix("--manifest="))
      case "--manifest" if it.hasNext       => manifestPath = Some(it.next())
      case s if s.startsWith("--slot=")     => slotOverride = Some(s.stripPrefix("--slot="))
      case "--slot" if it.hasNext           => slotOverride = Some(it.next())
      case other => positional += other

  val subverb = positional.headOption.getOrElse("deploy")
  val subject = if positional.size > 1 then positional(1) else ""

  // ─── Load project manifest ────────────────────────────────────────────────
  val sscFile: os.Path = manifestPath.map(p => os.Path(p, os.pwd))
    .orElse(findProjectSsc())
    .getOrElse {
      System.err.println("ssc deploy: no .ssc project file found"); System.exit(1); ???
    }
  val source  = os.read(sscFile)
  val module  = Parser.parse(source)
  val mf      = module.manifest.getOrElse {
    System.err.println("ssc deploy: .ssc file has no front-matter manifest"); System.exit(1); ???
  }

  // Merge the raw YAML from all four deploy keys into one map for parsing
  val deployRaw: Map[String, Any] = Map(
    "deploy"       -> mf.raw.getOrElse("deploy", null),
    "groups"       -> mf.raw.getOrElse("groups", null),
    "environments" -> mf.raw.getOrElse("environments", null),
    "state"        -> mf.raw.getOrElse("state", null),
  ).collect { case (k, v) if v != null => k -> v }

  val dm = parseDeployManifest(deployRaw)

  if dm.targets.isEmpty && dm.environments.isEmpty then
    System.err.println("ssc deploy: manifest has no deploy: or environments: blocks")
    System.exit(1)

  val resolvedEnv = envFlag.getOrElse(defaultEnv(dm))

  subverb match

    case "envs" =>
      if dm.environments.isEmpty then println("No environments declared.")
      else
        println(f"${"ENVIRONMENT"}%-20s ${"PURPOSE"}%-12s ${"SLOT"}%-8s ${"GROUPS"}")
        for (name, env) <- dm.environments do
          val slot = env.blueGreen.map(bg => bg.activeSlot).getOrElse("-")
          val grps = env.activeGroups.mkString(", ")
          println(f"${name}%-20s ${env.purpose.toString.toLowerCase}%-12s ${slot}%-8s ${grps}")

    case "plan" =>
      val groupName = if subject.nonEmpty then subject
                      else dm.groups.headOption.map(_._1).getOrElse {
                        System.err.println("ssc deploy plan: specify a group name"); System.exit(1); ???
                      }
      val group = dm.groups.getOrElse(groupName,
        throw DeployError(s"[deploy/unknown-group] Group '$groupName' not found. Available: ${dm.groups.keys.mkString(", ")}"))
      val sorted = DeployDag.topoSort(group.members, group.deps)
      val stages = group.mode match
        case ExecMode.Parallel            => DeployDag.toStages(sorted, group.deps)
        case ExecMode.Sequence            => sorted.map(List(_))
        case ExecMode.Pipeline(explicit)  => explicit
      println(s"Deploy plan: group=$groupName env=$resolvedEnv mode=${group.mode} on_failure=${group.onFailure}")
      println(s"Resolved execution stages:")
      stages.zipWithIndex.foreach { case (stage, i) =>
        println(s"  Stage ${i + 1}: ${stage.mkString(", ")}")
        stage.foreach { t =>
          val cfg    = dm.targets.getOrElse(t, Map.empty)
          val kind   = cfg.getOrElse("kind", "?").toString
          val deps   = group.deps.getOrElse(t, Nil)
          val depStr = if deps.nonEmpty then s" (depends on: ${deps.mkString(", ")})" else ""
          println(s"           [$t] kind=$kind$depStr")
        }
      }
      if dryRun then println("[dry-run] No actions taken.")

    case "status" =>
      val targets = if subject.nonEmpty then List(subject)
                    else dm.targets.keys.toList
      println(f"${"TARGET"}%-25s ${"KIND"}%-15s ${"HEALTHY"}%-8s ${"REVISION"}")
      for t <- targets do
        val cfg     = dm.targets.getOrElse(t, Map.empty)
        val kind    = cfg.getOrElse("kind", "?").toString
        println(f"${t}%-25s ${kind}%-15s ${"?"}%-8s -")

    case "deploy" | "" =>
      val groupOrTarget = subject
      val env = resolveEnv(dm, resolvedEnv)
      val activeGroups =
        if groupOrTarget.nonEmpty then List(groupOrTarget)
        else env.activeGroups.filter(dm.groups.contains)
      if activeGroups.isEmpty && dm.targets.nonEmpty then
        // Deploy all targets in declaration order as a single sequence
        runDeployTargets(dm.targets.keys.toList, dm, env, resolvedEnv, slotOverride, dryRun, verbose, sscFile.toNIO.getParent.toString)
      else
        for gn <- activeGroups do
          val group = dm.groups.getOrElse(gn,
            throw DeployError(s"[deploy/unknown-group] Group '$gn' not found."))
          runDeployGroup(group, dm, env, resolvedEnv, slotOverride, dryRun, verbose, sscFile.toNIO.getParent.toString)

    case other =>
      System.err.println(s"ssc deploy: unknown subverb '$other'. Use: deploy, plan, envs, status, build, push, rollback, logs, diff, destroy, switch, promote")
      System.exit(1)

private def runDeployGroup(
  group:      scalascript.compiler.plugin.deploy.DeployGroup,
  dm:         scalascript.compiler.plugin.deploy.DeployManifest,
  env:        scalascript.compiler.plugin.deploy.DeployEnvironment,
  envName:    String,
  slot:       Option[String],
  dryRun:     Boolean,
  verbose:    Boolean,
  workDirStr: String,
): Unit =
  import scalascript.compiler.plugin.deploy.*
  val collected = scala.collection.concurrent.TrieMap.empty[String, Map[String, String]]
  def emit(e: DeployEvent): Unit = e match
    case DeployEvent.Started(t, en, s)          => println(s"▶ [$t] env=$en${s.map(x => s" slot=$x").getOrElse("")}")
    case DeployEvent.Building(t, p)             => if verbose then println(s"  building $t: $p")
    case DeployEvent.Pushed(t, ref)             => if verbose then println(s"  pushed $t: $ref")
    case DeployEvent.Deployed(t, outs)          => println(s"✓ [$t] ${outs.get("url").map(u => s"→ $u").getOrElse("")}")
    case DeployEvent.Failed(t, err)             => System.err.println(s"✗ [$t] $err")
    case DeployEvent.RolledBack(t)              => println(s"↩ [$t] rolled back")
    case DeployEvent.SkippedDependency(t, by)   => println(s"— [$t] skipped (depends on failed: $by)")
    case DeployEvent.GroupComplete(g, ok, _)    => println(s"${if ok then "✓" else "✗"} group $g complete")

  def runTarget(targetName: String, @annotation.unused prereqOutputs: Map[String, String]): Option[Map[String, String]] =
    val baseCfg = dm.targets.getOrElse(targetName, Map.empty)
    val overCfg = env.targetOverrides.getOrElse(targetName, Map.empty)
    val cfg = DeployManifest.effectiveTargetConfig(baseCfg, overCfg)
    val kind = cfg.getOrElse("kind", "traditional").toString
    val transport = cfg.getOrElse("transport", "").toString
    val workDir = os.Path(workDirStr)
    val ctx = DeployContext(targetName, cfg, envName, slot, dryRun, verbose,
      n => collected.getOrElse(n, Map.empty), workDir)
    emit(DeployEvent.Started(targetName, envName, slot))
    try
      val adapter: scalascript.compiler.plugin.deploy.DeployTarget =
        if kind == "traditional" && transport == "subprocess" then
          val port = cfg.getOrElse("port", 8080) match
            case n: Integer => n.toInt
            case s: String  => s.toIntOption.getOrElse(8080)
            case _          => 8080
          LocalSubprocessTarget(port)
        else
          StubDeployTarget(kind)
      val art  = adapter.build(ctx)
      emit(DeployEvent.Building(targetName, art.artifactPath))
      val ref  = adapter.push(ctx, art)
      emit(DeployEvent.Pushed(targetName, ref.ref))
      adapter.deploy(ctx, ref)
      val outs = adapter.outputs(ctx)
      collected(targetName) = outs
      emit(DeployEvent.Deployed(targetName, outs))
      Some(outs)
    catch case e: Throwable =>
      emit(DeployEvent.Failed(targetName, e.getMessage))
      None

  val (_, failed, _) = DeployOrchestrator.run(group, runTarget, emit)
  if failed.nonEmpty then
    System.err.println(s"${failed.size} target(s) failed: ${failed.mkString(", ")}")
    System.exit(1)

private def runDeployTargets(
  targets:    List[String],
  dm:         scalascript.compiler.plugin.deploy.DeployManifest,
  env:        scalascript.compiler.plugin.deploy.DeployEnvironment,
  envName:    String,
  slot:       Option[String],
  dryRun:     Boolean,
  verbose:    Boolean,
  workDirStr: String,
): Unit =
  import scalascript.compiler.plugin.deploy.*
  val group = DeployGroup("all", targets, ExecMode.Sequence, Map.empty, FailurePolicy.AbortRemaining, None)
  runDeployGroup(group, dm, env, envName, slot, dryRun, verbose, workDirStr)

/** Placeholder for unimplemented target kinds (v1.52.2+). */
private class StubDeployTarget(targetKind: String) extends scalascript.compiler.plugin.deploy.DeployTarget:
  def kind:         String = targetKind
  def artifactKind: scalascript.compiler.plugin.deploy.ArtifactKind = scalascript.compiler.plugin.deploy.ArtifactKind.FatJar
  def build(ctx: scalascript.compiler.plugin.deploy.DeployContext):   scalascript.compiler.plugin.deploy.BuildResult  =
    if ctx.dryRun then scalascript.compiler.plugin.deploy.BuildResult("(dry-run)", artifactKind)
    else throw scalascript.compiler.plugin.deploy.DeployError(s"[deploy/not-implemented] Target kind '$targetKind' is not yet implemented. Available in v1.52: traditional/subprocess.")
  def push(ctx: scalascript.compiler.plugin.deploy.DeployContext, art: scalascript.compiler.plugin.deploy.BuildResult):   scalascript.compiler.plugin.deploy.PushResult   = scalascript.compiler.plugin.deploy.PushResult("(dry-run)")
  def deploy(ctx: scalascript.compiler.plugin.deploy.DeployContext, ref: scalascript.compiler.plugin.deploy.PushResult):  scalascript.compiler.plugin.deploy.DeployResult = scalascript.compiler.plugin.deploy.DeployResult("(dry-run)")
  def rollback(ctx: scalascript.compiler.plugin.deploy.DeployContext, to: scalascript.compiler.plugin.deploy.RevisionRef): scalascript.compiler.plugin.deploy.RollbackResult = scalascript.compiler.plugin.deploy.RollbackResult("(dry-run)")
  def status(ctx: scalascript.compiler.plugin.deploy.DeployContext):  scalascript.compiler.plugin.deploy.StatusReport = scalascript.compiler.plugin.deploy.StatusReport(ctx.targetName, healthy = false, message = "not implemented")
  def logs(ctx: scalascript.compiler.plugin.deploy.DeployContext, opts: scalascript.compiler.plugin.deploy.LogOpts): Iterator[scalascript.compiler.plugin.deploy.LogLine] = Iterator.empty
  def outputs(ctx: scalascript.compiler.plugin.deploy.DeployContext): Map[String, String] = Map.empty

// ssc plugin <subcommand>  —  v1.7 Tier 5
// ─────────────────────────────────────────────────────────────────────────────

/** Default directory where installed `.sscpkg` files live. */
private def pluginsDir: os.Path = os.home / ".scalascript" / "compiler" / "plugins"

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

def newCommand(args: List[String]): Unit =
  args match
    case name :: rest =>
      try
        val opts = NewProject.parseOptions(rest)
        val dir = NewProject.create(name, opts.template, opts.outputDir)
        println(s"Created ${opts.template} project: $dir")
      catch
        case e: Exception =>
          System.err.println(s"ssc new: ${e.getMessage}")
          System.exit(1)
    case Nil =>
      System.err.println("Usage: ssc new <name> [--template app|lib|plugin] [--output-dir <dir>]")
      System.exit(1)

/** Find the "project" `.ssc` file for the current directory.
 *  Prefers a file whose stem matches the directory name (`myapp/myapp.ssc`);
 *  falls back to the single `.ssc` file in the directory if there is exactly one. */
private def findProjectSsc(): Option[os.Path] =
  val dir     = os.pwd
  val byName  = dir / s"${dir.last}.ssc"
  if os.exists(byName) then Some(byName)
  else
    val found = os.list(dir).filter(p => p.ext == "ssc" && os.isFile(p))
    if found.length == 1 then Some(found.head) else None

/** `ssc <script>` — run a named script from the project .ssc frontmatter.
 *  Falls back to `runCommand` if no project file is found or the script is
 *  not declared, so `ssc somefile.ssc` continues to work as before. */
private def scriptCommand(cmd: String, extraArgs: List[String]): Unit =
  findProjectSsc() match
    case None => runCommand(cmd :: extraArgs)
    case Some(sscFile) =>
      val scripts =
        scala.util.Try(scalascript.parser.Parser.parse(os.read(sscFile)).manifest)
          .toOption.flatten.map(_.scripts).getOrElse(Map.empty)
      scripts.get(cmd) match
        case None => runCommand(cmd :: extraArgs)
        case Some(scriptStr) =>
          val parts = scriptStr.trim.split("\\s+").toList.filterNot(_.isEmpty)
          parts match
            case Nil => ()
            case subCmd :: rest =>
              val cmdArgs = rest ::: sscFile.toString :: extraArgs
              subCmd match
                case "run"     => runCommand(cmdArgs)
                case "watch"   => watchCommand(cmdArgs)
                case "build"   => buildCommand(cmdArgs)
                case "serve"   => serveCommand(cmdArgs)
                case "test"    => testCommand(cmdArgs)
                case "check"   => checkCommand(cmdArgs)
                case "fmt"     => fmtCommand(cmdArgs)
                case "bundle"  => bundleCommand(cmdArgs)
                case "preview" => previewCommand(cmdArgs)
                case "emit-js" => emitJsCommand(cmdArgs)
                case other     =>
                  System.err.println(
                    s"ssc: script '$cmd' maps to unknown subcommand '$other'\n" +
                    s"  Defined in: $sscFile")
                  System.exit(1)

/** `ssc install [--prefix <dir>]` — install ssc to a system prefix (default: `~/.local`).
 *  Copies `bin/lib/` (JARs) and `std/` from the current installation root to
 *  `<prefix>/lib/ssc/`, then writes a self-contained launcher at `<prefix>/bin/ssc`.
 *  The launcher hard-codes the prefix so the binary works from any directory. */
def selfInstallCommand(args: List[String]): Unit =
  val prefix: os.Path = args match
    case "--prefix" :: p :: _ => os.Path(p, os.pwd)
    case Nil                  => os.home / ".local"
    case _ =>
      System.err.println("Usage: ssc install [--prefix <dir>]")
      System.exit(1); os.pwd

  val libRoot: os.Path = scalascript.imports.ImportResolver.libPath.getOrElse {
    System.err.println(
      "ssc install: cannot determine current install root.\n" +
      "  ssc must be launched via the bin/ssc launcher (ssc.lib.path must be set).")
    System.exit(1); os.pwd
  }

  val destRoot = prefix / "lib" / "ssc"
  val destBin  = prefix / "bin"

  println(s"Installing ssc → $prefix")

  // Copy bin/lib/ (runtime JARs + thin ssc.jar)
  val srcLib = libRoot / "bin" / "lib"
  if !os.exists(srcLib) then
    System.err.println(s"ssc install: bin/lib not found at $srcLib"); System.exit(1)
  os.makeDir.all(destRoot / "bin")
  os.walk(srcLib).foreach { src =>
    val dest = destRoot / "bin" / "lib" / src.relativeTo(srcLib)
    if os.isDir(src) then os.makeDir.all(dest)
    else { os.makeDir.all(dest / os.up); os.copy.over(src, dest) }
  }
  val jarCount = os.walk(destRoot / "bin" / "lib").count(_.ext == "jar")
  println(s"  ✓  Library   → $destRoot/bin/lib/  ($jarCount jars)")

  // Copy std/ (standard library .ssc files)
  val srcStd = libRoot / "std"
  if os.exists(srcStd) then
    os.walk(srcStd).foreach { src =>
      val dest = destRoot / "std" / src.relativeTo(srcStd)
      if os.isDir(src) then os.makeDir.all(dest)
      else { os.makeDir.all(dest / os.up); os.copy.over(src, dest) }
    }
    println(s"  ✓  Stdlib    → $destRoot/std/")

  // Write a self-contained launcher with hard-coded prefix
  os.makeDir.all(destBin)
  val launcher = destBin / "ssc"
  os.write.over(launcher,
    s"""#!/usr/bin/env bash
       |exec java -Dssc.lib.path="$destRoot" \\
       |  -cp "$destRoot/bin/lib/jars/*:$destRoot/bin/lib/ssc.jar" \\
       |  scalascript.cli.ssc "$$@"
       |""".stripMargin)
  java.nio.file.Files.setPosixFilePermissions(
    launcher.toNIO,
    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
  println(s"  ✓  Launcher  → $launcher")
  println()

  val pathDirs = sys.env.getOrElse("PATH", "").split(':').toSet
  if pathDirs.contains(destBin.toString) then
    println(s"$destBin is already in PATH — done.")
  else
    println(s"Add to PATH (not yet present):")
    println(s"""  echo 'export PATH="$$PATH:$destBin"' >> ~/.zshrc""")

/** `ssc plugin install <path-or-url-or-name>` — copy/download a
 *  `.sscpkg` to `~/.scalascript/compiler/plugins/` and print a confirmation.
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
      scalascript.compiler.plugin.LocalRegistry.resolve(rawSrc) match
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
    try scalascript.compiler.plugin.SscpkgLoader.load(tmp).manifest
    finally os.remove(tmp)

  os.makeDir.all(pluginsDir)
  val dest = pluginsDir / s"${manifest.id}-${manifest.version}.sscpkg"
  os.write.over(dest, bytes)
  println(s"Installed ${manifest.id} ${manifest.version} → $dest")

/** `ssc plugin list` — print every `.sscpkg` in `~/.scalascript/compiler/plugins/`. */
def pluginList(): Unit =
  if !os.isDir(pluginsDir) then
    println("(no plugins installed)"); return
  val pkgs = os.list(pluginsDir).filter(_.ext == "sscpkg").sorted
  if pkgs.isEmpty then println("(no plugins installed)")
  else
    pkgs.foreach { p =>
      val m = scala.util.Try(scalascript.compiler.plugin.SscpkgLoader.load(p).manifest)
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
    val m = scalascript.compiler.plugin.SscpkgLoader.load(p).manifest
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

  val manifest = scalascript.compiler.plugin.SscpkgManifest.parseString(os.read(manifestPath)).get
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
  import scalascript.compiler.plugin.LocalRegistry
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

/** Load a [[scalascript.ast.Module]] from an `.ssc` source or a pre-compiled
 *  `.sscc` binary.  `.sscc` files skip markdown/YAML/scalameta parsing;
 *  all other extensions fall through to [[Parser.parse]]. */
private def loadModule(path: os.Path): scalascript.ast.Module =
  if path.last.endsWith(".sscc") then
    scalascript.ast.SsccFormat.read(os.read.bytes(path)) match
      case Right(m)  => m
      case Left(err) =>
        System.err.println(s"ssc: cannot load ${path.last}: $err")
        System.exit(1)
        throw new AssertionError()
  else
    Parser.parse(os.read(path))

def runCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  // `--spark-version <v>` and `--spark-master <url>` plumb into
  // BackendOptions.extra("sparkVersion") / ("sparkMaster") respectively,
  // consumed by SparkBackend.compile.  Stripped here before file dispatch
  // so they don't get treated as paths.
  var sparkVersionFlag:  Option[String] = None
  var sparkMasterFlag:   Option[String] = None
  var frontendFlag:      Option[String] = None
  var targetFlag:        Option[String] = None
  var modeFlag:          Option[String] = None
  var serverUrlFlag:     Option[String] = None
  var transportFlag:     Option[BackendTransportKind] = None
  var hostFlag:          Option[String] = None
  var portFlag:          Option[Int] = None
  var openBrowserFlag:   Option[Boolean] = None
  var serverBackendFlag: String         = "jdk"
  var consoleFlag:       Boolean        = true   // --console / --no-console
  var rebuildFlag:       Boolean        = false  // --rebuild / --no-rebuild
  var deviceFlag:        Boolean        = false  // --device
  var deviceIdFlag:      Option[String] = None   // --device-id <udid>
  val fileArgs = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--spark-version"    if it.hasNext => sparkVersionFlag  = Some(it.next())
      case "--spark-master"     if it.hasNext => sparkMasterFlag   = Some(it.next())
      case "--server-backend"   if it.hasNext => serverBackendFlag = it.next()
      case "--target"           if it.hasNext => targetFlag        = Some(it.next())
      case "--mode"             if it.hasNext => modeFlag          = Some(it.next())
      case "--server-url"       if it.hasNext => serverUrlFlag     = Some(it.next())
      case "--transport"        if it.hasNext => transportFlag     = Some(parseTransportFlag("run --transport", it.next()))
      case "--host"             if it.hasNext => hostFlag          = Some(it.next())
      case "--port"             if it.hasNext => portFlag          = Some(parsePortFlag("run --port", it.next()))
      case "--open-browser"                  => openBrowserFlag    = Some(true)
      case "--no-open-browser"               => openBrowserFlag    = Some(false)
      case flag if flag.startsWith("--open-browser=") =>
        openBrowserFlag = parseBooleanFlag("run --open-browser", flag.drop("--open-browser=".length))
      case "--console"                   => consoleFlag  = true
      case "--no-console"                => consoleFlag  = false
      case "--rebuild"                   => rebuildFlag  = true
      case "--no-rebuild"                => rebuildFlag  = false
      case "--device"                    => deviceFlag   = true
      case "--device-id" if it.hasNext  => deviceIdFlag = Some(it.next()); deviceFlag = true
      case "--frontend"         if it.hasNext =>
        val name = it.next()
        if !validFrontendNames(name) then
          System.err.println(s"run: unknown --frontend '$name', valid: ${validFrontendNames.mkString(", ")}")
          System.exit(1)
        frontendFlag = Some(name)
      case f => fileArgs += f

  val targetSelection = targetFlag.orElse(ActiveFlags.current.target)
  val runMode = modeFlag.map(_.trim.toLowerCase)
  runMode match
    case Some("server") =>
      ActiveFlags.current.backend match
        case Some(backend) if backend != "jvm" && backend != "jvm-rest" =>
          System.err.println(s"run --mode server requires --backend jvm or --backend jvm-rest, got '$backend'")
          System.exit(1)
        case _ =>
          for file <- fileArgs.toList do
            val path = os.Path(file, os.pwd)
            validateRunTransport(path, runMode, serverUrlFlag, transportFlag)
            val bind = bindOptions(path, hostFlag, portFlag, None)
            runJvmServerHook(path, serverBackendFlag, bind)
          return
    case Some("client") =>
      for file <- fileArgs.toList do
        val path = os.Path(file, os.pwd)
        validateRunTransport(path, runMode, serverUrlFlag, transportFlag)
      val serverUrl = serverUrlFlag.getOrElse {
        System.err.println("run --mode client requires --server-url <url>")
        System.exit(1)
        throw new AssertionError()
      }
      for file <- fileArgs.toList do
        val path = os.Path(file, os.pwd)
        val manifestFrontend =
          scala.util.Try(loadModule(path).manifest.flatMap(_.frontendFramework)).getOrElse(None)
        val selectedFrontend = frontendFlag.orElse(manifestFrontend)
        val bind = bindOptions(path, hostFlag, portFlag, openBrowserFlag)
        if targetRequestsElectron(targetSelection) || selectedFrontend.contains("electron") then
          runElectronClientDevHook(path, serverUrl)
        else if selectedFrontend.exists(browserFrontendNames) then
          runWebClientPreviewHook(path, selectedFrontend.get, serverUrl, bind)
        else
          System.err.println("run --mode client requires --frontend electron, react, solid, vue, or custom")
          System.exit(1)
      return
    case Some("fullstack") => ()
    case Some(other) =>
      System.err.println(s"run: unknown --mode '$other', valid: server, client, fullstack")
      System.exit(1)
    case None => ()

  for file <- fileArgs.toList do
    validateRunTransport(os.Path(file, os.pwd), runMode, serverUrlFlag, transportFlag)

  if runRequestsSwingFrontend(frontendFlag, fileArgs.toList) then
    rejectInterpreterSwingRun()

  // --target jvm: compile via JvmGen → scala-cli → execute
  if targetSelection.contains("jvm") then
    for file <- fileArgs.toList do
      runJvmViaScalaCli(os.Path(file, os.pwd), serverBackendFlag, "run --target jvm")
    return

  // --target macos / desktop-macos: build Swift package + swift build + launch binary
  if targetSelection.exists(t => t == "macos" || t == "desktop-macos") then
    val outDir = os.Path("target/build", os.pwd) / "macos"
    for file <- fileArgs.toList do
      val sscFile = os.Path(file, os.pwd)
      val appName = swiftAppName(
        scala.util.Try(Parser.parse(os.read(sscFile)).manifest.flatMap(_.name)).toOption.flatten
      )
      val binary = outDir / ".build" / "debug" / appName
      val needsBuild = rebuildFlag || !os.exists(binary) ||
        os.mtime(sscFile) > os.mtime(binary)
      if needsBuild then
        buildSwiftUIPackage(sscFile, outDir, "macos", runSwiftBuild = true)
      else
        println(s"  Skipping build (no .ssc changes). Use --rebuild to force.")
      if !os.exists(binary) then
        System.err.println(s"swift build did not produce ${displayPath(binary)}")
        System.exit(1)
      println(s"  Launching $appName...")
      if consoleFlag then
        os.proc(binary).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
      else
        os.proc(binary).spawn(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir)
    return

  // --target ios / mobile-ios: Simulator (default) or real device (--device)
  if targetSelection.exists(t => t == "ios" || t == "mobile-ios") then
    if deviceFlag then
      val outDir = os.Path("target/build", os.pwd) / "ios-device"
      for file <- fileArgs.toList do
        runSwiftUIIosDevice(os.Path(file, os.pwd), outDir, consoleFlag, rebuildFlag, deviceIdFlag)
    else
      val outDir = os.Path("target/build", os.pwd) / "ios"
      for file <- fileArgs.toList do
        runSwiftUIIosSimulator(os.Path(file, os.pwd), outDir, consoleFlag, rebuildFlag)
    return

  val noExplicitRunMode =
    targetSelection.isEmpty && frontendFlag.isEmpty && ActiveFlags.current.backend.isEmpty
  if noExplicitRunMode && fileArgs.nonEmpty then
    val files = fileArgs.toList
    val shouldRunDefault = files.forall { file =>
      val path = os.Path(file, os.pwd)
      os.exists(path) &&
        scala.util.Try(shouldDefaultToElectronJvmRest(loadModule(path), os.read(path))).getOrElse(false)
    }
    if shouldRunDefault then
      for file <- files do
        runElectronJvmRestDevHook(os.Path(file, os.pwd), serverBackendFlag)
      return

  // --target desktop / desktop-electron / desktop-jvm, or --frontend electron → Electron dev-run
  val isElectronRun =
    targetRequestsElectron(targetSelection) ||
    frontendFlag.contains("electron")
  val isJvmRestRun = targetRequestsElectronJvmRest(targetSelection, ActiveFlags.current.backend)
  if isJvmRestRun && !isElectronRun then
    System.err.println("run --backend jvm-rest currently requires --frontend electron, --target desktop, or --target desktop-jvm")
    System.exit(1)
  if isElectronRun && isJvmRestRun then
    for file <- fileArgs.toList do
      runElectronJvmRestDevHook(os.Path(file, os.pwd), serverBackendFlag)
    return
  if isElectronRun then
    for file <- fileArgs.toList do
      runElectronDev(os.Path(file, os.pwd))
    return

  frontendFlag.foreach(applyFrontendBackend)
  val backendId = ActiveFlags.current.backend.getOrElse("int")
  for file <- fileArgs.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        // Load config: frontmatter < sidecar files < fenced blocks < -Dscalascript.* < CLI flags.
        // ConfigRegistry.setSidecar merges system props automatically as the top layer.
        val sidecarCv = loadSidecarConfig(path)
        scalascript.config.ConfigRegistry.setSidecar(sidecarCv.getOrElse(scalascript.config.ConfigValue.empty))
        if frontendFlag.isEmpty then
          scalascript.config.ConfigRegistry.getSidecar
            .flatMap(_.get("frontend").flatMap(_.getString))
            .filter(validFrontendNames)
            .foreach(applyFrontendBackend)
        val module = loadModule(path)
        val effectiveBackend =
          if backendId == "int" then
            // Check front-matter `backend:` for a per-file default.
            module.manifest.flatMap(_.raw.get("backend"))
              .collect { case s: String => s }
              .getOrElse("int")
          else backendId
        // Spark version resolution lives at the CLI layer because the
        // front-matter `raw` map is dropped by Normalize — we read it
        // here while we still hold the ast.Module, then thread it
        // through BackendOptions.extra to the SPI backend.
        val extras: Map[String, String] =
          if effectiveBackend == "spark" then
            val version =
              sparkVersionFlag
                .orElse(
                  module.manifest.flatMap(_.raw.get("spark-version"))
                    .collect { case s: String => s }
                )
                .getOrElse(SparkGen.DefaultVersion)
            val master =
              sparkMasterFlag
                .orElse(
                  module.manifest.flatMap(_.raw.get("spark-master"))
                    .collect { case s: String => s }
                )
                .getOrElse(SparkGen.DefaultMaster)
            // `spark-config:` front-matter map (v1.25 § 9.5 Phase C.3
            // slice 3): read the YAML map, encode into a single
            // BackendOptions.extra string entry under "sparkConfig".
            // SparkBackend.compile decodes and forwards to SparkGen
            // which emits one `.config(k, v)` per pair.
            val configMap = module.manifest
              .flatMap(_.raw.get("spark-config"))
              .map(SparkBackend.fromYamlMap)
              .getOrElse(Map.empty)
            // `spark-app-name:` front-matter override for the Spark UI
            // / driver / executor log name (Phase C.3 slice 4).
            val appName = module.manifest
              .flatMap(_.raw.get("spark-app-name"))
              .collect { case s: String => s }
            // Phase G.2 — `spark-hive-metastore:` (Thrift URI) and
            // `spark-warehouse:` (path) keys.  Both read here from
            // the raw YAML map before Normalize strips it; both
            // threaded through BackendOptions.extra as single
            // strings.  Either key alone is enough to trigger the
            // Hive wiring on the SparkSession (catalogImplementation
            // = hive + enableHiveSupport + spark-hive_2.13 dep).
            val hiveMetastore = module.manifest
              .flatMap(_.raw.get("spark-hive-metastore"))
              .collect { case s: String => s }
            val warehouse = module.manifest
              .flatMap(_.raw.get("spark-warehouse"))
              .collect { case s: String => s }
            val base = Map("sparkVersion" -> version, "sparkMaster" -> master)
            val withConfig =
              if configMap.isEmpty then base
              else base + (SparkBackend.SparkConfigOption ->
                           SparkBackend.encodeSparkConfig(configMap))
            val withAppName = appName.fold(withConfig)(n => withConfig + (SparkBackend.SparkAppNameOption -> n))
            val withHive    = hiveMetastore.fold(withAppName)(uri => withAppName + (SparkBackend.SparkHiveMetastoreOption -> uri))
            warehouse.fold(withHive)(p => withHive + (SparkBackend.SparkWarehouseOption -> p))
          else Map.empty
        val frontendName = frontendFlag.orElse(
          module.manifest.flatMap(_.raw.get("frontend")).collect { case s: String => s }
        )
        val extrasWithFrontend = frontendName.fold(extras)(n => extras + ("frontendName" -> n))
        compileViaBackend(effectiveBackend, path, extrasWithFrontend) match
          case CompileResult.Executed(stdout, stderr, exit) =>
            if stdout.nonEmpty then print(stdout)
            if stderr.nonEmpty then System.err.print(stderr)
            if exit != 0 then System.exit(exit)
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
      finally scalascript.config.ConfigRegistry.clearSidecar()

private[cli] def runJvmViaScalaCli(sscFile: os.Path, serverBackend: String, purpose: String): Unit =
  if !os.exists(sscFile) then
    System.err.println(s"run: file not found: $sscFile"); System.exit(1)
  try
    val rawScript = expectText(compileViaBackend("jvm", sscFile), purpose)
    val script    = injectServerBackend(rawScript, serverBackend)
    val tmp = os.temp(script, suffix = ".sc", deleteOnExit = true)
    try
      val result = os.proc(scalaCliCommand, "run", tmp, "--server=false")
        .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
      if result.exitCode != 0 then System.exit(result.exitCode)
    finally os.remove(tmp)
  catch case e: Exception =>
    System.err.println(s"run: ${e.getMessage}"); System.exit(1)

private[cli] def runJvmServerDev(sscFile: os.Path, serverBackend: String, bind: RunBindOptions): Unit =
  val source = os.read(sscFile)
  val effectivePort = bind.port.orElse(detectServePort(source))
  effectivePort.foreach { port =>
    printComponentUrls("backend server", bind.host, port, backendUrl = None)
  }
  val runFile =
    bind.port match
      case Some(port) =>
        val rewritten = rewritePlainServePort(source, port)
        if rewritten == source then sscFile
        else os.temp(rewritten, suffix = ".ssc", deleteOnExit = true)
      case None => sscFile
  runJvmViaScalaCli(runFile, serverBackend, "run --mode server")

private[cli] var runJvmServerHook: (os.Path, String, RunBindOptions) => Unit =
  runJvmServerDev

/** Compile `sscFile` to an Electron bundle in a temp dir and launch
 *  `electron <tmpDir>`.  Blocks until the Electron window is closed. */
private def runElectronDev(sscFile: os.Path): Unit =
  if !os.exists(sscFile) then
    System.err.println(s"run: file not found: $sscFile"); System.exit(1)
  val tmpDir = os.temp.dir(prefix = "ssc-electron-", deleteOnExit = true)
  val module = scalascript.parser.Parser.parse(os.read(sscFile))
  buildElectronBundle(sscFile, tmpDir)
  val title = module.manifest
    .flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
  println(s"ssc: launching Electron — $title")
  println(s"     bundle: $tmpDir")
  // Verify electron is on PATH before attempting launch.
  val electronOk =
    scala.util.Try(os.proc(electronCommand, "--version").call(check = false).exitCode == 0)
      .getOrElse(false)
  if !electronOk then
    System.err.println("ssc: 'electron' not found on PATH.  Install it:")
    System.err.println("  npm install -g electron")
    System.err.println("  ssc toolchain install --target desktop")
    System.exit(1)
  val exit = runElectronProject(tmpDir, module)
  if exit != 0 then System.exit(exit)

private[cli] def runElectronClientDev(sscFile: os.Path, backendBaseUrl: String): Unit =
  if !os.exists(sscFile) then
    System.err.println(s"run: file not found: $sscFile"); System.exit(1)
  val tmpDir = os.temp.dir(prefix = "ssc-electron-client-", deleteOnExit = true)
  val module = scalascript.parser.Parser.parse(os.read(sscFile))
  buildElectronBundle(sscFile, tmpDir, backendBaseUrl = Some(backendBaseUrl))
  val title = module.manifest
    .flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
  println(s"ssc: launching Electron client — $title")
  println(s"     backend: $backendBaseUrl")
  println(s"     bundle:  $tmpDir")
  val exit = runElectronProject(tmpDir, module)
  if exit != 0 then System.exit(exit)

private[cli] var runElectronClientDevHook: (os.Path, String) => Unit =
  runElectronClientDev

private[cli] def runWebClientPreview(sscFile: os.Path, frontend: String, backendBaseUrl: String, bind: RunBindOptions): Unit =
  if !os.exists(sscFile) then
    System.err.println(s"run: file not found: $sscFile"); System.exit(1)
  applyFrontendBackend(frontend)
  val tmpDir = os.temp.dir(prefix = "ssc-web-client-", deleteOnExit = true)
  os.write(tmpDir / "index.html", renderSpaHtml(sscFile, Some(backendBaseUrl)))
  val server = com.sun.net.httpserver.HttpServer.create(
    new java.net.InetSocketAddress(bind.host, bind.port.getOrElse(0)),
    0
  )
  server.createContext("/", exchange =>
    val path = exchange.getRequestURI.getPath
    val file = if path == "/" || path.isEmpty then tmpDir / "index.html" else tmpDir / path.stripPrefix("/")
    if os.exists(file) && os.isFile(file) then
      val bytes = os.read.bytes(file)
      val contentType =
        if file.last.endsWith(".html") then "text/html; charset=utf-8"
        else if file.last.endsWith(".js") then "application/javascript; charset=utf-8"
        else if file.last.endsWith(".css") then "text/css; charset=utf-8"
        else "application/octet-stream"
      exchange.getResponseHeaders.set("Content-Type", contentType)
      exchange.sendResponseHeaders(200, bytes.length)
      val out = exchange.getResponseBody
      try out.write(bytes) finally out.close()
    else
      val bytes = "Not found".getBytes("UTF-8")
      exchange.sendResponseHeaders(404, bytes.length)
      val out = exchange.getResponseBody
      try out.write(bytes) finally out.close()
  )
  server.start()
  val port = server.getAddress.getPort
  val localUrl = printComponentUrls(s"frontend client ($frontend)", bind.host, port, Some(backendBaseUrl))
  println(s"  open browser: ${bind.openBrowser}")
  println("  press Ctrl+C to stop")
  if bind.openBrowser then openBrowserHook(localUrl)
  sys.addShutdownHook(server.stop(0))
  Thread.currentThread().join()

private[cli] var runWebClientPreviewHook: (os.Path, String, String, RunBindOptions) => Unit =
  runWebClientPreview

/** Dev-run split-process Electron mode.
 *
 *  The JVM backend is generated with the existing JVM backend and started via
 *  scala-cli.  The Electron renderer is generated from the same source, but its
 *  browser fetch overlay receives `__sscBackendBaseUrl`, so relative
 *  `fetch("/api/...")` calls go to the JVM server instead of the renderer's
 *  self-contained route table.
 */
private[cli] def runElectronJvmRestDev(sscFile: os.Path, serverBackend: String): Unit =
  if !os.exists(sscFile) then
    System.err.println(s"run: file not found: $sscFile"); System.exit(1)

  val module = loadModule(sscFile)
  val title  = module.manifest.flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
  val port   = detectServePort(os.read(sscFile)).getOrElse(8080)
  val backendBaseUrl = s"http://127.0.0.1:$port"
  val desktopToken = java.util.UUID.randomUUID().toString

  val rawScript = expectText(compileViaBackend("jvm", sscFile), "run --backend jvm-rest")
  val script    = injectDesktopTokenMiddleware(injectServerBackend(rawScript, serverBackend))
  val backendScript = os.temp(script, suffix = ".sc", deleteOnExit = true)
  val backendPb = new java.lang.ProcessBuilder(scalaCliCommand, "run", backendScript.toString, "--server=false")
  backendPb.inheritIO()
  backendPb.environment().put("SSC_DESKTOP_TOKEN", desktopToken)
  val backendProcess = backendPb.start()

  var electronExit = 0
  try
    waitForTcpReady("127.0.0.1", port, backendProcess, timeoutMs = 30000)
    val tmpDir = os.temp.dir(prefix = "ssc-electron-jvm-rest-", deleteOnExit = true)
    buildElectronBundle(sscFile, tmpDir, backendBaseUrl = Some(backendBaseUrl), desktopToken = Some(desktopToken))
    println(s"ssc: launching Electron JVM REST app — $title")
    println(s"     backend: $backendBaseUrl")
    println(s"     bundle:  $tmpDir")
    electronExit = runElectronProject(tmpDir, module)
  finally
    if backendProcess.isAlive then
      backendProcess.destroy()
      if !backendProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) then
        backendProcess.destroyForcibly()
    scala.util.Try(os.remove(backendScript))
  if electronExit != 0 then System.exit(electronExit)

private[cli] var runElectronJvmRestDevHook: (os.Path, String) => Unit =
  runElectronJvmRestDev

private[cli] var scalaCliCommand: String = "scala-cli"
private[cli] var electronCommand: String = "electron"
private[cli] var npmCommand:      String = "npm"
private[cli] var openBrowserHook: String => Unit = openSystemBrowser

private[cli] case class RunBindOptions(
    host:        String = "0.0.0.0",
    port:        Option[Int] = None,
    openBrowser: Boolean = false
)

private[cli] def printComponentUrls(component: String, bindHost: String, port: Int, backendUrl: Option[String]): String =
  val localHost =
    if bindHost == "0.0.0.0" || bindHost == "::" then "127.0.0.1"
    else bindHost
  val localUrl = s"http://$localHost:$port/"
  val externalUrls =
    if bindHost == "0.0.0.0" || bindHost == "::" then localNetworkAddresses().map(ip => s"http://$ip:$port/")
    else if bindHost == "127.0.0.1" || bindHost == "localhost" then Nil
    else List(s"http://$bindHost:$port/")
  println(s"ssc $component")
  println(s"  local URL:   $localUrl")
  if externalUrls.nonEmpty then
    println("  external URLs:")
    externalUrls.foreach(url => println(s"    $url"))
  else println("  external URLs: none detected")
  backendUrl.foreach(url => println(s"  backend URL: $url"))
  localUrl

private[cli] def bindOptions(
    path:            os.Path,
    hostOverride:    Option[String],
    portOverride:    Option[Int],
    browserOverride: Option[Boolean]
): RunBindOptions =
  RunBindOptions(
    host = hostOverride
      .orElse(frontMatterString(path, "host", "bind-host", "bindHost"))
      .getOrElse("0.0.0.0"),
    port = portOverride.orElse(frontMatterInt(path, "port")),
    openBrowser = browserOverride
      .orElse(frontMatterBoolean(path, "open-browser", "openBrowser"))
      .getOrElse(false)
  )

private[cli] def jsStringLiteral(s: String): String =
  "\"" + s.flatMap {
    case '\\' => "\\\\"
    case '"'  => "\\\""
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c    => c.toString
  } + "\""

private[cli] def localNetworkAddresses(): List[String] =
  import scala.jdk.CollectionConverters.*
  java.net.NetworkInterface.getNetworkInterfaces.asScala.toList
    .filter(ni => !ni.isLoopback && ni.isUp)
    .flatMap(_.getInetAddresses.asScala.toList)
    .collect { case a: java.net.Inet4Address if !a.isLoopbackAddress => a.getHostAddress }
    .distinct
    .sorted

private def openSystemBrowser(url: String): Unit =
  val osName = sys.props.getOrElse("os.name", "").toLowerCase
  val cmd =
    if osName.contains("mac") then Seq("open", url)
    else if osName.contains("linux") then Seq("xdg-open", url)
    else Seq("cmd", "/c", "start", url)
  scala.util.Try(new java.lang.ProcessBuilder(cmd*).start())

private def runElectronProject(tmpDir: os.Path, module: Module): Int =
  val electronOk =
    scala.util.Try(os.proc(electronCommand, "--version").call(check = false).exitCode == 0)
      .getOrElse(false)
  if !electronOk then
    System.err.println("ssc: 'electron' not found on PATH.  Install it:")
    System.err.println("  npm install -g electron")
    System.err.println("  ssc toolchain install --target desktop")
    return 1
  if module.manifest.exists(_.databases.nonEmpty) then
    val npmOk =
      scala.util.Try(os.proc(npmCommand, "--version").call(check = false).exitCode == 0)
        .getOrElse(false)
    if npmOk then
      println("ssc: installing Electron runtime dependencies")
      val install = os.proc(npmCommand, "install", "--silent", "--omit=dev")
        .call(cwd = tmpDir, check = false)
      if install.exitCode != 0 then
        System.err.println(s"ssc: npm install failed, falling back to vendored sql.js assets:\n${install.out.text()}${install.err.text()}")
    else
      println("ssc: npm not found; using vendored sql.js assets")
  os.proc(electronCommand, tmpDir.toString)
    .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    .exitCode

private[cli] def detectServePort(source: String): Option[Int] =
  val uiServe = """(?s)\bserve\s*\(.*?,\s*([0-9]{1,5})""".r
  val plainServe = """\bserve\s*\(\s*([0-9]{1,5})""".r
  uiServe.findFirstMatchIn(source)
    .orElse(plainServe.findFirstMatchIn(source))
    .flatMap(m => m.group(1).toIntOption)
    .filter(p => p > 0 && p <= 65535)

private[cli] def parseBooleanFlag(name: String, value: String): Option[Boolean] =
  value.trim.toLowerCase match
    case "true" | "yes" | "on" | "1"  => Some(true)
    case "false" | "no" | "off" | "0" => Some(false)
    case other =>
      System.err.println(s"$name must be true/false, yes/no, on/off, or 1/0; got '$other'")
      System.exit(1)
      None

private[cli] def parsePortFlag(name: String, value: String): Int =
  value.trim.toIntOption.filter(p => p >= 0 && p <= 65535).getOrElse {
    System.err.println(s"$name must be an integer port in 0..65535; got '$value'")
    System.exit(1)
    0
  }

private[cli] def parseTransportFlag(name: String, value: String): BackendTransportKind =
  BackendTransportKind.parse(value).getOrElse {
    System.err.println(s"$name must be http or in-process; got '$value'")
    System.exit(1)
    BackendTransportKind.Http
  }

private[cli] def validateRunTransport(
    path:              os.Path,
    runMode:           Option[String],
    serverUrlOverride: Option[String],
    transportOverride: Option[BackendTransportKind]
): Unit =
  val transport = resolveRunTransport(path, transportOverride).fold(
    message =>
      System.err.println(message)
      System.exit(1)
      None,
    identity
  )
  validateTransportSelection(runMode, serverUrlOverride, transport).left.foreach { message =>
    System.err.println(message)
    System.exit(1)
  }

private[cli] def resolveRunTransport(
    path:              os.Path,
    transportOverride: Option[BackendTransportKind]
): Either[String, Option[BackendTransportKind]] =
  transportOverride match
    case some @ Some(_) => Right(some)
    case None =>
      frontMatterTransportName(path) match
        case None => Right(None)
        case Some(raw) =>
          BackendTransportKind.parse(raw)
            .map(kind => Right(Some(kind)))
            .getOrElse(Left(s"front matter transport must be http or in-process; got '$raw'"))

private[cli] def validateTransportSelection(
    runMode:   Option[String],
    serverUrl: Option[String],
    transport: Option[BackendTransportKind]
): Either[String, Unit] =
  transport match
    case None | Some(BackendTransportKind.Http) => Right(())
    case Some(BackendTransportKind.InProcess) =>
      runMode match
        case Some("server") =>
          Left("run --mode server does not support --transport in-process; server-only mode uses HTTP/server runtime")
        case Some("client") =>
          Left("run --mode client does not support --transport in-process; client-only mode requires --server-url over HTTP")
        case _ if serverUrl.nonEmpty =>
          Left("run --server-url implies --transport http and cannot be combined with --transport in-process")
        case _ => Right(())

private[cli] def validateRunJvmTransportSelection(
    frontendName: Option[String],
    transport:    Option[BackendTransportKind]
): Either[String, Unit] =
  transport match
    case None | Some(BackendTransportKind.Http) => Right(())
    case Some(BackendTransportKind.InProcess) =>
      frontendName match
        case Some("swing") | Some("javafx") => Right(())
        case Some(other) =>
          Left(s"run-jvm --transport in-process requires a JVM-hosted frontend; '$other' is not supported")
        case None =>
          Left("run-jvm --transport in-process is planned but not implemented yet for JVM execution")

private[cli] def frontMatterTransport(path: os.Path): Option[BackendTransportKind] =
  frontMatterTransportName(path).flatMap(BackendTransportKind.parse)

private[cli] def frontMatterTransportName(path: os.Path): Option[String] =
  val flat = frontMatterString(path, "transport")
  flat.orElse(frontMatterNestedString(path, "fullstack", "transport"))

private[cli] def frontMatterString(path: os.Path, keys: String*): Option[String] =
  scala.util.Try(loadModule(path).manifest.flatMap { manifest =>
    keys.iterator
      .flatMap(key => manifest.raw.get(key))
      .collectFirst { case s: String if s.trim.nonEmpty => s.trim }
  }).getOrElse(None)

private[cli] def frontMatterNestedString(path: os.Path, parent: String, key: String): Option[String] =
  scala.util.Try(loadModule(path).manifest.flatMap { manifest =>
    manifest.raw.get(parent).flatMap(rawMapString(_, key))
  }).getOrElse(None)

private def rawMapString(raw: Any, key: String): Option[String] =
  import scala.jdk.CollectionConverters.*
  raw match
    case m: java.util.Map[?, ?] =>
      m.asInstanceOf[java.util.Map[String, Any]].asScala.get(key)
        .collect { case s: String if s.trim.nonEmpty => s.trim }
    case m: scala.collection.Map[?, ?] =>
      m.asInstanceOf[scala.collection.Map[String, Any]].get(key)
        .collect { case s: String if s.trim.nonEmpty => s.trim }
    case _ => None

private[cli] def frontMatterInt(path: os.Path, keys: String*): Option[Int] =
  scala.util.Try(loadModule(path).manifest.flatMap { manifest =>
    keys.iterator
      .flatMap(key => manifest.raw.get(key))
      .flatMap {
        case n: java.lang.Number => Some(n.intValue())
        case s: String           => s.trim.toIntOption
        case _                   => None
      }
      .find(p => p >= 0 && p <= 65535)
  }).getOrElse(None)

private[cli] def frontMatterBoolean(path: os.Path, keys: String*): Option[Boolean] =
  scala.util.Try(loadModule(path).manifest.flatMap { manifest =>
    keys.iterator
      .flatMap(key => manifest.raw.get(key))
      .flatMap {
        case b: java.lang.Boolean => Some(b.booleanValue())
        case b: Boolean           => Some(b)
        case s: String            => parseBooleanFlag(s"front matter ${keys.mkString("/")}", s)
        case n: java.lang.Number  => Some(n.intValue() != 0)
        case _                    => None
      }
      .toSeq
      .headOption
  }).getOrElse(None)

private[cli] def rewritePlainServePort(source: String, port: Int): String =
  """\bserve\s*\(\s*[0-9]{1,5}\s*\)""".r
    .replaceFirstIn(source, s"serve($port)")

private[cli] def shouldDefaultToElectronJvmRest(module: Module, source: String): Boolean =
  val frontendIsElectron =
    module.manifest.flatMap(_.frontendFramework).exists(_.trim.equalsIgnoreCase("electron"))
  val hasBackendRoute =
    module.manifest.exists(_.routes.nonEmpty) ||
      """(?m)\broute\s*\(""".r.findFirstIn(source).isDefined
  frontendIsElectron && hasBackendRoute && detectServePort(source).isDefined

private[cli] def targetRequestsElectron(target: Option[String]): Boolean =
  target.exists(t => t == "desktop" || t == "desktop-electron" || t == "desktop-jvm")

private[cli] def targetRequestsElectronJvmRest(target: Option[String], backend: Option[String]): Boolean =
  backend.contains("jvm-rest") || target.contains("desktop-jvm")

private def waitForTcpReady(
    host:       String,
    port:       Int,
    process:    java.lang.Process,
    timeoutMs:  Long
): Unit =
  val deadline = System.nanoTime() + timeoutMs * 1000000L
  var ready = false
  while !ready && System.nanoTime() < deadline do
    if !process.isAlive then
      throw new RuntimeException(s"JVM backend exited before listening on $host:$port")
    try
      val socket = new java.net.Socket()
      try
        socket.connect(new java.net.InetSocketAddress(host, port), 250)
        ready = true
      finally socket.close()
    catch case _: java.io.IOException =>
      Thread.sleep(100)
  if !ready then
    throw new RuntimeException(s"JVM backend did not become ready on $host:$port within ${timeoutMs}ms")

def replCommand(@annotation.unused args: List[String]): Unit =
  import scala.io.StdIn
  val interp = Interpreter()
  interp.run(Parser.parse("# REPL\n"))   // initialise builtins, no code runs
  val dbgHooks = ReplDebugHooks()
  interp.setDebugSourceFile("<repl>")
  interp.setDebugHooks(Some(dbgHooks.mkHooks()))
  System.err.println("ScalaScript REPL  (:help for commands, :quit to exit, blank line to run)")
  var running = true
  // Global REPL setting: include handler deserialization details in 400 errors.
  // Read by replHandleMount for typed handler wrapping (Phase 7).
  var errorDetails: Boolean = true
  // Tracks the port the HTTP server is currently listening on (None = stopped).
  val serverPort = new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  while running do
    Option(StdIn.readLine("ssc> ")) match
      case None | Some(":quit" | ":q" | ":exit") => running = false
      case Some(":help" | ":h") =>
        System.err.println(
          """|ScalaScript REPL
             |
             |Input:
             |  Type code and press Enter to add a line.
             |  Blank line          — run the accumulated snippet.
             |  Multi-line input is shown with the "   | " continuation prompt.
             |
             |General:
             |  :help  :h           — this message
             |  :quit  :q  :exit    — exit the REPL
             |  :reset              — clear all bindings, restart interpreter
             |  :set errorDetails <true|false>  — verbose deser errors (default: true)
             |
             |HTTP server:
             |  :serve [port]       — start HTTP server in background (default: 8080)
             |  :stop [--keep-routes] — stop server; clears routes unless --keep-routes
             |  :clear              — clear route table without stopping server
             |
             |Routes:
             |  :mount M /path { expr }         — register inline handler
             |  :mount M /path name             — register function from REPL bindings
             |  :mount M /path file.ssc [k=v]  — register handler from file
             |  :load file.ssc      — run file's route() calls; replaces previous routes
             |  :reload file.ssc    — re-run without repeating method/path
             |  :unmount M /path    — remove a specific route
             |  :routes             — list all registered routes
             |
             |Testing:
             |  :http M /path [body] [-H "K: V"]  — real HTTP request to localhost:<port>
             |  :call M /path [body] [-H "K: V"]  — in-process dispatch (no server needed)
             |
             |Breakpoints & stepping:
             |  :break <N>          — set breakpoint at snippet line N
             |  :break list         — list all breakpoints
             |  :break clear        — remove all breakpoints
             |  :step               — enable step-in for the next snippet
             |
             |Debug sub-prompt (appears when a breakpoint is hit):
             |  :continue  :c       — resume to next breakpoint or end
             |  :next      :n       — step over
             |  :step      :s       — step into
             |  :out                — step out of current function
             |  :locals    :l       — show local variables
             |  :stack     :bt      — show call stack
             |  :print <expr>       — evaluate expression in current frame
             |  :quit      :q       — abort snippet, return to ssc>
             |""".stripMargin)
      case Some(":reset") =>
        interp.run(Parser.parse("# REPL\n"))
        System.err.println("[reset] interpreter cleared")
      case Some(s) if s.startsWith(":set ") =>
        replHandleSet(s.trim, { v => errorDetails = v })
      case Some(s) if s == ":serve" || s.startsWith(":serve ") =>
        replHandleServe(s.trim, serverPort, interp)
      case Some(s) if s == ":stop" || s == ":stop --keep-routes" =>
        replHandleStop(s.trim, serverPort)
      case Some(":clear") =>
        scalascript.server.Routes.clear()
        System.err.println("Routes cleared.")
      case Some(s) if s.startsWith(":mount ") =>
        replHandleMount(s.trim, interp, errorDetails)
      case Some(s) if s.startsWith(":load ") =>
        replHandleLoad(s.trim, interp)
      case Some(s) if s.startsWith(":reload ") =>
        replHandleReload(s.trim, interp)
      case Some(s) if s.startsWith(":unmount ") =>
        replHandleUnmount(s.trim)
      case Some(":routes") =>
        replHandleRoutes()
      case Some(s) if s == ":http" || s.startsWith(":http ") =>
        replHandleHttp(s.trim, serverPort)
      case Some(s) if s == ":call" || s.startsWith(":call ") =>
        replHandleCall(s.trim, interp)
      case Some(s) if s.startsWith(":break")      => replHandleBreak(s.trim, dbgHooks)
      case Some(":step")                          =>
        dbgHooks.enableStepIn()
        System.err.println("[step] step-in enabled — enter your snippet")
      case Some(first) =>
        val lines = scala.collection.mutable.ArrayBuffer(first)
        var more = true
        while more do
          Option(StdIn.readLine("   | ")) match
            case None | Some("") => more = false
            case Some(next)      => lines += next
        val code = lines.mkString("\n").trim
        if code.nonEmpty then
          if dbgHooks.isDebugActive then
            runReplSnippetDebug(code, interp, dbgHooks)
          else
            try   interp.runSnippet(code)
            catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")

/** Handle `:serve [port]` in the REPL.
 *  Starts WebServer in a background virtual thread (non-blocking).
 *  `serverPort` tracks the running port; call is a no-op if already serving. */
def replHandleServe(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]],
    interp:     Interpreter
): Unit =
  serverPort.get() match
    case Some(p) =>
      System.err.println(s"Already serving on :$p.")
    case None =>
      val portOpt: Option[Int] = cmd.stripPrefix(":serve").trim match
        case ""  => Some(8080)
        case s   => s.toIntOption.orElse { System.err.println(s"Invalid port: $s"); None }
      portOpt.foreach { port =>
        val dir = System.getProperty("user.dir")
        serverPort.set(Some(port))
        Thread.ofVirtual().start { () =>
          try
            scalascript.server.WebServer.start(port, dir, interp.out,
              wsRoutes = interp.wsRoutes)
          catch
            case _: Throwable => serverPort.set(None)
        }
        System.err.println(s"Listening on :$port")
      }

/** Handle `:stop [--keep-routes]` in the REPL.
 *  Stops the running server; clears routes unless `--keep-routes` is present. */
def replHandleStop(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]]
): Unit =
  serverPort.get() match
    case None =>
      System.err.println("No server running.")
    case Some(_) =>
      scalascript.server.WebServer.stop()
      serverPort.set(None)
      val keepRoutes = cmd.endsWith("--keep-routes")
      if keepRoutes then
        System.err.println("Server stopped. Routes kept.")
      else
        scalascript.server.Routes.clear()
        System.err.println("Server stopped. Routes cleared.")

/** Handle `:set <key> <value>` in the REPL.
 *
 *  Currently supported keys:
 *  - `errorDetails` — `true` or `false`; controls verbose deser errors (Phase 7).
 *
 *  `setFn` is a callback that receives the parsed Boolean; the caller stores it in
 *  its local `var errorDetails`.  This avoids threading a mutable cell through all
 *  other helpers. */
def replHandleSet(cmd: String, setFn: Boolean => Unit): Unit =
  val rest = cmd.stripPrefix(":set").trim
  rest.split("\\s+", 2).toList match
    case List("errorDetails", value) =>
      value match
        case "true"  => setFn(true);  System.err.println("errorDetails = true")
        case "false" => setFn(false); System.err.println("errorDetails = false")
        case _       => System.err.println("Expected true or false")
    case List(key, _) =>
      System.err.println(s"Unknown setting: $key. Known: errorDetails")
    case _ =>
      System.err.println("Usage: :set errorDetails true|false")

/** Handle `:mount METHOD /path REST` in the REPL.
 *
 *  Three forms are supported:
 *  - Inline:    `:mount GET /ping { _ => Response.text("pong") }`
 *  - By name:   `:mount GET /greet greet`
 *  - From file: `:mount GET /items/:id handlers/entity.ssc [key=value ...]`
 *
 *  `errorDetails` is the global REPL setting from `:set errorDetails`.
 */
def replHandleMount(cmd: String, interp: Interpreter, errorDetails: Boolean = true): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.{Value, TypedHandlerWrapper}
  // Strip ":mount " prefix and split into at most 3 parts: method, path, rest
  val rest0 = cmd.stripPrefix(":mount").trim
  val parts = rest0.split("\\s+", 3)
  if parts.length < 3 then
    System.err.println("Usage: :mount METHOD /path { expr | name | file.ssc [k=v ...] }")
    return
  val method = parts(0).toUpperCase
  val path   = parts(1)
  val rest   = parts(2)

  if rest.startsWith("{") then
    // ── Form 1: inline handler expression ────────────────────────────────
    try
      interp.runSnippet(rest)
      val rawHandler = interp.lastResult
      val baseHandler: Value = rawHandler match
        case fn: Value.FunV => fn
        case other =>
          // Auto-wrap bare Response values
          Value.NativeFnV("mount.static",
            scalascript.interpreter.Computation.pureFn(_ => other))
      val handler = TypedHandlerWrapper.wrapIfTyped(
        baseHandler,
        invoke      = (fn, args) => interp.invoke(fn, args),
        globalsView = interp.globalsView,
        mountedPath = path,
        errorDetails = errorDetails,
      )
      Routes.register(method, path, handler, interp, source = None, mountCtx = Map.empty)
      System.err.println(s"Mounted: $method $path")
    catch
      case e: Exception => System.err.println(s"Error evaluating handler: ${e.getMessage}")

  else if rest.contains(".ssc") && (rest.endsWith(".ssc") || rest.contains(".ssc ")) then
    // ── Form 3: file + optional ctx key=value tokens ──────────────────────
    val tokens = rest.split("\\s+").toList
    val file   = tokens.head
    val ctx: Map[String, Value] = tokens.drop(1).flatMap { t =>
      val pair = t.split("=", 2)
      if pair.length == 2 then Some(pair(0) -> (Value.StringV(pair(1)): Value))
      else None
    }.toMap
    val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
    try
      interp.mountFileAsRoute(method, path, absPath, ctx)
      val ctxStr = if ctx.nonEmpty then
        s", ctx: {${ctx.map((k, v) => s"$k=${Value.show(v)}").mkString(", ")}}"
      else ""
      System.err.println(s"Mounted: $method $path  ($file$ctxStr)")
    catch
      case e: Exception => System.err.println(s"Error mounting $file: ${e.getMessage}")

  else
    // ── Form 2: function name from REPL globals ───────────────────────────
    val name = rest.trim
    interp.globalsView.get(name) match
      case None =>
        System.err.println(s"Unknown name: $name")
      case Some(fn: Value.FunV) =>
        val handler = TypedHandlerWrapper.wrapIfTyped(
          fn,
          invoke       = (fn2, args) => interp.invoke(fn2, args),
          globalsView  = interp.globalsView,
          mountedPath  = path,
          errorDetails = errorDetails,
        )
        Routes.register(method, path, handler, interp, source = None, mountCtx = Map.empty)
        System.err.println(s"Mounted: $method $path  ($name)")
      case Some(_) =>
        System.err.println(s"Not a function: $name")

/** Handle `:load file.ssc` in the REPL.
 *
 *  Resolves the file to an absolute path, clears any routes previously
 *  registered by that file (via [[Routes.removeBySource]]), then parses and
 *  runs the file with [[Interpreter.setLoadingFile]] set so that every
 *  `route()` call inside records `source = Some(absPath)` and `style = "load"`.
 *  The file is executed in the existing REPL interpreter's context so that
 *  all its globals and plugins are available.  Prints the newly registered
 *  routes on success.
 */
def replHandleLoad(cmd: String, interp: Interpreter): Unit =
  import scalascript.server.Routes
  import scalascript.parser.Parser
  val file = cmd.stripPrefix(":load").trim
  if file.isEmpty then
    System.err.println("Usage: :load file.ssc")
    return
  val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
  val f = new java.io.File(absPath)
  if !f.exists() then
    System.err.println(s"File not found: $file")
    return
  // Remove stale routes from a previous load of this file
  Routes.removeBySource(absPath)
  // Tag all route() calls inside with source + style="load"
  interp.setLoadingFile(Some(absPath))
  try
    val contents = scala.io.Source.fromFile(absPath).mkString
    // Use run() rather than runSections() so that builtins and plugin
    // intrinsics are (re-)initialised before the file's sections execute.
    // run() is additive — it does not clear existing REPL globals, it
    // only (re-)installs builtins on top.
    interp.run(Parser.parse(contents))
  catch
    case e: Exception =>
      System.err.println(s"Error loading $file: ${e.getMessage}")
  finally
    interp.setLoadingFile(None)
  // Print registered routes from this file
  val registered = Routes.all.filter(_.source.contains(absPath))
  if registered.isEmpty then
    System.err.println(s"Loaded $file: (no routes registered)")
  else
    System.err.println(s"Loaded $file:")
    registered.foreach { e =>
      System.err.println(s"  ${e.method.padTo(6, ' ')} ${e.path}")
    }

/** Handle `:reload file.ssc` in the REPL.
 *
 *  Looks up existing [[Routes.Entry]] records with `source == Some(absPath)`.
 *  If the entries have `style == "load"` (registered via `:load`), re-runs
 *  [[replHandleLoad]].  If they have `style == "mount"` (registered via
 *  `:mount file.ssc`), re-mounts each one using the stored method, path,
 *  and mountCtx.  Mixed styles in the same file are handled entry-by-entry.
 */
def replHandleReload(cmd: String, interp: Interpreter): Unit =
  import scalascript.server.Routes
  val file = cmd.stripPrefix(":reload").trim
  if file.isEmpty then
    System.err.println("Usage: :reload file.ssc")
    return
  val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
  val existing = Routes.all.filter(_.source.contains(absPath))
  if existing.isEmpty then
    System.err.println(s"Unknown file: $file — use :mount or :load first.")
    return
  val f = new java.io.File(absPath)
  if !f.exists() then
    System.err.println(s"File not found: $file")
    return
  // Partition by registration style
  val (loadEntries, mountEntries) = existing.partition(_.style == "load")
  // Re-load style: clear + rerun (replHandleLoad handles printing)
  if loadEntries.nonEmpty then
    replHandleLoad(s":load $file", interp)
  // Re-mount style: re-mount each entry with its original method/path/ctx
  mountEntries.foreach { entry =>
    try
      interp.mountFileAsRoute(entry.method, entry.path, absPath, entry.mountCtx)
      System.err.println(s"Reloaded: ${entry.method} ${entry.path}  ($file)")
    catch
      case e: Exception =>
        System.err.println(s"Error reloading ${entry.method} ${entry.path}: ${e.getMessage}")
  }

/** Handle `:unmount METHOD /path` in the REPL. */
def replHandleUnmount(cmd: String): Unit =
  import scalascript.server.Routes
  val rest = cmd.stripPrefix(":unmount").trim
  val parts = rest.split("\\s+", 2)
  if parts.length < 2 then
    System.err.println("Usage: :unmount METHOD /path")
    return
  val method = parts(0).toUpperCase
  val path   = parts(1)
  if Routes.remove(method, path) then
    System.err.println(s"Unmounted: $method $path")
  else
    System.err.println(s"Not mounted: $method $path")

/** Handle `:routes` in the REPL.
 *
 *  Prints a formatted table of all registered routes:
 *    method (padded to 6) | path (padded to longest+2) | source | ctx
 *
 *  If no routes are registered: prints `(no routes registered)`. */
def replHandleRoutes(): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.Value
  val entries = Routes.all
  if entries.isEmpty then
    System.err.println("(no routes registered)")
  else
    val pathWidth = (entries.map(_.path.length) :+ 4).max + 2
    entries.foreach { e =>
      val method  = e.method.padTo(6, ' ')
      val path    = e.path.padTo(pathWidth, ' ')
      val src     = e.source match
        case None       => "<inline>"
        case Some(abs)  =>
          // Try to make it relative to CWD; fall back to basename only
          val cwd = java.nio.file.Paths.get("").toAbsolutePath
          try
            val rel = cwd.relativize(java.nio.file.Paths.get(abs)).toString
            if rel.length < abs.length then rel else java.nio.file.Paths.get(abs).getFileName.toString
          catch case _: Throwable => java.nio.file.Paths.get(abs).getFileName.toString
      val ctxStr  = if e.mountCtx.nonEmpty then
        "  {" + e.mountCtx.map((k, v) => s"$k=${Value.show(v)}").mkString(", ") + "}"
      else ""
      System.err.println(s"  $method $path $src$ctxStr")
    }

/** Parse `-H "Key: Value"` flags and body tokens from REPL `:http`/`:call` args.
 *
 *  Returns `(headers: Map[String,String], bodyTokens: List[String])`.
 *  Tokens following `-H` (each must be a single "Key: Value" string) are
 *  consumed as headers; all remaining tokens are joined as the body. */
private def parseHttpArgs(tokens: List[String]): (Map[String, String], String) =
  val headers = scala.collection.mutable.LinkedHashMap.empty[String, String]
  val body    = scala.collection.mutable.ListBuffer.empty[String]
  var i = 0
  val arr = tokens.toArray
  while i < arr.length do
    if arr(i) == "-H" && i + 1 < arr.length then
      val hv = arr(i + 1)
      val colon = hv.indexOf(':')
      if colon > 0 then
        headers(hv.take(colon).trim) = hv.drop(colon + 1).trim
      i += 2
    else
      body += arr(i)
      i += 1
  (headers.toMap, body.mkString(" "))

/** Map an HTTP numeric status code to its standard reason phrase. */
private def httpStatusText(status: Int): String = status match
  case 100 => "Continue"
  case 101 => "Switching Protocols"
  case 200 => "OK"
  case 201 => "Created"
  case 202 => "Accepted"
  case 204 => "No Content"
  case 206 => "Partial Content"
  case 301 => "Moved Permanently"
  case 302 => "Found"
  case 303 => "See Other"
  case 304 => "Not Modified"
  case 307 => "Temporary Redirect"
  case 308 => "Permanent Redirect"
  case 400 => "Bad Request"
  case 401 => "Unauthorized"
  case 403 => "Forbidden"
  case 404 => "Not Found"
  case 405 => "Method Not Allowed"
  case 409 => "Conflict"
  case 410 => "Gone"
  case 422 => "Unprocessable Entity"
  case 429 => "Too Many Requests"
  case 500 => "Internal Server Error"
  case 501 => "Not Implemented"
  case 502 => "Bad Gateway"
  case 503 => "Service Unavailable"
  case _   => ""

/** Print a `:http` / `:call` response line in the format:
 *  {{{
 *  → 200 OK  text/plain
 *  pong
 *  }}}
 */
private def printHttpResponse(status: Int, contentType: String, body: String): Unit =
  val reason = httpStatusText(status)
  val statusLine = if reason.nonEmpty then s"→ $status $reason" else s"→ $status"
  val ctLine = if contentType.nonEmpty then s"$statusLine  $contentType" else statusLine
  System.err.println(ctLine)
  if body.nonEmpty then System.err.println(body)

/** Handle `:http METHOD /path [body] [-H "Key: Value" ...]` in the REPL.
 *
 *  Sends a real HTTP/1.1 request over a raw `java.net.Socket` to
 *  `localhost:<port>`.  Requires a server started with `:serve`. */
def replHandleHttp(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]]
): Unit =
  serverPort.get() match
    case None =>
      System.err.println("No server running. Use :serve [port] first.")
    case Some(port) =>
      val rest = cmd.stripPrefix(":http").trim
      val tokens = splitRespectingQuotes(rest)
      if tokens.length < 2 then
        System.err.println("Usage: :http METHOD /path [body] [-H \"Key: Value\"] ...")
        return
      val method = tokens(0).toUpperCase
      val path   = tokens(1)
      val (headers, body) = parseHttpArgs(tokens.drop(2))
      try
        val sock  = java.net.Socket("localhost", port)
        try
          val out  = sock.getOutputStream
          val bodyBytes = body.getBytes("UTF-8")
          val sb = new StringBuilder
          sb.append(s"$method $path HTTP/1.1\r\n")
          sb.append(s"Host: localhost\r\n")
          if bodyBytes.nonEmpty then
            sb.append(s"Content-Length: ${bodyBytes.length}\r\n")
          headers.foreach { case (k, v) => sb.append(s"$k: $v\r\n") }
          sb.append("\r\n")
          out.write(sb.toString.getBytes("UTF-8"))
          if bodyBytes.nonEmpty then out.write(bodyBytes)
          out.flush()

          // Read response
          val in = sock.getInputStream
          val response = readHttpResponse(in)
          val (status, contentType, respBody) = response
          printHttpResponse(status, contentType, respBody)
        finally
          sock.close()
      catch
        case e: Exception =>
          System.err.println(s"HTTP error: ${e.getMessage}")

/** Read a minimal HTTP/1.1 response from an InputStream.
 *  Returns `(statusCode, contentType, body)`. */
private def readHttpResponse(in: java.io.InputStream): (Int, String, String) =
  var endOfHeaders = false
  val headers = scala.collection.mutable.LinkedHashMap.empty[String, String]
  var status  = 200
  var statusParsed = false
  // Read line-by-line through the header section
  val lineBytes = new java.io.ByteArrayOutputStream
  var b = in.read()
  while b != -1 && !endOfHeaders do
    if b == '\n' then
      val line = lineBytes.toString("UTF-8").stripTrailing()
      lineBytes.reset()
      if !statusParsed then
        // HTTP/1.1 200 OK
        val parts = line.split(" +", 3)
        if parts.length >= 2 then
          status = parts(1).toIntOption.getOrElse(200)
        statusParsed = true
      else if line.isEmpty then
        endOfHeaders = true
      else
        val colon = line.indexOf(':')
        if colon > 0 then
          headers(line.take(colon).trim.toLowerCase) = line.drop(colon + 1).trim
    else if b != '\r' then
      lineBytes.write(b)
    b = in.read()
  // Read body: Content-Length if present, else read until EOF
  val ct = headers.getOrElse("content-type", "")
  val contentType = ct.split(";").headOption.map(_.trim).getOrElse(ct)
  val bodyStr =
    headers.get("content-length") match
      case Some(lenStr) =>
        val len = lenStr.toIntOption.getOrElse(0)
        if len > 0 then
          val bodyArr = new Array[Byte](len)
          var total = 0
          while total < len do
            val n = in.read(bodyArr, total, len - total)
            if n < 0 then total = len else total += n
          new String(bodyArr, "UTF-8")
        else ""
      case None =>
        // No Content-Length — read until connection close
        val bodyBuf = new java.io.ByteArrayOutputStream
        val chunk = new Array[Byte](4096)
        var n = in.read(chunk)
        while n > 0 do
          bodyBuf.write(chunk, 0, n)
          n = in.read(chunk)
        bodyBuf.toString("UTF-8")
  (status, contentType, bodyStr)

/** Tokenize a string respecting double-quoted groups.
 *  `foo "bar baz" qux` → `List("foo", "bar baz", "qux")`. */
private def splitRespectingQuotes(s: String): List[String] =
  val tokens = scala.collection.mutable.ListBuffer.empty[String]
  val cur    = new StringBuilder
  var inQ    = false
  for ch <- s do
    if ch == '"' then
      inQ = !inQ
    else if ch == ' ' && !inQ then
      if cur.nonEmpty then { tokens += cur.toString(); cur.clear() }
    else
      cur += ch
  if cur.nonEmpty then tokens += cur.toString()
  tokens.toList

/** Handle `:call METHOD /path [body] [-H "Key: Value" ...]` in the REPL.
 *
 *  In-process dispatch — no network, no `:serve` needed.  Builds a synthetic
 *  `Request` value from the parsed tokens, dispatches via `Routes.matchRequest`,
 *  invokes the handler, and prints the result. */
def replHandleCall(cmd: String, @annotation.unused interp: Interpreter): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.Value
  val rest = cmd.stripPrefix(":call").trim
  val tokens = splitRespectingQuotes(rest)
  if tokens.length < 2 then
    System.err.println("Usage: :call METHOD /path [body] [-H \"Key: Value\"] ...")
    return
  val method = tokens(0).toUpperCase
  // Path may contain a query string
  val rawPath = tokens(1)
  val (pathOnly, queryStr) =
    val q = rawPath.indexOf('?')
    if q >= 0 then (rawPath.take(q), rawPath.drop(q + 1)) else (rawPath, "")
  val (headers, body) = parseHttpArgs(tokens.drop(2))
  // Parse query string into Map[Value, Value] (required by MapV)
  val query: Map[Value, Value] = queryStr.split('&').flatMap { kv =>
    val eq = kv.indexOf('=')
    if eq > 0 then
      Some((Value.StringV(kv.take(eq)): Value) -> (Value.StringV(kv.drop(eq + 1)): Value))
    else if kv.nonEmpty then
      Some((Value.StringV(kv): Value) -> (Value.EmptyStr: Value))
    else None
  }.toMap
  Routes.matchRequest(method, pathOnly) match
    case None =>
      System.err.println("→ 404 Not Found")
    case Some((entry, params)) =>
      val req = Value.InstanceV("Request", Map(
        "method"      -> Value.StringV(method),
        "path"        -> Value.StringV(pathOnly),
        "params"      -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
        "query"       -> Value.MapV(query),
        "headers"     -> Value.MapV(headers.map((k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value))),
        "body"        -> Value.StringV(body),
        "form"        -> Value.EmptyMap,
        "files"       -> Value.EmptyMap,
        "session"     -> Value.EmptyMap,
        "bearerToken" -> Value.NoneV,
        "jwtClaims"   -> Value.NoneV,
        "basicAuth"   -> Value.NoneV
      ))
      try
        val result = entry.interpreter.invoke(entry.handler, List(req))
        val (status, contentType, respBody) = extractCallResponse(result)
        printHttpResponse(status, contentType, respBody)
      catch
        case e: Exception =>
          System.err.println(s"→ 500 Internal Server Error")
          System.err.println(s"Error: ${e.getMessage}")

/** Extract status, content-type, and body from an invoked handler's result
 *  for `:call` (in-process dispatch, no HTTP socket). */
private def extractCallResponse(v: scalascript.interpreter.Value): (Int, String, String) =
  import scalascript.interpreter.Value
  v match
    case Value.InstanceV("Response", fields) =>
      val status = fields.get("status") match
        case Some(Value.IntV(n)) => n.toInt
        case _                   => 200
      val ct = fields.get("headers") match
        case Some(Value.MapV(m)) =>
          m.collectFirst {
            case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase("content-type") => v
          }.getOrElse("").split(";").headOption.map(_.trim).getOrElse("")
        case _ => ""
      val body = fields.get("body") match
        case Some(Value.StringV(s)) => s
        case Some(other)            => Value.show(other)
        case None                   => ""
      (status, ct, body)
    case Value.StringV(s) =>
      (200, "text/plain", s)
    case Value.UnitV =>
      (204, "", "")
    case other =>
      (200, "text/plain", Value.show(other))

def replHandleBreak(cmd: String, hooks: ReplDebugHooks): Unit =
  cmd match
    case ":break clear" | ":b clear" =>
      hooks.clearAllBreakpoints()
      System.err.println("[break] all breakpoints cleared")
    case ":break list" | ":b list" =>
      val bps = hooks.listBreakpoints
      if bps.isEmpty then System.err.println("[break] no breakpoints set")
      else System.err.println(s"[break] lines: ${bps.mkString(", ")}")
    case s =>
      s.stripPrefix(":break").stripPrefix(":b").trim.toIntOption match
        case Some(n) =>
          hooks.setBreakpoint(n)
          System.err.println(s"[break] set at line $n")
        case None =>
          System.err.println("Usage: :break <N> | :break clear | :break list")

/** Run a snippet on a background thread with debug hooks active.
 *  Blocks the calling (REPL main) thread until execution completes or the user quits. */
def runReplSnippetDebug(code: String, interp: Interpreter, hooks: ReplDebugHooks): Unit =
  val codeLines = code.linesIterator.toVector
  hooks.resetForNewSnippet()
  val thread = Thread.ofVirtual().start { () =>
    try   interp.runSnippet(code)
    catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")
    finally hooks.signalFinished()
  }
  var inSnippet = true
  while inSnippet do
    val item = hooks.stoppedQueue.take()
    item match
      case None        => inSnippet = false
      case Some(frame) =>
        replPrintStop(frame, codeLines, hooks.blockDocLine)
        inSnippet = replDebugSubLoop(frame, thread, interp, hooks)
  thread.join()
  hooks.clearStepMode()

/** Interactive `(debug) ` sub-loop for one stop.
 *  Returns true = snippet still running (user resumed), false = user quit. */
def replDebugSubLoop(
    frame:  scalascript.interpreter.debug.DebugFrame,
    thread: Thread,
    interp: Interpreter,
    hooks:  ReplDebugHooks
): Boolean =
  import scala.io.StdIn
  import ReplDebugHooks.StepMode
  var resume    = false
  var keepGoing = true
  while !resume do
    Option(StdIn.readLine("(debug) ")) match
      case None | Some(":quit" | ":q") =>
        thread.interrupt()
        keepGoing = false
        resume    = true
      case Some(":continue" | ":c") =>
        hooks.resume(StepMode.Off)
        resume = true
      case Some(":next" | ":n") =>
        hooks.resume(StepMode.StepOver(frame.callDepth))
        resume = true
      case Some(":step" | ":s") =>
        hooks.resume(StepMode.StepIn)
        resume = true
      case Some(":out") =>
        hooks.resume(StepMode.StepOut(frame.callDepth))
        resume = true
      case Some(":locals" | ":l") =>
        replPrintLocals(frame)
      case Some(":stack" | ":bt") =>
        replPrintCallStack(frame, hooks.blockDocLine)
      case Some(s) if s.startsWith(":print ") =>
        replEvalPrint(s.drop(7).trim, frame, interp)
      case Some(":help" | ":h") =>
        replPrintDebugHelp()
      case Some("") => ()
      case Some(other) =>
        System.err.println(s"  Unknown: $other  (:help for commands)")
  keepGoing

def replPrintStop(
    frame:        scalascript.interpreter.debug.DebugFrame,
    codeLines:    Vector[String],
    blockDocLine: Int
): Unit =
  val snippetLine = frame.line - blockDocLine
  val lineText    = codeLines.lift(snippetLine - 1).getOrElse("???")
  System.err.println(s"[stopped] at line $snippetLine")
  System.err.println(s"  > $lineText")

def replPrintLocals(frame: scalascript.interpreter.debug.DebugFrame): Unit =
  val visible = frame.locals
    .filter { case (k, v) =>
      !k.startsWith("_") && !k.startsWith("$") &&
      !v.isInstanceOf[scalascript.interpreter.Value.NativeFnV]
    }
    .toList.sortBy(_._1)
  if visible.isEmpty then System.err.println("  (no locals)")
  else visible.foreach { case (n, v) =>
    System.err.println(s"  $n = ${scalascript.interpreter.Value.show(v)}")
  }

def replPrintCallStack(
    frame:        scalascript.interpreter.debug.DebugFrame,
    blockDocLine: Int
): Unit =
  val snippetLine = frame.line - blockDocLine
  System.err.println(s"  [0] ${frame.name} : line $snippetLine")
  frame.callFrames.reverseIterator.zipWithIndex.foreach { case (cf, i) =>
    val cfLine = cf.line - blockDocLine
    System.err.println(s"  [${i + 1}] ${cf.name} : line $cfLine")
  }

def replEvalPrint(
    exprSrc: String,
    frame:   scalascript.interpreter.debug.DebugFrame,
    interp:  Interpreter
): Unit =
  try
    val v = interp.evalExpr(exprSrc, frame.locals)
    System.err.println(s"  = ${scalascript.interpreter.Value.show(v)}")
  catch case e: Exception =>
    System.err.println(s"  Error: ${e.getMessage}")

def replPrintDebugHelp(): Unit =
  System.err.println(
    """|  Debug commands:
       |    :continue | :c      — resume to next breakpoint or end
       |    :next     | :n      — step over to next line
       |    :step     | :s      — step into next expression
       |    :out               — step out of current function
       |    :locals   | :l      — show local variables
       |    :stack    | :bt     — show call stack
       |    :print <expr>      — evaluate expression in current context
       |    :quit     | :q      — stop and return to REPL""".stripMargin
  )

def watchCommand(args: List[String]): Unit =
  import java.nio.file.{FileSystems, Paths, StandardWatchEventKinds}
  import scala.jdk.CollectionConverters.*
  if args.isEmpty then { println("Error: No file specified"); System.exit(1) }
  // Parse --frontend flag (same as runCommand; flag overrides frontmatter)
  val it = args.iterator
  var fileArg:     Option[String] = None
  var frontendArg: Option[String] = None
  while it.hasNext do
    val a = it.next()
    if a == "--frontend" && it.hasNext then
      val name = it.next()
      if validFrontendNames(name) then frontendArg = Some(name)
      else { System.err.println(s"watch: unknown --frontend '$name'"); System.exit(1) }
    else if !a.startsWith("-") && fileArg.isEmpty then fileArg = Some(a)
  val file    = fileArg.getOrElse { println("Error: No file specified"); System.exit(1); "" }
  val absPath = Paths.get(file).toAbsolutePath.normalize
  val dir     = absPath.getParent
  val osPath  = os.Path(absPath)
  // Apply frontend: CLI flag wins; fall back to frontmatter `frontend:` key.
  frontendArg match
    case Some(name) => applyFrontendBackend(name)
    case None =>
      ParseCache.getOrParse(osPath).manifest
        .flatMap(_.raw.get("frontend"))
        .collect { case s: String => s }
        .filter(validFrontendNames)
        .foreach(applyFrontendBackend)

  // ── Hot-reload state ─────────────────────────────────────────────────
  // `isServerFile` is set to true on first run if the source contains a
  // top-level `serve(` call.  Once true, subsequent reloads use headless
  // mode so the already-running server port is not rebound; only the
  // route table is refreshed.
  var isServerFile  = false
  // Whether the server has already been started (bound port + blocking thread).
  var serverStarted = false
  // Section snapshots from the previous type-check run, enabling incremental
  // re-checking: only sections whose content hash changed are re-typed.
  var prevTyperSnapshots: List[SectionSnapshot] = Nil

  // ── Incremental interpreter state (non-server mode only) ─────────────
  // Reusing the same Interpreter across cycles lets us skip re-running
  // unchanged sections.  Checkpoints(i) = interpreter state before section i.
  // Length = module.sections.length + 1 after each run.
  // Server files are excluded: route-table mutations live outside `globals`,
  // so checkpoint restore would leave stale routes in scalascript.server.Routes.
  var theInterp: Interpreter = null
  var interpCheckpoints: Vector[scalascript.interpreter.InterpCheckpoint] = Vector.empty

  def timestamp(): String =
    val now = java.time.LocalTime.now()
    f"${now.getHour}%02d:${now.getMinute}%02d:${now.getSecond}%02d"

  def runOnce(headless: Boolean): Unit =
    try
      val module      = ParseCache.getOrParse(osPath)
      val oldSnaps    = prevTyperSnapshots
      // Incremental type-check: re-type only sections whose hash changed.
      val (typed, newSnaps) = Typer.typeCheckIncrementalModule(module, oldSnaps)
      // Section-level diff: which sections were added / modified / removed?
      val diff = SectionDiff.compute(oldSnaps, newSnaps)
      prevTyperSnapshots = newSnaps
      if typed.errors.nonEmpty then
        typed.errors.foreach(e => System.err.println(s"[${timestamp()}] type: ${e.show}"))
      // Skip interpreter re-run on false-positive watch events (editor touched
      // mtime without changing content — ParseCache already de-duped the parse,
      // and SectionDiff confirms nothing actually changed).
      if diff.isEmpty && oldSnaps.nonEmpty then
        System.err.println(s"[${timestamp()}] (no section changes — skipping re-run)")
        return
      if !diff.isEmpty && oldSnaps.nonEmpty then
        System.err.println(s"[${timestamp()}] changed: ${diff.show}")
      if headless then
        // Server hot-reload: full re-run on a fresh interpreter so the route
        // table is rebuilt cleanly (Routes.clear + new headless Interpreter).
        scalascript.server.Routes.clear()
        val hi = Interpreter(baseDir = Some(osPath / os.up), headless = true)
        frontendArg.orElse(ParseCache.getOrParse(osPath).manifest.flatMap(_.raw.get("frontend")).collect { case s: String => s })
          .foreach(n => hi.injectGlobal("_ssc_frontend_name", scalascript.interpreter.Value.StringV(n)))
        hi.run(module)
      else if theInterp != null && interpCheckpoints.nonEmpty then
        // Incremental path: find first changed section and re-run only from there.
        val prevHashes = oldSnaps.map(_.sectionHash)
        val currHashes = newSnaps.map(_.sectionHash)
        val firstChanged = currHashes.zipWithIndex.collectFirst {
          case (h, i) if prevHashes.lift(i).forall(_ != h) => i
        }.getOrElse(module.sections.length)
        val skipped = module.sections.length - (module.sections.length - firstChanged)
        val t0 = System.nanoTime()
        interpCheckpoints = theInterp.runSectionsIncremental(
          module.sections, firstChanged, interpCheckpoints
        )
        val ms = (System.nanoTime() - t0) / 1_000_000
        if skipped > 0 then
          System.err.println(
            s"[${timestamp()}] incremental: skipped $skipped/${module.sections.length} sections (${ms}ms)"
          )
      else
        // First run for non-server file: run everything and record checkpoints.
        val interp = Interpreter(baseDir = Some(osPath / os.up), headless = false)
        frontendArg.orElse(ParseCache.getOrParse(osPath).manifest.flatMap(_.raw.get("frontend")).collect { case s: String => s })
          .foreach(n => interp.injectGlobal("_ssc_frontend_name", scalascript.interpreter.Value.StringV(n)))
        theInterp = interp
        interpCheckpoints = interp.runWithCheckpoints(module)
    catch
      case e: Exception =>
        System.err.println(s"[${timestamp()}] Error: ${e.getMessage}")

  // Detect whether the raw source contains a `serve(` call so we can
  // choose the right reload strategy without modifying the AST pipeline.
  def detectServerFile(): Boolean =
    try
      val src = os.read(osPath)
      // Match `serve(` that is not inside a line comment.  Simple heuristic:
      // strip everything after `//` on each line, then look for `serve(`.
      src.linesIterator.exists { line =>
        val stripped = line.replaceAll("//.*", "").trim
        stripped.contains("serve(")
      }
    catch case _: Exception => false

  System.err.println(s"[${timestamp()}] Watching ${absPath.getFileName}... (Ctrl+C to stop)")

  // ── First run ────────────────────────────────────────────────────────
  isServerFile = detectServerFile()
  System.err.println(
    s"[${timestamp()}] Running ${absPath.getFileName}" +
    (if isServerFile then " (server mode — hot reload enabled)" else "") + "..."
  )
  if isServerFile then
    // Start the server on a background thread so the watch loop can continue.
    val t = Thread(() => runOnce(headless = false))
    t.setDaemon(false)
    t.start()
    serverStarted = true
    // Give the server a moment to bind before we start watching.
    Thread.sleep(500)
  else
    runOnce(headless = false)

  // ── Watch loop ───────────────────────────────────────────────────────
  val watcher = FileSystems.getDefault.newWatchService()
  dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
  while true do
    val key     = watcher.take()
    val changed = key.pollEvents().asScala.exists { ev =>
      ev.context().asInstanceOf[java.nio.file.Path].getFileName == absPath.getFileName
    }
    if changed then
      // Small debounce: editors often fire two ENTRY_MODIFY events in
      // quick succession (content write + metadata update).
      Thread.sleep(50)
      System.err.println(s"\n[2J[H[${timestamp()}] Reloading ${absPath.getFileName}...")
      if isServerFile && serverStarted then
        runOnce(headless = true)
        System.err.println(s"[${timestamp()}] Routes reloaded.")
      else
        runOnce(headless = false)
    key.reset()

private case class WatchBenchConfig(
    file:          String = "",
    cycles:        Int = 7,
    targetMs:      Long = 100,
    requireTarget: Boolean = false
)

private def watchBenchCommand(args: List[String]): Unit =
  if args.isEmpty then
    System.err.println("Usage: ssc watch-bench [--cycles N] [--target-ms N] [--require-target] <file.ssc>")
    System.exit(1)
  var cfg = WatchBenchConfig()
  var i   = 0
  def nextValue(flag: String): String =
    if i + 1 >= args.length then
      System.err.println(s"watch-bench: missing value for $flag")
      System.exit(1)
    i += 1
    args(i)
  while i < args.length do
    args(i) match
      case "--cycles" =>
        cfg = cfg.copy(cycles = nextValue("--cycles").toInt)
      case "--target-ms" =>
        cfg = cfg.copy(targetMs = nextValue("--target-ms").toLong)
      case "--require-target" =>
        cfg = cfg.copy(requireTarget = true)
      case s if !s.startsWith("-") && cfg.file.isEmpty =>
        cfg = cfg.copy(file = s)
      case other =>
        System.err.println(s"watch-bench: unknown argument: $other")
        System.exit(1)
    i += 1
  if cfg.file.isEmpty then
    System.err.println("Usage: ssc watch-bench [--cycles N] [--target-ms N] [--require-target] <file.ssc>")
    System.exit(1)
  if cfg.cycles < 1 then
    System.err.println("watch-bench: --cycles must be positive")
    System.exit(1)

  val srcPath = os.Path(java.nio.file.Paths.get(cfg.file).toAbsolutePath.normalize)
  if !os.exists(srcPath) then
    System.err.println(s"watch-bench: file not found: ${cfg.file}")
    System.exit(1)

  val tmpDir  = os.temp.dir(prefix = "ssc-watch-bench-", deleteOnExit = true)
  val tmpFile = tmpDir / srcPath.last
  os.copy(srcPath, tmpFile)
  ParseCache.clear()

  def isServerSource(): Boolean =
    os.read(tmpFile).linesIterator.exists { line =>
      line.replaceAll("//.*", "").contains("serve(")
    }

  def mutate(cycle: Int): Unit =
    val marker = s"// watch-bench-cycle-$cycle"
    val src    = os.read(tmpFile)
    val idx    = src.lastIndexOf("```")
    val next =
      if idx >= 0 then src.take(idx) + marker + "\n" + src.drop(idx)
      else src + s"\n# Watch Bench $cycle\n```scala\n$marker\n```\n"
    os.write.over(tmpFile, next)

  var prevSnapshots: List[SectionSnapshot] = Nil
  var interp: Interpreter = null
  var checkpoints: Vector[scalascript.interpreter.InterpCheckpoint] = Vector.empty
  val serverMode = isServerSource()
  def benchInterpreter(headless: Boolean): Interpreter =
    Interpreter(
      out = new java.io.PrintStream(java.io.ByteArrayOutputStream(), true, "UTF-8"),
      baseDir = Some(tmpFile / os.up),
      headless = headless
    )

  def reloadOnce(cold: Boolean): Long =
    val t0 = System.nanoTime()
    val module = ParseCache.getOrParse(tmpFile)
    val (typed, newSnapshots) = Typer.typeCheckIncrementalModule(module, prevSnapshots)
    val oldSnapshots = prevSnapshots
    prevSnapshots = newSnapshots
    if typed.errors.nonEmpty then
      typed.errors.foreach(e => System.err.println(s"watch-bench type: ${e.show}"))
    if serverMode then
      scalascript.server.Routes.clear()
      benchInterpreter(headless = true).run(module)
    else if cold || interp == null || checkpoints.isEmpty then
      interp = benchInterpreter(headless = false)
      checkpoints = interp.runWithCheckpoints(module)
    else
      val prevHashes = oldSnapshots.map(_.sectionHash)
      val currHashes = newSnapshots.map(_.sectionHash)
      val firstChanged = currHashes.zipWithIndex.collectFirst {
        case (h, idx) if prevHashes.lift(idx).forall(_ != h) => idx
      }.getOrElse(module.sections.length)
      checkpoints = interp.runSectionsIncremental(module.sections, firstChanged, checkpoints)
    (System.nanoTime() - t0) / 1_000_000L

  val warmMs = reloadOnce(cold = true)
  val samples = (1 to cfg.cycles).map { cycle =>
    mutate(cycle)
    val ms = reloadOnce(cold = false)
    println(s"cycle $cycle: ${ms}ms")
    ms
  }.toVector.sorted
  val p50 = samples(samples.length / 2)
  val max = samples.last
  println(s"watch-bench: file=${srcPath.last} mode=${if serverMode then "server" else "script"} warm=${warmMs}ms p50=${p50}ms max=${max}ms target=${cfg.targetMs}ms")
  if cfg.requireTarget && max > cfg.targetMs then
    System.err.println(s"watch-bench: max ${max}ms exceeded target ${cfg.targetMs}ms")
    System.exit(1)

def emitJsCommand(args: List[String]): Unit =
  // Parse --no-tree-shake and --stats flags before processing files.
  var noTreeShake = false
  var printStats  = false
  val files = args.filter {
    case "--no-tree-shake" => noTreeShake = true; false
    case "--stats"         => printStats  = true; false
    case _                 => true
  }
  if files.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- files do
    val path    = os.Path(file, os.pwd)
    val baseDir = Some(path / os.up)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module   = Parser.parse(os.read(path))
        val segments = compileJsSegments(path, noTreeShake = noTreeShake)
        val hasSSBlocks = segments.exists {
          case Segment.Code("javascript", _) => true
          case _                             => false
        }
        if hasSSBlocks then
          val caps = JsGen.detectCapabilities(module, baseDir)
          print(JsGen.generateRuntime(caps))
        if printStats && !noTreeShake then
          // Re-run tree-shaking to get stats (shake result is separate from segmented output)
          val (_, statsOpt) = JsGen.generateWithStats(module, baseDir, noTreeShake = false)
          statsOpt.foreach(s => System.err.println(s.summary))
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

def emitWasmCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val stem = path.last.stripSuffix(".ssc")
        compileViaBackend("wasm", path) match
          case CompileResult.Segmented(segs) =>
            if segs.isEmpty then
              System.err.println("emit-wasm: no compilable scala/scalascript blocks found in source")
              System.exit(1)
            for seg <- segs do seg match
              case Segment.Asset(name, bytes, _) =>
                val out = os.pwd / name
                os.write.over(out, bytes)
                System.err.println(s"Wrote $out (${bytes.length} bytes)")
              case Segment.Code("javascript", glue) =>
                val out = os.pwd / s"$stem.js"
                os.write.over(out, glue)
                System.err.println(s"Wrote $out")
              case _ => ()
          case CompileResult.Failed(diags) =>
            diags.foreach(d => System.err.println(s"[error] $d"))
            System.exit(1)
          case other =>
            System.err.println(s"emit-wasm: unexpected ${other.getClass.getSimpleName}")
            System.exit(1)
      catch case e: Exception =>
        System.err.println(s"WASM generation error: ${e.getMessage}")
        System.exit(1)

def emitSpaCommand(args: List[String]): Unit =
  // v1.18 / Phase A7 — optional --frontend <custom|react|solid|vue>
  // picks which FrontendFrameworkSpi impl downstream SPA codegen routes
  // through.  Today the SPA path doesn't yet consume the registry (that
  // lands in A8), but validating + selecting here keeps the flag stable.
  var frontendBackend: Option[String] = None
  var serverUrl:       Option[String] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it    = args.iterator
  while it.hasNext do
    it.next() match
      case "--frontend" if it.hasNext =>
        val name = it.next()
        if !browserFrontendNames.contains(name) then
          System.err.println(
            s"emit-spa: unknown --frontend '$name' " +
            s"(valid: ${browserFrontendNames.toList.sorted.mkString(" / ")})")
          System.exit(1)
        frontendBackend = Some(name)
      case "--server-url" if it.hasNext =>
        serverUrl = Some(it.next())
      case f => files += f
  if files.isEmpty then { println("Error: No files specified"); System.exit(1) }
  frontendBackend.foreach(applyFrontendBackend)
  for file <- files.toList do
    val path    = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        println(renderSpaHtml(path, serverUrl))
      catch case e: Exception =>
        System.err.println(s"SPA generation error: ${e.getMessage}")
        System.exit(1)

private[cli] def renderSpaHtml(sscFile: os.Path, backendBaseUrl: Option[String]): String =
  val baseDir = Some(sscFile / os.up)
  val module = Parser.parse(os.read(sscFile))
  val segments = compileJsSegments(sscFile)
  val title    = module.manifest.flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
  // Concatenate user JS — same segment loop as emit-js but no
  // process.stdout flushes (browser-only output goes to console).
  val userJs = segments.collect {
    case Segment.Code("javascript", code) => code
    case Segment.Source("scala", src)     =>
      ScalaJsBackend.compileSourceToJs(src, baseDir)
  }.filter(_.nonEmpty).mkString("\n")
  val rawJs = rawJavaScriptBlocks(module)
  // v1.17 Phase 3 — when the user's JS references `mcpConnect`,
  // splice in the browser-compatible MCP client preamble.  The
  // Node-side `JsRuntimeMcp` would import worker_threads etc.,
  // which crashes in a browser; the browser variant uses sync XHR
  // with zero deps.
  val browserJs = userJs + "\n" + rawJs
  val mcpPreamble =
    if browserJs.contains("mcpConnect") || browserJs.contains("mcpServer") then
      "\n" + JsRuntimeMcpBrowser
    else ""
  // Tree-shake: detect which runtime blocks are actually needed,
  // then exclude Node-only capabilities (Mcp, Dataset) that would
  // crash in a browser environment.
  val allCaps    = JsGen.detectCapabilities(module, baseDir)
  val spaCaps    = allCaps - JsGen.Capability.Mcp - JsGen.Capability.Dataset
  val spaRuntime = JsGen.generateRuntime(spaCaps)
  val backendInit = backendBaseUrl.fold("") { url =>
    s"globalThis.__sscBackendBaseUrl = ${jsStringLiteral(url)}; // injected by ssc --server-url\n"
  }
  s"""<!doctype html>
     |<html lang="en">
     |<head>
     |  <meta charset="utf-8">
     |  <meta name="viewport" content="width=device-width, initial-scale=1">
     |  <title>$title</title>
     |</head>
     |<body>
     |<script>
     |$backendInit$spaRuntime
     |$JsRuntimeBrowserPatch$mcpPreamble
     |$rawJs
     |$userJs
     |</script>
     |</body>
     |</html>""".stripMargin

private[cli] def rawJavaScriptBlocks(module: Module): String =
  def collect(section: Section): List[String] =
    section.content.collect {
      case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) => cb.source
    } ++ section.subsections.flatMap(collect)

  module.sections.flatMap(collect).filter(_.nonEmpty).mkString("\n")

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
        val wcCaps = JsGen.detectCapabilities(module, baseDir)
        print(JsGen.generateRuntime(wcCaps))
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
          val jsHookIndented = jsHook.replace("\n", "\n  ")
          // Anonymous class via `customElements.define(tag, class extends … {})`
          // — avoids clashing with the heading-bound `<section>Component` object
          // JsGen synthesises for the markdown section that introduces the
          // component.
          // SSR hydration guard: if the element was rendered server-side with
          // declarative shadow DOM (`<template shadowrootmode="open">`), the
          // browser deserialises the shadow root before JS runs — skip
          // attachShadow/innerHTML so the pre-rendered content isn't wiped.
          println(s"""
customElements.define('$tag', class extends HTMLElement {
  static get observedAttributes() { return [$paramsArr]; }
  connectedCallback() {
    if (this.shadowRoot && this.shadowRoot.childNodes.length > 0) {
      const shadow = this.shadowRoot;$jsHookIndented
      return;
    }
    const shadow = this.attachShadow({mode: 'open'});
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
// ssc emit-spark  —  Apache Spark backend (Phase 1: local SparkSession)
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc emit-spark [--spark-version <v>] <file.ssc> [-o <file.scala>]`
 *
 *  Parses and type-checks the `.ssc` file, then emits a Scala 3 + Spark
 *  program to stdout (or to `-o <file>` if specified).
 *
 *  The generated program uses `SparkSession.builder().master("local[*]")`
 *  so it can be run locally without a cluster.  To run it:
 *
 *  ```
 *  ssc emit-spark myfile.ssc -o myfile.spark.scala
 *  scala-cli run myfile.spark.scala \
 *    --dep org.apache.spark:spark-core_2.13:4.0.0 \
 *    --dep org.apache.spark:spark-sql_2.13:4.0.0 \
 *    --scala 3
 *  ```
 *
 *  Spark version resolution (highest priority first):
 *  1. `--spark-version <v>` CLI flag
 *  2. `spark-version:` in front-matter YAML
 *  3. [[SparkGen.DefaultVersion]] (currently `4.0.0`)
 *
 *  Phase 1: `master("local[*]")` is hardcoded.
 *  Phase 2: `--spark-master` flag + `ssc submit` for cluster submission.
 */
def emitSparkCommand(args: List[String]): Unit =
  var outputArg:       Option[String] = None
  var sparkVersionArg: Option[String] = None
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" if it.hasNext              => outputArg = Some(it.next())
      case "--spark-version" if it.hasNext => sparkVersionArg = Some(it.next())
      case f                               => files += f
  if files.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- files do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        // Version resolution: CLI flag > front-matter > default.
        val sparkVersion =
          sparkVersionArg
            .orElse(
              module.manifest.flatMap(_.raw.get("spark-version"))
                .collect { case s: String => s }
            )
            .getOrElse(SparkGen.DefaultVersion)
        // Pull the `spark-master` and `spark-config:` front-matter so the
        // emitted source matches `ssc run --backend spark` and `ssc submit`
        // for the same input.  `emit-spark` has no CLI master flag of its
        // own — the front-matter is the only override path here.
        val sparkMaster = module.manifest.flatMap(_.raw.get("spark-master"))
          .collect { case s: String => s }
          .getOrElse(SparkGen.DefaultMaster)
        val sparkConfig = module.manifest
          .flatMap(_.raw.get("spark-config"))
          .map(SparkBackend.fromYamlMap)
          .getOrElse(Map.empty[String, String])
        val appName = module.manifest.flatMap(_.raw.get("spark-app-name"))
          .collect { case s: String => s }
          .getOrElse(SparkGen.DefaultAppName)
        val code = SparkGen.generate(
          module,
          baseDir      = Some(path / os.up),
          sparkVersion = sparkVersion,
          sparkMaster  = sparkMaster,
          extraConfig  = sparkConfig,
          appName      = appName
        )
        outputArg match
          case Some("-") | None => println(code)
          case Some(out)        =>
            val outPath = os.Path(out, os.pwd)
            os.write.over(outPath, code)
            System.err.println(s"Spark source written to $out")
      catch case e: Exception =>
        System.err.println(s"Spark generation error: ${e.getMessage}")
        System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc submit  —  Apache Spark backend, cluster submission (v1.25 § 9.5 B.2)
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc submit <file.ssc> [--spark-master <url>] [--spark-version <v>] [--dry-run] [-- <extra spark-submit args>]`
 *
 *  Closes the gap between `ssc run --backend spark` (Phase A/B.1 — ships the
 *  driver via `scala-cli run`, only viable for thin-classpath Spark Standalone)
 *  and a real cluster deployment.  `submit` packages the generated Spark
 *  source into a fat assembly JAR via `scala-cli --power package --assembly`
 *  and then launches it through `spark-submit`, which is the standard entry
 *  point for YARN, Kubernetes, and production Spark Standalone.
 *
 *  Flow:
 *  1. Parse the `.ssc` file (no Normalize — we need the manifest raw map
 *     for `spark-version` / `spark-master` resolution, same as `runCommand`).
 *  2. Resolve `sparkVersion` and `sparkMaster` with the standard three-level
 *     priority: CLI flag > front-matter > [[SparkGen.Default…]].
 *  3. Generate the Scala 3 + Spark source and write it to
 *     `/tmp/ssc-spark-<hash>.scala`.
 *  4. Run [[SparkSubmit.packageCommand]] — produces `/tmp/ssc-spark-<hash>.jar`.
 *  5. Run [[SparkSubmit.submitCommand]] — launches it on the resolved master.
 *
 *  `--dry-run` skips steps 4–5 and prints the argv that would be run;
 *  useful for shell integration testing and for the user to inspect /
 *  copy-paste the spark-submit invocation.
 *
 *  Anything after a literal `--` separator on the command line flows
 *  through to spark-submit verbatim (`extraSparkArgs` in
 *  [[SparkSubmit.submitCommand]]) — that's how users pass
 *  `--executor-memory 4g`, `--num-executors 8`, `--deploy-mode cluster`,
 *  etc., without ScalaScript needing a per-flag model. */
def submitCommand(args: List[String]): Unit =
  var sparkVersionArg: Option[String] = None
  var sparkMasterArg:  Option[String] = None
  var dryRun:          Boolean        = false
  val files          = scala.collection.mutable.ArrayBuffer.empty[String]
  val extraSparkArgs = scala.collection.mutable.ArrayBuffer.empty[String]
  var pastSeparator  = false
  val it = args.iterator
  while it.hasNext do
    val a = it.next()
    if pastSeparator then extraSparkArgs += a
    else a match
      case "--spark-version" if it.hasNext => sparkVersionArg = Some(it.next())
      case "--spark-master"  if it.hasNext => sparkMasterArg  = Some(it.next())
      case "--dry-run"                     => dryRun = true
      case "--"                            => pastSeparator = true
      case f                               => files += f
  if files.isEmpty then
    System.err.println("Usage: ssc submit <file.ssc> [--spark-master <url>] [--spark-version <v>] [--dry-run] [-- <extra spark-submit args>]")
    System.exit(1)
  if files.size > 1 then
    System.err.println(s"Error: ssc submit accepts one .ssc file, got ${files.size}")
    System.exit(1)
  val file = files.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    System.err.println(s"Error: File not found: $file")
    System.exit(1)
  try
    val module = Parser.parse(os.read(path))
    val sparkVersion =
      sparkVersionArg
        .orElse(module.manifest.flatMap(_.raw.get("spark-version")).collect { case s: String => s })
        .getOrElse(SparkGen.DefaultVersion)
    val sparkMaster =
      sparkMasterArg
        .orElse(module.manifest.flatMap(_.raw.get("spark-master")).collect { case s: String => s })
        .getOrElse(SparkGen.DefaultMaster)
    // `spark-config:` front-matter map (v1.25 § 9.5 Phase C.3 slice 3):
    // baked into the SparkSession.builder so the same configs apply
    // whether the user runs `ssc run --backend spark` or submits the
    // fat JAR via `spark-submit` from the cluster side.
    val sparkConfig = module.manifest
      .flatMap(_.raw.get("spark-config"))
      .map(SparkBackend.fromYamlMap)
      .getOrElse(Map.empty[String, String])
    val appName = module.manifest.flatMap(_.raw.get("spark-app-name"))
      .collect { case s: String => s }
      .getOrElse(SparkGen.DefaultAppName)
    val code = SparkGen.generate(
      module,
      baseDir      = Some(path / os.up),
      sparkVersion = sparkVersion,
      sparkMaster  = sparkMaster,
      extraConfig  = sparkConfig,
      appName      = appName
    )
    val hash    = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val srcPath = os.Path(s"/tmp/ssc-spark-$hash.scala")
    val jarPath = os.Path(s"/tmp/ssc-spark-$hash.jar")
    os.write.over(srcPath, code)
    val pkgCmd    = SparkSubmit.packageCommand(srcPath, jarPath, sparkVersion)
    val submitCmd = SparkSubmit.submitCommand(
      jar            = jarPath,
      master         = sparkMaster,
      extraSparkArgs = extraSparkArgs.toList
    )
    if dryRun then
      println(s"# Spark $sparkVersion (master=$sparkMaster)")
      println(s"# source: $srcPath")
      println(pkgCmd.mkString(" "))
      println(submitCmd.mkString(" "))
    else
      System.err.println(s"[spark] Spark $sparkVersion (master=$sparkMaster) — packaging: $srcPath → $jarPath")
      val pkgExit = scala.sys.process.Process(pkgCmd).!
      if pkgExit != 0 then
        System.err.println(s"[spark] scala-cli package failed (exit $pkgExit)")
        System.exit(pkgExit)
      System.err.println(s"[spark] submitting → $sparkMaster")
      val submitExit = scala.sys.process.Process(submitCmd).!
      if submitExit != 0 then System.exit(submitExit)
  catch case e: Exception =>
    System.err.println(s"ssc submit error: ${e.getMessage}")
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
      if reportCodeBlockParseErrors(module, file) then
        System.exit(1)
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
      if reportCodeBlockParseErrors(module, file) then
        System.exit(1)
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

// ─────────────────────────────────────────────────────────────────────────────
// ssc compile-jvm  —  v2.0 JVM-backend incremental codegen cache
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc run-jvm <file.ssc> [-- args...]`
 *
 *  One-shot: compile `.ssc` via JvmGen, write to a temp `.sc` file, and
 *  run it immediately with `scala-cli run --server=false`.  Equivalent to
 *  `compile-jvm` + `link --backend jvm` but without leaving any artifacts
 *  on disk.  Requires `scala-cli` on PATH. */
/** Compile `path` via JvmGen and write the result to a `.scjvm` artifact at
 *  `scjvmPath` for future cache hits.  Returns the generated Scala 3 source. */
private def compileJvmAndCache(
    path:            os.Path,
    baseName:        String,
    scjvmPath:       os.Path,
    frontendOverride: Option[String] = None
): String =
  val module    = Parser.parse(os.read(path))
  val baseDir   = Some(path / os.up)
  val source    = JvmGen.generate(module, baseDir, frontendOverride = frontendOverride)
  scala.util.Try {
    val sourceHash = InterfaceExtractor.sha256(os.read.bytes(path))
    val moduleId   = module.manifest.flatMap(_.name).getOrElse(baseName)
    val pkg        = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val moduleName = module.manifest.flatMap(_.name)
    os.makeDir.all(scjvmPath / os.up)
    JvmArtifactIO.writeJvmFile(moduleId, pkg, moduleName, sourceHash, source, Nil, scjvmPath)
  }.recover { case e => System.err.println(s"[warn] artifact write failed: $e") }
  source

def runJvmCommand(args: List[String]): Unit =
  if args.isEmpty then
    System.err.println("Usage: ssc run-jvm [--frontend <custom|react|solid|vue|swing|javafx>] [--transport <http|in-process>] <file.ssc>")
    System.exit(1)
  var jvmFrontendFlag: Option[String] = None
  var jvmTransportFlag: Option[BackendTransportKind] = None
  var jvmFileArg:      Option[String] = None
  val jvmIt = args.iterator
  while jvmIt.hasNext do
    jvmIt.next() match
      case "--frontend" if jvmIt.hasNext =>
        val name = jvmIt.next()
        if !validFrontendNames(name) then
          System.err.println(s"run-jvm: unknown --frontend '$name', valid: ${validFrontendNames.mkString(", ")}")
          System.exit(1)
        jvmFrontendFlag = Some(name)
      case "--transport" if jvmIt.hasNext =>
        jvmTransportFlag = Some(parseTransportFlag("run-jvm --transport", jvmIt.next()))
      case f => jvmFileArg = Some(f)
  val file = jvmFileArg.getOrElse {
    System.err.println("Usage: ssc run-jvm [--frontend <custom|react|solid|vue|swing|javafx>] [--transport <http|in-process>] <file.ssc>")
    System.exit(1); ""
  }
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    System.err.println(s"Error: File not found: $file"); System.exit(1)

  // Artifact cache: skip JvmGen codegen when the .scjvm artifact is fresh.
  // The artifact is stored in <file-dir>/.ssc-artifacts/<name>.scjvm and is
  // invalidated by a SHA-256 mismatch against the current source bytes.
  // --frontend CLI flag or sidecar config frontend: key bypass the cache.
  import scalascript.artifact.ModuleGraph
  val artDir    = AutoResolve.defaultArtifactDir(path)
  val baseName  = path.last.stripSuffix(".ssc")
  val scjvmPath = artDir / (baseName + ".scjvm")
  // Load config for run-jvm: frontmatter < sidecar files < -Dscalascript.* < CLI flag.
  val jvmSidecar = loadSidecarConfig(path)
  scalascript.config.ConfigRegistry.setSidecar(jvmSidecar.getOrElse(scalascript.config.ConfigValue.empty))
  val sidecarFrontend = scalascript.config.ConfigRegistry.getSidecar
    .flatMap(_.get("frontend").flatMap(_.getString)).filter(validFrontendNames)
  // CLI flag beats all; system props beat sidecar files; both beat frontmatter.
  val frontendOverride = jvmFrontendFlag.orElse(sidecarFrontend)
  val transport = resolveRunTransport(path, jvmTransportFlag).fold(
    message =>
      System.err.println(s"run-jvm: $message")
      System.exit(1)
      None,
    identity
  )
  validateRunJvmTransportSelection(frontendOverride, transport).left.foreach { message =>
    System.err.println(message)
    System.exit(1)
  }
  if !JvmBytecode.scalaCliAvailable then
    System.err.println(s"run-jvm: ${JvmBytecode.scalaCliMissingMessage}")
    System.exit(1)
  val raw =
    if frontendOverride.isEmpty && !ModuleGraph.isJvmStale(path, artDir) then
      JvmArtifactIO.readJvmFile(scjvmPath) match
        case Right(art) => art.scalaSource
        case Left(_)    => compileJvmAndCache(path, baseName, scjvmPath)
    else
      compileJvmAndCache(path, baseName, scjvmPath, frontendOverride)

  val jarsDir = scalascript.imports.ImportResolver.libPath.map(_ / "bin" / "lib" / "jars")
  val source = jarsDir match
    case Some(jars) => patchLocalSscDeps(raw, jars)
    case None       => raw
  val tmp = os.temp(source, suffix = ".sc", deleteOnExit = true)
  try
    val sub = os.proc("scala-cli", "run", tmp, "--server=false")
      .spawn(stdout = os.Inherit, stderr = os.Inherit)
    // Kill the entire scala-cli process tree on JVM shutdown so the server
    // doesn't linger after Ctrl+C or SIGTERM.
    def killTree(ph: ProcessHandle): Unit =
      ph.descendants().forEach(killTree(_))
      ph.destroyForcibly()
    val hook = new Thread(() => killTree(sub.wrapped.toHandle))
    Runtime.getRuntime.addShutdownHook(hook)
    sub.waitFor()
    Runtime.getRuntime.removeShutdownHook(hook)
    val exitCode = sub.wrapped.exitValue()
    if exitCode != 0 then System.exit(exitCode)
  catch
    case _: IllegalStateException => () // shutdown already in progress — process tree killed by hook
    case e: Exception =>
      System.err.println(s"run-jvm: scala-cli invocation failed: ${e.getMessage}")
      System.exit(1)

/** Replace `//> using (lib|dep) "io.scalascript::artifact:version"` lines
 *  with `//> using jar <jarsDir>/artifact_3-version.jar` so that run-jvm
 *  uses local staged JARs instead of trying to resolve SNAPSHOTs from Maven. */
private def patchLocalSscDeps(source: String, jarsDir: os.Path): String =
  val pat = raw"""//> using (?:lib|dep) "io\.scalascript::([^:]+):([^"]+)"""".r
  source.linesWithSeparators.map { line =>
    pat.findFirstMatchIn(line.stripLineEnd) match
      case Some(m) =>
        val artifact = m.group(1)
        val version  = m.group(2)
        val jar      = jarsDir / s"${artifact}_3-${version}.jar"
        if os.exists(jar) then s"""//> using jar "${jar}"\n"""
        else line
      case None => line
  }.mkString

/** `ssc run-js <file.ssc>`
 *
 *  One-shot: compile `.ssc` via JsGen (runtime preamble + user code),
 *  write to a temp `.cjs` file, and run it immediately with `node`.
 *  When the module has sql blocks the command installs the required npm
 *  deps (e.g. sql.js) via `npm install` in a temp work dir first.
 *  Requires `node` on PATH; `npm` required only when sql blocks present. */
/** Spawn a node process, register a JVM shutdown hook to kill it on
 *  exit (Ctrl+C / SIGTERM / normal return), and wait for it to finish.
 *  Without the hook the child becomes an orphan when the JVM is killed. */
private def runNodeAndWait(cmd: Seq[String], cwd: Option[os.Path]): Unit =
  val pb = new ProcessBuilder(cmd*)
  pb.inheritIO()
  cwd.foreach(p => pb.directory(p.toIO))
  val proc = pb.start()
  val hook = new Thread(() => proc.destroy())
  Runtime.getRuntime.addShutdownHook(hook)
  try
    val exitCode = proc.waitFor()
    Runtime.getRuntime.removeShutdownHook(hook)
    if exitCode != 0 then System.exit(exitCode)
  catch
    case _: InterruptedException =>
      proc.destroy()
      System.exit(1)

def runJsCommand(args: List[String]): Unit =
  if args.isEmpty then
    System.err.println("Usage: ssc run-js [--frontend <custom|react|solid|vue>] <file.ssc>")
    System.exit(1)
  var jsFrontendFlag: Option[String] = None
  var jsFileArg:      Option[String] = None
  val jsIt = args.iterator
  while jsIt.hasNext do
    jsIt.next() match
      case "--frontend" if jsIt.hasNext =>
        val name = jsIt.next()
        if !browserFrontendNames(name) then
          System.err.println(s"run-js: unknown --frontend '$name', valid: ${browserFrontendNames.mkString(", ")}")
          System.exit(1)
        jsFrontendFlag = Some(name)
      case f => jsFileArg = Some(f)
  val file = jsFileArg.getOrElse {
    System.err.println("Usage: ssc run-js [--frontend <custom|react|solid|vue>] <file.ssc>")
    System.exit(1); ""
  }
  // Node.js execution doesn't use FrontendFrameworks (browser-side concern),
  // but we accept the flag for consistency.
  jsFrontendFlag.foreach(applyFrontendBackend)
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    System.err.println(s"Error: File not found: $file"); System.exit(1)
  val nodeAvailable = scala.util.Try {
    os.proc("node", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
  }.getOrElse(false)
  if !nodeAvailable then
    System.err.println("run-js: node not found on PATH"); System.exit(1)
  // Detect capabilities to build the runtime preamble, then compile user code.
  val module  = Parser.parse(os.read(path))
  val baseDir = Some(path / os.up)
  val caps    = JsGen.detectCapabilities(module, baseDir)
  val runtime = JsGen.generateRuntime(caps)
  val result  = compileViaBackend("js", path)
  val effectiveFrontendName: Option[String] =
    jsFrontendFlag
      .orElse(loadSidecarConfig(path).flatMap(_.get("frontend").flatMap(_.getString)).filter(validFrontendNames))
      .orElse(module.manifest.flatMap(_.frontendFramework))
  result match
    case CompileResult.Failed(diags) =>
      diags.foreach(d => System.err.println(s"[error] $d")); System.exit(1)
    case CompileResult.TextOutput(userCode, _, sources) =>
      val frontendInit = effectiveFrontendName.fold("")(n => s"_ssc_frontend_name = '${n.replace("'", "\\'")}'; // injected by ssc\n")
      val bundle  = runtime + "\n" + frontendInit + userCode
      val pkgJson = sources.collectFirst { case scalascript.backend.spi.SourceArtifact("package.json", c) => c }
      pkgJson match
        case None =>
          // No npm deps — write single temp file and run directly.
          val tmp = os.temp(bundle, suffix = ".cjs", deleteOnExit = true)
          runNodeAndWait(Seq("node", tmp.toString), cwd = None)
        case Some(pkg) =>
          // SQL deps present — set up a temp work dir, npm install, then run.
          val npmAvailable = scala.util.Try {
            os.proc("npm", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
          }.getOrElse(false)
          if !npmAvailable then
            System.err.println("run-js: npm not found on PATH (required for sql blocks)"); System.exit(1)
          val workDir = os.temp.dir(deleteOnExit = true)
          os.write(workDir / "main.cjs", bundle)
          os.write(workDir / "package.json", pkg)
          val inst = os.proc("npm", "install", "--no-audit", "--no-fund", "--silent")
            .call(cwd = workDir, check = false, stdout = os.Pipe, stderr = os.Pipe)
          if inst.exitCode != 0 then
            System.err.println(s"run-js: npm install failed:\n${inst.out.text()}${inst.err.text()}")
            System.exit(1)
          runNodeAndWait(Seq("node", "main.cjs"), cwd = Some(workDir))
    case other =>
      System.err.println(s"run-js: unexpected compile result ${other.getClass.getSimpleName}"); System.exit(1)

/** `ssc compile-jvm <file.ssc> [-o <file.scjvm>] [--iface-dir <dir>]`
 *
 *  Parses, normalises, and (optionally) type-checks the `.ssc` file against
 *  pre-compiled `.scim` interfaces from `--iface-dir`.  Runs the JVM backend's
 *  source codegen (`JvmGen.generate`) on this SINGLE module — not its
 *  transitive deps — and writes the emitted Scala 3 source as a `.scjvm`
 *  JSON artifact (ABI envelope + source hash + scalaSource + imports).
 *
 *  `ssc link --backend jvm` later concatenates every `.scjvm`'s `scalaSource`
 *  in dep order to produce the combined source for scala-cli / scalac,
 *  bypassing per-link re-codegen for unchanged modules.
 *
 *  If `-o` is not specified the output is written to `<file>.scjvm` in the
 *  same directory as the source.  Pass `-` as the output path to print to
 *  stdout instead.
 *
 *  v2.0 — JVM incremental codegen cache.
 */
def compileJvmCommand(args: List[String]): Unit =
  var outputArg:    Option[String]  = None
  var ifaceDir:     Option[os.Path] = None
  var artifactDir:  Option[os.Path] = None
  var noAutoDeps:   Boolean         = false
  var bytecode:     Boolean         = false
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => outputArg = Some(it.next())
      case "--iface-dir" | "-I" if it.hasNext =>
        ifaceDir = Some(os.Path(it.next(), os.pwd))
      case "--artifact-dir" if it.hasNext =>
        artifactDir = Some(os.Path(it.next(), os.pwd))
      case "--no-auto-deps" => noAutoDeps = true
      case "--bytecode"     => bytecode = true
      case f => files += f

  if files.isEmpty then
    System.err.println("Usage: ssc compile-jvm <file.ssc> [-o <file.scjvm>] [--iface-dir <dir>] [--artifact-dir <dir>] [--no-auto-deps] [--bytecode]")
    System.exit(1)

  // `--bytecode` is opt-in.  When requested, scala-cli must be on PATH —
  // fail loudly rather than silently downgrade to source-only.
  if bytecode && !JvmBytecode.scalaCliAvailable then
    System.err.println(s"compile-jvm: ${JvmBytecode.scalaCliMissingMessage}")
    System.exit(1)

  for file <- files.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"Error: File not found: $file"); System.exit(1)
    try
      // ── Auto-resolve transitive local-path imports ────────────────────
      //
      // Walk `Content.Import` edges from the target, parse each, build a
      // DAG, topo-sort, and recursively compile every dep that's stale
      // (missing or SHA-256-mismatched `.scim` / `.scjvm`).  Dependency
      // artifacts are written to the artifact-dir (defaults to
      // `<target-dir>/.ssc-artifacts/`).  `--no-auto-deps` reproduces
      // the pre-v2.0 behaviour: don't touch deps; rely on `--iface-dir`.
      val effectiveArtifactDir: os.Path =
        artifactDir.getOrElse:
          ifaceDir.getOrElse(AutoResolve.defaultArtifactDir(path))

      val autoResolvedIfaceDir: Option[os.Path] =
        if noAutoDeps then ifaceDir
        else
          val resolution = AutoResolve.resolve(path)
          if resolution.cycles.nonEmpty then
            System.err.println("compile-jvm: circular dependencies detected:")
            resolution.cycles.foreach { cycle =>
              System.err.println("  " + cycle.map(_.last).mkString(" → "))
            }
            System.exit(1)
          // The target itself appears in `orderedNodes`; only deps
          // need pre-compilation.
          val deps = resolution.orderedNodes.filter(_.path != path)
          if deps.nonEmpty then os.makeDir.all(effectiveArtifactDir)
          for dep <- deps do
            val baseName = dep.path.last.stripSuffix(".ssc")
            val scimPath = effectiveArtifactDir / (baseName + ".scim")
            val scjvmPath = effectiveArtifactDir / (baseName + ".scjvm")
            // When --bytecode is set, a dep is fresh only when its .scjvm
            // also has a non-empty classBundle.  Otherwise auto-resolve
            // wouldn't see that an older source-only artifact must be
            // re-compiled with bytecode this round.
            val scimFresh = AutoResolve.isScimFresh(dep, effectiveArtifactDir)
            val scjvmFresh =
              AutoResolve.isScjvmFresh(dep, effectiveArtifactDir) &&
              (!bytecode || scjvmHasClassBundle(scjvmPath))
            val fresh = scimFresh && scjvmFresh
            if !fresh then
              compileJvmDepInto(dep, effectiveArtifactDir, scimPath, scjvmPath, bytecode)
          // Either return the explicit --iface-dir (so its `.scim`
          // files participate too) or our newly populated artifact dir.
          // When the user gave both flags, --iface-dir wins for
          // resolution but deps still land in --artifact-dir.
          Some(ifaceDir.getOrElse(effectiveArtifactDir))

      val sourceBytes = os.read.bytes(path)
      val src         = new String(sourceBytes, "UTF-8")
      val module      = Parser.parse(src)

      // Bail out early with a structured diagnostic if any scalascript block
      // failed to parse.  Without this, the typer would emit the opaque
      // "Failed to parse scalascript code block" — useless for bisecting.
      if reportCodeBlockParseErrors(module, file) then
        System.exit(1)

      // v2.0 Phase 5 — discover and stage pre-compiled artifacts shipped
      // alongside any `dep:` imports' cached `.ssc`.  Each `.sscpkg` built
      // with `--with-artifacts` carries a `.ssc-artifacts/<basename>.<ext>`
      // sibling; when found, copy it into the consumer's artifact dir so
      // the typer + linker pick it up without re-parsing the dep source.
      // No-op when no dep: imports or no artifacts ship — source-parse
      // path still works.
      val precompiledDeps =
        stagePrecompiledDepArtifacts(module, path, effectiveArtifactDir, List("scim", "scjvm"))
      if precompiledDeps.nonEmpty then
        val summary = precompiledDeps.toList.sortBy(_._1).map((e, n) => s"$n $e").mkString(", ")
        println(s"compile-jvm: staged pre-compiled dep artifacts ($summary)")

      // Pre-load interfaces from the iface dir (auto-resolved or
      // user-supplied) for type-checking the target.
      val interfaces: Map[String, scalascript.ir.ModuleInterface] =
        autoResolvedIfaceDir match
          case None => Map.empty
          case Some(dir) =>
            if !os.isDir(dir) then
              // No interfaces to load (e.g. the target has no deps and we
              // never created the dir).  Fall through to an empty map.
              Map.empty
            else
              os.list(dir).filter(_.ext == "scim").flatMap { p =>
                ArtifactIO.readInterfaceFile(p) match
                  case Right(iface) =>
                    val alias = p.last.stripSuffix(".scim")
                    List(alias -> iface)
                  case Left(err) =>
                    System.err.println(s"  [warn] skipping ${p.last}: $err")
                    Nil
              }.toMap

      // Type-check (optionally against pre-compiled interfaces).  Errors are
      // surfaced as a non-zero exit code so the build orchestrator can stop
      // before producing a stale `.scjvm`.
      val typed =
        if interfaces.isEmpty then Typer.typeCheck(module)
        else Typer.typeCheckWithInterfaces(module, interfaces)
      if typed.hasErrors then
        typed.errors.foreach(e => System.err.println(s"  Error: ${e.msg}"))
        System.exit(1)

      // Run the JVM backend codegen on THIS module.
      //
      //  - Source-only mode (no --bytecode): emit the legacy full-source
      //    (preamble + user code) for the textual link path.
      //  - Bytecode mode (--bytecode): emit user code ONLY with an
      //    `import _ssc_runtime.*` prefix, so the shared runtime
      //    classBundle from `_runtime.scjvm-runtime` covers the helpers.
      val baseDir     = Some(path / os.up)
      // In bytecode mode we also need the generated→original .ssc line
      // map so `link --source-map` can build a JSR-45 SMAP.  The
      // source-only path doesn't need it (no `.class` files are produced
      // here for SMAP injection at link time).
      val (scalaSource, userLineMap): (String, Map[Int, Int]) =
        if bytecode then JvmGen.generateUserOnlyWithLineMap(module, baseDir)
        else            (JvmGen.generate(module, baseDir), Map.empty[Int, Int])
      val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
      val moduleName  = module.manifest.flatMap(_.name)
      val sourceHash  = InterfaceExtractor.sha256(sourceBytes)

      // Detect this module's capability set up front — needed in bytecode
      // mode to decide whether the shared runtime artifact must be
      // regenerated.
      val moduleCaps: Set[String] =
        if !bytecode then Set.empty
        else JvmGen.detectCapabilities(module, baseDir).map(JvmGen.Capability.encode)

      // Best-effort import discovery: collect `Content.Import` paths and
      // front-matter `dependencies:` aliases.  Used by the linker as a hint
      // for cross-module FQN resolution.  Not load-bearing for the textual
      // MVP — the produced source already contains every name it needs.
      val rawImports = collectImports(module.sections)
      val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
      val imports    = (rawImports ++ depAliases).distinct.toList

      val moduleId = moduleName.getOrElse(path.last.stripSuffix(".ssc"))

      // ── Optional bytecode bundle ─────────────────────────────────────
      //
      // When --bytecode is set, drive scala-cli on the user-only Scala
      // source produced above.  Two new responsibilities versus pre-Phase-2:
      //
      //  1. Ensure `_runtime.scjvm-runtime` covers the UNION of this
      //     module's capabilities and any already-present deps'
      //     capabilities — regenerating when missing or out-of-date.
      //  2. Pass the runtime classBundle as part of scala-cli's classpath
      //     so the wildcard-imported helpers resolve at compile time.
      val classBundleOpt: Option[String] =
        if !bytecode then None
        else
          // Compute artifact dir for the runtime.  The `.scjvm-runtime`
          // must sit next to the `.scjvm` files so `linkJvmFromBytecode`
          // can find it.  Pick the directory of `-o <foo.scjvm>` when
          // provided, otherwise fall back to the resolved iface/artifact
          // dir, otherwise the source's parent dir.
          val rtDir: os.Path = outputArg match
            case Some(out) if out.endsWith(".scjvm") =>
              val outAbs = os.Path(out, os.pwd)
              os.makeDir.all(outAbs / os.up)
              outAbs / os.up
            case _ =>
              autoResolvedIfaceDir.getOrElse(path / os.up)
          val depCaps   = unionDepCapabilities(rtDir)
          val unionCaps = depCaps ++ moduleCaps
          try
            ensureRuntimeArtifact(rtDir, unionCaps)
          catch case e: Throwable =>
            System.err.println(s"compile-jvm --bytecode: ${e.getMessage}")
            System.exit(1)

          val depClasspathDir = Some(extractDepBundlesForCompile(rtDir))
          try
            JvmBytecode.compileAndPack(scalaSource, depClasspathDir.toList, scriptName = moduleId) match
              case Right(b64) => Some(b64)
              case Left(err)  =>
                System.err.println(s"compile-jvm --bytecode: $err")
                System.exit(1)
                None // unreachable; appeases the type checker
          finally
            depClasspathDir.foreach(d => scala.util.Try(os.remove.all(d)))

      // Stringify the line map for upickle (Map[String, Int] round-trips
      // cleanly; Map[Int, Int] would land as a 2-element array).
      val lineMapStr: Map[String, Int] = userLineMap.map { (g, o) => g.toString -> o }
      val json = JvmArtifactIO.writeJvm(
        moduleId, pkg, moduleName, sourceHash, scalaSource, imports,
        classBundleOpt, moduleCaps.toList.sorted,
        sectionHashes = Map.empty,
        lineMap       = lineMapStr)
      outputArg match
        case Some("-") => println(json)
        case Some(out) =>
          val outPath = os.Path(out, os.pwd)
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"JVM artifact written to $outPath" +
            (if classBundleOpt.isDefined then s" (with classBundle: ${classBundleOpt.get.length} b64 chars)" else ""))
        case None =>
          val outPath = path / os.up / (path.last.stripSuffix(".ssc") + ".scjvm")
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"JVM artifact written to ${outPath.relativeTo(os.pwd)}" +
            (if classBundleOpt.isDefined then s" (with classBundle: ${classBundleOpt.get.length} b64 chars)" else ""))
    catch case e: Exception =>
      System.err.println(s"compile-jvm error: ${e.getMessage}")
      System.exit(1)

/** `ssc compile-runtime --capabilities <comma-sep> [--artifact-dir <dir>]`
 *
 *  v2.0 Phase 2 — explicit invocation of the shared-runtime compile step.
 *  Generates the runtime source for the requested capability set via
 *  `JvmGen.generateRuntime`, drives scala-cli on it, and persists the
 *  resulting classBundle as `<artifact-dir>/_runtime.scjvm-runtime`.
 *
 *  Mostly useful for testing — `compile-jvm --bytecode` already invokes
 *  the same code path implicitly when a module's capability set isn't
 *  covered by the existing runtime.
 *
 *  Capability names (comma-separated, no spaces):
 *    effects | mutual-tco | reactive | serve | mcp | dataset | json
 *
 *  Special value `all` requests every known capability (useful when
 *  pre-baking a "kitchen-sink" runtime for distribution). */
def compileRuntimeCommand(args: List[String]): Unit =
  var capsArg:     Option[String]  = None
  var artifactDir: Option[os.Path] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--capabilities" if it.hasNext   => capsArg     = Some(it.next())
      case "--artifact-dir" if it.hasNext   => artifactDir = Some(os.Path(it.next(), os.pwd))
      case other =>
        System.err.println(s"compile-runtime: unrecognised argument '$other'")
        System.exit(1)

  // `--backend <id>` is a GLOBAL flag stripped by `GlobalFlags.parse`
  // before the command handler sees its args; read it from the active
  // flags snapshot instead.  Defaults to "jvm" to preserve the existing
  // pre-v2.0-Phase-2 CLI shape.
  val backend = ActiveFlags.current.backend.getOrElse("jvm")

  if backend != "jvm" && backend != "js" then
    System.err.println(s"compile-runtime: --backend must be 'jvm' or 'js'; got '$backend'")
    System.exit(1)

  // scala-cli is only needed for the JVM backend — JS is source-only.
  if backend == "jvm" && !JvmBytecode.scalaCliAvailable then
    System.err.println(s"compile-runtime: ${JvmBytecode.scalaCliMissingMessage}")
    System.exit(1)

  val caps: Set[String] = capsArg.map(_.trim) match
    case None | Some("") =>
      System.err.println("compile-runtime: --capabilities is required " +
        "(or pass --capabilities all for the full preamble)")
      System.exit(1)
      Set.empty
    case Some("all") =>
      if backend == "jvm" then JvmGen.Capability.all.map(JvmGen.Capability.encode)
      else                     JsGen.Capability.all.map(JsGen.Capability.encode)
    case Some(csv) =>
      csv.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet

  val dir = artifactDir.getOrElse(os.pwd)
  try
    val path =
      if backend == "jvm" then ensureRuntimeArtifact(dir, caps)
      else                     ensureJsRuntimeArtifact(dir, caps)
    println(s"Shared $backend runtime written to $path " +
      s"(capabilities: ${caps.toList.sorted.mkString(", ")})")
  catch case e: Throwable =>
    System.err.println(s"compile-runtime: ${e.getMessage}")
    System.exit(1)

/** Compile a single dependency module into `.scim` + `.scjvm` artifacts
 *  living in `artifactDir`.  Used by `compile-jvm` auto-resolution to
 *  pre-build every transitive dep before the target.
 *
 *  Type-checks the dep against the interfaces already present in
 *  `artifactDir` (so chains like `c → b → a` see `a`'s interface when
 *  type-checking `b`).
 *
 *  Throws on type-check failures or codegen exceptions; the caller
 *  catches and surfaces a non-zero exit code. */
private def compileJvmDepInto(
    dep:         AutoResolve.Node,
    artifactDir: os.Path,
    scimPath:    os.Path,
    scjvmPath:   os.Path,
    bytecode:    Boolean = false
): Unit =
  val module     = dep.module
  // Surface a structured parse-error diagnostic for any failing block in
  // the dep BEFORE we try to type-check / codegen it, then fail hard.
  if reportCodeBlockParseErrors(module, dep.path.toString) then
    throw new RuntimeException(s"parse error in dep ${dep.path.last} (see diagnostic above)")
  // Build the interfaces map from `.scim` files already in the artifact
  // dir.  Deeper deps will have been compiled first (topo order) so
  // their interfaces are available when type-checking this one.
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    if os.exists(artifactDir) && os.isDir(artifactDir) then
      os.list(artifactDir).filter(_.ext == "scim").flatMap { p =>
        ArtifactIO.readInterfaceFile(p) match
          case Right(iface) => List(p.last.stripSuffix(".scim") -> iface)
          case Left(_)      => Nil
      }.toMap
    else Map.empty
  val typed =
    if interfaces.isEmpty then Typer.typeCheck(module)
    else Typer.typeCheckWithInterfaces(module, interfaces)
  if typed.hasErrors then
    val msgs = typed.errors.map(_.msg).mkString("; ")
    throw new RuntimeException(s"type errors in dep ${dep.path.last}: $msgs")

  // .scim first so siblings parsed in topo order pick it up.
  val iface = InterfaceExtractor.extract(module, dep.sourceBytes)
  ArtifactIO.writeInterfaceFile(iface, scimPath)

  // Now the .scjvm.  Source-only deps still get the full preamble; in
  // bytecode mode the dep emits user-code-only with `import _ssc_runtime.*`
  // and the shared `_runtime.scjvm-runtime` covers the helpers.
  val baseDir     = Some(dep.path / os.up)
  // Bytecode mode also produces the gen→orig .ssc line map for SMAP
  // injection at link time (see compileJvmCommand for context).
  val (scalaSource, userLineMap): (String, Map[Int, Int]) =
    if bytecode then JvmGen.generateUserOnlyWithLineMap(module, baseDir)
    else            (JvmGen.generate(module, baseDir), Map.empty[Int, Int])
  val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
  val moduleName  = module.manifest.flatMap(_.name)
  val sourceHash  = InterfaceExtractor.sha256(dep.sourceBytes)
  val baseName    = dep.path.last.stripSuffix(".ssc")
  val rawImports  = collectImports(module.sections)
  val depAliases  = module.manifest.toList.flatMap(_.dependencies.keys)
  val imports     = (rawImports ++ depAliases).distinct.toList
  val moduleId    = moduleName.getOrElse(baseName)

  val moduleCaps: Set[String] =
    if !bytecode then Set.empty
    else JvmGen.detectCapabilities(module, baseDir).map(JvmGen.Capability.encode)

  // When --bytecode is set, also produce a classBundle for this dep so
  // the linker has a real .class artifact to pack.  Wire previously-built
  // deps' classBundles + the shared `_runtime.scjvm-runtime` into the
  // classpath for this scala-cli invocation (transitive deps were
  // compiled first in topo order; runtime is regenerated if needed).
  val classBundleOpt: Option[String] =
    if !bytecode then None
    else
      val depCaps   = unionDepCapabilities(artifactDir)
      val unionCaps = depCaps ++ moduleCaps
      ensureRuntimeArtifact(artifactDir, unionCaps)
      val depCpDir = extractDepBundlesForCompile(artifactDir)
      try
        JvmBytecode.compileAndPack(scalaSource, List(depCpDir), scriptName = moduleId) match
          case Right(b64) => Some(b64)
          case Left(err)  =>
            throw new RuntimeException(s"--bytecode dep ${dep.path.last}: $err")
      finally
        scala.util.Try(os.remove.all(depCpDir))

  val lineMapStr: Map[String, Int] = userLineMap.map { (g, o) => g.toString -> o }
  JvmArtifactIO.writeJvmFile(
    moduleId, pkg, moduleName, sourceHash, scalaSource, imports, scjvmPath,
    classBundleOpt, moduleCaps.toList.sorted,
    sectionHashes = Map.empty, lineMap = lineMapStr)

/** True when `scjvmPath` exists and its `classBundle` is non-empty.
 *  Used by `compile-jvm --bytecode` to detect that an existing source-only
 *  `.scjvm` artifact must be recompiled with a class bundle this round. */
private def scjvmHasClassBundle(scjvmPath: os.Path): Boolean =
  if !os.exists(scjvmPath) then false
  else JvmArtifactIO.readJvmFile(scjvmPath) match
    case Right(a) => a.classBundle.exists(_.nonEmpty)
    case Left(_)  => false

/** Walk `artifactDir` for `.scjvm` files, extract every non-empty
 *  `classBundle` into a fresh temp dir, and return that dir.
 *
 *  The result is suitable for passing to scala-cli as `--jar <dir>` so
 *  cross-module references in the source being compiled resolve at compile
 *  time.  Caller is responsible for deleting the temp dir.
 *
 *  Returns the temp dir even when no bundles were found — callers can pass
 *  an empty dir to scala-cli without harm. */
private def extractDepBundlesForCompile(artifactDir: os.Path): os.Path =
  val dest = os.temp.dir(prefix = "ssc-bytecode-deps-")
  if os.isDir(artifactDir) then
    for p <- os.list(artifactDir).filter(_.ext == "scjvm") do
      JvmArtifactIO.readJvmFile(p) match
        case Right(a) =>
          a.classBundle.foreach(b =>
            if b.nonEmpty then JvmBytecode.extractBundleTo(b, dest)
          )
        case Left(_) => () // skip malformed dep artifacts silently
    // v2.0 Phase 2 — also pull the shared `_runtime.scjvm-runtime` classBundle
    // into the dep classpath so user code that references `_show` / `_handle`
    // / `route` (via wildcard import of `_ssc_runtime`) resolves at compile
    // time.  Missing or malformed runtime artifacts are skipped silently —
    // pre-Phase-2 .scjvm files ship the full preamble in their own bundles.
    val runtimePath = artifactDir / "_runtime.scjvm-runtime"
    if os.exists(runtimePath) then
      JvmArtifactIO.readRuntimeFile(runtimePath) match
        case Right(rt) =>
          if rt.classBundle.nonEmpty then
            JvmBytecode.extractBundleTo(rt.classBundle, dest)
        case Left(_) => ()
  dest

/** v2.0 Phase 2 — read every `.scjvm` in `artifactDir` and return the
 *  union of their `capabilities` fields.  Used by `compile-jvm --bytecode`
 *  to compute whether the existing `_runtime.scjvm-runtime` covers the
 *  capability set the current build needs. */
private def unionDepCapabilities(artifactDir: os.Path): Set[String] =
  if !os.isDir(artifactDir) then Set.empty
  else
    val acc = scala.collection.mutable.Set.empty[String]
    for p <- os.list(artifactDir).filter(_.ext == "scjvm") do
      JvmArtifactIO.readJvmFile(p) match
        case Right(a) => acc ++= a.capabilities
        case Left(_)  => ()
    acc.toSet

/** v2.0 Phase 2 — ensure the shared `_runtime.scjvm-runtime` in
 *  `artifactDir` covers `requiredCapabilities`.  Regenerates it via
 *  `JvmGen.generateRuntime` + `JvmBytecode.compileRuntimeAndPack` when
 *  missing or when the existing runtime's capability set is a strict
 *  subset of the required set.  No-op when the existing runtime already
 *  covers (≥) the required capabilities.
 *
 *  Throws on scala-cli compile failure; caller catches and surfaces a
 *  non-zero exit code.
 *
 *  Returns the path of the (possibly freshly-written) runtime artifact. */
private def ensureRuntimeArtifact(
    artifactDir:          os.Path,
    requiredCapabilities: Set[String]
): os.Path =
  os.makeDir.all(artifactDir)
  val runtimePath = artifactDir / "_runtime.scjvm-runtime"
  val existing: Option[scalascript.ir.ModuleJvmRuntimeArtifact] =
    if !os.exists(runtimePath) then None
    else JvmArtifactIO.readRuntimeFile(runtimePath).toOption

  // Decode required strings to capabilities.  Unknown strings are
  // surfaced as a hard error — we'd rather fail loudly than silently
  // emit a runtime that's missing a block the module assumes is present.
  val requiredCaps: Set[scalascript.codegen.JvmGen.Capability] =
    requiredCapabilities.map { s =>
      scalascript.codegen.JvmGen.Capability.decode(s).getOrElse(
        throw new RuntimeException(s"Unknown JVM capability: '$s' " +
          "(.scjvm written by a newer compiler version?)")
      )
    }

  val needsRegen = existing match
    case None      => true
    case Some(art) =>
      val have = art.capabilities.toSet
      // Regenerate when the required set is not a subset of what we
      // already have.  Going FROM a superset to a subset is a no-op
      // (the shared runtime stays valid; its classes are still on the
      // classpath, and any unused ones are unused without harm).
      !requiredCapabilities.subsetOf(have)

  if !needsRegen then runtimePath
  else
    val runtimeSource = scalascript.codegen.JvmGen.generateRuntime(requiredCaps)
    val sourceHash    = scalascript.artifact.InterfaceExtractor.sha256(
                          runtimeSource.getBytes("UTF-8"))
    JvmBytecode.compileRuntimeAndPack(runtimeSource) match
      case Right(b64) =>
        JvmArtifactIO.writeRuntimeFile(
          capabilities = requiredCapabilities.toList.sorted,
          sourceHash   = sourceHash,
          classBundle  = b64,
          path         = runtimePath
        )
        runtimePath
      case Left(err) =>
        throw new RuntimeException(s"--bytecode: shared runtime compile failed:\n$err")

/** v2.0 Phase 2 (JS) — read every `.scjs` in `artifactDir` and return the
 *  union of their `capabilities` fields.  Used by `compile-js` to compute
 *  whether the existing `_runtime.scjs-runtime` covers the capability set
 *  the current build needs. */
private def unionDepCapabilitiesJs(artifactDir: os.Path): Set[String] =
  if !os.isDir(artifactDir) then Set.empty
  else
    val acc = scala.collection.mutable.Set.empty[String]
    for p <- os.list(artifactDir).filter(_.ext == "scjs") do
      JsArtifactIO.readJsFile(p) match
        case Right(a) => acc ++= a.capabilities
        case Left(_)  => ()
    acc.toSet

/** v2.0 Phase 2 (JS) — ensure the shared `_runtime.scjs-runtime` in
 *  `artifactDir` covers `requiredCapabilities`.  Regenerates it via
 *  `JsGen.generateRuntime` when missing or when the existing runtime's
 *  capability set is a strict subset of the required set.  No-op when
 *  the existing runtime already covers (≥) the required capabilities.
 *
 *  Returns the path of the (possibly freshly-written) runtime artifact. */
private def ensureJsRuntimeArtifact(
    artifactDir:          os.Path,
    requiredCapabilities: Set[String]
): os.Path =
  os.makeDir.all(artifactDir)
  val runtimePath = artifactDir / "_runtime.scjs-runtime"
  val existing: Option[scalascript.ir.ModuleJsRuntimeArtifact] =
    if !os.exists(runtimePath) then None
    else JsArtifactIO.readRuntimeFile(runtimePath).toOption

  // Decode required strings to capabilities.  Unknown strings are a hard
  // error — better to fail loudly than silently emit a runtime missing a
  // block the module assumes is present.
  val requiredCaps: Set[scalascript.codegen.JsGen.Capability] =
    requiredCapabilities.map { s =>
      scalascript.codegen.JsGen.Capability.decode(s).getOrElse(
        throw new RuntimeException(s"Unknown JS capability: '$s' " +
          "(.scjs written by a newer compiler version?)")
      )
    }

  val needsRegen = existing match
    case None      => true
    case Some(art) =>
      val have = art.capabilities.toSet
      !requiredCapabilities.subsetOf(have)

  if !needsRegen then runtimePath
  else
    val runtimeSource = scalascript.codegen.JsGen.generateRuntime(requiredCaps)
    val sourceHash    = scalascript.artifact.InterfaceExtractor.sha256(
                          runtimeSource.getBytes("UTF-8"))
    JsArtifactIO.writeRuntimeFile(
      capabilities = requiredCapabilities.toList.sorted,
      sourceHash   = sourceHash,
      jsSource     = runtimeSource,
      path         = runtimePath
    )
    runtimePath

// ─────────────────────────────────────────────────────────────────────────────
// ssc compile-js  —  v2.0 JS-backend incremental codegen cache
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc compile-js <file.ssc> [-o <file.scjs>] [--iface-dir <dir>]`
 *
 *  Parses, normalises, and (optionally) type-checks the `.ssc` file against
 *  pre-compiled `.scim` interfaces from `--iface-dir`.  Runs the JS backend's
 *  source codegen (`JsGen.generate`) on this SINGLE module — not its
 *  transitive deps — and writes the emitted JS source as a `.scjs` JSON
 *  artifact (ABI envelope + source hash + jsSource + imports).
 *
 *  `ssc link --backend js` later concatenates every `.scjs`'s `jsSource` in
 *  dep order to produce the combined source for `node` / browser script-tag
 *  inclusion, bypassing per-link re-codegen for unchanged modules.
 *
 *  The emitted `jsSource` is self-contained: it includes the JS runtime
 *  preamble (`JsRuntime` + `JsRuntimeAsync` + effects + dataset) followed by
 *  the user-code JS from `JsGen.generate`.  Multiple `.scjs` files share the
 *  preamble verbatim, so the linker uses a longest-common-prefix dedup pass
 *  (identical to the JVM linker's runtime-prefix strip) before emitting the
 *  combined output.
 *
 *  If `-o` is not specified the output is written to `<file>.scjs` in the
 *  same directory as the source.  Pass `-` as the output path to print to
 *  stdout instead.
 *
 *  v2.0 — JS incremental codegen cache.
 */
def compileJsCommand(args: List[String]): Unit =
  var outputArg:   Option[String]  = None
  var ifaceDir:    Option[os.Path] = None
  var artifactDir: Option[os.Path] = None
  var noAutoDeps:  Boolean         = false
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext => outputArg = Some(it.next())
      case "--iface-dir" | "-I" if it.hasNext =>
        ifaceDir = Some(os.Path(it.next(), os.pwd))
      case "--artifact-dir" if it.hasNext =>
        artifactDir = Some(os.Path(it.next(), os.pwd))
      case "--no-auto-deps" => noAutoDeps = true
      case f => files += f

  if files.isEmpty then
    System.err.println("Usage: ssc compile-js <file.ssc> [-o <file.scjs>] [--iface-dir <dir>] [--artifact-dir <dir>] [--no-auto-deps]")
    System.exit(1)

  for file <- files.toList do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"Error: File not found: $file"); System.exit(1)
    try
      // ── Auto-resolve transitive local-path imports ────────────────────
      // (See `compile-jvm` for the design.  Same shape, `.scjs` instead
      // of `.scjvm` for the backend cache.)
      val effectiveArtifactDir: os.Path =
        artifactDir.getOrElse:
          ifaceDir.getOrElse(AutoResolve.defaultArtifactDir(path))

      val autoResolvedIfaceDir: Option[os.Path] =
        if noAutoDeps then ifaceDir
        else
          val resolution = AutoResolve.resolve(path)
          if resolution.cycles.nonEmpty then
            System.err.println("compile-js: circular dependencies detected:")
            resolution.cycles.foreach { cycle =>
              System.err.println("  " + cycle.map(_.last).mkString(" → "))
            }
            System.exit(1)
          val deps = resolution.orderedNodes.filter(_.path != path)
          if deps.nonEmpty then os.makeDir.all(effectiveArtifactDir)
          for dep <- deps do
            val baseName = dep.path.last.stripSuffix(".ssc")
            val scimPath = effectiveArtifactDir / (baseName + ".scim")
            val scjsPath = effectiveArtifactDir / (baseName + ".scjs")
            val fresh = AutoResolve.isScimFresh(dep, effectiveArtifactDir) &&
                        AutoResolve.isScjsFresh(dep, effectiveArtifactDir)
            if !fresh then
              compileJsDepInto(dep, effectiveArtifactDir, scimPath, scjsPath)
          Some(ifaceDir.getOrElse(effectiveArtifactDir))

      val sourceBytes = os.read.bytes(path)
      val src         = new String(sourceBytes, "UTF-8")
      val module      = Parser.parse(src)

      // Bail out early with a structured diagnostic if any scalascript block
      // failed to parse — same rationale as compile-jvm.
      if reportCodeBlockParseErrors(module, file) then
        System.exit(1)

      // v2.0 Phase 5 — stage pre-compiled artifacts shipped alongside any
      // `dep:` imports' cached `.ssc`.  See `compileJvmCommand` for the
      // detailed rationale.  Discovered `.scim` + `.scjs` end up in the
      // effective artifact dir; the typer + linker pick them up directly.
      val precompiledDeps =
        stagePrecompiledDepArtifacts(module, path, effectiveArtifactDir, List("scim", "scjs"))
      if precompiledDeps.nonEmpty then
        val summary = precompiledDeps.toList.sortBy(_._1).map((e, n) => s"$n $e").mkString(", ")
        println(s"compile-js: staged pre-compiled dep artifacts ($summary)")

      // Pre-load interfaces from the iface dir (auto-resolved or
      // user-supplied) for type-checking the target.
      val interfaces: Map[String, scalascript.ir.ModuleInterface] =
        autoResolvedIfaceDir match
          case None => Map.empty
          case Some(dir) =>
            if !os.isDir(dir) then Map.empty
            else
              os.list(dir).filter(_.ext == "scim").flatMap { p =>
                ArtifactIO.readInterfaceFile(p) match
                  case Right(iface) =>
                    val alias = p.last.stripSuffix(".scim")
                    List(alias -> iface)
                  case Left(err) =>
                    System.err.println(s"  [warn] skipping ${p.last}: $err")
                    Nil
              }.toMap

      // Type-check (optionally against pre-compiled interfaces).  Errors are
      // surfaced as a non-zero exit code so the build orchestrator can stop
      // before producing a stale `.scjs`.
      val typed =
        if interfaces.isEmpty then Typer.typeCheck(module)
        else Typer.typeCheckWithInterfaces(module, interfaces)
      if typed.hasErrors then
        typed.errors.foreach(e => System.err.println(s"  Error: ${e.msg}"))
        System.exit(1)

      // Run the JS backend codegen on THIS module only (no merged dep code).
      // v2.0 Phase 2: emit user-code-only JS (no preamble).  The shared
      // runtime preamble is persisted once per artifact dir as
      // `_runtime.scjs-runtime` and concatenated at link time.
      val baseDir   = Some(path / os.up)
      val jsSource  = JsGen.generateUserOnly(module, baseDir)
      val pkg       = module.manifest.flatMap(_.pkg).getOrElse(Nil)
      val moduleName = module.manifest.flatMap(_.name)
      val sourceHash = InterfaceExtractor.sha256(sourceBytes)

      // Best-effort import discovery: collect `Content.Import` paths and
      // front-matter `dependencies:` aliases.  Used by the linker as a hint
      // for cross-module FQN resolution.  Not load-bearing for the textual
      // MVP — the produced source already contains every name it needs.
      val rawImports = collectImports(module.sections)
      val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
      val imports    = (rawImports ++ depAliases).distinct.toList

      val moduleId = moduleName.getOrElse(path.last.stripSuffix(".ssc"))

      // The `.scjs-runtime` lands in the SAME directory as the `.scjs`
      // it accompanies — the linker picks it up by scanning the artifact
      // dirs.  When the user passes `-o some/path/a.scjs`, the runtime
      // belongs in `some/path/`; otherwise the runtime sits next to the
      // source file (default `-o` resolves there).  Auto-resolved deps
      // landed in `effectiveArtifactDir` and the dep's `.scjs` files are
      // already there, so the runtime covers them too when that dir is
      // the eventual output dir.
      val runtimeDir: os.Path = outputArg match
        case Some("-")                              => effectiveArtifactDir
        case Some(out) if out.endsWith(".scjs")     =>
          val outPath = os.Path(out, os.pwd)
          outPath / os.up
        case Some(_)                                => effectiveArtifactDir
        case None                                   => path / os.up

      // v2.0 Phase 2 — detect capabilities for THIS module, then ensure
      // the shared runtime artifact in the artifact dir covers the union
      // across all `.scjs` files seen so far (existing + this module).
      val moduleCaps: Set[String] =
        JsGen.detectCapabilities(module, baseDir).map(JsGen.Capability.encode)

      val json = JsArtifactIO.writeJs(moduleId, pkg, moduleName, sourceHash, jsSource, imports, moduleCaps.toList.sorted)
      outputArg match
        case Some("-") => println(json)
        case Some(out) =>
          val outPath = os.Path(out, os.pwd)
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"JS artifact written to $outPath")
        case None =>
          val outPath = path / os.up / (path.last.stripSuffix(".ssc") + ".scjs")
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, json)
          println(s"JS artifact written to ${outPath.relativeTo(os.pwd)}")

      // Ensure the shared runtime AFTER writing this module's `.scjs` so
      // `unionDepCapabilitiesJs(runtimeDir)` sees this module's caps too.
      val depCaps   = unionDepCapabilitiesJs(runtimeDir) ++
                      unionDepCapabilitiesJs(effectiveArtifactDir)
      val unionCaps = depCaps ++ moduleCaps
      try ensureJsRuntimeArtifact(runtimeDir, unionCaps)
      catch case e: Throwable =>
        System.err.println(s"compile-js: shared runtime regeneration failed: ${e.getMessage}")
        System.exit(1)
    catch case e: Exception =>
      System.err.println(s"compile-js error: ${e.getMessage}")
      System.exit(1)

/** Compile a single dependency module into `.scim` + `.scjs` artifacts
 *  living in `artifactDir`.  Mirror of `compileJvmDepInto`. */
private def compileJsDepInto(
    dep:         AutoResolve.Node,
    artifactDir: os.Path,
    scimPath:    os.Path,
    scjsPath:    os.Path
): Unit =
  val module = dep.module
  if reportCodeBlockParseErrors(module, dep.path.toString) then
    throw new RuntimeException(s"parse error in dep ${dep.path.last} (see diagnostic above)")
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    if os.exists(artifactDir) && os.isDir(artifactDir) then
      os.list(artifactDir).filter(_.ext == "scim").flatMap { p =>
        ArtifactIO.readInterfaceFile(p) match
          case Right(iface) => List(p.last.stripSuffix(".scim") -> iface)
          case Left(_)      => Nil
      }.toMap
    else Map.empty
  val typed =
    if interfaces.isEmpty then Typer.typeCheck(module)
    else Typer.typeCheckWithInterfaces(module, interfaces)
  if typed.hasErrors then
    val msgs = typed.errors.map(_.msg).mkString("; ")
    throw new RuntimeException(s"type errors in dep ${dep.path.last}: $msgs")

  val iface = InterfaceExtractor.extract(module, dep.sourceBytes)
  ArtifactIO.writeInterfaceFile(iface, scimPath)

  val baseDir    = Some(dep.path / os.up)
  // v2.0 Phase 2: user-code-only emit; shared runtime ships separately.
  val jsSource   = JsGen.generateUserOnly(module, baseDir)
  val pkg        = module.manifest.flatMap(_.pkg).getOrElse(Nil)
  val moduleName = module.manifest.flatMap(_.name)
  val sourceHash = InterfaceExtractor.sha256(dep.sourceBytes)
  val baseName   = dep.path.last.stripSuffix(".ssc")
  val rawImports = collectImports(module.sections)
  val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
  val imports    = (rawImports ++ depAliases).distinct.toList
  val moduleId   = moduleName.getOrElse(baseName)
  val moduleCaps: Set[String] =
    JsGen.detectCapabilities(module, baseDir).map(JsGen.Capability.encode)
  // Top-level caller (`compileJsCommand`) regenerates the shared
  // `_runtime.scjs-runtime` once per build using the union of every
  // module's capabilities.  Here we only persist this dep's capability
  // list — the runtime ensure step happens after all deps + the target
  // have been compiled.
  JsArtifactIO.writeJsFile(moduleId, pkg, moduleName, sourceHash, jsSource, imports, scjsPath, moduleCaps.toList.sorted)

// ─────────────────────────────────────────────────────────────────────────────
// ssc deps <file.ssc>  —  show the resolved import graph (topo-sorted)
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc deps <file.ssc> [--graph]`
 *
 *  Walk the target's import closure via `AutoResolve.resolve` and print
 *  the topologically-sorted dependency list.  Useful for understanding
 *  what `compile-jvm` / `compile-js` will recursively compile, and for
 *  CI scripts that need to know the artifact set up front.
 *
 *  With `--graph`, prints a one-line edge per import (`from → to`)
 *  in addition to the sorted list.
 *
 *  v2.0 — dep-graph introspection. */
def depsCommand(args: List[String]): Unit =
  var graphMode = false
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  args.foreach {
    case "--graph" => graphMode = true
    case f         => files += f
  }
  if files.length != 1 then
    System.err.println("Usage: ssc deps <file.ssc> [--graph]")
    System.exit(1)

  val path = os.Path(files.head, os.pwd)
  if !os.exists(path) then
    System.err.println(s"deps: file not found: ${files.head}")
    System.exit(1)

  val resolution =
    try AutoResolve.resolve(path)
    catch case e: Exception =>
      System.err.println(s"deps: ${e.getMessage}")
      System.exit(1); throw e

  if resolution.cycles.nonEmpty then
    System.err.println(s"deps: ${resolution.cycles.length} cycle(s) detected:")
    resolution.cycles.foreach { cycle =>
      System.err.println(s"  ${cycle.map(_.last).mkString(" → ")}")
    }
    System.exit(1)

  // Topo-sorted list, target last (deps come first).
  println(s"target: $path")
  println(s"resolved ${resolution.orderedNodes.length} module(s) in topo order:")
  resolution.orderedNodes.foreach { node =>
    val marker = if node.path == path then " (target)" else ""
    println(s"  ${node.path}$marker")
  }
  if graphMode then
    println(s"\nedges:")
    resolution.orderedNodes.foreach { node =>
      node.depPaths.foreach { dep =>
        println(s"  ${node.path.last} → ${dep.last}")
      }
    }

// ─────────────────────────────────────────────────────────────────────────────
// ssc clean <artifact-dir>  —  v2.0 artifact garbage collector
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc clean <dir> [--dry-run] [--all]`
 *
 *  Walk an artifact directory and remove stale v2.0 artifacts.  Two modes:
 *
 *   - **Default (garbage collection):** for each artifact (`*.scim`,
 *     `*.scir`, `*.scjvm`, `*.scjs`), check whether the corresponding
 *     `.ssc` source still exists under `<dir>/..` (the conventional
 *     "source dir is one level above artifact dir" layout).  Artifacts
 *     whose source has been deleted are removed.  Companion runtime
 *     artifacts (`*.scjvm-runtime`, `*.scjs-runtime`) are NEVER removed
 *     in this mode — they're keyed by capabilities, not source, so a
 *     no-longer-referenced runtime is fine to leave alone (it will be
 *     overwritten on the next compile that needs the same caps).
 *
 *   - **`--all`:** unconditionally remove every artifact in `<dir>`
 *     matching the v2.0 extension set.  Includes runtime artifacts.
 *
 *  `--dry-run` prints what would be removed without touching the FS.
 *  Output: one line per artifact (with action: REMOVE / DRY-RUN /
 *  KEEP), followed by a summary line.
 *
 *  Idempotent: running twice on the same state is a no-op (the second
 *  run reports zero removals).
 *
 *  v2.0 Phase 3 follow-up — artifact GC for users who manually
 *  manage their artifact dirs without re-running a full `build`. */
def cleanCommand(args: List[String]): Unit =
  var dryRun = false
  var all    = false
  val dirs   = scala.collection.mutable.ArrayBuffer.empty[String]
  args.foreach {
    case "--dry-run" => dryRun = true
    case "--all"     => all    = true
    case d           => dirs += d
  }
  if dirs.isEmpty then
    System.err.println("Usage: ssc clean <dir> [--dry-run] [--all]")
    System.err.println("  Default: remove artifacts whose source .ssc no longer exists in <dir>/..")
    System.err.println("  --dry-run  Print actions; don't delete.")
    System.err.println("  --all      Remove every v2.0 artifact (incl. runtimes) under <dir>.")
    System.exit(1)
  if dirs.length > 1 then
    System.err.println(
      s"Warning: ssc clean currently processes a single directory; ignoring ${dirs.length - 1} extra path(s).")

  val dir = os.Path(dirs.head, os.pwd)
  if !os.exists(dir) then
    System.err.println(s"clean: directory not found: $dir")
    System.exit(1)
  if !os.isDir(dir) then
    System.err.println(s"clean: not a directory: $dir")
    System.exit(1)

  // The v2.0 artifact extensions we manage.  Two flavours:
  //  - Per-module artifacts (keyed by <moduleId>.scXX → look for
  //    <moduleId>.ssc in `srcDir`).
  //  - Runtime artifacts (".scjvm-runtime", ".scjs-runtime") — keyed
  //    by capability set, not source; only --all touches them.
  val moduleExts  = Set("scim", "scir", "scjvm", "scjs")
  val runtimeExts = Set("scjvm-runtime", "scjs-runtime")

  val srcDir = dir / os.up

  // Walk dir non-recursively — artifacts live in a flat dir per the
  // conventional layout (build → `artifacts/`).  Recursive walk would
  // surprise users who symlink a `src/` tree under `artifacts/`.
  val entries =
    if !os.isDir(dir) then Nil
    else os.list(dir).filter(p => os.isFile(p)).toList.sortBy(_.last)

  /** True when `path` looks like a per-module artifact (e.g. `foo.scjvm`,
   *  not `_runtime.scjvm-runtime`). */
  def isModuleArtifact(path: os.Path): Boolean =
    val name = path.last
    moduleExts.exists(ext => name.endsWith("." + ext)) &&
      !runtimeExts.exists(ext => name.endsWith("." + ext))

  def isRuntimeArtifact(path: os.Path): Boolean =
    val name = path.last
    runtimeExts.exists(ext => name.endsWith("." + ext))

  /** Strip the artifact extension off `name` to recover the module id
   *  (`foo.scjvm` → `foo`, `bar.scim` → `bar`).  Returns None for non-
   *  artifact filenames. */
  def moduleIdOf(name: String): Option[String] =
    moduleExts.collectFirst {
      case ext if name.endsWith("." + ext) => name.stripSuffix("." + ext)
    }

  /** True when a `<moduleId>.ssc` exists somewhere we can find it.  We
   *  check the conventional `<dir>/..` first, then `<dir>` itself (in
   *  case the user keeps sources next to artifacts), then a recursive
   *  walk of `<dir>/..` as a last resort — modulo skipping `<dir>` to
   *  avoid loops. */
  def sourceExists(moduleId: String): Boolean =
    val target = moduleId + ".ssc"
    // Cheap checks first.
    if os.exists(srcDir / target) then true
    else if os.exists(dir / target) then true
    else
      // Recursive fallback under srcDir; cap the walk so a huge tree
      // doesn't make `clean` painful.  Skip the artifact dir itself.
      val cap = 5000
      val it  = os.walk(srcDir, skip = p => p == dir, maxDepth = 6).iterator
      var seen = 0
      var found = false
      while it.hasNext && !found && seen < cap do
        val p = it.next()
        seen += 1
        if p.last == target && os.isFile(p) then found = true
      found

  // Stage 1 — classify every entry.
  sealed trait Action
  case object Remove extends Action  // artifact is stale → delete
  case object Keep   extends Action  // artifact is fresh → leave alone
  case object Skip   extends Action  // not an artifact we manage

  case class Decision(path: os.Path, action: Action, reason: String)

  val decisions = entries.map { p =>
    val name = p.last
    val isMod = isModuleArtifact(p)
    val isRt  = isRuntimeArtifact(p)
    if !isMod && !isRt then Decision(p, Skip, "not a v2.0 artifact")
    else if all then
      Decision(p, Remove,
        if isRt then "runtime artifact (--all)"
        else        "module artifact (--all)")
    else if isRt then
      Decision(p, Keep, "runtime artifact — only removed with --all")
    else
      moduleIdOf(name) match
        case None     => Decision(p, Skip, "unrecognised artifact name")
        case Some(id) =>
          if sourceExists(id) then
            Decision(p, Keep, s"source $id.ssc exists")
          else
            Decision(p, Remove, s"source $id.ssc no longer exists")
  }

  // Stage 2 — apply (or pretend to).  Print one line per non-Skip action.
  val toRemove = decisions.filter(_.action == Remove)
  toRemove.foreach { d =>
    val tag = if dryRun then "DRY-RUN" else "REMOVE"
    println(s"$tag ${d.path.last}  (${d.reason})")
    if !dryRun then
      try os.remove(d.path)
      catch case e: Throwable =>
        System.err.println(s"clean: failed to remove ${d.path.last}: ${e.getMessage}")
  }

  val keptCount    = decisions.count(_.action == Keep)
  val skippedCount = decisions.count(_.action == Skip)
  val removedCount = toRemove.size
  val verb         = if dryRun then "would remove" else "removed"
  println(s"Summary: $verb $removedCount; kept $keptCount; skipped $skippedCount (non-artifact files).")

// ─────────────────────────────────────────────────────────────────────────────
// ssc info <artifact>  —  v2.0 artifact envelope inspector
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc info <path-to-artifact> [--json]`
 *
 *  Inspect any v2.0 artifact (`.scim`, `.scir`, `.scjvm`, `.scjs`) and
 *  dump its envelope plus the key contents of its body in plain text.
 *  Useful for debugging, CI sanity checks, and ABI verification.
 *
 *  Format is detected by file extension.  With `--json` the entire
 *  envelope is pretty-printed as JSON instead of the human-readable
 *  summary.
 *
 *  Non-zero exit codes:
 *    1 — file does not exist, unknown extension, or parse error
 *    1 — magic / abiVersion mismatch (artifact corruption or ABI bump)
 *
 *  v2.0 — artifact introspection. */
def infoCommand(args: List[String]): Unit =
  var jsonMode = false
  var sectionsMode = false
  val files = scala.collection.mutable.ArrayBuffer.empty[String]
  args.foreach {
    case "--json"      => jsonMode = true
    case "--sections"  => sectionsMode = true
    case f             => files += f
  }
  if files.isEmpty then
    System.err.println("Usage: ssc info <artifact> [--json] [--sections]")
    System.err.println("Supported extensions: .scim, .scir, .scjvm, .scjs")
    System.exit(1)
  // Single-argument MVP — process the first file only.  Multiple files
  // are reserved for a follow-up; pre-warn if the user passed more.
  if files.length > 1 then
    System.err.println(
      s"Warning: ssc info currently inspects a single artifact; ignoring ${files.length - 1} extra path(s).")

  val file = files.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    System.err.println(s"info: file not found: $file")
    System.exit(1)

  val ext = path.ext
  if !Set("scim", "scir", "scjvm", "scjs").contains(ext) then
    System.err.println(
      s"info: unsupported extension '.$ext' — expected .scim, .scir, .scjvm, or .scjs")
    System.exit(1)

  try
    val raw     = os.read(path)
    val bytes   = os.read.bytes(path)
    val fileSize = bytes.length
    ext match
      case "scim"  => printScimInfo(path, raw, fileSize, jsonMode, sectionsMode)
      case "scir"  => printScirInfo(path, raw, fileSize, jsonMode, sectionsMode)
      case "scjvm" => printScjvmInfo(path, raw, fileSize, jsonMode, sectionsMode)
      case "scjs"  => printScjsInfo(path, raw, fileSize, jsonMode, sectionsMode)
      case _       => () // unreachable — extension already validated above
  catch case e: Exception =>
    System.err.println(s"info: ${e.getMessage}")
    System.exit(1)

private def printScimInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
  ArtifactIO.readInterface(json) match
    case Left(err) =>
      System.err.println(s"info: $err")
      System.exit(1)
    case Right(iface) =>
      if jsonMode then println(ArtifactIO.writeInterface(iface))
      else
        println(s"file: $path")
        println(s"format: .scim (module interface)")
        println(s"magic: ${iface.magic}")
        println(s"abiVersion: ${iface.abiVersion}")
        println(s"moduleId: ${iface.moduleName.getOrElse("<unnamed>")}")
        println(s"package: ${if iface.pkg.isEmpty then "<none>" else iface.pkg.mkString(".")}")
        println(s"moduleVersion: ${iface.moduleVersion.getOrElse("<none>")}")
        println(s"sourceHash: ${iface.sourceHash}")
        println(s"size: $fileSize bytes")
        println(s"exports: ${iface.exports.length}")
        iface.exports.foreach { e =>
          println(s"  - ${e.kind} ${e.name}: ${e.tpe}")
        }
        if iface.externDefs.nonEmpty then
          println(s"externDefs: ${iface.externDefs.length}")
          iface.externDefs.foreach { e =>
            println(s"  - extern ${e.name}: ${e.tpe}")
          }
        println(s"instances: ${iface.instances.length}")
        iface.instances.foreach { i =>
          println(s"  - ${i.typeclass}[${i.typeParam}] via ${i.witnessName} (${i.fqn})")
        }
        println(s"capabilities: ${iface.capabilities.length}")
        iface.capabilities.foreach { c =>
          println(s"  - ${c.name}")
        }
        if iface.dependencies.nonEmpty then
          println(s"dependencies: ${iface.dependencies.size}")
          iface.dependencies.toList.sortBy(_._1).foreach { (alias, target) =>
            println(s"  - $alias → $target")
          }
        if sectionsMode then
          printSectionHashes(iface.sectionHashes)

/** Pretty-print the `sectionHashes` map (shared across .scim/.scir/.scjvm/.scjs).
 *
 *  Entries are emitted in iteration order — which is insertion order
 *  for the `LinkedHashMap`-derived map persisted by
 *  `InterfaceExtractor.computeSectionHashes` and any subsequent
 *  `.copy(sectionHashes = ...)` calls.  When the field is empty (pre-
 *  Phase-3 artifact or `--section-cache` was off at write time) the line
 *  reports "sectionHashes: 0 (none — section cache off or pre-Phase-3)"
 *  so users diagnose missing data, not just absence.
 *
 *  v2.0 Phase 3 — `ssc info <artifact> --sections` extension. */
private def printSectionHashes(map: Map[String, String]): Unit =
  if map.isEmpty then
    println("sectionHashes: 0 (none — section cache off or pre-Phase-3 artifact)")
  else
    println(s"sectionHashes: ${map.size}")
    map.toList.foreach { case (id, h) =>
      println(s"  - $id: $h")
    }

private def printScirInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
  // Decode the artifact directly so we have access to the sectionHashes map
  // for `--sections`; the legacy `readIr` returns a tuple that drops it.
  val artEither =
    scala.util.Try(upickle.default.read[scalascript.ir.ModuleIrArtifact](json)).toEither.left.map { e =>
      s"Failed to parse .scir artifact: ${e.getMessage}"
    }
  artEither match
    case Left(err) =>
      System.err.println(s"info: $err")
      System.exit(1)
    case Right(art) =>
      if jsonMode then
        // Round-trip through writeIr so the canonical pretty form is
        // emitted (matches pre-Phase-3 behaviour).
        val nm = scala.util.Try(upickle.default.read[scalascript.ir.NormalizedModule](art.body)).getOrElse(
          scalascript.ir.NormalizedModule(manifest = None, sections = Nil))
        println(ArtifactIO.writeIr(nm, art.pkg, art.moduleName, art.sourceHash, art.sectionHashes))
      else
        // Body byte size — `art.body` is the embedded JSON string.
        val bodyBytes = art.body.length
        val sectionCount =
          scala.util.Try(upickle.default.read[scalascript.ir.NormalizedModule](art.body).sections.length).getOrElse(0)
        println(s"file: $path")
        println(s"format: .scir (module IR artifact)")
        println(s"magic: ${art.magic}")
        println(s"abiVersion: ${art.abiVersion}")
        println(s"moduleId: ${art.moduleName.getOrElse("<unnamed>")}")
        println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
        println(s"sourceHash: ${art.sourceHash}")
        println(s"size: $fileSize bytes")
        println(s"sections: $sectionCount")
        println(s"bodyBytes: $bodyBytes")
        if sectionsMode then
          printSectionHashes(art.sectionHashes)

private def printScjvmInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
  JvmArtifactIO.readJvm(json) match
    case Left(err) =>
      System.err.println(s"info: $err")
      System.exit(1)
    case Right(art) =>
      if jsonMode then println(JvmArtifactIO.writeJvm(art))
      else
        println(s"file: $path")
        println(s"format: .scjvm (JVM-backend cached source)")
        println(s"magic: ${art.magic}")
        println(s"abiVersion: ${art.abiVersion}")
        println(s"moduleId: ${art.moduleId}")
        println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
        println(s"moduleName: ${art.moduleName.getOrElse("<unnamed>")}")
        println(s"sourceHash: ${art.sourceHash}")
        println(s"size: $fileSize bytes")
        println(s"scalaSourceBytes: ${art.scalaSource.length}")
        println(s"imports: ${art.imports.length}")
        art.imports.foreach { imp => println(s"  - $imp") }
        if sectionsMode then
          printSectionHashes(art.sectionHashes)

private def printScjsInfo(path: os.Path, json: String, fileSize: Long, jsonMode: Boolean, sectionsMode: Boolean = false): Unit =
  JsArtifactIO.readJs(json) match
    case Left(err) =>
      System.err.println(s"info: $err")
      System.exit(1)
    case Right(art) =>
      if jsonMode then println(JsArtifactIO.writeJs(art))
      else
        println(s"file: $path")
        println(s"format: .scjs (JS-backend cached source)")
        println(s"magic: ${art.magic}")
        println(s"abiVersion: ${art.abiVersion}")
        println(s"moduleId: ${art.moduleId}")
        println(s"package: ${if art.pkg.isEmpty then "<none>" else art.pkg.mkString(".")}")
        println(s"moduleName: ${art.moduleName.getOrElse("<unnamed>")}")
        println(s"sourceHash: ${art.sourceHash}")
        println(s"size: $fileSize bytes")
        println(s"jsSourceBytes: ${art.jsSource.length}")
        println(s"imports: ${art.imports.length}")
        art.imports.foreach { imp => println(s"  - $imp") }
        if sectionsMode then
          printSectionHashes(art.sectionHashes)


// ─────────────────────────────────────────────────────────────────────────────
// ssc verify <artifact-dir>  —  v2.0 operational health-check
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc verify <artifact-dir> [--src-dir <dir>] [--strict] [--json]`
 *
 *  Walk every v2.0 artifact in `<artifact-dir>` (top-level only) and report
 *  its integrity status.  Designed for CI/CD use: gates deploy on a clean
 *  artifact set.
 *
 *  Per-artifact checks:
 *
 *   1. Envelope — `magic == "SSCART"` and `abiVersion == ArtifactVersion.current`,
 *      reusing the existing IO readers (their `Left(err)` is propagated as the
 *      artifact's FAIL reason).
 *
 *   2. `sourceHash` shape — 64-char lowercase hex.
 *
 *   3. `sourceHash` freshness (only with `--strict`) — for `.scim/.scir/.scjvm/.scjs`,
 *      locate `<moduleId>.ssc` under `--src-dir` (recursive), recompute SHA-256
 *      via `InterfaceExtractor.sha256` and assert the match.  Missing source
 *      file → WARN.  Runtime artifacts (`.scjvm-runtime` / `.scjs-runtime`)
 *      hash a synthetic runtime source, not a user file — they're skipped.
 *
 *   4. Cross-reference integrity — every `imports` entry in a `.scim/.scjvm/.scjs`
 *      must have a matching artifact of the same kind in the dir (case-
 *      insensitive basename match).  Missing import → FAIL.
 *
 *   5. Runtime artifact coverage — for `.scjvm/.scjs` collections, the union of
 *      every module's `capabilities` must be a subset of the corresponding
 *      `_runtime.scjvm-runtime` / `_runtime.scjs-runtime` capabilities.  When
 *      modules declare capabilities but no runtime artifact exists, this is
 *      a WARN (which becomes FAIL with `--strict`).
 *
 *  Exit codes:
 *   - 0 when every artifact is OK, or only WARNs without `--strict`.
 *   - 1 when any FAIL, or any WARN with `--strict`.
 *
 *  Output: plain text by default (one line per artifact + summary).  `--json`
 *  emits a parseable JSON document `{ dir, artifacts: [...], summary: {...} }`.
 *
 *  v2.0 Phase 3 — operational health check. */
def verifyCommand(args: List[String]): Unit =
  var artifactDirArg: Option[String] = None
  var srcDirArg:      Option[String] = None
  var strict:         Boolean        = false
  var jsonMode:       Boolean        = false
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--src-dir" if it.hasNext => srcDirArg = Some(it.next())
      case "--strict"                => strict = true
      case "--json"                  => jsonMode = true
      case other if other.startsWith("--") =>
        System.err.println(s"verify: unrecognised argument '$other'")
        System.exit(1)
      case other =>
        if artifactDirArg.isDefined then
          System.err.println(s"verify: unexpected extra argument '$other'")
          System.exit(1)
        artifactDirArg = Some(other)

  if artifactDirArg.isEmpty then
    System.err.println("Usage: ssc verify <artifact-dir> [--src-dir <dir>] [--strict] [--json]")
    System.exit(1)

  val artifactDir = os.Path(artifactDirArg.get, os.pwd)
  if !os.exists(artifactDir) then
    System.err.println(s"verify: artifact-dir not found: $artifactDir")
    System.exit(1)
  if !os.isDir(artifactDir) then
    System.err.println(s"verify: not a directory: $artifactDir")
    System.exit(1)

  val srcDir = srcDirArg.map(os.Path(_, os.pwd)).getOrElse(artifactDir / os.up)
  // Source dir is allowed to be missing; freshness checks just degrade to WARN.

  val (rows, summary) = runVerify(artifactDir, srcDir, strict)

  if jsonMode then
    println(VerifyReport.toJson(artifactDir, rows, summary))
  else
    VerifyReport.printPlain(artifactDir, rows, summary)

  // Exit code policy: any FAIL → 1.  With --strict, any WARN also → 1.
  val shouldFail =
    summary.fail > 0 || (strict && summary.warn > 0)
  if shouldFail then System.exit(1)

/** Per-artifact verify outcome. */
private case class VerifyRow(
    path:        os.Path,
    format:      String,                // "scim" | "scir" | "scjvm" | "scjs" | "scjvm-runtime" | "scjs-runtime"
    status:      String,                // "ok" | "warn" | "fail"
    summary:     String,                // human-readable one-liner for plain output
    detail:      Map[String, ujson.Value] = Map.empty,
    error:       Option[String] = None
)

private case class VerifySummary(ok: Int, warn: Int, fail: Int):
  def total: Int = ok + warn + fail

/** Walk every supported artifact in `artifactDir`, classify each, and return
 *  the row + summary counts.  Pure read-only. */
private def runVerify(
    artifactDir: os.Path,
    srcDir:      os.Path,
    strict:      Boolean
): (List[VerifyRow], VerifySummary) =
  val supportedExts = Set("scim", "scir", "scjvm", "scjs", "scjvm-runtime", "scjs-runtime")
  // os-lib `Path.ext` returns the last suffix (e.g. "scjvm-runtime" for
  // `_runtime.scjvm-runtime`).  Top-level only — no recursion.
  val files: List[os.Path] =
    if !os.isDir(artifactDir) then Nil
    else
      os.list(artifactDir)
        .filter(os.isFile)
        .filter(p => supportedExts.contains(p.ext))
        .sortBy(_.last)
        .toList

  // Quick lookup tables for cross-ref checks.  Use lowercase basenames so the
  // match is case-insensitive per spec.
  val scimNames:  Set[String] = files.filter(_.ext == "scim") .map(_.last.toLowerCase).toSet
  val scjvmNames: Set[String] = files.filter(_.ext == "scjvm").map(_.last.toLowerCase).toSet
  val scjsNames:  Set[String] = files.filter(_.ext == "scjs") .map(_.last.toLowerCase).toSet

  // Index source files by their basename minus ".ssc" so freshness lookup is
  // O(1) per artifact.  Recursive walk of srcDir (when it exists) — collisions
  // (same moduleId in two subdirs) are resolved by first-wins.
  val sourceIndex: Map[String, os.Path] =
    if !os.exists(srcDir) || !os.isDir(srcDir) then Map.empty
    else
      val acc = scala.collection.mutable.LinkedHashMap.empty[String, os.Path]
      try
        os.walk(srcDir).filter(p => os.isFile(p) && p.ext == "ssc").foreach { p =>
          val key = p.last.stripSuffix(".ssc")
          if !acc.contains(key) then acc(key) = p
        }
      catch case _: Throwable => () // unreadable subdirs are ignored
      acc.toMap

  // Capability unions — populated by classifyJvm / classifyJs and consumed
  // below to assess runtime coverage.
  val jvmCaps = scala.collection.mutable.Set.empty[String]
  val jsCaps  = scala.collection.mutable.Set.empty[String]
  var jvmRuntimeRow: Option[VerifyRow] = None
  var jsRuntimeRow:  Option[VerifyRow] = None

  val rows = scala.collection.mutable.ListBuffer.empty[VerifyRow]

  files.foreach { p =>
    val row = p.ext match
      case "scim"           => verifyScim(p, srcDir, sourceIndex, strict, scimNames)
      case "scir"           => verifyScir(p, srcDir, sourceIndex, strict)
      case "scjvm"          =>
        val r = verifyScjvm(p, srcDir, sourceIndex, strict, scjvmNames)
        r.detail.get("capabilities").foreach(_.arr.foreach(c => jvmCaps += c.str))
        r
      case "scjs"           =>
        val r = verifyScjs(p, srcDir, sourceIndex, strict, scjsNames)
        r.detail.get("capabilities").foreach(_.arr.foreach(c => jsCaps += c.str))
        r
      case "scjvm-runtime"  =>
        val r = verifyJvmRuntime(p)
        jvmRuntimeRow = Some(r)
        r
      case "scjs-runtime"   =>
        val r = verifyJsRuntime(p)
        jsRuntimeRow = Some(r)
        r
      case _                => // unreachable: filtered above
        VerifyRow(p, p.ext, "fail", s"unsupported extension '.${p.ext}'", error = Some("unsupported extension"))
    rows += row
  }

  // Cross-cutting: runtime capability coverage.  Append synthetic WARN rows
  // when modules declare capabilities the runtime doesn't cover (or the
  // runtime is missing entirely).
  def assessRuntimeCoverage(
      kind:        String,     // "scjvm" | "scjs"
      moduleCaps:  Set[String],
      runtimeRow:  Option[VerifyRow]
  ): Option[VerifyRow] =
    if moduleCaps.isEmpty then None
    else runtimeRow match
      case None =>
        Some(VerifyRow(
          artifactDir / s"_runtime.$kind-runtime",
          s"$kind-runtime",
          "warn",
          s"runtime artifact MISSING but modules declare capabilities [${moduleCaps.toList.sorted.mkString(", ")}]",
          error = Some(s"missing _runtime.$kind-runtime")
        ))
      case Some(r) if r.status == "fail" =>
        None  // runtime row already failed; no need to layer a coverage warning on top.
      case Some(r) =>
        val rtCaps =
          r.detail.get("capabilities").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
        val uncovered = moduleCaps -- rtCaps
        if uncovered.isEmpty then None
        else
          Some(VerifyRow(
            r.path,
            r.format,
            "fail",
            s"runtime missing capabilities [${uncovered.toList.sorted.mkString(", ")}] " +
            s"(declared by modules but not in runtime caps [${rtCaps.toList.sorted.mkString(", ")}])",
            error = Some(s"runtime missing capabilities: ${uncovered.toList.sorted.mkString(",")}")
          ))

  assessRuntimeCoverage("scjvm", jvmCaps.toSet, jvmRuntimeRow).foreach(rows += _)
  assessRuntimeCoverage("scjs",  jsCaps.toSet,  jsRuntimeRow ).foreach(rows += _)

  val ok   = rows.count(_.status == "ok")
  val warn = rows.count(_.status == "warn")
  val fail = rows.count(_.status == "fail")
  (rows.toList, VerifySummary(ok, warn, fail))

// ── Per-format classifiers ──────────────────────────────────────────────────

/** Hex sanity check for `sourceHash`.  64 chars, all `[0-9a-f]`. */
private def isValidSha256Hex(s: String): Boolean =
  s.length == 64 && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

/** Recompute the SHA-256 of `<candidate>.ssc` if found in `sourceIndex`.
 *
 *  Tries each candidate in order — typically `[moduleName, artifactBasename]`
 *  — so we can match either a manifest `name:` or the on-disk file stem.
 *  The build's incremental orchestrator names `.scim` files after the source
 *  basename (e.g. `ast.scim`) but stamps `moduleName` with a hyphen-joined
 *  package prefix (e.g. `std-dsl-ast`); falling back to the file basename
 *  lets `--strict` succeed against the on-disk source `ast.ssc`.
 *
 *  Returns:
 *   - `Right(true)`  — found and matches.
 *   - `Right(false)` — found but mismatch (stale artifact / touched source).
 *   - `Left("missing")` — none of the candidates resolved (warn).
 *   - `Left(err)`        — i/o error (warn). */
private def checkSourceHash(
    candidates: List[String],
    artifactSha: String,
    sourceIndex: Map[String, os.Path]
): Either[String, Boolean] =
  val hit = candidates.iterator.flatMap(c => sourceIndex.get(c)).nextOption()
  hit match
    case None      => Left("missing")
    case Some(src) =>
      try
        val recomputed = InterfaceExtractor.sha256(os.read.bytes(src))
        Right(recomputed.equalsIgnoreCase(artifactSha))
      catch case e: Throwable =>
        Left(s"read error: ${e.getMessage}")

/** Cross-reference check: every entry in `imports` must match a file in
 *  `siblingNames` (lowercase basenames in the same artifact dir).
 *
 *  The build's `imports` field carries either bare module IDs (e.g. `"util"`,
 *  emitted by `compile-jvm` from front-matter aliases) OR path fragments
 *  (e.g. `"./util.ssc"`, emitted from raw `[link](util.ssc)` markdown imports).
 *  Normalise both into a candidate basename `<id>.<ext>` and look it up.  An
 *  import is considered satisfied when ANY candidate basename matches. */
private def checkImports(
    imports:      List[String],
    artifactKind: String,         // "scim" | "scjvm" | "scjs"
    siblingNames: Set[String]
): List[String] =
  imports.flatMap { imp =>
    val raw = imp.trim
    if raw.isEmpty then None
    else
      // Strip leading "./" and trailing ".ssc"; the residue is the module id.
      val trimmed = raw.stripPrefix("./").stripSuffix(".ssc")
      // Last path segment only — `foo/bar` → `bar`.
      val basename = trimmed.split('/').last.toLowerCase
      val candidate = s"$basename.$artifactKind"
      if siblingNames.contains(candidate) then None
      else Some(imp)
  }

private def verifyScim(
    path:         os.Path,
    srcDir:       os.Path,
    sourceIndex:  Map[String, os.Path],
    strict:       Boolean,
    siblingScims: Set[String]
): VerifyRow =
  ArtifactIO.readInterfaceFile(path) match
    case Left(err) =>
      VerifyRow(path, "scim", "fail", err.linesIterator.next(), error = Some(err))
    case Right(iface) =>
      val checks  = scala.collection.mutable.ListBuffer.empty[String]
      val warns   = scala.collection.mutable.ListBuffer.empty[String]
      val fails   = scala.collection.mutable.ListBuffer.empty[String]

      checks += "envelope OK"

      if !isValidSha256Hex(iface.sourceHash) then
        fails += s"sourceHash is not a 64-char hex string (got '${iface.sourceHash.take(16)}...')"
      else if strict then
        val basename = path.last.stripSuffix(".scim")
        val candidates = (iface.moduleName.toList :+ basename).distinct
        checkSourceHash(candidates, iface.sourceHash, sourceIndex) match
          case Right(true)  => checks += "sourceHash OK"
          case Right(false) => fails  += s"sourceHash mismatch — source file changed since artifact was written"
          case Left("missing") => warns += s"sourceHash MISSING in $srcDir (no ${candidates.head}.ssc found)"
          case Left(err)    => warns += s"sourceHash unverified ($err)"
      else
        checks += "sourceHash OK"

      // Cross-refs from manifest dependencies.  Bare `imports` (path-based,
      // emitted by JvmArtifact / JsArtifact `imports` list) live on the .scjvm
      // and .scjs envelopes; .scim's authoritative source is `dependencies`.
      val deps        = iface.dependencies.keys.toList
      val missingDeps = checkImports(deps, "scim", siblingScims)
      if missingDeps.nonEmpty then
        fails += s"missing interface deps: ${missingDeps.mkString(", ")}"
      else if deps.nonEmpty then
        checks += s"imports ${deps.mkString(", ")}"

      checks += s"${iface.exports.length} exports"

      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else if warns.nonEmpty then ("warn", (checks ++ warns).mkString(", "))
        else ("ok", checks.mkString(", "))

      val detail = scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      detail("exports")    = ujson.Num(iface.exports.length.toDouble)
      detail("sourceHash") = ujson.Str(iface.sourceHash)
      if iface.capabilities.nonEmpty then
        detail("capabilities") = ujson.Arr.from(iface.capabilities.map(c => ujson.Str(c.name)))
      VerifyRow(path, "scim", status, summary, detail.toMap, fails.headOption.orElse(warns.headOption))

private def verifyScir(
    path:        os.Path,
    srcDir:      os.Path,
    sourceIndex: Map[String, os.Path],
    strict:      Boolean
): VerifyRow =
  ArtifactIO.readIrFile(path) match
    case Left(err) =>
      VerifyRow(path, "scir", "fail", err.linesIterator.next(), error = Some(err))
    case Right((nm, _, moduleName, sourceHash)) =>
      val checks = scala.collection.mutable.ListBuffer.empty[String]
      val warns  = scala.collection.mutable.ListBuffer.empty[String]
      val fails  = scala.collection.mutable.ListBuffer.empty[String]

      checks += "envelope OK"

      if !isValidSha256Hex(sourceHash) then
        fails += s"sourceHash is not a 64-char hex string"
      else if strict then
        val basename = path.last.stripSuffix(".scir")
        val candidates = (moduleName.toList :+ basename).distinct
        checkSourceHash(candidates, sourceHash, sourceIndex) match
          case Right(true)     => checks += "sourceHash OK"
          case Right(false)    => fails  += s"sourceHash mismatch — source file changed"
          case Left("missing") => warns  += s"sourceHash MISSING in $srcDir (no ${candidates.head}.ssc found)"
          case Left(err)       => warns  += s"sourceHash unverified ($err)"
      else
        checks += "sourceHash OK"

      checks += s"${nm.sections.length} sections"

      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else if warns.nonEmpty then ("warn", (checks ++ warns).mkString(", "))
        else ("ok", checks.mkString(", "))

      val detail = Map[String, ujson.Value](
        "sections"   -> ujson.Num(nm.sections.length.toDouble),
        "sourceHash" -> ujson.Str(sourceHash)
      )
      VerifyRow(path, "scir", status, summary, detail, fails.headOption.orElse(warns.headOption))

private def verifyScjvm(
    path:         os.Path,
    srcDir:       os.Path,
    sourceIndex:  Map[String, os.Path],
    strict:       Boolean,
    siblingScjvm: Set[String]
): VerifyRow =
  JvmArtifactIO.readJvmFile(path) match
    case Left(err) =>
      VerifyRow(path, "scjvm", "fail", err.linesIterator.next(), error = Some(err))
    case Right(art) =>
      val checks = scala.collection.mutable.ListBuffer.empty[String]
      val warns  = scala.collection.mutable.ListBuffer.empty[String]
      val fails  = scala.collection.mutable.ListBuffer.empty[String]

      checks += "envelope OK"

      if !isValidSha256Hex(art.sourceHash) then
        fails += s"sourceHash is not a 64-char hex string"
      else if strict then
        val basename = path.last.stripSuffix(".scjvm")
        val candidates = (art.moduleName.toList :+ art.moduleId :+ basename).distinct
        checkSourceHash(candidates, art.sourceHash, sourceIndex) match
          case Right(true)     => checks += "sourceHash OK"
          case Right(false)    => fails  += s"sourceHash mismatch — source file changed"
          case Left("missing") => warns  += s"sourceHash MISSING in $srcDir (no ${candidates.head}.ssc found)"
          case Left(err)       => warns  += s"sourceHash unverified ($err)"
      else
        checks += "sourceHash OK"

      val missingImports = checkImports(art.imports, "scjvm", siblingScjvm)
      if missingImports.nonEmpty then
        fails += s"missing JVM imports: ${missingImports.mkString(", ")}"
      else if art.imports.nonEmpty then
        checks += s"imports ${art.imports.mkString(", ")}"

      art.classBundle.foreach { b =>
        val kb = (b.length * 3.0 / 4.0 / 1024.0).round
        checks += s"classBundle $kb KB"
      }
      if art.capabilities.nonEmpty then
        checks += s"caps [${art.capabilities.mkString(", ")}]"

      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else if warns.nonEmpty then ("warn", (checks ++ warns).mkString(", "))
        else ("ok", checks.mkString(", "))

      val detail = scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      detail("sourceHash")   = ujson.Str(art.sourceHash)
      detail("imports")      = ujson.Arr.from(art.imports.map(ujson.Str(_)))
      detail("capabilities") = ujson.Arr.from(art.capabilities.map(ujson.Str(_)))
      art.classBundle.foreach(b =>
        detail("classBundleBytes") = ujson.Num((b.length * 3.0 / 4.0).round.toDouble))
      VerifyRow(path, "scjvm", status, summary, detail.toMap, fails.headOption.orElse(warns.headOption))

private def verifyScjs(
    path:        os.Path,
    srcDir:      os.Path,
    sourceIndex: Map[String, os.Path],
    strict:      Boolean,
    siblingScjs: Set[String]
): VerifyRow =
  JsArtifactIO.readJsFile(path) match
    case Left(err) =>
      VerifyRow(path, "scjs", "fail", err.linesIterator.next(), error = Some(err))
    case Right(art) =>
      val checks = scala.collection.mutable.ListBuffer.empty[String]
      val warns  = scala.collection.mutable.ListBuffer.empty[String]
      val fails  = scala.collection.mutable.ListBuffer.empty[String]

      checks += "envelope OK"

      if !isValidSha256Hex(art.sourceHash) then
        fails += s"sourceHash is not a 64-char hex string"
      else if strict then
        val basename = path.last.stripSuffix(".scjs")
        val candidates = (art.moduleName.toList :+ art.moduleId :+ basename).distinct
        checkSourceHash(candidates, art.sourceHash, sourceIndex) match
          case Right(true)     => checks += "sourceHash OK"
          case Right(false)    => fails  += s"sourceHash mismatch — source file changed"
          case Left("missing") => warns  += s"sourceHash MISSING in $srcDir (no ${candidates.head}.ssc found)"
          case Left(err)       => warns  += s"sourceHash unverified ($err)"
      else
        checks += "sourceHash OK"

      val missingImports = checkImports(art.imports, "scjs", siblingScjs)
      if missingImports.nonEmpty then
        fails += s"missing JS imports: ${missingImports.mkString(", ")}"
      else if art.imports.nonEmpty then
        checks += s"imports ${art.imports.mkString(", ")}"

      val kb = (art.jsSource.length / 1024.0).round
      checks += s"jsSource $kb KB"
      if art.capabilities.nonEmpty then
        checks += s"caps [${art.capabilities.mkString(", ")}]"

      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else if warns.nonEmpty then ("warn", (checks ++ warns).mkString(", "))
        else ("ok", checks.mkString(", "))

      val detail = scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      detail("sourceHash")     = ujson.Str(art.sourceHash)
      detail("imports")        = ujson.Arr.from(art.imports.map(ujson.Str(_)))
      detail("capabilities")   = ujson.Arr.from(art.capabilities.map(ujson.Str(_)))
      detail("jsSourceBytes")  = ujson.Num(art.jsSource.length.toDouble)
      VerifyRow(path, "scjs", status, summary, detail.toMap, fails.headOption.orElse(warns.headOption))

private def verifyJvmRuntime(path: os.Path): VerifyRow =
  JvmArtifactIO.readRuntimeFile(path) match
    case Left(err) =>
      VerifyRow(path, "scjvm-runtime", "fail", err.linesIterator.next(), error = Some(err))
    case Right(rt) =>
      val checks = scala.collection.mutable.ListBuffer.empty[String]
      val fails  = scala.collection.mutable.ListBuffer.empty[String]
      checks += "envelope OK"
      if !isValidSha256Hex(rt.sourceHash) then
        fails += s"sourceHash is not a 64-char hex string"
      else
        checks += "sourceHash OK"
      val kb = (rt.classBundle.length * 3.0 / 4.0 / 1024.0).round
      checks += s"classBundle $kb KB"
      checks += s"caps [${rt.capabilities.mkString(", ")}]"
      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else ("ok", checks.mkString(", "))
      val detail = Map[String, ujson.Value](
        "sourceHash"       -> ujson.Str(rt.sourceHash),
        "capabilities"     -> ujson.Arr.from(rt.capabilities.map(ujson.Str(_))),
        "classBundleBytes" -> ujson.Num((rt.classBundle.length * 3.0 / 4.0).round.toDouble)
      )
      VerifyRow(path, "scjvm-runtime", status, summary, detail, fails.headOption)

private def verifyJsRuntime(path: os.Path): VerifyRow =
  JsArtifactIO.readRuntimeFile(path) match
    case Left(err) =>
      VerifyRow(path, "scjs-runtime", "fail", err.linesIterator.next(), error = Some(err))
    case Right(rt) =>
      val checks = scala.collection.mutable.ListBuffer.empty[String]
      val fails  = scala.collection.mutable.ListBuffer.empty[String]
      checks += "envelope OK"
      if !isValidSha256Hex(rt.sourceHash) then
        fails += s"sourceHash is not a 64-char hex string"
      else
        checks += "sourceHash OK"
      val kb = (rt.jsSource.length / 1024.0).round
      checks += s"jsSource $kb KB"
      checks += s"caps [${rt.capabilities.mkString(", ")}]"
      val (status, summary) =
        if fails.nonEmpty then ("fail", fails.mkString("; "))
        else ("ok", checks.mkString(", "))
      val detail = Map[String, ujson.Value](
        "sourceHash"    -> ujson.Str(rt.sourceHash),
        "capabilities"  -> ujson.Arr.from(rt.capabilities.map(ujson.Str(_))),
        "jsSourceBytes" -> ujson.Num(rt.jsSource.length.toDouble)
      )
      VerifyRow(path, "scjs-runtime", status, summary, detail, fails.headOption)

// ── Output formatting ───────────────────────────────────────────────────────

private object VerifyReport:
  /** Plain-text report.  One line per artifact + a final summary line. */
  def printPlain(dir: os.Path, rows: List[VerifyRow], summary: VerifySummary): Unit =
    println(s"verify: $dir")
    if rows.isEmpty then println("  (0 artifacts found)")
    else
      // Right-pad path names so summaries line up.
      val nameWidth = rows.map(_.path.last.length).max
      rows.foreach: r =>
        val marker = r.status match
          case "ok"   => "OK"
          case "warn" => "WARN"
          case "fail" => "FAIL"
          case _      => "??"
        val name = r.path.last.padTo(nameWidth, ' ')
        println(s"  [$marker] $name  ${r.summary}")
    end if
    println(s"verify: ${summary.ok} OK, ${summary.warn} WARN, ${summary.fail} FAIL")

  /** Machine-readable JSON report. */
  def toJson(dir: os.Path, rows: List[VerifyRow], summary: VerifySummary): String =
    val arr = ujson.Arr.from(rows.map { r =>
      val o = ujson.Obj(
        "path"   -> ujson.Str(r.path.last),
        "format" -> ujson.Str(r.format),
        "status" -> ujson.Str(r.status)
      )
      r.detail.foreach { (k, v) => o(k) = v }
      r.error.foreach(e => o("error") = ujson.Str(e))
      o("summary") = ujson.Str(r.summary)
      o: ujson.Value
    })
    val obj = ujson.Obj(
      "dir"       -> ujson.Str(dir.toString),
      "artifacts" -> arr,
      "summary"   -> ujson.Obj(
        "ok"   -> ujson.Num(summary.ok.toDouble),
        "warn" -> ujson.Num(summary.warn.toDouble),
        "fail" -> ujson.Num(summary.fail.toDouble)
      )
    )
    ujson.write(obj, indent = 2)


/** Build the self-contained JS source written to a `.scjs` artifact.
 *
 *  Concatenates a capability-filtered runtime preamble with the per-module
 *  user JS.  Only runtime blocks actually needed by the module are emitted,
 *  reducing output size substantially for modules that don't use actors,
 *  MCP, Dataset, etc.
 *
 *  v2.0 — JS incremental codegen cache + JS tree-shaking. */
private def buildScjsSource(module: scalascript.ast.Module, userJs: String,
                             baseDir: Option[os.Path] = None): String =
  val caps = JsGen.detectCapabilities(module, baseDir)
  val sb   = new StringBuilder
  sb.append(JsGen.generateRuntime(caps))
  sb.append("// ── scalascript user code ───────────────────────────────────────────\n")
  sb.append(userJs)
  if !userJs.endsWith("\n") then sb.append('\n')
  sb.toString

/** Collect raw import paths from a section recursively.  Used by
 *  `compile-jvm` to populate `ModuleJvmArtifact.imports` as a hint for the
 *  linker. */
private def collectImports(sections: List[Section]): List[String] =
  sections.flatMap { s =>
    s.content.collect { case imp: Content.Import => imp.path } ++
      collectImports(s.subsections)
  }

/** v2.0 Phase 5 — discover pre-compiled artifacts shipped alongside cached
 *  `dep:` sources and stage them into the consumer's artifact dir.
 *
 *  For every `dep:` import in `module`:
 *  - Resolve the cached `.ssc` via `ImportResolver`.
 *  - Look for `.ssc-artifacts/<basename>.<ext>` alongside it.
 *  - If found, copy into `targetArtifactDir` so the typer (and the linker)
 *    see the dep's pre-compiled `.scim` / `.scjvm` / `.scjs` directly.
 *
 *  Returns the count of artifacts staged per extension (for logging).
 *  Source-fallback: when no artifacts ship alongside, the dep's source is
 *  parsed via the regular `JvmGen.inlineImport` / `JsGen` path later, so
 *  callers can be unconditional in invoking this helper.
 *
 *  Corrupt artifacts (bad magic / abi mismatch) surface a clear error and
 *  the file is skipped — the caller can choose to fall back to source. */
private def stagePrecompiledDepArtifacts(
    module:             Module,
    sourcePath:         os.Path,
    targetArtifactDir:  os.Path,
    desiredExts:        List[String]
): Map[String, Int] =
  import scalascript.imports.ImportResolver
  val deps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
  val baseDir = sourcePath / os.up
  val depImports = collectImports(module.sections).filter(_.startsWith("dep:"))
  if depImports.isEmpty then return Map.empty
  os.makeDir.all(targetArtifactDir)
  val tallies = scala.collection.mutable.Map.empty[String, Int]
  for depUri <- depImports.distinct do
    val resolved =
      try Some(ImportResolver.resolve(depUri, baseDir, deps, lockPath = None))
      catch case _: Throwable => None
    resolved match
      case Some(sscPath) =>
        for ext <- desiredExts do
          ImportResolver.findArtifactAlongside(sscPath, ext) match
            case Some(art) =>
              // Validate envelope before staging — bad magic must surface
              // a clear error so the user knows the .sscpkg is broken.
              val checkResult: Either[String, Unit] = ext match
                case "scim"  =>
                  scalascript.artifact.ArtifactIO.readInterfaceFile(art).map(_ => ())
                case "scjvm" =>
                  scalascript.artifact.JvmArtifactIO.readJvmFile(art).map(_ => ())
                case "scjs"  =>
                  scalascript.artifact.JsArtifactIO.readJsFile(art).map(_ => ())
                case _       => Right(())
              checkResult match
                case Right(_) =>
                  val dest = targetArtifactDir / art.last
                  if !os.exists(dest) then
                    os.copy.over(art, dest, createFolders = true)
                  tallies(ext) = tallies.getOrElse(ext, 0) + 1
                case Left(err) =>
                  throw new RuntimeException(
                    s"compile: corrupt pre-compiled dep artifact $art: $err"
                  )
            case None => ()
      case None => ()
  tallies.toMap


/** `ssc package [<project-file>] [--target <t>] [--out <dir>] [--compiled]`
 *
 *  Builds distributable packages for all targets in `targets:` frontmatter
 *  (or a single `--target` override).  Default target: `ssc`.
 *
 *  ssc (default)  →  fat JAR at `<out>/name.jar` (interpreter-based)
 *  jvm            →  fully compiled via JvmGen → scala-cli → bytecode JAR
 *  js             →  JS bundle at `<out>/name.js`
 *  web            →  static HTML + assets in `<out>/`
 *
 *  Auto-discovers the project file by directory name when none is given,
 *  exactly like `ssc build`. */
/** `ssc package --lib [<dir>] [-o <out.ssclib>] [--manifest <file>]`
 *
 *  Pack a ScalaScript library source tree into a `.ssclib` ZIP archive.
 *
 *  - `<dir>` defaults to `os.pwd`.
 *  - Manifest is read from `<dir>/ssclib-manifest.yaml` unless overridden.
 *  - Output defaults to `<cacheId>-<version>.ssclib` in the current directory.
 *  - All files under `src/` are included; falls back to all `.ssc` in the root. */
def packageLib(args: List[String]): Unit =
  import java.util.zip.{ZipOutputStream, ZipEntry}
  import scalascript.imports.SsclibManifest

  var manifestArg: Option[String] = None
  var outputArg:   Option[String] = None
  var dirArg:      Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--manifest" if it.hasNext      => manifestArg = Some(it.next())
      case "--output" | "-o" if it.hasNext => outputArg   = Some(it.next())
      case d                               => dirArg      = Some(d)

  val dir = os.Path(dirArg.getOrElse(os.pwd.toString), os.pwd)
  if !os.isDir(dir) then
    System.err.println(s"ssc package --lib: not a directory: $dir"); System.exit(1)

  val manifestFile = manifestArg
    .map(m => os.Path(m, os.pwd))
    .getOrElse(dir / SsclibManifest.FileName)

  val manifest =
    if os.exists(manifestFile) then
      SsclibManifest.parseString(os.read(manifestFile)) match
        case scala.util.Success(m) => m
        case scala.util.Failure(e) =>
          System.err.println(s"ssc package --lib: invalid manifest: ${e.getMessage}")
          System.exit(1); ???
    else
      val libName = s"local/${dir.last}"
      SsclibManifest(name = libName)

  val outName = outputArg.getOrElse(s"${manifest.cacheId}-${manifest.version}.ssclib")
  val outPath = os.Path(outName, os.pwd)

  val srcDir = dir / "src"
  val sources: Seq[(os.Path, String)] =
    if os.exists(srcDir) && os.isDir(srcDir) then
      os.walk(srcDir).filter(os.isFile).map { f => (f, "src/" + f.relativeTo(srcDir).toString) }
    else
      os.list(dir).filter(f => os.isFile(f) && f.ext == "ssc").map { f => (f, f.last) }

  val manifestContent =
    if os.exists(manifestFile) then os.read(manifestFile)
    else SsclibManifest.toYaml(manifest)

  os.makeDir.all(outPath / os.up)
  val zip = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    zip.putNextEntry(new ZipEntry(SsclibManifest.FileName))
    zip.write(manifestContent.getBytes("UTF-8"))
    zip.closeEntry()
    sources.foreach { (file, entryName) =>
      zip.putNextEntry(new ZipEntry(entryName))
      zip.write(os.read.bytes(file))
      zip.closeEntry()
    }
  finally zip.close()

  val fileCount = sources.length + 1
  println(s"${outPath.last}  ($fileCount files) — name=${manifest.name} version=${manifest.version}")

def packageCommand(args: List[String]): Unit =
  if args.contains("--lib") then
    return packageLib(args.filterNot(_ == "--lib"))
  val compiled = args.contains("--compiled")
  val rest     = args.filterNot(_ == "--compiled")

  // Parse --target, --out, --export-method, --team-id, --distribution, --dmg/--no-dmg, --notarize/--no-notarize, positionals
  var targetFlag:       Option[String] = None
  var outFlag:          Option[String] = None
  var exportMethodFlag: String         = "development"
  var teamIdFlag:       Option[String] = None
  var distributionFlag: Boolean        = false
  var dmgFlag:          Boolean        = true
  var notarizeFlag:     Boolean        = true
  val positional = scala.collection.mutable.ListBuffer.empty[String]
  val it = rest.iterator
  while it.hasNext do
    it.next() match
      case "--target"        if it.hasNext => targetFlag       = Some(it.next())
      case "--out"           if it.hasNext => outFlag          = Some(it.next())
      case "--export-method" if it.hasNext => exportMethodFlag = it.next()
      case "--team-id"       if it.hasNext => teamIdFlag       = Some(it.next())
      case "--distribution"               => distributionFlag  = true
      case "--no-dmg"                     => dmgFlag           = false
      case "--dmg"                        => dmgFlag           = true
      case "--no-notarize"                => notarizeFlag      = false
      case "--notarize"                   => notarizeFlag      = true
      case other                          => positional += other

  val projectFile: Option[os.Path] = positional.headOption match
    case Some(arg) =>
      val p = os.Path(arg, os.pwd)
      if os.exists(p) && os.isFile(p) && p.ext == "ssc" then Some(p)
      else if !os.exists(p) || os.isFile(p) then
        val candidate = os.Path(arg.stripSuffix(".ssc") + ".ssc", os.pwd)
        if os.exists(candidate) && os.isFile(candidate) then Some(candidate) else None
      else None
    case None => findProjectSsc()

  // Legacy scala-cli mode: explicit .ssc file + --compiled flag
  if compiled then
    val sscPath = projectFile.getOrElse {
      System.err.println("ssc package --compiled: no project file found"); System.exit(1); ???
    }
    val sscFiles = List(sscPath)
    val scalaCliFlags = positional.drop(1).toList
    for path <- sscFiles do
      if !os.exists(path) then { System.err.println(s"Error: File not found: $path"); System.exit(1) }
      else
        try
          val script = expectText(compileViaBackend("jvm", path), "package")
          val tmp    = os.temp(script, suffix = ".sc")
          try
            val hasOutput = scalaCliFlags.exists(f => f == "-o" || f == "--output")
            val outputFlags =
              if hasOutput then Nil
              else List("--output", path.last.stripSuffix(".ssc"))
            val result = os.proc(
              "scala-cli", "--power", "package", tmp,
              "--server=false",
              outputFlags,
              scalaCliFlags
            ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = os.pwd, check = false)
            if result.exitCode != 0 then System.exit(result.exitCode)
          finally os.remove(tmp)
        catch case e: Exception =>
          System.err.println(s"Package error: ${e.getMessage}")
          System.exit(1)
    return

  // Project-file mode (default)
  projectFile match
    case None =>
      System.err.println("ssc package: no project file found (pass a name, name.ssc, or run from a project directory)")
      System.exit(1)
    case Some(pf) =>
      val manifest = scala.util.Try(scalascript.parser.Parser.parse(os.read(pf)).manifest).toOption.flatten
      val name     = manifest.flatMap(_.name).getOrElse(pf.last.stripSuffix(".ssc"))
      val effectiveTarget = targetFlag.orElse(ActiveFlags.current.target)
      val targets  = effectiveTarget.map(List(_))
        .orElse(manifest.map(_.targets).filter(_.nonEmpty))
        .getOrElse(List("ssc"))
      val outDir   = os.Path(outFlag.getOrElse("target/package"), os.pwd)
      os.makeDir.all(outDir)

      println(s"Packaging $name  targets: ${targets.mkString(", ")}  →  ${displayPath(outDir)}/")
      for t <- targets do
        if t == "ios" || t == "mobile-ios" then
          packageIosIpa(pf, outDir / t, exportMethodFlag, teamIdFlag)
        else if (t == "macos" || t == "desktop-macos") && distributionFlag then
          packageMacosDistribution(pf, outDir / t, teamIdFlag, dmg = dmgFlag, notarize = notarizeFlag)
        else
          buildProjectFileCommand(pf, Some(t), outDir, fat = true)

/** `ssc publish --target ios [--testflight|--appstore] [--fastlane] [--api-key-path <p>]
 *    [--submit-for-review] [--release-notes <text>] [<project.ssc>]`
 *
 *  Uploads an iOS app to TestFlight or App Store via fastlane.
 *  By default, generates a `Fastfile` in the project directory then invokes fastlane.
 *  `--fastlane` skips generation and uses the existing `Fastfile`. */
def publishCommand(args: List[String]): Unit =
  var targetFlag:          Option[String] = None
  var testflightFlag:      Boolean        = false
  var appstoreFlag:        Boolean        = false
  var fastlaneFlag:        Boolean        = false
  var apiKeyPathFlag:      Option[String] = None
  var submitForReviewFlag: Boolean        = false
  var releaseNotesFlag:    Option[String] = None
  val positional = scala.collection.mutable.ListBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--target"           if it.hasNext => targetFlag       = Some(it.next())
      case "--api-key-path"     if it.hasNext => apiKeyPathFlag   = Some(it.next())
      case "--release-notes"    if it.hasNext => releaseNotesFlag = Some(it.next())
      case "--testflight"                     => testflightFlag   = true
      case "--appstore"                       => appstoreFlag     = true
      case "--fastlane"                       => fastlaneFlag     = true
      case "--submit-for-review"              => submitForReviewFlag = true
      case other                              => positional += other

  val projectFile = positional.headOption match
    case Some(arg) =>
      val p = os.Path(arg, os.pwd)
      if os.exists(p) && p.ext == "ssc" then p
      else { System.err.println(s"ssc publish: file not found: $arg"); System.exit(1); ??? }
    case None => findProjectSsc().getOrElse {
      System.err.println("ssc publish: no project file found"); System.exit(1); ???
    }

  val effectiveTarget = targetFlag.orElse(ActiveFlags.current.target)

  effectiveTarget match
    case Some("ios") | Some("mobile-ios") =>
      if !testflightFlag && !appstoreFlag then
        System.err.println("ssc publish --target ios: specify --testflight or --appstore")
        System.exit(1)
      val lane = if appstoreFlag then "appstore" else "testflight"
      publishIosFastlane(
        projectFile, lane, fastlaneFlag, apiKeyPathFlag,
        submitForReviewFlag, releaseNotesFlag
      )
    case Some("macos") | Some("desktop-macos") =>
      if !appstoreFlag then
        System.err.println("ssc publish --target macos: specify --appstore")
        System.exit(1)
      publishMacosFastlane(projectFile, fastlaneFlag, apiKeyPathFlag, submitForReviewFlag)
    case Some(t) =>
      System.err.println(s"ssc publish: unsupported target '$t'  (valid: ios, macos)")
      System.exit(1)
    case None =>
      System.err.println("ssc publish: --target is required  (valid: ios, macos)")
      System.exit(1)

/** `ssc publish --target ios` implementation via fastlane.
 *
 *  Generates a `Fastfile` in the project directory (unless `--fastlane` is set),
 *  then runs `fastlane <lane>` in that directory.
 *
 *  Credentials:
 *   - API key: `apiKeyPath` → env `APP_STORE_CONNECT_API_KEY_PATH`
 *   - Without API key, fastlane falls back to Apple ID (interactive prompt). */
private def publishIosFastlane(
    sscFile:         os.Path,
    lane:            String,
    useExisting:     Boolean,
    apiKeyPath:      Option[String],
    submitForReview: Boolean,
    releaseNotes:    Option[String]
): Unit =
  if os.proc("fastlane", "--version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: fastlane is required for ssc publish --target ios.\n" +
      "Install: brew install fastlane"
    )
    System.exit(1)

  val module  = Parser.parse(os.read(sscFile))
  val appName = swiftAppName(module.manifest.flatMap(_.name))
  val projectDir = sscFile / os.up
  val fastfile   = projectDir / "Fastfile"

  if !useExisting then
    val content = generateFastfile(appName, submitForReview, releaseNotes)
    os.write.over(fastfile, content)
    println(s"  Generated ${displayPath(fastfile)}")
  else
    if !os.exists(fastfile) then
      System.err.println(s"ssc publish --fastlane: no Fastfile found at ${displayPath(fastfile)}")
      System.exit(1)
    println(s"  Using existing ${displayPath(fastfile)}")

  // Set API key env if provided
  val resolvedKeyPath = apiKeyPath
    .orElse(sys.env.get("APP_STORE_CONNECT_API_KEY_PATH"))
  val extraEnv = resolvedKeyPath
    .map(p => Map("APP_STORE_CONNECT_API_KEY_PATH" -> p))
    .getOrElse(Map.empty)

  println(s"  Running: fastlane $lane")
  val result = os.proc("fastlane", lane)
    .call(
      stdout = os.Inherit, stderr = os.Inherit, cwd = projectDir,
      env = extraEnv, check = false
    )
  if result.exitCode != 0 then System.exit(result.exitCode)
  println(s"  fastlane $lane completed successfully.")

/** Generate a Fastfile for iOS TestFlight + App Store lanes. */
private def generateFastfile(
    appName: String, submitForReview: Boolean, releaseNotes: Option[String]
): String =
  val submitLine = if submitForReview then "    deliver(submit_for_review: true, automatic_release: false)" else ""
  val notesBlock = releaseNotes.map(n => s"""    pilot(changelog: "$n", skip_waiting_for_build_processing: true)""")
    .getOrElse("    pilot(skip_waiting_for_build_processing: true)")
  s"""|# Generated by ssc publish --target ios
      |# Edit this file to add metadata, screenshots, signing configuration, etc.
      |# See https://docs.fastlane.tools for available actions.
      |
      |default_platform(:ios)
      |
      |platform :ios do
      |
      |  lane :testflight do
      |    gym(scheme: "$appName", export_method: "app-store")
      |$notesBlock
      |  end
      |
      |  lane :appstore do
      |    gym(scheme: "$appName", export_method: "app-store")
      |$submitLine
      |  end
      |
      |end
      |""".stripMargin

/** `ssc package --target macos --distribution` — codesign + notarize + DMG.
 *
 *  Produces a distributable, Gatekeeper-approved `.dmg` from the Swift Package.
 *  Requires: Developer ID Application certificate, Xcode 13+ (notarytool),
 *  App Store Connect API key or Apple ID for notarytool auth. */
private def packageMacosDistribution(
    sscFile:   os.Path,
    outDir:    os.Path,
    teamId:    Option[String],
    dmg:       Boolean,
    notarize:  Boolean
): Unit =
  if os.proc("xcodebuild", "-version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println("Error: Xcode is required for ssc package --target macos --distribution.")
    System.exit(1)

  val module   = Parser.parse(os.read(sscFile))
  val manifest = module.manifest
  val appName  = swiftAppName(manifest.flatMap(_.name))
  val resolvedTeamId = teamId
    .orElse(manifest.flatMap(_.raw.get("team-id").collect { case s: String => s }))
    .orElse(sys.env.get("SSC_TEAM_ID"))

  println(s"  Generating Swift package for macOS...")
  buildSwiftUIPackage(sscFile, outDir, "macos")

  val archivePath = outDir / s"$appName.xcarchive"
  val exportDir   = outDir / "export"
  val exportPlist = outDir / "ExportOptions-macos.plist"

  os.write.over(exportPlist, generateMacosExportOptionsPlist(resolvedTeamId))
  println(s"  ExportOptions-macos.plist → ${displayPath(exportPlist)}")

  println(s"  Archiving $appName for macOS...")
  val archArgs = List(
    "xcodebuild", "archive",
    "-scheme", appName,
    "-destination", "generic/platform=macOS",
    "-allowProvisioningUpdates",
    "-archivePath", archivePath.toString
  ) ++ resolvedTeamId.toList.map(id => s"DEVELOPMENT_TEAM=$id")
  val archResult = os.proc(archArgs)
    .call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if archResult.exitCode != 0 then System.exit(archResult.exitCode)

  os.makeDir.all(exportDir)
  println(s"  Exporting signed .app...")
  val exportResult = os.proc(
    "xcodebuild", "-exportArchive",
    "-archivePath",        archivePath.toString,
    "-exportPath",         exportDir.toString,
    "-exportOptionsPlist", exportPlist.toString,
    "-allowProvisioningUpdates"
  ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
  if exportResult.exitCode != 0 then System.exit(exportResult.exitCode)

  val appBundle = exportDir / s"$appName.app"

  if notarize then
    println(s"  Notarizing $appName (this may take 1-5 minutes)...")
    val apiKeyPath = sys.env.get("APP_STORE_CONNECT_API_KEY_PATH")
    val notaryArgs = List(
      "xcrun", "notarytool", "submit", appBundle.toString,
      "--wait"
    ) ++ apiKeyPath.toList.flatMap(p => List("--key", p))
    val notaryResult = os.proc(notaryArgs)
      .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    if notaryResult.exitCode != 0 then System.exit(notaryResult.exitCode)

    println(s"  Stapling notarization ticket...")
    val stapleResult = os.proc("xcrun", "stapler", "staple", appBundle.toString)
      .call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    if stapleResult.exitCode != 0 then System.exit(stapleResult.exitCode)

  if dmg then
    val dmgPath = outDir / s"$appName.dmg"
    println(s"  Creating DMG → ${displayPath(dmgPath)}...")
    val dmgResult = os.proc(
      "hdiutil", "create",
      "-volname",   appName,
      "-srcfolder", appBundle.toString,
      "-ov",
      "-format",    "UDZO",
      dmgPath.toString
    ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
    if dmgResult.exitCode != 0 then System.exit(dmgResult.exitCode)
    println(s"  .dmg → ${displayPath(dmgPath)}")
  else
    println(s"  .app → ${displayPath(appBundle)}")

/** Generate ExportOptions.plist for macOS Developer ID distribution. */
private def generateMacosExportOptionsPlist(teamId: Option[String]): String =
  val teamLine = teamId.map(id =>
    s"  <key>teamID</key>\n  <string>$id</string>\n"
  ).getOrElse("")
  s"""|<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      |<plist version="1.0">
      |<dict>
      |  <key>method</key>
      |  <string>developer-id</string>
      |$teamLine  <key>uploadSymbols</key>
      |  <true/>
      |</dict>
      |</plist>
      |""".stripMargin

/** `ssc publish --target macos --appstore` via fastlane. */
private def publishMacosFastlane(
    sscFile:         os.Path,
    useExisting:     Boolean,
    apiKeyPath:      Option[String],
    submitForReview: Boolean
): Unit =
  if os.proc("fastlane", "--version")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode != 0 then
    System.err.println(
      "Error: fastlane is required for ssc publish --target macos.\n" +
      "Install: brew install fastlane"
    )
    System.exit(1)

  val module     = Parser.parse(os.read(sscFile))
  val appName    = swiftAppName(module.manifest.flatMap(_.name))
  val projectDir = sscFile / os.up
  val fastfile   = projectDir / "Fastfile"

  if !useExisting then
    val content = generateMacosFastfile(appName, submitForReview)
    os.write.over(fastfile, content)
    println(s"  Generated ${displayPath(fastfile)}")
  else
    if !os.exists(fastfile) then
      System.err.println(s"ssc publish --fastlane: no Fastfile found at ${displayPath(fastfile)}")
      System.exit(1)
    println(s"  Using existing ${displayPath(fastfile)}")

  val resolvedKeyPath = apiKeyPath.orElse(sys.env.get("APP_STORE_CONNECT_API_KEY_PATH"))
  val extraEnv = resolvedKeyPath.map(p => Map("APP_STORE_CONNECT_API_KEY_PATH" -> p)).getOrElse(Map.empty)

  println(s"  Running: fastlane mac_appstore")
  val result = os.proc("fastlane", "mac_appstore")
    .call(stdout = os.Inherit, stderr = os.Inherit, cwd = projectDir, env = extraEnv, check = false)
  if result.exitCode != 0 then System.exit(result.exitCode)
  println(s"  fastlane mac_appstore completed successfully.")

/** Generate a Fastfile for Mac App Store lane. */
private def generateMacosFastfile(appName: String, submitForReview: Boolean): String =
  val submitLine = if submitForReview then
    "    deliver(submit_for_review: true, automatic_release: false, platform: :mac)"
  else ""
  s"""|# Generated by ssc publish --target macos --appstore
      |# See https://docs.fastlane.tools for available actions.
      |
      |default_platform(:mac)
      |
      |platform :mac do
      |
      |  lane :mac_appstore do
      |    gym(scheme: "$appName", export_method: "app-store", destination: "generic/platform=macOS")
      |$submitLine
      |  end
      |
      |end
      |""".stripMargin

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
              variant.args.get(p).map(Value.StringV.apply).getOrElse(Value.EmptyStr)
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

/** `ssc check [--iface-dir <dir>] <file.ssc> [...]`
 *
 *  Standalone type-checker for CI use.  Parses and type-checks each `.ssc`
 *  file without running the interpreter or generating code.
 *
 *  Diagnostics are written to stderr in the format:
 *    file:line:col: error: message
 *  or (when no position is available):
 *    file: error: message
 *
 *  Exit code: 0 if all files type-check cleanly, 1 if any errors were found.
 *
 *  `--iface-dir <dir>` (or `-I <dir>`) loads pre-compiled `.scim` interface
 *  files from `<dir>` and checks against them (same as `check-with-iface`
 *  but with CI-friendly output).
 */
/** Result of checking a single `.ssc` file.  Used by both human-readable and
 *  JSON output paths.
 *
 *  @param file        display path (as given by the user or discovered)
 *  @param parseErrors true when the file had a code-block parse error
 *  @param errors      type errors from the typer
 *  @param elapsedMs   wall-clock time for parse + type-check of this file
 *  @param missing     true when the file was not found on disk
 */
private case class CheckResult(
  file:        String,
  parseErrors: Boolean,
  errors:      List[scalascript.typer.TypeError],
  elapsedMs:   Long,
  missing:     Boolean = false
):
  def ok: Boolean = !missing && !parseErrors && !errors.exists(!_.isWarning)

/** Check a single `.ssc` file.  Does NOT invoke any backend (no JvmGen / JsGen /
 *  Interpreter).  Returns a [[CheckResult]] summary.
 *
 *  @param file       display name of the file (used in diagnostics)
 *  @param interfaces pre-loaded `.scim` interfaces (may be empty)
 *  @param pluginBuiltins extra built-in names from loaded backend plugins
 */
private def checkOneFile(
  file: String,
  interfaces: Map[String, scalascript.ir.ModuleInterface],
  pluginBuiltins: Set[String],
  strictNamespaces: Boolean = false
): CheckResult =
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    return CheckResult(file, parseErrors = false, errors = Nil, elapsedMs = 0L, missing = true)
  val t0 = System.currentTimeMillis()
  try
    val module = Parser.parse(os.read(path))
    // Collect code-block parse errors (structural, with position).
    var hasParseError = false
    def walkSection(s: scalascript.ast.Section): Unit =
      s.content.foreach {
        case cb: scalascript.ast.Content.CodeBlock
            if cb.tree.isEmpty && cb.parseError.isDefined =>
          hasParseError = true
        case _ => ()
      }
      s.subsections.foreach(walkSection)
    module.sections.foreach(walkSection)
    if hasParseError then
      val elapsed = System.currentTimeMillis() - t0
      CheckResult(file, parseErrors = true, errors = Nil, elapsedMs = elapsed)
    else
      val typed =
        if interfaces.isEmpty then Typer.typeCheckStrict(module, pluginBuiltins)
        else if strictNamespaces then
          Typer.typeCheckStrictNamespaces(module, interfaces)
        else Typer.typeCheckWithInterfaces(module, interfaces, strict = true, pluginBuiltins)
      val elapsed = System.currentTimeMillis() - t0
      CheckResult(file, parseErrors = false, errors = typed.errors, elapsedMs = elapsed)
  catch case e: Exception =>
    val elapsed = System.currentTimeMillis() - t0
    // Treat an unexpected exception as a parse error so the caller can assign
    // exit code 2.
    CheckResult(
      file, parseErrors = true,
      errors = List(scalascript.typer.TypeError(e.getMessage, None)),
      elapsedMs = elapsed
    )

/** Recursively walk `dir` and return paths of all `.ssc` files. */
private def collectSscFiles(dir: os.Path): List[os.Path] =
  if !os.exists(dir) || !os.isDir(dir) then Nil
  else
    os.walk(dir)
      .filter(p => os.isFile(p) && p.ext == "ssc")
      .sortBy(_.toString)
      .toList

/** Render `CheckResult` list as structured JSON.
 *
 *  Single-file form:
 *  {{{
 *    {"file":"f.ssc","errors":[...],"warnings":[],"elapsed_ms":42}
 *  }}}
 *  Multi-file form is an array of such objects.
 */
private def checkResultsToJson(results: List[CheckResult]): String =
  def escapeJson(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
     .replace("\t", "\\t")
  def diagToJson(e: scalascript.typer.TypeError): String =
    val (line, col) = e.span.map(s => (s.start.line, s.start.column)).getOrElse((0, 0))
    val sev = if e.isWarning then "warning" else "error"
    s"""{"line":$line,"col":$col,"severity":"$sev","message":"${escapeJson(e.msg)}"}"""
  def resultToJson(r: CheckResult): String =
    val trueErrors = r.errors.filter(!_.isWarning)
    val warns      = r.errors.filter(_.isWarning)
    val errJsons   = trueErrors.map(diagToJson).mkString(",")
    val warnJsons  = warns.map(diagToJson).mkString(",")
    val parseNote =
      if r.missing then s"""{"line":0,"col":0,"severity":"error","message":"file not found"}"""
      else if r.parseErrors && r.errors.isEmpty then
        s"""{"line":0,"col":0,"severity":"error","message":"parse error"}"""
      else ""
    val allErrs =
      if parseNote.nonEmpty then
        if errJsons.isEmpty then s"[$parseNote]" else s"[$parseNote,$errJsons]"
      else s"[$errJsons]"
    s"""{"file":"${escapeJson(r.file)}","errors":$allErrs,"warnings":[$warnJsons],"elapsed_ms":${r.elapsedMs}}"""
  if results.length == 1 then resultToJson(results.head)
  else "[" + results.map(resultToJson).mkString(",\n ") + "]"

def checkCommand(args: List[String]): Unit =
  import java.nio.file.{FileSystems, Paths, StandardWatchEventKinds}
  import scala.jdk.CollectionConverters.*

  var ifaceDir:         Option[os.Path] = None
  var jsonMode:         Boolean         = false
  var quietMode:        Boolean         = false
  var watchMode:        Boolean         = false
  var strictNamespaces: Boolean         = false
  val inputs = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--iface-dir" | "-I" if it.hasNext =>
        ifaceDir = Some(os.Path(it.next(), os.pwd))
      case "--json"              => jsonMode         = true
      case "--quiet"             => quietMode        = true
      case "--watch"             => watchMode        = true
      case "--strict-namespaces" => strictNamespaces = true
      case f                     => inputs += f

  if inputs.isEmpty then
    if !quietMode then
      System.err.println(
        "Usage: ssc check [--iface-dir <dir>] [--json] [--quiet] [--watch] [--strict-namespaces] <file.ssc|dir/> [...]"
      )
    System.exit(1)

  // ── Load interface files ─────────────────────────────────────────────────
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    ifaceDir match
      case None => Map.empty
      case Some(dir) =>
        if !os.isDir(dir) then
          if !quietMode then
            System.err.println(s"ssc check: --iface-dir '$dir' is not a directory")
          System.exit(1)
        os.list(dir).filter(_.ext == "scim").flatMap { p =>
          ArtifactIO.readInterfaceFile(p) match
            case Right(iface) =>
              List(p.last.stripSuffix(".scim") -> iface)
            case Left(err) =>
              if !quietMode then
                System.err.println(s"ssc check: [warn] skipping interface ${p.last}: $err")
              Nil
        }.toMap

  // ── Collect plugin built-in names ─────────────────────────────────────
  val pluginBuiltins: Set[String] =
    BackendRegistry.inProcess
      .flatMap(_.intrinsics.keys)
      .flatMap { qn =>
        val full = qn.value
        full :: full.split('.').headOption.toList
      }
      .toSet

  // ── Expand inputs: files + directories ───────────────────────────────
  def expandInputs(inList: List[String]): List[String] =
    inList.flatMap { inp =>
      val p = os.Path(inp, os.pwd)
      if os.isDir(p) then collectSscFiles(p).map(_.toString)
      else List(inp)
    }

  // ── Exit code helper ─────────────────────────────────────────────────
  def exitCodeFor(results: List[CheckResult]): Int =
    if      results.exists(_.missing)                                           then 3
    else if results.exists(r => !r.missing && r.errors.exists(!_.isWarning))   then 1
    else if results.exists(_.parseErrors)                                       then 2
    else                                                                             0

  // ── Watch mode ────────────────────────────────────────────────────────
  if watchMode then
    // In watch mode we only support a single file argument.
    val rawFile = inputs.headOption.getOrElse:
      if !quietMode then System.err.println("ssc check --watch: expected a file argument")
      System.exit(1); ""
    val absPath   = Paths.get(rawFile).toAbsolutePath.normalize
    val dir       = absPath.getParent
    val displayF  = rawFile

    def timestamp(): String =
      val now = java.time.LocalTime.now()
      f"${now.getHour}%02d:${now.getMinute}%02d:${now.getSecond}%02d"

    def runOnce(): Unit =
      val t0       = System.currentTimeMillis()
      val result   = checkOneFile(displayF, interfaces, pluginBuiltins, strictNamespaces)
      val elapsed  = System.currentTimeMillis() - t0
      val ts       = timestamp()
      if !quietMode then
        if jsonMode then
          println(s"[$ts] " + checkResultsToJson(List(result)))
        else if result.ok then
          println(s"[$ts] ${absPath.getFileName}: OK (${elapsed}ms)")
        else
          val nerrs = result.errors.size + (if result.parseErrors then 1 else 0)
          System.err.println(s"[$ts] ${absPath.getFileName}: $nerrs error(s)")
          if result.missing then
            System.err.println(s"[$ts] ${result.file}: error: file not found")
          else if result.parseErrors then
            val path = os.Path(displayF, os.pwd)
            try { val m = Parser.parse(os.read(path)); reportCodeBlockParseErrors(m, displayF) }
            catch case e: Exception => System.err.println(s"$displayF: error: ${e.getMessage}")
          else
            result.errors.foreach { e =>
              val loc = e.span.map(s => s"$displayF:${s.start.line}:${s.start.column}").getOrElse(displayF)
              System.err.println(s"[$ts] $loc: error: ${e.msg}")
            }

    if !quietMode then
      System.err.println(s"[${timestamp()}] Watching ${absPath.getFileName}... (Ctrl+C to stop)")
    runOnce()

    val watcher = FileSystems.getDefault.newWatchService()
    dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
    try
      while true do
        val key     = watcher.take()
        val changed = key.pollEvents().asScala.exists { ev =>
          ev.context().asInstanceOf[java.nio.file.Path].getFileName == absPath.getFileName
        }
        if changed then
          Thread.sleep(50) // debounce
          runOnce()
        key.reset()
    catch case _: InterruptedException => () // Ctrl-C
    return

  // ── Normal (one-shot) mode ────────────────────────────────────────────
  val fileList = expandInputs(inputs.toList)
  if fileList.isEmpty then
    if !quietMode then System.err.println("ssc check: no .ssc files found")
    System.exit(1)
  // checkOneFile is stateless (new Typer per call, immutable inputs), so safe to parallelize.
  val results: List[CheckResult] =
    if fileList.sizeIs <= 1 then fileList.map(f => checkOneFile(f, interfaces, pluginBuiltins, strictNamespaces))
    else
      val nCores = Runtime.getRuntime.availableProcessors().max(1)
      val pool   = new java.util.concurrent.ForkJoinPool(nCores.min(fileList.size))
      try
        val tasks = fileList.map(f => pool.submit[CheckResult](() => checkOneFile(f, interfaces, pluginBuiltins, strictNamespaces)))
        tasks.map(_.get())
      finally pool.shutdown()
  if !quietMode then
    if jsonMode then
      println(checkResultsToJson(results))
    else
      results.foreach { r =>
        if r.missing then
          System.err.println(s"${r.file}: error: file not found")
        else if r.parseErrors then
          val path = os.Path(r.file, os.pwd)
          try
            val module = Parser.parse(os.read(path))
            reportCodeBlockParseErrors(module, r.file)
          catch case e: Exception =>
            System.err.println(s"${r.file}: error: ${e.getMessage}")
        else if r.errors.nonEmpty then
          r.errors.foreach { e =>
            val location = e.span match
              case Some(s) => s"${r.file}:${s.start.line}:${s.start.column}"
              case None    => r.file
            val label = if e.isWarning then "warning" else "error"
            System.err.println(s"$location: $label: ${e.msg}")
          }
          if r.errors.forall(_.isWarning) then println(s"${r.file}: OK (with warnings)")
        else
          println(s"${r.file}: OK")
      }
      val errCount = results.count(r => r.missing || r.parseErrors || r.errors.exists(!_.isWarning))
      if errCount > 1 then System.err.println(s"$errCount errors found.")
  System.exit(exitCodeFor(results))

// ─────────────────────────────────────────────────────────────────────────────
// ssc link  —  v2.0 separate compilation
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc link <artifact-dir> [-o <output>] [--backend <id>]`
 *
 *  Collects compiled artifacts from `<artifact-dir>` and produces a linked
 *  result.  Two modes:
 *
 *  1. **IR-link mode** (default).  Reads `.scim` + `.scir` pairs, merges into
 *     a single `NormalizedModule`, and either writes the merged IR as
 *     `.scir` (`-o foo.scir`) or compiles via `--backend <id>` (default
 *     `int`) and prints / executes the result.
 *
 *  2. **JVM cached-source mode** — `--backend jvm` with `.scjvm` artifacts
 *     present in the dir.  Reads `.scjvm` artifacts, textually concatenates
 *     their `scalaSource` strings in alphabetical order, and either prints
 *     the combined source (`-o -`) or packages it via scala-cli into a JAR
 *     (`-o foo.jar`).
 *
 *     MVP: textual concat.  Real linker should resolve cross-module imports
 *     through bytecode mangling.  Phase 2.
 *
 *  Artifacts are processed in dependency order: the linker reads each `.scim`
 *  to determine the package, then topologically sorts them.  Cycles are
 *  detected and reported as errors.
 *
 *  v2.0 / Stage 5.
 */
def linkCommand(args: List[String]): Unit =
  import scalascript.artifact.Linker
  var outputArg:        Option[String] = None
  var backendArg:       Option[String] = None
  var bytecode:         Boolean        = false
  var sourceMap:        Boolean        = false
  var emitScalaFacade:  Boolean        = false
  val artifactDirs = scala.collection.mutable.ArrayBuffer.empty[String]
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "-o" | "--output" if it.hasNext  => outputArg  = Some(it.next())
      case "--backend"       if it.hasNext  => backendArg = Some(it.next())
      case "--bytecode"                     => bytecode = true
      case "--source-map" | "--source-maps" => sourceMap = true
      case "--emit-scala-facade"            => emitScalaFacade = true
      case d                                => artifactDirs += d

  if artifactDirs.isEmpty then
    System.err.println("Usage: ssc link <artifact-dir> [-o <output>] [--backend <id>] [--bytecode] [--source-map] [--emit-scala-facade]")
    System.exit(1)

  // `--emit-scala-facade` only makes sense paired with `--bytecode` on the
  // JVM backend — the facade `.class` files need a host JAR.  Fail fast
  // with a clear message instead of silently producing nothing.
  if emitScalaFacade && (!bytecode || backendArg.exists(_ != "jvm")) then
    System.err.println("link: --emit-scala-facade requires `--backend jvm --bytecode`")
    System.exit(1)

  // ── JVM cached-source mode ───────────────────────────────────────────────
  // If --backend jvm and the artifact dir(s) contain .scjvm files, take the
  // textual-concat path.  This bypasses the IR linker and the JvmGen
  // re-emission, splicing the per-module Scala 3 source strings directly.
  //
  // When --bytecode is ALSO set, take the bytecode-level path instead:
  // require every .scjvm to carry a classBundle and pack them into a JAR
  // directly (no scala-cli at link time).
  val bid = backendArg.orElse(ActiveFlags.current.backend).getOrElse("int")
  if bid == "jvm" then
    val scjvmFiles = artifactDirs.toList.flatMap { dir =>
      val p = os.Path(dir, os.pwd)
      if !os.isDir(p) then Nil
      else os.list(p).filter(_.ext == "scjvm").toList.sorted
    }
    if scjvmFiles.nonEmpty then
      if bytecode then
        linkJvmFromBytecode(scjvmFiles, outputArg, sourceMap, emitScalaFacade)
      else
        linkJvmFromScjvm(scjvmFiles, outputArg)
      return
  // Fall through to standard IR-link mode if no .scjvm files were found.

  // ── JS cached-source mode ────────────────────────────────────────────────
  // If --backend js and the artifact dir(s) contain .scjs files, take the
  // textual-concat path.  This bypasses the IR linker and the JsGen
  // re-emission, splicing the per-module JS source strings directly and
  // deduplicating the shared runtime preamble via longest-common-prefix.
  if bid == "js" then
    val scjsFiles = artifactDirs.toList.flatMap { dir =>
      val p = os.Path(dir, os.pwd)
      if !os.isDir(p) then Nil
      else os.list(p).filter(_.ext == "scjs").toList.sorted
    }
    // v2.0 Phase 2 — discover any `_runtime.scjs-runtime` artifacts
    // sitting next to the `.scjs` files.  Multiple dirs may each carry
    // one; we union their preambles by capability set at link time.
    val runtimeFiles = artifactDirs.toList.flatMap { dir =>
      val p = os.Path(dir, os.pwd)
      if !os.isDir(p) then Nil
      else os.list(p).filter(_.last.endsWith(".scjs-runtime")).toList.sorted
    }
    if scjsFiles.nonEmpty then
      linkJsFromScjs(scjsFiles, runtimeFiles, outputArg, sourceMap)
      return
  // Fall through to standard IR-link mode if no .scjs files were found.

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

/** Link a set of `.scjvm` artifacts by textually concatenating their
 *  `scalaSource` strings (in path-sorted order) into a single Scala 3 source.
 *
 *  Output modes:
 *  - `-o -`               : print the combined source to stdout.
 *  - `-o <foo.scala>`     : write combined source to a `.scala` file.
 *  - `-o <foo.jar>`       : write combined source to a temp `.sc`, drive
 *                           `scala-cli --power package -o <foo.jar>` on it.
 *  - (no -o)              : run the combined source via `scala-cli run`.
 *
 *  MVP limitation: textual concat.  Cross-module imports rely on the
 *  per-module emitter having already produced fully-qualified names.  A
 *  real linker resolves cross-module references through bytecode-level
 *  symbol mangling — Phase 2.
 *
 *  v2.0 — JVM incremental codegen cache. */
/** Link a set of `.scjvm` artifacts in **bytecode mode** — every artifact
 *  must carry a non-empty `classBundle`; unpack them and pack the union of
 *  their `.class` entries into a single JAR.
 *
 *  This is the v2.0 Phase 2 path: no scala-cli at link time, no source
 *  concat, no per-link re-compilation.  Real `.class` bytes go straight
 *  from each `.scjvm`'s `classBundle` into the JAR.
 *
 *  Output modes:
 *  - `-o <foo.jar>`  : write a JAR at the given path (the only supported
 *                      output in MVP — bytecode-mode linking has no use
 *                      for `-o -` source dumping).
 *  - (no -o)         : write to `out.jar` in the current working directory.
 *
 *  Duplicate FQN handling: when two `.scjvm` artifacts ship the same
 *  class entry (typically the shared runtime preamble's helpers — the
 *  per-module emitter inlines them into every module's emitted source so
 *  scala-cli compiles them once per module), the FIRST occurrence in the
 *  alphabetically-sorted artifact list wins.  Discarded duplicates are
 *  reported as a brief summary on stderr.
 *
 *  v2.0 — Phase 2 bytecode-level JVM linker. */
private def linkJvmFromBytecode(
    scjvmFiles:       List[os.Path],
    outputArg:        Option[String],
    sourceMap:        Boolean = false,
    emitScalaFacade:  Boolean = false
): Unit =
  // 1. Read every artifact, validate classBundle presence, accumulate
  //    (moduleId, base64) pairs in the order the user supplied.
  val artifacts = scala.collection.mutable.ArrayBuffer.empty[scalascript.ir.ModuleJvmArtifact]
  var hasError  = false
  for p <- scjvmFiles do
    JvmArtifactIO.readJvmFile(p) match
      case Right(a) => artifacts += a
      case Left(e)  =>
        System.err.println(s"link --backend jvm --bytecode: failed to read ${p.last}: $e")
        hasError = true
  if hasError then System.exit(1)
  if artifacts.isEmpty then
    System.err.println("link --backend jvm --bytecode: no .scjvm artifacts found")
    System.exit(1)

  val missingBundles = artifacts.toList.filter(_.classBundle.forall(_.isEmpty))
  if missingBundles.nonEmpty then
    System.err.println(s"link --backend jvm --bytecode: " +
      s"${missingBundles.length} module(s) have no classBundle:")
    missingBundles.foreach { a =>
      System.err.println(s"  - ${a.moduleId}: recompile with `ssc compile-jvm --bytecode`")
    }
    System.exit(1)

  // 2. Resolve output path.  Default to ./out.jar when no -o is given so
  //    the command always produces a tangible artifact.
  val outPath: os.Path = outputArg match
    case Some(out) if out.endsWith(".jar") => os.Path(out, os.pwd)
    case Some(out) =>
      System.err.println(s"link --backend jvm --bytecode: -o output must end with .jar, got: $out")
      System.exit(1)
      os.pwd / "out.jar" // unreachable; satisfies type checker
    case None =>
      os.pwd / "out.jar"

  // 3. v2.0 Phase 2 — collect shared runtime bundles from every distinct
  //    artifact dir that contains a `_runtime.scjvm-runtime` next to its
  //    `.scjvm` files.  A `.scjvm` is "split-runtime" when its emitted
  //    Scala source references the shared runtime package (`import
  //    _ssc_runtime.…`); any such module REQUIRES the shared bundle
  //    because the per-module classBundle ships only user code.
  //    Pre-Phase-2 `.scjvm` artifacts (full preamble baked into their
  //    own classBundle and no `_ssc_runtime` reference) work unchanged.
  val artifactDirs: List[os.Path] = scjvmFiles.map(_ / os.up).distinct
  val needsSharedRuntime = artifacts.exists(a =>
    a.scalaSource.contains("_ssc_runtime") || a.capabilities.nonEmpty
  )
  val runtimeBundles = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
  if needsSharedRuntime then
    for d <- artifactDirs do
      val rtPath = d / "_runtime.scjvm-runtime"
      if os.exists(rtPath) then
        JvmArtifactIO.readRuntimeFile(rtPath) match
          case Right(rt) =>
            if rt.classBundle.nonEmpty then
              runtimeBundles += (s"_ssc_runtime@${d.last}" -> rt.classBundle)
          case Left(err) =>
            System.err.println(s"link --backend jvm --bytecode: " +
              s"failed to read ${rtPath.last}: $err")
            System.exit(1)
    if runtimeBundles.isEmpty then
      val splitModules = artifacts.filter(a =>
        a.scalaSource.contains("_ssc_runtime") || a.capabilities.nonEmpty)
      System.err.println(s"link --backend jvm --bytecode: " +
        s"${splitModules.length} module(s) reference the shared runtime " +
        s"but no _runtime.scjvm-runtime was found in their artifact dir(s):")
      artifactDirs.foreach(d => System.err.println(s"  - $d"))
      System.err.println("  Recompile with `ssc compile-jvm --bytecode` to " +
        "regenerate the shared runtime, or `ssc compile-runtime`.")
      System.exit(1)

  // 4. Pack runtime first (so its classes win on duplicate FQN, which
  //    matters when a module accidentally redefines `_show` etc.), then
  //    the per-module bundles in the order given.
  val bundles = runtimeBundles.toList ++ artifacts.toList.map(a => a.moduleId -> a.classBundle.get)

  // v2.0 Phase 4 (Option A) — when `--source-map` is set, build a JSR-45
  // SMAP for every module that has a populated `lineMap` and inject it
  // into the module's `.class` files just before they're packed into
  // the JAR.  Modules without a `lineMap` (pre-Phase-4 artifacts) skip
  // the rewrite — the linker continues to ship Option B sidecar
  // `.ssc.scala` files alongside the JAR as the IDE-friendly fallback.
  val smapByModule: Map[String, String] =
    if !sourceMap then Map.empty
    else
      val builder = scala.collection.mutable.Map.empty[String, String]
      var warned  = false
      for a <- artifacts.toList do
        val safeName = a.moduleId.replaceAll("[^A-Za-z0-9_.-]", "_") match
          case ""   => "Main"
          case s    => if s.head.isDigit then "M" + s else s
        if a.lineMap.isEmpty then
          if !warned then
            System.err.println(s"link --backend jvm --bytecode --source-map: " +
              s"${a.moduleId} has no lineMap (pre-Phase-4 artifact); " +
              s"recompile with `ssc compile-jvm --bytecode` for SMAP support.")
            warned = true
        else
          val intMap: Map[Int, Int] = a.lineMap.flatMap { (k, v) =>
            scala.util.Try(k.toInt).toOption.map(_ -> v)
          }
          val sscFileName = s"${safeName}.ssc"
          val smap = JvmSmap.build(safeName, sscFileName, intMap)
          builder(a.moduleId) = smap
      builder.toMap

  // v2.0 interop Tier 4 — `--emit-scala-facade`.
  //
  // When set, we additionally:
  //   1. extract every bundle (user + runtime) to a temp classpath dir
  //      so the facade compile can resolve `_ssc_runtime.<mangled>`;
  //   2. read `.scim` files from each artifact dir (one per module);
  //   3. run FacadeGenerator → Map[relPath, ScalaSource];
  //   4. compile the result via Scala3Driver;
  //   5. pack everything (user bundles, runtime bundles, facade .class
  //      files, .scim resources under META-INF/scalascript/) into the
  //      final JAR.
  //
  // Failure mid-flight surfaces a clear error and exits non-zero so the
  // user knows the facade build failed independently of the user-code
  // pack (which has already validated up to this point).
  var facadeClassDir: Option[os.Path] = None
  var facadeClasspathDir: Option[os.Path] = None
  var scimResources:  List[(String, Array[Byte])] = Nil
  var facadeEntries:  Int = 0
  if emitScalaFacade then
    // Only extract RUNTIME bundles to the facade-compile classpath.
    // User-code bundles get wrapped by JvmGen in `object pkg: object subpkg:
    // <defs>` (Phase-2 split-runtime emit), so their bytecode declares e.g.
    // `object demo` at the empty package.  If the facade's
    // `package demo.a: export Ssc.demo_a_add as add` block compiled with
    // those user classes on cp, scalac would reject the file with
    // "It cannot be used at the same time as the name of a package."
    // The runtime alone is enough to resolve `Ssc.x` references.
    val cpDir = os.temp.dir(prefix = "ssc-facade-cp-")
    facadeClasspathDir = Some(cpDir)
    val runtimeOnly = bundles.filter((moduleId, _) => moduleId.startsWith("_ssc_runtime"))
    for (moduleId, base64Zip) <- runtimeOnly do
      val safeId = moduleId.replaceAll("[^A-Za-z0-9_.-]", "_")
      JvmBytecode.extractBundleTo(base64Zip, cpDir / safeId)

    val scimFiles = artifactDirs.flatMap { d =>
      if !os.isDir(d) then Nil
      else os.list(d).filter(_.ext == "scim").toList.sorted
    }
    val ifaces = scimFiles.flatMap { p =>
      ArtifactIO.readInterfaceFile(p).toOption
    }
    if ifaces.nonEmpty then
      val facadeSources = scalascript.interop.facade.FacadeGenerator
        .generateFromInterfaces(ifaces)
      if facadeSources.isEmpty then
        System.err.println("link --emit-scala-facade: warning — no facade entries " +
          "produced from .scim files (all exports may be too deeply nested for v0.1)")
      else
        val cpDirs: List[os.Path] = (cpDir :: os.list(cpDir).filter(os.isDir).toList).distinct
        JvmBytecode.compileFacade(facadeSources, cpDirs) match
          case Right(out) =>
            facadeClassDir = Some(out)
            facadeEntries  = facadeSources.size
          case Left(err) =>
            // Best-effort: failing the facade compile is NOT fatal.  The
            // META-INF/.scim resources still ship below so
            // `ScalascriptLoader.fromJar` keeps working; the consumer
            // just falls back to reflective dispatch instead of
            // compile-time `export`-aliased imports.
            //
            // The most common cause today is that JvmGen wraps user code
            // in `object pkg: object subpkg:` at the empty package level
            // (Phase-2 split-runtime emit), and the facade's
            // `package pkg.subpkg: export ...` block can't reference
            // names there — Scala 3 disallows object/package name
            // clashes AND empty-package-to-named-package references.
            // Fixing this is a JvmGen refactor (emit `package pkg.subpkg:`
            // for `package:`-decorated modules) tracked separately.
            System.err.println(s"link --emit-scala-facade: facade compile failed " +
              s"(continuing with META-INF/.scim only):\n$err")
            facadeClassDir = None
    else
      System.err.println("link --emit-scala-facade: warning — no .scim files " +
        "found in artifact dir(s); facade table will be absent from the JAR")

    // Embed every .scim as META-INF/scalascript/<basename>.scim so that
    // scalascript.interop.loader.ScalascriptLoader.fromJar(...) can pick
    // the facade table up at runtime.
    scimResources = scimFiles.map { p =>
      val entryName = s"META-INF/scalascript/${p.last}"
      entryName -> os.read.bytes(p)
    }

  val (uniqueClasses, duplicates) =
    if !emitScalaFacade && smapByModule.isEmpty then
      JvmBytecode.packBundlesAsJar(bundles, outPath)
    else if !emitScalaFacade then
      JvmBytecode.packBundlesAsJarWithSmap(bundles, smapByModule, outPath)
    else
      JvmBytecode.packBundlesAsJarWithFacade(
        bundles        = bundles,
        smapByModule   = smapByModule,
        facadeClassDir = facadeClassDir,
        scimResources  = scimResources,
        outJar         = outPath
      )

  // Clean up facade temp dirs after pack — packer has copied what it needed.
  facadeClassDir.foreach(d => scala.util.Try(os.remove.all(d)))
  facadeClasspathDir.foreach(d => scala.util.Try(os.remove.all(d)))

  val rtNote = if runtimeBundles.isEmpty then ""
               else s" (incl. ${runtimeBundles.length} shared runtime bundle(s))"
  val facadeNote =
    if !emitScalaFacade then ""
    else s"; facade: $facadeEntries package(s), ${scimResources.size} META-INF/.scim resource(s)"
  println(s"Linked ${artifacts.size} .scjvm artifact(s) → $outPath$rtNote$facadeNote " +
    s"($uniqueClasses unique class(es); JAR size ${os.size(outPath)} bytes)")
  if duplicates.nonEmpty then
    println(s"  (deduplicated ${duplicates.size} duplicate class entries; first-write-wins)")

  // v2.0 Phase 4 — source-map support.
  //
  // Option B (sidecar `.ssc.scala` next to the JAR): kept on by default
  // whenever `--source-map` is set.  IDE source-attachment works against
  // a `.scala` file whose name matches the `.class` files' SourceFile
  // attribute (`<moduleId>_sc.scala` today) — IntelliJ falls back to
  // whole-file source navigation.
  //
  // Option A (JSR-45 `SourceDebugExtension` injection): wired through
  // `packBundlesAsJarWithSmap` above when modules carry a `lineMap`.
  // After Option A injection, `java -jar out.jar` stack traces resolve
  // to `<moduleId>.ssc:<line>` instead of `<moduleId>_sc.scala:<line>`
  // — even WITHOUT the sidecar present.
  if sourceMap then
    val outDir = outPath / os.up
    var written = 0
    for a <- artifacts.toList do
      val safeName = a.moduleId.replaceAll("[^A-Za-z0-9_.-]", "_") match
        case ""   => "Main"
        case s    => if s.head.isDigit then "M" + s else s
      val sidecar = outDir / s"$safeName.ssc.scala"
      os.write.over(sidecar, a.scalaSource)
      written += 1
    val smapNote =
      if smapByModule.isEmpty then ""
      else s"; ${smapByModule.size} module(s) carry SMAP for SourceDebugExtension stack traces"
    println(s"  Source-map sidecars written ($written .ssc.scala file(s) " +
      s"next to ${outPath.last}) for IDE source attachment$smapNote.")

private def linkJvmFromScjvm(
    scjvmFiles: List[os.Path],
    outputArg:  Option[String]
): Unit =
  // Read all artifacts; bail on the first envelope mismatch so a stale
  // artifact can't silently pollute the combined source.
  val artifacts = scala.collection.mutable.ArrayBuffer.empty[scalascript.ir.ModuleJvmArtifact]
  var hasError  = false
  for p <- scjvmFiles do
    JvmArtifactIO.readJvmFile(p) match
      case Right(a) => artifacts += a
      case Left(e)  =>
        System.err.println(s"link: failed to read ${p.last}: $e")
        hasError = true
  if hasError then System.exit(1)
  if artifacts.isEmpty then
    System.err.println("link --backend jvm: no .scjvm artifacts found")
    System.exit(1)

  // MVP: textual concat.  Each .scjvm's `scalaSource` is a complete Scala
  // 3 script — we strip duplicate `//> using` directives so scala-cli
  // doesn't reject the combined file with "duplicate directive".  Real
  // linking would resolve symbols at IR level; here we trust the per-module
  // emitter and rely on Scala's own scoping rules.
  val combined = mergeScalaSources(artifacts.toList.map(_.scalaSource))

  println(s"Linked ${artifacts.size} .scjvm artifact(s) into combined Scala source " +
    s"(${combined.linesIterator.length} lines)")

  outputArg match
    case Some("-") =>
      println(combined)

    case Some(out) if out.endsWith(".scala") || out.endsWith(".sc") =>
      val outPath = os.Path(out, os.pwd)
      os.makeDir.all(outPath / os.up)
      os.write.over(outPath, combined)
      println(s"Combined Scala source written to $outPath")

    case Some(out) if out.endsWith(".jar") =>
      val outPath = os.Path(out, os.pwd)
      os.makeDir.all(outPath / os.up)
      val tmp = os.temp(combined, suffix = ".sc", deleteOnExit = true)
      try
        val res = os.proc(
          "scala-cli", "--power", "package", tmp.toString,
          "-o", outPath.toString, "--assembly", "--force",
          "--server=false"
        ).call(stdout = os.Inherit, stderr = os.Inherit, check = false)
        if res.exitCode != 0 then
          System.err.println(s"link --backend jvm: scala-cli package failed with exit ${res.exitCode}")
          System.exit(res.exitCode)
        println(s"JAR written to $outPath")
      catch case e: Exception =>
        System.err.println(s"link --backend jvm: scala-cli invocation failed: ${e.getMessage}")
        System.exit(1)

    case Some(out) =>
      System.err.println(s"link --backend jvm: -o output must end with .scala, .sc, .jar, or be '-', got: $out")
      System.exit(1)

    case None =>
      // No output: run the combined source via scala-cli run.
      val tmp = os.temp(combined, suffix = ".sc", deleteOnExit = true)
      try
        val res = os.proc("scala-cli", "run", tmp, "--server=false").call(
          stdout = os.Inherit, stderr = os.Inherit, check = false
        )
        if res.exitCode != 0 then System.exit(res.exitCode)
      catch case e: Exception =>
        System.err.println(s"link --backend jvm: scala-cli run failed: ${e.getMessage}")
        System.exit(1)

/** Merge multiple Scala 3 sources produced by `JvmGen.generate` into a single
 *  combined source suitable for scala-cli.
 *
 *  Two layers of deduplication, both consequences of the MVP's "textual
 *  concat" strategy:
 *
 *  1. **`//> using` directives** — every module's preamble redeclares them;
 *     concatenating verbatim makes scala-cli reject the combined file
 *     ("duplicate directive").  Keep one copy of each, in first-seen order.
 *
 *  2. **Shared runtime prefix** — `JvmGen.generate` emits a large block of
 *     identical runtime helpers (`_show`, `commonRuntime`, `generatorRuntime`,
 *     `htmlDslTagBindings`, optional `effectsRuntime` / `mutualTcoRuntime` /
 *     etc.) before the user code.  When concatenating multiple modules
 *     verbatim every `val br = _Tag("br", voidTag = true)` is emitted N
 *     times, producing "Double definition" errors.  Strip the longest
 *     common prefix shared by every source (minus the directives), emit it
 *     once, then append each module's distinct tail.
 *
 *  This works because the runtime portion of `JvmGen.generate` is
 *  deterministic and identical across modules emitted by the same compiler
 *  version — content-hash identity is what makes the common-prefix trick
 *  safe.  Phase 2 will replace this with bytecode-level cross-module
 *  symbol mangling so the runtime isn't shipped per module at all. */
private def mergeScalaSources(sources: List[String]): String =
  // Step 1 — directive dedup.  Strip leading `//> using` lines from each
  // source and accumulate into a single LinkedHashSet (first-seen order).
  val seenDirectives = scala.collection.mutable.LinkedHashSet.empty[String]
  val tails          = scala.collection.mutable.ArrayBuffer.empty[String]
  for src <- sources do
    val (directives, rest) = src.linesWithSeparators.span(_.trim.startsWith("//>"))
    directives.foreach(d => seenDirectives += d.stripLineEnd)
    tails += rest.mkString

  // Step 2 — common-prefix dedup across `tails`.
  val shared = longestCommonPrefix(tails.toList)

  val sb = new StringBuilder
  seenDirectives.foreach(d => sb.append(d).append('\n'))
  if seenDirectives.nonEmpty then sb.append('\n')
  if shared.nonEmpty then
    sb.append(shared)
    if !shared.endsWith("\n") then sb.append('\n')
    sb.append('\n')
  tails.foreach { t =>
    val unique = t.drop(shared.length)
    if unique.nonEmpty then
      sb.append(unique)
      if !unique.endsWith("\n") then sb.append('\n')
      sb.append('\n')
  }

  // Step 3 — safety-net dedup of duplicate top-level declarations.  The
  // longest-common-prefix pass only works when every module's preamble is
  // byte-identical.  As soon as module A uses `effect Foo:` (so its
  // `.scjvm` includes `effectsRuntime`) and module B doesn't, the shared
  // prefix is truncated and the runtime helpers (e.g., `_handle`) appear
  // twice in the concatenated tail.  Walk the combined source and drop
  // any top-level `def`/`val`/`class`/… whose name has already been seen.
  dedupTopLevelDefs(sb.toString, lang = "scala")

/** Link a set of `.scjs` artifacts by textually concatenating their
 *  `jsSource` strings (in path-sorted order) into a single JS source.
 *
 *  Output modes:
 *  - `-o -`               : print the combined source to stdout.
 *  - `-o <foo.js>`        : write combined source to a `.js` file.
 *  - (no -o)              : run via `node` if available, otherwise print to
 *                           stdout.
 *
 *  MVP limitation: textual concat.  This is sound for JS because there is
 *  no module-level scoping in classic-script mode — every emitted `function`
 *  / `const` lands in the same global namespace.  Cross-module references
 *  rely on the per-module emitter having produced fully-qualified or shared-
 *  global names.  Phase 2 will replace this with ES-module imports or
 *  bytecode-level cross-module symbol mangling.
 *
 *  The dedup pass relies on identical runtime preambles across `.scjs`
 *  files — every `.scjs` emitted by the same compiler version embeds the
 *  same `JsRuntime` + `JsRuntimeAsync` + … prefix.  A longest-common-prefix
 *  scan over the per-module sources (whole-lines only) lifts that prefix
 *  out of the concat, emits it once at the top of the combined output,
 *  then appends each module's distinct tail.
 *
 *  v2.0 — JS incremental codegen cache. */
private def linkJsFromScjs(
    scjsFiles:    List[os.Path],
    runtimeFiles: List[os.Path],
    outputArg:    Option[String],
    sourceMap:    Boolean = false
): Unit =
  // Read all artifacts; bail on the first envelope mismatch so a stale
  // artifact can't silently pollute the combined source.
  val artifacts = scala.collection.mutable.ArrayBuffer.empty[scalascript.ir.ModuleJsArtifact]
  var hasError  = false
  for p <- scjsFiles do
    JsArtifactIO.readJsFile(p) match
      case Right(a) => artifacts += a
      case Left(e)  =>
        System.err.println(s"link: failed to read ${p.last}: $e")
        hasError = true
  if hasError then System.exit(1)
  if artifacts.isEmpty then
    System.err.println("link --backend js: no .scjs artifacts found")
    System.exit(1)

  // v2.0 Phase 2 — runtime-aware link path.  When at least one
  // `_runtime.scjs-runtime` exists in the artifact dirs, all `.scjs`
  // files are expected to carry user-only `jsSource` (post-split-runtime
  // emit).  Concatenate the runtime preamble ONCE at the head of the
  // output, then each module's user-only `jsSource` in path-sorted order.
  //
  // When NO runtime artifact is present, fall back to the legacy LCP
  // dedup path — older `.scjs` files were emitted by v2.0 MVP and ship
  // the full preamble inside `jsSource`.
  val runtimeArts = scala.collection.mutable.ArrayBuffer.empty[scalascript.ir.ModuleJsRuntimeArtifact]
  for p <- runtimeFiles do
    JsArtifactIO.readRuntimeFile(p) match
      case Right(rt) => runtimeArts += rt
      case Left(e)   =>
        System.err.println(s"link: failed to read ${p.last}: $e")
        hasError = true
  if hasError then System.exit(1)

  // v2.0 Phase 4 — when `--source-map` is set we track per-module line
  // ranges so we can emit a V3 source map alongside `out.js`.  Each
  // tracked module records `(moduleId, sscPath, startLineInclusive,
  // endLineExclusive)` describing the rows of the combined output that
  // came from that module's `jsSource`.  Runtime / preamble / wrapper
  // lines fall in the gaps and are left unmapped in the resulting `.map`.
  case class ModuleRange(moduleId: String, sscPath: String, startLine: Int, endLine: Int)
  val moduleRanges = scala.collection.mutable.ArrayBuffer.empty[ModuleRange]

  // Re-resolve the source `.ssc` filename for each artifact.  When the
  // artifact dir contains a sibling `<moduleId>.ssc` (the typical layout
  // for `compile-js a.ssc -o artifacts/a.scjs`), use that path; otherwise
  // fall back to `<moduleId>.ssc` relative to the link output.  The
  // resolved string is what appears in the source map's `sources` array.
  def resolveSscPath(art: scalascript.ir.ModuleJsArtifact, artifactDir: os.Path): String =
    val candidates = List(
      artifactDir / s"${art.moduleId}.ssc",
      artifactDir / os.up / s"${art.moduleId}.ssc"
    )
    candidates.find(os.exists) match
      case Some(p) => p.toString
      case None    => s"${art.moduleId}.ssc"

  // Build a map artifact → resolved `.ssc` path.
  val sscPathFor: Map[String, String] =
    artifacts.zip(scjsFiles).map { (a, p) =>
      a.moduleId -> resolveSscPath(a, p / os.up)
    }.toMap

  // Track how many lines we've appended so far to compute ranges.
  val sb = new StringBuilder
  var generatedLines = 0
  def append(text: String): Unit =
    sb.append(text)
    if !text.endsWith("\n") then sb.append('\n')
  def linesIn(s: String): Int =
    // Count the number of newlines that the appended block contributes
    // to the combined output.  We always append a trailing newline if
    // missing, so the lines added == # of '\n' in the trimmed-trailing-\n
    // block plus 1.
    if s.isEmpty then 0
    else
      val trimmed = if s.endsWith("\n") then s.dropRight(1) else s
      trimmed.count(_ == '\n') + 1

  val combined =
    if runtimeArts.nonEmpty then
      // Pick the runtime with the widest capability set — when multiple
      // artifact dirs each ship one, the one covering everyone's needs
      // wins.  When two cover disjoint subsets, fall back to LCP-dedup
      // of their `jsSource` strings to recover the shared core.
      val widest = runtimeArts.maxBy(_.capabilities.size)
      append(widest.jsSource)
      generatedLines += linesIn(widest.jsSource)
      val marker = "// ── scalascript user code ───────────────────────────────────────────"
      sb.append(marker).append('\n')
      generatedLines += 1
      artifacts.toList.foreach { a =>
        if a.jsSource.nonEmpty then
          val start = generatedLines
          append(a.jsSource)
          generatedLines += linesIn(a.jsSource)
          moduleRanges += ModuleRange(
            moduleId  = a.moduleId,
            sscPath   = sscPathFor.getOrElse(a.moduleId, s"${a.moduleId}.ssc"),
            startLine = start,
            endLine   = generatedLines
          )
      }
      // Flush the `_output` buffer at the end of the combined script —
      // `_println` appends to `_output` rather than printing directly so
      // a final emit-time flush is required (mirrors the per-segment
      // flush that `emit-js` injects after each ScalaScript block).  On
      // Node this writes to stdout; in a browser SPA the patch overrides
      // `serve(...)` and flushes via `console.log` on each route render.
      sb.append("process.stdout.write(_output.join('\\n') + (_output.length ? '\\n' : '')); _output = [];\n")
      generatedLines += 1
      sb.toString
    else
      // Legacy v2.0 MVP path — every `.scjs` carries the full preamble.
      // LCP-dedup lifts the shared prefix.  We can't accurately attribute
      // ranges per module here (the LCP step rewrites byte offsets), so
      // we approximate: each module's UNIQUE tail (its `jsSource` minus
      // the shared prefix) lands in order after the prefix.
      val sources = artifacts.toList.map(_.jsSource)
      val shared  = longestCommonPrefix(sources)
      sb.append(shared)
      if shared.nonEmpty && !shared.endsWith("\n") then sb.append('\n')
      val preambleLines =
        if shared.isEmpty then 0
        else if shared.endsWith("\n") then shared.count(_ == '\n')
        else shared.count(_ == '\n') + 1
      generatedLines += preambleLines
      artifacts.toList.foreach { a =>
        val unique = a.jsSource.drop(shared.length)
        if unique.nonEmpty then
          val start = generatedLines
          append(unique)
          generatedLines += linesIn(unique)
          moduleRanges += ModuleRange(
            moduleId  = a.moduleId,
            sscPath   = sscPathFor.getOrElse(a.moduleId, s"${a.moduleId}.ssc"),
            startLine = start,
            endLine   = generatedLines
          )
      }
      dedupTopLevelDefs(sb.toString, lang = "js")

  if runtimeArts.nonEmpty then
    println(s"Linked ${artifacts.size} .scjs + 1 shared runtime " +
      s"(capabilities: ${runtimeArts.maxBy(_.capabilities.size).capabilities.mkString(", ")}) " +
      s"into combined JS source (${combined.linesIterator.length} lines)")
  else
    println(s"Linked ${artifacts.size} .scjs artifact(s) into combined JS source " +
      s"(${combined.linesIterator.length} lines)")

  // Helper: when `--source-map` is set and we're writing to a real
  // `out.js` path, also emit `out.js.map` next to it and append the
  // `//# sourceMappingURL` pragma to `out.js`.  Returns the contents to
  // actually write (which may carry the trailing pragma).
  def maybeWriteSourceMap(outPath: os.Path, content: String): String =
    if !sourceMap then content
    else
      val mapPath = os.Path(outPath.toString + ".map")
      val builder = new SourceMapV3.Builder(outPath.last)
      // Pre-register sources in stable order so the resulting indices
      // match the order modules appear in `moduleRanges`.
      moduleRanges.toList.foreach(r => builder.registerSource(r.sscPath))
      val rangeByLine: Array[Option[ModuleRange]] =
        // Lookup table: generated-line → owning module range, if any.
        val arr = Array.fill[Option[ModuleRange]](combined.linesIterator.length)(None)
        moduleRanges.foreach { r =>
          var i = r.startLine
          while i < r.endLine && i < arr.length do
            arr(i) = Some(r)
            i += 1
        }
        arr
      var line = 0
      while line < rangeByLine.length do
        rangeByLine(line) match
          case None    => builder.addUnmappedLines(1)
          case Some(r) =>
            // Map generated line → source line within the module's `.ssc`.
            // We don't have per-line origin tracking on the emitted JS
            // (the `.scjs` doesn't carry source positions), so we use a
            // coarse line-N-in-module → line-N-in-source mapping clamped
            // at line 0.  This is line-granularity by design — the task
            // brief explicitly accepts coarse-grained mappings here.
            val offsetInModule = line - r.startLine
            builder.addMappedLine(r.sscPath, offsetInModule)
        line += 1
      val json = builder.toJson()
      os.write.over(mapPath, json)
      println(s"Source map written to $mapPath " +
        s"(${builder.sources.length} source(s); " +
        s"${combined.linesIterator.length} generated line(s))")
      val pragma = s"//# sourceMappingURL=${mapPath.last}\n"
      val joined =
        if content.endsWith("\n") then content + pragma
        else content + "\n" + pragma
      joined

  outputArg match
    case Some("-") =>
      // `-o -` always prints to stdout; source-map generation in
      // streaming mode is meaningless (no path to point a pragma at)
      // so we silently skip the map.
      println(combined)

    case Some(out) if out.endsWith(".js") =>
      val outPath = os.Path(out, os.pwd)
      os.makeDir.all(outPath / os.up)
      val finalContent = maybeWriteSourceMap(outPath, combined)
      os.write.over(outPath, finalContent)
      println(s"Combined JS source written to $outPath")

    case Some(out) =>
      System.err.println(s"link --backend js: -o output must end with .js or be '-', got: $out")
      System.exit(1)

    case None =>
      // No output: run via `node` if available, otherwise print to stdout.
      // Source-map generation in "no -o" mode is also a no-op for the
      // same reason as `-o -` — there's no on-disk path to anchor it.
      val nodeAvailable = scala.util.Try {
        os.proc("node", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
      }.getOrElse(false)
      if nodeAvailable then
        val tmp = os.temp(combined, suffix = ".js", deleteOnExit = true)
        try
          val res = os.proc("node", tmp.toString).call(
            stdout = os.Inherit, stderr = os.Inherit, check = false
          )
          if res.exitCode != 0 then System.exit(res.exitCode)
        catch case e: Exception =>
          System.err.println(s"link --backend js: node invocation failed: ${e.getMessage}")
          System.exit(1)
      else
        println(combined)

/** Merge multiple JS sources produced by `JsGen.generate` (wrapped with the
 *  runtime preamble inside `.scjs`) into a single combined source suitable
 *  for `node` or browser script-tag inclusion.
 *
 *  The runtime portion of `.scjs` source is identical across modules from
 *  the same compiler version, so a whole-line longest-common-prefix scan
 *  lifts it out cleanly.  Concretely: the LCP across all modules' source
 *  strings contains the runtime preamble (and the marker line that
 *  separates it from user code).  Each module's unique tail is appended
 *  in path-sorted order after the shared prefix is emitted once.
 *
 *  This works because JS has a single global namespace in classic script
 *  mode — concatenating per-module `function add(...) { ... }` /
 *  `const Foo = { ... }` statements puts every name in the same scope,
 *  matching how `emit-js` already builds whole-program output today.
 *  Phase 2: real linking would use ES modules + named exports rather
 *  than relying on global hoisting.  v2.0 MVP. */
private def mergeJsSources(sources: List[String]): String =
  if sources.isEmpty then ""
  else if sources.size == 1 then sources.head
  else
    val shared = longestCommonPrefix(sources)
    val sb = new StringBuilder
    if shared.nonEmpty then
      sb.append(shared)
      if !shared.endsWith("\n") then sb.append('\n')
    sources.foreach { s =>
      val unique = s.drop(shared.length)
      if unique.nonEmpty then
        sb.append(unique)
        if !unique.endsWith("\n") then sb.append('\n')
    }
    // Safety-net dedup — see `mergeScalaSources` for rationale.  Modules
    // with divergent runtime preambles (effects vs. plain, async vs.
    // sync, …) cause the prefix-dedup to leave duplicate top-level
    // `function`/`const`/`class` declarations in the concatenated tail.
    dedupTopLevelDefs(sb.toString, lang = "js")

/** Safety-net deduplicator for the textually-concatenated link output.
 *
 *  The longest-common-prefix pass in [[mergeScalaSources]] /
 *  [[mergeJsSources]] only lifts the runtime preamble cleanly when every
 *  module's preamble is byte-identical.  As soon as one module uses
 *  `effect Foo:` (triggering `effectsRuntime`) and another doesn't, the
 *  shared prefix is truncated and the runtime helpers — `_handle`,
 *  `_perform`, `_Computation`, … — end up duplicated in the concatenated
 *  tail.  scalac / node then refuses the source with "Double definition".
 *
 *  This pass walks the combined source line-by-line, tracks which
 *  top-level declarations (column-0 `def`/`val`/`class`/`object`/`trait`/
 *  `enum`/`type` for Scala; `function`/`const`/`let`/`var`/`class` for
 *  JS) have already been emitted, and skips every subsequent definition
 *  with the same leading name.  Inner declarations (anything indented) are
 *  left untouched — they're scoped under their enclosing block and can't
 *  collide at the file level.
 *
 *  Skip strategy per top-level decl:
 *   1. If the def-opening line contains `{`, track brace depth from that
 *      point and skip until depth returns to 0.
 *   2. Otherwise (Scala 3 indentation syntax), skip until the next
 *      column-0 non-blank line — which is either another top-level decl
 *      or the end of file.
 *
 *  This is conservative: when a line doesn't match any known top-level
 *  pattern it's emitted verbatim.  False positives only fire when two
 *  modules genuinely declare the same top-level name, in which case the
 *  later definition is silently dropped (the JVM/JS scoping rules would
 *  have rejected the duplicate anyway). */
private def dedupTopLevelDefs(source: String, lang: String): String =
  val lines = source.linesWithSeparators.toArray
  val seen  = scala.collection.mutable.HashSet.empty[String]
  val out   = new StringBuilder

  // Recogniser: given the leading non-whitespace portion of a column-0
  // line, return Some(declaredName) iff this is a top-level decl we know
  // how to dedup.  None otherwise — emit verbatim.
  val scalaKinds = Set(
    "def", "val", "var", "lazy",
    "class", "object", "trait", "enum", "type",
    "given", "extension"
  )
  val jsKinds = Set("function", "const", "let", "var", "class")
  // Modifier keywords that precede a real decl-kind; we strip them off.
  // `async` is included for JS so `async function foo` parses.
  val modifierKeywords = Set(
    "private", "protected", "public",
    "implicit", "final", "sealed", "abstract", "open",
    "case", "lazy", "inline", "transparent", "override",
    "export", "async"
  )
  val delimChars = "([:={ \t,;".toSet

  def declName(line: String): Option[String] =
    if line.isEmpty || line.charAt(0).isWhitespace then None
    else
      val trimmed = line.stripLineEnd
      // Strip a leading modifier chain — we still match on the next
      // token after.
      var rest = trimmed
      var changed = true
      while changed do
        changed = false
        val sp = rest.indexOf(' ')
        if sp > 0 then
          val tok = rest.substring(0, sp)
          if modifierKeywords.contains(tok) then
            rest = rest.substring(sp + 1).stripLeading()
            changed = true

      // Now `rest` begins with what should be the decl-kind keyword.
      val sp = rest.indexOf(' ')
      if sp <= 0 then None
      else
        val kind = rest.substring(0, sp)
        val after = rest.substring(sp + 1).stripLeading()
        var end = 0
        while end < after.length && !delimChars.contains(after.charAt(end)) do
          end += 1
        val name = after.substring(0, end)
        if name.isEmpty then None
        else
          val recognised = lang match
            case "scala" => scalaKinds.contains(kind)
            case "js"    => jsKinds.contains(kind)
            case _       => false
          if recognised then Some(s"$kind:$name") else None

  // Skip strategy: when we encounter a duplicate top-level decl, consume
  // its full body and return the index of the next line to keep.
  def skipBody(startIdx: Int): Int =
    val startLine = lines(startIdx)
    // Count brace balance starting from startLine.
    var braces = 0
    var hasSeenBrace = false
    def count(s: String): Unit =
      var i = 0
      // Skip line/block comments and strings approximately — for
      // generated code this is good enough.  We treat `//` as line-end.
      while i < s.length do
        val c = s.charAt(i)
        if c == '/' && i + 1 < s.length && s.charAt(i + 1) == '/' then
          i = s.length
        else if c == '"' then
          // skip until matching unescaped quote
          i += 1
          while i < s.length && s.charAt(i) != '"' do
            if s.charAt(i) == '\\' && i + 1 < s.length then i += 2 else i += 1
          if i < s.length then i += 1
        else
          if c == '{' then { braces += 1; hasSeenBrace = true }
          else if c == '}' then braces -= 1
          i += 1
    count(startLine)

    var i = startIdx + 1
    if hasSeenBrace then
      // Brace-counting mode: skip until braces return to 0.
      while i < lines.length && braces > 0 do
        count(lines(i))
        i += 1
      i
    else
      // Indentation mode (Scala 3 `def foo: …` / `class Foo:`).  Skip
      // every blank line and every line that begins with whitespace.
      // Stop at the next column-0 non-blank line that looks like a new
      // top-level decl.  Column-0 lines starting with `)`, `}`, `]`, or
      // an operator are signature/expression continuations of the
      // current def — keep skipping past them.  If such a continuation
      // opens a `{` we promote to brace-mode for the rest of the body.
      var done = false
      var result = i
      while i < lines.length && !done do
        val ln = lines(i)
        val stripped = ln.stripLineEnd
        if stripped.isEmpty then
          i += 1
        else if ln.charAt(0).isWhitespace then
          i += 1
        else
          val firstCh = stripped.charAt(0)
          if firstCh == ')' || firstCh == '}' || firstCh == ']' ||
             firstCh == ',' || firstCh == '.' then
            count(ln)
            if braces > 0 then
              // Promote to brace mode for the rest of this body.
              i += 1
              while i < lines.length && braces > 0 do
                count(lines(i))
                i += 1
              done = true
              result = i
            else
              i += 1
          else
            result = i
            done = true
      if done then result else i

  var i = 0
  while i < lines.length do
    val line = lines(i)
    declName(line) match
      case Some(key) =>
        if seen.contains(key) then
          // Duplicate — skip the entire definition body.
          i = skipBody(i)
        else
          seen += key
          out.append(line)
          i += 1
      case None =>
        out.append(line)
        i += 1
  out.toString

/** Longest common string prefix across a non-empty list of strings.  Returns
 *  the empty string when the inputs share nothing or the list is empty.
 *  Used to strip the deterministic JvmGen runtime preamble from per-module
 *  cached sources during linking. */
private def longestCommonPrefix(xs: List[String]): String =
  if xs.isEmpty then ""
  else if xs.size == 1 then ""  // nothing to dedup against
  else
    val first = xs.head
    val rest  = xs.tail
    var i = 0
    val limit = (first.length :: rest.map(_.length)).min
    var ok = true
    while ok && i < limit do
      val c = first.charAt(i)
      if rest.forall(_.charAt(i) == c) then i += 1 else ok = false
    // Don't split inside a line — back up to the last newline so the shared
    // prefix is whole-lines only.  Avoids slicing a `def` declaration in half.
    val nl = first.lastIndexOf('\n', i - 1)
    if nl >= 0 then first.substring(0, nl + 1) else ""

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
        if reportCodeBlockParseErrors(module, file) then
          hasErrors = true
        else
          // `check-with-iface` runs the typer in strict mode so that
          // references to undefined names — names that resolve to neither
          // the consumer's own defs, any imported `.scim`, nor the builtin
          // prelude — surface as type errors with a non-zero exit code.
          // Other entry points (`compile`, `emit-interface`, `emit-ir`)
          // remain permissive for backward compatibility.
          val typed =
            if interfaces.isEmpty then Typer.typeCheckStrict(module)
            else Typer.typeCheckWithInterfaces(module, interfaces, strict = true)
          if typed.hasErrors then
            hasErrors = true
            typed.errors.foreach(e => System.err.println(s"  Error: ${e.show}"))
          else
            println("OK")
            println(typed.show)
      catch case e: Exception =>
        hasErrors = true
        System.err.println(s"Error: ${e.getMessage}")
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

// ─── fmt command ────────────────────────────────────────────────────────────

def fmtCommand(args: List[String]): Unit =
  val checkMode  = args.contains("--check")
  val stdoutMode = args.contains("--stdout")
  val files      = args.filterNot(a => a == "--check" || a == "--stdout")

  if files.isEmpty then
    System.err.println("ssc fmt: no files specified")
    System.exit(1)

  if stdoutMode && files.length > 1 then
    System.err.println("ssc fmt --stdout: only one file allowed")
    System.exit(1)

  var anyNeedsFormatting = false

  for file <- files do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"ssc fmt: file not found: $file")
      System.exit(1)
    val source    = os.read(path)
    val formatted = Formatter.format(source)
    if stdoutMode then
      print(formatted)
    else if checkMode then
      if formatted != source then
        System.err.println(s"$file: needs formatting")
        anyNeedsFormatting = true
    else
      if formatted != source then
        os.write.over(path, formatted)
        println(s"formatted: $file")

  if checkMode && anyNeedsFormatting then
    System.exit(1)

/** `ssc profile [--top N] [--output <file.json>] <file.ssc>`
 *
 *  Runs the interpreter with lightweight call-level instrumentation and
 *  prints a table of the top-N hotspots by total wall time after the run.
 */
// ─── CompileStats — accumulates per-phase totals for --Ystats output ─────────

/** Accumulates per-phase wall-clock totals across all modules in a build.
 *
 *  Enabled by the global `--Ystats` flag.  Call `CompileStats.enable()` once
 *  per build, then wrap each phase with `CompileStats.time("phase") { ... }`.
 *  Call `CompileStats.printAndReset()` at the end of the build to emit the
 *  table to stderr.
 *
 *  Thread-safe via `synchronized` — phases must be short enough that
 *  synchronisation overhead is negligible compared to parse/typecheck time. */
object CompileStats:
  private val acc     = scala.collection.mutable.LinkedHashMap.empty[String, Long]
  @volatile private var on = false

  def enable(): Unit  = { on = true; acc.clear() }
  def isOn:    Boolean = on

  def time[A](phase: String)(body: => A): A =
    if !on then body
    else
      val t0 = System.nanoTime()
      val r  = body
      val ms = (System.nanoTime() - t0) / 1_000_000L
      acc.synchronized { acc.update(phase, acc.getOrElse(phase, 0L) + ms) }
      r

  def printAndReset(out: java.io.PrintStream = System.err): Unit =
    if !on || acc.isEmpty then return
    val snap = acc.synchronized { acc.toList }
    val total = snap.map(_._2).sum
    val w = snap.map(_._1.length).maxOption.getOrElse(10)
    out.println()
    out.println("[Ystats] Compiler phase timings:")
    snap.foreach { (name, ms) =>
      val pct = if total > 0 then f"${ms * 100.0 / total}%5.1f%%" else "  n/a"
      out.println(f"  ${name.padTo(w, ' ')}  $ms%6d ms  $pct")
    }
    out.println(f"  ${"TOTAL".padTo(w, ' ')}  $total%6d ms")
    on = false
    acc.clear()

// ─── `ssc profile` — per-phase timing + allocation + flame-graph JSON ─────────

/** Measure one compiler pipeline phase: wall-clock time and heap allocation
 *  delta.  Returns both the computed value and the measurement record.
 *
 *  Heap allocation is approximated by the difference in
 *  `totalMemory - freeMemory` before and after the body.  It can be negative
 *  when a GC cycle happens inside the body — callers should treat that as 0.
 */
case class PhaseResult(
    name:      String,
    wallMs:    Long,
    allocBytes: Long,
    subPhases: List[PhaseResult] = Nil
)

private[cli] def timed[A](name: String)(body: => A): (A, PhaseResult) =
  val rt     = Runtime.getRuntime
  val heap0  = rt.totalMemory - rt.freeMemory
  val t0     = System.nanoTime()
  val result = body
  val wallMs = (System.nanoTime() - t0) / 1_000_000L
  val alloc  = (rt.totalMemory - rt.freeMemory) - heap0
  (result, PhaseResult(name, wallMs, alloc))

/** `ssc profile <file.ssc> [options]` — instrument each compiler phase and
 *  report wall-clock time, heap allocation delta, optional flame-graph JSON
 *  output, regression comparison, and multi-run averaging.
 *
 *  Options:
 *  {{{
 *    --top=N            show N slowest phases (default: all)
 *    --out=<file>       write flame-graph JSON (default: profile.json)
 *    --compare=<file>   diff against a prior JSON run; ⚠ on >10% regressions
 *    --runs=N           average over N runs (default: 1)
 *  }}}
 */
def profileCommand(args: List[String]): Unit =
  import scalascript.interpreter.Profiler
  var topN:      Int            = Int.MaxValue
  var jsonOut:   String         = "profile.json"
  var writeJson: Boolean        = false
  var compareFile: Option[String] = None
  var numRuns:   Int            = 1
  var files:     List[String]   = Nil

  val arr = args.toArray
  var i   = 0
  while i < arr.length do
    arr(i) match
      // --top=N  or  --top N
      case s if s.startsWith("--top=") =>
        topN = s.stripPrefix("--top=").toIntOption.getOrElse {
          System.err.println("--top requires an integer argument"); System.exit(1); Int.MaxValue
        }
        i += 1
      case "--top" if i + 1 < arr.length =>
        topN = arr(i + 1).toIntOption.getOrElse {
          System.err.println("--top requires an integer argument"); System.exit(1); Int.MaxValue
        }
        i += 2
      // --out=<file>  or  --output <file>
      case s if s.startsWith("--out=") =>
        jsonOut = s.stripPrefix("--out="); writeJson = true; i += 1
      case "--out" if i + 1 < arr.length =>
        jsonOut = arr(i + 1); writeJson = true; i += 2
      case "--output" if i + 1 < arr.length =>
        jsonOut = arr(i + 1); writeJson = true; i += 2
      // --compare=<file>  or  --compare <file>
      case s if s.startsWith("--compare=") =>
        compareFile = Some(s.stripPrefix("--compare=")); i += 1
      case "--compare" if i + 1 < arr.length =>
        compareFile = Some(arr(i + 1)); i += 2
      // --runs=N  or  --runs N
      case s if s.startsWith("--runs=") =>
        numRuns = s.stripPrefix("--runs=").toIntOption.getOrElse {
          System.err.println("--runs requires an integer argument"); System.exit(1); 1
        }
        i += 1
      case "--runs" if i + 1 < arr.length =>
        numRuns = arr(i + 1).toIntOption.getOrElse {
          System.err.println("--runs requires an integer argument"); System.exit(1); 1
        }
        i += 2
      case f =>
        files = files :+ f; i += 1

  if files.isEmpty then
    System.err.println(
      "Usage: ssc profile <file.ssc> [--top=N] [--out=profile.json] [--compare=baseline.json] [--runs=N]"
    )
    System.exit(1)

  val file = files.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then
    System.err.println(s"ssc profile: file not found: $file")
    System.exit(3)

  // ── Run pipeline N times, collecting per-run phase lists ─────────────────

  /** Run the full parse→typecheck→normalize pipeline once and return the
   *  list of `PhaseResult`s. */
  def runOnce(): List[PhaseResult] =
    val source = os.read(path)

    val (module, parseResult) =
      timed("parse") { scalascript.parser.Parser.parse(source) }

    val (_, typeckResult) =
      timed("typecheck") { scalascript.typer.Typer.typeCheck(module) }

    val (ir, normResult) =
      timed("normalize") { scalascript.transform.Normalize(module) }

    // Choose codegen phase based on --backend flag (default: jvm).
    val backendId = ActiveFlags.current.backend.getOrElse("jvm")
    val codegenResult: PhaseResult =
      if backendId == "js" then
        timed("js-codegen") {
          scalascript.codegen.JsGen.generate(module, Some(path / os.up), Map.empty)
        }._2
      else
        timed("jvm-codegen") {
          scalascript.codegen.JvmGen.generate(module, Some(path / os.up), Map.empty)
        }._2

    val (_, linkResult) =
      timed("link") {
        // "link" represents output writing / bytecode linking.
        // We measure the normalized IR serialization as a proxy when no
        // separate linker step is present.
        ir.hashCode()
      }

    List(parseResult, typeckResult, normResult, codegenResult, linkResult)

  val allRuns: List[List[PhaseResult]] =
    (1 to numRuns).toList.map(_ => runOnce())

  // ── Aggregate multiple runs into one list (min/avg/max per phase) ─────────

  // For N=1 just use that single run's values directly.
  // For N>1 build a merged list where wallMs = avg.
  val phaseNames: List[String] = allRuns.head.map(_.name)

  val aggregated: List[PhaseResult] =
    phaseNames.map { name =>
      val matching = allRuns.flatMap(_.find(_.name == name))
      val wallVals  = matching.map(_.wallMs)
      val allocVals = matching.map(_.allocBytes)
      val wallAvg   = if wallVals.isEmpty then 0L else wallVals.sum / wallVals.size
      val allocAvg  = if allocVals.isEmpty then 0L else allocVals.sum / allocVals.size
      PhaseResult(name, wallAvg, allocAvg)
    }

  val wallMin: Map[String, Long] =
    if numRuns <= 1 then Map.empty
    else phaseNames.map(name =>
      name -> allRuns.flatMap(_.find(_.name == name)).map(_.wallMs).minOption.getOrElse(0L)
    ).toMap

  val wallMax: Map[String, Long] =
    if numRuns <= 1 then Map.empty
    else phaseNames.map(name =>
      name -> allRuns.flatMap(_.find(_.name == name)).map(_.wallMs).maxOption.getOrElse(0L)
    ).toMap

  val totalWallMs   = aggregated.map(_.wallMs).sum
  val totalAllocBytes = aggregated.map(_.allocBytes).sum

  // ── Print human-readable table ────────────────────────────────────────────

  val visiblePhases =
    if topN == Int.MaxValue then aggregated
    else aggregated.sortBy(-_.wallMs).take(topN)

  println()
  if numRuns > 1 then
    println(f"${"Phase"}%-20s  ${"Wall (ms)"}%10s  ${"Alloc (MB)"}%10s  ${"min/avg/max (ms)"}%s")
    println("─" * 72)
    for ph <- visiblePhases do
      val allocMb = ph.allocBytes / 1_000_000.0
      val minMs   = wallMin.getOrElse(ph.name, ph.wallMs)
      val maxMs   = wallMax.getOrElse(ph.name, ph.wallMs)
      println(f"${ph.name}%-20s  ${ph.wallMs}%10d  $allocMb%10.1f  $minMs/${ ph.wallMs }/$maxMs")
    println("─" * 72)
    println(f"${"total"}%-20s  ${totalWallMs}%10d  ${totalAllocBytes / 1_000_000.0}%10.1f")
  else
    println(f"${"Phase"}%-20s  ${"Wall (ms)"}%10s  ${"Alloc (MB)"}%10s")
    println("─" * 45)
    for ph <- visiblePhases do
      val allocMb = ph.allocBytes / 1_000_000.0
      println(f"${ph.name}%-20s  ${ph.wallMs}%10d  $allocMb%10.1f")
    println("─" * 45)
    println(f"${"total"}%-20s  ${totalWallMs}%10d  ${totalAllocBytes / 1_000_000.0}%10.1f")

  // ── --top=N summary (sorted by wallMs) ────────────────────────────────────

  if topN < Int.MaxValue then
    println()
    println(s"Top $topN hottest phases:")
    aggregated.sortBy(-_.wallMs).take(topN).zipWithIndex.foreach { case (ph, idx) =>
      println(f"  ${idx + 1}. ${ph.name}%-16s ${ph.wallMs} ms")
    }

  // ── Write flame-graph JSON ────────────────────────────────────────────────

  val timestamp = java.time.Instant.now().toString
  val phasesJson = aggregated.map { ph =>
    s"""    {"name":"${ph.name}","wallMs":${ph.wallMs},"allocBytes":${ph.allocBytes}}"""
  }.mkString(",\n")
  val flameJson =
    s"""{
       |  "version": "ssc-profile/1.0",
       |  "file": "${path.last}",
       |  "timestamp": "$timestamp",
       |  "runs": $numRuns,
       |  "phases": [
       |$phasesJson
       |  ],
       |  "totalWallMs": $totalWallMs,
       |  "totalAllocBytes": $totalAllocBytes
       |}
       |""".stripMargin

  if writeJson then
    val outPath = os.Path(jsonOut, os.pwd)
    os.write.over(outPath, flameJson)
    println(s"\nProfile written to $jsonOut")

  // ── --compare=<baseline> ──────────────────────────────────────────────────

  compareFile.foreach { baselineFile =>
    val baselinePath = os.Path(baselineFile, os.pwd)
    if !os.exists(baselinePath) then
      System.err.println(s"ssc profile --compare: baseline file not found: $baselineFile")
      System.exit(1)

    // Minimal JSON parser: extract phases array.
    val baselineJson = os.read(baselinePath)
    val baselinePhases: Map[String, Long] =
      // Parse {"name":"...","wallMs":N,...} entries from "phases":[...] array.
      val phaseBlockRegex = """\{"name":"([^"]+)","wallMs":(\d+)""".r
      phaseBlockRegex.findAllMatchIn(baselineJson).map { m =>
        m.group(1) -> m.group(2).toLong
      }.toMap

    if baselinePhases.isEmpty then
      System.err.println(s"ssc profile --compare: could not parse phases from $baselineFile")
      System.exit(1)

    println()
    println(f"${"Phase"}%-20s  ${"Baseline (ms)"}%14s  ${"Current (ms)"}%13s  ${"Delta"}%s")
    println("─" * 62)
    var hasRegression = false
    for ph <- aggregated do
      baselinePhases.get(ph.name) match
        case None =>
          println(f"${ph.name}%-20s  ${"N/A"}%14s  ${ph.wallMs}%13d  (new)")
        case Some(base) =>
          val delta    = ph.wallMs - base
          val pctDouble = if base == 0 then 0.0 else delta.toDouble / base * 100.0
          val pct      = f"${if pctDouble >= 0 then "+" else ""}$pctDouble%.0f%%"
          val warn     = if pctDouble > 10.0 then " ⚠" else ""
          if pctDouble > 10.0 then hasRegression = true
          println(f"${ph.name}%-20s  $base%14d  ${ph.wallMs}%13d  $pct$warn")
    if hasRegression then
      println()
      println("⚠ Regressions detected (>10% slower than baseline).")
  }

  // Keep legacy Profiler.renderTable for backward compatibility when --top is small.
  if topN <= 20 && topN != Int.MaxValue then
    println()
    System.out.print(Profiler.renderTable(topN))

/** `ssc lsp` — run the Language Server Protocol server over stdio.
 *
 *  No options for now.  Reads framed JSON-RPC from stdin, writes to stdout,
 *  logs to stderr.  Exits with the JSON-RPC negotiated exit code (0 if
 *  `shutdown` preceded `exit`, 1 otherwise). */
def lspCommand(args: List[String]): Unit =
  import scalascript.cli.lsp.LspServer
  // Currently unused — reserved for future flags (`--log-file <path>`,
  // `--artifact-dir <dir>`, …).  Ignored silently in MVP.
  val _ = args
  val code = LspServer.runStdio()
  System.exit(code)

/** `ssc cluster status <url>` — fetch GET <url>/_ssc-cluster/status
 *  from a running ssc node and pretty-print the JSON snapshot.  The
 *  endpoint is registered automatically when the node calls
 *  `startNode(...)`.  Output is a human-readable summary; raw JSON
 *  is available via `--json`. */
def clusterCommand(args: List[String]): Unit =
  args match
    case "status"   :: rest => clusterStatusCommand(rest)
    case "drain"    :: rest => clusterDrainCommand(rest)
    case "events"   :: rest => clusterEventsCommand(rest)
    case "step-down" :: rest => clusterStepDownCommand(rest)
    case "handlers" :: rest => clusterHandlersCommand(rest)
    case "run"      :: rest => clusterRunCommand(rest)
    case "package"  :: rest => clusterPackageCommand(rest)
    case "stop"     :: rest => clusterStopCommand(rest)
    case ("help" | "--help" | "-h") :: _ =>
      println("Usage: ssc cluster <subcommand>")
      println("  status    <url> [--json] [--token=<t>]            show JSON snapshot")
      println("  drain     <url> [--off]  [--token=<t>]            toggle drain mode")
      println("  events    <url> [--since=<ms>] [--token=<t>]      dump events ring")
      println("  step-down <url> [--token=<t>]                     graceful leader step-down")
      println("  handlers  --seed <url> [--token=<t>] [--json]     list exported handlers")
      println("  run       <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr>] [--join <url>]")
      println("  package   <file.ssc> --out <dist.zip> [--target worker]")
      println("  stop      --seed <url> [--token=<t>]              drain then step-down")
      println()
      println("Auth: --token=<t> or SSC_CLUSTER_TOKEN env.  Sends")
      println("`Authorization: Bearer <token>` on every request.")
    case _ =>
      System.err.println("Usage: ssc cluster {status|drain|events|step-down|handlers|run|package|stop} <url> [opts]")
      System.exit(2)

private def clusterStepDownCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster step-down <url> [--token=<t>]")
    System.exit(2)
  else
    val url = urlOpt.head
    val target =
      if url.endsWith("/_ssc-cluster/step-down") then url
      else url.stripSuffix("/") + "/_ssc-cluster/step-down"
    val token  = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(target))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json"),
      token
    ).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to POST $target: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      resp.statusCode() match
        case 200 =>
          println(resp.body())
        case 409 =>
          // Not leader — surface the body's `"leader":"..."` field so
          // the operator knows where to point the next attempt.
          System.err.println("not leader — current leader: " + resp.body())
          System.exit(1)
        case other =>
          System.err.println(s"unexpected status $other from $target")
          System.err.println(resp.body())
          System.exit(1)
    }

/** Pull the shared-secret Bearer token from a `--token=<t>` flag (if
 *  present) or the `SSC_CLUSTER_TOKEN` env var.  Empty result ⇒ skip
 *  the Authorization header — endpoints accept anonymous calls when
 *  the server's `setClusterAuthToken` is unset. */
private def clusterAuthTokenFor(flags: List[String]): String =
  flags.find(_.startsWith("--token="))
    .map(_.stripPrefix("--token="))
    .orElse(sys.env.get("SSC_CLUSTER_TOKEN"))
    .getOrElse("")

/** Attach `Authorization: Bearer <token>` when non-empty.  Always
 *  sets Content-Type for the POST flows. */
private def applyClusterAuth(
    builder: java.net.http.HttpRequest.Builder,
    token: String
): java.net.http.HttpRequest.Builder =
  if token.nonEmpty then builder.header("Authorization", "Bearer " + token)
  else builder

private def clusterEventsCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  val sinceMs: Option[Long] = flags
    .find(_.startsWith("--since="))
    .map(_.stripPrefix("--since="))
    .flatMap(_.toLongOption)
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster events <url> [--since=<epoch-ms>]")
    System.exit(2)
  else
    val url = urlOpt.head
    val base =
      if url.endsWith("/_ssc-cluster/events") then url
      else url.stripSuffix("/") + "/_ssc-cluster/events"
    val full = sinceMs match
      case Some(t) => base + "?since=" + t
      case None    => base
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(full))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $full: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $full")
        System.err.println(resp.body())
        System.exit(1)
      else
        // Body is a flat JSON array — split on `},{` and print one
        // event per line.  Avoid pulling in a JSON parser.
        val body = resp.body().trim
        if body == "[]" then println("(no events)")
        else
          val inner = body.stripPrefix("[").stripSuffix("]")
          val parts = inner.split("\\},\\{").toIndexedSeq.map { s =>
            val withOpen  = if s.startsWith("{") then s else "{" + s
            if withOpen.endsWith("}") then withOpen else withOpen + "}"
          }
          parts.foreach(println)
    }

private def clusterDrainCommand(args: List[String]): Unit =
  val (flags, urlOpt) = args.partition(_.startsWith("--"))
  val off = flags.contains("--off")
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster drain <url> [--off]")
    System.err.println("  e.g. ssc cluster drain http://localhost:8080      # enable")
    System.err.println("       ssc cluster drain http://localhost:8080 --off  # disable")
    System.exit(2)
  else
    val url = urlOpt.head
    val drainUrl =
      if url.endsWith("/_ssc-cluster/drain") then url
      else url.stripSuffix("/") + "/_ssc-cluster/drain"
    val payload = if off then """{"enabled":false}""" else """{"enabled":true}"""
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(drainUrl))
        .timeout(java.time.Duration.ofSeconds(10))
        .header("Content-Type", "application/json"),
      token
    ).POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to POST $drainUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $drainUrl")
        System.err.println(resp.body())
        System.exit(1)
      else
        println(s"${if off then "disabled" else "enabled"} drain on $url")
        println(resp.body())
    }

private def clusterStatusCommand(args: List[String]): Unit =
  val (raw, urlOpt) = args.partition(_.startsWith("--"))
  val rawJson = raw.contains("--json")
  if urlOpt.isEmpty then
    System.err.println("Usage: ssc cluster status <url> [--json]")
    System.err.println("  e.g. ssc cluster status http://localhost:8080")
    System.exit(2)
  else
    val url = urlOpt.head
    val statusUrl =
      if url.endsWith("/_ssc-cluster/status") then url
      else url.stripSuffix("/") + "/_ssc-cluster/status"
    val token = clusterAuthTokenFor(raw)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(statusUrl))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt: Option[java.net.http.HttpResponse[String]] =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $statusUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $statusUrl")
        System.err.println(resp.body())
        System.exit(1)
      else if rawJson then
        println(resp.body())
      else
        printClusterStatusHuman(resp.body())
    }

private def printClusterStatusHuman(body: String): Unit =
  // Hand-rolled JSON extraction — keeps the CLI free of a JSON dep.
  // Reads only what we emit on the server side (flat object, scalar
  // values + two string arrays).
  def strField(key: String): String =
    val pat = "\"" + key + "\":\""
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      val end   = body.indexOf("\"", start)
      if end < 0 then "" else body.substring(start, end)
  def boolField(key: String): String =
    val pat = "\"" + key + "\":"
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      if body.startsWith("true",  start) then "true"
      else if body.startsWith("false", start) then "false"
      else ""
  def intField(key: String): String =
    val pat = "\"" + key + "\":"
    val i = body.indexOf(pat)
    if i < 0 then ""
    else
      val start = i + pat.length
      val end   = body.indexOf(',', start) match
        case -1 => body.indexOf('}', start)
        case n  => n
      if end < 0 then "" else body.substring(start, end).trim
  def arrField(key: String): List[String] =
    val pat = "\"" + key + "\":["
    val i = body.indexOf(pat)
    if i < 0 then Nil
    else
      val start = i + pat.length
      val end   = body.indexOf(']', start)
      if end < 0 then Nil
      else
        val inside = body.substring(start, end).trim
        if inside.isEmpty then Nil
        else inside.split(',').toList.map(_.trim.stripPrefix("\"").stripSuffix("\""))
  println(s"node:        ${strField("nodeId")}")
  println(s"protocol:    ${strField("protocol")}")
  val leader = strField("leader")
  println(s"leader:      ${if leader.isEmpty then "<none>" else leader}")
  val members = arrField("members")
  println(s"members:     ${if members.isEmpty then "<none>" else members.mkString(", ")}")
  println(s"drainingSelf: ${boolField("drainingSelf")}")
  val drainPeers = arrField("drainingPeers")
  if drainPeers.nonEmpty then
    println(s"drainingPeers: ${drainPeers.mkString(", ")}")
  val rt = intField("raftTerm")
  val rs = strField("raftState")
  if strField("protocol") == "raft" || rt != "0" then
    println(s"raftTerm:    $rt")
    println(s"raftState:   $rs")

def printSection(s: Section, indent: Int): Unit =
  val prefix = "  " * indent
  println(s"$prefix${"#" * s.heading.level} ${s.heading.text}")
  s.content.foreach {
    case Content.CodeBlock(lang, src, tree, _, _, _, _) =>
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

// ─── generate-facade ─────────────────────────────────────────────────────────

/** `ssc generate-facade <artifactDir> [-o <outputDir>]`
 *
 *  Reads all `.scim` artifacts from `artifactDir`, runs
 *  `FacadeGenerator.generate`, and writes the resulting Scala 3 source
 *  files to `outputDir` (default: current working directory).
 *
 *  Exits 0 even when no facade is emitted (identity-mapped Tier-5
 *  artifacts produce no file — that's expected, not an error). */
def generateFacadeCommand(args: List[String]): Unit =
  var artifactDir: Option[String] = None
  var outputDir:   Option[String] = None
  var rest = args
  while rest.nonEmpty do
    rest.head match
      case "-o" | "--output" =>
        rest = rest.tail
        if rest.isEmpty then
          System.err.println("generate-facade: -o requires a directory argument")
          System.exit(1)
        outputDir = Some(rest.head)
        rest = rest.tail
      case flag if flag.startsWith("-") =>
        System.err.println(s"generate-facade: unknown flag $flag")
        System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
        System.exit(1)
      case dir =>
        if artifactDir.isDefined then
          System.err.println("generate-facade: too many positional arguments")
          System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
          System.exit(1)
        artifactDir = Some(dir)
        rest = rest.tail

  if artifactDir.isEmpty then
    System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
    System.err.println("  Read .scim artifacts from <artifactDir>, emit Scala 3 facade sources.")
    System.err.println("  Outputs to <outputDir> (default: current directory).")
    System.exit(1)

  val artPath = os.Path(java.nio.file.Paths.get(artifactDir.get).toAbsolutePath)
  val outPath = os.Path(java.nio.file.Paths.get(outputDir.getOrElse(".")).toAbsolutePath)

  if !os.exists(artPath) || !os.isDir(artPath) then
    System.err.println(s"generate-facade: artifact directory not found: $artPath")
    System.exit(1)

  os.makeDir.all(outPath)

  val sources = scalascript.interop.facade.FacadeGenerator.generate(artPath)
  if sources.isEmpty then
    // Tier-5 identity artifacts produce no facade — this is normal.
    System.err.println("[ssc] generate-facade: no legacy facade entries found; nothing written.")
  else
    for (relPath, content) <- sources.toList.sortBy(_._1) do
      val target = outPath / os.RelPath(relPath)
      os.makeDir.all(target / os.up)
      os.write.over(target, content)
      System.err.println(s"[ssc] generate-facade: wrote ${outPath.relativeTo(os.pwd)}/${relPath}")

// ── v1.63.5 — cluster run/package/handlers/stop ──────────────────────────────

/** `ssc cluster handlers --seed <url>` — fetch GET <url>/_ssc-cluster/handlers
 *  and list exported remote-handler operations. */
private def clusterHandlersCommand(args: List[String]): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  val seedOpt = flags.collectFirst { case s if s.startsWith("--seed=") => s.drop(7) }
    .orElse(flags.zipWithIndex.collectFirst { case ("--seed", i) if i + 1 < flags.size => flags(i + 1) })
    .orElse(positional.headOption)
  if seedOpt.isEmpty then
    System.err.println("Usage: ssc cluster handlers --seed <url> [--token=<t>] [--json]")
    System.exit(2)
  else
    val seed = seedOpt.get
    val handlersUrl =
      if seed.endsWith("/_ssc-cluster/handlers") then seed
      else seed.stripSuffix("/") + "/_ssc-cluster/handlers"
    val token = clusterAuthTokenFor(flags)
    val rawJson = flags.contains("--json")
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    val req = applyClusterAuth(
      java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(handlersUrl))
        .timeout(java.time.Duration.ofSeconds(10)),
      token
    ).GET().build()
    val respOpt =
      try Some(client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()))
      catch case e: Throwable =>
        System.err.println(s"failed to GET $handlersUrl: ${e.getMessage}")
        System.exit(1)
        None
    respOpt.foreach { resp =>
      if resp.statusCode() != 200 then
        System.err.println(s"unexpected status ${resp.statusCode()} from $handlersUrl")
        System.err.println(resp.body())
        System.exit(1)
      else if rawJson then
        println(resp.body())
      else
        // Hand-rolled extraction of name/path/transports from JSON array.
        val body = resp.body()
        println("Remote handlers:")
        val entries = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
        var pos = 0
        while pos < body.length do
          val nameStart = body.indexOf("\"name\":\"", pos)
          if nameStart < 0 then pos = body.length
          else
            val ns = nameStart + 8
            val ne = body.indexOf('"', ns)
            val name = if ne > ns then body.substring(ns, ne) else "?"
            val pathStart = body.indexOf("\"path\":\"", pos)
            val path =
              if pathStart >= 0 && pathStart < body.indexOf('}', nameStart) then
                val ps = pathStart + 8; val pe = body.indexOf('"', ps)
                if pe > ps then body.substring(ps, pe) else ""
              else ""
            entries += ((name, path, ""))
            pos = ne + 1
        if entries.isEmpty then println("  (none)")
        else entries.foreach { (name, path, _) =>
          println(s"  $name${if path.nonEmpty then s" → $path" else ""}")
        }
    }

/** `ssc cluster run <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr>] [--join <url>]`
 *  — run a .ssc file as a cluster node by injecting cluster env vars and
 *  delegating to the normal `ssc run` command. */
private def clusterRunCommand(args: List[String]): Unit =
  if args.isEmpty then
    System.err.println("Usage: ssc cluster run <file.ssc> [--role <r>] [--node-id <id>] [--bind <addr:port>] [--join <ws-url>] [--token <t>]")
    System.exit(2)
  val it = args.iterator
  var fileArg: Option[String] = None
  var roleFlag: Option[String] = None
  var nodeIdFlag: Option[String] = None
  var bindFlag: Option[String] = None
  var joinFlag: Option[String] = None
  var tokenFlag: Option[String] = None
  val extra = scala.collection.mutable.ArrayBuffer.empty[String]
  while it.hasNext do
    it.next() match
      case "--role"    if it.hasNext => roleFlag    = Some(it.next())
      case "--node-id" if it.hasNext => nodeIdFlag  = Some(it.next())
      case "--bind"    if it.hasNext => bindFlag    = Some(it.next())
      case "--join"    if it.hasNext => joinFlag    = Some(it.next())
      case "--token"   if it.hasNext => tokenFlag   = Some(it.next())
      case flag if flag.startsWith("--role=")    => roleFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--node-id=") => nodeIdFlag  = Some(flag.drop(10))
      case flag if flag.startsWith("--bind=")    => bindFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--join=")    => joinFlag    = Some(flag.drop(7))
      case flag if flag.startsWith("--token=")   => tokenFlag   = Some(flag.drop(8))
      case other if !other.startsWith("--") && fileArg.isEmpty => fileArg = Some(other)
      case other => extra += other
  if fileArg.isEmpty then
    System.err.println("ssc cluster run: no .ssc file specified")
    System.exit(2)
  val envVars = scala.collection.mutable.Map.empty[String, String]
  roleFlag.foreach   { r => envVars("SSC_CLUSTER_ROLE")  = r }
  nodeIdFlag.foreach { n => envVars("SSC_NODE_ID")       = n }
  bindFlag.foreach   { b => envVars("SSC_BIND")          = b }
  joinFlag.foreach   { j => envVars("SSC_JOIN_SEEDS")    = j }
  tokenFlag.foreach  { t => envVars("SSC_CLUSTER_TOKEN") = t }
  // Delegate to the regular runCommand path with cluster env vars injected.
  val result = os.proc("ssc", "run", fileArg.get)
    .call(stdout = os.Inherit, stderr = os.Inherit, env = envVars.toMap, check = false)
  System.exit(result.exitCode)

/** `ssc cluster package <file.ssc> --out <dist.zip> [--target worker]` —
 *  package a .ssc source file into a worker bundle zip with code identity
 *  and registry metadata. */
private def clusterPackageCommand(args: List[String]): Unit =
  val it = args.iterator
  var fileArg: Option[String] = None
  var outFlag:    Option[String] = None
  var targetFlag: Option[String] = Some("worker")
  while it.hasNext do
    it.next() match
      case "--out"    if it.hasNext => outFlag    = Some(it.next())
      case "--target" if it.hasNext => targetFlag = Some(it.next())
      case flag if flag.startsWith("--out=")    => outFlag    = Some(flag.drop(6))
      case flag if flag.startsWith("--target=") => targetFlag = Some(flag.drop(9))
      case other if !other.startsWith("--") && fileArg.isEmpty => fileArg = Some(other)
      case _ => ()
  if fileArg.isEmpty || outFlag.isEmpty then
    System.err.println("Usage: ssc cluster package <file.ssc> --out <dist.zip> [--target worker]")
    System.exit(2)
  val srcPath = os.Path(fileArg.get, os.pwd)
  if !os.exists(srcPath) then
    System.err.println(s"ssc cluster package: file not found: $srcPath")
    System.exit(1)
  val outPath = os.Path(outFlag.get, os.pwd)
  os.makeDir.all(outPath / os.up)
  // Compute a simple SHA-256 code identity hash over the source bytes.
  val srcBytes = os.read.bytes(srcPath)
  val digest   = java.security.MessageDigest.getInstance("SHA-256")
  val hashHex  = digest.digest(srcBytes).map(b => "%02x".format(b)).mkString
  val target   = targetFlag.getOrElse("worker")
  // Build the worker bundle metadata JSON.
  val metaJson =
    s"""{
       |  "bundleVersion": "1",
       |  "target": "$target",
       |  "sourceFile": "${srcPath.last}",
       |  "codeIdentity": {
       |    "algorithmId": "sha256",
       |    "hash": "$hashHex"
       |  },
       |  "registryMetadata": {
       |    "remoteHandlers": [],
       |    "exportedBehaviors": [],
       |    "exportedSources": []
       |  },
       |  "runtimeVersion": "1.63.5"
       |}""".stripMargin
  // Write the zip: source file + manifest.json.
  val zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
  try
    zos.putNextEntry(new java.util.zip.ZipEntry(srcPath.last))
    zos.write(srcBytes)
    zos.closeEntry()
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"))
    zos.write(metaJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    zos.closeEntry()
  finally
    zos.close()
  println(s"[ssc] cluster package: wrote ${outPath}")
  println(s"[ssc]   source:       ${srcPath.last}")
  println(s"[ssc]   codeIdentity: sha256:${hashHex.take(16)}...")
  println(s"[ssc]   target:       $target")

/** `ssc cluster stop --seed <url> [--token <t>]` — drain the target node
 *  and request a graceful leader step-down. */
private def clusterStopCommand(args: List[String]): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  val seedOpt = flags.collectFirst { case s if s.startsWith("--seed=") => s.drop(7) }
    .orElse(positional.headOption)
  if seedOpt.isEmpty then
    System.err.println("Usage: ssc cluster stop --seed <url> [--token=<t>]")
    System.exit(2)
  else
    val seed  = seedOpt.get.stripSuffix("/")
    val token = clusterAuthTokenFor(flags)
    val client = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build()
    def post(path: String, body: String): Int =
      val req = applyClusterAuth(
        java.net.http.HttpRequest.newBuilder()
          .uri(java.net.URI.create(seed + path))
          .timeout(java.time.Duration.ofSeconds(10))
          .header("Content-Type", "application/json"),
        token
      ).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
      try client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
      catch case e: Throwable =>
        System.err.println(s"request to $seed$path failed: ${e.getMessage}")
        -1
    System.err.println(s"[ssc] draining $seed ...")
    val drainStatus = post("/_ssc-cluster/drain", """{"enabled":true}""")
    if drainStatus >= 0 && drainStatus < 300 then
      System.err.println(s"[ssc] stepping down leader ...")
      post("/_ssc-cluster/step-down", "")
    System.err.println(s"[ssc] stop signal sent to $seed")
