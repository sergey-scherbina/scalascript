# ssc 2.0 kernel (K1)

The untyped Core IR evaluator — the only long-lived inner Scala
([`../specs/00-overview.md`](../specs/00-overview.md)). Reference semantics,
correctness over speed. A single file, [`Kernel.scala`](Kernel.scala):

- **Core IR ADTs** — `Term` (11 nodes), `Const`, `Value` (10 shapes) — per
  [`../specs/10-core-ir.md`](../specs/10-core-ir.md).
- **Reader** — lenient canonical-S-expr parser per
  [`../specs/12-ir-format.md`](../specs/12-ir-format.md) (whitespace + `;` comments).
- **Evaluator** — big-step rules (§4) on a `while`/trampoline loop so that calls in
  tail position run in **constant stack** (the TCO guarantee, invariant 7).
- **Primitives `δ`** — minimal set (`i.*`, `not`, `io.print`); widen as the seed/`sscc`
  need more (§5).

## Run

Uses [scala-cli](https://scala-cli.virtuslab.org/) (Scala 3.8.3). From `ssc2/`:

```bash
scala-cli run kernel -- run conformance/fact.coreir      # => 120
```

> Note: if your shell wraps `scala-cli` to append `--server=false`, that flag lands
> after `--` and leaks into program args. Use `command scala-cli … --server=false -- …`
> (bypasses the wrapper) or run the check script below (plain `bash`, no wrapper).

## Conformance

```bash
conformance/check.sh        # runs all fixtures, checks expected results
```

Acceptance set ([`../conformance/README.md`](../conformance/README.md)): `thunk`=42,
`fact`=120, `map`=`Cons(2, Cons(4, Cons(6, Nil)))`, `letrec`=true,
`tco`=500000500000 (1e6 tail calls, constant stack).

## Next

K-seed adds the `ssc₀ → Core IR` seed alongside this kernel (shares the `Term`/`Const`
ADT and adds a canonical S-expr *writer* = `coreir.encode`). See `../SPRINT.md`.
