# Distribution Ecosystem — Multi-Channel Plugin & Artifact Delivery

Status: **partially implemented**.  Phase 1 landed 2026-05-29; later phases
remain tracked as `arch-distribution` milestones in `BACKLOG.md`.
Companion specs: [`docs/plugin-architecture.md`](plugin-architecture.md),
[`docs/arch-build-registry.md`](arch-build-registry.md),
[`docs/arch-sbt-plugin.md`](arch-sbt-plugin.md).

---

## 1. Goals

- **External plugin repositories work without Maven Central.**  Third-party
  authors keep their plugin in their own GitHub repo; users consume it with
  `//> using dep "github:user/repo@v1.0.0"` — no Maven account, no publish
  step, no change to ScalaScript itself.
- **Multiple distribution channels under one `DepResolver` SPI**, so a new
  channel (JitPack, GitHub Packages, S3, Artifactory) can be added as a
  plugin, not a core change.
- **Coursier wiring** for Maven-coordinate deps, with `~/.cache/coursier/`
  as the canonical cache — retries, mirrors, and credentials come for free.
- **Content-addressed `DepCache`** so any channel can pin reproducibly via
  `sha256:...` digest annotation.
- **First-party artifacts on Maven Central** as one channel of many — not a
  prerequisite for the community.

## 2. Non-goals

- Transitive dependency resolution across `.ssc` modules (separate
  compilation concern, tracked as v2.0).
- Private registry server (tracked separately; `LocalRegistry` covers the
  in-org case today).
- `git+ssh:` channel (complex auth, low ROI; optional future addition).
- Semver compatibility checking between plugin versions.

## 3. Architecture

### 3a. `DepResolver` SPI

Replace `lang/core/.../imports/ImportResolver` (monolithic `dep:` / `pkg:`
logic) with a pluggable `DepResolver` SPI:

```scala
// runtime/backend/spi/src/main/scala/scalascript/backend/spi/DepResolver.scala
trait DepResolver {
  def scheme: String               // "github", "jitpack", "dep", "https", …
  def resolve(spec: DepSpec): Future[Path]   // returns local path to artifact
}

case class DepSpec(
  raw: String,          // original //> using dep "..." string
  sha256: Option[String],  // optional content-address pin
)
```

`ImportResolver` becomes a thin dispatcher: it tries each registered
`DepResolver` in priority order, caches results in `DepCache`.

Built-in resolvers shipped in `lang/core`:

| Scheme | Class | Resolves to |
|--------|-------|-------------|
| `dep:` | `MavenDepResolver` | Coursier resolve → local Coursier cache |
| `pkg:` | `SscpkgResolver` | sscpkg archive from local registry or HTTP |
| `github:` | `GithubReleaseResolver` | GitHub Releases asset download |
| `https:` / `http:` | `HttpDepResolver` | direct URL download (current `LocalRegistry` logic) |
| `jitpack:` | `JitpackResolver` | JitPack Maven endpoint via Coursier |

Third-party resolvers register via `META-INF/services/scalascript.backend.spi.DepResolver`.

Phase 1 implementation note: the SPI lives in `runtime/backend/spi`, and
`ImportResolver` dispatches `github:` through built-in plus ServiceLoader
resolvers. Existing `dep:` and `pkg:` behavior is unchanged; moving those
schemes behind resolver classes is left to Phase 2 with Coursier/JitPack.

Phase 2 implementation note: Maven-shaped `dep:` coordinates
(`dep:group:artifact:version` or `dep:group::artifact:version`) now dispatch to
`MavenDepResolver`; legacy source imports (`dep:org/name:version`) still use the
existing dep-sources chain. The resolver invokes Coursier via
`-Dssc.coursier.command`, `SSC_COURSIER`, `cs`, or `coursier`, and accepts extra
repositories via `ssc.coursier.repositories` / `SSC_COURSIER_REPOSITORIES`.
`jitpack:` delegates to the same resolver with the `jitpack` repository enabled.

### 3b. URI scheme syntax

```
//> using dep "github:sergey/my-plugin@v1.2.0"
//> using dep "github:sergey/my-plugin@v1.2.0#dist/plugin.sscpkg"  // specific asset path
//> using dep "jitpack:com.github.sergey:my-plugin:v1.2.0"
//> using dep "dep:io.scalascript::json-plugin:1.0.0"              // Maven via Coursier
//> using dep "https://cdn.example.com/plugins/foo-1.0.sscpkg"    // plain HTTP (current)
//> using dep "pkg:io.scalascript/json:1.0.0"                      // sscpkg registry (current)
```

The existing `dep:` and `pkg:` schemes are preserved with identical behaviour;
no breaking change.

### 3c. `GithubReleaseResolver`

1. Parse `github:owner/repo@tag[#asset-path]`.
2. Call `https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}`.
3. Find the asset whose name matches `asset-path` (default: first `*.sscpkg`).
4. Download to `~/.cache/scalascript/github/{owner}/{repo}/{tag}/{asset}`.
5. Optionally verify `sha256:` digest.
6. Return local path.

Auth: uses `GITHUB_TOKEN` env var when present; unauthenticated for public repos.

### 3d. `JitpackResolver`

Delegates to `MavenDepResolver` with Coursier's `jitpack` repository enabled.
The coordinate body is ordinary Coursier/Maven syntax, for example
`jitpack:com.github.owner:repo:v1.0.0`.

