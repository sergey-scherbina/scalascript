# v2 has no calling convention — the principled statement, and the direct-call arm as its narrow case

> Status: SPEC. The narrow fix (a direct-call arm) was attempted three times and is INERT for a
> structural reason; §"Principled statement" below is the reframing that came out of it.
> Task: `TASK/v2-perfomance.md` §v2-perf-6b. Rules: [`POLICY.md`](../POLICY.md) P-1.4, P-6.1.

## Principled statement (2026-07-31)

**Every call in v2 — direct or indirect — allocates an argument array and passes boxed values.
There is no calling convention.** The lambda-call defect is one symptom; the "fast" paths are slow
for the same reason.

Measured on one host, v2 bytecode lane, per call:

| | ns | what it is |
|---|---|---|
| raw loop iteration, no call | 0.71 | the floor v2 can already reach |
| top-level `def`, `INVOKESTATIC` | 11.2 | **the fastest call v2 has** |
| closure invoked by `foreach` | 7.1 | monomorphic site inside `Runtime` |
| lambda in a local, called directly | 48.7 | one shared megamorphic funnel |
| …the same, with Long args and a real body | 107 | + boxing per argument and result |
| **v1 doing that same call** | **2.9** | |

Two independent causes, and the narrow fix addressed neither:

**A · No per-call-site dispatch.** Every indirect call funnels through one static `Emit.app`, so
`c.code` is megamorphic *globally* and C2 can never devirtualise it — confirmed by
`-XX:+PrintCompilation`: hot at tier 4, compiled as a root rather than inlined, `made not entrant`.
`foreach` is 7× faster for one reason only: its call site lives inside `Runtime.foreachConsOp`,
private and monomorphic per loop, so the JIT inlines the closure body into the loop.
→ **`invokedynamic` per call site** (`MutableCallSite` + guard = a monomorphic inline cache). Each
site then shows the JIT one target. This needs **no static knowledge of the callee** — which is
exactly why it is the principled fix and the direct-call arm was not.

**B · No calling convention.** `genArray` (`:1394`) emits `ANEWARRAY` **per call**, on every path
including the direct `INVOKESTATIC` one, and every argument and result is a boxed `Value`. That is
what makes the *fastest* call v2 has cost 11.2 ns when v1 does the whole thing in 2.9.
→ **Arity-specialised entry points** `(Value)Value`, `(Value,Value)Value`, … for arity 1–4, and
unboxed `(long)long` / `(double)double` variants where the typed IR already knows the type — F5b
emits `i.*` and `f.*`, so the information exists and is unused at the call boundary.

**C · Method, not a fix: A-normalisation erases the syntactic link.** OpAnf rewrites any call in
expression position into a `Let` whose rhs `mayOp`, which materialises the frame. Every
generator-level pattern match on "the lambda is right here" is therefore fighting the IR — which is
why three attempts were inert. **If known-callee information is ever needed, compute it as an IR
analysis over the whole term and annotate the `App` nodes; never pattern-match shapes in the
emitter.**

**Order, and why.** A first: it is general (it fixes `foreach`-shaped, method-dispatch and
lambda-call sites alike), it is immune to the IR shape that defeated three attempts, and it is worth
~48.7 → ~7-11 ns. B second: it is worth ~11 → ~3 ns on *all* calls including the ones A already
fixed, and it is what closes the remaining gap to v1. Together they also move the collection cluster
(`v2-perf-6`/`v2-perf-9`), whose 7 ns per element is mostly B — which turns "price it as a
programme" into two concrete first steps.

## A · Per-call-site inline cache — the concrete design

The generator already speaks `invokedynamic`: `emitLamFnRef` (`:571`) uses `LambdaMetafactory` to
materialise a `LamFn`. So the machinery and its idioms are in the codebase; what A adds is a
**call-site** indy rather than a constructor one.

### A-0 · The prerequisite, and it is the thing three attempts kept circling

An inline cache needs a **stable identity for the callee**. `Emit.clos` builds
`Value.ClosV(captured, arity, env => Done(unroll(fn.call(env))))` — a **fresh `ClosV` and a fresh
forwarding Scala closure on every evaluation of the lambda expression**. So neither the `ClosV` nor
its `code` can be guarded on: a lambda created inside a loop is a different object every iteration.

