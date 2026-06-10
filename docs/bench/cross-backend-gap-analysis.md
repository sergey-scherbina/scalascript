# Cross-backend gap analysis (2026-06-10)

Evidence-backed diagnosis of the remaining cross-backend unevenness, after the
HOF-JIT + JS field-access/nested-loop wins landed. Each conclusion below was
verified with a measurement or probe, not assumed.

## Three kinds of "unevenness"

### 1. Real codegen gaps — honest, fixable (sizeable work)

| Cell | Root cause (verified) | Fix |
|---|---|---|
| **js `map-ops` 1.06 ms** (40×) | `Map.updated` = `new Map(obj)` — a full **O(n) copy** per call → O(n²) over the loop. jvm/rust/interp use persistent HashMaps (structural sharing). Node micro: copy 1.31 ms vs mutable 0.014 ms (**91×**); the `_dispatch` prefix adds ≈0. The copy is 100% of the cost. | Persistent/HAMT immutable map in the JS runtime. Large, touches a core type. |
| **js `hof-pipeline` 0.028 ms** (6×), map/filter/fold closures generally | `x*2`/`x%3` on list elements emit `_arith('*',…)` + a `typeof==='string'` repeat-guard because JsGen can't prove the element is numeric; the `foldLeft` lambda also gets a tuple-destructuring wrapper. Inspected generated JS. | JS numeric type inference: track `List[Int]` element types → type map/filter/fold closure params numeric → native ops (same family as the landed `instance-field` fix). Medium. |

### 2. Interpreter JIT is cleverer than the AOT backends — honest, not a bug

Cases where **a compiled backend is slower than the tree-walking interpreter**:
`jvm list-fold 0.075` (interp 0.006), `jvm range-sum 0.013` (interp 0.004),
`jvm mutual-recursion 3.82` (interp 1.22). The interpreter's run-time JIT applies
optimizations the AOT codegen backends don't:

- **invariant-accumulation hoist** (`list-fold`: `xs.foreach(x => sum += x)` with
  `xs` loop-invariant → the inner sum is hoisted out of the outer loop);
- **range map-fold fusion** (`range-sum`);
- **mutual-tail-call trampolining** (`mutual-recursion`: Scala/JVM does *no* mutual
  TCO, so jvm makes 1M real stack calls; the interp JIT loops it).

All produce correct results — the AOT backends just leave the optimization on the
table. Fixing = porting these passes into `JvmGen`/rust codegen (per-case work).

### 3. Measurement artifacts — folds (the honesty caveat)

Several compiled sub-µs numbers are **not real work** — HotSpot/LLVM/V8 folded the
pure, loop-invariant workload to a compile-time constant:

- `jvm instance-field` **0.000326 ms** — verified a fold: adding a per-iteration
  `Bench.opaque` barrier raised it to **0.0079 ms** (24×, the honest cost of 10k
  `normSq` calls). Same shape for `jvm tuple-monoid` (2 ps!), `bool-predicate`,
  `either-chain`, `option-chain`, `literal-match`, and several rust/js cells.
- **NOT folds** (checked): `typeclass-monoid` (no loop, opaque inputs — 5 ns is
  real); `arith-loop`/`nested-loop`/`pattern-match-heavy`/`list-fold` (jvm runs
  the real loop).

So for the folded rows the table compares fake-fast compiled numbers against the
interpreter's honest (it *cannot* fold) numbers — not apples-to-apples.

#### Why we can't just sprinkle `Bench.opaque` to "fix" it

`Bench.opaque` is the right barrier for the AOT backends (jvm `@volatile` branch,
rust `std::hint::black_box`). But on the **interpreter** it is catastrophic:
wrapping a loop result in `Bench.opaque` makes the interpreter's JIT **bail to
tree-walk** (the specialised while-loop / FastTier / bytecode matchers don't
recognise the `opaque` call), and adds a slow native dispatch per call. Measured:

- `interp instance-field` 0.0068 → **31.7 ms** with an opaque-wrapped result;
- `interp arith-loop` 0.287 → **3043 ms** (10,600×) with `Bench.opaque(i)`.

So a naïve "de-fold every workload with `Bench.opaque`" honesty pass would make the
**interpreter** look ~10,000× slower — the opposite of honest. A correct honesty
pass needs *either* (a) make `Bench.opaque` JIT-transparent across **all** interp
JIT pattern-matchers (a large, fragile change — a single `VmCompiler.compileInto`
identity case is NOT enough; the while-loop matchers bail first), *or* (b)
redesign each foldable workload to use loop-**varying** data so no backend can
fold and no barrier is needed (per-workload benchmark-design work, changes what is
measured). Both are real projects, not a quick edit. Until then: **read the
sub-µs compiled cells as folds, not as honest throughput.**

## Recommended order of attack
1. **JS numeric type inference** (#1b) — cleanest real win, several JS cells.
2. **Honesty: redesign folded workloads to varying data** (#3b) — or land
   JIT-transparent `Bench.opaque` (#3a) first, then add barriers.
3. **JS persistent map** (#1a) — biggest single gap (map-ops 40×), but a core-type
   change with real regression risk.
4. **AOT codegen passes** (#2) — invariant hoist / range fusion / mutual-TCO in
   `JvmGen`/rust to match the interp JIT.
