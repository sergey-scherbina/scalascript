# x402 — Scalus settlement for Cardano

Status: **draft / planning**. Source of truth for the Plutus-escrow
implementation of `CardanoProvider.Scalus` in `x402-facilitator-cardano`.
Until each phase below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this design.

## 1. Goals

- **On-chain-enforced atomic settlement** for x402 Cardano payments.
  The current `CardanoProvider.Blockfrost` is **optimistic**: it
  CIP-8-verifies the payer's proof and balance-checks the address, then
  returns `Ok` without forcing a settlement Tx. That works for
  low-stakes flows but is replayable against the same UTxO and is not
  a real escrow.
- **Pluggable settler interface.** A new `ScalusSettler` SPI lives in
  a separate module `x402-facilitator-cardano-scalus`. The optimistic
  Blockfrost path stays where it is; the Plutus path is a peer.
- **Reuse Scalus** for the on-chain validator (Plutus V3 compiled
  from Scala-flavored DSL) and **bloxbean cardano-client-lib** for
  off-chain Tx construction, witness signing, and submission.
- **Hold the existing x402 wire format constant.** Cardano payment
  payloads already carry `CardanoPaymentProof` (CIP-8 COSE_Sign1 +
  COSE_Key). The escrow extension reuses these fields by interpreting
  the `message` bytes as a claim authorization payload, not just the
  request description.

## 2. Non-goals

- **Replacing the Blockfrost provider.** It stays for use cases where
  the receiver controls double-spend risk out-of-band (e.g. one-shot
  research APIs). Operators pick per deployment.
- **A general-purpose Plutus contract framework.** Plutus authorship
  lives in Scalus; we consume it for one specific escrow script.
- **A Cardano-node embedding.** The facilitator talks to Ogmios for
  submission and Kupo or Blockfrost for UTxO indexing — it never
  embeds a node.
- **Stake-aware base addresses for the relayer.** The facilitator's
  relayer uses an enterprise address (CIP-19 type 6/7); stake delegation
  is the operator's concern, not ours.
- **Refunds.** A canceled or expired escrow UTxO is recoverable by the
  original payer via the `Refund` redeemer (§3); the facilitator
  does not initiate refunds.

## 3. Architecture

### 3.1 Two-step payment flow

```
         ┌───────────┐                ┌─────────────┐                ┌────────────┐
         │  Payer    │                │ Facilitator │                │ Cardano    │
         │  (client) │                │  + relayer  │                │ chain      │
         └─────┬─────┘                └──────┬──────┘                └─────┬──────┘
               │                             │                             │
   1. Deposit  │── build escrow tx ──────────┼─────────────────────────────┤
      (payer)  │   inputs: payer UTxOs       │                             │
               │   output: escrow_script + datum                           │
               │── sign + submit ────────────┼────────────────────────────►│
               │                             │                             │
   2. Pay      │── X-Payment (CIP-8 proof + escrow_ref) ─►│                │
      (HTTP)   │                             │── verify(proof, req) ──►   │
               │                             │   • CIP-8 sig over message  │
               │                             │   • escrow UTxO exists      │
               │                             │   • datum matches request   │
               │                             │── settle(proof, req) ───►   │
               │                             │   build claim tx:           │
               │                             │     input:  escrow_ref      │
               │                             │     output: receiver addr   │
               │                             │     redeemer: proof bytes   │
               │                             │     witness: relayer key    │
               │                             │── sign + submit ───────────►│
               │◄─ 200 + data ──────────────-│                             │
```

The facilitator never holds the payer's funds outright — the escrow UTxO
sits at a script address, and Plutus enforces the claim conditions.

### 3.2 Plutus validator (`x402-escrow.plutus`)

Datum:

