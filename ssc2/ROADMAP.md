# ssc 2.0 — Roadmap

Bootstrap milestones for the clean-room self-hosting build. Architecture and decisions:
[`specs/00-overview.md`](specs/00-overview.md). The bottom layer (kernel + seed) is the
permanent trusted base; everything above it is ScalaScript compiled to Core IR.

## K0 — Freeze Core IR  ◀ current

Pin the untyped kernel language before any code.

- [x] `specs/10-core-ir.md` v0 — value domain, 11 nodes, big-step semantics, primitive
      table, program envelope, conformance sketch.
- [ ] Review/decide the §8 open points that affect the node set (notably: TCO guarantee
      vs. outer trampoline lowering) before declaring the node set frozen.
- [ ] `specs/12-ir-format.md` — canonical serialization (kernel-owned; what `coreir.encode`
      emits). Needed before the seed can target a stable format.
- [ ] Hand-write the conformance programs (factorial, list map, small match) as serialized
      Core IR fixtures — the acceptance set for K1.

## K1 — Scala evaluator (the kernel)

The only long-lived inner Scala. Target: a few thousand lines, correctness over speed.

- [ ] Own sbt build under `ssc2/` (zero dependency on the `ssc 1.0` tree).
- [ ] Core IR data types + loader (`coreir.decode` / serialized-IR reader).
- [ ] Tree-walking evaluator implementing every §4 rule.
- [ ] Primitive table (`δ`) per §5: int/bool/str/bytes/data/map/array/cell/io/coreir.
- [ ] CLI: `ssc2 run <program.coreir>`.
- [ ] Green on the K0 conformance fixtures.

## K-seed — `ssc₀` + permanent seed

The permanent minimal Scala front door into Core IR.

- [ ] `specs/15-ssc0.md` — `ssc₀` grammar (λ, `let`/`letrec`, ADTs, `match`, literals,
      primitive calls, named recursion, simple modules) + `ssc₀ → Core IR` lowering. This
      *is* the seed's contract.
- [ ] Seed (Scala): tokenizer → parser → name resolution (de Bruijn for locals, names for
      globals) → desugar → `coreir.encode`. No type checking. Hundreds of lines, not
      thousands.
- [ ] `ssc2 build <prog.ssc0>` → `<prog.coreir>`.

## K2 — Self-hosting `sscc` + fixpoint

- [ ] Write `sscc` (the real compiler: Markdown+Scala lexer/parser, typer, type erasure,
      lowering to Core IR) **in `ssc₀`**.
- [ ] `seed(sscc.ssc0) = sscc.coreir`; run on the evaluator.
- [ ] **Fixpoint CI invariant:** `run sscc.coreir on sscc.ssc0 == sscc.coreir`
      (`specs/20-bootstrap.md`).
- [ ] First real `.ssc` examples compile and run end to end.

## K3 — Regrow the world (in ScalaScript)

Incremental, now fully dogfood. Each is an `.ssc` library/backend compiled by `sscc`:

- [ ] Standard library.
- [ ] Type system growth (`specs/40-typer-as-library.md`): type classes, given/using, etc.
- [ ] Effects / actors / async as libraries (lowered to CPS/Data — no kernel changes).
- [ ] Backends: JVM, JS, WASM — each an `.ssc` program `Core IR → target`.
- [ ] Optional: self-typed dogfood compiler (rewrite `sscc` in full typed ScalaScript,
      type-checking itself; 2-stage build). Deferred per decision D6.

## Invariants that hold across all milestones

- The kernel never gains: a type checker, the surface parser, effects/continuations, a
  JIT, or any target backend. If it can be written in ScalaScript, it is.
- The system is always rebuildable from source: `{Scala kernel + Scala seed + sources}`.
  No checked-in binary IR blob in the trusted path.
- The fixpoint diff between the seed's lowering and `sscc`'s self-lowering is permanent CI.
