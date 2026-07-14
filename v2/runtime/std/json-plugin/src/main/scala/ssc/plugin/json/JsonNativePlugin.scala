package ssc.plugin.json

import ssc.{Done, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Navigation/value bridge for the self-hosted ScalaScript JSON core. */
final class JsonNativePlugin extends NativePlugin:
  def id: String = "40-json"

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
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
        case _ => throw new RuntimeException("lookup expected a valid List value")
    out.toList

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private final class JsonBox(val core: Value, context: NativePluginContext,
      optional: Boolean = false, present: Boolean = true)
      extends Value.NamedMethodObj, NativeJsonValue:
    def underlying: AnyRef = core

    private def boxed(value: Value): Value = Value.ForeignV(JsonBox(value, context))
    // `get` returns an OPTIONAL JsonValue: it renders Some(inner)/None like INT yet keeps
    // every JsonValue method (asString on an absent key → "", `.map` → Some/None). This
    // reconciles json-value's Option usage (`v.get(k).map(...)`, prints Some/None) with
    // json-deep-import / ui-typed-json's apply usage (`v.get(k).asString`). (v2-json-optional.)
    private def boxedOpt(v: Option[Value]): Value =
      Value.ForeignV(JsonBox(v.getOrElse(NativeJsonCodec.nullCore), context,
        optional = true, present = v.isDefined))

    private def argText(args: List[Value], index: Int): Option[String] = args.lift(index) match
      case Some(Value.StrV(text)) => Some(text)
      case _ => None

    private def decimal: Option[java.math.BigDecimal] =
      NativeJsonCodec.numberText(core).orElse(NativeJsonCodec.stringValue(core)).flatMap { text =>
        try Some(new java.math.BigDecimal(text))
        catch case _: NumberFormatException => None
      }

    private def getCore(key: String): Option[Value] =
      NativeJsonCodec.objectValues(core).find(_._1 == key).map(_._2)

    private def atCore(index: Long): Option[Value] =
      if index < 0 || index > Int.MaxValue then None
      else NativeJsonCodec.arrayValues(core).lift(index.toInt)

    def getField(name: String): Option[Value] = name match
      case "apply" => Some(closure(1) { args => args.headOption match
        case Some(Value.StrV(key)) => boxed(getCore(key).getOrElse(NativeJsonCodec.nullCore))
        case Some(Value.IntV(index)) => boxed(atCore(index).getOrElse(NativeJsonCodec.nullCore))
        case _ => boxed(NativeJsonCodec.nullCore)
      })
      case "get" => Some(closure(1) { args => boxedOpt(argText(args, 0).flatMap(getCore)) })
      // `.map(f): Option` — present → Some(f(innerJsonValue)); absent → None. Lets
      // `v.get(k).map(jv => jv.asString)` print `Some(Ada)` / `None` like INT.
      case "map" if optional => Some(closure(1) { args =>
        if present then Value.DataV("Some", Vector(context.invoke(args.head, List(boxed(core)))))
        else Value.DataV("None", Vector.empty)
      })
      // `_show` — println/anyStr renders a JsonBox (a NamedMethodObj ForeignV) via this
      // field before the `<foreign>` fallback. An OPTIONAL box renders Some(inner)/None;
      // a plain box renders INT's native Map/List/scalar form.
      case "_show" => Some(Value.StrV(
        if optional && !present then "None"
        else if optional then s"Some(${NativeJsonCodec.interpShow(core)})"
        else NativeJsonCodec.interpShow(core)))
      case "at" => Some(closure(1) { args => args.headOption match
        case Some(Value.IntV(index)) => boxed(atCore(index).getOrElse(NativeJsonCodec.nullCore))
        case _ => boxed(NativeJsonCodec.nullCore)
      })
      case "isNull" => Some(closure(0)(_ => Value.BoolV(NativeJsonCodec.isNull(core))))
      case "asString" => Some(closure(0)(_ =>
        Value.StrV(NativeJsonCodec.stringValue(core).getOrElse(""))))
      case "asInt" => Some(closure(0)(_ => Value.IntV(decimal.map(_.longValue()).getOrElse(0L))))
      case "asDouble" => Some(closure(0)(_ => Value.FloatV(decimal.map(_.doubleValue()).getOrElse(0.0))))
      case "asBool" => Some(closure(0)(_ => Value.BoolV(NativeJsonCodec.boolValue(core).getOrElse(false))))
      case "asList" => Some(closure(0)(_ => list(NativeJsonCodec.arrayValues(core).map(boxed))))
      case "asDecimal" => Some(closure(0)(_ =>
        Value.DecimalV(decimal.getOrElse(java.math.BigDecimal.ZERO).toPlainString)))
      case "optString" => Some(closure(0)(_ => NativeJsonCodec.stringValue(core) match
        case Some(text) => Value.DataV("Some", Vector(Value.StrV(text)))
        case None => Value.DataV("None", Vector.empty)))
      case "optInt" => Some(closure(0)(_ => decimal match
        case Some(value) if value.stripTrailingZeros().scale() <= 0 =>
          Value.DataV("Some", Vector(Value.IntV(value.longValue())))
        case _ => Value.DataV("None", Vector.empty)))
      case "optDecimal" => Some(closure(0)(_ => decimal match
        case Some(value) => Value.DataV("Some", Vector(Value.DecimalV(value.toPlainString)))
        case None => Value.DataV("None", Vector.empty)))
      case "getOrElse" => Some(closure(2) { args =>
        val fallback = argText(args, 1).getOrElse("")
        argText(args, 0).flatMap(getCore) match
          case Some(value) => NativeJsonCodec.stringValue(value)
            .map(Value.StrV.apply)
            .getOrElse(Value.StrV(NativeJsonCodec.renderCore(value)))
          case None => Value.StrV(fallback)
      })
      case "raw" => Some(Value.StrV(NativeJsonCodec.renderCore(core)))
      case "size" => Some(Value.IntV((NativeJsonCodec.arrayValues(core) match
        case Nil => NativeJsonCodec.objectValues(core).size
        case values => values.size).toLong))
      case "keys" => Some(list(NativeJsonCodec.objectValues(core).map { case (key, _) => Value.StrV(key) }))
      case _ => None

    def getCoreValue(key: String): Option[Value] = getCore(key)
    def atCoreValue(index: Long): Option[Value] = atCore(index)

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def lookup(args: List[Value]): Option[Value] = args match
    case Value.ForeignV(box: JsonBox) :: Value.StrV(key) :: Nil =>
      box.getCoreValue(key).map(NativeJsonCodec.toRaw)
    case Value.ForeignV(box: JsonBox) :: Value.IntV(index) :: Nil =>
      box.atCoreValue(index).map(NativeJsonCodec.toRaw)
    case Value.MapV(map) :: key :: Nil => map.get(key)
    case Value.ForeignV(map: collection.Map[?, ?]) :: key :: Nil
        if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]].get(key)
    case (values @ Value.DataV("Cons" | "Nil", _)) :: Value.IntV(index) :: Nil if index >= 0 =>
      unlist(values).lift(index.toInt)
    case Value.StrV(text) :: Value.IntV(index) :: Nil if index >= 0 && index < text.length =>
      Some(Value.StrV(text.substring(index.toInt, index.toInt + 1)))
    case _ => None

  def install(context: NativePluginContext): Unit =
    NativeJsonCodec.resetRenderer()
    native(context, "__jsonCoreInstallRenderer") {
      case renderer :: Nil => NativeJsonCodec.installRenderer(context, renderer); Value.UnitV
      case _ => throw new RuntimeException("__jsonCoreInstallRenderer(render)")
    }
    native(context, "__jsonCoreWrap") {
      case core :: Nil => Value.ForeignV(JsonBox(core, context))
      case _ => throw new RuntimeException("__jsonCoreWrap(value)")
    }
    native(context, "__jsonCoreWrapStrict") {
      case result :: Nil => Value.ForeignV(JsonBox(NativeJsonCodec.unwrapStrict(result), context))
      case _ => throw new RuntimeException("__jsonCoreWrapStrict(result)")
    }
    native(context, "__jsonCoreRawStrict") {
      case result :: Nil => NativeJsonCodec.toRaw(NativeJsonCodec.unwrapStrict(result))
      case _ => throw new RuntimeException("__jsonCoreRawStrict(result)")
    }
    native(context, "__jsonCoreEncodeValue") {
      case value :: Nil => NativeJsonCodec.toCore(value)
      case _ => throw new RuntimeException("__jsonCoreEncodeValue(value)")
    }

    // A direct global without std/json.ssc must never fall back to a host parser.
    native(context, "jsonParse") { _ =>
      throw new RuntimeException("jsonParse is self-hosted; import std/json.ssc")
    }
    native(context, "lookup") { args =>
      lookup(args).getOrElse(throw new RuntimeException("lookup: key not found"))
    }
    native(context, "lookupOpt") { args => lookup(args) match
      case Some(value) => Value.DataV("Some", Vector(value))
      case None => Value.DataV("None", Vector.empty)
    }
