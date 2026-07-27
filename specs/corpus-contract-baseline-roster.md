# Corpus Contract baseline roster

## Overview

The Corpus Contract freezes only non-PASS `(case, lane, status)` rows today.
Absence therefore has two incompatible meanings: an existing case was PASS at
the freeze, or the case did not exist at the freeze. This feature adds a frozen
case roster so the differential gate reports `NEW` and `REGRESSED` from
evidence instead of asking a triager to reconstruct git history.

This is test-apparatus behavior only. It does not change ScalaScript language
semantics or the normative `SPEC.md`.

## Interface

- `tests/conformance/corpus-baseline.tsv` remains the sorted set of non-PASS
  `case<TAB>lane<TAB>status` rows.
- `tests/conformance/contract-roster.tsv` starts with the exact versioned
  header `# corpus-contract-roster-v1<TAB>baseline-sha256=<64hex>`, followed by
  the sorted, unique set of case names at the same freeze.
- `scala-cli tests/conformance/contract.sc --update-baseline` is the only
  writer for both files. It is valid only for an unsharded, unfiltered run over
  the canonical default lanes `int,js,v2`.
- `scala-cli tests/conformance/contract.sc --self-test` exercises the pure
  baseline classifier without requiring a built toolchain or running a corpus
  lane.

## Behavior

- [ ] A current non-PASS row for a case present in the frozen roster, but not
      in the frozen non-PASS rows, is reported as `REGRESSION`.
- [ ] A current case absent from the frozen roster is reported as `NEW`,
      whether its current rows are PASS or non-PASS.
- [ ] A full unfiltered run reports roster entries no longer present in the
      current selected corpus as stale/removed; a shard or `--only` run never
      infers removal outside the cases it observed.
- [ ] Existing improvement detection remains intact: a frozen non-PASS row that
      now passes is red until the baseline is deliberately refreshed.
- [ ] Missing, duplicate, unsorted, digest-mismatched, or
      baseline-inconsistent roster metadata fails closed with a diagnostic; it
      is never treated as an empty roster.
- [ ] `--update-baseline` combined with `--shard`, `--only`, `--list`, or a
      non-canonical lane set exits 2 before any file is written.
- [ ] A full baseline update writes the current non-PASS rows and the complete
      selected-case roster, with a roster header digesting the exact baseline
      bytes written by that update.

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

Let `R` be the frozen case roster, `B` the frozen non-PASS rows, `C` the
current non-PASS rows, and `N` the case names actually observed by this run.

- `newCases = N - R`
- `regressions = { row in C - B | case(row) in R }`
- `improvements = B_scoped - C`
- on a full unfiltered run only, `removedCases = R - selectedCurrentCases`

`B_scoped` keeps the existing subset behavior: only rows for observed cases and
requested lanes participate. New-case detection is safe in every subset
because presence in `N` is positive evidence; removal detection is full-run
only because absence from a subset proves nothing.

The roster parser validates canonical sorted uniqueness, verifies the SHA-256
of the exact `corpus-baseline.tsv` bytes, and checks that every case named by a
baseline row exists in the roster. The digest makes a crash, hand edit, or
neighboring baseline update fail closed instead of silently separating the two
halves of one freeze.

## Initial freeze

The initial roster must describe the same observation as the already-committed
baseline. `git log -- tests/conformance/corpus-baseline.tsv` identifies
`3449c588cc362d3ad30555344195b299f09d6150` (2026-07-17) as the current
baseline freeze. Its exact baseline bytes have SHA-256
`d73cc059362c1aea218f39029387867af5cdba0477403003e1d528e1a91a62ec`.
Running the real selection semantics against that commit gives **465 names**.
Current HEAD has **493**: 28 additions and zero removals, including both
`coroutine-demo` and `int-width`. Generate the roster from those freeze-time
465 names and pair it to the current baseline digest.

Generating from current HEAD is forbidden: `coroutine-demo` was added after
the freeze and is the motivating NEW case. Including it in the initial roster
would preserve the original misclassification while making the new gate look
green.

If `corpus-baseline.tsv` moves before E7 lands, discard the reconstructed
roster and regenerate from the exact new baseline update. The digest is the
mechanical guard against landing a stale pair.

## Decisions

- **Use a companion roster file, not pseudo-status rows in
  `corpus-baseline.tsv`.** This preserves the existing baseline row format and
  lets E7 land without hand-editing the neighboring claim's active re-baseline.
  Rejected: encode `ROSTER` as a fake lane/status row, because every baseline
  consumer would need to distinguish metadata from an observed result.
- **Bind the two files by content digest.** A multi-file update is not
  transactionally atomic. A digest turns any partial update into an explicit
  red state. Rejected: trust commit co-location alone, because the exact live
  claim drift that surfaced during E7 proves duplicated metadata can diverge.
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
   `scala-cli tests/conformance/contract.sc --update-baseline --only hello --list`
   currently exits 0 instead of refusing the unsafe scope.
2. Run `scala-cli tests/conformance/contract.sc --self-test`; it must cover NEW
   red, NEW pass, REGRESSION, IMPROVEMENT, scoped-removal suppression, digest
   mismatch, and partial-update refusal.
3. Reconstruct the initial roster from baseline freeze `3449c588c`; prove
   `coroutine-demo` is absent, the names are sorted/unique, and the recorded
   digest matches the current baseline bytes.
4. Run an affected Corpus Contract slice with an existing case and one
   post-freeze case; the latter must be labeled `NEW`, never `REGRESSION`.

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
Implementation and green verification remain pending.
