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
- a pushed tag selected by `v*.*.*` performs the same qualification, but may
  publish only when its exact name is stable SemVer
  `v<major>.<minor>.<patch>` with no leading zeroes and every matrix leg
  succeeds. A broader glob match is a failed run, not a release.

Workflow runs are serialized per full Git ref with
`cancel-in-progress: false`. A same-tag rerun cannot interleave release asset
mutation with another run.

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

`<artifact-id>` is an ASCII release identifier matching
`[A-Za-z0-9][A-Za-z0-9._-]*`; the archive basename must be exactly
`<artifact-id>.tar.gz`. Qualification requires Bash 3.2 or newer and Python
3.9 or newer on the runner. Those tools are qualification dependencies, not
runtime dependencies of the shipped `ssc`.

The command validates and executes the supplied archive. The archive's
directory must also contain the separately uploaded `<artifact-id>` executable;
the qualifier byte-compares it with the extracted `ssc`. It does not read build
outputs, source-tree libraries, or a checked-out example after extraction.
Success is silent except for a final stable summary. Every refusal names the
failed check and prints the relevant expected and actual values.

Runtime installation-root precedence is exact:

1. an existing `ssc.lib.path` system property is authoritative;
2. otherwise, a non-empty `SSC_LIB_PATH` is promoted to that property;
3. otherwise, and only in a native-image runtime, the executable discovers its
   bundled root.

JVM launches and explicitly configured native launches must not inspect the
current executable. The native image must initialize every eager
host-path consumer at run time; the package-wide build-time-initialization
default must not snapshot build-runner paths or environment values into the
executable.

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

### Publication helper

```text
scripts/native-release-publish <stable-tag> <artifact-directory>
```

The privileged job calls one versioned repository helper rather than carrying
untested release mutation inline in workflow YAML. The helper requires Bash 3.2
or newer, Python 3.9 or newer, `gh`, a non-empty `GH_TOKEN`, and an explicit
`GH_REPO` in `owner/repository` form. `<stable-tag>` must match the exact stable
SemVer grammar from the workflow contract.

`<artifact-directory>` must be a real directory, not a link, and its complete
top-level entry set must be exactly these nine non-empty regular files:

```text
ssc-linux-x86_64
ssc-linux-x86_64.tar.gz
ssc-linux-x86_64.tar.gz.sha256
ssc-macos-arm64
ssc-macos-arm64.tar.gz
ssc-macos-arm64.tar.gz.sha256
ssc-macos-x86_64
ssc-macos-x86_64.tar.gz
ssc-macos-x86_64.tar.gz.sha256
```

Every checksum sidecar is byte-compared with the lowercase SHA-256 of its
archive in conventional `digest  basename\n` form. The helper then performs a
read-only release-by-tag REST lookup. Only a confirmed HTTP 404 authorizes
creation: a success response means the release already exists, while a missing
or different error status is an ambiguous lookup failure and remains red.

For an absent release the helper makes one exact
`gh release create <tag> ...` CLI invocation with `--repo`, `--verify-tag`,
`--title`, `--generate-notes`, and all nine explicitly ordered paths. Any
non-zero result is a publication failure. It never invokes release edit,
upload, delete, or `--clobber`.

## Behavior

- [ ] A manual dispatch builds and qualifies all three declared platform
      artifacts but cannot receive a release-writing token or execute a publish
      step.
- [x] A version-tag build runs the identical archive qualifier before the
      tag-only publication job can download or publish artifacts.
- [x] A tag publication accepts only exact stable SemVer names such as
      `v2.0.0`; malformed glob matches, prerelease-looking names, leading-zero
      numeric identifiers, branches, and manual dispatches cannot publish.
- [x] Workflow-level concurrency serializes runs for the same full ref without
      cancelling an in-progress run. Independent tags remain parallel.
- [ ] A read-only CI prerequisite executes the compare-first e2e archive and
      publication contracts before any native-image matrix leg. The controlled
      good objects must pass, while every one-dimension mutation must fail at
      its named expected/actual check without contacting GitHub.
