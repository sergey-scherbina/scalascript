# Conformance corpus and its freeze — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `tests/conformance/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] **type-ascription-matrix — one case for every type name on every lane.** Claim
      `type-ascription-matrix`.

      **Why.** The type-test table is written FOUR times (interpreter, v1 JS, native, JVM) and the
      copies drift SILENTLY, because every differential gate compares the lanes to EACH OTHER — a gap
      all of them share reads as green. Measured over 2026-07-30/31, five separate defects of this
      one shape were found by accident, one probe at a time: `Unit` (int+js), `TupleN` (int+js),
      `Set` (int, and NOT fixable on two lanes), `Map` (js), `List` (native). Each cost a claim, a
      build and a case.

      **What.** One case exercising every type name — `Unit Boolean Int Long Double String Char List
      Seq Map Set Vector Array Option Tuple2 Tuple3` plus a user type and a negative — against a
      golden taken from the JVM lane, which runs real Scala and is therefore the oracle. Known
      divergences are recorded as `corpus-baseline.tsv` rows, which the contract already supports
      (`case<TAB>lane<TAB>status`).

      **What it buys.** A new divergence becomes a REGRESSION and a closed one an IMPROVEMENT, in the
      nightly, automatically. The class stops depending on somebody thinking to write the right probe.
      Expectations are pinned against SCALA rather than against the neighbouring lane, which is the
      only way a shared gap can be seen at all.
