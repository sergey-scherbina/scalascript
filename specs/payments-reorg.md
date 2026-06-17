# spec: payments-reorg — unify crypto / wallet / payments under one `payments/` tree

Status: **planned (2026-06-17).** Decided with Sergiy: target = everything under
`payments/`; migration = incremental, one family/provider per push. Claim: `payments-reorg`.

## Motivation

Payment-related code lives in **four inconsistent patterns** today:

| Family | Current location | Pattern |
|---|---|---|
| Traditional processors (21) | `runtime/std/payments-{stripe,paypal,sepa,…}` (flat) | std plugins, ServiceLoader |
| PaymentProvider SPI | `runtime/std/payments-plugin` | std plugin |
| W3C Payment Request | `runtime/std/payment-request-plugin` | std plugin |
| Blockchain | `payments/blockchain/{evm,solana,bitcoin,cardano,cosmos,spi,evm-abi}` | cross-compiled ✓ nested |
| Wallet | `payments/wallet/{spi,connect,connector-*,…}` | cross-compiled ✓ nested |
| x402 | `payments/x402/{core,server,client,facilitator-*,…}` | cross-compiled ✓ nested |
| Crypto | `runtime/std/crypto-plugin` + `cryptoSpi`/`cryptoBouncycastle`/`cryptoNobleJs` | mixed |

Blockchain/wallet/x402 are already tidily nested under top-level `payments/`. The odd
ones out are the **21 flat processors + the two SPI plugins + crypto**, which sit flat
under `runtime/std/`. Goal: pull them into the same `payments/` tree so all
payment/crypto code is discoverable in one place.

## Key fact that makes this low-risk

Traditional processors are **ServiceLoader-activated**, not referenced by `.ssc` import
paths: user code writes `PaymentProvider.named("stripe")` and the runtime resolves
`StripeProvider` via `META-INF/services/scalascript.payments.PaymentProvider`. The plugin
is on the classpath via the CLI `PluginSpec` list in `build.sbt`.

⇒ Moving a processor changes **only build config**: the module's `.in(file(...))` path,
the root `aggregate`, and the `PluginSpec` list. The Scala **package stays**
`scalascript.payments.<x>`, the `META-INF/services` stays, and **no `.ssc` user code,
example, or import changes**. The `git mv` preserves history.

## Target tree

```
payments/
  spi/            ← runtime/std/payments-plugin      (PaymentProvider SPI, Money, WebhookReceiver)
  request/        ← runtime/std/payment-request-plugin (W3C Payment Request API)
  processors/
    stripe/ paypal/ braintree/ adyen/ checkout/ square/    (card PSPs)
    ach/ sepa/ swift/ fednow/                              (bank rails — Americas/EU/global)
    pix/ mx-spei/ ca-eft/                                   (Americas)
    uk-bacs/ uk-chaps/ uk-fps/                              (UK)
    au-npp/ sg-paynow/ india-upi/ japan-zengin/             (APAC)
    mock/                                                   (test double)
  crypto/         ← runtime/std/crypto-plugin + cryptoSpi/cryptoBouncycastle/cryptoNobleJs
  blockchain/     (already here — unchanged)
  wallet/         (already here — unchanged)
  x402/           (already here — unchanged)
```

Notes:
- **sbt val names stay** (`paymentsStripe`, `paymentsPlugin`, `cryptoSpiCross`, …) — only
  the `.in(file(...))` directory path changes. Renaming vals is out of scope (churns every
  `.dependsOn`/aggregate reference for zero functional gain).
- `payments-plugin` → `payments/spi` (it IS the SPI); `payment-request-plugin` →
  `payments/request` (distinct W3C API, not the PaymentProvider SPI).
- **crypto** is shared (wallet + x402 + blockchain depend on it), but lives under
  `payments/crypto/` for locality; it stays a peer, not a processor.

## Migration — incremental, per family

Each slice: `git mv` the dir(s) → fix the `.in(file(...))` path(s) in `build.sbt` →
`sbt <module>/compile` (+ `/test` where fast) → commit → push. The root `aggregate` and
`PluginSpec` list reference **val names**, so they only change if a val is renamed (it
isn't) — verify they still resolve after each move.

1. **spi + request** — `payments-plugin`→`payments/spi`, `payment-request-plugin`→`payments/request`.
2. **card PSPs** — stripe, paypal, braintree, adyen, checkout, square → `payments/processors/`.
3. **bank rails** — ach, sepa, swift, fednow → `payments/processors/`.
4. **Americas + UK + APAC rails** — pix, mx-spei, ca-eft, uk-*, au-npp, sg-paynow, india-upi, japan-zengin, mock.
5. **crypto** — crypto-plugin + cryptoSpi/bouncycastle/noble → `payments/crypto/`.

Bundle small families per commit to keep the count reasonable; verify each before push.

## Verify (per slice)

- `sbt "<movedModule>/compile"` clean (and `/test` for the SPI + one processor).
- `grep` build.sbt for any stale `runtime/std/payments-` / `crypto-plugin` `file()` paths.
- `ssc` still loads the plugin: the `traditional-payments` example runs (ServiceLoader
  finds the provider) — proves the `PluginSpec` list + classpath survived the move.
- Root `sbt compile` green at the end of each slice (catches a missed `file()` path).

## Non-goals

- Renaming Scala packages or sbt val names.
- Touching `.ssc` user code / examples / docs imports (none reference the moved paths).
- Reorganising the already-nested `payments/{blockchain,wallet,x402}` beyond leaving them.
- Maven coordinates / artifact names (only relevant at publish time — deferred last).

## Behavior checklist

- [ ] `payments/spi` + `payments/request` moved; build green.
- [ ] `payments/processors/*` — all 21 processors moved; build green; `traditional-payments` runs.
- [ ] `payments/crypto/*` moved; build green.
- [ ] No `runtime/std/payments-*` / `runtime/std/crypto-plugin` dirs remain; no stale `file()` paths.
- [ ] Full `sbt compile` + the payments/crypto plugin test suites green.
