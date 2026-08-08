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

**Host boundary** — `Prim d, primId, args`

The single door to everything the IR does not define: I/O, host interop, plugin SPI. This is what
makes the zero-dependency invariant *checkable* rather than aspirational — the kernel defines the
IR, the verifier and the executor; the primitive manifest is a data table, and anything with an
external dependency lives behind a `primId` on the far side of it.

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
