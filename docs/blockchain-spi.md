# Blockchain SPI — Plan

Status: **draft / planning**. Source of truth for the chain-abstraction
layer that sits below both [`wallet-spi`](wallet-spi.md) and `x402-*`.
Until each phase below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this design.

## 1. Goals

- **One contract, many chains.** What "address", "transaction",
  "signature", "asset balance", "broadcast", and "receipt" mean is
  defined once at the SPI level and implemented per chain.
- **Both wallet and x402 consume the same abstraction.** No more
  duplicated EIP-712 hashing in two places; no more ad-hoc ABI
  encoding inside `EvmFacilitator`; no more chain-specific branches
  in `x402-core` (the current `PaymentScheme.CardanoExact` exception
  becomes just another chain adapter).
- **Free multi-chain support for x402.** Adding a new chain means
  shipping one `blockchain-<chain>` module — x402 starts accepting
  payments on that chain with no changes to `x402-core` /
  `x402-server` / `x402-client`.
- **Replaceable on every axis.** A chain implementation can be swapped
  (e.g. a faster Bitcoin implementation, or a TEE-backed EVM signer)
  without touching higher layers.
- **Replaces the current ad-hoc state.** Today `EvmFacilitator` calls
  `EvmClient.erc20Balance` directly and hand-codes selector bytes
  (`0x70a08231` for `balanceOf`). After this SPI lands, the
  facilitator depends only on `blockchain-spi` and does not know it's
  talking to an EVM chain.

## 2. Non-goals

- We are **not** writing a single mega-trait that handles every chain
  feature ever. Chain-specific features stay in chain-specific
  adapters, exposed through `ChainAdapter#chainSpecific` escape
  hatches when needed.
- We are **not** implementing all chains in one phase. The SPI lands
  with `blockchain-evm` only; Solana, Bitcoin, Cosmos follow.
- We are **not** writing a transaction indexer / block explorer / gas
  oracle. Those are future clients of this SPI.
- We are **not** owning RPC connection management or rate-limit
  policy. `ChainContext` is the abstraction; concrete connection
  pools belong elsewhere.

## 3. Where this SPI sits

```
              ┌─────────────────────────────────────────────┐
              │  wallet-spi   |   x402-core                 │  ← clients
              │  (Vault,      |   (PaymentRequirements,     │
              │   RawSigner,  |    PaymentPayload,           │
              │   Strategy)   |    Facilitator)              │
              └────────────────────┬────────────────────────┘
                                   │
                       depend on ──┤
                                   ▼
              ┌─────────────────────────────────────────────┐
              │             blockchain-spi                  │  ← this document
              │   ChainAdapter / ChainId / Asset / Address  │
              │   TypedData / SignatureFormat / recover /   │
              │   queries (balance / nonce / receipt)       │
              └────────────────────┬────────────────────────┘
                                   │
                       depend on ──┤
                                   ▼
              ┌─────────────────────────────────────────────┐
              │   blockchain-evm     blockchain-solana  …   │  ← per-chain impls
              └────────────────────┬────────────────────────┘
                                   │
                                   ▼
              ┌─────────────────────────────────────────────┐
              │   crypto-spi  (sign / verify / hash / HD)   │  ← lowest layer
              │   └ crypto-bouncycastle  /  crypto-noble-js │
              └─────────────────────────────────────────────┘
```

Two strict invariants:

1. **No upward dependency.** `blockchain-spi` does not depend on
   `wallet-spi` or `x402-*`. The chain abstraction is the foundation;
   it does not know its clients.
2. **No cross-chain dependency.** `blockchain-evm` does not depend on
   `blockchain-solana`. Each chain is self-contained behind the
   shared SPI.

## 4. Module layout

