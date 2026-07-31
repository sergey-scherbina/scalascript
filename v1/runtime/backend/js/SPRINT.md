# JS backend — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `v1/runtime/backend/js/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] js-long-hoisted-const-mix — JsGen's invariant-fold hoist built `let s = 0` / `acc = acc + _kN`
  as TEXT, so a Long (BigInt) accumulator threw. Seeds `0n` and routes both adds through `_larith`
  when the accumulator or addend is statically Long; the Int path keeps its native `+`. The 64-bit
  mask is what keeps the REORDERING the hoist performs sound across an overflow. Gate:
  `long-accum-invariant-fold` (Int / Long / Long-overflowing). bench.sh list-fold js: n/a → 0.1007.

- [x] js-long-arith-wrap — a new `_larith` (and `_idiv`/`_imod`) masks BigInt results with
  `asIntN(64)` so ssc `Long` wraps like INT/JVM instead of growing unbounded. NOT `_arith` itself:
  ssc `BigInt` shares that representation and must stay unbounded. Gate: new lane-independent conformance
  case `long-overflow-wrap` (golden), which FAILS on 7 of 11 lines against the pre-fix toolchain.
  Found from the bench corpus: `tuple-monoid`'s js cell ran 43.5 s per pass instead of 5 ms.
