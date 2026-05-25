package scalascript.frontend.swing

import scalascript.frontend.*

/** JDK-only Swing desktop frontend backend.
 *
 *  Phase 1 intentionally emits a minimal native app artifact: a Scala source
 *  file with a `JFrame`, a small text renderer, and simple static controls.
 *  Later phases will replace the fallback rendering with toolkit-aware
 *  lowering and action transport integration. */
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
       |$body
       |
       |      frame.getContentPane.add(root, BorderLayout.CENTER)
       |      frame.setSize(640, 420)
       |      frame.setLocationRelativeTo(null)
       |      frame.setVisible(true)
       |    }
       |""".stripMargin

  private def emitBuilder(parent: String, view: View[?], indent: Int): String =
    val pad = " " * indent
    view match
      case View.Text(content, _) =>
        s"""$pad$parent.add(JLabel("${scalaString(content())}"))"""
      case View.TextNode(value) =>
        s"""$pad$parent.add(JLabel("${scalaString(value())}"))"""
      case View.SignalText(signal, _) =>
        s"""$pad$parent.add(JLabel("${scalaString(signal.apply())}"))"""
      case View.Button(label, _, enabled, _) =>
        s"""$pad${parent}.add({
           |$pad  val button = JButton("${scalaString(textOf(label))}")
           |$pad  button.setEnabled(${enabled()})
           |$pad  button
           |$pad})""".stripMargin
      case View.TextInput(value, placeholder, multiline, secure, _) =>
        val initial = scalaString(Option(value.apply()).getOrElse(""))
        if multiline then
          s"""$pad${parent}.add(JScrollPane(JTextArea("$initial", 4, 32)))"""
        else if secure then
          s"""$pad${parent}.add(JPasswordField("$initial", 32))"""
        else
          s"""$pad${parent}.add({
             |$pad  val field = JTextField("$initial", 32)
             |$pad  field.putClientProperty("JTextField.placeholderText", "${scalaString(placeholder)}")
             |$pad  field
             |$pad})""".stripMargin
      case View.Toggle(checked, label, _) =>
        s"""$pad${parent}.add(JCheckBox("${scalaString(label)}", ${checked.apply()}))"""
      case View.Column(children, spacing, _, _) =>
        emitPanel(parent, children, axis = "BoxLayout.Y_AXIS", spacing, indent)
      case View.Row(children, spacing, _, _) =>
        emitPanel(parent, children, axis = "BoxLayout.X_AXIS", spacing, indent)
      case View.Fragment(children) =>
        children.map(emitBuilder(parent, _, indent)).mkString("\n")
      case View.Show(cond, whenTrue, whenFalse) =>
        emitBuilder(parent, if cond() then whenTrue() else whenFalse(), indent)
      case View.ShowSignal(cond, whenTrue, whenFalse) =>
        emitBuilder(parent, if cond.apply() then whenTrue else whenFalse, indent)
      case View.For(items, render) =>
        items().map(item => emitBuilder(parent, render(item), indent)).mkString("\n")
      case View.Styled(child, _) =>
        emitBuilder(parent, child, indent)
      case View.Adaptive(_, desktop, _, fallback) =>
        emitBuilder(parent, desktop.getOrElse(fallback), indent)
      case View.Spacer(size) =>
        val height = size.map(_.round.toInt).getOrElse(8)
        s"""$pad$parent.add(Box.createVerticalStrut($height))"""
      case other =>
        s"""$pad$parent.add(JLabel("${scalaString(s"[unsupported Swing view: ${other.productPrefix}]")}"))"""

  private def emitPanel(parent: String, children: Seq[View[?]], axis: String, spacing: Double, indent: Int): String =
    val pad = " " * indent
    val childPad = " " * (indent + 2)
    val childBody =
      children.zipWithIndex.map { (child, idx) =>
        val rendered = emitBuilder("panel", child, indent + 2)
        val spacer =
          if spacing > 0 && idx < children.size - 1 then
            s"\n${childPad}panel.add(Box.createRigidArea(java.awt.Dimension(0, ${spacing.round.toInt})))"
          else ""
        rendered + spacer
      }.mkString("\n")
    s"""$pad${parent}.add({
       |$pad  val panel = JPanel()
       |$pad  panel.setLayout(BoxLayout(panel, $axis))
       |$childBody
       |$pad  panel
       |$pad})""".stripMargin

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
