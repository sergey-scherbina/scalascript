# v2 Full Compatibility Plan

Goal: v2 achieves full language compatibility + performance parity with v1.
After this plan is complete, Phase 3 (CLI switch) can proceed safely.

Spec for Phase 2 continuation. See also: `specs/v1-to-v2-migration.md`.

> Status (2026-07-05): drafted 2026-07-03, committed 2026-07-05. Track 1 (T1.1-T1.3)
> has substantial in-flight work on branch `feature/v2-frontend-bridge` (unmerged).
> T5.1 in progress (`feature/v2-ssc1c-globals-bug`). Track 3 overlaps SPRINT's
> "v2 bench performance" items (v2-recursion-opt / v2-pattern-match-opt).

---

## Track 1 — v1 IrExpr → Core IR (foundation)

Everything else depends on this. Without it, only the basic ssc1c subset works.

**Why**: v1 `IrExpr` is a named-variable AST (VarRef, Lambda, Block, Call, MatchTree,
Handle/Perform/Resume, ExternCall). The v2 Core IR is de Bruijn-indexed untyped lambda
calculus. A bridge module converts between them, reusing the entire v1 frontend (parser +
typer + linker + macro expansion) and plugging into the v2 VM and backends.

### T1.1 — `FrontendBridge`: IrExpr → Term

New sbt module: `v2/frontend-bridge/`. Depends on `ir` (v1 lang) + `v2Core`.

**Scope tracker**: a `List[String]` (newest-first) of in-scope variable names.
`VarRef(name)` → `Local(scope.indexOf(name))` if found, else `Global(name)`.
Each `Lambda(params, body)` pushes params in reverse (last param = Local(0)).
Each `Block` let-binding for a `val`/`def` pushes the bound name.

**Node mapping** (full table):

| v1 IrExpr | Core IR Term | Notes |
|---|---|---|
| `Lit(IntL(n))` | `Lit(CInt(n))` | |
| `Lit(DoubleL(d))` | `Lit(CFloat(d))` | |
| `Lit(StringL(s))` | `Lit(CStr(s))` | |
| `Lit(BoolL(b))` | `Lit(CBool(b))` | |
| `Lit(UnitL)` | `Lit(CUnit)` | |
| `VarRef(name)` | `Local(i)` or `Global(name)` | scope lookup |
| `Lambda(ps, body)` | `Lam(ps.length, body')` | push ps reversed |
| `Block(stmts)` | chain of `Let` | see below |
| `Call(target, args)` | `App(Global(target.qn), args')` | |
| `TailCall(target, args)` | `App(Global(target.qn), args')` | same |
| `Apply(fn, args)` | `App(fn', args')` | |
| `Select(qual, name)` | `App(Global(name), [qual'])` | method call |
| `If(c, t, e)` | `If(c', t', e'.getOrElse(Lit(CUnit)))` | |
| `ExternCall(name, args)` | `Prim(name.value, args')` | name stays |
| `MatchTree(scrut, root)` | `Match(scrut', arms, default)` | see below |
| `Perform(eff, op, args)` | `App(Global("_eff_perform"), [Lit(CStr(op)), ...args'])` | |
| `Handle(body, cases, ret)` | `App(Global("_eff_handle"), ...)` | see below |
| `Resume(k, value)` | `App(Global(k.qn), [value'])` | |
| `MacroImpl(...)` | assert — must be expanded | Linker expands before this |
| `Unsupported(syn)` | compilation error | |

**Block lowering**: A `Block(stmts)` where stmts are a mix of val/def declarations
and expressions. Strategy:
- An expression statement: wrap as `Let([expr'], body_of_rest)`, binding `"_blk_"` 
- A val/def statement: `Let([rhs'], rest)` binding the name (push name to scope)
- Recursive def: `LetRec([Lam(...)], rest)` binding the name
- Final expression is the block's value (no Let wrapper)

To distinguish val/def from expr in `Block(stmts: List[IrExpr])`: stmts at block level
that are `Call` to a special binding form, or check what v1 interpreter does.
INVESTIGATION NEEDED: read `v1/runtime/backend/interpreter/src/main/scala/scalascript/`
to understand how the interpreter builds the frame from `Block` stmts.

**MatchTree lowering**: `DecisionNode` is a decision tree:
- `Switch(cases, default)` → `Match(scrut, arms, default)`
- `Leaf(action)` → `action'`
- `PatCtor(ctor, fields)` → arm tag = ctor.qn.simpleName, arity = fields.length

**Handle/Perform lowering** (effects via v2 shift/reset):
```
Perform(eff, op, args) →
  App(Global("_eff_perform"), [Lit(CStr(s"${eff.value}.$op")), ...args'])

Handle(body, cases, ret) →
  App(Global("_eff_handle"), [
    Lam(0, body'),           // thunk: the computation
    Lam(2, handlerBody),     // handler: (op_name, resume) => dispatch on cases
    Lam(1, ret.body')        // return map: result => ret.body'
  ])
```
The handler body dispatches on the operation name string via Match/If-chain.

### T1.2 — NormalizedModule → Program

`NormalizedModule.sections` → `List[Section]` → `Section.content` → `List[Content]`.
Each `Content.CodeBlock(source, body)` contributes `List[IrExpr]`.

Top-level defs (IrExpr nodes that represent function/val definitions at module scope)
become `Def(name, body)` entries in `Program.defs`.
The module entry point is a synthetic `App(Global("main"), Nil)` or unit.

### T1.3 — CLI wiring

