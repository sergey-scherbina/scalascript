package scalascript.frontend.swiftui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

/** Smoke test for the busi-dashboard IR against the SwiftUI emitter.
 *
 *  Covers: @model structs (BalanceSheet/TrialBalance/AuditLog), FetchJsonSignal
 *  state declarations, ModelView if-let, ForModel ForEach, ModelText interpolation,
 *  three-tab TabView, and the swiftc -parse gate.
 *
 *  The `swiftc -parse` gate is skipped when `swift` is not on PATH. */
class SwiftUIModelSmokeTest extends AnyFunSuite:

  // ── Model defs ─────────────────────────────────────────────────────────────

  private val assetLineDef = ModelDef("AssetLine", List(
    ModelField("code",   ModelFieldType.Str),
    ModelField("amount", ModelFieldType.DblF)
  ))

  private val balanceSheetDef = ModelDef("BalanceSheet", List(
    ModelField("id",    ModelFieldType.Str),
    ModelField("total", ModelFieldType.DblF),
    ModelField("lines", ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))),
    ModelField("note",  ModelFieldType.Optional(ModelFieldType.Str))
  ))

  private val trialEntryDef = ModelDef("TrialEntry", List(
    ModelField("account", ModelFieldType.Str),
    ModelField("debit",   ModelFieldType.DblF),
    ModelField("credit",  ModelFieldType.DblF)
  ))

  private val trialBalanceDef = ModelDef("TrialBalance", List(
    ModelField("id",      ModelFieldType.Str),
    ModelField("period",  ModelFieldType.Str),
    ModelField("entries", ModelFieldType.ListOf(ModelFieldType.Nested("TrialEntry")))
  ))

  private val auditEventDef = ModelDef("AuditEvent", List(
    ModelField("seq",       ModelFieldType.Str),
    ModelField("timestamp", ModelFieldType.Str),
    ModelField("action",    ModelFieldType.Str),
    ModelField("user",      ModelFieldType.Str)
  ))

  private val auditLogDef = ModelDef("AuditLog", List(
    ModelField("id",     ModelFieldType.Str),
    ModelField("events", ModelFieldType.ListOf(ModelFieldType.Nested("AuditEvent")))
  ))

  private val allModels = List(
    assetLineDef, balanceSheetDef,
    trialEntryDef, trialBalanceDef,
    auditEventDef, auditLogDef
  )

  // ── Signals ────────────────────────────────────────────────────────────────

  private val bsTick    = ReactiveSignal[Int]("bsTick", 0)
  private val tbTick    = ReactiveSignal[Int]("tbTick", 0)
  private val auditTick = ReactiveSignal[Int]("auditTick", 0)
  private val selTab    = ReactiveSignal[Int]("selectedTab", 0)

  private val bsSig    = new FetchJsonSignal("balanceSheet",  "/api/balance-sheet",  bsTick.id,    "BalanceSheet")
  private val tbSig    = new FetchJsonSignal("trialBalance",  "/api/trial-balance",  tbTick.id,    "TrialBalance")
  private val auditSig = new FetchJsonSignal("auditLog",      "/api/audit-log",      auditTick.id, "AuditLog")

  // ── View tree ──────────────────────────────────────────────────────────────

  private def balanceTab: View[?] = View.ModelView(bsSig, "bs",
    View.Column(List(
      View.Row(List(
        View.Text(() => "Total:", Style()),
        View.ModelText("bs", "total")
      ), 4, VAlign.Center, Style()),
      View.ForModel("bs", "lines", "line",
        View.Row(List(
          View.ModelText("line", "code"),
          View.ModelText("line", "amount")
        ), 8, VAlign.Center, Style()),
        Style()
      ),
      View.ModelText("bs", "note")
    ), 8, HAlign.Start, Style())
  )

  private def trialTab: View[?] = View.ModelView(tbSig, "tb",
    View.Column(List(
      View.Row(List(
        View.Text(() => "Period:", Style()),
        View.ModelText("tb", "period")
      ), 4, VAlign.Center, Style()),
      View.ForModel("tb", "entries", "entry",
        View.Row(List(
          View.ModelText("entry", "account"),
          View.ModelText("entry", "debit"),
          View.ModelText("entry", "credit")
        ), 8, VAlign.Center, Style()),
        Style()
      )
    ), 8, HAlign.Start, Style())
  )

  private def auditTab: View[?] = View.ModelView(auditSig, "al",
    View.Column(List(
      View.ForModel("al", "events", "event",
        View.Column(List(
          View.Row(List(
            View.ModelText("event", "timestamp"),
            View.ModelText("event", "action")
          ), 4, VAlign.Center, Style()),
          View.ModelText("event", "user")
        ), 2, HAlign.Start, Style()),
        Style()
      )
    ), 8, HAlign.Start, Style())
  )

  private def dashboardModule: FrontendModule =
    val root = View.TabBar(
      tabs = List(
        Tab("Balance", Some("chart.bar.fill"),         balanceTab),
        Tab("Trial",   Some("list.number"),            trialTab),
        Tab("Audit",   Some("doc.text.magnifyingglass"), auditTab)
      ),
      current = selTab
    )
    FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => root)),
      entryPoint   = "Main",
      initialRoute = "/",
      models       = allModels
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

  test("busi-dashboard emit does not throw") {
    val result = SwiftUIFrameworkBackend().emitNative(dashboardModule, Platform.Mobile(MobileOs.iOS))
    assert(result.isDefined)
  }

  // ── Model structs ─────────────────────────────────────────────────────────

  test("model structs: all six types emitted") {
    val cv = emitContentView()
    assert(cv.contains("struct AssetLine"),    "AssetLine missing")
    assert(cv.contains("struct BalanceSheet"), "BalanceSheet missing")
    assert(cv.contains("struct TrialEntry"),   "TrialEntry missing")
    assert(cv.contains("struct TrialBalance"), "TrialBalance missing")
    assert(cv.contains("struct AuditEvent"),   "AuditEvent missing")
    assert(cv.contains("struct AuditLog"),     "AuditLog missing")
  }

  test("model structs: appear before struct ContentView") {
    val cv = emitContentView()
    assert(cv.indexOf("struct AssetLine") < cv.indexOf("struct ContentView"))
  }

  test("model structs: Decodable conformance on all types") {
    val cv = emitContentView()
    assert(cv.contains("struct BalanceSheet: Decodable"))
    assert(cv.contains("struct TrialBalance: Decodable"))
    assert(cv.contains("struct AuditLog: Decodable"))
  }

  // ── FetchJsonSignal state declarations ───────────────────────────────────

  test("FetchJsonSignal: @State declarations for all three signals") {
    val cv = emitContentView()
    assert(cv.contains("@State private var balanceSheet: BalanceSheet? = nil"))
    assert(cv.contains("@State private var trialBalance: TrialBalance? = nil"))
    assert(cv.contains("@State private var auditLog: AuditLog? = nil"))
  }

  test("FetchJsonSignal: JSONDecoder used in fetch methods") {
    val cv = emitContentView()
    assert(cv.contains("JSONDecoder().decode(BalanceSheet.self"), "BalanceSheet decode")
    assert(cv.contains("JSONDecoder().decode(TrialBalance.self"), "TrialBalance decode")
    assert(cv.contains("JSONDecoder().decode(AuditLog.self"),     "AuditLog decode")
  }

  // ── ModelView ─────────────────────────────────────────────────────────────

  test("ModelView: if-let bindings for all three signals") {
    val cv = emitContentView()
    assert(cv.contains("if let bs = balanceSheet {"),  "bs binding")
    assert(cv.contains("if let tb = trialBalance {"),  "tb binding")
    assert(cv.contains("if let al = auditLog {"),      "al binding")
  }

  // ── ForModel ──────────────────────────────────────────────────────────────

  test("ForModel: ForEach for balance sheet lines") {
    val cv = emitContentView()
    assert(cv.contains("ForEach(bs.lines"), "ForEach bs.lines")
  }

  test("ForModel: ForEach for trial balance entries") {
    val cv = emitContentView()
    assert(cv.contains("ForEach(tb.entries"), "ForEach tb.entries")
  }

  test("ForModel: ForEach for audit log events") {
    val cv = emitContentView()
    assert(cv.contains("ForEach(al.events"), "ForEach al.events")
  }

  // ── ModelText ─────────────────────────────────────────────────────────────

  test("ModelText: interpolated field references present") {
    val cv = emitContentView()
    assert(cv.contains("""\(bs.total)"""),      "bs.total")
    assert(cv.contains("""\(tb.period)"""),     "tb.period")
    assert(cv.contains("""\(event.timestamp)"""), "event.timestamp")
  }

  // ── swiftc -parse gate ────────────────────────────────────────────────────

  test("ContentView.swift parses without errors (swiftc -parse, skipped if no swift)") {
    swiftBinary match {
      case None =>
        cancel("swift not on PATH — skipping swiftc -parse gate")
      case Some(swiftPath) =>
        val cv  = emitContentView()
        val tmp = java.io.File.createTempFile("BusiDashboard", ".swift")
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
