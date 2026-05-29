# `ssc new` — Project Scaffolding & Installation

Status: **partially implemented**. The plugin template path
(`ssc new <name> --template plugin`) landed with `arch-distribution-p4` on
2026-05-29. Phase 1 app/lib templates and repository-side Coursier channel
fixture landed on 2026-05-29. Phase 2 dsl/web-app/wasm-app templates plus
Homebrew/curl release inputs landed on 2026-05-29. Phase 3 standalone docs and
`install.sh --dev` behavior landed on 2026-05-29.
Companion: [`docs/arch-sbt-plugin.md`](arch-sbt-plugin.md),
[`docs/arch-distribution.md`](arch-distribution.md).

---

## 1. Goals

- **`ssc new <name> [--template <id>]`** creates a working project directory
  in under 5 seconds on any machine that has `ssc` installed.
- **Install without cloning the monorepo.**  Today the only path is
  `git clone` + `./install.sh` (requires sbt 1.x, a JDK, 10+ minute build).
  We need: `cs install ssc`, `brew install ssc`, or `curl -fsSL ... | sh`.
- **Plugin authoring template** gives community plugin authors a GitHub
  Actions CI that produces a `.sscpkg` and attaches it to releases with
  zero Maven infrastructure.

## 2. Non-goals

- Graphical project wizard or web-based scaffolding.
- IDE project import (covered by BSP in `arch-sbt-plugin.md §3g`).
- Template versioning / online template registry (local templates only for v1).
- `ssc migrate` to upgrade existing projects across template versions.

## 3. Architecture

### 3a. `ssc new` subcommand

Add to `tools/cli/src/main/scala/scalascript/cli/Main.scala` (current line
137 is the last `case` in the subcommand match):

```scala
case List("new", name, rest @ _*) =>
  val opts = parseNewOpts(rest.toList)
  NewProject.create(name, opts.template, opts.outputDir)
```

`NewProject.create`:
1. Read template from `classpath:templates/{template}/` (packaged inside
   `ssc.jar`).
2. Copy to `{outputDir}/{name}/`, substituting `${name}`, `${Name}`,
   `${version}` placeholders.
3. Run `git init` (if git on PATH).
4. Print next-steps message.

### 3b. Template structure

Templates live in `tools/cli/src/main/resources/templates/{id}/`:

```
templates/
  app/          -- runnable JVM application
  lib/          -- ScalaScript library (.sscpkg)
  plugin/       -- ScalaScript plugin (extern intrinsic + sscpkg)
  dsl/          -- DSL library (parser combinators + std/dsl)
  web-app/      -- browser frontend (JS backend)
  wasm-app/     -- WASM target
```

Implementation note: `app/`, `lib/`, `plugin/`, `dsl/`, `web-app/`, and
`wasm-app/` are currently implemented.

Each template includes at minimum:
```
${name}/
  build.sbt                           # sbt-scalascript plugin wired
  project/plugins.sbt                 # addSbtPlugin("io.scalascript" % ...)
  src/main/scalascript/Main.ssc       # hello-world entry point
  .gitignore
```

The `plugin` template additionally includes:
```
  .github/workflows/release.yml       # build + attach .sscpkg on tag push
  src/main/resources/META-INF/services/scalascript.backend.spi.Backend
```

### 3c. GitHub Actions release workflow (plugin template)

```yaml
# .github/workflows/release.yml
on:
  push:
    tags: ["v*.*.*"]
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: coursier/setup-action@v1
        with: { apps: "ssc" }
      - run: ssc package --output dist/${name}.sscpkg
      - uses: softprops/action-gh-release@v2
        with:
          files: dist/${name}.sscpkg
```

Users then add `//> using dep "github:{owner}/${name}@{tag}"` with no Maven
account required.

### 3d. Installation paths

**Goal**: the `ssc` binary available without cloning the monorepo.

#### Option A — Coursier launcher (recommended, Phase 1)

```bash
cs install ssc:1.0.0 --channel https://releases.scalascript.io/coursier.json
# then:
ssc new my-project
```

`releases.scalascript.io/coursier.json` is a Coursier channel descriptor.
In-repo source lives at `releases/coursier.json`:
```json
{
  "libraries": {
    "ssc": {
      "1.0.0": { "url": "https://github.com/..../releases/download/v1.0.0/ssc.jar" }
    }
  }
}
```