The one stable thing is the **`LamFn`**, because `emitLamFnRef`'s metafactory call sites with no
captures return a singleton.

**So `ClosV` must retain its `LamFn`.** That is a core-representation change — and it is the same
change the earlier "fast arm inside `Emit.app`" idea would have needed. That idea was refuted
because a faster funnel helps the 7.1 ns path and the 48.7 ns path *equally*. **The retained
`LamFn` is not refuted; it was only ever the prerequisite, not the fix.** The fix is where the
dispatch happens, not how fast the funnel is.

### A-1 · The shape

At each generic `App(f, args)` site, replace `Emit.app` + `Emit.unroll` with

```
INVOKEDYNAMIC call(Value, [Value)Value   bsm = Emit.callSiteBootstrap
```

The bootstrap returns a `MutableCallSite` whose initial target is a linker. On first invocation the
linker inspects the receiver and installs

```
GuardWithTest(
  test   = receiver is a compiled ClosV && receiver.fn eq cachedFn && receiver.arity == n,
  target = cachedFn.call(Runtime.extend(receiver.env, args))   // direct, monomorphic
  fallback = relink)
```

so C2 sees **one target per site** and can inline the lambda body into the caller — which is exactly
and only why `foreach` costs 7.1 ns today.

### A-2 · Degradation, stated up front

A site that relinks more than N times (2 is the usual choice) is genuinely polymorphic; install
plain `Emit.app` permanently and stop relinking. Without that cap a megamorphic site pays the relink
cost forever and ends up **slower** than today — the failure mode this design must not ship with.

### A-3 · What it is worth, and what would disprove it

**Expected: 48.7 → 7–11 ns** at monomorphic sites, i.e. the `foreach` number, since that is the same
mechanism arrived at deliberately rather than by accident. It does **not** address the array
allocation or the boxing — that is B, and it is why the floor is 7 rather than v1's 2.9.

**Disqualifying evidence, to be taken BEFORE writing the linker:** build only A-0 (retain the
`LamFn`) and add a temporary counter to `Emit.app` recording how many distinct `fn` identities each
call site sees. **If the hot sites are not monomorphic, an inline cache buys nothing** and the whole
of A is void. That measurement costs one build and is the correct first step — not the linker.

### A-4 · Order within A

1. A-0 + the identity census (one build, answers whether A is worth doing at all);
2. the bootstrap and linker, guarded, with the relink cap;
3. measure with the alternating protocol on `lambda-call`, and check `foreach`-shaped rows do not
   regress — they already have their monomorphic site and must not lose it.

---

# Direct call for a lambda bound to a local — v2 bytecode lane

> Status: SPEC, not implemented. Required before code because this changes the core bytecode
> generator on the default execution lane. Task: `TASK/v2-perfomance.md` §v2-perf-6b.
> Rules: [`POLICY.md`](../POLICY.md) P-1.4, P-6.1.

## The defect, measured

`val f = (x: Int) => …; f(i)` — a lambda bound to a local and invoked directly — costs **48.7 ns
per call** on the v2 bytecode lane. The same closure invoked by `foreach` costs **7.1 ns**, and a
top-level `def` reached by `INVOKESTATIC` costs **11.2 ns**. Medians of three rounds, 100 000
invocations each, alternating protocol.

As a corpus row (`bench/corpus/lambda-call.ssc`, added 2026-07-30 before any fix):

```
lambda-call    ssc 0.0029    v2-bytecode 5.03    ratio 1734x
```

**That is the worst row in the corpus**, ahead of `lazylist-take` at 566×. It was invisible until
the row existed: every other higher-order workload passes its lambda as an *argument* to a
collection method, which is a different and far cheaper path. Real code writes the direct shape
constantly.

## Why it is slow — supported by two readings, and NOT by two others

`JvmByteGen` has three application arms: `App(Global g)` with a known method → `INVOKESTATIC`; the
self/mutual-tail arms; and everything else → `gen(f); genArray(args); Emit.app; Emit.unroll`. The
direct lambda call lands in the last one.