```
crypto-spi                # curve / hash / KDF / AEAD / HD — cross-compile
crypto-bouncycastle       # JVM default impl
crypto-noble-js           # Scala.js impl (later phase)

blockchain-spi            # this SPI — cross-compile
blockchain-evm            # one impl per CAIP-2 family
blockchain-solana         # ed25519, base58 addrs, versioned tx
blockchain-bitcoin        # UTXO, PSBT, P2WPKH
blockchain-cosmos         # bech32, sign_doc, IBC-aware
blockchain-cardano        # CIP-8, lovelace, native assets
```

Cross-compile model mirrors `runtime-server-spi` / `runtime-server-jvm`
+ `runtime-server-jvm-jetty` / `-netty`. Discovery via
`ServiceLoader` on JVM, static registry on Scala.js (open question §13.1
in `wallet-spi.md` applies here too — resolved once for both SPIs).

## 5. Core types

```scala
package scalascript.blockchain.spi

/** CAIP-2: chain namespace + reference. */
case class ChainId(caip2: String):
  def namespace: String = caip2.split(":")(0)   // "eip155", "solana", "bip122", …
  def reference: String = caip2.split(":")(1)

/** CAIP-10: account = ChainId + address. */
case class AccountId(chain: ChainId, address: String):
  def caip10: String = s"${chain.caip2}:$address"

/** Asset metadata, chain-agnostic. */
case class Asset(
  chain:    ChainId,
  address:  String,          // contract address; native sentinel ("native") for ETH / SOL / BTC
  symbol:   String,
  decimals: Int,
)

/** Typed-data envelope. EVM payload is EIP-712; Cardano is CIP-8;
 *  Solana / Cosmos use sign-doc forms. The adapter knows how to hash
 *  this into a digest. */
sealed trait TypedData
object TypedData:
  case class Eip712(
    domain: Map[String, ujson.Value],
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, ujson.Value],
    primaryType: String,
  ) extends TypedData

  case class CosmosSignDoc(bytes: Array[Byte])              extends TypedData
  case class Cip8(payload: Array[Byte], headers: Array[Byte]) extends TypedData
  case class Raw(bytes: Array[Byte])                        extends TypedData

/** Chain-side error model. Chain-specific subtypes allowed; this is
 *  what facilitators / wallets pattern-match against. */
sealed trait ChainError
object ChainError:
  case class Network(message: String)        extends ChainError
  case class InsufficientFunds(have: BigInt, need: BigInt) extends ChainError
  case class InvalidSignature(reason: String) extends ChainError
  case class TxRejected(reason: String)      extends ChainError
  case class Other(message: String)          extends ChainError
```

## 6. ChainAdapter trait

