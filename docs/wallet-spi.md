# Wallet SPI — Plan

Status: **draft / planning**. Source of truth for the multi-chain wallet
infrastructure. Until each phase below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this design.

## 1. Goals

- **One contract, many blockchains.** Adding a new chain (Solana,
  Bitcoin, Cosmos, Tron, …) is a new `ChainAdapter` implementation;
  no changes to higher-level wallet code.
- **One contract, many key-management strategies.** Encrypted seed,
  MPC / threshold signing, smart-account + passkey, hardware wallet —
  all behind the same `Vault` / `RawSigner` / `AccountStrategy` SPI.
- **Cross-compile JVM + Scala.js.** The same source compiles for
  backend automation (JVM CLI, tests, server-side signing) and for
  in-browser PWA wallets.
- **Replaceable cryptography.** Curve / hash / KDF / AEAD primitives
  live behind a `CryptoBackend` SPI with a default BouncyCastle JVM
  implementation. JS implementation uses `@noble/curves` + `@noble/hashes`.
  Test doubles, HSM, and TEE implementations swap in without touching
  higher layers.
- **Real signatures.** The current stub in `x402-client`
  (SHA-256 over private key for "address", SHA-256 over input for
  "signature") is replaced with real secp256k1 ECDSA + EIP-712
  typed-data hashing.
- **DApp connectivity is pluggable.** EIP-1193, Solana Wallet
  Standard, and WalletConnect v2 are each separate `DappConnector`
  modules. Any future protocol slots in without touching the wallet
  core.

## 2. Non-goals

- We are **not** writing all chain adapters at once. v1 ships SPI +
  EVM only. Solana, Bitcoin, Cosmos follow in later phases.
- We are **not** building wallet UI / onboarding flows / PWA shell.
  Those come after the SPI is stable.
- We are **not** implementing MPC / threshold signing ourselves.
  When needed, we integrate an external provider via the `Vault` SPI.
- We are **not** implementing an ERC-4337 bundler. We consume an
  external bundler client when smart-account support is added.
- We are **not** writing our own elliptic-curve math. JVM uses
  BouncyCastle (audited); JS uses `@noble/curves` (audited).
- We are **not** implementing the WalletConnect relay server. Only
  the wallet-side client of the WC protocol.

## 3. Architecture — three axes

The wallet space has three orthogonal extension axes:

- **Axis A — chains.** Bitcoin, EVM family, Solana, Cosmos, Tron, …
  Implementation: `ChainAdapter` trait (§7).
- **Axis B — key management.** Encrypted seed, MPC, smart-account +
  passkey, hardware wallet. Implementation: `Vault` + `RawSigner`
  (§6) and `AccountStrategy` (§8).
- **Axis C — dApp connectivity.** EIP-1193, Solana Wallet Standard,
  WalletConnect v2. Implementation: `DappConnector` trait (§10).

Below the high-level SPI sits a **fourth, lower-level SPI** — the
cryptographic primitives layer (§4). All higher layers consume it.

```
                       ┌─────────────────────────────────┐
                       │ DappConnector  (axis C)         │
                       │   eip-1193 / wc-v2 / wallet-std │
                       └──────────────┬──────────────────┘
                                      │ AccountManager facade
                       ┌──────────────▼──────────────────┐
                       │ AccountStrategy   (axis B-high) │
                       │   Eoa / SmartAccount (ERC-4337) │
                       └──────────────┬──────────────────┘
                          ┌───────────┴────────────┐
                          ▼                        ▼
               ┌──────────────────┐     ┌──────────────────┐
               │ ChainAdapter      │     │ Vault            │
               │ (axis A)          │     │ (axis B-low)     │
               │ evm / solana /    │     │ encrypted / mpc /│
               │ bitcoin / cosmos  │     │ passkey / hw     │
               └────────┬──────────┘     └────────┬─────────┘
                        │                         │
                        │  RawSigner ◄────────────┘
                        │
                        ▼
               ┌──────────────────────────────────────────┐
               │ CryptoBackend  (lower SPI)               │
               │   bouncycastle-jvm / noble-js / …        │
               └──────────────────────────────────────────┘
```

## 4. CryptoBackend SPI (lower layer)

