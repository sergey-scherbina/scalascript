# Test harness — sprint

This module's queue. **Two states and no third:** `[~]` in progress, `[x]` done. Anything not being
worked on belongs in `tests/BACKLOG.md`. Layout: [`../specs/work-tracking-layout.md`](../specs/work-tracking-layout.md).

- [~] **TYPES MUST BE RIGHT — v1, v2 AND v3.** Mandate from Sergiy 2026-08-15, recorded in full
      BEFORE any code, because by step 3 nobody will remember why step 1 exists.

      **THE GOAL: one answer to "what does a declared type mean", enforced the same way on every
      lane.** Today the same three-line program gets four different treatments and two different
      VALUES, and the two checkers this repo owns disagree with each other.

      **What is already measured, and is the reason this is a programme rather than a fix.**

      `def f(a: Int): Int = a ; println(f("x"))` — one program, freshly built toolchain:

      | lane | treatment of the declared `Int` | prints |
      |---|---|---|
      | native (`bin/ssc run`) | dropped at parse (`skipTypeAnnot`) | `x` |
      | interpreter (`--v1`) | dropped | `x` |
      | js (`run-js`) | read, emitted as a COERCION `_charCodeOrNull(a) ?? a` | **`120`** |
      | jvm (`run-jvm`) | handed to scalac | **`[E007]` compile error** |

      And the two checkers disagree on a program neither annotation is needed for:

          def h(a: Int): Int = a + 1 ; h("x")
            native ssc1-check   ->  TYPEERR: cannot unify Int: Int vs String
            v1 `ssc-tools check` ->  OK

      **What each lane actually has** (first pass, to be completed in step 1):
      * **v2 native** — `v2/lib/ssc1-check.ssc0`, 809 lines, Hindley-Milner (Algorithm W). ON the
        normal path: `RunNativeV2` runs `tower/bin/ssc1-check-run.ssc0` per source file and refuses
        unless it answers `OK`. Infers from USE only; `parseParam` calls `skipTypeAnnot`, so
        annotations never reach it, and `TyDyn` unifies with anything.
      * **v1** — `v1/lang/core/.../typer/Typer.scala`, 2084 lines (+ Types 473, TypeEvidence 190).
        NOT on the `run` path: called only from `check`, `watch`, `watch-bench`, `compile-jvm`,
        `compile-js`. More permissive than the native checker on the one case measured so far.
      * **jvm** — no checker of its own; scalac does it, which is why that lane is the strictest.
      * **v3** — no `unify`/`Typer` under `v3/src` at all; to be established in step 1.

      **1. [ ] THE CENSUS — one table, every lane, every question.**
         A battery of small programs, each isolating ONE typing question (declared param, declared
         return, declared `val`, use-derived conflict, arity/shape, `TyDyn` escapes), run through
         every lane and every checker entry point, with the verdict recorded verbatim. The artifact
         is the table; the finding is which cells disagree. **No fixes in this step** — a census that
         also changes things cannot be re-run against its own baseline.

      **2. [ ] THE BLAST RADIUS — what does honouring annotations cost.**
         Upper bound already counted: of 399 conformance cases, 155 declare a typed parameter, 64
         declare `jvm` among their backends (so scalac already checks them), 16 do both — leaving
         **139 cases with typed parameters that no strict checker has ever seen.** That is an upper
         bound, not a prediction. The real number needs `parseParam` to stop skipping and
         `ssc1inferLam` to seed from the annotation, behind an env flag, and the corpus run. Nothing
         flipped.

      **3. [ ] THE CONTRACT — Sergiy's decision, taken with 1 and 2 in hand.**
         A declared type is documentation, a coercion hint, or a constraint. Any one is defensible;
         having all three simultaneously is what produces three answers to one program. **Not an
         agent's call**, and it is the reason steps 1 and 2 come first.

      **4. [ ] CONVERGENCE, gated.** `tests/e2e/declared-type-agreement.sh`: the same program on
         every lane must give ONE verdict. Whatever the contract turns out to be, agreement is
         required by all three of the possible answers, so the gate can be written before the
         decision and will simply encode it afterwards.

      **What I will not promise:** I cannot decide the contract, and I will not flip a language rule
      because a corpus number looked acceptable. What I can do is make the disagreement visible,
      priced, and impossible to reintroduce quietly.

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

      **1. [x] The mechanism — make taking work cheaper than choosing it.**
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

      **2. [x] The audit — measure whether a gate can fail at all.**
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
         Prediction, written before the work: 0-3 genuinely vacuous among the 48. **DONE, and the
         answer is 0** — of 105 wired gates that invoke a launcher, 91 fail when it fails and the
         other 14 decline by design (12 `ssc_usable_or_skip`, 2 their own check), declared by name
         with the reason. Nothing swallows a failure.
         THREE MEASUREMENT DESIGNS WERE BUILT AND THROWN AWAY BY THEIR OWN RESULTS: selecting gates
         by grepping for a launcher NAME caught 13 that only mention one (the same "a comment is not
         a caller" error a sibling had fixed hours earlier); dropping the filter put 51 of 151 in a
         declared list and buried the signal; the third replaces the launcher with a stub that
         RECORDS its call, so membership is observed instead of guessed.
         AND IT TOOK TWO MORE FIXES TO REPRODUCE. A timeout was being folded into "red", i.e. into
         healthy — the audit reporting a verdict it never obtained, which is the exact defect it
         hunts. And the residual variance was NOT a flaky gate: both suspects failed 5/5 alone with
         durations steady to the second, and only ever passed inside the sweep, because wired gates
         share hard-coded ports and answer each other's requests. Filed as
         `wired-gates-share-hard-coded-tcp-ports`; the audit now re-verifies each blind candidate
         ALONE, and two consecutive runs give an identical verdict (105 / 14).

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

- [x] `pre-push-message-backticks` — the overlap guard's refusal message is written into an
      **unquoted** `<<EOF` (`.githooks/pre-push:413`), so the six backticked identifiers in its prose
      are command substitutions the shell runs while printing a refusal. Met as a reader on
      2026-08-14, not by reading the code: a correctly-refused claim printed
      `command not found: items:`, `command not found: --items`, `slug: No such file or directory`
      and then twenty lines of `scripts/coord-claim` usage — because it EXECUTED
      `scripts/coord-claim`. The reader's first suspicion is their own invocation, which is the
      worst possible moment for this message to be corrupt.

      **Census first, so the size of the claim is measured rather than assumed:** 19 backticked
      lines sit inside unquoted heredocs across `.githooks/`, `scripts/` and `tests/`, and **15 of
      them are already escaped** `\``. The same file escapes correctly at lines 133 and 140. So this
      is one prose block, lines 421-425, not a rot to sweep.

      1. Fix: the dynamic line (`$problems`) leaves the heredoc as a `printf`, the static prose
         becomes `<<'EOF'`. Text preserved byte-for-byte; the class removed rather than the six
         backticks escaped, because escaping is the thing that fails at 95%.
      2. Ratchet: `tests/e2e/no-live-backticks-in-heredocs.sh`, wired into `scripts/smoke-ci.ssc`.
         An UNESCAPED backtick inside an UNQUOTED heredoc body, over 348 tracked shell files, 1.8 s.
         Four controls (P-6.1b): the pre-fix spelling must be detected, and the escaped, quoted and
         merely-mentioned spellings must not.

      **Two things went wrong in the gate before it was green, and both are the standing shapes.**
      It first read `<<WORD` inside a STRING as an opener, ran to end-of-file and reported 18
      backticked COMMENTS — the sibling gate's comment-stripping lesson, met the same way. And its
      population was a path allow-list covering **314 of 348** tracked shell files, missing the
      extensionless tools outside those directories: `bin/ssc`, `v2/ssc`, `v3/ssc3`, the launchers.
      The launcher every agent runs was outside the check. Population is now a property, not a list.

      **Why a gate for one site:** this is the eighth occurrence of the class in this project. Seven
      were an agent's own typing — `git commit -m`, `coord-release --note` — and were answered by
      `--note-file` (`7bcfab999`) and by a memory rule. This is the first one that is CHECKED-IN
      CODE, where no habit of mine can reach it and every agent meets it at the same bad moment.

      **DONE 2026-08-14, and the task grew twice on the way — each widening claimed BEFORE the
      edit, one file at a time.** Beyond the fix and the ratchet:

      - **`origin/main` was RED for everyone** on `freeze-consistency-gate`: `41ae217cd` removed the
        `known-red:` from `extension-call-in-a-def-body.ssc` and left its `KNOWN-RED` row in
        `corpus-baseline.tsv`, with that claim already released, so nobody owned the repair. Since
        smoke is the pre-push suite it blocked every push. Verified on origin/main itself, not only
        locally, and at 3.6 s it was not a load timeout. The deletion forces the paired
        `baseline-sha256=` to be recomputed — the current digest was RECONSTRUCTED from the unedited
        file first, and `roster-sha256` measured to cover the roster body only, so it cannot cascade.
      - **`build-ram-guard --self-test` measured the machine, not itself** — a machine-wide process
        count across two tiers it runs under `DRY=1`, which cannot kill. 1 failure in 20 at load 45;
        the correct assertion already existed in its own gate ten lines away. Fixed, because it was
        the only red in a 96/97 smoke and an intermittent red teaches people to stop reading the
        suite — the `lint-markdown` failure mode arriving from the other direction.
      - **Filed, not fixed:** `v2-unknown-member-on-a-builtin-receiver-yields-a-closure-instead-of-refusing`
        — `"a".nosuch` prints `<closure>` on both v2 fronts where `int` refuses. Found while checking
        the known-red removal was honest; the first reading (that the extension fix caused it) was
        killed by one probe.

      **Final: 97/97 smoke green, 887.9 s of a 1027 s budget** — under budget, on the tree pushed.

- [x] `ci-status-guard-owns-its-repo` — `tests/e2e/ci-status-guard.sh` built its claim fixture with
      `git -C "$ROOT" worktree add`, i.e. it MUTATED the shared main repo from inside the pre-push
      suite. BUGS entry `ci-status-guard-races-the-shared-repo-index-lock` blamed "the shared index
      lock"; that root cause is **refuted by measurement** — 20 same-basename races in an empty repo,
      5 in a full 7 688-file checkout, 5 against a prunable leftover holding the basename and 6
      against a 400-iteration `worktree prune` loop, **zero failures across all 36** — and
      `git worktree add` never touches the main index at all. The 2026-07-31 failure stays
      unexplained, and the entry says so rather than claiming a fix it cannot demonstrate.

      What WAS proven: **three permanent leftovers** — `.git/worktrees/claim-wt`, `claim-wt1`,
      `claim-wt2` (09/08, 09/08, 12/08) plus three orphan `feature/ci-red-main-final-fixture-*`
      branches. `git worktree prune` will not collect them: it honours `gc.worktreePruneExpire`,
      three months by default. The success path cleans up correctly; **only interrupted runs leak**,
      one registration each, forever. And the check's own cost scaled with the litter, because
      `coord-status` walks `git worktree list` and this file calls it seven times.

      Landed: the fixture builds in a **throwaway clone** (`--local --no-checkout`, 0.066 s — git
      hardlinks the objects, so it is cheaper than what it replaced), the tree's `scripts/` is copied
      over the clone's committed copy so the code under test stays the WORKING TREE's and not
      `HEAD`'s, and the guard asserts at both ends that the shared repo's `claim-wt*` registrations
      and `feature/ci-red-main-*` branches are unchanged. **67 s -> 33 s**, and 19.8 s inside smoke.
      Shared repo swept: 110 registrations -> 94, every remaining directory present.

      Two things the work turned up that were not in the plan. **One:** the clone made a latent
      defect in `scripts/coord-status` block — `claim_activity_epoch` held the only three `git log`
      calls in that file without `-C "$ROOT"`, so "live by commit activity" resolved against the
      CALLER'S working directory, silently. Same repo, same instant: 4 claims stale from inside the
      repo, 5 from `/tmp`, and a stale claim is one the triage table says may be reclaimed. Filed
      and fixed as `coord-status-activity-lookup-reads-the-callers-cwd` in `scripts/BUGS.md`.
      **Two:** the new assertion itself read `--git-common-dir`, which answers a RELATIVE `.git` from
      the main checkout — from any other CWD both sides of the comparison were empty and it passed
      without looking. `--path-format=absolute`, plus a refusal when the registry is unreadable;
      re-verified from `/tmp`, where the reverted control now fires and the fixed guard passes.

- [x] `f4-dualrun-gate-compares-F-with-ITSELF-since-the-front-flip` — `specs/v2.2-p6.5-dualrun.sh`
      runs `SSC_FRONT=F bin/ssc run` against a BARE `bin/ssc run`, but `RunNativeV2.frontIsF` is
      `!SSC_FRONT.equalsIgnoreCase("legacy")` — so both sides are F and the gate whose entire
      purpose is comparing the two fronts has been comparing one with itself since `56d7d705f`.
      Vacuously green ever since. Fix: the baseline side sets `SSC_FRONT=legacy`, the labels stop
      calling the thing under test "default", and `--self-test` plants a divergence and requires
      the gate to FAIL.
