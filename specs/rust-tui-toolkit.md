# rust-tui-toolkit — std/ui → ratatui via the Rust codegen backend (live thunks)

Status: **COMPLETE** (2026-06-23, with Sergiy — "делай вариант [полный транспайл .ssc → Rust]"). All slices S1–S5 shipped + cargo-verified. SPRINT
track `rust-tui-*`. The terminal analog of [`rust-web-toolkit.md`](rust-web-toolkit.md). **S1 (seam + render) DONE
2026-06-23** — a `signal`/`computedSignal`/`signalText`/`serve` program transpiles via RustCodeWalk and the emitted
ratatui crate `cargo run`s rendering the computed value in the terminal (`RustGenTuiToolkitTest`, incl. cargo
smoke). Proves computedSignal renders via the full transpile path; the live event loop is S2.

## 1. Why (the decision)

The `frontend/tui` ratatui backend (`frontend/tui/TuiEmitter.scala`, spec
[`frontend-tui-ratatui.md`](frontend-tui-ratatui.md)) is a **static** emitter: it lowers a pre-built `View`
IR to hand-shaped ratatui Rust. It cannot run a `computedSignal(() => …)` — that's an arbitrary ScalaScript
thunk, and a static emitter can neither re-run a compiled closure nor see its AST. So derived signals (e.g.
`computedSignal(() => gib(jsonValue(fetch())…))`) render a frozen emit-time snapshot in the terminal while
working live on the web.

**The fix the operator chose:** route the terminal through the **Rust codegen backend (`RustCodeWalk`)** — the
same path the **rust-web-toolkit** already uses to transpile real `.ssc` UI logic (signals, `computedSignal`
thunks → real re-runnable Rust closures, the `View` tree, `lower`) to Rust — and render the resulting `View` to
**ratatui** instead of HTML/SSR. On this path `computedSignal` is *already* a live Rust closure
(`_ui_computed_signal<F: Fn() -> String + …>` registered in `ssc_computed`, re-run by `ssc_recompute_all()` on
every signal change — proven by `RustGenWebToolkitTest` "computed signal LIVE recompute"). So a terminal redraw
that re-reads the signal store after a recompute shows the new value: **computedSignal goes live in the terminal.**

## 2. What already exists (reused unchanged)

From rust-web-toolkit (all landed, cargo/curl-verified):
- **Import inliner (G1)** — `Main.compileViaBackend` → `inlineImportsRust` flattens the whole `std/ui` graph
  (`primitives/nodes/theme/layout/typography/input/display/lower/data/reactive`) into one module before
  `RustBackend.compile`. The toolkit transpiles with zero "unsupported" diagnostics today.
- **Language features `lower.ssc` needs** — block exprs, partial functions, tuple/typed patterns, `_`-lambdas,
  varargs `T*`→`Vec<T>`, `List ++`, opaque-type mapping (`mapType`, `RustCodeWalk.scala:1245`): `View`→
  `crate::runtime::ui::View`, `Signal`/`EventHandler`/`Any`→`crate::value::Value`.
- **Signal runtime** (`ValueRs`) — `ssc_signals(): Mutex<HashMap<String,String>>`, `ssc_computed(): Vec<(name,
  Box<dyn Fn()->String>)>`, `ssc_register_computed`, `ssc_recompute_all()`, `Value::signal_value()` (reads the
  live store). **Backend-target-agnostic — reused as-is.**
- **Signal/computed intrinsics** (`RustIntrinsics.scala:85`) — `signal`/`computedSignal`/`signalText`/
  `showSignal`/`setSignal`/`toggleSignal`/`inputChange`/`eqSignal`/`seedSignal` → `_ui_*`. Shared.
- **`computedSignal` read-lowering + clone-capture** (`RustCodeWalk` `collectLocalSignals`/`renderClosure`) —
  `loc()` on a signal local → `loc.signal_value().show()[.parse::<i64/f64>()]`; closures clone-capture signal
  locals. Shared.

## 3. The obstacle: the Rust `View` is HTML-collapsed

The Rust `View` enum (`UiRs`) has only three variants:
```rust
pub enum View { Element { tag: String, attrs: Vec<(String,String)>, children: Vec<View> }, Text(String), Fragment(Vec<View>) }
```
By the time the IR is a `View`, `lower.ssc` has collapsed everything to `element("div"/"h1"/"p"/"button"/…)`
with CSS baked into a `style` attr, and event handlers folded into `data-ssc-*` attrs by `_ui_element`. So a
`tui.rs` renderer must interpret the HTML-ish tree (the terminal analog of the JVM `NativeElementLowering`):

| `View` shape | ratatui |
|---|---|
| `Element{tag:"div", style has flex-direction:column}` | `Layout` vertical (gap from `gap:`) |
| `Element{tag:"div", flex-direction:row}` | `Layout` horizontal |
| `Element{tag:"h1".."h6"}` | bold `Paragraph` |
| `Element{tag:"p"|"span"}` / `Text(s)` | `Paragraph` |
| `Element{tag:"button", data-ssc-set/toggle/...}` | focusable item; Enter → run the `data-ssc-*` action |
| `Element{tag:"input", data-ssc-input:name}` | focusable input bound to the signal |
| `Element{tag:"hr"}` | a divider / horizontal rule |
| `Element{data-ssc-text:name}` (from `signalText`) | read the **current** `ssc_signals()[name]` each frame |
| `Element{tag:"table"/...}` (DataTable) | ratatui `Table` (needs the fetch family — §6) |

