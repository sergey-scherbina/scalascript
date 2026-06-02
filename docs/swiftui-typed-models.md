# SwiftUI Typed JSON Models — v1.66

## 1. Problem

`FetchUrlSignal` is a `ReactiveSignal[String]` — the HTTP response body is
always stored as a raw JSON string.  The SwiftUI emitter generates:

```swift
@State private var balance: String = ""
// ...
balance = String(data: data, encoding: .utf8) ?? ""
```

For structured data — nested objects, arrays with field access, formatted
money values — displaying a raw JSON string to the user is not useful.  The
`busi` accounting app (Phase 20) had to be written entirely in hand-crafted
Swift because of this gap; ScalaScript could not generate the balance-sheet
view, aging reports, or audit log in any usable form.

## 2. Goals

- `@model case class` in a `.ssc` file → `struct X: Decodable` (+ `Identifiable`
  when an id field is present) in generated Swift.
- `FetchJsonSignal[T]` → `@State var name: T? = nil` + `JSONDecoder` load
  function (instead of raw `String`).
- `ModelView` / `ForModel` / `ModelText` IR nodes for typed template rendering:
  unwrap `Optional[T]`, iterate a nested array, display a dot-path field.
- `balance.isLoaded` / `balance.isLoading` guards for `Show` / `ProgressView`.
- A complete `busi-dashboard.ssc` smoke test that compiles via
  `ssc build --target mobile-ios` and passes `swiftc -parse`.

## 3. Non-goals

- JS/React/Vue/Solid backends (they receive typed data via `fetch().then(JSON.parse)`;
  typed models can be added there in a follow-up).
- Hot-reload / SwiftUI Previews (deferred per §2 of `docs/swiftui.md`).
- Compile-time JSON schema validation (path strings are not type-checked against
  the model at `.ssc` parse time; wrong paths produce a runtime Swift compiler
  error when the emitted package is built with `swift build`).

Cross-backend typed model parity is now tracked separately in
`docs/typed-models-ir.md`. This SwiftUI document remains the backend-specific
contract for Swift struct emission, optional state, and SwiftUI view lowering.

## 4. API

### 4.1 `@model case class` — typed JSON shape

```ssc
@model case class Money(amount: String, currency: String, formatted: String)
@model case class ReportLine(code: String, name: String, depth: Int, balance: Money)
@model case class Section(subtotal: Money, lines: List[ReportLine])
@model case class BalanceSheet(
  currency: String,
  assets: Section, liabilities: Section, equity: Section,
  netIncome: Money, balanced: Boolean
)
```

Rules:
- Fields may be `String`, `Int`, `Double`, `Boolean`, another `@model` type, or
  `List[X]` where `X` is any of the above.
- A field named `id`, `code`, `seq`, or `docId` causes the emitter to add
  `, Identifiable` to the struct protocol list and emit `var id: T { fieldName }`.
- Nested `@model` types must be declared before use (same file scope).
- The annotation is recognised only when `frontend: swiftui` is declared in the
  front-matter; it is ignored (or treated as a plain case class) by all other
  backends.

### 4.2 `FetchJsonSignal[T]` — typed fetch signal

```ssc
val balance = FetchJsonSignal[BalanceSheet](
  "balance",
  "/org/demo/json/balance-sheet/full",
  tick,
  headers = authHdr   // optional Signal[String], same as FetchUrlSignal
)
```

- `T` must be a `@model case class` declared in the same file.
- Companion properties auto-generated: `balance_loading: Bool`,
  `balance_error: String`.
- `balance.isLoaded`  → `ReactiveSignal[Boolean]` backed by `balance != nil`
  (emits `balance_loaded` `@State var`).
- `balance.isLoading` → backed by `balance_loading`.

### 4.3 `ModelView` — unwrap Optional typed signal

```ssc
ModelView(balance, "bs") {
  // bs: BalanceSheet is in scope — use ModelText / ForModel here
  Text("Balance Sheet")
  ModelText("bs", "currency")
}
```

Emits:
```swift
if let bs = balance {
    VStack {
        Text("Balance Sheet")
        Text(bs.currency)
    }
}
```

### 4.4 `ForModel` — iterate a nested array field

