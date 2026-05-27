# Wallet SPI — Scala.js cross-compile

Status: **draft / Stage 1 landed**. Source of truth for the cross-compile
sweep that takes the wallet-spi track from JVM-only to JVM + Scala.js so
the same SPI artefacts run inside browser PWAs and dApp connectors.
Until each stage below is checked off in
[`MILESTONES.md`](../MILESTONES.md), the code does not match this design.

Anchored on the cross-compile model from
[`docs/wallet-spi.md`](wallet-spi.md) §8 — wallet-spi, wallet-strategy-*,
wallet-connect, and wallet-vault-encrypted are explicitly meant to be
cross-compile projects. This document describes how that lands.

## 1. Goals

- **Enable wallet-spi modules to run inside Scala.js.** Browser PWA
  wallets, in-page dApp connectors (EIP-1193 / WalletConnect / Solana
  Wallet Standard), and browser-side x402 clients all need the wallet
  SPI to be available as Scala.js artefacts.
- **No regression to JVM behaviour.** Existing JVM consumers
  (`x402-client`, `mcp-wallet`, `wallet-vault-encrypted-jvm`,
  `wallet-vault-ledger-jvm`, the entire JVM test suite) keep compiling
  and running unchanged. The cross-compile JVM target is exactly the
  artefact those consumers depend on today.
- **Use the standard sbt cross-compile machinery.** `sbt-scalajs` +
  `sbt-crossproject` + `sbt-scalajs-crossproject`; `CrossType.Full`
  layout (`shared/` + `jvm/` + `js/`); the `xxxCross.jvm` /
  `xxxCross.js` accessors give us the per-platform sbt projects.
- **One source tree per module, two platforms.** Shared SPI traits
  + value classes live in `shared/src/main/scala/`. Platform-specific
  glue (the ServiceLoader-based registries in crypto-spi / blockchain-spi
  today) moves into `jvm/src/main/scala/` with the Scala.js equivalent
  (static registry-only — see §4.2) landing in `js/src/main/scala/` as
  later stages need it.

## 2. Non-goals

- **Cross-compiling `wallet-vault-encrypted` is deferred.** It pulls in
  JCE (`javax.crypto.Cipher`, `SecretKeyFactory`, `PBEKeySpec`) and
  `java.security.SecureRandom`. The browser equivalent is the
  SubtleCrypto + WebCrypto API. The port is a self-contained Stage 5;
  this slice does not start on it.
- **Cross-compiling `wallet-connect` is deferred.** It uses
  `java.net.http.WebSocket` (JDK-native WS client) and JCE primitives
  for the Noise handshake / Chacha20Poly1305 frame encryption. Browser
  equivalent: native `WebSocket` + WebCrypto AEAD. Self-contained
  Stage 6.
- **No Scala.js impls of `crypto-bouncycastle` or `blockchain-evm`.**
  Stage 1 cross-compiles only the SPI traits — the JVM-specific impl
  modules (`crypto-bouncycastle`, `blockchain-evm`, `blockchain-cardano`,
  `blockchain-solana`, `blockchain-evm-abi`) stay JVM-only. A future
  `crypto-noble-js` / `blockchain-evm-js` will provide the Scala.js
  impl side; the cross-compiled traits make that possible.
- **No CI wiring in this slice.** Stage 1 demonstrates that
  `sbt walletSpiJs/test` runs locally on Node.js; CI hookup follows
  once enough modules cross-compile to make a JS test suite worth
  scheduling separately.
- **No `wallet-connector-eip1193-js` / `wallet-connector-wallet-std`
  in this slice.** Those need DOM facades (`window.ethereum`,
  `@wallet-standard/core`); cross-compiling them lands in Stage 3
  after the SPI substrate is in place.
- **No `wallet-vault-passkey-js`.** WebAuthn is JS-only by nature;
  it'll land directly as a Scala.js project once Stage 5
  (encrypted vault) shows the SubtleCrypto adapter pattern.

## 3. Architecture

### 3.1 Which modules cross-compile, in what order

Stage 1 (this slice):

- **`wallet-spi`** — `RawSigner` / `Vault` / `AccountStrategy` /
  `DappConnector` / `AccountManager`. Zero `java.*` deps in its own
  sources. Two transitive deps (`crypto-spi`, `blockchain-spi`) need
  to cross-compile too because the wallet-spi sources import types
  from them (`Curve`, `PublicKey`, `HashAlgo` and `ChainAdapter`,
  `ChainId`, `TypedData`). The cross-compile of those two is small
  enough to land in the same slice — see §3.2.

Stage 2 — JVM-side impl on JS (next slice):

- `crypto-noble-js` — Scala.js implementation of `CryptoBackend`
  backed by `@noble/curves` + `@noble/hashes`.
- `blockchain-evm-js` (if needed) — adapter parts that compile to JS;
  the existing `blockchain-evm` JVM module stays.

Stage 3 — Strategy modules + EIP-1193 / Wallet Standard connectors:

- `wallet-strategy-eoa` cross-compile.
- `wallet-connector-eip1193` cross-compile + `window.ethereum`
  injection glue in the new `js/` source tree.
- `wallet-connector-wallet-std` cross-compile + `registerWallet`
  facade.

Stage 4 — ERC-4337 + smart-account:

- `wallet-strategy-erc4337` cross-compile (small `java.*` surface
  today: `java.math.BigInteger` in a couple of helpers; replaceable
  by `BigInt` where the Scala stdlib equivalent suffices).

Stage 5 — Encrypted Vault Scala.js port:

- `wallet-vault-encrypted` cross-compile.
- JS side: SubtleCrypto adapter for `AES-GCM` + `PBKDF2`
  (Argon2id is not in WebCrypto; either keep it JS-impl via
  `@noble/hashes` argon2 wrapper or fall back to PBKDF2 with a
  high iteration count for the JS variant).

Stage 6 — WalletConnect v2:

- `wallet-connect` cross-compile.
- JS side: native `WebSocket` adapter + WebCrypto AEAD; the existing
  JVM side keeps using `java.net.http.WebSocket` + BouncyCastle.

### 3.2 Why crypto-spi and blockchain-spi cross-compile in Stage 1

