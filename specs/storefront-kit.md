# Storefront kit — non-custodial sell-anything pages

Status: **draft / planned** (2026-08-30, with Sergiy). Source of truth for the
storefront kit: a lightweight, self-hostable "sell your services or products"
page defined as one `.ssc` document, with checkout across the seller's *own*
payment rails (fiat links + crypto), payment verification, order notifications,
and a conversational (LLM-driven) builder as the primary non-technical UX.
Queued work lives under the "Finmon engine + storefront kit" heading in
`payments/BACKLOG.md`. Companion: [`finmon-engine.md`](finmon-engine.md) —
crypto payment verification uses its `ChainEventSource.watch`, and payer
screening is the storefront's paid upgrade.

## 1. Goals

- **A storefront is a document.** One `store.ssc` file: YAML front-matter
  declares seller, products, prices, payment rails, delivery; the Markdown
  body is the page content. `ssc` builds it into a static site (the existing
  content toolkit / static-SPA lane). Git-versionable, portable, no platform
  lock-in — the seller owns a text file, not an account in our database.
- **Non-custodial, bring-your-own-accounts.** Money moves buyer → seller
  directly over the seller's own rails: their Stripe Payment Link, their
  PayPal, their on-chain addresses (x402/USDC and plain address+QR). The kit
  composes checkout and verifies receipt; it never holds or routes funds —
  which is what keeps it plain software, not a licensed money service.
- **Verification, not trust.** "Paid" is a verified state: crypto rails
  confirm via `ChainEventSource.watch` on the seller's addresses; x402 via
  the existing `x402-server` flow; fiat links via provider webhooks/redirect
  confirmation where the provider offers them, else explicit manual
  confirmation by the seller (stated honestly in the order timeline).
- **Orders reach the seller where they live: Telegram.** Order created, paid,
  needs-action — pushed through a rozum bridge to the seller's chat (see
  `REPOS.md`; rozum is the sibling repo). Email is a later adapter.
- **Agent-buyable by construction.** Every product exposed over x402 is
  purchasable by an AI agent with a wallet — a capability no mainstream
  storefront has, and it costs us nothing extra since x402 modules exist.
- **Rails are plugins.** "Integration with every payment system" is an
  accretion of small `CheckoutRail` adapters, added one at a time, not a
  monolith.

## 2. Non-goals

- **No custody, no merchant-of-record, no card processing of our own** — that
  is Stripe/Paddle's licensed business; we compose their checkout links.
- **No marketplace.** Each store is its own page with its own rails; there is
  no central catalog, cart across sellers, or platform fee mechanism in v1.
- **No visual drag-and-drop builder.** We do not compete with Wix/Tilda on
  canvas editing. The builder UX is conversational + a plain wizard form
  (§6); the document stays the single source of truth.
- **No inventory/ERP.** A `quantity` field with decrement-on-paid is v1's
  entire stock story.

## 3. The document

```
---
store:
  name: Maria Ceramics
  seller: maria            # handle; contact details live in delivery/notify
payments:
  stripe-link: https://buy.stripe.com/...      # seller's own
  paypal-me:   https://paypal.me/maria         # seller's own
  x402:                                        # seller's own address
    asset: USDC
    chain: base
    address: "0xAB..."
  address-qr:                                  # plain receive addresses
    - { asset: BTC, address: "bc1q..." }
    - { asset: ADA, address: "addr1..." }
notify:
  telegram: "@maria_orders"                    # via rozum bridge
products:
  - id: cup-01
    name: Hand-thrown cup
    price: { amount: 30, currency: EUR }
    accept: [stripe-link, paypal-me, x402, address-qr]
    delivery: manual                           # manual | file:<path> | booking:<url>
    quantity: 12
---
# Maria Ceramics
Ordinary Markdown: photos, story, terms...
```

- Front-matter is schema-validated (§6.3); prices are `payments/money`
  `Money`; crypto entries reuse `blockchain-spi` `ChainId`/`Address` types.
- `ssc build store.ssc` → static site: product cards, per-product checkout
  panel rendered by the rails' UI fragments, order-status page.
- Crypto pricing: fiat-denominated prices convert at checkout via the
  existing `payments/fx` providers (`fx-ecb` default), quote validity window
  stated on the page.

## 4. Components

### 4.1 `CheckoutRail` SPI

```scala
trait CheckoutRail:
  def id: RailId
  def render(p: Product, o: OrderDraft): CheckoutFragment   // link/QR/button/x402 challenge
  def verify(o: Order): Stream[PaymentSignal]               // webhook / chain watch / manual
```

v1 rails: `stripe-link` (link out + webhook receiver), `paypal-me` (link out +
manual confirm in v1 — PayPal webhooks are a follow-up), `x402`
(x402-server), `address-qr` (QR + `ChainEventSource.watch` with amount/memo
matching and a confirmation threshold per asset). Each rail is its own small
module, added independently.

### 4.2 Orders

