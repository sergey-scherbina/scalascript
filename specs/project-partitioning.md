# The project in three parts — the language, its standard library, and additional libraries

Status: **descriptive**. This document does not move a single file. It states the partition that the
repository already implies, names every module in it, and records where the tree disagrees with
itself. Companion specs: [`work-tracking-layout.md`](work-tracking-layout.md) (which directory owns
which BOARD), [`arch-distribution.md`](arch-distribution.md), [`arch-library-modularity.md`](arch-library-modularity.md).

## 1. Why the split needs writing down

`build.sbt` defines **260 sbt projects**. Nothing in the tree says which of them a person has to
understand to work on the language, which ship to every user by default, and which are one domain's
concern. The answer exists — it is just spread across an sbt allowlist, a directory naming
convention, and two launcher tiers, with no single place to read it and nothing that checks the
three agree.

## 2. The criterion, and why one axis is not enough

There are TWO independent facts about a module, and conflating them is what makes the layout look
arbitrary:

- **Role** — what the module *is*: the language, its standard library, or an additional library.
- **Tier** — what the module *ships in*: `build.sbt`'s `standardJarPrefixes` allowlist decides
  whether a JAR lands in the default `bin/ssc` distribution or only in the optional
  tools/compatibility tier (`bin/ssc-tools`).

Role is a judgement; tier is a fact already written down and enforced by the installer. Below, every
module carries its tier: `*` = in the standard tier, blank = not.

They are orthogonal on purpose. The compiler kernel ships (role: language, tier: standard). The
whole v1 plugin set does not (role: standard library, tier: compatibility) — because the entire v1
lane is the compatibility tier, not because those plugins are somehow less standard. Reading either
axis as the other produces exactly the wrong conclusion in both directions.

**The one place the two axes must agree, and today do:** no module in Part III is in the standard
tier. All 143 are outside it. That is an invariant worth keeping, and §7 gates it.

## 3. Part I — the language (40 modules)

Everything needed to turn `.ssc` text into a running program: front ends, IR, type checker,
backends, runtimes, the CLI, and the SPI that makes Parts II and III possible at all.

The rule of thumb: **if it would still be needed when every library in the repository is deleted, it
is Part I.**

Two fronts and two runtimes coexist here by design, not by accident — `v2/` is the self-hosted
compiler that is now the default lane, and `v1/` is the compatibility lane it replaced. Both are the
language.
`v1/runtime/backend` — 20

```
  v1/runtime/backend/conformance                 scalascript-backend-conformance
  v1/runtime/backend/css                         scalascript-backend-css
  v1/runtime/backend/dap                         scalascript-backend-dap
  v1/runtime/backend/flink                       scalascript-backend-flink
  v1/runtime/backend/html                        scalascript-backend-html
  v1/runtime/backend/interpreter                 scalascript-backend-interpreter
  v1/runtime/backend/interpreter-bench           scalascript-interpreter-bench
  v1/runtime/backend/interpreter-plugin-tests    scalascript-backend-interpreter-plugin-tests
  v1/runtime/backend/interpreter-server          scalascript-backend-interpreter-server
  v1/runtime/backend/js                          scalascript-backend-js
  v1/runtime/backend/jvm                         scalascript-backend-jvm
  v1/runtime/backend/kafka-streams               scalascript-backend-kafka-streams
  v1/runtime/backend/node                        scalascript-backend-node
  v1/runtime/backend/rust                        scalascript-backend-rust
  v1/runtime/backend/scala-source                scalascript-backend-scala-source
  v1/runtime/backend/scalajs                     scalascript-backend-scalajs
  v1/runtime/backend/spark                       scalascript-backend-spark
  v1/runtime/backend/spi                         scalascript-backend-spi
  v1/runtime/backend/test-utils                  scalascript-test-utils
  v1/runtime/backend/wasm                        scalascript-backend-wasm
```

`v2 (kernel)` — 10