`wallet-spi` sources import six types from those two modules
(`Curve`, `HashAlgo`, `PublicKey`, `ChainAdapter`, `ChainId`,
`TypedData`). Cross-compiling wallet-spi without those would create a
type fork (a different `ChainAdapter` shape on JVM vs JS), which is
exactly what cross-compile is supposed to avoid.

The good news is the SPI surface of crypto-spi and blockchain-spi is
**already JS-clean except for one file each**:

- `crypto-spi/.../CryptoBackend.scala` — only the `object CryptoBackend`
  companion uses `java.util.ServiceLoader`. The `trait CryptoBackend`
  is pure.
- `blockchain-spi/.../Blockchain.scala` — entire file is a
  ServiceLoader-backed registry.

Stage 1 moves those two registry-objects into `jvm/src/main/scala/`
and leaves traits + value-class types in `shared/src/main/scala/`.
The Scala.js side gets no registry today — wallet-spi consumers on JS
don't yet need one (Stage 2 introduces the static-registry pattern
when `crypto-noble-js` lands). For now, the JS side compiles the
traits and a smoke test exercises them.

This is a minor expansion of "cross-compile only wallet-spi"; it's
the cheapest way to get wallet-spi to actually cross-compile.

### 3.3 CrossType selection

| Module          | CrossType | Why |
|-----------------|-----------|-----|
| `crypto-spi`    | `Full`    | JVM-only `object CryptoBackend` ServiceLoader code lives in `jvm/`; JS side currently has no companion-object content. |
| `blockchain-spi`| `Full`    | Same — `object Blockchain` ServiceLoader registry lives in `jvm/`. |
| `wallet-spi`    | `Full`    | Sources today are JS-clean; `shared/` holds all four files. `jvm/` and `js/` are empty placeholders for future platform-specific helpers (e.g. a `ServiceLoader`-based `AccountManager` discovery on JVM, a static-registry equivalent on JS). |

`CrossType.Full` (rather than `Pure`) is chosen uniformly so all
three modules have the same layout and future per-platform
escape-hatches don't need a layout migration.

### 3.4 ServiceLoader → static-registry pattern for Scala.js

`ServiceLoader` is JVM-only. The standard Scala.js workaround used
across the project is a static registry: backends call
`Registry.register(impl)` from a top-level Scala.js initialiser
(or from an explicit `init()` block at app entry).

Stage 1 leaves the JS-side registry empty. Stage 2 lands a real
JS-side `object CryptoBackend` with `register(...)` + `get(id)` +
`all` methods that match the JVM API surface; impl modules call
`CryptoBackend.register(NobleJsBackend)` at module load. Same shape
for `object Blockchain`.

The pattern is shared with `runtime-server-spi` / `backend-spi`:
**SPI** modules don't bake in ServiceLoader; **registry** lives in a
companion object that has a JVM impl (ServiceLoader) and a JS impl
(explicit registration), both fronted by the same `lookup` /
`all` / `get` API.

### 3.5 Shared source layout

```
wallet-spi/
  shared/src/main/scala/scalascript/wallet/spi/
    Vault.scala
    RawSigner.scala
    AccountStrategy.scala
    DappConnector.scala
  shared/src/test/scala/scalascript/wallet/spi/
    CrossCompileSmokeTest.scala        ← runs on JVM + JS
  jvm/src/main/scala/                  ← empty (placeholder)
  js/src/main/scala/                   ← empty (placeholder)
```

Same shape for crypto-spi and blockchain-spi, except their `jvm/`
holds the ServiceLoader-backed registry-object companion to the
shared trait.

## 4. Build wiring

### 4.1 plugins.sbt

```
addSbtPlugin("org.scala-js"      % "sbt-scalajs"              % "1.16.0")
addSbtPlugin("org.portable-scala"% "sbt-crossproject"         % "1.3.2")
addSbtPlugin("org.portable-scala"% "sbt-scalajs-crossproject" % "1.3.2")
```

Scala.js 1.16.0 supports Scala 3.8.x — verified locally with the
project's pinned `scalaVersion := "3.8.3"`. If a future Scala bump
breaks compatibility, pin to whichever Scala.js version supports the
new Scala first.

### 4.2 build.sbt — crossProject pattern

Each cross-compiled SPI becomes a `crossProject(JVMPlatform, JSPlatform)`
with two convenience aliases:

```scala
import org.portable_scala.sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

lazy val walletSpiCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet-spi"))
    .dependsOn(blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-spi",
      libraryDependencies += "com.lihaoyi" %%% "upickle" % "3.3.1",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )

lazy val walletSpiJvm = walletSpiCross.jvm
lazy val walletSpiJs  = walletSpiCross.js
// Alias kept for the dozens of `.dependsOn(walletSpi)` references
// elsewhere in build.sbt — they continue to target the JVM artefact.
lazy val walletSpi    = walletSpiJvm
```

The `%%%` operator (from sbt-crossproject) resolves to `%%` on JVM
and to the Scala.js variant on JS. upickle and scalatest both ship
Scala.js artefacts so the same dependency declaration works on both
sides.

### 4.3 Aggregator

The root aggregator gains `walletSpiJs` (and `cryptoSpiJs`,
`blockchainSpiJs`) so plain `sbt compile` builds both platforms.
Existing aggregate entries for `walletSpi` / `cryptoSpi` /
`blockchainSpi` are kept (resolving to the `*Jvm` alias) so other
modules that aggregate them transitively keep working.

### 4.4 Test execution on Scala.js

Default Scala.js test execution uses Node.js (`scalaJSUseMainModuleInitializer
:= false` is the default for non-app modules). The agent and CI need
Node ≥ 18 on `PATH`. `sbt walletSpiJs/test` compiles the smoke test
through the Scala.js linker, runs the resulting `.js` under Node, and
fails the build if any assertion fails.

A comment in `build.sbt` next to `walletSpiJs` flags the Node
requirement so a fresh CI image / agent setup script knows what to
install.

## 5. Migration plan

### Stage 1 — Plugins + wallet-spi (+ crypto-spi + blockchain-spi cross-compile)

**This slice.** See deliverables in the task brief.

1. plugins.sbt — add `sbt-scalajs`, `sbt-crossproject`,
   `sbt-scalajs-crossproject`.
