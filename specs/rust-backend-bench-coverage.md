# Rust Backend — Benchmark Coverage Roadmap

Status: **active / planning**.  16 of 22 bench corpus workloads return
`n/a` on the rust target.  This spec identifies every gap, categorises
them by implementation effort, and maps them to Sprint tasks.

---

## 1. Baseline picture (2026-06-08)

| Workload | Status | Root cause |
|---|---|---|
| `arith-loop` | ✅ 0.000001 ms | — |
| `bool-predicate` | ✅ 0.000097 ms | — |
| `mutual-recursion` | ✅ 0.000002 ms | — |
| `nested-loop` | ✅ 0.000002 ms | — |
| `recursion-fib` | ✅ 1.46 ms | — |
| `recursion-tco` | ✅ 0.000006 ms | — |
| `hello-world` | ❌ `black_box(r)` type mismatch | `workload(): Unit` → `r: ()` but `black_box` can't coerce to `i64` |
| `string-concat` | ❌ `String + i64` type mismatch | `"item-" + n` where n: i64 needs explicit to_string/format |
| `literal-match` | ❌ `.toLong` Term.Select | Method `.toLong` / `.toInt` not emitted |
| `hof-pipeline` | ❌ chained `.map.filter.foldLeft` | Collection method chaining on Vec |
| `list-fold` | ❌ `Term.Block` in foreach | Multi-stmt closure body `{ sum = sum + x }` in foreach |
| `instance-field` | ❌ bare `Vec` type | `case class Vec(x: Int, y: Int)` — actually a case class, not the Vec collection |
| `map-ops` | ❌ `Map[Int, Int]` type | HashMap type + `.updated` / `.getOrElse` |
| `option-chain` | ❌ `Option[Int]` type | Option type + `.flatMap.map.getOrElse` |
| `either-chain` | ❌ `Either[String, Int]` type | Either type + `.map.flatMap.fold` |
| `pattern-match-heavy` | ❌ `Shape` enum type param + multi-stmt while | `case class ... extends sealed trait` + multi-stmt block |
| `range-sum` | ❌ `(0 until 50).map.foldLeft` | Range type + chained iterator |
| `string-split` | ❌ `.split(",").map.foldLeft` | String.split + method chain |
| `tuple-monoid` | ❌ `(Int, Int, Int, Int)` type | Tuple4 type + `++` Tuple concat op |
| `effect-pure` | ❌ `Int ! Logger` type | Algebraic effect type — R.4.2 |
| `effect-stream` | ❌ effect stream pattern | R.4.2 + stream library |
| `typeclass-fold` | ❌ `TypeClasses` capability | R.6 — needs `given`/`summon` lowering |

---

## 2. Gap taxonomy

### Gap A — Numeric type conversions (`.toLong`, `.toInt`, `.toDouble`)

Affects: `literal-match`, `hof-pipeline`, `list-fold`, `option-chain`,
`either-chain`, `map-ops`, `string-split`, `range-sum`, `string-concat` (partially).

ScalaScript bench fixtures use `.toLong` to coerce Int return values to
Long for the `workload(): Long` signature required by the bench harness.

Fix: recognise `Term.Select(expr, Term.Name("toLong" | "toInt" | "toDouble"))` 
in `renderTerm` and lower to `(expr) as i64` / `as i32` / `as f64`.

### Gap B — `hello-world` bench harness incompatibility

The injected `main.rs` wraps the workload result in `std::hint::black_box(r)`.
For `workload(): Unit`, `r` is `()` — `black_box<()>` compiles fine, but the
harness expects `_run_workload() -> i64`.  The bench harness injects its own
`main.rs` (see `bench/run.sc`), so the fix belongs in how the rust backend
wraps a Unit-returning workload for benchmarking.

Fix (two options):
  **A.** Add a fallback in `_run_workload`: when `workload(): Unit`, emit a
  `workload(); 0` body so `_run_workload` still returns `i64`.  This requires
  the bench harness to detect the return type OR the workload to always return
  `Long`.
  **B.** Emit a `0i64` sentinel when the bench harness detects `workload()`
  returns `()`.  Fix belongs in `bench/run.sc` injection, not the compiler.

Recommended: **B** — the bench harness already customises `main.rs`; add a
sentinel `let r = generated::ssc_program::workload(); let _ = r; 0i64` when
`workload` is `Unit`.

### Gap C — String + non-String (string-concat)

`"item-" + n` where `n: i64` fails because Rust `String::add` only accepts
`&str`.  Fix: in `renderTerm` for `Term.ApplyInfix(lhs, "+", rhs)`, when one
operand is a `String` literal and the other is numeric, lower to
`format!("{}{}", lhs, rhs)`.

### Gap D — Method calls on `Term.Select` (chain methods)

`classify(i).toLong`, `xs.map(f).filter(g).foldLeft(z)(h)` — currently all
`Term.Select` on unrecognised methods fall through as "unsupported expression".

