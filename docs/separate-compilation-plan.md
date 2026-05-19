# v2.0 — Separate Compilation Plan

Status: **Stages 1–6 landed; Stages 5.1–5.7 polish landed; Phase 2 (bytecode
linker MVP) in progress**.  ~430 tests green (418 core + 14+ CLI subprocess).
See "Progress log" at the bottom for full landing notes.

## Overview

Every `ssc compile` today parses, types, normalises, and emits the entire
reachable module-tree in one pass.  v2.0 makes each `.ssc` file compile
independently into a pair of artifacts:

- `.scim` — **module interface**: exported types, `extern def` signatures,
  typeclass instances, capability declarations.  No bodies.
- `.scir` — **module IR**: body IR in JSON, the `NormalizedModule` JSON
  representation of the module body.

Consumers link against pre-compiled `.scim` artifacts instead of re-parsing
every source.  The full-tree single-pass path is **not removed** — it stays
as the default for `ssc compile` / `ssc run` etc.

Analogues: Haskell `.hi` + `.o`, OCaml `.cmi` + `.cmo`, Scala `.tasty` + `.class`.

---

## Codebase map (discovered during exploration)

| File | Role |
|---|---|
| `ir/src/main/scala/scalascript/ir/Ir.scala` | `NormalizedModule` + all IR types, already `derives ReadWriter` for JSON round-trip |
| `core/src/main/scala/scalascript/parser/Parser.scala` | Parses `.ssc`; extracts `package:` front-matter (v1.18) into `Manifest.pkg` |
| `core/src/main/scala/scalascript/transform/Normalize.scala` | `AST → NormalizedModule`; calls `SourceLanguageRegistry` for embedded fences |
| `core/src/main/scala/scalascript/typer/Typer.scala` | Single-module typer; walks AST, builds `Scope`; today sees only one module at a time |
| `core/src/main/scala/scalascript/imports/ImportResolver.scala` | Resolves Markdown-import links to local `os.Path`; dep: scheme; URL caching |
| `core/src/main/scala/scalascript/plugin/SscpkgManifest.scala` | `manifest.yaml` inside `.sscpkg`; template for `.scim` envelope |
| `cli/src/main/scala/scalascript/cli/Main.scala` | All CLI commands; `compileViaBackend` is the single-pass entry point |
| `backend-spi/src/main/scala/scalascript/backend/spi/Backend.scala` | `Backend.compile(ir: NormalizedModule, opts)` — linker hands this a fully-linked module |

Key insight: `NormalizedModule` already derives `ReadWriter`, so JSON
serialisation is free.  The `.scir` artifact is essentially
`upickle.default.write(normalizedModule)`.  The `.scim` artifact is a
filtered subset.

---

## Module boundary decision

**One `.ssc` file = one module.**

Rationale:
- Files already carry `package:` front-matter from v1.18, giving each file
  a stable fully-qualified name (e.g. `org.example.ui`).
- The existing `ImportResolver` already resolves cross-file links — those
  become cross-module artifact references.
- A directory-as-module design would require a `package.yaml` convention and
  a build manifest format we don't have.  Post-v2.0 concern.

---

## Stages and iterations

### Stage 1 — Plan + artifact format  [DONE]

Iterations:
1. [x] Plan doc (`docs/separate-compilation-plan.md`)
2. [x] Define `.scim` / `.scir` wire format in `ir/Ir.scala` — added
       `ArtifactVersion`, `ModuleInterface`, `ExportedSymbol`, `InstanceDecl`,
       `CapabilityDecl`, `ModuleIrArtifact` — all `derives ReadWriter`.
3. [x] `ArtifactVersion` object with magic string (`SSCART`) + ABI version (`2.0`).

Acceptance criteria: met — `sbt compile` clean, no behaviour change.

### Stage 2 — Interface extraction (`.scim` writer)  [DONE]

