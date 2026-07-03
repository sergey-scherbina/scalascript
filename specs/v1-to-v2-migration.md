# ssc v1 → v2 migration plan

## Goal

Make v2 the default runtime for `ssc`. Three phases: restructure, readiness, switch.

---

## Phase 1 — Restructure: extract v1 into `v1/`

### What moves

| From (current) | To |
|---|---|
| `lang/` | `v1/lang/` |
| `runtime/` | `v1/runtime/` |
| `tools/` | `v1/tools/` |

Everything else stays at root: `backend/` (kafka/postgres/...), `frontend/`, `gov/`,
`payments/`, `mcp/`, `registry/`, `bench/`, `v2/`, `specs/`, coordination files.

### What changes in `build.sbt`

Only the `.in(file("..."))` paths — mechanical prefix addition:
- `.in(file("lang/..."))` → `.in(file("v1/lang/..."))`
- `.in(file("runtime/..."))` → `.in(file("v1/runtime/..."))`
- `.in(file("tools/..."))` → `.in(file("v1/tools/..."))`

sbt module **names** (`backendInterpreter`, `core`, `ir`, `pluginApi`, ...) and all
`dependsOn` references do NOT change. Domain libraries compile unchanged.

### Done-when

`sbt compile` green, `ssc run hello.ssc` works, all plugins load correctly.

---

## Phase 2 — Readiness: v2 covers everything v1 covers

v2 must be fully production-ready before Phase 3. "Production-ready" means: every
feature available through `ssc` works through the v2 pipeline with equal or better
performance. Phase 3 is a one-line switch; no feature work happens in Phase 3.

### 2a — v2 as an sbt module

- Add `v2/` to root `build.sbt` as `lazy val v2Core = project.in(file("v2/src"))...`
- `v2/src/` stays as-is (frozen kernel: `Runtime.scala`, `Compiler.scala`, `CoreIR.scala`)
- Build tooling: `sbt v2Core/compile` replaces `scala-cli` for CI; bench script updated

### 2b — Plugin SPI: shift/reset instead of trampoline

v1 `BackendSpi.runWithHandler` is a manual delimited continuation (trampoline).
v2 has shift/reset built in (`_eff_perform` / `_eff_handle` / `_eff_run`).

Design: a new `v2/runtime/backend/spi/` module implements `BackendSpi` via v2 effects:

```
// plugin calls perform(op, args) → this is shift (captures continuation k)
// v2 effect handler wraps the computation → this is reset
// handler(op, args) returns result → k(result) resumes computation
```

No trampoline emulation — shift/reset IS the native mechanism in v2. The shim:
1. Takes an `ssc1c`-compiled IR of the computation
2. Wraps it in a v2 effect `reset` block
3. When `perform(op, args)` fires (shift), calls the plugin's actual handler
4. Passes the result back through the v2 continuation

Value translation: `SpiValue` ↔ v2 `Value` adapter (thin layer, scalars are identity
after value-data unification).

### 2c — New v2 backends (Core IR based)

v1 backends operate on v1 AST. v2 backends operate on Core IR (simpler, lambda calculus).
New backends live in `v2/backend/jvm/`, `v2/backend/js/`, `v2/backend/rust/`.

#### v2 JVM backend

Core IR → JVM bytecode (via ASM) or Core IR → Java source → javac.
JVM JIT takes over naturally once bytecode is generated — no manual JIT needed.
Performance target: match or exceed v1 JVM backend benchmarks.

Key features to carry over from v1:
- Closure compilation (already in v2 `Compiler.scala`)
- Primitive fast-paths (`LongCellV`, `FastCode` — already done in v2 VM)
- Effect handling via shift/reset (2b above)

#### v2 JS backend

Core IR → JavaScript (ES2020+, arrow functions, closures).
Simpler than v1 JS backend since Core IR is already in closure-normal form.
Target: same output semantics, similar performance to v1 JS backend.

#### v2 Rust backend

Core IR → Rust source.
v1 Rust backend generates idiomatic Rust with ownership; v2 can start with
a simpler GC-via-Rc model and refine.
All v1 Rust features must be supported (effects via shift/reset → Rust trait objects).

#### WASM

Toolchain-gated (no wabt/wasmtime in standard env). Port after JVM/JS/Rust are done.

### 2d — Full feature checklist (gate for Phase 3)

- [ ] All 31 bench corpus programs pass on each backend (JVM, JS, Rust)
- [ ] All plugin categories work through v2 SPI (logger, random, clock, state, cache,
      retry, env, http, sql, actors, mcp, crypto, payments)  
- [ ] `ssc compile --backend jvm/js/rust foo.ssc` produces correct output
- [ ] `ssc run foo.ssc` through v2 VM: correct + ≥ v1 performance on bench corpus
- [ ] Domain libraries (payments, crypto, blockchain, frontend) compile and run
- [ ] Conformance suite green (all backends)
- [ ] TUI (`ssc tui`) works through v2

---

## Phase 3 — Switch: v2 is the default

When Phase 2 checklist is 100% green:

1. `v1/tools/cli` entry point changed: invoke v2 pipeline instead of v1
2. `ssc run` → v2 VM by default; `ssc compile --backend X` → v2 backend X
3. v1 pipeline available via `ssc --v1 run` for migration period (remove later)
4. Update install scripts, docker images, docs

This phase is purely mechanical — no new features, only wiring.

---

## What stays in v1 permanently

v1 backends (`v1/runtime/backend/jvm`, `/js`, `/rust`) remain as-is. They are not
removed, just superseded. If a v2 backend has a regression, `ssc --v1` is the escape
hatch. Once v2 is stable and all users are migrated, v1 can be archived.

---

## Execution order

```
Phase 1 (restructure)     — one session, mechanical, low risk
Phase 2a (sbt module)     — one session
Phase 2b (SPI shift/reset) — one session, key design work
Phase 2c JVM backend      — largest slice, multiple sessions
Phase 2c JS backend       — medium slice
Phase 2c Rust backend     — medium slice
Phase 2d checklist        — verification pass
Phase 3 (switch)          — one session, one-line change + wiring
```
