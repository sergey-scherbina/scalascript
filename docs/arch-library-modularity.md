# Library Modularity — Specification

Status: **implemented through Phase 6**.  Tracked as `arch-library-modularity`
milestone in `BACKLOG.md`.
Companion specs: [`docs/arch-distribution.md`](arch-distribution.md),
[`docs/arch-stable-spi.md`](arch-stable-spi.md).

---

## 1. Goals

- Introduce a **"ScalaScript Library Package"** format — a distributable unit
  for pure-ScalaScript libraries (no JVM code required) spanning multiple
  `.ssc` files, with a manifest, versioning, and transitive dependency
  declarations.
- Enable **transitive dependency resolution**: when library A declares
  `dep:org/json:1.0` and user code imports A, the runtime automatically pulls
  in `json` without manual re-declaration.
- Provide **access control at module boundary**: `@internal` annotation marks
  definitions as not importable from outside the package; compiler emits an
  error on violation.
- **Namespace collision detection**: warn (and optionally error) when two
  imported libraries export the same top-level heading/name.
- **`@deprecated` and `@experimental` annotations** for library API lifecycle.

## 2. Non-goals

- Full ML-style module system (functors, first-class modules).
- Binary compatibility enforcement (MiMa-style) — deferred to v2.x (Phase 5).
- Closing the `std/` privilege gap (community libraries calling Java/JS) —
  covered by [`docs/arch-ffi.md`](arch-ffi.md) (Tier 1 `@jvm`/`@js` annotations
  - Tier 2 `glue.jar` in `.ssclib`).
- Circular dependency resolution — cycles are a hard error.
- Package signing / provenance (out of scope for v1.x).

## 3. Current state — the granularity gap

ScalaScript has three packaging levels with a missing middle:

| Level | Format | JVM code required? | Multi-file? | Transitive deps? |
|-------|--------|--------------------|------------|-----------------|
| Single file | `.ssc` | No | No | No |
| **(missing)** | **`ssclib`** | **No** | **Yes** | **Yes** |
| Plugin | `.sscpkg` | Yes | Yes | Partial |
| IR artifact | `.scim` | No | One per module | No |

The gap means a library author must choose between:
- One giant `.ssc` file (unmanageable for anything non-trivial).
- A full JVM `.sscpkg` plugin (requires Scala toolchain, complex build).

## 4. Architecture

### 4a. `ssclib` — ScalaScript Library Package format

A `.ssclib` is a ZIP archive with the following layout:

```
my-lib-1.0.ssclib
  ssclib-manifest.yaml        # metadata + deps
  src/
    main.ssc                  # public API entry point
    internal/
      utils.ssc               # @internal modules
      helpers.ssc
  ir/
    main.scim                 # optional pre-compiled IR for fast import
    internal/utils.scim
```

`ssclib-manifest.yaml`:

```yaml
name: io.example/my-lib
version: 1.0.0
entry: src/main.ssc           # public-facing entry point
scala-script-version: ">=1.60"
dependencies:
  - dep: io.scalascript/json:1.0.0
  - dep: io.example/utils:2.1.0
```

The `entry` field declares the public surface; files under `src/internal/`
or annotated `@internal` are not importable from outside.

### 4b. Import syntax — no change

```
[myLib](dep:io.example/my-lib:1.0.0)
```

`ImportResolver` resolves `dep:` to a `.ssclib` archive (or the existing
single-`.ssc` path for backwards compat), unpacks it to
`~/.cache/scalascript/libs/io.example/my-lib/1.0.0/`, and makes the
`entry` file the import target.

Backwards compatibility: plain `.ssc` files (no archive) continue to work
exactly as today — `dep:` can point to either format.

### 4c. Transitive dependency resolution

When `ImportResolver` processes a `.ssclib`, it reads `ssclib-manifest.yaml`
and enqueues all `dependencies` for resolution before typechecking begins.
Transitive closure is computed breadth-first; cycles → hard error with a
clear message naming the cycle.

**Conflict resolution** (two libs require different versions of the same dep):
- Default: latest version wins (optimistic, like npm).
- Strict mode (`--strict-deps`): error on any version conflict.
- Lockfile (`ssc-lock.yaml`) records the resolved set; `ssc update` refreshes it.

```yaml
# ssc-lock.yaml — generated, committed to repo
locked:
  io.scalascript/json: 1.0.0
  io.example/utils: 2.1.3
  io.example/my-lib: 1.0.0
```

### 4d. Access control — `@internal`

New annotation `@internal` on any definition or heading:

```markdown
## Http                        # public — importable

### @internal
## HttpInternals               # only visible within this package
```

Or at the definition level:

```scala
@internal
def buildAuthHeader(token: String): String = ...  // not importable outside
```

The compiler checks at import resolution time: if a consumer imports a name
marked `@internal` from a different package, it emits `Error.InternalAccess`:

```
error: `HttpInternals.buildAuthHeader` is marked @internal in io.example/http-lib
       and cannot be imported from outside that library.
```

Within the same `.ssclib` (same `name:` in manifest), `@internal` is freely accessible.

### 4e. Namespace collision detection

When two imports contribute the same top-level name, the compiler:

1. Emits a `Warning.NamespaceCollision` by default:
   ```
   warning: both `io.example/a:1.0` and `io.example/b:2.0` export `Http`
            The second import shadows the first. Use qualified names to disambiguate.
   ```
2. With `--strict-namespaces`: error instead of warning.
3. Qualified access: `[Http from a](dep:io.example/a:1.0)` pins the import.

### 4f. API lifecycle annotations

```scala
@deprecated(since = "1.2.0", "Use newMethod() instead")
def oldMethod(): Unit = ...

@experimental("API subject to change without notice")
def newFeature(): Unit = ...
```

