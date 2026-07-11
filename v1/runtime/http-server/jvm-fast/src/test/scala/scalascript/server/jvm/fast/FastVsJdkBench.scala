package scalascript.server.jvm.fast

import scalascript.server.{Request, Response}
import scalascript.server.spi.*
import scalascript.server.jvm.JdkServerBackend
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Apples-to-apples framework-level comparison of the two production `HttpServerSpi` backends —
  * the fast NIO/vthread engine vs the com.sun `JdkServerBackend` — driven through the identical
  * SPI `HttpHandler` and client. This is the honest metric for making `fast` the default.
  * Not a unit test; run explicitly:
  *
  *   sbt "runtimeServerJvmFast/Test/runMain scalascript.server.jvm.fast.FastVsJdkBench"
  */
object FastVsJdkBench:
  private val handler = new HttpHandler:
    def onHttpRequest(req: Request): HttpResult =
      HttpResult.PlainResp(Response(200, Map("Content-Type" -> "text/plain"), "hello, world\n"))
    def onWsUpgrade(req: Request): WsUpgradeResult = WsUpgradeResult.Reject(404, "no ws")

  def main(args: Array[String]): Unit =
    val requests    = sys.env.getOrElse("BENCH_REQS", "60000").toInt
    val concurrency = sys.env.getOrElse("BENCH_CONC", "64").toInt
    println(s"HttpServerSpi backend bench: $requests requests, $concurrency concurrent, keep-alive\n")

    val fast = new FastServerBackend
    val jdk  = new JdkServerBackend
    fast.start(0, None, handler)
    jdk.start(0, None, handler)
    try
      load(jdk.localPort, 20000, concurrency)  // warm
      load(fast.localPort, 20000, concurrency)
      val j = load(jdk.localPort, requests, concurrency)
      val f = load(fast.localPort, requests, concurrency)
      println(f"${"backend"}%-16s ${"req/s"}%12s ${"p50 ms"}%10s ${"p99 ms"}%10s ${"errors"}%8s")
      println("-" * 60)
      printRow("jdk (com.sun)", j)
      printRow("fast (NIO/vt)", f)
      println(f"%nthroughput ratio (fast / jdk): ${f.reqPerSec / j.reqPerSec}%.2fx")
    finally
      fast.stop(); jdk.stop()

  private final case class Result(reqPerSec: Double, p50: Double, p99: Double, errors: Int)

  private def load(port: Int, total: Int, concurrency: Int): Result =
    val client    = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    val req       = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/")).GET().build()
    val latencies = new java.util.concurrent.ConcurrentLinkedQueue[Long]()
    val errors    = new AtomicInteger(0)
    val remaining = new AtomicInteger(total)
    val pool      = Executors.newVirtualThreadPerTaskExecutor()
    val startNs   = System.nanoTime()
    val workers = (0 until concurrency).map { _ =>
      pool.submit(new Runnable {
        def run(): Unit =
          while remaining.getAndDecrement() > 0 do
            val t0 = System.nanoTime()
            try
              val r = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
              if r.statusCode() != 200 then errors.incrementAndGet()
              latencies.add(System.nanoTime() - t0)
            catch case _: Throwable => errors.incrementAndGet()
      })
    }
    workers.foreach(_.get())
    val elapsed = System.nanoTime() - startNs
    pool.shutdown()
    val sorted = latencies.toArray(Array.empty[java.lang.Long]).map(_.toLong).sorted
    def pct(p: Double): Double =
      if sorted.isEmpty then 0.0 else sorted(math.min(sorted.length - 1, (p * sorted.length).toInt)) / 1e6
    Result(total.toDouble / (elapsed / 1e9), pct(0.50), pct(0.99), errors.get())

  private def printRow(name: String, r: Result): Unit =
    println(f"$name%-16s ${r.reqPerSec}%12.0f ${r.p50}%10.3f ${r.p99}%10.3f ${r.errors}%8d")