```scala
package scalascript.blockchain.spi

import scala.concurrent.Future
import scalascript.wallet.crypto.{Curve, HashAlgo, PublicKey}

trait ChainAdapter:
  type Tx
  type SignedTx

  // ── identity ─────────────────────────────────────────────────────
  def chainId: ChainId
  def supportedCurves: Seq[Curve]
  def defaultDerivationPath: String

  // ── addresses ────────────────────────────────────────────────────
  def addressFromPublicKey(pk: PublicKey): String
  def isValidAddress(s: String): Boolean
  def normalizeAddress(s: String): String     // EIP-55 checksum, bech32 lowercase, …

  // ── typed-data hashing ───────────────────────────────────────────
  /** Compute the digest that must be signed for a TypedData value.
   *  For EVM this is `keccak256(0x19 0x01 domainSeparator structHash)`. */
  def typedDataDigest(data: TypedData): Array[Byte]

  // ── signature ↔ signer ───────────────────────────────────────────
  /** Recover the signer's address from a signature over a digest.
   *  For EVM this is ecrecover (sig is r||s||v). Used by facilitators
   *  to validate a payment authorization. */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String]

  /** Verify that `signature` (over `digest`) was produced by the
   *  holder of `expected`. Default impl: recoverAddress == expected. */
  def verifySignature(digest: Array[Byte], signature: Array[Byte], expected: String): Boolean =
    recoverAddress(digest, signature).exists(_.equalsIgnoreCase(expected))

  // ── transactions ─────────────────────────────────────────────────
  def buildTransaction(intent: TxIntent, ctx: ChainContext): Future[Tx]
  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload
  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx
  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash]
  def describe(tx: Tx): TxDescription          // human-readable for UI / logs

  // ── queries ──────────────────────────────────────────────────────
  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt]
  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt]
  def nonceOf(address: String, ctx: ChainContext): Future[BigInt]
  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]]
  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt]

  // ── contract reads ──────────────────────────────────────────────
  /** Read-only contract execution. On EVM this is `eth_call`; on
   *  Solana this is a simulated program invoke; on Cardano this is
   *  script-context evaluation. Returns raw bytes — the caller is
   *  responsible for decoding (typically via the ABI codec for that
   *  chain's contract model). */
  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]]

  /** Predict the address a Deploy intent would produce. EVM CREATE:
   *  derives from sender + nonce. EVM CREATE2: from deployer + salt
   *  + codehash. Solana: PDA from seeds. */
  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String]

case class SigningPayload(bytes: Array[Byte], hash: HashAlgo)
case class TxHash(value: String)
case class TxReceipt(hash: TxHash, success: Boolean, blockNumber: BigInt, gasUsed: BigInt)
case class TxDescription(summary: String, fields: Map[String, String])

sealed trait TxIntent
object TxIntent:
  case class NativeTransfer(to: String, amount: BigInt)                                   extends TxIntent
  case class TokenTransfer(asset: Asset, to: String, amount: BigInt)                      extends TxIntent
  case class ContractCall(target: String, calldata: Array[Byte], value: BigInt = 0)       extends TxIntent
  /** Specifically the EIP-3009 / x402 settlement path. The adapter
   *  knows how to encode it; clients don't need to. */
  case class TokenTransferAuthorized(
    asset:       Asset,
    from:        String,
    to:          String,
    amount:      BigInt,
    validAfter:  BigInt,
    validBefore: BigInt,
    nonce:       Array[Byte],
    signature:   Array[Byte],
  ) extends TxIntent

  /** Deploy a contract. `bytecode` is the chain-native binary
   *  (EVM bytecode, UPLC for Cardano, BPF for Solana, WASM for
   *  WASM chains). `args` are ABI-encoded constructor arguments
   *  (chain-specific encoding). `salt = Some(_)` selects CREATE2
   *  on EVM (counterfactual address). */
  case class Deploy(
    bytecode: Array[Byte],
    args:     Array[Byte]         = Array.empty,
    salt:     Option[Array[Byte]] = None,
  ) extends TxIntent

trait ChainContext:
  def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value]
  def nowSeconds: Long
```

`Tx` and `SignedTx` are path-dependent: each adapter keeps its native
representation (`EvmTx`, `SolanaTx`, `BtcTx`) but generic code flows
through `ChainAdapter` polymorphically.

### 6.1 Smart contract interaction

Three primitives in the trait above carry the full interaction surface:

| Operation | Primitive |
|---|---|
| Read state from a deployed contract | `ChainAdapter.call(target, calldata)` |
| Send tx invoking a contract function | `TxIntent.ContractCall(target, calldata, value)` |
| Deploy a new contract | `TxIntent.Deploy(bytecode, args, salt)` |
| Sign off-chain payload for on-chain verify | `ChainAdapter.typedDataDigest(TypedData.Eip712(...))` |
| Recover signer from a contract-verified signature | `ChainAdapter.recoverAddress(digest, sig)` |
| ERC-20-style token balance (read shortcut) | `ChainAdapter.tokenBalance(asset, holder)` |
| ERC-3009 / x402 payment authorization | `TxIntent.TokenTransferAuthorized(...)` |

#### Ergonomic typed proxies

