---
title: Tuple Monoid — Heterogeneous Lists and Effect Output Unification
version: v1.60
status: landed 2026-05-28
---

# Tuple Monoid

## Overview

Tuples in ScalaScript form a **monoid** under concatenation:

```
(A, B) ++ (C, D)  =  (A, B, C, D)
()                =  identity
```

The zero-tuple `()` is the canonical representation of `Unit`.  
`Unit` becomes a type alias for `()`, and all effect runners are described
uniformly as `Out(E) ++ (R,)` where `Out(E)` is the effect's output type.

---

## 1. Unit = 0-tuple

```scalascript
type Unit = ()
```

- `()` is both the type and the single value of the 0-tuple.
- `val x: () = ()` — valid; `x` and `()` are identical.
- `def f(): Int` — the `()` in parameter position means "no arguments".
- `def f(): Unit` and `def f(): ()` are identical declarations.

### Internal representation

At the type level `SType.Unit` becomes `SType.Tuple(Nil)` — no longer a
separate `Named("Unit", Nil)`.  At the value level `Value.UnitV` is
retained as a smart alias for `Value.TupleV(Nil)`.

**Backward compatibility:** everywhere the identifier `Unit` appears in type
annotations or extern declarations it normalizes to `Tuple(Nil)` during
parsing.  The string `"Unit"` in `.scim` artifacts and the surface syntax
`Unit` both round-trip correctly.

---

## 2. Concatenation `++`

### Type level

```scalascript
type (A₁, ..., Aₙ) ++ (B₁, ..., Bₘ) = (A₁, ..., Aₙ, B₁, ..., Bₘ)
```

`++` is a **right-associative** infix type operator that flattens eagerly:
the result is always a flat `Tuple`, never nested.

```scalascript
// type-level examples
type AB   = (Int, String)
type CD   = (Boolean, Double)
type ABCD = AB ++ CD          // (Int, String, Boolean, Double)
type T    = (Int,) ++ ()      // (Int,)  — right identity
type U    = () ++ (Int,)      // (Int,)  — left identity
```

### Value level

```scalascript
val ab = (1, "hello")
val cd = (true, 3.14)
val r  = ab ++ cd             // (1, "hello", true, 3.14)
```

The `++` method is defined on every tuple value:
`(a₁, ..., aₙ).++(b₁, ..., bₘ) = (a₁, ..., aₙ, b₁, ..., bₘ)`.

### Monoid laws

```
()  ++ T       = T            left identity
T   ++ ()      = T            right identity
(S ++ T) ++ U  = S ++ (T ++ U)  associativity
```

---

## 3. Effect rows — the unordered dual

Tuples form an **ordered** monoid (sequence).  Effect rows form an
**unordered** monoid (set):

| Structure      | Elements       | Identity  | `++` operation |
|----------------|----------------|-----------|----------------|
| `Tuple`        | typed values   | `()`      | concatenation (ordered) |
| `EffectRow`    | `EffectOp`s    | `{}`      | union (unordered) |

Both satisfy the same abstract monoid laws.  The two `++` are different
operations — one is sequence concat, one is set union — but they live on
the same abstract interface.

Effect-row syntax is unchanged: `! (Logger, Stream[A])` continues to parse
as an unordered set `{Logger, Stream[A]}`.  Tuple `++` applies only in
value-type position.

---

## 4. Effect output types and the unified runner signature

Every algebraic effect `E` has an **output type** `Out(E)`:

| Effect        | `Out(E)`     | Meaning                     |
|---------------|--------------|-----------------------------|
| `Logger`      | `()`         | side-effect only            |
| `Random`      | `()`         | capability injection        |
| `Clock`       | `()`         | capability injection        |
| `Env`         | `()`         | capability injection        |
| `Http`        | `()`         | side-effect only            |
| `NonDet`      | `()`         | exploration (multi-shot)    |
| `State[S]`    | `(S,)`       | final state is emitted      |
| `Stream[A]`   | `(Source[A],)` | emitted elements           |

The **unified runner signature** is:

```
run[E](body: R ! E) : Out(E) ++ (R,)
```

Because `++` absorbs `()` (identity law), runners where `Out(E) = ()` return
`(R,)` which flattens to `R` — not a 1-tuple.  The result types of existing
runners follow directly:

```
runLogger { body: A ! Logger }
  = Out(Logger) ++ (A,)  =  () ++ (A,)  =  (A,)  ≅  A

runState(s₀) { body: A ! State[S] }
  = Out(State[S]) ++ (A,)  =  (S,) ++ (A,)  =  (S, A)

runStream { body: R ! Stream[A] }
  = Out(Stream[A]) ++ (R,)  =  (Source[A],) ++ (R,)  =  (Source[A], R)
```

All three match the actual return types in the current implementation —
the unified model is purely descriptive for `Logger`/`Random`/etc. and
already structural for `State` and `Stream`.

### Multi-effect discharge

When multiple effects are discharged simultaneously their output types
concatenate left-to-right:

```
run[E₁, E₂](body: R ! (E₁, E₂))
  : Out(E₁) ++ Out(E₂) ++ (R,)
```

Example — body produces both log messages and stream elements:

