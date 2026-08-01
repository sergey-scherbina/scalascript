# JS backend — sprint

This module's queue. **Two states, and there is no third:**

    - [~] SLUG — one line      in progress
    - [x] SLUG — one line      done

Anything not being worked on belongs in `v1/runtime/backend/js/BACKLOG.md`, not here — a queue with a
"planned" state just becomes a second backlog. A task being worked on also has a row on
the root `SPRINT.md` board and a live `.work/active/<slug>.claim`; all three are written
in one commit. Layout: `specs/work-tracking-layout.md`.

- [x] **js-no-tail-call-elimination-overflows-scljet-large-page** — self-TCO exists in JsGen and is
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

## js codegen pair (claim `js-codegen-pair`)

- [x] **J-1 — `js-unused-val-drops-side-effecting-call`.** Fixed in the **TreeShaker**, not the
      emitter: `isReachableStat` filters on the NAME, so keeping the statement without rooting the
      name leaves the initialiser calling an already-pruned `eff` — a ReferenceError traded for a
      silent no-op. A top-level binding whose initialiser is not trivially pure is now a root.
      **The gate is an e2e script, not a conformance case:** shaking runs on `emit-js`, not the
      `run-js` the corpus uses, so a case would have been green both ways.
- [x] **J-2 — `js-class-method-named-arg-nan`.** TWO causes behind one symptom, and the first fix
      alone moved `6/NaN` to `NaN/3` — which is how the second showed itself. (a) class BODIES were
      never walked for param order, so named args were not reordered; (b) class-method defaults were
      dropped, because the signature was built from bare names. Defaults are applied in the body,
      not as JS defaults, because a default may read a field that only exists after the `_self`
      destructuring.
- [x] **J-3 — evidence.** `named-arg-defaults` extended to class methods and its frozen expected
      file regenerated **from the jvm oracle**, not from int — which is what caught J-4.
- [x] **J-5 — DONE 2026-08-01.** Both gates are registered in `scripts/smoke-ci.ssc` and now RUN:
      `ok js-shaker-effectful-binding 0.7s`, `ok v2-char-numeric-position 4.6s`. Original note: CI and
      `scripts/smoke-ci.ssc` list e2e scripts BY NAME — there is no generic `tests/e2e/*.sh` runner
      — so an unregistered gate is never executed by anything but a human. `smoke-ci.ssc` is held by
      the `bench-seed-type` claim right now, so this could not be done here. Exact lines owed:

          Check("v1/runtime/backend/js", "js-shaker-effectful-binding",
                "tests/e2e/js-shaker-keeps-effectful-binding.sh", List(), 180000),
          Check("v2", "v2-char-numeric-position",
                "tests/e2e/v2-char-numeric-position.sh", List(), 240000),

      The second is from the `v2-char-value` claim earlier today and has the same problem — I landed
      it without checking that the registry is explicit. Both gates PASS when run by hand.
- [ ] **J-4 — NOT mine, filed: `int-field-valued-default-undefined-on-empty-call`.** `P2(5).shift()`
      with no arguments dies on INT with `Undefined: x` while jvm and js both answer `10/2`. The
      sharp part: `shift(dy = 1)` takes the SAME default and succeeds, so it is the empty argument
      list that loses the receiver. Invisible from a lane comparison until js started agreeing with
      the oracle in this commit.
