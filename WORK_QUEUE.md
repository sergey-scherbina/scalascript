# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Files in
`.work/active/` without the `.claim` suffix are invalid coordination markers:
run `scripts/coord-status`, report/fix the marker, and do not start overlapping
work until it is repaired or released.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## JS Codegen Performance (open)

- [x] **js-codegen-opt-p1** — ✓ Landed 2026-06-03 commit `957a66f0`.
  Four JS codegen fixes to eliminate hot-path overhead and repair tuple concat
  correctness. Spec: `docs/js-codegen-opt-p1.md`.

  Slices:
  - **A — TCO bypass for non-recursive functions**: added
    `anywhereContainsSelfCall(d.body, fname)` so only genuinely recursive
    functions get the `while(true)` trampoline.
  - **B — `genMatchAsStmts`**: emits tail/return-position `Term.Match` as an
    if-else chain with const bindings and explicit return/continue, avoiding
    the old IIFE shape and fixing wildcard/catch-all syntax.
  - **C — TCO multi-param temp vars**: replaced array destructuring with
    individual temporary constants in `genTcoBody` and `genMutualTcoBody`.
  - **D — Fixed `++` multi-arg infix bug**: `(1,2)++(3,4)` now preserves all
    RHS tuple elements in both direct and CPS infix paths.

  Verification: all 1233 conformance tests passed in the implementation commit.
  Measured result: most wins were code-shape/correctness rather than wall-clock
  because V8 already optimised through several old patterns; tuple-monoid now
  computes the correct 4-tuple and regresses until p2 hoists constant tuple
  construction. Full before/after table and post-mortem are in
  `docs/js-codegen-opt-p1.md`.

- [x] **js-codegen-opt-p2** — Loop-invariant constant tuple hoisting. ✓ Landed 2026-06-04.
  `(1,2)++(3,4)` in while-body hoisted as `const _k0 = Object.freeze(Object.assign([1,2,3,4],{_isTuple:true}))`.
  tuple-monoid: 4.24 ms → 0.025 ms (170×). 1236 conformance tests passed.
  See `docs/js-codegen-opt-p2.md`.

---

## busi-driven follow-ups (open)

These surfaced building the `busi` app (sibling repo) against an RBAC-gated,
bearer-token API. Resume via the standard claim/worktree flow.

- [x] **ui-fetch-auth-v1** — ✓ Landed 2026-06-01. `headers: Signal[String] = emptyHeaders`
  on `fetchAction`/`fetchActionClear`; `data-ssc-fetch-headers` attr + click-time
  `JSON.parse` in `_ssc_ui_mount`; all backends updated.
- [x] **ui-fetch-auth-v2** — ✓ Landed 2026-06-01. `fetchUrlSignal` now performs
  real GET on mount + tick; `_fetchGet` metadata on Signal drives `data-ssc-fetch-get-*`
  attrs; `fetchTableView` also takes `headers`. All emitters updated.
- [x] **v1.66.0-typed-models-ir** — ✓ Landed 2026-06-02. Shared IR foundation (all backends depend on
  this). Parser: recognize `@model case class` / `model case class` (mirrors
  `@remote` in `Parser.scala:437`); store `ModelDef`/`ModelFieldDecl`/`ModelFieldType`
  ADT on `Manifest.models`. Frontend IR: un-final `FetchUrlSignal`; add `CodecHint`
  sealed trait (`RawText`/`Json`/`FormUrlEncoded`/`Custom`); add `FetchJsonSignal`
  subtype; add `ModelView`/`ForModel`/`ModelText` to `View[+A]`. SPI: add
  `FrontendModule.models: List[ModelDef] = Nil` + `Capability.TypedModels`. New
  utilities: `ModelPathResolver`, `ModelCaseClassEmitter`, `JsonDecoder` trait.
  Intrinsics: `fetchJsonSignal`, isLoading/isLoaded/error properties. All ~25
  `FrontendModule(...)` construction sites unchanged (defaulted field). Tests:
  `ModelParseTest` + `ModelPathResolverTest` + backwards-compat smoke (all existing
  backend suites pass). **Spec:** [`docs/typed-models-ir.md`](docs/typed-models-ir.md)

- [x] **v1.66.1-swiftui-typed-models** — ✓ Landed 2026-06-02. SwiftUI emitter consumes the IR from
  v1.66.0: `emitModelStructs` → `struct X: Decodable [+ Identifiable]`; typed fetch
  via `JSONDecoder().decode`; `@State private var <id>: <T>? = nil` + companion vars;
  `ModelView` → `if let`; `ForModel` → `ForEach`; `ModelText` → `Text(path)`.
  Replaces the original `v1.66.1-swiftui-model-structs` + `v1.66.2-swiftui-model-view-nodes`.
  Fix 2026-06-03: `DeleteItem` inside `ForModel` now emits `"\(itemVar.idField)"` in
  `httpBody` instead of the TODO stub; `ForCtx.modelItemVar` threads the item var into
  nested emitAction. 2 new tests; suite 115 green.
  **Spec:** `docs/swiftui-typed-models.md §7`

- [x] **v1.66.2-react-typed-models** — ✓ Landed 2026-06-02. — React emitter: typed fetch (`r.json()` branch
  in mount-fetch hook, lines 60-66); companion state vars; `ModelView`/`ForModel`/
  `ModelText` as `signal && ...` / `.map(...)` / property access.
  **Spec:** `docs/typed-models-ir.md §React`

- [x] **v1.66.3-vue-typed-models** — ✓ Landed 2026-06-02. — Vue emitter: fix existing mount-fetch parity
  gap (Vue currently ignores `FetchUrlSignal.fetchUrl` on mount); then typed
  `r.json()` branch; `v-if`/`v-for`/`{{ bs.field }}` for view nodes.
  **Spec:** `docs/typed-models-ir.md §Vue`

- [x] **v1.66.4-solid-typed-models** — ✓ Landed 2026-06-02. Solid emitter:
  fix mount-fetch parity gap (`collectFetchSignals` + direct `fetch()` before DOM
  construction, `createEffect` for tick); `FetchJsonSignal` → `r.json()` + companion
  `_loading`/`_loaded`/`_error` signals; `ModelView` → `createEffect`-driven span with
  `bindingVar = signal()`; `ForModel` → for-loop over `bindingVar.fieldPath`; `ModelText` →
  `createTextNode(String(varName.fieldPath))`; `registerSignal` skips `FetchUrlSignal`.
  11 new tests in `SolidTypedModelsTest`; all 46 Solid tests pass.
  **Spec:** `docs/typed-models-ir.md §Solid`

- [x] **v1.66.5-custom-typed-models** — ✓ Landed 2026-06-02. Custom (StaticJs) emitter:
  `__ssc_signals` cells for fetch signal state (null/false/false/'' for JSON); `r.json()` decode
  via `__setSignal` + companion updates; tick subscriber + guard; `ModelView` → `__modelview_<id>`
  rebuild fn subscribed to signal, DOM-clear + re-render; `ForModel` → `__formodel_<n>` per-item fn
  + for-loop over `bindingVar.fieldPath`; `ModelText` → `createTextNode(String(varName.fieldPath))`;
  `registerSignal` skips `FetchUrlSignal`; tick auto-registered. 12 new tests; all 63 Custom tests pass.
  **Spec:** `docs/typed-models-ir.md §Custom`

- [x] **v1.66.6-electron-typed-models** — ✓ Landed 2026-06-02. Electron delegates renderer
  output to Custom (StaticJs) emitter — typed models inherited from v1.66.5 with zero emitter
  changes. Added `ElectronTypedModelsTest` (4 tests): FetchJsonSignal null-init round-trip,
  ModelView rebuild fn, ForModel+ModelText field access, bundle-builder `_ssc_frontend_name`
  injection. All 23 Electron tests pass.
  **Spec:** `docs/typed-models-ir.md §Electron`

- [x] **v1.66.7-swing-typed-models** — Swing: `modelData` + `withModel` on `RuntimeState`; `modelField` dot-path traversal; `ModelView`/`ForModel`/`ModelText` cases in `addTo`; async fetch via `JsonDecoder` SPI; `buildViewTest` for unit tests. 7 new tests, all 19 Swing tests green.

- [x] **v1.66.8-javafx-typed-models** — JavaFX: `modelData` + `withModel` on `RuntimeState`; `modelField` dot-path traversal; `ModelView`/`ForModel`/`ModelText` cases in `addTo`; async fetch via `JsonDecoder` SPI + `Platform.runLater`; `buildViewTest` for unit tests. 7 new tests, all 15 JavaFX tests green.

- [x] **v1.66.9-busi-dashboard** — `busi-dashboard.ssc` example with BalanceSheet/TrialBalance/AuditLog models, three-tab SwiftUI layout, `fetchJsonSignal` per tab, `ModelView`/`ForModel`/`ModelText`; `SwiftUIModelSmokeTest` with 11 assertions (model structs, @State decls, JSONDecoder, if-let bindings, ForEach, ModelText, swiftc-parse gate). All 101 SwiftUI tests green.

- [x] **interp-getOrElse-fn-default** — Bug: `m.getOrElse(k, en(k))` returns
  `en(k)` instead of the map value. Root cause: `TcoRuntime.tcoTrampoline`
  replaces mutual-tail-call targets (like `en`, found in tail pos at `case None
  => en(k)`) with `MutualTailCall`-throwing stubs. When `en(k)` also appears as
  a *non-tail* argument in `m.getOrElse(k, en(k))`, the stub escapes argument
  evaluation and the trampoline jumps to `en` — discarding the pending map
  lookup. ✓ Landed 2026-06-02: added `appearsAsCallInNonTailPos(curFun.body,
  name)` filter in `mutualEntries` construction — any tail-call target that
  ALSO appears as a `Term.Apply` callee in a non-tail position is skipped from
  the stub installation, so it resolves to the original FunV everywhere and
  argument evaluation works correctly. New regression test
  `map.getOrElse with fn-call default in match arm returns map value` (1205-test
  suite green in both FASTTIER on AND off modes). The earlier WIP attempt's
  `Undefined: loc` symptom was an unrelated artifact of untyped `def t(loc, k)`
  syntax in the original repro; with typed params (`def t(loc: String, k: String)`)
  the filter alone produces the correct result. Recursive cluster sanity:
  `recursionFib` 28.3 ms / `recursionTco` 993 µs / `recursiveEval` 31.4 ms — all
  within historical baseline (the filter runs only on `envStable` rebuild,
  i.e. once per `curFun` transition, not per iter).

## Codebase maintenance / architecture hygiene (open)

These are deliberately small slices. Before taking any frontend item, check
`.work/active/` and `git worktree list`; typed-model frontend work is currently
being landed backend by backend.

- [x] **codebase-maintenance-roadmap** - ✓ Landed 2026-06-02. Persisted the
  2026-06-02 architecture hygiene plan in specs, backlog, and queue; restored
  the missing `docs/typed-models-ir.md` file referenced by v1.66 queue items;
  extracted the self-contained `LspCmd` and `GenerateFacadeCmd` providers into
  `tools/cli/.../LspAndFacadeCommands.scala`, leaving ServiceLoader FQCNs
  unchanged. Verification: `git diff --check`; attempted standard
  `cd <worktree> && sbt "cli/compile"` after rebasing to current `origin/main`,
  but it stopped before CLI on unrelated frontend `-Werror` warnings in active
  typed-model parity work (`frontendSolid`, `frontendCustom`, and JavaFX
  stale `@nowarn`). Focused verification passed with temporary sbt-session
  overrides removing `-Werror` only from those frontend deps:
  `cli/compile` and `cli/testOnly scalascript.cli.CommandRegistryTest` (8 tests).

- [x] **coord-claim-protocol-hardening** — ✓ Landed 2026-06-03. Tightened
  claim instructions so every valid claim must be
  `.work/active/<slug>.claim`, documented recovery for suffix-less active
  markers, and taught `scripts/coord-status` to flag invalid markers instead of
  silently reporting "no claims". Verification: `git diff --check`;
  `scripts/coord-status --no-fetch` against a tree containing an invalid
  datatable marker.

- [x] **coord-status-clean-worktrees** — ✓ Landed 2026-06-03. Extended
  `scripts/coord-status` with a `clean landed worktrees` section that lists
  linked worktrees that are clean, unlocked, not ahead of `origin/main`, and
  whose `HEAD` is already contained in `origin/main`; each entry prints a
  cleanup command. Locked, dirty, ahead, prunable, and main-checkout worktrees
  are excluded. Verification: `bash -n scripts/coord-status`;
  `scripts/coord-status --no-fetch`; `git diff --check`.

- [x] **frontend-view-traversal-core** - ✓ Landed 2026-06-02. Added
  `ViewTraversal` in `frontend/core` (`children` + `foreachDepthFirst`) with
  adaptive-branch options and migrated React's fetch-signal collector to it.
  Regression coverage: core traversal tests plus React typed JSON fetch nested
  inside semantic `Column`. Remaining collectors/backends can migrate
  incrementally.

- [x] **typed-models-structural-types** — `ModelPathValidator` in `frontend/core`: walks View tree tracking binding context from `ModelView`/`ForModel`, calls `ModelPathResolver` for every in-scope `ModelText`/`ForModel` path, produces typed `PathError` list. Unbound vars silently skipped. `validateModule` for full-module checks. 14 new tests, all 41 frontendCore tests green.

- [x] **datatable-generalize** — ✓ Landed 2026-06-02. Full replacement of
  `View.FetchTable` with `View.DataTable(signal, columns, actions)`. Added
  `EventHandler.ItemAction` + `SetFieldToSignal`; `DataTableLowering`
  (`<table><thead><tbody>` chrome via `ModelView/ForModel/ModelText/Button`);
  Solid/Custom Phase 2 tbody span fix; native Swing `JTable` renderer;
  `dataTable`/`fcol`/`rowDelete`/`rowPost`/`rowLink` .ssc surface;
  `FetchTable` and all its plumbing deleted (grep gate: 0 hits).
  React (56), Vue (58), Solid (63), Custom (63), Swing (19), FetchPlugin (5) tests pass.

- [x] **datatable-authoring-surface-cleanup** — ✓ Landed 2026-06-03. Phase 0
  of `docs/datatable-authoring-surface.md`: imported `rowEditAction` in
  `runtime/std/ui/data.ssc`; made interpreter `fieldColumn` accept the
  null/default edit action emitted by `fcol`; made row-action intrinsics accept
  null/default `emptyHeaders` from std/ui helpers, including the bare
  `Value.NativeFnV("emptyHeaders", ...)` sentinel; migrated examples/docs to
  the explicit `fetchUrlSignal(...)` + `dataTable(signal, columns, actions)`
  contract; added editable-column intrinsic coverage and a source guard that
  keeps `rowEditAction` imported by `std/ui/data.ssc`. Verification:
  `git diff --check`; `rg -n "dataTable\\(fetchUrl|fetchTable\\(|fetchTable\\b|fetchTableView" runtime/std/ui examples docs/native-platform.md docs/user-guide.md docs/tutorial.md README.md` (no hits);
  `cd <worktree> && sbt "fetchPlugin/testOnly scalascript.compiler.plugin.fetch.FetchPluginInterpreterTest" "backendInterpreter/testOnly scalascript.JvmGenSwingRuntimeTest" "backendInterpreterServer/testOnly scalascript.ToolkitDemoValidateTest"`.

- [x] **datatable-path-validation** — ✓ Landed 2026-06-03. Phase 1 of
  `docs/datatable-authoring-surface.md`: `ModelPathValidator` now validates
  typed `View.DataTable` descriptors as semantic nodes when
  `dt.signal.codec == CodecHint.Json(rowModelName)`. It checks
  `FieldColumnDef.fieldPath`, editable-column `RowInlineEdit.idField`,
  `RowDelete.idField`, `RowPost.bodyField`, and `RowLink.fieldPath`; raw
  fetch-backed tables remain permissive. Design note: do not validate
  `DataTableLowering.lower(dt)` directly because its synthetic
  `ForModel(signal.id, "", "row", ...)` table chrome would create false
  non-list errors. Verification: `git diff --check`; `cd <worktree> && sbt
  "frontendCore/testOnly scalascript.frontend.ModelPathValidatorTest"` (17
  tests); `cd <worktree> && sbt "frontendCore/test"` (46 tests).

- [x] **datatable-source-abstraction** — ✓ Landed 2026-06-03. Phase 2 of
  `docs/datatable-authoring-surface.md`. Introduced `TableDataSource` sealed
  trait (`Remote`, `StaticRows`, `SignalRows`) in `frontendCore/Primitives.scala`.
  Changed `View.DataTable(signal: FetchUrlSignal)` to `source: TableDataSource`.
  Updated all backends (React/Vue/Solid/Custom/SwiftUI/Swing/JavaFX) to gate on
  Remote; non-Remote renders a stub. Added `staticRowsSource`/`signalRowsSource`
  intrinsics and `staticDataTable`/`signalDataTable` helpers in `std/ui/data.ssc`.
  All tests green: frontendCore/React/Vue 47+56+58 passed; fetchPlugin 6 passed.

- [x] **datatable-column-action-expressiveness** — ✓ Landed 2026-06-03 (eb51bf99).
  Phase 3 of `docs/datatable-authoring-surface.md`. Added `ColumnKind`
  (Text/Date/Money/StatusBadge/Link), `RowPayload` (Field/WholeRow/Fields),
  `View.FormattedField`, width hints on columns. Updated all 8 emitters +
  `FetchIntrinsics` + `primitives.ssc` / `data.ssc`. 58 tests green.

- [x] **cli-command-result-exitcode** - ✓ Landed 2026-06-02. Introduced
  internal `ExitCode` / `CommandResult`, `CliCommand.runResult` compatibility
  default, `CommandRegistry.dispatchResult`, and top-level launcher propagation.
  `LspCmd` is the first command migrated off direct `System.exit` on the main
  dispatch path; legacy `run(args): Unit` remains for the plugin SPI and
  existing commands. Tests: `CommandResultTest` + `CommandRegistryTest` (11).

- [x] **jvmgen-ui-bridge-split** - ✓ Landed 2026-06-02. Extracted the
  frontend `std.ui.primitives` generated-source block from `JvmGen.scala` into
  `JvmRuntimeUiPrimitives.source`, leaving the call site as a direct string
  append. Verification: `git diff --check`; `emit-scala
  examples/frontend/dashboard/dashboard.ssc` compared byte-identical against
  `origin/main` after normalizing absolute `//> using jar` paths.

- [x] **build-family-registry** — ✓ Landed 2026-06-03. `FrontendSpec` +
  `allFrontends` registry; root aggregate + cli derived from it.

## Interpreter perf — Phase C + D continuation (open)

After the 2026-06-02 wins (recursive cluster at JVM-codegen speed,
foreach cluster 4–8× faster, pureCallSum 205×), two open directions
remain. Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a
non-win.

### Current baseline (2026-06-03 end-of-session, default flags ALL ON)

> **2026-06-03 update.** Full `scripts/bench interp` run (wi=3 mi=5 1 fork)
> after landing all dual-bank / Phase C-D / while-jit-inline-match work.
> Major improvements since the 2026-06-02 table:
> - `instanceFieldAccess` 8.4 → **0.042** ms (200×, while-jit-inline-match)
> - `pureCallSum`         12.5 → **0.273** ms (46×, phase-c-bytecode-pure-fn-call + A.1/A.2)
> - `pureCallSum2`        14.0 → **0.281** ms (50×)
> - `patternMatchHeavy`   10 → **0.405** ms (25×, while-jit-mixed + mixed-foreach-set)
> - `patternMatchSet`     113 → **0.201** ms (560×)
> - `patternMatchWide`    74 → **1.527** ms (48×)
> - `recursiveEval`       13.0 → **3.570** ms (3.6×, dual-bank LExpr + LApplyR1)
> - `recursiveEvalMixed`  13.2 → **3.600** ms (3.7×)
> - `mapForeach`          532 → **2.142** ms (248×, fasttier-2arg-callentry + fast-map-foreach-preresolved)
> - `refChainArg`         9.7 → **0.375** ms (26×, while-jit-ref-select-chain)
> Three remaining slow spots: `recursiveEval`/`recursiveEvalMixed` 3.57/3.60 ms,
> `mapForeach` 2.14 ms, `patternMatchWide` 1.53 ms.
> Full analysis: [`docs/bench-analysis-2026-06-03.md`](docs/bench-analysis-2026-06-03.md).

Default flags ON: `SSC_JIT=on`, `SSC_FASTTIER=on`, `SSC_JIT_BYTECODE=on`.
Backend selector: `SSC_JIT_BACKEND=asm` (default: `javac`; `AsmJitBackend` landed 2026-06-03).
Opt-out via `=off` on env or `-D…=off` on JMH forks. Numbers below are
the baseline for any next A/B; if your stash-baseline gives wildly
different numbers, sanity-check sbt picked up the worktree (`set current
project to root (in build file:<worktree-path>/)` line) before trusting.

`InterpreterBench` (ms/op, 1 fork × 5 iters, 2026-06-03):

| Bench | Current | Notes |
|---|---|---|
| `arithLoop` | **0.270** | JVM parity |
| `effectPure` | 0.015 | trivially small |
| `instanceFieldAccess` | **0.042** | while-jit-inline-match 195× |
| `mapForeach` | **0.187** | while-jit-map-foreach 11.4× |
| `matchBodyBaseline` | 0.045 | — |
| `nestedMatchExpr` | 0.043 | — |
| `patternGuard` | 0.046 | — |
| `patternMatchHeavy` | **0.405** | while-jit-mixed win; near JVM parity |
| `patternMatchSet` | **0.201** | — |
| `patternMatchWide` | 1.527 | **OPEN** — phase-d-patternmatch-fused-foreach target |
| `pureCallSum` | **0.273** | JVM parity |
| `pureCallSum2` | **0.281** | JVM parity |
| `pureCallSumIf` | **0.287** | JVM parity |
| `pureCallSumBlock` | **0.278** | JVM parity |
| `recursionFib` | **1.262** | BEATS JVM (1.42 ms) |
| `recursionFibD` | **1.504** | JVM parity |
| `recursionFibMul` | **1.330** | JVM parity |
| `recursionFibMulD` | **1.666** | JVM parity |
| `recursionTco` | **0.034** | JVM parity |
| `recursiveEval` | **3.567** | INVOKESTATIC arm self-calls confirmed (8.4× vs 29.9 ms JIT-off) |
| `recursiveEvalMixed` | **3.660** | INVOKESTATIC arm self-calls confirmed (12.3× vs 45.2 ms JIT-off) |
| `refChainArg` | 0.375 | while-jit-ref-select-chain 26× |
| `refFieldArg` | 0.046 | — |
| `tupleMonoid` | 0.202 | interp ok; JS 2.52 ms still open (js-codegen-opt-p2) |

`RuntimeBench` cross-backend (µs/op, default flags):

```
interp_recursionFib:   1190   vs jvm 1281  (interp 0.93× — FASTER than JVM!)
interp_recursionTco:     31   vs jvm   24  (interp 1.29× — parity)
interp_patternMatch:  114610  vs jvm  566  (interp 203× off — main Phase D gap)
interp_arithLoop:        283  vs jvm  274  (interp ~1× — at JVM parity, while-JIT landed)
```

### Methodology for next focused commits

These rules came out of today's session and apply specifically to the
cross-module changes in the queue below. They are NOT bureaucracy — each
maps to a concrete mistake that was caught (or nearly missed) on the
verify step. Apply them.

1. **JFR-profile-first before any invasive optimization.** Don't write
   code to attack an assumed bottleneck. Take a JFR alloc + CPU sample
   under the bench shape with default flags, identify the actual
   dominant allocator / hot leaf, and only then design the fix.
   `sbt "interpreterBench/Jmh/run -wi 2 -i 3 -f 1 -prof \"jfr:configName=profile\" .*<bench>.*"`
   then parse `jdk.ObjectAllocationSample` events for top-class +
   top-scalascript-site weight. A sampler can over-attribute to a hot
   leaf — cross-check against deterministic `-prof gc` `alloc.rate.norm`
   before committing to a design.

2. **Small focused commits over cross-module megacommits.** For changes
   that touch ≥3 modules (e.g. EvalRuntime + CallRuntime +
   DispatchRuntime + FastTier), split into a sequence: infrastructure
   first (e.g. the TLS plumbing alone), then the FastTier-side
   integration behind an off-by-default flag, then the EvalRuntime
   recognition that flips the flag for the matching pattern. Each
   compiles + tests + benches separately. A single 4-file commit that
   "all works together" is the highest-risk shape and the hardest to
   bisect when something silently regresses.

3. **Boolean-return guards on bytecode JIT-style emissions.** Anything
   that emits a `long` return MUST guard against bodies whose top-level
   result is Boolean (`< <= > >= == != && ||`, or a `Term.If` whose
   branches end in such). The wrapping site will turn the result into
   `IntV(0|1)` and quietly break every consumer that expected `BoolV`.
   This is the bug pattern from commit `72aab9aa`'s verify step
   (6 tests caught at the test-suite gate). See `BytecodeJit.isBoolReturning`.