`data-ssc-text` is the key to live reactivity: a `signalText(sig)` lowers to an element carrying the signal
name, so the redraw reads the *current* store value — and after a `setSignal` → `ssc_recompute_all()`, that
value is the recomputed one.

## 4. The seam: `BackendOptions.extra("uiTarget" -> "tui")`

`RustGen.generate(opts: BackendOptions, …)` already reads `opts.extra.get("binName")`. Add a free-form
`uiTarget` and branch at the existing UI-gating sites:

- `RustGen.scala:54` `uiUsage` stays; when `uiTarget=="tui"`:
  - `:161` emit a **`TuiRs`** (`runtime/tui.rs` — the ratatui renderer + helpers) instead of `UiRs`;
  - `:128` emit a **`TuiRunRs`** (crossterm event loop) instead of appending `UiServeRs`;
  - `:362` Cargo deps = `ratatui`/`crossterm` instead of `hyper`/`tokio`/WS.
- `serve(view, port)` dispatch (`RustCodeWalk.scala:1852`) → `crate::runtime::tui::_tui_run(view)` on the tui
  target (the port is ignored — a terminal app isn't served). RustCodeWalk needs the `uiTarget` threaded (via
  `opts`/the intrinsic overlay).
- New CLI: `ssc run-tui <file.ssc>` / `build-tui` (mirror `run-rust`/`build-rust`), passing
  `extra("uiTarget"->"tui")`. (`frontend: tui` convergence — §7.)

The `Value` enum + signal store (`ValueRs`) and the computed/signal intrinsics are **unchanged** — only the
*render* + *run* differ from web.

## 5. Slices (each independently green; gate = the emitted crate `cargo build`s + a `TestBackend` snapshot or a
`cargo test`)

- **S1 — seam + minimal render (foundation). ✓ DONE (2026-06-23).** `uiTarget` flag threaded into `RustGen`; a minimal `TuiRs`
  (`_tui_render(View) → ratatui`: `Text`/`Fragment`/`Element` with the core tags → Paragraph/Layout; reads
  `data-ssc-text` from `ssc_signals()`); draw-once via `TestBackend`; `serve`→`_tui_run` (draw-once + print
  snapshot). **Gate:** a `.ssc` `serve(lower(vstack(heading,text,signalText(computedSignal(...))),theme),0)`
  transpiles via RustCodeWalk and `cargo run` (SSC_TUI_SNAPSHOT) prints the rendered buffer with the computed
  value. Proves the whole pipeline (transpile → ratatui) end to end.
- **S2 — live event loop. ✓ DONE (2026-06-23).** Crossterm event loop (raw mode + alt screen → draw → poll → quit on q/Esc), focus
  ring over `data-ssc-*` focusables, Enter/Space → run the focused element's action (`setSignal`/`toggle`) →
  `ssc_recompute_all()` → redraw. **Gate:** a counter (`signal` + `computedSignal(()=>show(count*2))` + a
  `+`/button); a `cargo test` feeds the activate key and asserts the computed text changed — **computedSignal
  LIVE in the terminal.**
- **S3 — full tag → ratatui mapping. ✓ DONE (2026-06-23, layout+colors).** CSS-string parse for flex direction/gap (`div`), all `std/ui` chrome
  (card/badge/divider/spacer/input/toggle), `showSignal` conditional, focus highlight + colors. **Gate:** the
  `rozum-meeting.ssc`-style toolkit program renders to a faithful terminal snapshot.
- **S4 — fetch family + DataTable/remoteTable.** Rust runtimes + intrinsic mappings for `fetchUrlSignal`/
  `fetchRowsSource`/`staticRowsSource`/`signalRowsSource`, a Rust `rowsOf` envelope-drill mirroring
  `__ssc_rowsOf`, and a real `_tui_data_table_view` (fetch → rows → ratatui `Table`). NOTE: these are absent on
  the Rust path **entirely** today (web SSR stubs `dataTableView` to an empty Fragment). **Gate:** `remoteTable(
  fetchUrlSignal(...), cols, rowsPath)` renders fetched rows in the terminal against a local HTTP server.
- **S5 — convergence. ✓ DONE (2026-06-23).** Point `frontend: tui` / `ssc run --frontend tui` at this path (it supersedes the static
  `frontend/tui` emitter for dynamic apps), or unify the two so one `.ssc` + `frontend: tui` gives the live TUI.
  Retire/keep the static emitter per the result.

## 6. Open questions / risks

- **Two TUI pipelines.** The static `frontend/tui` emitter (live tables via `fetch_rows`, colors, focus —
  already shipped) vs this RustCodeWalk path (live thunks, but DataTable/fetch absent until S4). They overlap;
  §S5 converges them. Until then this is selected by `run-tui`/`build-tui`, leaving `frontend: tui` on the
  static emitter so nothing regresses.
- **HTML-collapse fidelity.** Parsing CSS strings for layout (flex-direction/gap) in Rust is brittle; the JVM
  side reads structured `View.Column/Row`. Consider teaching `lower.ssc` (or a pre-pass) to keep structural
  intent for native targets — shared with the swing/javafx CSS-drop limitation.
- **Build weight.** Each gate is a cargo build (ratatui + crossterm); gate on `assume(cargo)` like
  `RustGenCargoSmokeTest`.
- **`RustCodeWalk` opts threading.** The `serve` dispatch (`:1852`) needs the `uiTarget`; confirm how to thread
  `BackendOptions` (or the intrinsic overlay) into `RustCodeWalk`.