A pluggable layer for curve / hash / KDF / AEAD / HD-derivation
primitives. Discovered via `ServiceLoader` on JVM; static registry on
Scala.js (mirroring the pattern in `runtime-server-spi` /
`backend-spi`).

```scala
package scalascript.wallet.crypto

enum Curve:
  case Secp256k1, Ed25519, P256, Sr25519, Bls12_381

enum HashAlgo:
  case None, Sha256, Sha512, Keccak256, Ripemd160, Hmac_Sha512

trait CryptoBackend:
  def id: String                    // "bouncycastle-jvm", "noble-js", …
  def supports(curve: Curve): Boolean

  // Signing primitives
  def sign(curve: Curve, privKey: Array[Byte], msg: Array[Byte], hash: HashAlgo): Array[Byte]
  def verify(curve: Curve, pubKey: Array[Byte], msg: Array[Byte], sig: Array[Byte], hash: HashAlgo): Boolean
  def derivePublic(curve: Curve, privKey: Array[Byte]): Array[Byte]
  def recoverPublic(curve: Curve, msgHash: Array[Byte], sig: Array[Byte], recId: Int): Array[Byte]

  // Hash primitives
  def hash(algo: HashAlgo, data: Array[Byte]): Array[Byte]
  def hmac(algo: HashAlgo, key: Array[Byte], data: Array[Byte]): Array[Byte]

  // BIP-32 (secp256k1) / SLIP-0010 (ed25519, p256) HD derivation
  def deriveMaster(curve: Curve, seed: Array[Byte]): HdKey
  def deriveChild(curve: Curve, parent: HdKey, index: Long, hardened: Boolean): HdKey

  // KDF
  def pbkdf2(password: Array[Byte], salt: Array[Byte], iter: Int, len: Int, hash: HashAlgo): Array[Byte]
  def argon2id(password: Array[Byte], salt: Array[Byte], memKiB: Int, iter: Int, parallelism: Int, len: Int): Array[Byte]
  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: Array[Byte], len: Int, hash: HashAlgo): Array[Byte]

  // AEAD (for vault encryption)
  def aesGcmEncrypt(key: Array[Byte], iv: Array[Byte], plaintext: Array[Byte], aad: Array[Byte]): Array[Byte]
  def aesGcmDecrypt(key: Array[Byte], iv: Array[Byte], ciphertext: Array[Byte], aad: Array[Byte]): Array[Byte]

  // Secure RNG
  def randomBytes(len: Int): Array[Byte]

case class HdKey(privateKey: Array[Byte], chainCode: Array[Byte])

object CryptoBackend:
  def get(): CryptoBackend                  // first registered
  def get(id: String): CryptoBackend        // by id
  def all: Seq[CryptoBackend]
```

### 4.1 Default implementations

- **`wallet-crypto-bouncycastle`** — JVM impl, uses `bcprov-jdk18on`
  for everything. Registers via
  `META-INF/services/scalascript.wallet.crypto.CryptoBackend`.
  This is the **default** and what the v1 phase ships.
- **`wallet-crypto-noble-js`** — Scala.js facade over `@noble/curves`
  + `@noble/hashes` + `@noble/ciphers`. Phase 3.
- **`wallet-crypto-tink-jvm`** — alternative JVM impl using Google
  Tink. Reserved; not implemented unless requested.

The CryptoBackend SPI is the **only** place chain-specific elliptic-
curve math lives. Higher layers depend on it abstractly through
`Curve` / `HashAlgo` parameters.

### 4.2 Sync vs. async

The SPI is **synchronous** (`Array[Byte] → Array[Byte]`). Justification:

- BouncyCastle is sync.
- `@noble/curves` is sync (pure JS, no WebCrypto).
- Most callers wrap whole flows in `Future` anyway.

WebAuthn / passkey signing **is** unavoidably async, but it does not
go through `CryptoBackend` — it goes through a separate
`PasskeySigner` (which implements `RawSigner` directly, see §6).

## 5. Module layout

