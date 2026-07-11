package ssc.plugin.httpfast

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** Transport-level throughput comparison: the new [[FastHttpServer]] engine vs the raw
  * `com.sun.net.httpserver.HttpServer` the old plugin uses (both with virtual-thread
  * executors, identical trivial handler — this isolates the parser/router/transport, not
  * the ssc VM). Not a unit test; run it explicitly:
  *
  *   sbt "v2NativeHttpFastPlugin/Test/runMain ssc.plugin.httpfast.HttpFastBench"
  */
object HttpFastBench:
  private val Body = "hello, world\n".getBytes(UTF_8)

  def main(args: Array[String]): Unit =
    val requests    = sys.env.getOrElse("BENCH_REQS", "60000").toInt
    val concurrency = sys.env.getOrElse("BENCH_CONC", "64").toInt

    println(s"HTTP transport bench: $requests requests, $concurrency concurrent, keep-alive")
    println("(warmup 20k each, then measured)\n")

    val jdkPort = startJdk()
    val fast    = new FastHttpServer(_ => RawResponse(200, Map("Content-Type" -> "text/plain"), Body))
    val fastPort = fast.start(0)

    try
      // warm both
      load(jdkPort, 20000, concurrency)
      load(fastPort, 20000, concurrency)

      val jdk  = load(jdkPort, requests, concurrency)
      val fastR = load(fastPort, requests, concurrency)

      println(f"${"server"}%-16s ${"req/s"}%12s ${"p50 ms"}%10s ${"p99 ms"}%10s ${"errors"}%8s")
      println("-" * 60)
      printRow("com.sun (old)", jdk)
      printRow("FastHttpServer", fastR)
      println()
      println(f"throughput ratio (new / old): ${fastR.reqPerSec / jdk.reqPerSec}%.2fx")
    finally
      fast.stop()
      jdkServer.foreach(_.stop(0))

  private var jdkServer: Option[HttpServer] = None
  private def startJdk(): Int =
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    s.createContext("/", (ex: HttpExchange) => {
      ex.getResponseHeaders.set("Content-Type", "text/plain")
      ex.sendResponseHeaders(200, Body.length.toLong)
      val os = ex.getResponseBody; os.write(Body); os.close()
    })
    s.start()
    jdkServer = Some(s)
    s.getAddress.getPort

  private final case class Result(reqPerSec: Double, p50: Double, p99: Double, errors: Int)

  private def load(port: Int, total: Int, concurrency: Int): Result =
    val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    val uri    = URI.create(s"http://127.0.0.1:$port/")
    val req    = HttpRequest.newBuilder(uri).GET().build()
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
    val elapsedNs = System.nanoTime() - startNs
    pool.shutdown()
    val sorted = latencies.toArray(Array.empty[java.lang.Long]).map(_.toLong).sorted
    def pct(p: Double): Double =
      if sorted.isEmpty then 0.0 else sorted(math.min(sorted.length - 1, (p * sorted.length).toInt)) / 1e6
    Result(total.toDouble / (elapsedNs / 1e9), pct(0.50), pct(0.99), errors.get())

  private def printRow(name: String, r: Result): Unit =
    println(f"$name%-16s ${r.reqPerSec}%12.0f ${r.p50}%10.3f ${r.p99}%10.3f ${r.errors}%8d")