- `@deprecated`: compiler emits `Warning.Deprecated` at each call site.
  `--fatal-warnings` turns it into an error for library publishers.
- `@experimental`: compiler emits `Warning.Experimental` at each call site.
  Requires explicit `import scalascript.experimental.feature` to suppress.

### 4g. `ssc package --lib` command

Produces a `.ssclib` archive from a project directory:

```bash
ssc package --lib --manifest ssclib-manifest.yaml --output dist/my-lib-1.0.ssclib
```

Optionally pre-compiles all `.ssc` files to `.scim` interface artifacts under
`ir/`, and compares public interfaces before publishing a new version:

```bash
ssc package --lib --precompile --manifest ssclib-manifest.yaml --output dist/my-lib-1.0.ssclib
ssc check-compat dist/my-lib-1.0.ssclib dist/my-lib-1.1.ssclib
```

## 5. Migration

- All existing `dep:` imports of single `.ssc` files continue to work.
- `@deprecated` and `@experimental` are new annotations — no existing code
  affected.
- `@internal` defaults to "accessible" in the absence of explicit annotation;
  no existing code becomes inaccessible.
- Lockfile (`ssc-lock.yaml`) is opt-in; `ssc` works without it.

## 6. Phases

### Phase 1 — `@deprecated` and `@experimental` annotations

- New annotations in `lang/core/.../ast/Annotation.scala`.
- Typer emits warnings at call sites.
- `--fatal-warnings` flag (if not already present).
- Tests: 6+ tests — deprecated def → warning; experimental import → warning;
  `@SuppressWarnings("deprecated")` suppresses.
- No format change; no resolver change.
- **Deliverable**: library authors can mark APIs for lifecycle management today.

### Phase 2 — `@internal` access control

- `@internal` annotation parsed and stored on definitions.
- Cross-package access check in `Typer` (or `CapabilityCheck`).
- Error emitted with clear message including source package name.
- Tests: 8+ tests — same-package access OK; cross-package access error;
  `@internal` on heading → all definitions under heading are internal.

### Phase 3 — Namespace collision detection

- `ImportResolver` tracks name contributions per import.
- Warning on first collision; `--strict-namespaces` flag for error.
- Qualified import syntax: `[Name from alias](dep:...)`.
- Tests: 6+ tests — single import OK; two imports same name → warning;
  `--strict-namespaces` → error; qualified import disambiguates.

### Phase 4 — `ssclib` format + `ssc package --lib`

- `SsclibManifest` case class; reader/writer for `ssclib-manifest.yaml`.
- `ssc package --lib` CLI command in `tools/cli/src/main/scala/scalascript/cli/`.
- ZIP packaging with `src/` + optional `ir/` layout.
- `ImportResolver` updated to unpack `.ssclib` archives alongside single-file deps.
- Tests: `ssc package --lib` produces valid archive; `ImportResolver` resolves
  entry point from archive; malformed manifest → clear error.

### Phase 5 — Transitive dependency resolution + lockfile

- Recursive dep resolution in `ImportResolver`: BFS over `ssclib-manifest.yaml`
  dependency lists.
- Conflict resolution (latest-wins by default).
- `scc-lock.yaml` generation on `ssc build`; `ssc update` refreshes it.
- `--strict-deps` flag for version-conflict errors.
- Cycle detection → error with cycle path.
- Tests: transitive dep pulled in; conflict resolved correctly; cycle detected;
  lockfile generated; `ssc update` refreshes versions.

### Phase 6 — Pre-compiled IR in `.ssclib` + fast import (v2.x)

- ✓ Landed 2026-05-29: `ssc package --lib --precompile` compiles all packaged
  `.ssc` sources to `.scim` interfaces and bundles them in `ir/`.
- ✓ Landed 2026-05-29: `ssc check-compat old.ssclib new.ssclib` compares public
  `.scim` symbol shapes and reports removed/changed symbols; if no `ir/*.scim`
  exists, it derives interfaces from packaged `src/*.ssc`.
- Import-time fast-path remains staged behind consumers that already pass
  `.scim` interface directories; `dep:` resolution still returns the manifest
  entry `.ssc` path for source compatibility.
- Tests: `SsclibPackageCliTest` covers precompiled archive layout and removed
  public symbol detection.

## 7. Testing strategy

- Phase 1–3: unit tests in `lang/core/src/test/` using in-memory source strings.
- Phase 4: integration tests using temp directories; `ssc package --lib` CLI.
- Phase 5: multi-library fixture (3 libs with shared dep) + conflict fixture.
- Phase 6: CLI integration tests for `--precompile` and `check-compat`; a
  performance benchmark can be added once import consumers use `ir/*.scim`
  directly from the `.ssclib` cache.

## 8. Open questions

1. **`.ssclib` vs `.sscpkg` naming** — should `.sscpkg` be unified into one
   format that covers both pure-ScalaScript and JVM-backed cases?  Upside:
   simpler mental model.  Downside: the JVM-backed case requires ClassLoader
   machinery that would pollute the pure case.  Recommendation: keep separate.
2. **Conflict resolution strategy** — "latest wins" is npm-style and causes
   subtle bugs.  "Error on any conflict" (Cargo-style) is safer but breaks
   more ecosystems.  Recommendation: latest-wins default with `--strict-deps`
   opt-in, same as today's `ssc install` semantics.
3. **`@internal` granularity** — should it be per-definition only, or also
   per-heading (all defs under a heading become internal)?  Recommendation:
   both, with heading-level `@internal` as syntactic sugar.
4. **Lockfile location** — project root `ssc-lock.yaml` or inside `.ssc/`
   directory?  Recommendation: project root, committed to version control.
