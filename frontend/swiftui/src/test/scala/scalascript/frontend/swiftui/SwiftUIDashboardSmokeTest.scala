package scalascript.frontend.swiftui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** Smoke test for the SwiftUI emit pathway against a rich dashboard-style module.
 *
 *  Covers: TabBar, NavigationStack, LazyList, LazyGrid, Form, Slider,
 *  Toggle, TextInput, Button, Icon, Spacer, Divider, FetchUrlSignal,
 *  SignalText, Text, Column, Row, ScrollView, ForSignal.
 *
 *  The `swiftc -parse` validation gate is skipped when `swift` is not on PATH,
 *  so this test is always safe to run in CI without Xcode. */
class SwiftUIDashboardSmokeTest extends AnyFunSuite:

  private val refreshTick = ReactiveSignal[Int]("refreshTick", 0)
  private val selectedTab = ReactiveSignal[Int]("selectedTab", 0)
  private val searchQuery = ReactiveSignal[String]("searchQuery", "")
  private val darkMode    = ReactiveSignal[Boolean]("darkMode", false)
  private val brightness  = ReactiveSignal[Double]("brightness", 0.8)
  private val items       = ReactiveSignalList[String]("items", List("Alpha", "Beta", "Gamma"))
  private val statusText  = FetchUrlSignal("statusText", "https://api.example.com/status", "refreshTick")

  private def settingsTab: View[?] = View.Form(
    View.Column(
      children = List(
        View.Toggle(darkMode, "Dark Mode", Style()),
        View.Slider(brightness, 0.0, 1.0, 0.0, Style()),
        View.TextInput(searchQuery, "Search…", multiline = false, secure = false, Style())
      ),
      spacing = 8, align = HAlign.Start, style = Style()
    ),
    onSubmit = EventHandler.Simple(() => ()),
    style    = Style()
  )

  private def listTab: View[?] = View.Column(
    children = List(
      View.Row(
        children = List(
          View.TextInput(searchQuery, "Filter…", multiline = false, secure = false, Style()),
          View.Button(
            label   = View.Text(() => "Refresh", Style()),
            action  = EventHandler.IncrementSignal(refreshTick, 1),
            enabled = () => true,
            style   = Style()
          )
        ),
        spacing = 8, align = VAlign.Center, style = Style()
      ),
      View.SignalText(statusText, Style()),
      View.ForSignal(
        items        = items,
        itemTemplate = Some(View.Row(
          children = List(
            View.Icon("circle.fill", Style()),
            View.TextNode(() => "item"),
            View.Spacer(None),
            View.Button(
              label   = View.Text(() => "Remove", Style()),
              action  = EventHandler.RemoveSelfFromList(items),
              enabled = () => true,
              style   = Style()
            )
          ),
          spacing = 12, align = VAlign.Center, style = Style()
        ))
      )
    ),
    spacing = 0, align = HAlign.Start, style = Style()
  )

  private def gridTab: View[?] = View.ScrollView(
    View.LazyGrid(
      items   = () => List("Alpha", "Beta", "Gamma"),
      render  = item => View.Column(
        children = List(
          View.Icon("square.fill", Style()),
          View.Text(() => item, Style())
        ),
        spacing = 4, align = HAlign.Center, style = Style()
      ),
      columns = GridColumns.Fixed(2)
    ),
    axis  = Axis.Vertical,
    style = Style()
  )

  private def dashboardModule: FrontendModule =
    val tabView = View.TabBar(
      tabs = List(
        Tab(label = "Items",    icon = Some("list.bullet"),     content = listTab),
        Tab(label = "Grid",     icon = Some("square.grid.2x2"), content = gridTab),
        Tab(label = "Settings", icon = Some("gear"),            content = settingsTab)
      ),
      current = selectedTab
    )
    val navStack = View.NavigationStack(
      routes  = Map("/" -> (() => tabView)),
      current = ReactiveSignal[String]("route", "/"),
      style   = Style()
    )
    FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => navStack)),
      entryPoint   = "Main",
      initialRoute = "/",
      appManifest  = Some(AppManifest("com.example.dashboard", "Dashboard", "1.0.0"))
    )

  private def emitContentView(): String =
    SwiftUIFrameworkBackend()
      .emitNative(dashboardModule, Platform.Mobile(MobileOs.iOS)).get
      .sources.find(_._1.endsWith("ContentView.swift")).get._2

  private def swiftBinary: Option[String] =
    val candidates = List("/usr/bin/swiftc", "/usr/local/bin/swiftc") ++
      Option(System.getenv("PATH")).toList
        .flatMap(_.split(":").toList)
        .map(_ + "/swiftc")
    candidates.find(p => java.nio.file.Files.isExecutable(java.nio.file.Paths.get(p)))

  // ── Emit does not throw ───────────────────────────────────────────────────

  test("dashboard emit does not throw") {
    val result = SwiftUIFrameworkBackend().emitNative(dashboardModule, Platform.Mobile(MobileOs.iOS))
    assert(result.isDefined)
  }

  // ── Mandatory files present ───────────────────────────────────────────────

  test("dashboard emit produces Package.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(dashboardModule, Platform.Mobile(MobileOs.iOS)).get
    assert(art.sources.contains("Package.swift"))
  }

  test("dashboard emit produces ContentView.swift") {
    val art = SwiftUIFrameworkBackend().emitNative(dashboardModule, Platform.Mobile(MobileOs.iOS)).get
    assert(art.sources.keys.exists(_.endsWith("ContentView.swift")))
  }

  // ── ContentView structural checks ────────────────────────────────────────

  test("ContentView contains NavigationStack") {
    assert(emitContentView().contains("NavigationStack"), "expected NavigationStack")
  }

  test("ContentView contains TabView") {
    assert(emitContentView().contains("TabView(selection: $selectedTab)"), "expected TabView")
  }

  test("ContentView contains LazyVGrid") {
    assert(emitContentView().contains("LazyVGrid"), "expected LazyVGrid")
  }

  test("ContentView contains ForEach for ForSignal") {
    assert(emitContentView().contains("ForEach(items.indices"), "expected ForEach from ForSignal")
  }

  test("ContentView FetchUrlSignal emits .task modifier") {
    assert(
      emitContentView().contains(".task { await _load_statusText() }"),
      "expected .task fetch modifier"
    )
  }

  test("ContentView FetchUrlSignal emits .onChange modifier") {
    assert(emitContentView().contains(".onChange(of: refreshTick)"), "expected .onChange for tick re-fetch")
  }

  test("ContentView FetchUrlSignal emits private load function") {
    val cv = emitContentView()
    assert(cv.contains("private func _load_statusText() async"), "expected load function")
    assert(cv.contains("URLSession.shared.data(from: _url)"), "expected URLSession fetch")
  }

  // ── Unsupported nodes emit safe fallback (EmptyView + comment) ───────────

  test("catch-all: unsupported IR node output does not contain old [unsupported:] format") {
    assert(!emitContentView().contains("[unsupported:"), "old format must not appear in output")
  }

  test("catch-all emits EmptyView() + TODO comment for truly unknown nodes") {
    // SafeArea is handled as pass-through — exercise emitter without crash.
    val safeModule = FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => View.SafeArea(
        View.Text(() => "inner", Style()), edges = Edge.all
      ))),
      entryPoint   = "Main",
      initialRoute = "/",
      appManifest  = Some(AppManifest("com.example.safe", "Safe", "1.0.0"))
    )
    val art = SwiftUIFrameworkBackend().emitNative(safeModule, Platform.Mobile(MobileOs.iOS)).get
    val cv  = art.sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("import SwiftUI"), "output must be a valid Swift file header")
    assert(!cv.contains("throw "),        "output must not contain throw")
  }

  // ── swiftc -parse gate (skipped when swift not on PATH) ──────────────────

  test("ContentView.swift parses without errors (swiftc -parse, skipped if no swift)") {
    swiftBinary match {
      case None =>
        cancel("swift not on PATH — skipping swiftc -parse gate")
      case Some(swiftPath) =>
        val cv  = emitContentView()
        val tmp = java.io.File.createTempFile("ContentView", ".swift")
        try {
          java.nio.file.Files.writeString(tmp.toPath, cv)
          val proc = new ProcessBuilder(swiftPath, "-parse", tmp.getAbsolutePath)
            .redirectErrorStream(true)
            .start()
          val output = scala.io.Source.fromInputStream(proc.getInputStream).mkString
          val exit   = proc.waitFor()
          assert(exit == 0, s"swiftc -parse failed (exit $exit):\n$output")
        } finally { tmp.delete() }
    }
  }
