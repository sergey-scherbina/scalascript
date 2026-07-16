package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.{Typer, SectionSnapshot, SectionDiff}
// Stage 5.4 will phase these direct imports out via HTTP intrinsics +
// concrete ir.Value bridging.  Until then, render / build / serve / repl
// commands need Interpreter + JsRuntime preamble strings directly;
// emit-spa needs ScalaJsBackend.compileSourceToJs for per-segment
// Scala source compilation.
import scalascript.interpreter.Interpreter
import scalascript.codegen.JsGen
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
// cli-main-helper-split-p2: leaf helper clusters extracted from this file.
import RenderHelpers.*
import ArtifactInfoPrinters.*

@main def ssc(rawArgs: String*): Unit =
  // --quiet silences third-party SLF4J library output (commonmark, …)
  // by raising the slf4j-simple threshold to error.  Must run before any SLF4J
  // logger is first touched.  Keep --quiet in args0 so subcommands can see it.
  if rawArgs.contains("--quiet") then
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
  val args0 = rawArgs

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
  // Register the opt-in `plugin-available/` dirs so the interpreter's lazy
  // ensurePluginsLoaded() can commit them on first missing name/extern
  // (plugin-lazyload-extern-imports). Not loaded here — startup stays fast.
  BackendRegistry.setAvailableDirs(pluginAvailableDirs)
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
  else
    dispatchCommand(args).exitIfFailure()

private def dispatchCommand(args: List[String]): CommandResult =
  val token = args.head
  // Registry-driven dispatch (specs/cli-command-spi.md). Unknown tokens fall
  // through to scriptCommand, which runs a named project script if defined.
  CommandRegistry.lookup(token) match
    case Some(cmd) => cmd.runResult(args.tail)
    case None      =>
      scriptCommand(token, args.tail)
      CommandResult.Success

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
    if System.in.available() == 0 then return
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

private[cli] def compileViaBackend(
    backendId: String,
    file:      os.Path,
    extras:    Map[String, String] = Map.empty
): CompileResult =
  val module0 = loadModule(file)
  // The Rust backend (unlike JVM/JS) does not resolve `[name](path.ssc)` file
  // imports inside its codegen, so inline the imported modules' defs here.
  val module  =
    if backendId == "rust" then
      val extra = inlineImportsRust(module0, file / os.up, scala.collection.mutable.Set.empty[String])
      if extra.isEmpty then module0 else module0.copy(sections = extra ++ module0.sections)
    else module0
  // Fail loudly on a code-block parse error instead of silently dropping the
  // un-parsed block (which left `ssc run` producing no output / a silent hang).
  // `reportCodeBlockParseErrors` prints the structured `file:line:col` diagnostic
  // to stderr; we then short-circuit so the backend never runs partial IR.
  if reportCodeBlockParseErrors(module, file.toString) then
    // The structured diagnostic was already printed to stderr above; return an
    // empty Failed so the caller exits non-zero without re-printing a raw
    // `Generic(...)` toString on top of it.
    return CompileResult.Failed(Nil)
  val backend = resolveBackend(backendId)
  val opts = BackendOptions(
    baseDir = Some((file / os.up).toNIO),
    extra   = extras
  )
  backend match
    // wide-jit C-2: the tree-walking interpreter runs the ORIGINAL tree-bearing
    // module directly, skipping the Normalize→Denormalize round-trip + source
    // re-parse (SectionRuntime consumes `cb.tree`). Parse errors were already
    // reported above; the interpreter imposes no capability restrictions.
    case ib: scalascript.interpreter.InterpreterBackend =>
      // wide-jit C-3 (opt-in via SSC_JIT_TYPESTATS): typecheck the SAME `module` object —
      // identity-preserving, so the Typer's per-node types key on the very trees the JIT
      // compiles — best-effort (never fail the run on a type error). OFF by default → empty
      // map → the interpreter/JIT behave exactly as before.
      val emptyTypes = java.util.Collections.emptyMap[scala.meta.Tree, scalascript.typer.SType]()
      val nodeTypes =
        if sys.env.contains("SSC_JIT_TYPESTATS") || sys.props.contains("ssc.jit.typestats") then
          try { val typer = new scalascript.typer.Typer(); typer.typeCheck(module); typer.nodeTypes }
          catch case _: Throwable => emptyTypes
        else emptyTypes
      ib.compileAstModule(module, opts, nodeTypes)
    case _ =>
      val ir    = Normalize(module)
      val diags = CapabilityCheck.validate(ir, backend.capabilities, backendId)
      if diags.nonEmpty then CompileResult.Failed(diags)
      else backend.compile(ir, opts)

private[cli] def compileJsSegments(path: os.Path, noTreeShake: Boolean = false): List[Segment] =
  // [E3] honor the `def view()` convention on the codegen path (client-mode SPA
  // / emit-spa): synthesize `serve(view(), 8080)` so JsGen emits the React mount.
  val module  = AutoViewEntry.maybeInject(loadModule(path))
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

private[cli] def expectText(r: CompileResult, what: String): String = r match
  case CompileResult.TextOutput(code, _, _) => code
  case CompileResult.Failed(diags) =>
    diags.foreach(d => System.err.println(s"[error] $d"))
    System.exit(1); ""
  case other =>
    System.err.println(s"$what: unexpected result ${other.getClass.getSimpleName}")
    System.exit(1); ""

/** Inject the HTTP/WS server backend selection into a generated
 *  scala-cli script.  See specs/http-server-spi-plan.md for the SPI
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
  Set("custom", "react", "solid", "vue", "electron", "swing", "javafx", "swiftui", "tui")

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
  val header =
    """ScalaScript (ssc)
      |
      |Usage: ssc <command> [options] <files...>""".stripMargin
  // Platform targets + flag/logging/example sections are curated prose; the
  // per-command "Commands" listing above is generated from the registry.
  val tail =
    """
      |Platform targets:
      |  tui     <f>                       Run a .ssc UI live in the terminal (ratatui, reactive)
      |  run     --frontend tui <f>        Same live terminal UI (transpiled to a ratatui binary)
      |  run     --frontend electron <f>   Compile .ssc and open in an Electron desktop window
      |  run-jvm --frontend swing <f>      Launch the JDK-only Swing desktop frontend
      |  run-jvm --frontend javafx <f>     Launch the OpenJFX desktop frontend
      |  build   --target desktop <f>      Generate an Electron bundle (npm run build to package)
      |  build   --target ios|macos <f>    Generate a SwiftUI Swift package
      |  package --target macos <f>        Build + ad-hoc codesign .app (ready-to-run, no cert)
      |  package --target ios <f>          Archive + export signed .ipa (Xcode + Apple Developer)
      |    --export-method <m>             development|ad-hoc|enterprise|app-store (default: development)
      |    --team-id <id>                  Apple Developer Team ID (or SSC_TEAM_ID)
      |  package --target macos --distribution <f>  Codesign + notarize + DMG (Developer ID cert)
      |    --no-dmg / --no-notarize        Signed .app only / skip notarization
      |  publish --target ios|macos <f>    Upload to TestFlight / App Store via fastlane
      |    --testflight | --appstore       Destination; --submit-for-review to auto-submit
      |    --api-key-path <p>              App Store Connect API key (.p8) or APP_STORE_CONNECT_API_KEY_PATH
      |  run     --target macos <f>        Build the SwiftUI macOS app and launch it
      |  run     --target ios [--device] <f>  Build + boot Simulator (or deploy to USB device)
      |
      |Package flags (passed through to scala-cli package):
      |  --lib                  Pack a library source tree into a .ssclib ZIP archive
      |                           ssc package --lib [<dir>] [-o my-lib-1.0.ssclib] [--manifest …] [--precompile]
      |                           --jvm-glue <jar> / --js-glue <js>  glue packed in the archive
      |  --assembly             Fat JAR with all dependencies bundled
      |  --standalone           Self-contained binary (like the ssc binary itself)
      |  --native               GraalVM native image (requires native-image)
      |  -o, --output <path>    Output file (default: input filename without .ssc)
      |  --force, -f            Overwrite existing output
      |  (any other scala-cli package flag is forwarded as-is)
      |
      |Compiler diagnostic flags:
      |  --Ystats               Print per-phase compiler timing table after each build/check
      |
      |Logging flags:
      |  --quiet                       Silence third-party library logs (SLF4J -> error)
      |  --logs.defaultLevel=<level>   ssc logger root level (warn|info|debug|error; default: warn)
      |  --logs.<name>.level=<level>   Per-logger level, e.g. --logs.scalascript.server.level=info
      |  --logs.logFile=System.out     Write ssc logs to stdout instead of stderr
      |  Any --logs.KEY=VALUE maps to -Dscalascript.logger.KEY=VALUE
      |
      |Examples:
      |  ssc examples/hello.ssc
      |  ssc compile examples/hello.ssc
      |  ssc package --assembly -o hello.jar examples/hello.ssc
      |  ssc emit-js examples/hello.ssc | node
      |  ssc emit-spa examples/spa-demo.ssc > spa.html
      |  ssc serve 8080
      |""".stripMargin
  println(header + "\n" + Help.renderCommands() + tail)

final class ServeCmd extends CliCommand:
  def name = "serve"
  override def summary = "Start an HTTP server serving .ssc files as web pages"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
    // `ssc serve file.ssc` — run a .ssc server script with hot-reload.
    // `ssc serve [port] [dir]` — serve static files from a directory.
    args.headOption match
      case Some(f) if f.endsWith(".ssc") => CommandRegistry.dispatch("watch", args)
      case _ =>
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
        val dir  = args.drop(1).headOption.getOrElse(".")
        printComponentUrls("static server", "0.0.0.0", port, backendUrl = None)
        scalascript.server.WebServer.start(port, dir, System.out)

/** `ssc render <file> [path]` — runs the .ssc file in headless mode
 *  (skipping the blocking `serve(port)` call), then invokes the
 *  registered GET handler for `path` (default `/`) with a synthetic
 *  request and prints the response body to stdout.  Useful for
 *  generating static HTML from a server-style `.ssc` page without
 *  booting an HTTP listener. */
