package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Random effect, after extraction from interpreter core into `random-effect-plugin`
 *  (core-minimization). These are the cases formerly in `StdEffectsTest`, now run with NO explicit
 *  `installPlugins` — so `runRandom` / `runRandomSeeded` resolve purely via the lazy ServiceLoader
 *  path (the plugin is on this module's classpath through `allPlugins`), exactly as in production.
 *  `runRandomSeeded(seed) { … }` also covers the block-form SPI's config-args path. */
class RandomPluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  // core-min-prelude-migrate: `runRandom` is no longer hardcoded in the Typer prelude; it now
  // resolves for `ssc check` via the bundled plugin's `preludeSymbols` (the keystone, in action).
  test("runRandom resolves for `ssc check` via the plugin's preludeSymbols, not a core hardcode"):
    val mod = Parser.parse("# T\n\n```scalascript\ndef f(): Int = runRandom { 1 }\n```\n")
    // removed from `effectBuiltins` → a plugin-less strict typecheck flags it…
    assert(scalascript.typer.Typer.typeCheckStrict(mod).errors.exists(_.msg.contains("runRandom")),
      "runRandom should be undefined without the plugin's prelude")
    // …the bundled plugin DECLARES it, so a typecheck with its preludeSymbols resolves it.
    val prelude = new scalascript.compiler.plugin.random.RandomEffectPlugin().preludeSymbols
    val typed   = scalascript.typer.Typer(strict = true, preludeSymbols = prelude).typeCheck(mod)
    assert(!typed.errors.exists(_.msg.contains("undefined name: runRandom")),
      s"runRandom should resolve via preludeSymbols; got: ${typed.errors.map(_.msg).mkString(" | ")}")

  test("runRandomSeeded produces deterministic nextInt (via plugin)"):
    val r1 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    val r2 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    r1 shouldBe r2
    r1.split("\n").length shouldBe 2

  test("runRandomSeeded(1) nextInt(10) is in [0, 10) (via plugin)"):
    captured("""
      runRandomSeeded(1) {
        val n = Random.nextInt(10)
        println(n >= 0 && n < 10)
      }
    """) shouldBe "true"

  test("runRandomSeeded nextDouble in [0, 1) (via plugin)"):
    captured("""
      runRandomSeeded(7) {
        val d = Random.nextDouble()
        println(d >= 0.0 && d < 1.0)
      }
    """) shouldBe "true"

  test("Random.pick returns element from list (via plugin)"):
    captured("""
      runRandomSeeded(5) {
        val xs = List(10, 20, 30)
        val v  = Random.pick(xs)
        println(xs.contains(v))
      }
    """) shouldBe "true"

  test("Random.uuid returns a UUID-shaped string (via plugin)"):
    captured("""
      runRandomSeeded(99) {
        val id = Random.uuid()
        println(id.length)
      }
    """) shouldBe "36"

  test("runRandom (unseeded) produces a string of expected length (via plugin)"):
    captured("""
      runRandom {
        println(Random.uuid().length)
      }
    """) shouldBe "36"
