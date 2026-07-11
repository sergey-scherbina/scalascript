package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import ssc.{Done, Runtime, Value}

/** Drives the plugin the way the runtime does — through the intrinsics registered in
  * V2PluginRegistry by a real ServiceLoader `install` — to prove the whole bridge (route →
  * serve → engine → dispatch → toResponse → response) works, not just the engine in
  * isolation. The ssc-level end-to-end (path params, query, WebSocket echo via the VM) is
  * validated in the hf-5 CLI `--native` run; this locks the core path into CI. */
class HttpFastVmIntegrationTest extends AnyFunSuite:

  private def freePort(): Int =
    val ss = new ServerSocket(0)
    try ss.getLocalPort finally ss.close()

  test("route + serveAsync serve a request through the registered intrinsics") {
    ssc.plugin.NativePluginHost.loadAll()
    val reg  = ssc.V2PluginRegistry
    val port = freePort()

    // A handler ClosV that ignores its request arg and returns a bare String (→ 200 text).
    val handler = Value.ClosV(Runtime.emptyEnv, 1, _ => Done(Value.StrV("hi there")))
    reg.lookup("route").get(List(Value.StrV("GET"), Value.StrV("/hi"), handler))

    // A handler that READS the request (proves the 9-field Request DataV reaches the handler):
    // fields = (method, path, …); returns a Response DataV echoing the path.
    val echo = Value.ClosV(Runtime.emptyEnv, 1, env =>
      val request = env.last.asInstanceOf[Value.DataV]
      val path    = request.fields(1).asInstanceOf[Value.StrV].s
      Done(Value.DataV("Response", Vector(Value.IntV(200), Value.MapV.empty, Value.StrV("path=" + path)))))
    reg.lookup("route").get(List(Value.StrV("GET"), Value.StrV("/echo/:name"), echo))

    reg.lookup("serveAsync").get(List(Value.IntV(port.toLong)))

    try
      val client = HttpClient.newHttpClient()
      val get = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/hi")).GET().build()
      val r = client.send(get, HttpResponse.BodyHandlers.ofString())
      assert(r.statusCode() == 200, s"status ${r.statusCode()}")
      assert(r.body() == "hi there", s"body '${r.body()}'")

      // the :param route matches + the handler sees the real path
      val echoReq = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/echo/bob")).GET().build()
      assert(client.send(echoReq, HttpResponse.BodyHandlers.ofString()).body() == "path=/echo/bob")

      // an unregistered path 404s through the same bridge
      val miss = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/nope")).GET().build()
      assert(client.send(miss, HttpResponse.BodyHandlers.ofString()).statusCode() == 404)
    finally
      reg.lookup("stop").get(List())
  }

  test("mount serves a static file (binary-safe) with a content-type from the extension") {
    ssc.plugin.NativePluginHost.loadAll()
    val reg  = ssc.V2PluginRegistry
    val port = freePort()
    val dir  = java.nio.file.Files.createTempDirectory("ssc-static")
    // a body with non-UTF-8 bytes proves the static path is binary-safe (not String-mangled)
    val bytes = Array[Byte](0x89.toByte, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF.toByte)
    java.nio.file.Files.write(dir.resolve("logo.png"), bytes)
    reg.lookup("mount").get(List(Value.StrV("/assets"), Value.StrV(dir.toString)))
    reg.lookup("serveAsync").get(List(Value.IntV(port.toLong)))
    try
      val client = HttpClient.newHttpClient()
      val get = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/assets/logo.png")).GET().build()
      val r = client.send(get, HttpResponse.BodyHandlers.ofByteArray())
      assert(r.statusCode() == 200)
      assert(r.headers().firstValue("content-type").get == "image/png")
      assert(r.body().sameElements(bytes), "static file bytes were mangled")
      // a missing static file falls through to 404
      val miss = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/assets/nope.png")).GET().build()
      assert(client.send(miss, HttpResponse.BodyHandlers.ofString()).statusCode() == 404)
    finally
      reg.lookup("stop").get(List())
  }
