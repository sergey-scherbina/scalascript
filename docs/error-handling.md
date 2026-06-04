# Error handling — `throws[A, E]` + integration

Status: **design / planning**.  Decided pieces written into the
plan; sections marked **In design** capture ongoing thinking and
need another pass before locking.  Implementation tracked as
**v1.15 — Checked errors via `throws`** in MILESTONES.md, with
restartable errors as a v1.16 successor dependent on v1.12
algebraic-effects feasibility.

Companion to [`docs/direct-syntax.md`](direct-syntax.md) §DS-7
(thrown exceptions NOT auto-wrapped), [`docs/specs/final-tagless.md`](final-tagless.md)
(v1.13 — `using` resolution required for `Either[E, *]` monad
lookup), and [`docs/specs/coroutines.md`](coroutines.md) §6 (algebraic
effects layer where restartable errors live).

## 1. Motivation

What works today:

- **`Either[A, B]`** sum type with `mapRight` / `flatMapRight` /
  `foldEither` helpers (v1.1 std)
- **`MonadError[F, E]`** with `raise` / `handleError` / `attempt`
  for `Option`, partly for `Either` (v1.1)
- **DS-7 in direct-syntax** — `M.fail(...)` / `M.recover(...)`
  for monadic failure; thrown exceptions deliberately NOT
  auto-wrapped (v1.8)

What's missing:

1. **Ergonomic signature**: today `def parseInt(s: String):
   Either[ParseError, Int]` reads awkwardly; the natural form is
   `def parseInt(s: String): Int throws ParseError`.
2. **Direct-syntax auto-unpack of `throws`-typed values** —
   today the user threads the `Either` themselves; we want
   `id = parseInt(...)` to bind-or-short-circuit transparently.
3. **JVM/Scala exception interop** — what to do when the
   underlying platform (`Files.readAllBytes`, `s.toInt`,
   division-by-zero) throws.  DS-7 says no auto-wrap, but
   the stdlib still needs *some* story.
4. **Stack traces for our errors** — Either-encoded errors are
   plain values without a trace.  Real apps need diagnostics
   comparable to JVM exception traces.
5. **Restartable errors** (Common Lisp condition system) — the
   handler decides whether to resume the suspended computation
   with a replacement value vs abort.  Requires v1.12.

This document settles (1) and (2) as decided design, sketches
(3) and (4) as in-design, references (5) as future.

## 2. Decided design — `infix type throws[A, E]`

### 2.1 The type alias

```scala
infix type throws[A, E] = Either[E, A]
```

`Int throws ParseError` is sugar for `Either[ParseError, Int]`.
The error is in the *left* slot (canonical Scala convention for
"failure"); the success value sits on the right.  Net: every
existing `Either[E, A]` instance, helper, typeclass, and
extension method works on `A throws E` unchanged.

**Why Either-encoded, not union (`A | E`)**:

| Aspect | `throws[A, E] = Either[E, A]` (chosen) | `throws[A, E] = A \| E` (rejected) |
|--------|----------------------------------------|------------------------------------|
| Monad instance | ✅ existing `Monad[Either[E, *]]` from v1.1 | Would need a synthesised `Monad[A \| E, *]` (no clear shape) |
| Direct-syntax integration | ✅ free — auto-bind via std Monad | Needs ad-hoc adapter typeclass |
| Boxing | One allocation per error | Zero — value is its own type |
| Pattern match | `Right(n)` / `Left(e)` (familiar) | Plain `n: Int` / `e: ParseError` |
| Stack traces | Carried in the error value | Same — error value lives in stack |

Boxing cost is acceptable for the error path; direct-syntax
integration is the bigger win.  Locked.

### 2.2 Auto-conversions at the return site

Returning bare values is ergonomic; Scala 3 `Conversion[A, B]`
makes it work:

```scala
given [E, A] => Conversion[A, Either[E, A]] = Right(_)
given [E, A] => Conversion[E, Either[E, A]] = Left(_)
```

These two givens live in std (`std/error-handling.ssc`) and ship
implicitly.  At the return position of a function typed
`Int throws ParseError`, the user can write:

```scala
def parseInt(s: String): Int throws ParseError =
  if validInt(s) then s.toInt                  // → Right(s.toInt)
  else            then ParseError(s"bad: $s")  // → Left(ParseError(...))
```

