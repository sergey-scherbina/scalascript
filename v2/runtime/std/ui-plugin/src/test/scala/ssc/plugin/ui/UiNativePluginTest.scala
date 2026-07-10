package ssc.plugin.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class UiNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def invoke(fn: Value, args: Value*): Value = fn match
    case closure: Value.ClosV =>
      val env = if args.isEmpty then closure.env else Runtime.extend(closure.env, args.toArray)
      Runtime.run(closure.code, env)
    case Value.ForeignV(named: Value.NamedMethodObj) => invoke(named.getField("apply").get, args*)
    case _ => fail("value is not callable")

  private def method(value: Value, name: String): Value = value match
    case Value.ForeignV(named: Value.NamedMethodObj) => named.getField(name).get
    case _ => fail("value has no named methods")

  private def list(values: Value*): Value =
    values.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def map(entries: (String, Value)*): Value =
    val result = mutable.LinkedHashMap.empty[Value, Value]
    entries.foreach { case (key, value) => result(Value.StrV(key)) = value }
    Value.ForeignV(result)

  test("mutable and derived signals use the native callback boundary"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val count = call("signal", Value.StrV("count"), Value.IntV(1))
    assert(invoke(count) == Value.IntV(1))
    invoke(method(count, "set"), Value.IntV(2))
    assert(invoke(count) == Value.IntV(2))
    val plusOne = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.IntV(number) => ssc.Done(Value.IntV(number + 1))
      case _ => ssc.Done(Value.UnitV))
    invoke(method(count, "update"), plusOne)
    assert(invoke(count) == Value.IntV(3))
    val equal = call("eqSignal", count, Value.IntV(3))
    assert(invoke(equal) == Value.BoolV(true))

  test("static emit escapes and deterministically renders current signal values"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val name = call("signal", Value.StrV("name"), Value.StrV("Grace"))
    invoke(method(name, "set"), Value.StrV("Ada"))
    val visible = call("signal", Value.StrV("visible"), Value.BoolV(true))
    val heading = call("element",
      Value.StrV("h1"), map(), map(),
      list(call("textNode", Value.StrV("Hi <native>"))))
    val conditional = call("showSignal", visible,
      call("element", Value.StrV("span"), map(), map(), list(call("textNode", Value.StrV("shown")))),
      call("textNode", Value.StrV("hidden")))
    val root = call("element",
      Value.StrV("main"), map("id" -> Value.StrV("app"), "class" -> Value.StrV("card")), map(),
      list(heading, call("signalText", name), conditional))
    val out = Files.createTempDirectory("ssc-native-ui-")

    assert(call("emit", root, Value.StrV(out.toString)) == Value.UnitV)

    val html = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8)
    assert(html ==
      "<!doctype html>\n<main class=\"card\" id=\"app\"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>\n")
