# SSC IR — the executable core of ScalaScript 3

> This is the design document of the whole version. Everything else in `v3/` is an implementation
> detail that this file constrains. Charter and invariants: [`00-charter.md`](00-charter.md).

SSC IR is **not** a pruned AST. An AST says what the program *is*; SSC IR says what the machine
*does*, in order. A module in this form is already a program waiting to be run: a linear sequence of
instructions per function, each naming its operands and where its result goes.

That distinction is the reason v3 exists. v2's `CoreIR` (`v2/src/CoreIR.scala:20`) is a tree of
terms — `Lam`, `App`, `Let`, `Match` — and the runtime walks it recursively. It is a good
*denotational* representation and a poor *operational* one: every step allocates, the evaluation
order lives in the walker rather than in the data, and there is nothing to serialize mid-execution
because "where we are" is a position in the JVM call stack.

## 1 · Model

```text
Module
  consts  : Const[]         constant pool
  types   : TypeDef[]       constructor layouts — THE source of truth for field indices
  globals : GlobalDef[]     mutable module-level cells
  funcs   : Func[]          the code
  entry   : FuncId

Func
  name    : String
  nparams : Int             parameters occupy r0 … r(nparams-1)
  nregs   : Int             frame size, fixed at build time
  body    : Instr[]
```

A **frame** is a flat vector of `nregs` slots. There is no operand stack and no dynamic frame
growth: the builder computes `nregs` once, so a frame is a fixed-size array of values. Two
consequences that are the point rather than a side effect:

- an interpreter step reads and writes slots by index — no push/pop bookkeeping per operand;
- a frame is **data**, so a paused computation is `(funcId, instruction path, frame)` and can be
  written to disk. Durable continuations stop being a special mechanism and become a property of
  the representation.

### 1.1 Values

`Unit`, `Bool`, `Int` (**64-bit**, wrapping — `Int` is i64 in ScalaScript, not i32), `Big`
(arbitrary precision), `Float` (IEEE-754 double), `Str`, `Bytes`, `Data` (a tagged record),
`Closure`, `Arr` (mutable, fixed length), `Prim` handles owned by the host.

## 2 · Control flow is structured, and that is a deliberate constraint

Control flow uses WebAssembly's model — nested regions and relative branches — and **arbitrary jumps
cannot be expressed at all**:

| instruction | meaning |
|---|---|
| `Block(body)` | a region; `Br 0` inside it jumps to just **after** the block |
| `Loop(body)` | a region; `Br 0` inside it jumps back to the **top** of the loop |
| `If(cond, then, else)` | also a region for branch-depth counting |
| `Br(d)` | leave/repeat the `d`-th enclosing region, innermost is `0` |
| `BrIf(cond, d)` | the same, when `cond` holds |
| `Ret(r)` / `TailCall(...)` | leave the function |

Regions carry **no result values** — unlike WASM, where block results are a real source of
complexity. A register machine does not need them: write the value to a register before branching.

The asymmetry that decides this design: **structured → flat is a one-pass mechanical lowering; flat
→ structured is an algorithm.** Emitting JVM bytecode or native code from regions means emitting
labels and gotos in a single walk. Going the other way — recovering loops from a basic-block graph
so that JS, Rust, Swift or Scala *source* can be printed — is the relooper problem, with heuristics
and output nobody wants to read. Source output is an explicit requirement here, so v3 keeps the
direction that is free and pays nothing for it: control flow built from regions is reducible by
construction, and the verifier's branch check is one integer comparison against the region depth.

**And the constraint has now been cashed in.** A target that has `while` but no `goto` — v2's Core
IR, and every source language the backends print — needs the loop back, and because the lowering
emits exactly one shape for one,

```
(block (loop  <cond…>  (brif c 1)  <body…>  (br 0)))
```