```scala
case class EscrowDatum(
  payerKeyHash:    ByteString,     // 28-byte Blake2b-224 of payer's Ed25519 vk
  claimMessageHash: ByteString,    // Blake2b-256 of the CIP-8 message bytes
  receiver:        Address,        // exact recipient (no fuzz)
  amount:          Lovelace,       // exact amount payable
  validBefore:     POSIXTime,      // claim window upper bound
  refundAfter:     POSIXTime,      // payer can sweep after this slot
)
```

Two redeemers:

- `Claim(coseSign1Bytes, coseKeyBytes)` — facilitator path. The
  validator:
  1. Decodes the COSE_Key, extracts the Ed25519 verification key, and
     checks that `Blake2b224(vk) == datum.payerKeyHash`.
  2. Decodes the COSE_Sign1, reconstructs the Sig_Structure, and
     verifies the Ed25519 signature against the vk.
  3. Checks that the COSE_Sign1 payload bytes hash to
     `datum.claimMessageHash`.
  4. Checks the Tx outputs include exactly `datum.amount` lovelace at
     `datum.receiver`.
  5. Checks the Tx validity range upper bound `<= datum.validBefore`.

- `Refund` — payer path. The validator checks:
  1. The Tx is signed by `datum.payerKeyHash` (standard PubKeyHash
     witness, not CIP-8).
  2. The Tx validity range lower bound `>= datum.refundAfter`.

The validator does **not** depend on the redeemer's CIP-8 message
*content* — it only checks the hash. The semantic agreement (what the
message contains) is enforced by the facilitator at `verify` time
before settling.

### 3.3 Off-chain (`x402-facilitator-cardano-scalus`)

```
┌────────────────────────────────────────────────────────────────┐
│  CardanoFacilitator (existing)                                  │
│    provider match                                               │
│      Blockfrost(...) → optimistic Ok                            │
│      Scalus(...)     → settler.submit(payload, req)             │
└────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────────┐
│  ScalusSettler trait (new — this module)                        │
│    def submit(p: PaymentPayload, r: PaymentRequirements)        │
│        : Future[SettleResult]                                   │
└────────────────────────────────────────────────────────────────┘
        │
        ├─ ScalusSettler.unimplemented   ← Phase 1 stub
        ├─ ScalusSettler.preprod(cfg)    ← Phase 4 — bloxbean impl
        └─ ScalusSettler.mainnet(cfg)    ← Phase 4 — bloxbean impl
```

Module: `x402-facilitator-cardano-scalus`.

Dependencies (added incrementally):

- Phase 1: only `x402-facilitator-cardano`, no SDK pulls.
- Phase 2: `org.scalus:scalus:*` for the Plutus compiler.
- Phase 4: `com.bloxbean.cardano:cardano-client-lib:*` for off-chain
  Tx construction; transitively pulls JNA + secp256k1 (already in our
  classpath via BouncyCastle).
- Phase 5: optional Ogmios WebSocket client — JDK `java.net.http`
  is enough for the v1 path.

### 3.4 Wire-format extension

The payload's `CardanoPaymentProof` stays binary-compatible. Two new
fields are *interpreted* (not added) when the provider is Scalus:

- The CIP-8 `message` bytes now contain a structured claim authorization
  rather than the raw `req.description`:

  ```
  ScalusClaimMessage := concat(
    "x402-scalus/v1",   // domain separation
    receiver_addr_bytes,
    amount_lovelace_be8,
    valid_before_posix_be8,
  )
  ```

- The payload carries an `escrowRef` field via the `nonce` slot
  (currently empty for Cardano) encoded as `txhash#index`.

These are backwards-compatible at the `CardanoPaymentProof` schema level
but require the client wallet (`Wallets.cardano`) to gain a
"Scalus mode" that signs the structured message rather than the
description. Out of scope for Phase 1.

## 4. Migration

- Existing `CardanoProvider.Blockfrost` flows: **no change**. The
  optimistic path remains operational.
- Existing `CardanoProvider.Scalus` enum case: **shape preserved**;
  its `signingKey`, `nodeSocket`, `ogmiosUrl`, `kupoUrl` fields are
  the inputs the Phase 4 `bloxbean` settler will consume.
