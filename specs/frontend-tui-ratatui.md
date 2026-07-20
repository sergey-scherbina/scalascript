# `frontend/tui` — a ratatui terminal-UI backend for the Tk frontend SPI

Status: **COMPLETE (slices 0–5)** (2026-06-23, with Sergiy — "мы ведём всю компиляторную сторону сами. Оформляй
спеку, вноси в спринт и делай все что нужно"). SPRINT track `frontend-tui-*`. The `frontend/tui` ratatui backend
lowers the framework-agnostic `View` IR end-to-end: static layout + text (slice 1), a reactive signal store +
crossterm redraw loop (slice 2), a focus ring with keyboard events that mutate the store (slice 3),
`DataTable`/`TabBar`/`NavigationStack` (slice 4), and managed HTTP-fetch-bound signals (slice 5 + refresh
follow-up). `frontendTui/test` **36/36** — fast string-match cases + five `assume(cargo)` end-to-end smokes
that build and run the emitted crate (including local-`HttpServer` bootstrap, remote-table, and refresh tests).
Any backend-selection input
(`-Dscalascript.frontend=tui` / front-matter / inline) resolves it. The rozum side can now author its control
center as one `std/ui` Tk `.ssc` app and compile it to a terminal binary.

**User entrypoint (native-emit dispatch, 2026-06-23):** `emit(view, outDir)` from a `.ssc` with `frontend: tui`
now writes the ratatui crate (`Cargo.toml` + `src/main.rs`) to `outDir` and prints the build command — then
`cd outDir && cargo run`. The `emit` intrinsic (`FrontendIntrinsics.emitFrontendArtifact`) dispatches by the
backend's `supportedPlatforms`: web backends → html/js as before; native-only backends (tui) → `emitNative`'s
source tree. `serve(view, port)` with a native backend now fails with a clear message (a terminal app isn't
served over HTTP — use `emit`). This was the seam that blocked authoring a Tk `.ssc` for TUI.

**Style → ratatui (2026-06-23):** `Style` is mapped to a ratatui `Style` per leaf — `text.foreground`/
`decoration.background` `Color` (Rgb/Rgba/Hex/Named/System-token) → `.fg`/`.bg`; `fontWeight` → `BOLD`/`DIM`;
`Underline` → `UNDERLINED`. Styles thread through `Styled` (the `.foreground(…)` modifier DSL) and merge
parent→child. **Plus a focus highlight:** the focused widget renders with `Modifier::REVERSED` (so interactive
focus is visible beyond the `> ` marker), including tab headers. Caveat: `std/ui` widgets that carry color as a
CSS-string `style` attr lose it at `NativeElementLowering` (a shared native-backend limitation) — colors arrive
only via the typed `Style`/modifier DSL; mapping the CSS strings is a separate follow-up.

**`DataTable.Remote` live tables (2026-06-23):** a `Remote(FetchUrlSignal, rowsPath)` table now renders fetched
JSON — the body lands in `signals[id]` at bootstrap and is parsed each frame (`fetch_rows`/`json_field`,
serde_json) following the `rowsPath` envelope (dotted) or default `{data|rows|items|results}` keys, building a
ratatui `Table`. `serde_json` is added only when a remote table exists. This is what the rozum control-API binds
to. (`frontendTui/test` 34/34, incl. a local-`HttpServer` JSON smoke.)

**Managed fetch refresh (2026-07-20):** the emitter now preserves every `FetchUrlSignal.tickId`, snapshots
the tick after bootstrap, and re-fetches only changed bindings before the next frame. Failed refreshes retain
the last-good body; unchanged frames perform no GET. The generated-Cargo regression proves a refresh-button
activation replaces the remote table's JSON, then proves HTTP 500 cannot discard it. Contract:
[`frontend-tui-fetch-refresh.md`](frontend-tui-fetch-refresh.md). (`frontendTui/test` 36/36.)

Remaining follow-ups (lower priority, per-slice notes below): `A11y.focusOrder` seeding + hidden-branch focus
skip (need a render↔ring index map / runtime focus set), overlays (`Sheet`/`AlertDialog`), `DataTable.SignalRows`
+ typed-model views (`ModelView`/`ForModel`), CSS-string style decode in `NativeElementLowering` (shared with
swing/javafx), and a dedicated `ssc` CLI verb (today the entrypoint is the `emit(view, dir)` intrinsic +
`frontend: tui` front-matter). These are edge polish — the backend already covers the control-center use case
(interactive widgets, colors + focus highlight, live tables, tabs, routing, fetch).

Cross-repo: this is the **scalascript-side** half of the rozum **Unified Control Center (UCC)** initiative
(`rozum:docs/specs/unified-control-center.md`, master `386a892`). The operator's decision: **scalascript
owns the entire compiler side** — the full `frontend/tui` backend (the SPI impl *and* the `View → ratatui`
lowering table). rozum owns the control-center `.ssc` app + the control data-API.

## 1. Goal

A new render backend, **`frontend/tui`**, implementing `scalascript.frontend.FrontendFrameworkSpi`, that
lowers the framework-agnostic `View` IR onto **ratatui + crossterm** Rust idioms — so the *same* `std/ui`
(`Tk`) `.ssc` app that already compiles to web (React/SSR) and JVM desktop (Swing/JavaFX) also compiles to a
native **terminal UI** binary. "Compiled twice" = one Tk app run through two `FrontendFrameworkSpi` backends
(web + tui).

This is the **one real gap** the UCC recon found: the neutral UI vocabulary (`std/ui`), reactivity
(`ReactiveSignal`/`EventHandler`), the SPI, web backends, SSR, and even JVM-desktop/Apple backends already
exist and are tested. Only a terminal renderer is missing.

## 2. The three questions the rozum side asked — answered (grounded in code, 2026-06-23)

The UCC spec left three questions for the scalascript side. Answers, verified against the code:

### Q1 — How does `ssc build` select a frontend backend?
Via `scalascript.frontend.FrontendFrameworks` (`frontend/core/.../FrontendFrameworks.scala`) — a hybrid
**ServiceLoader + explicit-name registry** (same shape as `HttpServerBackends`). `all()` = programmatic
`register(impl)` then `ServiceLoader.load(classOf[FrontendFrameworkSpi])` (deduped by `name`); `current()` =
the selected `name`, else first-on-classpath wins; `setBackend(name)` selects (loud throw if absent). **Four
selection inputs, highest priority first:**
1. JVM system property `-Dscalascript.frontend=<name>` (alias `-Dssc.frontend`) — wired in
   `JvmGenPreamble.scala` (~L306).
2. CLI flag `--frontend <name>` — `tools/cli/.../EmitCommands.scala` (~L319) → `JvmGen.generate(...,
   frontendOverride)`.
3. Front-matter key `frontend:` (alias `frontend-framework:`) — `Parser.scala` (~L217) →
   `Module.frontendFramework`; the interpreter applies it (`Interpreter.scala` ~L971).
4. Inline `setFrontendFramework("name")` in `.ssc` — `FrontendIntrinsics.scala` (~L20).
5. Default: first impl on classpath (only `custom` is bundled today).

**Therefore selecting the TUI backend needs no new mechanism** — `frontend/tui` just registers `name = "tui"`
via `META-INF/services`, is added to an sbt module's classpath (opt-in `dependsOn`), and `"tui"` is added to
the CLI `--frontend` validation set. Then any of inputs 1–4 picks it.

### Q2 — Does focus/keyboard navigation live in Tk-core or the TUI backend?
**The focus *engine* lives in the TUI backend; the Tk core already carries the declarative *hints*.** The
core `View`/Tk model has **no** keyboard-event, tab-order, or focus machinery — only accessibility metadata:
`A11y.focusable: Option[Boolean]` + `A11y.focusOrder: Option[Int]` (`Primitives.scala` ~L229), exposed via
the `.focusOrder(n)` view modifier (~L568). All interactivity is delegated per-backend already (React emits
DOM handlers; Swing/JavaFX attach listeners and use the native toolkit's focus). So the terminal focus ring,
Tab/arrow traversal, and crossterm `KeyEvent` dispatch are **new logic emitted by the TUI backend** — there is
no shared core to extend, and this matches how every other backend owns its event loop. The backend **seeds**
its tab-order from `A11y.focusOrder` when present (so the model *can* express order portably), but the engine
is backend-side. No core changes required for focus beyond what already exists.

### Q3 — Who leads the compiler side?
**scalascript leads it entirely** (operator decision). This spec + the `frontend/tui` module are the full
compiler-side deliverable. rozum consumes it (the control-center app + control-API).

## 3. Integration route — `emitNative` (the Swing/JavaFX native pattern)

The native backends (`swing`, `javafx`) are the precedent, **not** the web `emit` path and **not** the Rust
codegen backend (`RustCodeWalk`). They:
- override **`emitNative(module, platform): Option[EmittedArtifact.NativeApp]`** (their `emit` throws);
- return `NativeApp(sources = Map(path -> source), buildScript, manifest, format, target)` — `sources` is a
  path→source-text map, `buildScript` e.g. `"scala-cli run ."`;
- call `NativeElementLowering.lower(entry.body(()))` to un-lower raw web `View.Element("div"/"input"/...)`
  into structural `View.Column/Row/Button/TextInput/...`, then recursively `emitBuilder` the target source;
- model reactivity with a runtime **signal store** (`mutable.Map[id, Any]`) + a **bindings** map
  (`Map[id, Buffer[() => Unit]]`) of re-render callbacks — exactly the imperative model a terminal needs (vs
  React's declarative reconciler).

**`frontend/tui` mirrors this, emitting Rust source instead of Scala source:**
- `TuiFrameworkBackend.emit` throws (`"tui is a terminal frontend; use emitNative"`); `emitNative` returns a
  `NativeApp` whose `sources` are `Cargo.toml` + `src/main.rs` (ratatui + crossterm), `buildScript =
  "cargo run"`, `format = AppFormat.RatatuiApp`, `target = Platform.Terminal`.
- A `TuiEmitter` calls `NativeElementLowering.lower(...)` then walks the structural `View` IR, emitting Rust
  that builds ratatui widgets — copying the Swing `emitBuilder` + signal-store + bindings shape, adapted to
  ratatui's pull-redraw loop.

This keeps the backend **self-contained** (no `RustCodeWalk` coupling): it hand-shapes a small, fixed
ratatui crate. The emitted crate is independently `cargo build`/`cargo run`-able, which is also how it's
tested (ratatui ships a headless `TestBackend` whose buffer we snapshot, and an `assume(cargo)`-gated smoke,
mirroring `RustGenCargoSmokeTest`).

### Minimal core additions
Two additive enum cases in `frontend/core/.../Primitives.scala`:
- `Platform.Terminal` (new `case` in `enum Platform`).
- `AppFormat.RatatuiApp` (new `case` in `enum AppFormat`).

Both are additive; no existing construction site changes (all default `Platform.Web` / `AppFormat.WebSpa`).

## 4. The `View → ratatui` lowering table

Consumes the structural `View` IR (post `NativeElementLowering`). Reactivity: a signal-dirty flag schedules
a redraw; each frame re-reads signals (ratatui is immediate-mode — the whole `View` re-renders per frame from
current state, so there is no incremental-DOM concern, only a redraw-coalescing one).

| `View` case | ratatui / crossterm lowering |
|---|---|
| `Column` / `Row` / `Stack` | `Layout::default().direction(Vertical/Horizontal).constraints([...])` |
| `Spacer(size)` | `Constraint::Min(0)` (grow) / `Constraint::Length(n)` |
| `Divider` | a `Block` bottom border / horizontal rule row |
| `Text` / `Heading` | `Paragraph::new(Span)` (heading = bold / underlined style) |
| `SignalText` / `ShowSignal` | `Paragraph` re-read from the signal store each frame |
| `Styled` + `Style` / `Theme` | `Style::default().fg(..).bg(..).add_modifier(BOLD/DIM)` on the `Span` |
| `Card` | `Block::default().borders(ALL).title(..)` wrapping the child area |
| `Button` / `SignalButton` | a focusable item; `Enter`/Space on focus → run its `EventHandler` |
| `TextInput` / `TextField` | a focusable input widget; track `(buffer, cursor)` in state; keys edit it → `InputChange` |
| `Toggle` | focusable `[x]`/`[ ]`; Space → `ToggleSignal` |
| `Table` / `DataTable` | ratatui `Table` (rows from the model/signal) |
| `For` / `ForModel` / `LazyList` | iterate the bound collection → child widgets/`List` rows |
| `TabBar` / `Router` / `Link` | a tab/screen-stack index in state; keys switch the active screen |
| `Badge` / `Pill` / `Tag` | styled `Span` (fg/bg) |
| `Spinner` | a tick-frame glyph cycled by the redraw timer |
| `Image` / `Icon` / raw CSS | not representable in a terminal → drop to a placeholder `Span` (text alt) / honor a `when(Platform.Terminal)` escape-hatch branch |

`EventHandler` execution mirrors the Swing descriptor→action mapping: `SetSignalLiteral`/`IncrementSignal`/
`ToggleSignal`/`InputChange`/`Simple`/`FetchAction` → mutate the signal store (+ schedule redraw) / call the
fetch runtime.

## 5. Slices (each independently green; gate = the emitted crate `cargo build`s and a `TestBackend` buffer
snapshot matches)

- **Slice 0 — scaffold + selection wiring. ✓ DONE (2026-06-23).** New sbt module `frontendTui`
  (`frontend/tui`) — `TuiFrameworkBackend extends FrontendFrameworkSpi` (`name="tui"`, `emit` throws,
  `emitNative` → minimal buildable crate via `TuiEmitter`), `META-INF/services/...FrontendFrameworkSpi`,
  registered in build.sbt `allFrontends` (auto root-aggregate + CLI classpath). Added `Platform.Terminal` +
  `AppFormat.RatatuiApp` to `frontend/core/.../Primitives.scala` (additive; all `Platform` matches have
  catch-alls, `AppFormat` is never matched — sibling backends recompile clean). **Gate met:** `frontendTui/test`
  8/8 — `FrontendFrameworks.setBackend("tui")` resolves, ServiceLoader discovers it, `emit` throws,
  `emitNative` emits `Cargo.toml`+`src/main.rs`, and `TuiCargoSmokeTest` (assume(cargo)) confirms the emitted
  crate `cargo run`s (ratatui 0.29, headless `TestBackend`, prints `ssc-tui: ok`). CLI `--frontend tui` native
  wiring deferred to a later slice (the browser `--frontend` set is web-only; selection already works via
  `-Dscalascript.frontend=tui` / front-matter / inline).
- **Slice 1 — static layout + text (read-only). ✓ DONE (2026-06-23).** `TuiEmitter` lowers the `View` IR (post
  `NativeElementLowering`) to a recursive `render_root`: `Column/Fragment/For/LazyList` → vertical `Layout`
  (measured `Constraint::Length`), `Row` → horizontal `Layout` (`Constraint::Ratio(1,n)`),
  `Stack`/`ScrollView`/`Styled` pass-through, `Text/SignalText/TextNode` → `Paragraph`, `Divider` → top-border
  `Block`, `Spacer` → reserved blank rows, `Show/ShowSignal` evaluated once (static). Interactive nodes
  (`Button`/`TextInput`/`Toggle`) render as static text (events → slice 3); `Style`/`Theme` mapping deferred.
  Headless `TestBackend` buffer-snapshot harness (`buffer_to_lines`). **Gate met:** `frontendTui/test` 18/18 —
  10 fast `TuiEmitterTest` cases + `TuiCargoSmokeTest` (assume(cargo)) rendering heading+text+divider+row whose
  buffer snapshot has the laid-out text with row children side-by-side. (UCC PoC step 1: read-only message list.)
- **Slice 2 — signals + redraw loop. ✓ DONE (2026-06-23).** Emitted crate now holds a runtime signal store
  (`HashMap<String, Value>` + a `Value` enum `S/I/B`) seeded from the View tree's `ReactiveSignal` initials;
  `render_root(frame, area, signals)` reads `SignalText`→`sig(...)`, `Toggle`→`toggle_text(...)`,
  `TextInput`→`text_input_display(...)`, and `ShowSignal`→a runtime `if sig_truthy(...)` branch (re-read each
  frame). `main` runs a real **crossterm event loop** (`enable_raw_mode` + alternate screen → draw → `event::poll`
  → quit on `q`/Esc → teardown), reached via ratatui's crossterm re-export (no extra dep). A headless
  `SSC_TUI_SNAPSHOT` env path renders once via `TestBackend` for CI. **Gate met:** `frontendTui/test` 20/20 —
  `TuiCargoSmokeTest` builds the loop crate, renders a signal-bound frame headlessly, AND runs `cargo test` on a
  generated `#[cfg(test)] reactive_rerender` that mutates a signal and asserts the frame changes.
- **Slice 3 — focus ring + keyboard + events. ✓ DONE (2026-06-23).** Emit assigns each focusable
  (`Button`/`TextInput`/`Toggle`) a document-order focus index; the crate carries `FOCUS_COUNT`, `is_text_input`,
  `focus_mark` (a `> ` indicator the focused widget renders), `activate`/`type_char`/`backspace` (generated
  per-index `match` arms), and `handle_key` (Tab/↓ + Shift-Tab/↑ traversal, Enter/Space → `activate`, typing →
  `type_char`, Backspace, Esc/`q` → quit). `render_root(frame, area, signals, focus)` threads focus; the
  crossterm loop holds `mut signals` + `mut focus` and `handle_key` mutates them. `EventHandler` execution =
  the declarative ones (`SetSignalLiteral`/`IncrementSignal`/`ToggleSignal`, plus `TextInput` editing via
  `InputChange`); `Simple`/`WithEvent` are Scala closures → no-op. **Gate met:** `frontendTui/test` 21/21 — the
  cargo smoke builds an interactive crate (signal + button + text-input) and `cargo test` runs generated
  `event_handlers_run` (button mutates the store), `text_input_typing`, `tab_moves_focus`, `reactive_rerender`.
  (UCC PoC step 2: composer.) Note: focus order = document order today; `A11y.focusOrder` seeding + hidden-branch
  focus skipping are follow-ups.
- **Slice 4 — table/list + routing/tabs. ✓ DONE (2026-06-23).** `DataTable(StaticRows)` → a ratatui `Table`
  (header from column titles, cells from each row's `fieldPath`; `Remote`/`SignalRows` → placeholder until
  slice 5). `TabBar` → a header row of focusable tab labels (active = `[label]`) whose activation
  `Set(current, idx)` switches the tab + a runtime `match sig_int(current)` rendering the active tab's content
  (reuses the slice-3 focus/activate machinery — Tab to a header, Enter to switch). `NavigationStack` → a
  runtime `match sig(current).as_str()` over the named routes. Added a `sig_int` accessor. `Badge/Spinner/
  Pill/Tag` chrome already lowers to text via `std/ui` (`lower.ssc` → `Element`/`Text`) and renders as such —
  no special case (color styling stays deferred). **Gate met:** `frontendTui/test` 25/25 — 3 fast emitter cases
  + a second cargo smoke building a `TabBar[DataTable, …]` whose snapshot shows the active `[Rooms]` tab + the
  table header (Room/Unread) + rows. (UCC PoC step 3: room switcher.) Follow-ups: hidden-tab focus skipping,
  `ForModel`/`EditableCell`, overlays (`Sheet`/`AlertDialog`).
- **Slice 5 — fetch data binding. ✓ DONE (2026-06-23).** `collectFetches` finds every `FetchUrlSignal`
  (it *is* a `ReactiveSignal[String]` carrying a URL) referenced by `SignalText`/`DataTable.Remote`/`ModelView`;
  the crate emits `fetch_text(url)` (blocking `ureq` GET) + a `bootstrap(signals)` that populates each fetch
  signal at startup, called before the first render in both the snapshot and interactive paths. A
  `SignalText` bound to a fetch signal then renders the fetched body (slice-2 store read, unchanged). `ureq` is
  added to `Cargo.toml` **only** when the app fetches (non-fetch crates stay lean; `bootstrap` is then empty).
  **Gate met:** `frontendTui/test` 28/28 — 2 fast emitter cases (fetch emits `bootstrap`+`ureq`; non-fetch app
  has empty `bootstrap`/no dep) + a 3rd cargo smoke that starts a local JDK `HttpServer`, builds a crate whose
  `SignalText` is bound to it, and asserts the snapshot contains the fetched body. This is the seam the rozum
  control-API binds to over HTTP. Follow-up: dynamic `DataTable.Remote` rows + typed-model views
  (`ModelView`/`ForModel`) from fetched JSON (needs `serde_json` + runtime row iteration).

- **Slice 5 follow-up — refresh-tick parity. ✓ DONE (2026-07-20).** Fetch metadata retains `tickId`; generated
  Rust captures post-bootstrap observations and runs a change-sensitive `refresh_fetches` before each frame.
  Successful responses replace the destination signal, failures retain last-good content, and unchanged ticks
  issue no GET. **Gate met:** `frontendTui/test` 36/36, including a generated-Cargo/local-HTTP table test with
  bootstrap → successful button refresh → unchanged no-op → HTTP-500 last-good retention.

After Slice 5, the rozum side reaches PoC parity with the 1389-line hand-written `crates/rozum-meeting/src/tui`
and can retire it.

## 6. Module layout (mirrors `frontend/swing`)

```
frontend/tui/
  src/main/scala/scalascript/frontend/tui/
    TuiFrameworkBackend.scala     # SPI impl: name="tui", emit throws, emitNative → NativeApp
    TuiEmitter.scala              # View IR → Rust(ratatui) source (the lowering table)
    TuiRuntimeTemplates.scala     # fixed Rust snippets: signal store, event loop, focus ring, Value
  src/main/resources/META-INF/services/scalascript.frontend.FrontendFrameworkSpi
  src/test/scala/scalascript/frontend/tui/
    TuiEmitterTest.scala          # per-node emit (string-match, fast)
    TuiCargoSmokeTest.scala       # assume(cargo): emit → cargo build → TestBackend snapshot
```
`build.sbt`: `lazy val frontendTui = project.in(file("frontend/tui")).dependsOn(frontendCore)`; add to the
root aggregate; opt-in `dependsOn` on the CLI/host that wants TUI output (like the other frontend backends).

## 7. Risks / open questions

- **Signals → terminal redraw:** Tk reactivity is push (`ReactiveSignal`); ratatui is a pull redraw loop. Map
  signal-dirty → schedule one coalesced redraw per event-loop tick; avoid per-signal full rebuilds. (UCC risk.)
- **Two output languages:** web emits JS (React), TUI emits Rust (ratatui). Fine for "compiled twice", but the
  rozum control-API must be reachable from both — HTTP for JS, in-process/socket for Rust (rozum's side; Slice 5
  provides the fetch seam).
- **Fidelity / escape hatches:** terminals can't do images/arbitrary CSS. Default to the common subset; allow
  rare `when(Platform.Terminal)` branches (operator approved sparing use).
- **CI cost:** the cargo-gated smoke needs `cargo` + the ratatui crate (network for the first fetch); gate on
  `assume(cargoAvailable)` so toolchain-less CI skips, exactly like `RustGenCargoSmokeTest`.
- **`NativeElementLowering` coverage:** confirm it un-lowers every web `View.Element` shape the `std/ui`
  `lower.ssc` emits that the TUI cares about; extend it (shared with swing/javafx) if a gap shows up.