2. Move `wallet-spi/src/main/scala/...` to
   `wallet-spi/shared/src/main/scala/...`. No content changes.
3. Move `crypto-spi/src/main/scala/...` to
   `crypto-spi/shared/src/main/scala/...`, with `object CryptoBackend`
   ServiceLoader block (only) split into
   `crypto-spi/jvm/src/main/scala/scalascript/crypto/CryptoBackendJvm.scala`.
4. Move `blockchain-spi/src/main/scala/...` similarly; the entire
   `Blockchain.scala` file moves to
   `blockchain-spi/jvm/src/main/scala/`.
5. Convert all three to `crossProject(JVMPlatform, JSPlatform)` in
   build.sbt; introduce `xxxJvm` / `xxxJs` lazy vals + `xxx` JVM alias.
6. Add `walletSpiJs`, `cryptoSpiJs`, `blockchainSpiJs` to the root
   aggregator.
7. Add `CrossCompileSmokeTest` in `wallet-spi/shared/src/test/scala/`.
8. Verify `sbt walletSpi/test walletSpiJs/test compile`.

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js)

Status: **landed 2026-05-20**. The Stage 1 `object CryptoBackend`
already exposes the cross-platform `register(...)` / `all` / `get(id)`
surface (see [CryptoBackend.scala](../crypto-spi/shared/src/main/scala/scalascript/crypto/CryptoBackend.scala)
§ companion). Stage 2 lands the first JS impl that registers through
it:

- `crypto-noble-js` (`crypto-noble-js/`) — Scala.js-only sbt project
  (`enablePlugins(ScalaJSPlugin)`), depends on `cryptoSpiJs`. Module
  kind set to `CommonJSModule` so noble v1.x's `require()`-style
  exports resolve at link time.
- `NobleFacades.scala` — `@JSImport` facades over
  `@noble/curves/{secp256k1, ed25519, p256}` and `@noble/hashes/{sha256,
  sha512, sha3, ripemd160, hmac, hkdf}`. Only the call surface needed
  by `NobleCryptoBackend` is bound.
- `NobleCryptoBackend.scala` — `CryptoBackend` impl for secp256k1 /
  ed25519 / p256 (the three the JVM `BouncyCastleBackend` supports).
  Output bytes match JVM bit-for-bit (verified by the
  `CrossPlatformFixturesTest` in `crypto-bouncycastle/src/test/`, which
  asserts the same hex strings the JS test asserts).
- `Register.scala` — `Register.install()` for Scala-side init, plus
  `@JSExportTopLevel("registerNobleCryptoBackend")` for host JS init.
- `NobleCryptoBackendTest.scala` — 16 scalatest specs under Node:
  empty-string sha256 / keccak256 / sha512, HMAC-SHA256 RFC 4231 #1,
  HKDF-SHA256 RFC 5869 #1, RFC 8032 ed25519 vector 1 (derivePublic +
  sign empty msg), secp256k1 derive / sign-verify / recover-pubkey /
  EVM-address (privkey 0x4646… → 0x9d8a62f6…) / tamper rejection,
  p256 derive + sign-verify, and registry round-trip via `Register.install`.

Deferred to later stages (raise `UnsupportedOperationException` on
JS for now):

- HD derivation (`deriveMaster` / `deriveChild`) — Stage 4 (ERC-4337)
  or Stage 3 (strategy-eoa) will need this; deferred so Stage 2 stays
  small.
- PBKDF2 / Argon2id / AES-GCM — Stage 5 (`wallet-vault-encrypted`,
  which will wire SubtleCrypto + a noble Argon2 wrapper).
- Sr25519 / BLS12-381 — `supports(...)` returns `false`; not on the
  Stage 2 critical path.

**npm dependency strategy**: no sbt-scalajs-bundler. The
`crypto-noble-js/package.json` declares the two noble deps; running
`npm install` from `crypto-noble-js/` before `sbt cryptoNobleJs/test`
populates `crypto-noble-js/node_modules/`, and the Scala.js
Node-launched test runner walks up from `target/.../*-fastopt/` to
find them. CI just adds `npm install --prefix crypto-noble-js` before
the sbt test step.

### Stage 3 — Strategy + connector cross-compile

Status: **landed 2026-05-20**.

- `wallet-strategy-eoa` cross-compiled (`CrossType.Full`).  Pure SPI
  usage; sources moved to `wallet-strategy-eoa/shared/src/main/scala/`.
  Tests rewritten as `AsyncFunSuite` (Scala.js cannot block on
  `Await.result`); 5 specs run on both platforms.  `RawPrivateKeyVault`
  uses `CryptoBackend.get()`, which on JS now resolves to the
  Noble.js backend (Stage 2).
- `wallet-connector-eip1193` cross-compiled + `js/` source dir.
  Shared `shared/` holds the protocol translator + EIP-6963 value
  types; JVM-only test (real `EvmChainAdapter` + BouncyCastle signer)
  lives under `jvm/src/test/`.  `js/src/main/scala/.../WindowEthereumProvider.scala`
  wraps the cross-compiled `Eip1193Provider` in a JS façade that:
  - Exposes `request({method, params})` returning `js.Promise[js.Any]`
    matching the dApp-facing EIP-1193 contract.
  - Emits `eip6963:announceProvider` events on the global window and
    listens for `eip6963:requestProvider` to re-announce.
  - Last-writer-wins binds `window.ethereum` to the provider for
    legacy dApps that don't speak EIP-6963.
  - Top-level `@JSExportTopLevel("registerScalaScriptEip1193")` lets
    host JS install a wallet from the bootstrap script.
  - sbt dep: `org.scala-js %%% "scalajs-dom" % "2.8.0"` (added via
    `jsSettings`).  No npm `package.json` — DOM facades are part of
    scalajs-dom and require no JS-side install.
