# ScalaScript 3 — sprint

Queue for the **`v3/`** module. Two states and no third: `[~]` in progress, `[x]` done.
Not-started work belongs in [`BACKLOG.md`](BACKLOG.md).

Design: [`specs/00-charter.md`](specs/00-charter.md) · [`specs/10-ssc-ir.md`](specs/10-ssc-ir.md).
Pipeline: `source → AST → SSC IR → execute | translate`.

**The order is deliberate: the IR is designed and verified before a front is written against it.**
A compiler built on an IR that turns out to be wrong is work thrown away twice.

## ssc3-core (claim `ssc3-core`)

- [x] **SSC3-0 — charter, IR spec, module.** `v3/` registered in `tests/fixtures/modules.tsv`;
      `specs/00-charter.md`, `10-ssc-ir.md`, `20-core-language.md`, `30-portable-subset.md`; boards.
      *Done when:* the four specs exist and the IR spec fixes the instruction set, the validation
      rules and the canonical text form.

- [x] **SSC3-1 — `Array` into the portable subset, fixed on every lane v3 targets.**
      Found by measurement while scoping SSC3-0 and blocking: the IR frame *is* an array.
      `new Array[Int](3).length` is `1` on both lanes today — [`../BUGS.md`](../BUGS.md) →
      `new-array-n-builds-a-one-element-array`, filed at the root because `lane: multi` was
      measured rather than assumed and the fix is not in `v3/`.
      Re-measure the whole inherited gap map (`specs/uniml-portable-gapmap.md`, 2026-07-13) before
      writing kernel sources against it.
      *Gate:* `v3/tests/array-floor.ssc` asserts length, indexed read/write past index 0, and
      `Array.fill`; run on int, js, jvm and v2. **Must be observed failing first** — record the red
      count in the commit (P-6.1). Compare output, never the exit code.

- [x] **SSC3-2 — IR core: data model, verifier, canonical text form.** In the portable subset.
      `Module`/`Func`/`Instr` per the spec; the verifier's five checks; `.ssir` reader and writer.
      *Gate:* `v3/selftest.sh` — round-trip as a property, opcode coverage in BOTH directions
      against a closed vocabulary, and a planted-defect suite of one malformed module per validation
      rule, each rejected naming the failing instruction path. Both halves observed FAILING before
      being trusted: blinding the register check turned rule 1 into `ACCEPTED — the verifier cannot
      see this defect`, and making the writer drop `NumKind` broke `read(write(m)) == m`.

- [x] **SSC3-3 — the v2 bridge: SSC3 IR → v2 Core IR (`V-0`).** RUNNING end to end for the
      straight-line + `If` + `Ret` + `Call` + `Prim` subset; every other instruction is refused BY
      NAME. Sergiy's call, and it comes BEFORE
      our own executor because it is what makes v3 usable at all: the whole v2 backend fleet — VM,
      JVM bytecode, JS, Rust, native — is inherited instead of re-earned.
      Raising a linear form into v2's term tree is only tractable because SSC3 IR is structured by
      construction; from basic blocks this step would be the relooper.
      `V-0` translates the register file as ONE mutable array with `Prim` get/set, so there is no
      SSA and no join points — mechanical and obviously correct, and slow. `Br` out of a `Block`
      becomes a tail call to the region's continuation, emitted as `LetRec`; each region's
      continuation is statically known, again because the form is structured.
      *Gate:* `v3/bridge-gate.sh` — each `v3/tests/bridge/*.ssir` is verified, translated, RUN on
      the v2 VM, and its OUTPUT compared against a checked-in expectation, never the exit code.
      Red in BOTH directions: a fixture using an untranslatable instruction must be refused with the
      instruction named, and the gate fails loud if no case ran.
      Still refused, each by name: `Loop`/`Br`/`BrIf` (needs each region's continuation as a
      `LetRec`), `Switch`, `MkData`/`Field`/`Tag`, the array and global instructions,
      `MkClos`/`CallV`, the effect trio, and the bitwise operators.

- [x] **SSC3-3b — the executor.** `ssc3 exec` runs SSC IR directly; 10 000 000 tail calls in
      constant stack, and every bridge fixture agrees byte for byte across both lanes.
      *Original entry:* The register VM: frame allocation, the instruction loop,
      structured branch handling, `TailCall` frame reuse, `Prim` dispatch. `ssc3 run-ir <file.ssir>`.
      **The bridge makes v3 usable; this makes v3 better than v2** — three properties the bridge
      cannot deliver, and they are why this is not optional: `TailCall` in constant stack (v2 has no
      TCO, which is why its launchers pass `-Xss512m`), serializable frames, and the `kind`
      specialization that v2's dynamic primitives discard.
      *Gate:* hand-written `.ssir` programs covering every opcode, expected output checked in;
      a tail-recursive loop of 10^7 iterations completing in constant stack is part of it, since
      that is the stability claim.

- [ ] **SSC3-3c — `V-1`: raise registers to `Let` bindings.** Each assignment becomes a fresh
      de Bruijn binding, with joins at the ends of `If`/`Loop` bodies expressed as lambda
      parameters. Strictly a performance follow-up to `V-0`, and only where a measurement says it
      pays — `V-0` is correct, and correct-and-slow is a shippable state that SSA-with-joins on day
      one is not.

- [x] **SSC3-3d — a clock prim, so v3 can be timed by the same harness as everything else.**
      DONE 2026-08-09. `nanoTime()` in the language, `io.nanoTime` as the prim — **v2's own name for
      it**, because the bridge emits prim names verbatim and a different one would run on the
      executor and be refused on the bridge. One prim, both lanes, no mapping and no refusal.
      Both constraints this entry named are met: `v3/tests/front/clock.ssc` CALLS the clock and
      prints only what a clock guarantees — that it does not go backwards, that it is positive, and
      that a difference is a number — so `exec-gate.sh` reports `clock -> true/true/true (both
      lanes agree)` rather than failing forever on a value that differs every run.
      v3 has no way to read a clock: the prim table is `io.println` plus collection operations. That
      is why the v3 bench column added on 2026-08-07 (`847707e98`) runs its rep loop DRIVER-SIDE in
      `ssc3 bench`, while every other column in `bench/run.sc` is timed by a wrapper written in
      ScalaScript that calls `System.nanoTime()` as an ordinary call. Same excluded compilation, same
      100 ms adaptive window — but v3 is not charged for executing its own rep counter and the others
      are, so the columns are not measured by one instrument.

      **This is what `Prim` is for, and my earlier note was wrong to imply otherwise.** The bench
      commit said adding a clock would "widen the kernel's host surface", but I-1 says the opposite
      in as many words: everything outside the kernel "reaches the language only through `Prim` and
      the plugin SPI". A clock is exactly such a thing, and routing it through `Prim` is the boundary
      working, not the boundary moving. What was true is narrower — it was out of scope for a bench
      column, and doing it inside that claim would have been the change nobody reviewed.

      Two constraints that are the actual work, not the prim itself:

      - **The differential gate compares OUTPUT.** `exec-gate.sh` runs every fixture on the executor
        and on the v2 bridge and requires identical output. A clock is the first prim whose result is
        different on every call, so any fixture that prints one directly destroys that property. The
        prim must be introduced with a rule about where it may appear — the honest one is that
        differential fixtures may CALL it but must not print it, and the gate should be able to say
        which fixture broke the rule rather than just going red.
      - **The bridge needs an answer.** `BridgeV2` lowers SSC IR to v2 Core IR; a prim v2 does not
        have must either map to v2's own clock or be refused by name with a message that says so.
        Silently emitting something v2 will not recognise is the failure mode to avoid.

      *Done when:* `bench/run.sc` measures v3 through the same in-language wrapper as the other
      columns, the asymmetry paragraph comes OUT of `bench/README.md` and out of `runV3Bench`'s
      comment rather than being restated, and a differential fixture exercising the prim is green on
      both lanes.

- [x] **SSC3-4 — the front.** RUNNING: `bin/ssc3 run <file.ssc>` compiles and executes real
      `.ssc` source through v3's own lexer, parser, typed AST, lowering, verifier and the v2
      bridge. The AST and the lowering are the halves that SURVIVE the UniML swap; only the
      interim parser is replaced.
      *Originally scoped as UniML-first:* Sergiy's call: v3 defines **no AST type of its own** — the
      UniML tree IS the AST, and lowering reads it directly. `specs/40-front-on-uniml.md`.
      UniML is ours, dependency-free, and already dual-compilable in exactly the subset I-2 asks
      for, so the parser lands inside the differential gate at no extra cost; spans, edge roles and
      `Origin` (source-backed vs synthetic) come with it.
      The ScalaScript grammar is still ours — precedence, indentation, every construct in
      `specs/20-core-language.md` §2 — and so are the lexer's own character tables, since the
      portable subset bans host `Char` classification.
      **First act: re-measure UniML's two gaps** (`Array`, anonymous `new Trait:`) rather than
      inherit them from a gap map written 2026-07-13.
      *Gate:* `ssc3 run` on a growing case set, every Tier 0 construct with one; plus an
      unknown-node-kind refusal, because an open `Branch(kind: String, …)` gives no exhaustiveness
      and a silent miss is the failure mode that buys.

- [x] **SSC3-5 — the driver, the honest number, the differential gate.** `v3/ssc3`,
      `v3/corpus-report.sh` (N = 18/355 on 2026-08-03, four buckets, CRASH 0), and
      `v3/exec-gate.sh` — 19 cases across BOTH lanes plus two constant-stack contrasts.
      *Original entry:*
      CLI: `run`, `ir` (print `.ssir`), `check` (verify only), `build --target`.
      `v3/corpus-report.sh` publishing `N/381` in the four buckets of `specs/20-core-language.md` §4,
      gated on non-regression of `N`.
      `v3/portable-diff.sh` — front built by scalac vs the same sources run on ScalaScript 2, IR
      compared byte for byte (invariant I-3).
      *Gate:* both harnesses observed failing on a planted defect before either is trusted.

Sergiy uses `bin/ssc3` from **SSC3-3** onward (hand-written IR) and for real programs from
**SSC3-4**. Everything before that is apparatus.

## SSC3-6 — breadth: walk the corpus blocker chain by MEASUREMENT

Not "add the constructs I think are missing". `corpus-report.sh`'s UNSUPPORTED histogram names the
top blocker, that one construct gets implemented, and the histogram is re-read — the blocker moves,
and the next one is usually not the one I would have guessed. `N` is the honest number throughout;
it may rise in any commit and fall in none (I-5).

The chain so far, each link measured, not predicted. Note how many links sit in ONE heavily-imported
std module (`v1/runtime/std/scljet/bytes.ssc`, imported by 116 cases): breadth on the corpus is
gated by depth on a few files, which is not visible without the histogram.

| link | blocker | cases |
|---|---|---|
| 1 | `def empty:` — a parameterless `def` | 116 |
| 2 | `++` on lists | 118 |
| 3 | `<<` `>>` `>>>` `&` `\|` `^` — bitwise operators | 116 |
| 4 | a continuation `else` on its own, deeper-indented line | 116 |
| 5 | a nested pattern — `case Right(ByteRead(v, _))` | 116 |
| 6 | `:+` — and with it every `:`-starting operator | 116 |
| 7 | **`trait`** — the wall: it needs dispatch, which needs a type checker | 137 |

Link 6 is the clearest argument for measuring. The bucket read `expected an expression, found :` at
126 cases, and the obvious reading — `effect X:`, which the corpus has plenty of — was wrong: 116 of
the 126 were ONE line, `acc.reverse :+ (value & 255L).toInt`. The lexer special-cased `::` and
nothing else, so `:+` split into punctuation and `+`. `effect` and `extension` together were 10.

After it, the histogram is no longer a chain but a WALL: `trait` at 137, then `[` (generics) at 36.
Both need a type checker, which is the next real decision rather than the next construct.

## SSC3-7 — the bench corpus, one task per MEASURED blocker

Measured 2026-08-07 against `bench/corpus` (36 files), after the first attempt at this list was
wrong twice over and both mistakes are the reason the list is shaped this way.

**Mistake one: the harness blamed the language.** `ssc3 bench` accepted only a zero-argument
`workload`, and 17 corpus files declare `def workload(seed: Long)` — the opaque input that stops a
pure body folding to a constant. Nine rows v3 compiles AND runs came back `n/a`, which reads from
the table as "v3 cannot run this program". `tests/e2e/bench-seed-type-gate.sh` exists because the
shared wrapper did this twice; this was the third time, on a new lane.

**Mistake two: compiling is not running.** A sweep with `ssc3 ir` said 23 of 36 compile, and that
number went into `bench/README.md` as if it were coverage. Eleven of those 23 do not execute. The
split below is what the two stages actually report.

| stage | count | meaning |
|---|---|---|
| runs | 19 | compiles, executes, produces a number |
| front declines | 13 | the parse or the name resolution stops it |
| executor declines | 4 | it compiles, and something is missing at run time |

Each row below is its own task on purpose: the blocker is a MEASURED diagnostic with a file and a
column, so each one can be verified by re-running exactly that file. Do not merge them on a hunch
that two share a cause — link 6 of SSC3-6 is the standing counter-example, where 116 of 126 cases in
one symptom bucket turned out to be a different construct than the obvious reading.

### Front — the parse or the name (13 files)

> **RE-MEASURED 2026-08-07, and none of SSC3-7a…7e is a front gap any more.** Every message quoted
> below is `v3/src/Parser.scala`'s, and v3's parser stopped being the default front when the UniML
> swap landed (`67ef6fd67`). Measured on the fixtures these items name, through both fronts:
>
> | item | fixture | v3 front | uniml front (the DEFAULT) | where it actually stops now |
> |---|---|---|---|---|
> | 7c | `bench/corpus/effect-pure.ssc` | refused | **parses** | verifier: `call to unknown function 'runLogger'` |
> | 7e | `bench/corpus/effect-stream.ssc` | refused | **parses** | verifier: `unknown name 'Stream'` |
> | 7a | `effect-oneshot.ssc` | refused | refused | `UniFront`: "`effect` is outside SSC3 core Tier 0" |
> | 7b | `effect-multishot.ssc` | refused | refused | same |
> | 7d | `typeclass-fold.ssc`, `typeclass-monoid.ssc` | refused | refused | `UniFront`: "`given … with` is outside SSC3 core Tier 0" |
>
> So they split into two kinds and neither is "grow the parser":
>
> - **7c and 7e are no longer parse problems at all.** 7c's text says "the `!` is parsed as far as
>   the return type and then the body is not reached" — the body IS reached now, and what is missing
>   is the effect RUNTIME. Both stop in the verifier on a name the program calls.
> - **7a, 7b and 7d are refused DELIBERATELY, and by the projection rather than the dialect.** The
>   UniML dialect parses all three; `v3/uniml/UniFront.scala` declines them by name because v3 does
>   not have the construct — "REFUSALS ARE THE POINT", as that file says, since an erased construct
>   lowers, runs, and prints something plausible. Closing one is a decision about what SSC3 core
>   admits, taken with `specs/20-core-language.md` §2's tiers, not a parser change.
>
> The fixture paths in the items are also stale: they are in `bench/corpus/`, not `v3/tests/front/`.
> Left below verbatim rather than rewritten — the measurements they carry were true when taken, and
> what changed is the front underneath them.

- [x] **SSC3-7a — `effect X:` declarations.** DONE 2026-08-08, end to end. `effect-oneshot` runs and
      agrees with the v1 interpreter on the value: **881 on both**. Corpus 29 -> 30.

      The chain: `effect Bump:` parses with `parseTrait` (a name, a `:`, body-less `def`s — the same
      shape), op ids are assigned in `Lower.programOf`, `Bump.tick()` becomes `Instr.Perform`, and
      `handle(body) { case tick(resume) => resume(7) }` becomes `Instr.Handle` with the arm's binders
      as `params` and its LAST binder as `k`, per the protocol decided in `10-ssc-ir.md` §3.

      **The body is lowered INSIDE the Handle block**, which is what installs the handler before it
      runs. That is also why `handle` cannot be an ordinary function: an ordinary call evaluates its
      argument first, and a perform in there would find no handler.

      **Three things this front cannot do, refused by name rather than guessed:**

      - an arm is resolved by the OPERATION NAME alone, because the pattern parser deliberately drops
        a qualifier (`C.Red` and `Red` are one constructor once an enum is split per case). Two
        effects declaring the same operation name are REFUSED — picking one would be a wrong answer;
      - an arm whose binders are not plain names is refused;
      - an arm with no continuation binder is refused, naming the shape it wants.

      The LAST binder is the continuation and the ones before it are the operation's arguments.
      Nothing else could state that, so the code does.

- [ ] **SSC3-7b — `multi effect X:` and continuation capture.** DESIGN DECIDED 2026-08-08, written
      into `10-ssc-ir.md` §3 "Capturing a continuation"; implementation is the open part.

      **`k` is a closure the LOWERING builds, not a machine the executor reifies.** The alternative
      was measured first: `Exec.scala` is 1363 lines of STRUCTURED host recursion — eight recursive
      `exec` sites, `Block`/`Loop`/`If`/`Switch` nested across host frames — so "the rest of the
      computation" is a position in a tree plus every enclosing region, living on the host's stack.
      Making that copyable is a rewrite of the largest file in the kernel.

      v3 has had `MkClos`/`CallV`/`VClos` from the start, and a continuation that is a closure is
      **multi-shot for free**: a closure may be called zero, one or many times and is not consumed by
      being called. Multi-shot stops being a second feature after one-shot.

      **The cost is real and named:** functions that can perform must be lowered in CPS, and the IR
      is structured, so a `Loop` containing a `Perform` becomes a recursive function. `TailCall`
      keeps that constant-stack. The transformation applies only to functions that TRANSITIVELY
      perform — two programs in today's corpus.

      **The executor's tail-resumptive path stays.** It is what a CPS-converted arm reduces to when
      the arm resumes once as its last act, so it becomes an optimisation rather than the only thing
      that works — and the structural refusal it carries stays honest until CPS lands.

      **Step 1 DONE 2026-08-08.** `Perform.performing(m: Module)` computes the transitively-performing
      set, and `ssc3 performs <file>` prints it so the step is checkable on its own rather than only
      when CPS lands on top of it. `effect-oneshot` gives `loop` (performs directly) and `workload`
      (reaches it through a call); a program with no effects gives nothing.

      ONE definition, on the IR — `Instr.Perform` and `Instr.Call` are unambiguous there, while the
      AST has two spellings of a call and an op id resolved late. Two implementations of one notion
      is the trap this repository keeps paying for.

      Conservative in the direction that costs speed (a `Perform` inside the function's own `handle`
      still counts, since deciding otherwise means matching op ids through nested handles and being
      wrong loses a continuation silently) and under-approximating in the one place it cannot help
      (a call through a VALUE contributes nothing, the same limit the `gapNames` walk records).

      **Step 2's design decided 2026-08-08**, in `10-ssc-ir.md` §3 "Who PRODUCES the continuation".
      The protocol said where `k` goes and not where it comes from; the executor has been writing
      `unit` there since the tail-resumptive path was written, because that path never reads it.

      **The LOWERING produces it, as `Perform`'s LAST ARGUMENT.** An arm binds `params.length` values
      from the front and `k` from the one after — the same rule its own binders already follow, so
      it is one convention stated twice rather than two conventions. A function that has not been
      converted emits `perform` with the operation's arguments alone and `k` stays `unit`, which is
      exactly today's behaviour, so the two coexist without a mode flag.

      Splitting is what makes "the rest of a function" a function: a `Perform` at position *i* keeps
      everything before it and turns everything after into a new function taking the resumed value
      and the registers still live there. **That is also why step 3 is a step and not a detail** — a
      perform inside a `Loop` cannot split that way, because a loop's remainder is not a suffix of an
      instruction list.

      **Step 2 DONE 2026-08-09.** `Cps.split` — a Module→Module pass, like `TailCalls`, so nothing
      about it knows what a `.ssc` is. A `Perform` at position i divides the body: what comes before
      stays, `MkClos k, f$k, <captures>` is built just before it, the continuation goes in as
      `Perform`'s LAST argument, `f` ends with `Ret d` (what the handler returns IS the answer), and
      what came after becomes `f$k`.

      **Every register is captured**, not a computed live set — with all of them captured the
      continuation's parameters are `0 .. nregs-1` in order and the moved instructions need NO
      renaming. A live-range analysis captures fewer and must renumber, which is a second thing to
      get wrong while the first is unproven. It costs closure size, never correctness.

      Checkable on its own via `ssc3 cps <file>`, and checked three ways: the split module VERIFIES
      (a split that moves a register without saying so fails rule 1 there), it round-trips through
      `fmt`, and on a program with no effects the pass is byte-identical to no pass at all — so
      nothing pays for effects it does not use.

      Still only the TOP LEVEL of a body: a `Perform` inside a `Loop`/`If`/`Switch` is left alone,
      which is step 3 and is a step rather than a detail because a region's remainder is not a suffix
      of an instruction list. And it changes nothing yet — the executor's tail-resumptive path still
      runs unconverted functions unchanged, and wiring the pass in is step 4.

      **Steps 4 and 5 DONE 2026-08-09 — MULTI-SHOT CONTINUATIONS RUN.**

          effect E: def op(): Int
          def f(): Int = val a = E.op(); a + 1
          handle(f()) { case op(k) => k(1) + k(10) }     ->  13

      `(1+1) + (10+1)`: the handler resumed the SAME continuation twice with different values and
      the rest of `f` ran again each time. Zero-shot works too — an arm that never resumes returns
      its own value — and neither needed a second mechanism, because resuming is calling a closure
      and a closure is not consumed by being called.

      `Perform` takes the CPS path when it carries one argument more than the arm binds; the extra
      one is the continuation. Otherwise it takes the unconverted path unchanged, so the
      tail-resumptive fast path and its structural refusal are still there for functions the split
      did not touch — measured: the earlier fixture still gives 21 and `effect-oneshot` still 881.

      The arm now ENDS WITH A `Ret`, so its value has one place to come from rather than a register
      the executor would have to guess. That change made `tailResumptive` reject every handler it
      used to accept, because it asks "is the LAST act a resume" — fixed by skipping a trailing
      `Ret`, which is the shape it was really asking about.

      `Cps` runs BEFORE `TailCalls` in the pipeline: splitting introduces a `Ret` and a new
      function, and tail-call detection should see the final shape. Corpus unchanged at 31 of 36
      after wiring, so nothing pays for effects it does not use.

      *Order of work, each step gateable on its own:* (1) compute the transitively-performing set;
      (2) CPS-convert a performing function with no loop; (3) loops to recursive functions;
      (4) `Perform` builds the `VClos` and `Resume` calls it; (5) drop the tail-resumptive refusal
      for the cases CPS now covers, and let `effect-multishot` run.

- [x] **SSC3-7c — `!` effect types in a signature.** DONE 2026-08-08. `skipType` continues past `!`
      exactly as it already did past `=>`, with the same safety argument: every caller is a
      DECLARATION position, never expression position, so consuming a `!` cannot eat a prefix
      negation. Without it the type ended at `Int`, `! Logger` was read as an expression, and the
      file failed pointing at the `=` that follows — a diagnostic about a `def`'s BODY whose real
      problem was its return type.

- [x] **SSC3-7d — `given name: T with`.** `typeclass-fold.ssc:15:13` (`given intSum: Monoid[Int] with`)
      and `typeclass-monoid.ssc:10:16` (`given intMonoid: IntMonoid with`) →
      `expected an expression, found :`. Tier 2 in `specs/20-core-language.md §2`; queued here with
      the measurement rather than left implicit in that deferral.
      **DONE 2026-08-09 as §52's G1** — the DECLARATION only, on both fronts, as a named object.
      Of the two rows this entry names, it turns `typeclass-monoid` (`VInt(6)`, checked as 0+1+2+3
      against the source). `typeclass-fold` is NOT turned and was never G1's to turn: it reaches
      its instance through `summon[Monoid[A]]` inside a function generic in `A`, which is the
      type-directed question — §52's G2, queued as mandatory work.
- [x] **SSC3-7e — a block argument, `f { … }`.** DONE 2026-08-08. `.map { x => … }` had worked; the
      same form on a bare name (`runLogger { … }`) or after an argument list (`handle(e) { … }`) had
      not. SAME LINE only — the `(` case's lesson, not decoration, since a block body ends by
      consuming its DEDENT. A bare name builds a `Call`, not an `Apply`: `f(x)` already produces
      `Call` and the lowering resolves the function there, so `Apply(Name("take"), …)` arrived as an
      unbound NAME and said `unknown name 'take'` for a function defined three lines up.

      **It does not finish the two rows it came from.** `f { … }` passes the block as an ORDINARY,
      EAGERLY EVALUATED argument, because v3 has no by-name parameters — measured:
      `def once(x: => Int)` fails with `expected a name, found =>`. So `runLogger { body }` evaluates
      `body` BEFORE the handler is installed and a perform inside finds no handler. See SSC3-7s.

- [x] **SSC3-7s — by-name parameters, `def f(x: => A)`.** DONE 2026-08-08, as a FRONT
      transformation — the way Scala does it, and the way that costs the IR, the executor and the
      bridge nothing. The call site wraps the argument in a zero-argument lambda; each use of the
      parameter in the body calls it. Both halves already existed: `val f = () => 41; f()` has always
      worked, so this is a rewrite, not a new capability.

      **Verified by OBSERVABLE EFFECT, not by parsing.** With `var n = 0` and a `bump()` that
      increments it:

          def twice(x: => Int) = x + x    twice(bump())  ->  3    (called twice: 1 + 2)
          def once(x: Int)     = x + x    once(bump())   ->  2    (called once:  1 + 1)

      A parse-only check could not tell those apart, and "it compiles" is what a wrong by-name
      implementation also does.

      Applied where `allDefs` is BOUND, so every consumer downstream — the gap check,
      `zeroArityNames`, the lowering — sees the rewritten program and none of them knows this feature
      exists. Threading a second list is how one consumer ends up reading the un-rewritten version.

      **Two limits, both stated rather than discovered.** A call site is rewritten only when the
      callee is a statically known `def`; a call THROUGH A VALUE cannot be, because knowing whether
      that value's parameter is by-name needs a type and Tier 0 has none. And a by-name parameter
      SHADOWED by a lambda parameter of the same name is REFUSED BY NAME
      (`v3/tests/effects/by-name-shadowed.ssc`) — rewriting inside the shadow would read a value from
      the wrong binding, which is a wrong answer rather than a missing feature.

