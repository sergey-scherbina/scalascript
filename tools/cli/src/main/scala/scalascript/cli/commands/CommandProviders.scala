package scalascript.cli.commands

import scalascript.cli.*
import scalascript.compiler.plugin.BackendRegistry

// Generated CliCommand providers (see docs/cli-command-spi.md). During the
// incremental migration these delegate to the existing command functions in
// the scalascript.cli package; bodies move in here over time.

final class ParseCmd extends CliCommand:
  def name = "parse"
  def run(args: List[String]): Unit = parseCommand(args)

final class CheckCmd extends CliCommand:
  def name = "check"
  def run(args: List[String]): Unit = checkCommand(args)

final class RunCmd extends CliCommand:
  def name = "run"
  def run(args: List[String]): Unit = runCommand(args)

final class WatchCmd extends CliCommand:
  def name = "watch"
  def run(args: List[String]): Unit = watchCommand(args)

final class WatchBenchCmd extends CliCommand:
  def name = "watch-bench"
  def run(args: List[String]): Unit = watchBenchCommand(args)

final class ReplCmd extends CliCommand:
  def name = "repl"
  def run(args: List[String]): Unit = replCommand(args)

final class EmitJsCmd extends CliCommand:
  def name = "emit-js"
  def run(args: List[String]): Unit = emitJsCommand(args)

final class EmitWasmCmd extends CliCommand:
  def name = "emit-wasm"
  def run(args: List[String]): Unit = emitWasmCommand(args)

final class EmitOpenapiCmd extends CliCommand:
  def name = "emit-openapi"
  def run(args: List[String]): Unit = emitOpenapiCommand(args)

final class EmitSpaCmd extends CliCommand:
  def name = "emit-spa"
  def run(args: List[String]): Unit = emitSpaCommand(args)

final class EmitScalaCmd extends CliCommand:
  def name = "emit-scala"
  def run(args: List[String]): Unit = emitScalaCommand(args)

final class EmitSparkCmd extends CliCommand:
  def name = "emit-spark"
  def run(args: List[String]): Unit = emitSparkCommand(args)

final class SubmitCmd extends CliCommand:
  def name = "submit"
  def run(args: List[String]): Unit = submitCommand(args)

final class EmitWcCmd extends CliCommand:
  def name = "emit-wc"
  def run(args: List[String]): Unit = emitWcCommand(args)

final class EmitInterfaceCmd extends CliCommand:
  def name = "emit-interface"
  def run(args: List[String]): Unit = emitInterfaceCommand(args)

final class EmitIrCmd extends CliCommand:
  def name = "emit-ir"
  def run(args: List[String]): Unit = emitIrCommand(args)

final class RunJvmCmd extends CliCommand:
  def name = "run-jvm"
  def run(args: List[String]): Unit = runJvmCommand(args)

final class RunJsCmd extends CliCommand:
  def name = "run-js"
  def run(args: List[String]): Unit = runJsCommand(args)

final class CompileJvmCmd extends CliCommand:
  def name = "compile-jvm"
  def run(args: List[String]): Unit = compileJvmCommand(args)

final class CompileJsCmd extends CliCommand:
  def name = "compile-js"
  def run(args: List[String]): Unit = compileJsCommand(args)

final class CompileRuntimeCmd extends CliCommand:
  def name = "compile-runtime"
  def run(args: List[String]): Unit = compileRuntimeCommand(args)

final class CheckWithIfaceCmd extends CliCommand:
  def name = "check-with-iface"
  def run(args: List[String]): Unit = checkWithInterfaceCommand(args)

final class LinkCmd extends CliCommand:
  def name = "link"
  def run(args: List[String]): Unit = linkCommand(args)

final class GenerateFacadeCmd extends CliCommand:
  def name = "generate-facade"
  def run(args: List[String]): Unit = generateFacadeCommand(args)

final class InfoCmd extends CliCommand:
  def name = "info"
  def run(args: List[String]): Unit = infoCommand(args)

final class CleanCmd extends CliCommand:
  def name = "clean"
  def run(args: List[String]): Unit = cleanCommand(args)

