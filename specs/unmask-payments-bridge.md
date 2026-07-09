# v2 Payments and Bank-Rails Bridge

## Overview

The v2 production lane must execute the documented standard-Scala payment
examples without unresolved plugin-boundary values in stdout. After standard
`scala` fences became runnable, three examples were unmasked as false greens:
`examples/traditional-payments.ssc`, `examples/bank-rails-pix.ssc`, and
`examples/bank-rails-fednow.ssc` all exit 0 on v2, but they print `Op(...)` or
`Stub` values where concrete payment/provider results should appear.

This slice bridges the payment and bank-rails surface needed by those examples:
`PaymentProvider.named("stripe")`, deterministic card/subscription/refund
operations, `Money`/`Currency` arithmetic helpers, Pix/FedNow provider factories,
pure Pix QR generation, and the small `Instant`/`Thread` poll surface used by the
FedNow walkthrough.

## Interface

- `PaymentProvider.named("stripe")` is available in v2 standard-Scala fences and
  returns a no-network deterministic provider method object.
- The provider supports the documented example methods:
  `createIntent`, `confirmIntent`, `captureIntent`, `voidIntent`,
  `createPlan`, `createCustomer`, `attachMethod`, `subscribe`,
  `changeSubscription`, `cancelSubscription`, `refund`, and
  `submitDisputeEvidence`.
- `Money(...)`, `Money.zero`, `Money.allocate`, `Currency(...)`, and common
  `Currency` constants are available with field access and display shapes that
  match the existing payment examples.
- Payment companion objects and enum-like cases used by the examples are
  available on v2: `PaymentMethod`, `PaymentIntent`, `CaptureMethod`,
  `BillingInterval`, `ProrationMode`, `RefundReason`, and related ID wrappers.
- `PixConfig(...)`, `PixProvider(...)`, `PixQrCode.StaticConfig(...)`,
  `PixQrCode.DynamicConfig(...)`, `PixQrCode.buildStatic`, and
  `PixQrCode.buildDynamic` are available. QR generation delegates to the existing
  pure Pix QR implementation rather than duplicating EMV payload logic.
- `FedNowConfig.fromEnv`, `FedNowProvider(...)`, `BankAccount(...)`,
  `InitiateTransferRequest(...)`, `RailKind.PIX`, `RailKind.FEDNOW`,
  `BankTransferStatus.*`, and `TransferId(...)` are available.
- `Instant.now().plusSeconds(...)`, `Instant#isAfter`, and `Thread.sleep(...)`
  are available only as the minimal standard-Scala bridge surface required by
  `examples/bank-rails-fednow.ssc`.

## Behavior

- [x] `bin/ssc run --v2 examples/traditional-payments.ssc` exits 0 and does not
      print `Op(` or `Stub`.
- [x] The first traditional payment charge reaches
      `PaymentIntent.Succeeded` and prints a deterministic charge id.
- [x] The subscription/customer/store-method snippets in
      `traditional-payments.ssc` print deterministic IDs and statuses when they
      are part of the runnable stream.
- [x] `bin/ssc run --v2 examples/bank-rails-pix.ssc` exits 0 and does not print
      `Op(` or `Stub`.
- [x] The Pix transfer section prints a deterministic transfer id plus concrete
      pending/settled statuses.
- [x] `PixQrCode.buildStatic` and `buildDynamic` return real EMV payload strings
      through the existing Pix QR implementation.
- [x] `bin/ssc run --v2 examples/bank-rails-fednow.ssc` exits 0 and does not
      print `Op(` or `Stub`.
- [x] The FedNow poll section can evaluate `Instant.now().plusSeconds(...)`,
      `Instant#isAfter`, and `Thread.sleep(...)` without leaking operations.
- [x] Focused bridge tests cover payments, Pix, and FedNow so later bridge
      changes cannot silently reintroduce `Op`/`Stub` output.

## Out of scope

- Real Stripe, Pix, or FedNow network calls. The v2 production smoke path must
  not require credentials, sponsor-bank connectivity, or external services.
- Full PSP/bank-rail behavioral fidelity. The bridge is deterministic and
  example-oriented; production adapters remain in the existing payments modules.
- Implementing all payment specs from `specs/traditional-payments.md` and
  `specs/bank-rails.md`. This slice covers the documented runnable example
  surface that currently blocks v2 production honesty.
- Treating v1 output as the oracle. The rollback `--v1` lane currently fails
  earlier on missing payment/bank-rails names, so v2 acceptance is based on the
  documented behavior and absence of unresolved bridge values.
- Adding new core intrinsics. Payment behavior belongs in the v2 bridge over
  existing std modules, not in runtime core.

## Design

The implementation should keep the v2 runtime core small and bridge only the
names exposed by the examples:

- Add existing payment/bank-rails modules to `v2PluginBridge` so the bridge can
  reuse public types and pure helpers, especially Pix QR generation.
