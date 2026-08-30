# Finmon engine — real-time transaction monitoring

Status: **draft / planned** (2026-08-30, with Sergiy). Source of truth for the
self-hosted transaction-monitoring engine ("finmon") that watches payment flows —
on-chain and bank-rail alike — screens counterparties, evaluates typology rules
over the live stream, and raises alerts. Queued work lives under the
"Finmon engine + storefront kit" heading in `payments/BACKLOG.md`; until a slice
is checked off there, the code does not match this design. Companion spec:
[`storefront-kit.md`](storefront-kit.md) — the storefront is the first external
consumer of the event-ingestion layer defined here.

## 1. Goals

- **One normalized event stream, many sources.** A single `TransferEvent` model
  covers an ERC-20 transfer on Base, a Cardano UTxO movement, a SEPA credit
  transfer parsed from ISO 20022, and a storefront order webhook. Rules and
  screening are written once, against the normalized model, and never know
  where an event came from.
- **Ingestion as an SPI, on the existing chain layer.** `blockchain-spi`
  already defines `ChainAdapter`/`ChainContext` (balances, broadcast,
  receipts). This spec adds the *event* side — `ChainEventSource` — as a
  sibling seam in the same module family, per-chain implementations living
  next to the existing `payments/blockchain/<chain>` adapters.
- **Screening without paid dependencies.** Sanctions/risk screening runs on
  public data (OFAC SDN including digital-currency addresses, EU consolidated
  list, UK OFSI) out of the box. The existing `payments/compliance-*` provider
  SPI (`compliance-chainalysis`, `compliance-complyadvantage`) plugs in as an
  optional deeper tier — the engine must be fully functional without any
  third-party API key.
- **Rules as data.** Detection rules (velocity, structuring, fan-in/fan-out,
  dormancy break) are declarative documents, not code changes: a rule pack is
  a versioned set of `.ssc` documents the engine loads, so updating typologies
  is a content update. This is also the commercial surface: curated rule packs
  are a subscription artifact.
- **Self-hosted and demoable.** Single-process deployment, no mandatory
  external services; a public live demo (USDC on Base) must run on one
  laptop-class node.

## 2. Non-goals

- **No attribution dataset.** We do not build address clustering or entity
  labeling (Chainalysis/Elliptic territory); we consume such data through the
  compliance SPI where a customer pays for it.
- **No custody, no funds flow.** The engine observes and scores; it never
  holds keys or money. (Enforcement — blocking a payment before it happens —
  is a future client of this engine, not part of it.)
- **No case-management suite in v1.** Alerts persist and are queryable; a full
  investigation UI (assignments, SAR drafting) is a later layer. v1 ships the
  alert log, a live dashboard, and CSV/JSONL export; goAML XML export is a
  planned slice, not v1.
- **Not a licensed compliance service.** We ship software a regulated entity
  runs itself; we make no regulatory claims on their behalf.

## 3. Where it sits

```
  sources                        engine                      outputs
┌──────────────────────┐   ┌────────────────────────┐   ┌─────────────────┐
│ ChainEventSource     │   │  normalize → enrich    │   │ alert log       │
│  (evm, solana,       │──▶│  screen (sanctions)    │──▶│ live dashboard  │
│   cardano, bitcoin…) │   │  rules (windowed)      │   │ CSV/JSONL/goAML │
│ Iso20022Ingest       │──▶│  score / dedupe        │   │ storefront API  │
│  (pacs.008, camt.053)│   │                        │   └─────────────────┘
│ storefront orders    │──▶│  checkpoints, replay   │
└──────────────────────┘   └────────────────────────┘
```

Strict invariants, mirroring `blockchain-spi.md` §3:

1. **No upward dependency.** `ChainEventSource` lives beside `ChainAdapter`
   and depends only on `blockchain-spi` types + `ChainContext`. The engine
   depends on sources; sources never depend on the engine.
2. **Transports stay in `ChainContext`.** Sources express *what* to observe;
   how bytes arrive (HTTP polling, WebSocket subscription, gRPC) is a
   `ChainContext` concern. This spec adds one context capability:
   `rpcSubscribe(method, params*)` returning a stream handle, with a
   documented polling fallback for contexts that cannot stream.

