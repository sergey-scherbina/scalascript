package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Value as V2Value, V2PluginRegistry}
import scalascript.interpreter.DataValue
import scalascript.backend.spi.{
  NativeImpl, Backend, IntrinsicImpl, Capabilities, SpiVersionRange, SpiVersion, OutputKind
}
import scalascript.ir.{QualifiedName, NormalizedModule}

class PluginBridgeTest extends AnyFunSuite:

  // ── Value translation: v2 → v1 ─────────────────────────────────────────

  test("v2ToV1: UnitV"):
    assert(PluginBridge.v2ToV1(V2Value.UnitV) == DataValue.UnitV)

  test("v2ToV1: BoolV"):
    assert(PluginBridge.v2ToV1(V2Value.BoolV(true))  == DataValue.BoolV(true))
    assert(PluginBridge.v2ToV1(V2Value.BoolV(false)) == DataValue.BoolV(false))

  test("v2ToV1: IntV"):
    assert(PluginBridge.v2ToV1(V2Value.IntV(42L))  == DataValue.IntV(42L))
    assert(PluginBridge.v2ToV1(V2Value.IntV(-1L))  == DataValue.IntV(-1L))

  test("v2ToV1: FloatV → DoubleV"):
    assert(PluginBridge.v2ToV1(V2Value.FloatV(3.14)) == DataValue.DoubleV(3.14))

  test("v2ToV1: StrV → StringV"):
    assert(PluginBridge.v2ToV1(V2Value.StrV("hello")) == DataValue.StringV("hello"))

  test("v2ToV1: BigV → BigIntV"):
    assert(PluginBridge.v2ToV1(V2Value.BigV(BigInt(999))) == DataValue.BigIntV(BigInt(999)))

  test("v2ToV1: DataV → InstanceV with positional fields"):
    val v2 = V2Value.DataV("Pair", Vector(V2Value.IntV(1L), V2Value.StrV("x")))
    val v1 = PluginBridge.v2ToV1(v2)
    v1 match
      case inst: scalascript.interpreter.Value.InstanceV =>
        assert(inst.typeName == "Pair")
        val arr = inst.fieldsArr
        assert(arr != null)
        assert(arr.toList == List(DataValue.IntV(1L), DataValue.StringV("x")))
      case other => fail(s"expected InstanceV, got $other")

  // ── Value translation: v1 → v2 ─────────────────────────────────────────

  test("v1ToV2: UnitV"):
    assert(PluginBridge.v1ToV2(DataValue.UnitV) == V2Value.UnitV)

  test("v1ToV2: BoolV"):
    assert(PluginBridge.v1ToV2(DataValue.BoolV(true))  == V2Value.BoolV(true))
    assert(PluginBridge.v1ToV2(DataValue.BoolV(false)) == V2Value.BoolV(false))

  test("v1ToV2: IntV"):
    assert(PluginBridge.v1ToV2(DataValue.IntV(99L)) == V2Value.IntV(99L))

  test("v1ToV2: DoubleV → FloatV"):
    assert(PluginBridge.v1ToV2(DataValue.DoubleV(2.718)) == V2Value.FloatV(2.718))

  test("v1ToV2: StringV → StrV"):
    assert(PluginBridge.v1ToV2(DataValue.StringV("world")) == V2Value.StrV("world"))

  test("v1ToV2: BigIntV → BigV"):
    assert(PluginBridge.v1ToV2(DataValue.BigIntV(BigInt(42))) == V2Value.BigV(BigInt(42)))

  test("v1ToV2: CharV → StrV(single char)"):
    assert(PluginBridge.v1ToV2(DataValue.CharV('A')) == V2Value.StrV("A"))

  test("v1ToV2: NullV → None DataV"):
    assert(PluginBridge.v1ToV2(DataValue.NullV) == V2Value.DataV("None", Vector.empty))

  test("v1ToV2: OptionV(null) → None"):
    assert(PluginBridge.v1ToV2(scalascript.interpreter.Value.OptionV(null)) ==
      V2Value.DataV("None", Vector.empty))

  test("v1ToV2: OptionV(inner) → Some"):
    assert(PluginBridge.v1ToV2(scalascript.interpreter.Value.OptionV(DataValue.IntV(7L))) ==
      V2Value.DataV("Some", Vector(V2Value.IntV(7L))))

  test("v1ToV2: ListV → Cons/Nil chain"):
    val v1List = scalascript.interpreter.Value.ListV(
      List(DataValue.IntV(1L), DataValue.IntV(2L), DataValue.IntV(3L))
    )
    val expected =
      V2Value.DataV("Cons", Vector(V2Value.IntV(1L),
        V2Value.DataV("Cons", Vector(V2Value.IntV(2L),
          V2Value.DataV("Cons", Vector(V2Value.IntV(3L),
            V2Value.DataV("Nil", Vector.empty)))))))
    assert(PluginBridge.v1ToV2(v1List) == expected)

  test("v1ToV2: TupleV → DataV Tuple2"):
    val t = scalascript.interpreter.Value.TupleV(List(DataValue.IntV(1L), DataValue.StringV("a")))
    assert(PluginBridge.v1ToV2(t) == V2Value.DataV("Tuple2", Vector(V2Value.IntV(1L), V2Value.StrV("a"))))

  // ── loadBackend: wire a stub backend through the registry ───────────────

  test("loadBackend: NativeImpl registered and callable via V2PluginRegistry"):
    val op = s"test.bridge.add.${java.util.UUID.randomUUID()}" // unique per run
    val stubBackend = new MinimalBackendStub(Map(
      QualifiedName(op) -> NativeImpl { (_, args) =>
        val a = args(0).asInstanceOf[DataValue.IntV].v
        val b = args(1).asInstanceOf[DataValue.IntV].v
        DataValue.IntV(a + b)
      }
    ))
    val count = PluginBridge.loadBackend(stubBackend)
    assert(count == 1)
    val fn = V2PluginRegistry.lookup(op)
    assert(fn.isDefined, s"op '$op' should be registered after loadBackend")
    val result = fn.get(List(V2Value.IntV(3L), V2Value.IntV(4L)))
    assert(result == V2Value.IntV(7L))

  test("loadBackend: returns count of registered NativeImpl ops"):
    val id = java.util.UUID.randomUUID().toString
    val stub = new MinimalBackendStub(Map(
      QualifiedName(s"test.$id.a") -> NativeImpl((_, _) => DataValue.UnitV),
      QualifiedName(s"test.$id.b") -> NativeImpl((_, _) => DataValue.UnitV),
    ))
    assert(PluginBridge.loadBackend(stub) == 2)

  test("loadBackend: string-echo intrinsic round-trips StrV"):
    val op = s"test.echo.${java.util.UUID.randomUUID()}"
    val stub = new MinimalBackendStub(Map(
      QualifiedName(op) -> NativeImpl { (_, args) => args.head }
    ))
    PluginBridge.loadBackend(stub)
    val fn = V2PluginRegistry.lookup(op).get
    assert(fn(List(V2Value.StrV("ping"))) == V2Value.StrV("ping"))

/** Minimal Backend stub for unit-testing PluginBridge. */
private class MinimalBackendStub(val intrinsics: Map[QualifiedName, IntrinsicImpl]) extends Backend:
  import scalascript.backend.spi.{BackendOptions, CompileResult}
  def id: String = "test-stub"
  def displayName: String = "Test Stub"
  def spiVersion: String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    Set.empty, Set(OutputKind.ExecutionResult), Set.empty,
    SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
  )
  def acceptedSources: Set[String] = Set.empty
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Executed("", "", 0)
