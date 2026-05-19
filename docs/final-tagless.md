# Final Tagless / typeclass UX

Status: **design / planning**.  Implementation tracked as
**v1.13 — Final Tagless ergonomics** in MILESTONES.md.  Companion
to [`docs/coroutines.md`](coroutines.md) (program-as-control-flow),
[`MILESTONES.md` v1.11.5](../MILESTONES.md) (`Free[F, A]` —
program-as-data), and [`docs/direct-syntax.md`](direct-syntax.md)
(monadic-block sugar).

This document is the source of truth for what Final Tagless looks
like in ScalaScript once landed, the typer dependencies it requires,
and how it sits alongside the other monadic-code mechanisms.

## 1. Where we are today

ScalaScript already ships **most of the conceptual prerequisites**
for Final Tagless after v1.1:

| Capability | Status |
|------------|--------|
| HKT in traits (`trait Functor[F[_]]`) | ✅ ships with v1.1 |
| `given X: Y with { … }` instances of typeclasses | ✅ ships with v1.1 |
| `summon[T]` explicit instance lookup | ✅ ships with v1.1 (`typeclass` conformance test) |
| Standard library written in FT style (`Monad[List]`, `Monad[Option]`, …) | ✅ ships with v1.1 |
| Cross-file imports of `given` instances + extension methods | ✅ since v1.1 step 2 fix |
| **`using` clause auto-resolution** from in-scope `given`s | ❌ blocked |
| **Context bounds** `[F[_]: Monad]` syntax | ❌ depends on `using` |
| **Cross-file trait inheritance with HKT** (`trait HttpClient[F[_]] extends Monad[F]`) | ❌ partial; cross-file `extends` breaks JVM compile |
| **Sealed-trait extension dispatch** in interpreter | ❌ `Right(_)` doesn't pick up extensions registered on `Either` |

Net effect: **FT works architecturally** — you can declare
algebras, build instances, summon them.  But it's not **ergonomic**:
every typeclass parameter must be passed explicitly
(`combineAll(xs, intSum)` rather than `xs.combineAll`).  Closing the
four typer gaps above turns idiomatic FT from "possible with care"
into "the default mode of std".

## 2. Motivation

Standard library v1.1 already follows the FT pattern under the hood
— every helper takes a typeclass instance as an explicit parameter
because `using` doesn't auto-resolve:

```scala
// Today
def combineAll[A](xs: List[A], m: Monoid[A]): A =
  xs.foldLeft(m.empty)(m.combine)
combineAll(List(1, 2, 3), intSum)                  // explicit instance

// After v1.13
def combineAll[A: Monoid](xs: List[A]): A =
  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
combineAll(List(1, 2, 3))                          // resolved via given intSum
```

The user-visible win is **less ceremony for the common case**;
the typer-internal win is **typeclass composition becomes
declarative** (`def doIt[F[_]: Monad: Console]: F[Unit]` rather
than a four-arg explicit-pass signature).

This is the same shape Scala 3, cats, ZIO, and every modern FP
language has converged on.  ScalaScript already has the typeclass
infrastructure; what's missing is the syntactic UX layer.

## 3. The four typer dependencies

### 3.1 `using` clause auto-resolution

**Today:** `(using m: Monoid[A])` parameters require explicit
caller-side passing.  `summon[Monoid[A]]` works inside the body but
the call site still writes `f(x)(myMonoid)` — there's no resolver.

**Target:** at every call site `f(x)` where `f` has a `using`
parameter list, the typer walks the in-scope `given` instances and
selects a unique match.  If exactly one matches, inject it; if zero
or multiple, raise an actionable error.

**Implementation:**

- Typer pass after argument-resolution, before method-dispatch.
- For each `(using T1, T2, …)` parameter list, build a set of
  candidates from in-scope `given` declarations whose type matches.
- Standard Scala 3 priority rules: more-specific given wins; same-
  specificity is ambiguous (error).
- Rewrite the `Apply` node to include the resolved arguments
  before lowering.

**Cross-backend cost:**

- INT: ~3-4 days — new resolver pass on `Term.Apply` in
  `Interpreter.scala`.
- JsGen / JvmGen: ~1 day each — typer pass shared (it's a typer
  feature, not a codegen feature).  Backends emit the result the
  same way `summon[T]` already lowers.

### 3.2 Context bounds `[F[_]: M]` syntax