```ssc
ForModel("bs", "assets.lines", "line") {
  Row(spacing: 8) {
    ModelText("line", "name")
    Spacer()
    ModelText("line", "balance.formatted")
  }
}
```

Emits:
```swift
ForEach(bs.assets.lines, id: \.code) { line in
    HStack(spacing: 8) {
        Text(line.name)
        Spacer()
        Text(line.balance.formatted)
    }
}
```

- `fieldPath` is a dot-separated Swift member-access path relative to `bindingVar`.
- The `id:` key is inferred from the list element type's Identifiable field
  (`code`, `id`, `seq`, `docId`) if present; falls back to `\.self`.
- `ForModel` must appear inside a `ModelView` block (or another `ForModel` block)
  that declares the `bindingVar`.

### 4.5 `ModelText` — display a dot-path field

```ssc
ModelText("bs", "netIncome.formatted")
```

Emits `Text(bs.netIncome.formatted)`.

- Path may be arbitrarily deep (e.g. `"assets.subtotal.formatted"`).
- Empty `fieldPath` (`ModelText("bs", "")`) emits `Text(String(describing: bs))`.
- Style modifiers work the same as on `Text`.

## 5. Complete busi-dashboard example

```ssc
#!/usr/bin/env ssc
---
name: busi-dashboard
version: 1.0.0
frontend: swiftui
bundle-id: com.example.busi-dashboard
min-os:
  ios: "17"
  macos: "14"
---

@model case class Money(amount: String, currency: String, formatted: String)
@model case class ReportLine(code: String, name: String, depth: Int, balance: Money)
@model case class Section(subtotal: Money, lines: List[ReportLine])
@model case class BalanceSheet(currency: String,
  assets: Section, liabilities: Section, equity: Section,
  netIncome: Money, balanced: Boolean)

@model case class TrialRow(account: String, currency: String,
  debits: Money, credits: Money)
@model case class TrialBalance(rows: List[TrialRow], balanced: Boolean)

@model case class AuditEntry(seq: Int, at: String, actor: String,
  kind: String, detail: String)
@model case class AuditLog(audit: List[AuditEntry])

val tab      = Signal(0)
val tick     = Signal(0)
val authHdr  = Signal("""{"Authorization":"Bearer tok-owner"}""")
val baseUrl  = "http://localhost:8080/org/demo"

val balance = FetchJsonSignal[BalanceSheet]("balance",
  baseUrl + "/json/balance-sheet/full", tick, headers = authHdr)
val trialBal = FetchJsonSignal[TrialBalance]("trialBal",
  baseUrl + "/json/trial-balance", tick, headers = authHdr)
val auditLog = FetchJsonSignal[AuditLog]("auditLog",
  baseUrl + "/json/audit", tick, headers = authHdr)

def sectionBlock(bindingVar: String, secPath: String, title: String) =
  Column(spacing: 4) {
    Text(title).style(fontWeight = Bold)
    ForModel(bindingVar, secPath + ".lines", "line") {
      Row(spacing: 8) {
        ModelText("line", "name")
        Spacer()
        ModelText("line", "balance.formatted").style(foreground = Color.secondary)
      }
    }
    Row {
      Text("Total").style(fontWeight = Bold)
      Spacer()
      ModelText(bindingVar, secPath + ".subtotal.formatted").style(fontWeight = Bold)
    }
  }

def balanceSheetTab() =
  Show(balance.isLoading) { Text("Loading…") }
  Show(balance.isLoaded) {
    ModelView(balance, "bs") {
      Column(spacing: 16) {
        Text("Balance Sheet (" + ModelText("bs", "currency") + ")")
        sectionBlock("bs", "assets",      "Assets")
        sectionBlock("bs", "liabilities", "Liabilities")
        sectionBlock("bs", "equity",      "Equity")
        Divider()
        Row {
          Text("Net Income").style(fontWeight = Bold)
          Spacer()
          ModelText("bs", "netIncome.formatted").style(fontWeight = Bold)
        }
      }
    }
  }

def trialBalanceTab() =
  Show(trialBal.isLoaded) {
    ModelView(trialBal, "tb") {
      Column(spacing: 0) {
        ForModel("tb", "rows", "row") {
          Row(spacing: 8) {
            ModelText("row", "account")
            Spacer()
            ModelText("row", "debits.formatted")
            ModelText("row", "credits.formatted")
          }
        }
      }
    }
  }

def auditTab() =
  Show(auditLog.isLoaded) {
    ModelView(auditLog, "al") {
      Column(spacing: 0) {
        ForModel("al", "audit", "entry") {
          Column(spacing: 2) {
            Row {
              ModelText("entry", "kind").style(fontWeight = Bold)
              Spacer()
              ModelText("entry", "actor").style(foreground = Color.secondary)
            }
            ModelText("entry", "detail").style(foreground = Color.secondary)
          }
        }
      }
    }
  }

def view() =
  Column {
    Button("Refresh") { tick += 1 }
      .padding(8)
    TabView(selection = tab) {
      balanceSheetTab().tabItem("Balance Sheet", icon = "chart.bar.doc.horizontal", tag = 0)
      trialBalanceTab().tabItem("Trial Balance", icon = "list.number", tag = 1)
      auditTab().tabItem("Audit", icon = "doc.text.magnifyingglass", tag = 2)
    }
  }
```

