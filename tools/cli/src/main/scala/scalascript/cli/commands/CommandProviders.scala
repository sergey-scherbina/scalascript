package scalascript.cli.commands

import scalascript.cli.*
import scalascript.compiler.plugin.BackendRegistry

// Generated CliCommand providers (see specs/cli-command-spi.md). They carry the
// help metadata (summary / category / details) and delegate execution to the
// command functions in the scalascript.cli package.

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
