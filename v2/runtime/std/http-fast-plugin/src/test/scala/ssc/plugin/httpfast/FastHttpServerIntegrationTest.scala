package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** End-to-end tests over a real loopback socket, driven by java.net.http.HttpClient. */
class FastHttpServerIntegrationTest extends AnyFunSuite:

  private def withServer(handler: RawRequest => RawResponse)(body: (String, HttpClient) => Unit): Unit =
    val server = new FastHttpServer(handler, idleTimeoutMs = 5000)
    val port   = server.start(0)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    try body(s"http://127.0.0.1:$port", client)
    finally server.stop()

  private def text(status: Int, s: String): RawResponse =
    RawResponse(status, Map("Content-Type" -> "text/plain; charset=utf-8"), s.getBytes(UTF_8))

  private def get(client: HttpClient, url: String): HttpResponse[String] =
    client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

  test("serves a simple GET and reports the bound ephemeral port") {
    withServer(_ => text(200, "hello")) { (base, client) =>
      val r = get(client, s"$base/")
      assert(r.statusCode() == 200)
      assert(r.body() == "hello")
      assert(r.headers().firstValue("content-length").get == "5")
    }
  }

  test("echoes a POST body and reports method + path") {
    val handler: RawRequest => RawResponse = req =>
      text(200, s"${req.method} ${req.path} ${new String(req.body, UTF_8)}")
    withServer(handler) { (base, client) =>
      val req = HttpRequest.newBuilder(URI.create(s"$base/echo"))
        .POST(HttpRequest.BodyPublishers.ofString("payload")).build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      assert(r.body() == "POST /echo payload")
    }
  }

  test("surfaces the parsed query string to the handler") {
    val handler: RawRequest => RawResponse = req =>
      text(200, req.query.toList.sorted.map((k, v) => s"$k=$v").mkString(","))
    withServer(handler) { (base, client) =>
      assert(get(client, s"$base/s?a=1&b=hello+world").body() == "a=1,b=hello world")
    }
  }

  test("a handler that throws yields a 500") {
    withServer(_ => throw new RuntimeException("boom")) { (base, client) =>
      val r = get(client, s"$base/")
      assert(r.statusCode() == 500)
      assert(r.body().contains("boom"))
    }
  }

  test("keep-alive: many sequential requests reuse one connection") {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    withServer(_ => text(200, count.incrementAndGet().toString)) { (base, client) =>
      for i <- 1 to 25 do
        assert(get(client, s"$base/").body() == i.toString)
    }
  }

  test("handles concurrent requests correctly under load") {
    // Handler echoes a per-request token so we can verify no cross-talk between vthreads.
    val handler: RawRequest => RawResponse = req => text(200, req.path)
    withServer(handler) { (base, client) =>
      val n    = 200
      val pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
      try
        val tasks = (0 until n).map { i =>
          pool.submit(new java.util.concurrent.Callable[Boolean] {
            def call(): Boolean = get(client, s"$base/item/$i").body() == s"/item/$i"
          })
        }
        assert(tasks.forall(_.get(10, java.util.concurrent.TimeUnit.SECONDS)))
      finally pool.shutdown()
    }
  }

  test("a body over the configured limit is rejected with 400") {
    val server = new FastHttpServer(_ => text(200, "ok"),
      limits = HttpProtocol.Limits(maxBodyBytes = 8), idleTimeoutMs = 5000)
    val port   = server.start(0)
    val client = HttpClient.newBuilder().build()
    try
      val req = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/"))
        .POST(HttpRequest.BodyPublishers.ofString("way too much data")).build()
      val r = client.send(req, HttpResponse.BodyHandlers.ofString())
      assert(r.statusCode() == 400)
    finally server.stop()
  }