- [x] **SSC3-7f — `Either` / `Right` / `Left`.** DONE, in two halves by two agents. `f1a82c9b8`
      (a sibling) put `Right`/`Left` in the constructor table, which made them CONSTRUCTIBLE; nothing
      could then be done with the value, so `either-chain` advanced one step and stopped at
      `method 'map' on #6(3) is not implemented`. The executor half — `map`, `flatMap`, `fold`,
      `isRight`/`isLeft`, `getOrElse` — is right-biased as Scala is, and `flatMap` returns the
      function's result AS IS rather than re-wrapping it, which in an untyped executor would
      silently build `Right(Right(x))`. Verified by DIFFERENTIAL: `663` on v3's executor, on the v2
      bridge and on the v1 interpreter. `type-lambda-placeholder`, the second file this task named,
      now stops at `unknown name 'type'` — a type alias, filed as SSC3-7q.

- [x] **SSC3-7q — `type` aliases.** DONE. Consumed and DISCARDED, which is the whole of it: types
      are erased at Tier 0, which is why `skipType` exists and why `asInstanceOf` is the identity in
      the executor. An alias names a type; with no types at run time it names nothing, and every USE
      of it was already skipped by `skipTypeAnn`.

      Third occurrence of one pattern, after `sealed` and `extern` directly above it in the same
      loop: an unrecognised leading word falls through to the EXPRESSION parser, becomes a top-level
      statement reading an unbound name, and the file dies with a message about the keyword —
      `unknown name 'type'` — pointing at a line whose content is a type.

      The `=` is part of the TEST rather than only of the consumption: a branch matching on `type`
      alone would have to leave the tokens untouched when no `=` followed, and the top-level loop
      would spin on them forever. `type F[A] = …` counts brackets, since they nest in `type M[F[_]]`.

      Verified DIFFERENTIALLY: `482` on the executor and the bridge. Corpus 25 -> 26. **Negative
      control:** `type-lambda-native` is still refused, so this does not silently claim SSC3-7i —
      and its diagnostic improved on the way, from `expected an expression, found [` to
      `expected a name, found [`, which points at the type rather than at the statement.

- [x] **SSC3-7g — `until`.** DONE. `range-sum` runs and agrees with the v1 interpreter on the
      value (`2425500` on both), which is the check that matters — not merely that it stopped
      failing.
- [x] **SSC3-7h — `to`.** DONE as an OPERATOR, and the file it came from still does not run. They
      were one fix, as suspected: alphanumeric identifiers are now infix operators generally, so
      `to` and `until` both parse. `streams-pipeline.ssc` then moved to a different blocker at
      `10:5` — see SSC3-7p. Filing them apart is what made that visible; a merged "ranges" task
      would have been ticked on `range-sum` alone and quietly carried the second file's real gap.

      *How it was done, since the shape is reusable:* an alphanumeric operator is a method call —
      `a to b` IS `a.to(b)` — so the parser gained a `TId` case in the operator loop, the lowering
      routes any letter-initial operator to `Invoke` exactly as `++` already did, and only `Exec`
      knows what `to`/`until` mean. Precedence rows shifted 1..8 → 2..9 because Scala puts
      alphanumeric operators BELOW every symbolic one and there is no integer between 0 and 1.

- [x] **SSC3-7p — a leading-dot method chain on continuation lines.** DONE. The `.` branch of the
      postfix loop wanted the dot as the IMMEDIATELY next token, so layout ended the chain.

      Layout is consumed ONLY when a `.` actually follows it, which is what keeps this from
      repeating the `(` case's mistake in a new place: there is nothing to guess, because no
      statement in this language begins with a dot. INDENTs crossed are counted and their DEDENTs
      given back when the chain ends — the same bookkeeping `parseBin` does after a trailing
      operator, and for the same reason: an unmatched DEDENT ends the enclosing block one statement
      early.

      **The file behind it still does not run**, exactly as this entry predicted when it was
      written: `streams-pipeline.ssc:9:4` now says `unknown name 'Bench'`. Filed as SSC3-7r. Corpus
      stays at 28 — the parser gap is closed and the row is not, and those are two different facts.

- [x] **SSC3-7r — `Bench.opaque`.** DONE, as the IDENTITY, and that is the honest implementation
      rather than a shortcut. Its whole job is to stop an OPTIMISER proving the surrounding
      expression constant — `std::hint::black_box` on Rust, an ordinary identity call on JVM/JS/interp.
      v3 walks IR and folds nothing, so there is nothing to defeat; the same reasoning makes
      `asInstanceOf` the identity in the executor. `streams-pipeline` runs: `36`, which is
      `(1 to 10).map(_*2).filter(_%3==0).sum` = 6+12+18 by hand. Corpus 28 -> 29.

      **One consequence, stated:** the v2 bridge inherits the erasure, so a program measured through
      the bridge loses the barrier. Nothing measures that lane today — `bench/run.sc` has no
      v3-bridge column — but a future one would need the barrier emitted rather than erased.

- [x] **SSC3-7i — type lambdas, `[A] =>> …`.** `type-lambda-native.ssc:12:13` —
      `type Pair = [A] =>> (A, A)` → `expected an expression, found [`. Behind the generics wall
      SSC3-6 already names (`[` at 36 cases), so this is gated on the type checker decision.
      **DONE 2026-08-09, and the gating was WRONG — it is three lines in `skipType`.** A type
      lambda is a TYPE, Tier 0 discards types, and once `type Pair = [A] =>> (A, A)` and
      `val p: Pair[Long] = …` are both discarded what is left is an ordinary tuple. No checker, no
      generics.
      **What said so was `front-capability-gate.sh`, not a hunch.** `type-lambda-native` was the
      one row in its `KNOWN_UNIML_ONLY` list — UniML had accepted this file all along — and a
      construct one front takes at Tier 0 is expressible at Tier 0. The list is now EMPTY in both
      directions: over 50 programs the two fronts accept and refuse exactly the same set.
      `skipType` now takes a leading `[…]` when `=>>` follows, and REFUSES it otherwise, so a type
      that merely starts with a bracket is not silently swallowed.
      Measured: `type-lambda.ssc` prints 15 and 3, hand-computed (20%7=6, 20%11=9); the bench row
      runs at `VInt(10)`.
- [x] **SSC3-7j — `Vector`.** DONE, sharing `Array`'s representation, and the choice is not
      cosmetic. Vector is an INDEXED sequence; lowering it to a list — the obvious alternative, since
      `Seq` already goes there — would make `v(i)` a traversal, and `vector-index.ssc` exists
      precisely to measure indexed access. v3's column would then have reported list-walking under
      the name "vector", beside seven columns reporting indexing.

      **What it gives up, stated rather than discovered later:** a Scala `Vector` is immutable and
      `VArr` is not, so `v(i) = x` is accepted here and rejected by Scala. At Tier 0 types are erased
      and `asInstanceOf` is already the identity, so this is the tier's existing bargain rather than
      a new one — but it IS a difference, and a type checker will have to take it back.

      Verified DIFFERENTIALLY: `4764780` on the executor and the bridge. Corpus 26 -> 27.

- [x] **SSC3-7k — `LazyList`.** DONE, and REALLY lazy — a cons-thunk, not a materialised prefix.
      `LazyList.from(n)` is infinite and the corpus row maps over the whole thing before taking 8, so
      the representation has to be able to be infinite. The cheap alternative — build a generous
      prefix and call it a LazyList — passes that exact row and is a lie the moment a `filter`
      appears. The control says so: `from(0).filter(_ > 1000000).take(2).toList` returns
      `List(1000001, 1000002)`; a prefix of any fixed size returns empty.

      **A fold that cannot finish REFUSES BY NAME rather than hanging** — `LazyList.from(0).sum`
      says "walked 10000000 elements without reaching the end … `take(n)` first". Hanging is the
      worst of the three possible behaviours, worse than a wrong answer, because nothing says
      anything at all. Same reasoning as the `until` range guard.

      **Not memoised**, unlike Scala's LazyList: traversing one twice recomputes it. Invisible for
      the pure functions Tier 0 has, and not once effects arrive. Written down because the NAME
      promises memoisation to anyone who knows the Scala type.

      Corpus 27 -> 28. **Executor only:** the bridge crashes with a raw Java stack trace
      (`no dispatch for .filter on <closure>`) — filed as
      `BUGS.md v3-bridge-lazylist-crashes-with-a-java-stack-trace`, since `BridgeV2.scala` was
      outside this claim.


### Executor — it compiles, and then it stops (4 files)

- [x] **SSC3-7l — `<` on Double.** DONE, and the measurement the task demanded chose the file.
      The constant pool holds `(float 0.0)`, so the lowering was right: `Lt`/`Le`/`Gt`/`Ge` simply
      had no `VFloat` arms, while `Add`/`Sub`/`Mul`/`Div` had had them since they were written.
      Doubles could be added and not compared. Verified DIFFERENTIALLY: `499999500000` on v3's
      executor, the v2 bridge and the v1 interpreter. Corpus 22 -> 23.

      *The diagnostic was fixed too, because it caused the wrong first guess.* `show` renders a
      Double the way the reference lane does, so `0.0` prints as `0` and the failure read
      `Lt on 0 and 1000000` — an Int problem, by the look of it. An operator arm is missing for a
      pair of TYPES, so the message now names them: `Lt on String x and Int 3`.

- [x] **SSC3-7m — `Map.updated`.** DONE. It COPIES: `VMap` wraps a mutable `ArrayBuffer`, and the
      one-line version that writes in place passes `map-ops` and is wrong — `val b = a.updated(k, v)`
      must leave `a` alone. No corpus row would have caught it, because no corpus row keeps the old
      map, so the control is a written one: `a.getOrElse(1,0)` still `10` after `a.updated(1,99)`.
      Verified DIFFERENTIALLY: `124750` on the executor and the bridge alike.

- [x] **SSC3-7n — `Option.flatMap`.** DONE. `map` was there and `flatMap` was not, one line apart
      in the same dispatch block. Returns the function's result AS IS: re-wrapping would build
      `Some(Some(x))`, which then reads as a present value at every later `isDefined` — the same
      trap the Either case documents, and the two now sit next to each other so the contrast with
      `map` is visible. Verified DIFFERENTIALLY: `138` on v3's executor, the v2 bridge and the v1
      interpreter. Corpus 21 -> 22.
- [x] **SSC3-7o — `++` on a tuple.** DONE. `(a, b) ++ (c, d)` is `(a, b, c, d)`; tuples are
      synthetic `TupleN` case classes and the lowering already pre-registers `Tuple2`..`Tuple8`
      wherever a `_n` accessor appears, so the widened type exists by the time the executor needs it.
      `t == tagOf(m, "Tuple" + f.length)` answers "is this a tuple" without a second table to keep
      in sync.

      **AND IT FOUND A BUG IN THE OTHER LANE.** The differential disagrees: `401280` on the executor,
      `0` plus a hundred thousand `Stub`s on the bridge — which does NOT fail, it returns. Filed as
      `BUGS.md v3-bridge-tuple-concat-emits-Stub`; pre-existing (identical with this fix stashed),
      and invisible until now because the executor used to refuse the program first.

**All four executor gaps are closed: corpus 19 -> 25 running.** What remains is 11 files, all
declined by the FRONT.

*Verifying any one of these:* `v3/ssc3 bench --warmup 0 --reps 1 bench/corpus/<file>.ssc` must print
a `BENCH_MS:` line instead of the quoted diagnostic, and `./bench.sh --backends v3 <file>` must show
a number instead of `n/a`.

### Defects found and fixed along the way

- [x] **A parse error inside an IMPORTED unit named the ROOT file.** `std-index.ssc:35:1: trait is
      outside…` where line 35 of that file holds a `println`: the imported unit's line number with
      the importer's path, pointing at a line that has nothing to do with the error, in a file the
      reader did not write. `Loader.closure` now attributes to the unit it is parsing.
- [x] **A continuation `else` was a parse error.** `val v = if c then a` / `else b` on the next line
      indented deeper emits an INDENT before `else`, and `parseIf` skipped only newlines. Fixed by
      consuming the layout ONLY when `else` actually follows, and taking the matching DEDENT — which
      arrives BEHIND that line's newline, so a check on the first token alone removes nothing and the
      enclosing block ends one statement early. Both halves were needed; the first alone compiled and
      lost the binding.
- [x] **A missing input file crashed with a raw Java stack trace.** The one diagnostic in the driver
      that was not `ssc3: …`. It also mattered beyond tidiness: `corpus-report.sh` classifies a stack
      trace as CRASH, so an unreadable path would have been counted as a v3 defect.
- [x] **The bridge gate's refusal probe had gone stale.** It asserted the bridge REFUSES `globget`;
      globals became translatable, so the check went red — the gate working, not failing. Re-pointed
      at `resume`, which the bridge genuinely cannot translate. `.ssir` gained `;` line comments so a
      fixture can say which behaviour it pins (`Text.write` never emits them, so `fmt` strips them).
- [x] **`Tag` was PARTIAL, and the two lanes disagreed about it.** Testing the tag of a value that
      is not `Data` threw on the executor and had no defined answer on the bridge. Harmless while
      patterns were flat; a nested pattern tests the tag of a FIELD, and a field is routinely not a
      constructor — `Right(42)` against `case Right(ByteRead(v, _))` is an ordinary non-match in
      Scala and was a crash here. `Tag` is now TOTAL on both lanes, yielding -1, which no type index
      can equal. Observed: with the throw reinstated the gate goes red naming both lanes —
      `executor [7/107/-2] bridge [7/107/-2/-1/102/9/0]` — so it can tell the two states apart.
- [x] **The two lanes printed Doubles DIFFERENTLY, and the gate could not see it.** The executor
      printed `3.0`, `-0.0`, `123456789.0`; v2 prints `3`, `0`, `123456789`. Invisible because no
      fixture printed a whole-number Double — a differential gate is only differential over what it
      runs. Cause: the executor printed through `Text.floatText`, the CANONICAL `.ssir` form, which
      is a different contract from a program's output; the two are now separate functions. Direction
      chosen by measurement, not taste: `ssc3 run` goes through v2 and the corpus expectations are
      the ones every lane is held to, so the executor matches the reference lane. Real Scala prints
      `3.0`, so this is v1-parity behaviour — if the repository ever changes it, v3 inherits it.
- [x] **`toInt` was identity, and should truncate to 32 bits.** `5000000000L.toInt` is `705032704`
      in Scala and in v2; the executor returned the value unchanged. Assumed because ScalaScript's
      `Int` is 64-bit — true, and not what `toInt` means. Caught by running BOTH lanes on the same
      program instead of reasoning about one.
- [x] **`++` was parsed right-associative.** It ends in `+`, so Scala parses it left. Invisible
      because concatenation is associative — the latent kind, which surfaces on the first operator
      where it is not. Associativity now follows Scala's rule (right iff the operator ends in `:`)
      and precedence follows the FIRST character, which is what made `:+` parseable at all.
- [x] **A contended host made one corpus run a hypothesis.** A background report run CONCURRENTLY
      with three gates that package the same sources reported `CRASH 360, N = 0` — a total
      regression. Re-measured alone: `N = 26, CRASH 0`, unchanged. Corpus runs take the host alone.

## SSC3-7 — make v3 WRITABLE, not just measurable

Sergiy's call, 2026-08-04: optimise for him being able to write programs in v3, not for `N` against
the v1 corpus. The two genuinely diverge — the corpus wall is `trait` and generics, which are
library-author features, while what stops you writing an ordinary program is tuples and `for`.
`N` stays the honesty metric and will move more slowly; that is the trade, stated up front.

Baseline MEASURED 2026-08-04, one probe per construct, before any of it was written:

| construct | state |
|---|---|
| `(a, b)` literal, `._1`, `case (a, b)`, `val (x, y) =` | all four refuse |
| `for x <- xs do` / `yield` | both refuse |
| `case n if n > 0 =>` | refuses |
| `Array(1,2,3)`, `a(i)`, `a(i) = v` | all three refuse — the IR HAS arrays, the front has no syntax |
| `xs.foreach` | LANE DIVERGENCE: the bridge runs it, the executor has no such method |
| `'x'` | refuses |

- [x] **7a — tuples.** Literal, `._1`/`._2`/…, `case (a, b) =>`, and `val (x, y) = e`. Lowered to
      the same `MkData` a `case class` uses, so patterns and field reads need no new IR.
- [x] **7b — pattern guards.** `case p if cond =>`. One field on `MatchArm` and one test at the base
      case of `testPat`, which is already the single place every arm kind converges.
- [x] **7c — `foreach` on the executor.** A one-line lane divergence, and the gate did not see it
      because no fixture used `foreach`. Fixture first, then the fix.
- [x] **7d — `for` comprehensions.** `for x <- xs do e` and `for x <- xs yield e`, desugared in the
      parser to `foreach`/`map`. Multiple generators and `if` guards only if measured worth it.
- [x] **7e — `Array` syntax.** `Array(…)`, `a(i)`, `a(i) = v`, `a.length`. The IR instructions
      (`NewArr`/`ArrGet`/`ArrSet`/`ArrLen`) exist and are exercised by the frame itself; this is
      front work only.

**`Char` is DEFERRED, and not because it is hard.** Measured: `"hello".charAt(1)` prints `101` on
the v2 lane — the reference lane has no `Char` type and yields the code point as an integer. Adding
a real `Char` to v3 would make its two lanes disagree on every program that touches one, which is
invariant I-3. That makes it a language decision for the repository, not a construct for this
sprint, and the honest thing is to say so rather than to implement half of it.

### Defects found while making it writable

- [x] **Every constructed value printed DIFFERENTLY on the two lanes.** Executor `#0(1, 2)`, v2
      `P(1, 2)`; a list as its nested Cons cells rather than `List(1, 2)`. That is every program
      that prints a data value, and the differential gate could not see it because no fixture
      printed one. The type names were there all along — in the module the printer did not have.
      Split into `show` (raw tags, for the executor's own diagnostics) and `showV(m, v)` (what
      reaches the user), threaded into `println`, auto-output, `throw`, `toString` and string `+`.
- [x] **`=>` and `<-` became binary OPERATORS.** Fallout from keying precedence on the first
      character: `=>` starts with `=` and got precedence 4, `<-` starts with `<` and got 5. The
      exact-string table simply had no row for them. Latent until pattern guards became the first
      place an expression is parsed immediately before a `=>` — everywhere else `=>` is consumed by
      `expectOp` or claimed by `lambdaAhead` first.
- [x] **A type annotation could only be a NAME.** `(Int, Int)` and `Int => Int` both failed, and the
      diagnostic blamed the token rather than saying types were the limit. Fixing it for tuple
      signatures fixed FUNCTION-TYPED PARAMETERS too — `def sum(f: Int => Int, n: Int)` had never
      parsed, which is a daily-use shape nobody had measured.
- [x] **My own grep was blind to a failed compile.** `grep -E '^-- \[E'` never matched because
      scala-cli colours the marker: the ANSI escape sits before `--`. Two type errors read as
      "compiled". A check that cannot fail is not a check.

**Tuples are the same `MkData` a `case class` uses.** Nothing else learned what a tuple is:
construction, `t._1` through the existing field-by-name `Switch`, `case (a, b)` as a constructor
pattern, one arm in `showV`. The representation is v2's own (`DataV("Tuple2", …)`), so the bridge
builds a real v2 tuple rather than a lookalike. Verified THREE ways — executor, bridge and the v1
interpreter produce byte-identical output for the whole fixture.

### Defects found while adding `for` and arrays

- [x] **A lambda nested inside a lambda got its ENCLOSING lambda's index.** The lifted function's
      index was taken BEFORE the body was lowered, and the body lifts its own lambdas onto the same
      list — so the inner one appended first and took the number, and the outer's `MkClos` pointed
      at the inner function. Calling it passed the outer's argument count to the inner's arity:
      `__lam2 takes 2 argument(s), given 1`. It hit EVERY closure nested inside a capturing closure,
      which is exactly what a `for` with two generators desugars to. Both lanes failed IDENTICALLY,
      so the differential gate could not see it — a reminder that agreement is not correctness.
- [x] **`1 + "x"` threw on the executor.** String concatenation was implemented for a string on the
      LEFT only. `p._1 + p._2` over a mixed tuple printed on one lane and failed on the other.
- [x] **A `for … do` body could not be a STATEMENT.** `for i <- xs do total = total + a(i)` — the
      ordinary shape of an imperative loop — parsed the body as an expression and blamed the `=`.

**Arrays print `<foreign>`, deliberately.** Both reference lanes print that: an array is a host
object to v1 and v2 and they say so. Printing the contents would read better and would make v3's
two lanes disagree on every program that prints an array, which is invariant I-3. The executor's own
diagnostics still show the contents, because they are not the language's output.

## SSC3-8 — methods, traits, and dispatch WITHOUT a type checker

The refusal `trait` used to carry — "it needs dispatch, which needs a type checker" — was my claim,
and measuring it showed it was too strong. v3's dispatch has been dynamic from the start: `Invoke`
asks the value, and the field-by-name `Switch` already picks an arm by the receiver's TAG at run
time. A `trait` needs exactly that and nothing more. Only `given`/`using` genuinely needs types,
and it stays refused.

- [x] **8a — `case class` bodies.** Methods become ordinary top-level `C.m` functions taking the
      receiver first, the same flattening `object` members get. The body is prefixed with a `val`
      per field, so an unqualified `x` inside a method means `this.x` and a later local `val x`
      shadows it by ordinary scoping rather than by a special rule.
- [x] **8b — `trait`.** Abstract members make the NAME known; concrete ones are inherited by every
      class that extends the trait, transitively, with the subclass's own definition winning.
      `override` and `final` are accepted and carry no meaning at Tier 0.
- [x] **8c — dispatch.** `recv.m(args)` becomes a `Switch` with an arm per declaring class calling
      `C.m` directly, and a DEFAULT that falls back to `Invoke`. No new IR, and it works on both
      lanes because the arms are ordinary calls rather than a v2 method table v3 cannot populate.
- [x] **8d — self-calls.** Inside a method, an unqualified call to a SIBLING method means
      `this.that(…)`. Rewritten on the AST while methods are being flattened, because by lowering
      time a method is an ordinary top-level function with no memory of its class.

**v3 now accepts a program v1 REJECTS.** A trait's concrete member inherited by a subclass fails on
the v1 interpreter with `__method__: no dispatch for .describe on Sq(3)`; v3 runs it on both of its
lanes. That is not a divergence in the sense invariant I-3 forbids — I-3 is about v3's two lanes
agreeing with each other, and they do, byte for byte. It is the same safe direction the identifier
alphabet took: accepting more than the reference does cannot change the meaning of a program the
reference accepts.

`trait` was 137 of 333 refusals. After this it is zero.

- [x] **8e — `case object`.** A NULLARY CONSTRUCTOR, the same thing an enum's `case Red` produces —
      not an `object`, which at Tier 0 is a namespace. Measured: 116 of the 123 cases in the top
      refusal bucket were one line, `case object SqlNull extends SqliteValue`, in one imported
      module. Twice in a row now the bucket's label named a construct the corpus barely used and
      hid a single line that 116 files reach through an import.
- [x] **8f — `{ case (k, v) => … }` as a LAMBDA.** How a destructuring callback is written —
      `pairs.foreach { case (n, s) => … }`. Desugared to `x => x match { case … }`, so it inherits
      guards, nesting and fall-through from the match that already exists instead of getting a
      second, quieter implementation.

## SSC3-9 — the blockers that were never the construct they were named after

Three links in a row where the bucket's LABEL pointed at the wrong thing, and each was one line in
one imported module reaching 116 files:

| bucket said | it actually was |
|---|---|
| `expected an expression, found case` (123) | `case object SqlNull extends SqliteValue` |
| `expected an expression, found <newline>` (120) | `val result =` with the value on the next line |
| `unexpected character '''` → `unterminated character literal` (120) | **a block comment** — the apostrophe in the English word `journal's`, inside `/* … */`, which the lexer never skipped |

The last one is the sharpest. A missing comment form does not announce itself; it announces
whatever it stumbles into first — here an apostrophe in prose, reported as a character literal, in
a file nobody was editing.

- [x] **9a — a `val` may take an indented block.** `parseBody` rather than `parseExpr`, which is
      what a `def` body already used. The asymmetry had no reason behind it.
- [x] **9b — character literals.** Lowered to the code point through the `char` primitive BOTH
      lanes have: v2 stores a char as `CharV extends IntV`, so `'x' + 1` is 121 and `println('x')`
      is `x`. No new `Lit` — a new one would have needed a codec, a verifier rule and two backend
      arms to express what the existing prim already does. `Char` was deferred in SSC3-7 on the
      grounds that it would split the lanes; measuring the reference lane showed the opposite.
- [x] **9c — `charAt` returns an INT**, matching v1: `"abc".charAt(1)` is 98. Char LITERALS are
      chars there and `charAt` is not, and v3 copies the inconsistency rather than tidying it.
- [x] **9d — nested block comments.**
- [x] **9e — `mkAdd(1)` where `mkAdd` is a PARENLESS def returning a function.** Two steps: call it
      with no arguments, then apply the result. Lowering it as one call passed an argument to a
      zero-parameter function and produced INVALID IR — caught by the verifier, not by a wrong
      answer, which is exactly what invariant I-4 is for. It was the corpus's only CRASH.

**v3 accepts two things v1 rejects**, both in the safe direction: a trait's inherited concrete
method, and a NESTED block comment (v1 answers `structural CoreIR contains parser sentinel _err`).

- [x] **9f — a trailing binary operator continues the expression onto the next line.**

      journalU32(record.pageNumber.toLong) ++ byteSliceToList(record.page) ++
        journalU32(checksum)

      Scala's rule, and the top refusal at 119 cases. The layout after the operator is consumed
      unconditionally — unlike the `else` continuation there is nothing to guess, since an operator
      with no right operand cannot mean anything else — but the INDENTs crossed are still counted
      so their DEDENTs can be taken back. An unmatched DEDENT ends the enclosing block one
      statement early, which is the half the `else` continuation had to learn the hard way.

      **v1 fails this one by printing `Stub`** — the silent sentinel at exit 0, not a diagnostic.
      Third construct where v3 is ahead of the reference lane, and the only one where the reference
      gives a WRONG ANSWER rather than a refusal.

