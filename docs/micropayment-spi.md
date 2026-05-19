# Micropayment Platform SPI — Plan

Status: **draft / planning**. Source of truth for the micropayment
platform layer that sits **above** [`blockchain-spi`](blockchain-spi.md)
and [`wallet-spi`](wallet-spi.md), and is a **peer** of `x402-*`.
Until each phase below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this design.

## 1. Goals

- **Amortise on-chain fees for microtransactions.** When the per-request
  payment amount is smaller than the gas cost of an on-chain tx (e.g.
  $0.001/API call vs $0.01 gas on L2), an off-chain channel accumulates
  value and settles on-chain infrequently. The economics are addressed
  at the platform level, not by the caller.
- **One `MicropaymentChannel` interface, multiple strategies.** Threshold
  batching, EVM state channels, Hydra heads, and probabilistic tickets
  are all behind the same trait. The server and client code do not change
  when the strategy changes.
- **Build on `blockchain-spi` and `wallet-spi`, not around them.**
  `ChainAdapter`, `Asset`, `ChainId`, `RawSigner`, and `AccountStrategy`
  are consumed here; they are not redefined.
- **Peer of x402, not a replacement.** x402 handles stateless per-request
  payments. This SPI handles stateful session channels. The two coexist;
  `ThresholdBatching` uses x402's `Facilitator` as its settlement backend.
- **Forward-compatible with x402 refactoring.** x402 will be refactored to
  use `blockchain-spi` in the future. Phase 6 of this SPI switches
  `ThresholdBatching` to use the `blockchain-spi` `ChainAdapter` directly,
  with no API change visible to callers.
- **Cross-compile JVM + Scala.js** for the core SPI and the probabilistic
  and client modules. Server-side and chain-dependent modules are JVM-only.

## 2. Non-goals

- We are **not** replacing x402 or its per-request payment schemes. The
  `PaymentScheme.Stream` in `x402-core` remains and is the right choice
  when L2 fees are already low enough.
- We are **not** defining chain abstractions. `ChainAdapter`, `ChainId`,
  `Asset`, and `TypedData` are defined in
  [`blockchain-spi`](blockchain-spi.md).
- We are **not** defining key management or signing strategies. `RawSigner`,
  `Vault`, and `AccountStrategy` are defined in
  [`wallet-spi`](wallet-spi.md).
- We are **not** building a payment routing / fee-optimisation oracle. The
  caller chooses the `ChannelKind`; we implement it.
- We are **not** implementing cross-chain channels (payer on EVM, payee on
  Cardano) — deferred to a future bridge layer after Phase 5.
- We are **not** writing smart-contract code in this document. The EVM
  `PaymentChannel` contract required by `StateChannel` is a separate
  deliverable (compiled with the smart-contracts toolchain).

## 3. Where this SPI sits

```
  ┌──────────────────────────────────────────────────────────────────┐
  │  micropayment-server        micropayment-client                  │
  │  withMicropayment(…)        MicropaymentHttpClient               │
  └───────────────────────────────┬──────────────────────────────────┘
                                  │ uses
  ┌───────────────────────────────▼──────────────────────────────────┐
  │                  micropayment-spi                                │  ← this document
  │   MicropaymentChannel / ChannelProvider / SettlementPolicy /     │
  │   ChannelConfig / ChannelState / PaymentReceipt / ChannelKind    │
  └──────┬─────────────────┬─────────────────────┬───────────────────┘
         │                 │                     │
         ▼                 ▼                     ▼
  ┌────────────┐  ┌──────────────────┐  ┌───────────────────────────┐
  │ wallet-spi │  │ blockchain-spi   │  │ x402-core                 │
  │ RawSigner  │  │ ChainAdapter     │  │ Facilitator / NonceStore  │
  │ Vault      │  │ ChainId / Asset  │  │ (settlement backend only) │
  │ Strategy   │  │ TypedData        │  └───────────────────────────┘
  └────────────┘  └──────────────────┘
```

Strict invariants:

1. **No upward dependency.** `micropayment-spi` does not depend on
   `micropayment-server` or `micropayment-client`.
2. **x402 dependency is settlement-only.** Only `micropayment-threshold`
   imports `x402-core`; the SPI itself does not.
3. **No cross-variant dependency.** `micropayment-channel-evm` does not
   depend on `micropayment-hydra` or `micropayment-probabilistic`.

## 4. Module layout

```
micropayment-spi                 # core traits + types — cross-compile JVM + Scala.js
micropayment-threshold           # ThresholdBatching impl — JVM
                                 #   settlement backend: x402-core Facilitator
micropayment-channel-evm         # EVM state channels — JVM
                                 #   requires blockchain-evm (blockchain-spi Phase 2)
micropayment-hydra               # Cardano Hydra heads — JVM
                                 #   requires Hydra node WebSocket API
micropayment-probabilistic       # lottery micropayments — cross-compile JVM + Scala.js
micropayment-server              # withMicropayment middleware — JVM
                                 #   requires x402-server + micropayment-spi
micropayment-client              # MicropaymentHttpClient — cross-compile JVM + Scala.js
```

