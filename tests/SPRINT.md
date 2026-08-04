# Test harness — sprint

This module's queue. **Two states and no third:** `[~]` in progress, `[x]` done. Anything not being
worked on belongs in `tests/BACKLOG.md`. Layout: [`../specs/work-tracking-layout.md`](../specs/work-tracking-layout.md).

- [~] `f4-dualrun-gate-compares-F-with-ITSELF-since-the-front-flip` — `specs/v2.2-p6.5-dualrun.sh`
      runs `SSC_FRONT=F bin/ssc run` against a BARE `bin/ssc run`, but `RunNativeV2.frontIsF` is
      `!SSC_FRONT.equalsIgnoreCase("legacy")` — so both sides are F and the gate whose entire
      purpose is comparing the two fronts has been comparing one with itself since `56d7d705f`.
      Vacuously green ever since. Fix: the baseline side sets `SSC_FRONT=legacy`, the labels stop
      calling the thing under test "default", and `--self-test` plants a divergence and requires
      the gate to FAIL.
