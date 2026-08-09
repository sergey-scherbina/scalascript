package scalascript.compiler.plugin.remote

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value, ValueSerializer}
import scalascript.parser.Parser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class RemotePluginInterpreterTest extends AnyFunSuite:

  private def runModule(source: String): Interpreter =
    scalascript.server.Routes.clear()
    val interp = Interpreter()
    interp.installPlugins(List(RemoteInterpreterPlugin()))
    interp.run(Parser.parse(source))
    interp

  test("remoteHandlers front matter registers in-process Remote.function calls"):
    val interp = runModule(
      """|---
         |remoteHandlers:
         |  demo.echo:
         |    function: echo
         |    request: String
         |    response: String
         |---
         |
         |# Remote
         |
         |```scala
         |def echo(value: String): String = "echo:" + value
         |
         |val fn = remoteFunction("demo.echo")
         |val result = fn.call("hello")
         |```
         |""".stripMargin
    )

    assert(interp.exportedGlobals("result") == Value.StringV("echo:hello"))

  test("remoteTryCall returns typed RemoteCallError when handler is missing"):
    val interp = runModule(
      """|# Remote
         |
         |```scala
         |val result = remoteTryCall("missing.op", "hello")
         |```
         |""".stripMargin
    )

    assert(interp.exportedGlobals("result") ==
      Value.InstanceV("Left", Map("value" -> Value.InstanceV("HandlerNotFound", Map("name" -> Value.StringV("missing.op"))))))

  test("remoteHandlers front matter exposes POST JSON fallback route"):
    val interp = runModule(
      """|---
         |remoteHandlers:
         |  demo.echo:
         |    function: echo
         |    path: /rpc/echo
         |    request: String
         |    response: String
         |---
         |
         |# Remote
         |
         |```scala
         |def echo(value: String): String = "echo:" + value
         |```
         |""".stripMargin
    )

    val Some((entry, _)) = scalascript.server.Routes.matchRequest("POST", "/rpc/echo"): @unchecked
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV("POST"),
      "path"    -> Value.StringV("/rpc/echo"),
      "headers" -> Value.EmptyMap,
      "body"    -> Value.StringV(ValueSerializer.serialize(Value.StringV("wire"))),
      "form"    -> Value.EmptyMap,
      "files"   -> Value.EmptyMap,
      "cookies" -> Value.EmptyMap,
      "session" -> Value.EmptyMap,
      "json"    -> Value.NoneV
    ))
    val response = interp.invoke(entry.handler, List(req))

    assert(response == Value.InstanceV("Response", Map(
      "status" -> Value.intV(200),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("application/scalascript-value+json"))),
      "body" -> Value.StringV(ValueSerializer.serialize(Value.StringV("echo:wire")))
    )))

  test("source @remote annotation and remote def sugar feed the same runtime registry"):
    val interp = runModule(
      """|# Remote
         |
         |```scala
         |@remote(name = "demo.upper", path = "/rpc/upper")
         |def upper(value: String): String = value.toUpperCase
         |
         |remote def localEcho(value: String): String = "local:" + value
         |
         |val upperResult = remoteCall("demo.upper", "hello")
         |val localResult = remoteCall("localEcho", "hello")
         |```
         |""".stripMargin
    )

    assert(interp.exportedGlobals("upperResult") == Value.StringV("HELLO"))
    assert(interp.exportedGlobals("localResult") == Value.StringV("local:hello"))

  test("Remote.http posts ValueSerializer JSON and decodes the response"):
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rpc/echo", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV("echo:" + s)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    try
      val port = server.getAddress.getPort
      val interp = runModule(
        s"""|# Remote
            |
            |```scala
            |val result = remoteHttpFunction("http://127.0.0.1:$port/rpc/echo").call("wire")
            |```
            |""".stripMargin
      )
      assert(interp.exportedGlobals("result") == Value.StringV("echo:wire"))
    finally server.stop(0)

  test("Remote.stub joins base URL and path for HTTP JSON fallback calls"):
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/rpc/echo", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV("stub:" + s)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    try
      val port = server.getAddress.getPort
      val interp = runModule(
        s"""|# Remote
            |
            |```scala
            |val stub = remoteStub[Any]("http://127.0.0.1:$port/api/")
            |val direct = stub.call("/rpc/echo", "wire")
            |val viaFunction = stub.function("rpc/echo").call("fn")
            |```
            |""".stripMargin
      )
      assert(interp.exportedGlobals("direct") == Value.StringV("stub:wire"))
      assert(interp.exportedGlobals("viaFunction") == Value.StringV("stub:fn"))
    finally server.stop(0)

  test("remoteStub[Api] derives trait-shaped stub with per-method HTTP dispatch"):
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/echo", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV("echo:" + s)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    try
      val port = server.getAddress.getPort
      val interp = runModule(
        s"""|# Remote
            |
            |```scala
            |trait EchoApi:
            |  def echo(s: String): String
            |
            |val client = remoteStub[EchoApi]("http://127.0.0.1:$port/")
            |val result = client.echo("hello")
            |```
            |""".stripMargin
      )
      assert(interp.exportedGlobals("result") == Value.StringV("echo:hello"))
    finally server.stop(0)

  test("remoteStub[Api] without matching trait falls back to path-based RemoteStub"):
    val interp = runModule(
      """|# Remote
         |
         |```scala
         |val client = remoteStub[UnknownApi]("http://localhost:9999/")
         |val hasFunction = client != null
         |```
         |""".stripMargin
    )
    assert(interp.exportedGlobals("hasFunction") == Value.boolV(true))

  test("remoteStub[Api] with multiple methods registers each as a NativeFn"):
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/greet", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV("hi:" + s)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.createContext("/upper", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV(s.toUpperCase)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    try
      val port = server.getAddress.getPort
      val interp = runModule(
        s"""|# Remote
            |
            |```scala
            |trait GreetApi:
            |  def greet(name: String): String
            |  def upper(s: String): String
            |
            |val client = remoteStub[GreetApi]("http://127.0.0.1:$port")
            |val hi = client.greet("World")
            |val up = client.upper("hello")
            |```
            |""".stripMargin
      )
      assert(interp.exportedGlobals("hi") == Value.StringV("hi:World"))
      assert(interp.exportedGlobals("up") == Value.StringV("HELLO"))
    finally server.stop(0)

  test("Remote.stub[Api](baseUrl) derives trait-shaped stub same as remoteStub[Api]"):
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/ping", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      val in = ValueSerializer.deserialize(body)
      val out = in match
        case Value.StringV(s) => Value.StringV("pong:" + s)
        case other            => other
      val bytes = ValueSerializer.serialize(out).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/scalascript-value+json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    try
      val port = server.getAddress.getPort
      val interp = runModule(
        s"""|# Remote
            |
            |```scala
            |trait PingApi:
            |  def ping(s: String): String
            |
            |val client = Remote.stub[PingApi]("http://127.0.0.1:$port/")
            |val result = client.ping("test")
            |```
            |""".stripMargin
      )
      assert(interp.exportedGlobals("result") == Value.StringV("pong:test"))
    finally server.stop(0)
