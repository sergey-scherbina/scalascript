# ssc 2.0 — `ssc`, a runtime compiler

`v2/` is a **clean-room redesign** of ScalaScript, built from a minimal kernel upward and
bootstrapped on itself (dogfood). **Isolated**: its own build (scala-cli), zero dependency
on the `ssc 1.0` tree under the repo root. The old implementation is never touched or
reused. Architecture & decisions: [`specs/00-overview.md`](specs/00-overview.md).

## The one program: `v2/ssc`

A single self-sufficient Scala 3 binary that fuses **front + compiler + runtime**. It reads
code, **generates a program** (compiles), and runs it — a *runtime compiler*. The pipeline:

```
ssc0  ──►  ir  ──►  ssc (VM)  ──►  cpu
source     bytecode  compile-to-closures + execute
```

- **ir** (Core IR) is a real first-class **bytecode** — a tiny untyped lambda calculus
  ([`specs/10-core-ir.md`](specs/10-core-ir.md)), with a canonical text form
  ([`specs/12-ir-format.md`](specs/12-ir-format.md)).
- **ssc0** is the thin readable source language ([`specs/15-ssc0.md`](specs/15-ssc0.md)); the
  front (`ssc0 → ir`) is built into the binary, but `ir` is also a standalone artifact —
  hence the duality: `ssc` runs source *or* bytecode.
- **ssc** is the VM. Per the **JIT philosophy** (once we know what a node is, we emit a
  closure that does exactly that — no re-dispatch on the AST at run time), it compiles `ir`
  to a tree of closures and runs it with proper tail calls (constant-stack TCO).

### Three modes

```bash
./ssc run     examples/fact.ssc0     # ssc0 → ir → run            => 120
./ssc compile examples/fact.ssc0     # ssc0 → ir (canonical bytecode, stdout)
./ssc run-ir  conformance/fact.coreir # ir → run (pure VM)        => 120
```

(`./ssc` is a thin scala-cli launcher over [`src/`](src). First run compiles the VM.)

## The tower

`ssc` is the irreducible foundation. On top of it, as a tower, each layer is built using
the one below: **`ssc0 → ssc.1 → ssct (typed) → … → full ScalaScript`**, until v2
reproduces everything ssc 1.0 has — but grounded on a tiny, auditable kernel. The type
checker is itself an *outer* layer (a program), not a kernel feature; the kernel stays
untyped (see [`specs/00-overview.md`](specs/00-overview.md)).

## Layout

```
v2/
  ssc                 launcher (./ssc run|compile|run-ir …)
  src/                the binary: CoreIR (ir + reader/writer), Runtime (VM + compiler +
                      δ), Ssc0 (front: lexer/parser/lower), Main (CLI)
  examples/*.ssc0     runnable ssc0 programs
  conformance/        ir fixtures + check.sh (builds one jar, runs all modes)
  specs/              10-core-ir · 12-ir-format · 15-ssc0 · 00-overview
```

## Status

The runtime compiler works end to end: ssc0 → ir → run, ir → run, and ssc0 → ir, all green
(`conformance/check.sh`), including a 1e6-deep tail loop in constant stack. Next: grow the
tower (a typed layer) and widen the primitive set. Roadmap: [`ROADMAP.md`](ROADMAP.md);
queue: [`SPRINT.md`](SPRINT.md).
