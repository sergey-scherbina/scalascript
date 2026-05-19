# v2.0 — Separate Compilation Plan

Status: **Feature-complete — Stages 1–6, polish rounds 5.1–5.7, and Phases
2 / 3 / 3+ / 4 / 5 all landed.**  v2.0 separate compilation is
production-ready for the planned scope.  ~190 artifact unit tests +
~110 CLI subprocess smoke tests + LSP tests + Typer/Normalize coverage,
all green.  See "Progress log" at the bottom for full landing notes; the
user-facing tutorial is [`docs/v2.0-getting-started.md`](v2.0-getting-started.md);
the wire-format spec is [`docs/v2.0-artifact-format.md`](v2.0-artifact-format.md).

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

(All three above landed in the subsequent Phase 2 deep refactor + Phase 3.)

---

## Phase 3 — Operational hardening (landed)

After Phase 2's deep JvmGen split, Phase 3 addressed perf + safety:

- **TASTy-direct scalac driver** — `dotty.tools.dotc.Driver` invoked
  in-process; warm per-module compile time drops from 1410 ms to 119 ms
  (~11.8× speedup).  `cli/Scala3Driver` wraps the API; scala-cli kept
  as fallback via `SSC_EXTERNAL_SCALA_CLI=1`.
- **ABI compatibility test suite** — 64 forensic tests pin the
  compatibility contract: strict-equality on envelope, additive-friendly
  on payload.  See `docs/v2.0-artifact-format.md`.
- **`ssc verify <dir>`** — operational health-check.  Validates envelope,
  ABI version, sourceHash shape, cross-refs, runtime coverage.
  `--strict` adds source-freshness check; `--json` for CI.

## Phase 3+ — Tooling round (landed)

- **Source maps** (opt-in `--source-map`): JVM Option B (sidecar
  `.ssc.scala` file next to JAR); JS V3 source maps via hand-rolled
  VLQ writer + `//# sourceMappingURL=` reference.
- **Per-section incremental** (opt-in `--section-cache`):
  cumulative-hash chain on `sectionHashes: Map[String, String]`
  field across all 4 artifact types.  Edit last section → only
  it is stale; edit first → full cascade.
- **LSP server** (new `ssc lsp` command): minimal Language Server
  over stdio.  `initialize/didOpen/didChange/didClose/definition/
  hover/publishDiagnostics`.  Cross-module resolution via `.scim`.

## Phase 4 — Honesty pass (landed)

The Phase 3+ "complete" line hid documented `TODO`s in code.  All five
follow-ups landed:

- **LSP positional accuracy** — `ExportedSymbol.definitionLine` +
  `.definitionColumn` populated by `InterfaceExtractor` from scalameta
  positions; `Content.CodeBlock.lineOffset` populated by `Parser` from
  CommonMark line numbers.  LSP cross-module go-to-definition stops
  returning `(0, 0)`; multi-block hover/definition no longer reports
  block-local lines as if blocks start at 1.
