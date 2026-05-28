# ScalaScript — Optimization and Modularity Roadmap

> Planning document. Items progress to MILESTONES.md when scheduled.
> Last updated: 2026-05-28.

---

## 1. Deferred items now unblocked

### v1.16 — Restartable errors

Was blocked until v2.0; **block lifted 2026-05-19** (v2.0 landed).

Common Lisp condition-system style restartable handlers.  A handler
can choose to resume the suspended computation at the throw point with
a replacement value rather than only aborting or rewrapping.

```scalascript
val config = restartable {
  case FileNotFound(path) => Restart.useDefault
  case PermissionDenied(p) => Restart.retry(sudo(p))
} {
  parseConfig(readFile("/etc/app.conf"))
}
```

Maps directly onto the v1.9 coroutine primitive: `throw e` becomes
`suspend(ErrorTag(e))`; the handler stack catches the tag and resumes
with a replacement value.  Estimated effort: ~1 week.

### v1.12 — Algebraic effects feasibility study — BLOCKED

**Do not start.** Remains blocked by explicit decision — not enough
concrete demand to justify the research cost right now.  Revisit when
a real user asks for typed effect rows.

Background: investigates OCaml-5 / Koka-style typed effect rows on top
of the existing typer.  The question is whether `(Async | Random)[A]`
can be tracked without a typer rewrite.  Not a shipping feature — would
produce a design doc + prototype + go/no-go decision.

---

## 2. Runtime optimizations

### 2a. Project Loom — virtual threads ✓ Landed (pre-v1.33)

**Status: complete.** Virtual threads are used everywhere in production:

- `JdkServerBackend` — `newThreadPerTaskExecutor(Thread.ofVirtual()...)` for
  per-connection dispatch; 10 000 WS connections need no OS threads.
- `WsProxy` — `Thread.ofVirtual()` for each proxied connection.
- `AsyncRuntime` — `newVirtualThreadPerTaskExecutor()` for `Async.parallel`.
- `TlsContextBuilder.vthreadPool()` — shared factory used by `ProxyRuntime`.

Remaining `newSingleThreadExecutor` calls are intentional (serial
interpreter handler dispatch; JDK HttpServer accept loop; heartbeat
scheduler) and must stay as platform threads.

### 2b. Value boxing — numeric specialization

**Effort: ~1 week. Impact: medium (hot loops).**

`IntV(n)`, `DoubleV(d)`, `BoolV(b)` are all heap-allocated.  In tight
loops (Dataset parallel, Async.parallel, tail-recursive sum) this
creates GC pressure.

Options:
- Specialize `Computation[A]` for `Int` and `Double` paths (avoids
  most allocations in arithmetic-heavy code).
- Pool common small `IntV` instances (like Java's `Integer.valueOf`
  cache for −128..127).
- Annotate hot dispatch paths with `@specialized`.

Benchmark target: fib(28) and sum(1e6) should improve ≥ 20 % over
the current already-optimized baseline (3-8× faster than original).

### 2c. Interpreter.scala split — lazy capability loading ✓ Landed (v1.33, 2026-05-21)

**Phase 1** (file split): `Interpreter.scala` split from ~2900 → ~600 lines;
actor/cluster scheduler extracted to `ActorInterp.scala` (self-type trait).

**Phase 2** (lazy plugin loading): `BackendRegistry.inProcess` (ServiceLoader
scan for HTTP/SQL/OAuth/etc. plugins) deferred until first use via a
`_pluginsLoaded` flag + `ensurePluginsLoaded()`.  Triggered from:
- `EvalRuntime` `Term.Name` miss
- `StatRuntime` `extern def` case (covers child interpreters for import files)

`globalOrStub` companion proxies resolve at call time rather than storing dead
stubs at construction time.  Cold-start: **0.35 s → 0.31 s** for `hello.ssc`.

The deeper per-file split (separate `HttpRuntime.scala`, `ActorRuntime.scala`,
etc. each self-registering) remains a future option if the residual ~40 ms
becomes a target.

---

## 3. Compiler optimizations

### 3a. Phase-timing instrumentation (`--Ystats`) ✓ Landed (2026-05-28)

**Status: complete.**

`CompileStats` accumulator wraps each build phase with a nanosecond
wall-clock timer.  Activated by the global `--Ystats` flag; prints a
table (ms + %) to stderr at the end of each build.

