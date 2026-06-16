# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Strategic-review proposals (2026-06-15) — the feature roadmap is built out; leverage has shifted from
building features to validating/hardening/enabling what exists. Work top-to-bottom.

Cross-backend collection-perf follow-ups (2026-06-16, after jit-collection-ops-slice2) — the dashboard
(`./bench.sh vector-index array-update lazylist-take`) showed each backend's remaining weak spot. Close them:

- [ ] **rust-mutable-array** — `array-update` is `n/a` on Rust (the corpus assumed Rust "has no distinct
      mutable Array type"). It does: `Vec<i64>`. Implement the `Array(...)` ctor + `a(i)` read + `a(i)=x`
      store in the Rust backend (`runtime/backend/rust`), mapping `Array(0,…)` → a mutable `vec![…]`,
      `a(idx)` → indexed read, `a(idx) = x` → `a[idx as usize] = x`. The Vector path already works
      (`vector-index` 0.66 ms) — mirror it but mutable. Verify `array-update` runs on Rust + the
      cross-backend result matches interp/jvm. Repro: `./bench.sh --backend rust array-update`.

- [ ] **jvm-lazylist-fusion** — JvmGen emits a real Scala `LazyList` for `LazyList.from(s).map(f).take(n).sum`,
      so `lazylist-take` is 5.87 ms (vs interp-JIT 0.058 ms). Apply the SAME pipeline fusion in JvmGen:
      recognize `LazyList.from(start).map(unary)?.take(n).sum` and emit a tight `while` loop over the
      n-element prefix (no lazy cons / thunk alloc). Reuse the recognizer idea from
      `JitHofShape.lazyFromMapTake` (interp). Verify `lazylist-take` drops on jvm + result unchanged
      (cross-backend). Repro: `./bench.sh --backend jvm lazylist-take`.

- [ ] **js-collection-perf** — the JS backend is slow on ALL three: `array-update` 24.8, `lazylist-take`
      8.92, `vector-index` 17.2 ms/iter (vs interp-JIT sub-ms). Speed up JS codegen / runtime for:
      (a) array `a[i]=x` store + read in a hot loop, (b) `Vector` indexed read (tagged-array path),
      (c) the LazyList thunk pipeline (`_lz*` runtime — consider fusing `from().map().take().sum`).
      Profile first to find the dominant cost (likely per-iteration allocation / megamorphic dispatch /
      the `_lz` thunk chain). Verify each workload drops + results match. Repro:
      `./bench.sh --backend js vector-index array-update lazylist-take`.

- [x] **jit-collection-ops-slice2** ✓ DONE 2026-06-16 — finished array / vector / lazy-list JIT on
      the interp bytecode JIT (both backends). Array read + in-place update (`array-update` 1580 →
      0.66 ms ~2400×; `GenCtx.seqLocals` static seq-local tracking + `buildArrayRef`/`arrayUpdateLong`);
      ASM-backend parity for `seq(i)` + `array(i)=x` (the `ssc-asm` column drops too — slice-1's
      "inert ASM" was the shared `looksLongValue` not knowing `seq(idx)`:Long, fixed via
      `JitShapeCtx.isSeqIndexName`); LazyList pipeline fusion (`LazyList.from(s).map(f)?.take(n).sum`
      → native loop, `lazylist-take` 190 → 0.058 ms ~3275× — the gap was LazyList machinery, not the
      arithmetic). JIT-on == off on all three (assembled jar, both backends). spec
      `specs/jit-collection-ops.md`.

- [x] **lazylist-all-backends** ✓ DONE 2026-06-16 — `LazyList` must FUNCTION on all 5 backends like
      Vector/Array do; today it is `n/a` on JS (eager arrays, no `LazyList.from`) and Rust (no
      LazyList). interp/JVM already work. JS: a thunk-based lazy runtime (`_lz*` cons/map/filter/
      take/drop/from/iterate/continually/range + force toList/sum) + dispatch + `_show`
      `LazyList(<not computed>)`. Rust: map the combinators to std lazy iterators (`(n..)`,
      `iter::successors`, `iter::repeat`, `.map/.filter/.take`, `.collect()/.sum()`). Verify
      `lazylist-take` runs on JS+Rust + cross-backend `LazyList.from(1).map(_*2).take(8).toList`
      agrees. spec `specs/lazylist-all-backends.md`.

- [x] **js-supertype-typetest** ✓ DONE 2026-06-15 — Fixed a JS-backend bug (found by busi):
      a type-test against a supertype (sealed trait / parent enum / abstract class) never
      matched a subtype instance, because `genPattern`'s `Pat.Typed` tested an exact
      `_type === 'Trait'` and emitted objects carry only their leaf `_type`. busi symptom:
      every `cardWithHeader` title was dropped in the SPA on all screens (interp was correct →
      `.ssc` tests passed). Fix: `JsGen.subtypeClosureInModule` builds `supertype → concrete
      descendants`; `Pat.Typed` widens a no-tag check to an `_type` OR over that closure.
      Leaf-tag / primitive / `Pat.Extract` paths unchanged. The JS analogue of the interp/JIT
      #1/#3 supertype-type-test fix. **Cross-module follow-up:** the first commit was single-module
      and its single-file test gave false confidence — the trait + subtypes live in an imported
      `package:` module (`nodes.ssc`) emitted by a fresh child `JsGen`, so the closure must
      accumulate across imports (`collectSubtypeEdgesFromModule` + `recomputeSubtypeClosure`,
      folded in per import + propagated to the child gen). Guards `SupertypeTypeTestJsTest` +
      `SupertypeTypeTestXModuleJsTest` (multi-file); `BUGS.md#js-supertype-typetest`; spec
      `specs/js-supertype-typetest.md`.
- [x] **rozum-integration** ✓ DONE 2026-06-15 — Added `std.agent` P0: app-owned
      OpenAI-compatible tool-call loop for stateless rozum gateways over existing
      `std.http` + `std.json`, with explicit JSON schemas, `AgentTool` handlers,
      transcript JSON, `MaxSteps`, non-2xx `Error`, unknown-tool and handler-validation
      tool-error feedback. Added `examples/rozum-agent.ssc`, README links, and
      `AgentSdkInterpreterTest` (7 cases). Verified with
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-integration && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest"`.
      Commits: spec `08f03f18f`, implementation `fce51c2e6`, verify/docs
      `2290e95bf`. ORIGINAL — Add ScalaScript integration with the adjacent Rust `rozum`
      app, following `../rozum/integration.md` and `../rozum/sdk.md`. HOW: read the
      rozum docs first, write and commit `specs/rozum-integration.md`, then implement
      the smallest ScalaScript surface that matches the documented SDK/integration
      contract. Keep any new user-facing APIs under `std.*` and implement required
      intrinsics in a `runtime/std/<feature>-plugin/` plugin, not interpreter core.
      Add a runnable `examples/` script and focused tests. Done when the example and
      relevant sbt tests pass from the worktree with explicit `cd`.
- [x] **compile-time-at-scale** ✓ DONE 2026-06-15 — measured parse/type/jvmGen/jsGen across N=50→6400 defs: frontend LINEAR, codegen ~linear+mild tail, NO O(n²); 6400-def module compiles <0.5s. Added CompileScaleBench guard + docs/compile-scale-findings.md. ORIGINAL — every compile/codegen bench uses TINY inputs (6-line
      programs; jvmgen-codegen-time −94% was on those). Compile-time at REAL scale is UNMEASURED. HOW:
      build a large-program compile-time bench (generate a big synthetic `.ssc` — many defs/blocks/types
      and/or a deep import chain), profile parser/typer/normalize/codegen scaling vs input size, find any
      O(n²) hotspot. Fix if a clear one exists (A/B); else ship the scaling profile + a guard bench as the
      deliverable. This is the real perf frontier now that micro-perf is at floor.
- [x] **xbackend-property-equivalence** ✓ SLICE 1 DONE 2026-06-15 — CrossBackendPropertyTest generates core Int programs (arith/val/if/fns) from seeds + asserts interp==JS(node) over 40 + interp==JVM(scala-cli) over 5; all agree (core holds). Harness extensible; broadening (ADTs/match/effects) + overflow dimension = BACKLOG full-suite. ORIGINAL (slice 1) — harden the core "one source, many targets" guarantee:
      a property/fuzz test that GENERATES small programs over a core expression/stmt subset and asserts
      `interp output == jvm == js`. Start bounded (arithmetic, lets, if/match, simple functions); reuse the
      existing 127-case conformance harness + the ~70 property/fuzz test files as scaffolding. Each found
      divergence is a real cross-backend bug. Grows the conformance guarantee from fixed cases to generated.

_See BACKLOG.md "Strategic-review proposals" for the broader/deferred items (real-workload perf, registry, demand-driven)._ Tidied 2026-06-15 — the sprint was a backlog of already-completed work (autonomous
batches, busi fixes, JIT stages, rust/std milestones, the 4 architecture refinements, the
effect-vm-continuations performance thread). All of it has shipped and is recorded in
`CHANGELOG.md` + git history. Pick the next item from `BACKLOG.md` (open `[ ]` items) or take
a fresh direction.
