package scalascript.compiler.plugin.json

import scalascript.plugin.api.{PluginValue, PluginError}
import scalascript.plugin.api.PluginValue.*

/** v1 interpreter port of the v2 `ssc.plugin.json.NativeJsonCodec`.
 *
 *  The standard JSON codec is self-hosted: parsing and rendering live in
 *  `runtime/std/json-core.ssc` (the `jsonCoreParse*` / `jsonCoreRender`
 *  functions and the `JsonCore*` case classes) and run as ordinary
 *  interpreted `.ssc` code.  This object is only the host boundary that maps
 *  ordinary interpreter values to/from that portable `JsonCore*` ADT — it does
 *  NOT parse or render JSON itself.
 *
 *  Written entirely against the stable `scalascript-plugin-api` surface
 *  (`PluginValue`), never `scalascript.interpreter.*`, so it satisfies
 *  `StableSpiEnforcementTest`.  Case-class instances are built with
 *  `orderedInstance` (declaration-order `fieldsArr`) so the interpreted
 *  `jsonCoreRender` can positionally destructure the ADT values this produces.
 */
object V1JsonCore:

  // ── JsonCore ADT constructors (names/arities/field-order must match the
  //    case classes in json-core.ssc). ──
  private def nullCore: PluginValue =
    orderedInstance("JsonCoreNull", Seq.empty)
  private def boolCore(b: Boolean): PluginValue =
    orderedInstance("JsonCoreBool", Seq("value" -> PluginValue.bool(b)))
  private def numberCore(raw: String): PluginValue =
    orderedInstance("JsonCoreNumber", Seq("numberText" -> PluginValue.string(raw)))
  private def stringCore(text: String): PluginValue =
    orderedInstance("JsonCoreString", Seq("codeUnits" -> codeUnits(text)))
  private def arrayCore(items: List[PluginValue]): PluginValue =
    orderedInstance("JsonCoreArray", Seq("items" -> PluginValue.list(items)))
  private def objectCore(fields: List[PluginValue]): PluginValue =
    orderedInstance("JsonCoreObject", Seq("fields" -> PluginValue.list(fields)))
  private def fieldCore(key: String, value: PluginValue): PluginValue =
    orderedInstance("JsonCoreField", Seq("key" -> codeUnits(key), "value" -> value))

  /** Encode a UTF-16 string as the `List[Int]` code-unit list json-core uses. */
  private def codeUnits(text: String): PluginValue =
    PluginValue.list(text.iterator.map(c => PluginValue.int(c.toLong)).toList)

  /** Decode a json-core code-unit list back to a `String`. The interpreted
   *  scanner mixes `CharV` (from `String.charAt`) and `IntV` (from `\\uXXXX` /
   *  escape decoding) in the same list, so accept either. */
  private def decodeCodeUnits(units: PluginValue): String =
    units.asList.getOrElse(Nil).iterator.map { u =>
      val codePoint = u.asInt.map(_.toInt).orElse(u.asChar.map(_.toInt))
      codePoint match
        case Some(n) if n >= 0 && n <= 65535 => n.toChar
        case _ => PluginError.raise("invalid JsonCore string code unit")
    }.mkString

  private def listItems(opt: Option[PluginValue]): List[PluginValue] =
    opt.flatMap(_.asList).getOrElse(Nil)

  /** `__jsonCoreEncodeValue` — ordinary runtime value → `JsonCore*` ADT. */
  def toCore(value: Any): PluginValue =
    value match
      case v if PluginValue.isUnitOrNull(v) => nullCore
      case Inst("JsonValue", fields) =>
        fields.get("_inner").map(inner => toCore(inner.unwrap)).getOrElse(nullCore)
      case Inst(tag, _) if tag.startsWith("JsonCore") => PluginValue.wrap(value)
      case Bool(b) => boolCore(b)
      case Num(n)  => numberCore(n.toString)
      case Big(n)  => numberCore(n.toString)
      case Dec(b)  => numberCore(b.bigDecimal.toPlainString)
      case Dbl(d)  =>
        if java.lang.Double.isFinite(d) then numberCore(java.lang.Double.toString(d))
        else PluginError.raise("jsonStringify cannot encode NaN or Infinity")
      case Chr(c)  => stringCore(c.toString)
      case Str(s)  => stringCore(s)
      case Opt(None)         => nullCore
      case Opt(Some(inner))  => toCore(inner.unwrap)
      case Lst(items) => arrayCore(items.map(i => toCore(i.unwrap)))
      case Tpl(elems) => arrayCore(elems.map(e => toCore(e.unwrap)))
      case MapVal(m) =>
        objectCore(m.iterator.map { (k, v) =>
          fieldCore(k.asString.getOrElse(PluginValue.showAny(k.unwrap)), toCore(v.unwrap))
        }.toList)
      case Inst(_, fields) =>
        objectCore(fields.iterator.map { (k, v) => fieldCore(k, toCore(v.unwrap)) }.toList)
      case other => stringCore(PluginValue.showAny(other))

  /** `JsonCoreOk(value, _)` → value; `JsonCoreErr(msg, offset)` → throw. */
  def unwrapStrict(result: Any): PluginValue =
    result match
      case Inst("JsonCoreOk", fields) => fields.getOrElse("value", PluginValue.unit)
      case Inst("JsonCoreErr", fields) =>
        val message = fields.get("message").flatMap(_.asString).getOrElse("invalid JSON")
        val offset  = fields.get("offset").flatMap(_.asInt).getOrElse(0L)
        PluginError.raise(s"invalid JSON at $offset: $message")
      case _ => PluginError.raise("invalid self-hosted JSON parser result")

  /** `JsonCore*` ADT → ordinary runtime value (objects → `MapV`, arrays →
   *  `ListV`, matching the value model the navigable `JsonValue` facade and
   *  `jsonParse` already expect). */
  def toRaw(core: Any): PluginValue =
    core match
      case Inst("JsonCoreNull", _) => PluginValue.unit
      case Inst("JsonCoreBool", f) =>
        PluginValue.bool(f.get("value").flatMap(_.asBool).getOrElse(false))
      case Inst("JsonCoreNumber", f) =>
        rawNumber(f.get("numberText").flatMap(_.asString).getOrElse("0"))
      case Inst("JsonCoreString", f) =>
        PluginValue.string(f.get("codeUnits").map(decodeCodeUnits).getOrElse(""))
      case Inst("JsonCoreArray", f) =>
        PluginValue.list(listItems(f.get("items")).map(i => toRaw(i.unwrap)))
      case Inst("JsonCoreObject", f) =>
        val entries = listItems(f.get("fields")).flatMap { field =>
          field.unwrap match
            case Inst("JsonCoreField", ff) =>
              val key   = ff.get("key").map(decodeCodeUnits).getOrElse("")
              val value = ff.get("value").map(x => toRaw(x.unwrap)).getOrElse(PluginValue.unit)
              Some(PluginValue.string(key) -> value)
            case _ => None
        }
        PluginValue.map(entries)
      case Inst("JsonCoreOk", _)  => toRaw(unwrapStrict(core).unwrap)
      case Inst("JsonCoreErr", _) => toRaw(unwrapStrict(core).unwrap)
      case _ => PluginValue.unit

  /** Parse a json-core numeric token to the tightest exact runtime value:
   *  integers → `Int`/`BigInt`, anything with a fraction/exponent → exact
   *  `Decimal` (lossless money), never a lossy `Double`. */
  private def rawNumber(raw: String): PluginValue =
    if !raw.exists(c => c == '.' || c == 'e' || c == 'E') then
      try PluginValue.int(raw.toLong)
      catch case _: NumberFormatException => PluginValue.bigint(BigInt(raw))
    else
      try PluginValue.decimal(BigDecimal(raw))
      catch case _: RuntimeException => PluginValue.decimal(BigDecimal("0.0"))
