# Backlog

Open and planned milestones — what still needs to be done.
Active in-progress work is in [ACTIVE.md](ACTIVE.md).
Completed work is in [CHANGELOG.md](CHANGELOG.md).

## Distributed Runtime (v1.63 planned)

Spec: [`docs/distributed-runtime.md`](docs/distributed-runtime.md).

ScalaScript has local streams, distributed streams, local/cluster actors,
typed route clients, Dataset workers, object sync routes, and deployment
targets. v1.63 adds one canonical local/remote/distributed runtime model so
users can move between local, remote, and distributed views without hiding
network semantics or deployment constraints.

- [x] **v1.63.0-distributed-runtime-spec** - Canonical spec and backlog.
      Merges the older placement/remoting plan and local/distributed cluster
      architecture into `docs/distributed-runtime.md`. Keeps operation names
      such as `users.get`, code identity, registries, code deployment, and
      worker bundles, while adopting `! Async`, `BasicStreamOps`, typed
      `ActorRef[M]`, `Cluster`, `SeedResolver`, and cluster-aware deploy
      phases. Updated 2026-05-28 with the operational details from
      `docs/cluster-operations.md`: token rotation, persistent cluster config,
      rolling upgrades, multi-region lowering, and HPA/autoscale. Landed
      2026-05-28.
- [x] **v1.63.1-stream-bridge-basic-ops** - Stream bridge and shared safe
      operators: add `runtime/std/streams-bridge.ssc`, `Source[A].distributed`,
      `DStream[A].local`, `DStream[A].localBounded`, `BasicStreamOps[F[_]]`,
      `_dag_sink_local`, and bounded/materialization tests.
- [x] **v1.63.2-typed-actors-remote-spawn** - Typed actors and remote spawn:
      complete `ActorRef[M]`, add `spawnRemote`, `BehaviorRegistry`,
      `cluster_spawn` / `cluster_spawn_ack`, JVM lowering for
      `setClusterAuthToken`, and two-node actor tests. Landed 2026-05-28:
      typed `ActorRef[M]` helpers over `Pid`, `registerBehavior`,
      JSON `cluster_spawn` / `cluster_spawn_ack`, JVM
      `setClusterAuthToken` lowering, local interpreter coverage, jar-gated
      two-node CLI smoke, and `examples/actors-typed-remote-spawn.ssc`.
- [x] **v1.63.3-cluster-capability-seed-code-identity** - Cluster capability,
      seed discovery, and code identity: add `Cluster`, `SeedResolver`,
      `.ssc` / `.sscc` code identity, `cluster:` and registry front matter,
      `cluster Demo:` lowering, and diagnostics for missing handlers/codecs
      and code mismatch. Landed 2026-05-28: backend SPI `Cluster`,
      `SeedResolver`, `CodeIdentity`; ScalaScript `ClusterCapability`,
      `SeedResolver.staticList`, `clusterOf`, `resolveSeeds`, `codeIdentity`,
      `assertCodeIdentity`; interpreter static seed resolution and clear
      diagnostics for non-static resolver descriptors; typed `cluster:` /
      registry front matter in AST/IR/`.sscc`; DNS/K8s seed resolver runtime.
      Missing registry function/source/behavior targets are rejected at parse
      time; top-level `cluster Demo:` blocks lower into `ClusterDecl`
      metadata; missing registry request/response/args types are rejected.
- [x] **v1.63.4-remote-registries-async-rpc** - Remote registries and async
      RPC: compile `@remote` / `remote def` / manifest handlers into
      `RemoteHandlerRegistry`, add `Remote.function[A, B](name)` returning
      `B ! Async | RemoteCallError`, add `remoteStub[Api]`, and support
      in-process/HTTP/WS transports with JSON fallback and future `WireCodec`.
      Landed 2026-05-28: backend SPI `RemoteHandlerRegistry`,
      `RemoteHandlerInfo`, and `RemoteCallError`; interpreter lowering for
      manifest `remoteHandlers:` entries into a local registry; `std.remote`
      plus `remote-plugin` for `Remote.function`, `remoteCall`,
      `remoteTryCall`, and `remoteHandlers()`; POST HTTP JSON fallback routes
      for handlers with `path:`; tests and `examples/remote-registry-rpc.ssc`.
      Remaining follow-ups: `@remote` / `remote def` source sugar,
      `remoteStub[Api]`, effect-row async lowering, WebSocket/internal-wire
      transport, and binary `WireCodec[A]` negotiation.
- [x] **v1.63.4b-remote-sugar-stubs-wire** - Remote RPC follow-ups after the
      registry base: lower `@remote` / `remote def` source declarations into
      `remoteHandlers:`, add `remoteStub[Api]`, make generated calls return
      `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport,
      and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback.
      Landed 2026-05-28 for source `@remote(name = ..., path = ...) def` and
      simple `remote def echo(...)` lowering into `remoteHandlers:` metadata,
      parser validation, and example coverage. Remaining pieces split into
      `v1.63.4c`.
- [x] **v1.63.4c-remote-stubs-async-wire** - Remote RPC stubs and transports:
      add `remoteStub[Api]`, make generated calls return
      `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport,
      and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback.
      Landed 2026-05-28 for explicit `Remote.http[A, B](url)` /
      `remoteHttpFunction` POST HTTP JSON fallback client calls with typed
      `RemoteCallError` results. Remaining typed stubs, async lowering,
      WebSocket/internal-wire, and binary `WireCodec[A]` split into
      `v1.63.4d`.
- [x] **v1.63.4d-remote-stubs-async-wire** - Typed remote RPC stubs and binary
      transports: add `remoteStub[Api]`, make generated calls return
      `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport,
      and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback.
      Landed 2026-05-28 partial bridge: `path:` remote handlers derive
      generated `RemoteRpc` typed HTTP client metadata that reuses the existing
      JS/JVM typed-route client codegen. Remaining pieces split into
      `v1.63.4e`.
- [x] **v1.63.4e-remote-trait-stubs-wire** - Remote RPC remaining transports:
      add trait-shaped `remoteStub[Api]`, make generated calls expose
      `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport,
      and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback.
      Landed 2026-05-29 partial runtime stub: `Remote.stub(baseUrl)` /
      `RemoteStub` provide path-based HTTP JSON fallback `function`, `call`,
      and `tryCall` helpers over the same remote HTTP transport. Remaining
      compile-time trait-shaped stub, async, WebSocket/internal-wire, and
      binary codec pieces split into `v1.63.4f`.
- [x] **v1.63.4f-remote-trait-stubs-wire** - Remote RPC compile-time stubs and
      binary transports: add trait-shaped `remoteStub[Api]`, make generated
      calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire
      transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON
      fallback.
      Landed 2026-05-29 surface syntax: `remoteStub[Api](baseUrl)` and
      `Remote.stub[Api](baseUrl)` accept a forward-compatible API type argument
      while returning the path-based `RemoteStub` facade. Generated trait
      methods, async lowering, WS/internal-wire, and binary codec negotiation
      split into `v1.63.4g`.
- [x] **v1.63.4g-remote-trait-methods-wire** - Remote RPC generated trait
      methods and binary transports: derive callable methods for
      `remoteStub[Api]`, make generated calls expose
      `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport,
      and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback.
- [x] **v1.63.5-cluster-runner-worker-bundles** - Cluster runner and worker
      bundles: `ssc cluster run/package/status/handlers/stop`, worker bundle
      packaging with code identity and registry metadata, roles, advertised
      URLs, auth-token wiring, deploy-target integration, and two-local-process
      smoke tests.
- [x] **v1.63.6-stream-actor-placement-adapters** - Stream and actor placement
      adapters: `Source[A].remote`, `RemoteSource[A].local`,
      `RemoteSource[A].distributed`, `DStream[A].remote`, WebSocket remote
      streams with JSON fallback, SSE constraints, local proxy actors, and
      router/sharded/role actor groups.
- [x] **v1.63.7-cluster-aware-deploy-ops** - Cluster-aware deployment and
      operations: `ClusterTarget`, K8s StatefulSet/headless Service/token
      Secret, `rotateClusterToken` with `token_rotate` / `token_rotate_ack`
      and quorum overlap, `clusterConfigSet/Get` persistence through
      `StateBackend`, `Deploy.rollingCluster`, `FaultToleranceConfig`
      multi-region lowering, K8s HPA/autoscale emission through `HpaConfig`,
      and Docker Compose target.
- [x] **v1.63.8-dynamic-code-ops-hardening** - Dynamic code shipping and ops
      hardening: signed worker bundles, remote artifact cache, dependency
      verification, sandbox/resource policy, audit log, unload/rollback,
      mixed-version placement after wire/schema compatibility, metrics/tracing,
      circuit breakers, load shedding, and production cookbook.

## Distributed Wire Protocol (v1.62 planned)

Spec: [`docs/distributed-wire-protocol.md`](docs/distributed-wire-protocol.md).

Internal ScalaScript-to-ScalaScript distributed traffic currently has several
JSON-shaped paths: actor WebSocket envelopes, typed route clients/RPC,
Dataset/MapReduce worker messages, and object sync routes. v1.62 introduces an
opt-in common wire layer with JSON fallback plus MsgPack and CBOR binary
profiles. Binary stays opt-in until JVM/interpreter/JS/browser/Electron
conformance is green.

- [x] **v1.62.0-distributed-wire-spec** - Spec and backlog.
      Defines covered surfaces, JSON/MsgPack/CBOR profiles, opt-in `wire:`
      config, WS/HTTP negotiation, JS/browser support from the first
      implementation phase, same-version-only binary compatibility, security,
      compression, resource limits, observability, and staged implementation
      plan. Landed 2026-05-28.
- [x] **v1.62.1-wire-core** - Shared wire runtime:
      `WireValue`, `WireEnvelope`, `WireCodec[A]`, errors, limits,
      negotiation types, front-matter/CLI parsing, JSON/MsgPack/CBOR codec
      profiles for JVM/interpreter and JS/browser, and cross-format golden
      vectors.
- [x] **v1.62.2-actors-binary-ws** - Actor cluster binary WebSocket:
      `ssc-actors-v2.<format>` subprotocols, binary WS frames for user
      messages plus registry, heartbeat, gossip, leader, pub/sub, config,
      drain, metrics, and phi-vector envelopes; JSON v1 fallback preserved.
- [x] **v1.62.3-typed-rpc-binary** - Typed route clients/RPC binary
      negotiation: generated HTTP `Accept`/`Content-Type` support for
      `application/vnd.scalascript.wire+msgpack` and `+cbor`, JSON fallback,
      binary WS subscription frames, and text/base64 SSE fallback.
- [x] **v1.62.4-dataset-binary-partitions** - Distributed Dataset/MapReduce
      binary partitions and shuffle: route `DatasetWirePartition` through
      `WireCodec[A]`, add chunking for large partitions, and run
      distributed-map/shuffle conformance with JSON, MsgPack, and CBOR.
      Landed 2026-05-29: `DatasetWire` wraps `DatasetWirePartition` in
      `WireEnvelope(protocol = "dataset")`, encodes/decodes JSON, MsgPack, and
      CBOR envelopes, preserves JSON numbers exactly, and chunks/reassembles
      large partitions at element boundaries with `chunk-id` / `chunk-index` /
      `chunk-count` headers. Runner transport selection split into
      `v1.62.4b`.
- [x] **v1.62.4b-dataset-runner-binary-wire** - Distributed Dataset/MapReduce
      runner binary transport selection: wire `runDistributedWire` /
      `runDistributedShuffleWire` actor messages to use `DatasetWire` envelopes
      when `wire.dataset` selects MsgPack/CBOR, retain JSON fallback, and add
      distributed map/shuffle conformance under JSON, MsgPack, and CBOR.
      Landed 2026-05-29 partial runner boundary: `DistributedDataset.run` /
      `runShuffle` accept `wireFormat` and round-trip input/output
      `DatasetWirePartition` values through `DatasetWire` for MsgPack/CBOR
      conformance while retaining existing actor messages. Direct binary actor
      frame selection split into `v1.62.4c`.
- [x] **v1.62.4c-dataset-actor-binary-frames** - Distributed Dataset/MapReduce
      direct binary actor frames: send `DatasetWire` envelope bytes in runner
      worker messages when `wire.dataset` selects MsgPack/CBOR, retain JSON
      object fallback, and add local actor map/shuffle conformance under all
      three formats.
      Landed 2026-05-29: `runDistributedWire`, `runDistributedShuffleWire`,
      and `DistributedDataset.run/runShuffle` route non-JSON `wireFormat`
      through `DatasetWire` binary actor frames for partition, shuffle-bucket,
      and key-result messages while preserving JSON object fallback.
- [x] **v1.62.5-dstream-native-wire** - Native DStream runner wire
      integration: binary element batches, watermarks, triggers, side inputs,
      side outputs, checkpoint metadata, and errors; external Spark/Kafka/
      Flink/Beam protocols remain unchanged.
- [x] **v1.62.6-object-sync-binary** - Client/server object-sync binary
      payloads for generated ScalaScript clients and servers; public/debug
      JSON routes remain available.
- [x] **v1.62.7-wire-security-ops** - Security, compression, and operations:
      HMAC frame signatures, session ids, sequence numbers, replay windows,
      gzip/zstd negotiation, mTLS hooks, limits enforcement, and metrics.
- [x] **v1.62.8-wire-compatibility** - Pre-stable compatibility/evolution:
      schema-id hashing, additive-change rules, default/unknown-field policy,
      old/new vector tests, and explicit mixed-version opt-in.

## Architecture & Extensibility Roadmap (v1.x–v2.x)

Cross-cutting improvements to make ScalaScript easier to extend, consume, and
distribute — identified in the 2026-05-28 architectural review.  Ten themes
(A–J), roughly ordered by impact and risk.  Companion plan:
`~/.claude/plans/glowing-swinging-river.md`.

### Theme C — Distribution ecosystem (multi-channel, not Maven-only)

- [x] **arch-distribution-p1** — `DepResolver` SPI + `GithubReleaseResolver`:
  refactor `ImportResolver` into a pluggable registry; add `github:user/repo@tag`
  scheme; `DepCache` with sha256 pin; tests against mock GitHub API.
  Spec: `docs/arch-distribution.md §5 Phase 1`.
  Landed 2026-05-29: `DepResolver`/`DepSpec`, content-addressed `DepCache`,
  built-in `GithubReleaseResolver`, `ImportResolver` dispatch for `github:`,
  `sha256:` suffix pins, and mock GitHub API tests.

- [x] **arch-distribution-p2** — Coursier wiring + JitPack:
  `MavenDepResolver` using Coursier for `dep:` scheme; `JitpackResolver` as
  thin Coursier repo wrapper; tests with embedded local Maven fixture.
  Spec: `docs/arch-distribution.md §5 Phase 2`.
  Landed 2026-05-29: Maven-shaped `dep:` coordinates dispatch to Coursier
  command wiring; legacy `dep:org/name:version` remains on dep-sources;
  `jitpack:` enables the JitPack repository; tests use a local Maven-layout
  fixture and fake Coursier command for deterministic coverage.

- [ ] **arch-distribution-p3** — First-party Maven Central publication
  (deferred; not queued):
  `project/Publishing.scala`; `io.scalascript` group ID unified; publish
  `scalascript-core`, `scalascript-runtime`, `sbt-scalascript` on tag push;
  sbt Plugin Portal registration. Deferred until Sergiy explicitly asks to
  publish to Maven Central, sbt Plugin Portal, or other official centralized
  repositories.  Spec: `docs/arch-distribution.md §5 Phase 3`.

- [x] **arch-distribution-p4** — Community plugin starter template:
  `templates/plugin/` with GitHub Actions release workflow; `ssc new --template plugin`;
  new `docs/community-plugins.md`.  Spec: `docs/arch-distribution.md §5 Phase 4`.
  Landed 2026-05-29: bundled plugin template resources, `NewProject`
  scaffolder, `ssc new <name> --template plugin`, release workflow,
  community plugin guide, and CLI template unit test.

### Theme D — sbt-scalascript plugin completion

- [x] **arch-sbt-plugin-p1** — Source convention + `sscCompile`:
  `sscSourceDirectories` setting; `sscCompile` task forks `ssc build`; wire
  into `Compile / compile`; scripted test: `sbt compile` compiles `.ssc` files.
  Spec: `docs/arch-sbt-plugin.md §5 Phase 1`.
  Landed 2026-05-29: added `SscRunner`, `sscSourceDirectories`, `sscBackend`,
  `sscExtraArgs`, config-scoped `sscArtifactDir`, `Compile / sscCompile`, and
  `Compile / compile` wiring; scripted `compile-sources` verifies `sbt compile`
  invokes `ssc build --incremental`.

- [x] **arch-sbt-plugin-p2** — `sscLink` + `packageBin`:
  `sscLink` task forks `ssc link`; wire into `Compile / packageBin`; scripted
  test: `sbt package` produces runnable JAR.
  Spec: `docs/arch-sbt-plugin.md §5 Phase 2`.
  Landed 2026-05-29: added `sscLinkedJar`, `Compile / sscLink`, link command
  wiring through `SscRunner`, skip behavior for projects without `.ssc`
  artifacts, and scripted `package-link` coverage for `sbt package`.

- [x] **arch-sbt-plugin-p3** — Test integration:
  `SscTestFramework`; `sscTest` forks `ssc test --output-format junit-xml`;
  JUnit XML parsing → sbt `TestResult`; scripted test: `sbt test` discovers
  and runs `.ssc` tests.  Spec: `docs/arch-sbt-plugin.md §5 Phase 3`.
  Landed 2026-05-29: added `sscTestResultsDir`, `Test / sscTest`, JUnit XML
  parsing in `SscTestFramework`, `Test / test` dependency wiring, and scripted
  `test-integration` coverage for `sbt test`.

- [x] **arch-sbt-plugin-p4** — REPL / Run / Watch + BSP wiring:
  `sscRepl`, `sscRun`, `sscWatch` tasks; `BspIntegration` emits
  `.bsp/scalascript.json` for Metals/IntelliJ.
  Spec: `docs/arch-sbt-plugin.md §5 Phase 4`.
  Landed 2026-05-29: added interactive `SscRunner`, `sscRepl`, `sscRun`,
  `sscWatch`, `sscBspSetup`, `BspIntegration`, and scripted `dev-tools`
  coverage for command wiring plus BSP file emission.

### Theme E — `ssc new` + standalone installation

- [x] **arch-ssc-new-p1** — `ssc new` subcommand + `app`/`lib` templates + Coursier channel:
  `NewProject.scala` in CLI; `app`, `lib` templates bundled in `ssc.jar`;
  Coursier channel JSON at `releases.scalascript.io`; `sbt cli/assembly` fat JAR.
  Spec: `docs/arch-ssc-new.md §5 Phase 1`.
  Landed 2026-05-29: changed `ssc new` default template to `app`, added bundled
  `app` and `lib` templates, added `releases/coursier.json`, documented the
  existing `cli/assembly` fat JAR path, expanded `NewProjectTest`, and fixed
  fresh `pluginApi` and `PluginSpec` Scala 3.8.3/sbt compatibility blockers
  found while verifying the CLI module.

- [x] **arch-ssc-new-p2** — Additional templates + Homebrew tap + curl installer:
  `plugin`, `dsl`, `web-app`, `wasm-app` templates; Homebrew tap formula;
  `curl | sh` installer; `README.md` Getting Started updated.
  Spec: `docs/arch-ssc-new.md §5 Phase 2`.
  Landed 2026-05-29: added bundled `dsl`, `web-app`, and `wasm-app` templates
  (`plugin` already existed), repo-local Homebrew formula source,
  `releases/install.sh`, documentation updates, and `NewProjectTest` coverage.

- [x] **arch-ssc-new-p3** — Standalone docs update:
  `docs/getting-started-standalone.md`; `docs/community-plugins.md`;
  `docs/user-guide.md §Installation` updated; `install.sh` gets `--dev` flag.
  Spec: `docs/arch-ssc-new.md §5 Phase 3`.
  Landed 2026-05-29: added standalone getting-started guide, updated user guide
  and community plugin docs, and made root `install.sh` developer-only via
  `--dev` with standalone install guidance by default.

### Theme B — Build-time registry consolidation

- [x] **arch-build-registry-p1** — `PluginSpec` in `build.sbt`:
  introduce `PluginSpec` case class; migrate all ~20 plugins; all five derived
  lists (cli deps, installBin, pluginPkgs, aggregate, pluginTests) computed from it;
  `build.sbt` shrinks ~200 lines.  Spec: `docs/arch-build-registry.md §5 Phase 1`.
  ✓ Landed 2026-05-29.

- [x] **arch-build-registry-p2** — Runtime `PluginRegistry` unification:
  new `PluginRegistry` trait in `backend/spi`; `BackendRegistry` implements it;
  `PluginManifest`/`SubprocessBackend` → `SubprocessPlugin` strategy;
  `LocalRegistry` absorbed into `RemotePluginInstaller`.
  Spec: `docs/arch-build-registry.md §5 Phase 2`.
  ✓ Landed 2026-05-29: added the SPI facade and source hierarchy, implemented it
  in `BackendRegistry`, introduced shared `RemotePluginInstaller`, and routed CLI
  plugin install plus `pkg:` auto-install through the shared installer.

### Theme A — Stable Plugin SPI

- [x] **arch-stable-spi-p1** — `scalascript-plugin-api` module:
  new `runtime/scalascript-plugin-api/` sbt subproject; `PluginValue`,
  `PluginError`, `PluginComputation` opaque aliases; `JsonCodec`; `PluginContext`
  as full capability re-export; all existing plugins add dep (no code changes yet).
  Spec: `docs/arch-stable-spi.md §5 Phase 1`. ✓ Landed 2026-05-29.

- [x] **arch-stable-spi-p2** — Capability decomposition + 3 showcase plugins:
  `HttpCap`, `WsCap`, `DbCap`, `StorageCap`, `ValidateCap`, `MountCap`;
  `NativeImpl.eval` typed; `LegacyNativeContext` shim; migrate `json-plugin`,
  `http-plugin`, `auth-plugin`.  Spec: `docs/arch-stable-spi.md §5 Phase 2`.
  ✓ Landed 2026-05-29: added capability traits, `LegacyNativeContext`,
  `PluginNative.eval`, representative typed-bridge intrinsics in json/http/auth
  plugins, and fixed auth `verifyPassword`.

- [x] **arch-stable-spi-p3** — Full migration of all 16 `*Intrinsics.scala`:
  remove `LegacyNativeContext`; delete `isStdPluginInterpreterTest` filter;
  CI classpath check rejects `scalascript/interpreter/` in plugin subprojects.
  Spec: `docs/arch-stable-spi.md §5 Phase 3`.

### Theme F — DSL platform hooks

- [x] **arch-dsl-hooks-p1** — `InterpolatorRegistry` + first migration:
  `InterpolatorImpl` trait; `Backend.interpolators` field; Typer / EvalRuntime /
  JvmGen / JsGen / CapabilityCheck consult registry; migrate `json"…"` and
  `html"…"`.  Spec: `docs/arch-dsl-hooks.md §6 Phase 1`.
  ✓ Landed 2026-05-29.

- [x] **arch-dsl-hooks-p2** — `PreprocessorRegistry`:
  `Preprocessor` trait; `PreprocessorRegistry`; 5 existing preprocessors
  converted to registered instances; `Parser.parseScalaWithDiagnostic` uses it.
  Spec: `docs/arch-dsl-hooks.md §6 Phase 2`.
  ✓ Landed 2026-05-29.

- [x] **arch-dsl-hooks-p3** — `SourceLanguage` built-in migration:
  `html`, `css`, `sql`, `xml`, `javascript` fenced tags become
  `SourceLanguagePlugin` implementations; `Lang.scala` routing removed.
  Spec: `docs/arch-dsl-hooks.md §6 Phase 3`.
  ✓ Landed 2026-05-29: registered bundled SourceLanguage plugins for
  `javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware `transaction`
  alongside existing `scala`/`html`/`css`; added attrs-aware `compileBlock`
  overload; Normalize routes through the registry with core-only
  SQL/transaction fallbacks.

- [x] **arch-dsl-hooks-p4** — `InterpolatorCheckRegistry`:
  `InterpolatorCheck` trait; `MarkupInterpolatorCheck` migrated; plugin
  `xml-plugin` registers compile-time check.
  Spec: `docs/arch-dsl-hooks.md §6 Phase 4`.

### Theme H — Library Modularity

Identified 2026-05-28. Six concrete gaps in the library system: no multi-file
pure-ScalaScript package format, no transitive dep propagation, no access
control, namespace collision risk, no API lifecycle annotations, no versioning
enforcement.  Full analysis in `docs/arch-library-modularity.md`.

- [x] **arch-lib-p1** — `@deprecated` + `@experimental` annotations:
  new annotations in `Annotation.scala`; typer emits warnings at call sites;
  `--fatal-warnings` flag; 6+ tests.
  Spec: `docs/arch-library-modularity.md §6 Phase 1`.

- [x] **arch-lib-p2** — `@internal` access control:
  `@internal` parsed + stored on definitions; cross-package check in Typer;
  error with clear message including source package name; per-definition and
  per-heading granularity; 8+ tests.
  Spec: `docs/arch-library-modularity.md §6 Phase 2`.

- [x] **arch-lib-p3** — Namespace collision detection:
  `ImportResolver` tracks name contributions per import; warning on collision;
  `--strict-namespaces` flag for error; qualified import syntax
  `[Name from alias](dep:...)`; 6+ tests.
  Spec: `docs/arch-library-modularity.md §6 Phase 3`.

- [x] **arch-lib-p4** — `ssclib` format + `ssc package --lib`:
  `SsclibManifest` YAML schema; `.ssclib` ZIP format (`src/` + optional `ir/`);
  `ssc package --lib` CLI command; `ImportResolver` unpacks archives; 8+ tests.
  Spec: `docs/arch-library-modularity.md §6 Phase 4`. ✓ Landed 2026-05-29.

- [x] **arch-lib-p5** — Transitive deps + lockfile:
  BFS dep resolution from `ssclib-manifest.yaml`; conflict resolution
  (latest-wins default); `ssc-lock.yaml` generation; `ssc update`; `--strict-deps`
  flag; cycle detection; 10+ tests.
  Spec: `docs/arch-library-modularity.md §6 Phase 5`. ✓ Landed 2026-05-29.

- [x] **arch-lib-p6** — Pre-compiled IR in `.ssclib` + compat check (v2.x):
  `ssc package --lib --precompile` → `.scim` in `ir/`; `ImportResolver` prefers
  pre-compiled; `ssc check-compat old.ssclib new.ssclib` reports API breakage.
  Spec: `docs/arch-library-modularity.md §6 Phase 6`. ✓ Landed 2026-05-29:
  `--precompile` writes `ir/*.scim`, `check-compat` reports removed/changed
  public symbols with source fallback, and `SsclibPackageCliTest` covers both.

### Theme I — Package Registry (discoverability)

Identified 2026-05-28. Without a registry the ecosystem cannot grow: users
cannot find libraries, authors cannot reach users.  Solution: GitHub repo +
GitHub Pages static site, zero server infrastructure, PR-based publishing.
Full spec: `docs/arch-registry.md`.

- [x] **arch-registry-p1** — Registry repository + `packages.yaml` schema:
  create `github.com/scalascript/registry`; define YAML schema; seed with
  3-5 first-party packages; `validate.yml` CI (schema check + `ssc check`).
  Spec: `docs/arch-registry.md §5 Phase 1`.

- [x] **arch-registry-p2** — `ssc search` / `ssc info` / `ssc add` CLI:
  `RegistryClient` (fetch + cache `packages.yaml`, TTL 1h); `ssc search <query>`
  (local search with ranking); `ssc info <name>`; `ssc add <name>` writes to
  manifest; mock-HTTP tests.  Spec: `docs/arch-registry.md §5 Phase 2`.

- [x] **arch-registry-p3** — GitHub Pages HTML index:
  `tools/registry-site/generate.sc` scala-cli script; `publish.yml` workflow;
  `site/index.html` (client-side lunr.js search); per-package JSON pages;
  `registry.scalascript.io` CNAME.  Spec: `docs/arch-registry.md §5 Phase 3`.

- [x] **arch-registry-p4** — Private registry support:
  `registry.url` config in `~/.config/scalascript/config.yaml`; `--registry
  <url>` CLI flag; enterprise internal mirror documentation.
  Spec: `docs/arch-registry.md §5 Phase 4`.

### Theme J — Lightweight FFI (@jvm / @js + glue.jar)

Identified 2026-05-28. Community libraries cannot call Java or JS APIs today —
only `std/` plugins can.  Two-tier FFI closes the gap without requiring a full
`BackendRegistry` plugin.  Full spec: `docs/arch-ffi.md`.

- [x] **arch-ffi-p1** — `@jvm("expr")` annotation + JVM codegen:
  `JvmInline` annotation AST node; annotation parser; `JvmGen` emits inline
  expression as method body; `$N` argument substitution; `CapabilityCheck`
  errors on `@jvm`-only def called from JS; `examples/ffi-inline.ssc`; 10+ tests.
  Spec: `docs/arch-ffi.md §6 Phase 1`.

- [x] **arch-ffi-p2** — `@js("expr")` codegen + interpreter behaviour:
  `JsGen` emits `@js("...")` as function body; `@interpreterUnsupported`
  annotation + clear error; cross-backend parity tests (both `@jvm` + `@js`).
  Spec: `docs/arch-ffi.md §6 Phase 2`.

- [x] **arch-ffi-p3** — `jvm/glue.jar` in `.ssclib` + `ssc package --lib --jvm-glue`:
  `ssclib-manifest.yaml` `glue.jvm`/`glue.js` fields; `ImportResolver` adds
  `glue.jar` to JVM classpath; `ssc package --lib --jvm-glue <jar>`; integration
  test with glue fixture.  Spec: `docs/arch-ffi.md §6 Phase 3`.
  _Prerequisite: arch-lib-p4 (`.ssclib` format)._

- [x] **arch-ffi-p4** — `js/glue.js` preamble injection + `META-INF/services` in glue.jar:
  JS codegen injects `js/glue.js` before `.ssc` output; `glue.jar`
  `META-INF/services` loaded into `BackendRegistry` → interpreter support;
  end-to-end JS test with `glue.js`-using library.
  Spec: `docs/arch-ffi.md §6 Phase 4`.

### Theme G — Metaprogramming v2.x (deferred)

- [x] **arch-meta-v2-p3** — Cross-module `inline` expansion: ✓ Landed 2026-05-29.
  `ExportedSymbol` carries `isInline`/`inlineParamNames`/`inlineBodySource`;
  `InterfaceExtractor` populates them; `Linker.expandInlineSource` does
  lambda-lifting expansion in `CodeBlock.source`; 16 tests.
  Spec: `docs/arch-metaprogramming-v2.md §4 Phase 3`.

- [x] **arch-meta-v2-p4** — Restricted `QuotedMacro[A]` surface: ✓ Landed 2026-05-29.
  First slice implements parser preprocessing for `${ impl('x) }` entrypoints
  and `'{ $x + ... }` quoted bodies, `.scim` `MacroImplRef` metadata,
  `MacroImpl` IR, and link-time expansion for direct quoted-expression macro
  bodies. Runtime/interpreter parity now supports the same direct quoted-body
  subset plus `Expr[A].asValue` / `Expr[A].asTerm`. Richer quoted terms,
  compile-time constant folding, unsupported-body diagnostics, and broader
  backend conformance remain planned follow-ups.
  Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`.

- [x] **arch-meta-v2-p4b** — Restricted quoted macro runtime parity: ✓ Landed 2026-05-29.
  Parser helper lowering carries quoted parameter names and runtime values;
  the interpreter registers lightweight `Expr`, `QuotedContext`, macro quote,
  splice, and quote-expression helpers; direct quoted macro bodies now run under
  `ssc run`; `Expr.asValue` returns `Option[A]`, and `Expr.asTerm` returns an
  opaque `ScalaScriptTerm` with `name` / `value`. Example:
  `examples/quoted-macro-interpreter.ssc`. Spec:
  `docs/arch-metaprogramming-v2.md §4 Phase 4`.

- [x] **arch-meta-v2-p4c** — Restricted quoted macro diagnostics: ✓ Landed 2026-05-29.
  Parser preprocessing lowers unsupported entrypoints such as `${ impl(x) }`
  to an explicit diagnostic helper instead of silently treating them as
  expandable macros; the interpreter reports `quoted macro error: ...`;
  linker normalization rejects non-quoted macro bodies and explains that the
  restricted subset must return a direct quoted expression such as
  `'{ $x + 1 }`. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`.

- [x] **arch-meta-v2-p4d** — Restricted quoted macro richer unsupported-body diagnostics: ✓ Landed 2026-05-29.
  Linker unsupported-body diagnostics now classify common restricted-macro
  misses: `Expr.asValue match` explains compile-time branching is not
  implemented yet; `Expr(...)` explains that link-time expansion requires
  direct quote syntax today; nested/non-top-level quotes and splices outside
  a direct quoted expression get targeted guidance while preserving direct
  quoted-expression expansion. Spec:
  `docs/arch-metaprogramming-v2.md §4 Phase 4`.

- [x] **arch-meta-v2-p5** — Full `Mirror`-based user typeclass derivation: ✓ Landed 2026-05-29.
  Interpreter/runtime slice registers summon-able `Mirror.Of[T]`,
  `Mirror.ProductOf[T]`, and `Mirror.SumOf[T]`; exposes `Mirror.of[T]`;
  carries product/sum metadata (`label`, `elemLabels`, `elemTypes`,
  `variants`, `fromProduct`, `ordinal`); and supports user-defined
  `derived(m: Mirror)` typeclasses from `derives`. Source-level `inline match`
  over `Mirror.Product/Sum` and broader backend conformance remain planned
  follow-ups. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 5`.

---

## Compiler extensibility roadmap

A cross-cutting note tying together the SPI followups
([`docs/spi-followups-plan.md`](docs/spi-followups-plan.md)), the
intrinsic-module extraction direction
([`docs/spi-intrinsics-design.md`](docs/spi-intrinsics-design.md)),
and the "deflation" benefit on the three large codegens.

### Today's pattern-matching debt

Every code generator hardcodes platform intrinsics as match arms on
`Term.Name`:

  - `backend-jvm/JvmGen.scala`     — 4500 LOC, ~⅓ is HTTP/WS/auth
    inlined match cases.
  - `backend-js/JsGen.scala`       — 5400 LOC, similar split.
  - `backend-interpreter/Interpreter.scala` — 4500 LOC, dozens of
    `nativeP("route")` / `nativeP("onWebSocket")` / … blocks.

Costs of this shape:

  - Adding a platform primitive touches three files.
  - No cross-backend parity check at build time; conformance suite
    catches misses post-hoc.
  - Individual intrinsics aren't independently testable.
  - Third-party platform extensions impossible — the code lives
    inside `core/`, not in plugins.

### What the SPI followups deliver

Stages already designed and planned in `docs/spi-followups-plan.md`
+ `docs/spi-intrinsics-design.md`:

  - **5+/A.4 — per-call-site `ExternCall` dispatch.**  ✅ **LANDED** (2026-05-18).
    Achieved via AST-level `dispatchIntrinsic` / `dispatchIntrinsicJs` in
    JvmGen / JsGen: both backends look up `QualifiedName(fname)` in the
    intrinsics table before falling through to any hardcoded handling.
    Stage 5+/B.3 extended this to `Term.Select(obj, method)` qualified calls.
    The original `ExternCall(qn, args)` IR-node path remains planned for when
    Normalize emits IR expressions; the AST-level approach covers all currently
    migrated intrinsics.  Per-intrinsic match arms removed for all migrated
    functions.
  - **5+/A.5 — `extern def` parser + `Backend.runtimePreamble`.**
    Declarations live in `runtime/std/*.ssc`; backends ship runtime helpers
    (e.g. emitted `class WebSocket`) via a single string field.
  - **5+/B — `std.http` extraction.**  ✅ **LANDED**.  route/serve/stop
    in InterpreterIntrinsics + JvmHttpIntrinsics + JsHttpIntrinsics.
    NativeContext extended.  runtime/std/http.ssc has Request/Response declarations.
  - **5+/D — `std.ws` / `std.auth` / `std.fs` / `std.crypto`
    extraction.**  ✅ **LANDED**.  Same pattern as 5+/B; all 11
    intrinsic families migrated to `.sscpkg` plugins (2026-05-21).
    See `docs/intrinsics-migration.md`.

### Expected deflation

After 5+/B + 5+/D land:

  | File                            | Before  | After  | Delta |
  |---------------------------------|--------:|-------:|------:|
  | `JvmGen.scala`                  | 4500    | ~1500  | −3000 |
  | `JsGen.scala`                   | 5400    | ~1500  | −3900 |
  | `Interpreter.scala`             | 4500    | ~2500  | −2000 |
  | new `backend-*/intrinsics/*`    |       0 | ~3000  | +3000 |

Total LOC roughly conserved; split by responsibility: codegen
core = "how to emit the generic language", intrinsic modules =
"what `onWebSocket` does on backend X".  Each intrinsic = one
function on each backend it claims.

### Third-party plugin path

The SPI declaration (§8 of `docs/backend-spi.md`) already supports
third-party intrinsic packages — a plugin author ships an `extern`
package together with the `Backend.intrinsics` entries that
implement it.  Once 5+/B proves the pattern in-tree, the
out-of-tree plugin path is one ServiceLoader-discovery wire-up.

Remaining UX/distribution work (not blocking the SPI mechanism):

  - **Package format** (`.sscpkg` archive with manifest + sources +
    optional pre-compiled IR) — ✅ **LANDED** (2026-05-21).
  - **Plugin resolver** — ✅ **LANDED** (2026-05-21): `pkg:` URI in
    `ImportResolver` + `BackendRegistry.findInstalledPkg` + auto-download
    via `LocalRegistry`.  `ssc install` shortcut also landed.
  - **Registry** — local registry (`~/.scalascript/registry.yaml`) with
    pre-seeded entries landed.  Remote registry (`registry.scalascript.io`)
    deferred; no concrete demand yet.

  Post-migration follow-ons (not blocking; tracked in `docs/intrinsics-migration.md` §11):

  - **Plugin test harness** — ✅ **LANDED (2026-05-26)**:
    `runtime/backend/test-utils` now provides `TestInterpreter(plugins =
    List(...))`, explicit plugin installation on `Interpreter`, and a harness
    self-test. Follow-up landed 2026-05-26: legacy std-plugin-backed
    interpreter tests moved behind `backendInterpreterPluginTests`, removing
    the `backendInterpreter / Test` → std-plugin project dependency;
    `graphPlugin`, `jsonPlugin`, `requestPlugin`, `fetchPlugin`,
    `frontendPlugin`, `swingPlugin`, `httpPlugin`, `authPlugin`, `oauthPlugin`, `wsPlugin`, `mcpPlugin`, and `sqlPlugin` now have
    isolated `src/test` suites via `testUtils % Test`. Remaining plugin-family
    suites have been migrated; the separate `sql {}` fenced-block dispatch refactor remains tracked below.
  - **Examples `pkg:` sweep** — ✅ **LANDED (2026-05-26)**: created `runtime/std/auth.ssc` (extern declarations for all auth-plugin intrinsics: CSRF, cookie, bcrypt, JWT, TOTP, WebAuthn, rate-limit, oauth client helpers); added `[route, serve, Response, Request](std/http.ssc)` import lines to 22 HTTP-using examples; added targeted `[csrfToken, …](std/auth.ssc)` imports to 4 auth-using examples (auth-demo, auth-full, oauth-demo, webauthn-demo).
  - **Jdbc `runSqlBlock` refactor** — ✅ **LANDED (2026-05-26)**:
    `Backend.sqlBlockRunner` + `SqlBlockContext` route plain `sql` fenced
    blocks through `sqlPlugin`; `SectionRuntime` only binds block results.
    Follow-up landed: `transaction` fenced blocks now route through the same
    plugin runner via `SqlBlockRunner.runTransaction`.
  - **`NativeContext` state-bag** — ✅ **LANDED (2026-05-26)**:
    `NativeContext` now exposes shared `feature*` and scoped `featureLocal*`
    state APIs. HTTP client config keys route through feature-local storage;
    existing named methods remain compatible.
  - **`interpreter-server` extraction** — ✅ **LANDED (2026-05-26)**:
    socket/server runtime moved to `runtime/backend/interpreter-server` as
    `backendInterpreterServer` behind `InterpreterServerSupport`. `Routes` /
    `WsRoutes` remain in interpreter core pending a smaller route-registry SPI.
  - **Route-registry SPI** — ✅ **LANDED (2026-05-26)**:
    `RouteRegistry` trait added to `interpreter` core; `Routes` extends it;
    `Interpreter.routeRegistry` field injected into `InterpreterHttpHandler`,
    `WebServer.start`, and `InterpreterServerSupportImpl` — decouples HTTP
    route dispatch from the global `Routes` singleton.

### Effort to "extensibility done"

5+/A.4 (~1-2d) + 5+/A.5 (~1-2d) + 5+/B (~3-5d) + 5+/D (~1-2d
per package × 4 packages = ~1 week) = **~2-3 weeks** of focused
work.  After this, "add a new platform primitive" is one
function per backend; "ship a Kafka library" is one external
plugin JAR.

### Out of scope here

Separate compilation of modules (per-module IR artifacts +
interface files + linker pass) is a different architectural axis.
Tracked as v2.0 below — it's a 2-3 month commitment that becomes
worth the cost only once a real package ecosystem emerges.

## Recommended implementation sequence

The roadmap items below interleave by version number but have real
dependency relationships.  This section gives a critical-path
ordering optimised for unblocking high-impact deliverables first
and minimising rework.

### Dependency graph

```
SPI 5+/A.4 (per-call-site dispatch)
  │
  └── SPI 5+/A.5 (extern def + Backend.runtimePreamble)
        │
        ├── SPI 5+/B  (std.http extraction; proof of intrinsic-module shape)
        │     │
        │     └── SPI 5+/D (std.ws / auth / fs / crypto extraction)
        │           │
        │           └── v1.7 (plugin packaging & discovery)
        │                 │
        │                 └── v2.0 (separate compilation)
        │
        └── v1.5 Tier 1 (TLS — could ship as intrinsic via new pipeline)
              │
              ├── v1.5 Tier 2 (HTTP client — uses Tier 1 TLS for HTTPS)
              │
              └── v1.5 Tier 3 (WS client — uses Tier 1 TLS for wss)
                    │
                    └── v1.6 Phase 3 (distributed actors over WS)

v1.5 Tier 4 (streaming) + Tier 5 (REST ergonomics) — orthogonal,
       no SPI dependency, can land any time

v1.6 Phase 2 (supervision) — orthogonal, no SPI dependency

6+/A (direct-syntax) — orthogonal, parked until std.* extracted
6+/C (HostCallback dispatcher) — orthogonal, parked
```

### Suggested order (critical-path optimised)

This minimises rework (TLS ships through the new pipeline rather
than the old hardcoded codegens, so no double-implementation) and
unblocks downstream features as early as possible.

  1. **SPI 5+/A.4** — per-call-site dispatch (~1-2d).
     Foundational; unblocks everything below.
  2. **SPI 5+/A.5** — `extern def` parser + `Backend.runtimePreamble`
     (~1-2d).  Foundational; pairs with 5+/A.4.
  3. **SPI 5+/B** — `std.http` extraction (~3-5d).
     Proof point that the SPI shape carries a real platform package
     end-to-end.  Critical for confidence before generalising.
  4. **v1.5 Tier 1 — TLS** ✓ Landed.
  5. **SPI 5+/D** — `std.ws / auth / fs / crypto` extraction ✓ Landed.
  6. **v1.5 Tier 2 — HTTP client** ✓ Landed.
  7. **v1.5 Tier 3 — WS client** ✓ Landed.
  8. **v1.7 — Plugin packaging & discovery** ✓ Landed.
  9. **v1.6 Phase 2 — Actors supervision** ✓ Landed.
 10. **v1.6 Phase 3 — Distributed actors** ✓ Landed.
 11. **v1.5 Tier 4 — HTTP server completeness** ✓ Landed.
 12. **v1.5 Tier 5 — REST ergonomics** ✓ Landed.
 13. **v1.8 — Direct-syntax do-notation** ✓ Landed.
     All 6 phases in main: interpreter, JvmGen+JsGen codegen,
     conformance tests, `runtime/std/monad-control.ssc`, diagnostics,
     `direct-syntax-demo.ssc`.
 14. **v1.9 — Coroutine primitive** ✓ Landed.
     All 4 phases; interpreter + JvmGen + JsGen; 19 conformance tests.
 15. **v1.10 — Generators** ✓ Landed.
     `flatMap`, `zip`, `zipWithIndex` added; all 3 backends; 4 new tests.
 16. **v1.11 — Continuation-based Async** ✓ Landed.
     Rewrite `Async.*` on top of v1.9 coroutines.  Internal
     `Computation[A]` becomes a runtime-only shim; ≥20% allocation
     reduction target on flatMap-heavy workloads.  User code
     unchanged — conformance gates the merge.
 17. **v1.11.5 — `Free[F, A]` as stdlib type** ✓ Landed
     User-facing `Free` monad in `runtime/std/free.ssc` built on v1.1
     typeclasses + v1.9 coroutines.  Program-as-data complement
     to coroutine's program-as-control-flow.  Pure library work,
     no compiler changes.  Parallel with v1.11 if scheduling
     permits.
 18. **v1.12 — Algebraic effects feasibility study** (~1 week, no
     shipping code).
     Design doc + prototype + go/no-go.  Investigates whether the
     existing typer can carry effect rows; commits to or rejects a
     v2.x algebraic-effects milestone.
 19. **v1.13 — Final Tagless ergonomics** ✓ Landed.
     Land four typer features that block idiomatic typeclass usage:
     `using` auto-resolution, context bounds, cross-file trait
     inheritance with HKT, sealed-trait extension dispatch in INT.
     Full design in [`docs/final-tagless.md`](docs/final-tagless.md).
     Closes carryover items 1 + 4 from v1.1.  Unlocks idiomatic FT
     across `runtime/std/*` and unblocks v1.14 `derives` + v1.15 `throws`.
 19. **v1.15 — Checked errors via `throws`** ✓ Landed.
     **Higher priority than v1.14** — closes the everyday
     error-handling story; prerequisite for many real apps.
     Dual-encoding: canonical `infix type throws[A, E] = Either[E, A]`
     (direct-syntax-integrated, monadic, ergonomic) plus opt-in
     `infix type throwsRaw[A, E] = A | E` (zero-allocation; preserves
     native JVM `Throwable.getStackTrace`; used for hot-path parsing
     and JVM exception interop).  Includes return-site auto-conversions,
     `box`/`unbox` between encodings, std-lib platform-exception shims
     (`parseInt`, etc.), `attemptCatch` / `attemptCatchRaw` opt-in
     lifts, and the `HasStackTrace` mixin with `currentStackTrace()`
     per-backend.  Full design in
     [`docs/error-handling.md`](docs/error-handling.md).  Depends on
     v1.8 ✓ (direct-syntax) + v1.13 (`using` + cross-file traits).
 20. **v1.14 — Metaprogramming MVP (`inline` + `derives`)** ✓ Landed.
     weeks).
     `inline def`/`val`/`if`/`match` + `compiletime.summonInline`
     compile-time evaluator, plus Tier 1 `derives` recipes for
     `Eq` / `Show` / `Hash` / `Order` and a handful of std
     typeclasses (`Foldable` / `Traversable` / `Functor`).
     Full design in [`docs/metaprogramming.md`](docs/metaprogramming.md).
     User-defined macros (`quoted.Expr`) explicitly out of scope —
     deferred to v2.x.  Depends on v1.13 (`Mirror` resolution).
 21. **v1.17 — MCP support (client + server)** ✓ Landed (Phases 1–7);
     v1.17.1 hardening ✓ Landed; v1.17.2 SSE/JS ✓ Landed;
     v1.17.3 prompts/JVM ✓ Landed; v1.17.4-min Http/Ws/JVM (minimal
     wiring, echo placeholder) ✓ Landed; v1.17.4-runtime consolidation
     Phase 1 (a + b + c) + Phase 2 (a + b + c + d + e + f + g —
     pure helpers + POJO HTTP model + RequestBuilder / ResponseWriter
     / StreamResponseWriter + StaticAssetServer + WsHandshake /
     Reassembler / RateLimiter + HttpDispatchLoop + WsFrameDispatch
     + HttpHelpers.{parseCookieHeader, readHttpHead, parseHttpHead}
     + TlsProxy migration, 29 inlined files) + Phase 3 (Option A:
     serveRuntime out of string templates — 4 real .scala files in
     a new runtime-server-jvm module, ~1750 LOC migrated from the
     """|..."""  template) + Option B (interpreter WS to per-VT
     thread model — Selector loop replaced with blocking accept +
     Thread.ofVirtual() per connection, mirroring the codegen;
     −211 LOC) ✓ Landed; v1.17.4 full (real `McpServerSession`
     dispatch + SDK import fixes) ✓ Landed (all 2026-05-19).
     Anthropic's Model Context Protocol via REST-shaped API
     in a separate namespace (`runtime/std/mcp/*`).  Intrinsic-first:
     wraps `@modelcontextprotocol/sdk` on Node and
     `io.modelcontextprotocol:sdk` on JVM; interpreter +
     scalajs-spa reject at typecheck via SPI feature flags.
     Full design in [`docs/mcp.md`](docs/mcp.md).  Remaining
     v1.17.x work: INT own-impl, type-class layer,
     streaming resources.

     **v1.17.x interpreter own-impl + OAuth/OIDC layer** ✓ Landed
     (Iterations J–AA, 2026-05-19):

     **MCP spec completion (J–R)** — `notifications/<cat>/list_changed`
     (J); cancellation via `notifications/cancelled` + cooperative
     `srv.isCancelled` polling (K); progress notifications with
     `_meta.progressToken` (L); `logging/setLevel` + `notifications/
     message` with syslog levels (M); `resources/templates/list` +
     RFC 6570 URI templates (N); `roots/list` server→client request +
     `notifications/roots/list_changed` (O); `elicitation/create`
     three-way reply (P); `completion/complete` for prompt args +
     resource template params (Q); cursor pagination on all four
     list endpoints (R).

     **OAuth 2.1 Authorization Server** — standalone
     `scalascript.oauth.*` package, fully decoupled from MCP, usable
     from any HTTP service:
     - **Iter S**: pluggable `TokenValidator` + `currentAuth`
       thread-local + RFC 9728 protected-resource metadata +
       WWW-Authenticate on 401; HTTP transport gates every request.
     - **Iter T**: standalone `AuthServer` — authorization-code grant
       with mandatory PKCE (OAuth 2.1), refresh-token grant with
       single-use rotation (§6.1), client-credentials grant,
       Dynamic Client Registration (RFC 7591), token introspection
       (RFC 7662), AS metadata (RFC 8414).  `McpAuth` reduced to a
       re-export shim over `oauth.OAuth`; bridge via
       `builder.useAuthServer(as)`.
     - **Iter U**: framework-agnostic HTTP route handlers
       (`OAuthRoutes`) for `/token`, `/introspect`, `/register`,
       `/authorize`, `/.well-known/oauth-authorization-server` —
       returns typed `RouteOutcome { Json | Redirect | Empty }`.
     - **Iter V**: token revocation (RFC 7009) — `/revoke` endpoint,
       access-token deny-list via JWT `jti` claim, refresh-token
       lookup; honoured by introspection + tokenValidator.
     - **Iter W**: backend-interpreter installer `OAuthHttp.installRoutes`
       wires all OAuth routes into the embedded WebServer.
     - **Iter X**: script-side intrinsics (`oauth.*` namespace) —
       `authServer(config)` + handle methods (`registerClient`,
       `issueClientCredentialsToken`, `introspect`, `revokeToken`,
       `metadata`), `serveAuthServer`, `issueHmacToken`,
       `pkceVerifier` / `pkceChallenge`, `srv.useAuthServer(asValue)`
       for MCP integration.  JVM bridging via stable-id registry.

     **OpenID Connect (OIDC)** Identity Provider layer on top of AS:
     - **Iter Y**: `scalascript.oidc.*` — `OidcServer` composes
       AuthServer, mints `id_token` (JWT with iss/sub/aud/exp/iat +
       scope-gated profile/email claims) when granted scope includes
       `openid`, serves `/userinfo` (bearer-validated, claim filter),
       extends discovery JSON.  `UserClaims` + `UserInfoStore`.
     - **Iter Z**: `OidcHttp.installRoutes` registers full
       OIDC + OAuth route set in one call (POST/GET `/userinfo`,
       `/.well-known/openid-configuration`).  Script API:
       `oidc.server(as)`, `oidc.serve(idp, basePath?)`, handle methods
       (`addUser`, `userInfo`, `mintIdToken`, `discovery`).

     **JWKS + RSA signing for production OAuth** (Iter AA):
     - Pluggable `TokenSigner` trait (alg / kid / sign / verify /
       publicJwk).  `HmacTokenSigner` (HS256) — default, symmetric.
       `RsaTokenSigner` (RS256) — asymmetric, 2048-bit RSA pairs.
     - `jwksDocument(signers)` — RFC 7517 JWK Set; symmetric signers
       contribute no public material.
     - AS accepts `customSigner` constructor param; all internal
       mint/verify paths route through `signer`.  Metadata advertises
       `token_endpoint_auth_signing_alg_values_supported` and (when
       asymmetric) `jwks_uri`.  GET `/.well-known/jwks.json` route
       in both `OAuthHttp` and `OidcHttp` installers.  OIDC
       `id_token` automatically RS256-signed when AS uses RSA signer.

     **Test coverage**: 270 tests across 32 suites covering all the
     above — MCP (143), OAuth core (29), OAuth routes (23), MCP↔OAuth
     bridge (5), OAuth revocation (9), OAuth HTTP installer (11),
     OAuth script intrinsics (6), OIDC server (18), OIDC script + HTTP
     installer (8), Auth/RSA/JWKS (18), plus older MCP suites.

     **Tool + resource annotations** (Iter BB) ✓ — MCP 2025-03 UI
     hints: `ToolAnnotations(title, readOnlyHint, destructiveHint,
     idempotentHint, openWorldHint)` and
     `ResourceAnnotations(audience, priority)`.  All optional,
     emitted in tools/list + resources/list + resources/templates/list
     only when non-empty; backwards-compatible (registration calls
     without an `annotations` arg still work).

     **Generic Resource Server SDK** (Iter CC) ✓ —
     `scalascript.oauth.OAuthGuard` lets any HTTP service wrap route
     handlers in bearer-token validation with one call.  Pure
     `OAuthGuard.check(headers, validator, requiredScopes?, realm?)`
     returns `GuardDecision { Allow(claims) | Deny(routeOutcome) }`;
     401 carries WWW-Authenticate; 403 carries `insufficient_scope`
     + the required-scope list (so clients know what to request).
     Script API: `oauth.guard(authServer, scopes?)(handler)` curries
     into a `req => Response` wrapped handler; alternative
     `oauth.guardWithValidator(validatorFn, scopes?)(handler)` for
     non-AS validators (JWKS / custom).  Companion
     `oauth.hmacValidator(secret)` exposes a stand-alone validator
     for script use.  MCP server's RS logic is the same `check` —
     unified codepath.

     **Examples + documentation** (Iter DD) ✓ — comprehensive
     `docs/oauth.md` (big-picture map, AS recipe, RS-guard recipe,
     OIDC recipe, RSA+JWKS migration path, MCP integration, spec
     compliance table covering 13 RFCs + OIDC Core / Discovery) plus
     four runnable `.ssc` examples: `oauth-as-standalone`,
     `oidc-idp`, `oauth-rs-guard`, `oauth-rsa-jwks`,
     `mcp-server-protected`.

     **Generic `_meta` propagation** (Iter EE) ✓ — final MCP spec
     gap closed.  Optional `meta: Option[ujson.Value]` on tool /
     resource / resource template / prompt registrations; emitted
     under the `_meta` JSON key on every list endpoint when non-
     empty.  Coexists cleanly with annotations + pagination;
     legacy registrations without a `meta` arg work unchanged.

     **MCP is now fully spec-compliant** against MCP 2025-03 +
     OAuth 2.1 + OIDC + the relevant RFCs.

     **RSA AS from scripts** (Iter FF) ✓ — `oauth.authServer(...)`
     now accepts `signer: "HS256" | "RS256"` (+ optional
     `signingKid`).  When `signer = "RS256"` a fresh 2048-bit RSA
     key pair is generated automatically; metadata picks up `RS256`
     in `token_endpoint_auth_signing_alg_values_supported` and the
     `jwks_uri` field; `/.well-known/jwks.json` publishes the
     public key with the supplied `kid`.  OIDC `id_token`
     automatically RS256-signed when AS uses RSA.  HS256 mode
     stays the default with full backwards compat.

     **Passkey / WebAuthn assertion grant** (Iter GG) ✓ —
     `scalascript.oauth.Passkey` + new
     `urn:ietf:params:oauth:grant-type:passkey` OAuth grant.
     `PasskeyStore` maps credentialId → (subject, publicKey, alg);
     `Passkey.verifySignature` validates RS256 / ES256 assertions;
     `GET /passkey/challenge` issues a single-use nonce; `/token`
     accepts the grant with form fields `credential_id / challenge /
     signed_data / signature / scope`.  Public-key decoders for
     X.509 SPKI, RSA JWK (n/e), and EC JWK (x/y).  Registration
     ceremony + clientDataJSON / origin / rpId verification stay
     with the caller — we focus on the cryptographic core +
     OAuth-integration plumbing.  Metadata + installer routes
     auto-pick-up the new grant + endpoint.

     **MCP late-2025 spec additions** (Iter HH) ✓ — fills the gap
     between MCP 2025-03 and the rolling additions that landed since:
       - `outputSchema` field on tool entries; tools/list emits it
       - `structuredContent` field on tools/call results;
         `ToolHandlerResult(content, isError, structuredContent)`
         supports the typed alternative payload
       - `audioContent(data, mimeType)` helper — `type: "audio"`
         content variant (parallel to imageContent)
       - `resourceLinkContent(uri, name?, description?, mimeType?)`
         — lightweight `type: "resource_link"` reference variant
       - direct `title` field on tool / resource / resource template /
         prompt entries (distinct from annotations.title; clients
         may prefer the entry-level field when both are set)
       - all new fields are optional; legacy registrations unchanged

     **Client-side auth coverage** (Iter II) ✓ — closes the gap an
     honest audit revealed: until this iteration our MCP clients
     couldn't talk to our own auth-protected MCP servers, and AS-side
     client secrets were stored in plaintext.

       - **McpHttpClient.setBearerToken / McpWsClient(bearerToken)**
         — bearer applied to every outbound POST + SSE GET (HTTP) or
         the WebSocket upgrade handshake (WS).  `mcpConnect(transport,
         timeoutMs?, bearerToken?)` exposes it from scripts.
       - **`scalascript.oauth.OAuthClient`** — client-side OAuth SDK
         covering all three roles' Client half: `discoverAs(issuer)`
         / `discoverRs(resourceUrl)` metadata lookups; `freshPkce()`
         + `authorizationUrl(...)` for the auth-code+PKCE flow;
         `exchangeAuthorizationCode / refresh / clientCredentials`
         token endpoints; `TokenHolder` with lazy auto-refresh when
         the cached token is within `refreshLeadSeconds` of expiry.
       - **AS client-secret hashing** — `OAuth.hashSecret` (PBKDF2-
         HMAC-SHA256, 100k iterations, 16-byte salt) +
         `verifySecret` (constant-time compare, legacy-plaintext
         fallback for non-prefixed entries).  `registerClient` now
         stores the hashed form; the registration response carries
         the plaintext once per RFC 7591 §3.2.1 norms.  Existing
         stores keep working — fallback path handles them until
         rotation.

     **v1.17.x is now feature-complete** for MCP + OAuth + OIDC +
     all the spec-grade auth surface a real production AS needs.

     **Iter JJ — Security correctness** ✓ — closed three critical
     security holes the audit flagged.

       - **`aud` audience validation** in `OAuthGuard.check(...,
         expectedAudience = Some("api-a"))`.  RS now refuses tokens
         whose `aud` claim doesn't include its identifier — defeats
         the "token issued for RS-A used at RS-B" attack.  Accepts
         both string and array forms of `aud` per RFC 7519 §4.1.3.
       - **OIDC `nonce` claim** round-trip: `AuthorizationRequest`
         + `/authorize` route + `AuthorizationCodeRecord` carry it
         from the authorize step to the AS's nonce side-map; OIDC
         `mintIdToken` pulls it out + embeds in the id_token —
         defeats id_token replay against a different request.
       - **Clock-skew tolerance** (`DefaultClockSkewSeconds = 60`)
         on JWT `exp` / `nbf` / `iat` checks.  Single
         `validateJwtTimestamps(payload, skew)` helper shared by
         the HMAC + RSA signer paths.  Defeats spurious failures
         from sub-second clock drift between AS and RS; far-future
         `iat` is rejected as a forgery signal.

     **Security hardening backlog** (remaining iterations):

     **Iter KK — Refresh-token reuse detection + rate limiting** ✓ —
     production-grade hardening on top of single-use rotation.

       - **Token family tracking**: every refresh token carries a
         `familyId` (auto-assigned at initial issuance, inherited
         across rotations).  Lets the AS revoke a whole chain in
         one call.  `revokeRefreshFamily(id)` burns every member +
         marks the family as denied forever.
       - **Rotated-token graveyard**: rotated-out refresh tokens
         move into a bounded LRU (default 10k entries) instead of
         vanishing.  `graveyardLookup(token)` returns the
         familyId of any previously-rotated token.
       - **Reuse detection in `handleRefresh`**: when the presented
         token is not in the active store, check the graveyard;
         a hit is the stolen-refresh-token signal → burn the
         family immediately (RFC OAuth 2.1 §4.14.2).
       - **Burn-list failsafe**: `isFamilyRevoked(familyId)`
         consulted on every refresh; persists across restarts in
         the InMemory impl + can be persisted out by custom stores
         (per-token graveyard may be unbounded; the family deny
         set is the durable guarantee).
       - **`scalascript.oauth.RateLimiter`** + `TokenBucket(cap,
         rate)` (continuous refill, key-scoped buckets) +
         `Disabled` no-op default.  AuthServer accepts via
         constructor.  `OAuthRoutes.handleToken` rejects with
         429 + Retry-After when over budget, keyed by client_id;
         runs BEFORE any PBKDF2 verify so brute-force probes pay
         no CPU cost.

     **Iter LL — Client SDK completeness** ✓ — closes the
     "client can't verify what it got" gap.

       - **State CSRF helpers** — `OAuthClient.freshState()` +
         `verifyState(expected, presented)` constant-time compare;
         caller stashes issued state in the session cookie, matches
         against the redirect parameter on callback.
       - **`OAuthClient.JwksCache(jwksUri, ttlSeconds = 300)`** —
         bounded cache backed by the AS's `/.well-known/jwks.json`
         endpoint.  Fetches lazily, refreshes on TTL miss or
         unknown-kid (rotation-tolerant).  Stale tolerated on
         transport failure (best-effort).  RSA + EC (P-256) keys.
       - **`OAuthClient.validateJwt(token, jwks)`** — verifies
         RS256 or ES256-signed external JWTs against the cache;
         clock-skew tolerance matches the AS-side signers.
       - **`OAuthClient.validateIdToken(idToken, jwks,
         expectedIssuer, expectedAudience, expectedNonce?)`** —
         OIDC validation: signature via JWKS, `iss` exact match,
         `aud` (string or array) MUST include expected, optional
         `nonce` exact match.

     **Iter MM — Production hardening** ✓ — three of the four
     planned bits landed (MCP-client 401→re-auth deferred to a
     separate iteration to keep this one focused on AS-side
     hardening that the audit flagged).

       - **TLS enforcement** via `AuthServerConfig.requireTls`:
         when true, OAuthRoutes.handleToken refuses requests whose
         `X-Forwarded-Proto` isn't `https` AND whose Host isn't a
         loopback (`localhost` / `127.0.0.1` / `[::1]`).  Dev
         workflow unaffected — loopback always passes.
       - **CORS** via `AuthServerConfig.corsOrigins: Set[String]`:
         empty (default) disables; non-empty advertises ACAO +
         allowed-methods + allowed-headers + `Vary: Origin` to
         matching origins; `"*"` reflects any origin (use carefully).
       - **AuthEvent audit hook** (`onAuthEvent: AuthEvent => Unit`)
         fires on every security-relevant event: TokenIssued,
         TokenRefused, ClientRegistered, AuthorizationCodeIssued,
         RefreshFamilyBurned, PasskeyAccepted/Rejected, TokenRevoked.
         Listener exceptions are swallowed so a poisoned hook can't
         break the hot path.

     **Iter NN — MCP client 401 → re-auth handler** ✓ —
     `McpHttpClient.setOn401Handler(fn: () => Option[String])`.
     When a request comes back 401 and a handler is wired, the
     client calls it for a fresh bearer + retries the same request
     once.  Returning None propagates the original 401 to the
     caller.  Single-retry budget prevents tight loops against a
     permanently-401 endpoint.  Typical wiring is
     `client.setOn401Handler(() => holder.current())` against an
     `OAuthClient.TokenHolder` that knows how to refresh.

     **Iter OO — OAuth client script intrinsics** ✓ —
     `oauth.client.*` namespace mirrors `OAuthClient` (JVM) for
     `.ssc` apps.  Mounted as a nested InstanceV under the
     existing `oauth` companion object so dotted access works the
     same as `math.sqrt(...)`.

       - `oauth.client.discoverAs(issuer)` / `discoverRs(url)`
         — RFC 8414 / 9728 metadata fetch returning a Map.
       - `oauth.client.freshPkce()` → Map { verifier, challenge,
         method }; `oauth.client.freshState()` / `verifyState(a, b)`.
       - `oauth.client.authorizationUrl(endpoint, clientId,
         redirectUri, scopes, state, challenge, method)` — pure
         URL builder.
       - `oauth.client.exchangeAuthorizationCode(...)`,
         `.refresh(...)`, `.clientCredentials(...)` — token
         endpoints; return a tagged Map:
         `{ ok: Boolean, accessToken?, tokenType?, expiresIn?,
            refreshToken?, idToken?, scope?, error?, description?,
            raw }`.
       - `oauth.client.tokenHolder(endpoint, clientId
         [, refreshLeadSeconds][, secret])` → InstanceV with
         `.seed(tokens) / .current() / .clear()`.  Bridges to the
         JVM TokenHolder via stable-id registry (same pattern as
         AuthServer / OidcServer handles).

     **Iter PP — Final security hardening** ✓ — closes the minor
     gaps an honest audit pass surfaced (DoS via large bodies,
     missing browser security headers, silent weak-secret
     acceptance, log-leakage of bearer tokens).

       - **Body size limit**:
         `AuthServerConfig.maxRequestBytes = 65_536` (64 KiB default).
         OAuthRoutes.handleToken returns 413 Payload Too Large
         before parsing anything — defeats AS-side OOM via megabyte
         JSON / form bodies.
       - **Browser security headers**: when
         `config.securityHeaders = true` (default), every AS
         response carries `X-Content-Type-Options: nosniff`,
         `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
         and (when `requireTls`) `Strict-Transport-Security:
         max-age=31536000; includeSubDomains`.
       - **HMAC secret strength check**:
         `AuthServer.signingSecretWarning: Option[String]` surfaces
         a startup warning for HS256 secrets shorter than 32 bytes
         (RFC 7518 §3.2 floor); RSA / custom signers skip the
         check.  Non-fatal — caller decides whether to refuse boot
         or just log.
       - **`OAuthRoutes.scrubSensitive(s)`** — log-line scrubber
         that redacts bearer headers, `access_token`,
         `refresh_token`, `client_secret`, `code_verifier` in
         both form-encoded and JSON contexts.  Safe for null /
         empty input.

     **Iter QQ — End-to-end integration test** ✓ — proves the
     22+ iterations actually compose: embedded JDK HttpServer
     hosts both the OAuth AS endpoints and an OAuth-protected MCP
     server in one process; drives the full stack through the
     public client APIs.

       1. `OAuthClient.discoverAs(baseUrl)` — RFC 8414 metadata fetch
       2. `OAuthClient.clientCredentials(tokenEndpoint, id, secret,
          scopes)` → real bearer token
       3. `McpHttpClient.setBearerToken(...)` + initialize handshake
       4. `tools/list` over /mcp with bearer → expected catalogue
       5. `tools/call` over /mcp → real tool invocation result
       6. `as.revokeToken(t)` → subsequent /mcp call gets 401
       7. RSA-signed AS metadata exposes `jwks_uri` that's actually
          reachable + serves the matching public key

     Five scenarios cover happy path, missing-bearer 401, garbage-
     bearer 401-invalid_token, post-revocation 401, RSA + JWKS
     discovery.  Boot helper takes `buildAs(baseUrl)` so the AS
     issuer claim ends up equal to the actually-bound port — the
     metadata document advertises real reachable URLs.

     **Iter RR — Persistent stores + full-stack example** ✓ —
     closes the last production blocker: AS state survives process
     restart.  Adds a single-file `.ssc` that exercises the entire
     stack end-to-end.

       - `scalascript.oauth.PersistentStores.JsonLineClientStore(path)`
         — append-only JSON-line file of `client.register` events;
         replay-on-construction reconstructs the in-memory map.
         Corrupt lines are skipped (resilient to partial writes).
       - `JsonLineTokenStore(path, graveyardCap = 10_000)` — same
         pattern across 8 event kinds:
           * `code.save` / `code.consume` (auth codes; one-shot
             consumption persists across restart)
           * `refresh.save` / `refresh.revoke` (rotation history;
             revoked tokens auto-populate the graveyard on replay
             so reuse detection still trips post-restart)
           * `access.revoke` / `family.revoke` (deny lists for the
             RFC 7009 + reuse-detection paths)
       - End-to-end test: register a client + mint a token via one
         AS instance, drop the AS, recreate from the same files,
         confirm the client + bear authentication still work.
       - `examples/oauth-mcp-full-stack.ssc` — single runnable demo
         combining `oauth.authServer` + `oauth.serveAuthServer` +
         `mcpServer { srv => srv.useAuthServer(as); ... }` +
         `serveMcp(Transport.Http(...))`.  Includes curl recipes
         for discovery / mint / protected-call / revoke and a
         Scala snippet showing the persistent-stores swap-in.

     **Iter SS — Production observability + CLI + extra examples** ✓ —
     four parallel work-streams (#1, #2, #3, #6) landed together:

       **(#1) `scalascript.oauth.Observability`** — three building
       blocks for AS deployments:
         - `Health.liveness` / `Health.readiness(check)` — RouteOutcome-
           shaped health probes (200 / 503 with `{status: ...}` body)
         - `class Metrics` — Prometheus exposition-format registry
           with labelled counters + gauges; `routeOutcome()` returns
           a 200 with `text/plain; version=0.0.4`
         - `MetricsBinding.attachDefault(as, m)` — wires the
           AuthEvent stream to 7 standard counters
           (`oauth_tokens_issued_total` / `_refused_total`,
           `oauth_clients_registered_total`, `oauth_codes_issued_total`,
           `oauth_family_burned_total`,
           `oauth_passkey_accepted_total` / `_rejected_total`)
         - `class JsonLineAudit(path)` — file-backed audit log
           consuming AuthEvent → one JSON line per event
           (ts + event + structured fields)

       **(#2) `examples/oidc-login-flow.ssc`** — complete OIDC
       login walk-through: PKCE + state CSRF + /authorize redirect +
       /token exchange + /userinfo bearer call + id_token signature
       + claim verification.  Two pre-registered users; manual curl
       recipe; JVM-side `validateIdToken` snippet.

       **(#3) Three MCP server templates** —
       `mcp-filesystem-server.ssc` (read/write/list/delete with
       hint annotations + sandbox caveat), `mcp-keyvalue-server.ssc`
       (in-memory mutable.Map closed over the builder block),
       `mcp-search-server.ssc` (Files.walk-based substring search,
       top-10 ranked).  All default to Transport.Stdio.

       **(#6) `ssc oauth` CLI subcommand** —
       `discover <issuer>` / `jwks <issuer>` /
       `dcr-register <issuer> <redirect-uri>…` (RFC 7591) /
       `mint <secret> <subject> [scopes…]` /
       `introspect <secret> <token>`.  `mint` warns on short HMAC
       secrets per RFC 7518 §3.2.  `introspect` decodes locally —
       no network round-trip for test fixtures.

     **Truly post-v1.17 (deferred)**: DPoP (RFC 9449
     sender-constrained tokens); PAR (RFC 9126 Pushed
     Authorization Requests); MTLS client auth (RFC 8705 —
     depends on ALPN / client-cert chains).
 22. **v1.18 — `package` keyword + std layout migration** ✓ Landed (all phases, 2026-05-19).
 23. **v1.19 — URL / dep imports** ✓ Landed.
     `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)`
     resolver, both with `ssc.lock` SHA-256 integrity-check.
     `ssc lock` / `ssc lock check` CLI.  Central registry
     deferred to v1.19.x.
 24. **v1.20 — DSL primitives + `runtime/std/parsing`** ✓ Landed (all sub-versions).
     User-defined string interpolators cross-backend +
     parser-combinator library (`runtime/std/parsing/*`) + AST/pretty-
     printer helpers (`runtime/std/dsl/*`).  Reified-by-default; Parser
     as ADT; left-recursion combinator family; context-in-parser
     via ADT nodes (foundation for v1.20.2).  Full design in
     [`docs/dsl.md`](docs/dsl.md).
     v1.20.1 (parser error recovery), v1.20.2 (indentation-aware),
     v1.20.3 (multi-pass pipeline) — all landed.  See CHANGELOG v1.20.
 25. **v1.21 — Local map-reduce (`Dataset[T]`)** ✓ Landed.
     Lazy `Dataset[T]` fluent API with sequential + parallel
     local execution (v1.3 Async.parallel on JVM; sequential
     fallback on JS pending v1.3 Node parallel).  Streaming
     via v1.10 generators.  Full design in
     [`docs/mapreduce.md`](docs/mapreduce.md).
 26. **v1.22 — Distributed map-reduce** ✓ Landed.
     Same `Dataset[T]` API, distributed via v1.6 distributed
     actors.  Coordinator-dispatched partitions, named-handler
     registry (no closure serialisation), coordinator-mediated
     shuffle, configurable failure handling.  Closure
     serialisation + worker-to-worker shuffle in v1.22.x.
 27. **Cluster management** ✓ Landed in v1.23.
     Shipped: membership view + events + per-link + cluster-wide
     Phi-accrual failure detection + `std.cluster.Cluster.*` wrapper
     + Bully leader election (with auto re-elect) + auto-reconnect
     on outbound link drops + periodic gossip re-discovery + cluster
     config distribution (LWW per key, snapshot on handshake) +
     rolling-restart drain protocol + cluster metrics aggregation
     (per-node gauges, snapshot on handshake) + Raft leader election
     (opt-in via `useRaftLeaderElection`) + external-coordinator
     dispatch (4-arg `useExternalCoordinator`, app-level adapter to
     etcd / Consul / ZooKeeper) + bounded `leaderHistory` for
     auditable leadership.  All three leader-election protocols
     share `electLeader` / `currentLeader` / `subscribeLeaderEvents`;
     see [`docs/cluster-raft.md`](docs/cluster-raft.md) for the
     unified API and per-protocol algorithms.
 28. **6+/C — HostCallback dispatcher** (~1 week).
     Stage 6+/C from spi-followups-plan.md.  Unblocks the first
     out-of-process (.NET / WASM) backend MVP.  Parked because no
     such backend is in flight.
 29. **v2.0 — Separate compilation** — MVP + post-MVP hardening ✓ Landed
     (2026-05-19): artifact format, `InterfaceExtractor` (with
     `exports:` filtering + package-wrapped object walk), `ArtifactIO`,
     `InterfaceScope` (real type parser), `Linker` (FQN rewrite, 7 e2e
     tests), `ModuleGraph`, six CLI commands, CLI subprocess smoke tests.
     Full pipeline deferred (~2-3 months remaining) — promote when
     one of {real package ecosystem, >30s incremental build, IDE demand}
     is true.

### Approximate total

Critical-path through step 10 (web stack production-ready + clean
SPI + plugin ecosystem + actors complete + clients done): **~10-12
weeks** of focused work.  Steps 11-13 add polish and ergonomics
over another month.  Steps 14-15 are future commitments tracked
here for prioritisation, not pending work.

### What can be parallelised

If two contributors:

  - **One** drives the SPI critical path: 1 → 2 → 3 → 5 → 8.
  - **Other** does v1.5 functional features 4 → 6 → 7 in parallel
    after SPI 5+/A.4/A.5 land.
  - v1.6 Phase 2 and v1.5 Tier 4/5 can interleave anywhere they fit.

### When the order changes

- **If standalone-internet deploy is urgent**: pull v1.5 Tier 1
  forward, ship it as inline codegen first, migrate to intrinsic
  later.  Costs ~1-2 days of rework when migrating; the deploy
  capability ships ~3 weeks earlier.
- **If a real third-party plugin author shows up**: pull v1.7
  forward; even an incomplete extraction (only std.http extracted,
  not yet std.ws/auth/fs/crypto) gives them enough scaffolding to
  start.  Document the partial surface and proceed iteratively.
- **If .NET / WASM MVP is in flight**: pull 6+/C HostCallback
  forward; otherwise that work duplicates platform intrinsics
  inside the subprocess backend.

## v0.7 — Reusable libraries and packaging

A consumer should be able to depend on a third-party `.ssc` library —
component pack, REST middleware, layout kit — without vendoring its
files into their own tree.  The steps are ordered so each one is
useful in isolation and unblocks the next.

1. **Registry** *(future)*.  Central index (`registry.scalascript.io`)
   with semver resolution, lock file (`ssc.lock`), publish/yank
   workflow.  Weeks of work; only worth opening once the surface
   above is well-trodden.  Out of scope for v0.7.

## v0.13 — Component theming variants

Beyond Tier 8's tokens we may want a "variant" concept: a Button
that's `tone="primary"` is a token-driven recolour, but a Button
that's `variant="ghost"` is a structurally different render (no
background, just a border).  Convention: variants live in the same
component, picked by string prop, documented in the front-matter
`variants:` list (used by `ssc preview`).

No code change — just discipline.  Promote when a real component
ends up with three+ variant branches.

## v1.12 — Typed Algebraic Effects

**Spec landed 2026-05-26** — `docs/algebraic-effects.md` complete. Go/no-go: **go**.

Design decisions locked:
- Effect syntax: `A ! Eff` (single), `A ! (E1, E2)` (multi, round parens).
- Effect rows: open by default with implicit tail variable. Total function = no `!`-clause = closed empty row.
- One-shot: `effect Foo { … }` — coroutine VT on JVM/interpreter; `function*`/`yield` on JS (closes `docs/coroutines.md:236-256` gap).
- Multi-shot: `multi effect Foo { … }` — Free-monad `Computation`-tree walk everywhere.
- Capability passing: `?=>` context functions (Scala 3 native, zero emitter work).
- Handler discharge: `handle[Foo](body : A ! (Foo, E)) : A ! E` — only named effect removed; tail propagates.

### Implementation milestones — ✓ All landed (2026-05-26)

**v1.12.1 — Type system + parser: ✓ Landed (2026-05-26)**
- `SType.EffectRow` + Rémy-style row unification in `Unifier`; `!` operator in `TypeParser`; `multi effect` keyword in `Parser.preprocessEffects`; `handle[Foo]` discharge in typer; `EffectAnalysis` verifier with diagnostics. 14 new tests.

**v1.12.2 — Runtime fast paths: ✓ Landed (2026-05-26)**
- JS: `function*`/`yield`/`iter.next(v)` for one-shot effect bodies; `_handleOneShot` preamble + `_resumed` flag. JVM/Interpreter: coroutine VT + dynamic one-shot-violation check. 3 new tests.

**v1.12.3 — Stdlib + capabilities: ✓ Landed (2026-05-26)**
- Typed discharge signatures for `runLogger`/`runRandomSeeded`/`runClockAt`/`runEnvWith`/`runState`/`runHttp`; `Reader[R]` + `NonDet` exemplars; `examples/algebraic-effects.ssc`; `EffectAnalysis` warnings promoted to errors. 42 total tests.

## v1.51 — Streams with Backpressure

**Spec landed 2026-05-27** — `docs/streams.md` complete. Go/no-go: **go**.

Design decisions locked:
- Types: `Source[A]` / `Sink[A]` / `Flow[A, B]` / `Stream[A] = Source[A]` — zero parser changes; all `SType.Named` applications.
- Hybrid push/pull: `stream { emit(x) }` push surface; `request(n)` credit protocol underneath.
- Defaults: credit = 16, buffer = 16 (Akka Streams default). Migration from `Generator`: use `.buffer(1, OverflowStrategy.Block)` for rendezvous semantics.
- Overflow strategies: alias existing `Overflow` enum from `runtime/std/actors.ssc:121-125` (`Block` / `DropOldest` / `DropNewest` / `Fail`).
- Error propagation: errors flow downstream + cancel upstream (Akka Streams `Supervision.Stop` default).
- Backend fast paths: JVM/interpreter = VT + `ArrayBlockingQueue(16)`; JS = native `async function*` + `Symbol.asyncIterator` (new emit path).
- UI signal adapter (`Source.signal`, `signal.bind`) scoped to v1.51.5.
- Effect-row integration (`A ! Stream`) — **landed in v1.51.6 (2026-05-28)**.

### Implementation milestones

**v1.51.1 — Plugin scaffolding + `Source` core: ✓ Landed (2026-05-28)**
- `runtime/std/streams-plugin/` + `streams.ssc`; `scan`, `onError`, `cancellable`, `tick`, `unfold`, `fromCallback`; `Feature.Streams` in interpreter + JVM capabilities. 12 new tests, 68 total.

**v1.51.2 — JS backend (`async function*` emit path): ✓ Landed (2026-05-28)**
- `_makeAsyncStream` in `JsRuntimeAsyncB` with 17 methods; `genExpr` cases for `tick`/`unfold`/`fromCallback`/`Sink.*`/`Flow.*`; `detectCapabilities` adds `Async` for stream modules. 20 `JsGenStreamsTest` code-shape tests.

**v1.51.3 — Flow + Sink + combining operators: ✓ Landed (2026-05-28)**
- 10 new `Flow` companion constructors with interpreter intrinsics + JsGen codegen; full `Sink` + `Flow` companion API in `streams.ssc`. 11 new Flow tests; 564/564 total pass.

**v1.51.4 — SSE/WS adapters, `mapAsync`, error recovery:** ✓ Landed (2026-05-27)

**v1.51.5 — Buffer strategies, time-based ops, UI signal adapter:** ✓ Landed (2026-05-27)
- `.buffer(n, OverflowStrategy)` with interpreter strategies:
  `Backpressure`/`Block`, `Drop`, `DropHead`/`DropOldest`, `Fail`
- `.throttle(Rate)`, `.debounce(Duration)` with interpreter wall-clock pacing
  and latest-value debounce
- `Source.signal[A](sig): Source[A]` live frontend `ReactiveSignal`
  subscription plus generic current-value adapter in the interpreter path
- `sig.bind(source)` reverse bridge for frontend `ReactiveSignal` values
- Swing/JavaFX runtime state maps synchronize with the shared signal bus
- 7 new interpreter tests; `examples/streams.ssc`, `runtime/std/streams.ssc`,
  README, user guide, and spec updated.
- Follow-up: platform-native SwiftUI stream/signal bridge.

**v1.51.6 — Effect-row integration (Landed 2026-05-28):**
- `Stream[A]` parameterized effect op (`EffectOp(name, args)` type-system extension)
- `Stream.emit[A]`, `Stream.complete[A]`, `Stream.error[A]`, `Stream.request[A]` ops
- `runStream[A, R](body): (Source[A], R)` — canonical algebraic-effects discharge runner
- Type-safe: `Stream.emit(x)` arg type unifies with `runStream` element type; mixed-type emits = compile error
- Cross-backend parity: interpreter, JS, JVM all return `(Source[A], R)` tuple

### Streams v1.51.5 follow-ups

Found while working on v1.51.5-streams-buffer. v1.51.5b landed interpreter
wall-clock `.throttle`/`.debounce`, subscription-backed `Source.signal`,
reverse `sig.bind(source)`, and Swing/JavaFX state-map synchronization.
SwiftUI still lowers signals to native `@State`; a platform-native
stream/signal bridge remains before advertising SwiftUI live streams as
complete.

## v1.52 — Deploy to Hostings, Clouds & Kubernetes-like Environments

**Spec landed 2026-05-27** — `docs/deploy.md` complete. Go/no-go: **go**.

Design decisions locked:
- **Five target categories**: container (OCI), Kubernetes-like, FaaS/serverless, static hosting, traditional hosting (SSH+systemd, rsync, SFTP/FTP, WAR drop, IIS).
- **Dual interface**: declarative `deploy:` + `groups:` + `environments:` blocks in manifest + `ssc deploy [target|group] --env=<env>` CLI subcommand driven by manifest.
- **`DeployTarget` SPI**: 6-verb lifecycle — `build / push / deploy / rollback / status / logs` — plus `outputs()` for cross-target value passing.
- **`DeployGroup` SPI**: multi-target orchestrator with `Parallel | Sequence | Pipeline(stages)` exec modes, DAG dependency resolution, three failure policies (`RollbackAll` / `ContinueRemaining` / `AbortRemaining`), structured event stream.
- **`DeployEnvironment` SPI**: orthogonal env axis — `local` (subprocess, dev machine) / `test-*` (ephemeral per-PR) / `staging` / `production`. Inheritance via `base:`, per-target `target_overrides:`.
- **Fault tolerance** (production): `fault_tolerance: { multi_region, quorum, failover_strategy }` with orchestrator-level regional health checks.
- **Blue-green** (production): two-slot model; `switch_strategy: instant | gradual(steps, interval)`, health-gated, smoke-tested, `hold_duration` for zero-rebuild rollback. `ssc deploy switch` + `ssc deploy promote --from --to`.
- **Hybrid state**: stateless default (reads from target-native state); optional remote state backend (`s3`, `consul`, `etcd`); production envs require remote state.
- **Spec-only in v1.52**: no code; implementation phased v1.52.1–v1.52.7.
- **Minimal depth**: SPI + taxonomy + examples per category; per-provider sections deferred to phase docs.

### Implementation milestones

**✓ Landed (2026-05-27) — v1.52.1 — Plugin scaffolding + AST + CLI stub + orchestrator core + local env:**
- `runtime/std/deploy-plugin/`: `DeployTarget`, `DeployGroup`, `DeployEnvironment`, `ArtifactKind`, `StateBackend`, `ArtifactRegistry`, `DeployManifest`, `LocalSubprocessTarget` SPI + adapters
- `DeployDag.topoSort` (Kahn's algorithm + cycle detection) + `toStages` + `DeployOrchestrator` (virtual threads, failure policies)
- `lang/core`: `Manifest` extended with `deploy`/`groups`/`environments`/`deployState` optional fields
- `ssc deploy [plan|status|envs] --env --group --target --dry-run --verbose`
- `examples/deploy.ssc` with 6 targets, 3 groups, 4 environments
- 22 unit tests; 509 core tests pass

**✓ Landed (2026-05-27) — v1.52.2 — Container target (generic OCI):**
- `DockerfileGenerator` — four base-image recipes (`eclipse-temurin:21-jre-alpine` / `gcr.io/distroless/cc` / `node:22-alpine` / `nginx:alpine`) per `ArtifactKind`; build-args, labels, env vars, custom `appPort`, `HEALTHCHECK` wired.
- `ContainerTarget` implementing all 7 `DeployTarget` SPI verbs; builder auto-detect (`buildctl` → `docker buildx` → `docker build`); multi-platform via `platform:` config; `docker push` with digest capture for rollback; `docker inspect` status; `docker logs` streaming; dry-run support throughout.
- `TargetFactory` — resolves `kind: container | traditional` to concrete `DeployTarget`.
- `ArtifactRegistry` extended with `OciImage` case.
- 14 new tests (Dockerfile generator × 9, TargetFactory × 3, ContainerTarget × 2); 36 deploy-plugin tests total.

**✓ Landed (2026-05-27) — v1.52.3 — Kubernetes target + blue-green:**
- `K8sManifestGenerator`: `Deployment` (liveness `/_health`, readiness `/_ready`, PreStop sleep drain, resource requests/limits, nodeSelector, annotations, blue/green slot label) + `Service` (ClusterIP, slot selector for blue-green) + `Ingress` (host, ingress class) + `ConfigMap` + `Secret` (base64-encoded). `bundle()` combines all; `blueGreenDeployments()` emits standby slot at 0 replicas.
- `K8sTarget`: full 7-verb `DeployTarget` SPI + `switch(ctx)` (kubectl patch service selector) + `promote(ctx, from, to)` (scale → switch → scale old to 0); blue-green enabled via `blue_green: "true"` config key; `kubectl` subprocess with configurable `kubeconfig`/`context`; `kubectl rollout undo` rollback; `kubectl logs -l app=<name>` streaming; dry-run throughout.
- `TargetFactory` extended with `"k8s" | "kubernetes"`.
- 17 new tests (manifest generator × 11, K8sTarget × 5, TargetFactory × 1); 53 total.

**✓ Landed (2026-05-27) — v1.52.4 — Traditional hosting (SSH + systemd, rsync, SFTP):**
- `SystemdUnitGenerator`: generates `.service` unit files for FatJar/NativeBinary/NodeBundle with ExecStart, user, WorkingDirectory, Environment vars, Restart, TimeoutStopSec, KillMode, [Install] section.
- `SshSystemdTarget` (`kind: traditional`, `transport: ssh+systemd`): SCP artifact + SCP systemd unit → `systemctl daemon-reload` + `systemctl restart`; `ssh journalctl -u <service>` logs; `systemctl is-active` status; pre/post_deploy hooks.
- `RsyncTarget` (`kind: rsync`): `rsync -avz --delete` with configurable `--rsh` SSH command; post_deploy hook; `journalctl -u nginx` log access.
- `SftpTarget` (`kind: sftp`): generates SFTP batch file; `sftp -b` tarball upload; post-upload `unpack_cmd` via SSH.
- `TargetFactory` extended: `traditional + transport=ssh+systemd`, `rsync`, `sftp/ftp`.
- 18 new tests (SystemdUnitGenerator × 6, SshSystemdTarget × 4, RsyncTarget × 3, SftpTarget × 2, TargetFactory × 3); 71 total.

**✓ Landed (2026-05-27) — v1.52.5 — Static hosting (generic):**
- `StaticTarget` (`kind: static`): four provider shapes — Vercel (vercel CLI or Deployments API v13), Netlify (netlify CLI or API), Cloudflare Pages (wrangler CLI or API; account_id via `team:`), GitHub Pages (git push to `gh-pages` branch via orphan branch). All dry-run capable. `status` via HTTP GET healthcheck. `outputs` passes URL through. `TargetFactory` extended with `"static"`. 9 new tests; 80 total.

**✓ Landed (2026-05-27) — v1.52.6 — FaaS / serverless (generic):**
- `FaasTarget` (`kind: faas`): four provider shapes — AWS Lambda (LambdaZip via `buildLambdaZip` + `aws lambda create-function/update-function-code/publish-version/update-alias`), Cloudflare Workers (`wrangler deploy` with `CLOUDFLARE_API_TOKEN`), GCP Cloud Run (`gcloud run deploy`), Vercel Functions (`vercel --prod`). All dry-run capable. `rollback` via Lambda alias version. `logs` via `aws logs tail`. `TargetFactory` extended with `"faas"/"lambda"/"serverless"`. 11 new tests; 91 total.

**✓ Landed (2026-05-27) — v1.52.7 — Remote state backends:**
- `JsonState` (minimal JSON ser/de for StateRecord; no external deps). `LocalFileStateBackend` (file-based at `~/.ssc-state/<app>/<env>/<target>.json`; sibling `.lock` file with TTL; contention detection). `S3StateBackend` (`aws s3api` subprocess; optimistic lock via head-object + mtime TTL). `ConsulStateBackend` (Consul KV HTTP API v1; session-based lock with TTL). `EtcdStateBackend` (`etcdctl` subprocess; lease-based lock). `StateBackendFactory` (backend config dispatch + production enforcement). `StateMigrator` (`ssc deploy state migrate`; dry-run; skipped/failed reporting). 14 new tests; 105 total.

## v1.53 — Traditional Payment Processors

**Status: spec landed 2026-05-27.**  `docs/traditional-payments.md` covers the full design.
Closes the `chargeCard()` placeholder from v1.38 Payment Request.
Implementation phases ship independently below.

**Locked design decisions:**
- `PaymentProvider` SPI (14 methods): `createIntent / confirmIntent / captureIntent / voidIntent` + `createCustomer / attachMethod / detachMethod / listMethods` + `createPlan / subscribe / changeSubscription / cancelSubscription` + `refund / submitDisputeEvidence` + `webhookReceiver`.
- Fiat-aware `Money(minorUnits: Long, currency: Currency)` type — replaces `Amount(String, String)` from v1.38.  ISO 4217 + crypto codes, Long minor units, banker's rounding (`HALF_EVEN`), `allocate` for split-without-remainder.
- `WebhookReceiver[E]` SPI with HMAC/RSA verify + `SeenKeyStore` idempotency + replay protection.
- `IdempotencyKey` opaque type threaded via implicit context; auto-derived from request hash as fallback; adapters pass to PSP header/field.
- `PaymentIntent` sealed state machine: `RequiresPaymentMethod → RequiresConfirmation → RequiresAction(SCAChallenge) → Processing → Succeeded | Failed | Canceled`.
- SCA / 3DS2 modelled via `SCAChallenge(redirectUrl, returnUrl, fingerprint)`.
- Subscription lifecycle: `Trialing | Active | PastDue | Canceled | Unpaid | Paused`.
- `ProrationMode: CreateProration | AlwaysInvoice | None`.
- Vault: `Customer.create + attachMethod + detachMethod + listMethods(Stream[StoredMethod])`.
- Four PSP adapter families: Stripe (canonical v1.53.1), PayPal+Braintree (v1.53.2), Adyen+Checkout.com (v1.53.3), Square (v1.53.4).
- `PaymentCapabilities` flag record (13 booleans).
- Effect-row integration via `IO[T] ! Payment` (v1.12); `MockProvider` discharges all operations in tests without network.
- `Feature.Payments` enum case in `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala`.
- No `payments:` manifest block, no `ssc payments` CLI in v1.53.
- Bank rails (SEPA / ACH / Pix / FedNow) deferred to v1.54+.
- Spec-only in v1.53; implementation phased v1.53.1–v1.53.7.

**v1.53.1 — Plugin scaffolding + Money + PaymentProvider SPI + WebhookReceiver + Stripe adapter (✓ Landed 2026-05-27):**
- New `payments/money/` subproject: `Money.scala` + `Currency.scala`.
- New `payments/webhook/` subproject: `WebhookReceiver.scala` + `SeenKeyStore.scala`.
- New `runtime/std/payments-plugin/` (2-file plugin + META-INF, mirrors `runtime/std/payment-request-plugin/`).
- `Feature.Payments` case added to `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala`.
- `payments/payment-request/.../PaymentTypes.scala:6` — `Amount` deprecated.
- New `runtime/std/payments-stripe/` — full Stripe adapter: PaymentIntent / SCA / Customer / Vault / Subscription / Refund / Dispute / Webhook.
- `examples/traditional-payments.ssc` — 12 worked snippets.
- Spec: `docs/traditional-payments.md §16`.

**v1.53.2 — PayPal Checkout + Braintree adapters (✓ Landed 2026-05-27):**
- `runtime/std/payments-paypal/` — PayPal Checkout (OAuth2, Order API, RSA webhook verify).
- `runtime/std/payments-braintree/` — Braintree (GraphQL, HMAC-SHA1 webhook).
- Spec: `docs/traditional-payments.md §11.2`.

**v1.53.3 — Adyen + Checkout.com adapters: ✓ Landed (2026-05-27)**
- `runtime/std/payments-adyen/` — X-API-Key, Checkout API v71, HMAC-SHA256 webhook over 8 sorted notification fields, Drop-in/Web Components nonce support.
- `runtime/std/payments-checkout/` — Bearer sk_xxx auth, Unified Payments API v3, HMAC-SHA256 hex over raw body, `Cko-Signature`. Both adapters: all 14 SPI methods. 25 new tests.

**v1.53.4 — Square adapter: ✓ Landed (2026-05-27)**
- `runtime/std/payments-square/` — Bearer access_token, Square Payments API v2, Web Payments SDK nonce (`source_id`), HMAC-SHA1 webhook. All 14 SPI methods. 14 new tests.

**v1.53.5 — Vault + Mandates + SCA polish: ✓ Landed (2026-05-27)**
- `ScaExemption` enum; `scaExemptions` + `mandateId` in `CreateIntentRequest`; `networkToken` + `mandateId` in `StoredMethod`; `createMandate`/`getMandate` on SPI; all 5 adapters updated. 87 total tests.

**v1.53.6 — Effect-row decomposition + MockProvider: ✓ Landed (2026-05-27)**
- `PaymentEffect` enum (Charging/Refunding/Disputing/Subscribing/Vaulting/Webhooking); `MockProvider` in-memory with `MockMode`; `MockWebhookReceiver`; `recorded*` + `reset()`. 41 new tests.

**v1.53.7 — Cluster-aware webhook idempotency: ✓ Landed (2026-05-27)**
- `RedisSeenKeyStore` (Lettuce `SET NX EX`); `PostgresSeenKeyStore` (`INSERT … ON CONFLICT DO NOTHING`); both in `payments/webhook-redis/` + `payments/webhook-postgres/`. 17 tests.

## v1.60 — Tuple Monoid ✓ Landed 2026-05-28

`Unit = ()` (0-tuple), `++` concatenation on tuples with monoid laws.
Effect runners described uniformly as `Out(E) ++ (R,)`. See `docs/tuple-monoid.md`.

**v1.60.1** ✓ — `SType.Unit = Tuple(Nil)`, `tupleConcat`, `++` type operator, `(A,)` 1-tuple syntax, 49 tests.
**v1.60.2** ✓ — `TupleV.++` in DispatchRuntime, `_tupleConcat` JS/JVM, 4+3 tests.
**v1.60.3** ✓ — `algebraic-effects.md` §8.3 unified runner table, `streams.ssc` tuple section, docs complete.
**v1.60.4** ✓ — 1-tuple ≅ element: bare-value `++` (`(A,B) ++ C`, `C ++ (A,B)`, `bare ++ bare`, `() ++ v = v`); 5+2 tests; full doc update.

## v1.61 — Performance & Memory Optimization

**Status: spec landed 2026-05-28.** See `docs/performance.md`. 8-phase roadmap; all phases gated on before/after benchmark numbers.

**v1.61.0** ✓ — Benchmark infrastructure: 8-workload corpus (`bench/corpus/`), `bench/run.sc` timing harness, `sbt-jmh` `interpreterBench` module, `scripts/bundle-size.sh`, `bench/BASELINE.md`. Landed 2026-05-28.
**v1.61.1** — Interpreter dispatch table: `HashMap[(ReceiverTag, InternedName), Handler]` + name interning. Target: ≥3× pattern-match-heavy, ≥2× arith-loop.
**v1.61.2** — Computation pure-path elimination: skip `FlatMap` wrapping for known-pure sub-trees. Target: ≥4× effect-pure.
**v1.61.3** — Env overhaul: `FrameMap` through `BlockRuntime`; eliminate per-stmt `local.toMap`; while-loop env reuse. Target: ≥2× arith-loop.
**v1.61.4** — Pattern-match compilation: per-`Term.Match` decision-tree closure cached by AST identity. Target: ≥4× pattern-match-heavy.
**v1.61.5** — JS codegen inlining: drop IIFE wrappers in statement pos; inline accessors; type-aware dispatch skip. Target: ≥30% JS speed, ≥40% smaller JS.
**v1.61.6** — Preamble sub-capabilities: Core → Console/HtmlDsl/Optics/IndexedDb/Jwt/Json. Target: ≥80% bundle reduction for Hello World.
**v1.61.7** — Memory: `IntV/DoubleV` pools, `TupleV → Array[Value]`, `FunV` split, `Span` sidecar, binary `.scim`/`.scjvm`. Target: ≥50% allocation rate reduction.

## v1.55 — First-class XML / Generic Markup

**Status: spec landed 2026-05-27.**  See `/Users/sergiy/.claude/plans/majestic-napping-moonbeam.md`.
Motivation: hand-rolled XML string templates in `SepaPainXml` / `Iso20022Xml` / Braintree / Apple plists
are fragile, unsafely escape values, and can't be validated; enterprise configs ship in XML.
XSLT deferred to v1.56.

**v1.55.1 — `markup-core` ADT + `xml"..."` interpolator + `PureMarkupCodec` + tests: ✓ Landed (2026-05-27)**
- `runtime/std/markup-core/`: `Markup` sealed ADT (`Doc/Element/Attr/Text/CData/PI/Comment/DocType/XmlDecl/QName/Raw`),
  `MarkupCodec` SPI (parse/serialize/validate), `XmlEscape` (5-entity escape/unescape),
  `PureMarkupCodec` (zero-dep XML 1.0 parser + serializer, ~300 LoC), `xml"..."` string interpolator
  (mandatory escape + `Markup.raw()` opt-out + `Markup.Element` splice).  17 tests.

**v1.55.2 — `Lang.Xml` + `SectionRuntime.runXmlBlock` + `Value.MarkupV`: ✓ Landed (2026-05-27)**
- `Lang.scala`: `Xml = "xml"`, `isXml`, label entry; `isStringBlock` unchanged (xml is not a string block).
- `SectionRuntime.scala`: `runXmlBlock` (render `${...}` → XML-escaped via `XmlEscape.escape` → parse via `PureMarkupCodec` → `MarkupV`); `renderStringBlock` signature generalised with `escapeFn: Option[String => String] = None`.
- `Value.scala`: `case MarkupV(doc: Markup.Doc)`; `show` serializes via `PureMarkupCodec.serialize`.
- `build.sbt`: added `markupCore` to `core` module's `dependsOn`.
- 8 tests in `SectionXmlBlockTest` all pass.

**v1.55.3 — `Feature.Markup` + `Backend.markupCodec` + JVM SAX codec: ✓ Landed (2026-05-27)**
- `Feature.scala`: `case Markup` (gates xml"..." interpolator + fenced xml blocks).
- `Backend.scala`: `def markupCodec: Option[MarkupCodec] = None` SPI hook.
- `JvmMarkupCodec` in `runtime/backend/interpreter/`: SAX parse (`SAXParserFactory` + `LexicalHandler`), serialize delegates to `PureMarkupCodec`, XSD validate via `javax.xml.validation.SchemaFactory`.
- `InterpreterCapabilities` + `JvmCapabilities`: declare `Feature.Markup` + `Lang.Xml` in `blockLanguages`.
- `InterpreterBackend`: override `markupCodec = Some(JvmMarkupCodec)`.
- `CapabilityCheck`: detect `xml"..."` interpolator (via `XmlInterpolatorPat`) and fenced xml blocks; reject if backend lacks `Feature.Markup`.
- 16 tests: 12 in `JvmMarkupCodecTest` + 4 new in `CapabilityCheckTest`.

**v1.55.4 — Compile-time `xml"..."` well-formedness checker: ✓ Landed (2026-05-27)**
- `MarkupInterpolatorCheck` in `lang/core/.../transform/`: walks scalameta trees in scalascript blocks,
  joins `Term.Interpolate("xml", parts, _)` string parts with `<placeholder/>` for each hole, calls
  `PureMarkupCodec.parse` at compile time, emits `Diagnostic.XmlParseError(message, line, col)`.
- `Diagnostic.XmlParseError` added to `backend/spi/Diagnostic.scala`.
- `markupCore` added as a dependency of the `core` sbt module.
- 10 tests in `MarkupInterpolatorCheckTest` (valid self-closing, open/close, interpolation, nested,
  attributes, namespace, unclosed tag, mismatched tags, bad attribute, two-errors-in-one-file).

**v1.55.5 — Element-literal AST (`<foo bar={expr}/>` syntax): ✓ Landed (2026-05-27)**
- `MarkupLiteralLower` AST transform in `lang/core/transform/`; opt-in via `import scalascript.markup.*`; namespaced tags, nested elements, text children, string + expression attributes. 16 tests.

**v1.55.6 — XSD validation + refactor `SepaPainXml`/`Iso20022Xml` onto `xml"..."`: ✓ Landed (2026-05-27)**
- `ValidationError(message, line, column)` in `MarkupCodec`; `SepaPainXml` (PAIN.001/008 + SCT Inst pacs.008) and `Iso20022Xml` (FedNow pacs.008) refactored from string concat to `xml"..."`; `SepaPainXmlGoldenTest` (22 tests) + `Iso20022XmlGoldenTest` (11 tests); 105 total tests pass.

**v1.55.7 — `.xml` ConfigParser ingest + `markup-js`/`markup-node` codecs + ServiceLoader: ✓ Landed (2026-05-27)**
- `ConfigParser.Format.Xml` + `detectFormat` (.xml extension); `XmlConfigParser` (element → `ConfigValue.Map`, CDATA, repeated tags → `Lst`); `runtime/std/markup-js/` (browser DOMParser/XMLSerializer Scala.js codec); `runtime/std/markup-node/` (`@xmldom/xmldom` Node.js codec); `markupCore` cross-compiled JVM+JS. 41 tests.

## v2.1 — Distributed Streams (Beam-style)

**Status: spec landed 2026-05-27.**  `docs/distributed-streams.md` covers the full design.
Implementation phases ship independently below.

- [x] **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` types + native bounded
  backend (wraps `Dataset[T]` partitions; no watermarks yet); `DirectRunner` test backend;
  `Feature.DistributedStreams` flag; `examples/distributed-streams.ssc`.
  Spec: `docs/distributed-streams.md §13`. (2026-05-27)

- [x] **v2.1.2-dstream-native-unbounded** — Processing-time windowing (`window`, `withTrigger`,
  `withAllowedLateness`, `withWatermark`), `timerProcessing(d)(f)`, `EventTime`+`WatermarkPerfect`
  capabilities on DirectRunner/Native. Spec: `docs/distributed-streams.md §13 v2.1.2`. (2026-05-27, 30 tests)

- [x] **v2.1.3-dstream-spark** — `SparkGen` extended: `containsDStream` detection + `dstreamSparkShim`
  emission inside `@main`. Full DStream DSL (v2.1.1+v2.1.2 operators) backed by driver-local `Seq[Any]`
  for bounded sources; `Feature.DistributedStreams` in `SparkCapabilities`. 14 new `SparkGenTest` tests;
  integration tests gated by `SPARK_MASTER`. Spec: `docs/distributed-streams.md §9.2`. (2026-05-27)

- [x] **v2.1.4-dstream-kafka** — Kafka Streams backend: `runtime/backend/kafka-streams/` module,
  `KafkaStreamsGen` with `containsDStream` + `dstreamKafkaShim` emission, `Backend.KafkaStreams`/`Kafka`
  aliases, `TopologyTestDriver` helpers, `Feature.DistributedStreams` in `KafkaStreamsCapabilities`,
  22 new `KafkaStreamsGenTest` tests. Spec: `docs/distributed-streams.md §9.3`. (2026-05-27)

- [x] **v2.1.5-dstream-flink** — Flink + Beam backends: `runtime/backend/flink/` module, `FlinkGen`
  (DataStream API shim, `_flinkEnv()` helper) + `BeamGen` (Java SDK shim, `_createBeamPipeline()`,
  runner dep auto-selection), `FlinkBackend`/`BeamBackend` SPI adapters, `FlinkCapabilities`/
  `BeamCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration, 30 new tests.
  Spec: `docs/distributed-streams.md §9.4–9.5`. (2026-05-27)

- [x] **v2.1.6-dstream-connectors** — Production connector stubs: `Kafka`, `Files`, `FileFormat`,
  `Jdbc`, `Pulsar`, `Kinesis` companions in all 4 shims + native interpreter intrinsics.
  `containsConnector` detection triggers shim emission. `DSource.fromDataset` bridge.
  SparkGen Kafka dep extended to cover `Kafka.source/sink/changelog` DStream usage.
  14 new tests. Spec: `docs/distributed-streams.md §6`. (2026-05-27)

- [x] **v2.1.7-dstream-stateful** — Stateful processing + timers: `statefulMap`, `statefulFlatMap`,
  `broadcastState`, `timerEventTime`; `ValueState`, `MapState`, `ListState`, `BagState`;
  `StateContext[K,S]`, `KeyedStateSpec[K,S]`. All 4 code-gen shims + native interpreter.
  +20 new tests. Spec: `docs/distributed-streams.md §5`. (2026-05-27)

- [x] **v2.1.8-dstream-side-io** — Side inputs / side outputs: `SideInput[T]` + `object SideInput`
  (`of`, `singleton`, `asMap`); `OutputTag[B]` + `object OutputTag`; `DStream.withSideInput(si)`;
  `DStream.sideOutput(tag)` returning `(DStream[T], DStream[B])`. All 4 shims + native interpreter.
  +8 interpreter tests, +8 generator tests (Spark/Kafka/Flink/Beam). Spec: `docs/distributed-streams.md §5.6`. (2026-05-27)

- [x] **v2.1.9-dstream-joins** — Windowed joins + flatten: `DStream.join(other)` (inner, KV key),
  `leftOuterJoin(other)`, `rightOuterJoin(other)`, `flatten` (stream of streams).
  All 4 shims + native interpreter. `Capability.WindowedJoins` already declared.
  +8 interpreter tests, +8 generator tests (Spark/Kafka/Flink/Beam). Spec: `docs/distributed-streams.md §5.7`. (2026-05-27)

- [x] **v2.1.10-dstream-conformance** — Cross-backend conformance suite (§14.3): new
  `runtime/backend/conformance/` module (`backendConformance`) with `DStreamConformanceTest`
  (8 tests): word count, windowed word count, stateful sum, side inputs, joins, connectors,
  backend aliases, full operator surface. All 8 tests pass across Spark/KafkaStreams/Flink/Beam.
  SparkGen + KafkaStreamsGen `Backend` object extended with missing `Flink`/`Beam` aliases (now
  all 4 shims declare all 7 backend aliases). `examples/distributed-streams.ssc` expanded from
  3 examples to 12, covering v2.1.2–v2.1.9 operators. (2026-05-27)

---

## v2.0 — Separate compilation of modules

**Status: working separate compilation landed 2026-05-19.**  All six
stages from the spec are implemented; the pipeline is exercised end-to-end
via CLI subprocess tests (`emit-interface → check-with-iface → emit-ir →
link → build --incremental`); the JVM backend produces per-module `.scjvm`
artifacts that the linker combines incrementally.  Tracking doc:
`docs/separate-compilation-plan.md`.

Test coverage: 522 core tests + 75 CLI subprocess smoke tests, all green.

Stage 5.4 / final round (landed 2026-05-19):
- `parseSType` and `SType.show` round-trip now handle union types
  `A | B`, intersection types `A & B` (with `&` binding tighter than `|`),
  and higher-kinded `F[_]` / `F[_, _]`.  Refinement types and match types
  still degrade to `SType.Any` (intentionally out of scope).
- `Typer` strict mode (`Typer(strict = true)`): when set, references to
  undefined `Term.Name` identifiers record a diagnostic on `Typer.errors`
  without crashing.  Scoped down conservatively: only flags bare
  camelCase / underscore-led identifiers; skips operators, method
  selectors, `Term.New`, lambdas, partial functions.
- `ssc check-with-iface` now uses strict mode + exits non-zero on
  diagnostics — actually catches undefined references in consumer code.
- JS backend incremental output: `.scjs` artifact + `ssc compile-js` +
  `ssc link --backend js [-o out.js]` + `ssc build --incremental --backend js`.
  Same shape as the JVM pipeline; longest-common-prefix dedup of the JS
  runtime preamble.

Stage 5.5 / robustness + ergonomics (landed 2026-05-19):
- **Auto-resolve**: `ssc compile-jvm/compile-js <target.ssc>` now walks
  the target's import closure, topo-sorts via Kahn, emits cycle traces
  on detection, and compiles each stale dep in order before the target.
  Default artifact dir: `<target-dir>/.ssc-artifacts/`; `--artifact-dir
  <dir>` override; `--no-auto-deps` for back-compat single-module behavior.
- **Linker dedup pass**: after the existing longest-common-prefix dedup
  of runtime preambles, both `mergeScalaSources` and `mergeJsSources` now
  drop duplicate top-level `def` / `val` / `class` / `object` declarations
  by name (first occurrence wins).  Defensive against conditional-runtime
  variation (module A uses effects, module B doesn't → different preambles
  → tail-dup of `_handle` etc.).  Strips modifier chains (`private`,
  `implicit`, `final`, `sealed`, `case`, `inline`, `lazy`, `async`),
  handles brace AND indentation-based bodies, string/comment-aware.
- **Strict-mode Select check**: extended `Typer` strict to also flag
  `Select(VarRef(importedModule), missingMember)` when the qualifier
  resolves to a known interface and the member is not exported.  Skips
  local-value receivers and dynamic-typed selectors; one diagnostic per
  miss (no double-report when qualifier itself is undefined).
- **`ssc info <artifact>`**: inspector for any `.scim` / `.scir` /
  `.scjvm` / `.scjs` envelope.  Plain-text mode dumps key=value lines
  with exports, hashes, byte counts; `--json` mode re-emits the
  canonical envelope through the same writer used to produce it.
  Failure modes (missing file, unknown extension, magic/ABI mismatch)
  exit non-zero with clear diagnostics.

Stage 5.6 / battle-test + JvmGen fixes (landed 2026-05-19):
- **Battle-test against real runtime/std/ modules**: 10 new tests against
  `runtime/std/eq.ssc`, `runtime/std/show.ssc`, `runtime/std/hash.ssc`, `runtime/std/order.ssc`,
  `runtime/std/dsl/*.ssc`, `runtime/std/parsing/*.ssc`.  ~50 % of runtime/std/ compiles and links
  end-to-end (the typeclass + ADT + dsl combinator idiom).  Surfaced
  4 concrete bugs documented in test TODO markers — see "Known gaps".
- **JvmGen effect-runtime emission fixes**:
  - Bare-name actor intrinsics (`subscribeClusterEvents()`, `clusterMembers()`)
    at `val rhs` now route through `emitExpr` and rewrite to `Actor.*`.
  - `blocksUseActors` now also fires on pattern-only modules — a module
    that only does `case NodeJoined(id) => ...` correctly pulls in the
    effects runtime and emits the matching case-class definitions.
  - Overloads of `serve` and `onWebSocket` collapsed into single defs with
    default arguments, so the v2.0 linker dedup pass doesn't drop them.
- **`ssc deps <file.ssc> [--graph]`**: prints the resolved import-closure
  in topo order (with optional `from → to` edges).  Useful for CI and
  for understanding what `compile-jvm`/`compile-js` will recursively
  compile before invocation.
- **Deep Select chains in strict mode**: `Typer` now recursively resolves
  `a.b.c` qualifier chains.  Single diagnostic at first break (no cascade).
  `ExportedSymbol.nested: List[ExportedSymbol]` field added to the IR
  (backward-compat default `Nil`).
- **Extractor populates `ExportedSymbol.nested`** (follow-up, 2026-05-19):
  `InterfaceExtractor` recursively walks `Defn.Object` bodies up to
  `MaxNestedDepth = 3` and emits `nested` entries for inner `def` / `val` /
  `class` / `object` / `trait` / `enum` members (names + FQNs exact, types
  best-effort `Any`).  Strict-mode deep-Select checks now reject unknown
  members through real `.scim` sub-namespaces instead of falling to
  permissive.  Package-shell walks also produce correctly-populated
  `nested` for sub-namespaces inside the shell.

Stage 5.7 / production blockers fixed (landed 2026-05-19):
- **Anonymous given witness identity**: `given Eq[Int] with { ... }` now
  synthesizes `witnessName = "given_Eq_Int"` and `fqn = "pkg_given_Eq_Int"`
  (or just `given_Eq_Int` when `pkg` is empty).  Pure structural identity
  — no hashes, deterministic across builds.  Type-arg type variables are
  dropped from the head name for cross-build stability (so `given Eq[List[A]]`
  → `given_Eq_List`).  Affects every typeclass instance in `runtime/std/`.
- **Structured parse diagnostics**: `Content.CodeBlock` carries an optional
  `parseError: Option[CodeBlockParseError]` with `(message, line, column,
  snippet)`.  All 8 CLI surfaces (`compile-jvm`/`-js`, `emit-interface`,
  `emit-ir`, `check-with-iface`, `build --incremental`, and auto-resolve
  dep helpers) print a 3-line snippet with `^` caret on parse failure
  instead of "Failed to parse scalascript code block".
- **YAML front-matter diagnostic**: wraps SnakeYAML's
  `ScannerException` to add the offending source line + `^` pointer + a
  targeted hint when the line contains `': '` (the most common cause:
  unquoted colons in string values).  Also quoted the `description:` in
  `runtime/std/parsing/recovery.ssc` so the parsing module set builds clean.
- **`ssc deps <file.ssc> [--graph]`**: prints the resolved import
  closure in topo order with `from → to` edges.

Phase 2 / bytecode linker (landed 2026-05-19):
- **MVP** (`--bytecode` opt-in): `ssc compile-jvm --bytecode` invokes
  `scala-cli` internally to produce real `.class` files; the artifact
  carries `classBundle` (base64-encoded ZIP of `.class` + `.tasty`).
  `ssc link --backend jvm --bytecode <dir> -o out.jar` extracts each
  bundle, dedups by FQN, packs into a single JAR.  Cross-module classpath
  wired by extracting deps' classBundles into a shared temp dir passed
  to scala-cli as `--jar`.  All scala-cli invocations use `--server=false`
  to avoid Bloop-daemon collisions under parallel test load.
- **Deep refactor** — runtime separated from user code:
  - `JvmGen.generateRuntime(capabilities)` emits the ~570KB runtime
    preamble wrapped in `object _ssc_runtime:` (top-level object — Scala 3
    forbids companion pairs at package scope).  Compiled once per
    artifact-dir into a `.scjvm-runtime` artifact (capabilities = union
    of all modules').
  - `JvmGen.generateUserOnly(module)` emits user code with
    `import _ssc_runtime.{given, *}` prepended.  No preamble.
  - `compile-jvm --bytecode` + `ensureRuntimeArtifact` automate runtime
    generation; `ssc compile-runtime --capabilities <list>` lets users
    drive it explicitly.
  - `linkJvmFromBytecode` packs the shared runtime classBundle once +
    each module's user classes.
  - 7 capabilities tracked: effects, mutual-tco, reactive, serve, mcp,
    dataset, json.
  - **Size impact**: per-module `.scjvm` shrinks from 515 KB to **10 KB**
    (51× reduction).  2-module out.jar: 554 KB → 301 KB (45% reduction).
    Per-additional-module cost: ~250 KB → ~3 KB of unique user classes.

Phase 2 follow-up (landed 2026-05-19, closes the last 3 known gaps):
- **Refinement + match types in `parseSType`**: `A { def foo: Int }`,
  `A { type T = Int }`, `T match { case Int => String; case _ => Any }`
  now parse to `SType.Refinement(base, members)` / `SType.Match(scrutinee,
  cases)` with full round-trip via `SType.show`.  11 new tests; all
  existing `SType` match sites stayed permissive via catch-all arms.
- **`build --incremental` unified diagnostic flow**: per-module work is
  wrapped in a stderr-redirect-to-buffer block; on failure the captured
  cause (YAML diagnostic, parse error position, etc.) is spliced onto
  stdout under the `... FAIL` line, indented 4 spaces.  Standalone
  `ssc compile-jvm` still emits to stderr.  CI scripts capturing only
  stdout now see both summary and cause.
- **JS-side runtime separation** (mirror of JVM Phase 2 deep refactor):
  `JsGen.generateRuntime(capabilities)` + `generateUserOnly(module)`
  emit module-only JS.  `.scjs-runtime` artifact format stores the
  shared runtime; modules ship only their unique JS.  Flat-scope concat
  (not IIFE) because runtime exports 200+ identifiers user code
  references unqualified.  5 capabilities: Core, Async, Effects, Mcp,
  Dataset.
  - **Size impact**: per-module `.scjs` shrinks from 80–150 KB to
    ~500 bytes (**200× reduction**); `_runtime.scjs-runtime` is 142 KB
    compiled once.  `out.js` stays at 139 KB (runtime needed at runtime).
    The win is incremental-rebuild bandwidth, not link output size.
  - **3 JS-specific edge cases** fixed: `EffectAnalysis` was seeding
    effectOps with builtin names (always non-empty), `_println` needs
    explicit flush at end of linked `out.js`, `--backend` is a global
    flag stripped by `GlobalFlags.parse` before command handlers see it.

**v2.0 separate compilation is feature-complete** for the planned scope.

Phase 3 / operational hardening (landed 2026-05-19):
- **TASTy direct scalac driver**: `cli/Scala3Driver` invokes
  `dotty.tools.dotc.Driver` in-process; warm per-module compile drops
  from 1410ms to **119ms (~11.8× speedup)**.  Custom `Reporter` formats
  diagnostics scala-cli-style.  `scala3-compiler` pinned to `3.8.3`.
  Fresh `Driver`/`Compiler` per invocation (no state leak).  scala-cli
  path kept as fallback via `SSC_EXTERNAL_SCALA_CLI=1` env var.
  `.scjvm` byte-identical between both paths.
- **ABI compatibility test suite**: 64 forensic tests covering 7
  artifact formats × 9 properties — round-trip stability, magic/version
  mismatch rejection, optional-field defaulting, unknown-extra
  tolerance, hash preservation.  Surfaced one sharp edge (Option[String]
  without explicit `= None` default isn't absent-tolerant — split tests
  into `optionalFields` vs `requiredOptionTypedFields`).
- **`docs/v2.0-artifact-format.md`**: 333 LoC wire-format spec.
  Compatibility policy in one phrase: "strict-equality on envelope,
  additive-friendly on payload."
- **`ssc verify <dir>`**: operational health-check command. Walks
  artifact dir, validates envelope + ABI + sourceHash shape + cross-refs
  + runtime coverage.  `--strict` adds source-freshness check;
  `--json` for machine-readable CI output.  Output uses `[OK]/[WARN]/[FAIL]`
  markers (safer than ✓/⚠/✗ glyphs in non-UTF8 terminals).

Test coverage after Phase 3: **522 core tests + 75 CLI subprocess
smoke tests**, all green.

Phase 3+ / final tooling round (landed 2026-05-19):
- **Source maps** (opt-in via `--source-map` on `ssc link`):
  - JVM (Option B): sibling `<moduleId>.ssc.scala` source file next to
    `out.jar`.  `.class` files' `SourceFile` attribute already names the
    Scala wrapper; IDEs source-attach via filename match.  No ASM dep.
  - JS: V3 source maps via hand-rolled VLQ writer (~180 LoC).  Appends
    `//# sourceMappingURL=out.js.map` to `out.js`.  Line-granularity
    mappings: runtime preamble unmapped, user-code lines map back to
    their `.ssc` source.  6 tests.
- **Per-section incremental** (opt-in via `build --incremental
  --section-cache`):
  - `sectionHashes: Map[String, String]` additive field on all 4
    artifact types.  Cumulative-hash chain (Option A): section N's
    hash includes its source + all prior sections' hashes joined.
  - `ModuleGraph.staleSections(srcPath, artifactDir)` returns the
    cumulative-stale list.  Edit last section → only it is stale; edit
    first → full cascade (preserves shared-scope safety).
  - `ssc info --sections` dumps the chain for any artifact.
  - 9 new tests.  Backward-compat: empty map default; ABI tests pass.
- **LSP server** (new `ssc lsp` command, 819 LoC server + 514 LoC tests):
  - Stdio JSON-RPC over hand-rolled Content-Length framing, no
    third-party LSP libs.
  - Methods: `initialize`, `initialized`, `shutdown`, `exit`,
    `textDocument/{didOpen,didChange,didClose,definition,hover,
    publishDiagnostics,completion}`.
  - Capabilities: full text-document sync, `definitionProvider`,
    `hoverProvider`, `completionProvider` (triggerCharacters: `.` ` `).
  - Loads `.scim` artifacts from `initializationOptions.artifactDir`
    (or workspace scan) for cross-module symbol resolution.
  - 25 LSP tests pass (16 protocol + 8 handlers + 1 integration
    spawning `ssc lsp` subprocess for full handshake).
  - **`textDocument/completion` landed (2026-05-19)**: prefix-filtered
    `CompletionList` combining user-defined symbols (from `TypedModule`),
    imported `.scim` interface symbols, and 27 built-in keywords.
    Item kinds: Function(3)/Constructor(4)/Variable(6)/Keyword(14).
    7 new handler tests; all 19 `LspHandlersTest` + 113 cli tests green.
  - **LSP Phase 2 landed (2026-05-20)** — five new methods + extended
    integration coverage:
    - `textDocument/references` — finds all use-sites of the name under
      cursor in the open document; returns `Location[]`.
    - `textDocument/prepareRename` — returns the `Range` of the
      renameable token, or `null` if the cursor is not on a name.
    - `textDocument/rename` — renames all occurrences in the open document;
      returns `WorkspaceEdit { changes: { uri: TextEdit[] } }`.
    - `textDocument/documentSymbol` — flat `SymbolInformation[]` with a
      Module heading + all scalameta `Defn.Def` / `Defn.Val` / `Defn.Var`
      / `Defn.Object` / `Defn.Class` / `Defn.Trait` names per block.
    - `workspace/symbol` — cross-document + cross-`.scim` substring search
      (case-insensitive, capped at 200); also searches pre-loaded interface
      symbols from the artifact dir.
    - `textDocument/signatureHelp` — backward character scan from cursor
      locates innermost unclosed `(`; comma-counts the active parameter;
      looks up the `def` via scalameta AST with a line-by-line text fallback
      when the block fails to parse (cursor inside incomplete call).
      Trigger chars: `(`, `,`.
    - `LspServerIntegrationTest` extended from 1 to 7 end-to-end tests
      covering all new methods; `withLspServer` / `initialize` / `didOpen`
      / `req` helpers eliminate per-test subprocess boilerplate.
    - 46 unit handler tests + 7 integration tests — all green.
  - **`runtime/std/actors.ssc` compile-jvm flip (2026-05-20)** — `V2RealStdModulesTest`
    expectation updated from "must fail" to "must succeed (exit 0, produces
    `.scjvm`)" after the codegen caught up with every `extern def` in
    `runtime/std/actors.ssc`.
  - **SectionDiff / AST-cache diff (2026-05-19)** — `SectionDiff` computes
    structural diffs between two parsed `Module`s at the section level; the
    incremental JVM/JS build pipelines consult it to skip re-emitting
    sections whose content hash and AST are unchanged.

**Final test coverage**: **531 core + ~115 CLI subprocess smoke tests**,
all green.

**v2.0 separate compilation is now ALL-DELIVERABLES-LANDED.**  The
documented "remaining post-Phase-3 directions" are now done.

Phase 4 / honesty-pass follow-ups (landed 2026-05-19):

The "ALL-DELIVERABLES-LANDED" line above hid a few sharp edges that
the implementing agents flagged in code comments as `TODO`s.  An
honesty-pass round addressed the most-impactful ones (all five
landed, 50+ new tests, 546 core + ~75 CLI subprocess green):

1. **LSP positional accuracy** — `ExportedSymbol` gets `definitionLine`
   + `definitionColumn` fields populated by `InterfaceExtractor` from
   scalameta positions; `Content.CodeBlock` gets `lineOffset` populated
   by `Parser` from CommonMark line numbers.  LSP cross-module
   go-to-definition stops returning `(0,0)`; multi-block hover/definition
   no longer reports block-local lines as if blocks start at 1.

2. **JVM source-maps Option A** — Phase 3+ landed Option B (sidecar
   `.ssc.scala` next to JAR; IDE source-attach via filename).  Option A
   injects JSR-45 SMAP into `SourceDebugExtension` attribute of each
   `.class` via ASM, so `java -jar out.jar` stack traces resolve to
   `.ssc` line numbers, not synthetic Scala lines.  Adds `lineMap`
   field to `ModuleJvmArtifact` (string-keyed for upickle reliability)
   and new `JvmSmap` + `JvmSmapInjector` modules.

3. **3 pre-existing `JvmBytecodeLink` failures** — `Main method not
   found in class a_sc` when `java -cp out.jar a_sc` runs.  Multiple
   agents constatated "not our regressions" without diagnosing.  The
   final-polish round looks at scala-cli's script-mode `<Name>$package`
   companion-vs-direct main-emit convention.

4. **`ssc clean <dir>`** — garbage-collect artifacts for sources that
   no longer exist.  `--dry-run`, `--all` flags.  Closes the "no GC"
   UX gap.

5. **Reproducibility tests** — pin byte-identical output across two
   `compile-jvm` invocations.  ZIP entries' timestamps fixed to epoch,
   sorted alphabetically.  Any non-deterministic source surfaced gets
   fixed in lockstep.

After Phase 4, the documented gaps are:

- **Per-section Option B (interface-based)** — current cumulative-hash
  chain cascades on first-section edit; an interface-aware variant
  would only re-emit sections whose public API changed.  Deferred —
  needs per-section interface extraction infrastructure.

- **Scale benchmark** — perf measured on trivial 2-module fixture.
  A real benchmark over 30+ `runtime/std/` modules at full `--bytecode
  --section-cache --source-map --strict` toggles is owed.

- **Cross-platform smoke** — all tests assume Unix paths.  Windows
  path separators, CRLF line endings (for `sourceHash`), file-locks
  not covered.

- **External `.sscpkg` artifact-level distribution** ✓ Landed (v2.0
  Phase 5).  `ssc bundle --with-artifacts` runs `build --incremental
  --backend jvm` + `--backend js` on the inputs, then bundles the
  produced `.scim` / `.scjvm` / `.scjs` files under a `.ssc-artifacts/`
  prefix inside the `.sscpkg`.  Consumer-side: `compile-jvm` and
  `compile-js` resolve each `dep:` import via `ImportResolver`, then
  `findArtifactAlongside(sscPath, ext)` discovers `<dir>/.ssc-artifacts/
  <basename>.<ext>` (auto-detect, no manifest schema change) and stages
  the artifact into the local artifact dir so the typer + linker pick
  it up directly.  Source-fallback when no artifacts ship; bad-magic
  artifacts surface a clear error.  5 new CLI tests in
  `SscpkgArtifactDistributionTest`.

- **Getting-started tutorial** — `docs/v2.0-artifact-format.md` is the
  wire spec; a user-facing "compile your first project with v2.0"
  doc is owed.

What landed:
- `ir/Ir.scala`: `ArtifactVersion` (magic `SSCART` + ABI `2.0`),
  `ModuleInterface`, `ExportedSymbol`, `InstanceDecl`, `CapabilityDecl`,
  `ModuleIrArtifact` — all `derives ReadWriter`, JSON round-trip from day one.
- `core/artifact/InterfaceExtractor.scala`: extracts `ModuleInterface` from
  a parsed AST module; SHA-256 source hash in every artifact.
- `core/artifact/ArtifactIO.scala`: `.scim` / `.scir` read/write with ABI
  version guard on every read.
- `core/artifact/InterfaceScope.scala`: populates a `Typer.Scope` from a
  pre-compiled interface; used by `ssc check-with-iface`.
- `core/artifact/Linker.scala`: merges compiled modules in dep order; FQN
  mangling and cross-module collision detection.
- `core/artifact/ModuleGraph.scala`: Kahn's topo-sort of `.ssc` files;
  `isStale` compares SHA-256 source hash vs artifact.
- `core/typer/Typer.scala`: `Typer(importedInterfaces)` constructor + 
  `Typer.typeCheckWithInterfaces` factory — backward-compatible.
- `cli/Main.scala`: six new commands — `emit-interface`, `emit-ir`,
  `check-with-iface`, `link`, and `build --incremental`.

Post-MVP additions (also landed 2026-05-19):
- `InterfaceExtractor` respects `exports:` front-matter — only declared
  names appear in `.scim`; private helpers stay invisible to consumers.
- `InterfaceExtractor` walks package-wrapped nested objects — `Parser.wrapSectionInPackage`
  rewrites blocks as `object foo: object bar: <body>`; extractor now
  recurses into nested `Defn.Object` so packaged modules (e.g. `runtime/std/dsl/pretty.ssc`)
  expose their inner types in `.scim`.
- `Linker` FQN-rewrite end-to-end tests (`LinkerRewriteTest`, 7 cases):
  top-level `VarRef` rewrite, lambda shadowing, multi-import multi-call-site,
  cross-module collision detection — exercised against real IR from
  `Normalize` + `AstToIr` rather than hand-rolled IR.
- CLI subprocess smoke tests (`V2ArtifactCliTest`, ~370 LOC): end-to-end
  `emit-interface`, `emit-ir`, `check-with-iface`, `link`, and
  `build --incremental` exercised at the `ssc` process boundary.
- `InterfaceExtractor` AST-based capability + extern detection:
  replaces text-scanning heuristics with proper scalameta AST traversal.
- `InterfaceScope.parseSType`: real Scala-style type parser instead of
  string splitting (handles generic, union, and intersection types).
- `Normalize` emits `IrExpr` bodies to unblock Linker rewrites.
- `wrapSectionInPackage` now applies `preprocessExtern` / `preprocessEffects`
  before wrapping — fixes silent parse failures for `runtime/std/*` files with
  both `package:` frontmatter and `extern def` surface forms.

Stage 5.3 / typer / JVM-incremental (landed 2026-05-19):
- `Linker.rewriteExpr` folds `Select` chains: `Select(VarRef("a"), "bar")`
  where module A (pkg=`["a"]`) exports `bar` collapses to `VarRef("a_bar")`.
  Handles multi-segment packages too (`std.dsl.foo` → `VarRef("std_dsl_foo")`).
- `ssc link -o foo.scir` now writes a deterministic composite SHA-256
  (joined input hashes) instead of the literal string `"linked"`.
- `Typer` real type inference for top-level signatures (`Defn.Def`,
  `Defn.Val`, `Defn.Class`): declared return types parse via `parseSType`;
  inferred return types use simple bidirectional propagation (literals,
  arithmetic on Int, block last-stat, converging if/else).  Complex
  bodies still fall back to `SType.Any`.  Closes the "everything is `Any`"
  gap that made `.scim` interfaces near-useless.
- `ssc compile-jvm <file.ssc> [-o out.scjvm] [--iface-dir <dir>]` — single-
  module JVM compile, emits a `.scjvm` artifact (SSCART-framed JSON wrapping
  per-module emitted Scala 3 source + SHA-256 + import hints).
- `ssc link --backend jvm <dir> [-o out.jar | out.scala]` — combines
  per-module `.scjvm` artifacts via textual concat + runtime-prefix dedup.
  MVP limitation: each `.scjvm` carries the full JvmGen runtime preamble;
  the linker finds the longest common whole-line prefix and emits it once.
  Real bytecode-level mangling is Phase 2.
- `ssc build --incremental --backend jvm <dir>` — emits `.scim` + `.scir`
  + `.scjvm` per stale module; SHA-256 staleness check makes the build
  truly incremental (untouched modules skip codegen).

Auto-resolve for per-module compile (landed 2026-05-19):
- `ssc compile-jvm <file.ssc>` and `ssc compile-js <file.ssc>` now walk
  the target's `Content.Import` closure, topo-sort the local-path
  dependency DAG, and recursively compile every stale dep into a
  shared artifact dir before compiling the target.  Dep artifacts
  default to `<target-dir>/.ssc-artifacts/` and are reused on
  subsequent runs (SHA-256 freshness check on both `.scim` and the
  backend-cache file).  No more "compile a first, then b" topo dance.
- New flags: `--artifact-dir <dir>` overrides where dep artifacts
  land; `--no-auto-deps` reverts to the old per-module behaviour
  (still relies on `--iface-dir` for cross-module type-checking).
- Cycles are detected before any codegen runs; the command exits
  non-zero with a `→`-joined cycle message naming the modules.
- New helper: `cli/AutoResolve.scala` (~190 LOC).  Reuses
  `InterfaceExtractor.sha256` + `ArtifactIO` / `JvmArtifactIO` /
  `JsArtifactIO` for freshness checks; does its own DFS-then-Kahn
  pass rather than reusing `ModuleGraph.build` so traversal starts
  at a single file rather than a whole directory.
- Tests: `AutoResolveCliTest` (9 cases) — two-module, three-module,
  idempotency, cycle, `--no-auto-deps`, JS-side parity, and
  `--artifact-dir` override.

Default `ssc compile` / `ssc build` / `ssc run` are completely unchanged.
The new commands are additive; the ABI commitment is in place from day one.

Today every `ssc compile` parses, types, normalises, and emits the
entire reachable module-tree in a single pass.  Separate compilation
means each module compiles independently into an IR artifact +
interface; consumer modules link against pre-compiled artifacts
instead of re-parsing every source.

Analogues: Haskell `.hi` + `.o`, OCaml `.cmi` + `.cmo`, Scala
`.tasty` + `.class`, Rust crates.

### What it unlocks

- **Incremental build speed.**  Stdlib + third-party packages don't
  re-parse on every `ssc compile`.  Important for large projects
  and IDE/language-server analysis loops.
- **Distributable libraries.**  Someone ships a `.sscpkg` with
  pre-compiled IR; consumer compiles only their own code against
  the package's interface.  Without source disclosure required.
- **Compilation caching across CI runs** — keyed on
  module-content hash, not whole-tree hash.
- **IDE support.**  Language server analyses one module at a time;
  cross-module references resolve through interface lookups,
  not full re-parse.

### What's needed

1. **Module boundary definition.**  What is a module?  A directory
   with a `package.yaml` declaring exports?  A single `.ssc` file?
   Decision needed before anything else.
2. **Interface artifact** (`.scim` or similar) — exported types,
   `extern def` signatures, typeclass instances, capability
   declarations.  No bodies.  Stable across builds within the
   same compiler version.
3. **IR artifact** — body IR in JSON / msgpack, the v0.1 SPI's
   `NormalizedModule` serialisation generalised per module.
4. **Type-checker that consumes interfaces without bodies.**  Today
   the typer sees the full module-tree; separate compilation
   requires it to type-check against a foreign module's interface
   alone.
5. **Linker pass.**  Collect compiled artifacts, resolve symbol
   references across modules, hand a fully-linked
   `NormalizedModule` to `Backend.compile`.
6. **Stable IR ABI.**  Decade-long commitment per Haskell `.hi`
   versioning practice — adds a `.scim` magic number / version
   guard so incompatible artifacts are detected, not silently
   miscompiled.
7. **Symbol mangling.**  Cross-module-name collisions resolved
   through fully-qualified mangled names; consumer-side imports
   rewrite to mangled forms before linking.
8. **Build orchestration.**  Dependency graph between modules;
   parallel + incremental rebuild logic; `Makefile`-style
   timestamp tracking or content-hash based.

### Cost

Realistically **2-3 months of focused work** by one person.  Every
piece of the pipeline is touched — parser carries through to
linker.  The hidden cost is the **stable IR ABI commitment** —
once shipped, the ABI freezes design space for the IR
representation.

### Prerequisites

  - Stage 5+/B + 5+/D (intrinsic-module extraction) **landed** —
    otherwise the "modules" concept is fragmented between SourceLanguage
    plugins, Backend plugins, and embedded platform code.
  - SPI v0.1 IR serialisation **shipped** — already done (Stage 2).
  - Real motivating use case — at least one third-party package
    that benefits from being distributed pre-compiled.

### Why deferred

Single-program compilation is the dominant scenario today; the
existing whole-tree compile is fast enough for the size of programs
we see.  Separate compilation pays off when:

  - A package ecosystem emerges (multiple third-party `.sscpkg`s
    in active use).
  - Build times exceed comfort (>30s incremental).
  - IDE / language-server demand emerges.

None of these are true in 2026.  Tracked here so the conversation
isn't restarted from scratch when one becomes true; the design
direction is clear, the cost is honest, the prerequisites are
listed.

### Considered alternatives — rejected

- **Bytecode-level caching.**  Cache `Backend.compile` output keyed
  on source hash.  Cheaper to implement but doesn't unlock
  distribution-without-source.  Could land as a smaller win on
  the way to full separate compilation.  Promote if real users
  ask.
- **Whole-program incremental** (Salsa-style demand-driven
  recomputation).  Faster builds without ABI commitment.
  Heavier implementation cost (architectural rewrite of the
  compilation driver); same outcome.  Defer pending Bazel /
  cargo-style use case.

## Interpreter ergonomics — carried over from v1.1

Three friction points surfaced while building the v1.1 typeclass
library.  Each was worked around at the call site (helpers instead
of extensions, typed-`val` instead of ascription) to keep the
milestone narrow, but each leaves an unergonomic seam users will
hit again.  Roughly a session each; pick up when the next milestone
that uses them lands.

1. **Sealed-trait extension dispatch in the interpreter.** ✓ Landed
   with v1.13.  `sealedParents` registry walks the parent chain so
   `Right(…)` reaches extensions on `Either`.  `runtime/std/bifunctor.ssc`
   and `runtime/std/monaderror.ssc` now use full extension dispatch.

2. **`using`-clause auto-resolution.** ✓ Landed with v1.13.
   Context-bound desugaring, `summon`, and `using` auto-resolution
   all wired in the interpreter.  `combineAll[A: Monoid]` resolves
   `given` instances from scope without explicit passing.

3. **`Term.Ascribe`.** ✓ Landed 2026-05-19.  Added
   `case t: Term.Ascribe => eval(t.expr, env)` to the interpreter's
   `eval` dispatcher and `case Term.Ascribe(inner, _) => genExpr(inner)`
   to JsGen (closing the latent footgun there too).  New conformance
   file `conformance/type-ascription.ssc` locks in three-backend parity.

## Known issues / latent flakes

Things noticed in passing while landing other work — not blocking, but
worth a separate fix when somebody has cycles.

- ~~**v1.22 distributed-* conformance tests fail on JVM**~~  ✓ **Landed (2026-05-20)** — dep-block CPS rewriting (Steps 0–7) landed on `feature/dep-cps`; all six tests (`distributed-{map,shuffle,failure-retry,failure-partial,heterogeneous}` + `cluster-connect`) now PASS [JVM]. Root cause was dep-block effect primitives bypassing the CPS rewriter; fixed via `analyzeDepEffectfulness` fixpoint + `cpsBody` parameter threading through the emit path. Full design history in `docs/dep-cps-rewrite.md`.

- ~~**`actors-process-info.ssc` JVM compile failures (Term.Match pattern-bind)**~~  ✓ **Landed (2026-05-20)** — `emitCpsExpr` `Term.Match` arm was not registering `Pat.Var` names in `anyBoundNames`, so `case Some(info) => info.links.length` emitted `info.links` directly on an `Any`-typed scrutinee binding, causing Scala compile errors. Fix: collect `Pat.Var` names per case arm and wrap with `withAnyBoundNames(...)` (mirrors the identical treatment in the `Term.PartialFunction` arm). Also fixed: `import actors.ProcessInfo` dropped via `sscDepModulePrefixes`; `_dispatch` Map key-access fallback for `processInfo`'s `Map[String,Any]` return; `object Overflow` added to runtime preamble; `_FlatMap((), senderK)` deferred resume in `_resumeBlockedSender` so `Block`-overflow sender continuation runs in its own scheduler turn (fixes `actors-bounded-mailbox.ssc` output ordering).

- ~~**Intrinsics migration — all 11 in-tree families to `.sscpkg`.**~~
  ✓ **Landed (2026-05-21)** — `HttpIntrinsics`, `WsIntrinsics`,
  `AuthIntrinsics`, `CoreIntrinsics`, `JsonIntrinsics`, `RequestIntrinsics`,
  `McpIntrinsics`, `OAuthIntrinsics`, `OAuthClientIntrinsics`,
  `JdbcIntrinsics`, `FrontendIntrinsics` (UiPrimitives) all migrated to
  per-family `.sscpkg` plugins.  Interpreter core ships with zero
  domain-specific intrinsics; third-party plugins can now extend the table
  without forking.  Full migration spec: `docs/intrinsics-migration.md`.

- **Post-migration follow-ons** (not blocking; spec §11 of
  `docs/intrinsics-migration.md`):
  - **Plugin test harness** — ✅ **LANDED (2026-05-26)**:
    `runtime/backend/test-utils` now exposes `TestInterpreter(plugins =
    List(p))`, backed by explicit `Interpreter.installPlugins(...)`, plus a
    fake-plugin self-test. Follow-up: `backendInterpreterPluginTests` now owns
    the legacy plugin-backed interpreter suites, removing the direct
    `backendInterpreter / Test` dependency on std plugins; `graphPlugin`,
    `jsonPlugin`, `requestPlugin`, `fetchPlugin`, `frontendPlugin`,
    `swingPlugin`, `httpPlugin`, `authPlugin`, `oauthPlugin`, `wsPlugin`, `mcpPlugin`, and `sqlPlugin` have isolated `src/test` suites via
    `testUtils % Test`. Remaining plugin-family suites have been migrated; the separate `sql {}` fenced-block dispatch refactor remains tracked below.
  - **Examples `pkg:` sweep** — ✅ **LANDED (2026-05-26)**: see §5.5 note above.
  - **Jdbc `runSqlBlock` refactor** — ✅ **LANDED (2026-05-26)**:
    `Backend.sqlBlockRunner` + `SqlBlockContext` route plain `sql` fenced
    blocks through `sqlPlugin`; `SectionRuntime` only binds block results.
    Follow-up landed: `transaction` fenced blocks now route through the same
    plugin runner via `SqlBlockRunner.runTransaction`.
  - **`NativeContext` state-bag** (`featureGet`/`featureSet`) — ✅ **LANDED (2026-05-26)**:
    SPI now has shared `featureGet` / `featureSet` / `featureRemove` /
    `featureUpdate` and scoped `featureLocal*` variants. HTTP client config
    uses `NativeContextFeatureKeys` + feature-local state while preserving the
    existing named methods.  Follow-up: migrate small plugin knobs
    opportunistically.  Effort: S.
  - **`interpreter-server` extraction** — ✅ **LANDED (2026-05-26)**:
    `WebServer`, `InterpreterHttpHandler`, WS proxy/session/connection code,
    in-process backend transport, and their server-specific tests now live in
    `runtime/backend/interpreter-server` (`backendInterpreterServer`) behind
    `InterpreterServerSupport`. `Routes` / `WsRoutes` remain in interpreter
    core; route-registry SPI now landed (see below).
  - **Route-registry SPI** — ✅ **LANDED (2026-05-26)**:
    `RouteRegistry` trait added to `interpreter` core; `Routes extends RouteRegistry`;
    `Interpreter.routeRegistry` field injected into `InterpreterHttpHandler`,
    `WebServer.start`, and `InterpreterServerSupportImpl` — decouples HTTP route
    dispatch from the global `Routes` singleton.

- ~~**WS test cross-suite isolation goes through a process-global
  `WsRoutes` table + `WsTestLock` monitor.**~~  ✓ **Landed (2026-05-21)** —
  `WsRoutes` refactored from a global `object` to `final class WsRoutes`
  owned by the `Interpreter` instance; `WsProxy` / `InterpreterHttpHandler`
  / `TlsProxy` / `WebServer` all receive the per-interpreter instance.
  `WsTestLock` deleted; 18 WS test files and 10 non-WS test files updated.
  All 18 WS suites now run fully in parallel — no lock, no `WsRoutes.clear()`.

- ~~**Scala compiler warnings in our own code.**~~  ✓ Landed (2026-05-19).
  All 13 warnings fixed across 8 files (5 scalameta `After_X_Y_Z` migrations
  in `AstToIr`, 1 match-exhaustiveness fix in `RequestBuilder`, removed
  unused symbols in `Linker`/`Interpreter`/`WebServer`/2 plugin tests,
  removed unused default param in `evalDirectBlock`).  `Compile / scalacOptions`
  bumped to `-Werror` (the non-deprecated alias of `-Xfatal-warnings`);
  `Test / scalacOptions` kept at warning-tolerant for scalatest macros /
  intentional mocks.  Future warnings now fail the build before they
  accumulate.

- ~~**`SupervisorTest` — 4 pre-existing failures.**~~  ✓ **Landed (2026-05-21)** —
  `OneForOne` permanent / transient / temporary restart specs and the
  `MaxRestarts` budget spec all pass.  Fixed as part of the v1.22 distributed
  tests + SupervisorTest track (Next wave item 1).

## CLI — native binary (GraalVM native-image)

**Status:** Phase 1 ✓ Landed (2026-05-27). Phase 2 ✓ Landed (2026-05-27). Phase 3 ✓ Landed (2026-05-27). Phase 4 ✓ Landed (2026-05-27).

Produce a self-contained `ssc` native executable via GraalVM native-image:
no JVM installation required, cold-start drops from ~1-2 s → ~50-100 ms.
Current baseline: `ssc.jar` 29.4 MiB → ProGuard-shrunk `ssc-min.jar` 26.4 MiB
(task: `sbt cli/shrinkJar`).  Native-image produces a 60-100 MiB binary
(embeds GC + thread runtime) but removes the JVM dependency entirely.

### Phase 1 — replace snakeyaml ✓ Landed (2026-05-27)

`lang/yaml/src/main/scala/scalascript/parser/SimpleYaml.scala` — pure-Scala
recursive-descent YAML subset parser (~250 LOC); returns Java collection types
matching snakeyaml's "native" load API (LinkedHashMap/ArrayList) so all call
sites work unchanged.  `org.yaml.snakeyaml` removed from `build.sbt`.

snakeyaml was the highest-risk dependency for native-image: it uses Java bean
reflection extensively and has a long history of requiring hand-patched
`reflect-config.json` that drifts on every update.

The frontmatter in `.ssc` files is a small YAML subset — only strings, lists,
and nested maps.  Replace snakeyaml with a minimal pure-Scala frontmatter
parser (no reflection, no external dependencies).  This is valuable
independently of native-image (smaller JAR, no reflection surface).

Deliverable: `scalascript.parser.FrontmatterParser` — hand-rolled recursive
descent, ~200 LOC, replaces all `snakeyaml` call sites.  All existing
frontmatter tests must pass.

### Phase 2 — native-image build ✓ Landed (2026-05-27)

1. **Reflection config** — run `native-image-agent` while exercising all CLI
   paths (run / compile / watch / serve / emit-* examples).  Curate generated
   `reflect-config.json` + `resource-config.json` for upickle and scala-meta.
   With snakeyaml gone, this is the only remaining reflection surface.

2. **ServiceLoader** — add explicit `resource-config.json` entries for all
   `META-INF/services/scalascript.backend.spi.Backend` files.  Standard
   backends baked in at build time; third-party in-process JAR plugins handled
   by Phase 3 bridge (see below).

3. **`build.sbt` + `sbt-native-image`:**
   ```
   cli.enablePlugins(GraalVMNativeImagePlugin)
   GraalVMNativeImage / mainClass := Some("scalascript.cli.ssc")
   graalVMNativeImageOptions ++= Seq(
     "--no-fallback",
     "--initialize-at-build-time=scala",
     "-H:ReflectionConfigurationFiles=native-image-configs/reflect-config.json",
     "-H:ResourceConfigurationFiles=native-image-configs/resource-config.json"
   )
   ```

4. **CI release matrix** — native builds only on release tags (not every push):
   ```yaml
   strategy.matrix:
     os: [ubuntu-latest, macos-latest, macos-13]   # linux x86, mac arm64, mac x86
   steps:
     - uses: graalvm/setup-graalvm@v1
       with: { java-version: '21', distribution: graalvm }
     - run: sbt cli/graalvm-native-image:packageBin
     - uses: actions/upload-artifact@v4
   ```
   Linux ARM64 and Windows are stretch goals.  Build time: 5-15 min per platform.

5. **Distribution** — ship as opt-in release artifact alongside `ssc.jar`.
   `ssc.jar` remains the default (`java -jar ssc.jar`); native binary is a
   convenience for users who want instant startup without a JVM.

### Phase 3 — `ssc-plugin-host` + automatic bridge ✓ Landed (2026-05-27)

**Goal: existing plugins work without any changes from plugin authors.**

`URLClassLoader` cannot load class files at runtime in native-image — so
`--plugin foo.jar` (in-process) is broken.  The fix is a small companion
artifact **`ssc-plugin-host.jar`** (~1-2 MB) shipped alongside the native
`ssc` binary.  Plugin authors change nothing.

#### `ssc-plugin-host` sbt subproject (new)

A minimal JVM-only artifact with no parser, no compiler, no backends — only:

- **`SubprocessHost`** main class — accepts a plugin JAR path as argument,
  loads it via `URLClassLoader` (works fine in a JVM process), discovers
  `Backend` implementations via `ServiceLoader`, then enters the existing
  stdin/stdout wire protocol loop.
- Wire protocol shared with `BackendRegistry`'s existing subprocess path.

Build output: `ssc-plugin-host.jar` (~1-2 MB).  Shipped alongside the
native `ssc` binary in every release archive (`ssc-<version>-<platform>.tar.gz`
contains both `ssc` and `lib/ssc-plugin-host.jar`).

#### Automatic bridge in native `ssc`

When native `ssc` sees `--plugin foo.jar`:

1. Locates `ssc-plugin-host.jar` next to the `ssc` binary
   (same directory, or `$SSC_HOME/lib/`).
2. Checks that `java` is on PATH.
3. Spawns: `java -cp foo.jar:<path>/ssc-plugin-host.jar scalascript.plugin.SubprocessHost foo.jar`
4. Connects via the existing subprocess wire protocol in `BackendRegistry`.

From the user's perspective `--plugin foo.jar` works identically in JVM
and native modes.  The only external requirement is `java` on PATH — a safe
assumption for anyone using JAR plugins.

If `ssc-plugin-host.jar` is missing or `java` is not on PATH, `ssc` prints
a clear diagnostic pointing to remediation steps.

### Phase 4 — native plugin binaries (opt-in guide, no core changes) ✓ Landed (2026-05-27)

`docs/native-plugin-guide.md` — full plugin-author guide: prerequisites, entry
point wiring (depend on `ssc-plugin-host` or inline `SubprocessHost`), sbt
setup with `GraalVMNativeImagePlugin`, minimal reflection/resource config,
agent-based config generation, CI matrix, `plugin.yaml` manifest, verification,
and a JAR-vs-native comparison table.  No core changes.

### Known tradeoffs

| Issue | Severity | Notes |
|---|---|---|
| Binary size larger than JAR | Medium | 60-100 MiB vs 26 MiB ProGuard JAR + JVM. No JVM dependency is the trade-off. |
| Reflection config drift | Medium | Running the agent again needed on significant dependency changes. Mitigated by snakeyaml removal. |
| JAR plugin bridge requires `java` | Low | Plugin authors always have java; end-users running native ssc typically don't need JAR plugins. |
| CI build time | Low | 5-15 min per platform, only on release tags. |
| Debug quality | Low | Stack traces less ergonomic in native mode. |

### Effort summary

| Phase | Effort | Unblocked by |
|-------|--------|-------------|
| 1 — replace snakeyaml | ~2 days | — |
| 2 — native-image build | ~3-4 days | Phase 1 |
| 3 — SubprocessPluginHost + bridge | ~3 days | Phase 2 |
| 4 — native plugin binaries (guide) | ~1 day | Phase 3 |
| **Total** | **~1.5 weeks** | |

---

## Optimization and modularity roadmap

Full planning document: [`docs/optimization-roadmap.md`](docs/optimization-roadmap.md).
Items below are the actionable milestones extracted from that document.

### Runtime — Project Loom (virtual threads) ✓ Complete (2026-05-21)

**Status: complete. Effort: ~2 hours. Priority: 1.**

Switch the HTTP/WS server executor to `Executors.newVirtualThreadPerTaskExecutor()`
(Java 21 LTS, stable).  Removes the one-thread-per-connection bottleneck without
a full NIO migration.  Affects `runtime-server-common` + `runtimeServerJvm`.

- [x] Replace executor in `runtime-server-common` and `runtimeServerJvm`
  - `TlsContextBuilder.vthreadPool()`: dropped reflective fallback, now calls
    `Executors.newVirtualThreadPerTaskExecutor()` directly (Java 21 confirmed).
  - `WebSocketRuntime.scala` writer thread: replaced reflective `Thread$Builder$OfVirtual`
    with direct `Thread.ofVirtual().name("ws-writer").start(...)`.
  - `Interpreter.scala` `asyncParInterp`: replaced `newCachedThreadPool()` with
    `newVirtualThreadPerTaskExecutor()` for lightweight parallel async.
  - `JvmGen.scala` generated `_runAsyncParallel`: same replacement in emitted code.
- [x] Note Java 21 requirement in docs
  - One-line comment added next to each change site.
- [x] MCP test servers switched from `newCachedThreadPool()` to
  `newVirtualThreadPerTaskExecutor()` (2026-05-21) — `McpHttpBidiTest`,
  `McpHttpSseNotifyTest`, `McpStreamableHttpTest`; all 4 tests green.
- [x] Smoke test: 10 000 concurrent WS connections without OOM (2026-05-27)
  - `WsLoad10kTest` opens 10 000 concurrent WebSocket connections via Loom
    virtual threads, asserts at least 99% open, keeps heap growth under 1 GB,
    checks `WsConnection.activeCount`, and skips automatically when
    `ulimit -n` is too low for a meaningful run.

### Tooling — `ssc check` standalone type-checker ✓ Landed (2026-05-27)

**Status: fully landed (2026-05-27). Effort: ~1 day. Priority: 2.**

`ssc check src/**/*.ssc` — run the typer without interpreting, exit non-zero on
diagnostics.  For CI.  Generalises existing `check-with-iface` to standalone.
Supports `--iface-dir <dir>` / `-I <dir>` for checking against pre-compiled interfaces.

- [x] Add `check` command to CLI dispatch
- [x] Print diagnostics to stderr in `file:line:col: message` format
- [x] Exit non-zero when any error found
- [x] `--json` flag: structured JSON diagnostics with line/col/severity/message + elapsed_ms
- [x] `--quiet` flag: suppress all output, exit code only (for pre-commit hooks)
- [x] `--watch` flag: re-check on file change using Java WatchService, Ctrl-C exits cleanly
- [x] Directory mode: recursively finds `*.ssc` files, checks each, exits 1 if any error
- [x] Distinct exit codes: 0=clean, 1=type-errors, 2=parse-errors, 3=file-not-found
- [x] Integration tests in `CheckCommandTest` (18 tests, all green)

### Runtime — Interpreter file split ✓ Landed (2026-05-21, Phase 1+2)

**Phase 1 (file split) — landed 2026-05-21.**
`Interpreter.scala` reduced from ~2900 → ~600 lines.  Actor/cluster
scheduler moved into `ActorInterp.scala` (same package, self-type trait).
No behavioral change; all existing tests green.

**Phase 2 (lazy plugin loading) — landed 2026-05-21 (v1.33).**

Pragmatic approach instead of the full `CapabilityLoader` SPI: deferred
`BackendRegistry.inProcess` (ServiceLoader scan) until first use via a
`_pluginsLoaded` flag + `ensurePluginsLoaded()` on first Term.Name miss and
on `extern def` in child interpreters.  Actor/signal/coroutine state is already
lazy (activates only when called); plugin intrinsics (HTTP, SQL, OAuth, etc.)
are the dominant cost for pure scripts.  Cold-start: 0.35 s → 0.31 s.

The deeper per-capability file split (CapabilityLoader SPI, separate
`HttpRuntime.scala` / `ActorRuntime.scala` files) remains a future option
if the remaining ~40 ms becomes a bottleneck.

- [x] Phase 1: extract actor/cluster into `ActorInterp.scala` (self-type trait)
- [x] Phase 2: defer ServiceLoader scan via `_pluginsLoaded` + `ensurePluginsLoaded()`
- [x] Phase 2: `globalOrStub` → deferred proxy NativeFnV (resolves at call time)
- [x] Phase 2: `extern def` case in StatRuntime triggers load for child interpreters
- [x] Phase 2: `setupPluginCompanions` (Db / DriverManager) called post-load
- [x] Phase 2: All existing tests green (14 pre-existing failures unchanged)
- [x] Phase 2: Benchmark: 0.35 s → 0.31 s cold-start (`hello.ssc`)

### Compiler — AST cache between watch cycles ✓ Landed (2026-05-21)

**Status: fully landed (2026-05-21). Effort: ~3 days. Priority: 4.**

`ssc watch` re-parses the full file on every save.  Cache
`(path, mtime, hash) → ParsedModule`; diff at section granularity and
re-evaluate only changed sections and their dependents.

- [x] `ParseCache` — key by path + mtime + content hash
- [x] Section-level diff by heading text — `SectionDiff.compute(prev, next)`
      compares `SectionSnapshot` lists, classifies sections as added/modified/
      removed; `ssc watch` logs which sections changed and skips the interpreter
      re-run entirely on false-positive OS watch events (mtime touched but
      content unchanged).  9 tests in `SectionDiffTest`.
- [x] Re-evaluate only changed + dependent sections — `InterpCheckpoint` +
      `Interpreter.runWithCheckpoints` + `runSectionsIncremental`; watch
      keeps interpreter alive across cycles, restores to checkpoint before
      first changed section, re-runs only the changed suffix.  5 tests in
      `WatchIncrementalTest`.  Server files (headless hot-reload) excluded
      to avoid route-table state issues.
- [x] Target harness: watch cycle on `rest-api.ssc` can be measured against
  a < 100 ms gate with `ssc watch-bench --cycles 10 --target-ms 100
  --require-target examples/rest-api.ssc` (2026-05-27).  The hot path now
  avoids duplicate section re-hashing in the incremental typer and uses a
  no-allocation byte-to-hex loop for ParseCache / SectionSnapshot SHA-256
  digests.  The benchmark mutates a temporary copy of the source and reports
  warm-up, p50, and max cycle times.

### Generated code — JS tree-shaking

**Status: fully landed (2026-05-27). Priority: 5.**

Phase 1 (capability groups): JS runtime preamble was emitted unconditionally
into every single-file output.  Partitioned into named capability groups
(Core, Async, Effects, Mcp, Dataset) and wired up `JsGen.detectCapabilities`
+ `JsGen.generateRuntime` in the single-file emit paths.

Phase 2 (worklist dead-code elimination, 2026-05-27): `TreeShaker` worklist
reachability analysis starting from `@main`, manifest `exports`, and
side-effectful top-level terms.  Only reachable `const`/`function` declarations
emitted.  `JsGen.generateWithStats` returns `(code, Option[TreeShakeStats])`.

What landed:
- [x] Capability groups partitioned in `JsGen.generateRuntime` (Phase 2)
- [x] `emit-js`: detect module capabilities, emit only needed runtime blocks
- [x] `emit-spa`: detect capabilities, exclude Node-only Mcp/Dataset for browser
- [x] `emit-wc`: detect capabilities, emit only needed runtime blocks
- [x] Verify: `hello.ssc` JS output drops from 273 KB → 139 KB (≈ 49 % reduction)
- [x] `TreeShaker` worklist reachability (`TreeShaker.scala`)
- [x] `JsGen.generateWithStats` / `JsGen.TreeShakeStats`
- [x] `--no-tree-shake` + `--stats` flags in `ssc emit-js`
- [x] 16 tests in `JsTreeShakeTest`

### Library modularity

**Status: landed (2026-05-21). Effort: ~3 days. Priority: 6. Depends on: Interpreter split.**

1. Fix `backendInterpreter / backendJvm` dependency to `% Test` only.
2. Publish `scalascript-core` artifact (`ir + backendSpi + core`) for linters
   and tool builders.
3. Publish `scalascript-interpreter` (core eval, no HTTP/actors) for embedding.

- [x] Fix test-scope dep leak — `frontendPlugin` moved to `% Test` in `backendInterpreter`
- [x] Add `scalascript-core` aggregate in `build.sbt` — `scalascriptCore` aggregates `ir + backendSpi + core`
- [x] Add `scalascript-interpreter` aggregate — `scalascriptInterpreterAgg` aggregates eval stack (full HTTP/actor decoupling deferred to Phase 2 lazy loading)

### New tool — `ssc profile file.ssc` ✓ Landed (2026-05-27)

**Status: complete. Effort: ~3 days. Priority: 7.**

Per-phase compiler profiler: wall-clock + heap allocation delta for each pipeline
phase (parse / typecheck / normalize / jvm-codegen / js-codegen / link).
Flame-graph JSON output (Brendan Gregg-compatible), `--compare` regression diff,
`--runs=N` multi-run min/avg/max, `--top=N` hottest phases.

- [x] Add `profile` command to CLI
- [x] Instrument `eval` / `callValue` with per-function counters (both TCO and non-TCO paths)
- [x] Print top-20 hotspots by wall time on exit
- [x] `--top N` flag to limit rows, `--output / --out` for JSON export
- [x] `PhaseResult` case class + `timed` helper (wall-clock + heap allocation delta)
- [x] `Profiler.recordPhase` / `phaseEntries()` — phase-level tracking in Profiler
- [x] Flame-graph JSON: `version`, `file`, `timestamp`, `runs`, `phases[]`, `totalWallMs`
- [x] `--compare=baseline.json` — regression diff with ⚠ on >10% regressions
- [x] `--runs=N` — multi-run averaging with min/avg/max display
- [x] `ProfileCommandTest` — 17 tests, 15 green, 2 cancelled (require assembled jar)

### Runtime — Numeric value specialization

**Status: landed (2026-05-21). Effort: ~1 week. Priority: 8.**

`IntV(n)`, `DoubleV(d)`, `BoolV(b)` are heap-allocated on every arithmetic
operation.  Pool small `IntV` instances; specialize `Computation[A]` for `Int`
fast-path.

- [x] Pool common small `IntV` (−128..1024) — `Value.intV(n)` factory + 1153-slot array; used in all arithmetic hot-paths in `DispatchRuntime.infix` + `EvalRuntime.scala` unary ops + `BuiltinsRuntime` range/indices; `IntVPoolTest` 8 cases
- [x] Specialize arithmetic fast-path in `Computation` — existing Pure short-circuit in `EvalRuntime ApplyInfix` (already in place) now benefits from pooled result values; no allocation for `a + b` when result is in −128..1024
- [x] Benchmark regression: `InterpreterTest` 107/107 pass; `BoolV(true)` / `BoolV(false)` pre-cached as `Value.True` / `Value.False`

### Compiler — Incremental type-checking ✓ Landed (2026-05-19)

**Status: complete.** `TypedEnv` snapshot per section; restore + re-run typer from changed section forward; leaf-section isolation test.

- [x] `TypedEnv` snapshot per section — Landed 2026-05-19
- [x] Restore snapshot, re-run typer from changed section forward — Landed 2026-05-19
- [x] Test: changing a leaf section does not re-check unrelated sections — Landed 2026-05-19

### New tool — REPL debugger ✓ Landed (2026-05-21)

**Status: complete.** Interactive step-debugger built directly into `ssc repl`,
reusing the `DebugHooks` / `BreakpointRegistry` infrastructure from the DAP
debugger (v1.29).  The interpreter runs on a background virtual thread; when
it hits a breakpoint or step stop it blocks and the REPL main thread enters a
`(debug) ` sub-prompt.

- [x] `ReplDebugHooks` — breakpoints, step modes (StepIn/StepOver/StepOut),
      `stoppedQueue` + `suspendLatch` threading model; `mkHooks(): DebugHooks`
- [x] `Interpreter.evalExpr` — evaluate an expression in current globals +
      extra env with hooks suppressed (used by `:print`)
- [x] `replCommand` — wires `ReplDebugHooks`, dispatches `:break`/`:step`
      top-level commands, runs debug snippets on background thread
- [x] Debug sub-prompt commands: `:continue`/`:c`, `:next`/`:n`, `:step`/`:s`,
      `:out`, `:locals`/`:l`, `:stack`/`:bt`, `:print <expr>`, `:help`
- [x] 7 tests in `ReplDebugTest`

### New tool — REPL web-aware mode ✓ Landed (v1.30)

**Status: complete.** All 8 phases implemented in `tools/cli/src/main/scala/scalascript/cli/Main.scala:replCommand`.

- `:serve [port]` / `:stop [--keep-routes]` / `:clear` — background VT HTTP server lifecycle
- `:mount METHOD /path { expr | name | file.ssc [k=v] }` — live route registration
- `:load` / `:reload` / `:unmount METHOD /path` — file-based route management
- `:routes` — tabular live route table
- `:http METHOD /path [body] [-H "K: V"]` — real HTTP request to localhost
- `:call METHOD /path [body] [-H "K: V"]` — in-process dispatch, no server needed
- Typed handlers: `CaseClass1 => CaseClass2` with auto-deser/ser, `errorDetails` 4-level config
- `:set errorDetails <true|false>` — verbose deser errors toggle

---

## Scala ↔ ScalaScript interop — Tiers 1 + 2 landed

**Status: Tiers 1 and 2 landed; Tier 3 (sbt plugin) and Tier 4
(`--emit-scala-facade` compiler flag) still open.**  Full design doc:
[`docs/scala-interop.md`](docs/scala-interop.md).

Goal: make ScalaScript-built JAR a first-class JVM-library citizen, so a
regular Scala 3 project can `import std.foo.add` (natural FQN) instead
of `import _ssc_runtime.std_foo_add` (the v2.0 mangling).  Architected
as four independent tiers; each is shippable on its own.

### Tier 1 — compiler-emitted facade metadata — ✓ LANDED

Foundation for every other tier.  Optional field added to the
existing `.scim` envelope; everything else is library/plugin code.

- [x] `scalaFacade: Map[String, String] = Map.empty` field on
      `ModuleInterface` in `ir/Ir.scala`.
- [x] Populated by `InterfaceExtractor` using `Linker.mangle`.
      Recurses via `ExportedSymbol.nested` (depth 3, existing cap).
- [x] `_ssc_runtime.` prefix on the mangled side mirrors JvmGen's
      Phase-2 runtime-wrapping object.
- [x] Respects `exports:` front-matter — private helpers stay out
      (we build facade from the already-filtered `exports` list).
- [x] 4 new tests in `ArtifactAbiCompatibilityTest`: round-trip,
      legacy-absent-tolerant, case-sensitive keys, empty-map canonical.
- [x] 5 new `InterfaceExtractorTest` cases (top-level entries,
      package: reflected, empty-pkg bare names, nested join rules,
      `exports:` filter passes through).

ABI policy: additive optional field, absent-tolerant — fits the
2.0 strict-equality / additive-payload contract; no `abiVersion` bump.

### Tier 2 — `scalascript-interop` library — ✓ LANDED

Pure Scala 3 module providing the runtime glue + facade-source
generator that Tier 3 will consume.  Lives in this repo as
`lang/interop/` subproject (dependsOn ir + core only — no backend deps so
downstream consumers don't pull in JvmGen / scalameta etc.).

- [x] `scalascript.interop.facade.FacadeGenerator` — reads `.scim`,
      emits one Scala 3 source per package with `export _ssc_runtime.<mangled>
      as <name>` lines.  Pure compile-time alias, zero runtime overhead.
      Multi-module packages merge.  Conservative v0.1: nested entries
      (depth > 1 beyond pkg) skipped pending JvmGen-shape pin.  Legacy
      `.scim` without `scalaFacade` falls back via `Linker.mangle`.
- [x] `scalascript.interop.loader.ScalascriptLoader` — `fromArtifactDir`
      and `fromJar` factories build a natural→mangled index; `call[A]`
      does reflective dispatch through the loaded classloader.  Two
      named exceptions surface the error cases
      (`NoSuchScalascriptSymbol`, `UnresolvedJvmMember`).  Reads
      `META-INF/scalascript/{module}.scim` resources from a JAR for
      Tier-4 self-contained mode.
- [x] `scalascript.interop.runtime.Effects` — `runEffects(...)` /
      `runEffectsAsync(...)` wrap an `Effectful[A]` thunk, returning
      `Either[EffectError, A]` / `Future[A]`.  v0.1 uses class-name
      heuristic to recognise `UnhandledEffect`; v0.2 will wire real
      handler registration once the runtime types stabilise.
- [x] `scalascript.interop.runtime.Actors` — typed `ActorRef[T]` with
      `send(msg: T)` routed through an installable `SendHook`
      dispatcher.  `wrap[T](rawRef)` for actors handed across the
      boundary.  `spawn` is a placeholder (NotImplementedError + v0.1
      message) — true spawn waits on the runtime-type bridge.
- [x] 34 tests: 10 FacadeGenerator + 4 integration + 6 Effects +
      4 Actors + 10 ScalascriptLoader.  All green.

### Tier 3 — `sbt-scalascript-interop` plugin — ✓ Landed (2026-05-27)

Build-tool integration for consumers who want automatic facade source
generation wired into their sbt/Mill/scala-cli build.

What landed:
- [x] `ssc generate-facade <artifactDir> [-o <outputDir>]` CLI command.
  Reads `.scim` artifacts, calls `FacadeGenerator.generate`, writes
  Scala 3 facade sources.  Exits 0 even when nothing is emitted
  (Tier-5 identity artifacts produce no file — expected).
- [x] `sbt-scalascript-interop` plugin (`tools/sbt-plugin/`):
  - `ScalascriptInteropPlugin` with `sscArtifactDir` setting,
    `sscBinary` setting (default: `"ssc"`), and `sscGenerateFacade`
    task hooked into `Compile / sourceGenerators`.
  - 4 scripted tests: `basic`, `identity`, `multi-module`, `no-artifacts`.
- [x] Mill module trait `ScalascriptInteropModule` — documented as a
  `build.sc` snippet in `docs/scala-interop.md §6.3`.
- [x] scala-cli directive documented in `docs/scala-interop.md §6.4`.

Source lives in `tools/sbt-plugin/` (ready to extract to a separate
`scalascript-sbt-plugin` repo for independent publish cadence when
there's a Maven/Sonatype publishing need).

### Tier 4 — compiler `--emit-scala-facade` flag — partial (v0.1)

**Status:** flag + plumbing + META-INF metadata embedding ✓ landed.
Facade `.class` emit ✗ blocked on a JvmGen refactor (see below).

What landed:
- [x] `ssc link --backend jvm --bytecode --emit-scala-facade` flag wired
      through `linkCommand` and `linkJvmFromBytecode`.  Flag validation
      enforces the `--bytecode` + JVM-backend combination.
- [x] `JvmBytecode.compileFacade(facadeSources, classpathDirs)` —
      writes generator output to a temp dir, runs `Scala3Driver` in
      process, returns the output dir for downstream packing.
- [x] `JvmBytecode.packBundlesAsJarWithFacade(bundles, smapByModule,
      facadeClassDir, scimResources, outJar)` — extends the existing
      pack path to also emit facade classes + arbitrary resource
      entries (used for `META-INF/scalascript/<name>.scim`).
- [x] Embed every `.scim` as `META-INF/scalascript/<name>.scim` in the
      linked JAR so `ScalascriptLoader.fromJar` works without a sidecar.
- [x] Graceful degradation: facade compile failure is non-fatal — the
      JAR still ships with META-INF resources and the reflective
      `ScalascriptLoader` path keeps working.
- [x] 6 CLI subprocess tests in `EmitScalaFacadeCliTest` (flag
      validation, META-INF byte-identity, multi-module, summary).

What's BLOCKED on a separate JvmGen refactor:
- Facade `.class` emission for `package:`-decorated modules.  v2.0
  JvmGen wraps user code in `object pkg: object subpkg: <defs>` at the
  empty package level, so the facade's `package pkg.subpkg: export
  Ssc.x as alias` block can't compile (Scala 3 rejects an
  object-name/package-name clash, and empty-package members are
  unreachable from named packages anyway).
- Facade `.class` emission for no-`package:` modules.  User code lands
  in `<scriptName>_sc$package$` (Scala 3's top-level-def wrapper),
  not under `_ssc_runtime` — so the Tier-1 facade table's
  `_ssc_runtime.<name>` mangling doesn't match the JVM symbol either.

### Tier 5 — JvmGen `package`-clause emission — ✓ LANDED

`JvmGen.generateUserOnly` now produces Scala 3 source with a real
`package` clause for `package:`-decorated modules:

```scala
import _ssc_runtime.{given, *}

package a.b:

  def f(...) = ...
```

(previously: empty-package `object a: object b: def f(...) = ...`).

Implementation: post-emit transform `unwrapPackageObjects` in JvmGen
detects the parser-introduced `object pkg: object sub:` wrap (still
present in the AST because `Parser.wrapSectionInPackage` runs first
for typer/interpreter scoping) and rewrites it to a `package pkg.sub:`
block clause, dedenting the body by `2 × pkg.size` spaces.  No-op for
modules without `package:`.

What landed:
- [x] `JvmGen.unwrapPackageObjects` — string-level transform applied
      to `genUserOnlyWithLineMap` output.
- [x] `JvmBytecode.{packBundlesAsJar, packBundlesAsJarWithSmap,
      packBundlesAsJarWithFacade}` now include `.tasty` files alongside
      `.class` so Scala 3 consumers can cross-compile against the JAR.
      Per-module .tasty is ~1-3 KB; runtime tasty adds ~150 KB to a
      ~300 KB JAR — acceptable for the first-class JVM library use case.
- [x] `InterfaceExtractor.buildScalaFacade` updated to emit IDENTITY
      mapping (`natural FQN → natural FQN`) for `package:`-decorated
      modules; empty map for no-package modules (their top-level defs
      live in Scala 3's `<file>_sc$package$` wrapper at the empty
      package, unreachable from named-package consumers).
- [x] `FacadeGenerator` skips identity entries (no `export` needed —
      natural FQN works directly via `import a.b.f`).  Legacy entries
      starting with `_ssc_runtime.` still emit re-exports for
      pre-Tier-5 artifacts on disk.
- [x] Scale benchmark held: 47/49 → 48/49 runtime/std/ modules cleared (one
      more passed after the `package`-clause change).
- [x] 7 CLI subprocess tests in `EmitScalaFacadeCliTest` (incl. new
      Tier-5 layout-pinning test that asserts `demo/a/*.class` +
      `demo/a/*.tasty` entries land in the linked JAR).
- [x] 36 interop unit tests + 97 ABI/extractor tests green.
- [x] End-to-end smoke verified: a Scala 3 consumer can write
      `import demo.a.{add, double}` against a ScalaScript-built JAR
      via `scala-cli run --jar lib.jar --scala 3.8.3` — no facade
      classes, no plugin, no manual demangling.

### Implementation order

Tier 1 → ship anytime (foundation).  Tier 2 → after Tier 1 lands.
Tier 3 → after Tier 2.  Tier 4 → after Tier 3 if there's demand.

### Out of scope (deferred)

- Scala 2 consumers (would need `type`/`val` shims instead of `export`).
- Cross-compilation type-check via TASTy (needs richer typer first).
- JS-side interop (separate doc; UMD bundle already works classpath-free).
- REPL integration (sugar on top of Tier 3, defer).

---

## Next wave — post-v1.24 plan

Sorted by priority.  Run one agent per track simultaneously.

| Pri | Item | Track | Effort | Depends on |
|-----|------|-------|--------|------------|
| 1 | ~~Fix SupervisorTest + v1.22 distributed tests~~ ✓ landed (2026-05-21) | A | 2 days | — |
| 2 | ~~Incremental type-checking~~ ✓ landed (2026-05-19) | B | 1 week | AST cache ✓ |
| 3 | ~~LSP server (`ssc lsp`)~~ ✓ landed Phase 1+2 (2026-05-20) | C | 2 weeks | — |
| 4 | ~~Interpreter file split (Phase 1)~~ ✓ landed (2026-05-21) | D | 1-2 days | — |
| 4b | ~~Interpreter lazy loading (Phase 2)~~ ✓ landed (2026-05-21) | D | 1 week | Phase 1 ✓ |
| 5 | ~~Library modularity~~ ✓ landed (2026-05-21) — `frontendPlugin % Test` dep fix + `scalascriptCore` / `scalascriptInterpreterAgg` aggregates | D | 3 days | Interpreter split |
| 6 | ~~`ssc debug` (DAP debugger) Phases 1–5~~ ✓ all landed (2026-05-21) — TCP skeleton, framing, breakpoints, step execution, variable inspection, stack frames; 16 integration tests | C | 2 weeks | Interpreter split |
| 7 | ~~Numeric value specialization~~ ✓ landed (2026-05-21) — `Value.intV()` pool (−128..1024) + `Value.True`/`Value.False` pre-cached; arithmetic hot-paths in DispatchRuntime/EvalRuntime use pooled values | E | 1 week | Interpreter split |
| 8 | ~~WASM backend~~ ✓ landed — `WasmGen` + `WasmBackend` SPI + `WasmCapabilities`; `ssc emit-wasm` CLI; `scala`/`scalascript`/`ssc` blocks → `scala-cli --js-emit-wasm`; v1.27 sql block support via JS shim; 31 tests | F | 3 weeks | — |
| 9 | ~~**Package registry**~~ ✓ landed (2026-05-21) — `pkg:` URI in ImportResolver + `ssc install` shortcut; `BackendRegistry.findInstalledPkg` + `loadAndExtract`; auto-download via LocalRegistry | G | 2 weeks | — |
| 10 | ~~Scala ↔ ScalaScript interop (Tier 1)~~ ✓ landed | H | ½ day | — |
| 11 | ~~Scala ↔ ScalaScript interop (Tier 2)~~ ✓ landed | H | 1 week | Tier 1 ✓ |
| 12 | ~~Scala interop (Tier 3 sbt plugin)~~ — deferred, no demand | H | 1 week | Tier 2 ✓ |
| 13 | ~~Scala interop (Tier 4 metadata + flag)~~ ✓ landed | H | 2 days | Tier 2 ✓ |
| 14 | ~~Scala interop Tier 5 — JvmGen package-clause emit~~ ✓ landed | H | 2-3 days | — |
| 15 | ~~**REPL web-aware mode**~~ ✓ landed (v1.30) — `:serve`/`:stop --keep-routes`/`:clear`/`:mount`/`:load`/`:reload`/`:routes`/`:http`/`:call`; typed handlers (`Input => Output`, auto-deser/ser, `errorDetails` 4-level config); all 8 phases complete | I | ~5 days | — |

Track D is serial.  All other tracks can run in parallel.

---

## Beyond

Larger features that aren't on the critical path but are worth keeping in
view so they shape near-term decisions.

- **Hot reload in `serve` mode.** ✅ **LANDED** — see `watchCommand` in
  `cli/src/main/scala/scalascript/cli/Main.scala`; port stays bound, route
  table cleared and rebuilt on each save.
- **REPL: web-aware mode.**  Tracked above in optimization roadmap.
- **`html"..."` precision.** ✅ **LANDED** — `findClosingBrace` in the
  interpreter is now string-aware: double-quoted, triple-quoted, and
  single-quoted literals are skipped so a `}` inside `${ a + "}" }`
  never prematurely closes the scan.  Four regression tests added to
  `InterpreterTest`.
- **Future web-services protocols.**  HTTP/2, gRPC, GraphQL, OpenAPI
  schema export — each questioned during v1.1 review and deferred
  with concrete reasoning.  See [`docs/future-protocols.md`](docs/future-protocols.md)
  for prerequisites, effort estimates, and why each is on hold
  until a concrete user surfaces.

## Speculative — Smart contracts backend

> Not scheduled. No concrete timeline. Here for ideation — revisit when
> the WASM backend is stable and there is a concrete target chain.

ScalaScript's functional core (immutable values, algebraic types, effect
tracking, deterministic evaluation) maps naturally onto the constraints of
smart contract VMs.  The open questions below need answers before any
implementation work starts.

### Why it fits

- Smart contracts must be **deterministic** — no I/O, no randomness, no
  system calls.  ScalaScript's effect system already tracks and restricts
  side effects; a `@contract` annotation could enforce no-effect purity
  statically.
- **Algebraic types + pattern matching** are exactly the right tool for
  encoding contract state machines.
- **Formal verification hooks** — the typed IR can feed a proof assistant
  (Lean 4, Coq) or SMT solver without changing the source language.
- The planned **WASM backend** opens the door to WASM-based chains for
  free, once it ships.

### Open question 1 — which chain(s)?

Different chains have very different VM models:

| Chain | VM | Native language | Notes |
|-------|----|-----------------|-------|
| Ethereum / EVM chains | EVM (stack machine) | Solidity, Vyper | Largest ecosystem; custom IR needed |
| Solana | SBF (BPF variant) | Rust | High throughput; no WASM path |
| Cardano | UPLC (lambda calculus) | Haskell / Plutus / **Scalus** | Strongest FP alignment; use **Scalus** — no custom VM backend needed |
| Polkadot / ink! | WASM | Rust | WASM backend would cover this |
| Near | WASM | Rust, JS | WASM backend would cover this |
| Cosmos / CosmWasm | WASM | Rust | WASM backend would cover this |
| Aptos / Sui | Move VM | Move | Novel ownership model |

**Most natural fit** given the planned WASM backend: **Near, Polkadot/ink!,
CosmWasm** — zero extra VM work once WASM is stable.

**Highest ecosystem value**: **EVM** — but needs a dedicated EVM bytecode
backend (different from WASM).

**Fastest path with strongest type-theory alignment**: **Cardano via Scalus** —
[Scalus](https://github.com/nau/scalus) is a Scala 3 library that compiles Scala
code to Plutus UPLC on the JVM.  ScalaScript already targets the JVM; `JvmGen`
can emit Scala 3 + Scalus annotations for `kind: contract` / `chain: cardano`
modules.  No new VM backend needed.

**Decision (2026-05-19):** Cardano target uses **Scalus**.  WASM chains to be
decided when the WASM backend ships.

### Open question 2 — contract model

What does a ScalaScript smart contract look like?

```scalascript
---
name: token
kind: contract
chain: near
---

# Token contract

```scalascript
@state
case class TokenState(balances: Map[String, Int], totalSupply: Int)

@view
def balanceOf(account: String): Int =
  State.get[TokenState].balances.getOrElse(account, 0)

@call
def transfer(from: String, to: String, amount: Int): Unit =
  direct[State[TokenState]] {
    val s = State.get[TokenState].!
    require(s.balances.getOrElse(from, 0) >= amount, "insufficient balance")
    val s2 = s.copy(balances =
      s.balances
        .updated(from, s.balances(from) - amount)
        .updated(to,   s.balances.getOrElse(to, 0) + amount))
    State.set(s2).!
  }
```

- `@state` — persistent storage type
- `@view` — read-only call (no gas for state write)
- `@call` — state-mutating transaction
- `direct[State[TokenState]]` — existing monadic do-notation over contract state

### Open question 3 — gas metering

Does ScalaScript insert gas checks automatically (like Ethereum's opcode
pricing), or does the underlying VM handle it?

- WASM VMs (Near, Polkadot): the runtime meters WASM instructions — no
  compiler work needed.
- EVM: every opcode has a gas cost; the compiler must emit `GAS` checks.
- Cardano: script size + execution units are the cost model; no per-opcode
  metering.

### Open question 4 — formal verification hooks

Long-term: can the typer emit proof obligations that Lean 4 / Z3 can
discharge?  The typed IR already has enough structure.  This is PhD-level
research territory — keep it in mind when designing the contract annotation
model, don't build for it yet.

### Suggested first step — Cardano/Scalus (no WASM needed)

The fastest concrete path to a working smart contract:

1. Add `scalus` as a dependency to `backend-jvm/` (or a new `backend-cardano/` module).
2. In `JvmGen`, when `kind: contract` + `chain: cardano` appear in front-matter,
   emit Scala 3 code with Scalus `@Validator` annotation wrapping the user logic.
3. Map `@state`, `@view`, `@call` annotations to Scalus's `Data` encoding.
4. Write 3 sample contracts: token transfer, simple auction, multisig.
5. Test on Cardano preview testnet.

No new language features needed — reuse existing type system + JVM backend.
`direct[State[TokenState]]` do-notation already maps to Scalus's state model.

**Prerequisite:** none — can start after v1.24 language features land.

### Suggested second step — WASM chain (when WASM backend ships)

Thin `backend-wasm-contract/` layer on top of `backend-wasm/` for Near or Polkadot.

---

## Speculative — Apache Spark backend

> Phase 1 landed (2026-05-19): `backend-spark/` sbt module + `SparkGen.scala` +
> `ssc emit-spark` + `ssc run --backend spark` CLI wiring + `examples/word-count.ssc`.
> v1.25 § 9.5 Phase A (SPI wrap), B.1 (`--spark-master` / `spark-master:`),
> B.2 (`ssc submit` — fat JAR via `scala-cli --power package --assembly` +
> shell-out to `spark-submit --master <url> --class runSparkJob <jar>` with
> `--` pass-through for cluster-specific tuning),
> C.1 (`sql` block → `spark.sql(text, args)`), C.2 (section-based `<sectionId>.sql`
> alias), C.3 slice 1 (`>10` binds → `java.util.Map.ofEntries`), C.3 slice 2
> (widen `sparkImports` with `Row`, `DataFrame`, `types._`), C.3 slice 3
> (`spark-config:` front-matter map → sorted `.config(k, v)` on
> `SparkSession.builder()`), C.3 slice 4 (`spark-app-name:` front-matter
> overrides `.appName(...)` so the Spark UI / history server / driver+executor
> logs show a human-readable per-job name), C.3 slice 5 (typed reader
> convenience shims `Dataset.{fromParquet,fromJson,fromCsv}(path): DataFrame`
> for one-shot reads), C.3 slice 6 (same readers gain variadic
> `options: (String, String)*` pairs so `Dataset.fromCsv("/p", "header" ->
> "true", "inferSchema" -> "true")` works inline — chains
> `spark.read.options(options.toMap).X(path)`), C.3 slice 7 (symmetric
> writer extension methods on Dataset[T] — `ds.toParquet(path, opts*)`,
> `.toJson(...)`, `.toCsv(...)` — delegate to
> `ds.write.options(opts.toMap).X(path)`; `mode` is intentionally
> NOT in the options map and users chain `.write.mode(...)` directly when
> they need overwrite/append), C.3 slice 8 (adaptive default configs —
> `spark.ui.enabled=false`, `spark.sql.shuffle.partitions=4`, and the
> log4j WARN override are emitted ONLY when `sparkMaster.startsWith("local")`;
> cluster targets get Spark's own defaults instead), and C.3 slice 9
> (schema bridge — `Dataset.schemaOf[T : Encoder]: StructType` plus typed
> reader cousins `Dataset.{fromParquetAs,fromJsonAs,fromCsvAs}[T : Encoder]`
> that chain `spark.read.schema(schemaOf[T]).options(opts.toMap).X(path).as[T]`
> so a case-class declaration IS the schema specification — closes C.3)
> all landed.  CLI side: `--describe-backend` also grew `capabilities.options`
> + `capabilities.blockLanguages` lines so the Spark surface is fully
> discoverable from the command line.
> v1.25 § 9.5 milestone is now complete end-to-end (Phases A, B.1, B.2, C.1,
> C.2, all of C.3, plus Phase D — `@SqlFn` UDF bridge from `scalascript`
> declarations to `sql` blocks via auto-emitted `spark.udf.register` calls);
> the Spark backend covers Phase 1 (local), Phase 2 (cluster submission),
> and Phase 3 (Spark SQL / DataFrames including typed readers,
> case-class schema derivation, and SQL-callable UDFs).
>
> **Phase E (landed 2026-05-20) — Scala 3 native Spark `Encoder` derivation.**
> Inline `given derived[T <: Product]` in `SscSparkEncoders` (emitted at
> the top of every Spark source) builds `AgnosticEncoders.ProductEncoder[T]`
> from a Scala 3 `Mirror.ProductOf[T]` and wraps via `ExpressionEncoder(...)`.
> No TypeTag, no macros, no third-party libs.  Primitive encoders surfaced
> as plain givens that wrap `Encoders.STRING/scalaInt/...`;
> `import spark.implicits._` dropped from the emit (its TypeTag-bound
> `newProductEncoder` poisons implicit search) and replaced by
> `import SscSparkEncoders.given`.  Emitted source pins
> `//> using scala 3.7.1` (Scala 3.8.x has a TASTy-bridge regression that
> breaks Spark `_2.13` runtime reflection) and the standard Spark JDK 17+
> add-opens flags as `//> using javaOpt` directives, so
> `scala-cli run <file>` works with zero command-line args.
> Verified end-to-end: `examples/spark-encoder-demo.ssc` runs under
> Scala 3.7.1 + Spark 4.0.0 + JVM 21 producing a typed `Dataset[User]`
> with the expected schema and `.filter`/`.collect` round-trip.
>
> **Phase E follow-ups landed (2026-05-20):**
> - ✓ `Option[T]` fields via `AgnosticEncoders.OptionEncoder` — schema
>   shows `nullable = true` and `Some`/`None` survives the round trip.
> - ✓ Nested case classes — recursive AgnosticEncoder summon via
>   `summonInline[AgnosticEncoder[t]]` for each field; nested products
>   land as Spark `struct` columns.  Verified end-to-end with
>   `examples/spark-nested-demo.ssc` (`Person` with `Option[Int]` age
>   + nested `Address`).
> - ✓ Collection fields (`Seq[T]`, `List[T]`, `Vector[T]`, `Set[T]`,
>   `Array[T]`, `Map[K, V]`) via `AgnosticEncoders.IterableEncoder` /
>   `ArrayEncoder` / `MapEncoder`.  `containsNull` /
>   `valueContainsNull` derived from the inner encoder's `nullable`
>   so `Seq[Option[String]]` gets `containsNull = true` automatically.
>   Verified end-to-end with `examples/spark-collections-demo.ssc`
>   (`Post` with `Seq[String]` tags, `List[Int]` scores,
>   `Map[String, String]` meta).
>
> **Phase E follow-ups landed (cont., 2026-05-20, batch 3):**
> - ✓ `@SqlFn` auto-emit revival.  `extractSqlFns` parses param types
>   and return type from the `def` signature; emit wraps the user's
>   function in Spark's Java `UDFN` functional-interface form
>   (TypeTag-free) with an explicit `DataType` looked up via
>   `SparkGen.SqlFnDataType`.  Phase D's headline UX ("`@SqlFn def fn`
>   makes the function callable from sql blocks") now actually works
>   end-to-end on Scala 3 + Spark `_2.13`.  Limitations: only `def`
>   form is recognised; generic return types degrade to `StringType`
>   + `// TODO`.  Verified with `examples/spark-udf-demo.ssc`.
> - ✓ Tuple-as-field — `Mirror.ProductOf[(A, B, …)]` is auto-synth'd
>   by Scala 3 since tuples are products, so the existing
>   `aenc_Product[T <: Product]` given handles tuples as case-class
>   fields with no extra code.  Spark emits them as
>   `struct<_1, _2, …>` columns.  Verified with
>   `examples/spark-tuple-demo.ssc`.
>
> **Phase E status: all formerly-open follow-ups landed.**  Spark
> milestone (v1.25 § 9.5) closed end-to-end for case classes with
> primitive, `Option`, nested, collection, tuple, and UDF features.
>
> **Phase F — Structured Streaming (in progress, 2026-05-20).**
>
> - [x] F.1 — Spec doc `docs/spark-streaming.md`: goals / non-goals,
>       source-sink detection table (rate, file csv/json/parquet, kafka,
>       socket, console, foreach), `awaitTermination()` shim rule, Kafka
>       dep auto-emit rule, trigger/watermark/window passthrough,
>       migration (purely additive — existing batch examples unchanged),
>       phases F.2–F.4, testing strategy (codegen tests always +
>       smoke tests gated by `RUN_SPARK_INTEGRATION`, Kafka smoke
>       gated by `RUN_SPARK_KAFKA`), open questions.
> - [x] F.2 — Core streaming codegen (2026-05-20): added
>       `Trigger`/`StreamingQuery`/`OutputMode` imports to
>       `sparkImports`; new `SparkGen.containsStreaming` /
>       `containsAwaitTermination` / `containsKafkaFormat`
>       detection helpers; refactored `genModule` to run
>       `extractSqlFns` once per block (shared between detection
>       and emission) and auto-append
>       `spark.streams.active.headOption.foreach(_.awaitTermination())`
>       right before `spark.stop()` when streaming markers are
>       present but the user hasn't called `awaitTermination`
>       themselves; new `examples/spark-streaming-rate-console.ssc`
>       (rate → console with `Trigger.ProcessingTime("1 second")`,
>       no external deps).  9 new SparkGenTest cases pin the
>       detection semantics + shim emission; smoke test added to
>       SparkRuntimeSmokeTest (gated by `RUN_SPARK_INTEGRATION`).
>       Existing 115 SparkGenTest cases unchanged.  Phase F.4's
>       Kafka dep emission also landed in the same slice because
>       the detection plumbing was shared (small enough that
>       splitting would have been mechanical).
> - [x] F.3 — File source/sink + checkpointing (2026-05-20): new
>       `SparkGen.containsFileStreamSink` (case-insensitive regex on
>       `.format("parquet"|"csv"|"json"|"orc"|"text")`) and
>       `containsCheckpointLocation` detection helpers; when the
>       module is streaming AND uses a file format AND the user
>       hasn't already set `checkpointLocation`, the generated
>       source header gains a `// NOTE Phase F.3` reminder block
>       (Spark refuses to `start()` file-sink streams without
>       `checkpointLocation`).  Example
>       `examples/spark-streaming-file-parquet.ssc` watches a
>       parquet input dir, transforms, writes parquet output with
>       a checkpoint dir; smoke-test verified.  5 new SparkGenTest
>       cases pin the emission/suppression semantics.
> - [x] F.4 — Kafka source/sink (2026-05-20): dep auto-emit landed
>       with F.2 (`.format("kafka")` detection → `//> using dep
>       "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"`).  New
>       `examples/spark-streaming-kafka.ssc` (Kafka topic in →
>       upper-case → Kafka topic out, with checkpoint).  Smoke test
>       added behind double-gate `RUN_SPARK_INTEGRATION=1` +
>       `RUN_SPARK_KAFKA=1` — keeps default `sbt test` green on
>       machines without Kafka.
>
> **Phase F status: F.1–F.4 all landed.**  Structured Streaming end
> to end on Scala 3.7.1 + Spark 4.0.0 — rate/console smoke-tested,
> file/parquet smoke-tested, Kafka dep + example landed
> (broker-gated smoke).  No Spark 4 + Scala 3 interop surprises:
> Structured Streaming reuses the same Catalyst / Encoder machinery
> Phase E already proved works.  Two non-blockers surfaced for
> follow-up: (a) the streaming guard pins
> `spark.streams.active.headOption` which awaits only the FIRST
> started query (multi-query programs need explicit
> `awaitAnyTermination`); (b) the `\$$` Scala 3 string-interp warning
> in the Phase E shim's `Ordering[T]` extension is unrelated to
> streaming but surfaces on every emitted file as a deprecation
> hint (cosmetic; doesn't block compile).
>
> Natural fit: ScalaScript's existing `Dataset[T]` API maps directly to Spark.

#### Lakehouse formats track — Delta / Iceberg / Hudi

> Goal: when a `.ssc` program uses `.format("delta")` /
> `.format("iceberg")` / `.format("hudi")` (read or write), `SparkGen`
> auto-emits the right `//> using dep` plus the `SparkSession.builder()`
> configs (SQL extension + catalog override) needed for the runtime to
> initialise.  Detection is regex-driven on the raw source, same shape
> as the existing `@SqlFn` parser — purely additive over Phase E
> (no break to the 115 existing `SparkGenTest` cases or the working
> smoke-test set).
>
> Full plan: [`docs/spark-lakehouse.md`](docs/spark-lakehouse.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **L.1 — Spec doc (landed 2026-05-20).**  `docs/spark-lakehouse.md`
>   covering goals / non-goals / detection mechanism / format →
>   coord+config table / phases L.2–L.4 / testing strategy / open
>   questions.  No code changes; gives the parallel Streaming track
>   (`feature/spark-phase-f-streaming`) a stable contract to compose
>   against.
> - **L.2 — Delta Lake (landed 2026-05-27).**  Three detection
>   paths: `.format("delta")` (case-insensitive), `import io.delta.*`,
>   and `DeltaTable.` identifier — any one triggers the full L.2 emit.
>   Auto-emits `//> using dep "io.delta:delta-spark_2.13:3.2.0"`,
>   `.config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")`,
>   `.config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")`,
>   and `import io.delta.tables.DeltaTable` in the generated source
>   (via `lakehouseImports`).  Layered between adaptive `local*`
>   defaults and user `spark-config:` so user overrides win
>   (Spark builder is last-write).  `SparkGen.DefaultDeltaVersion`
>   constant pins 3.2.0 (first Delta release with confirmed
>   Spark 4 `_2.13` support).  `detectLakehouseFormats(String)`
>   overload added.  Tests: 23 new `SparkGenTest` cases covering
>   positive/negative detection across all 3 paths, read+write
>   symmetry, case-insensitive matching, the substring trap
>   (`"delta-stage"` must not match), config ordering, and the
>   helper functions directly.  Examples: `spark-delta-demo.ssc`
>   (original) + `spark-lakehouse-delta.ssc` (round-trip + Delta
>   Table API history).  Smoke test gated by
>   `RUN_SPARK_INTEGRATION=1` AND `RUN_SPARK_DELTA=1`.
> - **L.3 — Iceberg (DEFERRED, 2026-05-20).**  Iceberg's Spark
>   runtime artifact is named after the Spark major.minor it targets
>   (`iceberg-spark-runtime-3.5_2.13`).  The 3.5 line is the latest
>   published and does NOT link cleanly against Spark 4.0.0
>   (Catalyst symbol changes in Spark 4 break the 3.5-bundled
>   implementation classes).  No `iceberg-spark-runtime-4.0_2.13`
>   artifact is published.  Re-opens once Iceberg ships a Spark 4
>   build.  Slot-in pattern in `docs/spark-lakehouse.md` § L.3:
>   `DefaultIcebergVersion` constant + extend `detectLakehouseFormats`
>   and `lakehouseConfigs` — `genModule` itself doesn't change.
> - **L.4 — Hudi (DEFERRED, 2026-05-20).**  Same Spark-major naming
>   issue as L.3: `hudi-spark3.5-bundle_2.13` is the latest released
>   and is built against Spark 3.5.  No `hudi-spark4.0-bundle_2.13`
>   artifact is published.  Hudi community tracks Spark 4 support
>   under HUDI-7706; L.4 re-opens once the artifact ships.  Slot-in
>   pattern symmetric to L.3.

#### Phase G — Catalog / Hive metastore DSL

> Goal: first-class DSL for the Spark Catalog — auto-registering
> Datasets as temp views, wiring the Hive metastore + warehouse via
> front-matter, and typed table reads via `Dataset.fromTable[T]`.
> Layered on Phases A–F (Spark backend) + Lakehouse L.1–L.2.  All
> detection is regex-driven on the raw block source, same shape as
> the existing `extractSqlFns` (Phase D) and `detectLakehouseFormats`
> (L.2) helpers.  Purely additive over the existing surface — no
> break to the 141+ existing `SparkGenTest` cases or the working
> smoke-test set.
>
> Full plan: [`docs/spark-catalog.md`](docs/spark-catalog.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **G.1 — Spec doc (landed 2026-05-20).**  `docs/spark-catalog.md`
>   covering goals / non-goals / front-matter keys / annotation
>   semantics / `Dataset.fromTable[T]` / composition with C.1-C.3,
>   D, E, F, L.2 / testing strategy / open questions.  No code
>   changes; gives implementers G.2–G.4 a stable contract.
> - **G.2 — Front-matter for metastore + warehouse (landed 2026-05-20).**
>   New `spark-hive-metastore:` (Thrift URI) and `spark-warehouse:`
>   (path) keys threaded through `BackendOptions.extra` into
>   `SparkGen`.  Emits `.config("spark.sql.catalogImplementation",
>   "hive")` + `.config("spark.hadoop.hive.metastore.uris", "<uri>")`
>   + `.config("spark.sql.warehouse.dir", "<path>")` +
>   `.enableHiveSupport()` on the builder when either key is set
>   (or when `enableHiveSupport()` appears in user code).
>   Auto-adds `org.apache.spark:spark-hive_2.13:<sparkVersion>` as
>   a `//> using dep`.  Hive configs sit between lakehouse configs
>   and the user `spark-config:` map so user overrides still win
>   (Spark builder is last-write).  9 new `SparkGenTest` cases pin
>   detection, dep emit, config ordering, escape semantics, and the
>   textual `.enableHiveSupport()` short-circuit.  Existing 141
>   `SparkGenTest` cases unchanged.
> - **G.3 — `@TempView("name")` annotation (landed 2026-05-20).**
>   Regex pass strips the annotation line and emits
>   `<varName>.createOrReplaceTempView("<viewName>")` after the
>   declaration.  Same shape as `@SqlFn` (Phase D) — both
>   annotations are sibling parsers anchored on a line-start
>   `@Marker`.  The per-block processing pipeline runs
>   `extractSqlFns` then `extractTempViews` on the cleaned source,
>   so a single block can carry both annotations side by side; the
>   emitted body appends UDF registrations first, then temp-view
>   registrations.  `TempViewSig(viewName, varName)` decouples the
>   SQL-side view name from the Scala-side var name.  Type
>   ascription (`val n: T = ...`) is supported via an optional
>   non-capturing regex group.  9 new SparkGenTest cases pin
>   detection, type-ascription form, composition with @SqlFn,
>   hyphen / underscore view names, order contract (registration
>   after val), decoupled name capture, helper round-trip, and the
>   no-@TempView regression guard.  Regex-tuning note: pulled `\s*`
>   out of the optional ascription group so Java's non-backtracking
>   regex engine doesn't eat the trailing space before `=`.
> - **G.4 — `Dataset.fromTable[T]("name")` typed reader (landed 2026-05-20).**
>   One-line shim on the `Dataset` companion:
>   `spark.table(name).as[T]` using the Phase E encoder derivation.
>   Symmetric for Hive-managed tables (G.2) and temp views (G.3) —
>   the caller doesn't care whether the table sits in the metastore
>   or was registered five lines earlier as a temp view.  Lands with
>   `examples/spark-hive-demo.ssc` (combines G.2 warehouse front-matter
>   + G.3 @TempView annotation + G.4 fromTable read in one end-to-end
>   round trip via Spark's embedded derby metastore — no external Hive
>   service required) and an opt-in smoke test gated by
>   `RUN_SPARK_INTEGRATION=1 && RUN_SPARK_HIVE=1`.  3 new SparkGenTest
>   cases pin the shim signature, user-side fromTable lands in emit,
>   and the G.3 + G.4 composition case.  Phase G is now closed
>   end-to-end with G.5 (catalog introspection helpers) intentionally
>   punted — the existing `spark.catalog.*` calls are already reachable
>   from scalascript blocks today.
> - **G.5 (optional, deferred) — Catalog introspection helpers.**
>   `Dataset.listTables()` / `Dataset.describeTable(name)` wraps.
>   Skipped per the spec; users reach `spark.catalog.listTables()`
>   and `spark.sql("DESCRIBE TABLE x")` directly when needed.  Re-opens
>   only on concrete user demand.

#### MLlib track — machine learning pipelines

> Goal: when a `.ssc` program imports `org.apache.spark.ml.*` (feature
> extractors, algorithms, `Pipeline`, model persistence), `SparkGen`
> auto-emits the `spark-mllib_2.13` runtime dep and the Phase E
> Scala 3 encoder shim gains support for the `org.apache.spark.ml.linalg.Vector`
> type (sealed trait, NOT a Product — Mirror-based derivation can't
> handle it).  Result: case classes with `features: Vector` fields,
> stock MLlib Pipelines, and `model.save()` / `PipelineModel.load()`
> all work end-to-end without any front-matter or CLI override.
> Detection is regex-driven on import-header substrings, same shape
> as the Streaming / Lakehouse tracks — purely additive over Phase E
> + F + Lakehouse L.2.
>
> Full plan: [`docs/spark-mllib.md`](docs/spark-mllib.md).
>
> Phases (each independently shippable per AGENTS.md rule 3):
>
> - **M.1 — Spec doc (landed 2026-05-20).**  `docs/spark-mllib.md`
>   covering goals / non-goals / detection mechanism / encoder bridge
>   design / coord table / phases M.2–M.5 / testing strategy / open
>   questions.  No code changes; gives the parallel Spark tracks a
>   stable contract to compose against.
> - **M.2 — Auto-emit `spark-mllib_2.13` dep (landed 2026-05-20).**
>   `containsMllib(source: String): Boolean` regex on
>   `\bimport\s+(?:org\.apache\.spark\.ml\.|o\.a\.s\.ml\.)`.  When
>   true, emits `//> using dep "org.apache.spark:spark-mllib_2.13:<sparkVersion>"`
>   right after the Kafka dep emit (with the existing `spark-core` /
>   `spark-sql` lines).  Verified `spark-mllib_2.13:4.0.0` exists on
>   Maven Central before merge.  Tests: 9 new `SparkGenTest` cases
>   covering positive detection (feature / classification / Pipeline /
>   linalg-only / `o.a.s.ml.` alias / grouped + wildcard imports),
>   negative detection (no MLlib import / `mllibConfig` variable name
>   doesn't match), the documented commented-out-import limitation,
>   and direct `containsMllib` helper assertions.
> - **M.3 — Vector encoder (landed 2026-05-20).**  Extends
>   `SscSparkEncoders` with an explicit
>   `aenc_MLVector: AgnosticEncoder[org.apache.spark.ml.linalg.Vector]`
>   given that wraps `UDTEncoder[MLVector](new VectorUDT(), classOf[VectorUDT])`.
>   Spark ML's `Vector` is a sealed trait (not a `Product`), so the
>   Mirror-based `aenc_Product[T <: Product]` derivation can't reach
>   it; the explicit given routes via Spark's own `VectorUDT`
>   user-defined type so the wire-level column shape matches what
>   every MLlib operator expects (a `VectorUDT.sqlType` struct, not
>   a Kryo blob).  Aliased to `MLVector` on import to avoid clashing
>   with the existing `aenc_Vector[E]` given for the Scala collection
>   `scala.collection.immutable.Vector`.  Gated on `usesMllib`: the
>   shim text is emitted only when MLlib is imported, so non-MLlib
>   programs don't reference the `VectorUDT` class (which lives in
>   the MLlib JAR and would fail to resolve otherwise).  Implementation
>   refactors `phaseEShim` from a `val` to `def(usesMllib: Boolean)`
>   splicing a `phaseEShimHead` + optional Vector block +
>   `phaseEShimTail` (containing the `aenc_Product` Mirror walk +
>   `derived[T]` Encoder given).  Tests: 4 new `SparkGenTest` cases
>   covering emit-when-imported, no-emit-without-import (gating
>   correctness), aliased-import coexistence with Scala collection
>   Vector, and source-ordering (`aenc_MLVector` slots between
>   collection encoders and `aenc_Product` so the Mirror walk picks
>   it up via `summonInline`).
> - **M.4 — Pipeline example end-to-end (landed 2026-05-20).**
>   `examples/spark-mllib-pipeline.ssc` — Tokenizer + HashingTF +
>   LogisticRegression on a tiny inline dataset (4 labelled docs,
>   binary classification).  Codegen test in `SparkGenTest` verifies
>   the dep + Vector encoder shim land for this example without
>   requiring the integration gate; smoke test in
>   `SparkRuntimeSmokeTest` invokes `scala-cli compile` against the
>   real `spark-mllib_2.13:4.0.0` JAR under
>   `RUN_SPARK_INTEGRATION=1 RUN_SPARK_MLLIB=1`.  Verified locally
>   under Scala 3.7.1 + Spark 4.0.0 + JDK 21 — generated source
>   resolves all deps via Coursier and type-checks cleanly.  During
>   M.4 development we discovered `VectorUDT` is `private[spark]` in
>   Spark 4.0.0; M.3's shim was updated to route through the public
>   `org.apache.spark.ml.linalg.SQLDataTypes.VectorType` singleton
>   (a `DataType`-typed instance that is actually a `VectorUDT` at
>   runtime) and recover the concrete `UserDefinedType[Vector]` via
>   cast.  Same wire-level interop with downstream MLlib operators
>   as a direct `new VectorUDT()` construction.
> - **M.5 — Model save/load (landed 2026-05-20).**
>   `examples/spark-mllib-model-save-load.ssc` — same pipeline shape
>   as M.4, plus `model.write.overwrite().save(path)` →
>   `PipelineModel.load(path)` round-trip with a prediction
>   equivalence check on the same training data.  Exercises Spark
>   ML's `MLWritable` / `MLReadable` traits (implemented by every
>   stock MLlib estimator/transformer) without any new SparkGen
>   surface — the save/load calls flow through unchanged because
>   PipelineModel + path Strings need no codegen support.  Codegen
>   test in `SparkGenTest` pins the API-surface preservation;
>   smoke test in `SparkRuntimeSmokeTest` invokes `scala-cli compile`
>   against the real `spark-mllib_2.13:4.0.0` JAR under
>   `RUN_SPARK_INTEGRATION=1 RUN_SPARK_MLLIB=1`.  Verified locally
>   under Scala 3.7.1 + Spark 4.0.0 + JDK 21 — generated source
>   resolves, type-checks, and preserves the `model.write.overwrite().save`
>   + `PipelineModel.load` calls bit-identically.  Documented
>   caveats in the example: save() writes a directory tree (not a
>   single file), cross-Spark-major load is MLlib's own concern,
>   custom non-stock components must implement `MLWritable` /
>   `MLReadable` themselves to participate.  M closes here for
>   stock-component scope.

### Why it fits

ScalaScript already has a local `Dataset[T]` implementation (v1.21) and
distributed MapReduce (v1.22).  Apache Spark is the industry-standard
distributed data processing framework — Scala-native, JVM-based.

The mapping is almost 1-to-1:

| ScalaScript | Spark |
|-------------|-------|
| `Dataset[T]` | `Dataset[T]` / `RDD[T]` |
| `.map(f)` | `.map(f)` |
| `.filter(p)` | `.filter(p)` |
| `.flatMap(f)` | `.flatMap(f)` |
| `.groupBy(key, value)` | `.groupByKey(key).mapValues(value)` |
| `.reduce(f)` | `.reduce(f)` |
| `.top(n)` | `.orderBy(...).limit(n)` |
| `.countByValue` | `.groupBy(...).count()` |
| `.join(other, on)` | `.join(other, on)` |
| `MapReduce.run(input, map, reduce)` | `rdd.flatMap(map).reduceByKey(reduce)` |

ScalaScript's type-checked, functional Dataset API becomes a high-level
typed DSL on top of Spark — with the same source running locally (interpreter
backend) or at scale (Spark backend).

### What it unlocks

```scalascript
---
name: word-count
backend: spark
---

# Word Count

```scalascript
val lines = Dataset.fromPath[String]("/data/books/*.txt")

val counts = lines
  .flatMap(line => line.split("\\s+").toList)
  .map(word => (word.toLowerCase, 1))
  .groupBy(_._1, _._2)
  .reduce(_ + _)
  .top(100)

counts.foreach { case (word, n) => println(s"$word: $n") }
```

This runs locally with `ssc run word-count.ssc` (interpreter, in-process)
and at scale with `ssc run --backend spark word-count.ssc` (Spark cluster).
Same source, same semantics, different scale.

### Implementation path

**Phase 1 — Local Spark session (~1 week): ✓ LANDED (2026-05-19)**

1. ✓ New `backend-spark/` sbt module — pure code-emitter, no Spark JARs on sbt
   classpath; Spark is resolved at runtime via `scala-cli --dep`.
2. ✓ `SparkGen.scala` — emits `SparkSession.builder().master("local[*]")` +
   `Dataset` companion shim + extension methods (`toList`, `top`, `takeOrdered`).
   Spark 4.0.0 default version (configurable via `--spark-version` flag or
   `spark-version:` front-matter key).
3. ✓ `ssc run --backend spark file.ssc` — generates Spark source, writes to
   `/tmp/ssc-spark-<hash>.scala`, runs via `scala-cli run --dep spark-*`.
4. ✓ `ssc emit-spark` command — emits generated Spark source to stdout or `-o file`.
5. ✓ `Dataset.fromPath[String](glob)` → `spark.read.textFile(glob).map(ev)`.
6. ✓ `examples/word-count.ssc` — example with `backend: spark` front-matter.
7. ✓ 21 unit tests in `SparkGenTest.scala` (no Spark runtime needed — structural
   source checks only).

**Phase 2 — Cluster submission (~1 week): ✓ LANDED (2026-05-19)**

5. ✓ `ssc submit file.ssc --spark-master spark://host:7077` packages the job
   as a fat JAR via `scala-cli --power package --assembly` and calls
   `spark-submit --master <url> --class runSparkJob <jar>`.
6. ✓ Support `--spark-master yarn` / `--spark-master k8s://...` — argv pinned
   in `SparkSubmit.submitCommand` tests across all four master URL shapes
   (`local[*]`, `spark://`, `yarn`, `k8s://`).
7. ✓ `--` separator after the file passes extra args through to `spark-submit`
   verbatim — `--executor-memory`, `--num-executors`, `--deploy-mode cluster`,
   etc.  Verified in `SubmitCommandTest`.
8. ✓ `--dry-run` prints the argv that would be invoked without shelling out;
   used by shell integration tests and useful for users inspecting what
   `ssc submit` is about to do.

**Phase 3 — Spark SQL and DataFrames (~1 week): ✓ LANDED (2026-05-19)**

7. ✓ Expose `DataFrame` as `Dataset[Row]` with a typed schema — `DataFrame`
   and `Row` widened into the emitted `sparkImports`, `_sqlBlock_<N>: DataFrame`
   bindings from sql blocks, typed `fromXAs[T : Encoder]` cousins of the
   readers return real `Dataset[T]`.
8. ✓ Case-class declarations map to Spark `StructType` via
   `Dataset.schemaOf[T : Encoder] = summon[Encoder[T]].schema`.  This
   subsumes the original "map `runtime/std/parsing` schemas" goal: case classes
   are the canonical schema declaration in Scala, and Spark's existing
   Encoder mechanism already derives the StructType from them — a custom
   parser-combinator → StructType layer would have been wasted work
   duplicating Spark's own derivation.
9. ✓ Inline SQL via `sql` fenced blocks — see Phase C.1 / C.2 above.
   String-interpolated `sql"..."` was the original sketch; the fenced-
   block path turned out cleaner (whole-block parameterisation via the
   shared `SqlBindRewriter`, alias-able by section).

### Key design decisions

**1. Same `Dataset[T]` API, different backend**

The user writes `Dataset[T]` code — the Spark backend compiles it to Spark,
the interpreter backend runs it in-process.  No user-visible difference.
Switching: change `backend: interpreter` to `backend: spark` in front-matter.

**2. Lazy evaluation**

Spark is lazy (transformations build a DAG, actions trigger execution).
ScalaScript's `Dataset` is also lazy by design — the existing implementation
already defers work until a terminal action (`.forEach`, `.reduce`, `.top`).
No semantic mismatch.

**3. Serialization**

Spark needs user types to be serializable (Kryo or Java serialization).
ScalaScript case classes compile to Scala 3 case classes — Spark can serialize
them automatically via `Encoder` derivation.  The backend emits implicit
`Encoder[T]` derivations for all `@state` types used in Dataset pipelines.

**4. Type safety**

ScalaScript's typer already type-checks `Dataset` operations.  The Spark
backend adds one more check: all types used in a distributed `Dataset` must
be serializable (no closures capturing non-serializable state).

**5. Local simulation for development**

`ssc run file.ssc` (no `--backend spark`) always uses the interpreter backend
with the local in-process `Dataset` implementation.  No Spark, no cluster, instant
feedback.  The Spark backend is only needed for production runs.

### Comparison

| | ScalaScript + Spark | Raw Spark (Scala) | PySpark | Flink |
|-|--------------------|--------------------|---------|-------|
| Type safety | ✓ (full) | ✓ (full) | ✗ (runtime) | ✓ |
| Local dev without cluster | ✓ | ✓ (local mode) | ✓ | ✓ |
| Same source local + cluster | ✓ | ✓ | ✓ | ✗ |
| Markdown-structured pipelines | ✓ | ✗ | ✗ | ✗ |
| Effect safety | ✓ | ✗ | ✗ | ✗ |
| Multi-backend (JS, JVM, Spark) | ✓ | ✗ | ✗ | ✗ |

### Prerequisites

- v1.24 language features (for cleaner DSL)
- Existing `Dataset[T]` + MapReduce API (already landed in v1.21–v1.22)
- JVM backend (already exists — Spark backend reuses its Scala 3 emission)

No new language features needed.  The Spark backend is a pure code-generation
addition on top of existing IR.

## v1.26 — `sql` fenced code blocks (JDBC)

**Status: complete (2026-05-21). All 7 phases landed.**

Adds the `sql` block tag (§ 3.3 / § 3.3.1 of `SPEC.md`): parameterised
SQL executed via JDBC.  The hard design rule and entire safety story
in one sentence: **every `${expr}` becomes a single `?` bind parameter
— string substitution into SQL is not part of the language, period**.
A `sql` block can never produce a SQL injection regardless of what
the surrounding ScalaScript code does, because there is no syntax to
splice a `String` into SQL in the first place.

Decisions (resolved on the way in):
- Binding: `${expr}` → `?`, safe-by-default, no unsafe-splice escape.
- Connection source: YAML front-matter `databases:` by default;
  `given Connection` in scope overrides for tests / one-offs.
- Result type: `Seq[Row]` for SELECT-family, `Int` for DML/DDL.
  `row.as[CaseClass]` projects by field name at runtime.
- Drivers bundled in core: H2 + SQLite (both embedded, no network).
  Everything else (Postgres, MySQL, …) via `dep:` import.
- Target: JVM-only.  JS / Node / Wasm emit `UnknownBlockLanguage`;
  the source survives verbatim in the IR for future backends.

Parallel-safety note: v1.25 (`worktree-js-node-blocks`) edits the
same `core/.../ast/Lang.scala` and the same § 3.3 of `SPEC.md`.
Whichever lands second rebases on the first — the additions are
co-located but non-overlapping.

### Phase 1 — SPEC + milestone (this iteration)

- [x] `SPEC.md` § 3.3 table row + new § 3.3.1 (binding rule, result
      type, connection resolution, drivers, target support).
- [x] `MILESTONES.md` v1.26 entry (this section).

### Phase 2 — Front-end lang-tag recognition

Narrow to classification only — the dedicated IR node moves into
Phase 3 where it gains real content (the bind list).  Until then
`sql` blocks route through `ir.Content.EmbeddedBlock` identical to
the existing `node.js` path.

- [x] `core/.../ast/Lang.scala`: add `Sql = "sql"`, `isSql`,
      `isParameterizedExec`; extend `isOpaqueExec` to cover sql so
      capability gating in `validate/CapabilityCheck` works
      generically (no new code in CapabilityCheck needed).
- [x] Tests: `core/.../ast/LangTest.scala` (predicate pinning) +
      `core/.../parser/SqlBlockTest.scala` (lang preservation,
      Normalize → EmbeddedBlock, Normalize/Denormalize round-trip).

### Phase 3 — Dedicated IR node + `sql` → `SqlBlock` routing

The rewriter itself was landed earlier as cross-target infrastructure
by v1.25 § 9.5 Phase C.1 (parallel work): a single
`transform/SqlBindRewriter` with two placeholder modes —
`rewriteJdbc` (`?`) consumed by v1.26 and `rewriteSparkSql`
(`:bind<N>`) consumed by the Spark backend.  v1.26 Phase 3 is now the
JVM consumer of that shared rewriter plus the dedicated IR shape.

- [x] New IR case `ir.Content.SqlBlock(source, binds, dbName, span)`
      added to the `Content` enum.  `source` is the original SQL
      verbatim (round-trip surface for `Denormalize`); `binds` is
      the ordered list of bind-expression source texts produced by
      `SqlBindRewriter.rewriteJdbc`.  The `?`-form (JDBC template)
      is recomputed at execution time by rerunning the rewriter on
      `source` — keeps the IR small and avoids any literal-`?`
      ambiguity in round-trip.  `dbName` is `None` until Phase 5
      wires the `@db=name` block attribute.
- [x] `Normalize` routes `sql` blocks through
      `SqlBindRewriter.rewriteJdbc`; malformed sources
      (`RewriteError`) fall back to `EmbeddedBlock` so a single bad
      block doesn't crash the pipeline (capability check still
      surfaces `UnknownBlockLanguage`, execution layer surfaces the
      precise bind diagnostic).
- [x] `Denormalize` emits the preserved `source` field, reproducing
      `${expr}` / `$$` markers verbatim.
- [x] `validate/CapabilityCheck` recognises `ir.Content.SqlBlock`
      and produces `Diagnostic.UnknownBlockLanguage("sql")` on
      backends that don't declare `sql` in `blockLanguages`.
- [x] Tests: `SqlBlockTest` updated to assert the new IR shape
      end-to-end (Normalize produces `SqlBlock` with the right
      binds, Denormalize reproduces the source).
      `CapabilityCheckTest` covers sql-gating both directions
      (declared vs. not declared).  592 tests in `core/test` green
      (includes the 14 pinning tests for the shared
      `SqlBindRewriter` from v1.25 § 9.5 Phase C.1).

### Phase 4 — `backend-sql-runtime` module

New sbt module `backend-sql-runtime/` with no dependency on any
backend SPI — pure runtime library, callable from interpreter and
from JvmGen-emitted code.

- [x] `SqlRuntime.execute(conn, sql, binds): SqlResult`.
      `SqlResult = Rows(Seq[Row]) | UpdateCount(Int)`.
      Statement-type detection by leading keyword
      (`isResultSetProducer`: SELECT / WITH / VALUES / SHOW / EXPLAIN).
- [x] `Row` type: positional + name-indexed (case-insensitive),
      `row(i)`, `row(name)`, `row.toMap`, `inline row.as[T]` for case
      class projection via `Mirror.ProductOf`, by field name.  Per-
      field runtime coercion with named diagnostics
      (`RowProjectionError`) on missing column, NULL into non-Option,
      or type mismatch.
- [x] Bundled deps: `com.h2database % h2 % 2.2.224`,
      `org.xerial % sqlite-jdbc % 3.45.3.0`.  No `dependsOn` — module
      is standalone so both backend-interpreter and backend-jvm can
      pick it up later without circular deps.
- [x] JDBC binding: explicit dispatch on runtime type for primitives,
      `BigDecimal` / `BigInt`, `Array[Byte]`, `java.time.*`
      (`LocalDate` / `LocalTime` / `LocalDateTime` / `Instant` /
      `OffsetDateTime` / `ZonedDateTime`), `java.util.UUID`.
      `None` / `null` → `setNull(Types.NULL)`; `Some` unwraps
      recursively.  Time-typed binds use the typed
      `setObject(i, value, JDBCType)` form for driver portability.
- [x] Legacy `java.sql.{Date, Time, Timestamp}` ResultSet values are
      normalised to `java.time.*` at materialisation so user code
      sees one consistent vocabulary.
- [x] Tests in `backend-sql-runtime/src/test/...`: 16 cases against
      in-memory H2 + a bundled-driver smoke test on SQLite.  Covers
      every statement family, `null` / `None` binds, `Option[T]`
      projection, `.as[CaseClass]` happy + diagnostic paths,
      LocalDate / Instant round-trips, multi-row order preservation,
      name lookup case-insensitivity, `Row.toMap`.

### Phase 5 — Connection plumbing

- [x] `lang/schemas/frontmatter.yaml`: `databases:` map — keys are
      connection names referenced by `@db=`, values carry
      `{url, user?, password?, driver?}`.  Strings may contain
      `${env:VAR}` references.
- [x] `ast.Manifest` / `ir.Manifest`: new `databases:
      List[DatabaseDecl]` field with default `Nil`.  Parser pulls
      entries out of the YAML map (missing `url` skips silently —
      runtime surfaces a precise diagnostic later).  Normalize +
      Denormalize forward the list.
- [x] `core/.../parser/Parser.scala`: fence-line attribute syntax
      `@key=value` (also accepts `@key="quoted value"`).  Keys
      lower-cased, values case-preserved.  Carried on
      `ast.Content.CodeBlock.attrs: Map[String, String]`.  General
      slot — `sql` uses it for `@db=` today; other tags can adopt
      it without an AST change.
- [x] `Normalize`: `sql` blocks read `attrs("db")` into
      `ir.Content.SqlBlock.dbName`; absent → `None`, registry
      default applies at execution time.
- [x] `backend-sql-runtime`: `EnvResolver.resolve(template,
      configKey, dbName, lookup)` expands `${env:NAME}` substrings
      at runtime (not at parse), raising `MissingEnv` with the
      variable / config field / db name on miss.
- [x] `backend-sql-runtime`: `ConnectionRegistry(specs, envLookup)`
      — lazy-open + cached `connect(name)`, identity on second
      call, `fresh(name)` for uncached opens, idempotent `close()`,
      `UnknownDatabase` lists available names on miss.
- [x] Tests: `DatabasesFrontmatterTest` (7 cases — YAML parsing,
      env-ref preservation, malformed-entry skip,
      Normalize/Denormalize round-trip).  `ConnectionRegistryTest`
      (16 cases — `EnvResolver` happy/error paths,
      regex-special-char escape, registry connect-and-cache,
      fresh-no-cache, close idempotency, post-close reopen,
      unknown-name diagnostic).  `SqlBlockTest` extended with
      `@db=name` parsing + key-lowercasing cases.

The `given Connection` / `given DataSource` override path is
implemented in Phase 6 alongside the interpreter wiring — the
registry already accepts pre-built `Connection`s through `fresh`,
so Phase 6 just routes the `given`-resolved connection straight
to `SqlRuntime.execute` and bypasses the registry.

### Phase 6 — Interpreter + JvmGen integration

#### Phase 6.A — Capability declarations + Denormalize round-trip (landed)

- [x] `JvmCapabilities` / `InterpreterCapabilities` declare
      `blockLanguages = Set(Lang.Sql)`.
- [x] `backend-jvm` and `backend-interpreter` `dependsOn(backendSqlRuntime)`.
- [x] `Denormalize` carries `ir.Content.SqlBlock.dbName` through
      `ast.Content.CodeBlock.attrs("db")` so consumers read the
      database selector through the same channel the parser
      populates it.

#### Phase 6.B — Interpreter executes sql blocks (landed)

- [x] `Value.Foreign(typeName, handle)` — opaque JVM-handle bridge.
- [x] `intrinsics/Jdbc.scala` — `DriverManager.getConnection` in
      both 1-arg and 3-arg overloads; returns
      `Foreign("Connection", conn)`.  `globals("DriverManager")`
      companion built in `initBuiltins`.
- [x] `Interpreter.run` materialises a per-module
      `ConnectionRegistry` from `manifest.databases` at module-init.
- [x] `Interpreter.runSection` dispatches `sql` blocks to a new
      `runSqlBlock` that:
      re-runs `SqlBindRewriter.rewriteJdbc` on `cb.source`, evals
      each bind expression in the current scope (`unwrapForJdbc`
      projects `Value` → JDBC `Any`), resolves the `Connection`
      via the override path (`Foreign("Connection", _)` bound to
      the `Connection` global) with the registry as fallback,
      calls `SqlRuntime.execute`, wraps the result (`Rows` →
      `ListV(MapV-per-row)`, `UpdateCount` → `IntV`), and binds it
      under both `<sectionId>.sql` and `_sqlBlock_<ordinal>`.
- [x] `SqlBlockInterpreterTest` (5 cases): registry-path DDL +
      INSERT + SELECT, dual surfacing, 1-arg + 3-arg override
      path, UPDATE returns affected-row count.

#### Phase 6.C — JvmGen codegen (landed)

- [x] `JvmGen.collectBlocks` recognises `ast.Content.CodeBlock` with
      `Lang.isSql`, increments a per-instance `sqlBlockCounter`, and
      emits a `JvmGen.Block` whose source is the Scala equivalent of
      the sql block — a `_sqlBlock_<N>: SqlResult = SqlRuntime
      .execute(_ssc_sql_resolve(<dbName>), "<?-templated>",
      List(<binds>))` expression with binds spliced as Scala source.
      First sql block per section also emits an `object <sectionId>:
      lazy val sql = _sqlBlock_<N>` alias (matches Spark Phase C.2 and
      the Interpreter's `globals(<sectionId>).sql` shape).
- [x] `emitSqlRegistry(databases)` materialises front-matter
      `databases:` entries as a `_ssc_sql_registry: ConnectionRegistry`
      constructed once at script entrypoint.  When the module has
      no `databases:`, the registry is `ConnectionRegistry.empty` so
      `given Connection` paths still work standalone.
- [x] `_ssc_sql_resolve(dbName: Option[String]): java.sql.Connection`
      helper uses Scala 3 `scala.compiletime.summonFrom` to prefer a
      `given java.sql.Connection` in scope; falls back to
      `_ssc_sql_registry.connect(dbName.getOrElse("default"))`.
- [x] `//> using dep` directives emitted only when sql blocks are
      present: `com.h2database:h2:2.2.224`,
      `org.xerial:sqlite-jdbc:3.45.3.0`, plus the
      `scalascript-backend-sql-runtime` library reference.
- [x] `Denormalize` round-trips `ir.Content.SqlBlock.dbName` through
      the existing `ast.Content.CodeBlock.attrs("db")` channel — same
      input shape JvmGen sees as the parser produces it.
- [x] Tests: `JvmGenSqlBlockTest` (14 cases — no-sql passthrough,
      `//> using dep` emission, registry materialisation with /
      without `databases:`, summonFrom helper, per-block emission
      with / without binds, sequential `_sqlBlock_<N>` numbering,
      `<sectionId>.sql` alias (first only, dedup on second),
      `@db=name` threading, `${env:NAME}` literal preservation).

v1.26.2 follow-up — **runtime smoke-test landed.**
`JvmGenSqlRuntimeTest` (2 cases) compiles + runs the JvmGen output
through `scala-cli` against an H2 in-memory database.  Worked around
the "no published artifact" problem by replacing the emitted
`//> using lib "io.scalascript::scalascript-backend-sql-runtime:…"`
directive with `//> using jar "<absolute-path>"` pointing at the
locally-built JAR.  The jar path is plumbed through a classpath
resource generated by `Test / resourceGenerators` in
`backendInterpreter`'s settings, which depends on
`backendSqlRuntime/Compile/packageBin` so it's always fresh.  Tests
auto-skip (via `assume(hasScalaCli, ...)`) when scala-cli isn't
available on PATH, so CI lanes without it stay green.

The `JsGen` / `NodeBackend` / `WasmBackend` `UnknownBlockLanguage`
diagnostic is **already wired generically** via
`validate/CapabilityCheck.unknownBlockLanguages` matching
`Lang.isOpaqueExec` (Phase 3 / 5).  An explicit end-to-end test for
each non-JVM backend is a Phase 7 conformance item.

### Phase 7 — Examples + conformance

- [x] `examples/sql-h2-quickstart.ssc`: zero-config H2 in-memory,
      DDL + DML + SELECT with bind params + section-aliased read.
- [x] `examples/sql-sqlite-file.ssc`: file-backed + in-memory
      SQLite, illustrates `@db=name` routing between two named
      connections in the same module.
- [x] Self-contained end-to-end test
      (`SqlExamplesTest`, 2 cases) that inlines the example sources
      verbatim and asserts they parse + execute under the
      interpreter against in-memory H2 and SQLite.  Catches
      regressions when parser / runtime changes silently break the
      documented usage shapes.

Phase 7 deferred items — all landed v1.26.2:

- [x] `conformance/sql-basic.ssc` + `conformance/expected/sql-basic.txt` +
      `SqlConformanceCaptureTest` (in-process scalatest harness that
      bypasses the bin/ssc + scala-cli + node toolchain `run.sc`
      requires, so `sbt test` enforces the regression).  Gated to
      `backends: [int]` — the JVM target's emitted code still
      references the unpublished `scalascript-backend-sql-runtime`
      artifact; the dedicated `JvmGenSqlRuntimeTest` covers the JVM
      path via a local-JAR override.
- [x] JS / Node / Wasm explicit `UnknownBlockLanguage` cases — added
      to `NodeBackendTest` and `WasmBackendTest` directly against
      each backend's real `Capabilities` instance (not a synthesised
      `Set.empty` stub).  Documents the dispatch path so a future
      backend that accidentally claims `sql` would fail loudly.
- [x] `docs/targets.md` block-language support matrix — new
      "Block Language Support" section with a per-block-lang × per-
      backend table (✅ / ❌), plus a v1.26-specific subsection
      explaining the dual rewriter (`rewriteJdbc` for JVM/Interpreter,
      `rewriteSparkSql` for Spark) and connection resolution.

JvmGen scala-cli runtime smoke-test landed earlier in v1.26.2 —
`JvmGenSqlRuntimeTest` rewrites the emitted `//> using lib` directive
to `//> using jar "<absolute-path>"` against the locally-built jar
plumbed through `Test / resourceGenerators`, so end-to-end coverage
exists without requiring the artifact to be published to Maven
Central.

### Follow-ups discovered during work

- **`client-postgres` reconciliation (landed v1.26.1).**  Originally
  `client-postgres` (commit `d45a250`) shipped with its own bind
  logic (poor subset of `Jdbc.bindOne`'s type matrix), and the
  transaction-path `withStmt` had a bug — `Some(x)` was passed
  through to `setObject` without recursive unwrap, and typed setters
  (`setString` / `setBoolean` / …) were skipped entirely.  Resolved
  via option (b) — `client-postgres` now `dependsOn(backendSqlRuntime)`
  and both client paths call the shared `scalascript.sql.Jdbc.bindAll`.
  Side effects of the consolidation:

  - Tx-path bind path matches the pooled path exactly (no behavioural
    diff between `client.execute(...)` and `client.transaction { tx
    => tx.execute(...) }`).
  - `ColumnDecoder` coverage expanded to match the bind side:
    `Short`, `Byte`, `Float`, `BigInt`, `java.time.{LocalDate,
    LocalTime, LocalDateTime, Instant, OffsetDateTime}`,
    `java.util.UUID`, `Array[Byte]`.  Legacy `java.sql.{Date, Time,
    Timestamp}` results auto-normalise to `java.time.*`.
  - Hand-written `Option[String|Int|Long|Double|BigDecimal]` givens
    replaced by a single generic `optionDecoder[A]` lift over any
    `ColumnDecoder[A]` — uses `rs.wasNull()` so primitive defaults
    (Int → 0, Boolean → false) correctly map to `None`.
  - `RowDecoder` single-column givens replaced by a generic
    `singleColumn[A]` lift over `ColumnDecoder[A]`; `queryOne[T]`
    works for every type `ColumnDecoder` supports.
  - `docs/postgres.md` rewritten to match the actual code (the
    previous version described a fictional `Async` / `AsyncStream`
    API that wasn't implemented).
  - `PgClientTest` extended with 7 cases pinning the new type
    coverage + tx-path consistency.  18 PgClient tests pass; both
    downstream consumers (`x402-nonce-postgres`,
    `x402-queue-postgres`) compile + test unchanged.

### Out of scope (deferred, not committed)

- Transactional API.  Phase 6 commits per statement (JDBC default).
  A `transaction { ... }` block-level helper is a follow-up — design
  it once the first real consumer surfaces.
- Static SQL type-checking (column types vs. case class fields at
  compile time).  Possible later via JDBC metadata at parse time,
  but adds a database round-trip to compilation — kept off until
  someone asks.
- Streaming results / cursor mode.  Phase 6 returns full `Seq[Row]`.
  Adding a `.stream` variant is mechanical; defer until a real
  large-result use case.
- Browser-side SQL (sql.js / DuckDB-Wasm).  Picked up in
  v1.27 — see [`docs/browser-sql.md`](docs/browser-sql.md).  As
  predicted, no IR / spec change needed; v1.27 is an additive
  capability declaration + a JS-side runtime module.

---

## v1.27 — browser-side SQL (sql.js / DuckDB-Wasm)

**Status: complete (2026-05-21). All 7 phases landed. Spec: [`docs/browser-sql.md`](docs/browser-sql.md).**

Extends the v1.26 `sql` fenced-block feature from JVM-only to the JS,
Node, and Wasm backends.  Same source, same `${expr} → ?` bind rule,
same `SqlBindRewriter.rewriteJdbc` output — only the runtime changes.
Two embedded engines, picked by URL prefix in the front-matter
`databases:` entry:

- `sqlite::memory:` / `sqlite:<path>` → sql.js (SQLite-WASM).
- `duckdb:` / `duckdb:<path>` → DuckDB-Wasm.
- `jdbc:*` URLs surface a build-time `UnsupportedJdbcUrl` diagnostic
  on JS / Node / Wasm targets (JVM target unaffected).

File-backed URLs work on Node; browser raises `MissingFs` at runtime
(parser cannot tell the two apart from front-matter alone — same
backend id for both).  Browser-side execution is always async by
construction; the emitted contract per block is `Promise[SqlResult]`
gated by a top-level `await` (or IIFE wrapper on legacy targets).

Parallel-safety note: no overlap with active worktrees.  Adds a new
`backend-sql-runtime-js` module + edits to the three JS-family
backend capabilities files (none of which other worktrees touch
today).

### Phase 1 — Spec + milestone (this iteration)

- [x] `docs/browser-sql.md` — goals, non-goals, architecture (module
      layout, URL→provider dispatch, runtime contract, override
      path), migration, 7 phases, testing strategy, 4 open
      questions.
- [x] `MILESTONES.md` v1.27 entry (this section).

### Phase 2 — `backend-sql-runtime-js` module ✓ Landed

- [x] New sbt module `backendSqlRuntimeJs`; `sql-runtime.mjs` shared
      facade (Connection / Row / Registry / execute), provider
      dispatch (`Providers.fromUrl`).
- [x] `SqlJsProvider` (sql.js wiring) + `DuckDbWasmProvider`
      (DuckDB-Wasm wiring).  Node uses `web-worker` (declared in
      the emitted `package.json`) over `node:worker_threads`; browser
      uses the JsDelivr default bundle.
- [x] `SqlRuntimeJsEmit` — codegen helper that loads the bundled
      `.mjs` source from the classpath and emits the bundle preamble
      (`ConnectionRegistry` init + `_ssc_sql_resolve(dbName)`
      override-or-registry dispatcher).  Shared across JsGen,
      NodeBackend, WasmBackend.
- [x] `ProviderId.fromUrl` — Scala-side mirror of the JS dispatch
      table; used by future Phase 4/5 backends to decide which npm
      deps to emit.
- [x] Tests:
      * 12 dispatch + enum-surface cases (`ProviderIdTest`).
      * 13 emit cases (`SqlRuntimeJsEmitTest`): resource load,
        registry-init JS shape for empty / single / full / multi
        entries, `${env:NAME}` preservation, jsString escapes, full
        preamble composition.
      * 16 Node `--test` cases under one Scala wrapper
        (`SqlRuntimeJsNodeTest`): sql.js (10 — CRUD, multi-row
        order, null binds, BLOB, boolean, Date, UPDATE count, PRAGMA,
        Row API, registry cache+reopen) + DuckDB-Wasm (6 — CRUD,
        GROUP BY, CTE/window, null binds, Row toMap, registry).
        Materialises into `target/sql-js-node-test/`, runs
        `npm install` once (mtime-stamped), then
        `node --test --test-force-exit *.test.mjs`.  Gracefully
        skips when `node` / `npm` aren't on PATH.

### Phase 3 — JsGen codegen for sql blocks ✓ Landed

Mirrors JvmGen Phase 6.C, adapted for async.

- [x] `JsCapabilities.blockLanguages += Lang.Sql`.  Generic
      `UnknownBlockLanguage("sql")` diagnostic no longer fires on the
      JS target.  NodeBackend / WasmBackend keep their old behaviour
      until Phase 4 / 5.
- [x] `JsGen.genSection` recognises `Lang.isSql`, emits
      `const _sqlBlock_<N> = await SqlRuntimeJs.execute(await
      _ssc_sql_resolve(<dbName>), <?-templated SQL>, [<binds>])`.
      First sql block per section also emits
      `if (typeof <sectionId> === 'undefined') var <sectionId> = {}; <sectionId>.sql = _sqlBlock_<N>`
      (matches the existing `genStringBlock` shape for `<sectionId>.html`
      / `.css`).
- [x] `_ssc_sql_registry` materialised from `manifest.databases`
      (shared `SqlRuntimeJsEmit.emitRegistryInit` from
      backend-sql-runtime-js); empty registry when the module has no
      front-matter `databases:`.  `${env:NAME}` markers in URL / user /
      password preserved verbatim — resolved at runtime by
      `sql-runtime.mjs`'s `resolveEnvRefs`.
- [x] `_ssc_sql_resolve(dbName)` checks `_ssc_sql_connections`
      (annotation override path, populated by future-Phase 6 codegen)
      first; falls back to `_ssc_sql_registry.connect(dbName ?? "default")`.
- [x] Bundle preamble — `sql-runtime.mjs` source inlined verbatim
      (with `export ` stripped so names land at script-level scope),
      followed by `const SqlRuntimeJs = { execute, ConnectionRegistry,
      ... }` namespace alias.  User body wrapped in
      `(async () => { ... })().catch(...)` — required for the per-
      block `await`s.  When the module also uses `runAsyncParallel`,
      the two flags collapse into one `needsAsync` decision so the
      IIFE wraps once.
- [x] `JsGen.bindExprToJs(exprSrc)` — parses each bind text back to
      `scala.meta.Term` and emits JS via the existing `genExpr`,
      so a bind like `${user.id + 1}` becomes the JS expression
      that evaluates in the surrounding scope.  Defensive fallback
      to verbatim source on parse failure.
- [x] `backend-js/build.sbt` now `dependsOn(backendSqlRuntimeJs)` —
      pulls in `SqlRuntimeJsEmit` for codegen + the bundled .mjs
      classpath resource.
- [x] Tests: `JsGenSqlBlockTest` (12 cases) — no-sql passthrough,
      preamble emission, `export ` stripping, async IIFE wrap,
      empty/populated registry, `${env:NAME}` preservation,
      per-block `_sqlBlock_<N>` emission with / without binds,
      sequential numbering, section alias (first-only, second
      doesn't redefine), `@db=name` threading, default fallback.
      All 12 green; full backend-interpreter suite (1228 tests)
      stays green.

### Phase 4 — NodeBackend wiring ✓ Landed

- [x] `NodeCapabilities.blockLanguages += Lang.Sql`.  Generic
      `UnknownBlockLanguage("sql")` diagnostic no longer fires on the
      Node target.
- [x] `NodeBackend.compile` emits a companion `package.json`
      `SourceArtifact` when sql blocks are present.  Deps are gated on
      actual provider references in `manifest.databases`: a module that
      only uses sqlite gets only `sql.js`; only-duckdb gets
      `@duckdb/duckdb-wasm` + `web-worker` (the Node Worker shim
      sql-runtime.mjs imports); both-or-neither (no `databases:`
      declared at all → annotation fallback) gets all three.
- [x] Output bundle is `.cjs` (CJS) — JsRuntime uses `require('fs')`
      etc; switching to ESM would require rewriting the entire
      runtime.  `await import('sql.js')` inside sql-runtime.mjs works
      fine in CJS context, so sql blocks compose with the CJS runtime.
- [x] `sql-runtime.mjs` rewrote `createRequire(import.meta.url)` to
      a dual-mode resolver (`globalThis.require` in CJS context;
      `createRequire("file://${cwd}/.")` in ESM).  `import.meta.url`
      is a syntax error in CJS, so any reference would break the
      NodeBackend embed even on an unreachable code path.
- [x] `NodePrintlnWriteThrough` — replaces JsRuntime's buffered
      `_println` with a write-through that pushes both to `_output`
      AND to `process.stdout`.  Necessary because JsGen wraps the
      sql-block body in an async IIFE; the original post-bundle flush
      ran synchronously before the IIFE's async work completed, so
      `println` calls inside it were silently dropped.
- [x] Swapped `NodeBackendTest`'s `UnknownBlockLanguage("sql")` case
      with `accepts sql blocks (no diagnostic)`; updated the
      `blockLanguages` set assertion to include `sql`.  Dedicated
      `UnsupportedJdbcUrl` validate-time diagnostic deferred to
      Phase 6 — until then, `jdbc:` URLs surface a runtime
      `UnsupportedJdbcUrl` from `sql-runtime.mjs`.
- [x] `NodeBackendSqlTest` (8 cases):
      * 5 unit cases — no-sql passthrough; sqlite-only deps; duckdb-
        only deps (+ web-worker); both providers; no-databases-declared
        fallback (lists everything).
      * 3 end-to-end cases — sqlite in-memory CRUD, DuckDB
        aggregation, `${expr}` binds evaluate in surrounding scope.
        Real `npm install` + `node main.cjs`.  Shared cache dir
        keyed by `package.json` ⇒ one install across the suite.
        Skips when node / npm not on PATH.
- [x] Full `backendNode/test` (22 tests) green; `backendSqlRuntimeJs/test`
      (27 tests) green; `backendInterpreter/testOnly JsGenSqlBlockTest`
      (12 tests) green.

### Phase 5 — WasmBackend wiring ✓ Landed (2026-05-20)

- [x] `WasmCapabilities.blockLanguages = Set(Lang.Sql)`.
- [x] `WasmBackend.emitJsShim` — when sql blocks are present, the
      `Segmented` result gains three assets mirroring NodeBackend's
      package-json emit:
      * `Segment.Asset("sql-runtime.mjs", …)` — bundled JS runtime via
        `SqlRuntimeJsEmit.runtimeSource`.
      * `Segment.Asset("sql-registry.mjs", …)` — per-module registry
        init derived from `manifest.databases`.
      * `Segment.Asset("package.json", …)` — npm deps (`sql.js`,
        `@duckdb/duckdb-wasm`, `web-worker`) gated on referenced
        providers; ESM (`"type": "module"`) since the Wasm shim is
        itself an ES module.
      Wasm body itself unaffected; sql-only modules (no scala blocks)
      still emit the three sql assets.
- [x] `backend-wasm/build.sbt` — `dependsOn(backendSqlRuntimeJs)` to
      pull in `SqlRuntimeJsEmit` + `ProviderId`.
- [x] Tests: `WasmBackendSqlTest` (8 cases — no-sql passthrough, sqlite-
      only deps, duckdb-only deps, both, no-databases fallback, runtime
      verbatim, registry shape with named connections, empty-registry
      shape).
- [x] Swap `WasmBackendTest`'s `UnknownBlockLanguage("sql")` case to
      assert **no** diagnostic (matches NodeBackendTest's Phase-4 case).

Deferred to Phase 7: end-to-end runtime execution of sql blocks under
the Wasm shim (`SqlBrowserExamplesTest` / `SqlBrowserConformanceCaptureTest`).
The current Wasm bytecode doesn't have extern bindings to invoke
`SqlRuntimeJs.execute` from compiled Scala.js wasm; the asset bundle
ships ready to install, but wiring user-code sql blocks to those
assets needs additional Wasm-side codegen beyond Phase 5's scope.

### Phase 6 — `UnsupportedJdbcUrl` diagnostic ✓ Landed

- [x] New `Diagnostic.UnsupportedJdbcUrl(db, url, backend)` case in
      the backend-spi enum.
- [x] `validate/CapabilityCheck.unsupportedJdbcUrls` raises the
      diagnostic when the target declares `Lang.Sql` in
      `blockLanguages` AND outputs include `JavaScriptSource` or
      `WasmBytecode` AND a `manifest.databases` entry's URL starts
      with `jdbc:`.  Heuristic chosen over hardcoded target id
      whitelist — adding a new JS-family backend in the future
      automatically picks up the same gating.
- [x] JVM-family targets (interpreter, JvmBytecode) unaffected: they
      accept `jdbc:` URLs natively via `backend-sql-runtime`.
- [x] Tests: `CapabilityCheckTest` extended with 7 cases — jdbc on
      JS-family → diagnostic, sqlite/duckdb on JS-family → no
      diagnostic, jdbc on JVM → no diagnostic, multiple jdbc entries
      → one diagnostic each, Wasm output kind triggers gating,
      target without sql in blockLanguages → orthogonal (no jdbc
      diag, but UnknownBlockLanguage still fires for the sql fence).
      All 23 cases green.

Wire-format follow-up (`SubprocessBackend.diagnosticFromWire`)
deferred — no subprocess plugin currently emits this diagnostic;
the wire encoder will gain a `unsupported-jdbc-url` kind the first
time a plugin needs to surface it.

### Phase 7 — Examples + conformance ✓ Landed (2026-05-20)

- [x] `examples/sql-browser-sqlite.ssc` — zero-config sqlite::memory:
      with `${expr}` binds, `<SectionId>.sql.rows` access, tagged
      `backends: [js, node, wasm]`.
- [x] `examples/sql-browser-duckdb.ssc` — two named connections
      (sqlite default + duckdb analytics) in the same module,
      `@db=analytics` routing for the analytical GROUP BY.
- [x] `SqlBrowserExamplesTest` (2 cases) — self-contained, inlines
      example sources verbatim, compiles via NodeBackend, runs under
      `node main.cjs` against real `sql.js` / `@duckdb/duckdb-wasm` /
      `web-worker`.  Stable cache dir per provider set ⇒ one
      `npm install` per `package.json` shape.  Skipped gracefully
      when `node` / `npm` aren't on PATH.
- [x] `conformance/sql-browser-basic.ssc` +
      `conformance/expected/sql-browser-basic.txt` — pins the v1.27
      browser-side sql contract (CREATE + INSERT with `${expr}` bind
      + SELECT + UPDATE-style row count via `.sql.count`) under the
      JS-family targets.  Tagged `backends: [js, node, wasm]`; carries
      `pending: needs npm install in conformance/run.sc JS lane` so
      the cross-backend harness skips it (the JS lane pipes emitted
      code to `node` without `npm install`, so `import 'sql.js'`
      fails with `MODULE_NOT_FOUND`).  The in-process
      `SqlBrowserConformanceCaptureTest` is the real regression net.
- [x] `SqlBrowserConformanceCaptureTest` — reads the on-disk
      conformance file (so drift between contract surface and test is
      surfaced loudly), compiles via NodeBackend, runs through the
      same `npm install` + `node main.cjs` harness as
      `SqlBrowserExamplesTest`, asserts stdout matches
      `conformance/expected/...` byte-for-byte.  Mirrors
      `SqlConformanceCaptureTest` (interpreter lane).
- [x] `docs/targets.md` — block-language matrix flipped ✅ for `sql`
      on JS / Node / Wasm (per-target parenthetical notes the
      runtime — sql.js / DuckDB-Wasm — plus the v1.27 marker).  New
      v1.27 subsection documents the URL-prefix dispatch table, the
      jdbc-only-on-JVM rule, and the per-target emit-time artifacts
      (Node ships `package.json`; Wasm ships `sql-runtime.mjs` +
      `sql-registry.mjs` + `package.json` as `Segment.Asset`s).

### Out of scope (deferred to v1.28+ or beyond)

- Sync SQL — every browser engine is async; no `deasync` shims.
- Network DBs from browser — use `client-postgres` from a server
  backend, expose via HTTP.
- Cross-runtime data sharing — JVM-process in-memory data is not
  visible to JS-process runs of the same module.
- Static SQL type-checking — inherits v1.26's deferral.
- ~~`transaction { ... }` block-level helper~~ — **✓ Landed v1.31 (2026-05-21).**

---

## Infrastructure clients — general-purpose ScalaScript libraries

Specs in `docs/`: `postgres.md`, `kafka.md`, `evm.md`, `coinbase.md`, `redis.md`.

### `postgres` — PostgreSQL client (JDBC + HikariCP) ✓ Complete (2026-05-21)

- [x] `PgConfig` (host/port/database/user/password/poolSize/fetchSize) + HikariCP pool
- [x] `PgClient`: `query[A]`, `queryOne[A]`, `execute`, `transaction`, `stream`, `foldLeft`, `close`
- [x] `RowDecoder[A]` typeclass + `ColumnDecoder[A]` with full type matrix (primitives, java.time, UUID, Array[Byte], Option)
- [x] Auto-derive `RowDecoder` for case classes via Scala 3 Mirror (column-position); tuple decoders arity 2+3
- [x] JDBC calls wrapped in `Future(blocking { ... })`
- [x] `stream[A]` / `foldLeft[A,B]` — cursor-based streaming via `setFetchSize` + TYPE_FORWARD_ONLY cursor; autoCommit saved/restored; available inside transaction
- [x] 26 tests against in-memory H2 (PostgreSQL compat mode); all green

### `kafka` — Kafka client (kafka-clients) ✓ Complete (landed earlier)

- [x] `KafkaConfig`, `KafkaRecord`, `RecordMeta`
- [x] `KafkaProducer` (string + bytes): `send`, `sendBytes`, `flush`, `close`
- [x] `KafkaConsumer`: `subscribe`, `poll`, `commit`, `close`
- [x] JDBC calls wrapped in `Future(blocking { ... })`
- [x] Tests skip gracefully when Kafka not on localhost:9092

### `evm` — EVM / JSON-RPC client ✓ Complete (landed earlier)

- [x] `EvmConfig` + `EvmNetworks` registry (Base, Ethereum, Polygon, Arbitrum, Optimism)
- [x] `EvmClient`: `blockNumber`, `getBalance`, `erc20Balance`, `erc20Allowance`
- [x] Transaction queries: `getTransaction`, `getReceipt`, `waitForReceipt`
- [x] `call` (eth_call) + raw `rpc` escape hatch
- [x] Implemented over HTTP JSON-RPC (sttp + upickle; no external Web3 library)
- [x] Tests skip gracefully when no local Anvil node

### `coinbase` — Coinbase API client ✓ Complete (landed earlier)

- [x] `CoinbaseConfig` + JWT/HMAC auth
- [x] `CoinbaseTrade`: products, candles, accounts, orders
- [x] `CoinbaseCdp`: wallet create/get, transfer, list balances
- [x] `CoinbaseFacilitator`: `verify`, `settle` (x402 facilitator API)
- [x] Tests with mocked HTTP

### `redis` — Redis client (Lettuce) ✓ Complete (landed earlier)

- [x] `RedisConfig` + Lettuce async connection (single-node)
- [x] Strings: `get`, `set` (+ TTL), `setNx`, `getSet`, `del`, `exists`, `expire`, `ttl`, `incr`, `incrBy`
- [x] Hashes: `hget`, `hset` (single + map), `hgetAll`, `hdel`, `hexists`, `hkeys`
- [x] Lists: `lpush`, `rpush`, `lpop`, `rpop`, `lrange`, `llen`
- [x] Sets: `sadd`, `srem`, `smembers`, `sismember`, `scard`
- [x] Sorted sets: `zadd` (single + map), `zrange`, `zscore`, `zrank`, `zrem`, `zcard`
- [x] Key ops: `keys`, `flushDb`
- [x] Tests skip gracefully when Redis not available

---

## x402 — HTTP payment protocol

Spec in `docs/x402.md`.

### Phase 1 — Core (`x402-core`) ✓ Landed

- [x] `PaymentScheme`: `Exact`, `Stream`, `CardanoExact`
- [x] `PaymentRequirements`, `TransferAuthorization`, `PaymentPayload`
- [x] `CardanoAsset`, `CardanoPaymentProof`
- [x] `Network`, `Asset`, `Assets` registry
- [x] `Facilitator` trait + `VerifyResult` / `SettleResult`
- [x] `NonceStore` trait + in-memory implementation
- [x] `SettlementMode`: `Synchronous` / `Async(queue)`
- [x] `SettlementQueue` trait + in-memory implementation

### Phase 2 — Server middleware (`x402-server`) ✓ Landed

- [x] `PaymentConfig` + `withPayment(config) { routes }` DSL
- [x] 402 response with `requirements` JSON body
- [x] `X-Payment` header parsing + base64 decode
- [x] Nonce claim before facilitator call (double-spend guard)
- [x] Sync settlement path (verify + settle in request)
- [x] Async settlement path (verify in request, enqueue settle)
- [x] `onSettled` callback hook
- [x] Tests: no-payment → 402, valid payment → 200, replay → 402

### Phase 3 — Client interceptor (`x402-client`) ✓ Landed

- [x] `Wallet` trait + `Eip712Domain`
- [x] `Wallets.metaMask()` (browser / window.ethereum) — landed in
      `x402ClientJs` (2026-05-28): connects with
      `eth_requestAccounts`, validates `eth_chainId`, and signs
      EIP-712 payloads via `eth_signTypedData_v4`. The current JS
      slice is a browser wallet helper; full browser `X402Client`
      retry/interceptor parity remains a future follow-up.
- [x] `Wallets.privateKey(hex, network)` + `Wallets.envKey(envVar, network)`
- [x] `X402Client(wallet, maxAmount, backend)` interceptor
- [x] Auto-retry on 402: parse requirements, sign, add `X-Payment`, retry
- [x] Refuse if `maxAmountRequired > maxAmount`
- [x] Tests: 402 → sign → 200 round-trip (mocked server)

### Phase 4 — EVM facilitators ✓ Landed

- [x] `x402-facilitator-coinbase`: delegates to `CoinbaseClient.x402`
- [x] `x402-facilitator-evm`: balance-check verify via `EvmClient` + pluggable settler
- [x] `Facilitators.withFallback(primary, fallback)` — in x402-core
- [x] `Facilitators.testnet()` — always Ok, no real settlement — in x402-core
- [x] Tests: verify Ok / Fail paths, settlement happy path

### Phase 5 — Durable queues and nonce stores ✓ Landed

- [x] `x402-queue-kafka`: `SettlementQueue` via `KafkaProducer` (enqueue); drain is application-side
- [x] `x402-queue-postgres`: `SettlementQueue` backed by `PgClient` (enqueue + process)
- [x] `x402-nonce-postgres`: `NonceStore` backed by `PgClient` (`ON CONFLICT DO NOTHING`)
- [x] `x402-nonce-redis`: `NonceStore` backed by `RedisClient` (`setNx` with TTL)

### Phase 6 — Cardano facilitator (`x402-facilitator-cardano`) ✓ Landed

- [x] `CardanoFacilitatorConfig` + `CardanoProvider` enum (Blockfrost, Scalus)
- [x] `CardanoProvider.Blockfrost`: balance check via Blockfrost API + CIP-8 verify
- [x] `CardanoProvider.Scalus`: server-side Tx building via Scalus + cardano-client-lib (bloxbean) — ✓ Landed via Phase 10 (x402-facilitator-cardano-scalus module + ScalusSettler)
- [x] CIP-8 signature verification (COSE_Sign1 + COSE_Key, Ed25519 via BouncyCastle)
- [x] Settlement: Blockfrost path — optimistic Ok after verify; Scalus — stub Fail
- [x] Tests: MiniCbor round-trips, CIP-8 verify, balance check, native assets, settlement

### Phase 7 — Stream scheme (metered billing) ✓ Landed

- [x] `PaymentScheme.Stream`: rate-per-unit, maxUnits, maxAmount
- [x] Server: validate `authorization.value == ratePerUnit * X-Units`; `withStreamPayment` wrapper
- [x] Client: authorizes `ratePerUnit` per request; session budget tracking; exhaustion → 402
- [x] Tests: unit counting, multi-unit, budget exhaustion, ratePerUnit > maxAmount guard

### Phase 8 — Test mode + examples ✓ Landed

- [x] `X402.testConfig(payTo)` — auto BaseSepolia + testnet facilitator
- [x] `X402.isTestMode` from `X402_ENV` env var
- [x] `examples/x402-server.ssc` — payment-gated REST endpoint
- [x] `examples/x402-client.ssc` — client auto-handles 402
- [x] `examples/x402-cardano.ssc` — Cardano payment flow (2026-05-20)

### Phase 9 — Cardano client-side wallet ✓ Landed (2026-05-20)

Closes the asymmetry between the Cardano facilitator (verifies CIP-8)
and the x402 client (previously EVM-only). `Wallets.cardano(hex,
address, network)` produces a CIP-8 / COSE_Sign1 proof via an Ed25519
`RawSigner`; `PayloadBuilder.build` branches on `CardanoExact` and
emits `cardanoProof` in the encoded payload. `MiniCbor` moved from
`x402-facilitator-cardano` to `x402-core` so the signer can share it
with the verifier. `Network` enum gained `CardanoMainnet` /
`CardanoPreprod` / `CardanoPreview`. The `Wallet` trait now also
declares `signCip8`; EVM and Cardano wallets reject the wrong shape.

- [x] `x402-core/MiniCbor` — moved from facilitator, now shared
- [x] `Network.CardanoMainnet/Preprod/Preview`; `Network.isCardano`
- [x] `x402-payments/client/Cip8Signer` — COSE_Sign1 + COSE_Key assembly
- [x] `CardanoPrivateKeyWallet` via `RawPrivateKeyVault(Ed25519)`
- [x] `Wallets.cardano` / `Wallets.cardanoEnvKey` factories
- [x] `PayloadBuilder.buildCardano` + `encode` cardanoProof field
- [x] Server `parsePayload` parses Cardano network names
- [x] `CardanoPayloadTest` — 5 tests round-trip-verify the proof with
      BouncyCastle Ed25519; signer / payload shape / dual-wallet reject
- [x] `CardanoFacilitatorTest` mocks updated for `getUtxos`/`submitTx`
      (pre-existing breakage from blockchain-cardano Phase 6)
- [x] CIP-19 enterprise address derivation from key (2026-05-20) —
      `Wallets.cardano(hex, network)` now derives `addr1` / `addr_test1`
      via `blockchain-cardano.CardanoAddress.fromPublicKey`; the
      `(hex, address, network)` form remains for stake-aware base
      addresses; example dropped its `CARDANO_ADDR` env var
- [x] Base addresses with staking (2026-05-20) —
      `CardanoAddress.fromPublicKeys(payment, stake, testnet)` builds
      CIP-19 type-0 base addresses (`header || Blake2b-224(payment) ||
      Blake2b-224(stake)`); `Wallets.cardanoBase(paymentHex, stakeHex,
      network)` + `cardanoBaseEnvKey` factories. Signing still uses
      only the payment key — stake key participates in the address but
      never signs payments. `CardanoAddress.Kind` exposed for caller
      sanity checks (Base / Enterprise / Reward / Pointer / Script).
- [→] `CardanoProvider.Scalus` settlement → see Phase 10 below

### Phase 10 — Scalus / Plutus-escrow settlement

Spec in [`docs/x402-cardano-scalus.md`](docs/x402-cardano-scalus.md).
Replaces the optimistic `CardanoProvider.Blockfrost` `Ok` with on-chain
Plutus-enforced escrow: payer locks lovelace at a script address, the
facilitator's relayer claims via a Tx whose redeemer carries the CIP-8
proof. Validator written in Scalus DSL; off-chain Tx building via
bloxbean `cardano-client-lib`.

#### Phase 1 — Spec + module scaffolding ✓ Landed (2026-05-20)

- [x] `docs/x402-cardano-scalus.md` — goals, escrow datum/redeemer
      shape, off-chain flow, 6-phase plan
- [x] New module `x402-facilitator-cardano-scalus` (build.sbt entry,
      depends on `x402Core` + `x402FacilitatorCardano`)
- [x] `ScalusSettler` trait + `ScalusSettler.unimplemented` stub +
      `asConfigHook` function adapter
- [x] `CardanoFacilitatorConfig.scalusSettle: Option[(payload, req)
      => Future[SettleResult]]` — pluggable hook on the existing
      facilitator. Default behavior unchanged (Scalus path still
      returns `Fail` with hint pointing at the new wiring).
- [x] 5 tests: stub Fail, hook delegation, Blockfrost-path
      regression, end-to-end settle delegation
- [x] All 17 existing Cardano facilitator tests still green

#### Phase 2 — On-chain validator (Scalus)

**Spike landed (2026-05-20)**: package rename + spec update with
blocker analysis. Validator code itself NOT yet landed — six concrete
issues documented in [`docs/x402-cardano-scalus.md`](docs/x402-cardano-scalus.md)
§5 (Phase 2 → Spike findings). Retry order:

- [x] Package rename `scalascript.x402.facilitator.scalus` →
      `scalascript.x402.facilitator.plutus` to avoid shadowing the
      Scalus library's top-level `scalus` package
- [x] Spec §5 expanded with: package collision, upickle 3↔4 eviction
      conflict, Scala 3.3.7→3.8.3 version drift, `Validator` trait's
      five deferred-inline purposes (need `ParameterizedValidator`),
      top-level vs nested derivation, doc-vs-jar import drift
- [x] **Prerequisite**: project-wide upickle 3.3.1 → 4.4.2 bump
      across ~21 modules + sttp.client4 4.0.0-M17 → 4.0.23 (commit
      `b736c5a6`). All ~120 affected tests stay green.
- [x] **Scala-version split build (2026-05-20)** — Phase 2 now ships
      real Plutus Core CBOR. New sbt sub-project `x402-escrow-plutus`
      pinned to `scalaVersion := "3.3.7"` (per-project override of
      the build's 3.8.3) carries the validator source + Scalus 0.15.1
      library + scalus-plugin 0.15.1. The plugin lowers `@Compile`
      validators correctly under 3.3.7. The sbt task
      `x402EscrowPlutus/emitEscrowHex` writes the compiled CBOR hex
      `payments/x402/facilitator-cardano-scalus/src/main/resources/x402-escrow.plutus.hex`.
      The main 3.8.3 module reads it via classloader at runtime —
      Scalus library dropped from the 3.8.3 module's deps.
- [x] `X402EscrowScript` — single-purpose Plutus V3 validator
      (`@Compile object … inline def validate(scData: Data)`, dispatch
      on `ScriptInfo.SpendingScript`, other purposes fail)
- [x] `EscrowDatum` + `EscrowRedeemer` (Claim/Refund) at top level
      with `derives FromData, ToData`
- [x] Structural checks (signatory presence for Claim / Refund)
      enforced on-chain
- [x] 4 tests in `X402EscrowCompiledTest`: resource present, hex
      well-formed, decoded length matches, deterministic across reads,
      >100 bytes (proves non-trivial program)
- [x] On-chain CIP-8 verification: COSE_Sign1 decode + Ed25519 verify
      against datum.payerKeyHash; payload-hash equality check
      ✓ Landed (2026-05-27): the Scalus validator accepts the
      canonical COSE_Key / COSE_Sign1 shape emitted by `Cip8Signer`,
      checks `blake2b_224(pubKey) == datum.payerKeyHash`, checks
      `blake2b_256(payload) == datum.claimMessageHash`, and verifies
      Ed25519 over the CIP-8 Sig_Structure. The committed Plutus
      resource was regenerated from 1208 to 3830 hex chars.
- [x] Output-shape check: exact lovelace to datum.receiver
      ✓ Landed (2026-05-27): Claim now requires at least one
      transaction output whose payment credential is
      `PubKeyCredential(datum.receiverHash)` and whose lovelace amount
      is exactly `datum.amount`. The committed Plutus resource was
      regenerated from 3830 to 5240 hex chars.
- [x] Validity-range check vs `datum.validBefore` / `datum.refundAfter`
      ✓ Landed (2026-05-27): Claim requires the transaction validity
      range to be entirely before `datum.validBefore`; Refund requires
      it to be entirely after `datum.refundAfter`. The committed
      Plutus resource was regenerated from 5240 to 6096 hex chars.
- [x] Unit tests via Scalus's script-context simulator under
      x402-escrow-plutus (Phase 2.5)
      ✓ Landed (2026-05-27): `X402EscrowScriptSimulatorTest`
      constructs `ScriptContext` values directly and validates claim
      happy path plus rejection branches for tampered CIP-8 signature,
      wrong receiver amount, invalid claim range, and refund timing.

#### Phase 3 — Escrow address + reference script

- [x] `EscrowScript.address(network)` — compiled-validator address
      ✓ Landed (2026-05-27): hashes the committed Plutus validator
      bytes with Blake2b-224 and emits CIP-19 enterprise script
      addresses for Cardano mainnet/preprod/preview.
- [x] Reference-script deploy helper (one-time op)
      ✓ Landed (2026-05-27): `ReferenceScriptDeployer.deploy(blockfrost,
      network, signingKeyHex)` builds and submits a bloxbean Tx posting
      the compiled Plutus V3 script as a reference-script output at the
      escrow script address; returns `(txHash, outputIndex)`. Config field
      `ScalusSettlerConfig.referenceScriptRef: Option[String]` stores the
      canonical `"<txHash>#<index>"` form. 2 tests: deploy returns correct
      txHash+index, round-trip parse into `ScalusSettlerConfig`.
- [x] Golden-bech32 test for stable script address per network
      ✓ Landed (2026-05-27): updated to current compiled validator hash.
      `addr1w9jy7xtwcuh8pp08ete45esset9rskafz7gqapgej5x59ss78k05l`
      (mainnet) and
      `addr_test1wpjy7xtwcuh8pp08ete45esset9rskafz7gqapgej5x59ss90znm6`
      (preprod / preview).

#### Phase 4 — Off-chain claim Tx via bloxbean

- [x] Add `com.bloxbean.cardano:cardano-client-lib` dependency
      ✓ Landed (2026-05-27): `x402-facilitator-cardano-scalus`
      depends on `cardano-client-lib` `0.8.0-preview1`.
- [x] `ScalusSettler.preprod(cfg)` / `.mainnet(cfg)` factories
      ✓ Landed (2026-05-27): `ScalusSettlerConfig`, typed
      `ClaimTxPlan`, injectable `ClaimTxBuilder`, and Blockfrost
      submit pipeline. Default builder still fails explicitly until
      Plutus witness construction lands.
- [x] Tx building: input = escrow UTxO ref, output = receiver +
      amount, redeemer = CIP-8 proof bytes, witness = relayer key — ✓ Landed (2026-05-27, all sub-items complete)
      - [x] Redeemer construction ✓ Landed (2026-05-27):
            `EscrowRedeemerCodec` builds bloxbean `PlutusData`
            `Claim(coseSign1Bytes, coseKeyBytes)` and exposes it on
            `ClaimTxPlan.claimRedeemer`.
      - [x] Draft bloxbean Transaction skeleton ✓ Landed (2026-05-27):
            `BloxbeanClaimTxBuilder.draft` serializes escrow input,
            receiver output, Plutus V3 script, and Spend redeemer;
            not production default.
      - [x] Collateral + required signer body fields ✓ Landed
            (2026-05-27): optional `ScalusSettlerConfig.collateralRef`
            and `relayerKeyHashHex` flow into draft transaction
            collateral inputs and required signers.
      - [x] Draft script data hash + relayer vkey witness ✓ Landed
            (2026-05-27): explicit `feeLovelace`, `ttlSlot`, and
            `validityStartSlot` flow into the transaction body;
            bloxbean `ScriptDataHashGenerator` computes the script
            data hash; `TransactionSigner` attaches a relayer
            `VkeyWitness`.
      - [x] Blockfrost protocol params reader ✓ Landed
            (2026-05-27): `BlockfrostClient.getProtocolParams()`
            parses latest-epoch fee, execution-price, collateral,
            and Plutus cost-model parameters for the planned balancer.
      - [x] Protocol-params min-fee balancing ✓ Landed
            (2026-05-27): `ScalusFeeBalancer` applies Cardano's
            linear min-fee formula to final draft CBOR size;
            `BloxbeanClaimTxBuilder.draftBalanced(...)` supports
            static params or async Blockfrost params.
      - [x] Static Plutus ex-units wiring ✓ Landed
            (2026-05-27): `ScalusSettlerConfig.claimExUnits` flows
            into `ClaimTxPlan`, redeemer `ExUnits`, and balanced fee
            estimation.
      - [x] Bloxbean evaluator adapter ✓ Landed
            (2026-05-27): `ScalusTxEvaluator.bloxbean(...)` adapts
            bloxbean `TransactionEvaluator` results into typed
            `ScalusExUnits`; evaluated-balanced draft rebuilds the
            redeemer and fee from evaluator output.
      - [x] Blockfrost/Ogmios evaluate endpoint for live ex-units
            ✓ Landed (2026-05-27): `BlockfrostClient.evaluateTx`
            posts CBOR to `/utils/txs/evaluate`;
            `ScalusTxEvaluator.blockfrost(...)` and
            `ScalusTxEvaluator.ogmiosHttp(url)` map endpoint
            responses into typed claim ex-units.
- [x] Submission via Blockfrost `submitTx` (Ogmios as Phase-5+ option)
      ✓ Landed (2026-05-27): builder-produced CBOR is submitted
      through `BlockfrostClient.submitTx`; tests pin Ok/Fail behavior.
- [x] Integration tests against Preprod (CI-gated by env vars)
      ✓ Landed (2026-05-27): `BloxbeanPreprodIntegrationTest`
      builds a balanced claim draft with live Blockfrost Preprod
      protocol params when `X402_SCALUS_PREPROD_IT=true`; submit is
      gated separately by `X402_SCALUS_PREPROD_SUBMIT=true`.

#### Phase 5 — Client-side Scalus-mode wallet

- [x] `Wallets.cardano(hex, network, scalusMode = true)`
      ✓ Landed (2026-05-27): adds selectable Scalus claim-message
      signing while preserving the default description-signing path.
- [x] Structured `ScalusClaimMessage` (domain-separated:
      receiver|amount|validBefore) replaces the description-bytes
      payload for Scalus payments
      ✓ Landed (2026-05-27): client tests assert COSE_Sign1 payload
      bytes and Ed25519 verification.
- [x] `escrowRef` propagated through the payload `nonce` slot
      ✓ Landed (2026-05-27): `PaymentRequirements.scalusEscrowRef`
      maps to `TransferAuthorization.nonce` for Cardano payloads.
- [x] Scalus provider verifies structured claim-message proof before
      settlement ✓ Landed (2026-05-27): `CardanoProvider.Scalus`
      requires escrowRef in `authorization.nonce`, verifies CIP-8
      against the structured claim message, and skips the legacy
      payer-balance check.
- [x] Shared Scalus claim-message codec ✓ Landed (2026-05-27):
      `ScalusClaimMessageCodec` in `x402-core` owns the
      `x402-scalus/v1 || receiver_bytes || amount || validBefore`
      encoding used by both client and facilitator.
- [x] Typed Scalus escrowRef parser ✓ Landed (2026-05-27):
      `ScalusEscrowRef` parses and validates
      `<64-hex-txhash>#<output-index>`; Scalus provider verification
      now rejects malformed nonce-slot escrow refs.
- [x] Round-trip test covering client → validator → claim Tx
      ✓ Landed (2026-05-27): `ScalusRoundTripTest` — 4 tests:
      ok round-trip (verify returns Ok), settle produces a valid
      `ClaimTxPlan`, tampered COSE signature → Fail, malformed
      escrowRef → Fail. Uses real Ed25519 key + mock Blockfrost;
      no live chain dependency.

#### Phase 6 — Deposit ergonomics + example

- [x] `EscrowDeposit.build(payerWallet, req)` helper
      ✓ Landed (2026-05-27): `EscrowDeposit.build(payerPublicKeyHex,
      req, validBeforeSlot, refundAfterSlot, cfg)` derives
      `payerKeyHash` via Blake2b-224 of the payer's Ed25519 public key,
      extracts `receiverHash` from the bech32 receiver address payload,
      computes `claimMessageHash = Blake2b-256(ScalusClaimMessage bytes)`,
      and builds a bloxbean deposit Tx with inline `EscrowDatumOffChain`.
      3 tests: datum fields correct, lovelace output matches amount,
      script address used.
- [x] `examples/x402-cardano-scalus.ssc` — full Preprod walkthrough
      ✓ Landed (2026-05-27): covers all 4 steps — reference-script
      deploy (one-time), payer deposit, Scalus-mode client HTTP request,
      and facilitator server config with `ScalusSettler.preprod`.
- [x] Update Phase 9 follow-up to point here for production flows
      ✓ Landed (2026-05-27): BACKLOG §Phase 9 now references
      `examples/x402-cardano-scalus.ssc` and `EscrowDeposit` for
      end-to-end Preprod + Mainnet Scalus flows.

---

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`docs/blockchain-spi.md`](docs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`docs/wallet-spi.md`](docs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

- [x] `docs/blockchain-spi.md` — chain abstraction, EVM facilitator
      fix path, x402 per-chain migration table
- [x] `docs/wallet-spi.md` — refactored to depend on blockchain-spi
- [x] `AGENTS.md` — spec-driven development workflow
- [x] `MILESTONES.md` — this entry

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

- [x] `crypto-spi` — `CryptoBackend` trait + registry (JVM
      ServiceLoader + explicit register for future Scala.js)
- [x] `crypto-bouncycastle` — JVM default impl (secp256k1 incl.
      ecrecover, ed25519, p256, keccak256, sha2, ripemd160, hmac,
      hkdf, pbkdf2, argon2id, AES-GCM, BIP-32 / SLIP-0010)
- [x] `blockchain-spi` — `ChainAdapter` / `ChainId` (CAIP-2) /
      `AccountId` (CAIP-10) / `Asset` / `TypedData` / `TxIntent`
      (incl. `Deploy`) / `Blockchain.register/lookup`
- [x] `blockchain-evm` read-side — `addressFromPublicKey` (keccak +
      last 20 + EIP-55), `typedDataDigest` (full EIP-712 with nested
      structs), `recoverAddress` (handles v ∈ {0,1,27,28}),
      `tokenBalance` (ERC-20 via `balanceOf`), generic `call` for
      `eth_call`. `buildTransaction` / `broadcast` deferred to
      Phase 2. `Eip3009.usdcTransferWithAuthorization` helper for
      x402's typed-data shape.
- [x] x402 verify fix (covers Base / BaseSepolia / Ethereum /
      Polygon / Arbitrum / Optimism — one adapter, six chain ids):
  - [x] `EvmFacilitator.verify` calls
        `blockchain-evm.recoverAddress` and rejects mismatched
        signatures with descriptive Fail messages
  - [x] `EvmFacilitatorTest` gains "tampered signature → Fail" and
        "signature signed by a different key → Fail" cases (the
        bug-fix this slice closes)
  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
- [x] x402-client shim: `PrivateKeyWallet` now wires
      `RawPrivateKeyVault` + `EoaStrategy` + `EvmChainAdapter` +
      `Eip3009` helper; public API (`Wallet`, `Wallets.privateKey`,
      `Wallets.envKey`) stable; existing 17 tests stay green with
      fixture addresses updated to valid 20-byte hex.
- [x] `wallet-spi` + `wallet-strategy-eoa`: `RawSigner` / `Vault` /
      `AccountStrategy` / `DappConnector` / `AccountManager` traits;
      `EoaStrategy` impl; `RawPrivateKeyVault` test helper.
- [x] Vector tests: RFC-6979 ECDSA + Ed25519 RFC 8032 #1 + EIP-712
      Mail reference + BIP-32 appendix C #1 + SLIP-0010 #1 +
      EIP-55 checksum + RFC 6070 PBKDF2 + AES-GCM tamper detection.
      67 tests across the five new modules.

### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

- [x] `Rlp` encoder (Yellow Paper appendix B): single-byte, short /
      long strings, lists, length-of-length headers.
- [x] `EvmTx` / `EvmSignedTx` — EIP-1559 (type 0x02) envelopes with
      RLP body, sighash, signed raw-hex serialisation. Legacy tx is
      not implemented; every EVM chain x402 currently targets
      supports EIP-1559.
- [x] `EvmChainAdapter` write-side:
  - [x] `buildTransaction(NativeTransfer / TokenTransfer /
        ContractCall / TokenTransferAuthorized / Deploy(CREATE))`
        queries nonce + estimates gas (eth_estimateGas + 10% margin)
        + reads fee market (eth_maxPriorityFeePerGas with
        eth_gasPrice fallback, base fee from latest block).
  - [x] `prepareSigningPayload` / `assembleSignedTransaction`
        normalising v ∈ {0,1,27,28} → yParity.
  - [x] `broadcast` via `eth_sendRawTransaction`.
  - [x] `waitForReceipt` polling with deadline.
  - [x] `predictDeployAddress` for CREATE (
        keccak256(rlp([sender,nonce]))[12..32]). CREATE2 deferred —
        needs a deployer factory contract.
- [x] `ChainAdapter.buildTransaction` SPI gained an explicit
      `sender: String` parameter (needed for nonce + gas estimation).
- [x] `blockchain-evm-abi` sub-module — pure-Scala Solidity ABI v2
      codec: encode/decode for uint*/int*/address/bool/bytesN/
      bytes/string/T[]/T[k]/tuple, function selector helper, event
      topic0 helper. Vector tests against published reference
      encodings (ERC-20 transfer calldata byte-identical, ERC-3009
      selector 0xe3ee160e, head/tail layout for mixed
      static/dynamic tuples).
- [x] `Erc20` typed proxy in `blockchain-evm` — typed reads
      (balanceOf / allowance / decimals / symbol / name) and write
      intents (transfer / approve / transferWithAuthorization).
- [x] `Erc20.Transfer` / `Approval` event decoders. `topic0` for
      Transfer matches the canonical
      0xddf252ad… hash.
- [x] `blockchain-spi.TxReceipt` gained a `logs: Seq[Log]` field
      (default empty, additive); `EvmChainAdapter.getReceipt` parses
      the JSON-RPC logs array into typed `Log` triples.
- [x] x402 settle fix (covers all 6 EVM chains):
  - [x] `EvmFacilitatorConfig.relayerKeyHex` — relayer wallet for
        on-chain settlement
  - [x] `EvmFacilitator.withRelayer(evm, key)` — convenience factory
  - [x] `settleOnChain` path: builds via `Erc20.transferWithAuthorization`,
        signs with EoaStrategy, broadcasts via EvmChainAdapter
  - [x] Custom `settler` escape hatch retained
  - [x] Backwards-compatible default: no relayer + no settler →
        Ok(0x000…000) stub (kept for testnet examples)
  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

- [x] `blockchain-solana` — Ed25519, Base58 addresses, SLIP-0010,
      versioned transactions (v0 + legacy), PDA derivation, SPL token support
- [x] 43 tests — address derivation, tx building, SPL TransferChecked, PDA, balances

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

- [x] `crypto-noble-js` — facade over `@noble/curves` +
      `@noble/hashes` (`@noble/ciphers` deferred to Stage 5 along with
      the encrypted-vault SubtleCrypto adapter)
- [x] Cross-backend conformance: bit-identical outputs on JVM vs JS
      for deterministic algorithms — see `CrossPlatformFixturesTest`
      in `crypto-bouncycastle/src/test/` vs `NobleCryptoBackendTest`
      in `crypto-noble-js/src/test/`
- [x] Resolves Scala.js registry-pattern open question (both SPIs).
      Full per-stage breakdown lives in
      `## Wallet SPI — Scala.js cross-compile / Stage 2` further down
      this file.

### Phase 5 — blockchain-bitcoin ✓ Landed (2026-05-27)

- [x] `blockchain-bitcoin` — secp256k1 ECDSA with RFC-6979 deterministic k,
      sighash variants (SIGHASH_ALL/NONE/SINGLE + ANYONECANPAY),
      BIP-143 SegWit sighash for P2WPKH inputs
- [x] P2WPKH bech32 (`bc1q`/`tb1q`) and P2TR bech32m (`bc1p`/`tb1p`) addresses
- [x] PSBT (BIP-174) builder: addInput/addOutput/sign/finalizeInputs/serialize +
      deserialize; round-trip tested
- [x] BIP-340 Schnorr sign/verify; BIP-341 tapTweakHash + tweakedKey + tweakedPrivateKey
- [x] `ChainId.BitcoinMainnet` / `ChainId.BitcoinTestnet` added to `blockchain-spi`
- [x] `BitcoinChainAdapter` implementing `ChainAdapter` SPI
- [x] 45 tests — sign/verify, sighash, P2WPKH/P2TR addresses, Bech32/Bech32m, PSBT, Schnorr

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

- [x] `blockchain-cardano` — Bech32 codec, CIP-19 enterprise addresses (Blake2b-224),
      CBOR encoder+decoder, CIP-8 COSE_Sign1 signing/verify, CardanoChainAdapter
      (`ChainAdapter` impl), CardanoTxBody CBOR builder, Blockfrost UTxO + submit
- [x] `BlockfrostClient` extended with `getUtxos()` and `submitTx()` (`BlockfrostUtxo` type)
- [x] `ChainId.CardanoMainnet` / `ChainId.CardanoPreprod` added to `blockchain-spi`
- [x] 19 tests — address derivation, balances, tx building, signing, CBOR round-trips, Bech32
- [x] `x402-facilitator-cardano` thin-glue refactor ✓ Landed (2026-05-28) — `CardanoScalusFacilitator.preprod/mainnet` factory wires `ScalusSettler.asConfigHook` into `CardanoFacilitatorConfig.scalusSettle`; 5 new tests in `CardanoScalusFacilitatorTest` + 3 new tests in `CardanoFacilitatorTest` for the delegating settle path.
- [x] `examples/x402-cardano.ssc` — Cardano payment flow example (2026-05-20; wiring + Scalus note updated 2026-05-28)

### Phase 7 — blockchain-cosmos ✓ Landed (2026-05-27)

- [x] `blockchain-cosmos` — secp256k1 / ed25519, StdSignDoc Amino JSON, bech32 with
      configurable HRP (cosmos/osmo/juno); `CosmosChainAdapter` implementing `ChainAdapter` SPI;
      `ChainId.CosmosHub` / `ChainId.Osmosis` / `ChainId.Juno`; `BlockchainProvider` SPI +
      `CosmosBackend` ServiceLoader registration; 41 tests

---

## Wallet SPI — Scala.js cross-compile ✓ Sprint complete (2026-05-20)

Spec in [`docs/wallet-spi-scalajs.md`](docs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

- [x] `project/plugins.sbt` — `sbt-scalajs` 1.20.2,
      `sbt-crossproject` 1.3.2, `sbt-scalajs-crossproject` 1.3.2.
- [x] `crypto-spi` cross-compile (`CrossType.Full`) — shared traits
      / value classes; JVM-only `CryptoBackendDiscovery`
      (ServiceLoader); JS-side `CryptoBackendDiscovery` no-op
      (explicit registration only). Companion `object CryptoBackend`
      lives in shared; cross-platform `register` / `all` / `get`
      surface preserved.
- [x] `blockchain-spi` cross-compile — same pattern: shared traits
      and `object Blockchain`, platform-specific
      `BlockchainDiscovery`.
- [x] `wallet-spi` cross-compile — pure SPI traits in `shared/`;
      `jvm/` and `js/` source dirs empty placeholders. All four
      source files (`RawSigner` / `Vault` / `AccountStrategy` /
      `DappConnector`) physically moved from `src/main/scala/` to
      `shared/src/main/scala/`.
- [x] `CrossCompileSmokeTest` in `wallet-spi/shared/src/test/` —
      8 specs exercising `Curve` / `HashAlgo` / `PublicKey` /
      `ChainId` / `VaultKind` / `UnlockCredential` /
      `AccountDescriptor` round-trips. Runs on JVM and Node.js.
- [x] Build: `sbt walletSpi/test walletSpiJs/test sbt compile`
      all green. No regressions to downstream JVM modules
      (`x402-client`, `walletStrategyEoa`, `mcpWallet`,
      `walletVaultEncrypted`, `walletVaultLedger*`,
      `walletConnect`, `walletConnectorEip1193`, etc. all stay
      compiling and passing tests).

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`docs/wallet-spi.md`](docs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

- [x] `crypto-noble-js` — Scala.js-only sbt project
      (`enablePlugins(ScalaJSPlugin)`, `.dependsOn(cryptoSpiJs)`).
      `ModuleKind.CommonJSModule` so noble v1.x's CJS exports
      resolve at link time.
- [x] `NobleFacades.scala` — `@JSImport` bindings for
      `@noble/curves/{secp256k1, ed25519, p256}` (sign / verify /
      getPublicKey / Signature.fromCompact + recoverPublicKey) and
      `@noble/hashes/{sha256, sha512, sha3.keccak_256, ripemd160,
      hmac, hkdf}`.
- [x] `NobleCryptoBackend` — implements `CryptoBackend` for
      secp256k1 / ed25519 / p256 (sign / verify / derivePublic /
      hash / hmac / hkdf / recoverPublic for secp256k1). Output
      bytes match JVM BouncyCastle bit-for-bit.
- [x] Registration — `Register.install()` for Scala-side init,
      plus `@JSExportTopLevel("registerNobleCryptoBackend")` for
      JS-host init.
- [x] `NobleCryptoBackendTest` (Node.js) — 16 specs:
      empty-string sha256/keccak256/sha512, HMAC-SHA256 RFC 4231 #1,
      HKDF-SHA256 RFC 5869 #1, ed25519 RFC 8032 vector 1 (derive +
      sign empty msg), secp256k1 derive + sign-verify + recover +
      EVM-address round-trip (privkey 0x4646… → 0x9d8a62f656…) +
      tamper rejection, p256 derive + sign-verify, registry
      round-trip via `Register.install`.
- [x] `CrossPlatformFixturesTest` (`crypto-bouncycastle/src/test/`)
      — 7 specs that assert the **same hex strings** the JS test
      asserts; running both sides green proves byte-identical
      cross-platform output.
- [x] npm-deps strategy: no sbt-scalajs-bundler. A
      `crypto-noble-js/package.json` pins `@noble/curves ^1.9.0` +
      `@noble/hashes ^1.8.0`; `npm install --prefix crypto-noble-js`
      is the only setup step before `sbt cryptoNobleJs/test`.
- [x] Build sanity sweep — `cryptoSpi(Js)/test cryptoNobleJs/test
      cryptoBouncycastle/test walletSpi(Js)/test` all green; full
      `sbt compile` clean.

**Not yet implemented on JS** (raise `UnsupportedOperationException`):
HD derivation (`deriveMaster` / `deriveChild`), PBKDF2, Argon2id,
AES-GCM. PBKDF2 / Argon2id / AES-GCM land in Stage 5 (encrypted vault
+ SubtleCrypto adapter). HD derivation lands when the first JS-side
strategy module needs it (Stage 3 or 4).

### Stage 3 — Strategy + connector cross-compile ✓ Landed (2026-05-20)

- [x] `wallet-strategy-eoa` → cross-compile (`CrossType.Full`).  Pure
      SPI usage, no platform-specific glue.  `EoaStrategy` /
      `RawPrivateKeyVault` now live in `wallet-strategy-eoa/shared/`;
      JVM + JS both resolve `CryptoBackend.get()` from the cross-
      compiled registry.  Existing 5 JVM tests preserved; 5 mirrored
      tests run on Scala.js (`AsyncFunSuite` so `Future` round-trips
      work without `Await.result`, which is JVM-only).
- [x] `wallet-connector-eip1193` → cross-compile + `js/` source dir.
      Shared `shared/` holds `Eip1193Provider` / `Eip1193Errors` /
      `Eip6963`; JVM-only test (depends on `blockchain-evm` +
      `cryptoBouncycastle`) stays under `jvm/src/test/`; new
      `js/src/main/.../WindowEthereumProvider.scala` wires the
      provider to the browser via `scalajs-dom` 2.8.0 — `request({
      method, params })` exposed as a JS Promise, EIP-6963
      `announceProvider` / `requestProvider` event flow, and
      `window.ethereum` last-writer-wins binding.  5 Node-side tests
      under `walletConnectorEip1193Js/test` exercise the event flow
      with stubbed `window` / `CustomEvent` globals.
- [x] `wallet-connector-wallet-std` → cross-compile + `js/` source dir.
      `shared/` holds a `WalletStandardConnectorBase` plus a small
      inlined subset of the Solana legacy-message wire protocol
      (`SolanaMessage` / `SolanaInstruction` / `Base58` / `CompactU16`)
      so the same decode / encode code links on both platforms;
      JVM-side concrete `WalletStandardConnector` bridges to the
      existing `scalascript.blockchain.solana.SolanaTx` /
      `SolanaSignedTx` for runtime cast compatibility with
      `SolanaChainAdapter`.  `js/src/main/.../WalletStandardRegister.scala`
      builds the `@wallet-standard/core` `Wallet` JS object and
      registers via both `wallet-standard:register-wallet` DOM
      events and the legacy `window.standard.wallets.registerWallet`
      slot.  Existing 9 JVM tests preserved; 4 new Node-side tests
      under `walletConnectorWalletStdJs/test`.
- [x] Build wiring — three new `*Cross` crossProjects in
      `build.sbt`; legacy `walletStrategyEoa` / `walletConnectorEip1193`
      / `walletConnectorWalletStd` retained as JVM aliases so
      downstream `dependsOn(...)` calls (mcp-wallet, x402-client,
      wallet-strategy-erc4337, …) keep working unchanged.  JS
      targets aggregated at the root.  Scala.js linker test fork
      disabled per module via `jsSettings(Test / fork := false)`.
- [x] Spec update — [`docs/wallet-spi-scalajs.md`](docs/wallet-spi-scalajs.md)
      § Stage 3 marked landed, with the JVM-side
      blockchain-solana bridge documented as the trade-off chosen
      for the Wallet Standard cross-compile (alternative was to fork
      the SolanaChainAdapter types on JS, which would have rippled
      across blockchain-solana consumers).

### Stage 4 — `wallet-strategy-erc4337` cross-compile ✓ Landed (2026-05-20)

- [x] `blockchain-evm-abi` cross-compiled (`CrossType.Full`).  Zero
      `java.*` deps outside the Scala.js stdlib (`java.util.Arrays`,
      `java.io.ByteArrayOutputStream`, `java.lang.StringBuilder`).
      `AbiCodecTest` split into `AbiCodecTestBase` (shared) + per-platform
      concrete classes; JS-side registers `crypto-noble-js`.  19 tests
      run on both platforms.
- [x] `wallet-strategy-erc4337` cross-compiled (`CrossType.Full`):
  - `shared/`: `UserOperation`, `UserOpHash{,V07}`, `EntryPoint`,
    `SmartAccountFactory` (+ `SimpleAccountFactory`),
    `PasskeyAssertion`, `PasskeySigner`, `SimplePasskeyAccountFactory`,
    plus a small inlined `Hex.scala` so shared sources don't reach
    into JVM-only `blockchain-evm`.
  - `jvm/`: `BundlerClient`, `SmartAccountAdapter`, `SmartAccount`
    stay JVM-only — they depend on `EvmChainAdapter` / the HTTP RPC
    in `blockchain-evm`.
  - `js/`: `WebAuthnFacade` + `PasskeySignerJs` — see Phase 6 below.
- [x] `java.math.BigInteger` audit: kept as-is in `PasskeySigner` (P-256
      group-order arithmetic; Scala.js shims `java.math.BigInteger`
      faithfully).  One `salt.bigInteger.toByteArray` replaced with
      `salt.toByteArray` in `SimpleAccountFactory.saltAsBytes`.
- [x] JVM test counts preserved bit-for-bit: 19 (`blockchainEvmAbi`)
      + 43 (`walletStrategyErc4337`) = 62, same as pre-Stage 4.
- [x] JS-side tests added: 19 (`blockchainEvmAbiJs`) + 33
      (`walletStrategyErc4337Js`, of which 6 are the new
      `WebAuthnFacadeTest`).
- [x] Spec update — [`docs/wallet-spi-scalajs.md`](docs/wallet-spi-scalajs.md)
      § Stage 4 marked landed; defers SmartAccountAdapter /
      BundlerClient / SmartAccount cross-compile until
      `blockchain-evm` itself crosses (Fetch RPC + RLP / EIP-1559
      codec on JS).

### Stage 5 — `wallet-vault-encrypted` cross-compile ✓ Landed (2026-05-20)

Stage 5a — light up the deferred KDF + AEAD primitives in
`crypto-noble-js`:

- [x] `pbkdf2` via `@noble/hashes/pbkdf2.pbkdf2(hashFn, password,
      salt, { c, dkLen })` — synchronous, SHA-256 / SHA-512.
      Bit-identical to BouncyCastle `PBKDF2WithHmacSHA{256,512}`.
- [x] `argon2id` via `@noble/hashes/argon2.argon2id(password, salt,
      { t, m, p, dkLen })` — synchronous, RFC 9106 v0x13.
      Bit-identical to BouncyCastle `Argon2BytesGenerator`.
- [x] `aesGcmEncrypt` / `aesGcmDecrypt` via
      `@noble/ciphers/aes.gcm(key, iv, aad?).encrypt / decrypt` —
      synchronous, byte-identical to BouncyCastle GCM.  Chosen over
      WebCrypto SubtleCrypto because the `CryptoBackend` SPI is sync
      and `crypto.subtle.encrypt` returns a Promise (see
      docs/wallet-spi-scalajs.md §5 Stage 5a for the rationale).
- [x] npm deps: pinned `@noble/ciphers ^1.2.1` next to the existing
      `@noble/curves` + `@noble/hashes` in `crypto-noble-js/package.json`.
- [x] Cross-platform fixtures — 9 new shared hex assertions mirrored
      across `crypto-bouncycastle/.../CrossPlatformFixturesTest`
      (16 total, was 7) and `crypto-noble-js/.../NobleCryptoBackendTest`
      (25 total, was 16).  Vectors cover Argon2id at two work factors,
      PBKDF2-SHA256 / SHA-512 at multiple iteration counts, AES-GCM
      encrypt with empty + non-empty AAD, 16 KiB plaintext round-trip,
      and tamper rejection.

Stage 5b — cross-compile `wallet-vault-encrypted`:

- [x] `shared/src/main/scala/scalascript/payments/wallet/vault/encrypted/`:
  - `Bip39.scala` + `Bip39Wordlist.scala` (embedded 2048-word
    English wordlist as a Scala const; old `bip39-english.txt`
    resource removed — Scala.js has no classpath).
  - `VaultFile.scala` — data + JSON codec (`toJson` / `fromJson`).
  - `EncryptedLocalVault.scala` — vault core parameterised over a
    `save: VaultFile => Unit` sink.  All crypto goes through
    `CryptoBackend.get()`.
- [x] `jvm/src/main/scala/.../VaultFileIo.scala` — JVM-only
      `java.nio.file.Path` read / write.
- [x] `jvm/src/main/scala/.../EncryptedLocalVaultFs.scala` —
      JVM-only `Path`-based `create` / `load` / `generate` that
      wraps the shared core with file I/O.  Preserves the
      pre-Stage-5 JVM API surface — every downstream caller that
      `dependsOn(walletVaultEncrypted)` keeps compiling unchanged
      (mcp-wallet, x402-client, wallet-vault-ledger-*, etc.).
- [x] `java.util.UUID.randomUUID()` replaced with a Scala.js-
      compatible 16-byte secure-random + RFC 4122 v4 bit-twiddling
      helper (the JVM `UUID.randomUUID` reaches into
      `java.security.SecureRandom`, not shimmed on Scala.js).
- [x] Build: `walletVaultEncryptedCross = crossProject(...)` with
      `.jvmConfigure(_.withId("walletVaultEncrypted"))` preserving
      the pre-Stage-5 project id; `walletVaultEncryptedJs` is the
      JS-side artefact.  JS test scope depends on `cryptoNobleJs`
      for the noble backend; module kind = CommonJS so the noble
      `require()` exports link.

Stage 5c — cross-platform parity tests:

- [x] `shared/src/test/scala/.../Bip39TestBase.scala` — 14 specs
      (wordlist sanity + entropy↔mnemonic + checksum + Trezor seed
      vector).  JVM concrete class uses `ServiceLoader`-registered
      BouncyCastle; JS concrete class registers noble in `beforeAll`.
- [x] `shared/src/test/scala/.../VaultCrossPlatformTestBase.scala`
      — synchronous 2-test fixture: Trezor BIP-39 seed vector +
      fixed Argon2id+AES-GCM ciphertext that asserts byte-identical
      output across JVM + JS.  Async sibling (1 test) does the
      full create → JSON round-trip → reopen → unlock flow.
- [x] `jvm/src/test/scala/.../EncryptedLocalVaultTest.scala` — 13
      file-I/O-driven tests against `EncryptedLocalVaultFs`;
      same coverage as pre-Stage-5.

Test count parity:

- [x] `walletVaultEncrypted` (JVM): pre 26 (13 + 13) → post 30 — the
      original 26 preserved bit-for-bit; +1 wordlist sanity, +2
      cross-platform vector, +1 async vault round-trip.
- [x] `walletVaultEncryptedJs`: 17 tests (14 Bip39 + 2 vector + 1
      async).
- [x] `cryptoNobleJs`: pre 16 → post 25 (9 new KDF + AEAD vectors).
- [x] `cryptoBouncycastle`: pre 7 fixture tests → post 16 (9 new,
      mirroring the JS side byte-for-byte).
- [x] `sbt compile` at root: green; no downstream module breakage.

Deferred / follow-ups:

- [x] **JS-side persistence layer** — landed 2026-05-27.
      `EncryptedLocalVaultJs` wires the shared vault core to
      `VaultFileStore` implementations for IndexedDB, localStorage, and
      in-memory Node/test fallback.  The durable value remains the shared
      `VaultFile.toJson` format; no crypto or file-format fork.

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6a — extend `CryptoBackend` SPI with the primitives WC needs
(additive only — no existing-method breakage):

- [x] `chacha20Poly1305Encrypt(key32, nonce12, plaintext, aad)` /
      `chacha20Poly1305Decrypt(...)` — `ciphertext || 16B tag`
      layout.  Decrypt throws the new shared
      `CryptoIntegrityException` on Poly1305 tag mismatch so callers
      pattern-match without depending on `javax.crypto.AEADBadTagException`.
- [x] `x25519GenerateKeypair()` / `x25519PublicKeyFromPrivate(priv32)`
      / `x25519DeriveSharedSecret(selfPriv, peerPub)` — 32-byte raw
      priv / pub both sides, raw ECDH output that the existing
      `hkdf` primitive consumes.
- [x] Default trait methods throw `UnsupportedOperationException` so
      third-party backends keep compiling; both
      `BouncyCastleBackend` (JCE `ChaCha20-Poly1305` provider + BC
      `X25519Agreement`) and `NobleCryptoBackend`
      (`@noble/ciphers/chacha.chacha20poly1305` +
      `@noble/curves/ed25519.x25519`) implement them.
- [x] 8 new cross-platform parity vectors per side
      (`CrossPlatformFixturesTest` + `NobleCryptoBackendTest`) —
      hex bytes stay byte-identical between JVM and JS:
      `cryptoBouncycastle/test` 24 → 24 (CrossPlatform 16→24);
      `cryptoNobleJs/test` 25 → 33.

Stage 6b — refactor `wallet-connect` to route through the SPI:

- [x] `RelayJwt`: `Ed25519Signer` → `CryptoBackend.get().sign(Curve.Ed25519, ...)`.
- [x] `WcEnvelope`: JCE `Cipher.getInstance("ChaCha20-Poly1305")` →
      `CryptoBackend.get().chacha20Poly1305Encrypt/Decrypt`.  Re-exports
      `CryptoIntegrityException` as `WcEnvelope.AeadBadTagException`.
- [x] `WcKeyAgreement`: BC `X25519Agreement` + `HKDFBytesGenerator` →
      `CryptoBackend.get().x25519DeriveSharedSecret` +
      `CryptoBackend.get().hkdf(... HashAlgo.Sha256)` + the existing
      `hash(HashAlgo.Sha256, symKey)` for topic derivation.
- [x] Post-refactor the three files have **zero** `java.*` /
      `javax.*` / `org.bouncycastle.*` direct references.

Stage 6c — `CrossType.Full` source split:

- [x] `shared/src/main/scala/scalascript/payments/wallet/walletconnect/`:
      `WcTypes`, `WcRelayTransport` (trait), `WcSessionStore` (now
      `mutable.HashMap` + `synchronized` — TrieMap isn't on Scala.js),
      `RelayJsonRpc`, `WsChannel` (trait), `RelayJwt`, `WcEnvelope`,
      `WcKeyAgreement`, `WalletConnectConnector`, plus the new
      `RelayTransportBase` (Option A: the demux + JSON-RPC core lives
      in shared, both platform transports are thin subclasses).
- [x] `jvm/src/main/scala/.../`: `JdkWsChannel`
      (`java.net.http.WebSocket`) + `JvmRelayTransport` (5-line
      `extends RelayTransportBase` — legacy entry point preserved
      for downstream JVM callers).
- [x] `js/src/main/scala/.../`: `BrowserWsChannel` wraps the browser's
      native `WebSocket` global via an injectable `wsConstructor`
      parameter (tests stub the constructor — no `globalThis.WebSocket`
      surgery needed) + `JsRelayTransport` mirrors `JvmRelayTransport`.

Stage 6d — tests:

- [x] JVM `walletConnect/test`: **49 tests across 7 suites**, same
      count as pre-Stage 6.  JVM-only suites become thin sub-classes
      of `*TestBase` specs in `shared/src/test/`.
- [x] JS `walletConnectJs/test`: **54 tests across 8 suites** — same
      49 shared specs + 5 new `BrowserWsChannelTest` specs against a
      mock `BrowserWebSocket` (connect → onopen, onText round-trip,
      send-before-connect failure, send-after-connect forwarding,
      idempotent close).
- [x] `RelayTransportTestBase` parameterised on a `mkTransport`
      factory so both `JvmRelayTransportTest` and
      `JsRelayTransportTest` run the same 7 protocol-level
      assertions against their respective platform transport.

Stage 6e — build wiring:

- [x] `walletConnectCross = crossProject(JVMPlatform, JSPlatform)
      .crossType(Full).in(file("wallet-connect"))` with
      `walletSpiCross`, `blockchainSpiCross`, `cryptoSpiCross`
      dependencies; JVM extra `cryptoBouncycastle % Test`; JS extra
      `cryptoNobleJs % Test`.
- [x] Legacy `walletConnect` alias = `walletConnectCross.jvm` — every
      downstream JVM consumer keeps compiling unchanged.
- [x] `walletConnectJs` added to the root aggregator.
- [x] `jsSettings(Test / fork := false,
      scalaJSLinkerConfig ~= _.withModuleKind(CommonJSModule))` to
      match `crypto-noble-js`.
- [x] `wallet-connect/package.json` mirrors
      `crypto-noble-js/package.json` so the Node test runner walks
      up to find `@noble/ciphers` / `@noble/curves` / `@noble/hashes`.

Stage 6 — deferred / follow-ups:

- [ ] **Real browser-WebSocket integration testing** — JS tests
      currently mock `BrowserWebSocket` (Node has no native
      `WebSocket` in the test runtime).  Live integration against
      `wss://relay.walletconnect.com` lands in the future PWA-wallet
      sprint that surfaces WC v2 in an actual browser.

Sprint closure: every wallet-spi-track module that has a JS-relevant
surface now cross-compiles JVM + Scala.js.  All future
`CryptoBackend` implementations are mandated to implement
ChaCha20-Poly1305, X25519, and the Stage 5 AEAD / KDF set in
addition to the original signing / hash / KDF surface — see
[`docs/wallet-spi-scalajs.md`](docs/wallet-spi-scalajs.md) §6 for
the full SPI checklist a new backend has to cover.

---

## Wallet SPI — key management + dApp connectivity

Spec in [`docs/wallet-spi.md`](docs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

- [x] `wallet-spi` — `RawSigner` / `Vault` / `AccountStrategy` /
      `DappConnector` / `AccountManager` (JVM only this phase;
      Scala.js cross-compile follows in Phase 3 of blockchain-spi)
- [x] `wallet-strategy-eoa` — `EoaStrategy` impl
- [x] In-memory `RawPrivateKeyVault` test helper (lives in
      `wallet-strategy-eoa` rather than `wallet-spi` since it needs
      `CryptoBackend.get()` to derive public keys)
- [x] x402-client refactor: `PrivateKeyWallet` is now a thin shim
      that wires `RawPrivateKeyVault` + `EoaStrategy` +
      `EvmChainAdapter` + `Eip3009` helper. Public API stable;
      existing `X402ClientTest` stays green with real signatures
      (fake addresses like `"0xpayTo"` replaced with valid 20-byte
      hex since real ABI encoding rejects malformed input).

### Phase 2 — Encrypted Vault ✓ Landed JVM + Scala.js core (2026-05-20)

- [x] `wallet-vault-encrypted` — cross-compiled (JVM + Scala.js) as
      of 2026-05-20; see "Wallet SPI — Scala.js cross-compile / Stage 5"
      further up.
- [x] BIP-39 mnemonic generation / restore (24-word default)
- [x] Argon2id → AES-GCM(seed) password unlock
- [x] `wallet-vault-encrypted-jvm` — filesystem (`VaultFile` /
      `EncryptedLocalVaultFs`) — `java.nio.file.Path`-based read /
      write.
- [x] `wallet-vault-encrypted-js` — IndexedDB persistence helper
      (2026-05-27).  `EncryptedLocalVaultJs` defaults to IndexedDB in
      browsers, falls back to localStorage, then to an in-memory store for
      Node/tests.  `EncryptedLocalVaultJsTest` covers create/load/unlock,
      account metadata persistence, and delete.

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

- [x] `wallet-connector-eip1193` — `Eip1193Provider` translator (JVM;
      JS `window.ethereum` injection wired in next iteration once the
      Scala.js cross-compile is on)
- [x] EIP-6963 multi-injected-provider discovery types
- [x] Translates `eth_*` JSON-RPC → `AccountManager.request`

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [x] `wallet-connect` — protocol shape + scaffolded
      `WalletConnectConnector` (JVM)
- [x] Multi-chain via CAIP-2 namespaces (`WcNamespace`)
- [x] Transport-layer cryptography (2026-05-20):
  - [x] `RelayJwt` — EdDSA(ed25519) JWT signing + did:key encoding
        (W3C `z6Mk…` multicodec prefix); JWT payload carries
        `iss`/`aud`/`iat`/`exp`/`sub`.
  - [x] `WcEnvelope` — Type 0 + Type 1 ChaCha20-Poly1305 envelopes
        (JCE `ChaCha20-Poly1305`), base64 transport framing.
  - [x] `WcKeyAgreement` — X25519 keypair / ECDH / HKDF-SHA256 →
        session symKey / topic = sha256(symKey); `wc:` pairing-URI
        parser (validates topic = sha256(symKey)).
- [x] JVM transport composition — `JvmRelayTransport` wiring the
      primitives above to JDK `java.net.http.WebSocket` and the
      `irn_publish` / `irn_subscribe` / `irn_subscription`
      JSON-RPC frames (2026-05-20):
  - [x] `WcSessionStore` — thread-safe in-memory `topic → (symKey,
        peerPub)` map used by the transport to look up sealing keys
        per topic.
  - [x] `WsChannel` trait + `JdkWsChannel` impl over
        `java.net.http.WebSocket` (partial-frame accumulator).
  - [x] `RelayJsonRpc` — `irn_publish` / `irn_subscribe` / `irn_unsubscribe`
        builders, `irn_subscription` parser, monotonic id allocator.
  - [x] `JvmRelayTransport` — composes the primitives + channel +
        store; ApproveSession sealed as Type-1 (ships responder's
        X25519 pubkey, derives session symKey, registers session
        topic = sha256(symKey)); other outbound variants seal Type-0.
        Inbound demux: `irn_subscription` → envelope decrypt →
        inner JSON-RPC method dispatch
        (`wc_sessionPropose` / `Request` / `Delete` / `Update` /
        `Ping`); unknown topics + unhandled methods are dropped.
  - [x] `WcOutbound` ADT carries an explicit `topic` field on every
        variant — the connector knows which topic each outbound
        belongs to.
- [x] JS-side relay transport — `wallet-connect` now cross-compiles
      (2026-05-20).  `BrowserWsChannel` wraps the browser's native
      `WebSocket` global and `JsRelayTransport` reuses the same
      `RelayTransportBase` the JVM-side `JvmRelayTransport` builds on,
      so the JS variant is a real Scala-side WC v2 transport rather
      than a `@walletconnect/sign-client` facade.  See "Wallet SPI —
      Scala.js cross-compile / Stage 6" further up.
- [ ] WC project-ID open question — still pending; the transport
      accepts a `projectId` argument on both platforms but CI does
      not yet provision one. To resolve before first production
      deployment.

### Phase 5 — Solana DappConnector ✓ Landed (2026-05-27)

- [x] `wallet-connector-wallet-std` — Solana Wallet Standard request
      surface (`standard:connect` / `standard:disconnect`,
      `solana:signMessage` / `signTransaction` / `signAndSendTransaction`,
      `wallet:setActiveChain`). Sui-side features deferred.
- [x] Blockchain-spi Phase 3 dependency satisfied by the existing
      `SolanaChainAdapter`.
- [x] Scala.js `registerWallet` integration with
      `window.standard.wallets` — `WalletStandardJs.register(info, connector)`,
      `WalletInfo` JS-native trait, `StandardWalletConnectorJs` feature-map
      bridge; 6 Node.js tests via `global.window` stub. Landed 2026-05-27.

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

- [x] `wallet-strategy-erc4337` — `SmartAccount.wrap(...)`
      convenience pairing
- [x] UserOp construction + signing over `userOpHash` (EntryPoint v0.6)
- [x] Bundler client (`BundlerClient` — send / estimate / receipt /
      supportedEntryPoints; both flat and `receipt:{}`-envelope reply
      shapes accepted)
- [x] Counterfactual CREATE2 address derivation (`SimpleAccountFactory`)
- [x] EntryPoint v0.7 PackedUserOperation — `UserOpHashV07`
      (compressed accountGasLimits / gasFees), version-aware
      `BundlerClient` (`BundlerClient.v07(...)`), wire-side
      factory / factoryData + paymaster split in JSON. The on-chain
      hash composition (`keccak(packed)`, then
      `keccak(encode(., ep, cid))`) is shared with v0.6.
- [x] **JVM Passkey owner via WebAuthn (P-256).** ✅ **LANDED** (2026-05-20).
      `PasskeyAssertion` (clientDataJSON challenge extraction +
      WebAuthn `sha256(authData || sha256(cdJson))` digest), `PasskeySigner`
      (`RawSigner` curve = P256; delegates the actual `navigator.credentials.get`
      assertion to a host callback so JVM tests inject a deterministic
      signer and JS will wire `navigator.credentials.get` later), DER →
      raw + low-s normalisation, ABI-encoded signature blob matching
      Coinbase Smart Wallet `WebAuthn.sol` / ERC-7836
      `(bytes authenticatorData, bytes clientDataJSON, bytes32 r,
       bytes32 s, uint256 challengeIndex, uint256 typeIndex)`.
      `SimplePasskeyAccountFactory` mirrors `SimpleAccountFactory`
      shape with `createAccount(uint256 x, uint256 y, uint256 salt)`
      init-code. 16 new tests, 43 total in `walletStrategyErc4337`.
- [x] **JS-side WebAuthn facade** (Scala.js): ✅ **LANDED**
      (2026-05-20) alongside the wallet-spi-scalajs § Stage 4
      cross-compile of `wallet-strategy-erc4337`.
      `wallet-strategy-erc4337/js/WebAuthnFacade.scala` wraps
      `navigator.credentials.get(...)` and fits the
      `assertChallenge: Array[Byte] => Future[WebAuthnAssertion]`
      callback shape; `PasskeySignerJs.fromBrowserPasskey(...)` is the
      browser-side convenience constructor that wires it into the
      cross-compiled `PasskeySigner`.  6 Node-side tests stub
      `navigator.credentials.get` (via `Object.defineProperty` —
      Node 20+ marks `navigator` as a read-only getter) and verify
      challenge byte-identity, the options dict shape (`rpId`,
      `userVerification:"required"`, `allowCredentials` transform),
      and ArrayBuffer→`Array[Byte]` round-trips for
      `authenticatorData` / `clientDataJSON` / `signature`.

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`docs/wallet-spi.md`](docs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

- [x] `wallet-vault-ledger` — shared types (JVM, cross-compile-ready
      sources): `LedgerTransport` trait, `Apdu` codec + chunked send,
      `AppSwitchRequired` error, `Dashboard.getAppName` probe,
      `Bip32Path` encoder, `CurveAppRouting` curve→app table.
      27 tests across `ApduTest` / `Bip32PathTest` /
      `CurveAppRoutingTest` / `DashboardTest`.
- [x] `wallet-vault-ledger-jvm` — `hid4java` transport with the
      Ledger HID framing (5-byte header + 64-byte frames + first-
      frame length prefix + CID 0x0101). 10 framing round-trip
      tests; the actual `Hid4JavaTransport` device class is wired
      but exercised manually in dev (no device in CI).
- [x] Ethereum-app signer: `wallet-vault-ledger-ethereum`
      (CLA=0xE0). `EthereumApp` wraps GET_PUBLIC_KEY (INS=0x02),
      SIGN_TRANSACTION (0x04 chunked), SIGN_PERSONAL_MESSAGE
      (0x08 chunked), SIGN_EIP712_HASHED (0x0C).
      `LedgerEthereumVault` implements `Vault`; probes `getAppName`
      before signing → `AppSwitchRequired` on mismatch.
      `LedgerEthereumSigner` extends `RawSigner`, routes
      `hash=Keccak256` to SIGN_TRANSACTION and `hash=None` (64-B
      `[domain||msgHash]`) to SIGN_EIP712. Covers all 6 EVM x402
      chains via the single Ethereum app. 13 tests.
- [x] `wallet-vault-ledger-js` — WebHID transport (Scala.js).
      ✓ Landed (2026-05-27). Adds `HidTransport` /
      `WebHidLedgerTransport` over `navigator.hid`, Ledger HID
      64-byte APDU framing, connect/disconnect lifecycle,
      `LedgerVault` for browser hardware wallets, Ethereum app
      routing through the shared EVM signer, and 13 mocked WebHID
      Scala.js tests. The JS slice includes a Cardano CIP-8 helper;
      the standalone JVM Cardano Ledger vault remains tracked below.
- [x] Solana-app signer: `wallet-vault-ledger-solana` — ed25519 + Solana
      sign-doc framing; CLA=0xE0, INS=0x04 SIGN_TRANSACTION / INS=0x07
      SIGN_OFFCHAIN_MESSAGE; default path `m/44'/501'/0'/0'`; Base58
      pubkey display; 11 tests. ✓ Landed 2026-05-27 (`94520b7c`).
- [x] Bitcoin-app signer: `wallet-vault-ledger-bitcoin` — PSBT-aware;
      CLA=0xE1 (new Bitcoin app protocol v2+); INS=0x04 SIGN_PSBT;
      LedgerBitcoinVault wraps PsbtBuilder from blockchain-bitcoin;
      14 tests. ✓ Landed 2026-05-27 (`14572bd3`).
- [x] Cardano-app signer: `wallet-vault-ledger-cardano` — CIP-8 framing;
      CLA=0xD7, INS=0x10 GET_EXTENDED_PUBLIC_KEY, INS=0x21 SIGN_TX;
      CIP-8 COSE_Sign1 Sig_Structure (hand-rolled CBOR); 11 tests.
      ✓ Landed 2026-05-27 (`19ad76cd`).
- [ ] Optional `wallet-vault-ledger-bluetooth-js` — WebBLE for
      Nano X / Stax. Deferred.
- [x] `wallet-vault-trezor` — `TrezorEthVault` + `TrezorBridge` + `TrezorSession` + `MockTrezorBridge`; 29 tests.
      ✓ Landed 2026-05-28.

### Phase 8 — MPC Vault

- [x] `wallet-vault-mpc` — HTTP client to external MPC provider
  - [x] `RemoteSigningClient` trait — `listAccounts` / `sign` /
        `health`, vendor-agnostic abstraction over the provider-side
        signing surface
  - [x] `McpVault` — SPI-conforming `Vault`; delegates to a
        `RemoteSigningClient`; lock = forget cached token, unlock =
        `health()` probe
  - [x] `McpRemoteSigner` — `RawSigner` impl that round-trips every
        signature through `RemoteSigningClient.sign`
  - [x] `HttpRemoteSigningClient` — reference JSON-over-HTTPS impl
        modelled on a Fireblocks-shaped REST surface; supports both
        synchronous (200 + completed signature) and asynchronous
        (202 + `operationId` → poll `/v1/operations/{id}`) flavours;
        bearer-token auth, configurable poll interval / max-attempts /
        request timeout; subclass hook (`decorateRequest`) for
        provider-specific auth decoration
  - [x] `MpcSerialization` — base64 codec + curve/hash naming + JSON
        marshalling for sign request, account list, operation status
- [x] `wallet-vault-mpc-fireblocks` — Fireblocks provider adapter.
      ✓ Landed 2026-05-28. Adds a dedicated sbt subproject,
      `FireblocksRemoteSigningClient`, RS256 JWT auth with `X-API-Key`,
      RAW transaction request generation, `/v1/transactions/{id}` polling,
      `FireblocksVault`, `FireblocksPlugin` ServiceLoader discovery,
      `docs/wallet-vault-mpc.md`, `examples/wallet-mpc-fireblocks.ssc`,
      and 16 mock-HTTP/JWT/wire tests.
- [ ] Remaining curve/vendor-specific MPC protocol modules — **deferred**.
      Coinbase MPC, ZenGo/Web3Auth/Lit Protocol, and the FROST-Ed25519
      family ship their own SDK semantics. Plan is one provider-specific
      adapter module per vendor (for example `wallet-vault-mpc-coinbase`)
      that subclasses or composes the shared `HttpRemoteSigningClient` and
      bundles vendor-mandated request decoration (HMAC/JWT signing,
      idempotency keys, polling cadence) — kept out of `wallet-vault-mpc`
      so the trait surface stays vendor-neutral.

---

## MCP × x402 × Wallet — agentic payments

Spec in [`docs/mcp-x402-wallet.md`](docs/mcp-x402-wallet.md). Layers
three integrations on top of `mcp-common`, `wallet-spi`,
`blockchain-spi`, and `x402-*`:

1. `mcp-wallet-server` — exposes `wallet-spi` operations as MCP
   tools (sign / send / balance / accounts) under a host-controlled
   `Policy` with `elicitation`-based consent.
2. `mcp-x402` — lifts HTTP 402 into MCP: a new `-32402` error code
   carrying `PaymentRequirements`, `_meta.x402.payment` field on
   `tools/call` params, `X402AutoPay` middleware on the client.
3. Composed flow: agent connects to a local stdio wallet server +
   a remote priced server; on `-32402` the client middleware signs
   via the wallet server and retries, transparently to the agent.

Depends on `mcp-common` (v1.17 — already largely landed),
`wallet-spi` Phase 1, `blockchain-spi` Phase 1, and `x402-core` /
`x402-server`.

### Phase 0 — Spec ✓ Landed (2026-05-19)

- [x] `docs/mcp-x402-wallet.md` — architecture, tools, policy
      model, error-code allocation, phase plan

### Phase 1 — mcp-wallet read-only ✓ Landed (2026-05-19)

`Policy` + `ConfirmationMode` types; `McpWalletServer.installOn(builder)` mounts
read-only tools (`wallet.listAccounts`, `wallet.getAddress`, `wallet.getBalance`)
and `wallet://accounts` resource. Policy filter controls exposed tools and chains.
8 tests covering listing, address lookup, balance (native + ERC-20), policy gate.

### Phase 2 — mcp-wallet signing with elicitation ✓ Landed (2026-05-19)

`wallet.signMessage`, `wallet.signTypedData`, `wallet.payX402` tools — all gated
on `ConfirmationMode`. `ElicitationPerCall` blocks until `ElicitationHandler`
approves; `Implicit` auto-approves for session keys. `AuditLog` records every op
(timestamp, tool, policy decision, sig hash); exposed via `wallet://audit` resource.
7 tests: approved sign, rejected sign, fail-closed without handler, payX402 payload,
maxPerCall enforcement, audit resource.

### Phase 3 — x402 over MCP server side ✓ Landed (2026-05-19)

`mcp-x402` module: `Mcp402Protocol` constants (`-32402`, `_meta.x402.*`);
`Mcp402Dispatcher.dispatchTool/Resource/Prompt` — emits `-32402` on unpaid calls,
verifies `_meta.x402.payment` via `Facilitator` before executing. `ToolPrice` /
`PaymentScope` additive fields on registrations. 7 tests: unpaid → -32402, valid
payment → Right, facilitator Fail, malformed base64, session-scoped oneShot=false,
resource/prompt kind labels.

### Phase 4 — x402 over MCP client side ✓ Landed (2026-05-19)

`X402AutoPay` middleware for `McpClientCore`: on `-32402` parses `PaymentRequirements`,
calls `PaymentSigner.sign`, retries with `_meta.x402.payment`. `maxAmount` ceiling
enforced independently of wallet policy. `onCharge` hook for observability.
`PaymentRequiredException.tryParse` for typed error handling. 7 tests: round-trip,
maxAmount rejection, charge hook, signer returning None, non-payment passthrough,
exception parse round-trip.

### Phase 5 — Composed agent flow ✓ Landed (2026-05-19)

`wallet.sendTransaction` tool (build + sign + broadcast, gated on policy + elicitation).
`ConfirmationMode.ElicitationCached(ttlSec)` caches per `(tool, chainId)` within TTL;
rejection not cached. `McpWalletPaymentSigner` bridges `mcp-wallet-server` → `X402AutoPay`
for end-to-end agent flow. `PaidAgentCompositionTest`: agent → autopay → wallet →
priced server full round-trip in-process. 6 tests.

### Phase 6 — Resources & prompts pricing ✓ Landed (2026-05-19)

`Mcp402Dispatcher.dispatchResource` / `dispatchPrompt` — same `-32402` / payment
verification pattern as tools; `kind` field (`tool`/`resource`/`prompt`) in requirements.
`X402AutoPay` handles resource/prompt 402s via the same `PaymentSigner` path.
7 tests covering resource, prompt, and tool dispatch + client round-trip.

### Phase 7 — OAuth-aware policy resolution ✓ Landed (2026-05-20)

`McpWalletAuth` thread-local carries JWT claims from the OAuth middleware into
tool handlers. `PolicyProvider` trait — `Static(policy)` (default) and
`FromAuth(resolver, fallback)` (per-request, reads current claims).
`McpWalletServer.policyProviderOverride` enables per-request scope narrowing:
`wallet:read` scope gates read-only tools; `wallet:sign` scope gates signing;
unrecognised scopes fall back to read-only policy. `maxPerCall` overridable
per-scope. 9 tests covering FromAuth routing, scope gates, fallback, thread-local
lifecycle, scoped budgets.

### Phase 8 — Stream payments via MCP ✓ Landed (2026-05-20)

`Pricing.Stream` variant in `ToolPrice`; `Mcp402Dispatcher` emits `scheme=stream`
in requirements and accepts stream-scheme payments. `Mcp402Protocol.streamChargeMeta`
/ `parseStreamCharge` helpers; `X402AutoPay.onStreamCharge` hook for running-total
accumulation. 7 tests: dispatch with stream pricing, stream payment acceptance,
meta round-trip, AutoPay stream hook, running-total accumulation.

## Micropayment Platform — channel-based fee amortisation for microtransactions

Spec in [`docs/micropayment-spi.md`](docs/micropayment-spi.md). Sits above `blockchain-spi` and `wallet-spi`; peer of x402. Five strategies: ThresholdBatching (x402 Facilitator backend), EVM StateChannel, Cardano HydraHead, Probabilistic lottery, and L2Native pass-through.

### Phase 1 — `micropayment-spi` core traits ✓ Landed (2026-05-19)

`ChannelId`, `ChannelConfig`, `ChannelState`, `PaymentReceipt`, `SettlementResult`,
`MicropaymentChannel`, `SettlementPolicy` combinators, `ChannelKind`, `ChannelProvider`.

### Phase 2 — ThresholdBatching + server middleware + HTTP client ✓ Landed (2026-05-19)

EIP-712 cumulative-receipt signing, `ReceiptStore`, `withMicropayment` server middleware,
`ReceiptCodec`, `MicropaymentHttpClient`. 9 integration tests.

### Phase 3 — Probabilistic lottery (`micropayment-probabilistic`) ✓ Landed (2026-05-19)

Pure `LotteryMath` (win-condition, HMAC-seeded salt, commitment), `LotteryTicket`,
`LotteryReveal`, `WinningTicketStore`, `ProbabilisticChannel`, `ProbabilisticProvider`.
11 math test vectors + 10 integration tests. Settlement accumulates won amounts
in-memory; on-chain batch redemption deferred to Phase 4.

### Phase 6 — Multi-chain ThresholdBatching via `blockchain-spi` ChainAdapter ✓ Landed (2026-05-19)

Removed x402 `Facilitator`/`NonceStore` from `ThresholdChannel`; settlement now uses
`chain.buildTransaction(TxIntent.TokenTransferAuthorized)` + `strategy.signTransaction` +
`chain.broadcast`. Removed `x402Core` build dependency; unified upickle to 3.3.1.

### Phase 4 — EVM state channels (`micropayment-channel-evm`) ✓ Landed (2026-05-20)

`PaymentChannel` Solidity contract (`submitFinalState`, `challenge`, `cooperativeClose`, `finalise`).
`StateChannel` — payer-side `pay()` (EIP-712 personal_sign), payee-side `receive()` (sig recovery),
`settle()` / `challenge()` / `cooperativeClose()`. ABI encoding via `PaymentChannelAbi`.
`StateChannelProvider` — deploys contract via `TxIntent.Deploy` + `predictDeployAddress`, or
wraps existing contract. 16 tests (lifecycle, cooperative close, dispute path, provider, ABI helpers).

### Phase 5 — Cardano Hydra heads (`micropayment-hydra`) ✓ Landed (2026-05-20)

`HydraNodeClient` trait — Java 11 `java.net.http.WebSocket` live impl + in-process `StubHydraNodeClient`.
`HydraMessage` — `HydraServerMsg` (HeadIsOpen, TxValid, TxInvalid, HeadIsClosed, HeadIsFinalized, …)
+ `HydraClientMsg` (NewTx, Close, Fanout) with JSON parsing/serialisation.
`HydraChannel` — payer-side `pay()` (NewTx → waits TxValid via Promise), payee-side `receive()`
(waits TxValid for txId from receipt), `settle()` (Close → HeadIsClosed → Fanout → HeadIsFinalized).
`HydraHeadProvider` — opens channel against a connected Hydra node; `stub()` helper for tests.
18 tests (pay/receive lifecycle, TxInvalid error, settle path, threshold policy, provider, message parsing).

---

## v1.30 — `@side=client|server` for SQL blocks in full-stack modules

**Status: complete (2026-05-21). Phases 1–4 all landed.**

In `frontend:` modules a `sql` block now carries an optional `@side`
attribute that controls whether the block runs in the server bundle or
the browser bundle:

```sql @db=local @side=client
SELECT * FROM cache WHERE key = ${k}
```

Without the attribute the block defaults to `@side=server` (existing
behaviour unchanged).

### Phase 1 — Spec ✓ Landed (2026-05-21)

- [x] `SPEC.md` §3.3.1: `@side` attribute table, allowed schemes per
      side, `UnsupportedDbUrl` diagnostic for wrong-side schemes,
      backward-compat note (`@side=server` default).

### Phase 2 — Milestone ✓ Landed (2026-05-21)

- [x] This `MILESTONES.md` v1.30 entry.

### Phase 3 — IR + parser ✓ Landed (2026-05-21)

- [x] `SqlBlock.side: Side` (`Side.Server` default / `Side.Client`)
      added to the IR node.
- [x] `Normalize` reads `@side` fence attribute → `SqlBlock.side`.
- [x] `CapabilityCheck`: new `UnsupportedClientSideDbUrl` diagnostic
      when `@side=client` references a non-JS-supported URL scheme.

### Phase 4 — Codegen ✓ Landed (2026-05-21)

- [x] `JvmGen`: `@side=client` blocks skipped from server Scala; collected
      into `_ssc_client_sql_js` (sql-runtime.mjs inlined + async IIFE with
      registry + block calls) appended to `app.js` by
      `_ssc_ui_emit_to_dir` / `_ssc_ui_emit_to_tempdir`.
- [x] `JsGen`: `@side=server` blocks skipped (not emitted into the JS bundle).
      `hasSqlBlocks` also excludes server-only blocks so the SQL preamble is
      not injected when all sql blocks are `@side=server`.
- [x] `SqlRuntimeJs` namespace in JsGen updated to include `SqliteWasmProvider`.
- [x] `build.sbt`: `backendJvm` now depends on `backendSqlRuntimeJs` so
      `SqlRuntimeJsEmit.runtimeSource` is available at JvmGen's own compile
      time (the mjs source is inlined into the emitted `.sc` string, no extra
      `//> using lib` needed at run time).
- [x] End-to-end example: `examples/frontend/local-first/local-first.ssc` —
      `@side=server` SQLite REST API + `@side=client` sqlite-opfs local cache.

---

## OpenAPI 3.1

**Spec:** [`docs/openapi.md`](docs/openapi.md)

Phase 1 (interpreter `/_openapi.json` + `/_swagger`) landed as part of HTTP infrastructure.
Phases 2–5 are planned.

- [x] **openapi-p1** — Interpreter `/_openapi.json` + `/_swagger`: `OpenApiRuntime` auto-registered
  when `serve()` / `serveAsync()` is called; path-param conversion; handler introspection;
  Swagger UI CDN page; 12 tests in `OpenApiRuntimeTest`. Landed as part of HTTP infrastructure.

- [ ] **openapi-p2** — JVM codegen + shared generator + response schema:
  extract `OpenApiGenerator` into `runtime/backend/spi/`; `JvmGen` emits `/_openapi.json`
  and `/_swagger` routes; response schema derived from handler return type.
  Spec: `docs/openapi.md §5 Phase 2`. Effort: ~3 days.

- [ ] **openapi-p3** — `@openapi` per-route annotation:
  `runtime/std/openapi.ssc` extern; `RouteEntry.metadata`; `HttpIntrinsics` merges metadata;
  `OpenApiGenerator` uses it for summary/description/tags/deprecated.
  Spec: `docs/openapi.md §5 Phase 3`. Effort: ~2 days.

- [ ] **openapi-p4** — Security schemes + auth declarations:
  `openApiSecurity(name, scheme, format)` extern; `components.securitySchemes` emission;
  per-route `security` array; `authMw` heuristic.
  Spec: `docs/openapi.md §5 Phase 4`. Effort: ~2 days.

- [ ] **openapi-p5** — `ssc emit-openapi` CLI + YAML output:
  `emitOpenapiCommand` in CLI; `--format json|yaml`; `--title`/`--version`/`--server` flags;
  interpreter dry-run; `EmitOpenapiCliTest` (4+ tests).
  Spec: `docs/openapi.md §5 Phase 5`. Effort: ~2 days.

---

## GraphQL

**Spec:** [`docs/graphql.md`](docs/graphql.md)

Wires to `graphql-java` (JVM/interpreter) and `graphql-js` (JS/Node).
Schema-first: SDL in `graphql` fenced blocks. Resolver functions in ScalaScript.

- [ ] **graphql-p1** — Schema + resolvers + `serveGraphQL` (JVM/interpreter):
  `runtime/std/graphql-plugin/` sbt subproject; `graphql-java` 22.x dep;
  `GraphQL.schema(sdl)`, `GraphQL.resolvers(…)`, `serveGraphQL(port, resolvers)`,
  `graphqlHandler(schema, resolvers)` externs; `graphql` fenced-block tag in
  `SourceLanguageRegistry`; `examples/graphql-hello.ssc`; 10+ tests.
  Spec: `docs/graphql.md §5 Phase 1`. Effort: ~4 days.

- [ ] **graphql-p2** — Async resolvers + GraphQL client + JS backend:
  `Future[A]` / `A ! Async` resolver support; `graphqlQuery` client extern;
  `graphql-js` JS runtime; JsGen `graphqlHandler` codegen; `Feature.GraphQL`;
  `examples/graphql-client.ssc`.
  Spec: `docs/graphql.md §5 Phase 2`. Effort: ~3 days.

- [ ] **graphql-p3** — Subscriptions over WebSocket (`graphql-ws`):
  WS endpoint `GET /graphql/ws`; `graphql-ws` protocol handler; `Source[A]` →
  `Publisher[A]` bridge; `graphqlSubscribe` client extern; production mode
  (introspection off); `examples/graphql-subscriptions.ssc`.
  Spec: `docs/graphql.md §5 Phase 3`. Effort: ~4 days.

- [ ] **graphql-p4** — Compile-time SDL validation:
  `GraphQLLanguagePlugin.compileBlock` validates SDL via `graphql-java` `SchemaParser`
  at `ssc build`; LSP diagnostics inline; `GraphQLSchemaCheckTest` (6+ tests).
  Spec: `docs/graphql.md §5 Phase 4`. Effort: ~2 days.

---

## v1.38 — Payment Request API (browser + server)

**Status:** Complete
**Spec:** [`docs/payment-request.md`](docs/payment-request.md)

Adds first-class support for the W3C Payment Request API with server-side
Apple Pay merchant validation and Google Pay token decryption. The same `.ssc`
file hosts both the browser-side payment sheet and the server verification
routes.

### Scope

**Browser (JS/SPA target)**
- [x] `PaymentRequest` DSL — `methods`, `total`, `items`, `options`, `shippingOptions`
- [x] `PaymentMethod.Card`, `PaymentMethod.ApplePay`, `PaymentMethod.GooglePay`
- [x] `request.show()`, `canMakePayment()`, `abort()`
- [x] `onMerchantValidation`, `onShippingAddressChange`, `onShippingOptionChange` hooks
- [x] JS preamble: `_prMethodData`, `_prDetails`, `_prOptions` helpers
- [x] `JsPaymentIntrinsics` — `RuntimeCall` table in `runtime/backend/js/intrinsics/Payment.scala`

**Server (JVM target)**
- [x] `ApplePay.validateMerchant(...)` — mTLS HTTPS POST to Apple's validation URL
- [x] `ApplePay.decryptToken(...)` — ECDH + AES-256-GCM decryption of Apple Pay token
- [x] `GooglePay.decryptToken(...)` — ECv2 signature verification + ECDH decryption
- [x] `JvmPaymentIntrinsics` — `RuntimeCall` table in `runtime/backend/jvm/intrinsics/Payment.scala`
- [x] `payments/payment-request/` sbt module with JVM implementation classes

**Interpreter**
- [x] Mock implementations for all intrinsics (always returns success)
- [x] `PaymentRequestIntrinsics` + `PaymentRequestPlugin` in `runtime/std/payment-request-plugin/`

**Types**
- [x] `Amount`, `PaymentItem`, `ShippingOption`, `PaymentOptions`, `PaymentResponse`
- [x] `CardDetails`, `ApplePayToken`, `GooglePayToken`, `GooglePayDecryptedCard`
- [x] `PaymentError` hierarchy

## v1.48 — SwiftUI Native Frontend (iOS + macOS)

**Status:** Complete (all 3 phases landed 2026-05-26)
**Spec:** [`docs/swiftui.md`](docs/swiftui.md)

Adds the `swiftui` frontend renderer — the first native mobile backend.
A `.ssc` file with `frontend: swiftui` emits a complete Swift Package
(Package.swift + ContentView.swift + App entry) that compiles with
`swift build` and targets iOS 17+ and/or macOS 14+.

The same View IR that drives the web (React/Vue/Solid/Custom) and desktop
(Swing, Electron, JavaFX) backends now also drives SwiftUI, with identical
signal semantics and style modifier lowering.

### Scope

**SwiftUI emitter**
- [x] `SwiftUIFrameworkBackend` — `FrontendFrameworkSpi` for `swiftui` target
- [x] Full View IR → SwiftUI mapping: `VStack/HStack/ZStack`, `Text`, `Button`,
      `TextField`, `SecureField`, `TextEditor`, `Toggle`, `Slider`, `Picker`,
      `Image`, `AsyncImage`, `Image(systemName:)`, `List`, `LazyVGrid`,
      `TabView`, `NavigationStack`, `ScrollView`, `.sheet`, `.alert`, `Form`,
      `Spacer`, `Divider`, `Fragment`, `Show/ShowSignal`, `Adaptive`, etc.
- [x] `ReactiveSignal[T]` → `@State private var` with Swift type inference
- [x] All 10 EventHandler cases lowered to SwiftUI/Swift expressions
- [x] Style modifiers: padding, frame, background, foreground, font, cornerRadius, opacity, a11y
- [x] `Package.swift` generation with iOS + macOS platform declarations
- [x] `@main` App entry + `WindowGroup { ContentView() }`
- [x] Separate iOS-only / macOS-only / dual-platform Package.swift variants

**Tests + example**
- [x] 41 unit tests in `SwiftUIEmitterTest` — all View cases, EventHandlers, style, helpers, ForSignal→ForEach, RemoveSelfFromList, AppModel
- [x] `examples/frontend/ios-hello/ios-hello.ssc` — counter + text input + toggle demo

**build.sbt**
- [x] `frontendSwiftUI` module (`frontend/swiftui/`)

### Planned phases

- **Phase 1 ✓ Landed (2026-05-26)** — SwiftUI emitter, 30 tests, example, build.sbt
- **Phase 2 ✓ Landed (2026-05-26)** — CLI integration: `ssc build --target mobile-ios`,
  `ssc build --target desktop-macos`, `ssc toolchain check --target mobile-ios` (swift+xcode),
  JvmGen `_ssc_ui_emit_native_platform_to_dir` + swiftui arm in `_ssc_ui_serve`
- **Phase 3 ✓ Landed (2026-05-26)** — Reactive list lowering: `ForSignal` → `ForEach`
  with index-aware `ForCtx` for `RemoveSelfFromList`; `@Observable AppModel` class
  generated when list signals exist (Observation framework, iOS 17+); 11 new tests

### Out of scope

- Server-side Swift (Vapor routes)
- Kotlin/Compose (P4, separate milestone)
- GTK / Scala Native (P7)
- SwiftUI Preview / HMR (post-v1.0, requires Xcode build system)

## v1.48.1 — `ssc run` one-command wrapper for SwiftUI targets

**Status:** ✓ Landed (2026-05-26) — macOS done; iOS simulator planned (see v1.48.2)

**What landed:**
- `--target macos` is now the canonical target name (`desktop-macos` kept as alias)
- `ssc package --target macos MyApp.ssc` — generates Swift Package and runs `swift build`
- `ssc run --target macos MyApp.ssc` — generates Swift Package, runs `swift build`, launches the binary
- `swiftAppName` helper derives Swift product name from `.ssc` name frontmatter

---

## v1.48.2 — `ssc run --target ios` (iOS Simulator)

**Status:** ✓ Landed (2026-05-26)
**Depends on:** v1.48.1 ✓

`--target ios` is the canonical name; `mobile-ios` kept as alias (same rename pattern as `desktop-macos` → `macos`).

### Goals

`ssc run --target ios MyApp.ssc` does the full cycle in one command:

1. Generate Swift Package (`buildSwiftUIPackage platform=ios`)
2. `xcodebuild build -scheme <AppName> -destination "platform=iOS Simulator,id=<uuid>" -derivedDataPath target/build/ios/derived`
3. Find `.app` at `derived/Build/Products/Debug-iphonesimulator/<AppName>.app`
4. Select simulator: `xcrun simctl list devices available --json` → latest available iPhone (e.g. iPhone 16 Pro)
5. Boot: `xcrun simctl boot <uuid>` (ignore "already booted" error)
6. Open Simulator.app: `open -a Simulator`
7. Install: `xcrun simctl install booted <path-to.app>`
8. Launch: `xcrun simctl launch [--console] booted <bundle-id>`

Bundle ID from frontmatter `bundle-id:` (default `com.example.app`).

### Log streaming flag

`--console` / `--no-console` (default: `--console`)

| Flag | Behaviour |
|------|-----------|
| `--console` (default) | `xcrun simctl launch --console` — blocks terminal, streams app stdout/stderr |
| `--no-console` | `xcrun simctl launch` — returns immediately after launch; app logs go to device log only |

Useful combinations:
- `ssc run --target ios MyApp.ssc` — build + launch, stay and watch logs
- `ssc run --target ios --no-console MyApp.ssc` — build + launch, return to shell prompt

The flag also applies to `--target macos` (where it controls whether the terminal blocks on the launched binary process).

### Incremental build

`--rebuild` / `--no-rebuild` (default: `--no-rebuild` = incremental)

| Flag | Behaviour |
|------|-----------|
| `--no-rebuild` (default) | Compare `mtime(MyApp.ssc)` vs `mtime(<AppName>.app/Info.plist)` — skip package + xcodebuild if unchanged; only install + launch |
| `--rebuild` | Always regenerate Swift Package and run xcodebuild regardless of mtimes |

Applies to both `--target ios` and `--target macos`.

### Pre-flight check

If `xcodebuild` or `xcrun` missing → clear error:
```
Error: Xcode is required for --target ios.
Run: ssc toolchain check --target ios
```

### Non-goals

- Real device deploy (needs Apple Developer account + signing — separate task v1.48.3)
- Hot-reload / live preview
- iPad or tvOS simulator

Effort: ~1 day.

---

## v1.48.3 — `ssc run --target ios --device` (real device via ios-deploy)

**Status:** ✓ Landed (2026-05-26)
**Depends on:** v1.48.2 ✓ (same Swift Package generation, same xcodebuild build step)

### Goals

`ssc run --target ios --device [--device-id <udid>] MyApp.ssc`

1. Build Swift Package + `xcodebuild` for device (arm64, not simulator SDK), with automatic signing:
   `xcodebuild build -scheme <AppName> -destination "generic/platform=iOS" -allowProvisioningUpdates`
2. If multiple devices connected and no `--device-id` → pick the first, print its name
3. Deploy + launch via **ios-deploy**:
   ```
   ios-deploy --bundle <path-to.app> [--justlaunch | --debug]
   ```
4. `--console` (default): `ios-deploy --debug` — streams logs via LLDB  
   `--no-console`: `ios-deploy --justlaunch` — returns immediately

### Flags summary

| Flag | Default | Description |
|------|---------|-------------|
| `--device` | — | target real device instead of simulator |
| `--device-id <udid>` | first connected | specific device UDID |
| `--console` / `--no-console` | `--console` | stream logs / return immediately |
| `--rebuild` / `--no-rebuild` | `--no-rebuild` | force full rebuild / incremental |

### Requirements

- Apple Developer account (free is sufficient for dev, 7-day cert expiry)
- Device registered in Apple Developer Portal (or Xcode auto-registration with `-allowProvisioningUpdates`)
- `ios-deploy` on PATH: `brew install ios-deploy`
- Device trusted on this Mac

### Pre-flight check

```
Error: ios-deploy is required for --target ios --device.
Run: ssc toolchain install --target ios
```

### Non-goals

- IPA export for distribution (→ v1.48.4)
- watchOS / tvOS companion app on device

Effort: ~1 day.

---

## v1.48.4 — `ssc package --target ios` → distributable .ipa

**Status:** ✓ Landed (2026-05-26)
**Depends on:** v1.48.1 ✓

### Goals

`ssc package --target ios [--out <dir>] MyApp.ssc`

Produces a signed `.ipa` ready for TestFlight or ad-hoc distribution:

1. Generate Swift Package
2. `xcodebuild archive -scheme <AppName> -archivePath target/package/ios/<AppName>.xcarchive`
3. `xcodebuild -exportArchive -archivePath ... -exportPath target/package/ios/ -exportOptionsPlist <generated-ExportOptions.plist>`
4. Output: `target/package/ios/<AppName>.ipa`

### ExportOptions.plist

Generated from frontmatter:

```yaml
bundle-id: com.example.myapp       # required
team-id: XXXXXXXXXX                # required; from keychain or env SSC_TEAM_ID
export-method: app-store           # app-store | ad-hoc | development | enterprise
```

If `team-id` not in frontmatter → look in env `SSC_TEAM_ID` → look in Xcode default team → error with instructions.

### Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--export-method` | `app-store` | Override export method |
| `--out <dir>` | `target/package/ios/` | Output directory |

### Requirements

- Distribution certificate in Keychain (or use `ssc toolchain setup-signing`)
- App ID registered in Apple Developer Portal

Effort: ~1 day.

---

## v1.48.5 — `ssc publish --target ios` (TestFlight + App Store via fastlane)

**Status:** ✓ Landed (2026-05-26)
**Depends on:** v1.48.4 ✓

### Goals

```bash
ssc publish --target ios --testflight MyApp.ssc   # upload to TestFlight
ssc publish --target ios --appstore MyApp.ssc      # submit to App Store
```

Full pipeline: build archive → export .ipa → upload → (optionally) submit for review.

### Fastfile integration

**Default behaviour** — generate `Fastfile` in project directory and run fastlane:

```ruby
# Generated: Fastfile
lane :testflight do
  gym(scheme: "<AppName>", export_method: "app-store")
  pilot(skip_waiting_for_build_processing: true)
end

lane :appstore do
  gym(scheme: "<AppName>", export_method: "app-store")
  deliver(submit_for_review: true, automatic_release: false)
end
```

**`--fastlane` flag** — skip generation, use the existing `Fastfile` in the project directory:

```bash
ssc publish --target ios --testflight --fastlane MyApp.ssc
# equivalent to: cd <project-dir> && fastlane testflight
```

Useful when the team has a custom `Fastfile` with additional steps (metadata, screenshots, changelog).

### App Store Connect credentials

Preferred: API key (`--api-key-path <path-to-AuthKey.p8>` or env `APP_STORE_CONNECT_API_KEY_PATH`).  
Fallback: Apple ID + app-specific password (less secure, not recommended for CI).

### Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--testflight` | — | Upload to TestFlight |
| `--appstore` | — | Submit to App Store |
| `--fastlane` | off | Use existing Fastfile instead of generating |
| `--api-key-path <p>` | env | App Store Connect API key (.p8) |
| `--submit-for-review` | false | Auto-submit after upload (App Store only) |
| `--release-notes <text>` | — | What's new text for TestFlight |

### Requirements

- `fastlane` on PATH: `brew install fastlane`
- App Store Connect API key (recommended) or Apple ID
- Distribution certificate + provisioning profile (or `ssc toolchain setup-signing`)
- App record created in App Store Connect

Effort: ~2 days (fastlane integration + credential handling + Fastfile generation).

---

## v1.49 — macOS distribution: notarize + DMG + Mac App Store

**Status:** ✓ Landed (2026-05-26)
**Depends on:** v1.48.1 ✓

### Goals

Three subcommands:

#### `ssc package --target macos --distribution`

Produces a distributable signed + notarized `.app` and `.dmg`:

1. `xcodebuild archive -scheme <AppName>` → `.xcarchive`
2. `xcodebuild -exportArchive` with macOS export options → signed `.app`
3. `codesign --deep --sign "Developer ID Application: ..."` (if not already done by xcodebuild)
4. `xcrun notarytool submit` → wait for notarization (async, ~1-5 min)
5. `xcrun stapler staple <AppName>.app`
6. Create DMG: `hdiutil create -volname <AppName> -srcfolder <AppName>.app -ov -format UDZO <AppName>.dmg`
7. Output: `target/package/macos/<AppName>.dmg`

#### `ssc publish --target macos --appstore`

1. `xcodebuild archive` with Mac App Store export options
2. `xcrun altool --upload-app` or `fastlane deliver` → upload to App Store Connect
3. Submit for review (optional `--submit-for-review`)

#### Fastfile integration (same pattern as iOS)

Default: generate `Fastfile` with `lane :mac_appstore` and `lane :notarize`.  
`--fastlane` flag: use existing `Fastfile`.

### Frontmatter keys

```yaml
bundle-id: com.example.myapp
team-id: XXXXXXXXXX
mac-category: public.app-category.productivity  # for App Store
```

### Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--distribution` | off | Build for distribution (notarize + DMG) vs dev |
| `--appstore` | off | Mac App Store export instead of Developer ID |
| `--fastlane` | off | Use existing Fastfile |
| `--dmg` / `--no-dmg` | `--dmg` | Create DMG wrapper |
| `--notarize` / `--no-notarize` | `--notarize` | Run notarytool (required for Gatekeeper) |
| `--submit-for-review` | false | Auto-submit after upload |
| `--api-key-path <p>` | env | App Store Connect API key |

### Requirements

- Developer ID Application certificate (for DMG/notarize) or Mac App Store certificate
- `fastlane` on PATH for publish lane
- App Store Connect API key for upload
- `xcrun notarytool` — included with Xcode 13+

### `ssc toolchain setup-signing`

New subcommand — initialises `fastlane match`:

```bash
ssc toolchain setup-signing --target ios    # fastlane match init + fetch/create certs
ssc toolchain setup-signing --target macos  # Developer ID + Mac App Store certs
```

Stores certificates in git repo (or S3 — configurable). Team members run once to pull certs.

### `ssc toolchain install` additions

```bash
ssc toolchain install --target ios    # brew install ios-deploy fastlane
ssc toolchain install --target macos  # brew install fastlane
```

Effort: ~3 days (notarize flow + DMG + fastlane Mac lanes + toolchain setup-signing).
