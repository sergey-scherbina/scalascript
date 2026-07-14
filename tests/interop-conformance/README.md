# Control-interoperability conformance matrix

Runnable conformance harness for the target-neutral **control-interoperability**
model agreed in the Rozum `#interoperability` joint resolution (2026-07-14). It
measures a single runner against the normative axis matrix and prints one row per
axis. The **portable-VM** (`ssc run --bytecode`) is the *reference runner*: other
host/runner profiles (JVM, JS/TS, Rust, Swift, WASM/WASI) measure against the
same matrix, and "runner delivery order" is chosen by **measured** readiness, not
presumed backend age.

Normative spec ownership (see the joint resolution):

- `specs/control-interoperability.md` — target-neutral core (owner; in progress).
- `specs/scala3-bidirectional-control.md` — the frozen control model + `save()`/
  `run()` durable surface this harness targets.
- This harness — the executable reference row + the axis inventory.

## How to run

```bash
sbt installBin                       # build ./bin/ssc if needed
tests/interop-conformance/run.sh     # or: SSC=/path/to/ssc tests/interop-conformance/run.sh
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
| 06 | durable save/run, same process | pending-codec | needs DurableValue codec |
| 07 | cross-host resume | pending-codec | ✗ today (no capsule) |
| 08 | concurrent multi-shot | pending-codec | needs SavedContinuation |
| 09 | no prefix/main replay | pending-codec | needs init-free resume entry |
| 10 | raw ForeignV → Unsavable (neg.) | pending-codec | FrameGate discriminator |
| 11 | missing resolver → reject (neg.) | pending-codec | atomic admission |
| 12 | codec / ExactArtifact mismatch (neg.) | pending-codec | versioned-codec + pin |
| 13 | signature / quota (neg.) | pending-codec | untrusted-capsule admission |

Measurable-now axes (01–05) are the **in-process** control semantics, which the
portable-VM implements today via `handle(prog()) { case Eff.op(args, resume) => … }`
(validated against `tests/conformance/effect-deep-handler-state.ssc`). They are
the reference baseline every host profile must also pass.

Axes 06–13 require the durable capsule surface (`continuation.save()` /
`SavedContinuation.run()`), the `DurableValue` wire codec, and the atomic
admission layer. Per the v2.2 gate, byte-affecting codec work begins only **after
the X1 self-compilation fixed-point**; until then these are `pending-codec` and
recorded in `pending/` so each converts mechanically to a runnable probe when the
codec lands. The single ✗ the model exists to close is **07 (cross-host
resume)**: the portable-VM already does N→M resume in-process (02, 05); only the
durable serialization to cross a host boundary is missing.

## Layout

```
tests/interop-conformance/
  README.md                 # this matrix
  run.sh                    # runner: portable-VM reference row + pending enumeration
  probes/NN-axis.ssc        # runnable measurable-now probes
  expected/NN-axis.txt      # expected stdout
  pending/NN-axis.pending   # not-yet-measurable axes (spec + expected behaviour)
```

## Extending

- **New host/runner profile**: run this harness with `SSC=/path/to/that-runner`
  (or its equivalent) and record its row next to the portable-VM row. A profile
  cannot claim control-interop conformance without a native bidirectional
  value/call bridge (joint resolution point 2).
- **A pending axis becomes implementable**: move `pending/NN-*.pending` to a
  `probes/NN-*.ssc` + `expected/NN-*.txt` pair using the durable surface, and
  flip its matrix row to measurable-now.