## 4. The normalized model

```scala
case class TransferEvent(
  source:        EventSource,          // Chain(ChainId) | Rail(RailKind) | Storefront
  txId:          String,               // tx hash / EndToEndId / order id
  at:            Instant,
  from:          Counterparty,         // address or BankAccount projection
  to:            Counterparty,
  asset:         AssetRef,             // Asset (chain) or currency code (rail)
  amount:        Money,                // payments/money — one money type everywhere
  finality:      Finality,             // confirmations / settled / provisional
  meta:          Map[String, String],
)
```

- `Money` and `RailKind` are the existing `payments/money` and
  `payments/bank-rails` types — the bank-rail vocabulary is already written;
  this spec reuses it, it does not fork it.
- `Counterparty` unifies a chain address and a bank account into one matchable
  identity (`id: String`, `kind`, optional display fields). Screening and
  rules key on `Counterparty.id`.
- **Finality is data, not policy.** Sources report depth (confirmations,
  settlement status); *rules* decide what depth they act at. Reorg handling is
  therefore uniform: a source may emit `Revert(txId)` and the engine retracts
  derived state (v1: retraction invalidates open windows that included the
  event and re-emits affected alerts as `revised`).

## 5. Ingestion seams

### 5.1 `ChainEventSource` (new, `payments/blockchain/spi`)

```scala
trait ChainEventSource:
  def chain: ChainId
  def live(from: Checkpoint)(using ChainContext): Stream[SourcedEvent]
  def backfill(range: BlockRange)(using ChainContext): Stream[SourcedEvent]
  def watch(addrs: Set[Address], from: Checkpoint)(using ChainContext): Stream[SourcedEvent]
```

- `live` resumes from a persisted `Checkpoint` (block/slot + hash); the engine
  persists checkpoints after processing, so restart = resume + backfill of the
  gap. At-least-once delivery; the engine dedupes on `(source, txId, logIndex)`.
- `watch` is the narrow mode the storefront uses (a handful of addresses, low
  rate); `live` is the firehose mode the monitoring demo uses. Same seam, so
  the storefront exercises the exact code the flagship runs.
- **First implementation: EVM** (`eth_subscribe`/`eth_getLogs` on ERC-20
  `Transfer` topics through `rpcSubscribe`/`rpcCall`) — one implementation
  covers Ethereum, Base, Arbitrum, Optimism, Polygon, BNB by `ChainId`.
  Then, in order: Tron (new adapter; USDT flows carry the AML content),
  Bitcoin, Solana, Cardano (chain-sync via the existing Blockfrost client
  first, Ogmios later), Stellar. Each is "another adapter" per
  `blockchain-spi` — no engine changes.

### 5.2 `Iso20022Ingest` (new, `payments/bank-rails`)

- Parsers for `pacs.008` (customer credit transfer) and `camt.053`/`camt.054`
  (statements/notifications) into `TransferEvent`, reusing `BankAccount` /
  `RailKind`. Input: files or a directory feed (how regulated clients actually
  hand data over); a streaming endpoint is a later slice.
- **Synthetic generator**: produces labeled ISO 20022 scenario sets —
  structuring under a threshold, smurfing fan-in, rapid pass-through — used
  both for the public demo (real bank data is never available) and as the
  rules gate corpus. Labels ship with the corpus so expected alerts are
  machine-checkable.

## 6. Screening

- `SanctionsIndex`: loader + refresher for public lists (OFAC SDN + digital
  currency addresses, EU consolidated, UK OFSI), normalized into exact-match
  address sets and normalized-name entries. Address hit ⇒ `severity: critical`
  alert, no rule needed. Name matching in v1 is normalized-exact only —
  fuzzy/transliterated matching is a **later slice with its own
  false-positive-rate gate**, not a v1 afterthought (naive fuzzy matching is
  where screening tools drown users in noise).