- [x] The qualifier refuses a missing file, duplicate entry, unexpected regular
      file, unsafe path, symlink, non-executable `ssc`, direct/extracted binary
      mismatch, missing plugin host, checksum mismatch, wrong process exit,
      wrong stdout (including a `v20` runtime falsely matching `v2`), subprocess
      timeout, unexpected or missing frontend-manifest entry, frontend content
      mismatch, and unexpected ASM fallback with a named expected/actual
      diagnostic.
- [x] Interface and format failures are also compare-first and named: malformed
      artifact id, wrong archive basename, missing checksum sidecar, duplicate,
      unsorted, unsafe, or malformed manifest rows, version stderr, ASM
      non-zero exit/stderr, unreadable checksum/archive/manifest/frontend bytes,
      and an unsupported Python runtime never escape as a raw traceback.
- [ ] The archive is relocatable. After extraction into a fresh temporary
      directory, with ScalaScript path overrides unset, its native executable
      discovers the bundled standard v2 frontend data without a checkout or an
      absolute build-machine path.
- [x] Installation-root configuration preserves
      `ssc.lib.path > SSC_LIB_PATH > bundled-root` precedence. JVM launches and
      native launches with either explicit override do not query
      `ProcessHandle`; a missing/empty environment override falls through to
      native bundled-root discovery.
- [ ] Native-image configuration initializes `os.package$` plus the complete
      `scalascript` and `ssc` application package trees at run time despite the
      existing `--initialize-at-build-time=scalascript` setting. This prevents
      frozen cwd/home/cache/library/tmp paths, persisted random state,
      build-runner values for documented JIT/FASTTIER and v2 depth switches,
      and equivalent future eager state reached through cross-package
      initialization chains.
- [x] The runtime-initialization policy is embedded below
      `META-INF/native-image/` in the CLI artifact and therefore applies to
      ordinary local `cli/graalvm-native-image:packageBin` builds as well as the
      release workflow. Runner-specific builder-memory policy is not embedded
      in that reusable artifact metadata.
- [ ] The shared native-image build settings resolve reflection and resource
      configuration files from the repository root and contain only real
      GraalVM `Feature` implementations. The default local build and the
      release workflow use the same valid settings; CI does not hide broken
      defaults behind workflow-only replacements.
- [x] Packaging reads the actual sbt-native-packager output
      `target/graalvm-native-image/scalascript-cli`, verifies it is executable,
      and renames only the release-facing copy to `ssc`.
- [ ] The workflow copies the binary, archive, checksum, and qualifier outside
      the checkout, makes the checkout's staged native frontend unavailable,
      and runs qualification from the isolated copy. A build-time absolute
      checkout path therefore fails rather than borrowing files from the runner.
- [ ] `ssc --version` exits zero and prints exactly two newline-terminated
      records: a non-empty `ssc <version>` line followed by
      `runtime: v2 (default; --v1 opts back)  ·  jvm <version>`. A prefix such
      as `v20` is not a v2 identity.
- [ ] A generated, self-contained `.ssc` probe runs through
      `ssc run --v2 --interpret` by a relative path from the isolated working
      directory and prints the exact expected result. An absolute probe path is
      insufficient because it can bypass a build-time-frozen `os.pwd`.
- [ ] The same probe runs through `ssc run --v2 --bytecode`, prints the same
      result, and does not emit the stable
      `ssc: --bytecode fell back to the VM lane` marker.
- [ ] Ordinary VM and ASM probes need no `java`, `scala-cli`, `scalac`, `javac`,
      repository file, or ambient `SSC_LIB_PATH`/`SSC_STD_PATH`. The plugin host
      is the only archive component allowed to require a JRE. Runtime probes
      receive a minimal allowlisted environment with an empty `PATH`, isolated
      `HOME`/XDG directories, and their isolated working directory; they do not
      inherit `JAVA_HOME`, `SSC_HOME`, ScalaScript path overrides, or toolchain
      injection variables.
- [ ] `lib/ssc-plugin-host.jar` is non-empty, has the expected main entry point,
      and its no-argument invocation reaches the stable
      `[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>` diagnostic. This
      qualifies the packaged path and host startup; existing protocol tests
      retain responsibility for native CLI lookup and third-party backend
      semantics.
