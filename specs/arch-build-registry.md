# Build-time Registry Consolidation — Specification

Status: **partially implemented**.  Phases 1 AND 2 landed 2026-05-29.
Remaining: Phase 3 (cleanup — remove deprecated `PluginManifest`/`LocalRegistry`
wrappers + `isStdPluginInterpreterTest` filter; partly gated on Theme A Phase 3)
and the OPTIONAL Phase 4 (family registries, only where they remove real
duplication).  Tracked as `arch-build-registry` milestone in `BACKLOG.md`.
Companion: [`specs/plugin-architecture.md`](plugin-architecture.md).

---

## 1. Goals

- **Single source of truth for plugin registration in `build.sbt`.**  Today
  adding one plugin requires editing five separate places in `build.sbt`
  (new `lazy val`, `cli` deps line 739, `installBin` prefix set lines
  907-913, `pluginPkgs` Seq lines 925-941, root aggregate lines 3064-3067,
  `backendInterpreterPluginTests` deps lines 2393-2395).  This creates
  merge conflicts in parallel-agent sessions and makes `build.sbt`
  increasingly hard to navigate (~3100 lines).
- **Merge the three plugin registries** — `BackendRegistry` (in-process +
  sscpkg), `PluginManifest`/`SubprocessBackend` (out-of-process),
  `LocalRegistry` (download URLs) — into one coherent lookup with a single
  cache.  Today each has its own cache and discovery path, creating subtle
  ordering bugs and duplicated cache-invalidation logic.
- **Make "add a plugin" a one-file change** — new sbt subproject + one
  `META-INF/services` entry.  No other central file should need editing.

## 2. Non-goals

- Changing the user-facing `ssc install` or `.sscpkg` format.
- Removing any existing plugin or changing plugin APIs.
- Dynamic plugin hot-reload (plugin set is fixed at startup).
- External plugin publication / Maven resolution (tracked separately in
  [`specs/arch-distribution.md`](arch-distribution.md)).

## 3. Architecture

### 3a. `build.sbt` — single `PluginSpec` registry

Replace the five scattered places with one `Seq[PluginSpec]` at the top:

```scala
// build.sbt — new unified plugin registry
case class PluginSpec(
  id: String,           // e.g. "json"
  project: Project,     // the lazy val sbt Project
  jarPrefix: String,    // installBin prefix match, e.g. "scalascript-json"
)

val allPlugins: Seq[PluginSpec] = Seq(
  PluginSpec("json",   jsonPlugin,   "scalascript-json"),
  PluginSpec("auth",   authPlugin,   "scalascript-auth"),
  PluginSpec("http",   httpPlugin,   "scalascript-http"),
  // ...all ~20 plugins...
)
```

All five derived lists are computed from `allPlugins`:

```scala
// cli deps — derived
val cliPluginDeps = allPlugins.map(_.project % Test)

// installBin prefix set — derived
val installBinPrefixes = allPlugins.map(_.jarPrefix).toSet

// pluginPkgs Seq — derived
val pluginPkgIds = allPlugins.map(_.id)

// root aggregate — derived
val pluginProjects: Seq[ProjectReference] = allPlugins.map(_.project)

// backendInterpreterPluginTests — derived
val pluginTestDeps = allPlugins.map(_.project % Test)
```

The `isStdPluginInterpreterTest` filename filter in `build.sbt:43-54` is
deleted once Phase 1 of `arch-stable-spi` removes the compile-time coupling
that required the split.

### 3b. Runtime registries — unified `PluginRegistry`

Collapse `BackendRegistry`, `PluginManifest`/`SubprocessBackend`, and
`LocalRegistry` behind a single `PluginRegistry` facade:

