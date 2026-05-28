# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" if its slug has no file in `.work/active/`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Distributed Runtime — v1.63

- [x] **v1.63.0-distributed-runtime-spec** — Canonical spec and backlog for local/remote/distributed runtime support. Merges `docs/placement-and-remoting.md` and `docs/arch-local-distributed-cluster.md` into `docs/distributed-runtime.md`; keeps operation names like `users.get`, code identity, handler/source/behavior registries, worker bundles, cluster CLI/front matter, and dynamic-code roadmap, while adopting `! Async`, `BasicStreamOps`, typed `ActorRef[M]`, `Cluster`, `SeedResolver`, and cluster-aware deploy phases. Updated with `docs/cluster-operations.md` details for token rotation, persistent cluster config, rolling upgrades, multi-region lowering, and HPA/autoscale. ✓ Landed 2026-05-28.

- [x] **v1.63.1-stream-bridge-basic-ops** — Stream bridge and shared safe operators: add `runtime/std/streams-bridge.ssc`, `Source[A].distributed`, `DStream[A].local`, `DStream[A].localBounded`, `BasicStreamOps[F[_]]`, `_dag_sink_local`, and bounded/materialization tests. Spec: `docs/distributed-runtime.md §v1.63.1`. ✓ Landed 2026-05-28.

- [x] **v1.63.2-typed-actors-remote-spawn** — Typed actors and remote spawn: complete `ActorRef[M]`, add `spawnRemote`, `BehaviorRegistry`, `cluster_spawn` / `cluster_spawn_ack`, JVM lowering for `setClusterAuthToken`, and two-node actor tests. Spec: `docs/distributed-runtime.md §v1.63.2`. ✓ Landed 2026-05-28.

- [x] **v1.63.3-cluster-capability-seed-code-identity** — Cluster capability, seed discovery, and code identity: add `Cluster`, `SeedResolver`, `.ssc` / `.sscc` code identity, `cluster:` and registry front matter, `cluster Demo:` lowering, and diagnostics for missing handlers/codecs and code mismatch. Spec: `docs/distributed-runtime.md §v1.63.3`. ✓ Landed 2026-05-28: backend SPI `Cluster` / `SeedResolver` / `CodeIdentity`, ScalaScript `ClusterCapability` / `SeedResolver.staticList` / `clusterOf` / `resolveSeeds` / `codeIdentity` / `assertCodeIdentity`, interpreter coverage for static/DNS/K8s seeds plus Consul diagnostics, typed `cluster:` / registry front matter in AST/IR/`.sscc`, missing registry target/type validation, and top-level `cluster Demo:` lowering.

- [x] **v1.63.4-remote-registries-async-rpc** — Remote registries and async RPC: compile `@remote` / `remote def` / manifest handlers into `RemoteHandlerRegistry`, add `Remote.function[A, B](name)` returning `B ! Async | RemoteCallError`, add `remoteStub[Api]`, and support in-process/HTTP/WS transports with JSON fallback and future `WireCodec`. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28: backend SPI `RemoteHandlerRegistry` / `RemoteHandlerInfo` / `RemoteCallError`, interpreter `remoteHandlers:` registry lowering, `std.remote` + `remote-plugin` for in-process `Remote.function` / `remoteTryCall`, and POST HTTP JSON fallback for `path:` entries. `@remote` / `remote def`, `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain follow-ups.

- [x] **v1.63.4b-remote-sugar-stubs-wire** — Remote RPC follow-ups: lower `@remote` / `remote def` source declarations into `remoteHandlers:`, add `remoteStub[Api]`, make generated calls return `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28 for source `@remote(name = ..., path = ...) def` and simple `remote def echo(...)` lowering into `remoteHandlers:` metadata, with parser validation and example coverage. Remaining pieces split into `v1.63.4c`.

- [x] **v1.63.4c-remote-stubs-async-wire** — Remote RPC stubs and transports: add `remoteStub[Api]`, make generated calls return `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28 for explicit `Remote.http[A, B](url)` / `remoteHttpFunction` POST HTTP JSON fallback client calls with typed `RemoteCallError` results. Remaining typed stubs, async lowering, WebSocket/internal-wire, and binary `WireCodec[A]` split into `v1.63.4d`.

- [x] **v1.63.4d-remote-stubs-async-wire** — Typed remote RPC stubs and binary transports: derive generated typed HTTP client metadata from `path:` remote handlers as the first `remoteStub` bridge, then continue with trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec[A]` negotiation while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-28: `RemoteClientDeriver` now converts `path:` remote handlers into generated `RemoteRpc` typed HTTP client metadata, preserving existing explicit clients and reusing JS/JVM typed-route client codegen.

- [x] **v1.63.4e-remote-trait-stubs-wire** — Remote RPC remaining transports: add trait-shaped `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-29 partial runtime stub: `Remote.stub(baseUrl)` / `RemoteStub` now provide path-based HTTP JSON fallback `function`, `call`, and `tryCall` helpers over the same remote HTTP transport. Trait-shaped compile-time `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain split into `v1.63.4f`.

- [x] **v1.63.4f-remote-trait-stubs-wire** — Remote RPC compile-time stubs and binary transports: add trait-shaped `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`. ✓ Landed 2026-05-29 surface syntax: `remoteStub[Api](baseUrl)` and `Remote.stub[Api](baseUrl)` now accept a forward-compatible API type argument while returning the path-based `RemoteStub` facade. Generated trait methods, async lowering, WS/internal-wire, and binary codec negotiation remain split into `v1.63.4g`.

- [ ] **v1.63.4g-remote-trait-methods-wire** — Remote RPC generated trait methods and binary transports: derive callable methods for `remoteStub[Api]`, make generated calls expose `B ! Async | RemoteCallError`, add WebSocket/internal-wire transport, and negotiate binary `WireCodec[A]` while keeping HTTP JSON fallback. Spec: `docs/distributed-runtime.md §v1.63.4`.

- [ ] **v1.63.5-cluster-runner-worker-bundles** — Cluster runner and worker bundles: `ssc cluster run/package/status/handlers/stop`, worker bundle packaging with code identity and registry metadata, roles, advertised URLs, auth-token wiring, deploy-target integration, and two-local-process smoke tests. Spec: `docs/distributed-runtime.md §v1.63.5`.

- [ ] **v1.63.6-stream-actor-placement-adapters** — Stream and actor placement adapters: `Source[A].remote`, `RemoteSource[A].local`, `RemoteSource[A].distributed`, `DStream[A].remote`, WebSocket remote streams with JSON fallback, SSE constraints, local proxy actors, and router/sharded/role actor groups. Spec: `docs/distributed-runtime.md §v1.63.6`.

- [ ] **v1.63.7-cluster-aware-deploy-ops** — Cluster-aware deployment and operations: `ClusterTarget`, K8s StatefulSet/headless Service/token Secret, `rotateClusterToken` with `token_rotate` / `token_rotate_ack` and quorum overlap, `clusterConfigSet/Get` persistence through `StateBackend`, `Deploy.rollingCluster`, `FaultToleranceConfig` multi-region lowering, K8s HPA/autoscale emission through `HpaConfig`, and Docker Compose target. Spec: `docs/distributed-runtime.md §v1.63.7`.

- [ ] **v1.63.8-dynamic-code-ops-hardening** — Dynamic code shipping and ops hardening: signed worker bundles, remote artifact cache, dependency verification, sandbox/resource policy, audit log, unload/rollback, mixed-version placement after wire/schema compatibility, metrics/tracing, circuit breakers, load shedding, and production cookbook. Spec: `docs/distributed-runtime.md §v1.63.8`.

## Distributed Wire Protocol — v1.62

- [x] **v1.62.0-distributed-wire-spec** — Spec and backlog for an opt-in internal distributed wire layer across actors, cluster control plane, Dataset/MapReduce, native DStream runner, typed route clients/RPC, WebSocket subscriptions, and object sync. Covers JSON fallback plus MsgPack and CBOR profiles, JS/browser support, same-version-only initial binary compatibility, negotiation, security, compression, resource limits, observability, and phases v1.62.1–v1.62.8. Spec: `docs/distributed-wire-protocol.md`. ✓ Landed 2026-05-28.

- [x] **v1.62.1-wire-core** — Shared wire runtime: add `WireValue`, `WireEnvelope`, `WireCodec[A]`, decode errors, resource limits, negotiation/config types, front-matter/CLI parsing for `wire:`, JSON/MsgPack/CBOR codec profiles on JVM/interpreter and JS/browser, and golden cross-format vectors. Spec: `docs/distributed-wire-protocol.md §Phase 1`. ✓ Landed 2026-05-28.

- [x] **v1.62.2-actors-binary-ws** — Actor cluster binary WebSocket: `ssc-actors-v2.<format>` subprotocols, binary WS frames for actor user messages and cluster control envelopes (registry, heartbeat, gossip, leader election, pub/sub, config, drain, metrics, phi vectors), preserving JSON `ssc-actors-v1` fallback. Spec: `docs/distributed-wire-protocol.md §Phase 2`. ✓ Landed 2026-05-28.

- [x] **v1.62.3-typed-rpc-binary** — Typed route clients/RPC binary negotiation: generated HTTP `Accept`/`Content-Type` for MsgPack/CBOR wire payloads, JSON fallback, 406/415 errors, binary WS subscription frames, and SSE text/base64 fallback. Spec: `docs/distributed-wire-protocol.md §Phase 3`. ✓ Landed 2026-05-29.

- [ ] **v1.62.4-dataset-binary-partitions** — Distributed Dataset/MapReduce binary partitions and shuffle: route `DatasetWirePartition` through `WireCodec[A]`, chunk large partitions, and run distributed map/shuffle conformance under JSON, MsgPack, and CBOR. Spec: `docs/distributed-wire-protocol.md §Phase 4`.

- [ ] **v1.62.5-dstream-native-wire** — Native DStream runner binary wire: element batches, watermarks, triggers, side inputs, side outputs, checkpoint metadata, and errors over the shared wire layer. External Spark/Kafka/Flink/Beam protocols stay unchanged. Spec: `docs/distributed-wire-protocol.md §Phase 5`.

- [ ] **v1.62.6-object-sync-binary** — Client/server object sync binary payloads for generated ScalaScript clients/servers, with public/debug JSON routes retained. Spec: `docs/distributed-wire-protocol.md §Phase 6`.

- [ ] **v1.62.7-wire-security-ops** — Wire security and operations: HMAC frame signatures, session ids, sequence numbers, replay windows, gzip/zstd negotiation, mTLS hooks, frame/depth/chunk limits, and metrics. Spec: `docs/distributed-wire-protocol.md §Phase 7`.

- [ ] **v1.62.8-wire-compatibility** — Binary compatibility before first stable release: schema-id hashing, additive evolution rules, default/unknown-field policy, old/new test vectors, and explicit mixed-version opt-in. Spec: `docs/distributed-wire-protocol.md §Phase 8`.

## Architecture & Extensibility — queued

Queued from `BACKLOG.md §Architecture & Extensibility Roadmap`. Official
centralized publication targets such as Maven Central and sbt Plugin Portal are
intentionally omitted from this queue and remain deferred in `BACKLOG.md`.
ScalaScript's own registry work stays queued.

### Theme C — Distribution ecosystem

