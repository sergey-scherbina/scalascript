package ssc.plugin.ui

import ssc.Value
import ssc.plugin.NativePluginContext

/** The v1.20 typed HTML tag DSL on the native lane — `div(attr.cls := "hero", h1("Welcome"))`.
 *
 *  A direct port of `BuiltinsRuntime`'s tag builders (v1 registers them as core interpreter
 *  globals; the native lane has no such place, so they live in the ui plugin next to the escaping
 *  it already needs). Ported rather than reinvented on purpose: the corpus golden for
 *  `tests/conformance/html-dsl.ssc` comes from the interpreter, so any difference in attribute
 *  ORDER, escaping, or void-tag handling is a diverging row, not a style choice.
 *
 *  This is NOT the declarative UI ABI in `UiNativePlugin` — that renders a `NativeUiElement` tree
 *  and deliberately SORTS its attributes. These tags preserve insertion order, because that is what
 *  the interpreter does and what the golden shows (`class` before `id`, `src` before `alt`).
 *
 *  Node representation is `_Raw(html)`, which the VM already understands: `Runtime.scala` renders
 *  `DataV("_Raw", …)` as its first field in three places, so `println(page)` prints the markup with
 *  no change to Show — the one piece of this that could have made it a much larger job.
 */
private[ui] object HtmlTagDsl:

  private val containerTags = List(
    "html", "head", "body", "title", "style", "script", "main",
    "section", "header", "footer", "nav", "article", "aside",
    "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "dl", "dt", "dd",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th",
    "form", "button", "label", "select", "option", "textarea",
    "figure", "figcaption", "blockquote")

  // `link` is deliberately ABSENT. The actors plugin owns that global (`link(pid)` — process
  // linking), and the native plugin host refuses duplicate ownership outright rather than letting
  // one silently overwrite the other. This is PARITY, not a gap: verified by running `link()` on
  // the interpreter, where the actor version wins too (`link(pid): Unit` from ActorGlobals), so the
  // HTML `<link>` tag is unreachable on v1 as well. If it is ever wanted, it needs a name on both
  // lanes, not a registration here.
  private val voidTags = List("br", "hr", "img", "input", "meta")

  /** `attr.type_` / `attr.for_` / `attr.value_` / `attr.method_` carry the underscore because the
   *  bare names are Scala keywords or collide with common user bindings — same spelling as v1, so
   *  a program moves between lanes unchanged. */
  private val attrKeys = List(
    "cls" -> "class", "id" -> "id", "href" -> "href", "src" -> "src", "alt" -> "alt",
    "name" -> "name", "title" -> "title", "style" -> "style", "type_" -> "type",
    "value_" -> "value", "placeholder" -> "placeholder", "method_" -> "method",
    "action" -> "action", "target" -> "target", "rel" -> "rel", "for_" -> "for",
    "role" -> "role", "colspan" -> "colspan", "rowspan" -> "rowspan", "disabled" -> "disabled")

  /** Byte-for-byte `Interpreter.htmlEscape`, including `'` -> `&#39;`. */
  private def htmlEscape(s: String): String =
    val sb = new StringBuilder
    s.foreach {
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    }
    sb.toString

  private def raw(html: String): Value =
    Value.DataV("_Raw", collection.immutable.ArraySeq(Value.StrV(html)))

  private def field(v: Value, i: Int): Value = v match
    case Value.DataV(_, fs) if fs.length > i => fs(i)
    case _                                   => Value.UnitV

  private def str(v: Value): String = v match
    case Value.StrV(s) => s
    case other         => ssc.Show.show(other)

  /** Trusted html passes through, Lists flatten so `xs.map(li)` composes inside a parent tag,
   *  everything else is shown and escaped — mirroring v1's `renderChild`. */
  private def renderChild(v: Value): String = v match
    case Value.DataV("_Raw", fs) if fs.nonEmpty => str(fs.head)
    case Value.DataV("Cons", _) | Value.DataV("Nil", _) =>
      def go(x: Value, acc: StringBuilder): String = x match
        case Value.DataV("Cons", fs) if fs.length == 2 => acc ++= renderChild(fs(0)); go(fs(1), acc)
        case _                                         => acc.toString
      go(v, new StringBuilder)
    case other => htmlEscape(str(other))

  private def renderTag(name: String, args: List[Value], void: Boolean): Value =
    // LinkedHashMap, not a sorted or plain map: the golden has `class` before `id` and `src`
    // before `alt`, i.e. SOURCE order. A HashMap here passes on some JDKs and not others.
    val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
    val children = new StringBuilder
    def handle(v: Value): Unit = v match
      case a @ Value.DataV("Attr", fs) if fs.length >= 2 => attrs(str(field(a, 0))) = str(field(a, 1))
      case Value.DataV("Cons", fs) if fs.length == 2     => handle(fs(0)); handle(fs(1))
      case Value.DataV("Nil", _)                         => ()
      case other                                         => children ++= renderChild(other)
    args.foreach(handle)
    val attrStr =
      if attrs.isEmpty then ""
      else attrs.map((k, v) => s""" $k="${htmlEscape(v)}"""").mkString
    if void then raw(s"<$name$attrStr>")
    else raw(s"<$name$attrStr>${children.toString}</$name>")

  def install(context: NativePluginContext): Unit =
    context.registerFields("AttrKey", Vector("name"))
    context.registerFields("Attr", Vector("name", "value"))
    context.registerFields("HtmlAttrNs", attrKeys.map(_._1).toVector)

    context.registerValue("attr", Value.DataV("HtmlAttrNs",
      collection.immutable.ArraySeq.from(attrKeys.map { (_, htmlName) =>
        Value.DataV("AttrKey", collection.immutable.ArraySeq(Value.StrV(htmlName)))
      })))

    // `key := value` lowers to a METHOD named `:=` on the receiver (`ssc1-lower.ssc0:2789` emits
    // `__method__(":=", lhs, rhs)`), so it registers as a tagged method rather than a global.
    context.registerTaggedMethod("AttrKey", ":=") {
      case recv :: value :: Nil =>
        Value.DataV("Attr", collection.immutable.ArraySeq(field(recv, 0), Value.StrV(str(value))))
      case _ => throw new IllegalArgumentException("attr.key := value")
    }

    containerTags.foreach { t => context.registerGlobal(t, -1)(args => renderTag(t, args, void = false)) }
    voidTags.foreach     { t => context.registerGlobal(t, -1)(args => renderTag(t, args, void = true)) }
