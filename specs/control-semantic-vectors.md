# Control semantic vectors

Status: **implemented and verified** (2026-07-15).

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

- `id` is a stable zero-padded decimal id. Rows are strictly ordered and
  contiguous from `01`; a new vector appends the next id. An obsolete or blocked
  law remains an explicit phased row instead of being deleted, and an id is never
  reused for another law.
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

`status` is `ready`, `optional`, or `pending`. Every ready process lane is part of
the default shell gate. A ready host-test lane is mandatory in its owning project
suite and runs through `--lane` or `--all-installed`; the default command does not
silently count an unexecuted host suite green. An optional lane runs under
`--all-installed` when its adapter and external toolchain are available. A pending
lane is always printed with its reason. Every named mandatory profile has a row
even before it can execute a vector.

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

The current bindings are exact, not interchangeable labels:

```text
portable-vm   -> ssc-vm                 ready
portable-asm  -> ssc-asm                ready
scala-explicit -> scala3-control-test    ready
scala-direct  -> scala3-control-macros-test ready
all remaining mandatory lanes -> none   pending
```

The validator rejects an unknown lane, a swapped adapter/status binding, or a
`ready` lane whose declared capabilities admit no executable specified vector.
Changing one of these bindings requires the same explicit catalog-and-runner
revision as adding an adapter.

`tests/interop-conformance/run.sh` supports:

```text
run.sh                         validate + run every ready process lane
run.sh --validate              validate catalogs and adapters only
run.sh --list                  print the complete lane-by-vector readiness matrix
run.sh --lane <lane>           run one selected lane
run.sh --all-installed         run ready and installed optional lanes
```

`SSC=/path/to/ssc` selects the standard launcher for portable lanes. Adapter ids
are closed and validated in this version: `ssc-vm`, `ssc-asm`,
`scala3-control-test`, `scala3-control-macros-test`, and `none`. The direct adapter
is frozen by [`scala3-control-macros.md`](scala3-control-macros.md) and is ready
with the bounded `shift-reset` capability. Adding an optional native/AOT adapter requires
an explicit catalog-and-runner revision that names its executable environment
variable; an adapter never falls back to another backend. Tool absence is
`UNAVAILABLE`, not `PASS`.

Both Scala adapters are ScalaTest suites in the existing `scala3ControlApi` test
scope. They read the same catalog, map stable vector ids to typed
`scalascript.control` programs, and compare canonical structured outcomes. The
direct adapter additionally compares each eligible result to the explicit Scala
program. Neither adds a production dependency or publishes a second semantics
library.

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

Vector 12 retains its stable compound identity but has three independently
asserted admission subcases: incompatible value/frame codec schema is
`CodecMismatch`; a present exact artifact with incompatible ABI/control identity
is `AbiMismatch`; and an absent required exact artifact/toolchain implementation
is `MissingDependency`. A target adapter must not collapse these failures.

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

Vector 22 carries both `prompt-isolation` and `shift-reset`. The extra capability
is intentional: its law forwards an outer prompt through a nested reset for a
different prompt. The bounded Scala inline M1 lane advertises only `shift-reset`,
so it executes vectors 18 and 23; the explicit Scala lane advertises both and keeps
vector 22. The later managed compiler-plugin lane must gain `prompt-isolation`
before it can claim that residual-row boundary.

## Behavior

- [x] The catalog validator rejects duplicate, malformed, orphaned, or silently
      omitted vector/lane records before any user program executes.
- [x] Default execution runs both `portable-vm` and `portable-asm`; every runnable
      process vector has identical exit/stream bytes on both lanes.
- [x] The explicit Scala API executes the catalog's currently implementable effect,
      handler, prompt, mutation, stack-safety, one-shot, and capture-negative laws.
- [x] Fresh/nested prompts, nearest matching reset, and same-prompt shift-body
      behavior have distinct stable vector ids rather than one umbrella result.
- [x] Multi-shot continuation evidence proves control is copied while an ordinary
      captured mutable heap cell remains shared across local resumes.
- [x] One-shot and managed-capture negatives assert structured identities first and
      exact CLI rendering where a process boundary exists.
- [x] The compound codec/exact-artifact admission vector preserves distinct
      `CodecMismatch`, `AbiMismatch`, and `MissingDependency` constructors.
- [x] Every mandatory future lane is present in the matrix and reports `PENDING` or
      `UNAVAILABLE` instead of being omitted or counted green.
- [x] Cancellation is recorded as `pending-spec` until its public transition and
      diagnostic contract is frozen by the target-neutral semantic owner.
- [x] Durable/admission/network vectors remain explicit `pending-codec` rows until
      X1 and the capsule implementation make them executable.
- [x] The affected Scala suite, portable VM/ASM matrix, catalog validation, and
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
- Implementing `.ssc` shift/reset lowering, a Scala compiler plugin, a host
  value/call bridge, or a durable continuation codec in this slice. The later
  lexical macro M1 supplies the now-ready `scala-direct` adapter without changing
  the catalog's semantic ownership.
- Treating an AOT backend row as proof of host-bridge or runner qualification.
- Defining cancellation semantics, retry, exactly-once effects, or prefix replay.

## Results

- `vectors.tsv` contains 26 contiguous stable ids: 17 `specified`, eight
  `pending-codec` (10--17), and one deliberately `pending-spec` cancellation row
  (26). `lanes.tsv` declares all nine mandatory lanes; the explicit and bounded
  direct Scala lanes are ready, while five future generated host/AOT lanes remain
  explicitly `pending`.
- `tests/interop-conformance/validation-test.sh` passes nine negative cases:
  duplicate vector/lane, removed id, swapped stable adapter, empty ready lane,
  missing eligible probe, orphan expected bytes, mismatched front-matter, and
  missing pending record.
- After `scripts/sbtc "cli/installBin"`, the default
  `SSC="$PWD/bin/ssc" tests/interop-conformance/run.sh` gate passes all 13 eligible
  exact-output vectors on `portable-vm` and the same 13/13 on `portable-asm`.
- `tests/interop-conformance/run.sh --lane scala-explicit` passes 17 typed semantic
  programs plus the catalog/program coverage test (18/18).
- `tests/interop-conformance/run.sh --lane scala-direct` passes vectors 18 and 23
  plus catalog/program coverage (3/3), with an explicit-API differential oracle.
  The complete `scripts/sbtc "scala3ControlApi/test"` scope passes 80/80.
- `tests/conformance/run.sh --only 'effect*,effects*'` passes all five affected
  conformance cases across every lane each case declares. `git diff --check` is
  clean.
