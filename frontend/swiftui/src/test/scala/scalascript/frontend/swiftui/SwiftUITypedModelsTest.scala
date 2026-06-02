package scalascript.frontend.swiftui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

class SwiftUITypedModelsTest extends AnyFunSuite:

  private val lineDef  = ModelDef("AssetLine", List(
    ModelField("code",   ModelFieldType.Str),
    ModelField("amount", ModelFieldType.DblF),
  ))
  private val sheetDef = ModelDef("BalanceSheet", List(
    ModelField("id",    ModelFieldType.Str),
    ModelField("total", ModelFieldType.DblF),
    ModelField("lines", ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))),
    ModelField("note",  ModelFieldType.Optional(ModelFieldType.Str)),
  ))

  private def contentViewOf(root: View[?], models: List[ModelDef] = Nil): String =
    val module = FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => root)),
      entryPoint   = "Main",
      initialRoute = "/",
      models       = models
    )
    SwiftUIFrameworkBackend()
      .emitNative(module, Platform.Mobile(MobileOs.iOS)).get
      .sources.find(_._1.endsWith("ContentView.swift")).get._2

  // ── emitModelStructs ──────────────────────────────────────────────────────

  test("emitModelStructs: scalar fields with Decodable+Identifiable (code field)") {
    val src = SwiftUIEmitter.emitModelStructs(List(lineDef))
    // 'code' field triggers Identifiable
    assert(src.contains("struct AssetLine: Decodable, Identifiable {"))
    assert(src.contains("let code: String"))
    assert(src.contains("let amount: Double"))
  }

  test("emitModelStructs: Identifiable when id field present") {
    val src = SwiftUIEmitter.emitModelStructs(List(sheetDef))
    assert(src.contains("struct BalanceSheet: Decodable, Identifiable {"))
    assert(!src.contains("var id: String { id }"))  // id field IS named id; no computed needed
  }

  test("emitModelStructs: Identifiable with non-id identifying field") {
    val src = SwiftUIEmitter.emitModelStructs(List(lineDef))
    // lineDef has 'code' as identifying field
    assert(src.contains("struct AssetLine: Decodable, Identifiable {"))
    assert(src.contains("var id: String { code }"))
  }

  test("emitModelStructs: List field") {
    val src = SwiftUIEmitter.emitModelStructs(List(sheetDef))
    assert(src.contains("let lines: [AssetLine]"))
  }

  test("emitModelStructs: Optional field") {
    val src = SwiftUIEmitter.emitModelStructs(List(sheetDef))
    assert(src.contains("let note: String?"))
  }

  test("emitModelStructs: Decodable-only when no identifying field") {
    val m = ModelDef("Payload", List(ModelField("value", ModelFieldType.DblF)))
    val src = SwiftUIEmitter.emitModelStructs(List(m))
    assert(src.contains("struct Payload: Decodable {"))
    assert(!src.contains("Identifiable"))
  }

  test("emitModelStructs: empty list produces empty string") {
    val src = SwiftUIEmitter.emitModelStructs(Nil)
    assert(src.isEmpty)
  }

  test("emitModelStructs: multiple models") {
    val src = SwiftUIEmitter.emitModelStructs(List(lineDef, sheetDef))
    assert(src.contains("struct AssetLine"))
    assert(src.contains("struct BalanceSheet"))
  }

  // ── ContentView: model structs appear before struct ContentView ───────────

  test("contentView: model structs emitted before ContentView") {
    val cv = contentViewOf(View.Text(() => "ok", Style()), models = List(lineDef))
    val structPos   = cv.indexOf("struct AssetLine")
    val contentPos  = cv.indexOf("struct ContentView")
    assert(structPos >= 0 && contentPos >= 0)
    assert(structPos < contentPos)
  }

  // ── FetchJsonSignal state decls ───────────────────────────────────────────

  test("FetchJsonSignal: typed state @State var emitted as optional model type") {
    val tick = new ReactiveSignal[Int]("tick", 0)
    val sig  = new FetchJsonSignal("balanceSheet", "/api/balance", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "bs", View.Text(() => "ok", Style()))
    val cv   = contentViewOf(view)
    assert(cv.contains("@State private var balanceSheet: BalanceSheet? = nil"))
    assert(cv.contains("@State private var balanceSheet_loading: Bool = false"))
    assert(cv.contains("@State private var balanceSheet_loaded: Bool = false"))
    assert(cv.contains("@State private var balanceSheet_error: String = \"\""))
  }

  // ── emitFetchMethods: typed JSONDecoder ──────────────────────────────────

  test("FetchJsonSignal: fetch method uses JSONDecoder") {
    val tick = new ReactiveSignal[Int]("tick", 0)
    val sig  = new FetchJsonSignal("bs", "/api/balance", tick.id, "BalanceSheet")
    val view = View.ModelView(sig, "data", View.Text(() => "hi", Style()))
    val cv   = contentViewOf(view)
    assert(cv.contains("JSONDecoder().decode(BalanceSheet.self, from: data)"))
    assert(cv.contains("bs_loading = true"))
    assert(cv.contains("bs_error = error.localizedDescription"))
  }

  // ── ModelView emitView case ──────────────────────────────────────────────

  test("ModelView: emits if-let binding") {
    val tick = new ReactiveSignal[Int]("tick", 0)
    val sig  = new FetchJsonSignal("sheet", "/api/sheet", tick.id, "BalanceSheet")
    val inner = View.Text(() => "inner", Style())
    val view = View.ModelView(sig, "bs", inner)
    val cv   = contentViewOf(view)
    assert(cv.contains("if let bs = sheet {"))
    assert(cv.contains("""Text("inner")"""))
  }

  // ── ForModel emitView case ───────────────────────────────────────────────

  test("ForModel: emits ForEach with field path") {
    val inner = View.Text(() => "row", Style())
    val view  = View.ForModel("bs", "lines", "line", inner)
    val cv    = contentViewOf(view)
    assert(cv.contains("ForEach(bs.lines, id: \\.self) { line in"))
    assert(cv.contains("""Text("row")"""))
  }

  // ── ModelText emitView case ──────────────────────────────────────────────

  test("ModelText: emits string interpolation") {
    val view = View.ModelText("bs", "total")
    val cv   = contentViewOf(view)
    assert(cv.contains("""Text("\(bs.total)")"""))
  }