```scala
trait PluginRegistry {
  def lookup(id: String): Option[Backend]
  def listInstalled(): Seq[PluginMeta]
  def install(source: PluginSource): Future[Unit]
}

sealed trait PluginSource
case class ClasspathPlugin(fqcn: String)    extends PluginSource  // in-process ServiceLoader
case class SscpkgPlugin(path: Path)         extends PluginSource  // .sscpkg archive
case class SubprocessPlugin(
  binary: Path,
  args: Seq[String] = Nil,
  workingDirectory: Option[Path] = None,
  protocol: String = "stdio-json",
) extends PluginSource  // out-of-process
case class RemotePlugin(uri: URI)           extends PluginSource  // download then sscpkg
```

`BackendRegistry` is the implementation of `PluginRegistry`.  The existing
public `BackendRegistry.lookup/all/inProcess/loadSscpkg` methods remain for
compatibility, while new callers can depend on the SPI facade.  Discovery and
lookup priority is classpath > explicit installed plugin cache > manifest-backed
subprocess plugins.

Phase 2 uses the existing `BackendRegistry` subprocess cache as the unified
runtime cache.  A stronger `(id, version, sha256)` cache key is deferred to
Phase 3 cleanup because legacy `plugin.yaml` subprocess manifests do not carry a
content hash today.

### 3c. `LocalRegistry` absorption

`LocalRegistry` (the `~/.scalascript/registry.yaml` file) becomes a
`RemotePlugin` pre-seeded list, fetched and cached lazily.  In Phase 2 the
download/install logic moved to `RemotePluginInstaller`; `LocalRegistry` remains
as a compatibility wrapper for CLI registry management and existing callers.
The wrapper is removed in Phase 3.

## 4. Migration

The build.sbt refactor is purely internal; `lazy val` names stay the same,
only their wiring changes.  No user-facing change.

Runtime: `BackendRegistry` public API stays unchanged.  `LocalRegistry` is kept
as a thin compatibility surface through one release.  `PluginManifest` and
`SubprocessBackend` remain the concrete parser/transport types for legacy
`plugin.yaml`; `SubprocessPlugin` is the new install strategy at the
`PluginRegistry` facade.

## 5. Phases

### Phase 1 — `build.sbt` `PluginSpec` registry

- Introduce `PluginSpec` case class at top of `build.sbt`.
- Migrate all ~20 plugins to it.
- Verify: `sbt cli/stage` produces same artifact; all existing tests pass.
- Deliverable: `build.sbt` shrinks by ~200 lines; adding a plugin in the
  template section is 3 lines instead of 30.

### Phase 2 — Runtime `PluginRegistry` unification

- ✓ Landed 2026-05-29: new `PluginRegistry`, `PluginMeta`, and
  `PluginSource` hierarchy in `backend/spi`.
- ✓ Landed 2026-05-29: `BackendRegistry` implements the facade while preserving
  existing lookup/list/install APIs.
- ✓ Landed 2026-05-29: `SubprocessPlugin` strategy delegates to the existing
  `SubprocessBackend` wire protocol.
- ✓ Landed 2026-05-29: `RemotePluginInstaller` owns path/URL/registry-alias
  `.sscpkg` install logic; CLI install and `pkg:` auto-install share it.
- ✓ Landed 2026-05-29: `BackendRegistryTest` covers the facade and classpath
  install strategy; existing local-registry and subprocess tests remain green.

### Phase 3 — Cleanup (status reconciled 2026-06-18)

- `PluginManifest` / `LocalRegistry` are **not** trivial dead wrappers — they have
  **live callers** (`ImportResolver`, `RemotePluginInstaller`, `BackendRegistry`,
  `tools/cli/.../PluginCommands`). "Removal" is therefore a **caller migration** onto
  the `PluginRegistry`/`BackendRegistry` facade, not a delete — with real regression
  risk. Do it caller-by-caller behind green tests, or leave the thin compat surface as
  documented in §"Migration". NOT a quick cleanup; partly gated on Theme A Phase 3.
- `isStdPluginInterpreterTest` filter: **already gone** (no such symbol in the tree as
  of 2026-06-18) — this bullet was stale; nothing to remove.