`-XX:+PrintCompilation` on a 3 000 000-iteration run:

```
3098   2  ssc.Emit$::app (273 bytes)
3145   4  ssc.Emit$::app (273 bytes)                <- tier 4 (C2): hot
3098   2  ssc.Emit$::app (273 bytes)  made not entrant
3110   2  ssc.Emit$::clos$$anonfun$1 (20 bytes)     <- the Emit.clos forwarder
```

`Emit.app` is hot enough for C2, is compiled **as a root rather than inlined into the call site**,
is **under** `FreqInlineSize` (273 < 325) so size is not the blocker, and **deoptimises** — the
signature of a call site whose speculative profile was invalidated. One shared static funnel,
reached from every generated call site, whose internal `Runtime.run(c.code, …)` sees every closure
shape in the program.

`foreach` escapes this because `Runtime.callClos` is private to `Runtime` and inlines into the
`foreach` arm, where `fn.code` sees few shapes.

**Two hypotheses were tested and killed before this one, and they must not be retried:**

1. *The `Runtime.run` + forwarder + `extend` wrapper is the cost.* **No** — `callClos` is
   `Runtime.run(fn.code, extend(fn.env, args))`, i.e. the same wrapper, and it is the 7.1 ns path.
   Both pay it. A fast arm inside `Emit.app` would speed up both equally.
2. *It is the allocation, or the closure body.* **No** — the slower probe allocates ONE closure and
   calls it 100 000 times while the faster allocates 10 000; and `foreach(x => { sum = 7L })`
   measured cheaper than `foreach(x => { })`, i.e. the body is inside the noise.

So the fix must make the **call site** monomorphic. Nothing else has survived contact with a
measurement.

## The change

### C-1 · Remember which local holds which compiled lambda

`Ctx` already carries `localTailTargets: Map[Int, (String, Int)]`, De Bruijn index → (method,
arity), but it is populated **only for `LetRec` groups** (`JvmByteGen.scala:1010`, `:1056`). A plain
`Let` whose right-hand side is a `Lam` is not in it.

Add a sibling map for that case. Key it by **JVM slot**, not by De Bruijn index: slots are stable
within a method, whereas the De Bruijn index of the same binding changes with depth, and
`ctx.slotFor(i)` already converts one to the other (`:163`).

Populate it in the pure-`Let` arm (`:975`), which already does `gen(r, ctx); val slot = ctx.push();
ASTORE slot`. When `r` is a `Term.Lam(ar, _)`, record `slot -> (method, ar)`. The method name is
minted inside the `Lam` arm (`:917`, `ctx.g.freshLam()`), so it must be handed back — the smallest
way is for the `Lam` arm to leave it in a one-slot `ctx` field that the `Let` arm reads immediately
after `gen(r, ctx)` and then clears.

**Only the pure-`Let` arm.** The effectful `Let` materialises a frame, and a local there is not a
JVM slot.

### C-2 · A direct-call arm for `App(Local i)`

Before the generic `case Term.App(f, args)`, add:

```
case Term.App(Term.Local(i), args)
    if !tail && i < ctx.slotDepth &&
       ctx.localLamSlots.get(ctx.slotFor(i)).exists(_._2 == args.length) =>
```

emitting:

```
ALOAD slot                                  the ClosV
INVOKESTATIC Emit.closEnv (Value)->Value[]  its captured env          ← NEW helper
genArray(args)
INVOKESTATIC Emit.extendArr ([Value,[Value)->[Value]                  ← see C-3
INVOKESTATIC <Gen>.<m> ([Value)->Value      the lambda's compiled body
INVOKESTATIC Emit.unroll (Value)->Value
```

`tail` is excluded deliberately: the tail case already has an arm (`:1257`) that returns a `Bounce`,
and mixing the two would break the trampoline's constant-stack guarantee.

### C-3 · The helper, and the one thing that makes this non-trivial

