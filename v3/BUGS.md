# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

## v3-multishot-handler-without-a-return-clause — a multi-shot `resume` hands back an `Int` where the handler wants a `List`

<!-- status: open
     lane: v3
     area: runtime
     gate: v3/bench-corpus-gate.sh (row effect-multishot)
     found-by: claude-code
     found-at: 2026-08-14 -->

**`bench-corpus-gate` is RED on `effect-multishot`, and it says `STOPPED computing — it produced a
number before`. That message describes the symptom and gets the event backwards: the row did not
break, it stopped lying.**

    ssc3: bench/corpus/effect-multishot.ssc: flatMap needs a List and got the Int 13 — it
    contributes no elements, so the result would be silently short.

The handler is `case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))` with NO return
clause, so `resume(opt)` hands back the computation's own value — an `Int` — and `flatMap` is handed
a non-list. **`7730f6039` (2026-08-12), `flatMap refuses a non-list instead of silently dropping
it`, is what made that visible**, and it landed AFTER the gate's own baseline comment
(`Measured 2026-08-11: 34 of the 36 rows compute`, `55ca34c86`, 2026-08-11 17:35). Before it, the
non-list contributed nothing and the row computed a number that was **silently short** — so the
gate was green over a wrong answer, which is the reading that matters when deciding what to do
about it.

**WHICH SIDE IS WRONG IS NOT SETTLED HERE, deliberately, because the two readings need different
fixes and picking one without measuring is how a bench row gets quietly rewritten to suit the
compiler.** Either the FIXTURE is wrong and its handler needs a return clause lifting `Int` into
`List[Int]`, or v3 is over-strict and a multi-shot `resume` should lift on its own — the refusal's
own second sentence raises exactly that. `specs/direct-style-eval-spec.md` §10.1 and the fixture's
prose (jvm, js and rust all run this workload) are where that gets decided.

**NOT CAUSED BY SSC3-14b, and the control says so rather than the calendar.** Found while gating the
varargs change; the identical refusal appears at the parent commit with the uniml front REBUILT
there (classpath stamp `ef81729f`, not the branch's `dec0f0be`) — without that rebuild the "control"
would have carried the change under test, because `uniml.cp` is keyed on `v3/src` and `v3/uniml` and
never on `uniml/scala/…`. On the kernel front the row fails identically with and without the change,
and `Lower` is the only half either front shares.

**THE OBVIOUS PROBE MEASURES NOTHING — worth writing down, because it is the one anybody reaching
for this entry will try first.** `v3/ssc3 run bench/corpus/effect-multishot.ssc` exits 0 with ZERO
bytes on every state, fixed or broken: the file defines `workload(seed)` and never calls or prints
it, the harness does. Use the gate's own mechanism — `ssc3 bench --warmup 1 --reps 1` and grep for
`BENCH_SINK` — and keep a POSITIVE control beside it (`effect-oneshot` returns 1), so a 0 means the
row rather than the apparatus.

**NOT declared in the gate's `KNOWN_BLANK`.** That list is an admission that a row does not compute
today, and adding this one would turn a red that is telling the truth into silence before anybody
has decided which side is wrong.

## v3-mixed-int-double-arith — the executor refused `1 * 2.0` while its own bridge computed it

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     fixed-in: 9447789af
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-numeric.ssc, run on BOTH lanes) -->

**Measured 2026-08-11.** `binOp` in `v3/src/Exec.scala` had only HOMOGENEOUS arms — `(VInt, VInt)`
and `(VFloat, VFloat)` — so every mixed pair missed all of them and fell through to the failure
case:

    println(1 * 2.0)   ->  ssc3: Mul on Int 1 and Double 2
    println(1 + 2.0)   ->  ssc3: Add on Int 1 and Double 2
    println(7 / 2.0)   ->  ssc3: Div on Int 7 and Double 2
    var r = 4
    println(r * 1000000.0)  ->  ssc3: Mul on Int 4 and Double 1000000

**This was a two-lane divergence, not only a missing feature — which is what makes it an I-3
defect rather than a gap.** The same file on the same commit:

    v3/ssc3 run --bridge mix.ssc   ->  2 2 3 3.5
    v3/ssc3 exec mix.ssc           ->  ssc3: Mul on Int 1 and Double 2

**The direction was measured before it was chosen**, because the other reading — that v3 is
deliberately strict about numeric towers — would have made a widening fix a regression. It is not:
interp, native and the v2 bridge all widen, and all three print `2 2 3 3.5` for the four lines
above. v3 was alone.

