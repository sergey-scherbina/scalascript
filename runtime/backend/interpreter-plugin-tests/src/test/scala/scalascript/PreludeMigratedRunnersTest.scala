package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.ir.ExportedSymbol

/** core-min-prelude-migrate (batch): the bundled effect-runner keywords `runRetry` /
 *  `runRetryNoSleep` / `runCache` / `runCacheBypass` / `runClock` / `runEnv` are no longer
 *  hardcoded in the Typer prelude `effectBuiltins`. Each now ships in its plugin's
 *  `preludeSymbols` (the keystone). This locks the migration: a plugin-less strict typecheck
 *  MUST flag the name as undefined, and a typecheck carrying that plugin's `preludeSymbols`
 *  MUST resolve it — so re-adding the name to core (or dropping it from the plugin) regresses. */
class PreludeMigratedRunnersTest extends AnyFunSuite with Matchers:

  /** (runner keyword, the plugin that declares it) */
  private val migrated: List[(String, List[ExportedSymbol])] = List(
    "runRetry"        -> new scalascript.compiler.plugin.retry.RetryEffectPlugin().preludeSymbols,
    "runRetryNoSleep" -> new scalascript.compiler.plugin.retry.RetryEffectPlugin().preludeSymbols,
    "runCache"        -> new scalascript.compiler.plugin.cache.CacheEffectPlugin().preludeSymbols,
    "runCacheBypass"  -> new scalascript.compiler.plugin.cache.CacheEffectPlugin().preludeSymbols,
    "runClock"        -> new scalascript.compiler.plugin.clock.ClockEffectPlugin().preludeSymbols,
    "runEnv"          -> new scalascript.compiler.plugin.env.EnvEffectPlugin().preludeSymbols,
  )

  for (runner, prelude) <- migrated do
    test(s"$runner resolves for `ssc check` via its plugin's preludeSymbols, not a core hardcode"):
      val mod = Parser.parse(s"# T\n\n```scalascript\ndef f(): Int = $runner { 1 }\n```\n")
      // removed from `effectBuiltins` → a plugin-less strict typecheck flags it…
      assert(scalascript.typer.Typer.typeCheckStrict(mod).errors.exists(_.msg.contains(runner)),
        s"$runner should be undefined without the plugin's prelude")
      // …the bundled plugin DECLARES it, so a typecheck with its preludeSymbols resolves it.
      assert(prelude.exists(_.name == runner), s"$runner must appear in the plugin's preludeSymbols")
      val typed = scalascript.typer.Typer(strict = true, preludeSymbols = prelude).typeCheck(mod)
      assert(!typed.errors.exists(_.msg.contains(s"undefined name: $runner")),
        s"$runner should resolve via preludeSymbols; got: ${typed.errors.map(_.msg).mkString(" | ")}")
