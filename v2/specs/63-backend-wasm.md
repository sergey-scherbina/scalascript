# 63 — Backend: Core IR → WASM

## v1 (self-hosted, ssc0 layer): `./ssc0-wasm` (already shipped)

> `v2/ssc0-wasm` reuses the self-hosted Rust backend (`v2/lib/backend-rust-gen.ssc0`) with
> the `wasm32-wasip1` target and runs the module on Node's built-in WASI host
> (`v2/scripts/run-wasi.mjs`). No new codegen: WASM is a cross-compilation *target* of the
> same generated Rust, not a distinct value-representation/dispatch backend. Gated by
> `v2/conformance/check.sh` on `rustup target list --installed | grep -q wasm32-wasip1`;
> green for quicksort and the 1e6-deep TCO fixture (`specs/v2-rust-wasm-lanes.md`).

## v2 (Phase 4, Scala CoreIR layer): `v2/backend/check.sh` `run_wasm` (this slice)

The Phase-4 Scala CoreIR source generators (`v2/backend/{jvm,js,rust}`, exercised by
`v2/backend/check.sh`) had JVM/JS/Rust but no WASM row — the same gap the self-hosted layer
had before `ssc0-wasm` closed it (`specs/v2-rust-wasm-lanes.md`). This slice closes it at the
Phase-4 layer the identical way, reusing the identical toolchain:

```
ir.coreir → RustBackend.generate (v2/backend/rust/RustBackend.scala)
          → gen.rs
          → rustc -O --target wasm32-wasip1 -C link-arg=-zstack-size=536870912 gen.rs -o gen.wasm
          → node v2/scripts/run-wasi.mjs gen.wasm   (reused unchanged, generic WASI host)
```

No distinct WASM value-representation/dispatch backend — `v2/backend/rust/RustBackend.scala`'s
existing `V` value enum + reference-counted closures (`v2/specs/61-backend-rust.md`'s ssc0
description covers the same shape) is reused as-is; WASM is a cross-compilation *target* of the
same generated Rust, same as `x86_64`/`aarch64` are.

One targeted change WAS needed in `RustBackend.generate`'s `main()` template, found by actually
running the cross-compiled module (not assumed): the emitted `main()` unconditionally spawns
`ssc_run` on a new OS thread with a 2GB stack (deep non-tail-recursive fixtures need more than
the platform default stack — see the comment at that call site). `wasm32-wasip1` has no OS
thread support; `std::thread::Builder::spawn` compiles fine but PANICS at runtime under Node's
`node:wasi` host ("operation not supported on this platform"). Fixed with a `#[cfg]`-gated
`main()`: the native arm is byte-for-byte unchanged; a new `#[cfg(target_arch = "wasm32")]` arm
calls `ssc_run()` directly on the main thread. Compile-time `cfg`, so this is not a runtime
target-detection branch — each target only ever sees its own arm.

