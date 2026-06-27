# ssc 2.0 — Sprint (active task queue)

Self-contained queue for the isolated **`v2/`** subproject (separate from the repo-root
`SPRINT.md`, which serves ssc 1.0). Milestone view: [`ROADMAP.md`](ROADMAP.md). Pipeline:
`ssc0 → ir → ssc(VM) → cpu`. Work each slice in its own worktree off `origin/main`.

## Done

- [x] Core IR **frozen v1** + `12-ir-format` + `15-ssc0` + `conformance/*.coreir` (K0,
      2026-06-25).
- [x] **runtime compiler `v2/ssc`** (2026-06-26) — one Scala 3 binary, `src/`: CoreIR
      (ir + reader/writer), Runtime (**compile-to-closures** VM + trampoline TCO + δ), Ssc0
      (lexer/parser/lower), Main (CLI `run`/`compile`/`run-ir`) + `./ssc` launcher. All
      modes green via `conformance/check.sh` (ssc0 examples + ir fixtures + `ssc0→ir` map-def
      reproduction; `tco` 1e6 deep in constant stack). Renamed `ssc2/ → v2/`. (Fused the
      previously-separate K1 VM and K-seed front into one binary.)

## Pending (K2 — grow the tower)

- [x] **ssct — the typed layer** (2026-06-27) — `lib/ssct.ssc0` (136 lines): a typed lambda
      calculus with `infer` (synthesis-only type checker) + erased `evalTerm`, **written in
      ssc0** (D1: types as an outer library, kernel stays untyped). `check` = type-check then
      run. Spec `40-typer-as-library.md`.
- [x] **ssct textual surface** (2026-06-27) — `lib/ssct-front.ssc0` (170 lines): a real
      **lexer + parser written in ssc0** for `.ssct` text → `Term`. Driver `bin/ssct.ssc0` +
      `v2/ssct` launcher: `./ssct examples/id.ssct` ⟶ text→lex→parse→typecheck→run, all ssc0.
      Examples id/cond (`Typed(...)`) + bad (`TypeError`) + conformance. **Kernel byte-for-byte
      unchanged** (still 851 lines). Deferred: erase-to-ir via `coreir.encode`, HM/unification.
- [x] **erase-to-ir + coreir.encode** (2026-06-27) — closes the loop `.ssct → ir → run-ir`.
      Kernel +`coreir.encode` prim (`IrEncode`: IR-as-Data tree → canonical bytecode; the ONE
      place the kernel grew, +~60 LOC → 911) + Main skips printing `Unit`. `lib/ssct-emit.ssc0`
      (~25 ssc0): `erase` (de Bruijn + drop types) + `emit`. `bin/ssctc.ssc0` + `v2/ssctc`
      launcher. `./ssctc id.ssct | ./ssc run-ir` ⟶ 42; conformance asserts exact bytecode +
      run-ir result. The typed program now runs on the real VM.
- [x] **delta-widen** (2026-06-26) — full `δ`: `big.*`, `f.*` + numeric conversions, string
      group (UTF-16 units), bytes, data reflection (`tagOf`/`arity`/`fieldAt`),
      `map.*`/`arr.*`/`cell.*` (Foreign mutable), I/O (`readFile`/`writeFile`/`env`/`exit`).
      +103 LOC (722→825). Examples greet/bigfact/mapdemo + conformance. Lexer fix: `#i->big`
      prim names. Still deferred: `coreir.encode/decode` (with self-hosting), `mathx.*`.
- [x] **ssc0-imports** (2026-06-26) — `import "path"` (flat global namespace) via `Loader`:
      relative resolution, load-once / cycle-safe, duplicate-def-name error. `lib/list.ssc0`
      + `examples/uselib.ssc0` (sum(range(100))=4950) + conformance.
- [x] **stdlib + interpreter** (2026-06-26) — `lib/list.ssc0` (foldl/foldr/map/filter/append/
      reverse/length/sum/head/range), `lib/option.ssc0`; `examples/pipeline.ssc0`
      (sum∘map∘filter∘range = 120) + `examples/calc.ssc0` — a real expression-language
      interpreter in ~20 lines of ssc0 (ADTs, match, env, let → 42). Lexer: `;` now an
      optional separator. Demonstrates the thesis: rich behaviour = small ssc0 on a tiny kernel.
- [x] **self-hosting — FIXPOINT REACHED** (2026-06-27) — `lib/ssc0c.ssc0`: the ssc0 compiler
      written in ssc0 (lex+parse+lower+emit). Differential invariant `ssc0c X == ssc compile X`
      holds byte-for-byte: M1 (fact/tco) + M2 (match/ctor/let/letrec/str → map/calc).
      **M4: `examples/ssc0c-self.ssc0` (lib + main), compiled by the Scala front then run on its
      OWN source, reproduces itself byte-for-byte (gen1==gen2==gen3, 20413 bytes) — a stable
      self-hosting fixpoint.** `bin/ssc0c.ssc0` + `v2/ssc0c` launcher (-Xss512m for deep non-tail
      recursion) + conformance + spec `20-bootstrap.md`. **Kernel: +0 lines (still 913).** Left:
      M3 (ssc0c `import` resolution → multi-file self-compile).

## Backlog

- [ ] bare-`#prim` η-expansion (needs a prim-arity table); Array-env for speed; `v2-bin`
      compact binary ir.
- [ ] `mathx.*` transcendental floats; structural map keys; `hash.sha256`. (Deferred opens,
      `10-core-ir.md §8`.)
- [ ] K3: stdlib, full type system, effects/actors as libraries, JVM/JS/WASM backends as
      ssc-compiled programs `ir → target`.
