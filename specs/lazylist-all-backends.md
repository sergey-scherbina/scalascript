# spec: lazylist-all-backends — `LazyList` works on every backend

Status: DONE (2026-06-16) — `LazyList` now functions on all 5 backends. `lazylist-take`:
ssc 198 / ssc-asm 197 / jvm 5.6 / **js 8.75 (was n/a)** / **rust 0.083 (was n/a)**.
Claim: `lazylist-all-backends`
Backends: interp ✓ + JVM ✓ (already real & lazy) · JS ✓ (NEW) · Rust ✓ (NEW)

## Motivation

`LazyList` is real & lazy on interp (`Value.LazyListV` over Scala's `LazyList`) and JVM
(raw-emit). But it is **`n/a` on JS** (emitted as an eager array; `LazyList.from`/`iterate`/
`continually` are undefined → crash) and **`n/a` on Rust** (no LazyList at all). Unlike Vector
and Array, `LazyList` does not work everywhere. It must — laziness/infinite streams included.

## Design

### JS — thunk-based lazy runtime (`_lz*`)

A memoised lazy cons: `{ _lazy:true, _nil, head, tail() }` where `tail` forces+memoises once.

- Builders: `_lzFrom(n)` (infinite), `_lzIterate(seed, f)`, `_lzContinually(f)`, `_lzRange(a,b[,s])`,
  `_lzFromArray([…])`. `LazyList` object gets `.from/.iterate/.continually/.range/.tabulate`;
  `LazyList(a,b,c)` → `_lzFromArray`.
- Lazy ops (stay lazy, build a new cons whose tail defers): `map`, `filter`, `filterNot`,
  `take`, `drop`, `takeWhile`, `dropWhile`, `flatMap`, `zipWithIndex`, `++`/`concat`, `prepended`.
- Force ops: `toList`/`toSeq`/`toVector`/`toArray` (→ array), `head`, `headOption`, `tail`,
  `isEmpty`, `nonEmpty`, `sum`, `length`/`size`, `foreach`, `mkString`, `find`/`exists`/`forall`
  (short-circuit), `apply(i)`.
- `_dispatch`: a `v._lazy` branch routing the above. `_show(_lazy)` → `LazyList(<not computed>)`
  (matches interp/JVM; forced elements not tracked — acceptable, tests use `.toList`).
- JsGen: `LazyList(...)` → `_lzFromArray([...])`; `LazyList.from`/etc resolve to the `LazyList`
  runtime object. Element fns must be pure (JS closures; effects in a lazy map are out of scope).

### Rust — std lazy iterators

Rust iterators are already lazy, a natural fit. Emit the LazyList chain as an iterator expression
forced at the end:
- `LazyList.from(n)` → `(n..)`; `LazyList.iterate(s)(f)` → `std::iter::successors(Some(s), |&x| Some(f(x)))`;
  `LazyList.continually(x)` → `std::iter::repeat(x)`; `LazyList.range(a,b)` → `(a..b)`;
  `LazyList(a,b,c)` → `vec![a,b,c].into_iter()`.
- `.map/.filter/.take/.skip(drop)/.take_while/.skip_while/.flat_map/.enumerate(zipWithIndex)` →
  the matching lazy iterator adapters; `.collect::<Vec<_>>()` for `toList`/`toVector`;
  `.sum::<i64>()` for `sum`; `.count()` for `length`; `.nth(i)` for `apply`.
- Scope: a fluent chain that is forced (`.toList`/`.sum`/`.foreach`/…) — the common shape and
  what `lazylist-take` uses. A LazyList stored in a `val` and reused across statements needs a
  boxed-iterator type; deferred (documented) — typical code chains-then-forces.

## Verify

- interp/JVM unchanged (already green: `CollectionRealTypeTest`).
- `lazylist-take` runs on JS + Rust (no longer `n/a`) in `bench/BASELINE.md`.
- Cross-backend (interp==JS==JVM, and rust where it forces): `LazyList.from(1).map(_*2).take(8).toList`
  → `List(2, 4, 6, 8, 10, 12, 14, 16)`; `LazyList.from(1).filter(_%2==0).take(3).toList` → `List(2,4,6)`.
- JS lazy proof: an infinite source with `.take(n).toList` terminates (only n forced).

## Behavior checklist

- [x] JS: `LazyList.from(1).map(x=>x*2).take(3).toList` → `List(2, 4, 6)` (infinite source, terminates).
- [x] JS: `LazyList(1,2,3).toList` → `List(1, 2, 3)`; `println(LazyList(1,2,3))` → `LazyList(<not computed>)`.
- [x] JS: `LazyList.iterate(1)(x=>x*2).take(5).toList`, `LazyList.continually(7).take(3).toList`.
- [x] Rust: `LazyList.from(1).map(...).take(8).sum` compiles + runs as `(1..).map(...).take(8).sum::<i64>()`.
- [x] `lazylist-take` no longer `n/a` on JS (8.75 ms) / Rust (0.083 ms); cross-backend guard added.

Verified: interp == JS == JVM on 7 shapes (`CrossBackendPropertyTest "LazyList lazy combinators"`),
interp == real Scala exactly, `RustGenLazyListTest`, Rust suite 204 green, interp==JS 74 programs
0-skip. FOLLOW-UP (documented, not blocking): a `LazyList` stored in a `val` and reused across
statements on Rust needs a boxed-iterator type — typical code chains-then-forces; the interp tree-
walks the lazy pipeline (perf, not correctness).