### 3e. `DepCache` — content-addressed

```scala
object DepCache {
  def get(key: CacheKey): Option[Path]
  def put(key: CacheKey, artifact: Path): Path
  case class CacheKey(scheme: String, coord: String, sha256: Option[String])
}
```

Cache lives at `~/.cache/scalascript/deps/` (already used by the `dep:`
path).  `sha256` pin makes any channel reproducible: `//> using dep
"github:user/repo@main#dist/plugin.sscpkg" sha256:abc123…`.

### 3f. First-party Maven publication

Publish to Maven Central via Sonatype:

```
io.scalascript:scalascript-core:1.0.0
io.scalascript:scalascript-runtime:1.0.0
io.scalascript:sbt-scalascript:1.0.0        (Sonatype Maven Releases → sbt Plugin Portal)
io.scalascript:scalascript-json-plugin:1.0.0 // example community-style plugin
```

Group ID unified to `io.scalascript` (rename `tools/sbt-plugin` from `org.scalascript`).
New `project/Publishing.scala` sbt file with Sonatype OSS config.

Community plugin authors are **not required** to publish to Maven Central;
GitHub Releases + `github:` scheme is the zero-friction path.

### 3g. Community plugin starter template

New `tools/cli/src/main/resources/templates/plugin/` directory (used by
`ssc new --template plugin` per `arch-ssc-new.md`):

```
my-plugin/
  build.sbt                          # depends on scalascript-plugin-api
  src/main/scala/…/MyPlugin.scala
  src/main/resources/META-INF/services/scalascript.backend.spi.Backend
  src/main/scalascript/my-plugin.ssc # extern declarations
  .github/workflows/release.yml      # builds .sscpkg + attaches to GitHub Release
```

The GitHub Actions workflow: on `push tag v*.*.*`, run `ssc package`,
upload `*.sscpkg` as a release asset.  Users then add:
`//> using dep "github:{owner}/{repo}@{tag}"`.

## 4. Migration

- `dep:` and `pkg:` schemes are routed through the new `DepResolver` SPI
  with identical behaviour.  No `.ssc` source file needs changing.
- `LocalRegistry` is absorbed into `DepCache` / `HttpDepResolver`;
  `~/.scalascript/registry.yaml` continues to work as before.
- `lang/core/.../imports/ImportResolver.scala` is refactored internally;
  public API (`resolveImports`) signature unchanged.

## 5. Phases

### Phase 1 — `DepResolver` SPI + `GithubReleaseResolver`

- New `DepResolver` trait in `backend/spi`. ✓ Landed 2026-05-29.
- `ImportResolver` refactored to dispatch external resolver schemes.
  ✓ Landed 2026-05-29 for `github:` while preserving existing `dep:` / `pkg:`.
- `GithubReleaseResolver` as first non-trivial new channel. ✓ Landed
  2026-05-29.
- Tests: mock GitHub API server; resolve + cache; sha256 pin verification.
  ✓ Landed 2026-05-29.
- Deliverable: `//> using dep "github:user/repo@v1.0.0"` works. ✓ Landed
  2026-05-29 for Markdown imports such as `[Plugin](github:user/repo@v1.0.0)`.

### Phase 2 — Coursier wiring + JitPack

- `MavenDepResolver` using Coursier command wiring. ✓ Landed 2026-05-29.
- `JitpackResolver` as thin Coursier-repo wrapper. ✓ Landed 2026-05-29.
- Tests: Coursier command resolution against a local Maven repo fixture.
  ✓ Landed 2026-05-29.

### Phase 3 — First-party Maven publication

- `project/Publishing.scala` with Sonatype config.
- CI: publish to Maven Central on tag push.
- Group ID renamed `io.scalascript` in `tools/sbt-plugin/build.sbt`.
- `sbt-plugin-portal` registration for `sbt-scalascript`.

### Phase 4 — Community plugin starter template

- `tools/cli/src/main/resources/templates/plugin/` directory.
- GitHub Actions workflow template.
- Docs: new `docs/community-plugins.md` walkthrough.

## 6. Testing strategy

- Phase 1: `GithubReleaseResolver` tested against `com.sun.net.httpserver.HttpServer`
  mock (same pattern as wallet-vault tests).
- Phase 2: Coursier resolution tested with an embedded local Maven repo fixture
  (Maven local repo directory pointed at a fixture).
- Phase 3: publishing to Sonatype staging tested via `sbt +publishSigned` dry-run.
- Phase 4: `ssc new --template plugin` integration test (creates project,
  verifies `build.sbt` and workflow exist, no compile errors).

## 7. Open questions

1. Should `sha256:` be inline in the URI string or in a separate lockfile
   (e.g. `dep-lock.ssc.lock`)?  Lockfile is more idiomatic (Yarn, Cargo),
   but inline is simpler for single-dep cases.
2. GitHub rate limits: 60 req/hour unauthenticated, 5000 with token.  Should
   the resolver cache the GitHub API response (release JSON) separately from
   the asset, with a TTL?
3. Does `dep:` (Maven via Coursier) resolve transitively at the ScalaScript
   level?  Recommendation: no — plugins are single-artifact `.sscpkg` files;
   transitive resolution is Coursier's concern for JVM classpath only.
