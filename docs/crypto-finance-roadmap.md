# Crypto / finance roadmap — what we could build, and why

This is the **single grouped home** for the forward-looking crypto / blockchain / identity / payments work
that used to live as a loose brainstorm in [`capabilities.md` §7](capabilities.md). It explains *what* each
initiative is, *why* it matters, *where* it would be applied, and *what we gain* — in plain terms, item by item.

- The **engineering plan** (sliced work, file pointers, acceptance criteria, sequencing) is the companion
  spec: [`specs/crypto-finance-roadmap.md`](../specs/crypto-finance-roadmap.md).
- The **queued tasks** are in `SPRINT.md` (near-term, codeable now) and `BACKLOG.md` (larger / later epics),
  both under the "Crypto/finance roadmap" headings.
- The **current state** (what already exists) is [`capabilities.md` §4–§6](capabilities.md).

Everything here follows one repeating shape, the same one FROST-Ed25519 already demonstrates end-to-end.

---

## The one method behind all of it: *reference → seam → gate → native*

Every item below — a new chain, a threshold-signing protocol, a 2FA code generator, an identity format — is
built the same way, because the codebase already proves this shape works:

1. **Reference** — write a small, pure-ScalaScript / `BigInteger` / JDK implementation that avoids
   platform-only APIs, so it compiles to **every** backend (JVM, JS, Rust, WASM) for free.
2. **Seam** — define a trait for its primitives plus a `register / current / reset` registry, with the
   reference as the always-available default.
3. **Gate** — prove the reference correct by testing it against an existing, trusted implementation
   (BouncyCastle, `@noble/*`, or published RFC test vectors).
4. **Native** — optionally `register(...)` a per-platform native fast path (BouncyCastle on JVM, `@noble` on
   JS, a Rust crate) that is then used **transparently**. Where none exists, the reference still runs.

FROST-Ed25519 is the textbook instance (see [`capabilities.md` §6](capabilities.md)): a from-scratch
`Ed25519Group` + `Sha512` + protocol over `BigInteger`, behind a pluggable `Ed25519Ops` seam, gated against
BouncyCastle, with an optional native backend — and it runs identically on JVM and JS. **Every roadmap item is
"do that again" for a new primitive.** That's why this is cheap: new work is *implement a trait*, not *design
an architecture*.

---

## Where we stand today (honest snapshot)

| Area | Status | Note |
|---|---|---|
| **EVM** (addresses, ABI v2, RLP, EIP-712/3009, sign, **broadcast**) | ✅ done | pure-Scala encoders; has a JSON-RPC client (`clientEvm`) |
| **Solana** (message build, Base58, PDA, sign, **broadcast**) | ✅ done | pure-Scala; broadcasts via the generic RPC seam — but **no turnkey client module** like EVM's |
| **Crypto SPI** (sign/verify/recover, hashes, HD, KDF, AEAD, X25519) | ✅ done | curves secp256k1 / Ed25519 / P-256; BouncyCastle (JVM) + `@noble` (JS) |
| **FROST-Ed25519** threshold signing | ✅ done | our own, end-to-end to a wallet vault; runs on JVM **and** JS |
| **Cardano / Bitcoin / Cosmos** (CBOR, Bech32, PSBT, Amino) | 🟡 half | own encoders, but call **BouncyCastle directly** → JVM-only, not yet JS |
| **BIP-32 HD on JS** | 🟡 gap | `@noble` backend `deriveMaster`/`deriveChild` currently throw |
| **Blake2b** in the crypto SPI | 🟡 gap | Keccak-256 and RIPEMD-160 are already in `HashAlgo`; **Blake2b is the one missing hash** |
| Distributed transport between FROST signers | 🔴 not yet | `FrostQuorum` holds shares in-process; no network layer yet |

The takeaways that shape priority: the **biggest single dependency we still carry** is direct-BouncyCastle in
three chains, and removing it is the *same move we already did for SHA-512 in FROST*. The **smallest gap that
finishes a whole network** is a Solana client. And the **most natural product** we're ~90% toward is a
distributed threshold-custody wallet.