- `wallet-connector-wallet-std` cross-compiled + `js/` source dir.
  Approach A (extract the small subset) chosen: the connector's
  `shared/SolanaWireProtocol.scala` inlines `SolanaMessage`,
  `SolanaInstruction`, `Base58`, and `CompactU16` so the shared
  decoder / encoder code links on both platforms.  The JVM-only
  bridge `wallet-connector-wallet-std/jvm/.../WalletStandardConnectorJvm.scala`
  translates the shared `SolanaMessage` into the
  `scalascript.blockchain.solana.SolanaTx` / `SolanaSignedTx` types the
  existing `SolanaChainAdapter` consumes (preserves the
  `tx.asInstanceOf[adapter.Tx]` cast at runtime).  The
  user-facing `WalletStandardConnector` class is a thin concrete
  subclass of `WalletStandardConnectorBase` on each platform —
  JVM-side bridges to `blockchain-solana`; JS-side stubs the
  bridge methods with `UnsupportedOperationException` until a
  Scala.js Solana `ChainAdapter` ships (out of scope for Stage 3 —
  `solana:signMessage` still works on JS because it doesn't need the
  bridge).  `js/src/main/scala/.../WalletStandardRegister.scala`
  builds the `@wallet-standard/core` `Wallet` JS object and
  registers via both routes:
  - **Pull-style** (current spec) — dispatches a
    `wallet-standard:register-wallet` `CustomEvent` carrying a
    callback that the dApp invokes with the wallet handle.
  - **Push-style** (legacy compatibility) — invokes
    `window.standard.wallets.registerWallet(wallet)` if available.
  - Top-level `@JSExportTopLevel("registerScalaScriptWallet")` for
    host JS bootstrap.

### 5.1 Stage 3 testing notes

- JVM test counts preserved bit-for-bit: 5 (eoa) + 13 (eip1193) +
  9 (wallet-std) = 27, same as pre-Stage 3.
- JS tests added: 5 (eoa, mirrored from JVM) + 5 (eip1193 facade) +
  4 (wallet-std register) = 14.
- DOM globals (`window`, `CustomEvent`) are stubbed via a
  `@JSGlobal("globalThis")` typed binding so the test can mutate them
  without tripping the "loading the global scope as a value" rule.
  Listeners are invoked synchronously on dispatch.
- Node v18+ on `PATH` is the only host dep; no extra npm packages
  needed for these three modules.

### 5.2 Stage 3 deferred / follow-ups

- A real Scala.js Solana `ChainAdapter` (would unstub
  `WalletStandardConnector.buildSolanaTx` on JS) — separate slice;
  not on the wallet-spi-scalajs critical path.
- EIP-6963 event-emission end-to-end coverage in a real browser
  remains a manual smoke test — the Stage 3 Node tests cover the
  Scala-side event dispatch + listener flow but not full
  cross-frame `dispatchEvent` semantics.

### Stage 4 — `wallet-strategy-erc4337` cross-compile

Status: **landed 2026-05-20**.

Stage 4 cross-compiles two modules and adds the browser WebAuthn
facade that makes the `PasskeySigner` actually usable from a Scala.js
PWA wallet:

- **`blockchain-evm-abi`** cross-compiled (`CrossType.Full`).  The
  ABI codec had zero `java.*` deps outside the Scala.js-shimmed
  surface (`java.util.Arrays.copyOfRange`, `java.io.ByteArrayOutputStream`,
  `java.lang.StringBuilder`), so the move is purely a layout change.
  `AbiCodecTest` is split into a `AbiCodecTestBase` (in `shared/`)
  with platform-specific concrete classes — the JS one mixes in
  `BeforeAndAfterAll` and explicitly registers `crypto-noble-js`
  (no `ServiceLoader` on JS), the JVM one is a plain subclass that
  relies on the auto-registered `crypto-bouncycastle`.  Same 19
  tests run on both platforms.
- **`wallet-strategy-erc4337`** cross-compiled (`CrossType.Full`):
  - `shared/`: `UserOperation`, `UserOpHash{,V07}`, `EntryPoint`,
    `SmartAccountFactory` (+ `SimpleAccountFactory`),
    `PasskeyAssertion`, `PasskeySigner`,
    `SimplePasskeyAccountFactory`, plus a small inlined `Hex.scala`
    (~50 LoC) so shared sources don't reach into JVM-only
    `blockchain-evm`.  Tests: `UserOpHash{,V07}Test`,
    `WebAuthnAssertionTest`, `PasskeyFactoryTest` — all run as
    `*TestBase` abstract classes with `*Test` concrete subclasses
    per platform (same noble/bouncycastle bootstrap as
    `blockchain-evm-abi`).
  - `jvm/`: `BundlerClient` (uses `Hex` from `blockchain-evm`),
    `SmartAccountAdapter` (uses `EvmChainAdapter` from
    `blockchain-evm`), and `SmartAccount` (the `wrap(...)` helper).
    These stay JVM-only because they depend on `blockchain-evm`,
    which itself stays JVM-only in this slice (it carries
    `java.net.http.HttpClient` and the EVM RLP / EIP-712 codec).
    Tests: `BundlerClientV07Test`, `SmartAccountAdapterTest`,
    `PasskeySignerTest`.
  - `js/`: the WebAuthn facade — see below.

`java.*` audit in this slice:

- `java.math.BigInteger` is used heavily in `PasskeySigner` (for the
  P-256 group order arithmetic during low-s normalisation).  Scala.js
  shims `java.math.BigInteger` faithfully, so we kept it as-is rather
  than reaching for `BigInt` (the BigInteger API is closer to the
  EC math the file does).
- `java.util.Base64` (`PasskeyAssertion`, tests) — Scala.js shims
  it; kept as-is.
- `java.util.Arrays.copyOfRange`, `java.lang.StringBuilder`,
  `java.io.ByteArrayOutputStream` — Scala.js stdlib, kept as-is.
- One JVM-only `salt.bigInteger.toByteArray` in
  `SimpleAccountFactory.saltAsBytes` replaced with `salt.toByteArray`
  (`BigInt.toByteArray` directly).

#### Stage 4 — WebAuthn JS facade

`wallet-strategy-erc4337/js/` adds:

- `WebAuthnFacade.scala` — Scala.js facade over
  `navigator.credentials.get(...)`.  Builds the
  `PublicKeyCredentialRequestOptions` dict (challenge as
  `Uint8Array`, rpId, optional `allowCredentials`,
  `userVerification:"required"`), converts the resulting JS
  `Promise[PublicKeyCredential]` to a Scala `Future`, and unwraps
  `authenticatorData` / `clientDataJSON` / `signature` ArrayBuffers
  into `Array[Byte]`.  Returns a `WebAuthnAssertion` ready for the
  cross-compiled `PasskeySigner`.
