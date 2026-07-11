package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

class WebSocketEngineTest extends AnyFunSuite:

  test("acceptKey matches the RFC 6455 example vector") {
    // RFC 6455 §1.3: key "dGhlIHNhbXBsZSBub25jZQ==" → "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
    assert(WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
  }

  test("isUpgrade requires the websocket Upgrade header + a key") {
    assert(WebSocketFrames.isUpgrade(Map("upgrade" -> "websocket", "sec-websocket-key" -> "x")))
    assert(!WebSocketFrames.isUpgrade(Map("upgrade" -> "websocket")))
    assert(!WebSocketFrames.isUpgrade(Map("sec-websocket-key" -> "x")))
  }

  // --- integration over a real socket, driven by java.net.http.WebSocket ---

  private final class TestDispatcher(wire: WsConnection => Unit, route: String = "/ws")
      extends FastHttpServer.WebSocketDispatcher:
    private val ids = new AtomicLong(0)
    val opened = new java.util.concurrent.atomic.AtomicInteger(0)
    def hasRoute(path: String): Boolean = path == route
    def onUpgrade(request: RawRequest, sock: java.net.Socket, reader: HttpReader,
                  out: java.io.OutputStream): Unit =
      val key = request.headers.getOrElse("sec-websocket-key", "")
      WebSocketFrames.writeHandshake(out, key, None)
      sock.setSoTimeout(0)
      val conn = new WsConnection(ids.incrementAndGet(), sock, reader, out, request, None)
      opened.incrementAndGet()
      wire(conn)
      conn.readLoop()

  private def withWsServer(dispatcher: FastHttpServer.WebSocketDispatcher)(body: (Int, HttpClient) => Unit): Unit =
    val server = new FastHttpServer(
      _ => RawResponse(426, Map.empty, Array.emptyByteArray),
      webSocket = Some(dispatcher))
    val port = server.start(0)
    try body(port, HttpClient.newHttpClient())
    finally server.stop()

  private def collectingListener(sink: LinkedBlockingQueue[String], closed: CountDownLatch): WebSocket.Listener =
    new WebSocket.Listener:
      private val buf = new StringBuilder
      override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletableFuture[?] =
        buf.append(data)
        if last then { sink.add(buf.toString); buf.setLength(0) }
        ws.request(1)
        null
      override def onClose(ws: WebSocket, status: Int, reason: String): CompletableFuture[?] =
        closed.countDown(); null

  test("echoes a text message end-to-end (upgrade + framing)") {
    val d = new TestDispatcher(conn => conn.onText = s => conn.sendText(s"echo:$s"))
    withWsServer(d) { (port, client) =>
      val got   = new LinkedBlockingQueue[String]()
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), collectingListener(got, new CountDownLatch(1))).join()
      ws.sendText("hello", true).join()
      assert(got.poll(5, TimeUnit.SECONDS) == "echo:hello")
      assert(d.opened.get() == 1)
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
    }
  }

  test("assembles a fragmented client message") {
    val d = new TestDispatcher(conn => conn.onText = s => conn.sendText(s))
    withWsServer(d) { (port, client) =>
      val got = new LinkedBlockingQueue[String]()
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), collectingListener(got, new CountDownLatch(1))).join()
      ws.sendText("part1-", false).join()
      ws.sendText("part2", true).join()
      assert(got.poll(5, TimeUnit.SECONDS) == "part1-part2")
    }
  }

  test("round-trips a large message (exercises 16-bit/64-bit length encoding)") {
    val big = "x" * 200000
    val d = new TestDispatcher(conn => conn.onText = s => conn.sendText(s))
    withWsServer(d) { (port, client) =>
      val got = new LinkedBlockingQueue[String]()
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), collectingListener(got, new CountDownLatch(1))).join()
      ws.sendText(big, true).join()
      assert(got.poll(10, TimeUnit.SECONDS) == big)
    }
  }

  test("delivers binary frames") {
    val received = new LinkedBlockingQueue[Array[Byte]]()
    val d = new TestDispatcher(conn => conn.onBinary = b => { received.add(b); conn.sendBytes(b) })
    withWsServer(d) { (port, client) =>
      val back = new LinkedBlockingQueue[Int]()
      val listener = new WebSocket.Listener:
        override def onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
          back.add(data.remaining()); ws.request(1); null
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), listener).join()
      ws.sendBinary(ByteBuffer.wrap(Array[Byte](1, 2, 3, 4, 5)), true).join()
      assert(back.poll(5, TimeUnit.SECONDS) == 5)
      assert(received.poll(5, TimeUnit.SECONDS).sameElements(Array[Byte](1, 2, 3, 4, 5)))
    }
  }

  test("server-initiated close fires the client onClose") {
    val d = new TestDispatcher(conn => conn.onText = _ => conn.close(1000, "done"))
    withWsServer(d) { (port, client) =>
      val closed = new CountDownLatch(1)
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), collectingListener(new LinkedBlockingQueue, closed)).join()
      ws.sendText("go", true).join()
      assert(closed.await(5, TimeUnit.SECONDS))
    }
  }

  test("client close fires the server-side onClose callback") {
    val serverClosed = new CountDownLatch(1)
    val d = new TestDispatcher(conn => conn.onClose = (_, _) => serverClosed.countDown())
    withWsServer(d) { (port, client) =>
      val ws = client.newWebSocketBuilder()
        .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), new WebSocket.Listener {}).join()
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
      assert(serverClosed.await(5, TimeUnit.SECONDS))
    }
  }

  test("broadcasts to many concurrent connections (WsRoom pattern)") {
    val room = new java.util.concurrent.CopyOnWriteArrayList[WsConnection]()
    val d = new TestDispatcher(conn => {
      room.add(conn)
      conn.onText = s => room.forEach(c => if !c.isClosed then c.sendText(s))
      conn.onClose = (_, _) => room.remove(conn)
    })
    withWsServer(d) { (port, client) =>
      val n = 20
      val sinks = (0 until n).map(_ => new LinkedBlockingQueue[String]())
      val sockets = (0 until n).map { i =>
        client.newWebSocketBuilder()
          .buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), collectingListener(sinks(i), new CountDownLatch(1))).join()
      }
      // one client broadcasts; all n (including sender) should receive it
      sockets.head.sendText("broadcast!", true).join()
      for i <- 0 until n do
        assert(sinks(i).poll(5, TimeUnit.SECONDS) == "broadcast!", s"client $i missed the broadcast")
      sockets.foreach(_.sendClose(WebSocket.NORMAL_CLOSURE, "").join())
    }
  }
