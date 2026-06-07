package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.4.1 — algebraic-effects runtime infrastructure (no IR
 *  lowering yet; that lands in R.4.2). */
class RustGenR41Test extends AnyFunSuite:

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

  test("RustCapabilities declares AlgebraicEffects"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.AlgebraicEffects))

  test("EffectRs template covers the spec-mandated shapes"):
    val t = RustRuntimeTemplates.EffectRs
    assert(t.contains("pub struct Op {"),               "Op missing")
    assert(t.contains("pub enum EffArg {"),             "EffArg missing")
    assert(t.contains("pub enum Computation<A> {"),     "Computation missing")
    assert(t.contains("Pure(A)"),                       "Pure variant missing")
    assert(t.contains("Effect(Op, Box<dyn FnOnce(EffArg) -> Computation<A>>)"),
                                                         "Effect variant missing")
    assert(t.contains("pub struct HandlerStack {"),     "HandlerStack missing")
    assert(t.contains("pub fn run_with<A>"),            "run_with missing")
    assert(t.contains("pub fn pure<A>"),                "pure ctor missing")
    assert(t.contains("pub fn perform<A>"),             "perform ctor missing")
    // Embedded sanity tests verify the runtime independently of codegen.
    assert(t.contains("#[cfg(test)]"))
    assert(t.contains("pure_returns_its_value"))
    assert(t.contains("single_effect_dispatches_through_handler"))
    assert(t.contains("unhandled_effect_panics_with_named_diagnostic"))

  test("hello-world programs stay effect.rs-free"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin
    )
    assert(!a.contains("src/runtime/effect.rs"))
    assert(!a("src/runtime/mod.rs").contains("pub mod effect;"))

  test("programs that reference `perform(` get effect.rs + pub mod effect"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = ()
        |```
        |
        |```rust
        |pub fn x() -> i32 {
        |    use crate::runtime::effect::*;
        |    let _ = perform("x", vec![], |_| pure(0i64));
        |    0
        |}
        |```
        |""".stripMargin
    )
    assert(a.contains("src/runtime/effect.rs"),
      s"effect.rs missing from assets: ${a.keys.toList.sorted}")
    assert(a("src/runtime/mod.rs").contains("pub mod effect;"))

  test("`effect E:` declaration in scalascript also triggers the emit"):
    val a = assets(
      """```scalascript
        |effect Ask:
        |  case op(): Long
        |
        |@main def run(): Unit = ()
        |```
        |""".stripMargin
    )
    assert(a.contains("src/runtime/effect.rs"))

  test("scan ignores bare substrings (superformula, effective, …)"):
    val a = assets(
      """```scalascript
        |def superformula(): Long = 0
        |def effective():   Long = 0
        |def perfx():       Long = 0
        |def handler():     Long = 0
        |@main def run(): Unit = ()
        |```
        |""".stripMargin
    )
    assert(!a.contains("src/runtime/effect.rs"),
      s"false-positive effect emit: ${a.keys.toList.sorted}")

  test("Cargo.toml stays unchanged when effect runtime is emitted (no extra crate deps)"):
    val a = assets(
      """```rust
        |pub fn x() -> i32 {
        |    use crate::runtime::effect::*;
        |    let _ = perform("x", vec![], |_| pure(0i64));
        |    0
        |}
        |```
        |
        |```scalascript
        |@main def run(): Unit = ()
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(!toml.contains("sha2"))
    assert(!toml.contains("base64"))
    assert(!toml.contains("serde_json"))
    // effect.rs is pure std — never adds a crate dep.
