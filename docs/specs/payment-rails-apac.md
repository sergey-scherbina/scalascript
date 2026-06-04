# Payment Rails — APAC + Americas

> Spec for new payment rails beyond the v1.55 set.
> Implementation status, aggregator REST shape, and test strategy per rail.

---

## AU_NPP — Australia New Payments Platform (NPP / Osko / PayID)

**Status**: implemented (v1.57.1)
**Module**: `runtime/std/payments-au-npp/`
**SPI entry**: `AuNppPlugin` registered in `META-INF/services/scalascript.backend.spi.Backend`

### Goals

Provide an adapter for the Australia New Payments Platform (NPP), the country's
real-time 24x7 payments infrastructure. NPP underlies the consumer-facing "Osko"
brand and the **PayID** proxy-resolution service.

### Non-goals

- Cross-border FX conversion (out of scope; see `docs/specs/traditional-payments.md §FxProvider`)
- NPP batch / bulk payments (standard NPP credit only)
- Direct RITS (Reserve Bank of Australia settlement) integration — aggregator-mediated only

### Architecture

Four files in `runtime/std/payments-au-npp/`:

| File | Role |
|------|------|
| `AuNppConfig` | Configuration: `apiKey`, `baseUrl`, `webhookSecret` |
| `AuNppApi` | REST client: PayID resolve, NPP credit submit, status poll |
| `AuNppProvider` | `BankRailsProvider` impl for `RailKind.AU_NPP` |
| `AuNppWebhookReceiver` | `WebhookReceiver[BankRailsEvent]`, HMAC-SHA256 verify |
| `AuNppPlugin` | Backend SPI ServiceLoader registration |

SPI additions in `payments/bank-rails/`:

- `RailKind.AU_NPP` — new case
- `BankAccount.payid: Option[String]` — PayID proxy (mobile / email / ABN / ACN / ORG)
- `BankAccount.bsbNumber: Option[String]` — Bank-State-Branch number (6 digits)
- `BankRailsEvent.AuNppCredited(transfer)` — NPP settlement confirmed
- `BankRailsEvent.AuNppReturned(transfer, returnCode)` — transfer returned
- `BankRailsError.NppPayIdNotFound(payid)` — PayID not in directory
- `BankRailsError.UnsupportedCurrency(currency, rail, supported)` — non-AUD amount

### PayID Resolution

PayID is an NPP-native proxy service that maps a human-readable identifier to a
BSB + account number:

| PayID type | Example |
|-----------|---------|
| Mobile | `+61412345678` |
| Email | `user@example.com` |
| ABN (business) | `51 824 753 556` |
| ACN (company) | `000 000 019` |
| ORG (registered org) | any NPP-registered organisation identifier |

Resolution flow:

1. `AuNppProvider.initiateTransfer` checks `req.recipient.payid`.
2. If present, calls `AuNppApi.resolvePayId(payid)` → POST `/v1/payid/resolve`
   with `{"payid": "<value>"}`.
3. Aggregator returns `{"bsb": "062-000", "accountNumber": "11223344"}`.
4. Provider uses resolved BSB + account for the NPP credit transfer.
5. If `payid` is absent, falls back to `BankAccount.bsbNumber` + `BankAccount.accountNumber`.

### Aggregator REST shape

The adapter targets a standard NPP aggregator REST API (Monoova, Zepto, Cuscal, or
major bank business APIs such as ANZ Transactive / CBA CommBiz).

#### POST `/v1/payid/resolve`

Request:
```json
{ "payid": "+61412345678" }
```

Response:
```json
{ "bsb": "062-000", "accountNumber": "11223344" }
```

#### POST `/v1/npp/payments`

ISO 20022 pacs.008-shaped JSON envelope (aggregator converts to full XML internally):

```json
{
  "msgId": "<endToEndId>",
  "endToEndId": "<idempotencyKey>",
  "instructedAmount": { "currency": "AUD", "value": 10000 },
  "creditor": {
    "name": "Alice Smith",
    "bsb": "062-000",
    "accountNumber": "11223344"
  },
  "debtor": {
    "name": "Acme Pty Ltd",
    "bsb": "033-000",
    "accountNumber": "99887766"
  },
  "remittanceInfo": "Invoice 42",
  "serviceLevel": "NPP",
  "payidUsed": "+61412345678"   // optional, for audit
}
```