Explicit `Right(...)` / `Left(...)` keeps working — the
conversions only fire when the bare type doesn't match the
expected type.

**Implementation cost**:

- Parser: `infix type` keyword (or recognise `type X infix` —
  Scala 3 syntax).  ~0.5 day.
- Typer: type-position desugar `A throws E` → `Either[E, A]`.
  Trivial once the alias is parsed.  ~0.5 day.
- Conversion givens: ship in std.  ~0.5 day.
- Cross-backend verify: INT / JS / JVM all already support
  Scala 3 conversions through v1.1 typeclass machinery.  ~1 day.

### 2.3 Direct-syntax integration

Inside `direct[Either[E, *]] { … }`:

```scala
type ResultS = [A] =>> Either[AppError, A]

def handler(req: Request): Response throws AppError = direct[ResultS] {
  id   = parseInt(req.params("id"))   // monadic bind on Either[AppError, Int]
  user = loadUser(id)                 // chains via std Monad[ResultS]
  Response.json(user)                 // pure tail, auto-lifts to Right
}
```

`=` is monadic bind (DS-2); the Monad instance for
`Either[AppError, *]` from v1.1 std handles `Left`-short-circuit
correctly.  Pure tail (`Response.json(...)`) auto-lifts via
`Monad.pure`.

**Prerequisite**: v1.13 must land first (`using` auto-resolution
for `Monad[Either[E, *]]`) AND cross-file trait inheritance
(`Either[E, *]` partial application).

### 2.4 Dual encoding — `throws` (canon) + `throwsRaw` (opt-in)

Either-encoded `throws` is the canonical default — ergonomic,
monad-integrated, direct-syntax friendly.  But there are two
narrow situations where the Either box has real cost:

1. **JVM/Scala exception interop** — the `catch` clause already
   produces a `Throwable` value; wrapping it in `Left(e)` is
   pure overhead.
2. **Hot-path parsers** (millions of operations / second) —
   allocation pressure of `Right(...)` per call shows up in
   profiles.

To address both without polluting the canonical surface, ship a
**companion** union-typed alias:

```scala
infix type throws[A, E]    = Either[E, A]    // canonical
infix type throwsRaw[A, E] = A | E           // opt-in: perf + interop
```

Plus bidirectional conversion helpers in std:

```scala
inline def box[A, E](raw: A | E): A throws E = raw match
  case e: E => Left(e)
  case a    => Right(a.asInstanceOf[A])    // type-test on E first

inline def unbox[A, E](boxed: A throws E): A | E = boxed match
  case Left(e)  => e
  case Right(a) => a
```

#### When to use which (the rule of thumb in std docs)

| Situation | Use | Reason |
|-----------|-----|--------|
| Default / business logic | `throws` | Composable, direct-syntax integration |
| Generic monadic code (`[F[_]: Monad]`) | `throws` | Has Monad instance |
| API across module boundary | `throws` | Stable, encoded form, no `A`-vs-`E` ambiguity risk |
| `direct[F] { … }` blocks | `throws` | Free monadic auto-bind |
| Interop with JVM exception via single `catch` | `throwsRaw` | Preserves native stack trace, zero box |
| Hot-path parser (millions / sec) | `throwsRaw` | Zero allocation per call |
| Internal helper inside one module where profile shows it | `throwsRaw` | Compiler can inline more aggressively |

**API boundary rule**: between modules always `throws`;
internal optimisations can use `throwsRaw` and convert at the
boundary.  Don't pollute downstream APIs with the perf-channel.

#### Restrictions on `throwsRaw`

Scala 3 unions require members to be **distinguishable at
runtime**.  `throwsRaw[Int, Int]` is a compile error — the type
tester can't tell which member was meant.  This is a hard
limit: when `A` and `E` overlap on runtime type, use `throws`
(which boxes both into distinct cases).

#### Stack-trace consequence

The dual encoding produces a **two-tier stack-trace model**:

| Encoding | Trace source | Cost |
|----------|--------------|------|
| `throwsRaw[A, E <: Throwable]` | native `Throwable.getStackTrace` | 0 (free) |
| `throws[A, E <: HasStackTrace]` | our `currentStackTrace()` capture | 1 stack walk per error |
| `throws[A, E]` where `E` has no trace mixin | trace absent — pure value | 0 |

This is cohabitation, not conflict: `throwsRaw[A, Throwable]`
inherits native machinery for free; `throws` over `HasStackTrace`
uses our uniform machinery; trace-less errors pay nothing.

