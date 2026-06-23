# What ScalaScript can do — capabilities & the provider-independence pattern

A map of our own capabilities, written to make the project's design philosophy concrete and to point at where
it can go next. Everything below is grounded in code that exists today; file pointers are given so claims are
checkable.

---

## 1. The one big idea: *portable reference + pluggable native provider*

The organizing principle of the whole codebase is a single repeating shape:

> **A portable reference implementation** (pure ScalaScript / `BigInteger` / JDK, compiles to every platform)
> **plus a registry SPI** where a platform may `register(...)` a **native implementation** that is then used
> **transparently** — selected at runtime (or compile-time). The reference is the always-available correctness
> fallback; the native impl is the fast path. Where no native provider exists, the reference still runs
> everywhere, so the pattern **degrades gracefully**.

This is realized at every layer — compiler, runtime services, concurrency, crypto, blockchain, payments, UI —
and it's the same shape you can apply to anything new. FROST-Ed25519 is the textbook example (see §6).

---

## 2. Compile targets (one source → many platforms)

One normalized IR, many `Backend`s (`runtime/backend/spi/.../Backend.scala`), each contributing its own
`intrinsics`; discovered via `META-INF/services` (in-process) or `.sscpkg` (subprocess).

| Target | What it is |
|---|---|
| **Interpreter** | Direct-style evaluator + numeric JIT; the *reference semantics* every codegen target must match; powers REPL / `serve` / `ssc run`. |
| **JVM** | Emits Scala for scala-cli/JVM; CPS transform for effects. |
| **JS / Node / Scala.js SPA** | Emits JS/ESM (+ a JS runtime preamble: signals, graphql, indexeddb, mcp). |
| **Rust** | Emits Rust; one-shot algebraic effects via tagless-final traits. |
| **WASM** | Scala.js → `.wasm`; lowers `@wasm(...)` externs. |
| **Spark / Kafka-Streams / Flink (+Beam)** | The same stream program emitted to multiple distributed engines via a shared distributed-streams abstraction. |
| **Source embedders** | `scala`/`javascript`/`rust`/`xml`/`sql`/`html`/`css`/`transaction` fenced blocks. |

→ *one reference, many native execution providers* is already true at the compiler boundary.

---

## 3. Language / runtime features

- **Algebraic effects** — `effect Foo {…}` + `handle(body){ case Foo.op(args, resume) => }`; interpreter runs a
  free-monad trampoline (multi-shot `resume`), each backend lowers it natively (one-shot on Rust).
- **Block-form effect runners** — `runLogger/runRandom/runClock/runEnv/runState/runRetry/runCache/runHttp { … }`
  as pluggable `BlockForm` handlers over a typed `SpiValue`, not hardcoded in the interpreter.
- **Actors / cluster** — Erlang-style supervision, bounded mailboxes, cluster registry, phi-accrual failure
  detection, Bully + Raft leader election.
- **Backpressured streams** — `stream { emit(x) }` + Source/Sink/Flow.
- **Declarative UI** — a backend-agnostic node tree lowered per frontend (JS / JVM / native), typed JSON,
  theming, routing.
- **HTTP server** — `route/serve/sse/ws/tls/cors`; pluggable server backend (JDK fallback, opt-in Jetty/Netty).
- **SQL / GraphQL fenced blocks**, **type lambdas / HKT**, **inline + quoted macros**.

---

## 4. The financial / crypto stack (what's already here)

A broad, plugin-architected stack — and almost all of it is *SPI + pure reference/mock + thin SDK-free adapters*.

**Cryptography** — `CryptoBackend` SPI (`payments/crypto/spi/`): `sign/verify/derivePublic/recoverPublic`
(secp256k1 ecrecover), `hash/hmac`, BIP-32/SLIP-0010 HD derivation, `pbkdf2/argon2id/hkdf`, AES-GCM,
ChaCha20-Poly1305, X25519. Curves: **secp256k1, Ed25519, P-256** (Sr25519/BLS12-381 enumerated, not yet
implemented). Two interchangeable, byte-for-byte-compatible backends: **BouncyCastle on JVM** (ServiceLoader)
and **`@noble/*` on JS** (`register`). Plus **FROST-Ed25519** — our own pure threshold-signing module
(`cryptoFrost`, no main dependency; see [frost-ed25519-usage.md](frost-ed25519-usage.md)).

