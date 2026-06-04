package scalascript.frontend.javafx

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class JavaFxFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers JavaFxFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "javafx"),
      s"Expected 'javafx' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + no JS dependencies") {
    val backend = JavaFxFrameworkBackend()
    assert(backend.name == "javafx")
    assert(backend.capabilities.contains(Capability.ComponentTree))
    assert(backend.capabilities.contains(Capability.SignalState))
    assert(backend.jsDeps.isEmpty)
    assert(backend.supportedPlatforms.contains(Platform.Desktop(DesktopOs.MacOS)))
    assert(backend.supportedPlatforms.contains(Platform.Desktop(DesktopOs.Linux)))
    assert(backend.supportedPlatforms.contains(Platform.Desktop(DesktopOs.Windows)))
    assert(!backend.supportedPlatforms.contains(Platform.Web))
  }

  test("web emit fails loudly") {
    val backend = JavaFxFrameworkBackend()
    val ex = intercept[UnsupportedOperationException] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("JVM desktop frontend"))
  }

  test("emitNative returns None for web platform") {
    val backend = JavaFxFrameworkBackend()
    val result = backend.emitNative(FrontendModule(Nil, "App", "/"), Platform.Web)
    assert(result.isEmpty)
  }

  test("emitNative produces standalone Scala/JavaFX source") {
    val backend = JavaFxFrameworkBackend()
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.Text(() => "Hello JavaFX"),
        View.Button(View.Text(() => "Click me"), EventHandler.Simple(() => ()))
      ))
    )
    val manifest = AppManifest("com.example.javafx", "JavaFX Demo", "1.0.0")
    val artifact = backend.emitNative(
      FrontendModule(List(app), "App", "/", appManifest = Some(manifest)),
      Platform.Desktop(DesktopOs.Linux)
    ).get

    assert(artifact.format == AppFormat.JavaFxApp)
    assert(artifact.target == Platform.Desktop(DesktopOs.Linux))
    assert(artifact.manifest == manifest)
    assert(artifact.buildScript == "scala-cli run .")
    val source = artifact.sources("src/main/scala/Main.scala")
    assert(source.contains("//> using dep \"org.openjfx:javafx-controls:"))
    assert(source.contains("import javafx.application.Application"))
    assert(source.contains("class App extends Application"))
    assert(source.contains("Application.launch(classOf[App]"))
    assert(source.contains("JavaFX Demo"))
    assert(source.contains("""Label("Hello JavaFX")"""))
    assert(source.contains("""Button("Click me")"""))
  }

  test("emitNative lowers layout, signals, toggle, text input, divider, scroll") {
    val backend = JavaFxFrameworkBackend()
    val name    = ReactiveSignal[String]("name", "Alice")
    val accept  = ReactiveSignal[Boolean]("accept", true)
    val counter = ReactiveSignal[Int]("counter", 0)
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.Row(Seq(
          View.Text(() => "Name").fontSize(18).bold.foreground(Color.Named("red")),
          View.TextInput(name, placeholder = "Your name")
        ), spacing = 12),
        View.SignalText(counter),
        View.Button(View.Text(() => "Increment"), EventHandler.IncrementSignal(counter, 1)),
        View.Toggle(accept, "Accept terms"),
        View.Divider(),
        View.ScrollView(View.Text(() => "Scrollable body"))
      ), spacing = 8).padding(10).background(Color.Hex("#f8f8f8"))
    )
    val source = backend.emitNative(
      FrontendModule(List(app), "App", "/"),
      Platform.Desktop(DesktopOs.MacOS)
    ).get.sources("src/main/scala/Main.scala")

    assert(source.contains("HBox(12.0"))
    assert(source.contains("VBox(8.0"))
    assert(source.contains("-fx-font-size: 18px"))
    assert(source.contains("-fx-font-weight: bold"))
    assert(source.contains("-fx-text-fill: red"))
    assert(source.contains("-fx-background-color: #f8f8f8"))
    assert(source.contains("-fx-padding: 10px"))
    assert(source.contains("""TextField(signalString(signals, "name"))"""))
    assert(source.contains("""CheckBox("Accept terms")"""))
    assert(source.contains("Separator(Orientation.HORIZONTAL)"))
    assert(source.contains("ScrollPane(innerBox)"))
    assert(source.contains("""incrementSignal(signals, bindings, "counter", 1)"""))
    assert(source.contains("""bindSignal(bindings, "counter")"""))
    assert(source.contains(""""name" -> "Alice""""))
    assert(source.contains(""""counter" -> 0"""))
    assert(source.contains(""""accept" -> true"""))
  }

  test("emitNative lowers SeedSignal with pristine source sync") {
    val backend = JavaFxFrameworkBackend()
    val sourceSignal = ReactiveSignal[String]("serverName", "Alice")
    val draft = SeedSignal("draftName", sourceSignal)
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Column(Seq(
        View.TextInput(draft, placeholder = "Draft"),
        View.Button(View.Text(() => "Refresh"), EventHandler.SetSignalLiteral(sourceSignal, "Bob")),
        View.Button(View.Text(() => "Edit"), EventHandler.SetSignalLiteral(draft, "Carol"))
      ))
    )
    val source = backend.emitNative(
      FrontendModule(List(app), "App", "/"),
      Platform.Desktop(DesktopOs.MacOS)
    ).get.sources("src/main/scala/Main.scala")

    assert(source.contains(""""draftName" -> "Alice""""))
    assert(source.contains(""""serverName" -> "Alice""""))
    assert(source.contains("""val seedPristine = mutable.Map[String, Boolean]("draftName" -> true)"""))
    assert(source.contains("""val seedSources = Map[String, String]("draftName" -> "serverName")"""))
    assert(source.contains("""setSeedSignal(signals, bindings, seedPristine, seedSources, "draftName", newVal)"""))
    assert(source.contains("""setSeedSignal(signals, bindings, seedPristine, seedSources, "serverName", "Bob")"""))
    assert(source.contains("""setSeedSignal(signals, bindings, seedPristine, seedSources, "draftName", "Carol")"""))
    assert(source.contains("preserveSeedPristine = true"))
  }

  test("emitStyle CSS — colors, border, padding") {
    val backend = JavaFxFrameworkBackend()
    val app = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Text(() => "Styled")
        .foreground(Color.Rgb(255, 0, 0))
        .background(Color.Rgba(0, 0, 255, 0.5))
        .border(Color.Transparent, 2)
        .italic
    )
    val source = backend.emitNative(
      FrontendModule(List(app), "App", "/"),
      Platform.Desktop(DesktopOs.Windows)
    ).get.sources("src/main/scala/Main.scala")

    assert(source.contains("-fx-text-fill: rgb(255, 0, 0)"))
    assert(source.contains("-fx-background-color: rgb(0, 0, 255)"))
    assert(source.contains("-fx-border-color: transparent"))
    assert(source.contains("-fx-border-width: 2px"))
    assert(source.contains("-fx-font-style: italic"))
  }

  test("emitNative uses mac-aarch64 classifier on Apple Silicon (mocked via property)") {
    val origName = sys.props.getOrElse("os.name", "")
    val origArch = sys.props.getOrElse("os.arch", "")
    sys.props.put("os.name", "Mac OS X")
    sys.props.put("os.arch", "aarch64")
    try
      val backend = JavaFxFrameworkBackend()
      val app = ComponentDef("App", Nil, _ => View.Text(() => "test"))
      val source = backend.emitNative(
        FrontendModule(List(app), "App", "/"),
        Platform.Desktop(DesktopOs.MacOS)
      ).get.sources("src/main/scala/Main.scala")
      assert(source.contains(":mac-aarch64\""))
    finally
      sys.props.put("os.name", origName)
      sys.props.put("os.arch", origArch)
  }
