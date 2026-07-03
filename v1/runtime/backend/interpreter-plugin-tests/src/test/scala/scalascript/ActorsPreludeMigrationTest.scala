package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.actors.ActorsInterpreterPlugin

/** core-min-prelude-migrate (actors): the actor/process/cluster keyword set left the hardcoded Typer
 *  prelude `effectBuiltins` and now ships in `ActorsInterpreterPlugin.preludeSymbols`. The bundled
 *  actors plugin declares them for `ssc check`; the runtime stays in core via the provider seam.
 *  This locks the migration for a representative name from each category: a plugin-less strict
 *  typecheck MUST flag the name as undefined, and a typecheck carrying the plugin's `preludeSymbols`
 *  MUST resolve it. (The mechanism is identical for all ~60 names, so a sample suffices.) */
class ActorsPreludeMigrationTest extends AnyFunSuite with Matchers:

  private val prelude = new ActorsInterpreterPlugin().preludeSymbols

  // one representative name per category — runner, primitive, ref, membership, leader, gossip,
  // config, drain, metric, timer.
  private val sample = List(
    "runActors", "spawn", "self", "send", "actorRef", "clusterMembers", "electLeader",
    "requestGossip", "clusterConfigGet", "setDraining", "clusterMetricSum", "sendAfter", "recvFrom",
  )

  test("every migrated actor name is declared in the plugin's preludeSymbols"):
    val declared = prelude.map(_.name).toSet
    val missing  = sample.filterNot(declared.contains)
    assert(missing.isEmpty, s"actors plugin preludeSymbols missing: ${missing.mkString(", ")}")

  for name <- sample do
    test(s"$name resolves for `ssc check` via the actors plugin's preludeSymbols, not a core hardcode"):
      val mod = Parser.parse(s"# T\n\n```scalascript\ndef f(): Int = { $name; 1 }\n```\n")
      // removed from `effectBuiltins` → a plugin-less strict typecheck flags it…
      assert(scalascript.typer.Typer.typeCheckStrict(mod).errors.exists(_.msg.contains(name)),
        s"$name should be undefined without the plugin's prelude")
      // …the bundled actors plugin DECLARES it, so a typecheck with its preludeSymbols resolves it.
      val typed = scalascript.typer.Typer(strict = true, preludeSymbols = prelude).typeCheck(mod)
      assert(!typed.errors.exists(_.msg.contains(s"undefined name: $name")),
        s"$name should resolve via preludeSymbols; got: ${typed.errors.map(_.msg).mkString(" | ")}")
