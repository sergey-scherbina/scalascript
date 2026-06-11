# `std.ui` — Content-toolkit: Action Registry + Live-signal Binding

Status: **3a (action registry) ✓ landed 2026-06-10; 3b (live-signal binding) ✓ landed 2026-06-11.**
Tracked as `ui-content-toolkit` in BACKLOG.md. Origin: busi UI proposals (P3).

> **3a as built:** `toolkit:button?action=<id>` Markdown links bind to an EventHandler
> registered in `ContentToolkitOptions.actions` (a `Map[String, EventHandler]`, e.g.
> `["saveDraft" -> fetchJsonAction(...)]`). The toolkit resolves the id at lower time →
> an `ActionButtonNode` wired to the handler; an unregistered id fails loudly. This builds
> on the existing toolkit control machinery (signals/components/bindings) — it adds the
> *write* half. `.ssc`: `ContentToolkitOptions.actions`, `contentAction(id, handler)`,
> `contentToolkitOptionsWithActions`. Impl: `content-plugin` `ContentIntrinsics`.
>
> **3b as built (2026-06-11):** `toolkit:table?rows=<id>` Markdown links bind to a
> `ContentRowBinding` registered in `ContentToolkitOptions.rowBindings` (a
> `Map[String, ContentRowBinding]`). The binding carries `rows` (a runtime fetch
> `Signal`), `columns` (the same `fcol(title, fieldPath)` field-column descriptors
> `dataTable` accepts), and optional per-row `actions`. The toolkit resolves the id at
> lower time → a `DataTableNode`, reusing the existing DataTable lowering (web `<table>`
> / native `JTable`); an unregistered id fails loudly, listing the available ids — the
> same fail-loud principle as 3a. `.ssc`: `ContentToolkitOptions.rowBindings`,
> `ContentRowBinding`, `contentRows(id, rows, columns, actions?)`,
> `contentToolkitOptionsWithRows`. Impl: `content-plugin` `ContentIntrinsics`
> (`rowBindingRegistry` + the `case "table"` toolkit-link branch). Example:
> `examples/content-live-rows.ssc`. Tests: `ContentPluginInterpreterTest` (2) +
> `MarkdownContentFrontendSmokeTest` (2, end-to-end through `emit`).
>
> The original sketch (below) proposed a dedicated `contentBindRows(...)` extern and a
> `TableColumn` column type; as built, the binding is registered through the existing
> toolkit-options machinery (mirroring 3a actions) and reuses `fcol`/`DataTableNode`
> rather than introducing a parallel column type — simpler and with no new lowering.

Original (deferred) sketch follows.

Origin: busi UI proposals (P3) — `busi/docs/scalascript-ui-proposals.md`.
Related: busi `specs/markdown-yaml-ui-authoring.md`, `runtime/std/ui/content.ssc`,
`lower.ssc`, the parked `ToolkitDsl.scala`. Companion specs:
[`std-ui-typed-json.md`](std-ui-typed-json.md), [`std-ui-styled-tknode.md`](std-ui-styled-tknode.md).

---

## 1. Motivation

The content toolkit is good for *static* Markdown/YAML-authored content but cannot
express live data or server writes, which is why busi keeps all real screens code-first.
Two capabilities unlock Markdown-authored *write* screens:

1. **Action-id registry** — `toolkit:` links bind to typed server effects by stable id.
2. **Live-signal binding** — a content region renders rows from a runtime signal
   (a fetch result), not from an authored static YAML fence.

This is the strategic bet for content-authored screens. It is **deferred**: busi
screens are write-heavy, so the value of content-authoring is conditional, and P1/P2
deliver regardless of whether we commit to it. This spec records the agreed design so
the decision can be made later without re-deriving it.

---

## 2. Action-id registry (unlock writes)

Markdown stays declarative and inspectable; the typed capability (endpoint, auth, retry,
refresh, audit) stays in ScalaScript code, bound by a stable id.

```markdown
- [Save draft](toolkit:button?action=saveDraft&enabledWhen=enabled)
```

```scalascript
// Bind an action id to an EventHandler (e.g. fetchJsonAction from P1).
case class ContentAction(id: String, handler: EventHandler)
def contentAction(id: String, handler: EventHandler): ContentAction

// Register a set of actions for a content section; toolkit: links resolve by id.
def contentToolkitActions(actions: List[ContentAction]): Any

val actions = contentToolkitActions([
  contentAction("saveDraft", fetchJsonAction("PUT", url, () => bodySig(), tick, authHdr))
])
val page = lower(contentToolkitSection("controls", actions), defaultTheme)
```

Unresolved `action=` ids are a **loud** error at lower time (not a silent no-op button),
listing the available ids — the same fail-loud principle as P4b.

---

## 3. Live-signal binding into a content region

`contentData(id)` reads a **static** fenced data block. To show real data in a Markdown
section, bind a **runtime signal** (a fetch result) into a content table/component:

```scalascript
// Bind a Signal[JsonValue] (from fetchJsonSignal, P1) as the row source for a
// GFM table region authored in Markdown. Columns map to JsonValue field paths.
def contentBindRows(regionId: String, rows: Signal[JsonValue],
                    columns: List[TableColumn]): Any
```

The authored Markdown declares the table shape (headers); the rows come from the live
signal at render time. Reuses the P1 `JsonValue` navigation for column extraction and
the existing `DataTableNode` lowering — no new table renderer.

---

## 4. Backend policy

Architecturally clean: both capabilities produce ordinary `TkNode`s lowered via the
existing `lower(view, Theme)` path, so they inherit web + native lowering. The action
registry is a resolution pass over `toolkit:` links; the signal binding reuses
`DataTableNode` + the P1 fetch signal. No backend-specific surface beyond what P1
already introduces.

---

## 5. Dependencies and sequencing

- **Depends on P1** (`fetchJsonSignal` / `fetchJsonAction` / `JsonValue`) — actions
  write JSON bodies; row binding reads `Signal[JsonValue]`.
- **Benefits from P2** (styled primitives) for the rendered chrome.
- Relates to the parked `ToolkitDsl.scala` (see project memory `project_parked_toolkit`)
  — reconcile with that work before starting.

Do **not** start until P1 lands and the team commits to content-authored screens.
First busi consumer would be Rule Pack Studio + Settings descriptions, then
opportunistic static handoff prose on Phase 79 screens.

---

## 6. Non-goals

- A Markdown-authoring IDE / live preview.
- Arbitrary client-side logic in Markdown (the typed capability stays in `.ssc`).
- Replacing code-first screens — this is additive for content-suited surfaces only.