```
wallet-spi                    # high-level traits — cross-compile JVM + Scala.js
wallet-crypto-spi             # CryptoBackend trait + registry — cross-compile
wallet-crypto-bouncycastle    # default JVM impl
wallet-crypto-noble-js        # Scala.js impl (Phase 3)

wallet-chain-evm              # EVM ChainAdapter + EIP-712 + ABI helpers
wallet-chain-solana           # Solana ChainAdapter (Phase 5)
wallet-chain-bitcoin          # Bitcoin ChainAdapter (Phase 9)
wallet-chain-cosmos           # Cosmos ChainAdapter (later)

wallet-vault-encrypted        # encrypted seed (cross-compile interface)
wallet-vault-encrypted-jvm    # JVM-side IO (filesystem)
wallet-vault-encrypted-js     # Scala.js-side IO (IndexedDB)
wallet-vault-passkey-js       # WebAuthn vault (Scala.js only)
wallet-vault-mpc              # HTTP client to external MPC provider
wallet-vault-ledger           # Ledger via WebHID (JS) / hid4java (JVM)

wallet-strategy-eoa           # generic, cross-compile
wallet-strategy-erc4337       # EVM-only smart-account wrapper

wallet-connector-eip1193-js   # window.ethereum injection (Scala.js)
wallet-connector-wallet-std   # Solana / Sui Wallet Standard (Scala.js)
wallet-connect                # WC v2 — cross-compile (JVM + JS)
```

Cross-compile model mirrors `runtime-server-spi` ↔ `runtime-server-jvm`
/ `runtime-server-jvm-jetty` / `runtime-server-jvm-netty`.

## 6. RawSigner & Vault

```scala
package scalascript.wallet.spi

import scalascript.wallet.crypto.{Curve, HashAlgo, PublicKey}

trait RawSigner:
  def curve: Curve
  def publicKey: PublicKey
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]]

enum VaultKind:
  case EncryptedLocal, Mpc, Hardware, Passkey, InMemory

trait Vault:
  def kind: VaultKind
  def id: String
  def isLocked: Boolean
  def unlock(credential: UnlockCredential): Future[Unit]
  def lock(): Unit
  def listAccounts(): Future[Seq[AccountDescriptor]]
  /** Main factory: a signer for a (curve, derivationPath) pair. */
  def getSigner(curve: Curve, derivationPath: String): Future[RawSigner]

sealed trait UnlockCredential
object UnlockCredential:
  case class Password(value: String)        extends UnlockCredential
  case object Biometric                     extends UnlockCredential
  case object None                          extends UnlockCredential

case class AccountDescriptor(
  id:             String,
  label:          String,
  publicKeys:     Map[Curve, PublicKey],
  derivationPath: String,
)
```

**Invariant:** `RawSigner` does not know about chains. It knows only
`curve` and how to sign bytes. `Vault.getSigner(curve, path)` is the
sole factory; if the vault doesn't support a curve, it throws a
descriptive error.

## 7. ChainAdapter

```scala
package scalascript.wallet.spi

case class ChainId(caip2: String)
// "eip155:1", "solana:5eyk…", "bip122:000000000019…"

trait ChainAdapter:
  type Tx
  type SignedTx

  def chainId: ChainId
  def supportedCurves: Seq[Curve]
  def defaultDerivationPath: String              // BIP-44

  def addressFromPublicKey(pk: PublicKey): String

  def buildTransaction(intent: TxIntent, ctx: ChainContext): Future[Tx]
  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload
  def assembleSignedTransaction(tx: Tx, sig: Array[Byte], signer: PublicKey): SignedTx
  def broadcast(signed: SignedTx, ctx: ChainContext): Future[String]   // tx hash
  def describe(tx: Tx): TxDescription            // for UI / dry-run

case class SigningPayload(bytes: Array[Byte], hash: HashAlgo)

trait ChainContext:
  def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value]
  def nowSeconds: Long
```

The `Tx` / `SignedTx` are **path-dependent types** so each adapter
keeps its own native representation (`EvmTx`, `SolanaTx`, `BtcTx`),
but generic code can still flow through `ChainAdapter` polymorphically.

EVM-specific encoding helpers (RLP, ABI, EIP-712 struct hashing,
keccak-based address derivation) live in `wallet-chain-evm` and are
reused by `wallet-strategy-erc4337`.

## 8. AccountStrategy

```scala
package scalascript.wallet.spi

trait AccountStrategy:
  def kind: String     // "eoa", "smart-account", "mpc-eoa"
  def getAddress(chain: ChainAdapter): Future[String]
  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx]
  def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]]
  def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]]

class EoaStrategy(signer: RawSigner) extends AccountStrategy:
  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
    val payload = chain.prepareSigningPayload(tx, signer.publicKey)
    signer.sign(payload.bytes, payload.hash).map { sig =>
      chain.assembleSignedTransaction(tx, sig, signer.publicKey)
    }
  // …
```

