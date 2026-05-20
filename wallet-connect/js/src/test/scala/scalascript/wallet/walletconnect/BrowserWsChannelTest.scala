package scalascript.wallet.walletconnect

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** Scala.js test for [[BrowserWsChannel]] — exercises connect / send /
 *  onText / close round-trips against a JS-side mock `WebSocket` that
 *  the channel constructs via its injectable `wsConstructor` parameter
 *  (no need to stub `globalThis.WebSocket`).
 *
 *  Real browser-integration testing (against a live `wss://...`) is
 *  out of scope here — Scala.js test runners execute under Node.js
 *  with no `WebSocket` global, so we test the adapter's contract with
 *  a deterministic mock instead. */
class BrowserWsChannelTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  /** Captures every `send`, exposes hooks to drive onopen / onmessage
   *  / onclose from the test side, and tracks how many close() calls
   *  the channel made. */
  private final class MockWebSocket extends BrowserWsChannel.BrowserWebSocket:
    var onopen:    js.Function1[js.Any, Unit]                       = (_ => ())
    var onmessage: js.Function1[BrowserWsChannel.MessageEvent, Unit] = (_ => ())
    var onclose:   js.Function1[js.Any, Unit]                       = (_ => ())
    var onerror:   js.Function1[js.Any, Unit]                       = (_ => ())
    val sentFrames = scala.collection.mutable.ArrayBuffer.empty[String]
    var closeCount = 0
    def send(data: String): Unit = sentFrames += data
    def close(): Unit = closeCount += 1

  private def mockMessageEvent(payload: js.Any): BrowserWsChannel.MessageEvent =
    new BrowserWsChannel.MessageEvent:
      val data: js.Any = payload

  test("connect → onopen completes the Future and records the URL") {
    val mock = new MockWebSocket
    var ctorUrl: Option[String] = None
    val ch = new BrowserWsChannel(url => { ctorUrl = Some(url); mock })
    val fut = ch.connect("wss://relay.example.com?auth=stub")
    // Simulate the underlying socket announcing its open state.
    mock.onopen(js.Object())
    fut.map { _ =>
      assert(ctorUrl.contains("wss://relay.example.com?auth=stub"))
    }
  }

  test("onText receives text frames the underlying socket reports") {
    val mock = new MockWebSocket
    val ch   = new BrowserWsChannel(_ => mock)
    val received = scala.collection.mutable.ArrayBuffer.empty[String]
    ch.onText(s => received += s)
    val fut = ch.connect("wss://relay.example.com")
    mock.onopen(js.Object())
    fut.map { _ =>
      // Push two text frames.
      mock.onmessage(mockMessageEvent("hello".asInstanceOf[js.Any]))
      mock.onmessage(mockMessageEvent("world".asInstanceOf[js.Any]))
      // Binary frame (object) — should be ignored.
      mock.onmessage(mockMessageEvent(js.Object()))
      assert(received.toSeq == Seq("hello", "world"))
    }
  }

  test("send before connect fails with IllegalStateException") {
    val ch = new BrowserWsChannel(_ => new MockWebSocket)
    val fut = ch.send("oops")
    fut.failed.map { ex =>
      assert(ex.isInstanceOf[IllegalStateException])
      assert(ex.getMessage.contains("send before connect"))
    }
  }

  test("send after connect forwards data to the underlying socket") {
    val mock = new MockWebSocket
    val ch   = new BrowserWsChannel(_ => mock)
    val fut = ch.connect("wss://relay.example.com")
    mock.onopen(js.Object())
    for
      _ <- fut
      _ <- ch.send("frame-1")
      _ <- ch.send("frame-2")
    yield assert(mock.sentFrames.toSeq == Seq("frame-1", "frame-2"))
  }

  test("close idempotency — calling close twice still resolves and only triggers one socket close") {
    val mock = new MockWebSocket
    val ch   = new BrowserWsChannel(_ => mock)
    val fut = ch.connect("wss://relay.example.com")
    mock.onopen(js.Object())
    for
      _ <- fut
      _ <- ch.close()
      _ <- ch.close()   // second close is a no-op
    yield assert(mock.closeCount == 1, s"expected 1 underlying close, got ${mock.closeCount}")
  }
