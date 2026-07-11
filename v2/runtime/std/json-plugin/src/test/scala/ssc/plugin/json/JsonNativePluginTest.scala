package ssc.plugin.json

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class JsonNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def method(receiver: Value, name: String, args: Value*): Value =
    Prims.methodOp(name, receiver, args.toList)

  private def install(): Unit =
    NativePluginHost.installProviders(List(JsonNativePlugin()))
    val renderer = Value.ClosV(Runtime.emptyEnv, 1, _ => Done(Value.StrV("SELF-HOSTED")))
    call("__jsonCoreInstallRenderer", renderer)

  test("JsonValue bridge navigates portable JsonCore values totally") {
    install()
    val source = Value.MapV.from(List(
      Value.StrV("name") -> Value.StrV("Ada"),
      Value.StrV("items") -> Value.DataV("Cons", Vector(Value.IntV(1),
        Value.DataV("Cons", Vector(Value.IntV(2), Value.DataV("Nil", Vector.empty))))),
      Value.StrV("amount") -> Value.DecimalV("1000.0100"),
      Value.StrV("on") -> Value.BoolV(true)))
    val root = call("__jsonCoreWrap", NativeJsonCodec.toCore(source))

    assert(method(method(root, "get", Value.StrV("name")), "asString") == Value.StrV("Ada"))
    val items = method(root, "get", Value.StrV("items"))
    assert(method(method(items, "at", Value.IntV(1)), "asInt") == Value.IntV(2))
    assert(method(method(root, "get", Value.StrV("missing")), "isNull") == Value.BoolV(true))
    assert(method(method(root, "get", Value.StrV("amount")), "asDecimal") ==
      Value.DecimalV("1000.0100"))
    assert(method(method(root, "get", Value.StrV("on")), "asBool") == Value.BoolV(true))
    assert(method(root, "size") == Value.IntV(4))
    assert(method(root, "raw") == Value.StrV("SELF-HOSTED"))
  }

  test("strict result bridge rejects JsonCoreErr and converts JsonCoreOk") {
    install()
    val ok = Value.DataV("JsonCoreOk", Vector(
      NativeJsonCodec.toCore(Value.StrV("hello")), Value.IntV(7)))
    assert(call("__jsonCoreRawStrict", ok) == Value.StrV("hello"))

    val error = Value.DataV("JsonCoreErr", Vector(Value.StrV("unexpected token"), Value.IntV(3)))
    val thrown = intercept[RuntimeException](call("__jsonCoreRawStrict", error))
    assert(thrown.getMessage == "invalid JSON at 3: unexpected token")
  }

  test("runtime encoding and HTTP reuse invoke the installed self-hosted renderer") {
    install()
    val values = Value.DataV("Cons", Vector(Value.IntV(1),
      Value.DataV("Cons", Vector(Value.StrV("two"), Value.DataV("Nil", Vector.empty)))))
    val core = call("__jsonCoreEncodeValue", values)
    assert(core.isInstanceOf[Value.DataV])
    assert(NativeJsonCodec.stringify(values) == "SELF-HOSTED")

    val parsedRaw = NativeJsonCodec.toRaw(core)
    assert(call("lookup", parsedRaw, Value.IntV(1)) == Value.StrV("two"))
    assert(call("lookupOpt", parsedRaw, Value.IntV(9)) == Value.DataV("None", Vector.empty))
  }