Iterations:
1. [x] `InterfaceExtractor` in `core/artifact/` — runs Typer to collect
       `DefSummary` entries, scans AST for `given` instances + `extern def`,
       detects capabilities by text-scanning code blocks.
2. [x] `ssc emit-interface <file>` command — writes `.scim` JSON or prints
       to stdout (`-o -`).
3. [x] ABI version guard embedded in every `.scim` envelope.

Smoke test: `ssc emit-interface examples/data-types.ssc -o -` produces
valid JSON with correct exports, `ssc emit-interface std/either.ssc -o -`
produces FQN `std_either_*` for package `std.either`.

### Stage 3 — IR artifact (`.scir` writer)  [DONE]

Iterations:
1. [x] `ArtifactIO.writeIr` / `readIr` — wraps `NormalizedModule` JSON in the
       ABI envelope (magic + version + source SHA-256 + pkg + moduleName).
2. [x] `ssc emit-ir <file>` command — writes `.scir` JSON or prints to stdout.
3. [x] ABI version guard on read via `ArtifactIO.readIr`.

### Stage 4 — Typer consuming interfaces  [DONE]

Iterations:
1. [x] `InterfaceScope` — populates a `Scope` from a `ModuleInterface`;
       `parseSType` converts stored type strings back to `SType`.
2. [x] `Typer(importedInterfaces)` constructor — merges `InterfaceScope`s
       between the prelude and the module's own top-level scope.
3. [x] `ssc check-with-iface [--iface-dir <dir>] <file.ssc>` — loads all
       `.scim` files from the dir, builds interface scopes, type-checks.
       Falls back to standard check if no `--iface-dir`.

### Stage 5 — Linker pass  [DONE]

Iterations:
1. [x] `Linker` object — builds symbol table from all `ModuleInterface`s,
       merges `NormalizedModule` sections in dep order, rewrites cross-module
       `VarRef` nodes in `CodeBlock.body` IrExpr trees (no-op until Stage 3+
       populates body nodes; structure correct for future use).
2. [x] `Linker.mangle(pkg, name)` + `Linker.detectCollisions` for FQN
       mangling and cross-module collision reporting.
3. [x] `ssc link <artifact-dir> [-o <output.scir>] [--backend <id>]` — loads
       `.scim`+`.scir` pairs, links, and either writes merged `.scir` or
       compiles+executes via the specified backend.

Smoke test: `ssc link /tmp/ssc-test-artifacts` linked 1 module + executed
via interpreter backend — full output verified.

### Stage 6 — Build orchestration (`ssc build --incremental`)  [DONE]

Iterations:
1. [x] `ModuleGraph` — Kahn's algorithm topo-sort of `.ssc` files; imports
       extracted by parsing front-matter; cycle detection.
2. [x] `ModuleGraph.isStale` — compares SHA-256 of source with stored hash
       in `.scim` artifact; returns `true` if artifact absent or hash mismatch.
3. [x] `ssc build --incremental <src-dir> [--artifact-dir <dir>]` — walks
       the graph in topo order, skips up-to-date modules, compiles stale ones.
       Default `ssc build` (static-site generator) unchanged.

Smoke tests:
- First run: compiles 1 module, writes `data-types.scim` + `data-types.scir`.
- Second run: skips 1 module (0 compiled, 1 up-to-date, 0 failed).
- `ssc link` on artifacts: linked + executed via interpreter, full output verified.

---

## Post-MVP rounds (Stage 5.1 – 5.7)

After the 6-stage MVP landed, seven rounds of polish closed real production
gaps that the MVP didn't address.  Full chronology is in `MILESTONES.md`
under "v2.0 — Separate compilation of modules"; the highlights:

- **Stage 5.1** — `Normalize` emits real `IrExpr` bodies (was always `Nil`),
  unblocking `Linker.rewriteExpr`.  Real type parser for `InterfaceScope`
  (generics / tuples / function types / qualified paths).  AST-based
  capability + extern detection in `InterfaceExtractor` (was grep).
  Comprehensive test suite (61 tests) for the artifact pipeline.
