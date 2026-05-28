# Ledger Hardware Wallet Vault

Ledger hardware wallet support in ScalaScript consists of a shared layer and
transport-specific modules for different host environments.

## Modules

| Module | Target | Transport |
|--------|--------|-----------|
| `wallet-vault-ledger` | JVM + JS (shared) | Types, APDU codec, `LedgerTransport` trait |
| `wallet-vault-ledger-jvm` | JVM | HID via hid4java |
| `wallet-vault-ledger-js` | Scala.js / browser | WebHID (`navigator.hid`) |
| `wallet-vault-ledger-bluetooth-js` | Scala.js / browser | WebBLE (`navigator.bluetooth`) |
| `wallet-vault-ledger-ethereum` | JVM | Ethereum app (sign / getAddress) |
| `wallet-vault-ledger-bitcoin` | JVM | Bitcoin app (PSBT / P2WPKH / P2TR) |
| `wallet-vault-ledger-solana` | JVM | Solana app (Ed25519) |
| `wallet-vault-ledger-cardano` | JVM | Cardano app (CIP-8 / CIP-19) |

See `docs/wallet-spi.md §5.1` for the full architecture and how transports
compose with vault implementations.

## §bluetooth-transport — WebBLE transport (`wallet-vault-ledger-bluetooth-js`)

### Goals

- `WebBleTransport` implementing `LedgerTransport` for Ledger Nano X / Stax
  over the browser's Web Bluetooth API (`navigator.bluetooth`).
- Reuses the same APDU channel/tag/seq framing as the HID transport, adapted
  to the negotiated BLE MTU (default 23 bytes, payload 20 bytes per frame).
- Testable without a physical device via `MockBluetoothDevice`.
- All existing vault implementations (`LedgerEthVault`, `LedgerBitcoinVault`,
  etc.) transparently accept any `LedgerTransport` — no vault changes needed.

### Non-goals

- React Native / Expo BLE — browser Web Bluetooth only.
- MTU negotiation — caller supplies the negotiated MTU; default is 23 bytes.
- Bonding, pairing UI, device re-connection — handled by the browser.

### Architecture

```
WebBleTransport(device: WebBleDevice, framing: BleFraming)
        │
        ├─ BleFraming(mtu)          — same channel/tag/seq header as HID,
        │                             variable frame size (mtu - 3 bytes)
        │
        └─ WebBleDevice             — abstract interface (testable)
              │
              ├─ BrowserBluetoothDevice   — live: navigator.bluetooth GATT
              └─ MockBluetoothDevice      — test: in-memory queue
```

**BLE GATT UUIDs** (Ledger Nano X / Stax):

| Role | UUID |
|------|------|
| Service | `13d63400-2c97-0004-0000-4c6564676572` |
| Write (host → device) | `13d63400-2c97-0004-0001-4c6564676572` |
| Notify (device → host) | `13d63400-2c97-0004-0002-4c6564676572` |

### BLE framing vs HID framing

Both transports use the same 5-byte header:

```
byte 0–1: channel ID = 0x0101
byte 2  : command tag = 0x05
byte 3–4: sequence number (big-endian, starts at 0)
```

First frame additionally carries:

```
byte 5–6: total APDU length (big-endian)
```

Key difference: HID frames are **padded to exactly 64 bytes**; BLE frames
are **variable length** (no trailing padding), sized at `mtu - 3` bytes
(3 bytes for ATT protocol overhead).  With the default MTU of 23 this gives
20 bytes per frame; with a negotiated MTU of 100 it gives 97 bytes.

### Usage

```scala
import scalascript.wallet.vault.ledger.js.ble.WebBleTransport
import scalascript.wallet.vault.ledger.ethereum.LedgerEthereumVault

// Browser-side (Scala.js):
for
  transport <- WebBleTransport.requestLedger()    // prompts device picker
  _         <- transport.open()
  vault     =  LedgerEthereumVault(transport)
  address   <- vault.getAddress("m/44'/60'/0'/0/0")
yield println(s"Ledger address: $address")
```

Pass a negotiated MTU if the browser reports it after connection:

```scala
val transport = WebBleTransport.requestLedger(mtu = 244)
```

### Testing

```scala
import scalascript.wallet.vault.ledger.js.ble.{MockBluetoothDevice, WebBleTransport}

val device = MockBluetoothDevice()
device.queueApdu(responseBytes)       // queue a pre-built APDU response
val t = WebBleTransport(device)
for
  _ <- t.open()
  r <- t.exchange(requestApdu)
yield assert(r.sameElements(responseBytes))
```
