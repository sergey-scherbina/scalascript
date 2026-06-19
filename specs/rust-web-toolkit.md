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

1. **S1 (G2-core)** — `View` HTML runtime: `element`/`textNode`/`fragment` intrinsics +
   a `_ui_render(View) -> String` SSR fn (a Rust `runtime/ui.rs` template, gated on use
   like `http.rs`). A `@main` building `element("div", …, [textNode("hi")])` SSRs the
   expected HTML. **This PR.**
2. **S2 (G4)** — `serve(view, port)` overload: render the `View` then serve it via the
   existing `_http_serve` listener (static `GET /`). Arity-2 `serve` dispatch in the codewalk.
3. **S3 (G1)** — transpile imported `std/ui/*.ssc` widget library into the crate so
   `vstack`/`heading`/`text`/`lower` work on top of the S1 primitives.
4. **S4 (G3)** — named args in curried application (`vstack(gap=12)(…)`).
5. **S5 (G5)** — `Signal` reactivity: SSR initial value + emit the client JS bundle.

Prereq landed: **I1** `s"…${expr}…"` compound splices (`RustGenWebToolkitTest` 3/3).

Acceptance (end state): the `@main` toolkit probe — and ultimately `rozum-web.ssc` —
compiles with `ssc build-rust` to a binary that SSRs the same HTML the JVM run emits.

## Method

Drive from the target, NOT from CLI probes against the stale `bin/ssc`. Authoritative
signal = the `RustGen*Test` codegen unit tests (`new RustBackend().compile(Normalize(
Parser.parse(src)), opts)`) + a `cargo build` of the emitted crate for end-to-end gaps.