- [ ] **arch-distribution-p1** — `DepResolver` SPI + `GithubReleaseResolver`: refactor `ImportResolver` into a pluggable registry; add `github:user/repo@tag` scheme; `DepCache` with sha256 pin; tests against mock GitHub API. Spec: `docs/arch-distribution.md §5 Phase 1`.

- [ ] **arch-distribution-p2** — Coursier wiring + JitPack: `MavenDepResolver` using Coursier for `dep:` scheme; `JitpackResolver` as thin Coursier repo wrapper; tests with embedded local Maven fixture. Spec: `docs/arch-distribution.md §5 Phase 2`.

- [ ] **arch-distribution-p4** — Community plugin starter template: `templates/plugin/` with GitHub Actions release workflow; `ssc new --template plugin`; new `docs/community-plugins.md`. Spec: `docs/arch-distribution.md §5 Phase 4`.

### Theme D — sbt-scalascript plugin completion

- [ ] **arch-sbt-plugin-p1** — Source convention + `sscCompile`: `sscSourceDirectories` setting; `sscCompile` task forks `ssc build`; wire into `Compile / compile`; scripted test: `sbt compile` compiles `.ssc` files. Spec: `docs/arch-sbt-plugin.md §5 Phase 1`.

- [ ] **arch-sbt-plugin-p2** — `sscLink` + `packageBin`: `sscLink` task forks `ssc link`; wire into `Compile / packageBin`; scripted test: `sbt package` produces runnable JAR. Spec: `docs/arch-sbt-plugin.md §5 Phase 2`.

- [ ] **arch-sbt-plugin-p3** — Test integration: `SscTestFramework`; `sscTest` forks `ssc test --output-format junit-xml`; JUnit XML parsing to sbt `TestResult`; scripted test: `sbt test` discovers and runs `.ssc` tests. Spec: `docs/arch-sbt-plugin.md §5 Phase 3`.

- [ ] **arch-sbt-plugin-p4** — REPL / Run / Watch + BSP wiring: `sscRepl`, `sscRun`, `sscWatch` tasks; `BspIntegration` emits `.bsp/scalascript.json` for Metals/IntelliJ. Spec: `docs/arch-sbt-plugin.md §5 Phase 4`.

### Theme E — `ssc new` + standalone installation

- [ ] **arch-ssc-new-p1** — `ssc new` subcommand + `app`/`lib` templates + Coursier channel: `NewProject.scala` in CLI; `app`, `lib` templates bundled in `ssc.jar`; Coursier channel JSON at `releases.scalascript.io`; `sbt cli/assembly` fat JAR. Spec: `docs/arch-ssc-new.md §5 Phase 1`.

- [ ] **arch-ssc-new-p2** — Additional templates + Homebrew tap + curl installer: `plugin`, `dsl`, `web-app`, `wasm-app` templates; Homebrew tap formula; `curl | sh` installer; `README.md` Getting Started updated. Spec: `docs/arch-ssc-new.md §5 Phase 2`.

- [ ] **arch-ssc-new-p3** — Standalone docs update: `docs/getting-started-standalone.md`; `docs/community-plugins.md`; `docs/user-guide.md §Installation` updated; `install.sh` gets `--dev` flag. Spec: `docs/arch-ssc-new.md §5 Phase 3`.

### Theme B — Build-time registry consolidation

- [ ] **arch-build-registry-p1** — `PluginSpec` in `build.sbt`: introduce `PluginSpec` case class; migrate all plugins; compute CLI deps, installBin, pluginPkgs, aggregate, and pluginTests from it. Spec: `docs/arch-build-registry.md §5 Phase 1`.

- [ ] **arch-build-registry-p2** — Runtime `PluginRegistry` unification: new `PluginRegistry` trait in `backend/spi`; `BackendRegistry` implements it; `PluginManifest`/`SubprocessBackend` to `SubprocessPlugin`; `LocalRegistry` absorbed into `RemotePluginInstaller`. Spec: `docs/arch-build-registry.md §5 Phase 2`.

### Theme A — Stable Plugin SPI

- [ ] **arch-stable-spi-p1** — `scalascript-plugin-api` module: new sbt subproject; `PluginValue`, `PluginError`, `PluginComputation`, `JsonCodec`, and `PluginContext` capability re-export; existing plugins add dependency. Spec: `docs/arch-stable-spi.md §5 Phase 1`.

- [ ] **arch-stable-spi-p2** — Capability decomposition + 3 showcase plugins: `HttpCap`, `WsCap`, `DbCap`, `StorageCap`, `ValidateCap`, `MountCap`; typed `NativeImpl.eval`; `LegacyNativeContext` shim; migrate `json-plugin`, `http-plugin`, `auth-plugin`. Spec: `docs/arch-stable-spi.md §5 Phase 2`.

- [ ] **arch-stable-spi-p3** — Full migration of all `*Intrinsics.scala`: remove `LegacyNativeContext`; delete `isStdPluginInterpreterTest` filter; CI classpath check rejects `scalascript/interpreter/` in plugin subprojects. Spec: `docs/arch-stable-spi.md §5 Phase 3`.

### Theme F — DSL platform hooks

- [ ] **arch-dsl-hooks-p1** — `InterpolatorRegistry` + first migration: `InterpolatorImpl` trait; `Backend.interpolators` field; Typer / EvalRuntime / JvmGen / JsGen / CapabilityCheck consult registry; migrate `json"..."` and `html"..."`. Spec: `docs/arch-dsl-hooks.md §6 Phase 1`.

- [ ] **arch-dsl-hooks-p2** — `PreprocessorRegistry`: `Preprocessor` trait; `PreprocessorRegistry`; five existing preprocessors converted to registered instances; `Parser.parseScalaWithDiagnostic` uses it. Spec: `docs/arch-dsl-hooks.md §6 Phase 2`.

- [ ] **arch-dsl-hooks-p3** — `SourceLanguage` built-in migration: `html`, `css`, `sql`, `xml`, `javascript` fenced tags become `SourceLanguagePlugin` implementations; `Lang.scala` routing removed. Spec: `docs/arch-dsl-hooks.md §6 Phase 3`.

- [ ] **arch-dsl-hooks-p4** — `InterpolatorCheckRegistry`: `InterpolatorCheck` trait; `MarkupInterpolatorCheck` migrated; plugin `xml-plugin` registers compile-time check. Spec: `docs/arch-dsl-hooks.md §6 Phase 4`.

### Theme H — Library Modularity

- [ ] **arch-lib-p1** — `@deprecated` + `@experimental` annotations: new annotations in `Annotation.scala`; typer emits warnings at call sites; `--fatal-warnings` flag; tests. Spec: `docs/arch-library-modularity.md §6 Phase 1`.

- [ ] **arch-lib-p2** — `@internal` access control: parsed and stored annotations; cross-package check in Typer; source-package diagnostics; per-definition and per-heading granularity. Spec: `docs/arch-library-modularity.md §6 Phase 2`.

- [ ] **arch-lib-p3** — Namespace collision detection: `ImportResolver` tracks name contributions per import; warning on collision; `--strict-namespaces`; qualified import syntax `[Name from alias](dep:...)`. Spec: `docs/arch-library-modularity.md §6 Phase 3`.

- [ ] **arch-lib-p4** — `ssclib` format + `ssc package --lib`: `SsclibManifest` YAML schema; `.ssclib` ZIP format; `ssc package --lib`; `ImportResolver` unpacks archives. Spec: `docs/arch-library-modularity.md §6 Phase 4`.

- [ ] **arch-lib-p5** — Transitive deps + lockfile: BFS dependency resolution from `ssclib-manifest.yaml`; conflict resolution; `ssc-lock.yaml`; `ssc update`; `--strict-deps`; cycle detection. Spec: `docs/arch-library-modularity.md §6 Phase 5`.

- [ ] **arch-lib-p6** — Pre-compiled IR in `.ssclib` + compat check: `ssc package --lib --precompile`; `.scim` in `ir/`; resolver prefers pre-compiled IR; `ssc check-compat old.ssclib new.ssclib`. Spec: `docs/arch-library-modularity.md §6 Phase 6`.

### Theme I — Package Registry

- [ ] **arch-registry-p1** — Registry repository + `packages.yaml` schema: create first-party registry repo; define schema; seed with first-party packages; validation CI. Spec: `docs/arch-registry.md §5 Phase 1`.

- [ ] **arch-registry-p2** — `ssc search` / `ssc info` / `ssc add` CLI: cached `RegistryClient`; local ranked search; manifest update; mock-HTTP tests. Spec: `docs/arch-registry.md §5 Phase 2`.

- [ ] **arch-registry-p3** — GitHub Pages HTML index: generation script, publish workflow, client-side search, per-package JSON, and `registry.scalascript.io` CNAME. Spec: `docs/arch-registry.md §5 Phase 3`.

- [ ] **arch-registry-p4** — Private registry support: `registry.url` config; `--registry <url>` CLI flag; enterprise internal mirror documentation. Spec: `docs/arch-registry.md §5 Phase 4`.

### Theme J — Lightweight FFI

- [ ] **arch-ffi-p1** — `@jvm("expr")` annotation + JVM codegen: inline JVM expression bodies, argument substitution, capability checks, example, and tests. Spec: `docs/arch-ffi.md §6 Phase 1`.

- [ ] **arch-ffi-p2** — `@js("expr")` codegen + interpreter behaviour: JS inline bodies; `@interpreterUnsupported`; cross-backend parity tests. Spec: `docs/arch-ffi.md §6 Phase 2`.

- [ ] **arch-ffi-p3** — `jvm/glue.jar` in `.ssclib` + `ssc package --lib --jvm-glue`: manifest glue fields, JVM classpath injection, package CLI flag, and glue fixture integration test. Spec: `docs/arch-ffi.md §6 Phase 3`. Prerequisite: `arch-lib-p4`.

- [ ] **arch-ffi-p4** — `js/glue.js` preamble injection + `META-INF/services` in glue.jar: JS glue injection, service loading into `BackendRegistry`, and end-to-end JS glue test. Spec: `docs/arch-ffi.md §6 Phase 4`.

### Theme G — Metaprogramming v2.x

- [ ] **arch-meta-v2-p3** — Cross-module `inline` expansion: IR-level inlining in `ssc link`; requires plugin-author demand and stable SPI/distribution foundations. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 3`.

- [ ] **arch-meta-v2-p4** — Restricted `QuotedMacro[A]` surface: `Expr[A].asValue`, `Expr[A].asTerm`, quoting, `MacroImpl` IR node, expansion at link time. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 4`.

- [ ] **arch-meta-v2-p5** — Full `Mirror`-based user typeclass derivation: `scalascript.reflect.Mirror`; inline match on `Mirror.Product/Sum` for arbitrary user typeclasses. Spec: `docs/arch-metaprogramming-v2.md §4 Phase 5`.

## Government Interaction — v1.59 Bureau

- [x] **v1.59.1-bureau-core** — `gov/bureau-core/` module: all SPI types (`CountryCode` opaque type + constants, `LegalForm` enum, `TaxIdentifier`/`TaxIdType`, `BusinessEntity`, `GovDomain`, `SubmissionResult`/`SubmissionStatus`/`GovError`); domain provider traits (`CountryProvider`, `FiscalProvider`, `SocialProvider`, `RegistryProvider`, `CustomsProvider`, `StatisticsProvider`, `EnvProvider`); shared fiscal/social/registry types (`FiscalInvoice`+`Currency`+`ExchangeRate`, `TaxDeclaration`, `AuditFile`, `ContributionDeclaration`, `EmployeeRecord`, `PaymentReference`, `BusinessRecord`, `VatPayerStatus`); `BureauError` sealed hierarchy (7 cases). `BureauCoreTest`: type construction, `BusinessEntity.requireTaxId`, `SubmissionStatus`, `BureauError` hierarchy. Spec: `docs/bureau.md §3–§7`.

