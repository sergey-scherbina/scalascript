package scalascript.frontend.javafx

import scalascript.frontend.*

/** JDK + OpenJFX desktop frontend backend.
 *
 *  The JavaFX emitter covers the same toolkit subset as Swing: text/labels,
 *  buttons, text fields, checkboxes, vertical/horizontal stacks, spacers,
 *  dividers, scroll views, and CSS-based styling via `setStyle(...)`. */
final class JavaFxFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "javafx"

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
      "javafx is a JVM desktop frontend; use emitNative(..., Platform.Desktop(...))"
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
          sources     = Map("src/main/scala/Main.scala" -> JavaFxEmitter.mainScala(module, manifest)),
          resources   = Map.empty,
          buildScript = "scala-cli run .",
          manifest    = manifest,
          format      = AppFormat.JavaFxApp,
          target      = platform
        ))
      case _ => None

// ─── Emitter — generates standalone Scala source ─────────────────────────────

private object JavaFxEmitter:

  private val javafxVersion = "21.0.5"

  private def javafxOs: String =
    val name = sys.props.getOrElse("os.name", "")
    val arch = sys.props.getOrElse("os.arch", "")
    if name.startsWith("Mac") then
      if arch.contains("aarch64") then "mac-aarch64" else "mac"
    else if name.startsWith("Windows") then "win"
    else "linux"

  def mainScala(module: FrontendModule, manifest: AppManifest): String =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val root     = entry.body(())
    val signals  = collectSignals(root)
    val body     = emitBuilder("root", root, indent = 6)
    val sigTable = emitSignalTable(signals)
    val os       = javafxOs
    s"""//> using scala 3.8.3
       |//> using option -Wunused:all -deprecation -feature
       |//> using dep "org.openjfx:javafx-controls:$javafxVersion:$os"
       |//> using dep "org.openjfx:javafx-base:$javafxVersion:$os"
       |//> using dep "org.openjfx:javafx-graphics:$javafxVersion:$os"
       |
       |import javafx.application.Application
       |import javafx.geometry.{Insets, Orientation}
       |import javafx.scene.Scene
       |import javafx.scene.control.*
       |import javafx.scene.layout.*
       |import scala.collection.mutable
       |
       |object Main:
       |  def main(args: Array[String]): Unit =
       |    Application.launch(classOf[App], args*)
       |
       |class App extends Application:
       |  override def start(primaryStage: javafx.stage.Stage): Unit =
       |    primaryStage.setTitle("${scalaString(manifest.displayName)}")
       |
       |    val signals = mutable.Map[String, Any]($sigTable)
       |    val bindings = mutable.Map.empty[String, mutable.Buffer[() => Unit]]
       |
       |    val root = VBox(16.0)
       |    root.setPadding(Insets(16, 16, 16, 16))
       |$body
       |
       |    val scene = Scene(root, 640.0, 420.0)
       |    primaryStage.setScene(scene)
       |    primaryStage.show()
       |
       |  private def bindSignal(bindings: mutable.Map[String, mutable.Buffer[() => Unit]], id: String)(refresh: => Unit): Unit =
       |    bindings.getOrElseUpdate(id, mutable.Buffer.empty) += (() => refresh)
       |    refresh
       |
       |  private def refreshSignal(bindings: mutable.Map[String, mutable.Buffer[() => Unit]], id: String): Unit =
       |    bindings.get(id).foreach(_.foreach(refresh => refresh()))
       |
       |  private def setSignal(signals: mutable.Map[String, Any], bindings: mutable.Map[String, mutable.Buffer[() => Unit]], id: String, value: Any): Unit =
       |    signals.update(id, value)
       |    refreshSignal(bindings, id)
       |
       |  private def incrementSignal(signals: mutable.Map[String, Any], bindings: mutable.Map[String, mutable.Buffer[() => Unit]], id: String, by: Int): Unit =
       |    val next = signals.get(id).collect { case n: Int => n }.getOrElse(0) + by
       |    setSignal(signals, bindings, id, next)
       |
       |  private def toggleSignal(signals: mutable.Map[String, Any], bindings: mutable.Map[String, mutable.Buffer[() => Unit]], id: String): Unit =
       |    val next = !signals.get(id).collect { case b: Boolean => b }.getOrElse(false)
       |    setSignal(signals, bindings, id, next)
       |
       |  private def signalString(signals: mutable.Map[String, Any], id: String): String =
       |    signals.get(id).map(String.valueOf).getOrElse("")
       |
       |  private def signalBoolean(signals: mutable.Map[String, Any], id: String): Boolean =
       |    signals.get(id).collect { case b: Boolean => b }.getOrElse(false)
       |
       |""".stripMargin

  private def emitBuilder(parent: String, view: View[?], indent: Int): String =
    val pad = " " * indent
    view match
      case View.Text(content, style) =>
        emitComponent(parent, "label", s"""Label("${scalaString(content())}")""", emitStyle(style), indent)
      case View.TextNode(value) =>
        emitComponent(parent, "label", s"""Label("${scalaString(value())}")""", "", indent)
      case View.SignalText(signal, style) =>
        emitSignalLabel(parent, signal, style, indent)
      case View.Button(label, action, enabled, style) =>
        emitButton(parent, label, action, enabled, style, indent)
      case View.TextInput(value, placeholder, multiline, secure, style) =>
        emitTextInput(parent, value, placeholder, multiline, secure, style, indent)
      case View.Toggle(checked, label, style) =>
        emitToggle(parent, checked, label, style, indent)
      case View.Column(children, spacing, _, style) =>
        emitBox(parent, children, vertical = true, spacing, style, indent)
      case View.Row(children, spacing, _, style) =>
        emitBox(parent, children, vertical = false, spacing, style, indent)
      case View.ScrollView(child, axis, style) =>
        emitScrollView(parent, child, axis, style, indent)
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
          case View.Text(content, _)     => emitComponent(parent, "label", s"""Label("${scalaString(content())}")""", emitStyle(style), indent)
          case View.TextNode(value)      => emitComponent(parent, "label", s"""Label("${scalaString(value())}")""", emitStyle(style), indent)
          case View.SignalText(sig, _)   => emitSignalLabel(parent, sig, style, indent)
          case View.Button(lbl, act, en, _) => emitButton(parent, lbl, act, en, style, indent)
          case View.TextInput(v, ph, ml, sc, _) => emitTextInput(parent, v, ph, ml, sc, style, indent)
          case View.Toggle(chk, lbl, _)  => emitToggle(parent, chk, lbl, style, indent)
          case View.Column(ch, sp, _, _) => emitBox(parent, ch, vertical = true, sp, style, indent)
          case View.Row(ch, sp, _, _)    => emitBox(parent, ch, vertical = false, sp, style, indent)
          case View.Divider(ax, _)       => emitDivider(parent, ax, style, indent)
          case _                         => emitBuilder(parent, child, indent)
      case View.Adaptive(_, desktop, _, fallback) =>
        emitBuilder(parent, desktop.getOrElse(fallback), indent)
      case View.Spacer(size) =>
        val px = size.map(_.round.toInt).getOrElse(8)
        s"""$pad${parent}.getChildren.add({
           |$pad  val spacer = Region()
           |$pad  spacer.setPrefSize($px.0, $px.0)
           |$pad  spacer
           |$pad})""".stripMargin
      case other =>
        emitComponent(parent, "unsupported", s"""Label("${scalaString(s"[unsupported: ${other.productPrefix}]")}")""", "", indent)

  private def emitBox(parent: String, children: Seq[View[?]], vertical: Boolean, spacing: Double, style: Style, indent: Int): String =
    val pad   = " " * indent
    val klass = if vertical then "VBox" else "HBox"
    val css   = emitStyle(style)
    val childBody = children.map(emitBuilder("box", _, indent + 2)).mkString("\n")
    s"""$pad${parent}.getChildren.add({
       |$pad  val box = $klass($spacing)
       |${if css.nonEmpty then s"$pad  box.setStyle(\"${escapeStyle(css)}\")" else ""}
       |$childBody
       |$pad  box
       |$pad})""".stripMargin

  private def emitComponent(parent: String, name: String, constructor: String, css: String, indent: Int): String =
    val pad      = " " * indent
    val styleStr = if css.nonEmpty then s"\n$pad  $name.setStyle(\"${escapeStyle(css)}\")" else ""
    s"""$pad${parent}.getChildren.add({
       |$pad  val $name = $constructor$styleStr
       |$pad  $name
       |$pad})""".stripMargin

  private def emitSignalLabel(parent: String, signal: ReactiveSignal[?], style: Style, indent: Int): String =
    val pad      = " " * indent
    val id       = scalaString(signal.id)
    val css      = emitStyle(style)
    val styleStr = if css.nonEmpty then s"\n$pad  label.setStyle(\"${escapeStyle(css)}\")" else ""
    s"""$pad${parent}.getChildren.add({
       |$pad  val label = Label(signalString(signals, "$id"))$styleStr
       |$pad  bindSignal(bindings, "$id") {
       |$pad    label.setText(signalString(signals, "$id"))
       |$pad  }
       |$pad  label
       |$pad})""".stripMargin

  private def emitButton(parent: String, label: View[?], action: EventHandler, enabled: () => Boolean, style: Style, indent: Int): String =
    val pad      = " " * indent
    val css      = emitStyle(style)
    val styleStr = if css.nonEmpty then s"\n$pad  button.setStyle(\"${escapeStyle(css)}\")" else ""
    s"""$pad${parent}.getChildren.add({
       |$pad  val button = Button("${scalaString(textOf(label))}")
       |$pad  button.setDisable(${!enabled()})$styleStr
       |${emitAction("button", action, indent + 2)}
       |$pad  button
       |$pad})""".stripMargin

  private def emitToggle(parent: String, checked: ReactiveSignal[Boolean], label: String, style: Style, indent: Int): String =
    val pad      = " " * indent
    val id       = scalaString(checked.id)
    val css      = emitStyle(style)
    val styleStr = if css.nonEmpty then s"\n$pad  checkbox.setStyle(\"${escapeStyle(css)}\")" else ""
    s"""$pad${parent}.getChildren.add({
       |$pad  val checkbox = CheckBox("${scalaString(label)}")
       |$pad  checkbox.setSelected(signalBoolean(signals, "$id"))$styleStr
       |$pad  checkbox.selectedProperty().addListener((_, _, newVal) => setSignal(signals, bindings, "$id", newVal.booleanValue()))
       |$pad  bindSignal(bindings, "$id") {
       |$pad    if checkbox.isSelected != signalBoolean(signals, "$id") then checkbox.setSelected(signalBoolean(signals, "$id"))
       |$pad  }
       |$pad  checkbox
       |$pad})""".stripMargin

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
    val id  = scalaString(value.id)
    val css = emitStyle(style)
    if multiline then
      val styleStr = if css.nonEmpty then s"\n$pad  area.setStyle(\"${escapeStyle(css)}\")" else ""
      s"""$pad${parent}.getChildren.add({
         |$pad  val area = TextArea(signalString(signals, "$id"))$styleStr
         |$pad  area.setPromptText("${scalaString(placeholder)}")
         |$pad  area.textProperty().addListener((_, _, newVal) => setSignal(signals, bindings, "$id", newVal))
         |$pad  bindSignal(bindings, "$id") {
         |$pad    val next = signalString(signals, "$id")
         |$pad    if area.getText != next then area.setText(next)
         |$pad  }
         |$pad  area
         |$pad})""".stripMargin
    else if secure then
      emitComponent(parent, "field", s"""PasswordField()""", css, indent) // TODO: bind signal
    else
      val styleStr = if css.nonEmpty then s"\n$pad  field.setStyle(\"${escapeStyle(css)}\")" else ""
      s"""$pad${parent}.getChildren.add({
         |$pad  val field = TextField(signalString(signals, "$id"))$styleStr
         |$pad  field.setPromptText("${scalaString(placeholder)}")
         |$pad  field.textProperty().addListener((_, _, newVal) => setSignal(signals, bindings, "$id", newVal))
         |$pad  bindSignal(bindings, "$id") {
         |$pad    val next = signalString(signals, "$id")
         |$pad    if field.getText != next then field.setText(next)
         |$pad  }
         |$pad  field
         |$pad})""".stripMargin

  private def emitScrollView(parent: String, child: View[?], axis: Axis, style: Style, indent: Int): String =
    val pad      = " " * indent
    val css      = emitStyle(style)
    val styleStr = if css.nonEmpty then s"\n$pad  scrollPane.setStyle(\"${escapeStyle(css)}\")" else ""
    val innerBody = emitBuilder("innerBox", child, indent + 2)
    s"""$pad${parent}.getChildren.add({
       |$pad  val innerBox = VBox()
       |$innerBody
       |$pad  val scrollPane = ScrollPane(innerBox)
       |$pad  scrollPane.setFitToWidth(true)
       |${emitScrollPolicy(axis, indent + 2)}$styleStr
       |$pad  scrollPane
       |$pad})""".stripMargin

  private def emitScrollPolicy(axis: Axis, indent: Int): String =
    val pad = " " * indent
    axis match
      case Axis.Horizontal =>
        s"${pad}scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)\n${pad}scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)"
      case Axis.Both =>
        s"${pad}scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)\n${pad}scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)"
      case _ =>
        s"${pad}scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)\n${pad}scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)"

  private def emitDivider(parent: String, axis: Axis, style: Style, indent: Int): String =
    val orientation = axis match
      case Axis.Vertical => "Orientation.VERTICAL"
      case _             => "Orientation.HORIZONTAL"
    emitComponent(parent, "divider", s"Separator($orientation)", emitStyle(style), indent)

  private def emitAction(componentName: String, action: EventHandler, indent: Int): String =
    val pad = " " * indent
    action match
      case EventHandler.SetSignalLiteral(signal, value) =>
        s"""$pad$componentName.setOnAction(_ => setSignal(signals, bindings, "${scalaString(signal.id)}", ${scalaLiteral(value)}))"""
      case EventHandler.IncrementSignal(signal, by) =>
        s"""$pad$componentName.setOnAction(_ => incrementSignal(signals, bindings, "${scalaString(signal.id)}", $by))"""
      case EventHandler.ToggleSignal(signal) =>
        s"""$pad$componentName.setOnAction(_ => toggleSignal(signals, bindings, "${scalaString(signal.id)}"))"""
      case EventHandler.Simple(_) | EventHandler.WithEvent(_) =>
        s"""$pad// JVM closure event handler not serializable into generated JavaFX source."""
      case other =>
        s"""$pad// JavaFX action bridge does not support ${other.getClass.getSimpleName} yet."""

  private def emitStyle(style: Style): String =
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]
    style.text.foreground.foreach(c => parts += s"-fx-text-fill: ${cssColor(c)}")
    style.text.fontSize.foreach(s => parts += s"-fx-font-size: ${s.toInt}px")
    style.text.fontWeight.foreach {
      case FontWeight.Bold | FontWeight.ExtraBold | FontWeight.Black | FontWeight.SemiBold =>
        parts += "-fx-font-weight: bold"
      case _ =>
    }
    if style.text.fontStyle == FontStyle.Italic then parts += "-fx-font-style: italic"
    style.decoration.background.foreach(c => parts += s"-fx-background-color: ${cssColor(c)}")
    style.decoration.borderColor.foreach(c => parts += s"-fx-border-color: ${cssColor(c)}")
    style.decoration.borderWidth.foreach(w => parts += s"-fx-border-width: ${w.toInt}px")
    val p = style.layout.padding
    if p != EdgeInsets.zero then
      parts += s"-fx-padding: ${p.top.toInt}px ${p.right.toInt}px ${p.bottom.toInt}px ${p.left.toInt}px"
    parts.mkString("; ")

  private def cssColor(color: Color): String =
    color match
      case Color.Hex(value)       => if value.startsWith("#") then value else s"#$value"
      case Color.Rgb(r, g, b)     => s"rgb($r, $g, $b)"
      case Color.Rgba(r, g, b, _) => s"rgb($r, $g, $b)"
      case Color.Named(n)         => n
      case Color.System(n)        => n
      case Color.Transparent      => "transparent"

  private def escapeStyle(css: String): String =
    css.replace("\\", "\\\\").replace("\"", "\\\"")

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

  private def scalaLiteral(value: Any): String =
    value match
      case null       => "null"
      case s: String  => "\"" + scalaString(s) + "\""
      case c: Char    => "'" + scalaString(c) + "'"
      case b: Boolean => b.toString
      case n: Byte    => n.toString
      case n: Short   => n.toString
      case n: Int     => n.toString
      case n: Long    => n.toString + "L"
      case n: Float   => n.toString + "f"
      case n: Double  => n.toString
      case other      => "\"" + scalaString(other) + "\""

  private final case class SignalInitial(id: String, value: Any)

  private def collectSignals(view: View[?]): List[SignalInitial] =
    def add(acc: Map[String, SignalInitial], signal: ReactiveSignal[?]): Map[String, SignalInitial] =
      acc.updatedWith(signal.id) {
        case existing @ Some(_) => existing
        case None               => Some(SignalInitial(signal.id, signal.apply()))
      }
    def loop(acc: Map[String, SignalInitial], v: View[?]): Map[String, SignalInitial] =
      v match
        case View.SignalText(signal, _)          => add(acc, signal)
        case View.Button(_, action, _, _)        => collectActionSignal(acc, action)
        case View.TextInput(value, _, _, _, _)   => add(acc, value)
        case View.Toggle(checked, _, _)          => add(acc, checked)
        case View.Column(children, _, _, _)      => children.foldLeft(acc)(loop)
        case View.Row(children, _, _, _)         => children.foldLeft(acc)(loop)
        case View.Stack(children, _)             => children.foldLeft(acc)(loop)
        case View.ScrollView(child, _, _)        => loop(acc, child)
        case View.Fragment(children)             => children.foldLeft(acc)(loop)
        case View.Show(_, whenTrue, whenFalse)   => loop(loop(acc, whenTrue()), whenFalse())
        case View.ShowSignal(cond, wt, wf)       => loop(loop(add(acc, cond), wt), wf)
        case View.For(items, render)             => items().foldLeft(acc)((a, i) => loop(a, render(i)))
        case View.Styled(child, _)               => loop(acc, child)
        case View.Adaptive(web, desktop, mobile, fallback) =>
          List(web, desktop, mobile).flatten.foldLeft(loop(acc, fallback))(loop)
        case _ => acc
    loop(Map.empty, view).values.toList.sortBy(_.id)

  private def collectActionSignal(acc: Map[String, SignalInitial], action: EventHandler): Map[String, SignalInitial] =
    action match
      case EventHandler.SetSignalLiteral(signal, _) => addSignal(acc, signal)
      case EventHandler.IncrementSignal(signal, _)  => addSignal(acc, signal)
      case EventHandler.ToggleSignal(signal)        => addSignal(acc, signal)
      case EventHandler.InputChange(signal)         => addSignal(acc, signal)
      case EventHandler.FetchAction(_, _, body, tick, _) => addSignal(addSignal(acc, body), tick)
      case _ => acc

  private def addSignal(acc: Map[String, SignalInitial], signal: ReactiveSignal[?]): Map[String, SignalInitial] =
    acc.updatedWith(signal.id) {
      case existing @ Some(_) => existing
      case None               => Some(SignalInitial(signal.id, signal.apply()))
    }

  private def emitSignalTable(signals: List[SignalInitial]): String =
    signals.map(s => s""""${scalaString(s.id)}" -> ${scalaLiteral(s.value)}""").mkString(", ")
