# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" if its slug has no file in `.work/active/`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Frontend & Clients

_(all done — see Done section below)_

## Language & Compiler — Spark extensions

- [x] **spark-streaming-f2-f4** — Spark Structured Streaming phases F.2–F.4: (F.2) `SparkGen.detectStreaming`, `awaitTermination()` shim, `examples/spark-streaming-rate-console.ssc`, 3+ codegen tests; (F.3) file source/sink + checkpointing comment emit, `examples/spark-streaming-file-parquet.ssc`; (F.4) `.format("kafka")` detection → auto-emit `spark-sql-kafka-0-10_2.13` dep, `examples/spark-streaming-kafka.ssc`. Smoke tests gated by `RUN_SPARK_INTEGRATION`/`RUN_SPARK_KAFKA`. Spec: `docs/spark-streaming.md §F.2–F.4`. ✓ Landed (2026-05-27)

- [x] **spark-lakehouse-l2** — Spark Lakehouse Delta Lake (L.2): `SparkGen.detectLakehouseFormats` detects `.format("delta")` → auto-emit `io.delta:delta-spark_2.13` dep + `spark.sql.extensions`/`spark.sql.catalog.spark_catalog` config; `SparkGen.lakehouseImports`; `examples/spark-lakehouse-delta.ssc` (write/read Parquet→Delta, merge-into); 6+ codegen tests. L.3 Iceberg and L.4 Hudi remain deferred. Spec: `docs/spark-lakehouse.md §L.2`. (2026-05-27)

- [x] **spark-catalog-g2-g4** — Spark Catalog phases G.2–G.4: (G.2) front-matter `spark-hive-metastore:`/`spark-warehouse:` keys → emit `spark-hive_2.13` dep + `enableHiveSupport()` + config keys; (G.3) `@TempView("name")` annotation rewriter → `createOrReplaceTempView`; (G.4) `Dataset.fromTable[T](name)` shim via `spark.table(name).as[T]`; `examples/spark-catalog-hive.ssc`; 8+ codegen tests. Spec: `docs/spark-catalog.md §G.2–G.4`. (2026-05-27)

- [x] **spark-mllib-m2-m5** — Spark MLlib phases M.2–M.5: (M.2) `SparkGen.containsMllib` detection → auto-emit `spark-mllib_2.13` dep; (M.3) `aenc_Vector` given in `SscSparkEncoders` shim via `UDTEncoder(new VectorUDT(), classOf[VectorUDT])`, gated on `usesMllib`; (M.4) `examples/spark-mllib-pipeline.ssc` (Tokenizer+HashingTF+LogisticRegression pipeline); (M.5) `examples/spark-mllib-model-save-load.ssc` (model.write.save + PipelineModel.load); 10+ codegen tests. Spec: `docs/spark-mllib.md §M.2–M.5`. (2026-05-27)

- [~] **v1.56-xslt** — XSLT transformation support (deferred from v1.55): `Markup.transform(xslt: String): Markup.Doc` via `javax.xml.transform.TransformerFactory` (JVM); `XsltTransformer` class with `apply(source: Markup.Doc, params: Map[String, String]): Markup.Doc`; `JvmMarkupCodec.transform` implementation; `MarkupCodec.transform` SPI hook (default: `UnsupportedOperationException`); `Feature.Xslt` capability flag declared in `InterpreterCapabilities`/`JvmCapabilities`; `CapabilityCheck` rejects `transform` on non-XSLT backends; `examples/xslt-transform.ssc`; 12+ tests. Spec: `BACKLOG.md §v1.55` (XSLT deferred note) + `docs/markup-core`.

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

- [x] **v1.51.6-streams-effects** — `Source[A] ! Stream` effect-row integration + `runStream { … }` discharge runner analogous to `runLogger`. Spec: `docs/streams.md §14.6`. (2026-05-27)

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
- [x] **interpreter-ergonomics** — Interpreter ergonomics — All 3 items landed (v1.13 + 2026-05-19)
- [x] **wasm-backend-phase1** — WASM backend: scalascript/ssc block support (Phase 1), integration tests + example (Phase 2), `//> using dep` hoisting + HTTP Fetch example (Phase 3) — All 3 phases landed (2026-05-26)

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
