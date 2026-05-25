package scalascript.frontend.swing

import scalascript.frontend.*

/** JDK-only Swing desktop frontend backend.
 *
 *  The Swing emitter covers the first static toolkit subset: text/labels,
 *  buttons, text fields, checkboxes, vertical/horizontal stacks, spacers,
 *  dividers, scroll views, and basic layout/style defaults. Action dispatch
 *  remains Phase 3 work. */
final class SwingFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "swing"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState
  )

  override def jsDeps: List[JsDep] = Nil

  override def supportedPlatforms: Set[Platform] = Set(
    Platform.Desktop(DesktopOs.MacOS),
    Platform.Desktop(DesktopOs.Linux),
    Platform.Desktop(DesktopOs.Windows)
  )

  override def emit(module: FrontendModule): EmittedSpa =
    throw UnsupportedOperationException(
      "swing is a JVM desktop frontend; use emitNative(..., Platform.Desktop(...))"
    )

  override def emitNative(
    module:   FrontendModule,
    platform: Platform
  ): Option[EmittedArtifact.NativeApp] =
    platform match
      case Platform.Desktop(_) | Platform.All =>
        val manifest = module.appManifest.getOrElse(
          AppManifest("com.example.app", "ScalaScript App", "1.0.0")
        )
        Some(EmittedArtifact.NativeApp(
          sources     = Map("src/main/scala/Main.scala" -> SwingEmitter.mainScala(module, manifest)),
          resources   = Map.empty,
          buildScript = "scala-cli run .",
          manifest    = manifest,
          format      = AppFormat.SwingApp,
          target      = platform
        ))
      case _ => None