A direct `INVOKESTATIC` needs `captured ++ args` as its env, and **`captured` is not available
statically** — it lives inside the `ClosV` sitting in the slot. Hence `Emit.closEnv(v: Value):
Array[Value]`, returning `v.asInstanceOf[Value.ClosV].env`, plus an `extend` reachable from
generated code (`Runtime.extend` at `Runtime.scala:465` takes `(Env, Array[Value])`; confirm `Env`
is `Array[Value]` and expose it under a stable name if it is not).

`closEnv` must be **fail-fast, not fail-soft**: if the slot does not hold a `ClosV` the generator's
assumption is wrong and the program must die loudly at that point. A fallback to `Emit.app` there
would hide a miscompilation as a performance mystery.

## Correctness argument, and where it could be wrong

The arm is safe iff the slot provably holds the closure the `Let` bound. It does when: the binding
is a `val` (locals in this scheme are single-assignment slots), the RHS is a syntactic `Lam` (not a
value that merely happens to be a closure), and no rebinding or shadowing intervenes — shadowing
allocates a *new* slot, so the map key changes with it.

**The risk this spec exists to bound:** an arm that fires when the slot holds something else emits a
call into an unrelated method body. That is silent miscompilation, not a crash. Hence C-3's
fail-fast and the gate below.

## Attempt 1 — IMPLEMENTED, INERT, REVERTED (2026-07-31)

The change above was written exactly as specced (Ctx map keyed by JVM slot, `Lam` arm publishing the
minted method, pure-`Let` arm recording it, a `!tail` `App(Local i)` arm, `Emit.closEnv` fail-fast)
and built cleanly — 0 errors. **The arm never fires.**

Proven the way this spec demands, before any measurement: `Emit.closEnv` was replaced with
`sys.error("PROBE")` and the toolchain rebuilt. Neither shape dies —

```
val f = (x: Int) => 0L ; while … do sum = sum + f(i)     → runs, no error
val f = (x: Int) => 0L ; f(1) + f(2)                     → runs, no error   ← same method as the Let
```

**That is the value of the rule.** Had it been measured instead, the result would have been "no
gain", and the reading would have been "the megamorphic theory is wrong" — a third wrong conclusion
on this task, and this time an expensive one, since it would have condemned the correct theory.

**Two candidates ruled out while diagnosing:**
- `mayOp(Term.Lam)` is **false** (`JvmByteGen.scala:402`), so a `Let` with a `Lam` right-hand side
  really does take the pure arm that was patched — not the effectful one;
- the call being in a nested method (the `while` body) is not the whole story either: the
  same-method probe `f(1) + f(2)` does not fire the arm.

## Attempt 2 — same result, and it corrects attempt 1's diagnosis (2026-07-31)

Re-applied identically and rebuilt. **Three things were established, and one of them corrects the
record above.**

**1. The build was never the problem — attempt 1's suspicion was wrong.** `javap` on both the
compiled class and the staged jar shows the methods present:

```
public ssc.Value[] closEnv(ssc.Value);
public ssc.Value[] extendEnv(ssc.Value[], ssc.Value[]);
```

⚠ The check that said otherwise was mine: `unzip -p <jar> | strings | grep closEnv` returns nothing
because a jar's entries are deflated — piping the archive through `strings` reads compressed bytes,
not class constant pools. **`javap -p -classpath <jar>` is the check; `strings` on a jar is not.**
Attempt 1's probe was therefore VALID and its verdict stands.

**2. The arm is inert on BOTH fronts, measured.**

    F (default)   BEFORE 5.03    AFTER 5.11
    legacy        BEFORE 8.30    AFTER 9.67

**3. The IR shape is right, so the guard is what fails.** Legacy IR for the probe is exactly what
the arm was written for:

```
(lam 0 (let ((lam 1 (lit (int 0))))
         (prim __arith__ … (app (local 0) …) (app (local 0) …))))
```

*(Also worth carrying: this IR is the LEGACY front's. F emits typed IR that diverges by design, so
"the shape is right" was only ever established for one of the two lanes — dump F's IR via
`runir F0.ir <prog>` before relying on it. That is a second reason the cheap route misled.)*

