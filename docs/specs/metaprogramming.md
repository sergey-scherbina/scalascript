# Metaprogramming MVP — `inline` + `derives`

Status: **design / planning**.  Implementation tracked as
**v1.14 — Metaprogramming MVP** in MILESTONES.md.  Companion to
[`docs/specs/final-tagless.md`](final-tagless.md) (v1.13 prerequisite —
`using` resolution and `Mirror` typeclass) and the broader
metaprogramming follow-ups (user-defined macros, deferred to v2.x).

This document is the source of truth for ScalaScript's
metaprogramming surface in v1.x: what's in scope, what's
deliberately deferred, and why.

## 1. Scope decision

ScalaScript's metaprogramming story for v1.x is **deliberately
narrow**:

- ✅ **`inline` keyword** — compile-time inlining, constant
  folding, type-level matching.  Necessary infrastructure;
  unlocks everything else.
- ✅ **`derives` mechanism for blessed typeclasses** — Tier 1
  recipes (`Eq`, `Show`, `Hash`, `Order`) plus a handful of std
  typeclasses where derivation is mechanical (`Foldable`,
  `Traversable`, `Functor`).
- ❌ **User-defined macros (`quoted.Expr` / `quotes.reflect`)** —
  out of scope.  ~3-4 months of work across three backends for
  a feature with a relatively small audience; revisit in v2.x if
  concrete consumers surface.

Why this split:

- `inline` is the **gateway**.  Every `derives` implementation
  internally uses `inline match` over `Mirror.Of[A]` to walk
  product/sum type structure at compile time.  Without `inline`,
  `derives` either becomes heavy runtime reflection or stays
  unimplemented.
- `derives` is the **ergonomic payoff**.  Writing
  `case class Person(name: String, age: Int) derives Eq, Show` is
  the single biggest UX win in the metaprogramming space; covers
  ~80% of what real users want from "macros."
- User-defined macros are the **expensive 20%**.  Custom typeclass
  derivation, schema generators, DSL processors — all valuable,
  none in the critical path.  Deferring keeps the v1.x cycle
  closable.

## 2. `inline` — what it adds

The user-visible surface follows Scala 3 verbatim:

| Form | Meaning |
|------|---------|
| `inline def f(x: Int): Int = x + 1` | Body inlined at call site; arguments may also be `inline` |
| `inline val n: Int = 42` | Compile-time constant; `n` is a `42` literal everywhere it's referenced |
| `inline if cond then a else b` | Fold to `a` or `b` at compile time if `cond` is `inline`-known |
| `inline match` | Pattern match resolved at compile time; only the matching branch survives |
| `compiletime.constValue[T]` | Lift a literal type back to a value: `constValue["foo"]: String = "foo"` |
| `compiletime.summonInline[T]` | `summon[T]` resolved at compile time; compile error if no instance |
| `compiletime.error("msg")` | Emit a compile error from within `inline` code |

`inline` does NOT add:

- Arbitrary AST manipulation (`quoted.Expr` / `quotes.reflect`)
- Macro hygiene primitives (`quotes.symbol`, fresh-name generation
  beyond what the typer does)
- Code generation from strings (`compiletime.parse`)

These are the user-defined macros bucket — deferred.

### 2.1 Worked example — type-level constant

```scala
inline def stringLengthOf[N <: Int & Singleton]: Int =
  compiletime.constValue[N]

val len = stringLengthOf[4]   // val len: Int = 4 — folded at compile time
```

### 2.2 Worked example — `inline match` for typeclass dispatch

```scala
inline def show[T](t: T): String =
  inline t match
    case s: String  => s
    case n: Int     => n.toString
    case b: Boolean => if b then "yes" else "no"
    case _          => compiletime.error("show: unsupported type")

show("hi")    // "hi"
show(42)      // "42"
show(true)    // "yes"
show(3.14)    // compile error
```

