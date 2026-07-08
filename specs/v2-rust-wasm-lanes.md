# v2 Rust/WASM Lanes

## Overview

This slice makes the v2 self-hosted target lanes credible production gates again. The Phase 4 Scala CoreIR source-generator harness (`v2/backend/check.sh`) is already green for JVM/JS/Rust; the remaining risk is the self-hosted `ssc0 -> JS/Rust/WASM` lane used by `v2/conformance/check.sh`.

## Interface

- `./v2/conformance/check.sh` remains the gate for the self-hosted VM/JS/Rust/WASM tower.
- `./v2/ssc0-rust <file.ssc0>` emits Rust that `rustc -O` can compile.
- `./v2/ssc0-js <file.ssc0>` emits Node-compatible JavaScript.
- `./v2/ssc0-wasm <file.ssc0>` reuses the Rust backend, compiles to `wasm32-wasip1`, and runs under Node's WASI host when the toolchain is installed.

## Behavior

- [x] Self-hosted JS/Rust/WASM target display matches VM `Show.show` for proper `Cons`/`Nil` lists: `List(a, b)`, not `Cons(a, Cons(b, Nil))`.
- [x] Self-hosted Rust emits valid `f64` literals for whole-valued floats after the accepted `Writer.floatStr` collapse (`2.0`, not `2` inside `V::Fl(...)`).
- [x] `v2/conformance/check.sh` expectations match the accepted kernel display contract: whole floats may render as `10`, and proper lists render as `List(...)`.
- [x] WASM remains toolchain-gated and green for quicksort plus 1e6-tail-call TCO through `ssc0-wasm`.

## Out of Scope

- Switching user-facing `ssc run-rust` / `build-rust` to the v2 Rust backend.
- Making Rust or WASM the default lane.
- Reworking the frozen v2 kernel display semantics; this slice aligns target emitters and gates to the already accepted VM contract.

## Design

Two Rust paths exist and must not be confused:

- `v2/backend/rust/RustBackend.scala` is the Scala CoreIR source generator used by the Phase 4 `.ssc -> FrontendBridge -> CoreIR -> target` work. It already aligns list display with the VM and is covered by `v2/backend/check.sh`.
- `v2/lib/backend-rust-gen.ssc0` and `v2/lib/backend-js-gen.ssc0` are self-hosted ssc0 target generators used by `v2/conformance/check.sh`. They still render raw `Cons`/`Nil` constructor syntax and are the source of the red target gate.

The lowest-risk fix is to align the self-hosted target display helpers with the VM and Scala source generators, then rebaseline only the conformance expectations that were made stale by the already-landed `p4-kernel-green` display decision.

## Results

Baseline from `feature/p4-rust-wasm-lanes` before implementation:

```text
./v2/backend/check.sh
ALL GREEN (8 fixtures x 3 backends)

./v2/conformance/check.sh
red: stale Cons/Nil and 10.0 display expectations, self-hosted JS/Rust list-display drift,
and Rust `V::Fl(2)` / `V::Fl(1)` compile errors for whole-valued float literals.
WASM quicksort and TCO checks pass with the installed wasm32-wasip1 + Node WASI toolchain.
```

Implemented in `84d7ac77f`:

```text
./v2/conformance/check.sh
green, including self-hosted JS/Rust target rows, Rust float numeric rows, and
WASM quicksort + 1e6-tail-call TCO.

./v2/backend/check.sh
ALL GREEN (8 fixtures x 3 backends)

tests/conformance/run.sh --only 'effects,effect-*,async*,direct-*,js-*-effect-*,std-functor-applicative-monad,std-foldable-traversable,std-index' --no-memo
12 passed, 0 failed

tests/conformance/run.sh --only 'rust*,wasm*' --no-memo
0 matching top-level cases; Rust/WASM coverage for this slice is the v2 gate above.
```

Implementation note: the full v2 gate exposed a VM-only typed effect regression
after the target fixes. `Runtime.scala` now only auto-threads statement/binding
`DataV("Op", ...)` values when they are bridge/runtime effects with dotted labels
such as `Console.writeLine`; pure v2 free-monad Ops such as `Op("log", ...)`,
`Op("yield", ...)`, and `Op("QA", ...)` remain ordinary data for their
schedulers/handlers to match.
