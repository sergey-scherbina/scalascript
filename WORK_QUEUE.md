# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" if its slug has no file in `.work/active/`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Frontend & Clients

_(all done — see Done section below)_

## Payments & Blockchain

- [x] **v1.53.1-payments-spi-stripe** — `payments/money/` + `payments/webhook/` + `runtime/std/payments-plugin/` + `Feature.Payments` enum case + Stripe adapter (PaymentIntent / SCA / Customer / Vault / Subscription / Refund / Dispute / Webhook) + `examples/traditional-payments.ssc`. Closes `chargeCard()` placeholder from v1.38. Spec: `docs/traditional-payments.md §16`. (2026-05-27)

- [x] **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (OAuth2 client-cred, PayPal Orders v2, RSA-SHA256 webhook verify) + `runtime/std/payments-braintree/` (GraphQL API, XML REST plans/subscriptions, HMAC-SHA1 webhook). Spec: `docs/traditional-payments.md §11.2`. (2026-05-27)

- [x] **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (X-API-Key, Drop-in nonce, HMAC-SHA256 over notification fields, additionalData escape hatch) + `runtime/std/payments-checkout/` (sk_xxx, Frames token, HMAC-SHA256 over raw body). Spec: `docs/traditional-payments.md §11.3`. (2026-05-27)

- [x] **v1.53.4-payments-square** — `runtime/std/payments-square/` (Bearer token, Web Payments SDK nonce, HMAC-SHA1 over notification_url+body). Spec: `docs/traditional-payments.md §11.4`. (2026-05-27)

- [x] **v1.53.5-payments-vault-mandates-sca** — Cross-PSP `createMandate`/`getMandate` SPI methods (all 5 adapters); `ScaExemption` enum + `scaExemptions` in `CreateIntentRequest`; `mandateId` in `CreateIntentRequest` for off-session MIT; `networkToken` + `mandateId` fields in `StoredMethod`; PSD2 off-session flags wired in PayPal/Adyen/Stripe; `Mandate` extended with `customerId`/`vaultId`/`providerRef`. 9 new SPI-level tests in StripeProviderTest. Spec: `docs/traditional-payments.md §16.5`. (2026-05-27)

- [x] **v1.53.6-payments-mock-provider** — `runtime/std/payments-mock/` fully in-memory `MockProvider` + `MockWebhookReceiver`; `MockMode` enum (Succeed/Fail/RequireSCA) per effect group; all 16 SPI methods; `recorded*` inspection helpers + `reset()`; `PaymentEffect` enum added to SPI; 41 tests. Spec: `docs/traditional-payments.md §16.6`. (2026-05-27)

- [x] **v1.53.7-payments-webhook-cluster** — `payments/webhook-redis/` `RedisSeenKeyStore` (Lettuce SET NX EX, configurable prefix + timeout, 8 tests) + `payments/webhook-postgres/` `PostgresSeenKeyStore` (HikariCP INSERT ON CONFLICT DO NOTHING, auto-CREATE TABLE, `purgeExpired()`, H2 test suite, 9 tests). Both implement `SeenKeyStore` SPI; cluster-safe idempotency for multi-instance deployments. Spec: `docs/traditional-payments.md §16.7`. (2026-05-27)

## Database

_(all done — see Done section below)_

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

## Language & Compiler

- [x] **v2.1.6-dstream-connectors** — `Kafka`/`Files`/`FileFormat`/`Jdbc`/`Pulsar`/`Kinesis` stubs in all 4 code-gen shims (Spark, KafkaStreams, Flink, Beam) + native interpreter intrinsics; `containsConnector` in each generator; `DSource.fromDataset` bridge; SparkGen Kafka dep extended; `DSink[T] = Any` alias; 14 new tests. Spec: `docs/distributed-streams.md §6`. (2026-05-27)

- [x] **v2.1.5-dstream-flink** — `runtime/backend/flink/` module: `FlinkGen` (Flink DataStream API shim, `_flinkEnv()` helper), `BeamGen` (Apache Beam Java SDK shim, `_createBeamPipeline()`, runner dep auto-selection for DirectRunner/FlinkRunner/SparkRunner), `FlinkBackend`/`BeamBackend` SPI adapters, `FlinkCapabilities`/`BeamCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration, `PipelineOptions` case class; 30 new `FlinkGenTest` tests. Spec: `docs/distributed-streams.md §9.4–9.5`. (2026-05-27)

- [x] **v2.1.4-dstream-kafka** — `runtime/backend/kafka-streams/` module: `KafkaStreamsGen` (shim pattern from v2.1.3, adds `Backend.KafkaStreams`/`Backend.Kafka`, topology helpers, extended `containsDStream` for `Window.*`/`WatermarkStrategy.*`/`Trigger.*`), `KafkaStreamsBackend` SPI adapter, `KafkaStreamsCapabilities` (`Feature.DistributedStreams`), ServiceLoader registration; 22 new `KafkaStreamsGenTest` tests. Spec: `docs/distributed-streams.md §9.3`. (2026-05-27)

- [x] **v2.1.3-dstream-spark** — `SparkGen` DStream shim: `containsDStream` detection + `dstreamSparkShim` emission; full pipeline DSL backed by `Seq[Any]` for bounded `InMemory` sources; `Feature.DistributedStreams` in `SparkCapabilities`; 14 new `SparkGenTest` tests. Spec: `docs/distributed-streams.md §9.2`. (2026-05-27)

- [x] **v2.1.2-dstream-native-unbounded** — Processing-time `window(Window.fixed/sliding/session/global)`, `withTrigger`, `withAllowedLateness`, `withWatermark(WatermarkStrategy.atEnd)`, `timerProcessing(d)(f)`; DirectRunner provides `EventTime` + `WatermarkPerfect` in v2.1.2. Spec: `docs/distributed-streams.md §13 v2.1.2`. (2026-05-27, 30 tests green)

- [x] **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` types + native bounded backend (wraps `Dataset[T]` partitions); `DirectRunner` test backend; `Feature.DistributedStreams` flag; `examples/distributed-streams.ssc`. Spec: `docs/distributed-streams.md §13`. (2026-05-27, 23 tests green)

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
- [x] **interpreter-ergonomics** — Interpreter ergonomics — All 3 items landed (v1.13 + 2026-05-19)
- [x] **wasm-backend-phase1** — WASM backend: scalascript/ssc block support (Phase 1), integration tests + example (Phase 2), `//> using dep` hoisting + HTTP Fetch example (Phase 3) — All 3 phases landed (2026-05-26)

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
