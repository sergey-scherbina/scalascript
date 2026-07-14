# Algebraic Effects

Status: **design / planning**. Implementation tracked starting from v1.12.
Companion to [`specs/coroutines.md`](coroutines.md) (coroutine primitive and an
optional one-shot optimization), [`docs/direct-syntax.md`](../docs/direct-syntax.md) (monadic
`for`/`yield` surface), and [`docs/error-handling.md`](../docs/error-handling.md)
(`throws[A,E]` and `MonadError`).

[`control-interoperability.md`](control-interoperability.md) extends this design
with the target-neutral laws for typed multi-prompt `shift`/`reset`, managed host
control, and reusable portable `save`/`run`. The
[`Scala 3 profile`](scala3-bidirectional-control.md) is one host realization, not
the semantic owner. Where the historical v1.12 scope below excludes all first-class
continuations, the common control specification supersedes it for delimited control
only; `callCC` remains a non-goal.

This document is the source of truth for the typed algebraic-effect system:
why it exists, how effects appear in types and declarations, how handlers
discharge effects, and how the system sits over the existing runtime substrate
without breaking what v1.16 already shipped.

Historical v1 backend-path descriptions below are implementation notes, not a
second semantic contract. The current reference semantics are the guarded
three-field `Pure | Op` protocol: plain typed `.ssc effect` is one-shot,
`multi effect` and raw CoreIR/Mira `effect.perform` are reusable, and a
coroutine/generator lowering is an optional target-private optimization.

---

## 1. Motivation and relation to v1.16

v1.16 shipped a fully working algebraic-effect handler at the **runtime level**:

- `effect Foo { def op(...): A }` is parsed by `Parser.preprocessEffects`
  and rewritten into an `object Foo` with a sentinel body `__effectOp__`.
- `handle(body) { case Foo.op(args, resume) => … }` intercepts `Perform`
  nodes and builds a `resume` closure that can be called any number of times
  (multi-shot semantics — see `EffectsRuntime.evalHandle`).
- Built-in effects ship out of the box: `Logger`, `Random`, `Clock`, `Env`,
  `Http`, `State` — each wired as a `runX { body }` runner in
  `StdEffectsRuntime.scala`.
- `Feature.AlgebraicEffects` is advertised by every backend (interpreter, JVM,
  JS, Node, Wasm, Spark, ScalaJs).

What v1.16 did **not** ship is **type-level effect tracking**. Effects are
dispatched by string-tagged `Perform(effName, opName, args)` nodes; the type
system is unaware. Concretely:

| Missing capability | Consequence |
|--------------------|-------------|
| No effect rows on function signatures | Compiler cannot tell you which effects a function may perform |
| No handler discharge in the type system | Calling `handle[Foo]` does not remove `Foo` from the inferred type |
| No "unhandled effect" error | Forgetting to install a handler is a runtime crash, not a compile-time error |
| No distinction between one-shot and multi-shot effects at the type level | Every handler allocates a Free-monad `Computation` tree even when single-resume is guaranteed |

v1.12 closes these gaps **at the design level**. The deliverable is this
specification. Implementation follows in subsequent milestones (v1.12.1+) once
the design is reviewed and signed off.

---

## 2. Conceptual model

An **effect** is a named operation that a function may perform, whose
*semantics* are determined by the *handler* rather than the callee.

```
effect Logger {
  def log(msg: String): Unit
}

def greet(name: String): Unit ! Logger =
  Logger.log(s"Hello, $name")

def main(): Unit =
  handle[Logger](greet("world")) {
    case Logger.log(msg, resume) =>
      println(msg)
      resume(())
  }
```

Three roles:

| Role | Who | What they write |
|------|-----|-----------------|
| **Effect declaration** | Library author | `effect Foo { def op(...): A }` — names the operations |
| **Effect use** | Caller | function signature `A ! Foo`; body calls `Foo.op(...)` |
| **Handler** | Top-level or middleware | `handle[Foo](body) { case Foo.op(..., resume) => … }` |

**Effect row.** A function's effect set is a *row* — an ordered set of named
effects plus an implicit tail that propagates whatever the calling context
permits. The row is **open by default**: declaring `def foo(): A ! Logs` means
"performs *at least* Logs; passes everything else through unaltered."

