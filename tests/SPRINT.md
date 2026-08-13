# Test harness — sprint

This module's queue. **Two states and no third:** `[~]` in progress, `[x]` done. Anything not being
worked on belongs in `tests/BACKLOG.md`. Layout: [`../specs/work-tracking-layout.md`](../specs/work-tracking-layout.md).

- [~] **GATE-HEALTH PROGRAMME — three steps, one goal.** Agreed with Sergiy 2026-08-13. Recorded
      here in full BEFORE any code, because by step 3 nobody will remember why step 1 exists.

      **THE GOAL: a check that cannot fail must not be able to exist quietly, and the problems we
      already know about must have a route to being fixed rather than a route to being filed.**

      The evidence this rests on, measured 2026-08-12/13. The same defect shape appeared six times
      in one day — a check reporting green because it does not look: `v1-jit-size` unwired for two
      weeks; the orphan ratchet matching its own frozen list; my self-test that could not fail; a
      vacuity control that could not tell SKIP from vacuous; the smoke budget derived from a sample
      filtered by success. Each cost more than the defect it was supposed to catch. And the counts:
      **185 gates, 23 with a self-test, 33 invoked by nothing, 84 open board entries against 3-6
      live claims.** The queue is not the bottleneck — attention is.

      **Sergiy's objection, which reordered this plan and is the reason step 1 comes first:** *"Who
      will fix the problems you find? Agents say it is not my problem and nothing gets done."* He is
      right. What actually got fixed on 2026-08-12/13 was fixed because it BLOCKED someone —
      `v1-jit-size` because a split could not be verified without it, the `renderTerm` baseline twice
      in a day by two different agents because main was red. What was merely filed did not move,
      except one entry a sibling took within hours *because it carried a measurement and a named
      gate*. So a finding must either block something or arrive claimable; anything else is a note.

      **1. [~] The mechanism — make taking work cheaper than choosing it.**
         - `scripts/next`: one command answering "what should I do", ranked on COMPUTABLE signals
           only (has a named gate → has an acceptance test; `confirmed:`; `kind:`; age), excluding
           what is claimed, printing the ready-to-paste `coord-claim` line. Totals on line one, so
           "there is nothing to do" stops being the default assumption.
         - One line in `AGENTS.md` **§2 "Before starting"** — the pre-work checklist, NOT POLICY.
           Measured lesson `agents-obey-the-checklist-not-the-policy`: the checklist beat the policy
           for weeks. And the section literally named "Immediate next steps for Claude Code" is
           dead bootstrap text from project inception ("git init", "scaffold README.md") — the one
           place whose title promises this answer currently answers nonsense. It gets replaced.
         - A QUALITY BAR enforced by `bugs-index-gate` (already on every push): an open entry must
           carry a `gate:` naming an acceptance test, or state the next measurement. Without it an
           entry cannot be claimed — there is no way to know when it is done — so it is a note, not
           a task. Ratchet: freeze today's non-conforming, fail on a NEW one.

      **2. [ ] The audit — measure whether a gate can fail at all.**
         Control A, cheap and universal for the 48 wired checks that need a launcher: remove both
         launcher jars, run them, every one must go RED. Validated on 20 gates already — 19 red, and
         the one green was `f-char-escape-gate`, which SKIPS by design, so the control cannot
         separate "asserts nothing" from "declines to judge" and that limit is part of the design.
         The 30 board/shell checks get no signal from it; for them the honest output is a named
         "no evidence" list, which is the work, not a failure of the audit.
         Merged with the orphan ratchet rather than built beside it: **orphan and vacuous are one
         defect measured two ways** (green by not running / green by not looking), and two frozen
         lists over the same population is a second decision site. One table, two axes. The cell
         that matters most is UNWIRED **and** VACUOUS — that is the only evidence that justifies
         DELETING a gate, which today's subject-liveness triage could not give (0 of 33 dead).
         Prediction, written before the work: 0-3 genuinely vacuous among the 48. **A zero is a
         result, not a failure** — it would move all attention to the 30 with no evidence.

      **3. [ ] The drain — one general list, worked in batches.**
         Not "file and hope". Batches with a shared root cause where one exists: the 2026-08-02
         sweep found TWO mechanical causes explaining 22 of 26 failures, so 16 symptoms are likely
         3-4 causes and one session, where 16 separate entries would be nobody's. Order: wire the 17
         vacuity-checked passers into tier 2 (33 -> 16); diagnose the 16 failures as a batch; then
         the audit's output. Anything that turns out to belong to another area gets an entry WITH
         an acceptance test and, where possible, enforcement scoped to the FILE that would cause it
         — a ratchet that fires on an unrelated person is how gates get deleted (`renderTerm` grew
         from honest Rust work and stopped that agent twice in a day).

      **What I will not promise:** I cannot assign work to another agent. I can do three things —
      do it myself, make it block, and write it so the next reader need not re-derive it. For these
      findings the answer to "who fixes them" is me, in batches; the detectors exist to make the
      work finite and to stop it growing back.

- [x] `f4-dualrun-gate-compares-F-with-ITSELF-since-the-front-flip` — `specs/v2.2-p6.5-dualrun.sh`
      runs `SSC_FRONT=F bin/ssc run` against a BARE `bin/ssc run`, but `RunNativeV2.frontIsF` is
      `!SSC_FRONT.equalsIgnoreCase("legacy")` — so both sides are F and the gate whose entire
      purpose is comparing the two fronts has been comparing one with itself since `56d7d705f`.
      Vacuously green ever since. Fix: the baseline side sets `SSC_FRONT=legacy`, the labels stop
      calling the thing under test "default", and `--self-test` plants a divergence and requires
      the gate to FAIL.
