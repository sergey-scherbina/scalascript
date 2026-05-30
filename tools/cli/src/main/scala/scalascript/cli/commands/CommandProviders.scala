package scalascript.cli.commands

import scalascript.cli.*
import scalascript.compiler.plugin.BackendRegistry

// Generated CliCommand providers (see docs/cli-command-spi.md). They carry the
// help metadata (summary / category / details) and delegate execution to the
// command functions in the scalascript.cli package.

final class RunCmd extends CliCommand:
  def name = "run"
  override def summary = "Execute .ssc via the tree-walking interpreter (the default runner)"
  override def category = "Run & develop"
  override def details = List("Flags: --frontend <custom|react|solid|vue|electron|swing|javafx|swiftui>", "       --mode <server|client> / --transport <http|in-process>", "       --host <addr> / --port <n> / --open-browser | --no-open-browser")
  def run(args: List[String]): Unit = runCommand(args)

final class WatchCmd extends CliCommand:
  def name = "watch"
  override def summary = "Run .ssc and re-run on every file change"
  override def category = "Run & develop"
  override def details = List("Flags: --frontend <custom|react|solid|vue|swing>")
  def run(args: List[String]): Unit = watchCommand(args)

final class WatchBenchCmd extends CliCommand:
  def name = "watch-bench"
  override def summary = "Benchmark one watch reload cycle on a temp copy"
  override def category = "Run & develop"
  override def details = List("Flags: --cycles <n>, --target-ms <n>, --require-target")
  def run(args: List[String]): Unit = watchBenchCommand(args)

final class BenchCmd extends CliCommand:
  def name = "bench"
  override def summary = "Benchmark .ssc execution time"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = benchCommand(args)

final class ReplCmd extends CliCommand:
  def name = "repl"
  override def summary = "Start an interactive REPL (blank line runs, :quit exits)"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = replCommand(args)

final class ServeCmd extends CliCommand:
  def name = "serve"
  override def summary = "Start an HTTP server serving .ssc files as web pages"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = serveCommand(args)

final class RenderCmd extends CliCommand:
  def name = "render"
  override def summary = "Render a single .ssc to static HTML via its registered handler"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = renderCommand(args)

final class PreviewCmd extends CliCommand:
  def name = "preview"
  override def summary = "Open a browser preview of each front-matter component variant"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = previewCommand(args)

final class TestCmd extends CliCommand:
  def name = "test"
  override def summary = "Run component unit tests; non-zero exit on any failure"
  override def category = "Run & develop"
  def run(args: List[String]): Unit = testCommand(args)

final class FmtCmd extends CliCommand:
  def name = "fmt"
  override def summary = "Format .ssc files in place"
  override def category = "Run & develop"
  override def details = List("Flags: --check (CI mode), --stdout (single file)")
  def run(args: List[String]): Unit = fmtCommand(args)

final class ProfileCmd extends CliCommand:
  def name = "profile"
  override def summary = "Run with call-level profiling; print top-N hotspots"
  override def category = "Run & develop"
  override def details = List("Flags: --top N (default 20), --output <profile.json>")
  def run(args: List[String]): Unit = profileCommand(args)

final class BuildCmd extends CliCommand:
  def name = "build"
  override def summary = "Batch-render a directory's .ssc to <out-dir> (or --incremental artifacts)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit = buildCommand(args)

final class BundleCmd extends CliCommand:
  def name = "bundle"
  override def summary = "Pack .ssc files + their .ssc imports into a .sscpkg archive"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit = bundleCommand(args)

final class PackageCmd extends CliCommand:
  def name = "package"
  override def summary = "Package .ssc via scala-cli (see Package flags below)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit = packageCommand(args)

final class DeployCmd extends CliCommand:
  def name = "deploy"
  override def summary = "Deploy to hostings, clouds & Kubernetes-like environments"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit = deployCommand(args)

final class NewCmd extends CliCommand:
  def name = "new"
  override def summary = "Scaffold a new project (e.g. --template plugin)"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit = newCommand(args)

final class EmitScalaCmd extends CliCommand:
  def name = "emit-scala"
  override def summary = "Print generated Scala 3 script to stdout"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit = emitScalaCommand(args)

final class EmitJsCmd extends CliCommand:
  def name = "emit-js"
  override def summary = "Transpile .ssc to JavaScript (Node) and print to stdout"
  override def category = "Emit & transpile"
  override def details = List("Flags: --no-tree-shake, --stats")
  def run(args: List[String]): Unit = emitJsCommand(args)