Modules **consumed** (not owned here):

- `blockchain-spi` — `ChainId`, `Asset`, `ChainAdapter` (see
  [`blockchain-spi.md`](blockchain-spi.md))
- `wallet-spi` — `AccountStrategy` (see [`wallet-spi.md`](wallet-spi.md))
- `x402-core` — `Facilitator`, `NonceStore`, `PaymentRequirements`

## 5. Core types

```scala
package scalascript.micropayment.spi

import scalascript.blockchain.spi.{ChainId, Asset}
import scala.concurrent.{Future, ExecutionContext}

/** Opaque identifier for a channel instance. Generated on open.
 *  Must be globally unique across providers; see open question §12.2. */
type ChannelId = String

/** Configuration supplied when opening a new channel. */
case class ChannelConfig(
  chain:            ChainId,
  asset:            Asset,
  payee:            String,          // address of the receiving party
  initialDeposit:   BigInt,          // wei / lovelace / lamport atomic units
  settlementPolicy: SettlementPolicy,
  timeout:          scala.concurrent.duration.Duration,
)

/** Live state of an open channel. Immutable snapshot; fetch fresh from
 *  `MicropaymentChannel.state` for up-to-date values. */
case class ChannelState(
  channelId:    ChannelId,
  sequence:     Long,                // monotonically increasing per receipt
  offChainPaid: BigInt,              // accumulated off-chain since last settlement
  onChainPaid:  BigInt,              // total settled on-chain
  openSince:    java.time.Instant,
  lastActivity: Option[java.time.Instant],
)

/** Signed proof of a single payment increment. The payee holds the
 *  latest valid receipt as the claim against the channel deposit. */
case class PaymentReceipt(
  channelId:  ChannelId,
  sequence:   Long,
  amount:     BigInt,                // this increment only
  cumulative: BigInt,                // running total this session (offChainPaid + amount)
  payerSig:   Array[Byte],           // signed over (channelId, sequence, cumulative)
  timestamp:  Long,                  // unix millis
)

/** Outcome of an on-chain settlement attempt. */
enum SettlementResult:
  case Ok(txHash: String, settled: BigInt)
  case Partial(txHash: String, settled: BigInt, remaining: BigInt)
  case Fail(reason: String)
```

## 6. MicropaymentChannel trait

```scala
package scalascript.micropayment.spi

import scala.concurrent.Future

trait MicropaymentChannel:
  /** Stable identifier for this channel instance. */
  def channelId: ChannelId

  /** Latest known state snapshot. Reflects the last committed receipt
   *  and the last on-chain settlement. May be stale by one in-flight
   *  `pay` call; call `availableBalance` for a confirmed figure. */
  def state: ChannelState

  /** Remaining spendable balance (deposit − offChainPaid − onChainPaid).
   *  May require an on-chain query for deposit-backed variants. */
  def availableBalance: Future[BigInt]

  // ── payer side ───────────────────────────────────────────────────

  /** Sign and send a payment receipt for `amount` atomic units.
   *  Increments `state.sequence` and `state.offChainPaid` locally.
   *  Delivers the receipt to the payee according to the provider's
   *  transport (HTTP header, WS message, etc.). */
  def pay(amount: BigInt, memo: String = ""): Future[PaymentReceipt]

  // ── payee side ───────────────────────────────────────────────────

  /** Record an incoming receipt. Verifies the payer's signature and
   *  that `receipt.sequence > state.sequence` (replay protection).
   *  Updates local state. Triggers settlement if `settlementPolicy`
   *  says so after the update. */
  def receive(receipt: PaymentReceipt): Future[Unit]

  // ── settlement ───────────────────────────────────────────────────

  /** Trigger an on-chain settlement immediately, regardless of policy.
   *  The settled amount is claimed from the on-chain deposit using the
   *  latest valid receipt. Resets `state.offChainPaid` to 0 on success. */
  def settle(): Future[SettlementResult]

  /** Perform final settlement and mark the channel closed.
   *  Any remaining deposit is returned to the payer. */
  def close(): Future[SettlementResult]
```

**Key invariants:**

- `PaymentReceipt.payerSig` covers `(channelId, sequence, cumulative)`.
  The payee always keeps only the receipt with the highest `sequence`; it
  is the only one that can be submitted on-chain.
- `sequence` is monotonically increasing and checked by `receive`. A
  duplicate or reordered receipt is rejected with a failed `Future`.
