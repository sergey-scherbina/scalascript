# v2-bench-compat — v2 full compatibility with v1 bench corpus

**Goal:** `v2/scripts/bench.sh` runs all 31 programs from `bench/corpus/` on v2,
producing wall-clock ms/op numbers comparable to v1's `bench.sh` (JMH).

## Architecture

The v2 pipeline for bench programs:

```
bench/corpus/foo.ssc
  → extract scalascript block
  → prepend "def main(): Unit = println(workload())" (or use existing main)
  → java -jar v2.jar run bin/ssc1c.ssc0 /dev/stdin   → Core IR
  → java -jar v2.jar run-ir /dev/stdin               → output
```

The bench harness times N=20 cold runs per program, reports median ms/op (no
JVM warmup — v2 has no JIT, so cold = steady-state). A side-by-side table
compares v1 JMH numbers (from bench/BASELINE.md) with v2 wall-clock medians.

## Milestones

### KV1 — ssc1c parser fixes for bench corpus syntax (P0)

The bench corpus uses Scala 3 syntax features not yet handled by ssc1c:

| Feature | Example | Fix |
|---|---|---|
| Long literals | `0L`, `2862933555777941757L` | lex `\d+L` as int literal, strip `L` |
| Scala 3 `while ... do` | `while i < n do\n  body` | parse `do` after cond as block start |
| `.toLong` / `.toInt` | `(s % 7).toLong` | identity cast (v2 VM is 64-bit) |
| `.toDouble` / `.toFloat` | `n.toDouble` | identity for now (no Float yet) |
| Scala 3 `if ... then` (no braces) | `if n > 0 then a else b` | already works? verify |
| `def f(): Long =` return type | type annotation | already ignored |

Files: `v2/lib/ssc1-front.ssc0` (lexer + parser).

**Programs unlocked:** arith-loop, bool-predicate, nested-loop, recursion-fib,
recursion-tco, mutual-recursion, literal-match, string-concat (= 8 programs).

### KV2 — v2 bench harness (P0)

`v2/scripts/bench.sh`:
- Builds v2.jar once via `scala-cli package`
- For each program in corpus: extracts scalascript block, appends main-wrapper
  if no `def main` exists, compiles+runs N=20 times, reports median ms/op
- Prints a markdown table: program | v2-ms | v1-ms (from BASELINE.md) | ratio
- Skips programs that fail with `SKIP (unsupported feature: ...)` message

### KV3 — stdlib: List, Either, Option (P1)

Implement as ssc0 prelude auto-injected by ssc1c before user code:
- `List(a,b,c)` → `Cons(a, Cons(b, Cons(c, Nil)))`
- `.map`, `.filter`, `.foldLeft`, `.length`, `.head`, `.tail`, `.isEmpty`
- `Some(x)`, `None`, `Option.apply`
- `Right(x)`, `Left(x)`, `.map`, `.flatMap`, `.fold`, `.getOrElse`

Also lower these selector-method calls in ssc1c: `xs.map(f)` → `_sel_map(xs)(f)`
(ssc1c already has `_sel_map` etc. in its prelude — wire them up).

**Programs unlocked:** list-fold, hof-pipeline, either-chain, option-chain (= 4 programs).

### KV4 — stdlib: case class, sealed trait (P1)

- `case class Foo(a: T, b: T)` → constructor + field selectors `Foo_a`, `Foo_b`
- `sealed trait Bar` → supertype tag (for pattern matching by constructor)
- Pattern `case Foo(a, b) =>` → match on constructor + bind fields

**Programs unlocked:** instance-field, pattern-match-heavy, tuple-monoid (= 3 programs).

### KV5 — stdlib: String methods (P1)

In ssc1c prelude / intrinsics:
- `s.split(sep)` → `#split(s, sep)` → List[String]
- `s.trim` → `#strim(s)`
- `s.toInt` → `#str->i(s)` (already exists as `__str_toInt`)
- `s.mkString(sep)` → join list with sep
- `xs.mkString(sep)` → List[String] → String

**Programs unlocked:** string-split (= 1 program).

### KV6 — stdlib: Range, Array, LazyList (P2)

- `0 until N` / `0 to N` → Range in ssc0 (iterator-like)
- `xs.sum` → foldLeft(0)(_ + _)
- `Array(...)` → mutable list/vector
- `a(i) = x` → array update
- `LazyList.from(n)` → infinite lazy list in ssc0
- `.take(n)` on LazyList

