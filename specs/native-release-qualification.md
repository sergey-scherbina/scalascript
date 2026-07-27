# Native Release Qualification

## Overview

The `Native Release` workflow builds three GraalVM executables and can publish
them from a version tag, but it has never completed a run. A successful
`native-image` compilation alone is not release evidence: the published archive
must be relocatable, contain the self-hosted v2 frontend data needed at runtime,
and execute the product from the extracted archive without a repository,
Scala CLI, a Scala compiler, or a JVM for ordinary `.ssc` programs.

This contract adds a non-publishing manual qualification path. It exercises the
same platform matrix and validates the final archive on its own runner before
upload. Tag publication remains a separate, more privileged step and may run
only after the same qualification succeeds.

## Interface

### Workflow

`.github/workflows/native-release.yml` supports two events:

- a `workflow_dispatch` event always performs a dry qualification and never
  creates or mutates a GitHub Release;
- a pushed `v*.*.*` tag performs the same qualification and may publish only
  after every matrix leg succeeds.

The build matrix remains:

| Artifact id | Runner | Target |
|---|---|---|
| `ssc-linux-x86_64` | `ubuntu-latest` | Linux x86_64 |
| `ssc-macos-arm64` | `macos-latest` | macOS arm64 |
| `ssc-macos-x86_64` | `macos-15-intel` | macOS x86_64 |

The matrix job has read-only repository credentials. A tag-only publication job
is separate, depends on the complete matrix, and is the only job granted
`contents: write`.

### Archive qualifier

```text
scripts/native-release-qualify <artifact-id> <archive.tar.gz>
```

The command validates and executes the supplied archive. The archive's
directory must also contain the separately uploaded `<artifact-id>` executable;
the qualifier byte-compares it with the extracted `ssc`. It does not read build
outputs, source-tree libraries, or a checked-out example after extraction.
Success is silent except for a final stable summary. Every refusal names the
failed check and prints the relevant expected and actual values.

Each uploaded artifact contains:

```text
<artifact-id>                 # native executable, retained for direct download
<artifact-id>.tar.gz          # relocatable distribution
<artifact-id>.tar.gz.sha256   # checksum sidecar
```

The distribution archive contains:

```text
ssc
lib/ssc-plugin-host.jar
bin/lib/standard/native-front/**
bin/lib/standard/native-front/MANIFEST.sha256
README.md
```

Directories may appear as tar entries. Regular files outside the declared
layout, duplicate paths, absolute paths, `..` traversal, and links are refused.
The checksum sidecar uses the conventional lowercase SHA-256 plus basename
format and must verify before extraction. `MANIFEST.sha256` lists every other
regular file below `native-front` with its relative path and digest; the
qualifier requires the extracted file set and every digest to match it exactly.

## Behavior

- [ ] A manual dispatch builds and qualifies all three declared platform
      artifacts but cannot receive a release-writing token or execute a publish
      step.
- [ ] A version-tag build runs the identical archive qualifier before the
      tag-only publication job can download or publish artifacts.
- [ ] The qualifier refuses a missing file, duplicate entry, unexpected regular
      file, unsafe path, symlink, non-executable `ssc`, direct/extracted binary
      mismatch, missing plugin host, checksum mismatch, wrong process exit,
      wrong stdout, subprocess timeout, unexpected or missing frontend-manifest
      entry, frontend content mismatch, and unexpected ASM fallback with a
      named expected/actual diagnostic.
- [ ] The archive is relocatable. After extraction into a fresh temporary
      directory, with ScalaScript path overrides unset, its native executable
      discovers the bundled standard v2 frontend data without a checkout or an
      absolute build-machine path.
- [ ] The workflow copies the binary, archive, checksum, and qualifier outside
      the checkout, makes the checkout's staged native frontend unavailable,
      and runs qualification from the isolated copy. A build-time absolute
      checkout path therefore fails rather than borrowing files from the runner.
- [ ] `ssc --version` exits zero and identifies ScalaScript plus the v2 default
      runtime.
- [ ] A generated, self-contained `.ssc` probe runs through
      `ssc run --v2 --interpret` and prints the exact expected result.
- [ ] The same probe runs through `ssc run --v2 --bytecode`, prints the same
      result, and does not emit the stable
      `ssc: --bytecode fell back to the VM lane` marker.
- [ ] Ordinary VM and ASM probes need no `java`, `scala-cli`, `scalac`, `javac`,
      repository file, or ambient `SSC_LIB_PATH`/`SSC_STD_PATH`. The plugin host
      is the only archive component allowed to require a JRE.
- [ ] `lib/ssc-plugin-host.jar` is non-empty, has the expected main entry point,
      and its no-argument invocation reaches the stable
      `[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>` diagnostic. This
      qualifies the packaged path and host startup; existing protocol tests
      retain responsibility for native CLI lookup and third-party backend
      semantics.
- [ ] Version, VM, ASM, and plugin-host subprocesses have a cross-platform
      bounded timeout. A hung product is a named qualification failure rather
      than a runner that waits indefinitely.
- [ ] Every matrix artifact and checksum is uploaded only after its runner-local
      post-extraction qualification passes.
