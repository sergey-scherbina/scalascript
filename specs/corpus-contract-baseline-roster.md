# Corpus Contract baseline roster

## Overview

Before this feature, the Corpus Contract froze only non-PASS
`(case, lane, status)` rows. Absence therefore had two incompatible meanings:
an existing case was PASS at the freeze, or the case did not exist at the
freeze. The companion frozen roster lets the differential gate report `NEW`
and `REGRESSION` from evidence instead of asking a triager to reconstruct git
history.

This is test-apparatus behavior only. It does not change ScalaScript language
semantics or the normative `SPEC.md`.

## Interface

- `tests/conformance/corpus-baseline.tsv` remains the sorted set of non-PASS
  `case<TAB>lane<TAB>status` rows.
- `tests/conformance/contract-roster.tsv` starts with the exact versioned
  header
  `# corpus-contract-roster-v1<TAB>baseline-sha256=<64hex><TAB>roster-sha256=<64hex>`,
  followed by the sorted, unique set of case names at the same freeze. Both
  digests cover canonical UTF-8/LF serializations, independent of checkout EOL.
- `scala-cli tests/conformance/contract.sc -- --update-baseline` is the only
  writer for both files. It is valid only for an unsharded, unfiltered run over
  the canonical default lanes `int,js,v2`.
- `scala-cli tests/conformance/contract.sc -- --self-test` exercises the pure
  baseline classifier without requiring a built toolchain or running a corpus
  lane.

## Behavior

- [x] A current non-PASS row for a case present in the frozen roster, but not
      in the frozen non-PASS rows, is reported as `REGRESSION`.
- [x] A current case absent from the frozen roster is reported as `NEW`,
      whether its current rows are PASS or non-PASS.
- [x] Any unfiltered selection, including a shard, reports roster entries no
      longer present in the complete pre-shard selected corpus as
      stale/removed. An `--only` run suppresses global removal inference.
- [x] Existing improvement detection remains intact: a frozen non-PASS row that
      was actually observed and now passes is red until the baseline is
      deliberately refreshed. A status transition or backend-excluded cell is
      not an improvement. A frozen case-level SKIP improves only when at least
      one eligible lane ran and all observed lane cells passed.
- [x] Missing, duplicate, unsorted, digest-mismatched, or
      baseline-inconsistent roster metadata fails closed with a diagnostic; it
      is never treated as an empty roster. Baseline lanes must be canonical,
      known lane names (or `*` paired with `SKIP`).
- [x] `--update-baseline` combined with `--shard`, `--only`, `--list`, or a
      malformed/non-canonical lane list exits 2 before any file is written.
- [x] A full baseline update writes the current non-PASS rows and the complete
      selected-case roster, with a roster header digesting both canonical
      serializations written by that update.
- [x] CLI options fail closed on missing values, duplicates, unknown lanes,
      empty filters/lane lists, and non-positive numeric budgets. A normal gate
      exits 2 instead of GREEN when it selects zero cases or observes zero
      lane/skip cells.

## Out of scope

- Changing the current corpus non-PASS rows while
  `corpus-gate-remaining-reds` owns that re-baseline.
- Deciding whether a newly added failing case is acceptable. The gate labels it
  `NEW`; a human still classifies and either fixes, skips on documented
  non-hermetic grounds, or deliberately freezes it.
- Freezing a complete `(case, lane)` presence matrix. The requested evidence is
  a case roster; newly enabled lanes on an existing case remain ordinary matrix
  changes.
- Changing timeout, sharding, known-red, skip, or golden semantics.

## Design

Let `R` be the frozen case roster, `B` the frozen non-PASS cell map, `C` the
current non-PASS cell map, `N` the case names actually observed by this run,
and `O` the `(case,lane)` keys actually observed after `backends:` filtering.
The wildcard `(case,*)` key is considered in scope whenever the case itself was
observed. It becomes an improvement only when the case has at least one
observed lane key and `C` has no non-PASS row for that case.