recovering it is a pattern match rather than an analysis. `BridgeV2` does exactly that, and the
measurement is what makes the point concrete: emitting the CTL-flag form instead cost `arith-loop`
**32 v2 operations per iteration where the structured form costs 12**, and twenty of those thirty-two
were the flag — a guard before every statement, a per-depth "go round again" slot, a test at the
bottom of the loop. That number is the price of NOT having this section's constraint, paid on one
backend, on one loop.

## 3 · Instruction set

`d`, `a`, `b`, `c` are register indices; `k` indexes the constant pool; `g` the global table;
`t` the type table; `f` the function table.

**Constants and moves** — `Const d, k` · `Move d, a`

**Arithmetic and comparison** — `Un(op, kind, d, a)` · `Bin(op, kind, d, a, b)`

`op ∈ add sub mul div rem neg · band bor bxor bnot shl shr ushr · lt le gt ge eq ne · not`

`kind ∈ Dyn | I64 | F64 | Big` — the numeric kind is a **field, not a separate opcode**. The front
emits `Dyn` unless it can prove the operand type; the specializer pass rewrites the field in place.
This keeps one uniform instruction shape instead of an opcode explosion, and it means optimization
is a rewrite of data rather than a change of representation.

> `&&` and `||` are **never** `Bin` ops. They short-circuit, so they lower to `If`. An IR that lets
> them be strict binary operators has already lost the semantics, quietly.

**Calls** — `Call d, f, args` (direct) · `CallV d, a, args` (indirect/closure) ·
`MkClos d, f, captures` · `TailCall f, args` · `Ret a`

`TailCall` reuses the current frame. Self- and mutual tail recursion therefore run in constant
stack. This is a stability requirement, not an optimization: the v2 launchers need `-Xss512m`
(`v2/ssc1:7`) precisely because recursion depth in the compiler maps onto host stack depth.

**Data** — `MkData d, t, args` · `Field d, a, t, idx` · `Tag d, a` · `Switch a, arms, default`

**Dynamic dispatch** — `Invoke d, name, recv, args`

`name` indexes the CONSTANT POOL and the verifier checks the entry is an `LStr`, so a backend can
never be handed a number to dispatch on. It is **not** a `Prim`: `Prim` is the door to the HOST, and
dispatching a method on a value is language semantics — folding it in would hide a call from every
pass that wants to see calls, and would make the host boundary a lie. A register would have been the
other way to carry the name and is worse: the name is known when the front emits, so a pool index
lets a backend resolve it at translation time instead of carrying a string through the frame.

**Exceptions** — `Try d, body, exn, handler`

Both regions leave their result in the same `d`, so there is one result register rather than two a
later pass would have to reconcile; `exn` is where the caught value is bound. Its own instruction
rather than an effect: `Perform`/`Handle` are RESUMABLE and an exception is not, and conflating them
would give one instruction two control semantics.

`Field` carries its type index, and that is not redundancy: **without it rule 4 below is not
checkable at all.** The first cut of this spec wrote `Field d, a, idx`, and writing the verifier
showed the rule it claims to enforce degrades to "valid for the widest type declared anywhere",
which is not an invariant. The front always knows `t` — it has just matched the constructor. `idx`
is validated against `types[t]`, and `types` is the **only** place a field layout is written down. This repo has a whole bug family from the alternative: v2 field access is index-based
with a compile-time name→index registry that could disagree with the runtime record layout, and
every disagreement is a silent wrong-field read. One table, consulted by both the emitter and the
verifier, is the fix.

**Mutable storage** — `NewArr d, a` · `ArrGet d, a, b` · `ArrSet a, b, c` · `ArrLen d, a` ·
`GlobGet d, g` · `GlobSet g, a`

**Effects — the dispatcher** — `Perform d, opId, args` · `Handle d, body, arms` · `Resume d, a, b`

`Perform` suspends the current computation and hands `(opId, args)` plus the captured frame to the
nearest enclosing `Handle`. Because the frame is data (§1), what the handler receives is a value it
may store, queue, or resume later from another thread — which is the execution model as stated:
the executor performs a command and reports the result back to a dispatcher.

