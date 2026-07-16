# Package Registry — Specification

Status: **IMPLEMENTED (CLI + no-domain static registry), 2026-06-20.** The `ssc search` / `ssc info` / `ssc add` CLI
is built (`LockCommands.SearchCmd`/`AddCmd`, `Main.InfoCmd` registry dispatch) over
`scalascript.imports.RegistryClient` (URL priority `--registry` → config → default
GitHub Pages project URL `https://sergey-scherbina.github.io/scalascript/packages.yaml`;
1-hour-TTL cache at `~/.cache/scalascript/registry/`;
`--refresh`; `--offline` cached-only search via `RegistryClient.loadOffline()`;
substring+keyword search via `RegistryEntry`). In-repo seed catalog at
`registry/packages.yaml` (5 first-party `io.scalascript/*` entries). `registry/site/`
now contains the generated no-domain Pages artifact (`packages.yaml`, `index.html`,
per-package JSON, `search-index.json`) and `.github/workflows/registry-pages.yml`
deploys it without `CNAME`. REMAINING: custom-domain alias
(`registry.scalascript.io`) and cross-repo/community governance. The MVP must not block
on domain registration.
This spec was previously stale ("planned") — the implementation had already landed.
Companion: [`specs/arch-distribution.md`](arch-distribution.md),
[`specs/arch-library-modularity.md`](arch-library-modularity.md),
[`specs/arch-ssc-new.md`](arch-ssc-new.md).

---

## 1. Goals

- **Discoverability**: `ssc search json` returns ranked results — no GitHub
  search, no manual hunting.
- **Zero server infrastructure**: the registry is static files on GitHub Pages;
  no backend, no database, no ops cost, and no custom domain required for the
  first public MVP.
- **PR-based publishing**: library authors submit a PR adding one entry to
  `packages.yaml`; CI verifies the package resolves and compiles.
- **`ssc search` / `ssc info` / `ssc add` CLI commands** integrated with the
  registry.
- **Browseable HTML index** at the GitHub Pages project URL
  `https://sergey-scherbina.github.io/scalascript/registry/`, auto-generated from
  `packages.yaml` by CI. (The Pages root serves the project landing page; the
  registry machine files stay at the root — see below.)

## 2. Non-goals

- Authenticated publishing (no npm-style login/tokens in v1).
- Package ownership enforcement — name squatting is addressed by PR review.
- Paid tiers or private packages.
- Hosting artifact binaries — the registry is an index only; artifacts live
  at their source URLs (GitHub Releases, JitPack, etc.).
- Automated quality scoring or test execution of submitted packages.

## 3. Architecture

### 3a. Registry repository layout

No-domain MVP layout in this repo:

```
github.com/sergey-scherbina/scalascript/
  registry/
    packages.yaml                 # source catalog; single source of truth
    site/                         # generated locally/CI; GitHub Pages artifact root
      packages.yaml               # copied catalog for CLI default URL
      index.html                  # searchable package listing
      packages/
        io.example/
          json-extra/
            index.json            # machine-readable metadata per package
      search-index.json           # JSON search docs over all packages
  .github/
    workflows/
      validate.yml                # PR check: resolve + ssc check each package
      registry-pages.yml          # on merge to main: regenerate + deploy Pages
```

Pages URL for the MVP:

- HTML index: `https://sergey-scherbina.github.io/scalascript/registry/`
- CLI YAML: `https://sergey-scherbina.github.io/scalascript/packages.yaml`

The Pages root (`/`) serves the project landing page (`site/`), so the browseable
index lives under `/registry/`. The **CLI YAML path is a contract** —
`RegistryClient.DefaultRegistryUrl` is a built-in default in shipped source, so
`/packages.yaml`, `/packages/**` and `/search-index.json` must keep resolving at
the root. Both trees are published by the single `Pages` workflow
(`.github/workflows/pages.yml`); a second Pages-deploying workflow would race it.