State machine: `draft → pending → paid(finality) → delivered | expired |
flagged`. Append-only order log (JSONL v1, same `AlertStore`-style storage SPI
as finmon); every transition carries its evidence (webhook payload, tx id +
confirmations, or "seller confirmed manually"). `paid` on crypto requires the
per-asset confirmation threshold; underpayment/overpayment produce
`needs-action` signals to the seller, never silent acceptance.

### 4.3 Runtime shape

A fully static page cannot receive webhooks or watch chains, so the kit has
two deployment tiers, same document:

- **static + verifier daemon**: the built site is static; one small
  self-hosted daemon (per seller or shared) runs rails' `verify` streams,
  updates order state, serves the order-status endpoint, pushes Telegram
  notifications. This is the self-hosted default.
- **hosted**: we run the daemon + publishing for sellers who don't self-host —
  the first paid tier. Same code path, no fork.

### 4.4 Paid upgrades (the business surface, all optional)

Hosting + custom domain; sales analytics; **payer screening** — incoming
crypto payments scored by the finmon engine (sanctions hit / risk heuristics)
with a monthly "screening report" artifact a seller can show their bank
(de-risking defense); rule-pack subscription for volume sellers.

## 5. Verification honesty rules

- A rail that cannot confirm programmatically must say so in the order
  timeline ("seller confirmed receipt manually") — the kit never fakes
  certainty it doesn't have.
- Chain verification states its threshold ("paid — 12 confirmations") and
  handles reorgs by reverting to `pending` with a visible event, per
  finmon's `Revert` contract.
- Fiat webhook receivers verify provider signatures; unverifiable webhooks
  are dropped and logged, never trusted.

## 6. Creating a store — the builder question

Three doors into the same document; the document stays the only state.

### 6.1 By hand (developers, v1 day one)

Copy a template from `examples/`, edit, `ssc build`. Templates are complete
working stores (goods / services+booking / digital files). This door is also
the escape hatch from the other two — there is nothing the builder can do
that hand-editing cannot.

### 6.2 Conversational builder (the primary seller UX)

An LLM interview in a chat the seller already has open — Telegram via the
rozum bridge, or web chat: *what do you sell → send photos → prices → how do
you take money today (walks them through creating a Stripe Payment Link if
they have none) → delivery*. The model emits `store.ssc`; the seller sees a
**preview link, never YAML**. Edits are the same conversation ("add a 20%
weekend discount") producing a diff → re-validate → re-preview → publish on
approval.

The pipeline that makes LLM generation safe is mechanical, not prompt-hope:

```
interview → generate store.ssc → parse (ssc) → schema-validate front-matter
  → build preview → seller approves → publish     (any failure → regenerate
                                                   with the validator's error)
```

The target is a *typed document*, not free HTML — the validator rejects
hallucinated fields, the renderer guarantees the page's structure, and the
whole exchange is auditable as a file diff. Model-agnostic: rozum's local
gateway (frugal cascade) or any cloud model; the builder talks to a chat
port, not a vendor SDK.

### 6.3 Wizard form (no-LLM fallback)

A static web form generating the same front-matter — one field per schema
entry, template body attached. Cheap because it is generated *from the
schema*: `storefront-schema` is the single definition from which the
validator, the wizard form, and the docs table all derive.

What the seller sees day-to-day, then: **one Telegram chat** — where they
built the store, where orders and payment confirmations arrive, where they
type changes; plus one public link that is their store. No dashboard to learn
in v1; the hosted analytics page comes with the paid tier.

## 7. Build shape and slices

Same **reference → seam → gate → native** discipline; queue mirrored in
`payments/BACKLOG.md`:

1. **`storefront-schema`** — front-matter schema + validator + `Order` model
   and storage. Gate: valid/invalid corpus; wizard form derives from schema.
2. **`storefront-build`** — `store.ssc` → static site via content toolkit;
   three example templates. Gate: templates build and render; golden-file
   snapshots.
3. **`storefront-rail-x402` / `storefront-rail-address-qr`** — crypto rails
   on `ChainEventSource.watch` (depends on finmon slice 2). Gate: recorded-
   chain replay drives an order to `paid` at threshold; reorg reverts it.
4. **`storefront-rail-stripe-link`** — link-out + webhook receiver with
   signature verification. Gate: recorded webhook fixtures (valid, invalid
   signature, replay) drive correct transitions.
5. **`storefront-verifier-daemon`** — the self-hosted daemon: verify streams,
   order-status endpoint, Telegram notify via rozum bridge (cross-repo;
   integration test uses a stub bridge). Gate: end-to-end on the x402 rail
   against a local chain fixture.
6. **`storefront-builder-conversational`** — interview flow + the §6.2
   validation pipeline, model-agnostic. Gate: scripted interview transcripts
   produce valid stores; injected invalid generations are caught and
   regenerated; zero unvalidated publishes.
7. Later: `storefront-rail-paypal-webhooks`, `storefront-hosted`,
   `storefront-screening` (finmon integration), `storefront-analytics`.
