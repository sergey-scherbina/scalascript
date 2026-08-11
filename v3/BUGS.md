# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

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

**Guard.** `v3/tests/front/mixed-numeric.ssc` + `.expected`. Fixtures in that directory are run by
`v3/exec-gate.sh` on the executor AND through the bridge, and the two outputs are compared, so a
future one-lane fix cannot pass it. Verified to fail without the fix: reverting `Exec.scala` alone
makes the fixture die on its first line with `Add on Int 1 and Double 2`.

**Found from.** §55 B1 (one timing wrapper for every column): the shared bench wrapper's last line
is `_ssc_reps * 1000000.0` with an Int counter, so this refusal — not any parser gap — is what
stopped the wrapper from running on v3 at all.