- [x] **9g — a single-line body may be an ASSIGNMENT.** `if curCount > 0 then leaves = leaves + 1`
      — a statement, where `parseBody` parsed an expression. Fixed in `parseBody` itself, so `if`,
      `else`, `while`, a `def` body and a lambda body all get it at once; `for … do` had to be
      fixed on its own before this and is the reason the shared fix was obvious.

      **v1 SILENTLY TRUNCATES the same program.** `while i < 3 do i = i + 1` makes it print 2 of 6
      lines and exit 0 with no diagnostic — the same family as the recorded interpreter defect
      where `if cond then <assign>` with no else is silently skipped. Worth filing separately: it
      is a wrong answer, not a refusal.

- [x] **9h — named arguments and `copy`.** `pager.copy(cache = existingCache, lruOldestFirst = …)`
      was 116 cases. Named arguments are resolved to positions on the AST, alongside defaults;
      `copy` is resolved in the LOWERING, because which class the receiver is is not known until
      run time — the same reason field-by-name access is a `Switch`. Each arm builds its own
      constructor: named fields from the arguments, the rest read back off the receiver.

### The apparatus defect that cost the most this session

`v3/ssc3` compiled BOTH trees through `scala-cli run` on every invocation, into their shared
`.scala-build`. Any concurrent scala-cli on the same tree cleans that directory underneath the
running one; the compile dies with `NoSuchFileException` writing a `.tasty`, and the program
produces **no output** — which a gate that compares output reads as a wrong answer.

Measured: the same fixture, ten runs by hand, came back empty on runs 2, 3 and 5. A different
fixture failed on each gate run, which is what made it look like a flaky v3 defect rather than a
build race. The concurrent scala-cli was MY OWN — two overlapping background gate runs I had
launched myself, the same mistake as the `CRASH 360` earlier, but this time with the mechanism
identified rather than just the correlation.

The driver now packages each tree once per SOURCE DIGEST and runs the jar. Three consequences:
the race window shrinks from every invocation to the first, a failure is a non-zero exit with a
named cause instead of silence, and the gates got several times faster.

**Rule for this module: one gate run at a time, and never a corpus report beside one.**

## SSC3-10 — alternatives and type arguments

- [x] **10a — `case A | B =>`.** 116 cases, and the alternatives BIND NOTHING — Scala requires
      every alternative to bind the same names, and binding none is the only way to satisfy that
      without analysis. So it is a pure disjunction of tests, built as nested `If`s that set ONE
      boolean rather than a chain of `BOr`: `||` is lowered to `If` everywhere else in this file
      because it short-circuits, and a bitwise or on two booleans is defined on neither lane. Built
      back to front so the BODY appears once rather than once per alternative.

      **v1 answers `match: no arm for IndexLeafPage/0`** — it takes only the FIRST alternative and
      loses the rest. Fourth construct where v3 is ahead of the reference lane.
- [x] **10b — type arguments in an EXPRESSION.** `List[Int](1, 2, 3)`, `empty[A]()`,
      `xs.map[Int](f)`. 158 cases, the largest bucket once alternatives landed. Skipped like every
      other type at Tier 0: `a[0]` is not indexing in this language — arrays use `a(0)` — so a `[`
      after a name is unambiguously a type argument list and there is nothing to disambiguate.

## SSC3-11 — the apparatus for the UniML front swap

Sergiy asked whether v3 is ready to use UniML for the AST. The answer is yes, but **not in the shape
the spec said**, and `v3/specs/40-front-on-uniml.md` was rewritten to say why: both earlier versions
had v3 defining no AST of its own, which was true and cheap when `Lower.scala` was small. It is now
the largest and most measured part of v3, and its behaviours were FOUND rather than designed —
forty-five fixtures hold them. Replacing the AST and rewriting the lowering in one step risks all
forty-five at once, and a differential between v3's two LANES cannot see it, because both lanes move
together.

So: **UniML's typed projection is mapped into v3's `Ast`; the lowering does not change.** That makes
the swap gateable — the same source through both fronts must print the same `Ast`.

- [x] **11a — `AstText`,** the canonical text form of an `Ast`. Positions are deliberately NOT
      printed: two fronts may legitimately attribute a node to different columns, and a diff full of
      position noise is a diff nobody reads. Shape and names are what must agree.
- [x] **11b — `Front`,** one door from source text to a `Program`, threaded through `Loader` rather
      than read from a global so two fronts can run in the SAME process on the same file. `uniml` is
      a name that refuses with a pointer to the spec, and is deliberately ABSENT from `available`:
      a name that is listed and then fails reads as a regression, one that never appeared reads as
      unfinished work.
- [x] **11c — `v3/front-diff.sh`.** It says out loud what it is today: ONE front, so nothing is
      compared. It proves the printer is TOTAL over 39 fixtures, and it SELF-TESTS the comparator by
      mutating one token and requiring the difference to be seen — the doctrine applied before the
      thing it guards exists, because a comparator nobody has watched fail is a hypothesis.
      Observed failing: with `render` returning an empty string the gate goes RED.

### What UniML still owes v3 — the ordering request

Written into `40-front-on-uniml.md` §5, measured from UniML's own numbers rather than guessed:
`for` (37 gaps), `try`/`throw` (32 + 56), `spike.pfblock` (185), a nested `def` (48), and the
ALPHABET item that is still open. Every one of the first four is a construct **v3 already supports
with a green fixture on both lanes**, so a swap before they close would lose working features.

Recorded as NOT a blocker, so nobody sequences behind it: an import's PATH is absent from the CST.
v3's imports are markdown links read from the source TEXT by `Loader`, never from the tree.

### And a build fact, measured

Pointing scala-cli at both source trees does NOT work: UniML has a package
`scalascript.uniml.dialect.scalascript`, and inside it `import scalascript.uniml.*` resolves to the
INNERMOST `scalascript`. sbt never sees this because it compiles each subproject as its own unit.
So UniML is consumed as JARS built by sbt — the same digest-keyed caching `v3/ssc3` already does.

- [x] **11d — every gate goes through the cached driver.** Three of them still spelled out
      `scala-cli run v3/src` and so were still on the un-cached path: `exec-gate` went red on
      `type-args` with an EMPTY executor output for a program that runs correctly three times in a
      row by hand. The driver gained a `v2` passthrough so a gate never has to name `scala-cli`
      again. **The exec gate went from ~25 minutes to 26 seconds.** The race is the reason; the
      speed is the side effect, and it is the one that makes running all five gates per batch
      affordable.

## SSC3-12 — the specs catch up with what was measured

Sergiy: "напиши об этом всем в спецификацию". The specs had drifted, and `20-core-language.md` had
drifted in the direction its own warning was written about.

- [x] **12a — `20-core-language.md` §2 re-measured.** The table still called `trait`, `Char`,
      tuples, guards and `Array` unimplemented WEEKS after they landed. Its first version was wrong
      the other way — listing things that did not work — and the warning at the top of it was
      written for exactly that. Now it drifted in the opposite direction, so the warning now says
      *both* directions. Re-measured construct by construct with a fresh probe set rather than
      transcribed from the fixture names.
- [x] **12b — a NOT-IMPLEMENTED table with reasons.** `given`/`using` is singled out: it is the one
      item Tier 0 cannot reach by adding syntax, because it needs type-directed resolution. The
      other ten are ordinary missing work — curried application, `Map`/`Set`, `lazy val`, varargs,
      exponent literals, a nested `def`, a multi-arm `catch`, and the rest.
- [x] **12c — §3a, printing.** Both lanes print v1's convention and not Scala's — `3.0` prints `3`,
      an array prints `<foreign>`, `charAt` returns `98`. Written down with the two debugging rounds
      that bought it: one helper serving both the canonical `.ssir` form and the language's output,
      and `Char` deferred on a misreading of exactly this table.
- [x] **12d — §4a, where v3 accepts MORE than v1.** Five constructs, two of which are v1 giving a
      WRONG ANSWER rather than a refusal (`Stub` at exit 0; a silently truncated program). Recorded
      so a reader who meets them does not conclude v3 is broken, and argued: accepting more cannot
      change the meaning of a program the reference accepts, so `N` is unaffected.
- [x] **12e — §4b, what the number does not measure.** `N` counts corpus cases, not how pleasant v3
      is to write. Sergiy chose the second explicitly on 2026-08-05 and the two pull apart.
- [x] **12f — `00-charter.md` I-3, two clarifications.** It is about v3's OWN two lanes, not about
      v1. And **agreement is not correctness**: the nested-closure bug failed identically on both
      lanes and the differential said GREEN. A differential is evidence of agreement; a third
      implementation is what turns it into evidence of correctness.
- [x] **12g — the charter gained "how the apparatus has lied".** A gate that cannot fail; compare
      output never exit codes; a contended host reports a defect rather than contention; a
      measurement answers only the question it literally asked. Every entry has the round that
      bought it attached.
- [x] **12h — six string methods.** `substring` `indexOf` `replace` `contains` `startsWith`
      `endsWith` ran on the bridge and refused on the executor — found while probing FOR the spec,
      which is the argument for re-measuring rather than transcribing.

## SSC3-13 — the UniML integration, answered by probe

Sergiy asked whether the AST should be built straight from UniML during parsing. Answered in
`40-front-on-uniml.md` §5a, and the answer needed two corrections to my own earlier writing.

- [x] **13a — §5 was WRONG and is corrected.** It listed five constructs as UniML's debt — `for`,
      `try`/`throw`, `pfblock`, a nested `def`. All five were modelled. I built that table from the
      prose of UniML's sprint file without opening `SpikeTyped.scala`, and the prose was stale by
      ONE COMMIT. The measured state is coverage **100.0%**, gaps **28**, silent drops **0**, and
      *no construct the CST has is unmodelled*. The 28 are parse-recovery holes — breadth, not
      typing. A list inherited rather than measured, which is the error this repository documents
      most and which I made against a colleague's notes.
- [x] **13b — §5a answers the architecture question with the two trees measured.** `SpikeAst` has 47
      node kinds and keeps Scala's SURFACE shapes — `For`/`ForGen`, `PartialFn`, `Tuple`,
      `PatTuple`/`PatCons`, `IndexAssign`, `Infix`/`Prefix`, three separate call nodes. v3's `Ast`
      has ~25 and has already desugared all of them. **The second projection is where v3's
      desugaring lives; it is the work, not overhead.** Building from the CST would not remove it —
      it would remove the compiler's help while doing it, and re-open the silent-miss hole UniML
      just spent a sprint closing.
- [x] **13c — the build integration is VERIFIED, no longer assumed.** Seven classpath entries from
      sbt; a program compiled against them parses ScalaScript and projects `Def`, `ValDef`,
      `For`+`ForGen`, `CaseClass` with a defaulted param, `Match` with `PatCons` — with spans, no
      sbt at run time.
- [x] **13d — two API facts found by the probe.** `SpikeTyped.module` takes a SCALA SUBTREE, not the
      composed root. And a BARE `.ssc` yields ZERO subtrees, while fences have been optional here
      since 2026-07-09 — a whole program would read as prose, silently. Filed in §5 as a request.

- [x] **13e — UniML's BREADTH measured, which turned the question from open to closed.** The
      projection is at 100%, so the only remaining question was whether the DIALECT parses what
      SSC3 core needs. Answered by running it over every file and counting `spike.error`:

      | corpus | files | with a parse error | `spike.error` |
      |---|---|---|---|
      | `v3/tests/front/` | 46 | **1** | 1 |
      | `tests/conformance/` | 390 | **2** | 4 |

      Three constructs in the whole corpus, and TWO of them v3 does not support either (`x += 1`,
      a user-defined symbolic operator `def <~>`). **The entire breadth debt to v3 is one
      construct**: `if c then a(i) = v`, an index assignment as a single-line branch body. Narrowed
      by probe, and the narrowing is not what the file name suggests — `a(1) = 4` alone parses and
      `if c then n = 5` parses; only the combination fails.

- [x] **13f — `50-uniml-projection.md`,** the projection's CONTRACT. §5a says why the projection
      reads the typed AST; nothing said WHAT maps to what. Now it does: declarations, the three
      call shapes collapsing into two v3 nodes, the five desugarings that must reproduce exactly
      what `Parser` already produces (`for`, `PartialFn`, `TupleVal`, `IndexAssign`, interpolation),
      the pattern table, and every construct that must REFUSE rather than be quietly erased —
      `PatTyped` above all, since erasing its type makes `case x: Int` match everything.
      Four OPEN QUESTIONS are left open on purpose, each a probe rather than a guess.
- [x] **13g — the import note in §5 corrected, again by reading code instead of prose.**
      `ImportDecl` carries its path now (`ImportDecl(a.b.c, [], false)`). But the MARKDOWN link
      yields nothing — it sits outside the fence, so the ScalaScript dialect never sees it. Correct
      behaviour, verified rather than assumed, and it means the projection does NOT build the module
      graph: `Loader` keeps doing it from the raw text. The opposite assumption would have produced
      a front that resolved no imports while looking correct.

## SSC3-14 — the UniML front EXISTS, and the differential has a number

`v3/uniml/UniFront.scala` + `UniMain.scala`: source → UniML CST → typed projection → v3's `Ast`,
implementing `50-uniml-projection.md`. It lives OUTSIDE `v3/src` on purpose — the kernel has zero
dependencies and must keep building when UniML is not built at all, which every gate relies on.

**First measurement, 40 fixtures: 17 identical / 19 different / 4 refused.** Then three fixes, each
found by reading a diff rather than by guessing:

| | identical |
|---|---|
| first run | 17 |
| + `Block(Nil, Some(e))` printed as `e` — a canonicalisation, since the two ARE the same expression | 20 |
| + the destructuring temp named after what it BINDS instead of where it is | 22 |
| + v3's enum cases emitted in SOURCE order | 22 |

**Two defects in v3 that only the differential could find**, because both are invisible to a lane
comparison — the two lanes share the front:

- [x] **v3 emitted an enum's cases in REVERSE source order.** `parseEnum` prepends and returned
      without reversing. Nothing observable depended on it — tags are assigned on first use — which
      is exactly why it survived. The other front had them in source order, and that is what made it
      visible.
- [x] **The destructuring temporary was named after a POSITION.** Two fronts may attribute a node to
      different columns, so the name differed for a variable nobody writes and nobody reads. Now
      derived from the bound names.

**Two gaps in UniML that only the differential could find**, both WRONG ANSWERS rather than losses,
now filed in `40-front-on-uniml.md` §5b:

- `ValDef` has no mutability flag — `var x = 0` and `val x = 0` project identically.
- a character literal projects as `IntLit`, so `'x'` is indistinguishable from `120`.

Remaining: 14 differences and 4 refusals, each with a named cause. Two of the refusals are
`case object` (v3 needs a nullary class; UniML gives an `ObjectDecl`), one is the known dialect gap
`if c then a(i) = v`, and one is a NESTED block comment, which v3 supports and the dialect
diagnoses.

## SSC3-15 — the front-diff gate is REAL, and it found the biggest UniML gap

`v3/ssc3 fronts` reports which fronts can run, `v3/ssc3 ast <f> uniml` routes to the second one, and
`front-diff.sh` ASKS the driver instead of grepping a source file. The classpath is cached on a
digest of both source trees, for the same reason the jars are: without it the gate recompiles once
per fixture.

Availability is an explicit cached fact — `v3/.jars/uniml.cp` exists or it does not — refreshed by
`v3/uniml-classpath.sh`. The driver never runs sbt: a build of something the caller is not using has
no business blocking `ssc3 run`.

**Agreement: 25 of 40 fixtures, up from 17 at the first measurement.** The gate is RED and correctly
so — it now compares two fronts and reports what differs, which is what it was built for.

- [x] **15a — a negated literal is a literal.** v3's lexer folds the sign in; UniML keeps the written
      `Prefix("-", IntLit(1))`, which is right for a CST-faithful tree and wrong for this one. Three
      fixtures.
- [x] **15b — `trait` VANISHES on the UniML side, and none of UniML's own gates can see it.**
      **DONE — verified 2026-08-12 by running it, not by reading the code.** `U.TraitDecl` projects
      to `TraitDef` with its methods; an abstract signature (`NotImplemented`) becomes
      `__abstract__` so `Lower` dispatches to the subclass instead of returning unit; and a
      trait member that is not a `def` is REFUSED BY NAME rather than dropped. Measured on both
      fronts: `trait Shape` with two abstract defs and a `case class` override prints `9` / `sq`
      through v3's own front AND through the UniML projection, and `trait Named` with a `val id`
      is refused by both. The item was stale — the work had landed with nothing marking it here,
      which is the same shape as the two BUGS entries found already-fixed this morning.
      Measured: the CST for `trait Shape:` is `spike.sealed` — the kind the dialect also gives
      imports and anonymous `given`s — and the projection maps it to `NoOpDecl`, "parsed, and
      genuinely carrying nothing". The trait's METHODS never reach the CST.

      So the `spike.error` count does not see it (nothing failed to parse), the silent-drop census
      does not see it (`NoOpDecl` is modelled, and there is no subtree beneath it to drop), and the
      coverage figure counts it as typed. **A construct consumed into a contentless node is
      invisible to all three measurements at once** — the same shape as the coverage metric that
      rewarded dropping, and a reminder that a measurement can only see what the representation
      admits exists.

      Filed as item 3 of `40-front-on-uniml.md` §5b, and it is the most consequential item there:
      `trait` gates 137 corpus cases, and v3's traits carry the dispatch that makes them worth
      having.

