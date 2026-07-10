package ssc.plugin.json

import ssc.Value
import ssc.plugin.NativePluginContext

/** Shared portable JSON view used by standard-tier providers. */
private[plugin] trait NativeJsonValue:
  def core: Value

/**
 * Host boundary for ordinary runtime values. Parsing and rendering are owned by
 * `std/json-core.ssc`; this bridge only maps `ssc.Value` to/from its portable
 * `JsonCore*` ADTs and invokes the installed self-hosted renderer.
 */
private[plugin] object NativeJsonCodec:
  private val NilValue = Value.DataV("Nil", Vector.empty)
  private val NullCore = Value.DataV("JsonCoreNull", Vector.empty)

  @volatile private var renderer: Option[(NativePluginContext, Value)] = None

  def resetRenderer(): Unit = renderer = None

  def nullCore: Value = NullCore

  def installRenderer(context: NativePluginContext, fn: Value): Unit =
    renderer = Some(context -> fn)

  def stringify(value: Value): String = renderCore(toCore(value))

  def renderCore(core: Value): String = renderer match
    case Some((context, fn)) => context.invoke(fn, List(core)) match
      case Value.StrV(text) => text
      case other => throw new RuntimeException(s"self-hosted JSON renderer returned $other")
    case None =>
      throw new RuntimeException(
        "self-hosted JSON renderer is not installed; import std/json.ssc")

  def toCore(value: Value): Value = value match
    case Value.ForeignV(box: NativeJsonValue) => box.core
    case core @ Value.DataV(tag, _) if tag.startsWith("JsonCore") => core
    case Value.UnitV => NullCore
    case Value.BoolV(boolean) => Value.DataV("JsonCoreBool", Vector(Value.BoolV(boolean)))
    case Value.IntV(number) => numberCore(number.toString)
    case Value.BigV(number) => numberCore(number.toString)
    case Value.DecimalV(text) => numberCore(text)
    case Value.FloatV(number) =>
      if java.lang.Double.isFinite(number) then numberCore(java.lang.Double.toString(number))
      else throw new RuntimeException("jsonStringify cannot encode NaN or Infinity")
    case Value.StrV(text) => stringCore(text)
    case Value.BytesV(bytes) =>
      arrayCore(bytes.iterator.map(byte => numberCore((byte & 0xff).toString)).toList)
    case Value.DataV("Nil", _) => arrayCore(Nil)
    case cons @ Value.DataV("Cons", _) => arrayCore(unlist(cons).map(toCore))
    case Value.DataV("None", _) => NullCore
    case Value.DataV("Some", Seq(inner)) => toCore(inner)
    case Value.DataV(tag, fields) if tag.startsWith("Tuple") => arrayCore(fields.map(toCore))
    case Value.DataV(tag, fields) => objectCore(List(
      "tag" -> stringCore(tag),
      "fields" -> arrayCore(fields.map(toCore))))
    case Value.ForeignV(decimal: java.math.BigDecimal) => numberCore(decimal.toPlainString)
    case Value.ForeignV(map: collection.Map[?, ?]) =>
      val entries = map.iterator.map {
        case (Value.StrV(key), item: Value) => key -> toCore(item)
        case (key: String, item: Value) => key -> toCore(item)
        case (key, item: Value) => key.toString -> toCore(item)
        case (key, item) => key.toString -> stringCore(item.toString)
      }.toList.sortBy(_._1)
      objectCore(entries)
    case _: Value.ClosV => stringCore("<function>")
    case Value.ForeignV(other) => stringCore(other.toString)
    case cell: Value.LongCellV => numberCore(cell.v.toString)

  def unwrapStrict(result: Value): Value = result match
    case Value.DataV("JsonCoreOk", Seq(value, _)) => value
    case Value.DataV("JsonCoreErr", Seq(Value.StrV(message), Value.IntV(offset))) =>
      throw new RuntimeException(s"invalid JSON at $offset: $message")
    case _ => throw new RuntimeException("invalid self-hosted JSON parser result")

  def toRaw(core: Value): Value = core match
    case Value.DataV("JsonCoreNull", _) => Value.UnitV
    case Value.DataV("JsonCoreBool", Seq(Value.BoolV(boolean))) => Value.BoolV(boolean)
    case Value.DataV("JsonCoreNumber", Seq(Value.StrV(raw))) => rawNumber(raw)
    case Value.DataV("JsonCoreString", Seq(codeUnits)) => Value.StrV(decodeCodeUnits(codeUnits))
    case Value.DataV("JsonCoreArray", Seq(items)) => list(unlist(items).map(toRaw))
    case Value.DataV("JsonCoreObject", Seq(fields)) =>
      val values = collection.mutable.LinkedHashMap.empty[Value, Value]
      unlist(fields).foreach {
        case Value.DataV("JsonCoreField", Seq(key, item)) =>
          values(Value.StrV(decodeCodeUnits(key))) = toRaw(item)
        case _ => ()
      }
      Value.ForeignV(values)
    case Value.DataV("JsonCoreOk", _) => toRaw(unwrapStrict(core))
    case Value.DataV("JsonCoreErr", _) => toRaw(unwrapStrict(core))
    case _ => Value.UnitV

  def isNull(core: Value): Boolean = core match
    case Value.DataV("JsonCoreNull", _) => true
    case _ => false

  def stringValue(core: Value): Option[String] = core match
    case Value.DataV("JsonCoreString", Seq(codeUnits)) => Some(decodeCodeUnits(codeUnits))
    case _ => None

  def numberText(core: Value): Option[String] = core match
    case Value.DataV("JsonCoreNumber", Seq(Value.StrV(raw))) => Some(raw)
    case _ => None

  def boolValue(core: Value): Option[Boolean] = core match
    case Value.DataV("JsonCoreBool", Seq(Value.BoolV(boolean))) => Some(boolean)
    case _ => None

  def arrayValues(core: Value): List[Value] = core match
    case Value.DataV("JsonCoreArray", Seq(items)) => unlist(items)
    case _ => Nil

  def objectValues(core: Value): List[(String, Value)] = core match
    case Value.DataV("JsonCoreObject", Seq(fields)) => unlist(fields).flatMap {
      case Value.DataV("JsonCoreField", Seq(key, item)) =>
        Some(decodeCodeUnits(key) -> item)
      case _ => None
    }
    case _ => Nil

  def stringCore(text: String): Value =
    Value.DataV("JsonCoreString", Vector(list(text.iterator.map(c => Value.IntV(c.toLong)).toList)))

  private def numberCore(raw: String): Value =
    Value.DataV("JsonCoreNumber", Vector(Value.StrV(raw)))

  private def arrayCore(values: IterableOnce[Value]): Value =
    Value.DataV("JsonCoreArray", Vector(list(values)))

  private def objectCore(entries: IterableOnce[(String, Value)]): Value =
    val fields = entries.iterator.map { case (key, value) =>
      Value.DataV("JsonCoreField", Vector(codeUnits(key), value))
    }.toList
    Value.DataV("JsonCoreObject", Vector(list(fields)))

  private def codeUnits(text: String): Value =
    list(text.iterator.map(c => Value.IntV(c.toLong)).toList)

  private def decodeCodeUnits(value: Value): String =
    unlist(value).iterator.map {
      case Value.IntV(unit) if unit >= 0 && unit <= 65535 => unit.toChar
      case _ => throw new RuntimeException("invalid JsonCore string code unit")
    }.mkString

  private def rawNumber(raw: String): Value =
    if !raw.exists(c => c == '.' || c == 'e' || c == 'E') then
      try Value.IntV(raw.toLong)
      catch case _: NumberFormatException => Value.BigV(BigInt(raw))
    else
      try Value.DecimalV(raw)
      catch case _: RuntimeException => Value.DecimalV("0.0")

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](NilValue) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def unlist(value: Value): List[Value] =
    val out = collection.mutable.ListBuffer.empty[Value]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(head, tail)) =>
          out += head
          current = tail
        case Value.DataV("Nil", _) => done = true
        case _ => throw new RuntimeException("invalid ScalaScript List value")
    out.toList
