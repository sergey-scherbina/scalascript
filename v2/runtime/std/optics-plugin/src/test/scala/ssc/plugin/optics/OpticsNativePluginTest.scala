package ssc.plugin.optics

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class OpticsNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(OpticsNativePlugin()))
    V2PluginRegistry.registerFieldNames("Address", Vector("street", "city"))
    V2PluginRegistry.registerFieldNames("Person", Vector("name", "address"))

  private def list(values: Value*): Value =
    values.reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def call(receiver: Value, name: String, args: Value*): Value = receiver match
    case Value.ForeignV(obj: Value.NamedMethodObj) => obj.getField(name) match
      case Some(fn: Value.ClosV) =>
        Runtime.run(fn.code, Runtime.extend(fn.env, args.toArray))
      case other => fail(s"missing optic method $name: $other")
    case other => fail(s"not an optic: $other")

  private def callback(fn: Value => Value): Value =
    Value.ClosV(Runtime.emptyEnv, 1, env => Done(fn(env(0))))

  test("field, optional, traversal, composition, and prism stay structural") {
    install()
    val field = (name: String) => Value.DataV("OField", Vector(Value.StrV(name)))
    def focus(steps: Value*): Value =
      V2PluginRegistry.lookup("optics.focus").get.apply(List(list(steps*)))
    val person = Value.DataV("Person", Vector(Value.StrV("Alice"),
      Value.DataV("Address", Vector(Value.StrV("Main"), Value.StrV("Boston")))))

    val city = focus(field("address"), field("city"))
    assert(call(city, "get", person) == Value.StrV("Boston"))
    assert(call(city, "set", person, Value.StrV("Paris")) ==
      Value.DataV("Person", Vector(Value.StrV("Alice"),
        Value.DataV("Address", Vector(Value.StrV("Main"), Value.StrV("Paris"))))))

    val optional = focus(Value.DataV("OSome", Vector.empty), field("city"))
    assert(call(optional, "getOption", Value.DataV("None", Vector.empty)) ==
      Value.DataV("None", Vector.empty))

    val traversal = focus(Value.DataV("OEach", Vector.empty))
    assert(Prims.unlistPub(call(traversal, "modify", list(Value.IntV(1), Value.IntV(2)),
      callback { case Value.IntV(n) => Value.IntV(n + 1) })) ==
      List(Value.IntV(2), Value.IntV(3)))

    val address = focus(field("address"))
    val innerCity = focus(field("city"))
    assert(call(call(address, "andThen", innerCity), "get", person) == Value.StrV("Boston"))

    val prism = V2PluginRegistry.lookup("optics.prism").get(List(Value.StrV("Circle")))
    val circle = Value.DataV("Circle", Vector(Value.IntV(5)))
    val rect = Value.DataV("Rect", Vector(Value.IntV(3), Value.IntV(4)))
    assert(call(prism, "getOption", circle) == Value.DataV("Some", Vector(circle)))
    assert(call(prism, "modify", rect, callback(value => value)) == rect)
  }