**Wallets** — `Vault`/`RawSigner` SPI; **our own** BIP-39 (embedded wordlist), encrypted local vault
(Argon2id + AES-256-GCM through the crypto SPI), EOA + ERC-4337 account abstraction (own UserOp hashing,
passkey/WebAuthn owner), Ledger (own APDU/app codecs; vendor lib only for the USB byte pipe), Trezor, and
connectors (EIP-1193, WalletConnect v2 with own envelope crypto over X25519+ChaCha20, Wallet Standard). MPC
vaults (Fireblocks/Coinbase/Lit/ZenGo) are **thin clients to remote external providers** behind a
`RemoteSigningClient` SPI.

**Blockchains** — `ChainAdapter` SPI (`payments/blockchain/spi/`), one impl per chain, all signing through the
crypto SPI:
- **EVM** + full Solidity **ABI v2** codec, RLP, EIP-55/712/3009, ERC-20 — **pure Scala, cross-compiles to JS**.
- **Solana** — message build, Base58, CompactU16, PDA, Ed25519 on-curve — **pure Scala, cross-compiles to JS**.
- **Cardano / Bitcoin / Cosmos** — own encoders (CBOR, Bech32, PSBT/SegWit, Amino sign-doc) but currently pull
  BouncyCastle (Blake2b / secp256k1-DER / RIPEMD-160) directly, so not yet backend-agnostic.
- Clients: `clientEvm` (JSON-RPC), `clientBlockfrost` (Cardano). Bitcoin/Cosmos build+sign offline but have no
  broadcast client yet.

**Payments / services** — `PaymentProvider` SPI (Stripe/PayPal/Braintree/Adyen/Checkout/Square + a mock, all
ujson-over-HTTP, **no vendor SDKs**); `BankRailsProvider` (~15 rails: SEPA/ACH/FedNow/Pix/SWIFT/UK-FPS/etc.);
**x402** HTTP-402 micropayments (core + server + client + facilitators + pluggable nonce/queue stores); off-chain
**micropayment channels** (threshold batching, probabilistic lottery tickets, EVM state channels, Cardano
Hydra); FX (ECB / OpenExchangeRates); `Money` + `WebhookReceiver` substrate; W3C Payment Request (Apple/Google
Pay token decryption). Adjacent: tax, compliance/AML.

---

## 5. The provider-independence machinery (how substitution works)

The same SPI shape recurs; to add provider-independence to anything, copy one of these:

| Seam | Mechanism | Reference impl |
|---|---|---|
| `Backend` | `intrinsics` per target; ServiceLoader / `.sscpkg` discovery | the interpreter is the canonical semantics |
| `CryptoBackend` | `register` / `get` registry; ServiceLoader (JVM) / init-block `register` (JS) | BouncyCastle (JVM), `@noble` (JS) |
| **`Ed25519Ops`** (FROST) | `register` / `current` / `reset`; even `randomBytes`/`sha512` on the seam | **pure `BigInteger` + own `Sha512`, zero deps** |
| `HttpServerSpi` | classpath fallback + opt-in providers + `setHttpServerBackend` | JDK `com.sun.net.httpserver` (zero-dep) |
| `Vault` / `RawSigner` | per-vault impls behind one trait | in-memory + encrypted-local |
| `ChainAdapter` / `Blockchain` | CAIP-2 registry, ServiceLoader / `register` | EVM, Solana (pure Scala) |
| `PaymentProvider` / `BankRailsProvider` / `FxProvider` | ServiceLoader-discovered | mock PSP, in-memory stores |
| `@jvm`/`@js`/`@rust`/`@wasm` externs + `.ssclib` glue | one annotation → native host expr per target | the ScalaScript body is the fallback |

**`ssc emit-lib`** turns the idea outward: a ScalaScript feature is emitted as a **native** host package — npm
ESM, sbt jar, Rust crate, Java/Maven — so even non-ScalaScript consumers get the portable implementation with
no ScalaScript runtime at their edge (first feature shipped: optics, all four hosts). And the **package
registry** (`pkg:` + `LocalRegistry` + the new `RemoteRegistry`/`FileRegistry`/`RegistryHttpServer`) is the
distribution channel for those `.sscpkg` libraries.

---

## 6. The FROST template — and where to copy it

FROST is the cleanest instance of the pattern and the model for new "own implementation, provider-independent"
features:
- **Our own reference**, dependency-free, compiles everywhere: `Ed25519Group` (curve math), `Sha512` (hashing),
  `FrostKeygen` + `FrostSign` (the protocol) — all over `BigInteger`/`Long`.