final class VerifyCmd extends CliCommand:
  def name = "verify"
  def run(args: List[String]): Unit = verifyCommand(args)

final class CheckCompatCmd extends CliCommand:
  def name = "check-compat"
  def run(args: List[String]): Unit = checkCompatCommand(args)

final class DepsCmd extends CliCommand:
  def name = "deps"
  def run(args: List[String]): Unit = depsCommand(args)

final class DeployCmd extends CliCommand:
  def name = "deploy"
  def run(args: List[String]): Unit = deployCommand(args)

final class PackageCmd extends CliCommand:
  def name = "package"
  def run(args: List[String]): Unit = packageCommand(args)

final class PublishCmd extends CliCommand:
  def name = "publish"
  def run(args: List[String]): Unit = publishCommand(args)

final class ServeCmd extends CliCommand:
  def name = "serve"
  def run(args: List[String]): Unit = serveCommand(args)

final class RenderCmd extends CliCommand:
  def name = "render"
  def run(args: List[String]): Unit = renderCommand(args)

final class BuildCmd extends CliCommand:
  def name = "build"
  def run(args: List[String]): Unit = buildCommand(args)

final class BundleCmd extends CliCommand:
  def name = "bundle"
  def run(args: List[String]): Unit = bundleCommand(args)

final class NewCmd extends CliCommand:
  def name = "new"
  def run(args: List[String]): Unit = newCommand(args)

final class PluginCmd extends CliCommand:
  def name = "plugin"
  def run(args: List[String]): Unit = pluginCommand(args)

final class LockCmd extends CliCommand:
  def name = "lock"
  def run(args: List[String]): Unit = lockCommand(args)

final class UpdateCmd extends CliCommand:
  def name = "update"
  def run(args: List[String]): Unit = updateCommand(args)

final class SearchCmd extends CliCommand:
  def name = "search"
  def run(args: List[String]): Unit = registrySearchCommand(args)

final class AddCmd extends CliCommand:
  def name = "add"
  def run(args: List[String]): Unit = registryAddCommand(args)

final class TestCmd extends CliCommand:
  def name = "test"
  def run(args: List[String]): Unit = testCommand(args)

final class PreviewCmd extends CliCommand:
  def name = "preview"
  def run(args: List[String]): Unit = previewCommand(args)

final class FmtCmd extends CliCommand:
  def name = "fmt"
  def run(args: List[String]): Unit = fmtCommand(args)

final class BenchCmd extends CliCommand:
  def name = "bench"
  def run(args: List[String]): Unit = benchCommand(args)

final class ProfileCmd extends CliCommand:
  def name = "profile"
  def run(args: List[String]): Unit = profileCommand(args)

final class LspCmd extends CliCommand:
  def name = "lsp"
  def run(args: List[String]): Unit = lspCommand(args)

final class ClusterCmd extends CliCommand:
  def name = "cluster"
  def run(args: List[String]): Unit = clusterCommand(args)

final class DebugCmd extends CliCommand:
  def name = "debug"
  def run(args: List[String]): Unit = DebugCommand.run(args)

final class OauthCmd extends CliCommand:
  def name = "oauth"
  def run(args: List[String]): Unit = OAuthCli.run(args)

final class ToolchainCmd extends CliCommand:
  def name = "toolchain"
  def run(args: List[String]): Unit = ToolchainCommand.run(args)

final class HelpCmd extends CliCommand:
  def name = "help"
  override def aliases = List("--help", "-h")
  def run(args: List[String]): Unit = printUsage()

final class ListBackendsCmd extends CliCommand:
  def name = "--list-backends"
  def run(args: List[String]): Unit = println(BackendRegistry.describe)

final class InstallCmd extends CliCommand:
  def name = "install"
  // No args or --prefix => install ssc itself; otherwise => `plugin install` shortcut.
  def run(args: List[String]): Unit =
    if args.isEmpty || args.headOption.contains("--prefix") then selfInstallCommand(args)
    else pluginInstall(args)
