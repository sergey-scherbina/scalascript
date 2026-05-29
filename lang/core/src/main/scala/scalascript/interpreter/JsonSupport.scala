package scalascript.interpreter

/** JSON encoding helpers shared between the bundled Http intrinsics and the
 *  json-plugin.  Lives in `core` so both callers can reach it without a
 *  circular dependency. */

// ── JSON encoder ─────────────────────────────────────────────────────────────

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
    case Value.NoneV        => "null"
    case Value.OptionV(x)           => jsonToJson(x)
    case Value.TupleV(elems)        => elems.map(jsonToJson).mkString("[", ",", "]")
    case Value.InstanceV(_, fields) =>
      fields.map((k, v) => quote(k) + ":" + jsonToJson(v)).mkString("{", ",", "}")
    case other                      => quote(Value.show(other))

// ── JsonValue wrapper ─────────────────────────────────────────────────────────

def wrapJson(inner: Value): Value =
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
      case Value.MapV(m) =>
        val v = m.getOrElse(Value.StringV(k), null)
        if v != null then Value.OptionV(wrapJson(v)) else Value.NoneV
      case _             => Value.NoneV
    case List(Value.IntV(i)) => inner match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        Value.OptionV(wrapJson(items(i.toInt)))
      case _ => Value.NoneV
    case _ => throw InterpretError("JsonValue.get(key | index)")
  })
  val asStringFn = Value.NativeFnV("JsonValue.asString", Computation.pureFn(_ => inner match
    case Value.StringV(s) => Value.StringV(s)
    case other            => typedFail("asString", other)))
  val asIntFn = Value.NativeFnV("JsonValue.asInt", Computation.pureFn(_ => inner match
    case n: Value.IntV    => n
    case Value.DoubleV(d) => Value.intV(d.toLong)
    case other            => typedFail("asInt", other)))
  val asDoubleFn = Value.NativeFnV("JsonValue.asDouble", Computation.pureFn(_ => inner match
    case d: Value.DoubleV => d
    case Value.IntV(n)    => Value.doubleV(n.toDouble)
    case other            => typedFail("asDouble", other)))
  val asBoolFn = Value.NativeFnV("JsonValue.asBool", Computation.pureFn(_ => inner match
    case b: Value.BoolV => b
    case other          => typedFail("asBool", other)))
  val asListFn = Value.NativeFnV("JsonValue.asList", Computation.pureFn(_ => inner match
    case Value.ListV(items) => Value.ListV(items.map(wrapJson))
    case other              => typedFail("asList", other)))
  val asMapFn = Value.NativeFnV("JsonValue.asMap", Computation.pureFn(_ => inner match
    case Value.MapV(m) => Value.MapV(m.map { case (k, v) => k -> wrapJson(v) })
    case other         => typedFail("asMap", other)))
  val rawFn    = Value.NativeFnV("JsonValue.raw",    Computation.pureFn(_ => inner))
  val isNullFn = Value.NativeFnV("JsonValue.isNull", Computation.pureFn(_ => inner match
    case Value.UnitV | Value.NoneV => Value.True
    case _                                  => Value.False))
  val keysFn = Value.NativeFnV("JsonValue.keys", Computation.pureFn(_ => inner match
    case Value.MapV(m) => Value.ListV(m.keys.toList)
    case _             => Value.EmptyList))
  val sizeFn = Value.NativeFnV("JsonValue.size", Computation.pureFn(_ => inner match
    case Value.ListV(items) => Value.intV(items.length.toLong)
    case Value.MapV(m)      => Value.intV(m.size.toLong)
    case Value.StringV(s)   => Value.intV(s.length.toLong)
    case _                  => Value.intV(0L)))
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

def lookupKey(v: Value, k: Value): Option[Value] = v match
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

def jsonAnyToValue(a: Any): Value = a match
  case n: Long    => Value.intV(n)
  case i: Int     => Value.intV(i.toLong)
  case d: Double  => Value.doubleV(d)
  case s: String  => Value.StringV(s)
  case b: Boolean => Value.boolV(b)
  case ()         => Value.UnitV
  case v: Value   => v
  case other      => Value.StringV(other.toString)
