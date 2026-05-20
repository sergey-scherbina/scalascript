package scalascript.frontend.toolkit

import scalascript.frontend.{View, AttrValue}

/** v1.18 Phase C — Server-side rendering for the toolkit.
 *
 *  `Ssr.renderToHtml(view)` walks a `View` AST and emits a string
 *  of HTML.  Pure function — no DOM access, no signal subscriptions,
 *  no event handler wiring.  Dynamic + signal-bound attributes are
 *  snapshotted at render time; the resulting HTML is a still frame
 *  the browser then hydrates on (any backend) load.
 *
 *  Use cases:
 *    - Static-site generators that pre-render every route at build
 *      time and serve the HTML for instant first-paint.
 *    - SEO — search-crawler bots see content without executing JS.
 *    - Email templates / PDF generation — the same toolkit-tree can
 *      target a non-interactive HTML consumer.
 *    - Snapshot testing — golden HTML strings under a fixed theme +
 *      stable signal states.
 *
 *  Limitations (call site's responsibility):
 *    - Event handlers are dropped (the rendered HTML is non-interactive
 *      until JS hydration runs).
 *    - `ComponentInstance` is rendered via the component's body —
 *      no per-component lifecycle hooks fire.
 *    - `For[T]` / `Show` / `ShowSignal` are evaluated eagerly using
 *      the current signal state.  No subscriptions are set up. */
