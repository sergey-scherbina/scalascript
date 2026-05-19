# v2.0 — Separate Compilation Plan

Status: **Stage 1 in progress** (plan committed, exploration done)

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
