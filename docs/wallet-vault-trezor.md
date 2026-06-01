# Trezor Hardware Wallet Vault

Spec for `wallet-vault-trezor` — a `Vault` SPI implementation backed by a
Trezor device via the [Trezor Bridge](https://github.com/trezor/trezord-go)
local HTTP daemon.

## Goals

- Full `Vault` SPI implementation for Trezor One, Model T, Model 3.
- EVM signing: `eth_sign` (EIP-191) and `eth_signTypedData` (EIP-712) via
  the Trezor Ethereum app messages.
- HD key derivation: `GetPublicKey` for any BIP-44 derivation path,
  returning a `RawSigner` scoped to that path.
- Session lifecycle (acquire → call → release) isolated per `Vault`
  instance so concurrent users don't clobber each other's sessions.
- Testable without a physical device: `MockTrezorBridge` replays canned
  JSON responses.

## Non-goals

- Bitcoin / Cardano / Solana app messages — deferred. The Trezor Ethereum
  app covers the primary use-case. Other chains are an extension point
  (inject a custom `TrezorAppMessageHandler`).
- Trezor Connect JS SDK — this module uses the Trezor Bridge REST API
  directly and does not wrap the official JS SDK.
- BLE / USB transport — handled by the Bridge; this module is transport-
  agnostic (just HTTP to `127.0.0.1:21325`).
- Passphrase-protected wallets — deferred.

## Architecture

```
payments/wallet/vault-trezor/
  src/main/scala/scalascript/wallet/vault/trezor/
    TrezorBridge.scala          ← HTTP client trait + impl + MockTrezorBridge
    TrezorSession.scala         ← acquire/release lifecycle (RAII)
    TrezorMessages.scala        ← JSON message ADT (Initialize / Features /
                                   GetPublicKey / EthereumSignMessage / …)
    TrezorEthVault.scala        ← Vault SPI for EVM (Secp256k1)
    TrezorVaultPlugin.scala     ← ServiceLoader stub
  src/test/scala/…/
    TrezorBridgeTest.scala      ← HTTP parsing + mock round-trips
    TrezorSessionTest.scala     ← acquire/release + double-release
    TrezorEthVaultTest.scala    ← sign / unlock / lock via mock bridge
```

### `TrezorBridge` trait

```scala
trait TrezorBridge:
  def version(): Future[String]
  def enumerate(): Future[Seq[TrezorDeviceInfo]]
  def acquire(path: String, previousSession: Option[String]): Future[String]
  def release(session: String): Future[Unit]
  def call(session: String, messageType: String, message: ujson.Value): Future[TrezorResponse]
```

`TrezorDeviceInfo(path: String, session: Option[String], vendor: String, product: String)`.

`TrezorResponse(messageType: String, message: ujson.Value)`.

`HttpTrezorBridge(baseUrl: String)` — default impl (`ujson` + `requests-scala`).

`MockTrezorBridge` — injectable map of `(messageType → response)` pairs;
records calls for assertion.

### `TrezorSession`

```scala
class TrezorSession(bridge: TrezorBridge, devicePath: String):
  def withSession[A](f: String => Future[A]): Future[A]
    // acquires, calls f, releases in finally
```

Calls `acquire(path, None)` (ignores prior session for simplicity); after
`f` completes (success or failure) calls `release(session)`.

### `TrezorEthVault`

Implements `Vault`. Lifecycle mirrors `LedgerEthereumVault`:
- `unlock(credential)` — acquires a `TrezorSession` (sends `Initialize`,
  verifies device is not PIN-locked via `Features.pin_protection` vs
  `Features.pin_cached`).
- `lock()` — releases session.
- `getSigner(Curve.Secp256k1, path)` — sends `GetPublicKey(path)`,
  returns a `TrezorEthSigner` bound to that path.
- `listAccounts()` — returns the default `m/44'/60'/0'/0/0` account
  descriptor.

`TrezorEthSigner` implements `RawSigner`:
- `sign(bytes)` — sends `EthereumSignMessage(address_n, message)`,
  returns the 65-byte signature.
- `publicKey` — populated once from `GetPublicKey`.

### Message shape (Trezor Bridge JSON protocol)

Request: `POST /call/<session>` with body
`{"type": "Initialize", "message": {}}`.

Response: `{"type": "Features", "message": { "initialized": true, ... }}`.

Key messages:

| Type | Direction | Purpose |
|------|-----------|---------|
| `Initialize` | → device | Start session, get device info |
| `Features` | ← device | Device metadata (firmware, initialized, pin_cached) |
| `GetPublicKey` | → device | Derive xpub at BIP-32 path |
| `PublicKey` | ← device | `xpub`, `node.public_key`, `node.chain_code` |
| `EthereumSignMessage` | → device | Sign EIP-191 message |
| `EthereumMessageSignature` | ← device | `address`, `signature` (hex) |
| `Failure` | ← device | Error with `code` + `message` |
| `ButtonRequest` | ← device | User confirmation needed; respond with `ButtonAck` |
| `PinMatrixRequest` | ← device | PIN needed (session not PIN-cached) |

BIP-32 paths are sent as `address_n` arrays: `m/44'/60'/0'/0/0` → `[2147483692, 2147483708, 2147483648, 0, 0]`.

## Migration

No callers to migrate — this is a new module. It sits alongside
`vault-ledger-*` as a peer.

## Phases

**Phase 1 (this PR)**
- `TrezorBridge` trait + `HttpTrezorBridge` impl.
- `TrezorSession.withSession` lifecycle.
- `TrezorMessages` ADT (Initialize, Features, GetPublicKey, PublicKey,
  EthereumSignMessage, EthereumMessageSignature, Failure, ButtonRequest,
  ButtonAck).
- `TrezorEthVault` + `TrezorEthSigner`.
- `MockTrezorBridge`.
- `sbt` subproject `walletVaultTrezor`.
- 20+ tests.

Future phases: Bitcoin app (P2WPKH/P2TR), Cardano app (CIP-8), passphrase
wallets, PIN matrix handling.

## Testing strategy

All tests use `MockTrezorBridge`. The mock stores a `Queue[TrezorResponse]`
per message type; tests push expected responses, call vault methods, assert
calls were received. No real device or daemon needed.

Integration test (gated on `TREZOR_BRIDGE_URL` env var) exercises the full
round-trip against a live bridge.

## Open questions

None — this is a straightforward adapter over a well-documented REST API.