final class RenderCmd extends CliCommand:
  def name = "render"
  override def summary = "Render a single .ssc to static HTML via its registered handler"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
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
private[cli] def buildArtifactsInto(
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
    fat: Boolean = false,
    legacySwift: Boolean = false,
    backendBaseUrl: Option[String] = None,
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
      try CommandRegistry.dispatch("emit-js", List(projectFile.toString))
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
      println(s"Building ${if legacySwift then "legacy SwiftUI" else "v2 Swift"} iOS package → ${displayPath(outDir)}")
      if legacySwift then buildSwiftUIPackage(projectFile, outDir, "ios", runSwiftBuild = fat)
      else buildV2SwiftPackage(projectFile, outDir, _root_.ssc.swift.SwiftPlatform.IOS, runSwiftBuild = fat, backendBaseUrl = backendBaseUrl)
      if !fat then
        println(s"  Swift package written. iOS application build continues after the NativeUi App target is generated.")

    case "macos" | "desktop-macos" =>
      println(s"Building ${if legacySwift then "legacy SwiftUI" else "v2 Swift"} macOS package → ${displayPath(outDir)}")
      if legacySwift then buildSwiftUIPackage(projectFile, outDir, "macos", runSwiftBuild = fat)
      else buildV2SwiftPackage(projectFile, outDir, _root_.ssc.swift.SwiftPlatform.MacOS, runSwiftBuild = fat, backendBaseUrl = backendBaseUrl)
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
final class BuildCmd extends CliCommand:
  def name = "build"
  override def summary = "Batch-render a directory's .ssc to <out-dir> (or --incremental artifacts)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit =
    // v2.0: --incremental flag routes to separate-compilation build orchestrator.
    if args.contains("--incremental") then
      val rest = args.filterNot(_ == "--incremental")
      incrementalBuildCommand(rest)
      return

    // Parse --target and --out flags, collect remaining positionals.
    var targetFlag: Option[String] = None
    var outFlag:    Option[String] = None
    var v1Flag:     Boolean        = false
    var v2Flag:     Boolean        = false
    var serverUrlFlag: Option[String] = None
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    val remaining  = args.iterator
    while remaining.hasNext do
      remaining.next() match
        case "--target" if remaining.hasNext => targetFlag = Some(remaining.next())
        case "--out"    if remaining.hasNext => outFlag    = Some(remaining.next())
        case "--v1"                          => v1Flag     = true
        case "--v2"                          => v2Flag     = true
        case "--server-url" if remaining.hasNext => serverUrlFlag = Some(remaining.next())
        case other                           => positional += other

    if v1Flag && v2Flag then
      System.err.println("ssc build: --v1 and --v2 are mutually exclusive")
      System.exit(1)

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
        buildProjectFileCommand(pf, effective, outDir, legacySwift = v1Flag, backendBaseUrl = serverUrlFlag)
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

private[cli] def displayPath(p: os.Path): String =
  val cwd = os.pwd.toString
  val s   = p.toString
  if s.startsWith(cwd + "/") then s.substring(cwd.length + 1) else s

private[cli] def outPathFor(outDir: os.Path, urlPath: String): os.Path =
  val clean = urlPath.stripPrefix("/").stripSuffix("/")
  if clean.isEmpty then outDir / "index.html"
  else if clean.endsWith(".html") then outDir / os.SubPath(clean)
  else
    val segs = clean.split('/').toIndexedSeq
    val withExt = segs.init :+ (segs.last + ".html")
    outDir / os.SubPath(withExt.mkString("/"))

final class NewCmd extends CliCommand:
  def name = "new"
  override def summary = "Scaffold a new project (e.g. --template plugin)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit =
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
        System.err.println("Usage: ssc new <name> [--template app|lib|plugin|dsl|web-app|wasm-app] [--output-dir <dir>]")
        System.exit(1)

/** Find the "project" `.ssc` file for the current directory.
 *  Prefers a file whose stem matches the directory name (`myapp/myapp.ssc`);
 *  falls back to the single `.ssc` file in the directory if there is exactly one. */
private[cli] def findProjectSsc(): Option[os.Path] =
  val dir     = os.pwd
  val byName  = dir / s"${dir.last}.ssc"
  if os.exists(byName) then Some(byName)
  else
    val found = os.list(dir).filter(p => p.ext == "ssc" && os.isFile(p))
    if found.length == 1 then Some(found.head) else None

/** Common-ancestor directory of a non-empty list of paths.
 *  Same as `paths.head`'s parents, narrowed to whichever still
 *  starts every other path. */
private[cli] def commonAncestor(paths: List[os.Path]): os.Path =
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
private[cli] def relativeArchivePath(fromDir: String, target: String): String =
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

final class ParseCmd extends CliCommand:
  def name = "parse"
  override def summary = "Parse .ssc files and print the AST"
  override def category = "Check & inspect"
  def run(args: List[String]): Unit =
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
/** Recursively inline `[name](path.ssc)` file imports into a flat list of the
 *  imported modules' sections, for the Rust backend (which does not resolve
 *  imports inside codegen the way JVM/JS do).  Transitive imports resolve
 *  relative to the importing file's directory; deduped by resolved path
 *  (cycle-safe).  `dep:`/URL imports and parse failures are skipped silently —
 *  the backend then surfaces any genuinely missing symbol. */
private def inlineImportsRust(
    module:  scalascript.ast.Module,
    baseDir: os.Path,
    seen:    scala.collection.mutable.Set[String]
): List[scalascript.ast.Section] =
  import scalascript.ast.{Section, Content}
  def importPaths(secs: List[Section]): List[String] =
    secs.flatMap(s =>
      s.content.collect { case i: Content.Import => i.path } ++ importPaths(s.subsections))
  importPaths(module.sections).flatMap { rawPath =>
    val resolved =
      try Some(scalascript.imports.ImportResolver.resolve(rawPath, baseDir))
      catch { case _: Throwable => None }
    resolved match
      case Some(p) if os.exists(p) && !os.isDir(p) && !p.last.endsWith(".sscc")
          && !seen.contains(p.toString) =>
        seen += p.toString
        scala.util.Try(Parser.parse(os.read(p))).toOption match
          case Some(im) => inlineImportsRust(im, p / os.up, seen) ++ im.sections
          case None     => Nil
      case _ => Nil
  }

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

final class RunCmd extends CliCommand:
  def name = "run"
  override def summary = "Execute .ssc via the v2 VM by default"
  override def category = "Run & develop"
  override def details = List("Flags: --frontend <custom|react|solid|vue|electron|swing|javafx|swiftui>", "       --mode <server|client> / --transport <http|in-process>", "       --host <addr> / --port <n> / --open-browser | --no-open-browser", "       --native  (self-hosted frontend -> CoreIR -> v2 VM; no compiler process)", "       --v2 / --compat-frontend  (self-hosted native front -> CoreIR -> v2 VM)", "       --v1  (rollback to the v1 tree-walking interpreter)", "       --bytecode  (direct ASM execution; combines with --native)", "       -- separates source files from program args for v2 VM runners")
  def run(args: List[String]): Unit =
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
    var teamIdFlag:        Option[String] = None   // --team-id <developer team>
    var v1Flag:            Boolean        = false  // --v1 (rollback to the v1 tree-walking interpreter)
    var v2Flag:            Boolean        = false  // --v2 (force the ssc 2.0 VM via FrontendBridge)
    var nativeFlag:        Boolean        = false  // --native (self-hosted frontend -> v2)
    var compatFrontendFlag: Boolean       = false  // --compat-frontend (explicit Scalameta bridge)
    var bytecodeFlag:      Boolean        = false  // --bytecode (v2 lane compiled to JVM bytecode, Phase 4)
    val fileArgs = scala.collection.mutable.ArrayBuffer.empty[String]
    val programArgs = scala.collection.mutable.ArrayBuffer.empty[String]
    var afterArgSeparator = false
    val it = args.iterator
    while it.hasNext do
      val next = it.next()
      if afterArgSeparator then programArgs += next
      else next match
        case "--" => afterArgSeparator = true
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
        case "--team-id" if it.hasNext    => teamIdFlag = Some(it.next())
        case "--v1"                        => v1Flag       = true
        case "--v2"                        => v2Flag       = true
        case "--native"                    => nativeFlag   = true
        case "--compat-frontend"           => compatFrontendFlag = true
        case "--bytecode"                  => bytecodeFlag = true
        case "--device-id" if it.hasNext  => deviceIdFlag = Some(it.next()); deviceFlag = true
        case "--frontend"         if it.hasNext =>
          val name = it.next()
          if !validFrontendNames(name) then
            System.err.println(s"run: unknown --frontend '$name', valid: ${validFrontendNames.mkString(", ")}")
            System.exit(1)
          frontendFlag = Some(name)
        case f => fileArgs += f
    val programArgv = programArgs.toList
    def rejectProgramArgs(mode: String): Unit =
      if programArgv.nonEmpty then
        System.err.println(s"run: program args after -- are supported only by v2 VM runners, not $mode")
        System.err.println("Usage: ssc run [--native|--compat-frontend|--v2|--bytecode] <file.ssc> -- [args...]")
        System.exit(1)

    val frontendRoutes = List(v1Flag, nativeFlag, v2Flag || compatFrontendFlag).count(identity)
    if frontendRoutes > 1 then
      System.err.println("run: --v1, --native, and --compat-frontend/--v2 are mutually exclusive")
      System.exit(1)

    val targetSelection = targetFlag.orElse(ActiveFlags.current.target)
    val appleTarget = targetSelection.exists {
      case "macos" | "desktop-macos" | "ios" | "mobile-ios" => true
      case _ => false
    }

    if nativeFlag then
      if fileArgs.isEmpty then { println("Error: No files specified"); System.exit(1) }
      try RunNativeV2.run(fileArgs.toList, programArgv, bytecodeFlag)
      catch
        case failure: _root_.ssc.ControlRunFailure =>
          System.err.println(failure.rendered)
          System.exit(1)
        case e: Exception =>
          System.err.println(s"run --native: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
          System.exit(1)
      return

    // `--v2` / `--bytecode`: the ssc 2.0 runtime via the native ssc1 front (the
    // scalameta FrontendBridge/RunV2 tier has been retired — this is the same
    // path `bin/ssc run --v2` uses). Plain default-lane runs reach it below too
    // unless `--v1` selects the tree-walking interpreter.
    if bytecodeFlag then
      if fileArgs.isEmpty then { println("Error: No files specified"); System.exit(1) }
      try RunNativeV2.run(fileArgs.toList, programArgv, bytecode = true)
      catch case failure: _root_.ssc.ControlRunFailure =>
        System.err.println(failure.rendered)
        System.exit(1)
      return

    if (v2Flag || compatFrontendFlag) && !appleTarget then
      if fileArgs.isEmpty then { println("Error: No files specified"); System.exit(1) }
      try RunNativeV2.run(fileArgs.toList, programArgv, bytecode = false)
      catch case failure: _root_.ssc.ControlRunFailure =>
        System.err.println(failure.rendered)
        System.exit(1)
      return

    val runMode = modeFlag.map(_.trim.toLowerCase)
    runMode match
      case Some("server") =>
        rejectProgramArgs("run --mode server")
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
        rejectProgramArgs("run --mode client")
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
      rejectProgramArgs("run --target jvm")
      for file <- fileArgs.toList do
        runJvmViaScalaCli(os.Path(file, os.pwd), serverBackendFlag, "run --target jvm")
      return

    // --target macos / desktop-macos: build Swift package + swift build + launch binary
    if targetSelection.exists(t => t == "macos" || t == "desktop-macos") then
      rejectProgramArgs("run --target macos")
      if v1Flag then runMacosTargets(fileArgs.toList, rebuildFlag, consoleFlag)
      else runV2MacosTargets(fileArgs.toList, serverUrlFlag)
      return

    // --target ios / mobile-ios: Simulator (default) or real device (--device)
    if targetSelection.exists(t => t == "ios" || t == "mobile-ios") then
      rejectProgramArgs("run --target ios")
      if v1Flag then runIosTargets(fileArgs.toList, deviceFlag, deviceIdFlag, consoleFlag, rebuildFlag)
      else runV2IosTargets(
        fileArgs.toList, consoleFlag, deviceFlag, deviceIdFlag, teamIdFlag, serverUrlFlag)
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
        rejectProgramArgs("frontend/electron default run")
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
      rejectProgramArgs("Electron JVM REST run")
      for file <- fileArgs.toList do
        runElectronJvmRestDevHook(os.Path(file, os.pwd), serverBackendFlag)
      return
    if isElectronRun then
      rejectProgramArgs("Electron run")
      for file <- fileArgs.toList do
        runElectronDev(os.Path(file, os.pwd))
      return

    // --frontend tui → the live ratatui terminal runner (rust-tui-toolkit S5):
    // transpile to a Cargo crate (uiTarget=tui) + `cargo run`, so signal/
    // computedSignal reactivity is LIVE. Supersedes the static frontend/tui
    // emitter. Falls back to the interpreter path below when cargo is absent.
    if frontendFlag.contains("tui") && TuiRunner.cargoAvailable then
      rejectProgramArgs("TUI run")
      for file <- fileArgs.toList do
        val code = TuiRunner.runFile(os.Path(file, os.pwd))
        if code != 0 then System.exit(code)
      return

    if !v1Flag &&
        runFlagsAllowV2Default(
          targetSelection,
          frontendFlag,
          ActiveFlags.current.backend,
          runMode,
          serverUrlFlag,
          transportFlag,
          hostFlag,
          portFlag,
          openBrowserFlag
        ) &&
        fileArgs.nonEmpty &&
        fileArgs.toList.forall { file =>
          val path = os.Path(file, os.pwd)
          os.exists(path) &&
            scala.util.Try(shouldUseV2DefaultRunner(loadModule(path))).getOrElse(false)
        }
    then
      try RunNativeV2.run(fileArgs.toList, programArgv, bytecode = false)
      catch case failure: _root_.ssc.ControlRunFailure =>
        System.err.println(failure.rendered)
        System.exit(1)
      return

    rejectProgramArgs("the selected non-v2 runner")

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

/** `ssc run --target macos|desktop-macos`: build a SwiftUI package per file and
 *  launch the binary (rebuilding only when the `.ssc` is newer or `--rebuild`).
 *  Extracted from `RunCmd.run` for readability — behaviour unchanged. */
private[cli] def runMacosTargets(files: List[String], rebuild: Boolean, console: Boolean): Unit =
  val outDir = os.Path("target/build", os.pwd) / "macos"
  for file <- files do
    val sscFile = os.Path(file, os.pwd)
    val appName = swiftAppName(
      scala.util.Try(Parser.parse(os.read(sscFile)).manifest.flatMap(_.name)).toOption.flatten
    )
    val binary = outDir / ".build" / "debug" / appName
    val needsBuild = rebuild || !os.exists(binary) || os.mtime(sscFile) > os.mtime(binary)
    if needsBuild then
      buildSwiftUIPackage(sscFile, outDir, "macos", runSwiftBuild = true)
    else
      println(s"  Skipping build (no .ssc changes). Use --rebuild to force.")
    if !os.exists(binary) then
      System.err.println(s"swift build did not produce ${displayPath(binary)}")
      System.exit(1)
    println(s"  Launching $appName...")
    if console then
      os.proc(binary).call(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir, check = false)
    else
      os.proc(binary).spawn(stdout = os.Inherit, stderr = os.Inherit, cwd = outDir)

/** v2 Apple desktop route: checked frontend → CoreIR → generated AppCore
 *  package → SwiftPM. This deliberately shares no Parser/JvmGen/SwiftUIEmitter
 *  state with the compatibility route above. */
private[cli] def runV2MacosTargets(files: List[String], backendBaseUrl: Option[String] = None): Unit =
  val outRoot = os.Path("target/build", os.pwd) / "macos"
  for file <- files do
    val input = os.Path(file, os.pwd)
    try
      val emitted = buildV2SwiftPackage(
        input,
        outRoot,
        _root_.ssc.swift.SwiftPlatform.MacOS,
        backendBaseUrl = backendBaseUrl,
      )
      emitted.xcodeApp match
        case Some(_) =>
          val built = SwiftV2Cli.buildXcodeApplication(
            emitted, "platform=macOS", outRoot / "derived", "run --target macos")
          new ProcessBuilder(built.executable.toString).inheritIO().start()
        case None =>
          val exit = SwiftV2Cli.runPackage(emitted, Nil, "run --target macos")
          if exit != 0 then System.exit(exit)
    catch case e: Exception =>
      System.err.println(s"run --target macos: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
      System.exit(1)

/** `ssc run --target ios|mobile-ios`: simulator (default) or real device (`--device`).
 *  Extracted from `RunCmd.run` for readability — behaviour unchanged. */
private[cli] def runIosTargets(files: List[String], device: Boolean, deviceId: Option[String],
                               console: Boolean, rebuild: Boolean): Unit =
  if device then
    val outDir = os.Path("target/build", os.pwd) / "ios-device"
    for file <- files do
      runSwiftUIIosDevice(os.Path(file, os.pwd), outDir, console, rebuild, deviceId)
  else
    val outDir = os.Path("target/build", os.pwd) / "ios"
    for file <- files do
      runSwiftUIIosSimulator(os.Path(file, os.pwd), outDir, console, rebuild)

private[cli] def runV2IosTargets(files: List[String], console: Boolean): Unit =
  runV2IosTargets(files, console, device = false, None, None, None)

private[cli] def runV2IosTargets(
    files: List[String],
    console: Boolean,
    device: Boolean,
    deviceId: Option[String],
    teamId: Option[String],
    backendBaseUrl: Option[String],
): Unit =
  try
    if device then
      val resolvedTeam = SwiftV2Distribution.resolveTeam(teamId, sys.env, "run --target ios --device")
      SwiftV2Distribution.requireIosDeploy("run --target ios --device")
      SwiftV2Distribution.requireXcodebuild("run --target ios --device")
      for file <- files do
        val outDir = os.Path("target/build", os.pwd) / "ios-device"
        val context = SwiftV2Distribution.context(
          os.Path(file, os.pwd), outDir, _root_.ssc.swift.SwiftPlatform.IOS,
          backendBaseUrl, "run --target ios --device")
        SwiftV2Distribution.runIosDevice(
          context, resolvedTeam, deviceId, console, "run --target ios --device")
    else
      val outDir = os.Path("target/build", os.pwd) / "ios"
      val (simUdid, simName) = pickIosSimulator().getOrElse {
        throw new IllegalStateException("run --target ios: no available iOS Simulator")
      }
      for file <- files do
        val emitted = buildV2SwiftPackage(
          os.Path(file, os.pwd), outDir, _root_.ssc.swift.SwiftPlatform.IOS,
          backendBaseUrl = backendBaseUrl)
        val built = SwiftV2Cli.buildXcodeApplication(
          emitted, s"platform=iOS Simulator,id=$simUdid", outDir / "derived", "run --target ios")
        os.proc("xcrun", "simctl", "boot", simUdid).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        val install = os.proc("xcrun", "simctl", "install", simUdid, built.bundle.toString)
          .call(check = false, stdout = os.Inherit, stderr = os.Inherit)
        if install.exitCode != 0 then throw new IllegalStateException("run --target ios: simulator install failed")
        val bundleId = emitted.xcodeApp.get.bundleId
        val launchArgs = if console then List("--console", simUdid, bundleId) else List(simUdid, bundleId)
        val launch = os.proc(List("xcrun", "simctl", "launch") ++ launchArgs)
          .call(check = false, stdout = os.Inherit, stderr = os.Inherit)
        if launch.exitCode != 0 then throw new IllegalStateException(s"run --target ios: launch failed on $simName")
  catch case e: Exception =>
    val detail = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    val message = if detail.startsWith("run --target ios") then detail else s"run --target ios: $detail"
    System.err.println(message)
    System.exit(1)

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

private[cli] def runFlagsAllowV2Default(
    targetSelection: Option[String],
    frontendFlag:    Option[String],
    backendFlag:     Option[String],
    runMode:         Option[String],
    serverUrlFlag:   Option[String],
    transportFlag:   Option[BackendTransportKind],
    hostFlag:        Option[String],
    portFlag:        Option[Int],
    openBrowserFlag: Option[Boolean]
): Boolean =
  targetSelection.isEmpty &&
    frontendFlag.isEmpty &&
    backendFlag.isEmpty &&
    runMode.isEmpty &&
    serverUrlFlag.isEmpty &&
    transportFlag.isEmpty &&
    hostFlag.isEmpty &&
    portFlag.isEmpty &&
    openBrowserFlag.isEmpty

private[cli] def shouldUseV2DefaultRunner(module: Module): Boolean =
  val explicitLaneKeys = Set("backend", "frontend", "target", "transport", "fullstack")
  val rawKeys =
    module.manifest
      .map(_.raw.keySet.map(_.trim.toLowerCase).toSet)
      .getOrElse(Set.empty)
  rawKeys.intersect(explicitLaneKeys).isEmpty &&
    module.manifest.flatMap(_.frontendFramework).isEmpty

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


final class WatchCmd extends CliCommand:
  def name = "watch"
  override def summary = "Run .ssc and re-run on every file change"
  override def category = "Run & develop"
  override def details = List("Flags: --frontend <custom|react|solid|vue|swing>")
  def run(args: List[String]): Unit =
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
            module.sections,
            firstChanged,
            interpCheckpoints,
            module.document.map(_.sections).getOrElse(Nil)
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

final class WatchBenchCmd extends CliCommand:
  def name = "watch-bench"
  override def summary = "Benchmark one watch reload cycle on a temp copy"
  override def category = "Run & develop"
  override def details = List("Flags: --cycles <n>, --target-ms <n>, --require-target")
  def run(args: List[String]): Unit =
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
        checkpoints = interp.runSectionsIncremental(
          module.sections,
          firstChanged,
          checkpoints,
          module.document.map(_.sections).getOrElse(Nil)
        )
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
final class EmitSparkCmd extends CliCommand:
  def name = "emit-spark"
  override def summary = "Print generated Scala 3 + Spark program to stdout"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit =
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
final class SubmitCmd extends CliCommand:
  def name = "submit"
  override def summary = "Package .ssc as a Spark fat JAR and launch via spark-submit"
  override def category = "Emit & transpile"
  override def details = List("Flags: --spark-master <url>, --spark-version <v>, --dry-run")
  def run(args: List[String]): Unit =
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
final class EmitInterfaceCmd extends CliCommand:
  def name = "emit-interface"
  override def summary = "Extract module interface to a .scim artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
final class EmitIrCmd extends CliCommand:
  def name = "emit-ir"
  override def summary = "Emit normalised module IR to a .scir artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
final class RunJvmCmd extends CliCommand:
  def name = "run-jvm"
  override def summary = "Compile via JvmGen and run immediately via scala-cli"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
    val x402ToolsClasspath =
      if !source.contains("scalascript.x402.") then None
      else
        scalascript.imports.ImportResolver.libPath.flatMap { libRoot =>
          val lane = libRoot / "bin" / "lib" / "tools" / "x402"
          val classes = lane / "classes"
          val jars = lane / "jars"
          val entries =
            (if os.exists(classes) then List(classes) else Nil) ++
            (if os.exists(jars) then os.list(jars).filter(_.ext == "jar").sorted else Nil)
          Option.when(entries.nonEmpty)(entries.map(_.toString)
            .mkString(java.io.File.pathSeparator))
        }
    if source.contains("scalascript.x402.") && x402ToolsClasspath.isEmpty then
      System.err.println("run-jvm: x402 tools runtime is not installed; run installBin")
      System.exit(1)
    val tmp = os.temp(source, suffix = ".sc", deleteOnExit = true)
    try
      // SSC_SCALACLI_SERVER=1 keeps the bloop server (warm compiler): the
      // conformance JVM lane pays a COLD scalac start per case otherwise
      // (specs/conformance-perf.md F3). Default stays serverless.
      val scliArgs =
        (if sys.env.get("SSC_SCALACLI_SERVER").contains("1") then Seq("scala-cli", "run", tmp.toString)
         else Seq("scala-cli", "run", tmp.toString, "--server=false")) ++
          x402ToolsClasspath.toSeq.flatMap(cp => Seq("--classpath", cp))
      val sub = os.proc(scliArgs)
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
private[cli] def patchLocalSscDeps(source: String, jarsDir: os.Path): String =
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

final class RunJsCmd extends CliCommand:
  def name = "run-js"
  override def summary = "Compile via JsGen and run immediately via node"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
    if args.isEmpty then
      System.err.println("Usage: ssc run-js [--v2] [--frontend <custom|react|solid|vue>] <file.ssc> [args...]")
      System.exit(1)
    var jsFrontendFlag: Option[String] = None
    var jsFileArg:      Option[String] = None
    var jsV2Flag:       Boolean        = false
    val jsArgv = scala.collection.mutable.ArrayBuffer.empty[String]
    val jsIt = args.iterator
    while jsIt.hasNext do
      jsIt.next() match
        case "--v2" => jsV2Flag = true
        case "--frontend" if jsIt.hasNext =>
          val name = jsIt.next()
          if !browserFrontendNames(name) then
            System.err.println(s"run-js: unknown --frontend '$name', valid: ${browserFrontendNames.mkString(", ")}")
            System.exit(1)
          jsFrontendFlag = Some(name)
        case f =>
          if jsFileArg.isEmpty then jsFileArg = Some(f)
          else jsArgv += f
    val file = jsFileArg.getOrElse {
      System.err.println("Usage: ssc run-js [--v2] [--frontend <custom|react|solid|vue>] <file.ssc> [args...]")
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
    if jsV2Flag then
      RunNativeV2.runJs(List(path.toString), jsArgv.toList)
      return
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
        val frontendInit = effectiveFrontendName.fold("")(n => s"_ssc_frontend_name = '${n.replace("'", "\\'")}'; globalThis._ssc_frontend_name = _ssc_frontend_name; // injected by ssc\n")
        val bundle  = runtime + "\n" + frontendInit + userCode
        val pkgJson = sources.collectFirst { case scalascript.backend.spi.SourceArtifact("package.json", c) => c }
        pkgJson match
          case None =>
            // No npm deps — write single temp file and run directly.
            val tmp = os.temp(bundle, suffix = ".cjs", deleteOnExit = true)
            runNodeAndWait(Seq("node", tmp.toString) ++ jsArgv.toSeq, cwd = None)
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
            runNodeAndWait(Seq("node", "main.cjs") ++ jsArgv.toSeq, cwd = Some(workDir))
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
final class CompileJvmCmd extends CliCommand:
  def name = "compile-jvm"
  override def summary = "Emit JVM-backend cached Scala source to .scjvm"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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

    // `--bytecode` is opt-in.  When the external scala-cli path is forced via
    // SSC_EXTERNAL_SCALA_CLI=1, scala-cli must be on PATH — fail loudly rather
    // than silently downgrade to source-only.  The default in-process Scala 3
    // driver does NOT require scala-cli, so we only gate when external mode is
    // explicitly requested.
    if bytecode && JvmBytecode.useExternalScalaCli && !JvmBytecode.scalaCliAvailable then
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
final class CompileRuntimeCmd extends CliCommand:
  def name = "compile-runtime"
  override def summary = "Emit the cached backend runtime artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
final class CompileJsCmd extends CliCommand:
  def name = "compile-js"
  override def summary = "Emit JS-backend cached JS source to .scjs"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
final class DepsCmd extends CliCommand:
  def name = "deps"
  override def summary = "Print the resolved import/dependency graph"
  override def category = "Check & inspect"
  def run(args: List[String]): Unit =
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
final class CleanCmd extends CliCommand:
  def name = "clean"
  override def summary = "Remove stale v2.0 artifacts"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --dry-run, --all")
  def run(args: List[String]): Unit =
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
final class InfoCmd extends CliCommand:
  def name = "info"
  override def summary = "Inspect a .scim/.scir/.scjvm/.scjs artifact"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --json (dump the full envelope)")
  def run(args: List[String]): Unit =
    var jsonMode     = false
    var sectionsMode = false
    var registryArg: Option[String] = None
    val files        = scala.collection.mutable.ArrayBuffer.empty[String]
    val it2          = args.iterator
    while it2.hasNext do
      it2.next() match
        case "--json"                       => jsonMode     = true
        case "--sections"                   => sectionsMode = true
        case "--registry" if it2.hasNext   => registryArg  = Some(it2.next())
        case f                             => files        += f
    if files.isEmpty then
      System.err.println("Usage: ssc info <name-or-artifact> [--json] [--sections] [--registry <url>]")
      System.err.println("  Registry: ssc info io.example/lib")
      System.err.println("  Artifact: ssc info <file>.scim  (supported: .scim, .scir, .scjvm, .scjs)")
      System.exit(1)

    // If first arg looks like a registry name (<group>/<artifact>), dispatch to registry info.
    // A path is a file reference (not a registry name) if it starts with '/', './', '../',
    // or refers to a file that exists on disk.
    val firstArg = files.head
    val looksLikeFilePath = firstArg.startsWith("/") || firstArg.startsWith("./") ||
      firstArg.startsWith("../") || os.exists(os.Path(firstArg, os.pwd))
    if !looksLikeFilePath && firstArg.contains('/') && !Set("scim", "scir", "scjvm", "scjs").contains(firstArg.split('.').lastOption.getOrElse("")) then
      import scalascript.imports.RegistryClient
      val url     = RegistryClient.effectiveUrl(registryArg)
      val entries = RegistryClient.load(url, refresh = registryArg.isDefined)
      entries.find(_.name == firstArg) match
        case None =>
          System.err.println(s"Package '${firstArg}' not found in registry.")
          System.err.println(s"Run 'ssc search' to browse available packages.")
          System.exit(1)
        case Some(e) =>
          print(RegistryClient.formatInfo(e))
      return

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
      val bytes   = os.read.bytes(path)
      val fileSize = bytes.length
      ext match
        case "scim"  => printScimInfo(path, bytes, fileSize, jsonMode, sectionsMode)
        case "scir"  => printScirInfo(path, bytes, fileSize, jsonMode, sectionsMode)
        case "scjvm" => printScjvmInfo(path, bytes, fileSize, jsonMode, sectionsMode)
        case "scjs"  => printScjsInfo(path, bytes, fileSize, jsonMode, sectionsMode)
        case _       => () // unreachable — extension already validated above
    catch case e: Exception =>
      System.err.println(s"info: ${e.getMessage}")
      System.exit(1)

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
final class VerifyCmd extends CliCommand:
  def name = "verify"
  override def summary = "Health-check v2.0 artifacts in a directory"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --strict, --src-dir <dir>, --json")
  def run(args: List[String]): Unit =
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

    val srcDir = srcDirArg.map(os.Path(_, os.pwd)).getOrElse(defaultVerifySrcDir(artifactDir))
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

private def defaultVerifySrcDir(artifactDir: os.Path): os.Path =
  if artifactDir.last == ".ssc-artifacts" then artifactDir / os.up
  else artifactDir

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

final class CheckCompatCmd extends CliCommand:
  def name = "check-compat"
  override def summary = "Compare public .scim interfaces; fail on breaking changes"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
    if args.length != 2 then
      System.err.println("Usage: ssc check-compat <old.ssclib> <new.ssclib>")
      System.exit(1)
    val oldPath = os.Path(args(0), os.pwd)
    val newPath = os.Path(args(1), os.pwd)
    try
      val report = checkSsclibCompat(oldPath, newPath)
      if report.isCompatible then
        println(s"Compatible: ${oldPath.last} -> ${newPath.last}")
      else
        report.removed.foreach(s => println(s"REMOVED $s"))
        report.changed.foreach(s => println(s"CHANGED $s"))
        System.exit(1)
    catch case e: RuntimeException =>
      System.err.println(s"check-compat: ${e.getMessage}")
      System.exit(1)

final class PackageCmd extends CliCommand:
  def name = "package"
  override def summary = "Package .ssc via scala-cli (see Package flags below)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit =
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
    var notaryProfileFlag: Option[String] = None
    var notaryTimeoutFlag: Option[String] = None
    var v1Flag:           Boolean        = false
    var v2Flag:           Boolean        = false
    var serverUrlFlag:    Option[String] = None
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
        case "--notary-profile" if it.hasNext => notaryProfileFlag = Some(it.next())
        case "--notary-timeout-seconds" if it.hasNext => notaryTimeoutFlag = Some(it.next())
        case "--v1"                         => v1Flag            = true
        case "--v2"                         => v2Flag            = true
        case "--server-url" if it.hasNext  => serverUrlFlag     = Some(it.next())
        case other                          => positional += other

    if v1Flag && v2Flag then
      System.err.println("ssc package: --v1 and --v2 are mutually exclusive")
      System.exit(1)

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
        val effectiveTarget = targetFlag.orElse(ActiveFlags.current.target)
        val outDir   = os.Path(outFlag.getOrElse("target/package"), os.pwd)
        if v2Flag && effectiveTarget.isEmpty then
          System.err.println("ssc package --v2: --target is required")
          System.exit(1)
        val explicitV2Apple = !v1Flag && effectiveTarget.exists {
          case "ios" | "mobile-ios" => true
          case "macos" | "desktop-macos" => true
          case _ => false
        }
        if explicitV2Apple then
          try
            os.makeDir.all(outDir)
            effectiveTarget.get match
              case "ios" | "mobile-ios" =>
                val team = SwiftV2Distribution.resolveTeam(teamIdFlag, sys.env, "ssc package")
                val command = "ssc package --target ios"
                val exportMethod = SwiftV2Distribution.normalizeExportMethod(exportMethodFlag, command)
                SwiftV2Distribution.requireXcodebuild(command)
                val targetOut = outDir / effectiveTarget.get
                val context = SwiftV2Distribution.context(
                  pf, targetOut, _root_.ssc.swift.SwiftPlatform.IOS,
                  serverUrlFlag, command)
                val ipa = SwiftV2Distribution.packageIos(
                  context, exportMethod, team, command)
                println(s"  .ipa → ${displayPath(ipa)}")
              case "macos" | "desktop-macos" =>
                val targetOut = outDir / effectiveTarget.get
                if distributionFlag then
                  val command = "ssc package --target macos --distribution"
                  val team = SwiftV2Distribution.resolveTeam(teamIdFlag, sys.env, command)
                  val timeout = SwiftV2Distribution.parseNotaryTimeout(notaryTimeoutFlag, command)
                  val profile = notaryProfileFlag.orElse(sys.env.get("SSC_NOTARY_KEYCHAIN_PROFILE"))
                  SwiftV2Distribution.preflightMacDistribution(
                    notarizeFlag, dmgFlag, profile, command)
                  val context = SwiftV2Distribution.context(
                    pf, targetOut, _root_.ssc.swift.SwiftPlatform.MacOS,
                    serverUrlFlag, command)
                  val result = SwiftV2Distribution.packageMacDeveloperId(
                    context, team, notarizeFlag, dmgFlag, profile, timeout, command)
                  result.dmg match
                    case Some(path) => println(s"  .dmg → ${displayPath(path)}")
                    case None => println(s"  .app → ${displayPath(result.app)}")
                else
                  val command = "ssc package --target macos"
                  val packaged = SwiftV2Cli.packageMacos(pf, targetOut, serverUrlFlag, command)
                  if packaged.signed then
                    println(s"  Signed application (ad-hoc) → ${displayPath(packaged.artifact)}")
                  else
                    println(s"  Swift package → ${displayPath(packaged.artifact)}")
              case _ => ()
            return
          catch case e: Exception =>
            System.err.println(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            System.exit(1)

        val manifest = scala.util.Try(scalascript.parser.Parser.parse(os.read(pf)).manifest).toOption.flatten
        val name     = manifest.flatMap(_.name).getOrElse(pf.last.stripSuffix(".ssc"))
        val targets  = effectiveTarget.map(List(_))
          .orElse(manifest.map(_.targets).filter(_.nonEmpty))
          .getOrElse(List("ssc"))
        os.makeDir.all(outDir)

        println(s"Packaging $name  targets: ${targets.mkString(", ")}  →  ${displayPath(outDir)}/")
        for t <- targets do
          if t == "ios" || t == "mobile-ios" then
            if v1Flag then packageIosIpa(pf, outDir / t, exportMethodFlag, teamIdFlag)
            else
              buildV2SwiftPackage(pf, outDir / t, _root_.ssc.swift.SwiftPlatform.IOS, backendBaseUrl = serverUrlFlag)
              System.err.println(
                "ssc package --target ios: the v2 NativeUi application target is not generated yet; no v1 fallback was attempted"
              )
              System.exit(1)
          else if (t == "macos" || t == "desktop-macos") && distributionFlag then
            if v1Flag then packageMacosDistribution(pf, outDir / t, teamIdFlag, dmg = dmgFlag, notarize = notarizeFlag)
            else
              buildV2SwiftPackage(pf, outDir / t, _root_.ssc.swift.SwiftPlatform.MacOS, backendBaseUrl = serverUrlFlag)
              System.err.println(
                "ssc package --target macos --distribution: the v2 NativeUi application target is not generated yet; no v1 fallback was attempted"
              )
              System.exit(1)
          else
            buildProjectFileCommand(pf, Some(t), outDir, fat = true, legacySwift = v1Flag, backendBaseUrl = serverUrlFlag)

/** `ssc publish --target ios [--testflight|--appstore] [--fastlane] [--api-key-path <p>]
 *    [--submit-for-review] [--release-notes <text>] [<project.ssc>]`
 *
 *  Uploads an iOS app to TestFlight or App Store via fastlane.
 *  By default, generates a `Fastfile` in the project directory then invokes fastlane.
 *  `--fastlane` skips generation and uses the existing `Fastfile`. */
final class PublishCmd extends CliCommand:
  def name = "publish"
  override def summary = "Publish artifacts (TestFlight/App Store per --target)"
  override def category = "Dependencies & plugins"
  def run(args: List[String]): Unit =
    var targetFlag:          Option[String] = None
    var testflightFlag:      Boolean        = false
    var appstoreFlag:        Boolean        = false
    var fastlaneFlag:        Boolean        = false
    var apiKeyPathFlag:      Option[String] = None
    var submitForReviewFlag: Boolean        = false
    var releaseNotesFlag:    Option[String] = None
    var teamIdFlag:          Option[String] = None
    var v1Flag:              Boolean        = false
    var v2Flag:              Boolean        = false
    var serverUrlFlag:       Option[String] = None
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--target"           if it.hasNext => targetFlag       = Some(it.next())
        case "--api-key-path"     if it.hasNext => apiKeyPathFlag   = Some(it.next())
        case "--release-notes"    if it.hasNext => releaseNotesFlag = Some(it.next())
        case "--team-id"          if it.hasNext => teamIdFlag = Some(it.next())
        case "--testflight"                     => testflightFlag   = true
        case "--appstore"                       => appstoreFlag     = true
        case "--fastlane"                       => fastlaneFlag     = true
        case "--submit-for-review"              => submitForReviewFlag = true
        case "--v1"                             => v1Flag = true
        case "--v2"                             => v2Flag = true
        case "--server-url" if it.hasNext       => serverUrlFlag = Some(it.next())
        case other                              => positional += other

    if v1Flag && v2Flag then
      System.err.println("ssc publish: --v1 and --v2 are mutually exclusive")
      System.exit(1)

    val projectFile = positional.headOption match
      case Some(arg) =>
        val p = os.Path(arg, os.pwd)
        if os.exists(p) && p.ext == "ssc" then p
        else { System.err.println(s"ssc publish: file not found: $arg"); System.exit(1); ??? }
      case None => findProjectSsc().getOrElse {
        System.err.println("ssc publish: no project file found"); System.exit(1); ???
      }

    val effectiveTarget = targetFlag.orElse(ActiveFlags.current.target)

    val v2AppleTarget = !v1Flag && effectiveTarget.exists {
      case "ios" | "mobile-ios" | "macos" | "desktop-macos" => true
      case _ => false
    }
    if v2AppleTarget then
      try
        val target = effectiveTarget.get
        val isIos = target == "ios" || target == "mobile-ios"
        if isIos && !testflightFlag && !appstoreFlag then
          throw new IllegalArgumentException(
            "ssc publish --target ios: specify --testflight or --appstore")
        if !isIos && !appstoreFlag then
          throw new IllegalArgumentException(
            "ssc publish --target macos: specify --appstore")
        val command = s"ssc publish --target ${if isIos then "ios" else "macos"}"
        val team = SwiftV2Distribution.resolveTeam(teamIdFlag, sys.env, command)
        val apiKey = SwiftV2Distribution.requireApiKey(apiKeyPathFlag, sys.env, command)
        SwiftV2Distribution.requireFastlane(command)
        SwiftV2Distribution.requireXcodebuild(command)
        val output = os.Path("target/publish", os.pwd) / (if isIos then "ios" else "macos")
        val platform = if isIos then _root_.ssc.swift.SwiftPlatform.IOS else _root_.ssc.swift.SwiftPlatform.MacOS
        val context = SwiftV2Distribution.context(
          projectFile, output, platform, serverUrlFlag, command)
        val (artifact, lane) =
          if isIos then
            val ipa = SwiftV2Distribution.packageIos(
              context, "app-store-connect", team, command)
            ipa -> (if appstoreFlag then "appstore" else "testflight")
          else
            SwiftV2Distribution.packageMacAppStore(context, team, command) -> "mac_appstore"
        SwiftV2Distribution.runFastlane(
          context, lane, artifact, apiKey, releaseNotesFlag, submitForReviewFlag,
          fastlaneFlag, projectFile / os.up, command)
        return
      catch case e: Exception =>
        System.err.println(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        System.exit(1)

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

final class TestCmd extends CliCommand:
  def name = "test"
  override def summary = "Run component unit tests; non-zero exit on any failure"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
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
final class PreviewCmd extends CliCommand:
  def name = "preview"
  override def summary = "Open a browser preview of each front-matter component variant"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
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

/** check-autoload-plugin-by-import: the dotted import prefixes a module declares (e.g.
 *  `scalascript.x402.client` for `import scalascript.x402.client.{X402Client, Wallets}`). Used to
 *  auto-load a bundled-but-opt-in plugin whose `providesImports` covers one of them. */
private[cli] def importPrefixesOf(module: scalascript.ast.Module): Set[String] =
  import scala.meta.*
  // imports inside a ```scalascript fence are scala.meta.Import nodes in the block tree;
  // doc-level (markdown) imports are `Content.Import`. Collect both.
  def fromTree(t: scala.meta.Tree): List[String] =
    t.collect { case i: Import => i.importers.map(_.ref.syntax) }.flatten
  def loop(s: scalascript.ast.Section): List[String] =
    s.content.flatMap {
      case cb: scalascript.ast.Content.CodeBlock => cb.tree.toList.flatMap(n => fromTree(n.tree))
      case imp: scalascript.ast.Content.Import   => List(imp.path)
      case _                                     => Nil
    } ++ s.subsections.flatMap(loop)
  module.sections.flatMap(loop).toSet

/** Directories holding bundled-but-opt-in `.sscpkg` plugins (`lib/compiler/plugin-available/`),
 *  for `ssc check`'s import-driven auto-load. The install-relative dir plus an optional
 *  `-Dscalascript.pluginAvailableDir=…` override (used by tests / custom layouts). */
private[cli] def pluginAvailableDirs: List[os.Path] =
  val fromInstall = scalascript.imports.ImportResolver.libPath
    .map(_ / "bin" / "lib" / "compiler" / "plugin-available").toList
  val fromProp = Option(System.getProperty("scalascript.pluginAvailableDir"))
    .filter(_.nonEmpty).map(os.Path(_, os.pwd)).toList
  (fromInstall ++ fromProp).filter(os.exists).distinct

/** Check a single `.ssc` file.  Does NOT invoke any backend (no JvmGen / JsGen /
 *  Interpreter).  Returns a [[CheckResult]] summary.
 *
 *  @param file       display name of the file (used in diagnostics)
 *  @param interfaces pre-loaded `.scim` interfaces (may be empty)
 *  @param pluginBuiltins extra built-in names from loaded backend plugins
 */
// check-stdlib-interface-load: memoize extracted std-module interfaces across all files in one
// `ssc check examples/*.ssc` run — a std module (e.g. std/crypto.ssc) is imported by many examples
// and InterfaceExtractor.extract runs a full typeCheck per module.
private val stdIfaceCache = collection.mutable.Map.empty[os.Path, scalascript.ir.ModuleInterface]

// Intrinsic names from all bundled-but-opt-in plugins (pdf, extra crypto, totp, nfc, …). Computed
// once per process (scanning every .sscpkg is not free) and unioned into the check's builtins so
// `ssc check` resolves advanced-plugin intrinsic calls the same way it resolves essential ones.
private var availIntrinsicsMemo: Option[Set[String]] = None
private def availableIntrinsicNames(): Set[String] =
  availIntrinsicsMemo.getOrElse {
    val s = BackendRegistry.availableIntrinsicNames(pluginAvailableDirs)
    availIntrinsicsMemo = Some(s); s
  }

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
    val src    = os.read(path)
    val module = CompileStats.time("parse") { Parser.parse(src) }
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
      // core-min-prelude-spi: typed prelude symbols contributed by loaded plugins, so `ssc check`
      // type-checks calls to plugin intrinsics without the names being hardcoded in the Typer
      // prelude. `pluginBuiltins` (names-only intrinsic keys) stays as the fallback.
      // check-autoload-plugin-by-import: ALSO fold in preludeSymbols from bundled-but-opt-in plugins
      // whose `providesImports` matches one of this file's imports — so advanced names resolve for a
      // file that clearly intends them (e.g. `import scalascript.x402.*`) without a manual `--plugin`.
      val pluginPrelude =
        BackendRegistry.inProcess.flatMap(_.preludeSymbols) ++
          BackendRegistry.availablePreludeSymbols(pluginAvailableDirs)
      // check-stdlib-interface-load: resolve this file's imports to std `.ssc` modules and load their
      // exported types + `extern def`s, so `ssc check` resolves names the module provides — e.g.
      // `aesGcmEncrypt` (extern) from `import std.crypto.*`, `Money`/`BankAccount` (types) from a
      // `[Money](std/money.ssc)` link import. The static analogue of the interpreter's native module
      // loading. Memoized per resolved path (one std module is imported by many examples). Dotted
      // `scalascript.payments.*` names have no `.ssc` file — they come from plugin preludeSymbols
      // (availablePreludeSymbols) instead, so a failed resolve here is silently skipped.
      val stdIfaces: Map[String, scalascript.ir.ModuleInterface] =
        importPrefixesOf(module).iterator.flatMap { p =>
          val candidate = if p.endsWith(".ssc") || p.contains('/') then p else p.replace('.', '/') + ".ssc"
          try
            val resolved = scalascript.imports.ImportResolver.resolve(candidate, path / os.up)
            if os.exists(resolved) && resolved.ext == "ssc" then
              val iface = stdIfaceCache.getOrElseUpdate(resolved, {
                val bytes = os.read.bytes(resolved)
                scalascript.artifact.InterfaceExtractor.extract(Parser.parse(new String(bytes, "UTF-8")), bytes)
              })
              Some(p -> iface)
            else None
          catch case _: Throwable => None
        }.toMap
      val effIfaces = interfaces ++ stdIfaces
      val allBuiltins = pluginBuiltins ++ availableIntrinsicNames()
      val typed = CompileStats.time("typer") {
        if effIfaces.isEmpty then
          Typer(Map.empty, strict = true, extraBuiltins = allBuiltins, preludeSymbols = pluginPrelude)
            .typeCheck(module)
        else if strictNamespaces then
          Typer(effIfaces, strictNamespaces = true, preludeSymbols = pluginPrelude).typeCheck(module)
        else
          Typer(effIfaces, strict = true, extraBuiltins = allBuiltins, preludeSymbols = pluginPrelude)
            .typeCheck(module)
      }
      // declarative-ui Scope B.7 — warn on @ui=toolkit ids that reference no
      // registered action / data source (id-existence lint; warnings only).
      val lintWarnings = contentToolkitLintWarnings(file, module)
      // arch-meta-v2 macro-codegen — warn on interpreter-only quoted macros that
      // can't compile to the JVM/JS backends (warnings only).
      val macroWarnings = scalascript.artifact.MacroCodegen.codegenWarnings(module)
      // arch-meta-v2 C2 (conservative) — re-typecheck the macro/inline-EXPANDED module
      // and warn on any undefined name the expansion INTRODUCED (a broken macro/inline
      // body that the pre-expansion check above can't see). Self-gating via a pre/post
      // diff; warnings only; never breaks `ssc check`; free no-op for macro-free modules.
      val expansionWarnings = scalascript.artifact.MacroCodegen.expansionTypeWarnings(
        module, interfaces, pluginBuiltins, strictNamespaces, Some(path / os.up))
      val elapsed = System.currentTimeMillis() - t0
      CheckResult(file, parseErrors = false,
        errors = typed.errors ++ lintWarnings ++ macroWarnings ++ expansionWarnings, elapsedMs = elapsed)
  catch case e: Exception =>
    val elapsed = System.currentTimeMillis() - t0
    // Treat an unexpected exception as a parse error so the caller can assign
    // exit code 2.
    CheckResult(
      file, parseErrors = true,
      errors = List(scalascript.typer.TypeError(e.getMessage, None)),
      elapsedMs = elapsed
    )

/** declarative-ui Scope B.7 — id-existence lint for `@ui=toolkit` controls.
 *
 *  Returns warning [[TypeError]]s for a control `action:` / `source:` / `rows:`
 *  that references an id no `contentAction` / `contentRows` registers. Pure
 *  cross-check lives in [[scalascript.transform.ContentToolkitLint]]; this CLI
 *  glue gathers registrations across the entry module's transitively-imported
 *  `.ssc` modules so an id registered in another file is still recognised.
 *
 *  Conservative on incompleteness: if a **local (non-std/library)** import can
 *  not be resolved or parsed, the import graph may hide a registration, so the
 *  lint is suppressed for this file rather than risk a false positive. Std /
 *  `pkg:` / `dep:` / URL imports never carry registrations, so a failure to
 *  resolve one does not suppress the lint.
 */
private def contentToolkitLintWarnings(
  file: String, module: scalascript.ast.Module
): List[scalascript.typer.TypeError] =
  import scalascript.transform.ContentToolkitLint
  import scalascript.transform.ContentToolkitLint.Registrations
  // Fast out: no toolkit references → nothing to check (and no import work).
  if ContentToolkitLint.collectReferences(module).isEmpty then return Nil

  def importPaths(m: scalascript.ast.Module): List[String] =
    def loop(s: scalascript.ast.Section): List[String] =
      s.content.collect { case imp: scalascript.ast.Content.Import => imp.path } ++
        s.subsections.flatMap(loop)
    m.sections.flatMap(loop)

  // A std / packaged / remote import never registers a concrete toolkit id, so
  // failing to resolve it must not suppress the lint.
  def isLibraryImport(path: String): Boolean =
    path.startsWith("std/") || path.contains("/std/") ||
      path.startsWith("pkg:") || path.startsWith("dep:") ||
      path.startsWith("github:") || path.startsWith("jitpack:") ||
      path.startsWith("http://") || path.startsWith("https://")

  val seen     = scala.collection.mutable.Set.empty[String]
  var complete = true
  def collectFrom(m: scalascript.ast.Module, base: os.Path): Registrations =
    importPaths(m).foldLeft(Registrations.empty) { (acc, path) =>
      val resolved =
        try Some(scalascript.imports.ImportResolver.resolve(path, base))
        catch case _: Throwable => None
      resolved match
        case Some(p) if os.exists(p) && p.ext == "ssc" =>
          if seen.add(p.toString) then
            try
              val cm = Parser.parse(os.read(p))
              acc ++ ContentToolkitLint.collectRegistrations(cm) ++ collectFrom(cm, p / os.up)
            catch case _: Throwable =>
              if !isLibraryImport(path) then complete = false
              acc
          else acc
        case _ =>
          if !isLibraryImport(path) then complete = false
          acc
    }

  val base  = os.Path(file, os.pwd) / os.up
  val extra = collectFrom(module, base)
  if !complete then Nil
  else ContentToolkitLint.lint(module, extra)

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

final class CheckCmd extends CliCommand:
  def name = "check"
  override def summary = "Type-check .ssc (parse + typer; no codegen)"
  override def category = "Check & inspect"
  override def details = List("Flags: --json, --quiet, --watch, --iface-dir <dir>", "Exit codes: 0 clean, 1 type errors, 2 parse errors, 3 file not found")
  def run(args: List[String]): Unit =
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
    if ActiveFlags.current.yStats then CompileStats.enable()
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
    CompileStats.printAndReset()
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
final class LinkCmd extends CliCommand:
  def name = "link"
  override def summary = "Link .scim/.scir artifact pairs into a merged module"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
final class CheckWithIfaceCmd extends CliCommand:
  def name = "check-with-iface"
  override def summary = "Type-check consuming pre-compiled .scim interfaces"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
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
            // core-min-prelude-spi: resolve loaded-plugin symbols here too (names via intrinsic
            // keys + typed signatures via preludeSymbols) so `check-with-iface` is consistent with
            // `ssc check` and doesn't false-positive on plugin-backed names. Strictly reduces errors.
            val pluginBuiltins = BackendRegistry.inProcess
              .flatMap(_.intrinsics.keys).flatMap(qn => qn.value :: qn.value.split('.').headOption.toList).toSet
            // check-autoload-plugin-by-import: consistent with `ssc check` (above).
            val pluginPrelude  = BackendRegistry.inProcess.flatMap(_.preludeSymbols) ++
              BackendRegistry.availablePreludeSymbols(pluginAvailableDirs)
            val typed =
              if interfaces.isEmpty then
                Typer(strict = true, extraBuiltins = pluginBuiltins, preludeSymbols = pluginPrelude).typeCheck(module)
              else
                Typer(interfaces, strict = true, extraBuiltins = pluginBuiltins, preludeSymbols = pluginPrelude)
                  .typeCheck(module)
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

final class FmtCmd extends CliCommand:
  def name = "fmt"
  override def summary = "Format .ssc files in place"
  override def category = "Run & develop"
  override def details = List("Flags: --check (CI mode), --stdout (single file)")
  def run(args: List[String]): Unit =
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

/** `ssc bench <file.ssc> [options]` — benchmark a .ssc file using exactly
 *  one backend.  Warmup + timing are embedded inside a generated wrapper
 *  script so compilation and process-startup costs are excluded.
 *
 *  The backend is selected with the global `--backend <ssc|jvm|js|v2|v2-jvm|v2-rust>` flag
 *  (default: ssc), shared with other ssc commands.
 *
 *  Options:
 *  {{{
 *    --backend <ssc|jvm|js|v2|v2-jvm|v2-rust>
 *                                 (global flag) backend to use (default: ssc)
 *    --warmup N                  warmup iterations (default 5)
 *    --warmup-time N             warmup for N milliseconds (time-based; overrides --warmup)
 *    --reps N                    measured iterations (default 20)
 *    --smoke                     quick interp smoke over bench/corpus/hello-world.ssc
 *    --target-ms N               fail if result exceeds N ms/iter
 *    --require-target            exit non-zero when --target-ms is exceeded
 *    --baseline                  write result to bench/BASELINE_RUNTIME.md
 *    --machine                   print machine-readable BENCH line
 *  }}}
 */
final class BenchCmd extends CliCommand:
  def name = "bench"
  override def summary = "Benchmark .ssc execution time on one backend"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
    if args.isEmpty then
      System.err.println("Usage: ssc bench [--backend <ssc|jvm|js|v2|v2-bytecode|v2-jvm|v2-rust>] [--warmup N] [--warmup-time N] [--reps N] [--smoke] [--target-ms N] [--require-target] [--baseline] [--machine] <file.ssc>")
      System.exit(1)

    // --backend is a global flag (GlobalFlags), consumed before we see args.
    // Read it from ActiveFlags; default to "ssc".
    var backend    = ActiveFlags.current.backend.getOrElse("ssc")
    var warmup     = 5
    var reps       = 20
    var smoke      = false
    var writeBase  = false
    var machine    = false
    var targetMs: Option[Long] = None
    var requireTarget  = false
    var warmupExplicit   = false
    var repsExplicit     = false
    // Default: 2 s time-based warmup. --warmup N clears this (count-based).
    var warmupTimeMs: Option[Long] = Some(2000L)
    var fileArg: Option[String] = None

    def parseTarget(raw: String): Long = raw.toLongOption.getOrElse {
      System.err.println(s"bench: invalid --target-ms value: $raw")
      System.exit(1)
      0L
    }

    // "interp" accepted as a backward-compatible alias for "ssc".
    if backend == "interp" then backend = "ssc"
    val validBackends = Set("ssc", "jvm", "js", "v2", "v2-bytecode", "v2-jvm", "v2-rust")
    if !validBackends(backend) then
      System.err.println(s"bench: unknown backend '$backend', valid: ${validBackends.mkString(", ")}")
      System.exit(1)

    val arr = args.toArray
    var i   = 0
    while i < arr.length do
      arr(i) match
        case "--smoke"          => smoke = true; i += 1
        case "--baseline"       => writeBase = true; i += 1
        case "--require-target" => requireTarget = true; i += 1
        case "--machine"        => machine = true; i += 1
        case "--warmup" if i+1 < arr.length => warmup = arr(i+1).toIntOption.getOrElse(5); warmupExplicit = true; warmupTimeMs = None; i += 2
        case "--warmup-time" if i+1 < arr.length =>
          warmupTimeMs = arr(i+1).toLongOption.filter(_ > 0).orElse {
            System.err.println(s"bench: --warmup-time must be a positive integer (milliseconds)"); System.exit(1); None
          }; i += 2
        case "--reps"   if i+1 < arr.length => reps   = arr(i+1).toIntOption.getOrElse(20); repsExplicit = true; i += 2
        case "--target-ms" if i+1 < arr.length => targetMs = Some(parseTarget(arr(i+1))); i += 2
        case s if s.startsWith("--warmup=")      => warmup = s.stripPrefix("--warmup=").toIntOption.getOrElse(5); warmupExplicit = true; warmupTimeMs = None; i += 1
        case s if s.startsWith("--warmup-time=") =>
          warmupTimeMs = s.stripPrefix("--warmup-time=").toLongOption.filter(_ > 0).orElse {
            System.err.println(s"bench: --warmup-time must be a positive integer (milliseconds)"); System.exit(1); None
          }; i += 1
        case s if s.startsWith("--reps=")      => reps   = s.stripPrefix("--reps=").toIntOption.getOrElse(20); repsExplicit = true; i += 1
        case s if s.startsWith("--target-ms=") => targetMs = Some(parseTarget(s.stripPrefix("--target-ms="))); i += 1
        case f if !f.startsWith("-")  => fileArg = Some(f); i += 1
        case other => System.err.println(s"bench: unknown flag $other"); System.exit(1)

    if smoke then
      warmupTimeMs = None
      if !warmupExplicit then warmup = 0
      if !repsExplicit then reps = 1
      backend = "ssc"

    if warmupTimeMs.isEmpty && warmup < 0 || reps <= 0 then
      System.err.println("bench: --warmup must be >= 0 and --reps must be positive")
      System.exit(1)
    if requireTarget && targetMs.isEmpty then
      System.err.println("bench: --require-target requires --target-ms N")
      System.exit(1)

    def findRepoRoot(start: os.Path): Option[os.Path] =
      var cur = if os.isDir(start) then start else start / os.up
      var done = false
      while !done do
        if os.exists(cur / "build.sbt") && os.exists(cur / "bench" / "corpus") then return Some(cur)
        val parent = cur / os.up
        if parent == cur then done = true else cur = parent
      None

    val path = fileArg match
      case Some(file) => os.Path(file, os.pwd)
      case None if smoke =>
        findRepoRoot(os.pwd).map(_ / "bench" / "corpus" / "hello-world.ssc").getOrElse {
          System.err.println("bench: --smoke could not find bench/corpus/hello-world.ssc")
          System.exit(1)
          os.pwd / "bench" / "corpus" / "hello-world.ssc"
        }
      case None =>
        System.err.println("bench: no file specified")
        System.exit(1)
        os.pwd / "missing.ssc"
    if !os.exists(path) then
      System.err.println(s"bench: file not found: $path"); System.exit(1)

    // Find the ssc script: prefer ssc.lib.path (set by bin/ssc), then SSC env, then PATH.
    val sscCmd: Seq[String] =
      sys.props.get("ssc.lib.path").flatMap { libPath =>
        val script = java.nio.file.Paths.get(libPath).resolve("bin/ssc")
        if java.nio.file.Files.isExecutable(script) then Some(Seq(script.toString))
        else None
      }.orElse(sys.env.get("SSC").map(s => Seq(s)))
       .getOrElse(Seq("ssc"))

    // Extract ScalaScript code from a Markdown fence (```scalascript...```) or return raw.
    def extractCode(content: String): String =
      val marker = "```scalascript"
      val start  = content.indexOf(marker)
      if start < 0 then content
      else
        val codeStart = content.indexOf('\n', start) + 1
        val endFence  = content.indexOf("\n```", codeStart)
        if endFence < 0 then content.substring(codeStart)
        else content.substring(codeStart, endFence)

    // Detect workload()'s return type — we use a primitive-typed sink for
    // Int/Long/Double/Boolean workloads to avoid per-iter autoboxing into
    // `Any`.  Boxing was dominating measurements: 3-op `workload()` reading
    // ~1 µs/iter where the actual work is ~3 ns; the ~1 µs was the Integer
    // alloc & GC pressure on each `_ssc_sink = workload()`.
    //
    // Returns the (sinkType, sinkInit, sinkUpdate(workloadCall), sinkPrint)
    // tuple.  `_ssc_sink ^= workload().toLong` keeps the JIT honest (XOR
    // makes the result reachable so DCE can't drop the call) without the
    // heap allocation an `Any` sink would force.  Non-numeric workloads
    // fall back to the historical `Any` sink.
    def workloadReturnType(code: String): String =
      // Effect-typed workloads (`runLogger { ... }`, etc.) emit as `Any` in
      // JvmGen even when the declared return is `Int`/`Long` — fall back to
      // the boxed sink path.
      // Param list may be empty `()` or carry a bench seed `(seed: Long)` —
      // match any parenthesised arg list so seed-threaded workloads still get
      // their primitive (unboxed) sink instead of falling back to `Any`.
      val effectCall = """def\s+workload\s*\([^)]*\)\s*:\s*[A-Za-z]+\s*=\s*run[A-Z]""".r
      if effectCall.findFirstIn(code).isDefined then "Any"
      else
        val re = raw"def\s+workload\s*\([^)]*\)\s*:\s*([A-Za-z]+)".r
        re.findFirstMatchIn(code).map(_.group(1)).getOrElse("Any")

    // Wrap user code with a self-contained timing harness that prints BENCH_MS.
    // Uses markdown format so emit-scala/emit-js process it correctly.
    def generateWrapper(code: String, warmupN: Int, repsN: Int,
                        warmupTimeMs: Option[Long], targetBackend: String): String =
      // JVM pre-warm: BytecodeJIT reduces eval calls to ~10 per workload() iteration
      // for TCO workloads — far below JVM C2 threshold (~10 K invocations).
      // Three stages:
      //   fib-22 : exercises tree-walk eval/callFun (~140 K calls, 5 reps)
      //   tco-50 : exercises JitRuntime / MH-invoke path (10 K cache-hit calls)
      //   pwm    : mutual-tail-call shape → forces tcoTrampoline itself into C2
      //            (plain tco pre-warm bypasses tcoTrampoline via JIT short-circuit)
      // With --warmup-time the user-specified warmup loop runs until a wall-clock
      // deadline rather than a fixed iteration count; the pre-warm block above still
      // runs first so the JVM's own C2 is in play before the timed phase starts.
      //
      // Two-phase warmup for the time-based path:
      //
      // Phase 1 (time-based, monadic loop): `while nanoTime() < end do workload()`
      //   — warms workload itself, BytecodeJIT, callValue0Slow, and C2 via the
      //   monadic Term.While pure-fast-path (EvalRuntime line ~3307).
      //
      // Phase 2 (shape-correct, ~1/6 of total budget):
      //   outer = time-bounded (prevents expensive workloads from blowing the budget)
      //   inner = `while _ssc_iw < 50 do { workload(); _ssc_iw += 1 }`
      //   — the inner loop has EXACTLY the same shape as the timed loop, so it
      //   exercises the same `tryMixedLongWhile` code path and accumulates back-edges
      //   for JVM C2 method-level compilation of `tryMixedLongWhile` itself.  For
      //   cheap workloads (~3 µs) the 500 ms phase-2 budget yields ~3300 invocations
      //   × 50 back-edges = 165K total — enough for C2 method compilation.  For
      //   expensive workloads (~1 ms), phase 2 runs only ~8 invocations (irrelevant;
      //   workload cost dwarfs any JIT overhead).  Use --reps 500 for accurate floor
      //   values on any workload.
      // Pick a sink shape per return type.
      //
      // JMH-style anti-fold: an AtomicLong with `.lazySet(...)` per iter
      // prevents HotSpot from hoisting the loop out via scalar-evolution
      // (`while r<N do sink+=workload()` was being rewritten to
      // `sink += workload()*N` on pure workloads, giving ~8ps/iter — far
      // below 1 CPU cycle).  `lazySet` is a relaxed volatile write: HotSpot
      // must materialise each write, but doesn't need a memory barrier per
      // iter, so overhead is ~1-2 ns/iter (acceptable noise for benches).
      //
      // The fix lives in the JVM sink only.  Interp/JS don't have an AtomicLong
      // surface in our subset, AND they aren't sophisticated enough to fold
      // the outer loop, so they keep the plain primitive sink.
      val returnTy = workloadReturnType(code)
      // Seed-threading (bench-honest-corpus-seed): a workload declared as
      // `def workload(seed: Long): T` opts into runtime-varying input so the
      // AOT backends (C2 / TurboFan / LLVM) cannot constant-fold the otherwise
      // pure, zero-input body to a compile-time constant.  The seed source must
      // be OPAQUE to the optimiser:
      //   - JVM: read the AtomicLong sink (`_ssc_sink.get()`).  A volatile/atomic
      //     load is not constant-propagatable, so C2 can't precompute the body.
      //     We feed the RAW value (no `| 1L` normalisation): `(x | 1) & mask`
      //     would let C2 algebraically re-derive a constant and re-enable the
      //     fold — the whole defeat hinges on the workload's `seed & mask` being
      //     opaque.  A zero seed on iter 0 is a perfectly valid input.
      //   - Interp/JS: they don't apply outer-loop scalar evolution, so a cheap
      //     monotonic `_ssc_seed` (incremented per iter) is enough varying input.
      // A no-arg `def workload(): T` keeps the historical plain call unchanged.
      val hasSeed = raw"def\s+workload\s*\(\s*seed\b".r.findFirstIn(code).isDefined
      val (sinkDecl, sinkUpdate, sinkRead) =
        if targetBackend == "jvm" then
          // JVM anti-fold: AtomicLong.getAndAdd is `lock xaddq` on x86 / `ldadd`
          // on ARMv8.1+ — a single atomic that HotSpot must materialise on each
          // iteration (intermediate values are thread-observable).  This defeats
          // the outer-loop scalar-evolution fold (`while r<N do sink+=workload()`
          // → `sink+=workload()*N` on pure-constant workloads).  Honest floor:
          // ~1.8 ns/iter on M1, ~5-10 ns/iter on x86.
          //
          // Workload-INTERNAL fold (C2 inlining workload() and seeing its result
          // is a compile-time constant — e.g. streams-pipeline collapsing to a
          // literal) is defeated separately by the seed parameter: `wc` below
          // feeds `_ssc_sink.get()` (opaque atomic load) as the workload's seed,
          // so the body is no longer a zero-input pure function.  Seed-less
          // workloads keep the plain `workload()` call (they run a real loop).
          val wc = if hasSeed then "workload(_ssc_sink.get())" else "workload()"
          returnTy match
            case "Int" | "Long" | "Boolean" =>
              ("val _ssc_sink = new java.util.concurrent.atomic.AtomicLong(0L)",
               returnTy match
                 case "Int"     => s"_ssc_sink.getAndAdd($wc.toLong)"
                 case "Long"    => s"_ssc_sink.getAndAdd($wc)"
                 case "Boolean" => s"_ssc_sink.getAndAdd(if $wc then 1L else 0L)",
               "_ssc_sink.get()")
            case "Double" =>
              ("val _ssc_sink = new java.util.concurrent.atomic.AtomicLong(0L)",
               s"_ssc_sink.getAndAdd(java.lang.Double.doubleToRawLongBits($wc))",
               "java.lang.Double.longBitsToDouble(_ssc_sink.get())")
            case _ =>
              // Any branch — covers Unit (println workloads), tuples, case
              // classes etc.  hashCode().toLong gives a Long-shaped reduction;
              // a null guard handles both Unit and null returns.
              ("val _ssc_sink = new java.util.concurrent.atomic.AtomicLong(0L)",
               s"_ssc_sink.getAndAdd({ val __r: Any = $wc; if __r == null then 0L else __r.hashCode().toLong })",
               "_ssc_sink.get()")
        else
          // Interp/JS: no AtomicLong in our subset.  Use plain primitive sink;
          // the tree-walking interpreter and V8 don't apply scalar-evolution
          // outer-loop fold anyway, so this is honest already.  When the
          // workload takes a seed, declare a monotonic `_ssc_seed` and advance
          // it inside the (block-wrapped) sink update so the input varies.
          val seedDecl = if hasSeed then "var _ssc_seed: Long = 1L\n" else ""
          val wc       = if hasSeed then "workload(_ssc_seed)" else "workload()"
          def upd(core: String) = if hasSeed then s"{ _ssc_seed = _ssc_seed + 1; $core }" else core
          returnTy match
            case "Int"     => (seedDecl + "var _ssc_sink: Long = 0L",   upd(s"_ssc_sink = _ssc_sink + $wc.toLong"), "_ssc_sink")
            case "Long"    => (seedDecl + "var _ssc_sink: Long = 0L",   upd(s"_ssc_sink = _ssc_sink + $wc"),        "_ssc_sink")
            case "Double"  => (seedDecl + "var _ssc_sink: Double = 0d", upd(s"_ssc_sink = _ssc_sink + $wc"),        "_ssc_sink")
            case "Boolean" => (seedDecl + "var _ssc_sink: Long = 0L",   upd(s"_ssc_sink = _ssc_sink + (if $wc then 1L else 0L)"), "_ssc_sink")
            case _         => (seedDecl + "var _ssc_sink: Any = null",  upd(s"_ssc_sink = $wc"),                     "_ssc_sink")
      val warmupBlock = warmupTimeMs match
        case Some(ms) =>
          val ns       = ms * 1000000L
          val phase2Ns = (ms / 6) * 1000000L
          s"""val _ssc_wt_end = System.nanoTime() + ${ns}L
             |while System.nanoTime() < _ssc_wt_end do
             |  $sinkUpdate
             |val _ssc_sw_end = System.nanoTime() + ${phase2Ns}L
             |while System.nanoTime() < _ssc_sw_end do
             |  var _ssc_iw = 0
             |  while _ssc_iw < 50 do
             |    $sinkUpdate
             |    _ssc_iw += 1""".stripMargin
        case None =>
          s"var _ssc_w = 0\nwhile _ssc_w < $warmupN do\n  $sinkUpdate\n  _ssc_w += 1"
      s"""# bench-wrapper
         |
         |```scalascript
         |$code
         |
         |def _ssc_pfib(n: Int): Int = if n <= 1 then n else _ssc_pfib(n - 1) + _ssc_pfib(n - 2)
         |def _ssc_ptco(n: Int, a: Int): Int = if n <= 0 then a else _ssc_ptco(n - 1, a + n)
         |def _ssc_pwm(n: Int): Int = _ssc_pwm_i(n, 0)
         |def _ssc_pwm_i(n: Int, a: Int): Int = if n <= 0 then a else _ssc_pwm_i(n - 1, a + n)
         |var _ssc_pi = 0
         |while _ssc_pi < 5 do
         |  _ssc_pfib(22)
         |  _ssc_pi += 1
         |var _ssc_pj = 0
         |while _ssc_pj < 10000 do
         |  _ssc_ptco(50, 0)
         |  _ssc_pj += 1
         |var _ssc_pk = 0
         |while _ssc_pk < 30000 do
         |  _ssc_pwm(5)
         |  _ssc_pk += 1
         |$sinkDecl
         |$warmupBlock
         |// Adaptive timed loop: double reps until the measured window is
         |// at least 100ms.  This guarantees enough samples for nanoTime
         |// resolution (~25-100ns granularity) and lets HotSpot reach its
         |// steady-state JIT level even for very-fast workloads (e.g.
         |// 3-add-op typeclass-monoid that compiles to ~1ns/iter).  Capped
         |// at 2^28 reps (268M) to avoid runaway on infinitely-fast paths.
         |var _ssc_reps = $repsN
         |var _ssc_ns: Long = 0L
         |while _ssc_ns < 100_000_000L && _ssc_reps <= 268_435_456 do
         |  val _ssc_t0 = System.nanoTime()
         |  var _ssc_r = 0
         |  while _ssc_r < _ssc_reps do
         |    $sinkUpdate
         |    _ssc_r += 1
         |  _ssc_ns = System.nanoTime() - _ssc_t0
         |  if _ssc_ns < 100_000_000L then _ssc_reps = _ssc_reps * 2
         |println(s"BENCH_MS: $${_ssc_ns.toDouble / (_ssc_reps * 1000000.0)}")
         |println(s"BENCH_SINK: $${$sinkRead}")
         |```
         |""".stripMargin

    def parseBenchMs(output: String): Option[Double] =
      output.linesIterator
        .filter(_.startsWith("BENCH_MS:"))
        .flatMap(l => l.stripPrefix("BENCH_MS:").trim.toDoubleOption)
        .toSeq.lastOption

    def fmtMs(ms: Double): String =
      // Sub-microsecond results need 6+ decimals to compare across backends;
      // a 3-decimal format buried JVM's ~1ns-per-iter inside "0.001".
      if      ms < 1.0e-3 then f"$ms%.6f"
      else if ms < 1.0    then f"$ms%.4f"
      else if ms < 10.0   then f"$ms%.2f"
      else                     f"$ms%.1f"

    val name     = path.last.stripSuffix(".ssc")
    val userCode = extractCode(os.read(path))
    // Per-backend wrapper — JVM gets the JMH-style AtomicLong sink (so HotSpot
    // can't fold the outer timing loop via scalar evolution); interp/JS get
    // the plain primitive sink (they don't fold the outer loop anyway).
    //
    // NOTE on workload-internal fold: corpus workloads with no input parameters
    // (`def workload(): T = ...`) are subject to compile-time precomputation
    // by C2 / TurboFan / LLVM — the workload's result is a compile-time
    // constant.  Wrapping with `Bench.opaque(...)` at the .ssc level breaks
    // interp's unboxed-Long fast-path (the local becomes a boxed `Value`),
    // so it must be done by source signature change (`workload(seed: Long): T`
    // with a runtime-varying seed) — tracked as `bench-honest-workload-seed`.
    val wrapperTargetBackend = backend match
      case "v2-jvm" | "v2-rust" => "v2"
      case other    => other
    val wrapper      = generateWrapper(userCode, warmup, reps, warmupTimeMs, wrapperTargetBackend)

    // Native ssc1 front for the v2 bench lanes (the retired FrontendBridge tier's
    // replacement): write the wrapper to a temp .ssc beside the source so its
    // relative imports resolve, then compile through RunNativeV2.
    def benchNativeCompile(): NativeV2Compilation =
      val tmp = os.temp(wrapper, dir = path / os.up, prefix = "ssc-bench-", suffix = ".ssc")
      try RunNativeV2.compile(List(tmp.toString)) finally os.remove(tmp)

    // Run interpreter in-process: parse wrapper, capture stdout, parse BENCH_MS.
    def timeInterp(): Option[Double] =
      val module = scalascript.parser.Parser.parse(wrapper)
      val outBuf = new java.io.ByteArrayOutputStream()
      val outPs  = new java.io.PrintStream(outBuf, true, "UTF-8")
      try scalascript.interpreter.Interpreter(out = outPs, baseDir = Some(path / os.up), headless = true).run(module)
      catch case _: Throwable => ()
      parseBenchMs(outBuf.toString("UTF-8"))

    // v2 engine lane: same wrapper, run through FrontendBridge + the v2 VM
    // (the post-switch `ssc run` default). Stdout captured like timeInterp —
    // the wrapper prints its own per-iteration ms.
    def timeV2(): Option[Double] =
      val outBuf = new java.io.ByteArrayOutputStream()
      val outPs  = new java.io.PrintStream(outBuf, true, "UTF-8")
      // NB: Console.withOut ONLY — a System.setOut swap here poisons
      // scala.Console's lazily-captured default stream (its DynamicVariable
      // snapshots System.out at class-init; if that happens inside the swap,
      // every later println in this process goes to the dead buffer).
      try
        val compiled = benchNativeCompile()
        _root_.ssc.plugin.NativePluginHost.loadAll(compiled.config)
        Console.withOut(outPs) {
          _root_.ssc.Runtime.runManaged(
            _root_.ssc.Compiler.compile(compiled.program),
            Array.empty[_root_.ssc.Value],
          )
        }
      catch case e: Throwable =>
        if System.getenv("SSC_BENCH_DEBUG") != null then
          System.err.println(s"[timeV2] ${e.getClass.getSimpleName}: ${e.getMessage}")
          e.getStackTrace.take(6).foreach(f => System.err.println(s"[timeV2]   at $f"))
      parseBenchMs(outBuf.toString("UTF-8"))

    def v2CoreIr(): Option[String] =
      try
        Some(_root_.ssc.Writer.program(benchNativeCompile().program))
      catch case e: Throwable =>
        if System.getenv("SSC_BENCH_DEBUG") != null then
          System.err.println(s"[v2CoreIr] ${e.getClass.getSimpleName}: ${e.getMessage}")
          e.getStackTrace.take(6).foreach(f => System.err.println(s"[v2CoreIr]   at $f"))
        None

    def timeV2Bytecode(): Option[Double] =
      val outBuf = new java.io.ByteArrayOutputStream()
      val outPs  = new java.io.PrintStream(outBuf, true, "UTF-8")
      try
        val compiled = benchNativeCompile()
        _root_.ssc.Runtime.argv = Nil
        _root_.ssc.plugin.NativePluginHost.loadAll(compiled.config)
        Console.withOut(outPs) {
          val prog = compiled.program
          _root_.ssc.Emit.globalsRef = collection.mutable.HashMap.empty
          val bytes = _root_.ssc.bytecode.JvmByteGen.emitProgram(_root_.ssc.bytecode.OpAnfNative.lift(prog))
          val res =
            try _root_.ssc.bytecode.JvmByteGen.runProgram(bytes)
            catch case e: java.lang.reflect.InvocationTargetException =>
              throw Option(e.getCause).getOrElse(e)
          res match
            case _root_.ssc.Value.UnitV => ()
            case other                  => println(_root_.ssc.Show.show(other))
        }
      catch case e: Throwable =>
        if System.getenv("SSC_BENCH_DEBUG") != null then
          System.err.println(s"[timeV2Bytecode] ${e.getClass.getSimpleName}: ${e.getMessage}")
          e.getStackTrace.take(6).foreach(f => System.err.println(s"[timeV2Bytecode]   at $f"))
      parseBenchMs(outBuf.toString("UTF-8"))

    def runV2SourceGenerator(backendDir: os.Path, ir: String): Option[String] =
      try
        val tmpIr = os.temp(ir, suffix = ".coreir", deleteOnExit = true)
        val generated = os.proc("scala-cli", "run", backendDir.toString, "-q", "--server=false", "--", tmpIr.toString)
          .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        if generated.exitCode != 0 then
          if System.getenv("SSC_BENCH_DEBUG") != null then
            System.err.println(generated.err.text())
          None
        else Some(generated.out.text())
      catch case e: Throwable =>
        if System.getenv("SSC_BENCH_DEBUG") != null then
          System.err.println(s"[runV2SourceGenerator] ${e.getClass.getSimpleName}: ${e.getMessage}")
        None

    def timeV2Jvm(): Option[Double] =
      if !JvmBytecode.scalaCliAvailable then None
      else
        findRepoRoot(path).flatMap { root =>
          val src = for
            ir <- v2CoreIr()
            generated <- runV2SourceGenerator(root / "v2" / "backend" / "jvm", ir)
          yield generated
          src.flatMap { generated =>
            try
              val tmpSc = os.temp(generated, suffix = ".scala", deleteOnExit = true)
              val run = os.proc("scala-cli",
                                "--java-opt", "-XX:CompileThreshold=100",
                                "--java-opt", "-XX:-BackgroundCompilation",
                                "--server=false",
                                tmpSc.toString)
                .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
              if run.exitCode != 0 then
                if System.getenv("SSC_BENCH_DEBUG") != null then
                  System.err.println(run.err.text())
                None
              else parseBenchMs(run.out.text())
            catch case e: Throwable =>
              if System.getenv("SSC_BENCH_DEBUG") != null then
                System.err.println(s"[timeV2Jvm] ${e.getClass.getSimpleName}: ${e.getMessage}")
              None
          }
        }

    def rustcAvailable: Boolean =
      scala.util.Try(os.proc("rustc", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
        .getOrElse(false)

    def patchV2RustBenchAntiFold(src: String): String =
      val longFnRe        = """\bfn\s+([A-Za-z_]\w*_long)\s*\(([^)]*)\)\s*->\s*i64\s*\{""".r
      val firstIntLitRe   = """\b\d+(?:i(?:8|16|32|64))?\b""".r
      val simpleAddUpdate = """\([A-Za-z_]\w*\)\.wrapping_add\([A-Za-z_]\w*\)""".r
      val fns = longFnRe.findAllMatchIn(src).map(m => (m.start, m.group(1), m.group(2).trim)).toList
      if fns.isEmpty then src
      else
        def bodyOf(fnStart: Int): Option[(Int, Int)] =
          val ob = src.indexOf('{', fnStart)
          if ob < 0 then None
          else
            var depth = 1
            var k = ob + 1
            while k < src.length && depth > 0 do
              src.charAt(k) match
                case '{' => depth += 1
                case '}' => depth -= 1
                case _   => ()
              k += 1
            if depth == 0 then Some((ob + 1, k - 1)) else None

        def patchFirstLiteral(body: String): String =
          if body.contains("std::hint::black_box(") then body
          else
            firstIntLitRe.findFirstMatchIn(body) match
              case Some(m) =>
                body.substring(0, m.start) +
                  "std::hint::black_box(" + m.matched + ")" +
                  body.substring(m.end)
              case None => body

        def patchSingleSelfCallUpdate(name: String, body: String): String =
          if body.contains("std::hint::black_box(") then body
          else
            val selfCallRe = ("\\b" + java.util.regex.Pattern.quote(name) + "\\s*\\(").r
            val selfCalls = selfCallRe.findAllMatchIn(body).length
            if selfCalls != 1 then body
            else
              simpleAddUpdate.findFirstMatchIn(body) match
                case Some(m) =>
                  body.substring(0, m.start) +
                    "std::hint::black_box(" + m.matched + ")" +
                    body.substring(m.end)
                case None => body

        var out = src
        for (fnStart, name, params) <- fns.sortBy { case (start, _, _) => -start } do
          bodyOf(fnStart) match
            case Some((bs, be)) =>
              val body = out.substring(bs, be)
              val patched =
                if params.isEmpty then patchFirstLiteral(body)
                else patchSingleSelfCallUpdate(name, body)
              out = out.substring(0, bs) + patched + out.substring(be)
            case None => ()
        out

    def timeV2Rust(): Option[Double] =
      if !rustcAvailable || !JvmBytecode.scalaCliAvailable then None
      else
        findRepoRoot(path).flatMap { root =>
          val src = for
            ir <- v2CoreIr()
            generated <- runV2SourceGenerator(root / "v2" / "backend" / "rust", ir)
          yield generated
          src.flatMap { generated =>
            try
              val tmpDir = os.temp.dir(prefix = "ssc-bench-v2-rust-", deleteOnExit = true)
              val rs     = tmpDir / "main.rs"
              val bin    = tmpDir / "bench-bin"
              os.write.over(rs, patchV2RustBenchAntiFold(generated))
              val build = os.proc("rustc", "-O", rs.toString, "-o", bin.toString)
                .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
              if build.exitCode != 0 then
                if System.getenv("SSC_BENCH_DEBUG") != null then
                  System.err.println(build.err.text())
                None
              else
                val run = os.proc(bin.toString)
                  .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
                if run.exitCode != 0 then
                  if System.getenv("SSC_BENCH_DEBUG") != null then
                    System.err.println(run.err.text())
                  None
                else parseBenchMs(run.out.text())
            catch case e: Throwable =>
              if System.getenv("SSC_BENCH_DEBUG") != null then
                System.err.println(s"[timeV2Rust] ${e.getClass.getSimpleName}: ${e.getMessage}")
              None
          }
        }

    // Fuse `(lo to hi).map(f).filter(g).foldLeft(z)(h)` → manual while loop.
    // HotSpot can JIT the chained-Vector form well, but it still allocates
    // an IndexedSeq per `.map`/`.filter` step (~46 ns/iter vs ~3 ns for the
    // fused loop).  Detect the chain in the emitted Scala source and rewrite
    // it into a `locally { ... }` block.  Semantics-preserving.
    // Captures allow one level of nested parens (for `(sum, x) =>` style
    // lambdas inside the chain args).
    val streamFuseRe =
      ("""\((\d+)\s+to\s+(\d+)\)\.map\(((?:[^()]|\([^()]*\))+?)\)\.filter\(((?:[^()]|\([^()]*\))+?)\)""" +
       """\.foldLeft\(((?:[^()]|\([^()]*\))+?)\)\(((?:[^()]|\([^()]*\))+?)\)""").r
    // Lambda body extractor for `<param> => <body>` and `(<p1>, <p2>) => <body>`.
    // Returns (paramNames, body); falls back to None if shape is unfamiliar.
    val lam1Re = """^\s*([A-Za-z_]\w*)\s*=>\s*(.+)$""".r
    val lam2Re = """^\s*\(\s*([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*\)\s*=>\s*(.+)$""".r
    def fuseStreamChain(src: String): String =
      streamFuseRe.replaceAllIn(src, m =>
        val lo = m.group(1); val hi = m.group(2)
        val f  = m.group(3); val g  = m.group(4)
        val z  = m.group(5); val h  = m.group(6)
        val rendered: Option[String] = (f, g, h) match
          case (lam1Re(fp, fb), lam1Re(gp, gb), lam2Re(ha, hx, hb)) =>
            // Substitute __m for fp / gp; __acc, __m for ha / hx.
            // Wrap with parens for safety inside larger expressions.
            val mapBody    = fb.replaceAll(s"\\b$fp\\b", "__m_pre")
            val filterBody = gb.replaceAll(s"\\b$gp\\b", "__m")
            val foldBody   = hb.replaceAll(s"\\b$ha\\b", "__acc").replaceAll(s"\\b$hx\\b", "__m")
            Some(s"""locally { var __acc: Long = ($z).toLong; var __i = $lo; while __i <= $hi do { val __m_pre = __i; val __m = ($mapBody); if ($filterBody) then __acc = ($foldBody).toLong; __i = __i + 1 }; __acc.toInt }""")
          case _ => None
        rendered.map(scala.util.matching.Regex.quoteReplacement).getOrElse(m.matched)
      )

    // emit-scala → stable /tmp/*.sc → scala-cli (compilation excluded from timing).
    def timeJvm(): Option[Double] =
      val tmpSsc = os.Path(s"/tmp/ssc-bench-jvm-$name.ssc")
      val tmpSc  = os.Path(s"/tmp/ssc-bench-jvm-$name.sc")
      os.write.over(tmpSsc, wrapper)
      val emit = os.proc(sscCmd ++ Seq("emit-scala", tmpSsc.toString))
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      if emit.exitCode != 0 then return None
      val emittedSrc = new String(emit.out.bytes, "UTF-8")
      val fusedSrc   = fuseStreamChain(emittedSrc)
      os.write.over(tmpSc, fusedSrc)
      val run = os.proc("scala-cli",
                        "--java-opt", "-XX:CompileThreshold=100",
                        "--java-opt", "-XX:-BackgroundCompilation",
                        "--server=false",
                        tmpSc.toString)
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      if run.exitCode != 0 then None
      else parseBenchMs(run.out.text())

    // emit-js → stable /tmp/*.cjs → node (compilation excluded from timing).
    def timeJs(): Option[Double] =
      val tmpSsc = os.Path(s"/tmp/ssc-bench-js-$name.ssc")
      val tmpCjs = os.Path(s"/tmp/ssc-bench-js-$name.cjs")
      os.write.over(tmpSsc, wrapper)
      val emit = os.proc(sscCmd ++ Seq("emit-js", tmpSsc.toString))
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      if emit.exitCode != 0 then return None
      os.write.over(tmpCjs, emit.out.bytes)
      val run = os.proc("node", tmpCjs.toString)
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      if run.exitCode != 0 then None
      else parseBenchMs(run.out.text())

    // Check tool availability for the selected backend.
    backend match
      case "jvm" if !JvmBytecode.scalaCliAvailable =>
        System.err.println("bench: scala-cli not found — cannot run jvm backend"); System.exit(1)
      case "v2-jvm" if !JvmBytecode.scalaCliAvailable =>
        System.err.println("bench: scala-cli not found — cannot run v2-jvm backend"); System.exit(1)
      case "js" if !scala.util.Try {
        os.proc("node", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
      }.getOrElse(false) =>
        System.err.println("bench: node not found — cannot run js backend"); System.exit(1)
      case _ => ()

    val warmupDesc = warmupTimeMs match
      case Some(ms) => s"${ms}ms (time-based)"
      case None     => s"$warmup iters"
    if !machine then
      println(s"\nssc bench — $name  [backend: $backend]")
      println("=" * 60)
      println(s"Warmup: $warmupDesc  Reps: $reps")
      println()

    val result: Option[Double] = backend match
      case "jvm"    => timeJvm()
      case "js"     => timeJs()
      case "v2"     => timeV2()
      case "v2-bytecode" => timeV2Bytecode()
      case "v2-jvm" => timeV2Jvm()
      case "v2-rust"=> timeV2Rust()
      case _        => timeInterp()

    if machine then
      result.foreach(ms => println(s"BENCH $backend ${fmtMs(ms)}"))
    else
      val msStr = result.fold("n/a")(fmtMs)
      println(s"$backend: $msStr ms/iter")
      val hw = s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}, JVM ${System.getProperty("java.version")}"
      println(s"\nDate: ${java.time.Instant.now()}")
      println(s"Hardware: $hw")
      println(s"Notes: warmup=$warmupDesc reps=$reps; warmup+timing inside wrapper, compilation excluded")

      targetMs.foreach { limit =>
        result.filter(_ > limit.toDouble) match
          case None => println(s"Target: <= ${limit}ms/iter (met)")
          case Some(ms) =>
            val msg = s"Target: <= ${limit}ms/iter (exceeded: $backend=${fmtMs(ms)}ms)"
            if requireTarget then System.err.println(msg) else println(msg)
            if requireTarget then System.exit(1)
      }

      if writeBase then
        val baseDir = findRepoRoot(path).map(_ / "bench").getOrElse(path / os.up / os.up / "bench")
        val outPath = baseDir / "BASELINE_RUNTIME.md"
        if os.exists(baseDir) then
          val content = s"# Runtime Benchmark Baseline\n\nFile: `$name.ssc`  Backend: $backend  \nDate: ${java.time.Instant.now()}  Warmup: $warmupDesc  Reps: $reps\n\n$backend: $msStr ms/iter\n\nHardware: $hw\n"
          os.write.over(outPath, content)
          println(s"\nBaseline written to ${outPath}")

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
final class ProfileCmd extends CliCommand:
  def name = "profile"
  override def summary = "Run with call-level profiling; print top-N hotspots"
  override def category = "Run & develop"
  override def details = List("Flags: --top N (default 20), --output <profile.json>")
  def run(args: List[String]): Unit =
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
