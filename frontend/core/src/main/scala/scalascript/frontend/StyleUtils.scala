package scalascript.frontend

/** CSS generation helpers shared by all web-target emitters (React, Vue, Solid, Custom).
 *
 *  Every function converts a Scala `Style` sub-record to an equivalent
 *  inline CSS string.  The string format is intentionally plain
 *  CSS (`property: value; ...`) — each emitter applies its own
 *  downstream conversion where needed (e.g. React's camelCase object). */
object StyleUtils:

  // ── Top-level converter ─────────────────────────────────────────────────────

  def styleToCSS(s: Style): String =
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]

    // Layout
    val l = s.layout
    if l.padding != EdgeInsets.zero  then parts += s"padding: ${edgeInsetsToCSS(l.padding)}"
    if l.margin  != EdgeInsets.zero  then parts += s"margin: ${edgeInsetsToCSS(l.margin)}"
    dimensionToCSS(l.width) .foreach(v => parts += s"width: $v")
    dimensionToCSS(l.height).foreach(v => parts += s"height: $v")
    l.minWidth .foreach(v  => parts += s"min-width: ${v}px")
    l.maxWidth .foreach(v  => parts += s"max-width: ${v}px")
    l.minHeight.foreach(v  => parts += s"min-height: ${v}px")
    l.maxHeight.foreach(v  => parts += s"max-height: ${v}px")
    l.flex      .foreach(v => parts += s"flex: $v")
    l.flexShrink.foreach(v => parts += s"flex-shrink: $v")
    l.flexBasis .foreach(d => dimensionToCSS(d).foreach(v => parts += s"flex-basis: $v"))
    l.alignSelf .foreach(a => parts += s"align-self: ${alignToCSS(a)}")
    l.gap       .foreach(v => parts += s"gap: ${v}px")
    l.zIndex    .foreach(v => parts += s"z-index: $v")

    // Text
    val t = s.text
    t.fontSize    .foreach(v => parts += s"font-size: ${v}px")
    t.fontWeight  .foreach(w => parts += s"font-weight: ${fontWeightToCSS(w)}")
    t.fontFamily  .foreach(f => parts += s"font-family: $f")
    if t.fontStyle == FontStyle.Italic then parts += "font-style: italic"
    t.lineHeight  .foreach(v => parts += s"line-height: $v")
    t.letterSpacing.foreach(v => parts += s"letter-spacing: ${v}px")
    if t.textDecoration.nonEmpty then
      parts += s"text-decoration: ${t.textDecoration.map(textDecorationToCSS).mkString(" ")}"
    if t.textAlign != TextAlign.Start then parts += s"text-align: ${textAlignToCSS(t.textAlign)}"
    if t.textOverflow == TextOverflow.Ellipsis then
      parts += "text-overflow: ellipsis"
      parts += "overflow: hidden"
      parts += "white-space: nowrap"
    t.maxLines.foreach(n => parts += s"-webkit-line-clamp: $n")
    t.foreground.foreach(c => parts += s"color: ${colorToCSS(c)}")

    // Decoration
    val d = s.decoration
    d.background .foreach(c => parts += s"background: ${colorToCSS(c)}")
    d.borderColor.foreach(c => parts += s"border-color: ${colorToCSS(c)}")
    d.borderWidth.foreach(w => parts += s"border-width: ${w}px")
    if d.borderRadius != BorderRadius.zero then
      parts += s"border-radius: ${borderRadiusToCSS(d.borderRadius)}"
    if d.borderStyle != BorderLineStyle.None && (d.borderWidth.isDefined || d.borderColor.isDefined) then
      parts += s"border-style: ${borderStyleToCSS(d.borderStyle)}"

    // Effects
    val e = s.effects
    e.shadow.foreach(sh => parts += s"box-shadow: ${shadowToCSS(sh)}")
    if e.opacity != 1.0 then parts += s"opacity: ${e.opacity}"
    if e.overflow != Overflow.Visible then parts += s"overflow: ${overflowToCSS(e.overflow)}"
    e.cursor.foreach(c => parts += s"cursor: ${cursorToCSS(c)}")

    // Transforms
    if s.transform.nonEmpty then
      parts += s"transform: ${s.transform.map(transformToCSS).mkString(" ")}"

    // Animation
    s.animation.foreach { a =>
      val delay = if a.delay > 0 then s" ${a.delay.toLong}ms" else ""
      parts += s"transition: all ${a.duration.toLong}ms ${curveToCSS(a.curve)}$delay"
    }

    // Web-only overrides (last — win against any computed rule above)
    s.web.foreach { (k, v) => parts += s"$k: $v" }

    parts.filter(_.nonEmpty).mkString("; ")

  // ── Merge helpers ───────────────────────────────────────────────────────────

  def mergeCSS(base: String, extra: String): String =
    if extra.isEmpty then base
    else if base.isEmpty then extra
    else s"$base; $extra"

  // ── Alignment ───────────────────────────────────────────────────────────────

  def hAlignToCSS(a: HAlign): String = a match
    case HAlign.Start   => "flex-start"
    case HAlign.Center  => "center"
    case HAlign.End     => "flex-end"
    case HAlign.Stretch => "stretch"

  def vAlignToCSS(a: VAlign): String = a match
    case VAlign.Top     => "flex-start"
    case VAlign.Center  => "center"
    case VAlign.Bottom  => "flex-end"
    case VAlign.Stretch => "stretch"

  // ── Color ───────────────────────────────────────────────────────────────────

  def colorToCSS(c: Color): String = c match
    case Color.Hex(v)             => v
    case Color.Rgb(r, g, b)       => s"rgb($r, $g, $b)"
    case Color.Rgba(r, g, b, a)   => s"rgba($r, $g, $b, $a)"
    case Color.Named(n)           => n
    case Color.System(token)      => s"var(--color-$token)"
    case Color.Transparent        => "transparent"

  // ── Sub-record helpers ──────────────────────────────────────────────────────

  def edgeInsetsToCSS(e: EdgeInsets): String =
    if e.top == e.bottom && e.left == e.right && e.top == e.left then
      s"${e.top}px"
    else
      s"${e.top}px ${e.right}px ${e.bottom}px ${e.left}px"

  def dimensionToCSS(d: Dimension): Option[String] = d match
    case Dimension.Auto         => None
    case Dimension.Fixed(v)     => Some(s"${v}px")
    case Dimension.Fraction(f)  => Some(s"${(f * 100).toInt}%")
    case Dimension.Fill         => Some("100%")

  def fontWeightToCSS(w: FontWeight): String = w match
    case FontWeight.Thin       => "100"
    case FontWeight.ExtraLight => "200"
    case FontWeight.Light      => "300"
    case FontWeight.Regular    => "400"
    case FontWeight.Medium     => "500"
    case FontWeight.SemiBold   => "600"
    case FontWeight.Bold       => "700"
    case FontWeight.ExtraBold  => "800"
    case FontWeight.Black      => "900"
    case FontWeight.Custom(v)  => v.toString

  def borderRadiusToCSS(r: BorderRadius): String =
    s"${r.topLeft}px ${r.topRight}px ${r.bottomRight}px ${r.bottomLeft}px"

  def shadowToCSS(s: Shadow): String =
    s"${s.offsetX}px ${s.offsetY}px ${s.blur}px ${s.spread}px ${colorToCSS(s.color)}"

  def transformToCSS(t: Transform): String = t match
    case Transform.Rotate(deg)     => s"rotate(${deg}deg)"
    case Transform.Scale(x, y)     => s"scale($x, $y)"
    case Transform.Translate(x, y) => s"translate(${x}px, ${y}px)"
    case Transform.SkewX(deg)      => s"skewX(${deg}deg)"
    case Transform.SkewY(deg)      => s"skewY(${deg}deg)"

  def curveToCSS(c: Curve): String = c match
    case Curve.Linear                   => "linear"
    case Curve.EaseIn                   => "ease-in"
    case Curve.EaseOut                  => "ease-out"
    case Curve.EaseInOut                => "ease-in-out"
    case Curve.Spring(stiffness, damp)  =>
      // Approximate spring with cubic-bezier — good enough for web rendering.
      val p2 = (1.0 - damp / (2.0 * scala.math.sqrt(stiffness * damp))).min(1.0).max(0.0)
      f"cubic-bezier(0.34, $p2%.2f, 0.64, 1)"

  def overflowToCSS(o: Overflow): String = o match
    case Overflow.Visible => "visible"
    case Overflow.Hidden  => "hidden"
    case Overflow.Scroll  => "scroll"
    case Overflow.Auto    => "auto"

  def borderStyleToCSS(bs: BorderLineStyle): String = bs match
    case BorderLineStyle.Solid  => "solid"
    case BorderLineStyle.Dashed => "dashed"
    case BorderLineStyle.Dotted => "dotted"
    case BorderLineStyle.None   => "none"

  def cursorToCSS(c: Cursor): String = c match
    case Cursor.Default    => "default"
    case Cursor.Pointer    => "pointer"
    case Cursor.Text       => "text"
    case Cursor.Grab       => "grab"
    case Cursor.Grabbing   => "grabbing"
    case Cursor.NotAllowed => "not-allowed"
    case Cursor.Crosshair  => "crosshair"

  def gridColumnsToCSS(g: GridColumns): String = g match
    case GridColumns.Fixed(n)       => s"repeat($n, 1fr)"
    case GridColumns.Adaptive(minW) => s"repeat(auto-fill, minmax(${minW}px, 1fr))"
    case GridColumns.Fill           => "1fr"

  // ── Private helpers ─────────────────────────────────────────────────────────

  private def alignToCSS(a: Align): String = a match
    case Align.Start    => "flex-start"
    case Align.Center   => "center"
    case Align.End      => "flex-end"
    case Align.Stretch  => "stretch"
    case Align.Baseline => "baseline"

  private def textDecorationToCSS(td: TextDecoration): String = td match
    case TextDecoration.Underline     => "underline"
    case TextDecoration.Strikethrough => "line-through"
    case TextDecoration.Overline      => "overline"

  private def textAlignToCSS(ta: TextAlign): String = ta match
    case TextAlign.Start   => "left"
    case TextAlign.Center  => "center"
    case TextAlign.End     => "right"
    case TextAlign.Justify => "justify"
