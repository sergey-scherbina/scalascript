# v2 Backend Check ssc1c Wrapper App-Lit

## Overview

This slice restores two generated ssc1c regression rows in
`v2/backend/check.sh`: `bool-predicate` and `mutual-recursion`. They currently
fail before any source backend runs because the VM oracle cannot execute the
CoreIR generated from the temporary `.ssc1` wrapper. Production source-backend
work needs these rows to be trustworthy parity gates again.

## Interface

No user-facing language, CLI, or corpus-workload interface changes are planned.
The public verification commands for this slice are:

```bash
v2/backend/check.sh bool
v2/backend/check.sh mutual-recursion
```

The harness keeps compiling the existing corpus files through `v2/bin/ssc1c.ssc0`
with synthetic `main` wrappers:

```scala
def main(): Unit = { println(workload(42L)); () }
def main(): Unit = { println(workload()); () }
```

## Behavior

- [ ] The failing `bool-predicate` and `mutual-recursion` backend-check rows are
      reproduced from the real `v2/backend/check.sh` path.
- [ ] The invalid `(app (lit (int 1000)) (lam 0 ...))` CoreIR shape is traced to
      a concrete ssc1c or harness lowering cause.
- [ ] The fix preserves the existing corpus sources and does not special-case
      JVM/JS/Rust source generators.
- [ ] `v2/backend/check.sh bool` and `v2/backend/check.sh mutual-recursion`
      pass end-to-end through the VM oracle and all source backends.
- [ ] Existing backend fixtures (`tco`, `letrec`) and affected conformance still
      pass, along with `git diff --check`.

## Out of Scope

- Source-generator performance work.
- Rust/JVM/JS generator semantic changes.
- Rewriting `bench/corpus/bool-predicate.ssc` or
  `bench/corpus/mutual-recursion.ssc`.
- Broad ssc1 language/compiler refactors beyond the invalid wrapper CoreIR
  shape.

## Design

Start from the exact harness path rather than hand-authored CoreIR. Capture the
temporary `.ssc1` program produced by `v2/backend/check.sh`, compile it through
`v2/bin/ssc1c.ssc0`, and inspect the smallest source construct that becomes an
application where the literal `1000` is in function position. The likely area is
ssc1c parsing/lowering around the wrapped workload body, especially
`until`/loop/block syntax or precedence around a parenthesized condition.

The fix should live where the invalid CoreIR is introduced. If the corpus source
is parsed/lowered incorrectly, fix ssc1c. If the harness wrapper is ambiguous
for ssc1c, adjust the wrapper shape in `v2/backend/check.sh` to emit an
unambiguous `.ssc1` program while keeping the workload semantics unchanged.

## Decisions

- **Use the VM oracle as the first gate** - chosen because the current failure
  happens in `run-ir` before source backend code generation. Rejected:
  debugging generated JVM/JS/Rust output first, because those backends never see
  a valid expected output for these rows.
- **Keep corpus workloads unchanged** - chosen so the benchmark/parity corpus
  remains comparable across previous v2 backend work. Rejected: editing
  `bench/corpus/bool-predicate.ssc` only to make the wrapper pass.
- **Treat this as a production gate repair, not performance work** - chosen
  because the value is restoring acceptance coverage for future source-backend
  slices.

## Results

Pending. Fill this section after reproduction, implementation, and verification
with the exact root cause, fix location, commands, and observed outputs.