### 8.1 Smart accounts (ERC-4337)

`SmartAccountStrategy` is **not** a peer of `EoaStrategy` at the
adapter level. Instead, it lives as a wrapper that turns an EVM
`ChainAdapter` into a smart-account-aware adapter:

```scala
object SmartAccount:
  def wrap(
    underlying: EvmChainAdapter,
    owner:      RawSigner,            // p256 from passkey, or any RawSigner
    bundler:    BundlerClient,
    factory:    SmartAccountFactory,
  ): ChainAdapter
```

Inside the wrapper:
- `buildTransaction` constructs a `UserOperation`, not a raw EVM tx.
- `prepareSigningPayload` returns the `userOpHash`.
- `broadcast` submits via bundler RPC instead of public RPC.
- `addressFromPublicKey` returns the counterfactual CREATE2 address.

This means **passkey owners** (curve = p256) can drive EVM accounts
even though p256 is not the EVM-native curve. The whole AA system
remains a chain-level concern, not a strategy-level concern.

## 9. ChainId, addresses, and CAIP

All cross-chain identifiers in the SPI use **CAIP-2** strings:

| Chain        | CAIP-2 example                                            |
|--------------|-----------------------------------------------------------|
| Ethereum     | `eip155:1`                                                |
| Base         | `eip155:8453`                                             |
| Solana       | `solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w`         |
| Bitcoin      | `bip122:000000000019d6689c085ae165831e93`                 |
| Cosmos Hub   | `cosmos:cosmoshub-4`                                       |

CAIP-10 (`<caip2>:<address>`) is used wherever we need to qualify an
address with its chain. This is the form WalletConnect v2 namespaces
expect — keeping the SPI native to it is essential for the connector
in §10.3.

The existing `Network` enum in `x402-core` is **not** changed in
Phase 1 (compatibility); it is migrated to wrap `ChainId(caip2)`
in a follow-up phase.

## 10. DappConnector

```scala
package scalascript.wallet.spi

trait DappConnector:
  def protocol: String     // "eip-1193", "wallet-standard", "walletconnect-v2"
  def attach(manager: AccountManager): Unit
  def detach(): Unit

trait AccountManager:
  def chains: Set[ChainId]
  def strategyFor(chain: ChainId): Option[AccountStrategy]
  def adapterFor(chain: ChainId): Option[ChainAdapter]
  def request(req: DappRequest): Future[DappResponse]
```

### 10.1 EIP-1193 (`wallet-connector-eip1193-js`)

Scala.js only. Injects a `window.ethereum`-compatible provider in the
host page. Translates JSON-RPC calls (`eth_requestAccounts`,
`personal_sign`, `eth_sendTransaction`, `eth_signTypedData_v4`, etc.)
into `AccountManager.request`.

Supports EIP-6963 multi-injected-provider discovery so it coexists
with MetaMask / Rabby.

### 10.2 Wallet Standard (`wallet-connector-wallet-std`)

Scala.js only. Implements the `@wallet-standard/core` shape via
`registerWallet`. Targets Solana / Sui dApps.

### 10.3 WalletConnect v2 (`wallet-connect`)

**Cross-compile** (JVM + Scala.js). The wallet-side client of the WC
v2 protocol: opens a Web Socket to a WC relay, advertises a
`namespaces` capability built from `AccountManager.chains`, accepts
incoming session proposals and signing requests.

- JVM: ws via JDK `java.net.http.WebSocket` (already used elsewhere
  in this repo).
- Scala.js: facade over `@walletconnect/sign-client`.

Multi-chain falls out for free because WC v2 is namespace-based — one
session can carry `eip155:*` + `solana:*` simultaneously. This is the
primary connectivity channel for the eventual PWA wallet, since a
PWA cannot inject `window.ethereum` into a third-party page.

## 11. Cross-compile model

`wallet-spi`, `wallet-crypto-spi`, all `wallet-chain-*`,
`wallet-strategy-*`, and the WC connector are **cross-compiled**
(JVM + Scala.js) sbt projects.