**Closed row / total function.** A function declared *without* a `!`-clause is
**total** — its effect row is closed at empty. `def main(): Unit` accepts no
residual effects from inside it; any unhandled effect that escapes is a
compile-time error. This is how the system enforces "all effects must be
exhausted here" — not via a special keyword, but by the presence or absence of
the `!` clause.

**Capabilities.** The `?=>` context-function type is a parallel, lighter
mechanism for plain contextual values (config, logger sink, request scope).
Capabilities cannot capture continuations; there is no `resume`. Use a
capability when the operation's semantics are fixed and you only need
dependency injection. Use a handler when the operation's semantics depend on
the handler (error recovery, non-determinism, scheduling). See §6.

---

## 3. Type-level surface

### 3.1 `EffectRow` — new type-system case

Add `EffectRow(tail: Option[Var], ops: Set[Named])` as a new case in
`SType` (`lang/core/src/main/scala/scalascript/typer/Types.scala:19-41`).

```scala
enum SType:
  // … existing cases …
  case EffectRow(tail: Option[Var], ops: Set[Named])
```

An `EffectRow` is *not* a standalone type; it appears only as the second
argument to the `!` type operator. `SType.Function` gains a new field:

```scala
case Function(params: List[SType], result: SType,
              effects: EffectRow = EffectRow(None, Set.empty))
```

Default `EffectRow(None, Set.empty)` = total / pure (closed empty row).

### 3.2 Two reading rules

| Signature written | Parsed as | Meaning |
|-------------------|-----------|---------|
| `def foo(): A` | `Function(..., A, EffectRow(None, ∅))` | **Total.** Closed empty row. Any unhandled `perform` inside is a compile-time error. |
| `def foo(): A ! Logs` | `Function(..., A, EffectRow(Some(fresh), {Logs}))` | **Effectful.** Open row: performs `Logs`, propagates everything else. |
| `def foo(): A ! (Logs, Random)` | `Function(..., A, EffectRow(Some(fresh), {Logs, Random}))` | **Effectful.** Open row: performs both, propagates the rest. |

The tail variable `fresh` is a fresh `SType.Var` created by the typer when it
encounters a `!`-annotated function. It is **never written by the user** —
only inferred. The pretty-printer omits the tail; `show` renders only the
declared ops.

### 3.3 Unifier extension

`Unifier` (`Types.scala:174-209`) currently descends `Named`, `Function`, and
`Tuple`. It must be extended to:

1. Unify `EffectRow(t1, ops1)` against `EffectRow(t2, ops2)` via Rémy-style
   row unification:
   - If both tails are `None`: `ops1` must equal `ops2` (closed rows must agree exactly).
   - If one tail is `Some(v)` and the other is closed: unify `v` with a row
     containing the difference, or fail if the closed row is a strict subset.
   - If both tails are `Some`: unify tails with a fresh-variable residual.
2. At a call site: the callee's effect row must be *absorbed* by the caller's
   context. Specifically, for each op in the callee row, either:
   - A handler is active for it (discharge), or
   - The caller's own tail includes it (propagation).

Row variables live in a **separate id space** from regular type variables to
prevent cross-domain occurs-check errors.

### 3.4 `EffectAnalysis` migration

`lang/core/src/main/scala/scalascript/transform/EffectAnalysis.scala` today
performs a name-based fixed-point reachability analysis: a function is
"effectful" if it transitively calls a `__effectOp__` def. This analysis
remains as a **verifier** for one release cycle (v1.12.1 warn mode), cross-
checking whether the typer's declared effect set matches what `EffectAnalysis`
infers. Mismatches produce warnings. v1.12.2 promotes them to errors.

---

## 4. Effect declarations

### 4.1 One-shot effects (default)

```ssc
effect Logger {
  def log(msg: String): Unit
  def warn(msg: String): Unit
}
```

`effect Foo { … }` means: handler calls `resume` **at most once** per
invocation. Reference lowering uses `effect.perform.oneshot(effectId,
operationName, args...)`, whose base continuation owns one linearizable claim.
A backend may replace that guarded `Pure | Op` path with a coroutine, generator,
or other allocation-saving fast path only when it preserves the same rejection,
deep-handler, forwarding, and diagnostic behavior. Avoiding a computation-tree
allocation is an optimization, not a semantic requirement.

### 4.2 Multi-shot effects