final class EmitWasmCmd extends CliCommand:
  def name = "emit-wasm"
  override def summary = "Compile scala/scalascript blocks to WebAssembly via Scala.js"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit = emitWasmCommand(args)

final class EmitSpaCmd extends CliCommand:
  def name = "emit-spa"
  override def summary = "Wrap .ssc as a browser SPA (HTML + embedded JS)"
  override def category = "Emit & transpile"
  override def details = List("Flags: --frontend <custom|react|solid|vue>")
  def run(args: List[String]): Unit = emitSpaCommand(args)

final class EmitWcCmd extends CliCommand:
  def name = "emit-wc"
  override def summary = "Emit each component as a W3C Custom Element bundle"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit = emitWcCommand(args)

final class EmitOpenapiCmd extends CliCommand:
  def name = "emit-openapi"
  override def summary = "Export OpenAPI 3.1 JSON/YAML without starting a server"
  override def category = "Emit & transpile"
  override def details = List("Flags: --format <json|yaml>, -o <file>, --title <s>, --version <v>, --server <url>")
  def run(args: List[String]): Unit = emitOpenapiCommand(args)

final class EmitSparkCmd extends CliCommand:
  def name = "emit-spark"
  override def summary = "Print generated Scala 3 + Spark program to stdout"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit = emitSparkCommand(args)

final class SubmitCmd extends CliCommand:
  def name = "submit"
  override def summary = "Package .ssc as a Spark fat JAR and launch via spark-submit"
  override def category = "Emit & transpile"
  override def details = List("Flags: --spark-master <url>, --spark-version <v>, --dry-run")
  def run(args: List[String]): Unit = submitCommand(args)

final class EmitInterfaceCmd extends CliCommand:
  def name = "emit-interface"
  override def summary = "Extract module interface to a .scim artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = emitInterfaceCommand(args)

final class EmitIrCmd extends CliCommand:
  def name = "emit-ir"
  override def summary = "Emit normalised module IR to a .scir artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = emitIrCommand(args)

final class RunJvmCmd extends CliCommand:
  def name = "run-jvm"
  override def summary = "Compile via JvmGen and run immediately via scala-cli"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = runJvmCommand(args)

final class RunJsCmd extends CliCommand:
  def name = "run-js"
  override def summary = "Compile via JsGen and run immediately via node"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = runJsCommand(args)

final class CompileJvmCmd extends CliCommand:
  def name = "compile-jvm"
  override def summary = "Emit JVM-backend cached Scala source to .scjvm"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = compileJvmCommand(args)

final class CompileJsCmd extends CliCommand:
  def name = "compile-js"
  override def summary = "Emit JS-backend cached JS source to .scjs"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = compileJsCommand(args)

final class CompileRuntimeCmd extends CliCommand:
  def name = "compile-runtime"
  override def summary = "Emit the cached backend runtime artifact"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = compileRuntimeCommand(args)

final class CheckWithIfaceCmd extends CliCommand:
  def name = "check-with-iface"
  override def summary = "Type-check consuming pre-compiled .scim interfaces"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = checkWithInterfaceCommand(args)

final class LinkCmd extends CliCommand:
  def name = "link"
  override def summary = "Link .scim/.scir artifact pairs into a merged module"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = linkCommand(args)

final class GenerateFacadeCmd extends CliCommand:
  def name = "generate-facade"
  override def summary = "Emit Scala 3 facade sources from .scim artifacts"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = generateFacadeCommand(args)

final class InfoCmd extends CliCommand:
  def name = "info"
  override def summary = "Inspect a .scim/.scir/.scjvm/.scjs artifact"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --json (dump the full envelope)")
  def run(args: List[String]): Unit = infoCommand(args)

final class CleanCmd extends CliCommand:
  def name = "clean"
  override def summary = "Remove stale v2.0 artifacts"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --dry-run, --all")
  def run(args: List[String]): Unit = cleanCommand(args)

final class VerifyCmd extends CliCommand:
  def name = "verify"
  override def summary = "Health-check v2.0 artifacts in a directory"
  override def category = "Separate compilation (v2.0)"
  override def details = List("Flags: --strict, --src-dir <dir>, --json")
  def run(args: List[String]): Unit = verifyCommand(args)

final class CheckCompatCmd extends CliCommand:
  def name = "check-compat"
  override def summary = "Compare public .scim interfaces; fail on breaking changes"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit = checkCompatCommand(args)

final class ParseCmd extends CliCommand:
  def name = "parse"
  override def summary = "Parse .ssc files and print the AST"
  override def category = "Check & inspect"
  def run(args: List[String]): Unit = parseCommand(args)

