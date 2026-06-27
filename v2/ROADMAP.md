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

Deferred (widen as the tower needs): rest of δ (`big.*`/`f.*`/string/bytes/`map.*`/`arr.*`/
`cell.*`/`io.*` files/`coreir.encode-decode` as a prim); ssc0 `import` (multi-file);
bare-`#prim` η-expansion; Array-env / binary `ir` (`v2-bin`) for speed.

## K2 — grow the tower (toward self-hosting)  ◀ current

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
- [ ] Toward the **fixpoint CI invariant**: a compiler written in ssc0 that compiles itself;
      `compile(self) == compile(self) via self` (`specs/20-bootstrap.md`). `coreir.encode` is
      the bootstrap-critical primitive — now in place.
- [ ] Richer types (HM/unification, products/sums) — tower growth, all in ssc0.

## K3 — Regrow the world (on the tower)

Incremental dogfood, each an ssc-compiled program: standard library; full type system;
effects/actors/async as libraries (lowered to ir, no kernel change); JVM/JS/WASM backends
as programs `ir → target`.

## Invariants across all milestones

- The kernel (the `ssc` binary) stays minimal and untyped: no type checker, no surface
  parser beyond ssc0, no effects/continuations, no target backend baked in. If it can be a
  program on the tower, it is.
- The ir is a real artifact: `ssc compile` emits it, `ssc run-ir` runs it; the canonical
  form makes a fixpoint/diff a byte compare.
