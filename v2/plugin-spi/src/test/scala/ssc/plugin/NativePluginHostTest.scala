package ssc.plugin

import org.scalatest.funsuite.AnyFunSuite
import ssc.Value

class NativePluginHostTest extends AnyFunSuite:
  private final class Provider(val id: String, name: String, value: Long) extends NativePlugin:
    def install(context: NativePluginContext): Unit =
      context.registerValue(name, Value.IntV(value))

  test("providers install in stable id order") {
    val seen = collection.mutable.ArrayBuffer.empty[String]
    def provider(id0: String) = new NativePlugin:
      def id: String = id0
      def install(context: NativePluginContext): Unit = seen += id0

    assert(NativePluginHost.installProviders(List(provider("z"), provider("a"))) == 2)
    assert(seen.toList == List("a", "z"))
  }

  test("duplicate ownership names both providers") {
    val err = intercept[IllegalStateException] {
      NativePluginHost.installProviders(List(
        Provider("first", "same", 1),
        Provider("second", "same", 2)
      ))
    }
    assert(err.getMessage.contains("first and second"))
    assert(err.getMessage.contains("global 'same'"))
  }

  test("duplicate provider ids fail before installation") {
    val err = intercept[IllegalStateException] {
      NativePluginHost.installProviders(List(
        Provider("duplicate", "one", 1),
        Provider("duplicate", "two", 2)
      ))
    }
    assert(err.getMessage.contains("duplicate native plugin id(s): duplicate"))
  }

  test("provider callbacks run through the host trampoline with bounded errors") {
    var result: Value = Value.UnitV
    val callback = Value.ClosV(ssc.Runtime.emptyEnv, 1, env => ssc.Done(env.last))
    val provider = new NativePlugin:
      def id: String = "callback"
      def install(context: NativePluginContext): Unit =
        result = context.invoke(callback, List(Value.StrV("ok")))
        val error = intercept[IllegalArgumentException](context.invoke(Value.IntV(1), Nil))
        assert(error.getMessage == "native callback value is not callable")

    NativePluginHost.installProviders(List(provider))
    assert(result == Value.StrV("ok"))
  }
