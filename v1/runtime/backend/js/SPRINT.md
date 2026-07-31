# JS backend — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `v1/runtime/backend/js/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] js-long-arith-wrap — `_arith`/`_idiv`/`_imod` mask BigInt results with `asIntN(64)` so ssc
  `Long` wraps like INT/JVM instead of growing unbounded. Gate: new lane-independent conformance
  case `long-overflow-wrap` (golden), which FAILS on 7 of 11 lines against the pre-fix toolchain.
  Found from the bench corpus: `tuple-monoid`'s js cell ran 43.5 s per pass instead of 5 ms.