- `CardanoProvider.Scalus` verification now expects the structured
  claim message and `authorization.nonce` escrowRef; this is additive
  to the existing optimistic Blockfrost verification path, which still
  signs `req.description`.
- `CardanoFacilitator.settle` currently has a hardcoded
  `Fail("Scalus settlement not yet implemented")` for the Scalus
  provider; Phase 1 replaces that with a delegation to a
  pluggable `ScalusSettler` (default: `unimplemented` stub that
  returns the same `Fail`). No behavior change for current callers.
- After Phase 4 lands, operators opt into Plutus settlement by
  constructing the facilitator with
  `CardanoFacilitator.scalus(config, ScalusSettler.preprod(cfg))`.

## 5. Phases

### Phase 1 — spec + module scaffolding

- This document.
- New module `x402-facilitator-cardano-scalus` with build.sbt entry.
- `ScalusSettler` trait + `ScalusSettler.unimplemented` stub.
- `CardanoFacilitator` accepts an optional `ScalusSettler` in its
  config; without one, Scalus provider still fails as today.
- Tests: stub returns `Fail` with the new message; existing
  Blockfrost tests stay green.

### Phase 2 — on-chain validator

- Plutus V3 validator in Scalus DSL: `src/main/scala/.../X402EscrowScript.scala`.
- Datum / redeemer types per §3.2.
- Compiled-script artifact committed to repo (or regenerated at
  test time, depending on Scalus stability).
- Unit tests with Scalus's test framework: Claim happy path,
  Refund happy path, all rejection branches.

#### Spike findings (2026-05-20) — what to address before retry

A first implementation attempt against Scalus 0.15.1 surfaced the
following blockers; the validator code was not committed. Capture
these so the next attempt starts informed:

1. **Package-name collision.** The library's top-level package is
   `scalus`. Our original module package `scalascript.x402.facilitator.scalus`
   shadowed it under any nested import resolution, breaking
   `import scalus.*`. **Fixed in this commit**: module package
   renamed to `scalascript.x402.facilitator.plutus`. Keep using
   `plutus` for any future Scalus-using code.

2. **upickle eviction.** Scalus 0.15.1 transitively requires
   `com.lihaoyi:upickle_3:4.4.2`; the x402 / client-blockfrost stack
   is pinned to `3.3.1` in ~21 places in `build.sbt`. sbt's
   semver-spec eviction treats the 3↔4 drift as a fatal error. A
   per-module `evictionErrorLevel := Level.Warn` is a tactical
   workaround but lets a 4.x JAR be selected at runtime under code
   compiled against 3.x — fragile.
   **Real fix**: project-wide upickle 3.3.1 → 4.4.2 bump before
   re-introducing the Scalus dep. Out of scope for the Phase 2 PR;
   a separate cleanup commit should land first.

3. **Scala-version drift.** Scalus 0.15.1 was built against Scala 3.3.7;
   we target 3.8.3. The `@Compile` macro and `derives FromData, ToData`
   resolution both work at compile time, but the `Validator` trait
   exposes deferred-inline methods for every Plutus V3 script purpose
   (`mint`, `spend`, `withdraw`, `propose`, `vote`) — overriding only
   `spend` triggers a "Deferred inline method mint cannot be invoked"
   error at the `PlutusV3.compile` site. The Phase 2 retry must
   either:
   - override all 5 purposes with explicit `fail` bodies, or
   - locate a Scalus base type that targets a single purpose, or
   - parameterize via `ParameterizedValidator` (jar inspection
     shows `scalus.cardano.onchain.plutus.v3.{ParameterizedValidator,
     DataParameterizedValidator}` exist — likely the right path).

4. **FromData derivation gotcha.** Custom datum / redeemer types
   compile cleanly with `derives FromData, ToData` **only when
   declared at top level**, not nested inside the `@Compile object`.
   The validator object can still reference them; just don't nest
   the definitions.