Affected methods by priority:
  - `.toLong` / `.toInt` / `.toDouble` — numeric coercions (Gap A)
  - `.foreach(f)` — iteration over Vec (list-fold, pattern-match-heavy)
  - `.map(f)` / `.filter(f)` / `.foldLeft(z)(f)` on Vec — hof-pipeline,
    range-sum, string-split
  - `.split(sep)` on String → Vec<&str> / Vec<String> — string-split
  - `.trim` / `.toInt` on String — string-split
  - `.updated(k, v)` / `.getOrElse(k, d)` on HashMap — map-ops
  - `.flatMap(f)` / `.map(f)` / `.getOrElse(d)` on Option — option-chain
  - `.map(f)` / `.flatMap(f)` / `.fold(l, r)` on Either — either-chain
  - `.len()` already works; `.length` maps to `.len()`

### Gap E — Collection type support

New types needed in `mapType`:
  - `Option[T]` → `Option<T>` with idiomatic methods
  - `Map[K, V]` → `std::collections::HashMap<K, V>`
  - `Either[L, R]` → `Result<R, L>` (or a custom `enum Either<L, R>`)
  - Range (`until`, `to`) → Rust range `(0..N)` or `..(=N)`

### Gap F — `case class ... extends sealed trait` (pattern-match-heavy)

The bench uses `sealed trait Shape` + `case class Circle extends Shape` —
Scala 3 style. RustCodeWalk currently handles `enum Shape { case Circle(r) }`
but not `sealed trait` + `case class` ADTs.

Fix: collect `sealed trait` + `case class ... extends Trait` patterns and
lower them to the same Rust enum representation.

### Gap G — Multi-statement closures in foreach position

`xs.foreach(x => { sum = sum + x })` — the closure body is a `Term.Block`
with a single assignment. The Block body of a closure is already handled in
`renderClosure` but only when the closure result is not Unit. The problem is
the closure is passed directly to `foreach` which is not yet a known method.
Prerequisite: `foreach` method on `Vec<T>` (Gap D).

### Gap H — Tuple types and `++` concat (tuple-monoid)

`(Int, Int, Int, Int)` type + `(a, b) ++ (c, d)` — the `++` operator on
tuples is a ScalaScript extension that concatenates two tuples into a wider
one.

Fix: map `Type.Tuple(elems)` to Rust tuple `(T1, T2, …)` in `mapType`.
For `++`, lower `lhs ++ rhs` when both are tuple literals to a flattened
tuple `(lhs.0, lhs.1, …, rhs.0, rhs.1, …)`.

### Gap I — TypeClasses (typeclass-fold)

`trait Semigroup[A]` + `given intSum: Monoid[Int]` + `summon[Monoid[A]]` —
requires generic trait objects, `given` instances, `summon` lowering.  This
is a fundamentally different code-generation challenge that deserves its own
R.6 slice.

### Gap J — Algebraic effects (effect-pure, effect-stream)

`Int ! Logger` effect type and stream operations.  Requires R.4.2 lowering
(Perform/Handle/Resume IR nodes).

---

## 3. Implementation priority order

| Priority | Gap | Benchmarks unlocked | Effort |
|---|---|---|---|
| P0 | A — `.toLong`/`.toInt`/`.toDouble` | literal-match, hof-pipeline, option-chain, either-chain, map-ops, string-split, range-sum | XS |
| P0 | B — hello-world bench harness fix | hello-world | XS (bench/run.sc) |
| P0 | C — String + numeric concat | string-concat | XS |
| P1 | D.foreach — Vec.foreach | list-fold (partial) | S |
| P1 | D.map/filter/foldLeft — Vec methods | hof-pipeline, range-sum (partial), string-split (partial) | M |
| P1 | G — multi-stmt closure bodies in foreach | list-fold (full), pattern-match-heavy (partial) | S |
| P2 | F — sealed trait + case class ADT | pattern-match-heavy | M |
| P2 | H — Tuple type + `++` | tuple-monoid | S |
| P2 | E.Option — Option[T] + methods | option-chain | M |
| P3 | E.HashMap — Map[K, V] + methods | map-ops | M |
| P3 | E.Either — Either[L, R] + methods | either-chain | M |
| P3 | Range — `(0 until N)` | range-sum (full) | S |
| P3 | D.String.split/trim/toInt | string-split (full) | M |
| P4 | I — TypeClasses | typeclass-fold | XL (R.6) |
| P4 | J — Algebraic effects | effect-pure, effect-stream | XL (R.4.2) |

**Quick wins (P0 alone unlock 7/16 n/a benchmarks).**

---

## 4. Bench coverage target

After P0+P1: 12/22 green (from current 6/22).
After P0+P1+P2: 16/22 green.
After P0+P1+P2+P3: 20/22 green.
After P0+P1+P2+P3+P4: 22/22 green (requires R.4.2 + R.6).
