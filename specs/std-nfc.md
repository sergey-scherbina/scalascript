# `std.nfc`

## Overview

`std.nfc` exposes Near Field Communication as a standard platform capability.
User `.ssc` code calls a portable API for NDEF tag reading/writing and capability
checks; platform APIs such as Android `android.nfc.*`, Apple `CoreNFC`, and
browser `NDEFReader` stay behind backend/plugin adapters.

The MVP is intentionally NDEF-first. Raw tag technologies and card emulation are
separate capability-gated phases because they have different support and policy
constraints on Android, iOS, and Web NFC.

## Interface

Public package: `runtime/std/nfc.ssc`, package `std.nfc`.

Capability flags in `runtime/backend/spi/.../Feature.scala`:

- `NfcNdef` — baseline NDEF read/write/status.
- `NfcTagTech` — deferred raw tag communication (ISO7816, ISO15693, FeliCa,
  MIFARE, NFC-A/B/F/V).
- `NfcCardEmulation` — deferred card emulation / presentment.

MVP `.ssc` surface:

```scalascript
package std.nfc

case class NfcCapabilities(
  supported:       Boolean,
  enabled:         Boolean,
  ndefRead:        Boolean,
  ndefWrite:       Boolean,
  tagTech:         Boolean = false,
  cardEmulation:   Boolean = false,
  platform:        String = ""
)

sealed trait NfcPermission
case object NfcPermissionGranted extends NfcPermission
case object NfcPermissionDenied extends NfcPermission
case object NfcPermissionPrompt extends NfcPermission
case object NfcPermissionUnknown extends NfcPermission

case class NfcScanOptions(
  prompt:          String = "Hold an NFC tag near this device",
  timeoutMs:       Int = 60000,
  multiple:        Boolean = false,
  requireWritable: Boolean = false
)

case class NdefRecord(
  recordType: String,
  mediaType:  Option[String],
  id:         Option[String],
  encoding:   String,
  lang:       String,
  data:       List[Int]
)

case class NdefMessage(records: List[NdefRecord])

case class NfcTag(
  id:           Option[String],
  technologies: List[String],
  message:      NdefMessage
)

sealed trait NfcError
case object NfcNotSupported extends NfcError
case object NfcDisabled extends NfcError
case object NfcPermissionDeniedError extends NfcError
case object NfcCancelled extends NfcError
case object NfcTimeout extends NfcError
case object NfcTagLost extends NfcError
case class NfcInvalidMessage(reason: String) extends NfcError
case class NfcPlatformError(code: String, message: String) extends NfcError

extern def nfcCapabilities(): NfcCapabilities
extern def nfcPermissionStatus(): NfcPermission
extern def requestNfcPermission(): NfcPermission
extern def readNdef(options: NfcScanOptions = NfcScanOptions()): NfcTag
extern def writeNdef(message: NdefMessage, options: NfcScanOptions = NfcScanOptions()): Unit

extern def textRecord(text: String, lang: String = "en", id: Option[String] = None): NdefRecord
extern def uriRecord(uri: String, id: Option[String] = None): NdefRecord
extern def mimeRecord(mediaType: String, bytes: List[Int], id: Option[String] = None): NdefRecord
```

The byte representation is `List[Int]` with each element in `0..255`, matching
existing byte-like std APIs. Helper constructors validate or normalize this
shape inside the std/plugin boundary. They are declared as `extern def` in the
MVP because portable UTF-8 byte construction is not yet available as a pure
`.ssc` helper.

## Behavior

- [ ] Regular `.ssc` code never imports or references `android.*`, `CoreNFC`,
      `NDEFReader`, or other platform NFC APIs directly.
- [ ] Importing/calling `std.nfc` requires `Feature.NfcNdef`; a backend without
      the flag rejects the program or returns a typed `NfcNotSupported` result
      through the interpreter, depending on the existing intrinsic path.
- [ ] `nfcCapabilities()` is total and returns `supported = false` on unsupported
      targets instead of throwing.
- [ ] `nfcPermissionStatus()` is total. Web may report
      `NfcPermissionPrompt`/`NfcPermissionUnknown`; native adapters map platform
      state to `NfcPermissionGranted`, `NfcPermissionDenied`, or
      `NfcPermissionUnknown`.
- [ ] `requestNfcPermission()` may be a no-op on platforms where permission is
      install-time/entitlement-based, but it must never expose platform objects.
- [ ] `readNdef()` returns an `NfcTag` with zero or more NDEF records, preserving
      tag id when the platform exposes one and using `None` when it does not.
- [ ] `writeNdef()` writes an NDEF message to a compatible writable tag or fails
      predictably with `NfcNotSupported`, `NfcPermissionDeniedError`,
      `NfcInvalidMessage`, `NfcTagLost`, or `NfcTimeout`.
- [ ] Helper constructors produce portable NDEF records for text, URI, and MIME
      payloads without requiring platform-specific record classes.
- [ ] Native mobile packaging injects only the declarations required by the
      selected NFC capability.
- [ ] Tests cover unsupported interpreter behavior and pure helper encoding.

## Design

### Placement