## 6. IR changes

### 6.1 `Primitives.scala` additions

```scala
// --- New: typed JSON fetch signal ---
// Extends FetchUrlSignal — adds modelTypeName for codegen only.
// T is erased in the IR; modelTypeName carries the @model class name.
final class FetchJsonSignal[T](
    id2: String,
    fetchUrl: String,
    tickId: String,
    val modelTypeName: String,
    headersId: Option[String] = None
) extends FetchUrlSignal(id2, fetchUrl, tickId, headersId)

// Companion signals auto-generated in ContentView:
//   @State private var <id>_loading: Bool = false
//   @State private var <id>_loaded:  Bool = false
//   @State private var <id>_error:   String = ""

// isLoaded / isLoading — convenience projections for Show guards
// In .ssc: balance.isLoaded expands to a ReactiveSignal[Boolean]
// backed by @State var balance_loaded: Bool = false

// --- New View nodes (add to enum View[+A]) ---
enum View[+A]:
  // ... existing cases ...

  // Renders template inside `if let bindingVar = signal { ... }`
  case ModelView(
    signal: FetchJsonSignal[?],
    bindingVar: String,
    template: View[?],
    style: Style = Style()
  ) extends View[Nothing]

  // Renders template inside ForEach over bindingVar.fieldPath
  // Must appear inside ModelView or another ForModel that declared bindingVar.
  case ForModel(
    bindingVar: String,
    fieldPath: String,    // dot-path, e.g. "assets.lines"
    itemVar: String,
    template: View[?],
    style: Style = Style()
  ) extends View[Nothing]

  // Emits Text(varName.fieldPath) — dot-path → Swift member access
  case ModelText(
    varName: String,
    fieldPath: String,
    style: Style = Style()
  ) extends View[Nothing]
```

### 6.2 Module-level model registry

The ScalaScript frontend collects `@model case class` declarations into a new
`Module.models: List[ModelDef]` field (or equivalent), where:

```scala
case class ModelField(name: String, tpe: ModelFieldType)
enum ModelFieldType:
  case Str, BoolF, IntF, DblF
  case Nested(name: String)
  case ListOf(inner: ModelFieldType)

case class ModelDef(name: String, fields: List[ModelField])
```

The SwiftUI emitter reads `module.models` and emits Decodable structs before
the `ContentView` body.

## 7. SwiftUI emitter changes

### 7.1 Decodable struct generation

```scala
def emitModelStructs(models: List[ModelDef]): String =
  models.map { m =>
    val idField = m.fields.find(f => Seq("id","code","seq","docId").contains(f.name))
    val protocols = if idField.isDefined then "Decodable, Identifiable" else "Decodable"
    val idLine = idField.map { f =>
      s"\n    var id: ${swiftFieldType(f.tpe)} { ${f.name} }"
    }.getOrElse("")
    val fields = m.fields.map { f =>
      s"    let ${f.name}: ${swiftFieldType(f.tpe)}"
    }.mkString("\n")
    s"struct ${m.name}: $protocols {\n$fields$idLine\n}"
  }.mkString("\n\n")

def swiftFieldType(t: ModelFieldType): String = t match
  case Str           => "String"
  case BoolF         => "Bool"
  case IntF          => "Int"
  case DblF          => "Double"
  case Nested(name)  => name
  case ListOf(inner) => s"[${swiftFieldType(inner)}]"
```

