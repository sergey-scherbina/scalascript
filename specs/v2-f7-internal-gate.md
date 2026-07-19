# v2 internal-gate recovery

## Overview

Restore `v2/conformance/check.sh` as a truthful green gate for the v2 kernel and tower. The work has
two independent correctness obligations: the self-hosted `ssc0c` compiler must emit the same canonical
Core IR as the Scala bootstrap across real imports, and compilation of deep but well-formed Core IR must
never escape as a host `StackOverflowError`. This spec refines the frozen Core IR and bootstrap contracts;
it does not change the Core IR node set, value domain, or canonical encoding.

## Interface

- `bash v2/conformance/check.sh` is the release gate. Its process exit status is authoritative and must
  be captured directly rather than through a trailing pipeline command.
- `ssc compile <entry.ssc0>` and `ssc run bin/ssc0c.ssc0 <entry.ssc0>` are independent compilers whose
  successful outputs are canonical Core IR text and must be byte-identical, including for imported files.
- `ssc run-ir <program.coreir>` accepts untrusted canonical capsules through the bounded reader. A capsule
  rejected by the compiler must produce a stable ScalaScript diagnostic and non-zero exit; JVM errors or
  process crashes are not diagnostics.

## Behavior

- [ ] The Scala bootstrap and self-hosted compiler emit byte-identical non-empty Core IR for
      `v2/examples/uselib.ssc0` and a minimized two-file regression fixture.
- [ ] The single-file `ssc0c` fixpoint and the multi-file `bin/ssc0c.ssc0` fixpoint remain byte-identical.
- [ ] A mismatch prints the compared artifact paths, byte sizes, and a useful first canonical difference
      before the gate exits non-zero; it is never classified or skipped before the byte comparison runs.
- [ ] On `-Xss1m`, the smallest adversarial well-formed capsule from the bug ledger either compiles and
      runs or fails with a stable compiler-depth diagnostic; it never throws `StackOverflowError`.
- [ ] Ordinary shipped tower programs, including the program that originally exercised the deep compiler
      path, continue to compile and preserve their exact observable output.
- [ ] One-million-step tail-recursive runtime conformance remains stack-safe; compiler-depth protection
      must not weaken the Core IR proper-tail-call guarantee.
- [ ] `v2/conformance/check.sh` reaches natural completion with no `FAIL` rows and exit status zero.

## Out of scope

- Changing the frozen Core IR node/constant inventory or canonical S-expression format.
- Making arbitrary non-tail-recursive source programs stack-safe at runtime.
- Treating a larger JVM stack as the sole protection for untrusted capsules.
- Blessing or normalizing unequal compiler output to make the differential pass.
- Refactoring unrelated v2 frontend breadth, backend feature gaps, or kernel/tower size boundaries.

## Design

### Multi-file differential

The bootstrap invariant from `v2/specs/20-bootstrap.md` is the oracle: compare the two complete canonical
outputs first. Persist both outputs, print a real diff, and only then locate the first divergent loader,
definition-order, name-resolution, or lowering decision. The regression must use at least two files because
a single-file fixture cannot exercise the import boundary where this defect lives. Existing self-fixpoints
are necessary but not sufficient: two copies of the same wrong compiler can reproduce the same wrong bytes.

### Compiler-depth safety

Measure the current failure under a deliberately small, CI-shaped `-Xss1m` stack. The accepted fix may use
an iterative traversal, an explicit compiler-depth budget, or a composition of both, but it must turn host
stack exhaustion into a deterministic language-level result while retaining all ordinary corpus/fixpoint
programs. Any configurable limit must be validated before recursive compilation begins and documented with
the measured maximum depth of shipped artifacts.

### Gate truthfulness

Every gate comparison computes both sides before classifying the result and prints expected/actual evidence
on failure. Empty output is a distinct failure, never equality. The final verifier records stdout, stderr,
and the direct process status separately so a successful `tail`, `grep`, or retry cannot mask a red gate.

## Decisions

- **Canonical byte equality remains the oracle** — chosen because canonical Core IR exists specifically to
  make independent compiler agreement decidable. Rejected: semantic spot checks or expected-blob refreshes,
  which can hide a lowering or import-order regression.
- **Use a multi-file regression** — chosen because the reported divergence occurs only across an import.
  Rejected: a single-file approximation, which already passes and cannot cover the failing boundary.
- **Fail closed before host stack exhaustion** — chosen because persisted Core IR is untrusted input and a
  JVM `StackOverflowError` is a denial of service. Rejected: increasing launcher `-Xss` alone, which merely
  moves the failure and keeps behavior host-dependent.
- **Keep runtime TCO and compiler traversal separate** — chosen because Core IR tail-call semantics concern
  evaluation, while the defect is recursive compilation before evaluation. Rejected: weakening the TCO gate
  or reclassifying compiler overflow as a runtime limitation.

## Verification

1. Run a focused two-file differential and print both canonical artifacts plus their diff on a forced
   mismatch.
2. Run the single-file and multi-file self-hosting fixpoints.
3. Run the adversarial compiler-depth regression at `-Xss1m` and the legitimate tower repro.
4. Run `bash v2/conformance/check.sh` without a status-masking pipeline and record its exact summary/status.
5. Run the affected shared conformance slice and exact-SHA GitHub CI required by the project workflow.

## Results

Fresh `origin/main` baseline and implementation results are intentionally pending F7.1. The prior audit at
`358facd8e` reported 639 successful checks and three failures: one `ssc0c uselib.ssc0` byte mismatch and two
compiler `StackOverflowError`s. Those are hypotheses to re-measure, not expectations to preserve.