---

## Track 1 — Chains & currencies

The offline surface of most chains — addresses, transaction building, signing payloads — is just *encoding + a
signature*. We already prove this is pure-Scala-doable (EVM and Solana). The heavy parts are only (a) a few
curve/hash primitives and (b) RPC nodes.

### 1.1 Blake2b in the crypto SPI *(foundation, highest leverage)*
- **What** — add Blake2b (224- and 256-bit) to the `HashAlgo` enum and to both backends, with a pure-Scala
  reference for the SPI to fall back on. (Keccak-256 and RIPEMD-160 already exist in the SPI.)
- **Why** — Blake2b-224 is exactly the primitive Cardano calls BouncyCastle for directly. It's the last hash
  missing before three chains can drop their hard dependency.
- **Where** — `payments/crypto/spi` (the enum), `payments/crypto/bouncycastle` + `payments/crypto/noble-js`
  (the impls), and consumed by Cardano addressing.
- **Benefit** — unblocks items 1.3–1.5 below; one bounded, vector-gateable primitive.

### 1.2 BIP-32 HD key derivation on JS *(foundation)*
- **What** — implement BIP-32 / SLIP-0010 hierarchical-deterministic derivation in the `@noble` JS backend
  (`deriveMaster` / `deriveChild` currently throw "not yet implemented on Scala.js").
- **Why** — HD derivation is how a single seed phrase produces every account/address. Without it on JS, wallets
  and chain adapters can sign on the JVM but not in the browser.
- **Where** — `payments/crypto/noble-js`; consumed by every wallet vault and chain adapter that derives keys.
- **Benefit** — makes the whole wallet + chain stack genuinely browser-capable, not JVM-only.

### 1.3–1.5 Make Cardano / Bitcoin / Cosmos backend-agnostic *(highest architectural leverage)*
- **What** — route the three chains' crypto through the `CryptoBackend` SPI instead of importing
  `org.bouncycastle.*` directly: Cardano's Blake2b-224 (→ item 1.1), Bitcoin's secp256k1-DER signing +
  RIPEMD-160, Cosmos's secp256k1 + RIPEMD-160 + Ed25519. Then declare each module a cross-project so it builds
  on JS too.
- **Why** — these three are the only place in the financial stack that still bypasses the SPI, and that bypass
  is the single reason they're JVM-only and carry a heavy dependency.
- **Where** — `payments/blockchain/{cardano,bitcoin,cosmos}` (`CardanoAddress`, `BitcoinCrypto`,
  `CosmosCrypto`, `CosmosSignDoc`).
- **Benefit** — one repeated move (the "FROST move") makes **three chains** cross-compile to JS *and* sheds the
  last heavy vendor dependency from the crypto path. Architecturally the most valuable item on the board.

### 1.6 Solana RPC client *(smallest gap, finishes a network)*
- **What** — a concrete Solana JSON-RPC client / `ChainContext` transport module
  (`payments/client/solana`), the counterpart of the existing `clientEvm`.
- **Why** — we already *build, sign, and broadcast* Solana transactions; the adapter just needs a `ChainContext`
  to talk to, and today the caller has to supply one by hand. EVM ships a turnkey client; Solana doesn't.
- **Where** — new `payments/client/solana`, used wherever Solana transactions are submitted.
- **Benefit** — the smallest possible change that makes a whole chain *turnkey* end-to-end.

### 1.7 Own missing primitives behind the SPI *(the FROST move, repeated)*
- **What** — pure-Scala reference implementations of **Keccak-256**, **Blake2b**, **RIPEMD-160**, and
  **secp256k1** scalar/point math, sitting behind the SPI as the always-available fallback (native backends
  stay the fast path).
- **Why** — today these exist only via BouncyCastle/`@noble`. A pure reference means the primitive runs on
  *any* platform even with no native provider, exactly like FROST's `Sha512`.
