# spec: jit-collection-ops — bytecode-JIT for Vector / Array (and why not LazyList)

Status: slice 1 DONE (2026-06-15) — Vector/List/Array indexed READ JITs on the default
JavacJitBackend (`vector-index` 1056 → 1.14 ms, ~925×). Slice 2 (Array update) + ASM parity
+ LazyList rationale below.
Claim: `jit-collection-ops`
Backends: interp bytecode JIT — `JavacJitBackend` (default, DONE) + `AsmJitBackend` (parity, follow-up)

## Motivation

The wall-clock dashboard shows the interpreter **tree-walks** the new collection workloads
(`bench/corpus/{vector-index,array-update,lazylist-take}.ssc`): `vector-index` 1056 ms vs jvm
0.51 ms (~2000×), `array-update` 1580 ms (~3000×), `lazylist-take` 198 ms vs 5.6 ms (~35×).
`ssc-asm ≈ ssc`, confirming neither JIT backend compiles these — the loop **bails** the whole
body to tree-walk because the collection op (`v(idx)`, `a(i)=x`, the lazy pipeline) returns
`null` from `walkLong`/`walkStat`. The numeric parts (`s = (s*48271)%…`, `i = i+1`) are already
JIT-able; only the collection op blocks the loop.

## Findings (where each bails)

- `walkLong` lowers `v(idx)` as `Term.Apply(Term.Name("v"), [idx])` → `emitLongCall("v", …)`
  (treats `v` as a function) → `null` → bail. (JavacJitBackend ~line 1042.)
- The receiver's runtime type IS knowable at JIT-compile time for a **global**: the loop is
  compiled only after it's hot, so a top-level `val v = Vector(…)` is already in
  `interp.globals` as a `VectorV`. A **local** val (e.g. `val a = Array(…)` inside the def) is a
  JIT slot whose element type must be tracked separately.

## Scope

### Slice 1 — Vector / List / Array **indexed read** `seq(i)` (this spec, DONE first)

- `JitRefDispatch.seqIndexLong(recv, idx): Long` — `VectorV`/`ListV`/`ArrayV` → element's Long
  (`IntV.x` / double-bits / bool). Mirrors the existing `lastLong`/`headLong` helpers; throws
  `ClassCastException` on a non-seq receiver so a wrong shape bails cleanly (the JIT entry catches).
- `JavacJitBackend.walkLong`: before the `emitLongCall` fallthrough, recognise
  `name(idx)` where `name` is a **seq-typed ref** (`isSeqRefName`: a global that is currently a
  `VectorV`/`ListV`/`ArrayV`, or a tracked ref-local) and one Long arg → emit
  `JitRefDispatch.seqIndexLong((Object)(ref), idx)`.
- `AsmJitBackend`: same recognition, emitting the equivalent `invokevirtual` to `seqIndexLong`.
- Also a ref-returning form (`seqIndexRef`) for `seq(i)` where the element is itself a ref.

### Slice 2 — Array **read + in-place update** `a(i)` / `a(i)=x` (follow-up)

Needs (a) slot-type tracking so the JIT knows local `a` holds an `ArrayV`, and (b) a
`JitRefDispatch.arrayUpdateLong(recv, idx, value)` store op + `walkStat` recognition of
`Term.Assign(Term.Apply(a, [idx]), rhs)`. More involved (local + mutation) → its own slice.

### LazyList — NOT a JIT target (declined, with rationale)

`lazylist-take`'s body is `LazyList.from(start).map(_*2).take(8).sum` — it constructs a fresh
infinite lazy list, maps, takes, and folds **per iteration**. That is not a loop-op to lower;
it is whole-pipeline fusion of Scala's `LazyList` machinery (cons thunks, memoisation) into
bytecode. The 35× gap is the inherent cost of real laziness, not a tree-walk bail that a helper
removes. Left tree-walked; documented here so it isn't re-attempted as a "collection op".

## Verify

- Assembled jar (`sbt installBin`) — JIT only fires from the real launcher, not `runMain`.
- `./bench.sh --backend ssc vector-index` drops from ~1056 ms toward the numeric-loop floor.
- `SSC_JIT_BYTECODE=off` parity: same result tree-walked.
- Correctness: a hot `seq(i)`-sum loop gives the same Long as tree-walk; JIT on/off agree.

## Behavior checklist

- [x] `seqIndexLong` returns the right element for VectorV / ListV / ArrayV (`JitSeqIndexTest`).
- [x] `vector-index` JITs on JavacJitBackend: 1056 → 1.14 ms (~925×), result unchanged.
- [x] `SSC_JIT_BYTECODE=off` gives the identical result (952395756 both ways).
- [~] AsmJitBackend: `walkRef` parity for Vector/Array globals kept; the `walkLong` seq-index
      emission BAILS (ASM tracks top-level-val globals differently than Javac) → reverted to avoid
      shipping an inert/risky path. Documented FOLLOW-UP.
- [x] Slice 2 (Array update) + LazyList rationale recorded (above).
- Full clean interp suite 1820 green; no regression.
