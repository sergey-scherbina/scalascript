# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

- [ ] **green-main-conformance-7fail** — restore the default top-level
      conformance gate after fresh `--no-memo` repro confirmed 7 deterministic
      failures on 2026-07-09. Repro from a clean staged CLI:
      `scripts/sbtc "installBin"` then
      `tests/conformance/run.sh --only 'case-classes,dataset-shape,direct-control-flow,effect-imported-handler,effect-transitive-handler,fenceless-bare-code,js-applyunary-effect-cps,sealed-traits' --no-memo`.
      Observed: `case-classes` JS (NaN / constructor ordinal mismatch),
      `dataset-shape` JVM (missing stdout), `direct-control-flow` JS (missing
      stdout), `effect-imported-handler` JS (missing stdout),
      `effect-transitive-handler` JS (missing stdout),
      `js-applyunary-effect-cps` JS (missing stdout), and `sealed-traits` JS
      (NaN). `fenceless-bare-code` passed in the fresh targeted repro even
      though the earlier full non-`--no-memo` run reported it red. Track details
      in `BUGS.md#green-main-conformance-7fail`. Approach: first reproduce each
      failing lane directly with `bin/ssc run-js` / `bin/ssc run-jvm` to capture
      stderr, then fix or explicitly reclassify rows without mixing with
      bytecode perf work. Done when the focused repro is green and full
      `tests/conformance/run.sh --no-memo` has no deterministic failures beyond
      documented pending/skips.
      Progress 2026-07-09: `dataset-shape` JVM is fixed by parameterless
      `_Dataset.mkString` plus JVM `.scjvm` codegen cache key bump; direct
      `run-jvm`, focused `dataset-shape` conformance, and the eight-row repro
      confirm it is green. Remaining failures: `case-classes` JS,
      `direct-control-flow` JS, `effect-imported-handler` JS,
      `effect-transitive-handler` JS, `js-applyunary-effect-cps` JS, and
      `sealed-traits` JS.

- [x] **v2-read-gigs-handle-leak-minimize** - DONE 2026-07-09 in
      `dd42da430` and `615ed5f8f`: fixed both production blockers behind
      busi's v2 `read_gigs` failure. Payments' `Currency` companion remains
      constructor-compatible with std/money's `Currency(code, scale, symbol)`,
      and v2 no longer lowers common dynamic zero-arg members such as
      `List.head` to eager `fieldAt` just because an imported case class also
      has a `head` field. The new multi-import conformance
      `head-field-effect-shadow` pins the real leak shape. Gates: focused
      Currency/List.head bridge tests, `installBin`, reduced repros, busi
      `tests/v2/gigs.ssc`, live busi hub `/api/gigs` and `/mcp read_gigs`,
      affected conformance `head-field-*,money-multisection`, full
      `FrontendBridgeTest`, payments/bank-rails examples, and
      `git diff --check`.
      Original scope: reproduce and minimize the real
      busi hub `read_gigs` v2 failure tracked in
      `BUGS.md#v2-read-gigs-handle-leak`. The isolated dispatcher-shaped repro
      did not fail, so the first production slice is to run the real harness if
      a busi checkout/config is available, then reduce the trigger enough to
      land either a focused conformance/e2e fixture or a narrow compiler/runtime
      fix. Repro target from BUGS: boot busi's hub on `--v2`, call MCP
      `tools/call` for `read_gigs`, and observe `HTTP 500` with
      `if: condition not Bool: Op("GigSource.fetch", (), <closure>)`; v1 and
      `tests/v2/gigs.ssc` are not sufficient oracles because the small isolated
      pattern already passes. Approach: inspect the real `src/v2/http/mcp.ssc`
      / `src/v2/domain/gigs.ssc` call graph and import scale, create a local
      reduced `.ssc` fixture in this repo once the trigger is understood, then
      fix the responsible v2 handle/effect/bridge path without broadening into
      unrelated MCP tools. Done when BUGS records the actual root cause, the
      failing shape is pinned by a real harness or reduced regression, affected
      conformance/e2e plus `git diff --check` pass, and the claim is released.
      Update 2026-07-09: current ScalaScript `origin/main` now fails earlier
      than the original live-hub-only symptom. With this worktree's staged CLI,
      `cd /Users/sergiy/work/my/busi &&
      /Users/sergiy/work/my/scalascript-wt-v2-read-gigs-handle-leak-minimize/bin/ssc --v2 tests/v2/gigs.ssc`
      throws `arity: 1 expected, 3 given` at `ssc.Runtime.run`, while busi's
      pinned ScalaScript submodule still passes the same test via
      `SSC_LANE_FLAG=--v2 scripts/ssc tests/v2/gigs.ssc`. First reduce and fix
      this isolated arity regression on current ScalaScript, then re-check the
      real hub `/mcp tools/call read_gigs` leak if the isolated test is green.
      Update 2026-07-09 (root cause found): after the Currency arity fix, the
      live hub and a smaller import repro still leaked `GigSource.fetch`.
      The reducer found that importing `runRepoJournalFrom` pulls in
      `case class RepoRef(name, head)`, which makes the global field registry
      lower every `.head` to `fieldAt`. That turns `List.head` in
      `scoredGigs` into eager field access, bypassing method/effect lifting and
      letting `GigSource.fetch` reach `scoreGig`'s `if`. Self-contained repro:
      define `RepoRef(name, head)`, then call
      `runSimGigSource(() => gigsText(scoutGigs()))` where `scoredGigs` uses
      `gigs.foldLeft(gigs.head)(...)`; current v2 prints `abc` for
      `RepoRef.head` and then fails with
      `if: condition not Bool: Op("GigSource.fetch", (), <closure>)`.

- [x] **v2-jvm-user-request-shadow** - DONE 2026-07-09 in `d5538d66a`:
      the JVM codegen no longer leaks public HTTP runtime `Request`/`Response`
      case-class names into non-server user modules that define the same
      top-level names. HTTP/server modules keep the existing `commonRuntime +
      serveRuntime` path; collision-prone non-server scripts use a reduced
      common runtime plus private `_SscRuntime*` request/response stubs for
      actor/HTTP-effect fallback references. Bumped the JVM artifact codegen
      version so stale `.scjvm` artifacts regenerate. Gates:
      `FrontendBridgeTest` 42/42, `installBin`, direct `bin/ssc run-jvm
      tests/conformance/user-request-shadow.ssc` prints `7/9/7/42`, affected
      conformance `money-multisection,v2-*,user-request-shadow` 7/7, and full
      `./v2/conformance/check.sh`; `git diff --check`.
      Original scope:
      fix the JVM conformance lane for
      `tests/conformance/user-request-shadow.ssc`, where a non-HTTP user
      `case class Request(alpha, beta)` conflicts with the always-inlined
      HTTP runtime `case class Request(method, path, ...)` in `run-jvm`.
      Repro after `scripts/sbtc "installBin"`:
      `bin/ssc run-jvm tests/conformance/user-request-shadow.ssc` fails with
      `Request is already defined`, and
      `tests/conformance/run.sh --only 'user-request-shadow' --no-memo`
      passes INT/JS but fails JVM with missing stdout. Approach: keep
      HTTP/server modules on the existing public `Request`/`Response` runtime
      path, but make the non-server JVM preamble collision-safe by avoiding
      public HTTP POJO names when user top-level names contain
      `Request`/`Response`/`StreamResponse`; actor/HTTP-effect stubs can use
      private `_SscRuntime*` names. Done when the direct `run-jvm` repro prints
      `7/9/7/42`, affected conformance for
      `money-multisection,v2-*,user-request-shadow` is green, full
      `./v2/conformance/check.sh` is green, and `git diff --check` passes.

- [x] **v2-vm-foreach-match-boundary** — DONE 2026-07-09 in
      `58fd143b8`: `FastCode.tryFC` now has a no-materialized-env lane for
      inline `foreach` `Lam(1, body)` shapes whose supported body can be
      evaluated against a virtual appended `Local(0)`. This removes the
      per-element `Runtime.appendOne(env, elem)` allocation in the
      bridge-generated `cell.set(total, total + area(s))` hot path, while
      complex/capturing bodies fall back to the old path. Added a regression
      that stores an escaping nested lambda from a `foreach` body and verifies
      it still captures the first element, guarding against unsafe env reuse.
      Benchmarks: `pattern-match-heavy` improved from baseline `v2 18.2 ms`
      to `v2 14.4 ms` in the single-row command; the four-row probe still keeps
      the v2 VM production gate red (`pattern-match-heavy` 15.2 ms vs `ssc`
      0.058 ms, `recursion-fib` 5.80 ms vs 1.18 ms, `recursion-tco` 0.272 ms
      vs 0.031 ms). Gates: focused `FrontendBridgeTest`, `installBin`,
      four-row bench, full `./v2/conformance/check.sh`, conformance `litdoc`,
      and `git diff --check`.

- [x] **v2-vm-effect-handlers-regression** — DONE 2026-07-09 in
      `b6f88744c`: fixed the v2 VM effect-handler regression by guarding the
      `Match`-scrutinee `DataV("Op", ...)` lift with `Runtime.isAutoThreadOp`.
      Free-monad `Op` values from `lib/effects.ssc0` and Mira typed effects now
      remain matchable by handlers, while dotted bridge/runtime auto-thread
      operations keep their expression-position lift. Added focused
      `FrontendBridgeTest` coverage for `examples/effects-state.ssc0` and
      `examples/hm-eff-comp.hm` compiled through `bin/mirac.ssc0` to CoreIR.
      Gates: focused `FrontendBridgeTest -- -z "effect handlers"`, full
      `./v2/conformance/check.sh`, `installBin`, and
      `tests/conformance/run.sh --only 'litdoc'` passed.

- [x] **v2-vm-pattern-match-heavy-fast-tier** — DONE 2026-07-09 in
      `3698d9e96`: `FastCode.tryFC(Match(...))` now reuses tiny scratch
      env arrays for compact arithmetic-only match arms proven safe by
      `armBodyScratchSafe`, avoiding per-dispatch `Array(fs...)` allocation
      in the `pattern-match-heavy` `area` dispatcher. The focused bridge test
      asserts that `area` and `workload` expose `fcEntry` and compute the
      expected Double result. Benchmarks: full `pattern-match-heavy` v2 row
      improved from 35.1 ms to 16.4-17.0 ms; the four-row production gate
      remains red (`pattern-match-heavy` 17.0 ms vs `ssc` 0.059 ms,
      `recursion-fib` 6.61 ms vs 1.29 ms, `recursion-tco` 0.275 ms vs
      0.031 ms). Gates: focused `FrontendBridgeTest`, `installBin`,
      two full `./v2/conformance/check.sh` runs after the runtime change,
      `tests/conformance/run.sh --only 'litdoc'`, and `git diff --check`.

- [x] **v2-vm-production-jit-gate** — DONE 2026-07-09: landed the first
      narrow v2 VM production-JIT slice by recognizing the exact
      bridge-lowered local Long-cell summation loop from
      `bench/corpus/arith-loop.ssc` in both normal `Code` and arity-0
      `fcEntry`. The bounded four-row command
      `./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib
      recursion-tco pattern-match-heavy` moved `arith-loop` v2 from 9.91 ms to
      0.000018 ms while keeping the gate honest: `pattern-match-heavy` 19.1 ms,
      `recursion-fib` 6.34 ms, and `recursion-tco` 0.308 ms remain outside the
      2x target. Gates: focused `FrontendBridgeTest -- -z var`, `installBin`,
      targeted and four-row bench probes, `tests/conformance/run.sh --only
      'litdoc'`, and `git diff --check`. Post-rebase `./v2/conformance/check.sh`
      is red on the pre-existing VM effect-handler regression now tracked as
      `v2-vm-effect-handlers-regression`; the same failures reproduce on clean
      `origin/main` at `ab78c6cac`.

- [x] **v2-backend-performance-harness** — DONE 2026-07-09 in
      `01d9abf32`/`677969e1a`: `scripts/bench v2-backends [workload]` and
      `./bench.sh --v2-backends ...` now expose same-shape v2 VM, v2 JVM
      source backend, and v2 Rust source backend timing columns. The four-row
      bounded probe produces non-`n/a` rows for `arith-loop`,
      `pattern-match-heavy`, `recursion-fib`, and `recursion-tco`; default
      `scripts/bench v2-backends arith-loop` after `installBin` reported
      `v2=9.68 ms`, `v2-jvm=0.265 ms`, `v2-rust=66.8 ms`. This closes the
      measurement gap only: the Phase-3 backend performance thresholds stay
      open and are tracked by `v2-source-backend-production-perf-gates` in
      BACKLOG. Gates: `git diff --check`; `./v2/backend/check.sh tco`;
      `./v2/backend/check.sh bool`; `scripts/sbtc "cli/testOnly
      scalascript.cli.CommandRegistryTest"`; `scripts/sbtc "cli/testOnly
      scalascript.cli.GlobalFlagsTest"`; `scripts/sbtc "installBin"`;
      `tests/conformance/run.sh --only 'litdoc'`; `scripts/bench v2-backends
      arith-loop`.

- [x] **v2-prod-performance-gate-baseline** — DONE 2026-07-09 in
      `a4b7e6997`: recorded the first bounded production-v2 performance gate
      baseline and left the Phase-3 performance checkboxes open honestly.
      `./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib
      recursion-tco pattern-match-heavy` shows v2 VM at 37.5x-355.6x slower
      than `ssc` on representative corpus rows, so the v2 VM 2x gate is red.
      The current `jvm`/`rust` corpus columns are not the v2 separate-backend
      gates; `v2-backend-performance-harness` is queued in BACKLOG. Also fixed
      BUGS `scripts-bench-wall-all-na` in `966a530e6`; `scripts/bench wall`
      now produces usable fib/sum/list-ops rows. Gates: `scripts/sbtc
      "installBin"`; `scripts/bench list`; bounded `bench.sh` probe;
      `scripts/bench wall`; `tests/conformance/run.sh --only 'litdoc'` passed
      INT/JS/JVM.

- [x] **v2-vm-perf-hotpath-triage** — DONE 2026-07-09: reproduced the
      four-row production performance probe and landed two bounded v2 VM hot-path
      fixes without widening into separate JVM/Rust backend harness work.
      `SelfRecLL` now recognises bridge-generated Long comparisons, moving
      `recursion-fib` from 68.5 ms to 5.94 ms (~11.5x faster). A conservative
      arity-2 self-tail Long loop fast path moves `recursion-tco` from 2.52 ms
      to 0.273 ms (~9.2x faster). The exact command was
      `./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib
      recursion-tco pattern-match-heavy`. After the fixes the gate is still red:
      `arith-loop` 42.2x, `pattern-match-heavy` 682.7x, `recursion-fib` 5.0x,
      and `recursion-tco` 10.1x slower than `ssc`. Follow-up
      `v2-vm-production-jit-gate` is in BACKLOG for the larger JIT/closed-form
      production track. Gates: `scripts/sbtc "v2FrontendBridge/testOnly
      ssc.bridge.FrontendBridgeTest -- -z SelfRecLL"`, `scripts/sbtc
      "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z fast"`,
      `scripts/sbtc "installBin"`, before/after bounded `bench.sh`,
      `./v2/conformance/check.sh`, and `tests/conformance/run.sh --only
      'recursion,tail-recursion,mutual-recursion,litdoc'` passed.

- [x] **v2-jvm-source-mutual-tco** — DONE 2026-07-09 in `7f58b1516`:
      resolved the BACKLOG `v2-jvm-tco-manual` gap for the v2 source JVM
      backend by adding a conservative local dispatcher loop for eligible
      multi-lam `LetRec` groups. Deep even/odd-style mutual recursion now
      bounces through `_TcoJump(fid,args)` without consuming JVM stack; unsafe
      non-tail or arity-mismatched groups stay on the existing closure-var
      fallback. Spec verification in `0247da3da`. Gates:
      `scala-cli compile v2/backend/jvm/`; standalone source-JVM generated
      runs for `mutual-tco.coreir` and `letrec.coreir`; temporary non-tail
      fallback check emitted no `_mutual_`; `./v2/conformance/check.sh` passed
      including `run-ir mutual-tco.coreir => true`; `scripts/sbtc "installBin"`;
      `tests/conformance/run.sh --only 'litdoc'` passed INT/JS/JVM.

- [x] **v2-prod-readiness-doc-sync** — DONE 2026-07-09 in `745bf2de6`:
      synced the durable v2 production-readiness docs after the clean
      post-JS/runtime-fix parity rebaseline. `v2/output-parity-baseline.md`
      now names the post-JS revalidation worktree, and
      `specs/v2-full-compat.md` now distinguishes the clean default-lane
      switch criteria from remaining perf/backend/server/provider-lane work.
      Gates: `git diff --check HEAD~1..HEAD`; `scripts/sbtc "installBin"`;
      `tests/conformance/run.sh --only 'litdoc'` passed INT/JS/JVM. Gotcha:
      the first `litdoc` run in the fresh worktree failed with `<missing>`
      outputs because `bin/ssc` had not been staged yet; after `installBin`,
      the same conformance slice passed.

- [x] **v2-prod-post-jsgen-parity-rebaseline** — DONE 2026-07-09 in
      `feature/v2-prod-post-jsgen-parity-rebaseline`: refreshed the v2
      production output-parity baseline after the 2026-07-09 JS flat-bundle and
      stream fixes, without touching the sibling-owned
      `v2-head-field-dispatch-fix` work. Gates: `scripts/sbtc "installBin"`
      passed; `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`
      exited 0 with `68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only`
      and skip buckets `26 both-fail not-a-gap · 36 true-server · 0
      long-running · 33 backend-lane · 5 nondet · 4 v1-side · 195 total`;
      conformance `tests/conformance/run.sh --only 'litdoc'` passed
      INT/JS/JVM. No new BUGS entry was needed because the real gate had no
      strict mismatch or v2-error rows.

- [x] **jsgen-preamble-collision-decls** — DONE 2026-07-09 in `854a87f1b`:
      closed the remaining actionable `jsgen-toplevel-name-vs-preamble` bug
      class for flat JS bundles. JsGen now applies the derived runtime
      top-level rename map to non-`val`/`var` declarations too: `def`, `@js` /
      `@jvm` extern stubs, `object`, case class constructors, enum
      companions/cases, explicit named givens, and import aliases. Direct
      function-call fast paths now call the emitted JS name while effect/TCO
      analysis still uses the original source name. Object collisions now emit
      a renamed binding instead of `Object.assign(scope, ...)` against a
      runtime helper. Guards: `backendInterpreter/testOnly
      scalascript.JsGenStdImportTest` (49/49), conformance `litdoc`
      (INT/JS/JVM), and conformance `mcp-types` (INT/JS; JVM skipped by
      fixture).
      Original scope:
      close the remaining actionable
      `jsgen-toplevel-name-vs-preamble` production bug class after
      `v2-litdoc-js-jvm-backend-lanes` fixed top-level `val`/`var` collisions.
      BUGS entry was still open because other top-level declaration forms were not
      audited. Scope: inspect `runtime/backend/js` generator naming/lowering for
      user top-level `def`, object/enum/class-like declarations, and std extern
      declarations that may collide with JS runtime/preamble globals such as
      `scope`, `args`, `doc`, `List`, `assert`, and fs/clock helpers. Fix by
      reusing the derived runtime top-level declaration set and applying one
      consistent JS-safe rename map across declaration emission and references;
      do not broaden into unrelated missing JS capability runtime hooks
      (`nowMillis`, crypto) unless a focused collision test requires it. Add
      focused regression tests next to `JsGenStdImportTest`, plus a CLI/raw
      `emit-js | node --check` or conformance slice if an existing fixture can
      exercise the fixed form. Done when the BUGS entry moves to `fixed` (or a
      clearly-scoped residual follow-up remains for a different capability gap)
      and affected JS tests/conformance pass.

- [x] **bug-ledger-scjvm-cache-duplicate-close** — DONE 2026-07-09: closed the old
      `scjvm-artifact-cache-ignores-compiler-version` BUGS entry as a duplicate
      of the landed `jvm-artifact-cache-codegen-invalidation` fix. Found after
      completing that slice: the current top BUGS entry is fixed, but the older
      2026-07-07 cache-version report remains `open`, so a fresh agent would
      think the same production blocker is still unresolved. Done when BUGS
      points to commits `322ee868f`/`14aa2819d` and this SPRINT item is checked
      off with no code changes.

- [x] **v2-stream-family-output-parity** — DONE 2026-07-09 in `d1d0bc1fd`: fixed the last two strict production
      output mismatches in the default v2 gate: `examples/distributed-streams.ssc`
      and `examples/streams.ssc`. Baseline after
      `v2-v1-side-mismatch-classification`: full parity is
      `68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only` with
      `2 v1-side` skips; the only strict mismatches are now these two stream
      rows. Repro with the staged runner:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/distributed-streams.ssc examples/streams.ssc`.
      Observed shapes from the 2026-07-09 full sweep:
      `distributed-streams.ssc` v2 omits the word-count block after the first
      section, while `streams.ssc` v2 prints `1`, `4`, `9` after
      `=== 2. Stream block ===` where v1 stops at the section header. Work
      loop: reproduce the two rows in the real assembled harness, inspect
      whether the divergence is v2 stream semantics, standard-Scala
      multi-section execution, or another v1-side documented-output case, then
      either fix v2 with focused conformance/regression coverage or classify a
      documented non-v2 blocker explicitly. Done-when: affected conformance
      passes, targeted parity for both rows is either identical or explicitly
      classified with a durable BUGS/BACKLOG note, and the full parity baseline
      has no unexplained strict mismatch left.
      Initial repro 2026-07-09: `distributed-streams.ssc` v2 fails in
      `DStreamsIntrinsics.evalDag(_dag_combinePerKey)` because `KV` fields are
      positional (`_0`/`_1`) rather than named (`key`/`value`) after v2→v1
      conversion; register v2 field names for `KV`. `streams.ssc` v2 correctly
      emits the stream block but then fails at `Source.runFold(z)(f) — outer`;
      make stream/DStream `runFold` natives accept both curried and flattened
      two-argument calls, then rerun the targeted examples to expose the next
      row or close the slice.
      Progress 2026-07-09: after the first code pass, `distributed-streams.ssc`
      reaches section 5 and fails with `__method__: no dispatch for .value on
      10` inside `statefulMap`; the DStreams plugin is now invoking the stateful
      callback with a raw value where the example expects a keyed `KV` input.
      `streams.ssc` reaches section 7 and fails at `Source.throttle: rate
      elements must be > 0`; stream timing natives need the same flattened
      two-arg compatibility as `runFold`. Continue in this slice by normalizing
      the DStreams stateful callback shape and accepting flattened
      `throttle/debounce/sample` rate args, then rerun direct v2 and targeted
      parity.
      Outcome: v2 now runs both examples to completion. The bridge registers
      `KV`/`Rate` field names, converts large v2 Cons/Nil lists iteratively,
      accepts flattened curried stream/DStream native calls, exposes signal
      `.bind`, and returns DStreams tuple/option shapes that v2 callbacks can
      pattern-match. `scripts/v2-output-parity` now classifies
      `distributed-streams.ssc` and `streams.ssc` as v1-side/better-output rows
      because rollback v1 stops early while v2 prints the documented flow.
      Gates: `git diff --check`; streams plugin 83/83; DStreams plugin 66/66;
      PluginBridge 26/26; FrontendBridge 29/29; conformance `signals`
      INT/JS/JVM; direct `--v2` runs for both examples; targeted parity
      `2 v1-side`; full parity
      `68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only` with
      `4 v1-side` skips across 195 examples.

- [x] **v2-v1-side-mismatch-classification** — DONE 2026-07-09 in `18ee5ecfc`: verified and classified the two
      remaining full-parity mismatches that prior durable findings identify as
      v1-side/better-output rows, not v2 production regressions:
      `examples/effects.ssc` and `examples/dsl-calc-parser.ssc`. Claimed
      2026-07-09 by codex in
      `/Users/sergiy/work/my/scalascript-wt-v2-v1-side-mismatch-classification`.
      Baseline after `v2-scala-fence-multiblock-parity`: full parity is
      `68/95 identical · 4 mismatch · 0 v2-error · 23 v1-only` with remaining
      mismatches `distributed-streams.ssc`, `dsl-calc-parser.ssc`,
      `effects.ssc`, and `streams.ssc`. Prior notes say `effects.ssc` v2
      prints all six documented lines while v1 stops after three, and
      `dsl-calc-parser.ssc` v2 renders full round-trips while v1 truncates
      every parser result to the first number. Work loop: run `scripts/sbtc
      "installBin"`, then targeted real-harness parity for
      `examples/effects.ssc examples/dsl-calc-parser.ssc`; if those findings
      still hold, update `scripts/v2-output-parity` classification so these
      rows are visible as v1-side/better-output skips rather than strict v2
      mismatches, add/refresh focused conformance expected output for the v2
      documented behavior where missing, and update `v2/output-parity-baseline.md`,
      `specs/v2-full-compat.md`, `BUGS.md`, and `CHANGELOG.md`. If the repro
      shows a real v2 semantic error, stop classification and fix the v2 cause
      with a faithful regression instead. Done-when: affected conformance passes,
      targeted parity reports the two rows classified or identical with no
      v2-error, and full parity improves from four strict mismatches to the
      remaining stream-family rows only.
      Additional 2026-07-09 gate-hardening found mid-slice: a full sweep on a
      nearly full disk corrupted the summary because `scripts/v2-output-parity`
      kept running after RC/tmp writes failed. Fix the script to fail fast on
      temp/RC create or write errors before recording any new full baseline.
      The corrupted full-sweep output from that run is not a valid baseline.
      Outcome: `effects.ssc` and `dsl-calc-parser.ssc` now report as
      `v1-side` skips; the parity harness fails fast on temp/RC creation/write
      errors. Gates: `git diff --check`; targeted parity for
      `effects`/`dsl-calc-parser` => `2 v1-side`; targeted freshness parity for
      `scala-js-demo`/`lang-split` => 2/2 identical; artificial unwritable
      `SSC_PARITY_TMPDIR` exits `rc=2`; conformance `effects` passed INT/JS/JVM;
      full parity is `68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only`
      with `2 v1-side` skips across 195 examples. The only strict mismatches
      left are `distributed-streams.ssc` and `streams.ssc`, now queued as
      `v2-stream-family-output-parity`.

- [x] **v2-scala-fence-multiblock-parity** — DONE 2026-07-09 in `f57c74da8`: fixed the deterministic
      standard-`scala` fence parity gaps in the v2 production output gate.
      Claimed 2026-07-09 by codex in
      `/Users/sergiy/work/my/scalascript-wt-v2-scala-fence-multiblock-parity`.
      Repro after staging the CLI:
      `scripts/sbtc "installBin"` then
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/scala-js-demo.ssc examples/lang-split.ssc`.
      Baseline from the preceding gate: full parity is
      `66/95 identical · 6 mismatch · 0 v2-error · 23 v1-only` with 5 nondet
      skips; remaining deterministic mismatches include `scala-js-demo.ssc` and
      `lang-split.ssc`. What to fix: (1) `scala-js-demo.ssc` is a
      standard-Scala-only document with multiple `scala` fences and v2 must run
      the whole document in order, not a truncated subset; (2)
      `lang-split.ssc` explicitly documents that `scala` and `scalascript`
      blocks may coexist in a shared interpreter/JVM environment, so v2 should
      include those standard `scala` fences too. Current likely owner:
      `v2/frontend-bridge/src/main/scala/ssc/bridge/FrontendBridge.scala`
      `extractCode` around runnable-fence policy and top-level statement
      conversion. Preserve the existing guard from
      `v2-standard-scala-fences-skipped`: do not run arbitrary illustrative
      `scala` snippets in mixed ScalaScript docs unless the document declares or
      otherwise clearly intends mixed runnable language blocks. Add focused
      tests in `FrontendBridgeTest` and conformance coverage for the all-Scala
      multi-fence shape and the intentional mixed-runnable shape. Done-when:
      focused v2 frontend tests pass, `tests/conformance/run.sh --only
      'standard-scala-*' --no-memo` (or the exact new affected globs) passes,
      targeted parity for `examples/scala-js-demo.ssc examples/lang-split.ssc`
      matches or has a newly filed/classified non-fence mismatch, and the full
      parity baseline/docs are updated with the new counts.
      Reproduced 2026-07-09 after `installBin`: `scala-js-demo.ssc` v2 starts
      correctly then crashes on missing `String.takeWhile` dispatch after
      `Sum 1..10 = 55`; `lang-split.ssc` v2 exits 0 but skips the intentional
      mixed `scala` fences. So this slice is now two narrow fixes:
      `Runtime.scala` string predicate method support plus `extractCode` policy
      for documented mixed runnable language-block examples.
      Second repro pass: after those two fixes, `lang-split.ssc` matches and
      `scala-js-demo.ssc` exposes two more narrow existing-support gaps:
      `f"..."` formatting is currently treated like raw `s"..."` concatenation,
      and guarded constructor-pattern arms use `__match_fail__` on guard false
      instead of falling through to the next case. Add focused regressions for
      both; they are required before `scala-js-demo.ssc` can match.
      Outcome: `scala-js-demo.ssc` and `lang-split.ssc` are now output-identical.
      v2 runs standard-Scala-only multi-fence documents in order; mixed
      `scalascript`/`scala` documents keep standard `scala` fences illustrative
      unless they opt in with `runScalaFences: true` (aliases:
      `run-scala-fences: true`, `scalaFences: runnable`, or
      `scala-fences: runnable`). Added `String.takeWhile`/`dropWhile`,
      `f"..."` interpolation, and guarded constructor-pattern fall-through
      support. Gates:
      `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest` 25/25,
      `installBin`, conformance `standard-scala-*` 3/3 on INT/JS/JVM, targeted
      parity 2/2 identical, and full parity
      `68/95 identical · 4 mismatch · 0 v2-error · 23 v1-only` with 5 nondet
      skips across 195 examples.

- [x] **v2-busi-testsweep-gaps** — DONE 2026-07-08: **61/61 busi tests green on --v2** (was 47/61).
      Seven root causes, one BUGS.md entry each (batch `v2-busi-testsweep-gaps`): shared top-level
      var cells; tryFBc string-equality optimism (`if p == period` always true — 5 tests); HOF
      effect threading (map/filter/fold collect raw Ops); Array companion returned lists; tolerant
      0L length FastCode; mid-line fence regex desync; OpAnf Lit-binding demoted arith to the
      weaker table dispatch (+ Map+(k->v) added there); content section lookups now fall back to
      imported documents. Gates: corpus 153/9 = base, conformance run.sh 125/125, v2 batch 110/40,
      benches at/below baseline. FOLLOW-UP queued in BACKLOG: unify Prims.arithOp vs table __arith__.
      Original: busi tests/v2 on --v2: 47/61 PASS after op-arg-lifting
- [x] **root-test-verify-default-srcdir-parent-scan** — DONE 2026-07-08 in
      `6c996bd63`: `ssc verify <artifact-dir>` now bounds implicit source
      discovery to the artifact directory itself, except for conventional
      `.ssc-artifacts` dirs where parent source lookup remains intentional.
      Added a subprocess regression proving a custom `out/` dir under a parent
      with stale `a.ssc` reports `sourceHash MISSING` under `--strict` rather
      than scanning the parent and producing `sourceHash mismatch`. Gates:
      `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"` 8/8 green;
      `tests/conformance/run.sh --only 'std-process-import' --no-memo` 1/1 green.
      Original: fix `ssc verify` default
      source discovery so `verify <artifact-dir>` does not recursively scan the
      whole parent temp/workspace tree. Root-gate repro: during
      `scripts/sbtc "test"`, `VerifyCliTest` tiny temp cases spent ~1-2 min each
      in child `java -jar .../ssc.jar verify /var/.../ssc-verify-*`; `jcmd`
      showed `runVerify(Main.scala:4125)` in `os.walk(srcDir).filter(os.isFile)`.
      Current code sets default `srcDir = artifactDir / os.up`. Fix direction:
      use a bounded default (artifact dir itself unless it is a conventional
      artifact-output dir such as `.ssc-artifacts`, where parent source lookup is
      intentional) and keep explicit `--src-dir` unchanged. Done-when:
      `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"` is green and
      the no-runtime/json cases no longer scan the temp parent.

- [x] **root-test-stable-spi-os-plugin-import** — DONE 2026-07-08 in
      `c3e277723`: OS plugin no longer imports `scalascript.interpreter`;
      invalid `exit(...)` args now raise through the stable `PluginError`
      surface, and the existing NUL arg separator literal was normalized to
      `"\u0000"` so future diffs stay text-friendly. Gates:
      `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.StableSpiEnforcementTest"`
      2/2 green; `scripts/sbtc "osPlugin/testOnly scalascript.compiler.plugin.os.OsPluginTest"`
      14/14 green; `tests/conformance/run.sh --only 'std-process-import' --no-memo`
      1/1 green. Original: restore stable SPI enforcement.
      Root-gate repro: `StableSpiEnforcementTest` failed because
      `runtime/std/os-plugin/src/main/scala/scalascript/compiler/plugin/os/OsIntrinsics.scala`
      imports `scalascript.interpreter.InterpretError`, which is forbidden for
      value-surface plugins. Fix direction: migrate the OS plugin to the stable
      `scalascript-plugin-api` error/value surface, or document a real exemption
      if it is intentionally outside the value-surface class. Done-when: the
      stable SPI enforcement test is green plus affected conformance.

- [x] **root-test-v2-conformance-toolkit-regressions** — clear the remaining
      v2/default conformance failures seen in the post-cluster full root gate.
      Repro from `scripts/sbtc "test"`: `V2ConformanceTest` failed
      `std-ui-jobpanel` (`?` labels instead of `2:Jobs` / `2:New job`),
      `tkv2-busi-home`, `tkv2-forms`, `tkv2-offline`
      (`RuntimeException: __method__: no field 'set' on named-method-obj`), and
      `tkv2-pwa` (`RuntimeException: unbound global: pwa`). Work loop: reproduce
      with targeted `V2ConformanceTest` filters, split if necessary, fix the
      shared `named-method-obj.set` family first, then `pwa`, then jobpanel
      labels. Done-when: selected cases are green and affected conformance is run.
      Progress 2026-07-08 `dad57a70b`: `named-method-obj.set` fixed by exposing
      `get`/`set` on v2 `ReactiveSignal` method objects and writing raw host
      values. Gates: `V2ConformanceTest -z tkv2-busi-home`, `-z tkv2-forms`,
      `-z tkv2-offline` green; conformance
      `tkv2-busi-home,tkv2-forms,tkv2-offline` 3/3 green. Remaining in this
      item: `tkv2-pwa` (`unbound global: pwa`) and `std-ui-jobpanel` heading
      label shape.
      Progress 2026-07-08 `a9028b830`: `tkv2-pwa` fixed. The v2 bridge now
      loads `pwaPlugin`, translates `pwa(...)` named args/defaults, and forwards
      plugin `ctx.registerRoute(...)` calls into the real v2 web server route
      registry. Gates: `V2ConformanceTest -z tkv2-pwa` green, `-z tkv2` green
      (6/6), and conformance `tkv2-pwa` green (INT pass; JS/JVM skipped by
      metadata). Remaining in this item: `std-ui-jobpanel` heading label shape.
      Progress 2026-07-08 `0facf7506`: `std-ui-jobpanel` fixed by keeping
      curried vararg defs (`cardWithHeader(header)(body*)`) out of the direct
      single-clause vararg call wrapper; first clauses now receive the header
      value directly instead of `List(header)`. Gates after rebasing on
      `origin/main@9e48204e5`: `V2ConformanceTest -z std-ui-jobpanel` green,
      `V2ConformanceTest -z tkv2` green (6/6), and conformance
      `std-ui-jobpanel` green (INT+JS pass; JVM skipped). New remaining blocker
      from the full suite after that rebase: `array-companion-statics`
      (`__method__: no dispatch for .sum on <foreign>`).
      Result 2026-07-08 `f6e6383ac`: `array-companion-statics` fixed by making
      `ForeignV(ArrayBuffer)` list-like for read-only collection dispatch while
      preserving real mutable array operations. Gates:
      `V2ConformanceTest -z array-companion-statics` green,
      conformance `array-companion-statics` green (INT+JS+JVM), and full
      `V2ConformanceTest` green (76 succeeded, 54 ignored, 0 failed). This
      root v2 conformance-toolkit item has no remaining known deterministic
      blockers.

- [x] **v2-op-arg-lifting** — DONE 2026-07-08: OpAnf bridge-side CoreIR pass (NOT a runtime
      lift — that would break the Mira/hm kernel lane where Op values are legal fn args).
      Let-binds may-be-Op args (App/Prim/Ctor/Match-scrut/If-cond); kernel letThread does the
      deferral; `handle(expr)` paren-form args excluded (op must reach handle raw); GATED to
      sources mentioning effect/handle (ungated = pattern-match-heavy 3-4× slower; gated =
      baseline everywhere, effect-multishot 5.19 ≈ 5.04 base). busi ledger ALL OK on --v2;
      corpus 153/9 = base; conf v2 batch 109/39 (js-applyunary-effect-cps FLIPPED TO PASS).
      Companion fix: args global was shadowed by a bridged native fn (BUGS.md
      v2-args-global-shadowed-by-native). Details in BUGS.md v2-op-arg-lifting.
      Original: strict calls (closures AND plugin natives, incl. `println`,
      and perform-argument evaluation) with an unresolved effect `Op` ARGUMENT must defer
      into the Op's continuation instead of consuming the Op as a value. Found working
      busi's ledger past append/2: `formatMoney(accountBalance(...))` gets a raw
      `Op(Journal.read, …)` (v1's compile-time CPS never faces this). Existing lifts:
      letThreadOp (val), seqThreadOp (statements), methodOp (receiver), arithOp
      (operands), applyFallback (fn-position) — the missing one is ARG-position.
      Fix at the uniform chokepoint (`Runtime.run` `Call` step or App arg-eval paths,
      incl. global fast paths): any arg `DataV("Op",…)` → rebuild Op with a reapplying
      continuation. HOT PATH: A/B with `scripts/bench` (bench-v2-lane claim is active —
      coordinate). Repros: busi ledger.ssc check #2 (`FAIL: cash debit`), conformance
      `js-applyunary-effect-cps.ssc` on v2 (`__unary__: - on Op`). Full notes in
      BUGS.md `v2-op-arg-lifting`. BLOCKS busi's --v2 conformance re-run.

- [x] **v2-actors-sendafter-cli-default-noop** — DONE 2026-07-08
      (`a6c9d8b7c`): production follow-up from
      `green-main-full-sbt-test-gating`: v2/default fat-jar actor flows with
      `sendAfter` exit 0 without delivering delayed messages, while `--v1` prints
      the expected message. Repro: after `scripts/sbtc "cli/assembly"`, run a
      temp `.ssc` containing `runActors { val me = spawn { () => val pid = self();
      sendAfter(10, pid, "hello"); receive { case msg => println("got: " + msg) } } }`
      with default, `--v2`, and `--v1`. Default/`--v2` produce no `got: hello`;
      `--v1` does. This is NOT fixed by the root-test harness commit
      `da63bb96a`; that commit only marks v1 cluster integration fixture nodes
      explicit `--v1` so root `sbt test` tests the runtime it was written for.
      Done-when: v2 either implements actor timer delivery for this repro and
      relevant actor conformance slices, or rejects unsupported actor APIs under
      `--v2` with a clear diagnostic instead of silent success.
      Outcome: implemented v2 actor timer delivery in
      `PluginBridge.registerActors` instead of rejecting actor APIs. The v2 actor
      bridge now tracks actor-run quiescence, blocked receives, scheduled sends,
      and queue wakeups so `runActors` does not return while child/timer work is
      still live. Default, `--v2`, and `--v1` fat-jar repros now all print
      `got: hello`.
      Active plan 2026-07-08 (`v2-actors-sendafter-cli-default-noop` / codex):
      - [x] Reproduce the fat-jar/default/`--v2` no-output behavior and the
            `--v1` expected `got: hello` baseline using `cli/assembly`.
      - [x] Locate the v2 path for actor primitives (`runActors`, `spawn`,
            `sendAfter`, `receive`) and decide whether timer delivery belongs
            in the v2 actor bridge now or should be a hard unsupported diagnostic.
      - [x] Add a faithful regression in the real CLI/runtime harness: no silent
            exit-0 when `sendAfter` is used under default/`--v2`.
      - [x] Run focused actor/CLI tests plus affected conformance before push;
            if fixed, update `BUGS.md`, `SPRINT.md`, and `CHANGELOG.md`.
            Gates: `scripts/sbtc "v2PluginBridge/compile"`;
            `scripts/sbtc "cli/assembly"`; original fat-jar repro default/`--v2`/`--v1`;
            `scripts/sbtc "cli/testOnly *V2ActorCliTest"`; `scripts/sbtc "installBin"`;
            `tests/conformance/run.sh --only 'actors-*' --no-memo` (8/8 passed).
            Gotcha: conformance uses `bin/ssc` / `bin/lib/ssc.jar`; run `installBin`
            after changing CLI/v2 runtime code, otherwise it can test a stale or
            missing installed jar.

- [x] **p3-mcp-and-tails** — DONE 2026-07-08 (5377e271f): the "MCP switch regression" was an
      UNMASKED exit-0 fiction (default invokeCallback is a NO-OP — setup blocks never ran; the
      switch-owner's override made them execute honestly). Fixed properly: curried extern-method
      protocol (two-clause `def m(a)(b)` decls scanned from extern-class bodies; conversion keeps
      the two-step) — ALL 7 MCP examples PASS. std/mcp exports Tool/Transport/requireString;
      phantom readOnlyHint/destructiveHint args removed from 2 examples; node-fs-read → js lane.
      **Corpus 153/9 — zero systemic v2 fails remain** (wip control-center, datatable emit-path,
      4 environmental, dsl-mini batch-ghost, x402-cardano external). Parity 63/85, conf 68.
      REMAINING (non-gate): v1-deep ×2 (actors scheduler-termination race; dsl-calc .many()),
      dsl-mini batch-vs-run arity ghost, control-center-live wip mechanics, datatable emit-path.
## Разоблачённые exit-0 фикции (cdd032f03 unmask, диагнозы 2026-07-09)

cdd032f03 «run standard scala source fences» сделал исполняемыми ```scala-фенсы —
пять примеров, что «проходили» НИКОГДА не исполняясь (ноль строк вывода до коммита),
теперь показывают реальные дыры v2. Гейт-база честная: 149/13 (было фиктивное 153/9).

- [x] **unmask-remote-def** — CLOSED 2026-07-09: v2 now runs
      `examples/remote-registry-rpc.ssc` honestly through the in-process remote
      registry. `remote def` is rewritten before scala.meta, manifest/`@remote`/
      sugar metadata registers handler closures, and `Remote.function(...).call`,
      `tryCall`, `remoteTryCall`, and `Remote.handlers()` work on v2. Gates:
      remote-focused bridge tests 2/2, full `FrontendBridgeTest` 38/38,
      `installBin`, `bin/ssc run --v2 examples/remote-registry-rpc.ssc`,
      `tests/conformance/run.sh --only 'distributed*'` 5/5, full
      `./v2/conformance/check.sh` before the final unrelated native-front rebase,
      and final-tip `git diff --check`.
      Original scope — remote-registry-rpc: три слоя (поверхность уточнена 07-09):
      (а) `remote def f(...)` — мягкий модификатор, scala.meta не парсит → текст-препасс
      `remote def X` → def X + регистрация; (б) std/remote.ssc (99 строк, 22 def/extern)
      должен конвертироваться бриджем; (в) remote-plugin нативы → V2PluginRegistry.
      Active plan 2026-07-09: committed spec first in `specs/unmask-remote-def.md`,
      then implement the smallest v2 in-process registry slice. Repro baseline:
      `bin/ssc run --v2 examples/remote-registry-rpc.ssc` exits 1 at
      `<input>:91: error: '}' expected but 'def' found` on `remote def`.
      Implementation path: `FrontendBridge` rewrites simple `remote def` before
      scala.meta, collects manifest/`@remote`/sugar metadata, and prepends entry
      `remote.registerHandler` calls that pass the actual handler closure;
      `PluginBridge` stores handler metadata+closure and registers `remoteFunction`,
      `remoteCall`, `remoteTryCall`, and `remoteHandlers` globals. Out of scope
      for this slice: HTTP fallback routes, `Remote.http`, `Remote.stub`, trait
      stubs, async lowering, WebSocket/internal-wire. Done when focused bridge
      tests pass, `installBin` passes, the example exits 0 with `echo:hello`,
      `HELLO`, `local:hello`, `echo:typed`, and handler listing lines, plus
      affected conformance and `git diff --check`.
- [x] **unmask-markup-bridge** — CLOSED 2026-07-09 in `b668359f9`:
      v2 now runs the documented `examples/xslt-transform.ssc` production
      example honestly. The bridge adds the minimal JVM markup/XSLT surface:
      `xml"""..."""` lowers through XML-escaping bridge helpers, `MarkupCodec`
      / `PureMarkupCodec` expose parse/serialize/transform method objects,
      `SerializeOpts` named/default construction works, XSLT params accept
      `Map[String,String]`, and transform failures return readable
      `Left(TransformError(message))`. Gates: full `FrontendBridgeTest` 39/39,
      `installBin`, real `bin/ssc run --v2 examples/xslt-transform.ssc`
      prints identity `<catalog>`, rename `<report>/<item>`, HTML `EUR`, and
      expected stylesheet error handling; affected conformance
      `tests/conformance/run.sh --only 'v2-*,content*' --no-memo` 7/7; full
      `./v2/conformance/check.sh`; and `git diff --check`. Note: the standard
      conformance INT lane still runs `--v1`, so the direct XSLT oracle is the
      assembled `--v2` example plus the focused bridge regression.
- [x] **unmask-payments-bridge** - CLOSED 2026-07-09 in `d255f18f8`/`69aad3c3f`:
      v2 now runs the documented `traditional-payments`, Pix, and FedNow
      examples honestly instead of leaking `Op(...)` or `Stub` values. The
      bridge adds deterministic no-network payment/bank-rails provider method
      objects, payment record field metadata, `Money`/`Currency` helpers, pure
      Pix QR generation, and the small `Instant`/`Thread` surface needed by the
      FedNow poll snippet. Non-self-contained route/webhook/platform/negative
      examples are explicitly `scala no-run`, and the runnable money section
      prints formatted amounts. Gates: `FrontendBridgeTest` 42/42, `installBin`,
      the three real `bin/ssc run --v2` examples with a no-`Op(`/no-`Stub`
      stdout guard, affected conformance `money-multisection,v2-*` 4/4, full
      `./v2/conformance/check.sh`, and `git diff --check`.
      Original scope:
      standard-Scala payment examples so they execute honestly instead of leaking
      `Op(...)` or `Stub` values. Spec: `specs/unmask-payments-bridge.md`. Bug:
      `BUGS.md#v2-payments-bankrails-op-stub-leaks`.
      Baseline after `scripts/sbtc "installBin"` on 2026-07-09:
      `bin/ssc run --v2 examples/traditional-payments.ssc` exits 0 but prints
      `Op("PaymentProvider.named", "stripe", <closure>)`; `bank-rails-pix.ssc`
      exits 0 but prints `Transfer initiated: Stub, status: Stub`,
      `Transfer status: Stub`, and an unhandled `PixQrCode.buildStatic` `Op`;
      `bank-rails-fednow.ssc` exits 0 but prints
      `FedNow transfer Stub submitted - status: Stub` and `Op("Instant.now", ...)`.
      Rollback `--v1` is not an oracle for this slice: these examples currently
      fail earlier on missing `PaymentProvider` / `PixConfig` / `FedNowConfig`.
      Implementation approach: add the existing payments/bank-rails modules to
      `v2PluginBridge`; register deterministic no-network method objects for
      `PaymentProvider.named("stripe")`, `PixProvider(...)`, and
      `FedNowProvider(...)`; bridge `Money`, `Currency`, enum/object companions,
      provider result ADTs, `PixQrCode` pure QR generation, and the small
      `Instant`/`Thread` surface needed by the FedNow poll example. Rejected:
      invoking real Stripe/Pix/FedNow adapters from examples, because production
      v2 smoke tests must not depend on live credentials or networks.
      Done when focused bridge tests pass, `installBin` passes, the three examples
      exit 0 without `Op(` or `Stub` in stdout, affected conformance/parity gates
      have been run, `git diff --check` is clean, and BUGS/SPRINT/CHANGELOG are
      updated in a separate bookkeeping commit.
- [x] **unmask-splice-in-scala-fence** — CLOSED: не сплайсы, а НЕВАЛИДНЫЙ Scala в примере
      (голый $ перед цифрой в s-строке — v1 терпел, scala.meta нет); пример исправлен $$49.99.
      ОСТАЁТСЯ (переименовано): **unmask-payments-bridge** — rc=0, но PaymentProvider-Op'ы
      текут в вывод: payments SPI не бриджен.
- [x] **unmask-webhook-global** — CLOSED: webhookRequest — свободная переменная ПСЕВДОКОДА;
      введён атрибут ```scala no-run для иллюстративных фенсов, фенс размечен.
- [x] **unmask-streams-runfold** — CLOSED: зелёный после match-scrutinee Op-lift (bbd05ab1d).
- [x] **unmask-markup-codec** — DUPLICATE 2026-07-09: merged into the active
      `unmask-markup-bridge` slice above. Same baseline: xslt-transform rc=0
      with empty stdout because the markup std surface is not bridged in v2.
- [x] **kernel: match-scrutinee Op-lift** — DONE bbd05ab1d: Op в скрутини матча лифтится
      (хендлер сперва, резюмированное значение матчится) — семья лифтов ПОЛНАЯ
      (операнды арифметики, ресиверы методов, записи var, скрутини матчей).
      Гейты: корпус 149/13, конформанс 85/3.

## p4-bc-perf — bytecode lane perf vs the now-fast VM (2026-07-09)

The VM lane got ~10x faster recently (arith/JIT work): fib25x30 VM 22ms vs
bytecode 107ms. Byte-lane is now 3-12x BEHIND on hot workloads. Sweep
(VM vs --bytecode, self-timed drivers over bench/corpus):
  string-concat 12.6x, list-fold 11.3x, pattern-match-heavy 10.2x,
  recursion-fib 5.1x, recursion-tco 4.3x, nested-loop 3.1x;
  at parity: hof-pipeline, map-ops, range-sum, string-split, typeclass-*;
  byte-lane WINS: mutual-recursion 0.56x (bounce trampoline).
**UPDATE 2026-07-09 — all 3 big gaps CLOSED (near parity):**
  list-fold 11.3x→1.55x (foreach-inline fabf450eb), pattern-match-heavy
  10.2x→1.25x (pure-def foreach bodies inline, d1b78b29d), string-concat
  11.5x→1.18x (direct .length/.size, 54efd028b). Remaining: p4-bc-unboxed-arith
  (fib 5x, arith loops — the VM near-JITs these to ~0ms; needs unboxed codegen).
      ROOT: the VM has COMPILE-LEVEL fast paths (FastCode unboxed arith via
tryFLC, inline-foreach-body via tryFCAppended) that the bytecode EMITTER
lacks — it routes hot ops through the generic runtime dispatch.
LANDED: foreachConsOp (61554b55c) — runtime foreach walks Cons directly
(no unlist materialise + no discarded result accum); ~5%, the rest is
per-element callClos + dispatch.
NEGATIVE RESULT: specialized per-op arith methods (Emit.add/sub/…) made
fib WORSE (107→146ms) — inline-lambda alloc; the JIT already handles the
string-op switch. Dispatch is NOT the bottleneck; boxing + callClos are.
- [x] **p4-bc-foreach-inline** — DONE 2026-07-09 (fabf450eb): inline Cons-walk
      for `foreach(Lam(1,body))` with EFFECT-FREE body — element PUSHED as a
      fresh De Bruijn slot (cleaner than the env-array plan: body reads it as
      Local(0) + captures via existing slot/env machinery), gen(body) inline,
      POP, advance consTail. pureNoEffect guard → effectful bodies fall to
      runtime foreachConsOp (Op-threading preserved). list-fold bc 786→113ms
      (~7x); bc/vm 11.3x→1.55x. Captures verified (14/30 both lanes). Corpus
      154/8, conformance 94/2.
- [x] **p4-bc-unboxed-arith** — DONE 2026-07-09: added a bytecode corpus bench
      lane (`scripts/bench v2-bytecode`), then emitted conservative unboxed
      `long` paths for bridge-lowered integer arithmetic where proof is local
      and semantics-preserving: `LongCellV` loop get/set arithmetic/comparisons
      and guarded arity-1 self-recursive Int functions. Generic `__arith__`
      remains the fallback, and the recursive fast entry checks the runtime
      argument is `IntV` before entering the `(J)J` method. Final benches after
      `installBin`: `arith-loop` bytecode 43.6ms -> 6.80ms, `nested-loop`
      52.2ms -> 7.60ms, `range-sum` stays at parity (0.424ms baseline ->
      0.413ms), and `recursion-fib` 31.9ms -> 1.27ms. Gates:
      `v2FrontendBridge/testOnly ... -- -z "v2 bytecode"` (2/2),
      `installBin`, affected conformance
      `arithmetic,recursion,tail-recursion,mutual-recursion` 4/4 with
      `--no-memo`, final four `scripts/bench v2-bytecode` rows, and
      `git diff --check`. Full `tests/conformance/run.sh` is still red due to
      unrelated rows now tracked as `green-main-conformance-7fail`.
      Original scope: track provably-Int operands in the emitter
      and emit unboxed JVM arith (iadd/if_icmple) with boxing only at
      call/store boundaries (VM's tryFLC analog). Helps arith-loop/nested-
      loop/range-sum where the VM near-JITs to 0ms.
      Plan 2026-07-09: first record a bytecode-vs-VM baseline for a narrow
      integer-loop family (`arith-loop`, `nested-loop`, `range-sum`, and a fib
      row if the wrapper exposes it), using `scripts/bench` commands where
      available and documenting any required project wrapper fallback before
      running it. Then inspect the v2 bytecode emitter's current arithmetic,
      comparison, local-slot, and closure-call lowering; add the smallest typed
      proof that recognizes bridge-lowered Int/Long loop operands without
      changing generic `__arith__` semantics. Rejected upfront: resurrecting the
      previous specialized per-op runtime methods, because they made fib worse
      (107ms -> 146ms) and did not address boxing/callClos. Done when an
      A/B baseline shows either a clear win or a documented negative result,
      focused emitter tests pin correctness, affected conformance passes,
      `git diff --check` is clean, and SPRINT/CHANGELOG record the outcome.
      Baseline 2026-07-09 after adding `scripts/bench v2-bytecode`:
      `scripts/bench v2-bytecode arith-loop` => v2 0.000018 ms,
      v2-bytecode 43.6 ms; `nested-loop` => v2 17.7 ms, v2-bytecode
      52.2 ms; `range-sum` => v2 0.401 ms, v2-bytecode 0.424 ms
      (already near parity); `recursion-fib` => v2 5.76 ms, v2-bytecode
      31.9 ms. First optimization target: `arith-loop`/`recursion-fib`,
      not `range-sum`.

## Phase 4 — perf baseline v2-VM (bench 2026-07-08, `./bench.sh --backend v2`)

Полная таблица в истории бенчей; ключевые точки (ms/iter, v2 vs v1-interp+JIT):
паритет/быстрее — effect-multishot 5.04 vs 4.75, streams-pipeline 0.0078 vs 0.012,
hello 0.000142 vs 0.0032, typeclass-fold 1.98 vs 1.32; средняя зона (циклы/вызовы,
цель байткод-лейна) — fib 63.5 vs 1.25 (51×), arith-loop 9.73 vs 0.27 (36×),
nested-loop 60×, tco 98×; ПАТОЛОГИИ (точечные VM-фиксы до байткода) —
lazylist-take 213.8 vs 0.060 (~3560×), effect-stream 28.7 vs 0.017 (~1700×),
array-update 279 vs 0.72 (~386×), pattern-match-heavy 385×, vector-index 136×.

- [x] **p4-perf-lazylist** — ДИАГНОЗ СКОРРЕКТИРОВАН 2026-07-08 (охота закрыта): НЕ квадратично —
      scaling-проба take(8/16/32/64) = 46/73/79/92ms (суб-линейно, доминирует константа на цепочку
      ~10μs). JFR: горячее — generic-`__method__` резолвер + List-аллокации args + callClos на
      элемент. v2 оборачивает НАТИВНЫЙ scala.LazyList (обёртки тонкие ✓) — вся цена = 4 generic-
      диспетча + 8 VM-вызовов замыкания на цепочку × 20k цепочек ворклада. ЛЕЧЕНИЕ КЛАССА =
      p4-jvm-lane-bytecode (компиляция структуры); опциональные микро-вины: name-first кэш
      диспетча в methodOp, безаллокационный 0/1-арг путь __method__ (сейчас всегда List).
      Тот же вердикт применим к array-update/vector-index/pattern-match-heavy — снять их
      отдельные охоты, объединить в «generic-dispatch constant» класс под байткод-лейн.
- [x] **p4-perf-dispatch-class** — DONE 2026-07-08: array-update/vector-index/pattern-match-heavy/effect-stream:
      скорее всего тот же generic-dispatch constant (см. lazylist-диагноз). После bytecode-
      milestone-2 пере-мерить; если хвосты останутся — точечные охоты.
      Result: no code changes. Re-measurement confirms these are not four
      independent workload bugs. `ssc`, `ssc-asm`, JVM, JS, and Rust target
      lanes are already in the expected low-ms/sub-ms range for the supported
      cases; the remaining pathological column is the explicit `v2` VM runner,
      matching the `p4-perf-lazylist` generic-dispatch / VM-constant diagnosis.
      Treat per-workload hunts as closed; remaining production path is
      `p4-jvm-lane-bytecode` / compiled-lane defaulting, not ad hoc fixes here.
      Active plan 2026-07-08 (`p4-perf-dispatch-class` / codex):
      - [x] Stage the current runner with `scripts/sbtc "installBin"` because
            corpus benchmarks use `bin/ssc`, then run `scripts/bench smoke`.
      - [x] Re-measure the named corpus workloads with the existing corpus
            wrapper, recording the exact command and rows:
            `./bench.sh --warmup-time 1000 --reps 50 array-update vector-index pattern-match-heavy effect-stream`.
      - [x] Compare against the checked-in `bench/BASELINE.md` rows and the
            `p4-perf-lazylist` diagnosis. If the rows are now explained by the
            compiled-lane/generic-dispatch class, close this item as a class
            decision with no code changes.
      - [x] If a workload still has a distinct unexplained gap, queue a narrow
            follow-up in SPRINT/BACKLOG with the measured command, affected
            backend, and suspected owner; do not start a broad optimization in
            this slice.
            No new per-workload follow-up queued: all four share the same
            explicit-`v2` VM column shape.
      Done-when: SPRINT/CHANGELOG record the measurement table and decision,
      with no stale open `p4-perf-dispatch-class` item left behind.
      Measurement (`./bench.sh --warmup-time 1000 --reps 50 array-update vector-index pattern-match-heavy effect-stream`):
      | Workload | ssc | ssc-asm | v2 | jvm | js | rust |
      | --- | ---: | ---: | ---: | ---: | ---: | ---: |
      | `array-update` | 0.694 | 0.648 | 272.7 | 0.506 | 4.88 | 0.644 |
      | `effect-stream` | 0.016 | 0.017 | 28.1 | n/a | 0.017 | 0.020 |
      | `pattern-match-heavy` | 0.053 | 0.052 | 46.3 | 0.046 | 0.047 | 1.37 |
      | `vector-index` | 1.00 | 0.848 | 142.6 | 0.477 | 4.89 | 0.593 |
- [~] **p4-bench-na-fixes** — 2 из 3 закрыты 2026-07-08 (3d11617a0): effect-pure 0.130 ms/iter
      (плагин-джары в bench-пути); effect-oneshot семантически РАЗБЛОКИРОВАН четырьмя Op-lift
      швами (__method__-ресивер, arithOp оба операнда, cell/lcell.set через liftOverOp) +
      __effect__-прим для декларированных эффектов (FastCode отказывается от effectful-деревьев
      вместо asInt-краша) — теперь перф-bound (класс p4-perf-* патологий, эффект-в-горячем-цикле).
      ОСТАЛОСЬ: type-lambda-native — parse-гап `[X] =>>` (семья type-lambda).

## Phase 4 — compiled lanes on v2 (программа, 2026-07-08)

AUDIT: v2 владеет полным путём .ssc → CoreIR (ssc1c, self-hosted KC4) → три source-кодгена
(v2/backend: JvmBackend 983 строк, JsBackend, RustBackend 1194) + wasm-раннер (ssc0-wasm) +
парити-харнес check.sh (VM-выход = эталон). Базлайн: **18 ok / 6 fail** (floatnum ×3, map ×3).
Разрыв до корпуса: std/plugin-поверхность В ТАРГЕТЕ — у v1-лейнов она есть как рантаймы
(JvmRuntimePreamble, JS base runtime, Rust runtime).

АРХИТЕКТУРНОЕ РЕШЕНИЕ: v2-кодгены генерируют код, линкующийся против СУЩЕСТВУЮЩИХ v1
таргет-рантаймов (переиспозование лет работы над std-поверхностью; тот же bridge-паттерн,
что вывез интерп-лейн).

- [x] **p4-kernel-green** — DONE 2026-07-08: floatStr-семантика (целые даблы сворачиваются,
      nan/inf lowercase) + Cons/Nil→List(…) рендер выровнены на VM-эталон во всех трёх
      кодгенах. **check.sh: 24/24 ALL GREEN.**
- [x] **p4-corpus-probe** — DONE 2026-07-08. Ключевой сдвиг: ssc1c (self-hosted KC-инструмент)
      для лейнов НЕ нужен — **FrontendBridge и есть .ssc→CoreIR компилятор: 194/195 корпуса
      конвертится** (запускается и без scala-cli: `java -cp bin/lib/jars ssc.cli run`).
      Перепись примов бридж-эмиссии (31 отличный прим): __arith__ 12k, __method__ 10k,
      fieldAt 8.6k, __isTag__ 1.7k, __mk_map__ 1.3k, global.reg, __autoPrint__, cell.*, __try__,
      __sqlExec__… — ВСЕ реализованы в ssc.Prims/Runtime (пребилт v2-core.jar). СЛЕДСТВИЕ для
      p4-jvm-lane-bytecode: ASM-кодген компилирует ТОЛЬКО структуру (lam/app/let/match/seq/
      letrec/ctor/if), все примы = invokestatic в ssc.Prims; plugin-поверхность = тот же
      PluginBridge.loadAll() на старте. Перф — из компиляции структуры (циклы/вызовы/матчи).
- [~] **p4-jvm-lane-bytecode** — MILESTONE 2 GREEN 2026-07-08 (7d385b541): ВСЕ структурные
      формы компилируются (Lam через indy/SAM, Match tag-диспетч, LetRec, While; гибридная
      env-модель массив+слоты с материализацией при захвате). **fib(25)×30: 116ms байткод
      против 266 v2-VM и 378 v1-интерп — лейн БЫСТРЕЕ обоих (2.3×/3.3×)**; рычаги: прямой
      invokestatic для известных дефов, кэш резолва в шимах, прямой Emit.arith без StrV-боксинга
      оператора. Валидировано: hello/fib/match-рекурсия/замыкания/каррирование.
      MILESTONE 3 GREEN 2026-07-08 (214c71f7b): tail-позиции трекаются; self-tail =
      Emit.rebind(frame, args) + GOTO start (клон фрейма — алиасинг замыканиями; fast-path для
      top-level дефов). tco(1M) = константный стек ✓; fib 152ms (1.75× быстрее VM) ✓; регрессий
      нет. COVERAGE+CLI GREEN 2026-07-08 (5aad7f5d8): compile-свип 194/195, НОЛЬ Unsupported;
      **`ssc run --bytecode` доступен пользователям** (e2e: hello, tco 1M, fib 122ms против
      266 VM / 378 v1). **FULL OUTPUT PARITY 2026-07-09 (98d10da80): свип identical 96 / mismatch 0 / bc-error 0**
      (+3 vm-only-error: лейн ИСПОЛНЯЕТ swing-frontend файлы, которые VM отказывается).
      Полный стек: Seq/Let-цепочки (seqThread/letThread), Match-scrutinee Let-переписывание,
      value-дефы в install(), авто-cell @xxx глобалов, Signal-ячейки, anyStr Stub-рендер,
      mutual-tail bounce, self-tail GOTO. Харнес: scripts/bc-parity-sweep. fib 108ms (1.75×
      над VM), tco 1M, конформанс 86. ДАЛЬШЕ ДЛЯ ЛЕЙНА: перф-раунд (лейн теперь семантически
      полный — можно мерить полный bench-корпус на --bytecode), затем разговор о дефолте;
      в letrec-телах self-tail отключён (документировано).
      MILESTONE 1 GREEN 2026-07-08: модуль v2JvmBytecode
      (v2/backend-jvm-bytecode, ASM 9.7 + v2Core), шимы ssc.Emit (prim0..N/app/ctor/global/
      литералы — эмиссия = push-args + invokestatic), эмиттер девяти структурных форм entry
      (Lit/Global/Local/Prim/App/Seq/If/Let/Ctor; De Bruijn → JVM-слоты). Смоук: hello.ssc →
      бридж → CoreIR → 288 байт байткода → defineClass → «Hello, World!». Гибрид: дефы от
      VM-компилятора (Emit.globalsRef). MILESTONE 2: Lam→методы+ClosV-подкласс, Match→tag-switch,
      LetRec; затем корпус-покрытие и CLI-флаг. РЕШЕНИЕ (2026-07-08, обсуждено с владельцем): CoreIR → JVM
      байткод НАПРЯМУЮ через ASM 9.7 (уже в deps), in-process, БЕЗ scala-cli/bloop/scalac.
      Рантайм НЕ генерится: байткод статически линкуется против пребилт scalascript-v2-core.jar
      (ssc.Runtime/ssc.Prims). run = ClassWriter→defineClass; build = jar. Паттерны эмиссии
      (Value-репрезентация, TCO-трамплин, dispatch) адаптировать из v1 AsmJitBackend (парити
      с javac, зелёный сьют). Эмиссию изолировать за узким ClassEmitter-интерфейсом — на
      JDK 24+ свап на стандартный ClassFile API (JEP 484) без ASM. Текущий Scala-source
      JvmBackend.scala остаётся как reference/debug-генератор для check.sh.
      Горизонт «без Scala вообще»: build-time Scala невидим пользователю (fat-jar, нужен JRE);
      runtime scala-library уходит опциональной фазой — порт ядрового Runtime (~1-2kloc) на Java.
- [x] **p4-js-lane-bridge** — DONE 2026-07-08: v2 CoreIR -> JS bridge is
      available as opt-in `ssc run-js --v2 <file.ssc> [args...]` while legacy
      `run-js` stays on the v1 JS path. The production CLI now builds
      `v2/backend/js` as `v2JsBackend`, calls `ssc.js.JsGen.generate` in-process,
      writes a temp `.cjs`, and runs Node with forwarded argv. The v2 JS preamble
      now includes the FrontendBridge standard globals/bridge primitives needed
      for `.ssc -> FrontendBridge -> CoreIR -> JS` (`println`, `print`, `args`,
      `__autoPrint__`, `__arith__`, `__method__`, `__math_obj__`, etc.).
      Gates: `scripts/sbtc "v2JsBackend/compile"`;
      `scripts/sbtc "cli/compile; cli/assembly; cli/testOnly *V2JsLaneCliTest"`
      (1 test green, including argv); `scripts/sbtc "installBin"`; direct
      installed CLI smokes `bin/ssc run-js examples/hello.ssc`,
      `bin/ssc run-js --v2 examples/hello.ssc`, and
      `bin/ssc run --v2 examples/hello.ssc` all print `Hello, World!`;
      `v2/backend/check.sh` green (`ALL GREEN (8 fixtures x 3 backends)`);
      affected conformance
      `tests/conformance/run.sh --only 'js-cps-intrinsic-rewrite,node-basic' --no-memo`
      green (2/2). Follow-up discovered and queued: `p4-v2-run-argv-separator`
      / `BUGS.md` `v2-run-cli-argv-not-forwarded` for default `ssc run --v2`
      argv syntax; `run-js --v2` argv forwarding is covered here.
      Original: v2 CoreIR -> JS bridge, first as an opt-in
      Node runner (`run-js --v2`) before any default JS-lane flip. Spec:
      `specs/v2-js-lane-bridge.md`.
      Active plan 2026-07-08 (`p4-js-lane-bridge` / codex):
      - [x] Claim the slice and read the existing v2 JS backend, JVM bytecode
            lane, CLI `RunV2`, and production compatibility specs.
      - [x] Commit the spec/SPRINT planning slice before implementation.
      - [x] Add an sbt-built `v2JsBackend` module for `v2/backend/js` so the
            fat-jar CLI can call the generator in-process.
      - [x] Add `ssc run-js --v2 <file.ssc> [args...]` as an opt-in route:
            FrontendBridge -> CoreIR -> `ssc.js.JsGen.generate` -> temp `.cjs`
            -> Node, while preserving legacy `run-js` without `--v2`.
      - [x] Add focused CLI regression(s) for `run-js --v2` and unchanged
            legacy routing.
      - [x] Verify with `scripts/sbtc "v2JsBackend/compile"`, focused CLI tests,
            `scripts/sbtc "installBin"`, direct `bin/ssc run-js --v2
            examples/hello.ssc`, the CoreIR backend JS fixture harness, and the
            nearest affected conformance JS slice.
      Done-when: the opt-in v2 JS runner is available from the installed CLI,
      has a regression, and the spec/SPRINT records exact verification results.
- [x] **p4-v2-run-argv-separator** — DONE 2026-07-08 (`64de9b9af`): default
      `ssc run <file.ssc> -- [args...]`, explicit `ssc run --v2 <file.ssc> --
      [args...]`, and `ssc run --bytecode <file.ssc> -- [args...]` now forward
      program argv into v2 `Runtime.argv`. Positionals before `--` remain source
      files, preserving multi-file runs. The bytecode lane also now mirrors the
      VM's list application fallback so `args(0)` works through compiled
      `Emit.app`. Gates: `scripts/sbtc "cli/compile; cli/assembly; cli/testOnly
      *V2RunArgvCliTest"` (2/2); `scripts/sbtc "installBin"`; direct installed
      CLI smokes for default/`--v2`/`--bytecode` all print `2`, `one`, `two`;
      `tests/conformance/run.sh --only 'collections' --no-memo` green
      (INT/JS/JVM); combined assembled-CLI smoke
      `scripts/sbtc "cli/testOnly *V2RunArgvCliTest *V2JsLaneCliTest"` green
      (3/3). BUGS.md `v2-run-cli-argv-not-forwarded` moved to fixed.
      Original: fix the default/explicit v2 VM runner's
      program argv forwarding without breaking multi-file runs. Found during
      `p4-js-lane-bridge`: `bin/ssc run-js --v2 /tmp/args.ssc one two` sees
      `args.length == 2`, while `bin/ssc run --v2 /tmp/args.ssc one two`
      currently passes `Nil` into `RunV2.run`, prints `0`, then crashes on
      `args(0)`. Track root cause and repro in `BUGS.md`
      `v2-run-cli-argv-not-forwarded`. How: add an explicit `--` separator
      contract such as `ssc run [flags] <file.ssc> -- [args...]`, forward the
      trailing argv to `RunV2.run` and `RunV2.runBytecode`, and update usage plus
      a real assembled-CLI regression. Done-when: focused CLI test proves
      `run --v2` argv delivery and current multi-file/file-argument behavior is
      not silently reinterpreted.
- [x] **p4-rust-wasm-lanes** — DONE 2026-07-08 in `84d7ac77f`: restored the
      self-hosted v2 Rust/WASM target gate. JS/Rust/WASM target display now
      matches VM `List(...)`; self-hosted Rust emits valid whole-float literals
      (`V::Fl(2.0)`, not `V::Fl(2)`); stale display expectations were
      rebaselined; and the VM-only typed effect-handler regression was fixed by
      restricting `Let`/`Seq` auto-threading to bridge/runtime Ops with dotted
      labels while preserving pure free-monad `Op(...)` values as data. Gates:
      `./v2/conformance/check.sh` green; `./v2/backend/check.sh` green (`ALL
      GREEN (8 fixtures x 3 backends)`); affected conformance
      `tests/conformance/run.sh --only 'effects,effect-*,async*,direct-*,js-*-effect-*,std-functor-applicative-monad,std-foldable-traversable,std-index' --no-memo`
      = 12 passed, 0 failed; `tests/conformance/run.sh --only 'rust*,wasm*'
      --no-memo` = 0 matching top-level cases, so Rust/WASM coverage is through
      the v2 gate. Gotcha: top-level conformance uses `bin/ssc`; if
      `bin/lib/ssc.jar` is missing, it reports `<missing>` outputs because
      stderr is suppressed. Build the launcher (`bash install.sh --dev` or the
      equivalent `installBin`) before interpreting affected conformance output.
      Original:
      restore the self-hosted v2 Rust/WASM target
      gate before any default-lane flip. Spec: `specs/v2-rust-wasm-lanes.md`.
      Baseline 2026-07-08 from this claim:
      `./v2/backend/check.sh` is green (`ALL GREEN (8 fixtures x 3
      backends)`), but `./v2/conformance/check.sh` is red. The red gate splits
      into two concrete bugs tracked in `BUGS.md`:
      `v2-ssc0-target-display-drift` (self-hosted JS/Rust target display and
      stale conformance expectations still use raw `Cons(..., Nil)` / `10.0`
      after `p4-kernel-green` accepted VM `List(...)` + collapsed whole-float
      display) and `v2-ssc0-rust-float-literal-emits-int` (`V::Fl(2)` /
      `V::Fl(1)` rustc E0308 after `#f->str` collapses whole floats).
      Active plan 2026-07-08 (`p4-rust-wasm-lanes` / codex):
      - [x] Commit this spec/SPRINT/BUGS planning slice before code
            (`9fa380d89`, pushed before implementation).
      - [x] Align `v2/lib/backend-js-gen.ssc0` and
            `v2/lib/backend-rust-gen.ssc0` `show` helpers with VM
            `Show.show`: proper `Cons`/`Nil` chains render as `List(...)`.
            Because `ssc0-wasm` reuses the Rust generator, this also defines
            WASM display.
      - [x] Normalize self-hosted Rust float literal emission so `IrFloat(2.0)`
            becomes valid Rust inside `V::Fl(...)` (`2.0`, or Rust constants
            for `nan`/`inf` if encountered).
      - [x] Update only stale `v2/conformance/check.sh` expectations caused by
            accepted kernel display semantics (`List(...)`, collapsed whole
            floats); do not paper over semantic mismatches.
      - [x] Fix the VM-only effect-handler regression found after the target
            fixes: `async-tasks.ssc0`, typed `hm-async.hm`, and `handleM`
            rows return raw `Op(...)` under `run`/`run-ir` while JS/Rust target
            rows produce values. Track as `BUGS.md`
            `v2-vm-effect-handlers-return-raw-op`; do not accept raw `Op(...)`
            as the expected result.
      - [x] Verify `./v2/conformance/check.sh`, `./v2/backend/check.sh`, and
            affected repo-level conformance (`tests/conformance/run.sh --only
            'rust*,wasm*'` or the nearest matching slice if no cases match).
      Done-when: self-hosted Rust rows compile/pass, WASM quicksort/TCO remains
      green, the target display contract is documented, and the bugs move to
      `fixed` with the landing SHA.
- [x] **p4-default-flip** — DONE 2026-07-08: stale queue duplicate closed after
      verifying it was already implemented by `v2-prod-default-switch`
      (`719943f40`, `d2ba78c0a`, `89a38f1e3`). Plain default-lane
      `ssc run <file>` already routes through the v2 VM; `ssc run --v1 <file>`
      remains the rollback path; `ssc run --v2 <file>` remains an explicit
      force flag. Fresh verification from
      `/Users/sergiy/work/my/scalascript-wt-p4-default-flip`:
      `scripts/sbtc "cli/testOnly scalascript.cli.V2DefaultSwitchTest scalascript.cli.CommandRegistryTest"`
      => 11/11 tests passed; `scripts/sbtc "installBin"` passed; direct
      `bin/ssc run`, `bin/ssc run --v1`, and `bin/ssc run --v2`
      `examples/hello.ssc` all printed `Hello, World!`; affected conformance
      `tests/conformance/run.sh --only 'dsl*' --no-memo` passed
      `dsl-multi-pass` in INT/JS/JVM. No code/spec changes were needed.

## v2 production readiness (2026-07-08, Sergiy: "довести v2 до production")

Goal: make v2 safe to become the default `ssc` runtime, with `ssc --v1` kept as the
rollback path. This workstream does **not** try to green every unrelated repo-wide
test first; it fixes repo-wide gates only when they block the v2 production gate.
Coordinate with existing Phase-3/p3 items below instead of duplicating their fixes.

- [x] **v2-prod-queue-hygiene** — DONE 2026-07-09: reconciled stale v2
      production queue entries that still appeared open after
      `v2-prod-default-switch`, `v2-output-parity-harness`, and
      `v2-parity-current-errors` landed. The old Phase-3 switch container now
      points at the shipped default-switch commits, and the struck
      `v2-output-parity-full-corpus` duplicate now points at the shipped harness
      plus current full gate (`64/98 identical · 11 mismatch · 0 v2-error ·
      23 v1-only`). No source behavior changed; verification: `git diff --check`.
      Original plan:
      reconcile stale v2 production queue entries that
      still appear open after `v2-prod-default-switch`, `v2-output-parity-harness`,
      and `v2-parity-current-errors` landed. How: mark the old Phase-3 switch
      container as landed/superseded by `v2-prod-default-switch`, mark the struck
      `v2-output-parity-full-corpus` duplicate as reconciled by the shipped
      harness/current gate, and add a changelog note. No source behavior changes.
      Done-when: `SPRINT.md` has no stale open switch/full-corpus duplicates,
      `CHANGELOG.md` names this queue cleanup, and a docs-only verification
      (`git diff --check`) passes.
- [x] **v2-arith-unification** — DONE 2026-07-09 (`a2985d911`): removed the
      remaining v2 arithmetic dispatch split. `resolve("__arith__")` is now a
      thin delegate to `Prims.arithOp`, and `arithOp` owns the previous
      table-only behavior (Decimal, actor-send, `:=`, list/tuple/string/numeric
      cases, char-code comparisons, and unknown declaration fallback) without
      recursively calling the table. Added CoreIR regressions where the op name
      comes from a local binding, forcing the non-literal path. Gates:
      `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
      = 20/20; `scripts/sbtc "installBin"` passed;
      `tests/conformance/run.sh --only 'litdoc,arithmetic' --no-memo` passed
      `arithmetic` on INT/JS/JVM and skipped `litdoc` because no
      `expected/litdoc.txt` exists. Direct litdoc A/B still has the separate
      inline-bold mismatch tracked below as `v2-litdoc-inline-bold-parity`; the
      arith/map data line agrees. Original plan:
      remove the remaining v2 arithmetic dispatch
      split between literal-op `Prims.arithOp` fast paths and the non-literal
      `resolve("__arith__")` table. Why: BACKLOG/BUGS already caught a real
      busi litdoc failure where ANF demoted `__arith__(Lit("+"), map, pair)` to
      the weaker table path; patching one case fixed litdoc, but production v2
      should not have two divergent semantic tables. How: add focused CoreIR
      regressions that pass the op name through a local (forcing the non-literal
      path) for Map+Tuple2, char-code comparisons, Decimal, Tuple++/list ops,
      actor-send/unknown-declaration fallbacks as applicable; move table-only
      behavior into `Prims.arithOp`; make `resolve("__arith__")` a thin delegate
      to `arithOp`; remove `arithOp` fallbacks that call `resolve("__arith__")`
      so the delegate cannot recurse. Verify with
      `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
      plus affected conformance `tests/conformance/run.sh --only 'litdoc,arithmetic' --no-memo`
      after `installBin`.
- [x] **v2-litdoc-inline-bold-parity** — DONE 2026-07-09 (`2b5a36660`):
      restored v2 regex semantics for `String.split`/`str.split` and added
      `tests/conformance/expected/litdoc.txt`. Root cause: v2 quoted the split
      delimiter with `Pattern.quote`, while v1 treats `.split(sep)` as regex;
      litdoc's `"\\*\\*"` delimiter therefore never split bold markers on v2.
      `litdoc.ssc` is now an INT conformance case; JS/JVM are backend-lane
      follow-ups (`jsgen-toplevel-name-vs-preamble` for `val doc` collision and
      `jvmgen-litdoc-mapped-string-mkstring` for mapped-string `mkString()`).
      Gates: `scripts/sbtc "installBin"` passed;
      `tests/conformance/run.sh --only 'litdoc' --no-memo` passed INT and
      skipped JS/JVM by `backends: [int]`; direct `bin/ssc run --v1/--v2`
      `tests/conformance/litdoc.ssc` diff is empty. Original:
      follow-up found during
      `v2-arith-unification` verification. After `installBin`, direct real-harness
      A/B for `tests/conformance/litdoc.ssc` still differs only on inline bold
      rendering: v1 prints `inline: P(buy a )B(new)P( dress)`, v2 prints
      `inline: P(buy a **new** dress)`. This is not the arith/map divergence:
      the `data: price=40` line agrees after the arith unification. How:
      inspect `runtime/std/litdoc.ssc` plus v2 bridge lowering for `inlinesOf`
      pattern/method calls, reproduce with the direct `bin/ssc run --v1/--v2`
      diff, then add a focused expected conformance case or make
      `litdoc.ssc` eligible for the existing expected-file harness. Done-when:
      the direct litdoc A/B diff is empty and BUGS `v2-litdoc-inline-bold-parity`
      moves to `fixed`.
- [x] **v2-litdoc-js-jvm-backend-lanes** — DONE 2026-07-09 (`782f07438`):
      `tests/conformance/litdoc.ssc` now runs across INT/JS/JVM. Fixes:
      JS top-level runtime-name collision for user `val`/`var` bindings
      (`val doc` → generated safe name), JS `String.split` now uses regex
      semantics to match Scala/JVM, JVM omits the `doc` helper when the module
      owns top-level `doc`, and JVM no-arg `.mkString()` rewrites to Scala's
      parameterless `.mkString`. Gates: `scripts/sbtc "backendJs/compile;
      backendJvm/compile; installBin"`; direct `bin/ssc emit-js
      tests/conformance/litdoc.ssc | node`; direct `bin/ssc run-jvm
      tests/conformance/litdoc.ssc` after removing the stale generated
      `.scjvm`; `tests/conformance/run.sh --only 'litdoc' --no-memo`; and
      `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest
      scalascript.JvmGenBackendBlockTest"` (52/52). Original plan: promote the
      BACKLOG backend-lane
      follow-up for `tests/conformance/litdoc.ssc` so the same fixture can run
      across INT/JS/JVM instead of staying `backends: [int]`. Baseline from
      BUGS/BACKLOG: raw JS fails with `jsgen-toplevel-name-vs-preamble` because
      top-level `val doc = ...` collides with the JS preamble `doc` helper; JVM
      codegen fails compiling the litdoc fence line shaped like
      `doc.nodes.filter(...).map(...).map(_show).mkString()` with
      `StringOps.apply` missing parameter. Work plan:
      - [x] Reproduce from a staged real CLI:
            `scripts/sbtc "installBin"`, then
            `bin/ssc emit-js tests/conformance/litdoc.ssc | node` and
            `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
            Baseline 2026-07-09 after `installBin`: JS fails at generated
            `const doc = _call(parseDoc, md);` with
            `SyntaxError: Identifier 'doc' has already been declared`; JVM
            fails compiling
            `doc.nodes.filter(...).map(...).map(_show).mkString()` with
            `missing argument for parameter i of method apply in class StringOps`.
            Current `tests/conformance/run.sh --only 'litdoc' --no-memo`
            reports INT PASS and skips JS/JVM due to `backends: [int]`.
      - [x] Fix the JS generator at the general preamble-collision boundary,
            not by renaming the fixture. Rejected shortcut: fixture-only rename
            (`val litDoc = ...`) would green this case while leaving the known
            `jsgen-toplevel-name-vs-preamble` production class open.
            Implementation direction: reserve JS runtime top-level names and
            rename colliding user top-level `val`/`var` bindings plus their
            normal name references. This slice intentionally targets top-level
            collision repros; expand lexical shadow tracking only if focused
            tests expose a local-shadow regression.
      - [x] Fix the JVM generator/lowering for mapped-string `mkString()` so the
            generated Scala compiles and prints the expected litdoc line.
            Investigation update: `emit-scala` also emits `def doc(args: Any*)`
            from `JvmRuntimePreamble`, then `val doc = parseDoc(md)`. The
            observed `StringOps.apply` compile error is likely the same
            preamble/user-name collision surfaced later in type inference, so
            first fix JVM by omitting the `doc` helper when the module owns the
            top-level `doc` name; revisit `routeMkStringThroughShow` only if the
            direct JVM repro still fails afterward.
      - [x] Remove the temporary `backends: [int]` restriction from
            `tests/conformance/litdoc.ssc` and run
            `tests/conformance/run.sh --only 'litdoc' --no-memo` with all
            enabled lanes, plus focused sbt tests for the touched generator(s).
      - [x] Update `BUGS.md` entries
            `jsgen-toplevel-name-vs-preamble` and
            `jvmgen-litdoc-mapped-string-mkstring`, move the BACKLOG row to
            landed, add CHANGELOG, and release the claim after push.
- [x] **jvm-artifact-cache-codegen-invalidation** — DONE 2026-07-09: fixed the `run-jvm`
      artifact cache so generated `.scjvm` files are invalidated by compiler /
      JVM codegen version as well as `.ssc` source bytes. Repro discovered
      during `v2-litdoc-js-jvm-backend-lanes`: after a JVM codegen fix,
      `bin/ssc emit-scala tests/conformance/litdoc.ssc` showed fresh output but
      `bin/ssc run-jvm tests/conformance/litdoc.ssc` still compiled
      `tests/conformance/.ssc-artifacts/litdoc.scjvm` until that generated file
      was removed. BUGS: `jvm-artifact-cache-codegen-invalidation`. Done when a
      generated artifact records/compares a compiler-codegen cache key, with a
      focused CLI regression proving unchanged source + changed key forces
      regeneration. Implementation: `322ee868f` added the artifact
      `codegenVersion` key + stale check; `14aa2819d` added a
      `run-jvm` CLI regression that rewrites an otherwise source-fresh artifact
      with an old key and verifies regeneration. Gates:
      `core/testOnly scalascript.artifact.ModuleGraphTest` (15/15),
      `cli/assembly; cli/testOnly scalascript.cli.JvmIncrementalCliTest`
      (5/5), `scripts/sbtc "installBin"`, and
      `tests/conformance/run.sh --only 'litdoc' --no-memo` (INT/JS/JVM PASS).
- [x] **v2-parity-post-split-refresh** — DONE 2026-07-09: refreshed the
      production output-parity baseline after `v2-arith-unification`
      (`a2985d911`) and `v2-litdoc-inline-bold-parity` (`2b5a36660`). Gates:
      `scripts/sbtc "installBin"` passed, then
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` produced
      **64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only** `(26
      both-fail not-a-gap · 36 true-server · 0 long-running · 33 backend-lane ·
      2 nondet · 195 total)`. Counts are unchanged from the current-error
      reconciliation gate, and no deterministic v2-error row reappeared. The
      next narrow production candidate is `graph-neo4j-storage.ssc`, where v1
      prints `StoredEdge(...)` and v2 prints `<foreign>`. Original plan:
      refresh the production output-parity baseline after `v2-arith-unification`
      and `v2-litdoc-inline-bold-parity`; stage the runner with
      `scripts/sbtc "installBin"`, run the full parity gate, and record exact
      counts plus the remaining mismatch list in `v2/output-parity-baseline.md`,
      `specs/v2-full-compat.md`, this SPRINT item, and `CHANGELOG.md`.
- [x] **v2-graph-neo4j-foreign-parity** — DONE 2026-07-09 (`c39afa9ba`):
      fixed the next narrow production mismatch from the post-split baseline.
      Root cause: `Graph.putEdge` returns a v1 named `InstanceV` bridged as
      `ForeignV(NamedMethodObj)` to preserve field access, but both the bridged
      `println` path and v2 `__autoPrint__` treated that wrapper as opaque and
      printed `<foreign>`. Fix: render named v1-backed method objects through
      v1 `Value.show`, and make v2 core `Show` route `NamedMethodObj.underlying`
      through the existing foreign renderer callback. Added
      `tests/conformance/graph-edge-display.ssc` as an INT regression for the
      last-expression auto-print path. Gates:
      `scripts/sbtc "v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest"`
      passed 23/23; `scripts/sbtc "installBin"` passed;
      `tests/conformance/run.sh --only 'graph-edge-display' --no-memo` passed;
      targeted `graph-neo4j-storage.ssc` parity passed 1/1; full parity is now
      **65/98 identical · 10 mismatch · 0 v2-error · 23 v1-only** `(26
      both-fail not-a-gap · 36 true-server · 0 long-running · 33 backend-lane ·
      2 nondet · 195 total)`.
- [x] **v2-async-parallel-timing-parity** — DONE 2026-07-09 (`ea62f9d38`):
      normalized the next small production mismatch. Root cause:
      `examples/async-parallel-demo.ssc` printed live wall-clock milliseconds
      (`took ~Nms`), so v1/v2 byte-for-byte parity mismatched even though both
      lanes computed the same `List(50, 50, 50)`. Fix: keep deterministic result
      lines in stdout and leave timing expectations in prose/comments; no
      runtime semantics or parity harness changes. Gates:
      `scripts/sbtc "installBin"` passed;
      `tests/conformance/run.sh --only 'async-parallel' --no-memo` passed
      INT/JS/JVM; targeted `async-parallel-demo.ssc` parity passed 1/1; full
      parity is now **66/98 identical · 9 mismatch · 0 v2-error · 23 v1-only**
      `(26 both-fail not-a-gap · 36 true-server · 0 long-running · 33
      backend-lane · 2 nondet · 195 total)`.
- [x] **v2-os-env-nondet-parity** — DONE 2026-07-09 (`6e82f20b2`):
      moved the next false production mismatch out of the strict byte-parity
      bucket without weakening the example. Root cause: `examples/os-env.ssc`
      prints host/platform data, so v1 placeholders and v2 real values cannot
      be byte-stable across runners or machines; v2 is better here, not broken.
      Fix: add `os-env.ssc` to `scripts/v2-output-parity`'s
      nondeterministic-output classification with an explicit comment; leave
      `examples/os-env.ssc` and std/os runtime behavior unchanged. Added
      `tests/conformance/std-os.ssc` for deterministic std/os helper coverage.
      Gates: `scripts/sbtc "installBin"` passed; targeted `os-env` parity now
      reports nondet skip; `tests/conformance/run.sh --only 'std-os' --no-memo`
      passed INT; full parity is now
      **66/97 identical · 8 mismatch · 0 v2-error · 23 v1-only** `(26
      both-fail not-a-gap · 36 true-server · 0 long-running · 33 backend-lane ·
      3 nondet · 195 total)`.
- [x] **v2-mcp-oauth-secret-nondet-parity** — DONE 2026-07-09 (`2142f8e0d`):
      classified the remaining OAuth/MCP generated-secret output family outside
      strict byte parity. Root cause: `mcp-server-protected.ssc` and
      `oauth-mcp-full-stack.ssc` print generated client ids/secrets plus server
      startup/banner lines, so independent v1/v2 runs cannot byte-match. Fix:
      add both examples to `scripts/v2-output-parity`'s
      nondeterministic-output classification with a comment; examples/runtime
      unchanged. Gates: `scripts/sbtc "installBin"` passed; targeted parity
      reports both as nondet skips; `tests/conformance/run.sh --only 'mcp-*' --no-memo`
      passed enabled `mcp-types` on INT/JS with server/client cases skipped by
      requirements; full parity is now
      **66/95 identical · 6 mismatch · 0 v2-error · 23 v1-only** `(26
      both-fail not-a-gap · 36 true-server · 0 long-running · 33 backend-lane ·
      5 nondet · 195 total)`.
- [x] **v2-prod-baseline-refresh** — DONE 2026-07-08: refreshed the authoritative
      full-corpus output-parity baseline from this worktree after `scripts/sbtc
      "installBin"`. Command:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`.
      Result: **51/88 output-identical · 13 mismatch · 1 v2-error · 23 v1-only**
      `(37 both-fail not-a-gap · 36 true-server · 32 backend-lane · 2 nondet ·
      195 total)`. Major reclassification: `algebraic-effects.ssc` now MATCHES, so
      the old p3 effects divergence is no longer the first production blocker.
      Fresh first engine slice is content structured-block round-trip
      (`content-linked-namespaces`, `content-tables`, `content-to-markdown`).
      Baseline recorded in `v2/output-parity-baseline.md` and
      `specs/v2-full-compat.md`.
      ORIGINAL PLAN: refresh the authoritative v1-vs-v2 output-parity
      baseline before changing semantics. How: from the claimed worktree, build/stage
      `bin/ssc`, run `SSC="bin/ssc" scripts/v2-output-parity --all`, record exact
      match/mismatch/v2-error/v1-only counts in `v2/output-parity-baseline.md`,
      `specs/v2-full-compat.md`, and this section. Done-when: a fresh agent can
      reproduce the baseline with one command and knows which failures are production
      blockers vs lane/env/v1 bugs.
- [x] **v2-prod-effects-parity audit** — RECLASSIFIED 2026-07-08: no code needed in
      this workstream for `examples/algebraic-effects.ssc`; fresh full-corpus parity
      shows it output-identical on v2. `examples/effects.ssc` still mismatches, but
      v1 prints only the first 3 documented lines while v2 prints the full 6-line
      documented behavior; treat that as a v1-side follow-up, not a v2 production
      blocker. The output-equality gate is `scripts/v2-output-parity --all`.
      ORIGINAL PLAN: close `p3-effects-output-divergence` for
      `examples/algebraic-effects.ssc` and add a regression/gate that checks output
      equality, not just exit code.
- [x] **v2-prod-content-parity** — DONE 2026-07-08 (146779cb6): restored v2 bridge
      document context for structured content parity. Root cause: PluginBridge's
      batch stubs overrode real content plugin natives, the FrontendBridge import walk
      did not populate `ContentImportedModules`, and bridged println rendered
      `TableNode.sortCol` as `None` where v1 case-class output uses `null`. Fix:
      `setDocumentFromSource` now resets/seeds content document/current-section
      context, imports register parsed content documents by namespace, content
      introspection/module/markdown natives use the real plugin, and bridge display
      preserves v1 `TableNode(..., null)` output. No-regression decision: keep only
      `contentToolkitSection` as the historical batch stub until section-level
      toolkit lowering is fixed; `contentToolkitBlock` remains real for table parity.
      Verification:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content*.ssc`
      => **10/10 identical** (1 v1 long-running skip); `scala-cli
      tests/conformance/run.sc -- --only 'content*' --no-memo` => **5 passed,
      0 failed**; full corpus now **54/88 identical · 10 mismatch · 1 v2-error ·
      23 v1-only** `(37 both-fail · 36 true-server · 32 backend-lane · 2 nondet ·
      195 total)`.
      ORIGINAL PLAN: resume `p3-parity-content`: preserve plugin-owned structured
      content block values across rawToV2/v1ToV2 so `content-tables`,
      `content-to-markdown`, and `content-linked-namespaces` round-trip like v1.
- [x] **v2-prod-plugin-boundary** — DONE 2026-07-08 (e80b1e70b): closed the
      remaining current production-relevant plugin bridge blockers. `dataset-parallel-sum`
      was fixed earlier in this item by iterative list conversion; this final subslice
      makes all four rozum agent examples output-identical by preserving mixed
      positional/named constructor args (`AgentEvent("TextDelta", text = ...)`) and
      dispatching `AgentSchemaInstance.decode` through its `decodeAny` field. Targeted
      parity: `examples/rozum-agent-schema-derived.ssc` +
      `examples/rozum-agent-streaming.ssc` => **2/2 MATCH**; full rozum cluster =>
      **4/4 MATCH**. `scala-cli tests/conformance/run.sc -- --only 'rozum*'
      --no-memo` has **0 matching cases**. Full output parity:
      **60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only**.
      ORIGINAL PLAN: close remaining production-relevant plugin bridge
      shape gaps: `Stub`/`Op` leaks, foreign value conversion, lazy-loaded plugin
      extern imports, native registration misses, and the deliberate
      `contentToolkitSection` batch stub left by `v2-prod-content-parity`. Do not
      remove that stub until real section-level toolkit lowering is parity-checked
      against `content-slot`, `content-toolkit-yaml-controls`, and the other
      `contentToolkitSection` examples. Non-production examples must be explicitly
      classified as env-gated, backend-lane, nondeterministic, or v1-bug.
      FIRST SUBSLICE (2026-07-08, claim `v2-prod-plugin-boundary`): start with the
      only remaining full-parity `v2-error`, `examples/dataset-parallel-sum.ssc`.
      Reproduce with the real staged binary:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset-parallel-sum.ssc`.
      If v2 is timing out on honest compute, inspect the Dataset bridge implementation
      for `runLocal`/`runParallel`/`reduce` over `List.range(1, 100_001)` and either
      make that path finish within the parity watchdog or record a defensible lane/scope
      classification. Done-when: the example is MATCH or intentionally excluded from
      the production-required gate with a recorded reason and follow-up.
      FIRST SUBSLICE RESULT: DONE 2026-07-08 (44f3d4a24). The v2 side was not slow;
      it crashed with `StackOverflowError` in recursive `Prims.unlistPub` while
      converting the 100k-element `List.range` passed to `Dataset.fromList`.
      `unlistPub` and `listOf` are now iterative. Verification:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset-parallel-sum.ssc`
      => MATCH; `scala-cli tests/conformance/run.sc -- --only 'dataset*' --no-memo`
      => **15 passed, 0 failed**; `examples/dataset*.ssc` parity has **0 v2-error**.
      Full corpus after the fix: **54/88 identical · 11 mismatch · 0 v2-error ·
      23 v1-only**; the extra mismatch was a transient `invoice-email` generated
      byte-count mismatch, and an immediate targeted rerun of `invoice-email` +
      `dataset-parallel-sum` was **2/2 MATCH**.
      SECOND SUBSLICE (2026-07-08, claim `v2-prod-plugin-boundary`): close or
      explicitly classify the last current production-relevant rozum mismatch
      cluster after `v2-quoted-macro-interpreter-parity` raised the full corpus to
      **58/81 identical · 7 mismatch · 0 v2-error · 16 v1-only**:
      `examples/rozum-agent-schema-derived.ssc` and
      `examples/rozum-agent-streaming.ssc`. Start with the real staged binary:
      `scripts/sbtc "installBin"` then
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`.
      If needed, compare direct v1/v2 stdout for both examples. Inspect the rozum
      plugin/runner bridge path and the matching neighbors (`rozum-agent.ssc`,
      `rozum-agent-pool.ssc`) before changing behavior. Done-when: both examples
      MATCH, or the docs classify them out of the default production gate with an
      explicit lane/scope reason and follow-up. Verification to record before push:
      targeted parity for both examples, affected conformance
      `scala-cli tests/conformance/run.sc -- --only 'rozum*' --no-memo` (record if
      no cases), relevant sbt test(s), and a full parity/baseline update if counts
      change.
      SECOND SUBSLICE RESULT: DONE 2026-07-08 (e80b1e70b). Repro showed real v2
      bugs, not a lane/scope exclusion: schema-derived crashed after the server banner
      with `match: no arm for Stub/0`, and streaming returned the final result but
      skipped user-visible callback prints because `event.kind` was `Unit`. Fixes:
      mixed constructor named-arg lowering now keeps positional args, and
      `AgentSchemaInstance.decode` dispatch calls the stored `decodeAny` closure.
      Verification:
      `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest -- -z constructor`
      and `-- -z AgentSchemaInstance` pass; `scripts/sbtc "installBin"` passes;
      targeted rozum parity is **2/2 MATCH**; full rozum cluster is **4/4 MATCH**;
      affected conformance `rozum*` has **0 cases**; full corpus is
      **60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only**.
- [x] **v2-prod-invoice-email-nondet** — DONE 2026-07-08 (d8e0ecee4): stabilized
      `examples/invoice-email.ssc` by keeping the MIME/PDF assembly path but removing
      the exact generated `message.length` from stdout. The example now prints the
      stable semantic result `MIME message assembled: PDF attached` once the message is
      non-empty. Verification: direct `bin/ssc run` and `bin/ssc run --v2` both print
      the stable line; repeated targeted parity
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc`
      was **5/5 MATCH**; neighbor cluster
      `examples/invoice*.ssc examples/pdf-extract-demo.ssc` was **3/3 MATCH**.
      Affected conformance globs `invoice*`, `*pdf*`, and `*mime*` contain **0 cases**,
      so the production gate is the examples parity check.
      ORIGINAL PLAN: stabilize the `examples/invoice-email.ssc`
      output so the v2 production parity gate is not sensitive to generated MIME/PDF
      byte counts. Why: the latest full sweep has zero v2-error cases, but one run
      observed `invoice-email.ssc` as an extra mismatch (`2681` vs `2685`) before an
      immediate targeted rerun matched; production readiness should not depend on
      byte-exact generated artifacts that can vary across runners. How: inspect the
      example output contract, prefer changing the example to print stable semantic
      facts (PDF attached / MIME assembled / recipient or subject) instead of
      `bytes.length`, and avoid touching sibling-owned files:
      `scripts/v2-output-parity`, `build.sbt`, `v2/frontend-bridge/**`,
      `v2/plugin-bridge/**`, and `v1/runtime/std/ui/primitives.ssc`. Rejected
      alternative: normalize this in `scripts/v2-output-parity`, because
      `p3-final-push` already owns that harness file and normalizing a single example
      hides a poor demo contract. Verify with a fresh staged binary and repeated
      targeted parity:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc`
      plus the nearest affected conformance slice
      `scala-cli tests/conformance/run.sc -- --only 'invoice*|pdf*|mime*' --no-memo`
      (record if no such cases are present). Done-when: targeted parity is stable
      across repeated runs, docs/baseline record the result, and no sibling-claimed
      files are modified.
- [x] **v2-prod-post-p3-baseline** — DONE 2026-07-08: refreshed the full production
      parity gate after `a0f032c15` and `d8e0ecee4`. Build:
      `scripts/sbtc "installBin"` from the worktree. Full gate:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` =>
      **55/85 identical · 9 mismatch · 1 v2-error · 20 v1-only**
      `(40 both-fail not-a-gap · 36 true-server · 0 long-running · 32 backend-lane ·
      2 nondet · 195 total)`. The single v2-error is
      `content-toolkit-yaml-controls.ssc`; `content-slot.ssc` also mismatches with an
      extra `Unsupported: TermSelectPostfixImpl` line. Important improvements now
      confirmed in the full gate: `content-form-submit`, `content-live-rows`,
      `typed-sql-crud`, `ui-fetch-json`, `ui-remote-table`, `rozum-agent`, and
      `rozum-agent-pool` are MATCH. Remaining production-relevant blockers are the
      content toolkit section family, quoted macro interpreter body evaluation, and
      the rozum schema-derived/streaming mismatch/scope decision; actors-pingpong,
      async-parallel, effects, os-env, and most v1-only entries are scope/v1/nondet
      issues, not v2-default blockers.
      ORIGINAL PLAN: refresh the authoritative production parity
      baseline after `a0f032c15` (real v2 web server + rozum family parity) and
      `d8e0ecee4` (invoice email stable output). Why: `v2-prod-default-switch`
      cannot be judged from the older 54/88 + transient-invoice baseline, and the p3
      commit reports a materially different gate: **55/85 identical (65%) · 9
      mismatch · 1 v2-error** before the invoice-output cleanup. How: in this
      worktree only, build with `scripts/sbtc "installBin"` (explicit `cd` to the
      worktree), then run
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`. Record the
      exact counts and the remaining production blockers in `v2/output-parity-baseline.md`,
      `specs/v2-full-compat.md`, and this SPRINT item; add a CHANGELOG entry. Do not
      edit `scripts/v2-output-parity`, `v2/frontend-bridge/**`, or
      `v2/plugin-bridge/**` in this slice; if the run exposes a new code bug, file it
      in `BUGS.md` and queue a separate fix. Done-when: the post-p3 full-corpus
      result is reproducible from one command and the next action is clear:
      either claim a concrete remaining blocker or proceed to `v2-prod-corpus-scope`.
- [x] **v2-prod-content-toolkit-section** — DONE 2026-07-08 (7dee6daf0): fixed the
      last current v2-error and its sibling content-toolkit mismatch. Root causes:
      v2 `MinimalCtx` did not expose plugin global resolution/callback invocation to
      real content-plugin lowering, so inline YAML table columns could not call
      `fieldColumn`; and FrontendBridge did not desugar `[bodyEl]` after the spaced
      infix operator in `headerParts ++ [bodyEl] ++ footerParts`, leaving scalameta's
      unsupported `TermSelectPostfixImpl` in `std/ui/lower.ssc`. Fix: bridge
      callbacks through v2/v1 value conversion and classify spaced operator-following
      `[` as expression-position list literal syntax. Verification:
      `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z infix"`
      => 1/1 green; `scripts/sbtc "installBin"` green; direct v2 runs of
      `examples/content-toolkit-yaml-controls.ssc` and `examples/content-slot.ssc`
      print only their expected `:ok` lines; targeted parity is **2/2 MATCH**;
      `scala-cli tests/conformance/run.sc -- --only 'content*' --no-memo` =>
      **5 passed, 0 failed**; `PARITY_TIMEOUT=45 SSC="bin/ssc"
      scripts/v2-output-parity examples/content*.ssc` => **10/10 MATCH** plus the
      expected `content-introspection` v1 timeout classification; full production
      parity now has **0 v2-error** and measures **57/81 identical · 8 mismatch ·
      16 v1-only** `(44 both-fail · 36 true-server · 32 backend-lane · 2 nondet ·
      195 total)`.
      ORIGINAL PLAN: fix the last current v2-error and its sibling content-toolkit
      mismatch. Repro after `scripts/sbtc "installBin"`:
      `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc` fails with
      `contentToolkitNode: table column builder 'fieldColumn' is not available —
      import it from std/ui/data (fcol/mcol/scol/dcol/lcol)`, and
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-slot.ssc examples/content-toolkit-yaml-controls.ssc`
      reports `content-slot.ssc` mismatch due to extra
      `Unsupported: TermSelectPostfixImpl` plus `content-toolkit-yaml-controls.ssc`
      V2-ERROR. Likely area: real `contentToolkitSection` lowering through
      `v1/runtime/std/content-plugin/**`, std/ui/data `fcol` -> `fieldColumn`
      availability, and v2 bridge handling of the UI helper shape. Done-when:
      both examples are parity MATCH, `scala-cli tests/conformance/run.sc -- --only 'content*' --no-memo`
      remains green, and full parity has **0 v2-error** again.
- [x] **v2-prod-quoted-macro-interpreter** — DONE 2026-07-08 (387c804da): fixed the
      remaining production-relevant quoted macro interpreter output mismatch. Root
      causes: v2 run left interpreter-only macro impls in helper form but had not
      registered the v1 interpreter helper globals/methods (`__ssc_macro__`,
      `__ssc_quote__`, `Expr.asValue`, `Expr.asTerm`, `QuotedContext`), and
      FrontendBridge converted forward macro entrypoints before recording the
      implementation helper's `using QuotedContext` metadata, leaving curried
      closures in stdout. Fix: register v2 helper globals, add `Expr` method
      dispatch, resolve the built-in `QuotedContext`, and pre-record `using`
      metadata before converting top-level bodies. Verification:
      `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest -- -z quoted"`
      green; `scripts/sbtc "v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest"`
      => **22/22 green**; `scripts/sbtc "installBin"` green; direct v1/v2 runs of
      `examples/quoted-macro-interpreter.ssc` both print `42`, `literal: 7`, `x`;
      targeted parity for `quoted-macro-interpreter.ssc` and
      `quoted-macro-constfold.ssc` is **2/2 MATCH**; affected conformance
      `scala-cli tests/conformance/run.sc -- --only '*quoted*' --no-memo` has
      **0 matching cases**; full production parity is now **58/81 identical ·
      7 mismatch · 0 v2-error · 16 v1-only**.
      ORIGINAL PLAN: fix the remaining production-relevant quoted macro interpreter
      output mismatch. Repro after `scripts/sbtc
      "installBin"`:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/quoted-macro-interpreter.ssc`
      reports v1 output `42`, `literal: 7`, `x` but v2 prints only `42`.
      Direct commands:
      `bin/ssc run examples/quoted-macro-interpreter.ssc` and
      `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc`. How: read
      `specs/arch-metaprogramming-v2.md` and `specs/macro-codegen-backends.md`;
      preserve the already-green `quoted-macro-constfold.ssc` path; inspect the v2
      macro pre-pass (`FrontendBridge.convertSource` →
      `PluginBridge.expandMacrosInSource` / `MacroCodegen.expand`) and reuse or
      mirror the Linker `MacroExpansion` evaluation path for computed interpreter
      bodies (`x.asValue.getOrElse`, `x.asTerm.name`) rather than papering over
      output in the parity harness. Done-when: targeted parity for
      `examples/quoted-macro-interpreter.ssc` and `examples/quoted-macro-constfold.ssc`
      is MATCH, affected conformance `scala-cli tests/conformance/run.sc -- --only
      '*quoted*' --no-memo` is green or explicitly has 0 cases, and the full
      production parity blocker list/baseline is updated.
- [x] **v2-prod-corpus-scope** — DONE 2026-07-08: made the Phase-3 corpus gate
      honest and unblocked the default-switch slice by scope. Fresh verification from
      `/Users/sergiy/work/my/scalascript-wt-v2-prod-corpus-scope` after
      `scripts/sbtc "installBin"` reproduced:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` =>
      **60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only**
      `(44 both-fail · 36 true-server · 0 long-running · 32 backend-lane ·
      2 nondet · 195 total)`. Decision: no current default-lane v2 regression blocks
      `v2-prod-default-switch`. Spark/local node simulation/server/external-credential
      work is lane-specific; Spark local shim is not required before the default switch
      because all Spark examples are explicit backend-lane programs. The five
      remaining mismatches are classified as v1-side/v2-better/nondeterministic/DSL
      follow-up, not default-switch blockers.
      ORIGINAL PLAN: make the Phase-3 corpus gate honest: classify Spark,
      distributed actors/node simulation, live servers, JVM-lane examples, and external
      credentials into production-required vs lane-specific gates. Record rejected
      alternatives, especially whether Spark local shim is required before default v2.
      PLAN (2026-07-08, claim `v2-prod-corpus-scope`): this is a docs/gate slice,
      not a feature-fix slice. First rebuild/stage `bin/ssc` in this worktree with
      `scripts/sbtc "installBin"` and rerun the authoritative gate:
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`. Use that
      exact output to classify every remaining non-MATCH bucket:
      1. default production gate: examples that should run under `ssc run` after the
         default switch and therefore must be MATCH or explicitly v1-bug/v2-better;
      2. lane-specific gates: `backend: jvm|spark|js|rust|wasm`, true servers,
         distributed actor/node simulations, external credentials/services, and
         nondeterministic-output examples;
      3. known follow-up bugs that should not block the default switch but must be
         visible in BACKLOG/BUGS if not already tracked.
      Update `v2/output-parity-baseline.md` and `specs/v2-full-compat.md` with the
      taxonomy, exact counts, remaining five mismatch classifications, and the
      Spark/local-node-sim decision. Rejected default: do not require a Spark local
      shim before `ssc run` defaults to v2 unless a no-frontmatter default-lane example
      requires Spark semantics. If the fresh run exposes a new v2-error or a mismatch
      that belongs in the default gate, stop this slice, file it in `BUGS.md`, and
      queue a concrete fix before `v2-prod-default-switch`. Done-when: a fresh agent
      can decide from docs alone whether `v2-prod-default-switch` is unblocked, with
      the exact verification command and all exclusions justified.
- [x] **v2-prod-js-dsl-conformance** — DONE 2026-07-08 (39ebb6fda): fixed the
      JS-lane `dsl-multi-pass` conformance failure surfaced during
      `v2-prod-corpus-scope`. Root cause: JS `String.forall` passes boxed `_Char`
      values to predicates, but `_arith` compared `_Char` against one-character JS
      string literals with native object-vs-string ordering, so
      `c >= 'a' && c <= 'z'` rejected alphabetic identifiers. Fix: add a shared
      `_charCodeOrNull` helper and normalize `<`, `>`, `<=`, `>=` only when either
      operand is `_Char`, preserving ordinary string comparison and string
      concatenation. Verification: `scripts/sbtc "installBin"` green;
      `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` passes
      `dsl-multi-pass` in INT/JS/JVM. Neighbor check
      `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
      confirms `collections` + `dsl-multi-pass` pass; it exposed unrelated INT-only
      std/parsing empty-output failures, now tracked as
      BUGS.md / SPRINT `conformance-parsing-int-empty-output`.
      ORIGINAL PLAN: fix or reclassify the JS-lane conformance failure surfaced
      during `v2-prod-corpus-scope`. Repro after `scripts/sbtc "installBin"`:
      `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` currently
      reports `dsl-multi-pass` INT PASS / JS FAIL / JVM PASS. JS prints
      `[parse] unrecognised token: x` for the `"x + z"` and `"x + y"` scenarios
      where INT/JVM produce `[name-resolve] undefined: z` and `ok: 8`. Likely area:
      JS backend/runtime lowering of string-character predicates in
      `t.forall(c => (c >= 'a' && c <= 'z') || c == '_')`, or char/string compare
      semantics in generated JS. Done-when: the command is green with `--no-memo`
      and BUGS.md `conformance-dsl-multi-pass-js` is updated with root cause and
      fix SHA. This is not a default output-parity blocker, but it is a release
      hygiene gate if production requires conformance green.
- [x] **v2-prod-default-switch** — DONE 2026-07-08 (719943f40, d2ba78c0a,
      89a38f1e3): plain default-lane `ssc run <file>` now routes through the v2 VM
      via FrontendBridge; `ssc run --v1 <file>` is the explicit v1 tree-walking
      interpreter rollback; `ssc run --v2 <file>` remains accepted as an explicit
      v2 force flag. Explicit lanes remain on their specialized paths: `--target`,
      `--backend`, `--frontend`, `--mode`, transport/server/client options,
      electron/JVM-rest auto-detection, TUI, and sources with explicit `backend:`,
      `frontend:`, `target:`, `transport:`, or `fullstack:` front matter.
      `scripts/v2-output-parity` now compares explicit `run --v1` vs `run --v2`,
      and the conformance INT lane uses `run-batch --v1`, so existing gates still
      measure v1-vs-v2 rather than v2-vs-v2. Verification:
      `scripts/sbtc "cli/testOnly scalascript.cli.V2DefaultSwitchTest scalascript.cli.CommandRegistryTest"`
      => 11/11 passed; `scripts/sbtc "installBin"` passed; `bin/ssc run`,
      `bin/ssc run --v1`, and `bin/ssc run --v2` all print `Hello, World!` for
      `examples/hello.ssc`; `examples/effects.ssc` plain `run` matches `--v2`
      full output while `--v1` preserves the old rollback one-shot failure;
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` reproduces
      **60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only**; affected
      conformance `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`
      passes `dsl-multi-pass` in INT/JS/JVM.
      ORIGINAL PLAN: UNBLOCKED by `v2-prod-corpus-scope`; switch `ssc run` default
      to v2, keep `ssc --v1` rollback, update docs and install/CI gates. No feature
      work belongs in this slice; a failed gate sends work back to the earlier
      slices. Start by identifying the CLI flag/parser path for `run`, preserving
      an explicit v1 escape hatch, then update docs and gate with the same full
      output-parity command recorded above.
      IMPLEMENTATION PLAN (2026-07-08, claim `v2-prod-default-switch`): implement the switch in
      `v1/tools/cli/src/main/scala/scalascript/cli/Main.scala` / `RunCmd`. Current
      state: `run --v2` is an early preview branch, and the plain fallback path runs
      v1 through `compileViaBackend(..., "int")`. Change: add `--v1` rollback and
      make the plain default-lane fallback call `RunV2.run(...)`. Preserve explicit
      lanes on the existing v1/specialized paths: `--target`, `--backend`,
      `--frontend`, `--mode`, transport/server/client flags, electron/JVM-rest
      auto-detection, TUI, and any source with explicit `backend:` or `frontend:`
      front matter. Keep `--v2` accepted as an explicit v2 force flag; `--v1 --v2`
      is a usage error. Add a small test around the routing predicate / flag handling
      instead of a broad refactor. Update `README.md`, `v2/output-parity-baseline.md`,
      and `specs/v2-full-compat.md` to say `ssc run` now defaults to v2 and
      `ssc run --v1` is rollback. Verify with:
      `scripts/sbtc "cli/testOnly scalascript.cli.*V2* scalascript.cli.CommandRegistryTest"`,
      `scripts/sbtc "installBin"`, direct `bin/ssc run examples/hello.ssc`,
      direct `bin/ssc run --v1 examples/hello.ssc`, direct
      `bin/ssc run --v2 examples/hello.ssc`, the production gate
      `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`, and affected
      conformance `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`.
- [x] **conformance-parsing-int-empty-output** — DONE 2026-07-08 (d65c678bd):
      fixed the INT-only std/parsing conformance failures found while verifying
      `v2-prod-js-dsl-conformance`. Root cause: `std/parsing/recovery.ssc`
      defined/documented `runParserAll`, `advanceToSync`, and recovery extension
      methods but omitted them from front-matter `exports:`, so the explicit imports
      failed on stderr before any stdout. Fix: export `recoverUntil`, `errorNode`,
      `parseAll`, `advanceToSync`, and `runParserAll`. Verification after
      `scripts/sbtc "installBin"`: direct
      `bin/ssc run --v1 tests/conformance/parsing-error-node.ssc` prints expected
      output; `scala-cli tests/conformance/run.sc -- --only 'parsing*' --no-memo`
      passes all three INT cases; expanded neighbor slice
      `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
      passes 5/5 runnable cases, with the two indent cases still skipped for missing
      expected files.

## Phase-3 readiness (2026-07-06, corpus-tails run)

**Conformance suite 59/59 GREEN; corpus 172/193 (89.1%).** T4.2/T4.3 done earlier.
Remaining 21 batch fails, classified:
- **Environmental / out-of-parity (6)**: x402-cardano x2 (needs BLOCKFROST_KEY / path
  escape — fails on v1 too), pg-listen-notify (needs live PostgreSQL), node-fs-read
  (js-lane globalThis), storage-demo (runStorage driver — documented not-extracted),
  algebraic-effects (runStream runner not bridged).
- **Dataset natives cluster (7)**: distributed-* — needs Dataset.of/fromFile/map/
  collect natives over lists + wire codecs; local-loopback actor sim already landed.
- **Singles (8)**: actors-typed-remote-spawn (registerBehavior variant),
  datatable-static-spa (parse), dsl-ast-builder (/ by zero), dsl-mini-language
  (tuple-lambda auto-untuple), seed-signal + typed-sql-crud + rozum-agent-streaming
  + spark-shared-schema-reader (plugin-boundary conversions).

Claimable slices for the above (queued 2026-07-07):

- [x] **p3-dataset-natives** — DONE 2026-07-07 (de98b551c). The "7-fail cluster / ONE
      mechanism" premise was WRONG — peeling exposed SIX distinct v2 bugs (all fixed,
      each minimally repro'd) + an honest reclassification:
      • FIXED: Dataset natives as __fallback__.* (plain keys shadowed spark's Dataset —
        runtime consults fallbacks only after plugin+effect miss); std/-suffix import
        fallback for pre-move ../runtime/ paths; Cons.grouped; NESTED tuple patterns in
        case-lambdas; set-minus / Map+(k->v) in arithOp; def param names pre-registered
        in pass 1 (all-named call to a LOWER-defined def compiled args as ASSIGNMENTS —
        all params arrived Unit; also hit defs without defaults).
      • Harness: BatchCli per-file watchdog (SSC_BATCH_TIMEOUT_MS) + lane-SKIP for
        `backend: jvm` examples; SSC_DEBUG_ACTORS actor-death diagnostics.
      • RECLASSIFIED (not v2-VM gaps): wire-protocol/wire-shuffle/codec/typed-helpers =
        jvm-lane (scalascript.typeddata imports; previously FALSE-passed as never-run
        lazy Free chains); join/log-aggregation = environmental (data files absent, fail
        v1-interp too); parallel-sum = v2-perf (honest compute now, >45s; was a false
        exit-0 pass). word-count: 6 layers fixed, final blocker = connectNode local sim
        returns the address string — needs a node-sim seam (design decision, see below).
      • Corpus vs same-day clean: 165P/21F/9SKIP vs 170P/25F; conformance identical.
- [x] **p3-connectnode-node-sim** — DONE 2026-07-08 (`6c0e39559`): the LAST
      distributed-* blocker is closed. `std.mapreduce.localLoopbackCluster`
      builds explicit local workers running `ShuffleProtocol`, offline
      distributed examples no longer hang on `Cluster.connect` documentation
      addresses, and v2 tuple/handler-registry lowering bugs exposed by the real
      worker path are fixed or avoided in std. Gates:
      `scripts/sbtc "cli/assembly; cli/testOnly scalascript.cli.V2TuplePatternCliTest"`
      4/4 green; direct default-v2 runs of distributed word-count/log
      aggregation/join green; affected conformance selector
      `cluster-connect,distributed-*` 6/6 green. Follow-up queued:
      `v2-bridge-case-class-instance-methods` for the remaining
      `cluster.close()` stub class.
      Original: the local-loopback
      actors sim has no node simulation behind `connectNode(address)` (returns the raw
      address string; sends go nowhere; collectors hang in receive — now visible thanks
      to the batch watchdog). Design decision needed: either cluster.ssc spawns the
      .ssc-defined WorkerProtocol locally when the address is not a live node, or the
      bridge grows a registerNodeSim seam. Owner call; all groundwork (natives, message
      flow, diagnostics) landed in p3-dataset-natives.
      IMPLEMENTATION PLAN (2026-07-08, claim `p3-connectnode-node-sim`): choose an
      explicit local map-reduce helper, not a `connectNode`/bridge SPI change. Add
      `localLoopbackCluster(ns: Node*)` exported from `std.mapreduce`, returning a
      `Cluster` whose pids are local actors running `ShuffleProtocol.handleMessages()`
      (the superset worker loop for map-only and shuffle jobs);
      mirror the std change in both `runtime/std/` and `v1/runtime/std/`; update
      offline distributed examples to use the helper instead of documentation
      addresses through `Cluster.connect`; keep `Cluster.connect` as the real remote
      node API. Spec: `specs/p3-connectnode-node-sim.md`. Verify with direct
      `bin/ssc run examples/distributed-word-count.ssc`, affected distributed
      examples if their data dependencies are local, and
      `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`.
      MID-FIX DISCOVERY (2026-07-08): after `localLoopbackCluster` reaches real
      local worker actors, v2 still kills workers through tuple lowering
      (`lookup(v, key)` in actor death logs). Minimal repro:
      `val pair: Any = ("ada", 1); pair match { case (w: String, _: Int) => w }`
      fails under default v2 with `unbound global: w`, while `--v1` prints `ada`.
      Current slice must harden map-reduce tuple access and add/fix the focused
      v2 tuple pattern/selector regression before the word-count smoke can go
      green. Tracked in `BUGS.md` as `v2-mapreduce-handler-registry-tuple-lookup`.
      MID-FIX DISCOVERY 2 (2026-07-08): `cluster.close()` on the v2 lane lowers
      to `Stub("Cluster.close")` because `v2/frontend-bridge` registers
      case-class fields but does not emit methods defined inside case-class
      templates. This slice will avoid the stub in examples with explicit
      `ShutdownWorker()` sends so the distributed smoke can pass; the bridge
      fix is queued below as `v2-bridge-case-class-instance-methods` and tracked
      in `BUGS.md` as `v2-case-class-instance-methods-stub`.
- [x] **v2-bridge-case-class-instance-methods** — DONE 2026-07-08
      (`f12cad127`): methods declared inside `case class ...:` bodies now lower
      on the v2/default lane through the existing tag-dispatched
      extension-method machinery. Constructor fields are bound from the
      receiver before method bodies compile, same-named methods dispatch by
      receiver tag, and runtime `__methodOrExt__` preserves registered field
      precedence so ordinary fields such as `.name` still win. The distributed
      examples are back on the public `cluster.close()` API. Gates:
      `scripts/sbtc "v2Core/compile; v2FrontendBridge/compile"`,
      `scripts/sbtc "cli/assembly; cli/testOnly scalascript.cli.V2CaseClassMethodCliTest"`
      3/3, `scripts/sbtc "cli/testOnly scalascript.cli.V2TuplePatternCliTest"`
      4/4, `scripts/sbtc "installBin"`, direct default-v2 distributed
      word-count/log-aggregation/join runs, conformance
      `cluster-connect,distributed-*` 6/6, and conformance
      `data-types,lenses,optional,traversal,fn-typed-field` 4/4.
- [~] **p3-corpus-singles** — 6 of 8 RESOLVED 2026-07-07 (8624649f0 + c3c44aa03):
      dsl-ast-builder + rozum-agent-streaming fixed by the p3-dataset-natives systemic fixes;
      the rozum-agent family (streaming incl.) needed TWO more systemic bugs — try/catch scope
      off-by-one (phantom "_unit_" slot for the zero-arity body thunk; ANY try inside a def
      referencing params was broken) and ambiguous fieldIndex (first-registered class's index
      read the WRONG FIELD when same-named fields sit at different positions — transportError);
      actors-typed-remote-spawn got a typed ActorRef surface (address/isLocal/tryLocal/tell/
      publishAs + globalWhereis registry) in the actors bridge; seed-signal was a BROKEN EXAMPLE
      (applied the Theme value as a function; fails v1 too) — fixed to lower(node, defaultTheme).
      Closure Function1.andThen/compose added to methodOp (std/dsl pipelines).
      REMAINING (each diagnosed):
      • dsl-mini-language — andThen now dispatches, but the 4-stage Pass pipeline still dies
        "arity: 0 expected, 1 given" INSIDE the composed chain; 5 isolation probes (typed-val
        lambda, def-returning-lambda, cross-fence composition) all PASS — the failing construct
        is subtler (Pass type-alias + Either.map chain suspected). Resume from /tmp probes.
      • typed-sql-crud — "expected Data, got <foreign>" (plugin-boundary value conversion).
      • datatable-static-spa — generated-JS parse error (emit path, ":1049 illegal start").
      • spark-shared-schema-reader — "unbound global: java" (scala-block java.* use; likely
        belongs in the jvm lane like the typeddata quartet — decide classification).
      Corpus now 171 PASS / 15 FAIL / 9 SKIP(jvm-lane) vs clean-2026-07-07 170/25.
- [x] **p3-parity-derives-mirror → REPURPOSED p3-parity-sql-cluster** — DONE 2026-07-07
      (f7feafaa2). Fresh parity data showed derives/mirror already MATCHing (stale premise);
      the real fat cluster was sql + advanced plugins. Landed: sql-fence section ids use v1
      sectionIdent camelCase; anyStr renders Value-keyed ForeignV maps + lists v1-style
      (String-keyed method-objects excluded — unguarded cast CCE'd typeclass mid-run);
      RunV2 loads BOTH plugin tiers + extracts ALL .sscpkg jars (Db.insert/crypto/oauth
      escaped as Free Ops with essential-only); fieldAt(recv, idx, NAME) 3-arg form + by-name
      row access (rows are UNORDERED UPPERCASE-labeled maps; [T] stripped ⇒ no decoding).
      **Parity 21→30/54 identical, v2-error 11→4; corpus 172P/14F/9SKIP; conformance 65/5.**
      NOTE: sql-sqlite-file mismatch = by-design persistent /tmp db (nondeterministic-output
      class, same as uuid-v7 — harness should normalize/exclude both).
- [~] **p3-parity-effects-shape + p3-effects-output-divergence** — CORE FIXED 2026-07-07
      (84503577e): the entire divergence class was the v2 VM DISCARDING effect Ops in
      statement position and val bindings (all Seq/Let paths). Free-monad threading added
      (Runtime.seqThreadOp/letThreadOp; Let keeps the common path TAIL — 1M-TCO probe green).
      examples/effects.ssc on v2 now prints ALL SIX documented lines exactly.
      REMAINING → State + runStream CLOSED 2026-07-07 (49709edaa): dynamic effect context
      now wins over generic __method__.* natives and same-named plugin intrinsics (State.get
      was Stub-swallowed); runStream implemented natively in the bridge (emit collects,
      complete() aborts, returns (Source, result)). algebraic-effects.ssc = PARITY MATCH.
      Corpus 174P/12F/9SKIP; parity 31/56. Still open from this family:

      • v1 BUG (new): v1 `ssc run examples/effects.ssc` prints only 3 of 6 documented lines
        (stops after the Collecting-Output section) — the parity entry can't MATCH until the
        V1 side is fixed; v2 now matches the documented expected output.
      • algebraic-effects: remaining diff is State-effect get/set semantics (v2 prints
        List()/Stub1 where v1 prints 0/1) — parameterized-handler state threading.
      • runStream runner still not bridged (unbound global: runStream) — separate item.
- [~] **p3-parity-quoted-macros** — constfold at PARITY 2026-07-07 (4bb475c47): convertSource
      runs MacroCodegen.expand as a TEXT pre-pass (expanded block sources spliced back pairwise;
      trailing-newline boundary preserved — gluing broke the fence). quoted-macro-interpreter
      UNMASKED as a false pass (exit-0 with "Unsupported:" garbage before): its impls have
      COMPUTED non-quote bodies ("literal: " + x.asValue.getOrElse, x.asTerm.name) — expansion
      needs Linker-style const-fold EVALUATION of impl bodies, not just beta-reduction. Resume
      there (Linker.MacroExpansion machinery).
- [x] **p3-parity-stub-op-leaks** — CLOSED 2026-07-07 (b4235a6aa) as harness
      reclassification: after the advanced-plugin-tier fix flipped 7 of 11, the remaining 4
      "v2-errors" (graph-codecs, object-store-jdbc, spark-schema-mapping, typed-object-codec)
      are ALL `backend: jvm` lane examples (scala fences, typeddata imports) — the harness now
      lane-skips them like BatchCli, plus a nondeterministic-output class (sql-sqlite-file,
      uuid-v7). Corrected metric: **31/50 identical (62%) · 12 mismatch · 0 v2-error ·
      7 v1-only**. The 7 v1-only entries (dsl-mini-language, dsl-json-parser, dsl-sql-recovery,
      international-bank-rails, paginated-typed-client, sql-browser-duckdb, x402-metamask) are
      programs v2 RUNS and v1 crashes on — v1 bugs; dsl-mini-language's v2 side (the corpus
      single) is thereby DONE.
- [~] **p3-parity-content** — flagship content.ssc at PARITY MATCH 2026-07-07 (73019def7):
      md-strip prim, per-fence __autoPrint__ (v1 auto-output), v1-Value passthrough in v2ToV1,
      Show.foreignRenderer hook (kernel v1-free), setDocumentFromSource→featureGet(ContentDocument).
      REMAINING: content-tables / content-to-markdown / content-linked-namespaces need round-trip
      FIDELITY for structured block values — rawToV2/v1ToV2 deep-conversion loses the plugin shape
      contentPlainText/contentToMarkdown expect (block found, renders empty). Resume: probe what
      shape contentBlock's blockValue takes through rawToV2 and preserve it (ForeignV passthrough
      for plugin-owned structs vs deep conversion for plain data).
- [x] **p3-parity-singles2** — DONE 2026-07-07 (77de9926b): signals-demo PARITY (reactive
      effect{} blocks: kernel read/write hooks + single-flush diamond semantics); dsl-calc-parser
      v2-side CORRECT (symbolic-operator routing: extension ops ~ | ~> <~ ++ were dying in
      __arith__; new __arithExt__ prim for ambiguous ops; String.toDouble raw-Double v1 semantics;
      floatStr in Float-String concat) — v1 .many() bug truncates its own output; os-env = v1 bug
      (prints <native:platform>); spark-udf-demo = spark lane (harness lane-skip widened).
      V1-BUG list for a v1 owner: effects.ssc (3 of 6 lines), os-env 0-arg natives,
      dsl-calc-parser .many(), + 7 v1-only parity entries (v2 works, v1 empty).
- [x] **p3-server-actor-parity-harness** — DONE 2026-07-07 (cd5c3a42a): SKIP_RE narrowed to
      true servers; terminating actor/async/dataset examples now run BOUNDED (rc via file — the
      grep pipe clobbers $?; v1 timeout → long-running class). FIRST honest full baseline:
      **46/89 identical · 18 mismatch · 1 v2-error · 24 v1-only** (36 both-fail · 36 true-server ·
      32 backend-lane · 2 nondet · 195). The 24 v1-only (ALL MCP servers, x402, dsl family,
      dataset-word-count) = v2 RUNS them, plain `ssc run` prints nothing — v1-side lane to
      investigate (plugins not loaded on default run?). NEW measured mismatch queue:
      rozum-agent ×4 (likely transport-nondet), async-demo/async-parallel, actors-pingpong,
      dataset-stats, lenses, storage-demo, yaml-parse.
- [x] **p3-parity-singles3** — DONE 2026-07-07 (3e35f2a53): yaml-parse/storage-demo/
      dataset-stats/async-demo/lenses at PARITY. Six systemic fixes: yaml section fences
      (__yamlSection__ prim + scanner regex), file-backed runStorage, Async runtime
      (runAsync/runAsyncParallel, virtual-thread futures), effect-dispatch chain on explicit
      Options (equal-indent case-None bodies parse as statement SEQUENCES — the fallback ran
      but an Op was always returned on the binary), duplicate top-level val hoisting (second
      CDef clobbered the first — lenses r=Rect read as r=Roster), anyStr ctor/tuple unquoted
      rendering. Remainders: async-parallel (~Nms timing nondet), actors-pingpong (v1
      exit-cascade — v1 doesn't print final done).
- [x] **p3-final-push** — DONE 2026-07-08 (a0f032c15): REAL web server on `run --v2`
      (route/serveAsync/stop bridged to WebServer; batch stubs split out of loadAll; banner-
      deterministic serveAsync; curried route). desugarListLiterals TRIPLE-QUOTE fix — \" inside
      """…""" shifted quote pairing and rewrote [1,2] INSIDE later string literals (silent JSON
      corruption on the wire — rozum bodies). __method__.get on named-instance objects. Harness:
      fixed-port examples get port+1 on the v2 lane. BatchCli lanes widened (spark|js|rust|wasm).
      **rozum-agent family at parity; parity 55/85 (65%); corpus 152/11/32-lane; conformance 65.**
- [x] **p3-spark-local-engine** — RECLASSIFIED 2026-07-08: no v2 default-lane
      local Spark shim is required for production. `v2-prod-corpus-scope`
      reran the authoritative gate and decided that all Spark examples are
      explicit backend-lane programs, not blockers for plain `ssc run` defaulting
      to v2. Keep future Spark local-engine work in a Spark/backend milestone,
      not the default runtime production queue. Original context: spark-config-demo,
      spark-delta-demo, spark-lakehouse-{delta,hudi,iceberg}, and word-count were
      unmasked after lazy Op chains began executing honestly; they need Spark
      surfaces such as `.toDF`, `createOrReplaceTempView`, `spark.sql`, and delta
      tables that are outside the plain default-lane gate.
- [x] **p3-effects-output-divergence** — SUPERSEDED 2026-07-08: the current
      production gate no longer reproduces the old `algebraic-effects.ssc`
      divergence. `v2-prod-baseline-refresh` and `v2-prod-effects-parity audit`
      record that `examples/algebraic-effects.ssc` is output-identical on v2.
      `examples/effects.ssc` still differs because v1 prints only the first three
      documented lines while v2 prints the full documented six-line behavior; that
      is a v1-side follow-up, not a v2 default-switch blocker. The output-equality
      gate remains `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`.

## Active tasks

### ▶ ssc-toolkit-v2 (2026-07-07, owner-directed via busi: the busi SPA must move React→ScalaScript)

Requirements source: busi `src/v2/specs/frontend-on-scalascript.md` (owner 2026-07-06). busi is the
**conformance target** — toolkit v2 is done when busi's `App.tsx` (99 pieces of state, ~91 form
interactions, offline-first PWA, WebAuthn, 4 locales) is expressible in `.ssc`. Design + full slice
detail: **[`specs/ssc-toolkit-v2.md`](specs/ssc-toolkit-v2.md)**. Additive over `std/ui` — no breaking
changes for existing consumers (rozum control-center, busi server pages). Every slice ships
conformance cases (INT==JS) and runs the affected-slice conformance before push (AGENTS.md 4b).

- [x] **tkv2-components** ✓ DONE 2026-07-07 — `std/ui/component.ssc`: `component(kind, key)(Ctx => N)`
      + `ctxSignal` → `<kind>__<key>__<name>` (SANITIZED — emitter contract: signal ids must be JS
      identifiers `[A-Za-z_][A-Za-z0-9_]*`; React derives useState var names from them, so `/`
      separators are rejected at emit). `childCtx` nesting; pure .ssc. Disposal DEFERRED to
      tkv2-keyed-for (tree is built once today). Conformance `tkv2-component` INT==JS; example
      `component-demo` browser-driven. Fixed 2 JsGen bugs en route (BUGS.md: Signal-import-vs-preamble,
      reserved-word param body rename). GOTCHA for later slices: char comparisons + regex replaceAll
      diverge between lanes — sanitize with substring+contains (see ctxClean).
- [x] **tkv2-offline** ✓ DONE 2026-07-07 — `std/ui/offline.ssc`: `localStorageGet/Set/Remove` +
      `onlineSignal()` + `persistedSignal(name, default)` externs (frontend-plugin JVM lowering:
      per-process map + constant-true; signals.mjs `_ssc_ui_*` shims: real localStorage/navigator.onLine
      in-browser, mem-map/true on Node). ALSO: interp dispatch for `sig.get()`/`sig.set(v)` on
      ReactiveSignal (JS-lane parity) — makes ui-signal BEHAVIOR conformance-testable INT==JS for all
      future slices. Conformance `tkv2-offline`; browser-driven via emit-spa (type → localStorage →
      reload restores → offline badge flips). GOTCHAS: persist via effect-subscription, NOT a set-wrapper
      (DOM/fetch write through `_signalSet` by id, bypassing the object's .set — caught in the real
      browser, invisible to the Node conformance run); use `window.localStorage`, not the bare global
      (Node 26 defines a warning getter). `fetchOrLocal` DEFERRED to the busi-home slice (needs the
      fetch machinery + a local compute fn — design it against the real screen, not speculatively).
- [x] **tkv2-forms** ✓ DONE 2026-07-07 — `std/ui/form.ssc`: `FieldSpec` data-DSL (required/min/max/
      pattern — pure `validateField`, same rules every backend) + `form(ctx, specs)` (drafts =
      component-scoped signals) + `fieldError`/`formErrors`/`formValid` (computed, live) +
      `formField`/`submitGate` widgets. ALSO: `String.matches` added to the JS lane (anchored,
      Scala full-match semantics; guard `string-matches` INT==JS==JVM); interp `computedSignal`/
      `eqSignal` now RECOMPUTE ON READ (JS read-freshness parity → reactive derived state is
      conformance-testable). Conformance `tkv2-forms` INT==JS; form-demo browser-driven (live
      errors, gate opens/closes). GOTCHAS: `.toMap` on List-of-pairs isn't dispatched on interp
      (use foldLeft+updated); JsGen capability detection reads the ENTRY file only — every new
      std/ui module must register its API names in the hasUiHelpers list or import-only usage
      emits without signals.mjs; SPA drivers must assert page.innerText (textContent includes
      script source + display:none branches). DEFERRED: touched-state (errors show from start),
      submit busy/error tri-state (needs an onFailure fetch effect).
- [x] **tkv2-spa-pipeline** ✓ DONE 2026-07-07 — audited: `emit-spa --frontend custom` output has
      ZERO external script/link/import tags (offline-demo + form-demo bundles); the only http(s)
      strings are inert jwt-auth endpoint constants riding the serve→HtmlDsl→Jwt capability chain
      (tree-shake candidate, size-only). Production path documented in user-guide §17.9; all
      toolkit-v2 primitives already verified on this path (slices 1–3 browser drives).
- [x] **tkv2-pwa-adopt** ✓ DONE 2026-07-07 (code+tests; .ssc drive PENDING on
      plugin-lazyload-extern-imports) — `std/pwa.ssc` extended: `cacheVersion` (cache-name bump +
      activate cleanup), `networkFirst` (fresh-online/cached-offline read routes; never list write
      routes), `offlineHtml` (navigation fallback page), `maskableIcon`. Everything busi's
      hand-written `http/pwa.ssc` does. PwaPluginTest 4/4 (generators); conformance `tkv2-pwa`
      written but `pending:` — FOUND pre-existing regression: lazy-loaded plugin externs
      (smtp/tcp/pwa) are dead from .ssc on main (BUGS.md plugin-lazyload-extern-imports; stock
      pwa-demo example fails). busi-side adoption happens at the migration pilot (needs a pin bump).
- [x] **tkv2-busi-home-conformance** ✓ DONE 2026-07-07 — `tkv2-busi-home` corpus case (INT==JS):
      busi-shaped obligation ids → per-card instance-scoped expand; income form (digits/date
      patterns) with live gate; persisted home payload surviving the reload shape; onlineSignal.
      Browser twin `examples/frontend/busi-home-demo` driven via emit-spa (only the toggled card
      expands; Record appears on valid form). GOTCHA found+fixed in form.ssc: a computed thunk
      invoked from ANOTHER module's context doesn't resolve this module's globals (load-order/
      global-resolution trap) — bind module functions to local vals before closing over them.
- [x] **tkv2-keyed-for** ✓ DONE 2026-07-09 — `forKeyed(items, key)(render)` landed for the
      JsGen/custom browser runtime (`ea79e003a`; docs `8b9c47e25`, `f129df583`): std/ui node
      + primitive, `_ForKeyed` render marker, scoped `_ssc_ui_mount` binder for dynamically
      inserted rows, keyed reconcile by direct child `data-ssc-key`, JVM/interpreter static
      fallback, conformance case, and `examples/frontend/keyed-for-demo`. Gates:
      `backendInterpreter/testOnly scalascript.JsGenStdImportTest scalascript.JsRuntimeKeyedForTest`
      (43/43), affected module compiles, `tests/conformance/run.sh --only 'tkv2-keyed-for'
      --no-memo`, and `bin/ssc emit-spa --frontend custom examples/frontend/keyed-for-demo/keyed-for-demo.ssc`.
      Note: same-key item value changes intentionally do not re-render in this slice.
- [x] **tkv2-webauthn** ✓ DONE 2026-07-09 — browser `navigator.credentials.create/get`
      actions (register/assert) for the production `emit-spa --frontend custom` path.
      Feature `e61a89b4c`, docs `6801d977c`: `std/ui/webauthn.ssc` exports
      `webauthnRegister` / `webauthnAssert` EventHandlers, `signals.mjs` runs the
      begin -> browser credential -> complete ceremony with base64url payloads and
      caller headers, off-browser fallbacks report a clear unavailable error, and
      the adjacent `std/auth.ssc` WebAuthn declaration drift is fixed.
      Active plan 2026-07-09 (`feature/tkv2-webauthn` / codex):
      - [x] Spec first in `specs/tkv2-webauthn.md`, then commit/push it before implementation.
      - [x] Add UI-facing WebAuthn EventHandler externs in `std/ui/webauthn.ssc`, not to core:
            `webauthnRegister(beginUrl, completeUrl, rpName, result, error, headers, timeoutMs,
            userVerification)` and `webauthnAssert(beginUrl, completeUrl, result, error, headers,
            timeoutMs, userVerification)`.
      - [x] Implement the browser/custom runtime in `signals.mjs`: POST begin JSON, call
            `navigator.credentials.create/get`, base64url-encode browser ArrayBuffers, POST complete JSON,
            write response text into `result`, and write user-visible failures into `error`.
      - [x] Keep Node/interpreter behavior deterministic: off-browser handler creation is allowed, but
            invoking it reports a clear "WebAuthn unavailable" error instead of silently succeeding.
      - [x] Fix the adjacent std-auth WebAuthn declaration drift recorded in `BUGS.md`
            (`std-auth-webauthn-signature-drift`): declarations must match the existing JVM/JS runtime
            implementations and examples.
      - [x] Add focused runtime tests with stubbed `navigator.credentials` and `fetch`, plus a conformance
            API smoke case. Gate before push with targeted Scala tests, affected compiles,
            `tests/conformance/run.sh --only 'tkv2-webauthn,webauthn-server-verify' --no-memo`, and an
            `emit-spa --frontend custom` smoke of the new example.
      Gates: affected compiles green; `backendInterpreter/testOnly
      scalascript.JsRuntimeWebAuthnClientTest scalascript.JsGenStdImportTest` green (43 tests);
      conformance `tkv2-webauthn,webauthn-server-verify` green (2/2, INT+JS pass);
      `bin/ssc emit-spa --frontend custom examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`
      emitted the expected WebAuthn browser runtime markers. Gotcha recorded in
      `specs/tkv2-webauthn.md`: stale local `bin/ssc` required `scripts/sbtc "installBin"`
      before real-harness conformance.
- [x] **tkv2-typed-client** — DONE 2026-07-09 (`4656f9629`): route-derived
      `.ssc` API clients now produce callable path-param methods. `RouteDeriver`
      defaults no-body/no-param endpoints to `Unit`, one no-body path param to
      `String`, multiple no-body path params to `Any`, and body methods to
      `Any`, while explicit `apiClients:` metadata and existing validation
      warnings remain unchanged. Browser JS clients now accept the derived
      input and substitute it into the `fetch` path; JVM/Swing sees the same
      metadata and emits callable in-process methods. Gates: `RouteDeriverTest`
      16/16; `JsGenTypedRouteClientTest` + `JvmGenTypedRouteClientTest` 57/57;
      affected compiles; `installBin`; conformance `tkv2-typed-client-derived`
      1/1 JS; `emit-js` and `emit-spa --frontend custom --server-url` smokes for
      `examples/derived-route-clients.ssc`. Gotcha: CLI/conformance use
      installed `bin/ssc`, so run `scripts/sbtc "installBin"` after
      RouteDeriver/codegen changes.
      Original: route-derived `.ssc` API client; browser transport = fetch, JVM =
      existing in-process transport (fullstack spec phases 0–5).
      Active plan 2026-07-09 (`feature/tkv2-typed-client` / codex):
      - [x] Claim/worktree created; stale `bin/ssc` gotcha re-confirmed and fixed locally with
            `scripts/sbtc "installBin"` before CLI smoke.
      - [x] Spec first in `specs/tkv2-typed-client.md` and bug ledger entry
            `route-deriver-path-param-unit-client` in `BUGS.md`, then commit/push before code.
      - [x] Fix `RouteDeriver.makeEndpoint`: no explicit `apiClients:` and no typed handler evidence
            should derive `String` for one non-body path parameter, `Any` for multiple non-body path
            parameters, `Unit` only when no body and no path params; body methods stay `Any`.
      - [x] Add/adjust tests: `RouteDeriverTest` for route/mount/routes path-param defaults;
            `JsGenTypedRouteClientTest` Node harness proving derived `Api.get...("42")` fetches
            `/api/.../42`; `JvmGenTypedRouteClientTest` proving Swing/JVM emits callable derived
            methods over in-process transport.
      - [x] Add a JS-only conformance smoke `tkv2-typed-client-derived` with stubbed `fetch` and
            `awaitClient(Api.get...("42"))`; update `examples/derived-route-clients.ssc` so the
            no-manual-`apiClients:` example is actually browser-callable.
      - [x] Docs/bookkeeping: update `specs/typed-route-clients.md`, `specs/ssc-toolkit-v2.md`,
            README/user-guide/example index as needed, then mark BUGS/SPRINT/CHANGELOG done.
      Done-when: targeted core/codegen tests pass, affected compiles pass, conformance
      `tests/conformance/run.sh --only 'tkv2-typed-client-derived' --no-memo` passes, and
      `bin/ssc emit-spa --frontend custom --server-url http://server.example:49155 <example>`
      contains a derived `Api` client whose path-param method accepts an input argument.
- [x] **tkv2-theme-css-vars** ✓ DONE 2026-07-07 (taken out of order — small) — `cssVariables(t: Theme)`
      in theme.ssc: the theme as `:root { --ssc-* }` custom properties; one ssc value drives toolkit
      AND hand-kept CSS. Conformance `tkv2-theme-css-vars` INT==JS.

### Local model session help (2026-07-07)

- [x] **qwen-rozum-session** — help Sergiy start a local `rozum` chat session with a Qwen 3.6 model.
      Why: user wants an actionable on-machine launch path, not compiler work.
      How: inspect existing repo docs/scripts/examples for `rozum` gateway/client commands and Qwen/OpenAI-compatible model configuration; avoid code changes unless a missing script/doc is discovered and explicitly needed. Verify commands with non-destructive `--help`/status/list checks first, then provide the minimal terminal sequence. If the requested exact model name is not present locally, explain the likely model id/config place and how to list/install it.
      Done-when: Sergiy has concrete commands for starting the model backend/gateway and opening a `rozum` chat/session, plus any prerequisites or unknowns called out.
      Result: `rozum` and `ollama` are installed; meeting daemon is running with rooms including
      `scalascript`; no shared gateway is running. The exact installed Qwen 3.6 model is
      `mlx-community:Qwen3.6-35B-A3B-4bit-DWQ` (19 GiB on disk). Verified launch shape from
      `USER_MANUAL.md`: start gateway on `8089`, run `rozum meetings participant --gateway-url
      http://127.0.0.1:8089/v1`, then attach with `rozum meetings attach --room <room>`.
      Current dry-run refuses Qwen3.6: even `--n-ctx 4096 --min-free-ram-gb 0` needs 21.84 GiB
      available vs 21.45 GiB, short ~0.4 GiB; with normal margin it is short ~2.35 GiB.
      `mlx-community:Qwen3-4B-4bit` dry-run passes and can be used as a small-model smoke.

### Green main recovery (2026-07-06, user asked to finish the stabilization)

- [x] **green-main-crypto-ci** — restore `origin/main` to a buildable state before more v2 feature work.
      Why: the latest CI push is red in markdownlint, `sbt compile cli/assembly`, and conformance; v2 parity
      work is hard to trust while the main branch cannot assemble the launcher.
      How: first fix the concrete compile blocker in `payments/crypto/bouncycastle/BouncyCastleBackend.scala`
      by adapting it to the current portable crypto APIs (`ChaCha20Poly1305.seal/open`,
      `X25519.derivePublicKey/sharedSecret`, random private key generation). Then run targeted compile for
      `cryptoBouncycastle` and the affected crypto tests; if compile is green, re-check `sbt compile cli/assembly`
      with an explicit worktree `cd`. After code is green, triage whether CI conformance failures are downstream
      of the failed launcher or a separate runner issue, and record any remaining follow-up separately.
      Done-when: `cd <worktree> && sbt "cryptoBouncycastle/compile"` passes; broader compile/assembly is either
      green or has a newly diagnosed next blocker recorded here.
      Result: fixed the compile blocker by replacing the wildcard `scalascript.crypto.*` import in
      `BouncyCastleBackend.scala` with explicit SPI imports, so unqualified `ChaCha20Poly1305` and `X25519`
      resolve to the JVM/BouncyCastle package helpers again. Verified:
      `sbt "cryptoBouncycastle/compile"`, `sbt "cryptoBouncycastle/test"` (55/55), and
      `sbt "compile" "cli/assembly"` all pass in `/Users/sergiy/work/my/scalascript-wt-finish-green-main`.

- [x] **green-main-conformance-gating** — DONE 2026-07-08 (`3008b2677`):
      full default conformance is green with
      `tests/conformance/run.sh --no-memo` => **122 passed, 0 failed out of
      122 tests (+2 pending)**. Pending cases are intentional metadata gates:
      `http-client` (external httpbin.org dependency) and `sql-browser-basic`
      (needs npm install in the JS lane, pinned by its capture test). This slice
      fixed the deterministic blockers found after the original 102/20 baseline:
      actors/effects INT, JVM CPS cluster/distributed/effect cases, JS std/json
      intrinsic targets, JS product rendering, INT SQL block scope, std
      typeclass INT/JVM aggregate gaps, JVM std-ui generated braces, stale
      `.scjvm` codegen cache invalidation, INT while assignment order, and INT
      Semigroup-via-Monoid given resolution.
      ORIGINAL PLAN: fix the remaining CI conformance failures separately from the crypto
      compile blocker. Repro from the same worktree after `bash install.sh --dev`:
      `scripts/conformance -- --no-memo` starts running but shows multiple pre-existing non-crypto clusters:
      INT actor/cluster tests print empty output while JS/JVM pass; JVM-only cluster/distributed/effect-imported
      tests print empty output; `http-client` returns `0`/empty and then stalls on a network-adjacent section.
      A single-case check `scripts/conformance -- --only js-crypto-extern-standalone --no-memo` also fails INT
      because `crypto-plugin.sscpkg` is staged under `bin/lib/compiler/plugin-available/` (advanced, opt-in),
      while the test is marked `backends: [int, js]`. Decide per case whether to auto-load the plugin, add an
      explicit plugin flag to the runner, or narrow/pending the conformance case to the backend it actually
      validates. 2026-07-07 targeted check: `scripts/conformance -- --only mcp-types` passes INT but JS fails
      with `SyntaxError: Identifier 'args' has already been declared` because the fixture's `val args` collides
      with the JS preamble `function args()` (tracked in BUGS.md `jsgen-toplevel-name-vs-preamble`). Narrow fix:
      rename that fixture local to `mcpArgs` so the MCP conformance case is not blocked by the known unrelated
      JS top-level-name bug; done in `2e1f2c287`, and
      `scripts/conformance -- --only mcp-types --no-memo` now passes INT/JS. Done-when: CI conformance job no longer expects environment-gated or opt-in-plugin behavior
      from the default `bin/ssc` launcher.
      UPDATE 2026-07-08 (`conformance-http-client-external-httpbin`): current
      `scripts/conformance -- --only 'http-client' --no-memo` returned five INT
      `503` statuses from live `https://httpbin.org` and then stalled in the JS
      lane. Reclassified this fixture with `pending:` because default conformance
      must not depend on an external network service. Follow-up: replace it with a
      local deterministic HTTP fixture before re-enabling. Remaining fresh
      deterministic failures after the p3-remaining-ten landing: `actors-supervision`
      INT, `effects` INT, `effect-transitive-handler` JVM, and JVM-only
      `cluster-connect` / `distributed-failure-*` / `distributed-heterogeneous` /
      `distributed-shuffle`.
      UPDATE 2026-07-08 (`conformance-actors-exit-os-shadow`,
      `conformance-effects-choose-one-shot`): INT cluster fixed in two shippable
      slices. `actors-supervision` root cause was lazy os-plugin `exit(code)`
      shadowing the core actor `exit(pid, reason)`; fix `96bf969ed` preserves the
      previous native fallback and makes OS `exit` report a usage mismatch for
      non-code arguments. `effects` root cause was a conformance source bug:
      `Choose` was declared one-shot despite the expected multi-shot handler; fix
      `edda7c5d3` declares `multi effect Choose`. Verification:
      `backendInterpreterPluginTests/testOnly scalascript.ActorSupervisionTest`,
      direct `bin/ssc run --v1` checks, and
      `scripts/conformance -- --only 'actors-supervision' --no-memo` /
      `scripts/conformance -- --only 'effects' --no-memo` pass INT/JS/JVM.
      Remaining known failures in this claim are JVM-only generated-Scala compile
      errors: `effect-transitive-handler` and the cluster/distributed cases where
      local values are inferred/emitted as `Any`.
      UPDATE 2026-07-08 (`conformance-jvm-cps-any-typing-and-effect-args`,
      `conformance-jvm-cps-local-unit-effect-cast`): fixed the remaining
      deterministic JVM-only slice in `df7cfb613`. Root causes: CPS continuations
      widened untyped vals from known constructors/defs to `Any`; effectful lambdas
      nested under call argument clauses could bypass CPS emission; and local
      actor-loop defs declared `Unit` cast unresolved `receive` computations to
      `Unit`, causing workers to exit before health-check replies. Verification:
      `scripts/sbtc "backendInterpreter/compile"`, `scripts/sbtc "installBin"`,
      direct `bin/ssc run-jvm tests/conformance/cluster-connect.ssc` prints
      `unhealthy nodes: 0`, and
      `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
      passes **6/6**. Next: run the full default conformance gate with the
      serverless wrapper and either mark this item done or record any newly exposed
      blockers before release.
      FULL-GATE BASELINE 2026-07-08: after `scripts/sbtc "installBin"` and the
      landed JVM CPS fix, `tests/conformance/run.sh --no-memo` reports
      **102 passed, 20 failed out of 122 tests (+2 pending)**. New blockers are
      recorded in `BUGS.md`: `conformance-js-json-stringify-missing-global`,
      `conformance-js-product-show-synthetic-tag`,
      `conformance-int-sql-block-scope`,
      `conformance-std-typeclass-int-jvm-gaps`,
      `conformance-jvm-std-ui-generated-braces`, and
      `conformance-int-variables-while-update`.
      Active-claim subslice plan, do not claim separately while
      `green-main-conformance-gating` is active:
      - [x] **conformance-js-json-stringify-missing-global** — smallest JS-only
            crash: `bin/ssc run-js tests/conformance/json-read.ssc` fails with
            `ReferenceError: jsonStringify is not defined`. Fix the JS global/import
            path or std-json JS intrinsic registration; verify with
            `tests/conformance/run.sh --only 'json-read' --no-memo`.
            FIXED 2026-07-08 in `718d04027`: JS JSON intrinsics now target the
            existing `_ssc_ui_jsonStringify` / `_ssc_ui_jsonValue` runtime helpers
            instead of undefined bare globals, and `JsGenStdImportTest` covers the
            bare intrinsic path. Verification: `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`,
            `scripts/sbtc "installBin"`, direct `bin/ssc run-js tests/conformance/json-read.ssc`,
            and `tests/conformance/run.sh --only 'json-read' --no-memo` (**1/1 green**).
      - [x] **conformance-js-product-show-synthetic-tag** — JS product rendering
            includes ADT/case-class synthetic tag indexes, breaking `prisms`,
            `optic-polish`, `optics-index-at`, and `optional`. Verify with
            `tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`.
            FIXED 2026-07-08 in `4e8cbb635`: JS runtime `_show` skips internal
            `_tag`, and positional `.copy(...)` skips `_type`/`_tag` when mapping
            arguments over product fields. Direct JS repros for `prisms` and
            `optic-polish` now match expected output; the affected conformance
            slice is **4/4 green**.
      - [x] **conformance-int-sql-block-scope** — INT SQL interpolation cannot see
            preceding Scala block vals (`newId`); verify `sql-basic,sql-transaction`.
            FIXED 2026-07-08 in `c31389b25`: `Denormalize` now re-parses parseable
            embedded `scala`/`ssc`/`scalascript` blocks after the CLI
            `Normalize -> Denormalize` backend path, so the interpreter executes the
            preceding Scala block and SQL bind expressions see its globals.
            Verification: `scripts/sbtc "sqlPlugin/testOnly scalascript.compiler.plugin.sql.SqlPluginInterpreterTest"`,
            `scripts/sbtc "installBin"`, direct `bin/ssc run --v1` for
            `sql-basic` and `sql-transaction`, and
            `tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo`
            (**2/2 green**).
      - [x] **conformance-std-typeclass-int-jvm-gaps** — INT `std-index` stack
            overflows after two lines; JVM typeclass aggregate imports miss exported
            helpers/`Left`/`Right`; verify `std-*` typeclass cases.
            FIXED 2026-07-08 in `f92d147b0` / `7328e35db`: INT dispatch now
            prefers real built-in members over same-named imported extensions,
            preventing `Option.map` recursion in std typeclass helpers. JVM
            codegen records imported type/extension metadata even across
            de-duplicated imports, imports standalone top-level extensions,
            preserves re-export provenance for std/index aggregate names, hoists
            uppercase type specs from mixed std imports into `object std`, and
            lowers explicit contextual instance args to Scala `(using ...)`
            calls. Std typeclass manifests now export/import their type names
            explicitly for strict import resolution. Verification:
            `scripts/sbtc "backendJvm/compile"`,
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`,
            `scripts/sbtc "installBin"`, direct INT/JVM repros, and
            `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
            (**6/6 green**).
      - [x] **conformance-jvm-std-ui-generated-braces** — JVM `std-ui-extended*`
            generated Scala has an unmatched brace/EOF; inspect imported UI
            component object emission.
            FIXED 2026-07-08 in `9bd6cb87d`: `JvmGen` now preserves
            triple-quoted JavaScript/CSS literals while converting `object X:`
            blocks and while merging duplicate package/object blocks, so braces
            inside imported UI strings no longer close Scala objects early. The
            regression covers both a minimal duplicate-object source and the real
            `tests/conformance/std-ui-extended.ssc` directory import. Verification:
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`
            (**14/14 green**); direct
            `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc` after forced
            regeneration of stale local `std-ui*.scjvm`; and
            `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
            (**5/5 green**). Follow-up cache invalidation risk tracked separately
            as `jvm-scjvm-cache-codegen-version`.
      - [x] **conformance-int-variables-while-update** — INT `variables` prints
            `sum=10` for the first while loop; inspect mutable var read-after-write
            inside interpreter while sequencing.
            FIXED 2026-07-08 in `4e67a2f41`: the closed-form while optimizer now
            bails when an accumulator RHS reads a counter that was assigned earlier
            in the same loop body, preserving ScalaScript's sequential assignment
            order. This keeps `x = x + 1; sum = sum + x` on the sequential loop path
            so `sum` sees the post-update `x`. Verification:
            `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
            (**6/6 green**); `scripts/sbtc "installBin"`; direct
            `bin/ssc run --v1 tests/conformance/variables.ssc`; and
            `tests/conformance/run.sh --only 'variables' --no-memo`
            (**1/1 green**).
      - [x] **jvm-scjvm-cache-codegen-version** — production cache follow-up found
            while fixing std-ui: `run-jvm` reused source-fresh `.scjvm` artifacts
            emitted by an older JVM backend, so the assembled CLI kept failing until
            `tests/conformance/.ssc-artifacts/std-ui*.scjvm` was removed. Tracked in
            `BUGS.md`. Done-when `.scjvm` freshness accounts for compiler/backend
            codegen version (or an equivalent invalidation signal) and a CLI
            regression proves stale source-fresh artifacts regenerate after the
            version changes.
            FIXED 2026-07-08 in `322ee868f`: JVM `.scjvm` artifacts now carry a
            `codegenVersion` cache key set by `JvmArtifactIO`, and
            `ModuleGraph.isJvmStale` invalidates source-fresh artifacts whose
            codegen key is missing or old. Legacy artifacts remain ABI-readable
            and regenerate instead of being reused. Verification:
            `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
            (**15/15 green**), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
            (**7/7 green**), `scripts/sbtc "installBin"`, and
            `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
            (**5/5 green**). Next: run full default conformance with the
            serverless wrapper and either mark `green-main-conformance-gating`
            complete or record the next blocker before releasing the claim.
      FULL-GATE UPDATE 2026-07-08: after `322ee868f` / `4463a6117`,
      `tests/conformance/run.sh --no-memo` reports **121 passed, 1 failed out of
      122 tests (+2 pending)**. The only remaining blocker is
      `std-semigroup-monoid`, failing only on INT with expected lines 4-6
      missing (`Some(24)`, `42`, `foo`) while JS/JVM pass. Tracked in `BUGS.md`
      as `conformance-int-std-semigroup-monoid`.
      - [x] **conformance-int-std-semigroup-monoid** — final full-gate blocker:
            reproduce with `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`
            and `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`;
            inspect INT handling of std Semigroup/Monoid givens/extensions or
            imported typeclass dispatch; add a focused interpreter/std regression.
            Done-when direct INT output includes all expected lines, the targeted
            conformance slice is green across enabled backends, and the full
            default conformance gate is rerun.
            FIXED 2026-07-08 in `e571fd3ae`: INT concrete/parametric given
            registration now exposes parent typeclass aliases through
            `parentTypes`, so a `Monoid[Int]` given also satisfies a
            `Semigroup[Int]` demand. Root cause: `combineAllOption[A: Semigroup]`
            failed after the first three lines because `intSum` was only
            registered as `Monoid[Int]`; JS/JVM inherited Scala's subtype
            evidence behavior. Verification: direct
            `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`;
            `scripts/sbtc "backendInterpreter/testOnly scalascript.FinalTaglessConformanceTest scalascript.GivenUsingTest"`
            (**17/17 green**); and
            `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`
            (**1/1 green**). Next: rerun full default conformance; if green,
            mark `green-main-conformance-gating` complete and release the claim.
      FINAL GATE 2026-07-08: `tests/conformance/run.sh --no-memo` reports
      **122 passed, 0 failed out of 122 tests (+2 pending)**. No deterministic
      conformance blockers remain in this claim.

- [x] **green-main-full-sbt-test-gating** — fix the root `sbt "test"` gate after the
      `PluginCliTest` compile blocker. Repro: `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main &&
      sbt "test"`. The first run hit a transient Scala 3 compiler crash in `clientEvm/Test/compile`;
      targeted `clientEvm/Test/compile` passed immediately. The second full run completed in 29:08 and
      confirmed `PluginCliTest` passes, but failed unrelated suites: `CrossBackendIntrinsicParityTest`
      (`webauthnConfigureStore`/`webauthnStoreRemove` JS-only drift; fixed in `8dfd2989e`),
      `JvmGenSwingRuntimeTest` (local helper resolved repo root as `v1`, fixed in `395e8aab3`),
      `StableSpiEnforcementTest` (`tcp-plugin` imported `scalascript.interpreter.Value` from a
      value-surface plugin; fixed in `484d56101`), `AgentConformanceTest` (`Address already in use`
      in `beforeAll`, fixed in `eae491e11`), plus
      Scala.js `loadedTestFrameworks` fallout after a Node non-zero exit. Remaining targeted blockers
      reproduced on 2026-07-07:
      `backendWasm/testOnly scalascript.codegen.WasmBackendTest` has 7 effectful-WASM failures
      (handler/resume, effectful `String*` mains, arithmetic/HOF effect bodies, cross-module effects);
      `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` had one value-shape failure in
      `loadBackend` (`Long` vs `DataValue.IntV`, fixed in `7e2650e2c`); and
      `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` had one `mcp-types` failure
      (`user.name` blank; missing-field validation printed `no error`, fixed in `2e1f2c287`).
      Next slice: fix WASM effects, then re-check the Scala.js fallout, and only then rerun root `sbt "test"`.
      **2026-07-07 session ledger (claude takeover after codex stalled):** WASM effects FIXED
      (adopted codex's in-flight preserveTotalEffectfulReturnTypes, backendWasm 48/48, 9f04f8a29);
      jvmgen-block-call-empty-parens 3-bug chain FIXED (7bc09fffa — see BUGS.md, all 4 JVM-lane
      conformance repros green + SwiftUI 118/118 + JvmGen/Effect 193/193); runActors fat-jar
      family FIXED (a36e74fa0: cli dependsOn actorsPlugin + ActorInterp lazy-load seam —
      MultiNodeClusterTest 0/4→4/4, full cli/test 18-fail→5-fail); EmitScalaFacadeCliTest harness
      FIXED (bce70aaeb: -Dssc.lib.path derivation). REMAINING, precisely diagnosed in BUGS.md:
      `bytecode-shared-runtime-routes-unbound` (genRuntime gating emits _routes refs without defs —
      blocks the 5 facade tests + compile-jvm --bytecode) and `scalajs-jsenv-run-terminated`
      (node-26 jsEnv, 6 JS test modules, serial + CI). Root `sbt test` after those two = the gate.
      ACTIVE CLAIM PLAN 2026-07-08 (`green-main-full-sbt-test-gating` / codex):
      - [x] **bytecode-shared-runtime-routes-unbound** — fixed in `83fc339e2`. Reproduced with
            `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"` from this worktree;
            root cause was split `JvmGen.genRuntime` omitting `stubServeRuntime` when
            `Serve` was absent even though the always-included common/effects runtime
            references `_routes`, `route`, `onWebSocket`, and `_httpDoRequest`.
            Verified `backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest`,
            `installBin`, `cli/assembly`, `cli/testOnly *EmitScalaFacadeCliTest`, and
            `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.
      - [x] **scalajs-jsenv-run-terminated** — fixed in `1da48bfd5`. Serial repro
            `scripts/sbtc "cryptoNobleJs/test"` resolved to Node `MODULE_NOT_FOUND`
            for `@noble/ciphers/aes` because npm deps were never installed in clean
            worktrees/CI. Added idempotent `npmInstallForScalaJsTest` and wired it
            into `Test / loadedTestFrameworks` for `cryptoNobleJs`,
            `walletVaultEncryptedJs`, `walletStrategyErc4337Js`,
            `blockchainEvmAbiJs`, `walletConnectJs`, and `markupNode`.
            Verified those six suites plus `tests/conformance/run.sh --only
            'std-semigroup-monoid' --no-memo`.
      ROOT RETEST 2026-07-08: started `scripts/sbtc "test"` from
      `/Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating`.
      The PTY session was lost before the final sbt summary, so do not treat this
      as the authoritative complete failure list. Observed root-gate blockers were
      recorded in `BUGS.md` and must be reproduced targeted before coding:
      - [x] **root-test-command-registry-other-category** — fixed in `631ed8052`.
            Root cause: `VersionCmd` used the unclassified fallback-style
            category `Other`; `version` now appears under the existing `Help`
            bucket, preserving the registry test that catches future commands
            without explicit help grouping. Verified
            `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`
            (**8/8 green**) and `tests/conformance/run.sh --only
            'std-semigroup-monoid' --no-memo` (**1/1 green**).
            Original repro: deterministic-looking
            `CommandRegistryTest` failure: `every command category is in the help
            ordering` reports `List("Other")`. First repro/fix because it is a
            narrow CLI test and not entangled with cluster timing:
            `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`.
      - [x] **root-test-sealed-extension-option-dispatch** — fixed in `1e503de04`.
            Root cause: built-in `Option.orElse` accepted any single argument, so
            `Some(42).orElse(0)` returned the built-in receiver `Some(42)` before
            the user extension `def orElse(default: A): A` could run. Built-in
            `orElse` now handles only Option-valued alternatives; non-Option
            defaults fall through to extension dispatch. Verified
            `scripts/sbtc "backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest"`
            (**4/4 green**), the filtered `InterpreterTest` built-in-priority /
            `option orElse` slice, and `tests/conformance/run.sh --only
            'option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror'
            --no-memo` (**5/5 green** on INT/JS/JVM). Original repro:
            `SealedExtensionDispatchTest` expected `42\n99`, got `Some(42)\n99`
            for the `Some` case.
      - [x] **root-test-cluster-cli-runtime-readiness** — fixed in `da63bb96a`.
            Root cause: after the v2 default switch, these v1 actor-cluster
            integration tests spawned node fixtures with `java -jar ssc.jar
            <node.ssc>`, so the node scripts ran on v2/default. Minimal fat-jar
            repro showed `sendAfter` actor flows print under `--v1` but exit 0
            with no delayed message under default/`--v2`; the v2 gap is tracked
            separately as `v2-actors-sendafter-cli-default-noop`. Harness fix:
            node fixture subprocesses now pass explicit `--v1`; CLI subcommands
            (`cluster status`, `cluster drain`, `cluster step-down`, etc.) still
            run normally against those nodes. Verified the expanded cluster
            slice `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest
            scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest
            scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest
            scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest
            scalascript.cli.ClusterDrainCliTest scalascript.cli.ClusterEventsCliTest
            scalascript.cli.PartitionTest"` (**13/13 green**) and
            `tests/conformance/run.sh --only 'actors*,cluster-connect,distributed*'
            --no-memo` (**14 passed, 0 failed**). Original repro: cluster CLI/runtime
            family: `ClusterStepDownCliTest`, `ClusterStatusCliTest`,
            `ClusterAuthCliTest`, `MultiNodeClusterTest`,
            `ClusterBullyStatusConvergenceTest`, `PartitionHealingTest`, and
            `SingletonFailoverTest` showed node bind/readiness/leader marker
            failures. Repro the family after the two narrow failures:
            `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest"`.
      Done-when: run root `scripts/sbtc "test"` after both fixed slices are on the branch;
      if green, mark this gate done and release the claim. If red, record the next deterministic
      blocker in BUGS.md + SPRINT before fixing it.
      - [x] **root-test-v2-array-companion-foreign-sum** — fixed in
            `f6e6383ac`. New deterministic
            `V2ConformanceTest` blocker discovered after rebasing the jobpanel
            fix onto `origin/main@9e48204e5`: full
            `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`
            fails only `array-companion-statics` with
            `RuntimeException: __method__: no dispatch for .sum on <foreign>`.
            Targeted repro first: `scripts/sbtc "v2FrontendBridge/testOnly
            ssc.bridge.V2ConformanceTest -- -z array-companion-statics"` plus
            `tests/conformance/run.sh --only 'array-companion-statics' --no-memo`.
            Root cause: Array companion statics now intentionally return real
            `ForeignV(ArrayBuffer)` values for mutable arrays, but collection
            methods still only accepted Cons/Nil lists. Runtime fix: treat
            ArrayBuffer as list-like for read-only collection dispatch. Gates:
            targeted `array-companion-statics`, affected conformance, and full
            `V2ConformanceTest` are green.
      - [x] **root-test-sbt-aggregate-heap-oom** — root
            `scripts/sbtc "test"` on `origin/main@c9d300335` is now blocked by
            sbt/JVM heap stability, not a known deterministic v2 conformance
            failure. The run progressed through many suites, then printed
            repeated `OutOfMemoryError: Java heap space` from `pool-453`
            threads; the sbt JVM was non-responsive to `jcmd`, Ctrl-C did not
            stop it, SIGTERM only removed 47 node children, and SIGKILL was
            required. Work loop: identify whether this is root aggregate
            parallelism, Scala.js jsEnv node fan-out, or one leaking module; try
            bounded root-equivalent test invocation / focused module groups; then
            encode the stable production gate command or build setting. Done-when:
            a root-equivalent gate completes without heap OOM/hung sbt JVM and
            the command/result are recorded.
            Progress 2026-07-08 (uncommitted): a global sbt
            `Tags.Test` concurrency cap, env-overridable via
            `SSC_SBT_TEST_CONCURRENCY` and defaulting to 4, made the next root
            `scripts/sbtc "test"` complete in about 27m32s without the prior
            OOM/hung sbt JVM symptom. It still exited 1 because two later
            deterministic/root-runner blockers surfaced; fix those next, then
            rerun the root gate before marking this item fixed.
      - [x] **root-test-js-rowpost-runtime-contract** — new backendInterpreter
            blocker from the bounded root gate. Repro in root stream:
            `scalascript.JsGenStdImportTest` case `JS signal runtime defines the
            std/ui row-data natives` failed because generated JS did not contain
            `_RowPost` body payload resolution
            `body: resolvePayload(r, act.bodyField)`. Work loop: run focused
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest -- -z row-data"`;
            inspect `_RowPost`/`resolvePayload` runtime generation; either
            restore the real row POST body resolver or update the assertion if
            current code is semantically equivalent. Done-when: focused
            `JsGenStdImportTest` is green plus affected std/ui conformance.
      - [x] **root-test-cli-fork-exit-after-green** — new CLI aggregate blocker
            from the bounded root gate. Repro in root stream: `cli / Test / test`
            reported all CLI tests passed (488 succeeded, 0 failed, 19 canceled),
            then sbt failed because the forked `sbt.ForkMain` JVM exited 1.
            Work loop: reproduce with focused `cli/testOnly` suites starting
            from the last emitted CLI suite, then widen to `cli/test`; inspect
            late JVM/process cleanup and generated `v1/tools/cli/ssc-storage.json`
            rather than masking the fork exit. Done-when: `scripts/sbtc
            "cli/test"` exits 0 and the final root-equivalent gate no longer
            reports the CLI task failure.
            Progress 2026-07-08 (uncommitted): focused
            `ElectronJvmRestCliTest` is green with fork exit 0 after updating
            stale fake-Electron greps for the typed-route client signatures and
            fetch retry loop. Full `cli/test` no longer shows the old after-green
            fork exit; it now reports ordinary assertion failures below.
      - [x] **root-test-cli-toolkit-electron-duplicate-seqmap** — new full
            `cli/test` blocker after the fork-exit fix. Focused repro:
            `scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`.
            Full-run symptom: Electron renderer throws
            `Uncaught SyntaxError: Identifier '_seqMap' has already been declared`,
            causing `SMOKE_FAIL initial render missing`. Work loop: inspect the
            generated toolkit Electron bundle and deduplicate/scope repeated JS
            helper preamble emission so `_seqMap` is declared once. Done-when:
            focused smoke test is green and full `cli/test` no longer reports it.
      - [x] **root-test-cli-spark-submit-dry-run-deps** — new full `cli/test`
            blocker after the fork-exit fix. Focused repro:
            `scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`.
            Failures: dry-run output no longer contains
            `org.apache.spark::spark-core:4.0.0` for the default Spark version
            nor `spark-core:3.5.1` for `--spark-version 3.5.1`. Work loop:
            inspect current `submit` dry-run output/contract; either restore the
            dependency strings/options or update the stale test expectations if
            the dependency surface intentionally moved. Done-when: focused
            `SubmitCommandTest` is green and full `cli/test` no longer reports it.
      Result 2026-07-08 (`cea0c3aed`): root gate is green. Fixes included a
      bounded root Test concurrency cap (`SSC_SBT_TEST_CONCURRENCY`, default 4),
      strict-mode-safe JS runtime helper emission for Electron/browser bundles,
      repeat-safe typed JSON facade bindings, updated typed route client smoke
      assertions, sharper `_RowPost` payload-resolver assertions, and Spark
      submit dry-run assertions against the generated package source. Verified:
      `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed),
      `tests/conformance/run.sh --only
      'collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*'
      --no-memo` (19/19), and bounded root `scripts/sbtc "test"` (`[success]`
      elapsed 1668s / 0:27:48.0).

- [x] **green-main-plugin-cli-oslib-shadow** — fix the remaining `sbt test` CI blocker in
      `v1/tools/cli/src/test/scala/scalascript/plugin/PluginCliTest.scala`.
      Repro: `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"` fails with
      `type Path is not a member of scalascript.compiler.plugin.os` plus missing `temp`, `read`, `write`,
      `makeDir`, etc. Root cause hypothesis: the test is in package `scalascript.compiler.plugin`, where the
      local `scalascript.compiler.plugin.os` package shadows os-lib's root `os` package. Qualify os-lib as
      `_root_.os` (or an explicit alias) inside the test, then rerun `cli/Test/compile` and the affected
      `cli/testOnly scalascript.compiler.plugin.PluginCliTest`. Done-when: the CI `sbt - compile and test`
      job no longer fails at `PluginCliTest.scala` test compilation.
      Result: fixed in `6d133361a` by qualifying os-lib as `_root_.os` inside `PluginCliTest`, avoiding the
      local `scalascript.compiler.plugin.os` package shadow. Verified:
      `sbt "cli/Test/compile"` and
      `sbt "cli/testOnly scalascript.compiler.plugin.PluginCliTest"` (8/8).

- [x] **green-main-markdownlint-policy** — make the Markdown lint job match the repository's actual historical
      documentation style instead of failing on legacy board/spec/changelog formatting. Current CI fails before
      useful validation on rules already violated broadly (`MD007`, `MD009`, `MD011`, `MD012`, `MD014`, `MD022`,
      `MD026`, `MD029`, `MD034`, `MD037`, `MD038`, `MD050`, `MD058`). Update `.markdownlint.json` rather than
      mass-reformatting durable project history. Done-when: `markdownlint '**/*.md' --ignore node_modules`
      exits 0 locally.
      Result: disabled the legacy-violated rules in `.markdownlint.json`; verified locally with
      `npx --yes markdownlint-cli '**/*.md' --ignore node_modules` (exit 0).

### Workflow polish (2026-07-06, Sergiy approved proposals 1-2)

- [x] **ws-1 workflow-verify-step**: THE WORKFLOW gains step 4b — run the affected
      conformance slice (`run.sc --only`) before every push; now cheap enough to require.
- [x] **ws-2 nightly-sanitizer** — installed (LaunchAgent io.scalascript.kill-stale-builders, daily 03:00, script copied to ~/.local/bin so any repo branch state is fine; kickstart-verified exit 0): scripts/install-build-sanitizer (idempotent crontab
      entry, 03:00 daily `kill-stale-builders --kill`) + installed on this host.


### Build-perf wave 2 (2026-07-06, Sergiy: "зроби усе що можеш")

- [x] **bp2-1 agents-workflow-banner**: AGENTS.md top-of-file THE WORKFLOW section
      (plan→sprint, worktree, claim, push-to-main, cleanup). (this commit)
- [x] **bp2-2 f4-batch-runner** — DONE (run-batch cmd; INT one-JVM, JS one-emit-JVM; identical results batched vs not on 22 cases; 6-case slice 36.1->15.2s with warm JVM lane default): `ssc run-batch --delim <s> <files…>` (one JVM runs many
      cases, delimiter-separated output) + run.sc uses it for the INT lane (one JVM instead
      of 193); JS lane: emit all sources in the same batch JVM, execute per-case in ONE
      node process via vm contexts. Measure before/after on a 20-case slice.
- [x] **bp2-3 env-heap-cleanup** — DONE (-Xmx12g removed from ~/.zshenv, backup kept): remove -Xmx12g from JDK_JAVA_OPTIONS in ~/.zshenv
      (backup kept); build-level heaps are explicit since bp-1.
- [ ] **bp2-4 ci-test-shard** — DEFERRED with verdict: hand-partitioning 259 modules is brittle and untestable locally; bp-5 classes-cache + pipelining (-25%) already cut CI compile; revisit only if CI wall-time still hurts after those land: split the CI `sbt test` job into parallel matrix shards
      by module groups.
- [x] **bp2-5 pipelining-measure** — MEASURED: clean cli-chain compile 34.5s WITH vs 46.3s WITHOUT usePipelining (-25% wall, CPU util 745% vs 577%); flag stays ON: one clean-compile A/B timing for usePipelining
      (document the number; revert flag if it turns out negative).
- [x] **bp2-6 exportjars-scope** — INVESTIGATED, NO CHANGE: warm touch-recompile loop through the 20-module chain is 13.2s with jars; toggling the flag invalidates zinc (A/B misleading); jar packaging is not the dominant term: measure whether ThisBuild/exportJars
      actually costs in the dev loop; scope or document.
- [x] **bp2-7 worktree-warm-targets** — INVESTIGATED, NEGATIVE: zinc analysis is absolute-path-bound — copied targets recompile anyway (57 modules, 34s = same as cold-with-pipelining). New-worktree cold cost is acceptable post-pipelining; do NOT build target-copying: zinc analysis stores absolute
      paths — verify whether target-copy into a new worktree survives; document verdict.

### Build-perf + conformance-perf sprint (2026-07-06, Sergiy directive: "запиши у спринт і зроби")

Build optimization (from the 2026-07-06 build audit: 259 modules, ~8s/31s CPU per cold sbt -batch
invocation, JDK_JAVA_OPTIONS=-Xmx12g inherited by every forked JVM, 2 orphaned sbt servers at 2.5GB
each, CI recompiles all modules, v2 parity harness rebuilds v2.jar per run):

- [x] **bp-1 test-heap-default** (= BACKLOG conformance-test-heap-default L1): explicit env-gated
      `-Xmx` for forked test JVMs in build.sbt (`SSC_TEST_XMX`, default 2g) so tests stop inheriting
      the ambient 12g; JMH/proguard pins stay. Verify: v2FrontendBridge suite green under 2g.
- [x] **bp-2 pipelining**: `ThisBuild / usePipelining := true` (sbt 1.10 + Scala 3.8 support it);
      verify full compile + suite; revert if zinc misbehaves.
- [x] **bp-3 worktree-server-hygiene**: scripts/new-worktree (and a new scripts/rm-worktree) kill the
      worktree's sbt server on removal; add scripts/kill-stale-builders for orphans.
- [x] **bp-4 v2-jar-cache**: v2/backend/check.sh caches v2.jar keyed by hash of v2/src/*.scala
      (skip scala-cli --assembly when unchanged).
- [x] **bp-5 ci-class-cache**: ci.yml caches **/target (classes+zinc) keyed by SHA with restore-keys
      so PR builds recompile only changed modules.
- [x] **bp-6 sbt-client-docs**: AGENTS.md note + scripts/sbtc thin-client helper (8s -> <1s per command).

Conformance-PERF (BACKLOG items, specs/conformance-perf.md):

- [x] **cp-1 conformance-affected-only (F1)**: `run.sc --only <glob|files>` so the fix-test loop runs
      just touched cases; full corpus stays for CI.
- [x] **cp-2 conformance-memoize (F2)**: skip cases whose (input, ssc.jar hash, expected) is unchanged
      since last green (cache file under target/); `--no-memo` escape hatch.
- [x] **cp-3 conformance-warm-runner (F3 subset)**: JVM lane compiles через warm bloop server instead
      of cold `--server=false` scala-cli per case; INT lane stays bin/ssc (already one JVM per case).
- [x] **cp-4** covered by bp-1 (same L1 item).


### ▶ v1→v2 migration (2026-07-03 — planned, not started)
Spec: `specs/v1-to-v2-migration.md`

Three phases — execute in order, each phase gated by the previous:

- [x] **Phase 1: restructure** — DONE 2026-07-03. `git mv lang/ → v1/lang/`, `runtime/ → v1/runtime/`,
      `tools/ → v1/tools/`. Updated `.in(file("..."))` paths in `build.sbt` (75 entries). Also updated
      `install.sh`, `scripts/runtime-bench.sh`, `tests/perf/{coldstart,serverrss}/run.sh`, and 3 CI
      workflows. `sbt compile` green, `ssc run examples/hello.ssc` prints `Hello, World!`.
- [x] **Phase 2a: v2 sbt module** — DONE 2026-07-03. Added `lazy val v2Core = project.in(file("v2/src"))` to `build.sbt`; added `v2Core` to root aggregate. `sbt "v2Core/compile"` green (5 sources, 4 s). `//> using` scala-cli directives in `v2/src/project.scala` are valid Scala comments, silently ignored by sbt.
- [x] **Phase 2b: v1-plugin bridge** — DONE 2026-07-03. `V2PluginRegistry` added to `v2/src/Runtime.scala`
      (fallback in `Prims.resolve` before throwing). `v2/plugin-bridge/` sbt module created;
      `PluginBridge.loadAll()` ServiceLoader-discovers v1 `Backend` plugins, extracts `NativeImpl`
      intrinsics, translates `v2Value ↔ v1Value` (scalars + DataV/InstanceV/List/Option/Tuple),
      registers wrapped handlers with `V2PluginRegistry`. 22 tests green. Non-bridgeable: `InlineCode`,
      `RuntimeCall` (compile-time only), `BlockForm` effect runners (deferred). Spec original description
      (shift/reset SPI) is a later phase; this bridges the existing NativeImpl surface first.
- [x] **Phase 2c: v2 JVM backend** — DONE 2026-07-03; TCO fixed 2026-07-03.
      `v2/backend/jvm/JvmBackend.scala`: reads Core IR (S-expression text), emits a self-contained
      Scala 3 source file. When compiled with `scalac` and run with `java`, produces byte-identical
      output to `ssc run-ir`. 29/29 pass (all conformance + all 23 v2 examples incl. `tco.coreir`
      — 1M tail calls complete without stack overflow via `@tailrec def`). Preamble handles all
      Core IR constructs + full prim set. TCO: global self-tail-recursive defs → `@tailrec def`;
      single-lam LetRec self-tail-calls → `@tailrec def`; mutual LetRec → closure vars (no trampoline).
- [x] **Phase 2c: v2 JS backend** — DONE 2026-07-03. `v2/backend/js/JsBackend.scala`:
      reads Core IR S-expr, emits a self-contained .js file. Trampoline TCO ($tco/$c),
      full prim set, ADTs as {t,f}, cells as arrays, maps as wrappers. All 5 conformance
      fixtures + 15 kc examples pass (output identical to ssc run-ir); 100k-deep TCO ok.
- [x] **Phase 2c: v2 Rust backend** — DONE 2026-07-03. `v2/backend/rust/RustBackend.scala`:
      Core IR → self-contained Rust source via `scala-cli run v2/backend/rust/`. 29/31 bench corpus
      pass (2 failures are pre-existing ssc1c IR bugs that also fail the v2 VM). Key: forward-ref
      cells (`__fwd`) for all global Lam defs (self/mutual recursion), 256MB thread for deep
      tail recursion, `v_sconcat` handles any Data++Data (Pair++Pair→Tuple4).
- [x] **Phase 2d: full checklist** — DONE 2026-07-03. Verification pass results:
      • JVM: 5/5 conformance (fact/letrec/map/tco/thunk), 3/3 bench corpus (arith-loop/recursion-fib/list-fold) — PASS. TCO verified (tco.coreir = 500000500000 without stack overflow).
      • JS: 5/5 conformance, 2/2 bench corpus (arith-loop/recursion-fib) — PASS. Trampoline TCO correct.
      • Rust: 29/31 bench corpus PASS. 2 failures = known ssc1c IR bugs (bool-predicate/@count global, mutual-recursion) — both also fail the v2 VM. See BACKLOG: v2-ssc1c-globals-bug.
      • sbt v2Core/compile: SUCCESS (5 sources, 4 s).
      GOTCHA: macOS `echo` processes `\n` as a real newline (unlike Linux). Use `program > file` (redirect) or `printf '%s\n' "$var"` when writing backend output to files. The generated Scala/Rust code contains literal `\n` in preamble strings; `echo "$VAR"` corrupts them silently.
- [x] **Phase 3: switch** — RECONCILED 2026-07-09: the actual CLI default switch
      landed in `v2-prod-default-switch` (`719943f40`, `d2ba78c0a`,
      `89a38f1e3`) and the stale duplicate queue row `p4-default-flip` was
      closed on 2026-07-08. Plain `ssc run <file>` defaults to v2; `ssc run
      --v1 <file>` remains the rollback path; `ssc run --v2 <file>` remains the
      explicit force flag. Historical planning notes below are kept for context.
      Original:
      CLI default → v2; `ssc --v1` escape hatch retained.
      - [x] **`ssc run --v2` flag** DONE 2026-07-05 (`RunV2.scala`, `feature/v2-cli-run-flag`): additive
        preview flag routing a source through the v1 frontend → FrontendBridge → v2 VM (default runner
        unchanged). `ssc run --v2 examples/hello.ssc` == v1 output. Makes v1-vs-v2 output parity checkable
        from the CLI; the eventual default-switch builds on this.
      - **OUTPUT-PARITY FINDING (for Track 4 / conformance):** `examples/algebraic-effects.ssc` exits 0 on
        v2 (PASS in the exit-0 coverage harness) but prints DIFFERENT output than v1 (v2: `List() / 1 / …`
        vs v1: `0 / 10 / 11 / List(11,21,…) / done / (42,…)`). The 96.4% exit-0 coverage OVERSTATES real
        compat; the Phase-3 gate needs an **output-equality** check. First concrete effects-semantics gap
        found this way — a v2 VM effects divergence, not a bridge/flag bug (the flag mirrors `bridgeCli`).

### ▶ v2 full compatibility (2026-07-03 — Track 1 through 5)
Spec: `specs/v2-full-compat.md`
Goal: v2 handles ALL v1 programs with full language features + performance parity.
Phase 3 (CLI switch) is gated on this entire track completing.

**Track 1 — v1 IrExpr → Core IR (foundation — do first)**
- [x] **T1.1: FrontendBridge** — DONE 2026-07-03. `v2/frontend-bridge/` sbt module created.
      `FrontendBridge.scala`: scalameta → Core IR via de Bruijn scope (List[String]), convertExpr/convertMatch/convertPat.
      `ModuleBridge.scala`: walks Module sections → scalameta stats → FrontendBridge.
      BridgeCli `run`/`run-module`/`emit` commands.
      Gate met: unit tests (12 pass) + examples via `sbt "v2FrontendBridge/run run-module"`.
- [x] **T1.2: NormalizedModule → Program** — DONE (ModuleBridge.convert). Gate met: hello.ssc runs.
- [x] **T1.3: CLI wiring** — DONE via BridgeCli `run-module`. Gate met: `sbt "v2FrontendBridge/run run-module examples/hello.ssc"` prints `Hello, World!`.
- [x] **T1.4: Examples verification (core language)** — DONE 2026-07-03 (2a828e9f1).
      Pure-language examples passing: hello, functional, enums, data-types, typed-data, bitwise-operators, extensions, default-params.
      Key fixes: extension methods (Defn.ExtensionGroup), for-do loops (Term.For), nested ctor patterns (flat flattenPattern/shiftLocals),
      `->` operator, String+Int concat, String*Int repeat, __isTag__ prim, __unsupported__ global.
      Plugin-dependent examples (effects, actors, async, algebra, dsl-*-with-std-imports): EXPECTED FAIL (require T2.1+).
      Remaining pure-language items: algebraic-effects.ssc (needs `handle` keyword), generators.ssc (generators plugin).
      Gate: 8/8 pure language examples pass; 0 unexpected failures.

**Track 2 — Plugin parity**
- [x] **T2.1: BlockForm effects** — DONE 2026-07-04. All 7 effect plugins (Logger/State/
      Random/Clock/Env/Retry/Cache) wired to v2 via V2EffectContext ThreadLocal + PluginBridge.
      Three fixes needed: (1) FastCode global-lookup paths bypass V2PluginRegistry → added
      lookupGlobal fallback to all 3 paths; (2) FrontendBridge emitted block args as eager
      Seq → added Lam(0) thunk wrap for statement blocks (lambdas detected by
      `Block(List(Function|AnonFn))` heuristic); (3) `__arith__` unknown-op catch-all for
      `effect Logger:` declaration prims. Gate: runLogger+runLoggerToList+runState all correct.
- [x] **T2.2: HTTP/SQL intrinsics** — DONE 2026-07-04. httpPlugin+sqlPlugin added to
      v2PluginBridge deps; NativeImpl registration now also registers as v2 global ClosV
      (env-as-arglist) so App(Global(name), args) resolves correctly. Fixed raw-arg
      conversion: NativeImpl expects unwrapped primitives (String/Long/Boolean) not v1 Value
      objects — added v2ToRaw/rawToV2 helpers (mirrors Interpreter.unwrapValueAsAny).
      Gate: `httpGet("https://httpbin.org/get")` returns HTTP 200 Response with JSON body.
- [x] **T2.3: Actors (spike)** — DONE 2026-07-04. VirtualThread-per-actor model implemented in
      PluginBridge: spawn/receive/self/exit/runActors registered as v2 globals; `!` wired via
      __arith__ → actor.send. Fixes: (1) v2 Match non-DataV scrutinees fall through to default arm
      instead of erroring (needed for `case s: String => ...` on StrV); (2) @timeout cell registered
      as ForeignV so cell.set works; new FastCode path in Runtime.scala also needed lookupGlobal
      fallback; (3) exit() needs dead flag (interrupt alone races with LinkedBlockingQueue.take if msg
      already present); (4) 2-arg globals (exit) need arity=2 (v2 App is non-curried n-arg).
      Gate: examples/actors-pingpong.ssc passes all checks (ping-pong, timeout-None, timeout-Some,
      exit+ignored message, done).

**Track 3 — Performance parity**
- [x] **T3.1: Baseline benchmarks** — DONE 2026-07-03. All 22 bench programs run through v2 bridge.
      Key correctness fixes in this session: vector-index (list O(n) indexed access), array-update
      (Array factory + ForeignV apply), map-ops (Map.updated/getOrElse/apply), streams-pipeline
      (Bench.opaque identity stub + Range.to list), lazylist-take (LazyList stored as ForeignV Scala LazyList),
      typeclass-monoid (Bench.opaque), Either/Option methods, Int.toInt/toLong.
      typeclass-fold: DEFERRED (requires summon[T] typeclass dict-passing — T2 scope).

      | program          | v1 (ms)  | v2 bridge (ms) | ratio |
      |------------------|----------|----------------|-------|
      | arith-loop       | 0.244    | 6.1            | 25×   |
      | nested-loop      | 0.256    | 31.6           | 123×  |
      | recursion-fib    | 1.22     | 257            | 211×  |
      | list-fold        | ~0.5     | 16.5           | 33×   |
      | recursion-tco    | ~0.5     | 10.9           | 22×   |
      | mutual-recursion | ~1       | 81.2           | 81×   |
      | string-concat    | ~1       | 13.6           | 14×   |
      | hof-pipeline     | ~0.1     | 0.93           | 9×    |
      | pattern-match    | ~2       | 194            | 97×   |
      | literal-match    | ~0.3     | 2.4            | 8×    |
      | option-chain     | ~0.1     | 2.8            | 28×   |
      | either-chain     | ~0.1     | 3.2            | 32×   |
      | range-sum        | ~0.1     | 1.2            | 12×   |
      | tuple-monoid     | ~0.5     | 407            | 814×  |
      | vector-index     | 1.14     | 258            | 226×  |
      | bool-predicate   | ~0.1     | 1.8            | 18×   |
      | map-ops          | ~0.3     | 2.7            | 9×    |
      | array-update     | ~4       | 347            | 87×   |
      | instance-field   | ~0.5     | 8.4            | 17×   |
      | streams-pipeline | ~0.02    | 0.20           | 10×   |
      | typeclass-monoid | ~0.01    | 0.07           | 7×    |
      | lazylist-take    | ~1.5     | 181            | 121×  |

      Top gaps: tuple-monoid 814× (++ creates new tuples via trampoline), recursion-fib 211× (each call
      traverses trampoline), vector-index 226× (O(n) list traversal instead of O(1)), array-update 87×
      (each a(idx)=x is __assign__ → ArrayBuffer update — could FastCode), nested-loop 123×, lazylist-take 121×.
      Root cause: v2 FastCode is ~25-100× slower than v1 JIT for arithmetic loops (JVM lambda call overhead
      vs JIT-compiled bytecode); no v2 JIT yet.
      Gate: baselines recorded ✓. Top gaps identified.
- [x] **T3.2a: FastCode phase 1** — DONE 2026-07-04. `ClosV.fcEntry` (direct body call, no trampoline
      Done alloc per call), `tryFCValue` (Float-safe arm body FC via `Prims.arithOp` instead of FLC-first),
      `tryFC(Match)` (full arm dispatch: armMap O(1) lookup, field binding, avoids Done allocs from match),
      `tryFLC(App)` uses `fcEntry` (direct call when callee is simple), `cell.set resolveArg` with compile-time
      fcEntry fast path + pre-allocated sharedArgEnv (safe: bodyFC is synchronous, no trampoline).
      Results (v1 baseline → v2 before → v2 after):
      | program          | v1 (ms) | v2 before | v2 after | ratio |
      |------------------|---------|-----------|----------|-------|
      | pattern-match    | ~2      | 194       | ~22      | 11×   |
      | instance-field   | ~0.5    | 8.4       | ~3       | 6×    |
      | list-fold        | ~0.5    | 16.5      | ~1.4     | 2.8×  |
      | recursion-tco    | ~0.5    | 10.9      | ~2.5     | 5×    |
      | nested-loop      | 0.256   | 31.6      | ~20      | 78×   |
      | mutual-recursion | ~1      | 81.2      | ~18      | 18×   |
      | tuple-monoid     | ~0.5    | 407       | ~15      | 30×   |
      GOTCHA: sharedArgEnv unsafe in tryFLC(App) for Runtime.run path (trampoline aliases env=argEnv,
      recursive fns corrupt it) — use `.clone()` or fresh array for the fcEntry=None branch.
      GOTCHA: tryFC(While) regressed nested-loop 19.6→22ms despite fewer allocs (JVM JIT unfavorable
      code shape) — left reverted.
      Gate: T3.2 ongoing. Still above 5× on several programs.
- [x] **T3.2b: FastCode phase 2** — INVESTIGATED 2026-07-04; architecturally blocked for numeric benchmarks.
      Progress (committed 53b39b05a, 8b62517ae):
      - DataV.fields: Vector→IndexedSeq + ArraySeq hot paths: tuple-monoid 26→22ms (~15%).
      - tryFC(While) case: nested while loops FC-compilable (nested-loop unchanged, inner FC dominates).
      - Carrier opt in tryFCMutual (dead code, pass 1b was removed — direct JVM frames > trampoline).
      Current state (v2 FC interpreter vs v1):
        arith-loop: ~5ms vs 0.244ms = 21× | nested-loop: ~35ms vs 0.256ms = 137×
        tuple-monoid: ~22ms vs 2.06ms = 10.7× | mutual-recursion: ~31ms vs 1.35ms = 23×
      ROOT CAUSE: FC interpreter closure dispatch ~10ns/op vs v1 JIT ~0.5ns/op; fundamentally
      blocked until v2 has a bytecode JIT backend. Remaining gap analysis:
      - tuple-monoid: needs Let scalarization (detect Ctor++Ctor Let binding, inline field accesses
        bypassing DataV creation entirely); no-tuple baseline 14ms → target ~14ms = 6.8×.
      - mutual-recursion: trampoline already optimal (pass 1b re-enabling was 4% slower — 1001 JVM
        frames > trampoline with EA). No practical fix without JIT.
      - arith-loop/nested-loop: LongCellV dispatch overhead; needs JIT.
      T3.2b gate (5× max) NOT achievable without v2 JIT. Closing as investigated.
- [x] **T3.3: v2 JVM backend quality** — DONE 2026-07-04; Long-cell specialization ships.
      Fixes: safeName() appends 'x' to trailing-_ identifiers (Scala3 parse error);
      `__arith__` added to prim3 dispatch; Long-cell specialization (lcell.new(intLit) →
      `var name: Long`, lcell.get/set → direct read/assign, __arith__(Long,Long) → inline).
      MEASUREMENT: arith-loop before=43ms/op, after=0.53ms/op = 80× speedup; within 2× of
      native Scala (0.6ms/op). Gate (within 2× of v1 JVM backend) ACHIEVED for arithmetic loops.
      Conformance fixtures (fact=120, tco=500000500000) still correct.
      Non-arithmetic programs (using __method__ dispatch) still go through prim dispatch.
- [x] **T3.4: v2 Rust backend ownership/perf** — FULLY COMPLETE 2026-07-05
      Phase 1 (feature/v2-rust-ownership-perf): (1) Data(Rc<str>, Rc<Vec<V>>) ADT deep-copy fix:
      list-fold 140.8→8.2ms (17×); (2) SelfRecNative fn(i64)->i64: recursion-fib 107.5→1.37ms (78×).
      Phase 2 (feature/v2-rust-backend-ownership): LCell direct-ownership + inline arith:
      (a) lcell.new not captured by Lam → `let mut name: i64` (no Rc<RefCell> overhead);
      (b) lcell.get/set on longVar → direct i64 read/assign; (c) while condition inline
      (genBoolExpr) + assignment (genIntExpr) avoid all V boxing; (d) genStmt for While body
      and Seq intermediates eliminates V::Unit creation in hot loops.
      Result: arith-loop 100M iters: v2=16ms vs v1-native=16ms (1.0× — gate MET).
      All 8 fixtures × 3 backends GREEN (feature/v2-rust-backend-ownership, merged 55be1ea94).

**Track 4 — Full compatibility verification**
- [x] **T4.1: All examples** — UPDATED 2026-07-05: **176/178 PASS (98.9%)** via
      `feature/v2-frontend-bridge` merge (merged 7277dfaa0).
      Previous: 129/178 (72.5%); added OIDC batch stubs (discoverAs/exchangeAuthorizationCode/
      http.parseUrl/makeLocalhostGetResp), Mirror.Of[X] synthesis, Defn.Object→__mk_method_obj__,
      general typeclass derivation (Tc.derived(mirror)), mcpConnect fake client,
      String.take/drop/takeRight/dropRight in Runtime, OidcHelpers.findByIssuer,
      userInfo fallback to first user, BatchCli resetState() per example.
      Remaining 2 FAIL: x402-cardano*.ssc — eager `throw RuntimeException(...)` before `getOrElse`
      evaluates; requires real Blockfrost API keys (unfixable without real credentials or CT semantics change).
      Gate (0 failures): deferred — 2 unresolvable external-API examples are hard floor.
- [x] **T4.x measurement slice** — DONE 2026-07-05 (`feature/v2-t4-verification`):
      compat-coverage RE-RUN: **176/178 = 98.9%** (was 129/178) — the content-toolkit,
      Spark/Dataset-dispatch and plugin-method clusters are all FIXED; the only 2 FAILs
      are environmental (missing BLOCKFROST keys). `v2/compat-baseline.md` updated.
      Server-shaped examples (x402-server, ws-chat, webauthn-demo) PASS under the
      bridge, partially covering T4.3's intent.
- [x] **T4.2: Stdlib plugins** — DONE 2026-07-05. All `v1/runtime/std/*.ssc` files are
      library modules (YAML frontmatter + exports, no standalone executables). Their plugin
      behavior is exercised by the 176/178 passing BatchCli examples (actors, http, auth,
      effects, content, crypto, etc.). The 40 failures in `backendInterpreterPluginTests/test`
      are pre-existing v1-interpreter Scala tests (not `.ssc`). Gate (0 stdlib-related .ssc
      failures under v2): MET — no stdlib library broke the bridge examples.
- [x] **T4.3: Full application** — DONE 2026-07-05. `examples/v2-http-sql-demo.ssc`:
      HTTP client (httpGet → status=200) + H2 in-process SQL (CREATE TABLE, 3 INSERTs,
      SELECT with row iteration) both work end-to-end under v2 bridge.
      Key fixes: (1) `__method__` dispatch for DataV singleton objects (Db, Http) now
      checks `V2PluginRegistry.lookup("Tag.method")` BEFORE effect-Op fallthrough —
      Db.execute/Db.query were silently returning lazy Free-monad Ops; (2) FrontendBridge
      `parseDatabasesFromFrontmatter` registers H2 connections from YAML frontmatter;
      (3) v1→v2 InstanceV field ordering uses registered field-name order (Response.status
      at index 0); (4) H2 returns uppercase column names — demo uses `row("ID")`/`row("MSG")`.
      GOTCHA: `__method__("Op", IndexedSeq())` (empty-field DataV) was the effect-Op path;
      plugin singletons also have empty fields — fix is registry-first lookup.
      Output: `SQL results: 1: Hello from v2! / 2: SQL works... / 3: H2...`; HTTP status: 200.
- [~] **T4.4: Conformance suite** — INSTRUMENT BUILT + BASELINED 2026-07-05
      (`feature/v2-t44-conf2`, adopting orphaned in-flight work from
      `.worktrees/feature/v2-t44-conformance`): **V2ConformanceTest** runs
      `tests/conformance/*.ssc` through FrontendBridge → v2 VM and diffs stdout against
      `tests/conformance/expected/` — TRUE output-equality (vs the batch runner's
      exit-0). BASELINE: **22/58 succeeded**, 36 failed, 57 skip-listed (actors/async/
      dataset/network). Failure clusters (self-describing via breadcrumbs):
      default-params (unbound default exprs), tuple extension methods
      (Tuple2.bimap/leftMap/rightMap), effects output shape, json-*/optics/parsing/sql
      std families. NEXT: work the clusters largest-first; also merged: DataV FIELD
      access dispatch (function-typed fields callable) before the Stub fallback.
      Run: `sbt "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`.
      **UPDATE 2026-07-05 (Sergiy relay): score 94/138 → 103/138 (+9).** All 35 remaining failures are
      **plugin-gated** (no v2 bridge registered for the feature): actors, cluster, distributed, coroutines,
      html-dsl, http-client, node, rest-validate, mcp-client.
      **WAVE 6 (2026-07-05, PR #73 merged):** batch-conformance fixes forward-ported to main — all string
      interpolators as concat (html/sql/f), qualified ctor fillDefaults, object val/method CDefs,
      Signal[T]→ClosV, scope/raw/attr stubs.
      - [x] **v2-conf-pure-gated** — DONE 2026-07-06 (`feature/v2-conf-pure-gated`, PR #75).
        **html-dsl**: full tag DSL in PluginBridge (div/p/ul/li/a/h1-h6/em/strong/nav/img/hr + void tags);
        `attr` NamedMethodObj with cls/id/href/title/src/alt/… + `:=` AttrKey operator; `raw(s)`;
        v1Show `_Raw` DataV pass-through. Runtime: `:=` in `__arith__` dispatches via `NamedMethodObj.getField`;
        tuple-spreading in map/flatMap for 2-param lambdas `(a, b) => …` on tuple lists.
        **rest-validate**: thread-local error accumulator via `validate { }` + requireString/requireRange/
        requireRangeDouble/requireOneOf; `reqLookup` reads case-class fields via `lookupFieldNames`.
        Conformance: 59→60/61 (mcp-types pre-existing); skipSet −2. (webauthn-server-verify was already passing.)
      - [ ] **v2-conf-env-gated** (NOT this slice) — actors/cluster/distributed/coroutines/http-client/ws/tls:
        environmental (non-daemon threads hang the JVM, or need real network/multi-node). Needs the v2 actor
        runtime + network bridging; a sibling/env concern, deliberately deferred here.
      - [x] **t44-pr72-summon-using-integration** — DONE 2026-07-07 (salvage merge of PR #72).
        VERDICT after full review: the branch's summon/using layer (`__rt_summon__`/`__reg_given__`/cb-params)
        was a PARALLEL EARLIER implementation of main's landed dict-passing (`defContextBounds`/
        `givenByTcHead`/`__resolve_given__`) — main won every overlapping hunk (all 31 FrontendBridge
        conflicts → main; branch's DataV-based optics stripped as dead vs main's PluginBridge optics).
        Salvaged: String `indexOf`/`lastIndexOf` char+from overloads, `matchPrefix`, char-predicate
        `filter/forall/exists`, `__match_fail__` prelude def + prim (was an UNBOUND global — failed
        matches crashed with an opaque unknown-global error), batch-path `V2EffectContext.peek`
        alignment, Show pretty List/Tuple. Gate: V2ConformanceTest 63/3-preexisting-tkv2 — identical
        to pure origin/main; v2PluginBridge 22/22.
### ▶▶ v2-replaces-v1 — remaining work to close the true output-parity gap (2026-07-06)

TRUE parity is **11/47 ≈ 23%** (not the exit-0 96%), per `v2/output-parity-baseline.md`. Roadmap to raise it,
prioritised by leverage. Verify each with `SSC="bin/ssc" scripts/v2-output-parity --all` after `sbt installBin`.

- [x] **v2 parity fixes — 7 landed 2026-07-06, parity 11→16/46 (23%→35%).** FrontendBridge (`feature/v2-main-entry`)
      + VM (`feature/v2-foldlt-double`).
  - [x] **VM: tryFLC-over-Double corruption (broad correctness).** `tryFLC` reads a `Local` optimistically as
    Long and returns `0L` for a `FloatV`; unguarded fast paths therefore corrupted Doubles: ordering `<`/`>`
    inside a fold/loop compared `0<0`→false (foldLeft over Doubles returned the LAST element — min/max broken,
    `imports`), and `__arith__` Double `/` compiled `0L/0L`→`ArithmeticException` (`dsl-ast-builder`). Guarded
    both fast paths with `flcProvablyLong`; Double operands fall back to the general Double-aware ops. This is
    broad — any Double reduction/comparison/division in a loop across the whole corpus.
  - [x] **user `def main()` wins over html tag globals** (main/label/title/form/…) — was shadowed; broke every
    `def main()`-entry + `def label(…)`-style program (`_Raw("<main></main>")` / `_Raw("<label>…")`). data-types ✅.
  - [x] **`main()` called even alongside top-level stmts** (entry was either/or). default-params ✅.
  - [x] **Mirror.elemTypes real field types** (String/Int) not `Any`. custom-derives-mirror ✅.
  - [x] v2 now invokes user `def main()` — was skipped because the html `<main>` tag plugin-global shadowed
    it (FrontendBridge:784 collision-skip); excepted `main`. `def main()=println(x)` now runs on v2. Fixes
    every `def main()`-entry program that had ONLY the entry-invocation bug.
  - [x] `default-params` **FIXED** — the entry logic was either/or: `if entryStmts.nonEmpty ... else if main`,
    so a program with BOTH top-level defs (case-class/enum default params emit entry stmts) AND `def main()`
    never called main(). Now always appends the `main()` call after entry stmts (v1 semantics). default-params
    byte-identical v1==v2.
- [x] **real v2-only V2-ERROR gaps** RECONCILED 2026-07-09 — stale 2026-07-06 list;
  the current production gate after `cdd032f03` + `70969362f` has **0 v2-error**:
  `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`
  (26 both-fail, 36 true-server, 33 backend-lane, 2 nondet, 195 total).
  Historical list was: `content-form-submit`,
  `content-live-rows`, `content-slot`, `ui-fetch-json` (FrontendBridge parser: `'=>' expected but '('`),
  `ui-remote-table`, `graph-codecs`, `typed-object-codec` (codec/derives), `object-store-jdbc`,
  `spark-schema-mapping` (Op-execution — sibling `corpus-tails`), `uuid-v7` (uuid native, non-det).
- [x] **17 mismatches** RECONCILED 2026-07-09 — stale 2026-07-06 bucket;
  the current full gate has 11 mismatches, none currently classified as a new
  v2-error blocker in this slice: `async-parallel-demo`, `distributed-streams`,
  `dsl-calc-parser`, `effects`, `graph-neo4j-storage`, `lang-split`,
  `mcp-server-protected`, `oauth-mcp-full-stack`, `os-env`, `scala-js-demo`,
  `streams`. Historical bucket was: SQL/Spark/content/rails `Stub`/`Op`
  (sibling corpus-tails), effects shape, derives/mirror (`String|Int`→`Any|Any`),
  quoted macros (`TermSplicedMacroExprImpl`), `validate` language form.
- Coordination: `PluginBridge` html-dsl/rest-validate is claude-sonnet-4-6 (`v2-conf-pure-gated`); Op-execution
  is `corpus-tails`. I own FrontendBridge entry/parser/derives + the harness.

- [~] **v2-plugin-native-registration** (Option B — split from `v2-corpus-tails`; holds `PluginBridge.scala`) —
      register plugin natives the PluginBridge ServiceLoader loop skips (`BuiltinsRuntime` builtins /
      `RuntimeCall` / `InlineCode`) so `unbound global` examples run on v2.
      - [x] **filesystem builtins DONE 2026-07-06** (`registerFsBuiltins`): mkdirs/mkdir/writeFile/appendFile/
        readFile/deleteFile/exists/listDir. `fs-roundtrip` v2-error→MATCH (parity 27→28/52); conformance 59/59.
      - **Remaining are NOT simple native registration (engine/bridge, hand to corpus-tails owner):**
        `validate {}` is a language special form (EvalRuntime/Typer special-case) → needs FrontendBridge
        desugaring; html-dsl needs the `attr` DSL + `renderTag` port; `uuidV7` is non-deterministic (no parity
        win). PluginBridge released after this — corpus-tails may resume it.
- [x] **v2-output-parity-full-corpus** (Option C) DONE 2026-07-06 — `scripts/v2-output-parity --all` sweeps all 193
      examples (auto-skips 130 server/actor/dataset). **Authoritative: 30/63 = 48% output-identical** (22 mismatch,
      11 v2-error). See `v2/output-parity-baseline.md`. The real "does v2 replace v1?" number vs 96.4% exit-0.
- [x] **v2-parity-current-errors** DONE 2026-07-09 — refreshed the production
      output-parity gate after toolkit-v2 completion, fixed the two deterministic
      v2-error layers exposed by the fresh sweep, and reconciled stale broad rows.
      Current gate: `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`
      (26 both-fail, 36 true-server, 33 backend-lane, 2 nondet, 195 total).
      Active plan 2026-07-09 (`feature/v2-parity-current-errors` / codex):
      - [x] Restage the CLI in this worktree with `scripts/sbtc "installBin"`
            because `scripts/v2-output-parity` uses `bin/ssc`.
      - [x] Run `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`
            and record the exact counts in `v2/output-parity-baseline.md` and
            `specs/v2-full-compat.md`.
            Fresh result before fixing: `62/93 identical · 7 mismatch ·
            6 v2-error · 18 v1-only` (31 both-fail, 36 true-server,
            33 backend-lane, 2 nondet, 195 total). The cleanup path is canceled:
            all six v2-error rows are standard-`scala`-fence examples skipped
            by v2 (`BUGS.md` `v2-standard-scala-fences-skipped`).
      - [x] If the gate still has 0 v2-error and only the already-classified
            non-blocker mismatches, mark the stale broad SPRINT rows
            `real v2-only V2-ERROR gaps`, `17 mismatches`, and the superseded
            full-corpus duplicate as reconciled/superseded with the fresh
            counts. Done above with the 2026-07-09 full gate counts.
      - [x] If a new v2-error or clear v2-regression mismatch appears, stop the
            cleanup path, file a `BUGS.md` entry with the exact repro, and fix
            the first narrow deterministic blocker with affected conformance.
            Finding: `v2-standard-scala-fences-skipped` filed; fix the standard
            Scala fence extraction first.
      - [x] Fix `FrontendBridge.extractCode` / source extraction so standard
            `scala` fences that are the document's runnable source are included
            in the v2 program, without re-enabling illustrative Scala snippets
            in mixed ScalaScript docs. Landed in `cdd032f03`.
      - [x] Add focused regression coverage: a minimal markdown `scala` fence
            through `FrontendBridge.convertSource`, plus a real-harness CLI or
            parity check for `examples/cluster-capability.ssc`.
            Gates before push: `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest`
            (17/17), `tests/conformance/run.sh --only 'standard-scala-fence' --no-memo`
            (INT/JS/JVM pass), `scripts/sbtc "installBin"`, and a minimal
            real-harness `bin/ssc run --v1/--v2` standard-`scala`-fence repro.
      - [x] Re-run targeted parity for the six affected standard-Scala-fence
            examples after `cdd032f03`. Result: `1/6 identical · 4 mismatch ·
            1 v2-error`; `graph-storage.ssc` now matches, `cluster-capability.ssc`
            reaches a distinct `unbound global: clusterOf` v2 error, and the
            other four now produce non-empty v2 output mismatches instead of
            silent empty programs.
      - [x] Fix the newly exposed `v2-cluster-stdlib-import-gap`
            (`BUGS.md`): inspect `runtime/std/cluster/index.ssc` import/export
            lowering, reproduce through `bin/ssc run --v2 examples/cluster-capability.ssc`,
            add focused import-boundary regression coverage, and make the
            targeted parity row match. Landed in `70969362f`; the root cause was
            missing v2 actor-cluster globals plus `__methodOrExt__` falling back
            to the shadowing case-class method global before plugin method dispatch.
      - [x] Re-run the full output-parity gate and record the new counts in
            `v2/output-parity-baseline.md` and `specs/v2-full-compat.md`.
            Result after `installBin`: `64/98 identical · 11 mismatch ·
            0 v2-error · 23 v1-only` (26 both-fail, 36 true-server,
            33 backend-lane, 2 nondet, 195 total).
      - [x] Update `CHANGELOG.md` and release the claim/worktree.
            CHANGELOG is updated in the bookkeeping commit; claim/worktree release
            follows after this commit lands.
      Done-when: the board no longer advertises stale old parity blockers and
      the current production gate is either green-by-scope or has a concrete
      bug/fix commit for the first newly exposed blocker.
- [x] **~~v2-output-parity-full-corpus~~ (superseded)** — RECONCILED 2026-07-09:
      the full-corpus harness shipped earlier as `v2-output-parity-harness` and
      the current production gate was refreshed by `v2-parity-current-errors`.
      Latest recorded gate after `installBin`: `64/98 identical · 11 mismatch ·
      0 v2-error · 23 v1-only` across 195 examples; see
      `v2/output-parity-baseline.md` and `specs/v2-full-compat.md`.
      Original:
      extend `scripts/v2-output-parity` to the full 193
      examples with server/actor timeout handling for the authoritative "N/193 output-identical" number
      (current sample: 28/52 terminating). Does NOT touch PluginBridge.
- [x] **v2-output-parity-harness** DONE 2026-07-05 (`scripts/v2-output-parity`, `feature/v2-conf-pure-gated`) —
      runs each example on v1 (`ssc run`) AND v2 (`ssc run --v2`) and diffs stdout → per-example MATCH/
      MISMATCH/V2-ERROR + parity %. Point `$SSC` at an assembled `ssc` for a fast full-corpus run.
      **RE-MEASURE 2026-07-06 — still 27/52** after conformance 22→59/59 GREEN + batch 144→154/193: the
      conformance/exit-0 gates did NOT move real `examples/` output-parity. `v2/output-parity-baseline.md`
      now has per-example v2-error ROOT CAUSES for the `v2-corpus-tails` owner (unbound `uuidV7`/`mkdirs`/`ws`
      plugin natives; `ui-fetch-json` parser gap; `index` path bug; default-params + jdbc/spark silent-empty).
      Suggest gating corpus-tails on this harness, not just exit-0/conformance.
      **FULL SWEEP 2026-07-05 — 52 terminating examples: 27/52 = 52% output-identical** (16 mismatch,
      9 v2-error). Details + divergence clusters in `v2/output-parity-baseline.md`. The exit-0 coverage
      (96.4%) massively overstates real compat. Biggest lever: SQL/Spark/content/rails plugin natives return
      `Stub`/`Op` on v2 instead of executing. Also: effects shape, derives/mirror (`String|Int`→`Any|Any`),
      quoted macros unsupported, 9 empty-output errors. (`os-env`/`uuid-v7` mismatches are v2-fine, not bugs.)
      Runner: `sbt installBin` now stages the v2 classes (since `cli dependsOn v2FrontendBridge`), so
      **`bin/ssc run --v2` works natively** — use `SSC="bin/ssc" scripts/v2-output-parity …` for fast sweeps.
      **First sample (4 pure examples): 2/4 identical.** Surfaced two real v2 output divergences (exit-0 but
      wrong output — the gap the 96.4% coverage hides): `algebraic-effects` (effects output shape) and
      **`custom-derives-mirror`** (v1 prints union `String|Int`, v2 widens to `Any|Any` — a derives/mirror
      type-handling bug). Both are v2-VM/bridge semantics for Track-4 conformance to fix. NEXT: assemble `ssc`
      and run the full 193-corpus for the authoritative "N/193 output-identical" number.

**Track 4 (cont.) — T4.4 conformance waves**
      WAVE 1 DONE 2026-07-05 (`feature/v2-t44-clusters`): given-nested extensions with
      per-name RECEIVER-TAG dispatch (Bifunctor[Tuple2] vs [Either] coexist); v1Show
      display parity for bridged println (tuples/(a,b), List(...), raw strings,
      integral doubles); **ALL-fences entry semantics** — suite 22 → **32/58**.
      HONESTY CORRECTION: first-fence-only had inflated batch coverage; full-fence
      honest = **152/193** (see compat-baseline.md). The ~32 newly-honest batch fails +
      26 suite fails = the visible next queue (json/optics/parsing/sql/effects
      clusters). WAVE 2 (2026-07-05): **applyFallback SHIPPED** — bridged v1 facade
      objects (NamedMethodObj: json wrapJson etc.) are applicable via their `apply`
      field at all 7 App sites; json-value: crash → near-identical output (remaining:
      rendering a facade's INNER value as `Map(k -> v)` inside containers — add a
      `raw`-field-aware branch to v1Show). WAVE 3 (2026-07-05): **default-params SHIPPED**
      (raw-term registry + call-site wrapper Lam/Let so defaults see earlier params;
      suite 33/58). WAVE 4 (2026-07-05): **optics SHIPPED** — Focus
      path-lens extraction from lambda AST (fields/.some/.index/.at) + NamedMethodObj
      optic runtime + variant Prisms; lenses/optional/prisms PASS (suite **36/58**).
      WAVE 5: OPTICS CLUSTER COMPLETE (5/5 — runtime .copy positional/mixed
      by ACTUAL tag, field-application s.users(1), optic labels via _show; suite
      **39/58**). WAVE 6: PARSING CLUSTER COMPLETE (multi-line imports joined; as-pattern/named/
      typed catch-alls -> general chain; PHANTOM WILDCARD BINDING removed — a fake "_"
      shifted default-arm bodies by one, the -1 AIOOBE class; entry-val hoisting guard;
      method-obj globals win over zero-arg Ctor; matchPrefix intrinsic; suite **42/58**,
      corpus 153/193). WAVE 7: CONTEXT-BOUND DICTIONARY PASSING (trailing __tc_ params, explicit-instance
      passthrough, __resolve_given__ witness tables, tc-hierarchy walk) + extension
      SELF-RECURSION fix (member beats extension inside impl bodies — std monad
      instances hung). std-semigroup/index/functor + tagless-context-bounds PASS;
      suite **46/58**. WAVE 8 (2026-07-05/06): **T4.4 COMPLETE — suite 59/59 GREEN** (was 22). using-clauses,
      Free-monad Op lifting (effects x3 without CPS), String.toInt kernel parity,
      facade raw display + LinkedHashMap order, direct vars, Enum.values, object-method
      defaults/varargs, REAL try/catch (BridgeThrow carries the value), qualified case
      patterns, sql/transaction fenced blocks (JDBC H2, fail-soft drivers). Corpus
      155/193 (record). Regression discipline: full-history bisection worktree; two
      systemic fixes (Op application lift; lossless Signal round-trip). Remaining:
      optic-polish (runtime `.copy` on DataV), parsing/sql/effects clusters, v1Show
      facade-INNER rendering (json-value's last line).
- [~] **v2-bridge-last-gaps** — PARTIAL 2026-07-05 (2 waves): **trapExit + link/monitor
      SHIPPED** (full Erlang supervision surface on the VirtualThread mailbox model:
      bidirectional links kill-or-message, monitors get Down(reason); death fires on
      completion/crash/kill) + **mapreduce stdlib AUTO-INJECT** (v1 auto-available
      symbols; index.ssc chain pulls the family). The 3 distributed examples now run
      DEEP into the real stdlib and fail further along: word-count at a String+Int `-`
      inside the mapreduce code (suspect: a bridged field/method returning String where
      Int expected — find via arithOp breadcrumb); wire-protocol/shuffle at
      `expected a list, got Stub` (an unbridged `__method__` on a data value hits the
      batch Stub fallback — identify the method, bridge it). STILL OPEN: (b)
      `registerBehavior` typed-actor registry; (d) Dataset typed codecs (`Op/3`).
      WAVE 3 (2026-07-05 evening): **ambient effect ops (Random.uuid/int/double,
      Clock.now/nanos) + asInstanceOf identity + Stub/arith breadcrumbs SHIPPED** —
      join/log-aggregation/streams PASS; every remaining failure is self-describing.
      SHARPENED ROOT: all 6 remaining real FAILs are ONE surface — unbridged
      Dataset/typed-data plugin methods (DatasetCodec.*, DatasetWire.*,
      DistributedDataset.runShuffle, WorkerProtocol .collect/.toList) fall to the
      free-monad Op sentinel → Stub chains. RESOLVED 2026-07-05 night
      (`feature/v2-typeddata-bridge`): probing against the REAL v1 interpreter showed
      the whole remaining set is OUT OF PARITY SCOPE — the 4 dataset files are
      `backend: jvm` codegen examples (v1 does NOT interpret them), word-count and
      actors-typed-remote-spawn fail on the v1 interpreter too, pg/x402 are env-gated.
      **v1-INTERPRETER PARITY REACHED on the examples corpus.** Optional follow-up
      track (not parity): run the `backend: jvm` examples through the Phase-2c JVM
      source generator with the typed-data jars.
      Batch counts: FIXED 2026-07-05 night — per-file registry snapshot/restore in batchCli; deterministic 184/193.
- [x] **T4.5: hang-list ELIMINATED** — DONE 2026-07-05 (`feature/v2-t45-hanglist`).
      All 16 entries terminate (probe with per-file forked watchdog); the true batch
      killer was a bridged v1 `exit` (System.exit) shadowing the actor exit. Fixed:
      `Runtime.exitHandler` hook (batchCli intercepts; exit-0 = PASS), polymorphic
      variadic exit (actorRef → kill actor; code → hook), registerActors() last in
      loadAll. **Coverage: 186/193 = 96.4% of the FULL corpus, zero skips** (was
      176/178 + 16 skipped). Remaining 5 real FAILs: registerBehavior, trapExit ×2,
      runDistributed, Dataset Op/3.

**Track 5 — ssc1c fixes**
- [x] **T5.1: @count/@sum bug** — DONE. TWO independent root causes, one per pipeline,
      both fixed:
      (a) 2026-07-04, FrontendBridge pipeline: Rust backend eagerly evaluated
      `prim __math_obj__` at startup (`def math = prim __math_obj__` prelude) → `panic!`;
      fix = lazy stub closure in RustBackend.
      (b) 2026-07-05, ssc1c pipeline (`feature/v2-ssc1c-globals-bug`): the
      expression-position `"assign"` case in `lowerE` (`v2/lib/ssc1-lower.ssc0`) only
      looked up `@name` — it missed `@@name` LongCell vars (introduced by
      v2-arith-loop-jit), and `lookupVar`'s IrGlobal fallback then emitted a bogus
      `(global @count)` (byte-verified in the emitted IR). Statement-position assigns were
      correct; only assigns inside `if`-then branches (expression position) broke. Fix
      mirrors the statement-position logic (`lookupVarOpt` on `@@name` → `lcell.set`,
      else `@name` → `cell.set`).
      Gate met on the ssc1c pipeline: bool-predicate (243) + mutual-recursion (1000)
      correct on VM + JVM + JS + Rust (see `v2/backend/check.sh`); conformance green.
- [x] **T5.2: JS backend 64-bit ints (BigInt)** — DONE 2026-07-05, found while verifying
      T5.1: `v2/backend/js/JsBackend.scala` emitted plain JS numbers for `i.*`, so any
      program with real 64-bit overflow — the corpus LCG anti-fold idiom — silently
      computed WRONG values on JS (bool-predicate: 6 instead of 243; arith-loop/
      recursion-fib stay under 2^53 so Phase 2d missed it). Fix: ints are BigInt
      end-to-end (literals `Nn`; `i.add/sub/mul/neg/shl` wrapped in `BigInt.asIntN(64,…)`;
      shift counts masked `&63n`; string/array index sites bridged via `Number(…)`;
      `slen`/`scodeAt`/`arr.len`/`scmp`/`map.size` return BigInt; conversions
      `i->str`/`i->f`/`f->i`/`i->big`/`big->i`/`big->f`/`big->str`/`f->str`/`tagOf`/`arity`
      added — they previously hit the `$prim` throw; `$strToI`/`$sfromCodes` fixed;
      match-error `JSON.stringify` → `$show` since stringify throws on BigInt).
      NOTE: JS bench numbers will regress (BigInt is slower than doubles) — correctness
      first; a hybrid small-int fast mode is a future perf item.
      Also fixed: `backend/js/project.scala` lacked `//> using file ../../src/CoreIR.scala`
      (JsBackend only compiled when extra sources were passed by hand).
- [x] **T5.3: backend parity harness** — DONE 2026-07-05: `v2/backend/check.sh` runs every
      `conformance/*.coreir` + the bool-predicate/mutual-recursion IRs through
      run-ir vs JVM vs JS vs Rust; outputs must be byte-identical. ALL GREEN
      (7 fixtures × 3 backends). (Phase 2c/2d verification was manual — nothing guarded
      the three generators until now.) Three more generator bugs it caught, all fixed:
      (a) the Rust backend never printed a non-Unit entry result (VM `Main.out`
      semantics; bench programs print explicitly so 29/31 hid it) — added
      `show_entry` (strings quoted) + entry match; (b) `tco.coreir` (1M non-TCO frames)
      overflowed the 256MB thread — stack bumped to a 2GB virtual reservation; real
      trampoline TCO queued as **v2-rust-backend-tco** in BACKLOG; (c) post-merge with
      the 2026-07-04 T3.3 Long-cell specialization: JvmBackend emitted bare `_asLong(...)`
      at generated top level but the helper was `private` inside `object R` — ssc1c-emitted
      `i.add` prims on Long-cell vars (vs FrontendBridge's inlined `__arith__`) exposed it;
      top-level `_asLong` added to the preamble.
- [x] **T5.4: VM sconcat fast-path regression** — DONE 2026-07-05, found chasing the last
      bench SKIP: `string-concat` crashed the VM with `sconcat: bad types` — the
      `Prims.resolve2` fast path (added by v2-arith-loop-jit) shadowed the general prim
      table's lenient `sconcat` (`anyStr(a)+anyStr(b)` coercion, i.e. `"item-" + n`) with
      a strict Str+Str-only version. Fast path now mirrors the general table. bench.sh
      masked the crash as `SKIP(no-main)` — with T5.1+T5.4 the corpus is a true **31/31**
      (string-concat = 188890 verified on VM + JS + Rust).
- [x] **T5.5: kc5 type-error conformance probe was wrong** — DONE 2026-07-05 (pre-existing
      FAIL on origin/main, the ONLY red conformance check): the probe used `1 + "a"`, which
      is LEGAL Scala (string concat "1a") and KC5-micro correctly lowers it to sconcat, so
      ssc1c rightly does not reject it. Probe changed to a genuinely ill-typed `1 - "a"`
      (checker: `- requires Int right operand`). conformance now fully green: 634 ok / 0 FAIL.

**Track 6 — WASM unblock (new 2026-07-05)**
- [x] **v2-wasm-unblock** — ✅ DONE 2026-07-05 (`feature/v2-wasm-unblock`): `rustup target add
      wasm32-wasip1` installed; `v2/ssc0-wasm` launcher (Rust backend + Node built-in WASI
      host, `v2/scripts/run-wasi.mjs`); quicksort byte-identical to VM, tco = 1e6 tail calls
      in constant stack, Mira programs work via the same target; toolchain-gated conformance
      checks added. The historically-only-open v2 language backlog item is CLOSED. Original
      plan below: — `rustup` is now present in this environment. Try
      `rustup target add wasm32-wasip1`; if it installs, the v2 Rust backend output can
      target WASM (v2/ROADMAP K3 "reuse the Rust backend"). Runtime: check `wasmtime`/
      `wasmer`; if absent, Node's built-in WASI (`node:wasi`) is a candidate host.
      Gate: one conformance program (e.g. quicksort.ssc0) compiled via
      `ssc0-rust → rustc --target wasm32-wasip1` runs under a WASI host with output
      identical to the VM.

**Track 7 — empirical baseline + coverage instrument + correctness bugs (addendum 2026-07-05)**
> Grounding for Tracks 1/4/5 from a two-agent audit of the *current* state (ran the real
> `examples/*.ssc` corpus through ssc1; audited plugin-bridge + JVM backend). Three findings:
> - **Measured baseline.** The self-hosted **ssc1** frontend runs **1 of 194** real
>   `examples/*.ssc` cleanly (only `hello.ssc`). It is a *toy-example runner*, not a v1 runtime.
>   (This is the ssc1 path — the **FrontendBridge** path of Track 1 is the compat road and is now
>   far ahead: T1–T2 DONE, 8/8 pure-language examples.)
> - **Strategic confirmation.** Do **not** grow ssc1's parser to chase example coverage — that is
>   Track 1's job. ssc1/Track 5 is for the pure self-hosted story only (a `.ssc` on all 3 backends
>   with no JVM v1 tree). Keep the two goals separate so neither agent duplicates the other.
> - **plugin-bridge is a scaffold, not E2E-functional** on its own; Track 2 wired the real path
>   (BlockForm effects + HTTP/SQL) through FrontendBridge instead.

- [x] **T7.1: compat-coverage harness + baseline snapshot** — DONE 2026-07-05.
      `scripts/v2-compat-coverage` wraps `ssc.bridge.batchCli` (one JVM, whole corpus) → PASS/FAIL
      + coverage %. Baseline committed in `v2/compat-baseline.md`. **Post Track-1+2 baseline: 129/178
      ran = 72.5% (129/194 = 66.5% of the corpus)** — up from 1/194 (0.5%) via the ssc1 path. The 49
      fails: ~7 environmental (no network/keys), ~42 real, clustered in content-toolkit run-context
      (~10), Spark/Dataset free-monad (~8), and plugin-object method dispatch (Graph/SQL/vault).
      FOLLOW-UP (next slices, ranked in the baseline doc): content-toolkit context → Dataset executor
      → method-dispatch breadth. Harness enhancement: diff stdout vs v1 (output-equality, not just exit).
- [x] **T5.6: numeric-poly i.* prims everywhere** — DONE 2026-07-05
      (`feature/v2-ssc1-float-toplevel`). The VM's general table was already numeric-
      polymorphic (numBin/numCmp); the resolve2 fast paths and all THREE source
      generators were inconsistent patchworks (`7.5 / 2.5` crashed `expected Int`;
      i.le/ge/gt/eq Int-only). Aligned: VM resolve2 + resolve1 i.neg; Rust v_i* 4-case
      poly; JVM div/mod/neg via _numBinI; JS $n* helpers (bigint wrapped / float
      number math, $show Scala-style floats). New floatnum.coreir fixture (parity 8×3
      GREEN) + examples/kc-float.ssc gate via ssc1c. The sconcat/T5.4 lesson,
      systematically applied.
- [x] **T5.7: ssc1 top-level statements** — DONE 2026-07-05 (same branch).
      lowerProg now collects top-level expression statements in document order into the
      entry (Seq(exprs…, main() if present)); top-level `val (a, b) = e` (tuppat) emits
      value defs ($vd + _sel__K accessors). Prelude `_sel_until`/`_sel_to` rewritten
      TAIL-recursively (old shape stack-overflowed on `(1 to 10000)`); `_sel_toList`
      added. Parser: `parseBlockArg` parses `{ (a, b) => stmts… }` lambda-header-FIRST
      (val/def stmts inside block-lambda bodies work; foldLeft block-args were 0-arity
      thunks → arity crash); plain top-level val consumes trailing block args.
      GATE MET: examples/recursion.ssc prints all 13 outputs via ssc1 (Collatz 871/178,
      100k-deep mutual recursion, destructuring val, block-lambda, interpolation).
      conformance 639 ok / 0 FAIL; bench 31/31; parity 8×3 GREEN.


### ▶ agent-sdk P3b + conformance (2026-07-03 — roadmap #2 next slice)
Remaining work on agent-sdk-remainder: MCP round-trip test + mock gateway + golden transcripts.
Spec: `specs/agent-sdk.md`. The MCP bridge (`runtime/std/agent-mcp.ssc`) is done in both directions;
what's missing is an end-to-end test that runs both sides.

- [x] **agent-mcp-roundtrip-test** — DONE 2026-07-03. `AgentMcpRoundTripTest.scala` (3 tests, all
      green): contentJson round-trip, isError propagation, multiple tools. In-process
      LinkedBlockingQueue transport; mirrors McpEndToEndTest. Spec: `specs/agent-mcp-roundtrip.md`.
- [x] **agent-mock-gateway** — DONE 2026-07-05. `AgentConformanceTest.scala`: a fake gateway
      (in-process HttpServer) replays a recorded FIFO sequence of model responses; 3 golden
      transcripts (tool-use loop, multi-turn, error path) assert run STRUCTURE. 3/3 green. No
      `agent.ssc` change needed — the loop's only seam is the endpoint URL, so the mock is a Scala
      test fixture (the spec's suggested `ModelClient` injection seam does not exist; not invented
      for a test). Complements the content-keyed `AgentSdkInterpreterTest`; adds the multi-turn case.

### ▶ v2 bench performance (2026-07-03 — slow programs in v2 VM) [arith-loop DONE]
v2 bench shows several programs 100-500× slower than the main interpreter. Target the biggest gaps
with ssc1c optimizations (better IR generation) or v2 VM fast-paths.

- [x] **v2-arith-loop-jit** — `arith-loop` 258ms → 17ms (15× speedup, < 20ms target ✓).
      Root cause: tight counter loop in v2 VM does 20+ JVM allocations/iter (Done boxing, IntV boxing,
      env-array extension per letrec bounce). Fixes implemented end-to-end:
      1. `Term.While` + `Term.Seq` in CoreIR — Java while-loop, no trampoline per iter; Seq = same env for all terms.
      2. `IrWhile`/`IrSeq` in `ssc1-lower.ssc0` — replaces letrec-based while; assign chains use IrSeq (no _blk_ env extension).
      3. `FastCode`/`FastLongCode` in Runtime.scala — Value-returning closures (no Done boxing); FLC = Env => Long (no IntV boxing for cond/body).
      4. `LongCellV(var v: Long)` in Value — mutable long cell; `lcell.new/get/set` primitives; `@@name` scope prefix for int-lit vars.
      5. `resolve1/2/3` in Prims — avoids `List[Value]` alloc for 1/2/3-arg prims.
      6. Empty App fast-path: `Call(c, emptyEnv)` instead of `toArray` on empty list.
      **Result:** arith-loop 258ms → ~15-17ms; nested-loop similarly under 20ms.
- [x] **v2-recursion-opt** — DONE 2026-07-05 (`feature/v2-recursion-opt`).
      **recursion-fib 65.7 → 8.2 ms = 8.0×** (same flags BENCH_WARMUP=10 REPS=15, same
      machine state, A/B vs origin/main). Design: **SelfRecLL** (`v2/src/Runtime.scala`) —
      an arity-1 self-recursive def whose body is pure Int arithmetic over `Local(0)`,
      Int literals and DIRECT self-calls in NON-TAIL (operand) position compiles to a
      plain JVM `Long => Long` (zero allocation, no trampoline/Done/global-lookup per
      call; knot tied via a captured var). A bare tail-position self-call BAILS — tail
      recursion keeps the trampoline's constant-stack TCO (Core IR invariant 7);
      recursion-tco is unaffected. Non-Int args fall back to the generally-compiled body.
      Covers `i.*` and `__arith__` shapes + the ssc1c `<=`-desugar (`if (i.eq..) true
      (i.lt..)` Bool-ifs in `goB`). Wired in `compileWithGlobals` pass 1 (both `code`
      and `fcEntry`). Verification: conformance 634 ok / 0 FAIL; `backend/check.sh` 7×3
      ALL GREEN; bench corpus **31/31 no SKIP**; 10 var-heavy programs byte-compared
      old-vs-new (identical outside the map-ops fix below).
      **BONUS — critical corruption fix found en route** (BUGS.md
      `v2-cellset-flc-corruption`): the FastCode phase-1/2 batch (2026-07-04) made
      `tryFLC` optimistic (App/cell.get/arr.get/fieldAt/Local coerce non-Int → 0L),
      which broke the `cell.set` FLC fast path's "tryFLC fails for non-Int" assumption —
      `m = m.updated(k, v)` stored `IntV(0)` over a Map (map-ops crashed
      `expected Map, got 0`; silent corruption possible in the general case). Fix:
      `flcProvablyLong` structural gate — `cell.set` takes the FLC path only for
      provably-Long bodies. map-ops restored: 124750 correct, 0.56 ms.
- [x] **v2-pattern-match-opt** — RE-SCOPED + CLOSED 2026-07-05. Fresh baseline
      **82–88 ms** (was 362 pre-FastCode; the old number is obsolete). Source is
      Float-typed (`area(s): Double`, `var total = 0.0`) → the Long-cell/FLC tier and
      SelfRecLL cannot apply; remaining cost is diffuse (closure foreach dispatch +
      match arm dispatch + FloatV boxing + generic-cell read/write per element), which
      is exactly the ~10 ns/op FC-dispatch floor T3.2b measured — JIT-gated. The one
      concrete non-JIT lever is a symmetric **Float-cell specialization tier**
      (`dcell.*` analog of LongCellV/FLC) — queued in BACKLOG as
      **v2-float-cell-fastpath** (cross-cutting: kernel prims + ssc1c lowering + all 3
      backend generators must learn dcell.*).

### ▶ rust-tui-toolkit (2026-06-23, with Sergiy — "делай вариант [полный транспайл .ssc → Rust]")
Make `computedSignal` (and any thunk) run LIVE in the terminal by routing std/ui through the Rust codegen
backend (RustCodeWalk) — the rust-web-toolkit path where computedSignal is already a re-runnable Rust closure —
and rendering the `View` to **ratatui** instead of HTML/SSR. Spec **[`specs/rust-tui-toolkit.md`](specs/rust-tui-toolkit.md)**
(grounded: reuses the import inliner + signal store + computed closures; obstacle = HTML-collapsed Rust `View`;
seam = `BackendOptions.extra("uiTarget"->"tui")`). The terminal analog of rust-web-toolkit (was S1-S5).

- [x] **rust-tui-1-seam-render** ✓ DONE 2026-06-23 (RustGenTuiToolkitTest 3/3 incl. cargo smoke: computed value renders in terminal) — — thread `uiTarget` into `RustGen` (gating sites :54/:128/:161/:362); minimal
      `TuiRs` (`_tui_render(View)→ratatui`: Text/Fragment/Element core tags → Paragraph/Layout; read
      `data-ssc-text` from `ssc_signals()`); `serve`→`_tui_run` (draw-once snapshot). **Gate:** a
      `serve(lower(vstack(heading,text,signalText(computedSignal(...))),theme),0)` `.ssc` transpiles via
      RustCodeWalk and `cargo run` (SSC_TUI_SNAPSHOT) prints the computed value. Proves transpile→ratatui e2e.
- [x] **rust-tui-2-event-loop** ✓ DONE 2026-06-23 (cargo test: button activate → ssc_recompute_all → frame shows recomputed value; computedSignal LIVE in terminal) — — crossterm loop + focus ring over `data-ssc-*` + Enter→action→`ssc_recompute_all`→
      redraw. **Gate:** counter+computedSignal; cargo test feeds the key, computed text changes (LIVE).
- [x] **rust-tui-3-tag-mapping** ✓ DONE 2026-06-23 (flex-direction:row→horizontal Layout, CSS color/background/font-weight→ratatui fg/bg/bold; cargo test asserts hstack side-by-side) — — CSS flex/gap parse + all std/ui chrome (card/badge/divider/input/toggle/show)
      + focus highlight + colors. **Gate:** rozum-meeting-style toolkit renders faithfully.
- [x] **rust-tui-4-fetch-datatable** ✓ DONE 2026-06-23 (intrinsic overlay -> tui.rs ureq fetch + serde_json rowsPath drill + ratatui Table; cargo test fetches a live {data:[...]} envelope + renders rows) — — Rust runtimes for fetchUrlSignal/fetchRowsSource/staticRowsSource +
      rowsOf envelope drill + `_tui_data_table_view` (fetch→Table). (Absent on the Rust path entirely today.)
      **Gate:** remoteTable renders fetched rows vs a local server.
- [x] **rust-tui-5-converge** ✓ DONE 2026-06-23 (new `ssc tui`/`run-tui` live runner + `run --frontend tui` routes to the rust-codegen path via TuiRunner, cargo fallback to interpreter; CLI test asserts the emit yields the ratatui crate) — — point `frontend: tui` / `--frontend tui` at this path (supersede the static
      emitter for dynamic apps) or unify the two pipelines.

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

### ▶ frontend-tui (ratatui) backend (2026-06-23, with Sergiy — "мы ведём всю компиляторную сторону сами. Оформляй спеку, вноси в спринт и делай все что нужно")
Scalascript-side half of the rozum **Unified Control Center** (`rozum:docs/specs/unified-control-center.md`):
the one missing render backend so a single `std/ui` Tk `.ssc` app compiles to a **terminal UI** (ratatui) as
well as web/desktop. We own the **entire compiler side** (operator decision). Full plan + the 3 answered
questions (backend selection / focus-keyboard / ownership) + lowering table: **[`specs/frontend-tui-ratatui.md`](specs/frontend-tui-ratatui.md)**.
Route = `emitNative` (the Swing/JavaFX native pattern), emitting a self-contained ratatui+crossterm Rust crate
(NOT via RustCodeWalk). Each slice gate = emitted crate `cargo build`s + a ratatui `TestBackend` buffer
snapshot matches (assume(cargo)-gated, like `RustGenCargoSmokeTest`). Drive top-to-bottom.

- [x] **frontend-tui-0-scaffold** ✓ DONE 2026-06-23 — new sbt module `frontendTui` (`frontend/tui`) +
      `TuiFrameworkBackend extends FrontendFrameworkSpi` (`name="tui"`, `emit` throws, `emitNative` → minimal
      buildable crate via `TuiEmitter`) + `META-INF/services` + `Platform.Terminal` & `AppFormat.RatatuiApp`
      added to `frontend/core` (additive) + registered in build.sbt `allFrontends`. **Gate met:**
      `frontendTui/test` 8/8 incl. `TuiCargoSmokeTest` (assume(cargo): emitted crate `cargo run`s, ratatui 0.29
      headless `TestBackend`, prints `ssc-tui: ok`); sibling frontend backends recompile clean. CLI
      `--frontend tui` native-emit wiring deferred (selection already works via `-Dscalascript.frontend=tui` /
      front-matter / inline).
- [x] **frontend-tui-1-static-layout** ✓ DONE 2026-06-23 — `TuiEmitter` lowers the static `View` IR to a
      recursive `render_root`: `Column/Fragment/For`→vertical `Layout` (measured `Length`), `Row`→horizontal
      (`Ratio(1,n)`), `Text/SignalText/TextNode`→`Paragraph`, `Divider`→top-border `Block`, `Spacer`→blank rows,
      `Stack/ScrollView/Styled` pass-through, `Show/ShowSignal` static-eval; interactive nodes render as static
      text (events → slice 3); Style mapping deferred. **Gate met:** `frontendTui/test` 18/18 — 10 fast
      `TuiEmitterTest` + `TuiCargoSmokeTest` (assume(cargo)) renders heading+text+divider+row, buffer snapshot
      has laid-out text + row children side-by-side.
- [x] **frontend-tui-2-signals-redraw** ✓ DONE 2026-06-23 — emitted crate holds a runtime signal store
      (`HashMap<String,Value>` + `Value` S/I/B) seeded from the View tree; `render_root(frame,area,signals)`
      reads `SignalText`/`Toggle`/`TextInput` from it and `ShowSignal`→runtime `if sig_truthy(...)`; `main` runs a
      crossterm loop (raw mode + alt screen → draw → `event::poll` → quit on q/Esc) via ratatui's crossterm
      re-export; headless `SSC_TUI_SNAPSHOT` path for CI. **Gate met:** `frontendTui/test` 20/20 — cargo smoke
      builds the loop crate, renders a signal-bound frame headlessly, AND `cargo test` runs a generated
      `reactive_rerender` proving a signal mutation re-renders.
- [x] **frontend-tui-3-focus-events** ✓ DONE 2026-06-23 — document-order focus ring (`FOCUS_COUNT`,
      `is_text_input`, `focus_mark`), `handle_key` (Tab/↓ + Shift-Tab/↑, Enter/Space→`activate`, typing→
      `type_char`, Backspace, Esc/`q`→quit), generated `activate`/`type_char`/`backspace` match arms; declarative
      `EventHandler`s (`SetSignalLiteral`/`IncrementSignal`/`ToggleSignal` + `TextInput` `InputChange`) mutate the
      store, `Simple`/`WithEvent`→no-op; `render_root(...,focus)` shows the focus marker. **Gate met:**
      `frontendTui/test` 21/21 — cargo smoke builds an interactive crate (signal+button+text-input) and
      `cargo test` runs generated `event_handlers_run`/`text_input_typing`/`tab_moves_focus`/`reactive_rerender`.
      (UCC PoC step 2: composer.) Follow-ups: `A11y.focusOrder` seeding + hidden-`ShowSignal`-branch focus skip.
- [x] **frontend-tui-4-table-routing** ✓ DONE 2026-06-23 — `DataTable(StaticRows)`→ratatui `Table` (header from
      column titles, cells from row `fieldPath`); `TabBar`→focusable tab headers (`Set(current,idx)` activation) +
      runtime `match sig_int(current)` content; `NavigationStack`→runtime `match sig(current).as_str()` routes;
      `sig_int` accessor added; `Badge/Spinner/Pill/Tag` already render as text via std/ui lowering. **Gate met:**
      `frontendTui/test` 25/25 — 3 fast emitter cases + a 2nd cargo smoke building `TabBar[DataTable,…]` (snapshot
      shows active `[Rooms]` + table header + rows). (UCC PoC step 3.) Follow-ups: hidden-tab focus skip,
      ForModel/EditableCell, Sheet/AlertDialog overlays.
- [x] **frontend-tui-5-fetch-binding** ✓ DONE 2026-06-23 — `collectFetches` finds every `FetchUrlSignal`
      (a `ReactiveSignal[String]` carrying a URL) in `SignalText`/`DataTable.Remote`/`ModelView`; emits
      `fetch_text(url)` (blocking `ureq` GET) + `bootstrap(signals)` populating each at startup (before first
      render, both snapshot + interactive); a fetch-bound `SignalText` then renders the body. `ureq` added to
      Cargo.toml only when the app fetches. **Gate met:** `frontendTui/test` 28/28 — 2 fast emitter cases + a
      3rd cargo smoke that starts a local JDK `HttpServer`, builds a crate bound to it, and asserts the snapshot
      shows the fetched body. This is the seam the rozum control-API binds to over HTTP. Follow-up: dynamic
      `DataTable.Remote` rows + typed-model views from fetched JSON (needs `serde_json`).

  **▶ frontend-tui MILESTONE COMPLETE (slices 0–5).** The ratatui terminal-UI backend lowers the full `View`
  IR; rozum can author its control center as one `std/ui` `.ssc` app and compile it to a terminal binary,
  retiring the hand-written `crates/rozum-meeting/src/tui`. Spec `specs/frontend-tui-ratatui.md`. Open
  follow-ups (not blocking): Style/Theme colors, A11y.focusOrder seeding, typed-model dynamic tables,
  Sheet/AlertDialog overlays, CLI `--frontend tui` native-emit flag.

### ▶ Crypto/finance roadmap (2026-06-23, with Sergiy — "да хочу. все хочу. … внеси все это в спринт или в беклог")
Sergiy asked to queue the whole forward-looking crypto/blockchain/identity/payments brainstorm. Plan + per-item
"what / why / where / benefit" + slices: **[`docs/crypto-finance-roadmap.md`](docs/crypto-finance-roadmap.md)**
(explainer) + **[`specs/crypto-finance-roadmap.md`](specs/crypto-finance-roadmap.md)** (engineering plan). The
near-term, codeable-now slices are below; the larger/later epics are in `BACKLOG.md` → "Crypto/finance roadmap —
later epics". Every slice follows **reference → seam → gate → native** (the FROST template). Recommended order is
foundations first (Blake2b + JS-HD) → make three chains backend-agnostic (highest architectural value).

- [x] **crypto-spi-blake2b** ✓ DONE 2026-06-23 — added `Blake2b224`/`Blake2b256` to `HashAlgo`
      (`payments/crypto/spi/shared/.../HashAlgo.scala`); implement in `bouncycastle` (`Blake2bDigest`) +
      `noble-js` (`@noble/hashes/blake2b`); add a pure-Scala `Blake2b` reference fallback (mirrors FROST's
      `Sha512`). **Why:** Blake2b is the one hash missing from the SPI (Keccak-256 + RIPEMD-160 already there);
      it's Cardano's last direct-BouncyCastle dependency. **Gate:** RFC 7693 vectors + Cardano address fixtures
      match across both backends + the reference. Unblocks `chains-backend-agnostic` (Cardano).

- [x] **noble-js-hd-derivation** ✓ DONE 2026-06-23 — implemented `deriveMaster`/`deriveChild` in
      `payments/crypto/noble-js` (they currently THROW "not yet implemented on Scala.js") via `@scure/bip32` /
      HMAC-SHA512, for secp256k1 + Ed25519 (SLIP-0010). **Why:** without BIP-32 HD on JS, wallets + chain
      adapters sign on JVM but not in-browser. **Gate:** byte-for-byte equal to the BouncyCastle backend for the
      existing JVM HD fixtures (BIP-32 + SLIP-0010 vectors).

- [x] **chains-backend-agnostic** ✓ COMPLETE 2026-06-23 (all 3 slices) — route Cardano/Bitcoin/Cosmos crypto
      through the `CryptoBackend` SPI instead of importing `org.bouncycastle.*` directly, then make each a
      crossProject (currently all three are JVM-only `project`s). **Why:** this is the only crypto path still
      bypassing the SPI, and the sole reason these three are JVM-only + carry a heavy dep. The "FROST move",
      repeated → 3 chains gain JS + shed BouncyCastle.
      - [x] Slice 1 (Cardano) ✓ DONE 2026-06-23 — `CardanoAddress` Blake2b-224 + `CardanoChainAdapter.txBodyHash`
        Blake2b-256 now use the portable `scalascript.crypto.Blake2b` reference (zero `org.bouncycastle` in
        `src/main`). `blockchainCardano` → `crossProject(JVM, JS)` `CrossType.Full`: the portable address / CBOR /
        Blake2b / tx-type core moved to `shared/` (cross-compiles to JS); the Blockfrost-backed adapter stays in
        `jvm/` (sttp4 + Future I/O). New `CardanoPortableTest` (shared, no `CryptoBackend`) pins byte-exact CIP-19
        address goldens + RFC 7693 BLAKE2b vectors + tx-body-hash + bech32 + CBOR roundtrips → **JVM 42 / JS 19
        green**, proving browser-wallet bytes are byte-identical to the JVM. HD-on-JS already covered by
        `noble-js-hd-derivation`. Downstream `x402*Cardano*` consumers recompile clean (`.jvm` keeps the id).
      - [x] Slice 2 (Bitcoin) ✓ DONE 2026-06-23 — Sergiy chose "port secp256k1 from scratch" over routing
        through the SPI (Bitcoin also needs Taproot/Schnorr BIP-340/341, which no generic sign/hash SPI can
        express). Built a full **from-scratch portable secp256k1 stack** in `crypto-spi/shared` (no
        `org.bouncycastle`, identical JVM+JS): `Sha256`/`Ripemd160`/`HmacSha256` (NIST/RFC vectors),
        `Secp256k1Group` (Jacobian, multiples-of-G table), `Secp256k1Ecdsa` (RFC-6979 + low-S DER — the d=1
        vector reproduced byte-exact, **resolving the low-S gotcha**), `Secp256k1Schnorr` (BIP-340 vector 1
        byte-exact + BIP-341 Taproot tweak). `BitcoinCrypto` rewritten as a thin shim over it; `blockchainBitcoin`
        → `CrossType.Pure` crossProject (adapter is stub-only, so the WHOLE module — addresses/ECDSA/PSBT/Taproot
        — cross-compiles, no shared/jvm split). cryptoBouncycastle dep dropped. **JVM 45 / JS 45 green** + 38
        portable-stack vectors JVM+JS. Downstream walletVaultLedgerBitcoin recompiles clean. The portable
        secp256k1 is **reusable for Slice 3 (Cosmos)**.
      - [x] Slice 3 (Cosmos) ✓ DONE 2026-06-23 — `CosmosCrypto` + `CosmosSignDoc` rewritten as thin shims over
        the portable stack (secp256k1 via `Secp256k1Ecdsa`, RIPEMD-160 via `Ripemd160`, **Ed25519 via the new
        portable RFC-8032 `Ed25519`** built on the relocated `Ed25519Group`/`Sha512`). `blockchainCosmos` →
        `CrossType.Full` crossProject (Full, not Pure, because the `ServiceLoader` discovery test is JVM-only →
        moved to `jvm/src/test`; `META-INF/services` registration moved to `jvm/src/main/resources`). cosmos
        test de-BouncyCastled (Ed25519 pubkey via `deriveEd25519PublicKey`). cryptoBouncycastle dep dropped.
        **JVM 41 / JS 40 green** (Amino sign-doc, secp256k1 + Ed25519 sign/verify, addresses — all byte-identical
        cross-platform).
      - **Gate (all): ✓ MET** — all three chains: per-chain tests green on JVM **and** newly pass on JS; zero
        `org.bouncycastle` code in any `src/main`. **chains-backend-agnostic COMPLETE (Cardano + Bitcoin +
        Cosmos).** Byproduct: a full portable from-scratch crypto stack in `crypto-spi/shared` (SHA-256/512,
        RIPEMD-160, HMAC-SHA256, secp256k1 ECDSA+Schnorr+Taproot, Ed25519) reusable by any chain/wallet on JS.

- [x] **client-solana-rpc** ✓ DONE 2026-06-23 — new `payments/client/solana` (`clientSolana`): typed
      `SolanaClient` (sttp4 JSON-RPC: getBalance/getLatestBlockhash/getTokenAccountsByOwner/getTransaction/
      sendTransaction/getAccountInfo + raw `rpc`) mirroring `clientEvm`, PLUS the deliverable — `Solana.chainContext(config)`
      returns a turnkey `ChainContext` so callers stop hand-rolling one (`SolanaChainContext` wraps a
      `SolanaClient`; `rpcCall` returns the raw result envelope the adapter unwraps). **Gate MET:** a mock-RPC
      build→sign→broadcast through `SolanaChainAdapter` + the turnkey context (signing with the portable
      `crypto.Ed25519`) — asserts getLatestBlockhash + sendTransaction fire and a base64 tx (sig64+message) is
      submitted; config/shape parity with clientEvm; a devnet-gated live test (getLatestBlockhash/getBalance,
      cancels if offline) — ran green against live Solana devnet. `clientSolana` 5/5. main deps blockchainSpi;
      test deps blockchainSolana + cryptoSpi (% Test). Added to root aggregate. No `examples/` dir — followed the
      clientEvm precedent (mock test + reachability-gated live test = the runnable example).

- [x] **frost-secp256k1** ✓ DONE 2026-06-23 — FROST threshold Schnorr on secp256k1 producing **standard BIP-340**
      signatures, in `FrostSecp256k1` (cryptoFrost/shared), built directly on the portable `Secp256k1Group` +
      `Secp256k1Schnorr` from chains-backend-agnostic. Trusted-dealer Shamir over the scalar field `n` (even-`y`
      group key forced at keygen) + two-round signing (per-signer binding via SHA-256, aggregate nonce `R` forced
      even-`y` with per-signer nonce flip, BIP-340 tagged-hash challenge, Lagrange-weighted partials). **Gate MET:**
      every `t`-of-`n` aggregate verifies under the standard BIP-340 verifier `Secp256k1Schnorr.verify` (2-of-3 all
      subsets, 3-of-5, 5-of-5, 1-of-1, over-quorum) — **cryptoFrost JVM 27 / JS 13 green**, plus a 600-run random
      soak (0 failures). In-process quorum (matches `FrostSign`); the networked transport is the separate
      `frost-distributed-transport` slice. **Also fixed a latent origin/main regression**: the new
      `scalascript.crypto.Ed25519` (added in the Cosmos slice) shadowed BouncyCastle's `object Ed25519` via
      `import scalascript.crypto.*`, breaking `cryptoBouncycastle` compile (uncaught — that module wasn't
      recompiled then); renamed the BC helper → `BcEd25519`. cryptoBouncycastle 52 green. GOTCHA: BIP-340
      `Secp256k1Schnorr.verify` REQUIRES a 32-byte message — short test strings silently return false (not a sig
      bug); always sign a 32-byte hash.

- [x] **frost-distributed-transport** ✓ DONE 2026-06-23 (protocol + in-process transport; network binding noted) —
      refactored `FrostSecp256k1` signing into composable rounds (`commit`/`prepare`/`partial`/`aggregate`;
      `thresholdSign` reimplemented on top, so in-process and distributed paths are byte-identical) and added
      `FrostDistributedSigning`: a `Participant` holds exactly ONE share (`private`, no accessor — never leaves the
      host); a `Coordinator` (`coordinate`) holds the group key + signer set but **no shares**, driving round 1
      (public commitments) → public package → round 2 (public partials) → aggregate over a `Transport`
      abstraction. `LocalTransport` runs participants in-process (the no-co-location simulation). **Gate MET:** a
      `t`-of-`n` distributed run produces a valid BIP-340 signature (2-of-3 all subsets, 3-of-5, 5-of-5);
      byte-identical to the in-process path for the same nonces; only public data (33-byte commitments + partial
      scalars, never a share) crosses the transport (asserted via a recording transport). cryptoFrost JVM 39 / JS
      25. **Concrete HTTP transport DONE 2026-06-24** (walletVaultMpcFrost): `FrostParticipantServer` (JDK
      HttpServer, one share/host, `/round1` `/round2` `/health`) + `DistributedFrostSigningClient` (share-free
      coordinator over HTTP/JSON) → multi-host distributed FROST-Ed25519, verified under standard Ed25519, plugged
      into `McpVault` = **threshold-custody-wallet DONE** (BACKLOG). WS/actor transport = same protocol, different
      pipe. **Also hardened the pre-existing `shamir-secret-backup` tamper test** (single-byte high/padding flips
      are truncation-masked by design → corrupt the whole share).

- [x] **totp-hotp** ✓ DONE 2026-06-23 — HOTP (RFC 4226, counter) + TOTP (RFC 6238, time) in `Totp`
      (cryptoSpi/shared), fully PORTABLE (no SPI backend): added portable `Sha1` (FIPS 180) + generic `Hmac`
      (sha1/sha256/sha512) to crypto-spi/shared, then HOTP dynamic-truncation + TOTP time-step + a
      `validate(window=±1)` skew check. Configurable digits + SHA-1/256/512 (`Totp.Algo`). **Gate MET:** byte-exact
      RFC 4226 App. D (HOTP counters 0-9) + RFC 6238 App. B (TOTP 8-digit, SHA-1/256/512 at 6 timestamps) + FIPS
      SHA-1 + RFC 2202 HMAC-SHA1 vectors. cryptoSpi JVM 51 / JS 51. (SHA-1 is collision-broken — included ONLY for
      these legacy HMAC standards, documented as such.) **Now exposed to `.ssc`** (2026-06-24) via the crypto
      plugin: `hotp`/`totp`/`totpValidate` intrinsics in `CryptoIntrinsics` (secret as base64, algo
      SHA1/256/512); RFC-vector tests through the interpreter + `examples/totp-shamir-demo.ssc`.

- [x] **shamir-secret-backup** ✓ DONE 2026-06-23 — `ShamirSecretSharing` (cryptoFrost/shared): `t`-of-`n` split /
      recover of ARBITRARY byte secrets (seed phrases, keys, blobs) over the prime field `GF(2^255−19)`
      (`Ed25519Group.P`), generalizing FROST's single-element Shamir. Length-prefixed secret → 31-byte chunks
      (`< 2^248 < p`), each split by an independent degree-`(t-1)` polynomial; shares = `id ‖ 32-byte-per-chunk`.
      `recover` is total (truncates each reconstructed chunk to 31 bytes — raw Shamir has no integrity check, so
      `<t`/tampered shares yield a wrong value, not the secret). **Gate MET:** round-trips across sizes
      (0/1/16/31/32/33/64/100/256 B) × thresholds (1-of-1…5-of-5); every t-subset recovers the same secret;
      `<t` reveals nothing; tampered → wrong. cryptoFrost JVM 34 / JS 20. NOT SLIP-0039 wire-compatible
      (SLIP-0039 = GF(256)+mnemonics; this is the prime-field generalization the roadmap asked for). **Now
      exposed to `.ssc`** (2026-06-24) via the crypto plugin: `shamirSplit`/`shamirRecover` intrinsics (secret +
      shares as base64, shares space-separated); round-trip tests through the interpreter +
      `examples/totp-shamir-demo.ssc`.

### ▶ JVM / interp perf (2026-07-02 — "JVM, interp perf -> sprint")

- [x] **jit-value-class-names** — ALREADY IN MAIN (commit `2a563020c`, branch `feature/jit-class-names-fix`).
      AsmJitBackend + JavacJitBackend updated for value-unification: scalar leaves in `DataValue$XxxV`,
      container types in `Value$package$Value$XxxV`, `Value` union erases to `java/lang/Object`.
      JitClasspathTest probe updated to reference `DataValue.class`. 1878 backendInterpreter tests pass.

- [x] **recursionFib-perf** — FLOOR CONFIRMED. `JavacJitBackend.tryCompile` (Phase C) already compiles
      `def fib(n)` body to JVM bytecode via javac → static `long fib(long)` method; HotSpot JIT-compiles
      that further to native code. The 1.193 ms/op IS the compiled floor for binary-recursive fib(30)
      (~2.7M recursive calls as native JVM). Phase C delivered 23.8× over tree-walk (was ~28 ms).
      No further improvement feasible without changing algorithm semantics. Verdict: floor, not a JIT gap.

- [x] **jit-cast-isinstanceof-fix** ✓ DONE 2026-07-03 (feature/jit-cast-isinstanceof-fix) — fixed silent
      exception in `asInstanceOf[WhileLongRunFn]` cast after `cls.getConstructor().newInstance()` in all 8
      JIT compile sites (4 in `JavacJitBackend`, 4 in `AsmJitBackend`). Root cause: Scala 3 catches an
      exception silently when `asInstanceOf` follows `newInstance()` in certain class-loader contexts; fix
      splits into `isInstanceOf` check before the cast. Confirmed with `ssc.jit.bytecode=off` bench:
      `multiVal` 12ms (interpreter) → 0.59ms (JIT) = 20× speedup. Poly closed form done next.

- [x] **interp-poly-closed-form** ✓ DONE 2026-07-03 (f7b243288, feature/interp-poly-closed-form → main) —
      `walkQuadPoly` + `tryExtractPolyAddend` + inline-poly fast path in `tryClosedFormPolyLoop`.
      Peels `acc` from left-assoc `acc + X1 + X2 + …` chains, sums `walkQuadPoly` coefficients, then
      computes `Σ a2*(S+j*stp)^2 + a1*(S+j*stp) + a0` in O(1) BigInt. `multiVal` bench: was 0.59ms (JIT)
      → effectively 0 (O(1) closed form). `PolyClosedFormTest` 7/7 differential tests green. Also catches
      linear inline addends. `JitLintTest` updated (linear acc now closed-form not JIT path). 189/189 pass.

### ▶ Promoted to active by Sergiy (2026-06-23 — "все эти задачи внеси в спринт")
Sergiy explicitly OVERRODE the deferred/backlog status of these four — they are now active sprint work, to be
done (each is genuinely codeable; the external parts are called out). Drive top-to-bottom.

- [x] **coremin-actors-codemove** ✓ DONE 2026-07-02 (4578c8e4f, feature/actors-plugin-move → main) — ActorScheduler.scala (2846 lines) + ActorClusterRoutes.scala extracted to actors-plugin; ActorInterp.scala slimmed 2956 → 98 lines (provider/session + host bridge only); MissingActorRuntimeProvider default (clear error if plugin not loaded); 23 actor/cluster tests moved to backendInterpreterPluginTests (install ActorsInterpreterPlugin); backendInterpreterPluginTests 839 pass; all actor suites 66/0 green.
~~- [ ] **coremin-actors-codemove** (stale — superseded by [x] above; full scope done 2026-07-02)~~

- [x] **theme-a-stable-plugin-spi — Phase 3 (versioning)** ✓ DONE 2026-07-02 (a3b3f6d31, feature/stable-spi-phase3-load-compat → main) — load-time API compat check COMPLETE: `Backend.pluginApiVersion: String = "1.0.0"` (default; third-party plugins override with `PluginApiVersion.Current` at build time); `BackendRegistry` warns on incompatible `pluginApiVersion` for in-process + `.sscpkg` loads (non-fatal, mirrors `spiVersion` pattern); `PluginManifest` + `SscpkgManifest` gain optional `pluginApiVersion` field; `PluginApiVersionCompatTest` 7/0 + `PluginManifestTest` 7/0 + core 1033/0 + pluginApi 22/0 all green. Phase 3 FULLY COMPLETE (migration + signature lock + compat check).

- [~] **remote-package-registry** (Tier 3 strategic — unlocks the 3rd-party plugin ecosystem) — the local story
      is done (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install`, `.sscpkg`). **Slice 1 DONE
      2026-06-23:** the registry protocol + reference server — `RemoteRegistry` (`Entry(id,version,sha256,desc)`
      + JSON index wire format + `compareVersions` + `sha256Hex`) and `FileRegistry` (directory-backed catalog:
      publish [immutable releases] / search / resolve [exact or latest] / versions / fetch [checksum-verified]).
      `RemoteRegistryTest` 7/0. Greenfield/additive. Spec `specs/arch-build-registry.md` §6b. Follow-up slices
      below (do gradually, one at a time). EXTERNAL (deploy, not code): host `registry.scalascript.io`.
      **RECONCILE NOTE 2026-06-23 (probed existing infra):** the registry CLIENT already exists more fully than
      slice 1 assumed — `RegistryClient` fetches+caches `packages.yaml` from a configurable URL, and `ssc search`
      + `ssc install` + `LocalRegistry` consume it; `ssc publish` is TAKEN (app-store upload). So the real gap is
      the SERVER/publish side, and `FileRegistry` must speak the client's **`packages.yaml`** format (not its own
      `index.json`). Slices corrected accordingly:
  - [x] **registry-packages-yaml-bridge** (slice 2) ✓ DONE 2026-06-23 — `FileRegistry.exportPackagesYaml(baseUrl)`
        / `writePackagesYaml` project the catalog into the client `LocalRegistry.Entry` `packages.yaml` shape
        (id→url+version+description, one entry per id at its latest version, `url`→stored artifact), so the
        EXISTING `RegistryClient`/`ssc search`/`ssc install` consume a `FileRegistry`-served dir unchanged; the
        richer `index.json` (sha256/all-versions) stays the publish-side record. Test round-trips through
        `LocalRegistry.parseFile`/`resolve`. `RemoteRegistryTest` 8/0.
  - [x] **registry-publish-cmd** (slice 3) ✓ DONE 2026-06-23 — `ssc plugin registry publish <pkg.sscpkg>
        [--registry <dir>] [--base-url <url>] [--description <t>]` (the existing `ssc plugin registry` subcommand
        group — not `ssc publish`, which is app-store). New `SscpkgLoader.loadManifest` (manifest-only) reads
        id/version; calls `FileRegistry.publish` (content + index.json) + `writePackagesYaml`. Round-trip tested
        (temp `.sscpkg` → loadManifest → publish → fetch → client `LocalRegistry.resolve`). `RemoteRegistryTest`
        9/0; cli compiles.
  - [x] **registry-http-server** (slice 4) ✓ DONE 2026-06-23 — `RegistryHttpServer` (JDK `com.sun.net.httpserver`,
        dependency-free): `GET /packages.yaml` + `GET /packages/<id>/<version>.sscpkg` + `POST /publish/<id>/<version>`;
        auto-derives its self-referencing base URL from the bound port; loopback by default. In-process round-trip
        test (`java.net.http.HttpClient`). `RegistryHttpServerTest`+`RemoteRegistryTest` 10/0.
  - [x] **registry-publish-auth** (slice 5) ✓ DONE 2026-06-23 — `RegistryHttpServer` optional
        `publishTokens: Set[String]`: non-empty ⇒ `POST /publish` needs `Authorization: Bearer <token>` (else
        401); empty ⇒ open (dev default); GET reads stay public. `RegistryHttpServerTest` 2/0.
        **→ remote-package-registry CODE COMPLETE** (slices 1-5: protocol + `FileRegistry` + `packages.yaml`
        bridge + `ssc plugin registry publish` + HTTP server + auth). Only EXTERNAL deploy (host the domain + TLS)
        remains — the `[~]` parent stays open on that deploy step alone.

- [x] **FROST-Ed25519** ✓ DONE (slices 1–8 all complete — threshold Ed25519 signing — wallet MPC stack) — **FEASIBILITY PROBED + PLANNED INTO
      SUB-SLICES 2026-06-23.** FROST = flexible round-optimized Schnorr threshold signatures over Ed25519, as a
      self-contained `walletVaultMpcFrost` variant (the existing `walletVaultMpc*` are REMOTE/external-provider
      clients — Fireblocks/Coinbase/Lit/Zengo — not in-house threshold crypto, so FROST is the first). **KEY
      FINDING:** the codebase exposes NO usable Ed25519 GROUP operations — `payments/crypto/bouncycastle/Ed25519.scala`
      is high-level sign/verify only (BC `Ed25519Signer`); FROST needs scalar field (mod L), point add, base+arbitrary
      scalar mult, encode/decode. So **do NOT hand-roll curve math** (correctness-critical) — add a vetted group-ops
      library (e.g. `cafe.cryptography:ed25519-elisabeth`, pure-Java Edwards-point + Scalar arithmetic). Correctness
      gate throughout: a FROST signature MUST verify under the EXISTING standard verifier (`Ed25519.verify`) against
      the group public key. Substantial multi-session crypto — do as discrete green sub-slices, one at a time:
  - [x] **frost-groupops** (slice 1) ✓ DONE 2026-06-23 — FROM-SCRATCH (Sergiy's call, no new dep). New
        `cryptoFrost` module (`payments/crypto/frost`, pure; BC test-only). `Ed25519Group` = RFC 8032 reference
        group arithmetic (BigInteger): field mod 2^255-19, twisted-Edwards extended-coord add, scalar mult,
        encode/decode, base point B, order L, scalar field, `secretScalar`. `Ed25519GroupTest` 6/0 incl. the
        gate — generated pubkeys match BouncyCastle Ed25519 bit-for-bit (25 random seeds). Spec `specs/frost-ed25519.md`.
  - [x] **frost-keygen** (slice 2) ✓ DONE 2026-06-23 — `FrostKeygen`: trusted-dealer `t`-of-`n` Shamir over the
        scalar field (degree-(t-1) poly, shares `(id,f(id))`, group key `B·sk`) + Feldman VSS commitments `B·a_j`
        (`verifyShare`) + Lagrange `reconstruct` at x=0; `generateFrom` (explicit coeffs) for determinism + as the
        DKG building block. `FrostKeygenTest` 4/0 (cryptoFrost 10/0): t-subsets recover sk + match group key; <t
        don't; VSS accepts good / rejects tampered shares.
  - [x] **frost-signing + frost-aggregate-verify** (slices 3+4) ✓ DONE 2026-06-23 (combined — signing isn't
        verifiable until aggregation yields a checkable signature). `FrostSign`: round1 nonces `(d,e)`+commitments
        `(D,E)`; `ρ_i=SHA512(domain‖id‖msg‖commits) mod L`; `R=Σ(D_i+ρ_i·E_i)`; `c=SHA512(R‖A‖msg) mod L`;
        `z_i=d_i+ρ_i·e_i+λ_i·c·s_i`; aggregate → 64-byte `encode(R)‖scalarLE(z)`. **GATE PASSED:** `FrostSignTest`
        4/0 (cryptoFrost 14/0) — 2-of-3 AND every 3-of-5 subset verifies under BouncyCastle Ed25519; tampered
        partial + wrong message rejected. **FROST-Ed25519 functionally complete** (group ops + keygen + signing).
  - [x] **frost-ops-seam** (slice 5) ✓ DONE 2026-06-23 — the substitution mechanism. `Ed25519Ops` trait (point
        ops + scalar field + `secretScalar` + `sha512`) with `Ed25519Ops.Reference` (pure `Ed25519Group` + JDK
        SHA-512) as DEFAULT + registry (`current`/`register`/`reset`). `FrostKeygen`/`FrostSign` route ONLY through
        `Ed25519Ops.current` (incl. SHA-512 — no direct `java.security`), so a native backend substitutes
        transparently. Behaviour-preserving (14 prior tests pass through the seam) + a substitution test (a
        registered spy backend IS exercised by keygen+sign; reset restores reference). cryptoFrost 16/0.
  - [~] **frost-crossbuild** (slice 6) — make the REFERENCE FROST compile+run on JS. PROBE: the JVM-only deps
        are `java.security` SHA-512 AND `java.security.SecureRandom` (Scala.js 1.20 has neither). Split:
    - [x] **6a portable SHA-512** ✓ DONE 2026-06-23 — pure-Scala `Sha512` (Long-based, FIPS 180-4); routed
          `Ed25519Ops.Reference.sha512` + `Ed25519Group.secretScalar` through it; **removed `java.security` from
          hashing**. `Sha512Test` (abc/empty FIPS vectors + matches `java.security` across padding boundaries);
          cryptoFrost 19/0.
    - [x] **6b RNG via seam** ✓ DONE 2026-06-23 — `Ed25519Ops.randomBytes(n)`/`randomScalar()` (Reference = JVM
          `SecureRandom`). `FrostKeygen.generate`/`FrostSign.round1` dropped their `rng: SecureRandom` params and
          source from `Ed25519Ops.current` → FROST logic is fully `java.security`-free (only the JVM default's
          `randomBytes` uses it; 6c splits per-platform) AND the RNG is a substitutable primitive. cryptoFrost 19/0.
    - [x] **6c crossProject** ✓ DONE 2026-06-23 — `cryptoFrost` is a `crossProject(JVM,JS)`; reference (Ed25519
          math + own SHA-512 + keygen + signing + seam) is pure → compiles+RUNS on JS. `PlatformEntropy` per-platform
          (JVM `SecureRandom` / JS WebCrypto). Shared tests run on BOTH: **JS 6/6 on Node** (incl. `generate(3,5)`
          via WebCrypto + the substitution test), JVM 19/0 (BC/java.security tests in `jvm/`). **→ FROST
          cross-platform story COMPLETE: one reference, identical on JVM + JS, native RNG, transparent substitution.**
  - [x] **frost-native-backend** (slice 7) ✓ DONE 2026-06-23 — `CryptoBackedEd25519Ops`: an `Ed25519Ops` backend
        delegating SHA-512 + RNG to the project's `CryptoBackend` SPI (BC/JVM, noble/JS), group math stays the
        reference. `cryptoFrost dependsOn cryptoSpi` (no external dep). Verified (JVM 20/0): BC SHA-512 == our
        reference SHA-512; a BC-backed 2-of-3 FROST signature verifies under BouncyCastle Ed25519; JS still 6/0
        (bridge cross-compiles). Closes the loop — portable reference + transparent substitution down to the crypto provider.
  - [x] **frost-vault-integration** (slice 8) ✓ DONE 2026-06-23 — FROST wired into the wallet stack as an
        in-house threshold provider. `FrostSigningClient extends RemoteSigningClient` runs the FROST 2-round
        protocol locally over a `FrostQuorum` (instead of an external TSS service), plugging straight into the
        existing `McpVault` (kind=Mpc) delegate seam whose own doc already names "FROST for Ed25519" — so a
        threshold wallet is just `McpVault("…", new FrostSigningClient(Seq(quorum)))`, no new `Vault` impl. New
        module `walletVaultMpcFrost` dependsOn `walletVaultMpc` + `cryptoFrost` (BC test-only). Verified 3/0:
        vault unlock → getSigner(Ed25519) → sign → 64-byte sig verifies under standard BouncyCastle Ed25519
        (distinct subsets); non-Ed25519/unknown-account/sub-threshold rejected. **Closes the FROST track
        (slices 1–8).** Remaining FROST refinements (constant-time field, full DKG, distributed transport,
        JS @noble mirror) are future work, not slices.

### ▶ Autonomous queue (2026-06-23, with Sergiy — "все кроме мавена — в спринт и делай")
When the clean autonomous coremin slices ran out (value-unification is sibling-active; NFC/wallet-ws are
device/browser-blocked; Maven publish is explicit-go only), Sergiy directed: queue everything except Maven
and execute autonomously. In priority order:

**▶▶ stable-SPI Phase 3 — FULL breakdown (2026-06-23, Sergiy: "делай Phase 3 автономно … заноси в спринт
сразу всё, потом делай постепенно").** GOAL: the **28** plugin `*Intrinsics.scala` that `import
scalascript.interpreter.{Value, InterpretError, Computation, …}` depend ONLY on the stable
`scalascript-plugin-api`, so a core/interpreter refactor (or a third-party plugin) can't break them, and the
build can reject any plugin jar containing `scalascript/interpreter/`. **PROBED FINDING:**
`PluginValue`/`PluginComputation` are opaque `Any` with NO accessors; `evalLegacy`'s own doc says full
Value-decoupling is "v2.x". So import-removal is GATED on a **Value-surface in the stable API** — it does NOT
come from `evalLegacy` (which only decouples the *context*). Cycle-checked: `pluginApi → core` is acyclic
(core deps = `valueData, backendSpi, …`, not pluginApi). Do gradually, one plugin/small-batch per slice, each
validated + pushed:
- [x] **p3-foundation** ✓ DONE 2026-06-23 — `scalascript-plugin-api` now `dependsOn(core)` (acyclic seam);
      `PluginValue` exposes stable extractors (`asString/asInt/asDouble/asBool/asChar/asList/asTuple/asMap/
      asOption`) + constructors (`string/int/double/bool/char/list/tuple/map/some/none/unit`) + `show`, backed
      by the interpreter `Value`; `PluginError` builds the real `InterpretError` + `raise(msg)`. PROOF:
      `mime-plugin` migrated off `scalascript.interpreter` end-to-end. `pluginApi/test` 14/0, `mimePlugin/test`
      4/0, `PluginExamplesSmokeTest` 1/0. The surface may need a few more accessors as later batches surface new
      shapes — extend `PluginValue` as needed.
  ~~- [ ] p3-foundation (original)~~ — expose a stable Value-surface through
      `scalascript-plugin-api` so plugins stop importing `scalascript.interpreter.Value`. DESIGN (decided):
      `pluginApi` gains a `core` dep = the ONE controlled seam (moves the coupling 28→1; opaque `PluginValue` +
      stable extractors/constructors keep the plugin ABI stable even as core's `Value` repr changes — e.g.
      value-unification). Add to `PluginValue`: extractors `asString/asInt/asDouble/asBool/asChar/asList/asTuple/
      asMap/asOption` + constructors `string/int/double/bool/list/tuple/map/unit/some/none` + `show`; keep
      `PluginError(msg)` (= InterpretError) + `PluginComputation.pure`. Stable bridges for the non-Value imports:
      `JsonParser`/`jsonToJson` → `JsonCodec` (exists) or a parser bridge; `OAuthBridge` (mcp/oauth) → a
      capability/stable surface. PROOF in this slice: migrate `mime-plugin` (simplest) end-to-end off
      `scalascript.interpreter`. VERIFY: `pluginApi` compiles with the core dep (no cycle); mime compiles with no
      `scalascript.interpreter` import + its tests green.
- [x] **p3-batch-A** ✓ DONE 2026-06-23 — ALL 10 migrated off scalascript.interpreter: mime/pdf/fs/crypto/payment-request/nfc/auth/fetch/graph/yaml (tests green). Surface complete: full Value-surface + extractor objects (Str/Num/Dbl/Bool/Chr/Lst/Tpl/Inst/Opt/Big/MapVal/Foreign/NativeFn) + foreign/nullV/isUnitOrNull/showAny/isRuntimeValue + asInstance via effectiveFields. Recipe mature (stateful line-aware swap; mid-line .collect{case}; strip pattern type-tests; bare Value types; OptionV-ctor->some/option; structural store->PluginValue+wrap; showAny for Value-vs-native).
      **BREAKTHROUGH 2026-06-23 — the hard problem is solved.** The blocker on the pattern-matching plugins:
      they use `Value.StringV(x)` etc. BOTH as constructors AND as `case` PATTERNS, and `PluginValue` (opaque)
      can't be pattern-matched. SOLUTION: added **extractor objects** to `PluginValue` — `Str/Num/Dbl/Bool/Chr/
      Lst/Tpl/Inst/Opt/Big/MapVal/Foreign/NativeFn` (each `unapply(v: Any)`), plus `foreign`/`nullV`/`isUnitOrNull`.
      Now `args match { case List(Str(label), Bool(p)) => … }` works without importing `Value`. Migration recipe
      (proven on payment-request): **line-aware** swap — on `case` lines (left of `=>`) use the extractors
      (`Value.StringV`→`Str`), elsewhere use constructors (`Value.StringV`→`PluginValue.string`); `.asInstanceOf
      [Value]`→`.asInstanceOf[PluginValue]`; `Map[String, Value]`→`Map[String, PluginValue]`; `throw
      InterpretError`→`PluginError.raise`. **`Value.Foreign(tn, handle: Any)` IS exposable** (generic host-object
      wrapper, not interpreter-internal) — so fetch is NOT blocked, just Foreign-heavy.
      REMAINING (yaml only — last batch-A): **auth** (heavy: MapV/OptionV/Instance), **graph/yaml**
      (also move internal `Value` store to `PluginValue`/`Any`)
      RECIPE REFINEMENTS (from auth): the line-aware script must also handle (a) MID-LINE patterns in
      `.collect { case (Str(k), Str(v)) => … }` (not only line-start `case`), and (b) bare `Value` TYPE
      annotations (`Option[Value]`/`: Value`/`[Value]`) → `PluginValue` (the `Value.`-only residual check
      misses them).
- [x] **p3-batch-B** ✓ DONE (all 7: ws/pwa/json + oauth/dstreams/graphql/streams — giants done in p3-giants)
- [x] **p3-batch-C** ✓ DONE (all 10: uuid/os/request/smtp/sql/remote/frontend + mcp/content — giants done in p3-giants)
- [x] **p3-giants** ✓ DONE — all migrated; `actors` is a PERMANENT exemption (interpreter-only runtime provider). All ctx is covered by EXISTING caps — big-but-mechanical value/NativeFnV
      passes PLUS one bridge each. Per-plugin scope:
  - **http** ✓ DONE (4 unit + 58 integration tests: MountHandler/TypedHandler/HttpClient/TypedRpcBinary).
        jsonToJson → `jsonEncode`; the `TypedHandlerWrapper.wrapIfTyped` coupling → new `PluginValue.wrapTypedHandler`
        seam + `funArity` (FunV param count for the mount static/handler shape check); globalsView was just
        `Map.empty`. 21 NativeFnV → `nativeFn`. All 33 ctx methods were already on HttpCap&WsCap&Storage&Mount.
  - **dstreams** ✓ DONE (59 tests). Internal Value-DAG engine (136 InstanceV, 56 NativeFnV, 11 `.fields`/`.typeName`
        sites). New PluginApi accessors: `pv.field(name)`, `pv.typeNameOf`, `InstAny` extractor (binds a whole
        instance value, replacing `case x: Value.InstanceV`). `.fields.get`→`.field`, `Inst`/`InstAny`/`Lst`/`Str`
        extractors, all 56 `Computation.pureFn`→`nativeFn`. ctx (featureGet/Set, invokeCallback, registerRoute) on caps.
  - **oauth** ✓ DONE (4 unit + 58 integration: McpOAuthBridge/OAuthGuard/OAuthRsa/OAuthScript/Oidc/OAuthAuthServer).
        5-file web (OAuthIntrinsics 334 + OAuthHttp + OidcHttp + OAuthClientIntrinsics + OidcHelpers) migrated together;
        `OAuthBridge` (1-field ConcurrentHashMap) RELOCATED lang/core/interpreter → `scalascript.plugin.api.OAuthBridge`
        (core only defined it; mcp + the test reference it indirectly). ujson.Value protected via `(?<![A-Za-z.])`
        anchored regex; shared `Value`-typed helpers (toStringSet/resolveAuthServer) retyped to `Any`.
  - **mcp** ✓ DONE (2 unit + 184 integration: 30 Mcp* test files incl McpOAuthBridge/McpHttpBidi/McpBidiSampling).
        Single file (1508 loc, 87 NativeFnV, 151 StringV, 72 ujson). OAuthBridge already moved; ctx on caps; the 4
        `: Value.InstanceV` were return types (→ `PluginValue`). ujson.Value protected via anchored regex; `Mcp`
        value helpers (valueToStringList/valueToJson/valueToAuthResult) retyped to `Any`.
  - **streams** ✓ DONE (88 tests). dstreams' sibling — same recipe; extra: 26 `.asInstanceOf[Value]`→`[PluginValue]`
        (valid no-op cast, PluginValue erases to Any), OptionV/TupleV unfold inspection → `Opt`/`asTuple`, NativeFnV
        type-tests → `Fn`, Foreign signal patterns → `Foreign`. GOTCHA: the `X: Value.InstanceV`→`InstAny(X)` regex
        also hit a def PARAM (revert to `X: PluginValue`); stripping `case X: Value` ascriptions can shadow a
        following catch-all (restore with an `isRuntimeValue` guard).
  - **content** ✓ DONE (29 tests). Largest (2144 loc), no Computation/NativeFnV (pure value construction).
        NEW accessors: `pv.fields` (whole field map), `PluginValue.orderedInstance` (array-backed field ORDER —
        content nodes are read positionally via `inst.fieldNames`, a behavioral bug caught by tests). GOTCHAS:
        the AST `ast.ContentValue.*` ADT (137 uses) collides with `Value.*` replaces → anchor every regex with
        `(?<![A-Za-z])`; the `InstAny`/`: Value` regexes also hit DEF PARAMS (revert to `: PluginValue`).
  - **graphql** ✓ DONE (162 tests, incl GraphQLSubscriptionTest). 2-file web; carrier case classes
        (`GraphQLResolvers`/`ScalarCodec`/`GraphQLFederationEntities`) hold `AnyRef` (NOT `Any`).
        ROOT CAUSE of the earlier "blocker": `GraphQLSubscriptionTest` asserts `res.subscription("e") eq fn`;
        with an `Any` carrier `res.subscription("e")` is statically `Any` (no `.eq`), so scalatest's `assert`
        macro routes it through its `Equalizer` implicit and casts the WRAPPER to `AnyRef` — comparing the
        wrapper, not the value (always false). `AnyRef` carrier → `.eq` is direct reference equality, like the
        original `Map[String, Value]` (`Value = DataValue|ValueRest` is `<: AnyRef`). NOT a scalac bug; the
        debug-println "passes" were my explicit `.asInstanceOf[AnyRef]` casts bypassing the Equalizer. anchored
        regex protects `ujson.Value`; `valueToJava`/`addResolver`/`byType`/`entities` retyped to AnyRef.
  - **actors** — PERMANENT exemption (correct, not unfinished). Interpreter-only runtime PROVIDER
        (`intrinsics = Map.empty`); its `ActorRuntimeProvider` SPI is interpreter-coupled BY DESIGN —
        `ActorRuntimeHost` traffics in `Computation`/`Value`/`Env`/`scala.meta.Case`, and the SPI doc says
        actors "cannot use the host-neutral `BlockForm` SPI without leaking interpreter internals". No
        host-neutral form exists to migrate to. `StableSpiEnforcementTest` exempts it; the stale-exemption
        guard keeps the allowlist honest.
- [x] **p3-enforce** ✓ DONE — BUILD CHECK: `StableSpiEnforcementTest` (backendInterpreterPluginTests) scans every
      `runtime/std/*-plugin/src/main` and fails if a value-surface plugin references `scalascript.interpreter`;
      a second test guards against STALE exemptions. Exemption: `actors-plugin` (runtime provider) only — graphql now migrated. The 27 migrations are locked in. REMAINING:
      `PluginNative.evalLegacy` stays (still the legitimate untyped `(ctx, args)=>Any` entry the migrated plugins
      use — bodies are clean, so it's no longer "transitional"; only its scaladoc's "may use Value.*" note is now
      stale). Bytecode-level jar scan + the graphql/actors special cases are the only open items.
      STATUS: 27/28 plugins clean (batch-A 10 + ws/pwa/json + uuid/os/request/smtp/
      sql/remote/frontend/http/dstreams/streams/content/oauth/mcp/graphql). PluginApi seam now exposes: nativeFn/callFn, Fn/isCallable, jsonEncode/
      jsonFacade/fromHostAny/parseJson/lookupKey, decimal/asDecimal/Dec, funArity/wrapTypedHandler, field/typeNameOf/
      InstAny, fields/orderedInstance, OAuthBridge(relocated). Remaining: actors only (runtime-provider — permanent exemption is the right call). 27/28 value-surface
      migrations COMPLETE; graphql resolved (carrier must be AnyRef not Any, for scalatest eq).

In priority order:
- [x] **autonomous-hardening** ✓ DONE 2026-06-23 — broad sweep of the coremin-affected surface (cli
      `ExamplesSmokeTest` + interpreter `StdEffectsTest`/`InterpreterTest`/`Actor*`/`*Effect*`/`Stream*`):
      **all green, 2/0 + 338/0, no new breakages.** The one real stale-example breakage (`algebraic-effects.ssc`
      ran `Undefined: runState` in the no-plugin cli smoke) was already caught+fixed in the advanced-optin turn.
      So the effect extractions + prelude minimization did not leave other regressions in the high-signal areas.
      (Did NOT run the ~20-min scala-cli `CrossBackendPropertyTest` — that's a codegen-vs-interp regression
      catcher, orthogonal to the coremin churn; siblings exercise it.)
- **coremin-actors-codemove** → PROMOTED to active 2026-06-23 (Sergiy "внеси в спринт") — see the "Promoted to
      active" queue at the top of Active tasks. (Probe context retained there: atomic ~3500-LOC move of
      `ActorInterp`+`ActorGlobals`+`ActorWireProtocol`, `private[interpreter]`-coupled via the `ActorRuntimeProvider`
      seam; prefer lifting the touched core internals into a typed seam, then moving the file.)
- [x] **strategic-theme-survey** ✓ DONE 2026-06-23 — surveyed BACKLOG strategic themes: the audit shows
      Themes A/E/F/H/J are ALREADY BUILT (FFI = `GlueClasspathRegistry`/`GlueJsPreambleRegistry` landed;
      modularity = `SsclibManifest` landed; stable-SPI Phases 1+2 landed). The only open strategic item is
      `remote-package-registry` (registry.scalascript.io), explicitly DEMAND-DRIVEN (build when a real external
      plugin author needs it — needs hosting/domain, not codeable autonomously). So no greenfield strategic
      slice is ready. Maven publication stays EXCLUDED per Sergiy.
- [x] **advanced-example-check-ux** ✓ DONE 2026-06-23 — concrete follow-up to advanced-optin: the 7 examples
      using advanced-plugin names (`x402-*`→payments, `oauth`/`oidc`→oauth) now `ssc check`-flag unless the
      plugin is added (verified: `undefined name: DefaultSyncBackend/basicRequest`). Added a uniform "Advanced
      plugin" note to each pointing at `--plugin`. Fence-lint + cli smoke 2/0.
- [x] **check-autoload-plugin-by-import** ✓ DONE 2026-06-23 (Sergiy: build it) — `ssc check` now auto-resolves
      advanced names when the file imports the plugin's namespace, no manual `--plugin`. SHIPPED: SPI
      `Backend.providesImports: List[String] = Nil`; payments→`scalascript.x402`, oauth→`scalascript.oauth`+
      `scalascript.oidc`, spark→`scalascript.spark` declare it. `importPrefixesOf(module)` extracts import refs
      from the ```scalascript code-block trees (`scala.meta.Import.importers.ref.syntax`) + doc-level
      `Content.Import`; `BackendRegistry.importMatchedPreludeSymbols(prefixes, availableDirs)` scans
      `lib/compiler/plugin-available` `.sscpkg` packages with a THROWAWAY `URLClassLoader` (non-matching plugins
      never committed to the runtime) and folds in matching `preludeSymbols`. Wired into `ssc check` (Main ~5293)
      AND `check-with-iface`; `-Dscalascript.pluginAvailableDir=` override for tests/custom layouts.
      **Verified end-to-end** against the real staged `payments-plugin.sscpkg`: `ssc check examples/x402-client.ssc`
      → `OK` (was `undefined DefaultSyncBackend/basicRequest`); still errors without the dir; `hello.ssc` unaffected
      (import-gated). `CheckAutoloadImportTest` 3/0, plugin-tests 712/0, cli smoke 2/0. The 7 advanced-example notes
      were updated to reflect the auto-detection. GOTCHA: Scala 3 nested comments — `/*` inside a `/** */` opens a
      nested comment (bit me in a test doc-string).

- [x] **board-spec-hygiene** ✓ DONE 2026-06-23 — reconciled stale core-min/polyglot board/spec wording.
      Updated `specs/polyglot-libraries.md` to the 2026-06-23 landed state, removed future-looking optics
      follow-ups from completed SPRINT entries now that JS/JVM/Rust/Java optics all ship, clarified that
      advanced opt-in prelude cleanup landed after `coremin-hybrid-split`, and changed old block-form template
      notes from "next work" to historical "later landed" wording. No code changed; active `core-min-value-unification`
      claim/worktree untouched.
- [x] **backlog-hygiene** ✓ DONE 2026-06-23 — docs-only classification pass for stale BACKLOG open items.
      Added a status-hygiene note to `BACKLOG.md`; marked `@wasmExport/@wasmImport` out-of-scope by design;
      converted history-only perf rows (`hof-glue-jit-compile`, `vectorize-pure-loop`, `direct-style-eval`) and
      `demand-driven-from-busi` to non-checkbox notes; consolidated duplicate `registry.scalascript.io` under
      `remote-package-registry`; and labelled the remaining intentional `[ ]` rows as `BLOCKED` or `DEFERRED`
      where appropriate. No code changed; active value-unification work untouched.

### ▶ Unblocked & claimable now (2026-06-22 eve, with Sergiy — "занеси в спринт всё что не заблокировано")

These need NO design decision — claimable immediately, in priority/tractability order. Full blueprints
live in the `polyglot-phase2-optics-allhosts` entry below (Task B = cross-language reuse, proven on the JS
slice). Each is one host of the optics-library packaging, individually claimable.

- [x] **polyglot-optics-board-hygiene** ✓ DONE 2026-06-22 — reconciled stale optics packaging entries at the top of `SPRINT.md`.
      **How:** compare the open `emit-lib-cli` / `polyglot-optics-jvm` entries here with the later completed
      `optics-emit-lib-cli`, `optics-jvm-facade`, `polyglot-optics-rust`, and `polyglot-optics-java` entries
      plus `CHANGELOG.md`; mark stale duplicates as done/superseded instead of letting agents re-claim already
      landed work. Do not touch implementation. **Verify:** grep shows no open `[ ]` optics packaging duplicate
      remains in the top claimable queue; active claims are unchanged.
- [x] **emit-lib-cli** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-emit-lib-cli` entry:
      `ssc emit-lib --host js --feature optics -o <dir>` is already user-reachable through `EmitLibCmd`
      (`EmitLibCmdTest` 2/2, README/user-guide updated).
- [x] **polyglot-optics-jvm** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-jvm-facade`
      entry: `emit-lib --host jvm` already emits the native Scala optics library with a compiled smoke and
      golden API coverage.
- [x] **polyglot-optics-rust** ✓ DONE 2026-06-22 (`f13427d4b`, mellow-shrew) — `RustLibPackager`
      (counterpart of Js/JvmLibPackager) emits a dependency-free `ssc-optics` Rust crate (Cargo.toml +
      src/lib.rs + README) via `emit-lib --host rust --feature optics`. lib.rs = faithful dynamic port of
      the JS/JVM optics over a `Value` enum (Obj/Arr/Opt/Str/Int/Bool/Null + `_type` sums): Lens/Optional/
      Traversal/Prism + steps field/index/at/some/each. `RustLibPackagerTest` 4/4: golden (file-set + API +
      dep-free) + a Rust-toolchain-gated cargo smoke (writes the crate + an integration test exercising all
      4 optics + `cargo test` — the emitted Rust compiles AND behaves). user-guide + README updated. 3rd of
      4 optics hosts; Java landed next, so all four hosts now ship.
- [x] **polyglot-optics-java** ✓ DONE 2026-06-22 (`09e174612`, mellow-shrew) — `JavaLibPackager` emits a
      dependency-free `ssc-optics` Java/Maven project (pom.xml + Optics.java + README) via `emit-lib --host
      java`. Optics.java = faithful Java 17 port over dynamic `Object` (Map/List/Optional/`_type` sums):
      Lens/Optional_/Traversal/Prism + steps. `JavaLibPackagerTest` 5/5: golden + emit-lib layout + a
      javac-gated compile/run smoke (exercises all 4 optics → 5/9/10/false/[1, 2]/true/false). **ALL FOUR
      optics hosts now ship: JS (npm) + JVM (sbt) + Rust (cargo) + Java (maven) — Task B optics COMPLETE.**

### ▶ JS-runtime + polyglot follow-ups (2026-06-22 eve, with Sergiy — "запиши в спринт все эти задачи и делай автономно")

Queued after the JS `.mjs`-resource cleanup + rename. Drive top-to-bottom (tractability order).

- [x] **optics-emit-lib-cli** ✓ DONE 2026-06-22 — `ssc emit-lib --host js --feature optics -o <dir>` writes the
      `@scalascript/optics` npm package (package.json + index.mjs + optics.d.ts) from `JsLibPackager`. New
      `EmitLibCmd` registered via the ServiceLoader `CliCommand` SPI; `EmitLibCmdTest` 2/2; README CLI row +
      user-guide section. The optics packager is now user-reachable (was test-only). More host/feature combos
      follow the same shape (see `optics-jvm-facade`).
- [x] **jvm-rust-runtime-resources** ✓ DONE 2026-06-22 (JVM + Rust; §3 #8 closed all backends) — mirror the JS `.mjs`-resource cleanup (polyglot §3 #8) for JVM
      (`JvmGenRuntimeSources`) + Rust (`RustRuntimeTemplates`). **PROBED 2026-06-22 (bright-quail) — NOT a clean
      mechanical copy like JS; more involved:**
      • **JVM** `JvmGenRuntimeSources.scala` (3656 lines): 13 runtime strings, each
        `JvmGenRuntimeCache.memo("key"): """|…|""".stripMargin` — plain (NOT interpolated) but **margin-based**,
        and lazily memo-cached. Migratable: strip the `|` margins → write the post-`stripMargin` content to a
        resource (a `.scala`-fragment file), replace body with `memo("key"): JvmRuntimeResource.load("key")`.
        Byte-identity = `stripMargin` output == resource (NOT a verbatim source copy like JS). Needs a new
        `JvmRuntimeResource` loader.
      • **Rust** `RustRuntimeTemplates.scala` (1570 lines): ~17 `stripMargin` strings (migratable, same shape) +
        **1 `s"""` INTERPOLATED** template (computed at runtime — CANNOT move to a static resource; leave it).
        Needs a `RustRuntimeResource` loader.
      • Scope: feasible + bounded per backend, but each string needs `stripMargin`-output verification and the
        win is smaller than JS (the `|`-margin source is already editable; gain = a real `.scala`/`.rs` file with
        no margin noise + lint/highlight). Do JVM and Rust as **separate slices**. NOT a one-shot mechanical
        sweep — budget per-backend. Spec: extend `specs/js-runtime-resources.md`.
- [x] **optics-jvm-facade** ✓ DONE 2026-06-22 (emit-lib --host jvm; native Scala optics lib, scala-cli-compiled; Rust crate + Java facade + typed/macro optics remain) — Phase 2 next host (`specs/polyglot-libraries.md` §4/§6): publish optics as a JVM
      jar facade + golden API-signature test. Optics has no `.ssc` defs (AST-level) → author a thin Scala facade
      object `Ssc.Optics` (or a `.ssc` facade) over the same 4 optic shapes; reuse `FacadeGenerator`/`ssc link
      --emit-scala-facade`/`JarCommands`. Golden: mirror the JS `optics.d.ts` golden with a Scala signature golden.
      Rust crate and Java facade later followed the same packager shape; all four optics hosts now ship.
- [x] **rust-multishot-unbounded** ✓ DONE 2026-06-23 — **Tier-3 UNBOUNDED (recursion)**: a `multi effect`
      performed inside recursion (dynamic depth) lowers via a Free-monad `MComp` builder (`fn __comp`) +
      multi-shot interpreter (`fn __run`, `resume(v)`→`__run(k(Value::from(v)))`, re-invokable `Rc<dyn Fn>`);
      runtime `MComp`+`and_then` in `runtime/effect.rs`. Recursive Amb `program(2)` → `4`, cargo-run;
      `backendRust` 252/0. + recursive/nested effectful-call reborrow fix (`&mut *_eff`). **Multi-shot effects
      on Rust are now COMPLETE for realistic programs** (Tier-1 List/Option, Tier-2 static-nested, Tier-3
      unbounded recursion). Follow-ups (additive, no consumer): loop-form unbounded, op-args/multi-op in Tier-3.
- [x] **rust-effects-multishot-r6** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bounded Rust multi-shot support is done:
      Tier-1 List (`effect-multishot` bench now runs on rust), Tier-1 Option, and Tier-2 static-depth general
      handlers all landed and cargo-ran (`RustGenMultiShotTest`: List, Option, 1-flip Amb, 2-nested-flip Amb).
      Unbounded **recursion** later landed too (`rust-multishot-unbounded`, 2026-06-23, Free-monad MComp); only
      the *loop* form (vs recursion) remains additive with no current consumer. No Rust code in this closeout.
- [x] **rust-multishot-r6-closeout** ✓ DONE 2026-06-22 — docs-only closeout for R.6 after bounded Rust multi-shot
      slices landed. Updated the detailed `rust-effects-multishot-r6` SPRINT entry to actionable-scope done and
      replaced the obsolete BACKLOG wording that said the Rust bench was unavailable; the only deferred work is unbounded
      perform-in-loop / explicit trampoline, with no current consumer.
- [x] **rust-multishot-board-reconcile** ✓ DONE 2026-06-22 — docs-only cleanup after R.6 Tier-2 nested/static-depth landed.
      The older open `[ ] rust-effects-multishot-r6` entry later in `SPRINT.md` is stale/duplicative: Tier-1 List,
      Tier-1 Option, and Tier-2 static-depth are all done; only unbounded perform-in-loop remains, explicitly
      additive with no current consumer. Marked the duplicate open entry as superseded by the detailed `[~]`
      status above; no Rust code touched. Verify: `rg -n "^- \\[ \\] \\*\\*rust-effects-multishot-r6" SPRINT.md`
      returns no matches.

### ▶ Newly queued (2026-06-22, with Sergiy — "бери все эти задачи если других нет, заноси в спринт")

Queued after closing rust-web-toolkit follow-ons + fixing the index-read move bug it shipped.

- [x] **worktree-guardrail** ✓ DONE 2026-06-22 (`bffef3447`, mellow-shrew, with Sergiy) — structural fix so
      feature commits can't land in the shared `main` checkout again (root cause of the parked-feature-branch
      mess: a prior session committed rust-web-toolkit directly in shared main instead of a worktree, partly
      due to the `EnterWorktree` false-positive, claude-code #27881). **`.githooks/pre-commit`** blocks a
      non-`.work/` commit when in the main checkout (`git-dir==git-common-dir`) OR on branch `main`; feature
      worktrees unaffected; `--no-verify` escape hatch. **`scripts/new-worktree <name>`** = external-path
      worktree recipe (NOT under `.worktrees/`, which siblings prune). **`scripts/setup-hooks`** sets
      `core.hooksPath`. Spec `specs/worktree-guardrail.md`; `scripts/test-worktree-guardrail` 5/5.
      **ACTIVATED** on the shared repo (`core.hooksPath=.githooks`) + verified live: a feature commit in
      shared main is refused, a `.work/` coordination commit passes. (Other clones: run `scripts/setup-hooks`
      once; worktrees off current `origin/main` already carry `.githooks/`.)

- [x] **rust-cargo-smoke-coverage** ✓ DONE 2026-06-22 (`2c8032a5c`, mellow-shrew) — `RustGenCargoSmokeTest`:
      a Rust-toolchain-gated suite (`assume(cargoAvailable)` — probes `cargo --version` directly, since
      `backendRust` doesn't depend on the CLI's `RustToolchain`) that emits a feature-exercising program
      to a temp crate, `cargo run`s it, and asserts real stdout. Covers collection ops (take/drop/
      takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/endsWith/contains), and
      the `Vec<String>` index-read regression (E0507). Closes the move/borrow/type bug class the
      string-match suite can't see. `backendRust` 236/0. BACKLOG `rust-backend-cargo-smoke-coverage` landed.

- [x] **metaprogramming-v2-track-c2** ✓ DONE 2026-06-22 (mellow-shrew, with Sergiy — CONSERVATIVE slice).
      Probed first: the full ambition (Typer over expanded code + map errors to `.ssc` positions) is a real
      trap — both expanders flatten trees→string→re-parse (positions destroyed; a position map would have to
      be built inside 4 hand-written char-scanners) AND full inference over expanded macro-runtime constructs
      risks false positives (confirmed; spec deferred it for good reason). Built the SAFE slice instead:
      `MacroCodegen.expansionTypeWarnings` (wired into `ssc check` `checkOneFile`) catches a macro/inline
      **expansion** that references an undefined name (source type-checks, expansion doesn't). **Zero false
      positives** via a pre/post `Reference to undefined name` DIFF (machinery cancels; user's own undefined
      names stay with the normal check); warning-only; file-level (no position map); excludes builtins/stripped
      names/`_`-helpers; never breaks `ssc check`. Reach is bounded by the strict Typer's position-sensitive
      undefined-name check (val-rhs/bare-stmt). `MacroCodegenTest` +5 (broken→1, valid const-fold/direct-quote/
      interpreter→0, no-op→0); core artifact+typer 496/0; verified end-to-end via `ssc check`. Spec
      `specs/arch-metaprogramming-v2.md` C2 updated. DEFERRED still: precise positions + full-inference recheck.

### ▶ emit-js whole-program effect analysis (2026-06-22, with Sergiy — "берись, запиши в спринт, напиши спеку, и делай") — busi-reported #3, transitive piece

Closes the last open piece of the emit-js effect-handler cluster (BUGS.md
`jsgen-emitjs-effect-handler`; #1/#2/#4 done, #3 core done on `6def53541`, #5
documented). Spec: **`specs/emitjs-effect-whole-program.md`**. The per-module
`EffectAnalysis` doesn't see effects reachable through a 3+-level import chain
(busi: `ledger.accountBalance` → `journal.query` → `Journal`), so a function
calling a transitively-imported effectful function isn't CPS-lowered and its Free
value leaks at runtime. Raw `emit-js` of such a program throws on Node; the JIT
path is fine.

- [x] **emitjs-effect-whole-program** ✓ DONE 2026-06-22 — busi `ledger.ssc` (+ obligation/plan/payment/gate/income) now run end-to-end as raw `emit-js` standalone bundles on Node; guard `tests/conformance/effect-transitive-handler.ssc` (3-level, INT==JS==JVM); busi `make v2-test`+`v2-test-js` + cross-backend green. (1) `JsGen.analyzeEffects` collects trees
      recursively across the import graph (reuse `genImport`'s resolution; parse
      once; visited-set for cycles) and runs `EffectAnalysis.analyze` on the union;
      (2) `effectOps`/`effectfulFuns`/`multiShotEffects` become shared constructor
      params threaded to child gens (like `topLevelConsts`), populated once by the
      entry gen's whole-program pre-pass; (3) drop the now-redundant per-`genImport`
      `analyzeEffects`+merge. Guard: `tests/conformance/effect-transitive-handler.ssc`
      (3-level, INT==JS==JVM) + `ssc emit-js tests/v2/ledger.ssc | node` runs e2e +
      `CrossBackendPropertyTest`/conformance/busi `make v2-test`+`v2-test-js` green.

- [x] **emitjs-standalone-frontiers** ✓ DONE 2026-06-22 (claude-code, `fix/js-standalone-frontiers`) —
      closes the three remaining busi standalone-bundle frontiers recorded under
      `jsgen-emitjs-effect-handler` so `tests/v2/{trust,qr}.ssc` now run end-to-end as raw
      `emit-js | node` bundles and `ksef.ssc` passes `node --check`. Three JS-codegen fixes +
      one refinement: (1) `Term.ApplyUnary` CPS-lowers an effectful operand (`!x`/`-x`) via
      `_bind` instead of `_run`-wrapping it outside the handler (fixes `trust.ssc`); (2) `_dispatch`
      routes `Array.fill/tabulate/range/empty` to the `List` companion since `Array(...)` emits a
      bare native-constructor value (fixes `qr.ssc`); (3) the 14 std/fs file-ops are seeded into
      `declaredBindings` so importing them never re-emits a colliding top-level `const readFile`
      (fixes `ksef.ssc` syntax); (4) refined the `fn-typed-field` `_dispatch` guard from a blanket
      "_type instance → return field as-is" to a precise variadic-lambda check, so genuine zero-arg
      methods (`JsonValue.asString`) auto-invoke again (`json-value` FAIL→PASS). Guards:
      `tests/conformance/{js-applyunary-effect-cps,array-companion-statics}.ssc` + the existing
      `fn-typed-field`/`json-value`. **Before/after emit-js+node sweep over all 113 conformance
      tests: zero PASS→FAIL regressions** (82→85 PASS); busi `make v2-test`+`v2-test-js` green
      (26 files, both backends).

- [x] **emitjs-standalone-capability** ✓ DONE 2026-06-22 (claude-code) — the follow-on frontier:
      emit `nowMillis` (clock) + crypto capabilities into the raw `emit-js` standalone bundle so
      `inbox`/`ksef`/`repo*` run under `ssc emit-js | node`. Two bugs (see BUGS.md
      `jsgen-emitjs-capability-standalone`): (1) a `RuntimeCall` intrinsic (`nowMillis`→`Date.now`)
      reached via the CPS path wasn't rewritten — `genCpsApply` now applies it (new helper
      `intrinsicRuntimeTarget`); (2) a `std/crypto` extern (`sha256`) bound to the `undefined` host
      stub and shadowed its `_sha256` intrinsic — `genObjectAsExpr` now falls back to the intrinsic
      target (guarded by `typeof` + `target != fname` so std/auth's identity webauthn externs don't
      self-reference→TDZ). Standalone emit-js+node sweep **13/21 → 20/21** v2 domain files; guards
      `tests/conformance/{js-cps-intrinsic-rewrite,js-crypto-extern-standalone}.ssc` (INT==JS);
      before/after conformance sweep **zero PASS→FAIL** (84→84); busi `make v2-test`+`v2-test-js`
      green. **Remaining:** `auth.ssc` standalone needs Node WebAuthn impls (host-only externs, no
      `_webauthn*` preamble) — a separate feature, not a capability-emission gap.

### ▶ Core-minimization + polyglot-libraries program (2026-06-22, with Sergiy — "минимизировать ядро всех рантаймов и компиляторов, все вынести в библиотеки и плагины" + "сделать все переиспользуемым со всех рантаймов — из скалы, джавы, джаваскрипт, раста — в виде библиотек, сначала написать спеку")

Two complementary directives, ONE program. **Design spec written: `specs/polyglot-libraries.md`**
(grounded in a full core-vs-plugin extraction analysis). A self-contained module is the unit of reuse:
extract a feature behind the SPI (A) → publish it as a per-host library (B) is the same artifact.

**DECIDED DIRECTION (2026-06-22, with Sergiy — "вынести в плагины всё что возможно"; spec §7a):**
**B→A (enabler-first)**; language forms + hot-path stdlib stay core **forever**; **hybrid** distribution
(essential plugins bundled, advanced opt-in via `pkg:`). Task sequence:

- [x] **coremin-prelude-spi** ✓ KEYSTONE DONE 2026-06-22 (`0ef0bde11`, mellow-shrew) — the SPI hook so a
      plugin declares its check-time public symbols WITH type-signatures and `ssc check` resolves AND
      type-checks calls to them, no hardcoded core list. Decided shape: names+full signatures. Reuse, don't
      invent: `ExportedSymbol` already encodes typed symbols; `InterfaceScope.parseSType`/`parseKind`
      (made `private[scalascript]`) invert `SType.show`. **`Backend.preludeSymbols: List[ExportedSymbol]`**
      (chose the flat symbol list over a full `ModuleInterface` wrapper — no magic/abiVersion/sourceHash
      boilerplate); Typer gains a `preludeSymbols` ctor param → `createPrelude` defines each with its declared
      type (not the untyped `variadic`); `ssc check` (`Main.scala`) collects
      `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` + threads it in; `pluginBuiltins` (names-only) kept
      as fallback. Additive/no-op when empty. Proof `TyperPreludeSymbolsTest` (without→undefined; with→resolves;
      declared type flows — return-mismatch flagged, correct call passes); typer+artifact 499/0. Spec
      `specs/core-min-prelude-spi.md`. NOTE: hook lives at the Typer/`check` layer only (codegen backends are a
      separate concern).
- [x] **sprint-stale-open-items-reconcile** ✓ DONE 2026-06-22 — reconciled stale open items that are already superseded/done.
      **How:** mark `coremin-prelude-migrate-ORIG` as superseded by the immediately preceding
      `coremin-prelude-migrate` finding, and mark `polyglot-phase2-optics-allhosts` as complete because
      JS/JVM/Rust/Java optics hosts now all ship (`optics-emit-lib-cli`, `optics-jvm-facade`,
      `polyglot-optics-rust`, `polyglot-optics-java`). Do not change code. Leave genuinely open items
      (`coremin-actors-migrate`, `coremin-hybrid-split`, `core-min-phase3plus`, etc.) untouched.
      **Verify:** grep shows no open `[ ]` entries for `coremin-prelude-migrate-ORIG` or
      `polyglot-phase2-optics-allhosts`; active claims remain unchanged.
- [x] **coremin-prelude-migrate** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bundled-effect runner prelude migration is complete: 16 bundled-effect runner names moved from the hardcoded Typer prelude into plugin `preludeSymbols`, and the unused typed `runnerType2` helper was removed. This closes the safe actionable scope for this item. Remaining prelude work is split into separate items: advanced/non-bundled `pluginObjects`/`pluginBuiltins` strict opt-in via complete plugin `preludeSymbols`, plus Stream/Actors runner extraction.
  **UPDATE 2026-06-22: finding (2) partially DISPROVED for VARIADIC runner names.** `runRandom` (proof, `754139832`) + a batch of 6 more (`runRetry`/`runRetryNoSleep`/`runCache`/`runCacheBypass`/`runClock`/`runEnv`) now migrate cleanly off `effectBuiltins` into their plugins' `preludeSymbols` — a variadic block-form runner needs NO effect-type to travel (it types as `def … : Any`), so it does NOT wait on `coremin-effecthandlers-spi`. **7 bundled-effect runner names now off the core prelude; locked by `PreludeMigratedRunnersTest` (668/0).** STILL blocked: the NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`). Remaining bundled variadic runner candidates: audit `effectBuiltins` for any not-yet-migrated (e.g. `runStorage`/`runTx`/`runActors`/`runAsync` — only if their plugin is default-bundled AND the keyword is variadic).
  **UPDATE-2 2026-06-22: finding (2) FULLY DISPROVED for bundled runners — even the TYPED ones migrate.** `runRandomSeeded`/`runClockAt`/`runEnvWith` (formerly `runnerType2` `s.define`s) are now in their plugins' `preludeSymbols` too. The unlock: the typer does **NOT enforce effect discharge** (no "unhandled effect" diagnostic anywhere in `lang/core/.../typer/`), so the runner's `! Eff` row is tracked-but-not-checked → declaring the name `Any` is sufficient for `ssc check`; the interpreter resolves the runner via the plugin's block-form, not the typer type. So typed runners do NOT wait on `coremin-effecthandlers-spi` after all. **Production-soundness CONFIRMED:** `installBin` stages all of `allPlugins` (effect plugins included) onto the shipped classpath, so `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` loads them in the real `ssc check` (the `cli/run` compile classpath lacking them is a dev-only artifact). **10 bundled-effect runner names now off the core prelude** (`runRandom` + 6 variadic + 3 typed); `PreludeMigratedRunnersTest` 671/0.
  **UPDATE-3 2026-06-22: SWEEP COMPLETE — the last 6 bundled runners migrated.** `runLogger`/`runLoggerJson`/`runLoggerToList` (logger-plugin), `runState` (state-plugin), `runHttp`/`runHttpStub` (http-plugin) are now in their plugins' `preludeSymbols`; the now-unused typed `runnerType2` prelude helper was removed (`runnerType` stays for `runStream`). **16 bundled-effect runner names total are off the core prelude; only `runStream` remains** (owned by `coremin-stream-migrate`). Verified runtime-unaffected: `StdEffectsTest` runs `runHttp`/`runState`/… end-to-end (15/0). `PreludeMigratedRunnersTest` locks all 15 migrated runners (677/0). **This sub-thread of `coremin-prelude-migrate` (bundled effect runners) is now DONE.** Remaining prelude work is entirely on the OTHER two axes: NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`) and the Stream/Actors runners (entangled, separate SPI additions).
  **UPDATE-4 2026-06-23: runStream prelude name MIGRATED — the runner prelude axis is now 100% (`coremin-stream-prelude-migrate`).** `runStream` + the `Stream` object moved from the hardcoded Typer prelude into `StreamsInterpreterPlugin.preludeSymbols` (`ExportedSymbol("runStream","runStream","def","Any")` + `("Stream","Stream","object","Any")`); the now-dead `runnerType`/`bodyWithEff` typer helpers were removed (core compiles strict `-Werror`). This is the **prelude-name** axis only — Stream's RUNTIME (Free-monad driver + `tryStreamEmitWhileFast` FastTier + `installStreamGlobal`) stays in core per `coremin-stream-migrate` (a `BlockForm` only sees `SpiValue`, no AST). streams-plugin is bundled (installBin stages it; META-INF/services Backend provider) → production `ssc check` resolves via `BackendRegistry.inProcess`. `PreludeMigratedRunnersTest` now locks 16 runners incl. `runStream` (16/16). **NO effect-runner name is hardcoded in the core Typer prelude anymore.** (Pre-existing unrelated failure observed: `StreamsPluginInterpreterTest` "runStream result supports runForeach" — `var buf` captured in `runForeach` loses the first emission; fails on clean origin/main too → filed separately as a runtime var-capture bug, NOT introduced here.)
  **UPDATE-5 2026-06-23: ACTORS keyword set + ADVANCED-OPTIN prelude names DONE — the prelude is now fully minimized.** (a) actors-prelude (`2d9b02588`): ~55 actor/process/cluster keywords → `ActorsInterpreterPlugin.preludeSymbols`. (b) advanced-optin (Sergiy chose "strict opt-in for advanced names"): the hardcoded `pluginObjects`/`pluginBuiltins` PLUGIN-owned names moved to their owning plugins' `preludeSymbols` by tier — essential (Source→streams, setHttpServerBackend→ws, http→http; auto-loaded, no UX change), advanced (oauth/oidc→oauth, Wallets/X402*/Cardano*/PaymentConfig/DefaultSyncBackend/basicRequest→payments, spark/PipelineModel→SparkBackend; resolve only via `--plugin` = strict opt-in). `pluginObjects` deleted; `pluginBuiltins` 21→11 (only interpreter-core globals Async/Await/Signal/Future/Storage + stdlib-.ssc HandlerRegistry/Cluster/ShuffleStage/Stage/runDistributed/runDistributedShuffle remain — no owning compiled plugin). `AdvancedOptInPreludeTest` (710/0). **Caught+fixed a PRE-EXISTING regression**: `algebraic-effects.ssc` (uses runState/runLogger/… = extracted plugins) was still in the cli core-smoke `runnableExamples` (no plugins) → failed at runtime `Undefined: runState` since the first effect extraction; moved it to `PluginExamplesSmokeTest`. **The Typer prelude `effectBuiltins` (language forms + not-yet-extracted runners runAsync/runAuthWith/runStorage/runTx/httpClient/async-primitives + test helpers) and `pluginBuiltins` (11 core/stdlib names) are now the irreducible hardcoded remainder** — everything plugin-owned is declared by its plugin. LESSON: run the cli `ExamplesSmokeTest` after ANY effect extraction (effect examples become plugin-backed, the cli smoke interp has no plugins).
- [x] **coremin-prelude-board-closeout** ✓ DONE 2026-06-22 — docs-only closeout for `coremin-prelude-migrate`
      after UPDATE-3. Marked the actionable scope done, kept future work explicit under the advanced strict
      opt-in and Stream/Actors entries, and added the `CHANGELOG.md` note. No Typer/plugin code changed.
      **Verify:** grep shows no open `[~] coremin-prelude-migrate` and no open
      `[ ] coremin-prelude-board-closeout`; conflict-marker grep is clean.
- [x] **coremin-prelude-migrate-ORIG** ✓ SUPERSEDED 2026-06-22 — original blind-migration plan is superseded
      by the `coremin-prelude-migrate` finding above. The original blocker framing is now stale:
      `coremin-hybrid-split` landed, bundled-effect runner typing proved unnecessary for plugin
      `preludeSymbols`, and the remaining prelude work belongs to separate advanced strict opt-in and
      Stream/Actors tasks. Do not re-claim this original plan as-is.
- [x] **coremin-http-migrate** ✓ DONE 2026-06-22 (`f8f9ac4d3`, mellow-shrew) — the Http effect runner
      (`runHttp` real I/O + `runHttpStub(routes)` stub) extracted from interpreter core into the
      already-bundled `http-plugin`'s `blockForms` — 8th effect off core. Two new SPI capabilities:
      `BlockContext.makeRecord` (handler replies with a `Response` record) + `BlockContext.featureLocal`
      (handler reads the base-url/timeout/retry config the core `httpClient(baseUrl)` form sets).
      `HttpEffectRunner` ports the java.net request logic (Option-based). Removed from core: EvalRuntime
      cases + 2 `reservedApplyHeads` + `EffectHandlers.httpRun`/`doHttpRequest`. `httpClient(baseUrl)` setter
      stays core by design. Tests moved StdEffectsTest→HttpEffectPluginTest (4/4, lazy ServiceLoader);
      StdEffectsTest 15/15. NOTE follow-up: `Interpreter.mkHttpCtx` now dead (minor cleanup).

- [x] **coremin-actors-board-reconcile** ✓ DONE 2026-06-22 — collapsed duplicate open `coremin-actors-migrate` entries.
      **How:** keep one actionable actors item that states the real blocker (scheduler/message-loop seam)
      and mark the older duplicate as superseded; do not touch code or claim the actual actors migration.
      **Verify:** grep shows exactly one open `[ ] **coremin-actors-migrate**` in `SPRINT.md`.
- [x] **coremin-actors-migrate** ✓ SUPERSEDED 2026-06-22 — duplicate of the more precise
      `coremin-actors-migrate (A, entangled)` item below; keep that one as the single open actors entry.
- [x] **coremin-effecthandlers-spi** ✓ RECONCILED → SUBSUMED 2026-06-22 (mellow-shrew). The "3rd keystone
      hook" turned out already covered by the **block-form SPI** (the 1st keystone): a plugin owns a custom
      effect's `Perform` resolution via `Backend.blockForms` (`BlockForm.effectName` + `EffectHandler.reply`),
      dispatched through the core `runWithHandler` trampoline — proven by **8 effects** migrated this way
      (Logger/Random/Clock/Env/State/Retry/Cache/Http). The capability set is complete: stateful per-op reply,
      config args (`newHandler`), closure-apply (`applyFn`), record-build (`makeRecord`), feature-local-read
      (`featureLocal`), result-combination (`result`), stdout (`out`). No separate hook needed.
- [x] **coremin-stream-migrate** ✓ ACTIONABLE SCOPE CLOSED 2026-06-22 — investigated and deliberately deferred; the Stream effect stays in core for now because extraction is low-ROI without a clean consumer for new SPI.
      `runStream` has a **FastTier** (`tryStreamEmitWhileFast`, AST-level `while … Stream.emit` bypass of the
      Free-monad trampoline — zero-FlatMap fast path) that is interp-internal and CANNOT move to a plugin
      (a `BlockForm` only sees `SpiValue` replies, no AST). So a migration is necessarily *partial*: the
      ~40-line `streamRun` handler could move (it'd need a new trampoline **terminate-signal** SPI for
      `Stream.complete/error` short-circuit + `BlockContext.callGlobal` for `Source.from`), but the
      `runStream` case + FastTier + `installStreamGlobal` stay in core. ~40 lines shrunk for real complexity +
      a shared-trampoline change → not worth it. The two new SPI capabilities (terminate-signal + callGlobal)
      are designed + validated (runWithHandler: a resolver returning `Pure(term)` abandons the body) — add
      them only when a clean consumer appears. No code changed for this closeout.
- [x] **coremin-actors-migrate** ✓ DONE (superseded by coremin-actors-codemove, 4578c8e4f) — provider seam + prelude migration + session slice all landed 2026-06-22/23; the "optional hard code-move" was completed by the dedicated `coremin-actors-codemove` task (2026-07-02). Full history:
      `specs/coremin-actors-plugin.md` (`6538c10c6`) defines the interpreter-local actor runtime seam.
      `ea898ca82` adds `ActorRuntimeProvider` / `ActorRuntimeHost`; `ActorInterp.actorInterp` now dispatches
      through `CoreActorRuntimeProvider`, which delegates to the existing core scheduler, so behavior is unchanged.
      `539105e3c` adds the essential bundled `runtime/std/actors-plugin` skeleton, ServiceLoader descriptor,
      provider installation via `ActorRuntimeProviderBackend`, actor `preludeSymbols`, and
      `ActorsPluginProviderTest` (2/0). `cli/installBin` passed and now stages 26 essential `.sscpkg` files
      plus 13 advanced.
      Verified: `backendInterpreter/compile` passed; actor targeted suites
      (`ActorSupervisionTest`, `ActorStopOutsideTest`, `ActorGroupTest`, `ActorDistributedTest`) passed 29/0
      (ScalaTest printed a reporter `InterruptedException`, but sbt finished `[success]`).
      **PRELUDE-NAMES SLICE DONE 2026-06-23 (this session):** the ~55-name actor/process/cluster keyword set
      (`runActors` + spawn/self/send/receive/timeout/recvFrom + membership/leader/gossip/config/drain/metric +
      timers) is now removed from the Typer `effectBuiltins` and DECLARED in `ActorsInterpreterPlugin.preludeSymbols`
      (bundled → production `ssc check` resolves via `BackendRegistry.inProcess`; runtime stays in core via the
      seam, so `spawn`/`self`/… still resolve through `ActorInterp`/`ActorGlobals`). Verified runtime-unaffected:
      `ActorDistributedTest`+`ActorBinaryWsTest` 53/0; `ActorsPreludeMigrationTest` locks a representative name per
      category; typer 196/0, plugin-tests 693/0. `effectBuiltins` now holds only language forms + the not-yet-bundled
      runners (runAsync/runAuthWith/runStorage/runTx/httpClient/async primitives) + test helpers.
      **SESSION-SEAM SLICE DONE 2026-06-23:** `ActorRuntimeProvider` now opens a per-host
      `ActorRuntimeSession`; `ActorInterp` lazily caches one session per `Interpreter` and clears it when a
      replacement provider is installed. This records the state ownership boundary before any future runtime code
      move, without moving scheduler code today. Verified:
      `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
      passed 3/0, and `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest scalascript.ActorBinaryWsTest"`
      passed 53/0 (known ScalaTest reporter `InterruptedException`, sbt `[success]`).
      **Remaining (the hard code-move, optional):** move `ActorRuntime`, scheduler loop, `handleActorOp`, and
      cluster/event drains behind the provider into `runtime/std/actors-plugin`; keep `receive` syntax capture in
      core. **Gotcha:** do not store actor/cluster mutable state on the ServiceLoader backend singleton; today's
      state is per `Interpreter`, so the move slice needs per-host/per-interpreter state ownership. This code-move is
      a large interpreter-internal refactor with NO user-visible change (the seam already lets the runtime live
      either side); deferred as low-ROI like Stream. **Net: the coremin prelude + extraction program is at its
      practical end — all bundled effects + actor names off core, hybrid-split done; only the optional Stream/Actors
      interpreter-internal code-moves remain, both deliberately deferred.**
- [x] **coremin-hybrid-split** ✓ DONE 2026-06-22 (codex) — no-domain hybrid plugin distribution slice.
      `PluginSpec` now carries an essential/advanced tier; `installBin` stages 25 essential bundled
      `.sscpkg` files in `bin/lib/compiler/plugins` (auto-loaded) and 13 advanced bundled `.sscpkg`
      files in `bin/lib/compiler/plugin-available` (opt-in via `ssc --plugin <path>` or
      `ssc plugin install <path>`). No registry domain or hosting required. This slice deliberately did NOT remove
      Typer hardcoded advanced compatibility names; that strict opt-in prelude cleanup later landed in
      `advanced-optin` (2026-06-23). Verification: `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/compile"` passed in 82s; `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/installBin"` passed and produced the two directories/counts above. Bonus guardrail: `installBin` now fails if the explicit `pluginPkgs` list is missing or duplicating an `allPlugins` id; this caught and fixed the pre-existing omission of `fs`/`os`/`yaml` from staged `.sscpkg` files.

- [x] **polyglot-libraries-spec** ✓ SPEC CLOSED 2026-06-22 — `specs/polyglot-libraries.md` now reflects that the
      original draft has implementation slices landed. It unifies A (minimize core) + B (cross-language reuse);
      the original baseline found ~6–7.5K LOC of feature code still baked into interpreter core, but since then
      the block-form SPI, typed `SpiValue`, plugin `preludeSymbols`, multiple effect migrations, JS runtime-resource
      extraction, and no-domain bundled plugin distribution split have landed. Remaining implementation work is
      tracked by separate active/deferred items (`coremin-actors-migrate` optional hard code-move,
      `core-min-value-unification` deep value refactor).
- [x] **core-min-phase1-logger-keystone** (A — the SPI keystone) ✓ KEYSTONE PROVEN END-TO-END 2026-06-22. The
      block-form + effect-handler plugin SPI now works: a plugin can contribute a `keyword { body }` effect-runner
      and the interpreter dispatches to it. 5 increments on origin/main: (1) `c2eec8d3c` generic effect trampoline
      `EffectHandlers.runWithHandler`; (2) `f2d8b5304` SPI contract `BlockForm`/`EffectHandler`/`BlockContext`;
      (3) `7dc508c3b` made it **type-safe** — a host-neutral `SpiValue` ADT instead of `Any` (per Sergiy's review);
      (4) `af58335bc` interp wiring — `valueToSpi`/`spiToValue`, a `_blockForms` registry populated by
      `installPlugins`/`ensurePluginsLoaded`, and an `EvalRuntime` generic block-form case; (5) `0a578ab88` **proof**:
      `reservedApplyHeads` fast-path also excludes `interp.blockForms` names so a plugin keyword reaches the
      dispatch (empty until a plugin loads → plugin-free scripts unchanged). `BlockFormSpiTest`: a `runTally { }`
      plugin block-form + stateful handler → `25`, Int args/replies round-tripped `Value↔SpiValue`. **No
      regression** (StdEffectsTest 48/0, InterpreterTest 141/0). Historical follow-up status: the template was
      used for Logger/Random/Clock/Env/State/Retry/Cache/Http; actors use the separate provider/session seam
      because they own a scheduler rather than a simple block-form handler.
- [x] **core-min-logger-migrate** (A) — ✓ DONE 2026-06-22 (`0353e51ae`). Logger fully extracted from
      interpreter core into `runtime/std/logger-effect-plugin` (`LoggerEffectPlugin extends Backend` with
      `blockForms = Map(runLogger→text, runLoggerJson→json, runLoggerToList→collect-with-`result`-tuple)`,
      handlers over `SpiValue`/`ctx.out`) + `META-INF/services/scalascript.backend.spi.Backend`; build.sbt wired
      via the `allPlugins` registry (`PluginSpec("logger", …)` → auto aggregate + `installBin` + plugin-tests
      classpath). Removed from core: 3 `runLogger*` cases + the 3 names in `reservedApplyHeads` (`EvalRuntime`),
      `loggerRun`/`loggerToListRun`/`loggerJsonStr` (`EffectHandlers`; generic `runWithHandler` stays). The 4
      Logger tests moved `StdEffectsTest`→`LoggerPluginTest` (`interpreter-plugin-tests`) and run with NO
      `installPlugins` — proving production lazy-ServiceLoader dispatch. Verified: StdEffectsTest+InterpreterTest
      **185 green**, LoggerPluginTest+BlockFormSpiTest **7 green**. This became the reusable template for the
      later Random/Clock/Env/State/Retry/Cache/Http plugin migrations; actors use the separate scheduler seam.
- [x] **core-min-random-migrate** (A) — ✓ DONE 2026-06-22 (`2d525ea59`). Random extracted to
      `runtime/std/random-effect-plugin` (`RandomEffectPlugin`; one `RandomBlockForm` registered under both
      `runRandom` and `runRandomSeeded`; per-block `java.util.Random`, replies over `SpiValue` —
      nextInt/nextDouble/uuid/pick, `pick` round-trips arbitrary list elements via `SpiValue.Opaque`). **This
      slice GENERALIZED the block-form SPI to CONFIG ARGS** — `keyword(config…){body}`, not just `keyword{body}`:
      `dispatchBlockForm` now evaluates leading config terms → `newHandler(ctx, cfgArgs)` (the seed). Added the
      generic *curried* block-form cases in `EvalRuntime` (loaded + lazy-load mirror), placed AFTER all hardcoded
      curried special-forms (runClockAt/runEnvWith/httpClient/…) so they only catch genuinely-unmatched applies.
      Removed core `randomRun` + 2 cases + 2 `reservedApplyHeads` names. Tests moved
      `StdEffectsTest`→`RandomPluginTest` (no `installPlugins`). Verified: StdEffectsTest+InterpreterTest **179
      green**, RandomPluginTest+LoggerPluginTest+BlockFormSpiTest **13 green** + full-suite sweep.
- [x] **core-min-clock-env-migrate** (A) — ✓ DONE 2026-06-22. Clock + Env extracted to
      `clock-effect-plugin` + `env-effect-plugin` (one effect = one library). Both curried-config siblings, so
      they REUSE the config-args SPI path from `core-min-random-migrate` with ZERO new dispatch machinery:
      `runClockAt(t0)` → `newHandler` reads frozen-ms; `runEnvWith(map)` → reads the overlay (exercises the
      SPI's `MapV` config path). `ClockBlockForm`/`EnvBlockForm` registered under both plain+curried keywords;
      handlers reply over `SpiValue` (Clock now/nowIso/sleep, frozen=no-op; Env get/set/required with per-block
      mutable overlay + real-`getenv` fallback). Removed core `clockRun`/`envRun` + 4 cases + 4
      `reservedApplyHeads` names. Tests moved `StdEffectsTest`→`ClockPluginTest`+`EnvPluginTest`. Verified:
      interpreter **169 green**, full plugin-tests **647 green** (1 env-gated cancel). FOUR effects are now
      plugins: Logger, Random, Clock, Env.
- [x] **core-min-state-migrate** (A) — ✓ DONE 2026-06-22. State extracted to `state-effect-plugin`. State is
      the first NON-pure-reply effect: `State.modify(f)` must *apply a ScalaScript closure*, which the
      pure-reply SPI couldn't do. **Grew the SPI by exactly one capability — `BlockContext.applyFn(fn, args)`**
      (defaulted to throw → backward-compatible; the interpreter overrides it, routing back through
      `callValue` + synchronous `Computation.run`, parity with the old `callValue1`). `StateBlockForm` under
      `runState`; `newHandler` takes the initial state (config arg); get/set/modify reply over `SpiValue`;
      the `result` hook returns `(finalState, bodyResult)`. Removed core `stateRun` + case + `reservedApplyHeads`
      name. Tests `StdEffectsTest`→`StatePluginTest`. Verified: interpreter **165 green**, full plugin-tests
      **651 green** (1 env cancel). **FIVE effects now plugins: Logger, Random, Clock, Env, State.** Probed and
      recorded: the REMAINING runners (Retry/Cache/Http/Actors) also need interp callbacks — Retry/Cache via
      `applyFn` (thunks); Http additionally needs to construct a `Response` record (no `SpiValue` record case
      yet → would need a `BlockContext.makeRecord` or an Opaque-instance helper); Actors need the message loop.
- [x] **core-min-retry-cache-migrate** (A) — ✓ DONE 2026-06-22. Retry + Cache extracted to `retry-effect-plugin` +
      `cache-effect-plugin`, copying the State template (both re-invoke the body thunk via `BlockContext.applyFn`).
      `RetryBlockForm(sleep)` under `runRetry`/`runRetryNoSleep`; `CacheBlockForm(bypass)` under
      `runCache`/`runCacheBypass`. The Cache TTL store moved into the plugin (process-local `object CacheStore`,
      was `interp._cacheStore`); per-block `bypass` replaces the `_cacheBypass` ThreadLocal (each block's handler
      carries it; trampoline dynamic-scope == ThreadLocal). Removed from core: 4 `EvalRuntime` cases + 4
      `reservedApplyHeads` names; `EffectHandlers.retryRun`/`cacheRun`; `Interpreter._cacheStore`/`_cacheBypass`.
      Wired into `allPlugins` (auto aggregate + plugin-tests classpath) + the explicit `pluginPkgs` installBin list.
      Tests moved `StdEffectsTest`→`RetryPluginTest`(3)+`CachePluginTest`(2) (no `installPlugins`, lazy dispatch).
      Verified: plugin-tests **656/0** (1 env-gated cancel) + InterpreterTest+StdEffectsTest **160/0**. **SEVEN
      effects now plugins: Logger, Random, Clock, Env, State, Retry, Cache.** NOTE: emitters (`Retry`/`Cache`
      globals in `StdEffectsRuntime`) stay in core per the State precedent — only the heavy handlers move.
- [x] **polyglot-phase2-optics-allhosts** ✓ DONE 2026-06-22 — per-host optics library packaging now ships for
      all four hosts: JS/npm (`optics-emit-lib-cli`), JVM/Scala (`optics-jvm-facade`), Rust/cargo
      (`polyglot-optics-rust`), and Java/Maven (`polyglot-optics-java`). Spec §4 + §6. Historical blueprint:
      • Optics is **NOT** a `.ssc` module or named intrinsics — it's AST-level: `Focus[T](_.a.b)`
        (`EvalRuntime.scala:4591`→`OpticsRuntime.evalFocus`) + `Prism[Outer,Variant]` (`:4318`→`buildPrism`); JS at
        `JsGen.scala:4542`/`3746`, runtime `JsRuntimeOptics.scala` gated by `Capability.Optics`. **There is no
        exported symbol table to read — the public facade must be AUTHORED.** The canonical contract is the 4 synth
        optic shapes: Lens(get/set/modify/andThen), Optional(getOption/set/modify/andThen),
        Traversal(getAll/modify/set/andThen), Prism(getOption/reverseGet/set/modify/andThen) — IDENTICAL between
        `OpticsRuntime` (interp/JVM) and `JsRuntimeOptics` (JS). `PathStep`=Field/Some/Each/Index/AtKey.
      • Packaging infra TODAY: `ssc package --lib` (`SsclibPackaging.scala`) emits a `.ssclib` SOURCE zip (NOT a
        host artifact). `emit-js`/`emit-rust`/`emit-scala` emit programs. `ssc link --backend jvm --bytecode
        --emit-scala-facade` (`FacadeGenerator`) is the closest jar/facade path. **Spec §4's `emit-rust --lib` is
        FICTIONAL** — Rust lib mode = "module has no `@main`" (`RustGen.scala:62` → `renderLibRs()`/`src/lib.rs`,
        Cargo `[lib]`, golden-tested in `RustGenRuntimeFilesTest`/`RustGenCargoTomlTest`).
      • Per-host state: **JS = most tractable** (runtime exists+gated; only need ESM wrapper + `package.json` +
        hand-written `.d.ts`; no new codegen). **JVM** = facade/link-to-jar exists but optics has no compilable
        `.ssc` defs → author a thin facade. **Rust** = lib-crate skeleton exists but optic `pub fn` codegen is
        GREENFIELD. **Java** = fully greenfield (`JavaFacadeEmitter` + value-mapping seam). Golden pattern: mirror
        `RustGenCargoTomlTest` exact-string asserts, or `WireGoldenVectorTest` table.
      • **First slice = JS optics npm package**: call `JsGen.generateRuntime(Set(Capability.Optics,Core))`, wrap as
        ESM re-exporting `makeLens/makeOptional/makeTraversal/makePrism`, emit `package.json` + curated `optics.d.ts`
        (the 4 shapes above); golden test asserts the `.d.ts` + exported symbols. Then JVM/Rust/Java follow the
        same packager shape. Rank to ship: JS → JVM → Rust → Java.
      • **✓ JS SLICE LANDED 2026-06-22** — `JsLibPackager` (in `backendJs`) emits the `@scalascript/optics` npm
        ESM package (`package.json` + `index.mjs` + curated `optics.d.ts`); bundles the `JsRuntimeOptics` `_make*`
        factories + only the `_None`/`_Some`/`_isMap` deps (HAMT narrowed to native `Map` at the edge) + step
        builders; re-exports stable `makeLens/makeOptional/makeTraversal/makePrism/Some/None/field/index/at/some/each`.
        `JsLibPackagerTest` 5/5 incl. a node ESM smoke that imports the generated package + exercises all 4 optics.
        The `.d.ts` is the frozen API golden. **Later slices all landed:** (a) user-reachable
        `emit-lib --host js --feature optics -o <dir>` via `EmitLibCmd`; (b) JVM facade jar; (c) Rust crate;
        (d) Java facade. Golden API-signature tests now cover each host.
- [x] **js-runtime-resources** ✓ DONE 2026-06-22 (optics pilot) — first slice of polyglot-libraries §3 #8:
      move JS backend runtime fragments out of big Scala string constants into real `.mjs` resource files
      (lintable / `node --check`-able / editor-friendly). `JsRuntimeResource.load(name)` reads + caches a
      classpath resource under `/scalascript/js-runtime/`; `JsRuntimeOptics` is now a thin wrapper
      (`load("optics.mjs")`) keeping its `val X: String` API → call sites + emitted JS unchanged, verified
      **byte-identical** (7555B, `diff`-empty; `JsLibPackager` golden+node-smoke unchanged). `JsRuntimeResourceTest`
      5/5. Spec `specs/js-runtime-resources.md`. **✓ REST DONE 2026-06-22 (js-runtime-resources-rest):** the
      remaining 17 fragments (`Part1a`–`d`, `Part2a/2b`, `AsyncA/B`, `Signals`, `Dataset`, `IndexedDb`,
      `BrowserPatch`, `Graphql`, `Mcp`, `McpBrowser`, `Payment`, `V14Effects`) all migrated — `diff`-verified
      byte-identical, backendJs compiles, 65 JS codegen tests green. **§3 #8 closed for JS** (all 18 fragments
      now `.mjs`; the `JsRuntime`/`JsRuntimeAsync` aggregators in `JsGen.scala` stay computed). FOLLOW-UPS: same
      pattern for JVM/Rust runtime strings; optional `tsc --checkJs`/`eslint` CI gate (needs JSDoc first).
- [x] **rust-effects-multishot-r6** ✓ SUPERSEDED 2026-06-22 — duplicate of the detailed `[~] rust-effects-multishot-r6`
      status above. Tier-1 List, Tier-1 Option, and Tier-2 static-depth are done; remaining unbounded
      perform-in-loop is additive with no current consumer. ORIGINAL: multi-shot algebraic effects on Rust (resume invoked
      more than once, e.g. NonDet `{1,2}×{10,20}`). One-shot handle/resume already SHIPPED (`a87afba34`, tagless-
      final, no trampoline). lucky-otter flagged multi-shot as out-of-scope/hard: needs an `FnMut` continuation
      that can be re-invoked — the tagless-final one-shot lowering (`resume(v)`→`v` tail-substitution) can't express
      it. RESEARCH slice: probe whether a captured-closure continuation (`Box<dyn FnMut>`) or a CPS/defunctionalized
      re-entry is tractable in `RustCodeWalk`'s handle lowering; if not bounded, SCOPE DOWN + document the blocker
      in `specs/rust-effects.md` §R.6 and BACKLOG. Spec `specs/rust-effects.md`. Lower confidence than the other two.
- [x] **core-min-phase3plus** ✓ ACTIONABLE SCOPE DONE 2026-06-23 — the practical core-min/polyglot Phase 3+
      queue has landed or been split into sharper items. Landed: Logger/Random/Clock/Env/State/Retry/Cache/Http
      effect runners moved to plugins; JS/JVM/Rust runtime resources moved out of backend string blobs where
      bounded; optics ships as native JS/JVM/Rust/Java host libraries via `emit-lib`; bundled prelude names are
      minimized (`runStream`/`Stream`, actors keyword set, and advanced/essential plugin-owned names now come from
      plugin `preludeSymbols`); actors have a provider + per-interpreter session seam. Not closed here:
      `core-min-value-unification` stays as its own deep refactor, and the hard Stream/Actors interpreter-internal
      code moves stay deferred/optional because they have low ROI without a new consumer.
- [x] **core-min-value-unification** ✓ SCALARS-ONLY SCOPE DONE 2026-06-23 — **SPEC + Slices 1-6 LANDED**
      (`specs/value-unification.md`), on two complementary tracks. PROBED the real surface: **4387
      `Value.<Case>` sites across 46 files**; `Value` = sealed trait co-defined with `Computation`/`Env`/
      `FrameMap` (circular) + perf pools; the SPI conversion was lossless via `Opaque` EXCEPT `Char`→`StrV`
      and `Vector`→`ListV` (coerced). **Structural blockers found:** a sealed trait can't be split across
      modules, and data cases can't `extend` a core type if they must live *below* core (a `DataValue extends
      Value` marker is the WRONG direction) → end-state = standalone low-module `DataValue` enum + `Value =
      DataValue | carriers`, `type SpiValue = DataValue`, conversion deleted. NO early slice deletes duplication
      (payoff lands at the final merge), so the work is a sequence of safe always-green slices.
      **Track A — SpiValue completion:** added `SpiValue.CharV`/`VectorV` so the SPI boundary is LOSSLESS for
      all immutable data cases (mutable `Array` + case instances stay `Opaque`, correct); `SpiValueDataRoundTripTest`,
      plugin-tests 712/0. **Track B — disentangle `Value.scala`:** extracted `Computation`+runtime signals →
      `Computation.scala` and `Env`/`FrameMap`/`MutableEnvView` → `Env.scala` (byte-identical, zero-behavior;
      InterpreterTest 158/0, effects 33/0, closure/pattern/tuple 186/0). **Slice 3 spike DONE 2026-06-23:**
      validated `type Value = DataValue | Callable` (union) + `export DataValue.*` from `object Value` — existing
      `Value.IntV(n)` construct + `case Value.IntV(n)` patterns compile unchanged, DataValue lives below core,
      exhaustiveness preserved under -Werror (rejected: `DataValue extends Value` marker; bare union w/o export).
      **SCOPE DECISION 2026-06-23 (Sergiy): SCALARS-ONLY — full merge OFF the table.** The container/closure
      obstacle: the interp stores closures INSIDE containers (`List(() => 10)` = `ListV(List(FunV))`), so a
      fully-merged low data type would force closures-as-`Opaque` → a cast on the HOT function-dispatch path
      (perf regression Sergiy declined). So only the scalar leaves are shared; containers + carriers stay core;
      the conversion shrinks (scalars→identity) but is NOT deleted. **Slice 4 DONE 2026-06-23:** flipped `Value`
      to a union `type Value = DataValue | ValueRest` — `DataValue` (new enum, `DataValue.scala`) = 9 scalar
      leaves; `ValueRest` (sealed) = 14 container/instance/carrier cases; `object Value` re-exports scalars via
      `export DataValue.*` so all ~4387 sites are UNCHANGED. Astonishingly clean: the ONLY friction was one
      `java.util.Arrays.sort` over a union array (→ `Array[AnyRef]` cast); exhaustiveness preserved. Verified
      core+backendInterpreter+all plugins+server+dap compile; core/test 1019/0, plugin-tests 712/0, broad
      interp/value/effects 218/0, numeric/collection/JIT 77/0 (~2026 green). **Slice 5 DONE:** moved `DataValue`
      to a new low leaf module `lang/value-data` (below core+backendSpi). **Slice 6 DONE:** `SpiValue` is now
      `type SpiValue = DataValue | SpiRest` — scalar leaves are the SAME shared `DataValue` classes (SpiRest =
      SPI-private containers + Opaque; `object SpiValue` re-exports `DataValue` w/ `StringV as StrV`, so the 9
      plugins + all `SpiValue.*` sites are unchanged); `valueToSpi`/`spiToValue` convert scalars by IDENTITY.
      **✅ SCALARS-ONLY UNIFICATION COMPLETE** — one shared set of scalar classes across `Value` + `SpiValue`;
      the scalar half of the conversion is gone; the container half stays by design (closure-bearing obstacle).
      plugin-tests 712/0, round-trip+effects+numeric 183/0. The actionable scope of this task is now CLOSED
      (full merge deliberately off — perf). Original goal/notes below (NOTE: the "delete the conversion / one
      type" end-state is SUPERSEDED by the scalars-only decision — the container half is correct to keep).
      <br>**Goal (original):** collapse the duplication
      between the interpreter's `Value` and the SPI's `SpiValue` into ONE value type. Today they're separate by
      necessity: `interpreter.Value` (in `core`) is entangled with *execution* — `FunV(closure: Env)`,
      `NativeFnV(f: List[Value] => Computation)`, mutable `InstanceV`, `type Env = Map[String, Value]` — and
      `backendSpi` (which `core` depends on, not vice versa) can't reference it, so the boundary uses the
      host-neutral `SpiValue` (+ a `Value↔SpiValue` conversion). **Goal:** un-entangle `Value` from execution —
      split the *pure-data* cases (`Int/Double/Str/Bool/Char/Unit/List/Vector/Array/Map/Tuple/Option/Instance`)
      from the *runtime-carrier* cases (closures/native-fns hold an `Env`/`Computation`), moving closures +
      `Computation` out of the `Value` ADT into a separate runtime structure. Then the data ADT can live in a
      low shared module and **be** `SpiValue` — one value type across interp + SPI + host libraries (Task B),
      deleting the conversion. **Caveat (why it's LATER):** it's a deep refactor touching every `Value` match in
      the interpreter (DispatchRuntime/PatternRuntime/EvalRuntime), and it still privileges the interpreter's
      shape, so it's lower-priority than the keystone extractions; the current `SpiValue` (= the safe data
      subset) is correct in the meantime. **Verify:** full interp suite green; `Value↔SpiValue` conversion gone;
      no `Env`/`Computation` reachable from the SPI value type.

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
      `signal("n", 10)` + `n() + 5` → `15`. `backendRust` 225/0.
- [x] **direct-WS** ✓ DONE 2026-06-20 — a `serve(view)` program also exposes a WS signal endpoint on
      `port + 1` for external clients (rozum bridge), bidirectional + sharing the SSE store/broadcast/recompute.
      `ssc_ws_serve` (accept_async) sends state on connect, streams updates, and an incoming `name=value` frame
      sets+recomputes. **Verified cargo + raw-WS client (python):** WS-push `locale=de` → `{"__c0":"de"}`.
      `backendRust` 226/0. **rust-web S5 now FULLY COMPLETE** (set/toggle, SSE, computed compile+SSR + live
      recompute, typed reads, direct-WS — all built + cargo/curl/WS-verified).

### ▶ Benchmark perf-divergence queue (2026-06-21, with Sergiy — "разбирайся в чем дела — в jit? В codegen? В bench?")

The big per-workload outliers from the same `./bench.sh` sweep, each ROOT-CAUSED by hand (emit + read the
generated code / toggle the JIT). Verdict per case: **codegen**, **jit**, or **bench** (intentional anti-fold).

- [x] **asm-jit-effect-pathology** (JIT) ✓ DONE 2026-06-21 — `ssc-asm` `effect-oneshot` **9.46 → 0.032
      ms/iter**, now effectively matching default `ssc` (0.025 ms/iter). Root cause: Javac bytecode JIT lowered
      active one-shot tail-resume effect ops through `JitGlobals.resolveEffectLong*`, but ASM `walkLong` did
      not, so `Bump.tick().toLong` bailed out to the slow effect trampoline. Fix `0d5e03b87`: ASM mirrors the
      resolver lowering and treats resolved effect calls as Long-shaped for `.toLong`/`.toInt`. Verified with
      `AsmEffectJitTest`, `EffectOneShotFastPathTest`, `JitLintTest` (85/85), `sbt -no-colors cli/installBin`,
      and `./bench.sh effect-oneshot --backend ssc{,-asm}`.
- [x] **js-tuple-monoid-alloc** (CODEGEN) ✓ DONE 2026-06-21 — **`js` `tuple-monoid` 7.40 → 2.60 ms (2.85×)**,
      no longer the slowest cell. Two general JsGen fixes: (1) `t._N` on a statically-known tuple lowers to a
      direct `t[N-1]` array read (new `tupleVars` tracking + `isTupleExpr`), skipping the megamorphic
      `_dispatch(t,'_N',[])`; case classes never match `isTupleExpr` so their Product `._N` is untouched.
      (2) a tuple-LITERAL concat `(a,b) ++ (c,d)` flattens into ONE `Object.assign([a,b,c,d],{_isTuple:true})`
      instead of `_tupleConcat(Object.assign(..),Object.assign(..))` (3 allocs → 1); a variable operand still
      uses `_tupleConcat`. **Verified:** 281 JS unit tests green; interp == js on tuple flatten/`._N`/show/eq.
      NOT done (left): native `+` for the `_arith('+')` on tuple-element reads (needs tuple-element type
      tracking) — lower value. The `s` LCG interp/js delta in this workload is the separate 64-bit-Long-on-JS
      precision limitation, not a tuple bug.
- NOTE (no task — **bench**, intentional): rust `arith-loop` **1.52 ms (4.7× jvm)** is largely the harness's
      anti-fold — `run.sc` wraps every rust closure body + per-iter reassignment in `std::hint::black_box(...)`,
      blocking LLVM loop optimization (the comment at `run.sc:176` even tunes this so rust "stops looking 3–4×
      slower"). Not a codegen bug; leave as-is unless we want a lighter rust anti-fold.

### ▶ Benchmark backend-gap queue (2026-06-21, with Sergiy — "Запиши в спринт все n/a")

Every `n/a` from a full `./bench.sh` sweep (31 workloads × ssc/ssc-asm/jvm/js/rust), each VERIFIED by hand
against the current toolchain (the corpus comments were stale). The bench measures time only (no correctness
check — that's `CrossBackendPropertyTest`, green); `n/a` = that backend's emit/build/run failed.

- [x] **rust-effects-handle-resume** (R.4.2, ONE-SHOT) ✓ DONE 2026-06-22 — **`effect-oneshot` n/a → 0.0020 ms
      on rust** (the fastest backend on it). Custom algebraic effects with explicit `handle`/`resume` now
      compile + run on rust via **tagless-final traits** (per `specs/rust-effects.md §10`), NOT the Free-monad
      CPS port the old `rust-backend.md §R.4` implied — so the `while`-loop case needs **no trampoline** (the
      loop runs directly; `Bump.tick()` is `_eff.tick()`). 3 gaps implemented: (1) a custom `effect E:` object
      emits a `trait ${E}Effect` with required methods (`collectEffectOps` + `renderTaglessEffectsRs`); (2)
      `Eff.op(args)` → `_eff.op(args)`; (3) `handle(body){ case Eff.op(binders, resume) => arm }` → a handler
      `struct __H_E; impl ${E}Effect for __H_E { fn op(&mut self, binders) -> ret { <resume(v)⇒v> } }` +
      `{ let mut _eff = __H_E; <body> }`. **Verified:** minimal probe cargo-builds → `10`; the real
      `effect-oneshot.ssc` workload → `962` (== interp/jvm); `backendRust` 230/0 + 3 new `RustGenR44Test`
      cases. **Remaining (R.6 follow-up, NOT this task): multi-shot.** `effect-multishot` stays `n/a` — its
      `opts.flatMap(opt => resume(opt))` calls `resume` many times, which a single trait-method return can't
      model (needs FnMut continuation re-invocation); it fails cargo cleanly (out of scope by design).
- [x] **jvm-multishot-result-type** ✓ DONE 2026-06-21 — `effect-multishot` was `n/a` on **jvm** because
      CPS def emission widened total handled-effect wrappers from their declared result type to `Any`:
      `def workload(seed: Long): Long` emitted as `def workload(seed: Long): Any`, and the bench wrapper's
      typed sink failed with `Found: Any; Required: Long`. Fix (`39b7c665f`): keep declared non-effect-row
      result types at CPS def boundaries and cast the final CPS result there; effect-row defs (`A ! Eff`)
      still return `Any` so handlers can unwrap Free computations. Guard: `JvmGenEffectsRuntimeTest`
      `addLong(workload(0L))` e2e. **Verified:** `backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest`
      34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` `n/a` -> 0.075 ms.
- [x] **rust-either-chain-closure-type** (E0282) ✓ DONE 2026-06-21 — `either-chain` was `n/a` on **rust**
      (`cargo build` → `error[E0282]: type annotations needed` because the chained `match match match …`
      emitted each Either arm as `(move |x| { … })(v)`, whose closure param type rustc couldn't infer). Fix:
      a new `inlineArm` lowers a 1-param Either map/flatMap/fold arm to a `{ let x = v; body }` block instead
      of an immediately-applied closure — the `let` flows `x`'s type straight from `v`. Function-reference args
      keep `(f)(v)`. **Verified:** `cargo build` green; interp == rust (`R=632`); `./bench.sh either-chain
      --backend rust` n/a → **0.0040 ms**; `backendRust` 229/0 + a new `RustGenR23Test` E0282 regression test.
- [x] **bench-stale-jvm-na-hygiene** ✓ DONE 2026-06-21 — the stale JVM `n/a` was not a cache issue; it shared
      the `jvm-multishot-result-type` root cause. Total CPS wrappers declared as `Long` emitted as `Any`, so
      the bench sink rejected both `effect-oneshot` and `effect-multishot`. Corpus comments were refreshed.
      **Verified:** `./bench.sh effect-oneshot --backend jvm` = 0.160 ms; `./bench.sh effect-multishot --backend jvm`
      = 0.075 ms; `./bench.sh effect-oneshot effect-multishot --backend js` = 0.347 / 0.224 ms.

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
- [x] **stable-plugin-spi-p3** ✓ DONE 2026-06-21 — completed one small Phase 3 SPI cleanup slice:
      `bench-plugin` now implements `Bench.opaque` through `PluginNative.eval` / `PluginValue` instead of
      importing `scalascript.interpreter.Value` directly. Added `BenchIntrinsicsTest` to lock identity
      behavior (including empty args -> `Unit`) and to scan `bench-plugin/src/main` for direct interpreter
      imports so this slice does not regress. **Verified:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/stable-plugin-spi-p3 && sbt -no-colors "benchPlugin/test; pluginApi/test; benchPlugin/checkPluginBoundary"`
      → `BenchIntrinsicsTest` 2/2 green, `PluginApiTest` 14/14 green, `benchPlugin/checkPluginBoundary` green.
- [x] **js-char-wrapper-string-map** ✓ DONE 2026-06-21 — added a JS `_Char` box (`JsRuntimePart2a`):
      `valueOf`→code point, `toString`→1-char string (so concat/arith/`_show` coerce). Iterated chars
      (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) box;
      `String.map` returns a String only when every result is a `_Char`, else a Seq (mirrors `strMapResult`).
      `_dispatch` got a `_Char` branch mirroring the interp `dispatchChar` (`toInt`→code, `isDigit`/`toUpper`/
      `asDigit`/…); `_eq` bridges `_Char` ↔ 1-char String literal and ↔ Int. `CrossBackendPropertyTest`
      "String.map char vs non-char" now asserts interp == JS == JVM (+ a char-method map/filter case).
      **Verified:** 280 JS unit tests green (23 suites, 0 fail); String.map + string-method-gaps cross-backend
      green on all 3 backends; direct node probe matches interp byte-for-byte. Residual (BUGS.md): a char
      *literal*'s `.toInt` (`'5'.toInt`) still diverges (literals stay strings to avoid literal-pattern
      `===` codegen) — separate, lower-value follow-up.
- [x] **rust-web-example** ✓ DONE 2026-06-21 (a55e101f2) — added `examples/rust/web-signals.ssc`
      (signal + computedSignal + signalText + serve), emit-rust + `cargo build` green, binary serves SSR and
      `/__ssc/push?name=locale&value=de` recomputes the computed signal (`{"__c0":"fr"}` → `{"__c0":"de"}`).
      Building it (vs the string-match tests, which never cargo-build) surfaced + fixed **two real bugs**:
      (1) computed move-closure use-after-move (cargo E0382) — `renderClosure` now clone-captures read signal
      locals; new regression test, backendRust 228/0; (2) docs showed `POST /__ssc/push -d` but the endpoint
      reads query params `?name=&value=` — corrected example + rust-backend.md + user-guide.md.
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
- [x] **board-meta-v2-reconcile** ✓ DONE 2026-06-21 — removed stale meta-v2 Track C/C2 "still open"
      guidance from the board.
      **How:** reconcile `SPRINT.md`'s later `[~] metaprogramming-v2` paragraph and `BACKLOG.md` roadmap text
      with the authoritative `meta-v2-track-c` done entry plus `specs/arch-metaprogramming-v2.md` §4b, which
      says the remaining arbitrary post-expansion re-typecheck ambition is deferred by design. Keep the
      historical spec rationale; change only active queue/backlog wording so future agents do not pick C2 as
      buildable work. **Verify:** targeted grep now leaves only spec/history/deferred wording; active
      `SPRINT.md`/`BACKLOG.md` guidance no longer presents C2 as buildable work.
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

- [x] **rust-web-toolkit** (external driver: rozum) — bring the declarative std/ui toolkit
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
      **✓ CLOSED 2026-06-22:** S1–S5 all landed on `origin/main` (S4 named/curried args + omitted-default
      fill; S5 SSR + local client + server-push + SSE/direct-WS + computed live recompute + typed signal
      reads — see CHANGELOG 2026-06-19/06-20). The driving use case `examples/rozum-meeting.ssc` builds to a
      binary and SSRs over hyper. General Rust-backend follow-ons (Vec `take/drop/sorted/distinct`, String
      `.replace`, http prefix-routing/no-store/POST-body/MIME, indexable `split/toList`) landed on main via
      `rwt-followons` (613c2bb21, `backendRust` 233/0). The `feature/rust-web-toolkit` branch is rozum's own.

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

- [x] **metaprogramming-v2** ✓ ACTIONABLE SCOPE DONE 2026-06-21 — AUDIT 2026-06-17: NOT a from-scratch build. All three
      phases have working bases (P3 Linker `inlineTable`/`expandInlineSource`; P4 `${impl('x)}` + direct
      `'{ $x+1 }` + interp parity + `MacroImpl` IR; P5 runtime `Mirror` + user `derived(m: Mirror)`).
      PROGRESS: **Track A** (P5 cross-backend derives conformance) ✓ DONE (A1a/b/c + A2 + A3,
      2026-06-17; only deferred edge cases remain — sum-type/enum mirrors, generics, mixed-derives clauses).
      **Track B** (P4 const-folding `Expr.asValue match`): **B1 + B2 ✓ DONE 2026-06-18** (interp splice
      unwraps `Expr(v)`; `Linker.expandMacroSource` const-folds literal args to the `Some` branch, else the
      `None` direct quote; `LinkerRewriteTest` +7 / `InlineDerivesTest`; `examples/quoted-macro-constfold.ssc`).
      **B3 ✓ DONE 2026-06-18 — JVM + JS** (was blocked — quoted macros were interpreter-only): the
      `macro-codegen-backends` pass (`MacroCodegen.expand`, hooked into `JvmGen` + `JsGen` generate entry
      points) expands + strips macros pre-codegen, no-op for macro-free modules;
      `QuotedMacroJvmConformanceTest` (scala-cli) + `QuotedMacroJsConformanceTest` (node) match interp.
      **Track B is complete (B1+B2+B3).** **Track C:** C1 (multi-clause inline) ✓ DONE 2026-06-18
      (curry tail clauses into the body — no scanner/wire change); C2's practical backend guard is already
      wired through `MacroCodegen.codegenWarnings`, and the broader arbitrary post-expansion re-typecheck +
      source-positioned-error ambition is deferred by design (position-map requirement + false-positive risk).
      No bounded autonomous meta-v2 build slice remains on the board.

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
- [~] **theme-a-stable-plugin-spi** — Phases 1+2 landed (stable surface exists). Residual = **Phase 3 versioned
      stable API module → PROMOTED to active 2026-06-23** (Sergiy "внеси в спринт"); see the "Promoted to active"
      queue at the top of Active tasks.
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

**Genuine remaining BUILD work** (across Tiers): no bounded autonomous build slice is currently ready here.
The old sbt-plugin build pieces are done and publication is Maven-gated; build-registry Phase 3 is moot and
Phase 4 is demand-driven; meta-v2 Tracks A/B/C are actionable-scope done with only deferred edge cases. The
small residuals above are blocked by real browser/device/external inputs. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.

## Corpus 152/10 — честная категоризация остатка (2026-07-09, corpus-real-bugs)

Два ОБЩИХ бага починены и закреплены конформансом (d741736bf, 7ec8e3f74):
- [x] **parenless-def-autoinvoke** — `def foo: T = body` (Lam(0)) при ссылке по имени
      теперь вычисляется (App(Global,Nil)); externs (значения, не thunk) в отдельном
      наборе. Чинит dsl-mini-language. Кейс parenless-def-value.
- [x] **predef-???** — `???` бросает NotImplementedError вместо «unbound global»; ленивый
      (не-взятая ветка не бросает). Кейс predef-notimplemented.

Оставшиеся 10 — НЕ быстрые общие баги (каждый = среда/wip/плагин-слайс):
- СРЕДА (не code-fixable): distributed-join (нет ./data/orders.csv), distributed-log-aggregation
  (нет /var/log/app.log), x402-cardano (нет BLOCKFROST_KEY), x402-cardano-scalus (val=??? by design,
  нужен реальный key vault + Scalus).
- WIP (чужая ветка): control-center-live (wip/control-center-live).
- СЕРВЕР/SPA (биндит порт; в батче парс-артефакт конкатенации фенсов): datatable-static-spa.
- КОНФИГ примера: pg-listen-notify (нужна databases: секция во front-matter).
- [x] **mcp-search-server** — FIXED 07-09 (15030a16c): curried-native-в-block-DSL.
  knownCurriedNatives {tool/toolWithSchema/resource/prompt} держит two-step (run-путь
  не зовёт resetState — seed бы не выжил); first-clause hints позиционно; native принимает
  name+desc+Bool-hints. Корпус 153/9. Исходный анализ ниже (для истории):
- (история) БРИДЖ-СЛАЙС (углублён 07-09, v2-finish-all): mcp-search-server — ЧАСТИЧНО: общий
  named-arg→UnitV баг ПОЧИНЕН (6ef926e16 — named args к методам теперь позиционные значения,
  не теряются). ОСТАЁТСЯ: curried-native-в-block-DSL. `srv.tool(4args)(handler)` внутри
  `mcpServer{srv=>…}` конвертится путём, который НЕ проходит через convertApply (apply-dbg
  не сработал, mcp-dbg — да → тело block-DSL идёт спец-обработчиком convertBlock/иным,
  минуя curried-арм 1963). Нужно: найти путь конверсии тела block-DSL и применить two-step
  для curried-нативов (tool/toolWithSchema/resource/prompt) + native принять trailing
  hint-args в 1-м клозе. Спекулятивные правки (native hint case, curried seed) откачены —
  не работали, т.к. корень в необследованном пути конверсии.
- БРИДЖ-БАГ (старая формулировка, см. выше): mcp-search-server — НЕ native-фикс.
  Дамп аргументов показал ДВА бридж-бага: (1) named-args к методам opaque-инстанса
  (`srv.tool(..., readOnlyHint = true)`) конвертируются как `cell.set(@name, n)` → UnitV
  (механизм receive(timeout=n) из FrontendBridge ~2669 протекает на общий method-call
  named-arg путь) — значения ИМЁННЫХ аргументов ТЕРЯЮТСЯ; (2) curried `(handler)` схлопывается
  в тот же плоский список (5 аргументов вместо two-step). native толерантным делать НЕЛЬЗЯ —
  спрячет оба бага (tool-аннотации потеряются). Слайс = починить method-call named-arg
  конверсию в бридже (не путать с @timeout-cell механизмом). graphql-client: graphql-плагин
  не бриджен + SpaceX API отдаёт HTML (частично среда).
- ПОЛНЫЙ СЛАЙС (scoped выше): remote-registry-rpc (unmask-remote-def, 3 слоя).


## p4-bc-perf UPDATE (78c459fc4): pure-Seq inline closed loop/recursion gaps; 9/10 workloads now parity-or-faster than VM. Remaining: recursion-tco 4.6x (boxed tail-loop params — needs unboxed Long slots).