**Today:** `def fold[F[_]: Foldable](fa: F[Int])` — parser likely
accepts the shape but the desugarer doesn't transparently lower it
to `(using Foldable[F])`.

**Target:** `[F[_]: M1: M2]` desugars to a `(using M1[F], M2[F])`
parameter list, appended to the existing parameter list.  Standard
Scala 3 semantics.

**Implementation:** parser-level desugaring at AST construction
time; touches `Defn.Def` / `Decl.Def` parsing in scala-meta's
parser config (already handled by upstream — verify).  Most likely
this just works once §3.1 lands, since the desugared form is then
resolvable.

**Cross-backend cost:** ~0.5 day, mostly verifying.

### 3.3 Cross-file trait inheritance with HKT

**Today:** `trait Traversable[T[_]] extends Foldable[T]` works
within one file (v1.1 step 3) but `trait Traversable[T[_]] extends
Functor[T], Foldable[T]` across files breaks the JVM compile when
`Functor` lives in `std/functor-applicative-monad.ssc`.

**Target:** any imported trait can appear in an `extends` clause,
regardless of source file.  HKT parameters propagate correctly
across the import boundary.

**Implementation:** the typer's import-resolution pass needs to
carry trait *definitions* (not just instances) into the consumer's
scope.  Today imports propagate `given` instances and extension
methods; trait definitions are erased at the interpreter and lost
at the JVM compile boundary.