- `PasskeySignerJs.scala` — convenience constructor
  `fromBrowserPasskey(publicKey, rpId, allowCredentials)` that wires
  `assertChallenge` to `WebAuthnFacade.assertChallenge`.
- `WebAuthnFacadeTest` (6 tests) — stubs `navigator.credentials.get`
  on `globalThis` (via `Object.defineProperty` — Node 20+ marks
  `navigator` as a getter, so plain assignment throws), and asserts:
  challenge byte-identity, rpId / userVerification options,
  `allowCredentials` encoding, the three ArrayBuffer → byte-array
  round-trips, and signed-byte fidelity of the `toUint8Array` helper.

The `scalajs-dom 2.8.0` dep is declared in `jsSettings(...)`.
`scalaJSLinkerConfig` is set to `CommonJSModule` for the JS side
because the test config depends on `crypto-noble-js`, which itself
links as a CommonJS module (so `@noble/*` `require()` calls
resolve).

#### Stage 4 — deferred / follow-ups

- **`SmartAccountAdapter` / `BundlerClient` / `SmartAccount` remain
  JVM-only.**  All three depend on `EvmChainAdapter` / the JVM HTTP
  RPC client in `blockchain-evm`.  Cross-compiling those onto
  Scala.js needs (a) a Fetch-based RPC `ChainContext` impl,
  (b) cross-compiling the EIP-1559 transaction codec + RLP +
  keccak from `blockchain-evm` itself.  That's the natural Stage 4½
  / Stage 5-companion follow-up; with the SPI substrate already
  cross-compiled in Stages 1-3, it's a self-contained slice.
- **No real browser-integration test for the WebAuthn facade.**  The
  Node-side stub asserts the options-dict shape + the ArrayBuffer
  round-trip; an end-to-end test against a real authenticator
  (virtual authenticator via Playwright, or a yubikey) is out of
  scope for this slice.
- **HD derivation on Scala.js still throws
  `UnsupportedOperationException`** (Stage 2's `NobleCryptoBackend`
  deferred it).  The cross-compiled `PasskeySigner` doesn't need HD
  — passkeys don't derive — but a future `wallet-vault-encrypted-js`
  (Stage 5) will, and Stage 5 will be the trigger to land HD in
  `NobleCryptoBackend`.

### Stage 5 — `wallet-vault-encrypted` cross-compile

Status: **landed 2026-05-20**; JS persistence landed 2026-05-27.

Stage 5 does two things in one slice: it lights up the deferred KDF
+ AEAD primitives in the Stage-2 noble backend, and cross-compiles the
encrypted vault on top of them.  The result is a shared, platform-
neutral `EncryptedLocalVault` that runs identically on JVM + JS, plus
a JVM-only `EncryptedLocalVaultFs` wrapper that keeps the original
`Path`-based API used by every downstream JVM caller.

#### Stage 5a — light up `NobleCryptoBackend`

- **PBKDF2** — `@noble/hashes/pbkdf2.pbkdf2(hashFn, password, salt,
  { c, dkLen })`.  Synchronous; SHA-256 and SHA-512 hash functions
  supported.  Bit-identical to BouncyCastle's
  `PBKDF2WithHmacSHA{256,512}` on the same input.
- **Argon2id** — `@noble/hashes/argon2.argon2id(password, salt,
  { t, m, p, dkLen })`.  Synchronous; RFC 9106 version 0x13.
  Bit-identical to BouncyCastle's `Argon2BytesGenerator`.  Pinned via
  `@noble/hashes ^1.8.0`.
- **AES-GCM** — `@noble/ciphers/aes.gcm(key, iv, aad?).encrypt /
  .decrypt`.  Synchronous.  Pinned via `@noble/ciphers ^1.2.1`.
  **Chosen over WebCrypto SubtleCrypto on purpose**: the `CryptoBackend`
  SPI exposes synchronous `aesGcmEncrypt` / `aesGcmDecrypt`; SubtleCrypto's
  `crypto.subtle.encrypt` returns a Promise, which can't be awaited
  inside the sync SPI on either browser or Node.  Routing through
  noble keeps the API contract while still matching JVM ciphertext
  bit-for-bit (verified by the Stage 5 cross-platform fixtures —
  9 shared hex assertions across
  `crypto-bouncycastle/.../CrossPlatformFixturesTest` and
  `crypto-noble-js/.../NobleCryptoBackendTest`).

#### Stage 5b — cross-compile the vault

- Sources moved from `wallet-vault-encrypted/src/main/scala/` to
  `wallet-vault-encrypted/shared/src/main/scala/` for the platform-
  neutral pieces:
  - `Bip39.scala` — wordlist + entropy↔mnemonic + checksum +
    `Mnemonic.toSeed` (PBKDF2-HMAC-SHA512 via `CryptoBackend`).
  - `Bip39Wordlist.scala` — **embedded** 2048-word English BIP-39
    wordlist as a Scala const.  The original
    `src/main/resources/bip39-english.txt` is removed; the embedded
    list is asserted equal in the shared test
    (`wordlist embedded const has 2048 entries, first 'abandon', last 'zoo'`).
    This kills the only classpath-resource lookup in the module —
    Scala.js has no classpath, so the resource lookup would have
    crashed.
  - `VaultFile.scala` — pure data + JSON codec (`toJson` / `fromJson`).
  - `EncryptedLocalVault.scala` — the vault core, parameterised over
    a pluggable `save: VaultFile => Unit` sink.  All crypto routes
    through `CryptoBackend.get()`, so the same code path encrypts /
    decrypts the seed on JVM (BouncyCastle) and JS (noble) with
    byte-identical output.
- JVM-only pieces moved to `wallet-vault-encrypted/jvm/src/main/scala/`:
  - `VaultFileIo.{read,write}` — `java.nio.file.Path`-based file I/O.
  - `EncryptedLocalVaultFs.{create,load,generate}` — thin wrapper that
    handles `Files.createDirectories(...)` and passes a `Path`-saving
    `save` callback through to the shared `EncryptedLocalVault.create`.
    Preserves the pre-Stage-5 JVM-side API surface; downstream callers
    that `dependsOn(walletVaultEncrypted)` keep compiling unchanged.
