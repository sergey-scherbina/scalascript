# Ledger Hardware Wallet Vault

Ledger hardware wallet support in ScalaScript consists of a shared protocol layer,
platform-specific transports, and per-chain vault implementations.

## Module overview

| Module | Target | Role | sbt project |
|--------|--------|------|-------------|
| `wallet-vault-ledger` | JVM + JS (shared) | Types, APDU codec, `LedgerTransport` trait | `walletVaultLedger` |
| `wallet-vault-ledger-jvm` | JVM | HID via hid4java | `walletVaultLedgerJvm` |
| `wallet-vault-ledger-js` | Scala.js / browser | WebHID (`navigator.hid`) | `walletVaultLedgerJs` |
| `wallet-vault-ledger-bluetooth-js` | Scala.js / browser | WebBLE (`navigator.bluetooth`) | `walletVaultLedgerBluetoothJs` |
| `wallet-vault-ledger-ethereum` | JVM | Ethereum app — sign tx / personal / EIP-712 | `walletVaultLedgerEthereum` |
| `wallet-vault-ledger-bitcoin` | JVM | Bitcoin app — PSBT, P2WPKH, P2TR (new protocol v2+) | `walletVaultLedgerBitcoin` |
| `wallet-vault-ledger-solana` | JVM | Solana app — Ed25519, off-chain message | `walletVaultLedgerSolana` |
| `wallet-vault-ledger-cardano` | JVM | Cardano app — CIP-8 / CIP-1852 | `walletVaultLedgerCardano` |

Chain vaults accept any `LedgerTransport` — JVM HID, WebHID (JS), or WebBLE (JS) —
so the same vault implementation works across all host environments.

---

## Shared layer (`wallet-vault-ledger`)

### `LedgerTransport` trait

```scala
trait LedgerTransport:
  def open(): Future[Unit]
  def close(): Future[Unit]
  def exchange(apdu: Array[Byte]): Future[Array[Byte]]
  def isOpen: Boolean
```

`exchange` sends a raw APDU command and returns the response bytes
(payload + 2 status-word bytes `sw1 sw2`). The transport does not interpret
responses — status-word checking and payload parsing live in the app layer.

### APDU status words

Common status words (ISO 7816 + Ledger extensions):

| Code | Meaning |
|------|---------|
| `0x9000` | Success |
| `0x6982` | Security status not satisfied (device locked) |
| `0x6985` | User declined on device |
| `0x6A82` | Application not found / app not open |
| `0x6B00` | Invalid P1/P2 |
| `0x6D00` | Unknown INS (wrong app open) |
| `0x6E00` | Unknown CLA (wrong app open) |

### `Bip32Path` — BIP-32 derivation path encoder

Parses `m/44'/60'/0'/0/0`-style strings into the 4-byte big-endian integers
the Ledger wire format expects. Hardened segments (`'`) have bit `0x80000000` set.

Built-in defaults:

| Chain | Path |
|-------|------|
| Ethereum | `m/44'/60'/0'/0/0` |
| Solana | `m/44'/501'/0'/0'` |
| Bitcoin (BIP-84 segwit) | `m/84'/0'/0'/0/0` |
| Cardano (CIP-1852) | `m/1852'/1815'/0'/0/0` |

### `Dashboard` — app detection

```scala
Dashboard.getAppName(transport): Future[AppInfo]
// AppInfo(name: String, version: String)
```

Sends `B0 01 00 00` (`getAppAndVersion`) to the BOLOS dashboard app.
The Vault layer calls this in `getSigner` to verify the correct chain app
is open before signing. If the running app doesn't match, `AppSwitchRequired`
is raised so the host can prompt the user.

### `AppSwitchRequired`

```scala
case class AppSwitchRequired(currentApp: String, requiredApp: String) extends RuntimeException
// message: "Ledger app mismatch: currently 'Ethereum', need 'Bitcoin'. Open the 'Bitcoin' app on the device and retry."
```

Only one Ledger app is active at a time. The host must surface this to the
user and retry `getSigner` after the user switches apps.

### `CurveAppRouting`

Maps `(Curve, CAIP-2 namespace)` to the required on-device app name:

| Curve | Namespace | App |
|-------|-----------|-----|
| `Secp256k1` | `eip155` | `"Ethereum"` |
| `Secp256k1` | `bip122` | `"Bitcoin"` |
| `Ed25519` | `solana` | `"Solana"` |
| `Ed25519` | `cardano` | `"Cardano ADA"` |

