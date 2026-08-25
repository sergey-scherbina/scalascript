# Test harness — sprint

This module's queue. **Two states and no third:** `[~]` in progress, `[x]` done. Anything not being
worked on belongs in `tests/BACKLOG.md`. Layout: [`../specs/work-tracking-layout.md`](../specs/work-tracking-layout.md).

- [~] **THE SMOKE JOB CAP IS UNDER THE SUITE AGAIN** (claim `smoke-job-cap`, BUGS
      `smoke-job-cap-no-longer-looser-than-the-suite`). The 2026-08-15 fix held ten days and the
      suite grew back. Measured 2026-08-25 from run 32842423639 (`af87dfd8f`, killed at the cap):
      job cap `timeout-minutes: 30`; setup before the suite starts 11:27:47 -> 11:34:38 = **6.9
      min**; the suite derived a budget of **1717 s = 28.6 min** (it was ~1095 s after 2026-08-15);
      at the kill it had logged **94 of 136 checks** in 23.4 min and was still running. The rule the
      cap is written under — strictly LOOSER than budget plus build — needs **35.6 min**.
      Successful runs sit at 25.4-28.6 min and cancelled ones at 30.3-30.4, so the distribution
      straddles the cap and a green is a coin flip rather than a verdict; `0c71bfe45` and
      `7c048a081` died the same way.

      **DECIDED 2026-08-25 by Sergiy: BOTH levers**, where 2026-08-15 used one.

      1. **Take 661.1 s (11.0 min) off the push path** — the `f-*` and `ui-*` gates that accumulated
         since the last move: f-plain-class 75.8, f-guarded-arm 72.1, f-private-member 67.0,
         ui-select-from 64.7, f-context-bounds 63.5, f-triple-interp 56.1, f-ui-signal-counter 51.7,
         f-for-tuple 45.4, f-placeholder-ctor 45.1, ui-provider-gap 40.4, f-front-cache 35.6,
         ui-computed-signal 28.8, nativeui-annotation 14.9. Mark them `optional` in
         `scripts/smoke-ci.ssc` AND wire them as a named step in `ci.yml`'s tier-2 job — `optional`
         alone is deletion wearing a flag, because `ci.yml` does not run `smoke-ci` at all.
      2. **Raise `timeout-minutes` 30 -> 45.** After (1) the push path is ~27 min, so the rule holds
         with ~18 min of room instead of the ~3 min that decayed in ten days. Raising ALONE was
         rejected on 2026-08-15 for the reason that still applies — nothing then limits the suite's
         growth — which is why it is paired with (1) rather than used instead of it.

      **Done when:** `scripts/smoke-ci --list` reports the new optional count (an optional check and
      a deleted one must never look the same in a log), the local suite is green, and a real push run
      of `smoke.yml` COMPLETES instead of being cancelled.

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

      **1. [x] THE CENSUS — DONE 2026-08-15, and NO TWO COLUMNS AGREE.**
         A battery of small programs, each isolating ONE typing question (declared param, declared
         return, declared `val`, use-derived conflict, arity/shape, `TyDyn` escapes), run through
         every lane and every checker entry point, with the verdict recorded verbatim. The artifact
         is the table; the finding is which cells disagree. **No fixes in this step** — a census that
         also changes things cannot be re-run against its own baseline.

         **Result: `tests/BUGS.md no-two-type-checkers-in-this-repo-agree`.** Seven programs, six
         lanes. Six of the seven are ill-typed under any reading and each is treated differently by
         at least three lanes. The worst cell is not a rejection but TWO DIFFERENT WRONG ANSWERS:
         `def h(a: Int) = a + 1; h("x")` gives `x1` on v1 and v3, `121` on js, and a rejection on v2
         and jvm. **js never rejects anything** — six of six ill-typed programs print a value.
         **And the two checkers are complementary halves of one:** v2 has inference without
         declarations (`skipTypeAnnot` throws the annotation away), v1 has declarations without
         inference (return and `val` types checked, parameters not, and it is not on the `run` path
         at all). Two consequences need no contract decision: `ssc-tools check` green-lights programs
         that crash on its own runtime, and `run --v1` type-checks nothing.

      **2. [x] THE BLAST RADIUS — MEASURED, AND IT IS ZERO.**
         Upper bound already counted: of 399 conformance cases, 155 declare a typed parameter, 64
         declare `jvm` among their backends (so scalac already checks them), 16 do both — leaving
         **139 cases with typed parameters that no strict checker has ever seen.** That is an upper
         bound, not a prediction. The real number needs `parseParam` to stop skipping and
         `ssc1inferLam` to seed from the annotation, behind an env flag, and the corpus run. Nothing
         flipped.

         **DONE 2026-08-15, and the answer is 0 of 399.** Implemented and measured rather than
         estimated: 148 conformance cases declare a parameter in the recognised form and **not one
         is rejected** when the constraint is on. The corpus was already type-correct; nothing
         checked it. Full smoke 110/110 green with it on.
         **The zero is controlled**: a deliberately ill-typed case planted into the corpus IS
         reported by the same sweep, and the sweep saw all 399 files — so 0 means "nothing to
         reject", not "the instrument did not look".
         **Honest limit of the number:** it measures the NARROW set — `Int`, `String`, `BigInt` in
         the simple syntactic position, the same boundary F draws. Widening the set is a separate
         measurement with a separate cost, and `scripts/BUGS.md` records that `Long` could not be
         added to `knownTyName` without a consumer census whose failure mode is a SILENT coverage
         loss.
         **How it is built, so it cannot become a second divergent extraction:** the AST is
         unchanged — `parseParam` still yields the bare name — and a positional registry keyed by the
         DEF's name carries the types, mirroring `funcDefaultsCell`. Keyed positionally and by def on
         purpose: the bare-name keying of that older registry cost a real defect the same day
         (`a6c1806f2`).
         **Two bugs in my own implementation, both found by a PROBE rather than by re-reading it:**
         `Int` lexes as `uid`, not `id`, so the recording silently never fired; and patching the
         block-statement `def` branch left every TOP-LEVEL def unconstrained while the front happily
         recorded. A debug print said `TYDBG reg f any=Y` with the verdict unchanged, which is what
         named the second one.

      **3. [x] THE CONTRACT — DECIDED by Sergiy 2026-08-15.**
         *"Объявленный тип — и документация, и подсказка приведения, и ограничение. Все сразу. Это
         безусловно ограничение, но там где это допустимо и уместно и имеет смысл — это подсказка
         приведения."*

         So: **a declared type is a CONSTRAINT, unconditionally. Where a coercion is admissible it
         is also a coercion hint.** Both halves, and the constraint is not conditional on anything.

         **"Where admissible" has to be a CLOSED, NAMED SET or it means "wherever some implementation
         happens to do something" — which is today's state.** The set is not invented here; it is
         read off what the language already does, measured 2026-08-15:

         | site | coercion today | on which lane |
         |---|---|---|
         | **operator** | `1 + 1.5` -> `2.5`, `1 == 1.0` -> `true` | native AND v1, agreeing |
         | **declaration** — parameter | none: `f(a: Double)` given `1` prints `1`, not `1.0` | no lane |
         | **declaration** — `val` | none: `val d: Double = 1` prints `1` | no lane |
         | **declaration** — parameter, js only | `_charCodeOrNull(a) ?? a` turns a 1-char String into its code point | **js alone, and it is the wrong answer** |

         **THE MEASUREMENT THAT MAKES THIS TRACTABLE: nothing depends on declaration-site coercion,
         because none exists.** Turning the constraint on cannot break code that relied on a
         conversion; the only thing it can break is code that was silently ill-typed. That is
         precisely what step 2 counts, and it is a much cleaner question than it looked.

         **The admissible set, therefore:** the numeric widenings the language already performs at
         operators (Int -> Double and the rest of that tower). Everything else is an error at a
         declaration — String -> Int, Bool -> Int, and the narrowing Double -> Int included. The js
         String -> Int char-code coercion is NOT in the set and becomes a defect under this contract
         rather than a design choice (`v2/BUGS.md a-declared-parameter-type-means-four-different-things-on-four-lanes`).

         **Open sub-question, to be answered by measurement rather than taste before implementing:**
         whether `Char` -> `Int` belongs in the admissible set. `specs/v2-char-is-an-int.md` says a
         Char IS an Int on this lane, which would make it a widening rather than a coercion — but a
         one-character STRING is not a Char, and conflating the two is what produces `f("x") = 120`.

      **4. [~] CONVERGENCE — the gate EXISTS and is green; the convergence itself is the backlog.**
         `tests/e2e/declared-type-agreement.sh`, done 2026-08-16 as a **frozen table rather than an
         assertion**: demanding agreement today would put a red gate in the suite on day one. It
         freezes every lane's current verdict and fails when one changes SILENTLY; the table can only
         shrink, and each shrink is a deliberate edit somebody reads. It prints the progress —
         **1 row in agreement, 2 diverging** — and carries a `--self-test` that plants a wrong cell
         and requires the gate to fail on it, so a table matching everything cannot be mistaken for a
         table comparing nothing.

         **The SHAPE of the remaining work changed, which is the point of having the table.** On
         2026-08-15 the same three-line program got FOUR treatments and two different VALUES — `x`,
         `x`, `120`, and a scalac rejection. The `120` is gone (`3555c8fce`), so three lanes now give
         IDENTICAL answers and the only divergence left is **"native constrains, the interpreter, js
         and v3 do not"**. One line of work rather than a four-way reconciliation — and blocked
         behind the same 14 typer gaps as steps 2 and 3.

      **5. [x] THE CLAUSE MODEL — DONE 2026-08-16. Asked for by Sergiy, and it is the one remainder
         step 2 measured and could not close.** *"Сделай модель клауз чтобы тайпер правильно работал
         для каррированых функций."*

         **Landed:** one arrow per explicit clause at all three sites that build a def's type (the
         `checkStat` pre-pass, the self-recursion binding, the use-site symbol), contextual clauses
         folded onto the last explicit one, and a "not a function" arm restricted to a named set of
         primitives. `two(1)(2)(3)` is rejected; `two(1)(2)`, `tri(1)(2)(3)`, `val p = two(1); p(2)`
         and the four cases the reverted attempt broke are all accepted; the corpus keeps **9
         rejections, 0 new, 0 lost** and `check examples/*.ssc` exits 0. Guarded by
         `tests/e2e/v1-check-sees-what-it-runs.sh` (24 checks), where
         every curried rejection is PAIRED with the legal call the first attempt broke — a gate
         holding only the rejection would pass on a checker that rejects everything.
         Full write-up: `tests/BUGS.md v1-typer-flattens-parameter-clauses`.

         **The measured state, which is why this is its own step rather than a missing assertion.**
         `Typer.scala` flattens every parameter clause into ONE list at both sites that build a
         function's type (the `checkStat` pre-pass and the real `Defn.Def` case). So
         `def two(a: Int)(b: Int): Int` types as `(Int, Int) => Int`, and:
         * `two(1)(2)(3)` — over-applied, dies at runtime with `Not callable: 3` — is ACCEPTED,
           because `two(1)` reads as an underflow (permitted: trailing params may have defaults)
           and `(...)(...)`'s outer apply then re-reads the SAME flattened type.
         * The obvious catch — "applying a concrete primitive is an error" — was implemented,
           measured and **REVERTED**: with clauses flattened, `two(1)` types as `Int`, so a LEGAL
           curried call is indistinguishable from applying a primitive. It rejected four correct
           corpus cases, two of them the very cases that pin currying as intended
           (`curried-def-clauses`, `curried-def-three-clauses`, `fewer-braces-colon`,
           `tagless-resolution`). **That refutation is the evidence for this step:** the check is
           not wrong, the TYPE is, and no assertion on top of a flattened type can be right.

         **The model:** one clause = one arrow. `def two(a: Int)(b: Int): Int` becomes
         `Int => (Int => Int)`. Then `two(1)` is a Function, `two(1)(2)` is `Int`, `two(1)(2)(3)`
         applies an `Int` — an error that needs no special case, only a `case _` at the apply site.

         **The two things that make this not a one-line change, both to be measured not guessed:**
         * **`using`/`implicit` clauses are auto-supplied and must NOT become arrows.**
           `def display[A](a: A)(using s: Show[A]): String` is called `display(x)`, and nesting
           would make that an under-application whose type is a Function rather than a String.
           Corpus: 6 files use multiple clauses, **2 of them are using-clauses** —
           `tagless-*`. They keep today's flattening (appended to the last explicit clause, where
           underflow tolerance already covers them).
         * **`checkAssignable` compares `Function` arity-wise** (`ap.length == ep.length`), so a
           curried def passed where a 2-arg function is expected changes verdict. Blast radius to
           be counted before landing, not after.

         **Acceptance, and it is a DIFFERENTIAL not a green run:** `two(1)(2)(3)` must be rejected
         by `check`; the four reverted cases and the whole corpus must keep today's verdict count
         (9 rejections, 0 new, 0 lost). Both numbers, or it did not land.

      **6. [~] THE PRELUDE THE CHECKER KNOWS MUST COME FROM THE RUNTIME, NOT FROM A LIST.**
         **Source A (interpreter globals) is DONE**; source B turned out to be a different question
         entirely and is filed separately — see the two paragraphs at the end of this step.
         Opened 2026-08-16 as the direct consequence of step 5's landing, and it is what blocks the
         LAST two pieces of this programme.

         **The measurement, taken before any code.** `Typer.scala` decides whether a name exists from
         a hand-maintained `pluginBuiltins` list. The interpreter decides from its own global table.
         Extracting both and diffing them:

         > **111 ambient interpreter globals; the typer does not know 77 of them.**
         > `coroutineCreate` `readFile` `writeFile` `exec` `Http` `Random` `Response` `Logger`
         > `Stream` `Env` `Files` `Cache` `Clock` `Retry` `Window` `attr` `oauth` …

         These are not exotic. They are what a program is made of, and `ssc-tools check` believes
         every one of them is an undefined name.

         **What it already cost, twice, in one night:**
         * Turning argument inference on rejected **11 of the examples CI checks on every push** —
           `Node`, `Transport`, `vstack`, `__ssc_quote__` — so undefined-name reporting had to be
           switched OFF inside a variadic argument to land step 5 at all.
         * **4 of the 9 conformance cases `check` still rejects** are this: three `coroutine-*`
           (`coroutineCreate`, an interpreter global AND a `std/coroutine.ssc` export) and
           `html-dsl` (`div`). Those 9 rejections are exactly what stops `run --v1` from
           type-checking at all — so **this step is the blocker for step 7 as well.**

         **TWO sources, and conflating them is how the list got into this state.**
         | source | example | how `check` should learn it |
         |---|---|---|
         | interpreter globals | `coroutineCreate`, `suspend`, `__ssc_quote__` | ask a probe `Interpreter` — `BuiltinsRuntime.initBuiltins(p)` then `p.globalsView.keySet` |
         | std module `exports:` | `Node`, `vstack`, `Transport` | the module's own front-matter, for the modules the runtime loads FOR THIS FILE |

         The second is per-file: `examples/fetch-auth.ssc` gets `vstack` because its front-matter says
         `frontend: react`, not because it imports anything. `check` already resolves EXPLICIT
         imports (`check-stdlib-interface-load`); the ambient selection is the missing half.

         **The gate must check BOTH directions**, or a generated list that still needs its manual copy
         has replaced nothing: (a) a program using an ambient global type-checks, (b) `pluginBuiltins`
         no longer lists what the probe supplies, (c) a genuinely undefined name is STILL rejected.

         **Cost to measure before committing to the design:** constructing a probe `Interpreter` runs
         the plugin ServiceLoader. `check` runs over 399 files in CI, so this is memoized once per
         process or it is not viable — and "viable" means a measured number, not an assumption.

         **DONE for source A, 2026-08-16.** `Interpreter.ambientGlobalNames` — a probe interpreter
         with its builtins installed, its keys read — feeds a new `ambientNames` Typer parameter.
         The four conformance cases it was costing pass (`coroutine-*` ×3, `html-dsl`); examples
         211/211; corpus rejections **9 → 5**, all five now the effect verifier alone. Guarded by
         `tests/e2e/typer-prelude-from-runtime-names.sh` (15 checks), which asserts BOTH that the
         names are known AND that they are not string literals in `Typer.scala`.

         **The ordering bug worth remembering:** folded into `extraBuiltins` — i.e. defined LAST, at
         the variadic `Any => Any` sentinel — the ambient names CLOBBERED the prelude's real types.
         `None` became `Any => Any` and seven examples failed with `expected Option[String], found
         Any => Any`. They are now defined FIRST, at `SType.Any`. **A source that knows only a name
         must never outrank one that knows a type.**

         **Source B is not a missing list, it is a LANE MISMATCH, and it stopped this step.** With
         reporting turned on inside variadic arguments, exactly ONE example fails —
         `examples/fetch-auth.ssc` on `text`/`textField`/`actionButton`. Those are `std/ui` exports
         of a `frontend: react` program, and **`run --v1` cannot run it either** (`Undefined:
         vstack`). So CI is asking a v1 typer to check a js-lane program, and the only reason such
         programs pass today is ten names I hand-added on 2026-08-16 that make `check` ACCEPT what
         `run --v1` refuses — `ssc-tools check` says OK on `vstack("a")`, the runtime says
         `Undefined: vstack`. That is this programme's own defect pointing the other way; filed as
         `check-accepts-names-the-v1-runtime-does-not-have` with the three options and their
         consequences, and frozen by the gate's ratchet so the list can only shrink.

         **So `variadicArgDepth` STAYS**, with its reason rewritten: it is no longer "the list is
         incomplete" but "one example belongs to a lane this checker does not model". Removing it
         today would trade a known-permissive checker for a red CI step on a program that is not
         wrong.

      **7. [~] Whether `run --v1` type-checks at all — THE BLOCKER IS GONE; the decision is now the
         only thing left.** The native lane already refuses to run a file its checker rejects
         (`RunNativeV2` requires `OK` from `ssc1-check`). `run --v1` checks nothing.

         The reason it could not be turned on was arithmetic: `check` rejected **9 working
         conformance programs**. Both causes are now closed and the count is **0** —

         | | rejections | what closed it |
         |---|---|---|
         | 2026-08-15 baseline | 9 | — |
         | after the runtime prelude (`a42310890`) | 5 | 4 were names the checker did not know |
         | after the effect-row content checks + truthful annotations | **0** | the last 5 |

         **And the second half mattered more than the count.** The five were rejected for declaring
         no effect row — and under the old verifier ANY row satisfied it, `! Nonsense` included. So
         annotating them first would have produced a clean corpus resting on five declarations that
         meant nothing. The verifier now reads the row (`effect-row-verifier-demands-a-declaration-it-never-checks`),
         each annotation was taken from its own diagnostic, and the corpus contract re-ran 14/14
         across int/js/v2 to show the rows are behaviour-preserving rather than merely accepted.

         **What remains is a DECISION, not a blocker:** should `run --v1` refuse a file `check`
         rejects, the way the native lane does? Arguments both ways belong to Sergiy — it changes
         when a program is allowed to run, not merely what a tool reports. Note the one thing that
         must be settled with it: `check-accepts-names-the-v1-runtime-does-not-have` means `check` is
         still permissive in the OTHER direction, so gating `run` on it would not be symmetric yet.

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
