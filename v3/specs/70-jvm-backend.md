# v3's own JVM backend — the fork, with the evidence, put to the owner

> Stage 12's first deliverable. [`../BACKLOG.md`](../BACKLOG.md) sequences v3's own backends
> **JVM → JS → Rust → Swift → Python → R** and says each one starts with a plan document, the JVM
> one asking "bytecode directly à la v2's `backend-jvm-bytecode`, or source-level à la `run-jvm` —
> put to the owner with measured trade-offs before code." This is that document. No backend code
> exists yet and none should until the questions in §7 are answered.
>
> Every number below names the command that produced it. Where something is inferred rather than
> measured it says so in the same sentence.

## 1 · What the JVM target is today

`ssc3 build` emits **v2 Core IR** through [`BridgeV2.scala`](../src/BridgeV2.scala) (2,039 lines),
and v2's fleet takes it from there — the VM, or `JvmByteGen` for real bytecode. That is how v3 has
had a JVM target since SSC3-3 without writing a backend, and it is what stage 12 retires **per
target, only once the replacement beats a parity gate against the bridge on the full corpus**.

The bridge is not a stopgap to be embarrassed about. It is invariant I-3's second lane, and the
charter is explicit that it stays real. Retiring it for the JVM means the JVM target stops going
through it — not that the bridge stops existing.

Where the two lanes stand, from `v3/BACKLOG.md` and `v3/SPRINT.md` (2026-08-30):
**executor 278, bridge 275, of ~380 corpus files.**

## 2 · The fork has three arms, not two, and invariant I-1 is why

**I-1 names backends explicitly:** *"The kernel — lexer, AST, IR, verifier, executor, **backends** —
builds with an empty `libraryDependencies`: the JDK and nothing else."*

That is not an aspiration in this tree, it is the observed state. Measured:

```sh
grep -h '^import' v3/src/*.scala        # -> nothing at all
grep -ho 'java\.[a-z]*\.[A-Za-z]*' v3/src/*.scala | sort | uniq -c | sort -rn
```

