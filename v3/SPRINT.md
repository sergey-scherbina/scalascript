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

- [ ] **SSC3-3d — a clock prim, so v3 can be timed by the same harness as everything else.**
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

- [ ] **SSC3-7a — `effect X:` declarations.** `effect-oneshot.ssc:18:12` — `effect Bump:` →
      `expected an expression, found :`. The IR already reserves `Perform`/`Handle`/`Resume`, so
      this is a front gap, not a representation gap.
- [ ] **SSC3-7b — `multi effect X:`.** `effect-multishot.ssc:19:20` — `multi effect NonDet:` →
      same message, different keyword. Separate from 7a because multi-shot resumption is a different
      executor obligation, and closing 7a must not silently claim this.
- [ ] **SSC3-7c — `!` effect types in a signature.** `effect-pure.ssc:6:35` —
      `def compute(n: Int): Int ! Logger =` → `expected an expression, found =`. The `!` is parsed
      as far as the return type and then the body is not reached.
- [ ] **SSC3-7d — `given name: T with`.** `typeclass-fold.ssc:15:13` (`given intSum: Monoid[Int] with`)
      and `typeclass-monoid.ssc:10:16` (`given intMonoid: IntMonoid with`) →
      `expected an expression, found :`. Tier 2 in `specs/20-core-language.md §2`; queued here with
      the measurement rather than left implicit in that deferral.
- [ ] **SSC3-7e — a block argument, `f { … }`.** `effect-stream.ssc:7:28` — `val (src, _) =
      runStream {` → `expected an expression, found {`. A call whose single argument is a block.
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
- [ ] **15b — `trait` VANISHES on the UniML side, and none of UniML's own gates can see it.**
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
