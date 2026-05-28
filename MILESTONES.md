# Milestones

> This file is a navigation index. Full content lives in three files:

| File | Contents |
|------|----------|
| [BACKLOG.md](BACKLOG.md) | Open and planned milestones — what still needs to be done |
| [ACTIVE.md](ACTIVE.md) | Milestones currently in progress (synced with `WORK_QUEUE.md`) |
| [CHANGELOG.md](CHANGELOG.md) | Completed milestones, newest first |

---

## Quick status (2026-05-28)

### In progress
- v1.57.1-payment-rails-australia-npp — Australia NPP/PayID adapter
- v1.57.2-payment-rails-canada-eft — Canada Interac e-Transfer + EFT adapter
- v1.57.3-payment-rails-mexico-spei — Mexico SPEI + CLABE adapter
- v1.57-fx-provider — FX rate provider SPI + ECB / Open Exchange Rates adapters
- graph-storage-fullstack — Phase 6: graph query REST routes + client-side cache example

### Recently completed
- v1.63.0-placement-remoting-spec — placement/remoting spec for streams, actors, typed async functions/RPC, cluster deployment, and code identity ✓ (2026-05-28)
- v1.62.0-distributed-wire-spec — opt-in JSON/MsgPack/CBOR internal distributed wire protocol spec + backlog ✓ (2026-05-28)
- v1.57.3-payment-rails-mexico-spei — Mexico SPEI + CLABE, 44 tests ✓ (2026-05-27)
- graph-storage-fullstack — Phase 6 full-stack examples ✓ (2026-05-27)
- openapi-export — `/_openapi.json` + `/_swagger` built-in routes ✓ (2026-05-27)
- secret-resolvers-cloud — AWS/GCP/Azure secret resolver plugins ✓ (2026-05-27)
- wallet-solana-standard-js — Scala.js `registerWallet` bridge ✓ (2026-05-27)
- x402-cardano-scalus-completion — Phases 3/5/6 complete ✓ (2026-05-27)
- v1.56-xslt — XSLT 1.0 transformation, Feature.Xslt, 18 tests ✓ (2026-05-27)
- secret-resolvers-jdk — Vault/Doppler/1Password/pass resolver plugins ✓ (2026-05-27)
- v1.55.8-singapore-paynow — PayNow proxy resolution + FAST payment, 67 tests ✓ (2026-05-27)
- v1.55.7-japan-zengin — Zengin fixed-width file, kana validation, 59 tests ✓ (2026-05-27)
- v1.55.6-india-upi — UPI push+collect + RSA-SHA256, 63 tests ✓ (2026-05-27)
- v1.55.5-uk-chaps — UK CHAPS ISO 20022 pacs.008, 46 tests ✓ (2026-05-27)
- v1.55.4-uk-bacs — BACS DD Standard-18 + AUDDIS, 61 tests ✓ (2026-05-27)
- v1.55.3-uk-faster-payments — UK FPS + CoP name check, 47 tests ✓ (2026-05-27)
- v1.55.2-sepa-instant — SCT Inst pacs.008, 49 tests ✓ (2026-05-27)
- v1.55.1-international-swift — SWIFT MT103 + pacs.008 CBPR+, 65 tests ✓ (2026-05-27)
- v1.52.7-deploy-state-backends — `LocalFileStateBackend`/`S3StateBackend`/`ConsulStateBackend`/`EtcdStateBackend` + `StateBackendFactory` + `StateMigrator`, 105 tests total ✓ (2026-05-27)
- v1.52.6-deploy-faas — `FaasTarget` (Lambda/Cloudflare Workers/Cloud Run/Vercel Functions), 91 tests total ✓ (2026-05-27)
- v1.52.5-deploy-static — `StaticTarget` (Vercel/Netlify/Cloudflare Pages/GitHub Pages), 80 tests total ✓ (2026-05-27)
- v1.52.4-deploy-traditional — `SystemdUnitGenerator` + `SshSystemdTarget` + `RsyncTarget` + `SftpTarget`, 71 tests total ✓ (2026-05-27)
- v1.52.3-deploy-k8s — `K8sManifestGenerator` + `K8sTarget` (all 7 SPI verbs + switch/promote blue-green), `TargetFactory` k8s case, 53 tests total ✓ (2026-05-27)
- v1.52.2-deploy-container — `DockerfileGenerator` (4 base-image recipes), `ContainerTarget` (all 7 SPI verbs, buildctl/buildx/docker fallback, multi-platform, digest rollback), `TargetFactory`, 36 tests total ✓ (2026-05-27)
- v2.1.10-dstream-conformance — new `backendConformance` module; 8 cross-backend conformance tests; `examples/distributed-streams.ssc` expanded to 12 examples; all 4 shims now declare all 7 backend aliases ✓ (2026-05-27)
- v2.1.9-dstream-joins — `join`/`leftOuterJoin`/`rightOuterJoin`/`flatten`; all 4 shims + native interpreter `evalDag`; `CAP_WINDOWED_JOINS`; +20 tests ✓ (2026-05-27)
- v2.1.8-dstream-side-io — `SideInput[T]`/`OutputTag[B]`/`withSideInput`/`sideOutput`; all 4 shims + native interpreter; `CAP_SIDE_INPUTS`/`CAP_SIDE_OUTPUTS`; +16 tests ✓ (2026-05-27)
- v2.1.7-dstream-stateful — `statefulMap`/`statefulFlatMap`/`broadcastState`/`timerEventTime`; state types `ValueState`/`MapState`/`ListState`/`BagState`; `StateContext`/`KeyedStateSpec`; all 4 shims + native interpreter, 20 new tests ✓ (2026-05-27)
- v2.1.6-dstream-connectors — `Kafka`/`Files`/`Jdbc`/`Pulsar`/`Kinesis` stubs in all 4 shims + native interpreter, `containsConnector` detection, 14 new tests ✓ (2026-05-27)
- v2.1.5-dstream-flink — Flink + Beam backends: `FlinkGen`/`BeamGen`/`FlinkBackend`/`BeamBackend`, `PipelineOptions`, `_flinkEnv`/`_createBeamPipeline`, 30 tests ✓ (2026-05-27)
- v2.1.4-dstream-kafka — Kafka Streams backend: `KafkaStreamsGen` + `KafkaStreamsBackend` + `KafkaStreamsCapabilities`, `dstreamKafkaShim`, 22 tests ✓ (2026-05-27)
- v2.1.3-dstream-spark — `SparkGen` DStream shim: full pipeline DSL emitted inside `@main`, `Feature.DistributedStreams` in `SparkCapabilities`, 14 new tests ✓ (2026-05-27)
- v2.1.2-dstream-native-unbounded — Processing-time windowing, `timerProcessing`, `withWatermark`, `EventTime`+`WatermarkPerfect` capabilities, 30 tests ✓ (2026-05-27)
- v2.1.1-dstream-native-bounded — `DStream[T]` / `Pipeline` native bounded backend, DirectRunner, 23 tests, `examples/distributed-streams.ssc` ✓ (2026-05-27)
- v1.53 — Traditional Payment Processors spec (`docs/traditional-payments.md`) — `PaymentProvider` SPI, `Money` type, `WebhookReceiver`, 4 PSP adapter families planned ✓ (2026-05-27)
- v2.1.0 — Distributed Streams spec (`docs/distributed-streams.md`) — DStream[T], full Beam model, 5 backends, capability system ✓ (2026-05-27)
- v1.52 — Deploy to Hostings/Clouds/K8s spec (`docs/deploy.md`) + go decision ✓ (2026-05-27)
- v1.51 — Streams with Backpressure spec (`docs/streams.md`) + go decision ✓ (2026-05-27)
- v1.50 — GraalVM native-image build + `ssc-plugin-host` bridge + native plugin guide ✓ (2026-05-27)
- v1.12.3 — Effects stdlib: `NonDet`, `Reader`, typed discharge signatures, `examples/algebraic-effects.ssc` ✓ (2026-05-26)
- v1.12.2 — One-shot effect runtime fast path + JS `function*` + dynamic violation check ✓ (2026-05-26)
- v1.12.1 — Typed Algebraic Effects type system + parser + diagnostics ✓ (2026-05-26)
- v1.12 — Typed Algebraic Effects spec (`docs/algebraic-effects.md`) + go decision ✓ (2026-05-26)
- v1.48 (SwiftUI Phase 3) — Reactive list lowering + `@Observable` AppModel ✓ (2026-05-26)
- v1.46 — Typed Route Clients (all phases, including pagination) ✓ (2026-05-26)
- v1.48 — JavaFX Typed Route Clients ✓ (2026-05-26)
- v1.47 — JavaFX Desktop Frontend ✓ (2026-05-26)
- v1.45 — JVM Desktop Frontend ✓ (2026-05-26)
- v1.44 — Full-Stack In-Process Transport ✓ (2026-05-26)
- v1.43 — Electron JVM REST Backend ✓ (2026-05-26)
- v1.42 — Native Platform P3: Electron Renderer ✓ (2026-05-23)