#### Direct-syntax interaction

`direct[F]` integration is **`throws`-only** in v1.15.  Monad
instance on `A | E` requires inline-match-driven Monad
derivation (v1.14 territory), and even then `A`-vs-`E`
ambiguity restricts the cases where it's well-defined.

If you need direct-syntax over a raw value: box first.

```scala
val handled: Response throws AppError = direct[ResultS] {
  raw  = parseTokenRaw(buf, idx)   // throwsRaw[Token, ParseError] — fast
  tok  = box(raw)                  // throws[Token, ParseError] — monadic
  // ... rest of monadic flow
}
```

### 2.5 Locked policies (promoted from open questions 2026-05-18)

Four items that were carrying recommendations in §6 are now
**locked** with the recommendations as the final design.  Listed
here so a reader doesn't have to chase them through the open-
questions section.

#### 2.5.1 Adapter naming — `attemptCatch` / `attemptCatchRaw`

The Either-encoded lift is `attemptCatch[E <: Throwable] { … }`;
the union-encoded perf/interop lift is `attemptCatchRaw[E <:
Throwable] { … }`.  Both names ship in std.  The `Raw` suffix
mirrors `throwsRaw` and signals "perf channel — sees the
exception value directly, no box".

No alternative name (`safeCall`, `catching`, etc.) — verb-first
matches the rest of the std API (`requireNonNull`, `divideOrError`),
and the `attempt-` prefix mirrors `MonadError.attempt`.

#### 2.5.2 Stack-trace mixin — `HasStackTrace`, opt-in

The std convention is `trait HasStackTrace { def stackTrace:
List[Frame] }` — opt-in.  Error types that want a trace mix in
`HasStackTrace`; their constructor or factory calls
`currentStackTrace()` to capture.  Errors that don't mix it in
pay zero overhead — pure data.

Std additionally provides a `trait Error extends HasStackTrace`
convenience for the common case where an error wants both a
domain shape (case-class fields) AND a trace.  Users who
explicitly *don't* want a trace skip both mixins.

Rejected alternative: always-on capture for every error value
(~1-5% function-call overhead even when nobody reads the
trace).  Hot-path parsers care about this.

#### 2.5.3 Raw-form shim policy — measurement-driven, not speculative

Standard-library platform-exception shims (§3.2) ship in the
**`throws` form by default**.  A `Raw`-form companion
(`parseIntRaw`, etc.) is added **only when profiling
demonstrates measurable allocation pressure on that specific
helper in real code**.  Never on speculative basis.

Initial v1.15 shim set: all in `throws` form, no Raw variants.
If a downstream consumer (e.g. high-throughput JSON parser in
`std/json`) demonstrates the Either box is dominant, that's
when the Raw-form companion gets added — case-by-case, with
the benchmark in the PR.

#### 2.5.4 `throwsRaw` runtime-distinguishability — known limitation

Scala 3 unions require members to be distinguishable at
runtime.  `throwsRaw[Int, Int]` is a compile error: the type
tester can't tell whether a value of type `Int` was an "A" or
an "E".  This is **not an open question** — it's a documented
limitation of `throwsRaw`.

The escape hatch is `throws[Int, Int]` (Either-encoded), which
explicitly tags both sides with `Left`/`Right`.  When `A` and
`E` overlap on runtime type, the user uses `throws` instead;
the compiler error from `throwsRaw` is actionable.

#### 2.5.5 Unchecked `throw e` / `try`-`catch` — peer to `throwsRaw`

`throw e` and `try { … } catch case e: E => …` are **first-class
mechanisms** in ScalaScript and stay at the same tier as
`throwsRaw`: opt-in, low-level, for the narrow cases where the
canonical `throws` doesn't fit.

- Untyped at the signature level — `def f(x: Int): Y` does NOT
  declare what exceptions it might throw.  Callers learn from
  documentation, not from the type.
- Zero-allocation on the happy path (no `Right` / `Left` /
  union packing).
- Direct integration with JVM exception infrastructure — uses
  the underlying platform's stack unwinding, native trace, JVM
  finalizers, monitor unlock on `Thread.interrupt`, etc.
- Survives unchanged through direct-syntax blocks (DS-7): a
  thrown exception inside `direct[F] { … }` propagates out via
  normal stack unwinding, **NOT** auto-wrapped into `F.fail`.