- **JVM source maps Option A** — JSR-45 SMAP injected via ASM into
  `SourceDebugExtension` of every `.class`.  Adds `lineMap` field
  (string-keyed for upickle reliability) on `ModuleJvmArtifact`; new
  `cli/JvmSmap` + `cli/JvmSmapInjector` modules.  Stack traces
  resolve to `.ssc` lines in JDI-aware tooling (IntelliJ / Metals /
  `jdb`); raw `java -cp` still shows `.scala` lines (JVM-platform
  limit — `Throwable.printStackTrace` doesn't consult SMAP).
- **`Main method not found in class a_sc`** — fixed via main() stub
  in the JVM bytecode wrapper object; previously 3 pre-existing
  `JvmBytecodeLinkCliTest` failures.
- **`ssc clean <dir>`** — garbage-collect artifacts whose source
  `.ssc` no longer exists.  `--dry-run` and `--all` flags; safe for
  CI use.  Covered by `CleanCliTest` (5 tests).
- **Reproducibility tests** — pin byte-identical output across two
  `compile-jvm` invocations.  ZIP entry timestamps fixed to epoch,
  entries sorted alphabetically, scalac `-sourceroot` set.  Covered
  by `ReproducibilityTest` (5 tests).

---

## Phase 5 — Production distribution (landed)

After Phase 4 closed the in-tree honesty list, Phase 5 addressed
external distribution + the last-known section-cache gap:

- **Per-section interface-based caching** (Option B) — opt in via
  `ssc build --incremental --section-cache=interface`.  Edits to a
  section's body that don't change its exported signatures no longer
  cascade to later sections.  The default cumulative-hash mode
  (`--section-cache` alone or `--section-cache=cumulative`) is still
  the conservative shared-scope-safe choice.  `ssc info --sections`
  dumps the chain in either mode.
- **External `.sscpkg` artifact-level distribution** — `ssc bundle
  --with-artifacts` runs `build --incremental --backend jvm` +
  `--backend js` on the inputs, then bundles the produced
  `.scim` / `.scjvm` / `.scjs` files under a `.ssc-artifacts/`
  prefix inside the `.sscpkg`.  Consumer-side: `compile-jvm` and
  `compile-js` resolve each `dep:` import via `ImportResolver`,
  call `findArtifactAlongside(sscPath, ext)` to discover
  `<dir>/.ssc-artifacts/<basename>.<ext>` (auto-detect, no
  manifest schema change), and stage the artifact into the local
  artifact dir so the typer + linker pick it up directly.
  Source-fallback when no artifacts ship; bad-magic artifacts
  surface a clear error.  Covered by
  `SscpkgArtifactDistributionTest` (5 tests).
- **User-facing getting-started tutorial** —
  [`docs/v2.0-getting-started.md`](v2.0-getting-started.md):
  copy-pasteable walkthrough from "I have an `.ssc` file" to
  "I have a JAR + source maps + LSP integration" in ~15 minutes.

### Remaining deferred directions

These are documented but not blocking the v2.0 feature-completeness
declaration:

- **Scale benchmark over real std/.**  ✓ Landed.  Bench runner at
  `scripts/v2-scale-bench.sh`; results written up in
  [`docs/v2.0-scale-benchmark.md`](v2.0-scale-benchmark.md).
  39/49 std/ modules compile under `build --incremental`; the
  10 failures are JvmGen-level gaps (`@targetName ""` clashes, shared
  `package:` self-reference issues, missing intrinsic surfaces),
  not v2.0 pipeline regressions.  Section caching adds 5-25 % overhead
  at the small-file scale and is a wash; per-module SHA-256 staleness
  is the right default.
- **Cross-platform (Windows) smoke.**  All tests assume Unix paths;
  Windows path separators, CRLF line endings (relevant for
  `sourceHash`), and file-lock semantics are not covered.

---

## Open questions — resolutions

1. **Interface granularity** — ~partially closed.  Stage 5.2 added real
   top-level type inference for `Defn.Def/Val/Class` signatures; Stage
   5.4 extended `parseSType` to union / intersection / higher-kinded
   types; Phase 2 follow-up added refinement + match types.  Complex
   bodies still fall back to `SType.Any` — interface usefulness scales
   with typer richness, which is itself a separate axis of work.

2. **Incremental backend linkage** — closed.  Per-module `.scjvm` and
   `.scjs` artifacts (Stage 5.2 / 5.4) ship the backend-cached form;
   `ssc link --backend {jvm|js}` consumes them directly.  Phase 2's
   runtime split (`.scjvm-runtime` / `.scjs-runtime`) made per-module
   payloads tiny (51× / 200× smaller).

3. **Circular imports** — closed.  `ModuleGraph` (Stage 6) and
   `AutoResolve` (Stage 5.5) both detect cycles, emit a `→`-joined
   cycle trace, and exit non-zero before any codegen runs.

4. **`package:` as module identity** — open.  Two files with the same
   `package:` value are still accepted in the single-pass path; a
   collision check at incremental-build entry is owed.

5. **Subprocess backends** — closed.  `SubprocessBackend` forwards
   `NormalizedModule` over the wire protocol; pre-linked `.scir` fits
   without change.  Confirmed after the linker landed.

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
| 2026-05-19 | Phase 2 | MVP | per-module `.class` bytecode in `.scjvm`; `link --backend jvm --bytecode` packs JAR |
| 2026-05-19 | Phase 2 | deep | JvmGen split into `generateRuntime` + `generateUserOnly`; `.scjvm-runtime` shared artifact; per-module 515 KB → 10 KB (51×) |
| 2026-05-19 | Phase 2 | follow-up | Refinement/match types in `parseSType`; unified `build --incremental` stdout/stderr; JS runtime split (200× per-module `.scjs` reduction) |
| 2026-05-19 | Phase 3 | – | TASTy-direct scalac driver (11.8× speedup); 64-test ABI compat suite; `docs/v2.0-artifact-format.md`; `ssc verify` |
| 2026-05-19 | Phase 3+ | – | `--source-map` (JVM sidecar `.ssc.scala` + JS V3 maps); `--section-cache` (per-section cumulative hash); `ssc lsp` (819 LoC server, 25 tests) |
| 2026-05-19 | Phase 4 | landed | `ExportedSymbol.definitionLine`/`Column` + `CodeBlock.lineOffset` (accurate LSP positions); JVM SMAP via ASM (`SourceDebugExtension`, Option A); `Main not found` fix (main() stub in wrapper); `ssc clean` + tests; reproducibility tests (pinned ZIP timestamps + sorted entries) |
| 2026-05-19 | Phase 5 | landed | `--section-cache=interface` (Option B, body edits don't cascade); `ssc bundle --with-artifacts` + consumer-side `findArtifactAlongside` resolution; user-facing `docs/v2.0-getting-started.md` |
