package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

class RegistryHttpServerTest extends AnyFunSuite:

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("HTTP round-trip: POST publish → GET packages.yaml → GET artifact (drop-in for RegistryClient)"):
    val root   = os.temp.dir(prefix = "ssc-reg-http-")
    val reg    = FileRegistry(root)
    val client = HttpClient.newHttpClient()
    val server = RegistryHttpServer(reg)            // baseUrl auto-derived from the bound port
    val port2  = server.start(0)
    val base2  = s"http://127.0.0.1:$port2"
    try
      // POST publish
      val pub = client.send(
        HttpRequest.newBuilder(URI.create(s"$base2/publish/org.example.foo/1.0.0?description=Foo%20plugin"))
          .POST(HttpRequest.BodyPublishers.ofByteArray(bytes("FOO-PKG"))).build(),
        HttpResponse.BodyHandlers.ofString())
      assert(pub.statusCode() == 200, pub.body())
      assert(pub.body().contains("org.example.foo@1.0.0"))

      // GET packages.yaml — the existing client format; parses + resolves
      val idx = client.send(
        HttpRequest.newBuilder(URI.create(s"$base2/packages.yaml")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(idx.statusCode() == 200)
      assert(idx.body().contains("org.example.foo"))
      assert(idx.body().contains(s"$base2/packages/org.example.foo/1.0.0.sscpkg"))

      // GET the artifact — bytes round-trip
      val art = client.send(
        HttpRequest.newBuilder(URI.create(s"$base2/packages/org.example.foo/1.0.0.sscpkg")).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray())
      assert(art.statusCode() == 200)
      assert(art.body().toSeq == bytes("FOO-PKG").toSeq)

      // 404 for an unknown artifact
      val miss = client.send(
        HttpRequest.newBuilder(URI.create(s"$base2/packages/nope/9.9.9.sscpkg")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(miss.statusCode() == 404)
    finally server.stop()
