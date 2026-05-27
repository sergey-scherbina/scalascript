# v1.53 — Traditional Payment Processors

> **Status:** spec landed 2026-05-27. Go/no-go: **go**.
> Implementation phases: [v1.53.1](#161-v1531--plugin-scaffolding--money--paymentprovider-spi--webhookreceiver--stripe-adapter) → v1.53.7.

---

## 1. Motivation & relation to v1.38 Payment Request

ScalaScript v1.38 landed `docs/payment-request.md` (516 lines) and
`runtime/std/payment-request-plugin/`: the W3C Payment Request browser sheet,
Apple Pay merchant-validation TLS + ECDH/AES-256-GCM token decryption, and Google
Pay ECv2 signature-verify + decryption.  That covers the cryptographic hand-off from
wallet to merchant.

**What v1.38 deliberately left unimplemented is the charge step itself.**  Four
placeholder calls sit in `docs/payment-request.md` with no backing code:

```scala
// docs/payment-request.md:314
val result = chargeCard(decrypted)

// docs/payment-request.md:331
val result = chargeCard(card)

// docs/payment-request.md:388
chargeApplePay(card)

// docs/payment-request.md:394
chargeGooglePay(card)
```

Each caller is expected to write their own HTTP client against a PSP
(Stripe / Adyen / PayPal / …).  ScalaScript provides the decrypted
token; the merchant does the rest.  That is the gap.

**Adjacent gaps** confirmed by grepping the full tree:

- Zero references to Stripe, PayPal, Braintree, Square, Adyen, Checkout.com,
  Worldpay anywhere in `runtime/`, `payments/`, `lang/`, `docs/`.
  `docs/modularity.md:242` explicitly marks *"Integrations with third-party
  services (Stripe, Slack, Discord)"* as out-of-scope for core — so a plugin
  is the intended home, but no such plugin exists.
- No fiat-aware Money type.  The only money-like surface is
  `case class Amount(currency: String, value: String)` at
  `payments/payment-request/src/main/scala/scalascript/payment/PaymentTypes.scala:6`.
  No minor-unit arithmetic, no ISO 4217 validation, no rounding mode.
- No webhook primitive.  PSPs deliver outcomes asynchronously
  (`charge.succeeded`, `invoice.payment_failed`, `dispute.created`), requiring
  HMAC/RSA signature verify, replay protection, idempotency-keyed handlers, and
  retry semantics.  Grepping `webhook|Webhook` across `runtime/std/`,
  `payments/`, and `runtime/http-server/` returns nothing PSP-shaped.
- No subscription / recurring billing types (plans, proration, dunning, invoices).
- No refund / dispute / chargeback lifecycle — not even the type surface.
- No stored payment-method / vault / customer abstraction beyond what Apple/Google
  Pay decrypt exposes.
- No SCA / 3DS2 / PSD2 strong-customer-authentication challenge plumbing.
- No idempotency-key primitive.  Every PSP requires it for safe retries.
- `MILESTONES.md:50` — "Payments & Blockchain" direction queue is listed as empty.

`docs/micropayment-spi.md` (910 lines, phases 1-4 landed) is **crypto-only**:
`Facilitator: verify/settle`, `txHash` settlement, EIP-3009 / CIP-8 / Cardano/Scalus.
It does not generalize to fiat rails; the type surface (`txHash`, `paymentHeader`,
`blockchain`) has no analogue in PSP integrations.

**v1.53 fills this gap at the design level.**  The deliverable is `docs/traditional-payments.md`
(this file) — defining the `PaymentProvider` SPI, the fiat `Money` type, the
`WebhookReceiver` primitive, the customer/vault abstraction, and the subscription /
refund / dispute / SCA models.  **No code lands in v1.53** — same workflow as v1.12
algebraic effects, v1.51 streams, and v1.52 deploy.

---

## 2. Conceptual model

Seven first-class roles:

**`Money`** — `(minorUnits: Long, currency: Currency)`.  Bedrock value type for
every fiat amount in the system.  Replaces the stringly-typed
`Amount(currency: String, value: String)` from v1.38.

**`PaymentMethod`** — abstract handle carried to a PSP.  Variants:
`Card(networkToken | applePayToken | googlePayToken | rawDetails)`,
`BankAccount(accountId)`, `Wallet(provider, externalId)`, `SavedMethod(vaultId)`.
No raw PANs leave the host; PSP SDKs tokenize on collection.

**`Customer`** — long-lived buyer identity at the PSP.  Attaches payment methods;
subscribes to plans; receives invoices.  Keyed by a PSP-generated `CustomerId`.

**`PaymentIntent`** — explicit lifecycle state machine that gates every charge
through confirmation, optional SCA challenge, and capture.  Modelled on Stripe's
intent shape because it has converged with Adyen, Braintree, and Square's modern
APIs.

**`PaymentProvider` adapter** — SPI bridge encapsulating "how to talk to PSP X".
The 14-method trait defined in §3.  Same trait+ServiceLoader shape as
`Backend` (`runtime/backend/spi/src/main/scala/scalascript/backend/spi/Backend.scala`)
and `DeployTarget` (v1.52).

**`Subscription`** — recurring billing handle: plan, current period, status
(`trialing | active | past_due | canceled | unpaid | paused`), upcoming invoice
preview.  Owned by a PSP; local app state should treat the PSP as source of truth.

**`WebhookEvent`** — async outcome carrier from PSP.  Typed event union;
`verify` → `dispatch` flow in `WebhookReceiver[E]`.

Two orthogonal axes — **PSP × feature**:

```
                │ card charge │ SCA/3DS2 │ subscriptions │ refunds │ disputes │ vault │
────────────────┼─────────────┼──────────┼───────────────┼─────────┼──────────┼───────┤
Stripe          │     ✓       │    ✓     │       ✓       │    ✓    │    ✓     │   ✓   │
PayPal/Braintree│     ✓       │    ✓     │       ✓       │    ✓    │    ✓     │   ✓   │
Adyen           │     ✓       │    ✓     │       ✓       │    ✓    │    ✓     │   ✓   │
Square          │     ✓       │    ✗     │       ✓       │    ✓    │    ✓     │   ✓   │
```

Mental model anchors:

- `PaymentProvider` SPI ≈ `Backend` SPI from `runtime/backend/spi/` — pluggable
  adapter with `id`, `capabilities`, ServiceLoader registration.
- `WebhookReceiver[E]` ≈ typed route handler from v1.46 — receives an HTTP request,
  verifies a signature, dispatches to a typed event handler.
- `Subscription` lifecycle ≈ `DeployTarget` state machine from v1.52 — discrete
  state transitions, adapter-specific state names mapped to a shared enum.
- `Money` ≈ v1.51 `Stream[T]` — a first-class generic value type the whole system
  passes around, replacing ad-hoc strings.

---

## 3. Type-level surface

### 3.1 Money

New module `payments/money/`:

```scala
// payments/money/src/main/scala/scalascript/payments/money/Money.scala

opaque type Currency = String
object Currency:
  val USD: Currency = "USD"
  val EUR: Currency = "EUR"
  val GBP: Currency = "GBP"
  // … common constants, not exhaustive

  def apply(code: String): Currency        // validates ISO 4217 or known crypto code; throws on invalid
  def minorUnitsPower(c: Currency): Int    // USD→2, JPY→0, KWD→3, BTC→8, ETH→18
  def isFiat(c: Currency):  Boolean
  def isCrypto(c: Currency): Boolean       // BTC, ETH, USDC, ADA accepted (x402 cross-compat)

case class Money(minorUnits: Long, currency: Currency):
  def + (other: Money): Money              // throws CurrencyMismatch if currencies differ
  def - (other: Money): Money
  def * (factor: BigDecimal, mode: RoundingMode = RoundingMode.HALF_EVEN): Money
  def / (divisor: BigDecimal, mode: RoundingMode = RoundingMode.HALF_EVEN): Money
  def unary_- : Money                      // negate
  def toDecimal: BigDecimal                // minorUnits / 10^minorUnitsPower(currency)
  def format(locale: java.util.Locale): String
  def <  (other: Money): Boolean           // throws CurrencyMismatch
  def <= (other: Money): Boolean
  def >  (other: Money): Boolean
  def >= (other: Money): Boolean

object Money:
  def apply(amount: BigDecimal, currency: Currency): Money   // rounds via HALF_EVEN
  def zero(currency: Currency): Money
  def allocate(total: Money, ratios: List[BigDecimal]): List[Money]  // distributes remainder
```

**Storage**: `Long` minor units avoids `BigDecimal` overhead for hot paths.  64-bit max
≈ ±$92 quadrillion in USD — sufficient for any commerce use case.  Crypto codes are
accepted for x402 cross-compat (see `docs/x402.md`).

**Minor-unit table**: USD/EUR/GBP/CHF = 2 (cents); JPY/KRW/VND = 0; KWD/BHD/OMR = 3;
BTC = 8 (satoshis); ETH = 18 (wei, but Long covers ≤ 9.2 ETH; `BigMoney` for larger
crypto amounts is deferred to a future phase).

**Arithmetic**: `+` / `-` across different currencies throws `CurrencyMismatch`.
`*` / `/` accept any numeric factor + explicit `RoundingMode` (default `HALF_EVEN`
= IEEE 754 banker's rounding — minimises cumulative bias for tax tables and proration).
`allocate` distributes a total across N ratios without dropping remainder pennies
(allocates any modular excess to the first bucket(s)).

**JSON**: emits `{"amount":"19.99","currency":"USD"}` — the decimal string representation
interops with Stripe / PayPal / Adyen (all return strings, not numbers).  Parser
accepts both `"19.99"` and `19.99` as value.

**Back-compat with `Amount`**: `payments/payment-request/src/main/scala/scalascript/payment/PaymentTypes.scala:6`
keeps `Amount` as a `@deprecated` alias.  New code uses `Money`; existing `Amount`
callsites emit a compile warning.

### 3.2 PaymentProvider SPI

New module `runtime/std/payments-plugin/`, mirroring the 2-file + META-INF layout
of `runtime/std/payment-request-plugin/`:

```scala
// runtime/std/payments-plugin/src/main/scala/scalascript/compiler/plugin/payments/PaymentProvider.scala

trait PaymentProvider:
  def id:           String                // "stripe" | "paypal" | "braintree" | "adyen" | "square"
  def displayName:  String
  def spiVersion:   String
  def capabilities: PaymentCapabilities
  def mode:         PaymentMode          // Test | Live

  // ── Group 1: PaymentIntent — one-time charges ────────────────────────────
  def createIntent(req: CreateIntentRequest):   IO[PaymentIntent] ! Payment
  def confirmIntent(id: IntentId, method: PaymentMethod): IO[PaymentIntent] ! Payment
  def captureIntent(id: IntentId, amount: Option[Money] = None): IO[PaymentIntent] ! Payment
  def voidIntent(id: IntentId):                 IO[Unit] ! Payment

  // ── Group 2: Customer + Vault ────────────────────────────────────────────
  def createCustomer(req: CreateCustomerRequest): IO[Customer] ! Payment
  def attachMethod(customerId: CustomerId, method: PaymentMethod): IO[StoredMethod] ! Payment
  def detachMethod(vaultId: VaultId):              IO[Unit] ! Payment
  def listMethods(customerId: CustomerId):         IO[Stream[StoredMethod]] ! Payment

  // ── Group 3: Subscriptions ───────────────────────────────────────────────
  def createPlan(req: CreatePlanRequest):           IO[Plan] ! Payment
  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): IO[Subscription] ! Payment
  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): IO[Subscription] ! Payment
  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): IO[Subscription] ! Payment

  // ── Group 4: Refunds + Disputes ──────────────────────────────────────────
  def refund(req: RefundRequest):                IO[Refund] ! Payment
  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): IO[Dispute] ! Payment

  // ── Group 5: Webhook handling ────────────────────────────────────────────
  def webhookReceiver: WebhookReceiver[PaymentEvent]
```

**`PaymentCapabilities`** — feature flag record:

```scala
case class PaymentCapabilities(
  supportsSubscriptions:     Boolean = false,
  supportsSCA:               Boolean = false,
  supports3DS2:              Boolean = false,
  supportsACH:               Boolean = false,
  supportsSEPA:              Boolean = false,
  supportsApplePay:          Boolean = false,
  supportsGooglePay:         Boolean = false,
  supportsRefunds:           Boolean = false,
  supportsPartialRefunds:    Boolean = false,
  supportsDisputes:          Boolean = false,
  supportsConnectedAccounts: Boolean = false,
  supportsMultiCurrency:     Boolean = false,
  supportsMandates:          Boolean = false,
)
```

Adapter declares its true flags.  Callers may call `provider.capabilities.supportsSCA` before
invoking SCA-specific flows to produce a useful error instead of a PSP 400.

**`PaymentMode`**: `enum PaymentMode { case Test, Live }`.  Adapter checks that `id` /
secret-key prefix match (`sk_test_*` = Test, `sk_live_*` = Live for Stripe).  Mismatch →
fatal error before any API call.

**ServiceLoader**: `META-INF/services/scalascript.payments.PaymentProvider`.

**Provider selection**: `PaymentProvider.named("stripe")` — reads `${env:STRIPE_SECRET_KEY}`
via `backend/config/src/main/scala/scalascript/config/SubstitutionEngine.scala` resolvers
(`${vault:…}` / `${sops:…}` / `${env:…}` / `${file:…}` all work unchanged).

### 3.3 PaymentIntent state machine

```scala
enum PaymentIntent:
  case RequiresPaymentMethod(id: IntentId, amount: Money, currency: Currency, metadata: Map[String, String])
  case RequiresConfirmation(id: IntentId, amount: Money, method: PaymentMethod)
  case RequiresAction(id: IntentId, amount: Money, action: SCAChallenge)
  case Processing(id: IntentId, amount: Money)
  case Succeeded(id: IntentId, amount: Money, charge: Charge)
  case Canceled(id: IntentId, reason: CancelReason)
  case Failed(id: IntentId, error: PaymentError, retryable: Boolean)
```

**`SCAChallenge`** — 3DS2 / PSD2 strong-customer-authentication challenge payload:

```scala
case class SCAChallenge(
  provider:     String,       // "stripe" | "adyen" | …
  redirectUrl:  String,       // issuer's 3DS2 challenge URL — frontend redirects here
  returnUrl:    String,       // merchant's confirmation endpoint — issuer redirects back here
  fingerprint:  String,       // opaque token for backend to re-confirm intent after challenge
)
```

### 3.4 Supporting types

```scala
// Recurring billing
case class Plan(id: PlanId, amount: Money, interval: BillingInterval,
                trialPeriodDays: Option[Int] = None, metadata: Map[String, String] = Map.empty)

enum BillingInterval:
  case Daily(count: Int = 1)
  case Weekly(count: Int = 1)
  case Monthly(count: Int = 1)
  case Yearly(count: Int = 1)

enum SubscriptionStatus:
  case Trialing, Active, PastDue, Canceled, Unpaid, Paused

case class Subscription(id: SubscriptionId, customerId: CustomerId, planId: PlanId,
                        status: SubscriptionStatus, currentPeriodEnd: java.time.Instant,
                        cancelAtPeriodEnd: Boolean, trialEnd: Option[java.time.Instant])

enum ProrationMode:
  case CreateProration   // default — generates a proration credit/invoice immediately
  case AlwaysInvoice     // always create a new invoice for the proration
  case None              // no proration; change takes effect at next period

// Refunds + Disputes
case class Refund(id: RefundId, intentId: IntentId, amount: Money,
                  reason: RefundReason, status: RefundStatus)

enum RefundStatus:
  case Pending, Succeeded, Failed, Canceled

enum RefundReason:
  case Duplicate, Fraudulent, RequestedByCustomer, Other(description: String)

case class Dispute(id: DisputeId, intentId: IntentId, amount: Money,
                   reason: DisputeReason, status: DisputeStatus,
                   dueDate: java.time.Instant, evidence: Option[DisputeEvidence])

enum DisputeStatus:
  case NeedsResponse, UnderReview, Won, Lost, WarningClosed

case class DisputeEvidence(
  customerCommunication: Option[String] = None,
  receipt:               Option[String] = None,
  shippingDocumentation: Option[String] = None,
  uncategorizedText:     Option[String] = None,
  serviceDocumentation:  Option[String] = None,
)

// Vault
case class Customer(id: CustomerId, email: String, name: Option[String],
                    metadata: Map[String, String])

case class StoredMethod(vaultId: VaultId, last4: String, brand: String,
                        expMonth: String, expYear: String, funding: String,
                        isDefault: Boolean)

case class Mandate(id: MandateId, status: MandateStatus, mandateType: MandateType)
enum MandateStatus { case Active, Inactive, Pending }
enum MandateType   { case MultiUse, SingleUse }

// Charges
case class Charge(id: ChargeId, intentId: IntentId, amount: Money, paid: Boolean,
                  receiptUrl: Option[String], balanceTransactionId: Option[String])

// Error hierarchy
sealed class PaymentError(msg: String) extends RuntimeException(msg)
case class CardDeclined(code: String, message: String, retryPolicy: RetryPolicy) extends PaymentError(message)
case class InsufficientFunds(message: String) extends PaymentError(message)
case class AuthenticationRequired(challenge: SCAChallenge) extends PaymentError("3DS2 challenge required")
case class InvalidPaymentMethod(message: String) extends PaymentError(message)
case class RateLimitExceeded(retryAfter: Option[java.time.Duration]) extends PaymentError("rate limited")
case class ProviderUnreachable(retryAfter: Option[java.time.Duration]) extends PaymentError("provider unreachable")
case class PermanentProviderError(code: String, body: String) extends PaymentError(s"provider error: $code")
case class DuplicateRequest(originalId: String) extends PaymentError("duplicate idempotency key with different body")

enum RetryPolicy:
  case RetryNow
  case RetryAfterAction    // SCA or card update needed
  case DoNotRetry

// Payment effect row (v1.12 algebraic effects)
type Payment  // declared as effect row — operations discharged by PaymentProvider adapter
              // or by MockProvider test handler
```

### 3.5 Idempotency key

```scala
opaque type IdempotencyKey = String
object IdempotencyKey:
  def apply(key: String): IdempotencyKey = key
  def fromRequest(req: Any): IdempotencyKey  // SHA-256 of canonicalized request body

def withIdempotencyKey[A](key: IdempotencyKey)(block: => IO[A] ! Payment): IO[A] ! Payment
```

Threaded via implicit context (Loom virtual-thread `ScopedValue` on JVM; `CoroutineLocal`
on the ScalaScript runtime).  Adapters read it and pass to the PSP API header / field.

### 3.6 Feature enum extension

`runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala:37` gains:

```scala
case Payments  // runtime/std/payments-plugin/ — PaymentProvider SPI (fiat PSPs)
```

alongside existing `case PaymentRequest`.

---

## 4. Money type — full design

**Why `Long` and not `BigDecimal`**: money amounts in commerce are almost always
small integers (cents).  `Long` arithmetic is JIT-compiled to native ops; `BigDecimal`
is heap-allocated and slower by 10-50x.  The 64-bit range covers ±$92 quadrillion —
enough for the largest GDP in history, times 100.

**Minor-unit table** (authoritative for all adapters):

| Currency codes | Minor-unit power | Example: 1.00 unit = N minor units |
|----------------|-----------------|-------------------------------------|
| USD, EUR, GBP, CHF, AUD, CAD, ... | 2 | 100 (cents) |
| JPY, KRW, VND, BIF, ... | 0 | 1 (no subunit) |
| KWD, BHD, OMR, TND | 3 | 1000 (fils/baisa/piastres) |
| BTC | 8 | 100_000_000 (satoshis) |
| ETH | 18 | (use BigMoney for > 9.2 ETH) |

**Allocation**: splitting a total across N proportional buckets without losing a
cent is a classic problem.  `Money.allocate(total, List(1, 1, 1))` distributes
`$1.00 USD` as `[34¢, 33¢, 33¢]`, not `[33¢, 33¢, 33¢]` + uncounted 1¢ leftover.
The algorithm distributes remainder cents one-per-bucket starting from the largest
remainder.  Used by proration engines, marketplace splits, and tax rounding.

**Banker's rounding (`HALF_EVEN`)**: the IEEE 754 default.  `0.5` rounds to `0` (even),
`1.5` rounds to `2` (even).  Minimises cumulative bias in large tables — critical for
correct VAT/GST computation over many line items, and for proration credits.

**Currency conversion**: `Money +` across currencies throws `CurrencyMismatch` at runtime.
Use `FxProvider` / `FxMoneyConverter` (§FxProvider) for cross-currency conversion.

**JSON encoding**: PSPs universally use decimal strings — `"19.99"` not `19.99` — because
IEEE 754 `double` cannot represent `0.1 + 0.2` exactly.  `Money.toJson` emits
`{"amount":"19.99","currency":"USD"}`.  `Money.fromJson` accepts both string and number
forms for robustness.

---

## 5. Webhook primitive — full design

Every PSP delivers lifecycle events asynchronously via HTTP POST to a merchant-registered
URL.  The merchant must: (a) verify the signature to reject forged events, (b) return
quickly (< 5s) so the PSP marks delivery successful, (c) process idempotently because
PSPs retry on 5xx, (d) protect against replay.

**`WebhookReceiver[E]` SPI** lives in `payments/webhook/`:

```scala
trait WebhookReceiver[E]:
  def verify(req: HttpRequest, secret: Secret): Either[WebhookError, E]
  def idempotencyKey(e: E): String
  def handle(e: E)(handler: PartialFunction[E, IO[Unit] ! Payment]): IO[HttpResponse] ! Payment
```

`handle` wraps the `handler` pf:
1. Computes `idempotencyKey(e)` and queries `SeenKeyStore.wasSeen(key)`.
2. If already seen: return `200 OK` immediately (idempotent delivery).
3. If not seen: invoke `handler(e)`.  On success: `markSeen(key, expiry = 30.days)`, return
   `200 OK`.  On failure: return `500 Internal Server Error` — PSP will retry.
4. On `verify` failure: return `400 Bad Request`.

**Signature verification** (per-PSP):

| PSP | Algorithm | Header(s) |
|-----|-----------|-----------|
| Stripe | HMAC-SHA256 over raw body + `t=<timestamp>` | `Stripe-Signature` |
| PayPal | RSA-SHA256 over `transmission_id + timestamp + webhook_id + body_crc32` | `PAYPAL-TRANSMISSION-SIG`, `PAYPAL-TRANSMISSION-ID`, `PAYPAL-CERT-URL` |
| Adyen | HMAC-SHA256 over notification fields (not raw body) | `X-Adyen-Hmac-Signature` |
| Square | HMAC-SHA1 over `notification_url + raw_body` | `x-square-hmacsha1-signature` |

Timestamp tolerance: ±5 minutes by default (configurable via `WebhookConfig`).
Events outside this window are rejected even if the HMAC is valid — prevents replay.

**`SeenKeyStore` SPI**:

```scala
trait SeenKeyStore:
  def wasSeen(key: String): IO[Boolean]
  def markSeen(key: String, expiry: java.time.Duration): IO[Unit]
```

Default impl: in-memory `ConcurrentHashMap` — correct for single-instance dev.
Cluster-aware impls (Redis-backed, Postgres-backed) ship in v1.53.7.

**Routing**: `Route("POST", "/webhooks/stripe", stripe.webhookReceiver)` — plugs into
the existing v1.46 typed-route system (`lang/core/src/main/scala/scalascript/interpreter/TypedHandlerWrapper.scala`).
No new HTTP server changes needed.

**`PaymentEvent` union** — the typed event envelope each adapter's
`webhookReceiver.verify` returns:

```scala
enum PaymentEvent:
  case PaymentIntentSucceeded(intent: PaymentIntent)
  case PaymentIntentFailed(intent: PaymentIntent, error: PaymentError)
  case ChargeRefunded(refund: Refund)
  case InvoicePaymentSucceeded(invoiceId: String, amount: Money, subscriptionId: SubscriptionId)
  case InvoicePaymentFailed(invoiceId: String, subscriptionId: SubscriptionId, attemptsRemaining: Int)
  case SubscriptionUpdated(subscription: Subscription)
  case SubscriptionCanceled(subscription: Subscription)
  case DisputeCreated(dispute: Dispute)
  case DisputeEvidenceRequired(dispute: Dispute)
  case DisputeUpdated(dispute: Dispute)
  case ManualReviewRequired(intentId: IntentId, reason: String)
```

---

## 6. Idempotency

Every state-changing PSP API call must carry an idempotency key.

| PSP | Mechanism |
|-----|-----------|
| Stripe | `Idempotency-Key` HTTP header; 24h window |
| PayPal | `PayPal-Request-Id` header; 72h window |
| Braintree | `idempotencyKey` field in request body |
| Adyen | `reference` field in request body (also serves as order reference) |
| Square | `idempotency_key` field in request body; 48h window |

**Caller-supplied** (preferred): `withIdempotencyKey(IdempotencyKey("order-123-charge-1")) { provider.createIntent(...) }`.

**Auto-derived** (fallback): `IdempotencyKey.fromRequest(req)` computes
`SHA-256(canonical(endpoint + body))`.  Stable across retries of the same request;
unique across distinct requests.  Adapters log a debug warning when the auto-derive path is taken.

**In-process retry**: the outbound HTTP wrapper inside each adapter retries on
`ProviderUnreachable` with exponential backoff:
- 3 attempts: `100ms / 500ms / 2000ms`.
- **Same idempotency key** on every retry — safe by PSP contract (server returns the
  original response for a duplicate key within the window).
- `PermanentProviderError` and `CardDeclined(retryPolicy = DoNotRetry)` are NOT retried.

**Server-side dedup**: if a request arrives with the same idempotency key but a different
body hash, the adapter throws `DuplicateRequest(originalId)` before sending to the PSP.

---

## 7. PaymentIntent lifecycle

```
[RequiresPaymentMethod]
       │
       │  attachMethod / createIntent with method
       ▼
[RequiresConfirmation]
       │
       │  confirmIntent(method)
       ▼
[RequiresAction] ──── SCA/3DS2 challenge ────┐
       │                                      │  user completes challenge in browser
       │  (no SCA / auto-confirm)             │
       ▼                                      │
[Processing] ◄───────────────────────────────┘
       │
       ├──▶ [Succeeded(charge)]
       ├──▶ [Failed(error, retryable)] ──▶ reset to RequiresPaymentMethod (if retryable)
       └──▶ [Canceled(reason)]
```

**SCA / 3DS2 flow** (PSD2 / EU / UK / AU required for online card payments):

1. Backend calls `confirmIntent(id, method)`.
2. Adapter returns `RequiresAction(SCAChallenge(redirectUrl, returnUrl, fingerprint))`.
3. Backend serialises `SCAChallenge` and sends to frontend.
4. Frontend redirects user to `challenge.redirectUrl` (issuer's 3DS2 page).
5. User completes authentication at issuer.
6. Issuer redirects browser to `challenge.returnUrl` (merchant's endpoint, e.g. `/payment/confirm`).
7. Backend handler re-calls `confirmIntent` (with fingerprint from query param).
8. Adapter returns `Succeeded` or `Failed`.

The round-trip involves two HTTP requests and a browser redirect.  The `SCAChallenge`
type carries everything the frontend needs without leaking PSP internals.

**Authorize + capture flow** (marketplace / delayed-capture pattern):

- `createIntent(req.copy(captureMethod = CaptureMethod.Manual))` — creates a
  `requires_capture` intent that holds funds but does not collect them.  Auth-hold
  window is PSP-dependent (Stripe: 7 days; some PSPs: 30 days).
- `captureIntent(id, amount = Some(partialAmount))` — actually collects funds.
  Useful for: charge after shipment, amount adjustments (e.g. tip), marketplace escrow.
- `voidIntent(id)` — releases the hold without charging.

**Off-session future-use**: `CreateIntentRequest(setupFutureUsage = OffSession)` informs
the PSP that the customer is granting merchant-initiated transaction (MIT) permission for
future charges without the customer being present.  Required for SEPA mandates and
subscription initial-setup intents that don't charge immediately.

---

## 8. Subscription / recurring billing

### 8.1 Plan and lifecycle

```scala
case class CreatePlanRequest(
  amount:          Money,
  interval:        BillingInterval,
  trialPeriodDays: Option[Int]          = None,
  metadata:        Map[String, String]  = Map.empty,
)
```

`subscribe(customerId, planId, SubscribeOpts(...))` creates the subscription.  After
creation:

```
[Trialing] ──(trial ends)──▶ [Active] ──(charge fails)──▶ [PastDue]
                                                                │
                                                       dunning retries
                                                                │
                                               (max retries)──▶[Unpaid]
                                               (cancellation)──▶[Canceled]
[Active] ──(cancelSubscription)──────────────────────────────▶[Canceled]
```

### 8.2 Proration

When a plan changes mid-period, the PSP computes prorated credit and debit.  Example:

- Customer on $30/month plan, 15 days into a 30-day period.
- Upgrade to $60/month plan.
- With `ProrationMode.CreateProration` (Stripe default): PSP creates a $15 credit for
  unused old-plan time and a $30 charge for remaining new-plan time; net = $15 due
  immediately.
- With `ProrationMode.AlwaysInvoice`: generates a full invoice for the proration amount.
- With `ProrationMode.None`: no proration; change takes effect at the next renewal.

### 8.3 Dunning

Dunning = PSP's automatic retry schedule for failed recurring charges.  The adapter does
not implement dunning — it's a PSP dashboard setting.  Observed typical schedules:

| PSP | Retry schedule |
|-----|---------------|
| Stripe | Days 5, 7, 9, 12 after first failure (configurable) |
| PayPal | Days 1, 3, 5 |
| Adyen | configurable; default 3 retries over 7 days |

On each retry failure the PSP fires `InvoicePaymentFailed` with `attemptsRemaining`.
On exhaustion: subscription moves to `Unpaid` or `Canceled` (PSP-specific default).

### 8.4 Invoicing

Each successful recurring charge generates an `Invoice` at the PSP level with line items
and optional PSP-computed tax.  The adapter exposes `InvoicePaymentSucceeded` in the
webhook event union.  Full invoice retrieval (line items, tax, PDF URL) deferred to a
per-adapter phase.

---

## 9. Refunds, disputes, chargebacks

### 9.1 Refunds

```scala
case class RefundRequest(
  intentId: IntentId,
  amount:   Option[Money] = None,    // None = full refund
  reason:   RefundReason,
)
```

Multiple partial refunds up to the original charge total are supported on all four PSPs.
`refund()` returns the `Refund` record immediately; the actual settlement is async and
arrives via `ChargeRefunded` webhook event.

### 9.2 Dispute lifecycle

When a buyer's bank initiates a chargeback, the PSP fires `DisputeCreated`.  Funds are
immediately debited from the merchant's balance (Stripe, Square).  Some PSPs (Adyen)
hold in reserve instead.

```
[NeedsResponse] ──(submit evidence)──▶ [UnderReview] ──(bank decides)──▶ [Won]
                                                                         └──▶ [Lost]
[NeedsResponse] ──(deadline passes with no evidence)──▶ [Lost] (auto)
[NeedsResponse] ──(pre-dispute inquiry resolved)──────▶ [WarningClosed]
```

**Response deadline**: typically 7-21 days from creation depending on PSP and card
network.  `Dispute.dueDate` carries the exact deadline.  Missing the deadline results in
automatic `Lost`.

**Evidence submission**: `submitDisputeEvidence(disputeId, DisputeEvidence(...))`.  Typed
fields map to PSP-specific evidence formats.

### 9.3 Representment

PSP-specific premium fraud tools (Stripe Radar Chargeback Protection, Adyen
RevenueProtect, Braintree Kount integration) can automatically handle disputes on the
merchant's behalf.  The adapter exposes an `autoRepresentment: Boolean` capability flag.
Actual fraud-tool configuration lives in the PSP dashboard — out of scope for the SPI.

---

## 10. Stored payment methods, vault, tokenization

### 10.1 PCI scope boundary

**ScalaScript apps never handle raw PANs.**

Card collection happens exclusively via PSP-provided frontend SDK (Stripe Elements,
Braintree Hosted Fields, Adyen Drop-in, Square Web Payments SDK).  The SDK tokenizes the
card in the browser before any data leaves the customer's device.  The ScalaScript
backend receives only a one-time token (`pm_xxx` / `nonce` / `pmt_xxx` / `nonce_xxx`).
This keeps the merchant at **PCI-DSS SAQ A** scope — the lowest possible.

**In `PaymentMethod.Card`**: `rawDetails` variant (containing a `CardDetails` struct from
`payments/payment-request/src/main/scala/scalascript/payment/PaymentTypes.scala:50`)
exists *only* for testing with test card numbers like `4242 4242 4242 4242`.  Adapters
throw `CardRawDetailsNotAllowed` in `PaymentMode.Live`.

### 10.2 Customer and stored methods

```scala
case class CreateCustomerRequest(
  email:    String,
  name:     Option[String]         = None,
  metadata: Map[String, String]    = Map.empty,
)
```

`attachMethod(customerId, method)` sends the one-time token to the PSP and returns a
`StoredMethod` (vault token + masked card details).  The PSP stores the PAN and
constructs a durable vault token.

`listMethods(customerId)` returns a `Stream[StoredMethod]` (v1.51 `Stream[T]` from
`runtime/std/streams-plugin/` when v1.51.1 lands) for paginated retrieval.

`detachMethod(vaultId)` permanently removes the stored method from the PSP vault.

### 10.3 Network Tokens

Stripe, Adyen, and Braintree support **Network Tokens**: PSP-managed cryptogram-backed
alternatives to raw PANs that improve authorisation rates and reduce chargeback liability.
Network Token generation is transparent to the adapter — the PSP handles it internally.
The `StoredMethod` response may include `tokenizationMethod: "network_token"` for adapters
that expose it.

### 10.4 Mandates

For SEPA Direct Debit, ACH, and BACS payment methods, a **mandate** is the customer's
signed authorization to debit their account recurrently.  Required for MIT (merchant-initiated
transactions) without the customer present.

`Mandate(id, status, mandateType)` is returned in the `attachMethod` response for
mandate-capable methods.  `status: Active` means the PSP can initiate future debits.
Full mandate management (SEPA mandate download, ACH authorization text, BACS paper mandate)
is deferred to v1.53.5 and the bank-rails spec (v1.54+).

---

## 11. PSP adapter contract (per-provider taxonomy)

Per-provider subsections define the **adapter interface contract** — not per-endpoint
detail (which lands in the phase docs for each adapter).

### 11.1 Stripe

The canonical reference adapter.  Most complete PSP surface.

| Dimension | Detail |
|-----------|--------|
| Auth | Secret key `sk_test_*` / `sk_live_*` via `${env:STRIPE_SECRET_KEY}` |
| Webhook verification | HMAC-SHA256 over raw body + `Stripe-Signature` header |
| Idempotency | `Idempotency-Key` HTTP header; 24h dedup window |
| PCI | Stripe Elements (hosted fields); `pm_xxx` token |
| SCA | 3DS2 via `requires_action` + `next_action.redirect_to_url` |
| Subscriptions | Stripe Subscriptions + Invoicing; full proration API |
| Disputes | `Dispute` resource; evidence upload via file API |
| Network Tokens | Stripe Network Tokens (automatic via Adaptive Acceptance) |
| Capabilities all | Yes — all 13 `PaymentCapabilities` flags true |

`capabilities.supportsACH = true` (Stripe ACH bank debit via plaid), but ACH is deferred
to the bank-rails milestone (v1.54+) per user decision.

### 11.2 PayPal / Braintree

Two adapters sharing an owner.

**PayPal Checkout**: PayPal-balance flow (buyer pays with PayPal account).
Auth via OAuth2 client-credentials (`client_id` + `secret` → bearer token, expires 8h).
Webhook verification via RSA-SHA256 + cert chain (`PAYPAL-CERT-URL` header, PSP fetches
cert; adapter verifies signature against fetched cert).
No raw card collection on PayPal Checkout path.  `supportsGooglePay = true` (PayPal
Checkout can handle Google Pay via PayPal JS SDK).

**Braintree**: card-focused.  Same PSP, different API surface.
Auth via `merchant_id` + `public_key` + `private_key`.
Webhook HMAC-SHA1 over raw body + `bt-signature` / `bt-payload` headers.
PCI via Hosted Fields (`nonce` token).
`supportsApplePay = true`, `supportsGooglePay = true`.
Mandates: not supported natively.

### 11.3 Adyen / Checkout.com

**Adyen**: enterprise multi-currency.
Auth via `X-API-Key` header.  No OAuth.
PCI via Adyen Drop-in / Web Components (`encryptedCardNumber`, `encryptedExpiryMonth`,
`encryptedCvc` fields).
Webhook HMAC-SHA256 via `X-Adyen-Hmac-Signature`; signed over notification fields
(not raw body).
`additionalData` map in Adyen responses carries risk scores, 3DS2 exemptions, and
network token details — exposed via `provider_specific: Map[String, Any]` escape hatch.
`supportsConnectedAccounts = true` (Adyen MarketPay).

**Checkout.com**: similar enterprise surface.
Auth via `sk_xxx` secret key.
PCI via Frames hosted fields (`token_xxx`).
Webhook HMAC-SHA256 over raw body + `Cko-Signature` header.
Slightly simpler API than Adyen; capabilities matrix comparable.

### 11.4 Square

US SMB focus.  Simpler API; card-only.

Auth via `Bearer <access_token>` from `${env:SQUARE_ACCESS_TOKEN}`.
PCI via Square Web Payments SDK (`nonce_xxx` token).
Webhook HMAC-SHA1 over `notification_url + raw_body` signed against `${env:SQUARE_WEBHOOK_SIGNATURE_KEY}`.
No native SCA (`supportsSCA = false`).  No mandates.  Subscriptions supported.
Disputes accessible via `GET /v2/disputes` + evidence upload.

---

## 12. Diagnostics

Error messages follow the format established in `docs/deploy.md §12`.

**D1 — Unknown provider id**
```
error: unknown payment provider "braintreee"
  available providers: stripe, paypal, braintree, adyen, square
  nearest match: braintree
```

**D2 — Missing PSP credential**
```
error: STRIPE_SECRET_KEY is not set
  providers require a secret key to authenticate API calls.
  set it with one of:
    ${env:STRIPE_SECRET_KEY}
    ${vault:secret/stripe/prod}
    ${sops:stripe.yaml#secret_key}
  see: docs/config-system.md §3 (secret resolvers)
```

**D3 — SCA challenge required**
```
PaymentIntent is in state RequiresAction.
  the customer must complete a 3DS2 challenge:
    redirectUrl: https://hooks.stripe.com/3d_secure_2/...
    returnUrl:   https://yourapp.com/payment/confirm
  1. send the SCAChallenge to your frontend
  2. frontend redirects the user to redirectUrl
  3. after challenge, issuer redirects to returnUrl
  4. call confirmIntent(fingerprint) on your returnUrl handler
```

**D4 — Card declined**
```
error: card_declined [do_not_retry]
  the card was declined by the issuer.
  decline code: insufficient_funds
  suggested action: ask the customer to use a different payment method
                    or contact their bank
```

**D5 — Idempotency-key body mismatch**
```
error: DuplicateRequest — idempotency key "order-123-charge-1" was already used
  with a different request body
  original_intent_id: pi_3Pxxxxx
  to retry the original request use the same key with the same body
  to make a new request use a different idempotency key
```

**D6 — Webhook signature mismatch**
```
error: webhook signature verification failed
  expected: Stripe-Signature header (HMAC-SHA256)
  received: header missing
  make sure:
    1. you are reading the raw request body, not a parsed JSON body
    2. STRIPE_WEBHOOK_SECRET is set correctly (whsec_...)
    3. the webhook is registered in Stripe Dashboard → Webhooks
```

**D7 — Webhook replay detected**
```
error: webhook event evt_3Pxxxxx already processed
  first received: 2026-05-27T10:15:00Z
  idempotency key: evt_3Pxxxxx
  returning 200 OK (safe to retry delivery)
```

**D8 — Currency mismatch in Money arithmetic**
```
error: CurrencyMismatch in Money.+
  left:  Money(1999, USD)
  right: Money(1700, EUR)
  cannot add amounts in different currencies
  if you need cross-currency arithmetic, use an FxProvider (v1.54+)
```

**D9 — Dispute deadline approaching**
```
warning: dispute disp_xxx requires a response by 2026-06-03T23:59:59Z (7 days)
  amount: $49.99 USD
  reason: product_not_received
  to respond:
    provider.submitDisputeEvidence(disputeId, DisputeEvidence(
      receipt = Some("order-456 shipped 2026-05-20 via UPS 1Z..."),
      shippingDocumentation = Some("https://tracking.ups.com/..."),
    ))
```

**D10 — PSP rate-limited**
```
error: RateLimitExceeded
  retry after: 2026-05-27T10:16:02Z (62 seconds)
  the in-process retry handler will back off and retry automatically
  if retries are exhausted, the request fails with ProviderUnreachable
```

**D11 — Test-mode key used in Live environment**
```
error: PaymentMode mismatch
  provider mode:   Test  (key prefix: sk_test_...)
  runtime env:     production (process.env.RUNTIME_ENV = "production")
  to fix: set STRIPE_SECRET_KEY to a Live key (sk_live_...) for production
          or set mode = PaymentMode.Test explicitly if running in test
```

**D12 — Stored method card expired**
```
error: CardDeclined [do_not_retry]
  decline code: expired_card
  stored method: pm_xxx (Visa •••• 4242, exp 01/26)
  the stored card has expired. ask the customer to:
    1. add a new payment method (provider.attachMethod)
    2. optionally detach the expired card (provider.detachMethod)
```

---

## 13. Non-goals for v1.53

- **Per-provider adapter implementations** — Stripe / PayPal+Braintree / Adyen+Checkout.com /
  Square adapters are all deferred to v1.53.1 → v1.53.4.  v1.53 defines the SPI contract only.
- **Bank rails** — SEPA Direct Debit, ACH, Pix, FedNow, UPI, BACS — deferred to **v1.54+**
  as a separate spec and milestone.  The `PaymentProvider` SPI is designed to accommodate
  async-settlement modes so v1.54 adapters will slot in without breaking the 14-method
  trait contract.
- **Currency conversion / FX** — `FxProvider` SPI landed in v1.57 (`payments/fx/`,
  `payments/fx-ecb/`, `payments/fx-openexchangerates/`).  `Money +` across currencies
  still throws; use `FxMoneyConverter` for explicit conversion.
- **Tax calculation** — `TaxProvider` SPI (Stripe Tax / Avalara / TaxJar) deferred.
- **Marketplace / connected accounts / split payments** — Stripe Connect, Braintree
  Marketplace, Adyen MarketPay deferred.  `supportsConnectedAccounts` capability flag
  exists so future adapters can advertise support.
- **In-person / terminal payments** — Stripe Terminal / Square Reader / SumUp deferred.
  v1.53 is online only.
- **BNPL** — Klarna / Afterpay / Affirm are accessible as `PaymentMethod` variants via
  Stripe / Adyen; a standalone BNPL adapter is deferred.
- **Fraud / Radar / RevenueProtect** — PSP-specific fraud tools are advertised via
  capability flag; a dedicated `FraudProvider` SPI is deferred.
- **`ssc payments` CLI** — payments are runtime concerns, not build-time.
  A `payments:` manifest block and CLI are deferred to v1.53.x if user demand emerges.
- **Effect-row decomposition** into fine-grained `Charging | Refunding | Subscribing`
  sub-effects — deferred to v1.53.6.

---

## 14. Future work

- **Per-provider plugin packages**: `runtime/std/payments-stripe/`,
  `runtime/std/payments-paypal/`, `runtime/std/payments-adyen/`,
  `runtime/std/payments-square/` — each its own sbt subproject and release artifact.
- **v1.54 — Bank rails spec**: SEPA Direct Debit, ACH, Pix, FedNow, UPI — async
  settlement, mandate management, return-code handling, AML/KYC notes.
- **FX / multi-currency**: `FxProvider` SPI landed (v1.57).  Adapters: `EcbFxProvider`
  (ECB daily reference rates, EUR base) and `OerFxProvider` (Open Exchange Rates API v6,
  USD base).  `FxMoneyConverter` utility for batch conversion.
  Module layout: `payments/fx/` (SPI), `payments/fx-ecb/`, `payments/fx-openexchangerates/`.

### FxProvider

**SPI module**: `payments/fx/`  (`name := "payments-fx"`)

```scala
trait FxProvider:
  def id: String
  def displayName: String
  def getRate(from: Currency, to: Currency)(using ExecutionContext): Future[FxRate]
  def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money]
  def getRates(pairs: Set[CurrencyPair])(using ExecutionContext): Future[Map[CurrencyPair, FxRate]]

case class FxRate(from: Currency, to: Currency, rate: BigDecimal, mid: BigDecimal,
                  bid: Option[BigDecimal], ask: Option[BigDecimal], timestamp: Instant)
case class CurrencyPair(from: Currency, to: Currency)

sealed abstract class FxError(message: String, cause: Throwable = null) extends RuntimeException
object FxError:
  final case class RateUnavailable(from: Currency, to: Currency) extends FxError(...)
  final case class FxProviderError(message: String, cause: Throwable = null) extends FxError(...)

class FxMoneyConverter(provider: FxProvider):
  def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money]
  def convertAll(moneys: List[Money], to: Currency)(using ExecutionContext): Future[List[Money]]
```

**Adapters:**

| Module | Class | Base | Cache TTL | Scheme |
|--------|-------|------|-----------|--------|
| `payments/fx-ecb/` | `EcbFxProvider` | EUR | 1 hour | `"ecb"` |
| `payments/fx-openexchangerates/` | `OerFxProvider` | USD | 1 hour | `"openexchangerates"` / `"oer"` |

Cross-rates are derived on the fly: `rate(A→B) = rate(BASE→B) / rate(BASE→A)`.

`OerConfig.appId` is read from `OPENEXCHANGERATES_APP_ID` env var (`OerConfig.fromEnv`).
- **Tax**: `TaxProvider` SPI (Stripe Tax, Avalara, TaxJar) + automated tax line items
  in invoices.
- **Marketplace**: `ConnectedAccount` abstraction for Stripe Connect / Braintree
  Marketplace / Adyen MarketPay.
- **Terminal / in-person**: Stripe Terminal / Square hardware reader / SumUp.
- **Crypto-fiat bridge**: unify x402 (`docs/x402.md`) and v1.53 under one `Settle`
  effect — pay-with-crypto for fiat invoices via PSP-managed on-ramp (Stripe
  Crypto Payouts, Coinbase Commerce).
- **PCI DSS audit checklist generator**: `ssc payments audit` emits a checklist
  confirming SAQ A scope based on which adapter and method types are used.
- **Reconciliation**: `ReconcileProvider` SPI to match PSP daily settlement files
  against an internal ledger (Stripe Balance Transactions report, Adyen settlement
  detail report).
- **`BigMoney`**: for ETH (18 minor units) and other high-precision crypto amounts
  that overflow `Long`.
- **Fine-grained effect rows** (v1.53.6): `Payment[T] ! Charging | Refunding | Subscribing`
  decomposition for finer handler dispatch.
- **Saved-card migration tools**: when moving from Stripe to Adyen, vault tokens must be
  migrated via the card network's MDES/VTS token migration program — a future
  `TokenMigrator` SPI.

---

## 15. Examples

Twelve worked snippets land in `examples/traditional-payments.ssc` with v1.53.1
(not v1.53 — spec-only).  Listed here for reference:

**1 — One-time card charge (no SCA):**
```scala
val stripe = PaymentProvider.named("stripe")
val intent = stripe.createIntent(CreateIntentRequest(
  amount   = Money(4999L, Currency("USD")),    // $49.99
  method   = PaymentMethod.Card(pm_xxx),       // token from Stripe Elements
  confirm  = true,
  customer = Some(customerId),
))
intent match
  case Succeeded(_, _, charge) => db.markPaid(orderId, charge.id)
  case Failed(_, err, _)       => showError(err.getMessage)
  case req: RequiresAction     => redirectTo(req.action.redirectUrl)
```

**2 — One-time charge with SCA / 3DS2:**
```scala
route("POST", "/payment/confirm") { req =>
  val fingerprint = req.queryParam("fingerprint")
  val intent = stripe.confirmIntent(intentId, PaymentMethod.Fingerprint(fingerprint))
  intent match
    case Succeeded(_, _, charge) => Response.redirect("/order/success")
    case Failed(_, err, _)       => Response.json(Map("error" -> err.getMessage))
    case _ => Response.status(500)
}
```

**3 — Apple Pay completion (closes `docs/payment-request.md:388`):**
```scala
route("POST", "/payment/apple/process") { req =>
  val token = req.body[ApplePayToken]
  val decrypted = ApplePay.decryptToken(token,
    config.getString("apple-pay.cert-pem"),
    config.getString("apple-pay.key-pem"))
  // Close the placeholder from v1.38:
  val intent = stripe.createIntent(CreateIntentRequest(
    amount  = Money(BigDecimal("49.99"), Currency("USD")),
    method  = PaymentMethod.Card(ApplePayCard(decrypted)),
    confirm = true,
  ))
  Response.json(Map("success" -> intent.isInstanceOf[PaymentIntent.Succeeded]))
}
```

**4 — Subscribe to a recurring plan:**
```scala
val plan = stripe.createPlan(CreatePlanRequest(
  amount   = Money(999L, Currency("USD")),   // $9.99/month
  interval = BillingInterval.Monthly(),
))
val sub = stripe.subscribe(customerId, plan.id, SubscribeOpts(
  trialPeriodDays = Some(14),
  defaultMethod   = Some(vaultId),
))
```

**5 — Upgrade subscription mid-cycle with proration:**
```scala
stripe.changeSubscription(sub.id, premiumPlanId, ProrationMode.CreateProration)
```

**6 — Full and partial refund:**
```scala
// Full refund
stripe.refund(RefundRequest(intentId, amount = None, reason = RefundReason.RequestedByCustomer))
// Partial refund ($5)
stripe.refund(RefundRequest(intentId, amount = Some(Money(500L, Currency("USD"))),
              reason = RefundReason.Duplicate))
```

**7 — Vault customer + stored method:**
```scala
val customer  = stripe.createCustomer(CreateCustomerRequest(email = "user@example.com"))
val stored    = stripe.attachMethod(customer.id, PaymentMethod.Card(pm_xxx))
// Off-session future charge:
val intent = stripe.createIntent(CreateIntentRequest(
  amount    = Money(1999L, Currency("USD")),
  method    = PaymentMethod.SavedMethod(stored.vaultId),
  offSession = true,
))
```

**8 — Webhook handler:**
```scala
route("POST", "/webhooks/stripe", stripe.webhookReceiver.handle(_) {
  case PaymentEvent.PaymentIntentSucceeded(intent) =>
    db.markPaid(intentToOrder(intent.id))
  case PaymentEvent.InvoicePaymentFailed(invoiceId, subId, attemptsLeft) =>
    email.send(subToEmail(subId), s"Payment failed — $attemptsLeft attempts remaining")
  case PaymentEvent.DisputeCreated(dispute) =>
    alertOncall(s"Dispute ${dispute.id} requires response by ${dispute.dueDate}")
})
```

**9 — Dispute evidence submission:**
```scala
stripe.submitDisputeEvidence(dispute.id, DisputeEvidence(
  customerCommunication = Some("Email thread attached"),
  receipt               = Some(s"Order #${orderId} paid 2026-05-20"),
  shippingDocumentation = Some("UPS tracking 1Z...shipped 2026-05-21"),
))
```

**10 — Authorize + delayed capture:**
```scala
val intent = stripe.createIntent(CreateIntentRequest(
  amount        = Money(5000L, Currency("USD")),
  method        = PaymentMethod.Card(pm_xxx),
  captureMethod = CaptureMethod.Manual,
))
// ... ship the item ...
stripe.captureIntent(intent.id, amount = Some(Money(4800L, Currency("USD")))) // actual weight-based cost
```

**11 — Mock provider for tests:**
```scala
// Tests discharge the Payment effect with a MockProvider — no network, no real keys
handler Payment with MockProvider.alwaysSucceed:
  val result = myCheckoutFlow(cart)
  assert(result.isInstanceOf[OrderConfirmed])
```

**12 — Money arithmetic:**
```scala
val subtotal = Money(BigDecimal("49.99"), Currency("USD"))
val tax      = subtotal * BigDecimal("0.2")         // 20% VAT, banker's rounding
val total    = subtotal + tax
val perSeat  = Money.allocate(total, List(1, 1, 1)) // [20.00, 19.99, 19.99] — no lost penny
println(total.format(Locale.US))   // $59.99
```

---

## 16. Implementation phasing (post-v1.53)

### 16.1 v1.53.1 — Plugin scaffolding + Money + PaymentProvider SPI + WebhookReceiver + Stripe adapter

**Largest phase** — all foundational types land together because they co-depend.

Files created / modified:

- `payments/money/` — new `build.sbt` subproject + `Money.scala` + `Currency.scala`.
- `payments/webhook/` — new subproject + `WebhookReceiver.scala` + `SeenKeyStore.scala`.
- `runtime/std/payments-plugin/` — 2-file plugin + META-INF service descriptor
  (`scalascript.backend.spi.Backend`), mirroring `runtime/std/payment-request-plugin/`
  layout exactly.
- `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala` — add
  `case Payments` case.
- `payments/payment-request/.../PaymentTypes.scala:6` — deprecate `Amount`, add
  `type Amount = Money` alias.
- `runtime/std/payments-stripe/` — Stripe-specific adapter implementing all 14 SPI
  methods + `PaymentEvent.Stripe*` webhook events.
- `examples/traditional-payments.ssc` — all 12 worked examples from §15.

Stripe adapter covers: PaymentIntent create/confirm/capture/void, SCA/3DS2 redirect,
Customer create/attach/detach/list, Plan + Subscription CRUD with proration,
Refund + partial-refund, Dispute + evidence upload, Webhook HMAC-SHA256 verify +
idempotency-keyed dispatch.

### 16.2 v1.53.2 — PayPal Checkout + Braintree adapters

Files created:

- `runtime/std/payments-paypal/` — PayPal Checkout adapter (OAuth2 client-cred auth,
  Order API, RSA webhook verify).
- `runtime/std/payments-braintree/` — Braintree adapter (GraphQL API, HMAC-SHA1
  webhook verify, hosted-fields nonce).

PayPal and Braintree may run in parallel with v1.53.3 (independent adapter dirs).

### 16.3 v1.53.3 — Adyen + Checkout.com adapters

Files created:

- `runtime/std/payments-adyen/` — Adyen adapter (X-API-Key auth, Drop-in tokenization,
  HMAC-SHA256 webhook over notification fields, `additionalData` escape hatch).
- `runtime/std/payments-checkout/` — Checkout.com adapter (sk_xxx auth, Frames,
  HMAC-SHA256 over raw body).

### 16.4 v1.53.4 — Square adapter

Files created:

- `runtime/std/payments-square/` — Square adapter (Bearer token auth, Web Payments
  SDK nonce, HMAC-SHA1 webhook).

### 16.5 v1.53.5 — Vault + Mandates + SCA polish

- Cross-PSP mandate model: `Mandate` creation + status tracking in Stripe / Adyen /
  PayPal adapters.
- Off-session subscription charges with correct PSD2 `setup_future_usage` / `mandate`
  data flags.
- Network Token metadata exposed in `StoredMethod` response where PSP provides it.
- SCA exemption request support: `low_value_exemption`, `trusted_listing_exemption`
  flags in `CreateIntentRequest.scaExemptions`.

### 16.6 v1.53.6 — Effect-row decomposition + MockProvider

- `Payment` effect row split into `Charging | Refunding | Subscribing | Vaulting |
  Webhooking` sub-effects for finer handler granularity.
- `MockProvider.scala` — fully in-memory provider with configurable success/failure
  modes, no network required.  Used in ScalaScript's own test suite for the checkout
  examples.

### 16.7 v1.53.7 — Cluster-aware webhook idempotency

- `RedisSeenKeyStore` — Redis-backed idempotency store (reuses existing
  `backend/redis/` Jedis/Lettuce client).
- `PostgresSeenKeyStore` — Postgres-backed store (reuses `backend/postgres/` JDBC
  pool from `backend/postgres/src/main/scala/scalascript/backend/postgres/`).
- Distributed advisory lock over key insertion to prevent double-processing on
  multi-instance deployments.
- Configurable replay-protection window (default: 30 days).

---

## 17. Risks & open questions

**R1 — PSP API version pinning**
Stripe rolls API versions (`2024-11-20.acacia`, …) with breaking changes every few
months.  Mitigation: each adapter pins its API version string and documents the upgrade
path.  `PaymentCapabilities` flags shield app code from version differences.

**R2 — Long-tail PSP-specific fields**
Adyen `additionalData`, Stripe `metadata`, Square `reference_id` have no SPI analogue.
Mitigation: `provider_specific: Map[String, Any]` escape hatch in all response types,
documented as non-portable.

**R3 — Webhook at-least-once delivery**
PSPs retry on 5xx; handler must be idempotent.  Mitigation: built-in `SeenKeyStore`
idempotency in `WebhookReceiver.handle`.  Spec mandates user handlers be written
idempotently regardless.

**R4 — Webhook clock skew**
Server clock drift can falsely reject valid events (timestamp outside ±5min window).
Mitigation: configurable `timestampToleranceSeconds`; default 300.

**R5 — PCI scope explosion**
Any code path that touches a raw PAN would move the merchant from SAQ A to SAQ D
(full audit).  Mitigation: `PaymentMethod.Card(rawDetails)` is blocked in `Live` mode;
SPI types never carry PAN-shaped strings post-collection.

**R6 — SCA UX coordination complexity**
3DS2 requires three hops (merchant server → PSP → issuer → back → merchant).  The
`SCAChallenge` type carries everything the frontend needs.  Full worked example in §15.
`SCA` helper for single-call flows deferred to v1.53.5.

**R7 — Money Long overflow**
JPY or ETH amounts at extreme scales overflow `Long`.  Mitigation: document 64-bit max
≈ $92 quadrillion (sufficient for any commerce); `BigMoney` for ETH > 9.2 ETH deferred
to future phase; throw on arithmetic overflow.

**R8 — Proration rounding bias**
Repeated proration calculations may accumulate error.  Mitigation: `HALF_EVEN` default +
`Money.allocate` for multi-bucket splits.

**R9 — Subscription state divergence**
Local DB and PSP can diverge if a webhook is missed or DB rollback occurs after
webhook processing.  Mitigation: spec recommends PSP as source-of-truth; nightly
reconciliation (`ReconcileProvider` SPI, §14); state-event sourcing pattern recommended
in phase docs.

**R10 — Test-mode key leaking to prod**
`4242 4242 4242 4242` in production creates phantom `pi_xxx` IDs.
Mitigation: `PaymentMode.Test` vs `PaymentMode.Live` enforced in adapter constructor;
mismatch = fatal error before first API call.

**R11 — PSP outage mid-charge**
Charge may have been received on the PSP side before the response is lost.
Mitigation: `ProviderUnreachable` carries `retryAfter`; adapter retries with same
idempotency key (PSP returns original response for duplicate key within window).
App code should handle the `Failed` case with `RetryPolicy.RetryAfterAction` by queuing
a delayed retry.

**R12 — Webhook handler crash**
Handler invocation succeeds but post-handler business logic throws; PSP retries the event.
Mitigation: idempotency gate must be the last step inside `handle` — i.e., `markSeen` is
called only after the handler `IO` succeeds.  Transactional outbox pattern recommended in
phase docs: write intent outcome + email to the same DB transaction before returning 200.

**R13 — Cross-PSP subscription portability**
Migrating a subscriber from Stripe to Adyen requires network-level token migration
(Mastercard MDES / Visa VTS), a new customer record, and re-subscription.  Mitigation:
v1.53 does not promise portability; a `TokenMigrator` SPI is deferred to §14.

---

## 18. Go / no-go

**Go.**

Foundation is solid:

- v1.38 Payment Request (`docs/payment-request.md`) already handles wallet token
  cryptography — Apple Pay ECDH/AES-256-GCM decrypt and Google Pay ECv2 verify+decrypt.
  This closes the hardest crypto step; the adapter simply hands the decrypted token to
  the PSP.
- v1.46 typed routes (`lang/core/src/main/scala/scalascript/interpreter/TypedHandlerWrapper.scala`)
  give us first-class webhook endpoints with zero new infrastructure.
- v1.51 `Stream[T]` gives us paginated `listMethods(customerId): Stream[StoredMethod]`
  APIs when v1.51.1 lands.
- v1.12 algebraic effects give us `IO[T] ! Payment` row + handler discharge for a
  `MockProvider` that needs no network in tests.
- Config-system (`backend/config/src/main/scala/scalascript/config/SubstitutionEngine.scala`)
  gives us `${env:STRIPE_SECRET_KEY}` / `${vault:secret/stripe/prod}` / `${sops:...}`
  unchanged.
- `runtime/std/` plugin pattern is established (8+ live plugins including
  `payment-request-plugin`, `http-plugin`, `ws-plugin`, `auth-plugin`, `sql-plugin`).

Net new work: `Money` type + `PaymentProvider` SPI + `WebhookReceiver` SPI + idempotency
primitive + 4 PSP adapter families + vault / subscription / dispute lifecycle, across 7
phases.

Sequence: v1.53.1 immediately after sign-off (Stripe + all SPI + Money + Webhook land
together; they co-depend and validate the full contract).  v1.53.2 + v1.53.3 + v1.53.4
may run in parallel after v1.53.1 (independent adapter directories, no shared state).
v1.53.5 → v1.53.7 sequence after.