- Tests:
  - `shared/src/test/scala/.../Bip39TestBase.scala` — 14 tests
    (wordlist sanity + entropy↔mnemonic + checksum + PBKDF2-HMAC-SHA512
    + Trezor seed vector).  Per-platform subclass
    (`jvm/src/test/.../Bip39Test.scala` / `js/src/test/.../Bip39Test.scala`)
    registers BouncyCastle (auto via `ServiceLoader`) / noble
    (explicit `CryptoBackend.register(...)` in `beforeAll`).
  - `shared/src/test/scala/.../VaultCrossPlatformTestBase.scala` —
    synchronous `AnyFunSuite` with 2 cross-platform vectors (Trezor
    BIP-39 seed + fixed Argon2id+AES-GCM ciphertext, byte-identical
    across JVM + JS).  Async sibling `VaultCrossPlatformAsyncTestBase`
    (1 test) exercises the full create → JSON round-trip → reopen →
    unlock flow.
  - `jvm/src/test/scala/.../EncryptedLocalVaultTest.scala` — 13
    file-I/O-driven tests against `EncryptedLocalVaultFs`.  Same
    coverage as pre-Stage 5; only difference is the entry point name
    (`EncryptedLocalVault.create(path, ...)` → `EncryptedLocalVaultFs.create(path, ...)`).
- Build wiring: `walletVaultEncryptedCross = crossProject(...)` with
  `.jvmConfigure(_.withId("walletVaultEncrypted"))` keeping the
  pre-Stage-5 sbt project id so downstream `dependsOn(walletVaultEncrypted)`
  keeps resolving to the JVM artefact; `walletVaultEncryptedJs` is the
  Scala.js side.  JS test scope pulls in `cryptoNobleJs % Test` for
  the noble backend; module kind is CommonJS so noble's `require()`-
  style exports link.

#### Stage 5 — JVM test count parity

- Pre-Stage-5 JVM tests in `walletVaultEncrypted`: **26**
  (`Bip39Test` 13 + `EncryptedLocalVaultTest` 13).
- Post-Stage-5: **30** — the 13 + 13 are preserved bit-for-bit
  (renamed-API-only changes in `EncryptedLocalVaultTest`); the four
  new tests are: 1 wordlist sanity (in the shared `Bip39TestBase`),
  2 cross-platform vectors (`VaultCrossPlatformTest`), 1 async vault
  round-trip (`VaultCrossPlatformAsyncTest`).
- JS-side: **17** tests in `walletVaultEncryptedJs` (14 mirrored
  `Bip39Test`, 2 mirrored vector, 1 mirrored async round-trip).