#### Tier overview — when to use what

| Tier | Mechanism | Best for | Cost |
|------|-----------|----------|------|
| **1 — canonical** | `throws[A, E] = Either[E, A]` | Default for typed error flow.  Direct-syntax integrated.  Cross-module APIs. | 1 box per call |
| **2a — opt-in typed perf** | `throwsRaw[A, E] = A \| E` | Hot-path parsers; perf-critical inner loops where measurement shows Either-box dominates | 0 allocation |
| **2b — opt-in untyped escape** | `throw e` / `try`-`catch` | JVM exception interop with library code that already uses unchecked throws; cases where typed signature would lie (e.g. `OutOfMemoryError`) | 0 allocation (JVM-native) |
| **3 — monadic effects** | `MonadError[F, E]` | Inside custom `F[_]` effect types (Async, ZIO-style); reuses the v1.1 typeclass | 1 box, plus the F's overhead |
| **4 — restartable (future)** | algebraic-effects handler stack | Common Lisp condition-system style: handler decides resume / retry / abort | v1.16, depends on v1.12 |

#### How the three tier-2 forms bridge into tier 1

```scala
// 2b → 1: catch JVM exception, lift to Either
val a: Int throws NumberFormatException =
  attemptCatch[NumberFormatException] { s.toInt }

// 2b → 2a: catch JVM exception into union (preserves native trace)
val b: Int throwsRaw NumberFormatException =
  attemptCatchRaw[NumberFormatException] { s.toInt }

// 2a → 1: explicit boxing
val c: Int throws NumberFormatException = box(b)
val d: Int throwsRaw NumberFormatException = unbox(a)

// 1 → 2b: re-raise from typed channel (rare; usually user code
// already has a `Throwable` subtype, otherwise build a wrapper)
a match
  case Left(e)  => throw e         // E must be <: Throwable for this
  case Right(n) => doSomething(n)
```

#### Why unchecked exceptions stay

This is a **deliberate design choice**, not a temporary
compromise:

- **Some platform errors can't honestly be typed.**
  `OutOfMemoryError`, `StackOverflowError`,
  `InterruptedException` — these don't belong in a function's
  `throws E` signature; they're systemic, not domain errors.
  Unchecked is the right level.
- **Java/Scala library interop.**  Most existing library code
  throws; pretending otherwise would force `attemptCatch` at
  every call site, which is the boilerplate `throws` was
  supposed to remove.
- **Debugging & assertions.**  `assert(x > 0)`,
  `require(cond, msg)`, `???` — these throw on failure and
  should keep their stack-unwinding semantics.
- **Performance escape hatch.**  Some hot loops use exceptions
  as control flow (e.g. parser early-exit on syntax error).
  Removing the unchecked form would push these to monadic
  alternatives at real perf cost.

The tier model recognises that **no single error mechanism
fits all cases**.  `throws` is the right default; the other
tiers exist because they solve problems `throws` can't or
shouldn't.

#### 2.5.6 `throw` / `try`-`catch` inside `direct[F] { … }` — type-directed lowering

The same `throw e` / `try { … } catch case e: E => …` syntax
works **both** as JVM-native (tier 2b) and as monadic
failure / recovery (tier 1/3) — **distinguished by type
inference**.

#### Lowering rules

Inside `direct[F] { body }`:

| Form | Lowering | Trigger |
|------|----------|---------|
| `throw e` where `e: E` and `MonadError[F, E']` (with `E <: E'`) in scope | `F.fail(e)` | Monadic — typed bridge to `F`'s error channel |
| `throw e` where no `MonadError[F, _]` matches the type of `e` | JVM-native `throw e` (escapes via stack unwinding) | Tier 2b unchanged |
| `try body catch case e: E => h` where `MonadError[F, E']` in scope (`E <: E'`) | `F.handleError(body) { case e: E => h }` | Monadic — typed bridge |
| `try body catch case e: E => h` where no `MonadError[F, _]` matches `E` | JVM-native `try`-`catch` | Tier 2b unchanged |
| `try body catch { multiple cases }` — some types match `MonadError[F, _]`, others don't | Lower per-case: typed branches become `F.handleError`, untyped branches become JVM `catch` clauses on the lowered for-comprehension | Hybrid (rare, but supported) |

