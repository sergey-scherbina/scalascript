package scalascript.frontend.swiftui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class SwiftUIEmitterTest extends AnyFunSuite:

  private def makeModule(root: View[?]): FrontendModule =
    FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => root)),
      entryPoint   = "Main",
      initialRoute = "/"
    )

  private def backend = SwiftUIFrameworkBackend()

  // ── emit() must throw ────────────────────────────────────────────────────

  test("emit() throws for non-web target") {
    val spi = backend
    intercept[UnsupportedOperationException] {
      spi.emit(makeModule(View.Text(() => "hi", Style())))
    }
  }

  // ── supportedPlatforms ───────────────────────────────────────────────────

  test("supports iOS and macOS") {
    val spi = backend
    assert(spi.supportedPlatforms.contains(Platform.Mobile(MobileOs.iOS)))
    assert(spi.supportedPlatforms.contains(Platform.Desktop(DesktopOs.MacOS)))
    assert(!spi.supportedPlatforms.contains(Platform.Web))
  }

  // ── emitNative returns None for unmatched platform ───────────────────────

  test("emitNative returns None for web platform") {
    val result = backend.emitNative(makeModule(View.Text(() => "hi", Style())), Platform.Web)
    assert(result.isEmpty)
  }

  // ── Package.swift ────────────────────────────────────────────────────────

  test("packageSwift contains app name and iOS platform") {
    val pkg = SwiftUIEmitter.packageSwift("MyApp", "17", "14", includeIos = true, includeMacos = false)
    assert(pkg.contains("name: \"MyApp\""))
    assert(pkg.contains(".iOS(.v17)"))
    assert(!pkg.contains(".macOS"))
  }

  test("packageSwift macOS-only") {
    val pkg = SwiftUIEmitter.packageSwift("MyApp", "17", "14", includeIos = false, includeMacos = true)
    assert(pkg.contains(".macOS(.v14)"))
    assert(!pkg.contains(".iOS"))
  }

  // ── App.swift ────────────────────────────────────────────────────────────

  test("appSwift contains @main and WindowGroup") {
    val swift = SwiftUIEmitter.appSwift("MyApp")
    assert(swift.contains("@main"))
    assert(swift.contains("struct MyAppApp: App"))
    assert(swift.contains("WindowGroup"))
    assert(swift.contains("ContentView()"))
  }

  // ── ContentView — basic Text ──────────────────────────────────────────────

  test("Text view emits Text(...)") {
    val art = backend.emitNative(makeModule(View.Text(() => "Hello, World!", Style())), Platform.Mobile(MobileOs.iOS))
    assert(art.isDefined)
    val cv  = art.get.sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Text("Hello, World!")"""))
    assert(cv.contains("struct ContentView: View"))
    assert(cv.contains("var body: some View"))
  }

  // ── SignalText emits interpolated string ──────────────────────────────────

  test("SignalText emits @State var and string interpolation") {
    val sig = ReactiveSignal[Int]("count", 0)
    val view = View.SignalText(sig, Style())
    val art = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS))
    val cv  = art.get.sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("@State private var count: Int = 0"))
    assert(cv.contains("""Text("\(count)")"""))
  }

  // ── Button ────────────────────────────────────────────────────────────────

  test("Button with SetSignalLiteral emits Swift action") {
    val sig  = ReactiveSignal[Boolean]("active", false)
    val view = View.Button(
      label  = View.Text(() => "Click", Style()),
      action = EventHandler.SetSignalLiteral(sig, true),
      enabled = () => true,
      style  = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Button("Click")"""))
    assert(cv.contains("active = true"))
    assert(cv.contains("@State private var active: Bool = false"))
  }

  test("Button with IncrementSignal emits += action") {
    val sig  = ReactiveSignal[Int]("counter", 0)
    val view = View.Button(
      label  = View.Text(() => "+", Style()),
      action = EventHandler.IncrementSignal(sig, 1),
      enabled = () => true,
      style  = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("counter += 1"))
  }

  test("Button with ToggleSignal emits .toggle()") {
    val sig  = ReactiveSignal[Boolean]("visible", true)
    val view = View.Button(
      label  = View.Text(() => "Toggle", Style()),
      action = EventHandler.ToggleSignal(sig),
      enabled = () => true,
      style  = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("visible.toggle()"))
  }

  // ── TextInput ─────────────────────────────────────────────────────────────

  test("TextInput emits TextField with binding") {
    val sig  = ReactiveSignal[String]("username", "")
    val view = View.TextInput(sig, "Enter name", multiline = false, secure = false, Style())
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""TextField("Enter name", text: $username)"""))
    assert(cv.contains("@State private var username: String = \"\""))
  }

  test("secure TextInput emits SecureField") {
    val sig  = ReactiveSignal[String]("pwd", "")
    val view = View.TextInput(sig, "Password", multiline = false, secure = true, Style())
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("SecureField"))
  }

  // ── Toggle ────────────────────────────────────────────────────────────────

  test("Toggle emits Toggle with binding") {
    val sig  = ReactiveSignal[Boolean]("enabled", false)
    val view = View.Toggle(sig, "Enable notifications", Style())
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Toggle("Enable notifications", isOn: $enabled)"""))
  }

  // ── Column / Row ─────────────────────────────────────────────────────────

  test("Column emits VStack") {
    val view = View.Column(
      children = Seq(View.Text(() => "A", Style()), View.Text(() => "B", Style())),
      spacing  = 8,
      align    = HAlign.Start,
      style    = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("VStack(alignment: .leading, spacing: 8)"))
    assert(cv.contains("""Text("A")"""))
    assert(cv.contains("""Text("B")"""))
  }

  test("Row emits HStack") {
    val view = View.Row(
      children = Seq(View.Text(() => "X", Style())),
      spacing  = 4,
      align    = VAlign.Center,
      style    = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("HStack(alignment: .center, spacing: 4)"))
  }

  // ── Stack ─────────────────────────────────────────────────────────────────

  test("Stack emits ZStack") {
    val view = View.Stack(
      children = Seq(View.Text(() => "Back", Style()), View.Text(() => "Front", Style())),
      style    = Style()
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("ZStack"))
  }

  // ── Divider / Spacer ──────────────────────────────────────────────────────

  test("Divider emits Divider()") {
    val view = View.Divider(Axis.Horizontal, Style())
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("Divider()"))
  }

  test("Spacer with size emits minLength") {
    val view = View.Spacer(Some(16.0))
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("Spacer(minLength: 16)"))
  }

  test("Spacer without size emits plain Spacer()") {
    val view = View.Spacer(None)
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("Spacer()"))
  }

  // ── Style modifiers ───────────────────────────────────────────────────────

  test("padding style emits .padding()") {
    import scalascript.frontend.EdgeInsets
    val style = Style(layout = LayoutStyle(padding = EdgeInsets.all(16)))
    val view  = View.Text(() => "padded", style)
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains(".padding(16)"))
  }

  test("foreground color emits .foregroundStyle()") {
    val style = Style(text = TextStyle(foreground = Some(Color.Named("red"))))
    val view  = View.Text(() => "red text", style)
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains(".foregroundStyle(Color.red)"))
  }

  test("cornerRadius emits .cornerRadius()") {
    val style = Style(decoration = DecorationStyle(borderRadius = BorderRadius.all(8.0)))
    val view  = View.Text(() => "rounded", style)
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains(".cornerRadius(8)"))
  }

  // ── Show / Fragment ───────────────────────────────────────────────────────

  test("Show(true) renders whenTrue branch") {
    val view = View.Show(
      cond      = () => true,
      whenTrue  = () => View.Text(() => "yes", Style()),
      whenFalse = () => View.Text(() => "no", Style())
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Text("yes")"""))
    assert(!cv.contains("""Text("no")"""))
  }

  test("Fragment emits all children") {
    val view = View.Fragment(Seq(
      View.Text(() => "First", Style()),
      View.Text(() => "Second", Style())
    ))
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Text("First")"""))
    assert(cv.contains("""Text("Second")"""))
  }

  // ── Adaptive — picks mobile branch ───────────────────────────────────────

  test("Adaptive picks mobile branch for iOS") {
    val view = View.Adaptive(
      web      = Some(View.Text(() => "web", Style())),
      desktop  = Some(View.Text(() => "desktop", Style())),
      mobile   = Some(View.Text(() => "mobile", Style())),
      fallback = View.Text(() => "fallback", Style())
    )
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""Text("mobile")"""))
  }

  // ── ScrollView ────────────────────────────────────────────────────────────

  test("ScrollView emits SwiftUI ScrollView") {
    val view = View.ScrollView(View.Text(() => "content", Style()), Axis.Vertical, Style())
    val cv = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("ScrollView(.vertical)"))
  }

  // ── macOS platform ────────────────────────────────────────────────────────

  test("macOS platform emits SwiftUIApp artifact") {
    val art = backend.emitNative(
      makeModule(View.Text(() => "Mac app", Style())),
      Platform.Desktop(DesktopOs.MacOS)
    )
    assert(art.isDefined)
    assert(art.get.format == AppFormat.SwiftUIApp)
    val pkg = art.get.sources.find(_._1 == "Package.swift").get._2
    assert(pkg.contains(".macOS(.v14)"))
    assert(!pkg.contains(".iOS"))
  }

  // ── swiftIdent ────────────────────────────────────────────────────────────

  test("swiftIdent strips non-identifier chars and capitalizes") {
    assert(SwiftUIEmitter.swiftIdent("My App!") == "MyApp")
    assert(SwiftUIEmitter.swiftIdent("hello world") == "Helloworld")
    assert(SwiftUIEmitter.swiftIdent("123abc") == "App123abc")
  }

  // ── swiftString escaping ──────────────────────────────────────────────────

  test("swiftString escapes special chars") {
    assert(SwiftUIEmitter.swiftString("say \"hi\"") == """say \"hi\"""")
    assert(SwiftUIEmitter.swiftString("line\nnewline") == "line\\nnewline")
  }

  // ── Phase 3: ForSignal → ForEach ─────────────────────────────────────────

  test("ForSignal without template emits ForEach Text(item)") {
    val list = ReactiveSignalList[String]("todos", Seq("a", "b"))
    val view = View.ForSignal(list, "li", Map.empty, itemTemplate = None)
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("ForEach(todos, id: \\.self) { item in"))
    assert(cv.contains("Text(item)"))
    assert(cv.contains("@State private var todos: [String] = []"))
  }

  test("ForSignal with item template emits ForEach with indices") {
    val list = ReactiveSignalList[String]("items", Seq.empty)
    val tmpl = View.Text(() => "row", Style())
    val view = View.ForSignal(list, "li", Map.empty, itemTemplate = Some(tmpl))
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("ForEach(items.indices, id: \\.self) { __idx_items in"))
    assert(cv.contains("""Text("row")"""))
  }

  test("RemoveSelfFromList inside ForSignal emits list.remove(at:)") {
    val list   = ReactiveSignalList[String]("items", Seq.empty)
    val button = View.Button(
      label   = View.Text(() => "Delete", Style()),
      action  = EventHandler.RemoveSelfFromList(list),
      enabled = () => true,
      style   = Style()
    )
    val view = View.ForSignal(list, "li", Map.empty, itemTemplate = Some(button))
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("items.remove(at: __idx_items)"))
  }

  test("RemoveSelfFromList outside ForSignal emits comment") {
    val list   = ReactiveSignalList[String]("items", Seq.empty)
    val button = View.Button(
      label   = View.Text(() => "Delete", Style()),
      action  = EventHandler.RemoveSelfFromList(list),
      enabled = () => true,
      style   = Style()
    )
    val cv = backend.emitNative(makeModule(button), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("// RemoveSelfFromList: use inside ForSignal"))
    assert(!cv.contains("remove(at:)"))
  }

  test("PushSignalLiteral in button emits .append()") {
    val list   = ReactiveSignalList[String]("todos", Seq.empty)
    val button = View.Button(
      label   = View.Text(() => "Add", Style()),
      action  = EventHandler.PushSignalLiteral(list, "new item"),
      enabled = () => true,
      style   = Style()
    )
    val cv = backend.emitNative(makeModule(button), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("""todos.append("new item")"""))
  }

  test("ClearSignalList in button emits .removeAll()") {
    val list   = ReactiveSignalList[String]("items", Seq("a", "b"))
    val button = View.Button(
      label   = View.Text(() => "Clear", Style()),
      action  = EventHandler.ClearSignalList(list),
      enabled = () => true,
      style   = Style()
    )
    val cv = backend.emitNative(makeModule(button), Platform.Mobile(MobileOs.iOS)).get
                    .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("items.removeAll()"))
  }

  // ── Phase 3: @Observable AppModel generation ─────────────────────────────

  test("appModelSwift returns None when module has no list signals") {
    val view   = View.Text(() => "hello", Style())
    val module = makeModule(view)
    assert(SwiftUIEmitter.appModelSwift("App", module).isEmpty)
  }

  test("appModelSwift returns Some with @Observable class when lists exist") {
    val list   = ReactiveSignalList[String]("todos", Seq("a"))
    val view   = View.ForSignal(list, "li", Map.empty, None)
    val module = makeModule(view)
    val src    = SwiftUIEmitter.appModelSwift("MyApp", module)
    assert(src.isDefined)
    assert(src.get.contains("@Observable final class AppModel"))
    assert(src.get.contains("var todos: [String] = []"))
    assert(src.get.contains("import Observation"))
  }

  test("emitNative includes AppModel.swift when list signals are present") {
    val list   = ReactiveSignalList[String]("items", Seq.empty)
    val view   = View.ForSignal(list, "li", Map.empty, None)
    val art    = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS))
    assert(art.isDefined)
    val sourceKeys = art.get.sources.keys.toSet
    assert(sourceKeys.exists(_.endsWith("AppModel.swift")))
    val modelSrc = art.get.sources.find(_._1.endsWith("AppModel.swift")).get._2
    assert(modelSrc.contains("@Observable final class AppModel"))
    assert(modelSrc.contains("var items: [String] = []"))
  }

  test("emitNative does NOT include AppModel.swift when no list signals") {
    val view = View.Text(() => "hello", Style())
    val art  = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS))
    assert(art.isDefined)
    assert(!art.get.sources.keys.exists(_.endsWith("AppModel.swift")))
  }

  test("ForSignal with remove button inside Row — ForCtx threaded through container") {
    val list      = ReactiveSignalList[String]("tasks", Seq.empty)
    val removeBtn = View.Button(
      label   = View.Text(() => "x", Style()),
      action  = EventHandler.RemoveSelfFromList(list),
      enabled = () => true,
      style   = Style()
    )
    val row  = View.Row(children = Seq(View.Text(() => "Task", Style()), removeBtn), spacing = 8, align = VAlign.Center, style = Style())
    val view = View.ForSignal(list, "li", Map.empty, itemTemplate = Some(row))
    val cv   = backend.emitNative(makeModule(view), Platform.Mobile(MobileOs.iOS)).get
                      .sources.find(_._1.endsWith("ContentView.swift")).get._2
    assert(cv.contains("tasks.remove(at: __idx_tasks)"))
  }
