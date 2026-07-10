package ssc.plugin.ui

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import ssc.{Done, Runtime, Show, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free signals, view values, and deterministic static UI emission. */
final class UiNativePlugin extends NativePlugin:
  def id: String = "55-ui"

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private abstract class NativeSignal(val id: String, context: NativePluginContext)
      extends Value.NamedMethodObj:
    def read(): Value
    def write(value: Value): Unit
    def underlying: AnyRef = this
    def getField(name: String): Option[Value] = name match
      case "apply" | "get" => Some(closure(0)(_ => read()))
      case "set" => Some(closure(1) {
        case List(value) => write(value); Value.UnitV
        case _ => throw new RuntimeException("Signal.set(value)")
      })
      case "update" => Some(closure(1) {
        case List(fn) => write(context.invoke(fn, List(read()))); Value.UnitV
        case _ => throw new RuntimeException("Signal.update(fn)")
      })
      case "id" => Some(Value.StrV(id))
      case _ => None

  private final class MutableSignal(id: String, initial: Value, context: NativePluginContext)
      extends NativeSignal(id, context):
    private var current = initial
    def read(): Value = synchronized(current)
    def write(value: Value): Unit = synchronized { current = value }

  private final class DerivedSignal(id: String, compute: () => Value, context: NativePluginContext)
      extends NativeSignal(id, context):
    def read(): Value = compute()
    def write(value: Value): Unit =
      throw new RuntimeException(s"native derived signal '$id' is read-only")

  /** Declarative browser-fetch signal for the standard JVM/static UI lane.
   * Network execution belongs to the emitted browser runtime; the JVM value is
   * still a real readable Signal so composed helpers can be checked and built
   * without a compatibility fallback. */
  private final class FetchSignal(
      id: String,
      val url: String,
      val refresh: NativeSignal,
      val headers: NativeSignal,
      context: NativePluginContext)
      extends NativeSignal(id, context):
    def read(): Value = Value.StrV("")
    def write(value: Value): Unit =
      throw new RuntimeException(s"native fetch signal '$id' is read-only")

  private def signal(value: Value, operation: String): NativeSignal = value match
    case Value.ForeignV(signal: NativeSignal) => signal
    case _ => throw new RuntimeException(s"$operation argument 1 must be Signal")

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def unlist(value: Value, operation: String): List[Value] =
    val out = mutable.ListBuffer.empty[Value]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(head, tail)) => out += head; current = tail
        case Value.DataV("Nil", _) => done = true
        case _ => throw new RuntimeException(s"$operation expected a valid List")
    out.toList

  private def valueMap(value: Value, operation: String): collection.Map[Value, Value] = value match
    case Value.ForeignV(map: collection.Map[?, ?]) if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]]
    case _ => throw new RuntimeException(s"$operation expected Map[String, Any]")

  private def scalar(value: Value): String = value match
    case Value.StrV(text) => text
    case Value.IntV(number) => number.toString
    case Value.BigV(number) => number.toString
    case Value.FloatV(number) => number.toString
    case Value.BoolV(boolean) => boolean.toString
    case Value.UnitV => ""
    case Value.ForeignV(signal: NativeSignal) => scalar(signal.read())
    case Value.ForeignV(decimal: java.math.BigDecimal) => decimal.toPlainString
    case other => Show.show(other)

  private def escapeText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def escapeAttribute(text: String): String =
    escapeText(text).replace("\"", "&quot;").replace("'", "&#39;")

  private val validTag = "[A-Za-z][A-Za-z0-9:-]*".r
  private val voidTags = Set("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

  private def render(value: Value): String = value match
    case Value.DataV("NativeUiText", Seq(Value.StrV(text))) => escapeText(text)
    case Value.DataV("NativeUiSignalText", Seq(rawSignal)) => escapeText(scalar(rawSignal))
    case Value.DataV("NativeUiShow", Seq(rawSignal, whenTrue, whenFalse)) =>
      signal(rawSignal, "showSignal").read() match
        case Value.BoolV(true) => render(whenTrue)
        case _ => render(whenFalse)
    case Value.DataV("NativeUiFragment", Seq(children)) =>
      unlist(children, "fragment").map(render).mkString
    case Value.DataV("NativeUiElement", Seq(Value.StrV(tag), attrs, _, children)) =>
      if !validTag.matches(tag) then throw new RuntimeException(s"invalid native UI tag: $tag")
      val renderedAttrs = valueMap(attrs, "element attrs").iterator.collect {
        case (Value.StrV(key), Value.BoolV(false) | Value.UnitV) => None
        case (Value.StrV(key), Value.BoolV(true)) => Some(key -> key)
        case (Value.StrV(key), attrValue) => Some(key -> scalar(attrValue))
        case _ => throw new RuntimeException("native UI attribute names must be String")
      }.flatten.toList.sortBy(_._1).map { case (key, attrValue) =>
        s" ${escapeAttribute(key)}=\"${escapeAttribute(attrValue)}\""
      }.mkString
      val open = s"<$tag$renderedAttrs>"
      if voidTags(tag.toLowerCase) then open
      else open + unlist(children, "element children").map(render).mkString + s"</$tag>"
    case _ => throw new RuntimeException("native UI emit expected a View value")

  def install(context: NativePluginContext): Unit =
    context.registerFields("NativeUiText", Vector("text"))
    context.registerFields("NativeUiSignalText", Vector("signal"))
    context.registerFields("NativeUiShow", Vector("condition", "whenTrue", "whenFalse"))
    context.registerFields("NativeUiFragment", Vector("children"))
    context.registerFields("NativeUiElement", Vector("tag", "attrs", "events", "children"))
    context.registerFields("NativeUiEvent", Vector("kind", "signal", "value"))
    context.registerFields("NativeUiFetchAction", Vector("method", "url", "body", "onSuccessTick", "headers"))

    val emptyHeaders = Value.ForeignV(MutableSignal("__empty_headers__", Value.StrV(""), context))
    context.registerValue("emptyHeaders", emptyHeaders)

    native(context, "signal") { args =>
      if args.length != 2 then throw new RuntimeException("signal(name, default)")
      Value.ForeignV(MutableSignal(text(args, 0, "signal"), args(1), context))
    }
    native(context, "computedSignal") { args => args match
      case List(callback) => Value.ForeignV(DerivedSignal(
        "__computed__", () => Value.StrV(scalar(context.invoke(callback, Nil))), context))
      case _ => throw new RuntimeException("computedSignal(callback)")
    }
    native(context, "eqSignal") { args =>
      if args.length != 2 then throw new RuntimeException("eqSignal(signal, value)")
      val source = signal(args.head, "eqSignal")
      Value.ForeignV(DerivedSignal(s"${source.id}__eq__", () => Value.BoolV(source.read() == args(1)), context))
    }
    native(context, "hashSignal") { args =>
      if args.nonEmpty then throw new RuntimeException("hashSignal()")
      Value.ForeignV(MutableSignal("__hash__", Value.StrV(""), context))
    }

    native(context, "fetchUrlSignal") { args =>
      if args.length != 3 && args.length != 4 then
        throw new RuntimeException("fetchUrlSignal(name, url, refreshTick[, headers])")
      val refresh = signal(args(2), "fetchUrlSignal refreshTick")
      val headers = signal(args.lift(3).getOrElse(emptyHeaders), "fetchUrlSignal headers")
      Value.ForeignV(FetchSignal(
        text(args, 0, "fetchUrlSignal"),
        text(args, 1, "fetchUrlSignal"),
        refresh,
        headers,
        context))
    }
    native(context, "fetchAction") { args =>
      if args.length != 4 && args.length != 5 then
        throw new RuntimeException("fetchAction(method, url, body, onSuccessTick[, headers])")
      signal(args(2), "fetchAction body")
      signal(args(3), "fetchAction onSuccessTick")
      val headers = args.lift(4).getOrElse(emptyHeaders)
      signal(headers, "fetchAction headers")
      Value.DataV("NativeUiFetchAction", Vector(
        Value.StrV(text(args, 0, "fetchAction")),
        Value.StrV(text(args, 1, "fetchAction")),
        args(2),
        args(3),
        headers))
    }

    native(context, "textNode") { args =>
      Value.DataV("NativeUiText", Vector(Value.StrV(text(args, 0, "textNode"))))
    }
    native(context, "signalText") { args =>
      val source = args.headOption.getOrElse(throw new RuntimeException("signalText(signal)"))
      signal(source, "signalText")
      Value.DataV("NativeUiSignalText", Vector(source))
    }
    native(context, "showSignal") { args => args match
      case List(condition, whenTrue, whenFalse) =>
        signal(condition, "showSignal")
        Value.DataV("NativeUiShow", Vector(condition, whenTrue, whenFalse))
      case _ => throw new RuntimeException("showSignal(condition, whenTrue, whenFalse)")
    }
    native(context, "fragment") { args =>
      val children = args.headOption.getOrElse(throw new RuntimeException("fragment(children)"))
      unlist(children, "fragment")
      Value.DataV("NativeUiFragment", Vector(children))
    }
    native(context, "element") { args => args match
      case List(Value.StrV(tag), attrs, events, children) =>
        valueMap(attrs, "element attrs")
        valueMap(events, "element events")
        unlist(children, "element children")
        Value.DataV("NativeUiElement", Vector(Value.StrV(tag), attrs, events, children))
      case _ => throw new RuntimeException("element(tag, attrs, events, children)")
    }

    native(context, "setSignal") { args =>
      if args.length != 2 then throw new RuntimeException("setSignal(signal, value)")
      signal(args.head, "setSignal")
      Value.DataV("NativeUiEvent", Vector(Value.StrV("set"), args.head, args(1)))
    }
    native(context, "inputChange") { args =>
      val source = args.headOption.getOrElse(throw new RuntimeException("inputChange(signal)"))
      signal(source, "inputChange")
      Value.DataV("NativeUiEvent", Vector(Value.StrV("input"), source, Value.UnitV))
    }
    native(context, "toggleSignal") { args =>
      val source = args.headOption.getOrElse(throw new RuntimeException("toggleSignal(signal)"))
      signal(source, "toggleSignal")
      Value.DataV("NativeUiEvent", Vector(Value.StrV("toggle"), source, Value.UnitV))
    }

    native(context, "emit") { args => args match
      case List(tree, Value.StrV(outDir)) =>
        val output = Path.of(outDir).resolve("index.html")
        Files.createDirectories(output.getParent)
        Files.writeString(output, s"<!doctype html>\n${render(tree)}\n", StandardCharsets.UTF_8)
        Value.UnitV
      case _ => throw new RuntimeException("emit(tree, outDir)")
    }
