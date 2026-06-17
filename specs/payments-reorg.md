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

## Two layers (the structure that drives the hybrid scheme)

`payments/` is the **cross-compiled LIBRARY layer** and already holds nested families:
`payments/{crypto/{spi,bouncycastle,noble-js}, money, webhook, fx, tax, compliance,
micropayment, payment-request, client, bank-rails, blockchain, wallet, x402}`.

`runtime/std/*` is the **interpreter-PLUGIN layer** (ServiceLoader `scalascript.backend.spi.Backend`s
exposing intrinsics/providers to `.ssc`). Only **24** of these are payment-domain (no fx/tax/
compliance interp plugins exist — those are libs-only):

| Plugin (runtime/std) | Kind | Wraps a `payments/` lib? |
|---|---|---|
| `payments-plugin` | defines the `PaymentProvider` SPI + registration | no (plugin-only) |
| `payments-{stripe,paypal,braintree,adyen,checkout,square}` | card PSP providers | no (provider IS the plugin) |
| `payments-{ach,sepa,swift,fednow,pix,mx-spei,ca-eft,uk-bacs,uk-chaps,uk-fps,au-npp,sg-paynow,india-upi,japan-zengin}` | bank-rail providers | no |
| `payments-mock` | test double | no |
| `crypto-plugin` | intrinsics over the crypto lib | **yes** → `payments/crypto` |
| `payment-request-plugin` | intrinsics over the W3C lib | **yes** → `payments/payment-request` |

## Hybrid target (chosen with Sergiy)

- **Plugin-only families** (no separate lib) group under `payments/processors/`:
  the SPI hub at `payments/processors/spi` (matching `payments/blockchain/spi`,
  `payments/wallet/spi`), the 21 providers at `payments/processors/<name>`.
- **Wrapper plugins** sit next to the lib they wrap: `payments/crypto/plugin`,
  `payments/payment-request/plugin`.

```
payments/
  processors/
    spi/          ← runtime/std/payments-plugin   (PaymentProvider SPI + registration)
    stripe/ paypal/ braintree/ adyen/ checkout/ square/        ← runtime/std/payments-*
    ach/ sepa/ swift/ fednow/ pix/ mx-spei/ ca-eft/
    uk-bacs/ uk-chaps/ uk-fps/ au-npp/ sg-paynow/ india-upi/ japan-zengin/ mock/
  crypto/
    spi/ bouncycastle/ noble-js/   (libs — already here)
    plugin/       ← runtime/std/crypto-plugin
  payment-request/                 (lib — already here)
    plugin/       ← runtime/std/payment-request-plugin
  blockchain/ wallet/ x402/ money/ webhook/ fx/ tax/ compliance/ micropayment/  (unchanged)
```

Notes:
- **sbt val names stay** (`paymentsStripe`, `paymentsPlugin`, `cryptoPlugin`, …) — only the
  `.in(file(...))` path changes. The root `aggregate` + the CLI `PluginSpec` list reference val
  names, so they don't change; verify they still resolve.
- Scala packages stay (`scalascript.compiler.plugin.payments.*`, `…plugin.crypto.*`); `git mv`
  preserves history; `META-INF/services` moves with the dir → ServiceLoader unaffected.
- A wrapper plugin already `.dependsOn` its lib by **val name**, so colocating it under the lib's
  dir changes only its own `file()` path.

## Migration — incremental, per family

Each slice: `git mv` the dir(s) → fix the `.in(file(...))` path(s) in `build.sbt` →
`sbt <module>/compile` (+ `/test` for the SPI + one provider) → commit → push.

1. **spi** — `payments-plugin` → `payments/processors/spi`.
2. **card PSPs** — stripe, paypal, braintree, adyen, checkout, square → `payments/processors/`.
3. **bank rails (global/EU/Americas)** — ach, sepa, swift, fednow, pix, mx-spei, ca-eft → `payments/processors/`.
4. **UK + APAC + mock** — uk-bacs, uk-chaps, uk-fps, au-npp, sg-paynow, india-upi, japan-zengin, mock.
5. **wrapper plugins** — crypto-plugin → `payments/crypto/plugin`; payment-request-plugin → `payments/payment-request/plugin`.

Root `sbt compile` green after each slice; the `traditional-payments` example runs (ServiceLoader
proof) after the processors land.

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

- [x] `payments/processors/spi` moved; `paymentsPlugin/compile` green. (slice 1)
- [x] `payments/processors/*` — all 21 providers moved; all compile; `paymentsSepa/test` 71, `paymentsStripe/test` 23 green. (slices 2–4)
- [x] `payments/crypto/plugin` + `payments/payment-request/plugin` moved; `cryptoPlugin/test` 58 green. (slice 5)
- [x] No `runtime/std/payments-*` / `crypto-plugin` / `payment-request-plugin` dirs remain; no stale `file()` refs in build.sbt/scala/scripts (only CHANGELOG history retains old paths, which is correct).
- [x] `cli/installBin` stages all bundled plugins (PluginSpec list + root aggregate intact). DONE 2026-06-17.

Note: standalone `ssc check` of examples using a non-bundled processor (e.g. SEPA `MandateId`)
fails — **pre-existing**: only the SPI + crypto + payment-request plugins are bundled in the
default CLI; individual processors never were. Unrelated to the move.
