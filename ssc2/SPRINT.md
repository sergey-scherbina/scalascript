# ssc 2.0 — Sprint (active task queue)

Self-contained queue for the **isolated `ssc2/` subproject** (kept separate from the repo
root `SPRINT.md`, which serves ssc 1.0). Milestone view: [`ROADMAP.md`](ROADMAP.md).
Architecture & decisions: [`specs/00-overview.md`](specs/00-overview.md). Work each slice in
its own worktree off `origin/main`, push when green, per repo-root `AGENTS.md`.

## Done (this design session, 2026-06-25)

- [x] Core IR **frozen v1** — `specs/10-core-ir.md` (10 values, 11 nodes, big-step semantics,
      TCO guarantee, primitive table). Decisions D1–D8 in `specs/00-overview.md`.
- [x] `specs/12-ir-format.md` — canonical S-expr serialization (v1).
- [x] `specs/15-ssc0.md` — `ssc₀` grammar + lowering (the seed contract).
- [x] `conformance/*.coreir` — fact, map, thunk, letrec, tco (the K1 acceptance set).

## Pending

- [ ] **k1-evaluator** — the kernel, in Scala (scala-cli, single dir under `ssc2/kernel/`).
      Core IR ADT + lenient S-expr reader (`12-ir-format.md`) + big-step evaluator with a
      **trampoline for guaranteed TCO** (invariant 7) + primitive table `δ`
      (`10-core-ir.md §5`) + `ssc2 run <file.coreir>` CLI. Acceptance: all five
      `conformance/*.coreir` produce their expected results (`conformance/README.md`),
      including `tco.coreir` in constant stack. Start with: ADT → reader → evaluator (App,
      If, Match, Let, LetRec, Ctor, Prim) → the i.* / io.* / data prims the fixtures need →
      widen δ. Keep it minimal — correctness over speed.

- [ ] **k-seed** — the permanent seed, in Scala, alongside the kernel. Tokenizer → parser
      for `ssc₀` (`specs/15-ssc0.md`) → name resolution (de Bruijn locals, named globals) →
      desugar → `coreir.encode` (canonical S-expr). No type checking. Acceptance: the
      worked example in `15-ssc0.md` lowers to exactly `conformance/map.coreir`'s `map` def;
      `ssc2 build <f.ssc0>` → `.coreir` that the K1 evaluator runs.
      *Depends on k1-evaluator (shares the Core IR ADT + encoder).*

- [ ] **k2-sscc** — write the real compiler `sscc` (Markdown+Scala lexer/parser → typer →
      erasure → lowering) **in `ssc₀`**. Reach the fixpoint CI invariant
      (`seed(sscc) == sscc(sscc)`, `specs/20-bootstrap.md`). Large; slice further when
      starting. *Depends on k-seed.*

## Backlog (after bootstrap)

- [ ] `specs/20-bootstrap.md` — write up the seed + fixpoint-as-CI mechanics (can land with
      k-seed).
- [ ] `specs/30-erasure-lowering.md`, `specs/40-typer-as-library.md` — for K3.
- [ ] K3: stdlib, type-system growth, effects/actors as libraries, JVM/JS/WASM backends as
      `.ssc` programs. (See `ROADMAP.md`.)
- [ ] `mathx.*` transcendental float primitives; structural map keys; `hash.sha256`;
      `v2-bin` compact IR encoding. (Deferred opens from `10-core-ir.md §8` / `12 §open`.)
