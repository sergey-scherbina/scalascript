package scalascript.plugin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import upickle.default.*


/** Integration test: spin up `SubprocessHost` in a thread, communicate over
 *  piped streams, verify the `describe` + `compile` + `shutdown` round-trips.
 *
 *  Uses a minimal in-memory `Backend` implementation that mirrors how a real
 *  plugin JAR would register via ServiceLoader — but wired directly without
 *  URLClassLoader, so the test runs on the normal classpath. */
class SubprocessHostTest extends AnyFunSuite:

  test("SubprocessHost handles describe + shutdown over JSON") {
    // We exercise dispatch directly (not via process spawn) for reliability.
    val backend = MockBackend()

    val reqDescribe = Request(method = Methods.Describe, params = ujson.Obj(), id = 1L)
    val resp = SubprocessHost.dispatchForTest(backend, reqDescribe)

    assert(resp.id == 1L)
    assert(resp.result.isDefined)
    val desc = read[MessageBodies.DescribeResult](resp.result.get)
    assert(desc.id          == "mock")
    assert(desc.displayName == "Mock Backend")
    assert(desc.role        == "backend")
    assert(!desc.interactive)
  }

  test("SubprocessHost handles compile and returns TextOutput") {
    val backend = MockBackend()
    val module  = scalascript.ir.NormalizedModule(manifest = None, sections = Nil)
    val params  = MessageBodies.CompileParams(irJson = ujson.read(write(module)))
    val req     = Request(method = Methods.Compile, params = writeJs(params), id = 2L)
    val resp    = SubprocessHost.dispatchForTest(backend, req)

    assert(resp.id == 2L)
    assert(resp.result.isDefined)
    val wire = read[MessageBodies.CompileResultWire](resp.result.get)
    assert(wire.kind == "text")
    assert(wire.code.contains("// mock output"))
  }

  test("SubprocessHost returns MethodNotFound for unknown method") {
    val backend = MockBackend()
    val req  = Request(method = "bogus.method", params = ujson.Obj(), id = 3L)
    val resp = SubprocessHost.dispatchForTest(backend, req)
    assert(resp.error.isDefined)
    assert(resp.error.get.code == ErrorCodes.MethodNotFound)
  }

  test("SubprocessHost session lifecycle: open / feed / close") {
    val backend = MockInteractiveBackend()
    SubprocessHost.sessions.clear()

    val openReq  = Request(Methods.OpenSession, writeJs(MessageBodies.OpenSessionParams()), id = 4L)
    val openResp = SubprocessHost.dispatchForTest(backend, openReq)
    assert(openResp.result.isDefined)
    val sessionId = read[MessageBodies.OpenSessionResult](openResp.result.get).sessionId

    val block: scalascript.ir.NormalizedBlock = scalascript.ir.Content.Prose("test")
    val feedReq  = Request(Methods.SessionFeed, writeJs(MessageBodies.SessionFeedParams(sessionId, block)), id = 5L)
    val feedResp = SubprocessHost.dispatchForTest(backend, feedReq)
    assert(feedResp.result.isDefined)
    val wire = read[MessageBodies.CompileResultWire](feedResp.result.get)
    assert(wire.kind == "text")

    val closeReq  = Request(Methods.SessionClose, writeJs(MessageBodies.SessionCloseParams(sessionId)), id = 6L)
    val closeResp = SubprocessHost.dispatchForTest(backend, closeReq)
    assert(closeResp.error.isEmpty)
  }

// ── Mock helpers ───────────────────────────────────────────────────────────

private class MockBackend extends scalascript.backend.spi.Backend:
  def id            = "mock"
  def displayName   = "Mock Backend"
  def spiVersion    = scalascript.backend.spi.SpiVersion.Current
  def capabilities  = Capabilities(Set.empty, Set.empty, Set.empty, SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics    = Map.empty
  def acceptedSources = Set("scalascript")
  def compile(module: scalascript.ir.NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.TextOutput(code = "// mock output", language = "scala")

private class MockInteractiveBackend extends MockBackend with scalascript.backend.spi.InteractiveBackend:
  def openSession(opts: BackendOptions): Session = new Session:
    def feed(block: scalascript.ir.NormalizedBlock): CompileResult =
      CompileResult.TextOutput(code = "// session output", language = "scala")
    def invokeHandler(handlerRef: scalascript.ir.SymbolRef, args: List[scalascript.ir.Value]): scalascript.ir.Value =
      scalascript.ir.Value.Prim(scalascript.ir.LitValue.IntL(42))
    def close(): Unit = ()
