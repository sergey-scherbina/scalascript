# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

### ▶ Prioritized build queue (2026-06-18, with Sergiy — "внеси всё и делай автономно")

The genuine remaining **autonomously-actionable** build work, in priority order. Drive top-to-bottom,
one theme at a time, per-feature worktrees + claims. Everything below the queue is either history (`[x]`)
or blocked/deferred (kept for record, NOT actionable now — see "Excluded from the sprint").

> **Status 2026-06-18 (autonomous pass):** queue worked top-to-bottom. #1 meta-v2-track-c —
> verified already complete (no build). #2 sbt-plugin dep-resolution — ✓ built + tested (residuals
> design-/Maven-gated). #3 wasm-effects — **effectively COMPLETE**: arithmetic (2a) + `_dispatch`
> collection-HOFs (2b) + multi-shot (2c) + cross-module (2d) all built + run-verified on node (36 tests);
> `@main` args/non-Unit edge later closed by `wasm-main-edge` (40 tests). #4 build-registry-phase4 — assessed, no concrete target → no
> action. Then `sscBackends` cross-build ✓ DONE (user picked spec open-Q #2 → parallel outputs in one
> `compile`; scripted `cross-build/`). **What remains is Maven-gated only:** Maven Central + Plugin Portal
> publication (LAST, explicit-go). No bounded autonomous build work left.

### ▶ Quality / perf queue (2026-06-20, with Sergiy — "все эти задачи занеси в спринт и начинай делать")

After the perf series (foldLeft VM compile + typeclass-fold memo) micro-throughput is at the floor. The
next autonomously-actionable work is quality + unmeasured-axis perf, priority order. Drive top-to-bottom,
per-feature worktrees + claims.

> **Status 2026-06-20 (queue worked top-to-bottom — ALL DONE):** #1 real-workload-perf ✓ all three axes:
> (a) cold-start AppCDS −51% + harness, (b)+(c) steady-state server RSS+GC harness (~195 MB STABLE, no leak).
> #2 xbackend full+CI ✓ generator already broad (12 kinds) + wired into CI. #3 xbackend-test-hardening ✓
> `runCaptured` hang-proof runner. #4 rust-web-toolkit ✓ verified essentially complete + shipped the one
> bounded deferred slice (set/toggle client wiring); rest is browser/rozum-driven. **Queue fully resolved.**
> Follow-ups also DONE 2026-06-20 (per "сделай всё кроме maven"): **xbackend hang-proof sweep** — converted
> all 17 deadlock-risk (both-streams) subprocess-test files to `ProcTestUtil.runOrThrow`/`runCaptured` (the
> 22 single-stream `redirectErrorStream` files are deadlock-safe + behaviour-subtle → left as-is, standard
> set for new tests); 54 converted tests run green. **Server leak-hunt** — 4-min sustained-load run:
> definitively no leak (RSS peaked 205 MB, *ended 80 MB* as the JVM reclaimed heap; GC light/steady). **Only
> Maven publication (gated, excluded) + rozum/browser-driven rust refinements remain.**

### ▶ Rust-web computed-signal queue (2026-06-20, with Sergiy — "делай всё, заноси в спринт и делай")

The rust-web S5 refinements turned out to be autonomously buildable + curl/cargo-verifiable (set/toggle,
SSE, computed-read compile+SSR all DONE). Remaining, priority order:

- [x] **computed-live-recompute** ✓ DONE 2026-06-20 — computed signals are now fully reactive. Moved the
      signal store to `value.rs` (so `signal_value` can read it) + a computed-closure registry +
      `ssc_register_computed`/`ssc_recompute_all`; `_ui_computed_signal` is a re-runnable `Fn` returning a
      NAMED signal; `/__ssc/push` recomputes before broadcasting (SSE). **Verified cargo+curl:** push a dep →
      the computed signal auto-updates (`{"__c0":"fr"}` → `{"__c0":"de"}`). `backendRust` 224/0.
- [x] **computed-typed-reads** ✓ DONE 2026-06-20 — `collectLocalSignals` carries the element type; the apply
      emits `.parse::<i64>()`/`.parse::<f64>()` for `Signal[Int]`/`[Double]`, `.show()` for String. Verified:
      `signal("n", 10)` + `n() + 5` → `15`. `backendRust` 225/0. **Whole rust-web S5 done except direct-WS
      (now low-value — SSE supersedes).**

