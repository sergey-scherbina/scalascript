package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.interpreter.Interpreter

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/** Smoke test: 10 000 concurrent WebSocket connections must open
 *  and drain without an OutOfMemoryError.
 *
 *  Skipped automatically when the OS file-descriptor limit is below
 *  22 000 (need ≈2 fd per in-process connection, plus OS overhead).
 *  On macOS the default per-process limit is 256; on Linux/CI it is
 *  typically 65 536 or higher.  The test always runs in CI if the fd
 *  limit is sufficient.
 *
 *  The Loom virtual-thread executor is used for both server and
 *  client so each connection costs only a few KB of stack, not a
 *  full platform thread (~1 MB).  This is the same executor wired
 *  into `WsProxy` and `WebServer` since the Project-Loom migration
 *  (2026-05-21). */
class WsLoad10kTest extends AnyFunSuite with Matchers:

  private val TARGET    = 10_000
  private val MIN_FD    = TARGET * 2 + 2_000   // headroom for OS + JVM internals
  private val THRESHOLD = 0.99                  // 99 % of connections must succeed

  /** Reads the OS fd limit.  Returns `Int.MaxValue` if unreadable. */
  private def fdLimit(): Int =
    try
      val out = scala.sys.process.Process(Seq("sh", "-c", "ulimit -n")).!!.trim
      out.toIntOption.getOrElse(Int.MaxValue)
    catch case _: Throwable => Int.MaxValue

  // ── helpers ────────────────────────────────────────────────────────────

  private def handshake(port: Int): Socket =
    val s = Socket("127.0.0.1", port)
    s.setSoTimeout(10_000)
    val req = (
      "GET /load HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).getBytes(StandardCharsets.US_ASCII)
    s.getOutputStream.write(req); s.getOutputStream.flush()
    // consume the HTTP/1.1 101 response header
    val in  = s.getInputStream
    var (p3, p2, p1) = (-1, -1, -1)
    var done = false
    while !done do
      val b = in.read()
      if b < 0 then done = true
      else if p3 == 13 && p2 == 10 && p1 == 13 && b == 10 then done = true
      else { p3 = p2; p2 = p1; p1 = b }
    s

  private def startServer(): (WsProxy, com.sun.net.httpserver.HttpServer, java.util.concurrent.ExecutorService, Int) =
    WsConnection.maxActive.set(Int.MaxValue)
    WsConnection.activeCount.set(0)
    val interp = Interpreter()
    interp.run(Parser.parse("""# load
```scala
onWebSocket("/load") { ws => () }
```
"""))
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val internal = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    internal.createContext("/", ex => { ex.sendResponseHeaders(404, -1); ex.close() })
    internal.setExecutor(executor)
    internal.start()
    val devNull = PrintStream(ByteArrayOutputStream(), true, "UTF-8")
    val proxy = WsProxy(
      publicPort           = 0,
      internalAddr         = InetSocketAddress("127.0.0.1", internal.getAddress.getPort),
      wsExecutor           = executor,
      log                  = devNull,
      wsRoutes             = interp.wsRoutes,
      heartbeatIntervalMs  = 600_000L,
      heartbeatDeadAfterMs = 1_800_000L,
    )
    proxy.start()
    (proxy, internal, executor, proxy.localPort)

  // ── test ───────────────────────────────────────────────────────────────

  test(s"$TARGET concurrent WebSocket connections: ≥99% open without OOM"):
    assume(fdLimit() >= MIN_FD,
      s"fd limit ${fdLimit()} < $MIN_FD — skip (run `ulimit -n 65536` to enable)")

    val (proxy, internal, executor, port) = startServer()
    val sockets  = java.util.concurrent.ConcurrentLinkedQueue[Socket]()
    val latch    = CountDownLatch(TARGET)
    val errors   = AtomicInteger(0)
    val peakHeap = AtomicLong(0L)

    val clientPool = Executors.newVirtualThreadPerTaskExecutor()
    try
      // ── Phase 1: connect TARGET clients ──────────────────────────
      val heapBefore = Runtime.getRuntime.totalMemory()
      (1 to TARGET).foreach { _ =>
        val task: Runnable = () =>
          try
            val s = handshake(port)
            sockets.add(s)
            val cur = Runtime.getRuntime.totalMemory()
            peakHeap.getAndUpdate(prev => math.max(prev, cur))
          catch case _: Throwable =>
            errors.incrementAndGet()
          finally
            latch.countDown()
        clientPool.submit(task)
      }
      latch.await(120, TimeUnit.SECONDS)

      val opened    = sockets.size
      val heapDelta = (peakHeap.get - heapBefore) / 1024 / 1024

      // ── assertions ───────────────────────────────────────────────
      // 1) ≥ THRESHOLD of targets must have successfully upgraded
      opened.toDouble / TARGET should be >= THRESHOLD

      // 2) No OOM: heap growth < 1 GB (10k × ~100 KB per WS session)
      heapDelta should be < 1024L

      // 3) activeCount tracks the open connections accurately
      WsConnection.activeCount.get should be >= opened - 100  // allow for async lag

      // ── Phase 2: close all and verify drain ──────────────────────
      sockets.forEach { s => try s.close() catch case _: Throwable => () }
      val deadline = System.nanoTime() + 30L * 1_000_000_000L
      while WsConnection.activeCount.get > 0 && System.nanoTime() < deadline do
        Thread.sleep(20)
      WsConnection.activeCount.get should be <= 100 // allow a brief async tail

    finally
      clientPool.shutdownNow()
      proxy.stop()
      internal.stop(0)
      executor.shutdownNow()
      WsConnection.activeCount.set(0)
      WsConnection.maxActive.set(Int.MaxValue)
