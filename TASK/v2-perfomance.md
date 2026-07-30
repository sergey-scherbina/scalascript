# v2 — performance

Everything I know that is a **speed** problem. Correctness defects found while measuring are NOT
here even when a benchmark is what found them — they are in `TASK/inbox-uncategorised.md`, waiting
for the category policy to assign them a number.

Slice IDs are `v2-perf-N`. One row (`v2-perf-10`) is a **v1** defect; it is carried here because it
is the same defect class as the v2 one I already fixed and the same gate finds it — when this file
is split per policy, that row goes wherever v1 performance goes.

**Read first:** `specs/v2-vs-v1-backend-matrix.md` (the measured table and its categories) and
`specs/v2-runtime-perf-vs-v1.md` §7 (the measurement protocol — it is not optional here, see
"House rules" at the bottom).

Ratios below are `v2-bytecode ÷ ssc` from the 2026-07-29 sweep on a **quiet** machine (load 1.8).
`v2-bytecode` is the product lane; plain `v2` is the `--interpret` reference lane and is slower by
design.

---

## Cause located, fix specified — start here

### v2-perf-1 · The Double numeric tier is built and never wired up
**`float-fold` 315× · `float-loop` 54×** — against `arith-loop` (the Long twin) at **2.4×**.

`v2/lib/ssc1-lower.ssc0`, the `var` case (~:4038), reads
`if isIntLitExpr(expr) then lcell.new … else cell.new …`. There is no Double arm, so a `Double` var
gets a GENERIC cell and boxes on every read and write: 841 `FloatV` allocation samples on
`float-loop`, plus ~600 CPU samples in the string-keyed `cell.get`/`cell.set` path.

`Prims.dcell.new/get/set`, `Emit.dcellAccum` (written as the deliberate twin of `lcellAccum`) and
`JvmByteGen.canDouble/genDouble` **all already exist**. `grep -c dcell v2/lib/ssc1-lower.ssc0` → **0**.

Needs: an `isFloatLitExpr`; a `dcell.new` branch with its own scope marker; a Double twin at each
`@@`-marker read/write site (:2575, :2870, :4094). Target is the Long ratio (2.4×), not zero.

### v2-perf-2 · Every primitive is resolved by STRING at run time
`Emit.prim1` + `Emit$.s1` + `CHM.computeIfAbsent` ≈ 600 CPU samples on `float-loop`, ~18% of
`list-fold`'s profile. The op is a compile-time constant in the emitted bytecode.

Half of this is already done (`Emit.prim1/2/3` no longer build a `List` per call — 1.36× on
`literal-match`). What remains is emit-time resolution: `JvmByteGen` emits a static slot per
distinct op, filled in `<clinit>`. **Ceiling ~18% of the worst workload** — stated so nobody starts
it expecting more.

### v2-perf-3 · `Vector` is a cons chain, so indexing is O(n) by construction
**`vector-index` 46×.** v2 has no `VectorV`. Walking the chain in place instead of materialising it
bought only 1.3× (landed); the honest fix is a real indexed representation, which is a design
change and needs a spec before code.

---

## Measured, cause known, no fix specified yet

### v2-perf-4 · Allocation is what is left on the worst rows
**`pattern-match-heavy` 381×.** After the per-match field array was removed (1.20×), a re-profile
shows the remaining cost is allocation, not dispatch: **`Value[]` 1507** samples (an env array per
call, `Runtime.extend`) and **`FloatV` 841** (a box per number). This is the architectural layer —
see v2-perf-9.

### v2-perf-5 · The `__method__` split is a linear scan
`lazylist-take` spends **665 of ~2500 samples (~25%)** in `methodDispatch2..7` failing to match:
its `ForeignV` receiver is served by part 9, so it walks seven parts to get there.

A mechanical census (script over the arm patterns, not by eye) established that exactly **two**
parts are single-kind and provably skippable — part 2 is 40 `StrV` arms + one `case _`, part 7 is
40 `DataV` + one. Every other part is mixed, and parts 5/6/10 carry bare-variable receivers with
guards that can match anything. **Guarding those two was implemented, measured at 1.03–1.05× and
REVERTED** (see "Refuted"). Kind-indexed dispatch over the mixed parts is the remaining idea; the
census is its prerequisite and is preserved in `v2/BACKLOG.md v2m-2f`.

### v2-perf-6 · Collections, strings, predicates — the remaining ≥10× rows
`lazylist-take` 395× · `list-fold` 146× · `range-sum` 113× · `map-ops` 28× · `hof-pipeline` 27× ·
`bool-predicate` 25× · `literal-match` 18× · `string-concat` 12×.

`lazylist-take`'s profile is 833 samples inside Scala's own `LazyList` — inherent to backing the
type that way. `list-fold`'s `foreach` already walks the cons chain in place; there is no
materialisation left to remove there.

