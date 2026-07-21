# Coroutines

Status: **primitive and cancellation shipped on v1/JS; ScalaScript 2.1 native
provider specified**. Historical follow-up design is tracked across v1.10
(generators), v1.11 (continuation-based `Async`), and v1.12
(algebraic-effects feasibility). The standard native contract is
[`v2.1-native-coroutine-provider.md`](v2.1-native-coroutine-provider.md).
Companion to [`docs/direct-syntax.md`](../docs/direct-syntax.md) (which
gives the *surface* for monadic code) and
[`specs/backend-spi.md`](backend-spi.md) §8 (the intrinsic mechanism
that makes coroutines a single primitive across three backends).

Relationship to
[`control-interoperability.md`](control-interoperability.md): the mutable
coroutine handle in this document is an asymmetric one-shot primitive and may be
used as a target-private fast path. It is not the reference semantics for reusable
`shift` continuations or `SavedContinuation`; those use the stackless `Pure | Op`
protocol and preserve multiplicity. The
[`Scala 3 profile`](scala3-bidirectional-control.md) describes one concrete host
mapping without changing this boundary.

This document is the source of truth for the runtime primitive,
the orthogonal components built on top of it, the surface APIs
that derive from those components, and the backend-intrinsic shape
that makes the whole thing portable.

## 1. Motivation

ScalaScript currently has **three parallel implementations of "pause
and resume execution"**:

| Construct | How it pauses today |
|-----------|---------------------|
| `Async.delay / await / parallel` | Free monad with `flatMap`-chained continuations; one `Computation[A]` allocation per bind |
| Actors (`receive` / mailbox wait) | Loom virtual-thread park on JVM; NIO continuation on INT; microtask in JS |
| WebSocket handlers (`ws.recv()`) | Same shape as actors; reuses their machinery in JVM, distinct in INT |

Each layer was built independently and ships its own scheduler,
continuation representation, and suspend mechanism.  This works,
but is wasteful — the **runtime concept is identical across all
three**, only the surface API differs.  Coroutines extract that
shared concept into one runtime primitive that each derived layer
becomes a thin wrapper over.

The user-facing payoff:

1. **Generators** (`yield 1; yield 2; …`) — natural pull-based streams
   without an `Observable` library.
2. **Faster `Async`** — replace the per-bind Free-monad allocation
   with one suspension-frame per `await` boundary.
3. **Algebraic effects** become tractable — handler-stack semantics
   are coroutine semantics with tagged yields.
4. **Internal consistency** — actors / Async / generators share one
   runtime, one scheduler, one debugger story.

The **non-goal**: replace user-facing APIs.  `Async.delay(...)` /
`spawn(...)` / `ws.recv()` keep working unchanged.  Coroutines sit
*beneath* them.

## 2. The primitive

Four SPI intrinsics, one ADT.  Everything in this document reduces
to this surface:

```scala
// Status: a coroutine yielded, returned, failed, or was cancelled.
enum Step[+Y, +T]:
  case Yielded(value: Y)
  case Returned(value: T)
  case Errored(message: String)
  case Cancelled

// Create a paused coroutine.  Body does not execute until first resume.
extern def coroutineCreate[Y, R, T](body: () => T): Coroutine[Y, R, T]

// Resume; pass `in` as the result of the most recent suspend.  Returns
// the next Yielded or the final Returned.  Resuming a Returned coroutine
// is an error.
extern def coroutineResume[Y, R, T](co: Coroutine[Y, R, T], in: R): Step[Y, T]

// Inside a coroutine body: pause with `out`, receive `in` from the resumer
// on the next resume.  Throws "outside coroutine" if called from non-
// coroutine context.  Dynamically scoped, like `self()` in actors.
extern def suspend[Y, R](out: Y): R

// Invalidate the handle and interrupt an active body. Idempotent.
extern def coroutineCancel[Y, R, T](co: Coroutine[Y, R, T]): Unit
```

