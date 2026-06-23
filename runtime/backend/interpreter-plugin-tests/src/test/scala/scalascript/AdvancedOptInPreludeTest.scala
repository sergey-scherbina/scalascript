package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.ir.ExportedSymbol

/** core-min-advanced-optin: the hardcoded Typer `pluginObjects`/`pluginBuiltins` names for
 *  plugin-owned features moved into their owning plugins' `preludeSymbols`. ADVANCED (opt-in)
 *  plugins' names are now strict-opt-in — flagged by a plugin-less `ssc check`, resolved only when
 *  the plugin's `preludeSymbols` are present (i.e. the plugin was added via `--plugin`). ESSENTIAL
 *  plugins keep resolving (auto-loaded). Interpreter-core globals + stdlib-`.ssc` names stay
 *  hardcoded. This test locks all three behaviours. */
class AdvancedOptInPreludeTest extends AnyFunSuite with Matchers:

  private def flaggedWithoutPlugin(name: String): Boolean =
    val mod = Parser.parse(s"# T\n\n```scalascript\ndef f(): Int = { $name; 1 }\n```\n")
    scalascript.typer.Typer.typeCheckStrict(mod).errors.exists(_.msg.contains(name))

  private def resolvesWith(name: String, prelude: List[ExportedSymbol]): Boolean =
    val mod = Parser.parse(s"# T\n\n```scalascript\ndef f(): Int = { $name; 1 }\n```\n")
    !scalascript.typer.Typer(strict = true, preludeSymbols = prelude)
      .typeCheck(mod).errors.exists(_.msg.contains(s"undefined name: $name"))

  // ── ADVANCED (opt-in): flagged without the plugin, resolved with its preludeSymbols ──
  private val oauth    = new scalascript.compiler.plugin.oauth.OAuthInterpreterPlugin().preludeSymbols
  private val payments = new scalascript.compiler.plugin.payments.PaymentPlugin().preludeSymbols

  for (name, prelude) <- List(
        "oauth" -> oauth, "oidc" -> oauth,
        "Wallets" -> payments, "X402Client" -> payments, "X402" -> payments,
        "CardanoFacilitator" -> payments, "PaymentConfig" -> payments) do
    test(s"advanced name `$name` is strict-opt-in (flagged without plugin, resolves with its preludeSymbols)"):
      assert(prelude.exists(_.name == name), s"$name must be declared by its advanced plugin")
      assert(flaggedWithoutPlugin(name), s"$name should be UNDEFINED for a plugin-less ssc check (strict opt-in)")
      assert(resolvesWith(name, prelude), s"$name should resolve once the plugin's preludeSymbols are present")

  // ── ESSENTIAL: moved off the hardcode but still declared by an auto-loaded plugin ──
  private val streams = new scalascript.compiler.plugin.streams.StreamsInterpreterPlugin().preludeSymbols
  private val ws      = new scalascript.compiler.plugin.ws.WsInterpreterPlugin().preludeSymbols

  test("essential names (Source, setHttpServerBackend) are declared by their plugin's preludeSymbols"):
    assert(streams.exists(_.name == "Source"), "streams-plugin must declare Source")
    assert(ws.exists(_.name == "setHttpServerBackend"), "ws-plugin must declare setHttpServerBackend")
    assert(resolvesWith("Source", streams) && resolvesWith("setHttpServerBackend", ws))

  // ── CORE / stdlib-.ssc names must STAY hardcoded (resolve with NO plugin) ──
  for name <- List("Async", "Await", "Signal", "Future", "Storage",
                   "Cluster", "HandlerRegistry", "ShuffleStage", "runDistributed") do
    test(s"core/stdlib name `$name` still resolves from the core prelude (NOT removed)"):
      assert(!flaggedWithoutPlugin(name), s"$name must still be defined by the core prelude")
