# Wallet SPI — Plan

Status: **draft / planning**. Source of truth for the wallet-side
infrastructure that lives **above** [`blockchain-spi`](blockchain-spi.md)
— key storage, signing strategies, dApp connectivity. Until each phase
below is checked off in [`MILESTONES.md`](../MILESTONES.md), the code
does not match this design.

## 1. Goals

- **One contract, many key-management strategies.** Encrypted seed,
  MPC / threshold signing, smart-account + passkey, hardware wallet —
  all behind the same `Vault` / `RawSigner` / `AccountStrategy` SPI.
- **Cross-compile JVM + Scala.js.** The same source compiles for
  backend automation (JVM CLI, tests, server-side signing) and for
  in-browser PWA wallets.
- **DApp connectivity is pluggable.** EIP-1193, Solana Wallet
  Standard, and WalletConnect v2 are each separate `DappConnector`
  modules.
- **Reuse `blockchain-spi` for everything chain-shaped.** What an
  address is, how a transaction is built, how a signature recovers
  to a signer — all of that lives one layer down and is shared with
  `x402-*`. This document does **not** redefine those concepts; it
  consumes them.
- **Replace the x402-client stub.** `PrivateKeyWallet`
  (`x402-client/.../X402Client.scala:27-45`) currently uses SHA-256
  in place of secp256k1 ECDSA and EIP-712 hashing. Replaced via an
  adapter shim — x402's public API is unchanged.

## 2. Non-goals

- We are **not** defining chain abstractions here. `ChainAdapter`,
  `ChainId`, `TypedData`, `TxIntent`, etc. live in
  [`blockchain-spi`](blockchain-spi.md).
- We are **not** writing wallet UI / onboarding flows / PWA shell.
  Those come after the SPI is stable.
- We are **not** implementing MPC / threshold signing ourselves. When
  needed, we integrate a provider via the `Vault` SPI.
- We are **not** implementing an ERC-4337 bundler. We consume an
  external bundler client when smart-account support is added.
