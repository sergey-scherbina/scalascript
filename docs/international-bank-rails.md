# v1.55 — International & Domestic-Instant Bank Rails

> **Status:** spec landed 2026-05-27. Go/no-go: **go**.
> Implementation phases: [v1.55.1 SWIFT](#v1551--swift-mt103--iso-20022-pacs008) →
> [v1.55.2 SEPA Instant](#v1552--sepa-instant-sct-inst) →
> [v1.55.3 UK FPS](#v1553--uk-faster-payments-fps) →
> [v1.55.4 UK BACS](#v1554--uk-bacs-direct-debit) →
> [v1.55.5 UK CHAPS](#v1555--uk-chaps) →
> [v1.55.6 India UPI](#v1556--india-upi) →
> [v1.55.7 Japan Zengin](#v1557--japan-zengin) →
> [v1.55.8 Singapore PayNow](#v1558--singapore-paynow)

---

## 1. Goals

- Close the explicit non-goals listed in `docs/bank-rails.md §2`: add **SWIFT**,
  **SEPA Instant (SCT Inst)**, **UK FPS / BACS / CHAPS**, **India UPI**,
  **Japan Zengin**, and **Singapore PayNow** as first-class `BankRailsProvider` adapters.
- Group the 8 new rails into three families that share implementation patterns:
  - **Cross-border wire** — SWIFT MT103 + SWIFT ISO 20022 pacs.008 (CBPR+)
  - **EU-instant** — SEPA Instant CT (SCT Inst) extending the existing SEPA adapter
  - **Domestic-instant by region** — UK (FPS / BACS / CHAPS), India (UPI),
    Japan (Zengin), Singapore (PayNow)
- Reuse the `BankRailsProvider` SPI, `BankTransfer`, `DirectDebitMandate`, `Money`,
  `IdempotencyKey`, and `WebhookReceiver` primitives from v1.53/v1.54 **unchanged**.
  All type changes are **additive** (new `Option` fields, new enum cases).
- Provide a typed model for **correspondent-banking chain visibility** (SWIFT GPI hops).
- Model the **ISO 20022 MX migration** timeline for SWIFT (legacy MT phase-out deadline:
  November 2025 under CBPR+; all new integrations should prefer pacs.008).
- Each rail yields a standalone adapter subproject that compiles and ships independently.

---

## 2. Non-goals

- **Direct central-bank / scheme connectivity** — Fedwire, TARGET2, CHAPS direct via BoE,
  Zengin direct via JBA — all require extensive on-boarding. Adapters communicate with a
  **sponsor/aggregator API** in every case.
- **AML / KYC / sanctions screening** — rails in this spec carry jurisdiction-specific
  compliance obligations (OFAC, EU AML Directive, RBI KYC, MAS CDD). A future
  `ComplianceProvider` SPI is deferred. Per-rail sections document the obligation without
  implementing it.
- **FX / multi-currency conversion** — `FxProvider` deferred (same as v1.53/v1.54).
- **RTGS direct-bank sims / test-net** — no Fedwire or TARGET2 sandbox is modelled here.
- **Correspondent-bank simulator** — SWIFT multi-hop routing is visible via GPI but not
  simulated in unit tests (tests use a mocked aggregator REST endpoint).
- **UPI merchant-QR dynamic codes** — UPI QR generation for point-of-sale deferred;
  this spec covers push/collect API flows only.
- **BACS Credit** — only BACS Direct Debit is in scope. BACS Credit uses a separate
  submission window and is less commonly needed by SaaS platforms.

---

## 3. Why these rails are a separate spec from v1.54

### 3.1 Correspondent-banking topology

All four v1.54 rails (SEPA, ACH, Pix, FedNow) are single-hop: one sending bank, one
clearing system, one receiving bank. SWIFT cross-border transfers may route through 2–5
**correspondent banks** holding nostro/vostro accounts in intermediate currencies. The
transfer is not guaranteed to arrive until every hop settles.

SWIFT GPI (Global Payments Innovation, 2017) introduced the **UETR** (Unique End-to-end
Transaction Reference) — a UUID v4 assigned at initiation — and the GPI tracker, which
surfaces hop-by-hop status via `pacs.002` responses. The `BankTransfer` type gains a
`gpiTrail: List[GpiHop]` field (additive, defaults to `Nil`) to surface this.

### 3.2 ISO 20022 MX migration deadline

SWIFT is retiring the legacy MT message standard. The CBPR+ (Cross-Border Payments and
Reporting Plus) programme set November 2025 as the deadline for financial institutions to
accept ISO 20022 pacs.008 MX messages on cross-border corridors. New integrations must
default to pacs.008; MT103 support is kept as a `RailKind.SWIFT_MT103` case for legacy
aggregators that haven't fully migrated, but should be treated as deprecated.

### 3.3 BIC/SWIFT identifier model

SEPA uses IBANs. ACH uses routing numbers. SWIFT identifies institutions by **BIC** (Bank
Identifier Code, ISO 9362) — 8 or 11 character codes (`DEUTDEDB` or `DEUTDEDBFRA`). The
existing `BankAccount` has no `bic` field; v1.55.1 adds `bic: Option[String]` as an additive
field with a `None` default, preserving all existing call sites.

### 3.4 Charge-bearer semantics

SWIFT defines a charge model that is absent from domestic rails:

| Code | Who pays charges |
|------|-----------------|
| `OUR` | Sender bears all intermediary and beneficiary bank charges |
| `SHA` | Sender pays sending-bank charges; receiver pays receiving/intermediary charges |
| `BEN` | Receiver bears all charges; amount received may be less than amount sent |

SHA is the most common default. The `ChargeBearer` enum is new in v1.55.

### 3.5 Regional KYC/sanctions overlay touchpoints

SWIFT payments cleared through a US correspondent bank require OFAC screening by every
intermediary. SCT Inst requires EBA sanctions screening within the 10-second window.
UPI requires the payer to authenticate with a UPI PIN on their registered device (two-factor
on the payer side, transparent to the payee API). PayNow requires MAS Customer Due Diligence.
None of this is implemented by the adapters (deferred to `ComplianceProvider`), but each
per-rail section documents the obligation to avoid surprises in production.

---

## 4. Type-level surface (additive)

All changes extend existing types with new `Option` fields or add new enum cases.
**No existing call sites break.**

### 4.1 RailKind additions

```scala
// Additions to payments/bank-rails/src/main/scala/scalascript/payments/bankrails/RailKind.scala
enum RailKind:
  // existing v1.54 cases
  case SEPA_CT, SEPA_DD, ACH_CREDIT, ACH_DEBIT, PIX, FEDNOW
  // v1.55 additions
  case SWIFT_MT103    // legacy ISO 15022 wire (deprecated path, keep for aggregator compat)
  case SWIFT_PACS008  // ISO 20022 CBPR+ pacs.008.001.10 (preferred for new integrations)
  case SCT_INST       // SEPA Instant Credit Transfer (TIPS / RT1 backbones)
  case UK_FPS         // UK Faster Payments Service
  case UK_BACS_DD     // UK BACS Direct Debit (3-day cycle)
  case UK_CHAPS       // UK CHAPS same-day high-value RTGS (via aggregator)
  case IN_UPI         // India Unified Payments Interface (push + collect)
  case JP_ZENGIN      // Japan Zengin Data Telecommunication System
  case SG_PAYNOW      // Singapore PayNow (FAST + proxy resolution)
```

### 4.2 BankAccount field additions

New fields on `payments/bank-rails/.../BankTransfer.scala`, class `BankAccount`:

```scala
case class BankAccount(
  // existing fields (unchanged)
  iban:          Option[String] = None,
  accountNumber: Option[String] = None,
  routingNumber: Option[String] = None,
  bankCode:      Option[String] = None,
  pixKey:        Option[String] = None,
  holderName:    String,
  countryCode:   String,
  // v1.55 additions
  bic:              Option[String] = None,  // SWIFT/SCT Inst: BIC8 or BIC11
  sortCode:         Option[String] = None,  // UK FPS / BACS: 6-digit sort code
  upiVpa:           Option[String] = None,  // India UPI: Virtual Payment Address (name@bank)
  zenginBankCode:   Option[String] = None,  // Japan Zengin: 4-digit bank code
  zenginBranchCode: Option[String] = None,  // Japan Zengin: 3-digit branch code
  paynowProxy:      Option[String] = None,  // SG PayNow: mobile / NRIC / UEN proxy
)
```

### 4.3 New SWIFT-specific types

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/Swift.scala (new file v1.55.1)

/** UUID v4 assigned at SWIFT initiation; unique across the entire GPI network. */
opaque type Uetr = String
object Uetr:
  def generate(): Uetr    = java.util.UUID.randomUUID().toString
  def apply(s: String): Uetr = s
  extension (u: Uetr) def value: String = u

/** SWIFT charge-bearer instruction. SHA is the most common default. */
enum ChargeBearer:
  case OUR  // sender pays all intermediary + receiving-bank charges
  case SHA  // sender pays sending bank; receiver pays rest  (default)
  case BEN  // receiver bears all charges; received amount may differ

/** One hop in a SWIFT GPI tracker chain. */
case class GpiHop(
  bic:       String,                   // BIC of the institution at this hop
  status:    String,                   // ACSP / ACCC / RJCT (pacs.002 status codes)
  timestamp: java.time.Instant,
  charges:   Option[String] = None,    // intermediary deducted amount (free-text)
)
```

`BankTransfer` gains one additive field (defaults to `Nil`):

```scala
case class BankTransfer(
  // ... existing fields unchanged ...
  gpiTrail: List[GpiHop] = Nil,   // populated by SwiftProvider; empty for non-SWIFT rails
)
```

`InitiateTransferRequest` gains two additive fields for SWIFT:

```scala
case class InitiateTransferRequest(
  // ... existing fields unchanged ...
  chargeBearer: ChargeBearer = ChargeBearer.SHA,  // SWIFT only; ignored for other rails
  uetr:         Option[Uetr] = None,              // None = adapter generates a new UUID v4
)
```

### 4.4 BankRailsProvider (unchanged)

The 6-method trait is not modified. Adapters declare `supportedRails` to advertise which
`RailKind` cases they handle:

```scala
// no change to BankRailsProvider.scala
trait BankRailsProvider:
  def id: String
  def displayName: String
  def spiVersion: String
  def supportedRails: Set[RailKind]
  def initiateTransfer(req: InitiateTransferRequest):       BankTransfer
  def getTransfer(id: TransferId):                          BankTransfer
  def cancelTransfer(id: TransferId):                       Unit
  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer
  def getDirectDebit(id: TransferId):                       BankTransfer
  def webhookReceiver: WebhookReceiver[BankRailsEvent]
```

### 4.5 BankRailsEvent additions

New cases added to `payments/bank-rails/.../BankRailsEvent.scala`:

```scala
enum BankRailsEvent:
  // existing v1.54 cases (SEPA × 7, ACH × 3, Pix × 3, FedNow × 2)

  // SWIFT GPI (v1.55.1)
  case SwiftMt103Booked(uetr: String, amount: String, currency: String)
  case SwiftPacs008Settled(uetr: String, amount: String, currency: String)
  case SwiftGpiAdvanced(uetr: String, hop: GpiHop)          // intermediate hop update
  case SwiftRejected(uetr: String, statusCode: String, reason: String)

  // SEPA Instant (v1.55.2)
  case SctInstSettled(endToEndId: String, amount: String, currency: String)
  case SctInstRejected(endToEndId: String, reason: String)

  // UK FPS (v1.55.3)
  case UkFpsAccepted(txId: String, amount: String)
  case UkFpsRejected(txId: String, reason: String)
  case UkFpsReturned(txId: String, code: String, description: String)

  // UK BACS DD (v1.55.4)
  case BacsDdSubmitted(ref: String, settlementDate: String)
  case BacsDdPaid(ref: String, amount: String)
  case BacsAuddisAccepted(mandateRef: String)               // mandate set up OK
  case BacsAruddReturned(ref: String, code: String, description: String)  // unpaid return

  // UK CHAPS (v1.55.5)
  case ChapsSettled(endToEndId: String, amount: String, currency: String)
  case ChapsRejected(endToEndId: String, reason: String)

  // India UPI (v1.55.6)
  case UpiCollectInitiated(txnId: String, vpa: String, amount: String)
  case UpiApproved(txnId: String, utrNumber: String)
  case UpiDeclined(txnId: String, reason: String)

  // Japan Zengin (v1.55.7)
  case ZenginSettled(transferId: String, amount: String)
  case ZenginRejected(transferId: String, reason: String)

  // Singapore PayNow (v1.55.8)
  case PayNowSettled(txnRef: String, proxy: String, amount: String, currency: String)
  case PayNowFailed(txnRef: String, reason: String)
```

---

## 5. Settlement timing

Extends the table from `docs/bank-rails.md §3.1`:

| Rail | Typical settlement | Window | Max amount |
|------|--------------------|--------|-----------|
| SEPA CT | T+1 business day | 08:00–16:00 CET on business days | €999,999,999 |
| SEPA DD | T+2 (D-2 pre-notification) | Business days | No limit |
| ACH Credit | T+1 same-day / T+2 standard | Same-day window: 14:45 ET | $1,000,000 (same-day) |
| ACH Debit | T+1 same-day / T+2 standard | Multiple windows | Varies |
| Pix | T+0 < 10 seconds | 24 × 7 × 365 | R$999,999,999 |
| FedNow | T+0 < 10 seconds | 24 × 7 × 365 | $500,000 |
| **SWIFT MT103** | T+1 … T+5 (corridor-dependent) | Business days; cut-off varies | No limit |
| **SWIFT pacs.008** | T+1 … T+3 (MX corridors faster) | Business days | No limit |
| **SCT Inst** | T+0 < 10 seconds | 24 × 7 × 365 | €100,000 |
| **UK FPS** | T+0 < 2 seconds | 24 × 7 × 365 | £1,000,000 |
| **UK BACS DD** | T+3 (3-day cycle) | Business days | No limit |
| **UK CHAPS** | T+0 same-day (via aggregator) | 08:00–17:00 UK business days | No limit |
| **India UPI** | T+0 instant | 24 × 7 × 365 | ₹2,00,000 (default per-tx) |
| **Japan Zengin** | T+0 same-day | Settlement: 08:30–15:30 JST | ¥ no limit (bank may cap) |
| **Singapore PayNow** | T+0 < 30 seconds | 24 × 7 × 365 | S$200,000 (MAS default) |

Settlement-timing implications for `initiateTransfer` return value:

- SWIFT, SEPA CT, BACS: returns `BankTransferStatus.Pending` — terminal state arrives via webhook.
- SCT Inst, FPS, CHAPS: returns `Pending`; aggregator webhooks typically fire within seconds.
- UPI, Zengin, PayNow: may return `Settled` immediately for synchronous aggregators, but
  webhook confirmation should still be awaited for reconciliation.

---

## 6. Idempotency

Reuses `InitiateTransferRequest.idempotencyKey: String` from v1.54. Per-rail mapping:

| Rail | SPI field | Wire field |
|------|-----------|-----------|
| SWIFT MT103 | `idempotencyKey` | Field 20 (Transaction Reference Number) |
| SWIFT pacs.008 | `idempotencyKey` + `uetr` | `EndToEndId` / `UETR` in `GrpHdr` |
| SCT Inst | `idempotencyKey` | `EndToEndId` in pain.001/pacs.008 |
| UK FPS | `idempotencyKey` | `EndToEndIdentification` (max 35 chars) |
| UK BACS | `idempotencyKey` | `TransactionRef` in BACS submission file |
| UK CHAPS | `idempotencyKey` | `EndToEndId` in pacs.008 |
| India UPI | `idempotencyKey` | `txnId` in NPCI API request |
| Japan Zengin | `idempotencyKey` | `customer_transfer_id` in JBA REST |
| SG PayNow | `idempotencyKey` | `txnRef` in MAS PayNow API |

Adapters must return `DuplicateTransferRequest` from `BankRailsError` if a second call with
the same `idempotencyKey` arrives after submission, rather than double-submitting.

---

## 7. Webhook event taxonomy

Each adapter's `WebhookReceiver[BankRailsEvent]` maps incoming payloads to the cases in §4.5.

### SWIFT (v1.55.1)

| Event case | Trigger |
|------------|---------|
| `SwiftMt103Booked` | MT910/MT940 confirmation from correspondent bank |
| `SwiftPacs008Settled` | pacs.002 `ACCC` status from GPI tracker webhook |
| `SwiftGpiAdvanced` | pacs.002 `ACSP` (in-progress) hop update |
| `SwiftRejected` | pacs.002 `RJCT` or MT900 reject |

Webhook auth: `X-SWIFT-Signature` HMAC-SHA256 over raw body using shared secret agreed at aggregator on-boarding.

### SEPA Instant (v1.55.2)

| Event case | Trigger |
|------------|---------|
| `SctInstSettled` | pacs.002 `ACCC` from TIPS/RT1 acknowledgment via aggregator |
| `SctInstRejected` | pacs.002 `RJCT` within 10-second window |

Webhook auth: same `X-SEPA-Signature: sha256=<hex>` scheme as SEPA CT (reuses `SepaWebhookReceiver` logic).

### UK FPS (v1.55.3)

| Event case | Trigger |
|------------|---------|
| `UkFpsAccepted` | `Accepted` status notification from Pay.UK scheme via aggregator |
| `UkFpsRejected` | `Rejected` notification |
| `UkFpsReturned` | Return notification (up to 2 hours post-send) |

Webhook auth: `X-FPS-Signature: sha256=<hex>` HMAC-SHA256.

### UK BACS DD (v1.55.4)

| Event case | Trigger |
|------------|---------|
| `BacsDdSubmitted` | Aggregator confirms file accepted into BACS cycle |
| `BacsDdPaid` | Day 3 settlement confirmation |
| `BacsAuddisAccepted` | New mandate accepted by payer bank via AUDDIS |
| `BacsAruddReturned` | Unpaid direct debit returned (ARUDD) — see §10 for codes |

Webhook auth: `X-BACS-Signature` HMAC-SHA256.

### UK CHAPS (v1.55.5)

| Event case | Trigger |
|------------|---------|
| `ChapsSettled` | pacs.002 `ACCC` from BoE / aggregator |
| `ChapsRejected` | pacs.002 `RJCT` |

Webhook auth: `X-CHAPS-Signature: sha256=<hex>`.

### India UPI (v1.55.6)

| Event case | Trigger |
|------------|---------|
| `UpiCollectInitiated` | UPI Collect request sent; payer has not yet approved |
| `UpiApproved` | Payer approves; aggregator delivers final credit notification with UTR |
| `UpiDeclined` | Payer declines or times out |

Webhook auth: RSA-SHA256 signature over raw body using NPCI / aggregator public key.

### Japan Zengin (v1.55.7)

| Event case | Trigger |
|------------|---------|
| `ZenginSettled` | Transfer confirmed settled by Zengin system (via aggregator callback) |
| `ZenginRejected` | Transfer rejected (kana mismatch, invalid branch) |

Webhook auth: HMAC-SHA256 `X-Zengin-Signature`.

### Singapore PayNow (v1.55.8)

| Event case | Trigger |
|------------|---------|
| `PayNowSettled` | Settlement confirmation from FAST/PayNow network via aggregator |
| `PayNowFailed` | Proxy not found or transaction rejected |

Webhook auth: HMAC-SHA256 `X-PayNow-Signature`.

---

## 8. Per-rail sections

### §v1.55.1 — SWIFT MT103 + ISO 20022 pacs.008

**Protocol:** Adapts to an aggregator REST API (Wise Business API / Currencycloud / Airwallex
Business API / TransferMate) rather than direct SWIFT connectivity. The adapter
encapsulates whether the aggregator uses MT103 legacy or pacs.008 MX internally.

**Auth:** Bearer token (OAuth2 client credentials). Aggregator credentials supplied via config.

**Wire format:**
- `SWIFT_MT103`: aggregator receives JSON instruction, internally converts to ISO 15022 MT103.
  Fields: Field 20 (TRN = `idempotencyKey`), Field 32A (value date, currency, amount),
  Field 50K/59 (ordering/beneficiary), Field 71A (charge bearer `OUR/SHA/BEN`).
- `SWIFT_PACS008`: aggregator receives JSON instruction, internally converts to ISO 20022
  `pacs.008.001.10` (CBPR+ mandatory fields: `GrpHdr.MsgId`, `EndToEndId`, `UETR`,
  `IntrBkSttlmAmt`, `ChrgBr`, `Dbtr`, `Cdtr`, `DbtrAgt.FinInstnId.BICFI`,
  `CdtrAgt.FinInstnId.BICFI`).

**UETR:** If `req.uetr` is `None`, `SwiftProvider` calls `Uetr.generate()` (UUID v4) and
stores it on the returned `BankTransfer`. All GPI tracker updates reference this UETR.

**Webhook verify:** `X-SWIFT-Signature: sha256=<hex>`, HMAC-SHA256 over raw HTTP body,
shared secret configured at aggregator on-boarding.

**Adapter:** `runtime/std/payments-swift/`
```
payments-swift/
  src/main/scala/scalascript/payments/swift/
    SwiftPlugin.scala           — Backend; Feature.BankRails; ServiceLoader entry
    SwiftProvider.scala         — BankRailsProvider; supports SWIFT_MT103 + SWIFT_PACS008
    SwiftWebhookReceiver.scala  — WebhookReceiver[BankRailsEvent]
    SwiftMt103Builder.scala     — JSON instruction builder for MT103 aggregator format
    SwiftPacs008Builder.scala   — JSON instruction builder for pacs.008 aggregator format
    GpiTracker.scala            — parses pacs.002 GPI hop notifications → List[GpiHop]
```

**Deliverables (v1.55.1):**
- New `RailKind.SWIFT_MT103` + `SWIFT_PACS008` cases
- New `Uetr`, `ChargeBearer`, `GpiHop` types (new file `Swift.scala` in `payments/bank-rails/`)
- Additive fields on `BankAccount` (`bic`), `InitiateTransferRequest` (`chargeBearer`, `uetr`),
  `BankTransfer` (`gpiTrail`)
- New `SwiftGpiAdvanced/SwiftMt103Booked/SwiftPacs008Settled/SwiftRejected` event cases
- `runtime/std/payments-swift/` subproject (5 source files + 1 META-INF/services + 1 build entry)
- 35+ tests: MT103 instruction build, pacs.008 instruction build, UETR generation, HMAC
  webhook verify, GPI hop parsing, charge bearer field mapping
- `examples/international-bank-rails.ssc` stub updated with a SWIFT send example

**AML/sanctions note:** USD-denominated payments cleared through a US correspondent bank
require OFAC screening by every intermediary. The adapter is agnostic; production deployments
must wire in a `ComplianceProvider` before calling `initiateTransfer`.

**Out of scope:** Direct SWIFT Alliance connectivity; multi-leg FX conversion;
SWIFT gpi for Corporates (g4C) API; Bilateral Key Exchange (BKE).

---

### §v1.55.2 — SEPA Instant (SCT Inst)

**Protocol:** Extends `runtime/std/payments-sepa/` — same aggregator pattern (EBICS / REST),
same `SepaConfig`, same PAIN.001 / pacs.008 schema. Difference: `localInstrument = "INST"` +
`serviceLevel = "SEPA"` in the XML header, routing to TIPS (ECB) or RT1 (EBA CLEARING).

**Auth:** Same as SEPA CT — Bearer token or HTTP Basic over TLS.

**Wire format:** ISO 20022 `pacs.008.001.08` with `SvcLvl/Cd = SEPA` and
`LclInstrm/Cd = INST`. Maximum transmission time from payer's PSP to payee's PSP: 10 seconds.
Timeout is a `SctInstTimeout` error (§10).

**Webhook verify:** `X-SEPA-Signature: sha256=<hex>` — same logic as CT, reuses
`SepaWebhookReceiver.verify()`.

**Adapter:** Extends `runtime/std/payments-sepa/` rather than a new subproject:
- `SepaProvider.supportedRails` adds `RailKind.SCT_INST`
- `SepaPainXml` gains a `buildSctInstPacs008(req)` method that sets the `INST` instrument
- `SepaWebhookReceiver` adds cases for `SctInstSettled` / `SctInstRejected`

**Deliverables (v1.55.2):**
- `RailKind.SCT_INST` case
- `SepaPainXml.buildSctInstPacs008` — new method, ~30 lines
- `SepaProvider.supportedRails` update (one-liner)
- `SctInstSettled` / `SctInstRejected` event cases
- 12+ new tests in `payments-sepa/src/test/` (SCT Inst XML build, webhook decode,
  10s timeout error)

**Note:** Since March 2024, EU Regulation 2024/886 requires all eurozone PSPs that offer
SEPA CT to also offer SCT Inst at no extra charge. This makes `SCT_INST` a mandatory capability
for any `SepaProvider` that serves EU markets.

**Out of scope:** TIPS direct participation API; RT1 direct API; maximum amount increase
(currently €100,000 per transaction, may be raised by EBA).

---

### §v1.55.3 — UK Faster Payments (FPS)

**Protocol:** Pay.UK operated scheme; adapter communicates with a **sponsored aggregator**
(TrueLayer, ClearBank, OpenPayd, Modulr). REST JSON over HTTPS.

**Auth:** OAuth2 client credentials + mTLS client certificate (aggregator requirement).

**Wire format:** JSON payment request. Mandatory fields:
`sortCode` (6 digits, from `BankAccount.sortCode`), `accountNumber` (8 digits,
from `BankAccount.accountNumber`), `amount` (pence, integer), `currency` ("GBP"),
`reference` (max 18 chars — Faster Payments reference field), `endToEndId` (max 35 chars,
from `idempotencyKey`).

**Confirmation of Payee (CoP):** Pay.UK mandates a name-check before sending. The
`UkFpsProvider` exposes a helper `checkPayee(sortCode, accountNumber, name): CopResult`
with outcomes `{ Matched, CloseMatch(suggestedName), NoMatch, AccountSwitched, Unavailable }`.
`initiateTransfer` calls CoP internally and surfaces `UkCopNameMismatch` error when the
result is `NoMatch`.

**Webhook verify:** `X-FPS-Signature: sha256=<hex>`, HMAC-SHA256 over raw body.

**Adapter:** `runtime/std/payments-uk-fps/`
```
payments-uk-fps/
  src/main/scala/scalascript/payments/ukfps/
    UkFpsPlugin.scala
    UkFpsProvider.scala           — BankRailsProvider; supports UK_FPS
    UkFpsWebhookReceiver.scala    — WebhookReceiver[BankRailsEvent]
    ConfirmationOfPayee.scala     — CoP name-check client; CopResult enum
```

**Deliverables (v1.55.3):**
- `RailKind.UK_FPS`, `BankAccount.sortCode` addition
- `UkFpsAccepted/Rejected/Returned` event cases
- `UkCopNameMismatch` error case (§10), `CopResult` enum
- `runtime/std/payments-uk-fps/` subproject (4 source files)
- 30+ tests: JSON request build, CoP name-match cases, HMAC verify, Accepted/Rejected/Returned
  webhook decode

**Out of scope:** CHAPS/BACS routing (separate rails); Request to Pay (RtP, Pay.UK's pull
variant); Open Banking PIS (handled by `PaymentProvider` adapters in v1.53).

---

### §v1.55.4 — UK BACS Direct Debit

**Protocol:** BACS 3-day cycle. File-based submission via aggregator SFTP relay (BACSTEL-IP
gateway, modern aggregators provide an HTTP wrapper). Cycle: Day 1 submit → Day 2 process
→ Day 3 settle.

**Auth:** API key + IP allowlist at aggregator; legacy BACSTEL-IP uses Service User Number (SUN)
and password.

**Wire format:** BACS Standard-18 fixed-format file via aggregator upload endpoint.
Mandatory fields: `ServiceUserNumber` (6 chars), `TransactionCode` (01=DD, 17=SUN-managed),
`AccountName` (max 18 chars), `SortCode`, `AccountNumber`, `Amount` (pence), `TransRef`.
Mandates established via **AUDDIS** (Automated Direct Debit Instruction Service) — a separate
file submission to notify the payer's bank.

**Webhook verify:** `X-BACS-Signature` HMAC-SHA256.

**Adapter:** `runtime/std/payments-uk-bacs/`
```
payments-uk-bacs/
  src/main/scala/scalascript/payments/ukbacs/
    UkBacsPlugin.scala
    UkBacsProvider.scala          — BankRailsProvider; supports UK_BACS_DD
    UkBacsWebhookReceiver.scala
    BacsFile.scala                — Standard-18 fixed-format file builder
    AuddisFile.scala              — AUDDIS instruction file builder
```

**Mandate integration:** `initiateDirectDebit` uses the `DirectDebitMandate` from v1.54
(field `MandateSequenceType` maps: `First → Initial`, `Recurring → Recurring`,
`Final → Final`, `OneOff → OneOff`). Mandate setup fires `BacsAuddisAccepted` event on
Day 3 of the AUDDIS cycle.

**ARUDD/ADDACS/AWACS return codes:**

| Code | Meaning |
|------|---------|
| `0` | Instruction cancelled — refer to payer |
| `1` | Instruction cancelled — new instruction due |
| `2` | Payer deceased |
| `3` | Account transferred to new bank / branch |
| `5` | No account, wrong account type |
| `6` | No instruction |
| `B` | Account closed |
| `C` | Funds insufficient |
| `F` | Invalid reference |
| `G` | Bank account closed on customer instructions |
| `H` | Institution refused to accept direct debit |

These map to `BacsAruddReturned.code` in the event model.

**Deliverables (v1.55.4):**
- `RailKind.UK_BACS_DD`
- `BacsDdSubmitted/Paid/AuddisAccepted/AruddReturned` event cases
- `BacsCycleMissed` error case
- `runtime/std/payments-uk-bacs/` subproject (5 source files)
- 30+ tests: Standard-18 file build, AUDDIS file build, mandate sequence mapping,
  ARUDD code parsing, HMAC webhook verify

**Out of scope:** BACS Credit; BACS Direct Credit (salary / pension); CREST.

---

### §v1.55.5 — UK CHAPS

**Protocol:** Real-time gross settlement, same-day, high value. Via aggregator
(ClearBank, Starling Payments, Lloyds TSB CHAPS gateway) — not direct BoE connectivity.
Aggregator surfaces a REST JSON API wrapping ISO 20022 `pacs.008`.

**Auth:** OAuth2 Bearer + mTLS client certificate.

**Wire format:** ISO 20022 `pacs.008.001.08`. Required fields: `IBAN` or `AccountNumber +
SortCode` for payee, `BIC` of payee bank where known, `EndToEndId` (idempotency key),
`IntrBkSttlmAmt`, `Dbtr`, `Cdtr`. Aggregator converts to BoE CHAPS IS20022 format internally.

**Webhook verify:** `X-CHAPS-Signature: sha256=<hex>`, HMAC-SHA256.

**Adapter:** `runtime/std/payments-uk-chaps/`
```
payments-uk-chaps/
  src/main/scala/scalascript/payments/ukchaps/
    UkChapsPlugin.scala
    UkChapsProvider.scala         — BankRailsProvider; supports UK_CHAPS
    UkChapsWebhookReceiver.scala
    ChapsPacs008Builder.scala     — reuses structure from FedNow Iso20022Xml
```

**Note:** CHAPS `cancelTransfer` is only possible before the payment enters the RTGS queue
(aggregator-dependent, typically a narrow window). After queue entry, cancellation requires
manual bank intervention. The adapter raises `BankRailsCancelError` if the aggregator rejects
the cancel.

**Deliverables (v1.55.5):**
- `RailKind.UK_CHAPS`
- `ChapsSettled/ChapsRejected` event cases
- `runtime/std/payments-uk-chaps/` subproject (4 source files)
- 25+ tests: pacs.008 build, HMAC verify, Settled/Rejected webhook decode, cancel-after-queue
  error

**Out of scope:** Direct BoE RTGS API; CREST (DVP securities settlement).

---

### §v1.55.6 — India UPI

**Protocol:** NPCI (National Payments Corporation of India) mandates that third-party
applications integrate via licensed aggregators: Razorpay, PayU, JusPay, Cashfree,
PhonePe Business API. The aggregator exposes a REST/JSON API; internally the aggregator
is a TPAP (Third-Party Application Provider) connected to NPCI's UPI switch.

**Auth:** API key (X-Api-Key header) + RSA-SHA256 request signing using the merchant's
private key (required by Razorpay / JusPay). Config carries the private key path.

**Wire format:** JSON request to aggregator endpoint. Key fields:
`txnId` (= `idempotencyKey`), `amount` (paise, integer), `payeeVpa`
(= `BankAccount.upiVpa`), `payerVpa` (optional, for Collect), `remarks` (max 50 chars),
`purpose` (`00` = merchant payment, `14` = subscription).

UPI defines two flows:
- **Push (UPI Pay):** Payer initiates; `initiateTransfer` maps to UPI Pay API call.
  Returns `Pending`; `UpiApproved` arrives via webhook with UTR (Unique Transaction Reference).
- **Pull (UPI Collect):** Payee initiates a collect request; `initiateDirectDebit` maps to
  UPI Collect API. Payer sees a notification on their UPI app; `UpiCollectInitiated` event
  fires immediately. `UpiApproved` or `UpiDeclined` arrives when payer responds.

**Two-factor on payer device:** The payer enters their UPI PIN on their registered mobile app —
the adapter and the merchant never see the PIN. This is transparent to the `BankRailsProvider`
API surface but must be documented for compliance.

**Webhook verify:** RSA-SHA256 signature over raw body using NPCI / aggregator public key
delivered at on-boarding.

**Adapter:** `runtime/std/payments-india-upi/`
```
payments-india-upi/
  src/main/scala/scalascript/payments/upi/
    UpiPlugin.scala
    UpiProvider.scala             — BankRailsProvider; supports IN_UPI
    UpiWebhookReceiver.scala
    UpiNpciApi.scala              — aggregator REST client; RSA-SHA256 signing
```

**Deliverables (v1.55.6):**
- `RailKind.IN_UPI`, `BankAccount.upiVpa` addition
- `UpiCollectInitiated/Approved/Declined` event cases
- `UpiTwoFactorTimeout` error case (§10)
- `runtime/std/payments-india-upi/` subproject (4 source files)
- 35+ tests: Push/Collect request build, VPA format validation, RSA webhook signature verify,
  Approved/Declined event decode

**AML/KYC note:** NPCI RBI regulations require full KYC for merchant on-boarding and
per-transaction limits (₹2,00,000 by default per transaction; ₹5,00,000 for certain
categories with enhanced KYC). Aggregators enforce these limits; the adapter will receive
an HTTP error if a limit is exceeded.

**Out of scope:** UPI 2.0 overdraft linked accounts; UPI AutoPay recurring; BBPS (Bharat
Bill Payment System); IMPS direct integration.

---

### §v1.55.7 — Japan Zengin

**Protocol:** Zengin Data Telecommunication System, operated by the Japanese Bankers
Association (JBA). Adapter communicates with an aggregator REST API (GMO Payment Gateway,
AnyPay, regional bank API wrappers). Settlement windows: weekday 08:30–15:30 JST (Zengin
24/7 extended service available at participating banks since 2018, but settlement of older
banks still follows the cut-off window).

**Auth:** API key + HMAC-SHA256 request signing.

**Wire format:** JSON to aggregator. Key fields:
`bankCode` (4 digits, from `BankAccount.zenginBankCode`),
`branchCode` (3 digits, from `BankAccount.zenginBranchCode`),
`accountNumber` (7 digits, from `BankAccount.accountNumber`),
`accountType` (`1` = ordinary, `2` = current, `4` = savings),
`accountName` (kana, max 30 chars — must match bank records; mismatch causes rejection),
`amount` (integer yen), `customerId` (= `idempotencyKey`).

**Kana name matching:** Japanese bank transfers require the account holder name in
katakana. The `accountName` in `BankAccount.holderName` must be stored in full-width katakana
for Zengin. The adapter does not transliterate; the caller is responsible for providing the
correct kana form.

**Webhook verify:** `X-Zengin-Signature` HMAC-SHA256 over raw body.

**Adapter:** `runtime/std/payments-japan-zengin/`
```
payments-japan-zengin/
  src/main/scala/scalascript/payments/zengin/
    ZenginPlugin.scala
    ZenginProvider.scala          — BankRailsProvider; supports JP_ZENGIN
    ZenginWebhookReceiver.scala
    ZenginApi.scala               — aggregator REST client
```

**Deliverables (v1.55.7):**
- `RailKind.JP_ZENGIN`, `BankAccount.zenginBankCode/zenginBranchCode` additions
- `ZenginSettled/ZenginRejected` event cases
- `ZenginOutsideWindow` error case (§10)
- `runtime/std/payments-japan-zengin/` subproject (4 source files)
- 30+ tests: JSON request build, kana field requirement check, bank code format validation,
  settlement window guard, HMAC webhook verify

**Out of scope:** Zengin-via-FinTech API (direct bank-API aggregator beyond JBA standard);
foreign-currency transfers (JPY only).

---

### §v1.55.8 — Singapore PayNow

**Protocol:** PayNow is built on top of Singapore's FAST (Fast And Secure Transfers)
infrastructure. It adds proxy resolution — payers can send to a mobile number, NRIC/FIN, or
UEN (Unique Entity Number for businesses) without knowing the recipient's bank account.
Adapter communicates with a MAS-licensed aggregator (NETS, DBS PayNow Business API,
OCBC Business Connect, or MatchMove).

**Auth:** OAuth2 client credentials + HMAC-SHA256 request signing.

**Wire format:** JSON to aggregator. Key fields:
`proxyType` (`MOBILE` / `NRIC` / `UEN`),
`proxyValue` (= `BankAccount.paynowProxy`),
`amount` (SGD cents, integer), `currency` (`"SGD"`),
`transactionRef` (= `idempotencyKey`, max 35 chars),
`payerInfo` (optional display name shown to recipient).

**Proxy resolution:** The aggregator calls the PayNow proxy registry before submitting the
payment to the FAST network. If the proxy is not found, `PayNowProxyNotFound` is raised (§10).

**SGQR:** Singapore QR code (EMVCo compliant). Dynamic SGQR generation for payee-presented
QR codes is deferred (out of scope for this phase). Static SGQR (for display in-store)
is documented but not implemented.

**Webhook verify:** `X-PayNow-Signature` HMAC-SHA256 over raw body.

**Adapter:** `runtime/std/payments-sg-paynow/`
```
payments-sg-paynow/
  src/main/scala/scalascript/payments/paynow/
    PayNowPlugin.scala
    PayNowProvider.scala          — BankRailsProvider; supports SG_PAYNOW
    PayNowWebhookReceiver.scala
    PayNowApi.scala               — aggregator REST client; proxy resolution
```

**Deliverables (v1.55.8):**
- `RailKind.SG_PAYNOW`, `BankAccount.paynowProxy` addition
- `PayNowSettled/PayNowFailed` event cases
- `PayNowProxyNotFound` error case (§10)
- `runtime/std/payments-sg-paynow/` subproject (4 source files)
- 30+ tests: proxy-type dispatch, HMAC webhook verify, proxy-not-found error, Settled/Failed
  decode

**AML/KYC note:** MAS requires Customer Due Diligence for transactions above S$5,000 from
individuals. Aggregators enforce this at on-boarding and per-transaction.

**Out of scope:** Dynamic SGQR generation; PayNow via bank SWIFT connectivity; PAYNOW
merchant integration via SGFinDex.

---

## 9. Module layout

```
payments/
  bank-rails/              — shared SPI (BankRailsProvider, RailKind, types)
                             extended in v1.55.1 with Swift.scala

runtime/std/
  payments-sepa/           — SEPA CT + DD + SCT_INST (v1.55.2 extends this)
  payments-ach/            — ACH credit + debit
  payments-pix/            — Pix
  payments-fednow/         — FedNow
  payments-swift/          — SWIFT MT103 + pacs.008  (new v1.55.1)
  payments-uk-fps/         — UK Faster Payments       (new v1.55.3)
  payments-uk-bacs/        — UK BACS Direct Debit     (new v1.55.4)
  payments-uk-chaps/       — UK CHAPS                 (new v1.55.5)
  payments-india-upi/      — India UPI                (new v1.55.6)
  payments-japan-zengin/   — Japan Zengin             (new v1.55.7)
  payments-sg-paynow/      — Singapore PayNow         (new v1.55.8)
```

Each new subproject follows the v1.54 four-file layout:
`<Rail>Plugin.scala` + `<Rail>Provider.scala` + `<Rail>WebhookReceiver.scala`
- one wire-format helper, plus `src/main/resources/META-INF/services/scalascript.backend.spi.Backend`.

SCT Inst is the exception — it extends `runtime/std/payments-sepa/` rather than requiring a
new subproject, because it uses the same PAIN.001/pacs.008 schema and aggregator endpoint.

**ISO 20022 XML reuse:** `FedNowIso20022Xml.scala` and `ChapsPacs008Builder.scala`
will share significant structure. A future refactor could extract a shared
`payments-iso20022/` module; for v1.55 the builders are self-contained per adapter.

---

## 10. Error model

Extends `payments/bank-rails/src/main/scala/scalascript/payments/bankrails/BankRailsError.scala`
with the following sealed subclasses:

```scala
// v1.54 existing
class UnsupportedRail(rail: RailKind)       extends BankRailsError(...)
class TransferNotFound(id: TransferId)      extends BankRailsError(...)
class MandateNotActive(id: MandateId)       extends BankRailsError(...)
class DuplicateTransferRequest(key: String) extends BankRailsError(...)
class BankRailsCancelError(reason: String)  extends BankRailsError(...)
class FedNowLimitExceeded(amount: Money)    extends BankRailsError(...)
class PixKeyNotFound(pixKey: String)        extends BankRailsError(...)
class NachaCutoffMissed(window: String)     extends BankRailsError(...)

// v1.55 additions
class SwiftSanctionsHit(uetr: String, sanctionsRef: String)  extends BankRailsError(...)
class SwiftUetrInvalid(uetr: String)                          extends BankRailsError(...)
class SctInstTimeout(endToEndId: String, elapsed: Long)       extends BankRailsError(...)
class UkCopNameMismatch(suggested: Option[String])            extends BankRailsError(...)
class BacsCycleMissed(nextWindow: java.time.LocalDate)        extends BankRailsError(...)
class UpiTwoFactorTimeout(txnId: String)                      extends BankRailsError(...)
class ZenginOutsideWindow(nextOpen: java.time.ZonedDateTime)  extends BankRailsError(...)
class PayNowProxyNotFound(proxyType: String, proxyValue: String) extends BankRailsError(...)
```

---

## 11. Diagnostics

Adapters should expose diagnostic checks callable from `ssc health` (deferred CLI integration):

| ID | Rail | Check |
|----|------|-------|
| D7 | SWIFT | BIC format: 8 or 11 chars; chars 5–6 are ISO 3166 country code |
| D8 | SWIFT pacs.008 | ISO 20022 schema validation (XSD) before submission |
| D9 | SWIFT GPI | UETR is valid UUID v4 |
| D10 | SCT Inst | Aggregator round-trip latency < 8 seconds (2s headroom before 10s SLA) |
| D11 | UK FPS | CoP service availability (aggregator `/cop/health` endpoint) |
| D12 | India UPI | VPA format: `localPart@bankHandle`; localPart ≤ 100 chars, bankHandle resolves |
| D13 | Japan Zengin | Settlement window check: current JST within 08:30–15:30 |
| D14 | SG PayNow | Proxy registry lookup smoke-test with aggregator test proxy |

---

## 12. Testing strategy

Mirrors `docs/bank-rails.md §12`. Each adapter has:

1. **Unit tests for wire-format builders** — no HTTP; test that the JSON/XML/flat-file
   instruction produced from an `InitiateTransferRequest` fixture matches snapshot.
2. **Unit tests for `WebhookReceiver`** — feed raw payloads; assert correct `BankRailsEvent`
   decoded; assert `WebhookError.InvalidSignature` on bad HMAC.
3. **Unit tests for error paths** — force each `BankRailsError` subclass.
4. **Integration smoke test** — spin up a mock HTTP server (using the test harness pattern
   from `runtime/std/payments-sepa/src/test/`); run a full `initiateTransfer` + webhook
   delivery cycle.

Test targets per phase:

| Phase | Target tests | Cumulative |
|-------|-------------|-----------|
| v1.55.1 SWIFT | 35+ | 35 |
| v1.55.2 SCT Inst | 12+ | 47 |
| v1.55.3 UK FPS | 30+ | 77 |
| v1.55.4 UK BACS | 30+ | 107 |
| v1.55.5 UK CHAPS | 25+ | 132 |
| v1.55.6 India UPI | 35+ | 167 |
| v1.55.7 Japan Zengin | 30+ | 197 |
| v1.55.8 SG PayNow | 30+ | 227+ |

---

## 13. Composition with v1.53 / v1.54

SWIFT and all other v1.55 rails are accessed via the same effect model established in v1.54:

```scalascript
import std.payments.swift.*

val result = transferMoney(SwiftConfig(
  aggregatorUrl  = "https://api.currencycloud.com",
  apiKey         = "${env:CURRENCYCLOUD_KEY}",
  defaultCharge  = ChargeBearer.SHA
))
```

The `BankTransfer.gpiTrail` field (new in v1.55.1) allows inspecting multi-hop routing:

```scalascript
val transfer = provider.getTransfer(txId)
transfer.gpiTrail.foreach { hop =>
  println(s"${hop.bic}: ${hop.status} at ${hop.timestamp}")
}
```

SWIFT adapters coexist with v1.54 adapters — a program may call both
`SepaProvider.initiateTransfer` and `SwiftProvider.initiateTransfer` from the same module.
`Feature.BankRails` covers all rails; no new `Feature` case is needed.

---

## 14. AML / KYC / sanctions obligations

Bank rail integrations introduce jurisdiction-specific compliance obligations. The adapters in
v1.55 document but do not implement these checks. A future `ComplianceProvider` SPI will
address them.

| Rail | Jurisdiction | Obligation |
|------|-------------|-----------|
| SWIFT (USD) | US | OFAC screening at every intermediary; mandatory for US-cleared payments |
| SWIFT (EUR) | EU | EU dual-use / sanctions regulations (Council Regulation 833/2014) |
| SCT Inst | EU | EBA sanctions screening must complete within the 10s window |
| UK FPS/BACS/CHAPS | UK | OFSI / HM Treasury sanctions; PSR regulation; FCA authorisation |
| India UPI | India | RBI KYC for merchants; transaction limits enforced by aggregator |
| Japan Zengin | Japan | AML Act (犯収法); FSA transaction monitoring requirements |
| SG PayNow | Singapore | MAS CDD (Customer Due Diligence); FATF compliance |

Production deployments must wire `ComplianceProvider` before routing to any adapter in this
family. This is an architectural constraint, not an optional enhancement.

---

## 15. Implementation phases

| Phase | Slug | Key deliverables | Spec ref |
|-------|------|-----------------|---------|
| v1.55.1 | `v1.55.1-international-swift` | `payments-swift/`; `Uetr`, `ChargeBearer`, `GpiHop` types; `BankTransfer.gpiTrail`; 35+ tests | §v1.55.1 |
| v1.55.2 | `v1.55.2-sepa-instant` | Extend `payments-sepa/` with `SCT_INST`; 12+ tests | §v1.55.2 |
| v1.55.3 | `v1.55.3-uk-faster-payments` | `payments-uk-fps/`; CoP; 30+ tests | §v1.55.3 |
| v1.55.4 | `v1.55.4-uk-bacs` | `payments-uk-bacs/`; AUDDIS/ARUDD file builders; 30+ tests | §v1.55.4 |
| v1.55.5 | `v1.55.5-uk-chaps` | `payments-uk-chaps/`; pacs.008 builder; 25+ tests | §v1.55.5 |
| v1.55.6 | `v1.55.6-india-upi` | `payments-india-upi/`; push + collect; RSA webhook; 35+ tests | §v1.55.6 |
| v1.55.7 | `v1.55.7-japan-zengin` | `payments-japan-zengin/`; kana constraint; 30+ tests | §v1.55.7 |
| v1.55.8 | `v1.55.8-singapore-paynow` | `payments-sg-paynow/`; proxy resolution; 30+ tests | §v1.55.8 |

Phases are independently shippable. v1.55.2 (SCT Inst) extends an existing subproject so it
can be claimed after v1.55.1 lands the shared type additions; all other phases are independent.

---

## 16. Open questions

**(a) UETR allocation — client-side vs aggregator-assigned.**
Recommendation: client generates `Uetr.generate()` (UUID v4) and submits it. Aggregator may
override or echo it back. Adapter uses aggregator response UETR as the authoritative value.

**(b) BACS mandate sequence-type mapping.**
`MandateSequenceType` from v1.54 (`OneOff / First / Recurring / Final`) maps cleanly to BACS.
No new type needed. Open: whether to expose BACS `TransactionCode` (01/17/18/19) as a
`metadata` field or as a typed field on `InitiateDirectDebitRequest`. Recommendation: typed
`bacsTransactionCode: Option[Int]` on a BACS-specific config object, not on the shared SPI.

**(c) UPI Collect callback URL.**
Aggregators require a `callbackUrl` to post approval/decline events. Recommendation: include
as `callbackUrl: Option[String]` in `UpiConfig` (adapter-specific config, not in the shared
SPI). Each merchant registers one. Long-polling as a fallback for non-public deployments is
documented in the adapter.

**(d) SWIFT MT103 vs pacs.008 — one `RailKind.SWIFT` or two cases.**
Recommendation: **two cases** (`SWIFT_MT103` / `SWIFT_PACS008`). The wire formats differ
substantially; a single case would require a runtime sub-format discriminator that is
harder to track in `supportedRails` declarations. Parallels the `SEPA_CT / SEPA_DD` split.

**(e) ISO 20022 XML sharing between FedNow, CHAPS, SCT Inst, SWIFT pacs.008.**
All four use ISO 20022 pacs.008. For v1.55 each adapter carries its own builder (no external
dependency). If/when all are in production, extracting `payments-iso20022-core/` is the right
refactor — but deferred to keep phase scope tight.

---

## 17. Go / no-go

**Go.** Rationale:

- The `BankRailsProvider` SPI needs no breaking changes — only additive fields and new enum
  cases. All existing v1.54 adapters compile without modification.
- Every rail in scope has a well-established aggregator ecosystem, so we avoid direct
  central-bank connectivity in this phase.
- The v1.54 implementation pattern (four-file subproject + ServiceLoader + Feature.BankRails)
  is proven across four adapters and can be applied to the eight new rails with low risk.
- ISO 20022 pacs.008 is already implemented for FedNow — SWIFT and CHAPS reuse the same
  schema family.
- AML/sanctions obligations are documented but explicitly deferred; this is consistent with
  the v1.54 precedent and does not block spec approval.

**Blockers for v1.55.1 specifically:** Aggregator API credentials needed for SWIFT (Wise
Business / Currencycloud / Airwallex) — developer / sandbox credentials are freely available.
