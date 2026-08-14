# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

## v3-extern-member-in-an-object-has-no-meaning — one front refuses it, the other silently makes it an unpositioned crash

<!-- status: open
     lane: v3
     area: front
     gate: none
     found-by: claude-code
     found-at: 2026-08-14 -->

**`object math: extern def sqrt(x: Double): Double` is not expressible, and the two fronts fail
differently — which is the part that matters.**

    SSC3_FRONT=v3   ssc3: …:2:3: only `def` members are supported in a object at Tier 0, found extern
    uniml (default) (def "sqrt" (params (p "x")) (prim "__throw__" (str "an implementation is missing (`???`)")))

v3's own parser REFUSES, with a position, which is honest. uniml DROPS THE KEYWORD and gives the
member the body it gives any bodyless member — proved by a control: a member written without
`extern` at all projects to the identical `???`. So on the default front the declaration is accepted
and the program dies at run time with **no position and no name**, which `corpus-report.sh`
classifies CRASH rather than UNSUPPORTED.

**Found while wiring `math` (SSC3-14).** Worked around by declaring the four host hooks at TOP
LEVEL — `__mathSqrt`, `__mathFloor`, `__mathCeil`, `__mathRound` — with `object math` delegating.
The `__` prefix is not decoration: the prelude loads for every program, so a bare `sqrt` there would
shadow a program's own.

**A `Lower` change for this was written and REVERTED rather than shipped.** Resolving `hostPrims`
over object members is correct and unreachable — no front produces an abstract object member for it
to act on — and dead code that looks like support is worse than none. The comment at the
`objectDefs` site records this so the next reader does not re-derive it. When a front learns the
keyword, that is the line to add back.

**The fix is a FRONT pair**: v3's parser accepts `extern` as an object member and marks the def
abstract; uniml carries the keyword instead of dropping it. Both, or the divergence just changes
shape.

## v3-math-pow-fractional-needs-a-v2-prim — a fractional exponent has no answer on the bridge, and no v3-only fix can match

<!-- status: open
     lane: multi
     area: runtime
     gate: v3/tests/parity/math-pow.ssc (integer exponents only)
     found-by: claude-code
     found-at: 2026-08-14 -->

**`math.pow` (SSC3-14) is partial: an integer exponent is a multiply loop, a fractional one raises
with a message naming the reason.** The owner asked for that to be fixed. It can be, in one line —
but not in v3.

**WHAT A FIX HAS TO MATCH, measured before choosing one:**

    reference lane   pow(2.0, 0.5) = 1.4142135623730951
                     pow(2.0, 0.1) = 1.0717734625362931
                     pow(10.0,1.5) = 31.622776601683793

libm-exact, bit for bit what JVM `Math.pow` gives. **That rules out every approximation.** A
ScalaScript series, or the repeated-sqrt expansion — which is the tempting one, since `b^f` follows
from the binary expansion of `f` and BOTH lanes already have `f.sqrt` — lands within about 1e-12 and
differs in the last bits, so v3 would disagree with the reference on every fractional pow. Replacing
an honest refusal with a plausible wrong number is the defect this repository keeps paying for.

**AND IT CANNOT BE DONE ON ONE LANE.** v3's executor could call `Math.pow` in a line; the bridge
emits `(prim …)` to v2, and v2 has no `f.pow`, no `f.exp` and no `f.log` — checked name by name
against `v2/src/Runtime.scala`. A v3-only prim runs on the executor and is refused by the bridge,
which is invariant I-3 and exactly the defect closed this morning in
`v3-flatmap-nonlist-lane-divergence`.

**THE FIX**, beside the existing `f.sqrt` at `v2/src/Runtime.scala:1434`:

    case "f.pow" => a => FloatV(math.pow(flt(a, 0), flt(a, 1)))

then v3 wires it exactly as it wired `f.sqrt`: a `hostPrims` entry, an `Exec` case, and a prelude
`def pow(b: Double, e: Double) = __mathPow(b.toDouble, e.toDouble)`. Additive — nothing currently
emits that name.

**NOT TAKEN, because `v2/src/Runtime.scala` is held by the `rust-toint-lane-parity` claim.** Asked
for in the room. This is the second item today blocked on that one file; see the
`v2-bytecode-map-ops` release note, which stopped for the same reason.

## v3-concat-nonlist-splits-three-ways — `List ++ nonList` wraps on native, refuses on the v2 VM, and v3 must pick one

<!-- status: open
     lane: multi
     area: runtime
     gate: v3/parity-gate.sh (a probe was written and WITHDRAWN — see below)
     found-by: claude-code
     found-at: 2026-08-14 -->

**Found by a parity probe written while fixing `v3-flatmap-nonlist-lane-divergence`, and it caught
the fix itself over-reaching.** Three implementations, three answers, for `val n = 5; List(1,2) ++ n`:

    reference native (bin/ssc run)   1,2,5                     wraps
    v2 VM (v3/ssc3 run --bridge)     RuntimeException:         refuses, UNCAUGHT — a Java stack
                                     expected a list, got 5    trace reaches the user
    v3 exec                          ++ needs a List and got   refuses, with a position
                                     the Int 5