- **Where** — `payments/crypto/spi` reference impls; gated against BouncyCastle/`@noble`.
- **Benefit** — each is a bounded, vector-gateable reference that removes a vendor dependency and unlocks a
  platform; collectively they make the crypto core self-contained.

### 1.8 Newer chains: Aptos / Sui / Stellar / XRPL / Polkadot *(cheap tail)*
- **What** — a `ChainAdapter` per chain. Their signing is Ed25519 or secp256k1 (Polkadot adds sr25519) plus a
  tidy encoding.
- **Why** — once the needed primitive is in the crypto SPI, a new chain is "mostly another `ChainAdapter`" —
  the architecture is already there.
- **Where** — `payments/blockchain/<chain>`, mirroring the EVM/Solana adapters.
- **Benefit** — broad coverage for low marginal cost; this is the dividend the SPI work pays out.
  *(Polkadot additionally needs sr25519 — enumerated in `Curve` but unimplemented — so it depends on a
  Schnorrkel reference.)*

---

## Track 2 — Threshold & MPC signing (our own, like FROST)

FROST-Ed25519 is done. Its scaffolding — Shamir secret sharing over a scalar field, Lagrange interpolation, the
`*Ops` seam, the in-house `RemoteSigningClient` integration — is **reusable** for a family of related protocols.
"Threshold" means *t-of-n* parties jointly produce one signature without any single party ever holding the whole
key; "MPC" (multi-party computation) is the general term for that.

### 2.1 FROST-secp256k1 *(high reach, reuses the most)*
- **What** — threshold Schnorr signatures on the secp256k1 curve (FROST is curve-agnostic; we have it on
  Ed25519, this ports it to secp256k1).
- **Why** — secp256k1 is Bitcoin's and Ethereum's curve. Schnorr on it is exactly **Bitcoin Taproot**; it also
  serves Ethereum.
- **Where** — a new `*Ops` seam mirroring `Ed25519Ops`, reusing `FrostKeygen` / `FrostSign` arithmetic; lands
  next to `cryptoFrost`.
- **Benefit** — opens threshold custody for the two largest chains; most of the code is already written.

### 2.2 MuSig2 *(Bitcoin multisig as one key)*
- **What** — a 2-round multi-signature scheme where *n* signers produce a single Schnorr signature that looks
  like an ordinary single-key signature on-chain.
- **Why** — it makes an *n-of-n* Bitcoin wallet indistinguishable from a normal one — smaller, cheaper, more
  private than script-based multisig.
- **Where** — alongside FROST-secp256k1 (shares the secp256k1 Schnorr machinery).
- **Benefit** — best-in-class Bitcoin multisig with no new on-chain footprint.

### 2.3 Threshold ECDSA (GG / Lindell) *(harder, broad legacy reach)*
- **What** — *t-of-n* threshold signing for **ECDSA** (the legacy signature scheme), via the
  Gennaro-Goldfeld or Lindell protocols.
- **Why** — most existing Bitcoin and all pre-Schnorr Ethereum addresses use ECDSA, which is far harder to
  thresholdize than Schnorr (it needs multi-round MPC with Paillier/OT machinery).
- **Where** — its own module; reuses the Shamir/Lagrange base but adds substantial protocol code.
- **Benefit** — threshold custody for the entire installed base of ECDSA addresses. Deliberately later — it's
  the heaviest item in this track.

### 2.4 VRFs and BLS signatures *(validator / aggregate infrastructure)*
- **What** — **VRF** (verifiable random function): a keyed function whose output is provably random and
  publicly verifiable, used for leader election and lotteries. **BLS**: a signature scheme whose signatures
  *aggregate* — many signatures over many messages compress into one.
- **Why** — VRFs power proof-of-stake leader selection (Cardano, Algorand) and fair randomness; BLS is the
  backbone of Ethereum consensus and any "thousands of validators, one aggregated signature" design.
- **Where** — crypto SPI references behind the `*Ops` pattern; BLS needs the BLS12-381 curve (enumerated in
  `Curve`, unimplemented).
- **Benefit** — unlocks staking/validator and aggregate-signature use cases; foundational for several newer
  chains.