```ssc
multi effect NonDet {
  def choose[A](options: List[A]): A
}
```

`multi effect Foo { … }` means: handler may call `resume` **any number of
times** per invocation (including zero — abort). The reusable Free-monad
`Computation`-tree path is the reference representation. A qualified backend may
use any observationally equivalent encoding. Each branch starts at that captured
node; durable `save`/`run` never reconstructs it by replaying the computation prefix.

The `multi` prefix is a **parser-level keyword** on the `effect` declaration.
`Parser.preprocessEffects` (`lang/core/src/main/scala/scalascript/parser/Parser.scala:928-958`)
is extended to:
1. Recognise `multi effect` as a two-token keyword pair.
2. Tag the resulting rewritten object with a multi-shot marker (a synthetic
   attribute or a naming convention `__multiShot__`) that the typer reads when
   deciding which runtime path to emit.

### 4.3 Effect declaration syntax summary

| Form | Resume | Reference lowering |
|------|--------|--------------------|
| `effect Foo { … }` | At most once | guarded three-field `Pure | Op` via `effect.perform.oneshot` |
| `multi effect Foo { … }` | Any number | reusable three-field `Pure | Op` via raw `effect.perform` |

---

## 5. Handler semantics and discharge

### 5.1 Type-level discharge rule

```
handle[Foo](body : A ! (Foo, E₁, E₂, …)) : A ! (E₁, E₂, …)
```

`handle[Foo]` removes **only `Foo`** from the row; the remaining effects and
the implicit tail propagate. A handler never has to "cover all visible effects"
— it discharges what it names, leaves the rest unchanged.

The boundary where every effect must be discharged is determined by a callee
position **without a `!`-clause**. For example:

```ssc
def main(): Unit =              // closed row — nothing may escape
  handle[Random](
    handle[Logger](
      auditedShuffle(List(1,2,3))   // : List[Int] ! (Logger, Random)
    )                               // : List[Int] ! Random
  )                                 // : List[Int]
  // assignment to Unit — OK, Int not Unit but illustrative
```

If `handle[Logger]` is omitted and the result of `handle[Random](...)` still
has `! Logger`, the unifier fails with:

```
error: unhandled effect Logger
  at greet (examples/effects.ssc:12)
  called from main (examples/effects.ssc:20)
  main(): Unit requires a total (effect-free) computation
```

### 5.2 Handler pattern syntax

```ssc
handle[Foo](body) {
  case Foo.op(arg1, arg2, resume) =>
    // resume : ResultType => A ! residualRow
    val result = computeResult(arg1, arg2)
    resume(result)
}
```

`resume` is typed as a function from the op's result type to the handler body's
result type under the residual effect row. For multi-shot effects the source type
is the same, but lowering must preserve declaration multiplicity: plain effects
select `effect.perform.oneshot`, while `multi effect` selects reusable raw
`effect.perform` (see §4).

### 5.2.1 Return clause (`case Return(x) => …`)

A handler may include an optional **return clause** — a `case Return(x) => expr`
arm (unqualified `Return`, as opposed to the qualified `Eff.op(...)` effect cases).
It maps the handled computation's **final pure value**: when `body` (or a resumed
continuation) completes with value `x`, the handler yields `expr` instead of `x`.

This is what makes the textbook *deep-handler accumulation* work — `resume(())` of
the final continuation yields the return-clause result (e.g. `Nil`) rather than the
raw pure value, so it can be combined:

```ssc
val messages = handle(greet("World")) {
  case Logger.log(msg, resume) => msg :: resume(())   // prepend onto the rest
  case Return(_)               => List()               // base case: completed → []
}
// messages == List("Hello, World!")
```

The clause is a normal pattern (`Return(x)`, `Return(_)`, `Return(Some(y))`, multiple
arms with guards), matched against the completion value. **It is applied to each
continuation completion, not to op-case-body results** (so it composes once, never
twice). A handler **without** a return clause returns the pure value unchanged
(backward-compatible). Implementation: a handler with a return clause uses a direct
recursive evaluator (`EffectsRuntime.handleWithReturn`) where `resume` is an eager
`x => handleWithReturn(continuation(x))`; a target may retain an optimized
one-shot loop for the no-return-clause path when it preserves the reference laws.