For the ten or so universally-deployed contract interfaces (ERC-20 /
721 / 1155, Permit / Permit2, Multicall3, ERC-4337 EntryPoint), the
per-chain adapter module ships typed proxies built on these
primitives:

```scala
val usdc = blockchain.evm.Erc20(address = "0x833…", chain = baseEvm)
val balance: BigInt = await(usdc.balanceOf("0xAlice..."))
val tx = await(adapter.buildTransaction(
  usdc.transfer.intent(to = "0xBob...", amount = BigInt(1_000_000)),
  ctx,
))
```

Each proxy is a thin object over `Read[A]` / `TxIntent.ContractCall`
— no reflection, no codegen, hand-written, audited.

#### ABI codec submodule

Beyond the well-known interfaces, the ABI codec lives in a
dedicated sub-module so it's auditable on its own and reusable by
non-RPC contexts (e.g. compile-time codegen, tx introspection):

```
blockchain-evm-abi    # cross-compile; pure-Scala Solidity ABI v2 codec.
                      # Encodes / decodes:
                      #   uint*, int*, bool, address, bytes, bytesN,
                      #   string, T[], (T1, T2, …), nested dynamic structs.
                      # Function selector helper: keccak256(sig)[0..4].
```

The codec is **pure** — no chain access, no async, no Future. Safe
to use from contract authoring stacks (see
[`docs/smart-contracts.md`](smart-contracts.md)) as well.

Solana's analog (Borsh) and Cardano's analog (PlutusData CBOR) live
in `blockchain-solana-borsh` and `blockchain-cardano-plutus` when
those adapters land.

#### Event log decoding

`TxReceipt.logs` carries raw `(address, topics, data)` triples.
Typed events:

```scala
val transfers: Seq[Erc20.TransferEvent] =
  Erc20.Transfer.from(receipt.logs)
```

Each typed event has a known `topic0 = keccak256(signature)` and
ABI-decodes the remaining indexed + non-indexed args.

Realtime event subscription (`adapter.subscribe(filter, handler)`
over WS) — Phase 2 of `blockchain-evm`. Not required for x402 or
basic wallet UX.

#### Multicall

EVM's `Multicall3` contract (at the canonical
`0xcA11bde05977b3631167028862bE2a173976CA11` on every major EVM
chain) lets `N` reads execute in one RPC round-trip:

```scala
val results = await(adapter.multicall(Seq(
  usdc.balanceOf.read("0xAlice..."),
  usdc.balanceOf.read("0xBob..."),
  weth.balanceOf.read("0xAlice..."),
)))
```

Single fallback to per-read execution if Multicall3 is unreachable
on a given chain.

#### Smart-contract wallets

ERC-4337 smart accounts **are** contracts. The wallet-spi Phase 6
`SmartAccountStrategy` (see [`wallet-spi.md`](wallet-spi.md) §6.1)
wraps an EVM `ChainAdapter` to use a contract-wallet at the chain
layer. Inside the wrapper, `buildTransaction(ContractCall(...))`
becomes a `UserOperation` packaged for the EntryPoint contract.

#### Authored contracts (.ssc)

Contracts authored in `.ssc` per
[`docs/smart-contracts.md`](smart-contracts.md) integrate here at
deploy and interaction time:

1. `.ssc` source + `kind: contract` front-matter → backend
   (Scalus / WASM / EVM-future) → chain-native bytecode.
2. CLI deploy: `ssc deploy <module> --chain <id>` builds a
   `TxIntent.Deploy(bytecode, args)`, signs via the configured
   wallet-spi `Vault`, broadcasts via the chain's adapter, returns
   the deployed address.
3. Interaction from `.ssc` code: imported contract module exposes
   strongly-typed wrappers that lower to `ChainAdapter.call` (for
   `@view` methods) and `TxIntent.ContractCall` (for `@call`
   methods). The ABI / interface schema is known at compile time
   from the contract's own source.