`std.nfc` follows the project rule for new intrinsics: declarations live in
`runtime/std/nfc.ssc`; native/interpreter implementations live in
`runtime/std/nfc-plugin/`. No NFC intrinsic code is added to interpreter core.

Initial module layout:

```text
runtime/std/nfc.ssc
runtime/std/nfc-plugin/
  src/main/scala/scalascript/compiler/plugin/nfc/NfcInterpreterPlugin.scala
  src/main/scala/scalascript/compiler/plugin/nfc/NfcIntrinsics.scala
  src/main/resources/META-INF/services/scalascript.backend.spi.Backend
  src/test/scala/scalascript/compiler/plugin/nfc/NfcPluginTest.scala
```

### Backend policy

| Target | MVP behavior | Future native provider |
|---|---|---|
| Interpreter | deterministic unsupported/mockable intrinsics | Optional test adapter stored in plugin context |
| JS browser / PWA | `NDEFReader` when available; otherwise unsupported | Web NFC read/write |
| Node/JVM server | unsupported by default | External USB NFC readers are separate future plugins |
| Android | native adapter required | `NfcAdapter` reader mode, NDEF/tech APIs, manifest injection |
| iOS | native adapter required | Core NFC sessions, Info.plist + entitlement injection |

Browser Web NFC is not the canonical mobile implementation because it is
limited-availability and NDEF-only. It is still useful for PWA builds when the
host browser supports it.

### Native manifest and entitlement injection

Importing `std.nfc` or otherwise requiring `Feature.NfcNdef` causes native
packagers to inject NFC declarations. The exact insertion point follows the
existing native-platform pattern used for `std.geo`.

| Platform | MVP injected declaration |
|---|---|
| Android | `android.permission.NFC`; `android.hardware.nfc` with `required` controlled by app config |
| iOS | `NFCReaderUsageDescription`; NFC Tag Reading capability/entitlement |
| Web/PWA | No manifest injection; browser permission prompt/model applies |

Deferred `Feature.NfcCardEmulation` adds Android `android.hardware.nfc.hce`
and Android service metadata. iOS secure contactless/card-presentment requires
Apple NFC & SE Platform approval/entitlements and remains out of MVP.

### Error model

The public API names typed `NfcError` cases even if Phase 1 uses interpreter
errors internally. Native backends should converge on returning or throwing
these typed errors consistently with the project's broader error-handling work.
The important MVP invariant is that unsupported targets fail with clear NFC
diagnostics, not missing intrinsic/null failures.

### Testing

MVP tests:

- `NfcPluginTest` asserts every declared extern has an intrinsic entry.
- `nfcCapabilities()` on the interpreter returns a stable unsupported capability
  snapshot.
- `nfcPermissionStatus()` returns `Unknown` or `Denied` consistently in the
  unsupported interpreter adapter.
- `readNdef()` and `writeNdef()` fail with a clear unsupported diagnostic.
- Pure helper constructors in `runtime/std/nfc.ssc` compile through the normal
  frontend path.

Native adapter tests are deferred until Android/Kotlin and Swift business-code
backends can run real generated NFC code.

## Decisions

- **NDEF-first MVP** — chosen because Android, iOS, and Web NFC all expose NDEF
  as the safest common layer. Rejected: raw tag/APDU-first API, because iOS and
  Web have narrower support and extra entitlements.
- **Separate capability flags** — chosen so a Web-NFC backend can truthfully
  support NDEF without claiming raw tag tech or card emulation. Rejected: one
  broad `Nfc` flag, because it would hide meaningful platform gaps.
- **No direct platform APIs in `.ssc`** — chosen to preserve ScalaScript's
  target-agnostic semantics. Rejected: examples using `android.nfc.*`, Core NFC
  classes, or browser globals in regular `scalascript` blocks.
- **Card emulation deferred** — chosen because Android HCE and iOS NFC/SE
  presentment are security/product-policy features, not ordinary tag I/O.
- **Interpreter unsupported first** — chosen because CI cannot exercise real NFC
  hardware and the current repository has SwiftUI UI emission but no complete
  Android/Kotlin backend. Rejected: pretending full mobile NFC is implemented
  before a native adapter exists.
- **Prefixed permission and error cases** — chosen because std modules are often
  linked into one flat namespace today. Rejected: generic `Granted`, `Denied`,
  `Prompt`, and `Unknown` case objects, because they collide too easily with
  nearby std APIs and with the NFC error model.

## Out of scope

- Host card emulation, secure element presentment, payments, transit cards,
  access badges, and wallet/pass provisioning.
- Raw APDU/ISO7816/ISO15693/FeliCa/MIFARE APIs in the MVP.
- Background tag reading and app-launch association.
- External USB/PCSC NFC readers on JVM/desktop.
- A browser-only API that works only in Web NFC and is unavailable to native
  targets.

## Results

To be filled during verification.

## References

- Android NFC basics and `NfcAdapter` / NDEF dispatch.
- Android host-based card emulation and `HostApduService` for deferred
  `NfcCardEmulation`.
- Apple Core NFC tag reading/writing, `NFCReaderUsageDescription`, and NFC & SE
  Platform entitlement constraints.
- MDN Web NFC / `NDEFReader` limited availability and NDEF-only scope.