**The leading candidate, and it is checkable without a build.** `ctx.localLamSlots` lives on ONE
`Ctx`, and a `Ctx` is created per generated METHOD. A `workload` body is a `Seq` (`var sum`,
`var i`, `while`, result), which `emitChain` splits into one method per statement, each with a fresh
`Ctx` — so the map recorded at the `Let` is gone by the time the call is generated. `localTailTargets`
survives that because it is threaded through `TailCtx` and shifted (`shiftTailCtx`); my map is not.

**So the fix is not a new map at all — it is to carry the target the way `localTailTargets` already
is carried**, i.e. add the plain-`Let` case to that existing mechanism and read it from the existing
non-tail position. Check before building: does `f(1) + f(2)` with NO surrounding statements fire the
arm? If it does, the Ctx-scope story is confirmed; if it does not, the guard fails for a third
reason and no more builds should be spent before the F IR is dumped.

**Cost of this task so far: three build cycles (~30 min) and no gain.** Recorded so the next attempt
starts from the Ctx-threading design, not from a fourth guess.

## Attempt 3 — the IR reading, taken as the spec demanded (2026-07-31)

F0 bootstrapped (460 377 B) and F's own IR dumped for the probe. **It is byte-identical in shape to
the legacy front's:**

```
def sameMethod (lam 0 (let ((lam 1 (lit (int 0))))
                        (prim __arith__ (lit (str "+")) (app (local 0) (lit (int 1)))
                                                        (app (local 0) (lit (int 2))))))
```

So the shape the arm was written for is confirmed on **both** lanes, and the "F's typed IR differs"
worry is closed for this construct.

**A second candidate is closed too, statically:** the arithmetic fast path does not swallow the
operands. `canLong` (`:633`) has no `App` case and returns **false**, so `(prim __arith__ … (app …)
…)` bails out of `genLong` and its operands are generated by the ordinary `gen`. The call site
therefore does reach the `case Term.App(…)` arms.

**Walking the guard against this IR, every conjunct holds:** the pure-`Let` arm is taken
(`mayOp(Lam)` is false); `gen(Lam)` mints the method and publishes it; `ctx.push()` returns slot 1;
`localLamSlots` records `1 -> (m, 1)`; in the body `i = 0`, `slotDepth = 1`, `slotFor(0) = 1`, and
`args.length = 1` matches the recorded arity; `tail` is false inside a prim operand.

**So the reasoning says it fires and the measurement says it does not — and there is exactly one
verification gap left, which is mine.** Attempt 1 replaced `closEnv`'s body with `sys.error` and
rebuilt, but **that jar was never checked to contain the throwing version** — only attempt 2's jar
was checked, and only for the methods' EXISTENCE (`javap -p`), not their bodies. The probe that
declared the arm dead was therefore itself unverified.

**Attempt 4 must close that gap first, and it is the whole of the next step:**

```
javap -c -p -classpath <staged jar> 'ssc.Emit$' | grep -A5 closEnv    # the BODY, not the signature
```

Only if the throwing body is provably in the artifact does "the arm does not fire" become a fact
rather than a third unverified claim. **This is the same failure this task has now produced twice —
an apparatus trusted without being checked** (`unzip | strings` on a deflated jar; a probe build
never inspected). `POLICY.md` P-6.1 says a gate must be observed failing before it is trusted; a
probe is a gate.

**Cost so far: three build cycles, one F0 bootstrap, no gain.** Everything durable from this task —
the 48.7 / 11.2 / 7.1 ns measurement, the `lambda-call` corpus row at 1734×, the `PrintCompilation`
cause — is unaffected by the implementation failing to land.

## ✅ CAUSE FOUND (2026-07-31) — and with no build at all

`SSC_DUMP_IR=<defname>` already existed (`v2/backend-jvm-bytecode/OpAnfNative.scala:37`) and prints
the IR **as the generator receives it**, before and after OpAnf. Every earlier IR dump was the
FRONT's output; this is the one that mattered.

```
=== IR[sameMethod] POST-lift ===
Lam(0){ Let[1]{ Lam(1){ Lit(CInt(0)) }
  in Let[2]{ App{ Local(0) Lit(CInt(1)) }
             App{ Local(1) Lit(CInt(2)) }
     in Prim(__arith__){ Lit(CStr(+)) Local(1) Local(0) } } } }
```

