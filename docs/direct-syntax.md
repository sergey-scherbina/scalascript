# Direct-syntax do-notation

**Status: ✅ Landed — v1.8 (core) + v1.8.1 (extensions).**
All three backends (interpreter, JVM, JS) ship the feature.

Source of truth for the user-facing syntax, its lowering, and the
seven locked design decisions.  See also
[`docs/specs/backend-spi.md`](backend-spi.md) §6 (effect lowering) and
the worked example in [`docs/tutorial.md`](tutorial.md) §3.

## 1. Motivation

Today, sequencing two `Async` operations in a handler body looks
like one of these three forms.  None are ergonomic:

```scala
// (a) Callback nesting — works, but inverts control flow
route("GET", "/user") { req =>
  Async.flatMap(Async.delay(loadUser(req))) { user =>
    Async.map(Async.delay(loadOrders(user.id))) { orders =>
      Response.json(user, orders)
    }
  }
}

// (b) for-comprehension — works, but visually heavy and requires
//     the `<-` arrow to be remembered
route("GET", "/user") { req =>
  for
    user   <- Async.delay(loadUser(req))
    orders <- Async.delay(loadOrders(user.id))
  yield Response.json(user, orders)
}

// (c) Direct-syntax (this design) — reads like sync, types are honest
route("GET", "/user") { req =>
  user   = Async.delay(loadUser(req))
  orders = Async.delay(loadOrders(user.id))
  Response.json(user, orders)
}
```

Form (c) is what every modern effect system has converged on
— Scala 3.5+ capture checking via `boundary/break`, Kotlin
`suspend`, Rust `async/await`, OCaml 5 algebraic effects,
ZIO 2 direct-style, cats-effect 3 with `IO.uncancelable`'s
direct shape, Unison.  ScalaScript ships it as **pure
sugar** over the v1.1 `std/monad` machinery — zero new
runtime, zero new type-system primitives.

### The non-goal

This is **not** a new effect system.  It's not algebraic effects, it's
not capability tracking, it's not a substitute for `for { … } yield …`.
`for`-comprehensions stay the canonical multi-monad form.  Direct
syntax is the **single-monad sugar** that wraps the 80% case:
"this whole function lives in one effect, please stop making me write
`<-` on every line."

## 2. Worked examples

### 2.1 Implicit (type-directed) direct block

The handler's expected return type is `Request => Async[Response]`.
The block body is therefore in `Async`:

```scala
route("GET", "/api/user/:id") { req =>
  // Compiler sees the expected type Async[Response], lowers the
  // body to a for-comprehension over Async.
  id      = req.params("id").toInt    // pure binding — no bind
  user    = Async.delay(loadUser(id)) // monadic bind — Async[User]
  orders  = Async.delay(loadOrders(id))
  Response.json(user, orders)         // pure — last expression
}
```

### 2.2 Explicit `direct[M]` marker