- `cumulative = onChainPaid + offChainPaid + amount`. The on-chain
  contract validates cumulative ≥ all previous claims.

## 7. SettlementPolicy

```scala
package scalascript.micropayment.spi

trait SettlementPolicy:
  /** Called by `receive` after updating local state.
   *  Returns true if the channel should settle now. */
  def shouldSettle(state: ChannelState): Boolean

object SettlementPolicy:
  /** Settle when accumulated off-chain amount reaches `minAmount`. */
  def threshold(minAmount: BigInt): SettlementPolicy =
    state => state.offChainPaid >= minAmount

  /** Settle when the channel has been open longer than `d` since the
   *  last settlement (tracked via `state.lastActivity`). */
  def timeInterval(d: scala.concurrent.duration.Duration): SettlementPolicy =
    state =>
      state.lastActivity.exists { t =>
        java.time.Instant.now().toEpochMilli - t.toEpochMilli >= d.toMillis
      }

  /** Never settle automatically; only on explicit `settle()` or `close()`. */
  val onClose: SettlementPolicy = _ => false

  /** Settle with probability `p` on each receipt (0.0 = never, 1.0 = always).
   *  Used by `Probabilistic` channels internally; can be composed with others. */
  def probabilistic(p: Double): SettlementPolicy =
    _ => scala.util.Random.nextDouble() < p

  /** Settle if ANY of the given policies says to. */
  def any(ps: SettlementPolicy*): SettlementPolicy =
    state => ps.exists(_.shouldSettle(state))

  /** Settle only if ALL of the given policies say to. */
  def all(ps: SettlementPolicy*): SettlementPolicy =
    state => ps.forall(_.shouldSettle(state))
```

## 8. ChannelProvider SPI

```scala
package scalascript.micropayment.spi

import scala.concurrent.{Future, ExecutionContext}

enum ChannelKind:
  /** Off-chain receipt accumulation → batch on-chain settlement.
   *  Uses x402 Facilitator as the settlement backend. */
  case ThresholdBatching
  /** EVM payment channel contract: on-chain deposit, off-chain signed
   *  state updates, unilateral close after dispute window. */
  case StateChannel
  /** Cardano Hydra L2 isomorphic state channel: both parties commit
   *  UTxOs to a Hydra head, transact off-chain at ~zero fees, close
   *  back to mainchain to finalise. */
  case HydraHead
  /** Lottery-based: no deposit, no on-chain state. Each receipt is a
   *  probabilistic ticket; expected value == claimed amount. */
  case Probabilistic
  /** Pass-through for chains whose native fees are already low enough
   *  (Base, Arbitrum, Starknet). Every request is a real on-chain tx
   *  via x402 Exact scheme. No off-chain accumulation. */
  case L2Native

trait ChannelProvider:
  def kind: ChannelKind

  /** Open a new channel according to `config`.
   *  The payer's key material is resolved via `config`'s `AccountStrategy`
   *  (injected at construction time for each concrete provider). */
  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel]

  /** Restore a previously-opened channel by id (e.g. after a server
   *  restart). Returns `None` if the channel is unknown or expired. */
  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]]

  /** List all channels still considered open by this provider. */
  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]]
```

## 9. Server integration

```scala
package scalascript.micropayment.server

import scalascript.micropayment.spi.*
import scala.concurrent.{Future, ExecutionContext}

/** HTTP middleware for session-based micropayment channels.
 *
 *  On first request (no `X-Channel-Id` header): opens a new channel
 *  via `provider.open(config)`, returns the channel id in the response
 *  header `X-Channel-Id`.
 *
 *  On subsequent requests: calls `provider.restore(id)`, then validates
 *  the `X-Payment-Receipt` header (base64-encoded JSON `PaymentReceipt`),
 *  calls `channel.receive(receipt)`, and only invokes `handler` on success.
 *
 *  Settlement is triggered transparently by the channel's `SettlementPolicy`
 *  inside `channel.receive`. The server never calls `settle()` directly
 *  unless the handler itself does so. */
def withMicropayment(
  provider: ChannelProvider,
  config:   ChannelConfig,
)(
  handler: (Request, MicropaymentChannel) => Future[Response]
)(using ExecutionContext): Request => Future[Response]
```

HTTP headers used by this middleware:

| Header | Direction | Meaning |
|---|---|---|
| `X-Channel-Id` | request + response | Identifies an existing channel; absent on first request; always echoed back |
| `X-Payment-Receipt` | request | Base64-encoded JSON `PaymentReceipt` signed by payer |
| `X-Channel-Balance` | response | Current `availableBalance` as a decimal string (informational) |

On receipt verification failure the middleware responds `402 Payment Required`
with a JSON body following the x402 `PaymentRequirements` shape so existing
x402 clients can degrade gracefully.

