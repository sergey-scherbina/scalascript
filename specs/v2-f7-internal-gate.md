# v2 internal-gate recovery

## Overview

Restore `v2/conformance/check.sh` as a truthful green gate for the v2 kernel and tower. The self-hosted
`ssc0c` compiler must emit the same canonical Core IR as the Scala bootstrap across real imports, and
ordinary shipped tower programs must run through the stack-aware launcher already required by the
bootstrap/backend-generator contracts. This spec refines the bootstrap gate; it does not change the
Core IR node set, value domain, canonical encoding, or adversarial-input policy.

## Interface

- `bash v2/conformance/check.sh` is the release gate. Its process exit status is authoritative and must
  be captured directly rather than through a trailing pipeline command.
- `ssc compile <entry.ssc0>` and `ssc run bin/ssc0c.ssc0 <entry.ssc0>` are independent compilers whose
  successful outputs are canonical Core IR text and must be byte-identical, including for imported files.
- `sscx` is the gate's existing `java -Xss512m -jar` helper for shipped tower programs documented as
  stack-heavy. Backend-generator checks that compile those programs use `sscx`; ordinary runtime checks
  continue to use `ssc` so the two jobs do not silently share one stack policy.

## Behavior

- [x] The Scala bootstrap and self-hosted compiler emit byte-identical non-empty Core IR for
      `v2/examples/uselib.ssc0` and a minimized two-file regression fixture.
- [x] The self-hosted string scanner decodes the Scala seed's valid escape set (`\"`, `\\`, `\n`, `\r`,
      `\t`, and `\uXXXX`); the two-file differential contains an escaped LF so lexical parity is measured.
- [x] The single-file `ssc0c` fixpoint and the multi-file `bin/ssc0c.ssc0` fixpoint remain byte-identical.
- [x] A mismatch prints the compared artifact paths, byte sizes, and a useful first canonical difference
      before the gate exits non-zero; it is never classified or skipped before the byte comparison runs.
- [x] Ordinary shipped tower programs, including JS/Rust backend generators, compile through `sscx` and
      preserve their exact interpreter/native observable output.
- [x] `v2/conformance/check.sh` reaches natural completion with no `FAIL` rows and exit status zero.

## Out of scope

- Changing the frozen Core IR node/constant inventory or canonical S-expression format.
- Adding, tuning, or testing adversarial compiler-depth/DoS boundaries. The reproduced gap remains in
  `BUGS.md#coreir-compiler-unbounded-depth`, deferred by Sergiy on 2026-07-20, and does not block F7.
- Making arbitrary non-tail-recursive source programs stack-safe at runtime.
- Blessing or normalizing unequal compiler output to make the differential pass.
- Refactoring unrelated v2 frontend breadth, backend feature gaps, or kernel/tower size boundaries.

## Design

### Multi-file differential

The bootstrap invariant from `v2/specs/20-bootstrap.md` is the oracle: compare the two complete canonical
outputs first. Persist both outputs, print a real diff, and only then locate the first divergent loader,
definition-order, name-resolution, or lowering decision. The regression must use at least two files because
a single-file fixture cannot exercise the import boundary where this defect lives. Existing self-fixpoints
are necessary but not sufficient: two copies of the same wrong compiler can reproduce the same wrong bytes.
The minimized fixture also carries an escaped LF. This keeps exact lexical agreement observable after both
compiler CLIs adopt one trailing-LF output contract instead of letting the driver spell around a lexer bug.

### Stack-aware tower launch

The bootstrap and backend specs already identify self-hosted compiler/backend-generator programs whose
non-tail parser/compiler recursion needs a large compiler stack. The gate exposes that policy as `sscx`
(`java -Xss512m -jar`) and must use it consistently for those shipped programs. This is an operational fix
for legitimate toolchain workloads, not a new security boundary and not a claim that arbitrary-depth input
is safe.

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
- **Use the existing stack-aware launcher for documented tower jobs** — chosen because these are legitimate
  shipped programs and the same 512m policy is already normative in the bootstrap/async specs. Rejected:
  leaving two backend checks on the default-stack helper while equivalent tower checks use `sscx`.
- **Defer compiler-depth boundary work** — Sergiy explicitly removed adversarial boundary protection from
  the current loop on 2026-07-20. Rejected for F7: adding depth limits, fail-closed diagnostics, or boundary
  regressions before returning to ordinary bugs, optimization, minimization, and features.

## Verification

1. Run a focused two-file differential and print both canonical artifacts plus their diff on a forced
   mismatch.
2. Run the single-file and multi-file self-hosting fixpoints.
3. Run the legitimate stack-heavy JS/Rust backend-generator checks through `sscx`.
4. Run `bash v2/conformance/check.sh` without a status-masking pipeline and record its exact summary/status.
5. Run the affected shared conformance slice and exact-SHA GitHub CI required by the project workflow.

## Results

Fresh baseline at `5f39336a8`: `v2/conformance/check.sh` reaches natural exit 1 with **637 ok / 5 FAIL**.
The five labels are `ssc0c uselib.ssc0`, JS `quicksort-lib`/`zipwith`, and Rust
`quicksort-lib`/`zipwith`; every initial command and retry fails with the same default-stack compiler
`StackOverflowError`. The old `uselib ir differs` line is not an independent loader/lowering diagnosis:
the self-hosted side failed before comparison. At `-Xss512m` it succeeds and the 2865-byte Core IR payload
agrees, but exact streams still differ by the seed's one trailing LF (2866 vs 2865 bytes). The existing
gate's `$(...)` capture strips that evidence and is therefore not a valid byte comparator. Valid failing
programs are structurally shallow (self compiler max S-expression depth 28; JS/Rust generators 51), so a
reader-depth check alone cannot close compiler recursion. Replacing command substitution with the exact-file
comparator immediately exposed a second frontend discrepancy: `scanStr` preserves `\n` while the seed
decodes it, making the single/multi fixpoints 21050/21051 and 25875/25876 bytes respectively. F7.2 therefore
includes escape parity rather than hiding the discrepancy with a different newline spelling.

F7.2 result (`3056aa3b8`): both compiler CLIs emit one LF and all exact comparisons operate on complete
files. The comparator self-probe distinguishes one versus two bytes and rejects equal empty streams. The
all-escape two-file fixture is 259/259 bytes and runs to 42; `uselib` is 2866/2866; single and multi-file
fixpoints are 22844/22844 and 27669/27669. `CONF_FAST=1 bash v2/conformance/check.sh` reaches natural exit
0 with 408 ok / 0 FAIL; streams/status are `/tmp/v2-f7-f2-fast2.{out,err,status}`.

F7.3 local result (2026-07-20): the two backend-generator invocations now use the gate's existing `sscx`
helper. `bash -n v2/conformance/check.sh` passes, and the complete gate reaches natural exit **0** with
**644 ok / 0 FAIL**; no `StackOverflowError` or `command not found` appears. The run includes JS, Rust,
WASM, the exact compiler differentials/fixpoints, and the 1e6-tail-call check. Complete artifacts are
`/tmp/v2-f7-full-final.{out,err,status}`. The affected shared conformance slice
`tests/conformance/run.sh --only 'v2-*'` passes 11/11 (all memoized), 0 failed. Exact-SHA CI is the only
remaining F7 closure gate.
