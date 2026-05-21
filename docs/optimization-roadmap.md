# ScalaScript — Optimization and Modularity Roadmap

> Planning document. Items progress to MILESTONES.md when scheduled.
> Last updated: 2026-05-21.

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

### 3a. AST cache between watch cycles

**Effort: ~3 days. Impact: watch/REPL latency.**

`ssc watch` re-parses the entire file on every change.  The parser
(scalameta) dominates startup time for large `.ssc` files.

Proposed: cache `(path, mtime, content-hash) → ParsedModule`.  On
file change, check if only code-block N changed (AST diff at section
granularity); if only one section changed, re-evaluate that section
and its dependents instead of the full module.

Works naturally with the section-based evaluation order already in
the interpreter.

### 3b. Incremental type-checking

**Effort: ~1 week. Impact: REPL and large files.**

When only one code block changes, the typer should re-check only that
block and its transitive dependents (functions it calls, values it
uses).  The existing `Typer` already has an `errors` list and a strict
mode; an incremental mode would reuse the existing typed environment
for unchanged blocks.

### 3c. JvmGen artifact caching at `ssc run-jvm` ✓ Landed (v1.35, 2026-05-21)

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
| ✓ | JvmGen artifact caching (3c) | Done v1.35 |
| 1 | `ssc check` (6c) | 1 day, high CI value |
| 2 | AST cache / watch latency (3a) | Biggest developer-experience win |
| 3 | JS tree-shaking (4a) | Bundle size matters for browser |
| 4 | Library modularity (5) | Enables embedding use cases |
| 5 | v1.16 Restartable errors | Language feature, unblocked |
| 6 | Numeric specialization (2b) | Benchmark-driven, do after profiling |
| 7 | Incremental type-checking (3b) | Depends on AST cache (3a) |