## 10. Client integration

```scala
package scalascript.micropayment.client

import scalascript.micropayment.spi.*
import scalascript.wallet.spi.AccountStrategy
import scala.concurrent.{Future, ExecutionContext}

/** HTTP client that transparently manages a `MicropaymentChannel`.
 *
 *  On the first request the client calls `provider.open(config)` and
 *  stores the returned `MicropaymentChannel`. On each subsequent request
 *  it calls `channel.pay(amount)` and attaches the resulting receipt to
 *  the `X-Payment-Receipt` header. The `X-Channel-Id` header is always
 *  sent. If the server returns a new `X-Channel-Id` (e.g. after a server
 *  restart) the client calls `provider.restore` or re-opens as needed. */
class MicropaymentHttpClient(
  provider: ChannelProvider,
  config:   ChannelConfig,
  backend:  HttpBackend,
)(using ExecutionContext):
  def get(
    url:     String,
    amount:  BigInt,
    headers: Map[String, String] = Map.empty,
  ): Future[HttpResponse]

  def post(
    url:     String,
    body:    String,
    amount:  BigInt,
    headers: Map[String, String] = Map.empty,
  ): Future[HttpResponse]

  /** Explicitly settle and close the channel. Waits for the on-chain
   *  settlement result before returning. */
  def closeChannel(): Future[SettlementResult]

  /** Latest local state snapshot. `None` until the first request. */
  def channelState: Option[ChannelState]
```

`HttpBackend` is a minimal cross-compile trait (two methods: `get` /
`post`). JVM impl wraps `java.net.http.HttpClient`; Scala.js impl wraps
`fetch`.

## 11. Per-variant implementation sections

### 11.1 ThresholdBatching (`micropayment-threshold`)

**How it works:**

```
Payer                           Payee / Server
  │                                  │
  │──── pay(amount) ────────────────►│  receive(receipt): verify sig + seq
  │◄─── PaymentReceipt ─────────────│  store latest receipt in ReceiptStore
  │                                  │  if shouldSettle → settle()
  │                                  │    Facilitator.settle(TokenTransferAuthorized)
  │                                  │    resets offChainPaid to 0
```

Each call to `pay` produces a receipt signed over the EIP-3009
`TransferWithAuthorization` digest: `(from, to, cumulative, validAfter,
validBefore, nonce)` encoded as EIP-712 typed data. The payer signs the
*cumulative* amount; the payee can always submit the latest receipt for
the largest valid claim.

On settlement the server submits the receipt to its `Facilitator`:

```scala
package scalascript.micropayment.threshold

import scalascript.micropayment.spi.*
import scalascript.x402.core.{Facilitator, NonceStore}
import scalascript.wallet.spi.AccountStrategy

class ThresholdBatchingProvider(
  facilitator: Facilitator,
  nonceStore:  NonceStore,
  strategy:    AccountStrategy,
  receiptStore: ReceiptStore,        // see open question §12.1
) extends ChannelProvider:
  def kind = ChannelKind.ThresholdBatching
  // …
```

**Key config:**

```scala
case class ThresholdConfig(
  facilitator:  Facilitator,
  nonceStore:   NonceStore,
  receiptStore: ReceiptStore,
  minSettlement: BigInt,             // overrides ChannelConfig.settlementPolicy default
)
```

**Settlement mechanics:**

- The payee holds the latest signed receipt.
- Settlement calls `facilitator.settle(paymentPayload)` which encodes
  the receipt as an EIP-3009 `transferWithAuthorization` and broadcasts
  it on-chain.
- The `NonceStore` prevents the same nonce from being used twice (replay
  protection both on-chain and off-chain).
- On timeout, the payee submits the last known receipt; any excess
  deposit over the cumulative amount stays with the payer (EIP-3009
  validity window controls expiry).
- **No dispute window** — the payer signs the cumulative; there is
  nothing to dispute. Trust is on the payee side: the payee must hold
  the latest receipt, not an older one.

**Chain support:** Any EVM chain where EIP-3009 `transferWithAuthorization`
is available (USDC on Base, Ethereum mainnet, Polygon, Arbitrum, Optimism,
Base Sepolia). Cardano will be supported after Phase 6 (blockchain-spi
Cardano + CIP-8 equivalent).

**Tradeoffs:**

| Dimension | Value |
|---|---|
| Latency per payment | Off-chain only; sub-millisecond |
| Counterparty trust | Payee-trusted (must hold latest receipt honestly) |
| Fee amortisation | Full: 1 on-chain tx per `threshold / minPayment` receipts |
| Setup cost | Zero (no contract deploy) |
| Failure mode | Payee submits old receipt → payer overpays nothing; payer stops paying → payee submits latest |