final class CheckCmd extends CliCommand:
  def name = "check"
  override def summary = "Type-check .ssc (parse + typer; no codegen)"
  override def category = "Check & inspect"
  override def details = List("Flags: --json, --quiet, --watch, --iface-dir <dir>", "Exit codes: 0 clean, 1 type errors, 2 parse errors, 3 file not found")
  def run(args: List[String]): Unit = checkCommand(args)

final class DepsCmd extends CliCommand:
  def name = "deps"
  override def summary = "Print the resolved import/dependency graph"
  override def category = "Check & inspect"
  def run(args: List[String]): Unit = depsCommand(args)

final class LockCmd extends CliCommand:
  def name = "lock"
  override def summary = "Pin URL/dep imports in ssc.lock (SHA-256 integrity)"
  override def category = "Dependencies & plugins"
  override def details = List("ssc lock check <file> verifies imports against the lock")
  def run(args: List[String]): Unit = lockCommand(args)

final class UpdateCmd extends CliCommand:
  def name = "update"
  override def summary = "Re-resolve dep: imports transitively; write ssc-lock.yaml"
  override def category = "Dependencies & plugins"
  override def details = List("Flags: --strict-deps")
  def run(args: List[String]): Unit = updateCommand(args)

final class SearchCmd extends CliCommand:
  def name = "search"
  override def summary = "Search the plugin registry by id or description"
  override def category = "Dependencies & plugins"
  def run(args: List[String]): Unit = registrySearchCommand(args)

final class AddCmd extends CliCommand:
  def name = "add"
  override def summary = "Add or update a plugin registry entry"
  override def category = "Dependencies & plugins"
  def run(args: List[String]): Unit = registryAddCommand(args)

final class PluginCmd extends CliCommand:
  def name = "plugin"
  override def summary = "Manage installed .sscpkg plugins"
  override def category = "Dependencies & plugins"
  override def details = List("Subs: install | list | uninstall | check | pack | registry")
  def run(args: List[String]): Unit = pluginCommand(args)

final class ClusterCmd extends CliCommand:
  def name = "cluster"
  override def summary = "Inspect or operate a running ssc cluster node"
  override def category = "Services & tooling"
  override def details = List("Subs: status | events | drain | step-down | run | package | handlers | stop")
  def run(args: List[String]): Unit = clusterCommand(args)

final class LspCmd extends CliCommand:
  def name = "lsp"
  override def summary = "Run the Language Server Protocol server over stdio"
  override def category = "Services & tooling"
  def run(args: List[String]): Unit = lspCommand(args)

final class PublishCmd extends CliCommand:
  def name = "publish"
  override def summary = "Publish artifacts (TestFlight/App Store per --target)"
  override def category = "Dependencies & plugins"
  def run(args: List[String]): Unit = publishCommand(args)

final class DebugCmd extends CliCommand:
  def name = "debug"
  override def summary = "Run the Debug Adapter Protocol server"
  override def category = "Services & tooling"
  def run(args: List[String]): Unit = DebugCommand.run(args)

final class OauthCmd extends CliCommand:
  def name = "oauth"
  override def summary = "OAuth 2.1 / OIDC helper commands"
  override def category = "Services & tooling"
  def run(args: List[String]): Unit = OAuthCli.run(args)

final class ToolchainCmd extends CliCommand:
  def name = "toolchain"
  override def summary = "Manage native/desktop/mobile build toolchains"
  override def category = "Services & tooling"
  override def details = List("Subs: check | install | list   Targets: web, desktop, ios, macos, …")
  def run(args: List[String]): Unit = ToolchainCommand.run(args)

final class HelpCmd extends CliCommand:
  def name = "help"
  override def aliases = List("--help", "-h")
  override def summary = "Show this help message"
  override def category = "Help"
  def run(args: List[String]): Unit = printUsage()

final class ListBackendsCmd extends CliCommand:
  def name = "--list-backends"
  override def hidden = true
  def run(args: List[String]): Unit = println(BackendRegistry.describe)

final class InstallCmd extends CliCommand:
  def name = "install"
  override def summary = "Install ssc to a prefix, or a .sscpkg plugin"
  override def category = "Build, bundle & package"
  override def details = List("ssc install [--prefix <dir>]  |  ssc install <path|name>")
  def run(args: List[String]): Unit =
    if args.isEmpty || args.headOption.contains("--prefix") then selfInstallCommand(args)
    else pluginInstall(args)
