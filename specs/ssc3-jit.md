# SSC3 — execution performance and the tier ladder

> What v3's executor costs today, why the answer is **not** a port of v1's JIT, and the order the
> tiers have to arrive in. Design context: [`../v3/specs/10-ssc-ir.md`](../v3/specs/10-ssc-ir.md)
> §1–§3 and [`../v3/specs/00-charter.md`](../v3/specs/00-charter.md) invariants I-1 … I-5.

Status: **J0 queued**. Nothing here is implemented yet; `v3/SPRINT.md` carries the queue.

## 1 · The measurements, and what each one is worth

Two runs, `2026-08-09`, on a host at load ~43 with sibling agents building. Recorded in
`bench/history.tsv`. A contended host does not report contention, it reports a defect
(`00-charter.md`), so these are **order-of-magnitude facts, not ratios to defend**:

```bash
./v3/ssc3 bench --warmup 300 --reps 5 bench/corpus/arith-loop.ssc   # BENCH_MS: 226.9897834
./v3/ssc3 bench --warmup  50 --reps 5 bench/corpus/list-fold.ssc    # BENCH_MS:  60.0569834
```

Both answers are correct — `VInt(499999500000)` and `VInt(550000)` — so this is the cost of being
right slowly, not of being wrong fast.

| workload | v3 exec | v1 `ssc` | `jvm` | order |
|---|---|---|---|---|
| `arith-loop` | 226.99 ms | 0.244 | 0.241 | ~900× |
| `list-fold` | 60.06 ms | 0.0062 | 0.000338 | ~10 000× |

The v1/`jvm` columns are `bench/BASELINE.md`, captured 2026-06-15 on a different machine state, and
the v1 column is **already JIT-compiled**. Comparing across sessions is why the last column says
"order" rather than a number — re-measure both arms alternating before quoting a ratio anywhere
(`.agents/plugins/performance`).

### 1.1 · The static fact, which needs no benchmark at all

`javap -c -p` over the classes the driver had just built
(`v3/.jars/ssc3-a5781bce769e56fa/ssc3/Exec$.class`), highest bytecode offset per method:

```
13415  Exec$.invoke(Module, String, Value, List[Value])
 5478  Exec$.step(Module, Instr, Value[])
 4352  Exec$.binOp(Module, BinOp, Value, Value)
 2551  Exec$.prim(Module, String, List[Value])
```

HotSpot's `-XX:DontCompileHugeMethods` threshold is **8000 bytecodes**. `Exec.invoke` is 13 415, so
**HotSpot never compiles it**: every `xs.map`, `s.length`, `.foldLeft`, every method call any v3
program makes, is executed by the JVM's own bytecode interpreter for the life of the process, at
roughly interpreter speed, forever. That is the second row of the table.

`step` at 5478 does compile, but both it and `binOp` are far past `-XX:FreqInlineSize` (325), so
neither is ever inlined into `exec` — the dispatch loop pays a megamorphic call per instruction.

This is a repeat, not a discovery: the v2 runtime had a 49 384-bytecode method on its call path, and
splitting it was worth 2.4–10.8×. The lesson that did not carry over is that **nothing in the build
looks at method size**, so the next one will also arrive silently. §4 fixes that.

**Order-of-work consequence.** Until `invoke` is under the limit, every measurement of every later
tier is taken against a baseline that HotSpot refuses to compile. That makes J0 a prerequisite for
*measuring* J1–J3, not merely the cheapest win.

## 2 · Why this is not a port of v1's JIT

v1's JIT is `VmCompiler` (65 KB) + `AsmJitBackend` (5131 lines) + `JavacJitBackend` (4476), and
[`vm-jit-spec.md`](vm-jit-spec.md) §2 says what nearly all of it is for: **getting a register machine
out of a scalameta tree** — "why a register VM (not stack)", the register-window stack, the
per-register type inference, the Int/Double domain classification.

**v3's `Ir.scala` is that machine already.** A linear instruction sequence over a fixed-size register
frame, structured control flow, an explicit `TailCall`, and a `kind` field on every arithmetic
instruction placed there for exactly this purpose. Porting v1 would mean building a layer v3 owns by
construction. What must be ported is the *lessons*, and there are four:

**L1 — v1 is a bail architecture, and v3 must not be.** A narrow compilable subset; everything else
falls back to the tree-walker permanently (`JitRuntime` sets `disabled` after one failed compile).
[`jit-completeness.md`](jit-completeness.md) exists to chase the bail list one reason at a time —
310 disabled functions, `201 call: no compilable target`, `54 unsupported: Term.Select`. The list is
open-ended because its input is an AST. **v3's opcode set is closed and finite** (~30 cases in
`Instr`), so full coverage is a reachable state rather than a treadmill, and any tier that cannot
handle an opcode is a hole with a name rather than a shape nobody enumerated.

**L2 — per-unit state must not be keyed on identity.** v1 keys a synchronized `IdentityHashMap` on
`FunV` instances and documents the resulting leak (`vm-jit-spec.md` §6.1, "a long-running process
that mints many distinct named closures would accumulate entries"). v2 fixed it by making the counter
*be* the code node (`JitSite extends (Env => Step)`). **v3 can do better than both**: the unit is a
`Func`, and a `Func` is a stable index into `Module.funcs`. Per-function state is an `Array[Int]`
indexed by function id — no map, no identity, no synchronization, no leak, and it is in the portable
subset.

**L3 — loops need their own counter, and in v3 they are visible.** v1 patched hot `while` loops with
eager compilation plus `WhileJitEntry` because a loop body never re-enters the call path, so a
call counter cannot see it. v2 gave loops a separate site with a separate threshold (256 vs 8).
v3's `Instr.Loop` is an explicit region: back edges are in the data, and the counter has somewhere
obvious to live.

**L4 — the four operational rules v2 paid for.** (a) Compiling is not something the program should
*wait* for: v2 measured ~187 ms of compile in a hello-world run and found the threshold to be the
wrong lever — the right one is to keep running interpreted and swap when the unit is ready. (b)
Arming is decided once, before the first unit; with the JIT off the counting node is **absent**, not
cheap. (c) Statistics go to **stderr, never stdout**, or the parity gate compares a program against
itself. (d) An absent backend is a supported configuration, never a failure.

## 3 · The tier ladder

Each tier is separately shippable and separately measurable. Tiers J0–J2 are **inside the portable
subset** ([`../v3/specs/30-portable-subset.md`](../v3/specs/30-portable-subset.md)) and therefore run
on both hosts, which is the property v1 and v2 could never have: the self-hosting lane gets the speed
too. J3 is the only tier that needs a decision about the charter, and it is deliberately last.

### J0 · The executor stops being slow — no JIT involved

Not optimization in the interesting sense; removing work that is pure overhead.

1. **Split the huge methods** so no `Exec` method exceeds 8000 bytecodes, and the hot ones
   (`step`'s arithmetic and register cases) come in under 325 so they can inline. `invoke` splits by
   receiver kind — the `Value` case it dispatches on is already the natural seam.
2. **Materialize the constant pool once.** `Instr.Const` today calls `constOf(m.consts(k))`, which
   builds a fresh `Value` on *every execution*. A `Array[Value]` built at load makes it a load.
3. **`List[Instr]` → `Array[Instr]` per region**, built at load. The dispatch loop stops chasing
   cons cells and `.tail`-ing through the hot path.
4. **Small-integer cache** for `Value.VInt`, the loop-counter case.

*Blocked on `ssc3-cps-split`*, which holds `v3/src/Exec.scala`. Sequenced after it releases rather
than rebased into it.

### J1 · The specializer — a rewrite of DATA

`Ir.scala` says what this is for in as many words: *"the front emits `Dyn` unless it can prove the
operand type, and the specializer rewrites the field in place. Optimization is then a rewrite of
data, not a change of representation."*

Measured today: `Lower.scala` emits `NumKind.Dyn` at **all nine** of its `Bin`/`Un` sites and `I64`
at none, and `Exec.step` matches `case Instr.Bin(op, _, d, a, b)` — the underscore is the `kind`
field. **The lever the IR was designed around is emitted blank and read nowhere.**

1. `Specialize.module(m: Module): Module` — a pure pass. Propagate what is provable (literal kinds
   through the const pool, parameters whose every call site agrees, results of already-specialized
   instructions) and rewrite `Dyn` → `I64` / `F64` / `Big` where proved. Unproved stays `Dyn`;
   there is no failure mode, only a less specialized module.
2. `Exec` dispatches on `kind` **first**, so an `I64` add is a `long` add against two known-shape
   registers rather than a 40-arm tuple match in `binOp`.
3. **`Invoke` name → id at load.** `Invoke` carries a constant-pool index naming an `LStr`; resolving
   it once to a small integer (plus a per-call-site monomorphic inline cache keyed on the receiver's
   shape) takes the 13 415-bytecode string match off the hot path entirely, which J0 has by then
   already split but not made cheap.
4. **Superinstructions** — `Bin(Lt,…); BrIf` and `Const; Bin` fused. The classic interpreter win.

**Superinstructions and specialized opcodes are RUNTIME-ONLY.** The serialized `.ssir` and its text
form stay exactly what `10-ssc-ir.md` §3 defines. The alternative — new `Instr` cases — touches the
instruction set, the verifier's five rules, the canonical text form, the bridge to v2 Core IR and
every differential gate that compares them, in exchange for nothing the runtime form does not
already give. Parked with that trade-off rather than rejected.

### J2 · Closure compilation — still portable

Convert each `Func` body once into a tree of closures. Instruction decode disappears: operand
indices are captured, and the dispatch switch becomes a call. This is the rung neither v1 nor v2 has,
and being in the portable subset it is the one that makes v3 fast *as a self-hosted compiler* and not
only as a JVM program.

#### J2's design, settled 2026-08-10

**One type.** `Op = Array[Value] => Signal` — a compiled instruction, taking the frame and returning
the same four-case `Signal` the tree-walker returns. A compiled body is an `Array[Op]` and the
dispatch loop is `while i < ops.length do ops(i)(regs)`.

**What it removes, per instruction, per execution:** the pattern match on `Instr`, and the operand
decode. `Instr.Const(d, k)` becomes a closure that has already resolved `k` to a `Value` and
captured it — not an array read, not a pool index, nothing. `Bin` has its two source registers and
its proved `kind` baked in. A region no longer re-walks a `List[Instr]` and re-dispatches every
instruction inside it on every iteration of its loop.

**Coverage is a decision, not a limit.** The opcode set is closed (§2, L1), so the compiler could
handle all of it — but it does not have to on day one, and pretending otherwise is how v1's bail
list started. Every instruction the compiler does not specialize becomes
`regs => Exec.stepOne(m, i, regs)`: the exact interpreter arm, reached through one closure. So the
lane is COMPLETE from its first commit and gets faster as opcodes move across, and there is no
program it refuses.

**Behind `--closures`, default off, and that is the measurement design rather than caution.** Two
execution strategies over the same IR, selectable in one binary, is the best A/B rig available here
(§8.1) *and* a differential: `--identity` gains a second comparison in which two independent
executions of every corpus program must agree byte for byte. That is the technique the charter
credits with finding 8 defects in UniML, applied to the executor itself.

**What it does NOT do:** touch the frame. `callFunc` still allocates `new Array[Value](nregs)` per
call, so `recursion-fib` is expected not to move — the same control that made the J0 numbers mean
something, kept deliberately for J2.

#### J2 was MEASURED SLOWER, and the reason is J0c — 2026-08-10

Everything above is the design as it was reasoned. The measurement disagreed with it, so the
paragraph that predicted a win is corrected here rather than deleted, because the prediction and its
refutation are the useful pair.

8 alternating pairs per workload, one binary, `--closures` against the default:

| workload | closures | tree-walker | closures won | result |
|---|---|---|---|---|
| `arith-loop` | 171.8 ms | 110.3 | **1 of 8** | **~1.56× SLOWER** |
| `nested-loop` | 256.7 ms | 211.4 | 2 of 8 | ~1.21× slower |
| `list-fold` | 40.8 ms | 39.5 | 4 of 8 | unchanged |

1 of 8 is significant in the direction opposite to the one intended (sign test, p ≈ 0.035), and the
third row is the control that makes the other two credible at a host load of 69–96: `list-fold` is
`invoke`-bound, the closure lane DELEGATES `Invoke`, so that row could not move and did not.

**The mechanism, and it is a lesson about baselines rather than about closures.** "Compile to
closures beats a tree-walker" assumes the tree-walker pays a switch dispatch plus an operand decode
per instruction. **J0c removed exactly that**: `step` is 236 bytes and INLINED into `exec`, and its
match over a sealed `Instr` compiles to a tableswitch — one hot, well-predicted path. The closure
lane replaces that with `ops(i)(regs)`, a **megamorphic** call site: dozens of distinct closure
classes reach it, so HotSpot cannot inline it and dispatches through a vtable, and each region
closure adds a frame on top. The optimization was designed against a baseline that no longer
existed by the time it was written.

**Kept anyway, and not out of sentiment.** The lane's second property is independent of its speed:
it is a SECOND EXECUTION STRATEGY over the same IR, and `jit-gate.sh --identity` now runs every
program both ways and compares bytes. That differential is proven to discriminate — swapping the two
operands of `Bin` in the compiler alone turned **14 of 73** programs red, each naming the closure
lane, while the rest stayed green. A correctness net that selective is worth more than the lane's
lost 1.5×, and it costs nothing when the flag is off.

**For whoever retries this:** the array-of-closures dispatch is the part that lost. A CHAINED
design — each closure calling its successor directly instead of returning to a loop — gives the JIT
a monomorphic-per-site call and is the shape the literature actually measures. That is a different
experiment, not a tweak to this one.

### 8.2 · Frame pooling: refuted before it was written

`recursion-fib` is the slowest row in the corpus and the one nothing has moved, and `callFunc`
allocates `new Array[Value](nregs)` per call — so a frame pool is the obvious next move. **The
assumption behind it was checked first, and it is false.**

```text
java -Xlog:gc … bench recursion-fib
76 collections, 66.8 ms total pause   over a ~14 s run   →  0.5 %
each: 338M -> 2M          everything dies young, nothing is promoted
```

A pool removes GC *pauses*, and GC pauses are **half a percent** of this workload. The ceiling on
the whole idea is smaller than one round of measurement noise on this host. **Do not implement frame
pooling** — and the reason is written here rather than left as an absent task, because "the frame is
the next target" is exactly what the J0 control measurement seemed to say, and it is what a
reasonable person would build next.

**What the same numbers DO say:** 76 × 336 MB is roughly **25 GB allocated** in that run. The volume
is real; it is the GC *pause* that is not. So the target is allocating LESS, not recycling what is
allocated — and the frame is only part of it, because every arithmetic result is a fresh
`Value.VInt` too. That points back at J1's parked item, the two-bank frame (`Array[Long]` beside
`Array[Value]`), which removes the boxing rather than reusing the box. It is a bigger change than
anything in J0 and it now has a measurement pointing at it.

This is the second time in two days that checking an optimization's assumption before writing it
changed the answer — §8.1 for J2, this for the pool. The check cost one run each time.

## 9 · J1c — the two-bank frame, and the measurement that named it

**The assumption was checked first, and this time it held.** `arith-loop` makes exactly one call, so
its frame is allocated once — and it still reclaims **~4 087 MB** of young generation in a bench run.
Nothing else in that program allocates: every megabyte is a `Value.VInt` or `Value.VBool` boxing an
arithmetic result. `recursion-fib` reclaims ~16 519 MB, frames and boxing together. GC *pause* is
0.5 % (§8.2), so what costs is producing the garbage, not collecting it.

**The design.** A frame gains a second bank, `Array[Long]`, beside its `Array[Value]`. A register
lives in the long bank when every instruction that writes it writes a value the specializer proved
`I64`. `Bin`/`Un`/`Move`/`Const` then read and write `long` directly and allocate nothing;
everything else — `Call` arguments, `Ret`, `MkData`, `Invoke`, `Prim` — boxes on the way out, which
is orders of magnitude rarer than once per operation.

**This is the payoff for J1.** The specializer proves 34.5 % of the corpus's arithmetic instructions
and, so far, that has bought exactly nothing measurable (§8, J1b): choosing an arithmetic branch on
a proved kind is not worth anything when the branch still allocates its result. Consuming the same
proof to skip the allocation is the first use of it with a mechanism behind it.

**Scope, stated so the number is not oversold.** Parameters stay boxed in this pass: they arrive as
`List[Value]` from the caller, so unboxing them is a calling-convention change and a bigger one.
That means `recursion-fib`, whose hot value is a parameter, is expected NOT to move — the same shape
of control as before, predicted from the mechanism rather than after the fact. `arith-loop` and
`nested-loop`, whose hot registers are locals, are where the 4 GB is.

**The long bank is allocated only when the function has a long-bank register**, because a second
array per call would otherwise make every call-heavy program worse to speed up a loop-heavy one.

### 9.1 · It worked, and it was still slower — the executor is DISPATCH-bound

The design above was implemented, measured and **reverted from the executor**. The analysis stays,
the lane does not.

**The mechanism did exactly what it promised.** `arith-loop` went from ~4 087 MB of young generation
to **~391 MB — 10.5× less allocated** — with the answers unchanged. That number is load-independent
and it is not in dispute.

**The wall clock refused to follow.** 8 alternating pairs per workload, one host, two class
directories of the same tree:

| workload | banked | pre-J1c | banked won | |
|---|---|---|---|---|
| `arith-loop` | 83.1 ms | 61.0 | 3 of 8 | 0.73× |
| `nested-loop` | 98.7 ms | 112.7 | 5 of 8 | 1.14× |
| `list-fold` | 30.2 ms | 23.9 | 2 of 8 | 0.79× |

The tell is `list-fold`: it has almost no long-bank registers, so the bank cannot help it, and it got
**worse**. That is not a workload effect, it is an overhead every program pays — a function with one
long-bank register sends EVERY instruction down the banked path, and the opcodes that are not in the
banked hot core land in a 2 302-byte `stepBankedRest` instead of the 236-byte `step` that J0c got
inlined.

Splitting the banked core the way J0c split `step` was tried, and it worked as a threshold —
`stepBanked` came to 356 bytes ("hot method too big"), then 314 with `Mul` moved out, and
`-XX:+PrintInlining` says `inline (hot)`. **It changed the wall clock by nothing.**

**So the hypothesis this section was built on is refuted, and precisely:** §8.2 concluded that GC
*pause* was 0.5 % but the 25 GB of allocation was "real", and that reducing it was the target.
Reducing it by 10.5× bought nothing. Young-generation allocation on this JVM is close to free in
both halves — the pause AND the allocation itself — and what the executor's time actually goes into
is DISPATCH.

**That is the through-line of the whole ladder, and it is worth more than any single result here.**
Three changes in a row moved the thing they targeted and lost on the clock: closure compilation
(§8.1) replaced the dispatch and lost; the long bank left the dispatch alone but routed it through a
second loop and lost; and the one change that clearly won — J0c — did nothing except make the
existing dispatch *inlinable*. The executor is dispatch-bound, and the dispatch is already good.

**What that says to do next, and it is not more of this:** stop trying to make a dispatch cheaper
and reduce the NUMBER of dispatches. Superinstructions — fusing `Bin(Lt); BrIf` and `Const; Bin`
into single instructions at load time — keep the exact loop J0c tuned and simply push fewer
instructions through it. That is the one item from §3 J1 that was never built, and it is now the
only one the evidence points at.

## 10 · J1d — fewer instructions, and the honest state of the ladder

Copy propagation: `<something> → r` immediately followed by `Move(d, r)`, with `r` written once and
read once in the whole function, becomes the same something writing straight to `d`. A rewrite of
data; the instruction set, verifier and text form are untouched.

**Established, load-independently:**

| workload | instructions |
|---|---|
| `arith-loop` | 20 → **16** |
| `nested-loop` | 34 → **28** |
| `list-fold` | 56 → 54 |
| `recursion-fib` | 18 → 17 |

`arith-loop`'s LOOP BODY goes from 10 to 8 — a fifth of the dispatches in the corpus's hottest loop,
gone. Two of the remaining eight are still `Const` reloading loop-invariant literals, which is the
next pass and not this one.

**Not established: any speedup.** 8 alternating pairs on one binary — `arith-loop` 3 of 8 at 0.81×,
`nested-loop` 6 of 8 at 1.13×, `list-fold` 3 of 8 at 0.91×, at host load 42–45. A 20 % effect is an
order below this host's ~2× resolution floor, so these numbers say nothing in either direction and
the ratios must not be quoted as a regression.

### 10.1 · Where the ladder actually stands, stated plainly

Five attempts, and the score is one clear win:

| | mechanism | did the mechanism work? | did the clock move? |
|---|---|---|---|
| J0a/b | derived tables, `invoke` split | yes — never-compiled → compiled | **yes**, 7 of 8 pairs |
| J0c | `step` under the inline limit | yes — `inline (hot)` | part of the same win |
| J1b | `Exec` reads `kind` | yes | no, 5 of 10 |
| J2 | closure compilation | yes | **worse**, 1 of 8 |
| J1c | unboxed long bank | yes — 10.5× less allocated | **worse**, 3 of 8 |
| J1d | copy propagation | yes — 20 % fewer instructions | unmeasurable here |

**The one thing that worked was making the existing dispatch cheaper to run, not replacing it and
not feeding it less.** Everything since has moved its own target and left the clock alone or worse.

**So the honest recommendation is to stop optimising this executor on this host.** Not because the
remaining ideas are bad — loop-invariant hoisting and superinstructions both reduce dispatch counts
further, and both are cheap — but because **nothing under about 2× can be told from noise here**, and
four consecutive measurements have proved that rather than assumed it. The next person with a quiet
machine should re-run the `bench/history.tsv` rows for J1b, J1c and J1d before writing any new code:
two of those three may already be wins nobody can see, and one of them is a revert that might have
been unnecessary.

**Kept from this attempt:** `Specialize.longBanks` and its `--banks` gate. The analysis is correct,
hand-checked and asserted, and it is what any future unboxing needs on day one — including the
`F64` extension the `float-loop` fixture is watching for. **Not kept:** the executor lane, because
an unused fast path with an invariant coupled to an analysis in another file is debt, and this one
was measurably negative.

### J3 · Host bytecode behind a by-name seam — a separate decision

The shape is settled by precedent (`v2/src/Jit.scala`): the kernel names a class as a **string**,
resolves it once on the first hot unit, and a `null` answer means the unit stays interpreted. The
kernel never mentions ASM, never loads it, and a build without the backend is a supported
configuration. What is *not* settled is whether v3 wants a host-specific artifact at all, given I-1
and I-2 — and that question is worth answering with J0–J2's numbers in hand rather than before.

## 4 · The gates — and the one that is green by construction

**Build the gate before the feature** (`00-charter.md`), and plant the defect it exists to catch.

| gate | proves | how it is proven able to fail |
|---|---|---|
| `v3/jit-gate.sh --sizes` | no `Exec` method over 8000 bytecodes, hot ones under 325 | **it is RED on `main` today**: `invoke` is 13 415. The failing state is the starting state, which is the strongest form of P-6.1 evidence there is |
| `v3/jit-gate.sh --specialize` | `Specialize.module` assigns the kinds a hand-checked fixture must get | fixtures assert the `kind` field directly, per instruction, via the text form |
| `v3/jit-gate.sh --identity` | output of the corpus is byte-identical with the pass on and off | revert the specializer's rewrite and the fixtures in row 2 go red |

**Row 3 is green by construction until J1 step 2 lands, and saying so is the point.** While `Exec`
ignores `kind`, a pass that rewrites `kind` cannot change output *whatever it writes there* — so the
identity gate would pass a specializer that assigned `F64` to every string concatenation. It is
evidence about tiers J0/J2 and about J1 only *after* the executor reads the field. Until then, row 2
is the only check with an opinion. This is the repository's most expensive recurring failure
(`AGENTS.md` §"measurement apparatus must COMPARE, never PRE-JUDGE") and the way to not repeat it is
to name which gate is asleep and when it wakes up.

Two more that already exist and must keep working: `v3/parity-gate.sh` compares `run` against
`run --bridge` — a JIT that made it compare the executor against itself would be the exact failure
it was fixed for in August. And stats go to stderr, or the parity gate's stdout comparison becomes a
lie (v2 paid for this with `BytecodeFallbackMarker`).

## 5 · Invariants

- **I-1 (zero external dependencies)** — J0–J2 add no dependency; J3 adds none *to the kernel*, by
  construction of the seam.
- **I-2 (portable subset)** — J0–J2 use `Array`, `while`, `var`, closures and pattern matching, all
  in §1 of the subset spec. J3 is host-only and outside the kernel.
- **I-3 (the two hosts agree)** — a specialized module is still a `Module`; `portable-diff.sh`
  compares emitted `.ssir` and the pass runs on the executor's side of that boundary.
- **I-4 (nothing runs unverified)** — `Specialize` runs **after** `Verify.module`, and its output is
  re-verified in the gate so a rewrite that broke a validation rule cannot hide.
- **I-5 (compatibility is a number)** — `v3/corpus-report.sh --exec` must not move. Speed that costs
  `N` is not a win.

## 6 · Parked alternatives, with the trade-off that parked them

- **Port `AsmJitBackend` directly.** ~5k lines against an AST-shaped input v3 does not have, and it
  brings the bail architecture (L1) with it.
- **New `Instr` cases for specialized ops.** §3, J1: touches the instruction set, verifier, text
  form, bridge and every differential gate; the runtime-only form gives the same speed for none of it.
- **NaN-boxing the register file.** Needs host bit-twiddling the portable subset does not have.
  Revisit only if J1's two-bank `Array[Long]` + `Array[Value]` measurably falls short.
- **Threshold tuning as the answer to compile latency.** v2 measured it and it is not: raising the
  threshold 256× removed 75 % of units but only 41 % of the cost, because the expensive units are the
  hot ones. Off-thread compilation is the lever.

## 7 · Order

J0 → measure → J1 → measure → J2 → measure → decide J3. Every arrow is an alternating A/B recorded
in `bench/history.tsv` with the command that produced it, because on this host one run is a
hypothesis.

## 8 · What the first day actually taught, 2026-08-09

J1 and J0 both landed. **Neither produced a wall-clock number worth reporting, and that is the
finding**, not a footnote to it.

**Four A/Bs were run and all four were inconclusive.** J1b: 10 alternating pairs, "on" faster in 5
of 10. J0a: 6 pairs per workload, 4 of 6 and 3 of 6. Median differences were 2.7 to 15.5 ms against
pooled standard deviations of 22 to 43. Within-arm spread ran 1.6× to 3.9× at host loads of 45 to
72. **The resolution floor of this host, measured rather than assumed, is about 2×** — every change
in this ladder except a tier that eliminates the interpreter is smaller than that.

Two consequences, both cheap and both worth adopting before the next tier:

1. **Prefer evidence with a THRESHOLD in it.** `java -XX:+PrintCompilation` settled J0b in one run
   at load 72: at 13415 bytecodes `ssc3.Exec$::invoke` never appears in the compilation log; at
   6912 it does. That is the entire claim of that change, checked deterministically, for free. Ask
   of every proposed optimization *what discrete thing does this make true* — compiled vs not,
   allocated vs not, one pass vs O(n) — and check THAT, then measure the wall clock when a quiet
   machine exists.
2. **Two class directories are a legitimate A/B rig.** `v3/ssc3` keys its build directory on a
   digest of the sources, so a pre-change and post-change build coexist and can be alternated in
   one loop, on one host, in one JVM version: `java -cp v3/.jars/ssc3-<digest>:<toolchain>
   ssc3.ssc3 bench …`. No stashing, no rebuild between arms, and no chance of measuring the wrong
   binary — which is a failure this repository has recorded more than once.

**What is NOT concluded:** that these changes do not help. An inconclusive A/B is not a negative
result, and `invoke` moving from never-compiled to compiled is not something a reasonable person
expects to be free. The measurement is owed, on a quiet machine, and the exact commands are in the
`bench/history.tsv` rows.

### 8.1 · The number arrived, by measuring the SUM and waiting for the load to drop

Each J0 change is smaller than the floor; **all three together are not.** Measured at host load 38
— 8 alternating pairs per workload, two class directories of the same tree:

| workload | pre-J0 | J0 | pairs won | ratio |
|---|---|---|---|---|
| `list-fold` | 91.3 ms | **52.8** | **7 of 8** | **1.73×** |
| `arith-loop` | 82.6 ms | **64.9** | **7 of 8** | **1.27×** |
| `recursion-fib` | 428.0 ms | 410.2 | 4 of 8 | 1.04× |

**The statistic is the sign test, not the ratio of medians.** 7 of 8 one-sided is p ≈ 0.035; the
pooled standard deviation (38–106 ms) still exceeds the median difference, because a few runs are
outliers and the median is what survives them. Quote "7 of 8 pairs, ~1.7×" and not "1.73".

**The third row is the control, and it is why the first two mean something.** `recursion-fib` did
not move, and it is exactly the workload J0 did not address: it is dominated by the per-call frame,
`callFunc` allocating `new Array[Value](nregs)` and filling it with `VUnit` on every call. Had the
host merely quietened down, that row would have moved with the others — it cannot, because both
arms alternate inside one run. The prediction was made from the mechanism before the numbers were
read, which is the only order in which a control is worth anything.

And each row's mechanism was already established without a stopwatch: `list-fold` is the
`invoke`-bound one (13415 → 6912, never-compiled → compiled), `arith-loop` is the one whose loop
body is a constant, a move and an arithmetic operation (list walk plus allocation → array read;
`step` 5867 → 236, now inlined). **The threshold evidence predicted which rows would move, and then
they moved.**