### 11.2 StateChannel / EVM (`micropayment-channel-evm`)

**How it works:**

```
Payer                              Payee / Server
  │                                     │
  │── deploy PaymentChannel ───────────►│  (or join existing contract)
  │   (deposit locked in contract)      │
  │                                     │
  │── pay(amount): sign state ─────────►│  receive(receipt): verify sig + seq
  │   (sequence, cumulative, sig)        │  store latest signed state
  │                                     │
  │       (no on-chain tx per payment)  │
  │                                     │
  │── close() ─────────────────────────►│
         OR payee unilateral close       │── submitState(latestReceipt) ──► contract
                                         │   (after dispute window: finalise)
```

The `PaymentChannel` contract locks the payer's deposit and accepts
`submitFinalState(sequence, cumulative, payerSig)`. The dispute window
allows the payer to challenge a stale submission from the payee.

```scala
package scalascript.micropayment.evm

import scalascript.micropayment.spi.*
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.wallet.spi.AccountStrategy

class StateChannelProvider(
  chain:         EvmChainAdapter,        // requires blockchain-evm Phase 1+
  strategy:      AccountStrategy,
  contractAddr:  Option[String] = None,  // None = deploy fresh per channel
  disputeWindow: scala.concurrent.duration.Duration,
) extends ChannelProvider:
  def kind = ChannelKind.StateChannel
  // …
```

**Key config:**

```scala
case class StateChannelConfig(
  contractAddress: Option[String],   // pre-deployed; None = deploy on open
  disputeWindow:   scala.concurrent.duration.Duration,
  gasBuffer:       Double = 1.2,     // multiplier over estimated gas
)
```

**Settlement mechanics:**

- Cooperative close: both parties call `close()`; contract releases
  deposit minus cumulative to payee immediately.
- Unilateral close (payee): payee submits latest signed state; after
  `disputeWindow` the payee can call `finalise()`.
- Dispute (payer): if payee submits stale state, payer submits a receipt
  with a higher `sequence` during the window; contract honours the
  higher-sequence submission.
- Timeout: if the payer disappears, the payee submits the last valid
  receipt after `timeout` and recovers up to `cumulative`.

**Chain support:** Any EVM chain with a deployed `PaymentChannel`
contract. The contract bytecode is part of `micropayment-channel-evm`;
deployment is via `TxIntent.Deploy` from `blockchain-spi`.

**Tradeoffs:**

| Dimension | Value |
|---|---|
| Latency per payment | Off-chain only; sub-millisecond |
| Counterparty trust | Neither party trusted (contract enforces) |
| Fee amortisation | 2 on-chain txs per channel (open + close); all payments free |
| Setup cost | 1 deploy tx + deposit lock-up |
| Failure mode | Dispute window prevents either party from cheating |

### 11.3 HydraHead / Cardano (`micropayment-hydra`)

**How it works:**

```
Payer node                    Hydra Head                   Payee node
  │                               │                             │
  │── commit(UTxO) ──────────────►│◄────────── commit(UTxO) ───│
  │                         (head opens)                        │
  │                               │                             │
  │── Cardano tx (off-chain) ────►│──► fanout to payee UTxO ───│
  │   (full tx, zero fees,         │   (inside head; instant)   │
  │    ~infinite speed)            │                             │
  │                               │                             │
  │── close() ───────────────────►│  (finalise to mainchain)   │
```

Both payer and payee commit UTxOs to a Hydra Head managed by a Hydra
node (WebSocket API). Inside the head, payments are full Cardano
transactions at ~zero fees and instant finality. The head is closed
to mainchain when either party calls `close()` or the head times out.

```scala
package scalascript.micropayment.hydra

import scalascript.micropayment.spi.*
import scalascript.wallet.spi.AccountStrategy

class HydraHeadProvider(
  hydraWsUrl: String,                // ws://hydra-node:4001
  strategy:   AccountStrategy,
  headId:     Option[String] = None, // None = open new head
) extends ChannelProvider:
  def kind = ChannelKind.HydraHead
  // …
```

**Key config:**

```scala
case class HydraConfig(
  hydraNodeUrl: String,              // WebSocket endpoint of the Hydra node
  headId:       Option[String],      // existing head to join, or None to open
  contestationPeriod: scala.concurrent.duration.Duration,
)
```

**Settlement mechanics:**