The two-way value passing (`suspend(y): R`) is **deliberate** — it
subsumes one-way generators (`R = Unit`) at zero extra cost while
remaining strictly necessary for algebraic effects (handler returns
a result to the operation site via `resume`).

`coroutineCreate` is **lazy**: the body does not start executing
until the first `resume`.  This avoids the "coroutine runs ahead of
its consumer" race that bedevils eager-start designs.

Normal completion returns `Returned`; an unhandled body exception returns
`Errored(message)`. Both are terminal. `coroutineCancel` is also terminal and
idempotent; a later resume of any terminal handle throws. `Cancelled` remains
available to higher-level controlled-shutdown protocols, but cancellation does
not keep a low-level handle resumable solely to observe that case.

## 3. Grammar / scope rules

`suspend(y)` is a **free function** dynamically scoped to the
currently-executing coroutine — same model as `self()` in actors.
Two consequences:

- **Static check at use site is impossible** without effect tracking.
  The runtime check inside `suspend` raises "called outside a
  coroutine context" if there's no active frame.
- **Inside a closure that crosses a coroutine boundary**, `suspend`
  refers to the **innermost** active coroutine.  Lambdas passed to
  `xs.map(f)` execute inside the calling coroutine; lambdas captured
  and resumed later from a different scheduler tick attach to that
  scheduler's coroutine.

`Coroutine[Y, R, T]` is a process-local value — assignable, storable in memory,
and passable to functions. The mutable handle is a `CaptureBarrier` and
`Unsavable`; it is not a `SavedContinuation` and has no durable/network codec.
It does **not** participate in `Async` until something
explicitly schedules it.  A coroutine that never gets resumed sits
in memory until GC'd.

## 4. Worked examples

### 4.1 Generator (one-way yield)

```scala
def naturals: Coroutine[Int, Unit, Unit] = coroutineCreate {
  var n = 0
  while true do
    suspend(n)
    n += 1
}

val gen = naturals
println(coroutineResume(gen, ()))   // Yielded(0)
println(coroutineResume(gen, ()))   // Yielded(1)
println(coroutineResume(gen, ()))   // Yielded(2)
```

`Y = Int`, `R = Unit`, `T = Unit` (loop never returns).  v1.10 wraps
this in a `Generator[T]` user-facing API with `.next()`,
`.foreach(f)`, `.map(f)`, `.toList`.

### 4.2 Async (two-way: yield IO request, resume with result)

```scala
def loadUserAndOrders(req: Request): Coroutine[IORequest, IOResult, Response] =
  coroutineCreate {
    val user   = suspend(LoadUser(req.params("id")))   // IOResult is the user
    val orders = suspend(LoadOrders(user.id))
    Response.json(user, orders)
  }
```

`Y = IORequest`, `R = IOResult`.  The scheduler (Async runtime)
resumes when the IO completes, passing the result back through
`suspend`.  v1.11 rebuilds `Async.delay / await / parallel` on top
of this — same user API, no Free monad.

### 4.3 Algebraic effect handler

```scala
// Effect ops tagged at yield time
case class Op[A](name: String, args: List[Any])

def withRandom[T](co: Coroutine[Op[?], Any, T], seed: Long): T =
  val rng = scala.util.Random(seed)
  @tailrec def step(in: Any): T =
    coroutineResume(co, in) match
      case Step.Yielded(Op("nextInt", List(n: Int))) => step(rng.nextInt(n))
      case Step.Yielded(other)                       => suspend(other)   // re-raise to outer handler
      case Step.Returned(t)                          => t
  step(()) // first resume value is ignored
```

The handler is itself a function that knows how to interpret one
op-tag; multiple handlers stack via the re-raise pattern.  v1.12
investigates whether ScalaScript's type system can carry effect rows
to make this typeable without `Any`.

### 4.4 Actor receive (internal refactor sketch)