See [CHANGELOG.md](CHANGELOG.md) for the full list.

---

## Parallel directions (all independent)

| Direction | Top task | Spec |
|-----------|----------|------|
| **Frontend & Clients** | graph-storage-fullstack | `docs/graph-storage.md §Phase 6` |
| **Language & Compiler** | secret-resolvers-cloud | `docs/secret-resolvers.md §aws-secret §gcp-secret §azure-kv` |
| **Database** | _(queue empty)_ | — |
| **Payments & Blockchain** | v1.57-payment-rails-apac | `docs/international-bank-rails.md` (new v1.57 spec) |
| **Native Platform** | _(queue empty)_ | — |
| **Distribution & Tooling** | v1.57-fx-provider | `docs/traditional-payments.md §FxProvider` |
| **Runtime & Distributed Placement** | v1.63.1-placement-core-code-identity | `docs/placement-and-remoting.md §Phase 1` |
| **Runtime & Distributed** | v1.62.1-wire-core | `docs/distributed-wire-protocol.md §Phase 1` |

Multiple agents can work in parallel — one per direction. Tell each: `"работай над <Direction>"`.

## For agents

- **Pick next task**: read [WORK_QUEUE.md](WORK_QUEUE.md) by direction; claim via `AGENTS.md §"Task claiming protocol"`.
- **Mark landed**: update `BACKLOG.md` (remove entry from direction section) + add one-liner to `CHANGELOG.md`.
- **Start new milestone**: add it to the right direction section in `BACKLOG.md`.
