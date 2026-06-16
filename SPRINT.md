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

- [ ] **jit-collection-ops-slice2** — follow-ups to the Vector-index JIT (slice 1 DONE: `seq(i)`
      read JITs on the default JavacJitBackend, `vector-index` 1056→1.14 ms ~925×; spec
      `specs/jit-collection-ops.md`). Remaining:
      1. **Array read + in-place update** (`array-update` workload, 1580 ms tree-walked): JIT
         `a(i)` read + `a(i)=x` store. Needs (a) slot-type tracking so the JIT knows a local val
         holds an `ArrayV`, and (b) `JitRefDispatch.arrayUpdateLong(recv, idx, value)` + `walkStat`
         recognition of `Term.Assign(Term.Apply(a,[idx]), rhs)`. More involved than slice 1 (local +
         mutation, not a global read).
      2. **ASM-backend parity for `seq(i)`**: the Javac backend JITs it; `AsmJitBackend` bails (it
         tracks top-level-val globals differently — the `walkRef`/`isSeqRefName` discrimination that
         works on Javac doesn't engage on ASM). The inert ASM emission was reverted; the `walkRef`
         Vector/Array-global parity was kept. Investigate ASM global tracking and emit the
         `INVOKEVIRTUAL seqIndexLong` path so the `ssc-asm` dashboard column also drops.
      Note on **interp LazyList JIT**: the interp tree-walks the per-iteration lazy pipeline
      `from().map().take().sum` (not a simple loop-op to bytecode-JIT) — that interp-perf gap is a
      lower-priority follow-up, NOT a correctness issue. Verify each via the assembled jar
      (`sbt installBin`) + `./bench.sh --backend ssc <wl>` + JIT-on==off; clean-verify any JIT/codegen
      suite failure (stale-incremental compile gives false fails).

- [ ] **lazylist-all-backends** (in progress) — `LazyList` must FUNCTION on all 5 backends like
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