When context is ambiguous (e.g. assigning a direct block to a
`val` that doesn't constrain the monad), an explicit marker:

```scala
val computeStats = direct[Async] {
  raw    = fetchRaw()                 // Async[String]
  parsed = parse(raw)                 // pure (no lift)
  count  = lookupCounter(parsed)      // Async[Int]
  count * 2
}
```

### 2.3 Control flow

```scala
route("POST", "/orders") { req =>
  user = Async.delay(loadUser(req))
  if user.tier == "gold" then
    discount = Async.delay(lookupDiscount(user))
    Response.json(applyDiscount(req.body, discount))
  else
    Response.json(req.body)
}
```

Both branches must inhabit the same monad after desugaring; pure
branches auto-lift.

### 2.4 Loops

```scala
route("GET", "/users") { req =>
  ids = Async.delay(loadUserIds())
  // for-loop over a collection becomes traverse:
  users = ids.traverse(id => Async.delay(loadUser(id)))
  Response.json(users)
}
```

## 3. Grammar

A **direct block** is either:

- **Implicit** — any block whose expected return type is `M[A]`
  where `M` has a `Monad[M]` instance in scope, AND the block
  contains at least one *bind-form* (`x = expr` or bare `M[*]`-typed
  expression).  Pure blocks remain pure.

- **Explicit** — wrapped in `direct[M] { ... }`.  Compiler synthesises
  the `M[*]` expected type for the body.  `M` must resolve to a
  monad via `summon[Monad[M]]` at the call site.

Inside a direct block:

| Syntactic form | Lowering |
|----------------|----------|
| `val x = expr` | Pure local binding.  Body lifted via `Monad.pure(expr)` only at the block's end. |
| `x = expr` (no `val`) | Monadic bind.  Becomes `x <- expr` in the lowered for-comprehension; if `expr`'s type is not `M[*]`, auto-lifts via `Monad.pure`. |
| Bare `expr` of type `M[*]` | Becomes `_ <- expr` (bind-and-discard). |
| Bare `expr` of pure type | Regular Scala statement (no bind). |
| `var v = expr` / `v = expr` | Existing Scala mutable-var semantics — never monadic.  The `var` keyword disambiguates from monadic bind. |
| Last expression | The yield clause.  Pure values auto-lift via `Monad.pure`. |

## 4. Design decisions (DS-1 … DS-7)

| # | Question | Resolution |
|---|----------|------------|
| **DS-1** | How does the typer infer the monad for a direct block? | **Type-directed** — inferred from the expected return type (e.g. handler typed `Request => Async[Response]` ⇒ block in `Async`).  Explicit `direct[M] { ... }` marker as fallback when context is ambiguous. |
| **DS-2** | When do pure values auto-lift? | `val x = expr` is a pure local binding (no bind).  `x = expr` is a monadic bind; pure `expr` auto-lifts via `Monad.pure`.  `var`-rebind keeps Scala mutable-var semantics. |
| **DS-3** | Bare statements — bind-and-discard or regular? | **Type-directed** — bare `expr` of type `M[*]` becomes `_ <- expr`; pure bare expressions stay regular statements (e.g. `assert(x > 0)`). |
| **DS-4** | Control flow inside direct blocks (`if`/`match`/`while`/`for`)? | **All branches must inhabit the same monad after lifting.**  Pure branches in an `if`/`match` auto-lift to `M[*]` if any sibling branch is `M[*]`.  `while`/`do-while` of an `M[Unit]`-typed body desugars to `Monad.whileM_(cond, body)`; backends ship `whileM_` as a stdlib helper.  `for (x <- xs) body` where `body: M[Unit]` desugars to `xs.traverse_(x => body)`. |
| **DS-5** | Lambdas inside collection ops (`xs.map(x => doMonadic(x))`) — do they "see" the outer direct block? | **No — lambda bodies are independent direct blocks.**  A lambda's expected return type drives its own monad inference: a lambda typed `A => M[B]` is its own direct block; a lambda typed `A => B` is pure.  Cross-boundary effects require `xs.traverse(f)` (lambda returns `M[B]`, traverse threads the monad) rather than `xs.map(f)` (lambda must return pure).  `.map(x => doMonadic(x))` raises a type error directing the user to `.traverse`. |
| **DS-6** | Explicit bind-marker syntax (postfix `.!`, prefix `~`, `await`)? | **No marker in v1.**  Inference is pure-type-directed.  Locked deliberately to avoid a second syntactic dialect.  A follow-up postfix `.!` operator for explicitly forcing bind in genuinely ambiguous spots is parked for v1.8.1 once real usage surfaces ambiguity; until then, users write `_ = expr` to force the bind. |
| **DS-7** | Error handling — `MonadError` or thrown exceptions? | **Both, type-directed.**  `M.fail(...)` / `M.recover(...)` are the canonical monadic API.  Additionally, `throw e: E` / `try { … } catch case e: E => …` inside a direct block lower to `F.fail` / `F.handleError` **when** `MonadError[F, E]` is in scope (the typer-directed bridge — see [`docs/error-handling.md`](error-handling.md) §2.5.6); they keep their JVM-native semantics otherwise.  This narrows the original DS-7 lock from "thrown exceptions NEVER auto-wrap" to "thrown exceptions auto-wrap only when the user explicitly typed them AND the `F` advertises a matching error channel" — the two-fault-model trap is still avoided because the lowering is driven by what the user typed, not by silent magic on every `Throwable`. |

Locked 2026-05-17 (DS-1…DS-3, DS-7); DS-4…DS-6 locked
2026-05-18 (this document).

## 5. Desugaring

The transformer runs after typing and before backend-specific
lowering.  Pseudo-code:

```
def desugar(block: DirectBlock[M]): Term =
  val stmts = block.statements
  val tail  = block.tail            // the final expression
  // Walk stmts in reverse, building nested for-comprehension binds.
  val (binds, pureStmts) = stmts.foldRight((List.empty[Bind], List.empty[Stat])) {
    case (Stat.ValDef(x, rhs), (bs, ps)) =>
      // `val x = expr` — pure local, kept as-is in the yield body
      (bs, Stat.ValDef(x, rhs) :: ps)
    case (Stat.Assign(x, rhs), (bs, ps)) if isMonadic(rhs.tpe) =>
      // `x = expr` with M[*] rhs — true monadic bind
      (Bind(x, rhs) :: bs, ps)
    case (Stat.Assign(x, rhs), (bs, ps)) =>
      // `x = expr` with pure rhs — pure-lifted bind
      (Bind(x, q"Monad[M].pure($rhs)") :: bs, ps)
    case (Stat.ExprStat(e), (bs, ps)) if isMonadic(e.tpe) =>
      // bare monadic expr — bind-and-discard
      (Bind("_", e) :: bs, ps)
    case (stmt, (bs, ps)) =>
      // pure statement — keep in yield body
      (bs, stmt :: ps)
  }
  q"""for {
        ..${binds.map { case Bind(x, e) => q"$x <- $e" }}
      } yield {
        ..$pureStmts
        ${if isMonadic(tail.tpe) then tail
          else q"Monad[M].pure($tail)"}
      }"""
```

The result is a vanilla `for { x <- e1; y <- e2 } yield body` — the
existing v1.1 `Monad` instance machinery handles the rest.  No new
runtime, no new IR nodes.

### Control flow

`if cond then thenBranch else elseBranch` where the block's expected
type is `M[A]`:

- If both branches are `M[A]` after desugaring, leave as-is.
- If only one branch is `M[A]`, wrap the pure branch in `Monad[M].pure(...)`.

`while cond do body` where `body: M[Unit]`:

```
Monad[M].whileM_(cond)(body)
```

`whileM_` is a stdlib helper in `std/monad-control.ssc` (lands with
this milestone).  The v1.8.x follow-up adds two complementary
combinators in the same module:

- `untilM(cond)(body)` — do-while: runs `body` at least once, then
  loops while `!cond`.  Returns the last successful body result
  wrapped in `F`.  Stdlib spellings: `untilMResultOption`,
  `untilMResultEither`.
- `iterateWhileM(init)(step)(cond)` — Kleisli iteration: starts at
  `init`, while `cond(current)` is true threads `current` through
  the monadic `step`.  Check-first: if `cond(init)` is false the
  initial value is returned via `pure`.  Stdlib spellings:
  `iterateWhileMOption`, `iterateWhileMEither`.

Both short-circuit on monadic failure (`None` for Option,
`Left` for Either), matching the contract of `whileM_`.

`for x <- xs do body` where `body: M[Unit]`:

```
xs.traverse_(x => body)
```

`xs.traverse_` from `std/foldable-traversable.ssc` (already in v1.1).

## 6. Edge cases

### `return` and non-local exits

**Disallowed** inside direct blocks.  Use `M.fail(...)` for early
failure.  Reason: `return` from a desugared for-comprehension
bypasses the monad's bind chain and breaks effect semantics —
e.g. cancellation, retry, finalisers.

### Mutable `var` interacting with bind

```scala
var counter = 0
direct[Async] {
  result = Async.delay(fetchSomething())
  counter += 1              // pure side-effect on the var — OK
  result.length
}
```

The `var` is lexical; the desugared for-comprehension closes over
it normally.  Disallowed: assigning a monadic result *to* a `var`:

```scala
var x = 0
direct[Async] {
  x = Async.delay(1)        // ERROR: monadic bind not allowed on var
}
```

Compiler emits "monadic bind requires `val` or fresh name; got mutable `var`".

### Nested direct blocks

```scala
direct[Async] {
  outer = Async.delay(prepare())
  innerResult = direct[Option] {
    a = parseInt(outer)
    b = parseInt(outer.tail)
    a + b
  }
  Response.json(innerResult)
}
```

Inner block is in `Option`; outer in `Async`.  Lifting between
monads is **not automatic** — the inner block produces
`Option[Int]`, which the outer treats as a pure value.  Transformer
stacks (`OptionT[Async, *]`) are out of scope for v1.

### Pure-only block

```scala
direct[Async] {
  a = pureCompute()         // auto-lift via Monad.pure
  b = pureCompute2()
  a + b                     // pure tail — auto-lift
}
```

Compiles to `Monad[Async].pure(pureCompute() + pureCompute2())` — no
real binding happens, but the user can write the code uniformly.

## 7. Comparison

### vs. `for { … } yield …`

For-comprehension is the **canonical** form and continues to work
unchanged.  Direct syntax is sugar that compiles **to** a
for-comprehension — both forms can be mixed freely in the same
codebase, and a direct block can contain a nested
for-comprehension or vice-versa.

When to prefer which:
- **For-comprehension**: working in multiple monads (e.g.
  `Either` + `Async` via `EitherT`), pedagogical clarity, or
  when explicit `<-` is a feature.
- **Direct syntax**: a function body that's *entirely* in one
  monad, especially when it has 4+ binds.

### vs. Scala 3.5 capture checking / `boundary/break`

Different problem.  Capture checking tracks *which captures cross
which boundary* at the type level (where can `s` escape?).  Direct
syntax tracks *sequencing of monadic operations*.  The two are
orthogonal — direct syntax could later use capture checking to
verify that no `var` reaches across an `Async.parallel(...)`.

### vs. cats-effect 3 / ZIO 2 direct-style

Same idea, different ecosystem.  Cats-effect uses `IO.flatMap` and
the user explicitly threads via `>>`/`for`.  ZIO's direct-style
preview uses `for`-yield with hints.  Our shape is closer to
Unison's `unison-do` blocks or Kotlin's `suspend` — pure-type-
driven, no marker, single monad.  Trade-off: less explicit, more
ergonomic; works when the team already accepts monadic effect
abstractions (v1.1 stdlib already does).

## 8. Implementation phases

Each phase is a separate PR, mergeable in sequence.

### Phase 1 — Typer foundation (~3 days)

- Parser accepts `direct[M] { ... }` — `Term.Apply(Term.TypeApply(Term.Name("direct"), List(M)), List(block))`.
- Typer sets the expected type of `block` to `M[A]` where `A` is the
  block's inferred result type.
- Synthesises a `DirectMarker(M, body)` IR node (no runtime emission yet).

### Phase 2 — Desugaring transformer (~4 days)

- New `core/transform/DirectDesugar.scala` walks `DirectMarker` nodes.
- Implements the rewrite rules from §5.
- Emits a `Term.For` (the existing scala-meta node).
- Hooks into the typer pass so existing for-comprehension lowering
  takes over from there.

### Phase 3 — Type-directed mode (no marker required, ~3 days)

- Detect "implicit direct block": block whose expected type is `M[A]`
  with a `Monad[M]` in scope AND contains a bind-form (`x = expr` or
  bare `M[*]`-typed expr).
- Run the same desugarer on these blocks.

### Phase 4 — Control flow + traverse helpers (~2 days)

- Add `whileM_` to `std/monad-control.ssc` for `Monad[M]`.
- Verify `xs.traverse_` lowering works inside direct blocks.
- Conformance: `direct/control-flow.ssc` exercises `if`/`match`/`for`.

### Phase 5 — Diagnostics (~2 days)

- Compiler errors for the 4 known foot-guns:
  - `return` inside direct → "use `M.fail(...)` for early exit"
  - Monadic bind to `var` → "use `val` or a fresh name"
  - `.map(x => doMonadic(x))` → "use `.traverse` for monadic lambda body"
  - Cross-monad bind (mixing two `direct[A]` and `direct[B]`) → "transformer stack out of scope; lift explicitly"

### Phase 6 — Conformance + std rewrites (~2 days)

- Conformance: 5-6 tests covering the seven DS decisions
  (`direct/inference.ssc`, `direct/pure-lift.ssc`,
  `direct/control-flow.ssc`, `direct/traverse.ssc`,
  `direct/error.ssc`, `direct/nested.ssc`).
- Rewrite 2-3 illustrative examples (`examples/rest-api.ssc`,
  `examples/async-parallel-demo.ssc`) to direct syntax — they're
  the touch points users land on.

Total: ~16 days (~3 weeks).

## 9. Hard-no list (closed by design)

| Feature | Reason |
|---------|--------|
| Full effect-row composition (`Async \| Random` sharing a single monad) | Out of scope for v1; `direct[Async \| Random]` is accepted syntactically (v1.8.1) and duck-typed but does not compose two monads. |
| Fully general monad transformers (`StateT`, `WriterT`, …) | Out of scope; cross-monad lifting is limited to Option↔Either in v1.8.1. |
| **Silent** auto-wrap of any `Throwable` into `M.fail` | The two-fault-model trap — DS-7 (refined 2026-05-18) only auto-wraps when the user explicitly typed the throw AND `MonadError[F, E]` is in scope; never on bare `throw new RuntimeException(...)` |
| Capability-checked `direct[Pure] { ... }` | No `Monad[Pure]` in std today; would need its own foundation. |
| `await`-style keyword (`val x = await(expr)`) | Locked under DS-6 — pure type-directed, no marker. |
| Non-local `return` from inside a direct block | Bypasses bind chain; use `M.fail`. |

## 10. v1.8.1 extensions — landed

Three follow-ups from v1.8 shipped in v1.8.1:

### 10.1 Postfix `.!` explicit-bind operator (DS-6 follow-up)

Inside any `direct[M]` block, appending `.!` to an expression forces a
monadic bind at that point and returns the unwrapped value in-place:

```scala
direct[Option] {
  println(Some(42).!)          // prints 42; result discarded
  Some(Some(10).! + Some(32).!)  // => Some(42)
}
```

`fa.!` desugars via A-normalization in `DirectAnorm.expand`: each `.!`
occurrence is lifted into a fresh `_bN = fa` bind statement prepended
before the enclosing statement, and replaced by `_bN` at the original
position.  The A-normalization pre-pass runs on all three backends
(interpreter, JVM codegen, JS codegen) via `core/transform/DirectAnorm.scala`.

Boundaries where `.!` is **not** lifted: nested `direct[M]` blocks,
lambda bodies, and `Term.Block` sub-expressions — each of these forms
its own scope.

### 10.2 Effect-row union types

`direct[Async | Random]` is now accepted without a parse or validation
error.  `DirectTypeUtils.validateDirectTypeArg` permits `|`-connected
union types and rejects any other infix type operator.  At runtime the
leftmost type name (`Async` in the example) is used as the primary monad
for duck-typed `flatMap` dispatch — full multi-monad composition is still
out of scope for v1 (see §9 hard-no).

### 10.3 Transformer-aware lift (interpreter)

When a `direct[M]` block binds a value of a *different* compatible monad,
the interpreter auto-lifts it instead of dispatching `flatMap` on the
wrong type:

| Outer `M` | Bound value | Lift |
|-----------|-------------|------|
| `Option` | `Right(v)` | extract `v`, continue |
| `Option` | `Left(_)` | short-circuit to `None` |
| `Either` | `Some(v)` | extract `v`, continue |
| `Either` | `None` | short-circuit to `Left(())` |
| `Async` / other | `Option`/`Either` values | same rules as above |

```scala
case class Right[A](value: A)
case class Left[A](value: A)

val r = direct[Option] {
  x = Right(42)         // auto-lifted: x = 42
  Some(x * 2)
}
// => Some(84)
```

Implementation: `DirectMonadTag` enum (OptionM / EitherM / AsyncM /
ListM / OtherM) is extracted from the `direct[M]` type argument;
`liftBindValue` in the interpreter selects the lift rule before falling
back to duck-typed `flatMap`.

### Earlier follow-ups

- ~~**`std/monad-control.ssc` expansion** — `untilM`,
  `iterateWhileM`, loop combinators beyond `whileM_`.~~  Landed
  v1.8.x as `untilMResult{Option,Either}` and
  `iterateWhileM{Option,Either}` — see §5 control flow above.

### Still open

- **Capture checking interaction** — verify direct blocks don't
  leak `var`-captures across `Async.parallel`, once Scala 3.x
  capture checking matures.

## 11. Conformance plan

Six tests, each a single ScalaScript file under `conformance/`:

| Test | Exercises |
|------|-----------|
| `direct-inference.ssc` | DS-1: type-directed inference vs explicit `direct[M]` |
| `direct-pure-lift.ssc` | DS-2, DS-3: pure auto-lift, bare `M[*]` discard |
| `direct-control-flow.ssc` | DS-4: `if`/`match`/`while`/`for` desugaring |
| `direct-lambdas.ssc` | DS-5: lambda boundaries, `.traverse` vs `.map` |
| `direct-error.ssc` | DS-7: `M.fail` / `M.recover`; thrown exceptions don't auto-wrap |
| `direct-nested.ssc` | Nested direct blocks across `Async` / `Option` (no transformers) |

Each test runs on all three backends (INT, JS, JVM) under the
existing `conformance/run.sc` harness.  Behaviour is identical
across backends — direct syntax is pure source-to-source rewriting,
no runtime divergence.
