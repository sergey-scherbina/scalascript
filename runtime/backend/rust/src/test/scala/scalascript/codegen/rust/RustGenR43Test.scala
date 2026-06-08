package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.4.3 — Stream effect via VecStream collector (effect-stream bench).
 *  Verifies:
 *  - `runStream { body }` injects `VecStream::new()` and collects emits
 *  - `Stream.emit(x)` lowers to `_eff.stream_emit(x)`
 *  - `val (src, _) = runStream { … }` tuple-destructuring val binding
 *  - `src.runToList()` lowers to `src.items.clone()`
 *  - `lst.length` lowers to `lst.len() as i64`
 *  - `effects.rs` emitted with `StreamEffect<T>` trait + `VecStream<T>`
 *  - `runtime/mod.rs` re-exports the `effects` submodule */
class RustGenR43Test extends AnyFunSuite:

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

  private val effectStreamSrc =
    """# effect-stream
      |
      |```scalascript
      |def workload(): Int =
      |  val (src, _) = runStream {
      |    var i = 0
      |    while i < 10000 do
      |      Stream.emit(i)
      |      i = i + 1
      |  }
      |  val lst = src.runToList()
      |  lst.length
      |```
      |""".stripMargin

  test("runStream injects VecStream and wraps result in tuple"):
    val g = assets(effectStreamSrc)("src/generated/ssc_program.rs")
    assert(g.contains("let mut _eff = VecStream::new()"), s"VecStream missing in:\n$g")
    assert(g.contains("(_eff, ())"),                      s"tuple wrap missing in:\n$g")

  test("Stream.emit(i) lowers to _eff.stream_emit(i)"):
    val g = assets(effectStreamSrc)("src/generated/ssc_program.rs")
    assert(g.contains("_eff.stream_emit("), s"stream_emit missing in:\n$g")

  test("val (src, _) = runStream generates tuple destructuring let"):
    val g = assets(effectStreamSrc)("src/generated/ssc_program.rs")
    assert(g.contains("let (src, _) = {"), s"tuple let binding missing in:\n$g")

  test("src.runToList() lowers to src.items.clone()"):
    val g = assets(effectStreamSrc)("src/generated/ssc_program.rs")
    assert(g.contains("src.items.clone()"), s"items.clone() missing in:\n$g")

  test("effects.rs emitted with StreamEffect trait and VecStream struct"):
    val g = assets(effectStreamSrc)
    assert(g.contains("src/runtime/effects.rs"), "effects.rs asset missing")
    val eff = g("src/runtime/effects.rs")
    assert(eff.contains("pub trait StreamEffect<T>"), s"StreamEffect trait missing in:\n$eff")
    assert(eff.contains("pub struct VecStream<T>"),   s"VecStream struct missing in:\n$eff")
    assert(eff.contains("fn stream_emit"),             s"stream_emit method missing in:\n$eff")
    assert(eff.contains("pub items: Vec<T>"),          s"items field missing in:\n$eff")

  test("runtime/mod.rs re-exports effects submodule for runStream"):
    val m = assets(effectStreamSrc)("src/runtime/mod.rs")
    assert(m.contains("pub mod effects;"), s"'pub mod effects;' missing in:\n$m")

  test("use crate::runtime::effects::* imported in generated file"):
    val g = assets(effectStreamSrc)("src/generated/ssc_program.rs")
    assert(g.contains("use crate::runtime::effects::*"), s"import missing in:\n$g")
