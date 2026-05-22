package scalascript.bench

import scalascript.server.{WsProxy, WsConnection, WsFraming}
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** Spawn-helper that uses Loom virtual threads on JDK 21+ and a
 *  regular daemon thread on older JDKs.  Bench file isn't worth
 *  losing Java 17 compat for. */
private def _spawnVirtualOrDaemon(body: () => Unit): Thread =
  try
    val cls   = Class.forName("java.lang.Thread$Builder$OfVirtual")
    val of    = classOf[Thread].getMethod("ofVirtual").invoke(null)
    cls.getMethod("start", classOf[Runnable])
      .invoke(of, (() => body()): Runnable).asInstanceOf[Thread]
  catch case _: Throwable =>
    val t = Thread(() => body())
    t.setDaemon(true)
    t.start()
    t

/** WebSocket connect / fan-out / disconnect baseline for the
 *  interpreter's NIO proxy.  Runs in-process: same JVM hosts the
 *  server + N WebSocket clients (Loom virtual threads, ~few KB each).
 *
 *  Goal:
 *    - Establish a baseline before any of the v1.0 Sprint 5
 *      architectural changes (NIO HTTP, perm-deflate) so future
 *      work can show what it actually moved.
 *    - Make the fan-out cost visible: with the per-connection
 *      write-queue from Sprint 1, broadcast to N receivers should
 *      scale O(N enqueues), not O(slowest peer).
 *
 *  Usage:
 *    sbt 'compiler/runMain scalascript.bench.WsStress [N]'
 *  where N = target concurrent connections (default 1000, cap 10000). */
@main def WsStress(args: String*): Unit =
  val target = args.headOption.map(_.toInt).getOrElse(1000)
  val n      = math.min(target, 10000)

  println(s"WS stress · target=$n · Loom (virtual threads)")
  // Client + server both live in this JVM, so each logical
  // connection eats 2 file descriptors.  At the typical Linux
  // default of `ulimit -n 1024` only ~500 connects succeed; bump
  // (`ulimit -n 8192`) before scaling past that.
  val ulimit =
    try scala.sys.process.Process(Seq("sh", "-c", "ulimit -n")).!!.trim
    catch case _: Throwable => "?"
  println(s"  ulimit -n=$ulimit · in-process server+client = 2 fd per connection")

  // ─── Server ─────────────────────────────────────────────────────
  WsConnection.maxActive.set(Int.MaxValue)
  WsConnection.activeCount.set(0)

  val interp = Interpreter()
  interp.run(Parser.parse("""# bench
```scala
var clients: List[WebSocket] = List()
onWebSocket("/bench") { ws =>
  clients = ws :: clients
  ws.onMessage { msg =>
    clients.foreach { c => c.send(msg) }
  }
  ws.onClose { () =>
    clients = clients.filter(c => c != ws)
  }
}
```
"""))

  val executor = Executors.newSingleThreadExecutor()
  val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  internal.createContext("/", ex => { ex.sendResponseHeaders(404, -1); ex.close() })
  internal.setExecutor(executor)
  internal.start()
  val devNull = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
  // Long heartbeat so it doesn't interfere with the bench timing.
  val proxy = WsProxy(
    publicPort           = 0,
    internalAddr         = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
    wsExecutor           = executor,
    log                  = devNull,
    wsRoutes             = interp.wsRoutes,
    heartbeatIntervalMs  = 600_000L,
    heartbeatDeadAfterMs = 1_800_000L
  )
  proxy.start()
  val port = proxy.localPort

  def now(): Long       = System.nanoTime()
  def msSince(t: Long): Long = (now() - t) / 1_000_000L

  // ─── Phase 1 · connect ──────────────────────────────────────────
  val sockets   = ConcurrentLinkedQueue[Socket]()
  val connected = CountDownLatch(n)
  val cerr      = AtomicInteger(0)
  val t1 = now()
  (1 to n).foreach { _ =>
    _spawnVirtualOrDaemon { () =>
      try
        val s = WsStressUtil.handshake(port)
        sockets.add(s)
        connected.countDown()
      catch case _: Throwable =>
        cerr.incrementAndGet()
        connected.countDown()
    }
  }
  connected.await(60, TimeUnit.SECONDS)
  val cMs    = msSince(t1)
  val opened = sockets.size
  val heap   = Runtime.getRuntime.totalMemory / 1024 / 1024
  println(f"  connect  · ${cMs}%5d ms · opened=$opened%-5d err=${cerr.get} · heap=${heap}%4d MB")

  // Let the server's executor drain its handler queue so every
  // connection is in `var clients`.
  Thread.sleep(500)

  // ─── Phase 2 · broadcast fan-out ────────────────────────────────
  // Cap receivers at 200 so the test thread doesn't spend forever
  // reading frames serially.  We measure latency of fanning out
  // through 200; full-N scales the same.
  val all  = sockets.toArray.map(_.asInstanceOf[Socket])
  val pub  = all.head
  val recv = all.drop(1).take(math.min(opened - 1, 200)).toList
  val bcLat = CountDownLatch(recv.length)
  val bcErr = AtomicInteger(0)
  recv.foreach { s =>
    _spawnVirtualOrDaemon { () =>
      try { WsStressUtil.readFrame(s); bcLat.countDown() }
      catch case _: Throwable => bcErr.incrementAndGet(); bcLat.countDown()
    }
  }
  val t2 = now()
  WsStressUtil.sendText(pub, "fanout-go")
  bcLat.await(20, TimeUnit.SECONDS)
  val bMs = msSince(t2)
  println(f"  fanout   · ${bMs}%5d ms · receivers=${recv.length}%-4d err=${bcErr.get}")

  // ─── Phase 3 · disconnect all ───────────────────────────────────
  val t3 = now()
  sockets.forEach { s => try s.close() catch case _: Throwable => () }
  val ddl = System.nanoTime() + 30L * 1_000_000_000L
  while WsConnection.activeCount.get > 0 && System.nanoTime() < ddl do
    Thread.sleep(10)
  val dMs = msSince(t3)
  println(f"  drain    · ${dMs}%5d ms · activeCount=${WsConnection.activeCount.get}")

  proxy.stop()
  internal.stop(0)
  executor.shutdownNow()

private object WsStressUtil:
  def handshake(port: Int): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(10000)
    val req = (
      "GET /bench HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req); s.getOutputStream.flush()
    // Drain handshake response — read byte-by-byte to leave any
    // post-101 bytes in `in` for the parser.
    val in = s.getInputStream
    var prev3, prev2, prev1 = -1
    var done = false
    while !done do
      val b = in.read()
      if b < 0 then done = true
      else
        if prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10 then done = true
        prev3 = prev2; prev2 = prev1; prev1 = b
    s

  def sendText(s: Socket, text: String): Unit =
    val payload = text.getBytes(StandardCharsets.UTF_8)
    val mask    = Array[Byte](1, 2, 3, 4)
    val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
    val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
    s.getOutputStream.write(frame); s.getOutputStream.flush()

  def readFrame(s: Socket): WsFraming.Frame =
    val in  = s.getInputStream
    val acc = scala.collection.mutable.ArrayBuffer.empty[Byte]
    val tmp = new Array[Byte](256)
    while true do
      WsFraming.tryParse(acc.toArray, 0, acc.length) match
        case Some(f) => return f
        case None =>
          val r = in.read(tmp)
          if r < 0 then throw RuntimeException("EOF before full frame")
          acc ++= tmp.iterator.take(r)
    throw RuntimeException("unreachable")
