# `frontend/tui` — a ratatui terminal-UI backend for the Tk frontend SPI

Status: **IN PROGRESS** (2026-06-23, with Sergiy — "мы ведём всю компиляторную сторону сами. Оформляй
спеку, вноси в спринт и делай все что нужно"). SPRINT track `frontend-tui-*`. **Slice 0 (scaffold) DONE
2026-06-23** — `frontend/tui` module + `TuiFrameworkBackend` register & emit a cargo-buildable ratatui crate
(8/8 incl. cargo smoke). Slices 1–5 (the `View → ratatui` lowering table) remain.

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
- **Slice 1 — static layout + text (read-only).** `Column/Row/Spacer/Divider` → `Layout`; `Text/Heading/
  SignalText` (static read) → `Paragraph`; `Styled/Style/Theme` → styled `Span`; `Card` → bordered `Block`.
  Draw-once. **Gate:** a `vstack(heading, text)` program → cargo build + a `TestBackend` buffer snapshot of
  the rendered text. (UCC PoC step 1: read-only message list `vstack(messages.map(msgRow))`.)
- **Slice 2 — signals + redraw loop.** Rust signal store (`HashMap<String, Value>`) + dirty-flag → redraw;
  crossterm event loop (draw → poll(timeout) → on tick/dirty redraw). `SignalText/ShowSignal/For/ForSignal`
  re-read each frame. **Gate:** a tick-updated signal re-renders across two `TestBackend` frames.
- **Slice 3 — focus ring + keyboard + events.** Focus ring over focusable nodes (`Button`,`TextInput`,
  `Toggle`), Tab/Shift-Tab/arrow traversal (seeded by `A11y.focusOrder`), crossterm `KeyEvent` → `EventHandler`
  (`SetSignalLiteral/Increment/Toggle/InputChange/Simple`). `TextField` editing (buffer+cursor); `Button`
  Enter→handler. **Gate:** a `textField`+`actionButton` program; feed key events to `TestBackend`; assert
  signal mutation + re-render. (UCC PoC step 2: composer.)
- **Slice 4 — table/list + routing/tabs + chrome.** `Table/DataTable` → ratatui `Table`; `TabBar/Router/Link`
  → screen-stack/tab index; `Badge/Spinner/Pill/Tag`. **Gate:** table render + tab switch snapshots. (UCC PoC
  step 3: room switcher / unread badges.)
- **Slice 5 — fetch-json data binding.** `fetchUrlSignal/fetchJsonSignal` → an async HTTP fetch in the Rust
  runtime (ureq/reqwest) feeding a signal — the seam the rozum control-API binds to over HTTP. **Gate:** a
  fetch-backed list renders from a mock endpoint. (UCC PoC step 5 readiness.)

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
