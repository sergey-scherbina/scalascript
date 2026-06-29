# ssc 2.0 — Roadmap

Clean-room self-hosting build, grounded on one binary `v2/ssc` (front + compiler +
runtime). Architecture & decisions: [`specs/00-overview.md`](specs/00-overview.md).
Pipeline: `ssc0 → ir → ssc(VM) → cpu`.

## K0 — Freeze Core IR  ✅ COMPLETE (2026-06-25)

- [x] `specs/10-core-ir.md` **frozen v1** — 10 values, 11 nodes, big-step semantics, TCO,
      primitive table. Decisions D1–D8 in `specs/00-overview.md`.
- [x] `specs/12-ir-format.md` — canonical S-expr bytecode (v1).
- [x] `specs/15-ssc0.md` — `ssc₀` grammar + lowering.
- [x] `conformance/*.coreir` fixtures.

## ssc — the runtime compiler (front + VM)  ✅ COMPLETE (2026-06-26)

One binary `v2/ssc`, scala-cli + Scala 3.8.3, ~4 source files under `src/`, zero deps on
the `ssc 1.0` tree. Fuses what was scoped as K1 (VM) + K-seed (ssc0 front). Decisions D9–D11.

- [x] **ir layer** (`CoreIR.scala`): Core IR ADTs + lenient S-expr reader + canonical writer
      (= `coreir.encode`).
- [x] **VM** (`Runtime.scala`): **compile-to-closures** (the JIT — each node compiled once
      into a closure) + trampoline driver (proper tail calls, constant stack) + δ.
- [x] **front** (`Ssc0.scala`): ssc0 lexer + parser + lower (name resolution to de Bruijn).
- [x] **CLI** (`Main.scala`): `run` (ssc0→ir→run), `compile` (ssc0→ir), `run-ir` (ir→run);
      `./ssc` launcher.
- [x] Green: `conformance/check.sh` — all three modes, ssc0 examples + ir fixtures + the
      `ssc0 → ir` map-def reproduction, incl. `tco` at 1e6 depth in constant stack.

Resolved on the tower after the freeze: δ was widened, ssc0 imports became multi-file,
bare-`#prim` values are eta-expanded by `ssc0c`, and `v2-bin` provides a compact binary IR
tooling format. Still deliberately open: `coreir.decode` as a kernel primitive, Array-env
for VM speed, and WASM until a toolchain is available.

## K2 — grow the tower (toward self-hosting)  ✅ COMPLETE (2026-06-27)

Each layer is a program compiled/run by the layer below: `ssc0 → ssc.1 → ssct → …`.

- [x] δ-widened (full primitive set) + ssc0 `import`s + an ssc0 stdlib (2026-06-26).
- [x] **ssct — the typed layer** (2026-06-27, `lib/ssct.ssc0`, 136 lines): a typed lambda
      calculus type checker + erased evaluator, **written in ssc0** — types as an outer
      library, kernel stays untyped (D1). Spec `40-typer-as-library.md`. The thesis, live.
- [x] **textual `.ssct` surface** (2026-06-27, `lib/ssct-front.ssc0`, 170 lines): a real
      lexer + parser in ssc0; `./ssct <file.ssct>`.
- [x] **erase-to-ir + `coreir.encode`** (2026-06-27): the loop closes — `.ssct → typecheck →
      erase (ssc0) → ir bytecode → `ssc run-ir` (VM) → cpu`. Kernel gained exactly one
      primitive (`coreir.encode`). `./ssctc <file.ssct>` emits bytecode.
- [x] **SELF-HOSTING — fixpoint REACHED** (2026-06-27): `ssc0c` (`lib/ssc0c.ssc0`), the ssc0
      compiler written in ssc0, compiled by the Scala front and run on its **own source**,
      reproduces itself byte-for-byte (gen1==gen2==gen3). Differential invariant `ssc0c X ==
      ssc compile X` holds on fact/tco/map/calc. `specs/20-bootstrap.md`. **Kernel: +0.**
- [x] **Richer types / HM layer** — `ssct-hm` grew Algorithm W, let-polymorphism,
      products/tuples, records, sums/user ADTs, pattern matching, qualified-type-style
      dictionary passing for Num/Ord/user methods, effect rows, and typed resumes for
      single-op effects — all in ssc0, Kernel +0.

## K3 — Regrow the world (on the tower)  ✅ NON-WASM COMPLETE through K45 (2026-06-29)

Incremental dogfood, each an ssc-compiled program: standard library; full type system;
effects/actors/async as libraries (lowered to ir, no kernel change); JVM/JS/WASM backends
as programs `ir → target`.

- [x] **Stdlib breadth** — list/string/option/stream helpers, structural `mapx`/`set`,
      Either/Result combinators, SHA-256, and full pure float math/rounding/trig/log library.
- [x] **Typed language breadth** — `ssct-hm` is the main typed surface and compiles real programs
      to VM, JS, and native Rust; `examples/hm-json.hm` is the current whole-language showcase.
- [x] **Backends** — VM, JS, and native Rust are TCO-correct and covered by conformance.
- [x] **Effects / async / actors** — algebraic effects, cooperative async, and core actor
      semantics are libraries on the tower, not kernel features.
- [ ] **WASM** — blocked on missing `wabt`/`wasmtime`/`wasmer` or a Rust WASM target. Reuse the
      Rust backend when `rustup target add wasm32-wasip1` is available, or build a binary wasm
      emitter plus runtime.

## Invariants across all milestones

- The kernel (the `ssc` binary) stays minimal and untyped: no type checker, no surface
  parser beyond ssc0, no effects/continuations, no target backend baked in. If it can be a
  program on the tower, it is.
- The ir is a real artifact: `ssc compile` emits it, `ssc run-ir` runs it; the canonical
  form makes a fixpoint/diff a byte compare.