```
* v2/backend-jvm-bytecode                        scalascript-v2-jvm-bytecode
  v2/backend/js                                  scalascript-v2-js-backend
  v2/backend/swift                               scalascript-v2-swift-backend
  v2/host/scala/control                          scalascript-control
  v2/interop/descriptor                          scalascript-interop-descriptor
  v2/interop/plugin-profile                      scalascript-plugin-profile
* v2/jvm-runtime                                 scalascript-v2-jvm-runtime
* v2/nativeui                                    scalascript-v2-nativeui
* v2/plugin-spi                                  scalascript-v2-native-plugin-spi
* v2/src                                         scalascript-v2-core
```

`v1/lang` — 6

```
  v1/lang/compiler/driver                        scalascript-compiler-driver
  v1/lang/core                                   scalascript-core
  v1/lang/core-bench                             scalascript-compiler-bench
  v1/lang/interop                                scalascript-interop
  v1/lang/ir                                     scalascript-ir
  v1/lang/value-data                             scalascript-value-data
```

`v1/tools` — 3

```
  v1/tools/cli                                   
  v1/tools/plugin-host                           ssc-plugin-host
  v1/tools/x402-runtime                          scalascript-v21-x402-tools-runtime
```

`v1` — 1

```
  v1/runtime/scalascript-plugin-api              scalascript-plugin-api
```

### Part I is not only sbt projects

`v1/tools/cli` is the `ssc` command; the launchers in `bin/` are generated from
`scripts/launchers/`. The self-hosted fronts are `.ssc` SOURCES, not sbt modules, and they are the
most-edited files in the language:

```
  specs/v2.2-p6.5-fsub.ssc      F — the DEFAULT front (self-hosted)
  v2/lib/ssc1-front.ssc0        the legacy self-hosted front
  v2/lib/ssc1-lower.ssc0        the legacy lowerer
```

A change to either front changes the compiler for every user, so they are Part I in every sense
except that no `build.sbt` line mentions them.

## 4. Part II — the standard library (76 modules)

What a user gets without asking: available to any program, versioned with the toolchain, and not
tied to one domain.

**The v2 side already encodes this in the tree, and the encoding is exact.** Every one of the 21
modules under `v2/runtime/std/` is in the standard-tier allowlist; none of the 5 under
`v2/runtime/providers/` is. 21 in, 5 out, no exceptions. That is the cleanest std/additional
boundary in the repository and the model the rest should follow.

**The v1 side reads the other way and it is not a defect.** 43 modules under `v1/runtime/std/` and
only 3 are in the standard tier, because `v1` as a whole IS the compatibility tier. "std" there
means *the standard library of the legacy lane*, which is a coherent thing to be — but the same
directory word carrying a different tier meaning on the two sides is a genuine trap for a reader.
`v1/runtime/std` — 43

