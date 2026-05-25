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