```scala
// Pseudo-code — illustrative; real refactor happens in v1.9.x
final class Actor[M](
    val pid:     Pid,
    val mailbox: Mailbox[M],
    val co:      Coroutine[ActorSignal, Option[M], Unit]
)

// Inside actor body:
def receive[M]: M =
  while true do
    mailbox.pollNonBlocking() match
      case Some(msg) => return msg
      case None      => suspend(WaitForMessage)  // scheduler resumes us on mailbox push
```

The mailbox-push path on the scheduler side becomes
`coroutineResume(actor.co, Some(msg))`.  Same observable semantics
for users; one less ad-hoc continuation representation in the
runtime.

## 5. Orthogonal components

Four minimal building blocks; each derived feature uses some subset:

| Component | Role | Consumers |
|-----------|------|-----------|
| **Coroutine** | suspend / resume, two-way value passing | every other component |
| **Scheduler** | per-backend; decides which coroutine runs next | Async, Actors, WS handlers |
| **Channel / Mailbox** | blocking read, non-blocking write — built on Coroutine | Actors, WS, channels for streaming |
| **Supervisor signals** | out-of-band interrupt (kill, link death) | Actors |

Derived features as compositions:

- **`Generator[T]`** = Coroutine (no mailbox, no supervisor)
- **`Async[T]`** = Coroutine + IO Scheduler
- **`Actor[M]`** = Coroutine + Mailbox + Supervisor signals + Pid handle
- **Algebraic effect handler** = Coroutine + handler stack catching tagged yields

The redundancy critique becomes: **runtime redundancy is eliminated**
(one suspend mechanism, one scheduler per backend), **API redundancy
is intentional** — actors stay for fault-tolerant concurrent work,
Async for IO sequencing, generators for streams.  Each is a
different abstraction at the user surface.

## 6. Backend intrinsic mapping

The four intrinsics from §2 require one implementation per backend.

### 6.1 JVM (Loom virtual threads)

```scala
class Coroutine[Y, R, T]:
  private val toCo:    SynchronousQueue[R]            = SynchronousQueue()
  private val fromCo:  SynchronousQueue[Step[Y, T]]   = SynchronousQueue()
  private val thread   = Thread.ofVirtual().start { () =>
    try
      val result = body()
      fromCo.put(Step.Returned(result))
    catch case t: Throwable => fromCo.put(Step.Errored(t))
  }

def resume(in: R): Step[Y, T] =
  toCo.put(in); fromCo.take()
def suspend(out: Y): R =
  fromCo.put(Step.Yielded(out)); toCo.take()
```

~80 LOC including error handling.  Loom virtual threads make this
"actor mailbox in a different hat" — same primitive that already
backs JVM actors.

### 6.2 JS (Node)

```js
function coroutineCreate(body) {
  const gen = body();   // body is a generator function (function*)
  return { _type: 'Coroutine', gen, done: false };
}
function coroutineResume(co, in_) {
  if (co.done) throw new Error('resume of completed coroutine');
  const r = co.gen.next(in_);
  if (r.done) { co.done = true; return Step.Returned(r.value); }
  return Step.Yielded(r.value);
}
// suspend(y) — must compile to `yield y` inside generator body
// (handled by the desugarer; not a runtime function on its own).
```

~50 LOC + a JS-codegen transform that lowers `suspend(y)` calls
inside `coroutineCreate` bodies into `yield y`.  Generators are
native in JS so this is mostly plumbing.

### 6.3 Interpreter (NIO single-thread)

The heaviest implementation — the interpreter has no native
continuations.  Approach: **CPS transform of coroutine bodies at
parse time**.  Each `suspend(y)` becomes a `Computation.Suspend(y,
continuation)` node; the existing `Computation` machinery already
handles the wait/resume.

~300-400 LOC: a `CoroutineLowering` pass on `Term` trees that walks
the body, splits at every `suspend` call, threads the continuation
into a `Yielded` value.  Shares the trampolining infrastructure
already used by `Async`.