```
  v1/runtime/std/actors-plugin                   scalascript-actors-plugin
  v1/runtime/std/auth-plugin                     scalascript-auth-plugin
  v1/runtime/std/bench-plugin                    scalascript-bench-plugin
  v1/runtime/std/cache-effect-plugin             scalascript-cache-effect-plugin
  v1/runtime/std/clock-effect-plugin             scalascript-clock-effect-plugin
  v1/runtime/std/content-plugin                  scalascript-content-plugin
  v1/runtime/std/deploy-plugin                   scalascript-deploy-plugin
  v1/runtime/std/dstreams-plugin                 scalascript-dstreams-plugin
  v1/runtime/std/env-effect-plugin               scalascript-env-effect-plugin
  v1/runtime/std/fetch-plugin                    scalascript-fetch-plugin
  v1/runtime/std/frontend-plugin                 scalascript-frontend-plugin
  v1/runtime/std/fs-plugin                       scalascript-fs-plugin
  v1/runtime/std/graph-plugin                    scalascript-graph-plugin
  v1/runtime/std/graphql-plugin                  scalascript-graphql-plugin
  v1/runtime/std/http-plugin                     scalascript-http-plugin
  v1/runtime/std/json-plugin                     scalascript-json-plugin
  v1/runtime/std/logger-effect-plugin            scalascript-logger-effect-plugin
* v1/runtime/std/markup-core                     scalascript-markup-core
  v1/runtime/std/markup-js                       scalascript-markup-js
  v1/runtime/std/markup-node                     scalascript-markup-node
  v1/runtime/std/mcp-plugin                      scalascript-mcp-plugin
  v1/runtime/std/mime-plugin                     scalascript-mime-plugin
  v1/runtime/std/nfc-plugin                      scalascript-nfc-plugin
  v1/runtime/std/oauth-plugin                    scalascript-oauth-plugin
  v1/runtime/std/os-plugin                       scalascript-os-plugin
  v1/runtime/std/pdf-plugin                      scalascript-pdf-plugin
  v1/runtime/std/pwa-plugin                      scalascript-pwa-plugin
  v1/runtime/std/random-effect-plugin            scalascript-random-effect-plugin
  v1/runtime/std/remote-plugin                   scalascript-remote-plugin
  v1/runtime/std/request-plugin                  scalascript-request-plugin
  v1/runtime/std/retry-effect-plugin             scalascript-retry-effect-plugin
  v1/runtime/std/scljet-jdbc-plugin              scalascript-scljet-jdbc-plugin
* v1/runtime/std/scljet-vfs-host                 scalascript-scljet-vfs-host
  v1/runtime/std/scljet-vfs-plugin               scalascript-scljet-vfs-plugin
  v1/runtime/std/smtp-plugin                     scalascript-smtp-plugin
  v1/runtime/std/sql-plugin                      scalascript-sql-plugin
  v1/runtime/std/state-effect-plugin             scalascript-state-effect-plugin
  v1/runtime/std/streams-plugin                  scalascript-streams-plugin
  v1/runtime/std/swing-plugin                    scalascript-swing-plugin
  v1/runtime/std/tcp-plugin                      scalascript-tcp-plugin
  v1/runtime/std/uuid-plugin                     scalascript-uuid-plugin
  v1/runtime/std/ws-plugin                       scalascript-ws-plugin
* v1/runtime/std/yaml-plugin                     scalascript-yaml-plugin
```

`v2/runtime/std` — 21

```
* v2/runtime/std/actors-plugin                   scalascript-v2-native-actors-plugin
* v2/runtime/std/content-plugin                  scalascript-v2-native-content-plugin
* v2/runtime/std/crypto-plugin                   scalascript-v2-native-crypto-plugin
* v2/runtime/std/dataset-plugin                  scalascript-v2-native-dataset-plugin
* v2/runtime/std/distributed-plugin              scalascript-v2-native-distributed-plugin
* v2/runtime/std/effect-runners-plugin           scalascript-v2-native-effect-runners-plugin
* v2/runtime/std/fs-plugin                       scalascript-v2-native-fs-plugin
* v2/runtime/std/generator-plugin                scalascript-v2-native-generator-plugin
* v2/runtime/std/graph-plugin                    scalascript-v2-native-graph-plugin
* v2/runtime/std/host-plugin                     scalascript-v2-native-host-plugin
* v2/runtime/std/http-fast-plugin                scalascript-v2-native-http-fast-plugin
* v2/runtime/std/json-plugin                     scalascript-v2-native-json-plugin
* v2/runtime/std/optics-plugin                   scalascript-v2-native-optics-plugin
* v2/runtime/std/os-plugin                       scalascript-v2-native-os-plugin
* v2/runtime/std/reactive-plugin                 scalascript-v2-native-reactive-plugin
* v2/runtime/std/scljet-vfs-plugin               scalascript-v2-native-scljet-vfs-plugin
* v2/runtime/std/sql-plugin                      scalascript-v2-native-sql-plugin
* v2/runtime/std/state-effect-plugin             scalascript-v2-native-state-effect-plugin
* v2/runtime/std/storage-effect-plugin           scalascript-v2-native-storage-effect-plugin
* v2/runtime/std/ui-plugin                       scalascript-v2-native-ui-plugin
* v2/runtime/std/yaml-plugin                     scalascript-v2-native-yaml-plugin
```

`v1/runtime/http-server` — 7

```
  v1/runtime/http-server/common                  scalascript-runtime-server-common
* v1/runtime/http-server/fast-engine             scalascript-http-fast-engine
  v1/runtime/http-server/jvm                     scalascript-runtime-server-jvm
  v1/runtime/http-server/jvm-fast                scalascript-runtime-server-jvm-fast
  v1/runtime/http-server/jvm-jetty               scalascript-runtime-server-jvm-jetty
  v1/runtime/http-server/jvm-netty               scalascript-runtime-server-jvm-netty
  v1/runtime/http-server/spi                     scalascript-runtime-server-spi
```