- [ ] The completed manual run has successful Linux x86_64, macOS arm64, and
      macOS x86_64 matrix jobs, and
      `scripts/ci-status --workflow native-release.yml --event any --latest`
      exits zero for that exact run.

## Out of Scope

- Creating a real tag, GitHub Release, or public download.
- Code signing, notarization, provenance attestations, SBOM generation, or
  reproducible native-image bytes. These remain required before a public
  production release, but they are distinct from proving that the archive runs.
- Windows native artifacts or cross-compilation.
- Expanding the optional tools/compatibility-tier command surface.
- Re-testing arbitrary third-party plugin behavior; the qualifier proves that
  the shipped subprocess host is present and starts, while the backend SPI
  suites own its protocol semantics.
- Changing ScalaScript language or CoreIR semantics to make a release pass.

## Design

### Compare the shipped object

The qualifier receives the compressed archive, verifies its checksum and path
set, extracts it into a new directory, byte-compares the extracted executable
with the separately uploaded binary, and runs only files below that directory.
The workflow first copies the four qualification inputs to `RUNNER_TEMP`, then
makes the checkout's staged native frontend unavailable. It must not inspect
`dist/archive` or execute the pre-archive binary. This makes missing resources,
lost executable bits, stale archive composition, and absolute-path coupling
observable.

Before compression, packaging hashes the complete staged `native-front` file
set into its own manifest. Qualification compares the extracted set first and
then hashes every file, so an omitted but probe-unused standard module is red
rather than hidden by the arithmetic smoke test.

The e2e test constructs controlled archives and executable stubs. The same
observable is run once in a known-good form and then mutated one dimension at a
time, proving that each refusal would be red if the release were broken.

### Keep qualification and publication separate

The matrix owns checkout, native build, package, qualification, and artifact
upload with `contents: read`. A distinct release job is selected only by a
`v*.*.*` tag, downloads the already-qualified artifacts, and receives
`contents: write`. A boolean workflow input is deliberately not a publication
switch: a manual event is always non-publishing by construction.

### Bundle the self-hosted frontend as runtime data

`RunNativeV2` executes staged `ssc0` programs and reads portable standard
library sources. Those files are runtime data even though the CLI executable is
native. The distribution therefore carries the staged
`bin/lib/standard/native-front` subtree and resolves its installation root from
the extracted executable when no explicit development override is present.
The archive must not capture the checkout path used on the hosted runner.

### Exercise both v2 execution backends

The VM and direct-ASM probes use explicit CLI flags. Equality of stdout is
necessary but not sufficient: the ASM run also refuses the stable fallback
marker, so a VM run wearing the bytecode label cannot qualify the release.

## Decisions

- **Manual dispatch is always dry** — chosen because event identity makes
  publication structurally unreachable. Rejected: a `publish=true` input
  (manual typo or expression coercion could grant the dangerous path).
- **Qualify after compression** — chosen because the archive is the customer
  artifact. Rejected: smoke-testing `dist/archive` before `tar` (cannot catch a
  bad archive command or path set).
- **Run each artifact on its own matrix runner** — chosen because native
  executables are OS/architecture-specific. Rejected: download all archives
  into one Linux verification job (it can inspect but cannot execute macOS).
- **Use the supported Intel runner label** — `macos-15-intel` replaces the
  retired `macos-13` label while preserving x86_64 coverage. Rejected:
  `macos-latest` for both macOS legs (currently selects arm64 and would duplicate
  architecture coverage).
- **Compare the direct download with the archive** — chosen because both are
  uploaded release artifacts. Rejected: qualify only the archive while trusting
  that the separately copied binary came from the same build output.
- **Retain an explicit VM control** — chosen to distinguish frontend/runtime
  packaging failures from ASM admission failures. Rejected: ASM-only smoke
  (cannot localize the failing layer).
- **Checksum, not reproducibility, in this slice** — chosen because integrity of
  the uploaded bytes is independently testable now. Rejected: claiming
  bit-identical native-image output without first normalizing toolchain and
  archive metadata.
- **Bound every product process** — chosen because a hung executable is a
  release failure and GNU `timeout` is absent on stock macOS. The portable
  qualifier uses its existing Python runtime for subprocess deadlines.

## Results

Pre-change baseline on 2026-07-27:

- `gh run list --workflow native-release.yml` returned no runs.
- The tag-only workflow could publish without ever executing the archive.
- Its `macos-13` x86_64 runner label was
  [retired on 2025-12-04](https://github.blog/changelog/2025-09-19-github-actions-macos-13-runner-image-is-closing-down/);
  the supported standard Intel label is `macos-15-intel`.
- The archive held only `ssc`, `lib/ssc-plugin-host.jar`, and an optional
  `README.md`.
- `RunNativeV2.nativeFrontLayout` requires staged
  `bin/lib/standard/native-front` data and an `ssc.lib.path` installation root.
  The archive provided neither runtime data nor a relocatable root bootstrap,
  so native compilation success could not establish a usable v2 distribution.

Implementation measurements and the exact manual run are recorded here during
the verify phase.
