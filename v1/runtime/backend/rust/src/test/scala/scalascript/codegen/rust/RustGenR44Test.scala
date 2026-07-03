package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.4.4 — State + Random tagless-final effects.
 *  Verifies:
 *  - `runState(init) { body }` injects `StateHandler { state: init }`
 *  - `State.get()` lowers to `_eff.get_state()`
 *  - `State.put(s)` lowers to `_eff.put_state(s)`
 *  - `runRandom(seed) { body }` injects `RandomHandler { seed: seed as u64 }`
 *  - `Random.nextInt(b)` lowers to `_eff.next_int(b)`
 *  - `Random.nextFloat()` lowers to `_eff.next_float()`
 *  - `effects.rs` emits `StateEffect` trait + `StateHandler` struct
 *  - `effects.rs` emits `RandomEffect` trait + `RandomHandler` struct */
class RustGenR44Test extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def assets(src: String): Map[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) => n -> new String(b, "UTF-8") }.toMap
      case other => fail(s"expected Segmented, got $other")

  // ── State effect ──────────────────────────────────────────────────────

  private val stateSrc =
    """```scalascript
      |def workload(): Int = runState(0) {
      |  State.put(State.get() + 1)
      |  State.get()
      |}
      |```
      |""".stripMargin

  test("runState injects StateHandler with init value"):
    val g = assets(stateSrc)("src/generated/ssc_program.rs")
    assert(g.contains("let mut _eff = StateHandler { state: 0i64 }"),
      s"StateHandler init missing in:\n$g")

  test("State.get() lowers to _eff.get_state()"):
    val g = assets(stateSrc)("src/generated/ssc_program.rs")
    assert(g.contains("_eff.get_state()"),
      s"get_state call missing in:\n$g")

  test("State.put(s) lowers to _eff.put_state(s)"):
    val g = assets(stateSrc)("src/generated/ssc_program.rs")
    assert(g.contains("_eff.put_state("),
      s"put_state call missing in:\n$g")

  test("effects.rs emits StateEffect trait"):
    val eff = assets(stateSrc)("src/runtime/effects.rs")
    assert(eff.contains("pub trait StateEffect"),  s"StateEffect trait missing in:\n$eff")
    assert(eff.contains("fn get_state"),           s"get_state op missing in:\n$eff")
    assert(eff.contains("fn put_state"),           s"put_state op missing in:\n$eff")

  test("effects.rs emits StateHandler struct"):
    val eff = assets(stateSrc)("src/runtime/effects.rs")
    assert(eff.contains("pub struct StateHandler"), s"StateHandler struct missing in:\n$eff")
    assert(eff.contains("impl StateEffect for StateHandler"), s"impl missing in:\n$eff")

  // ── Random effect ─────────────────────────────────────────────────────

  private val randomSrc =
    """```scalascript
      |def workload(): Int = runRandom(42) {
      |  Random.nextInt(100) + Random.nextInt(100)
      |}
      |```
      |""".stripMargin

  test("runRandom injects RandomHandler with seed"):
    val g = assets(randomSrc)("src/generated/ssc_program.rs")
    assert(g.contains("let mut _eff = RandomHandler { seed: 42i64 as u64 }"),
      s"RandomHandler init missing in:\n$g")

  test("Random.nextInt(b) lowers to _eff.next_int(b)"):
    val g = assets(randomSrc)("src/generated/ssc_program.rs")
    assert(g.contains("_eff.next_int(100i64)"),
      s"next_int call missing in:\n$g")

  test("effects.rs emits RandomEffect trait"):
    val eff = assets(randomSrc)("src/runtime/effects.rs")
    assert(eff.contains("pub trait RandomEffect"), s"RandomEffect trait missing in:\n$eff")
    assert(eff.contains("fn next_int"),            s"next_int op missing in:\n$eff")
    assert(eff.contains("fn next_float"),          s"next_float op missing in:\n$eff")

  test("effects.rs emits RandomHandler struct with LCG"):
    val eff = assets(randomSrc)("src/runtime/effects.rs")
    assert(eff.contains("pub struct RandomHandler"), s"RandomHandler struct missing in:\n$eff")
    assert(eff.contains("impl RandomEffect for RandomHandler"), s"impl missing in:\n$eff")
    assert(eff.contains("wrapping_mul"),             s"LCG multiply missing in:\n$eff")

  // ── Random.nextFloat ─────────────────────────────────────────────────

  private val nextFloatSrc =
    """```scalascript
      |def workload(): Double = runRandom(7) {
      |  Random.nextFloat()
      |}
      |```
      |""".stripMargin

  test("Random.nextFloat() lowers to _eff.next_float()"):
    val g = assets(nextFloatSrc)("src/generated/ssc_program.rs")
    assert(g.contains("_eff.next_float()"),
      s"next_float call missing in:\n$g")

  // ── hello-world stays effect-free ────────────────────────────────────

  test("hello-world does not emit effects.rs"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    assert(!a.contains("src/runtime/effects.rs"),
      "effects.rs should not be emitted for a program with no effects")

  // ── Custom one-shot effect + explicit handle/resume (R.4.2) ────────────
  // A user `effect Bump: def tick(): Int` lowers to a `BumpEffect` trait; `Bump.tick()`
  // becomes `_eff.tick()`; `handle(body) { case Bump.tick(resume) => resume(5) }` becomes
  // a handler struct that impls the trait (with `resume(5)` ⇒ `5`), run against the body.
  private val customEffSrc =
    """```scalascript
      |effect Bump:
      |  def tick(): Int
      |def useEff(): Int ! Bump =
      |  Bump.tick() + Bump.tick()
      |@main def run(): Unit =
      |  println(handle(useEff()) { case Bump.tick(resume) => resume(5) })
      |```
      |""".stripMargin

  test("custom effect emits a trait with required methods (no NoOp default)"):
    val eff = assets(customEffSrc)("src/runtime/effects.rs")
    assert(eff.contains("pub trait BumpEffect {") && eff.contains("fn tick(&mut self) -> i64;"),
      s"BumpEffect trait missing in:\n$eff")
    assert(!eff.contains("NoOpBump"), s"a custom effect must not get a NoOp struct:\n$eff")

  test("effectful def gets the _eff param and Bump.tick() lowers to _eff.tick()"):
    val g = assets(customEffSrc)("src/generated/ssc_program.rs")
    assert(g.contains("pub fn useEff(_eff: &mut impl BumpEffect)"), s"_eff param missing:\n$g")
    assert(g.contains("_eff.tick()"), s"Bump.tick() did not lower to _eff.tick():\n$g")

  test("handle lowers to a handler struct + impl with resume(v) ⇒ v"):
    val g = assets(customEffSrc)("src/generated/ssc_program.rs")
    assert(g.contains("struct __H_Bump") && g.contains("impl BumpEffect for __H_Bump"),
      s"handler struct/impl missing:\n$g")
    assert(g.contains("fn tick(&mut self) -> i64 { 5i64 }"),
      s"resume(5) should lower to the op method returning 5i64:\n$g")
    assert(g.contains("let mut _eff = __H_Bump; useEff(&mut _eff)"),
      s"body should run against the handler:\n$g")
