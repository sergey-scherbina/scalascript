package ssc.plugin.json

import ssc.{Done, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free typed JSON provider for the standard ScalaScript 2.1 runtime. */
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
        case _ => throw new RuntimeException("jsonStringify expected a valid List value")
    out.toList

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def parse(text: String): ujson.Value =
    try ujson.read(text)
    catch case error: Throwable => throw new RuntimeException(s"invalid JSON: ${error.getMessage}")

  private def parseTolerant(text: String): ujson.Value =
    try ujson.read(text)
    catch case _: Throwable => ujson.Null

  private def numberText(value: ujson.Value): String = ujson.write(value)

  private def numberLong(value: ujson.Value): Long = value match
    case ujson.Num(number) => number.toLong
    case ujson.Str(text) => try BigDecimal(text).toLong catch case _: Throwable => 0L
    case _ => 0L

  private def decimal(value: ujson.Value): Option[java.math.BigDecimal] = value match
    case ujson.Str(text) => try Some(new java.math.BigDecimal(text)) catch case _: Throwable => None
    case number: ujson.Num =>
      try Some(new java.math.BigDecimal(numberText(number))) catch case _: Throwable => None
    case _ => None

  private def rawValue(value: ujson.Value): Value = value match
    case ujson.Str(text) => Value.StrV(text)
    case ujson.Num(number) if number.isWhole && number >= Long.MinValue && number <= Long.MaxValue =>
      Value.IntV(number.toLong)
    case ujson.Num(number) => Value.FloatV(number)
    case ujson.Bool(boolean) => Value.BoolV(boolean)
    case ujson.Null => Value.UnitV
    case array: ujson.Arr => list(array.value.iterator.map(rawValue))
    case obj: ujson.Obj =>
      val values = collection.mutable.LinkedHashMap.empty[Value, Value]
      obj.value.foreach { case (key, item) => values(Value.StrV(key)) = rawValue(item) }
      Value.ForeignV(values)

  private final class JsonBox(val inner: ujson.Value) extends Value.NamedMethodObj, NativeJsonValue:
    def underlying: AnyRef = inner

    private def boxed(value: ujson.Value): Value = Value.ForeignV(JsonBox(value))
    private def argText(args: List[Value], index: Int): Option[String] = args.lift(index) match
      case Some(Value.StrV(text)) => Some(text)
      case _ => None

    def getField(name: String): Option[Value] = name match
      case "apply" => Some(closure(1) { args => args.headOption match
        case Some(Value.StrV(key)) => get(key)
        case Some(Value.IntV(index)) => at(index)
        case _ => boxed(ujson.Null)
      })
      case "get" => Some(closure(1) { args => argText(args, 0).map(get).getOrElse(boxed(ujson.Null)) })
      case "at" => Some(closure(1) { args => args.headOption match
        case Some(Value.IntV(index)) => at(index)
        case _ => boxed(ujson.Null)
      })
      case "isNull" => Some(closure(0)(_ => Value.BoolV(inner == ujson.Null)))
      case "asString" => Some(closure(0)(_ => inner match
        case ujson.Str(text) => Value.StrV(text)
        case _ => Value.StrV("")))
      case "asInt" => Some(closure(0)(_ => Value.IntV(numberLong(inner))))
      case "asDouble" => Some(closure(0)(_ => inner match
        case ujson.Num(number) => Value.FloatV(number)
        case ujson.Str(text) => Value.FloatV(try text.toDouble catch case _: Throwable => 0.0)
        case _ => Value.FloatV(0.0)))
      case "asBool" => Some(closure(0)(_ => inner match
        case ujson.Bool(boolean) => Value.BoolV(boolean)
        case _ => Value.BoolV(false)))
      case "asList" => Some(closure(0)(_ => inner match
        case array: ujson.Arr => list(array.value.iterator.map(boxed))
        case _ => list(Nil)))
      case "asDecimal" => Some(closure(0)(_ =>
        Value.ForeignV(decimal(inner).getOrElse(java.math.BigDecimal.ZERO))))
      case "optString" => Some(closure(0)(_ => inner match
        case ujson.Str(text) => Value.DataV("Some", Vector(Value.StrV(text)))
        case _ => Value.DataV("None", Vector.empty)))
      case "optInt" => Some(closure(0)(_ => inner match
        case ujson.Num(number) if number.isWhole => Value.DataV("Some", Vector(Value.IntV(number.toLong)))
        case _ => Value.DataV("None", Vector.empty)))
      case "optDecimal" => Some(closure(0)(_ => decimal(inner) match
        case Some(value) => Value.DataV("Some", Vector(Value.ForeignV(value)))
        case None => Value.DataV("None", Vector.empty)))
      case "getOrElse" => Some(closure(2) { args =>
        val fallback = argText(args, 1).getOrElse("")
        argText(args, 0).flatMap(key => inner match
          case obj: ujson.Obj => obj.value.get(key)
          case _ => None) match
          case Some(ujson.Str(text)) => Value.StrV(text)
          case Some(value) => Value.StrV(ujson.write(value))
          case None => Value.StrV(fallback)
      })
      // Structured builders consume raw JSON as an already-encoded String.
      case "raw" => Some(Value.StrV(ujson.write(inner)))
      case _ => None

    private def get(key: String): Value = inner match
      case obj: ujson.Obj => boxed(obj.value.getOrElse(key, ujson.Null))
      case _ => boxed(ujson.Null)

    private def at(index: Long): Value = inner match
      case array: ujson.Arr if index >= 0 && index < array.value.length => boxed(array.value(index.toInt))
      case _ => boxed(ujson.Null)

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def inputJson(args: List[Value], tolerant: Boolean): ujson.Value = args.headOption match
    case Some(Value.StrV(text)) => if tolerant then parseTolerant(text) else parse(text)
    case Some(Value.ForeignV(box: JsonBox)) => box.inner
    case Some(value) => NativeJsonCodec.toJson(value)
    case None => ujson.Null

  private def lookup(args: List[Value]): Option[Value] = args match
    case Value.ForeignV(box: JsonBox) :: Value.StrV(key) :: Nil => box.inner match
      case obj: ujson.Obj => obj.value.get(key).map(rawValue)
      case _ => None
    case Value.ForeignV(box: JsonBox) :: Value.IntV(index) :: Nil => box.inner match
      case array: ujson.Arr if index >= 0 && index < array.value.length => Some(rawValue(array.value(index.toInt)))
      case _ => None
    case Value.ForeignV(map: collection.Map[?, ?]) :: key :: Nil
        if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]].get(key)
    case (values @ Value.DataV("Cons" | "Nil", _)) :: Value.IntV(index) :: Nil if index >= 0 =>
      unlist(values).lift(index.toInt)
    case _ => None

  def install(context: NativePluginContext): Unit =
    native(context, "jsonValue") { args => Value.ForeignV(JsonBox(inputJson(args, tolerant = true))) }
    native(context, "jsonStringify") { args =>
      val value = args.headOption.getOrElse(throw new RuntimeException("jsonStringify(v)"))
      Value.StrV(NativeJsonCodec.stringify(value))
    }
    native(context, "jsonParse") { args => rawValue(inputJson(args, tolerant = false)) }
    native(context, "jsonRead") { args => Value.ForeignV(JsonBox(inputJson(args, tolerant = false))) }
    native(context, "lookup") { args =>
      lookup(args).getOrElse(throw new RuntimeException("lookup: key not found"))
    }
    native(context, "lookupOpt") { args => lookup(args) match
      case Some(value) => Value.DataV("Some", Vector(value))
      case None => Value.DataV("None", Vector.empty)
    }