#### The protocol, and why it is written here

An arm is `on op, params, k, body`: the registers — **of the handling function's frame** — that
`Perform`'s arguments are copied into, and the register the continuation is placed in.

```
(handle 6
  (body (call 7 loop 8))
  (on 0 (params 9 10) (k 11)
    (resume 6 11 9)))
```

**Everything above the arm line was already here; the arm line was not, and that was a hole rather
than an omission.** Until 2026-08-08 this section described the dispatcher in prose and stopped:
`Perform d, opId, args` carried argument registers while `HandlerArm(op, body)` had no parameter
list, so nothing said where those arguments landed; `Resume d, k, v` read `k` as a continuation and
nothing anywhere put one in a register. `tests/sample.ssir` did not settle it either — it is a
coverage module, and its arm was `(on 0 (const 7 1))`, an arbitrary write. An implementer therefore
had to invent the convention, and the first one to do so would have made it real without writing it
down.

**Explicit registers rather than fixed positions, and the reason is the verifier.** The alternative
— arguments at `0..n-1` and the continuation at `n` of a fresh arm frame — needs no change to this
document, and that is exactly its defect: rule 1 below checks every register index against the
enclosing function's `nregs`, and a second frame with its own numbering is a rule this validation
cannot express. Naming the registers keeps the arm inside the one frame the verifier already knows
how to check, so `params` and `k` are ordinary indices validated by the machinery that is already
there. An IR whose invariants live in prose instead of in the verifier is the failure this whole
section exists to avoid.

**`k` is a value like any other.** Placing the continuation in a register is what makes §1's claim
("a frame is data") operative rather than aspirational: an arm may store it, ignore it, or resume it
more than once, and each of those is a program the executor either supports or refuses BY NAME. An
arm that resumes exactly once as its last act is the **tail-resumptive** class, and it needs no
continuation capture at all — `Perform` can run the arm and take the resumed value as its result at
any call depth. That is an implementation strategy, not a restriction of this protocol; the protocol
admits the general case whether or not a given executor does.

#### Capturing a continuation: a CLOSURE, not a reified machine — decided 2026-08-08

`k` is produced by the LOWERING, as an ordinary `VClos`. It is not built by the executor out of its
own stack.

**The alternative was measured and rejected.** The executor is 1363 lines of STRUCTURED host
recursion — eight recursive `exec` sites, and `Block`/`Loop`/`If`/`Switch` regions nested inside one
another across host frames. "The rest of the computation" there is not a program counter into a flat
list; it is a position in a tree plus every enclosing region, spread over the host's own stack.
Turning that into data means rewriting the machine: an explicit instruction pointer, an explicit
region stack, an explicit call stack, all copyable. That is the design §1 describes when it says a
frame is data, and it is a rewrite of the largest file in the kernel.

**The cheaper design is available because v3 already has first-class closures.** `MkClos`, `CallV`
and `VClos` have been in the instruction set from the start. A continuation that is a closure is:

- **multi-shot for free** — a closure may be called zero times, once, or many, and nothing about it
  is consumed by being called. Multi-shot is not a second feature after one-shot; it is what you get
  when you stop pretending the continuation is a stack;
- **storable** — §1's claim that a handler may "store, queue, or resume later from another thread" is
  literally true of a `VClos` and is not true of a slice of the host stack;
- **verifiable** — a closure is already covered by the verifier's rules, where a reified frame would
  need new ones.

**The cost, stated because it is not small.** Functions that can perform must be lowered in
continuation-passing style, and the IR is structured: a `Loop` containing a `Perform` becomes a
recursive function, since a loop's remainder cannot be a closure without one. v3 has `TailCall`, so
those recursions are constant-stack rather than a new leak. The transformation applies only to
functions that TRANSITIVELY perform — a set the lowering can compute, and which in the bench corpus
today is two programs.

#### Who PRODUCES the continuation — the last argument of `Perform`

