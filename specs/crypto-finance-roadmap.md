# Crypto / finance roadmap — engineering plan

Status: **PLANNED** (2026-06-23, with Sergiy — "да хочу. все хочу. … внеси все это в спринт или в беклог … и
напиши подробную документацию и спеку"). This is the canonical, grouped engineering plan for the
crypto / blockchain / identity / payments roadmap. Companion explainer (what each item *is* and *why*):
[`docs/crypto-finance-roadmap.md`](../docs/crypto-finance-roadmap.md). Queued work lives under the
"Crypto/finance roadmap" headings in `SPRINT.md` (near-term) and `BACKLOG.md` (later epics).

## 1. Goal & scope

Take the loose forward-looking brainstorm from [`docs/capabilities.md` §7](../docs/capabilities.md) and turn it
into a committed, sliced plan — every initiative grounded in code that exists today, each expressed as the same
repeatable build shape. **Non-goal:** doing any of it in this change. This commit only *plans and documents*;
implementation is per the SPRINT/BACKLOG slices below.

## 2. The build shape (binding for every slice)

Every slice follows **reference → seam → gate → native**, exactly as FROST-Ed25519 did
([`specs/frost-ed25519.md`](frost-ed25519.md), [`docs/capabilities.md` §6](../docs/capabilities.md)):

1. **Reference** — pure ScalaScript / `BigInteger` / JDK, no platform-only API → compiles to every backend.
2. **Seam** — a trait + `register / current / reset` registry, reference as default.
3. **Gate** — correctness proven against BouncyCastle / `@noble` / published RFC test vectors.
4. **Native** — optional per-platform fast path, used transparently; reference is the fallback.

A slice is "done" only when: green on the gate, an `examples/` program exists if it's user-facing (per
`AGENTS.md`), and SPRINT/CHANGELOG are updated.

## 3. Grounded baseline (verified 2026-06-23 against `origin/main`)

The facts the plan depends on — checked in code, not assumed:

- **`HashAlgo`** (`payments/crypto/spi/shared/.../HashAlgo.scala`) = `None, Sha256, Sha512, Keccak256,
  Ripemd160, HmacSha512`. **Keccak-256 and RIPEMD-160 already exist; Blake2b is the one missing hash.**
- **`CryptoBackend`** (same dir): `sign/verify/derivePublic/recoverPublic`, `hash/hmac`,
  `deriveMaster/deriveChild` (BIP-32/SLIP-0010), `pbkdf2/argon2id/hkdf`, `aesGcm*`, `chacha20Poly1305*`,
  `x25519*`, `randomBytes`. Curves (`Curve.scala`): `Secp256k1, Ed25519, P256` implemented; `Sr25519,
  Bls12_381` enumerated but unimplemented (`supports` → false).
- **Backends**: `payments/crypto/bouncycastle` (JVM, ServiceLoader, implements everything) and
  `payments/crypto/noble-js` (`@noble`, JS, `register`). **noble-js `deriveMaster`/`deriveChild` throw**
  ("HD derivation not yet implemented on Scala.js").
- **Chain adapters** (`payments/blockchain/{evm,solana,cardano,bitcoin,cosmos}`): all five are JVM-only
  `project`s. `ChainAdapter` (`payments/blockchain/spi`) reaches the network only through
  `ChainContext.rpcCall(method, params*)` — adapters don't embed transports.
  - **EVM, Solana, Cardano** broadcast (EVM/Solana via `rpcCall`, Cardano via `BlockfrostClient`).
  - **Bitcoin, Cosmos** build+sign offline; network ops throw "no node connection configured".
  - **Direct BouncyCastle bypass** (the only crypto path not going through the SPI):
    `cardano/CardanoAddress.scala` (Blake2b-224), `bitcoin/BitcoinCrypto.scala` (secp256k1-DER + RIPEMD-160),
    `cosmos/CosmosCrypto.scala` + `cosmos/CosmosSignDoc.scala` (secp256k1 + RIPEMD-160 + Ed25519).
  - **Client modules**: `payments/client/{evm,blockfrost,coinbase}` — **no `solana`**.
