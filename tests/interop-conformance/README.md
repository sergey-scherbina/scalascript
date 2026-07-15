# Control-interoperability semantic vectors

This directory is the executable evidence matrix for the target-neutral laws in
[`specs/control-interoperability.md`](../../specs/control-interoperability.md).
That specification remains the sole semantic owner. The catalog records stable
law identities, canonical outcomes, lane capabilities, and current readiness; it
does not redefine effects, handlers, prompts, continuation multiplicity, capture,
or saved-continuation behavior.

## Catalog model

Two checked TSV files replace hand-maintained per-runner matrices:

| File | Purpose |
|---|---|
| [`vectors.tsv`](vectors.tsv) | Stable vector id, law anchor, required capabilities, delivery phase, process contract, and canonical oracle |
| [`lanes.tsv`](lanes.tsv) | Stable lane id, concrete adapter, readiness, advertised capabilities, and an explicit reason for every pending lane |

A vector is runnable on a lane only when it is `specified` and all of its
capabilities are present in that lane. Process adapters additionally require an
exact `probes/<id>-<slug>.ssc` and `expected/<id>-<slug>.txt` pair. Structured
host results use the same catalog oracle through a native typed adapter.

The validator rejects malformed, duplicate, non-contiguous, or missing rows;
unsorted capabilities; swapped lane adapter/status bindings; a ready lane with no
executable vector; catalog/front-matter disagreement; missing eligible adapters;
and orphaned probe, expected, or pending files. A missing tool or capability is
reported as `UNAVAILABLE`, `UNSUPPORTED`, or `PENDING`; it is never counted as a
pass.

## How to run

```bash
# Validate, then run both ready process lanes: portable-vm and portable-asm.
tests/interop-conformance/run.sh

# Validate catalogs and adapters without executing a program.
tests/interop-conformance/run.sh --validate

# Print every lane × vector status, including unsupported and pending cells.
tests/interop-conformance/run.sh --list

# Run one adapter. This is also how to run the two Scala 3 suites.
tests/interop-conformance/run.sh --lane portable-vm
tests/interop-conformance/run.sh --lane portable-asm
tests/interop-conformance/run.sh --lane scala-explicit
tests/interop-conformance/run.sh --lane scala-direct

# Run every ready adapter plus any installed optional adapters.
tests/interop-conformance/run.sh --all-installed

# Select the standard launcher used by the two portable process lanes.
SSC=/path/to/ssc tests/interop-conformance/run.sh
```

The default command intentionally runs ready **process** lanes only. The
The `scala-explicit` and `scala-direct` adapters invoke their ScalaTest suites
through `scripts/sbtc`, so they are selected explicitly or via `--all-installed`.

## Current evidence

Catalog validation currently covers **26 vectors** and **9 lanes**:

| Lane | Adapter | Current evidence |
|---|---|---|
| `portable-vm` | `ssc-vm` | Ready; 13/13 eligible process vectors compare exact exit code and stdout/stderr bytes |
| `portable-asm` | `ssc-asm` | Ready; the same 13/13 process vectors and exact bytes |
| `scala-explicit` | `scala3-control-test` | Ready; 17 semantic-vector tests plus one catalog/program coverage test (18 tests total) |
| `scala-direct` | `scala3-control-macros-test` | Ready; vectors 18 and 23 plus one catalog/program coverage test (3 tests total), differentially checked against the explicit Scala API |
| `jvm-generated` | none | **PENDING** — typed managed JVM control lane is not qualified |
| `js-generated` | none | **PENDING** — JS/TypeScript host and runner profile is not qualified |
| `rust-generated` | none | **PENDING** — Rust host and runner profile is not qualified |
| `wasm-generated` | none | **PENDING** — WASM/WASI dynamic runner profile is not qualified |
| `swift-generated` | none | **PENDING** — Swift host and runner profile is not qualified |

The vector phases are deliberately visible:

- **17 `specified` vectors** cover one-shot and reusable resume, zero-resume,
  callbacks, deep handlers, residual forwarding, return placement, proper tail
  calls, stack safety, prompt isolation, true `shift`, shared-heap multi-shot
  behavior, and managed-capture rejection.
- **8 `pending-codec` vectors (10–17)** retain durable save/run, admission,
  cross-host execution, concurrent saved runs, and no-replay obligations until the
  post-X1 capsule implementation exists. Compound vector 12 separately requires
  `CodecMismatch`, `AbiMismatch`, and `MissingDependency`; an adapter cannot
  collapse those admission failures.
- **1 `pending-spec` vector (26)** records cancellation without inventing public
  transitions or a diagnostic that the target-neutral specification has not yet
  frozen.

Prompt vectors **18, 22, and 23** are specified and ready on `scala-explicit`.
The bounded lexical `scala-direct` M1 lane runs vectors **18 and 23**. Vector 22
also requires `prompt-isolation`: forwarding an outer prompt through a nested
different-prompt reset is deliberately reserved for the compiler-plugin tier.
They are unsupported on today's `.ssc` portable VM/ASM lanes because those lanes
do not yet advertise `shift-reset`; this is lane-specific readiness, not a global
semantic pending state. Vector 25 is likewise a structured explicit-API negative
rather than a process probe.

## Layout

```text
tests/interop-conformance/
  vectors.tsv                 target-neutral identities and canonical oracles
  lanes.tsv                   adapter readiness and capability inventory
  run.sh                      validator, matrix printer, and lane dispatcher
  probes/<id>-<slug>.ssc      runnable ScalaScript process adapters
  expected/<id>-<slug>.txt    exact expected process bytes
  pending/<id>-<slug>.pending explicit prerequisite for blocked vectors
```

## Extending the matrix

- Add or change a law in the target-neutral specification first; this harness is
  evidence, not a semantic owner.
- Give every new law a stable vector id, canonical oracle, and sorted capability
  set in `vectors.tsv`.
- Add a lane only with an explicit adapter, status, capability inventory, and
  reason. A generated backend lane does not by itself prove a native host bridge
  or a dynamic saved-continuation runner.
- When a pending vector becomes executable, keep its id, change its phase, and add
  the required process or native host adapter. Run `--validate` before executing
  any lane.
