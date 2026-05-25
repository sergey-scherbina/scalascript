package scalascript.frontend.swing

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

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