4. Clear-signing metadata (Ledger): the contract authoring stack
   can emit a clear-signing descriptor at compile time, listing
   parameter names + types + display labels per `@call`. Hardware
   wallets that load this descriptor show structured fields to the
   user when signing tx that invoke the contract.

This makes the contract / wallet / chain triangle reflexive: a
`.ssc` user can author the contract, deploy it, and interact with
it without ever writing ABI JSON by hand or leaving the
ScalaScript toolchain.

#### Non-EVM contract models

Each chain adapter exposes additional `TxIntent` variants that
reflect its native model — these are **chain-specific** and live
in the chain adapter's own module rather than `blockchain-spi`:

- **Solana** — `TxIntent.InvokeProgram(programId, accounts, data)`
  (programs + account inputs/outputs). SPL Token has its typed
  wrapper alongside `Erc20`.
- **Cardano (Plutus)** — UTXO + datum + redeemer; the adapter
  exposes `TxIntent.ConsumeWithRedeemer(...)` and related variants.
  No analog to `ContractCall` because Plutus doesn't have function
  invocations.
- **Bitcoin (Taproot)** — `TxIntent.SpendScriptPath(...)` for
  Taproot script-path spends; the leaf script is plain Bitcoin
  Script.

The top-level `TxIntent` enum keeps just the cross-chain shapes
(`NativeTransfer`, `TokenTransfer`, `ContractCall`, `Deploy`,
`TokenTransferAuthorized`). Chain-specific variants extend
`TxIntent` from their own modules — the `sealed trait` is sealed
within `blockchain-spi`'s file, but Scala 3 allows extension via
case-class membership of the same hierarchy from any module that
imports `TxIntent` (effectively open at the SPI level for the
chain adapters that own each variant).

If the language semantics here are tighter than expected at impl
time (sealed-trait rules), we fall back to a `TxIntent.ChainSpecific(payload: Any)`
escape hatch — flagged in Phase 1 open questions.

## 7. Registry + discovery

```scala
package scalascript.blockchain.spi

object Blockchain:
  /** Look up an adapter by CAIP-2 id. Returns None if no registered
   *  module supports this chain. */
  def lookup(id: ChainId): Option[ChainAdapter]
  def lookupOrThrow(id: ChainId): ChainAdapter
  def all: Seq[ChainAdapter]
  def register(adapter: ChainAdapter): Unit
```

JVM: `META-INF/services/scalascript.blockchain.spi.ChainAdapter` plus
a `BlockchainRegistry.discover()` call that walks ServiceLoader.

Scala.js: chain modules register themselves in a top-level init
block. See open question on registry pattern in
[`wallet-spi.md`](wallet-spi.md) §15.

## 8. blockchain-evm

The first concrete adapter. One module covers **all** EVM chains —
chain id is a constructor parameter, the curve / encoding / signing
logic is shared.

```scala
package scalascript.blockchain.evm

import scalascript.blockchain.spi.*

class EvmChainAdapter(
  val chainId: ChainId,            // "eip155:1", "eip155:8453", …
  val evm:     EvmClient,          // existing read-only JSON-RPC client
) extends ChainAdapter:
  type Tx       = EvmTx
  type SignedTx = EvmSignedTx
  // …

object EvmChainAdapter:
  /** Convenience for the common case. */
  def base(client: EvmClient):        EvmChainAdapter = …
  def baseSepolia(client: EvmClient): EvmChainAdapter = …
  def mainnet(client: EvmClient):     EvmChainAdapter = …
```

What this provides for **wallet** clients:

- `addressFromPublicKey(pk)` — `keccak256(pk.bytes)[12..32]` with
  EIP-55 checksum.
- `typedDataDigest(Eip712(...))` — full EIP-712 hashing:
  `keccak256(0x19 || 0x01 || domainSeparator || structHash)`.