**The v3 kernel contains zero `import` statements across 16,920 lines.** Every JDK use is written
out in full — `java.nio.file` 25 times, `java.io.File` 4, `java.io.IOException` 3, `java.lang.Long`
2, `java.lang.Double` 2, and one each of `java.util.regex`, `java.util.IdentityHashMap`,
`java.lang.reflect`. (The 42 lines matching the word "import" are all comments and strings about
ScalaScript's *own* import syntax.) A backend that pulls in a library would be the first dependency
this tree has ever had.

So the two arms the backlog names are not on equal terms, and neither is admissible as written:

| arm | its dependency | admissible in the kernel? |
|---|---|---|
| **A · bytecode**, à la `v2/backend-jvm-bytecode` | `org.ow2.asm:asm:9.7` (`build.sbt:271`) | **no, as written** — but see §3 |
| **B · source**, à la v1's `run-jvm` | a Scala compiler, at run time | **no** — and it is the exact dependency [`toolchain-gate.sh`](../toolchain-gate.sh) exists to keep out |
| **C · source**, Java, compiled in-process | none — `javax.tools` is the JDK | **yes**, on a full JDK only — see §5 |

Arm C is not in the backlog entry. It exists in this document only because asking the I-1 question
produced it, and it is written up honestly rather than dismissed.

Each arm also has a *location* variant: live outside the kernel as a plugin, the way
[`v3/plugins/JvmInterop.scala`](../plugins/JvmInterop.scala) and `V2Fleet` already do, where a
dependency is legal. That would make arm A admissible with ASM unchanged. It is a real option and
§6 weighs it.

## 3 · Arm A, measured: the hard part of a hand-written class writer is optional

The objection to a JDK-only bytecode emitter is always `StackMapTable` — the per-branch-target type
map the split verifier requires, which is a dataflow analysis, and which is most of what ASM's
`COMPUTE_FRAMES` is worth. `JvmByteGen` uses exactly that:

```
v2/backend-jvm-bytecode/JvmByteGen.scala:237  new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)
v2/backend-jvm-bytecode/JvmByteGen.scala:238  w.visit(Opcodes.V17, …)
```

**The requirement is a function of the class-file version, and the boundary was probed rather than
recalled.** A hand-built class file with one real branch (`iconst_1; ifeq L; getstatic; ldc;
invokevirtual; L: return`) and **no `StackMapTable` attribute at all**, emitted at four versions and
run on this host's Java 21.0.7:

| `major` | JVM verdict |
|---|---|
| 49 (Java 5) | **runs** |
| 50 (Java 6) | **runs** |
| 51 (Java 7) | `VerifyError: Expecting a stackmap frame at branch target 12` |
| 52 (Java 8) | `VerifyError: Expecting a stackmap frame at branch target 12` |

Generator and probe: [`v3/tests/jvm-backend-probe/run.sh`](../tests/jvm-backend-probe/run.sh),
which emits the constant pool by hand in ~40 lines of Python. It is checked in because a number
whose apparatus is gone cannot be re-read on the JDK the next reader has. The boundary is exact — 50 is the last version whose verifier falls back to type inference,
51 is where that failover was removed.

**The first version of this probe proved nothing, and the reason is worth keeping.** It used a
straight-line method with no branch, and *every* version ran it — including 52 — because a method
with no jump targets needs no stack map frames. A probe that answers the same for both hypotheses is
not a probe. Adding the branch is what made the versions differ. (The second version was wrong too,
in a way that announced itself: `ifeq`'s operand is a *relative* offset and I wrote an absolute one,
so v49 said `Illegal target of jump or branch` — a different error from the one under test, which is
how it got caught.)

**So arm A inside the kernel is: write the class file at major 50 and emit no stack maps.** The
constant pool, the method table and the `Code` attribute are bookkeeping — the probe writes a
working class in 40 lines of Python. What is bought is the whole of ASM's reason to exist here.

**What major 50 costs, stated rather than buried: no `invokedynamic`** — it needs 51. `JvmByteGen`
builds every closure with `invokedynamic` + `LambdaMetafactory` against a SAM
(`JvmByteGen.scala:17`), and that spelling is unavailable. `MkClos`/`CallV` would emit one generated
class per lambda implementing a shared interface instead, which is what every JVM language did
before Java 8 and what Scala 2 did until 2.12. This is question **Q2** in §7 — it is a real cost and
it is not mine to pay silently.

## 4 · What v3 has already done that both prior arts had to do for themselves

This is the measurement that most changes the size of the job, and it is the reason the two
line counts below are not comparable at face value.

| | lines | files |
|---|---:|---:|
| **A** · v2 `backend-jvm-bytecode` (ASM, CoreIR → bytecode) | **2,402** | 4 |
| **B** · v1 `runtime/backend/jvm` (Scala source, `run-jvm`) | **12,588** | 31 |

Arm B's two largest specialised pieces are `JvmGenCpsTransform.scala` (1,195) and
`JvmGenPreamble.scala` (955) — a CPS transform for effects, and a trampolined Free-monad runtime
emitted ahead of the user's code (`JvmGen.scala:37` measures that preamble at ~180 KB per module).
**v3 needs neither, because both jobs are already done in the IR, before any backend sees it:**

- [`TailCalls.scala`](../src/TailCalls.scala) (319 lines) turns self tail-calls into a loop and
  folds a mutual tail-call group into **one** function with a selector parameter, so "the JVM has no
  tail calls" is answered by a pass, not by a runtime.
- [`Cps.scala`](../src/Cps.scala) (197 lines) splits a function at a `Perform` so the continuation
  is an ordinary `MkClos` closure — "`k` is a `VClos` built by the compiler, not a machine the
  executor reifies."

A backend consuming post-`TailCalls`, post-`Cps` SSC IR therefore inherits the two hardest things
about targeting the JVM. It has **30 instructions** to cover — `Const Move Un Bin Block Loop If Br
BrIf Call CallV MkClos TailCall Ret MkData Field Tag Switch NewArr ArrGet ArrSet ArrLen GlobGet
GlobSet Try Perform Handle Resume Invoke Prim` (`v3/src/Ir.scala:119-190`) — and control flow that
[`10-ssc-ir.md`](10-ssc-ir.md) §2 guarantees is structured by construction, so emitting labels and
gotos is a single walk with no relooper.

The IR spec claimed this in advance and named this exact case: *"Emitting JVM bytecode or native
code from regions means emitting labels and gotos in a single walk."* Stage 12 is where that claim
gets tested.

## 5 · Arm C, measured: real, JDK-only, and it dies where the product ships

`javax.tools.JavaCompiler` is part of the JDK (`jdk.compiler`), so emitting **Java** source and
compiling it in-process is I-1 clean where a Scala compiler is not. Probed, not assumed —
[`v3/tests/jvm-backend-probe/JcProbe.java`](../tests/jvm-backend-probe/JcProbe.java) generates a
class, compiles it in-process and runs it:

```
getSystemJavaCompiler() -> com.sun.tools.javac.api.JavacTool@…
compile ok = true
in-process javac produced this: 3
```

**And on an image without `jdk.compiler` it does not merely degrade, it fails to link:**

```
$ java --limit-modules java.base -cp . JcProbe
Caused by: java.lang.NoClassDefFoundError: javax/tools/JavaFileManager
```

That matters because of something this repository has already paid for. `scripts/native-release-qualify:701-720`
records that `--bytecode` is **refused** on the native binary, with the error from the first run that
ever reached it:

```
com.oracle.svm.core.jdk.UnsupportedFeatureError:
  No classes have been predefined during the image build to load from bytecodes at runtime
```

and the owner's decision of 2026-08-05 that a refusal beats a silent fallback.

**Read carefully, that failure is about LOADING, not about EMITTING, and the distinction decides
this section.** v2's lane is a *run* lane: it generates a class for the user's program and loads it
in-process, which a closed-world image cannot do. A `translate` backend that writes a `.jar` to disk
only *writes bytes* — ordinary I/O, available in a native image like any other file write. So:

| | native image can emit? | native image can run in-process? |
|---|---|---|
| **A** bytecode → `.jar` on disk | **yes** — it is file I/O | no (the recorded `UnsupportedFeatureError`) |
| **C** Java source → in-process javac | **no** — `jdk.compiler` is absent (measured above) | no |

Arm C is therefore a full-JDK-only backend. Whether that disqualifies it is question **Q1**.

*Inference, flagged as such:* I did not build a native image to confirm `jdk.compiler` is absent from
one — the `--limit-modules` run is a proxy for the same condition, and the closed-world property that
produced the recorded `UnsupportedFeatureError` is the same one that would exclude javac.

## 6 · Recommendation

**Arm A, in the kernel, JDK-only, class-file major 50.** The reasons, in the order they carry weight:

1. **It is the only arm that is admissible where the charter says a backend lives.** I-1 names
   backends; A-in-kernel needs nothing, B needs a Scala compiler, C needs `jdk.compiler`.
2. **Its hardest part is measured away.** Major 50 removes `StackMapTable`, which is the reason
   people reach for ASM.
3. **Most of arm B's bulk is work v3 has already done.** 1,195 lines of CPS and 955 of Free-monad
   preamble answer questions `Cps.scala` and `TailCalls.scala` answer in 516 lines *for every
   backend at once*. Buying that a second time, in generated Scala, would be the same rule in two
   places — the failure mode `50-uniml-projection.md` already refuses for placeholder lambdas.
4. **It survives where the product ships.** Writing a jar works in a native image; both source arms
   do not.
5. **Source output is not lost by choosing it.** JS, Rust, Swift, Python and R are all source
   targets and all still come, in the owner's order — this is an argument about the JVM only, where
   a binary format is the *native* output and source is the detour.

**The plugin variant (arm A outside the kernel, with ASM) is not recommended, and the reason is not
purity.** It would work and it would be faster to write. But the JVM is the first target of six, it
is the one whose output format is a compiler's ordinary business, and taking a dependency for it
sets the precedent for the five that follow — each of which is *source* output and needs no library
at all. A kernel that has never had a dependency should not acquire its first one for the case that
needs it least.

## 7 · ANSWERED by the owner, 2026-09-01 — and he took the harder arm three times

The questions below were put as questions and are kept verbatim underneath their answers, because
what was asked is part of why the answer means what it does. **The owner did not take a single one
of my recommendations, and in each case he took the more expensive option.** That is not a note
about being wrong; it is the record of where the extra cost was bought deliberately.

| | asked | **decided** | my recommendation had been |
|---|---|---|---|
| **Q1** | what is the JVM target FOR? | **AOT `.jar` *and* an in-process run lane** | AOT only, at least first |
| **Q2** | closures without `invokedynamic`? | **major 51+, `invokedynamic`, write the `StackMapTable` computer** | start at major 50 |
| **Q3** | where does `Prim` land? | **v3 owns its own host layer for this target** | none — it was his to make |

**What the three answers cost, stated together, because they compound:**

- Q1 means the backend has **two consumers, not one** — a file writer and a class loader — and the
  in-process lane inherits the native-image refusal v2 already carries
  (`scripts/native-release-qualify:701`). That refusal must be honest and positioned from its first
  commit, not discovered by a user; the owner's own 2026-08-05 decision was refusal over silent
  fallback, and this lane is the same shape.
- Q2 means the `StackMapTable` computer is **on the critical path**, not an escape hatch. My
  estimate of 300–500 lines is still **not measured** and should be treated as a guess until the
  pass exists.
- Q3 means `Prim` is **its own slice of work**, not a call into the v2 fleet — so "the bridge
  retires for JVM" will mean v2 leaves the JVM story entirely, which is the stronger reading of
  stage 12 and the more expensive one.

**One thing gets cheaper, and it comes from this document's own probe.** Frames are only required
at *branch targets* — the first version of the probe passed at every version precisely because a
straight-line method has none. So even at major 51+, the stages below that emit no control flow need
no `StackMapTable` at all, and the computer is due exactly when §8 stage 3 lands. That is why the
staging survives Q2 unchanged rather than being reordered around it.

### The questions as they were asked

These are asked rather than defaulted because the evidence above does not settle them and because
the backlog entry sends this choice to the owner.

**Q1 · What is the JVM target FOR?** An AOT `translate` that writes a `.jar` you run with `java`,
or also an in-process run lane (`ssc3 run --jvm`)? The AOT reading is what makes major 50 safe and
what keeps the native binary able to emit. An in-process lane would be a second mechanism with the
native refusal v2 already carries.
*My recommendation was AOT only, at least first. **Overruled: both.***

**Q2 · Closures without `invokedynamic`.** Major 50 means `MkClos` emits one generated class per
lambda rather than an indy call site. The alternative is major 51+ and writing a `StackMapTable`
computer — a dataflow pass, my estimate 300–500 lines, **not measured**. Start at 50 and treat the
stack-map computer as the escape hatch if something forces 51?
*My recommendation was to start at 50. **Overruled: 51+ with the stack-map computer.***

**Q3 · Where does `Prim` land on the JVM target?** `Prim` is the host door. The produced jar can
call the existing v2 plugin fleet — which makes the *artifact* depend on v2, a dependency of the
OUTPUT rather than of the kernel — or v3 can own a host layer for this target. This is the question
that decides whether "the bridge retires for JVM" means v2 is gone from the JVM story or only from
the compile path.
*I had no recommendation. **Decided: v3 owns the host layer.***

## 8 · The staged plan — arm A, approved, with §7's answers applied

Each stage is its own claim, and none starts before the previous one's numbers are in. The
acceptance instrument throughout is the parity gate the backlog already prescribes: **the new
backend must match the bridge on the full corpus before the bridge stops serving this target.**

1. **DONE 2026-09-01 (`v3/src/JvmBackend.scala`, 258 lines).** `(40 + 2) * 7` emits a 351-byte
   major-52 class file that `java` loads and runs, printing 294; a second fixture chains
   sub/rem/shl/neg/bnot to 71. `v3/jvm-backend-gate.sh` gates both and its `--self-test` plants
   `lsub` for `ladd` — a defect that still VERIFIES, so only checking the ANSWER catches it — and
   requires red. Wired into `v3.yml` in the same commit. Straight-line `I64` only; everything else
   refuses by name at exit 1. The original text of this step: **The writer, and a gate that can
   fail.** Class file at **major 52** from a hand-built constant
   pool — 51 is the floor `invokedynamic` needs and 52 is the first version with no reason to prefer
   51 — plus a fixture that emits, runs, and compares against `ssc3 run`. Plant a wrong opcode and
   watch it go red before trusting it. **No `StackMapTable` yet, and that is not a shortcut:** this
   stage emits no branches, and the probe in §3 measured that a method with no jump targets verifies
   without frames at every version including 52.
2. **DONE 2026-09-02 (`v3/src/JvmBackend.scala`).** The straight-line instructions —
   `Const Move Un Bin MkData Field NewArr ArrGet ArrSet ArrLen GlobGet GlobSet`. Original text:
   *"…`Tag`… Measure N against the corpus after this stage; it will be small and that is the honest
   starting number."* Three things this stage settled that the plan did not know:
   - **The value representation changed, and it is the load-bearing decision.** Stage 1 kept every
     register as a raw `long` in two JVM slots; nothing holding a record or an array fits there.
     Every register is now ONE slot of `Ljava/lang/Object;` and a number is a `java.lang.Long`, at
     the cost of a box/unbox around every arithmetic instruction. **That price buys stage 3.** A
     `StackMapTable` is hard because it MERGES each local's verification type across the branches
     reaching a target; when every local is `java/lang/Object` in every branch, there is nothing to
     merge. Unboxing later is an optimisation with a number, not a correction.
   - **`Tag` moved to stage 3**, and the reason is a semantic one rather than a scheduling one.
     `Exec` makes `Tag` TOTAL — `-1` on a non-record, deliberately, because a nested pattern tests
     the tag of a field and a field is routinely not a record — so emitting it needs an `instanceof`
     and a branch, i.e. the first branch target, i.e. the frame computer stage 3 exists for. It also
     has no consumer before `Switch`, which is stage 3's.
   - **A record and an array are DIFFERENT JVM types on purpose** — `Object[]` with the tag at index
     0, and `java.util.ArrayList`. `Verify` checks index bounds and does not type registers
     (`v3/src/Verify.scala:107-131`), so nothing upstream stops a `Field` whose receiver is an
     array; the executor throws there, and one shared representation would have read element
     `idx+1` and answered a plausible wrong value instead. `NewArr` fills with `Long 0` to match the
     executor via `Collections.nCopies` — two calls, no loop, so no branch target enters this stage
     through the back door.

   **The first N, measured by `v3/jvm-backend-census.sh`:** see `v3/SPRINT.md` for the figure and
   the refusal histogram, which is what orders steps 3-6. **There is no second number and there
   cannot be one yet:** the obvious companion — "of those that emit, how many RUN to the same answer
   as `ssc3 run`" — needs a shared observable, and `run` prints through `io.println` while this
   backend's harness prints the entry's return value. `Prim` is step 6; the fixture gate becomes a
   differential there and not before.
3. **Control flow, and the `StackMapTable` computer with it** — `Block Loop If Br BrIf Switch Ret`,
   plus `Tag` and the comparison operators, which step 2 handed here because each needs a branch.
   §2 of the IR spec says the emission is one walk; if it is not, that is a finding about the IR and
   belongs back in `10-ssc-ir.md`. The frame computer arrives HERE because this is the first stage
   that creates a branch target, which is the only thing that requires one. Its own gate is a class
   that the verifier accepts and a deliberately wrong frame that it rejects — a computer whose
   output is never checked by the verifier is not a computer.
4. **Calls and closures** — `Call CallV MkClos TailCall Invoke`, Q2's answer applied.
5. **`Try`, then `Perform`/`Handle`/`Resume`** — post-`Cps` these are closure construction and
   dispatch, not a control-flow rewrite. `effects-gate.sh`'s 34 fixtures are the instrument.
6. **`Prim`, as a v3-owned host layer** (Q3) — its own SPI for this target, not a call into the v2
   fleet. Sized as its own slice; this is where "the bridge retires for JVM" becomes true or does
   not.
6b. **The in-process run lane** (Q1) — `ssc3 run --jvm`, sharing the emitter with `translate` so
   there is one code path and two consumers. Ships WITH its native refusal, named and positioned,
   because the closed-world image cannot load a class it did not see at build time.
7. **Parity against the bridge on the full corpus**, then and only then the bridge stops serving
   JVM.

A refusal must be an honest, positioned refusal at every stage — the module's own rule, and the
reason N is a number rather than an adjective (I-5).