### 7.2 `FetchJsonSignal` load function

Replace the `String` decode line with typed `JSONDecoder`:

```scala
// In emitFetchMethods, detect FetchJsonSignal subtype:
case fs: FetchJsonSignal[?] =>
  s"""${pad}private func _load_${fs.id}() async {
     |${pad2}${fs.id}_loading = true
     |${pad2}defer { ${fs.id}_loading = false }
     |${pad2}guard let _url = URL(string: ${swiftStringLit(fs.fetchUrl)}) else { return }
     |${pad2}do {
     |${pad3}var _req = URLRequest(url: _url)
     |${headerLines(fs, pad3)}
     |${pad3}let (data, _) = try await URLSession.shared.data(for: _req)
     |${pad3}${fs.id} = try JSONDecoder().decode(${fs.modelTypeName}.self, from: data)
     |${pad3}${fs.id}_loaded = true
     |${pad2}} catch { ${fs.id}_error = error.localizedDescription }
     |${pad}}""".stripMargin
```

`@State` declaration for `FetchJsonSignal`:
```swift
@State private var balance: BalanceSheet? = nil
@State private var balance_loading: Bool = false
@State private var balance_loaded: Bool = false
@State private var balance_error: String = ""
```

### 7.3 New view node emit

```scala
case View.ModelView(signal, bindingVar, template, style) =>
  val body = emitView(template, indent + 4, ctx)
  emitMods(s"${pad}if let $bindingVar = ${signal.id} {\n$body\n${pad}}", style, indent)

case View.ForModel(bindingVar, fieldPath, itemVar, template, style) =>
  val body = emitView(template, indent + 4, ctx)
  val idKey = inferIdentifiableKey(fieldPath)  // heuristic: "code"|"id"|"seq"|"self"
  val forEach = s"ForEach($bindingVar.$fieldPath, id: \\.$idKey)"
  emitMods(s"$pad$forEach { $itemVar in\n$body\n${pad}}", style, indent)

case View.ModelText(varName, fieldPath, style) =>
  val ref = if fieldPath.isEmpty then varName else s"$varName.$fieldPath"
  emitMods(s"${pad}Text($ref)", style, indent)

// Helper: infer Identifiable id key from the last path segment's element type name
// e.g. "assets.lines" → look up ReportLine → has "code" field → "code"
// Falls back to "self" when unknown.
private def inferIdentifiableKey(fieldPath: String): String =
  // Best effort: check known model types for id/code/seq/docId fields.
  // If the emitter has access to Module.models, resolve the element type.
  "self"  // safe default; can be improved with model registry lookup
```

### 7.4 `isLoaded` / `isLoading` in `ShowSignal`

`balance.isLoaded` in `.ssc` lowers to `ReactiveSignal[Boolean](id + "_loaded", false)`.
The emitter already handles `ShowSignal(cond, whenTrue, whenFalse)` — no new emit
case needed; only the `@State var balance_loaded: Bool = false` companion must be
declared alongside the typed signal.

## 8. Testing strategy

### Phase 1 — `@model` structs + `FetchJsonSignal` (unit)

- `@model Money(amount, currency, formatted)` → emits `struct Money: Decodable { ... }`
- `@model ReportLine(code, ...)` → emits `struct ReportLine: Decodable, Identifiable { ... var id: String { code } }`
- `@model Section(subtotal: Money, lines: List[ReportLine])` → field types: `Money`, `[ReportLine]`
- `@model BalanceSheet(...)` with nested types → full Decodable chain
- `FetchJsonSignal[BalanceSheet]("balance", url, tick)` → `@State var balance: BalanceSheet? = nil`
- Load function uses `JSONDecoder().decode(BalanceSheet.self, from: data)` not `String(...)`
- Companion vars `balance_loading`, `balance_loaded`, `balance_error` declared
- `balance.isLoaded` → `ShowSignal` using `balance_loaded`

