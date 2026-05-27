# v1.54 — Bank Rails Payments

> **Status:** spec landed 2026-05-27. Go/no-go: **go**.
> Implementation phases: [v1.54.1](#v1541--sepa-credit-transfer--core-direct-debit) → v1.54.4.

---

## 1. Goals

- Define a **`BankRailsProvider` SPI** (6 methods) that covers the four most commercially
  significant bank payment rails: SEPA (EU), ACH (US), Pix (Brazil), FedNow (US instant).
- Reuse the `Money`, `IdempotencyKey`, and `WebhookReceiver` primitives already defined
  in v1.53 (`payments/money/`, `payments/webhook/`).
- Model **async settlement** as a first-class concern — unlike card PSPs that return
  synchronous results, bank rails are inherently T+0 to T+2 with intermediate pending states.
- Provide a **mandate lifecycle** for direct-debit rails (SEPA DD, ACH debit) that enables
  merchant-initiated debit without the customer being present at debit time.
- Cover **file-based delivery** (PAIN XML via EBICS, Nacha flat-file via SFTP) alongside
  **API delivery** (Pix DICT REST, FedNow ISO 20022 over FedLine), because both appear in
  production bank integrations.

---

## 2. Non-goals

- **Card PSPs** — Stripe / PayPal / Adyen / Square are fully covered by v1.53. Nothing in
  v1.54 changes the `PaymentProvider` SPI.
- **UPI / BACS / SWIFT / SEPA Instant CT (SCT Inst)** — These are real-world rails but are
  excluded from v1.54. Future milestones may add them as `RailKind` cases.
- **Real-time gross settlement (RTGS)** — Fedwire, TARGET2, CHAPS require direct central-bank
  connectivity; not appropriate for a plugin-level SPI.
- **AML / KYC screening** — Bank rails require AML checks in production; a `ComplianceProvider`
  SPI is deferred. The spec notes the obligation in per-rail sections but does not implement it.
- **FX / multi-currency conversion** — `FxProvider` deferred (same as v1.53 §13).
- **Direct bank API connectivity** — Production ACH requires an FI (financial institution)
  sponsor, and SEPA requires a SEPA-scheme participant. The adapters communicate with a
  **sponsor/aggregator API** (e.g., Modern Treasury, Column, Stripe ACH, Adyen ACH), not
  with the Federal Reserve or EBA CLEARING directly.

---

## 3. Why bank rails are separate from card PSPs

Four structural differences drive the split:

### 3.1 Settlement timing

Card charges settle in T+1 to T+2 and the PSP gives a synchronous "approved/declined"
response.  Bank rail credits and debits are **asynchronous by design**: the initiating
party submits a payment instruction and receives confirmation of *receipt*, not of *final
settlement*, which happens later in a separate message.

| Rail | Typical settlement | Source of truth |
|------|--------------------|-----------------|
| SEPA Credit Transfer | T+1 (next business day) | Target2/EBA CLEARING pacs.002 |
| SEPA Core Direct Debit | T+2 (D-2 pre-notification required) | pacs.002 / return window |
| ACH Credit | T+1 same-day or T+2 standard | Nacha return window (R-codes) |
| ACH Debit | T+1 same-day or T+2 standard | Nacha return window 60-day consumer |
| Pix | T+0 (< 10 seconds) | DICT confirmation |
| FedNow | T+0 (< 10 seconds) | pacs.002 acknowledgment |

This means the primary response to `initiateTransfer` is `Pending` — the terminal states
(`Settled`, `Rejected`, `Returned`) arrive via webhook or polling.

### 3.2 Mandate lifecycle for direct-debit rails

Direct-debit rails (SEPA DD, ACH debit) require a signed **mandate** — a one-time
customer authorization for the merchant to pull funds from their account.  The mandate has
a lifecycle: `Pending → Active → Canceled | Expired | Revoked`.  Card PSPs handle this
implicitly (PSD2 MIT/SCA path); bank rails expose it explicitly.

Mandates are distinct from `StoredMethod` vault tokens in v1.53:

- A vault token is a PSP-side token for a tokenized card or bank account.
- A mandate is a legal authorization document that may have a paper trail, a reference ID
  traceable to the customer's bank statement, and a maximum-debit-amount cap.

### 3.3 File-based vs API delivery

Card PSPs offer REST JSON APIs.  Some bank rails still use **file-based message delivery**:

- **SEPA**: ISO 20022 PAIN XML messages delivered over **EBICS** (Electronic Banking
  Internet Communication Standard) or SFTP to the bank.
- **ACH**: **Nacha flat-file format** (fixed-column ASCII) delivered over SFTP or FTP to
  an ACH originator.

Pix and FedNow use REST/ISO 20022 APIs but require dedicated transport layers (DICT REST
for Pix, FedLine for FedNow).  The SPI abstracts delivery behind `initiateTransfer` /
`initiateDirectDebit` calls, but each adapter's protocol section documents the underlying
wire format.

### 3.4 Error model: return codes vs declines

Card declines are synchronous and well-typed (`CardDeclined(code: String)`).
Bank rail rejections are asynchronous **return messages** with rail-specific codes:

- SEPA: `REJECT` / `RETURN` message, `ReasonCode` (e.g. `AC01` — incorrect account number,
  `MS02` — unspecified reason).
- ACH: **R-codes** (e.g. `R02` — account closed, `R10` — customer advises not authorized).
- Pix: `ED05` — timeout, `AB09` — beneficiary not found.
- FedNow: ISO 20022 `pacs.002` `ReasonCode` (`AC01`, `CUST`, `DUPL`, etc.).

The `BankTransferStatus` type models these as `Returned(code: ReturnCode, description: String)`
and `Rejected(code: RejectCode, description: String)`.

---

## 4. Type-level surface

### 4.1 RailKind

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/RailKind.scala

enum RailKind:
  case SEPA_CT      // SEPA Credit Transfer  — push, T+1
  case SEPA_DD      // SEPA Core Direct Debit — pull, T+2, mandate required
  case ACH_CREDIT   // ACH Credit (push) — standard T+2 or same-day T+1
  case ACH_DEBIT    // ACH Debit (pull) — standard T+2 or same-day T+1, mandate required
  case PIX          // Pix instant (Brazil) — T+0, < 10 seconds
  case FEDNOW       // FedNow instant (US) — T+0, < 10 seconds
```

### 4.2 BankTransfer

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/BankTransfer.scala

opaque type TransferId = String
object TransferId:
  def apply(s: String): TransferId = s

case class BankAccount(
  iban:          Option[String] = None,    // SEPA
  accountNumber: Option[String] = None,    // ACH / Pix / FedNow
  routingNumber: Option[String] = None,    // ACH (ABA routing number)
  bankCode:      Option[String] = None,    // Pix ISPB / FedNow routing
  pixKey:        Option[String] = None,    // Pix key (CPF/CNPJ/phone/email/EVP)
  holderName:    String,
  countryCode:   String,                   // ISO 3166-1 alpha-2
)

case class InitiateTransferRequest(
  rail:             RailKind,
  amount:           Money,
  sender:           BankAccount,
  recipient:        BankAccount,
  reference:        String,                // end-to-end reference (max 35 chars for SEPA)
  idempotencyKey:   IdempotencyKey,
  sameDay:          Boolean = false,       // ACH same-day flag; ignored for non-ACH rails
  scheduledDate:    Option[java.time.LocalDate] = None,  // None = earliest possible
  metadata:         Map[String, String] = Map.empty,
)

case class BankTransfer(
  id:           TransferId,
  rail:         RailKind,
  amount:       Money,
  sender:       BankAccount,
  recipient:    BankAccount,
  reference:    String,
  status:       BankTransferStatus,
  createdAt:    java.time.Instant,
  settledAt:    Option[java.time.Instant] = None,
  returnedAt:   Option[java.time.Instant] = None,
  metadata:     Map[String, String] = Map.empty,
)

enum BankTransferStatus:
  case Pending                                                // submitted, awaiting settlement
  case Settled                                               // funds confirmed by receiving bank
  case Rejected(code: RejectCode, description: String)       // rejected before settlement
  case Returned(code: ReturnCode, description: String)       // returned after settlement
  case Canceled                                              // canceled before submission

case class RejectCode(value: String)   // opaque — rail-specific (SEPA: pacs.002; ACH: R-code; Pix: ED05 etc.)
case class ReturnCode(value: String)   // opaque — rail-specific post-settlement return
```

### 4.3 DirectDebitMandate

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/DirectDebitMandate.scala

opaque type MandateId = String
object MandateId:
  def apply(s: String): MandateId = s

case class InitiateDirectDebitRequest(
  rail:           RailKind,               // must be SEPA_DD or ACH_DEBIT
  amount:         Money,
  mandateId:      MandateId,
  reference:      String,                 // per-collection reference
  idempotencyKey: IdempotencyKey,
  sameDay:        Boolean = false,        // ACH only
  scheduledDate:  Option[java.time.LocalDate] = None,
  metadata:       Map[String, String] = Map.empty,
)

case class DirectDebitMandate(
  id:             MandateId,
  rail:           RailKind,
  debtorAccount:  BankAccount,
  creditorAccount: BankAccount,
  creditorName:   String,
  status:         MandateStatus,
  signedAt:       Option[java.time.Instant] = None,
  maxAmount:      Option[Money] = None,           // SEPA: optional cap; ACH: rarely enforced
  sequenceType:   MandateSequenceType,
  providerRef:    Option[String] = None,          // PSP / aggregator mandate reference
  metadata:       Map[String, String] = Map.empty,
)

enum MandateStatus:
  case Pending      // created locally, not yet confirmed by bank
  case Active       // confirmed — debits may be initiated
  case Suspended    // temporarily blocked (e.g. pre-notification failed)
  case Canceled     // canceled by customer or merchant
  case Expired      // SEPA: 36-month inactivity; ACH: lender-specific
  case Revoked      // customer contacted their bank directly (ACH Reg E)

enum MandateSequenceType:
  case OneOff   // single-use
  case First    // first in a series (SEPA-specific notification)
  case Recurring
  case Final    // last in a series (SEPA-specific)
```

### 4.4 BankRailsProvider SPI

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/BankRailsProvider.scala

trait BankRailsProvider:
  def id:          String      // "sepa" | "ach" | "pix" | "fednow" | "modern-treasury" | …
  def displayName: String
  def spiVersion:  String
  def supportedRails: Set[RailKind]

  // ── Push payments (credit transfer, Pix, FedNow) ────────────────────────────
  def initiateTransfer(req: InitiateTransferRequest):          IO[BankTransfer] ! BankRails
  def getTransfer(id: TransferId):                             IO[BankTransfer] ! BankRails
  def cancelTransfer(id: TransferId):                          IO[BankTransfer] ! BankRails

  // ── Pull payments (direct debit) ─────────────────────────────────────────────
  def initiateDirectDebit(req: InitiateDirectDebitRequest):   IO[BankTransfer] ! BankRails
  def getDirectDebit(id: TransferId):                          IO[BankTransfer] ! BankRails

  // ── Webhook delivery ─────────────────────────────────────────────────────────
  def webhookReceiver: WebhookReceiver[BankRailsEvent]

// Payment effect row for bank rails
type BankRails  // effect row discharged by BankRailsProvider adapter or MockBankRailsProvider
```

**Design notes:**

- `cancelTransfer` is meaningful only while the transfer is `Pending` (before batch
  submission for ACH/SEPA, or before the 10-second window for Pix/FedNow).  Attempting
  to cancel a `Settled` transfer returns `BankRailsCancelError.AlreadySettled`.
- There is no `createMandate` / `cancelMandate` in the primary SPI because mandate setup
  is done out-of-band (signed authorization form, OTP, bank redirect) before the first
  debit.  Adapters that offer mandate provisioning APIs (Modern Treasury, Stripe ACH) may
  expose these as provider-specific extensions.  The spec tracks only the mandate state
  as visible to the merchant.
- `getDirectDebit` polls for the status of a previously initiated debit collection.
  Callers should prefer webhooks over polling.

### 4.5 BankRailsEvent webhook union

```scala
// payments/bank-rails/src/main/scala/scalascript/payments/bankrails/BankRailsEvent.scala

enum BankRailsEvent:
  // SEPA
  case SepaTransferCompleted(transfer: BankTransfer)
  case SepaTransferRejected(transfer: BankTransfer, code: RejectCode)
  case SepaTransferReturned(transfer: BankTransfer, code: ReturnCode)
  case SepaMandateActivated(mandate: DirectDebitMandate)
  case SepaMandateCanceled(mandate: DirectDebitMandate)
  case SepaDirectDebitCompleted(transfer: BankTransfer)
  case SepaDirectDebitReturned(transfer: BankTransfer, code: ReturnCode)

  // ACH
  case AchTransferSettled(transfer: BankTransfer)
  case AchReturn(transfer: BankTransfer, rCode: RCode, description: String)
  case AchNotificationOfChange(transfer: BankTransfer, cCode: CCode, correctedData: String)

  // Pix
  case PixReceived(transfer: BankTransfer)
  case PixRefunded(transfer: BankTransfer, original: TransferId)
  case PixRejected(transfer: BankTransfer, code: RejectCode)

  // FedNow
  case FedNowCreditReceived(transfer: BankTransfer)
  case FedNowRejected(transfer: BankTransfer, code: RejectCode)

// ACH-specific types
opaque type RCode = String   // R01..R85 — Nacha return reason codes
object RCode:
  val R01: RCode = "R01"  // insufficient funds
  val R02: RCode = "R02"  // bank account closed
  val R03: RCode = "R03"  // no bank account / unable to locate account
  val R04: RCode = "R04"  // invalid bank account number
  val R07: RCode = "R07"  // authorization revoked by customer
  val R08: RCode = "R08"  // payment stopped
  val R10: RCode = "R10"  // customer advises not authorized
  val R16: RCode = "R16"  // bank account frozen
  // … complete list in adapter implementation
  def apply(s: String): RCode = s

opaque type CCode = String   // C01..C09 — Nacha Notification of Change codes
object CCode:
  val C01: CCode = "C01"  // incorrect bank account number
  val C02: CCode = "C02"  // incorrect routing number
  val C03: CCode = "C03"  // incorrect routing + account numbers
  val C05: CCode = "C05"  // incorrect transaction code
  val C06: CCode = "C06"  // incorrect account number and transaction code
  val C07: CCode = "C07"  // incorrect routing, account number and transaction code
  def apply(s: String): CCode = s
```

---

## 5. Settlement timing model

| Rail | Submission | Processing cut-off | Settlement | Return window |
|------|------------|-------------------|------------|---------------|
| SEPA CT | PAIN.001 XML | Bank's daily cut-off (typically 15:00–17:00 CET) | T+1 (next business day) | Not applicable (credit push) |
| SEPA DD | PAIN.008 XML | D-2 pre-notification required; D-0 cut-off | T+0+2 business days | D+5 weeks (unauthorized); 8 weeks (consumer authorized) |
| ACH Credit | Nacha flat-file | 10:30 AM / 2:45 PM ET for same-day; 5:00 PM ET standard | T+1 same-day; T+2 standard | N/A (push) |
| ACH Debit | Nacha flat-file | 10:30 AM / 2:45 PM ET for same-day; 5:00 PM ET standard | T+1 same-day; T+2 standard | 2 business days (admin); 60 calendar days (consumer Reg E) |
| Pix | DICT REST | Continuous (24/7) | T+0 (< 10 s) | Up to D+90 days via BACEN dispute |
| FedNow | ISO 20022 pacs.008 | Continuous (24/7) | T+0 (< 10 s) | pacs.002 rejection within seconds |

**Implications for `BankTransferStatus`:**

- SEPA CT and ACH Credit will typically stay `Pending` for 1–2 business days before
  transitioning to `Settled` or `Rejected`.
- Pix and FedNow resolve to `Settled` or `Rejected` within seconds; polling `getTransfer`
  a few times is a viable fallback if webhooks are unavailable.
- ACH Debit returns can arrive **up to 60 calendar days** after settlement for consumer
  accounts.  Adapters must handle `AchReturn` events long after the original collection.

---

## 6. Idempotency

Bank rails reuse `IdempotencyKey` from v1.53 (`payments/webhook/`):

```scala
def initiateTransfer(req: InitiateTransferRequest): IO[BankTransfer] ! BankRails
// req.idempotencyKey is mandatory — callers must supply it
```

| Rail | Idempotency mechanism |
|------|-----------------------|
| SEPA | `EndToEndId` in PAIN XML (max 35 chars, unique per bank per day) |
| ACH | `Individual ID` or `Company Entry Description` in Nacha file |
| Pix | `txid` in DICT API (UUID v4, merchant-generated) |
| FedNow | `InstrId` in pacs.008 ISO 20022 header |

`IdempotencyKey.fromRequest(req)` is available as a fallback, but callers should supply a
meaningful key (e.g. `IdempotencyKey(s"transfer-${orderId}-attempt-${n}")`).

Duplicate submissions with the same key return the original `BankTransfer` record.
Duplicate submissions with the same key but a different amount/recipient throw
`DuplicateTransferRequest(originalId: TransferId)`.

---

## 7. Webhook event taxonomy

### 7.1 SEPA

| Event | Payload | Trigger |
|-------|---------|---------|
| `sepa.transfer.completed` | `SepaTransferCompleted(transfer)` | pacs.002 positive status from receiving bank |
| `sepa.transfer.rejected` | `SepaTransferRejected(transfer, code)` | pacs.002 negative; reject codes AC01/AC04/AC06/BE01/FOCR/MS02/RC01 |
| `sepa.transfer.returned` | `SepaTransferReturned(transfer, code)` | pacs.004 return; reason AC01/MS02/CUST/DUPL |
| `sepa.directdebit.completed` | `SepaDirectDebitCompleted(transfer)` | pacs.003 accepted |
| `sepa.directdebit.returned` | `SepaDirectDebitReturned(transfer, code)` | pacs.004 return within consumer window |
| `sepa.mandate.activated` | `SepaMandateActivated(mandate)` | PSP confirms mandate accepted by debtor's bank |
| `sepa.mandate.canceled` | `SepaMandateCanceled(mandate)` | Customer cancellation or inactivity expiry |

### 7.2 ACH

| Event | Payload | Trigger |
|-------|---------|---------|
| `ach.transfer.settled` | `AchTransferSettled(transfer)` | Settlement date reached with no return |
| `ach.return` | `AchReturn(transfer, rCode, description)` | RDFI (receiving bank) returns the item; R-code identifies reason |
| `ach.notification_of_change` | `AchNotificationOfChange(transfer, cCode, correctedData)` | RDFI sends NOC with corrected account/routing details |

**R-code families** (from Nacha Operating Rules):

| Range | Meaning |
|-------|---------|
| R01–R08 | Account / funds issues |
| R09–R14 | Authorization issues |
| R15–R23 | Deceased / bankruptcy / legal |
| R29–R33 | Corporate / government |
| R61–R85 | Dishonored returns |

**NOC handling requirement:** Nacha rules require originating institutions to update account
details within 6 banking days of receiving an NOC.  Adapter implementations must surface
`AchNotificationOfChange` events promptly.

### 7.3 Pix

| Event | Payload | Trigger |
|-------|---------|---------|
| `pix.received` | `PixReceived(transfer)` | PSP webhook on credit confirmation from DICT |
| `pix.refunded` | `PixRefunded(transfer, originalId)` | Devolution (devolução) processed |
| `pix.rejected` | `PixRejected(transfer, code)` | DICT error or timeout; codes ED05/AB09/AGNT |

Pix uses **HMAC-SHA256** signature on the raw webhook body.  The signing key is derived from
the PSP client certificate (mTLS mutual-TLS channel). Each PSP (Sicoob, Itaú, Banco do Brasil,
inter alia) uses a slightly different header name; adapters normalize to `X-Pix-Signature`.

### 7.4 FedNow

| Event | Payload | Trigger |
|-------|---------|---------|
| `fednow.credit.received` | `FedNowCreditReceived(transfer)` | pacs.002 positive acknowledgment from Fed |
| `fednow.rejected` | `FedNowRejected(transfer, code)` | pacs.002 negative; codes AC01/CUST/DUPL/FOCR |

FedNow uses **ISO 20022 message signing** (JWS with certificate pinning) delivered over
FedLine Direct.  Aggregator adapters (e.g. Modern Treasury FedNow, Stripe Treasury)
translate to REST webhooks signed with HMAC-SHA256 over raw body.

---

## 8. Per-rail sections

### §v1.54.1 — SEPA (Credit Transfer + Core Direct Debit)

**Protocol:** ISO 20022 PAIN XML messages.
- Credit Transfer: `pain.001.001.09` (customer credit transfer initiation)
- Direct Debit: `pain.008.001.09` (customer direct debit initiation)
- Status report: `pain.002.001.12` (customer payment status report)

**Auth:** EBICS (Electronic Banking Internet Communication Standard) signature.
- User authentication via asymmetric RSA/ECC keys registered with the bank.
- Messages signed with A005/A006 signature class.
- Banks increasingly offer REST APIs (e.g. via Open Banking PSD2 / Berlin Group STET API)
  as an alternative; adapter supports both.

**File format:** UTF-8 XML, validated against ISO 20022 XSD.  Each file is a `GroupHeader`
wrapping one or more `PaymentInstructions`, each containing one or more `CreditTransferTransactionInformation`
(CT) or `DirectDebitTransactionInformation` (DD) records.

**Mandate model (SEPA DD):**
- Mandate reference (`MndtId`) max 35 chars, unique to creditor scheme ID.
- Sequence type: `FRST` (first), `RCUR` (recurring), `FNAL` (final), `OOFF` (one-off).
- Pre-notification: creditor must notify debtor at least 2 business days before collection.
- D-2 rule: mandate file must reach the bank at least 2 business days before settlement.
- Inactivity expiry: 36 months of no collections.

**Webhook verify:** Adapter registers a PSP webhook (REST JSON over HTTPS).  Signature header:
`X-SEPA-Signature: sha256=<hex>`.  HMAC-SHA256 over raw body using the webhook secret.

**Adapter:** `runtime/std/payments-sepa/`.

**Deliverables — v1.54.1:**
- `payments/bank-rails/` new subproject: `RailKind`, `BankAccount`, `BankTransfer`,
  `BankTransferStatus`, `DirectDebitMandate`, `MandateStatus`, `MandateSequenceType`,
  `InitiateTransferRequest`, `InitiateDirectDebitRequest`, `BankRailsEvent`, `BankRailsProvider`.
- `runtime/std/payments-sepa/` adapter: PAIN.001 + PAIN.008 XML generation; EBICS signing
  (stub + real-cert mode); pacs.002 status parsing; mandate lifecycle tracking.
- `runtime/backend/spi/Feature.scala`: add `case BankRails`.
- Webhook handler: `sepa.transfer.completed` / `.rejected` / `.returned` +
  `sepa.directdebit.completed` / `.returned` + mandate events.
- Tests: PAIN XML generation (round-trip), mandate sequence validation, pacs.002 parsing,
  webhook signature verify, `BankTransferStatus` transitions.
- Example: `examples/bank-rails-sepa.ssc`.

---

### §v1.54.2 — ACH (Credit + Debit via Nacha)

**Protocol:** Nacha flat-file format (ACH file), delivered over SFTP or REST to an
ACH originator (ODFI — originating depository financial institution) or an aggregator.

**Auth:**
- Direct ODFI: mutual TLS + SFTP public-key authentication.
- Aggregator (Modern Treasury, Column, Stripe ACH): OAuth2 bearer token or API key.

**File format:** Nacha fixed-column ASCII.  Structure:
```
File Header (1)
  Company Batch Header (5)
    Entry Detail (6)  ← one per transaction
    Addenda (7)       ← optional; remittance info
  Company Batch Control (8)
File Control (9)
```
- Entry: 94-character fixed-width record.
- Standard Entry Class (SEC) codes: `PPD` (prearranged payment, consumer), `CCD` (corporate credit/debit), `WEB` (internet-initiated), `TEL` (telephone-initiated).
- Same-day ACH flag: `SAMEDAY` in `Company Entry Description` (col 54-63) of batch header.

**Return code handling:**
- Returns arrive 2 business days (admin) or up to 60 days (consumer) after settlement.
- `AchReturn(transfer, rCode, description)` event; adapter maps all 85 Nacha R-codes to
  `ReturnCode` + human-readable description.
- NOC (Notification of Change): `AchNotificationOfChange(transfer, cCode, correctedData)`
  — adapter must update stored account details and re-submit.

**Mandate model (ACH Debit):**
- Written authorization from the consumer (Reg E) or corporation.
- No formal protocol mandate ID; merchants store their own reference.
- `MandateStatus.Active` = authorization on file; `Revoked` = customer contacted their bank.
- `DirectDebitMandate.maxAmount` is not enforced by Nacha but is tracked locally.

**Webhook verify:** HMAC-SHA256 over raw body; header `X-ACH-Signature`.

**Adapter:** `runtime/std/payments-ach/`.

**Deliverables — v1.54.2:**
- `runtime/std/payments-ach/` adapter: Nacha file generation + parser; same-day ACH flag;
  R-code + C-code enum constants (full Nacha list); mandate tracking in memory/file store.
- Webhook handler: `ach.transfer.settled` / `ach.return` / `ach.notification_of_change`.
- Tests: Nacha file round-trip, R-code mapping, same-day flag, NOC handling.
- Example: `examples/bank-rails-ach.ssc`.

---

### §v1.54.3 — Pix (Brazil instant payments)

**Protocol:** BACEN (Banco Central do Brasil) DICT (Diretório de Identificadores de Chaves
Pix) REST API + PSP REST APIs.  Communication uses mTLS with a BACEN-issued certificate.

**Auth:**
- Direct: mTLS client certificate issued by BACEN ICP-Brasil CA.  Certificate pinning.
- Aggregator (Stripe Pix, PayPal Pix, Gerencianet/EFÍ, PagSeguro, Mercado Pago):
  OAuth2 client-credentials or API key.

**Pix key types:** CPF/CNPJ (tax ID), phone number `+55XXXXXXXXXXX`, email, EVP (chave aleatória).

**QR Code types:**
- **Static QR** (`QRStatic`): fixed amount or no amount; reusable.
- **Dynamic QR** (`QRDynamic`): one-time use; amount embedded; contains `txid`.
  Dynamic QR is preferred for e-commerce (prevents duplicate payments).

**Idempotency:** `txid` (32-char UUID v4, merchant-generated).  BACEN returns
`MutaPagamentoNaoPermitido` (EM16) if `txid` already used with different data.

**Settlement:** T+0 — credit arrives in < 10 seconds (SLA guarantee by BACEN).
Failed / rejected transfers get a `cob.fail` webhook event within seconds.

**Webhook verify:** mTLS on the receiving side (PSP initiates callback to merchant HTTPS
endpoint).  Aggregator APIs use `X-Pix-Signature: sha256=<hex>` HMAC over raw body.

**Adapter:** `runtime/std/payments-pix/`.

**Deliverables — v1.54.3:**
- `runtime/std/payments-pix/` adapter: DICT API client (Pix key lookup, QR generation);
  `txid` generation; `BankAccount.pixKey` field; mTLS certificate loading.
- `BankRailsEvent.PixReceived` / `.PixRefunded` / `.PixRejected` handling.
- Tests: `txid` generation, QR code static/dynamic, webhook signature verify, T+0 status
  resolution via polling fallback.
- Example: `examples/bank-rails-pix.ssc`.

---

### §v1.54.4 — FedNow (US instant payments)

**Protocol:** ISO 20022 message format over **FedLine Direct** (Federal Reserve's leased-line
network) or FedNow Aggregator APIs.
- `pacs.008.001.09` — FedNow Credit Transfer (debit-credit legs)
- `pacs.002.001.12` — Payment Status Report (positive / negative acknowledgment)
- `admi.002.001.01` — FedNow System Event Notification

**Auth:**
- Direct FedLine: X.509 certificate (issued by Federal Reserve); JWS message signing.
- Aggregator (Modern Treasury FedNow, Column FedNow, Stripe Treasury instant): OAuth2 or
  API key; REST JSON over HTTPS; webhook delivery.

**Settlement:** T+0 — funds available at receiving bank in < 20 seconds (99th percentile).
pacs.002 positive ACK = `Settled`.  pacs.002 negative = `Rejected`.

**Supported transaction types:**
- Credit Transfer (push): payer-initiated.
- Request for Payment (RfP): payee requests payment; payer receives and approves.
  RfP is deferred to v1.54.4.x (requires additional message type).

**Message signing:** JWS compact serialization with `alg: RS256` or `ES256` per FedNow
spec.  Adapter verifies incoming pacs.002 JWS header against the Fed's published public key.

**Limits:** FedNow maximum transaction = $500,000 USD (as of 2026).  Participating FI may
set lower limits.  Adapter surfaces `FedNowLimitExceeded` error.

**Adapter:** `runtime/std/payments-fednow/`.

**Deliverables — v1.54.4:**
- `runtime/std/payments-fednow/` adapter: pacs.008 XML generation; pacs.002 parsing;
  JWS signature verify; `admi.002` system event handling.
- `BankRailsEvent.FedNowCreditReceived` / `.FedNowRejected` handling.
- ISO 20022 `ReasonCode` enum: AC01 (invalid account), CUST (customer decision), DUPL
  (duplicate), FOCR (following cancellation request), RC01 (bank ID incorrect).
- Tests: pacs.008 generation, pacs.002 parse + JWS verify, limit check, reject code mapping.
- Example: `examples/bank-rails-fednow.ssc`.

---

## 9. Module layout

```
payments/
  bank-rails/            ← core types (BankRailsProvider SPI + all type definitions)
    src/main/scala/scalascript/payments/bankrails/
      RailKind.scala
      BankAccount.scala
      BankTransfer.scala
      BankTransferStatus.scala
      DirectDebitMandate.scala
      MandateStatus.scala
      InitiateTransferRequest.scala
      InitiateDirectDebitRequest.scala
      BankRailsEvent.scala
      BankRailsProvider.scala
      ReturnCode.scala
      RejectCode.scala
      RCode.scala         ← ACH R-codes
      CCode.scala         ← ACH C-codes (NOC)

runtime/std/
  payments-sepa/         ← §v1.54.1
  payments-ach/          ← §v1.54.2
  payments-pix/          ← §v1.54.3
  payments-fednow/       ← §v1.54.4

examples/
  bank-rails-sepa.ssc
  bank-rails-ach.ssc
  bank-rails-pix.ssc
  bank-rails-fednow.ssc
```

All four adapter subprojects follow the 2-file + META-INF layout established in
`runtime/std/payments-stripe/` (a single `<Rail>Provider.scala` + `<Rail>Intrinsics.scala`
+ `META-INF/services/scalascript.payments.BankRailsProvider`).

---

## 10. Error model

```scala
sealed class BankRailsError(msg: String) extends RuntimeException(msg)
case class UnsupportedRail(rail: RailKind, provider: String)
  extends BankRailsError(s"$provider does not support $rail")
case class TransferNotFound(id: TransferId)
  extends BankRailsError(s"transfer $id not found")
case class MandateNotActive(id: MandateId, status: MandateStatus)
  extends BankRailsError(s"mandate $id is $status, not Active")
case class DuplicateTransferRequest(originalId: TransferId)
  extends BankRailsError(s"idempotency key already used for transfer $originalId")
case class BankRailsCancelError(id: TransferId, reason: String)
  extends BankRailsError(s"cannot cancel $id: $reason")
case class FedNowLimitExceeded(amount: Money, limit: Money)
  extends BankRailsError(s"amount $amount exceeds FedNow limit $limit")
case class PixKeyNotFound(key: String)
  extends BankRailsError(s"Pix key not found: $key")
case class NachaCutoffMissed(scheduledDate: java.time.LocalDate)
  extends BankRailsError(s"ACH cut-off missed for $scheduledDate — next available date is next business day")
```

---

## 11. Diagnostics

**D1 — Rail not supported by provider**
```
error: UnsupportedRail — provider "payments-sepa" does not support PIX
  supportedRails: SEPA_CT, SEPA_DD
  use a Pix-capable provider (e.g. payments-pix or modern-treasury)
```

**D2 — Mandate not active**
```
error: MandateNotActive — mandate mnd_xxx is Expired (SEPA 36-month inactivity)
  last collection: 2023-01-14
  to reactivate: obtain a new signed mandate from the customer (MandateSequenceType.First)
  and submit a new mandate registration to your bank
```

**D3 — ACH return R10**
```
ach.return — R10: Customer Advises Not Authorized
  transfer: ach_yyy  amount: $49.99 USD  date: 2026-05-27
  consumer has up to 60 days to dispute under Nacha Reg E
  action required:
    1. do not re-originate this entry
    2. contact the customer to resolve authorization
    3. if the customer confirms authorization, obtain a new signed authorization
```

**D4 — Pix key not found**
```
error: PixKeyNotFound — key "+5511999990000"
  the Pix key is not registered in DICT
  verify: the number is correct, the account holder has registered the key
  fallback: ask the customer for their IBAN-equivalent (agency + account number)
```

**D5 — FedNow limit exceeded**
```
error: FedNowLimitExceeded
  requested: $600,000.00 USD
  FedNow maximum: $500,000.00 USD
  split the transfer into two transactions each under the limit
  or use ACH (no per-transaction limit for business accounts)
```

**D6 — SEPA D-2 pre-notification deadline**
```
error: SepaMandateMissedDeadline — scheduled date 2026-05-28 requires submission by 2026-05-26
  today: 2026-05-27 (too late for 2026-05-28)
  earliest valid scheduled date: 2026-05-29
  adjust scheduledDate in InitiateDirectDebitRequest
```

---

## 12. Testing strategy

Each adapter phase (v1.54.1–v1.54.4) ships with:

1. **Unit tests** — XML/file generation (round-trip), status parsing, signature verify.
2. **Mock adapter** — `MockBankRailsProvider` (in-memory; configurable `Settled` / `Rejected` / `Returned` outcomes; fires webhook events synchronously in tests) ships alongside the core SPI in `payments/bank-rails/` at v1.54.1.
3. **Integration tests** (optional, flag-gated) — real sandbox API calls for Modern Treasury
   ACH sandbox and Pix sandbox (Banco Central provides a sandbox environment).

---

## 13. Composition with v1.53 PaymentProvider

The `BankRailsProvider` SPI is **intentionally separate** from `PaymentProvider`:

| Concern | `PaymentProvider` (v1.53) | `BankRailsProvider` (v1.54) |
|---------|--------------------------|------------------------------|
| Rail type | Card networks (Visa, MC, Amex) | ACH, SEPA, Pix, FedNow |
| Settlement | Synchronous (approved/declined) | Async (pending → settled/returned) |
| Primary abstraction | `PaymentIntent` state machine | `BankTransfer` + `DirectDebitMandate` |
| Mandate | Implicit (PSP handles) | Explicit mandate lifecycle |
| Idempotency | PSP-specific headers | Rail-specific end-to-end ID |
| Effect row | `! Payment` | `! BankRails` |

Some PSP adapters (Stripe, Adyen) support both card and ACH/SEPA via their own APIs.
For those, the adapter can implement **both** `PaymentProvider` and `BankRailsProvider`.
Example: `runtime/std/payments-stripe/` may gain `StripeAchProvider extends BankRailsProvider`
in a follow-up to v1.54.2.

---

## 14. AML / KYC obligation note

Bank rails are subject to AML (Anti-Money Laundering) and KYC (Know Your Customer)
regulation in every jurisdiction.  This spec does not implement screening.
Operators using the v1.54 adapters are responsible for:

- SEPA: EBA AML / 5AMLD compliance; `BeneficiaryName` matching against OFAC / EU sanctions.
- ACH: FinCEN BSA compliance; OFAC sanctions screening on recipient name + account.
- Pix: BACEN AML regulation; CPF/CNPJ validation.
- FedNow: FinCEN / OFAC; automated sanctions screening required by FedNow Operating Procedures.

A `ComplianceProvider` SPI (sanctions screening, enhanced due diligence) is planned as a
future milestone.

---

## 15. Implementation phases

### v1.54.1 — SEPA Credit Transfer + Core Direct Debit

**Scope:** Foundation (`payments/bank-rails/` SPI + all core types) + SEPA adapter.
**Deliverables:** listed in §8 v1.54.1 above.
**Blocked by:** nothing (v1.53.7 already landed).
**Parallelism:** v1.54.2 may start in parallel with v1.54.1 (independent adapter dir).

### v1.54.2 — ACH (Credit + Debit via Nacha)

**Scope:** ACH Nacha flat-file adapter.
**Deliverables:** listed in §8 v1.54.2.
**Blocked by:** `payments/bank-rails/` core types (v1.54.1 or inline).
**Parallelism:** may run parallel to v1.54.1 if `payments/bank-rails/` is scaffolded first.

### v1.54.3 — Pix (Brazil instant payments)

**Scope:** Pix DICT REST adapter.
**Deliverables:** listed in §8 v1.54.3.
**Blocked by:** `payments/bank-rails/` core types.
**Parallelism:** independent of v1.54.1 and v1.54.2.

### v1.54.4 — FedNow (US instant payments)

**Scope:** FedNow ISO 20022 adapter.
**Deliverables:** listed in §8 v1.54.4.
**Blocked by:** `payments/bank-rails/` core types.
**Parallelism:** independent of v1.54.1–v1.54.3.

---

## 16. Open questions

**Q1 — Aggregator vs direct connectivity**
Production SEPA and ACH require sponsorship / direct FI connectivity.  Should the spec
assume all adapters use an aggregator API (Modern Treasury, Column, Stripe) and note
direct-connect as an extension, or specify both paths from the start?
_Recommended resolution: aggregator-first.  Direct EBICS / FedLine connectivity is an
extension; mark as `@experimental` in the adapter._

**Q2 — Mandate provisioning API**
Modern Treasury and Column expose mandate creation APIs (POST /mandates).  Should
`BankRailsProvider` gain `createMandate` / `cancelMandate` methods in v1.54.1, or keep
mandate setup out-of-band?
_Recommended resolution: out-of-band for v1.54.1; add a `MandateProvisioningExtension`
optional mixin in a v1.54.1.x follow-up if demand emerges._

**Q3 — Pix QR code generation**
QR code image generation requires a dependency (ZXing or similar).  Should it be in the
core SPI or in a separate `pix-qr-plugin`?
_Recommended resolution: separate optional subproject `runtime/std/payments-pix-qr/` to
avoid pulling ZXing into the base adapter._

**Q4 — FedNow Request for Payment (RfP)**
RfP (payee-initiated payment request) is a FedNow feature with a distinct message flow.
Should it be in v1.54.4 or deferred?
_Recommended resolution: defer to v1.54.4.x.  pacs.008 credit transfer covers the primary
use case (payer-initiated push payments)._

---

## 17. Go / no-go

**Go.**

Foundation is solid:
- v1.53 `Money` + `IdempotencyKey` + `WebhookReceiver` + `SeenKeyStore` are all landed
  and reusable without modification.
- v1.46 typed routes give webhook endpoints.
- Plugin pattern (`runtime/std/<name>/` + META-INF) is well-established.

Net new work: `payments/bank-rails/` core SPI + 4 adapter subprojects + 4 examples.
The phased structure (v1.54.1 → v1.54.4) keeps each phase independently shippable.
SEPA and ACH are the highest-value targets (EU + US commerce); Pix is the largest instant
payment rail by volume globally; FedNow is the emerging US instant rail.
