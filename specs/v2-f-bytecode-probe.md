# F direct-ASM admission probe (V-6b)

## Overview

V-6a measured the self-hosted F frontend running as a tree-walked CoreIR
program and attributed most compile time to that execution. This slice tests
one structural hypothesis: whether the already-bootstrapped F0 program can run
through the existing `JvmByteGen` direct-ASM lane, with no transparent VM
fallback, while producing the same `F(F_src)` bytes.

This is an admission experiment, not a promise that direct ASM will win. A
negative result is complete only when it names the exact emitter or JVM limit
and records enough evidence to choose the next V-6c optimisation.

## Interface

The repository exposes one developer command:

```bash
scripts/v2-f-bytecode-probe [--reps N] [--keep DIR]
```

The command rebuilds its inputs from the current checkout, runs VM and
direct-ASM candidates in fresh JVMs, and prints machine-readable summary
lines:

```text
VM_OUTPUT_SHA256=<digest>
BYTECODE_ADMISSION=accepted|rejected
BYTECODE_OUTPUT_SHA256=<digest-or-none>
OUTPUT_EQUAL=yes|no|not-run
VM_MEDIAN_SECONDS=<seconds>
BYTECODE_MEDIAN_SECONDS=<seconds-or-none>
BYTECODE_SPEEDUP=<ratio-or-none>
```

On rejection it also prints the exception class and message. A rejected,
miscompiled, or byte-different candidate exits non-zero; it is never converted
to a passing VM run.

## Behavior

- [ ] The probe rebuilds one kernel plus one F0 from the current checkout and
      feeds the identical F source bytes and program arguments to both lanes.
- [ ] A VM control executes F0 and establishes the expected `F(F_src)` output
      before the bytecode result is classified.
- [ ] The direct-ASM candidate performs
      `OpAnfNative.lift -> JvmByteGen.emitProgram -> JvmByteGen.runProgram`
      directly, with no catch-and-run-VM path.
- [ ] `JvmByteGen` accepts a product-shaped F0 whose embedded user-source
      `CStr` exceeds the classfile modified-UTF8 limit. It reconstructs the
      exact string from bounded constants, including NUL, BMP, and surrogate
      code units, without changing CoreIR or filesystem semantics.
- [ ] Output bytes are compared before acceptance or performance
      classification; a mismatch prints both digests and fails.
- [ ] Raw fresh-process samples, medians, and the bytecode/VM speedup are
      printed. The default repetition count is at least three.
- [ ] The result is assessed against the V-6a admission target: exact output
      parity and at least 2x faster than the 5.17 s direct self-compile
      baseline. Product integration remains blocked unless both hold.
- [ ] The probe leaves the checkout unchanged and either removes temporary
      artifacts or preserves all of them under the explicit `--keep`
      directory.

## Out of scope

- Changing ScalaScript source semantics or the CoreIR format.
- Weakening F's correctness-preserving legacy fallback.
- Editing `RunNativeV2` while the separate
  `v2-bytecode-lane-silent-downgrade` claim owns that integration path.
- Optimising `JvmByteGen` before the probe identifies a concrete unsupported
  or slow shape.
- Treating a VM fallback as bytecode evidence.

## Design

The shell command reuses the bootstrap prefix from
`specs/v2.2-p6.5-fsub.sh` to construct F0. It builds a small probe driver
against `v2Core`, `v2JvmRuntime`, and `v2JvmBytecode`. The driver has two
explicit modes:

- `vm`: parse F0 with `Reader`, compile it with `Compiler`, and run it through
  `Runtime.runManaged`;
- `bytecode`: parse the same F0, reset `Emit.globalsRef`, lift effects with
  `OpAnfNative`, emit one JVM class, and invoke it with `JvmByteGen.runProgram`.

Both modes set `Runtime.argv` to the same F source path and capture stdout in
separate files. The shell layer hashes and compares those files before
calculating timing statistics. The bytecode process reports emitter and JVM
exceptions verbatim and terminates; it contains no fallback branch.

JVM constant-pool UTF8 entries are limited to 65,535 modified-UTF8 bytes. A
product F0 embeds the resolved user source as a `CStr`, so realistic source
closures can exceed that limit even though F's file-backed bootstrap F0 does
not. `JvmByteGen` therefore emits a direct `ldc` only when the literal fits.
Otherwise it partitions UTF-16 code units into chunks bounded by their
modified-UTF8 encoded length and constructs one `String` with
`StringBuilder.append`. Chunking by the classfile encoding, rather than source
character count or ordinary UTF-8, preserves NUL and surrogate behavior and
keeps every constant independently valid.

The probe intentionally uses fresh JVM processes. This answers the product
compile-time question and avoids making a warmed in-process loop look like a
cold frontend improvement.

## Decisions

- **Probe F0, not product `.ssc` input** — F0 is the already-bootstrapped
  compiler program identified by V-6a; probing a user program would test the
  execution backend but not F itself.
- **Compare bytes before classifying support** — successful class emission is
  insufficient if the generated program changes F output.
- **Keep fallback out of the candidate driver** — `RunNativeV2` currently
  falls back at link time, which can hide rejection and pre-judge the
  experiment as green.
- **Chunk oversized `CStr` values in the emitter** — the classfile limit is a
  JVM representation constraint, not a language or CoreIR limit. Rejected:
  truncating source (incorrect), writing literals to temporary files (changes
  artifact/runtime semantics), and a character-count-only threshold (does not
  model modified UTF8).
- **Use an external developer script before product wiring** — this keeps a
  rejected hypothesis out of the CLI and provides a reusable regression gate
  if the lane is admitted.

## Results

The initial file-backed F0 control is admitted: five fresh VM runs had a
4.93-second median and five direct-ASM runs had a 2.27-second median (2.17x),
with identical 409,629-byte output.

The first exact product-shape control is intentionally red. SClJet resolves to
593,193 source bytes, which the current runner embeds in a 1,040,325-byte F0.
`JvmByteGen.emitProgram` rejects it in 0.26 seconds with
`java.lang.IllegalArgumentException: UTF8 string too large`. This is the
concrete blocker the large-string behavior item must turn green.