Later, when a domain is registered, `registry.scalascript.io` can CNAME to the
same Pages site. That alias must not change the registry file format.

### 3b. `packages.yaml` schema

```yaml
# packages.yaml
- name: io.example/json-extra       # required, globally unique
  version: 1.2.0                    # required, latest stable
  description: "Extra JSON utilities for ScalaScript"
  keywords: [json, serialization, codec]
  backends: [jvm, js]               # which backends the package supports
  url: "github:example/json-extra@v1.2.0"   # dep: URI resolved by DepResolver
  license: Apache-2.0
  author: "Jane Example <jane@example.com>"
  homepage: "https://github.com/example/json-extra"
  changelog: "https://github.com/example/json-extra/blob/main/CHANGELOG.md"
  scala-script-version: ">=1.60"    # minimum ssc version required
```

Allowed `url` schemes: `github:`, `jitpack:`, `dep:` (Maven), `https:`.
The `name` field follows `<group>/<artifact>` convention; `io.scalascript/*`
is reserved for first-party packages.

### 3c. CLI commands

#### `ssc search <query>`

```
$ ssc search json
Fetching registry... (cached 3h ago)

  io.scalascript/json      1.0.0  Built-in JSON support (std)      [jvm, js]
  io.example/json-extra    1.2.0  Extra JSON utilities             [jvm, js]
  io.other/json-schema     0.4.1  JSON Schema validation           [jvm]

3 results. Use `ssc info <name>` for details.
```

Implementation: `ssc` downloads the raw `packages.yaml` from the effective registry URL
(`https://sergey-scherbina.github.io/scalascript/packages.yaml` by default) on first use, caches to
`~/.cache/scalascript/registry/packages.yaml` with a 1-hour TTL.  Search
runs locally via substring + keyword matching.  `--refresh` forces a
re-download.

#### `ssc info <name>`

```
$ ssc info io.example/json-extra
io.example/json-extra 1.2.0 — Extra JSON utilities for ScalaScript
  Author:   Jane Example
  License:  Apache-2.0
  Backends: jvm, js
  Requires: ssc >=1.60
  URL:      github:example/json-extra@v1.2.0
  Homepage: https://github.com/example/json-extra

  Install:
    //> using dep "io.example/json-extra:1.2.0"
    [jsonExtra](dep:io.example/json-extra:1.2.0)
```

#### `ssc add <name> [<version>]`

Adds an entry to the current project's `ssclib-manifest.yaml` (or front-matter):

```bash
$ ssc add io.example/json-extra
Added io.example/json-extra:1.2.0 to ssclib-manifest.yaml
Run `ssc update` to download and lock.
```

If no `ssclib-manifest.yaml` exists, `ssc add` creates one (or appends to
front-matter `dependencies:` if inside a single-file `.ssc`).

### 3d. Publishing workflow (library author)

1. Write and publish a release (`github:example/my-lib@v1.0.0`).
2. Open a PR against this repository, adding an entry to `registry/packages.yaml`.
3. Open a PR.  CI runs:
   - Schema validation of the YAML entry.
   - `ssc search io.example/my-lib` resolves via the proposed URL.
   - `ssc check <entry_point>` compiles the library's entry point.
4. Maintainer reviews (seconds for well-formed PRs) and merges.
5. CI regenerates the HTML index and search-index.json.
6. Library appears in `ssc search` within minutes.

### 3e. HTML index (GitHub Pages)

The `registry-pages.yml` workflow runs a small build script
(`tools/registry-site/generate.sc`, a plain scala-cli script) that:

- Reads `registry/packages.yaml`.
- Copies it to `registry/site/packages.yaml` for the CLI default URL.
- Generates `registry/site/packages/{group}/{artifact}/index.json` per package.
- Generates `registry/site/search-index.json` (JSON search docs).
- Generates `registry/site/index.html` — a self-contained HTML page with
  embedded JavaScript that loads `search-index.json` and renders
  results client-side.  No framework, no build step for the HTML
  itself — plain DOM + fetch.

