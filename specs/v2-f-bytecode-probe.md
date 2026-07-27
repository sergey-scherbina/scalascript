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

Product integration adds no CLI flag and does not change `.ssc` semantics.
When `SSC_FRONT_TRACE=1` is set, a selected nested compiler run prints this
stable stderr marker:

```text
[SSC_FRONT=F] nested F0 direct-ASM
```

The marker is diagnostic only; program stdout remains the observable compared
for parity.

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
- [ ] The product F runner considers only its first nested `coreir.eval`.
      Direct ASM is selected only when that program contains a string-backed
      constant that requires multiple classfile modified-UTF8 entries. Small
      first programs and every later nested eval stay on the VM.
- [ ] Classifying a small first program does not initialize `JvmByteGen` or
      load `org.objectweb.asm.*`; backend classes load only after admission
      selects direct ASM.
- [ ] The nested evaluator is thread-scoped and restoring. It cannot affect a
      checker, legacy-front run, another thread, or a later tower run.
- [ ] `OpAnfNative.lift` plus bytecode emission finish before the candidate is
      considered started. `Unsupported` and ASM method/class-size failures in
      that link phase may delegate to VM; any failure from `runProgram` is
      propagated and never rerun on VM.
- [ ] A selected direct-ASM run gets a fresh `Emit.globalsRef` for its entire
      install/entry execution and restores the previous map in `finally`.
- [ ] The product gate compares stdout bytes before inspecting trace markers:
      hello stays on VM, while the exact product SClJet workload selects nested
      direct ASM and remains byte-identical to `SSC_FRONT=legacy`.

## Out of scope

- Changing ScalaScript source semantics or the CoreIR format.
- Weakening F's correctness-preserving legacy fallback.
- Making all `coreir.eval` calls use direct ASM.
- Changing the user program's VM/direct-ASM choice (`ssc run` versus
  `ssc run --bytecode`); this policy accelerates the compiler front only.
- Retrying a bytecode program after install or entry execution has begun.
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

Product integration is deliberately narrower than the probe. `Runtime` exposes
a restoring thread-local scope whose evaluator has the shape
`Program => Option[Value]`; `coreir.eval` asks that scope first and retains its
existing VM implementation when no evaluator is installed or it returns
`None`. `RunNativeV2` installs one evaluator inside the dedicated tower thread
only when the F runner is active. A local one-shot flag consumes the first
nested program before classification, preventing later YAML, Markdown, or
content evals from being mistaken for F0.

`JvmBytecodeAdmission.requiresStringChunking` is the shared admission
predicate. Its class has no ASM references or eager emitter state. It walks
program constants and owns the modified-UTF8 byte accounting plus chunk
splitting used by `JvmByteGen.loadString`, so selection cannot drift to a
character-count or ordinary-UTF8 heuristic and a rejected small program cannot
load the backend merely by being classified. When selected, the evaluator
lifts and emits before execution, temporarily replaces `Emit.globalsRef`, runs
the generated class, and restores the previous map. Only the same pre-execution
failures accepted by the public bytecode lane (`Unsupported` and ASM
method/class-size limits) return `None`. Invocation/runtime failures escape
after unwrapping `InvocationTargetException`.

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
- **Select by the first large nested F0, not unconditionally** — exact product
  SClJet benefits strongly, but emission/loading overhead makes a tiny hello
  F0 slower. Rejected: compiling the outer F runner (it leaves nested F0 on
  the VM and regresses hello) and compiling every `coreir.eval` (later content
  evals are different workloads and would pay unmeasured startup cost).
- **Delegate only before execution** — emission is side-effect-free, so an
  unsupported or JVM-size-limited candidate can safely use the existing VM
  implementation. Rejected: catch-and-rerun after `runProgram`, because install
  or entry may already have produced side effects.
- **Thread-local runtime hook, installed inside the tower thread** — the tower
  deliberately runs on a dedicated large-stack thread, so a caller-thread
  scope would not propagate. A process-global callback would contaminate
  checkers and concurrent/later compilations.
- **ASM-free admission helper** — even a read-only call on `JvmByteGen`
  initializes its emitter object and ASM `Handle`; the backend-isolation gate
  caught this on hello. The selector therefore calls a data-only helper, and
  the emitter consumes that helper's chunking implementation. Rejected:
  duplicating the byte-count test in `RunNativeV2` (selection/emission drift)
  and making ASM eager on every default VM compile.

## Results

The initial file-backed F0 control is admitted: five fresh VM runs had a
4.93-second median and five direct-ASM runs had a 2.27-second median (2.17x),
with identical 409,629-byte output.

The first exact product-shape control exposed the concrete blocker: SClJet
resolves to 593,193 source bytes, which the runner embeds in a 1,040,325-byte
F0; pre-fix emission rejected it with
`java.lang.IllegalArgumentException: UTF8 string too large`.

After modified-UTF8 chunking, that exact F0 is admitted. VM execution took
35.89 seconds and direct ASM 8.19 seconds (4.38x), with identical
1,082,761-byte output (recorded SHA-256 prefix `6f52b6`).
A three-run file-backed rerun measured 4.71 versus 2.12 seconds (2.22x), with
the same output digest as the five-run control.

An outer-runner control rejects unconditional product wiring: compiling the
outer hello runner to ASM took about 1.68 seconds versus 1.25 seconds on VM and
still executed nested F0 through `coreir.eval` on the VM. A direct tiny F0
control likewise measured about 0.44 seconds for emit/load/run versus 0.26
seconds on VM. These controls admit the selective large-nested-F0 policy and
reject unconditional ASM.