- Extend `FrontendBridge`'s built-in knowledge for payment factory/object names
  that otherwise compile as inert constructors. Names like `PaymentProvider`,
  `Money`, `Currency`, `PixQrCode`, `FedNowConfig`, `Instant`, and `Thread`
  should resolve to registered method objects; uppercase callable factories like
  `PixProvider(...)` and `FedNowProvider(...)` should call globals rather than
  becoming `DataV` constructors.
- Register field names/defaults for payment and bank-rails records that appear
  in named-argument calls or field access. This keeps case-class-like values
  usable from v2 pattern matching and string interpolation.
- Register deterministic provider method objects in `PluginBridge`:
  - `PaymentProvider.named("stripe")` returns an in-memory Stripe-shaped test
    provider.
  - `PixProvider(config)` returns a Pix bank-rails provider whose transfer calls
    return deterministic `BankTransfer` data.
  - `FedNowProvider(config)` returns a FedNow provider whose transfer calls
    return deterministic `BankTransfer` data and whose over-limit path can be
    tested without network calls.
- For data that does not need JVM adapter behavior, return ordinary v2 `DataV`
  shapes with registered field names. For pure library behavior, call the
  existing Scala implementation and convert the result back to v2 values.

## Decisions

- **Use deterministic no-network bridge providers** - chosen because production
  v2 examples and gates must be reproducible without credentials or external
  services. Rejected: delegating `PaymentProvider.named("stripe")` to the real
  `StripeProvider`, because it would hit Stripe APIs and require secrets.
- **Reuse pure Pix QR code generation** - chosen because EMV payload assembly
  already exists in the Pix module. Rejected: duplicating QR construction inside
  the v2 bridge.
- **Do not use v1 parity as acceptance** - chosen because v1 currently fails
  before reaching the payment examples' behavior. Rejected: forcing v2 to match a
  stale rollback failure.
- **Bridge the example surface first** - chosen because this is a production
  honesty blocker exposed by runnable standard-Scala fences. Rejected: porting
  the entire payment SPI at once, which would widen the blast radius and delay a
  shippable v2 fix.

## Results

Baseline before implementation, after staging the CLI with
`scripts/sbtc "installBin"`:

```text
bin/ssc run --v2 examples/traditional-payments.ssc
# rc=0
# Op("PaymentProvider.named", "stripe", <closure>)

bin/ssc run --v2 examples/bank-rails-pix.ssc
# rc=0
# Transfer initiated: Stub, status: Stub
# Transfer status: Stub
# Op("PixQrCode.buildStatic", StaticConfig(...), <closure>)

bin/ssc run --v2 examples/bank-rails-fednow.ssc
# rc=0
# FedNow transfer Stub submitted - status: Stub
# Op("Instant.now", (), <closure>)

bin/ssc run --v1 examples/traditional-payments.ssc
# rc=1, Undefined: PaymentProvider

bin/ssc run --v1 examples/bank-rails-pix.ssc
# rc=1, Undefined: PixConfig

bin/ssc run --v1 examples/bank-rails-fednow.ssc
# rc=1, Undefined: FedNowConfig
```

Implementation results after `scripts/sbtc "installBin"`:

```text
bin/ssc run --v2 examples/traditional-payments.ssc
# rc=0
# Paid - charge: ch_stripe_demo_pi_1, receipt: Some(...)
# Subscribed: stripe_demo_sub_5, status: Trialing, ...
# Upgraded to stripe_demo_plan_6, status: Active
# Stored method: visa .... 4242, exp 12/2030
# Captured: stripe_demo_pi_10
# Subtotal: $49.99, Tax: $10.00, Total: $59.99
# List(0.34, 0.33, 0.33)

bin/ssc run --v2 examples/bank-rails-pix.ssc
# rc=0
# Transfer initiated: pix_order12345attempt1, status: Pending
# Transfer status: Settled
# Static QR payload (first 60 chars): 00020126410014br.gov.bcb.pix...
# Dynamic QR payload length: 198
# CRC suffix: 6304FDCB

bin/ssc run --v2 examples/bank-rails-fednow.ssc
# rc=0
# FedNow transfer fednow_fednowtransferorder88attempt1 submitted - status: Pending
# Final status: Settled
```

Verification:

- `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest'`
  passed 42/42.
- `scripts/sbtc 'installBin'` rebuilt the staged CLI.
- The three direct `bin/ssc run --v2` example checks passed and a shell guard
  confirmed none of their stdout contained `Op(` or `Stub`.
- `tests/conformance/run.sh --only 'money-multisection,v2-*' --no-memo`
  passed 4/4. The conformance corpus has no payments/Pix/FedNow fixture yet, so
  this affected slice covers the nearby v2 bridge and Money surface.
- `./v2/conformance/check.sh` passed.
- `git diff --check` passed.

Notes:

- The SCA, Apple Pay, refund/dispute, webhook, Pix Automático, and FedNow limit
  snippets remain in the examples as `scala no-run` because they depend on
  route/webhook/platform state or negative paths outside this deterministic v2
  smoke bridge.