- **Stage 5.2** — Real type inference for top-level `Defn.Def/Val/Class`
  signatures (was `SType.Any` everywhere).  Per-module JVM artifact format
  (`.scjvm` = SSCART envelope + emitted Scala source + SHA-256), plus
  `ssc compile-jvm`, `ssc link --backend jvm`, `ssc build --incremental
  --backend jvm`.
- **Stage 5.3** — `Select` chain folding in `Linker` (`a.bar` → `VarRef("a_bar")`
  when `a` is a known package).  Composite SHA-256 for linked `.scir`.
- **Stage 5.4** — `parseSType`/`SType.show` handle union `A | B`,
  intersection `A & B`, higher-kinded `F[_]`.  `Typer` strict mode +
  `ssc check-with-iface` rejects undefined references.  JS backend
  incremental output: `.scjs` artifact, `ssc compile-js`, `link --backend js`.
- **Stage 5.5** — `compile-jvm`/`compile-js` auto-resolve imports via
  Kahn topo-sort with cycle traces.  Linker drops duplicate top-level
  defs after concat (robust against conditional runtimes).  Strict mode
  flags `Select(importedModule, missingMember)`.  `ssc info <artifact>`
  inspector for all 4 formats (with `--json`).
- **Stage 5.6** — Battle-test against real `std/` modules (10 cases against
  `std/eq.ssc`, `std/dsl/*`, `std/parsing/*`, etc.) — surfaced 4 concrete
  bugs.  JvmGen effect-runtime fixes (bare-name val-rhs rewrite,
  pattern-only `blocksUseActors`, `serve`/`onWebSocket` overload collapse).
  Deep `a.b.c` `Select` chains in strict mode (recursive `QualResult`
  ADT, single diagnostic at first break).  `ssc deps <file.ssc> [--graph]`.
- **Stage 5.7** — Closed 4 of the 7 known production gaps:
  - **Anonymous given identity**: `given Eq[Int] with { ... }` now produces
    a stable witness name `given_Eq_Int` and FQN `pkg_given_Eq_Int`.
    Affects every typeclass instance in std/.
  - **Structured parse diagnostics**: `Content.CodeBlock.parseError`
    carries `(message, line, column, snippet)`; all 8 CLI surfaces print
    a 3-line snippet with `^` caret instead of "Failed to parse" opaque.
  - **YAML front-matter diagnostic**: SnakeYAML `ScannerException` now
    wraps with offending line + caret + targeted hint for unquoted-colon
    string values.
  - **Extractor populates `ExportedSymbol.nested`** (depth cap 3):
    deep `Select` chains through real `.scim` artifacts now reject
    unknown members strictly instead of falling permissive.

---

## Phase 2 — Bytecode-level linker (in progress)

The Stage 5.* polish made the source-level pipeline production-ready for
~half of std/.  Phase 2 replaces source-level textual concat + scala-cli
compile-at-link with **per-module compiled `.class` files packed in a JAR**.

MVP scope (current iteration):

- Extend `ModuleJvmArtifact` with optional `classBundle: Option[String]`
  (base64-encoded ZIP of `.class` files).  Backward-compatible default.
- `ssc compile-jvm --bytecode` invokes `scala-cli compile` internally,
  packs the produced `.class` files into `classBundle`.  Auto-resolve
  transitively propagates the flag and wires extracted deps onto each
  inner scala-cli invocation's classpath.
- `ssc link --backend jvm --bytecode <dir> -o out.jar` extracts each
  `.scjvm`'s `classBundle`, dedups by FQN, packs into a single JAR via
  `java.util.jar.JarOutputStream`.
- Errors loudly if any input lacks `classBundle` (requires `--bytecode`
  recompile) or if `scala-cli` is missing at compile time.