**Backend codegen (effect-handler-return-clause-codegen).** The return clause now
lowers on every backend that has a Free-monad `handle`/`resume` codegen path:

| Backend | Return-clause lowering |
|---------|------------------------|
| **Interpreter** | `EffectsRuntime.handleWithReturn` (recursive evaluator) |
| **JVM** | `_handleWithReturn(bodyThunk, handledOps, handlers, retMap)` runtime + `JvmGen.emitHandleForm` partitions the `Return` case into an `Any => Any` retMap |
| **JS / Node** | `_handleWithReturn(bodyFn, handledOps, handlers, retMap)` runtime + `JsGen.genHandleForm` emits a `(_rv) => {…}` retMap |
| **Rust** | n/a — the base `handle`/`resume`/`perform` IR lowering itself is unstarted (R.4.2). `effect.rs` ships the runtime infrastructure only; user `handle(...)` does not yet lower to executable Rust, so there is no handler codegen to attach a return clause to. The return clause lands automatically once R.4.2 wires `handle`/`resume` through `run_with`. |

On JVM and JS, `_handleWithReturn` is a recursive evaluator: `resume` re-enters it
(`v => hwr(continuation(v))`), a bare pure value passes through `retMap`, and
op-case-body results are returned directly so `retMap` maps each continuation
completion exactly once. A handler with no `Return` arm keeps using the unchanged
`_handle` / `_handleOneShot` loop.

### 5.3 Runtime paths per backend

The guarded `Pure | Op` fold is the **reference semantics**. Qualified lanes may
choose target-private encodings, but those choices are observable only through
performance:

| Lane | Required effect path in the current delivery profile |
|------|------------------------------------------------------|
| **Portable v2 JVM VM + direct ASM** | shared `PortableEffects`; guarded generic `Op` for plain effects, reusable generic `Op` for raw/`multi effect` |
| **Swift AOT** | generated equivalent `Pure | Op` runtime with a lock-protected one-shot base claim |
| **Legacy v1 JVM/interpreter/JS** | existing coroutine/generator/Free paths may remain as compatible optimizations |
| **New v2 JS/Rust/WASM CoreIR lanes** | typed `effect.*` remains unsupported until the complete primitive family and shared vectors qualify |

A JS `function*` lowering is therefore optional. JS generator state is not
cloneable, so it cannot implement a reusable continuation by itself; a reusable
representation is still required for `multi effect`.

---

## 6. Capability passing (`?=>`)

### 6.1 What capabilities are

A **capability** is a contextual value resolved at the use site by implicit
scope — like a `given`/`using` parameter in Scala 3. Capabilities:

- Are **not resumable**. There is no `resume` and no continuation captured.
- Are resolved at compile time by `summon[T]` / `summon`.
- Lower to Scala 3 native context functions in codegen (scalameta passes them
  through already; no emitter work needed).
- Are strictly cheaper than handlers at runtime — no suspension, no queue.

### 6.2 Context function types

```ssc
def withLogger[A](sink: String => Unit)(body: Logger ?=> A): A
```

`Logger ?=> A` reads "given a `Logger` capability, produce an `A`". Inside
`body`, `Logger.log(...)` resolves via `summon[Logger]`. No `resume`, no
continuation.

### 6.3 When to use which

| Use handlers when… | Use capabilities when… |
|--------------------|------------------------|
| The operation's *meaning* depends on the handler (error recovery, scheduling, backtracking) | The operation is fixed; you only need the value injected (logger sink, config, request scope) |
| You need to intercept and resume (multi-shot for NonDet; one-shot for custom IO) | You just need `summon[T]` to find the right implementation |
| The effect may be absent / optional (handler can not install it) | The capability is always present in scope |

### 6.4 Stdlib `runX` runners — both APIs

The existing `runLogger { … }`, `runRandomSeeded(seed) { … }`, etc. remain
as **handler-style** APIs in `StdEffectsRuntime.scala`. A new **capability-style**
API will be added alongside in a later milestone:

```ssc
// handler-style (existing, handler with resume)
runLogger { body }                  // : A (Logger discharged)

// capability-style (new, no resume — just injects a sink)
withLogger(println) { logger ?=>    // : A (no effect row — capabilities aren't effects)
  logger.log("hello")
}
```

Both APIs coexist. The trade-off is documented in §2 and this section.

---

## 7. Interaction matrix