- [x] Version, VM, ASM, and plugin-host subprocesses have a cross-platform
      bounded timeout. A hung product is a named qualification failure rather
      than a runner that waits indefinitely.
- [x] Every matrix artifact and checksum is uploaded only after its runner-local
      post-extraction qualification passes.
- [x] Publication refuses any pre-existing release for the tag and creates a
      fresh release with all nine assets in one exact `gh release create`
      invocation. Only a confirmed release-by-tag HTTP 404 permits that call;
      malformed tags, wrong/missing/linked assets, checksum drift, ambiguous
      lookup failures, argument drift, and create failure are compare-first e2e
      refusals. It never uses `gh release upload --clobber`; an asset upload
      failure cannot delete or mix files from a previously published release.
- [x] The workflow removes the repository-wide `-J-Xmx8g` native-image option
      and uses a single `-J-Xmx5g` builder limit. This stays below the 7 GB
      standard arm64 macOS runner while reserving memory for native/off-heap
      work; duplicate `-Xmx` options are refused by source inspection/lint.
- [ ] The completed manual run has successful Linux x86_64, macOS arm64, and
      macOS x86_64 matrix jobs. `gh run view <run-id> --json jobs` shows the
      qualifier prerequisite and all three matrix legs completed successfully,
      while `Publish qualified tag` is completed/skipped. A cancelled matrix
      leg is red; the intentionally skipped publication job is the proof that
      a manual dispatch remained non-publishing.

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
- Changing the generic `scripts/ci-status` job-selection contract. Until that
  tool distinguishes a guarded, intentionally skipped job from a required
  execution job, exact `gh run view <run-id> --json jobs` evidence is the
  acceptance source for this workflow.
- Changing ScalaScript language or CoreIR semantics to make a release pass.

### Public-release blockers after qualification

The non-publishing matrix run proves that the declared archives execute; it
does not by itself make a public release production-ready. A read-only
supply-chain audit against `origin/main@f24afc1a1` identified the following
mandatory follow-up slices. They are ordered so later trust metadata describes
an already stable release identity and payload:

1. **`native-release-identity-governance`** — bind the exact stable tag to the
   version reported by the executable and package metadata, then require the
   release commit to descend from the protected main line and pass an explicit
   production approval/tag-authenticity policy. The current build version is
   `0.1.0-SNAPSHOT`, while qualification accepts any non-empty version output,
   so tag syntax alone cannot establish artifact identity.
2. **`native-release-compliant-reproducible-payload`** — include the Apache
   license, generated third-party notices, and a component inventory; either
   remove raw executable assets or make them genuinely standalone; publish a
   digest for every payload; pin the effective runner/toolchain/dependency
   inputs; normalize archive metadata; and compare two independent unsigned
   builds before calling the payload reproducible. The current raw executable
   still requires the archive's adjacent frontend tree, and only archives have
   checksum sidecars.
3. **`native-release-trust-metadata`** — generate and validate an SBOM and
   provenance attestation for the final payload, sign the bytes that users
   receive, and Developer-ID sign, notarize, and staple the final macOS
   distributions. These steps must occur after all byte-changing packaging and
   before publication.
4. **`native-release-install-lifecycle`** — replace the stale JVM-only,
   non-verifying installer/Homebrew/Coursier endpoints with a digest/signature
   verifying native path; install atomically into versioned locations; and
   automate upgrade, downgrade, yank, and rollback drills. A create-only
   release prevents silent clobbering but is not a rollback policy.

Repository state measured on 2026-07-28 reinforces the trust/governance
blockers: `gh secret list --app actions` and `gh variable list` return no
entries, and the only configured GitHub environment is `github-pages`. Native
release signing/notarization therefore needs an explicit credential and
protected `production` environment provisioning step; workflow code must not
pretend that absent trust material is optional.

