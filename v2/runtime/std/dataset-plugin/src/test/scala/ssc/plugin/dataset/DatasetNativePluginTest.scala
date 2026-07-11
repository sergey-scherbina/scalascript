package ssc.plugin.dataset

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class DatasetNativePluginTest extends AnyFunSuite:
  private def install(): Unit =
    NativePluginHost.installProviders(List(DatasetNativePlugin()))

  private def static(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(s"Dataset.$name").getOrElse(fail(s"missing Dataset.$name"))(args.toList)

  private def call(receiver: Value, name: String, args: Value*): Value = receiver match
    case Value.ForeignV(obj: Value.NamedMethodObj) =>
      obj.getField(name) match
        case Some(fn: Value.ClosV) =>
          val env = if args.isEmpty then fn.env else Runtime.extend(fn.env, args.toArray)
          Runtime.run(fn.code, env)
        case other => fail(s"missing Dataset method $name: $other")
    case other => fail(s"not a Dataset: $other")

  private def function(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def list(values: Range): Value =
    var result: Value = Value.DataV("Nil", Vector.empty)
    values.reverseIterator.foreach(value => result = Value.DataV("Cons", Vector(Value.IntV(value), result)))
    result

  private def ints(value: Value): List[Long] =
    Prims.unlistPub(value).map { case Value.IntV(number) => number; case other => fail(s"not Int: $other") }

  test("lazy transformations and terminals preserve deterministic order") {
    install()
    val base = static("of", Value.IntV(3), Value.IntV(1), Value.IntV(2), Value.IntV(2))
    val mapped = call(base, "map", function(1) { case Value.IntV(n) :: Nil => Value.IntV(n * 2) })
    val filtered = call(mapped, "filter", function(1) { case Value.IntV(n) :: Nil => Value.BoolV(n >= 4) })
    assert(ints(call(filtered, "collect")) == List(6, 4, 4))
    assert(call(filtered, "count") == Value.IntV(3))
    assert(ints(call(filtered, "top", Value.IntV(2))) == List(6, 4))
    assert(ints(call(filtered, "toSet")) == List(6, 4))
  }

  test("grouping and parallel evaluation are stable") {
    install()
    val base = static("fromList", list(1 to 10000))
    val mapped = call(base, "map", function(1) { case Value.IntV(n) :: Nil => Value.IntV(n * n) })
    val parallel = call(mapped, "runParallel")
    assert(call(parallel, "count") == Value.IntV(10000))
    assert(call(parallel, "reduce", function(2) {
      case Value.IntV(left) :: Value.IntV(right) :: Nil => Value.IntV(left + right)
    }) == Value.IntV(333383335000L))

    val grouped = call(static("of", Value.StrV("b"), Value.StrV("a"), Value.StrV("b")),
      "countByValue")
    assert(grouped match
      case Value.MapV(entries) => entries.keys.toList == List(Value.StrV("b"), Value.StrV("a"))
      case _ => false)
  }

  test("100000 element conversion is stack safe") {
    install()
    val dataset = static("fromList", list(1 to 100000))
    assert(call(dataset, "count") == Value.IntV(100000))
    assert(Prims.unlistPub(call(dataset, "collect")).length == 100000)
  }

  test("file IO and Dataset combinators keep the local contract") {
    install()
    val input = Files.createTempFile("ssc-dataset-input", ".txt")
    val output = Files.createTempFile("ssc-dataset-output", ".txt")
    try
      Files.writeString(input, "alpha\nbeta\n", StandardCharsets.UTF_8)
      val lines = static("fromFile", Value.StrV(input.toString))
      assert(Prims.unlistPub(call(lines, "collect")) == List(Value.StrV("alpha"), Value.StrV("beta")))
      assert(call(lines, "saveToFile", Value.StrV(output.toString)) == Value.UnitV)
      assert(Files.readString(output, StandardCharsets.UTF_8) == "alpha\nbeta\n")

      val left = static("of", Value.IntV(1), Value.IntV(2), Value.IntV(2), Value.IntV(3))
      val right = static("of", Value.IntV(2), Value.IntV(4))
      assert(ints(call(call(left, "union", right), "collect")) == List(1, 2, 2, 3, 2, 4))
      assert(ints(call(call(left, "intersect", right), "collect")) == List(2))
      assert(call(left, "sum") == Value.IntV(8))
      assert(call(left, "min") == Value.IntV(1))
      assert(call(left, "max") == Value.IntV(3))
    finally
      Files.deleteIfExists(input)
      Files.deleteIfExists(output)
  }