```scalascript
def analyze(data: List[Int]): Int ! (Logger, Stream[Int]) =
  Logger.info("starting")
  data.foreach(x => if x > 0 then Stream.emit(x))
  data.length

// discharge both
val (src, n) = runStream { runLogger { analyze(List(1, -2, 3)) } }
// Out(Logger) ++ Out(Stream[Int]) ++ (Int,)
// = () ++ (Source[Int],) ++ (Int,)
// = (Source[Int], Int)
```

Multi-effect discharge with a combined runner (future `run[E₁, E₂]` syntax)
is deferred to a follow-up milestone; the nested-runner form works today.

---

## 5. Parsing and syntax

### `++` in type position

`++` is parsed as a right-associative binary type operator with lower
precedence than `,` inside a tuple:

```
TupleType ::= AtomType (',' AtomType)*
TupleConcat ::= TupleType ('++' TupleType)*
```

Disambiguation rule: `(A, B)` in value-type position is a tuple;
`(A, B)` in `! <effect-type>` position is an unordered effect set.
`(A, B) ++ (C, D)` can only appear in value-type position.

### 1-tuple syntax

`(A,)` (trailing comma) denotes a 1-element tuple, distinguishing it from
the parenthesized expression `(A)`.  This is needed for `Out(State[S]) = (S,)`.

---

## 6. Implementation scope

### Track 1 — Type system

1. **`SType.Unit` → `SType.Tuple(Nil)`**
   - Change alias in `lang/core/.../Types.scala:168`
   - Update `show`: `Tuple(Nil)` renders as `"()"` not `"()"`
     (already correct — `elems.mkString("(", ", ", ")")` gives `"()"`)
   - Update `parseSType` / `InterfaceScope` to normalize `Named("Unit", _)` → `Tuple(Nil)`

2. **`SType.Concat` or eager flattening in smart constructor**
   - Add `SType.tupleConcat(t1, t2): SType` that pattern-matches both args
     as `Tuple` and concatenates their `elems`; non-tuple args wrapped in
     `Tuple(List(t))` first, then flattened
   - No new ADT case needed — Concat desugars at parse time

3. **`++` in type parser** (`Typer.scala` / `TypeParser` region)
   - After parsing a tuple type, check for `++` token and recurse
   - Emit `SType.tupleConcat(left, right)` → flat `Tuple`

4. **Unifier**: `solve(Tuple(Nil), t)` and `solve(t, Tuple(Nil))` → `t` 
   (unit absorption); existing `(Tuple(e1), Tuple(e2)) if e1.length == e2.length`
   stays unchanged

### Track 2 — Value level

5. **`Value.UnitV` → `Value.TupleV(Nil)` unification**
   - Make `Value.UnitV` a `val` that is `Value.TupleV(Nil)`, or keep the case
     object and pattern-match both in `++` dispatch
   - Either way: `TupleV(Nil) == UnitV` structurally

6. **`++` on tuples in interpreter** (`DispatchRuntime.scala`)
   ```scala
   case (TupleV(as), "++", List(TupleV(bs))) => Pure(TupleV(as ++ bs))
   case (TupleV(as), "++", List(v))           => Pure(TupleV(as :+ v))
   case (v,          "++", List(TupleV(bs)))  => Pure(TupleV(v +: bs))
   ```

7. **`++` in JS codegen** (`JsGen.scala`)
   - Tuple concat lowers to array spread: `[...a, ...b]` with `_isTuple = true`

8. **`++` in JVM codegen** (`JvmGen.scala`)
   - Lower to `(a._1, ..., a._n, b._1, ..., b._m)` or via `_TupleConcat` helper

### Track 3 — Extern declarations and runtime spec

9. **`streams.ssc`** — add `++` to tuple extern API docs
10. **`algebraic-effects.md`** — add §"Unified runner signature" with the
    `Out(E) ++ (R,)` table

### Out of scope for v1.60

- Combined multi-effect `run[E₁, E₂]` runner (nested runners work today)
- Dependent/variadic tuples beyond static arity
- `Tuple.size`, `Tuple.head`, `Tuple.tail` type-level operations (potential v1.61)

---

## 7. Risks

- **`Unit` in `.scim` artifacts**: after the change, existing artifacts that
  encode `Unit` as `Named("Unit", Nil)` must deserialize as `Tuple(Nil)`.
  `parseSType` normalizes `Named("Unit", Nil)` → `Tuple(Nil)` on read — no
  format-version bump needed since the normalization is idempotent.

- **Pattern-match exhaustiveness**: wherever `SType.Named("Unit", Nil)` is
  matched explicitly it will silently miss `Tuple(Nil)`.  A grep-and-fix pass
  is required before the release.  `grep -rn '"Unit"' lang/` covers these.

- **1-tuple `(A,)` parsing**: trailing-comma tuples may clash with existing
  parser rules for function parameters.  The tuple parser needs a dedicated
  lookahead for the trailing comma.

- **`++` operator priority**: if user code already uses `++` as a method name
  on custom types the infix parser ambiguity must be resolved by precedence
  rules.  Low priority in the infix chain (below `,`) avoids most conflicts.