Until all four slices have their own compare-first gates and exact release
evidence, a green qualification run authorizes further engineering only, not a
public production tag.

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
Its mutation table covers the public invocation grammar, checksum sidecar,
archive safety/layout, complete ordered manifest, direct-byte identity, plugin
JAR structure/invocation, exact version/VM/ASM bytes, fallback marker, stderr,
exit status, and all four process timeouts.
The good native stub also refuses a non-extracted executable path, unexpected
argv, a changed probe, a non-isolated working directory, or any poisoned
ScalaScript/Java environment value. The Java stub accepts only
`-jar <extracted-plugin-host>` and proves that the selected path is the regular
file from the extracted archive. This makes isolation and invocation shape
tested observables rather than source-inspection assumptions.

The workflow runs this controlled contract in a small read-only prerequisite
job. Native matrix jobs depend on it, so a false-green regression in refusal
logic cannot qualify a real archive merely because the hosted product happens
to be healthy.

### Keep qualification and publication separate

The matrix owns checkout, native build, package, qualification, and artifact
upload with `contents: read`. A distinct release job is selected only by a
`v*.*.*` tag, downloads the already-qualified artifacts, and receives
`contents: write`. A boolean workflow input is deliberately not a publication
switch: a manual event is always non-publishing by construction.

### Make publication single-writer and fail closed

The workflow concurrency key includes the workflow name and full Git ref.
`cancel-in-progress: false` prevents a newer same-tag run from interrupting an
active publisher, while different release tags retain independent build
capacity.

The release job checks out the exact tag with credential persistence disabled,
downloads the qualified artifacts, and delegates all validation and mutation to
`scripts/native-release-publish`. That helper revalidates `GITHUB_REF_NAME`
against exact stable SemVer, byte-compares the complete nine-file set and
sidecars, and asks the release-by-tag REST endpoint for current state. It
continues only on a parsed HTTP 404. A success response is an existing release;
an authentication, transport, API, or unparseable response is ambiguous and
fails closed rather than being treated as absence.

For a confirmed-fresh tag the helper passes the complete ordered file set to
one `gh release create --verify-tag` call. GitHub CLI creates a draft, uploads
the assets, and publishes only after the uploads complete, so a partial upload
is not exposed as a published release. An operator must investigate and remove
any failed draft before a retry; the workflow never silently clobbers it. The
publication e2e test supplies a fake `gh`, records every argument, and compares
the exact lookup/create transcript before classifying the good case. Separate
mutations prove that malformed tags, file-set/type/checksum drift, existing
releases, ambiguous lookup results, and failed creation cannot report success.

### Bundle the self-hosted frontend as runtime data

`RunNativeV2` executes staged `ssc0` programs and reads portable standard
library sources. Those files are runtime data even though the CLI executable is
native. The distribution therefore carries the staged
`bin/lib/standard/native-front` subtree and resolves its installation root from
the extracted executable when no explicit development override is present.
`ssc.lib.path` remains authoritative; a non-empty `SSC_LIB_PATH` is promoted
before discovery so the bootstrap cannot accidentally mask it. Discovery is
lazy and native-only, so JVM launchers and explicitly configured native
processes do not touch `ProcessHandle`.

The current native-image build initializes the broad `scalascript` package at
build time. Correctness takes priority over speculative startup savings: the
portable CLI metadata requests run-time initialization for the complete
v1 and v2 application namespaces plus os-lib's cwd owner:

```text
os.package$
scalascript
ssc
```