### 2.5 Distributed FROST transport *(the missing 10% — most "productizing")*
- **What** — a real network layer between threshold signers: each share lives on its own host, round-1
  commitments and round-2 partials are exchanged over a transport. This is the production counterpart of the
  in-process `FrostQuorum` we have today.
- **Why** — `FrostQuorum` currently holds every signer's share in one process (a single-node simulation). A
  transport is what turns it into an actual *distributed* threshold wallet — the whole point of threshold
  custody is that the shares are *not* co-located.
- **Where** — a networked implementation of the existing `RemoteSigningClient` SPI (the same seam the external
  MPC vaults plug into); naturally built on our actors/cluster + HTTP/WS substrate.
- **Benefit** — converts a finished protocol into a deployable product. We're ~90% there; this is the
  highest-value single step toward a real custody offering.

---

## Track 3 — Identity & token services (not only currencies)

The same "own reference behind a seam" approach applies well beyond chains. These are small, pure, and
gateable against published RFC test vectors — most are a day or two each.

### 3.1 TOTP / HOTP *(2FA codes — quick win)*
- **What** — the standard one-time-password algorithms: **HOTP** (RFC 4226, counter-based) and **TOTP**
  (RFC 6238, time-based), the 6-digit codes in Google Authenticator / Authy.
- **Why** — every app with login wants 2FA; it's an HMAC plus a truncation, fully covered by RFC vectors.
- **Where** — a small `std`-level module over the existing `hmac` primitive.
- **Benefit** — a ubiquitous feature for almost no code; ideal first service.

### 3.2 WebAuthn / passkey server-side verification
- **What** — the *server* half of passkey login: verify the signed assertion a browser/authenticator returns
  during registration and authentication.
- **Why** — we already produce client assertions (in the ERC-4337 passkey-owner path); the server-side verify
  closes the loop so an app can actually *accept* passkey logins.
- **Where** — a service module over the crypto SPI (P-256 / Ed25519 verify + CBOR attestation parsing).
- **Benefit** — turns "we can sign with a passkey" into "we can log users in with a passkey."

### 3.3 PASETO / JWT / COSE token signing + verify
- **What** — signed-token formats. **JWT** is the ubiquitous JSON web token; **PASETO** is its
  security-hardened successor; **COSE** is the CBOR-based equivalent used by WebAuthn, FIDO, and IoT.
- **Why** — these are the lingua franca of auth and API access; we already have the signature primitives, so
  it's serialization + a verify path.
- **Where** — token service modules over the crypto SPI; COSE pairs naturally with 3.2.
- **Benefit** — first-class, dependency-free auth-token issuance and verification across all platforms.

### 3.4 Noise protocol framework
- **What** — **Noise** is a framework for building encrypted handshake protocols from a few primitives (DH +
  AEAD + hash); it's what WireGuard, WhatsApp, and Lightning use under the hood.
- **Why** — we already have X25519 + ChaCha20-Poly1305 on the SPI (WalletConnect uses them), so a Noise
  framework is a short hop and gives us a clean, modern, mutually-authenticated encrypted channel.
- **Where** — a protocol module over the existing X25519/AEAD primitives.
- **Benefit** — a reusable secure-channel building block for P2P, custody transport (Track 2.5), and more.

### 3.5 Shamir secret backup *(quick win — generalize what we have)*
- **What** — split an arbitrary secret (a seed phrase, an API key, a file-encryption key) into *n* shares such
  that any *t* reconstruct it and fewer reveal nothing — Shamir's Secret Sharing, à la SLIP-0039.
- **Why** — we already implement Shamir over the Ed25519 scalar field inside FROST; generalizing it to
  arbitrary byte secrets is mostly lifting that code over a prime field.
- **Where** — a `std`-level module reusing FROST's `FrostKeygen` Shamir/Lagrange logic.
- **Benefit** — social-recovery and split-backup for any secret, not just signing keys; very high utility for
  low cost.