5. **`tx.signatories.contains(...)`** requires a `PubKeyHash` argument,
   not a raw `ByteString`. Use `PubKeyHash(byteString)` from
   `scalus.cardano.onchain.plutus.v1.PubKeyHash` (re-exported from v3).

6. **Documentation drift.** The Scalus 0.15.1 jar contents diverge
   from `scalus.org` docs in important paths:
   - `scalus.builtin.{ByteString, Data, FromData, ToData}` (docs) →
     `scalus.uplc.builtin.{ByteString, Data, FromData, ToData}` (actual)
   - No `scalus.builtin.FromDataInstances` / `ToDataInstances` —
     instances are auto-derivable on case classes / enums.

Retry path (Phase 2.1): land the upickle bump first as its own
commit, then re-add the Scalus dep with `ParameterizedValidator` as
the base, with all 5 purposes overridden defensively.

#### Phase 2 retry (2026-05-20) — what landed and what's still blocked

The upickle 3.3.1 → 4.4.2 bump (commit `b736c5a6`) unblocked the
Scalus library dependency, and the validator source (`X402EscrowScript`
+ `EscrowDatum` + `EscrowRedeemer`) now type-checks against Scalus 0.15.1
in our build. Design lessons applied:

- Single-purpose validator written as plain
  `@Compile object X402EscrowScript { inline def validate(scData: Data): Unit }`
  rather than extending `Validator` (avoids needing to override all six
  deferred-inline Plutus V3 purpose methods).
- Datum + Redeemer at top level with `derives FromData, ToData`.
- `PubKeyHash` from `scalus.cardano.onchain.plutus.v1` used for keyhash
  slots; `scalus.cardano.onchain.plutus.v3.ScriptContext` + `ScriptInfo`
  used to dispatch on `SpendingScript`.

But a **harder blocker** surfaced when trying to actually compile the
validator to UPLC:

7. **Scalus compiler plugin requires Scala 3.3.7 internal dotty APIs.**
   `PlutusV3.compile(...)` is not a library method — it's intercepted
   by the `scalus-plugin` compiler plugin. The plugin (latest 0.16.0)
   was built against Scala 3.3.7 and references
   `dotty.tools.dotc.core.Names$Designator`, which does not exist in
   Scala 3.8.3's compiler. Enabling the plugin in this build fails at
   load time with `NoClassDefFoundError`. Without the plugin,
   `PlutusV3.compile` throws a marker `RuntimeException` at first
   invocation.

   Available paths considered:
   - **Chosen** (2026-05-20): build the Plutus contract in a separate
     sub-project (`x402-escrow-plutus`) pinned to `scalaVersion :=
     "3.3.7"`. sbt's per-project `scalaVersion` override lets it
     co-exist with the rest of the build at 3.8.3. The plugin works
     under 3.3.7; the sub-project's `emitEscrowHex` sbt task writes
     the compiled CBOR hex to
     `x402-facilitator-cardano-scalus/src/main/resources/x402-escrow.plutus.hex`,
     which the main 3.8.3 module reads via the classloader. The
     sub-project deliberately depends on NO other repo modules — TASTy
     is not cross-Scala-version-compatible, so any cross-version edge
     would require duplicating types.
   - Rejected: wait for Scalus targeting Scala 3.8.x.
   - Rejected: downgrade the whole project (huge blast radius).

   Workflow after the validator source changes:
   ```bash
   sbt x402EscrowPlutus/emitEscrowHex          # regenerate hex
   sbt x402FacilitatorCardanoScalus/test       # verify
   git add x402-facilitator-cardano-scalus/src/main/resources/x402-escrow.plutus.hex
   ```

   `EscrowDatum` / `EscrowRedeemer` deliberately live ONLY in the
   3.3.7 sub-project right now. Phase 4 (off-chain claim Tx) will need
   matching off-chain types in the 3.8.3 module — they'll be
   hand-redeclared there since cross-version sharing is impossible.