### 6.4 Why intrinsics fit here

The SPI's `extern def` mechanism (Б-1 in
`specs/spi-intrinsics-design.md`) is exactly the right surface:
each backend declares its four implementations of
`coroutineCreate / coroutineResume / suspend / coroutineCancel`, normalisation
lowers user calls to `ExternCall(...)`, the backend's emit picks
up.  No new SPI concept.

What this *unlocks*: every other intrinsic that currently has a
custom continuation (`Async.delay`, `actor.spawn`, `ws.recv`) can
optionally re-route through the coroutine layer in a later
refactor.  Not required for v1.9; opens the door for v1.11.

## 6.5. Coexistence with `Free[F, A]` in stdlib

Coroutines and Free monad sit at **different layers of abstraction**.
Both are useful, both stay — but for different things.

| Layer | Coroutine | `Free[F, A]` (stdlib) |
|-------|-----------|----------------------|
| Concern | "How do we pause execution?" | "How do we represent an effectful program as a value?" |
| Form | Runtime primitive (4 intrinsics) | Pure ScalaScript data structure (no intrinsics) |
| Optimisation by user | Impossible — opaque handle | Possible — `cata`/fusion over the value tree |
| Inspection / serialization | No | Only explicitly data-coded nodes; native closures/handles do not become durable |
| Re-run with different handler | No | Yes — `foldMap(nt)` branches from the explicit data tree; unrelated to durable no-prefix-replay `save`/`run` |
| Execution speed | Direct — no per-bind allocation | Slower — walks the value tree (acceptable cost for the data view) |

Serialization in this table applies only when every node and captured field has an
explicit data/graph codec. A `FlatMap` host closure, native coroutine handle, or live
resource never becomes durable merely because the surrounding program uses `Free`.

**Synergy**:

1. **`Free.runM` uses coroutines underneath** for efficient
   execution.  The data-tree gets folded into a coroutine that
   suspends at every `F[?]` node.
2. **`Free` is automatically `Monad`** (by construction).  v1.8
   direct-syntax (`direct[Free[F, *]] { … }`) works for free — no
   typer changes needed.
3. **Algebraic effects (v1.12)** use `Free` as the user-facing
   value-encoded DSL when language-level `effect` keywords aren't
   sufficient.  The handler stack from §4.3 becomes
   `Free.foldMap[F, Coroutine]`.
4. **Testing** — production handler returns `Coroutine`-backed
   Async; test handler returns `State` or `Identity`; same program,
   no test-doubles needed.  Standard cats-free pattern.

**Why no conflict**:

- Naming: `Free` is in std (`std/free.ssc`); `Coroutine` lives in
  the runtime (intrinsic-defined).  No collision.
- Compilation: only coroutines are intrinsics.  `Free` is pure
  Scala, builds on v1.1 typeclasses and v1.9 coroutines via std
  imports.  No new compiler concept.
- Existing `Computation[A]` (internal Free monad trampoline in
  core): demoted to internal-only after v1.11.  Stays in core
  for backwards compatibility for one minor-version cycle, then
  deprecated.  User-facing `Free` is a fresh type, not a rename.

**Authoring shape** — what `std/free.ssc` provides:

```scala
enum Free[F[_], A]:
  case Pure(a: A)
  case Suspend(fa: F[A])
  case FlatMap[F[_], A, B](fa: Free[F, A], k: A => Free[F, B]) extends Free[F, B]

object Free:
  def pure[F[_], A](a: A): Free[F, A]      = Pure(a)
  def liftF[F[_], A](fa: F[A]): Free[F, A] = Suspend(fa)

  // Interpret to another monad via a natural transformation F ~> G
  def foldMap[F[_], G[_], A](program: Free[F, A])
    (nt: [X] => F[X] => G[X])(using m: Monad[G]): G[A]

  // Coroutine-backed execution — the common case for runnable effects
  def runM[F[_], A](program: Free[F, A])
    (handler: [X] => F[X] => Coroutine[Nothing, Nothing, X]): Coroutine[Nothing, Nothing, A]
```