- Update `specs/plugin-architecture.md` to reflect the new shape (still open).

### Phase 4 — Build family registries

The first build registry phase reduced plugin wiring duplication, but
`build.sbt` still has large repeated families: frontend backends, runtime
standard plugins, benchmark modules, CLI/plugin packaging, and integration-test
groups.

Add small family registries only where they remove real duplication. The target
shape is declarative lists that derive aggregate membership, test dependencies,
ServiceLoader resource checks, and packaging classpaths from one source of
truth. Do not introduce a generic build DSL; keep each family close to the
existing sbt layout so future agents can audit the generated wiring quickly.

## 6. Testing strategy

- Phase 1: `sbt +test` must be green.  Add a `build.sbt` compile check that
  fails if any of the five derived lists diverge from `allPlugins`.
- Phase 2: add `PluginRegistryTest` — all three discovery strategies
  (classpath, sscpkg, subprocess) exercised with test doubles.
- Phase 3: regression — full test suite green.

## 6b. Remote registry (remote-package-registry — IN PROGRESS 2026-06-23)

The client side is done ([[LocalRegistry]] = static name→URL alias map; `RemotePluginInstaller` downloads a
`.sscpkg` from a URI; `ssc install`). The missing half is the **server-side catalog** that lets third-party
authors publish + others discover/resolve by name+version — what `registry.scalascript.io` will serve.

**Protocol (slice 1, DONE).** `RemoteRegistry` (`lang/core/.../compiler/plugin/RemoteRegistry.scala`):
- `Entry(id, version, sha256, description)` — a published artifact keyed by `(id, version)` with the SHA-256
  of its `.sscpkg` bytes (the publish↔install integrity contract). JSON-serialized (`toJson`/`fromJson`).
- `indexToJson`/`indexFromJson` — the catalog wire format (`{ "packages": [Entry…] }`), what an HTTP
  registry returns.
- `compareVersions` — dotted-numeric ordering (`1.2.10 > 1.2.9`; shorter prefix is smaller).
- `sha256Hex` — artifact digest.

**Reference registry (slice 1, DONE).** `FileRegistry(root)` — a directory-backed catalog
(`root/index.json` + `root/packages/<id>/<version>.sscpkg`) implementing the full operation set, doubling as
the round-trip test harness AND the implementation an HTTP service wraps:
- `publish(id, version, bytes, description)` — stores bytes + indexes; **immutable releases** (re-publishing
  identical content is idempotent, different content under the same `(id,version)` is rejected).
- `search(query)` — case-insensitive substring over id+description (empty query = whole catalog).
- `resolve(id, version)` — exact, or highest version for `""`/`"latest"`.
- `versions(id)`, `fetch(id, version)` — fetch verifies the SHA-256 against the index (no silent corruption).
- Locked by `RemoteRegistryTest` (7 cases: publish/round-trip/latest/absent/immutability/JSON-persistence/version-ordering).

**Follow-up slices:** `ssc publish` / `ssc search` CLI commands over `FileRegistry`; an HTTP server wrapping
it (the JSON wire format is ready); remote `pkg:` resolution (name → registry `resolve` → URI →
`RemotePluginInstaller`); auth for publish. EXTERNAL (deploy, not code): host `registry.scalascript.io`.

## 7. Open questions

1. Should `PluginSpec.project` hold the `lazy val` by reference or by
   name-string?  sbt's macro support for this is limited; may need a
   `def` instead.
2. Is `~/.scalascript/registry.yaml` the right location for the seeded
   remote list, or should it move to `~/.config/scalascript/`?
3. Should `sscpkg` discovery scan a user-writable directory (current:
   `~/.scalascript/compiler/plugins/`) or prefer a system-level dir?
4. Which build family should be migrated first: frontend backends, std runtime
   plugins, or benchmark modules? Pick the family with the clearest duplicate
   wiring and smallest test blast radius.