**Programs unlocked:** range-sum, streams-pipeline, array-update, lazylist-take,
vector-index (= 5 programs).

### KV7 — stdlib: Map (P2)

- `Map(k -> v, ...)` → structural map
- `.get(k)`, `.updated(k, v)`, `.getOrElse(k, default)`
- `Map.empty`

**Programs unlocked:** map-ops (= 1 program).

### KV8 — effects in ssc1c (P2)

Wire ssc1c lowering for algebraic effects (the Core IR already supports them
via the v2 VM's effect primitives — this is just the parser/lowerer side):
- `effect Foo:` + `def op(): T` → effect declaration
- `Foo.op()` → perform
- `handle { ... } { case Foo.op() => resume(...) }` → handle expression
- `multi effect` → multi-shot variant

**Programs unlocked:** effect-oneshot, effect-multishot, effect-pure,
effect-stream, streams-pipeline (= 5 programs).

### KV9 — typeclasses in ssc1c (P2)

- `trait TC[A]:` → typeclass declaration (erased to dicts)
- `given name: TC[Int] with` → dictionary value
- `using TC[A]` parameter → dict parameter
- `summon[TC[A]]` → dict lookup
- `[A: TC]` context bound → sugar for `using TC[A]`

**Programs unlocked:** typeclass-fold, typeclass-monoid (= 2 programs).

### KV10 — type lambdas in ssc1c (P3)

- `type Alias[X] = F[X]` and `[X] =>> F[X]` — already in v2 type system
- `Either[_, Int]` placeholder syntax
- These are type-level features that erase at runtime

**Programs unlocked:** type-lambda-native, type-lambda-placeholder (= 2 programs).

### KV11 — bench comparison + docs (P3)

- Run `bench.sh` (v1 JMH) and `v2/scripts/bench.sh` (v2 wall-clock)
- Document side-by-side in `docs/v2-bench-comparison.md`
- Update BASELINE.md with v2 column

## Coverage tracker

| Program | Needs | KV |
|---|---|---|
| hello-world | — | KV1 |
| arith-loop | `0L`, `while...do` | KV1 |
| bool-predicate | `while...do`, `&&` | KV1 |
| mutual-recursion | — | KV1 |
| recursion-fib | — | KV1 |
| recursion-tco | — | KV1 |
| literal-match | — | KV1 |
| nested-loop | `while...do` | KV1 |
| string-concat | — | KV1 |
| list-fold | `List`, `.foldLeft` | KV3 |
| hof-pipeline | `List`, `.map/.filter/.foldLeft` | KV3 |
| either-chain | `Either`, `.map/.flatMap/.fold` | KV3 |
| option-chain | `Option`, `.flatMap/.map/.getOrElse` | KV3 |
| instance-field | `case class` | KV4 |
| pattern-match-heavy | `sealed trait`, `case class`, `Double` | KV4 |
| tuple-monoid | `(Int,Int)` tuple ops | KV4 |
| string-split | `s.split`, `.trim`, `.toInt` | KV5 |
| range-sum | `0 until N`, `.map/.foldLeft` | KV6 |
| array-update | `Array`, `a(i)=x` | KV6 |
| lazylist-take | `LazyList.from`, `.map.take.sum` | KV6 |
| streams-pipeline | Range + HOFs | KV6 |
| vector-index | `Vector`, indexed access | KV6 |
| map-ops | `Map`, `.get/.updated` | KV7 |
| effect-oneshot | `effect Foo:`, handle | KV8 |
| effect-multishot | `multi effect` | KV8 |
| effect-pure | `effect Logger:` | KV8 |
| effect-stream | `runStream`, `Stream.emit` | KV8 |
| typeclass-fold | `trait`/`given`/`using` | KV9 |
| typeclass-monoid | `trait`/`given`/`using` | KV9 |
| type-lambda-native | `[X] =>> F[X]` | KV10 |
| type-lambda-placeholder | `Either[_, Int]` | KV10 |

## Done-when

`v2/scripts/bench.sh` runs all 31 programs and prints a side-by-side table.
Programs requiring unimplemented features print `SKIP` (not `FAIL`).
At KV9 all non-type-lambda programs are green.