`registry/site/` is uploaded with `actions/upload-pages-artifact` and deployed
with `actions/deploy-pages`; the MVP does not create or commit a `CNAME` file.

### 3f. `--refresh` and offline mode

```bash
ssc search json --refresh   # force re-download of registry
ssc search json --offline   # use cached index only; error if no cache
```

Cache path: `~/.cache/scalascript/registry/packages.yaml`.
Cache TTL: fixed at 1 hour. The registry URL is configurable in
`~/.config/scalascript/config.yaml`:

```yaml
registry:
  url: "https://sergey-scherbina.github.io/scalascript/packages.yaml"
```

Users can point `url` at an internal mirror or a private registry file.

## 4. Migration

Nothing to migrate — this is a net-new feature.  Existing `dep:` / `github:`
/ `pkg:` imports continue to work without the registry; the registry is
purely additive (helps find things, not required to use them).

## 5. Phases

### Phase 1 — Registry catalog + `packages.yaml` schema

- Define `packages.yaml` schema (YAML Schema / JSON Schema).
- Seed with 3-5 first-party packages (`io.scalascript/json`,
  `io.scalascript/http`, `io.scalascript/streams`).
- `validate.yml` CI: schema check + `ssc check` on each entry.
- Deliverable: registry catalog exists and validates.

### Phase 2 — `ssc search` / `ssc info` / `ssc add` CLI

- Add subcommands to `tools/cli/src/main/scala/scalascript/cli/Main.scala`.
- `RegistryClient` class: fetch + cache `packages.yaml`; search; resolve URL.
- Tests: mock HTTP server returns fixture `packages.yaml`; search returns
  correct ranked results; `ssc add` writes to manifest.

### Phase 3 — GitHub Pages HTML index

- `tools/registry-site/generate.sc` scala-cli script.
- `registry-pages.yml` GitHub Actions workflow.
- Generates `registry/site/index.html` + `search-index.json` + per-package JSON
  + a copied `packages.yaml`.
- No `CNAME` in the MVP. GitHub Pages serves the project URL.
- Deliverable: browseable web UI, shareable package pages, and a stable no-domain CLI default URL.

### Phase 4 — Private registry support

- `registry.url` config key in `~/.config/scalascript/config.yaml`.
- `--registry <url>` CLI flag on all registry commands.
- Documents pattern for enterprise internal registry (a private GitHub repo
  with the same `packages.yaml` format + GitHub Pages, or a plain HTTPS URL
  to a static file).

## 6. Testing strategy

- Phase 1: schema validation CI catches malformed entries (property-based
  test with arbitrary bad YAML).
- Phase 2: `RegistryClient` unit tests with `com.sun.net.httpserver` mock.
  Cache expiry test (fake clock).  `ssc add` integration test (temp project
  directory).
- Phase 3: snapshot test — given a fixture `packages.yaml`, generated HTML
  contains expected package names and search-index.json is valid lunr input.
- Phase 4: `ssc search --registry <local-file-url>` resolves from custom
  registry.

## 7. Open questions

1. **Version history**: should `packages.yaml` contain only the latest version,
   or a full version list?  Recommendation: latest only for simplicity; older
   versions remain available via direct `github:user/repo@old-tag` URLs.
2. **Removal policy**: if a library is abandoned or broken, how do we remove
   it?  Recommendation: maintainer PR sets `deprecated: true`; CI keeps the
   entry but marks it in search results.  Hard removal by registry maintainers
   only.
3. **Name reservation**: should authors be able to reserve a name before
   publishing?  Recommendation: no reservation in v1; first-merged-PR wins.
4. **Custom domain timing**: defer until after the no-domain GitHub Pages MVP is live.
   Do not block the registry on domain registration or DNS setup.
