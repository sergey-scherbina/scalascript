package scalascript.frontend.swing

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

import javax.swing.{JButton, JLabel, JTable, SwingUtilities}

class SwingFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers SwingFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "swing"),
      s"Expected 'swing' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + no JS dependencies") {
    val backend = SwingFrameworkBackend()
    assert(backend.name == "swing")
    assert(backend.capabilities.contains(Capability.ComponentTree))
    assert(backend.capabilities.contains(Capability.SignalState))
    assert(backend.jsDeps.isEmpty)
    assert(backend.supportedPlatforms.contains(Platform.Desktop(DesktopOs.MacOS)))
    assert(!backend.supportedPlatforms.contains(Platform.Web))
  }

  test("web emit fails loudly") {
    val backend = SwingFrameworkBackend()
    val ex = intercept[UnsupportedOperationException] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("JVM desktop frontend"))
  }

  test("emitNative produces minimal Swing app source") {
    val backend = SwingFrameworkBackend()
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.Text(() => "Hello Swing"),
        View.Button(View.Text(() => "Add"), EventHandler.Simple(() => ()))
      ))
    )
    val manifest = AppManifest("com.example.swing", "Swing Demo", "1.0.0")
    val artifact = backend.emitNative(
      FrontendModule(List(app), "App", "/", appManifest = Some(manifest)),
      Platform.Desktop(DesktopOs.Linux)
    ).get

    assert(artifact.format == AppFormat.SwingApp)
    assert(artifact.target == Platform.Desktop(DesktopOs.Linux))
    assert(artifact.manifest == manifest)
    assert(artifact.buildScript == "scala-cli run .")
    val source = artifact.sources("src/main/scala/Main.scala")
    assert(source.contains("import javax.swing.*"))
    assert(source.contains("""JFrame("Swing Demo")"""))
    assert(source.contains("""JLabel("Hello Swing")"""))
    assert(source.contains("""JButton("Add")"""))
  }

  test("emitNative lowers toolkit subset controls and layout") {
    val backend = SwingFrameworkBackend()
    val name = ReactiveSignal[String]("name", "Alice")
    val accept = ReactiveSignal[Boolean]("accept", true)
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.Row(Seq(
          View.Text(() => "Name").fontSize(18).bold.foreground(Color.Named("red")),
          View.TextInput(name, placeholder = "Your name")
        ), spacing = 12),
        View.Toggle(accept, "Accept terms"),
        View.Divider(),
        View.ScrollView(View.Text(() => "Scrollable body"))
      ), spacing = 8).padding(10).background(Color.Hex("#f8f8f8"))
    )
    val source = backend.emitNative(
      FrontendModule(List(app), "App", "/"),
      Platform.Desktop()
    ).get.sources("src/main/scala/Main.scala")

    assert(source.contains("panel.setLayout(BoxLayout(panel, BoxLayout.Y_AXIS))"))
    assert(source.contains("panel.setLayout(BoxLayout(panel, BoxLayout.X_AXIS))"))
    assert(source.contains("Box.createRigidArea(Dimension(12, 0))"))
    assert(source.contains("Box.createRigidArea(Dimension(0, 8))"))
    assert(source.contains("""JLabel("Name")"""))
    assert(source.contains("label.setForeground(Color.RED)"))
    assert(source.contains("label.setFont(label.getFont.deriveFont(Font.BOLD, 18.0f))"))
    assert(source.contains("""mutable.Map[String, Any]("accept" -> true, "name" -> "Alice")"""))
    assert(source.contains("""JTextField(signalString(signals, "name"), 32)"""))
    assert(source.contains("""field.putClientProperty("JTextField.placeholderText", "Your name")"""))
    assert(source.contains("""JCheckBox("Accept terms", signalBoolean(signals, "accept"))"""))
    assert(source.contains("""checkbox.addActionListener(_ => setSignal(signals, bindings, "accept", checkbox.isSelected))"""))
    assert(source.contains("JSeparator(SwingConstants.HORIZONTAL)"))
    assert(source.contains("JScrollPane(scrollPanel)"))
    assert(source.contains("panel.setOpaque(true)"))
    assert(source.contains("""panel.setBackground(Color.decode("#f8f8f8"))"""))
    assert(source.contains("panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))"))
  }

  test("emitNative wires simple signal actions and refresh bindings") {
    val backend = SwingFrameworkBackend()
    val count = ReactiveSignal[Int]("count", 0)
    val enabled = ReactiveSignal[Boolean]("enabled", false)
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.SignalText(count),
        View.Button(View.Text(() => "Increment"), EventHandler.IncrementSignal(count, by = 2)),
        View.Button(View.Text(() => "Reset"), EventHandler.SetSignalLiteral(count, 0)),
        View.Button(View.Text(() => "Toggle"), EventHandler.ToggleSignal(enabled)),
        View.Toggle(enabled, "Enabled")
      ))
    )
    val source = backend.emitNative(
      FrontendModule(List(app), "App", "/"),
      Platform.Desktop()
    ).get.sources("src/main/scala/Main.scala")

    assert(source.contains("""mutable.Map[String, Any]("count" -> 0, "enabled" -> false)"""))
    assert(source.contains("""bindSignal(bindings, "count")"""))
    assert(source.contains("""label.setText(signalString(signals, "count"))"""))
    assert(source.contains("""button.addActionListener(_ => incrementSignal(signals, bindings, "count", 2))"""))
    assert(source.contains("""button.addActionListener(_ => setSignal(signals, bindings, "count", 0))"""))
    assert(source.contains("""button.addActionListener(_ => toggleSignal(signals, bindings, "enabled"))"""))
    assert(source.contains("""bindSignal(bindings, "enabled")"""))
  }

  test("emitNative rejects non-desktop platforms") {
    val backend = SwingFrameworkBackend()
    val app = ComponentDef("App", Nil, _ => View.Text(() => "x"))
    val module = FrontendModule(List(app), "App", "/")
    assert(backend.emitNative(module, Platform.Web).isEmpty)
    assert(backend.emitNative(module, Platform.Mobile(MobileOs.Android)).isEmpty)
  }

  test("emitNative unknown entryPoint throws with helpful message") {
    val backend = SwingFrameworkBackend()
    val ex = intercept[IllegalArgumentException] {
      backend.emitNative(FrontendModule(Nil, "Missing", "/"), Platform.Desktop())
    }
    assert(ex.getMessage.contains("Missing"))
    assert(ex.getMessage.contains("entryPoint"))
  }

  test("SwingRuntime builds component tree and handles local signal actions in-process") {
    val count = ReactiveSignal[Int]("count", 0)
    val root = View.Element("div", Map.empty, Map.empty, Seq(
      View.SignalText(count),
      View.Button(View.Text(() => "Increment"), EventHandler.IncrementSignal(count))
    ))
    val panel = SwingRuntime.buildRoot(root)
    val label = findFirst[JLabel](panel).getOrElse(fail("missing label"))
    val button = findFirst[JButton](panel).getOrElse(fail("missing button"))

    assert(label.getText == "0")
    button.doClick()
    assert(label.getText == "1")
  }

  test("SwingRuntime refreshes bindings when ReactiveSignal changes externally") {
    val count = ReactiveSignal[Int]("count", 0)
    val root = View.SignalText(count)
    val panel = SwingRuntime.buildRoot(root)
    val label = findFirst[JLabel](panel).getOrElse(fail("missing label"))

    assert(label.getText == "0")
    count.set(7)
    SwingUtilities.invokeAndWait(() => ())
    assert(label.getText == "7")
  }

  test("SwingRuntime dispatches FetchAction through configured in-process dispatcher") {
    val body = ReactiveSignal[String]("body", "payload")
    val tick = ReactiveSignal[Int]("tick", 0)
    val calls = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
    val dispatcher = new SwingRuntime.FetchDispatcher:
      def request(method: String, url: String, requestBody: String): SwingRuntime.FetchResponse =
        calls += ((method, url, requestBody))
        SwingRuntime.FetchResponse(204)
    val root = View.Element("div", Map.empty, Map.empty, Seq(
      View.SignalText(tick),
      View.SignalText(body),
      View.Button(
        View.Text(() => "Save"),
        EventHandler.FetchAction("POST", "/api/items?q=1", body, tick, clearBody = true)
      )
    ))
    val state = SwingRuntime.RuntimeState.from(root, Some(dispatcher))
    val panel = SwingRuntime.buildRoot(root, state)
    val labels = findAll[JLabel](panel)
    val button = findFirst[JButton](panel).getOrElse(fail("missing button"))

    assert(labels.map(_.getText) == List("0", "payload"))
    button.doClick()
    assert(calls.toList == List(("POST", "/api/items?q=1", "payload")))
    assert(labels.map(_.getText) == List("1", ""))
  }

  test("SwingRuntime renders DataTable as JTable with column headers and rows from fetch") {
    val tick   = ReactiveSignal[Int]("tick", 0)
    val signal = FetchUrlSignal("empRows", "/api/employees", tick.id)
    val calls  = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
    var rows   = List("1" -> "Alice", "2" -> "Bob")
    val dispatcher = new SwingRuntime.FetchDispatcher:
      def request(method: String, url: String, body: String): SwingRuntime.FetchResponse =
        calls += ((method, url, body))
        method match
          case "GET"  =>
            val json = rows.map((id, n) => s"""{"id":"$id","name":"$n"}""").mkString("[",",","]")
            SwingRuntime.FetchResponse(200, json)
          case "POST" =>
            rows = rows.filterNot(_._1 == body)
            SwingRuntime.FetchResponse(204)
          case _      => SwingRuntime.FetchResponse(405)

    val testRows = rows.map((id, n) => Map[String, Any]("id" -> id, "name" -> n))
    val testDecoder: JsonDecoder = (_, _) => testRows

    val dt = View.DataTable(
      source  = TableDataSource.Remote(signal),
      columns = List(FieldColumnDef("ID", "id"), FieldColumnDef("Name", "name")),
      actions = List(RowActionDef.RowDelete("/api/employees/delete", "id", tick))
    )
    val root  = View.Element("div", Map.empty, Map.empty, Seq(dt))
    val state = SwingRuntime.RuntimeState.from(root, Some(dispatcher))
    val panel = JsonDecoder.withDecoder(testDecoder) { SwingRuntime.buildRoot(root, state) }

    // Initial fetch happened synchronously
    assert(calls.toList == List(("GET", "/api/employees", "")))

    val tables = findAll[JTable](panel)
    assert(tables.nonEmpty, "expected JTable in panel")
    val jt = tables.head
    assert(jt.getColumnCount == 2)
    assert(jt.getColumnName(0) == "ID")
    assert(jt.getColumnName(1) == "Name")
    assert(jt.getRowCount == 2)
    assert(jt.getValueAt(0, 1) == "Alice")
    assert(jt.getValueAt(1, 1) == "Bob")

    // Select row 0 and click Delete — should POST the id and re-fetch
    jt.setRowSelectionInterval(0, 0)
    val deleteBtn = findAll[JButton](panel).find(_.getText == "Delete").getOrElse(fail("no Delete button"))
    // Simulate re-fetch after delete: update testDecoder to reflect new state
    rows = rows.tail
    val updatedRows = rows.map((id, n) => Map[String, Any]("id" -> id, "name" -> n))
    val updatedDecoder: JsonDecoder = (_, _) => updatedRows
    JsonDecoder.withDecoder(updatedDecoder) { deleteBtn.doClick() }

    assert(calls.toList == List(
      ("GET", "/api/employees", ""),
      ("POST", "/api/employees/delete", "1"),
      ("GET", "/api/employees", "")
    ))
    assert(jt.getRowCount == 1)
    assert(jt.getValueAt(0, 1) == "Bob")
  }

  private def findFirst[A](root: java.awt.Container)(using ct: reflect.ClassTag[A]): Option[A] =
    root.getComponents.iterator.foldLeft(Option.empty[A]) {
      case (found @ Some(_), _) => found
      case (None, a: A) => Some(a)
      case (None, c: java.awt.Container) => findFirst[A](c)
      case (None, _) => None
    }

  private def findAll[A](root: java.awt.Container)(using ct: reflect.ClassTag[A]): List[A] =
    root.getComponents.iterator.toList.flatMap {
      case a: A => List(a)
      case c: java.awt.Container => findAll[A](c)
      case _ => Nil
    }