The `ssc.jar` produced by `sbt cli/assembly` is a self-contained fat JAR.

#### Option B — Homebrew tap (Phase 2)

```bash
brew install scalascript/tap/ssc
```

`github.com/scalascript/homebrew-tap` formula pointing to the fat JAR or a
GraalVM native-image binary (already produced by `v1.50-native-p2-graalvm`).

#### Option C — curl installer (Phase 2)

```bash
curl -fsSL https://get.scalascript.io | sh
```

Detects OS/arch, downloads the appropriate native binary from GitHub Releases,
places in `~/.local/bin/ssc`.

#### Rewrite of `install.sh`

The existing `install.sh` (requires `sbt cli/stage`) becomes a
**developer-mode** installer for people working on ScalaScript itself.
User-facing docs replace references to it with Option A/B/C.

## 4. Migration

- Existing `examples/` directory is unchanged — it remains as the canonical
  demo corpus inside the monorepo.
- The new templates ship independently inside `ssc.jar`.
- `install.sh` gains a `--dev` flag; without it, it prints "use `cs install ssc`".

## 5. Phases

### Phase 1 — `ssc new` + `app` template + Coursier channel

- `NewProject.scala` in `tools/cli/src/main/scala/scalascript/cli/`. ✓ Landed
  2026-05-29; default template is now `app`, while `--template plugin` remains
  explicit.
- `app`, `lib` templates in `tools/cli/src/main/resources/templates/`. ✓
  Landed 2026-05-29.
- Coursier channel JSON at `releases.scalascript.io`. ✓ Landed 2026-05-29 as
  `releases/coursier.json` source fixture.
- `sbt cli/assembly` produces self-contained `ssc.jar`. ✓ Already wired in
  `build.sbt` via sbt-assembly (`assembly / assemblyJarName := "ssc.jar"`).
- Tests: `NewProject` unit test (creates project in temp dir, verifies
  structure); integration test: `ssc new foo --template app && sbt run`
  exits 0.
  ✓ Unit coverage landed 2026-05-29 for default app, lib, and plugin
  scaffolds. Full `sbt run` integration remains CI-only/future because it
  requires a published sbt plugin artifact.

### Phase 2 — `plugin`, `dsl`, `web-app`, `wasm-app` templates

- Four additional templates. ✓ Landed 2026-05-29: `plugin` already existed;
  `dsl`, `web-app`, and `wasm-app` added in CLI resources.
- Homebrew tap formula. ✓ Landed 2026-05-29 as `releases/homebrew/ssc.rb`
  source template with release SHA placeholder.
- `curl | sh` installer script. ✓ Landed 2026-05-29 as `releases/install.sh`.
- Update `README.md` "Getting Started" section. ✓ Landed 2026-05-29 in the
  capabilities table and user-guide scaffolding notes.

### Phase 3 — docs update

- New `docs/getting-started-standalone.md` (fresh machine → running app
  in <5 minutes, no monorepo clone). ✓ Landed 2026-05-29.
- `docs/community-plugins.md` walkthrough for plugin template. ✓ Updated
  2026-05-29 with current template/install status.
- Update `docs/user-guide.md` "Installation" section. ✓ Landed 2026-05-29.
- `install.sh --dev` developer-mode switch. ✓ Landed 2026-05-29; plain
  `./install.sh` prints standalone install options instead of building.

## 6. Testing strategy

- `NewProject` unit test: calls `NewProject.create("hello", "app", tmpDir)`;
  asserts `build.sbt`, `src/main/scalascript/Main.ssc`, `.gitignore` exist;
  checks placeholder substitution.
- Integration test (CI only): `ssc new hello --template app && cd hello && sbt run`.
- Plugin template test: `ssc new my-plugin --template plugin && sbt package`
  produces a `.sscpkg`.
- Coursier channel test: `cs install ssc --channel <local-fixture-channel>`.

## 7. Open questions

1. Should templates live inside `ssc.jar` (current plan) or in a separate
   `ssc-templates` artifact fetched on first `ssc new` run?  Bundling keeps
   the CLI self-contained; separate artifact enables template updates without
   a CLI release.
2. Should `ssc new` run `git init` + initial commit automatically, or leave
   git setup to the user?
3. `app` template: should it include a `Main.ssc` with `serve()` (web app
   skeleton) or a plain `println` script?  Recommendation: two variants —
   `--template app-cli` and `--template app-web`.