- **FROST** (`cryptoFrost` crossProject `payments/crypto/frost`, `walletVaultMpcFrost`
  `payments/wallet/wallet-vault-mpc-frost`): Ed25519 threshold signing, end-to-end to a vault, JVM+JS. The
  in-process `FrostQuorum` holds all signing shares in one process — **no network transport between signers.**
- **Channels** (`payments/micropayment`): `ChannelProvider` SPI + four providers (state-channel, threshold,
  probabilistic, Hydra).

## 4. Track 1 — Chains & currencies

> Dependency order: **1.1 + 1.2 are foundations** for 1.3–1.5; 1.6 is independent; 1.7 deepens 1.1; 1.8 depends
> on the relevant primitive being in the SPI.

### 1.1 `crypto-spi-blake2b` — Blake2b in the SPI *(SPRINT, foundation)*
- Add `Blake2b224` and `Blake2b256` to `HashAlgo`.
- Implement in `bouncycastle` (`Blake2bDigest`, already used by Cardano) and `noble-js` (`@noble/hashes/blake2b`).
- Add a pure-Scala `Blake2b` reference for the SPI fallback (mirrors FROST's `Sha512`).
- **Gate**: RFC 7693 test vectors + Cardano address fixtures match across both backends + the reference.

### 1.2 `noble-js-hd-derivation` — BIP-32 HD on JS *(SPRINT, foundation)*
- Implement `deriveMaster` / `deriveChild` in `noble-js` (currently throw), via `@scure/bip32` /
  `@noble/hashes` HMAC-SHA512, for secp256k1 and Ed25519 (SLIP-0010).
- **Gate**: byte-for-byte equal to the BouncyCastle backend for the existing JVM HD fixtures (BIP-32 + SLIP-0010
  test vectors).

### 1.3–1.5 `chains-backend-agnostic` — Cardano / Bitcoin / Cosmos off direct BouncyCastle *(SPRINT, highest leverage)*
Per chain, replace `org.bouncycastle.*` calls with `CryptoBackend` calls, then make the module a crossProject.
- **1.3 Cardano**: `CardanoAddress` Blake2b-224 → `crypto.hash(Blake2b224, …)` (needs 1.1). Then
  `blockchainCardano` → crossProject; verify CIP-19 address fixtures on JVM+JS.
- **1.4 Bitcoin**: `BitcoinCrypto` secp256k1 DER signing → `crypto.sign(Secp256k1, …)` (the SPI already does
  low-S DER for ECDSA) + RIPEMD-160 → `crypto.hash(Ripemd160, …)`. Then `blockchainBitcoin` → crossProject;
  verify P2WPKH/PSBT fixtures on JVM+JS.
- **1.5 Cosmos**: `CosmosCrypto` + `CosmosSignDoc` secp256k1 + RIPEMD-160 + Ed25519 → SPI. Then
  `blockchainCosmos` → crossProject; verify Amino sign-doc fixtures on JVM+JS.
- **Gate (all three)**: existing per-chain address/signing tests stay green on JVM **and** newly pass on JS;
  zero `org.bouncycastle` imports remain in the three modules' `src/main`.

### 1.6 `client-solana-rpc` — turnkey Solana client *(SPRINT, smallest gap)*
- New `payments/client/solana` providing a `ChainContext` over Solana JSON-RPC (`sendTransaction`,
  `getLatestBlockhash`, `getBalance`, `getTokenAccountsByOwner`, `getTransaction`), mirroring `clientEvm`.
- **Gate**: an `examples/` end-to-end build→sign→broadcast against a devnet/mock RPC; parity with the EVM
  client's shape.

### 1.7 `crypto-spi-pure-references` — own primitives behind the SPI *(BACKLOG)*
- Pure-Scala references for Keccak-256, Blake2b (→ folds in 1.1), RIPEMD-160, and secp256k1 scalar/point math,
  each `register`-able as the SPI fallback so the primitive runs with no native provider.
- **Gate**: each reference matches BouncyCastle/`@noble` bit-for-bit over RFC vectors + random inputs.

### 1.8 `chains-new-adapters` — Aptos / Sui / Stellar / XRPL / Polkadot *(BACKLOG, epic)*
- One `ChainAdapter` per chain (`payments/blockchain/<chain>`), reusing the EVM/Solana template. Signing is
  Ed25519 or secp256k1; **Polkadot additionally needs sr25519** (enumerated in `Curve`, unimplemented → blocked
  on a Schnorrkel reference).
- **Gate**: address derivation + a signed tx fixture per chain; broadcast via `ChainContext`.

## 5. Track 2 — Threshold & MPC signing (reuses FROST scaffolding)

> All reuse FROST's Shamir-over-scalar-field + Lagrange + the `*Ops` seam pattern. Order: 2.1 → 2.2 share a
> secp256k1 Schnorr base; 2.5 is independent and the most product-shaped; 2.3/2.4 are heavier and later.

### 2.1 `frost-secp256k1` — threshold Schnorr on secp256k1 *(SPRINT, high reach)*
- A `Secp256k1Ops` seam mirroring `Ed25519Ops`; reuse `FrostKeygen` / `FrostSign` (curve-agnostic arithmetic).
  Produces BIP-340 Schnorr signatures → **Bitcoin Taproot** + Ethereum.
- **Gate**: a FROST-secp256k1 signature verifies under a standard BIP-340 verifier (BouncyCastle/`@noble`,
  test-only), for random `t`-of-`n`.

### 2.2 `musig2` — Bitcoin n-of-n as a single key *(BACKLOG)*
- MuSig2 2-round aggregation over the secp256k1 Schnorr base from 2.1.
- **Gate**: aggregated signature verifies as an ordinary BIP-340 single-key signature; matches BIP-327 vectors.

### 2.3 `threshold-ecdsa` — GG/Lindell *(BACKLOG, heaviest)*
- Multi-round threshold ECDSA (Paillier/OT machinery) for legacy Bitcoin/Ethereum addresses. Own module; reuses
  the Shamir/Lagrange base only.
- **Gate**: produced signature verifies as standard ECDSA; cross-checked against a reference for random `t`-of-`n`.

### 2.4 `vrf-bls` — verifiable randomness + aggregate signatures *(BACKLOG)*
- VRF (RFC 9381 ECVRF) reference for leader-election/lottery; BLS signatures over **BLS12-381** (enumerated in
  `Curve`, unimplemented → needs a pairing-friendly-curve reference).
- **Gate**: VRF output verifies + matches RFC 9381 vectors; BLS aggregate verifies + matches IETF vectors.

### 2.5 `frost-distributed-transport` — network layer between signers *(SPRINT, most productizing)*
- A networked `RemoteSigningClient` implementation that keeps each share on its own host and exchanges round-1
  commitments + round-2 partials over a transport (the production counterpart of in-process `FrostQuorum`).
  Built on the actors/cluster + HTTP/WS substrate.
- **Gate**: a multi-process (or multi-actor) `t`-of-`n` signing run produces a valid signature with **no single
  process holding all shares**; same signature as the in-process `FrostQuorum` for the same inputs.

## 6. Track 3 — Identity & token services

> Small, pure, RFC-vector-gateable. 3.1 and 3.5 are quick wins (SPRINT); the rest are BACKLOG clusters.

### 3.1 `totp-hotp` — RFC 4226 / 6238 one-time passwords *(SPRINT, quick win)*
- HOTP (counter) + TOTP (time) over the existing `hmac` primitive; `std`-level module.
- **Gate**: RFC 4226 / 6238 reference vectors.

### 3.5 `shamir-secret-backup` — split arbitrary secrets *(SPRINT, quick win)*
- Generalize FROST's scalar-field Shamir to arbitrary byte secrets over a prime field (SLIP-0039-style `t`-of-`n`).
- **Gate**: split→recombine round-trips for random secrets; `< t` shares reveal nothing (recombination fails);
  SLIP-0039 vectors where applicable.

### 3.2 `webauthn-server-verify` — passkey assertion verification *(BACKLOG)*
- Server-side WebAuthn registration + authentication verification (P-256 / Ed25519 verify + CBOR attestation),
  closing the loop with the existing client-assertion path.
- **Gate**: W3C WebAuthn test vectors; round-trip with our own client assertions.

### 3.3 `token-formats` — PASETO / JWT / COSE *(BACKLOG, cluster)*
- Signed-token sign+verify over the crypto SPI. COSE pairs with 3.2.
- **Gate**: RFC 7519 (JWT) / PASETO / RFC 8152 (COSE) vectors.

### 3.4 `noise-protocol` — encrypted handshake framework *(BACKLOG)*
- Noise patterns over the existing X25519 + ChaCha20-Poly1305 primitives.
- **Gate**: Noise spec test vectors (e.g. `XX`, `IK` handshakes).

### 3.6 `did-vc` — decentralized identity *(BACKLOG, epic)*
- did:key / did:web resolvers + Verifiable Credential signing (JSON-LD or JWT) over the crypto SPI.
- **Gate**: W3C DID/VC test suites; sign→verify round-trips.

### 3.7 `age-encryption` — encrypt-to-public-key *(BACKLOG)*
- age (X25519 + ChaCha20) first; PGP interop only if demanded.
- **Gate**: age reference vectors; round-trip with the `age` CLI.

## 7. Track 4 — "Invent our own" products

### 4.1 `threshold-custody-wallet` — vendor-optional product *(BACKLOG; unblocked by 2.1 + 2.5)*
- Compose `cryptoFrost` + `walletVaultMpcFrost` + `frost-distributed-transport` into a packaged distributed
  threshold-custody wallet. ~90% of the parts already exist.
- **Gate**: a deployable multi-host demo signs without co-located shares; documented as a product example.

### 4.2 `micropayment-own-scheme` — new ChannelProvider *(BACKLOG)*
- A new off-chain settlement scheme behind the existing `ChannelProvider` SPI.
- **Gate**: open→pay→settle lifecycle test, parity with existing providers.

### 4.3 `distributed-infra` — oracle / attestation, CAS, gossip/CRDT *(BACKLOG, speculative)*
- Reference-first building blocks over the actor/cluster substrate + crypto SPI.
- **Gate**: per-component correctness + a cluster integration test.

## 8. Sequencing (recommended)

```
SPRINT (near-term, high-leverage, codeable now):
  crypto-spi-blake2b ─┐
  noble-js-hd-deriv ──┼─→ chains-backend-agnostic (cardano → bitcoin → cosmos)  [highest architectural value]
  client-solana-rpc          (independent — smallest gap that finishes a network)
  frost-secp256k1            (reuses FROST scaffolding — Bitcoin Taproot + Ethereum)
  frost-distributed-transport (most productizing — turns FrostQuorum into a real distributed signer)
  totp-hotp, shamir-secret-backup  (quick service wins, vector-gated)

BACKLOG (larger / later):
  crypto-spi-pure-references, chains-new-adapters (Aptos/Sui/Stellar/XRPL/Polkadot)
  musig2, threshold-ecdsa, vrf-bls
  webauthn-server-verify, token-formats (PASETO/JWT/COSE), noise-protocol, did-vc, age-encryption
  threshold-custody-wallet (after frost-secp256k1 + frost-distributed-transport)
  micropayment-own-scheme, distributed-infra
```

## 9. Risks & open questions

- **Constant-time / side channels** — like FROST's `BigInteger` math, the pure references are correctness-first,
  not constant-time. Acceptable as a fallback (the native backend is the production fast path), but threshold
  signing over secrets warrants a hardening pass before any high-value custody deployment. Track per-slice.
- **secp256k1 DER in the SPI** — 1.4 assumes the SPI's `sign(Secp256k1, …)` already emits canonical low-S DER;
  verify against Bitcoin's current direct-BouncyCastle output before migrating, to avoid a malleability regression.
- **sr25519 / BLS12-381** — Polkadot (1.8) and BLS (2.4) need curves enumerated but unimplemented; each is a
  prerequisite reference, not a quick adapter.
- **Threshold-ECDSA scope** — 2.3 is genuinely multi-round MPC (Paillier/OT); it is the one item here that is
  *not* "implement a trait." Kept deliberately late.
- **Cross-build cost** — making cardano/bitcoin/cosmos crossProjects pulls them into the JS test matrix; budget
  for the added CI surface.