`backend` — 4

```
* backend/config                                 scalascript-backend-config-runtime
* backend/sql                                    scalascript-backend-sql-runtime
* backend/sql-js                                 scalascript-backend-sql-runtime-js
* backend/typed-data                             scalascript-backend-typed-data-runtime
```

`v1/lang` — 1

```
* v1/lang/yaml                                   scalascript-yaml
```

### Part II also includes `.ssc` modules with no sbt project

Seven standard-library modules are written in ScalaScript itself and therefore appear in no
`build.sbt` listing. They are imported by path and shipped as sources:

```
  v1/runtime/std/cluster      7 files   membership, sharding, singletons, consul/etcd coordination
  v1/runtime/std/dsl          5 files   AST, builders, passes, pretty-printer, walker
  v1/runtime/std/mapreduce    8 files   dataset, shuffle, handler registry, failure handling
  v1/runtime/std/mcp          4 files   Model Context Protocol client and server
  v1/runtime/std/parsing      5 files   parser combinators, layout, error recovery
  v1/runtime/std/scljet      23 files   the pure-.ssc SQLite engine (b-tree, pager, WAL, SQL)
  v1/runtime/std/ui          21 files   the declarative UI toolkit
```

`v1/runtime/std/scljet` is a **tracked symlink**, one of only two in the repository (the other is
`runtime -> v1/runtime`).

## 5. Part III — additional libraries (143 modules)

Domain libraries. None of them ships in the standard tier; a program opts in.

The dominant group by an order of magnitude is `payments/` (99 modules) — processors, wallets, the
x402 protocol stack, blockchains, micropayments, compliance, tax and FX.
`payments/processors` — 22

```
  payments/processors/ach                        scalascript-payments-ach
  payments/processors/adyen                      scalascript-payments-adyen
  payments/processors/au-npp                     scalascript-payments-au-npp
  payments/processors/braintree                  scalascript-payments-braintree
  payments/processors/ca-eft                     scalascript-payments-ca-eft
  payments/processors/checkout                   scalascript-payments-checkout
  payments/processors/fednow                     scalascript-payments-fednow
  payments/processors/india-upi                  scalascript-payments-india-upi
  payments/processors/japan-zengin               scalascript-payments-japan-zengin
  payments/processors/mock                       scalascript-payments-mock
  payments/processors/mx-spei                    scalascript-payments-mx-spei
  payments/processors/paypal                     scalascript-payments-paypal
  payments/processors/pix                        scalascript-payments-pix
  payments/processors/sepa                       scalascript-payments-sepa
  payments/processors/sg-paynow                  scalascript-payments-sg-paynow
  payments/processors/spi                        scalascript-payments-plugin
  payments/processors/square                     scalascript-payments-square
  payments/processors/stripe                     scalascript-payments-stripe
  payments/processors/swift                      scalascript-payments-swift
  payments/processors/uk-bacs                    scalascript-payments-uk-bacs
  payments/processors/uk-chaps                   scalascript-payments-uk-chaps
  payments/processors/uk-fps                     scalascript-payments-uk-fps
```

`payments/wallet` — 22

