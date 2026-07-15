# Control semantic vectors

Status: **implementation specification** (2026-07-15).

## Overview

This feature turns the control-interoperability laws into one target-neutral,
machine-checked vector catalog. The catalog is evidence for
[`control-interoperability.md`](control-interoperability.md); it does not own or
redefine effects, handlers, prompts, continuation multiplicity, capture, or
saved-continuation semantics.

Each execution lane declares exactly which catalog vectors it can execute now and
why every other vector is pending or inapplicable. A green portable-ASM row cannot
stand in for the portable VM, the compiler-independent Scala API, a managed
direct-style transform, an AOT backend, or a host/runner profile.

## Interface

The canonical inventory lives under `tests/interop-conformance/`:

```text
vectors.tsv                 target-neutral vector identity and oracle
lanes.tsv                   lane adapter, readiness, and capability inventory
probes/<id>-<slug>.ssc      ScalaScript adapter when the lane accepts .ssc
expected/<id>-<slug>.txt    exact rendered stdout/stderr for that adapter
pending/<id>-<slug>.pending detailed prerequisite for a blocked vector
run.sh                      catalog validator and lane runner
```

`vectors.tsv` is UTF-8 TSV with one header and these fields:

```text
id  slug  law  capabilities  phase  expectedExit  expectedStream  oracle
```

- `id` is a stable zero-padded decimal id. It is never reused for another law.
- `slug` is the stable kebab-case vector name and matches probe/expected names.
- `law` is the normative section anchor in `control-interoperability.md`.
- `capabilities` is a sorted comma-separated set of semantic capabilities.
- `phase` is `specified`, `pending-codec`, or `pending-spec`. Runtime readiness is
  lane-specific: for example the prompt vectors are specified and runnable on the
  explicit Scala lane while the current `.ssc` lanes lack `shift-reset`.
- `expectedExit` is `zero`, `nonzero`, or `pending`.
- `expectedStream` is `stdout`, `stderr`, `structured`, or `pending`.
- `oracle` is a short canonical result/failure description. Exact rendered bytes
  remain in `expected/` when a process adapter is runnable.

Tabs and newlines are forbidden inside fields. The validator rejects duplicate ids
or slugs, unknown phases/exit/stream values, missing laws, unsorted/duplicate
capabilities, missing process probes/oracles for an eligible process lane, orphan
probes/expected/pending files, and probe front-matter that disagrees with the
catalog.

`lanes.tsv` is UTF-8 TSV with these fields:

```text
lane  adapter  status  capabilities  reason
```

`status` is `ready`, `optional`, or `pending`. A ready lane is part of the default
gate. An optional lane runs under `--all-installed` when its adapter and external
toolchain are available. A pending lane is always printed with its reason. Every
named mandatory profile has a row even before it can execute a vector.

The stable lane ids are:

```text
portable-vm
portable-asm
scala-explicit
scala-direct
jvm-generated
js-generated
rust-generated
wasm-generated
swift-generated
```

`tests/interop-conformance/run.sh` supports:

```text
run.sh                         validate + run every ready process lane
run.sh --validate              validate catalogs and adapters only
run.sh --list                  print the complete lane-by-vector readiness matrix
run.sh --lane <lane>           run one selected lane
run.sh --all-installed         run ready and installed optional lanes
```

`SSC=/path/to/ssc` selects the standard launcher for portable lanes. Optional
native/AOT adapters use an explicit executable environment variable named by the
lane descriptor; they never fall back to another backend. Tool absence is
`UNAVAILABLE`, not `PASS`.

The compiler-independent Scala adapter is a ScalaTest suite in the existing
`scala3ControlApi` test scope. It reads the same catalog, maps stable vector ids to
typed `scalascript.control` programs, and compares canonical structured outcomes.
It does not add a production dependency or publish a second semantics library.

## Vector inventory

The initial catalog preserves axes 01--21 and adds separately identifiable rows
where the former delimited-control umbrella was too coarse:

- one-shot success and exact `AlreadyResumed(OperationId)` rejection;
- zero, one, and many resumes;
- deep handler reinstall, residual forwarding, and return placement;
- stackless effect/control and tail recursion;
- managed callback re-entry;
- fresh prompt isolation, nearest same-prompt reset, nested prompts, and the
  same-prompt shift-body case that distinguishes `shift` from `shift0`;
