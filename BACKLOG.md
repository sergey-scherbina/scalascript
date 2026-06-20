# Backlog

Open and planned milestones — what still needs to be done.
Active in-progress work is in [ACTIVE.md](ACTIVE.md).
Completed work is in [CHANGELOG.md](CHANGELOG.md).

## Open work — what's left (2026-06-15)

This backlog was tidied 2026-06-15: completed milestones moved to `CHANGELOG.md` + git
history; only sections with open `[ ]` items remain below. The full detailed history of the
55 archived milestones is recoverable from git (`git log -p BACKLOG.md`).

## Roadmap — agreed priority order (2026-06-17, with Sergiy)

Drive top-to-bottom, one major theme at a time. **Maven/centralized publication is dead
last — after everything else.**

1. **payments-reorg** ✓ DONE 2026-06-17 — all 24 payment-domain interp plugins moved under
   `payments/` (hybrid: `payments/processors/{spi,stripe,…}` for the 21 providers + SPI;
   `payments/crypto/plugin` + `payments/payment-request/plugin` next to their libs). Build-config
   only (git mv + `file()` paths); packages/services/val-names/aggregate/PluginSpec unchanged →
   user `.ssc` untouched. 5 slices, all compiled; sepa 71 / stripe 23 / crypto 58 tests green;
   installBin stages all plugins; 0 payment dirs left in runtime/std. spec `specs/payments-reorg.md`.
   **→ Next theme: agent-sdk-remainder (#2).**
2. **agent-sdk-remainder** (MINE) — the generic LLM-agent SDK is ~P0–P2 built
   (`runtime/std/agent.ssc`; specs `rozum-agent-{endpoint-pool,schema-derivation,streaming}`;
   4 interp test suites; 5 examples). Remaining: **P3** (embedded transport + MCP-server
   framework so external agents can drive an app), a **consolidated scalascript-side
   `specs/agent-sdk.md`** (mirroring rozum's `docs/specs/agent-sdk.md` + `integration.md` —
   the 3-contract model: ModelClient/AgentLoop/ToolRegistry/SchemaDerivation/EndpointPool/
   Transcript), and broader **conformance** (mock gateway + golden transcripts + live rozum).
   Coordinate via claims — core is shared with the rozum/busi effort.
   **Progress 2026-06-17:** ✓ consolidated `specs/agent-sdk.md` (P0–P2 confirmed shipped).
   ✓ **P3a MCP bridge COMPLETE (both directions)** — `runtime/std/agent-mcp.ssc`:
   `serveAgentToolsMcp(tools, transport)` (expose AgentTools over `mcpServer`) +
   `mcpToolSource(client)` (wrap an MCP server's tools as AgentTools; JSON→Map via the existing
   `jsonParse` intrinsic surfaced as a local extern; jvm/js only). Examples
   `agent-mcp-{server,toolsource}.ssc`; module + both examples `ssc check` OK; pushed. The two
   `ToolResult` types never meet by name → no collision. **Remaining:** (b) round-trip test
   (server+client; needs an MCP transport workable in a jvm/js test — Http is JS-only, Stdio blocks;
   mirror `McpEndToEndTest`); (c) conformance (mock gateway + golden transcripts). P3b Embedded =
   deferred (needs rozum `rozum-embed`).
3. **package-registry** ✓ DONE 2026-06-17 (in-repo CLI; spec was stale) — `ssc search`/`info`/`add`
   over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh` + `--offline`) + seed
   `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. REMAINING (external only): the
   `scalascript/registry` GitHub repo + Pages HTML index + validate/publish CI.
4. **sbt-plugin-finish** — `specs/arch-sbt-plugin.md` remaining surface: front-matter
   `dependencies:`→Coursier, cross-build targets (`sscBackends`), LSP/BSP polish. (Phases 1–4 landed;
   publication of the plugin itself = part of the deferred Maven step.) **← genuine remaining build work.**
5. **metaprogramming-v2** — `specs/arch-metaprogramming-v2.md`. AUDIT 2026-06-17: NOT from-scratch.
   All three phases have working bases (P3 Linker inline expansion; P4 `${impl('x)}`+`'{…}`+interp
   parity+`MacroImpl` IR; P5 runtime `Mirror`+user `derived(m: Mirror)`). Remaining = the "Planned"
   extension bullets, decomposed in spec §4b into small slices — **Track A** ✓ DONE (P5 cross-backend
   derives conformance — A1a/b/c+A2+A3, 2026-06-17; deferred edge cases only), **B** (P4 const-fold:
   **B1+B2 ✓ DONE 2026-06-18**, **B3 ✓ DONE 2026-06-18 — JVM + JS** via `macro-codegen-backends`
   (`MacroCodegen.expand`); Track B complete), **C** (P3 robustness — open, next slice). Days-per-slice,
   not the old "~3 months". **← Track C is the genuine remaining build.**

   *(macro-codegen-backends ✓ DONE 2026-06-18 — JVM + JS; moved to CHANGELOG. The default
   `emit`/`build`/`run` path does not use the Linker — `JvmGen`/`JsGen` inline imports at the
   source/tree level and rely on scalac's own `inline`; the `MacroCodegen.expand` pre-codegen pass
   handles macros for both backends.)*

   *(macro-crossmodule ✓ DONE 2026-06-18 — JVM (Approach B, `expandUnits`+`expandMacrosInBlocks`) + JS
   (Approach A entry-hook over local `.ssc` imports + `genImport` strip); moved to CHANGELOG. Follow-up:
   transitive cross-module macros on JS — the `genImport` strip uses no `baseDir`, so an imported module
   that itself calls a macro from its own imports isn't handled. Rare.)*
6. **deferred perf** — **CLOSED 2026-06-18 (re-measured; see the resolved entries below).**
   `hof-glue-jit-compile` → DEFERRED to the dual-bank `LExpr` VM roadmap (the only remaining lever is whole-fn
   JIT of `combineAll`, gated on that VM + `using`/given JIT support). `vectorize-pure-loop` → WONTFIX-until a
   non-polynomial hot-loop workload appears (targets already bypass the loop via Gauss). `direct-style-eval`
   → WONTFIX (data-disproven: `Pure` ≈16% alloc, dispatch ≈66% which it doesn't touch; 1261-site migration).
7. **other extensibility themes** — **AUDIT 2026-06-17: most are already BUILT; specs were stale.**
   A (Plugin SPI — `BackendRegistry` exists), E (`ssc new`/install — verified 2026-06-19: all bundled
   templates + standalone fixtures covered locally; live publication remains deferred), F (DSL hooks — spec
   "implemented through Phase 4", `InterpolatorRegistry`), H (library modularity — spec "implemented
   through Phase 6", `SsclibManifest`), J (FFI — `GlueClasspathRegistry`/`GlueJsPreambleRegistry` +
   `@jvm`/`@js` + `examples/js-glue-component.ssc`; spec stale at "planned"). **Action: reconcile these
   specs/BACKLOG to reality + verify any residual — NOT a from-scratch build.** **B** (build-time
   registry consolidation): Phases 1 AND 2 BOTH landed 2026-05-29 (spec confirms — `PluginRegistry`/
   `PluginMeta`/`PluginSource` + `BackendRegistry` facade + `SubprocessPlugin` + `RemotePluginInstaller`
   + `BackendRegistryTest`). **Phase 3 is MOOT (reconciled 2026-06-18):** `PluginManifest`/`LocalRegistry`
   are NOT removable "deprecated wrappers" — they are the **implementation** the facade is built ON
   (`BackendRegistry` uses `PluginManifest` for `manifestCache`/`defaultSearchPaths`; `ImportResolver` +
   `PluginCommands` use `LocalRegistry.resolve`/`loadAll` for the `~/.scalascript/registry.yaml`
   download-URL flow). There is nothing to "remove" — they're load-bearing. `isStdPluginInterpreterTest`
   is already gone. So Phase 3 = no action. OPTIONAL Phase 4 (family registries, "only where they remove
   real duplication") remains, demand-driven.
8. **arch-distribution-p3 / Maven Central + sbt Plugin Portal** — **LAST**, only on explicit go.

> **Roadmap reality check (2026-06-17):** the codebase is well ahead of these specs/BACKLOG entries —
> agent-sdk-remainder and package-registry were both found ALREADY BUILT (specs said "planned"), and
> the audit shows A/E/F/H/J are largely built too. The genuine remaining **build** work is narrow:
> **sbt-plugin-finish** (Phase 5 = dep-resolution wiring + Maven publish; publish is Maven-gated),
> **build-registry Phase 3 cleanup + optional Phase 4** (Phases 1–2 landed), **metaprogramming-v2**
> (also NOT from-scratch — all 3 phases have working bases; remaining = spec §4b slice tracks A/B/C,
> days-per-slice), and Maven (last). The high-value next move is RECONCILING the stale specs +
> filling small residuals, not re-building. **Update 2026-06-17:** `package-registry` + its `--offline`
> flag closed; `agent-sdk-remainder` MCP bridge closed; `metaprogramming-v2` audited + spec/SPRINT
> reconciled (it was the last "large from-scratch" entry — it isn't). Substantive remainder = §4b slices,
> sbt Phase 5 (Maven-gated), build-registry Phase 3/4, all needing a steer on which to invest in.

## Architecture Review follow-ups (2026-06-14)

Whole-project architecture survey (231 sbt modules, ~145K LOC main Scala). The project is
mature and low-debt (only 6 TODO/FIXME files, 21 "not yet supported"); these are *refinements*,
not blockers — hence BACKLOG, not SPRINT. Ordered by leverage/tractability. **#1 is the
recommended first pick** (bounded, measurable, compounds with the perf work).

- [x] **module-graph-grouping** ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`).
      197 `lazy val` module defs; thin SPI families (wallet 42, payments 35, walletVault 18, blockchain 13,
      x402 13). Conclusion: the per-impl module boundary **is** the SPI boundary — grouping the families
      either collapses it (shared package/service/artifact, can't take one impl) or is a no-op on the build
      graph (sbt `aggregate` only reduces typing). There is no consolidation that shrinks the graph AND
      keeps the boundaries, which the item's own constraint requires. The cold-build cost is the price of
      the deliberate "one module per SPI impl" design (cf. payments-reorg). **No action**; if a specific
      family is later found to have *true* code duplication, factor the shared part into one library module
      the impls depend on (targeted refactor, not family grouping).

- [ ] **remote-package-registry** (Tier 3, strategic/product) — the plugin ecosystem story is
      local-only (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install`, all LANDED). The SPI
      already supports third-party intrinsic packages (`.sscpkg`), but there's no `registry.scalascript.io`
      to discover/distribute them — deferred "no concrete demand yet". This is the missing piece to
      actually unlock the third-party ecosystem the SPI was built for. Product decision (build when there's
      a real external plugin author), not debt. Spec: extend `specs/arch-build-registry.md`.

## Native Platform follow-ups

- [ ] **std-nfc-packager-adapters** — Consume
      `scalascript.frontend.NativePlatformRequirements` in the SwiftUI/iOS,
      Android, and Web/PWA packagers, then implement real `std.nfc` read/write
      adapters where those targets exist. HOW: keep `runtime/std/nfc.ssc`
      unchanged; make native package generation use `Capability.NfcNdef` to
      emit Info.plist/entitlement, AndroidManifest, and Web permission/model
      declarations; add real device/browser harnesses for `readNdef()` and
      `writeNdef()`; check off the remaining hardware/manifest behavior items
      in `specs/std-nfc.md`. Deferred from `std-nfc-native-adapters` because
      the repo currently has the NFC API and requirements contract but no
      complete Android/Web-NFC packager integration path.

## WASM backend

The WASM backend (`runtime/backend/wasm`, Scala.js → `.wasm` via `scala-cli --js-emit-wasm`) now
handles `@wasm` externs, local `.ssc` import inlining, and quoted macros (2026-06-18). What remains:

- [x] **wasm-effects** — algebraic effects / handlers on WASM. **COMPLETE 2026-06-20.** **FIRST SLICE ✓ DONE 2026-06-18 — effects
      compile AND run on wasm.** The approach (probe-proven): `JvmGen.generateUserOnly` (CPS-lowered code,
      *without* the 300 KB JVM preamble — that preamble's `Thread`/`java.nio` parts are what crash the
      Scala.js linker) + a minimal **Scala.js-linkable effect runtime** (`WasmEffectRuntime` =
      `_Computation`/`_bind`/`_perform`/`_run`/`_handle`/`_handleWithReturn`, the pure-Scala subset of
      `JvmGenRuntimeSources`) emitted in `package _ssc_runtime`, + a re-added `@main` (generateUserOnly
      strips it). `backendWasm` now `dependsOn backendJvm`. Verified: `WasmBackendTest` compiles an effect
      program to a valid `.wasm` AND runs it via node (handler + resume → `hello\nworld`).
      **arithmetic ✓ DONE 2026-06-18 (slice 2a):** `_binOp` (+ `_bigIntOp`/`_bigDecOp`, all pure-Scala /
      Scala.js-linkable) added to `WasmEffectRuntime`; a probe showed `a + b` over `Any`-typed effect-op
      results lowers to `_binOp` — programs doing arithmetic in/around handlers now link + run (test
      'effects with arithmetic in body RUN on wasm' → 40). **`_dispatch` ✓ DONE 2026-06-18 (slice 2b):**
      collection/method calls on `Any` (e.g. `xs.map(..).filter(..).head` in a handler) lower to `_dispatch`;
      added the pure-Scala subset of `_dispatch` + its CPS-aware `_seqMap/_seqFlatMap/_seqFilter/_seqForeach/
      _seqExists/_seqForall/_seqCount/_seqFind/_seqFoldLeft` (+ `_seq`/`_isFree`) to `WasmEffectRuntime` —
      the JVM `getClass.getMethods…invoke` reflection `case _` (which the Scala.js linker rejects) is
      replaced by a clear error. Covers List/String/Option/Map/Set/numeric incl. sortBy/sorted. Test 'effects
      with collection HOFs in body RUN on wasm' → 6. **multi-shot ✓ DONE 2026-06-18 (slice 2c):** did NOT need
      a `_handle` rewrite (the wasm `_handle`'s `resume = (v) => interp(fn(v))` already supports repeated
      resume — same structure as the JVM one). A probe showed the canonical `opts.flatMap(o => resume(o))`
      handler lowers to `_anyFlatMap` + `_dispatch(all,"length")`; only `_anyFlatMap` was missing — added it
      (pure-Scala). Also fixed `usesEffects` to recognise the `multi effect Foo:` form (it keyed on a leading
      `effect`, so multi-shot modules skipped CPS lowering and hit scala-cli raw). Test 'multi-shot effects RUN
      on wasm' (NonDet `{1,2}×{10,20}`) → 4. **cross-module ✓ DONE 2026-06-18 (slice 2d, no code change):** an
      `effect` declared in an imported `.ssc` and only handled in the consumer already works — `generateUserOnly`
      resolves local imports via `baseDir` and lowers the whole graph (`object Log` + `_perform` + inlined
      `shout()`), and `collectSource` inlines the decl so `usesEffects` routes to the effect path. Verified by a
      run test 'cross-module effects RUN on wasm' (lib.ssc declares + performs, consumer handles) → `hello\nworld`.
      **`@main` args/non-Unit edge ✓ DONE 2026-06-20 (`wasm-main-edge`):** effectful WASM derives the user
      `@main` from the AST, preserves a single Scala 3 main parameter clause (including `String*` splicing),
      discards non-Unit returns in the synthetic wrapper, and rejects raw `Array[String]` args before scala-cli
      with a clear diagnostic. **Complete for wasm — common + advanced cases all run** (40 `WasmBackendTest`);
      any dynamic method outside the linkable `_dispatch` subset now errors clearly (was a reflection call on JVM).
      All additive, wasm-only.
- [ ] **`@wasmExport` / `@wasmImport`** — raw WASM ABI export/import. Out of scope **by design** (the
      Scala.js path owns the wasm ABI); would need a direct-emit wasm backend, not the Scala.js one.

## Interpreter Performance — Open Targets

Baselines from `scripts/bench interp` run 2026-06-04 (Javac JIT backend, `-wi 3 -i 5 -f 1`).

- [x] **hof-glue-jit-compile** — **RESOLVED 2026-06-19 with WORKING CODE + MEASUREMENT (not just analysis).
      Slice A SHIPPED to main default-on** (`LITER*` opcodes + `VmCompiler.tryCompileFoldLeft`; compiles a
      `List[Int].foldLeft` so it no longer bails the whole enclosing function; kill-switch `SSC_JIT_FOLDLEFT=0`;
      `JitFoldLeftTest` 17 differential tests + full interp suite 1878 green WITH IT ON). **No measured perf
      win** (interp `foldLeftReusing`/while-JIT already optimize the hot parts) — shipped per decision as a
      capability. **The typeclass case (`typeclassFoldMacro`) IS now sped up — ~19% — but via a SAFE
      interpreter memo, not the VM Slice C** (2026-06-19): a JFR profile showed the cost is ~79% evalCore
      tree-walk of the `summon[M].empty`/`summon[M].combine` sub-expressions, re-evaluated per call. So
      `evalFusedFoldLeft` memoizes the evaluated `(empty, combine)` per call-site keyed by given identity —
      repeat calls skip those sub-expressions. **DEFAULT-ON** (kill-switch `-Dssc.jit.foldtc=0`) — assumes a
      lawful, referentially-transparent monoid `empty`. `JitFoldTcTest` 8 differential tests (incl. polymorphic
      two-given soundness) + full interp suite green WITH IT ON (1839 tests, excl. infra-flaky cross-backend);
      typeclassFoldMacro 1.794 → 1.453 ms/op. The full VM Slice C (type-method opcode +
      hot-path using-guard relaxation) stays unbuilt — disproportionate, and the interp memo gets most of the
      win safely. Detail in `specs/jit-foldleft-compile.md`.
- [x] ~~**hof-glue-jit-compile** (superseded note)~~ — **RESOLVED 2026-06-19 with WORKING CODE + MEASUREMENT.**
      Slice A (inline-lambda `foldLeft` VM compilation) was BUILT + VERIFIED (`LITER*` opcodes +
      `VmCompiler.tryCompileFoldLeft`, flag-gated off-by-default; `JitFoldLeftTest` 12 differential tests +
      1873 interp green) and kept on branch `feature/jit-foldleft-a` (commit `4be211177`), NOT merged —
      because the **measurement showed no win**: `foldLeftLambda` 0.004→0.003 ms/op (within ±0.001 noise),
      since the plain-lambda fold is already fast via `foldLeftReusing`. The only slow case
      (`typeclassFoldMacro` 1.14 ms) needs Slice C, which tracing proved is disproportionate: generic
      `List[A]` (ref-domain fold, no safe unbox), a *type-method* combine (`lookupTypeMethod`/`invokeTypeMethod`,
      new opcode, still a dispatch per element even compiled), + relaxing the type-gate and the
      `usingParams.isEmpty` guards on the hottest call path (`CallRuntime` 137/239/257/284/632). A large
      multi-site hot-path change for a synthetic-bench bounded win — NOT pursued. Detail/build-log in
      `specs/jit-foldleft-compile.md`. Revisit only if a real runtime-typeclass-fold hot loop appears.
- [x] ~~**hof-glue-jit-compile** (prior design note)~~ — **DESIGNED + BUILD-READY 2026-06-19 (`specs/jit-foldleft-compile.md`).**
      Mapped the full "JIT-compile `combineAll`/`foldLeft`" lever against the real VM code: 6 interlocking
      pieces in dependency order, with a safe-first build order (Slice A = inline-lambda `foldLeft`, flag-gated
      off-by-default, differential-tested, measurable on a new `foldLeftLambda` bench → zero given/type-method
      risk; Slice B = `using`+`summon` plumbing; Slice C = type-method `.empty`/`.combine` opcodes → the
      `typeclassFoldMacro` win). KEY de-risking finding: the `using` arg is RESOLVED + APPENDED to the args
      array before invoke (`CallRuntime.bindArgs` ~430), so a compiled `combineAll` just gets the monoid as a
      trailing ref param. HARD WRINKLE: `summon[M].combine`/`.empty` are NOT InstanceV fields — they resolve
      via `lookupTypeMethod(typeName, name)` (DispatchRuntime:3180) + `invokeTypeMethod` (binds `this`+fields),
      so the per-element call is a type-method invocation needing a new `TMLOOKUP` opcode, not a bare CALLREF.
      Deliberately NOT one-shot: the JIT is on every hot path (silent-wrong-result risk), and the payoff is a
      synthetic bench (1.14 ms → ~0.1–0.3 ms). Next: build Slice A as a focused effort. (Prior history below.)
- [x] ~~**hof-glue-jit-compile** (history)~~ — **RESOLVED 2026-06-18 → DEFERRED to the dual-bank `LExpr` VM roadmap
      (closed; stop re-investigating in isolation).** Re-measured on current main: `typeclassFoldMacro` =
      **1.142 ms/op** vs `typeclassFold` = **0.005 ms/op** — the statically-typed fold fully JITs; the 228×
      gap is purely the macro version's per-call given/summon glue. The −10.5% fused fast-path is intact and
      `foldLeftReusing` (CallRuntime:212) already runs the fold as a native loop calling the bytecode-JIT'd
      `combine` per element, so loop+combine are fast. The ONLY remaining lever is whole-function JIT of
      `combineAll`, needing List-iteration opcodes in SscVm + a `foldLeft` recognizer in VmCompiler +
      `using`-param/given-member-access support in the JIT — a large architectural effort gated on the
      dual-bank `LExpr` VM work, risky (JIT is on every hot path). Big win is *possible* but it rides that VM
      roadmap; NOT a bounded slice. History below.
- [ ] ~~**hof-glue-jit-compile** (history)~~ (deep; reframed from `hof-dispatch-cpu-devirt`, investigated
      2026-06-13) — **PARTIAL interp slice landed 2026-06-13** (fused curried
      `List.foldLeft(z)(g)` fast-path in `evalApplyGeneral`: `typeclassFoldMacro` 1.259 → 1.127
      ms/op, **−10.5%**; `FusedFoldLeftTest`). The **full lever is still open.**
      `typeclassFoldMacro` (`combineAll[A: Monoid]` = `xs.foldLeft(empty)(combine)`, 300×).
      Investigation (spec `direct-style-eval-spec.md` §11.3) proved there is **no targeted
      ≥15% *devirt* win**: the inner `combine` is already bytecode-JIT'd (JIT on/off = 1.26 vs
      3.80 ms, 3×), and a fresh JFR CPU profile shows **78% leaf = `evalCore`** self-time (the
      megamorphic `term match`), with *no* devirtualizable callee — `trackPos` no-op and a
      `FunV` JIT-Entry cache (kill the `synchronized` `entryFor` lookup) both measured **0%**.
      The cost is the 300× tree-walk of `combineAll`'s HOF glue (the `foldLeft` Apply + the two
      `summon[Monoid[A]].{empty,combine}` Selects); the fused fast-path shaved the `foldLeft`
      dispatch portion (−10.5%) but the body is still re-interpreted 300×. The remaining lever
      is **compiling that glue**: `combineAll` bails the bytecode/VM JIT on the `foldLeft` HOF
      call (`call:no-compilable-target`, `VmCompiler.scala:521`). Closing it needs List-iteration
      opcodes in `SscVm` + a `foldLeft`-intrinsic recognizer in `VmCompiler` reusing the existing
      `CALLREF` opcode (the dual-bank `LExpr` roadmap, `project_dual_bank_lexpr`) so a
      `foldLeft`-with-a-runtime-monoid compiles to a tight loop. Large architectural effort, not
      a slice. A/B with `scripts/bench interp typeclassFoldMacro` (wall-clock).
      **Re-confirmed 2026-06-17 (perf-followups):** `CallRuntime.foldLeftReusing` ALREADY runs the
      fold as a native Scala `while` over a single reused `ReusableFrame2`, calling the
      bytecode-JIT'd `combine` per element (`JitRuntime.tryRun2`, CallRuntime.scala:221) — so the
      loop AND the combine are already fast. The residual is purely `combineAll`'s PER-CALL glue,
      tree-walked once per call: resolving the `using Monoid[A]` given + the two `summon`-member
      Selects + the `foldLeft` Apply dispatch. The only remaining lever is whole-function JIT of
      `combineAll` itself — which additionally needs **`using`-param + given-member-access support
      in the JIT** (not just a foldLeft recognizer). Confirmed DEFER: too large + too risky (JIT is
      on every hot path) for the ≤15% ceiling; revisit only with the dual-bank `LExpr` VM work.

- [x] **vectorize-pure-loop** — **RESOLVED 2026-06-18 → WONTFIX-until-a-motivating-workload (closed).**
      Confirmed on current main: `jdk.incubator.vector`/`LongVector` is referenced **nowhere** (truly
      unstarted), and `pureCallSum*` are computed by the Gauss closed-form in `walkLinearPoly`
      (EvalRuntime:1835/1872) — they **bypass the loop entirely**, so SIMD would help them 0%. There is no
      non-polynomial hot-loop benchmark that motivates it, and the cost (incubator `--add-modules`, ABI
      churn, tail-loop handling) is real. Do NOT build speculatively; revisit ONLY if a concrete
      non-polynomial pure-arithmetic hot loop appears as a real workload. Original sketch below.
- [ ] ~~**vectorize-pure-loop** (history)~~ — Use `jdk.incubator.vector.LongVector` inside
      `tryCompileWhileLong` to batch 4–8 lanes when the body is pure arithmetic
      on the counter. Expected 4–8× speedup on `pureCallSumIf` (if the recognized
      grammar for `walkLinearPoly` is extended) and similar shapes. `pureCallSum*`
      are now at the algebraic floor via Gauss; vector would help non-polynomial
      cases. Caveats: `--add-modules jdk.incubator.vector`, JDK incubator ABI
      churn, tail-loop handling for non-aligned N. Revisit after extending the
      closed-form recognizer or when a concrete non-polynomial bench motivates it.

## Quality / Contracts / Type System

These items come from the 2026-05-30 project-state review. They are intentionally
ordered to reduce risk: spec and hygiene first, broad implementation only after
the contracts are explicit.

- [x] **direct-style-eval** — **RESOLVED 2026-06-18 → WONTFIX (closed; data-disproven, do not start).**
      Re-confirmed on current main: `Computation.Pure` is constructed at **1261 sites** (even larger than the
      earlier ~530 estimate), and the allocation split is unchanged — `Pure` ≈16%, dispatch machinery ≈66%,
      which a direct-style `eval(...): Value` migration **does not touch**. So the wall-clock ceiling is below
      the ≥15% gate against a 1200-site, high-risk migration. The win these shapes want is JIT/devirt, not
      direct-style. Do NOT start without a real workload where `Pure` dominates a *tree-walked* path. Original
      below.
- [ ] ~~**direct-style-eval** (history)~~ (DEFERRED — data-disproven) — migrate `eval(...): Computation`
      to direct-style `eval(...): Value` to kill per-call `Pure` allocation. **Re-validated
      2026-06-13** (`specs/direct-style-eval-spec.md` §11.1): on the representative tree-walked
      workload `Computation.Pure` is only ~16% of allocation; the dispatch machinery (~66%)
      dominates and `evalDirect` doesn't touch it, so the wall-clock ceiling is below the ≥15%
      gate against a 530-site, high-risk migration. **Do NOT start** without a real workload
      where `Pure` dominates a *tree-walked* path. The win these shapes actually want is
      `hof-dispatch-devirt` (SPRINT) — pursue that instead.

## Architecture & Extensibility Roadmap (v1.x–v2.x)

Cross-cutting improvements to make ScalaScript easier to extend, consume, and
distribute — identified in the 2026-05-28 architectural review.  Ten themes
(A–J), roughly ordered by impact and risk.  Companion plan:
`~/.claude/plans/glowing-swinging-river.md`.

### Theme C — Distribution ecosystem (multi-channel, not Maven-only)

- [ ] **arch-distribution-p3** — First-party Maven Central publication
  (deferred; not queued):
  `project/Publishing.scala`; `io.scalascript` group ID unified; publish
  `scalascript-core`, `scalascript-runtime`, `sbt-scalascript` on tag push;
  sbt Plugin Portal registration. Deferred until Sergiy explicitly asks to
  publish to Maven Central, sbt Plugin Portal, or other official centralized
  repositories.  Spec: `specs/arch-distribution.md §5 Phase 3`.

### Theme D — sbt-scalascript plugin completion

### Theme E — `ssc new` + standalone installation

### Theme B — Build-time registry consolidation

### Theme A — Stable Plugin SPI

### Theme F — DSL platform hooks

### Theme H — Library Modularity

Identified 2026-05-28. Six concrete gaps in the library system: no multi-file
pure-ScalaScript package format, no transitive dep propagation, no access
control, namespace collision risk, no API lifecycle annotations, no versioning
enforcement.  Full analysis in `specs/arch-library-modularity.md`.

### Theme I — Package Registry (discoverability)

Identified 2026-05-28. Without a registry the ecosystem cannot grow: users
cannot find libraries, authors cannot reach users.  Solution: GitHub repo +
GitHub Pages static site, zero server infrastructure, PR-based publishing.
Full spec: `specs/arch-registry.md`.

### Theme J — Lightweight FFI (@jvm / @js + glue.jar)

Identified 2026-05-28. Community libraries cannot call Java or JS APIs today —
only `std/` plugins can.  Two-tier FFI closes the gap without requiring a full
`BackendRegistry` plugin.  Full spec: `specs/arch-ffi.md`.

### Theme G — Metaprogramming v2.x (deferred)

---

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`specs/blockchain-spi.md`](specs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`specs/wallet-spi.md`](specs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

### Phase 5 — blockchain-bitcoin ✓ Landed (2026-05-27)

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

### Phase 7 — blockchain-cosmos ✓ Landed (2026-05-27)

---

## Wallet SPI — Scala.js cross-compile ✓ Sprint complete (2026-05-20)

Spec in [`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`specs/wallet-spi.md`](specs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

### Stage 3 — Strategy + connector cross-compile ✓ Landed (2026-05-20)

### Stage 4 — `wallet-strategy-erc4337` cross-compile ✓ Landed (2026-05-20)

### Stage 5 — `wallet-vault-encrypted` cross-compile ✓ Landed (2026-05-20)

Stage 5a — light up the deferred KDF + AEAD primitives in
`crypto-noble-js`:

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6a — extend `CryptoBackend` SPI with the primitives WC needs
(additive only — no existing-method breakage):

- [ ] **Real browser-WebSocket integration testing** — JS tests
      currently mock `BrowserWebSocket` (Node has no native
      `WebSocket` in the test runtime).  Live integration against
      `wss://relay.walletconnect.com` lands in the future PWA-wallet
      sprint that surfaces WC v2 in an actual browser.

Sprint closure: every wallet-spi-track module that has a JS-relevant
surface now cross-compiles JVM + Scala.js.  All future
`CryptoBackend` implementations are mandated to implement
ChaCha20-Poly1305, X25519, and the Stage 5 AEAD / KDF set in
addition to the original signing / hash / KDF surface — see
[`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md) §6 for
the full SPI checklist a new backend has to cover.

---

## Wallet SPI — key management + dApp connectivity

Spec in [`specs/wallet-spi.md`](specs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

### Phase 2 — Encrypted Vault ✓ Landed JVM + Scala.js core (2026-05-20)

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [ ] WC project-ID open question — still pending; the transport
      accepts a `projectId` argument on both platforms but CI does
      not yet provision one. To resolve before first production
      deployment.

### Phase 5 — Solana DappConnector ✓ Landed (2026-05-27)

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`specs/wallet-spi.md`](specs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

### Phase 8 — MPC Vault

- [ ] FROST-Ed25519 and future MPC variants — deferred until a concrete
      production use case or partner request arrives.

---

## Strategic-review proposals (2026-06-15)

The feature roadmap is built out (729/740 done, 127 conformance cases, ~70 property/fuzz suites,
comprehensive docs). These are the higher-leverage *productization/hardening/enablement* directions.
The two active ones are in SPRINT (`compile-time-at-scale`, `xbackend-property-equivalence`).

- [x] **real-workload-perf** ✓ DONE 2026-06-20 (all three axes have harnesses + baselines) —
      micro-throughput is at floor; this is the real-workload axis.
      **(a) cold-start ✓ DONE 2026-06-20:** built `tests/perf/coldstart/` (pure-bash harness, no
      scala-cli/bloop → can't hang) measuring fresh `ssc run` wall-clock + peak RSS. Baseline ~378 ms /
      167 MB (JVM boot ~36 ms + classloading the 88 MB fat jar dominate). **Cut shipped:** AppCDS in
      `bin/ssc` + `install.sh` (`-XX:+AutoCreateSharedArchive`, auto-created first run, no build step,
      CDS-only — NOT TieredStopAtLevel which would hurt long-running `ssc serve`) → **378 → 182 ms (−51%)
      + peak RSS 167 → 114 MB (−32%)**; opt out `SSC_NO_CDS=1`. GraalVM native binary needs no CDS.
      **(b) steady-state RSS + (c) GC under load ✓ DONE 2026-06-20:** built `tests/perf/serverrss/` (boots
      a real `health-defaults.ssc` server on the JVM interp at `-Xmx512m` + GC log, drives concurrent load,
      samples RSS, reports footprint + start→end drift (leak signal) + GC pauses/time; pure bash, reliable
      teardown). Baseline (20s/4 loops, JDK 21): the interp server settles at **~195 MB RSS, STABLE** —
      ramps ~184→~195 MB then plateaus (no leak), **light GC** (~41 short pauses / 27 ms). Verdict flips to
      GROWING if drift >20%. **All three axes now have harnesses + baselines.** Complements
      `compile-time-at-scale` (the remaining unmeasured axis). Genuine open follow-up: a *long* (minutes)
      leak-hunt run is left to demand (the harness supports `secs=300+`).
- [x] **xbackend-property-equivalence (full suite)** ✓ DONE 2026-06-20. **Broaden:** already complete —
      the generator is at **12 kinds** incl. arith/List/match/enum/String/case-class/Option/Either/closures/
      nested-coll/string-ops/**effects** (the "REMAINING" list was stale); node leg verified 74 programs,
      interp==JS, 0 skipped. **CI-wired:** the `sbt` CI job had only Java+sbt so `CrossBackendPropertyTest`
      SKIPPED (assume node/scala-cli) — added Node.js setup so the interp==JS differential now runs in CI.
      **Made CI-safe first** (see `xbackend-test-hardening`): `ProcTestUtil.runCaptured` gives the subprocess
      runner a hard timeout that actually fires + deadlock-free stream draining, so a wedged scala-cli/node
      fails fast instead of hanging the job. The interp==JVM(scala-cli) leg stays gated (Conformance job
      covers it). Definitive cross-backend guarantee now standing in CI.
- [ ] **registry.scalascript.io (remote package registry)** — the strategic platform move: the SPI +
      `.sscpkg` + `pkg:` resolver + `ssc install` are all built and local-only; a remote registry is the
      missing piece to unlock the third-party plugin ecosystem the SPI was designed for. Product decision
      (build when there's a real external plugin author). Extends `specs/arch-build-registry.md`. (Dup of
      the existing `remote-package-registry` backlog item — consolidate when picked up.)
- [ ] **demand-driven-from-busi** (ongoing, not a discrete task) — the `busi` rozum channel is the live
      testbed and the highest-signal priority source; it is currently quiet. Proactively building one
      comprehensive real app (or asking busi what's painful) surfaces the gaps that matter more than any
      speculative backlog item. Keep sweeping the room per the rozum skill.

## Completed milestones — archived 2026-06-15 (detail in CHANGELOG.md + git history)

- Language Surface — Markdown Frontend from Content
- Codegen-time perf — jvmGen ~100× slower than jsGen (survey 2026-06-14)
- JS Codegen Performance
- Conformance Fixes — cross-backend gaps (2026-06-02)
- Tooling
- UUID Library — v1.65
- Crypto primitives — v1.66 ✓ DONE
- Codebase Maintenance / Architecture Hygiene
- Exact Numerics — BigInt, Decimal, Money (v1.64 ✓ DONE — all phases landed 2026-06; verified 2026-06-14)
- Distributed Runtime (v1.63 planned)
- Distributed Wire Protocol (v1.62 planned)
- Compiler extensibility roadmap
- Recommended implementation sequence
- v0.7 — Reusable libraries and packaging
- v0.13 — Component theming variants
- v1.12 — Typed Algebraic Effects
- v1.51 — Streams with Backpressure
- v1.52 — Deploy to Hostings, Clouds & Kubernetes-like Environments
- v1.53 — Traditional Payment Processors
- v1.60 — Tuple Monoid ✓ Landed 2026-05-28
- v1.61 — Performance & Memory Optimization
- Interpreter performance — next phases (post VM 2a)
- v1.55 — First-class XML / Generic Markup
- v2.1 — Distributed Streams (Beam-style)
- v2.0 — Separate compilation of modules
- Interpreter ergonomics — carried over from v1.1
- Known issues / latent flakes
- CLI — native binary (GraalVM native-image)
- Optimization and modularity roadmap
- Scala ↔ ScalaScript interop — Tiers 1 + 2 landed
- Next wave — post-v1.24 plan
- Beyond
- Speculative — Smart contracts backend
- Speculative — Apache Spark backend
- v1.26 — `sql` fenced code blocks (JDBC)
- v1.27 — browser-side SQL (sql.js / DuckDB-Wasm)
- Infrastructure clients — general-purpose ScalaScript libraries
- x402 — HTTP payment protocol
- MCP × x402 × Wallet — agentic payments
- Micropayment Platform — channel-based fee amortisation for microtransactions
- v1.30 — `@side=client|server` for SQL blocks in full-stack modules
- OpenAPI 3.1
- GraphQL
- v1.48 — SwiftUI Native Frontend (iOS + macOS)
- v1.48.1 — `ssc run` one-command wrapper for SwiftUI targets
- v1.48.2 — `ssc run --target ios` (iOS Simulator)
- v1.48.3 — `ssc run --target ios --device` (real device via ios-deploy)
- v1.48.4 — `ssc package --target ios` → distributable .ipa
- v1.48.5 — `ssc publish --target ios` (TestFlight + App Store via fastlane)
- v1.49 — macOS distribution: notarize + DMG + Mac App Store
- v1.65 — `ssc emit --frontend swiftui` pathway ✓ Landed 2026-06-02
- v1.66 — SwiftUI typed JSON models (`@model` + `FetchJsonSignal`)
- Backend-specific fenced blocks + platform-type ban
- std.fs / std.os / std.process — filesystem, OS & process abstraction
- Requested by busi (real testbed) — 2026-06-09