```
  payments/wallet/connect                        scalascript-wallet-connect
  payments/wallet/connector-eip1193              scalascript-wallet-connector-eip1193
  payments/wallet/connector-wallet-std           scalascript-wallet-connector-wallet-std
  payments/wallet/spi                            scalascript-wallet-spi
  payments/wallet/strategy-eoa                   scalascript-wallet-strategy-eoa
  payments/wallet/strategy-erc4337               scalascript-wallet-strategy-erc4337
  payments/wallet/vault-encrypted                scalascript-wallet-vault-encrypted
  payments/wallet/vault-ledger                   scalascript-wallet-vault-ledger
  payments/wallet/vault-ledger-bitcoin           scalascript-wallet-vault-ledger-bitcoin
  payments/wallet/vault-ledger-bluetooth-js      scalascript-wallet-vault-ledger-bluetooth-js
  payments/wallet/vault-ledger-cardano           scalascript-wallet-vault-ledger-cardano
  payments/wallet/vault-ledger-ethereum          scalascript-wallet-vault-ledger-ethereum
  payments/wallet/vault-ledger-js                scalascript-wallet-vault-ledger-js
  payments/wallet/vault-ledger-jvm               scalascript-wallet-vault-ledger-jvm
  payments/wallet/vault-ledger-solana            scalascript-wallet-vault-ledger-solana
  payments/wallet/vault-mpc                      scalascript-wallet-vault-mpc
  payments/wallet/vault-trezor                   scalascript-wallet-vault-trezor
  payments/wallet/wallet-vault-mpc-coinbase      scalascript-wallet-vault-mpc-coinbase
  payments/wallet/wallet-vault-mpc-fireblocks    scalascript-wallet-vault-mpc-fireblocks
  payments/wallet/wallet-vault-mpc-frost         scalascript-wallet-vault-mpc-frost
  payments/wallet/wallet-vault-mpc-lit           scalascript-wallet-vault-mpc-lit
  payments/wallet/wallet-vault-mpc-zengo         scalascript-wallet-vault-mpc-zengo
```

`payments` — 17

```
  payments/bank-rails                            scalascript-payments-bank-rails
  payments/compliance                            payments-compliance
  payments/compliance-chainalysis                payments-compliance-chainalysis
  payments/compliance-complyadvantage            payments-compliance-complyadvantage
  payments/compliance-mock                       payments-compliance-mock
  payments/fx                                    payments-fx
  payments/fx-ecb                                payments-fx-ecb
  payments/fx-openexchangerates                  payments-fx-openexchangerates
  payments/money                                 scalascript-payments-money
  payments/payment-request                       scalascript-payment-request
  payments/tax                                   payments-tax
  payments/tax-avalara                           payments-tax-avalara
  payments/tax-stripe                            payments-tax-stripe
  payments/tax-taxjar                            payments-tax-taxjar
  payments/webhook                               scalascript-payments-webhook
  payments/webhook-postgres                      scalascript-payments-webhook-postgres
  payments/webhook-redis                         scalascript-payments-webhook-redis
```

`payments/x402` — 13

```
  payments/x402/client                           scalascript-x402-client
  payments/x402/client-js                        scalascript-x402-client-js
  payments/x402/core                             scalascript-x402-core
  payments/x402/escrow-plutus                    scalascript-x402-escrow-plutus
  payments/x402/facilitator-cardano              scalascript-x402-facilitator-cardano
  payments/x402/facilitator-cardano-scalus       scalascript-x402-facilitator-cardano-scalus
  payments/x402/facilitator-coinbase             scalascript-x402-facilitator-coinbase
  payments/x402/facilitator-evm                  scalascript-x402-facilitator-evm
  payments/x402/nonce-postgres                   scalascript-x402-nonce-postgres
  payments/x402/nonce-redis                      scalascript-x402-nonce-redis
  payments/x402/queue-kafka                      scalascript-x402-queue-kafka
  payments/x402/queue-postgres                   scalascript-x402-queue-postgres
  payments/x402/server                           scalascript-x402-server
```

`frontend` — 12

```
  frontend/core                                  scalascript-frontend-core
  frontend/custom                                scalascript-frontend-custom
  frontend/electron                              scalascript-frontend-electron
  frontend/examples                              scalascript-frontend-examples
  frontend/javafx                                scalascript-frontend-javafx
  frontend/react                                 scalascript-frontend-react
  frontend/solid                                 scalascript-frontend-solid
  frontend/swiftui                               scalascript-frontend-swiftui
  frontend/swing                                 scalascript-frontend-swing
  frontend/toolkit                               scalascript-frontend-toolkit
  frontend/tui                                   scalascript-frontend-tui
  frontend/vue                                   scalascript-frontend-vue
```

`backend` — 9

```
  backend/graph                                  scalascript-backend-graph-runtime
  backend/kafka                                  scalascript-client-kafka
  backend/logger                                 scalascript-logger
  backend/postgres                               scalascript-client-postgres
  backend/redis                                  scalascript-client-redis
  backend/sql-aws                                scalascript-sql-aws
  backend/sql-azure                              scalascript-sql-azure
  backend/sql-gcp                                scalascript-sql-gcp
  backend/wire                                   scalascript-wire-core
```