- `newCases = N - R`
- `B_scoped = { (key,status) in B | key in O }`
- `regressions = { (key,status) in C | case(key) in R and key not in B }`
- `changes = { key in C ∩ B_scoped | C(key) != B(key) }`
- `improvements = { non-wildcard row in B_scoped | key not in C } ∪
  { (case,*,SKIP) | observedLanes(case) is non-empty and no current non-PASS
  row belongs to case }`
- without `--only`, `removedCases = R - selectedCurrentCases`

`B_scoped` keeps the existing subset behavior: only rows for observed cases and
actually executed lanes participate. Since `C` stores only non-PASS rows, the
absence used by `improvements` means PASS only after membership in `O` proves
that the cell ran. New-case detection is safe in every subset because presence
in `N` is positive evidence. Removal detection uses the complete `selected`
list computed before shard slicing, so each production shard can detect global
coverage loss; `--only` changes that list and therefore suppresses removals.

The parser validates canonical sorted uniqueness and one status per
`(case,lane)` key, verifies SHA-256 for both the canonical baseline and
canonical roster body, and checks that every case named by a baseline row
exists in the roster. Canonical serialization is UTF-8, one LF-terminated row
per value (or empty bytes for an empty baseline); it avoids `core.autocrlf`
false failures while still binding semantic content. The two digests make a
crash, content edit, or neighboring baseline update fail closed instead of
silently separating either half of one freeze.

## Initial freeze

The initial roster must describe the same observation as the already-committed
baseline. `git log -- tests/conformance/corpus-baseline.tsv` identifies
`3449c588cc362d3ad30555344195b299f09d6150` (2026-07-17) as the current
baseline freeze. Its exact baseline bytes have SHA-256
`d73cc059362c1aea218f39029387867af5cdba0477403003e1d528e1a91a62ec`.
Running the real selection semantics against that commit gives **465 names**.
At audited snapshot `e124cc20f`, the selection has **494**: 29 additions and
zero removals, including `coroutine-demo`, `coroutine-native-lifecycle`, and
`int-width`. Generate the roster from the freeze-time 465 names and pair it to
the current baseline digest.

Generating from current HEAD is forbidden: `coroutine-demo` was added after
the freeze and is the motivating NEW case. Including it in the initial roster
would preserve the original misclassification while making the new gate look
green.

`corpus-baseline.tsv` did not move before E7 landed. Future changes must use
the paired writer so the failure rows, roster names, and both digests advance
as one observation.

## Decisions

- **Use a companion roster file, not pseudo-status rows in
  `corpus-baseline.tsv`.** This preserves the existing baseline row format and
  lets E7 land without hand-editing the neighboring claim's active re-baseline.
  Rejected: encode `ROSTER` as a fake lane/status row, because every baseline
  consumer would need to distinguish metadata from an observed result.
- **Bind both halves by canonical content digests.** A multi-file update is not
  transactionally atomic, and a baseline-only hash leaves PASS-only roster
  names unauthenticated. Dual digests turn a partial write or body edit into an
  explicit red state. Canonical LF serialization is chosen over exact checkout
  bytes because `core.autocrlf` must not invalidate a valid freeze. Rejected:
  trust commit co-location or hash only the baseline.
- **Make new PASS cases red until frozen.** Otherwise a passing case could be
  added without entering `R`, and its first later failure would again be
  mislabeled `NEW` instead of `REGRESSION`.
- **Reject every partial update shape.** The writer replaces the complete
  frozen observation, so accepting `--only`, a shard, or fewer lanes is
  destructive by construction. Rejected: merge partial observations into the
  baseline, because stale rows could then survive indefinitely and no one run
  would prove the freeze coherent.
- **Test the pure classifier with synthetic sets.** This proves the semantic
  distinction directly and cheaply. The real corpus run remains the integration
  gate, not the only place classifier logic can fail.

## Verification plan

1. Before implementation, record the red control:
   `scala-cli tests/conformance/contract.sc -- --update-baseline --only hello --list`
   currently exits 0 instead of refusing the unsafe scope.
