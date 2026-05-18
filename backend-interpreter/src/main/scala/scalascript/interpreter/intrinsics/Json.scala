package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** JSON intrinsics for the tree-walking interpreter (Stage 5+/E).
 *
 *  Migrated from hardcoded `nativeP` calls in `Interpreter.initBuiltins`. */
val JsonIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  QualifiedName("jsonStringify") -> NativeImpl((_, args) =>
    args match
      case List(v) => jsonToJson(jsonAnyToValue(v))
      case _       => throw InterpretError("jsonStringify(v)")
  ),

  QualifiedName("jsonParse") -> NativeImpl((_, args) =>
    args match
      case List(s: String) =>
        try JsonParser.parse(s)
        catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
      case _ => throw InterpretError("jsonParse(s: String)")
  ),

  QualifiedName("jsonRead") -> NativeImpl((_, args) =>
    args match
      case List(s: String) =>
        try wrapJson(JsonParser.parse(s))
        catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
      case List(v) => wrapJson(jsonAnyToValue(v))
      case _       => throw InterpretError("jsonRead(s: String) or jsonRead(parsedAny)")
  ),

  QualifiedName("lookup") -> NativeImpl((_, args) =>
    args match
      case List(v, k) =>
        val vv = jsonAnyToValue(v)
        val kk = jsonAnyToValue(k)
        lookupKey(vv, kk) match
          case Some(x) => x
          case None    => throw InterpretError(s"lookup: key ${Value.show(kk)} not found in ${Value.show(vv)}")
      case _ => throw InterpretError("lookup(v, key)")
  ),

  QualifiedName("lookupOpt") -> NativeImpl((_, args) =>
    args match
      case List(v, k) => Value.OptionV(lookupKey(jsonAnyToValue(v), jsonAnyToValue(k)))
      case _          => throw InterpretError("lookupOpt(v, key)")
  ),

)

// ── JSON encoder ─────────────────────────────────────────────────────────────

/** JSON-encode a Value.  Package-accessible so Http.scala's Response.json
 *  intrinsic can call it without duplicating the encoder. */
def jsonToJson(v: Value): String =
  def quote(s: String): String =
    val sb = StringBuilder().append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
        case c    => sb.append(c)
      i += 1
    sb.append('"').toString
  def keyStr(k: Value): String = k match
    case Value.StringV(s) => s
    case other            => Value.show(other)
  v match
    case Value.StringV(s)           => quote(s)
    case Value.IntV(n)              => n.toString
    case Value.DoubleV(d)           => d.toString
    case Value.BoolV(b)             => b.toString
    case Value.NullV | Value.UnitV  => "null"
    case Value.CharV(c)             => quote(c.toString)
    case Value.ListV(items)         => items.map(jsonToJson).mkString("[", ",", "]")
    case Value.MapV(entries)        =>
      entries.map((k, v) => quote(keyStr(k)) + ":" + jsonToJson(v)).mkString("{", ",", "}")
    case Value.OptionV(None)        => "null"
    case Value.OptionV(Some(x))     => jsonToJson(x)
    case Value.TupleV(elems)        => elems.map(jsonToJson).mkString("[", ",", "]")
    case Value.InstanceV(_, fields) =>
      fields.map((k, v) => quote(k) + ":" + jsonToJson(v)).mkString("{", ",", "}")
    case other                      => quote(Value.show(other))

// ── JsonValue wrapper ─────────────────────────────────────────────────────────

