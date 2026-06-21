package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** v1.51.2 — JS codegen tests for backpressured streams.
 *
 *  All tests are code-shape checks (no node execution required):
 *  they verify `JsGen.generate` produces JS containing the expected patterns.
 */
class JsGenStreamsTest extends AnyFunSuite:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def js(code: String): String = JsGen.generate(module(code))

  // ── _makeAsyncStream runtime operators ──────────────────────────────────

  test("_makeAsyncStream runtime includes scan method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("scan(z)"))

  test("_makeAsyncStream runtime includes merge method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("merge(other)"))

  test("_makeAsyncStream runtime includes zipWith method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("zipWith(other)"))

  test("_makeAsyncStream runtime includes broadcast method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("broadcast(n)"))

  test("_makeAsyncStream runtime includes groupBy method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("groupBy(kf)"))

  test("_makeAsyncStream runtime includes mergeSubstreams method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("mergeSubstreams()"))

  test("_makeAsyncStream runtime includes mapAsync method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("mapAsync(n)"))

  test("_makeAsyncStream runtime includes to and via routing methods"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("async to(sink)"))
    assert(runtime.contains("via(flow)"))

  test("_makeAsyncStream runtime includes recover and mapError"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("recover(h)"))
    assert(runtime.contains("mapError(f)"))

  test("_makeAsyncStream runtime includes cancellable method"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Async))
    assert(runtime.contains("cancellable()"))

  // ── Codegen cases — factory methods ────────────────────────────────────

  test("Source.tick(ms) generates infinite async stream with setTimeout"):
    val out = js("Source.tick(50).take(3).runToList()")
    assert(out.contains("_makeAsyncStream"))
    assert(out.contains("while(true)"))
    assert(out.contains("setTimeout"))

  test("Source.unfold(seed)(f) generates async unfolding stream"):
    val out = js(
      "Source.unfold(0)(s => if s >= 3 then None else Some((s + 1, s))).runToList()"
    )
    assert(out.contains("_makeAsyncStream"))
    assert(out.contains("_None"))
    assert(out.contains("_t[0]"))

  test("Source.fromCallback(register) generates push-based stream"):
    val out = js(
      """Source.fromCallback(cb => { cb(1); cb(2); cb(3) }).runToList()"""
    )
    assert(out.contains("_makeAsyncStream"))
    assert(out.contains("_vs"))

  // ── Codegen cases — Sink companion ──────────────────────────────────────

  test("Sink.foreach(f) generates run via runForeach"):
    val out = js(
      """
      var total = 0
      val sink = Sink.foreach(x => { total = total + x })
      Source.from(List(1, 2, 3)).to(sink)
      """
    )
    assert(out.contains("runForeach"))

  test("Sink.fold(z)(f) generates run with accumulator"):
    val out = js(
      """
      val sink = Sink.fold(0)((acc, x) => acc + x)
      Source.from(List(1, 2, 3)).to(sink)
      """
    )
    assert(out.contains("acc="))

  test("Sink.ignore generates runDrain"):
    val out = js("Source.from(List(1, 2, 3)).to(Sink.ignore)")
    assert(out.contains("runDrain"))

  test("Sink.toList generates runToList"):
    val out = js("Source.from(List(1, 2, 3)).to(Sink.toList)")
    assert(out.contains("runToList"))

  // ── Codegen cases — Flow companion ─────────────────────────────────────

  test("Flow.map(f) generates apply via src.map(f)"):
    val out = js(
      """
      val flow = Flow.map(x => x * 2)
      Source.from(1 to 5).via(flow).runToList()
      """
    )
    assert(out.contains("src.map") || out.contains(".map("))

  test("Flow.filter(p) generates apply via src.filter(p)"):
    val out = js(
      """
      val flow = Flow.filter(x => x % 2 == 0)
      Source.from(1 to 10).via(flow).runToList()
      """
    )
    assert(out.contains("src.filter") || out.contains(".filter("))

  test("scan(z)(f) call chain is present in generated JS"):
    val out = js("Source.from(List(1, 2, 3)).scan(0)((acc, x) => acc + x).runToList()")
    assert(out.contains("'scan'") || out.contains(".scan("))

  // ── v1.51.6 Stream algebraic effect JS lowering ─────────────────────────
  // Note: JsGen.generate returns user-code only (no runtime preamble).
  // The `_perform('Stream', …)` definitions live in JsRuntimeV14Effects.
  // These tests verify (a) the Effects runtime section contains the Stream op
  // definitions and (b) the generated user code references Stream / runStream.

  test("JsRuntimeV14Effects contains Stream.emit _perform definition"):
    // _makeAsyncStream lives in JsRuntimeAsyncB; capability detection always
    // adds both Async and Effects when runStream/Stream.* is detected in user code.
    val rt = JsGen.generateRuntime(Set(JsGen.Capability.Effects, JsGen.Capability.Async))
    assert(rt.contains("_perform('Stream', 'emit'"),   s"missing Stream.emit in Effects runtime")
    assert(rt.contains("_perform('Stream', 'complete'"), s"missing Stream.complete in Effects runtime")
    assert(rt.contains("_perform('Stream', 'error'"),   s"missing Stream.error in Effects runtime")
    assert(rt.contains("_perform('Stream', 'request'"), s"missing Stream.request in Effects runtime")
    assert(rt.contains("function runStream("),          s"missing runStream in Effects runtime")
    assert(rt.contains("_makeAsyncStream"),             s"missing _makeAsyncStream in Effects runtime")

  test("runStream body with Stream.emit: user code references Stream and runStream"):
    val out = js(
      """
      val (src, _) = runStream { Stream.emit(1) }
      src
      """
    )
    assert(out.contains("runStream") || out.contains("_call(runStream"),
      s"expected runStream reference in user code: ${out.take(2000)}")
    assert(out.contains("Stream") || out.contains("'Stream'"),
      s"expected Stream reference in user code: ${out.take(2000)}")

  test("runStream body with Stream.complete: user code references Stream.complete"):
    val out = js("runStream { Stream.complete() }")
    assert(out.contains("complete") && (out.contains("Stream") || out.contains("'Stream'")),
      s"expected Stream.complete reference in user code: ${out.take(2000)}")

  test("runStream body with Stream.error: user code references Stream.error"):
    val out = js("""runStream { Stream.error("boom") }""")
    assert(out.contains("error") && (out.contains("Stream") || out.contains("'Stream'")),
      s"expected Stream.error reference in user code: ${out.take(2000)}")

  // ── Tuple monoid ++ JS lowering ─────────────────────────────────────────

  test("Core runtime includes _tupleConcat helper"):
    val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
    assert(rt.contains("function _tupleConcat("),
      s"missing _tupleConcat in Core runtime")
    assert(rt.contains("_isTuple"),
      s"_tupleConcat must set _isTuple on result")

  test("tuple-literal ++ tuple-literal flattens to a single array (no _tupleConcat)"):
    // A concat of two tuple LITERALS flattens into one `_isTuple` array literal instead
    // of `_tupleConcat(Object.assign(..), Object.assign(..))` (3 allocations → 1). The
    // value is identical; the single allocation matters in a hot loop (tuple-monoid).
    val out = js("val r = (1, 2) ++ (3, 4)")
    assert(out.contains("Object.assign([1, 2, 3, 4], {_isTuple: true})"),
      s"expected a single flattened tuple array: ${out.take(500)}")

  test("tuple-var ++ tuple still uses _tupleConcat (shape not a literal)"):
    // When a side is a variable (shape not statically a tuple literal) the runtime
    // `_tupleConcat` is still used — only literal-literal concat is flattened.
    val out = js("val a = (1, 2)\nval r = a ++ (3, 4)")
    assert(out.contains("_tupleConcat"),
      s"expected _tupleConcat for a variable operand: ${out.take(500)}")

  test("._N on a known tuple lowers to a direct index, not _dispatch"):
    // `t._1`/`t._4` on a tuple var → `t[0]`/`t[3]` (a single array read) instead of the
    // megamorphic `_dispatch(t, '_N', [])`. A case class's Product `._N` is unaffected.
    val out = js("val t = (10, 20) ++ (30, 40)\nval x = t._1 + t._4")
    assert(out.contains("t[0]") && out.contains("t[3]"),
      s"expected direct tuple indexing t[0]/t[3]: ${out.take(500)}")
    assert(!out.contains("_dispatch(t, '_1'"),
      s"._N on a tuple should not go through _dispatch: ${out.take(500)}")

  test("list ++ also uses _tupleConcat (runtime polymorphism)"):
    val out = js("val r = List(1, 2) ++ List(3, 4)")
    assert(out.contains("_tupleConcat"),
      s"expected _tupleConcat call in JS output: ${out.take(500)}")

  test("_tupleConcat handles non-array values (bare ++ bare)"):
    val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
    assert(rt.contains("Array.isArray(a)") || rt.contains("aArr"),
      s"_tupleConcat must handle non-array operands: ${rt.take(500)}")

  test("tuple ++ bare value uses _tupleConcat"):
    val out = js("val r = (1, 2) ++ 3")
    assert(out.contains("_tupleConcat"),
      s"expected _tupleConcat for tuple ++ bare: ${out.take(500)}")
