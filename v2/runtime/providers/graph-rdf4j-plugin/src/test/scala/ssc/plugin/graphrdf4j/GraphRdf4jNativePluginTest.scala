package ssc.plugin.graphrdf4j

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, Value}
import ssc.plugin.NativePluginHost

final class GraphRdf4jNativePluginTest extends AnyFunSuite:
  private val sparql = Value.DataV("Sparql", Vector.empty)

  private def call(name: String, args: Value*): Value =
    Prims.methodOp(name, sparql, args.toList)

  test("select and update use the RDF4J SPARQL HTTP protocol"):
    val requests = collection.mutable.ArrayBuffer.empty[(String, String)]
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/repositories/test", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      requests.synchronized(requests += exchange.getRequestHeaders.getFirst("Content-Type") -> body)
      val response =
        if body.contains("SELECT") then
          """{"results":{"bindings":[{"title":{"type":"literal","value":"Moby Dick"}}]}}"""
        else ""
      val bytes = response.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(if response.isEmpty then 204 else 200, bytes.length.toLong)
      if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
      exchange.close())
    server.start()
    try
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/repositories/test"
      NativePluginHost.installProviders(List(GraphRdf4jNativePlugin(key =>
        if key == "RDF4J_URL" then Some(url) else None)))
      val rows = Prims.unlistPub(call("select", Value.StrV("kg"), Value.StrV("SELECT ?title {}")))
      assert(rows.head.asInstanceOf[Value.MapV].entries(Value.StrV("title")) == Value.StrV("Moby Dick"))
      assert(call("update", Value.StrV("kg"), Value.StrV("INSERT DATA {}")) == Value.UnitV)
      assert(requests.map(_._1).toList ==
        List("application/sparql-query", "application/sparql-update"))
    finally server.stop(0)

  test("missing endpoint is an explicit configuration error"):
    NativePluginHost.installProviders(List(GraphRdf4jNativePlugin(_ => None)))
    val error = intercept[IllegalStateException] {
      call("select", Value.StrV("kg"), Value.StrV("SELECT * {}"))
    }
    assert(error.getMessage.contains("requires RDF4J_URL"))