The discriminator is **purely the type of `e` (or the case
pattern in `catch`)** vs the available `MonadError[F, ?]`
instances in scope at the call site.  No new keyword, no
annotation — the typer routes by the `using`-resolution
machinery from v1.13.

#### How this relates to DS-7

DS-7 said "thrown exceptions are NOT auto-wrapped into
monadic failure" — that holds for **untyped escapes**:
`throw new RuntimeException(...)` inside a direct block where
no `MonadError[F, RuntimeException]` is in scope keeps
escaping via the JVM channel.

The new rule narrows DS-7: when the user *did* write a
type-annotated throw (`throw e: AppError`) and the
surrounding `direct[F]` has `MonadError[F, AppError]`
available, the typer takes that as an explicit ask for the
monadic bridge and lowers accordingly.  The two-fault-model
trap is avoided because the user didn't get implicit
behaviour — the lowering is driven by what they *did*
type at the call site.

#### Worked example

```scala
type ResultS = [A] =>> Either[AppError, A]

def handler(req: Request): Response throws AppError = direct[ResultS] {
  // 1. Typed throw → monadic Left
  if !req.valid then throw AppError.BadRequest        // → Left(AppError.BadRequest)

  // 2. Typed try-catch → monadic handleError
  user = try loadUser(req.id)
         catch case _: NotFound => User.guest         // F.handleError lowering

  // 3. Untyped throw → JVM unchecked (no MonadError[ResultS, AssertionError])
  if user.age < 0 then assert(false, "impossible")    // escapes via stack

  // 4. Mixed catch — some typed, some untyped
  data = try fetchData(user)
         catch
           case e: AppError       => fallback         // typed → F.handleError clause
           case e: IOException    => throw new RuntimeException("io", e)
           // untyped → JVM catch clause wrapping the for-comp lowering

  Response.ok(data)
}
```

#### Why this works on top of existing infrastructure

- **No new compiler concept** — uses v1.8 direct-syntax
  desugarer + v1.13 `using` resolution.  The desugarer gets
  one extra rule: "before emitting `throw` / `try`-`catch`
  in lowered output, query `MonadError[F, ?]` for the type
  involved and prefer monadic if found."
- **No new SPI primitives** — `MonadError` is already in
  v1.1; direct-syntax already lowers to for-comprehension;
  this is one more case in the lowering table.
- **No runtime cost** — when the discrimination is made at
  compile time, the emitted code is either pure monadic or
  pure JVM-native.  No runtime branch.

#### Open questions (carry into v1.15 implementation)

- **Catch-pattern with `_: Throwable`** inside direct block —
  catches everything, including monadic failures lifted into
  `F.fail`?  Probably should: if user writes `case _:
  Throwable`, they want the universal catcher.  Lower to
  `F.handleError(_ => h)` plus a wrapping JVM `try`-`catch`
  for any unchecked that didn't go through the monadic path.
- **`throw` of a value that matches MonadError[F, E] for
  multiple F in scope** — error at compile time per Scala 3
  ambiguous-resolution rules; same as ordinary `using`
  ambiguity.
- **Tail-position `throw`** — pure-tail `throw e` should
  lower to `F.fail(e)` directly (no need for the surrounding
  for-comprehension); typer detects tail position.

### 2.6 Error subtyping

```scala
def loadUser(id: Int): User throws (NotFound | Forbidden) =
  // ...

def handler(req: Request): Response throws AppError = direct[ResultS] {
  user = loadUser(req.params("id"))   // throws (NotFound | Forbidden)
                                      // widens to throws AppError
                                      // (assuming NotFound <: AppError, Forbidden <: AppError)
  ...
}
```

`throws[A, E1] <: throws[A, E2]` when `E1 <: E2`.  This is just
the existing variance of `Either[E, A]` in `E` — Scala 3 handles
it natively once the alias is in place.  No extra work.

**Multiple errors via Scala 3 union types**:
`Int throws (ParseError | OverflowError)` — works directly,
nothing special to add.

## 3. In-design — JVM/Scala exception interop

This section needs another pass before locking.  Current
proposal:

### 3.1 The two-fault-model problem

We can't pretend platform exceptions don't exist.  They come from:

- **Standard library calls** — `s.toInt` throws `NumberFormatException`
- **IO operations** — `Files.readAllBytes` throws `IOException`
- **Arithmetic** — `x / 0` throws `ArithmeticException`
- **JVM internals** — `OutOfMemoryError`, `StackOverflowError`,
  `NullPointerException` from null-deref
