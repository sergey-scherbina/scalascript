package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.ir.ExportedSymbol

/** core-min-prelude-migrate (batch + typed): bundled effect-runner keywords are no longer hardcoded
 *  in the Typer prelude. The VARIADIC ones (`runRetry` / `runRetryNoSleep` / `runCache` /
 *  `runCacheBypass` / `runClock` / `runEnv`) left `effectBuiltins`; the TYPED ones
 *  (`runRandomSeeded` / `runClockAt` / `runEnvWith` — formerly `runnerType2` `s.define`s) left their
 *  core defs. Each now ships in its plugin's `preludeSymbols` (the keystone). Since the typer does
 *  NOT enforce effect discharge, declaring even the typed runners `Any` is sufficient for `ssc check`.
 *  This locks the migration: a plugin-less strict typecheck MUST flag the name as undefined, and a
 *  typecheck carrying that plugin's `preludeSymbols` MUST resolve it — so re-adding the name to core
 *  (or dropping it from the plugin) regresses. */
class PreludeMigratedRunnersTest extends AnyFunSuite with Matchers:

  private val retry  = new scalascript.compiler.plugin.retry.RetryEffectPlugin().preludeSymbols
  private val cache  = new scalascript.compiler.plugin.cache.CacheEffectPlugin().preludeSymbols
  private val clock  = new scalascript.compiler.plugin.clock.ClockEffectPlugin().preludeSymbols
  private val env    = new scalascript.compiler.plugin.env.EnvEffectPlugin().preludeSymbols
  private val random = new scalascript.compiler.plugin.random.RandomEffectPlugin().preludeSymbols

  /** (runner keyword, a call expression exercising its arity, the plugin's preludeSymbols) */
  private val migrated: List[(String, String, List[ExportedSymbol])] = List(
    ("runRetry",        "runRetry { 1 }",            retry),
    ("runRetryNoSleep", "runRetryNoSleep { 1 }",     retry),
    ("runCache",        "runCache { 1 }",            cache),
    ("runCacheBypass",  "runCacheBypass { 1 }",      cache),
    ("runClock",        "runClock { 1 }",            clock),
    ("runEnv",          "runEnv { 1 }",              env),
    // typed runners (formerly `runnerType2` core defs) — two-arg call form `runX(seed){body}`
    ("runRandomSeeded", "runRandomSeeded(1) { 1 }",  random),
    ("runClockAt",      "runClockAt(1) { 1 }",       clock),
    ("runEnvWith",      "runEnvWith(1) { 1 }",        env),
  )

  for (runner, call, prelude) <- migrated do
    test(s"$runner resolves for `ssc check` via its plugin's preludeSymbols, not a core hardcode"):
      val mod = Parser.parse(s"# T\n\n```scalascript\ndef f(): Int = $call\n```\n")
      // removed from the core prelude → a plugin-less strict typecheck flags it…
      assert(scalascript.typer.Typer.typeCheckStrict(mod).errors.exists(_.msg.contains(runner)),
        s"$runner should be undefined without the plugin's prelude")
      // …the bundled plugin DECLARES it, so a typecheck with its preludeSymbols resolves it.
      assert(prelude.exists(_.name == runner), s"$runner must appear in the plugin's preludeSymbols")
      val typed = scalascript.typer.Typer(strict = true, preludeSymbols = prelude).typeCheck(mod)
      assert(!typed.errors.exists(_.msg.contains(s"undefined name: $runner")),
        s"$runner should resolve via preludeSymbols; got: ${typed.errors.map(_.msg).mkString(" | ")}")
