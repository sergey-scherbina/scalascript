package scalascript.frontend.swing

import scalascript.frontend.*

import java.awt.{BorderLayout, Color as AwtColor, Dimension as AwtDimension, Font}
import java.awt.event.{WindowAdapter, WindowEvent}
import javax.swing.*
import javax.swing.event.{DocumentEvent, DocumentListener}
import javax.swing.text.JTextComponent
import scala.annotation.nowarn
import scala.collection.mutable
import scala.util.matching.Regex

/** Same-process Swing runner for JVM-hosted frontend modules.
 *
 *  This is the runtime counterpart to the source emitter. `ssc run-jvm
 *  --frontend swing` uses it so the desktop UI runs in the same JVM process as
 *  generated backend code instead of compiling and launching a second Scala
 *  program through nested scala-cli.
 */
object SwingRuntime:

  final case class Options(
      closeOperation:  Int = WindowConstants.EXIT_ON_CLOSE,
      size:            AwtDimension = AwtDimension(640, 420),
      centerOnScreen:  Boolean = true,
      fetchDispatcher: Option[FetchDispatcher] = None,
      iconPath:        Option[String] = None,
      onShutdown:      Option[() => Unit] = None
  )

  trait FetchDispatcher:
    def request(method: String, url: String, body: String): FetchResponse

  final case class FetchResponse(
      status:  Int,
      body:    String = "",
      headers: Map[String, String] = Map.empty
  )

  def run(module: FrontendModule, options: Options = Options()): JFrame =
    if SwingUtilities.isEventDispatchThread then
      val frame = frameFor(module, options)
      frame.setVisible(true)
      frame
    else
      var frame: JFrame | Null = null
      SwingUtilities.invokeAndWait { () =>
        val created = frameFor(module, options)
        created.setVisible(true)
        frame = created
      }
      frame.nn

  def frameFor(module: FrontendModule, options: Options = Options()): JFrame =
    val manifest = module.appManifest.getOrElse(
      AppManifest("com.example.app", "ScalaScript App", "1.0.0")
    )
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
          s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val rootView = entry.body(())
    val state = RuntimeState.from(rootView, options.fetchDispatcher)
    val frame = JFrame(manifest.displayName)
    options.iconPath.foreach { path =>
      try frame.setIconImage(ImageIcon(path).getImage)
      catch case _: Exception => ()
    }
    options.onShutdown match
      case Some(hook) =>
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
        frame.addWindowListener(new WindowAdapter:
          override def windowClosing(e: WindowEvent): Unit =
            hook()
            frame.dispose()
        )
      case None =>
        frame.setDefaultCloseOperation(options.closeOperation)
    frame.getContentPane.add(buildRoot(rootView, state), BorderLayout.CENTER)
    frame.setSize(options.size)
    if options.centerOnScreen then frame.setLocationRelativeTo(null)
    frame

  def buildRoot(view: View[?]): JPanel =
    buildRoot(view, RuntimeState.from(view))

  def buildRoot(view: View[?], state: RuntimeState): JPanel =
    val root = JPanel()
    root.setLayout(BoxLayout(root, BoxLayout.Y_AXIS))
    root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))
    addTo(root, view, state)
    root

  final class RuntimeState private[swing] (
      val signals:  mutable.Map[String, Any],
      val bindings: mutable.Map[String, mutable.Buffer[() => Unit]],
      val fetchDispatcher: Option[FetchDispatcher]
  ):
    def bindSignal(id: String)(refresh: => Unit): Unit =
      bindings.getOrElseUpdate(id, mutable.Buffer.empty) += (() => refresh)
      refresh

    def refreshSignal(id: String): Unit =
      bindings.get(id).foreach(_.foreach(refresh => refresh()))

    def setSignal(id: String, value: Any): Unit =
      signals.update(id, value)
      refreshSignal(id)

    def incrementSignal(id: String, by: Int): Unit =
      val next = signals.get(id).collect { case n: Int => n }.getOrElse(0) + by
      setSignal(id, next)

    def toggleSignal(id: String): Unit =
      val next = !signals.get(id).collect { case b: Boolean => b }.getOrElse(false)
      setSignal(id, next)

    def signalString(id: String): String =
      signals.get(id).map(String.valueOf).getOrElse("")

    def signalBoolean(id: String): Boolean =
      signals.get(id).collect { case b: Boolean => b }.getOrElse(false)

  object RuntimeState:
    def from(view: View[?], fetchDispatcher: Option[FetchDispatcher] = None): RuntimeState =
      RuntimeState(
        mutable.Map.from(collectSignals(view).map(s => s.id -> s.value)),
        mutable.Map.empty,
        fetchDispatcher
      )

  @nowarn("cat=deprecation")
  private def addTo(parent: JPanel, view: View[?], state: RuntimeState): Unit =
    view match
      case View.Text(content, style) =>
        parent.add(styled(JLabel(content()), style))
      case View.TextNode(value) =>
        parent.add(JLabel(value()))
      case View.SignalText(signal, style) =>
        val label = styled(JLabel(state.signalString(signal.id)), style)
        state.bindSignal(signal.id) {
          label.setText(state.signalString(signal.id))
        }
        parent.add(label)
      case View.Button(label, action, enabled, style) =>
        val button = styled(JButton(textOf(label)), style)
        button.setEnabled(enabled())
        wireAction(button, action, state)
        parent.add(button)
      case View.TextInput(value, placeholder, multiline, secure, style) =>
        parent.add(textInput(value, placeholder, multiline, secure, style, state))
      case View.Toggle(checked, label, style) =>
        val checkbox = styled(JCheckBox(label, state.signalBoolean(checked.id)), style)
        checkbox.addActionListener(_ => state.setSignal(checked.id, checkbox.isSelected))
        state.bindSignal(checked.id) {
          val next = state.signalBoolean(checked.id)
          if checkbox.isSelected != next then checkbox.setSelected(next)
        }
        parent.add(checkbox)
      case View.Column(children, spacing, _, style) =>
        parent.add(panel(children, BoxLayout.Y_AXIS, spacing, style, state))
      case View.Row(children, spacing, _, style) =>
        parent.add(panel(children, BoxLayout.X_AXIS, spacing, style, state))
      case View.Stack(children, style) =>
        parent.add(panel(children, BoxLayout.Y_AXIS, 0, style, state))
      case View.ScrollView(child, axis, style) =>
        val scrollPanel = panel(Seq(child), BoxLayout.Y_AXIS, 0, Style(), state)
        val scroll = styled(JScrollPane(scrollPanel), style)
        scroll.setHorizontalScrollBarPolicy(scrollPolicy(axis, horizontal = true))
        scroll.setVerticalScrollBarPolicy(scrollPolicy(axis, horizontal = false))
        parent.add(scroll)
      case View.Divider(axis, style) =>
        val orientation = axis match
          case Axis.Vertical => SwingConstants.VERTICAL
          case _             => SwingConstants.HORIZONTAL
        parent.add(styled(JSeparator(orientation), style))
      case View.Fragment(children) =>
        children.foreach(addTo(parent, _, state))
      case View.Element(_, _, _, children) =>
        children.foreach(addTo(parent, _, state))
      case View.FetchTable(_, fetchUrl, deleteUrl, tick) =>
        parent.add(fetchTable(fetchUrl, deleteUrl, tick, state))
      case View.Show(cond, whenTrue, whenFalse) =>
        addTo(parent, if cond() then whenTrue() else whenFalse(), state)
      case View.ShowSignal(cond, whenTrue, whenFalse) =>
        addTo(parent, if cond.apply() then whenTrue else whenFalse, state)
      case View.For(items, render) =>
        items().foreach(item => addTo(parent, render(item), state))
      case View.Styled(child, style) =>
        addTo(parent, restyle(child, style), state)
      case View.Adaptive(_, desktop, _, fallback) =>
        addTo(parent, desktop.getOrElse(fallback), state)
      case View.Spacer(size) =>
        val px = size.map(_.round.toInt).getOrElse(8)
        parent.add(Box.createRigidArea(AwtDimension(px, px)))
      case other =>
        parent.add(JLabel(s"[unsupported Swing view: ${other.productPrefix}]"))

  private def panel(children: Seq[View[?]], axis: Int, spacing: Double, style: Style, state: RuntimeState): JPanel =
    val p = styled(JPanel(), style)
    p.setLayout(BoxLayout(p, axis))
    children.zipWithIndex.foreach { case (child, idx) =>
      addTo(p, child, state)
      if spacing > 0 && idx < children.size - 1 then
        val spacer =
          if axis == BoxLayout.X_AXIS then AwtDimension(spacing.round.toInt, 0)
          else AwtDimension(0, spacing.round.toInt)
        p.add(Box.createRigidArea(spacer))
    }
    p

  private def textInput(
      value:       ReactiveSignal[String],
      placeholder: String,
      multiline:   Boolean,
      secure:      Boolean,
      style:       Style,
      state:       RuntimeState
  ): JComponent =
    if multiline then
      val area = JTextArea(state.signalString(value.id), 4, 32)
      installTextBinding(area, value, state)
      styled(JScrollPane(area), style)
    else if secure then
      styled(JPasswordField(state.signalString(value.id), 32), style)
    else
      val field = styled(JTextField(state.signalString(value.id), 32), style)
      field.putClientProperty("JTextField.placeholderText", placeholder)
      installTextBinding(field, value, state)
      field

  private def installTextBinding(component: JTextComponent, signal: ReactiveSignal[String], state: RuntimeState): Unit =
    var updating = false
    component.getDocument.addDocumentListener(new DocumentListener:
      private def sync(): Unit =
        if !updating then state.setSignal(signal.id, component.getText)
      def insertUpdate(e: DocumentEvent): Unit = sync()
      def removeUpdate(e: DocumentEvent): Unit = sync()
      def changedUpdate(e: DocumentEvent): Unit = sync()
    )
    state.bindSignal(signal.id) {
      val next = state.signalString(signal.id)
      if component.getText != next then
        updating = true
        component.setText(next)
        updating = false
    }

  private def wireAction(component: AbstractButton, action: EventHandler, state: RuntimeState): Unit =
    action match
      case EventHandler.SetSignalLiteral(signal, value) =>
        component.addActionListener(_ => state.setSignal(signal.id, value))
      case EventHandler.IncrementSignal(signal, by) =>
        component.addActionListener(_ => state.incrementSignal(signal.id, by))
      case EventHandler.ToggleSignal(signal) =>
        component.addActionListener(_ => state.toggleSignal(signal.id))
      case EventHandler.Simple(action) =>
        component.addActionListener(_ => action())
      case EventHandler.WithEvent(action) =>
        component.addActionListener(event => action(event))
      case EventHandler.FetchAction(method, url, body, onSuccessTick, clearBody) =>
        component.addActionListener { _ =>
          state.fetchDispatcher.foreach { dispatcher =>
            val response = dispatcher.request(method, url, state.signalString(body.id))
            if response.status >= 200 && response.status < 300 then
              state.incrementSignal(onSuccessTick.id, 1)
              if clearBody then state.setSignal(body.id, "")
          }
        }
      case _ => ()

  private def fetchTable(fetchUrl: String, deleteUrl: String, tick: ReactiveSignal[Int], state: RuntimeState): JPanel =
    val panel = JPanel()
    panel.setLayout(BoxLayout(panel, BoxLayout.Y_AXIS))

    def renderStatus(message: String): Unit =
      panel.removeAll()
      panel.add(JLabel(message))
      panel.revalidate()
      panel.repaint()

    def renderRows(rows: List[TableRow]): Unit =
      panel.removeAll()
      val header = JPanel()
      header.setLayout(BoxLayout(header, BoxLayout.X_AXIS))
      header.add(JLabel("Task"))
      header.add(Box.createHorizontalGlue())
      panel.add(header)
      rows.foreach { row =>
        val line = JPanel()
        line.setLayout(BoxLayout(line, BoxLayout.X_AXIS))
        line.add(JLabel(row.text))
        line.add(Box.createHorizontalGlue())
        val delete = JButton("Delete")
        delete.addActionListener { _ =>
          state.fetchDispatcher.foreach { dispatcher =>
            val response = dispatcher.request("POST", deleteUrl, row.id)
            if response.status >= 200 && response.status < 300 then
              state.incrementSignal(tick.id, 1)
          }
        }
        line.add(delete)
        panel.add(line)
      }
      if rows.isEmpty then panel.add(JLabel("No rows"))
      panel.revalidate()
      panel.repaint()

    def refresh(): Unit =
      state.fetchDispatcher match
        case Some(dispatcher) =>
          val response = dispatcher.request("GET", fetchUrl, "")
          if response.status >= 200 && response.status < 300 then renderRows(parseTableRows(response.body))
          else renderStatus(s"Fetch failed: ${response.status}")
        case None =>
          renderStatus("Fetch dispatcher is not configured")

    state.bindSignal(tick.id)(refresh())
    panel

  private def styled[A <: JComponent](component: A, style: Style): A =
    style.text.foreground.flatMap(toAwtColor).foreach(component.setForeground)
    style.decoration.background.flatMap(toAwtColor).foreach { color =>
      component.setOpaque(true)
      component.setBackground(color)
    }
    style.text.fontSize.foreach { size =>
      val weight =
        style.text.fontWeight match
          case Some(FontWeight.Bold | FontWeight.ExtraBold | FontWeight.Black | FontWeight.SemiBold) => Font.BOLD
          case _ => Font.PLAIN
      val shape = if style.text.fontStyle == FontStyle.Italic then Font.ITALIC else Font.PLAIN
      component.setFont(component.getFont.deriveFont(weight | shape, size.toFloat))
    }
    style.a11y.label.foreach(component.getAccessibleContext.setAccessibleName)
    component

  private def restyle(view: View[?], style: Style): View[?] =
    view match
      case View.Text(content, _)          => View.Text(content, style)
      case View.SignalText(signal, _)     => View.SignalText(signal, style)
      case View.Button(label, action, enabled, _) => View.Button(label, action, enabled, style)
      case View.TextInput(value, placeholder, multiline, secure, _) =>
        View.TextInput(value, placeholder, multiline, secure, style)
      case View.Toggle(checked, label, _) => View.Toggle(checked, label, style)
      case View.Column(children, spacing, align, _) => View.Column(children, spacing, align, style)
      case View.Row(children, spacing, align, _)    => View.Row(children, spacing, align, style)
      case View.Divider(axis, _)          => View.Divider(axis, style)
      case other                          => other

  private def textOf(view: View[?]): String =
    view match
      case View.Text(content, _)      => content()
      case View.TextNode(value)       => value()
      case View.SignalText(signal, _) => String.valueOf(signal.apply())
      case View.Fragment(children)    => children.map(textOf).mkString
      case View.Styled(child, _)      => textOf(child)
      case _                          => "Button"

  private def scrollPolicy(axis: Axis, horizontal: Boolean): Int =
    axis match
      case Axis.Horizontal if horizontal => ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
      case Axis.Horizontal               => ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
      case Axis.Both                     => if horizontal then ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED else ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
      case _ if horizontal               => ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
      case _                             => ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

  private def toAwtColor(color: scalascript.frontend.Color): Option[AwtColor] =
    color match
      case scalascript.frontend.Color.Hex(value) =>
        Some(AwtColor.decode(if value.startsWith("#") then value else s"#$value"))
      case scalascript.frontend.Color.Rgb(r, g, b) =>
        Some(AwtColor(r, g, b))
      case scalascript.frontend.Color.Rgba(r, g, b, _) =>
        Some(AwtColor(r, g, b))
      case scalascript.frontend.Color.Named("black") => Some(AwtColor.BLACK)
      case scalascript.frontend.Color.Named("blue")  => Some(AwtColor.BLUE)
      case scalascript.frontend.Color.Named("gray") | scalascript.frontend.Color.Named("grey") => Some(AwtColor.GRAY)
      case scalascript.frontend.Color.Named("green") => Some(AwtColor.GREEN)
      case scalascript.frontend.Color.Named("red")   => Some(AwtColor.RED)
      case scalascript.frontend.Color.Named("white") => Some(AwtColor.WHITE)
      case _ => None

  private final case class TableRow(id: String, text: String)

  private val JsonObjectPattern: Regex = """\{([^}]*)\}""".r
  private val JsonFieldPattern:  Regex = """"([^"]+)"\s*:\s*("(?:\\.|[^"])*"|[^,}]+)""".r

  private def parseTableRows(body: String): List[TableRow] =
    JsonObjectPattern.findAllMatchIn(body).toList.flatMap { obj =>
      val fields = JsonFieldPattern.findAllMatchIn(obj.group(1)).map { field =>
        field.group(1) -> jsonValue(field.group(2).trim)
      }.toMap
      fields.get("id").map(id => TableRow(id, fields.getOrElse("text", "")))
    }

  private def jsonValue(raw: String): String =
    if raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 then
      unescapeJson(raw.substring(1, raw.length - 1))
    else raw

  private def unescapeJson(value: String): String =
    val out = StringBuilder()
    var i = 0
    while i < value.length do
      if value.charAt(i) == '\\' && i + 1 < value.length then
        value.charAt(i + 1) match
          case '"'  => out += '"'
          case '\\' => out += '\\'
          case '/'  => out += '/'
          case 'b'  => out += '\b'
          case 'f'  => out += '\f'
          case 'n'  => out += '\n'
          case 'r'  => out += '\r'
          case 't'  => out += '\t'
          case other => out += other
        i += 2
      else
        out += value.charAt(i)
        i += 1
    out.toString

  private final case class SignalInitial(id: String, value: Any)

  @nowarn("cat=deprecation")
  private def collectSignals(view: View[?]): List[SignalInitial] =
    def add(acc: Map[String, SignalInitial], signal: ReactiveSignal[?]): Map[String, SignalInitial] =
      acc.updatedWith(signal.id) {
        case existing @ Some(_) => existing
        case None               => Some(SignalInitial(signal.id, signal.apply()))
      }
    def action(acc: Map[String, SignalInitial], handler: EventHandler): Map[String, SignalInitial] =
      handler match
        case EventHandler.SetSignalLiteral(signal, _) => add(acc, signal)
        case EventHandler.IncrementSignal(signal, _)  => add(acc, signal)
        case EventHandler.ToggleSignal(signal)        => add(acc, signal)
        case EventHandler.InputChange(signal)         => add(acc, signal)
        case EventHandler.FetchAction(_, _, body, onSuccessTick, _) => add(add(acc, body), onSuccessTick)
        case _ => acc
    def loop(acc: Map[String, SignalInitial], v: View[?]): Map[String, SignalInitial] =
      v match
        case View.SignalText(signal, _) => add(acc, signal)
        case View.Button(_, handler, _, _) => action(acc, handler)
        case View.TextInput(value, _, _, _, _) => add(acc, value)
        case View.Toggle(checked, _, _) => add(acc, checked)
        case View.FetchTable(_, _, _, tick) => add(acc, tick)
        case View.Column(children, _, _, _) => children.foldLeft(acc)(loop)
        case View.Row(children, _, _, _) => children.foldLeft(acc)(loop)
        case View.Stack(children, _) => children.foldLeft(acc)(loop)
        case View.ScrollView(child, _, _) => loop(acc, child)
        case View.Fragment(children) => children.foldLeft(acc)(loop)
        case View.Element(_, _, _, children) => children.foldLeft(acc)(loop)
        case View.Show(_, whenTrue, whenFalse) => loop(loop(acc, whenTrue()), whenFalse())
        case View.ShowSignal(cond, whenTrue, whenFalse) => loop(loop(add(acc, cond), whenTrue), whenFalse)
        case View.For(items, render) => items().foldLeft(acc)((next, item) => loop(next, render(item)))
        case View.Styled(child, _) => loop(acc, child)
        case View.Adaptive(web, desktop, mobile, fallback) =>
          List(web, desktop, mobile).flatten.foldLeft(loop(acc, fallback))(loop)
        case _ => acc
    loop(Map.empty, view).values.toList.sortBy(_.id)
