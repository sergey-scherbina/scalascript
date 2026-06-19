# Rust web toolkit — bring the declarative std/ui toolkit to the Rust backend

Branch: `feature/rust-web-toolkit`

## Goal

The high-level **declarative UI toolkit** — `vstack`/`hstack`/`heading`/`text`/
`textField`/`actionButton`/… composed into a typed `View` tree via `lower(theme(…))`
and served with `serve(view, port)`, plus `Signal`/`fetchUrlSignal`/`seedSignal`
reactivity — **already works on JVM** (interpreter + `backendJvm`; SSR). The toolkit
lives as a ScalaScript library in `runtime/std/ui/*.ssc` (`primitives`, `lower`,
`theme`, `layout`, `typography`, `input`, `display`, …), bottoming out in `extern def`
primitives (`element`, `textNode`, `fragment`, `View`, `Signal`, `serve`).

NOTE — this is NOT the `html"…"` string-interpolator / ```html fence (that's a lower
convention, explicitly "not the toolkit"). The target is the typed Widget/View layer.

Driving use case (external): author rozum's meeting web UI in `.ssc`→Rust (SSR the
chat page from a `View` tree) instead of the hand-written axum `src/meeting/web.rs`
+ `web_index.html`. ScalaScript registered in rozum's `REPOS.md` as `../scalascript`.

## Confirmed gaps (real `cargo` errors, fresh build 2026-06-19)

Probe: a minimal `@main` program importing `std/ui/{primitives,lower,theme,layout,
typography}` and building `lower(defaultTheme(vstack(gap=12)(heading(1,"…"),text("…"))))`
then `serve(view, 8080)`. The Rust **codegen front-end accepts the toolkit calls**
(no "unsupported expression"); the emitted crate fails `cargo build`:

1. **G1 — imported library `.ssc` modules are not compiled into the Rust crate.**
   `cannot find function heading/text/vstack/defaultTheme/lower`. The `[name](std/ui/x.ssc)`
   imports resolve on JVM/interpreter but the Rust backend does not transpile+link the
   referenced module bodies (and their transitive deps down to the `extern def` primitives).
   **This is the crux.** Needs the Rust backend's module-resolution to pull in imported
   `.ssc` library modules and emit their defs.
2. **G2 — `extern def` primitive intrinsics have no Rust runtime.** Once G1 links the
   library, the leaves (`element`, `textNode`, `fragment`, `View`, `Signal`, the
   `View`→HTML SSR renderer) need Rust runtime templates — mirror the JVM
   `runtime-server-jvm` / std-ui SSR shapes.
3. **G3 — named args in a curried application.** `vstack(gap = 12)(…)` → `cannot find
   value gap`. `DefaultParameters` is declared but this curried/named form isn't lowered.
4. **G4 — `serve(view, port)` overload.** Rust has only `_http_serve(port: i64)`;
   the SSR form `serve(view, port)` (render a static `View` to HTML + serve) is missing
   (`takes 1 argument but 2 supplied`).
5. **G5 — `Signal`/`fetchUrlSignal`/`seedSignal` reactivity** for SSR (initial render +
   client JS handoff). Defer to its own slice; SSR of the initial `View` comes first.

## Already landed (prerequisite)

- **I1 — `s"…${expr}…"` compound interpolation splices** (`RustCodeWalk.renderInterpArg`,
  `Term.Block` unwrap). Green: `RustGenWebToolkitTest` 3/3, full `backendRust` 207/0.
  Needed because toolkit widget bodies use `s"…"` (e.g. `href = s"mailto:${row.email}"`).

## Decision (operator, 2026-06-19): target **A = HTML/SSR**, simplest path.

Native GUI (B) rejected — too much work, no mature Rust mobile-GUI stack. A "mobile web
app via Rust" = Rust SSRs responsive HTML from the `View` tree + serves it; in-browser
interactivity ships as a JS bundle (the JS target, not Rust). Sequence bottom-up — prove
SSR at the primitive level first (no library needed), then layer the widget library:

## Increments (ship one at a time; green on `backendRust/test` + conformance)

1. **S1 (G2-core)** — `View` HTML runtime: `runtime/ui.rs` template (`enum View` +
   `_ui_render` SSR with text/attr escaping), gated on use like `http.rs`.
   - **S1a ✅ DONE** — `element`/`textNode`/`fragment` intrinsics + the template; gated.
     `RustGenWebToolkitTest`, full `backendRust` green.
   - **S1b ✅ DONE** — `renderHtml(view)` intrinsic (`_ui_render`, by-value). `textNode`
     + `fragment` compile **and run** end-to-end: `renderHtml(fragment(List(textNode("hi "),
     textNode("& <world>"))))` → `hi &amp; &lt;world&gt;` via `ssc run-rust`. Typing aligned
     itself (String literal → `"…".to_string()`; untyped `let` infers `View`).
   - **S1c ✅ DONE — `element` (the tag builder).** Two codegen fixes: (i) the `->` infix
     operator → Rust tuple `(a, b)`; (ii) non-empty `Map(k -> v, …)` → a HashMap-insert
     block (consistent with empty `Map()` → `HashMap::new()`). `_ui_element` takes
     `HashMap<String,String>` attrs, key-sorted for deterministic SSR. End-to-end:
     `renderHtml(element("div", Map("class"->"root","id"->"main"), Map(), List(textNode("hi & bye"))))`
     → `<div class="root" id="main">hi &amp; bye</div>` via `ssc run-rust`. `backendRust` 212/0.
2. **S2 (G4) ✅ DONE** — `serve(view, port)` overload. Arity-2 `serve` dispatch in the
   codewalk → `crate::runtime::http::_ui_serve`; `_ui_serve` renders the View once and
   serves it (text/html) for every request. Lives in a `UiServeRs` snippet appended to
   `http.rs` ONLY when uiUsage (it references `runtime::ui`), so pure `route`/`serve(port)`
   programs are unaffected. Proven end-to-end: `serve(element("div", Map("id"->"app"), Map(),
   List(textNode("hello from rust ssr"))), 8099)` compiles + runs; `curl :8099` →
   `<div id="app">hello from rust ssr</div>`. `backendRust` 214/0.
   - **S1d ✅ DONE — void elements.** `_ui_render` self-closes HTML void tags
     (`meta`/`br`/`img`/…): `<meta charset="utf-8">` with no closing tag.

   **Capstone (S1+S2): `examples/ssr-page.ssc`** — a full nested HTML page (html/head/
   meta/title/body/h1/div/p/b, attrs, Cyrillic, `&` escaping) built from the View
   primitives, compiled with `ssc build-rust`, served, and fetched: `curl :8123` →
   `<html lang="en">…<p class="msg"><b>claude</b>: …&amp; работает</p>…</html>`.
   A ScalaScript program → native Rust HTTP server that SSRs HTML. **The end goal is
   reachable today via the primitives** (verbose `element(...)` authoring).

3. **S3 (G1) — transpile the `std/ui` widget library (`vstack`/`heading`/`text`/`lower`).**
   LARGE + open-ended; entangled with S5. Requires: (a) a **recursive import inliner** for
   the Rust path — `compileViaBackend`/`RustGen` must resolve `[name](path.ssc)` imports
   (JVM/JS do this inside their gen via `ImportResolver`+baseDir; RustGen does not →
   `cannot find function text`); (b) transpiling the dependency graph
   `nodes.ssc` (sealed `TkNode` + ~20 case classes) → `theme.ssc` (Theme struct) →
   `layout/typography` (constructors) → `lower.ssc` (494-line monolithic `TkNode`→`View`
   match). `lower` is monolithic — it references the **signal** primitives (`signalText`,
   `showSignal`, `setSignal`, `inputChange`, `toggleSignal`, `eqSignal`, `dataTableView`),
   so it only compiles once those have Rust runtimes (≈ S5). No small slice exists.
   Recommended as a focused follow-up effort.
4. **S4 (G3)** — named args in curried application (`vstack(gap=12)(…)`).
5. **S5 (G5)** — `Signal` reactivity: SSR initial value + emit the client JS bundle.

Prereq landed: **I1** `s"…${expr}…"` compound splices (`RustGenWebToolkitTest` 3/3).

Acceptance (end state): the `@main` toolkit probe — and ultimately `rozum-web.ssc` —
compiles with `ssc build-rust` to a binary that SSRs the same HTML the JVM run emits.

## Method

Drive from the target, NOT from CLI probes against the stale `bin/ssc`. Authoritative
signal = the `RustGen*Test` codegen unit tests (`new RustBackend().compile(Normalize(
Parser.parse(src)), opts)`) + a `cargo build` of the emitted crate for end-to-end gaps.