private def wrapJson(inner: Value): Value =
  def typedFail(what: String, got: Value): Nothing =
    throw InterpretError(s"JsonValue.$what: expected ${what.stripPrefix("as").toLowerCase} but got ${Value.show(got)}")
  val applyFn = Value.NativeFnV("JsonValue.apply", Computation.pureFn {
    case List(Value.StringV(k)) => inner match
      case Value.MapV(m) => m.get(Value.StringV(k)) match
        case Some(v) => wrapJson(v)
        case None    => throw InterpretError(s"JsonValue: no key '$k'")
      case _ => throw InterpretError(s"JsonValue.apply($k): not an object")
    case List(Value.IntV(i)) => inner match
      case Value.ListV(items) =>
        if i >= 0 && i < items.length then wrapJson(items(i.toInt))
        else throw InterpretError(s"JsonValue: index $i out of bounds (size=${items.length})")
      case _ => throw InterpretError(s"JsonValue.apply($i): not an array")
    case args => throw InterpretError(s"JsonValue.apply(key: String | index: Int), got ${args.length} arg(s)")
  })
  val getFn = Value.NativeFnV("JsonValue.get", Computation.pureFn {
    case List(Value.StringV(k)) => inner match
      case Value.MapV(m) => Value.OptionV(m.get(Value.StringV(k)).map(wrapJson))
      case _             => Value.OptionV(None)
    case List(Value.IntV(i)) => inner match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        Value.OptionV(Some(wrapJson(items(i.toInt))))
      case _ => Value.OptionV(None)
    case _ => throw InterpretError("JsonValue.get(key | index)")
  })
  val asStringFn = Value.NativeFnV("JsonValue.asString", Computation.pureFn(_ => inner match
    case Value.StringV(s) => Value.StringV(s)
    case other            => typedFail("asString", other)))
  val asIntFn = Value.NativeFnV("JsonValue.asInt", Computation.pureFn(_ => inner match
    case Value.IntV(n)    => Value.IntV(n)
    case Value.DoubleV(d) => Value.IntV(d.toLong)
    case other            => typedFail("asInt", other)))
  val asDoubleFn = Value.NativeFnV("JsonValue.asDouble", Computation.pureFn(_ => inner match
    case Value.DoubleV(d) => Value.DoubleV(d)
    case Value.IntV(n)    => Value.DoubleV(n.toDouble)
    case other            => typedFail("asDouble", other)))
  val asBoolFn = Value.NativeFnV("JsonValue.asBool", Computation.pureFn(_ => inner match
    case Value.BoolV(b) => Value.BoolV(b)
    case other          => typedFail("asBool", other)))
  val asListFn = Value.NativeFnV("JsonValue.asList", Computation.pureFn(_ => inner match
    case Value.ListV(items) => Value.ListV(items.map(wrapJson))
    case other              => typedFail("asList", other)))
  val asMapFn = Value.NativeFnV("JsonValue.asMap", Computation.pureFn(_ => inner match
    case Value.MapV(m) => Value.MapV(m.map { case (k, v) => k -> wrapJson(v) })
    case other         => typedFail("asMap", other)))
  val rawFn    = Value.NativeFnV("JsonValue.raw",    Computation.pureFn(_ => inner))
  val isNullFn = Value.NativeFnV("JsonValue.isNull", Computation.pureFn(_ => inner match
    case Value.UnitV | Value.OptionV(None) => Value.BoolV(true)
    case _                                  => Value.BoolV(false)))
  val keysFn = Value.NativeFnV("JsonValue.keys", Computation.pureFn(_ => inner match
    case Value.MapV(m) => Value.ListV(m.keys.toList)
    case _             => Value.ListV(Nil)))
  val sizeFn = Value.NativeFnV("JsonValue.size", Computation.pureFn(_ => inner match
    case Value.ListV(items) => Value.IntV(items.length.toLong)
    case Value.MapV(m)      => Value.IntV(m.size.toLong)
    case Value.StringV(s)   => Value.IntV(s.length.toLong)
    case _                  => Value.IntV(0L)))
  Value.InstanceV("JsonValue", Map(
    "_inner"   -> inner,
    "apply"    -> applyFn,
    "get"      -> getFn,
    "asString" -> asStringFn,
    "asInt"    -> asIntFn,
    "asLong"   -> asIntFn,
    "asDouble" -> asDoubleFn,
    "asBool"   -> asBoolFn,
    "asList"   -> asListFn,
    "asMap"    -> asMapFn,
    "raw"      -> rawFn,
    "isNull"   -> isNullFn,
    "keys"     -> keysFn,
    "size"     -> sizeFn,
  ))

private def lookupKey(v: Value, k: Value): Option[Value] = v match
  case Value.MapV(m)      => m.get(k)
  case Value.ListV(items) => k match
    case Value.IntV(i) if i >= 0 && i < items.length => Some(items(i.toInt))
    case _                                            => None
  case Value.InstanceV(_, fields) => k match
    case Value.StringV(name) => fields.get(name)
    case _                   => None
  case Value.StringV(s) => k match
    case Value.IntV(i) if i >= 0 && i < s.length =>
      Some(Value.StringV(s.charAt(i.toInt).toString))
    case _ => None
  case _ => None

private def jsonAnyToValue(a: Any): Value = a match
  case n: Long    => Value.IntV(n)
  case i: Int     => Value.IntV(i.toLong)
  case d: Double  => Value.DoubleV(d)
  case s: String  => Value.StringV(s)
  case b: Boolean => Value.BoolV(b)
  case ()         => Value.UnitV
  case v: Value   => v
  case other      => Value.StringV(other.toString)