- **Third-party libraries** — anything outside our control
- **User code that uses `throw`** — escape hatch

DS-7 (locked in v1.8) says thrown exceptions are NOT
auto-wrapped into monadic failure.  Why: the "two-fault-model
trap" — some errors are monadic (`Left(...)`), others are
exceptions (`throw e`), and users can never remember which is
which.  Auto-wrap makes the two camps indistinguishable, which
is worse than picking one camp and forcing the other to be
explicit.

So our story is: **`throws[A, E]` is the canonical error
channel; platform exceptions stay in their own channel and
require explicit lifting to cross over**.

### 3.2 Strategy A — Standard-library shims

For common platform exceptions, ship `throws`-typed shims in std:

```scala
// In std/error-handling.ssc
def parseInt(s: String): Int throws NumberFormatException =
  try Right(s.toInt)
  catch case e: NumberFormatException => Left(e)

def parseLong(s: String): Long throws NumberFormatException = ...
def parseDouble(s: String): Double throws NumberFormatException = ...

// In std/io.ssc (when it ships)
def readFile(path: String): String throws IOException = ...
```

Cost: bake the lift into each helper once, users get `throws`
form for free.

Trade-off: only as good as the coverage.  Operations not yet
shimmed surface the raw JVM exception.

### 3.3 Strategy B — Explicit `attemptCatch[E] { … }`

Universal opt-in adapter:

```scala
inline def attemptCatch[E <: Throwable, A](inline body: A): A throws E =
  try Right(body)
  catch
    case e: E => Left(e)
    // Re-throw anything we weren't told to catch
    case other: Throwable => throw other

// Usage
val result = attemptCatch[IOException] { Files.readAllBytes(path) }
//           result: Array[Byte] throws IOException
```

`E <: Throwable` is the explicit "I'm reaching into the
platform exception channel and lifting one class of failure
into the typed channel" gate.

Trade-off: users must write `attemptCatch` whenever they call
unshimmed platform code.  Verbose but explicit.

### 3.4 Strategy C — Universal lift in direct blocks

A direct-syntax block could catch all `Throwable` and lift to
`Left`, with a configurable error type:

```scala
// Hypothetical — NOT proposed
def handler(req: Request): Response throws AppError =
  direct[ResultS](onThrowable = AppError.fromThrowable) {
    user = loadUser(req)   // even if `loadUser` throws, we lift to Left
    Response.json(user)
  }
```

**Rejected** — re-introduces DS-7's two-fault-model trap.  The
direct block hides whether an error was monadic or thrown.

### 3.5 Recommendation

**Strategy A + Strategy B together**:

- **Strategy A** for std-lib coverage: every common platform
  operation ships as a `throws`-typed shim.  Users prefer the
  shim; raw platform call stays available for power use.
- **Strategy B** for user-side opt-in: when the user calls
  third-party Java/Scala code that throws, they wrap with
  `attemptCatch[IOException] { … }`.

**Strategy C** stays rejected per DS-7.

Locked decision when this section lands: which platform
operations get shimmed in v1.15 vs later.  Tentative v1.15
shims: `parseInt`, `parseLong`, `parseDouble`,
`requireNonNull`, division-with-default.  IO shims (`readFile`,
`writeFile`) defer to v1.5 Tier 2-4 (the HTTP/IO stack).

## 4. In-design — stack-trace simulation

User asks for "наши собственные стектрейсы похожие на обычные,
но сделаны по-нашему" — diagnostic-equivalent traces, built
our way for cross-backend consistency.

### 4.1 Why not just use Throwable.getStackTrace

- Either-encoded errors are values, not exceptions.  No
  `getStackTrace` exists on `Right(...)`.
- JVM's stack trace is JVM-only.  JS has its own format
  (different parser per browser/Node version).  Interpreter
  has neither.
- We want uniform observable behaviour across backends.

### 4.2 Design — frame chain in runtime context

Each backend maintains a per-thread (per-coroutine, post-v1.9) **call frame chain**: a stack of
`Frame(file, line, fn)` pushed on function entry, popped on
return.  Error construction snapshots the current chain.

