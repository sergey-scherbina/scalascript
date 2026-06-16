# spec: jit-collection-ops — bytecode-JIT for Vector / Array / LazyList

Status: **ALL SLICES DONE.** Slice 1 (2026-06-15) — Vector/List/Array indexed READ JITs on the
default JavacJitBackend (`vector-index` 1056 → 1.14 ms, ~925×). Slice 2 (2026-06-16) — Array
read + in-place update (`array-update` 1580 → 0.66 ms, ~2400×), ASM-backend parity for `seq(i)`
+ `array(i)=x` (the `ssc-asm` column also drops), and LazyList pipeline fusion (`lazylist-take`
190 → 0.058 ms, ~3275×) — all on BOTH backends, JIT-on result == JIT-off.
Claim: `jit-collection-ops` (slice 1) / `jit-collection-ops-slice2` (slice 2)
Backends: interp bytecode JIT — `JavacJitBackend` (default) + `AsmJitBackend` (parity) — both DONE.

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

### Slice 2 — Array **read + in-place update** `a(i)` / `a(i)=x` (DONE 2026-06-16)

The receiver is a LOCAL `val a = Array(...)` (no runtime-type discrimination like a global), so the
JIT statically tracks seq-bound locals: `GenCtx.seqLocals` maps a ref-local bound to an
`Array`/`Vector`/`List` ctor → `isArray` flag (set in the val-binding sites via `withSeqLocal`,
classified by `seqCtorKind`). `isRefValRhs`/`walkRef` learned `Array(...)`/`Vector(...)`
(→ `JitRefDispatch.buildArrayRef`/`buildVectorRef`). The indexed READ `a(idx)` routes through the
same `seqIndexLong` recognition (now also accepting a `seqLocal`); the STORE
`Term.Assign(Term.Apply(a, [idx]), rhs)` on an array local emits
`JitRefDispatch.arrayUpdateLong((Object)a, idx, value)` (in-place `IntV` write; throws on a
non-Array receiver → loop bails). `array-update` 1580 → 0.66 ms (~2400×).

### ASM-backend parity (DONE 2026-06-16) — and the real reason slice 1 looked "inert"

The slice-1 ASM seq-index attempt bailed not because "ASM tracks globals differently" but because
the SHARED `JitPredicates.looksLongValue` didn't recognise `seq(idx)` as a Long. Javac's `.toLong`
handling try-walks the inner expr first (so it works without `looksLongValue`), but ASM emits
bytecode eagerly and must shape-gate on `looksLongValue` BEFORE emitting — so `seq(idx).toLong`
mis-routed to the ref fallback and bailed the whole function. Fix: `JitShapeCtx.isSeqIndexName`
(default false; both `GenCtx` override) + a `looksLongValue` case for `seq(idx)`. With that, the
ASM walkLong seq-index emit (`INVOKEVIRTUAL seqIndexLong`) + array-local support engage:
`vector-index` 1003 → 0.87 ms, `array-update` 1473 → 0.69 ms; `ssc-asm` result == `ssc` == tree-walk.

### LazyList — pipeline fusion JIT (DONE 2026-06-16; was declined as "inherent cost")

`lazylist-take`'s body `LazyList.from(start).map(_*2).take(8).sum` is a fresh lazy pipeline per
iteration. The ~35× gap was the LazyList machinery (cons thunks, memoisation), NOT the arithmetic —
so fusing the bounded prefix into a native loop eliminates it. `JitHofShape.lazyFromMapTake`
recognises `LazyList.from(start).map(unary)?.take(n)` (the receiver of a terminal `.sum`; `take`
REQUIRED so an unbounded `.sum` never matches); `JitHofDispatch.lazyFromMapTakeSum` forces only the
n-element prefix in a tight loop (reusing the `unaryLong` op encoding). Both backends emit the fused
call from a guarded `Term.Select(recv, "sum")`; `looksLongValue` knows the shape so
`<pipeline>.sum.toLong` stays numeric. `lazylist-take` 190 → 0.058 ms (~3275×).

NOTE — separately, `LazyList` must FUNCTION on every backend (it was `n/a` on JS/Rust): that
cross-backend work is its own spec `specs/lazylist-all-backends.md`, independent of this interp-JIT.

## Verify

- Assembled jar (`sbt installBin`) — JIT only fires from the real launcher, not `runMain`.
- `./bench.sh --backend ssc vector-index` drops from ~1056 ms toward the numeric-loop floor.
- `SSC_JIT_BYTECODE=off` parity: same result tree-walked.
- Correctness: a hot `seq(i)`-sum loop gives the same Long as tree-walk; JIT on/off agree.

## Behavior checklist

- [x] `seqIndexLong` returns the right element for VectorV / ListV / ArrayV (`JitSeqIndexTest`).
- [x] `vector-index` JITs on JavacJitBackend: 1056 → 1.14 ms (~925×), result unchanged.
- [x] `SSC_JIT_BYTECODE=off` gives the identical result (952395756 both ways).
- [x] `buildArrayRef`/`buildVectorRef`/`arrayUpdateLong` + `lazyFromMapTakeSum`/`lazyFromMapTake`
      unit-guarded (`JitSeqIndexTest`).
- [x] `array-update` JITs on JavacJitBackend: 1580 → 0.66 ms (~2400×); JIT-on == off (198066517).
- [x] AsmJitBackend parity: `vector-index` 1003 → 0.87 ms, `array-update` 1473 → 0.69 ms; result
      == javac == tree-walk (73340330). Root cause (shared `looksLongValue`) fixed, not reverted.
- [x] `lazylist-take` JITs on BOTH backends: 190 → 0.058 ms (~3275×); JIT-on == off (805370800).
- [x] Full clean interp suite green; no regression (shared `looksLongValue` only adds seq/lazy cases).