- **Pluggable seam** `Ed25519Ops` — point ops, scalar field, hash, *and randomness* are all substitution points;
  `register(native)` swaps in BouncyCastle/`@noble`/Rust transparently, `reset()` restores the reference.
- **Verified against a reference** (BouncyCastle Ed25519) so the from-scratch math is provably correct.

To make any capability provider-independent the FROST way: (1) write a small pure reference; (2) define a trait
for its primitives + a `register/current/reset` registry with the reference as default; (3) route all callers
through `current`; (4) gate correctness against an existing implementation; (5) optionally add native backends
per platform.

---

## 7. Future / brainstorm — what we could do directly, with minimal dependencies

*Forward-looking; not committed work. The thread: prefer our own dependency-light reference behind a
provider-independent seam, with native backends only as an opt-in fast path.*

### Cryptocurrencies we could support with little/no heavy deps
The offline surface (addresses, tx building, signing payloads) of most chains is just encoding + a signature,
and **we already prove this is doable in pure Scala** for EVM and Solana (both cross-compile to JS today). The
heavy parts are only (a) a few curve/hash primitives and (b) RPC nodes.

- **EVM / Solana — already pure-Scala-ready.** They only need *any* `CryptoBackend`; both even run on JS. The
  remaining gap is a Solana RPC client (EVM has one). Low effort, high leverage.
- **Cardano / Bitcoin / Cosmos — make them backend-agnostic.** Today they import BouncyCastle directly for
  Blake2b-224 / secp256k1-DER / RIPEMD-160 / Ed25519. Route those through the `CryptoBackend` SPI (add Blake2b
  + RIPEMD-160 + DER-encoding to the SPI, with our own reference impls — same as we did for SHA-512 in FROST)
  and they'd cross-compile and shed the hard dependency.
- **Our own missing primitives behind the SPI** (the FROST move, repeated): pure-Scala **Keccak-256**, **Blake2b**,
  **RIPEMD-160**, **secp256k1** scalar/point math, **BIP-32 HD on JS** (currently JVM-only). Each is a bounded,
  vector-gateable reference that removes a vendor dependency and unlocks a platform.
- **More threshold / MPC, our own (like FROST).** FROST-Ed25519 is done; natural siblings: **FROST-secp256k1**
  (threshold Schnorr for Bitcoin Taproot), **threshold ECDSA** (GG/Lindell for legacy Bitcoin/Ethereum),
  **MuSig2** (Bitcoin multisig as a single key), **VRFs** and **BLS** (validator/aggregate signatures). All
  fit the `*Ops` seam pattern with a pure reference + optional native fast path.
- **Newer chains** whose signing is Ed25519/secp256k1 + a tidy encoding (Aptos/Sui/Stellar/XRPL/Polkadot) are
  mostly "another `ChainAdapter`" once the primitive is in the crypto SPI.

### Services / protocols we could implement directly (not only currencies)
The same "own reference behind a seam" approach applies beyond chains:

- **TOTP / HOTP** (2FA codes), **WebAuthn/passkey** server-side verification (we already do client assertions),
  **PASETO / JWT / COSE** token signing+verify — all small, pure, vector-gateable.
- **Noise protocol / X25519 handshakes** — we already have X25519 + ChaCha20 on the SPI (WalletConnect uses
  them); a pure Noise framework is a short hop.
- **DIDs / Verifiable Credentials** (decentralized identity), **age/PGP-style encryption**, **Shamir backup**
  for arbitrary secrets (we already have the scalar-field Shamir in FROST — generalize it).
- **Our own micropayment / settlement schemes** — the channel SPI already hosts threshold/probabilistic/Hydra;
  inventing a new scheme is "another `ChannelProvider`."
- **A small, dependency-free oracle / attestation service**, **content-addressed storage**, **a gossip/CRDT
  layer** (we have cluster + actors) — all reference-first, provider-pluggable.

### Why this is realistic here
Three things make the above cheap: (1) the SPIs already exist, so new work is "implement a trait," not
"design an architecture"; (2) cross-compilation is free once the code avoids platform-only APIs (the FROST
SHA-512/RNG lesson); (3) every reference can be **gated against an existing implementation** for correctness,
then shipped with native backends as an opt-in. The endgame is a self-contained, vendor-optional financial /
crypto runtime that runs identically on JVM, JS, Rust, and WASM — with native acceleration wherever it exists.
