package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.4.2 — tagless-final effect traits (Logger etc.).
 *  Verifies:
 *  - `T ! E` return type is stripped to `T` in the Rust signature
 *  - effectful defs gain an `_eff: &mut impl EEffect` parameter
 *  - call sites to effectful defs thread `&mut _eff` automatically
 *  - `runLogger { body }` injects `NoOpLogger`
 *  - `src/runtime/effects.rs` is emitted with the trait + NoOp struct
 *  - `src/runtime/mod.rs` re-exports the `effects` submodule */
class RustGenR42Test extends AnyFunSuite:

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

  private val effectPureSrc =
    """# effect-pure
      |
      |```scalascript
      |def compute(n: Int): Int ! Logger =
      |  var acc = 0
      |  var i = 0
      |  while i < n do
      |    acc = acc + i
      |    i = i + 1
      |  acc
      |
      |def workload(): Int = runLogger { compute(10000) }
      |```
      |""".stripMargin

  test("T ! Logger return type stripped to T in Rust signature"):
    val g = assets(effectPureSrc)("src/generated/ssc_program.rs")
    assert(g.contains("pub fn compute(n: i64, _eff: &mut impl LoggerEffect) -> i64"),
      s"expected 'compute(n: i64, _eff: ...)' in:\n$g")

  test("workload has plain i64 return, no _eff param"):
    val g = assets(effectPureSrc)("src/generated/ssc_program.rs")
    assert(g.contains("pub fn workload() -> i64"), s"unexpected workload sig in:\n$g")

  test("runLogger block injects NoOpLogger and threads _eff"):
    val g = assets(effectPureSrc)("src/generated/ssc_program.rs")
    assert(g.contains("let mut _eff = NoOpLogger"),    s"NoOpLogger missing in:\n$g")
    assert(g.contains("compute(10000i64, &mut _eff)"), s"_eff threading missing in:\n$g")

  test("use crate::runtime::effects::* imported in generated file"):
    val g = assets(effectPureSrc)("src/generated/ssc_program.rs")
    assert(g.contains("use crate::runtime::effects::*"), s"import missing in:\n$g")

  test("effects.rs emitted with LoggerEffect trait"):
    val g = assets(effectPureSrc)
    assert(g.contains("src/runtime/effects.rs"), "effects.rs asset missing")
    val eff = g("src/runtime/effects.rs")
    assert(eff.contains("pub trait LoggerEffect"),  s"trait missing in effects.rs:\n$eff")
    assert(eff.contains("pub struct NoOpLogger"),   s"NoOpLogger missing in effects.rs:\n$eff")
    assert(eff.contains("impl LoggerEffect for NoOpLogger"), s"impl missing in effects.rs:\n$eff")

  test("runtime/mod.rs re-exports effects submodule"):
    val m = assets(effectPureSrc)("src/runtime/mod.rs")
    assert(m.contains("pub mod effects;"), s"'pub mod effects;' missing in runtime/mod.rs:\n$m")

  test("effects.rs LoggerEffect has log_info / log_warn / log_error / log_debug ops"):
    val eff = assets(effectPureSrc)("src/runtime/effects.rs")
    assert(eff.contains("fn log_info"),  s"log_info missing in:\n$eff")
    assert(eff.contains("fn log_warn"),  s"log_warn missing in:\n$eff")
    assert(eff.contains("fn log_error"), s"log_error missing in:\n$eff")
    assert(eff.contains("fn log_debug"), s"log_debug missing in:\n$eff")
