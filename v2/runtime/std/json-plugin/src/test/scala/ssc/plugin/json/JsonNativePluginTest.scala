package ssc.plugin.json

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class JsonNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def method(receiver: Value, name: String, args: Value*): Value =
    Prims.methodOp(name, receiver, args.toList)

  test("JsonValue navigation is total and preserves exact string decimals") {
    NativePluginHost.installProviders(List(JsonNativePlugin()))
    val root = call("jsonValue", Value.StrV(
      "{\"name\":\"Ada\",\"items\":[1,2],\"amount\":\"1000.01\",\"on\":true}"))
    assert(method(method(root, "get", Value.StrV("name")), "asString") == Value.StrV("Ada"))
    val items = method(root, "get", Value.StrV("items"))
    assert(method(method(items, "at", Value.IntV(1)), "asInt") == Value.IntV(2))
    assert(method(method(root, "get", Value.StrV("missing")), "isNull") == Value.BoolV(true))
    assert(method(method(root, "get", Value.StrV("amount")), "asDecimal") ==
      Value.ForeignV(new java.math.BigDecimal("1000.01")))
    assert(method(method(root, "get", Value.StrV("on")), "asBool") == Value.BoolV(true))
  }

  test("malformed JsonValue input is Null while strict jsonParse rejects") {
    NativePluginHost.installProviders(List(JsonNativePlugin()))
    val tolerant = call("jsonValue", Value.StrV("not json"))
    assert(method(tolerant, "isNull") == Value.BoolV(true))
    val error = intercept[RuntimeException](call("jsonParse", Value.StrV("not json")))
    assert(error.getMessage.startsWith("invalid JSON:"))
  }

  test("jsonStringify and legacy lookup points share the same value model") {
    NativePluginHost.installProviders(List(JsonNativePlugin()))
    val values = Value.DataV("Cons", Vector(Value.IntV(1),
      Value.DataV("Cons", Vector(Value.StrV("two"), Value.DataV("Nil", Vector.empty)))))
    assert(call("jsonStringify", values) == Value.StrV("[1,\"two\"]"))

    val parsed = call("jsonParse", Value.StrV("{\"k\":42}"))
    assert(call("lookup", parsed, Value.StrV("k")) == Value.IntV(42))
    assert(call("lookupOpt", parsed, Value.StrV("missing")) == Value.DataV("None", Vector.empty))
    val navigable = call("jsonRead", Value.StrV("{\"k\":42}"))
    assert(method(method(navigable, "get", Value.StrV("k")), "raw") == Value.StrV("42"))
  }