- `buildTransaction(NativeTransfer | TokenTransfer | ContractCall)` —
  RLP encoding for EIP-1559 and legacy txs.
- `broadcast` via `eth_sendRawTransaction`.

What this provides for **x402** facilitators:

- `recoverAddress(digest, sig)` — ecrecover. Lets
  `EvmFacilitator.verify` actually check the signature instead of
  trusting it blindly.
- `tokenBalance(asset, holder)` — replaces hand-coded `0x70a08231`
  selector in `EvmFacilitator`.
- `buildTransaction(TokenTransferAuthorized(...))` — encodes the
  EIP-3009 `transferWithAuthorization(from, to, value, validAfter,
  validBefore, nonce, v, r, s)` call, ready to broadcast. This makes
  the real `EvmFacilitator.settle` possible (today it returns a
  stubbed `0x000…000` tx hash).

## 9. Migration story

### 9.1 What's broken in x402 today

Two distinct bug classes, both addressed by adopting this SPI:

| Where | Bug | Fix path |
|---|---|---|
| `x402-client/X402Client.scala:33-36` | "Address" derivation via `SHA-256(privKey).take(20)` instead of `keccak256(uncompressedPubKey)[12..32]` | `blockchain-evm.addressFromPublicKey` |
| `x402-client/X402Client.scala:38-45` | "Signature" is `SHA-256(stringified fields) + "00"` instead of secp256k1 ECDSA over EIP-712 digest | `crypto-bouncycastle` (sign) + `blockchain-evm.typedDataDigest` (digest); orchestrated by `wallet-spi.EoaStrategy` |
| `x402-facilitator-evm/EvmFacilitator.scala:23-38` | `verify` never checks the signature — only balance + expiry + payTo match | `blockchain-evm.recoverAddress` + `verifySignature` |
| `x402-facilitator-evm/EvmFacilitator.scala:40-43` | Default `settle` returns `0x000…000` as a stub tx hash; no on-chain submission | `blockchain-evm.buildTransaction(TokenTransferAuthorized) + broadcast` |
| `x402-facilitator-evm/EvmFacilitator.scala:32` | Hand-coded `EvmClient.erc20Balance` selector | `blockchain-evm.tokenBalance` |

### 9.2 Public API stability for x402

`PaymentRequirements`, `PaymentPayload`, `Facilitator`, `NonceStore`,
`SettlementQueue`, `X402HttpClient`, `withPayment` middleware — all
keep their current signatures. The `Wallets.privateKey(hex, network)`
factory keeps its signature too; internally it builds an
`EoaStrategy(rawSigner)` over `blockchain-evm`.

Existing `X402ClientTest` and the `examples/x402-*.ssc` scripts
continue to work with no source changes. The signature bytes they
exchange become real ECDSA — fixture-asserted bytes in tests get
updated to known (RFC-6979 deterministic) outputs.

### 9.3 `x402-core.Network` → `ChainId` migration

The current `Network` enum in `x402-core` covers six EVM chains plus
Cardano:

| `Network` case   | CAIP-2          | x402 status today                |
|------------------|------------------|----------------------------------|
| `BaseSepolia`    | `eip155:84532`  | testnet, used by `testConfig`    |
| `Base`           | `eip155:8453`   | production target                |
| `EthereumMainnet`| `eip155:1`      | supported, USDC asset registered |
| `Polygon`        | `eip155:137`    | supported, USDC asset registered |
| `Arbitrum`       | `eip155:42161`  | supported, USDC asset registered |
| `Optimism`       | `eip155:10`     | supported, USDC asset registered |
| (Cardano)        | `cip:mainnet`*  | planned, x402 Phase 6 (separate) |

`*` Cardano lacks a CAIP-2 namespace standard; we use `cip:mainnet` /
`cip:preprod` internally pending CAIP definition.

The enum is **not** removed in Phase 1. Approach:

1. **Phase 1 (this SPI):** `Network` keeps its current constructors.
   Internally `Network.chainId: Int` becomes derivable from a
   `Network.toCaip2: ChainId` mapping. x402-core gains an
   `import scalascript.blockchain.spi.ChainId` and exposes both.
2. **Phase 2 (this SPI):** New code paths (real settle, multi-chain
   facilitator) take `ChainId` directly. Old `Network`-typed APIs
   continue to work as adapters.
3. **Phase 3 (this SPI, Solana lands):** `Network` becomes a `case
   class Network(chain: ChainId)` with the named constants kept as
   `val Base = Network(ChainId("eip155:8453"))` etc. Source-compatible
   for all existing call sites that wrote `Network.Base`.
4. **Phase 6 (this SPI, Cardano):** `PaymentScheme.CardanoExact`
   stays as a scheme, but its `network` field migrates to the same
   `ChainId`-based representation. `x402-facilitator-cardano`
   becomes `blockchain-cardano` + thin x402 glue.

### 9.4 x402 chain coverage during migration

The full x402 chain-by-chain rollout against `blockchain-spi`:

| x402 chain          | When fixed | What lands                                                   |
|---------------------|------------|--------------------------------------------------------------|
| Base                | Phase 1    | sign + recover; `EvmFacilitator` verifies sig + balance      |
| BaseSepolia         | Phase 1    | same as Base — one adapter, different chainId                |
| Ethereum            | Phase 1    | same                                                          |
| Polygon             | Phase 1    | same                                                          |
| Arbitrum            | Phase 1    | same                                                          |
| Optimism            | Phase 1    | same                                                          |
| All EVM (settle)    | Phase 2    | real `transferWithAuthorization` broadcast for every chainId |
| Cardano             | Phase 6    | `blockchain-cardano` adapter; CIP-8 verify, Tx submit         |

All six EVM chains migrate **together** in Phase 1 because they share
one `EvmChainAdapter` impl (chain id is a constructor parameter, not
a code branch). There is no per-chain phase split inside EVM.

`Facilitators.testnet()` in x402-core continues to work as the no-op
testing facilitator — it doesn't touch this SPI.

## 10. Phases

### Phase 1 — SPI + blockchain-evm minimum + x402 facilitator fix

Scope of the first PR. Independently shippable per `AGENTS.md` Rule 3.

Modules:
- `crypto-spi` — `CryptoBackend` trait + registry (cross-compile)
- `crypto-bouncycastle` — JVM default impl
- `blockchain-spi` — `ChainAdapter` / `ChainId` / `Asset` /
  `TypedData` / `TxIntent` (incl. `Deploy` and the contract-call
  primitives) / registry (cross-compile)
- `blockchain-evm` — EVM impl: `addressFromPublicKey` +
  `typedDataDigest` + `recoverAddress` + `tokenBalance` + `call`.
  **No** `buildTransaction` / `broadcast` yet (Phase 2). `call`
  is included because `EvmFacilitator.verify` needs read-only RPC
  for balance checks.
- `x402-facilitator-evm` refactor:
  - `verify` now calls `blockchain-evm.recoverAddress` + checks
    against `auth.from`
  - `tokenBalance` replaces hand-coded `erc20Balance` selector
- `x402-client` refactor: `PrivateKeyWallet` becomes adapter shim —
  see [`wallet-spi.md`](wallet-spi.md) §12 (companion Phase 1).

Tests:
- RFC-6979 ECDSA vectors, EIP-712 example vectors (from EIP itself),
  BIP-32 appendix C, EIP-55 checksum vectors.
- `EvmFacilitator.verify` rejects mismatched signatures (new test).
- Existing `X402ClientTest` + `EvmFacilitatorTest` stay green with
  real signatures.

Out of scope: real on-chain settle (Phase 2); Scala.js crypto
(Phase 4); non-EVM chains (Phase 3+).

### Phase 2 — blockchain-evm full ChainAdapter + ABI codec + real x402 settle

