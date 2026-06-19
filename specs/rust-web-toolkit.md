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

   **S3 progress (2026-06-19) — drove the gap cascade down on the `vstack/heading/lower`
   probe (`backendRust` green at each step, each pushed):**
   - ✅ **S3a import inliner** — `[name](path.ssc)` imports now resolve+transpile for Rust
     (`compileViaBackend`, recursive, cycle-safe). `cannot find function …` gone.
   - ✅ **S3b block expressions** — `Term.Block` in value/arg/match-arm position → Rust block.
     Cleared 28 errors at once.
   - ✅ **S3c partial functions** — `{ case p => … }` → `move |__pf| match __pf { … }`.
   - ✅ **S3d tuple + typed patterns** — `(k, v)` and `case h: T` in `renderPattern`.
   - ✅ **S3e placeholder `_`-lambdas** — counter-stack desugar (mirrors JsGen), `_.foo` →
     `move |__p0| { … }`. (Cleared 8.)
   - ✅ **S3f vararg param type** — `T*` → `Vec<T>` in mapType. (Cleared 3.)
   - ✅ **S3g `List ++` / try-`toInt` / `null`** — `a ++ b` → `[a, b].concat()`;
     `try e.toInt catch _ => fb` → `e.parse().unwrap_or(fb)`; `null` → `Value::Unit`.
   - ✅ **S3h struct field types** — `renderStruct` maps field types vs all user type names
     (Theme.colors → `ThemeColors`, not i64). (Cargo `i64 has no fields` 170 → 12.)
   - ✅ **S3i String match** — `match s.as_str() { "x" => … }`. (Cargo mismatches 110 → 69.)
   - ✅ **S3j opaque types** — `View` → `crate::runtime::ui::View`, `Signal`/`EventHandler`/`Any`
     → `crate::value::Value`. (Cargo mismatches 69 → 28.)
   - ✅ **S3k signal SSR stubs** — Rust runtimes for the signal extern defs (static SSR) +
     `.toList` on Vec → clone. (Cleared all ~22 signal `cannot find function`.)

   **STATUS: the whole std/ui library now CODEGEN-transpiles (zero "unsupported …"
   diagnostics).** The `cargo build` of the emitted crate is down to **~56 errors (from ~290)**
   — a type-reconciliation TAIL in `lower.ssc`'s intricate code: `TkNode`/`i64`, `String`/`Value`,
   `String`/`bool` mismatches (some from `Any→Value` interacting with concrete-typed args),
   ~12 residual struct-field `i64`, the curried-vararg **call-site** `vec![…]` wrapping (S3f did
   the param type only), `defaultTheme` (a top-level `val` not emitted), a HashMap-`.map` shape.
   Converging but finicky/multi-session. Then **S4** named/curried args, **S5** signal reactivity
   (the stubs render static-only). Cascade summary: codegen 28→11→6→3→0; cargo 290→170→108→70→56→31.

   **S3l–S3n (pushed)** — enum-variant field types (TabBarNode `tabs: List[Tab]`→`Vec<Tab>`;
   cleared 12 i64 + mismatches 39→25); **recursive-enum `Box`** (S3m — `ShowWhenNode.whenTrue:
   TkNode` → `Box<TkNode>` at def + `Box::new` at construction + `*field` deref-rebind at match,
   `EnumCtor.boxedFields`; cleared E0072 "infinite size" + E0391 cycle); **vararg call-site** (S3n
   — `_varargDefs` map; `vstack(12)(a,b)` → `vstack(12, vec![a,b])`; cleared the arg-count error).

   **STATUS: codegen 100% (whole std/ui transpiles); cargo `build` 290 → 3.** Cleared (S3o–S3s):
   two STRUCTURAL walls (recursive `Box`, varargs call-site); the **`Any`-value coercion** core
   (`impl Display for Value` + `_ui_attr<T: Display>` + a dedicated `element(…)` case stringifying
   attr values; events stay `Value`); **top-level `val`s** (inline initializer except `given`
   instances; `collectTopVals` recurses into Normalize's namespace `object`s for `defaultTheme`);
   **named-arg construction**; `.toList`→`clone().into_iter().collect()`, `.mkString`→`.join`;
   **closure-aware clone** of captured non-Copy values (E0507 — `Ctx.closureParams`); **String-val
   tracking** so `a + b` over strings → `format!` (`collectLocalStrings`); `&str` catch-all binders
   rebound to `String`; **reserved-keyword escaping** (`box`/`use` → `r#box`/`r#use`).
   Cascade: codegen 28→0; cargo 290→170→108→70→56→31→29→7→3.

   **S3t (2026-06-19) — the "3" were MASKING the real wall.** Path 2 (statically type the library)
   closed the `Any`-downcast pair: `CardNode(header: Any, footer: Any)` → `List[TkNode]` (0-or-1; a
   `Vec` keeps the recursion behind the heap so the enum stays finitely sized — `Option[TkNode]` would
   re-introduce E0072 since `Option<T>` stores `T` inline), constructors `null`→`List()`/`List(x)`,
   `lower`'s arm via `.map`. The catch-all `case alreadyLowered => alreadyLowered` is the JVM
   idempotency passthrough; on the statically-typed Rust enum the variant arms are exhaustive so it
   can't fire — **dropped in codegen** (`renderMatch`: trailing identity catch-all after a `Pat.Extract`
   arm is removed; rustc then checks exhaustiveness). Regression test in `RustGenWebToolkitTest`.

   But `E0308` (the catch-all's incompatible-arm error) is a HARD type error that **poisons rustc's
   inference and halts checking of the rest of `lower`** — so it was hiding ~30 downstream errors.
   Fixing it unmasked the TRUE wall: **OWNERSHIP**. `lower(n: TkNode, theme: Theme)` takes `theme`
   by value and reads it in nearly every arm (field projections `theme.colors.muted` move the String
   out; recursive `lower(child, theme)` moves the whole struct; `children.map(lower(_, theme))`
   moves it into an `FnMut`). On the JVM everything is a shared reference, so this is free; in Rust
   each first use *moves*. Breakdown of the 30: ~20 `theme` (9 E0507 move-out-of-FnMut, ~11 E0382
   reuse), ~10 non-Copy field/local reuse (`tb.href/key/label`, `value`, `to`, `title`, `label`,
   `checked`). **Fix = clone-insertion** (fits the existing clone-everywhere model; no borrow concept):
   - S3t.1 (done): placeholder `_`-lambda renders its body as a closure body (sets `closureParams`
     marker) so captured non-Copy (`theme`) clones instead of moving out of the `FnMut`; `__pN`
     placeholder params excluded from the arg-clone rule.
   - S3t.2 (done): a per-def **use-count** `multiUse` (names read >1×, minus a `copyNames` set of
     params/locals with a Copy declared type / numeric rhs so single-use & Copy stay clone-free →
     goldens hold). `cloneIfMoved(arg, rendered, ctx)` clones a bare-name OR pure field-projection
     (clone the *leaf*: borrows the root, moves nothing) when its root is a topVal / multiUse /
     closure-capture. Applied at call args, `element(…)` attr-map values (`_ui_attr(v)`), and val/var
     rhs (`let t = theme.typography.body.clone()` — else the field partial-moves `theme`). Borrow
     intrinsics (`writeFile`/…) skip cloning (they take `&arg`).
   - S3t.3 (done): immediately-collected `.map`/`.filter`/`.foreach`/`.fold` closures + placeholder
     `_`-lambdas drop `move` (borrow captures), so sibling/nested closures (DataTable `rows.map{…
     cells.map{…}}`) and a later read of `theme` don't fight over the move. Brace-block lambdas
     (`xs.map { x => … }`, a `Block`-wrapped `Function`) are unwrapped to the same borrow path.
     Event handlers etc. keep `move` (they may escape) — but the toolkit's events are `_ui_*(…)`
     calls (Values), not stored closures, so nothing regressed.

   **STATUS (2026-06-19): cargo `build` 290 → 0 — the whole std/ui toolkit COMPILES on Rust.** The
   `@main` probe `serve(lower(vstack(12)(heading(1,"Hi"), text("hello")), defaultTheme), 8124)` builds
   to a binary and SSRs `<div style="…gap:12px…"><h1 …>Hi</h1><p …>hello</p></div>` over hyper/tokio
   (verified by `curl`). backendRust 218 green; full suite green for everything touched. Only cosmetic
   `non_snake_case` warnings remain. **The Rust-SSR web goal is reached via the declarative toolkit.**
   Next: S4 named/curried args (`vstack(gap=12)(…)`), S5 `Signal` reactivity (SSR initial + client JS).
4. **S4 (G3)** — named args in curried application (`vstack(gap=12)(…)`).
5. **S5 (G5)** — `Signal` reactivity: SSR initial value + emit the client JS bundle.

Prereq landed: **I1** `s"…${expr}…"` compound splices (`RustGenWebToolkitTest` 3/3).

Acceptance (end state): the `@main` toolkit probe — and ultimately `rozum-web.ssc` —
compiles with `ssc build-rust` to a binary that SSRs the same HTML the JVM run emits.

## Method

Drive from the target, NOT from CLI probes against the stale `bin/ssc`. Authoritative
signal = the `RustGen*Test` codegen unit tests (`new RustBackend().compile(Normalize(
Parser.parse(src)), opts)`) + a `cargo build` of the emitted crate for end-to-end gaps.
