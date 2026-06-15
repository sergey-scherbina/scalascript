# spec: collection-real-type — interpreter uses real Scala collection semantics

Status: DONE (2026-06-15)
Claim: `collection-real-type`
Backends: interp (primary, the explicit ask), JVM (already correct — reference), JS (parity, best-effort)

## Motivation

The ScalaScript interpreter backs *every* sequence type with one `Value.ListV` for
uniform dispatch. After `collection-ctor-aliases` the constructors exist; after the
display-tag work `Vector(1,2,3)` *renders* as `Vector(1, 2, 3)`. The user now wants the
interpreter to **really use** the collection type, not just display it.

Key analysis (agreed with the user): in an **eager** interpreter,
`List`/`Seq`/`Vector`/`IndexedSeq`/`Iterable` are observably **identical** — the only
difference is display, and Scala already treats `Vector(1,2,3) == List(1,2,3)` as `true`
(structural `Seq` equality), which the interpreter's structural `==` already gives. So a
**display tag** (`ListV.collKind`) is the *correct* and sufficient model for those five.

Only **two** collection types have genuinely different runtime semantics:

| type      | real semantics the interp must honor                                  |
|-----------|------------------------------------------------------------------------|
| `Array`   | **mutable** (`a(i) = x`, `a.update(i,x)`) + **reference identity** (`Array(1,2,3) != Array(1,2,3)`) |
| `LazyList`| **lazy** — infinite streams; ops force only what's demanded            |

JVM (raw Scala emit) already gets both for free and is the reference.

## Design

### Display tag (kept, already done) — the 5 eager-equivalent types

`Value.ListV` gains `collKind` (mutable display tag, set on fresh instances via
`withKind`, NOT a case field → equality unchanged, `Vector == List` stays `true`).
`Seq`/`Iterable` → "List"; `Vector`/`IndexedSeq` → "Vector"; `List` → "List".
`DispatchRuntime.stampListKind` preserves the tag through type-preserving ops
(`map`/`filter`/…) and converts on `toVector`/`toIndexedSeq`.

### `Value.ArrayV(items: Array[Value])` — real mutable array

- Construction: `Array(...)`, `Array.fill/tabulate/range/ofDim/empty`.
- **Mutation**: `EvalRuntime` lowers `Term.Assign(Term.Apply(recv, idxs), rhs)` to
  `recv.update(idxs…, rhs)` (Scala's desugaring); `ArrayV.update(i, x)` writes in place.
- **Identity**: case-class `==` compares the `Array[Value]` field by reference →
  `Array(1,2,3) != Array(1,2,3)`, `a == a` true. `sameElements` is structural.
- **Read**: `a(i)` → `apply` → `items(i)` (bounds-checked).
- **Dispatch**: delegate to `dispatchList(items.toList, …)`; sequence-returning
  kind-preserving ops re-wrap as `ArrayV` (`Array.map` returns `Array`); `toList`/`toSeq`/
  `toVector`/`toIndexedSeq` produce a `ListV` with the right tag; `toArray` → `ArrayV`.
- **Display**: readable `Array(1, 2, 3)`. NOTE: this **diverges** from Scala's
  non-deterministic `[I@hash` toString (by design — `[I@hash` is useless and can't be
  cross-backend-asserted). Cross-backend tests use mutation/reads/`mkString`, never
  `println(arr)`.

### `Value.LazyListV(underlying: LazyList[Value])` — real lazy list

Backed by Scala's own `LazyList[Value]` → laziness, memoization, infinite support, AND
**`toString` parity with JVM for free** (`LazyList(<not computed>)` etc. — same as the
raw-emitted JVM program, since both use the real Scala `LazyList`).

- Constructors (lazy): `LazyList(...)`, `LazyList.from(n)`, `LazyList.iterate(seed)(f)`,
  `LazyList.continually(x)`, `LazyList.tabulate(n)(f)`, `LazyList.range(a,b[,step])`,
  `LazyList.empty`. `from`/`continually`/`iterate` are **infinite** and stay lazy.
- Lazy ops: `map/filter/filterNot/take/drop/takeWhile/dropWhile/headOption/tail/
  zipWithIndex/flatMap/++/append` return a `LazyListV` (still lazy).
- Force ops: `toList/toSeq/toVector/toArray/head/sum/length/foreach/mkString/force` —
  force only the demanded prefix. `take(n).toList` forces exactly `n`.
- `#::` cons (best-effort): `EvalRuntime` special-cases `h #:: tail` to defer the RHS
  (Scala's by-name cons) so `def from(n) = n #:: from(n+1)` yields an infinite stream
  without eager recursion. Only the **pure** RHS case; effectful RHS falls back.
- Display: `show(LazyListV(ll)) = ll.toString` → exact JVM parity.

### JS parity (best-effort, secondary)

- `Array`: add indexed-assignment codegen (`a(i) = x` → `a[i] = x`). Arrays already
  mutable. Identity equality NOT matched (JS `_eq` is structural) → not cross-tested.
- `LazyList`: a compact thunk-based lazy list (`_LazyList`) for parity on the common
  `LazyList.from(n).map(_*2).take(k).toList` shape. If too costly, document the limit and
  verify laziness interp-vs-JVM only.

## Verify

- `sbt "backendInterpreter/testOnly *CrossBackendPropertyTest *CollectionRealType*"`
- interp-vs-JVM for laziness/mutation; cross-backend (interp==JS==JVM) for deterministic
  shapes (Vector display, Array mutation+reads+mkString, LazyList `.take(n).toList`).

## Behavior checklist

- [x] `Vector(1,2,3)` prints `Vector(1, 2, 3)`; `.map(_*2)` stays `Vector` (interp/JVM; JS shows base on `.map`).
- [x] `Vector(1,2,3) == List(1,2,3)` is `true`.
- [x] `val a = Array(1,2,3); a(0)=9; a(0)` → `9`; `a.update(1,8); a(1)` → `8`.
- [x] `Array(1,2,3) != Array(1,2,3)`; `val a=Array(1); a == a` → `true` (interp/JVM; JS structural).
- [x] `Array(1,2,3).map(_*2)` is an `Array` (mutable), `.mkString(",")` → `2,4,6`.
- [x] `LazyList.from(1).map(_*2).take(3).toList` → `List(2, 4, 6)` (infinite source; interp/JVM).
- [x] `LazyList(1,2,3).toString` → `LazyList(<not computed>)` (matches JVM; interp/JVM).
- [x] `def from(n:Int):LazyList[Int] = n #:: from(n+1); from(1).take(4).toList` → `List(1,2,3,4)` (interp/JVM).

Verified: `CollectionRealTypeTest` (19 interp cases) + `CrossBackendPropertyTest` "real collection
type" (interp==JS==JVM). interp output matched real Scala / JVM exactly for every shape. JS deferred
(documented): LazyList laziness/combinators (`from`/`iterate`/`continually`) and `#::` are interp/JVM
only; JS Array equality is structural; JS loses the Vector display tag through array-rebuilding ops.