Response:
```json
{ "transferId": "npp-tx-001", "status": "pending" }
```

#### GET `/v1/npp/payments/{id}`

Response mirrors the initiation response plus updated `status` field.

#### Webhook events

Auth: `X-NPP-Signature: sha256=<hex>` — HMAC-SHA256 over raw body with shared secret.

| `"type"` field | Maps to |
|---------------|---------|
| `npp.payment.credited` | `BankRailsEvent.AuNppCredited(transfer)` |
| `npp.payment.returned` | `BankRailsEvent.AuNppReturned(transfer, returnCode)` |

Example `npp.payment.credited` payload:
```json
{
  "type": "npp.payment.credited",
  "transferId": "npp-tx-001",
  "amount": "10000",
  "creditorBsb": "062-000",
  "creditorAccount": "11223344",
  "creditorName": "Alice Smith",
  "debtorBsb": "033-000",
  "debtorAccount": "99887766",
  "debtorName": "Acme Pty Ltd",
  "remittanceInfo": "Invoice 42"
}
```

Example `npp.payment.returned` payload:
```json
{
  "type": "npp.payment.returned",
  "transferId": "npp-tx-001",
  "returnCode": "AC01",
  "amount": "10000"
}
```

### Constraints

- **AUD only** — NPP transfers are denominated in Australian Dollars. Non-AUD
  requests raise `UnsupportedCurrency`.
- **Irrevocable** — NPP transfers cannot be cancelled after submission.
  `cancelTransfer` throws `BankRailsCancelError`.
- **Settlement** — T+0, typically < 60 seconds, 24x7x365.
- **Limit** — AUD 1,000,000 per transaction (standard cap; higher limits require
  bilateral agreement with receiving NPP participant).
- **Direct debit not supported** — NPP is a push-only rail. `initiateDirectDebit`
  and `getDirectDebit` throw `UnsupportedRail`.

### Test strategy

The test suite (`AuNppProviderTest`, 35+ tests) uses `com.sun.net.httpserver.HttpServer`
for all HTTP-touching tests, started on an ephemeral port and torn down after each test.

Coverage:
- `RailKind.AU_NPP` case existence and distinctness
- `BankAccount.payid` / `bsbNumber` field preservation and defaults
- `AuNppConfig` field access + `fromEnv`
- Provider SPI contract (id, displayName, spiVersion, supportedRails)
- `UnsupportedRail` for non-NPP rails
- `UnsupportedCurrency` for non-AUD currencies (USD, GBP)
- `BankRailsCancelError` from `cancelTransfer`
- `UnsupportedRail` from `initiateDirectDebit`
- PayID resolution mock (happy path, BSB fallback field, missing BSB error)
- NPP credit transfer with PayID addressing (two-endpoint mock)
- NPP credit transfer with BSB + account fallback (single-endpoint mock)
- `getTransfer` status polling (Pending, Settled, Returned)
- Webhook HMAC-SHA256 verification (valid, wrong HMAC, missing header, bad format, lowercase header)
- `npp.payment.credited` → `AuNppCredited` parsing (fields, BSB/account)
- `npp.payment.returned` → `AuNppReturned` parsing (returnCode, reason fallback)
- Unknown event type → `MalformedPayload`
- `idempotencyKey` for both event types
- `hmacSha256` helper (length, hex chars, different secrets, different payloads)
- `BankRailsEvent.AuNppCredited/AuNppReturned` pattern matching
- `AuNppPlugin` capabilities
- `AuNppApi` JSON helpers (extractField, extractBoolField)
- `NppPayIdNotFound` error message and type hierarchy

---

## CA_INTERAC — Canada Interac e-Transfer + EFT

**Status**: planned (v1.57.2)

See BACKLOG.md §v1.57.2-payment-rails-canada-eft.

---

## MX_SPEI — Mexico SPEI (Sistema de Pagos Electrónicos Interbancarios)

**Status**: planned (v1.57.3)

See BACKLOG.md §v1.57.3-payment-rails-mexico-spei.