### 2.3 Worked example — `summonInline` for zero-cost typeclass

```scala
inline def encode[T](t: T): String =
  val encoder = compiletime.summonInline[Encoder[T]]
  encoder.run(t)

// Encoder[Int] is resolved at compile time; encode(42) generates
// a direct call to the Int encoder with no runtime summon.
```

## 3. `derives` — what it adds

Standard Scala 3 `derives` syntax:

```scala
case class Person(name: String, age: Int) derives Eq, Show
sealed trait Result derives Show
case class Ok(value: String)  extends Result
case class Err(message: String) extends Result
```

Compiler synthesises `given Eq[Person]`, `given Show[Person]`,
`given Show[Result]` via the typeclass companion's
`inline def derived[T]` method.

### 3.1 How a typeclass becomes derivable

```scala
trait Eq[A]:
  def eqv(a: A, b: A): Boolean

object Eq:
  // Standard Mirror-based derivation; lives in the typeclass companion
  inline def derived[A](using m: deriving.Mirror.Of[A]): Eq[A] =
    inline m match
      case s: deriving.Mirror.SumOf[A]     => sumEq[A](using s)
      case p: deriving.Mirror.ProductOf[A] => productEq[A](using p)

  inline def productEq[A](using p: deriving.Mirror.ProductOf[A]): Eq[A] =
    val instances = summonAllInstances[p.MirroredElemTypes]
    new Eq[A]:
      def eqv(a: A, b: A): Boolean =
        // walk product fields via Mirror, compare with the per-field Eq
        ...

  inline def sumEq[A](using s: deriving.Mirror.SumOf[A]): Eq[A] =
    val ordinals = (a: A) => s.ordinal(a)
    val instances = summonAllInstances[s.MirroredElemTypes]
    new Eq[A]:
      def eqv(a: A, b: A): Boolean =
        ordinals(a) == ordinals(b) && /* recurse into matching variant */
```

### 3.2 Tier 1 — recipes in v1.14

Each is mechanical; each ships as part of v1.14:

| Typeclass | Behaviour for product | Behaviour for sum |
|-----------|------------------------|--------------------|
| `Eq` | All fields equal | Same variant + matching field-eqv |
| `Show` | `Foo(a=…, b=…)` | Variant name + product representation |
| `Hash` | `(field hashes ##)` over fields | Variant index + product hash |
| `Order` | Lexicographic over fields, declaration order | Variant ordinal then field-wise |

### 3.3 Tier 2 — std typeclasses (v1.14 Phase 4)

Wires `derives` for typeclasses where derivation is well-defined:

- `derives Foldable`    — walk fields in declaration order
- `derives Traversable` — combine field-wise traversal with the F
- `derives Functor`     — map over the *last* type-parameter
  (Scala 3 convention)

`derives Monoid`, `derives Semigroup`, `derives Codec` are
**Tier 2** — defer to v1.14.1.  They require a different
derivation shape (combining-monoid-from-monoid-of-fields), which
is well-trodden but adds complexity orthogonal to Tier 1.

## 4. Implementation phases (v1.14)

### Phase 1 — `inline` evaluation (~5 days)

- Parser accepts `inline` modifier on `def` / `val` / `if` /
  `match`.  AST nodes annotated `isInline = true`.
- New `core/transform/InlineEvaluator.scala`: traverses
  inline-marked nodes, folds known values, expands inline calls
  at the call site.
- Implement `compiletime.constValue[T]`, `summonInline[T]`,
  `error("msg")` as compile-time primitives.
- Run before the existing typer's normalization pass.

### Phase 2 — `inline` cross-backend verification (~3 days)

The compile-time evaluator runs in `core/` before backend split;
each backend sees fully-inlined code.  This phase verifies that
INT / JS / JVM produce identical observable output for the
six `inline` conformance tests.

### Phase 3 — `derives` Tier 1 recipes (~5 days)

