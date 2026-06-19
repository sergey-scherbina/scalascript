# JIT-compile `combineAll`/`foldLeft` glue — implementation spec

Status: **DESIGNED 2026-06-19, not yet built.** This is the "full lever" behind
`hof-glue-jit-compile` (BACKLOG, resolved → deferred to this work). It is a real,
de-risked, multi-slice VM feature — NOT a one-shot. The JIT runs on every hot
path, so each slice ships flag-gated (`SSC_JIT_FOLDLEFT=1`) and is verified by
running the program with the JIT ON vs OFF over many inputs and asserting
identical results (the bail-on-throw safety net catches crashes but NOT
silently-wrong results — that is the whole risk).

## Target

```
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A
given intMonoid: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b
def combineAll[A: Monoid](xs: List[A]): A =
  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
```

Benchmark `typeclassFoldMacro` calls `combineAll(xs)` 300× over a 10-element list
= **1.142 ms/op** (vs the statically-typed `typeclassFold` = 0.005 ms/op, which
fully JITs). The whole `combineAll` body is tree-walked every call because the
function bails compilation; ~78% of the cost is `evalCore` self-time.

## What already exists (don't rebuild)

- Dual-bank register VM (`SscVm`): Long bank + parallel ref bank.
- `CALLREF` (47) — invoke a FunV in a ref register, with a polymorphic inline
  cache. `LOADFV` (48) — compile-time FunV constants. `JMP`/`CALL` — control flow.
- `GETFR`/`GETFI` — read a ref/numeric *field* off an InstanceV.
- `VmCompiler` register allocator + the curried `evalFusedFoldLeft` interp
  fast-path (−10.5%) + `foldLeftReusing` (CallRuntime:212, native loop calling the
  bytecode-JIT'd combine per element).

## The six pieces (in dependency order)

1. **List-iteration opcodes** (`SscVm`). The VM cannot walk a `List[Value]`. Add
   `LITERINIT dst,b` (refStack(dst) = a cursor over the ListV in refStack(b)),
   `LITERHN dst,b` (stack(dst) = cursor refStack(b) hasNext ? 1:0),
   `LITERNX dst,b` (refStack(dst) = next element Value, advance). Elements are
   **boxed Values** (IntV/…), so a numeric fold needs an unbox (`LITERNXI` →
   Long, or reuse an existing IntV→Long unbox). This boxed-element marshaling is
   the main correctness-surface of slice 1.

2. **`foldLeft` recognizer** (`VmCompiler`, in the `Term.Apply` `case None` arm,
   ~line 502). Match `Apply(Apply(Select(recv,"foldLeft"),[z]),[f])`. Compile
   `recv`→ref reg (ListV), `z`→acc reg. Emit: `LITERINIT cur,recv`; loop:
   `LITERHN t,cur` → `JMP end if !t`; `LITERNX e,cur`; *apply f*; `JMP loop`.

3. **Inline-lambda combine** (safe first win, no CALLREF). When `f` is a lambda
   literal `(a,b) => bodyExpr`, bind `a`→acc, `b`→elem and compile `bodyExpr`
   straight into acc. This handles `xs.foldLeft(0)((a,b)=>a+b)` — a common,
   testable, shippable pattern — with NO dispatch. **Slices 1+2+3 = the first
   shippable, measurable milestone** (add a `foldLeftLambda` bench).

4. **`using`-param threading** (`VmCompiler.typeGateOk`, line 97). Relax the gate
   so a function with `usingParams` compiles, treating each as a trailing ref
   param. KEY de-risking finding: the interpreter RESOLVES the given and APPENDS
   it to the args array *before* invoke (`CallRuntime.bindArgs` ~430–443), so the
   compiled `combineAll` simply receives the monoid InstanceV as its last ref
   param — no in-body given resolution needed. Verify the JIT invoke routing
   (`JitRuntime.tryRunN`) passes the resolved trailing arg.

5. **`summon[T]` → using register** (`VmCompiler`). Recognize
   `Term.ApplyType(Term.Name("summon"), _)` and resolve it to the using-param
   register (for a single using param, the last param).

6. **Type-method `.empty`/`.combine`** — the hardest. `summon[M].empty` /
   `.combine` are NOT InstanceV fields; they resolve via
   `lookupTypeMethod(inst.typeName, name)` (DispatchRuntime:3180) and invoke via
   `invokeTypeMethod` which binds `this`+fields. So a new opcode is needed —
   `TMLOOKUP dst,inst,nameSlot` (refStack(dst) = the type-method FunV) — and the
   per-element call must invoke it AS a type-method (receiver = the monoid), not a
   bare `CALLREF`. `.empty` is a 0-arg type-method call producing the initial acc.
   Slices 4+5+6 = the typeclass milestone that actually moves `typeclassFoldMacro`.

## Risk / payoff (why this is deliberate, not rushed)

- **Risk**: always-on JIT; a register-aliasing / unbox / effect-ordering / relaxed
  using-gate bug yields silently-wrong user results. Mitigated by flag-gating +
  JIT-on-vs-off differential tests, but the type-method path (slice 6) is genuinely
  intricate.
- **Payoff**: realistically `typeclassFoldMacro` 1.14 ms → ~0.1–0.3 ms (bounded by
  per-element type-method dispatch). It is a *synthetic* benchmark; real programs
  folding a runtime-resolved typeclass over a list in a hot loop are rare.

## Build order

Slice A (pieces 1+2+3, inline-lambda `foldLeft`) — shippable, measurable on a new
`foldLeftLambda` bench, zero given/type-method risk. Slice B (pieces 4+5) — using
+ summon plumbing. Slice C (piece 6) — type-method opcodes; lands the
`typeclassFoldMacro` win. Each slice: flag-gated, JIT-on-vs-off differential tests,
`scripts/bench interp` A/B, no regression in the full interp suite.