| Mechanism | Relation to effect rows |
|-----------|------------------------|
| `throws[A, E] = Either[E, A]` | **Separate concern.** `throws` is a return-channel type (the value is wrapped in `Either`); effects are about *side operations* the function may invoke. Do not conflate. A function can be `A ! Logger throws DbError` — error in the return channel, log in the effect row. |
| `Async[A]` | `Async` is itself an effect in the new model — performing it means "schedule this on the async runtime". Wrapping: `def fetch(url: String): Response ! Async`. The `runAsync` runner discharges `Async` from the row. |
| `Coroutine[Y, R]` | An optional low-level optimization for one-shot effects. The type system does not expose or require that lowering. |
| `Free[F, A]` | The reference substrate for reusable effects and a valid guarded representation for one-shot effects. `Computation = Pure | Perform | FlatMap` is an instance of `Free`. The `Free[F, A]` in `runtime/std/free.ssc` is a user-facing variant for custom program-as-data interpreters, separate from the internal `Computation` ADT. |
| `MonadError[F, E]` | Orthogonal. `MonadError` is a typeclass over monads that can fail; effects are a separate tracking layer. Both can be used together. |

---

## 8. Runtime semantics and backend notes

### 8.1 `Feature.AlgebraicEffects`

Every backend already advertises `Feature.AlgebraicEffects`
(`runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala:14`).
v1.12 does not change this contract. The flag means "this backend can execute
`effect`/`handle`/`resume`/`perform` programs." The type-level additions in
v1.12.1+ are purely additive to this guarantee.

### 8.2 Built-in effect handlers

The following effects ship as built-ins and will be **re-typed** in v1.12.1
to discharge their effects in their runner signatures:

| Effect | Current runner | New typed signature |
|--------|---------------|---------------------|
| `Logger` | `runLogger { body }` | `(body: A ! (Logger ∪ E)) => A ! E` |
| `Random` | `runRandomSeeded(seed) { body }` | `(seed: Long)(body: A ! (Random ∪ E)) => A ! E` |
| `Clock` | `runClockAt(t) { body }` | `(t: Instant)(body: A ! (Clock ∪ E)) => A ! E` |
| `Env` | `runEnvWith(m) { body }` | `(m: Map[String,String])(body: A ! (Env ∪ E)) => A ! E` |
| `State[S]` | `runState(s0) { body }` | `(s0: S)(body: A ! (State[S] ∪ E)) => (A, S) ! E` |
| `Http` | `runHttp { body }` | `(body: A ! (Http ∪ E)) => A ! E` |

(`E` here denotes the implicit row variable — see §11 for the future explicit form.)

### 8.3 Unified runner signature — `Out(E) ++ (R,)`

Every algebraic effect `E` has an **output type** `Out(E)` — the extra value the
runner emits beyond the body's return value:

| Effect        | `Out(E)`       | Meaning                       |
|---------------|----------------|-------------------------------|
| `Logger`      | `()`           | side-effect only              |
| `Random`      | `()`           | capability injection          |
| `Clock`       | `()`           | capability injection          |
| `Env`         | `()`           | capability injection          |
| `Http`        | `()`           | side-effect only              |
| `NonDet`      | `()`           | exploration (multi-shot)      |
| `State[S]`    | `(S,)`         | final state is emitted        |
| `Stream[A]`   | `(Source[A],)` | emitted elements              |

The **unified runner signature** is:

```
run[E](body: R ! E) : Out(E) ++ (R,)
```

Because `()` is the identity for `++` (the tuple monoid), runners where
`Out(E) = ()` return `() ++ (R,)` = `(R,)` which flattens to `R` — not a
1-tuple. The concrete return types of today's runners follow directly:

```
runLogger { body: A ! Logger }
  = Out(Logger) ++ (A,)  =  () ++ (A,)  =  (A,)  ≅  A

runState(s₀) { body: A ! State[S] }
  = Out(State[S]) ++ (A,)  =  (S,) ++ (A,)  =  (S, A)

runStream { body: R ! Stream[A] }
  = Out(Stream[A]) ++ (R,)  =  (Source[A],) ++ (R,)  =  (Source[A], R)
```

All three match the actual return types in the current implementation — the
unified model is purely descriptive for `Logger`/`Random`/etc. (they were
already correct) and structurally enforced for `State` and `Stream`.