- Provider SPI: `ScreeningProvider` with the local index as default;
  `compliance-chainalysis` / `compliance-complyadvantage` register as premium
  providers per the existing compliance module pattern. Results carry the
  provider name — an alert must say *why* and *per whom* an entity is risky.

## 7. Rules

- Execution model: keyed, windowed evaluation over the normalized stream —
  key by `Counterparty.id` (and rule-declared secondary keys), sliding/tumbling
  windows per rule, incremental aggregates (`std/aggregator.ssc` provides the
  algebra: sum/count/mean/min/max compose and merge across window panes).
- A rule = one `.ssc` document: YAML front-matter (id, version, severity,
  window, threshold parameters, applicable sources/assets) + a guarded
  expression over window aggregates + prose rationale in the body — the
  document *is* the auditable rule definition a compliance officer reads.
- v1 rule pack (each with a labeled synthetic scenario in the gate corpus):
  1. `velocity` — N events / window per counterparty above threshold.
  2. `structuring` — ≥K transfers each within ε below a threshold amount,
     same counterparty pair or fan-in, within window.
  3. `fan-in` / `fan-out` — distinct-counterparty count crossing threshold.
  4. `dormancy-break` — first activity after ≥D dormant days, amount above P
     percentile of the counterparty's history.
  5. `pass-through` — in ≈ out within τ minutes at ≥R% of amount.
- Alerts: `Alert(ruleId, ruleVersion, severity, key, window, evidence:
  Seq[TransferEvent], score)`, deduped per (rule, key, window), persisted
  append-only (JSONL in v1; storage behind a small `AlertStore` SPI so SQLite/
  Postgres are drop-ins).

## 8. Dashboard & demo

- An `examples/` program (per `AGENTS.md`: user-facing ⇒ example exists) wires
  EVM source (Base, USDC) → screening → v1 rule pack → live web dashboard
  (content toolkit; alert feed + per-rule counters + throughput). This is the
  public demo and the recruiting/grant artifact — it must run with a single
  command against a public RPC endpoint with no API keys.
- A second example runs the ISO 20022 synthetic corpus through the same
  engine, proving the "crypto + bank rails, one engine" claim.

## 9. Performance

Chunked stream processing end-to-end; budget for v1: **≥ 10k events/s
sustained single-node** with the full v1 rule pack and screening enabled,
measured with the alternating A/B protocol per the `performance` skill and
recorded in its history. The budget is a gate for the engine-core slice, not
aspiration prose.

## 10. Build shape and slices

Every slice follows **reference → seam → gate → native** per
[`crypto-finance-roadmap.md`](crypto-finance-roadmap.md) §2. Slice queue,
dependency-ordered, with gates — mirrored in `payments/BACKLOG.md`:

1. **`finmon-event-model`** — `TransferEvent`/`Counterparty`/`Finality` +
   dedupe/checkpoint contracts. Gate: model round-trips + dedupe property
   tests.
2. **`chain-event-source-evm`** — the `ChainEventSource` seam +
   `rpcSubscribe` context capability + EVM implementation (ERC-20 transfers).
   Gate: recorded-RPC replay fixtures reproduce a known Base block range
   bit-for-bit; polling fallback produces the identical event sequence.
3. **`iso20022-ingest`** — pacs.008/camt.053 parsers + synthetic generator.
   Gate: published sample messages parse; generator corpus round-trips;
   labels validate.
4. **`sanctions-screening`** — list loaders + `SanctionsIndex` +
   `ScreeningProvider` SPI. Gate: known sanctioned addresses/names from the
   public lists hit; refresh is idempotent.
5. **`finmon-rules-core`** — windowed engine + the five v1 rules as `.ssc`
   rule documents. Gate: labeled scenario corpus — every expected alert
   fires, zero unexpected alerts; throughput budget (§9) met.
6. **`finmon-dashboard-demo`** — the two `examples/` programs (§8). Gate:
   single-command run against public Base RPC; demo scenario script.
7. Later: `chain-event-source-{tron,bitcoin,solana,cardano,stellar}`,
   `finmon-goaml-export`, `screening-fuzzy-names` (with FPR gate),
   `finmon-enforcement` (the spend-firewall client).