**Cross-backend cost:** ~3-4 days.  INT: register imported traits
into a trait-symbol table.  JVM: emit imported traits at the top of
the generated Scala script (same way givens are inlined today).
JS: erase as before (JS doesn't have traits at runtime).

### 3.4 Sealed-trait extension dispatch in interpreter

**Today:** an `extension [A](fa: Either[E, A])` registers under
key `"Either"`.  A `Right(x)` value carries `typeName = "Right"`,
which doesn't match.  Net: extensions on sealed-trait types miss
dispatch on case-class subtype values.  v1.1 worked around this by
shipping helper functions for `Either` (steps 5, 6).

**Target:** at extension-dispatch time, when a value's exact type
name doesn't match, walk the sealed-trait parent chain and retry.

**Implementation:** add a sealed-trait → case-class registry built
at trait/class definition time.  `extensionDispatch` looks up the
sealed-parent chain of `Right` → `Either`, retries with the parent
type name.  ~100 LOC interpreter change.

**Cross-backend cost:** INT only — ~2 days.  JS already handles
this via the `_typeOf` machinery from v1.1 step 4.  JVM relies on
Scala's own dispatch, which handles inheritance natively.

## 4. What good FT looks like once landed

### 4.1 Defining an algebra

```scala
trait Console[F[_]]:
  def readLine: F[String]
  def writeLine(s: String): F[Unit]

object Console:
  // `apply` for `Console[F].method` syntax (standard FT convention)
  def apply[F[_]](using c: Console[F]): Console[F] = c
```

### 4.2 Writing a program against the algebra

```scala
def greet[F[_]: Console: Monad]: F[String] =
  for
    _    <- Console[F].writeLine("name?")
    name <- Console[F].readLine
    _    <- Console[F].writeLine(s"hi $name")
  yield name
```

`[F[_]: Console: Monad]` desugars (§3.2) to `(using Console[F],
using Monad[F])`.  `Console[F]` syntax is `Console.apply[F]`
which `summon`s the `Console[F]` instance (§3.1).

### 4.3 Two interpreters for one program

```scala
// Production
given consoleAsync: Console[Async] with
  def readLine             = Async.delay(scala.io.StdIn.readLine())
  def writeLine(s: String) = Async.delay(println(s))

// Test (collecting log)
given consoleTest: Console[StateLog] with
  def readLine             = StateLog.pop
  def writeLine(s: String) = StateLog.append(s)

// Same program, two outcomes
runAsync { greet[Async] }                       // production
val (log, _) = greet[StateLog].run(Log.empty)   // test
```

### 4.4 Layered algebras

```scala
// HTTP needs Console for logging + IO for transport
trait Http[F[_]] extends Monad[F]:
  def get(url: String): F[Response]

def fetchAll[F[_]: Http: Console](urls: List[String]): F[List[Response]] =
  for
    _   <- Console[F].writeLine(s"fetching ${urls.size}")
    rs  <- urls.traverse(Http[F].get)
    _   <- Console[F].writeLine(s"done")
  yield rs
```

`Http[F] extends Monad[F]` is a sub-algebra — captures the
constraint that any `Http`-implementing `F` is automatically a
monad.  Requires §3.3 if `Monad` lives in a separate file.

## 5. Coexistence with the rest of the stack

FT is one of four mechanisms ScalaScript has for "monadic code over
some effect".  Each has its own sweet spot.

| Mechanism | When to use | Where |
|-----------|-------------|-------|
| **Final Tagless** (this doc, v1.13) | Define an algebra once, plug different interpreters in.  Best for multi-target code (production / test / mock / browser). | Idiomatic for typeclass-heavy std code |
| **Free monad** (v1.11.5) | Program-as-data: inspect, transform, serialize, replay.  Optimization passes over the program tree. | Heavy-weight, when data-as-value matters |
| **Direct syntax** (v1.8) | Sequencing for a single concrete monad.  Sugar over `for { x <- e } yield body` for the 80% case. | Inside an FT polymorphic function body |
| **Algebraic effects** (v1.12) | Handler stack with re-raise semantics.  Same expressive power as FT + Free combined; different ergonomics. | Feasibility study; commit only if real consumers surface |

**Synergy points** (these aren't conflicts — they compose):

- **FT + direct-syntax**: `def greet[F[_]: Console: Monad]: F[String]
  = direct[F] { … }`.  Direct syntax over `F[_]` requires §3.1
  resolved (the typer needs to find `Monad[F]` from the context
  bound to use as the desugarer's pure-lift).  Direct syntax DS-1
  becomes more powerful once FT lands.
- **FT + Free**: write the algebra `Console[F]`, then define
  `given consoleFree: Console[Free[ConsoleF, *]] with { … }` — a
  Free interpreter that returns the unfolded program tree.
  Standard cats-free interop pattern.
- **FT + coroutines**: `Console[Async]` (v1.11) builds on coroutine
  primitives invisibly.  User code sees `Console[F]`; the runtime
  uses coroutines under the hood for the `Async` interpreter.

## 6. Implementation phases (v1.13)

Six phases over ~2 weeks.  Each phase is its own PR.

### Phase 1 — `using` auto-resolution (INT) (~3 days)

- Typer pass over `Term.Apply` nodes in `Interpreter.scala`.
- In-scope `given` table built as the interpreter walks the AST.
- Resolver: match parameter types against in-scope candidates.
- Error path: zero matches → "no given for `Type`"; multiple → "ambiguous given for `Type`: …".
- Conformance: `tagless-using-int.ssc` exercises the resolution
  with mono / poly / ambiguous cases.

### Phase 2 — `using` auto-resolution (JS) (~1 day)

JS-codegen passes the typer-resolved arguments through to `_call`
emit.  The typer-side work is done by Phase 1; this is glue.

### Phase 3 — `using` auto-resolution (JVM) (~1 day)

JVM-emitted Scala source declares the `(using …)` parameter list
as-is; Scala 3's own resolver picks up the slack.  Need to ensure
typer's normalization preserves the `using` modifier in `Defn.Def`
through to emit.

### Phase 4 — Context bounds desugaring (~0.5 day)

Parser-level: `[F[_]: M1: M2]` → `(using M1[F], M2[F])` appended.
Verify across the three backends with one conformance test.

### Phase 5 — Cross-file trait inheritance (~3-4 days)

- INT: register imported traits in a per-Interpreter trait-symbol
  table.  Lookup during `extends`-resolution.
- JVM: emit imported trait definitions at the top of the generated
  Scala script when any user trait extends them.
- JS: ensures trait erasure doesn't drop imported names from
  type-position references.
- Conformance: `tagless-multi-file.ssc` builds `trait Http[F[_]]
  extends Monad[F]` across two files, verifies all three backends.

### Phase 6 — Sealed-trait extension dispatch (INT) (~2 days)

- Build sealed-parent registry at trait/class definition time.
- `extensionDispatch` walks parent chain on miss.
- Conformance: re-enable the `bimap` / `handleError` extension-
  method versions for `Either` (currently shipped as helpers per
  v1.1 step 5/6 carryover).

### Phase 7 — Conformance + std polishing (~2 days)

- Six conformance tests covering the four typer dependencies.
- Rewrite `std/semigroup-monoid.ssc` helpers from
  `combineAll(xs, intSum)` to `[A: Monoid] => xs.combineAll`.
  Same observable behaviour, less boilerplate at the call site.
- Update `MILESTONES.md` carryover section: items 1 (`using` auto-
  resolution) and 4 (sealed-trait dispatch) marked as landed here;
  item 3 (`Term.Ascribe`) ✓ landed 2026-05-19 as standalone fix.

## 7. Hard-no list (closed by design)

| Feature | Reason |
|---------|--------|
| **User-defined macros** (Scala 3 `inline def` + `quoted.Expr`) | Out of scope; defer to a separate metaprogramming milestone with its own design |
| **Implicit conversions** that ferry values across effect boundaries | Reintroduces the two-fault-model trap (DS-7 of direct-syntax); use explicit `lift` |
| **`given` priority inheritance** beyond Scala 3 rules | Match upstream behaviour; custom resolution is a maintenance burden |
| **Effect-row tracking** (`[F[_]: (Console & Logger)]`) | Tracked under v1.12 algebraic-effects study; would need type-system extension |
| **Auto-deriving FT instances from concrete types** (`Console[List]` derived from `Monad[List]` automatically) | Confusing failure modes; explicit instances are the convention |

## 8. Open questions

These do **not** block v1.13 — they need answers when the
question first matters in real code.

- **Ambiguous-resolution UX.**  Standard Scala 3 raises a compile
  error.  Do we want a `@PreferThis` annotation or a fallback rule
  for the case where two equally-specific givens exist?  Lock when
  the first real ambiguity surfaces in std code.

- **Implicit conversions from FT algebras to concrete types.**
  `given Conversion[Async[A], MyApp[A]]` — useful for interop with
  pre-FT user code that doesn't take type parameters.  Land if a
  migration pattern demands it; not in v1.13.

- ~~**`derives` mechanism for FT instances.**~~  **Promoted to
  v1.14** — `case class Person(...) derives Eq, Show` lands in
  the metaprogramming MVP alongside `inline`.  See
  [`docs/metaprogramming.md`](metaprogramming.md).

- **Specific guidance: when to use Final Tagless vs Free vs direct
  syntax?**  Today's table in §5 is rule-of-thumb; needs sharpening
  once real usage patterns emerge in `std.http` (Stage 5+/B) and
  `std.ws`.

- **FT discoverability for newcomers.**  The pattern is powerful
  but unfamiliar to non-FP backgrounds.  Need a `examples/tagless-
  intro.ssc` worked tutorial — but the right shape depends on what
  ergonomics §3.1-3.4 actually ship with.

- **Multi-param given lookup performance.**  `(using M1, M2, M3,
  M4)` resolution time on INT — does the linear walk over in-scope
  givens scale to a real `std.*` import set?  Premature optimization
  to address now; benchmark when v1.13 ships and react.

- **Interop with v1.8 direct-syntax DS-1.**  Direct syntax's
  type-directed monad inference (DS-1) should pick up the
  context-bound `[F[_]: Monad]` automatically.  Verify when
  v1.8 ships.

## 9. Conformance plan

Six tests under `conformance/`, each running on all three backends:

| Test | Exercises |
|------|-----------|
| `tagless-resolution.ssc` | §3.1: `using` auto-resolution with single + chain + ambiguous cases |
| `tagless-context-bounds.ssc` | §3.2: `[F[_]: M]` desugaring + chained bounds `[F[_]: M1: M2]` |
| `tagless-multi-file.ssc` | §3.3: `trait Http[F[_]] extends Monad[F]` across files |
| `tagless-sealed-dispatch.ssc` | §3.4: extension on `Either` reachable from `Right`/`Left` in INT |
| `tagless-program.ssc` | §4: full Console example with two interpreters (Async + State) |
| `tagless-direct-syntax.ssc` | §5 synergy: `direct[F] { … }` with `[F[_]: Monad]` context bound |

Each test asserts cross-backend identical observable output.

## 10. Carryover updates

After v1.13 lands, the `Interpreter ergonomics — carried over from
v1.1` section in MILESTONES.md needs three edits:

- Item 1 (`using` auto-resolution) → marked **landed** with v1.13.
- Item 4 (sealed-trait extension dispatch in interpreter) → marked
  **landed** with v1.13.
- Item 3 (`Term.Ascribe`) → ✓ landed 2026-05-19 as standalone fix,
  unrelated to FT.

The two items that move out aren't deleted; they reference v1.13's
landing commit so the historical link is preserved.