- Parse `derives Type1, Type2` clause on case classes and
  sealed traits.  Desugar to `given Eq[Person] = Eq.derived[Person]`
  appended to the companion.
- Implement `deriving.Mirror.Of[T]` typeclass machinery —
  `Mirror.ProductOf` / `Mirror.SumOf` exposing `MirroredLabel`,
  `MirroredElemTypes`, `MirroredElemLabels`, `fromProduct(...)`,
  `ordinal(...)`.
- Ship `Eq` / `Show` / `Hash` / `Order` derivation methods on
  their companions.

### Phase 4 — `derives` for std typeclasses (~3 days)

- Wire derivation for `Foldable`, `Traversable`, `Functor`.
- Convention: `derives Functor` picks the **last** type parameter
  of a multi-param case class (matching Scala 3 convention).

### Phase 5 — Conformance + std polish (~2 days)

- Six `inline` conformance tests (`inline-constant.ssc`,
  `inline-if.ssc`, `inline-match.ssc`, `inline-summon.ssc`,
  `inline-error.ssc`, `inline-cross-backend.ssc`).
- Five `derives` conformance tests (one per Tier 1 typeclass +
  one combined `derives Eq, Show, Hash`).
- Optionally: rewrite `examples/typeclass.ssc` to use `derives`
  where applicable; same observable behaviour, less boilerplate.

## 5. Coexistence with the rest of the stack

| Mechanism | When it kicks in | Authoring style |
|-----------|------------------|-----------------|
| **`inline` evaluation** (this doc) | Compile time, before backend split | Authored in user code with `inline` modifier |
| **`derives` Tier 1** (this doc) | Compile time; instance generated in companion | Authored as `derives X, Y, Z` clause |
| **Final Tagless** (v1.13) | Runtime; `using`-resolved at call site | Authored as `[F[_]: M]` context bounds |
| **`Free[F, A]`** (v1.11.5) | Runtime; program-as-data | Authored as `Free.liftF` / `flatMap` |
| **Direct syntax** (v1.8) | Compile time → for-comprehension; runtime monadic | Authored as `direct[F] { … }` blocks |

**Synergy with v1.13 FT**: `derives Eq` for case classes
provides the structural equality that `Eq[F[A]]`-using FT code
needs.  Without `derives` you write the `Eq` instance by hand for
every domain type; with `derives` it's one keyword.

**Synergy with v1.11.5 Free**: `Free.foldMap[F, G]` requires a
`Monad[G]` — `derives Monad` is **not** in Tier 1 (the
derivation rule is non-trivial), but Tier 2 (v1.14.1) opens
that path.

**Synergy with v1.8 direct syntax**: `inline def show[T](t: T):
String = inline t match …` — direct syntax bodies can reference
inline-defined typeclass dispatch with zero runtime cost.

## 6. Hard-no list (closed by design)

| Feature | Reason |
|---------|--------|
| **User-defined macros (`quoted.Expr`, `quotes.reflect`)** | Out of scope; defer to v2.x |
| **`inline def` with side effects** | Must be referentially transparent at compile time |
| **Custom `derives` recipes from outside std** | Only blessed typeclasses (Tier 1 + Tier 2) auto-derive in v1.14; user-defined recipes wait for full macros |
| **Type-level naturals / Peano arithmetic** | `inline` is pragmatic, not Haskell-grade type-level |
| **String concatenation of generated code** (`compiletime.parse("def foo = 42")`) | Replaces all the safety `inline` gives you with text manipulation; not in scope |
| **Reading external files at compile time** | `inline def stringFromFile(path: String): String` — a real macro concern; defer |

## 7. Open questions

These do **not** block v1.14 — note and decide when first
relevant usage emerges.

- **`inline if` folding semantics.**  When does the typer know
  enough to fold?  Heuristic: when both branches are
  `inline`-computable values, and the condition reduces to a
  literal `Boolean`.  Mirror Scala 3.