- `crypto-noble-js`: pre-Stage-5 **16** tests; post-Stage-5 **25**
  (added 9 new KDF + AEAD parity assertions, mirrored byte-for-byte
  in `cryptoBouncycastle`'s `CrossPlatformFixturesTest`).

#### Stage 5d — JS persistence layer ✓ Landed (2026-05-27)

- `js/src/main/scala/.../EncryptedLocalVaultJs.scala` adds browser-side
  convenience entry points mirroring the JVM filesystem helper:
  `EncryptedLocalVaultJs.create/load/generate/delete/save`.
- `VaultFileStore` is the JS persistence contract.  Implementations:
  `IndexedDbVaultFileStore` (default in browsers), `LocalStorageVaultFileStore`
  (fallback for small/simple environments), and `MemoryVaultFileStore`
  (Node/tests/final fallback).
- IndexedDB stores `{ id, json }` records where `json` is the exact shared
  `VaultFile.toJson` representation.  No crypto or file-format fork: all
  encryption/decryption remains in the shared `EncryptedLocalVault` core.
- `saveAccounts()` remains synchronous in the shared vault API, so JS wrappers
  use a fire-and-forget `save` callback and also expose
  `EncryptedLocalVaultJs.save(vault, store)` when callers need an awaitable
  explicit persistence point.
- Tests: `EncryptedLocalVaultJsTest` covers create → load → unlock,
  account metadata persistence through the JS save callback, and delete.

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6 cross-compiles `wallet-connect` and ships the matching
browser-side WebSocket adapter + noble-backed ChaCha20-Poly1305 /
X25519 crypto.  Closes the wallet-spi Scala.js cross-compile sprint.

**Stage 6a — SPI additions (additive only — no existing-method
breakage)**:

- `CryptoBackend.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)`
  / `chacha20Poly1305Decrypt(...)` — `ciphertext || 16B tag` layout,
  byte-identical to JCE's `ChaCha20-Poly1305` provider and
  `@noble/ciphers/chacha.chacha20poly1305`.  Decrypt rethrows tag
  mismatches as the new shared `CryptoIntegrityException` so callers
  can pattern-match without depending on `javax.crypto.AEADBadTagException`.
- `CryptoBackend.x25519GenerateKeypair()` /
  `x25519PublicKeyFromPrivate(priv32)` /
  `x25519DeriveSharedSecret(selfPriv, peerPub)` — 32-byte raw priv /
  pub bytes both sides, raw ECDH output that feeds straight into the
  existing `hkdf` primitive.
- Cross-platform parity fixtures land in
  `crypto-bouncycastle/.../CrossPlatformFixturesTest` +
  `crypto-noble-js/.../NobleCryptoBackendTest` (8 new vectors each,
  same hex bytes on both sides).
- Default trait methods `throw UnsupportedOperationException` so
  third-party backends that don't implement the new primitives still
  compile — both ours implement them.

**Stage 6b — wallet-connect refactored to the SPI**:

- `RelayJwt` switches `Ed25519Signer` for
  `CryptoBackend.get().sign(Curve.Ed25519, ...)` / `verify(...)`.
- `WcEnvelope` switches JCE `Cipher.getInstance("ChaCha20-Poly1305")`
  for `CryptoBackend.get().chacha20Poly1305Encrypt/Decrypt`.  Throws
  `CryptoIntegrityException` (now re-exported as the WC-local type
  alias `WcEnvelope.AeadBadTagException`).
- `WcKeyAgreement` switches BC's `X25519Agreement` / `HKDFBytesGenerator`
  for the new SPI methods + the existing `hkdf` + `hash(Sha256, ...)`.
- After the refactor the three files have **zero** `java.*` /
  `javax.*` / `org.bouncycastle.*` direct references — they move to
  `shared/src/main/scala/`.

**Stage 6c — cross-project layout** (`CrossType.Full`):

- `shared/src/main/scala/scalascript/wallet/walletconnect/`:
  `WcTypes`, `WcRelayTransport` (trait), `WcSessionStore` (now backed
  by a synchronized `mutable.HashMap` — TrieMap isn't on Scala.js),
  `RelayJsonRpc`, `WsChannel` (trait), `RelayJwt`, `WcEnvelope`,
  `WcKeyAgreement`, `WalletConnectConnector`, and the new
  `RelayTransportBase` that owns the demux + JSON-RPC core.
- `jvm/src/main/scala/.../`: `JdkWsChannel` (`java.net.http.WebSocket`),
  `JvmRelayTransport` — now a thin `extends RelayTransportBase(...)`
  shim preserved as the legacy entry point.
- `js/src/main/scala/.../`: `BrowserWsChannel` (wraps the browser's
  native `WebSocket` global via an injectable `wsConstructor`
  parameter — tests stub the constructor, no `globalThis.WebSocket`
  surgery), `JsRelayTransport` — the JS twin of `JvmRelayTransport`.

**Option A composition**: `RelayTransportBase` in `shared/` owns the
full demux + JSON-RPC pipeline.  `JvmRelayTransport` and
`JsRelayTransport` are 5-line subclasses that only differ in which
`WsChannel` they wire.  The shared
[`RelayTransportTestBase`](../wallet-connect/shared/src/test/scala/scalascript/wallet/walletconnect/RelayTransportTestBase.scala)
spec runs against both — green on JVM ≡ green on JS for every
protocol assertion.

**Stage 6d — tests**:

- JVM `walletConnect/test` — 49 tests across 7 suites; same count as
  pre-Stage 6.  Previously-JVM-only suites
  (`JvmRelayTransportTest`, `WcEnvelopeTest`, `WcKeyAgreementTest`,
  `RelayJwtTest`, `WalletConnectConnectorTest`, `WcSessionStoreTest`,
  `RelayJsonRpcTest`) become thin JVM concrete sub-classes of
  `*TestBase` specs in `shared/src/test/`.
- JS `walletConnectJs/test` — 54 tests across 8 suites (same 49 shared
  specs + 5 new `BrowserWsChannelTest` tests against a mock
  `BrowserWebSocket`).
- Cross-platform crypto parity: 8 new ChaCha20-Poly1305 + X25519
  fixtures in `CrossPlatformFixturesTest` (BouncyCastle) and
  `NobleCryptoBackendTest` (noble), hex strings stay byte-identical
  between the two files.

**Stage 6e — build wiring**: `walletConnectCross =
crossProject(JVMPlatform, JSPlatform).crossType(Full)`; legacy
`walletConnect` alias preserved as `walletConnectCross.jvm` so every
existing JVM downstream `dependsOn(walletConnect)` keeps compiling.
JS side has `cryptoNobleJs % Test` to resolve `CryptoBackend.get()`;
`scalaJSLinkerConfig ~= _.withModuleKind(CommonJSModule)` matches
crypto-noble-js.  `walletConnectJs` added to the root aggregator.
`wallet-connect/package.json` mirrors `crypto-noble-js/package.json`
so the Node test runner walks up to find `@noble/ciphers` +
`@noble/curves` + `@noble/hashes`.

**Stage 6 — deferred / follow-ups**:

- Real browser WebSocket integration — the JS tests mock
  `BrowserWebSocket` (Node test runner has no native `WebSocket`).
  Live `wss://relay.walletconnect.com` integration lands in the
  future PWA-wallet sprint that surfaces WC v2 in the actual browser.

### Stage 7 — `wallet-vault-ledger-js` WebHID vault ✓ Landed (2026-05-27)

The browser Ledger slice adds a Scala.js-only
`payments/wallet/vault-ledger-js` module.  It compiles the shared
Ledger APDU and Ethereum app sources inline, then layers browser
transport and hardware-vault entry points on top:

- `scalascript.wallet.ledger.HidTransport` wraps `navigator.hid` for
  real browser sessions and exposes `connect` / `disconnect` through
  `LedgerVault`.
- `scalascript.wallet.vault.ledger.js.WebHidLedgerTransport` is the
  small testable transport adapter over a `WebHidDevice` facade.
- Ledger HID 64-byte packet framing is covered by round-trip and
  ordering tests.
- Ethereum signing reuses `wallet-vault-ledger-ethereum`; the browser
  vault probes the active Ledger app and raises `AppSwitchRequired`
  like the JVM path.
- Cardano support lands as a CIP-8 helper: extended public key lookup,
  COSE_Sign1, Sig_structure, and COSE_Key framing over WebHID.

Verification: `sbt 'walletVaultLedgerJs / Test / test'` runs 13
Node-backed Scala.js tests with a mocked HID device; no real Ledger is
needed in CI.

### CryptoBackend SPI surface — what every future backend must provide

With Stage 6 closed, the full set of [[CryptoBackend]] primitives any
new backend (e.g. WebCrypto-only, hardware-backed TPM) must
implement to be a drop-in replacement is:

1. **Signing** — `sign` / `verify` / `derivePublic` / `recoverPublic`
   for secp256k1 + ed25519 + p256 (curve `supports(...)` gates which
   are required).
2. **Hash + MAC** — `hash` for sha256 / sha512 / keccak256 /
   ripemd160; `hmac` for sha256 / sha512.
3. **HD derivation** — `deriveMaster` / `deriveChild` for secp256k1
   (BIP-32) + ed25519 (SLIP-0010).  *Optional* on Scala.js for now —
   `crypto-noble-js` still throws `UnsupportedOperationException`;
   Stage TBD lands BIP-32 on noble.
4. **KDF** — `pbkdf2` (sha256 + sha512), `argon2id` (RFC 9106 v0x13),
   `hkdf` (sha256 + sha512).
5. **AEAD** — `aesGcmEncrypt` / `aesGcmDecrypt` (12-byte IV, 16-byte
   tag) **and** `chacha20Poly1305Encrypt` /
   `chacha20Poly1305Decrypt` (12-byte nonce, 16-byte tag, throws
   `CryptoIntegrityException` on tag mismatch).
6. **X25519** — `x25519GenerateKeypair` /
   `x25519PublicKeyFromPrivate` / `x25519DeriveSharedSecret`
   (32-byte raw bytes both sides).
7. **RNG** — `randomBytes(len)` from a cryptographically-secure source.

`CryptoBackend.{aesGcm,chacha20Poly1305,x25519}*` all have default
implementations on the trait that throw
`UnsupportedOperationException` so partial backends continue to link;
both `BouncyCastleBackend` and `NobleCryptoBackend` implement every
primitive listed above.

## 6. Testing strategy

### 6.1 JVM tests

Existing JVM tests across the wallet-spi track (`x402-client`,
`wallet-strategy-eoa`, `mcp-wallet`, `wallet-vault-encrypted`,
`wallet-vault-ledger-*`, etc.) continue to run unchanged. They depend
on the JVM platform of the cross-projects (via the `walletSpi` /
`cryptoSpi` / `blockchainSpi` aliases) so no test code changes are
required.

`sbt walletSpi/test` (alias to `walletSpiJvm/test`) is green
post-slice with the same count as pre-slice — wallet-spi has no JVM
tests of its own today; the smoke test added in Stage 1 lives in
`shared/src/test/` and runs on both platforms.

### 6.2 Scala.js tests

Each cross-compiled module gets a `shared/src/test/scala/` directory
with scalatest specs that exercise the cross-compile contract:
construct SPI values, call `equals` / `hashCode` / pattern matches,
verify no JVM-only code paths are reachable from the JS side. The
JS test runner is the Scala.js default (Node.js via `node`).

Phase 1 lands one smoke test (`CrossCompileSmokeTest`) covering
`ChainId`, `Curve`, `HashAlgo`, `PublicKey`, `AccountDescriptor`,
`VaultKind`, `UnlockCredential` round-trips.

Later phases add real cross-cutting tests once impl modules
(crypto-noble-js, etc.) make real signing / hashing reproducible
across platforms — that's where conformance lives.

### 6.3 Conformance / cross-platform

Long-term we want a small conformance set ("sign this fixture with
Secp256k1, the JVM impl and the JS impl must produce the same
RFC-6979 signature") shared across crypto-bouncycastle and
crypto-noble-js. That's a Stage 2 deliverable; Stage 1 does not
require it because Stage 1 doesn't introduce a JS impl yet.

## 7. Open questions

1. **`Future[T]` ↔ `js.Promise[T]` at the connector boundary.**
   EIP-1193 / WalletConnect / Wallet Standard all expose Promise-based
   APIs in the browser. Mapping a Scala `Future[DappResponse]` to a
   `js.Promise[js.Dynamic]` (and vice versa) is mechanical, but the
   wrapper needs to live somewhere: `wallet-spi/js/` (per-SPI) or a
   shared `interop/scalajs/` helper module? Resolve before Stage 3
   (EIP-1193 / Wallet Standard).

2. **HTTP client choice on Scala.js.** WalletConnect / x402 client /
   MCP wallet all need an HTTP client on Scala.js. Options:
   `scalajs-dom` `Fetch` API (no extra dep) vs. `sttp-client4`'s
   Scala.js variant (already a JVM dep — same API both sides) vs.
   `scalajs-fetch`. Resolve before Stage 5 (vault-encrypted IndexedDB
   IO doesn't need it) / Stage 6 (WC v2 absolutely needs it).

3. **Cross-deps in `%` Test scope when one side is cross-compiled
   and the other isn't.** Example: `wallet-strategy-eoa` (Stage 3
   cross-compiles) depends on `cryptoBouncycastle % Test` (JVM
   only — never crosses). The `Test` config has to use the JVM
   accessor, not the cross alias. Pattern is:

   ```scala
   .jvmSettings(libraryDependencies += /* JVM-only test deps */ ...)
   .jvmConfigure(_.dependsOn(cryptoBouncycastle % Test))
   ```

   Document this with a worked example once Stage 3 lands.

4. **`% Test` cross-deps with a JS-only impl (e.g. tests that need
   crypto-noble-js on JS but crypto-bouncycastle on JVM).** Likely
   handled via `.jvmConfigure` / `.jsConfigure` per the
   sbt-crossproject docs; verify with a worked Stage 2 / Stage 3
   example.

5. **WebCrypto Argon2id fallback.** ~~WebCrypto exposes PBKDF2 but
   not Argon2id. For Stage 5 we either ship a JS Argon2id impl
   (~30 KB via `@noble/hashes` Argon2 wrapper, or `argon2-browser`)
   or fall back to PBKDF2 on JS only.~~  **Resolved (Stage 5,
   2026-05-20)** — picked `@noble/hashes/argon2.argon2id`
   (synchronous, RFC 9106 v0x13, bit-identical to BouncyCastle's
   `Argon2BytesGenerator`).  Bundle cost is the
   `@noble/hashes/argon2` module only; the rest of `@noble/hashes`
   is already pulled in by Stage 2.  AES-GCM also went through
   `@noble/ciphers/aes.gcm` instead of SubtleCrypto, because the
   `CryptoBackend` SPI is synchronous and SubtleCrypto returns a
   Promise — see Stage 5a.

## 8. References

- [`docs/wallet-spi.md`](wallet-spi.md) — full wallet SPI design,
  source of the cross-compile intent in §8.
- [`docs/blockchain-spi.md`](blockchain-spi.md) — the chain-side SPI
  this layer sits above; same cross-compile story applies.
- [Scala.js cross-compile guide](https://www.scala-js.org/doc/project/cross-build.html)
- [sbt-crossproject](https://github.com/portable-scala/sbt-crossproject)
- [sbt-scalajs](https://github.com/scala-js/scala-js)
- [@noble/curves](https://github.com/paulmillr/noble-curves) —
  reference Scala.js-target crypto library (Stage 2).
- [SubtleCrypto](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)
  — browser-native crypto API (Stage 5 / 6).