private object SwingEmitter:

  def mainScala(module: FrontendModule, manifest: AppManifest): String =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val root = entry.body(())
    val body = emitBuilder("root", root, indent = 4)
    s"""//> using scala 3.8.3
       |//> using option -Wunused:all -deprecation -feature
       |
       |import java.awt.BorderLayout
       |import java.awt.Color
       |import java.awt.Dimension
       |import java.awt.Font
       |import javax.swing.*
       |
       |object Main:
       |  def main(args: Array[String]): Unit =
       |    SwingUtilities.invokeLater { () =>
       |      val frame = JFrame("${scalaString(manifest.displayName)}")
       |      frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
       |
       |      val root = JPanel()
       |      root.setLayout(BoxLayout(root, BoxLayout.Y_AXIS))
       |      root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
       |$body
       |
       |      frame.getContentPane.add(root, BorderLayout.CENTER)
       |      frame.setSize(640, 420)
       |      frame.setLocationRelativeTo(null)
       |      frame.setVisible(true)
       |    }
       |
       |  private def setSizeHints(component: JComponent, width: Int, height: Int): Unit =
       |    val size = Dimension(width, height)
       |    component.setPreferredSize(size)
       |    component.setMinimumSize(size)
       |
       |""".stripMargin

  private def emitBuilder(parent: String, view: View[?], indent: Int): String =
    val pad = " " * indent
    view match
      case View.Text(content, style) =>
        emitComponent(parent, "label", s"""JLabel("${scalaString(content())}")""", style, indent)
      case View.TextNode(value) =>
        emitComponent(parent, "label", s"""JLabel("${scalaString(value())}")""", Style(), indent)
      case View.SignalText(signal, style) =>
        emitComponent(parent, "label", s"""JLabel("${scalaString(signal.apply())}")""", style, indent)
      case View.Button(label, _, enabled, style) =>
        emitComponent(parent, "button", s"""{
           |$pad  val button = JButton("${scalaString(textOf(label))}")
           |$pad  button.setEnabled(${enabled()})
           |$pad  button
           |$pad}""".stripMargin, style, indent)
      case View.TextInput(value, placeholder, multiline, secure, style) =>
        emitTextInput(parent, value, placeholder, multiline, secure, style, indent)
      case View.Toggle(checked, label, style) =>
        emitComponent(parent, "checkbox", s"""JCheckBox("${scalaString(label)}", ${checked.apply()})""", style, indent)
      case View.Column(children, spacing, _, style) =>
        emitPanel(parent, children, axis = "BoxLayout.Y_AXIS", spacing, style, indent)
      case View.Row(children, spacing, _, style) =>
        emitPanel(parent, children, axis = "BoxLayout.X_AXIS", spacing, style, indent)
      case View.ScrollView(child, axis, style) =>
        val childBody = emitPanel("scrollPanel", Seq(child), "BoxLayout.Y_AXIS", 0, Style(), indent + 2)
        emitComponent(parent, "scrollPane", s"""{
           |$pad  val scrollPanel = JPanel()
           |$pad  scrollPanel.setLayout(BoxLayout(scrollPanel, BoxLayout.Y_AXIS))
           |$childBody
           |$pad  val scrollPane = JScrollPane(scrollPanel)
           |$pad  scrollPane.setHorizontalScrollBarPolicy(${scrollPolicy(axis, horizontal = true)})
           |$pad  scrollPane.setVerticalScrollBarPolicy(${scrollPolicy(axis, horizontal = false)})
           |$pad  scrollPane
           |$pad}""".stripMargin, style, indent)
      case View.Divider(axis, style) =>
        emitDivider(parent, axis, style, indent)
      case View.Fragment(children) =>
        children.map(emitBuilder(parent, _, indent)).mkString("\n")
      case View.Show(cond, whenTrue, whenFalse) =>
        emitBuilder(parent, if cond() then whenTrue() else whenFalse(), indent)
      case View.ShowSignal(cond, whenTrue, whenFalse) =>
        emitBuilder(parent, if cond.apply() then whenTrue else whenFalse, indent)
      case View.For(items, render) =>
        items().map(item => emitBuilder(parent, render(item), indent)).mkString("\n")
      case View.Styled(child, style) =>
        child match
          case View.Text(content, _)          => emitComponent(parent, "label", s"""JLabel("${scalaString(content())}")""", style, indent)
          case View.TextNode(value)           => emitComponent(parent, "label", s"""JLabel("${scalaString(value())}")""", style, indent)
          case View.SignalText(signal, _)     => emitComponent(parent, "label", s"""JLabel("${scalaString(signal.apply())}")""", style, indent)
          case View.Button(label, _, enabled, _) =>
            emitComponent(parent, "button", s"""{
               |$pad  val button = JButton("${scalaString(textOf(label))}")
               |$pad  button.setEnabled(${enabled()})
               |$pad  button
               |$pad}""".stripMargin, style, indent)
          case View.TextInput(value, placeholder, multiline, secure, _) =>
            emitTextInput(parent, value, placeholder, multiline, secure, style, indent)
          case View.Toggle(checked, label, _) =>
            emitComponent(parent, "checkbox", s"""JCheckBox("${scalaString(label)}", ${checked.apply()})""", style, indent)
          case View.Column(children, s, _, _) => emitPanel(parent, children, "BoxLayout.Y_AXIS", s, style, indent)
          case View.Row(children, s, _, _)    => emitPanel(parent, children, "BoxLayout.X_AXIS", s, style, indent)
          case View.Divider(axis, _)          => emitDivider(parent, axis, style, indent)
          case _                              => emitBuilder(parent, child, indent)
      case View.Adaptive(_, desktop, _, fallback) =>
        emitBuilder(parent, desktop.getOrElse(fallback), indent)
      case View.Spacer(size) =>
        val px = size.map(_.round.toInt).getOrElse(8)
        s"""$pad$parent.add(Box.createRigidArea(Dimension($px, $px)))"""
      case other =>
        emitComponent(parent, "unsupported", s"""JLabel("${scalaString(s"[unsupported Swing view: ${other.productPrefix}]")}")""", Style(), indent)

  private def emitPanel(parent: String, children: Seq[View[?]], axis: String, spacing: Double, style: Style, indent: Int): String =
    val pad = " " * indent
    val childPad = " " * (indent + 2)
    val spacerDimension =
      if axis == "BoxLayout.X_AXIS" then s"Dimension(${spacing.round.toInt}, 0)"
      else s"Dimension(0, ${spacing.round.toInt})"
    val childBody =
      children.zipWithIndex.map { (child, idx) =>
        val rendered = emitBuilder("panel", child, indent + 2)
        val spacer =
          if spacing > 0 && idx < children.size - 1 then
            s"\n${childPad}panel.add(Box.createRigidArea($spacerDimension))"
          else ""
        rendered + spacer
      }.mkString("\n")
    s"""$pad${parent}.add({
       |$pad  val panel = JPanel()
       |$pad  panel.setLayout(BoxLayout(panel, $axis))
       |${emitStyle("panel", style, indent + 2)}
       |$childBody
       |$pad  panel
       |$pad})""".stripMargin

  private def emitComponent(parent: String, name: String, constructor: String, style: Style, indent: Int): String =
    val pad = " " * indent
    s"""$pad${parent}.add({
       |$pad  val $name = $constructor
       |${emitStyle(name, style, indent + 2)}
       |$pad  $name
       |$pad})""".stripMargin

  private def emitStyle(name: String, style: Style, indent: Int): String =
    val pad = " " * indent
    val lines = List(
      emitForeground(name, style.text.foreground, pad),
      emitFont(name, style.text, pad),
      emitBackground(name, style.decoration.background, pad),
      emitBorder(name, style, pad),
      emitSizeHints(name, style.layout, pad),
      style.a11y.label.map(label => s"""${pad}${name}.getAccessibleContext.setAccessibleName("${scalaString(label)}")""")
    ).flatten
    if lines.isEmpty then "" else lines.mkString("\n")

  private def emitForeground(name: String, color: Option[Color], pad: String): Option[String] =
    color.flatMap(toAwtColor).map(c => s"$pad$name.setForeground($c)")

  private def emitBackground(name: String, color: Option[Color], pad: String): Option[String] =
    color.flatMap(toAwtColor).map(c => s"$pad$name.setOpaque(true)\n$pad$name.setBackground($c)")

  private def emitFont(name: String, text: TextStyle, pad: String): Option[String] =
    val bits = List(
      text.fontWeight.map {
        case FontWeight.Bold | FontWeight.ExtraBold | FontWeight.Black | FontWeight.SemiBold => "Font.BOLD"
        case _ => "Font.PLAIN"
      },
      if text.fontStyle == FontStyle.Italic then Some("Font.ITALIC") else None
    ).flatten
    val styleExpr = if bits.isEmpty then "Font.PLAIN" else bits.mkString(" | ")
    text.fontSize.map(size => s"$pad$name.setFont($name.getFont.deriveFont($styleExpr, ${size.toFloat}f))")

  private def emitBorder(name: String, style: Style, pad: String): Option[String] =
    val edges = style.layout.padding
    val hasPadding = edges != EdgeInsets.zero
    val border =
      for
        color <- style.decoration.borderColor.flatMap(toAwtColor)
        width <- style.decoration.borderWidth
      yield (color, width)
    (hasPadding, border) match
      case (false, None) => None
      case (true, None) =>
        Some(s"$pad$name.setBorder(BorderFactory.createEmptyBorder(${edges.top.round.toInt}, ${edges.left.round.toInt}, ${edges.bottom.round.toInt}, ${edges.right.round.toInt}))")
      case (false, Some((color, width))) =>
        Some(s"$pad$name.setBorder(BorderFactory.createLineBorder($color, ${width.round.toInt.max(1)}))")
      case (true, Some((color, width))) =>
        Some(
          s"""$pad$name.setBorder(BorderFactory.createCompoundBorder(
             |$pad  BorderFactory.createLineBorder($color, ${width.round.toInt.max(1)}),
             |$pad  BorderFactory.createEmptyBorder(${edges.top.round.toInt}, ${edges.left.round.toInt}, ${edges.bottom.round.toInt}, ${edges.right.round.toInt})
             |$pad))""".stripMargin
        )

  private def emitSizeHints(name: String, layout: LayoutStyle, pad: String): Option[String] =
    (layout.width, layout.height) match
      case (Dimension.Fixed(w), Dimension.Fixed(h)) =>
        Some(s"${pad}setSizeHints($name, ${w.round.toInt}, ${h.round.toInt})")
      case _ => None

  private def toAwtColor(color: Color): Option[String] =
    color match
      case Color.Hex(value) =>
        Some(s"""Color.decode("${scalaString(if value.startsWith("#") then value else s"#$value")}")""")
      case Color.Rgb(r, g, b) =>
        Some(s"Color($r, $g, $b)")
      case Color.Rgba(r, g, b, _) =>
        Some(s"Color($r, $g, $b)")
      case Color.Named("black") => Some("Color.BLACK")
      case Color.Named("blue")  => Some("Color.BLUE")
      case Color.Named("gray") | Color.Named("grey") => Some("Color.GRAY")
      case Color.Named("green") => Some("Color.GREEN")
      case Color.Named("red")   => Some("Color.RED")
      case Color.Named("white") => Some("Color.WHITE")
      case _ => None

  private def scrollPolicy(axis: Axis, horizontal: Boolean): String =
    axis match
      case Axis.Horizontal if horizontal => "ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED"
      case Axis.Horizontal               => "ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER"
      case Axis.Both                     => if horizontal then "ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED" else "ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED"
      case _ if horizontal               => "ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER"
      case _                             => "ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED"

  private def emitTextInput(
    parent:      String,
    value:       ReactiveSignal[String],
    placeholder: String,
    multiline:   Boolean,
    secure:      Boolean,
    style:       Style,
    indent:      Int
  ): String =
    val pad = " " * indent
    val initial = scalaString(Option(value.apply()).getOrElse(""))
    if multiline then
      emitComponent(parent, "scroll", s"""JScrollPane(JTextArea("$initial", 4, 32))""", style, indent)
    else if secure then
      emitComponent(parent, "field", s"""JPasswordField("$initial", 32)""", style, indent)
    else
      emitComponent(parent, "field", s"""{
         |$pad  val field = JTextField("$initial", 32)
         |$pad  field.putClientProperty("JTextField.placeholderText", "${scalaString(placeholder)}")
         |$pad  field
         |$pad}""".stripMargin, style, indent)

  private def emitDivider(parent: String, axis: Axis, style: Style, indent: Int): String =
    val orientation = axis match
      case Axis.Vertical => "SwingConstants.VERTICAL"
      case _             => "SwingConstants.HORIZONTAL"
    emitComponent(parent, "divider", s"JSeparator($orientation)", style, indent)

  private def textOf(view: View[?]): String =
    view match
      case View.Text(content, _)      => content()
      case View.TextNode(value)       => value()
      case View.SignalText(signal, _) => String.valueOf(signal.apply())
      case View.Fragment(children)    => children.map(textOf).mkString
      case View.Styled(child, _)      => textOf(child)
      case _                          => "Button"

  private def scalaString(value: Any): String =
    String.valueOf(value).flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c => c.toString
    }