Phases instrumented in `incrementalBuildCommand` and `buildArtifactsInto`:
`discover`, `parse`, `normalize`, `interface`, `write-scim`, `write-scir`,
`jvm-codegen`, `js-codegen`.  `CompileStats` is zero-overhead when the
flag is off.

Usage: `ssc --Ystats build --incremental runtime/std/`

This is the **prerequisite measurement layer** for all further compiler
optimizations — run it before and after any change and attach the delta.

### 3b. JMH benchmark suite for compiler hot paths

**Effort: ~2 days. Impact: measurement only (enables data-driven opt).**

Without microbenchmarks, optimizing the typer is guesswork.  Add a
`langCoreBench` sbt subproject using `sbt-jmh`:

- `ParserBench` — parse `runtime/std/actors.ssc` (25 KB) end-to-end.
- `TyperBench` — typecheck the parsed module.
- `UnifyBench` — unify pairs of deeply nested `SType` (stress
  `SType.subst` and `Unifier.solve`); use `-prof gc` to measure
  allocation rate.

Baseline numbers establish the "before" for each optimization below.

Key files: `build.sbt` (new project), `lang/core/src/main/scala/…`
hot paths at `Types.scala:140–169` (subst/freeVars) and
`Typer.scala:594` (inferType).

### 3c. Typer allocation hot path

**Effort: ~1 week. Impact: high for complex modules.**

`SType.subst` and `SType.freeVars` (`Types.scala:140–169`) rebuild the
entire type tree on every `Unifier.solve` step (`Types.scala:243–244`).

Three concrete fixes in priority order:

1. **Hash-cons primitive `SType.Named`** — intern `Named(name, Nil)` for
   all arg-free types via a `concurrent.Map[String, Named]`.  The module-
   level vals for `Int`, `Boolean`, etc. (`Types.scala:171–186`) are already
   correct; extend the pattern.

2. **`IntMap` for unifier substitution** — replace `Map[Int, SType]` with
   `scala.collection.immutable.IntMap`; unboxes the `Int` key on every
   lookup.  Drop-in replacement in `subst` + `Unifier.solve`.

3. **`freeVars` accumulator** — replace per-node `Set.flatMap` with a
   `mutable.BitSet` accumulator passed by reference.

Structural fix (do after 1–3 are measured): switch to path-compressed
union-find for type-var unification so substitutions are never applied
eagerly.

### 3d. Parser double-parse under `package:` (quick win)

**Effort: ~1 day. Impact: any project that uses `package:` front-matter.**

`Parser.wrapSectionInPackage` (`Parser.scala:74–110`) passes every fenced
code block through scalameta twice when `package:` is set: once raw, once
wrapped.  Restructure preprocessing so scalameta sees the wrapped form once.

### 3e. `Scope.lookup` and `Typer.createPrelude` (quick wins)

**Effort: ~1 day. Impact: typer hot path.**

- `Scope.lookup` (`Types.scala:218–226`) recurses via `Option.orElse`;
  replace with a `while` loop.
- `Typer.createPrelude()` is rebuilt on every `Typer.typeCheck` call
  (`Typer.scala:59`); cache as a `lazy val` shared across modules in one
  build.

### 3f. Parallel compilation

**Effort: ~1 week. Impact: cold build time (multi-core machines).**

The pipeline is single-threaded.  The module graph already has a
topological sort — modules in the same rank are independent.

1. Parallel file reads and parse (`Main.buildArtifactsInto`) — wrap in
   `Future.traverse` over a bounded `ForkJoinPool`.  Guard `ParseCache`
   with `ConcurrentHashMap`.

2. Parallel typing of modules in the same topological rank
   (`ModuleGraph`).  `Typer` instances are per-module; the only shared
   state is the cross-module symbol table built by `Linker` after typing.

### 3g. JvmGen artifact caching at `ssc run-jvm` ✓ Landed (v1.35, 2026-05-21)

**Status: complete.** `ssc run-jvm` now writes a `.scjvm` artifact to
`.ssc-artifacts/` after each compile and reads it on the next run when
the source SHA-256 matches, bypassing JvmGen codegen entirely.
Uses `JvmGen.generate` directly (same path as `compile-jvm`).

---

## 4. Generated code optimizations

### 4a. JS tree-shaking

**Effort: ~1 week. Impact: bundle size.**