Target: **10 unit tests** in `SwiftUIEmitterTest`.

### Phase 2 — `ModelView` / `ForModel` / `ModelText` (unit)

- `ModelView(balance, "bs") { ModelText("bs", "currency") }` → `if let bs = balance { Text(bs.currency) }`
- `ModelView` + nested `ForModel("bs", "assets.lines", "line") { ... }` → `ForEach(bs.assets.lines, ...) { line in ... }`
- `ModelText("line", "balance.formatted")` → `Text(line.balance.formatted)`
- Deep path: `ModelText("bs", "netIncome.formatted")` → `Text(bs.netIncome.formatted)`
- Style modifiers on `ModelText` → appended SwiftUI modifiers
- `ForModel` without explicit id field → `id: \.self`
- `ForModel` with `code` field → `id: \.code`
- Empty `fieldPath` in `ModelText` → `Text(String(describing: varName))`
- `ModelView` with loading guard: `Show(balance.isLoaded) { ModelView(...) }` → `if balance_loaded { if let bs = balance { ... } }`

Target: **12 unit tests** in `SwiftUIEmitterTest`.

### Phase 3 — busi dashboard smoke test

- `examples/frontend/busi-dashboard/busi-dashboard.ssc` with full BalanceSheet +
  TrialBalance + AuditLog models, three tabs, all new nodes.
- `ssc build --target mobile-ios` exits 0.
- `swiftc -parse Sources/BusiDashboard/ContentView.swift` exits 0 (skip when
  `swift` not on PATH; gate with `assume(isSwiftAvailable)`).
- `AppModel.swift` contains all four Decodable structs.

Target: **8 smoke tests** in `SwiftUIModelSmokeTest`.

## 9. Phases

| Phase | Slug | Effort |
|-------|------|--------|
| 1 | `v1.66.1-swiftui-model-structs` | ~2 days — `@model` parse + codegen, `FetchJsonSignal` typed state |
| 2 | `v1.66.2-swiftui-model-view-nodes` | ~2 days — `ModelView` / `ForModel` / `ModelText` IR + emitter |
| 3 | `v1.66.3-swiftui-busi-dashboard` | ~1 day — example `.ssc` + smoke test |

## 10. Alternatives considered

### A. Lambda templates (`ModelView(balance) { bs => ... }`)
Requires the ScalaScript interpreter to evaluate templates with a "proxy" object
that intercepts field access and records paths. Complex to implement correctly
across all interpreter modes (fast-tier, JVM gen, etc.).  Deferred to a future
pass once there is demand for non-string field projections.

### B. JSONPath string queries at runtime
Runtime `JsonQuery("$.assets.lines[*].name", balance)` evaluated in Swift via a
JSONPath library.  Adds a third-party Swift dependency; harder to type-check.

### C. React/Vue parity first
Add `@model` to the JS emitters so they decode JSON into typed objects via
`JSON.parse` + TypeScript interfaces.  Orthogonal; can be added in a follow-up
without blocking the SwiftUI use case.

## 11. Open questions

1. Should `ForModel` be allowed outside a `ModelView` (using a top-level
   `FetchJsonSignal` binding directly)?  For now: no — require `ModelView` wrapping.
2. Should `ModelText` emit `Text(x ?? "")` when the field could be `Optional`?
   For now: trust that the field is non-optional in the model.
3. Should depth-based indentation for `ReportLine` be a built-in modifier or
   left to the user's `.ssc` view code?  Left to the user.

## 12. Cross-backend follow-up notes

Use `docs/typed-models-ir.md` as the canonical source for the shared `ModelDef`,
`FetchJsonSignal`, `CodecHint`, and `ModelView` / `ForModel` / `ModelText`
contracts. SwiftUI-specific lowering should still use `ModelPathResolver` for
field paths and identifying keys rather than maintaining a separate heuristic.

The decode contract is attached to `FetchUrlSignal.codec`. Future SwiftUI
changes should preserve raw-text behavior for `CodecHint.RawText` and route
typed JSON only through `CodecHint.Json(modelTypeName)`.