The protocol above says where `k` goes. It did not say where it comes from, and the executor has
been putting `unit` there since the tail-resumptive path was written, because that path never reads
it.

**The lowering produces it, and passes it as `Perform`'s LAST ARGUMENT.** An arm binds
`params.length` values from the front of the argument list and `k` from the one after them, which is
the same rule the arm's own binders already follow ("the LAST binder is the continuation") — one
convention, stated twice, rather than two.

A function that has been CPS-converted therefore emits

```
(mkclos 12 <rest-of-f> <live registers>)
(perform 7 0 8 12)          ; op 0, one operation argument in r8, continuation in r12
```

and one that has not — because nothing in it can be resumed non-tail — emits `perform` with the
operation's arguments alone. The executor binds `k` to `unit` in that case, exactly as it does now,
and the arm cannot tell the difference unless it tries to resume, which the tail-resumptive check
already forbids it from doing more than once.

**Why not have the executor build it.** That is the rejected design above, restated where an
implementer will be tempted: producing `k` in the executor means the executor knows what "the rest
of this function" is, which means the machine has to be reified. Producing it in the LOWERING means
the rest of the function is an ordinary function and `k` is an ordinary closure — the whole reason
this design was chosen.

**Splitting is what makes the rest of a function a function.** A `Perform` at position *i* of a body
splits it: everything before *i* stays, everything after becomes a new function whose parameters are
the resumed value and the registers still live at that point. Nested regions are why step 3 exists —
a perform inside a `Loop` cannot split that way, because the loop's remainder is not a suffix of an
instruction list — and it is why the loop conversion is a step of its own rather than a detail.

**What does not change:** `Perform`, `Handle`, `Resume` and the arm protocol above. The executor's
tail-resumptive fast path stays exactly as it is — it is what a CPS-converted arm reduces to when the
arm resumes once as its last act, so it becomes an optimisation rather than the only thing that
works.

#### Both encodings cross the v2 bridge — decided 2026-08-16

`Handle`, `Perform` and `Resume` are no longer executor-only. `BridgeV2` translates them, so all
twelve fixtures in `v3/tests/effects/` run on the v2 lane and `v3/effects-gate.sh` is a differential
rather than a one-lane suite.

**It does NOT go through v2's own `effect.perform` / `effect.handle`.** Those implement effects by
threading an `Op` value out through evaluation and rebuilding the continuation from v2's term tree.
Handing a bridged program to that machinery would put two continuation mechanisms in series, and the
bridge's register frame is one MUTABLE ARRAY — so a v2-captured continuation resumed twice would see
the first resumption's stores. Not a refusal: a wrong answer. The very fixture that would catch it,
`two-performs-multi-shot`, is in the suite and reads 8 where the answer is 12 the moment the copy is
removed, which is the same number the executor produced from the same defect before it was fixed.

**What is emitted instead is this document's own protocol.** Because CPS conversion has already made
the continuation an ordinary closure, what is left for the target to do is dynamically-scoped
dispatch, and that needs nothing v2 lacks:

| this IR | in v2 Core IR |
|---|---|
| `Handle` | push a record on a global array, run the body under `__tryFinally__`, pop |
| `Perform` | walk that array from the top for a record whose arm answers this operation, call it |
| `Resume` | apply the closure in `k` — resuming IS calling |
| an arm | a one-argument closure over a `arr.slice` COPY of the handling frame |
| the return clause | the same, applied when the body finished having performed nothing |

The tail-resumptive encoding — a `perform` that carries no continuation, which is what the lowering
emits when it cannot split (a `perform` inside a `Loop`, per step 3 above) — is translated by
REWRITING `resume(d, k, v)` to `move(d, v)` in the arm and running it in the handler's own frame.
That is not an approximation: with `k` bound to `unit` the two instructions do the same thing, which
is exactly what makes the tail-resumptive path an optimisation of the general one rather than a
second semantics. Only `ctl` is saved and restored around it, because the arm's trailing `ret` would
otherwise tell the HANDLING function it had returned.

