# JS backend — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `v1/runtime/backend/js/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [~] **js-no-tail-call-elimination-overflows-scljet-large-page** — self-TCO exists in JsGen and is
  applied ONLY on the top-level `function` path. A `def` inside an `object`/package module takes the
  arrow-function path (`const f = (a, b) => …`, JsGen.scala:3276), which has no such branch, so a
  self tail call stays a real JS call. Same function, same shape: fine at top level, blows the stack
  once it moves into a module. MEASURED on `scljet-large-page` — INT passes, JS dies with
  `RangeError: Maximum call stack size exceeded` in `writeZerosLoop` (`scljet/write.ssc:74`), a
  function whose own comment says it is tail-recursive "so the interpreter can TCO" it. Emitted
  today as `writeZerosLoop = (count, acc) => { … _call(writeZerosLoop, …) }` with no `while(true)`.
  Fix: the same transform on the member path, guards mirrored. Gate must FAIL before it.

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