Add `--v2` flag to `ssc run`:
```
ssc run --v2 foo.ssc
```
Pipeline: v1 frontend (parse+type+link) → `NormalizedModule` → `FrontendBridge.convert`
→ `Program` (Core IR) → `Compiler.compile` (v2) → `Runtime.run` (v2 VM).

As a stepping stone, also add:
```
ssc compile --emit-coreir foo.ssc   # outputs Core IR text to stdout
```
This lets the v2 backends (JVM/JS/Rust) consume any .ssc file immediately.

### T1.4 — Examples verification

After T1.3, run all `examples/` files through `ssc run --v2`. Target:
- First milestone: `examples/hello.ssc`, basic programs
- Second: effects examples
- Third: all examples

---

## Track 2 — Plugin parity

### T2.1 — BlockForm effects in v2

The 7 core effects (Logger, State, Retry, Cache, Env, Random, Clock) implement `BlockForm`
in v1 plugins. These need v2 shift/reset wiring.

Each `BlockForm` provides:
```scala
def run(body: Value => Value, handler: EffectHandler): Value
```
This IS shift/reset: `body` is the computation, `handler` is the effect handler.

Bridge: wrap each BlockForm as a v2 `_eff_handle`-compatible handler registered in
`V2PluginRegistry`. The v2 side calls `_eff_run(body, handler)` where:
- `body` calls `_eff_perform` for each effect op
- `handler` dispatches to the BlockForm's op handlers

### T2.2 — HTTP / SQL / WebSocket intrinsics

These are simple `IntrinsicImpl` (not BlockForm). Should work via existing `PluginBridge`
once T1.3 is in place (the programs can call ExternCall → Prim → V2PluginRegistry).
Verify end-to-end: HTTP GET through v2 runtime.

### T2.3 — Actors (spike)

Actors require concurrent execution + message passing. Two options:
- (A) Each actor = Java VirtualThread (Project Loom) + its own v2 VM instance
- (B) Cooperative scheduling via Core IR coroutines (requires new IR nodes)

Option A is simpler. Spike: implement actor spawn/send/receive via VirtualThread
in the v2 plugin bridge. Gate: an actor ping-pong program works under v2.

---

## Track 3 — Performance parity

### T3.1 — Baseline benchmarks

Run identical programs through both pipelines on the same machine.
Produce a table: `program | v1 interp ms | v2 VM ms | v2 JVM ms | v1 JVM ms`.
Identify the largest gaps.

### T3.2 — v2 VM hot paths

Already done: IrWhile, LongCellV, FastCode.
Remaining candidates (investigate after T3.1 numbers):
- HOF fast paths: foldLeft, map, filter without closure allocation
- String concat specialization
- Collection construction (List, Vector) without intermediate boxing

### T3.3 — v2 JVM backend quality

The generated Scala code uses `lazy val` + closures. Profile generated code vs
v1 JVM backend's output on the same programs. Optimize the most-impacted patterns.
Target: within 2× of v1 JVM backend on bench corpus.

### T3.4 — v2 Rust backend ownership

Replace `Rc<RefCell<V>>` closures with direct ownership where the closure is called
exactly once (not shared). Profile first to identify hot paths.
Target: within 1.5× of v1 Rust backend.

---

## Track 4 — Full compatibility verification

### T4.1 — All examples

Run every file in `examples/` under `ssc run --v2`. Fix each miss. Done-when: 0 failures.

### T4.2 — Stdlib plugins

Run `v1/runtime/std/*.ssc` plugin tests under v2. Known blocker: BlockForm effects (T2.1).
Done-when: all plugin tests pass.

### T4.3 — Full application test

Pick `busi` or a payments demo app. Run end-to-end under v2 (HTTP server, SQL, auth).
This is the final gate before Phase 3.

### T4.4 — Conformance suite

Run `sbt backendConformance/test` targeting v2 pipeline.
Done-when: conformance score v2 ≥ v1.

---

## Track 5 — ssc1c fixes

### T5.1 — @count/@sum bug

In `v2/lib/ssc1-lower.ssc0`, when lowering `var x = 0; while(...)`, ssc1c generates
`@count`/`@sum` global refs not in the defs list. Fix: emit `lcell.new` + `lcell.get`/
`lcell.set` instead of @-prefixed globals.
Affected: `bool-predicate`, `mutual-recursion` bench programs.

---

## Execution order

```
T1.1 (IrExpr→CoreIR)   ← START HERE — everything else unblocked by this
T1.2 (Module→Program)  ← immediately after T1.1
T5.1 (ssc1c @-bug)     ← quick fix, parallel with T1

T1.3 (CLI wiring)      ← after T1.1+T1.2
T2.1 (BlockForm)       ← parallel with T1.3
T2.2 (HTTP/SQL)        ← after T1.3

T1.4 (examples v1)     ← after T1.3
T3.1 (benchmarks)      ← after T1.3

T3.2-T3.4 (perf)       ← after T3.1
T2.3 (actors)          ← after T2.1, spike

T4.1-T4.4 (verify)     ← last, after all tracks
```

---

## Done-when (Phase 3 gate)

- [ ] `ssc run --v2 foo.ssc` works for ALL examples/ programs
- [ ] All plugin categories work through v2 (logger, state, http, sql, actors)
- [ ] v2 VM performance within 2× of v1 interpreter on bench corpus
- [ ] v2 JVM backend within 2× of v1 JVM backend
- [ ] v2 Rust backend within 1.5× of v1 Rust backend  
- [ ] Conformance score v2 ≥ v1
- [ ] One full application runs end-to-end under v2
