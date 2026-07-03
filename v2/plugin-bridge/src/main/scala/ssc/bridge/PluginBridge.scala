package ssc.bridge

import scalascript.backend.spi.{Backend, IntrinsicImpl, NativeImpl, NativeContext}
import scalascript.interpreter.{DataValue, Value as V1Value}
import scalascript.interpreter.DataValue.*
import ssc.{Value as V2Value, V2PluginRegistry}

/** Loads v1 Backend plugins from the classpath and registers their NativeImpl
 *  intrinsics with V2PluginRegistry so the v2 VM can dispatch to them.
 *
 *  Only NativeImpl intrinsics are bridged — InlineCode and RuntimeCall are
 *  compile-time code-gen variants used by the JVM/JS backends, not the VM.
 *
 *  Usage: call PluginBridge.loadAll() before running any v2 program that
 *  needs plugin intrinsics.
 *
 *  Effect-based plugins (block-forms that use runWithHandler / shift-reset)
 *  require deeper integration and are NOT covered here. They surface as
 *  "unimplemented primitive" errors at runtime — a known limitation. */
object PluginBridge:

  /** A minimal NativeContext for stateless intrinsics (IO, hash, math, etc.).
   *  Stateful intrinsics (route registration, DB connections, async, actors)
   *  will fail or no-op — they are not bridgeable without a full v1 runtime. */
  private object MinimalCtx extends NativeContext:
    def out: java.io.PrintStream = Console.out
    def err: java.io.PrintStream = Console.err

  /** Load all Backend implementations available on the classpath via
   *  ServiceLoader, extract NativeImpl intrinsics, and register them
   *  with V2PluginRegistry. Returns the number of ops registered. */
  def loadAll(): Int =
    var count = 0
    val loader = java.util.ServiceLoader.load(classOf[Backend])
    val it = loader.iterator()
    while it.hasNext do
      scala.util.Try(it.next()).foreach { backend =>
        backend.intrinsics.foreach { case (qn, impl) =>
          val op = qn.toString
          impl match
            case NativeImpl(eval) =>
              V2PluginRegistry.register(op, args => {
                val v1Args: List[Any] = args.map(v2ToV1)
                val v1Result: Any = eval(MinimalCtx, v1Args)
                v1ToV2(v1Result)
              })
              count += 1
            case _ => // InlineCode / RuntimeCall: compile-time only, skip
        }
      }
    count

  /** Load intrinsics from a specific Backend instance (e.g., for testing). */
  def loadBackend(backend: Backend): Int =
    var count = 0
    backend.intrinsics.foreach { case (qn, impl) =>
      val op = qn.toString
      impl match
        case NativeImpl(eval) =>
          V2PluginRegistry.register(op, args => {
            val v1Args: List[Any] = args.map(v2ToV1)
            val v1Result: Any = eval(MinimalCtx, v1Args)
            v1ToV2(v1Result)
          })
          count += 1
        case _ => // compile-time variants; not bridgeable
    }
    count

  // ── Value translation: v2 Value → v1 Value ─────────────────────────────

  /** Convert a v2 VM value to a v1 interpreter Value (for plugin arguments). */
  def v2ToV1(v: V2Value): V1Value = v match
    case V2Value.UnitV        => DataValue.UnitV
    case V2Value.BoolV(b)     => DataValue.BoolV(b)
    case V2Value.IntV(n)      => DataValue.IntV(n)
    case V2Value.FloatV(d)    => DataValue.DoubleV(d)
    case V2Value.StrV(s)      => DataValue.StringV(s)
    case V2Value.BigV(n)      => DataValue.BigIntV(n)
    case V2Value.BytesV(bs)   =>
      // Bytes: v1 has no BytesV; wrap as a list of IntV bytes
      val items = bs.toList.map(b => DataValue.IntV((b & 0xff).toLong): V1Value)
      scalascript.interpreter.Value.ListV(items)
    case V2Value.DataV(tag, fields) =>
      // Positional fields: expose as _0, _1, … for v1 InstanceV
      val fieldMap: Map[String, V1Value] = fields.zipWithIndex
        .map { case (fv, i) => s"_$i" -> v2ToV1(fv) }
        .toMap
      val inst = scalascript.interpreter.Value.InstanceV(tag, fieldMap)
      // Also populate positional arrays for v1 fast paths
      val arr: Array[V1Value] = fields.map(v2ToV1).toArray
      val names: Array[String] = fields.indices.map(i => s"_$i").toArray
      inst.fieldsArr = arr
      inst.fieldNames = names
      inst
    case V2Value.ForeignV(h) =>
      scalascript.interpreter.Value.Foreign(h.getClass.getSimpleName, h)
    case _ =>
      // Closures, LongCellV: not translatable — wrap as opaque Foreign
      scalascript.interpreter.Value.Foreign("v2Value", v.asInstanceOf[AnyRef])

  // ── Value translation: v1 Value → v2 Value ─────────────────────────────

  /** Convert a v1 interpreter Value to a v2 VM value (for plugin return values). */
  def v1ToV2(v: Any): V2Value = v match
    case DataValue.UnitV        => V2Value.UnitV
    case DataValue.BoolV(b)     => V2Value.BoolV(b)
    case DataValue.IntV(n)      => V2Value.IntV(n)
    case DataValue.DoubleV(d)   => V2Value.FloatV(d)
    case DataValue.StringV(s)   => V2Value.StrV(s)
    case DataValue.BigIntV(n)   => V2Value.BigV(n)
    case DataValue.DecimalV(d)  => V2Value.FloatV(d.toDouble)
    case DataValue.CharV(c)     => V2Value.StrV(c.toString)
    case DataValue.NullV        => V2Value.DataV("None", Vector.empty) // closest v2 equivalent
    case scalascript.interpreter.Value.InstanceV(tag, _) =>
      // Prefer positional fieldsArr if available (StatRuntime fast path)
      val inst = v.asInstanceOf[scalascript.interpreter.Value.InstanceV]
      val arr = inst.fieldsArr
      if arr != null then
        V2Value.DataV(tag, arr.toVector.map(v1ToV2))
      else
        V2Value.DataV(tag, inst.effectiveFields.values.toVector.map(v1ToV2))
    case scalascript.interpreter.Value.ListV(items) =>
      // Encode as a Cons/Nil chain (v2 list encoding)
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.VectorV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.OptionV(null) =>
      V2Value.DataV("None", Vector.empty)
    case scalascript.interpreter.Value.OptionV(inner) =>
      V2Value.DataV("Some", Vector(v1ToV2(inner)))
    case scalascript.interpreter.Value.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(v1ToV2).toVector)
    case scalascript.interpreter.Value.Foreign(_, h: AnyRef) =>
      V2Value.ForeignV(h)
    case _ =>
      // Closures and other complex v1 values: wrap in ForeignV
      V2Value.ForeignV(v.asInstanceOf[AnyRef])
