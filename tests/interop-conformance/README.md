# Control-interoperability conformance matrix

Runnable conformance harness for the target-neutral **control-interoperability**
model agreed in the Rozum `#interoperability` joint resolution (2026-07-14). It
measures a single runner against the normative axis matrix and prints one row per
axis. The **portable-VM** (`ssc run --bytecode`) is the *reference runner*: other
host/runner profiles (JVM, JS/TS, Rust, Swift, WASM/WASI) measure against the
same matrix, and "runner delivery order" is chosen by **measured** readiness, not
presumed backend age.

Normative spec ownership (see the joint resolution):

- `specs/control-interoperability.md` — landed sole target-neutral semantic owner.
- `specs/scala3-bidirectional-control.md` — Scala/JVM host profile only.
- `specs/control-interop-profile-portable-vm.md` — portable-VM reference runner
  profile and measured obligations.
- This harness — executable evidence/status and the axis inventory; it never
  redefines semantic laws.

## How to run

```bash
tests/interop-conformance/run.sh     # uses ./bin/ssc
SSC=/path/to/ssc tests/interop-conformance/run.sh
# only if no binary exists: scripts/sbtc "cli/installBin"
```

Exit code is non-zero if any *measurable-now* axis regresses. Pending axes print
as `PENDING` — the matrix is never silently reported as fully green.

## The matrix

Two orthogonal properties classify every capsule (joint resolution point 3):
`CodeMode = Portable(closed CoreIR) | ExactArtifact` and
`FrameGate = Savable(DurableValue/DurableRef) | Unsavable(reject)`. The axes
below exercise the control semantics and, where a capsule crosses a process or
host, those two gates.

| # | Axis | Status | portable-VM reference row |
|---|------|--------|---------------------------|
| 01 | one-shot resume | measurable-now | ✓ 42 |
| 02 | multi-shot resume (reusable N→M) | measurable-now | ✓ List(1, 2) |
| 03 | deep proper tail calls (intra-lane) | measurable-now | ✓ 2000000 |
| 04 | callback re-entry (managed closure) | measurable-now | ✓ 8 |
| 05 | capture + resume, same host | measurable-now | ✓ 30 |
| 06 | zero-resume (early return / abort) | measurable-now | ✓ -1 |
| 07 | deep handler reinstall over recursion | measurable-now | ✓ 3 |
| 08 | return-clause transform (Int→List) | measurable-now | ✓ List(42, 42) |
| 09 | nondeterminism (many-resume product) | measurable-now | ✓ List(11, 21, 12, 22) |
| 10 | raw ForeignV → Unsavable (neg.) | pending-codec | FrameGate discriminator |
| 11 | missing resolver → reject (neg.) | pending-codec | atomic admission |
| 12 | codec / ExactArtifact mismatch (neg.) | pending-codec | versioned-codec + pin |
| 13 | signature / quota (neg.) | pending-codec | untrusted-capsule admission |
| 14 | durable save/run, same process | pending-codec | needs DurableValue codec |
| 15 | cross-host resume | pending-codec | ✗ today (no capsule) |
| 16 | concurrent multi-shot | pending-codec | needs SavedContinuation |
| 17 | no prefix/main replay | pending-codec | needs init-free resume entry |
| 18 | delimited shift/reset (multi-prompt) | pending-runtime | `reset` unbound on VM |
| 19 | residual forwarding (nested handlers) | pending-runtime | inner ⇏ outer (no arm) |
| 20 | stack-safety, deep effect recursion | pending-runtime | overflow ~2k depth |
| 21 | one-shot violation diagnostic | measurable-now | ✓ `error [ONESHOT_VIOLATION]: One-shot violation: One.op resumed more than once` |

Measurable-now axes (01–09, 21) are the **in-process** control semantics the
portable-VM implements today via `handle(prog()) { case Eff.op(args, resume) => … }`
and `multi effect` (validated against `tests/conformance/effects.ssc` and
`effect-deep-handler-state.ssc`). They are the reference baseline every host
profile must also pass, and cover §14.1 semantic vectors that run on the VM:
one/many/zero-resume, one-shot rejection, deep handler reinstall, and
return-clause transform.

Two distinct pending groups — never silently green:

- **`pending-codec` (10–17)** require the durable capsule surface
  (`continuation.save()` / `SavedContinuation.run()`), the `DurableValue` wire
  codec, and the atomic admission layer. Per the v2.2 gate, byte-affecting codec
  work begins only **after the X1 self-compilation fixed point**. The single ✗ the
  whole model exists to close is **15 (cross-host resume)**: the portable-VM
  already does N→M resume in-process (02, 05, 09); only the durable serialization
  to cross a host boundary is missing.
- **`pending-runtime` (18–20)** are §14.1 semantic vectors the portable-VM
  effect runtime does **not** support yet, found empirically (2026-07-14, each
  `pending/` file records the exact measured evidence): delimited `shift`/`reset`
  is unbound; an inner handler does not forward an unhandled operation to an outer
  handler (`no arm for wr/2`); effect-performing recursion overflows the native
  stack between depth 500 and 2000 (pure TCO is unaffected — axis 03 ✓ at 2,000,000).
  These need effect-runtime support, **not** the codec.

## Layout

```
tests/interop-conformance/
  README.md                 # this matrix
  run.sh                    # runner: portable-VM reference row + pending enumeration
  probes/NN-axis.ssc        # runnable measurable-now probes
  expected/NN-axis.txt      # exact expected stdout/stderr selected by probe metadata
  pending/NN-axis.pending   # not-yet-measurable axes (spec + expected behaviour)
```

## Extending

- **New host/runner profile**: run this harness with `SSC=/path/to/that-runner`
  (or its equivalent) and record its row next to the portable-VM row. A profile
  cannot claim control-interop conformance without a native bidirectional
  value/call bridge (joint resolution point 2).
- **A pending axis becomes implementable**: move `pending/NN-*.pending` to a
  `probes/NN-*.ssc` + `expected/NN-*.txt` pair using the durable surface, and
  flip its matrix row to measurable-now. Success probes default to
  `expected-exit: zero` and `expected-stream: stdout`; expected-negative probes
  declare `expected-exit: nonzero` and normally `expected-stream: stderr`.