object Ssr:

  /** Render a `View` tree to an HTML string.  See [[Ssr]] for
   *  semantics + limitations. */
  def renderToHtml(view: View): String =
    val sb = new StringBuilder
    write(sb, view)
    sb.toString

  /** Render a `ToolkitNode` directly — equivalent to
   *  `renderToHtml(Toolkit.lower(node, theme))`. */
  def renderToHtml(node: ToolkitNode, theme: Theme = Theme.default): String =
    renderToHtml(Toolkit.lower(node, theme))

  /** Wrap a toolkit tree in a minimal HTML5 shell ready to drop into
   *  a static site.  `title` goes in `<title>`; the rendered tree
   *  goes in `<body>`.  `extraHead` is appended raw — use for `<meta>`,
   *  `<link>`, etc.  `lang` defaults to `"en"`. */
  def renderDocument(
    body:      ToolkitNode,
    title:     String              = "",
    theme:     Theme               = Theme.default,
    lang:      String               = "en",
    extraHead: String              = ""
  ): String =
    val bodyHtml = renderToHtml(body, theme)
    s"""<!DOCTYPE html>
       |<html lang="${escapeAttr(lang)}">
       |<head>
       |  <meta charset="utf-8">
       |  <meta name="viewport" content="width=device-width,initial-scale=1">
       |  <title>${escapeText(title)}</title>
       |  <style>
       |    body { margin: 0; padding: 0; background: ${theme.colors.background}; color: ${theme.colors.text}; font-family: ${theme.typography.body.fontFamily}; }
       |  </style>
       |  $extraHead
       |</head>
       |<body>$bodyHtml</body>
       |</html>""".stripMargin

  // ─── Implementation ───────────────────────────────────────────

  /** HTML5 void elements — these emit `<tag />` form, never have
   *  closing tags or children. */
  private val voidElements: Set[String] = Set(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "source", "track", "wbr"
  )

  private def write(sb: StringBuilder, v: View): Unit = v match
    case View.Element(tag, attrs, _, children) =>
      sb.append('<').append(tag)
      writeAttrs(sb, attrs)
      if voidElements.contains(tag.toLowerCase) then
        sb.append(" />")
      else
        sb.append('>')
        children.foreach(write(sb, _))
        sb.append("</").append(tag).append('>')

    case View.TextNode(thunk) =>
      sb.append(escapeText(safeRead(thunk, "")))

    case View.SignalText(signal) =>
      sb.append(escapeText(safeRead(() => signal().toString, "")))

    case View.Fragment(children) =>
      children.foreach(write(sb, _))

    case View.Show(cond, whenTrue, whenFalse) =>
      val active =
        if safeRead(cond, false) then whenTrue else whenFalse
      write(sb, safeRead(active, View.Fragment(Nil)))

    case View.ShowSignal(cond, whenTrue, whenFalse) =>
      val active =
        if safeRead(() => cond(), false) then whenTrue else whenFalse
      write(sb, active)

    case View.For(items, render) =>
      val snap = safeRead(items, Seq.empty)
      snap.foreach(item => write(sb, render(item)))

    case View.ComponentInstance(component, props) =>
      // Render the component's body via its render function.  No
      // lifecycle, no scoped state; the body's view AST is walked
      // exactly like any other.
      write(sb, component.render(props))

    case View.ForSignal(items, tag, attrs, itemTemplate) =>
      // ReactiveSignalList exposes only `initial` to JVM-side
      // callers (mutations live in the emitted JS runtime).  SSR
      // renders the frozen-time initial seed — backends hydrate +
      // re-render on mount when the list signal changes.
      val snap = items.initial
      snap.foreach { item =>
        itemTemplate match
          case Some(tmpl) =>
            write(sb, substituteItemText(tmpl, item.toString))
          case None =>
            sb.append('<').append(tag)
            writeAttrs(sb, attrs)
            sb.append('>')
            sb.append(escapeText(item.toString))
            sb.append("</").append(tag).append('>')
      }

    case View.ItemText =>
      // Static occurrence outside a ForSignal template — emit empty.
      ()

    case View.Portal(_, children) =>
      // SSR has no separate mount target; render the portal's
      // children inline.  Backends do the relocation at hydrate time.
      children.foreach(write(sb, _))

  private def writeAttrs(sb: StringBuilder, attrs: Map[String, AttrValue]): Unit =
    attrs.foreach { case (key, value) =>
      value match
        case AttrValue.Str(s) =>
          sb.append(' ').append(key).append("=\"").append(escapeAttr(s)).append('"')
        case AttrValue.Num(n) =>
          sb.append(' ').append(key).append("=\"").append(formatNum(n)).append('"')
        case AttrValue.Bool(true) =>
          // HTML boolean attrs render as bare name (`<input required>`).
          sb.append(' ').append(key)
        case AttrValue.Bool(false) =>
          ()  // boolean false attr is omitted
        case AttrValue.Dynamic(read) =>
          val snap = safeRead(() => read().toString, "")
          sb.append(' ').append(key).append("=\"").append(escapeAttr(snap)).append('"')
        case AttrValue.Absent =>
          ()
        case AttrValue.RefBinding(_) =>
          // Refs are wired by backends at mount time; SSR has no
          // mount step, so we drop the binding.
          ()
    }

  /** Walk a ForSignal item template, replacing every `View.ItemText`
   *  placeholder with a real text node carrying the current item's
   *  stringified value.  Other tree shapes pass through unchanged. */
  private def substituteItemText(tmpl: View, itemValue: String): View = tmpl match
    case View.ItemText =>
      View.TextNode(() => itemValue)
    case View.Element(tag, attrs, events, kids) =>
      View.Element(tag, attrs, events, kids.map(substituteItemText(_, itemValue)))
    case View.Fragment(kids) =>
      View.Fragment(kids.map(substituteItemText(_, itemValue)))
    case other =>
      other

  /** Format a Double without trailing `.0` for integer values — `1.0`
   *  renders as `1`, `1.5` as `1.5`.  Matches what HTML attribute
   *  values typically look like. */
  private def formatNum(n: Double): String =
    if n.isWhole && !n.isInfinite then n.toLong.toString
    else n.toString

  /** Try a thunk; return `fallback` on any throw.  Mirrors the
   *  defensive pattern used in widget lowerings — SSR shouldn't blow
   *  up because a signal hasn't been initialised yet. */
  private def safeRead[T](thunk: () => T, fallback: T): T =
    try thunk() catch case _: Throwable => fallback

  /** Escape `&`, `<`, `>` for text-node content. */
  private[toolkit] def escapeText(s: String): String =
    if s == null then ""
    else
      val sb = new StringBuilder(s.length)
      var i  = 0
      while i < s.length do
        s.charAt(i) match
          case '&' => sb.append("&amp;")
          case '<' => sb.append("&lt;")
          case '>' => sb.append("&gt;")
          case c   => sb.append(c)
        i += 1
      sb.toString

  /** Escape for attribute values — same as text plus `"` (we always
   *  quote with `"` so `'` is safe). */
  private[toolkit] def escapeAttr(s: String): String =
    if s == null then ""
    else
      val sb = new StringBuilder(s.length)
      var i  = 0
      while i < s.length do
        s.charAt(i) match
          case '&'  => sb.append("&amp;")
          case '<'  => sb.append("&lt;")
          case '>'  => sb.append("&gt;")
          case '"'  => sb.append("&quot;")
          case c    => sb.append(c)
        i += 1
      sb.toString