### v2-perf-7 · Category 3 has never been analysed
`tuple-monoid` 9.1× · `type-lambda-placeholder` 8.8× · `instance-field` 7.5× · `either-chain` 6.9× ·
`option-chain` 6.8× · `effect-pure` 6.8× · `mutual-recursion` 6.3× · `string-split` 5.2× ·
`nested-loop` 5.1× · `arith-loop` 2.4× · `recursion-fib` 1.0×.

Only now measurable: the earlier sweep ran at load 5.7–10.2, where sub-30% differences were not
established. **Contention COMPRESSES ratios** — `nested-loop` reads 5.1× here against 2.8× under
load, i.e. worse, not better. Do not treat the old numbers as a baseline.

---

## Compile-time, not run-time

### v2-perf-8 · The F front costs ~2.8× over legacy
`examples/scljet-crud.ssc`: F 11.40 s vs legacy 4.08 s. Was 6.8× — it fell to 2.8× as a side effect
of the `__method__` JIT fix, because F is an `.ssc` program interpreted on that runtime.

`specs/v2-f-compile-cost.md` Result 2 (the cost SCALES with program size, so a super-linear step
inside F is the likely remainder) has **not** been retested since. `scljet-jdbc` is still 27.9 s.

### v2-perf-8b · `scljet-jdbc`'s generated bytecode exceeds the JVM's 64 KB method limit
It prints `--bytecode fell back to the VM lane [class-size-limit] (MethodTooLargeException)` and
silently runs on the slower lane. Same "emit smaller methods" family as the JIT-size work. Tracked
as `v2/BUGS.md scljet-jdbc-facade-bytecode-class-too-large`.

---

## Not a slice — a programme

### v2-perf-9 · v1 JIT-compiles loops; v2 calls a generic runtime per element
This is the honest shape of the biggest remaining ratios (v2-perf-4, v2-perf-6). Closing it means giving v2
what v1 has: inline caching at prim/method sites, unboxed representations on the numeric paths, and
loop-level compilation rather than per-node dispatch. It should be **specced and priced as a
programme**, not attempted as another slice. `specs/v2-vs-v1-backend-matrix.md` §5.

---

## v1, same defect class

### v2-perf-10 · Six v1 methods are over HotSpot's `HugeMethodLimit`
Someone else's entry (`v1/runtime/backend/js/BUGS.md v1-interpreter-hot-path-never-jits`), listed
here because it is the same defect I fixed in v2 and the same gate finds it:
`ActorScheduler.handleActorOp` 28036, `JsGen.genExpr` 24984, `RustCodeWalk.renderTerm` 16346,
`EvalRuntime.evalCore` 15330, `DispatchRuntime.dispatchList` 14696, `dispatchString` 9839.
`DispatchRuntime.infix2` is already split. **Note that entry's own correction**: v1's bytecode JIT
bypasses these on hot loops (25× measured), so "every INT case pays" is false — the census flags a
hazard, it does not by itself prove a cost.

---

## Refuted — do not redo these

Each was implemented, measured with the alternating protocol, and reverted. They are here because
all three are plausible enough to be tried again.

| Idea | Result |
|---|---|
| Make the arithmetic dispatch inlinable (`arithFastTyped` is 1,423 bytecodes vs `FreqInlineSize` 325, so a constant `op` provably cannot reach its String switch) | Restructured so the whole hot chain is 9 → 199 → 259 and inlinable. **±4%, signs both ways.** The sizes are real; the cost is not. |
| Skip the two single-kind dispatch parts (25% of `lazylist-take`'s profile) | **1.03–1.05×, inside noise.** Reverted also because the guard carries a hazard: a future non-`StrV` arm in part 2 would be silently skipped. |
| `ArraySeq` instead of `Vector` for effect-Op fields | 1.08× on `effect-stream`, ranges overlap. **Kept** — same magnitude as the above but no invariant to violate. The rule that separates them: revert a no-gain change when carrying it costs future attention; keep it when it costs none. |

**Twice now a fat profile frame has not paid out by its weight**: `dataFields` was 28% of a profile
and bought 20%; the dispatch parts were 25% and bought nothing. Failed type tests are near-free once
JIT-compiled — samples mark where the *thread* is, not where the *cost* is. **A hot frame is a place
to look, never a size of prize.**

---

## House rules for this file

1. **Alternating protocol or no number.** On this host a single A/B run of IDENTICAL code has swung
   **2.5×** (`literal-match` measured 0.263 / 0.211 / 0.429 / 0.171 at load 5.5). Three
   before/after rounds, swap only the staged jar, compare medians. Normalising against an unchanged
   control column is **not** sufficient — the control's own JIT swings on the same rows.
2. **Never compare a fresh number against a stale table.** I did exactly that and reported
   `effect-stream` improving 271× → 115×; matched, it was 269× — unchanged. Both columns had fallen
   because the machine was quieter.
3. **Reproduce a "cannot run" claim OUTSIDE the harness before believing it.** Three workloads sat
   in the "v2 cannot do this" category for hours; the defect was the bench wrapper's `0d`.
4. **State the expected size before starting.** Every entry above does.