- [x] **15c — the gate carries an agreement FLOOR, not a pass/fail on completeness.** 25 of 40,
      may rise in any commit and fall in none — the same non-regression rule the corpus number
      carries. Without it the gate would be permanently red while the second front is built, and a
      permanently red gate stops being read. A refusal by the SECOND front is counted, not failed;
      a failure by v3'''s own front is still a hard FAIL, because that one must always print.
      Observed failing: floor 26 against 25 agreeing goes RED.

## SSC3-16 — 29 of 40, and every remaining difference is on UniML's side

| | agree |
|---|---|
| first measurement | 17 |
| block canonicalisation, temp naming, enum order | 22 |
| negated integer folded | 25 |
| **the uniml front goes through `Loader`** | 27 |
| negated FLOAT folded, and the arms moved BEFORE the general `Neg` | 29 |

- [x] **16a — the uniml front had NO IMPORTS.** It called `UniFront.parse` on one file, so every
      cross-file import vanished with no diagnostic. `Loader.closureWith` now takes the parse step as
      a PARAMETER — the kernel cannot name a front that lives outside it, so passing the function is
      what lets the second front reuse the module graph instead of reimplementing it.
- [x] **16b — the negated-literal arms were DEAD.** Placed after the general `Neg` arm, which had
      already matched. The gate said so by not moving, which is the cheapest way to find out.

**The remaining 11 are all UniML's**, each filed in `40-front-on-uniml.md` §5b:

| fixtures | gap |
|---|---|
| demo, globals, val-block, arrays | `ValDef` has no mutability flag — `var` reads as `val` |
| traits-methods | a `trait` VANISHES into `NoOpDecl` |
| case-object, alt-pattern | `ObjectDecl` has no `case` marker |
| char-literals | a character literal projects as `IntLit` |
| append-ops | `+:` is normalised to `::` |
| assign-body | the dialect gap `if c then a(i) = v` |
| block-comments | a NESTED block comment |

## SSC3-17 — LANE PARITY, measured by probe rather than by reading

Sergiy's call: close the parity hole before making the executor the default lane, because otherwise
the flip is how the hole gets discovered — on his programs.

**Measured: 23 of 32 method probes ran on the BRIDGE and refused on the EXECUTOR.** That is a hole
no amount of reading either implementation had suggested, and the reason is worth keeping: reading
tells you what IS there, a probe tells you what a program can REACH. The first four —
`substring`, `indexOf`, `replace`, `contains` — were found by accident while writing a spec, which
is what prompted the sweep.

- [x] **17a — the probe set**, one program per method, derived from what the CORPUS actually calls
      rather than from either implementation's source.
- [x] **17b — 23 methods implemented on the executor**: `exists` `forall` `find` `sorted` `sortBy`
      `zip` `take` `drop` `distinct` `count` `min` `max` `last` `init` `indexOf` `contains`
      `reduce` on lists; `isDefined` `map` `foreach` on `Option`; `reverse` `count` on `String`;
      `abs` on numbers.
- [x] **17c — the runtime's TYPES are pre-registered.** `xs.find(…)` returns a `Some` and
      `xs.zip(ys)` returns tuples, and a module that never wrote `Some` or `(a, b)` had no entry
      for them — so the executor could not build the value it was asked for. The bridge never
      noticed because v2 has its own constructors. That asymmetry is the divergence, and declaring
      the five types the runtime can produce removes it.
- [x] **17d — `mkString` printed through `show`, not `showV`.** A zipped list came out as
      `#4(1, a)` instead of `(1, a)` — the last probe to fall, and the only one whose cause was in
      the PRINTER rather than in a missing method.
- [x] **17e — `v3/parity-gate.sh`,** so it cannot decay. Three outcomes, one of them a failure:
      agree, NEITHER lane has it (not this gate's business), or diverge. Observed failing: removing
      `sorted` from the executor gives `FAIL list-sorted — bridge [1,2,3/] executor []`.

**30 of 32 agree; the other 2 (`3.max(5)`, `3.min(5)`) are implemented by neither lane**, which the
gate reports as `neither` rather than counting as agreement — a gate that scored those as passes
would be scoring its own blind spot.

## SSC3-18 — curried application, and the parity gap it immediately exposed

- [x] **18a — `f(a)(b)`.** A `(` DIRECTLY after an expression applies it. No newline may intervene
      and none can: a newline is its own token, so a `(` opening the next line is a new statement
      and is never reached. New AST node `Apply` — `Call` names a function, and a curried call has
      an EXPRESSION in that slot.
- [x] **18b — `foldLeft` / `foldRight`, and a way for a built-in to be applied in TWO argument
      lists.** `xs.foldLeft(z)(f)` means the first invoke gets one argument and must return
      something the second can apply. v3 has no general partial application — a `VClos` needs a
      lifted function index and a built-in has none — so `VPartial(recv, name, got)` is the shape
      that makes a curried BUILT-IN call expressible. `CallV` on one finishes the invoke.

      **It was invisible until 18a landed.** The construct existed on the bridge the whole time and
      the executor had no way to reach it, so no probe could have been written for it: a parity
      sweep can only probe what the front can PARSE.

## SSC3-19 — `->`, `Map`, and a defect that only became reachable

- [x] **19a — `k -> v` is a `Pair`.** Measured against v1, which prints `Pair(a, 1)` and NOT
      `(a, 1)`. The first attempt built a `Tuple2` and both v3 lanes agreed with each other while
      disagreeing with the reference — I-3 satisfied and compatibility broken, which is exactly the
      pair of facts a lane differential cannot separate. `Pair` is registered as a synthetic class
      alongside the tuples, so `._1` and patterns work through the machinery that already exists.
- [x] **19b — `Map(k -> v, …)`,** built from the prims v2 ALREADY has (`map.new`, `map.put`) rather
      than from a new IR form — the instruction set is not where a library type belongs. On the
      executor a `VMap` is an INSERTION-ORDERED buffer of pairs, because that is what the reference
      prints: `Map(a -> 1, b -> 2)` in the order written. `size` `isEmpty` `nonEmpty` `contains`
      `get` `getOrElse` `keys` `values`, and `m(k)` yielding the VALUE rather than an Option, with a
      missing key an error rather than a unit — a silent unit is a wrong answer that surfaces
      somewhere else.
- [x] **19c — calling a function held in a FIELD.** `v.step(1, 2)` where `step` is a field, not a
      method. It reached the corpus as `Stub` — v2's silent sentinel at exit 0 — the moment
      `foldLeft` became parseable and the case stopped being UNSUPPORTED.

      **The defect was always there; making one construct work is what let the program get far
      enough to show it.** That is the argument for re-running the WHOLE corpus after every front
      change rather than the bucket that moved.

**N = 39 → 45.** Six gates green; front-diff agreement 32 of 43, floor raised.

## SSC3-20 — a `def` inside a `def`

- [x] **20a — local functions are LIFTED, with captures as leading parameters.** Not rewritten into
      a `val` holding a lambda, because a local function may RECURSE and a `val`-bound lambda has no
      name to call itself by. Lifting keeps the name, so the recursive call is an ordinary call.

      Captures become parameters rather than a closure because the call sites are all visible: a
      local def is called from the body that declares it and from itself, and one pass rewrites
      both. Iterated to a fixed point, so a local def inside a local def lifts too.

      Verified on four shapes — plain, capturing, recursive, and parameterless. **v1 answers
      `<closure>1` on the parameterless one**, so it does not auto-apply it; v3 does, which is
      Scala's rule and the fifth place v3 is ahead of the reference.

      *Correction 2026-08-08:* v1 auto-applies it now — the sentence above was true when written and
      is not any more, and it is left in place because the decision it explains was made on it. The
      v1 defect is `parameterless-def-diverges-native-vs-interp` in the root `BUGS.md`, fixed by
      making the missing parameter clause a `FunV` constructor field, since `params` is `Nil` for
      both spellings. So this is no longer a place v3 is ahead of the reference; the count is one
      lower, and js and v2 still lose a LOCAL parameterless def
      (`parameterless-def-local-not-invoked-js-and-native`), which is the shape 20a is about.
- [x] **20b — the three sites the compiler named.** Adding `Stmt.LocalDef` produced six
      exhaustiveness warnings, which is the check doing its job. Two were printers. The third is the
      block lowering, and it does NOT silently ignore a local def that reaches it: lifting runs
      before any lowering and removes every one, so arriving there means the pass missed one, and
      dropping a whole function quietly is worse than an internal error with a name.

N = 45/360, DIFF 0, CRASH 0. Gates: front 50 cases, executor 52, parity 37 of 39, bridge, selftest.

## SSC3-21 — `Set`, exponent literals, and two constructs that do not exist

**The measurement saved more work than it cost.** Four constructs were on the list; probing the
reference lane first showed that **two of them are not in the language at all**:

| construct | v1 answers |
|---|---|
| `lazy val x = 5` | `unbound global: lazy` |
| `def f(xs: Int*)` | `arity: 1 expected, 3 given` |

Implementing them would have made v3 accept programs no other lane runs, for no compatibility gain,
and the spec would have carried them as v3 gaps forever. The probe took a minute.

- [x] **21a — exponent float literals.** `1.0e10`, `2.5E3`, `1e-3`. The exponent makes the literal a
      FLOAT even without a dot — `1e-3` is 0.001 — so the flag is set there and not only by the
      fraction. A digit after `e`/`E` (or after its sign) is REQUIRED, which is what keeps
      `1.toEngine` lexing as a number and a method call: the character after the `e` decides, the
      same rule the fraction uses for the dot.
- [x] **21b — `Set(…)`,** one prim (`set.of`), because v2 exposes exactly one and it takes every
      element — simpler than `Map`, which needs a put per pair. Insertion-ordered and de-duplicated
      on construction, matching the reference: `Set(1, 2, 2, 3)` prints `Set(1, 2, 3)`.
- [x] **21c — the spec table re-measured** and `lazy val`/varargs moved from "not implemented in v3"
      to "NOT IN THE LANGUAGE", with what v1 answers written next to each.

N = 45 → 47. Gates: front 52 cases, executor 54, parity 40 of 42.

## SSC3-22 — object members and qualified enum cases: the daily-use list is closed

- [x] **22a — `C.Red`.** v3 flattens an enum into one class per case, so the qualifier carries no
      information by the time the lowering sees it: it is DROPPED rather than resolved, in both
      expression and pattern position. Keeping it would mean two spellings of one constructor for
      every later phase to reconcile.
- [x] **22b — an `object` member that is not a `def`.** A `val`/`var` member becomes a module GLOBAL
      named `Object.member` — a namespace is not a value in this language, so there is nowhere else
      for a `var` to live, and the dot puts it in the same namespace the qualified read looks in.
      Members initialise BEFORE any top-level statement, since a script may read one.
- [x] **22c — an object's methods see its members UNQUALIFIED.** `def bump(): Unit = n = n + 1`
      means the object's own `n`. Rewritten while the methods are flattened, with a parameter or
      local of the same name SHADOWING — which is why it cannot be a plain `mapDeep`, the same
      scope care `selfCalls` needs. Without it the method reported `unknown name 'n'` while the
      global sat beside it under another name.

      `tests/conformance/object-var-member-scope.ssc` now MATCHES, and it was the case that said
      what the semantics had to be.

**v3 differs from v1 on one point here, and it is the sixth such.** Direct qualified assignment —
`Cfg.count = 7` from outside the object — takes effect in v3 (`x/0/7`) and is silently ignored by
v1 (`x/0/0`). Mutation through a METHOD works on both, which is what the corpus tests. v3's answer
is Scala's.

N = 47 → 48. Gates: front 54 cases, executor 56, parity 40 of 42.

## SSC3-23 — the lane I was comparing against was not the one I named

Sergiy asked whether v2 was fine on the four defects I had just filed against v1. Measuring the
question properly showed something worse than the answer: **I had been running the wrong lane all
session.**

`bin/ssc run` is the NATIVE lane — the self-hosted front on the v2 VM. The tree-walking interpreter
is `ssc-tools run --v1`. This repository's lane map already says so and I had it recorded. I ran
`bin/ssc run`, called it v1 in every comparison, and wrote six "v3 is ahead of v1" claims into two
specs, a sprint file and several commit messages.

Re-measured on `--v1`, all six:

| construct | `--v1` | native / `--v2` |
|---|---|---|
| inherited trait method | correct | RuntimeException |
| `case A \| B` | correct | first alternative only |
| nested block comment | correct | native-front exception |
| trailing-operator continuation | correct | `Stub` at exit 0 |
| `while … do <assign>` | correct | nothing at all, exit 0 |
| `Cfg.n = 7` | refuses WITH A POSITION | `0`, silently |

**The interpreter is correct on five and honestly refuses the sixth.** v3 is level with it, not
ahead — and ahead of the self-hosted front on all six.

- [x] **23a — the four `BUGS.md` entries re-filed** as `selfhost-front-*`, lane `multi`, with a
      three-lane table each and the correction stated inside the entry rather than edited away.
- [x] **23b — `20-core-language.md` §4a rewritten.** It claimed v3 accepted five things v1 rejects.
      It accepts them; so does v1.

**The lesson is one this file already carries in another form:** a measurement is evidence about the
command you actually ran. "A census answers only its own question" was written here about corpus
buckets; it applies to the runner just as hard, and I proved it by getting the runner wrong for a
whole session while quoting its output as fact.

## SSC3-24 — the parity gate was measuring its own probe set, not the corpus

Sergiy's ordering put lane parity before making the executor the default. Measuring whether that
precondition was MET turned the answer around: the parity gate said 40 of 42, and the corpus said
something else entirely.

| | bridge lane | executor lane |
|---|---|---|
| before this entry | PASS 48 | **PASS 34, CRASH 14** |
| after | PASS 48 | **PASS 45, CRASH 3** |

**Flipping the default would have dropped `N` from 48 to 34.**

The probe set was derived from the corpus's method names **by frequency, top ~30**, and every one
passed. The corpus reaches the tail: `toList` `slice` `scanLeft` `filterNot` `takeWhile` `dropWhile`
`zipWithIndex` `headOption` `lastOption` `toSet` on lists; `take` `drop` `toList` `lastIndexOf` and
a CHARACTER argument to `indexOf` on strings; `exists` `filter` `toList` on `Option`; `union`
`intersect` `diff` `subsetOf` `map` `filter` `exists` `foreach` `mkString` `++` and the `+`/`-`
operators on `Set`; and `xs(i)` applied to a LIST.

**A probe set is a census, and a census answers only its own question.** This one asked "do the
common methods agree?" and the answer was yes. The question that mattered was "does the corpus
run?", and only the corpus could answer it.

Two of the finds were WRONG ANSWERS rather than refusals, which is the worse kind:

- [x] **`mkString` has THREE forms**, and only the middle one was implemented. `mkString("[", ", ",
      "]")` silently used its FIRST argument as the separator and printed `5[3[8[1[9[2`. The arity
      was never checked, so nothing refused.
- [x] **`indexOf`'s arms were ordered by when they were written**, not by specificity: the general
      string case threw before the CHARACTER case was reached.

And one improvement to a failure rather than a fix to a bug: **`substring` checks its own bounds**.
The host's `StringIndexOutOfBoundsException` is a CRASH to the corpus report — "neither ran it nor
refused it cleanly" — while a named error is a refusal a reader can act on. The reference lane
throws too; this is about the quality of the failure.

**Still open, and now the whole of it:** `matches` (regex, and a portability question rather than a
missing arm), and `markdown-html`, where the executor reaches a backwards `substring(4, 2)` that the
bridge never does — a genuine divergence with the cause upstream of the call.

## SSC3-25 — the two lanes now give the SAME NUMBER on the corpus

| | bridge | executor |
|---|---|---|
| when the parity question was first asked | 48 | 34, CRASH 14 |
| after the first three rounds | 48 | 45, CRASH 3 |
| **now** | **48, DIFF 0, CRASH 0** | **48, DIFF 0, CRASH 0** |

`v3/corpus-report.sh --exec` measures it, so the difference is a number in the same report rather
than a separate errand.

The last three finds were the interesting ones, and all three were WRONG ANSWERS that presented as
something else:

- [x] **`indexOf` ignored its second argument** — the START OFFSET. Nothing failed at the call: it
      returned the first occurrence from zero, so a scan loop got an index BEHIND its own cursor and
      the `substring(from, at)` three calls later was backwards. **The crash was downstream of the
      cause**, which is what an ignored argument buys. Closing it took `markdown-html` from one line
      of output to thirty-nine.
- [x] **`Set` equality fell through to `false`.** `Set(1,2) == Set(2,1)` was false, because `eq` had
      no arm for it and the catch-all says no. A set is equal by CONTENT — which is what the corpus
      case is named for.
- [x] **`mkString` had one form of three** (SSC3-24), same shape: an unchecked arity silently
      choosing the wrong meaning.

Also: `"3".toInt`, `asInstanceOf` as the identity it is under erasure, and `matches` delegated to
the host. That last one is I-1's boundary read correctly — **the ban is on host character
classification deciding the language's SYNTAX, not on using the host to implement a library
method** — and a hand-written engine would have been a second regex semantics to keep in step.

**Self-sufficiency is now a decision rather than a risk.** The precondition Sergiy set is met and
measured on the corpus rather than on a probe set.

## SSC3-26 — `ssc3 run` is v3's own runtime

The third of Sergiy's four original goals — стабильность, лёгкость, **самодостаточность**,
корректность — and the one that had been waiting on a precondition.

- [x] **26a — `run` executes on v3's runtime; `run --bridge` reaches the v2 VM.** Three things were
      checked before the switch, not after:
      1. both lanes give the same corpus number — 48, DIFF 0, CRASH 0;
      2. the executor implements EVERY primitive v3's lowering can emit (5 plus 3 builtins), so
         there is no capability a v3 program can reach through the bridge and not here. This was
         the check that nearly stopped it: the bridge exposes v2's whole runtime — `io`, `str`,
         `big`, `cell`, `arr` — and the corpus, being pure, could never have shown the difference.
         What matters is not what v2 HAS but what v3 can EMIT;
      3. the gates still compare two real lanes.
- [x] **26b — the parity gate was VACUOUS for one commit, and I caught it by looking.** It compared
      `run` against `exec`; with `run` switched those became the same lane, and it was green for it.
      Now it names `run --bridge` explicitly. Observed failing afterwards: removing `sorted` from
      the executor gives `FAIL list-sorted — bridge [1,2,3/] executor []`.

      **Flipping a default silently re-points every gate that named it.** Worth stating as a rule:
      after changing what a command MEANS, re-read every gate that calls it and ask what the two
      sides are now.

The bridge is not retired. It is the COMPATIBILITY lane — `ssc3 build` emits v2 Core IR, which is
how v3 has the whole backend fleet without having written one — and I-3's differential needs both
sides to stay real.

## 27 · The UniML front reaches §7's acceptance number — 101 of 101 on the CORPUS

**N = 48 → 50. Front agreement: 35 → 48 of 48 fixtures, and 101 of 101 corpus cases.** §7 says the
swap is a number: both fronts print the same `Ast`, byte for byte, for every fixture and every
corpus case v3 compiles. That number is now met.

- [x] **27a — the projection follows UniML's new ADT.** `ObjectDecl` carries `isCase`, `ValDef`
      carries `isVar`, `CharLit` is its own node, `TraitDecl` and `AbstractVal` exist. Each closed a
      class of wrong answer rather than a missing feature: a `var` read as a `val` made every later
      assignment a refusal, and a `case object` was indistinguishable from an empty namespace.
      Fixture agreement 35 → 43 on that alone.

- [x] **27b — THE FIXTURE SET FLATTERED ITSELF: 48/48 while the corpus read 92/100.** The gate now
      sweeps `tests/conformance/` as well, and it found eight disagreements the 48 hand-written
      fixtures could not. This repository has paid for a probe set once already — a parity probe
      read 40/42 green while the corpus behind it read 34 against 48 — and the reason is
      structural, not carelessness: a probe set is written by whoever is fixing the thing it
      measures, so it drifts toward what has been fixed. The corpus was not.

- [x] **27c — a body-less `trait` or `object` SWALLOWED the declarations after it.** UniML's member
      loop ran even with no braces and no colon; `bodyCol` became the column of the NEXT TOP-LEVEL
      DECLARATION, `peekCol >= bodyCol` held trivially, and the sibling was parsed as a member —
      cascading, so `trait K` / `case object A` / `case class B` / `def f` at column 1 collapsed
      into one declaration four deep.

      **It had a census pinned on top of it.** `Ssc3ProjectionCensusSpec` asked "does any object
      hold a nested class?" and answered "yes, emphatically: 180 across 257 objects, ObjectDecl=96,
      CaseClass=84, Def=651" — and concluded v3's refusal was on a hot path. After the fix: 4
      nested, ObjectDecl=0, Def=261. **176 of the 180 were swallowed siblings and 390 top-level
      `def`s had been absorbed into a preceding object.** A census answers only its own question,
      and this one was asked of a tree the parser had rearranged. The refusal is on a COLD path;
      whoever writes it must plant a case.

- [x] **27d — four more UniML defects, each a WRONG ANSWER rather than a refusal.**
      - `0d` / `1.5f` / `3D`: no float suffix in the lexer, so `val a: Double = 0d` lexed as the
        integer `0` followed by the identifier `d` and the next line became a bare name. Three
        corpus cases came out with more statements than they had.
      - `'\r'` was the letter `r` — 114, not 13 — and `"a\rb"` was `arb`. **Two copies of the escape
        table, both knowing only `\n` and `\t`**, everything else falling through to itself: right
        for `\\`, `\"`, `\'`, silently wrong for the rest. Now one table, plus `\u` on both paths.
      - `PatLit` was stringly-typed and had lost the literal's KIND. The integer arm handed over the
        raw lexeme, the string arm decoded CONTENT with the quotes gone, and the char arm handed
        over `'\n'` raw — so `case '\n'` matched character 92. `case "NULL"` would have thrown
        `NumberFormatException` in the consumer; no fixture had one. It carries the `Expr` node now,
        so the two decode paths cannot disagree — there is only one.
      - `Cfg.count = 7` projected as `spike.error`. A dotted assignment target was unrepresentable,
        which is every object with a `var` member.

- [x] **27e — and THREE in v3's own front, which the differential found by disagreeing.**
      - `"""…"""` was not lexed, so `"""{"user":…}"""` read as the empty string `""` followed by the
        JSON's own tokens: one `val` became twenty-one statements. `json-value`, `json-lookup`.
      - ```` ```scalascript @id=defs ````: the info string was matched WHOLE, so an attributed fence
        was skipped entirely and `fence-attr-code` compiled with a third of itself missing.
      - `sealed trait Shape`: the modifier fell through to the expression parser and became a
        top-level statement reading an unbound name, `(do (name "sealed"))`.

      Fixing the fence match then broke `fence-doc-block`, whose `@doc` fence must NOT run and says
      so in its own prose. Reading attributes without reading that one traded a fence wrongly
      SKIPPED for a fence wrongly RUN — the worse of the two, and the gate caught it in the next
      run.

- [x] **27f — the gate was observed failing, organically, five times.** Not a planted defect: the
      corpus sweep went 92 → 94 → 96 → 100 → 101 as each cause was fixed, and the `@doc` regression
      appeared as a NEW disagreement between two of those runs. That is stronger evidence than a
      mutation, because nobody chose the failures.

**The swap itself is NOT in this commit.** The number is met, and flipping the default is its own
decision with its own risk: the kernel has zero dependencies by invariant I-1 and must keep working
with UniML unbuilt, so `Front.available` growing a default is a change to what `ssc3` REQUIRES, not
just to what it uses. And §26b's rule applies directly — flipping a default silently re-points every
gate that named it. It gets its own commit and its own re-reading of every gate.

## 28 · THE SWAP. UniML is the default front — N = 50 → 57

§7 made the swap a number and §27 met it: 48 of 48 fixtures and 101 of 101 corpus cases printing
the same `Ast`. This is the flip, and it did **not** go through cleanly — which is the point of
having done it as its own commit.

- [x] **28a — the front is REGISTERED, not imported.** `v3/uniml` is a separate artifact because the
      kernel has zero dependencies (I-1) and must build and run with UniML absent, so registration
      goes the other way: the outer artifact installs its parser into `Front`, and `Front.default`
      returns it. `UniMain` then runs the SAME `Cli.run` the kernel runs — before this it duplicated
      nothing because it only printed an `Ast`, and duplicating a 130-line dispatch so the second
      front could reach `exec` is exactly how the two would have drifted.

      `SSC3_FRONT=v3` still isolates the kernel. §7 promised that and an unexercised promise is a
      hypothesis, so `front-report-gate.sh` asserts it.

- [x] **28b — `ast` was the one command that ignored the registered front.** Its default was
      hard-coded `Front.v3`, so inside the uniml artifact `exec` ran on UniML while `ast` printed
      v3's tree. The text the differential compares and the tree that actually runs could have
      drifted apart with every gate green. Found by running a curried fixture and getting a refusal
      from `ast` and a verifier error from `exec` — two different fronts, one command line.

- [x] **28c — `corpus-report.sh` COULD NOT SEE THE FRONT, and I reported a number from it anyway.**
      It packages `v3/src` alone, so `SSC3_FRONT` was a no-op there. Two runs — one of them
      explicitly `SSC3_FRONT=v3` — gave the same N and the same buckets, and I wrote that down as
      "both fronts agree". They did not agree; one of them ran twice. A census answers only the
      question its own command asks. Fixed by teaching it the uniml classpath (`ssc3 __classpath`)
      and by printing which front the report measured in its header.

      The honest first measurement was **N = 53 with DIFF 1 and CRASH 3** — not the free win the
      Ast agreement suggested. Agreement is measured only where BOTH fronts print; UniML prints for
      cases v3's front refuses, and those reach the lowering for the first time.

- [x] **28d — four defects behind that, all in v3, none reachable before.**
      - Curried clauses. `def ap(n: Int)(f: …)` flattens to one parameter list, so `ap(3)(f)` has
        to flatten too; it lowered to a one-argument call and the verifier refused the module.
        `flattenCurried` runs before `fillDefaults` and is GUARDED ON ARITY — `mk()(3)`, where `mk`
        returns a closure, must stay an application of that closure.
      - `-9223372036854775808`. The minus has to join the digits BEFORE parsing: `Long.MinValue`'s
        digit string is 2^63 and overflows on its own. v3's own lexer learned this and says so at
        `Lexer.scala:13`; the projection was the second copy of the mistake, and it arrived as a raw
        `NumberFormatException`, which `corpus-report.sh` bins as CRASH.
      - **`split` was a LITERAL split, not a regex one.** `"a **b**".split("\\*\\*")` matched
        nothing and returned the whole string. Both lanes and both fronts printed the same wrong
        answer, so no differential here could ever have found it — the corpus did, against a
        recorded expectation from another lane. Agreement is not correctness.
      - The **constant pool conflated `-0.0` and `0.0`**, because `indexOf` compares with `==` and
        `-0.0 == 0.0` is true for a `Double` (and `NaN == NaN` is false, so every NaN got its own
        slot). `println(-0.0)` put a NEGATIVE zero in the pool; `1.0 / 0.0` then found that slot and
        divided by it, printing `-inf` where it must print `inf`. Interning is by
        `doubleToLongBits` now.

        **This one is the lesson of the whole session.** v3's own parser reads `-0.0` as
        `Neg(DoubleLit(0.0))` — a runtime negation of a positive zero — so the poisoned constant
        was never created and the pool looked correct for two months. UniML folds the sign into the
        literal, which is equally right, and hit it at once. And the front differential was BLIND:
        `AstText` deliberately folds `Neg(float)` into a negative literal, so the two trees printed
        identically while executing differently. A canonicalisation that exists to suppress a
        harmless difference will, one day, suppress a harmful one.

- [x] **28e — the uniml front ACCEPTED a file with an unclosed brace.** UniML's parser is
      error-TOLERANT by design — it reports and carries on, so a document viewer can still show the
      good parts. For a compiler front that is the wrong contract, and `def main(): Unit = {` with
      no `}` came back as a clean two-line program. Any `Error`/`Fatal` diagnostic is now a
      positioned refusal. Compiling a file the user did not write is worse than refusing one they
      did.

- [x] **28f — §26b's rule arrived in person.** Flipping the default silently re-pointed every
      runtime gate: exec, bridge, parity and the corpus all stopped touching v3's own front, which
      I-1 says must keep working. Two of them went red immediately, which is the system working.
      `front-report-gate.sh` now runs every fixture through BOTH fronts and requires the same
      answer — the end-to-end twin of `front-diff.sh`, and the one that would have caught the
      constant-pool bug, because it compares OUTPUT rather than trees.

      Observed failing: reverting the const-pool fix gives `DIFFER float-format` and RED.

- [x] **28g — `scala-cli compile` returned STALE CLASSES for a changed source.** After editing
      `Lower.scala` the classpath cache took a new digest, compiled, and handed back a directory
      still holding the previous version — so three gates reported a defect that was already fixed,
      which is the worst kind of wrong answer because it points at working code. A cache miss now
      discards the incremental build directory first: one full compile per source change, which is
      what a source change costs anyway.

**Measured, all four combinations:** uniml front N = 57 on both v3 lanes, v3's own front N = 50 on
both, DIFF 0 and CRASH 0 everywhere — so invariant I-3 holds under either front. Seven v3 gates
green, front agreement 48/48 fixtures and 102/102 corpus (floors raised), `install.sh --dev` clean,
smoke-ci 70/70.

## 29 · The classpath cache was unsound, and my first fix was for the wrong branch

`jar_for` and `uniml_classpath` sit six lines apart in `v3/ssc3` and look like the same idea. They
are not, and the difference is the whole bug.

`jar_for` caches an ARTIFACT FILE keyed on the source digest. The file holds the bytes, so
returning to an earlier source state hits a jar built from exactly that source. Sound.

`uniml_classpath` caches a PATH — and `scala-cli` rewrites that directory on every compile of the
same inputs. Measured: two compiles of one tree in two different states print the SAME directory.
So a digest-keyed cache of it fails in one specific way:

    state A -> compile -> cache[A] = P, P holds A
    state B -> compile -> cache[B] = P, P now holds B     <- shared, and overwritten
    back to A -> HIT on cache[A] = P -> serves P, holding B

Which is exactly an A/B measurement, and exactly the plant-and-revert a gate self-test does. It bit
me on the second: after restoring the constant-pool fix, three gates reported the defect I had just
removed. **A wrong answer that points at working code is the expensive kind** — the natural response
is to go and re-break the thing you fixed.

**§28g's fix was for the wrong branch.** It discarded the incremental build directory on a cache
MISS; the failing case is a HIT. Corrected here with a STAMP — one file recording which digest the
shared directory currently holds, and a hit requires it to match. Returning to an earlier state now
recompiles, which is the right cost: an A/B between two states must compile twice.

Verified by replaying the exact cycle — fixed → planted → reverted gives `inf -inf` / `-inf inf` /
`inf -inf`, where before the third step repeated the second.

**The census, since the question is where else this hides:** `--print-class-path` appears in exactly
one live place in the repository, and it is this one. Everything else either caches an artifact FILE
keyed on a digest (`jar_for`, sound by construction) or does not cache at all and pays a full
`scala-cli run` per invocation (most gates — slow, never stale). So the blast radius is one function,
now closed.

## 30 · The top corpus blocker was mine, one day old — `extern`, 145 cases

`??? is outside SSC3 core Tier 0` led the refusal list at **145 cases against ONE corpus file that
actually writes `???`**. The other 144 were `extern def readFile(path: String): String` — a host
function declaration, which §27's change to tell an abstract signature from `def f(): Unit = ()`
turned into a parse error. The standard library declares these in blocks (20 in `fs.ssc`, 15 in
`os.ssc`), so a program importing such a module refused outright even when it called none of them.

**Three designs, measured, two rejected.** Writing this down because the first two were plausible
and the numbers said otherwise:

1. **The extern's own name as a prim.** N 57 → 59, and the exec lane went to **13 CRASH** while the
   bridge went to **5 DIFF**: v2's plugin fleet answered eleven of those names and v3's executor
   answered none, so the two v3 lanes stopped agreeing and five programs RAN AND PRINTED THE WRONG
   THING. Worse than either lane refusing.
2. **v3's own `__throw__` with the name in the message.** Lanes agree again, but a run-time throw
   carries no source position, and `corpus-report.sh` classifies an unpositioned failure as CRASH —
   rightly, since its rule is that an actionable refusal names a place. Still 13 CRASH.

   The temptation here was to widen the classifier until my own failures looked better. That is the
   move that should make any reviewer suspicious, and it was not taken.
3. **An extern v3 cannot implement IS NOT A FUNCTION.** `Lower` drops it, so the declaration costs
   nothing and a CALL is refused at the call site by the ordinary unknown-name path — positioned,
   actionable, identical on both lanes. **N = 59 on both, DIFF 0, CRASH 0.**

- [x] **30a — one marker, and the SITE says what it means.** The extern first got its own
      `__extern__` marker, and the differential caught it within the hour: v3's own front has
      spelled a body-less def `__abstract__` since long before, so the two fronts printed different
      trees for three corpus cases. Now there is one marker meaning "no body was written", and
      whether that is an abstract method or a host function is decided by the SITE — `p.defs`
      versus a member list, a structural distinction rather than a second spelling kept in step by
      hand.

- [x] **30b — `extern` was a bare name in v3's own front,** exactly as `sealed` was in §27e: the
      modifier fell through to the expression parser and `node-basic` printed `(do (name
      "extern"))`. Same class, same fix, found the same way.

- [x] **30c — `???` throws where it is REACHED,** as it does in Scala. Refusing the file at parse
      time is stricter than the language and blocks programs whose `???` sits in a branch nobody
      takes — a stub on a case the test never constructs is the ordinary use.

**Measured:** N = 59 on both v3 lanes with the uniml front (was 57) and 50 with v3's own, DIFF 0 and
CRASH 0 in all four combinations. Front agreement 48/48 fixtures and **105/105 corpus** — up from
102, and total again after 30a and 30b. Seven gates green.

**What leads the refusal list now:** `unknown name` (55) and `call to unknown function` (30) — which
are no longer one construct but a long tail, then typed patterns (22), `given … with` (13) and
`effect` (10). The single-cause blockers are gone.

## 31 · `scala-cli` is gone from v3 — because invariant I-1 made it removable

v3 shelled into `scala-cli` for every artifact: the kernel jar, the v2 bridge jar, and the second
front's classpath. It does not any more, and the reason it CAN not is the charter's own first
invariant: `v3/src` and `v2/src` declare **zero dependencies**, and dependency resolution is the
only thing `scala-cli` offers over the compiler itself.

Measured before deciding: `dotty.tools.dotc.Main` on the pinned 3.8.3 compiles the kernel in **4.0 s
against `scala-cli`'s 5.4 s**, and `cs fetch` is 0.02 s from cache.

- [x] **31a — the version is READ, not written down twice.** The `scalac` on this host is **3.7.2**
      while every source says `//> using scala 3.8.3`. My first measurement of "can we drop
      scala-cli" used that launcher, compiled the kernel with the wrong compiler, and said nothing —
      which is how version drift survives. `SCALA_V` now comes out of the `//> using` line, and so
      do the compiler options.

- [x] **31b — content-addressed output closes §29 STRUCTURALLY.** `classes_for` chooses the output
      directory itself and puts the digest in its NAME, so it is written once and never rewritten.
      The stamp file §29 added was a guard against a directory `scala-cli` owned and overwrote;
      owning the directory makes the failure unrepresentable instead of detected. Returning to an
      earlier source state now finds that state's own directory intact.

- [x] **31c — `corpus-report.sh` stopped building its own copy.** It packaged two assemblies per
      run, so it compiled the same sources a second time and measured artifacts nothing else had
      ever run. It asks the driver now (`ssc3 __kernel-cp`, `__v2-cp`) and shares the cache.

- [x] **31d — the gate EMULATES the host, and its first version proved less than it claimed.**
      `toolchain-gate.sh` builds all three artifacts with `scala-cli` unavailable — a grep would
      pass on a call from a branch the pattern misses, and a dependence that is merely unused looks
      identical to one that is absent until you take the thing away.

      The first version removed every `PATH` directory holding a `scala-cli`. **`cs` lives in the
      same Coursier directory**, so that took the RESOLVER away too, and the build only worked
      because a toolchain classpath was already cached: green for the wrong reason. Found by
      planting a version drift and watching it fail with "coursier is needed" instead of the
      version message. It shadows `scala-cli` with a failing shim now, clears the toolchain cache
      as well, and asserts that `cs` is still reachable so that RESOLUTION is under test.

      Observed failing: a driver that calls `scala-cli` again (three checks red), and a pin that
      stops reading the source (`the kernel classpath does not carry scala3-library 3.8.3`).

- [x] **31e — `setup.sh` installs coursier.** `scala-cli` bundles its own copy, which is why nothing
      needed it before. Leaving that out would have made a fresh checkout unable to build v3 at all
      — a gap created by this commit, so it is closed by this commit.

**What still needs a real toolchain, stated plainly:** UniML is an sbt project and the second front
needs its classpath, so `v3/uniml-classpath.sh` still runs sbt. That dependency is real and stays;
`scala-cli` is not part of it. The end of the story is self-hosting — the kernel is written in the
Scala ∩ ScalaScript-2 portable subset precisely so that `ssc` can compile it — and at N = 59 of 362
that is a long way off.

**Measured:** eight gates green, N = 59 on both lanes with DIFF 0 and CRASH 0, agreement 48/48 and
105/105, `install.sh --dev` clean, smoke-ci 70/70.

## 32 · Typed patterns and `Either` — N 59 → 66, and the differential grew a second guard

Two constructs, each blocking a large block of the corpus, and both closed by matching the
REFERENCE rather than inventing a rule.

- [x] **32a — `case s: String =>`, the 138-case blocker.** The reference front emits
      `__isTag__(value, name, -1)` (`ssc1-lower.ssc0:3559`) and **v2 already implements it**
      (`Runtime.scala:1683`), so the bridge lane came for free and the executor implements the
      identical vocabulary rather than a parallel one. That is the same argument the map and set
      primitives carry, and it is why the two lanes agree here without a second thought.

      The test is nominal and FLAT, with no subtype graph — and its quirks are copied deliberately,
      because the frozen goldens encode the reference's answers, not an ideal semantics:
      `List`/`Seq`/`Iterable` all name a cons cell, `Option` names `Some`/`None`, and the four JVM
      exception supertypes match anything, because a caught error carries the thrown class's simple
      name and users catch by supertype.

      One thing v3 needed that v2 did not: **the tag is an INDEX here**, not a string, so the type
      table is consulted. Comparing the index to the name would have made every type test false —
      a pattern that silently never matches.

      The type is taken by its HEAD: `List[Int]` tests `List`. A type argument carries no runtime
      evidence, and passing the whole text would compare a tag to the literal `"List[Int]"`.

- [x] **32b — `Either`. Fifty-three cases on one name.** `Right`/`Left` are language-provided
      constructors exactly as `Some`/`None` are, and were missing for exactly the reason `Some`
      once was: nothing in the kernel names them, so nothing put them in the table.

- [x] **32c — the payoff was +7, not +138, and that is the honest read.** The 138 cases hit the
      typed-pattern wall FIRST; behind it stood others. The blocker histogram is the evidence: the
      typed-pattern line is gone entirely, `call to unknown function` went 176 → 59 as `Right`
      landed, and the freed cases moved forward into `unknown name '_'` — the placeholder lambda,
      `xs.map(_ * 2)`. **That is the next single-cause lever.**

- [x] **32d — THE GATE'S FLOOR WAS NOT A GUARD, and it took this commit to show it.** When typed
      patterns landed, the cases both fronts can print went 105 → 219, agreement went 105 → 145 —
      and **disagreements went 0 → 74** while the gate stayed green. A floor on the good number
      says nothing about the bad one, and a ratio would have the same hole in the other direction:
      it improves whenever the denominator grows.

      Two numbers now, two directions, both non-regressing — the rule I-5 already applies to the
      corpus N, applied to what the differential actually measures. Observed failing at a ceiling
      of 73.

      **The 74 are newly EXPOSED, not newly broken.** They are files that could not be parsed at
      all until this commit, and the disagreement is about where an indented block ENDS: v3's own
      front closes it before a following `if`, UniML nests the `if` inside. One of the two is wrong
      about the offside rule and the differential cannot say which — that needs the reference as a
      third opinion, and it is its own piece of work.

**Measured:** N = 66 on both v3 lanes with the uniml front (was 59) and 52 with v3's own (was 50),
DIFF 0 and CRASH 0 in all four combinations. Eight gates green, `install.sh --dev` clean, smoke-ci
70/70.

## 33 · The placeholder lambda — 97 cases on one underscore. N 66 → 69

`xs.map(_ * 2)`. The rule is the reference front's, verbatim (`ssc1-front.ssc0:984`): an ARGUMENT of
a call that contains a `_` but is not a bare `_` becomes a lambda over that argument, and a bare
`f(_)` is left alone because that is eta-expansion, a different thing.

- [x] **33a — each `_` is a DISTINCT parameter, left to right.** `_ + _` is `(a, b) => a + b` with
      arity 2, not one parameter used twice. The reference records this as a fix (K62.29) and names
      what it broke — `foldLeft`/`reduce` failing with "arity: 1 expected, 2 given". Copying the
      rule rather than re-deriving it is what keeps the bridge lane and the frozen goldens in
      agreement, and it is why `xs.foldLeft(0)(_ + _)` works on both lanes on the first try.

- [x] **33b — ONE pass in the lowering, not one per front.** Both fronts already hand `_` over as
      an ordinary name, so the desugaring has a single home. Two fronts implementing the same
      desugaring is two implementations that will disagree — the failure this project keeps
      arranging its apparatus to catch, so not arranging it is cheaper than catching it.

      The descent says exactly which shapes are searched — binary, prefix, call, and the receiver
      of a method call. Not a block, not an `if`, not a lambda body: a `_` there belongs to
      something else, and the reference does not look either.

- [x] **33c — `toChar` was the case behind it, and I got it wrong first.** `int-tochar-codepoint`
      reached the executor for the first time and crashed on a missing method, so CRASH went 0 → 1
      and the floor said so. I implemented it as a `VChar` — which read `65.toChar` as `A`
      correctly and diverged from the bridge on the very next method, `String.toInt: invalid
      integer`.

      **The reference returns a one-character STRING** (`v2/src/Runtime.scala:2000`,
      `StrV((n & 0xffff).toChar.toString)`), and the corpus case depends on it: it prints
      `65.toChar + 8364.toChar` as `A€` and `List(65,66,67).map(_.toChar).mkString` as `ABC`, both
      of which are string behaviour. Masking to the low 16 bits is the reference's rule too, and a
      real one — `toChar` is a UTF-16 CODE UNIT, so it wraps rather than failing.

      The lesson is the one this session keeps paying for: **the reference is the specification,
      and a plausible reading of the name is not.** A one-line probe on both lanes found it
      immediately; a corpus run would have found it as a DIFF much later.

- [x] **33d — a diagnostic that sent the reader to the wrong lane.** The executor's
      method-not-implemented message ended "`ssc3 run` uses the v2 runtime", which stopped being
      true on 2026-08-07 when `run` switched to v3's own executor. It now names the executor and
      points at `ssc3 run --bridge`. A stale diagnostic is worse than a terse one.

**Measured:** N = 69 on both v3 lanes (was 66), DIFF 0 and CRASH 0. Eight gates green,
`install.sh --dev` clean, smoke-ci 70/70. Front agreement unchanged at 145 of 219 with 74
disagreements — the offside-rule question from §32d is untouched and is the next thing to settle,
because it is a CORRECTNESS question rather than a coverage one.

## 34 · The 74 front disagreements are ZERO — and the last two were both mine

§32d recorded 74 corpus cases where the two fronts printed different trees, and said it needed the
reference as a third opinion because the differential cannot say which side is wrong. That is what
happened, from both ends:

- A sibling agent took the UniML half (`16ef08948`): the dialect bound an `else` unconditionally, so
  an `if` written as the last statement of an indented block swallowed the `else` belonging to the
  block's OWNER. A wrong ANSWER, not a loss — `f(2)` evaluated to 0 where the reference says 2 —
  which is why none of UniML's own gates saw it: diagnostic count, coverage and losslessness were
  green throughout. **All 74 were `scljet-*` cases importing ONE `scljet/sql.ssc`**, which spells
  that shape once.

- [x] **34a — the other half was v3's, and it was filed FOR me.** `v3/BACKLOG.md` recorded it with
      the correction that §5b's premise — "all remaining differences are on UniML's side" — was
      wrong. `parsePostfix` claimed "a newline is its own token, so a `(` opening the next line is a
      new statement and is not reached here". **False whenever the expression ended with an indented
      block**: closing it consumes the newline AND the dedent, so `while … do ⏎ … ⏎ (0 :: Nil) ++ xs`
      read as applying the `while`'s result. Applying the result of a `while` is not a thing this
      language has, which is what made the READING wrong rather than the tree merely unusual.

- [x] **34b — two mistakes on the way to a three-line fix, each worth more than the fix.**
      - **Identity by reference did not hold.** I found the end-of-expression line by walking the
        token list until it reached the remaining suffix, compared with `eq` — and `dropDedents`
        removes tokens from the MIDDLE, so the list is rebuilt and `eq` never matched. The helper
        returned "unknown" every time and, degrading permissively as designed, changed nothing. The
        design saved it: a wrong assumption became yesterday's behaviour rather than a silent
        rejection of valid code, and the measurement said so on the next run.
      - **A guard maintained only where it is read is wrong everywhere else.** I updated the line in
        the `(` branch alone, so after `Dataset.of(⏎ … ⏎).reduceByKey(a)(b)` it still held the line
        of `Dataset` and a legitimate second argument list was refused. One case, caught by the
        differential in the same run that confirmed the first fix.

- [x] **34c — and one that was purely mine: a `case object` LOST ITS METHODS.** The projection's
      `isCase` arm was written for the bare-marker shape and passed `Nil` for the members, so
      `case object NoFile extends SqliteFile: def readAt…` projected as an empty class. Two corpus
      files disagreed over it. A dropped member is exactly the failure this projection exists to
      avoid: the tree stays well-formed and smaller, so it lowers, runs, and answers a call by
      falling through to nothing.

**Measured:** front agreement **219 of 219, zero disagreements** (was 145 of 219 with 74). Both
guards tightened — floor 219, ceiling 0. N unchanged at 69 on both v3 lanes with DIFF 0 and CRASH 0;
v3's own front rose 52 → 55. Eight gates green, `install.sh --dev` clean, smoke-ci 70/70.

**The differential paid for itself here.** Every one of these four defects was a WRONG ANSWER that
each side's own gates called green, and each was found by two independent implementations printing
the same program and disagreeing.

## 35 · A sibling's find, checked against v3 — and v3 had the same defect one alphabet over

`BUGS.md` gained `an-undefined-name-in-a-pattern-means-three-different-things`: `case Nope =>` where
`Nope` is undefined. Scala 3 rejects it at compile time; of the three v1/v2 lanes, int and native
answer a silent `NO MATCH` and js throws a `ReferenceError` at run time — and only when the `match`
is reached, so a pattern in a cold branch ships and fails in production. The entry's own conclusion:
*a fix belongs in the fronts' resolution step rather than per backend.*

- [x] **35a — v3 already answers the way the entry asks.** Both lanes, both fronts:
      `unknown constructor 'Nope' in a pattern`, positioned, before anything runs. Recorded in the
      entry, because a recommendation with a working implementation behind it is a different thing
      from a proposal.

- [x] **35b — AND THE SAME DEFECT WAS IN v3, one alphabet over.** The parser decided
      constructor-versus-binder with `>= 'A' && <= 'Z'` — ASCII only — so every non-ASCII name read
      as lowercase and `case Éric =>` was a BINDER that matched everything and printed `BOUND 1`.
      The quiet, dangerous answer this entry warns about, in the lane that otherwise gets it right.
      The sibling's probe was about Unicode and reported no divergence; the ASCII control is what
      carried their defect, and the Unicode case is what carried mine.

      `Character.isUpperCase`, chosen by MEASUREMENT rather than preference: compared against
      `UniAlphabet.isTypeNameStart` — the curated table UniML's front decides by — over every code
      point in the BMP, the two disagree on **zero**. So the fronts agree by construction, not by
      two tables being kept in step by hand.

- [x] **35c — the corpus could not have found this**, which is why it needed fixtures.
      `unicode-capitalisation.ssc` runs on both fronts and both lanes; `unicode-unresolved-ctor.ssc`
      must be refused. Observed failing with the ASCII rule put back.

- [x] **35d — AND THE FIXTURE HALF OF THE GATE HAD THE SAME HOLE I FIXED IN THE CORPUS HALF
      YESTERDAY.** Adding a fixture raises the denominator: agreement was 48 of 49, the floor was
      48, the new fixture DIFFERED, and the gate said GREEN. Twice in two days is a shape, not luck
      — a floor on the good number is not a guard, wherever it appears. Both halves now carry a
      floor and a ceiling; observed failing on both at once.

**Measured:** N = 69 on both v3 lanes, 55 on v3's own front, DIFF 0 and CRASH 0 everywhere. Front
agreement 49/49 fixtures and 219/219 corpus. Eight gates green, `install.sh --dev` clean, smoke-ci
70/70.

## 36 · N 69 → 182. Five defects, one chasing the next, and every one a WRONG READING

`call to unknown function 'jvmVfsShmRead'` led the refusal list at **113 cases against one name**.
`scljet-address-read.ssc` does not contain that name: `v1/runtime/std/scljet/jvm-vfs.ssc` calls it on
line 136, and 113 corpus cases import that module without going near it. Each fix exposed the next.

- [x] **36a — an `extern` DECLARATION cost the whole program.** §30 dropped an unimplementable host
      function from the table, so a CALL was refused at the call site — right for a program that
      reaches one, wrong for a module that merely defines a function containing one. The declaration
      stays a function now, with a body that throws.

- [x] **36b — the throw needed its POSITION, and that is why §30 rejected this shape.** An
      unpositioned run-time failure is classified CRASH, rightly: a refusal a reader can act on
      names a place. `Diag.at` joins a path to a message that already carries `line:col` with no
      space, so `file.ssc:136:18: …` reads as one position. Measured: the same failure counted 13
      CRASH without it and 0 with it.

- [x] **36c — the dispatch emitted INVALID IR, and 113 cases came through one line.** A method call
      was claimed by any class having a method of that NAME, without checking the ARITY. `open.lock`
      is a FIELD read on a `MemoryHandleState`; an unrelated class has a one-argument `lock` METHOD;
      the arm emitted `JvmSqliteFile.lock(receiver)` — one argument where the flattened method takes
      two — and the verifier refused the module. **A name is not a signature**, and this file
      resolves methods by name across every class precisely because there is no type checker, so
      the arity is the only part of the signature available and has to be used.

- [x] **36d — and the same confusion in the other direction: 76 cases.** A name can be a method on
      one class and a field on another — `name` and `sectorSize` both are, across `scljet/`. Whichever
      arm claimed the call left the other's classes with no arm, so their receivers fell to the
      dynamic default and the executor answered `method 'name' … is not implemented`. One switch
      now, arms from BOTH sources, the tag decides — which is what this dispatch was always for.

- [x] **36e — `<` on Strings did not exist. 30 cases.** The comparison arms covered `VInt` and
      `VFloat` and nothing else, so `"alice" < "carol"` died with `Lt on String alice and String
      carol` — every one of them a `scljet` SQL query with an ORDER BY. `compareTo`, as the
      reference spells it (`scmp`), not a locale collator: a program that sorts differently on two
      machines is worse than one that sorts unexpectedly on both. Chars too, since a char is an
      integer on both lanes.

- [x] **36f — an arity mismatch is caught at LOWERING now, with a position.** `extern def
      pathJoin(parts: String*)` is a vararg host function and v3 has no varargs, so the declaration
      says one parameter and calls pass three or four. Emitting a call the verifier will reject is
      a defect in `Lower` whatever the cause: the lowering knows both numbers AND the position, the
      verifier knows neither.

      **It was too strict on its first run and the corpus said so in the same measurement.** A
      ZERO-ARITY def applied to arguments is legitimate — `def mkAdd = (a) => a + 1` then `mkAdd(3)`
      calls `mkAdd` with nothing and applies what it returns — and refusing it broke
      `parenless-def-value`, which had been passing. Exempted, and N went back up.

- [x] **36g — THE TWO LANES DISAGREED ON FIVE CASES, and reachability is what separates them.**
      A program that really calls a missing host function died at run time: the executor printed a
      clean positioned refusal, the bridge let v2 throw an uncaught `SscThrow` — so one lane read
      UNSUPPORTED and the other read a wrong ANSWER. Both halves of the problem had already been
      paid for: refusing the DECLARATION costs 113, refusing nothing costs 5.

      A reachable host gap is refused at BUILD time, from a call graph rooted at the entry.
      Deliberately UNDER-approximated — direct calls only, no dynamic dispatch — because
      over-approximating would mark a host function reachable through any same-named method and
      refuse the 113 again. What it can miss still fails at run time exactly as before: nothing gets
      worse, some things get better.

- [x] **36h — `Either.toOption`**, one case, `Right(v)` → `Some(v)` and `Left(_)` → `None`.

**Measured:** **N = 182 on both v3 lanes** (was 69), DIFF 0 and CRASH 0 on both; v3's own front 55 →
**164**. Front agreement 49/49 fixtures and **225/225** corpus, floor raised. Eight gates green,
`install.sh --dev` clean, smoke-ci 70/70.

Every defect here was a WRONG READING rather than a missing feature — a field read taken for a
method call, a method resolved by name without its arity, a comparison that silently had no arm.
The corpus found them only because each earlier fix let the next one be reached.

## 37 · Self-hosting: 0 of 16 files → 7, then the daily constructs. N 182 → 186

The corpus's single-cause blockers are gone — the top name is now `Dataset` at 12, then 5, 5, 3 —
so the next measurement worth taking is the one about writing v3 IN v3. It moved a long way:
**v3 reads 7 of its own 16 kernel files, where it read 0.** The nine that fail name small things,
and three of them are what a person writes daily.

- [x] **37a — an import link had to BEGIN its line.** The scanner read `[a](std/x.ssc)` anywhere,
      including inside a doc comment and inside a STRING LITERAL — so four corpus cases failed with
      `cannot find the import 'one.ssc'` over a markdown sample in a string, and `Loader.scala:44`
      was refused because this very comment's own example was read as an import.

      **Measured before changing the rule:** of 382 import lines across the corpus and the standard
      library, every one starts with `[`. The lines that do not are prose and string literals —
      exactly the false positives.

- [x] **37b — `n += 1`, and it needed BOTH fronts.** UniML has a `CompoundAssign` node; v3's own
      lexer takes operator characters by maximal munch, so `+=` arrived as one operator token and
      reached the expression parser as a BINARY operator — `(bin "+=" …)`, which is not a thing that
      can run. The differential caught it the moment UniML learned the construct, which is what the
      ceiling added in §35d is for.

- [x] **37c — `1 to n` / `0 until n` are a BINARY OPERATOR in the tree.** My first version emitted
      the desugared prim from the projection, and the fronts printed different trees at once: v3's
      own front already reads `to` as the operator it looks like. The desugaring belongs in the
      lowering — one implementation for two fronts — and it builds the same cons LIST the reference
      does rather than a lazy range.

- [x] **37d — four arms I added were already there.** The compiler called my `VChar` comparison
      arms unreachable: two arms further down already normalise a char to an integer, so chars had
      ordered correctly all along. Recorded rather than quietly deleted — an arm added where one
      exists is the same misreading as an arm missing where one is needed, and the compiler is the
      only reason this one cost nothing.

- [x] **37e — AND IT FOUND A WRONG ANSWER ON BOTH LANES.** `List(1,2,3).foreach { i => n = n + i }`
      leaves `n` at 0 where the reference says 6. v3's lambda lifting passes captures as leading
      PARAMETERS, so a captured `var` is copied and the assignment mutates the copy. **Both v3
      lanes agree and both are wrong**, so neither the parity gate nor the front differential can
      see it — the reference is what caught it. Filed as
      `v3-loses-a-mutation-to-a-captured-var`: the fix needs captured mutable locals boxed into
      cells (v2 already exposes `cell.new`/`get`/`set`, so the vocabulary is on both lanes) and an
      analysis of which locals need it, which is not end-of-session work.

**Measured:** N = 186 on both v3 lanes (was 182), DIFF 0 and CRASH 0. Front agreement 49/49 fixtures
and 228/228 corpus, floor raised. Eight gates green, `install.sh --dev` clean, smoke-ci 71/71.

## 38 · The captured `var` is boxed — and a one-element array was already in the vocabulary

§37e filed `v3-loses-a-mutation-to-a-captured-var`: `List(1,2,3).foreach { i => n = n + i }` left `n`
at 0 where the reference answers 6, on BOTH v3 lanes, so nothing in v3's own apparatus could see it.
Fixed here.

- [x] **38a — the box is a ONE-ELEMENT ARRAY, not a new prim family.** `Array(v)`, `n(0)` and
      `n(0) = v` are shapes both lanes already have — `NewArr`, an application, `ArrSet` — so this
      needed no new instruction, no new prim, and nothing added to the vocabulary the bridge shares
      with v2. A `cell` family would have been a second way to say the same thing.

- [x] **38b — only names a LAMBDA ASSIGNS TO from the outside.** Boxing every capture would cost
      every closure an indirection for a mutation that never happens; boxing every `var` would box
      loop counters no lambda sees. The binding rules mirror `freeVars` exactly, because a name
      shadowed by an inner binder is a DIFFERENT name.

- [x] **38c — and only names DECLARED HERE, which is the difference between working and broken.**
      A top-level `var` is a module GLOBAL in v3 — already a cell, already correct through a
      closure. Boxing one rewrote its reads and writes while its DECLARATION stayed a global
      assignment, so the box was never created and `arrays.ssc` died with `array write on ()`. The
      front gate caught it on the first run. The fixture now covers BOTH halves: a local that must
      be boxed and a global that must not.

- [x] **38d — the corpus never covered this at all.** N did not move — 186 before and after — so
      the fixture is the only thing standing between the fix and its silent return. Observed
      failing with the boxing removed: `expected [6/10/ab/30/8] got [0/0//30/8]`.

- [x] **38e — and the fixture found a THIRD instance of the same parser bug.** `println(q)` on the
      line after a `for … do` block read as `<the for> println (q)` — an identifier used INFIX. The
      comment there said the guard was unnecessary because "statement boundaries are real tokens",
      which is the same false claim the `(` continuation carried in §34a and for the same reason:
      closing an INDENTED BLOCK consumes the newline AND the dedent. Third time, so it is a shape:
      **anything that reads "the next token cannot be adjacent" is wrong after a block.**

- [x] **38f — `origin/main` was RED before I touched it, and I measured that in a clean worktree
      rather than assuming.** The by-name work landed with `front-diff.sh` at 234 of 263 and 29
      disagreements: UniML's projection emits the by-name THUNK, v3's own front passes the block
      eagerly and `rewriteByName` wraps it in the lowering — one feature in two places. Filed in
      `v3/BACKLOG.md` under the claim that owns it, with the note that a thunk of a thunk would be a
      wrong ANSWER rather than a printed difference. The ceiling was NOT lowered to accommodate it.

**Measured:** N = 186 on both v3 lanes, DIFF 0 and CRASH 0 (the corpus grew to 366 cases). Fixtures
50/50. Seven of eight gates green; `front-diff` is red on the pre-existing by-name divergence,
identically to `origin/main`.

## 39 · A file claim beats a module claim, and I was on the wrong side of that

`ssc3-effect-protocol` was opened today at 09:42 with FILE claims on `Lower.scala`, `Parser.scala`,
`Exec.scala`, `Ast.scala`, `AstText.scala`, `Loader.scala`, `Main.scala`, `Text.scala`,
`TailCalls.scala`, `Verify.scala` and `Ir.scala` — eleven of the sixteen files in `v3/src`. My own
`ssc3-core` held `v3` as a MODULE scope and I kept editing them: boxing in `Lower.scala`, the infix
rule in `Parser.scala`, `__isTag__` and ranges in `Exec.scala`.

`specs/claim-mutex.md` says a file scope is finer than a module scope, so theirs is the one that
holds. **Neither the pre-push guard nor either of us noticed** — the first visible symptom was a
merge conflict in `Lower.scala`, where their by-name/effects work and my boxing pass met. I resolved
it keeping both, which was right, but resolving a collision is not the same as not having one.

- [x] **39a — `ssc3-core` is narrowed to what they do not hold**: `v3/uniml`, `v3/specs`,
      `v3/tests/front`, the five `v3/src` files outside their set (`BridgeV2`, `Front`, `Lexer`,
      `Source`, `project`), the driver, the gates and the board files. The module scope is gone.

- [x] **39b — the red gate on main stays theirs, and that is the point of saying so.** `front-diff`
      reads 234 of 263 with 29 disagreements on `origin/main` itself — measured in a clean worktree,
      not inferred — because by-name is implemented in two places: UniML's projection emits the
      thunk and `rewriteByName` wraps eagerly in the lowering. Filed in `v3/BACKLOG.md` under their
      claim. Fixing it would mean choosing where by-name lives, which is their decision to make.

**What is left in my scope, honestly:** the UniML projection, the driver, the gates, the specs, and
five kernel files that are mostly leaves. The next language work in `v3/src` belongs to whoever
holds those files until that claim is released.

## 40 · The alphabet was routed through the host for one day — and the spec had banned it

Sergiy asked what the problem with the copy of the alphabet in `v3/Chars` is. Reading
`20-core-language.md` §3 to answer it turned up that the problem was mine: **§3 bans host character
classification by name** — `java.lang.Character` — and `Chars.isUpperStart` had called
`Character.isUpperCase` since §35b.

The ban is not tidiness. Route an alphabet through the host and the same source lexes differently on
the JVM, on JS and on the v2 VM, so the language's SYNTAX becomes host-dependent. And the
measurement I justified the host call with — "agrees with UniML's table on every BMP code point" —
was taken on one JVM, which is exactly the guarantee the rule says not to rely on.

- [x] **40a — the 606 ranges are copied into `Chars`, and the copy is deliberate.** Reaching into
      UniML would break I-1: the kernel builds and runs with UniML absent, and every gate depends on
      it. So there are two copies, which is the duplicated-helper shape this repository keeps paying
      for — unless there is a check.

- [x] **40b — the one-comparison rule was the other candidate, and it was rejected on a cost the
      author pays directly.** `A`–`Z` or anything ≥ U+0080 mirrors `isIdStart`, needs no table, and
      errs LOUD (an unknown constructor rather than a binder that matches everything). It also makes
      `case имя =>` a constructor, so Cyrillic and Greek names stop binding in patterns. A language
      that cannot bind a Russian name is worse than 606 ranges of data.

- [x] **40c — `toolchain-gate.sh` sweeps all 65536 BMP code points** comparing the two alphabets,
      and reports how many differ from Java's — which is the check §3 asks for in as many words.
      Observed failing on a ONE code point drift: `U+00D8 v3=false uniml=true`.

- [x] **40d — an `enum` case could not carry a FUNCTION type.** `case L(step: () => Option[(V, V)])`
      stopped at the `(` with `expected type, found '('`, while the identical field in a `case class`
      parsed — because the two used different type readers, one taking an identifier and the other
      the full type text. One reader now. It is `Exec.scala:55` in v3's own kernel, so it is one of
      the nine files self-hosting still trips on.

- [x] **40e — a multi-arm `catch` is WRITTEN AND NOT LANDED, and the measurement says why.** The
      projection ignored the type on a single typed arm — `case e: IllegalStateException =>` caught
      everything, where the reference answers `match: no matching case`. Making the type mean
      something turned a shared wrong answer into a LANE DIVERGENCE, because
      `Exec.scala:482` binds `VStr(e.message)` where the bridge binds the thrown value. Measured with
      the change in: N 186 → 185, DIFF 0 → 1, CRASH 0 → 1. `Exec.scala` belongs to
      `ssc3-effect-protocol` and `Try` is what that claim is redesigning, so it is filed
      (`v3-executor-catches-a-string-where-the-bridge-catches-the-value`) with the order of
      operations: fix the one line, then the projection stops ignoring the type in one move.

**Measured:** N = 186 on the exec lane, CRASH 0. **DIFF 1 is not mine** — `parameterless-def-local`
fails identically with my changes reverted, so it arrived with main; checked rather than assumed.
Fixtures 50/50, seven gates green, `install.sh --dev` clean, smoke-ci 72/72.

## 41 · The alphabet is ONE file now, shared by v3's kernel and UniML

Sergiy asked whether the table could be a single copy used by both. It can, and the shape is a
**source directory rather than a dependency in either direction**: `alphabet/src/Alphabet.scala`.

- [x] **41a — v3's kernel keeps invariant I-1, and that is measured rather than argued.** `v3/ssc3`
      compiles `v3/src` and `alphabet/src` together, so the kernel is still "a Scala compiler plus
      files from this repository" — no jar, no sbt, nothing to resolve. Checked directly:
      `dotc v3/src/*.scala alphabet/src/*.scala` with an empty classpath builds, and the result runs
      a program. Self-hosting is not made harder by one more file.

- [x] **41b — UniML gains no dependency on v3**, and keeps travelling as its own tree plus this one
      small directory. The file is `CrossType.Pure`-safe, so it compiles on UniML's JS lane too —
      which is why it contains no host calls at all.

- [x] **41c — IT HAD TO BE SAID IN TWO BUILDS.** `uniml/build.sbt` and the root `build.sbt` define
      the same project over the same sources. Adding the directory to the first alone left the
      second compiling a `UniAlphabet` that referenced a package it could not see — and the error
      was confusing, because `show uniml/Compile/unmanagedSources` (run from `uniml/`) listed the
      file while the failing compile was the other build's. Two build definitions over one source
      tree is the duplicated-helper shape wearing a different hat.

- [x] **41d — the gate changed, because comparing the two names would now be comparing one table
      with itself.** That is the vacuous-gate shape this repository has paid for twice, so the
      check asserts three different things: both names answer alike over the BMP (either could be
      mis-delegated — a broken delegation gives 1143 disagreements), exactly ONE definition of the
      table exists, and how many points we differ from Java on. All three observed failing.

      The single-copy check was wrong twice on the way. Grepping the NAME reported two copies
      because a UniML test mentions the table in a sentence. Then `grep -l` counted FILES, so a
      second table planted in the SAME file went unnoticed — found by planting one. It counts
      DEFINITIONS now.

- [x] **41e — and the justification in §3 is corrected.** UniML's own measurements draw a line I had
      blurred: case FOLDING diverges on the same runtime under `-Duser.language=tr` and they proved
      it in the locale, while CLASSIFICATION needs a different runtime and no divergence has been
      observed. The rule stands, but for the honest reason — an alphabet should be a property of the
      language, not of where it runs — rather than an observed failure it never had.

**Measured:** UniML 213/213, seven v3 gates green, `install.sh --dev` clean, smoke-ci 72/72,
`v3/src` + `alphabet/src` builds and runs with no UniML present.

## 42 · A `case class` inside an `object` is hoisted — `BridgeV2.scala` reads

`v3/src/BridgeV2.scala` and `Text.scala` both declare a `case class` inside their object, and the
projection refused the whole file for it. v3 has no nested classes and an `object` is a namespace
for `def`s and `val`s rather than a scope for types, so there is no nesting to preserve: the class
is hoisted to the top level under its PLAIN name.

- [x] **42a — the plain name is what makes it usable, and its cost is refused rather than
      resolved.** Every reference inside the object writes `Cursor`; qualifying the declaration to
      `Text.Cursor` would mean rewriting those references in patterns and constructor calls — a
      great deal of machinery for a shape the corpus uses four times. What plain names cost is a
      COLLISION, so two classes of one name are now a positioned refusal. `Lower` resolves a
      constructor by name and would otherwise take whichever it found first, reading one class's
      fields off the other's shape — a wrong answer with nothing to point at.

      The check covers EVERY class, not only hoisted ones: an `enum` case can collide with a class
      just as easily, and nothing was watching that either.

- [x] **42b — the count did not move, and the reason is worth recording.** Self-hosting reads 9 of
      17 before and after. `BridgeV2.scala` opened; `Ir.scala` closed, 31 minutes earlier, in
      `e36d6e5df` — a sibling's `performing` uses `{ case … => …; case _ => () }`, so the file
      joined the semicolon set. Checked with `git log -S` rather than assumed, because a flat number
      hiding a +1 and a −1 is exactly how a regression gets attributed to whoever measured last.

- [x] **42c — `Text.scala` moved one construct further** and now stops at a typed `catch` arm — the
      change that is written and blocked on `Exec.scala:482` (§40e). So that one line unblocks a
      kernel file as well as the corpus cases.

- [x] **42d — the fixture broke the differential, and the fix is a DECLARATION rather than a
      loosened rule.** `front-diff` requires v3's own front to print every fixture, which was right
      while the two had the same coverage and is not any more: the uniml front is ahead BY DESIGN,
      and `object-nested-class` is the first fixture only it can read. A `.uniml-only` marker beside
      the fixture makes that state declared instead of discovered, an UNMARKED refusal is still a
      failure, and the count carries its own ceiling — the two fronts drifting apart is the opposite
      of what this gate is for.

**Measured:** seven v3 gates green; the differential's fixture half is 50 of 51 with 1 declared
uniml-only. N = 186 of 367 with CRASH 0 and DIFF 2 — `effects-handler` and
`parameterless-def-local`, both from the effects claim's work and neither touching nested classes,
checked by reverting my change rather than by reasoning about it.

The differential's CORPUS half stays RED on `origin/main` at 234 of 270 — all 36 are the `actors-*`
by-name family owned by `ssc3-effect-protocol`, and the count grows as their work lets more cases
parse rather than because anything regressed. The ceiling was not raised to accommodate it.

`smoke-ci` read 72/73 on the run alongside these measurements, on `ci-status-guard` — which passes
standalone and queries CI status over the network. Recorded as a flake rather than claimed green.

## 43 · The corpus report: 600s → 217s, and the measurement said something I had not guessed

Sergiy asked what could be done about the report's speed. Measured before touching anything, which
changed the answer:

| | |
|---|---|
| one case, alone | **0.24 s** average, 0.42 s worst |
| the report's FIXED cost | ~1 s (`--limit 1`) |
| 120 cases | 79 s — **0.65 s each** |
| 240 cases | 442 s — **1.84 s each** |

**The per-case cost GROWS.** The corpus is alphabetical and the later `scljet-*` family re-parses a
large import closure every time, so the tail dominates. And the host has 14 cores while the report
was using 1.2 of them — so the fix is not "make a case faster", which is kernel work in someone
else's files, but "stop running them one at a time", which is this script.

- [x] **43a — a pool of background jobs IN THIS SHELL, not `xargs bash -c`.** The first version used
      xargs and every case "crashed": `export -f` does not carry ARRAYS, so `SSC3RUN` arrived empty
      in the subshell and the report read **240 CRASH** — a wrong answer that looks exactly like a
      catastrophic regression. Measured immediately, which is the only reason it cost a minute.

- [x] **43b — per-case temporary files.** The serial version shared one `$WORK/o` and `$WORK/e`;
      two concurrent cases would have overwritten each other's output and the verdicts would have
      been noise that still added up to a plausible number.

- [x] **43c — half the cores, and an escape hatch.** Each case is a JVM, and the deep-recursion
      cases sit on the bridge's stack limit — the script has said for weeks that they land in DIFF
      on a contended host and PASS otherwise. Piling on every core would trade minutes for a number
      that moves. `SSC3_CORPUS_JOBS=1` reproduces the serial reading exactly.

- [x] **43d — the answer is unchanged, checked at THREE points rather than asserted.** 240 cases
      on the exec lane: 127 serially, 127 in parallel. The whole corpus on the exec lane:
      **N = 186, DIFF 2, CRASH 0** both ways. The whole corpus on the BRIDGE lane, which spawns v2
      per case as well and is where contention would show first: **N = 186, DIFF 1, CRASH 1** both
      ways. A speedup that changes the number it reports is not a speedup, and the serial control is
      the only thing that can say so.

      The bridge lane's `CRASH 1` is `effects-handler` — "v2 bridge V-0 does not translate perform",
      a deterministic gap in the new effects work, not contention. Confirmed by reading it rather
      than by assuming parallelism was to blame.

**Measured:** exec lane 600 s → **217 s** (2.8×), bridge lane 4:46 with v2 spawned per case as well.

## 44 · `;` as a statement separator — one line, and the count did not move

`a = 1; b = 2` is ordinary Scala and neither front had it. Five of v3's own kernel files use it, so
it is on the self-hosting path as well as being something a person writes.

- [x] **44a — one line in `parseBlock`.** The loop called `parseStmt` and went round again without
      consuming the `;`, so the next iteration tried to parse a statement that began with one. The
      cursor has had `skipSemis` all along — thirty-two other places call it — and the block loop
      was the one that did not.

- [x] **44b — the self-hosting count did NOT move, 9 of 17 before and after, and that is the honest
      read.** Each of those five files has its own next blocker behind the semicolon. `Main.scala`
      now reaches the multi-arm `catch` (§40e, blocked on `Exec.scala:482`); `Lexer.scala` reaches a
      `;` inside an `if` BRANCH, which is a different parse path from a block. A construct opening
      and a file opening are not the same event, and reporting the second when only the first
      happened is how a number stops meaning anything.

- [x] **44c — N = 186 → 187, and the third DIFF is not mine.** `head-field-effect-shadow` fails
      identically with the change reverted — checked that way rather than by reading the name,
      though the name says the same. All three DIFFs (`effects-handler`,
      `parameterless-def-local`, `head-field-effect-shadow`) belong to the effects work.

**Measured:** UniML 218/218, seven v3 gates green, fixture half 51 of 52 with one declared
uniml-only, N = 187 of 368 with CRASH 0.

## 45 · A partial function in an ARGUMENT LIST — self-hosting 9 → 10

`f(a, { case P => … })` did not parse at all. The trailing form `xs.map { case … }` has always
worked, because it goes through `parseBlockArg`; inside an argument list the same braces reached
`parseExpr`, which reads `{` as a BLOCK, and a block cannot begin with `case`. **The whole call
failed** — not the argument, the call.

One position having a construct while another does not is the kind of difference nobody can
predict from the outside, and `v3/src/Ir.scala:180` is exactly the shape:
`scan(fn.body, { case Instr.Perform(…) => found = true; case _ => () })`.

- [x] **45a — found by walking the SELF-HOSTING list, not the corpus.** No conformance case writes
      it. The corpus is a good oracle for what the language is used for; v3's own kernel is a good
      oracle for what a person writing a compiler reaches for, and they are not the same set.

- [x] **45b — the `;` work from §44 is what made it visible.** `Ir.scala` reported the semicolon
      first; behind it stood this. Each of those five files is a small stack of blockers, which is
      why the file count moves slower than the construct count.

**Measured:** self-hosting **9 → 10 of 17**, N = 187 → **188 of 368** with CRASH 0, UniML 218/218,
seven v3 gates green, fixture half 52 of 53 with one declared uniml-only, `install.sh --dev` clean,
smoke-ci **76/76**. The three DIFFs are unchanged and all belong to the effects work.

## 46 · Three pattern gaps, and v3 passes the reference on one of them — self-hosting 10 → 11

Walking the self-hosting list again. All three are `ScalaSpike`, all three are things a person
writes, and none of them appears in the conformance corpus.

- [x] **46a — a type ascription binds TIGHTER than the bar.** `case _: Int | _: String =>` read `_`,
      took `: Int`, then met the `|` where the arm wanted `=>`. The alternation was collected first
      and one ascription applied to all of it, which is the wrong grouping; one alternand is now a
      pattern with its OWN optional type. `v3/src/Parser.scala:270` is the shape.

      **The tree is right and the lowering still refuses it**: `altTest` in `Lower.scala` handles a
      literal and a nullary constructor and nothing else, so a typed alternative is
      `an alternative pattern may not bind a name`. That file belongs to `ssc3-effect-protocol`.
      A parse refusal became a lowering refusal — no wrong answer either way, and the arm is a few
      lines whenever that claim frees the file.

- [x] **46b — alternatives NEST.** `case C("a" | "b", k) =>` reported `unsupported pattern '|'`,
      because only the arm's top level had the bar. Same `spike.apat` frame inside a constructor as
      outside, and nothing downstream needed changing: alternatives bind nothing wherever they
      appear, which is what makes them composable. `v3/src/Lower.scala:828` is the shape.

      **AND THE REFERENCE FRONT CANNOT PARSE IT** — `bin/ssc` answers "native frontend rejected
      incomplete parse … parser sentinel _err", while both v3 lanes now print `ab1`. So the corpus
      can never cover this: its goldens come from a front that refuses the construct. Worth saying
      out loud, because "the corpus does not exercise it" has meant "rare" every other time and here
      it means something else.

- [x] **46c — `var (a, b) = e`.** Only the `val` twin destructured, so `var` reported
      `expected var name, found '('`. It projects to the same `spike.tuppatval` frame with the same
      roles — v3 binds each name once at Tier 0, so a second shape would be two spellings of one
      meaning. `v3/src/Parser.scala:283` is the shape.

      Got the ROLE wrong first (`val.name` where the frame reads `tup.name`), and the symptom was a
      tuple temporary with no names bound — `unknown name 'a'`. Caught by running it, which is the
      only reason it did not land.

**Measured:** self-hosting **10 → 11 of 17**, N = 188 of 368 with CRASH 0, UniML 218/218, seven v3
gates green, fixture half 53 of 54 with one declared uniml-only.

`smoke-ci` read 75/77, and NEITHER is mine: `bugs-index` fails on
`v3-bridge-cannot-apply-a-lifted-capture`, a sibling's entry already on `origin/main` whose
`status: fixed` carries no `fixed-in`; `std-ui-forms` fails on the JVM lane under contention and
passes standalone twice, once with my change reverted and once with it in place.

## 47 · A plain string containing `${` ate the rest of the file — self-hosting 11 → 12

`v3/src/Lexer.scala` is a lexer, so it WRITES the characters other lexers read: `raw = raw + "${"`.
UniML's string lexer, on `${`, scanned for a balanced `}` — **for every string, not only an
interpolated one** — and when the match was not there it ran to the END OF THE FILE.

- [x] **47a — the symptom named the wrong construct, six lines away.** The file was refused at
      `Lexer.scala:268` with `expected statement, found 'then'`, and the cause was on 262. I chased
      the `else if` chain, the `;` in the branch, and the `'"'` in the condition — three
      reproductions that all PARSED — before bisecting the file itself, which found it in one step.
      **The bisection should have come first**: a reproduction I write tests my theory, and the file
      tests the code.

- [x] **47b — and where it happened to find a `}` later, it was SILENT.** `"${"` followed two lines
      down by `"}"` balanced, so the string token swallowed the code between them and produced a
      well-formed tree of a different program. My first probe of this shape "passed" for exactly
      that reason, which is how it survived being probed.

- [x] **47c — the guard is a LOOK-AHEAD, not a rollback.** `holeCloses` answers whether the `${`
      closes before the string does — counting nesting, skipping `\"`, and stopping at a newline,
      since a single-quoted string cannot span lines. Only then does the scan run. Looking ahead
      rather than scanning-and-undoing keeps the position and line/column bookkeeping untouched,
      which is the part that would have gone wrong quietly.

      Observed failing: with the guard replaced by `true`, the fixture stops parsing at line 7.

**Measured:** self-hosting **11 → 12 of 17**, UniML 218/218, seven v3 gates green, N = 188 of 368
with CRASH 0, fixture half 53 of 54 with one declared uniml-only. The corpus number does not move —
no conformance case writes a `${` inside a plain string, which is the same reason the whole class
survived: the corpus is written in the language, and this is a bug you only meet writing a compiler.

## 48 · The specs caught up with the code, and I stopped rather than manufacture work

The next thing on the self-hosting list was a pattern `val` — `val Tok.TInt(text, _) = e`. Measured
before starting: **one occurrence in the whole kernel, zero in the corpus**, and the same line also
needs `@unchecked`. Four files of machinery — `ScalaSpike`, `SpikeAst`, `SpikeTyped`, `UniFront` —
for one line. **Not done, and not done deliberately**: "next on the list" is not a reason.

What was left that is mine and real is the CONTRACT, which had fallen a long way behind:

- [x] **48a — `50-uniml-projection.md` §5 said "v3 has no typed pattern" and "REFUSE".** That
      stopped being true on 2026-08-07. A spec describing a refusal the code no longer makes sends
      the next reader to add something that exists. Same for `CompoundAssign`, `LocalDef` and
      `NotImplemented`, all still listed as refusals.

- [x] **48b — §5a is new, and its heading admits the order.** Written after the fact, because these
      landed one measurement at a time: host functions as declarations, the hoisting of nested
      classes, `case object` parents and methods, and the rule that a desugaring both fronts need
      lives PAST the fork rather than in the projection — placeholder lambdas, curried application,
      ranges and boxing are all in `Lower` for that reason.

- [x] **48c — `40-front-on-uniml.md` §5c records the list §5b could not have had.** §5b closed
      eight items and called the hand-over complete; it was complete FOR THE CORPUS. The other
      oracle is v3's own kernel, and walking it found nine more gaps in two days, **none of which
      appears in the corpus** — the corpus is written IN the language, and these are what you reach
      for writing a compiler.

**Where v3 stands, honestly.** Self-hosting 12 of 17 files; N = 188 of 368 with CRASH 0 and three
DIFFs that all belong to the effects claim. The remaining work in my scope is: three kernel files
waiting on one line in `Exec.scala:482`, one on `finally` in `Ast.scala`, one on a pattern `val`
that is not worth it yet — and on the corpus side, library surface in `Exec.scala`, `extension` in
`Lower.scala`, and Tier 2. **All of it is either another claim's file or charter-deferred.**

## 49 · Speed: the executor is ~900× off, and one cause needs no benchmark (claim `ssc3-jit`)

Design and the full ladder: [`../specs/ssc3-jit.md`](../specs/ssc3-jit.md). Only the in-flight slice
is a row here; J2/J3 and the parked alternatives live in that spec, which is written so a fresh agent
can pick this up cold.

Measured 2026-08-09 on a host at load ~43 — order of magnitude, not a ratio to defend:
`bench/corpus/arith-loop.ssc` **226.99 ms** against v1's 0.244, `list-fold.ssc` **60.06** against
0.0062. Both answers correct, so this is the cost of being right slowly.

The second row needs no benchmark at all. `javap` over the classes the driver had just built:
`Exec$.invoke` is **13 415 bytecodes**, and HotSpot's `DontCompileHugeMethods` cuts off at **8000** —
so it is never JIT-compiled, and every method call any v3 program makes runs in the JVM's own
bytecode interpreter for the life of the process. `step` (5478) and `binOp` (4352) do compile but are
past `FreqInlineSize` (325), so neither ever inlines into the dispatch loop. The v2 runtime had the
same shape at 49 384 bytecodes and splitting it was worth 2.4–10.8×; what did not carry over is that
**nothing in the build looks at method size**, which is why the first thing built here is the gate.

- [x] **SSC3-J1a — the specializer writes the field, and the gate judges it.** `6feef7689`.
      `Specialize.module` is a forward dataflow over the structured regions — `Block`/`If` consume
      depth-0 branches into their exit, `Loop` feeds them back into its entry and iterates to a
      fixpoint, deeper branches are re-emitted one level lower, which is the arithmetic `Exec`
      already does with `Signal.Branch(d - 1)`.
      **70 of 203 arithmetic and comparison instructions proved across the 30 `bench/corpus` files
      that lower — 67 `i64`, 3 `f64`, 34.5 %.** `arith-loop` and `float-loop` go from 3 `Dyn` to 3
      proved each, and both need the loop fixpoint: the conservative shortcut ("every register a
      loop writes is unknown at its head") is sound and proves nothing in either.
      **`Big` is never emitted, and that is a correctness rule rather than a gap** —
      `Exec.constOf` turns `Lit.LBig` into a `Value.VStr`, so a `Big`-marked instruction would name
      a representation the executor does not have. Invisible today, a wrong answer the day the field
      is trusted.
      *Gate:* `v3/jit-gate.sh --specialize`, four fixtures each failing for a DIFFERENT wrong
      analysis, plus a three-rule `--self-test` that plants each failure and requires RED.

- [x] **SSC3-J1b — `Exec.step` dispatches on `kind`, and it bought NOTHING. Measured.**
      `ssc3-cps-split` released `Exec.scala`, so this was no longer blocked.
      **10 alternating A/B pairs, same binary, `--no-specialize` as the OFF arm, load 45→54:**
      on median 138.0 ms, off 122.4 — **"on" faster in 5 of 10 pairs**, a 15.5 ms median difference
      against a 42.7 ms pooled sd, within-arm spread 2.4×. There is no effect here in either
      direction, and the 1.13 ratio is noise rather than a regression.
      **The reason needs no benchmark:** `binI64` is **895** bytecodes and `binF64` 578, both far
      over `FreqInlineSize` (325), so neither is inlined into `step` — which is 5918 and never
      inlined into `exec` either. The fast path swaps a tuple match for a CALL and leaves the
      per-operation `Value.VInt` allocation untouched. Found by the size gate, on its author.
      *Not doing:* shrinking `binI64` under 325. The measurement does not implicate it, and
      optimizing what the evidence has not named is how a day goes missing.
      *Landed anyway,* because a specializer whose field nobody reads is a strictly worse state,
      and because J0 and J2 need this seam. `--no-specialize` is now the OFF arm of every later
      measurement — and `ssc3 exec` took its path positionally, so that flag was unusable in that
      argument order until `--identity` caught it.

- [x] **The `--identity` gate exists now, and it is no longer green by construction.** 65 programs
      run with and without the pass, output compared byte for byte, plus `wrong-kind.ssir` — two
      strings added under a LYING `i64` annotation, which the executor must survive by honouring the
      values. **Proven to discriminate by removing `binI64`'s fallback arm and rebuilding**, not by
      argument.

**The follow-up, recorded here rather than as a third row** — a module sprint has two states and
"not started" is not one of them. The 133 instructions still `Dyn` are mostly PARAMETERS, which an
intraprocedural pass cannot know. Interprocedural kinds — join the argument kinds over every `Call`
site of a function, leave anything a `CallV` can reach at `Dyn` — is the next precision step, and it
is a strictly larger analysis, so it waits until J1b has shown that the proved third is worth
anything at run time. Proving more of a field nobody reads is not progress.

- [x] **SSC3-J0a/J0b — `invoke` is JIT-compiled for the first time.** `21d250b4c`.
      *J0a, the derived tables.* `Module` holds its pools as `List` and **`List.apply` is
      O(index)** — `m.consts(k)` walked k cons cells on EVERY execution, and `arith-loop` has two
      `Const` in its loop body, so a million iterations paid a million walks for a value that never
      changes. Same per call for `m.funcs(fi)`, per host call for `m.prims(p)`, per METHOD call for
      the `Invoke` name. All four are arrays now, and the constant pool holds VALUES so `constOf`
      stops allocating per execution (safe only because it produces immutable cases — stated in the
      source, because it would NOT be safe for `VData`/`VArr`/`VMap`).
      *J0b, the split.* `invoke` 13415 → **6912**, `invokeRest` 5379, both under
      `DontCompileHugeMethods`. A pure extraction of the final `case _` arm: no arm reordered, no
      guard changed.
      **PROVEN WITHOUT A STOPWATCH**, `java -XX:+PrintCompilation` on both builds, same workload:
      at 13415 `ssc3.Exec$::invoke` never appears in the compilation log at all; at 6912 both it
      and `invokeRest` are compiled. On a host at load 66–72 that is the only kind of evidence
      available, and it happens to be the stronger kind.
      **Wall clock says nothing yet and is recorded saying nothing:** 6 alternating pairs per
      workload across two class directories of the same tree — 4 of 6 on `arith-loop` (ratio 1.02,
      2.7 ms against a pooled sd of 36.4) and 3 of 6 on `list-fold` (0.91, 3.9 against 21.9).
      *The gate deleted its own declaration*, which is the two-way rule earning its place: with
      `invoke` at 6912 the `--sizes` check went RED **on the method that had just been fixed**,
      demanding the stale line go. A one-way threshold would have gone quietly green.

- [x] **SSC3-J0c — `step` is inlined into the dispatch loop.** `5219b8b61`. `exec` calls `step` per
      instruction, and at 5867 bytecodes it was compiled but eighteen times over `FreqInlineSize`.
      The three opcodes that dominate a loop body moved into a small `step`; everything else is one
      call further in `stepRest`. MOVED, not copied — a duplicate arm in `stepRest` would be a
      second decision site reachable by nobody.
      **The measurement corrected me mid-change:** after the split `--sizes` read 325, exactly the
      limit, and `-XX:+PrintInlining` read **326** and refused with "hot method too big". The size
      check reports the last instruction's OFFSET, a lower bound by however wide that instruction
      is. Extracting `binK` bought the last 90 bytes → `step (236 bytes) inline (hot)`. The gate now
      says this where the number is produced.

**AND THE NUMBER ARRIVED — by measuring the SUM, at load 38.** Each J0 change is under the floor;
all three together are not. 8 alternating pairs per workload, two class directories of the same
tree: **`list-fold` 91.3 → 52.8 ms, 7 of 8 pairs, ~1.7×**; **`arith-loop` 82.6 → 64.9, 7 of 8,
~1.3×**; `recursion-fib` 428.0 → 410.2, 4 of 8, no effect. The statistic is the SIGN TEST (7 of 8
one-sided, p ≈ 0.035), not the ratio of medians — the pooled sd still exceeds the median difference.

**The third row is the control and it is why the first two count.** `recursion-fib` is dominated by
the per-call frame — `callFunc` allocates `new Array[Value](nregs)` and fills it with `VUnit` every
call — which J0 never touched, and the prediction was made from the mechanism BEFORE the numbers
were read. A host that had merely quietened down would have moved all three.

**Left in J0, still unmeasured:** `List[Instr]` → an array per region, a small-integer cache, and
the per-call frame that `recursion-fib` just pointed at. **The frame is now the named next target,
by measurement rather than by taste.**

- [x] **SSC3-J2 — the closure lane exists, is SLOWER, and is kept for a different reason.**
      `e4dc0e94a`. Each `Func` compiles once into an `Array[Op]`, `Op = Array[Value] => Signal`;
      an opcode the compiler does not specialize delegates to `Exec.stepOne`, so coverage is
      complete from the first commit and there is no bail list.
      **Measured, 8 alternating pairs per workload, one binary:** `arith-loop` 110.3 → 171.8 ms,
      closures won **1 of 8** (~1.56× SLOWER, p ≈ 0.035 in the direction opposite to the one
      intended); `nested-loop` 211.4 → 256.7, 2 of 8; `list-fold` unchanged at 4 of 8 — the control,
      because it is `invoke`-bound and the lane delegates `Invoke`.
      **Why, and it is a lesson about baselines:** "compile to closures beats a tree-walker" assumes
      the walker pays a switch plus an operand decode per instruction, and **J0c had already removed
      that** — `step` is 236 bytes, inlined into `exec`, and its match over a sealed `Instr` is a
      tableswitch. `ops(i)(regs)` is a MEGAMORPHIC call site instead. The optimization was designed
      against a baseline that had stopped existing four commits earlier.
      **Kept behind `--closures`, default off, for the DIFFERENTIAL:** a second execution strategy
      over one IR, with `--identity` running every program both ways. Proven to discriminate —
      swapping `Bin`'s operands in the compiler turned **14 of 73** programs red, each naming the
      closure lane, the rest green.
      *For whoever retries it:* the array-of-closures dispatch is what lost. A CHAINED design, each
      closure calling its successor instead of returning to a loop, is what the literature actually
      measures. Different experiment, not a tweak.

- [x] **SSC3-J1c — the two-bank frame: built, measured, REVERTED from the executor.** The mechanism
      worked — `arith-loop` went from ~4 087 MB of young generation to **~391 MB, 10.5× less**, with
      answers unchanged. The clock refused to follow: `arith-loop` 3 of 8 pairs at 0.73×,
      `list-fold` 2 of 8 at 0.79×, only `nested-loop` 5 of 8 at 1.14×.
      **The tell is `list-fold`**, which has almost no long-bank registers and still got worse: one
      long-bank register sends EVERY instruction down the banked path, and anything outside the
      banked hot core meets a 2 302-byte `stepBankedRest` instead of the 236-byte `step` J0c
      inlined. Splitting that core to 314 bytes and confirming `inline (hot)` changed the clock by
      nothing.
      **So §8.2's conclusion is itself refuted:** reducing allocation 10.5× bought nothing, and
      young-generation allocation is close to free in both halves — the pause AND the allocation.
      *Kept:* `Specialize.longBanks` and the `--banks` gate — correct, hand-checked, and what any
      future unboxing needs on day one. *Not kept:* the executor lane, because an unused fast path
      with an invariant coupled to another file is debt.

**THE THROUGH-LINE, and it is worth more than any single row above.** Three changes moved the thing
they targeted and lost on the clock — closure compilation replaced the dispatch, the long bank
routed it through a second loop, frame pooling was refuted before it was written — and the one
change that clearly won, J0c, did nothing but make the existing dispatch INLINABLE. **The executor
is dispatch-bound and its dispatch is already good.** The next thing to try is therefore not a
cheaper dispatch but FEWER of them: superinstructions, fusing `Bin(Lt); BrIf` and `Const; Bin` at
load time, which keep the exact loop J0c tuned and push fewer instructions through it. That is the
one §3 J1 item never built, and now the only one the evidence points at.

- [x] **SSC3-J1d — copy propagation.** `cf8cd36e4`. `<something> → r` followed by `Move(d, r)`, with
      `r` written and read exactly once in the function, folds into one instruction.
      **Established, load-independently:** `arith-loop` 20 → **16** instructions, `nested-loop`
      34 → 28, `list-fold` 56 → 54, `recursion-fib` 18 → 17. The LOOP BODY of `arith-loop` goes
      10 → 8 — a fifth of the dispatches in the corpus's hottest loop.
      **Not established: any speedup.** 3 of 8 / 6 of 8 / 3 of 8 at host load 42–45; a 20 % effect is
      an order below this host's ~2× floor. The ratios are noise in both directions.
      *Gate:* `--no-optimize` is the OFF arm and `--identity` gained a fourth comparison for it —
      this is the pass that rewrites the instruction list itself, so it does not ride on the
      specializer's arm.

**WHERE THE LADDER STANDS, and it is the most useful line on this board.** Six attempts, one clear
win. J0a/b/c — make the EXISTING dispatch cheaper to run — moved the clock 7 of 8 pairs. Everything
since moved its own mechanism and left the clock alone or worse: J1b read the `kind` field (5 of 10),
J2 replaced the dispatch (1 of 8, worse), J1c allocated 10.5× less (3 of 8, worse), J1d executes 20 %
fewer instructions (unmeasurable). **The recommendation is to stop optimising this executor on this
host** — not because hoisting and superinstructions are bad ideas, but because four consecutive
measurements have PROVED, rather than assumed, that nothing under ~2× is visible here. Whoever gets a
quiet machine should first re-run the `bench/history.tsv` rows for J1b, J1c and J1d: two may be wins
nobody can see, and one is a revert that might not have been necessary.

**FRAME POOLING IS REFUTED — do not build it.** It was the obvious next move: `recursion-fib` is the
slowest row, nothing has moved it, and `callFunc` allocates a frame per call. The assumption was
checked before any code was written and it is false — `java -Xlog:gc` over that workload reports
**76 collections and 66.8 ms of total pause in a ~14 s run, 0.5 %**, with every collection going
338M → 2M so nothing is promoted. A pool removes GC *pauses*, and the ceiling on the whole idea is
smaller than one round of this host's noise. Written down here rather than left as an absent task,
because it is precisely what the J0 control seemed to point at.

What those numbers DO say: ~25 GB allocated in that run. The volume is real, the *pause* is not — so
the target is allocating less, not recycling. Every arithmetic result is a fresh `Value.VInt`, which
points at J1's parked two-bank frame (`Array[Long]` beside `Array[Value]`): it removes the boxing
instead of reusing the box. Bigger than anything in J0, and now it has a measurement behind it.

## 50 · Tier 2, un-deferred — and the first thing to establish is that it is not ONE thing

Sergiy lifted the Tier 2 deferral. Before planning anything, the charter's own grouping needed
checking, and **it groups two constructs that need different things**:

- **`extension`** — `extension (x: T) def m(a) = b`, then `v.m(a)`. Resolution is by NAME: if no
  class declares `m`, the extension's `m` applies with the receiver first. v3's method dispatch is
  already name-and-arity based, precisely because there is no type checker. **So `extension` needs
  no types at all**, and the charter listing it beside `given`/`using` overstated its cost.
- **`given` / `using`** — needs to pick an instance by TYPE. This is the one the charter is right
  about: there is nothing in a value's runtime tag that says which `Monoid[A]` a call wants.

**The plan, staged so each step is measurable on its own.**

1. **`extension`, projection-only.** The projection sees every declaration in `parse`, so it can
   collect the extension method names, emit each as a top-level `def` with the receiver as its
   first parameter, and rewrite `MethodCall(v, m, args)` to `Call(m, v :: args)` — but ONLY where
   no class declares `m`, so a real method always wins. Nothing outside `v3/uniml`.
2. **`using` parameters and `given` declarations, resolved by DECLARED TYPE TEXT.** A `using`
   parameter becomes an ordinary one; a `given` becomes a top-level `val`. At a call, if exactly
   ONE given's declared type text matches the `using` parameter's, pass it. **Ambiguity and generic
   instances are REFUSED by name** — this is a syntactic match, not inference, and calling it
   inference would be the kind of overstatement this file exists to avoid.
3. **Measure, then decide whether more is wanted.** Step 2 covers the shape typeclass code actually
   uses; whether the remainder is worth a type checker is a question for numbers, not for now.

**What blocks what, checked rather than assumed.** `ssc3-effect-protocol` released 24 hours ago, so
`Exec.scala` and `Lower.scala` are no longer its. They are now held by four OTHER claims
(`v3-dataset-vertical-slice`, `v3-calls-a-captured-function-parameter`, `v3-bridge-lifted-capture`,
`ssc3-cps-split`), and `Parser.scala` by a fifth. Step 1 needs none of them.

## 51 · Tier 2 stage 1, attempted and REVERTED — the measurement corrected the plan

§50's plan called `extension` "projection-only", on the reading that it resolves by NAME and so
needs no types. **The name part is right and the placement was wrong**, and it cost two
measurements to find out — both worth more than the feature.

- [x] **50a — a name-based rewrite broke 131 cases.** `v.m(a)` → `m(v, a)` wherever `m` is an
      extension name and no class in the module declares it: **N 188 → 130, CRASH 0 → 131**. An
      extension named `map` or `join` rewrote every `.map(…)` and `.join(…)` in the program,
      including the ones on lists. The projection knows what a module DECLARES and nothing about
      the built-in vocabulary — and adding that list is not the fix, because **which method a
      receiver has is a fact about its runtime value, not its syntax**.

- [x] **50b — refusing at the call site cannot cover it either.** `UniFront.parse` runs once per
      FILE, so an extension declared in an imported module is invisible where it is called. Two
      cases went from a clean UNSUPPORTED to an unpositioned CRASH for that reason. Turning a
      positioned refusal into an unpositioned failure is a regression even for a program that was
      never going to run.

- [x] **50c — reverted to the refusal, floors restored**: N = 188 of 368, CRASH 0. The work is
      filed in `v3/BACKLOG.md` with both measurements and the design conclusion: an extension is one
      more fallback in `Lower`'s dynamic `Invoke` default, tried after every class arm and the
      built-in table have missed — the only point where the merged program AND the receiver's
      runtime tag are both known.

**So Tier 2 is un-deferred and BLOCKED, which is a different thing from un-deferred and done.** Both
stages need `v3/src/Lower.scala`: stage 1 for the dispatch fallback, stage 2 because `given`
resolution has the same per-file blindness and additionally rewrites call sites. That file is held
by four claims, one of them active eleven minutes before this was written.

## 52 · The type checker is DECIDED and MANDATORY — `given` syntax now, inference next (claim `v3-given-syntax`)

**The decision was taken by Sergiy on 2026-08-09, against measured numbers, and it is not "if" but
"when".** §50 left it as *"a question for numbers, not for now"*, and §8 said `given`/`using`
*"genuinely needs types, and it stays refused"*. That is now settled: **both halves get built, in
this order**, and the second is not conditional on the first paying off.

**The numbers it was taken against** — `v3/corpus-report.sh`, 2026-08-09, on a rebuilt tree:

    N = 188 / 368        (166 / 367 on 2026-08-08)
    UNSUPPORTED = 174, of which
      49 unknown name + 34 unknown function + 26 host fn not on this lane = 109  (63%, library)
      9 extension · 8 non-`def` trait member · 5 `given … with` · 2 abstract `val`  ≈ 24  (typing)

Of the seven files that declare `given … with`, **two reference the instance BY NAME** —
`typeclass-monoid` and `typeclass-extension` — and **five need inference**:
`tagless-{context-bounds,multi-file,program,resolution}` and `typeclass-fold`, all through
`summon[T]` inside a function generic in its element type.

**Only ONE of the two by-name rows is G1's, and the correction is worth recording.** Framing this
section I wrote "2 rows" from that split; reading the files says otherwise.
`typeclass-extension` declares `trait Functor[F[_]]` whose sole member is
`extension [A](fa: F[A]) def fmap[B](f: A => B)`, so it needs a higher-kinded parameter and an
extension INSIDE a trait — §51's territory, and §51 is a revert. Referencing by name is necessary
for G1 and not sufficient; the row that G1 actually turns is `typeclass-monoid`.

**A syntactic shortcut was considered and REJECTED on evidence.** "One instance per trait in
scope" would pick wrong on `tagless-resolution`, which declares `Show[Int]` AND `Show[String]` —
one trait, two instances, separable only by a type argument. It would print a plausible number,
which is this repository's most expensive failure mode. §50 step 2's variant survives that
objection only because it REFUSES ambiguity instead of guessing; anything that guesses is out.

- [x] **G1 — `given name: T with` parses, as a NAMED VALUE and nothing more.** The declaration
      becomes what `object name` already becomes, so no new IR, executor or bridge behaviour is
      involved. **It buys ONE row — `typeclass-monoid` — and must not be sold as more**;
      `summon[T]` stays refused, by name, with a message that says inference is coming rather than
      that it is impossible.
      **Done 2026-08-09, and the capability gate found the one real defect in it.** Both fronts
      needed the edit — the refusal lived in the UniML projection, so a parser-only change would
      have made the DEFAULT lane accept what v3's own front refused. Then `typeclass-fold` came up
      as *accepted only by v3*: both fronts declined it, at DIFFERENT STAGES — UniML in `parse`,
      v3 later in the lowering with `unknown name 'summon'` — and `given` had been masking that by
      refusing the file first. `summon[T]` now refuses in v3's parser too, same position, same
      words, tied to the `[` so a value named `summon` keeps working.
- [ ] **G2 — the type checker. MANDATORY, not opportunistic.** Sergiy's words: every feature is to
      exist in the end, and **`tagless-*` is a goal in its own right**, not a side effect worth
      having only if cheap. So this is queued as work, not as a decision to revisit. What it has to
      answer is `summon[T]` where `T` mentions a function's own type parameter — that is the whole
      of the ask, and the five rows above are its acceptance set.
      **Read §51 before starting.** Stage 1 was attempted by NAME and reverted: `N 188 → 130,
      CRASH 0 → 131`, because an extension called `map` rewrote every `.map` in the program.
      The lesson transfers directly — *which instance a call needs is a fact about types, not
      about spelling*, and a checker is the thing that knows it.
      **Cost, stated up front so it is not discovered later:** the erasure bargain of Tier 0 ends
      where the checker begins; BOTH fronts must then agree on typing, which widens I-3 from
      "same output" to "same judgements"; and `N` may fall for the first time, which I-5 forbids
      quietly — so G2 lands behind a gate that reports the corpus number before and after.

  - [x] **G2 stage 1 — the question travels, and the unambiguous case is answered.** Landed
        2026-08-09. A `given` now records the HEAD of the trait it declares (`Monoid`, never
        `Monoid[Int]` — the argument is what erasure removes), `summon[T]` is carried to `Lower`
        as `__summon__("T")` by BOTH fronts instead of being refused at either, and `Lower`
        resolves it against the MERGED module: exactly one instance → that instance; zero or
        several → a refusal naming the count and the instances.
        **It turns ZERO corpus rows, and that is the honest result rather than a disappointment.**
        The estimate that said two came from grepping each FILE for its instances; the candidate
        set is the import CLOSURE, and over the closure `tagless-context-bounds` sees three
        `Monoid` instances, not one. Only `typeclass-fold` resolves — it has a single `Monoid` —
        and it then stops on something else entirely: `xs.foldLeft(…)(summon[Monoid[A]].combine)`
        passes a METHOD AS A VALUE, and v3 lowers `obj.m` with no arguments to a call with none,
        so the IR verifier reports `intSum.combine passes 0 arguments, it takes 2`. Filed as
        `v3-method-as-a-value`; it is the next thing standing between this row and running, and
        it is not a typing question.
        **What it does buy** is the machinery stage 2 needs and a refusal that says which
        instances it found, in place of `unknown name 'summon'`. Fixtures `using-param` (15 and 0,
        hand-computed) and `using-ambiguous` (refused on both fronts, same position).
  - [x] **G2 stage 2a — type-directed resolution, first order.** Landed 2026-08-09.
        `tests/conformance/tagless-resolution.ssc` RUNS on v3's front and prints
        `42 / hello / equal: 7 / not-equal / 99` — the row where `Show[Int]` and `Show[String]`
        are separable by nothing but the type. `(using …)` clauses, context bounds `[A: Monoid]`
        (which become `given_` parameters, as their own prose says they desugar to), and an
        explicit `(using showInt)` at a call site all parse; `Param` carries its declared type as
        TEXT and `Def` carries its type-parameter names.
        **MONOMORPHISATION, NOT A DICTIONARY, and a failure chose it rather than a preference.**
        The first version appended the instance as an argument and died on `unknown name 'showInt'`:
        a `given` is an `object`, a NAMESPACE with no runtime value, so there is nothing to pass.
        What works is specialising the callee — `display$showInt` with the `using` parameter's name
        replaced by the instance's, so `s.show(a)` becomes `showInt.show(a)`, the qualified call
        that has worked since G1. One copy per (function, instances) pair.
        **What it infers is deliberately small — a literal's type, and nothing else.** When it
        cannot infer, or the substituted type matches no instance or several, the call is left
        alone and the arity check refuses it by name with a position. Filling in "the only instance
        of that trait" would be the spelling shortcut §52 rejected.
        **Two ORDERING defects, both found by running rather than by reading:** `summon` must
        resolve BEFORE specialisation, or the copy has no parameter left to resolve against; and
        `f(a)(using inst)` must be flattened in a pass of its OWN, because `mapDeep` rebuilds
        children first and the inner call was being specialised before the outer clause was seen.
        **DECLARED GAP:** the projection still refuses `using`, because `SpikeAst.Def` has nowhere
        to keep `[A]`. The default front is UniML, so the feature needs `SSC3_FRONT=v3` and the
        corpus number does NOT move — 188/368 before and after. Probes `usingp` and `summon2` are
        declared in `front-capability-gate.sh`; the follow-up is
        `BUGS.md v3-uniml-def-has-no-type-parameters`.
  - [ ] **G2 stage 2b — inference past a literal.** `tagless-context-bounds` needs it and is the
        next row: `combineAll(xs)` inside a generic body passes a PARAMETER of type `List[A]`, not
        a literal, so three things are missing — a constructor's type (`List(1,2,3)` is
        `List[Int]`), structural matching of `List[A]` against `List[Int]`, and propagating the
        enclosing specialisation's binding into the calls its body makes.
  - [ ] **G2 stage 2c — higher-kinded.** `tagless-program` and `tagless-multi-file` select on a
        type CONSTRUCTOR — `Monad[Option]` beside `Monad[List]`, `Logged[List]` beside
        `Logged[Option]`. Not reachable by substituting a type argument into text.

## 55 · PLAN — the version comparison is not measurable yet, and the bridge is why (claim `v3-bench-and-bridge-plan`)

Sergiy asked to return to benchmarking v1 against v2 against v3, to runtime and JIT work, and to
"the problems v2 backends have under the v3 front". **The third is not a separate question**:
`ssc3 run` executes THROUGH `BridgeV2`, so the corpus number this repository tracks — `N = 191/368`
— already measures v3-front-on-v2-backend. What is separate is that the harness cannot yet compare
the three versions honestly, and three specific things make it so.

Ordered so that each entry is measurable when the one before it lands. **Numbers first, optimisation
after** — until the apparatus is fixed, "slow" and "measured wrong" are indistinguishable, and this
repository has paid for that confusion more than once.

- [ ] **R1 — EFFECTS CROSS THE BRIDGE, or the bridge refuses them BY NAME.** `BridgeV2` has zero
      cases for `Instr.Handle`, `Instr.Perform` and `Instr.Resume` — measured, `grep -c` is 0 — so
      an effectful program lowers to v2 Core IR with those instructions silently missing. Found on
      2026-08-09 when a `handle` fixture printed the right answer on the executor and NOTHING on
      the bridge; that is why `v3/tests/effects/` needed an executor-only gate.
      **A silent nothing is the worst of the three outcomes.** The order of work is therefore:
      refuse by name FIRST (so no program can quietly lose its effects), then carry what v2 can
      express.
      *Done when:* an effectful program either runs identically on both lanes or is refused with a
      position naming the instruction, and `v3/tests/effects/` fixtures can move back beside the
      differential.
- [ ] **B1 — one timing wrapper for every column.** `bench/run.sc` times v3 differently and says
      why in its own comment: the shared wrapper calls `nanoTime()` as ordinary ScalaScript and
      "v3 has no clock". **v3 has one since 2026-08-09** — `nanoTime()` → `io.nanoTime`, the same
      prim name v2 uses. The asymmetry the comment admits ("v3 is not charged for executing the rep
      counter, and that flatters it on the cheapest rows") can now be removed instead of disclaimed.
      *Done when:* v3 goes through `runSscBenchBackend`'s wrapper like every other column, and the
      comment explaining the exception is deleted rather than edited.
      **Scoped by measurement 2026-08-11 — three steps, two of them one-liners.** Everything else
      the wrapper needs already runs on v3 (underscore literals, nested `while`, a `def` with a
      parameter, `Long`/`Double` vars, string concat — all probed, not assumed):
      - **B1z — mixed Int/Double arithmetic. DONE 2026-08-11**, and it was a fourth gap the first
        scoping missed: the wrapper's last line is `_ssc_reps * 1000000.0` with an Int counter, and
        v3's `binOp` had only homogeneous arms, so it died with `Mul on Int 32768 and Double
        1000000` — after clearing everything else. It was also a two-lane divergence: the bridge
        computed those lines while the executor refused them. See `v3/BUGS.md`
        `v3-mixed-int-double-arith`. With it in, a faithful hand-built wrapper runs end to end on
        v3 and prints `BENCH_MS:`/`BENCH_SINK:`, so B1a is now the ONLY v3-side gap left.
        *Follow-up it surfaced, NOT v3's and so not filed here:* on mixed numeric COMPARISON v1 and
        v2 disagree with each other — interp evaluates `1 < 2.0` to `true`, native and v2 refuse it
        at type-check time with `cannot unify Int vs Float`. That is a cross-module entry for the
        root `BUGS.md`; it needs a claim on that file, which this slice did not hold. v3 refuses,
        matching the majority, and deliberately was not changed.
      - **B1a — DONE 2026-08-13.** v3 resolves `System.nanoTime()` — one arm in `Lower.scala`
        beside the `Bench.opaque` precedent, guarded on a user-defined `System` class or function so
        it stays a SPELLING and not a keyword (verified: a program with its own `case class System`
        still works). The real emitted wrapper now runs on v3 end to end and prints BENCH_MS.
      - **B1d — a FOURTH gap, found only by switching the column.** The wrapper's seeded update was
        `{ _ssc_seed = _ssc_seed + 1; core }`, and v3's front parses neither `;` nor a braced block
        in statement position. 17 of the 36 corpus files declare `def workload(seed: …)`, so the
        switch would have BLANKED them — caught because a three-row sample happened to include
        `option-chain`, not because the plan expected it. Fixed in the generator: two statements at
        the indent the site actually sits at. Same text for every lane; measured identical on v3,
        native and interp.
      - ~~B1a — the wrapper keeps `System.nanoTime()`, so v3 must resolve it.~~ One entry in the
        `builtins` table of `v3/src/Lower.scala`: `"System.nanoTime" -> "io.nanoTime"`. It cannot be
        solved from the wrapper side instead: the js backend maps ONLY that spelling
        (`Math.round(performance.now() * 1e6)`) and emits a bare `nanoTime()` verbatim as an
        undefined JS function, so switching the wrapper to v3's spelling breaks the js column.
        ⚠ BLOCKED while `Lower.scala` is held by claim `v3-prelude-and-dataset` — one line, but in
        someone else's file. Do not edit it under a different claim.
        **And do not release that claim as stale on the strength of its `heartbeat`.** Checked
        2026-08-11: the field read 5h19m old, which the protocol calls abandoned, while the claim's
        worktree went from CLEAN to two modified files within the same hour — the agent is working,
        the field is lying. The liveness evidence that counts is the worktree and the branch (it
        also holds a commit that is not on main), not the timestamp the holder last remembered to
        write. This is now the second time that field has been wrong in both directions in one day.
      - **B1b — the wrapper's fallback sink must stop using `null`.** `var _ssc_sink: Any = null`
        in `generateWrapper` (`v1/tools/cli/.../cli/Main.scala:7912`) is the branch for workloads
        returning anything other than Int/Long/Double/Boolean. v3 has no `null` and should not get
        one — a null-free language is a feature — so this is the WRAPPER's fix: `= 0` serves every
        lane. Needs a tools rebuild. **DONE 2026-08-11.** `= 0` measured on native, interp, v2 and
        v3 (all print `List(1, 2)` then `x` for an `Any` var reassigned to each) and on js the two
        spellings are indistinguishable; `bench --machine` then runs the Any-sink path end to end
        on ssc, v2 and js. The initial value is never read — the first warmup call overwrites it.
      - **B1c — one generator, two runners.** The generator lives inside the tools binary and the
        text is never written out (`os.temp`, deleted after use), so v3 cannot be handed the same
        bytes today. Add `--emit-wrapper` to the tools `bench` command (print and exit 0), then
        `runV3Bench` asks tools for the wrapper and runs it with `ssc3 run`. Do NOT reimplement
        `generateWrapper` inside v3: that is a second decision site for the measurement apparatus,
        and the first divergence between the two copies would be invisible in every number.
        **HALF DONE 2026-08-11: the flag is in, the switch is NOT, and that is deliberate.**
        `--emit-wrapper` prints the wrapper and runs nothing, and what it prints is proven shared:
        for `arith-loop` the emission is BYTE-IDENTICAL across `ssc`, `v2` and `js`. Two columns
        legitimately differ and neither weakens the claim — `jvm` gets the documented AtomicLong
        anti-fold, and `rust` is not in `validBackends` at all (it has its own `runRustBench`, as
        v3 does today). Run through `ssc-tools run --v1` the emitted text yields `BENCH_MS:` and
        `BENCH_SINK:`, so it is a runnable program and not just plausible text.
        *The remaining change is ONE line in `runV3Bench`, and it must wait for B1a.* Landing it
        now would BLANK the whole v3 column rather than improve it: the emitted wrapper on v3 dies
        with `unknown name 'System'` at 47:17 — measured on the real emitted bytes, not on a
        hand-built copy. A blank column is worse than a column with a disclosed asymmetry, and a
        fallback that quietly reverts to `ssc3 bench` would make each cell's measurement method
        unknowable, which is the same disease in a new place.
      *Gate:* the emitted text must be byte-identical for v3 and for the lane it is compared
      against — otherwise the columns are again measuring two different programs.
      *Expect the v3 column to get SLOWER when this lands, and say so when publishing:* today v3
      is not charged for the rep counter, seed increment or sink update, and every other column is.
- [x] **B2 — the harness's coverage claim is stale by a factor of one and a half.** DONE
      2026-08-11. The number is REMOVED rather than corrected: a count in a comment rots, and this
      one rotted by half in four days — "23 of the 36 as of 2026-08-07" read as v3 barely covering
      the table while the real figure had reached 34. The comment now points at
      `v3/bench-corpus-gate.sh`, which computes it on every run and names the rows that do not,
      and it also corrects what a blank cell MEANS: v3 accepts all 36, so a blank is a row that
      produced no number, not a front refusal.
      **Superseded description:** `bench/run.sc`
      says "v3 compiles 23 of the 36 corpus files as of 2026-08-07". Measured 2026-08-11: it
      ACCEPTS all 36 and COMPUTES 34. The two that do not — `effect-pure`, `effect-stream` — want a
      library function and a `Stream`, not a compiler change, and a blank cell should say which.
      *Done when:* the number is derived by the harness or dated in the text, so it cannot rot the
      same way twice.
- [x] **B3 — a regression in `bench/corpus` is invisible to every gate.** DONE 2026-08-11 as
      `v3/bench-corpus-gate.sh`, wired into the workflow with a self-test first. It asks only
      whether each row produces a `BENCH_SINK` — never how fast, because timing on a contended host
      is noise, and never the value, because that is `exec-gate.sh`'s job. Declares the two rows
      that do not compute (`effect-pure`, `effect-stream`, both wanting a library rather than a
      compiler change) and goes red in BOTH directions: a row that stops computing, and a declared
      blank that starts. Proven by planting each.
      **Superseded description:** `typeclass-fold` computed
      16500, stopped computing when stage 2b landed, and `N` never moved — because
      `corpus-report.sh` reads `tests/conformance` and the bench corpus is a SEPARATE set. Caught
      by hand on 2026-08-11, three days after it could have been.
      *Done when:* a gate runs the bench corpus for a NUMBER (not a timing) and fails when a row
      that computed stops computing. Cheap: `--warmup 1 --reps 1` and check for `BENCH_SINK`.
- [ ] **B4 — the three-version table, on a quiet host.** Only after B1–B3. Load was 29 when this
      was written and identical code spreads by 2.5× at load 5.5, so a table taken now would be
      published noise.
      *Done when:* `bench/run.sc` runs with all three versions and every backend, on a host below
      load 5, with the run recorded in `bench/history.tsv`.
- [ ] **J1 — read the table before touching the JIT.** Runtime and JIT work is what Sergiy asked
      for and it is LAST on purpose: the one measurement this repository already has says the
      biggest v3 cost is `Exec.invoke` at 13415 bytecodes, which HotSpot never compiles — a fact
      about method SIZE, not about the algorithm. Whether that still dominates is a question for
      B4's table.

## 54 · PLAN FOR 2026-08-09/10 — every remaining typeclass row, in dependency order

Sergiy: *"это нужно исправить … Остальное все тоже, обязательно"*. So this is a queue, not a menu.
Ordered by what unblocks what, and each entry says what it is DONE BY — a measurement, not a
feeling. Anything that turns out bigger than its entry gets split rather than stretched.

- [x] **U1 — UniML keeps a definition's type parameters, and the projection stops refusing
      `using`.** DONE 2026-08-09. `N` rose 188 → 189, both declared probes came out of the gate in
      the same commit, and `tagless-resolution` runs on the DEFAULT front with identical trees.
      Three wrong guesses first — the qualified-name `skipTypeParams` ate a plain def's `[A]`; a
      type parameter lexes as `spike.uid`, not `spike.id`; and the dialect erased a `using`
      parameter's type ARGUMENTS, which is the one thing resolution matches on. A one-line
      diagnostic answered in one run what two rounds of reading had not.
      The gates found two more, both "two places know one fact and disagree": v3 printed
      `f(a)(using x)` as an `Apply` where UniML printed one flat call, and a context bound became a
      parameter AFTER `sigs` was built, so `checkArity` passed a short call to the IR verifier,
      which refuses without a position — a CRASH rather than a refusal.
      **Superseded entry:** The declared divergence (`usingp`, `summon2` in `front-capability-gate.sh`) exists
      because `SpikeAst.Def` is `(name, params, ret, body, span)` — nowhere for `[A]`. THIS IS THE
      FIRST ITEM because the default front is UniML: until it lands, stage 2a is reachable only
      with `SSC3_FRONT=v3` and the corpus number cannot move.
      *Done when:* both declared probes come OUT of the gate's list in the same commit (the gate
      demands it), `tagless-resolution` runs on the DEFAULT front, and `N` RISES — it is a
      conformance case, so the corpus is the witness.
- [x] **2b — inference past a literal.** DONE 2026-08-09. `tagless-context-bounds` runs on BOTH
      fronts — `15 / hello, world / #60 / combined=7 / combined=hi / 42 / haha`, every value checked
      against the source — and `N` rose 189 → 190. All three pieces this entry named were needed,
      plus two it did not: the generic definition stays behind as a TEMPLATE and was arity-checked
      like running code (dropped now, but only when no call to it survives, so an unsolved call
      still gets `passes 1 argument(s)` rather than `unknown function`); and type arguments were
      being kept only for `using` parameters, so `xs: List[A]` arrived as `List` on the uniml path
      and the row ran on one front and not the other.
      **Superseded description:** `tagless-context-bounds` is the row. Three pieces, each
      measurable on its own: a constructor's type (`List(1,2,3)` is `List[Int]`), structural
      matching of `List[A]` against `List[Int]`, and propagating the enclosing specialisation's
      binding into the calls its body makes (`combineAll(xs)` inside `combineAndPretty`).
      *Done when:* `tagless-context-bounds` runs on both fronts and `N` rises again.
- [x] **2c — instances NAMED at the call site, wherever they sit.** DONE 2026-08-09, and the entry
      below was WRONG about what this row needs. `tagless-program` runs — output identical to
      `tests/conformance/expected/tagless-program.txt`, four lines — and `N` rose 190 → 191.
      **There was nothing to infer.** The program names both instances in every call:
      `greet("Alice", consoleOption, optionMonad)`, no `using`, no `summon`. It failed with
      `unknown name 'consoleOption'` because a `given` is an `object` at Tier 0 and objects are not
      values. Kinds would be needed to WORK OUT the instance, and nothing asks for that here. So the
      fix is the existing specialisation applied at argument POSITIONS rather than to a trailing
      `using` clause — which also subsumes the explicit `(using inst)` branch, leaving one code path
      where there were two that had to agree.
      **The split this entry predicted did happen, along a different line:** `tagless-multi-file` is
      blocked by `extension` INSIDE a `given` (`a trait member that is not a def`), which is X1's
      family, not resolution's.
      **Superseded description:** `tagless-program` and `tagless-multi-file` select on a type
      CONSTRUCTOR: `Monad[Option]` beside `Monad[List]`, `Logged[List]` beside `Logged[Option]`.
      Substituting a type argument into text does not reach it — the instance is chosen by what the
      VALUE is, so this needs either the receiver's runtime tag or a real kind-aware match. Expect
      this to be the one that splits.
      *Done when:* both rows run, or the entry is replaced by what measurement showed instead.
- [x] **X1 — `extension [M](ref: ActorRef[M])` — THE DIAGNOSTIC HALF ONLY.** Landed 2026-08-09.
      `extension` was not refused by v3's own front, it was UNPARSEABLE — `expected ')', found :`,
      naming punctuation instead of the construct — and both fronts now say
      `` `extension` is outside SSC3 core Tier 0 `` at the same position. Guarded by what FOLLOWS,
      because refusing the bare word made `extension` a hard keyword and broke a value of that name.
      **The BEHAVIOUR is not done and is not mine**: it is claim `v3-extension-dispatch`, taken by
      another agent, and §51's conclusion — a fallback in `Lower`'s dynamic `Invoke` default — is
      what it should follow. That is what turns the three rows.
      **Superseded description:**  `v3-extension-type-params`: a type-parameterised
      extension is a parse error, and it is the whole of what stands between
      `actors-bounded-mailbox` / `actors-process-info` and the module they import. Read §51 first —
      a name-based extension rewrite was tried and reverted at `N 188 → 130`.
- [x] **W1 — one walker, or a test that every walker handles every case.** Landed 2026-08-09 as
      `v3/walker-gate.sh`, wired into the workflow with a self-test first.
      **It found a real bug on its first run**: `qualifyMembers` did not descend into `NamedArg`, so
      an object's own `val` was never qualified inside a named argument —
      `Box(v = secret, tag = "x")` inside `object Store` reported `unknown name 'secret'`. The same
      hole `mapDeep` had, in a different walker. The remaining gaps are declared per walker and per
      case; the gate goes red when one closes and its declaration stays, which it did to me
      immediately — my first list was copied from a truncated run and claimed two cases were missing
      that were not.
      **Superseded description:** 
      `lower-has-six-hand-written-Expr-walkers-and-nothing-checks-they-agree`. Three were missed in
      one day, each with a different symptom; the middle one was a hole that predated the node.
      *Done when:* adding a case to `Expr` without touching a walker FAILS a test.
- [x] **E1 — `handle`'s return clause, and E2 — a clock prim (SSC3-3d).** Both landed 2026-08-09,
      the moment `Exec.scala` freed. E2: `nanoTime()` in the language, `io.nanoTime` as the prim —
      v2's own name, because the bridge emits prim names verbatim. E1: `case x => List(x)`, applied
      EXACTLY ONCE, at the point a computation finishes without performing; a boolean did not
      survive nesting and the frame carries a counter. The implicit alternative is not available at
      Tier 0 — lifting an `Int` into a `List` implicitly needs the handler's answer type, which this
      tier erases — so `effect-multishot` still answers 0 and its fixture was NOT edited to suit the
      implementation.
      **Found while landing it:** `v3/tests/effects/` held 15 fixtures that no gate read, 10 with no
      recorded expectation. `v3/effects-gate.sh` runs them on the executor lane and is wired in.
      **Superseded description:**  Both need
      `v3/src/Exec.scala`, held by `ssc3-unboxed-frame` at the time of writing. Taken the moment it
      frees; not started behind someone else's claim.

## 53 · The spike mirrored a reference BUG that the reference had already fixed

A sweep of the uniml front over 120 files of `examples/` and the standard library — a wider oracle
than the corpus and than v3's own kernel — refused 41. The largest cause I could act on was
`spike.error` in 8 files, and every one was an ANNOTATION on its own line: `@graphLabel("Module")`,
`@rdfClass(…)`, `@tailrec` above the declaration they annotate.

`ScalaSpike` emitted an error node there deliberately, and its comment said why, citing
`ssc1-front.ssc0:2499`. **So I read the reference instead of the comment**, and the reference says:

> An annotation is almost always written on its OWN line, so after skipping `@Name(args)` the next
> token is the `;` the layout pass inserted, not the annotated declaration — `parseOneStmt` saw `;`
> and emitted `_err`. … BUGS `ssc1-front-annotation-before-declaration`. F has no such gap.

…and its code now reads `parseOneStmt(skipSemis(skipAnn(toks)))`. **The reference had filed this as
its own bug and fixed it.** The spike was faithful to a version that no longer exists.

- [x] **52a — so this was a catch-up, not a divergence**, which is the opposite of the framing I
      took it to Sergiy with. Fidelity to an oracle means fidelity to what it does NOW, and that is
      a thing to re-read rather than remember.

- [x] **52b — AND I HUNG THE PARSER DOING IT.** Removing the vestigial `if false then () else`
      pulled the entire declaration dispatch INSIDE the `while isAnnotationStart` loop — in a
      language with an offside rule, deleting a branch moves everything its `else` held. A file with
      no annotation then parsed nothing and the outer `while !c.eof` spun forever.

      The symptom lied twice: the test suite timed out, then a single parse did, and "the machine is
      busy with the background job" is a PLAUSIBLE explanation that is also wrong. What settled it
      was a three-line program: `rc=124` on that cannot be contention.

- [x] **52c — the fixture is uniml-only, and the ceiling caught the second one.** v3's own front
      refuses `@` in the LEXER. Raising the ceiling from 1 to 2 is recorded with its reason and its
      way back — `Lexer.scala` is mine, `Parser.scala` is another claim's, and tokenising `@` alone
      would just move the refusal one layer down. Filed in `v3/BACKLOG.md`.

**Measured:** UniML 218/218, seven v3 gates green, N = 188 of 368 with CRASH 0. DIFF is 4, and none
is mine — `effects`, `effects-handler`, `head-field-effect-shadow`, `parameterless-def-local`
contain no annotation at all (`^@` count zero), so a change that only touches annotation handling
cannot reach them. Attribution by construction rather than by re-running.

## SSC3-12 — host IO, bytes, strings, and the last receiver-blind resolution

Four slices, written down before any of them is coded. They are ordered so each one is landable
alone and none is blocked on a decision the next one makes.

**THE MEASUREMENT THAT DECIDES ALL OF IT, taken 2026-08-12 before the plan was written:** the v2 VM
— the one `ssc3 run --bridge` executes on — already implements everything needed, so **not one v2
change is required**:

```text
  io.readFile  -> BytesV      io.writeFile <- bytes      io.exists
  str->utf8    utf8->str      blen   bget   bslice   bconcat      bytes->hex   hex->bytes
```

That is why `readFile` is reachable at all. `Lower.hostPrims` maps an extern to the prim that
performs it, and its table is the INTERSECTION of what BOTH lanes do — invariant I-3 made
structural rather than tested. Today it has one entry, `exists`, because the first draft mapped
`readFile`/`writeFile` on the strength of the NAMES matching and the bridge died with
`expected Bytes, got "hello from ScalaScript"`: v2 reads a file to bytes, `std/fs.ssc` declares
`readFile(path): String`. **Every slice below adds to that table only after checking the SHAPE.**

### A — a curried call is flattened by another class's method

`Lower.scala:1804`, the last receiver-blind resolution left in the file:
`sigs.exists((n, ps) => n.endsWith("." + nm) && ps.length == 1 + as1.length + as2.length)`. It is
milder than the two already fixed because it checks arity, and it is the same defect: the receiver
is not consulted, so a same-named method on ANY class can decide that `f(a)(b)` is one call rather
than two. Same shape as the fix in `fillDefaults`: exact `obj + "." + nm` when the receiver is a
NAME, and when it is an expression, use the suffix search only when it leaves ONE answer.

**Done when:** a probe with two classes whose same-named methods differ in shape flattens correctly
in both declaration orders; N does not fall.

### B — `readFile` / `writeFile`, as a COMPOSITION of prims

The extern's body is an expression, so it does not have to be a single prim:

```text
  readFile(p)      ->  utf8->str(io.readFile(p))
  writeFile(p, s)  ->  io.writeFile(p, str->utf8(s))
```

The IR is SHARED, so `Exec` must implement all four. That needs v3 to hold a byte string, which it
has no value for — `Value` is Unit/Bool/Int/Float/Str/Char/Data/Clos/Arr/Partial/Map. So this slice
adds one: `Value.VBytes`. **The hazard is named in advance:** a new `Value` case does not fail to
compile at the matches that need it, because most end in a catch-all — the same shape
`walker-gate.sh` exists for. So the slice includes an audit of every `Value` match that must learn
it (`showV`, `eq`, `binOp`, the bridge's literal writer) rather than trusting the compiler.

**Done when:** a program writes a file and reads it back with the SAME output on both lanes; a
read of a missing path is catchable by a ScalaScript `try/catch` rather than killing the
interpreter; N does not fall.

### C — the byte API, so `readBytes`/`writeBytes` can exist

`std/fs.ssc` declares `readBytes(path): List[Int]`, and v2's `io.readFile` gives `BytesV`, not a
cons list — the same name/shape trap as B. v2 exposes `blen`/`bget`/`bslice`/`bconcat`, so the
conversion is expressible; the open question this slice answers by measuring is WHERE it belongs:
a loop in ScalaScript over an opaque handle (which needs `Bytes` to be a value the language can
hold) versus a pair of prims on both lanes. **Not decided here** — B's `Value.VBytes` is what makes
either possible, and the choice should be made with B landed.

### D — CLOSED BY MEASUREMENT 2026-08-12: the intersection is exhausted

Not a task after all, and the census is the deliverable. Every conformance case was built and its
host-function refusal recorded — the 27 that remain are blocked on:

```text
  6 element        4 signal / eqSignal / computedSignal      5 content{Document,Data,Block,ModuleMetadata}
  2 mkdirs         2 actorGroupTell      sha256  readLine  exec  localStorageGet  webauthnChallenge
```

**Not one of them exists as a v2 prim.** Checked name by name against `v2/src/Runtime.scala`, which
implements ten `io.*` prims and nothing else that answers to these. So `hostPrims` cannot grow
further without a change to v2 — a different subsystem with a different owner — and any v3-only
addition would break I-3 exactly as the table exists to prevent.

That also prices slice C honestly: **the byte API buys zero corpus cases**, because none of the 27
is a byte one and `std-fs-failure-raises` is the only case in the tree that names `readBytes` at
all. It was built for API completeness on the owner's instruction, not for N, and the plan should
not have implied otherwise.

`deleteFile` — needed by `dataset-shape` — is in the same position: no v2 prim, so it stays an
honest refusal. `fromGenerator` and `runParallel` need laziness and parallelism, not IO.

### D (as originally written) — what is still out of reach, and who owns it

`deleteFile` — needed by `tests/conformance/dataset-shape` — has **no v2 prim at all**. A v3-only
`io.deleteFile` would break I-3 exactly as the table exists to prevent, so this one is a v2 change
with a different owner and is NOT in this sprint. `fromGenerator` and `runParallel` need laziness
and parallelism, not IO. Slice D is the honest accounting: re-measure the six blocked `dataset-*`
cases after B, say which moved, and file the rest by what they actually need.

**Claims are taken per slice, on the files that slice edits, and only when it starts editing.**

## SSC3-13 — `extension` inside a `trait`, so the typeclass tower can load

**The owner's design call, 2026-08-13:** `Dataset` should be an abstraction over a `Foldable`
typeclass with `List` as only the DEFAULT backend, and map-reduce should be reachable from it.

**That design is not new — it is already in the tree**, which is what makes this a repair rather
than a proposal:

```text
  std/foldable-traversable.ssc      trait Foldable[F[_]] with extension [A](fa: F[A])
  std/functor-applicative-monad.ssc trait Functor[F[_]], Applicative[F[_]], Monad
  std/mapreduce/                    dataset.ssc shuffle.ssc distributed.ssc cluster.ssc typed.ssc
                                    — and `extern object Dataset`, "lazy, parallel-capable pipeline"
```

**MY OWN WORK IS THE COUNTER-EXAMPLE, and it is recorded here rather than quietly fixed later:**
the prelude's `case class Dataset(items: List[Any])` (SSC3-11) is a SECOND `Dataset`, with the list
welded into the type rather than chosen as a backend. It was the right way to move N yesterday and
it is the wrong architecture for tomorrow. Reconciling it is step 3, and which of the two survives
is the owner's call, not this sprint's.

### The blocker is ONE Tier 0 limit, and it is measured

Higher-kinded types are NOT the problem — `trait Foldable[F[_]]: def sizeOf[A](fa: F[A]): Int`
compiles and runs today, probed before this was written. What stops the tower is:

```text
  std/functor-applicative-monad.ssc:38:3
  a `trait` member that is not a `def` is outside SSC3 core Tier 0
        -> extension [A](fa: F[A]) def map[B](f: A => B): F[B]
```

`extension` INSIDE a `trait`. The same message is **9 refusals in the corpus**, the third-largest
blocker after `unknown name` and `call to unknown function`.

**A — accept `extension` inside a `trait`.** v3 already has extensions at top level
(`v3/extension-gate.sh` guards their vocabulary); this is the same rewrite one scope deeper. Done
when `std/foldable-traversable.ssc` and `std/functor-applicative-monad.ssc` LOAD, and N does not
fall.

**B — measure what that opens.** Which of `std/mapreduce/*` becomes loadable, and which of the 9
refusals actually move. Not predicted here; the census after A decides what is next.

**C — reconcile the two `Dataset`s.** Only with A and B measured, and it is a decision to put to the
owner rather than take.

## SSC3-14 — `math`, and the one member neither lane can do

Measured 2026-08-13 and NOT started. `math` is 4 of the corpus's `unknown name` refusals —
`arithmetic`, `case-classes`, `js-scala-fenced-block`, `sealed-traits` — asking for
`math.pow` (3), `math.sqrt` (2), `math.round` (2), `math.Pi` (2), `math.abs` (1).

The split is decided by what v2's VM has, exactly as `hostPrims` was:

```text
  f.sqrt  f.round  f.floor  f.ceil     v2 has these -> reachable through an extern, like readFile
  Pi  abs                              pure ScalaScript, straight into the prelude
  pow                                  NO v2 prim at all
```

`pow` splits again: an INTEGER exponent is a loop and needs nothing; a fractional one needs `exp`
and `log`, which neither lane has. So the honest deliverable is integer `pow` plus a refusal that
NAMES the fractional case, rather than a silent wrong answer — and adding `f.pow` to v2 is the
alternative, which is a change to another subsystem and a separate decision.

## SSC3-15 — the type-directed-resolution DEBT, owed from SSC3-13

Recorded at the owner's instruction 2026-08-13, the day the approximation was chosen. **The full
statement lives in `v3/specs/20-core-language.md` §4a1**, because a sprint board gets rewritten and
this outlives it; this entry exists so the debt is visible from the working board too.

Short form: typeclass dispatch is being resolved from the receiver's CONSTRUCTOR plus a parameter's
DECLARED type, with the subtrait preferred. It must eventually be static types, a type checker and
type inference. The three edges the approximation has — a `val` carries no type, a widened static
type resolves where Scala would refuse, and the element type is invisible — are written out there,
so they are known limits rather than future bug reports.

### B — DONE 2026-08-13: the census, and it names the next two blockers

Slice A landed (`e417a237a`). What it opened, measured rather than predicted:

```text
  std/mapreduce/failure.ssc      LOADS        std/mapreduce/handlers.ssc   LOADS
  cluster / dataset / index      expected ')', found *        <- VARARGS
  distributed / shuffle / typed  an `import` line must be …   <- SELECTOR-LIST IMPORT
```

Two blockers, both measured, neither guessed:

**1. Varargs.** `std/mapreduce/dataset.ssc:21` is `def of[T](items: T*): Dataset[T]` — and
`Dataset.of` is called **35 times** in the corpus. Tier 0 has no varargs, so this is a language
question, not a parser gap: what does `T*` MEAN when the callee cannot see an array type? The honest
options are a `List[T]` parameter with the call site collecting its arguments, or a refusal that
names varargs instead of pointing at a `*`.

**2. The selector-list import**, `import scalascript.typeddata.{DatasetWire, DatasetWirePartition}` —
**5 corpus cases** and 3 mapreduce modules. v3 refuses it BY DESIGN, and the refusal's own text is
the argument against itself: it says selector lists "have no meaning here, because an import brings
the WHOLE module either way". If the form is a no-op rather than an unsupported feature, ACCEPTING
and ignoring the selector list is closer to that stated semantics than refusing is. That is a
decision about the language and belongs to the owner, not to a parser change.

**What this means for C.** The `extern object Dataset` in `std/mapreduce` cannot load until varargs
are answered, so reconciling it with the prelude's `case class Dataset` is still blocked — on a
different thing than when C was written, and now on a named one.