- [x] **v1.59.2-bureau-signing** — `gov/bureau-signing/` module: `SigningProvider` SPI (`sign(data, format): Future[SignatureResult]`, `getCertificateInfo`, `getSupportedFormats`); `SignatureFormat` enum (PAdES, XAdES, CAdES); `CertificateInfo`; `PfxSigningProvider` (.pfx/.p12 via `java.security.KeyStore`; PKCS#12 loading; SHA-256 with RSA; `SIGNING_PFX_PATH`+`SIGNING_PFX_PASSWORD` env); `MockSigningProvider` (always succeeds, configurable cert info); `SigningError` ADT. `PfxSigningTest`: sign+verify round-trip with generated test `.pfx`; `MockSigningTest`: always succeeds. Spec: `docs/bureau.md §8`.

- [x] **v1.59.3-bureau-pl-registry** — `gov/bureau-pl-registry/` module: `PlRegistryProvider` implementing `RegistryProvider` for `CountryCode.PL`; 4 adapters: CEIDG (`/api/interoperability/ceidg/v2/firma/pkd` REST, API-Key header), REGON (GUS BIR1 SOAP/REST, API key, `DaneSzukajPodmioty` query), Biała Lista (`/api/search/nip` JSON, API-Key), KRS (`/KRS/application/json/pl/search/...` REST, public); injectable HTTP for tests; ServiceLoader. `PlRegistryTest`: JSON parsing for each adapter, not-found paths, error handling (40+ tests). Spec: `docs/bureau.md §5.4`.

- [x] **v1.59.4-bureau-pl-fiscal-ksef** — `gov/bureau-pl-fiscal/` module (KSeF part): `PlKsefAdapter` — QES session auth (challenge→signed→session token), FA_VAT XML invoice submit (`POST /api/online/Invoice/Send`), status poll (`GET /api/online/Invoice/Status`), invoice fetch (`GET /api/online/Invoice/Get`), query invoices (`POST /api/online/Query/Invoice/sync`); `KsefInvoice` XML builder using `xml"..."` interpolator; `KsefSessionStore` (in-memory token cache, TTL 24h). `PlKsefTest`: auth challenge flow, submit+poll, fetch, query, API errors (401/429/503). Spec: `docs/bureau.md §5.1`.

- [x] **v1.59.5-bureau-pl-fiscal-declarations** — `gov/bureau-pl-fiscal/` (declaration part): `PlDeclarationAdapter` — JPK_VAT7M XML build+submit+poll (`POST /api/v3/deklaracje`), JPK_FA (sales invoice audit), CIT-8 + PIT-36 via e-Deklaracje SOAP (`POST https://e-deklaracje.mf.gov.pl/rejestracja`); schema validation hook via `JvmMarkupCodec.validate`; UPO receipt parsing. `PlDeclarationTest`: JPK_VAT7M submit+poll, schema validation, async rejection. Spec: `docs/bureau.md §5.2–§5.3`.

- [x] **v1.59.6-bureau-pl-social** — `gov/bureau-pl-social/` module: `PlZusAdapter` implementing `SocialProvider` for `CountryCode.PL`; KEDU XML declaration builder (DRA, RCA, RSA); ZUS PUE REST submission (`POST /services/Dokumenty`); NRB payment-reference generator (28-digit, ISO 7064 MOD97 check digit, ZUS NRB formula); `calculateContributions(base, type)` — replicate ZUS formula for emerytalne/rentowe/chorobowe/wypadkowe/FP/FGŚP rates. `PlZusTest`: DRA declaration, NRB reference generation (formula verified against ZUS examples), contributions calculation (25+ tests). Spec: `docs/bureau.md §5.5`.

- [x] **v1.59.7-bureau-eu** — `gov/bureau-eu/` module: `EuRegistryProvider` implementing `RegistryProvider` for EU-level queries; VIES VAT verification (`EuViesAdapter` — `checkVat` SOAP call to `http://ec.europa.eu/taxation_customs/vies/services/checkVatService`); `bureau-pl` aggregate module wiring all PL adapters; ServiceLoader for both. `ViesTest`: VAT number verify (valid, invalid, service down). Spec: `docs/bureau.md §6`.

- [x] **v1.59.8-bureau-scheduler** — `gov/bureau-scheduler/` module: `SimpleScheduler` (single-threaded `ScheduledExecutorService`-backed); job types `RecurringJob`/`OneTimeJob`/`PeriodJob` (file annual/monthly); `BureauCalendar` (Polish business day calendar for 2024–2026, Easter algorithm); `ScheduledJob` ADT; `onJobComplete`/`onJobFailed` callbacks; `runNow(jobId)` for manual trigger; `disable(jobId)`/`enable(jobId)`. `SchedulerTest`: job scheduling, runNow, callbacks, disable/enable. Spec: `docs/bureau.md §9`.

- [x] **v1.59.9-bureau-mock** — `gov/bureau-mock/` module: `MockBureauProvider` (configurable in-memory `CountryProvider` covering all domain sub-providers); named constructors `MockBureauProvider.poland()` / `.vat()` / `.all()`; `MockFiscalProvider`/`MockSocialProvider`/`MockRegistryProvider` (all named constructors return expected mock values, recorded calls for assertions); integration tests wiring all 7 previous modules end-to-end; `examples/bureau-demo.ssc`; `CHANGELOG.md` + `BACKLOG.md` v1.59 section. `MockProviderTest`: all named constructors (30+ tests). Spec: `docs/bureau.md §12`.

## v1.51 — Streams with Backpressure

- [x] **v1.51.1-streams-source-core** — Missing operators on `Source` in interpreter: `scan(z)(f)` (running aggregate), `Source.tick(durationMillis)` (periodic unit), `Source.unfold(s)(f)` (state-evolving generator), `Source.fromCallback(register)` (push adapter → bounded queue), `cancellable` (returns `(Source, () => Unit)`), `onError(f)` (side-effect on error without recovery); extern declarations in `streams.ssc` for all existing + new combinators; 12+ new tests. Spec: `docs/streams.md §5, §4.2`.

- [x] **v1.51.2-streams-js** — Complete JS `_makeAsyncStream` helper in `JsGen.scala` with terminal ops (`runForeach/runFold/runToList/runDrain`), combining (`merge/zipWith/broadcast/balance/groupBy/mergeSubstreams`), advanced (`scan/buffer/throttle/debounce/mapAsync/recover/mapError`), routing (`to/via`); JS codegen cases for `Source.tick`, `Source.unfold`, `Source.fromCallback`, `Sink.*`, `Flow.*`; 10+ JS codegen tests. Spec: `docs/streams.md §9.2, §9.3`.

- [x] **v1.51.3-streams-flow-sink** — Extended `Flow` constructors: `Flow.fromFunction/filter/concat/take/drop/flatMap/scan/mapAsync/recover/buffer/throttle/debounce`; complete `streams.ssc` extern declarations; 10+ Flow tests. Spec: `docs/streams.md §5.3`.

## Language & Compiler — Spark extensions (new)

- [x] **spark-lakehouse-l4-hudi** — Spark Lakehouse Hudi (L.4): `SparkGen.detectLakehouseFormats` extended to detect `.format("hudi")` → auto-emit `org.apache.hudi:hudi-spark3.5-bundle_2.13:0.15.0` dep + `spark.serializer=KryoSerializer` + `spark.sql.extensions=HoodieSparkSessionExtension` + `spark.sql.catalog.spark_catalog=HoodieCatalog` configs; `DefaultHudiVersion = "0.15.0"` constant; `examples/spark-lakehouse-hudi.ssc` (write/read/upsert Hudi table); 9 codegen tests. Spec: `docs/spark-lakehouse.md §L.4`. (2026-05-27)

## OAuth / Security

- [x] **oauth-dpop** — DPoP (RFC 9449) sender-constrained tokens for the existing OAuth 2.1 AS: `DPoP.verifyProof` (RS256 + ES256, `htm`/`htu` binding, `jti` replay prevention via `InMemoryJtiStore`, `nonce` + `ath` checks); `cnf.jkt` (RFC 7638 JWK thumbprint) injected into issued access tokens when DPoP proof present on `/token`; `token_type: DPoP` on DPoP-bound tokens; `OAuthGuard.check` extended with `requestMethod`/`requestUrl`/`dpopJtiStore` params to validate DPoP proof when `cnf.jkt` is set; `AuthServerConfig.dpopNonceLifetimeSeconds`; script API `oauth.authServer(...)` wires `dpopNonce` config field; 36 tests in `OAuthDPoPTest`. `docs/oauth.md §Spec compliance` updated. (2026-05-28)

- [x] **oauth-par** — PAR (RFC 9126 Pushed Authorization Requests): `POST /par` endpoint (`OAuthRoutes.handlePar`) accepting same params as `/authorize`; returns `request_uri` (urn:ietf:params:oauth:request_uri:<nonce>) + `expires_in`; `/authorize` resolves stored params via `request_uri`; `AuthServerConfig.parRequired` flag rejects direct params when true; single-use (`InMemoryPushedAuthRequestStore.consume`); `PushedAuthRequest`/`PushOutcome`/`PushedAuthRequestStore` types; `AuthServer.pushAuthorizationRequest`; PAR endpoint in AS metadata (`pushed_authorization_request_endpoint`); 27 tests in `OAuthPARTest`. (2026-05-28)

## Payments & Blockchain — v1.58 Compliance Provider

- [x] **v1.58-compliance-provider** — AML/KYC/sanctions compliance provider SPI: `payments/compliance/` SPI module (`ComplianceProvider` trait: `screenAml(entity)/verifyKyc(identity)/checkSanctions(party)/getStatus`; `ComplianceRequest/ComplianceResult/KycResult/SanctionsResult/AmlResult` types; `ComplianceError` sealed hierarchy); `payments/compliance-complyadvantage/` ComplyAdvantage REST v1 adapter (POST `/search`, HMAC-SHA256 webhook, 20+ tests); `payments/compliance-chainalysis/` Chainalysis KYT API adapter (POST `/transfers`, `GET /entities`, 15+ tests); `payments/compliance-mock/` MockComplianceProvider for testing (configurable pass/fail per check type, 20+ tests); 4 sbt subprojects. Spec: `docs/compliance-provider.md`.

## Language & Compiler — Spark extensions

- [x] **spark-lakehouse-l3-iceberg** — Spark Lakehouse Iceberg (L.3): `SparkGen.detectLakehouseFormats` extended to detect `.format("iceberg")` → auto-emit `org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:1.5.2` dep + `spark.sql.extensions` + `spark.sql.catalog.spark_catalog` Iceberg catalog config; `DefaultIcebergVersion = "1.5.2"` constant; `examples/spark-lakehouse-iceberg.ssc` (write/read Iceberg table, time-travel `asOf`, `MERGE INTO`); 6+ codegen tests. Spec: `docs/spark-lakehouse.md §L.3`.

## Frontend & Clients

- [x] **wallets-metamask-js** — Browser x402 MetaMask helper: `x402ClientJs` Scala.js artifact in package `scalascript.x402.client`; `Wallets.metaMask(network): Future[Wallet]` connects through `window.ethereum`, validates `eth_chainId`, signs EIP-712 via `eth_signTypedData_v4`, exposes `Wallets.metaMask(address, network)` for already-connected accounts, rejects CIP-8 on EVM wallets; 7 Node-backed Scala.js tests with stubbed `window.ethereum`. Spec: `BACKLOG.md §x402 — HTTP payment protocol`. ✓ Landed 2026-05-28.


## Language & Compiler — Secret Resolvers (cloud)

- [x] **secret-resolvers-cloud** — Cloud-provider secret resolver plugins: `AwsSmResolver` (AWS Secrets Manager via `software.amazon.awssdk:secretsmanager`, default creds chain, `AWS_REGION`); `GcpSmResolver` (GCP Secret Manager via `com.google.cloud:google-cloud-secretmanager`, ADC, `GOOGLE_CLOUD_PROJECT`); `AzureKvResolver` (Azure Key Vault via `com.azure:azure-security-keyvault-secrets`, `DefaultAzureCredential`). Each as a separate sbt subproject in `backend/sql-aws/`, `backend/sql-gcp/`, `backend/sql-azure/`; each registers via ServiceLoader; injectable protected methods for testability (11 AWS tests, 12 GCP tests, 12 Azure tests — 35 total). Spec: `docs/secret-resolvers.md §aws-secret §gcp-secret §azure-kv`. (2026-05-27)

## Payments & Blockchain — v1.57 New Payment Rails (APAC + Americas)

- [x] **v1.57.1-payment-rails-australia-npp** — Australia New Payments Platform (NPP/PayID) adapter: `runtime/std/payments-au-npp/` subproject; `AuNppProvider` implementing `BankRailsProvider` for `RailKind.AU_NPP`; PayID proxy resolution (phone/email/ABN to BSB+account via NPP participant gateway); NPP credit transfer via ISO 20022 pacs.008 over REST aggregator; `BankAccount.payid` additive field; `AuNppWebhookReceiver` (HMAC-SHA256 `X-NPP-Signature`; events: `npp.payment.credited/returned`); `AuNppPlugin` ServiceLoader; 35+ tests. Spec: new `docs/payment-rails-apac.md §AU_NPP`.

- [x] **v1.57.2-payment-rails-canada-eft** — Canada Interac e-Transfer + EFT adapter: `runtime/std/payments-ca-eft/` subproject; `CaEftProvider` implementing `BankRailsProvider` for `RailKind.CA_INTERAC` + `RailKind.CA_EFT`; Interac e-Transfer send (push by email/phone) via Interac Hub REST API; EFT (AFT credit/AFT debit, CPA Standard 005 format); `BankAccount.transitNumber` + `BankAccount.institutionNumber` additive fields; webhook events `interac.transfer.sent/reclaimed/expired`; 40+ tests. Spec: `docs/payment-rails-apac.md §CA_INTERAC`.

- [x] **v1.57.3-payment-rails-mexico-spei** — Mexico SPEI (Sistema de Pagos Electrónicos Interbancarios) adapter: `runtime/std/payments-mx-spei/` subproject; `MxSpeiProvider` implementing `BankRailsProvider` for `RailKind.MX_SPEI`; CLABE validation (18-digit control-digit); SPEI transfer via STP/Conekta aggregator REST API; `BankAccount.clabe` additive field; webhook events `spei.transfer.confirmed/rejected/returned`; 30+ tests. Spec: `docs/payment-rails-apac.md §MX_SPEI`. (2026-05-27)

## Payments & Blockchain — v1.57 FX Provider

- [x] **v1.57-fx-provider** — FX rate provider SPI deferred from v1.53/v1.55: `payments/fx/` SPI module with `FxProvider` trait (`getRate(from, to): Future[FxRate]`, `convert(money, to): Future[Money]`, `getRates(pairs): Future[Map[CurrencyPair, FxRate]]`); `FxRate(from, to, rate, mid, bid, ask, timestamp)`; `CurrencyPair` type; two adapters: (A) `EcbFxProvider` — ECB daily reference rates via `eurofxref/eurofxref-daily.xml`, cached with 1h TTL; (B) `OpenExchangeRatesFxProvider` — Open Exchange Rates API v6 (`/api/latest.json`), `APP_ID` env var; `FxPlugin` ServiceLoader; `FxMoneyConverter` standalone utility wrapping `FxProvider`; 76 tests (ECB XML parse, OER JSON parse, mock rate, convert round-trip, TTL expiry, missing-pair error, mock HTTP server). Spec: `docs/traditional-payments.md §FxProvider`. ✓ Landed (2026-05-27)

## Payments & Blockchain — v1.58 Tax Calculation Provider

- [x] **v1.58-tax-provider** — Tax calculation SPI + three adapters: `payments/tax/` SPI module (`TaxProvider` trait: `calculateTax/validateTaxId/getSupportedJurisdictions`; `TaxRequest/TaxQuote/TaxedLineItem/TaxAddress/TaxLineItem/JurisdictionTax/TaxIdValidation/Jurisdiction` types; `TaxError` sealed hierarchy; `TaxMoneyConverter` utility); `payments/tax-stripe/` Stripe Tax Calculations API v1 (form-encoded POST, Bearer Basic auth, idempotency key, format-only `validateTaxId`; 18 tests); `payments/tax-avalara/` Avalara AvaTax REST v2 (JSON POST, Basic accountNumber:licenseKey, `X-Avalara-Client` header, GET `/taxnumbervalidation`; 17 tests); `payments/tax-taxjar/` TaxJar SmartCalcs v2 (JSON POST, Bearer token, decimal amounts; 17 tests). All adapters use injectable HTTP methods for testability; ServiceLoader plugin registration; 4 sbt subprojects (`paymentsTax`, `paymentsTaxStripe`, `paymentsTaxAvalara`, `paymentsTaxJar`). ✓ Landed (2026-05-27)

## Frontend & Clients — Graph Storage Full-Stack Example

- [x] **graph-storage-fullstack** — Graph storage Phase 6: full-stack Electron/React frontend that queries server graph routes and caches selected results locally. New example `examples/graph-fullstack.ssc` with: (1) server graph routes using `graphs:` front-matter (`backend: embedded-tinkergraph`); REST endpoints `GET /api/graph/vertices`, `GET /api/graph/neighbors/:id`, `POST /api/graph/vertex`; (2) React frontend using generated `ApiClient` to call graph routes; client-side `IndexedDb.store[Module]("graph-cache")` caching of query results; cache-first read with background refresh; (3) `examples/graph-fullstack-rdf.ssc` RDF variant with `backend: rdf4j-memory` and SPARQL escape-hatch endpoint `POST /api/graph/sparql`; 20+ tests covering route handler codegen + graph facade + cache wiring. Spec: `docs/graph-storage.md §Phase 6`. (2026-05-27)

## Language & Compiler — API Tooling

- [x] **v1.29-dap-debugger** — Debug Adapter Protocol server (`runtime/backend/dap/`): all 5 phases — Phase 1 (TCP skeleton + initialize/launch/disconnect + `DapProtocol` framing + `DapServer` TCP accept); Phase 2 (`BreakpointRegistry` + `setBreakpoints` + stopped events); Phase 3 (step execution: `next`/`stepIn`/`stepOut`/`continue` + `StepMode` + depth-tracking); Phase 4 (variable inspection: `scopes`/`variables` + variablesReference tree); Phase 5 (stack frames: `stackTrace` + `DebugFrame` + source mapping). `ssc debug file.ssc [--port 5678]` CLI command. 5 test files (framing, phase1 lifecycle, breakpoint, step, variables, stack). Spec: `docs/dap-debugger.md §Phase 1–5`. (2026-05-21)

- [x] **pwa-plugin** — Progressive Web App support (`runtime/std/pwa-plugin/`): `pwa()` extern def in `std/pwa.ssc`; `PwaIntrinsics` registers `GET /manifest.json` (W3C Web App Manifest JSON) + `GET /sw.js` (cache-first service worker); configurable name/shortName/description/themeColor/backgroundColor/display/startUrl/icons/precache; works in interpreter + JVM codegen; `examples/pwa/pwa-demo.ssc`. Spec: `docs/pwa-plugin.md §Phase 1`. (2026-05-21)

- [x] **openapi-export** — Auto-derive OpenAPI 3.1 JSON from registered `route()` calls: `GET /_openapi.json` built-in endpoint (JSON document with paths/methods/path-parameters); `GET /_swagger` Swagger UI HTML page (CDN-linked). `OpenApiRuntime` registers both alongside `/_health`/`/_ready` when `serve`/`serveAsync` is called; walks `RouteRegistry.all`; extracts `:param` segments → `{param}` OpenAPI notation; inspects `FunV.paramTypes` for typed handlers; non-path typed params → query (GET/DELETE) or `requestBody` (POST/PUT/PATCH); type map String→string / Int+Long→integer / Double+Float→number / Boolean→boolean / other→object; `IntrinsicImpl.registerOpenApiDefaults()` hook wired from `HttpIntrinsics`; skips internal `/_*` routes. (2026-05-27)

## Language & Compiler — Spark extensions

- [x] **spark-streaming-f2-f4** — Spark Structured Streaming phases F.2–F.4: (F.2) `SparkGen.detectStreaming`, `awaitTermination()` shim, `examples/spark-streaming-rate-console.ssc`, 3+ codegen tests; (F.3) file source/sink + checkpointing comment emit, `examples/spark-streaming-file-parquet.ssc`; (F.4) `.format("kafka")` detection → auto-emit `spark-sql-kafka-0-10_2.13` dep, `examples/spark-streaming-kafka.ssc`. Smoke tests gated by `RUN_SPARK_INTEGRATION`/`RUN_SPARK_KAFKA`. Spec: `docs/spark-streaming.md §F.2–F.4`. ✓ Landed (2026-05-27)

- [x] **spark-lakehouse-l2** — Spark Lakehouse Delta Lake (L.2): `SparkGen.detectLakehouseFormats` detects `.format("delta")` → auto-emit `io.delta:delta-spark_2.13` dep + `spark.sql.extensions`/`spark.sql.catalog.spark_catalog` config; `SparkGen.lakehouseImports`; `examples/spark-lakehouse-delta.ssc` (write/read Parquet→Delta, merge-into); 6+ codegen tests. L.3 Iceberg and L.4 Hudi remain deferred. Spec: `docs/spark-lakehouse.md §L.2`. (2026-05-27)

- [x] **spark-catalog-g2-g4** — Spark Catalog phases G.2–G.4: (G.2) front-matter `spark-hive-metastore:`/`spark-warehouse:` keys → emit `spark-hive_2.13` dep + `enableHiveSupport()` + config keys; (G.3) `@TempView("name")` annotation rewriter → `createOrReplaceTempView`; (G.4) `Dataset.fromTable[T](name)` shim via `spark.table(name).as[T]`; `examples/spark-catalog-hive.ssc`; 8+ codegen tests. Spec: `docs/spark-catalog.md §G.2–G.4`. (2026-05-27)

- [x] **spark-mllib-m2-m5** — Spark MLlib phases M.2–M.5: (M.2) `SparkGen.containsMllib` detection → auto-emit `spark-mllib_2.13` dep; (M.3) `aenc_Vector` given in `SscSparkEncoders` shim via `UDTEncoder(new VectorUDT(), classOf[VectorUDT])`, gated on `usesMllib`; (M.4) `examples/spark-mllib-pipeline.ssc` (Tokenizer+HashingTF+LogisticRegression pipeline); (M.5) `examples/spark-mllib-model-save-load.ssc` (model.write.save + PipelineModel.load); 10+ codegen tests. Spec: `docs/spark-mllib.md §M.2–M.5`. (2026-05-27)

- [x] **v1.56-xslt** — XSLT transformation support (deferred from v1.55): `Markup.transform(xslt: String): Markup.Doc` via `javax.xml.transform.TransformerFactory` (JVM); `XsltTransformer` class with `apply(source: Markup.Doc, params: Map[String, String]): Markup.Doc`; `JvmMarkupCodec.transform` implementation; `MarkupCodec.transform` SPI hook (default: `UnsupportedOperationException`); `Feature.Xslt` capability flag declared in `InterpreterCapabilities`/`JvmCapabilities`; `CapabilityCheck` rejects `transform` on non-XSLT backends; `examples/xslt-transform.ssc`; 12+ tests. Spec: `BACKLOG.md §v1.55` (XSLT deferred note) + `docs/markup-core`.

## Payments & Blockchain

- [x] **x402-cardano-scalus-validator-simulator-tests** — Scalus script-context simulator tests for Cardano Scalus escrow validator happy path and rejection branches (signature, receiver/amount, validity range). Spec: `docs/x402-cardano-scalus.md §Phase 2`. (2026-05-27)

- [x] **v1.53.1-payments-spi-stripe** — `payments/money/` + `payments/webhook/` + `runtime/std/payments-plugin/` + `Feature.Payments` enum case + Stripe adapter (PaymentIntent / SCA / Customer / Vault / Subscription / Refund / Dispute / Webhook) + `examples/traditional-payments.ssc`. Closes `chargeCard()` placeholder from v1.38. Spec: `docs/traditional-payments.md §16`. (2026-05-27)

- [x] **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (OAuth2 client-cred, PayPal Orders v2, RSA-SHA256 webhook verify) + `runtime/std/payments-braintree/` (GraphQL API, XML REST plans/subscriptions, HMAC-SHA1 webhook). Spec: `docs/traditional-payments.md §11.2`. (2026-05-27)

- [x] **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (X-API-Key, Drop-in nonce, HMAC-SHA256 over notification fields, additionalData escape hatch) + `runtime/std/payments-checkout/` (sk_xxx, Frames token, HMAC-SHA256 over raw body). Spec: `docs/traditional-payments.md §11.3`. (2026-05-27)

- [x] **v1.53.4-payments-square** — `runtime/std/payments-square/` (Bearer token, Web Payments SDK nonce, HMAC-SHA1 over notification_url+body). Spec: `docs/traditional-payments.md §11.4`. (2026-05-27)

- [x] **v1.53.5-payments-vault-mandates-sca** — Cross-PSP `createMandate`/`getMandate` SPI methods (all 5 adapters); `ScaExemption` enum + `scaExemptions` in `CreateIntentRequest`; `mandateId` in `CreateIntentRequest` for off-session MIT; `networkToken` + `mandateId` fields in `StoredMethod`; PSD2 off-session flags wired in PayPal/Adyen/Stripe; `Mandate` extended with `customerId`/`vaultId`/`providerRef`. 9 new SPI-level tests in StripeProviderTest. Spec: `docs/traditional-payments.md §16.5`. (2026-05-27)

- [x] **v1.53.6-payments-mock-provider** — `runtime/std/payments-mock/` fully in-memory `MockProvider` + `MockWebhookReceiver`; `MockMode` enum (Succeed/Fail/RequireSCA) per effect group; all 16 SPI methods; `recorded*` inspection helpers + `reset()`; `PaymentEffect` enum added to SPI; 41 tests. Spec: `docs/traditional-payments.md §16.6`. (2026-05-27)

- [x] **v1.53.7-payments-webhook-cluster** — `payments/webhook-redis/` `RedisSeenKeyStore` (Lettuce SET NX EX, configurable prefix + timeout, 8 tests) + `payments/webhook-postgres/` `PostgresSeenKeyStore` (HikariCP INSERT ON CONFLICT DO NOTHING, auto-CREATE TABLE, `purgeExpired()`, H2 test suite, 9 tests). Both implement `SeenKeyStore` SPI; cluster-safe idempotency for multi-instance deployments. Spec: `docs/traditional-payments.md §16.7`. (2026-05-27)

- [x] **v1.54-bank-rails-spec** — Spec doc `docs/bank-rails.md`: SEPA Credit Transfer + Direct Debit, ACH (credit/debit via Nacha), Pix instant payments (Brazil), FedNow instant payments (US). Cover: `BankRailsProvider` SPI (6 methods: `initiateTransfer / getTransfer / cancelTransfer / initiateDirectDebit / getDirectDebit / webhookReceiver`), `BankTransfer` / `DirectDebitMandate` types, idempotency (reuse `IdempotencyKey` from v1.53), settlement timing model (T+0 / T+1 / T+2), webhook event taxonomy per rail. Implementation phases v1.54.1–v1.54.4 in the spec. Spec: `docs/traditional-payments.md §12` (deferred note). (2026-05-27)

- [x] **v1.54.1-bank-rails-sepa** — `payments/bank-rails/` SPI (BankRailsProvider, BankTransfer, DirectDebitMandate, RailKind, BankRailsEvent, RCode/CCode) + `runtime/std/payments-sepa/` (PAIN.001 CT + PAIN.008 DD XML builder; HMAC-SHA256 webhook receiver; SepaProvider; SepaPlugin; Feature.BankRails; 30 tests; example). Spec: `docs/bank-rails.md §v1.54.1`. (2026-05-27)

- [x] **v1.54.2-bank-rails-ach** — `payments/bank-rails/` (BankRailsProvider SPI + core types) + `runtime/std/payments-ach/` (NachaFile 94-char flat-file builder, same-day ACH, R/C-codes, HMAC webhook, 28 tests). Spec: `docs/bank-rails.md §v1.54.2`. (2026-05-27)

- [x] **v1.54.3-bank-rails-pix** — `runtime/std/payments-pix/` (Pix via DICT API + PSP REST; QR Code Static/Dynamic; webhook `pix.received`). Spec: `docs/bank-rails.md §v1.54.3`. ✓ Landed 2026-05-27.

- [x] **v1.54.4-bank-rails-fednow** — `runtime/std/payments-fednow/` (FedNow instant via ISO 20022 over FedLine; `pacs.008` credit transfer; `pacs.002` status; webhook adapter). Spec: `docs/bank-rails.md §v1.54.4`. ✓ Landed 2026-05-27.

- [x] **blockchain-bitcoin** — `payments/blockchain/bitcoin/`: secp256k1 ECDSA (RFC 6979), BIP-143 SegWit sighash, BIP-340 Schnorr + BIP-341 Taproot tweakedKey, P2WPKH bech32 + P2TR bech32m addresses, PSBT BIP-174 builder/signer/finalizer, `BitcoinChainAdapter` + `ChainId.BitcoinMainnet/Testnet`; 45 tests. ✓ Landed 2026-05-27.

- [x] **blockchain-cosmos** — `payments/blockchain/cosmos/`: secp256k1 + ed25519, StdSignDoc Amino, bech32 HRP (cosmos/osmo/juno), `CosmosChainAdapter`, `ChainId.CosmosHub/Osmosis/Juno`, `BlockchainProvider` ServiceLoader; 41 tests. ✓ Landed 2026-05-27.

- [x] **v1.55-international-bank-rails-spec** — Spec doc `docs/international-bank-rails.md`: SWIFT MT103 + ISO 20022 pacs.008 (CBPR+), SEPA Instant (SCT Inst), UK FPS + BACS DD + CHAPS, India UPI, Japan Zengin, Singapore PayNow. 9 new `RailKind` cases, `Uetr`/`ChargeBearer`/`GpiHop` SWIFT types, `BankAccount` additive fields, 28 new `BankRailsEvent` cases, 8 new `BankRailsError` cases, settlement timing table, 8 implementation phases v1.55.1–v1.55.8. (2026-05-27)

- [x] **v1.55.1-international-swift** — `payments/bank-rails/` type additions (Uetr, ChargeBearer, GpiHop, BankTransfer.gpiTrail/uetr/chargeBearer, RailKind.SWIFT_MT103/SWIFT_PACS008 + 7 other v1.55 cases, BankAccount.bic + 5 other v1.55 fields) + `runtime/std/payments-swift/` (SwiftProvider, SwiftMt103Builder, SwiftPacs008Builder, GpiTracker, SwiftWebhookReceiver, SwiftPlugin; 65 tests). Spec: `docs/international-bank-rails.md §v1.55.1`. (2026-05-27)

- [x] **v1.55.2-sepa-instant** — Extend `runtime/std/payments-sepa/`: `RailKind.SCT_INST`, `SepaPainXml.buildSctInstPacs008`, `SctInstSettled/SctInstRejected` events, `SctInstTimeout` error; 19 new tests (49 total). Spec: `docs/international-bank-rails.md §v1.55.2`. (2026-05-27)

- [x] **v1.55.3-uk-faster-payments** — `runtime/std/payments-uk-fps/` (UkFpsProvider, ConfirmationOfPayee) + `RailKind.UK_FPS` + `BankAccount.sortCode` + CoP name-check; 47 tests. Spec: `docs/international-bank-rails.md §v1.55.3`. (2026-05-27)

- [x] **v1.55.4-uk-bacs** — `runtime/std/payments-uk-bacs/` (UkBacsProvider, BacsFile, AuddisFile) + `RailKind.UK_BACS_DD` + AUDDIS/ARUDD flows; 61 tests. Spec: `docs/international-bank-rails.md §v1.55.4`. (2026-05-27)

- [x] **v1.55.5-uk-chaps** — `runtime/std/payments-uk-chaps/` (UkChapsProvider, ChapsPacs008Builder) + `RailKind.UK_CHAPS`; 25+ tests. Spec: `docs/international-bank-rails.md §v1.55.5`.

- [x] **v1.55.6-india-upi** — `runtime/std/payments-india-upi/` (UpiProvider, push + collect, RSA-SHA256 webhook) + `RailKind.IN_UPI` + `BankAccount.upiVpa`; 63 tests. Spec: `docs/international-bank-rails.md §v1.55.6`. (2026-05-27)

- [x] **v1.55.7-japan-zengin** — `runtime/std/payments-japan-zengin/` (ZenginProvider, kana constraint) + `RailKind.JP_ZENGIN` + `BankAccount.zenginBankCode/zenginBranchCode`; 59 tests. Landed 2026-05-27.

- [x] **v1.55.8-singapore-paynow** — `runtime/std/payments-sg-paynow/` (PayNowProvider, proxy resolution) + `RailKind.SG_PAYNOW` + `BankAccount.paynowProxy`; 30+ tests. Spec: `docs/international-bank-rails.md §v1.55.8`. (2026-05-27)

## Database

- [x] **secret-resolvers-jdk** — In-tree secret resolver plugins for `backend/sql`: `VaultSecretResolver` (HashiCorp Vault KV v1/v2 via `java.net.http.HttpClient` + `VAULT_ADDR`/`VAULT_TOKEN`/`VAULT_NAMESPACE`), `DopplerSecretResolver` (Doppler REST API via JDK HttpClient + `DOPPLER_TOKEN`), `OpSecretResolver` (1Password CLI subprocess `op read`), `PassSecretResolver` (Unix password-store CLI subprocess `pass show`); all implement `scalascript.sql.SecretResolver` SPI; ServiceLoader registration; 26 tests (mock HTTP server for vault/doppler, subprocess hooks for op/pass, scheme dispatch, missing env errors). Spec: `docs/secret-resolvers.md §vault §doppler §op §pass`. (2026-05-27)

## Native Platform

- [x] **v1.48.2-swiftui-ios-run** — `ssc run --target ios` (iOS Simulator) (2026-05-26)

- [x] **v1.48.3-swiftui-device-run** — `ssc run --target ios --device` (real device via ios-deploy) (2026-05-26)

- [x] **v1.48.4-ios-package** — `ssc package --target ios` → signed .ipa (2026-05-26)

- [x] **v1.48.5-ios-publish** — `ssc publish --target ios` (TestFlight + App Store via fastlane) (2026-05-26)

- [x] **v1.49-macos-distribution** — `ssc package/publish --target macos` (notarize + DMG + Mac App Store) (2026-05-26)

## Distribution & Tooling

- [x] **v1.52.1-deploy-plugin** — `runtime/std/deploy-plugin/` (four-file SPI layout) + `Manifest` AST `deploy`/`groups`/`environments`/`state` fields + `ssc deploy` CLI stub + multi-target orchestrator core (DAG resolver, parallel/sequence/pipeline executor, output→input wiring, failure handler, event stream) + local subprocess adapter + `ssc deploy plan`/`--dry-run`/`envs` + `examples/deploy.ssc`. Spec: `docs/deploy.md §16`. (2026-05-27)

- [x] **v1.52.2-deploy-container** — `DockerfileGenerator` (4 base-image recipes: temurin/distroless/node/nginx per ArtifactKind; HEALTHCHECK, build-args, labels) + `ContainerTarget` (all 7 SPI verbs; buildctl→buildx→docker fallback; multi-platform; digest rollback; docker inspect status; docker logs) + `TargetFactory` (kind→target resolver) + `ArtifactRegistry` OciImage case. Spec: `docs/deploy.md §6.1`. 14 new tests; 36 total. (2026-05-27)

- [x] **v1.52.3-deploy-k8s** — `K8sManifestGenerator` (Deployment+Service+Ingress+ConfigMap+Secret; liveness/readiness probes; PreStop drain; blue-green slot labels) + `K8sTarget` (all 7 SPI verbs + switch/promote; kubectl subprocess; blue-green; dry-run) + `TargetFactory` k8s case. Spec: `docs/deploy.md §6.2`. 17 new tests; 53 total. (2026-05-27)

- [x] **v1.52.4-deploy-traditional** — `SystemdUnitGenerator` + `SshSystemdTarget` (SSH+SCP+systemd) + `RsyncTarget` (rsync --delete) + `SftpTarget` (sftp batch upload) + TargetFactory transport dispatch. Spec: `docs/deploy.md §6.5`. 18 new tests; 71 total. (2026-05-27)

- [x] **v1.52.5-deploy-static** — `StaticTarget` (Vercel/Netlify/Cloudflare Pages/GitHub Pages; CLI-first with API fallback; HTTP GET status; dry-run) + TargetFactory `"static"`. Spec: `docs/deploy.md §6.4`. 9 new tests; 80 total. (2026-05-27)

- [x] **v1.52.6-deploy-faas** — `FaasTarget` (AWS Lambda LambdaZip+alias; Cloudflare Workers wrangler; GCP Cloud Run gcloud; Vercel Functions vercel CLI; all dry-run; rollback via alias version) + TargetFactory `"faas"/"lambda"/"serverless"`. Spec: `docs/deploy.md §6.3`. 11 new tests; 91 total. (2026-05-27)

- [x] **v1.52.7-deploy-state-backends** — `JsonState` (zero-dep ser/de) + `LocalFileStateBackend` (file + TTL lock) + `S3StateBackend` (aws s3api + mtime lock) + `ConsulStateBackend` (HTTP KV + session lock) + `EtcdStateBackend` (etcdctl + lease lock) + `StateBackendFactory` (dispatch + production enforcement) + `StateMigrator` (dry-run migrate). Spec: `docs/deploy.md §3.5/§10.2`. 14 new tests; 105 total. (2026-05-27)

- [x] **v1.50-native-p1-snakeyaml** — Replace snakeyaml with pure-Scala frontmatter parser (2026-05-27)

- [x] **v1.50-native-p2-graalvm** — GraalVM native-image build for `ssc` (2026-05-27)

- [x] **v1.50-native-p3-plugin-bridge** — `ssc-plugin-host.jar` + automatic bridge (existing plugins unchanged) (2026-05-27)
  _New `ssc-plugin-host` sbt subproject: `SubprocessHost` main loads any existing plugin JAR via URLClassLoader + ServiceLoader + wire protocol. Native `ssc` auto-spawns it when `--plugin foo.jar` given. Plugin authors change nothing. Spec: `BACKLOG.md §Phase 3`._

- [x] **v1.50-native-p4-plugin-guide** — Plugin-author guide: compile your plugin to a native binary via GraalVM native-image (docs only, no core changes) (2026-05-27)

- [x] **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections without OOM (2026-05-27)

- [x] **watch-100ms** — Watch cycle optimization: `ssc --watch rest-api.ssc` target < 100 ms per cycle (2026-05-27)
  _Added `ssc watch-bench` reload harness over a temporary source copy, plus hot-path hashing fixes: ParseCache / SectionSnapshot use direct hex encoding and incremental typer reuses precomputed section hashes instead of hashing retyped sections twice. Spec: `BACKLOG.md §Compiler — AST cache`._

- [x] **sbt-interop-plugin** — Build-tool integration: `sbt-scalascript-interop` plugin + Mill module trait + scala-cli directive (2026-05-27)
  _`ssc generate-facade` CLI command; sbt plugin with 4 scripted tests; Mill trait + scala-cli docs. Source: `tools/sbt-plugin/`. Spec: `docs/scala-interop.md §6`._

- [x] **ssc-check** — Standalone `ssc check <file>` CLI command: type-check without codegen or linking; exit 0 = clean, exit 1 = errors with structured output; `--json` flag for machine-readable diagnostics; `--watch` mode re-checks on save (reuse `ParseCache` + incremental typer); designed for CI pre-commit hooks and IDE integrations. Spec: `BACKLOG.md §Tooling — ssc check standalone type-checker`. (2026-05-27)

- [x] **lsp-phase3** — LSP Phase 3: `textDocument/codeAction` (quick-fix for unknown-name + unused-import diagnostics); `textDocument/formatting` (indent normalisation, trailing-whitespace strip); `textDocument/inlayHint` (inferred types on `val` bindings, effect annotations); `workspace/didChangeWatchedFiles` (auto-reload `.ssc` on disk change without client re-open). Spec: `BACKLOG.md §LSP server`. ✓ Landed 2026-05-27.

- [x] **js-tree-shaking** — Dead-code elimination in JS output: mark reachable symbols from `@main` / exported defs; emit only reachable `const`/`function` declarations; `--no-tree-shake` escape hatch; `ssc build --stats` reports removed vs kept symbol counts. Spec: `BACKLOG.md §Generated code — JS tree-shaking`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-js** — Ledger hardware wallet Scala.js integration: `wallet-vault-ledger-js` subproject; WebHID transport (`navigator.hid.requestDevice`) for browser; APDU framing over HID packets; Ethereum app signer (secp256k1 + EIP-712 typed-data); Cardano app signer (CIP-8 framing); connect/disconnect lifecycle; `LedgerVault` implementing `Vault` SPI; 13 tests via mocked HID device. Spec: `BACKLOG.md §wallet-vault-ledger-js`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-solana** — Ledger Solana-app signer (JVM): `payments/wallet/vault-ledger-solana/` — `SolanaApp` object (CLA=0xE0, INS=0x04 SIGN_TRANSACTION, INS=0x05 SIGN_OFFCHAIN_MESSAGE, default HD path `m/44'/501'/0'/0'`); `LedgerSolanaVault` implementing `Vault` SPI, routes `Curve.Ed25519` to `SolanaApp`; ed25519 signature bytes (64 B) returned as `Array[Byte]`; `AppSwitchRequired` guard via `Dashboard.getAppName`; `MockTransport` re-used from `wallet-vault-ledger`; 10+ tests (path encoding, single-packet sign, chunked sign, wrong-app error, Vault.getSigner routing). Spec: `BACKLOG.md §Phase 7 — Solana-app signer`. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-bitcoin** — Ledger Bitcoin-app signer (JVM): `payments/wallet/vault-ledger-bitcoin/` — `BitcoinApp` object (CLA=0xE1, new protocol v2+, INS=0x00/0x02/0x03/0x04); `LedgerBitcoinVault` implementing `Vault` SPI; `LedgerBitcoinRawSigner`; `AppSwitchRequired` guard via `Dashboard.getAppName`; `MockTransport` re-used; 14 tests. ✓ Landed 2026-05-27.

- [x] **wallet-ledger-cardano** — Ledger Cardano-app signer (JVM): `payments/wallet/vault-ledger-cardano/` — `CardanoApp` object (CLA=0xD7, INS=0x10 GET_EXTENDED_PUBLIC_KEY, INS=0x21 SIGN_TX with CIP-8 framing); `LedgerCardanoVault` implementing `Vault` SPI, routes `Curve.Ed25519` + Cardano-prefix HD path; CIP-8 COSE_Sign1 Sig_Structure builder (hand-rolled CBOR, no deps); `AppSwitchRequired` guard; `MockTransport` re-used; 11 tests. ✓ Landed 2026-05-27.

- [x] **ssc-profile** — `ssc profile <file.ssc>` CLI command: instrument parse + typecheck + codegen phases with wall-clock + allocation counters; output flame-graph-ready JSON (Brendan Gregg folded stacks format) to `profile.json`; `--top=N` flag prints N hottest functions to stdout; `--compare <baseline.json>` shows regression vs prior run. Spec: `BACKLOG.md §New tool — ssc profile file.ssc`. ✓ Landed 2026-05-27.

- [x] **x402-cardano-scalus-completion** — Complete x402 Cardano Scalus escrow settlement Phases 3/5/6: ✓ Landed 2026-05-27. (Phase 3) `ReferenceScriptDeployer` helper that builds a bloxbean Tx publishing the compiled Plutus script as a Cardano reference script (deploy-once; writes txHash+outputIndex to a config field), 2 tests; (Phase 5) full round-trip integration test `ScalusRoundTripTest` exercising Scalus client CIP-8 sign → `CardanoProvider.Scalus` verify + settle plan construction end-to-end with mock Blockfrost HTTP; (Phase 6) `EscrowDeposit.build(payerWallet, req)` — constructs and signs a bloxbean deposit Tx locking ADA at the script address with `EscrowDatum` (payerKeyHash, receiverHash, amount, validBefore, refundAfter, claimMessageHash), 3 tests; `examples/x402-cardano-scalus.ssc` full walkthrough showing the complete Preprod flow; update `BACKLOG.md §Phase 9 follow-up` to point at the new Scalus flows. Module: `payments/x402/facilitator-cardano-scalus/`. Spec: `docs/x402-cardano-scalus.md §Phase 3–6`. (2026-05-27)

- [x] **wallet-solana-standard-js** — Scala.js `registerWallet` integration for `wallet-connector-wallet-std`: add `WalletStandardJs` object in `wallet-connector-wallet-std/js/src/` implementing `window.standard.wallets` registration protocol (announce event via `window.dispatchEvent(new CustomEvent("wallet-standard:register-wallet", { detail: ... }))`); `WalletInfo` JS value shape (name, icon, chains, features); `StandardWalletConnectorJs` bridging `WalletConnectorWalletStd` to the browser registry; `WalletStandardJsTest` suite (6 tests: announce event dispatched on register, features shape, chains list, connect handler wiring, signMessage wiring, signTransaction wiring) via Node.js `global.window` property stub. Unblocked: wallet-spi Scala.js cross-compile Stage 1–6 all landed 2026-05-20. Spec: `BACKLOG.md §Phase 5 — Solana DappConnector`. (2026-05-27)

## Language & Compiler

- [x] **markup-lang-xml** — Wire `xml"..."` into language runtime: add `Lang.Xml = "xml"` / `isXml` to `Lang.scala`; extend `SectionRuntime` with `runXmlBlock` (render `${…}` → XML-escaped → parse → `MarkupV`); generalise `renderStringBlock` to accept `escapeFn: String => String`; add `Value.MarkupV(doc: Markup.Doc)` case to `Value.scala`; 8+ tests: fenced `` ```xml `` block binds `MarkupV`, element text escaping, splice of string value, splice of `Markup.Node`. Pre-written test skeleton in `runtime/backend/interpreter/src/test/scala/scalascript/SectionXmlBlockTest.scala`. Spec: `BACKLOG.md §v1.55.2`.

- [x] **markup-feature-backend** — `Feature.Markup` + `Backend.markupCodec` SPI + JVM SAX codec: add `case Markup` to `Feature.scala`; `def markupCodec: Option[MarkupCodec] = None` to `Backend.scala`; `JvmMarkupCodec` in `runtime/backend/interpreter/` using `javax.xml.parsers.SAXParser`; declare `Feature.Markup` in `*Capabilities.scala` for interpreter+JVM backends; `CapabilityCheck` rejects `xml"..."` on backends lacking `Feature.Markup`; 16 tests. Spec: `BACKLOG.md §v1.55.3`. (2026-05-27)

- [x] **markup-compile-check** — Compile-time `xml"..."` well-formedness: `lang/core/.../transform/MarkupInterpolatorCheck.scala` joins interpolation parts with placeholder text, runs `PureMarkupCodec.parse` at compile time, emits `Diagnostic.XmlParseError` on malformed input; 8+ tests (malformed tag, unclosed element, mismatched tags, valid doc passes). Spec: `BACKLOG.md §v1.55.4`. (2026-05-27)

- [x] **markup-element-literal** — Opt-in `<foo bar={expr}/>` element-literal syntax: `lang/core/.../transform/MarkupLiteralLower.scala`; `import scalascript.markup.*` enables; `<name attrs>{children}</name>` → `Markup.Element(…)` constructors; 10+ tests. Spec: `BACKLOG.md §v1.55.5`. (2026-05-27)

- [x] **markup-xsd-sepa-refactor** — XSD validation + refactor SEPA/FedNow XML: `JvmMarkupCodec.validate(doc, xsd)` via `javax.xml.validation`; rewrite `SepaPainXml` PAIN.001/008 + FedNow pacs.008/002 from string concat to `xml"..."` interpolator; golden-file regression suite (12 PAIN.001 fixtures). Spec: `BACKLOG.md §v1.55.6`. (2026-05-27)

- [x] **markup-config-js** — `.xml` ConfigParser + JS/Node markup codecs: `ConfigParser.Format.Xml` + detectFormat; `XmlConfigParser.scala` (XML → `ConfigValue.Object`); `runtime/std/markup-js/` (JS DOMParser/XMLSerializer); `runtime/std/markup-node/` (Node @xmldom/xmldom); `markupCore` cross-compiled (JVM+JS); 41 tests (16 XmlConfigParser + 11 markup-js + 14 markup-node). (2026-05-27)

- [x] **v2.1.6-dstream-connectors** — `Kafka`/`Files`/`FileFormat`/`Jdbc`/`Pulsar`/`Kinesis` stubs in all 4 code-gen shims (Spark, KafkaStreams, Flink, Beam) + native interpreter intrinsics; `containsConnector` in each generator; `DSource.fromDataset` bridge; SparkGen Kafka dep extended; `DSink[T] = Any` alias; 14 new tests. Spec: `docs/distributed-streams.md §6`. (2026-05-27)

- [x] **v2.1.5-dstream-flink** — `runtime/backend/flink/` module: `FlinkGen` (Flink DataStream API shim, `_flinkEnv()` helper), `BeamGen` (Apache Beam Java SDK shim, `_createBeamPipeline()`, runner dep auto-selection for DirectRunner/FlinkRunner/SparkRunner), `FlinkBackend`/`BeamBackend` SPI adapters, `FlinkCapabilities`/`BeamCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration, `PipelineOptions` case class; 30 new `FlinkGenTest` tests. Spec: `docs/distributed-streams.md §9.4–9.5`. (2026-05-27)

- [x] **v2.1.4-dstream-kafka** — `runtime/backend/kafka-streams/` module: `KafkaStreamsGen` (shim pattern from v2.1.3, adds `Backend.KafkaStreams`/`Backend.Kafka`, topology helpers, extended `containsDStream` for `Window.*`/`WatermarkStrategy.*`/`Trigger.*`), `KafkaStreamsBackend` SPI adapter, `KafkaStreamsCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration; 22 new `KafkaStreamsGenTest` tests. Spec: `docs/distributed-streams.md §9.3`. (2026-05-27)

- [x] **v2.1.3-dstream-spark** — `SparkGen` DStream shim: `containsDStream` detection + `dstreamSparkShim` emission; full pipeline DSL backed by `Seq[Any]` for bounded `InMemory` sources; `Feature.DistributedStreams` in `SparkCapabilities`; 14 new `SparkGenTest` tests. Spec: `docs/distributed-streams.md §9.2`. (2026-05-27)

- [x] **v2.1.2-dstream-native-unbounded** — Processing-time `window(Window.fixed/sliding/session/global)`, `withTrigger`, `withAllowedLateness`, `withWatermark(WatermarkStrategy.atEnd)`, `timerProcessing(d)(f)`; DirectRunner provides `EventTime` + `WatermarkPerfect` in v2.1.2. Spec: `docs/distributed-streams.md §13 v2.1.2`. (2026-05-27, 30 tests green)

- [x] **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` types + native bounded backend (wraps `Dataset[T]` partitions); `DirectRunner` test backend; `Feature.DistributedStreams` flag; `examples/distributed-streams.ssc`. Spec: `docs/distributed-streams.md §13`. (2026-05-27, 23 tests green)

- [x] **v1.51.4-streams-sse-ws** — `Source.fromSse`/`Sink.toSseStream` in HTTP plugin + `Source.fromWebSocket`/`Sink.toWsRoom` in WS plugin + `mapAsync(n)(f)` + `.recover(pf)`/`.mapError(f)`/`Source.bracket`. Spec: `docs/streams.md §14.4`. (2026-05-27)

- [x] **v1.51.5-streams-buffer** — `.buffer(n, OverflowStrategy)` (Drop/Fail/Backpressure/DropHead) + `.throttle(Rate)` + `.debounce(Duration)` + `Source.signal(sig)` current-value adapter in the interpreter path. Spec: `docs/streams.md §14.5`. (2026-05-27)

- [x] **v1.51.5b-streams-clock-ui-signals** — Interpreter wall-clock `.throttle`/`.debounce`, live frontend `ReactiveSignal` subscriptions for `Source.signal`, reverse `sig.bind(source)`, and Swing/JavaFX runtime state synchronization. SwiftUI platform-native stream bridging split to `v1.51.5c-streams-swiftui-bridge`. Spec: `BACKLOG.md §Streams v1.51.5 follow-ups`. (2026-05-27)

- [x] **v1.51.5c-streams-swiftui-bridge** — Platform-native SwiftUI stream/signal bridge for generated `@State` values, matching the interpreter/JVM desktop `Source.signal` + `sig.bind(source)` behavior where practical. Spec: `docs/streams.md §8.5`. (2026-05-27)

- [x] **v1.51.6-streams-typed** — Type-safe algebraic-effect integration: `Stream[A]` parameterized effect op (`EffectOp(name, args)` type-system extension), 4 ops (`emit/complete/error/request`), `runStream[A, R]: (Source[A], R)` canonical form, cross-backend parity (interpreter + JS + JVM all return the tuple). Spec: `docs/streams.md §14.6`. (2026-05-28)

- [x] **v1.51.3-streams-flow-sink** — `Flow[A, B]` + `Sink[A]` types; `.to(sink)` / `.via(flow)` routing; combining operators `zip` / `merge` / `concat` / `broadcast(n)` / `balance(n)` (queue-per-subscriber); `groupBy(key)` + `mergeSubstreams`; interpreter intrinsics in `StreamsIntrinsics.scala`; JS lowering in JsGen. Spec: `docs/streams.md §14.3`. (2026-05-27)

- [x] **v1.51.2-streams-js-backend** — JS `async function*` emit path: `_makeAsyncStream` helper in JsGen preamble; `stream { body }` → `_makeAsyncStream(async function*() { body })`; `emit(x)` → `yield x`; consumer iteration → `for await`; `Feature.Streams` in `JsCapabilities`; full operator set (map/filter/take/drop/flatMap/concat/zip) on async iterators. Spec: `docs/streams.md §14.2`. (2026-05-27)

- [x] **v1.51.1-streams-plugin** — `runtime/std/streams-plugin/` + `Source` core (`map`/`filter`/`runForeach`/`runFold`/`runToList`), interpreter + JVM only, `Feature.Streams` flag, `examples/streams.ssc`. Spec: `docs/streams.md §14`. (2026-05-27, commit 7f9a0f02)

- [x] **v1.12.1-effects-types** — Add `EffectRow` to `SType`, Rémy-style row unification, `!` operator in `TypeParser`, `multi effect` keyword, handler discharge in typer, `EffectAnalysis` verifier mode, §9 diagnostics. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §3, §4, §5.1, §13 v1.12.1._

- [x] **v1.12.2-effects-runtime** — JS `function*`/`yield` fast path for one-shot effects; coroutine VT wiring on JVM/interpreter; dynamic one-shot-violation check; cross-backend parity tests.
  _Spec: `docs/algebraic-effects.md` §5.3, §13 v1.12.2._

- [x] **v1.12.3-effects-stdlib** — Re-type `runLogger`/`runRandomSeeded`/etc. with discharge signatures; add `Reader[R]` capability; add `NonDet` multi-shot exemplar; `examples/algebraic-effects.ssc`; promote `EffectAnalysis` warnings to errors. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §6, §8.2, §13 v1.12.3._

---

## Done

- [x] **v1.12-spec** — Typed Algebraic Effects spec (`docs/algebraic-effects.md`) — design doc + go/no-go decision (2026-05-26)
- [x] **v1.46-phase1-metadata** — `apiClients:` front-matter → `ApiClientDecl` AST
- [x] **v1.46-phase2-swing-client** — JVM/Swing in-process callable clients
- [x] **v1.46-phase3-http-client** — JS HTTP client + `awaitClient` async
- [x] **v1.46-phase4-shared-codecs** — shared `_ssc_typed_json_encode/decode` facade
- [x] **v1.46-phase5-validation** — static path-param validation warnings
- [x] **v1.46-phase6-auth** — auth/custom header injection
- [x] **v1.46-phase6-per-call-headers** — per-call header overrides
- [x] **v1.46-phase6-retry** — retry policy (`_ssc_api_set_retry`)
- [x] **v1.46-phase6-cancel** — cancellation tokens
- [x] **v1.46-phase7-sse** — SSE streaming (EventSource/fetch JS; HttpURLConnection JVM)
- [x] **v1.46-ws-subscriptions** — WebSocket subscriptions (native WebSocket JS; java.net.http.HttpClient JVM; `_SscWsHandle` with `send()`+`close()`)
- [x] **v1.46-phase5-derivation** — Route derivation: `RouteDeriver` auto-generates `ApiClientDecl` from `route()` calls (2026-05-26)
- [x] **v1.46-pagination** — Pagination helpers: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` on JVM + JS (2026-05-26)
- [x] **v1.38-payment-request** — Payment Request API (browser + server) — Complete (2026-05-26)
- [x] **x402-http-payment** — x402 HTTP payment protocol — All phases landed (2026-05-19/20)
- [x] **blockchain-spi** — Blockchain SPI — All phases landed (2026-05-19/20)
- [x] **wallet-key-mgmt** — Wallet key management + dApp connectivity — Landed (2026-05-20)
- [x] **mcp-x402-wallet** — MCP × x402 × Wallet agentic payments — All 7 phases landed (2026-05-19)
- [x] **micropayment-platform** — Micropayment platform — All phases landed (2026-05-19/20)
- [x] **v1.26-sql-jdbc** — `sql` fenced code blocks (JDBC) — v1.26 + v1.26.1 + v1.26.2 landed (2026-05-21)
- [x] **v1.27-browser-sql** — Browser-side SQL (sql.js / DuckDB-Wasm) — v1.27 landed (2026-05-21)
- [x] **v1.30-side-sql** — `@side=client|server` for SQL blocks — v1.30 complete (2026-05-21)
- [x] **v1.31-transaction** — `transaction` fenced block — v1.31 landed (2026-05-21)
- [x] **v1.48-swiftui** — SwiftUI Native Frontend (iOS + macOS) — Phases 1–3 all landed (2026-05-26)
- [x] **v1.48.1-swiftui-run** — `ssc run --target macos` + swift build in package; target renamed desktop-macos → macos (2026-05-26)
- [x] **v1.30-repl-web-mode** — REPL web-aware mode + `mount()` intrinsic — All 8 phases landed: Routes refactor, `mount()` intrinsic, `:serve`/`:stop`/`:clear`, `:mount`, `:load`/`:reload`/`:unmount`, `:routes`/`:http`/`:call`, typed handlers, `:help`/`:set` (2026-05-26)
- [x] **v2.0-sep-compile** — Separate compilation — ALL-DELIVERABLES-LANDED (2026-05-20)
- [x] **v2.0-cross-platform-smoke** — Cross-platform portability for v2.0 artifact pipeline: `InterfaceExtractor.normalizeLineEndings` + `sourceFileHash` (CRLF→LF before hashing); `ModuleGraph.isStale/isJvmStale/isJsStale` updated; 13 tests in `CrossPlatformSmokeTest`; `docs/v2.0-scale-benchmark.md` updated. (2026-05-28)
- [x] **interpreter-ergonomics** — Interpreter ergonomics — All 3 items landed (v1.13 + 2026-05-19)
- [x] **wasm-backend-phase1** — WASM backend: scalascript/ssc block support (Phase 1), integration tests + example (Phase 2), `//> using dep` hoisting + HTTP Fetch example (Phase 3) — All 3 phases landed (2026-05-26)
- [x] **v1.60.1-tuple-monoid-types** — `SType.Unit = Tuple(Nil)`; `tupleConcat` smart constructor; `++` in type parser; 1-tuple `(A,)` surface syntax; 49 tests. ✓ Landed 2026-05-28.
- [x] **v1.60.2-tuple-monoid-values** — `TupleV ++ TupleV` in `DispatchRuntime`; `_tupleConcat` JS helper (sets `_isTuple`); JVM `_tupleConcat` with `scala.Tuple.fromArray`; 4 interpreter + 3 JsGen tests. ✓ Landed 2026-05-28.
- [x] **v1.60.3-tuple-monoid-docs** — `algebraic-effects.md` §8.3 "Unified runner signature" with `Out(E) ++ (R,)` table; `streams.ssc` tuple monoid section; `BACKLOG.md`/`CHANGELOG.md` v1.60 closed. ✓ Landed 2026-05-28.
- [x] **v1.60.4-tuple-bareconcat** — 1-tuple ≅ element equivalence + bare-value `++`: `(A,B) ++ C = (A,B,C)`, `C ++ (A,B) = (C,A,B)`, `bare ++ bare = 2-tuple`, `() ++ v = v`. `DispatchRuntime` (5 new cases), `_tupleConcat` JS/JVM (Array.isArray guard), 5 InterpreterTest + 2 JsGenStreamsTest. Docs: `tuple-monoid.md` §2, `user-guide.md`, `algebraic-effects.md` §8.3, `streams.ssc`, `streams.md`. ✓ Landed 2026-05-28.

## v1.61 — Performance & Memory Optimization

- [x] **v1.61.0-bench** — Benchmark infrastructure: 8-workload corpus (`bench/corpus/`), `bench/run.sc` (scala-cli timing harness), `bench/BASELINE.md`, `runtime/backend/interpreter-bench` (sbt-jmh module), `scripts/bundle-size.sh`. ✓ Landed 2026-05-28.
- [x] **v1.61.1-dispatch-table** — Two-level dispatch in `DispatchRuntime` (`recv match` → per-type `name match` hashCode switch); extensions early-exit when no user extensions registered. ✓ Landed 2026-05-28.
- [x] **v1.61.2-pure-path** — Smart `Computation.map`; all-Pure fast path in `sequence`; pure-path in `Term.Select`/`Term.Assign`/`BlockRuntime.evalBlock`. ✓ Landed 2026-05-28.
- [x] **v1.61.3-env-overhaul** — While-loop frame filtered to O(N_local_vars); ALL Term.Assign intercepts keep local in sync. arith-loop 15600ms → 4480ms (3.5×). ✓ Landed 2026-05-28.
- [x] **v1.61.4-pattern-compile** — Compile `Term.Match` to decision-tree closure cached by AST identity. pattern-match-heavy 6069ms → 3960ms (1.53× vs baseline). ✓ Landed 2026-05-28.
- [x] **v1.61.5-js-inlining** — Tuple IIFE→Object.assign; direct while stmt; int-mul typeof skip. User-code -17–30% chars per program. ✓ Landed 2026-05-28.
- [ ] **v1.61.6-preamble-split** — Sub-capability split of JS `Core` and JVM `commonRuntime`; Hello World target <10 KB. Target: ≥80% bundle reduction for trivial programs.
- [ ] **v1.61.7-memory** — `IntV`/`DoubleV` pools, `TupleV → Array`, `FunV` split, `Span` sidecar, `ArtifactIO` binary format. Target: ≥50% allocation rate reduction.

## x402 — Cardano Scalus thin-glue wiring

- [x] **x402-cardano-scalus-wire** — Wire `CardanoFacilitatorConfig.scalusSettle` to `BloxbeanScalusSettler`: add `CardanoScalusFacilitator.preprod/mainnet` factory in `x402-facilitator-cardano-scalus` (injects `ScalusSettler.asConfigHook`); remove "not yet implemented" error branch; 5 new tests in `CardanoScalusFacilitatorTest` + 3 new tests in `CardanoFacilitatorTest`; close stale BACKLOG checkbox. ✓ Landed 2026-05-28.

## Wallet — Trezor vault adapter

- [x] **wallet-vault-trezor** — Trezor hardware wallet vault adapter: `payments/wallet/vault-trezor/` sbt subproject; `TrezorEthVault` implementing `Vault` SPI; `TrezorBridge` trait + `HttpTrezorBridge` (Trezor Bridge local daemon `http://127.0.0.1:21325`); `TrezorSession` (acquire/release lifecycle); BIP-32 path encoding via `Bip32.parse` → `address_n` int array; `ButtonRequest` auto-ack loop; `MockTrezorBridge` with response queues for tests; 29 tests. Spec: `docs/wallet-vault-trezor.md`. ✓ Landed 2026-05-28.

## Wallet — Ledger WebBLE (Scala.js)

- [x] **wallet-vault-ledger-bluetooth-js** — Scala.js WebBLE transport for Ledger Nano X / Stax: `payments/wallet/wallet-vault-ledger-bluetooth-js/` cross-compiled sbt subproject (JS-only); `WebBleTransport` implementing `LedgerTransport`; wraps `navigator.bluetooth.requestDevice` → `connectGATT` → service UUID `13d63400-2c97-0004-0000-4c6564676572` (Ledger BLE service); `notify` characteristic for device→host; `write` characteristic for host→device; frame splitting for BLE MTU (default 23 bytes); same APDU framing as existing `LedgerHidTransport`; `MockBluetoothDevice` for tests (mirrors existing `MockHidDevice` pattern); 10+ tests. No new vault implementations needed — existing `LedgerEthVault`/`LedgerBitcoinVault`/etc. transparently use any `LedgerTransport`. Spec: `docs/wallet-vault-ledger.md §bluetooth-transport` (amend existing doc).

## Wallet — MPC vendor adapters

- [x] **wallet-vault-mpc-fireblocks** — Fireblocks MPC adapter: `payments/wallet/wallet-vault-mpc-fireblocks/` sbt subproject; `FireblocksRemoteSigningClient` extending `HttpRemoteSigningClient`; Fireblocks JWT auth (`RS256`, `iat`+`nonce`+`bodyHash` claims, API-key header `X-API-Key`); endpoint: `POST /v1/transactions` (create tx) → `GET /v1/transactions/{id}` (poll); `FireblocksVault` named constructor (`FireblocksVault(apiKey, privateKeyPem, baseUrl)`); `FireblocksPlugin` ServiceLoader; 16 tests (mock HTTP, JWT signing, poll loop, timeout). Spec: `docs/wallet-vault-mpc.md §fireblocks`. ✓ Landed 2026-05-28.

- [x] **wallet-vault-mpc-coinbase** — Coinbase MPC adapter: `payments/wallet/wallet-vault-mpc-coinbase/` sbt subproject; `CoinbaseRemoteSigningClient` extending `HttpRemoteSigningClient`; Coinbase Prime API auth (`EC P-256` request signing, `X-CB-ACCESS-KEY` + `X-CB-ACCESS-SIGNATURE` + `X-CB-ACCESS-TIMESTAMP` headers); endpoint: `POST /v1/portfolios/{portfolio_id}/signing_requests` → `GET /v1/portfolios/{portfolio_id}/signing_requests/{id}`; `CoinbaseVault` named constructor; `CoinbasePlugin` ServiceLoader; 15 tests. Spec: `docs/wallet-vault-mpc.md §coinbase`. ✓ Landed (2026-05-28)

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