`gov` — 8

```
  gov/bureau-core                                scalascript-bureau-core
  gov/bureau-eu                                  scalascript-bureau-eu
  gov/bureau-mock                                scalascript-bureau-mock
  gov/bureau-pl-fiscal                           scalascript-bureau-pl-fiscal
  gov/bureau-pl-registry                         scalascript-bureau-pl-registry
  gov/bureau-pl-social                           scalascript-bureau-pl-social
  gov/bureau-scheduler                           scalascript-bureau-scheduler
  gov/bureau-signing                             scalascript-bureau-signing
```

`payments/micropayment` — 8

```
  payments/micropayment/channel-evm              scalascript-micropayment-channel-evm
  payments/micropayment/client                   scalascript-micropayment-client
  payments/micropayment/hashchain                scalascript-micropayment-hashchain
  payments/micropayment/hydra                    scalascript-micropayment-hydra
  payments/micropayment/probabilistic            scalascript-micropayment-probabilistic
  payments/micropayment/server                   scalascript-micropayment-server
  payments/micropayment/spi                      scalascript-micropayment-spi
  payments/micropayment/threshold                scalascript-micropayment-threshold
```

`payments/blockchain` — 7

```
  payments/blockchain/bitcoin                    scalascript-blockchain-bitcoin
  payments/blockchain/cardano                    scalascript-blockchain-cardano
  payments/blockchain/cosmos                     scalascript-blockchain-cosmos
  payments/blockchain/evm                        scalascript-blockchain-evm
  payments/blockchain/evm-abi                    scalascript-blockchain-evm-abi
  payments/blockchain/solana                     scalascript-blockchain-solana
  payments/blockchain/spi                        scalascript-blockchain-spi
```

`payments/crypto` — 5

```
  payments/crypto/bouncycastle                   scalascript-crypto-bouncycastle
  payments/crypto/frost                          scalascript-crypto-frost
  payments/crypto/noble-js                       scalascript-crypto-noble-js
  payments/crypto/plugin                         scalascript-crypto-plugin
  payments/crypto/spi                            scalascript-crypto-spi
```

`v2/runtime/providers` — 5

```
  v2/runtime/providers/graph-rdf4j-plugin        scalascript-v2-native-graph-rdf4j-plugin
  v2/runtime/providers/mcp-plugin                scalascript-v2-native-mcp-plugin
  v2/runtime/providers/nfc-plugin                scalascript-v2-native-nfc-plugin
  v2/runtime/providers/pdf-plugin                scalascript-v2-native-pdf-plugin
  v2/runtime/providers/swift-plugin              scalascript-v2-native-swift-plugin
```

`payments/client` — 4

```
  payments/client/blockfrost                     scalascript-client-blockfrost
  payments/client/coinbase                       scalascript-client-coinbase
  payments/client/evm                            scalascript-client-evm
  payments/client/solana                         scalascript-client-solana
```

`uniml` — 7

```
  uniml/address                                  scalascript-uniml-address
  uniml/core                                     scalascript-uniml
  uniml/json                                     scalascript-uniml-json
  uniml/markdown                                 scalascript-uniml-markdown
  uniml/markdown/bridge                          scalascript-uniml-markdown-bridge
  uniml/xml                                      scalascript-uniml-xml
  uniml/yaml                                     scalascript-uniml-yaml
```

`mcp` — 3

```
  mcp/common                                     scalascript-mcp-common
  mcp/wallet                                     scalascript-mcp-wallet
  mcp/x402                                       scalascript-mcp-x402
```

`payments/payment-request` — 1

```
  payments/payment-request/plugin                scalascript-payment-request-plugin
```

## 6. What is in none of the three parts

Not every directory is a module, and pretending otherwise is how a partition turns into a lie.

**Apparatus — the toolchain's own infrastructure.** It builds and checks Parts I-III without being
any of them. `tests/fixtures/modules.tsv` already treats these as modules for BOARD ownership, which
is a different question from role and stays as it is.

