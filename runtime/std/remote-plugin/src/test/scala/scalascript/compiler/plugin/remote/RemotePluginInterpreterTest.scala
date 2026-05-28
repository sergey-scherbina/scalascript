package scalascript.compiler.plugin.remote

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value, ValueSerializer}
import scalascript.parser.Parser

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