2. Run `scala-cli tests/conformance/contract.sc -- --self-test`; it must cover NEW
   red, NEW pass, REGRESSION, true IMPROVEMENT, status CHANGE without a false
   improvement, wildcard-SKIP transitions, unobserved-lane suppression,
   sharded positive/`--only`-suppressed removal,
   baseline/roster digest mismatch, malformed metadata, and partial-update
   refusal.
3. Reconstruct the initial roster from baseline freeze `3449c588c`; prove
   `coroutine-demo` is absent, the names are sorted/unique, and the recorded
   digest matches the current baseline bytes.
4. Run an affected Corpus Contract slice with an existing case and one
   post-freeze case; the latter must be labeled `NEW`, never `REGRESSION`.
5. Run CLI negative controls for missing/empty values, an unknown/duplicate
   lane, a zero-match `--only`, and zero observed cells; assert exit 2 and the
   intended diagnostic.

## Results

Red control captured before implementation (2026-07-27):

```text
$ scala-cli --server=false tests/conformance/contract.sc -- \
    --update-baseline --only hello --list
hello
exit 0
```

The unsafe partial-update shape was accepted instead of exiting 2. `--list`
made the reproduction non-destructive; the baseline remained SHA-256
`d73cc059362c1aea218f39029387867af5cdba0477403003e1d528e1a91a62ec`.

Implementation landed on `origin/main` as `fc5f07f28`; the corrected operator
contract and v2 lane identity landed as `2a796b258`.

- The initial `contract-roster.tsv` has **465 sorted, unique names** reconstructed
  from baseline-producing commit
  `3449c588cc362d3ad30555344195b299f09d6150`. It excludes the post-freeze
  `coroutine-demo`, `coroutine-native-lifecycle`, and `int-width` cases.
- Canonical baseline SHA-256 is
  `d73cc059362c1aea218f39029387867af5cdba0477403003e1d528e1a91a62ec`;
  canonical roster-body SHA-256 is
  `5644dba6de418a725e61a220769b03a33bf9698807ffa3ea3b545e1ceb887fea`.
  The whole roster file intentionally has the different SHA-256
  `1eea8e49c8df34afbba40024417e30133408fc58791addefb760f247fae98f28`.
- `scala-cli tests/conformance/contract.sc -- --self-test` passes **29**
  parser/classifier checks. Fourteen CLI negative controls (unsafe update
  shapes, missing/empty/duplicate/unknown values, zero selection/cells, and
  non-positive budgets) each exit 2 with the intended diagnostic; a refused
  update leaves both freeze files byte-identical.
- The real mixed slice
  `--only arithmetic,int-width --workers 1` reports only the post-freeze
  `int-width js KNOWN-RED` row as `NEW`, never `REGRESSION`. The production-form
  `--shard 27/494 --lanes int --workers 1` is GREEN while retaining global
  removal detection.
- `tests/conformance/run.sh --only 'arithmetic,int-width' --no-memo` passes
  **2/2** cases with the two declared v1 JS/JVM known-red cells and both v2
  cells passing.
- `bin/ssc info --execution-plan --v2` confirms the documented standard route:
  native frontend/checker and default `asm` backend. Markdownlint passes for
  the E7 operator/spec documents and `git diff --check` is clean; the exact
  repository-wide lint exposed the separate pre-existing SPRINT E8 failures.
  An independent read-only review found no remaining E7 contract/doc
  correctness issue.

A full `--update-baseline` was intentionally not run: the neighboring
`corpus-gate-remaining-reds` claim owns `corpus-baseline.tsv`, and E7's initial
roster is paired to that exact existing freeze.

CI evidence for release is **level 3**. `scripts/ci-status --sha 9975a0c0c`
initially found no exact run; GitHub run `30307158170` then completed
`cancelled` with zero jobs after being superseded. That outcome is recorded as
RED/no verdict, not green, so the release relies on the named local gates above.