---

## JVM HID transport (`wallet-vault-ledger-jvm`)

`Hid4JavaTransport` wraps [hid4java](https://github.com/gary-rowe/hid4java)
to talk to a Ledger device over USB HID on the JVM.

```scala
import scalascript.wallet.vault.ledger.jvm.Hid4JavaTransport
import scalascript.wallet.vault.ledger.ethereum.LedgerEthereumVault

given ExecutionContext = ...

val transport = Hid4JavaTransport()          // picks the first Ledger on the USB bus
// Or pick by serial number:
val transport = Hid4JavaTransport(serialNumber = Some("ABC123"))

val vault = LedgerEthereumVault(transport)
await(vault.unlock(UnlockCredential.None))
val signer = await(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"))
```

Ledger vendor ID is `0x2C97`. Devices are selected by vendor ID; if multiple
Ledgers are attached, pass a `serialNumber` to disambiguate.

### HID framing

USB HID reports are 64 bytes each. Every frame has a 5-byte header:

```
bytes 0–1 : channel = 0x0101
byte  2   : command tag = 0x05
bytes 3–4 : sequence number (big-endian, starts at 0)
```

The first frame also carries:

```
bytes 5–6 : total APDU length (big-endian)
```

Frames are padded to exactly 64 bytes with `0x00`. `HidFraming` handles
encoding and decoding; it is unit-tested independently of hid4java.

### Testing

`Hid4JavaTransport` itself is not exercised by automated tests (no physical
device). The framing logic in `HidFraming` is fully unit-tested against the
wire format.

---

## WebHID transport (`wallet-vault-ledger-js`)

`WebHidLedgerTransport` uses the browser's [WebHID API](https://developer.mozilla.org/en-US/docs/Web/API/WebHID_API)
(`navigator.hid`). Works in Chromium-based browsers.

```scala
import scalascript.wallet.vault.ledger.js.WebHidLedgerTransport

// Browser-side (Scala.js):
for
  transport <- WebHidLedgerTransport.requestLedger()   // opens browser device picker
  _         <- transport.open()
  vault     =  LedgerEthVault(transport)               // existing vault — no changes
  address   <- vault.getAddress("m/44'/60'/0'/0/0")
yield println(s"Address: $address")
```

`WebHidFraming` uses the same channel/tag/seq/length header and 64-byte
padded frames as the JVM HID transport. The `WebHidDevice` trait abstracts
the browser WebHID handle, enabling tests via `MockWebHidDevice` (in-memory).

### Dependency

```scala
"org.scala-js" %%% "scalajs-dom" % "2.8.0"
```

---

## WebBLE transport (`wallet-vault-ledger-bluetooth-js`)

`WebBleTransport` uses the browser's [Web Bluetooth API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API)
(`navigator.bluetooth`). Works with Ledger Nano X and Stax.

### BLE GATT UUIDs

| Role | UUID |
|------|------|
| Service | `13d63400-2c97-0004-0000-4c6564676572` |
| Write (host → device) | `13d63400-2c97-0004-0001-4c6564676572` |
| Notify (device → host) | `13d63400-2c97-0004-0002-4c6564676572` |

### BLE framing vs HID framing

Both use the same 5-byte header (channel + tag + seq). Key difference:
HID frames are padded to exactly 64 bytes; BLE frames are variable length
(`mtu - 3` bytes, no trailing padding). The 3-byte ATT overhead leaves 20
bytes per frame at the default BLE MTU of 23.

```
Default MTU 23  → 20 bytes payload per frame
Negotiated 100  → 97 bytes payload per frame
Negotiated 244  → 241 bytes payload per frame
```

### Usage

```scala
import scalascript.wallet.vault.ledger.js.ble.WebBleTransport
import scalascript.wallet.vault.ledger.ethereum.LedgerEthereumVault

for
  transport <- WebBleTransport.requestLedger()      // browser device picker
  _         <- transport.open()
  vault     =  LedgerEthereumVault(transport)
  signer    <- vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0")
yield signer
```

Pass a negotiated MTU after the GATT connection is established:

```scala
val transport = WebBleTransport.requestLedger(mtu = 244)
```

### Architecture

```
WebBleTransport(device: WebBleDevice, framing: BleFraming)
  ├─ BleFraming(mtu)           — header + variable-length frames
  └─ WebBleDevice              — abstract interface (testable)
        ├─ BrowserBluetoothDevice   — live: navigator.bluetooth GATT
        └─ MockBluetoothDevice      — test: in-memory frame queue
```

### Testing

```scala
import scalascript.wallet.vault.ledger.js.ble.{BleFraming, MockBluetoothDevice, WebBleTransport}

val device = MockBluetoothDevice()
device.queueApdu(responseBytes)          // auto-frames via BleFraming
val t = WebBleTransport(device)
for
  _ <- t.open()
  r <- t.exchange(requestApdu)
yield assert(r.sameElements(responseBytes))
```

---

## Ethereum vault (`wallet-vault-ledger-ethereum`)

`LedgerEthereumVault` implements the Wallet SPI `Vault` for Ethereum / EVM.
Uses the Ledger Ethereum app (CLA `0xE0`).

### Supported operations

| Operation | INS | Description |
|-----------|-----|-------------|
| `GET_PUBLIC_KEY` | `0x02` | Returns compressed public key + Ethereum address |
| `SIGN_TRANSACTION` | `0x04` | Signs an RLP-encoded transaction; returns `v, r, s` |
| `GET_APP_CONFIGURATION` | `0x06` | Returns firmware flags + version |
| `SIGN_PERSONAL_MESSAGE` | `0x08` | EIP-191 personal sign |
| `SIGN_EIP712` | `0x0C` | EIP-712 typed data sign (domain hash + message hash) |

Chunking: SIGN_TRANSACTION and SIGN_PERSONAL_MESSAGE split payloads > 255
bytes across multiple APDUs. P1 = `0x00` (first), `0x80` (continuation).

Curve: `Secp256k1` only.

### Usage

```scala
import scalascript.wallet.vault.ledger.ethereum.LedgerEthereumVault
import scalascript.wallet.vault.ledger.jvm.Hid4JavaTransport

val vault = LedgerEthereumVault(Hid4JavaTransport())
await(vault.unlock(UnlockCredential.None))

// Get address
val accounts = await(vault.listAccounts())
// accounts(0) → AccountDescriptor(id="ledger-eth-0", derivationPath="m/44'/60'/0'/0/0", ...)

// Sign
val signer = await(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"))
val sig    = await(signer.sign(txHash, HashAlgo.None))  // returns 65-byte (v, r, s)
```

`getSigner` probes `Dashboard.getAppName` first. If the Ethereum app is not
active, `AppSwitchRequired("Bitcoin", "Ethereum")` is raised.

---

## Bitcoin vault (`wallet-vault-ledger-bitcoin`)

`LedgerBitcoinVault` uses the Ledger Bitcoin app (new protocol v2+, CLA `0xE1`).

### Supported operations

| Operation | INS | Description |
|-----------|-----|-------------|
| `GET_EXTENDED_PUBKEY` | `0x00` | BIP-32 xpub (chain code + pubkey) |
| `REGISTER_WALLET` | `0x02` | Register multisig policy (skipped for single-sig) |
| `GET_WALLET_ADDRESS` | `0x03` | Derive a receiving address |
| `SIGN_PSBT` | `0x04` | Sign a PSBT; returns per-input partial signatures |

Default path: `m/84'/0'/0'` (BIP-84 Native SegWit account level).

`GET_EXTENDED_PUBKEY` returns 74 bytes:
`chain_code(32) || pubkey(33) || fingerprint(4) || depth(1) || child_number(4)`.

SIGN_PSBT response: `n_sigs(1) || (sig_len(1) || sig(sig_len))*`.

Curve: `Secp256k1` only.

### Usage

```scala
import scalascript.wallet.vault.ledger.bitcoin.LedgerBitcoinVault
import scalascript.wallet.vault.ledger.jvm.Hid4JavaTransport

val vault = LedgerBitcoinVault(Hid4JavaTransport())
await(vault.unlock(UnlockCredential.None))
val signer = await(vault.getSigner(Curve.Secp256k1, "m/84'/0'/0'/0/0"))
val sig    = await(signer.sign(psbtHash, HashAlgo.None))
```

---

## Solana vault (`wallet-vault-ledger-solana`)

`LedgerSolanaVault` uses the Ledger Solana app (CLA `0xE0`).

### Supported operations

| Operation | INS | Description |
|-----------|-----|-------------|
| `GET_PUBKEY` | `0x05` | Returns 32-byte Ed25519 public key |
| `SIGN_TRANSACTION` | `0x04` | Signs a serialized Solana transaction; returns 64-byte sig |
| `SIGN_OFFCHAIN_MESSAGE` | `0x07` | Signs an off-chain message (wallet signature standard) |

Default path: `m/44'/501'/0'/0'`.

Chunking: same first (`P1=0x00`) / continuation (`P1=0x80`) pattern.

Curve: `Ed25519` only. Signatures are 64 bytes (no DER encoding).

`Base58` encoder is included in the module for Solana address formatting.

### Usage

```scala
import scalascript.wallet.vault.ledger.solana.LedgerSolanaVault
import scalascript.wallet.vault.ledger.jvm.Hid4JavaTransport

val vault = LedgerSolanaVault(Hid4JavaTransport())
await(vault.unlock(UnlockCredential.None))
val signer = await(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'/0'"))
val sig    = await(signer.sign(txBytes, HashAlgo.None))  // 64-byte Ed25519 signature
```

---

## Cardano vault (`wallet-vault-ledger-cardano`)

`LedgerCardanoVault` uses the Ledger Cardano app (CLA `0xD7`).

### Supported operations

| Operation | INS | Description |
|-----------|-----|-------------|
| `GET_EXTENDED_PUBLIC_KEY` | `0x10` | 32-byte Ed25519 pubkey + 32-byte chain code |
| `SIGN_TX` | `0x21` | CIP-8 data sign — signs a COSE Sig_Structure |

Default path: `m/1852'/1815'/0'/0/0` (CIP-1852 Shelley).

### CIP-8 signing

Cardano dApp signing follows [CIP-0008](https://github.com/cardano-foundation/CIPs/tree/master/CIP-0008)
(COSE_Sign1 envelope). `CardanoCip8` builds the signed payload:

```scala
// Protected header: CBOR {1: -8}  (alg: EdDSA)
val header    = CardanoCip8.protectedHeader
// Sig_Structure: ["Signature1", bstr(header), bstr(""), bstr(payload)]
val toSign    = CardanoCip8.sigStructure(payload)
```

The device signs `sigStructure`; the host assembles the full COSE_Sign1
envelope from the returned 64-byte signature.

### Usage

```scala
import scalascript.wallet.vault.ledger.cardano.LedgerCardanoVault
import scalascript.wallet.vault.ledger.jvm.Hid4JavaTransport

val vault = LedgerCardanoVault(Hid4JavaTransport())
await(vault.unlock(UnlockCredential.None))
val signer = await(vault.getSigner(Curve.Ed25519, "m/1852'/1815'/0'/0/0"))
val sig    = await(signer.sign(cip8Payload, HashAlgo.None))  // 64-byte Ed25519 sig
```

---

## Composing transport and vault

All chain vaults accept any `LedgerTransport`. Switch transport without
touching vault code:

```scala
// JVM desktop app
val t = Hid4JavaTransport()

// Browser with WebHID
val t = await(WebHidLedgerTransport.requestLedger())

// Browser with Bluetooth (Nano X / Stax)
val t = await(WebBleTransport.requestLedger())

// Same vault regardless of transport:
val vault = LedgerEthereumVault(t)
```

### App switching pattern

```scala
import scalascript.wallet.vault.ledger.AppSwitchRequired

vault.getSigner(Curve.Secp256k1, path).recover {
  case AppSwitchRequired(current, required) =>
    println(s"Please open the $required app on your Ledger (currently: $current)")
    Future.failed(AppSwitchRequired(current, required))
}
```

---

## Testing strategy

| Layer | Approach |
|-------|----------|
| `HidFraming` / `WebHidFraming` / `BleFraming` | Unit-tested: encode → decode round-trips, multi-frame APDUs, sequence numbers |
| `WebHidLedgerTransport` | Unit-tested via `MockWebHidDevice` |
| `WebBleTransport` | Unit-tested via `MockBluetoothDevice` (12 tests) |
| `Hid4JavaTransport` | No automated tests (no physical device); framing tested separately |
| Chain vaults (Ethereum/Bitcoin/Solana/Cardano) | Integration-tested via stub transport returning canned APDU responses |

```bash
sbt "walletVaultLedgerBluetoothJs/test"   # BLE framing + transport
sbt "walletVaultLedgerEthereum/test"       # Ethereum app + vault
sbt "walletVaultLedgerBitcoin/test"        # Bitcoin app + vault
sbt "walletVaultLedgerSolana/test"         # Solana app + vault
sbt "walletVaultLedgerCardano/test"        # Cardano app + vault
```