Both `foldMap` and `runM` are stack-safe via internal
trampolining; on small programs `runM` is approximately the same
speed as direct coroutine code, on programs with thousands of
binds `runM` is ~5-10× slower than equivalent direct-coroutine
code (the value-tree walk dominates).  Users who care about that
factor use coroutines directly; users who care about the data
view use `Free`.

**Not in scope**:

- `Cofree` / `FreeT` transformer stack — defer to v2.x if a
  concrete consumer emerges
- `Free.optimize` (fusion of adjacent `FlatMap` nodes) — possible
  but not in v1.11.5; revisit when measurements demand
- Compilation of `Free` programs to coroutines at parse time
  (a la cats-effect's `IO` rewriter) — out of scope; v1.8 direct-
  syntax over `Free[F, *]` already covers the ergonomics

## 7. Internal refactor strategy

The user-facing APIs (`Async.*`, `spawn(...)`, `receive { ... }`)
stay unchanged across all of v1.9–v1.12.  Coroutines land
**underneath** them as an internal implementation detail.

| Version | What sits on coroutines | What still has its own runtime |
|---------|-------------------------|--------------------------------|
| v1.9 | nothing — coroutines exist as a primitive only | Async (Free monad), Actors (Loom/NIO/microtask) |
| v1.10 | new `Generator[T]` | Async, Actors |
| v1.11 | new `Generator[T]`, Async (rewritten) | Actors |
| v1.9.x | new `Generator[T]`, Async, Actors (rewritten) | none |
| v1.12 (study) | + algebraic effect prototype | none |

The Actor refactor (v1.9.x) is **optional cleanup**, not a release
gate — actors keep working with the Loom path even if the refactor
never lands.  Same for the Async rewrite: v1.11 can stay on Free
monad if measurements don't justify the change.

## 8. Implementation phases

Each phase is its own milestone (see MILESTONES.md).  Per-phase
breakdown of new code, new conformance tests, and dependencies:

### v1.9 — Coroutine primitive (~2 weeks)

- 4 current intrinsics (`coroutineCreate`, `coroutineResume`, `suspend`,
  `coroutineCancel`) per backend (JVM, JS, INT); cancellation followed the
  initial three-operation v1.9 release in v1.9.x
- New `Step[Y, T]` ADT and `Coroutine[Y, R, T]` opaque handle
- Conformance: ping-pong, generator-like loop, two-way value
  passing, "outside coroutine" error
- Prerequisite: `extern def` parser + typer (Stage 5+/A.5, in
  flight in SPI followups)

### v1.10 — Generators (~3-5 days)

- `Generator[T]` user-facing wrapper around `Coroutine[T, Unit, Unit]`
- `.next()`, `.foreach(f)`, `.map(f)`, `.filter(f)`, `.toList`,
  `.take(n)`, `.zip(other)`, lazy pipelines
- `Iterator`-shape compatibility on JVM
- Conformance: lazy infinite streams, pipeline composition,
  early termination via `.take`
- Use cases: SSE event source (when Tier 4 #11 lands),
  large-file reading

### v1.11 — Continuation-based `Async` (~2 weeks)

- Rewrite `Async.delay / await / parallel` on top of coroutines
- Free monad in `Computation` becomes a thin compatibility shim
- Performance target: ≥20% reduction in `flatMap`-heavy benchmark
  allocation count; less critically, ≥10% wall-clock improvement
- Conformance: every existing Async test must pass unchanged
  (the refactor is non-observable from user code)
- Stack traces: must show the user's coroutine body, not the
  trampoline internals (was a regression risk in v0.8)

### v1.9.x — Actor internals refactor (~1 week, optional)

- Rebuild `spawn(...)` / `receive` on top of coroutines
- Mailbox becomes a `Channel[M]` built from the coroutine primitive
- Visible from outside only as a performance / code-size win
- Defer if v1.9 + v1.10 + v1.11 measurements show the existing
  actor runtime is already good enough

### v1.12 — Algebraic effects feasibility study (~1 week, no shipping code)

- Prototype handler-stack semantics on top of coroutines
- Investigate whether ScalaScript's type system can carry
  effect rows (`Async | Random | Logger`) — DS-1 follow-up
- Decide go/no-go for a v2.x algebraic-effects milestone
- Deliverable: design doc + working prototype + go/no-go decision

## 9. Hard-no list (closed by design)

| Feature | Reason |
|---------|--------|
| Symmetric coroutines (`transfer(other)` — switch directly to another coroutine) | Asymmetric (resume / suspend) is sufficient for all 4 derived use cases; symmetric adds scheduler complexity without payoff |
| User-visible scheduler API | The scheduler is per-backend, not a portable concept.  Users get `Async.parallel`, `actor.spawn`, etc. — never raw scheduler control |
| Stackful symmetric jumps (longjmp-style) | Unsafe; defeats supervisor semantics |
| Synchronous cross-coroutine `transfer` | Conflates scheduling with control flow.  Use channels |
| Coroutine cancellation in v1.9 | **Landed in v1.9.x (2026-05-19)**: `coroutineCancel(co)` invalidates the handle and interrupts the body thread; `Step.Cancelled` added to the ADT |
| `awaitWithin(timeout)` baked into coroutine primitive | Higher-level concern; lives in `Async`, not the primitive |

## 10. Conformance plan (v1.9)

Six tests under `conformance/`, each running on all three backends:

| Test | Exercises |
|------|-----------|
| `coroutine-basic.ssc` | create → resume → suspend → returned; single-yield case |
| `coroutine-twoway.ssc` | `suspend(y): R` passes values both directions across multiple round trips |
| `coroutine-generator.ssc` | Tight `while true do suspend(n)` loop; resumer pulls 10 values |
| `coroutine-error.ssc` | Body throws → resume returns `Step.Errored(t)`; resuming completed coroutine errors |
| `coroutine-nested.ssc` | Coroutine creates and resumes another coroutine inside its body |
| `coroutine-outside.ssc` | `suspend(...)` called from non-coroutine context raises clear error message |

Each test asserts cross-backend identical observable output.

## 11. Open questions

Decisions not blocking v1.9, but need answers before specific
follow-up milestones land.

- **Cancellation semantics** — **resolved (2026-05-19)**.
  `coroutineCancel(co)` uses `Thread.interrupt()` on the body's
  virtual thread; the body thread sees `InterruptedException`
  at the next blocking point.  Handle is removed from the live-
  coroutines map; subsequent `coroutineResume` calls throw.
  `Step.Cancelled` is added to the ADT for future use when actors
  need to observe a controlled-shutdown signal.

- **Stack-trace fidelity on INT** — the CPS transform on the
  interpreter may obscure user-visible stack frames.  Need a
  `Computation` debug mode that preserves source positions
  through the lowering.  Address during v1.9 implementation.

- **Effect rows on the type level** — required by v1.12.  Today
  the type system carries one master effect (`Async[A]`); algebraic
  effects need `(Async | Random | Logger)[A]` or similar.  This
  intersects with v1.8 direct-syntax's DS-1 (single-monad
  inference) — direct syntax over a row would need its own
  desugarer changes.

- **Resource safety** (try-finally across suspend) — Loom virtual
  threads handle this natively; JS generators handle it via
  `try/finally` in the generator body; the INT CPS transform
  must explicitly thread cleanup through suspension points.

- **Generator equality / hashing** — `Generator[T]` is a stateful
  handle.  Equality should be reference equality, not structural.
  Document this when v1.10 lands.
