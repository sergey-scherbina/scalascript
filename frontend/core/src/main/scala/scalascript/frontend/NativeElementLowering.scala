package scalascript.frontend

/** Normalizes the HTML-like View.Element shapes produced by std/ui lowerings
 *  into semantic View controls that native emitters and runtimes understand. */
object NativeElementLowering:

  def lower(view: View[?]): View[?] =
    view match
      case View.Element(tag, attrs, events, children) =>
        lowerElement(tag.toLowerCase, attrs, events, children)
      case View.Styled(child, style) =>
        View.Styled(lower(child), style)
      case View.Column(children, spacing, align, style) =>
        View.Column(children.map(lower), spacing, align, style)
      case View.Row(children, spacing, align, style) =>
        View.Row(children.map(lower), spacing, align, style)
      case View.Stack(children, style) =>
        View.Stack(children.map(lower), style)
      case View.ScrollView(child, axis, style) =>
        View.ScrollView(lower(child), axis, style)
      case View.Fragment(children) =>
        View.Fragment(children.map(lower))
      case View.Show(cond, whenTrue, whenFalse) =>
        View.Show(cond, () => lower(whenTrue()), () => lower(whenFalse()))
      case View.ShowSignal(cond, whenTrue, whenFalse) =>
        View.ShowSignal(cond, lower(whenTrue), lower(whenFalse))
      case View.For(items, render) =>
        View.For(items, item => lower(render(item)))
      case View.Adaptive(web, desktop, mobile, fallback) =>
        View.Adaptive(web.map(lower), desktop.map(lower), mobile.map(lower), lower(fallback))
      case other => other

  private def lowerElement(
      tag:      String,
      attrs:    Map[String, AttrValue],
      events:   Map[String, EventHandler],
      children: Seq[View[?]]
  ): View[?] =
    tag match
      case "div" =>
        val lowered = children.map(lower)
        val style   = stringAttr(attrs, "style").getOrElse("")
        val gap     = cssPx(style, "gap").getOrElse(0.0)
        if style.contains("flex-direction:row") then View.Row(lowered, spacing = gap)
        else if style.contains("flex-direction:column") then View.Column(lowered, spacing = gap)
        else View.Fragment(lowered)

      case "p" | "span" =>
        View.Fragment(children.map(lower))

      case "h1" | "h2" | "h3" | "h4" | "h5" | "h6" =>
        View.Text(() => plainText(children))

      case "label" =>
        checkboxChild(children)
          .map((signal, label) => View.Toggle(signal, label))
          .getOrElse(View.Fragment(children.map(lower)))

      case "input" =>
        stringAttr(attrs, "type").getOrElse("text") match
          case "checkbox" =>
            reactiveAttr[Boolean](attrs, "checked")
              .map(signal => View.Toggle(signal, ""))
              .getOrElse(View.Fragment(Nil))
          case "password" =>
            reactiveAttr[String](attrs, "value")
              .map(signal => View.TextInput(signal, stringAttr(attrs, "placeholder").getOrElse(""), secure = true))
              .getOrElse(View.Fragment(Nil))
          case _ =>
            reactiveAttr[String](attrs, "value")
              .map(signal => View.TextInput(signal, stringAttr(attrs, "placeholder").getOrElse("")))
              .getOrElse(View.Fragment(Nil))

      case "button" =>
        val action = events.getOrElse("click", EventHandler.Simple(() => ()))
        val disabled = boolAttr(attrs, "disabled").getOrElse(false) ||
          boolAttr(attrs, "aria-disabled").getOrElse(false)
        View.Button(View.Text(() => plainText(children)), action, enabled = () => !disabled)

      case "hr" =>
        View.Divider()

      case _ =>
        View.Fragment(children.map(lower))

  private def checkboxChild(children: Seq[View[?]]): Option[(ReactiveSignal[Boolean], String)] =
    val signal = children.collectFirst {
      case View.Element("input", attrs, _, _) if stringAttr(attrs, "type").contains("checkbox") =>
        reactiveAttr[Boolean](attrs, "checked")
    }.flatten
    signal.map(_ -> plainText(children.filterNot {
      case View.Element("input", attrs, _, _) => stringAttr(attrs, "type").contains("checkbox")
      case _ => false
    }))

  private def plainText(children: Seq[View[?]]): String =
    children.map {
      case View.Text(content, _) => content()
      case View.TextNode(value) => value()
      case View.SignalText(signal, _) => String.valueOf(signal())
      case View.Element(_, _, _, nested) => plainText(nested)
      case View.Fragment(nested) => plainText(nested)
      case View.Styled(child, _) => plainText(Seq(child))
      case _ => ""
    }.mkString

  private def stringAttr(attrs: Map[String, AttrValue], name: String): Option[String] =
    attrs.get(name).collect {
      case AttrValue.Str(value) => value
      case AttrValue.Num(value) => value.toString
      case AttrValue.Bool(value) => value.toString
    }

  private def boolAttr(attrs: Map[String, AttrValue], name: String): Option[Boolean] =
    attrs.get(name).collect {
      case AttrValue.Bool(value) => value
      case AttrValue.Str(value) => value.equalsIgnoreCase("true")
    }

  private def reactiveAttr[A](attrs: Map[String, AttrValue], name: String): Option[ReactiveSignal[A]] =
    attrs.get(name).collect {
      case AttrValue.Reactive(signal) => signal.asInstanceOf[ReactiveSignal[A]]
    }

  private def cssPx(style: String, name: String): Option[Double] =
    val prefix = name + ":"
    style
      .split(";")
      .iterator
      .map(_.trim)
      .find(_.startsWith(prefix))
      .flatMap { raw =>
        val value = raw.drop(prefix.length).trim.stripSuffix("px")
        value.toDoubleOption
      }
