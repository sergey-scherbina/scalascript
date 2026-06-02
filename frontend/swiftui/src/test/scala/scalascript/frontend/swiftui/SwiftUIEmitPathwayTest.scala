package scalascript.frontend.swiftui

import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import scalascript.frontend.*

/** Validates that `SwiftUIFrameworkBackend` is discoverable via
 *  `META-INF/services` and that the full emit pathway produces the
 *  three mandatory Swift Package files. */
class SwiftUIEmitPathwayTest extends AnyFunSuite:

  private def minimalModule: FrontendModule = FrontendModule(
    components   = List(ComponentDef("Main", Nil, _ => View.Text(() => "Hello", Style()))),
    entryPoint   = "Main",
    initialRoute = "/",
    appManifest  = Some(AppManifest("com.example.test", "TestApp", "1.0.0"))
  )

  // ── SPI discovery ────────────────────────────────────────────────────────

  test("SwiftUIFrameworkBackend is discovered via ServiceLoader") {
    val impls = ServiceLoader.load(classOf[FrontendFrameworkSpi]).iterator().asScala.toList
    assert(
      impls.exists(_.name == "swiftui"),
      s"Expected 'swiftui' in ServiceLoader, found: [${impls.map(_.name).mkString(", ")}]"
    )
  }

  test("ServiceLoader-resolved backend has correct name and platforms") {
    val backend = ServiceLoader.load(classOf[FrontendFrameworkSpi])
      .iterator().asScala
      .find(_.name == "swiftui")
      .getOrElse(fail("swiftui backend not found via ServiceLoader"))
    assert(backend.supportedPlatforms.contains(Platform.Mobile(MobileOs.iOS)))
    assert(backend.supportedPlatforms.contains(Platform.Desktop(DesktopOs.MacOS)))
    assert(!backend.supportedPlatforms.contains(Platform.Web))
  }

  // ── Emit pathway — iOS ───────────────────────────────────────────────────

  test("emitNative iOS produces Package.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Mobile(MobileOs.iOS))
    assert(art.isDefined, "emitNative returned None for iOS")
    assert(art.get.sources.contains("Package.swift"), s"No Package.swift in: ${art.get.sources.keys}")
  }

  test("emitNative iOS produces <App>App.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Mobile(MobileOs.iOS)).get
    val appEntry = art.sources.keys.find(_.endsWith("App.swift"))
    assert(appEntry.isDefined, s"No *App.swift in: ${art.sources.keys}")
  }

  test("emitNative iOS produces ContentView.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Mobile(MobileOs.iOS)).get
    val cv = art.sources.keys.find(_.endsWith("ContentView.swift"))
    assert(cv.isDefined, s"No ContentView.swift in: ${art.sources.keys}")
  }

  // ── Emit pathway — macOS ─────────────────────────────────────────────────

  test("emitNative macOS produces Package.swift + ContentView.swift + App.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Desktop(DesktopOs.MacOS)).get
    assert(art.sources.contains("Package.swift"))
    assert(art.sources.keys.exists(_.endsWith("ContentView.swift")))
    assert(art.sources.keys.exists(_.endsWith("App.swift")))
  }

  // ── buildScript ──────────────────────────────────────────────────────────

  test("emitNative build script is 'swift build'") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Mobile(MobileOs.iOS)).get
    assert(art.buildScript == "swift build")
  }

  // ── format ───────────────────────────────────────────────────────────────

  test("emitNative artifact format is SwiftUIApp") {
    val art = SwiftUIFrameworkBackend().emitNative(minimalModule, Platform.Mobile(MobileOs.iOS)).get
    assert(art.format == AppFormat.SwiftUIApp)
  }