Backend-specific implementations (`wallet-crypto-bouncycastle`,
`wallet-vault-encrypted-jvm`, `wallet-vault-encrypted-js`,
`wallet-vault-passkey-js`, `wallet-connector-eip1193-js`,
`wallet-connector-wallet-std`) are platform-specific projects that
depend on the cross-compiled SPI artefacts.

Discovery:
- JVM: `META-INF/services/scalascript.wallet.crypto.CryptoBackend` —
  same `ServiceLoader` pattern as `HttpServerSpi`.
- Scala.js: a static `WalletJsRegistry` object that backend impls
  call into from a top-level initialiser. Confirm against existing
  pattern in `backend-scalajs` before Phase 3 (see open question).

## 12. Migration from x402 stub

The current stub in
`x402-client/src/main/scala/scalascript/x402/client/X402Client.scala`
(`PrivateKeyWallet`) uses **SHA-256** in two places it should not:

```scala
// line 33-36 — fake "Ethereum address" via SHA-256(privkey):
val md   = MessageDigest.getInstance("SHA-256")
val hash = md.digest(keyBytes)
"0x" + hash.take(20).map(b => f"${b & 0xff}%02x").mkString

// line 41-44 — fake "signature" via SHA-256(domain || value):
val md    = MessageDigest.getInstance("SHA-256")
val hash  = md.digest(input.getBytes("UTF-8"))
"0x" + hash.map(...).mkString + "00"
```

Phase 1 replaces both with the real implementations via
adapter-shim (per architectural decision):

- New module `wallet-spi` introduces `RawSigner` + `EoaStrategy` +
  `wallet-chain-evm` (EIP-712 + address derivation).
- `x402.client.Wallet` becomes a thin adapter that delegates to an
  underlying `EoaStrategy(rawSigner)`. The `Wallets.privateKey(hex,
  network)` and `Wallets.envKey(...)` factories keep their public
  signatures; internally they wire up a `RawPrivateKeyVault` +
  `EoaStrategy` + EVM hashing helpers.
- Existing tests in `X402ClientTest` continue to compile and run.
  Their fixture-asserted signature bytes are updated to real
  (deterministic, RFC-6979) ECDSA outputs.

No breaking change to x402's public API in Phase 1.

## 13. Phases

Each phase below is independently shippable per
[`AGENTS.md`](../AGENTS.md) Rule 3.

### Phase 1 — Spec + skeleton SPI + JVM crypto + x402 shim

Scope of the v1 PR.

Modules:
- `wallet-spi` — cross-compile traits & types:
  `RawSigner`, `Vault`, `ChainAdapter`, `AccountStrategy`, `ChainId`,
  `TxIntent`, `TypedData`, `AccountManager`.
- `wallet-crypto-spi` — `CryptoBackend` trait + registry.
- `wallet-crypto-bouncycastle` — JVM default impl
  (`bcprov-jdk18on`). Covers secp256k1 + ed25519 + p256 sign / verify
  / derive; keccak256, sha2, hmac, hkdf, pbkdf2, argon2id; AES-GCM;
  BIP-32 / SLIP-0010 HD derivation.
- `wallet-chain-evm` — pure-EVM helpers (no transaction building yet):
  `addressFromPublicKey` (keccak256 + last 20 bytes), EIP-712 domain
  separator + struct hash, signature serialization with recovery-id
  (v), `EoaSignerEvm` helper wrapping `RawSigner`.
- x402-client refactor: `PrivateKeyWallet` → adapter over the above.

Out of scope in Phase 1: ChainAdapter for `buildTransaction` /
`broadcast`; Solana / Bitcoin; vault encryption; Scala.js backend; any
DappConnector.

Tests:
- Unit tests for `CryptoBackend` against RFC-6979 ECDSA test vectors,
  EIP-712 example vectors, BIP-32 test vectors, SLIP-0010 test
  vectors.
- `wallet-chain-evm` address-derivation tests against known
  (privkey → address) pairs.
- Existing `X402ClientTest` still green with real signatures.

### Phase 2 — Full EVM ChainAdapter

`wallet-chain-evm` gains `ChainAdapter` impl:
- `buildTransaction` from `TxIntent` (transfer ETH, ERC-20 transfer,
  arbitrary call) using `EvmClient` for nonce / gas estimation.
