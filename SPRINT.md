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

- [x] **meta-v2-track-c** ✓ DONE 2026-06-18 (verified, no build needed) — Track C is COMPLETE. C1
      (multi-clause inline) ✓ done 2026-06-18. C2's high-value slice ✓ already done + wired:
      `MacroCodegen.codegenWarnings(module)` is computed in `ssc check` (`Main.scala:5265`, merged into
      `CheckResult.errors:5267`) and warns up-front on interpreter-only macros that can't compile to JVM/JS —
      `MacroCodegenTest` 6/6 green. The remaining C2 ambition (run the Typer over *arbitrary* macro-expanded
      source, map type-errors to `.ssc` positions) is **DEFERRED by design** in the spec: needs a position
      map (re-parse loses positions) + risks false positives (Typer may not grok expanded macro-runtime
      constructs), niche audience — low ROI vs the codegen warning that covers the real failure mode. Building
      it now = busywork against the spec's own judgment. **→ Next pick: sbt-plugin-finish.**
- [ ] **sbt-plugin-finish** (roadmap #4, Phase 5) — `specs/arch-sbt-plugin.md` remaining surface:
      front-matter `dependencies:` → Coursier resolution, cross-build targets (`sscBackends` JVM/JS/WASM),
      LSP/BSP polish. Phases 1–4 landed. Publishing the plugin artifact itself = Maven-gated (NOT here).
- [ ] **wasm-effects** (follow-up slices to the 2026-06-18 first slice) — additive, wasm-only:
      (a) handlers needing `_dispatch`/`_binOp` (currently fail at link — add their pure-Scala subset to
      `WasmEffectRuntime` + routing); (b) multi-shot resume (`_anyFlatMap`); (c) cross-module effects
      (effect declared in an imported `.ssc`); (d) `@main` with args / non-`Unit` return. BACKLOG `wasm-effects`.
- [ ] **build-registry-phase4** (roadmap #7B, OPTIONAL / demand-driven) — family registries, **only where
      they remove real duplication**. Phases 1–2 landed; Phase 3 is moot (load-bearing, reconciled
      2026-06-18). Lowest priority; skip unless a concrete duplication target appears.

---

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

- [ ] **sbt-plugin-finish** (roadmap #4) — `specs/arch-sbt-plugin.md` remaining surface: front-matter
      `dependencies:` → Coursier, cross-build targets (`sscBackends` JVM/JS/WASM), LSP/BSP polish.
      (Phases 1–4 already landed.) Publishing the plugin artifact itself = part of the deferred Maven step.

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
- [~] **theme-e-ssc-new** — `ssc new`/install present in `Main` (verified `def name = "new"`). No dedicated
      spec file; nothing stale to reconcile. Effectively done; verify completeness if revisited.
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