**Host boundary** — `Prim d, primId, args`

The single door to everything the IR does not define: I/O, host interop, plugin SPI. This is what
makes the zero-dependency invariant *checkable* rather than aspirational — the kernel defines the
IR, the verifier and the executor; the primitive manifest is a data table, and anything with an
external dependency lives behind a `primId` on the far side of it.

#### WHAT A CONTINUATION MUST CONTAIN — one invariant, two realisations — written 2026-08-19

The three subsections above say `k` is a closure, who produces it, and how both encodings reach the
v2 bridge. They do not say what it must COVER, and by 2026-08-19 that had been answered three times
in three places: once here, as a prescription nobody implemented, and twice in code, differently.
This subsection states the invariant once and records the two realisations under it.

**THE INVARIANT.** Resuming a continuation must run everything the handled computation had left to
do, which is three things and not one:

1. **the rest of the performing function** — what `Cps.split` makes a closure of;
2. **the rest of every enclosing list up to the `handle`** — each caller's remainder, and each
   enclosing REGION's remainder, because a `Perform` deep in a call chain leaves work behind at
   every level it passed through;
3. **for a `loop`, the back edge** — a resumed loop-body remainder does not finish the loop, it
   finishes one ITERATION, and the loop must go on iterating afterwards.

A mechanism that covers 1 but not 2 does not fail loudly. It answers, with the arm's value dropped
and the caller carrying on — which is what both lanes did until this was written down.

**HOW EACH CLAUSE IS CHECKED**, named per clause, because a rule that is stated and not checked is
the shape that survives by never meeting an unusual input:

| clause | what pins it |
|---|---|
| 1 — the performing function's rest | `v3/tests/effects/two-performs`, `multi-shot`, `zero-shot` |
| 1 + the handler frame the continuation belongs to | `escaped-continuation` — an arm returns a closure resumed after its `handle` has finished |
| 2 — a caller's remainder | `cross-frame-statement`, `cross-frame-in-handle-body` |
| 3 — the back edge | **NOT pinned by a fixture.** Measured by hand against the v1 lane only |

`v3/effects-gate.sh` requires the executor, the v2 bridge and the expectation to agree three ways, so
a shape only one lane can run cannot be a fixture there. That is why clause 3 has no row: regions are
crossed by the executor and still refused by the bridge. Closing that gap means either the bridge
crossing regions too, or an executor-only fixture set with its own rule — a decision, not an
oversight, and it is recorded here so the empty cell is not mistaken for coverage.

**TWO REALISATIONS, AND THE DIFFERENCE IS NOT STYLISTIC.** It is a question about who owns the
registers, and the two lanes answer it differently:

* **the executor** (`Exec.scala`, `830efe318`) records a `PendingFrame` — an instruction suffix plus
  a register array — at each call, and also on the way INTO a region. A region does not open a frame:
  it runs in the frame around it, so the region's remainder and the enclosing one SHARE a register
  array and nothing has to be threaded. The frame recorded for a `loop` carries its body, which is
  clause 3. A snapshot of such a chain must clone one array per DISTINCT array, or the sharing that
  makes it work is exactly what the snapshot destroys.
* **the bridge** (`BridgeV2.scala`, `bc78e963c`) cannot record anything at run time — a v2 function
  has no way to hand over "the rest of me" — so the COMPILER builds the closure instead, splitting a
  caller at a non-tail call the way `Cps` splits at a perform. Its continuations are `MkClos`, which
  captures registers BY VALUE, and that is why the same trick does not extend to regions there: code
  after an `if` must see what the branch wrote. A region continuation on that lane has to SHARE the
  frame and therefore be built by the emitter, which is why the bridge still refuses regions.

**THE ROUTE NOT TAKEN.** The cost paragraph in "Capturing a continuation" above says:

> a `Loop` containing a `Perform` becomes a recursive function, since a loop's remainder cannot be a
> closure without one.