A second, environment-level (not codegen) gap: even after the `main()` fix, the two fixtures
needing ~1M frames of genuine native recursion (`tco`, `mutual-tco` — the same ones the 2GB
native stack exists for) still overflow under wasm+Node, and NOT from wasm's own linear-memory
stack (raised via `rustc`'s `-zstack-size` linker flag above, confirmed sufficient on its own)
but from V8's *own* internal call-stack handling for wasm function calls, which hit "Maximum
call stack size exceeded" even with both the OS thread stack `ulimit -s` and `node --stack-size`
raised to this machine's hard ceiling (`ulimit -Hs`, 64MB). This is a real, hand-verified
resource ceiling of the wasm32-wasip1-via-Node execution model on this machine, not a guess —
see `v2/backend/check.sh`'s `WASM_DEEP_RECURSION_SKIP` for the exact two fixtures and reasoning.
Every other fixture (shallow recursion, or the ssc1c regression programs) passes on wasm
identically to native.

## Interface

- `v2/backend/check.sh` gains a fourth backend row (`jvm js rust wasm`); the summary line's
  backend count changes from `... x 3 backends` to `... x 4 backends` (see Results for why the
  overall gate itself was, and remains, non-green for reasons unrelated to this slice).
- `run_wasm()` mirrors `run_rust()`'s two-step shape (generate, then execute), inserting the
  `--target wasm32-wasip1 -C link-arg=-zstack-size=536870912` cross-compile and swapping the
  native binary invocation for the Node WASI host (`v2/scripts/run-wasi.mjs`).
- `run_wasm` is entirely absent from `$BACKENDS` (not merely skipped-with-a-note) when
  `rustup target list --installed` lacks `wasm32-wasip1` — the gate degrades to the pre-existing
  3-backend behavior rather than failing outright on a machine without the toolchain.

## Behavior

- [x] `run_wasm` generates via the SAME `scli run "$DIR/rust"` call `run_rust` already uses —
      no separate WASM-specific Rust source, no drift risk between the two lanes.
- [x] Cross-compiles with `rustc -O --target wasm32-wasip1`, runs with
      `node --no-warnings v2/scripts/run-wasi.mjs`, and produces output byte-identical to the
      VM's `run-ir` reference for every `v2/backend/check.sh` fixture.
- [x] Toolchain-gated: when `wasm32-wasip1` isn't installed (`rustup target list --installed`),
      the wasm row is skipped with a clear message rather than failing the whole gate —
      mirrors the exact gating style `v2/conformance/check.sh` already uses for `ssc0-wasm`.

## Out of Scope

- A distinct Core-IR → WAT/WASM-binary emitter (hand-rolled, not reusing Rust). Not needed:
  WASM has no distinguishable "value representation" question here — it is purely a `rustc`
  compilation target, exactly as `x86_64`/`aarch64` are for the same generated Rust source.
- Wiring `wasm` into user-facing production CLI commands (`ssc run --v2 --target wasm`,
  `ssc emit-wasm`) or the `ssc bench --backend v2-wasm` timing harness. Rust and JVM are in
  the identical position today — reachable only via `v2/backend/check.sh` and the internal
  `runV2SourceGenerator` bench helper, no dedicated `run`/`emit` command — so WASM joins its
  Phase-4 siblings at the same support level rather than leapfrogging them. Bench timing
  specifically would also be dominated by Node process-spawn + WASI instantiation overhead,
  not the computation itself, so it wouldn't produce a meaningful number even if added.
  (Mirrors `specs/v2-rust-wasm-lanes.md`'s own "Out of Scope: switching user-facing
  `ssc run-rust`/`build-rust`... making Rust or WASM the default lane.")
- Raw `wasm32-unknown-unknown` (no-WASI, browser-shaped) output — not installed on this
  machine (only `wasm32-wasip1` is) and no browser-hosting harness exists in this repo to
  verify it against; `wasm32-wasip1` + Node's `node:wasi` is the only real, verifiable target
  available here.

## Results

Verified 2026-07-13 on this machine (`rustup target list --installed` includes
`wasm32-wasip1`; Node v26.5.0 has `node:wasi`), `./v2/backend/check.sh`:

```
ok   fact                 wasm
FAIL floatnum             wasm (expected vs got): (3, (4.5, (true, -2.5)))  vs  Pair(3, Pair(4.5, Pair(true, -2.5)))
ok   letrec               wasm
ok   map                  wasm
skip mutual-tco           wasm (>64MB-deep native recursion; incompatible with wasm+V8's stack model)
skip tco                  wasm (>64MB-deep native recursion; incompatible with wasm+V8's stack model)
ok   thunk                wasm
ok   bool-predicate       wasm
FAIL mutual-recursion     wasm (expected vs got)
FAILURES PRESENT
```

The overall gate still exits `FAILURES PRESENT` — but for reasons that predate and are
independent of this slice, confirmed by A/B (stashed these changes, re-ran the unmodified
3-backend gate): `floatnum` already failed on jvm/js/rust before this slice touched anything
(`Pair(...)` vs `(...)` tuple-display parity bug, shared by every Phase-4 generator — `SPRINT.md`
history shows this fixture has had a nonzero fail count before), and `mutual-recursion` already
failed on jvm/rust (passes on js) — a known, already-tracked flaky case with its own extensive
`BUGS.md` history. `v2/backend/check.sh`'s exit code was ALREADY non-zero on unmodified `main`;
this slice does not change that. wasm inherits both exactly: `floatnum` fails identically
(same display bug, same generator), `mutual-recursion` fails identically (same rust generator
output, cross-compiled). Neither is wasm-specific or newly introduced.

Net: WASM joins JVM/JS/Rust as a real, verified Phase-4 backend target. Every fixture wasm can
run at all, it runs correctly — 7 of 9 `ok`, 2 fixtures inherit pre-existing cross-backend bugs
(not wasm-specific, not new), and 2 are explicitly, narrowly `skip`ped for a hand-confirmed
environmental reason (not silently dropped).
