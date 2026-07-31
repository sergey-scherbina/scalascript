# v2 wide JIT — a run-time JIT for the v2 VM lane, in v1's image

**Status:** spec / design (no code yet). Written 2026-07-31 at Sergiy's direction — *"общий широкий
JIT для ssc v2 по образу и подобию JIT в ssc v1"*.

Companion reading, in this order: [`vm-jit-spec.md`](vm-jit-spec.md) (what v1's JIT *is*),
[`jit-universal-coverage.md`](jit-universal-coverage.md) (why v1's is still narrow),
[`wide-jit-typed-input.md`](wide-jit-typed-input.md) (why v1 cannot get types to its code
generator), [`v2-f5c-typed-bytecode.md`](v2-f5c-typed-bytecode.md) (why v2's VM lane has **no** JIT
today), [`v2-vm-production-jit-gate.md`](v2-vm-production-jit-gate.md) (the route policy this
replaces).

---

## 1. Where v2 stands today — the facts this design is built on

**Lane 0 — the VM lane, and it is the default.** `bin/ssc run` → `RunNativeV2.run(…, bytecode =
false)` → `runVm` (`v1/tools/cli/.../RunNativeV2.scala:27,512`). `Compiler.C.compile(Term): Code`
turns Core IR into a tree of closures (`type Code = Env => Step`), `Runtime.run` bounces tail calls
on a trampoline (`v2/src/Runtime.scala:439`). Every value is a boxed `Value`; every env is an
`Array[Value]`.

**This lane has had no JIT at all since 2026-07-23.** `f5c-4` deleted `FastCode`, `SelfRecLL`,
`SelfTailRecLL2`, the closed-form loop recognizer and `ClosV.fcEntry` — 1,340 lines — on the
explicit bet that the *bytecode* lane covers the numeric-recursion class
(`v2-f5c-typed-bytecode.md` §4.4). The bet was sound for that class and left the default lane with
tier 0 only.

**Lane 1 — the bytecode lane, and it is all-or-nothing.** `--bytecode` →
`JvmByteGen.emitProgram(Program): Array[Byte]` (Core IR → JVM bytecode via ASM), linked against the
static surface `ssc.Emit` (`v2/jvm-runtime/src/main/scala/ssc/Emit.scala`), loaded by a `GenLoader`
and entered through `install()` + `entry()` (`JvmByteGen.scala:1463`). It compiles **whole
programs**: one `Unsupported` throw (9 sites) or one ASM `MethodTooLarge` anywhere, and the entire
program falls back to the VM (`RunNativeV2.scala:543-559`) — announced on stderr, because a silent
fallback once certified "bytecode/VM parity" for a program the bytecode backend never compiled.

**Lane 2 — v1's interpreter JIT, the thing we are asked to mirror.** 12,400 lines under
`v1/runtime/backend/interpreter/.../vm/jit/`: identity-keyed hot counters at a call-count threshold
of 8, three engines (`SscVm` register VM, `JavacJitBackend`, `AsmJitBackend`), typed unboxed
dispatch interfaces, a `JitBailReason` vocabulary, `JitMissStats`, `ssc lint-jit`. Measured 198×
(`recursionFib`) and 278× (`recursionTco`) end-to-end over its own tree-walker.

**What one call costs on the v2 VM lane** — `V2DispatchBench`, JMH, 2026-07-31 (`v2/SPRINT.md` P-4):

| layer | ns/op | adds |
|---|---:|---|
| `jvmAdd` (floor) | 0.345 | — |
| `boxedAdd` | 1.329 | +0.98 `Value` boxing |
| `arithFast` | 2.620 | +1.29 typed arith — **this is what untyped `__arith__` actually costs** |
| `preResolvedFn` | 3.979 | +1.36 `List[Value]` + closure call |
| `resolvePerCall` | 10.437 | +6.46 string-keyed lookup — *paid once at compile time, not hot* |

So dispatch is **11.5× the floor**, and only ~1 ns of it is boxing. That is the size of the prize,
and it is also why this work needs JMH: this host swings 2.5× on identical whole-workload runs
(`v2/SPRINT.md`, first constraint).

---

## 2. Goal, and what "wide" has to mean here

**Goal.** Every function that becomes hot on the VM lane runs compiled, and **no function is ever
rejected as a whole**. Coverage is a property of the design, not the outcome of a race between two
AST walkers.

That second clause is the entire difference from v1. v1's JIT asks *"can I compile this function?"*
and answers `null` for most real code — `jit-universal-coverage.md` §2 records 300 missed functions
on one engine and "silent (unobserved)" on the other two. v2 can ask a strictly better question,
*"which parts of this function do I compile, and which do I leave in the interpreter?"*, because on
this lane the interpreter form of every subterm is **already a callable `Code` closure**. A subterm
the emitter does not know becomes a call back into that closure, not a bail.

**Non-goals** (stated so a later agent does not expect them):

- Replacing the `--bytecode`, `v2-jvm` or `v2-rust` routes, or changing the route policy of
  `v2-vm-production-jit-gate.md`. This makes the *default* lane fast; it does not deprecate AOT.
- A second Core IR walker. There will be exactly one (`JvmByteGen`), extended — see §3.1.
- Deoptimization after side effects. Guards are entry-only (§3.5); there is no mid-body bailout that
  re-runs a term twice.
- Changing observable semantics anywhere. `SSC_V2_JIT=off` and the JIT on must be byte-identical on
  the whole corpus; that is a gate, not an aspiration (§6).
- Non-JVM hosts. The JIT is a JVM-lane accelerator; where ASM is absent it stays off and the VM runs
  exactly as today (§3.6).

---

## 3. Design

### 3.1 One walker, reused: `JvmByteGen` becomes the JIT backend

v1's own retrospective is the argument: *"The two bytecode backends share structural predicates but
have **independent AST walkers** — adding a shape requires touching both files"*
(`jit-universal-coverage.md` §2), and coverage diverged between them until a spec had to be written
to reconcile it. v2 must not repeat that. `JvmByteGen` already compiles the whole Core IR surface,
already emits unboxed `Long`/`Double` paths (`canLong`/`canParamLong`/`genLong`), already
understands F's typed `i.*`/`f.*` prims, and already links against a runtime surface (`ssc.Emit`)
that is *shared with the VM*.

**No register VM.** v1 needed `SscVm` because its tier 0 is a scalameta tree-walker; v2's tier 0 is
already a compiled closure tree, so a register VM would be a second walker buying one tier where the
JVM's own JIT is the better second tier. *Parked alternative:* if a non-JVM host ever needs a fast
tier (GraalVM `native-image` with no runtime class definition, a future Rust host), a portable
register VM over Core IR is the answer, and it is a separate programme.

### 3.2 The JIT unit, and why the plumbing is nearly free

`Emit.LamFn` — `trait LamFn { def call(env: Array[Value]): Value }` — is *already* the interface
generated lambda bodies implement, and `Emit.clos(arity, fn, captured)` already wraps one as a VM
`ClosV` "by construction" (`Emit.scala:158-164`). So:

> **A JIT unit is one Core IR `Lam` body compiled to a `LamFn`, and installing it is
> `code := env => Done(Emit.unroll(fn.call(env)))`.**

No new bridge, no new calling convention, no `MethodHandle` zoo. (v1 needed 30+ typed interfaces in
`JitInterfaces.scala` because it had no such surface; v2's unboxed entries in §3.5 add at most a
handful, and only where they pay.)

### 3.3 Where the counter lives — a site, not an instance

v1 keys JIT state on the `FunV` *instance* through a synchronized `IdentityHashMap`, because
`FunV` is an enum case that cannot carry state (`vm-jit-spec.md` §6.1), and pays a documented leak
for it. v2 does not have that problem: a `Lam` body's `Code` is built **once per site** at compile
time and shared by every `ClosV` allocated there (`Runtime.scala:652` top-level defs, `:682` the
`Lam` case, `:738` `LetRec`). The counter belongs to the site.

And because `Code` is `Env => Step` — a SAM — the counter can *be* the code:

```scala
final class JitSite(val body: Term, val arity: Int, val name: String, slow: Code)
    extends Function1[Env, Step]:
  @volatile private var fast: Code | Null = null      // installed unit
  private var hits: Int = 0                           // benign race, v1 §6.4
  def apply(env: Env): Step =
    val f = fast
    if f != null then f(env)
    else
      hits += 1
      if hits == JitPolicy.threshold then JitPolicy.compile(this)
      slow(env)
```

The trampoline, `ClosV`, `applyStep`, `Emit.app` and every call site are **untouched** — the site
sees every entry because it *is* the body. This is the single most important reason the first slice
is small.

**Loops need their own sites.** A `While` body never re-enters `Runtime.run`'s `Call` bounce
(`Runtime.scala:912-918` runs the loop in a plain Java `while`), so a call counter alone would never
see a hot loop — v1 hit exactly this and had to special-case eager compilation for self-tail-
recursive functions (`vm-jit-spec.md` §7b) and add `WhileJitEntry`. In v2 the same `JitSite` wrapper
goes around the `While` body `Code` with a back-edge counter; installation is the same field flip,
so there is **no on-stack-replacement machinery** — the next iteration reads the new field.

### 3.4 Residual callbacks — the mechanism that makes it wide

When the emitter reaches a term it cannot compile, today it throws `Unsupported` and the caller
loses the whole program. In JIT mode it instead emits a call to

```scala
Emit.residual(unitId: Int, slot: Int, env: Array[Value]): Value   // = Runtime.run(residuals(slot), env)
```

where `residuals(slot)` is **the interpreter `Code` the VM would have run for that exact subterm**.
Semantics are identical by construction: it is the same closure, on the same env.

Consequences, stated rather than discovered:

- **Function-level coverage becomes 100 %.** `Unsupported` stops being a compile outcome and becomes
  a *cost*: a site with residuals is compiled and slower, not skipped.
- **Tail position is excluded.** `Emit.residual` runs the subterm to a `Value`, which flattens the
  trampoline for that call; a residual in tail position would turn unbounded mutual tail recursion
  into JVM stack growth. Rule: residualize **non-tail subterms only**; if the tail position itself
  is unsupported, do not compile the unit (i.e. exactly today's behaviour, localized to one site).
- **A residual is a measurement, not a mystery.** Each one is counted by term class, so
  `SSC_V2_JIT_STATS` names the shapes worth teaching the emitter next — the census v1 had to
  reconstruct from JFR archaeology.

### 3.5 Types: feedback at run time, not a typed IR

`wide-jit-typed-input.md` traced v1's narrowness to a structural cause — the frontend's types never
reach the code generator, `VmCompiler` re-infers and defaults to `TInt` — and chose strategy (C),
threading a typed tree end-to-end. That is the right fix for v1. For v2 it is neither necessary nor
sufficient: F types only `Int | String | BigInt` (`v2/SPRINT.md` VC-2c — a `Long` parameter comes
out as untyped `__arith__` everywhere, and the one attempt to widen it made F silently *decline*
programs), and the corpus writes `Long`.

The v2 answer is the one a JIT is allowed to give: **observe**. Each `JitSite` records a per-
parameter tag profile during tier 0 (`IntV`/`FloatV`/`StrV`/`DataV(tag)`/`ClosV`/other, saturating
to polymorphic). At compile time:

- a parameter seen monomorphically as `IntV` gets the unboxed `(J…)J` entry that
  `JvmByteGen.canParamLong` already emits, **including the `INSTANCEOF IntV` entry guard it already
  emits** — the machinery exists, it just has never been driven by anything but syntax;
- a guard that fails at entry runs the interpreter body instead. **Entry-only**: nothing has been
  evaluated yet, so no side effect can be duplicated. This is the same safety argument v1 makes
  ("the compiled subset is pure, so re-running is value-identical"), except it does not require the
  subset to be pure — only that the check happens before the first step.

This also closes, from the runtime side, the gap VC-2/VC-2c can only close from the front side: a
`var` whose type F cannot name still gets an unboxed cell if every value stored in it was an `IntV`.

### 3.6 Isolation, loading, and the flags

- **ASM must not load on the VM lane unless the JIT fires.** `v21-plugin-backend-isolation` is
  enforced by smokes, and `RunNativeV2` already contorts a `catch` clause to avoid *mentioning* an
  ASM type. So the kernel gets an SPI — `trait JitBackend { def compileUnit(...): LamFn | Null }` in
  `v2/src`, implementation in `v2/backend-jvm-bytecode` — resolved by name on the first hot site.
  Class absent, or `SSC_V2_JIT=off` → the field stays `null` and the VM runs precisely as today.
  This mirrors v1's `JitBackend.default` SPI + `enabled`.
- **Globals must be one namespace.** Generated code reads `Emit.global` / `Emit.globalsRef`
  (`Emit.scala:332-352`); the VM keeps globals in `Compiler.compileWithGlobals`'s `TrieMap`. The
  first slice points `Emit.globalsRef` at that same map so both tiers see one namespace, including
  the auto-created `@`-cell globals both sides create on first touch. Hazard to check in the gate:
  `runBytecode` *resets* `globalsRef`, so the JIT must not be enabled inside a bytecode run.
- **Class lifetime.** One hidden class per unit via `MethodHandles.Lookup.defineHiddenClass`, so a
  unit is collectable with its `LamFn`; `SSC_V2_JIT_MAX_UNITS` caps a long-running server. v1's
  documented leak (strong refs in the identity cache) is the failure being designed out.
- **Flags** — deliberately **not** `SSC_JIT*`, which is v1's and is set by `bench/run.sc` to select
  the `ssc-asm` lane; reusing it would make a bench row ambiguous about which JIT it measured:

  | var | default | meaning |
  |---|---|---|
  | `SSC_V2_JIT` | `on` (after J-9; `off` until then) | `off` disables every tier |
  | `SSC_V2_JIT_THRESHOLD` | 8 (v1's) for calls, 256 for loop back-edges | tier-up policy |
  | `SSC_V2_JIT_STATS` | unset | print the per-site report at exit |
  | `SSC_V2_JIT_MAX_UNITS` | 4096 | compilation budget |

### 3.7 Effects

`OpAnfNative.lift` is a whole-program pass and its purity registry is a least fixpoint over the call
graph (`v2-f5c-typed-bytecode.md` §3) — a per-unit JIT cannot recompute it per site. It does not
have to: `Compiler.compileWithGlobals` already knows every `Def`, so the fixpoint is computed once
at program-compile time and handed to `JitPolicy`. A unit whose body `mayProduceAutoThreadOp` is
compiled with the lifted term, exactly as the bytecode lane does; if the lift is unavailable for a
site, that site is not compiled (localized, per §3.4).

---

## 4. Coverage accounting — and a gate that can tell the tiers apart

v1's bail vocabulary is the part worth copying wholesale, because the alternative is JFR
archaeology. `JitReason` in v2 describes *residuals and misses*, not failures:

| case | meaning |
|---|---|
| `Residual(termClass)` | a subterm ran in the interpreter inside a compiled unit |
| `GuardMiss(param, tag)` | an entry guard rejected an argument; the unit ran interpreted |
| `TailUnsupported(form)` | tail position unsupported → the unit was not compiled (§3.4) |
| `SizeLimit` | ASM `MethodTooLarge`/`ClassTooLarge` for this unit |
| `Budget` | `SSC_V2_JIT_MAX_UNITS` reached |

**The tool is `ssc lint-jit`, extended — not a new command.** v1's diagnostic is
`ssc lint-jit <file.ssc>` (`v1/tools/cli/.../LintJitCmd.scala`, category *Diagnostics*, flags
`--json --quiet --fail-on-bail --include-while --backend javac|asm|both`); it walks
`interp.globals`, asks the live `tryCompile` for each `Defn.Def`, and prints the structural bail
reason plus a suggested refactor. (`ssc check-jit-coverage` from `jit-universal-coverage.md`
Stage 1 was **never built** — do not go looking for it.) v2 gains a **lane flag** on the same
command rather than a second name:

```
ssc lint-jit [-v2 | -v1] [--json] [--quiet] [--fail-on-bail] [--include-while]
             [--backend javac|asm|both] <file.ssc>
```

- **`-v2` is the default** — a bare `ssc lint-jit <file>` reports the v2 wide JIT.
- `-v1` selects the v1 interpreter JIT: today's behaviour, and where `--backend javac|asm|both`
  applies (v2 has one backend, so `--backend` with `-v2` is an error, not a silent no-op).
- The two are mutually exclusive; giving both is an error rather than last-one-wins.

**This flips the default of an existing command, and that has to be said out loud** in `summary`,
`details` and the release note: an invocation that lints the v1 lane today will lint v2 after J-8.
The flag is what makes the switch visible; a silently re-pointed diagnostic is the same class of
defect as a fallback that does not announce itself (`BytecodeFallbackMarker`).

*Spelling note:* six other commands write this as `--v2`/`--v1` (`Main.scala:1294`, `:1614`,
`:4926`, `:5100`, `RunBatchCmd.scala:34`, `StandardMain.scala:51`). `-v2`/`-v1` here is Sergiy's
call, 2026-07-31; if the inconsistency ever bites, change it in one place — this line and the
`details` text.

| view | invocation | answers |
|---|---|---|
| static | `ssc lint-jit <file.ssc>` (i.e. `-v2`) | which terms *would* residualize, which defs have an unsupported tail position (§3.4), per-unit size estimate — **before** running |
| dynamic | `SSC_V2_JIT_STATS=1 ssc run <file.ssc>` | which sites got hot and compiled, the residual histogram, guard misses |

Both print the **same `JitReason` vocabulary**, which is the unification v1's Stage 1 set out to
retrofit across its three engines and never finished. One concept keeps one name: two names for one
thing is the `FIXED`/`DONE`/`RESOLVED` drift this repo has already paid for once.

Static lint is weaker here than in v1 *by design* — with residual callbacks (§3.4) a function is
almost never rejected, so "will this JIT?" stops being the interesting question and "how much of it
will run compiled?" replaces it. That is why the dynamic view, not the lint, is what the gates below
assert on.

**The apparatus rule that this project keeps paying for:** *a gate is only a gate if it can
distinguish the two states*. An output gate cannot — the interpreter prints the right answer, so
`SSC_V2_JIT=off` and on are both green whether or not a single unit compiled. Two of the three gates
below therefore assert on **counters**, and the parity gate asserts that the run it is comparing
actually *was* JIT'd:

1. **Parity** — every corpus/conformance program, `SSC_V2_JIT=off` vs on, byte-identical stdout,
   **and** `SSC_V2_JIT_STATS` shows ≥ 1 compiled unit for the on-run. Without the second clause it is
   `bc-parity-sweep` comparing a program against itself, which is a defect this repo has already
   shipped once (`BUGS.md scljet-jdbc-facade-bytecode-class-too-large`).
2. **Coverage** — compiled-unit count and residual histogram, frozen as a floor per slice.
3. **Speed** — §6.

---

## 5. Slices

Each is one commit-sized piece with its own gate. The sprint entries live in `v2/SPRINT.md`
§"v2 wide JIT"; this section is the contract they cite.

| id | what | gate |
|---|---|---|
| **J-0** | Baseline + apparatus. Re-measure the four rows on *today's* main (the last table predates the FastCode removal, so it is about different code) and extend `V2DispatchBench` with a `jitSiteOverhead` case. | numbers recorded in this spec §7 with the exact commands |
| **J-1** | `JitSite` wrapper + counters at the four `Lam`-body sites and the `While` body. **No compilation** — the field is never set. | JMH: tier-0 overhead ≤ noise vs `HEAD~1`; corpus byte-identical |
| **J-2** | `JitBackend` SPI + lazy by-name load + flags + `Emit.globalsRef` bridge. | ASM not loaded when `SSC_V2_JIT=off` (assert on a `-verbose:class` grep); isolation smokes green |
| **J-3** | `JvmByteGen.emitUnit(body, arity, captured): LamFn` — one `Lam` body → one hidden class, boxed `Value` in/out, **no residuals** (unsupported ⇒ site not compiled). | parity gate (§4.1) on the corpus; ≥ 1 unit compiled on `recursion-fib` |
| **J-4** | Residual callbacks (§3.4) + the non-tail rule. | residual histogram non-empty on a program J-3 refused; parity holds; **revert-the-fix check**: with residuals disabled the refused program must go back to 0 compiled units |
| **J-5** | Type feedback + unboxed entries + entry guards (§3.5). | `var-expr-init` and `arith-loop` rows; `GuardMiss` counter proves the guard is live (rename-the-prim probe, per VC-4) |
| **J-6** | Loop back-edge sites and their threshold. | `arith-loop`, `range-sum`, `nested-loop` |
| **J-7** | Effect-aware units: purity fixpoint threading (§3.7). | `effects`, `effects-handler`, `algebraic-effects`, `generators`, `async-demo` byte-identical; `v2/conformance/check.sh` |
| **J-8** | `SSC_V2_JIT_STATS` + `ssc lint-jit` with `-v2` (default) / `-v1` (§4), one `JitReason` vocabulary for both. | both views name ≥ 1 residual class on a known-partial program and agree on it; `-v1` still reproduces today's report byte-for-byte |
| **J-9** | Default-on decision, with the measured evidence, or a recorded reason to stay opt-in. | the four-row bench + `scripts/smoke-ci` + a CI run |

**J-1 before J-3 is not bureaucracy.** A counter on every call is the one change that can slow down
*programs that never JIT*, and it is the only slice whose regression would be invisible in a
whole-workload bench on this host (§6). It gets its own JMH A/B before anything is built on it.

---

## 6. How this gets measured

- **Below 2× → JMH only.** `scripts/bench` → `V2DispatchBench` (landed 2026-07-31, error bars
  ±0.02–0.11 ns). The whole-workload harness on this host swings 2.5× on identical code; a number
  from it below 2× is not a result. This is why J-1's gate is a JMH case and not a corpus row.
- **At or above 2× → the corpus**, alternating A/B, 3 rounds, disjoint ranges required:
  ```bash
  ./bench.sh --warmup-time 500 --reps 20 arith-loop recursion-fib recursion-tco pattern-match-heavy
  ```
  Read the **`front` column** (`v2/SPRINT.md` P-3): 5 of 36 rows are compiled by the fallback front,
  and a conclusion drawn from one of those is about a different compiler.
- **Correctness before speed, every slice**: `./v2/conformance/check.sh`,
  `tests/conformance/run.sh --only '<affected>'`, `scripts/smoke-ci` before the push.

Historical reference points, explicitly **not** a baseline (they predate the FastCode removal —
re-measure in J-0): `pattern-match-heavy` v2 17.0 ms vs `ssc` 0.059; `recursion-fib` v2 6.61 vs 1.29;
`recursion-tco` v2 0.275 vs 0.031 (`v2-vm-pattern-match-heavy-fast-tier.md`, 2026-07-10).

---

## 7. Decisions

- **Reuse `JvmByteGen`; do not write a register VM.** Chosen because v2's tier 0 is already a
  compiled closure tree, and because v1's two independent walkers are the documented cause of its
  fragmented coverage. Rejected: a portable Core IR register VM — parked for a non-JVM host (§3.1).
- **Residual callbacks instead of function-level bails.** Chosen because it is what makes the JIT
  *wide* on day one instead of after a multi-stage coverage programme, and because the interpreter
  form of every subterm is already a callable closure. Rejected: v1's `tryCompile → null` shape,
  which is why `jit-universal-coverage.md` exists.
- **Run-time type feedback instead of a typed IR.** Chosen because F types only
  `Int | String | BigInt` and widening it made F silently decline programs, while `JvmByteGen`'s
  unboxed path already carries the runtime guard a feedback-driven JIT needs. Rejected (for now):
  v1's strategy (C) end-to-end typed tree — orthogonal, and it helps the AOT lanes more than this one.
- **Counter on the site, not the closure instance.** Chosen because the body `Code` is built once
  per `Lam` site, which removes both the `IdentityHashMap` and the leak v1 documents.
- **Entry-only guards, no mid-body deopt.** Chosen because a bailout after a side effect is a
  correctness bug, not a slow path; the same reason `RunNativeV2` refuses to catch runtime failures
  in its bytecode fallback.
- **Extend `ssc lint-jit` with `-v2` (default) / `-v1`; do not mint a second command.** Chosen
  because the `ssc` CLI already drives both lanes (`RunNativeV2` lives in `v1/tools/cli`) and
  because one concept must keep one name. Rejected: a separate `ssc jit-report` — it was in this
  spec's first draft, and it also mis-cited `ssc check-jit-coverage`, which
  `jit-universal-coverage.md` Stage 1 proposed and nobody ever built. Rejected: `--lane v2`, two
  tokens for a binary choice (Sergiy, 2026-07-31). **`-v2` as the DEFAULT is the deliberate part** —
  it says which lane the project expects you to be diagnosing, and it is why the default flip must
  be announced rather than inferred.
- **`SSC_V2_JIT*`, not `SSC_JIT*`.** Chosen because `bench/run.sc` sets `SSC_JIT_BACKEND` to select
  the v1 `ssc-asm` lane; a shared name makes a bench row ambiguous about which JIT it measured.
- **Off by default until J-9.** Chosen because "default lane, every program" is the widest blast
  radius in this repo, and the route policy in `v2-vm-production-jit-gate.md` is currently explicit
  rather than automatic.

## 8. Risks

| risk | mitigation |
|---|---|
| Tier-0 counter slows programs that never JIT | J-1 is its own slice with its own JMH A/B |
| Residual in tail position breaks TCO | non-tail-only rule (§3.4), and the unit is not compiled otherwise |
| ASM loaded on the VM lane breaks plugin isolation | by-name SPI load; `-verbose:class` assertion in J-2 |
| Generated classes accumulate in a server | hidden classes + `SSC_V2_JIT_MAX_UNITS` |
| Two globals maps diverge (`Emit.globalsRef` vs the VM's `TrieMap`) | one map, wired in J-2; JIT refuses to arm inside a `--bytecode` run |
| A gate that is green either way | §4: counters, not output, plus the revert-the-fix check |