### Phase 3 — escrow address + reference script deployment helpers

- `EscrowScript.address(network)` — derives the CIP-19 enterprise
  script address from the compiled validator + network header.
  **Landed (2026-05-27)** in the main 3.8.3 module by hashing the
  committed double-CBOR validator bytes with Blake2b-224 and encoding
  the script credential as `addr` / `addr_test` bech32. Golden values:
  `addr1wxj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qm75nhg`
  (mainnet) and
  `addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd`
  (preprod / preview).
- Operator helper to deploy a reference script (one-time, manual).
- Tests assert script-address stability against committed golden
  bech32 strings.

### Phase 4 — off-chain claim Tx via bloxbean

- `ScalusSettler.preprod(cfg)` / `.mainnet(cfg)` constructors.
- **Landed (2026-05-27):** `ScalusSettlerConfig`, typed
  `ClaimTxPlan`, injectable `ClaimTxBuilder`, and a submit pipeline
  that passes builder-produced CBOR to `BlockfrostClient.submitTx`.
- Tx building: input = escrow_ref, output = receiver + amount,
  collateral from relayer wallet, redeemer = CIP-8 proof bytes.
- **Landed (2026-05-27):** `EscrowRedeemerCodec` builds the bloxbean
  `PlutusData` for `Claim(coseSign1Bytes, coseKeyBytes)` as
  constructor alternative 0, and `ClaimTxPlan.claimRedeemer` exposes it
  to the transaction builder.
- **Landed (2026-05-27):** `BloxbeanClaimTxBuilder.draft` serializes a
  bloxbean `Transaction` skeleton containing the escrow script input,
  receiver output, Plutus V3 script, and Spend redeemer. It is not the
  production default because live script-cost evaluation and Preprod
  integration validation are still open.
- **Landed (2026-05-27):** the draft builder now carries optional
  `ScalusSettlerConfig.collateralRef` into the transaction body's
  collateral inputs and optional `relayerKeyHashHex` into
  `requiredSigners`. This pins the production transaction boundary
  without yet generating the relayer vkey witness.
- **Landed (2026-05-27):** the draft builder now also carries explicit
  `feeLovelace`, `ttlSlot`, and `validityStartSlot` config into the
  transaction body, computes the script data hash from the claim
  redeemer via bloxbean `ScriptDataHashGenerator`, and attaches a
  relayer `VkeyWitness` using `TransactionSigner`.
- **Still open:** the default `BloxbeanClaimTxBuilder` fails
  explicitly until protocol-params-backed fee balancing, real ex-unit
  evaluation, and integration validation are implemented on top of
  `cardano-client-lib` / Blockfrost.
- **Landed (2026-05-27):** `BlockfrostClient.getProtocolParams()`
  reads `/epochs/latest/parameters` into typed
  `BlockfrostProtocolParams` (`minFeeA`, `minFeeB`, execution prices,
  collateral bounds, and Plutus cost models). This is the data source
  for the planned protocol-params-backed fee balancer.
- **Landed (2026-05-27):** `ScalusFeeBalancer` estimates protocol
  min-fee from Blockfrost params and serialized transaction size, and
  `BloxbeanClaimTxBuilder.draftBalanced(...)` performs a two-pass draft
  rebuild so the body fee matches the final CBOR size. Script ex-units
  can be added to the estimate, but live ex-unit evaluation remains
  open until the builder has a node-backed `TransactionEvaluator`.
- **Landed (2026-05-27):** static `ScalusExUnits` now flows from
  `ScalusSettlerConfig.claimExUnits` into `ClaimTxPlan`, the claim
  redeemer's `ExUnits`, and the balanced fee estimate. Operators can
  pin conservative ex-units before live evaluator wiring lands.
- Witnessing: relayer Ed25519 signature on the Tx body hash.
- Submission via Blockfrost `submitTx` (already in our client) —
  Ogmios variant added later if needed. The Blockfrost submit path is
  covered by unit tests with an injected builder.