4. **try/finally is mandatory for TLS plumbing.** Any setter/clearer
   pair on a `ThreadLocal[…]` must be wrapped `try { setter; thunk }
   finally { clearer or restore-previous }`. A throwable from `thunk`
   leaves a stale slot for the next call on this thread. Specifically
   for the Phase D TLS items in this queue.

5. **Always invoke sbt with explicit `cd <worktree-path>`.** The harness
   shell CWD persists across Bash calls. Without `cd`, a previous
   command's CWD may leak and sbt silently picks up the main repo's
   `build.sbt` and target; edits appear to have no effect; benches
   measure the wrong code. AGENTS.md has the rule; respect it.

6. **Re-read files instead of trusting memory.** When you "remember"
   what `FastTier.tryDoubleAccumForeach` checks or which `case _ => null`
   exists in `collectFastAssignBody`, Read the file. The cost of
   re-reading is small; the cost of an incorrect refactor based on
   stale recall is a full debug cycle.

7. **Trust the existing test suite as the safety net.** Full
   `backendInterpreter/test` is ~50-60s. Run it in BOTH default and
   explicit-off modes for the flag you touch. A green default + green
   off is the only proof that the fallback works.

- [x] **phase-c-bytecode-double** — ✓ Landed 2026-06-02 commit `ab7f782e`.
      Double-typed params/return for non-match-bodied fns: `walkDouble`
      parallel walker, `Result.resultIsDouble`, MH signature with
      `classOf[Double]`, `JitRuntime.wrapBytecodeResult` discriminator.
      `recursionFibD` 33.1 → 1.45 ms (22.8×).

- [x] **phase-c-bytecode-mixed-type** — ✓ Validated 2026-06-02 commit
      `c9bf3b48`. Per-param `paramIsRef` + per-arg `walkLong`/`walkRef`
      dispatch already worked from the ADT-match slice (`aabc70b0`); the
      bench `recursiveEvalMixed` codifies the guarantee. No code change
      needed.

- [x] **phase-c-bytecode-double-globals** — ✓ Landed 2026-06-02.
      `BytecodeJit.readGlobalDouble(name)` parallel to `readGlobalLong`
      (same TLS-interpreter mechanism), and `walkDouble`'s `Term.Name`
      free-name case now emits the call when a compile-time
      `interp.globals` lookup resolves to `DoubleV`. IntV does not widen
      (deliberate — see the rationale in `readGlobalDouble`'s scaladoc).
      Bench `recursionFibMulD` (`val mul = 7.0`, recursive Double fib
      using `mul` in the base case) now matches the existing Int-globals
      shape: 6.01 ms vs `recursionFibMul` 5.86 ms (parity, within noise),
      vs off-mode floor 7118.8 ms (~1185×). All other benches stable
      (recursionFib 1.19 ms, recursionTco 0.032 ms, recursiveEval
      12.82 ms, patternMatchHeavy 115.2 ms). Full 1205-test suite green
      in both default and `SSC_JIT_BYTECODE=off` modes.

- [x] **phase-c-bytecode-invokeExact** — ✓ Landed 2026-06-02 commit `471b38d1`.
      Added `JitInterfaces.scala` (6 traits: LongFn1/DoubleFn1/ObjToLong/ObjToDouble/LongFn2/DoubleFn2).
      BytecodeJit.doCompile now adds `implements` clause + forwarding `apply` method to
      generated Java source and stores a `Result.direct` instance. JitRuntime.invokeBytecode1/2
      dispatches through `direct` without boxing when non-null; mixed-param functions fall back
      to the existing `mh.invoke` boxed path. HotSpot EA was already eliminating most boxing
      at steady state (recursionFib ≈1.26 ms unchanged), but the typed path removes the EA
      reliance. All 1205 tests pass.

- [x] **fasttier-2arg-callentry** — ✓ Landed 2026-06-02.
      `tryLongAccumForeachMap` + `tryDoubleAccumForeachMap` parallel to
      the existing list/set variants. Closure shape detected:
      `(p1, p2) => acc = acc + paramRef` where `paramRef` is one of
      the two closure params (covers the `total = total + v` family).
      Direct entry-component extraction — no inner `fn` lookup, no
      slot-match compile, no closure-env materialization. Hooked at
      BOTH `dispatchMap1.foreach` (the single-arg dispatch site —
      where `m.foreach(closure)` actually lands) AND `dispatchMap.foreach`
      (defensive — the 2+arg path), with a per-AST shape cache. Bench
      `mapForeach` **532 → 110 ms (4.8×)**. All other benches stable;
      full 1205-test suite green in both default and `SSC_FASTTIER=off`
      modes. **Gotcha for future agents**: `m.foreach(closure)` is 1-arg
      → goes through `dispatchMap1`, NOT `dispatchMap`. The dispatchMap
      site fires only for explicit multi-arg shapes.

- [x] **jit-match-recursive-descent** — ✓ Landed 2026-06-04.
      Verified that `walkLong`'s self-call case (present since the initial JIT
      commit) correctly emits INVOKESTATIC for arm-bound Object arg recursive
      calls in both 1-param (`ObjToLong` — `eval(l)`) and 2-param
      (`LongObjToLong` — `gEval(scale, l)`) shapes.  `walkArm` marks arm
      bindings passed to the recursive call as ref-typed via `bindingIsRef`,
      and `walkRef` resolves them to the hoisted `Object` Java locals.
      Note: the spec's root cause ("walkMatchBody bails") was incorrect —
      `walkLong`'s self-call case was never broken.  The 3.57 ms represents
      the achievable floor for 1021-node INVOKESTATIC tree traversal at
      ~3.5 ns/node; the spec's 35× target required ~0.1 ns/node which is
      sub-clock-cycle and physically unreachable.

      Verification:
      - `JitLintTest` — 4 new tests added: lint + direct-interface correctness
        for both `eval` (ObjToLong, build(3)=27) and `gEval` (LongObjToLong,
        result=26). All 1243 interpreter tests pass.
      - `scripts/bench interp recursiveEval` post-verification:
        `recursiveEval` 3.567 ± 0.317 ms/op,
        `recursiveEvalMixed` 3.660 ± 0.471 ms/op
        (JIT-off baseline: 29.9 ms / 45.2 ms — 8.4×/12.3× improvement
        already achieved in prior work via LApplyR1/LApplyR2 + INVOKESTATIC).

- [x] **while-jit-map-foreach** — ✓ Landed 2026-06-04. Fused outer-while + Map.foreach((k,v)) bytecode.
      Root cause of `mapForeach` 2.142 ms: the 2-param `(k,v)` closure is
      handled by `fasttier-2arg-callentry` (pre-resolved accumulator) but
      HashMap iteration itself still runs through Scala's `HashMap.foreach`
      without JVM method fusion.  At 500 K iterations / 2.14 ms = 4.28 ns/iter
      vs `patternMatchHeavy`'s 1.35 ns/iter (List via `while-jit-mixed`).

      Fix in `JavacJitBackend` / `WhileJitEntry`:
      - Add recognition in `tryCompileWhileMixed` (or a new
        `tryCompileWhileMapForeach`) for an inner body of shape
        `m.foreach((k, v) => { acc = acc + v })` where `m` is a
        val-bound `MapV` (already in TLS refs via `LRefConst`)
      - Emit a native `entrySet()` for-loop in the generated Java method:
        ```java
        for (java.util.Map.Entry<String,Object> _e :
                 ((Value.MapV)_refs[R]).javaMap().entrySet()) {
            _slot_acc += asLong(_e.getValue());
        }
        ```
      - `WhileJitEntry.mapLongFns` slot (parallel to `refLongFns`) + matching
        `JitGlobals.getMapLongFns` probe
      - Double-accumulator variant via `mapDoubleFns`

      **Bench target:** `mapForeach` 2.14 → ~0.2 ms (~10×).
      Spec: [`docs/bench-analysis-2026-06-03.md`](docs/bench-analysis-2026-06-03.md).

- [x] **phase-c-bytecode-mutual** — ✓ Landed 2026-06-03 commit `5b31db2`.
      `JavacJitBackend` co-emits JIT-compatible sibling defs as extra static
      methods in the same generated Java class and lets mutually recursive
      cycles call each other via ordinary static calls. Covered: pure-int
      sibling calls, pure-int mutual recursion, and mutually recursive
      long-returning ref-param ADT match functions (`Object` params classified
      from match scrutinees and sibling call positions). Out of scope until a
      concrete workload needs it: double-returning cycles and `ObjToObject` /
      ref-returning mutual cycles.

      Verification:
      - `cd .worktrees/feature/phase-c-bytecode-mutual-20260603 && sbt "backendInterpreter/testOnly scalascript.SscVmTest"` — 16 tests green.
      - `cd .worktrees/feature/phase-c-bytecode-mutual-20260603 && sbt "backendInterpreter/test"` — 1236 tests green.
      - `scripts/bench interp recursiveEval` after the binding classifier change:
        `recursiveEval` 3.629 ± 0.078 ms/op,
        `recursiveEvalMixed` 3.718 ± 0.153 ms/op. Baseline before this slice:
        3.518 ± 0.132 / 3.743 ± 0.051 ms/op; no meaningful regression signal.

- [x] **phase-c-bytecode-int-tag** — ✓ Landed 2026-06-02 commit `ebb3e9a3`
      (merge `f5ab3f20`). Added `var typeTag: Int = 0` to `Value.InstanceV`;
      `Interpreter.typeTagFor(typeName)` allocates monotonically increasing int tags;
      `StatRuntime` captures the tag at type registration and sets `inst.typeTag = tag`
      at every construction. `BytecodeJit.walkMatchBody` pre-resolves all arm tags at
      JIT-compile time; emits `switch(inst.typeTag())` when all tags are known → JVM
      `tableswitch` (O(1)), falls back to `switch(inst.typeName())` when any tag is 0.
      Bench (2 forks, 7 WI, 5 iter vs main):
        patternMatchHeavy:   2.230 → 1.976 ms  (~11%)
        patternMatchSet:     2.210 → 1.970 ms  (~11%)
        patternMatchWide:    3.623 → 2.284 ms  (~37%)
        recursiveEval:      13.405 → 7.607 ms  (~43%)
        recursiveEvalMixed: 11.861 → 7.456 ms  (~37%)
      Wide and recursiveEval gain most — arm count amplifies O(1) vs O(N) advantage.

- [x] **phase-c-mixed-while-jit** — ✓ Landed 2026-06-02 commit `44c3812c`.
      `boxToInteger <- slotOf$1` was 44% of CPU on `instanceFieldAccess` JFR
      (same source as the 25%-on-`patternMatchHeavy` observation that opened
      this item). Root cause: `mutable.LinkedHashMap[String, Int]` in
      `tryLongWhileAssign` and `tryMixedLongWhile`'s compile prologues — every
      `get`/`update` boxed the Int slot index. Replaced with a private
      Array-backed `SlotTable` (linear scan, no boxing) that exposes
      `slotIndex` / `register` / `contains` / `nameAt` / `size`. JFR after:
      `boxToInteger` no longer appears in the top 15 frames. Wall-clock not
      conclusive under current session noise (arithLoop drifted 0.26 → 0.37 ms
      in same run), but the eliminated allocator class is the reliable signal.

- [x] **interp-pattern-arm-int-tag-guard** — ✓ Landed 2026-06-02 commit
      `62b16bb8`. Companion to the JIT-side `phase-c-bytecode-int-tag` work:
      gives the LMatch / `runValue` / `runValueDouble` / `runValueLong`
      interpreter paths an Int-tag scan. New `CompiledMatch.ctorTagsInt`
      array, populated when every non-catch-all arm's ctor name is a
      registered ADT (`typeTagMap.contains`); `-1` is the catch-all sentinel.
      Pat.Extract arm closures capture `tn3Tag` and gate on
      `(tn3Tag != 0 && inst.typeTag == tn3Tag) || inst.typeName == tn3` — the
      Int compare short-circuits the hot path (Pair/Add/Mul/etc.), the String
      fallback covers unregistered ctor names (`Some`/`None`) and the rare
      bare-`Value.InstanceV(...)` constructions (e.g. EffectsRuntime's
      `RuntimeException`). JFR on `instanceFieldAccess`: `String.equals`
      dropped 59 → 31 samples (~50% reduction); the remaining 31 are
      `HashMap$Node.findNode` from the per-iter `globals.getOrElse(scrutName)`
      lookup — a separate lever (scrutinee invariance caching).

- [x] **phase-c-bytecode-wider-match** — ✓ Landed 2026-06-04. Wildcard/catch-all
      arms (`Pat.Wildcard` + `Pat.Var`) in all arm walkers (`walkArm`,
      `walkArmAsIfBranch`, `walkArmExpr`) and both match builders
      (`walkMatchBody`, `walkMatchExpr`). 17 JitLintTest + 1251 suite green.
      Remaining deferred (no bench bailing yet): literal field patterns, `Pat.Bind`,
      `Pat.Alternative`.

- [x] **phase-c-bytecode-block-single** (Direction A.1) — ✓ Landed 2026-06-02
      commit `b4eb11f1`. `walkLong` / `walkDouble` / `walkRef` now unwrap
      `Term.Block(b)` where `b.stats.length == 1` to the inner term, matching
      the treatment `walkLocalSlotCtx` got in commit `b4ae788c`. Multi-stmt
      blocks still bail (Direction A.5 — `phase-c-bytecode-block-multistat`).
      Tests 1204/1205 green (`I18nSsrTest` pre-existing JsGen WIP failure
      unrelated). No targeted bench moved measurably in this session due to
      system noise, but the change is correct infrastructure.

- [x] **phase-c-bytecode-if-in-while** (Direction A.2) — ✓ Landed 2026-06-03
      commit `b4ae788c`. `walkLocalSlotCtx` covers `Term.If` (ternary
      emission) and single-stat `Term.Block`. Loops with
      `x = if cond then a else b` now compile via while-JIT.

- [x] **phase-c-bytecode-pure-fn-call** (Direction A.3) — ✓ Landed 2026-06-03
      commit `4a4a1e09`. `walkLong` `Term.Apply` path detects globals-bound
      `def` and emits a static call by sanitised name. `LongFn1`/`LongFn2`
      traits added. **Bench `pureCallSum`/`pureCallSum2`: 13 → 0.28 ms (47×,
      JVM parity).**

- [x] **fast-map-foreach-preresolved** — ✓ Landed 2026-06-03 commit `1611e438`.
      `PreResolvedFastLongMapForeach` + `PreResolvedFastDoubleMapForeach` complete the
      fast-variant series (List/Set already had these). `ResolvedLong/DoubleMapAccum`
      structs carry `accName + useFirst`; `tryResolveLong/DoubleMapAccum` check guards
      once at setup; `runLong/DoubleAccumForeachMapFast` use pre-wired `cachedSlot`
      field, bypassing TLS on each iteration. Bench: mapForeach 2.238 → 2.023 ms (~10%).

- [x] **while-jit-mixed-foreach** — ✓ Landed 2026-06-03 commit `70ff8947`.
      Fused outer while + inner `xs.foreach(s => acc += fn(s))` into a single
      JVM-compiled Java method via new `tryCompileWhileMixed` SPI. Eliminates
      per-outer-iteration virtual dispatch to `PreResolvedForeach.run` and
      TLS slot reads; enables JVM devirtualization of the monomorphic `fn.apply(item)`
      call site. `WhileJitEntry.refDoubleFns` + `JitGlobals.getRefDoubleFns` for
      Double-acc; `EvalRuntime.tryWhileJitMixed` tries fused path first, falls back.
      Bench (2f wi=3 mi=10 ms/op):
      patternMatchHeavy 0.936 → 0.397 ms (2.37×);
      patternMatchWide 1.628 → 1.389 ms (1.17×);
      interp_patternMatch 1167 → 676 µs (1.73×, 1.21× above JVM floor).

- [x] **jit-fieldsarr-no-null-check** — ✓ Landed 2026-06-03 commit `1380fec5`.
      After `phase-d-instancev-array-repr-activation`, `StatRuntime` always populates
      `fieldsArr` at InstanceV construction; the `faVar != null ? faVar[i] : inst.fields().apply(name)`
      ternary in all four JIT arm-emission sites (`walkArm` switch, `walkArmAsIfBranch`,
      `walkMatchBody`, `walkRefArm`) was dead code that prevented the JVM from proving
      `faVar` non-null and eliminated an implicit null-check opportunity on array access.
      Replaced with direct `faVar[i]`; removed now-unused `val fname = fieldOrder(fi)`.
      4 insertions, 18 deletions. Bench: patternMatchHeavy 1.128 → 0.861 ms (~24%).

- [x] **phase-c-bytecode-foreach-static** (Direction A.4) — ✓ Landed 2026-06-03
      as `while-jit-mixed-foreach` (commit `70ff8947`). Implemented via
      `tryCompileWhileMixed` (new SPI method) rather than merging into
      `tryCompileWhileLong`; generates `WhileMixed_<n>` Java class with fused
      outer while + inner foreach. SetV extended in `while-jit-mixed-foreach-set`
      (commit `767eeea0`). patternMatchHeavy 2.37×, interp_patternMatch 1.73×.

- [x] **while-jit-mixed-foreach-set** — ✓ Landed 2026-06-03 commit `767eeea0`.
      Extended `tryCompileWhileMixed` (JavacJitBackend) and `tryWhileJitMixed`
      (EvalRuntime) to accept `Value.SetV` receivers alongside `Value.ListV`.
      For SetV, generates `Set.iterator()/hasNext()/next()` inner loop instead
      of the List `head/tail` unroll; all other codegen (outer while, int-assign
      RHSes, accumulator writeback) unchanged. EvalRuntime receiver resolution
      now matches both `lv: Value.ListV` and `sv: Value.SetV` from globals.
      Bench (5i wi=3 ms/op): patternMatchSet 0.797 → 0.283 ms (~2.8×).
      1233/1233 tests green.

- [x] **phase-c-bytecode-match-double-body** — ✓ Landed 2026-06-02.
      Three sub-pieces, three different landings:
      - **A** (walkMatchBody Double-typed via walkDouble) — was already
        in place pre-session via the `if ctx.isDouble then walkDouble`
        dispatch at BytecodeJit:607-608. No commit needed.
      - **B** (replace if-equals chain with int-tag switch) — landed
        2026-06-02 in commit `ebb3e9a3` (`phase-c-bytecode-int-tag`).
      - **D** (drop the duplicate `t == tn` arm-closure guard) —
        partially landed 2026-06-02 in commit `62b16bb8`
        (`interp-pattern-arm-int-tag-guard`): the Int-tag short-circuit
        skips String.equals on the hot path; the String fallback is
        retained for unregistered InstanceVs (e.g. `Value.InstanceV(
        "RuntimeException", …)` constructed bare in EffectsRuntime).
        Full removal needs every direct construction site to set
        typeTag, which is its own backlog item — not blocking this
        line item.
      - **Capability extension** (not in the original 3-part bundle but
        completes the spirit of the work item): commit `48768a20`
        adds `walkMatchExpr` so a `Term.Match` appearing as a
        sub-expression — not as the entire function body — JITs via
        a Java switch *expression* wrapped in a primitive
        LongSupplier/DoubleSupplier IIFE. walkLong / walkDouble now
        delegate to it. No current bench exercises this path; future
        nested-match shapes will JIT instead of tree-walking.
      Original target — `interp_patternMatch` 7615 → < 2500 µs/op —
      achieved by the int-tag landing alone: current ~1700 µs on a
      clean system (4.4×, exceeded the 3× spec target).

- [x] **phase-c-bytecode-block-multistat** (Direction A.5) — ✓ Landed 2026-06-03
      commit `6e11cc62`. `JavacJitBackend.walkBlockStmts` + `blockStmtsCtx`:
      multi-stat `Term.Block` bodies compile to Java `long`/`double` locals +
      final return. Expression-context multi-stat blocks use LongSupplier IIFE.
      `AsmJitBackend.emitValBindings`: LSTORE/DSTORE + slot allocation. Both
      backends support block-ends-with-match. 1228/1228 tests green.

- [x] **asm-jit-lapplyobjref-parity** — ✓ Landed 2026-06-03. Two commits:
      `5152e001` (AsmJitBackend 2-param ref-mixed typed interfaces:
      `LongObjToLong`/`ObjLongToLong`/`LongObjToDouble`/`ObjLongToDouble`)
      + `f7fc2b34` (dual-bank-lapply-r1 replaced `LApplyObjRef` with
      `LApplyR1` routing through `JitBackend.default.tryCompile` — works
      regardless of which JIT backend produced the `ObjToLong` interface).
      Bench gates `nestedMatchExpr`/`refFieldArg`/`recursiveEvalMixed`
      all locked in `InterpreterBench`.

