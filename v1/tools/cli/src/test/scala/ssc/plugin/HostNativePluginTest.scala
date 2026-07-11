package ssc.plugin

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.host.HostNativePlugin

final class HostNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).getOrElse(fail(s"missing handler $name"))(args.toList)

  private def captured(body: => Unit): String =
    val bytes = new ByteArrayOutputStream()
    val stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)
    try Console.withOut(stream)(body)
    finally stream.close()
    bytes.toString(StandardCharsets.UTF_8)

  test("doc preserves arbitrary values and render shares println display semantics"):
    NativePluginHost.installProviders(List(new HostNativePlugin))
    assert(V2PluginRegistry.lookupGlobal("doc").isEmpty)
    assert(V2PluginRegistry.lookupGlobal("render").isEmpty)
    val list = Value.DataV("Cons", Vector(
      Value.StrV("x"),
      Value.DataV("Cons", Vector(Value.StrV("y"), Value.DataV("Nil", Vector.empty)))))
    val document = call("doc", Value.StrV("alpha"), Value.IntV(2), list)
    assert(document == Value.DataV("NativeDoc", Vector(Value.StrV("alpha"), Value.IntV(2), list)))
    assert(captured { assert(call("render", document) == Value.UnitV) } ==
      "alpha\n2\nList(x, y)\n")

  test("empty documents and ordinary values keep exactly one trailing newline"):
    NativePluginHost.installProviders(List(new HostNativePlugin))
    assert(captured { call("render", call("doc")) } == "\n")
    assert(captured { call("render", Value.StrV("plain")) } == "plain\n")

  test("nested documents flatten recursively without leaking their runtime tag"):
    NativePluginHost.installProviders(List(new HostNativePlugin))
    val nested = call("doc",
      Value.StrV("alpha"),
      call("doc", Value.IntV(2), call("doc"), Value.BoolV(true)),
      call("doc", call("doc", Value.StrV("omega"))))
    assert(captured { assert(call("render", nested) == Value.UnitV) } ==
      "alpha\n2\ntrue\nomega\n")