- On-chain commit phase: both parties lock UTxOs into the head contract.
- Inside the head: each payment is a full Cardano tx (signed, validated
  by the head's local protocol rule, no on-chain broadcast).
- Close: either party can initiate; mainchain records the final UTxO
  distribution after the contestation period.
- Contest: if one party closes with a stale snapshot, the other submits
  the newer snapshot during `contestationPeriod`.

**Chain support:** Cardano mainnet and preprod. Requires a running Hydra
node (see open question §12.3).

**Tradeoffs:**

| Dimension | Value |
|---|---|
| Latency per payment | ~1 ms inside head (local WS round-trip) |
| Counterparty trust | Neither party trusted (Hydra protocol enforces) |
| Fee amortisation | ~2 mainchain txs per channel; infinite free txs inside |
| Setup cost | Both parties must be online at open; requires Hydra node |
| Failure mode | Contestation period protects against stale close |

### 11.4 Probabilistic (`micropayment-probabilistic`)

**How it works:**

```
Payer                               Payee
  │                                    │
  │── LotteryTicket(hash(preimage),    │
  │     commitment, claimedAmount) ───►│
  │                                    │  serverSalt ← random
  │◄── serverSalt ─────────────────────│
  │                                    │
  │── reveal(preimage) ───────────────►│
  │                                    │  wins iff hash(preimage XOR serverSalt) < threshold
  │                                    │  (threshold = claimedAmount / maxPayout)
  │                                    │  accumulate winning tickets
  │                                    │── batch-redeem on-chain periodically
```

No deposit, no contract, no setup. Each payment is a probabilistic
commitment. The expected payout per ticket equals `claimedAmount`. The
server accumulates winning tickets and submits a batch redemption
transaction when the total value justifies the on-chain fee.

```scala
package scalascript.micropayment.probabilistic

import scalascript.micropayment.spi.*

case class LotteryTicket(
  commitment:    Array[Byte],        // hash(preimage)
  claimedAmount: BigInt,
  payerAddress:  String,
  expiry:        Long,               // unix millis
)

case class LotteryReveal(
  preimage: Array[Byte],
  salt:     Array[Byte],             // serverSalt echoed back
)

class ProbabilisticProvider(
  chain:            scalascript.blockchain.spi.ChainAdapter,
  strategy:         scalascript.wallet.spi.AccountStrategy,
  maxPayout:        BigInt,          // batch redemption threshold
  redeemBatchSize:  Int = 50,
) extends ChannelProvider:
  def kind = ChannelKind.Probabilistic
  // …
```

**Settlement mechanics:**

- The server challenges with a random `serverSalt` per ticket.
- Win condition: `keccak256(preimage XOR serverSalt) < threshold`.
  `threshold = claimedAmount * 2^256 / maxPayout` (scaled to hash space).
- Expected value: `P(win) * maxPayout = claimedAmount` per ticket.
- Winning tickets are accumulated off-chain. The server redeems a batch
  (one on-chain tx) when `redeemBatchSize` tickets are won or a time
  window elapses (see open question §12.4).
- No dispute mechanism needed — the protocol is non-interactive after
  the reveal. A cheating payer whose `hash(preimage XOR salt) >= threshold`
  simply loses the round; the server ignores the ticket.

**Chain support:** Any chain; no contract required. Redemption uses
`TxIntent.TokenTransfer` on EVM or the equivalent on Cardano / Solana.
The lottery math is pure and cross-compiles to Scala.js.

**Tradeoffs:**

| Dimension | Value |
|---|---|
| Latency per payment | 1 round-trip (ticket + challenge); sub-5 ms |
| Counterparty trust | Payee-trusted for correct lottery outcome |
| Fee amortisation | `redeemBatchSize / expectedWinRate` receipts per on-chain tx |
| Setup cost | Zero (no contract, no deposit) |
| Failure mode | Payer sends non-winning tickets → payee receives nothing (no obligation to serve) |

### 11.5 L2Native (degenerate pass-through)

**How it works:**

`L2Native` is not a true micropayment channel — it is a thin adapter
that delegates every request to the existing x402 `Exact` scheme on a
cheap L2 chain (Base: ~$0.0001/tx, Arbitrum, Optimism, Starknet).
There is no off-chain accumulation; every `pay()` call results in a
real on-chain transaction.

The `MicropaymentChannel` interface is satisfied trivially:

```scala
package scalascript.micropayment.threshold   // or a tiny standalone shim

import scalascript.micropayment.spi.*

class L2NativeChannel(
  underlying: scalascript.x402.server.withPayment,
  config:     ChannelConfig,
) extends MicropaymentChannel:
  def settle()  = Future.successful(SettlementResult.Ok("n/a", state.offChainPaid))
  def close()   = settle()
  def pay(amount: BigInt, memo: String = "") =
    // submits x402 Exact payment immediately; no accumulation
    ???
  // …
```

**Module:** no new module. Use `x402-server` with `PaymentScheme.Exact`
on Base/Arbitrum/Optimism directly. The `ChannelProvider` wrapper in
`micropayment-threshold` can return an `L2NativeChannel` when
`ChannelKind.L2Native` is requested, reusing existing x402 infrastructure.

**Settlement mechanics:** Every receipt is an on-chain tx. No batching,
no dispute, no timeout management. Chain fees are the only cost.

**Chain support:** Any EVM L2 with fees below the payment granularity.
Practically: Base, Arbitrum One, Optimism, Starknet (via x402
Starknet facilitator when available).

**Tradeoffs:**

| Dimension | Value |
|---|---|
| Latency per payment | On-chain tx inclusion time (~1–2 s on L2) |
| Counterparty trust | Neither (on-chain settlement per tx) |
| Fee amortisation | None — each payment is on-chain |
| Setup cost | Zero |
| Failure mode | Standard x402 flow; server returns 402 on verify failure |

## 12. Migration from x402 Stream

The current `PaymentScheme.Stream` in `x402-core` is a metered billing
scheme: one `TransferWithAuthorization` per request covering `ratePerUnit`
of consumed units. It differs from `MicropaymentChannel` in three ways:

| Dimension | `PaymentScheme.Stream` (x402) | `MicropaymentChannel` (this SPI) |
|---|---|---|
| State | Stateless — no open/close lifecycle | Stateful — channel has explicit open + close |
| Per-request on-chain cost | Real settlement via `Facilitator` per request | Amortised — settlement batched per policy |
| Chain coverage | EVM only (EIP-3009) | Multi-chain (per variant) |

**Migration path:**

- `Stream` stays in x402 for simple cases where L2 fees are acceptable
  and the stateless model is preferred.
- `ThresholdBatching` replaces `Stream` when fee amortisation is needed
  (e.g. high-volume API services, $0.001/call tiers).
- Migration at the call site:

  ```scala
  // Before (x402 Stream — one on-chain tx per request)
  withStreamPayment(facilitator, ratePerUnit = 1_000)(handler)

  // After (ThresholdBatching — batches up to threshold)
  withMicropayment(thresholdProvider, ChannelConfig(
    chain = ChainId("eip155:8453"),
    asset = usdcBase,
    payee = serverAddress,
    initialDeposit = BigInt(10_000_000),
    settlementPolicy = SettlementPolicy.threshold(BigInt(1_000_000)),
    timeout = 1.hour,
  ))(handler)
  ```

No breaking changes to existing x402 server/client code. The `Stream`
scheme, `withStreamPayment`, and `withPayment` middleware are unchanged.

## 13. Phases

Each phase is independently shippable per [`AGENTS.md`](../AGENTS.md) Rule 3.

### Phase 1 — `micropayment-spi` core traits (cross-compile, no impls)

Depends on: `blockchain-spi` Phase 1 (for `ChainId`, `Asset`),
`wallet-spi` Phase 1 (for `AccountStrategy`).

- [ ] `micropayment-spi` module — cross-compile JVM + Scala.js
- [ ] `ChannelId`, `ChannelConfig`, `ChannelState`, `PaymentReceipt`,
      `SettlementResult` (§5)
- [ ] `MicropaymentChannel` trait (§6)
- [ ] `SettlementPolicy` trait + all combinators (§7)
- [ ] `ChannelKind` enum, `ChannelProvider` trait (§8)
- [ ] Unit tests: `SettlementPolicy` combinator algebra (all, any,
      threshold, probabilistic), no chain access needed
- [ ] `docs/micropayment-spi.md` — this document

### Phase 2 — ThresholdBatching + server + client (EVM x402 backend)

Depends on: Phase 1, `x402-core` Phase 5 (`NonceStore`), `x402-server`
Phase 2, `wallet-spi` Phase 1.

- [ ] `micropayment-threshold` — `ThresholdBatchingProvider`
- [ ] `ReceiptStore` SPI (in-memory + Postgres) — see open question §12.1
- [ ] EIP-3009 cumulative receipt signing via `AccountStrategy.signTypedData`
- [ ] Receipt signature verification using `blockchain-spi.ChainAdapter.recoverAddress`
- [ ] `micropayment-server` — `withMicropayment` middleware
- [ ] `micropayment-client` — `MicropaymentHttpClient` (cross-compile)
- [ ] Tests: mock `Facilitator` + mock chain; full open → pay × N →
      threshold trigger → settle → close cycle
- [ ] Example: `examples/micropayment-threshold.ssc` — payment-gated
      REST endpoint with threshold batching

### Phase 3 — Probabilistic lottery (`micropayment-probabilistic`)

Depends on: Phase 1. No blockchain-spi dependency for the lottery
math itself; `ChainAdapter` only needed for batch redemption.

- [ ] `micropayment-probabilistic` — `ProbabilisticProvider` (cross-compile)
- [ ] `LotteryTicket`, `LotteryReveal`, win-condition math
- [ ] Test vectors: known `(preimage, salt)` pairs vs expected win/lose outcomes
- [ ] Batch redemption via `TxIntent.TokenTransfer` on EVM
- [ ] Example: `examples/micropayment-probabilistic.ssc`

### Phase 4 — EVM state channels (`micropayment-channel-evm`)

Depends on: Phase 1, `blockchain-spi` Phase 2 (`buildTransaction` +
`broadcast`), `blockchain-evm` Phase 2.

- [ ] `PaymentChannel` Solidity contract (compiled via smart-contracts
      toolchain; deploy via `TxIntent.Deploy`)
- [ ] `micropayment-channel-evm` — `StateChannelProvider`
- [ ] Cooperative close path + unilateral close path
- [ ] Dispute window enforcement (local timer + chain query)
- [ ] Tests: Anvil local node, full open → pay × N → close cycle,
      dispute scenario

### Phase 5 — Cardano Hydra heads (`micropayment-hydra`)

Depends on: Phase 1, `blockchain-spi` Phase 6 (`blockchain-cardano`),
Hydra node WebSocket API (see open question §12.3).

- [ ] `micropayment-hydra` — `HydraHeadProvider`
- [ ] Hydra node WebSocket client (Scala JVM, `java.net.http.WebSocket`)
- [ ] Head commit / open / tx-submission / close / contest lifecycle
- [ ] Tests: Hydra devnet (Docker-based)

### Phase 6 — Multi-chain ThresholdBatching via `blockchain-spi` ChainAdapter

Depends on: Phase 2, `blockchain-spi` Phase 3+ (Solana, Cardano).
This phase removes the direct x402 `Facilitator` dependency from
`ThresholdBatching` — x402 will have been refactored onto `blockchain-spi`
by this point.

- [ ] `ThresholdBatchingProvider` refactored to use `ChainAdapter` directly
      instead of `Facilitator` for settlement
- [ ] Multi-chain support: Solana SPL token authorizations, Cardano CIP-8
- [ ] Removes `x402-core` import from `micropayment-threshold`
- [ ] All Phase 2 tests continue to pass (no API change)

## 14. Open questions

1. **Receipt persistence (`ReceiptStore`).** Where does the server store the
   latest valid `PaymentReceipt` between requests? Options: in-memory (tests
   only), Redis, Postgres. Should reuse the `NonceStore` pattern from
   `x402-core` (same `setNx`-style API) or add a distinct `ReceiptStore`
   SPI with update semantics (`upsert` on sequence, not `setNx`)? Resolve
   before Phase 2.

2. **Channel ID generation.** UUID v4, or deterministic hash of `(payer,
   payee, chain, openTimestamp)`? Must be globally unique across providers
   and survive server restarts. If deterministic, what is the exact input
   encoding? Resolve before Phase 2.

3. **Hydra node operator.** In the `HydraHead` variant, who runs the Hydra
   node — the server operator (self-hosted), or a shared/hosted Hydra
   infrastructure provider? This determines whether `micropayment-hydra`
   wraps a local node client or a hosted API client. Resolve before Phase 5.

4. **Probabilistic batch redemption economics.** The server accumulates
   winning lottery tickets off-chain. What is the minimum batch value or
   time window that makes redemption on-chain economical? The answer depends
   on the target chain's fee level. Should `ProbabilisticProvider` expose
   configurable `redeemThreshold: BigInt` and `redeemInterval: Duration`,
   or should the policy be fixed? Resolve before Phase 3.

5. **Cross-chain channels.** Is a single `MicropaymentChannel` ever
   cross-chain (payer on EVM, payee on Cardano)? Not in scope for Phases
   1–5. Deferred to a future bridge layer. If this becomes a requirement,
   `ChannelConfig` will gain an optional `payeeChain: ChainId` field.

## 15. References

- [`blockchain-spi.md`](blockchain-spi.md) — `ChainAdapter`, `ChainId`,
  `Asset`, `TypedData`, `TxIntent.TokenTransferAuthorized`
- [`wallet-spi.md`](wallet-spi.md) — `RawSigner`, `Vault`,
  `AccountStrategy.signTypedData`
- [`docs/x402.md`](x402.md) — x402 protocol; `Facilitator`,
  `NonceStore`, `PaymentScheme.Stream`
- EIP-3009 — `transferWithAuthorization` (EVM token authorization)
- EIP-712 — typed structured data hashing (receipt signing on EVM)
- Cardano CIP-8 — off-chain message signing (Hydra + ThresholdBatching
  on Cardano after Phase 6)
- Hydra Head protocol spec (`hydra.family/docs/protocol`)
- Probabilistic micropayments: Chiesa et al. "Probabilistic Lightning"
  (2018); Rivest "Electronic Lottery Tickets as Micropayments" (1997)