### 3.6 DIDs / Verifiable Credentials
- **What** — **DID** (decentralized identifier): a self-owned, cryptographically-verifiable identity not issued
  by any central authority. **VC** (verifiable credential): a tamper-evident, cryptographically-signed claim
  ("X attests Y about Z").
- **Why** — the W3C standard for portable, privacy-preserving digital identity (diplomas, KYC attestations,
  membership) — a genuinely larger bet that builds a whole identity stack on our signature primitives.
- **Where** — an identity module over the crypto SPI (did:key / did:web resolvers, JSON-LD or JWT credential
  signing).
- **Benefit** — positions us in decentralized identity; bigger scope, so it's a BACKLOG epic, not a quick win.

### 3.7 age / PGP-style encryption
- **What** — file/message encryption to a recipient's public key: **age** is the modern, minimal design;
  **PGP** is the legacy interop target.
- **Why** — "encrypt this for that person" is a perennial need; age is essentially X25519 + ChaCha20, which we
  already have.
- **Where** — an encryption module over the SPI; age first (clean), PGP only if interop demands it.
- **Benefit** — turnkey asymmetric encryption with no heavy dependency.

---

## Track 4 — "Invent our own" (products that exploit our unique position)

Our differentiator is that *one source compiles to JVM / JS / Rust / WASM* and we have effects, actors, and
channels. These ideas lean on that.

### 4.1 Vendor-optional threshold-custody wallet *(the natural product)*
- **What** — a complete threshold wallet: FROST signing (2.1/2.2) + distributed transport (2.5), packaged as a
  product. No external custody vendor required.
- **Why** — the external MPC vaults (Fireblocks/Coinbase/Lit/ZenGo) are thin clients to *someone else's*
  service; `walletVaultMpcFrost` already proves we can be the in-house provider. Add a transport and it's a
  real distributed-deploy custody offering.
- **Where** — composes `cryptoFrost` + `walletVaultMpcFrost` + the new transport; ships as a wallet product.
- **Benefit** — the single most "ours" product derivable from what's already built — we're ~90% there.

### 4.2 Our own micropayment / settlement scheme
- **What** — a new off-chain payment-channel scheme of our own design.
- **Why** — the channel SPI already hosts threshold-batching, probabilistic lottery-ticket, EVM state-channel,
  and Cardano Hydra providers; inventing a new scheme is "another `ChannelProvider`."
- **Where** — `payments/micropayment` as a new provider behind the existing SPI.
- **Benefit** — a differentiated settlement primitive at low architectural cost.

### 4.3 Dependency-free infrastructure: oracle / attestation, content-addressed storage, gossip / CRDT
- **What** — a small attestation/oracle service (sign external facts on-chain-verifiably), content-addressed
  storage (hash-keyed blobs), and a gossip/CRDT layer (eventually-consistent replication).
- **Why** — these are reference-first, provider-pluggable building blocks; we already have cluster + actors to
  build the distributed parts on.
- **Where** — new modules over the actor/cluster substrate + crypto SPI.
- **Benefit** — rounds out a self-contained distributed-systems toolkit; the most speculative, so furthest out.

---

## The endgame

The thread through all of it: **a self-contained, vendor-optional financial / crypto runtime that runs
identically on JVM, JS, Rust, and WASM, with native acceleration wherever it exists.** Three things make this
realistic and cheap here, and they're worth restating because they're *why* this roadmap is mostly
"implement a trait," not "build an architecture":

1. **The SPIs already exist** — `CryptoBackend`, `ChainAdapter`, `Vault`/`RawSigner`, `RemoteSigningClient`,
   `ChannelProvider`, `PaymentProvider`. New work plugs into them.
2. **Cross-compilation is free** once code avoids platform-only APIs — the SHA-512/RNG lesson from FROST.
3. **Every reference is gateable** against an existing implementation for correctness, then shipped with native
   backends as an opt-in fast path.

For the concrete slice-by-slice plan and acceptance criteria, see
[`specs/crypto-finance-roadmap.md`](../specs/crypto-finance-roadmap.md).