**Fixed** by two arms placed beside the existing `VChar` widening arms, which do the same thing for
the same reason. Deliberately narrow in two ways, both measured rather than assumed:

- **Four operators, not five.** `%` on a Double is refused by EVERY lane (native `TYPEERR: %
  requires Int left operand`, interp `No method '%' on Double`, v3 `Rem on Double`), so widening
  `Rem` would only exchange one refusal's message for another while claiming support no lane has.
- **Arithmetic only, no comparisons.** On mixed comparison the lanes disagree with each OTHER:
  interp evaluates `1 < 2.0` to `true`, while native and v2 refuse it at type-check time with
  `cannot unify Int vs Float`. v3 still refuses it, which is the majority position; picking a side
  of that divergence is a separate decision and is NOT made here. That divergence is between v1 and
  v2 and so belongs in the root `BUGS.md`, where it is NOT yet filed — it is carried as a follow-up
  in `v3/SPRINT.md` rather than referenced here as if it already existed.

  **SUPERSEDED the next day — see `v3-mixed-int-double-compare` below.** The paragraph above was
  right about what it had measured and wrong about what it concluded, and the missing measurement
  was v3's OWN bridge: it widens comparisons too, so the executor's refusal was not "the majority
  position", it was v3 disagreeing with itself. Left in place rather than rewritten, because the
  shape of the error is worth keeping: a survey of the OTHER lanes answered a question that was
  about THIS one.

**Guard.** `v3/tests/front/mixed-numeric.ssc` + `.expected`. Fixtures in that directory are run by
`v3/exec-gate.sh` on the executor AND through the bridge, and the two outputs are compared, so a
future one-lane fix cannot pass it. Verified to fail without the fix: reverting `Exec.scala` alone
makes the fixture die on its first line with `Add on Int 1 and Double 2`.

**Found from.** §55 B1 (one timing wrapper for every column): the shared bench wrapper's last line
is `_ssc_reps * 1000000.0` with an Int counter, so this refusal — not any parser gap — is what
stopped the wrapper from running on v3 at all.

## v3-mixed-int-double-compare — `1 == 1.0` was `false` on the executor and `true` on the bridge

<!-- status: fixed
     fixed-in: d1648e07a
     lane: v3
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-compare.ssc, run on BOTH lanes) -->

**Measured 2026-08-11**, the day after `v3-mixed-int-double-arith` fixed the arithmetic half and
deliberately left this one alone. The deferral was wrong, and one measurement it had not taken says
why — the same file on the same commit, executor versus bridge:

| expression   | `ssc3 run` (executor) | `ssc3 run --bridge` | Scala |
|---|---|---|---|
| `1 < 2.0`    | `Lt on Int 1 and Double 2` | `true`  | `true`  |
| `2.0 > 1`    | `Gt on Double 2 and Int 1` | `true`  | `true`  |
| `1 == 1.0`   | **`false`**                | `true`  | `true`  |
| `1 != 1.0`   | **`true`**                 | `false` | `false` |

The earlier entry declined to widen comparisons on the grounds that interp and v2 disagree with
each other, so any choice would take a side. That survey asked about the OTHER lanes and the
question was about THIS one: v3's two lanes disagreed on every mixed comparison, which is I-3, and
the bridge's answers are also Scala's.

**Equality was the dangerous half.** An ordering comparison REFUSED stops the program and gets
looked at. `1 == 1.0` returning `false` is a wrong answer that nothing reports: the program simply
takes the other branch.

**Fixed** by adding `Lt/Le/Gt/Ge/Eq/Ne` to the widening arms already in `binOp`, so a mixed pair is
retried as two Doubles.

**Where it is NOT fixed, deliberately.** The first draft widened inside `eq` — the helper shared by
scalar `==`, collection equality and pattern matching — which also made `List(1) == List(1.0)` true.
That is Scala's answer, but measurement says every lane here disagrees with Scala together: the
executor, the bridge AND interp all answer `false`. Widening there would have repaired one
divergence by opening another where the lanes were consistent. Fix what disagrees; leave what
agrees. The both-lanes fixture caught this in one run — it is asserted at its current value so the
regression cannot come back silently.

**Still open, and not v3's:** interp answers `1 == 1.0` with `false` while the v2 runtime answers
`true`, and the native front refuses every mixed comparison at type-check with `cannot unify
Int vs Float`. Filed as `lanes-disagree-on-mixed-numeric-comparison` in the root `BUGS.md`.