- **`inline match` exhaustiveness checks.**  Standard Scala 3
  warns on non-exhaustive inline matches.  Should we be stricter
  (error)?  Probably yes, since unreachable code in inline
  expansions causes confusing compile failures downstream.

- **Cross-file `derives`.**  `case class Foo(...) derives Eq` where
  `Eq` is imported from std.  Should work given v1.13's cross-
  file trait inheritance — verify in Phase 5 conformance.

- **`derives` source-order semantics for multi-param case classes.**
  `case class Pair[A, B](a: A, b: B) derives Functor` — which
  type-param does `Functor` map over?  Scala 3 picks the last
  (`B`).  We follow.

- **Performance of `inline summon` vs runtime `summon`.**  In
  principle a 1-instruction direct call vs an O(n) instance
  lookup.  Bench when v1.14 lands; if the win is real, evangelise
  `summonInline` in std hot paths.

- **`inline` and `Mirror.Of` interaction with the JVM backend.**
  Scala 3 compiles `inline` and `derives` via `dotty.tools.dotc`;
  our JvmGen-emitted Scala source already supports these
  directly.  Likely no extra work on JVM; verify Phase 2.

- **Tier 2 derivations.**  `derives Monoid`, `derives Semigroup`,
  `derives Codec`.  Each has a non-trivial derivation rule
  (combining-from-fields).  Defer to v1.14.1 — implement when
  std uses justify them.

- **Custom error messages for failed derivations.**
  `derives Show` on a case class containing `Function1` should
  fail with "no Show[Function1[…]]" not a giant Mirror trace.
  Wire via `compiletime.error` in the `derived` body.

- **Discoverability for users.**  `derives` is unfamiliar to
  non-Scala-3 backgrounds; tutorial in `examples/derives-
  intro.ssc` once v1.14 ships.

- **Compile-time evaluation budget.**  How expensive can an
  inline computation be before the compiler should give up?
  Scala 3 has a configurable limit; mirror.

## 8. Conformance plan

### `inline` tests (6)

| Test | Exercises |
|------|-----------|
| `inline-constant.ssc` | `inline val`, `compiletime.constValue` |
| `inline-if.ssc` | `inline if` over known/unknown condition |
| `inline-match.ssc` | `inline match` over type, exhaustive + non-exhaustive |
| `inline-summon.ssc` | `compiletime.summonInline[T]` |
| `inline-error.ssc` | `compiletime.error("msg")` at the right call site |
| `inline-cross-backend.ssc` | Same `inline def` across INT/JS/JVM, identical output |

### `derives` tests (5)

| Test | Exercises |
|------|-----------|
| `derives-eq.ssc` | `case class A derives Eq` for product + sum |
| `derives-show.ssc` | `derives Show` rendering matches expected format |
| `derives-hash.ssc` | `derives Hash` produces consistent hashes |
| `derives-order.ssc` | `derives Order` lexicographic ordering |
| `derives-combined.ssc` | `derives Eq, Show, Hash` — multiple clauses on one type |
| `derives-foldable.ssc` | Phase 4: `derives Foldable` on parameterised case class |

Each test runs on all three backends; observable output must
match exactly.

## 9. Deferred to v2.x

The user-defined macro story.  When and if it lands:

- Full `quoted.Expr[T]` / `quotes.reflect.*` API à la Scala 3.
- Per-backend macro execution (INT runs in-process; JS / JVM
  cross-compile to host macro at scalac level).
- Hygiene primitives: fresh-name generation, symbol comparison,
  splice safety.
- `Mirror`-driven derivation recipes for user-defined typeclasses
  (vs Tier 1 / Tier 2 blessed list).
- Compile-time effect tracking — `inline def` with monadic
  return type, lifted into `Free` at compile time.

None of this is committed.  Tracking the door so it's clear what
v1.14 *doesn't* address.
