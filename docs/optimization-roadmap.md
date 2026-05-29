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

### 2b. Value boxing — numeric specialization ✓ Partially landed (2026-05-28)

**Tactical pooling landed.** Full specialization deferred.

**Landed:**
- `IntV` pool widened to `[-2048..16383]` (18 432 entries) via `Value.intV(n)` smart
  constructor — covers HTTP status codes 200–500 and loop counters up to 16 K.
- `DoubleV` singletons for `0.0` and `1.0` via `Value.doubleV(d)`.
- All hot-path call sites updated: `EvalRuntime` literal evaluation,
  `DispatchRuntime` range generation, `DatasetRuntime` sum/count,
  `BuiltinsRuntime` tabulate index.

**Still open (deeper specialization):**
- Specialize `Computation[A]` for `Int` and `Double` paths to avoid
  most allocations in arithmetic-heavy code.
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

### 3b. JMH benchmark suite for compiler hot paths ✓ Landed (2026-05-28)

**Status: complete.**

New `compilerBench` sbt subproject at `lang/core-bench` with three benchmarks:

- `ParserBench` — parse `runtime/std/actors.ssc` (547 lines) end-to-end.
- `TyperBench` — type-check the parsed actors module via `Typer.typeCheck`.
- `UnifyBench` — unify deeply-nested `Function/Tuple` constraints at
  depths 3 and 8, stressing `SType.subst` and the occurs check.

Run: `sbt "compilerBench/Jmh/run -prof gc -wi 3 -i 5"`

### 3c. Typer allocation hot path ✓ Landed (2026-05-28)

**Status: complete.**

Four concrete fixes landed in this commit batch:

1. **Hash-cons `SType.Named(name, Nil)`** — `SType.named0(name)` uses a
   `ConcurrentHashMap` intern cache; `primitiveOrNamed` and `checkStat`
   (class/object/enum/opaque) now share a single object per distinct type
   name rather than allocating per use-site.

2. **`IntMap` for unifier substitution** — `Unifier.unify` now uses
   `scala.collection.immutable.IntMap` for the `subst` accumulator,
   unboxing the `Int` key on every lookup.  Drop-in replacement.

3. **`subst` empty-map guard** — `if m.isEmpty then return this` skips
   all tree traversal when no type variables have been bound yet.

4. **`Named(_, Nil) → this` in subst** — zero-arg types can never contain
   type variables; skip recursion entirely.

5. **`containsFreeVar` short-circuit occurs check** — replaces
   `t.freeVars.contains(id)` (builds full `Set[Int]`) with
   `t.containsFreeVar(id)` (returns as soon as the var is found).

### 3d. Parser double-parse under `package:` ✓ Landed (2026-05-28)

**Status: complete.**

`extractSections` now takes a `skipInitialParse: Boolean` parameter.
When `package:` is set, `wrapSectionInPackage` always discards the
first parse; we now skip it entirely by deferring the scalameta call
to the wrap step.

### 3e. `Scope.lookup` iterative ✓ Landed (2026-05-28)

**Status: complete.**

`Scope.lookup` and `Scope.lookupType` replaced recursive `Option.orElse`
chains with `while` loops, eliminating `Option` allocation per scope hop.

Note: `Typer.createPrelude` cache was deferred — the prelude `Scope` is
mutated by `predeclareModuleNames` in the no-importedInterfaces path,
preventing safe sharing.  Requires splitting module scope from prelude.

### 3f. Parallel compilation ✓ Phase 1 landed (2026-05-28)

**Phase 1 (parallel stale-check + parse) landed.**

`incrementalBuildCommand` now fires a `CompletableFuture` per module node
before the sequential build loop.  Each future computes:
- staleness flags (`isStale`, `isJvmStale`, `isJsStale`) — pure I/O
- for stale modules: reads source bytes and parses via `Parser.parse`

The sequential loop consumes pre-computed results, eliminating parse
latency from the critical path on multi-core machines.  `Parser.parse`
is pure (no global state) so the parallel phase is safe.

**Blocked (not yet landed):**
- Full parallel typing within topological rank — blocked by
  `System.setErr` global mutation inside the per-module build loop;
  each module redirects stderr during compilation, making parallel
  execution unsafe.  Requires extracting per-module output capture.
- Parallel modules in the same topological rank (`ModuleGraph`).
  `Typer` instances are per-module; the only shared state is the
  cross-module symbol table built by `Linker` after typing.

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

### 4b. Constant folding in JsGen / JvmGen ✅ DONE (opt-const-fold, 2026-05-29)

Expressions like `val x = 1 + 2` now emit `const x = 3` in JS output
and fold in the JvmGen effectful CPS path (avoiding `_binOp` dispatch).
`if(true/false)` with literal condition eliminates the dead branch.
Unary `-`/`!` on literals also fold. 36 new tests.

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
| ✓ | JMH benchmark suite (3b) | Done 2026-05-28 — ParserBench + TyperBench + UnifyBench |
| ✓ | Typer allocation — hash-cons + IntMap (3c) | Done 2026-05-28 — 5 optimizations landed |
| ✓ | Parser double-parse fix (3d) | Done 2026-05-28 — skipInitialParse flag |
| ✓ | `Scope.lookup` iterative (3e) | Done 2026-05-28 — while loop replaces Option chain |
| ✓ | Parallel stale-check + parse (3f phase 1) | Done 2026-05-28 — CompletableFuture pre-parse |
| ✓ | IntV/DoubleV pool + smart constructors (2b partial) | Done 2026-05-28 — pool [-2048..16383], DoubleV singletons |
| 1 | `ssc check` (6c) | 1 day, high CI value |
| 6 | JS tree-shaking (4a) | Bundle size matters for browser |
| 7 | Parallel typing within topological rank (3f phase 2) | Blocked by System.setErr global mutation |
| 8 | Library modularity (5) | Enables embedding use cases |
| 9 | v1.16 Restartable errors | Language feature, unblocked |
| 10 | Numeric specialization full (2b) | Specialize Computation[A], @specialized |
| 11 | Incremental type-checking (3h) | Depends on typer allocation (3c) |
