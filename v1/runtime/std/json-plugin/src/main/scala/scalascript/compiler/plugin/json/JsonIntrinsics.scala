package scalascript.compiler.plugin.json

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Dec, Lst, MapVal}

object JsonIntrinsics:

  private def stableNative(f: List[Any] => Any): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("jsonStringify") -> stableNative(args =>
      args match
        case List(v) => PluginValue.jsonEncode(PluginValue.fromHostAny(v))
        case _       => PluginError.raise("jsonStringify(v)")
    ),

    QualifiedName("jsonParse") -> stableNative(args =>
      args match
        case List(s: String) =>
          PluginValue.parseJson(s) match
            case Right(pv) => pv
            case Left(msg) => PluginError.raise(msg)
        case _ => PluginError.raise("jsonParse(s: String)")
    ),

    QualifiedName("jsonRead") -> stableNative(args =>
      args match
        case List(s: String) =>
          PluginValue.parseJson(s) match
            case Right(pv) => PluginValue.jsonFacade(pv)
            case Left(msg) => PluginError.raise(msg)
        case List(v) => PluginValue.jsonFacade(PluginValue.fromHostAny(v))
        case _       => PluginError.raise("jsonRead(s: String) or jsonRead(parsedAny)")
    ),

    QualifiedName("lookup") -> stableNative(args =>
      args match
        case List(v, k) =>
          val vv = PluginValue.fromHostAny(v)
          val kk = PluginValue.fromHostAny(k)
          vv.lookupKey(kk) match
            case Some(x) => x
            case None    => PluginError.raise(s"lookup: key ${kk.show} not found in ${vv.show}")
        case _ => PluginError.raise("lookup(v, key)")
    ),

    QualifiedName("lookupOpt") -> stableNative(args =>
      args match
        case List(v, k) => PluginValue.option(PluginValue.fromHostAny(v).lookupKey(PluginValue.fromHostAny(k)))
        case _          => PluginError.raise("lookupOpt(v, key)")
    ),

    // ── Navigable JsonValue (std.json) — total accessors, never throw ──────────
    // Tolerant parse → a navigable JsonValue (navJson InstanceV).  A missing key,
    // wrong shape, or malformed input funnels to a Null JsonValue / zero-default;
    // `opt*` and exact `asDecimal` (money) are the explicit-handling escape hatches.
    // See specs/std-ui-typed-json.md.
    QualifiedName("jsonValue") -> stableNative(args =>
      args match
        case List(s: String) =>
          navJson(PluginValue.parseJson(s).getOrElse(PluginValue.nullV))
        case List(v) => navJson(PluginValue.fromHostAny(v))
        case _       => navJson(PluginValue.nullV)
    ),

    // ── Self-hosted JSON core boundary (std/json.ssc) ─────────────────────────
    // The standard JSON codec is self-hosted: json-core.ssc owns parsing and
    // rendering (the `jsonCoreParse*` / `jsonCoreRender` interpreted functions),
    // and these five primitives are the only native seam — they map ordinary
    // runtime values to/from the portable `JsonCore*` ADT and (on v2) hold the
    // renderer.  See v2 `ssc.plugin.json.NativeJsonCodec` for the reference
    // semantics; `V1JsonCore` is the interpreter port on `PluginValue`.

    // Accept and ignore the self-hosted renderer closure: v1 renders JSON
    // in-language (json.ssc `jsonStringify` calls the interpreted `jsonCoreRender`
    // directly), so — unlike v2 — no native provider ever calls back into it.
    QualifiedName("__jsonCoreInstallRenderer") -> stableNative(_ => PluginValue.unit),

    QualifiedName("__jsonCoreEncodeValue") -> stableNative {
      case List(v) => V1JsonCore.toCore(v)
      case _       => PluginError.raise("__jsonCoreEncodeValue(value)")
    },

    QualifiedName("__jsonCoreWrap") -> stableNative {
      case List(core) => navJson(V1JsonCore.toRaw(core))
      case _          => PluginError.raise("__jsonCoreWrap(value)")
    },

    QualifiedName("__jsonCoreWrapStrict") -> stableNative {
      case List(result) => navJson(V1JsonCore.toRaw(V1JsonCore.unwrapStrict(result).unwrap))
      case _            => PluginError.raise("__jsonCoreWrapStrict(result)")
    },

    QualifiedName("__jsonCoreRawStrict") -> stableNative {
      case List(result) => V1JsonCore.toRaw(V1JsonCore.unwrapStrict(result).unwrap)
      case _            => PluginError.raise("__jsonCoreRawStrict(result)")
    },

  )

  /** A navigable, **total** JsonValue wrapper.  Unlike core `jsonFacade`, accessors
   *  never throw (zero-defaults), `get`/`at` return a wrapped (Null-on-miss)
   *  JsonValue rather than an Option, and `asDecimal` extracts exact money. */
  private def navJson(inner: PluginValue): PluginValue =
    def numToLong(v: PluginValue): Long = v match
      case Num(n) => n
      case Dbl(d) => d.toLong
      case Dec(b) => b.toLong
      case Str(s) => try BigDecimal(s).toLong catch case _: Throwable => 0L
      case _      => 0L
    def toDecimal(v: PluginValue): Option[BigDecimal] = v match
      case Dec(b) => Some(b)
      case Num(n) => Some(BigDecimal(n))
      case Dbl(d) => try Some(BigDecimal(d.toString)) catch case _: Throwable => None
      case Str(s) => try Some(BigDecimal(s)) catch case _: Throwable => None
      case _      => None
    def fn(name: String)(f: List[PluginValue] => PluginValue): PluginValue = PluginValue.nativeFn(name, f)
    def isNullish(v: PluginValue): Boolean = PluginValue.isUnitOrNull(v) || v == PluginValue.none
    PluginValue.instance("JsonValue", Map(
      "_inner" -> inner,
      "get" -> fn("JsonValue.get") {
        case List(Str(k)) => inner match
          case MapVal(m) => navJson(m.getOrElse(PluginValue.string(k), PluginValue.nullV))
          case _         => navJson(PluginValue.nullV)
        case _ => navJson(PluginValue.nullV)
      },
      "at" -> fn("JsonValue.at") {
        case List(idx) =>
          val i = numToLong(idx)
          inner match
            case Lst(items) if i >= 0 && i < items.length => navJson(items(i.toInt))
            case _                                        => navJson(PluginValue.nullV)
        case _ => navJson(PluginValue.nullV)
      },
      "isNull" -> fn("JsonValue.isNull")(_ => PluginValue.bool(isNullish(inner))),
      "asString" -> fn("JsonValue.asString")(_ => inner match
        case Str(s) => PluginValue.string(s)
        case _      => PluginValue.string("")),
      "asInt" -> fn("JsonValue.asInt")(_ => PluginValue.int(numToLong(inner))),
      "asDouble" -> fn("JsonValue.asDouble")(_ => inner match
        case Dbl(d) => PluginValue.double(d)
        case Num(n) => PluginValue.double(n.toDouble)
        case Dec(b) => PluginValue.double(b.toDouble)
        case Str(s) => PluginValue.double(try s.toDouble catch case _: Throwable => 0.0)
        case _      => PluginValue.double(0.0)),
      "asBool" -> fn("JsonValue.asBool")(_ => inner match
        case Bool(b) => PluginValue.bool(b)
        case _       => PluginValue.bool(false)),
      "asList" -> fn("JsonValue.asList")(_ => inner match
        case Lst(items) => PluginValue.list(items.map(navJson))
        case _          => PluginValue.list(Nil)),
      // Exact-decimal — lossless for money.  A JSON string ("1000.00") keeps its
      // text; a bare DoubleV was already lossy at parse time (spec §3.1).
      "asDecimal" -> fn("JsonValue.asDecimal")(_ =>
        PluginValue.decimal(toDecimal(inner).getOrElse(BigDecimal(0)))),
      "optString" -> fn("JsonValue.optString")(_ => inner match
        case Str(s) => PluginValue.some(PluginValue.string(s))
        case _      => PluginValue.none),
      "optInt" -> fn("JsonValue.optInt")(_ => inner match
        case Num(n)                  => PluginValue.some(PluginValue.int(n))
        case Dbl(d) if d.isWhole     => PluginValue.some(PluginValue.int(d.toLong))
        case _                       => PluginValue.none),
      "optDecimal" -> fn("JsonValue.optDecimal")(_ =>
        PluginValue.option(toDecimal(inner).map(PluginValue.decimal))),
      "getOrElse" -> fn("JsonValue.getOrElse") {
        case List(Str(k), Str(fb)) => inner match
          case MapVal(m) => m.get(PluginValue.string(k)) match
            case Some(Str(s)) => PluginValue.string(s)
            case Some(other)  => PluginValue.string(other.show)
            case None         => PluginValue.string(fb)
          case _ => PluginValue.string(fb)
        case _ => PluginValue.string("")
      },
      "raw" -> fn("JsonValue.raw")(_ => inner),
    ))
