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

### Stage 1 — Plan + artifact format (this commit)

Iterations:
1. [x] Plan doc (`docs/separate-compilation-plan.md`)
2. [ ] Define `.scim` / `.scir` wire format in `ir/Ir.scala` — add
       `ModuleInterface` case class + `ArtifactEnvelope` with magic
       number + version guard.  No behaviour change.
3. [ ] Add `ArtifactVersion` object to SPI — magic bytes + version string.

Acceptance criteria:
- `sbt compile` clean.
- No behaviour change to any existing command.

### Stage 2 — Interface extraction (`.scim` writer)

Iterations:
1. [ ] `ModuleInterface` extractor: walk a `NormalizedModule` and produce
       the interface — exported name → type (currently `SType.Any` until the
       typer provides better info), extern def signatures, imports re-exported
       by the module.
2. [ ] `ssc emit-interface <file>` command — compiles the file, extracts the
       interface, serialises to `.scim` JSON, prints to stdout or writes to
       `<file>.scim`.
3. [ ] Version guard on write: magic bytes + compiler version embedded in the
       envelope.

Acceptance criteria:
- `ssc emit-interface examples/hello.ssc` produces valid JSON.
- Round-trip: deserialise the JSON back into `ModuleInterface`; no data lost.

### Stage 3 — IR artifact (`.scir` writer)

Iterations:
1. [ ] `ssc emit-ir <file>` command — normalises the module, writes
       `NormalizedModule` JSON to `<file>.scir` with the artifact envelope.
2. [ ] ABI version guard on read: reject artifacts with mismatched version.
3. [ ] Content-hash in the envelope — SHA-256 of the `.ssc` source bytes,
       written into the envelope for staleness checking.

Acceptance criteria:
- Round-trip: `emit-ir` + deserialise produces an identical `NormalizedModule`.
- Version mismatch prints a clear error and exits non-zero.

### Stage 4 — Typer consuming interfaces

Iterations:
1. [ ] `InterfaceScope` — a `Scope` that is populated from a `ModuleInterface`
       rather than from source; provides `lookup(name)` for cross-module refs.
2. [ ] `Typer` accepts `Map[String, InterfaceScope]` for pre-compiled
       dependencies; falls back to full parse for uncompiled deps (backward
       compat).
3. [ ] `ssc check --use-interface <dir>` — loads `.scim` files from `<dir>`,
       builds `InterfaceScope` map, type-checks the source module against it.

Acceptance criteria:
- `ssc check` with pre-compiled interface for a dependency passes without
  re-parsing that dependency's source.
- Missing interface falls back to full parse (no regression).

### Stage 5 — Linker pass

Iterations:
1. [ ] `Linker` object: given a list of `(NormalizedModule, ModuleInterface)`
       pairs, resolves cross-module `VarRef` / `Call` nodes to fully-qualified
       `SymbolRef` using FQNs from the manifest `package:` prefix.
2. [ ] Symbol mangling: cross-module symbol names are mangled to their
       fully-qualified form (`org_example_ui_Card`) before handing to the
       backend.  `ssc emit-js` uses mangled names when two modules export the
       same short name.
3. [ ] `ssc link <artifact-dir> -o <output.scir>` — collects `.scir` files,
       links them, writes the merged `NormalizedModule` as a single `.scir`.

Acceptance criteria:
- Two modules both exporting `Card` link cleanly; the JS output has two
  distinct mangled identifiers.
- `ssc link` round-trips through the ABI version guard.

### Stage 6 — Build orchestration (`ssc build --incremental`)

Iterations:
1. [ ] `ModuleGraph` — reads all `.ssc` files in a directory tree, extracts
       `Import` edges, produces a topological order.
2. [ ] Staleness check: compare SHA-256 of each `.ssc` source with the hash
       stored in its `.scir` envelope; mark stale modules for recompilation.
3. [ ] `ssc build --incremental <dir>` — walks the graph in topological order,
       skips up-to-date modules, compiles stale ones.  Default `ssc build`
       unchanged (still full pass).

Acceptance criteria:
- Second `ssc build --incremental` on an unchanged tree compiles zero modules.
- Changing one source triggers recompilation of that module and its dependents
  only.
- Existing `ssc build` (without `--incremental`) unchanged.

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