### ▶ Improvement queue (2026-06-20, with Sergiy — "занеси все в спринт и делай")

Fresh do-soon queue after rust-web S5 closed. Work top-to-bottom, one claim/worktree per slice. Maven Central
publication remains explicit-go only; the registry work below is intentionally domain-independent first.

- [x] **wasm-main-edge** ✓ DONE 2026-06-20 — closed the last WASM effects tail. Effectful WASM now derives
      the user `@main` from the AST, preserves a single Scala 3 `@main` parameter clause (including
      `String*` splicing), discards non-`Unit` returns in the synthetic wrapper, and rejects raw
      `Array[String]` `@main` args with a clear "use `String*`" diagnostic. **Verified:**
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/wasm-main-edge && sbt "backendWasm/testOnly scalascript.codegen.WasmBackendTest"`
      → 40/40 green. Gotcha recorded in `specs/wasm-main-edge.md`: Scala.js ES-module launcher argument
      delivery is out of scope; a direct Node probe supplies empty `String*` args.
- [ ] **stable-plugin-spi-p3** — finish the remaining Phase 3 cleanup around stable plugin/SPI boundaries.
      **How:** read `specs/arch-stable-spi.md`; inventory direct `scalascript.interpreter.*` imports in
      `runtime/std/*/*Intrinsics.scala`; migrate one low-risk plugin or add a boundary/classpath regression
      that locks the intended rule. Keep this as small shippable slices, not a broad refactor. **Verify:**
      affected plugin tests plus the smallest relevant backend/interpreter suite.
- [ ] **js-char-wrapper-string-map** — fix the open JS cross-backend character semantics bug:
      `"abc".map(_.toInt)` must return `Seq[Int]` while char-to-char mapping may still optimize back to a
      `String`. **How:** use the `bugs` skill before starting; update `BUGS.md`; reproduce through the real
      cross-backend harness; add/adjust `CrossBackendPropertyTest` coverage; implement a JS `Char` wrapper or
      equivalent representation that preserves `Char` methods without coercing all mapped results to strings.
      **Verify:** targeted cross-backend property test and a minimal JS/backend run.
- [ ] **remote-registry-mvp** — create a remote registry MVP that does **not** require registering a domain.
      **How:** extend/reconcile `specs/arch-registry.md` and `specs/arch-build-registry.md` around a static
      registry index layout, validation, and publish flow that can be hosted at a GitHub Pages project URL
      first (for example `https://<owner>.github.io/<repo>/`) and later moved behind a custom domain without
      changing package metadata. Avoid Maven in this slice. **Verify:** local validation command/test over the
      registry seed plus a documented default/fallback registry URL strategy.
- [ ] **registry-domain-hosting-decision** — document the registry domain + hosting decision separately from
      implementation. **Working recommendation:** start on GitHub Pages/project URL without a domain; later
      attach `registry.scalascript.io` (or another chosen subdomain) via DNS/CNAME when the name is final.
      **Research refs:** GitHub Pages supports project sites at `*.github.io/<repo>` and custom domains/HTTPS;
      Cloudflare Pages supports Git-backed deploys and custom domains; Cloudflare Registrar can register
      domains at-cost if the base domain is not already owned. **Done when:** the spec records Phase A
      (no-domain static registry), Phase B (custom subdomain), DNS/HTTPS notes, and the rejected alternative
      "block registry MVP on domain purchase".

- [x] **real-workload-perf** (roadmap-next #1) ✓ DONE 2026-06-20 (all three axes). **(a) cold-start:**
      `tests/perf/coldstart/` + AppCDS in `bin/ssc`/`install.sh` → **378 → 182 ms (−51%)**, peak RSS −32%.
      **(b)+(c) steady-state RSS + GC:** `tests/perf/serverrss/` boots a real server under load → interp
      server **~195 MB RSS, STABLE** (no leak), light GC (~41 pauses/27 ms). Long minutes-scale leak-hunt
      left to demand (`secs=300+`). BACKLOG `real-workload-perf`.
- [x] **xbackend-property-equivalence (full + CI)** ✓ DONE 2026-06-20 — broaden was already complete (12
      kinds incl. effects/Option/Either/closures/nested; node leg 74 programs / 0 skipped) so the work was
      reconciling that + **wiring into CI**: added Node.js setup to the `sbt` job so the interp==JS
      differential now runs in CI (it was skipping). Made hang-safe first (next item). BACKLOG `xbackend-property-equivalence`.
- [x] **xbackend-test-hardening** ✓ DONE 2026-06-20 — root cause was NOT bloop per se: `runProc` read
      subprocess streams with blocking `mkString` BEFORE the bounded `awaitExit`, so a wedged child parked
      the read forever (and could pipe-buffer-deadlock). Fixed via `ProcTestUtil.runCaptured` (threaded
      stream drain + hard timeout that actually fires); `ProcTestUtilTest` proves a `sleep 60`@2s returns
      <15s + a stderr flood doesn't deadlock. `CrossBackendPropertyTest.runProc` delegates. (~9 other test
      files share the old antipattern but run fixed small programs — follow-up sweep, lower risk.)
- [x] **rust-web-toolkit finish** ✓ VERIFIED ESSENTIALLY COMPLETE 2026-06-20 (the "~56 cargo errors" was
      badly stale). Checked against the authoritative signal: **`backendRust` 221/0**, **`RustGenWebToolkitTest`
      17/17** green. Per `specs/rust-web-toolkit.md`: cargo `build` of the std/ui crate is **290 → 0** (whole
      toolkit compiles on Rust), **S4** named/curried args DONE, **S5a** (SSR initial value) + **S5b.1** (local
      client reactivity) + **S5b.2 A/B/C** (generic push / rozum bridge / computed-derived) all DELIVERED at
      poll-transport depth. **REMAINING = explicitly-deferred refinements**, NOT bounded build work: SSE/WS
      streaming transport, client recompute of computed signals, set/toggle/show client wiring, direct-WS
      client. All are **browser-dependent** (can't verify autonomously without a browser) and **rozum-driven**
      (spec method: "drive from the target … ultimately `rozum-web.ssc`"). Hand back to the rozum driver; do
      NOT push speculative client-JS refinements onto `feature/rust-web-toolkit` (rozum's active branch).


- [x] **meta-v2-track-c** ✓ DONE 2026-06-18 (verified, no build needed) — Track C is COMPLETE. C1
      (multi-clause inline) ✓ done 2026-06-18. C2's high-value slice ✓ already done + wired:
      `MacroCodegen.codegenWarnings(module)` is computed in `ssc check` (`Main.scala:5265`, merged into
      `CheckResult.errors:5267`) and warns up-front on interpreter-only macros that can't compile to JVM/JS —
      `MacroCodegenTest` 6/6 green. The remaining C2 ambition (run the Typer over *arbitrary* macro-expanded
      source, map type-errors to `.ssc` positions) is **DEFERRED by design** in the spec: needs a position
      map (re-parse loses positions) + risks false positives (Typer may not grok expanded macro-runtime
      constructs), niche audience — low ROI vs the codegen warning that covers the real failure mode. Building
      it now = busywork against the spec's own judgment. **→ Next pick: sbt-plugin-finish.**
- [~] **sbt-plugin-finish** (roadmap #4, Phase 5) — **dep-resolution ✓ DONE 2026-06-18**: the concrete
      actionable Phase 5 slice. `SscFrontMatter` lifts `.ssc` front-matter `dependencies:` `dep:` Maven
      coords into `sscManagedDependencies` → `libraryDependencies` (Java `%`, Scala-cross `%%`, local paths
      ignored); scripted `dep-resolution/` + full scripted suite green (9). Spec §3h/Phase 5 reconciled.
      **`sscBackends` cross-build ✓ DONE 2026-06-18** (user picked spec open-Q #2 → design A = parallel
      outputs in one `compile`): `sscBackends: Seq[String]` (default `Seq(sscBackend)`); `sscCompile` forks
      `ssc build --backend <b>` per backend — single = flat dir (backward-compat), multiple = per-backend
      subdirs. Scripted `cross-build/`; full suite green (10). RESIDUALS (NOT done): (a) LSP/BSP "polish" —
      `BspIntegration`/`sscBspSetup` already landed Phase 4, no concrete remaining deliverable; (b) Maven
      Central publish + Plugin Portal — Maven-gated (LAST). So the only buildable remainder here is
      Maven-gated.
- [x] **wasm-effects** ✓ COMPLETE 2026-06-20 — additive, wasm-only.
      **arithmetic ✓ DONE (slice 2a):** `_binOp` (+`_bigIntOp`/`_bigDecOp`) — `a + b`/`sum * 2` over effect-op
      results link + run (test → 40). **`_dispatch` ✓ DONE (slice 2b):** collection HOFs on `Any` —
      `xs.map(..).filter(..).head` in a handler links + runs (test → 6); copied the pure subset of `_dispatch`
      + `_seqX`/`_seq`/`_isFree`, reflection fallback → clear error. **multi-shot ✓ DONE (slice 2c):** did NOT
      need a `_handle` rewrite (probe disproved it) — just the pure `_anyFlatMap` helper + a `usesEffects` fix
      to recognise `multi effect Foo:`; NonDet `{1,2}×{10,20}` runs on node (test → 4). **cross-module ✓ DONE
      (slice 2d, no code change):** an imported `effect` already works — `generateUserOnly` resolves imports via
      `baseDir`; run test → `hello\nworld`. **`@main` args/non-Unit edge ✓ DONE (wasm-main-edge):** effectful
      `@main` wrappers preserve Scala 3 main parameter clauses, discard non-Unit returns, and reject invalid raw
      `Array[String]` args clearly. **Complete:** common + advanced cases all run; `WasmBackendTest` 40/40 green.
      BACKLOG `wasm-effects`.
- [x] **build-registry-phase4** ✓ ASSESSED → no action 2026-06-18 (demand-driven). Surveyed the ~24
      `*Registry` classes: they are domain-distinct (Preprocessor / Interpolator / Backend / Capability /
      Route / Command / GlueClasspath / GlueJsPreamble / …), each registering a different kind of thing —
      **not** a duplicated template. The closest pairs (`Glue*`, `Interpolator*`) are small and cohesive;
      consolidating them would be speculative refactoring, exactly what the spec's "only where they remove
      real duplication" guard rules out. No concrete duplication target → no build. Revisit only if one
      appears. (Phases 1–2 landed; Phase 3 moot/load-bearing.)

---

- [~] **rust-web-toolkit** (external driver: rozum) — bring the declarative std/ui toolkit
      (`vstack/heading/text` → `lower(theme)` → `View` → `serve(view, port)`), which works on JVM,
      up on the **Rust** backend via an HTML/SSR binding (operator path A; native GUI rejected as
      too costly). **DONE 2026-06-19:** I1 `s"…${expr}…"` splices + S1a HTML/SSR View primitives
      (`element/textNode/fragment` → `runtime/ui.rs`, gated) + S1b `renderHtml` SSR — `textNode`/
      `fragment` compile AND run end-to-end (`renderHtml(...)` → escaped HTML via `ssc run-rust`).
      `backendRust` 211/0. + S1c `element` (`->` → tuple; non-empty `Map(k->v)` → HashMap-insert;
      `_ui_element` key-sorted attrs) — `renderHtml(element("div",Map("class"->"root"),…))` →
      `<div class="root" …>…</div>` end-to-end, `backendRust` 212/0. + S2 `serve(view, port)` SSR
      overload (`_ui_serve` in `http.rs`, gated on uiUsage) — `curl :8099` → SSR'd HTML, proven
      end-to-end, `backendRust` 214/0. + S1d void elements (`<meta>` self-close) + **capstone
      `examples/ssr-page.ssc`**: full nested HTML page built from primitives → `ssc build-rust` →
      `curl :8123` returns the SSR'd page. **The Rust-SSR web goal is reachable today via primitives.**
      + **S3 (a–k) the std/ui library now CODEGEN-transpiles** (import inliner + block exprs +
      partial fns + patterns + placeholder `_`-lambdas + varargs type + `++`/try/null + struct
      field types + String-match `.as_str()` + opaque-type mapping + signal SSR stubs). Cascade:
      codegen 28→11→6→3→**0**; cargo 290→170→108→70→**56**. **REMAINING:** a finicky cargo
      type-reconciliation tail (~56: TkNode/i64 + String/Value + struct-field i64 + curried-vararg
      **call-site** `vec![]` wrapping + `defaultTheme` val) — converging, multi-session. Then S4
      named/curried args · S5 signal reactivity (stubs are static-only). Spec `specs/rust-web-toolkit.md`.

- [x] **agent-sdk-remainder** ✓ DONE 2026-06-17 (actionable scope) — consolidated `specs/agent-sdk.md`
      + **P3a MCP bridge both directions** (`runtime/std/agent-mcp.ssc`: `serveAgentToolsMcp` +
      `mcpToolSource`; examples `agent-mcp-{server,toolsource}.ssc`; all `ssc check` OK). Loop
      conformance already covered by `AgentSdkInterpreterTest`. DEFERRED (reasons in spec): bridge
      round-trip test (heavy jvm/js infra for thin glue), golden transcripts, P3b embedded (blocked
      on rozum `rozum-embed`). spec `specs/agent-sdk.md`. → **Next: package-registry.**

- [x] **package-registry** (roadmap #3) ✓ DONE 2026-06-17 — found ALREADY BUILT (spec was stale):
      `ssc search`/`info`/`add` over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh`) +
      seed `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. Added the minor
      `--offline` flag (cached-only search, `RegistryClient.loadOffline()`). REMAINING (external only):
      the `scalascript/registry` GitHub repo + Pages HTML + validate/publish CI.

- [x] **sbt-plugin-finish** ✓ ACTIONABLE SCOPE DONE 2026-06-18 — this duplicate open marker was stale.
      Front-matter `dependencies:` → Coursier and `sscBackends` cross-build are done + scripted-tested;
      LSP/BSP Phase 4 already landed with no concrete remaining deliverable. Publishing the plugin artifact
      itself is the deferred Maven Central / sbt Plugin Portal step and remains excluded from autonomous work.

- [~] **metaprogramming-v2** (roadmap #5) — AUDIT 2026-06-17: NOT a from-scratch build. All three
      phases have working bases (P3 Linker `inlineTable`/`expandInlineSource`; P4 `${impl('x)}` + direct
      `'{ $x+1 }` + interp parity + `MacroImpl` IR; P5 runtime `Mirror` + user `derived(m: Mirror)`).
      Remaining = the "Planned" extension bullets, broken into small slices in `specs/arch-metaprogramming-v2.md`
      §4b. PROGRESS: **Track A** (P5 cross-backend derives conformance) ✓ DONE (A1a/b/c + A2 + A3,
      2026-06-17; only deferred edge cases remain — sum-type/enum mirrors, generics, mixed-derives clauses).
      **Track B** (P4 const-folding `Expr.asValue match`): **B1 + B2 ✓ DONE 2026-06-18** (interp splice
      unwraps `Expr(v)`; `Linker.expandMacroSource` const-folds literal args to the `Some` branch, else the
      `None` direct quote; `LinkerRewriteTest` +7 / `InlineDerivesTest`; `examples/quoted-macro-constfold.ssc`).
      **B3 ✓ DONE 2026-06-18 — JVM + JS** (was blocked — quoted macros were interpreter-only): the
      `macro-codegen-backends` pass (`MacroCodegen.expand`, hooked into `JvmGen` + `JsGen` generate entry
      points) expands + strips macros pre-codegen, no-op for macro-free modules;
      `QuotedMacroJvmConformanceTest` (scala-cli) + `QuotedMacroJsConformanceTest` (node) match interp.
      **Track B is complete (B1+B2+B3).** **Track C:** C1 (multi-clause inline) ✓ DONE 2026-06-18
      (curry tail clauses into the body — no scanner/wire change); **C2** (post-expansion re-typecheck +
      source-positioned errors) is the last open meta-v2 slice. Days-per-slice. Spec Status corrected from
      stale "deferred/planning".

### Tier 2 — AUDIT 2026-06-17: most "themes" are already BUILT (specs stale)

While pulling these in I audited each against the code — and like agent-sdk + package-registry,
most are already implemented; the specs/BACKLOG were stale. So Tier 2 is mostly **reconcile +
verify residuals**, NOT from-scratch builds:

**RECONCILED 2026-06-18 (`tier2-spec-reconcile`)** — verified each theme against the code:
- [x] **theme-f-dsl-platform-hooks** — spec Status already accurate ("implemented through Phase 4",
      `InterpolatorRegistry`). No change needed.
- [x] **theme-h-library-modularity** — spec Status already accurate ("implemented through Phase 6",
      `SsclibManifest`). No change needed.
- [x] **theme-j-lightweight-ffi** — ✓ DONE: `@jvm`/`@js` (Phases 1–4) + `@rust` + **`@wasm`** all wired.
      The WASM backend exists (`runtime/backend/wasm`, Scala.js → `.wasm`); `WasmGen` lowers `@wasm("expr")`
      externs to a `def` (2026-06-18, `WasmBackendTest`). Only `@wasmExport`/`@wasmImport` (raw WASM ABI)
      stay out of scope **by design** (the Scala.js path owns the ABI). The "no WASM backend wiring" note
      was stale.
- [~] **theme-a-stable-plugin-spi** — spec Status accurate ("partially implemented"; Phases 1+2 landed).
      Residual = Phase 3 versioned stable API module. Left as-is (accurate).
- [x] **ssc-new-audit** ✓ DONE 2026-06-19 — verified and tightened the local `ssc new` /
      standalone-install surface without touching Maven/publication. Fixed `NewProject.create` to best-effort
      `git init -q`; fixed `ssc new` usage to list all bundled templates; made root `install.sh` match docs
      (`./install.sh` prints standalone Coursier/Homebrew/curl guidance, `./install.sh --dev` runs monorepo
      staging); clarified `specs/arch-ssc-new.md` (plugin template intentionally has no `project/plugins.sbt`;
      live channel publication remains deferred); updated the old benchmark note to use `install.sh --dev`.
      Added tests for all six templates, output-dir aliases, placeholder-free rendering, git-init, and release
      fixtures. Verify: `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/ssc-new-audit && sbt
      "cli/testOnly scalascript.cli.NewProjectTest scalascript.cli.StandaloneInstallFixturesTest"` → 8/8 green.
- [x] **board-ledger-hygiene** ✓ DONE 2026-06-19 — docs-only cleanup. Marked the duplicate
      `sbt-plugin-finish` open item as actionable-scope done/Maven-gated, and removed three stale
      `Status: open` lines inside fixed `BUGS.md` entries (`jvmgen-multishot-handle-result-any`,
      `jvmgen-handle-in-arg-position`, `js-self-handling-cps-fn-not-run`). Verify:
      `git grep -n "\*\*Status:\*\* open\|Status: open" -- BUGS.md` → no matches, and
      `git grep -n "^- \[ \] \*\*sbt-plugin-finish" -- SPRINT.md` → no matches.
- [x] **theme-b-build-registry-consolidation** — Phase 3 is **MOOT** (triaged 2026-06-18):
      `PluginManifest`/`LocalRegistry` are the **implementation** the facade is built on (not removable
      wrappers — `BackendRegistry` uses `PluginManifest`; `ImportResolver`/`PluginCommands` use
      `LocalRegistry`), and `isStdPluginInterpreterTest` is already gone. Nothing to remove. OPTIONAL
      Phase 4 (family registries) remains, demand-driven.
- [x] **module-graph-grouping** — ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`):
      197 modules; the per-impl module IS the SPI boundary; grouping either collapses it or is a no-op on
      the graph. No action.
- [ ] **std-nfc-packager-adapters** — BLOCKED autonomously: needs real iOS/Android/Web-NFC packager
      integration + device/browser harnesses. Native platform follow-up; can't verify without targets.
- [ ] **wallet-browser-ws-itest** — BLOCKED autonomously: real browser-WebSocket integration; full run
      needs a browser.

**Genuine remaining BUILD work** (across Tiers): `sbt-plugin-finish` Phase 5 (dep-resolution wiring +
Maven publish — publish is Maven-gated), `build-registry` Phase 3 cleanup + optional Phase 4 (Phases 1–2
landed), `metaprogramming-v2` — NOT large/from-scratch: all 3 phases have working bases; remaining =
the §4b slice tracks (A: P5 cross-backend conformance [first], B: P4 const-fold, C: P3 robustness),
days-per-slice. + the small residuals above. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.