```scala
trait HasStackTrace:
  def stackTrace: List[Frame]

case class Frame(file: String, line: Int, fn: String):
  override def toString = s"  at $fn ($file:$line)"

// `fail` helper that auto-captures
def fail[E <: HasStackTrace](e: E): Left[E, Nothing] = Left(e)

// User code
case class ParseError(msg: String, stackTrace: List[Frame]) extends HasStackTrace

def parseInt(s: String): Int throws ParseError =
  if invalid then fail(ParseError(s"bad: $s", currentStackTrace()))
  else Right(s.toInt)

// At the error site, observable trace:
//   ParseError(bad: x)
//     at parseInt (lib.ssc:42)
//     at handler  (route.ssc:17)
//     at <route GET /user/:id> (server)
```

### 4.3 Per-backend implementation

| Backend | Frame source | Overhead |
|---------|--------------|----------|
| **INT** | Existing position tracker (`Interpreter.scala` `trackPos`) — already counts call frames | Negligible; trace is free |
| **JVM** | Walk `Thread.currentThread.getStackTrace`, filter to user code | ~1µs per error (acceptable for the error path) |
| **JS** | Parse `new Error().stack`; or maintain our own chain via Source-position injection on `Term.Apply` | ~1µs error, ~5% function-call overhead if we go own-chain |

For consistency: the **frame format is identical** across
backends — `Frame(file, line, fn)`.  Each backend's runtime
exposes `currentStackTrace(): List[Frame]` which user code
calls (directly or via `fail` helper) at error construction.

### 4.4 Always-on vs opt-in

Trade-off:

- **Always-on**: every error value carries a trace.  Cost:
  ~1-5% function-call overhead.  Most apps prefer this.
- **Opt-in**: errors carry a trace only when they extend
  `HasStackTrace` and call `currentStackTrace()`.  Zero
  overhead when not used.  Hot-path parsers care.

**Recommendation**: opt-in via `HasStackTrace` mixin.  Std
errors all extend `HasStackTrace`; users opt-out by *not*
extending it.  Default cost is paid only on the error path
(error construction does one stack walk); steady-state has no
overhead.

### 4.5 Restartable errors interaction

Restartable errors (v1.16, dependent on v1.12) need the stack
**live**, not snapshotted — the handler resumes the actual
coroutine at the throw point.  Stack traces capture a
*snapshot* for diagnostics; restarts require suspension of the
*real* stack.

These are complementary mechanisms.  Snapshots for "here's
where the error happened"; live stacks for "handler resumes
into the original frame."  Both can ship.

## 5. Hard-no list (locked by design)

| Feature | Reason |
|---------|--------|
| **Auto-wrap thrown exceptions** in direct blocks | DS-7 (locked); the two-fault-model trap |
| **Removing or deprecating unchecked `throw` / `try`-`catch`** | First-class peer to `throwsRaw` per §2.5.5.  Not going away; not migrating to Either |
| **`A \| E` union encoding as the *default* `throws`** | Either-encoded is canon; union ships as opt-in `throwsRaw` companion (see §2.4) |
| **Auto-conversion across the `throws` / `throwsRaw` boundary** | Explicit `box` / `unbox` only — auto-coercion would hide the perf intent |
| **`throwsRaw` as the direct-syntax target** | `throws` (Either) is the only direct-syntax-integrated form; raw must `box` before entering a `direct[F]` block |
| **Std-lib shim duplicates** (`parseIntRaw` etc.) on speculative basis | Add `Raw`-variant only when profiling shows real benefit on a specific helper |
| **Java-style checked-`throws` clauses on signatures** (`def f() throws IOException`) — separate from the return type, compiler-enforced handling, stack propagation | Re-introduces parallel error-tracking machinery alongside `throws[A, E]` type alias; pick one path.  Note this targets the *checked* form — unchecked `throw e` stays per §2.5.5 |
| **`throws` on JVM `Throwable` subtypes only** | Restricting `E` to `Throwable` would tie us to JVM; `throws` works over any type |
| **Effect-row tracking for errors** (`F[A] throws E1 \| E2 ...`) | v1.12 algebraic effects territory; out of scope here |

## 6. Open questions

These do **not** block the v1.15 decided portion (§2).  Lock
when first usage emerges.

- **Final exact std-lib shim list for v1.15** — initial
  tentative: `parseInt`, `parseLong`, `parseDouble`,
  `requireNonNull`, `divideOrError`.  IO shims defer to v1.5
  Tier 2-4.  The exact contents of the v1.15 ship list lock
  at implementation time once edge cases (e.g. `parseHex`,
  `parseTimestamp`) get specific requests.
