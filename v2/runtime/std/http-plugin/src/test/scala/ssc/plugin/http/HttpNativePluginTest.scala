package ssc.plugin.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost
import ssc.plugin.json.JsonNativePlugin

class HttpNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def fields(value: Value): IndexedSeq[Value] = value match
    case Value.DataV("Response", result) => result
    case other => fail(s"expected Response, got $other")

  private def headerMap(value: Value): collection.Map[Value, Value] = value match
    case Value.ForeignV(map: collection.Map[?, ?]) => map.asInstanceOf[collection.Map[Value, Value]]
    case other => fail(s"expected header Map, got $other")

  test("outbound GET and POST use the JDK client and return v2 Response data") {
    NativePluginHost.installProviders(List(HttpNativePlugin()))
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/echo", exchange =>
      val requestBody = String(exchange.getRequestBody.readAllBytes(), UTF_8)
      val body = s"${exchange.getRequestMethod}:$requestBody"
      exchange.getResponseHeaders.add("X-Fixture", "native-http")
      exchange.sendResponseHeaders(202, body.getBytes(UTF_8).length)
      exchange.getResponseBody.write(body.getBytes(UTF_8))
      exchange.close())
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}/echo"
    try
      val get = fields(call("httpGet", Value.StrV(url)))
      assert(get(0) == Value.IntV(202))
      assert(get(2) == Value.StrV("GET:"))
      assert(headerMap(get(1)).get(Value.StrV("x-fixture")).contains(Value.StrV("native-http")))

      val requestHeaders = collection.mutable.LinkedHashMap[Value, Value](
        Value.StrV("Content-Type") -> Value.StrV("text/plain"))
      val post = fields(call("httpPost", Value.StrV(url), Value.StrV("hello"), Value.ForeignV(requestHeaders)))
      assert(post(0) == Value.IntV(202))
      assert(post(2) == Value.StrV("POST:hello"))
    finally server.stop(0)
  }

  test("Response builders reuse native JSON and cache helpers preserve fields") {
    NativePluginHost.installProviders(List(JsonNativePlugin(), HttpNativePlugin()))
    val renderer = Value.ClosV(Runtime.emptyEnv, 1, _ =>
      Done(Value.StrV("{\"n\":2,\"ok\":true}")))
    call("__jsonCoreInstallRenderer", renderer)
    val values = collection.mutable.LinkedHashMap[Value, Value](
      Value.StrV("ok") -> Value.BoolV(true), Value.StrV("n") -> Value.IntV(2))
    val json = fields(call("Response.json", Value.ForeignV(values)))
    assert(json(0) == Value.IntV(200))
    assert(json(2) == Value.StrV("{\"n\":2,\"ok\":true}"))

    val text = call("Response.text", Value.StrV("hello"), Value.IntV(201))
    val cached = fields(call("cacheable", text, Value.IntV(60), Value.StrV("v1")))
    assert(cached(0) == Value.IntV(201))
    assert(cached(2) == Value.StrV("hello"))
    assert(headerMap(cached(1))(Value.StrV("Cache-Control")) == Value.StrV("public, max-age=60"))
    assert(headerMap(cached(1))(Value.StrV("ETag")) == Value.StrV("v1"))
  }

  test("server operations fail explicitly instead of entering the compatibility bridge") {
    NativePluginHost.installProviders(List(HttpNativePlugin()))
    val error = intercept[RuntimeException](call("useGzip"))
    assert(error.getMessage ==
      "native HTTP server unavailable: useGzip requires the standard server-host SPI")
  }

  test("route, serveAsync, callback invocation, and stop run on the JDK server host") {
    NativePluginHost.installProviders(List(HttpNativePlugin()))
    val socket = ServerSocket(0)
    val port = try socket.getLocalPort finally socket.close()
    val handler = Value.ClosV(ssc.Runtime.emptyEnv, 1, env =>
      val request = env.last.asInstanceOf[Value.DataV]
      ssc.Done(Value.DataV("Response", Vector(
        Value.IntV(203),
        Value.ForeignV(collection.mutable.LinkedHashMap.empty[Value, Value]),
        Value.StrV("pong:" + request.fields(1).asInstanceOf[Value.StrV].s)))))
    val register = call("route", Value.StrV("GET"), Value.StrV("/ping")).asInstanceOf[Value.ClosV]
    ssc.Runtime.run(register.code, Array(handler))
    call("serveAsync", Value.IntV(port))
    try
      val response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/ping")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(response.statusCode() == 203)
      assert(response.body() == "pong:/ping")
    finally call("stop")
  }
