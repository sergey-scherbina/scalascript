# v2 Pattern Match Heavy Production Profile

## Overview

`pattern-match-heavy` is now the remaining representative v2 production
performance blocker. The previous bytecode sweep showed that v2 bytecode closes
the recursion family but is slower than the VM on this row, while the Rust
source lane is much faster. This slice recaptures the row, inspects the bridge
CoreIR and generated execution lanes, then lands at most one conservative
implementation only if the measured shape is explicit and has a clean fallback.

## Interface

No user-facing language or CLI contract changes are intended unless the
measurement proves a missing production-route switch is the blocker. Verification
uses the existing public commands:

```bash
scripts/sbtc "installBin"
scripts/bench v2-bytecode pattern-match-heavy
scripts/bench v2-backends pattern-match-heavy
bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3 bench/corpus/pattern-match-heavy.ssc
tests/conformance/run.sh --only '<affected-globs>' --no-memo
```

If runtime `FastCode`, VM/bytecode matching, or generic foreach/cell behavior
changes, also run the focused frontend bridge tests and `./v2/conformance/check.sh`.

## Behavior

- [x] A fresh worktree baseline is captured after `scripts/sbtc "installBin"`
      for `pattern-match-heavy` on `v2`, `v2-bytecode`, `v2-jvm`, and `v2-rust`.
- [x] The bridge-generated CoreIR for `bench/corpus/pattern-match-heavy.ssc`
      is recorded or summarized before implementation.
- [x] At least one concrete execution-lane blocker is identified from CoreIR,
      generated source/bytecode inspection, or a focused profile; speculative
      hand paths without measured evidence are rejected.
- [x] If a narrow fix lands, it preserves generic fallback semantics and adds
      focused regression coverage for the recognized shape.
- [x] The production gate is only marked improved/closed when final public bench
      rows justify it; otherwise the next blocker is recorded.
- [x] Affected tests/conformance, final bench rows, and `git diff --check`
      pass before push.

## Out of Scope

- Reopening recursion rows already closed by bytecode/JVM source routes.
- Reopening the source JVM/Rust production gate except for comparing current
  route numbers.
- Changing `bench/corpus/pattern-match-heavy.ssc` semantics.
- Broad VM/JIT rewrites or generic `FastCode` recognizers without a measured
  row and a strict safety predicate.

## Design

The known workload shape is:

```text
def workload(): Double =
  var total = 0.0
  var i = 0
  while i < 100000 do
    shapes.foreach(s => total = total + area(s))
    i = i + 1
  total
```

Previous VM slices removed compact match-arm env allocation and per-element
`Runtime.appendOne` allocation, reducing the VM row to roughly 14-15 ms. The
bytecode sweep measured `v2-bytecode=19.3 ms`, so simply promoting bytecode is
not the production answer.

Investigation order:

1. Stage the current CLI with `scripts/sbtc "installBin"`.
2. Re-run `pattern-match-heavy` through `v2-bytecode` and `v2-backends`.
3. Emit bridge CoreIR and inspect the hot `while` / `foreach` / `cell.set` /
   `area` match shape.
4. Inspect generated v2 JVM/Rust source or bytecode-lane behavior only where it
   explains a measured row.
5. Implement only if the blocker is a narrow, structural pattern with a natural
   fallback. A corpus-name shortcut is explicitly forbidden.

## Decisions

- **Target `pattern-match-heavy` now** - chosen because route sweep left it as
  the only representative row still far from production in VM/bytecode lanes.
  Rejected: more recursion work, which is already covered by bytecode/JVM source.
- **Profile/inspect before code** - chosen because the backlog warns against
  speculative `FastCode` recognizers after several local VM hand paths. Rejected:
  starting from a closed-form rewrite without confirming the emitted CoreIR
  shape and fallback surface.

## Results

Implementation commit: `eead9c4b8 fix(v2-vm): specialize static float foreach loops`.

Fresh baseline after `scripts/sbtc "installBin"`:

- `scripts/bench v2-bytecode pattern-match-heavy`: `v2=14.6 ms`,
  `v2-bytecode=19.4 ms`.
- `scripts/bench v2-backends pattern-match-heavy`: `v2=15.8 ms`,
  `v2-jvm=10.8 ms`, `v2-rust=0.296 ms`.
- `bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3
  bench/corpus/pattern-match-heavy.ssc`: `BENCH v2 14.4`.

The emitted CoreIR is the expected structural shape: `area` is a pure one-arg
global `match` over `Shape`, `shapes` is a top-level static `Cons` list, and
`workload` is a `while i < 100000` loop whose body is
`shapes.foreach(s => cell.set(total, cell.get(total) + area(s)))` followed by
`i = i + 1`.

Blocker identified: the VM fast foreach path had already removed per-element
`Runtime.appendOne`, but it still evaluated the pure `area(s)` match and float
arithmetic for every list element on every loop iteration. The Rust source
route's `staticFloatForeach` instead precomputes the static list's pure float
function results once, then runs the loop as unboxed f64 additions. The VM now
does the analogous narrow optimization for the same structural shape, while a
pure-evaluator safety predicate rejects cells, methods, effects, non-static
lists, and other impure terms.

Focused regression coverage:

- `FrontendBridgeTest` `bridge pattern-match-heavy workload exposes VM fast entries`
  covers the recognized workload result.
- `FrontendBridgeTest` `v2 VM static float foreach loop keeps impure global function
  on fallback` covers the safety fallback: an impure global function still runs
  once per foreach element instead of being precomputed.

Final rows after `scripts/sbtc "installBin"`:

- `bin/ssc --backend v2 bench --machine --warmup-time 100 --reps 3
  bench/corpus/pattern-match-heavy.ssc`: `BENCH v2 0.2653`.
- `scripts/bench v2-bytecode pattern-match-heavy`: `v2=0.266 ms`,
  `v2-bytecode=19.3 ms`.
- `scripts/bench v2-backends pattern-match-heavy`: `v2=0.266 ms`,
  `v2-jvm=10.9 ms`, `v2-rust=0.265 ms`.

Gates:

- `scripts/sbtc "v2FrontendBridge/compile"` passed.
- Focused bridge tests for `pattern-match-heavy` and `static` passed.
- Full `v2FrontendBridgeTest` was probed and had one unrelated
  `Currency.scale` failure, matching the active sibling
  `v2-money-decimal-regression` claim; the pattern/static tests passed.
- `scripts/sbtc "installBin"` passed after the implementation.
- `./v2/conformance/check.sh` passed.
- `tests/conformance/run.sh --only
  'pattern-matching,sealed-traits,list-companion,tagless-sealed-dispatch,v2-multiline-list-literal'
  --no-memo` passed: 5/5.
- `git diff --check` passed before the implementation commit and after this
  documentation update.
