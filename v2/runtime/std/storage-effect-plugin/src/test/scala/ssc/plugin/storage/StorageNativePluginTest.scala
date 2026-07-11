package ssc.plugin.storage

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, Runtime, V2EffectContext, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class StorageNativePluginTest extends AnyFunSuite:
  private def invoke(fn: Value, args: Value*): Value = fn match
    case closure: Value.ClosV =>
      val env = if args.isEmpty then closure.env else Runtime.extend(closure.env, args.toArray)
      Runtime.run(closure.code, env)
    case _ => fail("value is not callable")

  private def storage = V2EffectContext.peek("Storage").get

  test("ephemeral storage is ordered, isolated, and restores nested handlers"):
    NativePluginHost.installProviders(List(StorageNativePlugin()))
    val runner = V2PluginRegistry.lookupGlobal("runEphemeralStorage").get
    val inner = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      assert(storage("keys", Nil) == Value.DataV("Nil", Vector.empty))
      storage("put", List(Value.StrV("inner"), Value.StrV("value")))
      Done(storage("keys", Nil)))
    val outer = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      storage("put", List(Value.StrV("one"), Value.StrV("1")))
      storage("put", List(Value.StrV("two"), Value.StrV("2")))
      storage("put", List(Value.StrV("one"), Value.StrV("updated")))
      val nested = invoke(runner, inner)
      assert(storage("get", List(Value.StrV("one"))) ==
        Value.DataV("Some", Vector(Value.StrV("updated"))))
      storage("remove", List(Value.StrV("two")))
      Done(Value.DataV("Pair", Vector(nested, storage("keys", Nil)))))

    val result = invoke(runner, outer)
    assert(result == Value.DataV("Pair", Vector(
      Value.DataV("Cons", Vector(Value.StrV("inner"), Value.DataV("Nil", Vector.empty))),
      Value.DataV("Cons", Vector(Value.StrV("one"), Value.DataV("Nil", Vector.empty))))))
    assert(V2EffectContext.peek("Storage").isEmpty)

  test("file-backed storage escapes, flushes, and reloads deterministic JSON"):
    NativePluginHost.installProviders(List(StorageNativePlugin()))
    val runner = V2PluginRegistry.lookupGlobal("runStorage").get
    val file = Files.createTempDirectory("ssc-native-storage-").resolve("nested/store.json")
    val writer = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      storage("put", List(Value.StrV("quote\"key"), Value.StrV("line\none")))
      storage("put", List(Value.StrV("plain"), Value.IntV(7)))
      Done(Value.UnitV))
    invoke(runner, writer, Value.StrV(file.toString))
    assert(Files.readString(file) == "{\"quote\\\"key\":\"line\\none\",\"plain\":\"7\"}")

    val reader = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      Done(Value.DataV("Pair", Vector(
        storage("get", List(Value.StrV("quote\"key"))),
        storage("keys", Nil)))))
    val result = invoke(runner, reader, Value.StrV(file.toString))
    assert(result == Value.DataV("Pair", Vector(
      Value.DataV("Some", Vector(Value.StrV("line\none"))),
      Value.DataV("Cons", Vector(Value.StrV("quote\"key"),
        Value.DataV("Cons", Vector(Value.StrV("plain"), Value.DataV("Nil", Vector.empty))))))))

  test("malformed persisted JSON fails explicitly"):
    NativePluginHost.installProviders(List(StorageNativePlugin()))
    val runner = V2PluginRegistry.lookupGlobal("runStorage").get
    val file = Files.createTempFile("ssc-native-storage-bad-", ".json")
    Files.writeString(file, "{\"key\":42}")
    val body = Value.ClosV(Runtime.emptyEnv, 0, _ => Done(Value.UnitV))
    val error = intercept[RuntimeException](invoke(runner, body, Value.StrV(file.toString)))
    assert(error.getMessage.startsWith("Storage JSON:"))
