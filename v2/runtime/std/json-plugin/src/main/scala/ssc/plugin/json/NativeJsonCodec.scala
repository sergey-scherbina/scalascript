package ssc.plugin.json

import ssc.Value

/** Shared JSON view used by standard-tier providers without a registry back-edge. */
private[plugin] trait NativeJsonValue:
  def inner: ujson.Value

/** Core-free `ssc.Value` JSON encoder shared by JSON and HTTP providers. */
private[plugin] object NativeJsonCodec:
  def stringify(value: Value): String = ujson.write(toJson(value))

  def toJson(value: Value): ujson.Value = value match
    case Value.UnitV => ujson.Null
    case Value.BoolV(boolean) => ujson.Bool(boolean)
    case Value.IntV(number) => ujson.Num(number.toDouble)
    case Value.BigV(number) => ujson.Num(number.toDouble)
    case Value.FloatV(number) => ujson.Num(number)
    case Value.StrV(text) => ujson.Str(text)
    case Value.BytesV(bytes) => ujson.Arr.from(bytes.map(byte => ujson.Num((byte & 0xff).toDouble)))
    case Value.DataV("Nil", _) => ujson.Arr()
    case cons @ Value.DataV("Cons", _) => ujson.Arr.from(unlist(cons).map(toJson))
    case Value.DataV("None", _) => ujson.Null
    case Value.DataV("Some", Seq(inner)) => toJson(inner)
    case Value.DataV(tag, fields) if tag.startsWith("Tuple") => ujson.Arr.from(fields.map(toJson))
    case Value.DataV(tag, fields) => ujson.Obj(
      "tag" -> ujson.Str(tag),
      "fields" -> ujson.Arr.from(fields.map(toJson)))
    case Value.ForeignV(box: NativeJsonValue) => box.inner
    case Value.ForeignV(decimal: java.math.BigDecimal) => ujson.Num(decimal.doubleValue())
    case Value.ForeignV(map: collection.Map[?, ?]) =>
      val entries = map.iterator.map {
        case (Value.StrV(key), item: Value) => key -> toJson(item)
        case (key: String, item: Value) => key -> toJson(item)
        case (key, item: Value) => key.toString -> toJson(item)
        case (key, item) => key.toString -> ujson.Str(item.toString)
      }.toList.sortBy(_._1)
      ujson.Obj.from(entries)
    case _: Value.ClosV => ujson.Str("<function>")
    case Value.ForeignV(other) => ujson.Str(other.toString)
    case cell: Value.LongCellV => ujson.Num(cell.v.toDouble)

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
        case _ => throw new RuntimeException("jsonStringify expected a valid List value")
    out.toList
