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
