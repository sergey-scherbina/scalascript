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
