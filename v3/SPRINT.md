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