Out of scope for this MVP (later phases):
- Refactor JvmGen to emit module-only Scala (no preamble) so per-module
  bytecode is minimal.  Today's MVP packs the full preamble per module,
  then dedups at link.
- Share the runtime preamble as a separate `.scjvm-runtime` artifact.
- TASTy-based dep resolution (re-use Scala 3's incremental machinery).

---

## Open questions

1. **Interface granularity**: should typeclass instances be included in `.scim`?
   Currently the typer annotates everything as `SType.Any`; richer types would
   make interface-based checking more useful.  Defer: start with name-only
   interfaces; extend when the typer is richer.

2. **Incremental backend linkage**: once we have per-module `.scir` files,
   can backends consume them directly (one `.js` per module, bundled by a
   separate tool)?  That's a further step beyond the linker pass — defer to
   a v2.1 sprint.

3. **Circular imports**: the existing `ImportResolver` has no cycle detection.
   The `ModuleGraph` in Stage 6 will detect cycles and error clearly — but the
   single-pass path still accepts them silently.  Track as a latent issue.

4. **`package:` as module identity**: two files with the same `package:` value
   are today accepted; separate compilation would treat them as the same module
   and the second would overwrite the first.  Add a collision check in Stage 6.

5. **Subprocess backends**: `SubprocessBackend` forwards a `NormalizedModule`
   over the wire protocol.  A pre-linked `.scir` is already in the right shape.
   No change needed for Stage 5; confirm after the linker lands.

---

## Constraints (non-negotiable)

- Every existing `ssc` command keeps working — no breaking changes to the
  single-pass path.
- The `.scim` / `.scir` envelope has a version guard from day one.
- Symbol mangling uses fully-qualified names from `package:` declarations.
- `--incremental` is opt-in; default `ssc compile` / `ssc build` unchanged.
- Do not run `sbt test` — user runs tests manually.  Use `sbt compile` to
  verify each iteration.

---

## Progress log

| Date | Stage | Iteration | Notes |
|---|---|---|---|
| 2026-05-19 | 1 | 1 | Plan doc written; codebase explored |
| 2026-05-19 | 1 | 2-3 | Artifact format types added to `ir/Ir.scala` |
| 2026-05-19 | 2 | 1-3 | `InterfaceExtractor` + `ArtifactIO` + `ssc emit-interface` |
| 2026-05-19 | 3 | 1-3 | `ArtifactIO.writeIr/readIr` + `ssc emit-ir` |
| 2026-05-19 | 4 | 1-3 | `InterfaceScope` + `Typer` extension + `ssc check-with-iface` |
| 2026-05-19 | 5 | 1-3 | `Linker` + `ssc link` |
| 2026-05-19 | 6 | 1-3 | `ModuleGraph` + `ssc build --incremental` |
| 2026-05-19 | 5.1 | – | Real `IrExpr` bodies; real type parser; AST-based extractor heuristics; 61 artifact tests |
| 2026-05-19 | 5.2 | – | Real top-level type inference; `.scjvm` + `ssc compile-jvm`/`link --backend jvm` |
| 2026-05-19 | 5.3 | – | `Select` chain FQN fold; composite linked sourceHash |
| 2026-05-19 | 5.4 | – | union/intersection/higher-kinded types; Typer strict mode; JS incremental (`.scjs`) |
| 2026-05-19 | 5.5 | – | auto-resolve imports; linker dedup duplicate defs; strict Select; `ssc info` |
| 2026-05-19 | 5.6 | – | battle-test real std/; JvmGen effect-runtime fixes; deep `a.b.c` Select; `ssc deps` |
| 2026-05-19 | 5.7 | – | anonymous given identity; structured parse diagnostics; YAML hint; `ExportedSymbol.nested` populated |
| 2026-05-19 | Phase 2 | MVP | per-module `.class` bytecode in `.scjvm`; `link --backend jvm --bytecode` packs JAR (in progress) |