- EIP-1559 + legacy tx encoding.
- `broadcast` via `eth_sendRawTransaction`.
- Round-trip test: build → sign → recover → send → wait for receipt
  on Anvil.

### Phase 3 — Scala.js CryptoBackend

`wallet-crypto-noble-js` Scala.js facade. Cross-backend conformance
test: same test vectors must produce identical bytes on JVM and JS.

### Phase 4 — Encrypted Vault

`wallet-vault-encrypted` (cross-compile interface):
- BIP-39 mnemonic generation / restore (24-word default).
- Password unlock via Argon2id → AES-GCM(seed).
- JVM IO: file at `~/.scalascript/wallets/<id>.vault`.
- JS IO: IndexedDB record.

### Phase 5 — Solana ChainAdapter

`wallet-chain-solana`: ed25519 signing, base58 addresses,
versioned-transaction encoding, address lookup tables, send via
public RPC.

### Phase 6 — DappConnector EIP-1193 (Scala.js)

PWA can act as an injected provider in the host page.

### Phase 7 — DappConnector WalletConnect v2

Cross-compile (JVM + JS). Wallet side of WC v2 — accepts session
proposals, exposes signing capability over WS relay. Primary
connectivity channel for the PWA wallet.

### Phase 8 — ERC-4337 SmartAccountStrategy

Smart-contract wallet with passkey owner. Gas sponsorship via
external paymaster service. UserOp construction and submission via
bundler client.

### Phase 9 — Bitcoin ChainAdapter

`wallet-chain-bitcoin`: secp256k1 with sighash variants, P2WPKH
addresses (bech32), PSBT input/output for hardware-wallet
compatibility.

### Phase 10 — Hardware wallet Vault

`wallet-vault-ledger`: Ledger via WebHID (JS) / `hid4java` (JVM).
Optional `wallet-vault-trezor` similarly.

## 14. Testing strategy

- **Vector tests** against published test fixtures (RFC 6979, BIP-32
  appendix C, SLIP-0010 appendix B, EIP-712 examples, EIP-55 checksum
  vectors) — locked in Phase 1.
- **Round-trip tests** per curve: generate key → derive public → sign
  → verify. Locked in Phase 1.
- **Cross-backend conformance** from Phase 3 onward: same inputs
  produce bit-identical outputs on JVM and Scala.js where the
  algorithm is deterministic (RFC-6979 ECDSA, all hashes).
- **Integration tests** per chain adapter against a local node
  (Anvil for EVM in Phase 2; Solana validator in Phase 5; bitcoind
  regtest in Phase 9).
- **WC v2 connector** tests run against a public WC relay in CI
  (relay supplies a sandbox / dev project ID).

## 15. Open questions

1. **Scala.js registry pattern.** What is the established pattern in
   this project for static SPI registration on Scala.js (since
   `ServiceLoader` is JVM-only)? Confirm against `backend-scalajs`
   and `mcp-common` Phase 3 client. Resolve before Phase 3.
2. **Path-dependent `Tx` / `SignedTx`.** Verify with a worked
   `AccountManager` example before Phase 2 — if path-dependent types
   prove awkward across the manager / connector boundary, fall back
   to `ChainAdapter[Tx, SignedTx]`.
3. **`Network` migration in `x402-core`.** When (in which phase) do
   we replace the `Network` enum with `ChainId(caip2)`? Likely
   Phase 5 or after, when the second chain ships.
4. **WC v2 project ID.** WC requires a `projectId` (free from
   cloud.walletconnect.com). For CI tests, we need a sandbox ID.
   Resolve before Phase 7.

## 16. References

- BIP-32, BIP-39, BIP-44 — HD wallets, mnemonics, paths
- SLIP-0010 — HD for ed25519 / p256
- SLIP-0044 — coin types
- EIP-55 — address checksum
- EIP-155 — chain ID in signatures (replay protection)
- EIP-712 — typed structured data signing
- EIP-1193 — Provider API (`window.ethereum`)
- EIP-6963 — multi-injected-provider discovery
- EIP-4337 — account abstraction
- EIP-3009 — `transferWithAuthorization` (used by x402)
- CAIP-2, CAIP-10, CAIP-25 — chain / account / session namespaces
- WalletConnect v2 spec (`docs.walletconnect.com`)
- Solana Wallet Standard (`github.com/wallet-standard`)