- We are **not** writing our own elliptic-curve math. The
  [`crypto-spi`](blockchain-spi.md#4-module-layout) layer takes care
  of that.
- We are **not** implementing a WalletConnect relay server. Only the
  wallet-side client of the WC protocol.

## 3. Where this SPI sits

```
                ┌────────────────────────────────────┐
                │  DappConnector  (axis C)           │
                │    eip-1193 / wallet-std / wc-v2   │
                └─────────────────┬──────────────────┘
                                  │
                ┌─────────────────▼──────────────────┐
                │  AccountStrategy   (axis B-high)   │
                │    Eoa  /  SmartAccount(ERC-4337)  │
                └─────────────────┬──────────────────┘
                          ┌───────┴──────┐
                          ▼              ▼
              ┌─────────────────┐  ┌──────────────┐
              │ blockchain-spi  │  │ Vault        │  (axis B-low)
              │ ChainAdapter…   │  │ encrypted /  │
              │ (other doc)     │  │ mpc / passkey│
              └─────────────────┘  │ / hardware   │
                                   └───────┬──────┘
                                           │
                                           ▼  RawSigner
                                   ┌──────────────────┐
                                   │ crypto-spi       │
                                   │ (curve/hash/AEAD)│
                                   └──────────────────┘
```

Two extension axes belong to this document:

- **Axis B — key management.** Encrypted seed, MPC, smart-account +
  passkey, hardware wallet. Implementation: `Vault` + `RawSigner`
  (§5) and `AccountStrategy` (§6).
- **Axis C — dApp connectivity.** EIP-1193, Solana Wallet Standard,
  WalletConnect v2. Implementation: `DappConnector` trait (§7).

Axis A (chains) and the lower-level `crypto-spi` are defined in
[`blockchain-spi.md`](blockchain-spi.md).

## 4. Module layout

Modules owned by this SPI:

```
wallet-spi                    # traits — cross-compile JVM + Scala.js

wallet-vault-encrypted        # cross-compile interface
wallet-vault-encrypted-jvm    # filesystem IO
wallet-vault-encrypted-js     # IndexedDB IO
wallet-vault-passkey-js       # WebAuthn (Scala.js only)
wallet-vault-mpc              # HTTP client to external MPC provider
wallet-vault-ledger           # Ledger hardware (WebHID / hid4java)

wallet-strategy-eoa           # generic; usually the default
wallet-strategy-erc4337       # EVM-only smart-account wrapper

wallet-connector-eip1193-js   # window.ethereum injection
wallet-connector-wallet-std   # Solana / Sui Wallet Standard
wallet-connect                # WalletConnect v2 (cross-compile)
```

Modules **referenced** from [`blockchain-spi`](blockchain-spi.md):
`crypto-spi`, `crypto-bouncycastle`, `crypto-noble-js`,
`blockchain-spi`, `blockchain-evm`, `blockchain-solana`,
`blockchain-bitcoin`, …

## 5. RawSigner & Vault

```scala
package scalascript.wallet.spi

import scalascript.wallet.crypto.{Curve, HashAlgo, PublicKey}
import scala.concurrent.Future

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
  case class Password(value: String) extends UnlockCredential
  case object Biometric              extends UnlockCredential
  case object None                   extends UnlockCredential

case class AccountDescriptor(
  id:             String,
  label:          String,
  publicKeys:     Map[Curve, PublicKey],
  derivationPath: String,
)
```

**Invariant:** `RawSigner` does not know about chains. It knows only
`curve` and how to sign bytes. `Vault.getSigner(curve, path)` is the
sole factory; if the vault doesn't support a curve, it throws.

### 5.1 Multi-chain hardware wallets (Ledger)

Hardware wallets are a first-class `Vault` impl. Ledger devices in
particular are intrinsically multi-chain: one device holds **one
seed** and runs **per-chain firmware apps** (Bitcoin, Ethereum,
Solana, Cardano, Polkadot, Tron, … — ~5500 supported coins). Each
app implements the chain-native signing primitives, so the host
software (us) speaks one APDU protocol and gets the right curve
per chain "for free".

This composes naturally with our two-axis architecture:

```scala
package scalascript.wallet.vault.ledger

class LedgerVault(transport: LedgerTransport) extends Vault:
  def kind = VaultKind.Hardware

  def getSigner(curve: Curve, path: String): Future[RawSigner] =
    ensureCorrectApp(curve, path).map { _ =>
      new LedgerSigner(transport, curve, path)
    }

  /** Determine which on-device app is needed for (curve, path),
   *  probe the device for the active app, and surface an
   *  `AppSwitchRequired` to the host if a switch is needed. */
  private def ensureCorrectApp(curve: Curve, path: String): Future[Unit] = …

class LedgerSigner(t: LedgerTransport, val curve: Curve, val path: String)
    extends RawSigner:
  def sign(msg: Array[Byte], hash: HashAlgo) = t.signWithApp(curve, path, msg, hash)
```

Higher layers don't change at all:

- `blockchain-evm.EvmChainAdapter` calls
  `vault.getSigner(Secp256k1, "m/44'/60'/0'/0/0")` and gets a signer
  routed to the device's Ethereum app.
- `blockchain-solana.SolanaChainAdapter` calls
  `vault.getSigner(Ed25519, "m/44'/501'/0'/0'")` and gets a signer
  routed to the device's Solana app.
- `EoaStrategy` wraps either signer interchangeably.

#### Transport variants

| Transport | Where | Library |
|---|---|---|
| WebHID | Scala.js / browser PWA | `@ledgerhq/hw-transport-webhid` facade |
| HID | JVM (CLI / server) | `hid4java` |
| Bluetooth | Scala.js (Nano X / Stax) | `@ledgerhq/hw-transport-web-ble` facade |

Modules: `wallet-vault-ledger-js` (WebHID / WebBLE on Scala.js) and
`wallet-vault-ledger-jvm` (HID via hid4java). A cross-compile
`wallet-vault-ledger` module holds the shared types
(`LedgerTransport` trait, APDU codecs, `AppSwitchRequired` error).

#### App switching UX

Only one Ledger app is active at a time. When the host requests a
signer for a curve/path whose required app is not currently open,
`ensureCorrectApp` does not silently fail — it raises a typed
`AppSwitchRequired(neededApp: String)` future failure. The host is
expected to surface this to the user ("open the Ethereum app on
your Ledger") and retry.

The active app is detected via the device's `getAppName` APDU
(opcode `B0 01 00 00`). Mapping curve → required app:

| Curve / chain                | Ledger app          |
|------------------------------|---------------------|
| secp256k1 + path `m/44'/60'` | Ethereum            |
| secp256k1 + path `m/44'/0'`  | Bitcoin             |
| ed25519 + path `m/44'/501'`  | Solana              |
| ed25519 + path `m/1852'/...` | Cardano             |

Custom EVM chains (Polygon, Arbitrum, Optimism, Base, …) all run
through the Ethereum app — chain id is just a tx field; the curve
and signing logic are identical. So **all six x402 EVM chains share
one Ledger app**.

#### Blind signing vs. clear signing

Ledger refuses to sign opaque payloads by default ("blind signing"
must be explicitly enabled per app). For our flows:

- **EIP-712 / EIP-3009 (x402 payments)** — supported natively in
  the Ethereum app with structured display; the user sees decoded
  `TransferWithAuthorization` fields on-device. Clear-signing
  metadata is provided by the host alongside the typed-data bytes.
- **Arbitrary contract calls** — Ledger Clear Signing standard
  (registry of known contracts with ABI descriptors) covers the
  common ones; uncovered calls fall back to blind signing (user
  approval required, security warning shown).
- **Solana** — most common instructions decoded natively in the
  Solana app; SPL token transfers shown with amount + recipient.
- **Contracts authored in `.ssc`** — the authoring stack
  ([`docs/smart-contracts.md`](smart-contracts.md)) emits a
  clear-signing descriptor at compile time. The host attaches it
  to the signing request, the device renders parameter names and
  decoded values — no separate Clear Signing registry submission
  needed. See [`docs/blockchain-spi.md`](blockchain-spi.md) §6.1
  for the cross-layer integration.

#### Session and reconnection model

The device unlocks on PIN entry (out-of-band) and stays unlocked
until physical disconnect. Within a session, multiple signing
operations require no further authentication — only on-device user
confirmation per signature. The Vault does not cache device state;
disconnect = `Vault.lock()` semantics implicitly.

## 6. AccountStrategy

```scala
package scalascript.wallet.spi

import scalascript.blockchain.spi.{ChainAdapter, TypedData}

trait AccountStrategy:
  def kind: String     // "eoa", "smart-account", "mpc-eoa"
  def getAddress(chain: ChainAdapter): Future[String]
  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx]
  def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]]
  def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]]

class EoaStrategy(signer: RawSigner) extends AccountStrategy:
  def kind = "eoa"
  def getAddress(chain: ChainAdapter): Future[String] =
    Future.successful(chain.addressFromPublicKey(signer.publicKey))
  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
    val payload = chain.prepareSigningPayload(tx, signer.publicKey)
    signer.sign(payload.bytes, payload.hash).map { sig =>
      chain.assembleSignedTransaction(tx, sig, signer.publicKey)
    }
  def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
    val digest = chain.typedDataDigest(typed)
    signer.sign(digest, HashAlgo.None)
  // signMessage analogous (uses chain-specific message prefix)
```

### 6.1 Smart accounts (ERC-4337)

`SmartAccountStrategy` is **not** a peer of `EoaStrategy`. Instead, an
EVM `ChainAdapter` can be wrapped to become smart-account-aware — the
wrapping happens at the chain layer. See `blockchain-evm` for the
wrapping API; this SPI provides the convenience constructor that pairs
it with a passkey-based `RawSigner`.

```scala
object SmartAccount:
  def wrap(
    underlying: blockchain.evm.EvmChainAdapter,
    owner:      RawSigner,            // p256 from passkey, or any RawSigner
    bundler:    BundlerClient,
    factory:    SmartAccountFactory,
  ): ChainAdapter
```

This means **passkey owners** (curve = p256, native to WebAuthn) can
drive EVM accounts even though p256 is not the EVM-native curve — the
AA layer abstracts the curve mismatch.

## 7. DappConnector

```scala
package scalascript.wallet.spi

import scalascript.blockchain.spi.ChainId

trait DappConnector:
  def protocol: String     // "eip-1193", "wallet-standard", "walletconnect-v2"
  def attach(manager: AccountManager): Unit
  def detach(): Unit

trait AccountManager:
  def chains: Set[ChainId]
  def strategyFor(chain: ChainId): Option[AccountStrategy]
  def adapterFor(chain: ChainId): Option[blockchain.spi.ChainAdapter]
  def request(req: DappRequest): Future[DappResponse]
```

### 7.1 EIP-1193 (`wallet-connector-eip1193-js`)

Scala.js only. Injects a `window.ethereum`-compatible provider in the
host page. Translates JSON-RPC calls (`eth_requestAccounts`,
`personal_sign`, `eth_sendTransaction`, `eth_signTypedData_v4`, etc.)
into `AccountManager.request`. Supports EIP-6963 multi-injected-
provider discovery so it coexists with MetaMask / Rabby.

### 7.2 Wallet Standard (`wallet-connector-wallet-std`)

Scala.js only. Implements the `@wallet-standard/core` shape via
`registerWallet`. Targets Solana / Sui dApps.

### 7.3 WalletConnect v2 (`wallet-connect`)

**Cross-compile** (JVM + Scala.js). The wallet-side client of WC v2:
opens a Web Socket to a WC relay, advertises a `namespaces` capability
built from `AccountManager.chains` (CAIP-2 ids — defined in
[`blockchain-spi`](blockchain-spi.md)), accepts incoming session
proposals and signing requests.

- JVM: ws via JDK `java.net.http.WebSocket`.
- Scala.js: facade over `@walletconnect/sign-client`.

Multi-chain falls out naturally because WC v2 is CAIP-namespace-based
— one session can carry `eip155:*` + `solana:*` simultaneously.
**This is the primary connectivity channel** for the PWA wallet: a
PWA cannot inject `window.ethereum` into a third-party page.

## 8. Cross-compile model

`wallet-spi`, `wallet-strategy-*`, `wallet-vault-encrypted` (interface
only), and `wallet-connect` are cross-compile sbt projects (JVM +
Scala.js).

Backend-specific impls (`wallet-vault-encrypted-jvm`,
`wallet-vault-encrypted-js`, `wallet-vault-passkey-js`,
`wallet-vault-ledger`, `wallet-connector-eip1193-js`,
`wallet-connector-wallet-std`) are platform-specific projects that
depend on the cross-compiled SPI artefacts.

Discovery pattern is shared with `blockchain-spi` (resolve once for
both): JVM via `ServiceLoader`, Scala.js via static registry. See
open question §11.1.

## 9. Migration from x402-client stub

The current `Wallet` / `PrivateKeyWallet` in
`x402-client/src/main/scala/scalascript/x402/client/X402Client.scala`
uses **SHA-256** in two places it should not (lines 33-36 for "address"
and 38-45 for "signature"). The fix has two halves:

| Half | Where it lives | Doc |
|---|---|---|
| Real address + EIP-712 digest computation | `blockchain-evm` | [`blockchain-spi`](blockchain-spi.md) §8 |
| Real secp256k1 ECDSA over the digest | `crypto-bouncycastle` (via `EoaStrategy`) | this doc §6 |

Wallet-side migration (Phase 1 of this SPI):

- `x402.client.Wallet` becomes a thin adapter that delegates to an
  underlying `EoaStrategy(rawSigner)`. The `Wallets.privateKey(hex,
  network)` and `Wallets.envKey(...)` factories keep their public
  signatures; internally they wire up an in-memory vault + EOA
  strategy + `blockchain-evm` adapter for the requested network.
- Existing `X402ClientTest` continues to compile and run. Fixture-
  asserted signature bytes get updated to real (RFC-6979
  deterministic) outputs.

Facilitator-side migration and the per-chain x402 rollout are covered
in [`blockchain-spi`](blockchain-spi.md) §9 and §10.

No breaking change to x402's public API at any phase.

## 10. Phases

Each phase is independently shippable per
[`AGENTS.md`](../AGENTS.md) Rule 3. Phases here are **paired** with
phases in [`blockchain-spi`](blockchain-spi.md): wallet Phase 1 depends
on blockchain Phase 1; wallet Phase 6 (ERC-4337) depends on blockchain
Phase 2.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim

Depends on blockchain-spi Phase 1 (provides `blockchain-evm` with
EIP-712 digest + address derivation).

- [ ] `wallet-spi` — `RawSigner` / `Vault` / `AccountStrategy` /
      `DappConnector` / `AccountManager` (cross-compile)
- [ ] `wallet-strategy-eoa` — `EoaStrategy` impl (cross-compile)
- [ ] In-memory `RawPrivateKeyVault` (test helper, lives in
      `wallet-spi`)
- [ ] x402-client refactor: `PrivateKeyWallet` becomes adapter shim
      over `EoaStrategy` + `blockchain-evm.EvmChainAdapter`; existing
      `X402ClientTest` stays green with real signatures (fixture
      bytes updated)

### Phase 2 — Encrypted Vault

- [ ] `wallet-vault-encrypted` — interface (cross-compile)
- [ ] BIP-39 mnemonic generation / restore (24-word default)
- [ ] Argon2id → AES-GCM(seed) password unlock
- [ ] `wallet-vault-encrypted-jvm` — filesystem
      (`~/.scalascript/wallets/<id>.vault`)
- [ ] `wallet-vault-encrypted-js` — IndexedDB

### Phase 3 — DappConnector EIP-1193 (Scala.js)

- [ ] `wallet-connector-eip1193-js` — `window.ethereum` injection
- [ ] EIP-6963 multi-injected-provider discovery
- [ ] Translates `eth_*` JSON-RPC → `AccountManager.request`

### Phase 4 — DappConnector WalletConnect v2

- [ ] `wallet-connect` — cross-compile (JVM + JS)
- [ ] JVM: ws via JDK `java.net.http.WebSocket`
- [ ] JS: facade over `@walletconnect/sign-client`
- [ ] Multi-chain via CAIP-2 namespaces
- [ ] Resolves open question §11.2 (WC project ID for CI)

### Phase 5 — Solana DappConnector

- [ ] `wallet-connector-wallet-std` — Solana / Sui Wallet Standard
- [ ] Depends on blockchain-spi Phase 3 (`blockchain-solana`)

### Phase 6 — ERC-4337 SmartAccountStrategy

- [ ] `wallet-strategy-erc4337` — `SmartAccount.wrap(...)`
      convenience pairing
- [ ] UserOp construction + signing over `userOpHash`
- [ ] Bundler client (`eth_sendUserOperation` /
      `eth_estimateUserOperationGas`)
- [ ] Passkey owner via WebAuthn (Scala.js); curve = p256
- [ ] Counterfactual CREATE2 address derivation

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

See §5.1 for the architecture. The implementation lands one chain at
a time so the device-protocol surface stays reviewable.

- [ ] `wallet-vault-ledger` — shared types (cross-compile):
      `LedgerTransport` trait, APDU codecs, `AppSwitchRequired`
      error, `getAppName` probe, curve→app routing table
- [ ] `wallet-vault-ledger-jvm` — `hid4java`-backed transport
- [ ] `wallet-vault-ledger-js` — WebHID transport for Scala.js
- [ ] Ethereum-app signer first: secp256k1 + EIP-712 / EIP-3009
      (covers all 6 EVM x402 chains in one impl)
- [ ] Solana-app signer: ed25519 + Solana sign-doc framing
- [ ] Bitcoin-app signer: PSBT-aware (depends on
      blockchain-spi Phase 5)
- [ ] Cardano-app signer: CIP-8 framing (depends on blockchain-spi
      Phase 6)
- [ ] Optional `wallet-vault-ledger-bluetooth-js` for Nano X /
      Stax via WebBLE
- [ ] Optional `wallet-vault-trezor` follow-up

### Phase 8 — MPC Vault

- [ ] `wallet-vault-mpc` — HTTP client to an external MPC provider
- [ ] Curve-specific (one MPC backend per curve)
- [ ] No reference impl in this milestone — interface only, with one
      provider integration as proof of concept

## 11. Open questions

1. **Scala.js registry pattern.** What is the established pattern in
   this project for static SPI registration on Scala.js (since
   `ServiceLoader` is JVM-only)? Confirm against `backend-scalajs`
   and `mcp-common` Phase 3 client. Resolve before any Scala.js
   impl phase (this SPI's Phase 3 or blockchain-spi Phase 4 —
   whichever first). Resolves once for both SPIs.
2. **WC v2 project ID for CI.** WalletConnect requires a `projectId`
   (free from cloud.walletconnect.com). Resolve before Phase 4.
3. **AccountManager multi-account UX.** A wallet typically exposes
   multiple accounts; how does `AccountManager.request` route a
   `personal_sign` to the right account? Likely by including the
   address in the request and matching against registered strategies.
   Verify with a worked EIP-1193 example before Phase 3.

## 12. References

- See [`blockchain-spi`](blockchain-spi.md) §12 for chain-side
  references (BIPs, EIPs, CAIPs). The list below is wallet-specific.
- EIP-4337 — account abstraction
- EIP-6963 — multi-injected-provider discovery
- WebAuthn — passkey signing (p256)
- WalletConnect v2 spec (`docs.walletconnect.com`)
- Solana Wallet Standard (`github.com/wallet-standard`)