`Out(E)` and `++` are part of the v1.60 tuple monoid (`specs/tuple-monoid.md`).
The `()` identity and `++` flatten operator live in the type system
(`SType.Unit = Tuple(Nil)`, `SType.tupleConcat`), the interpreter
(`DispatchRuntime` `TupleV.++`), and both backends
(`_tupleConcat` in JS/JVM).

**v1.60.4 — bare-value concat.** A bare (non-tuple) value is implicitly treated
as a 1-tuple for `++`, so `(A, B) ++ C = (A, B, C)` and `C ++ (A, B) = (C, A, B)`.
The identity law `() ++ v = v` holds for bare values too — `() ++ 42 = 42`, not
`(42,)`.  This mirrors the type-level rule `tupleConcat` applies everywhere.

### 8.4 `Computation` ADT remains

The `Computation = Pure | Perform | FlatMap` Free monad
(`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/Value.scala:240-306`)
is the runtime representation for raw/reusable effects and the cross-backend
reference fold; a guarded continuation gives the same shape one-shot behavior.
It does not change shape in v1.12. The type system sits above it.
This runtime-private ADT is neither a CoreIR node nor a durable frame/capsule wire
representation; the control-interoperability contract owns those boundaries.

---

## 9. Diagnostics

### 9.1 Unhandled effect error

When a computation with non-empty effect row flows into a total (closed) callee
position, the compiler reports:

```
error [UNHANDLED_EFFECT]: effect 'Logger' is not handled
  → Logger.log called at examples/effects.ssc:12:5
  → propagates through greet (effects.ssc:10)
  → reaches main (effects.ssc:20) which requires a total computation
  Hint: wrap the body with handle[Logger] { … }
```

The error traces the `perform` site back through the call chain to the total
boundary.

### 9.2 Handler / effect mismatch

```
error [HANDLER_MISMATCH]: handle[Logger] block handles effect 'Logger'
but the body has effect row { Random }
  → Logger discharged, Random remains unhandled
  Hint: add handle[Random] { … } around the outer expression, or
        add a Random runner to your effect stack
```

### 9.3 One-shot violation

```
error [ONESHOT_VIOLATION]: One-shot violation: FileIO.read resumed more than once
  → second resume at examples/io.ssc:34:7
  Hint: declare 'multi effect FileIO { … }' if multiple resumes are intended
```

This error is a **dynamic check** implemented by an abstract linearizable claim
in the base resume closure. Its semantic result is
`Left(ResumeRejected.AlreadyResumed(OperationId(EffectId("FileIO"), "read")))`.
Direct `.ssc resume` maps that rejection to
`ControlRunFailure(AlreadyResumed(...))`; user `.ssc try/catch` does not intercept
it. The runner renders separate code `ONESHOT_VIOLATION` and message
`One-shot violation: FileIO.read resumed more than once` as the line above.
Static detection is future work.

---

## 10. Non-goals for v1.12

- **Explicit row variables in user signatures.** `def map[A, B, e](f: A => B ! (| e))(xs: List[A]): List[B] ! (| e)` — users can write this today without any special support (implicit tail covers the common case). Naming the tail variable is a future ergonomic upgrade for library authors.
- **Undelimited continuations.** `callCC` remains outside the language contract.
  Typed delimited `shift`/`reset` and reusable saved continuations are specified
  separately in [`control-interoperability.md`](control-interoperability.md)
  and therefore are no longer covered by this historical v1.12 non-goal.
- **Effect inference.** Functions must declare their effect row explicitly (`! Eff` or `! (E1, E2, …)`). Inference from the body is future work.
- **Context modification and return.** Functions that both receive and return a modified context (see §11 Future Work).
- **Structured concurrency as an effect.** `Async` is in scope as a named effect (§7), but a full structured-concurrency effect algebra is its own milestone.

---

## 11. Future work

### 11.1 Explicit effect-row variables

Currently the implicit tail variable covers every generic combinator without
the user knowing:

```ssc
// Works today without explicit e — tail-variable propagates through
def mapM[A, B](f: A => B ! Logger)(xs: List[A]): List[B] ! Logger =
  xs.map(f)
```

When a library author needs to be **explicit** about what propagates, named
row variables will be needed:

```ssc
def mapM[A, B, e](f: A => B ! (| e))(xs: List[A]): List[B] ! (| e)
```

Proposed syntax: `(| e)` for a pure row variable (no declared ops); `(Logger | e)` for
declared ops plus a tail; bounded form `e <: (Logger, State)` for constrained
propagation.

This is strictly additive — programs that omit row variables continue to
type-check with the implicit tail.

### 11.2 Effect-set aliases

```ssc
type AppEffects = (Logger, Random, State[AppState])

def runApp[A](body: A ! AppEffects): A = …
```

Named sets of effects as type aliases. Sugar over the union of the listed rows.

### 11.3 Context modification and return

The user has a separate planned design: a function that both *receives* a
contextual value and *returns a modified version* of it, without going through
explicit state-passing. Possible encoding: a new effect `Update[Ctx]` with ops
`get: Ctx` and `set(Ctx): Unit`, wired through the handler as a `State[Ctx]`
variant. Alternatively: a `Reader[Ctx] + Writer[Ctx]` capability pair with an
auto-threading handler. Planned as a separate milestone; not in scope here.

The existing `State` effect handler at
`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/EffectHandlers.scala:371`
is the closest existing primitive — it threads a `Value` state cell through
resumes and already gives functions a way to "return a modified context."

### 11.4 Structured effect inference

Automatically infer the effect row of a function body without requiring an
explicit `!`-annotation. Currently, every function must declare its row. Inference
reduces the annotation burden substantially, at the cost of implementation
complexity and potentially surprising inferred signatures in error messages.

### 11.5 Per-op multi-shot

Currently `multi effect Foo { … }` marks the *entire* effect as multi-shot.
Per-op marking (some ops are one-shot, others multi-shot within the same
effect) is cleaner for effects like `NonDet` where only `choose` needs
multi-shot but potential future ops (e.g. `fail`) don't. Future milestone.

---

## 12. Examples

The following signatures illustrate the type-level surface. Runnable
examples ship in `examples/algebraic-effects.ssc` in the implementation
milestone.

```ssc
// Single effect — no parens
def greet(name: String): Unit ! Logger =
  Logger.log(s"Hello, $name")

// Single effect on generic function
def shuffle[A](xs: List[A]): List[A] ! Random

// Multiple effects — round parens, comma-separated
def auditedShuffle[A](xs: List[A]): List[A] ! (Logger, Random)

// Capability passing — no effect row (not an effect, just injection)
def withCache[A](store: Store)(body: Cache ?=> A): A

// Multi-shot: NonDet
multi effect NonDet {
  def choose[A](options: List[A]): A
}
def search(graph: Graph, goal: Node): List[Path] ! NonDet

// Nested handlers — each discharges one effect
def main(): Unit =
  handle[Random](
    handle[Logger](
      auditedShuffle(List(1, 2, 3))   // List[Int] ! (Logger, Random)
    )                                   // List[Int] ! Random
  )                                     // List[Int]
  // ... (total, both effects discharged)

// Total boundary — no ! clause
def runAll(): Unit =   // closed row; must discharge everything inside
  handle[NonDet](search(g, goal)) {
    case NonDet.choose(options, resume) =>
      options.flatMap(opt => resume(opt))
  }
```

---

## 13. Implementation phasing (post-v1.12)

### v1.12.1 — Type system + parser

1. Add `EffectRow` to `SType` and `effects: EffectRow` to `SType.Function`
   (`Types.scala:22`).
2. Extend `Unifier` to descend `EffectRow` with Rémy-style row unification
   (`Types.scala:174-209`).
3. Extend `InterfaceScope.TypeParser` with a `!` operator at precedence
   between `=>` and `|`; RHS switches to effect-set mode (round-paren list of
   names) to avoid tuple ambiguity (`InterfaceScope.scala:107-209`).
4. Extend `Parser.preprocessEffects` to recognise `multi effect`
   (`Parser.scala:928-958`).
5. Special-case `handle[Foo](body)` in the typer to emit a discharge
   constraint (`Typer.scala:195-233`).
6. `EffectAnalysis` migrates to verifier mode (warn on divergence from typer).
7. Diagnostics: wire §9 error messages.

### v1.12.2 — Optional runtime fast paths (historical plan)