- [x] **asm-jit-parity-optimizations** — ✓ Landed 2026-06-04. Brought
      `AsmJitBackend` back to the current Javac optimization surface. Spec:
      [`docs/asm-jit-parity.md`](docs/asm-jit-parity.md).

      Phase 1: ✓ Landed 2026-06-04 commits `f48bcf1f`, `02fbc176`.
      shared `JitPredicates.isBoolReturning`, unary `+`/`-`, multi-statement
      expression blocks, guarded ADT match arms, `ObjToObject` ref-returning
      match functions, and long-returning sibling/mutual co-emit with
      callee-param-aware ref binding classification. Also fixed ASM
      string-chain fallback to clear `typeName` before arm-label jumps.
      Tests: direct ASM lock-ins in `SscVmTest` plus `JitLintTest` parity.
      Verification:
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && sbt "backendInterpreter/compile"`;
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && sbt "backendInterpreter/testOnly scalascript.SscVmTest"`
      (21/21);
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.InterpreterTest"`
      (139/139);
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-20260604 && SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.SscVmTest scalascript.InterpreterTest scalascript.JitLintTest"`
      (174/174).

      Phase 2: ✓ Landed 2026-06-04 commits `77cc22dc`, `702ce5f5`.
      ASM while-JIT now carries ref globals,
      `ObjToLong`, and `ObjToObject` arrays through `WhileJitEntry`; generated
      while methods hoist TLS refs and ref functions into locals;
      `walkWhileSlot` supports ref globals, simple field/select refs,
      `ObjToObject` chains, and inline ref-match RHS helpers. ASM match
      constructor extraction accepts qualified patterns such as
      `Shape.Circle(...)`. ASM also overrides `tryCompileWhileMixed` for
      ListV/SetV foreach fusion with `ObjToLong` and `ObjToDouble`
      accumulator functions.

      Rebase parity follow-up: after `phase-c-bytecode-wider-match` and
      `while-jit-map-foreach` landed on `origin/main`, ASM also gained
      wildcard / named catch-all ADT match arms and MapV foreach key/value
      fusion via `WhileJitEntry.mapIsKeyMode` plus the runtime-provided
      pre-extracted `Object[]`.

      Verification:
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-p2-20260604 && sbt "backendInterpreter/compile"`;
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-p2-20260604 && sbt "backendInterpreter/testOnly scalascript.SscVmTest"`
      (27/27);
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-p2-20260604 && SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.JitLintTest"`
      (17/17);
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/asm-jit-parity-optimizations-p2-20260604 && SSC_JIT_BACKEND=asm sbt "backendInterpreter/testOnly scalascript.SscVmTest scalascript.InterpreterTest scalascript.JitLintTest"`
      (183/183).

## Interpreter perf — Dual-bank LExpr roadmap (2026-06-03)

The "make JIT work always" strategic plan from
`~/.claude/plans/noble-discovering-knuth.md`. Phase 1 (dual-bank LExpr)
+ Phase 1b (2-arg ref-mixed) + Phase 2 (JitLint) shipped 2026-06-03.
Remaining items below are the "deferred" parts of the plan — each
gives the bench/shape it unblocks so a future agent can pick the
highest-impact item.

- [x] **dual-bank-lexpr-infra** — ✓ Landed 2026-06-03 commit `e8c2f64b`.
      `LExpr.eval(slots, refs)` signature; `LRefExpr` empty hierarchy;
      `SlotTable` kind tags (`SlotKindLong` / `Ref` / `Double`);
      `EmptyRefs` singleton; both tryLongWhileAssign and
      tryMixedLongWhile while-loop entries pre-allocate the refs bank
      based on `slotOfName.refCount`. No behavioural change.

- [x] **dual-bank-lapply-r1** — ✓ Landed 2026-06-03 commit `f7fc2b34`.
      `LApplyR1(argR: LRefExpr, ObjToLong)` + `LRefConst(v: AnyRef)`
      replace the specialised `LApplyObjRef` (commit 13af281f).
      `compileRefExpr(term)` recognises `Term.Name` for val-bound
      InstanceV. Both `compileExpr` sites use it before the slot-arg
      compileExpr probe. Generalises the 1-arg ref-arg JIT fast-path.

- [x] **dual-bank-lref-field-get** — ✓ Landed 2026-06-03 commit `96ab9004`.
      `LRefFieldGet(instR, fieldIdx)` reads `inst.fieldsArr[idx]` via the
      LRefExpr chain. `compileRefExpr` extended to `Term.Select(qual,
      fieldName)` with static type lookup from val snapshots. Bench
      `refFieldArg` locked in: `f(item.right)` shape — was
      tree-walk ~2700 ms, now LMatch parity ~14 ms (~170×).

- [x] **dual-bank-r2-mixed** — ✓ Landed 2026-06-03 commit `1bd98d51`.
      2-arg ref-mixed JIT dispatch. New typed ifaces:
      `LongObjToLong` / `ObjLongToLong` / `LongObjToDouble` /
      `ObjLongToDouble`. `JavacJitBackend.determineInterface` covers
      all 2-arg (paramIsRef × resultIsDouble) combos.
      `JitRuntime.invokeBytecode2` typed direct dispatch for each
      combination. `EvalRuntime.LApplyR2LongObj` / `LApplyR2ObjLong`
      + 2-arg refMixed fast path in both compileExpr sites. Bench:
      `recursiveEvalMixed` 7.97 → 3.67 ms (-54%, 2.2×).

- [x] **jit-lint-analyser** — ✓ Landed 2026-06-03 commit `0a43e4b1`.
      `vm/jit/JitLint.scala` walks `interp.globals`, simulates JIT
      compile, reports `JitBailReason` per Defn.Def (TryCatch,
      EffectReturn, UsingParams, VarargParam, PatternGuard,
      NonAdtScrutinee, NonExtractPattern, MixedReturnType,
      UnknownShape). CLI: `ssc lint-jit <file> [--json] [--quiet]
      [--fail-on-bail]`. 6-fixture test suite in `JitLintTest`.

- [x] **dual-bank-lapply-r1-to-ref** — ✓ Landed 2026-06-03. `ObjToObject`
      typed interface in `JitInterfaces.scala`; `walkRefArm` + `walkRefMatchBody`
      in `JavacJitBackend` emit ref-returning Java switch (with `Pat.Var`
      wildcard-arm `default:` support); `doCompile` tries ObjToObject path
      first for 1-param ref-scrutinee match bodies; `LApplyR1ToRef(argR:
      LRefExpr, ObjToObject)` in `EvalRuntime`; `compileRefExpr` `Term.Apply`
      case wired in both while-loop entries. Bench `refChainArg`
      (`leafVal(getLeft(tree))` × 1M): **191 → 9.9 ms (19×)**. 1230/1230 green.

- [x] **while-jit-ref-args** — ✓ Landed 2026-06-03 commit `b1c728af`.
      `tryCompileWhileLong` now handles while bodies that call a JIT-compiled
      `ObjToLong` function with a val-bound `InstanceV` argument (e.g.
      `total = total + absVal(aPos) + absVal(aNeg) + ...`).  New `WhileJitEntry`
      replaces bare `Method` in the SPI; carries `refNames` (globals to read
      per invocation) + `refFns` (ObjToLong instances, resolved at JIT time).
      `JitGlobals.withRefs(refs, fns)` TLS mirrors `withInterp`. Guard:
      `isInstanceOf[ObjToLong]` prevents ObjToObject fns (e.g. `getLeft`)
      from being misidentified; `!ctx.isCallee` blocks the path inside
      co-emitted callee static methods (no ref preamble there).
      **Bench wins (wi=5 mi=5 ms/op):**
        patternGuard:      12.4 → 0.044  (282×)
        matchBodyBaseline:  8.4 → 0.043  (196×)
        nestedMatchExpr:    8.6 → 0.042  (205×)
      Tests: 1230/1230 green.

- [x] **dual-bank-lref-match** — ✓ Landed 2026-06-03 commit `2305e321`.
      `LRefMatch(scrutR: LRefExpr, cm: CompiledMatch)` extends `LRefExpr`.
      `compileRefExpr` in both tryLongWhileAssign + tryMixedLongWhile gains
      `Term.Match` case (gates on `cm.valueCapable`). Use case:
      `f(e match { case Circle(r) => r; case x => x })` ref-arg in hot loop.
      Test: LRefMatch with val-bound Shape in 100-iter loop. 1229/1229 green.

- [x] **jit-pattern-guard-conditional-arm** — ✓ Landed 2026-06-03 commit `8924f4e6`.
      `walkMatchBody` detects `hasAnyGuard`; when true emits an if-chain form
      via new `walkArmAsIfBranch` (~80 lines) instead of Java switch. Each arm
      is `if (inst.typeTag() == N) { bindings; if (guard) { return body; } }`.
      Guard conditions compiled via existing `walkBool` (supports `<`, `<=`,
      `>`, `>=`, `==`, `!=`, `&&`, `||`). Also added `Term.ApplyUnary("-"/"+")
      to `walkLong` and `walkDouble`. JitLint test updated: Pat.Extract guarded
      match now `willJit = true`; new test for non-extract guard (still bails).
      **Bench `patternGuard` (4 × 1M pre-built val calls, ms/op):
        JIT off: 13,570 → JIT on: 11.7 → ~1,160× speedup.**
      Tests: 1227/1227 green.

- [x] **while-jit-ref-select-chain** — ✓ Landed 2026-06-03 commit `225d7e32`.
      Extended `walkRefArgCtx` with two new term shapes:
      1. `Term.Select(Name(n), field)` — resolves `n.field` at compile time
         (both must be InstanceV globals); registers dotted key `"n.field"` in
         `refNames`. `tryWhileJit` resolves dotted keys via two-level
         `globals → InstanceV.fields` lookup.
      2. `Term.Apply(fn, [refArg])` where fn is `ObjToObject`-compiled — emits
         `_objFnN.apply(innerRef)` in generated Java. New `refObjFns:
         Array[ObjToObject]` field on `WhileJitEntry`; new TLS
         `refObjFnsTls` / `getRefObjFns()` in `JitGlobals`; `withRefs` gains
         3rd arg `objFns`. `AsmJitBackend` passes `Array.empty[ObjToObject]`.
      **Bench wins (wi=3 mi=5 ms/op):**
        `refFieldArg`  (`f(item.right)`):         9.2 → 0.046 ms (~200×)
        `refChainArg`  (`leafVal(getLeft(tree))`): 9.7 → 0.308 ms (~31×)
      Tests: 1230/1230 green.

- [x] **while-jit-inline-match** — ✓ Landed 2026-06-03 commit `3f05c7f0`.
      Added `Term.Match` case to `walkLocalSlotCtx` in `tryCompileWhileLong`.
      When the scrutinee resolves to a val-bound InstanceV ref slot, a static
      helper `fn_imatch_HASH(Object scrutName)` is co-emitted using the existing
      `walkMatchBody` infrastructure — typeTag-switch + Int field extraction runs
      in native bytecode. Call site: `fn_imatch_HASH(_rN)`. Guard: `!isCallee`.
      Covers `total + (p match { case Pair(a,b) => a+b })` inline ADT matches.
      **Bench (wi=3 mi=5 ms/op):**
        `instanceFieldAccess`: 8.4 → 0.043 ms (~195×)
      Tests: 1230/1230 green.

- [x] **jit-lint-recognisers-pure-predicates** — ✓ Landed 2026-06-03 commit `92eeca9a`.
      `JitPredicates` object hosts `isBoolReturning` so both
      `JavacJitBackend.doCompile` and `JitLint.classifyBailReasons` call
      the same implementation. Three new `JitBailReason` variants:
      `BoolBody`, `ZeroParams`, `TooManyParams(n)` — previously all
      reported `UnknownShape`. `PatternGuard` description updated: guards
      on ADT scrutinees ARE compiled (via `walkArmAsIfBranch`); the reason
      only fires for Int/Long-scrutinee matches. JitLintTest: 10 tests
      (was 7). 1233/1233 interpreter suite green.

- [x] **phase-d-patternmatch-double-slot** — ✓ Landed 2026-06-02 commits `c2986e33` / `e583843c`.
      Double-acc slot bypass: `FastTier.accSlotTls`/`accNameTls` `ThreadLocal[Array[Long]]` pair;
      `withAccSlot(name, slot)(thunk)` setter/clearer (try/finally); `peekDoubleAccName(apply, interp)`
      reads the AST to detect `xs.foreach(s => acc = acc + fn(s))` double-acc closures. In
      `EvalRuntime.tryFastWhileAssign` (BoolV(true) branch), `mixedBody.leadingApplies` is scanned
      for a double-acc name; if found, `initV` bits are stored in an `Array[Long](doubleToRawLongBits, 1L)`,
      `withAccSlot` wraps `tryMixedLongWhile`, and globals are written back once after the loop.
      `tryDoubleAccumForeach` + `tryDoubleAccumForeachSet` read/write the slot instead of globals when
      TLS active; bail clears `slot(1)=0L` to signal validity.
      **Result**: `patternMatchHeavy` GC alloc 4.9 MB/op → 1.7 MB/op (−65%); wall-clock ~113 ms
      (CPU-bound — GC not the bottleneck at this alloc rate, as predicted by JFR profile).

- [x] **phase-d-while-jit** — ✓ Landed 2026-06-02.
      Extends `BytecodeJit` with `tryCompileWhileLong(cond, names, rhs)`:
      walks the condition via `walkLocalBool` and each RHS via `walkLocalSlot`
      (both emit `_v$i` local-variable references so HotSpot can register-allocate
      the slots). Generated Java: prologue copies `long[]` → named locals, tight
      while loop over locals, epilogue writes back. Sequential-assign semantics
      preserved (each slot update immediately visible to subsequent assigns).
      Global `IdentityHashMap[Term, AnyRef]` cache keyed by condition node identity
      ensures javac runs exactly once per unique while loop across all Interpreter
      instances (eliminates per-iteration recompilation in JMH benchmarks).
      Per-interpreter `whileJitCache` acts as secondary fast cache.
      Hooked between `tryHoistedPureWhile` and `tryLongWhileAssign`.
      **Result**: `arithLoop` 2.858 → 0.283 ms (10.1×, JVM parity). 1205 tests green.

- [x] **phase-d-foreach-hoist** — ✓ Landed 2026-06-02.
      Pre-resolve list receiver + closure `FunV` once before the outer `while` loop
      in `tryFastWhileAssign`, then call `FastTier.tryDoubleAccumForeach` directly
      from `tryMixedLongWhile` instead of routing through
      `interp.eval → evalCore → evalApplyGeneral` per outer iteration.
      Safety guard: only pre-resolve when the list receiver is a stable global
      (not in `longAssignNames`). Bail-out path falls through to standard eval.
      `PreResolvedForeach` carries `(applyIdx, list, closure)`.
      `tryPreResolveForeach` extracts a `Value.FunV` from `emptyClosureFunCache`
      or falls back to `interp.eval` (always fast for an empty-capture function).
      **Result**: `patternMatchHeavy` 113 → 10 ms (11×). 1205 tests green.
      Also fixed pre-existing frontend exhaustivity errors (ModelView/ForModel/ModelText
      stubs in StaticJsEmitter, SolidEmitter, VueEmitter) that were blocking test runs.

- [x] **phase-d-instancev-array-repr** — ✓ Landed 2026-06-02 commit `2df35b4b`
      (merge `a1c6cc9c`). Approach changed from the original 5-sub-phase plan:
      converted `enum Value` → `sealed trait Value` so `InstanceV` can carry a
      direct `var fieldsArr: Array[Value] | Null` field (enum bodies prohibit
      extension). StatRuntime populates `fieldsArr` at construction; PatternRuntime
      and BytecodeJit read it directly via `inst.fieldsArr`. WeakHashMap side-table
      removed. Flag default flipped ON (opt-out: `SSC_INSTANCEV_ARRAY=off`).
      Bench: patternMatchHeavy 2.816→2.095 ms (25%), Set 3.280→2.248 (31%),
      Wide 6.917→4.045 (41%). All 1205 tests green.

- [x] **phase-d-instancev-array-repr-infra** — ✓ Done as part of `2df35b4b` above.

- [x] **phase-d-instancev-array-repr-integration-patternruntime** — ✓ Done as part of `2df35b4b` above.

- [x] **phase-d-instancev-array-repr-integration-bytecodejit** — ✓ Done as part of `2df35b4b` above.

- [x] **phase-d-instancev-array-repr-integration-dispatchruntime** — ✓ Skipped: fieldsArr
      is populated at StatRuntime construction; PatternRuntime reads cover the hot path.
      DispatchRuntime field reads not identified as hot by JFR; no code change needed.

- [x] **phase-d-instancev-array-repr-activation** — ✓ Done as part of `2df35b4b` above.
      Flag is ON by default; StatRuntime populates `fieldsArr` at every InstanceV construction.

- [x] **phase-d-instancev-array-repr-flag-flip** — ✓ Landed 2026-06-03 commit `97f420f9`.
      StatRuntime hot-path constructors now write Map.empty into `fields` and populate
      `fieldsArr + fieldNames` in parallel; IMap.Map1/Map2/Map.from not allocated per
      InstanceV. `effectiveFields` method added to InstanceV; `equals`/`hashCode`
      overridden to use it (fixes StatRuntime vs deserialized InstanceV cross-comparison).
      Value.show, DerivesRuntime, DispatchRuntime, OpticsRuntime, PatternRuntime,
      SectionRuntime, ValueSerializer all updated. instanceVArrayEnabled flag removed.
      1233/1233 green. Bench: patternMatchSet 0.283 → 0.197 ms (~30%).

- [ ] **phase-d-patternmatch-fused-foreach** — once
      `phase-d-instancev-array-repr` lands, BytecodeJit could compile
      `area(s) match` directly to a Java method operating on the array
      repr; combined with FastTier-style foreach driver fusion this is
      the path toward closing the remaining `interp_patternMatch` 213×
      off-JVM gap in `RuntimeBench`.

- [x] **phase-d-patternmatchset-direct** — ✓ Landed 2026-06-02 commit
      `8f911f14`. Direct `Set`-aware FastTier path
      (`tryDoubleAccumForeachSet` + `tryLongAccumForeachSet`) hooked at
      `dispatchSet`'s foreach case; skips the `set.toList` allocation.
      `patternMatchSet` 148.5 → 116.4 ms (1.28×).

- [ ] **direct-style-eval-spec** (Direction C blocking task) — write
      `docs/direct-style-eval-spec.md` covering: migration strategy
      (capability flag, phased per-call-site migration), effect
      handling design (control-flow exceptions vs coroutines vs
      cont-aware stack), multi-shot continuation compatibility (verify
      against the `multishot-stack` agent worktree findings), per-file
      migration order (`EvalRuntime` 230 sites → `BlockRuntime` 41 →
      `PatternRuntime` 37 → tail in `DispatchRuntime`). 530+ sites
      total. Multi-week project — **defer until Directions A + B are
      stable**. No Direction C code changes until this spec lands and
      is reviewed. Per `noble-discovering-knuth.md` Direction C.

- [x] **ci-green-audit** — ✓ Landed 2026-06-02/03: batch-1 (a800cb69,
      11 failures) + SimpleYaml (bb6d5fa0) + batch-2 (b15dbffb, 9 CLI
      failures). Root causes: dir refactor (cli/→tools/cli/,
      std/→runtime/std/), MessagePack binary artifact format, missing
      compiler driver guard. All cli/ tests now pass or cancel cleanly.

## Tooling

- [x] **cli-bundle-frontend** — bundle `frontendPlugin` + `fetchPlugin` into the CLI (Compile) so `ssc run` resolves the std/ui frontend externs (`signal`/`lower`/`emit`) and `fetch`. Previously `% Test`, so frontend programs failed with `'signal' not found`. ✓ Landed 2026-05-31: std-ui smoke now emits index.html+app.js and prints `smoke:ok` via the jar; assembly clean; busi domain regression-green. (frontendReact was already Compile.)

- [x] **wire-option-char** — `ValueSerializer` (and thus `toWire`/`fromWire`) didn't handle `Option`/`Char`/`null`, so serializing a record with `Option` fields threw. ✓ Landed 2026-05-31: added `some`/`none`/`c`/`null` wire tags; 3 tests (incl. the busi Account shape with Option fields); suite green (1191).

- [x] **ssc-value-wire** — expose the interpreter wire serializer to `.ssc` as `toWire(value): String` / `fromWire(s): Value`, so programs can persist arbitrary values (case classes/enums, List/Map/Set/Tuple/Option, BigInt/Decimal). ✓ Landed 2026-05-31: 2 builtins over `ValueSerializer`; 4 round-trip tests incl. nested records and an event-log shape; suite green (1188). Unblocks busi Phase 7 (durable event log).

- [x] **interpreter-set-support** — the interpreter had no `Set`: `Set(...)`/`Set[T]()` was `Undefined` and `toSet` faked a deduped `List`. ✓ Landed 2026-05-30: added `Value.SetV(Set[Value])`, a `Set` constructor (`Set(...)`/`Set.empty`/`Set[T]()`), `dispatchSet` (contains/+/-/++/--/&/union/intersect/diff/subsetOf/size/isEmpty/nonEmpty/toList/head + map/filter→Set and foldLeft/exists/forall/foreach/mkString via List), `++`/`+`/`-` operators, sorted deterministic `show`, value-equality, and `"set"` wire tag; `List.toSet` now returns a real Set. 8 tests; full interpreter suite green (1184). (Interpreter scope; JS/JVM `Set` codegen unaffected.)