The CLI embeds the exact
`--initialize-at-run-time=os.package$,scalascript,ssc` option in
`META-INF/native-image/scalascript/ssc/native-image.properties`. GraalVM
[recommends embedded native-image configuration](https://www.graalvm.org/jdk21/reference-manual/native-image/overview/BuildConfiguration/)
and documents that
[explicit options can select run-time-initialized classes and packages](https://www.graalvm.org/jdk21/reference-manual/native-image/guides/specify-class-initialization/).
`os.package$` owns `os.pwd`; import and plugin objects eagerly call
cwd/home-derived os-lib APIs; server objects eagerly capture
`java.io.tmpdir` and allocate `SecureRandom`; interpreter objects eagerly read
documented environment and system-property switches. The v2 `ssc.Reader` and
`ssc.Compiler` objects eagerly capture documented depth properties. GraalVM's
security guide warns that
[build-time static state is persisted, including random seeds](https://www.graalvm.org/jdk21/security-guide/native-image/).
Restricting the override to only those audited subpackages is unsafe because
outer objects in other ScalaScript packages can initialize them transitively
at image build time, and a future eager object would silently reopen the same
defect class.

The real native-image matrix is the decisive compatibility check: it must
reject an invalid class-initialization combination or image-heap conflict,
execute the archive through a relative source path, and expose any material
startup regression. A future optimization may replace the broad build-time
rule with a measured allowlist of proven-safe classes; it must not narrow this
run-time policy based only on the current object inventory.

GraalVM JDK 21 package selectors are non-strict when no class has the exact
package name. Conflicting package rules are combined with
[`InitKind.max`](https://github.com/oracle/graal/blob/jdk-21.0.5/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/classinitialization/InitKind.java#L42-L51),
where `RUN_TIME` follows `BUILD_TIME`, through
[`ClassInitializationConfiguration`](https://github.com/oracle/graal/blob/jdk-21.0.5/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/classinitialization/ClassInitializationConfiguration.java#L89-L107).
The same implementation is present in the 21.0.0, 21.0.1, 21.0.2, and
21.0.5 tags. Therefore the embedded `scalascript` run-time rule wins even
though sbt-native-packager places the explicit broad build-time option after
the classpath. The real native build still remains the acceptance proof.

Builder heap is runner policy, not artifact semantics. The workflow removes the
inherited `-J-Xmx8g` and adds `-J-Xmx5g`: the standard arm64 macOS runner has
[7 GB of RAM](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job),
and GraalVM notes that native-image
[actual memory can exceed the configured Java heap](https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildOutput/).
The archive must not capture the checkout path used on the hosted runner.

### Make the native-image invocation self-consistent

The CLI project base is `v1/tools/cli`, so repository-root configuration files
resolve through `../../../native-image-configs`, not `../../native-image-configs`.
The build uses those existing reflection and resource files directly.
`org.graalvm.home.HomeFinder` is an abstract lookup utility, not an
`org.graalvm.nativeimage.hosted.Feature`; passing it through `--features`
aborts feature registration and is removed rather than replaced with an
unproven feature.

The native-image output name comes from the CLI project name,
`scalascript-cli`. The workflow consumes that exact build output, then gives
the copied direct-download artifact and archive entry their stable release
names. Source-side assumptions are checked before dispatch with the effective
sbt settings and filesystem paths; the hosted build and archive execution are
the final observables.

### Exercise both v2 execution backends

The VM and direct-ASM probes use explicit CLI flags. Equality of stdout is
necessary but not sufficient: the ASM run also refuses the stable fallback
marker, so a VM run wearing the bytecode label cannot qualify the release.

## Decisions

- **Manual dispatch is always dry** — chosen because event identity makes
  publication structurally unreachable. Rejected: a `publish=true` input
  (manual typo or expression coercion could grant the dangerous path).
- **Stable SemVer tags only** — chosen because the trigger glob is intentionally
  broad and cannot express numeric grammar. Rejected: treating any
  `v*.*.*` match as a production version (names such as `vfoo.bar.baz` would
  become ordinary releases).
- **Single-writer, confirmed-absent, create-only publication** — chosen because
  release assets must come from one qualified run. Only a parsed REST 404 is
  absence; a generic non-zero `gh release view` result is ambiguous and could
  hide authentication or transport failure. Rejected: `gh release upload
  --clobber` without per-ref concurrency (concurrent or failed reruns can delete
  and mix non-reproducible native assets).
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
- **Runtime-initialize both application namespaces** — chosen because
  cwd/home/tmp, RNG, runtime-toggle, and v2 depth state is host-dependent,
  while cross-package initialization can reach audited objects through an
  apparently unrelated outer object. Rejected: enumerating only the currently
  known host-sensitive subpackages or relying on automatic safe-initialization
  for `ssc` (transitive and future eager state would silently reopen the
  defect).
- **Embed portable native-image policy in the CLI JAR** — chosen so local and
  release builds share the correctness rule automatically. Rejected:
  workflow-only class-initialization flags (they leave the documented local
  native build path defective).
- **Keep builder heap in CI** — chosen because available RAM is a runner
  property. Rejected: embedding `-J-Xmx5g` in `native-image.properties`
  (needlessly constrains larger developer/build machines).
- **Repair shared native-image defaults** — chosen because documented local and
  release builds must execute the same valid invocation. Rejected:
  workflow-only removal of the invalid feature and replacement of missing
  configuration paths (would make CI green while leaving the local contract
  broken).
- **Allowlist the runtime environment** — chosen because deleting a short list
  of known contaminants leaves future Java/toolchain variables ambient.
  Rejected: inheriting the runner environment and unsetting only current
  ScalaScript overrides.

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
- Pre-implementation gate review found that the first version matcher accepted
  `runtime: v20...` as v2, the fake runtime did not prove environment/argv
  isolation, and the e2e refusal suite was not reachable from the workflow.
- Bootstrap review found that `currentExecutable` was evaluated eagerly on JVM
  launches, `SSC_LIB_PATH` could be masked, and
  `--initialize-at-build-time=scalascript` would freeze
  `ImportResolver` path state before the entrypoint bootstrap.
- Expanded bytecode review found eager cwd/home/tmp consumers, four
  build-time `SecureRandom` owners, and nine interpreter objects that read
  runtime switches, in addition to `os.package$.pwd`; package-boundary review
  showed that a narrow override could still initialize those objects through
  outer ScalaScript objects. The v2 `ssc` namespace also contains eager
  property-backed depth limits, and an absolute smoke-test path could hide the
  frozen cwd.
- Effective-setting review found that the native output is named
  `scalascript-cli`, both explicit native-image configuration paths point to a
  non-existent `v1/native-image-configs`, and
  `org.graalvm.home.HomeFinder` does not implement the required hosted
  `Feature` interface. Workflow review also found that inherited `-J-Xmx8g`
  exceeds the 7 GB arm64 macOS runner's physical memory.
- The generic non-CI path in `scripts/ci-status` requires every reported job to
  conclude `success`; it would therefore call the required skipped manual
  publication job red. Exact `gh run view` job evidence is used instead of
  weakening the workflow's permission boundary.
- Tag publication accepted arbitrary `v*.*.*` glob matches, had no per-ref
  concurrency, and replaced assets in an existing release with
  `gh release upload --clobber`. A rerun could therefore mix or delete
  non-reproducible platform assets.

Implementation measurements through `origin/main@5ee331fc2`:

- `8077605b9` landed the archive qualifier, relocatable native bootstrap, and
  embedded run-time initialization policy; `3ade9b1a3` made the successful
  qualifier summary compare the exact archive digest.
- `aecb881ef` added the fail-closed publication helper and its fake-`gh`
  contract; `a3863235e` bounded sidecar size before reading or rendering bytes.
  The publication suite passes 41/41 compare-first cases on macOS system Bash
  3.2, including exact NUL-delimited lookup/create argv, existing release,
  401/403/429/500, network, malformed response, anomalous exit-zero 404, and
  create failure without retry.
- `5ee331fc2` wired manual dispatch, the read-only prerequisite, the
  three-platform matrix, runner-local qualification, per-ref serialization,
  and the separate tag-only write job. Official `actionlint` 1.7.12, YAML
  parsing, all seven `run:` blocks, the exact matrix/permission/action-pin
  assertions, and the 61-case archive qualifier are green locally.
- `NativeImageInstallRootTest` passes 7/7. `cli/packageBin` produces
  `scalascript-cli_3-0.1.0-SNAPSHOT.jar`, whose embedded policy is exactly
  `Args = --initialize-at-run-time=os.package$,scalascript,ssc`.
- Independent publisher/workflow and Graal class-initialization reviews found
  no blocker, high, or medium issue in the landed implementation. The
  publication review's only bounded-output hardening note was closed by
  `a3863235e`.

Still open before dispatch: the shared `build.sbt` defaults point two config
options at non-existent `v1/native-image-configs`, pass the non-`Feature`
`org.graalvm.home.HomeFinder`, and are temporarily owned by the active
`uniml-production-completion` claim. Once that path is released, repair the
shared defaults, rerun the effective-setting gates, dispatch the manual
workflow, and record its exact run id, SHA, and job conclusions here.