- `EvmChainAdapter.buildTransaction(NativeTransfer | TokenTransfer
  | ContractCall | TokenTransferAuthorized | Deploy)` with RLP
  encoding (EIP-1559 + legacy).
- `broadcast` via `eth_sendRawTransaction`; `waitForReceipt`
  polling. `predictDeployAddress` for CREATE / CREATE2.
- `blockchain-evm-abi` — Solidity ABI v2 codec (encode/decode for
  all standard types + tuples + dynamic structs). Pure-Scala,
  cross-compile, no chain access. Vector-tested against published
  reference encodings.
- Typed proxies in `blockchain-evm`: `Erc20`, `Erc721`, `Erc1155`,
  `Permit2`, `Multicall3`, `Eip3009` typed-data helper. x402
  internals refactored to use `Eip3009` instead of inline EIP-712
  hashing — no public API change.
- `x402-facilitator-evm.settle` default settler becomes a real
  `transferWithAuthorization` call via `Erc20.transferWithAuthorization.intent(...)`;
  the `Option[settler]` escape hatch stays.
- Integration test against local Anvil node: full x402 flow
  end-to-end (verify Ok, settle, receipt returned); plus deploy +
  interact round-trip for ABI codec.

### Phase 3 — blockchain-solana

- ed25519 signing, base58 addresses, SLIP-0010 derivation.
- Versioned transactions, address-lookup-table support.
- Send / poll via Solana RPC.
- Triggers `Network → ChainId` migration in `x402-core` (see §9.3).

### Phase 4 — Scala.js CryptoBackend

- `crypto-noble-js` — facade over `@noble/curves` +
  `@noble/hashes` + `@noble/ciphers`.
- Cross-backend conformance: identical bytes on JVM and JS for
  deterministic algorithms (RFC-6979 ECDSA, all hashes).

### Phase 5 — blockchain-bitcoin

- secp256k1 with sighash variants (SIGHASH_ALL / NONE / SINGLE +
  ANYONECANPAY; SegWit BIP-143 / Taproot BIP-341).
- Bech32 addresses; P2WPKH minimum.
- PSBT (BIP-174) for hardware-wallet compatibility.

### Phase 6 — blockchain-cosmos / blockchain-cardano

- Cosmos: secp256k1 / ed25519, sign_doc / sign_mode_textual,
  bech32 prefixes, family-aware (Osmosis / Juno / cosmoshub-4 /
  …).
- Cardano: integrate `x402-facilitator-cardano` (already on the
  x402 roadmap) as a `blockchain-cardano` adapter — CIP-8 signing,
  lovelace + native assets.

## 11. Testing strategy

- **Vector tests** against published fixtures: RFC 6979, BIP-32
  appendix C, SLIP-0010 appendix B, EIP-712 examples, EIP-55
  checksum, EIP-3009 `TransferWithAuthorization` reference vectors.
- **Round-trip tests** per chain: build → sign → recover → verify.
- **Cross-backend conformance** from Phase 4 onward: bit-identical
  outputs on JVM vs Scala.js for deterministic algorithms.
- **Integration tests** against local nodes (Anvil for EVM,
  solana-test-validator for Solana, bitcoind regtest for Bitcoin).
- **x402 facilitator tests** become meaningful — a mismatched
  signature now causes verify failure (was silently accepted).

## 12. References

- BIP-32, BIP-39, BIP-44, SLIP-0010, SLIP-0044
- EIP-55 (checksum), EIP-155 (chain ID in sigs), EIP-712 (typed
  data), EIP-1559 (fee market), EIP-3009 (`transferWithAuthorization`)
- CAIP-2, CAIP-10 (chain / account namespaces)
- SegWit BIP-143, Taproot BIP-341, PSBT BIP-174
- Cosmos SDK sign_doc; Solana versioned tx; Cardano CIP-8 / CIP-30