- [x] **cli-bundle-http-plugin** — `ssc run` couldn't resolve `route`/`serve`/`serveAsync`/`httpPost`: the http plugin was ServiceLoader-registered but `cli` depended on it only at `% Test`, so the assembled `ssc.jar` didn't bundle it. ✓ Landed 2026-05-30: moved `httpPlugin` + `wsPlugin` to Compile scope on `cli` (matching `graphPlugin`/`deployPlugin`); ServiceLoader auto-loads them in `ssc run`. Verified live (serveAsync + httpPost round-trip), assembly clean, `cli/Test/compile` green, functional smoke (plain run + all busi phases) green. `BackendRegistryTest` unaffected (subsetOf; http/ws spiVersion 0.1.0).
- [x] **import-transitive-helpers** — fix: an imported module's exported function couldn't call its own internal helpers / its non-re-exported imports (e.g. `validateEntry` using `minorUnits` pulled from `std/money`) — they were `Undefined` at call time in the importer. ✓ Landed 2026-05-30: `SectionRuntime.runImport` now also merges the dependency's module-level names into the importer's globals for call-time resolution, skipping any name the parent already has (so identical builtins still can't shadow params — the #503a7e6c guard holds). 2 regression tests (single + multi-level call chains); full interpreter suite green (1176).

- [x] **enum-value-support** — Scala 3 `enum` cases usable as values (bare + qualified refs, matching, `EnumName.values`) across all backends. Spec `docs/enum-values.md`. ✓ Landed 2026-05-30: fixed `Defn.RepeatedEnumCase` (comma-separated `case A, B`) being dropped in `StatRuntime` (interpreter) and `JsGen` (JS), and JS now emits an `EnumName` companion with `values`; JVM already native. 11 tests (interpreter forms + interpreter/JVM/JS cross-backend conformance); suite green (1167).
- [x] **std-root-resolution** — well-known std root so external projects (e.g. `busi`) resolve bare `std/…` imports with zero config. Spec `docs/std-root-resolution.md`. ✓ Landed 2026-05-30: `ImportResolver.discoverStdRoot` discovery chain — `ssc.std.path` / `SSC_STD_PATH` override → `libPath` → `<jarDir>/std` → dev walk-up for ancestor `runtime/std` → `~/.scalascript/std`; pure & unit-tested (8 tests, precedence + missing-candidate + filesystem-root guard). Unblocks busi's `import std/money.ssc` (dev jar finds `<repo>/runtime/std` automatically).
- [x] **quality-roadmap-and-jmh-ignore** — Add the next quality roadmap tasks to this queue/backlog and ignore generated JMH per-benchmark output directories so shared `main` does not look dirty after local benchmark runs. ✓ Landed 2026-05-30.

## Quality / Contracts / Type System

- [x] **contract-validation-spec** — Spec first: define a shared contract-validation model for OpenAPI + GraphQL drift checks. Cover route/resolver signature ↔ schema compatibility, request/response body validation, typed errors/status codes, profile filtering, imported/overlayed contracts, CLI commands, and CI/test strategy. ✓ Landed 2026-05-30: [`docs/contract-validation.md`](docs/contract-validation.md) specifies the shared IR, diagnostics, OpenAPI/GraphQL checks, profiles/overlays/imports, planned CLI, warning/error/baseline policy, implementation phases, tests, and open questions.
- [x] **typer-real-types-roadmap-spec** — Spec first: plan the next tightening pass that reduces `Any` in exported symbols/IR and carries real types through case classes, enums, method return types, generic calls, typed routes, OpenAPI/GraphQL schemas, Dataset/Spark mapping, and plugin metadata. ✓ Landed 2026-05-30: [`docs/typer-real-types-roadmap.md`](docs/typer-real-types-roadmap.md) defines canonical type evidence, schema-oriented type shapes, exported/interface metadata, local inference expansion, route/remote/client evidence, OpenAPI/GraphQL consumers, typed-data/Dataset/Spark convergence, plugin metadata, phases, tests, and open questions.
- [x] **perf-regression-guard** — Add a lightweight performance regression workflow: checked-in benchmark manifest/baseline policy, ignored generated artifacts, short opt-in `ssc bench`/JMH smoke command, and docs for when results are informational vs CI-blocking. ✓ Landed 2026-05-30: `ssc bench --smoke` runs a quick interpreter-only corpus smoke with optional `--target-ms/--require-target`; `scripts/perf-smoke.sh --jmh` runs an opt-in short JMH smoke; `bench/perf-manifest.yaml`, `bench/README.md`, `bench/BASELINE.md`, README, docs/performance, and the user guide document the informational-vs-blocking policy; raw runtime/JMH outputs are ignored.
- [x] **cli-main-helper-split-p2** — Behavior-preserving CLI helper extraction: synthetic-request/render helpers → `RenderHelpers.scala`, artifact-info printers → `ArtifactInfoPrinters.scala`. Call sites import `.*` so they stay unqualified; command behavior + registry contracts unchanged. _(landed 2026-05-30)_
- [x] **cli-main-helper-split-p3** _(landed 2026-05-30)_ — Behavior-preserving extraction of the two cleanly-decoupled `Main.scala` clusters: install/script commands → `InstallCommands.scala` (`scriptCommand` + `selfInstallCommand`) and `.ssclib` packaging + compat → `SsclibPackaging.scala` (`packageLib`, `ssclibIrEntryName`, `ssclibInterfaceBytes`, `CompatReport`, `checkSsclibCompat`, `publicSsclibSymbols`, `publicSymbolShapes`, `readSsclibInterfaces`). Both are top-level package-level defs in `scalascript.cli`, so Main's command classes call them unqualified — file-scoped `private def`s widened to `private[cli] def`, bodies byte-identical. `CheckCompatCmd`/`PackageCmd` stay in Main. Main drops 289 lines; `cli/compile` clean; `SsclibPackageCliTest` + `CommandRegistryTest` green (10). The tangled build/compile pipeline (shares the 11-ref `collectImports`) deferred to p4.
- [x] **cli-main-helper-split-p4** _(landed 2026-05-31)_ — Behavior-preserving extraction of the build/compile pipeline cluster from `Main.scala` into `BuildPipeline.scala`: `compileJvmAndCache`, `compileJvmDepInto`, `scjvmHasClassBundle`, `extractDepBundlesForCompile`, `unionDepCapabilities`, `ensureRuntimeArtifact`, `unionDepCapabilitiesJs`, `ensureJsRuntimeArtifact`, `compileJsDepInto`, `collectImports` (the 11-ref shared helper), `stagePrecompiledDepArtifacts`. All relocated as top-level package-level defs in `scalascript.cli`, file-scoped `private def`s widened to `private[cli] def`, bodies byte-identical; `BuildPipeline.scala` imports `RenderHelpers.*` (for `reportCodeBlockParseErrors`). `CliCommand` classes stay in Main and call them unqualified. Main drops 410 lines; `cli/compile` clean; relocation verified byte-identical against origin/main (both files). CLI build/incremental integration suites could not run to completion this session due to machine contention from concurrent sessions' stale JVMs — relied on compile + byte-identity for this pure-relocation refactor.
- [x] **jsgen-split-p1** _(landed 2026-05-30)_ — Behavior-preserving extraction from the 12k-line `runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala`, mirroring the established `JsRuntime*.scala` (preamble strings) + `intrinsics/*.scala` (per-intrinsic codegen) pattern. Phase 1, lowest-risk leaf clusters: (a) large embedded runtime-preamble `String` vals (e.g. `httpTypedRouteClientRuntime`) → `JsRuntimeHttpClient.scala`; (b) self-contained string/quoting helpers (`jsQuote`/`jsStringLit`/`jsTemplateEscape`/`stringBlockTemplate`) → `JsGenStringUtils` object. Emitted JS byte-identical; `JsGenTypedRouteClientTest` 35/35 green. Heavier `genExpr`/`genStat` clusters deferred to a follow-up phase.
- [x] **jsgen-split-p2** _(landed 2026-05-30)_ — Continue the `JsGen.scala` preamble-string extraction (phase 2). Move the self-contained domain runtime-preamble `val`s — `JsRuntimeOptics`, `JsRuntimeSignals`, `JsRuntimeIndexedDb`, `JsRuntimeV14Effects`, `JsRuntimeBrowserPatch` — each to its own `JsRuntime<Name>.scala` (top-level `val` in `package scalascript.codegen`, mirroring `JsRuntimeDataset.scala`); references stay unqualified, no call-site changes. NOTE: `Optics`/`Signals`/`IndexedDb` are operands of the **eager** top-level `val JsRuntime` concat — extraction relies on cross-object lazy init (pure string vals, no cycles); verify via compile + `JsGenTypedRouteClientTest` + node round-trip that emitted JS stays byte-identical. Defer the giant `JsRuntimeAsyncA`/`AsyncB` and the `Part1a–Part2b` core sequence to a follow-up phase.
- [x] **jsgen-split-p3** _(landed 2026-05-30)_ — Phase 3 of the `JsGen.scala` preamble-string extraction. Move the 6 core sequential preamble `val`s — `JsRuntimePart1a`, `JsRuntimePart1b`, `JsRuntimePart1c`, `JsRuntimePart1d`, `JsRuntimePart2a`, `JsRuntimePart2b` — each into its own `JsRuntimePart*.scala` (top-level `val` in `package scalascript.codegen`); references stay unqualified, no call-site changes. All 6 are operands of the eager top-level `val JsRuntime` concat — same proven cross-object-init mechanism as p2 (Optics/Signals/IndexedDb). Verify byte-identical via compile + `JsGenTypedRouteClientTest`/`JsGenStreamsTest`/`JsGenIndexedDbTest`/`JsRuntimeMcpBrowserTest`. Defer the two giant `JsRuntimeAsyncA`/`AsyncB` vals to a final phase (p4).
- [x] **jsgen-split-p4** _(landed 2026-05-30)_ — Final phase of the `JsGen.scala` preamble-string extraction. Move the two giant async-runtime `val`s — `JsRuntimeAsyncA`, `JsRuntimeAsyncB` — each into its own `JsRuntimeAsync*.scala` (top-level `val` in `package scalascript.codegen`). The `lazy val JsRuntimeAsync = AsyncA + AsyncB` combinator stays in `JsGen` (method-time access, safest case). Verify byte-identical via compile + `JsGenStreamsTest`/`JsGenTypedRouteClientTest`/`JsRuntimeMcpBrowserTest`. Completes the preamble-string split; remaining `JsGen.scala` is the actual codegen logic.
- [x] **jvmgen-split-p1** _(landed 2026-05-30)_ — Behavior-preserving preamble-string extraction from the 9.5k-line `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGen.scala` (now the largest codegen file), mirroring the proven `jsgen-split` pattern. Phase 1, lowest-risk pure non-interpolated `stripMargin` runtime-preamble `val`s → dedicated `JvmRuntime*.scala` files in `package scalascript.codegen`: (a) `swingTypedRouteClientRuntime` → `JvmRuntimeSwingClient` (concatenates `TypedJsonCodecRuntime.jvmFacade` mid-string — extracted object keeps the import); (b) `preamble` (~180KB JDK runtime) → `JvmRuntimePreamble`; (c) `mutualTcoRuntime` → `JvmRuntimeMutualTco`. Extract as `object X { val source = … }`; update the 5 reference sites to `X.source`. Verify emitted JVM source byte-identical via compile + JvmGen test suites. Defer string helpers (`escapeStringLit`/`scalaStringLiteral`/`jsLitForClientSql`) and genExpr/genStat clusters to a follow-up phase.
- [x] **jvmgen-split-p2** _(landed 2026-05-30)_ — Phase 2 of the `JvmGen.scala` split: extract the three pure self-contained string-escaping helpers — `escapeStringLit`, `scalaStringLiteral`, `jsLitForClientSql` (all `String => String`, no instance state) — into a `JvmGenStringUtils` object (own file in `package scalascript.codegen`), mirroring the proven `JsGenStringUtils` pattern from jsgen-split-p1. Add `import JvmGenStringUtils.*` so the ~35 call sites stay unqualified. Verify emitted JVM source byte-identical via compile + JvmGen test suites. Defer genExpr/genStat clusters to a follow-up phase.

## GraphQL JS/Node parity (graphql-js)

JVM/interpreter GraphQL is complete (graphql-p1–p13 in `BACKLOG.md §GraphQL`). The
`graphql-js` JS/Node backend baseline (query + mutation + nested resolvers + client)
landed 2026-05-30 under graphql-p2; these bring the JS/Node target to feature parity.
Each is backed by the `graphql` npm package (plus a small extra dep where noted) and
verified with a live `node` round-trip. Details in `BACKLOG.md §"GraphQL JS/Node parity"`.

- [x] **graphql-js-scalars** — Custom scalars on graphql-js (`GraphQL.scalar` → `GraphQLScalarType`), nested object/list output. Mirrors graphql-p6. Node conformance tests. _(landed 2026-05-30)_
- [x] **graphql-js-dataloader** — Per-request DataLoader/batching on graphql-js (`GraphQL.dataLoader`, `_load`/`_batchLoad`, per-request dedup cache). Mirrors graphql-p9. _(landed 2026-05-30)_
- [x] **graphql-js-security** — Security/limits parity (`GraphQL.options`: maxDepth/maxComplexity/maxQueryLength/disableIntrospection) via graphql-js validation rules + body-length guard. Mirrors graphql-p10. _(landed 2026-05-30)_
- [x] **graphql-js-subscriptions** — Subscriptions over WebSocket (`graphql-transport-ws` on `onWebSocket`) + `graphqlSse` text/event-stream. Mirrors graphql-p3/p7/p13. No extra npm dep — buffered SSE + raw-masked WS client built on `graphql` ^16. _(landed 2026-05-30)_
- [x] **graphql-js-federation** — Apollo Federation v2 subgraph (`graphqlSubgraphMount`/`serveSubgraph`, Federation SDL preamble + `_entities`/`__typename`). Mirrors graphql-p12. Pure-SDL, no extra dep. _(landed 2026-05-30)_

## Exact Numerics — v1.64

Spec: `docs/exact-numerics.md`. Adds `BigInt`, `Decimal`/`BigDecimal`, a safe
numeric tower, and a `Money`/`Currency` std library across all three backends.
Required by the `busi` accounting project. Work top-to-bottom; v1.64.1 depends on
the spec, codegen phases depend on the interpreter phases, Money depends on
Decimal landing on every backend.

- [x] **v1.64.0-exact-numerics-spec** — Spec + backlog/work-queue. `docs/exact-numerics.md`. ✓ Landed 2026-05-30.
- [x] **v1.64.1-bigint-interpreter** — `Value.BigIntV`, `BigInt(...)` ctor, arithmetic/methods/tower in `DispatchRuntime`, show/serialize, tests. Spec `§4-5,§7`. ✓ Landed 2026-05-30: `BigIntV(BigInt)` in `Value`, `BigInt(int|string|bigint)` builtin, `+ - * / % < > <= >= == !=` with Int↔BigInt widening, `dispatchBigInt` methods (pow/abs/gcd/signum/isEven/isProbablePrime/min/max/mod/toInt/toLong/toBigInt/toDouble), `Int.toBigInt`, `bi` wire tag in `ValueSerializer`; 9 tests, full interpreter suite green (1117).
- [x] **v1.64.2-decimal-interpreter** — `Value.DecimalV`, construction, exact `+-*`, `divide`/MathContext, `setScale`/rounding, numeric `==` + normalised hashing, serialize, tests. Spec `§4.4-4.7`. ✓ Landed 2026-05-30: `DecimalV(scala.math.BigDecimal)` (value-based `==`/hashCode for free), `Decimal("…")`/`Decimal(unscaled,scale)`/`Decimal(int|bigint)` + `BigDecimal` alias, `RoundingMode.*` constants, arithmetic `+ - * /` and comparisons with Int/BigInt widening, **Decimal⊕Double rejected**, `dispatchDecimal` methods (setScale/round/divide/scale/precision/abs/negate/signum/isZero/min/max/pow/compareTo/toBigInt/toInt/toDouble), `Int.toDecimal`/`BigInt.toDecimal`, `dec` wire tag; 14 tests, full suite green (1131).
- [x] **v1.64.3-numerics-typer** — register types, numeric tower, Decimal↔Double type error, inference. Spec `§4.3`. ✓ Landed 2026-05-30: `SType.BigInt`/`SType.Decimal`, `primitiveOrNamed` maps `BigInt`/`Decimal`/`BigDecimal`, prelude ctors typed to return them, `isCompatible` widening Int⊆BigInt⊆Decimal (Decimal⊄Double), `checkBinaryOp` tower + **Decimal/BigInt ⊕ Double type error**; 11 tests. (3 unrelated core failures — RouteDeriverTest/CrossPlatformSmokeTest/NormalizeRoundTripTest — are pre-existing, reproduce on origin/main: missing `lang/conformance` dir + cross-file fixture.)
- [x] **v1.64.4-numerics-jvm-codegen** — lower to `scala.math.BigInt`/`BigDecimal`, map ops, cross-backend conformance. Spec `§5,§8`. ✓ Landed 2026-05-30: JVM preamble aliases `type/val Decimal = scala.math.BigDecimal` + `val RoundingMode`, conversion/method extensions (toBigInt/toDecimal/isEven/isOdd/negate/isZero/divide), `_bigIntOp`/`_bigDecOp` + `_binOp` BigInt/BigDecimal cases with Int/Long widening, `_show` plain rendering. Conformance test compiles generated `.sc` via scala-cli and asserts byte-identical output to the interpreter (4 tests); full suite green (1136).
- [x] **v1.64.5-numerics-js-codegen** — native `BigInt` + capability-gated `_Decimal` helper, node conformance. Spec `§5,§8`. ✓ Landed 2026-05-30: JS runtime gains `Decimal`/`BigDecimal`/`RoundingMode` + BigInt-backed `_Decimal` (`{u:bigint,s:int}`) with all 8 rounding modes (`_divRound`), `_decAdd/Sub/Mul/Cmp/SetScale/Divide`, and `_arith(op,a,b)` for BigInt/Decimal with Int widening + string-concat priority; JsGen routes `+ - / % < > <= >= == !=` through `_arith` when operands aren't both statically Int (int fast-path preserved); `_dispatch` numeric methods; `_show` renders bigint/Decimal plainly. Node conformance: 6 tests byte-identical to interpreter; full suite green (1142). Note: bare Decimal `/` uses HALF_EVEN≈DECIMAL128 (prefer explicit `divide`); CPS-context arithmetic routing is a follow-up.
- [x] **v1.64.6-money-stdlib** — `runtime/std/money/`: `Currency`/`Money`/`RoundingMode`, configurable currency table, cent-preserving `allocate`, format/parse, tests. Spec `§6`. ✓ Landed 2026-05-30: `runtime/std/money.ssc` — `Currency`/`Money` on `Decimal`, ISO default currency table (USD/EUR/GBP/UAH/PLN/JPY/BHD, overridable), `money`/`moneyOf`/`plus`/`minus`/`times`/`negate`/`compareMoney`/`minorUnits`/`formatMoney`, and cent-preserving `allocate`/`distribute` (largest-remainder: $0.05÷3 → 2,2,1; $100÷3 sums to exactly $100.00). 7 interpreter tests + cross-backend (JVM scala-cli + JS node) byte-identical conformance; full suite green (1152).
- [x] **v1.64.7-numerics-sugar** _(optional)_ — suffix literals `123n`/`12.34m`, oversized-int auto-promotion, `money"…"` interpolator (preprocessor). Spec `§4.2,§7`. ✓ Landed 2026-05-30: `numeric-literals` preprocessor (priority 15) rewrites `123n`→`BigInt("123")`, `12.34m`→`Decimal("12.34")`, and integer literals > Long.MaxValue → `BigInt("…")`, with underscore stripping; skips strings/char-literals/comments, hex/binary, `1.toString`, `t._1`, typed `L/f/d` literals, digit-bearing identifiers. 14 preprocessor unit tests + 4 e2e (interpreter + JVM/JS cross-backend); core green (only 3 pre-existing env failures), interpreter green (1156). `money"…"` interpolator deferred to the Money std lib.

---

## Distributed Runtime — v1.63

- [x] **v1.63.0-distributed-runtime-spec** — Canonical spec and backlog for local/remote/distributed runtime support. Merges `docs/placement-and-remoting.md` and `docs/arch-local-distributed-cluster.md` into `docs/distributed-runtime.md`; keeps operation names like `users.get`, code identity, handler/source/behavior registries, worker bundles, cluster CLI/front matter, and dynamic-code roadmap, while adopting `! Async`, `BasicStreamOps`, typed `ActorRef[M]`, `Cluster`, `SeedResolver`, and cluster-aware deploy phases. Updated with `docs/cluster-operations.md` details for token rotation, persistent cluster config, rolling upgrades, multi-region lowering, and HPA/autoscale. ✓ Landed 2026-05-28.

- [x] **v1.63.1-stream-bridge-basic-ops** — Stream bridge and shared safe operators: add `runtime/std/streams-bridge.ssc`, `Source[A].distributed`, `DStream[A].local`, `DStream[A].localBounded`, `BasicStreamOps[F[_]]`, `_dag_sink_local`, and bounded/materialization tests. Spec: `docs/distributed-runtime.md §v1.63.1`. ✓ Landed 2026-05-28.

- [x] **v1.63.2-typed-actors-remote-spawn** — Typed actors and remote spawn: complete `ActorRef[M]`, add `spawnRemote`, `BehaviorRegistry`, `cluster_spawn` / `cluster_spawn_ack`, JVM lowering for `setClusterAuthToken`, and two-node actor tests. Spec: `docs/distributed-runtime.md §v1.63.2`. ✓ Landed 2026-05-28.

- [x] **v1.63.3-cluster-capability-seed-code-identity** — Cluster capability, seed discovery, and code identity: add `Cluster`, `SeedResolver`, `.ssc` / `.sscc` code identity, `cluster:` and registry front matter, `cluster Demo:` lowering, and diagnostics for missing handlers/codecs and code mismatch. Spec: `docs/distributed-runtime.md §v1.63.3`. ✓ Landed 2026-05-28: backend SPI `Cluster` / `SeedResolver` / `CodeIdentity`, ScalaScript `ClusterCapability` / `SeedResolver.staticList` / `clusterOf` / `resolveSeeds` / `codeIdentity` / `assertCodeIdentity`, interpreter coverage for static/DNS/K8s seeds plus Consul diagnostics, typed `cluster:` / registry front matter in AST/IR/`.sscc`, missing registry target/type validation, and top-level `cluster Demo:` lowering.

- [x] **v1.63.4-remote-registries-async-rpc** — Remote registries and async RPC: compile `@remote` / `remote def` / manifest handlers into `RemoteHandlerRegistry`, add `Remote.function[A, B](name)` returning `B ! Async | RemoteCallError`, add `remoteStub[Api]`, and support in-process/HTTP/WS transports with JSON fallback and future `WireCodec`. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28: backend SPI `RemoteHandlerRegistry` / `RemoteHandlerInfo` / `RemoteCallError`, interpreter `remoteHandlers:` registry lowering, `std.remote` + `remote-plugin` for in-process `Remote.function` / `remoteTryCall`, and POST HTTP JSON fallback for `path:` entries. `@remote` / `remote def`, `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain follow-ups.

- [x] **v1.63.4b-remote-sugar-stubs-wire** — Remote RPC follow-ups: lower `@remote` / `remote def` source declarations into `remoteHandlers:`, add `remoteStub[Api]`, make generated calls return `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28 for source `@remote(name = ..., path = ...) def` and simple `remote def echo(...)` lowering into `remoteHandlers:` metadata, with parser validation and example coverage. Remaining pieces split into `v1.63.4c`.

- [x] **v1.63.4c-remote-stubs-async-wire** — Remote RPC stubs and transports: add `remoteStub[Api]`, make generated calls return `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28 for explicit `Remote.http[A, B](url)` / `remoteHttpFunction` POST HTTP JSON fallback client calls with typed `RemoteCallError` results. Remaining typed stubs, async lowering, WebSocket/internal-wire, and binary `WireCodec[A]` split into `v1.63.4d`.

- [x] **v1.63.4d-remote-stubs-async-wire** — Typed remote RPC stubs and binary transports: derive generated typed HTTP client metadata from `path:` remote handlers as the first `remoteStub` bridge, then continue with trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec[A]` negotiation while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28: `RemoteClientDeriver` now converts `path:` remote handlers into generated `RemoteRpc` typed HTTP client metadata, preserving existing explicit clients and reusing JS/JVM typed-route client codegen.

- [x] **v1.63.4e-remote-trait-stubs-wire** — Remote RPC remaining transports: add trait-shaped `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-29 partial runtime stub: `Remote.stub(baseUrl)` / `RemoteStub` now provide path-based HTTP JSON fallback `function`, `call`, and `tryCall` helpers over the same remote HTTP transport. Trait-shaped compile-time `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain split into `v1.63.4f`.

- [x] **v1.63.4f-remote-trait-stubs-wire** — Remote RPC compile-time stubs and binary transports: add trait-shaped `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-29 surface syntax: `remoteStub[Api](baseUrl)` and `Remote.stub[Api](baseUrl)` now accept a forward-compatible API type argument while returning the path-based `RemoteStub` facade. Generated trait methods, async lowering, WS/internal-wire, and binary codec negotiation remain split into `v1.63.4g`.

- [x] **v1.63.4g-remote-trait-methods-wire** — Remote RPC generated trait methods and binary transports: derive callable methods for `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-29: `remoteStub[EchoApi](baseUrl)` and `Remote.stub[EchoApi](baseUrl)` now derive a trait-shaped stub — `StatRuntime` records abstract method names via `nativeFeatureSet`, `EvalRuntime` intercepts the type-parameterized call and passes the type name to `RemoteIntrinsics`, which builds an `InstanceV` with per-method `NativeFnV` entries posting to `{baseUrl}/{methodName}`. Falls back to `RemoteStub` when no trait definition is found. WebSocket/internal-wire transport and binary `WireCodec[A]` remain deferred.

- [x] **v1.63.5-cluster-runner-worker-bundles** — Cluster runner and worker bundles: `ssc cluster run/package/status/handlers/stop`, worker bundle packaging with code identity and registry metadata, roles, advertised URLs, auth-token wiring, deploy-target integration, and two-local-process smoke tests. Spec: `docs/distributed-runtime.md §v1.63.5`. ✓ Landed 2026-05-29: `ssc cluster run` sets SSC_CLUSTER_ROLE/SSC_NODE_ID/SSC_BIND/SSC_JOIN_SEEDS/SSC_CLUSTER_TOKEN env vars and delegates to `ssc run`; `ssc cluster package` zips source + `manifest.json` (SHA-256 code identity + registry metadata); `ssc cluster handlers` GETs `/_ssc-cluster/handlers`; `ssc cluster stop` POSTs to drain then step-down. `GET /_ssc-cluster/handlers` route registered on `startNode` (interpreter + JVM). Fixed pre-existing `DapSession.scala` exhaustivity warning (Value.OptionV(None) case).

- [x] **v1.63.6-stream-actor-placement-adapters** — Stream and actor placement adapters: `Source[A].remote`, `RemoteSource[A].local`, `RemoteSource[A].distributed`, `DStream[A].remote`, WebSocket remote streams with JSON fallback, SSE constraints, local proxy actors, and router/sharded/role actor groups. Spec: `docs/distributed-runtime.md §v1.63.6`. ✓ Landed 2026-05-29: `Source.remote(name, policy)` / `RemoteSource.local(buffer)` / `RemoteSource.distributed` / `DStream.remote(name)` in streams-bridge.ssc + StreamsIntrinsics + DStreamsIntrinsics + DispatchRuntime bridges; `ActorGroup.router/sharded/role`, `actorGroupAdd/Remove/Members/Tell`, `proxyActor` in actors.ssc + ActorGlobals + ActorInterp (proxyFlush drain pattern); `RemoteStreamPolicy`, `SseOverflowPolicy`, `RoutingPolicy` companion constants in BuiltinsRuntime. 10 tests (5 stream + 5 actor).

- [x] **v1.63.7-cluster-aware-deploy-ops** — Cluster-aware deployment and operations: `ClusterTarget`, K8s StatefulSet/headless Service/token Secret, `rotateClusterToken` with `token_rotate` / `token_rotate_ack` and quorum overlap, `clusterConfigSet/Get` persistence through `StateBackend`, `Deploy.rollingCluster`, `FaultToleranceConfig` multi-region lowering, K8s HPA/autoscale emission through `HpaConfig`, and Docker Compose target. Spec: `docs/distributed-runtime.md §v1.63.7`. ✓ Landed 2026-05-29.

- [x] **v1.63.8-dynamic-code-ops-hardening** — Dynamic code shipping and ops hardening: signed worker bundles, remote artifact cache, dependency verification, sandbox/resource policy, audit log, unload/rollback, mixed-version placement after wire/schema compatibility, metrics/tracing, circuit breakers, load shedding, and production cookbook. Spec: `docs/distributed-runtime.md §v1.63.8`. ✓ Landed 2026-05-29.

## Distributed Wire Protocol — v1.62

- [x] **v1.62.0-distributed-wire-spec** — Spec and backlog for an opt-in internal distributed wire layer across actors, cluster control plane, Dataset/MapReduce, native DStream runner, typed route clients/RPC, WebSocket subscriptions, and object sync. Covers JSON fallback plus MsgPack and CBOR profiles, JS/browser support, same-version-only initial binary compatibility, negotiation, security, compression, resource limits, observability, and phases v1.62.1–v1.62.8. Spec: `docs/distributed-wire-protocol.md`. ✓ Landed 2026-05-28.

- [x] **v1.62.1-wire-core** — Shared wire runtime: add `WireValue`, `WireEnvelope`, `WireCodec[A]`, decode errors, resource limits, negotiation/config types, front-matter/CLI parsing for `wire:`, JSON/MsgPack/CBOR codec profiles on JVM/interpreter and JS/browser, and golden cross-format vectors. Spec: `docs/distributed-wire-protocol.md §Phase 1`. ✓ Landed 2026-05-28.

- [x] **v1.62.2-actors-binary-ws** — Actor cluster binary WebSocket: `ssc-actors-v2.<format>` subprotocols, binary WS frames for actor user messages and cluster control envelopes (registry, heartbeat, gossip, leader election, pub/sub, config, drain, metrics, phi vectors), preserving JSON `ssc-actors-v1` fallback. Spec: `docs/distributed-wire-protocol.md §Phase 2`. ✓ Landed 2026-05-28.

- [x] **v1.62.3-typed-rpc-binary** — Typed route clients/RPC binary negotiation: generated HTTP `Accept`/`Content-Type` for MsgPack/CBOR wire payloads, JSON fallback, 406/415 errors, binary WS subscription frames, and SSE text/base64 fallback. Spec: `docs/distributed-wire-protocol.md §Phase 3`. ✓ Landed 2026-05-29.

- [x] **v1.62.4-dataset-binary-partitions** — Distributed Dataset/MapReduce binary partitions and shuffle: route `DatasetWirePartition` through `WireCodec[A]`, chunk large partitions, and run distributed map/shuffle conformance under JSON, MsgPack, and CBOR. Spec: `docs/distributed-wire-protocol.md §Phase 4`. ✓ Landed 2026-05-29: `DatasetWire` wraps `DatasetWirePartition` in `WireEnvelope(protocol = "dataset")`, encodes/decodes JSON, MsgPack, and CBOR envelopes, preserves JSON numbers exactly, and chunks/reassembles large partitions at element boundaries with `chunk-id` / `chunk-index` / `chunk-count` headers. Runner transport selection split into `v1.62.4b`.

- [x] **v1.62.4b-dataset-runner-binary-wire** — Distributed Dataset/MapReduce runner binary transport selection: wire `runDistributedWire` / `runDistributedShuffleWire` actor messages to use `DatasetWire` envelopes when `wire.dataset` selects MsgPack/CBOR, retain JSON fallback, and add distributed map/shuffle conformance under JSON, MsgPack, and CBOR. Spec: `docs/distributed-wire-protocol.md §Phase 4`. ✓ Landed 2026-05-29 partial runner boundary: `DistributedDataset.run/runShuffle` now accept `wireFormat` and round-trip input/output `DatasetWirePartition` values through `DatasetWire` for MsgPack/CBOR conformance while retaining existing actor messages. Direct binary actor frame selection split into `v1.62.4c`.

- [x] **v1.62.4c-dataset-actor-binary-frames** — Distributed Dataset/MapReduce direct binary actor frames: send `DatasetWire` envelope bytes in runner worker messages when `wire.dataset` selects MsgPack/CBOR, retain JSON object fallback, and add local actor map/shuffle conformance under all three formats. Spec: `docs/distributed-wire-protocol.md §Phase 4`. ✓ Landed 2026-05-29: `runDistributedWire`, `runDistributedShuffleWire`, and `DistributedDataset.run/runShuffle` now route non-JSON `wireFormat` through `DatasetWire` binary actor frames for partition, shuffle-bucket, and key-result messages while keeping JSON object fallback.

- [x] **v1.62.5-dstream-native-wire** — `DStreamMsg` (7 kinds) + `WireCodec` + `DStreamEnvelope`; `TriggerKind` enum; JSON/MsgPack/CBOR round-trips; 58 tests. ✓ Landed 2026-05-29.

- [x] **v1.62.6-object-sync-binary** — `ObjectSyncMsg` (4 kinds) + value types (`SyncChange`/`SyncMutation`/`SyncResult`/`SyncConflict`) + `ObjectSyncEnvelope`; JSON/MsgPack/CBOR; 31 tests. ✓ Landed 2026-05-29.

- [x] **v1.62.7-wire-security-ops** — `WireIntegrity` (HMAC-SHA256), `WireCompression` (gzip), `WireSession`+`WireReplayWindow` (sequence+replay), `WireTlsConfig`, `WireMetrics`+`WireDebug`; 37 tests. ✓ Landed 2026-05-29.

- [x] **v1.62.8-wire-compatibility** — `WireSchemaId` (SHA-256 hash), `WireSchemaRegistry` (directional evolution), `WireCompatibilityGuard`, `WireGoldenVectorRegistry`; additive-field forward-compat verified; 21 tests. ✓ Landed 2026-05-29.

## Architecture & Extensibility — queued

Queued from `BACKLOG.md §Architecture & Extensibility Roadmap`. Official
centralized publication targets such as Maven Central and sbt Plugin Portal are
intentionally omitted from this queue and remain deferred in `BACKLOG.md`.
ScalaScript's own registry work stays queued.

### Theme C — Distribution ecosystem

- [x] **arch-distribution-p1** — `DepResolver` SPI + `GithubReleaseResolver`: refactor `ImportResolver` into a pluggable registry; add `github:user/repo@tag` scheme; `DepCache` with sha256 pin; tests against mock GitHub API. Spec: `docs/arch-distribution.md §5 Phase 1`. ✓ Landed 2026-05-29: `DepResolver`/`DepSpec`, content-addressed `DepCache`, built-in `GithubReleaseResolver`, `ImportResolver` dispatch for `github:` plus `sha256:` suffix pins, and mock GitHub API coverage.

- [x] **arch-distribution-p2** — Coursier wiring + JitPack: `MavenDepResolver` using Coursier for `dep:` scheme; `JitpackResolver` as thin Coursier repo wrapper; tests with embedded local Maven fixture. Spec: `docs/arch-distribution.md §5 Phase 2`. ✓ Landed 2026-05-29: Maven-shaped `dep:` coordinates dispatch to Coursier command wiring, legacy `dep:org/name:version` remains on dep-sources, `jitpack:` enables the JitPack repository, and fake-Coursier tests cover a local Maven-layout fixture.

- [x] **arch-distribution-p4** — Community plugin starter template: `templates/plugin/` with GitHub Actions release workflow; `ssc new --template plugin`; new `docs/community-plugins.md`. Spec: `docs/arch-distribution.md §5 Phase 4`. ✓ Landed 2026-05-29: bundled plugin template resources, `NewProject` scaffolder, `ssc new <name> --template plugin`, release workflow, community plugin guide, and CLI template unit test.

### Theme D — sbt-scalascript plugin completion

- [x] **arch-sbt-plugin-p1** — Source convention + `sscCompile`: `sscSourceDirectories` setting; `sscCompile` task forks `ssc build`; wire into `Compile / compile`; scripted test: `sbt compile` compiles `.ssc` files. Spec: `docs/arch-sbt-plugin.md §5 Phase 1`. ✓ Landed 2026-05-29: added `SscRunner`, `sscSourceDirectories`, `sscBackend`, `sscExtraArgs`, config-scoped `sscArtifactDir`, `Compile / sscCompile`, and `Compile / compile` wiring; scripted `compile-sources` verifies `sbt compile` invokes `ssc build --incremental`.

- [x] **arch-sbt-plugin-p2** — `sscLink` + `packageBin`: `sscLink` task forks `ssc link`; wire into `Compile / packageBin`; scripted test: `sbt package` produces runnable JAR. Spec: `docs/arch-sbt-plugin.md §5 Phase 2`. ✓ Landed 2026-05-29: added `sscLinkedJar`, `Compile / sscLink`, link command wiring through `SscRunner`, skip behavior for projects without `.ssc` artifacts, and scripted `package-link` coverage for `sbt package`.

- [x] **arch-sbt-plugin-p3** — Test integration: `SscTestFramework`; `sscTest` forks `ssc test --output-format junit-xml`; JUnit XML parsing to sbt `TestResult`; scripted test: `sbt test` discovers and runs `.ssc` tests. Spec: `docs/arch-sbt-plugin.md §5 Phase 3`. ✓ Landed 2026-05-29: added `sscTestResultsDir`, `Test / sscTest`, JUnit XML parsing in `SscTestFramework`, `Test / test` dependency wiring, and scripted `test-integration` coverage for `sbt test`.

- [x] **arch-sbt-plugin-p4** — REPL / Run / Watch + BSP wiring: `sscRepl`, `sscRun`, `sscWatch` tasks; `BspIntegration` emits `.bsp/scalascript.json` for Metals/IntelliJ. Spec: `docs/arch-sbt-plugin.md §5 Phase 4`. ✓ Landed 2026-05-29: added interactive `SscRunner`, `sscRepl`, `sscRun`, `sscWatch`, `sscBspSetup`, `BspIntegration`, and scripted `dev-tools` coverage for command wiring plus BSP file emission.

### Theme E — `ssc new` + standalone installation

- [x] **arch-ssc-new-p1** — `ssc new` subcommand + `app`/`lib` templates + Coursier channel: `NewProject.scala` in CLI; `app`, `lib` templates bundled in `ssc.jar`; Coursier channel JSON at `releases.scalascript.io`; `sbt cli/assembly` fat JAR. Spec: `docs/arch-ssc-new.md §5 Phase 1`. ✓ Landed 2026-05-29: changed `ssc new` default template to `app`, added bundled `app` and `lib` templates, added `releases/coursier.json`, documented the existing `cli/assembly` fat JAR path, expanded `NewProjectTest`, and fixed fresh `pluginApi` and `PluginSpec` Scala 3.8.3/sbt compatibility blockers found while verifying the CLI module.

- [x] **arch-ssc-new-p2** — Additional templates + Homebrew tap + curl installer: `plugin`, `dsl`, `web-app`, `wasm-app` templates; Homebrew tap formula; `curl | sh` installer; `README.md` Getting Started updated. Spec: `docs/arch-ssc-new.md §5 Phase 2`. ✓ Landed 2026-05-29: added bundled `dsl`, `web-app`, and `wasm-app` templates (`plugin` already existed), repo-local Homebrew formula source, `releases/install.sh`, documentation updates, and `NewProjectTest` coverage.

- [x] **arch-ssc-new-p3** — Standalone docs update: `docs/getting-started-standalone.md`; `docs/community-plugins.md`; `docs/user-guide.md §Installation` updated; `install.sh` gets `--dev` flag. Spec: `docs/arch-ssc-new.md §5 Phase 3`. ✓ Landed 2026-05-29: added standalone getting-started guide, updated user guide and community plugin docs, and made root `install.sh` developer-only via `--dev` with standalone install guidance by default.

### Theme B — Build-time registry consolidation

- [x] **arch-build-registry-p1** — `PluginSpec` in `build.sbt`: introduce `PluginSpec` case class; migrate all plugins; compute CLI deps, installBin, pluginPkgs, aggregate, and pluginTests from it. Spec: `docs/arch-build-registry.md §5 Phase 1`.

- [x] **arch-build-registry-p2** — Runtime `PluginRegistry` unification: new `PluginRegistry` trait in `backend/spi`; `BackendRegistry` implements it; `PluginManifest`/`SubprocessBackend` to `SubprocessPlugin`; `LocalRegistry` absorbed into `RemotePluginInstaller`. Spec: `docs/arch-build-registry.md §5 Phase 2`. ✓ Landed 2026-05-29: `BackendRegistry` now implements the facade, classpath/sscpkg/subprocess/remote sources share install entry points, and CLI/`pkg:` installs reuse `RemotePluginInstaller`.

### Theme A — Stable Plugin SPI

- [x] **arch-stable-spi-p1** — `scalascript-plugin-api` module: new sbt subproject; `PluginValue`, `PluginError`, `PluginComputation`, `JsonCodec`, and `PluginContext` capability re-export; existing plugins add dependency. Spec: `docs/arch-stable-spi.md §5 Phase 1`.

- [x] **arch-stable-spi-p2** — Capability decomposition + 3 showcase plugins: `HttpCap`, `WsCap`, `DbCap`, `StorageCap`, `ValidateCap`, `MountCap`; typed `NativeImpl.eval`; `LegacyNativeContext` shim; migrate `json-plugin`, `http-plugin`, `auth-plugin`. Spec: `docs/arch-stable-spi.md §5 Phase 2`. ✓ Landed 2026-05-29: added capability traits, `LegacyNativeContext`, `PluginNative.eval`, representative typed-bridge intrinsics in json/http/auth plugins, and fixed auth `verifyPassword`.

- [x] **arch-stable-spi-p3** — Full migration of all `*Intrinsics.scala`: remove `LegacyNativeContext`; delete `isStdPluginInterpreterTest` filter; CI classpath check rejects `scalascript/interpreter/` in plugin subprojects. Spec: `docs/arch-stable-spi.md §5 Phase 3`. ✅ Landed 2026-05-29: added `RemoteCap` + `evalLegacy` to `PluginApi`; migrated all 16 `*Intrinsics.scala` from `NativeImpl` to `PluginNative.evalLegacy`; fixed oauth/oidc helper method signatures to capability traits; moved 59 plugin test files to `runtime/backend/interpreter-plugin-tests/`; deleted `isStdPluginInterpreterTest` band-aid from `build.sbt`; added classpath boundary test.

### Theme F — DSL platform hooks

- [x] **arch-dsl-hooks-p1** — `InterpolatorRegistry` + first migration: `InterpolatorImpl` trait; `Backend.interpolators` field; Typer / EvalRuntime / JvmGen / JsGen / CapabilityCheck consult registry; migrate `json"..."` and `html"..."`. Spec: `docs/arch-dsl-hooks.md §6 Phase 1`. ✓ Landed 2026-05-29: `InterpolatorImpl` SPI trait in `runtime/backend/spi`; `InterpolatorRegistry` (TrieMap, `register`/`lookup`/`all`/`registerFrom`) in `lang/core/compiler/plugin` with built-in `HtmlInterpolator` + `CssInterpolator` pre-registered; `Backend.interpolators: List[InterpolatorImpl]` default `Nil`; Typer fallthrough to `registry.lookup(p).map(_.returnTypeName)`; JvmGen `blockContainsRegisteredInterpolator` + `termContainsRegisteredInterpolator` detectors + `jvmEmit` dispatch; JsGen both direct and CPS paths dispatch through `jsEmit`; CapabilityCheck scans source for registered interpolator prefixes and gates on `requiredFeatures`; 18 tests (11 registry+typer, 7 codegen).

- [x] **arch-dsl-hooks-p2** — `PreprocessorRegistry`: `Preprocessor` trait; `PreprocessorRegistry`; five existing preprocessors converted to registered instances; `Parser.parseScalaWithDiagnostic` uses it. Spec: `docs/arch-dsl-hooks.md §6 Phase 2`. ✓ Landed 2026-05-29: `Preprocessor` SPI trait in `runtime/backend/spi` (`name`, `priority`, `apply`); `PreprocessorRegistry` in `lang/core/parser` (TrieMap, `register`/`lookup`/`all`/`applyAll`/`registerFrom`); all 6 built-in preprocessors (`inline-imports` p10, `list-literals` p20, `slash-imports` p30, `remote-defs` p40, `effects` p50, `extern` p60) pre-registered via `private[parser]` method references; `Parser.preprocessForScala` replaced with `PreprocessorRegistry.applyAll`; `Backend.preprocessors: List[Preprocessor]` default `Nil`; 10 tests.

- [x] **arch-dsl-hooks-p3** — `SourceLanguage` built-in migration: `html`, `css`, `sql`, `xml`, `javascript` fenced tags become `SourceLanguagePlugin` implementations; `Lang.scala` routing removed. Spec: `docs/arch-dsl-hooks.md §6 Phase 3`. ✓ Landed 2026-05-29: bundled SourceLanguage registry now discovers `scala`, `html`, `css`, `javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware `transaction`; `compileBlock` has an attrs-aware overload for fenced attrs; Normalize dispatches through registry with core-only SQL/transaction fallbacks.

- [x] **arch-dsl-hooks-p4** — `InterpolatorCheckRegistry`: `InterpolatorCheck` trait; `MarkupInterpolatorCheck` migrated; plugin `xml-plugin` registers compile-time check. Spec: `docs/arch-dsl-hooks.md §6 Phase 4`. ✓ Landed 2026-05-29: `InterpolatorCheck` SPI trait in `runtime/backend/spi`; `InterpolatorCheckRegistry` with `XmlInterpolatorCheck` pre-registered (uses `PureMarkupCodec` for xml"…" validation); `MarkupInterpolatorCheck` now dispatches all interpolation names through `InterpolatorCheckRegistry.checkAll`; `Backend.interpolatorChecks: List[InterpolatorCheck]` default `Nil`; `BackendRegistry.registerDslHooks` registers checks on backend load; 12 tests (10 MarkupInterpolatorCheck regression + 2 registry).

### Theme H — Library Modularity

- [x] **arch-lib-p1** — `@deprecated` + `@experimental` annotations: new annotations in `Annotation.scala`; typer emits warnings at call sites; `--fatal-warnings` flag; tests. Spec: `docs/arch-library-modularity.md §6 Phase 1`. ✓ Landed 2026-05-29

- [x] **arch-lib-p2** — `@internal` access control: parsed and stored annotations; cross-package check in Typer; source-package diagnostics; per-definition and per-heading granularity. Spec: `docs/arch-library-modularity.md §6 Phase 2`. ✓ Landed 2026-05-29

- [x] **arch-lib-p3** — Namespace collision detection: `ImportResolver` tracks name contributions per import; warning on collision; `--strict-namespaces`; qualified import syntax `[Name from alias](dep:...)`. Spec: `docs/arch-library-modularity.md §6 Phase 3`. ✓ Landed 2026-05-29: `NamespaceCollision` detection in `InterfaceScope.detectCollisions`; `[Name from Alias]` qualified import syntax in parser; `strictNamespaces` Typer param; `--strict-namespaces` CLI flag; 12 tests.

- [x] **arch-lib-p4** — `ssclib` format + `ssc package --lib`: `SsclibManifest` YAML schema; `.ssclib` ZIP format; `ssc package --lib`; `ImportResolver` unpacks archives. Spec: `docs/arch-library-modularity.md §6 Phase 4`. ✓ Landed 2026-05-29: `SsclibManifest` case class with `parseString`/`toYaml`; `ImportResolver` resolves `dep:` to `.ssclib` archives (extracts to `~/.cache/scalascript/libs/`); `ssc package --lib` CLI command with `--manifest`/`--output` flags; `PluginSpec` moved to `project/PluginSpec.scala` (build.sbt scope fix); 11 manifest unit tests.

- [x] **arch-lib-p5** — Transitive deps + lockfile: BFS dependency resolution from `ssclib-manifest.yaml`; conflict resolution; `ssc-lock.yaml`; `ssc update`; `--strict-deps`; cycle detection. Spec: `docs/arch-library-modularity.md §6 Phase 5`. ✓ Landed 2026-05-29: `SemVer` numeric segment comparison; `SscLibLock` (`ssc-lock.yaml` YAML R/W); `ImportResolver.resolveAll` BFS with `ResolutionState` (cycle detection via `visiting` set, latest-wins conflict resolution, strict-deps error mode); `ssc update` CLI command; 19 unit + integration tests (mock HTTP server).

- [x] **arch-lib-p6** — Pre-compiled IR in `.ssclib` + compat check: `ssc package --lib --precompile`; `.scim` in `ir/`; resolver prefers pre-compiled IR; `ssc check-compat old.ssclib new.ssclib`. Spec: `docs/arch-library-modularity.md §6 Phase 6`. ✓ Landed 2026-05-29: `--precompile` writes `ir/*.scim`, `check-compat` reports removed/changed public symbols with source fallback, and CLI tests cover archive layout plus removed-symbol detection.

### Theme I — Package Registry

- [x] **arch-registry-p1** — Registry repository + `packages.yaml` schema: create first-party registry repo; define schema; seed with first-party packages; validation CI. Spec: `docs/arch-registry.md §5 Phase 1`.

- [x] **arch-registry-p2** — `ssc search` / `ssc info` / `ssc add` CLI: cached `RegistryClient`; local ranked search; manifest update; mock-HTTP tests. Spec: `docs/arch-registry.md §5 Phase 2`.

- [x] **arch-registry-p3** — GitHub Pages HTML index: generation script, publish workflow, client-side search, per-package JSON, and `registry.scalascript.io` CNAME. Spec: `docs/arch-registry.md §5 Phase 3`.

- [x] **arch-registry-p4** — Private registry support: `registry.url` config; `--registry <url>` CLI flag; enterprise internal mirror documentation. Spec: `docs/arch-registry.md §5 Phase 4`.

### Theme J — Lightweight FFI

- [x] **arch-ffi-p1** — `@jvm("expr")` annotation + JVM codegen: inline JVM expression bodies, argument substitution, capability checks, example, and tests. Spec: `docs/arch-ffi.md §6 Phase 1`. ✓ Landed 2026-05-29

- [x] **arch-ffi-p2** — `@js("expr")` codegen + interpreter behaviour: JS inline bodies; `@interpreterUnsupported`; cross-backend parity tests. Spec: `docs/arch-ffi.md §6 Phase 2`. ✓ Landed 2026-05-29

- [x] **arch-ffi-p3** — `jvm/glue.jar` in `.ssclib` + `ssc package --lib --jvm-glue`: manifest glue fields, JVM classpath injection, package CLI flag, and glue fixture integration test. Spec: `docs/arch-ffi.md §6 Phase 3`. Prerequisite: `arch-lib-p4`. ✓ Landed 2026-05-29: `SsclibManifest` extended with `glueJvm`/`glueJs` optional fields; `parseString` reads `glue.jvm`/`glue.js` from nested YAML map; `toYaml` emits `glue:` section when non-empty; `GlueClasspathRegistry` (TrieMap-backed, `addJar`/`contains`/`jars`/`clear`); `ImportResolver.extractSsclib` calls `addGlueJarToClasspath` when `glue.jvm` is declared (wires into thread context `URLClassLoader` when possible, always adds to `GlueClasspathRegistry`); `ssc package --lib` gains `--jvm-glue <jar>` and `--js-glue <js>` flags, packing the jars at `jvm/glue.jar` / `js/glue.js` in the archive; 8 unit + integration tests.

- [x] **arch-ffi-p4** — `js/glue.js` preamble injection + `META-INF/services` in glue.jar: JS glue injection, service loading into `BackendRegistry`, and end-to-end JS glue test. Spec: `docs/arch-ffi.md §6 Phase 4`. ✓ Landed 2026-05-29: `GlueJsPreambleRegistry` (TrieMap-backed, `addPreamble`/`contains`/`preambles`/`isEmpty`/`clear`); `ImportResolver.extractSsclib` registers `js/glue.js` content in `GlueJsPreambleRegistry` when `manifest.glueJs` is declared; `JsGen.generateRuntime` prepends all registered glue preambles before standard runtime parts; `addGlueJarToClasspath` calls `BackendRegistry.addPluginJar(jarPath)` for `META-INF/services` discovery; 10 unit + integration tests.

### Theme G — Metaprogramming v2.x

- [x] **arch-meta-v2-p3** — Cross-module `inline` expansion: IR-level inlining in `ssc link`; requires plugin-author demand and stable SPI/distribution foundations. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 3`.

- [x] **arch-meta-v2-p4** — Restricted `QuotedMacro[A]` surface: `Expr[A].asValue`, `Expr[A].asTerm`, quoting, `MacroImpl` IR node, expansion at link time. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`. ✓ Landed 2026-05-29: first restricted slice adds parser preprocessing for `${ impl('x) }` and `'{ $x + ... }`, `.scim` `MacroImplRef` metadata, `MacroImpl` IR, and link-time expansion for direct quoted-expression bodies; runtime/interpreter parity landed in `arch-meta-v2-p4b`.

- [x] **arch-meta-v2-p5** — Full `Mirror`-based user typeclass derivation: `scalascript.reflect.Mirror`; inline match on `Mirror.Product/Sum` for arbitrary user typeclasses. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 5`. ✓ Landed 2026-05-29: interpreter/runtime slice registers summon-able `Mirror.Of/ProductOf/SumOf`, exposes `Mirror.of[T]`, richer product/sum metadata, and supports user-defined `derived(m: Mirror)` typeclasses; source-level inline match and broader backend conformance remain planned follow-ups.

- [x] **arch-meta-v2-p4b** — Restricted quoted macro runtime parity: implement the next `Expr[A].asValue` / `Expr[A].asTerm` evaluation slice for direct quoted macro bodies and document the supported boundary. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`. ✓ Landed 2026-05-29: parser quote/splice helpers now carry runtime values, interpreter registers lightweight `Expr` / `QuotedContext` plus macro helper intrinsics, direct quoted macro bodies run under `ssc run`, and `Expr.asValue` / `Expr.asTerm` expose the restricted runtime metadata.

- [x] **arch-meta-v2-p4c** — Restricted quoted macro diagnostics: reject unsupported macro entrypoints and quoted macro bodies with explicit parser/interface/linker/interpreter diagnostics instead of silent non-expansion. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`. ✓ Landed 2026-05-29: `${ impl(x) }` now lowers to an explicit diagnostic helper requiring quoted args such as `${ impl('x) }`; interpreter reports `quoted macro error: ...`; linker rejects non-quoted macro bodies with a direct restricted-subset message.

- [x] **arch-meta-v2-p4d** — Restricted quoted macro richer unsupported-body diagnostics: classify common unsupported implementation bodies (`Expr(...)`, `x.asValue match`, nested quotes/splices) and report targeted guidance while preserving the direct quoted-expression happy path. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`. ✓ Landed 2026-05-29: linker unsupported-body diagnostics now classify `Expr.asValue match`, `Expr(...)` construction, nested/non-top-level quotes, and splices outside direct quote bodies with targeted guidance while preserving direct quoted-expression expansion.

## Government Interaction — v1.59 Bureau

- [x] **v1.59.1-bureau-core** — `gov/bureau-core/` module: all SPI types (`CountryCode` opaque type + constants, `LegalForm` enum, `TaxIdentifier`/`TaxIdType`, `BusinessEntity`, `GovDomain`, `SubmissionResult`/`SubmissionStatus`/`GovError`); domain provider traits (`CountryProvider`, `FiscalProvider`, `SocialProvider`, `RegistryProvider`, `CustomsProvider`, `StatisticsProvider`, `EnvProvider`); shared fiscal/social/registry types (`FiscalInvoice`+`Currency`+`ExchangeRate`, `TaxDeclaration`, `AuditFile`, `ContributionDeclaration`, `EmployeeRecord`, `PaymentReference`, `BusinessRecord`, `VatPayerStatus`); `BureauError` sealed hierarchy (7 cases). `BureauCoreTest`: type construction, `BusinessEntity.requireTaxId`, `SubmissionStatus`, `BureauError` hierarchy. Spec: `docs/bureau.md §3–§7`.

- [x] **v1.59.2-bureau-signing** — `gov/bureau-signing/` module: `SigningProvider` SPI (`sign(data, format): Future[SignatureResult]`, `getCertificateInfo`, `getSupportedFormats`); `SignatureFormat` enum (PAdES, XAdES, CAdES); `CertificateInfo`; `PfxSigningProvider` (.pfx/.p12 via `java.security.KeyStore`; PKCS#12 loading; SHA-256 with RSA; `SIGNING_PFX_PATH`+`SIGNING_PFX_PASSWORD` env); `MockSigningProvider` (always succeeds, configurable cert info); `SigningError` ADT. `PfxSigningTest`: sign+verify round-trip with generated test `.pfx`; `MockSigningTest`: always succeeds. Spec: `docs/bureau.md §8`.

- [x] **v1.59.3-bureau-pl-registry** — `gov/bureau-pl-registry/` module: `PlRegistryProvider` implementing `RegistryProvider` for `CountryCode.PL`; 4 adapters: CEIDG (`/api/interoperability/ceidg/v2/firma/pkd` REST, API-Key header), REGON (GUS BIR1 SOAP/REST, API key, `DaneSzukajPodmioty` query), Biała Lista (`/api/search/nip` JSON, API-Key), KRS (`/KRS/application/json/pl/search/...` REST, public); injectable HTTP for tests; ServiceLoader. `PlRegistryTest`: JSON parsing for each adapter, not-found paths, error handling (40+ tests). Spec: `docs/bureau.md §5.4`.

- [x] **v1.59.4-bureau-pl-fiscal-ksef** — `gov/bureau-pl-fiscal/` module (KSeF part): `PlKsefAdapter` — QES session auth (challenge→signed→session token), FA_VAT XML invoice submit (`POST /api/online/Invoice/Send`), status poll (`GET /api/online/Invoice/Status`), invoice fetch (`GET /api/online/Invoice/Get`), query invoices (`POST /api/online/Query/Invoice/sync`); `KsefInvoice` XML builder using `xml"..."` interpolator; `KsefSessionStore` (in-memory token cache, TTL 24h). `PlKsefTest`: auth challenge flow, submit+poll, fetch, query, API errors (401/429/503). Spec: `docs/bureau.md §5.1`.

- [x] **v1.59.5-bureau-pl-fiscal-declarations** — `gov/bureau-pl-fiscal/` (declaration part): `PlDeclarationAdapter` — JPK_VAT7M XML build+submit+poll (`POST /api/v3/deklaracje`), JPK_FA (sales invoice audit), CIT-8 + PIT-36 via e-Deklaracje SOAP (`POST https://e-deklaracje.mf.gov.pl/rejestracja`); schema validation hook via `JvmMarkupCodec.validate`; UPO receipt parsing. `PlDeclarationTest`: JPK_VAT7M submit+poll, schema validation, async rejection. Spec: `docs/bureau.md §5.2–§5.3`.

- [x] **v1.59.6-bureau-pl-social** — `gov/bureau-pl-social/` module: `PlZusAdapter` implementing `SocialProvider` for `CountryCode.PL`; KEDU XML declaration builder (DRA, RCA, RSA); ZUS PUE REST submission (`POST /services/Dokumenty`); NRB payment-reference generator (28-digit, ISO 7064 MOD97 check digit, ZUS NRB formula); `calculateContributions(base, type)` — replicate ZUS formula for emerytalne/rentowe/chorobowe/wypadkowe/FP/FGŚP rates. `PlZusTest`: DRA declaration, NRB reference generation (formula verified against ZUS examples), contributions calculation (25+ tests). Spec: `docs/bureau.md §5.5`.

- [x] **v1.59.7-bureau-eu** — `gov/bureau-eu/` module: `EuRegistryProvider` implementing `RegistryProvider` for EU-level queries; VIES VAT verification (`EuViesAdapter` — `checkVat` SOAP call to `http://ec.europa.eu/taxation_customs/vies/services/checkVatService`); `bureau-pl` aggregate module wiring all PL adapters; ServiceLoader for both. `ViesTest`: VAT number verify (valid, invalid, service down). Spec: `docs/bureau.md §6`.

- [x] **v1.59.8-bureau-scheduler** — `gov/bureau-scheduler/` module: `SimpleScheduler` (single-threaded `ScheduledExecutorService`-backed); job types `RecurringJob`/`OneTimeJob`/`PeriodJob` (file annual/monthly); `BureauCalendar` (Polish business day calendar for 2024–2026, Easter algorithm); `ScheduledJob` ADT; `onJobComplete`/`onJobFailed` callbacks; `runNow(jobId)` for manual trigger; `disable(jobId)`/`enable(jobId)`. `SchedulerTest`: job scheduling, runNow, callbacks, disable/enable. Spec: `docs/bureau.md §9`.

- [x] **v1.59.9-bureau-mock** — `gov/bureau-mock/` module: `MockBureauProvider` (configurable in-memory `CountryProvider` covering all domain sub-providers); named constructors `MockBureauProvider.poland()` / `.vat()` / `.all()`; `MockFiscalProvider`/`MockSocialProvider`/`MockRegistryProvider` (all named constructors return expected mock values, recorded calls for assertions); integration tests wiring all 7 previous modules end-to-end; `examples/bureau-demo.ssc`; `CHANGELOG.md` + `BACKLOG.md` v1.59 section. `MockProviderTest`: all named constructors (30+ tests). Spec: `docs/bureau.md §12`.

## v1.51 — Streams with Backpressure

- [x] **v1.51.1-streams-source-core** — Missing operators on `Source` in interpreter: `scan(z)(f)` (running aggregate), `Source.tick(durationMillis)` (periodic unit), `Source.unfold(s)(f)` (state-evolving generator), `Source.fromCallback(register)` (push adapter → bounded queue), `cancellable` (returns `(Source, () => Unit)`), `onError(f)` (side-effect on error without recovery); extern declarations in `streams.ssc` for all existing + new combinators; 12+ new tests. Spec: `docs/streams.md §5, §4.2`.

- [x] **v1.51.2-streams-js** — Complete JS `_makeAsyncStream` helper in `JsGen.scala` with terminal ops (`runForeach/runFold/runToList/runDrain`), combining (`merge/zipWith/broadcast/balance/groupBy/mergeSubstreams`), advanced (`scan/buffer/throttle/debounce/mapAsync/recover/mapError`), routing (`to/via`); JS codegen cases for `Source.tick`, `Source.unfold`, `Source.fromCallback`, `Sink.*`, `Flow.*`; 10+ JS codegen tests. Spec: `docs/streams.md §9.2, §9.3`.

- [x] **v1.51.3-streams-flow-sink** — Extended `Flow` constructors: `Flow.fromFunction/filter/concat/take/drop/flatMap/scan/mapAsync/recover/buffer/throttle/debounce`; complete `streams.ssc` extern declarations; 10+ Flow tests. Spec: `docs/streams.md §5.3`.

## Language & Compiler — Spark extensions (new)

- [x] **spark-lakehouse-l4-hudi** — Spark Lakehouse Hudi (L.4): `SparkGen.detectLakehouseFormats` extended to detect `.format("hudi")` → auto-emit `org.apache.hudi:hudi-spark3.5-bundle_2.13:0.15.0` dep + `spark.serializer=KryoSerializer` + `spark.sql.extensions=HoodieSparkSessionExtension` + `spark.sql.catalog.spark_catalog=HoodieCatalog` configs; `DefaultHudiVersion = "0.15.0"` constant; `examples/spark-lakehouse-hudi.ssc` (write/read/upsert Hudi table); 9 codegen tests. Spec: `docs/spark-lakehouse.md §L.4`. (2026-05-27)

## OAuth / Security

- [x] **oauth-dpop** — DPoP (RFC 9449) sender-constrained tokens for the existing OAuth 2.1 AS: `DPoP.verifyProof` (RS256 + ES256, `htm`/`htu` binding, `jti` replay prevention via `InMemoryJtiStore`, `nonce` + `ath` checks); `cnf.jkt` (RFC 7638 JWK thumbprint) injected into issued access tokens when DPoP proof present on `/token`; `token_type: DPoP` on DPoP-bound tokens; `OAuthGuard.check` extended with `requestMethod`/`requestUrl`/`dpopJtiStore` params to validate DPoP proof when `cnf.jkt` is set; `AuthServerConfig.dpopNonceLifetimeSeconds`; script API `oauth.authServer(...)` wires `dpopNonce` config field; 36 tests in `OAuthDPoPTest`. `docs/oauth.md §Spec compliance` updated. (2026-05-28)

- [x] **oauth-par** — PAR (RFC 9126 Pushed Authorization Requests): `POST /par` endpoint (`OAuthRoutes.handlePar`) accepting same params as `/authorize`; returns `request_uri` (urn:ietf:params:oauth:request_uri:<nonce>) + `expires_in`; `/authorize` resolves stored params via `request_uri`; `AuthServerConfig.parRequired` flag rejects direct params when true; single-use (`InMemoryPushedAuthRequestStore.consume`); `PushedAuthRequest`/`PushOutcome`/`PushedAuthRequestStore` types; `AuthServer.pushAuthorizationRequest`; PAR endpoint in AS metadata (`pushed_authorization_request_endpoint`); 27 tests in `OAuthPARTest`. (2026-05-28)

## Payments & Blockchain — v1.58 Compliance Provider

- [x] **v1.58-compliance-provider** — AML/KYC/sanctions compliance provider SPI: `payments/compliance/` SPI module (`ComplianceProvider` trait: `screenAml(entity)/verifyKyc(identity)/checkSanctions(party)/getStatus`; `ComplianceRequest/ComplianceResult/KycResult/SanctionsResult/AmlResult` types; `ComplianceError` sealed hierarchy); `payments/compliance-complyadvantage/` ComplyAdvantage REST v1 adapter (POST `/search`, HMAC-SHA256 webhook, 20+ tests); `payments/compliance-chainalysis/` Chainalysis KYT API adapter (POST `/transfers`, `GET /entities`, 15+ tests); `payments/compliance-mock/` MockComplianceProvider for testing (configurable pass/fail per check type, 20+ tests); 4 sbt subprojects. Spec: `docs/compliance-provider.md`.

## Language & Compiler — Spark extensions

- [x] **spark-lakehouse-l3-iceberg** — Spark Lakehouse Iceberg (L.3): `SparkGen.detectLakehouseFormats` extended to detect `.format("iceberg")` → auto-emit `org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:1.5.2` dep + `spark.sql.extensions` + `spark.sql.catalog.spark_catalog` Iceberg catalog config; `DefaultIcebergVersion = "1.5.2"` constant; `examples/spark-lakehouse-iceberg.ssc` (write/read Iceberg table, time-travel `asOf`, `MERGE INTO`); 6+ codegen tests. Spec: `docs/spark-lakehouse.md §L.3`.

## Frontend & Clients

- [x] **wallets-metamask-js** — Browser x402 MetaMask helper: `x402ClientJs` Scala.js artifact in package `scalascript.x402.client`; `Wallets.metaMask(network): Future[Wallet]` connects through `window.ethereum`, validates `eth_chainId`, signs EIP-712 via `eth_signTypedData_v4`, exposes `Wallets.metaMask(address, network)` for already-connected accounts, rejects CIP-8 on EVM wallets; 7 Node-backed Scala.js tests with stubbed `window.ethereum`. Spec: `BACKLOG.md §x402 — HTTP payment protocol`. ✓ Landed 2026-05-28.

## Language & Compiler — Secret Resolvers (cloud)

- [x] **secret-resolvers-cloud** — Cloud-provider secret resolver plugins: `AwsSmResolver` (AWS Secrets Manager via `software.amazon.awssdk:secretsmanager`, default creds chain, `AWS_REGION`); `GcpSmResolver` (GCP Secret Manager via `com.google.cloud:google-cloud-secretmanager`, ADC, `GOOGLE_CLOUD_PROJECT`); `AzureKvResolver` (Azure Key Vault via `com.azure:azure-security-keyvault-secrets`, `DefaultAzureCredential`). Each as a separate sbt subproject in `backend/sql-aws/`, `backend/sql-gcp/`, `backend/sql-azure/`; each registers via ServiceLoader; injectable protected methods for testability (11 AWS tests, 12 GCP tests, 12 Azure tests — 35 total). Spec: `docs/secret-resolvers.md §aws-secret §gcp-secret §azure-kv`. (2026-05-27)

## Payments & Blockchain — v1.57 New Payment Rails (APAC + Americas)

- [x] **v1.57.1-payment-rails-australia-npp** — Australia New Payments Platform (NPP/PayID) adapter: `runtime/std/payments-au-npp/` subproject; `AuNppProvider` implementing `BankRailsProvider` for `RailKind.AU_NPP`; PayID proxy resolution (phone/email/ABN to BSB+account via NPP participant gateway); NPP credit transfer via ISO 20022 pacs.008 over REST aggregator; `BankAccount.payid` additive field; `AuNppWebhookReceiver` (HMAC-SHA256 `X-NPP-Signature`; events: `npp.payment.credited/returned`); `AuNppPlugin` ServiceLoader; 35+ tests. Spec: new `docs/payment-rails-apac.md §AU_NPP`.

- [x] **v1.57.2-payment-rails-canada-eft** — Canada Interac e-Transfer + EFT adapter: `runtime/std/payments-ca-eft/` subproject; `CaEftProvider` implementing `BankRailsProvider` for `RailKind.CA_INTERAC` + `RailKind.CA_EFT`; Interac e-Transfer send (push by email/phone) via Interac Hub REST API; EFT (AFT credit/AFT debit, CPA Standard 005 format); `BankAccount.transitNumber` + `BankAccount.institutionNumber` additive fields; webhook events `interac.transfer.sent/reclaimed/expired`; 40+ tests. Spec: `docs/payment-rails-apac.md §CA_INTERAC`.

- [x] **v1.57.3-payment-rails-mexico-spei** — Mexico SPEI (Sistema de Pagos Electrónicos Interbancarios) adapter: `runtime/std/payments-mx-spei/` subproject; `MxSpeiProvider` implementing `BankRailsProvider` for `RailKind.MX_SPEI`; CLABE validation (18-digit control-digit); SPEI transfer via STP/Conekta aggregator REST API; `BankAccount.clabe` additive field; webhook events `spei.transfer.confirmed/rejected/returned`; 30+ tests. Spec: `docs/payment-rails-apac.md §MX_SPEI`. (2026-05-27)

## Payments & Blockchain — v1.57 FX Provider

- [x] **v1.57-fx-provider** — FX rate provider SPI deferred from v1.53/v1.55: `payments/fx/` SPI module with `FxProvider` trait (`getRate(from, to): Future[FxRate]`, `convert(money, to): Future[Money]`, `getRates(pairs): Future[Map[CurrencyPair, FxRate]]`); `FxRate(from, to, rate, mid, bid, ask, timestamp)`; `CurrencyPair` type; two adapters: (A) `EcbFxProvider` — ECB daily reference rates via `eurofxref/eurofxref-daily.xml`, cached with 1h TTL; (B) `OpenExchangeRatesFxProvider` — Open Exchange Rates API v6 (`/api/latest.json`), `APP_ID` env var; `FxPlugin` ServiceLoader; `FxMoneyConverter` standalone utility wrapping `FxProvider`; 76 tests (ECB XML parse, OER JSON parse, mock rate, convert round-trip, TTL expiry, missing-pair error, mock HTTP server). Spec: `docs/traditional-payments.md §FxProvider`. ✓ Landed (2026-05-27)

## Payments & Blockchain — v1.58 Tax Calculation Provider

- [x] **v1.58-tax-provider** — Tax calculation SPI + three adapters: `payments/tax/` SPI module (`TaxProvider` trait: `calculateTax/validateTaxId/getSupportedJurisdictions`; `TaxRequest/TaxQuote/TaxedLineItem/TaxAddress/TaxLineItem/JurisdictionTax/TaxIdValidation/Jurisdiction` types; `TaxError` sealed hierarchy; `TaxMoneyConverter` utility); `payments/tax-stripe/` Stripe Tax Calculations API v1 (form-encoded POST, Bearer Basic auth, idempotency key, format-only `validateTaxId`; 18 tests); `payments/tax-avalara/` Avalara AvaTax REST v2 (JSON POST, Basic accountNumber:licenseKey, `X-Avalara-Client` header, GET `/taxnumbervalidation`; 17 tests); `payments/tax-taxjar/` TaxJar SmartCalcs v2 (JSON POST, Bearer token, decimal amounts; 17 tests). All adapters use injectable HTTP methods for testability; ServiceLoader plugin registration; 4 sbt subprojects (`paymentsTax`, `paymentsTaxStripe`, `paymentsTaxAvalara`, `paymentsTaxJar`). ✓ Landed (2026-05-27)

## Frontend & Clients — Graph Storage Full-Stack Example

- [x] **graph-storage-fullstack** — Graph storage Phase 6: full-stack Electron/React frontend that queries server graph routes and caches selected results locally. New example `examples/graph-fullstack.ssc` with: (1) server graph routes using `graphs:` front-matter (`backend: embedded-tinkergraph`); REST endpoints `GET /api/graph/vertices`, `GET /api/graph/neighbors/:id`, `POST /api/graph/vertex`; (2) React frontend using generated `ApiClient` to call graph routes; client-side `IndexedDb.store[Module]("graph-cache")` caching of query results; cache-first read with background refresh; (3) `examples/graph-fullstack-rdf.ssc` RDF variant with `backend: rdf4j-memory` and SPARQL escape-hatch endpoint `POST /api/graph/sparql`; 20+ tests covering route handler codegen + graph facade + cache wiring. Spec: `docs/graph-storage.md §Phase 6`. (2026-05-27)

## Language & Compiler — API Tooling

- [x] **v1.29-dap-debugger** — Debug Adapter Protocol server (`runtime/backend/dap/`): all 5 phases — Phase 1 (TCP skeleton + initialize/launch/disconnect + `DapProtocol` framing + `DapServer` TCP accept); Phase 2 (`BreakpointRegistry` + `setBreakpoints` + stopped events); Phase 3 (step execution: `next`/`stepIn`/`stepOut`/`continue` + `StepMode` + depth-tracking); Phase 4 (variable inspection: `scopes`/`variables` + variablesReference tree); Phase 5 (stack frames: `stackTrace` + `DebugFrame` + source mapping). `ssc debug file.ssc [--port 5678]` CLI command. 5 test files (framing, phase1 lifecycle, breakpoint, step, variables, stack). Spec: `docs/dap-debugger.md §Phase 1–5`. (2026-05-21)

- [x] **pwa-plugin** — Progressive Web App support (`runtime/std/pwa-plugin/`): `pwa()` extern def in `std/pwa.ssc`; `PwaIntrinsics` registers `GET /manifest.json` (W3C Web App Manifest JSON) + `GET /sw.js` (cache-first service worker); configurable name/shortName/description/themeColor/backgroundColor/display/startUrl/icons/precache; works in interpreter + JVM codegen; `examples/pwa/pwa-demo.ssc`. Spec: `docs/pwa-plugin.md §Phase 1`. (2026-05-21)

- [x] **openapi-export** — Auto-derive OpenAPI 3.1 JSON from registered `route()` calls: `GET /_openapi.json` built-in endpoint (JSON document with paths/methods/path-parameters); `GET /_swagger` Swagger UI HTML page (CDN-linked). `OpenApiRuntime` registers both alongside `/_health`/`/_ready` when `serve`/`serveAsync` is called; walks `RouteRegistry.all`; extracts `:param` segments → `{param}` OpenAPI notation; inspects `FunV.paramTypes` for typed handlers; non-path typed params → query (GET/DELETE) or `requestBody` (POST/PUT/PATCH); type map String→string / Int+Long→integer / Double+Float→number / Boolean→boolean / other→object; `IntrinsicImpl.registerOpenApiDefaults()` hook wired from `HttpIntrinsics`; skips internal `/_*` routes. (2026-05-27)

- [x] **openapi-p2** — JVM codegen + shared OpenAPI generator: `OpenApiGenerator` in backend SPI, interpreter adapter, JVM generated `serve` / `serveAsync` registration of `/_openapi.json` and `/_swagger`, and JVM scala-cli e2e coverage. ✓ Landed 2026-05-29.

- [x] **openapi-p2b** — Response type metadata propagation for OpenAPI: typed front-matter/generated JVM route registrations now carry non-`Any` `apiClients:` response type metadata into `OpenApiRoute.responseType`; raw `route(...)` handlers keep the safe generic `200 OK` fallback. Spec: `docs/openapi.md §5 Phase 2 follow-up`. ✓ Landed 2026-05-29.

## Language & Compiler — Spark extensions

- [x] **spark-streaming-f2-f4** — Spark Structured Streaming phases F.2–F.4: (F.2) `SparkGen.detectStreaming`, `awaitTermination()` shim, `examples/spark-streaming-rate-console.ssc`, 3+ codegen tests; (F.3) file source/sink + checkpointing comment emit, `examples/spark-streaming-file-parquet.ssc`; (F.4) `.format("kafka")` detection → auto-emit `spark-sql-kafka-0-10_2.13` dep, `examples/spark-streaming-kafka.ssc`. Smoke tests gated by `RUN_SPARK_INTEGRATION`/`RUN_SPARK_KAFKA`. Spec: `docs/spark-streaming.md §F.2–F.4`. ✓ Landed (2026-05-27)

- [x] **spark-lakehouse-l2** — Spark Lakehouse Delta Lake (L.2): `SparkGen.detectLakehouseFormats` detects `.format("delta")` → auto-emit `io.delta:delta-spark_2.13` dep + `spark.sql.extensions`/`spark.sql.catalog.spark_catalog` config; `SparkGen.lakehouseImports`; `examples/spark-lakehouse-delta.ssc` (write/read Parquet→Delta, merge-into); 6+ codegen tests. L.3 Iceberg and L.4 Hudi remain deferred. Spec: `docs/spark-lakehouse.md §L.2`. (2026-05-27)

- [x] **spark-catalog-g2-g4** — Spark Catalog phases G.2–G.4: (G.2) front-matter `spark-hive-metastore:`/`spark-warehouse:` keys → emit `spark-hive_2.13` dep + `enableHiveSupport()` + config keys; (G.3) `@TempView("name")` annotation rewriter → `createOrReplaceTempView`; (G.4) `Dataset.fromTable[T](name)` shim via `spark.table(name).as[T]`; `examples/spark-catalog-hive.ssc`; 8+ codegen tests. Spec: `docs/spark-catalog.md §G.2–G.4`. (2026-05-27)

- [x] **spark-mllib-m2-m5** — Spark MLlib phases M.2–M.5: (M.2) `SparkGen.containsMllib` detection → auto-emit `spark-mllib_2.13` dep; (M.3) `aenc_Vector` given in `SscSparkEncoders` shim via `UDTEncoder(new VectorUDT(), classOf[VectorUDT])`, gated on `usesMllib`; (M.4) `examples/spark-mllib-pipeline.ssc` (Tokenizer+HashingTF+LogisticRegression pipeline); (M.5) `examples/spark-mllib-model-save-load.ssc` (model.write.save + PipelineModel.load); 10+ codegen tests. Spec: `docs/spark-mllib.md §M.2–M.5`. (2026-05-27)

- [x] **v1.56-xslt** — XSLT transformation support: `MarkupCodec.transform` SPI hook + `TransformError`; `XsltTransformer` (JAXP `TransformerFactory`, params forwarding, empty-output guard); `JvmMarkupCodec.transform` override; `Feature.Xslt`; `CapabilityCheck` XsltTransformPat; `InterpreterCapabilities`/`JvmCapabilities` declare `Feature.Xslt`; `examples/xslt-transform.ssc`; 18 tests. (2026-05-27)

## Payments & Blockchain

- [x] **x402-cardano-scalus-validator-simulator-tests** — Scalus script-context simulator tests for Cardano Scalus escrow validator happy path and rejection branches (signature, receiver/amount, validity range). Spec: `docs/x402-cardano-scalus.md §Phase 2`. (2026-05-27)

- [x] **v1.53.1-payments-spi-stripe** — `payments/money/` + `payments/webhook/` + `runtime/std/payments-plugin/` + `Feature.Payments` enum case + Stripe adapter (PaymentIntent / SCA / Customer / Vault / Subscription / Refund / Dispute / Webhook) + `examples/traditional-payments.ssc`. Closes `chargeCard()` placeholder from v1.38. Spec: `docs/traditional-payments.md §16`. (2026-05-27)

- [x] **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (OAuth2 client-cred, PayPal Orders v2, RSA-SHA256 webhook verify) + `runtime/std/payments-braintree/` (GraphQL API, XML REST plans/subscriptions, HMAC-SHA1 webhook). Spec: `docs/traditional-payments.md §11.2`. (2026-05-27)

- [x] **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (X-API-Key, Drop-in nonce, HMAC-SHA256 over notification fields, additionalData escape hatch) + `runtime/std/payments-checkout/` (sk_xxx, Frames token, HMAC-SHA256 over raw body). Spec: `docs/traditional-payments.md §11.3`. (2026-05-27)

- [x] **v1.53.4-payments-square** — `runtime/std/payments-square/` (Bearer token, Web Payments SDK nonce, HMAC-SHA1 over notification_url+body). Spec: `docs/traditional-payments.md §11.4`. (2026-05-27)

- [x] **v1.53.5-payments-vault-mandates-sca** — Cross-PSP `createMandate`/`getMandate` SPI methods (all 5 adapters); `ScaExemption` enum + `scaExemptions` in `CreateIntentRequest`; `mandateId` in `CreateIntentRequest` for off-session MIT; `networkToken` + `mandateId` fields in `StoredMethod`; PSD2 off-session flags wired in PayPal/Adyen/Stripe; `Mandate` extended with `customerId`/`vaultId`/`providerRef`. 9 new SPI-level tests in StripeProviderTest. Spec: `docs/traditional-payments.md §16.5`. (2026-05-27)

- [x] **v1.53.6-payments-mock-provider** — `runtime/std/payments-mock/` fully in-memory `MockProvider` + `MockWebhookReceiver`; `MockMode` enum (Succeed/Fail/RequireSCA) per effect group; all 16 SPI methods; `recorded*` inspection helpers + `reset()`; `PaymentEffect` enum added to SPI; 41 tests. Spec: `docs/traditional-payments.md §16.6`. (2026-05-27)

- [x] **v1.53.7-payments-webhook-cluster** — `payments/webhook-redis/` `RedisSeenKeyStore` (Lettuce SET NX EX, configurable prefix + timeout, 8 tests) + `payments/webhook-postgres/` `PostgresSeenKeyStore` (HikariCP INSERT ON CONFLICT DO NOTHING, auto-CREATE TABLE, `purgeExpired()`, H2 test suite, 9 tests). Both implement `SeenKeyStore` SPI; cluster-safe idempotency for multi-instance deployments. Spec: `docs/traditional-payments.md §16.7`. (2026-05-27)

- [x] **v1.54-bank-rails-spec** — Spec doc `docs/bank-rails.md`: SEPA Credit Transfer + Direct Debit, ACH (credit/debit via Nacha), Pix instant payments (Brazil), FedNow instant payments (US). Cover: `BankRailsProvider` SPI (6 methods: `initiateTransfer / getTransfer / cancelTransfer / initiateDirectDebit / getDirectDebit / webhookReceiver`), `BankTransfer` / `DirectDebitMandate` types, idempotency (reuse `IdempotencyKey` from v1.53), settlement timing model (T+0 / T+1 / T+2), webhook event taxonomy per rail. Implementation phases v1.54.1–v1.54.4 in the spec. Spec: `docs/traditional-payments.md §12` (deferred note). (2026-05-27)

- [x] **v1.54.1-bank-rails-sepa** — `payments/bank-rails/` SPI (BankRailsProvider, BankTransfer, DirectDebitMandate, RailKind, BankRailsEvent, RCode/CCode) + `runtime/std/payments-sepa/` (PAIN.001 CT + PAIN.008 DD XML builder; HMAC-SHA256 webhook receiver; SepaProvider; SepaPlugin; Feature.BankRails; 30 tests; example). Spec: `docs/bank-rails.md §v1.54.1`. (2026-05-27)

- [x] **v1.54.2-bank-rails-ach** — `payments/bank-rails/` (BankRailsProvider SPI + core types) + `runtime/std/payments-ach/` (NachaFile 94-char flat-file builder, same-day ACH, R/C-codes, HMAC webhook, 28 tests). Spec: `docs/bank-rails.md §v1.54.2`. (2026-05-27)

- [x] **v1.54.3-bank-rails-pix** — `runtime/std/payments-pix/` (Pix via DICT API + PSP REST; QR Code Static/Dynamic; webhook `pix.received`). Spec: `docs/bank-rails.md §v1.54.3`. ✓ Landed 2026-05-27.

- [x] **v1.54.4-bank-rails-fednow** — `runtime/std/payments-fednow/` (FedNow instant via ISO 20022 over FedLine; `pacs.008` credit transfer; `pacs.002` status; webhook adapter). Spec: `docs/bank-rails.md §v1.54.4`. ✓ Landed 2026-05-27.

- [x] **blockchain-bitcoin** — `payments/blockchain/bitcoin/`: secp256k1 ECDSA (RFC 6979), BIP-143 SegWit sighash, BIP-340 Schnorr + BIP-341 Taproot tweakedKey, P2WPKH bech32 + P2TR bech32m addresses, PSBT BIP-174 builder/signer/finalizer, `BitcoinChainAdapter` + `ChainId.BitcoinMainnet/Testnet`; 45 tests. ✓ Landed 2026-05-27.

- [x] **blockchain-cosmos** — `payments/blockchain/cosmos/`: secp256k1 + ed25519, StdSignDoc Amino, bech32 HRP (cosmos/osmo/juno), `CosmosChainAdapter`, `ChainId.CosmosHub/Osmosis/Juno`, `BlockchainProvider` ServiceLoader; 41 tests. ✓ Landed 2026-05-27.

- [x] **v1.55-international-bank-rails-spec** — Spec doc `docs/international-bank-rails.md`: SWIFT MT103 + ISO 20022 pacs.008 (CBPR+), SEPA Instant (SCT Inst), UK FPS + BACS DD + CHAPS, India UPI, Japan Zengin, Singapore PayNow. 9 new `RailKind` cases, `Uetr`/`ChargeBearer`/`GpiHop` SWIFT types, `BankAccount` additive fields, 28 new `BankRailsEvent` cases, 8 new `BankRailsError` cases, settlement timing table, 8 implementation phases v1.55.1–v1.55.8. (2026-05-27)

- [x] **v1.55.1-international-swift** — `payments/bank-rails/` type additions (Uetr, ChargeBearer, GpiHop, BankTransfer.gpiTrail/uetr/chargeBearer, RailKind.SWIFT_MT103/SWIFT_PACS008 + 7 other v1.55 cases, BankAccount.bic + 5 other v1.55 fields) + `runtime/std/payments-swift/` (SwiftProvider, SwiftMt103Builder, SwiftPacs008Builder, GpiTracker, SwiftWebhookReceiver, SwiftPlugin; 65 tests). Spec: `docs/international-bank-rails.md §v1.55.1`. (2026-05-27)

- [x] **v1.55.2-sepa-instant** — Extend `runtime/std/payments-sepa/`: `RailKind.SCT_INST`, `SepaPainXml.buildSctInstPacs008`, `SctInstSettled/SctInstRejected` events, `SctInstTimeout` error; 19 new tests (49 total). Spec: `docs/international-bank-rails.md §v1.55.2`. (2026-05-27)

- [x] **v1.55.3-uk-faster-payments** — `runtime/std/payments-uk-fps/` (UkFpsProvider, ConfirmationOfPayee) + `RailKind.UK_FPS` + `BankAccount.sortCode` + CoP name-check; 47 tests. Spec: `docs/international-bank-rails.md §v1.55.3`. (2026-05-27)

- [x] **v1.55.4-uk-bacs** — `runtime/std/payments-uk-bacs/` (UkBacsProvider, BacsFile, AuddisFile) + `RailKind.UK_BACS_DD` + AUDDIS/ARUDD flows; 61 tests. Spec: `docs/international-bank-rails.md §v1.55.4`. (2026-05-27)

- [x] **v1.55.5-uk-chaps** — `runtime/std/payments-uk-chaps/` (UkChapsProvider, ChapsPacs008Builder) + `RailKind.UK_CHAPS`; 25+ tests. Spec: `docs/international-bank-rails.md §v1.55.5`.

- [x] **v1.55.6-india-upi** — `runtime/std/payments-india-upi/` (UpiProvider, push + collect, RSA-SHA256 webhook) + `RailKind.IN_UPI` + `BankAccount.upiVpa`; 63 tests. Spec: `docs/international-bank-rails.md §v1.55.6`. (2026-05-27)

- [x] **v1.55.7-japan-zengin** — `runtime/std/payments-japan-zengin/` (ZenginProvider, kana constraint) + `RailKind.JP_ZENGIN` + `BankAccount.zenginBankCode/zenginBranchCode`; 59 tests. Landed 2026-05-27.

- [x] **v1.55.8-singapore-paynow** — `runtime/std/payments-sg-paynow/` (PayNowProvider, proxy resolution) + `RailKind.SG_PAYNOW` + `BankAccount.paynowProxy`; 30+ tests. Spec: `docs/international-bank-rails.md §v1.55.8`. (2026-05-27)

## Database

- [x] **secret-resolvers-jdk** — In-tree secret resolver plugins for `backend/sql`: `VaultSecretResolver` (HashiCorp Vault KV v1/v2 via `java.net.http.HttpClient` + `VAULT_ADDR`/`VAULT_TOKEN`/`VAULT_NAMESPACE`), `DopplerSecretResolver` (Doppler REST API via JDK HttpClient + `DOPPLER_TOKEN`), `OpSecretResolver` (1Password CLI subprocess `op read`), `PassSecretResolver` (Unix password-store CLI subprocess `pass show`); all implement `scalascript.sql.SecretResolver` SPI; ServiceLoader registration; 26 tests (mock HTTP server for vault/doppler, subprocess hooks for op/pass, scheme dispatch, missing env errors). Spec: `docs/secret-resolvers.md §vault §doppler §op §pass`. (2026-05-27)

## Native Platform

- [x] **v1.48.2-swiftui-ios-run** — `ssc run --target ios` (iOS Simulator) (2026-05-26)

- [x] **v1.48.3-swiftui-device-run** — `ssc run --target ios --device` (real device via ios-deploy) (2026-05-26)

- [x] **v1.48.4-ios-package** — `ssc package --target ios` → signed .ipa (2026-05-26)

- [x] **v1.48.5-ios-publish** — `ssc publish --target ios` (TestFlight + App Store via fastlane) (2026-05-26)

- [x] **v1.49-macos-distribution** — `ssc package/publish --target macos` (notarize + DMG + Mac App Store) (2026-05-26)

- [x] **v1.65.1-swiftui-spi-reg** — Add `META-INF/services` SPI registration for `SwiftUIFrameworkBackend`; fix `emit()` / CLI emit routing so `ssc emit --frontend swiftui` resolves the backend; `SwiftUIEmitPathwayTest` asserts emitted `ContentView.swift` + `Package.swift` + `<App>App.swift`. ✓ Landed 2026-06-02: created `frontend/swiftui/src/main/resources/META-INF/services/scalascript.frontend.FrontendFrameworkSpi`; 8 pathway tests; suite 57 green.

- [x] **v1.65.2-swiftui-fetch-emit** — `FetchAction` → `Task { @MainActor URLSession }` emit; `FetchUrlSignal` → `onAppear`/`onChange` async load with `@State` companion var. ≥ 4 new `SwiftUIEmitterTest` assertions. ✓ Landed 2026-06-02: GET uses `data(from:)`, POST/PUT/PATCH uses `URLRequest` + `httpBody`; `FetchUrlSignal` emits `.task { await _load_<id>() }` + `.onChange(of: tickId)` modifiers + private async load function; 5 new tests; suite 62 green.

- [x] **v1.65.3-swiftui-dashboard-smoke** — `ssc emit --frontend swiftui web/dashboard.ssc` → `swiftc -parse` green (skip when `swift` not on PATH). Unsupported IR nodes emit `// TODO: unsupported` rather than crashing. ✓ Landed 2026-06-02: changed catch-all from `Text("[unsupported: X]")` to `// TODO: unsupported IR node: X\nEmptyView()`; expanded `collectFetchSignals` to walk TabBar/NavigationStack/LazyList/LazyGrid/Sheet/SafeArea/KeyboardAvoiding; added `examples/frontend/dashboard/dashboard.ssc`; `SwiftUIDashboardSmokeTest` (13 tests, swiftc-parse gate); suite 75 green.

## Distribution & Tooling

- [x] **v1.52.1-deploy-plugin** — `runtime/std/deploy-plugin/` (four-file SPI layout) + `Manifest` AST `deploy`/`groups`/`environments`/`state` fields + `ssc deploy` CLI stub + multi-target orchestrator core (DAG resolver, parallel/sequence/pipeline executor, output→input wiring, failure handler, event stream) + local subprocess adapter + `ssc deploy plan`/`--dry-run`/`envs` + `examples/deploy.ssc`. Spec: `docs/deploy.md §16`. (2026-05-27)

- [x] **v1.52.2-deploy-container** — `DockerfileGenerator` (4 base-image recipes: temurin/distroless/node/nginx per ArtifactKind; HEALTHCHECK, build-args, labels) + `ContainerTarget` (all 7 SPI verbs; buildctl→buildx→docker fallback; multi-platform; digest rollback; docker inspect status; docker logs) + `TargetFactory` (kind→target resolver) + `ArtifactRegistry` OciImage case. Spec: `docs/deploy.md §6.1`. 14 new tests; 36 total. (2026-05-27)

- [x] **v1.52.3-deploy-k8s** — `K8sManifestGenerator` (Deployment+Service+Ingress+ConfigMap+Secret; liveness/readiness probes; PreStop drain; blue-green slot labels) + `K8sTarget` (all 7 SPI verbs + switch/promote; kubectl subprocess; blue-green; dry-run) + `TargetFactory` k8s case. Spec: `docs/deploy.md §6.2`. 17 new tests; 53 total. (2026-05-27)

- [x] **v1.52.4-deploy-traditional** — `SystemdUnitGenerator` + `SshSystemdTarget` (SSH+SCP+systemd) + `RsyncTarget` (rsync --delete) + `SftpTarget` (sftp batch upload) + TargetFactory transport dispatch. Spec: `docs/deploy.md §6.5`. 18 new tests; 71 total. (2026-05-27)

- [x] **v1.52.5-deploy-static** — `StaticTarget` (Vercel/Netlify/Cloudflare Pages/GitHub Pages; CLI-first with API fallback; HTTP GET status; dry-run) + TargetFactory `"static"`. Spec: `docs/deploy.md §6.4`. 9 new tests; 80 total. (2026-05-27)

- [x] **v1.52.6-deploy-faas** — `FaasTarget` (AWS Lambda LambdaZip+alias; Cloudflare Workers wrangler; GCP Cloud Run gcloud; Vercel Functions vercel CLI; all dry-run; rollback via alias version) + TargetFactory `"faas"/"lambda"/"serverless"`. Spec: `docs/deploy.md §6.3`. 11 new tests; 91 total. (2026-05-27)

- [x] **v1.52.7-deploy-state-backends** — `JsonState` (zero-dep ser/de) + `LocalFileStateBackend` (file + TTL lock) + `S3StateBackend` (aws s3api + mtime lock) + `ConsulStateBackend` (HTTP KV + session lock) + `EtcdStateBackend` (etcdctl + lease lock) + `StateBackendFactory` (dispatch + production enforcement) + `StateMigrator` (dry-run migrate). Spec: `docs/deploy.md §3.5/§10.2`. 14 new tests; 105 total. (2026-05-27)

- [x] **v1.50-native-p1-snakeyaml** — Replace snakeyaml with pure-Scala frontmatter parser (2026-05-27)

- [x] **v1.50-native-p2-graalvm** — GraalVM native-image build for `ssc` (2026-05-27)

- [x] **v1.50-native-p3-plugin-bridge** — `ssc-plugin-host.jar` + automatic bridge (existing plugins unchanged) (2026-05-27)
  _New `ssc-plugin-host` sbt subproject: `SubprocessHost` main loads any existing plugin JAR via URLClassLoader + ServiceLoader + wire protocol. Native `ssc` auto-spawns it when `--plugin foo.jar` given. Plugin authors change nothing. Spec: `BACKLOG.md §Phase 3`._

- [x] **v1.50-native-p4-plugin-guide** — Plugin-author guide: compile your plugin to a native binary via GraalVM native-image (docs only, no core changes) (2026-05-27)

- [x] **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections without OOM (2026-05-27)

- [x] **watch-100ms** — Watch cycle optimization: `ssc --watch rest-api.ssc` target < 100 ms per cycle (2026-05-27)
  _Added `ssc watch-bench` reload harness over a temporary source copy, plus hot-path hashing fixes: ParseCache / SectionSnapshot use direct hex encoding and incremental typer reuses precomputed section hashes instead of hashing retyped sections twice. Spec: `BACKLOG.md §Compiler — AST cache`._

- [x] **sbt-interop-plugin** — Build-tool integration: `sbt-scalascript-interop` plugin + Mill module trait + scala-cli directive (2026-05-27)
  _`ssc generate-facade` CLI command; sbt plugin with 4 scripted tests; Mill trait + scala-cli docs. Source: `tools/sbt-plugin/`. Spec: `docs/scala-interop.md §6`._

- [x] **ssc-check** — Standalone `ssc check <file>` CLI command: type-check without codegen or linking; exit 0 = clean, exit 1 = errors with structured output; `--json` flag for machine-readable diagnostics; `--watch` mode re-checks on save (reuse `ParseCache` + incremental typer); designed for CI pre-commit hooks and IDE integrations. Spec: `BACKLOG.md §Tooling — ssc check standalone type-checker`. (2026-05-27)

- [x] **lsp-phase3** — LSP Phase 3: `textDocument/codeAction` (quick-fix for unknown-name + unused-import diagnostics); `textDocument/formatting` (indent normalisation, trailing-whitespace strip); `textDocument/inlayHint` (inferred types on `val` bindings, effect annotations); `workspace/didChangeWatchedFiles` (auto-reload `.ssc` on disk change without client re-open). Spec: `BACKLOG.md §LSP server`. ✓ Landed 2026-05-27.

- [x] **js-tree-shaking** — Dead-code elimination in JS output: mark reachable symbols from `@main` / exported defs; emit only reachable `const`/`function` declarations; `--no-tree-shake` escape hatch; `ssc build --stats` reports removed vs kept symbol counts. Spec: `BACKLOG.md §Generated code — JS tree-shaking`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-js** — Ledger hardware wallet Scala.js integration: `wallet-vault-ledger-js` subproject; WebHID transport (`navigator.hid.requestDevice`) for browser; APDU framing over HID packets; Ethereum app signer (secp256k1 + EIP-712 typed-data); Cardano app signer (CIP-8 framing); connect/disconnect lifecycle; `LedgerVault` implementing `Vault` SPI; 13 tests via mocked HID device. Spec: `BACKLOG.md §wallet-vault-ledger-js`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-solana** — Ledger Solana-app signer (JVM): `payments/wallet/vault-ledger-solana/` — `SolanaApp` object (CLA=0xE0, INS=0x04 SIGN_TRANSACTION, INS=0x05 SIGN_OFFCHAIN_MESSAGE, default HD path `m/44'/501'/0'/0'`); `LedgerSolanaVault` implementing `Vault` SPI, routes `Curve.Ed25519` to `SolanaApp`; ed25519 signature bytes (64 B) returned as `Array[Byte]`; `AppSwitchRequired` guard via `Dashboard.getAppName`; `MockTransport` re-used from `wallet-vault-ledger`; 10+ tests (path encoding, single-packet sign, chunked sign, wrong-app error, Vault.getSigner routing). Spec: `BACKLOG.md §Phase 7 — Solana-app signer`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-bitcoin** — Ledger Bitcoin-app signer (JVM): `payments/wallet/vault-ledger-bitcoin/` — `BitcoinApp` object (CLA=0xE1, new protocol v2+, INS=0x00/0x02/0x03/0x04); `LedgerBitcoinVault` implementing `Vault` SPI; `LedgerBitcoinRawSigner`; `AppSwitchRequired` guard via `Dashboard.getAppName`; `MockTransport` re-used; 14 tests. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-cardano** — Ledger Cardano-app signer (JVM): `payments/wallet/vault-ledger-cardano/` — `CardanoApp` object (CLA=0xD7, INS=0x10 GET_EXTENDED_PUBLIC_KEY, INS=0x21 SIGN_TX with CIP-8 framing); `LedgerCardanoVault` implementing `Vault` SPI, routes `Curve.Ed25519` + Cardano-prefix HD path; CIP-8 COSE_Sign1 Sig_Structure builder (hand-rolled CBOR, no deps); `AppSwitchRequired` guard; `MockTransport` re-used; 11 tests. ✓ Landed 2026-05-27.

- [x] **ssc-profile** — `ssc profile <file.ssc>` CLI command: instrument parse + typecheck + codegen phases with wall-clock + allocation counters; output flame-graph-ready JSON (Brendan Gregg folded stacks format) to `profile.json`; `--top=N` flag prints N hottest functions to stdout; `--compare <baseline.json>` shows regression vs prior run. Spec: `BACKLOG.md §New tool — ssc profile file.ssc`. ✓ Landed 2026-05-27.

- [x] **x402-cardano-scalus-completion** — Complete x402 Cardano Scalus escrow settlement Phases 3/5/6: ✓ Landed 2026-05-27. (Phase 3) `ReferenceScriptDeployer` helper that builds a bloxbean Tx publishing the compiled Plutus script as a Cardano reference script (deploy-once; writes txHash+outputIndex to a config field), 2 tests; (Phase 5) full round-trip integration test `ScalusRoundTripTest` exercising Scalus client CIP-8 sign → `CardanoProvider.Scalus` verify + settle plan construction end-to-end with mock Blockfrost HTTP; (Phase 6) `EscrowDeposit.build(payerWallet, req)` — constructs and signs a bloxbean deposit Tx locking ADA at the script address with `EscrowDatum` (payerKeyHash, receiverHash, amount, validBefore, refundAfter, claimMessageHash), 3 tests; `examples/x402-cardano-scalus.ssc` full walkthrough showing the complete Preprod flow; update `BACKLOG.md §Phase 9 follow-up` to point at the new Scalus flows. Module: `payments/x402/facilitator-cardano-scalus/`. Spec: `docs/x402-cardano-scalus.md §Phase 3–6`. (2026-05-27)

- [x] **wallet-solana-standard-js** — Scala.js `registerWallet` integration for `wallet-connector-wallet-std`: add `WalletStandardJs` object in `wallet-connector-wallet-std/js/src/` implementing `window.standard.wallets` registration protocol (announce event via `window.dispatchEvent(new CustomEvent("wallet-standard:register-wallet", { detail: ... }))`); `WalletInfo` JS value shape (name, icon, chains, features); `StandardWalletConnectorJs` bridging `WalletConnectorWalletStd` to the browser registry; `WalletStandardJsTest` suite (6 tests: announce event dispatched on register, features shape, chains list, connect handler wiring, signMessage wiring, signTransaction wiring) via Node.js `global.window` property stub. Unblocked: wallet-spi Scala.js cross-compile Stage 1–6 all landed 2026-05-20. Spec: `BACKLOG.md §Phase 5 — Solana DappConnector`. (2026-05-27)

## Language & Compiler

- [x] **markup-lang-xml** — Wire `xml"..."` into language runtime: add `Lang.Xml = "xml"` / `isXml` to `Lang.scala`; extend `SectionRuntime` with `runXmlBlock` (render `${…}` → XML-escaped → parse → `MarkupV`); generalise `renderStringBlock` to accept `escapeFn: String => String`; add `Value.MarkupV(doc: Markup.Doc)` case to `Value.scala`; 8+ tests: fenced `` ```xml `` block binds `MarkupV`, element text escaping, splice of string value, splice of `Markup.Node`. Pre-written test skeleton in `runtime/backend/interpreter/src/test/scala/scalascript/SectionXmlBlockTest.scala`. Spec: `BACKLOG.md §v1.55.2`.

- [x] **markup-feature-backend** — `Feature.Markup` + `Backend.markupCodec` SPI + JVM SAX codec: add `case Markup` to `Feature.scala`; `def markupCodec: Option[MarkupCodec] = None` to `Backend.scala`; `JvmMarkupCodec` in `runtime/backend/interpreter/` using `javax.xml.parsers.SAXParser`; declare `Feature.Markup` in `*Capabilities.scala` for interpreter+JVM backends; `CapabilityCheck` rejects `xml"..."` on backends lacking `Feature.Markup`; 16 tests. Spec: `BACKLOG.md §v1.55.3`. (2026-05-27)

- [x] **markup-compile-check** — Compile-time `xml"..."` well-formedness: `lang/core/.../transform/MarkupInterpolatorCheck.scala` joins interpolation parts with placeholder text, runs `PureMarkupCodec.parse` at compile time, emits `Diagnostic.XmlParseError` on malformed input; 8+ tests (malformed tag, unclosed element, mismatched tags, valid doc passes). Spec: `BACKLOG.md §v1.55.4`. (2026-05-27)

- [x] **markup-element-literal** — Opt-in `<foo bar={expr}/>` element-literal syntax: `lang/core/.../transform/MarkupLiteralLower.scala`; `import scalascript.markup.*` enables; `<name attrs>{children}</name>` → `Markup.Element(…)` constructors; 10+ tests. Spec: `BACKLOG.md §v1.55.5`. (2026-05-27)

- [x] **markup-xsd-sepa-refactor** — XSD validation + refactor SEPA/FedNow XML: `JvmMarkupCodec.validate(doc, xsd)` via `javax.xml.validation`; rewrite `SepaPainXml` PAIN.001/008 + FedNow pacs.008/002 from string concat to `xml"..."` interpolator; golden-file regression suite (12 PAIN.001 fixtures). Spec: `BACKLOG.md §v1.55.6`. (2026-05-27)

- [x] **markup-config-js** — `.xml` ConfigParser + JS/Node markup codecs: `ConfigParser.Format.Xml` + detectFormat; `XmlConfigParser.scala` (XML → `ConfigValue.Object`); `runtime/std/markup-js/` (JS DOMParser/XMLSerializer); `runtime/std/markup-node/` (Node @xmldom/xmldom); `markupCore` cross-compiled (JVM+JS); 41 tests (16 XmlConfigParser + 11 markup-js + 14 markup-node). (2026-05-27)

- [x] **v2.1.6-dstream-connectors** — `Kafka`/`Files`/`FileFormat`/`Jdbc`/`Pulsar`/`Kinesis` stubs in all 4 code-gen shims (Spark, KafkaStreams, Flink, Beam) + native interpreter intrinsics; `containsConnector` in each generator; `DSource.fromDataset` bridge; SparkGen Kafka dep extended; `DSink[T] = Any` alias; 14 new tests. Spec: `docs/distributed-streams.md §6`. (2026-05-27)

- [x] **v2.1.5-dstream-flink** — `runtime/backend/flink/` module: `FlinkGen` (Flink DataStream API shim, `_flinkEnv()` helper), `BeamGen` (Apache Beam Java SDK shim, `_createBeamPipeline()`, runner dep auto-selection for DirectRunner/FlinkRunner/SparkRunner), `FlinkBackend`/`BeamBackend` SPI adapters, `FlinkCapabilities`/`BeamCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration, `PipelineOptions` case class; 30 new `FlinkGenTest` tests. Spec: `docs/distributed-streams.md §9.4–9.5`. (2026-05-27)

- [x] **v2.1.4-dstream-kafka** — `runtime/backend/kafka-streams/` module: `KafkaStreamsGen` (shim pattern from v2.1.3, adds `Backend.KafkaStreams`/`Backend.Kafka`, topology helpers, extended `containsDStream` for `Window.*`/`WatermarkStrategy.*`/`Trigger.*`), `KafkaStreamsBackend` SPI adapter, `KafkaStreamsCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration; 22 new `KafkaStreamsGenTest` tests. Spec: `docs/distributed-streams.md §9.3`. (2026-05-27)

- [x] **v2.1.3-dstream-spark** — `SparkGen` DStream shim: `containsDStream` detection + `dstreamSparkShim` emission; full pipeline DSL backed by `Seq[Any]` for bounded `InMemory` sources; `Feature.DistributedStreams` in `SparkCapabilities`; 14 new `SparkGenTest` tests. Spec: `docs/distributed-streams.md §9.2`. (2026-05-27)

- [x] **v2.1.2-dstream-native-unbounded** — Processing-time `window(Window.fixed/sliding/session/global)`, `withTrigger`, `withAllowedLateness`, `withWatermark(WatermarkStrategy.atEnd)`, `timerProcessing(d)(f)`; DirectRunner provides `EventTime` + `WatermarkPerfect` in v2.1.2. Spec: `docs/distributed-streams.md §13 v2.1.2`. (2026-05-27, 30 tests green)

- [x] **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` types + native bounded backend (wraps `Dataset[T]` partitions); `DirectRunner` test backend; `Feature.DistributedStreams` flag; `examples/distributed-streams.ssc`. Spec: `docs/distributed-streams.md §13`. (2026-05-27, 23 tests green)

- [x] **v1.51.4-streams-sse-ws** — `Source.fromSse`/`Sink.toSseStream` in HTTP plugin + `Source.fromWebSocket`/`Sink.toWsRoom` in WS plugin + `mapAsync(n)(f)` + `.recover(pf)`/`.mapError(f)`/`Source.bracket`. Spec: `docs/streams.md §14.4`. (2026-05-27)

- [x] **v1.51.5-streams-buffer** — `.buffer(n, OverflowStrategy)` (Drop/Fail/Backpressure/DropHead) + `.throttle(Rate)` + `.debounce(Duration)` + `Source.signal(sig)` current-value adapter in the interpreter path. Spec: `docs/streams.md §14.5`. (2026-05-27)

- [x] **v1.51.5b-streams-clock-ui-signals** — Interpreter wall-clock `.throttle`/`.debounce`, live frontend `ReactiveSignal` subscriptions for `Source.signal`, reverse `sig.bind(source)`, and Swing/JavaFX runtime state synchronization. SwiftUI platform-native stream bridging split to `v1.51.5c-streams-swiftui-bridge`. Spec: `BACKLOG.md §Streams v1.51.5 follow-ups`. (2026-05-27)

- [x] **v1.51.5c-streams-swiftui-bridge** — Platform-native SwiftUI stream/signal bridge for generated `@State` values, matching the interpreter/JVM desktop `Source.signal` + `sig.bind(source)` behavior where practical. Spec: `docs/streams.md §8.5`. (2026-05-27)

- [x] **v1.51.6-streams-typed** — Type-safe algebraic-effect integration: `Stream[A]` parameterized effect op (`EffectOp(name, args)` type-system extension), 4 ops (`emit/complete/error/request`), `runStream[A, R]: (Source[A], R)` canonical form, cross-backend parity (interpreter + JS + JVM all return the tuple). Spec: `docs/streams.md §14.6`. (2026-05-28)

- [x] **v1.51.3-streams-flow-sink** — `Flow[A, B]` + `Sink[A]` types; `.to(sink)` / `.via(flow)` routing; combining operators `zip` / `merge` / `concat` / `broadcast(n)` / `balance(n)` (queue-per-subscriber); `groupBy(key)` + `mergeSubstreams`; interpreter intrinsics in `StreamsIntrinsics.scala`; JS lowering in JsGen. Spec: `docs/streams.md §14.3`. (2026-05-27)

- [x] **v1.51.2-streams-js-backend** — JS `async function*` emit path: `_makeAsyncStream` helper in JsGen preamble; `stream { body }` → `_makeAsyncStream(async function*() { body })`; `emit(x)` → `yield x`; consumer iteration → `for await`; `Feature.Streams` in `JsCapabilities`; full operator set (map/filter/take/drop/flatMap/concat/zip) on async iterators. Spec: `docs/streams.md §14.2`. (2026-05-27)

- [x] **v1.51.1-streams-plugin** — `runtime/std/streams-plugin/` + `Source` core (`map`/`filter`/`runForeach`/`runFold`/`runToList`), interpreter + JVM only, `Feature.Streams` flag, `examples/streams.ssc`. Spec: `docs/streams.md §14`. (2026-05-27, commit 7f9a0f02)

- [x] **v1.12.1-effects-types** — Add `EffectRow` to `SType`, Rémy-style row unification, `!` operator in `TypeParser`, `multi effect` keyword, handler discharge in typer, `EffectAnalysis` verifier mode, §9 diagnostics. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §3, §4, §5.1, §13 v1.12.1._

- [x] **v1.12.2-effects-runtime** — JS `function*`/`yield` fast path for one-shot effects; coroutine VT wiring on JVM/interpreter; dynamic one-shot-violation check; cross-backend parity tests.
  _Spec: `docs/algebraic-effects.md` §5.3, §13 v1.12.2._

- [x] **v1.12.3-effects-stdlib** — Re-type `runLogger`/`runRandomSeeded`/etc. with discharge signatures; add `Reader[R]` capability; add `NonDet` multi-shot exemplar; `examples/algebraic-effects.ssc`; promote `EffectAnalysis` warnings to errors. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §6, §8.2, §13 v1.12.3._

---

## Done

- [x] **v1.12-spec** — Typed Algebraic Effects spec (`docs/algebraic-effects.md`) — design doc + go/no-go decision (2026-05-26)
- [x] **v1.46-phase1-metadata** — `apiClients:` front-matter → `ApiClientDecl` AST
- [x] **v1.46-phase2-swing-client** — JVM/Swing in-process callable clients
- [x] **v1.46-phase3-http-client** — JS HTTP client + `awaitClient` async
- [x] **v1.46-phase4-shared-codecs** — shared `_ssc_typed_json_encode/decode` facade
- [x] **v1.46-phase5-validation** — static path-param validation warnings
- [x] **v1.46-phase6-auth** — auth/custom header injection
- [x] **v1.46-phase6-per-call-headers** — per-call header overrides
- [x] **v1.46-phase6-retry** — retry policy (`_ssc_api_set_retry`)
- [x] **v1.46-phase6-cancel** — cancellation tokens
- [x] **v1.46-phase7-sse** — SSE streaming (EventSource/fetch JS; HttpURLConnection JVM)
- [x] **v1.46-ws-subscriptions** — WebSocket subscriptions (native WebSocket JS; java.net.http.HttpClient JVM; `_SscWsHandle` with `send()`+`close()`)
- [x] **v1.46-phase5-derivation** — Route derivation: `RouteDeriver` auto-generates `ApiClientDecl` from `route()` calls (2026-05-26)
- [x] **v1.46-pagination** — Pagination helpers: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` on JVM + JS (2026-05-26)
- [x] **v1.38-payment-request** — Payment Request API (browser + server) — Complete (2026-05-26)
- [x] **x402-http-payment** — x402 HTTP payment protocol — All phases landed (2026-05-19/20)
- [x] **blockchain-spi** — Blockchain SPI — All phases landed (2026-05-19/20)
- [x] **wallet-key-mgmt** — Wallet key management + dApp connectivity — Landed (2026-05-20)
- [x] **mcp-x402-wallet** — MCP × x402 × Wallet agentic payments — All 7 phases landed (2026-05-19)
- [x] **micropayment-platform** — Micropayment platform — All phases landed (2026-05-19/20)
- [x] **v1.26-sql-jdbc** — `sql` fenced code blocks (JDBC) — v1.26 + v1.26.1 + v1.26.2 landed (2026-05-21)
- [x] **v1.27-browser-sql** — Browser-side SQL (sql.js / DuckDB-Wasm) — v1.27 landed (2026-05-21)
- [x] **v1.30-side-sql** — `@side=client|server` for SQL blocks — v1.30 complete (2026-05-21)
- [x] **v1.31-transaction** — `transaction` fenced block — v1.31 landed (2026-05-21)
- [x] **v1.48-swiftui** — SwiftUI Native Frontend (iOS + macOS) — Phases 1–3 all landed (2026-05-26)
- [x] **v1.48.1-swiftui-run** — `ssc run --target macos` + swift build in package; target renamed desktop-macos → macos (2026-05-26)
- [x] **v1.30-repl-web-mode** — REPL web-aware mode + `mount()` intrinsic — All 8 phases landed: Routes refactor, `mount()` intrinsic, `:serve`/`:stop`/`:clear`, `:mount`, `:load`/`:reload`/`:unmount`, `:routes`/`:http`/`:call`, typed handlers, `:help`/`:set` (2026-05-26)
- [x] **v2.0-sep-compile** — Separate compilation — ALL-DELIVERABLES-LANDED (2026-05-20)
- [x] **v2.0-cross-platform-smoke** — Cross-platform portability for v2.0 artifact pipeline: `InterfaceExtractor.normalizeLineEndings` + `sourceFileHash` (CRLF→LF before hashing); `ModuleGraph.isStale/isJvmStale/isJsStale` updated; 13 tests in `CrossPlatformSmokeTest`; `docs/v2.0-scale-benchmark.md` updated. (2026-05-28)
- [x] **interpreter-ergonomics** — Interpreter ergonomics — All 3 items landed (v1.13 + 2026-05-19)
- [x] **wasm-backend-phase1** — WASM backend: scalascript/ssc block support (Phase 1), integration tests + example (Phase 2), `//> using dep` hoisting + HTTP Fetch example (Phase 3) — All 3 phases landed (2026-05-26)
- [x] **v1.60.1-tuple-monoid-types** — `SType.Unit = Tuple(Nil)`; `tupleConcat` smart constructor; `++` in type parser; 1-tuple `(A,)` surface syntax; 49 tests. ✓ Landed 2026-05-28.
- [x] **v1.60.2-tuple-monoid-values** — `TupleV ++ TupleV` in `DispatchRuntime`; `_tupleConcat` JS helper (sets `_isTuple`); JVM `_tupleConcat` with `scala.Tuple.fromArray`; 4 interpreter + 3 JsGen tests. ✓ Landed 2026-05-28.
- [x] **v1.60.3-tuple-monoid-docs** — `algebraic-effects.md` §8.3 "Unified runner signature" with `Out(E) ++ (R,)` table; `streams.ssc` tuple monoid section; `BACKLOG.md`/`CHANGELOG.md` v1.60 closed. ✓ Landed 2026-05-28.
- [x] **v1.60.4-tuple-bareconcat** — 1-tuple ≅ element equivalence + bare-value `++`: `(A,B) ++ C = (A,B,C)`, `C ++ (A,B) = (C,A,B)`, `bare ++ bare = 2-tuple`, `() ++ v = v`. `DispatchRuntime` (5 new cases), `_tupleConcat` JS/JVM (Array.isArray guard), 5 InterpreterTest + 2 JsGenStreamsTest. Docs: `tuple-monoid.md` §2, `user-guide.md`, `algebraic-effects.md` §8.3, `streams.ssc`, `streams.md`. ✓ Landed 2026-05-28.

## v1.61 — Performance & Memory Optimization

- [x] **v1.61.0-bench** — Benchmark infrastructure: 8-workload corpus (`bench/corpus/`), `bench/run.sc` (scala-cli timing harness), `bench/BASELINE.md`, `runtime/backend/interpreter-bench` (sbt-jmh module), `scripts/bundle-size.sh`. ✓ Landed 2026-05-28.
- [x] **v1.61.1-dispatch-table** — Two-level dispatch in `DispatchRuntime` (`recv match` → per-type `name match` hashCode switch); extensions early-exit when no user extensions registered. ✓ Landed 2026-05-28.
- [x] **v1.61.2-pure-path** — Smart `Computation.map`; all-Pure fast path in `sequence`; pure-path in `Term.Select`/`Term.Assign`/`BlockRuntime.evalBlock`. ✓ Landed 2026-05-28.
- [x] **v1.61.3-env-overhaul** — While-loop frame filtered to O(N_local_vars); ALL Term.Assign intercepts keep local in sync. arith-loop 15600ms → 4480ms (3.5×). ✓ Landed 2026-05-28.
- [x] **v1.61.4-pattern-compile** — Compile `Term.Match` to decision-tree closure cached by AST identity. pattern-match-heavy 6069ms → 3960ms (1.53× vs baseline). ✓ Landed 2026-05-28.
- [x] **v1.61.5-js-inlining** — Tuple IIFE→Object.assign; direct while stmt; int-mul typeof skip. User-code -17–30% chars per program. ✓ Landed 2026-05-28.
- [x] **v1.61.6-preamble-split** — JS preamble split into Core+HtmlDsl+Jwt+WsServer+Optics+Signals+IndexedDb capabilities; `generateRuntime` assembles conditionally; `detectCapabilities` extended. Core-only bundle ~50 KB vs ~185 KB full. ✓ Landed 2026-05-29.
- [x] **v1.61.7-memory** — `ArtifactIO`/`JvmArtifactIO` binary MessagePack format for `*File` methods; auto-detect on read; 5–10× smaller artifacts. (`IntV`/`DoubleV` pools, `TupleV → Array`, `FunV` split, `Span` sidecar — deferred, too invasive for this cycle.) ✓ Landed 2026-05-29.

## x402 — Cardano Scalus thin-glue wiring

- [x] **x402-cardano-scalus-wire** — Wire `CardanoFacilitatorConfig.scalusSettle` to `BloxbeanScalusSettler`: add `CardanoScalusFacilitator.preprod/mainnet` factory in `x402-facilitator-cardano-scalus` (injects `ScalusSettler.asConfigHook`); remove "not yet implemented" error branch; 5 new tests in `CardanoScalusFacilitatorTest` + 3 new tests in `CardanoFacilitatorTest`; close stale BACKLOG checkbox. ✓ Landed 2026-05-28.

## Wallet — Trezor vault adapter

- [x] **wallet-vault-trezor** — Trezor hardware wallet vault adapter: `payments/wallet/vault-trezor/` sbt subproject; `TrezorEthVault` implementing `Vault` SPI; `TrezorBridge` trait + `HttpTrezorBridge` (Trezor Bridge local daemon `http://127.0.0.1:21325`); `TrezorSession` (acquire/release lifecycle); BIP-32 path encoding via `Bip32.parse` → `address_n` int array; `ButtonRequest` auto-ack loop; `MockTrezorBridge` with response queues for tests; 29 tests. Spec: `docs/wallet-vault-trezor.md`. ✓ Landed 2026-05-28.

## Wallet — Ledger WebBLE (Scala.js)

- [x] **wallet-vault-ledger-bluetooth-js** — Scala.js WebBLE transport for Ledger Nano X / Stax: `payments/wallet/wallet-vault-ledger-bluetooth-js/` cross-compiled sbt subproject (JS-only); `WebBleTransport` implementing `LedgerTransport`; wraps `navigator.bluetooth.requestDevice` → `connectGATT` → service UUID `13d63400-2c97-0004-0000-4c6564676572` (Ledger BLE service); `notify` characteristic for device→host; `write` characteristic for host→device; frame splitting for BLE MTU (default 23 bytes); same APDU framing as existing `LedgerHidTransport`; `MockBluetoothDevice` for tests (mirrors existing `MockHidDevice` pattern); 10+ tests. No new vault implementations needed — existing `LedgerEthVault`/`LedgerBitcoinVault`/etc. transparently use any `LedgerTransport`. Spec: `docs/wallet-vault-ledger.md §bluetooth-transport` (amend existing doc).

## Wallet — MPC vendor adapters

- [x] **wallet-vault-mpc-fireblocks** — Fireblocks MPC adapter: `payments/wallet/wallet-vault-mpc-fireblocks/` sbt subproject; `FireblocksRemoteSigningClient` extending `HttpRemoteSigningClient`; Fireblocks JWT auth (`RS256`, `iat`+`nonce`+`bodyHash` claims, API-key header `X-API-Key`); endpoint: `POST /v1/transactions` (create tx) → `GET /v1/transactions/{id}` (poll); `FireblocksVault` named constructor (`FireblocksVault(apiKey, privateKeyPem, baseUrl)`); `FireblocksPlugin` ServiceLoader; 16 tests (mock HTTP, JWT signing, poll loop, timeout). Spec: `docs/wallet-vault-mpc.md §fireblocks`. ✓ Landed 2026-05-28.

- [x] **wallet-vault-mpc-coinbase** — Coinbase MPC adapter: `payments/wallet/wallet-vault-mpc-coinbase/` sbt subproject; `CoinbaseRemoteSigningClient` extending `HttpRemoteSigningClient`; Coinbase Prime API auth (`EC P-256` request signing, `X-CB-ACCESS-KEY` + `X-CB-ACCESS-SIGNATURE` + `X-CB-ACCESS-TIMESTAMP` headers); endpoint: `POST /v1/portfolios/{portfolio_id}/signing_requests` → `GET /v1/portfolios/{portfolio_id}/signing_requests/{id}`; `CoinbaseVault` named constructor; `CoinbasePlugin` ServiceLoader; 15 tests. Spec: `docs/wallet-vault-mpc.md §coinbase`. ✓ Landed (2026-05-28)

- [x] **wallet-vault-mpc-lit** — Lit Protocol threshold signing adapter: `payments/wallet/wallet-vault-mpc-lit/` sbt subproject; `LitRemoteSigningClient` extending `HttpRemoteSigningClient`; Lit PKP signing via `POST /web3/pkp/sign` with pre-computed `authSig` (SIWE-style bearer); `GET /health` for health; `GET /web3/pkp/list` for account discovery; `LitOptions(pkpPublicKey, authSig, sigName)`; `LitVault` named constructor; `LitPlugin` ServiceLoader; 14 tests (mock HTTP, authSig header, PKP sign body, signature parsing, poll timeout, curve mapping, ServiceLoader). Spec: `docs/wallet-vault-mpc.md §lit`.

- [x] **wallet-vault-mpc-zengo** — ZenGo X Enterprise MPC adapter: `payments/wallet/wallet-vault-mpc-zengo/` sbt subproject; `ZenGoRemoteSigningClient` extending `HttpRemoteSigningClient`; HMAC-SHA256 request auth (`X-ZENGO-KEY` + `X-ZENGO-TIMESTAMP` + `X-ZENGO-SIGNATURE` over `timestamp|method|path|sha256(body)`); `GET /v1/health`; `GET /v1/accounts`; `POST /v1/signing/requests` → `GET /v1/signing/requests/{id}` async poll; `ZenGoVault` named constructor; `ZenGoPlugin` ServiceLoader; 14 tests. Spec: `docs/wallet-vault-mpc.md §zengo`.

## CLI improvements (post command-SPI)

- [x] **cli-gate-flaky-tests** — The 4 chronically-red `cli` tests fail on clean
  main all session: `JvmDirectDriverTest` (×3 — in-process Scala3 compiler driver)
  and `TypedRouteDistributedExampleCliTest` ("renders SPA with raw JS"). They are
  environment-dependent, not regressions. First confirm the root cause (missing
  scala-cli / driver classpath), then env-gate them like the WS load tests
  (`assume(...)` / cancel) so `cli/test` is green-means-green. If any failure is a
  real regression, fix instead of gating. Goal: 0 spurious failures in `cli/test`.

- [x] **dap-value-exhaustiveness** — `DapSession.valueToDap`'s match over the
  interpreter `Value` enum has broken the `-Werror` build twice this session as
  concurrent commits added `BigIntV` then `DecimalV`. Add a resilient fallback
  (e.g. `case other => (IValue.show(other), other.getClass.getSimpleName)`) so new
  `Value` variants don't break the dap module / CLI compilation. Document the
  trade-off (loses the compiler's "update DAP" nudge) in a one-line comment.

- [~] **cli-command-context-spi** — Unlock plugin-provided `ssc` subcommands:
  extract a stable `CommandContext` (the cli-internal surface commands need —
  `compileViaBackend`, `expectText`, artifact IO, …) so a `CliCommand` no longer
  depends on package-private Main helpers directly. Then `.sscpkg` plugins on the
  classpath can contribute commands via the existing ServiceLoader path.
  **Spec landed**: `docs/cli-command-spi.md §plugin-commands` (CommandContext
  shape + migration sketch). **Implementation deferred**: no concrete plugin
  needs to contribute a command yet, so building the SPI now would be speculative
  (changes `run`'s signature across all commands for zero current consumer).
  Land it when the first real plugin command appears — design the context
  against its actual needs.

- [x] **cli-main-helper-split** — Main.scala is ~8,900 lines of shared helpers now
  that command logic moved to classes. Extract cohesive helper clusters (build
  pipeline, synthetic-request/render helpers, artifact IO) into focused files.
  Lower priority — diminishing navigational return; helpers are genuinely shared
  infra; behavior-preserving extraction only.

---

## Conformance fixes — cross-backend gaps (2026-06-02)

Root cause analysis: 10 conformance tests fail on all backends due to 4 distinct
bugs. Spec in BACKLOG.md §"Conformance Fixes".  Each item below is one focused
commit. Do them in order — triple-quote fix unblocks the std-ui-extended tests
which are the most numerous.

- [x] **conf-fix-triple-quote-macro** ✓ Landed 2026-06-03 — Fix `preprocessQuotedMacros` in
  `lang/core/src/main/scala/scalascript/parser/Parser.scala`.
  **Root cause**: `skipString` (around line 1476) only handles `"…"` single-quoted
  strings.  When `html"""<div class="${cls}">"""` is processed, it exits after
  the first `""` (empty string boundary), the third `"` starts a new string ending
  at the `"` in `class="`, then `${cls}` is treated as a top-level macro
  entrypoint → `__ssc_macro_error__(…)` → `Undefined: impl` at runtime.
  **Fix** (single file, ~15 lines):
  1. In `skipString`, before the standard single-quote handler, check if `in(i) == '"'
     && i + 2 < n && in(i+1) == '"' && in(i+2) == '"'` → scan forward for the
     matching `"""` closer, skipping all content including `${…}` inside.
  2. The triple-quote section must NOT feed any `${ }` slices to `rewriteQuotedArgs`.
  **Verify**: `bin/ssc run tests/conformance/std-ui-extended.ssc` should pass (no
  `impl` error); also run `std-ui-aggregator`, `std-ui-extended-b/c/d`.  Full
  conformance suite should show 5 fewer failures.
  **Tests**: 5 std-ui-extended* conformance tests + any `html"""…"""` unit tests.

- [x] **conf-fix-mcp-types-import** — ✓ Landed 2026-06-03. `SectionRuntime.runInlineImports`
  preprocesses raw `cb.source` via `Parser.rewriteInlineImports`; `PatternRuntime`
  Term.Select fix for imported enum singletons; JS `genPattern` uses qualifier;
  JS throw for user-defined types emits direct `throw val`; renamed `_restRequireString`
  etc. to avoid collision; `McpError` as plain case class; `mcp-types` PASS [INT] PASS [JS].

- [x] **conf-fix-parsing-stdlib** — ✓ Landed 2026-06-03. Fixed `recovery.ssc` to pass
  `NoContext` as 4th arg to all `runParser` calls; added `NoContext` to core.ssc import
  line; parsing tests restricted to `backends: int`; all 3 parsing-* conformance tests
  PASS [INT].

- [x] **conf-fix-http-client-js-jvm** ✓ Landed 2026-06-03 — Implement `httpGet`/`httpPost`/`httpClient`
  on JS and JVM backends, and gate the test on `requires: HttpClient` to skip in
  network-restricted CI.
  **Root cause**: Only the interpreter has these intrinsics; JS/JVM codegens have
  no runtime support.
  **Changes**:
  1. Add `requires: HttpClient` to `tests/conformance/http-client.ssc` frontmatter.
  2. Add `"HttpClient"` to the `backendFeatures` map in `tests/conformance/run.sc`
     for `int`, `js`, `jvm` (or omit from `js`/`jvm` if not implementing there yet).
  3. **JS backend** (`JsGen.scala`): emit a `_httpGet(url, headers)` helper in the
     JS runtime preamble using Node.js `https`/`http` modules (sync via
     `spawnSync` or async with await). Expose as `httpGet`, `httpPost`, `httpClient`.
  4. **JVM backend** (`JvmGen.scala`): emit `def httpGet(url: String, headers: Map[String, String] = Map.empty)` using `java.net.http.HttpClient` in the JVM preamble (same
     as the interpreter's `BuiltinsRuntime` implementation).
  **Note**: The CI network-access constraint means these tests should use
  `--network` in the GitHub Actions job or be marked as skip-if-no-network.
  **Tests**: `http-client` conformance test on all 3 backends (with network).

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