**v2 IS INTERNALLY ASYMMETRIC and that is the root of it:** its `flatMap` WRAPS a non-list (measured
— `v3/ssc3 run --bridge` prints `List(10, 20, 30)` for `List(1,2,3).flatMap(x => x * 10)`) while its
`++` refuses in `Prims.unlistPub`. Two operators, same question, opposite answers.

**WHY v3 REFUSES RATHER THAN WRAPPING, for now.** I widened `++` alongside `flatMap` because the
reference native lane wraps, and the new parity probe went RED in the same run: wrapping made
`ssc3 run` disagree with `ssc3 run --bridge`, which is the exact I-3 violation that commit existed
to remove, moved into a different operator. Invariant I-3 is about v3's OWN two lanes, and v3's
bridge IS the v2 VM, so matching v2 is what agreement means here. The widening was reverted.

**THE PROBE WAS WITHDRAWN, NOT SILENTLY DROPPED.** `v3/tests/parity/list-concat-nonlist.ssc` would
sit RED for a divergence in the SHAPE of two refusals (v3's positioned diagnostic versus v2's
uncaught exception) rather than in the answer, and a standing red teaches everyone to stop reading
the gate. It goes back the day v2 picks a side. Re-create it with:
`val n = 5` then `println((List(1,2) ++ n).mkString(","))`.

**THE FIX GOES IN v2**, making `++` agree with its own `flatMap`; v3 then widens to match in the
same commit. Filed here rather than in the repository-root `BUGS.md` — where a cross-module defect
belongs — because that file was held by the `v3-bridge-effects` claim at the time of writing. Move
it when that releases.

## v3-flatmap-nonlist-lane-divergence — `flatMap` and `++` refused a non-list that v3's OWN BRIDGE accepted (was: v3-multishot-handler-without-a-return-clause)

<!-- status: fixed
     lane: v3
     area: runtime
     fixed-in: 54eccf31f
     gate: v3/bench-corpus-gate.sh (row effect-multishot)
     found-by: claude-code
     found-at: 2026-08-14 -->

**RENAMED, BECAUSE THE TITLE I FILED THIS MORNING NAMED THE WRONG MECHANISM.** It read
`v3-multishot-handler-without-a-return-clause` and said the fixture's handler was missing a return
clause. Nothing about multi-shot handlers was wrong. The defect was `flatMap` on a NON-LIST result,
and the effect fixture was merely the loudest place it showed. The wrong name came from taking the
refusal's own advice at face value — the message said "if this is a handler, its return clause is
what lifts the final value" — instead of measuring another lane. That advice was in the code and it
was wrong, which is the kind of diagnostic that costs more than silence.

**THE FACT THAT SETTLES IT NEEDS NO OTHER IMPLEMENTATION: the two lanes of one compiler
disagreed.** `v3/ssc3 run --bridge` printed `List(10, 20, 30)` for a program `v3/ssc3 run` refused.
That is invariant I-3.

    op                     reference (bin/ssc run)    v2 VM (v3 --bridge)   v3 exec, before
    List.flatMap non-list  List(10, 20, 30)           List(10, 20, 30)      REFUSED
    List ++ non-list       List(1, 2, 5)              —                     REFUSED
    List.zip non-list      refuses "expected a list"  —                     refuses

A non-list is ONE ELEMENT on every lane that answers. `zip` refuses everywhere, so it was already
right and is untouched.

**THREE BEHAVIOURS, NOT TWO, AND COLLAPSING THEM IS WHAT WENT WRONG.** `7730f6039` (2026-08-12)
changed this walk from SWALLOWING a non-list to REFUSING it, on the owner's instruction, and it was
right that the swallow was a defect: a swallowed element made `xs.flatMap(f)` produce the EMPTY list
and a `foldLeft` over that returned a number that looked like an answer. What it got wrong is one
sentence — *"v2's runtime still swallows"*. It does not; it WRAPS, and
`git log -S flatMap 4a93c440c..HEAD -- v2/src/` is empty, so it wrapped then too. Swallow, wrap and
refuse are three different answers and only one of them is every other lane's.

**FIXED in `65a4a6d90` by a SECOND function rather than a flag on the shared one.** `listOut` serves
`flatMap`, `zip` and `++`; relaxing it would have made `zip` agree with nobody. So `listOrOne` wraps
a bare value and is used at the `flatMap` and `++` sites only.

**THE CHECK THAT TELLS WRAP FROM SWALLOW**, and it is the one worth keeping:
`tests/conformance/js-effect-multishot-long-fold.ssc` carries a checked-in **204**, v3 answered 0,
and **0 is also what swallowing produces** — so removing the refusal is not evidence on its own. It
answers 204 now, which means the element is contributing rather than merely no longer being refused.
`bench-corpus-gate` goes 33 -> 34 of 36 rows.

**N DID NOT MOVE — 212/369, DIFF 3, CRASH 9 — AND I PREDICTED THAT IT WOULD.** The prediction was
wrong and the reason is structural rather than surprising: both affected programs are invisible to
`corpus-report.sh` by construction. `js-effect-multishot-long-fold` declares `backends: [int, js]`,
so it is LANE-EXCLUDED and never counts in either direction, and `effect-multishot` lives in
`bench/corpus`, which that report does not read at all. I carried the price recorded in
`7730f6039` (CRASH 3 -> 4) forward without re-checking that the same case is lane-excluded today.
**The gate that covers this defect is `bench-corpus-gate`, and reading N for it was reading the
wrong instrument.**

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
