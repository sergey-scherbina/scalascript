# Error handling — `throws[A, E]` + integration

Status: **design / planning**.  Decided pieces written into the
plan; sections marked **In design** capture ongoing thinking and
need another pass before locking.  Implementation tracked as
**v1.15 — Checked errors via `throws`** in MILESTONES.md, with
restartable errors as a v1.16 successor dependent on v1.12
algebraic-effects feasibility.

Companion to [`docs/direct-syntax.md`](direct-syntax.md) §DS-7
(thrown exceptions NOT auto-wrapped), [`docs/final-tagless.md`](final-tagless.md)
(v1.13 — `using` resolution required for `Either[E, *]` monad
lookup), and [`docs/coroutines.md`](coroutines.md) §6 (algebraic
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

### 2.4 Error subtyping

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
| **`A \| E` union encoding** for `throws` | Rejected in favour of Either — direct-syntax integration |
| **Java-style throws clauses** that propagate via the stack | Re-introduces unchecked propagation; type alias on the return type is the path |
| **`throws` on JVM `Throwable` subtypes only** | Restricting `E` to `Throwable` would tie us to JVM; `throws` works over any type |
| **Effect-row tracking for errors** (`F[A] throws E1 \| E2 ...`) | v1.12 algebraic effects territory; out of scope here |

## 6. Open questions

These do **not** block the v1.15 decided portion (§2).  Lock
when first usage emerges.

- **Which std-lib platform operations get `throws`-typed
  shims in v1.15?**  Initial list: `parseInt`, `parseLong`,
  `parseDouble`, `requireNonNull`, divide-with-default.
  IO shims defer to v1.5 Tier 2-4.
- **`attemptCatch[E <: Throwable]` syntax** — `attemptCatch`?
  `safeCall`?  `catching`?  Naming TBD.
- **Stack-trace verbosity tuning** — collapse synthetic frames
  (the trampoline / coroutine machinery) into the user view?
  `--trace=internal` flag for full unfiltered?
- **`HasStackTrace` vs `extends Error`** — what's the std
  convention for "this error type carries a trace"?  Probably
  a `Error` trait in std that mixes in `HasStackTrace` by
  default, with explicit opt-out.
- **Restartable handlers and snapshot traces** — does a
  restart preserve the original error's snapshot, or build a
  new one when the handler retries?  Probably the original;
  user can capture a new one if they want.
- **Capture cost on hot paths** — measure when v1.15 lands;
  add a `noTrace` modifier on case classes if the overhead
  shows up.
- **Cross-backend trace format compatibility** — `Frame(file,
  line, fn)` is uniform, but the *content* of `fn` differs:
  JVM shows mangled JVM names, JS shows source names, INT
  shows the user's definition site.  Worth a normalisation
  pass for consistent output.
- **Java/Scala interop on the IDE side** — if a `throws`-typed
  value is passed to Java code, does it see `Either[E, A]`?
  Yes — `throws` is pure type alias, the runtime representation
  is `Either[E, A]`.  Document this for the IDE/refactoring
  story when v2.0 separate compilation arrives.

## 7. v1.15 scope (decided portion)

- `infix type throws[A, E] = Either[E, A]` shipped in std
- Two auto-conversion givens at return site
- Direct-syntax integration (depends on v1.8 + v1.13)
- Error subtyping (free via Scala 3 type rules)
- Std-lib platform-exception shims (initial set above)
- `attemptCatch[E <: Throwable] { … }` opt-in lift
- Conformance: 6-8 tests covering each piece

## 8. v1.16 scope (future, depends on v1.12)

- Restartable errors via algebraic-effects handler stack
- Handler resumes the suspended coroutine with a replacement value
- Patterns: `useDefault`, `retry(args)`, `abort(value)`,
  `transform(error)`
- Only ships if v1.12 feasibility study says "go" — otherwise
  retire this section

## 9. Conformance plan

### v1.15 decided tests (6)

| Test | Exercises |
|------|-----------|
| `throws-basic.ssc` | `throws[A, E]` ≡ `Either[E, A]`, conversions both directions |
| `throws-direct-syntax.ssc` | Auto-unpack inside `direct[Either[E, *]] { … }` |
| `throws-subtyping.ssc` | `E1 <: E2` ⇒ `throws[A, E1] <: throws[A, E2]` |
| `throws-multi-error.ssc` | `Int throws (ParseError \| OverflowError)` round-trip |
| `throws-shim.ssc` | Std `parseInt` etc. return `throws`-typed values |
| `throws-attempt-catch.ssc` | `attemptCatch[IOException] { … }` lifts JVM exceptions |

### v1.15 in-design tests (2)

| Test | Exercises |
|------|-----------|
| `throws-stack-trace.ssc` | `HasStackTrace` mixin; trace captured at construction; format identical across INT/JS/JVM |
| `throws-stack-restart.ssc` | (Defer to v1.16 — restartable handler resumes from snapshot) |