**OpAnf A-normalises the two calls into a second `Let`.** And `mayOp(Term.App)` is **true**
(`JvmByteGen.scala:394`), so that inner `Let` takes the **effectful** arm — which MATERIALISES the
frame. Once the frame is materialised the bindings are env-array entries, not JVM slots, so
`i < ctx.slotDepth` is false and the arm can never fire.

**This is why all three attempts were inert, and it is not shape-specific:** any call in an
expression position is A-normalised into a `Let` whose rhs `mayOp`, so the direct-call site is
*always* behind a materialised frame. A slot-keyed map cannot see it, ever.

**The corrected design — this is what attempt 4 implements.** Do exactly what `localTailTargets`
does, because it solves precisely this problem: key by **environment-relative De Bruijn index**, and
thread it through `TailCtx` / `shiftTailCtx` so it survives frame materialisation and the per-method
splits (`emitChain`, `emitLam`). The plain-`Let`-binds-a-`Lam` case becomes a second producer for
that existing map; the consumer is a new **non-tail** `App(Local i)` arm beside the tail one at
`:1257`, which already reads it correctly on the env side.

The `Emit.closEnv` / `extendEnv` helpers stay as specced — the captured env still has to come from
the `ClosV`, whether it is in a slot or in the frame.

### The lesson, and it is the expensive one from this task

**Three build cycles (~30 min) went into a guard that could never be entered, and the tool that
showed why was already in the repository.** `SSC_DUMP_IR` cost one command and no build. Before
rebuilding a compiler to test a hypothesis about what the compiler sees, **look for a way to print
what the compiler sees.** Two of this task's dead ends — the megamorphic reading and the Ctx-scope
reading — would both have been skipped by starting here.

**What is left to check, cheapest first:****What is left to check, cheapest first:****What is left to check, cheapest first:****What is left to check, cheapest first:**
1. **Does `val f = <lambda>` lower to `Let(List(Lam), body)` at all?** F may emit `LetRec` for a
   local binding so it can be recursive — in which case `localTailTargets` **already holds the
   entry**, and the fix is a non-tail arm reading that existing map rather than a new one. This is
   both the most likely and by far the cheapest outcome, so test it first.
2. If it is a `Let`, dump the Core IR for the probe and check whether the callee is really
   `Term.Local` — the front may be emitting something else at the call site.

**Get the IR before the next build.** Two build cycles were spent on a guard that was never
entered; a single IR dump would have shown it. `ssc info` is not the dumper — the bench harness's
`v2CoreIr()` uses `ssc.Writer.program(compiled.program)`, and `/tmp/ssc-x1.jar` (a `v2/src`
assembly) can run IR directly.

## Verification — in this order

1. **Prove the arm is LIVE before measuring.** Rename the emitted target to a nonexistent method:
   `bench/corpus/lambda-call.ssc` must die; a `def`-call program must keep working. *An inert change
   measures as "no gain" and reads as "the theory was wrong" — that already happened once on this
   task and cost two days.*
2. **`foreach`-shaped rows must NOT move.** `list-fold`, `hof-pipeline`, `range-sum`,
   `lazylist-take` take the other path. If they move, the arm is firing where it should not.
3. Conformance: the affected slice, plus `tests/e2e/v2-front-coverage.sh` and the X1 fixpoint
   (`specs/v2.2-p6.5-fsub.sh --self`, needs `SSC_JAR=<assembly>` and `V2_DIR`).
4. **Alternating A/B, three rounds, swapping only the built jar** — this change is compiled Scala,
   so both arms need a build; keep the BEFORE jar rather than rebuilding it per round.

**Expected size:** 48.7 → about 11 ns, the `def` floor already measured, i.e. **~4×** on
`lambda-call`. **Disqualifying evidence:** if the arm is live and `lambda-call` does not move, the
megamorphic reading is wrong too, and the remaining candidate is that the cost is inside the
lambda's own compiled body rather than at the call — measure the same lambda called through a
`def` wrapper to separate them.