```
  tests/               the harness      tests/conformance/  the corpus and its paired freeze
  scripts/             build, CI and coordination tooling
  specs/               specifications (669 tracked files)
  bin/                 generated launchers — NOT a source directory
  bench/               benchmarks
  .work/               claims and the ledger
```

**Product of the project, not part of it:** `examples/` (399 files), `docs/`, `site/`, `registry/`,
`releases/`.

**Fossils — the previous layout, never removed.** The repository was reorganised under `v1/` and the
old top-level tree stayed behind:

```
  lang/     2,540 files, 0 tracked   only target/ .bsp/ .scala-build/ + one stray JFR profile
  tools/       29 files, 0 tracked   only .bsp/ .scala-build/
```

TWO MORE LOOKED LIKE FOSSILS AND ARE NOT, which the gate found within minutes of being written by
going red on a checkout that had just been cleaned. `conformance/` and `scalascript/` come BACK:
they hold `.scala-build/` output and a stray `codegen/JsGen.class` — a tool writing to a RELATIVE
path from the wrong working directory, not a leftover of an old layout. They are §8.6, and the gate
deliberately does not check them: it would flap until the writer is fixed, and a flapping gate is
one people learn to ignore.

They were untracked build output, so git never saw them — but `ls` at the repository root did, and
they shadowed the real `v1/lang` and `v1/tools`. Nothing in the build resolved them: the only
tracked references are three stale COMMENTS (`build.sbt:2318`, `project/plugins.sbt:10`,
`scripts/BACKLOG.md:785`) that still name `tools/plugin-host` and `tools/cli` at the old paths.

**Removed 2026-07-31** from the main checkout (5,873 files, none tracked, `git status` unchanged
before and after). `tests/e2e/project-partition-gate.sh` now refuses them, so they cannot creep
back unnoticed. The three stale comments are left alone — they are comments, and rewriting them
touches `build.sbt`, which belongs to a different claim.

## 7. Invariants that are true today

Measured, not asserted. §8 proposes gating them; today they hold:

| # | invariant | today |
|---|---|---|
| 1 | no Part III module is in the standard tier | 143 / 143 hold |
| 2 | every `v2/runtime/std/*` module is in the standard tier | 21 / 21 hold |
| 3 | no `v2/runtime/providers/*` module is in the standard tier | 5 / 5 hold |
| 4 | every standard-tier prefix resolves to a repo module or a named third-party JAR | 35 modules + 10 external |
| 5 | every sbt project falls in exactly one part | 259 + the aggregate root |

Invariant 1 is the one that matters: it is what "additional" MEANS, and it is currently free —
nothing enforces it, so the next domain library added to the allowlist would break the partition
silently.

## 8. Where the tree disagrees with itself

Findings, ordered by how much they cost a reader. None is fixed by this document.

**8.1 `backend/` means two different things.** `v1/runtime/backend/*` are COMPILER backends (`int`,
`js`, `jvm`, `rust`, `wasm`, …). Top-level `backend/*` are DATA and SERVICE backends (`sql`,
`redis`, `postgres`, `kafka`, `graph`, `wire`, `logger`). Same word, unrelated meanings, and the two
sets even split across parts — four of the top-level ones are standard library, nine are additional.

**8.2 Four modules under `backend/` are standard library and nine are not**, with nothing in the
path to say which: `backend/config`, `backend/sql`, `backend/sql-js` and `backend/typed-data` are in
the allowlist; `backend/graph`, `backend/kafka`, `backend/logger`, `backend/postgres`,
`backend/redis`, `backend/sql-aws`, `backend/sql-azure`, `backend/sql-gcp` and `backend/wire` are
not. On the v2 side the same distinction is a directory name.

**8.3 UniML sat on both sides of the `v1/` line, and the placement was INVERTED for four of its
seven modules.** UniML is a standalone lossless token→tree framework; it is not the language's parser
infrastructure and must not be tied to a language version. Measured — transitive `dependsOn` closure,
asking whether any `v1/lang/*` or `v1/runtime/*` module is reachable:

| module | was | depends on v1? | placement |
|---|---|---|---|
| `uniml/core` | outside `v1/` | no | correct |
| `uniml/json` | outside `v1/` | no | correct |
| `uniml/markdown` | outside `v1/` | **yes** — `v1/lang/{core,ir,value-data,yaml}` | **inverted** |
| `uniml/yaml` | outside `v1/` | **yes** — same four | **inverted** |
| `v1/lang/uniml-address` | inside `v1/` | no | **inverted** → moved to `uniml/address` |
| `v1/lang/uniml-xml` | inside `v1/` | no | **inverted** → moved to `uniml/xml` |
| `v1/lang/uniml-markdown-bridge` | inside `v1/` | yes | moved to `uniml/markdown/bridge` |

The directory said nothing about the dependency, and where it said anything it was wrong.

**ALL SEVEN NOW LIVE UNDER `uniml/`, and `v1/lang/` holds nothing of UniML.** The grouping rule is
BY LIBRARY, not by dependency — a decision worth stating because the bridge disproves the simpler
rule: `uniml/markdown/bridge` genuinely depends on `v1/lang/{core,ir,value-data,yaml}`, and it lives
with the library it belongs to anyway. Its name says what it bridges and this document records the
dependency; the path is not asked to carry that.

Nesting it inside `uniml/markdown/` is safe because that project is `CrossType.Pure` with its
sources under `uniml/markdown/src/` — verified, `unimlMarkdown/Compile/sources` contains zero files
from `markdown/bridge/`, so the child cannot leak into the parent.

TWO STILL REACH INTO `v1/` AND THAT IS NOT FIXED BY MOVING THEM. `uniml/markdown` and `uniml/yaml`
were already outside `v1/`; the defect is the dependency itself, on `v1/lang/core`, `v1/lang/ir`,
`v1/lang/value-data` and `v1/lang/yaml`. Cutting it is real work and is not attempted here — and
after this move the tree no longer hints at it at all, so the dependency table above is the only
place it is written down.

`v1/lang/yaml` is the same shape once removed: a YAML library that ships in the standard tier and
lives in the language tree. Nothing depends on its location; it is left alone because it is
`markupCore`'s and UniML's dependency and moving it belongs with the 8.2 sweep.

The same inversion exists one directory over: `v1/runtime/std/markup-core` has NO v1 dependency
either, while its siblings `markup-js` and `markup-node` do.

**8.4 The fossil trees.** §6. Cheap to remove and the only finding here that costs nothing to fix.

**8.5 `v1/runtime/http-server/*` is split across tiers within one group.** `fast-engine` ships;
`common`, `spi`, `jvm`, `jvm-fast`, `jvm-jetty`, `jvm-netty` do not. The jetty and netty ones are
swappable providers and read as Part III by function, but they sit inside a Part II group.

**8.6 Two directories at the root are written by a tool with the wrong working directory.**
`conformance/` fills with `.scala-build/conformance_<hash>/` and `scalascript/` with a compiled
`codegen/JsGen.class`. Both are untracked, both reappear minutes after deletion, and neither
corresponds to a source tree — `tests/conformance/` and `v2/conformance/` are the real ones. Finding
the writer is a small investigation nobody has done; until then the root gains two directories that
read as modules and are not.

**Other small strays at the root:** `TASK/v2-perfomance.md` (one file, and a typo in the name),
`using/uuid-plugin.md` (one file), `arith-loop-rust/` (6 files, a generated Rust sample),
`scratch/` (11 files).

## 9. What would make this stick

In risk order. The first two are cheap and local; the third is a flag day and must not be started
while other agents hold worktrees.

1. ~~**Gate the invariants of §7**~~ — done: `tests/e2e/project-partition-gate.sh`, four checks with
   a `--self-test` that plants each defect and proves each is caught. Still to do: register it in
   `scripts/smoke-ci.ssc`, which is held by claim `smoke-budget-drift`.
2. ~~**Delete the fossils**~~ — done for `lang/` and `tools/`, §6. The three stale comments remain,
   and `conformance/`/`scalascript/` need their writer found (§8.6) rather than deleting.
3. **Resolve 8.1-8.3 by moving directories** — this rewrites `build.sbt`, every claim `paths:`,
   every `fixed-in:` path reference and every open worktree. It is a coordinated flag day, not a
   background tidy, and it should be one directory group at a time with the gate from (1) already
   in place to prove nothing changed tier.
