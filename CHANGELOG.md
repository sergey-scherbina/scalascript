# Changelog

Completed milestones, newest first. Each entry is a brief summary; git history has full implementation notes.

---

## 2026-05-27

- **x402-cardano-scalus-evaluate-endpoints** — Added live ex-unit endpoint wiring: `BlockfrostClient.evaluateTx` for `/utils/txs/evaluate`, `ScalusTxEvaluator.blockfrost(...)`, and `ScalusTxEvaluator.ogmiosHttp(url)` for Ogmios JSON-RPC `evaluateTransaction`. Endpoint responses now map into typed claim `ScalusExUnits`.

- **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (Adyen adapter: X-API-Key auth, Checkout API v71, HMAC-SHA256 webhook over 8 sorted notification fields, `additionalData` escape hatch, Drop-in/Web Components nonce support, `AdyenWebhookReceiver` with base64-decoded key) + `runtime/std/payments-checkout/` (Checkout.com adapter: Bearer sk_xxx auth, Unified Payments API v3, HMAC-SHA256 hex over raw body with `Cko-Signature` header, `CheckoutWebhookReceiver`). Both adapters implement all 14 SPI methods. 25 new tests (12 Adyen + 13 Checkout.com).

- **v2.1.4-dstream-kafka** — Kafka Streams backend for DStream: new `runtime/backend/kafka-streams/` module (`KafkaStreamsGen`, `KafkaStreamsBackend`, `KafkaStreamsCapabilities`). `KafkaStreamsGen` detects DStream code (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.KafkaStreams` / `Backend.Kafka` / `Window.*` / `WatermarkStrategy.*` / `Trigger.*`) and emits `dstreamKafkaShim` inside `@main def runKafkaStreamsJob()`. Shim provides full DStream DSL backed by driver-local `Seq[Any]` for bounded `InMemory` sources; Kafka Streams topology builder helpers (`_buildTopology`, `_runWithTestDriver`) for live `Kafka.source` inputs. `Backend.KafkaStreams` and `Backend.Kafka` aliases declared. `//> using dep org.apache.kafka:kafka-streams_2.13:3.7.1` + test-utils + clients directives emitted. `Feature.DistributedStreams` in `KafkaStreamsCapabilities`. ServiceLoader registration. 22 new `KafkaStreamsGenTest` tests. Also extends `SparkGen.containsDStream` with `Window.*` / `WatermarkStrategy.*` / `Trigger.*` detection (fixes SparkGen window shim test).

- **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (PayPal Checkout adapter: OAuth2 client-credentials with 8h token cache, PayPal Orders v2 API, RSA-SHA256 webhook verify against PayPal-fetched cert, all 14 SPI methods, `PayPalWebhookReceiver`) + `runtime/std/payments-braintree/` (Braintree adapter: HTTP Basic auth, GraphQL API for transactions/customers/vault, XML REST for plans/subscriptions/refunds/disputes, HMAC-SHA1 webhook with base64-decoded payload, `BraintreeWebhookReceiver`). 25 new tests (11 PayPal + 14 Braintree).

- **v2.1.3-dstream-spark** — Spark backend for DStream: `SparkGen` extended with DStream detection (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.Spark`) and `dstreamSparkShim` emission. Shim provides full DStream DSL (v2.1.1 + v2.1.2 operators) backed by driver-local `Seq[Any]` for bounded `InMemory` sources; produces identical results to `Backend.Direct` on the Spark driver. Operators: `map`, `filter`, `flatMap`, `keyBy`, `combinePerKey`, `merge`, `window`, `withTrigger`, `withWatermark`, `withAllowedLateness`, `timerProcessing`, `run`, `runToList`, `runFold`, `runForeach`, `runCount`. `KV[K,V]` case class, `Pipeline`, `InMemory`, `DSource`, `Backend`, `PipelineResult`, `Window`, `Trigger`, `WatermarkStrategy`, `AccumulationMode` companions all emitted. `Feature.DistributedStreams` added to `SparkCapabilities`. 14 new `SparkGenTest` tests. Integration tests gated by `SPARK_MASTER` env var (15 skipped without Spark).

- **x402-cardano-scalus-bloxbean-evaluator** — Added `ScalusTxEvaluator.bloxbean(...)` on top of bloxbean `TransactionEvaluator`, plus evaluated-balanced claim draft rebuilding. Evaluator-provided claim ex-units now flow into the redeemer and fee estimate before serialization.

- **x402-cardano-scalus-preprod-it** — Added env-gated Preprod integration coverage for the Cardano/Scalus claim Tx draft. `BloxbeanPreprodIntegrationTest` builds a balanced draft from live Blockfrost Preprod protocol params when `X402_SCALUS_PREPROD_IT=true`; actual submit remains separately gated by `X402_SCALUS_PREPROD_SUBMIT=true`.

- **v1.53.1-payments-spi-stripe** — `payments/money/` (opaque `Currency` + ISO 4217/crypto minor-units table, `Money` Long minor-units arithmetic with HALF_EVEN rounding, `allocate` for penny-perfect splits), `payments/webhook/` (`WebhookReceiver[E]` SPI, `SeenKeyStore` idempotency with expiry, `InMemorySeenKeyStore`), `runtime/std/payments-plugin/` (`PaymentProvider` 14-method SPI, all SPI types: `PaymentIntent`/`PaymentEvent`/`Customer`/`Subscription`/`Refund`/`Dispute`/`Mandate`/`SCAChallenge`/`PaymentError` hierarchy, `Feature.Payments`), `runtime/std/payments-stripe/` (full Stripe adapter: HMAC-SHA256 webhook verify, all 14 methods via Java HttpClient + form-encoded bodies, `StripeWebhookReceiver` with replay protection), `examples/traditional-payments.ssc` (12 worked snippets). `Amount` in `payment-request` deprecated. 33 new tests (19 MoneyTest + 14 StripeProviderTest). Closes `chargeCard()` placeholder from v1.38.

- **v2.1.2-dstream-native-unbounded** — Processing-time windowing + watermarks + `timerProcessing` on the native/direct backend. `window(Window.fixed/sliding/session/global)`, `withTrigger(Trigger.*)`, `withAllowedLateness(d)`, `withWatermark(WatermarkStrategy.*)` operators added to `DStream`. `timerProcessing(durationMs)(k => Iterable[B])` fires synchronously per unique key on DirectRunner. `directCapabilities` now includes `EventTime` + `WatermarkPerfect` (v2.1.2+). `collectRequiredCaps` extended for `_dag_window`, `_dag_withWatermark`, `_dag_withTrigger`. `dstreams.ssc` updated. 30 tests green.

- **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` Beam-style API on the native bounded backend. `Pipeline.create(name).read(DSource).map/filter/flatMap/keyBy/combinePerKey/merge.run(Backend.Direct|Native)`. `InMemory.source` / `InMemory.runAndCollect` testing helpers. `DSource.fromLocalSource` bridge from `Source[A]`. `Feature.DistributedStreams` flag. `Capability` negotiation at `.run()` (`CAPABILITY_MISMATCH` on missing cap). `examples/distributed-streams.ssc` (3 bounded examples). `dstreams-plugin` (23 tests green). `BuiltinsRuntime.setupPluginCompanions` extended for all DStream companion objects.

- **x402-cardano-scalus-static-exunits** — Added static `ScalusExUnits` wiring for the Cardano/Scalus claim Tx draft: configured ex-units now flow into `ClaimTxPlan`, the bloxbean redeemer, and balanced fee estimation. Live node-backed ex-unit evaluation remains open.

- **x402-cardano-scalus-fee-balancer** — Added `ScalusFeeBalancer` and `BloxbeanClaimTxBuilder.draftBalanced(...)`: the Cardano/Scalus claim Tx draft now estimates protocol min-fee from Blockfrost protocol params and final serialized CBOR size, with async params wiring for Blockfrost-backed builders. Live script ex-unit evaluation remains open.

- **v1.53** — Traditional Payment Processors spec landed (`docs/traditional-payments.md`). `PaymentProvider` SPI (14 methods: PaymentIntent / Customer+Vault / Subscriptions / Refunds+Disputes / Webhooks), fiat-aware `Money` type (Long minor units, ISO 4217 + crypto codes, banker's rounding, `allocate`), `WebhookReceiver[E]` primitive (HMAC/RSA verify + `SeenKeyStore` idempotency + replay protection), `IdempotencyKey` threading, `SCAChallenge` / 3DS2 flow, subscription lifecycle (proration / dunning / invoicing), full dispute lifecycle + evidence submission, vault (`Customer` + `StoredMethod` + `Mandate`). Closes `chargeCard()` placeholder from v1.38 Payment Request. Adapters deferred to v1.53.1–v1.53.7 (Stripe canonical first, then PayPal/Braintree, Adyen/Checkout.com, Square). Bank rails (SEPA/ACH/Pix/FedNow) deferred to v1.54+. Go/no-go: **go**.

- **v1.52.1** — Deploy plugin landed (`runtime/std/deploy-plugin/`). Six-verb `DeployTarget` SPI, `DeployGroup` orchestrator with DAG resolver (Kahn's + cycle detection), Parallel/Sequence/Pipeline execution modes, three failure policies (RollbackAll/ContinueRemaining/AbortRemaining), `LocalSubprocessTarget` adapter (fat-JAR subprocess + `/_health` polling), `DeployManifest` parser, `StateBackend` SPI with `NoopStateBackend`, `ArtifactRegistry` (10 artifact kinds), `Manifest` AST extended with `deploy`/`groups`/`environments`/`state` fields, `ssc deploy` CLI with `plan`/`status`/`envs` subcommands + `--env`/`--group`/`--target`/`--dry-run`/`--verbose` flags, `examples/deploy.ssc` annotated example, `docs/user-guide.md §26`.

- **x402-cardano-blockfrost-protocol-params** — Added typed `BlockfrostClient.getProtocolParams()` for `/epochs/latest/parameters`, covering fee constants, execution prices, collateral bounds, and Plutus cost models. This is the prerequisite data source for Cardano/Scalus protocol-params fee balancing.

- **x402-cardano-scalus-tx-witness** — Hardened the bloxbean Scalus claim transaction draft with explicit fee/TTL/validity body fields, computed script data hash via bloxbean `ScriptDataHashGenerator`, and relayer `VkeyWitness` signing via `TransactionSigner`. Protocol-params fee balancing and live ex-unit evaluation remain open.

- **v2.1.0** — Distributed Streams spec landed (`docs/distributed-streams.md`). Full Apache Beam model: `DStream[T]` / `KV[K,V]` / `Pipeline` / `PipelineResult`; event-time watermarks (`WatermarkStrategy`); Fixed/Sliding/Session/Global windowing; `Trigger` (AfterWatermark, AfterProcessingTime, AfterCount, Composite); panes (EARLY, ON_TIME, LATE) + accumulation modes (Discarding, Accumulating, AccumulatingAndRetracting); `Capability` enum (Set-based, checked at `.run()`); 5 first-class backends (Native v1.22 actors, Apache Spark, Apache Kafka Streams, Apache Flink, Apache Beam); `DSource[T]` / `DSink[T]` connector abstractions; `Coder[T]` unified serialisation with per-backend adapters; `DirectRunner` in-process test backend; integration bridges (`DStream ↔ Source[A]`, `DStream ↔ Dataset[T]`); 7 implementation phases (v2.1.1–v2.1.7). Go/no-go: **go**.

- **x402-cardano-scalus-tx-required-fields** — Extended the bloxbean Scalus claim transaction draft with optional collateral input and required signer key hash: `ScalusSettlerConfig.collateralRef` maps into body collateral, `relayerKeyHashHex` maps into body required signers, with validation and round-trip tests. Fee balancing and relayer vkey witness remain open.

- **v1.52** — Deploy spec landed (`docs/deploy.md`). Five target categories (container/k8s/faas/static/traditional), dual CLI+manifest interface, 6-verb `DeployTarget` SPI + `outputs()` for cross-target wiring, `DeployGroup` orchestrator with parallel/sequence/pipeline modes + DAG dependency resolution + three failure policies, `DeployEnvironment` axis for local/test/staging/production environments with `base:` inheritance + multi-region fault tolerance + quorum-based health checks + blue-green slot switching (`instant`/`gradual`) + `ssc deploy switch` + `ssc deploy promote`. Hybrid stateless+optional-remote-state model. Per-provider adapters deferred to v1.52.1–v1.52.7. Go/no-go: **go**.

- **x402-cardano-scalus-tx-draft** — Added `BloxbeanClaimTxBuilder.draft`, a non-default bloxbean Transaction skeleton builder that serializes the escrow input, receiver output, Plutus V3 script, and Spend redeemer; tests round-trip through bloxbean `Transaction.deserialize`. Fee balancing, collateral, and relayer witness remain open.

- **x402-cardano-scalus-claim-tx-builder** — Added bloxbean Plutus redeemer construction for Scalus escrow claims: `EscrowRedeemerCodec.claim` encodes `Claim(coseSign1Bytes, coseKeyBytes)` as constructor 0, and `ClaimTxPlan.claimRedeemer` exposes it to the future transaction builder. Full transaction body / script witness / relayer witness remain open.

- **x402-cardano-scalus-settler-bloxbean** — Phase 4 wiring for Cardano/Scalus settlement: added `cardano-client-lib` dependency, `ScalusSettlerConfig`, typed `ClaimTxPlan`, injectable `ClaimTxBuilder`, `ScalusSettler.preprod/mainnet`, and Blockfrost submit pipeline tests. The default builder still fails explicitly until real Plutus witness/redeemer construction is implemented.

- **x402-cardano-scalus-escrow-ref** — Added typed `ScalusEscrowRef` parsing/validation for canonical `<64-hex-txhash>#<output-index>` refs and wired `CardanoProvider.Scalus` verification to reject malformed nonce-slot escrow refs before settlement.

- **x402-cardano-scalus-claim-codec** — Factored Scalus claim-message binary encoding into `x402-core` as `ScalusClaimMessageCodec`, with unit tests for domain/receiver/uint64 layout. The Cardano client and facilitator now share the same encoder.

- **x402-cardano-scalus-server-verify** — `CardanoProvider.Scalus` now verifies the structured Scalus claim-message CIP-8 proof and requires the escrow UTxO ref in `authorization.nonce`, while preserving the legacy Blockfrost description-signing + payer-balance verification path. Claim Tx / UTxO datum validation remains planned in the settler.

- **x402-cardano-scalus-claim-message** — Client-side Scalus payment mode: `Wallets.cardano(hex, network, scalusMode = true)` signs a structured `ScalusClaimMessage` instead of `req.description`; `PaymentRequirements.scalusEscrowRef` is propagated through `authorization.nonce`; Cardano payload tests verify the COSE payload and Ed25519 signature. Real settler / claim Tx remains planned.

- **v1.51** — Streams with Backpressure spec: `docs/streams.md` — full design for `Source[A]` / `Sink[A]` / `Flow[A, B]` / `Stream[A]`; hybrid pull/push (push surface, `request(n)` credit underneath); default credit = 16 / buffer = 16 (Akka default); two-level architecture (uniform `Computation`-based semantics + JVM/interpreter VT+ArrayBlockingQueue and JS `async function*` fast paths); overflow strategies aliased from `actors.ssc Overflow`; errors flow downstream + cancel upstream; integration adapters for Generator/SSE/WS/Actor/UI-signals (`Source.signal` scoped to v1.51.5); effect-row integration deferred to v1.51.6+. Go/no-go: **go** — implementation sequence v1.51.1 → v1.51.2 → v1.51.3 → v1.51.4 → v1.51.5 defined.

- **x402-cardano-scalus-address** — Cardano/Scalus escrow Phase 3 slice: `EscrowScript.address(network)` derives stable CIP-19 enterprise script addresses from the committed Plutus validator bytes. Golden mainnet/preprod bech32 tests pin the address surface for future reference-script deployment and bloxbean claim Tx work.

- **wallet-vault-encrypted-js** — JS-side encrypted vault persistence: `EncryptedLocalVaultJs.create/load/generate/delete/save` wraps the shared `EncryptedLocalVault` core with `VaultFileStore`; browser default uses IndexedDB, falls back to localStorage, then in-memory storage for Node/tests. Durable data remains the shared `VaultFile.toJson` shape. Added Scala.js tests for create/load/unlock, account metadata persistence, and delete.

- **sbt-interop-plugin** — `ssc generate-facade` CLI command + `sbt-scalascript-interop` sbt plugin (Tier 3 interop): `ssc generate-facade <artifactDir> [-o <outDir>]` reads `.scim` artifacts and writes Scala 3 facade sources (delegating to `FacadeGenerator.generate`); `ScalascriptInteropPlugin` (Scala 2.12, `tools/sbt-plugin/`) auto-hooks into `Compile / sourceGenerators` via `sscGenerateFacade` task; `sscArtifactDir` and `sscBinary` settings; 4 scripted tests (`basic`, `identity`, `multi-module`, `no-artifacts`); Mill module trait + scala-cli directive documented in `docs/scala-interop.md §6`.

- **watch-100ms** — watch reload benchmark + hot-path hashing cleanup: new `ssc watch-bench [--cycles N] [--target-ms N] [--require-target] <file.ssc>` command runs watch reload cycles against a temporary copy and reports warm-up/p50/max; `WatchCycleBenchTest` covers the incremental path; `ParseCache` and `SectionSnapshot` SHA-256 hex encoding now use a direct char loop instead of per-byte `String.format`; incremental typer reuses precomputed section hashes when building snapshots for retyped sections.

- **v1.50-native-p4** — Native plugin binary guide: `docs/native-plugin-guide.md` — complete plugin-author guide for building GraalVM native binaries from existing plugins. Covers: `GraalVMNativeImagePlugin` sbt setup, minimal reflection/resource config, agent-based config generation, CI matrix (ubuntu/macos arm64/macos x86_64), `plugin.yaml` manifest for native executables, and JAR-vs-native comparison table. No core changes. Fully JVM-free `ssc (native) → wire protocol → plugin (native)` deployments now documented.

- **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections via Loom virtual threads. `WsLoad10kTest` asserts ≥ 99 % open, heap growth < 1 GB, `WsConnection.activeCount` tracks correctly, all drain cleanly. Auto-skips when `ulimit -n` < 22 000. Satisfies the Project-Loom follow-up deferred since 2026-05-21.
- **v1.50-native-p3** — `ssc-plugin-host` + automatic native bridge: new `tools/plugin-host` sbt subproject (`pluginHost`); `SubprocessHost` main class loads any plugin JAR via `URLClassLoader` + `ServiceLoader` (works in JVM subprocess), then enters the stdio-json wire protocol loop as the server side (handles `describe`, `compile`, `openSession`, `session.feed`, `session.close`, `invokeHandler`, `shutdown`). `BackendRegistry.addPluginJar` detects native-image mode via `org.graalvm.nativeimage.imagecode` system property; in native mode locates `ssc-plugin-host.jar` next to the binary or in `$SSC_HOME/lib/`, finds `java` via `java.home` system property or PATH, then spawns `java -cp plugin.jar:host.jar scalascript.plugin.SubprocessHost plugin.jar` and registers the result via the existing `SubprocessBackend` mechanism. Plugin authors change nothing. Build: `sbt pluginHost/assembly` → `ssc-plugin-host.jar`.

- **v1.50-native-p2** — GraalVM native-image build infrastructure: `sbt-native-packager` plugin added; `cli` project gains `GraalVMNativeImagePlugin` with `--no-fallback`, `--initialize-at-build-time=scala,scalascript`, reflection + resource config file pointers; `native-image-configs/reflect-config.json` (SLF4J binding, Scala runtime, upickle, scala-meta, borer, all ServiceLoader-discovered backend/frontend/server/plugin implementation classes); `native-image-configs/resource-config.json` (`META-INF/services/**`, logger-sources); `.github/workflows/native-release.yml` CI matrix (ubuntu x86_64, macos arm64, macos x86_64) triggered by version tags, uploads `.tar.gz` to GitHub Release; `stage` task renamed to `installBin` to avoid conflict with sbt-native-packager. Build: `sbt cli/graalvm-native-image:packageBin`. Regeneration guide in `native-image-configs/README.md`.

- **v1.50-native-p1** — Replace snakeyaml with pure-Scala `SimpleYaml` parser: new `lang/yaml` sbt module containing `SimpleYaml` (block/flow maps+sequences, scalars, comments, literal block scalars, inline map entries from sequence items); wired into `core` and `backendConfigRuntime` (previously standalone, no deps); all 7 call sites migrated (`Parser.scala`, `LockFile.scala`, `LocalRegistry.scala`, `SscpkgManifest.scala`, `PluginManifest.scala`, `ConfigParser.scala`, `Main.scala loadSopsSecrets`); snakeyaml removed from `build.sbt`; 21 new `SimpleYamlTest` tests.

## 2026-05-26

- **v1.12.3** — Effects stdlib: `StdEffectsRuntime` gains `NonDet` (multi-shot, `choose(options)`) and `Reader` (capability, `ask()`) globals; typed discharge signatures registered in `Typer` prelude for `runLogger`/`runLoggerJson`/`runLoggerToList`, `runRandomSeeded`, `runClockAt`, `runEnvWith`, `runState`, `runHttp`/`runHttpStub` (each accepts a body carrying the named effect row); `EffectAnalysis.verify` promoted to error-level with `asErrors: Boolean = true` default; `examples/algebraic-effects.ssc` showcase (Logger + State interleaved, NonDet multi-shot, capability vs handler styles, stdlib runner signatures); 2 new `StdEffectsTest` tests (42 total). v1.12 effects sprint complete.
- **NativeContext state-bag** — added shared `feature*` and scoped `featureLocal*` state APIs to `NativeContext`; HTTP client config now routes through `NativeContextFeatureKeys` while existing named methods stay compatible.
- **v1.12.2** — One-shot effect runtime: `EffectAnalysis.Result` gains `multiShotEffects: Set[String]`; `collectFromStats` detects `val __multiShot__ = true` in effect objects; `_handleOneShot` JS runtime emitted in preamble with per-dispatch `_resumed` flag; `genHandleForm` routes to `_handleOneShot` when all ops are one-shot; interpreter `evalHandle` gains `multiShotEffects: Set[String] = Set.empty` parameter and raises `InterpretError("One-shot violation: …")` on double-resume; `Interpreter.multiShotEffects` populated from `EffectAnalysis` in `runInit`; 3 new tests (`EffectAnalysisMultiShotTest`); `StdEffectsTest` (40) and `RestartableTest` (17) still green.
- **v1.12.1** — Typed Algebraic Effects — type system foundation: `SType.EffectRow` case with optional open tail variable; `SType.Function` extended with `effects: EffectRow` (default empty, backward-compatible); `show`/`subst`/`freeVars` updated; Rémy-style row unification in `Unifier.solveEffectRow`; `TypeParser` extended with `!` operator and `parseEffectSet` for effect-annotated function types; `multi effect` keyword in `Parser.preprocessEffects` emits `val __multiShot__ = true`; `EffectAnalysis.verify` cross-checks typer-declared effects against reachability analysis; 14 new tests (`EffectTypeTest`, `EffectAnalysisVerifierTest`).
- **v1.49** — macOS distribution: `ssc package --target macos --distribution` (codesign + notarize + DMG via `xcodebuild archive` + `exportArchive` + `notarytool` + `hdiutil`); `ssc publish --target macos --appstore` (fastlane `mac_appstore` lane, generates `Fastfile` by default); `ssc toolchain setup-signing` (`fastlane match init` for ios/macos); `fastlane` and `ios-deploy` added to toolchain tool map and target requirement lists; `--no-dmg`, `--no-notarize`, `--distribution` flags; 8 new tests (26 total in SwiftUIBuildCliTest).
- **v1.48.5** — `ssc publish --target ios` (TestFlight + App Store via fastlane): generates `Fastfile` with `testflight`/`appstore` lanes by default; `--fastlane` uses existing Fastfile; `--testflight`/`--appstore` route selection; `--api-key-path` / `APP_STORE_CONNECT_API_KEY_PATH`; `--submit-for-review`; `--release-notes`; 6 new tests (18 total in SwiftUIBuildCliTest).
- **wasm-backend-phase1** — WASM backend extended to compile `scalascript` / `ssc` blocks alongside `scala` blocks (Phase 1); integration tests and `wasm-scalascript.ssc` example (Phase 2); `//> using dep` directive hoisting for Scala.js dep declarations + `wasm-http.ssc` Fetch API example (Phase 3). `WasmBackend.acceptedSources` grows to include `scalascript` and `ssc`. 31 tests passing.
- **v1.48.4** — `ssc package --target ios` → signed `.ipa`: `xcodebuild archive` + `exportArchive`; ExportOptions.plist generated from frontmatter `bundle-id:`/`team-id:` or `SSC_TEAM_ID` env; `--export-method` (development|ad-hoc|enterprise|app-store, default: development); `--team-id`; `--out`; 4 new tests (12 total in SwiftUIBuildCliTest).
- **Interpreter server extraction** — `WebServer`, interpreter HTTP handler, WS proxy/session/connection runtime, in-process backend transport, and server-specific tests moved to new `backendInterpreterServer` module behind `InterpreterServerSupport`.
- **v1.12** — Typed Algebraic Effects spec: `docs/algebraic-effects.md` — full design for `A ! Eff` type syntax, open effect rows with implicit tail, `effect Foo { … }` / `multi effect Foo { … }` declarations, handler discharge rules, capability passing (`?=>`), one-shot fast paths (coroutine VT on JVM/interpreter; `function*`/`yield` on JS), multi-shot via Free-monad, interaction matrix with `throws`, `Async`, `Free`, and `MonadError`. Go/no-go: **go** — implementation milestone sequence v1.12.1 → v1.12.2 → v1.12.3 defined.
- **SQL plugin cleanup** — interpreter `transaction` fenced blocks now route through `SqlBlockRunner.runTransaction`; JDBC transaction execution and result encoding live in `runtime/std/sql-plugin` instead of interpreter core.
- **v1.48.3** — `ssc run --target ios --device` real device via ios-deploy: xcodebuild arm64 + automatic signing (`-allowProvisioningUpdates`) + `ios-deploy --bundle ... --no-wifi [--debug|--justlaunch]`. `--device-id <udid>` for specific device. Same `--console`/`--no-rebuild` flags as simulator path.
- **v1.48.2** — `ssc run --target ios` one-command iOS Simulator launch: xcodebuild → boot latest iPhone sim → open Simulator.app → install → `simctl launch`. `--console`/`--no-console` (default: stream logs), `--rebuild`/`--no-rebuild` (default: incremental mtime check). `--target ios` canonical; `mobile-ios` alias kept. `pickIosSimulator` picks latest available iPhone from highest iOS runtime.
- **v1.48.1** — `ssc run --target macos` one-command wrapper: generates Swift Package, runs `swift build`, launches binary. `ssc package --target macos` now includes `swift build`. Target renamed `desktop-macos` → `macos` (alias kept). `swiftAppName` helper derives Swift product name from `.ssc` name frontmatter.
- **v1.48** (SwiftUI Phase 3) — Reactive list lowering: `ForSignal` → `ForEach` with `ForCtx` for index-aware `RemoveSelfFromList`; `@Observable AppModel` emitted when list signals present (Observation framework, iOS 17+); 11 new tests (41 total). v1.48 now feature-complete.
- **v1.48** — JavaFX Typed Route Clients: same-process in-process BackendTransport for JavaFX; typed route client codegen for JavaFX mode.
- **v1.47** — JavaFX Desktop Frontend: JavaFX renderer with reactive View DSL and full SwingFrontend-parity API.
- **v1.46** (all phases complete) — Typed Route Clients: generated frontend clients over backend routes. JVM/Swing in-process + JS HTTP transports. Auth/custom header injection, per-call header overrides, retry policy, cancellation tokens. Phase 7: SSE streaming. Phase 8 (WS): `stream: ws` → `_SscWsHandle` bidirectional subscriptions. Pagination: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` to URL on both JVM and JS targets. Extended Phase 5: `RouteDeriver` now covers `routes:` front-matter, `mount()` calls, and cross-file typed-handler analysis (`(input: T) => ...` → `requestType = "T"`); `Parser.parseFile` passes `baseDir` for handler lookup.
- **v1.45** — JVM Desktop Frontend: Swing-based desktop frontend; reactive View DSL; JvmUiRuntime; `ssc run --frontend swing`.
- **v1.44** — Full-Stack In-Process Transport: BackendTransport + same-process fetch dispatch; Swing/JVM frontend reaches backend without HTTP.
- **v1.43** — Electron JVM REST Backend: `ssc run --mode server` + Electron renderer; REST-over-localhost JVM backend for Electron apps.

## 2026-05-23

- **v1.42** — Native Platform P3: Electron Renderer — Electron shell + Node.js IPC bridge; `ssc run --frontend electron`.
- **v1.41** — Native Platform P2: Toolchain UX — native build CLI ergonomics; `ssc native` subcommand improvements.
- **v1.40** — Native Platform P2: Web Renderer Update — updated Electron-embedded web renderer; renderer protocol version bump.
- **v1.39** — Native Platform P1: IR Foundation — new IR nodes + codegen for native platform targets.

## 2026-05-21

- **v1.37** — Typer: `ssc check` 33→94 examples — typer fixes raising passing conformance suite from 33 to 94 examples.
- **v1.36** — Parser bugfix: `preprocessInlineImports` ordering — fixed parse-order regression in inline import preprocessing.
- **v1.35** — `run-jvm` artifact caching — incremental rebuild avoidance for JVM-target `.ssc` scripts.
- **v1.34** — REPL Debugger — interactive breakpoint + step-through debugger in the REPL.
- **v1.33** — Interpreter lazy loading Phase 2 — deferred plugin loading; faster cold start.
- **v1.32** — `runtime/std/pwa-plugin`: Progressive Web App support — service worker, manifest generation, offline mode.
- **v1.31** — `transaction` fenced block — database transaction scope (`transaction { … }`) as a language construct.
- **v1.30** — REPL web-aware mode + `mount()` intrinsic — `ssc repl --web`; `mount()` hot-replaces running server routes.
- **v1.29** — DAP Debugger (`ssc debug`) — Debug Adapter Protocol server; VS Code / IDE debugger integration.
- **v1.28** — Config System — `config.ssc` front-matter + `ssc.Config.*` typed accessor; environment overrides.

## 2026-05-20

- **v1.25** — JavaScript / Node.js fenced code blocks — `js` and `node` fenced blocks executed natively in JS target; seamless JS interop from `.ssc`.
- **Wallet SPI** — Scala.js cross-compile sprint: wallet interface + Scala.js cross-compiled runtime; browser + JVM wallet stubs.

## 2026-05-19

- **v1.24** — Language features: pattern matching extensions, string interpolation improvements, type inference fixes, sealed-trait enhancements.
- **v1.23** — Cluster management: membership view + events, Phi-accrual failure detection, Bully + Raft leader election, config distribution, rolling-restart drain, cluster metrics, external-coordinator adapter (etcd/Consul/ZooKeeper).
- **v1.22** — Distributed map-reduce: `Dataset[T]` API over v1.6 distributed actors; coordinator-dispatched partitions; shuffle; configurable failure handling.
- **v1.21** — Local map-reduce (`Dataset[T]`): lazy fluent API; sequential + parallel local execution; streaming via v1.10 generators.
- **v1.20** (all sub-versions) — DSL primitives + `runtime/std/parsing`: user-defined string interpolators, parser-combinator library, error recovery, indentation-aware parsing, multi-pass pipeline.
- **v1.19** — URL / dep imports: `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)` resolver with `ssc.lock` SHA-256 integrity.
- **v1.18** — `package` keyword + std layout migration: `package foo.bar` declarations; `runtime/std/*` reorganised under package hierarchy.
- **v1.17** — MCP support (client + server): full MCP 2025-03 + OAuth 2.1 + OIDC compliance; AS + RS + OIDC IdP; WebAuthn passkey grant; persistent stores; observability; CLI `ssc oauth` subcommand.
- **v1.13** — Final Tagless ergonomics: `using` auto-resolution, context bounds, cross-file trait inheritance with HKT, sealed-trait extension dispatch.
- **v1.9.x** — Actor internals refactor: mailbox + scheduler rewrite; reduced allocation on hot actor paths.
- **v0.12** — SSR + client hydration: Declarative Shadow DOM via `wc()` + zero-JS-rerender hydration guard.
- **v0.11** — i18n / l10n: `translations:` front-matter + `t(key)` / `setLocale(code)` intrinsics across all backends.
- **v2.0** (MVP) — Separate compilation: artifact format, `InterfaceExtractor`, `ArtifactIO`, `InterfaceScope`, `Linker` (FQN rewrite), `ModuleGraph`, six CLI commands. Full pipeline deferred; see [BACKLOG.md](BACKLOG.md#v20--separate-compilation-of-modules).

## 2026-05-18 and earlier

- **v1.16** — Restartable errors via algebraic effects: `perform`/`handle`/`resume` across all backends.
- **v1.15** — Checked errors via `throws`: dual-encoding (`throws` / `throwsRaw`), `attemptCatch`, `HasStackTrace`, platform-exception shims.
- **v1.14** — Metaprogramming MVP: `inline def`/`val`/`if`/`match`, `compiletime.summonInline`, `derives` recipes for Eq/Show/Hash/Order.
- **v1.11.5** — `Free[F, A]` as stdlib type: user-facing Free monad in `runtime/std/free.ssc`.
- **v1.11** — Continuation-based `Async`: rewrite on top of v1.9 coroutines; `Computation[A]` shim; ≥20% allocation reduction.
- **v1.10** — Generators: `flatMap`, `zip`, `zipWithIndex`; all three backends; streaming foundation.
- **v1.9** — Coroutine primitive: `Coroutine[A, B]`; interpreter + JvmGen + JsGen; 19 conformance tests.
- **v1.8.1** — Direct-syntax extensions: additional monad `do`-notation shapes; error-channel integration.
- **v1.8** — Direct-syntax do-notation: `for`/`yield` over arbitrary monads; all phases; conformance suite.
- **v1.7** — Plugin packaging & discovery: `.sscpkg` format, `pkg:` URI resolver, `ssc install`, local registry.
- **v1.6** — Actors (Erlang-style, WebSocket-distributed): local actors, supervision trees, distributed via WS; all backends.
- **v1.5** — Transport layer: TLS + HTTP/WS clients + streaming (Phases A–D′; NIO migration deferred).
- **v1.4** — Standard-library effects: `State`, `Reader`, `Writer`, `IO`, effect-system stdlib.
- **v1.3** — Runtime upgrades: real-thread Async (virtual threads), persistence layer, Async-integrated WS.
- **v1.2** — Auth follow-up: combined example + WebAuthn / passkeys.
- **v1.1** — Standard type-class hierarchy: `Functor`, `Applicative`, `Monad`, `Foldable`, `Traversable`, etc.
- **v1.0** — WebSocket production-readiness: Sprints 1–4, 6 (Sprint 5 deferred).
- **v0.10** — Extended component pack: Card, Switch, Alert, Tag, Stats, Empty, Toolbar, Tree, Stepper, Lightbox, FileUpload, DateInput/Picker, TimePicker, Combobox, RangeSlider, Carousel.
- **v0.9** — Standard component pack + Optics second pass: 8-tier UI component library; Index optic (`.index(i)` / `.at(key)`).
- **v0.8** — Web Components target: `ssc emit-wc`, `customElements.define`, Shadow DOM, hydration guard.
- **v0.6** — Optics: Lens / Prism / Optional / Traversal across all backends.
- **v0.5** — Interpreter performance Tier 1: dispatch-table rewrite; ~3× faster on typical workloads.
- **Backend SPI v0.1** (Stages 1–9.1) + followups: 9-module sbt layout, SPI traits, in-process + out-of-process plugins, intrinsic extraction (`std.http`, `std.ws`, `std.auth`, etc.), `ssc fmt`.
- **Scala ↔ ScalaScript interop** — Tiers 1 + 2: `@ssc` annotation → `.ssc` stub generation; Scala callers import generated stubs.