The JsGen runtime preamble (~500 lines) is emitted into every output
file.  The v2.0 linker already deduplicates across modules.  Next step:
analyze which runtime helpers are actually called by the module and
emit only those.

Candidate groupings to split: effects runtime, actor runtime, MCP
runtime, Dataset runtime.  A module with no actors should not include
`_actorSpawn` et al.

### 4b. Constant folding in JsGen / JvmGen

**Effort: ~3 days. Impact: output quality.**

Expressions like `val x = 1 + 2` should emit `val x = 3` rather than
`1 + 2`.  Similarly `if true then a else b` → `a`.  The typer already
evaluates `compiletime.constValue` — extend this to the codegen phase
for common arithmetic and boolean constants.

---

## 5. Library modularity

### Current structure

The sbt build already has 14 modules.  The dependency graph is correct
for the tools.  Three problems for *library users*:

**Problem 1 — No thin `scalascript-core` artifact.**
A project that wants to embed the interpreter pulls in all backends
through `backendInterpreter`'s transitive deps.

Proposed: publish a `scalascript-core` artifact containing only
`ir + backendSpi + core`.  Users who want scripting without HTTP/WS/
actors depend on `scalascript-interpreter-minimal` (core eval only).

**Problem 2 — `backendInterpreter` depends on `backendJs` and `backendJvm`.**
This is a test-scope dependency that leaked into the main graph.
Fix: move the JVM-backend test dependency to `% Test` scope only.

**Problem 3 — `Interpreter.scala` monolith makes tree-shaking impossible.**
Even if you depend only on the interpreter module, you load all runtimes.
Fixed by item 2c above (split into per-capability files).

### Proposed published artifacts

| Artifact | Contains | Use case |
|----------|----------|----------|
| `scalascript-ir` | IR types + codecs | Tool builders |
| `scalascript-core` | Parser + Typer + AST | Linters, analyzers |
| `scalascript-interpreter` | Core eval, no HTTP | Embedded scripting |
| `scalascript-server` | HTTP/WS/Actors | Server-side apps |
| `scalascript-all` | Everything | Full CLI use |

---

## 6. New ideas not yet in MILESTONES

### 6a. `ssc profile file.ssc`

Built-in profiler: counts invocations per function and wall time per
section.  Output: top-N hotspots, call graph, allocation rate.
Uses the existing `bench/` infrastructure but runs inline rather than
requiring an external harness.  Effort: ~3 days.

### 6b. REPL: web-aware mode

`ssc repl` that lets you `mount GET /hello { req => ... }` interactively,
inspect the live route table with `:routes`, and test endpoints with
`:http GET /hello`.  Builds on the hot-reload infrastructure.
Effort: ~3 days.

### 6c. `ssc check` — standalone type checker

Run the typer without interpreting.  Exit-code semantics for CI:
```bash
ssc check src/**/*.ssc   # type-check all files, print diagnostics
```
Already partially exists via `ssc check-with-iface`.  Generalizing to
standalone (without interface files) is straightforward.  Effort: ~1 day.

---

## Priority order (suggested)

| Priority | Item | Why |
|----------|------|-----|
| ✓ | Project Loom (2a) | Done pre-v1.33 |
| ✓ | Interpreter split (2c) | Done v1.33 |
| ✓ | JvmGen artifact caching (3g) | Done v1.35 |
| ✓ | Phase-timing `--Ystats` (3a) | Done 2026-05-28 — measurement foundation |
| 1 | JMH benchmark suite (3b) | 2 days; prerequisite for data-driven typer opt |
| 2 | `ssc check` (6c) | 1 day, high CI value |
| 3 | Typer allocation — hash-cons + IntMap (3c) | Expected largest compiler speedup; validate with 3b first |
| 4 | Parser double-parse fix (3d) | 1 day quick win for `package:` projects |
| 5 | `Scope.lookup` + prelude cache (3e) | 1 day, safe wins |
| 6 | JS tree-shaking (4a) | Bundle size matters for browser |
| 7 | Parallel compilation (3f) | Needs 3c done first (verify no shared state) |
| 8 | Library modularity (5) | Enables embedding use cases |
| 9 | v1.16 Restartable errors | Language feature, unblocked |
| 10 | Numeric specialization (2b) | Benchmark-driven, do after profiling |
| 11 | Incremental type-checking (3h) | Depends on typer allocation (3c) |
