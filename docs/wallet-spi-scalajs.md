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

### Stage 2 — Scala.js CryptoBackend + Blockchain registry

- `object CryptoBackend` on JS gets `register(...)` + `all` + `get(id)`
  (no ServiceLoader; explicit init).
- Same for `object Blockchain`.
- `crypto-noble-js` Scala.js module added, registers itself on init.
- Resolves the long-standing "Scala.js registry pattern" open
  question (docs/wallet-spi.md §11.1).

### Stage 3 — Strategy + connector cross-compile

- `wallet-strategy-eoa` → cross-compile. Its `RawPrivateKeyVault`
  uses `CryptoBackend.get()`, which on JS now resolves to the
  Noble.js backend.
- `wallet-connector-eip1193` → cross-compile + `js/` source dir for
  `window.ethereum` injection (Scala.js DOM facade).
- `wallet-connector-wallet-std` → cross-compile + `js/` for the
  `@wallet-standard/core` facade.

### Stage 4 — `wallet-strategy-erc4337` cross-compile

- Audit / replace any `java.math.BigInteger` use with `BigInt`.
- Cross-compile; passkey owner (curve = p256) wired through Stage 5.

### Stage 5 — `wallet-vault-encrypted` cross-compile

- Move shared encrypted-payload types into `shared/`.
- JS side: SubtleCrypto adapter (`crypto.subtle.encrypt` /
  `decrypt` with AES-GCM, `crypto.subtle.deriveKey` with PBKDF2,
  noble argon2id for the password-stretching step).
- Existing `wallet-vault-encrypted-jvm` continues to work.

### Stage 6 — `wallet-connect` cross-compile

- Shared WC v2 protocol types in `shared/`.
- JS side: native `WebSocket` adapter + WebCrypto AEAD.
- JVM side: existing `java.net.http.WebSocket` + BouncyCastle
  unchanged.

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

5. **WebCrypto Argon2id fallback.** WebCrypto exposes PBKDF2 but
   not Argon2id. For Stage 5 we either ship a JS Argon2id impl
   (~30 KB via `@noble/hashes` Argon2 wrapper, or `argon2-browser`)
   or fall back to PBKDF2 on JS only. Decide before Stage 5 starts.

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