That is a sound design and it is **not implemented**, on either lane. It is left in place as the
decision it was, and flagged here because a specification describing a route nobody took is worse
than two implementations describing themselves: it reads as an answer. Anyone taking it up should
know it competes with two working mechanisms and would replace both — which is the only argument for
doing it, and a good one, since it would leave ONE statement instead of this subsection.

**AND ONE THING NEITHER REALISATION DOES.** A `Perform` standing directly inside a region is still
not split — `Cps.scala` calls that step 3, and it remains true. What both mechanisms cross is a CALL
to a function that performs, not a perform in place.


## 4 · Validation is mandatory, not a debug mode

Every load of IR — from disk, from a front, from a test — runs the verifier. A module is valid iff
one structural pass establishes:

1. every register index is `< nregs`;
2. every `Br d` has `d <` the enclosing region depth at that point;
3. every `k`, `g`, `t`, `f` is in range, and every `Call`'s argument count equals the callee's
   `nparams`;
4. every `Field`'s `idx` is `<` the field count of its own `types[t]`, and `t` is in range;
5. every function body ends in a terminator (`Ret`, `TailCall`, or a `Br` that leaves the function),
   and so does every arm of a terminating `If`/`Switch`.

An invalid module is refused with the instruction path that failed. The verifier is what buys the
"stability and correctness" this version is for: a malformed module must be impossible to *run*,
not merely unlikely to be produced.

## 5 · Serialization

Two forms, one of them canonical:

- **`.ssir`** — text, human-readable, what `ssc3 fmt <file>` prints, round-trips exactly. The form
  is an **S-expression**: a list whose items are all leaves stays on one line, and anything with a
  sub-form puts its leading leaves on the head line and then one item per line. One shape for the
  writer and the reader, through a single intermediate type, so the two are obviously inverse rather
  than merely similar. Deliberately no width heuristic and no item-count threshold — a layout with a
  magic number in it reflows when an unrelated edit crosses the number, and a canonical form that
  reflows makes every diff lie. A frozen example is [`../tests/sample.ssir`](../tests/sample.ssir),
  which exercises every instruction;
- **`.ssirb`** — binary, compact, for artifacts and caches. Not built yet: `.ssir` is canonical and
  sufficient until artifact size or load time is measured to be a problem.

**The text form is canonical for equality.** Every gate compares `.ssir`. Two rules learned the
expensive way in this repo:

- float formatting in a canonical form gets its **own** function and is never routed through a
  formatter shared with anything else — the shared one grows a parity requirement from its other
  caller and then the canonical form moves under you;
- the round-trip is a property test (`read(write(m)) == m` and `write(read(t)) == t`), not a
  handful of examples.

## 6 · What is deliberately not here

No SSA and no phi nodes — the optimizer works on regions and registers. No type system *in the IR*;
`kind` carries what the front proved and nothing more. No garbage collector — values are host
objects. No separate compilation in v1: a module is a whole program. Each of these is a real
decision with a real cost, and each is parked in [`../BACKLOG.md`](../BACKLOG.md) rather than
silently deferred.

## 7 · Worked example

`def fib(n: Int): Int = if n < 2 then n else fib(n - 1) + fib(n - 2)`

```text
func fib nparams=1 nregs=6
  const   r1, k0            ; 2
  bin     lt.i64 r2, r0, r1
  if      r2
    ret   r0
  end
  const   r1, k1            ; 1
  bin     sub.i64 r3, r0, r1
  call    r4, fib, [r3]
  const   r1, k0            ; 2
  bin     sub.i64 r5, r0, r1
  call    r5, fib, [r5]
  bin     add.i64 r3, r4, r5
  ret     r3
```

Note what is visible here and is not visible in a term tree: evaluation order, the fact that `r1` is
reused, the exact frame size, and the fact that nothing allocates. Those are the properties an
optimizer and a code generator need, and they are properties of the *data*, not of a walker.