- Integration tests against preprod with a funded relayer + a
  pre-deposited escrow UTxO; CI-skipped when env vars unset.

### Phase 5 — client-side Scalus-mode wallet

- `Wallets.cardano(hex, network, scalusMode = true)` — signs the
  structured claim message instead of the request description.
  **Landed (2026-05-27)** as a client-side mode flag; the default
  remains the legacy description-signing path for the optimistic
  Blockfrost provider.
- `PayloadBuilder` emits `escrowRef` via the `nonce` slot when the
  payload references an escrow UTxO. **Landed (2026-05-27)** via
  `PaymentRequirements.scalusEscrowRef`. `x402-core` also exposes
  `ScalusEscrowRef` to parse and validate the canonical
  `<64-hex-txhash>#<output-index>` form before the future Tx-builder
  consumes it.
- `CardanoProvider.Scalus` verifies the structured claim-message proof
  before settlement. **Landed (2026-05-27)**: the Scalus provider
  requires `authorization.nonce` to carry the escrowRef, verifies CIP-8
  against `x402-scalus/v1 || receiver_bytes || amount || validBefore`,
  and leaves UTxO/datum validation to the Phase 4 settler. The binary
  encoder is centralized in `x402-core` as `ScalusClaimMessageCodec`
  so client and facilitator cannot drift.
- Tests: round-trip a Scalus-mode payment through the validator's
  off-chain claim flow (using Phase 4 settler).

### Phase 6 — deposit-side ergonomics

- `EscrowDeposit.build(payerWallet, req)` helper — payer-side Tx
  that locks lovelace at the escrow script address.
- Example `examples/x402-cardano-scalus.ssc` showing the full
  deposit → 402 → claim → 200 flow on preprod.

## 6. Testing strategy

- **Phase 1**: unit tests for the trait stub + facilitator
  delegation. No real chain interaction.
- **Phase 2**: Scalus's evaluator running the compiled validator
  against constructed datums / redeemers / script contexts.
- **Phase 3**: golden-string tests for script address per network.
- **Phase 4**: integration tests skipped by default (`assume(env
  set)`), executed in CI under a `cardano-preprod-it` profile with
  a funded relayer secret.
- **Phase 5+**: round-trip tests crossing the client → facilitator
  → on-chain validator boundary.

## 7. Open questions

- **Plutus version**: V3 (current) or V2 (more widely supported on
  cold mainnet wallets)? Phase 2 picks one and we live with it.
- **Reference script vs inline script**: reference scripts make
  every claim Tx ~7KB smaller, but require a one-time deploy + a
  stable on-chain anchor. Default to reference scripts for
  Preprod / Mainnet, inline for ephemeral test environments.
- **Datum size**: 28+32+57+8+8+8 ≈ 141 bytes minimum, plus
  CBOR overhead. Within limits but worth measuring.
- **Time source**: validity ranges depend on slot ↔ POSIX-time
  conversion. Phase 4 must agree with Kupo / Blockfrost on the
  conversion strategy (era genesis params).
- **Multi-asset payments**: the validator outputs lovelace only in
  Phase 2; native-asset escrows (USDA, DJED) require an extra
  datum slot and validator branch. Punt to a Phase 7 follow-up.
- **Refund timing**: should `refundAfter` be relative to `validBefore`
  (e.g., +24h) or independent? Set it independently to allow
  long-tail recovery without forcing claims to be immediate.

## 8. References

- CIP-8: <https://cips.cardano.org/cips/cip8/>
- CIP-19 (addresses): <https://cips.cardano.org/cips/cip19/>
- Scalus: <https://scalus.org>
- bloxbean cardano-client-lib: <https://github.com/bloxbean/cardano-client-lib>
- Existing optimistic facilitator: `x402-facilitator-cardano/.../CardanoFacilitator.scala`
- x402 protocol: [`docs/x402.md`](x402.md)