- **Stack-trace verbosity tuning** — collapse synthetic frames
  (the trampoline / coroutine machinery) into the user view?
  `--trace=internal` flag for full unfiltered?  Operational
  decision; defer until v1.15 lands and users complain about
  noisy traces (or don't).
- **Restartable handlers and snapshot traces** — does a
  restart preserve the original error's snapshot, or build a
  new one when the handler retries?  Probably the original;
  user can capture a new one if they want.  Lives in the
  v1.16 design, not v1.15.
- **Capture cost on hot paths** — measure when v1.15 lands;
  add a `noTrace` modifier on case classes if the overhead
  shows up.  Until v1.15 ships there's nothing to bench.
- **Cross-backend trace format normalisation** — `Frame(file,
  line, fn)` is uniform, but the *content* of `fn` differs:
  JVM shows mangled JVM names, JS shows source names, INT
  shows the user's definition site.  Worth a normalisation
  pass for consistent output; tackle when the first cross-
  backend diagnostic mismatch surfaces.
- **Java/Scala interop on the IDE side** — if a `throws`-typed
  value is passed to Java code, does it see `Either[E, A]`?
  Yes — `throws` is pure type alias, the runtime representation
  is `Either[E, A]`.  Document this for the IDE/refactoring
  story when v2.0 separate compilation arrives.

### Already locked (cross-reference §2.5)

These were carrying recommendations and are now locked:

- **`attemptCatch` / `attemptCatchRaw`** naming — see §2.5.1
- **`HasStackTrace`** opt-in mixin convention — see §2.5.2
- **Raw-form shim policy** — measurement-driven, never
  speculative — see §2.5.3
- **`throwsRaw` runtime-distinguishability** — known
  limitation, escape hatch is `throws` — see §2.5.4

## 7. v1.15 scope (decided portion)

- `infix type throws[A, E] = Either[E, A]` shipped in std
- `infix type throwsRaw[A, E] = A | E` shipped in std as opt-in companion
- Two auto-conversion givens at return site (for `throws` form)
- `box[A, E](A | E): A throws E` / `unbox[A, E](A throws E): A | E`
- Direct-syntax integration for `throws` (depends on v1.8 + v1.13)
- Error subtyping (free via Scala 3 type rules)
- Std-lib platform-exception shims in `throws` form (initial set above)
- `attemptCatch[E <: Throwable] { … }` — Either-encoded lift
- `attemptCatchRaw[E <: Throwable] { … }` — union-encoded lift,
  preserves native trace, zero allocation
- Conformance: 7-9 tests covering each piece (one for the raw form
  and the conversion round-trip)

## 8. v1.16 scope (future, depends on v1.12)

- Restartable errors via algebraic-effects handler stack
- Handler resumes the suspended coroutine with a replacement value
- Patterns: `useDefault`, `retry(args)`, `abort(value)`,
  `transform(error)`
- Only ships if v1.12 feasibility study says "go" — otherwise
  retire this section

## 9. Conformance plan

### v1.15 decided tests (8)

| Test | Exercises |
|------|-----------|
| `throws-basic.ssc` | `throws[A, E]` ≡ `Either[E, A]`, conversions both directions |
| `throws-raw.ssc` | `throwsRaw[A, E] = A \| E`, pattern-match on union, `box`/`unbox` round-trip |
| `throws-direct-syntax.ssc` | Auto-unpack inside `direct[Either[E, *]] { … }` (Either form only) |
| `throws-subtyping.ssc` | `E1 <: E2` ⇒ `throws[A, E1] <: throws[A, E2]` (both encodings) |
| `throws-multi-error.ssc` | `Int throws (ParseError \| OverflowError)` round-trip |
| `throws-shim.ssc` | Std `parseInt` etc. return `throws`-typed values |
| `throws-attempt-catch.ssc` | `attemptCatch[IOException] { … }` Either lift |
| `throws-attempt-catch-raw.ssc` | `attemptCatchRaw[IOException] { … }` union lift; native stack trace preserved on JVM |

### v1.15 in-design tests (2)

| Test | Exercises |
|------|-----------|
| `throws-stack-trace.ssc` | `HasStackTrace` mixin; trace captured at construction; format identical across INT/JS/JVM |
| `throws-stack-restart.ssc` | (Defer to v1.16 — restartable handler resumes from snapshot) |