1. **JS**: emit `function*`/`yield`/`iter.next(v)` for one-shot effect bodies
   in `JsGen.scala` (closes the gap documented in `specs/coroutines.md:236-256`).
2. **JVM / Interpreter**: optionally wire the existing coroutine VT path as a
   one-shot optimization; guarded `evalHandle` remains valid reference semantics.
3. Add the one-shot-violation dynamic check at the base continuation claim.
4. Verify behavioral parity between fast path and Free-monad path by running
   the existing `StdEffectsTest` / `RestartableTest` under both paths.

### v1.12.3 — Standard library + capability passing

1. Re-type `runLogger`, `runRandomSeeded`, etc. to carry discharge in the
   signature (see §8.2 table).
2. Add `Reader[R]` as a pure capability exemplar (no effect row — resolved
   via `?=>`).
3. Add `NonDet` as the canonical multi-shot exemplar (handler collects all
   results by re-resuming).
4. Add `examples/algebraic-effects.ssc` showcase (Logger + State + NonDet
   interleaved, capability-style and handler-style side by side).
5. Promote `EffectAnalysis` divergence warnings to errors.

---

## 14. Risks and open questions

### Row unification complexity

Rémy-style row unification is well-understood (~30 additional lines in the
unifier) but the new `EffectRow` shape must interact correctly with
substitution and the occurs check. Mitigation: row variables live in a
separate id space from regular type variables; the unifier tests drive
correctness.

### Round-paren effect-set vs tuple parsing

`(A, B)` is already a tuple type in `TypeParser`. The RHS of `!` is parsed
in **effect-set mode**: a boolean in the recursive-descent parser that
interprets `(X, Y)` as a named-effect set rather than a tuple. This is
unambiguous in practice — the `!` operator's RHS never expects a tuple type.

### Multi-shot opt-in granularity

`multi effect Foo { … }` marks the whole effect as multi-shot. An effect that
needs per-op granularity must split into two declarations. Acceptable for
v1.12 — most real multi-shot use cases (NonDet, backtracking, probabilistic
programming) are uniformly multi-shot.

### JS generator state not cloneable

JS generators cannot be cloned. Multi-shot handlers on JS must use the
Free-monad path regardless of what the effect declares. This is a hard JS
limitation; the spec documents it and the compiler silently uses the Free-monad
path on JS for all `multi effect` ops.

### `EffectAnalysis` migration safety

If the typer's inferred effect set diverges from what `EffectAnalysis`
computes by name-reachability, the warning fires. In practice, divergence only
happens for: (a) effects routed through reflection / native interop (already
unsafe), or (b) effects declared but never called (dead code). Neither warrants
a hard error until the typer is proven stable.

### Diagnostics quality

Unhandled-effect errors must trace the `perform` call chain back to the total
boundary. This requires the typer to annotate each `perform` site with the
call path. Implementation cost: store a `List[SrcPos]` in the `Perform` IR
node. Tracked as a required deliverable in v1.12.1.

---

## 15. Go / no-go recommendation

**Historical v1.12 recommendation: Go.** The architectural fit remains clean,
but the current portable implementation supersedes the old claim that a native
coroutine/generator path is required:

- The Free-monad substrate (`Computation = Pure | Perform | FlatMap`) gives
  multi-shot semantics for free — no new runtime needed, only a typed layer on
  top.
- The coroutine VT substrate can provide a one-shot optimization where qualified.
- JS generators can provide a native one-shot optimization but cannot replace the
  reusable representation.
- A backend advertises typed algebraic effects only after the complete primitive
  family and shared vectors qualify; new v2 JS/Rust/WASM do not yet advertise it.
- The only genuinely new work is: `EffectRow` in `SType`, Rémy-style row
  unification in `Unifier`, a `!` token in `TypeParser` at one new precedence
  level, and a `multi effect` keyword in `preprocessEffects`.
- Capability passing via `?=>` requires zero emitter work — scalameta passes
  Scala 3 context functions through already.

The risk surface is bounded and each phase is independently shippable. There
are no blockers — v2.0 (separate compilation), which was the previous blocker
per BACKLOG, landed on 2026-05-20.

**Implementation sequence:** v1.12.1 (types + parser) → v1.12.2 (runtime fast
paths) → v1.12.3 (stdlib + capabilities). Each phase can be reviewed and
merged independently. **Recommendation: start v1.12.1 immediately.**
