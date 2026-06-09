package scalascript.compiler.plugin.json

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, JsonParser, Computation,
                                 jsonToJson, wrapJson, lookupKey, jsonAnyToValue}
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object JsonIntrinsics:

  private def stableNative(f: List[Any] => Any): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("jsonStringify") -> stableNative(args =>
      args match
        case List(v) => jsonToJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonStringify(v)")
    ),

    QualifiedName("jsonParse") -> stableNative(args =>
      args match
        case List(s: String) =>
          try JsonParser.parse(s)
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case _ => throw InterpretError("jsonParse(s: String)")
    ),

    QualifiedName("jsonRead") -> stableNative(args =>
      args match
        case List(s: String) =>
          try wrapJson(JsonParser.parse(s))
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case List(v) => wrapJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonRead(s: String) or jsonRead(parsedAny)")
    ),

    QualifiedName("lookup") -> stableNative(args =>
      args match
        case List(v, k) =>
          val vv = jsonAnyToValue(v)
          val kk = jsonAnyToValue(k)
          lookupKey(vv, kk) match
            case Some(x) => x
            case None    => throw InterpretError(s"lookup: key ${Value.show(kk)} not found in ${Value.show(vv)}")
        case _ => throw InterpretError("lookup(v, key)")
    ),

    QualifiedName("lookupOpt") -> stableNative(args =>
      args match
        case List(v, k) => Value.optionV(lookupKey(jsonAnyToValue(v), jsonAnyToValue(k)))
        case _          => throw InterpretError("lookupOpt(v, key)")
    ),

    // ── Navigable JsonValue (std.json) — total accessors, never throw ──────────
    // Tolerant parse → a navigable JsonValue (navJson InstanceV).  A missing key,
    // wrong shape, or malformed input funnels to a Null JsonValue / zero-default;
    // `opt*` and exact `asDecimal` (money) are the explicit-handling escape hatches.
    // See specs/std-ui-typed-json.md.
    QualifiedName("jsonValue") -> stableNative(args =>
      args match
        case List(s: String) =>
          navJson(try JsonParser.parse(s) catch case _: Throwable => Value.NullV)
        case List(v) => navJson(jsonAnyToValue(v))
        case _       => navJson(Value.NullV)
    ),

  )

  /** A navigable, **total** JsonValue wrapper.  Unlike core `wrapJson`, accessors
   *  never throw (zero-defaults), `get`/`at` return a wrapped (Null-on-miss)
   *  JsonValue rather than an Option, and `asDecimal` extracts exact money. */
  private def navJson(inner: Value): Value =
    def numToLong(v: Value): Long = v match
      case Value.IntV(n)     => n
      case Value.DoubleV(d)  => d.toLong
      case Value.DecimalV(b) => b.toLong
      case Value.StringV(s)  => try BigDecimal(s).toLong catch case _: Throwable => 0L
      case _                 => 0L
    def toDecimal(v: Value): Option[BigDecimal] = v match
      case Value.DecimalV(b) => Some(b)
      case Value.IntV(n)     => Some(BigDecimal(n))
      case Value.DoubleV(d)  => try Some(BigDecimal(d.toString)) catch case _: Throwable => None
      case Value.StringV(s)  => try Some(BigDecimal(s)) catch case _: Throwable => None
      case _                 => None
    def fn(name: String)(f: List[Value] => Value): Value = Value.NativeFnV(name, Computation.pureFn(f))
    Value.InstanceV("JsonValue", Map(
      "_inner" -> inner,
      "get" -> fn("JsonValue.get") {
        case List(Value.StringV(k)) => inner match
          case Value.MapV(m) => navJson(m.getOrElse(Value.StringV(k), Value.NullV))
          case _             => navJson(Value.NullV)
        case _ => navJson(Value.NullV)
      },
      "at" -> fn("JsonValue.at") {
        case List(idx) =>
          val i = numToLong(idx)
          inner match
            case Value.ListV(items) if i >= 0 && i < items.length => navJson(items(i.toInt))
            case _                                                => navJson(Value.NullV)
        case _ => navJson(Value.NullV)
      },
      "isNull" -> fn("JsonValue.isNull")(_ => inner match
        case Value.NullV | Value.UnitV | Value.NoneV => Value.True
        case _                                       => Value.False),
      "asString" -> fn("JsonValue.asString")(_ => inner match
        case Value.StringV(s) => Value.StringV(s)
        case _                => Value.StringV("")),
      "asInt" -> fn("JsonValue.asInt")(_ => Value.intV(numToLong(inner))),
      "asDouble" -> fn("JsonValue.asDouble")(_ => inner match
        case Value.DoubleV(d)  => Value.doubleV(d)
        case Value.IntV(n)     => Value.doubleV(n.toDouble)
        case Value.DecimalV(b) => Value.doubleV(b.toDouble)
        case Value.StringV(s)  => Value.doubleV(try s.toDouble catch case _: Throwable => 0.0)
        case _                 => Value.doubleV(0.0)),
      "asBool" -> fn("JsonValue.asBool")(_ => inner match
        case b: Value.BoolV => b
        case _              => Value.False),
      "asList" -> fn("JsonValue.asList")(_ => inner match
        case Value.ListV(items) => Value.ListV(items.map(navJson))
        case _                  => Value.EmptyList),
      // Exact-decimal — lossless for money.  A JSON string ("1000.00") keeps its
      // text; a bare DoubleV was already lossy at parse time (spec §3.1).
      "asDecimal" -> fn("JsonValue.asDecimal")(_ =>
        Value.DecimalV(toDecimal(inner).getOrElse(BigDecimal(0)))),
      "optString" -> fn("JsonValue.optString")(_ => inner match
        case Value.StringV(s) => Value.optionV(Some(Value.StringV(s)))
        case _                => Value.NoneV),
      "optInt" -> fn("JsonValue.optInt")(_ => inner match
        case n: Value.IntV                 => Value.optionV(Some(n))
        case Value.DoubleV(d) if d.isWhole => Value.optionV(Some(Value.intV(d.toLong)))
        case _                             => Value.NoneV),
      "optDecimal" -> fn("JsonValue.optDecimal")(_ =>
        Value.optionV(toDecimal(inner).map(b => Value.DecimalV(b)))),
      "getOrElse" -> fn("JsonValue.getOrElse") {
        case List(Value.StringV(k), Value.StringV(fb)) => inner match
          case Value.MapV(m) => m.get(Value.StringV(k)) match
            case Some(Value.StringV(s)) => Value.StringV(s)
            case Some(other)            => Value.StringV(Value.show(other))
            case None                   => Value.StringV(fb)
          case _ => Value.StringV(fb)
        case _ => Value.StringV("")
      },
      "raw" -> fn("JsonValue.raw")(_ => inner),
    ))