- reusable continuation control-copy with ordinary shared mutable heap;
- deterministic unmanaged-capture/capture-barrier failures;
- durable save/run, admission, remote, and no-replay cases as explicit
  `pending-codec` rows until their post-X1 implementation lands.

Cancellation remains `pending-spec`. The current common specification names
cancellation as required evidence but does not define public states, transition
ordering, interaction with one-shot ownership, or the exact diagnostic. This
feature records that gap and refuses to invent a test oracle. Once the semantic
owner freezes those transitions, the same row becomes executable without changing
its id.

## Lane mapping

One semantic vector may have different source adapters, but never a different law:

- portable VM and direct ASM execute the same `.ssc` probe and compare the same
  exact process oracle;
- the explicit Scala leaf executes a typed native adapter and compares the same
  canonical value or structured failure;
- managed Scala direct style must differentially agree with the explicit adapter;
- generated JVM, JS, Rust, WASM, and Swift lanes execute the `.ssc` adapter where
  their declared capability set admits it;
- a native host vector may use host source only when the mapping names the same
  vector id and the host profile requires that representation.

An AOT backend passing portable `.ssc` vectors is useful backend evidence but does
not imply a bidirectional host bridge or dynamic saved-capsule runner. The lane row
and capability names keep those claims separate.

## Behavior

- [ ] The catalog validator rejects duplicate, malformed, orphaned, or silently
      omitted vector/lane records before any user program executes.
- [ ] Default execution runs both `portable-vm` and `portable-asm`; every runnable
      process vector has identical exit/stream bytes on both lanes.
- [ ] The explicit Scala API executes the catalog's currently implementable effect,
      handler, prompt, mutation, stack-safety, one-shot, and capture-negative laws.
- [ ] Fresh/nested prompts, nearest matching reset, and same-prompt shift-body
      behavior have distinct stable vector ids rather than one umbrella result.
- [ ] Multi-shot continuation evidence proves control is copied while an ordinary
      captured mutable heap cell remains shared across local resumes.
- [ ] One-shot and managed-capture negatives assert structured identities first and
      exact CLI rendering where a process boundary exists.
- [ ] Every mandatory future lane is present in the matrix and reports `PENDING` or
      `UNAVAILABLE` instead of being omitted or counted green.
- [ ] Cancellation is recorded as `pending-spec` until its public transition and
      diagnostic contract is frozen by the target-neutral semantic owner.
- [ ] Durable/admission/network vectors remain explicit `pending-codec` rows until
      X1 and the capsule implementation make them executable.
- [ ] The affected Scala suite, portable VM/ASM matrix, catalog validation, and
      project conformance slice pass from an isolated worktree.

## Decisions

- **One normalized catalog plus lane declarations.** This makes omissions and
  accidental lane substitution mechanically visible. Rejected: independent lists
  in README, shell, and each host suite; they drift without a shared key.
- **Stable semantic ids, adapter-specific source.** Different host languages cannot
  share source syntax, but they can share law identity and oracle. Rejected: forcing
  all lanes through `.ssc`, which would test only backends rather than native hosts.
- **Capabilities determine eligibility.** A tool existing on `PATH` is not proof it
  implements effects, prompts, or saved continuations. Rejected: optimistic
  auto-detection followed by treating compilation failure as conformance evidence.
- **No new production module.** The catalog is test evidence and the explicit Scala
  adapter remains in `scala3ControlApi` test scope. Rejected: publishing a
  `control-vectors` runtime artifact that applications could mistake for semantics.
- **No cancellation guess.** The known specification gap is preserved explicitly.
  Rejected: choosing cancellation states in a harness and thereby making tests an
  accidental semantic owner.

## Out of scope

- Changing CoreIR, the v2 frontend/lowering, the seed image, or compiler fixed-point
  bytes before X1.
- Implementing `.ssc` shift/reset lowering, Scala direct-style macros/plugins, a
  host value/call bridge, or a durable continuation codec in this slice.
- Treating an AOT backend row as proof of host-bridge or runner qualification.
- Defining cancellation semantics, retry, exactly-once effects, or prefix replay.

## Results

Fill after implementation verification with exact vector counts, lane counts,
commands, and any deliberately pending rows.
