# What the 97 gate-less open entries actually are

**Measured 2026-08-06** with `scripts/bugs-report`'s own parser, imported rather than reimplemented,
so this census and the tool can never be reading different sets.

**The counts are a SNAPSHOT and go stale by the hour** — this repo lands entries continuously, and
the total moved 97 → 100 while the census was being written. Do not repair the numbers below; re-run
`scripts/bugs-report --status open --no-gate` for the current set. What does not go stale is the
SHAPE: almost nothing says why it has no gate, and most of what has no gate has no repro either.

## The number was wrong, and the query was why

`scripts/bugs-report --no-gate` reported **92**. It selected `gate in (None, "none")`, and five open
entries carry `gate: -`, which names nothing but is neither. **The real count is 97**, and the
predicate now treats `""`, `none` and `-` alike.

**It was written TWICE** — once for `--no-gate`, once for the summary line — and both copies read
`gate in (None, "none")`. Fixing one left the summary printing 92 while the listing returned 97, in
the same run, which is how the duplication announced itself. There is now one `names_no_gate(row)`
and two call sites.

Worth stating plainly: the query that answers *"what is unprotected"* was itself under-reporting by
five. Same shape as `fixed-in: -` — a dash is how this repo writes "nothing here", and a predicate
that does not know that is blind in exactly the direction that flatters it.

## The census

| | |
|---|---|
| open entries naming no gate | **97** |
| …that EXPLAIN the absence in their prose | **2** |
| …silent about it | **95** |
| …silent AND carrying a fenced repro | **20** |

The two that explain are `package-root-import-needs-an-exports-entry-on-int` ("writing one before
the decision would freeze whichever answer runs today") and
`js-lane-missing-derives-and-coroutinecancel`.

## What this changes about the obvious next move

The obvious move — "sweep the gate-less entries and add gates" — is not supported by the numbers.
**75 of the 95 silent entries carry no reproduction either**, so writing a gate for them starts with
reproducing the defect, which is the expensive half. The cheap subset is the 20 that already carry a
repro:

```
native     f-unbound-loop-is-the-new-top-gap
native     corpus-contract-scljet-jdbc-v2-timeout
native     backend-check-mutual-recursion-drops-output
native     native-release-unqualified-and-unrelocatable
native     scljet-jdbc-facade-bytecode-class-too-large
native     coreir-canonical-codec-contract
native     custom-jsemitter-signal-list-literal
apparatus  uniml-yaml-official-conformance-gap
int        int-imported-module-mutable-registry-not-shared
js         v2-mirror-fromproduct-stub
js         uniml-yaml-alias-resolution-last-wins
js         coreir-spec-node-inventory-drift
…and 8 more — `scripts/bugs-report --status open --no-gate` lists all 97
```

**A missing gate is not automatically a defect.** Two entries here have none on purpose and say so.
The problem is the 95 that are silent: nothing distinguishes "no gate because the decision is open"
from "no gate because nobody wrote one", so a reader cannot tell a considered absence from a hole.
That is a cheaper thing to fix than 95 gates — it is one sentence per entry, written by whoever
knows the answer, and it makes the next census answerable.

## Two heuristics I had to throw away, because they measured themselves

Both were caught by spot-checking the classifier's own output, and both would have shipped a
confident wrong number:

1. **"Does the prose explain the absence?" matched `gate: none` in the HEADER** — which every
   gate-less entry has, by definition. First answer: 25 explained / 72 silent. After excluding the
   `<!-- … -->` block: 3 / 94.
2. **The remaining 3 included a slug self-match** — `js-control-direct-typescript-version-ungated`
   matched on the word in its own heading; its body says nothing about a gate. Final: 2 / 95.

A classifier whose pattern can match the metadata it is classifying will always find what it is
looking for. Spot-check the hits, not just the count — the count is what looks reasonable either way.
