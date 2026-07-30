# Payments, wallet and chain SPI — backlog

Can-wait and not-yet-started work whose code lives in `payments/`. When an item is
picked up it moves to `payments/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## Crypto/finance roadmap — later epics (2026-06-23, with Sergiy)

The larger / later items of the crypto/blockchain/identity/payments roadmap. Near-term codeable slices are in
`SPRINT.md` → "Crypto/finance roadmap". Full plan + per-item "what / why / where / benefit":
**[`docs/crypto-finance-roadmap.md`](docs/crypto-finance-roadmap.md)** (explainer) +
**[`specs/crypto-finance-roadmap.md`](specs/crypto-finance-roadmap.md)** (engineering plan). All follow the
**reference → seam → gate → native** FROST template. Grouped here so the area isn't scattered.

**Track 1 — chains & currencies (deeper):**
- [~] **crypto-spi-pure-references** — pure-Scala references for Keccak-256, Blake2b, RIPEMD-160, secp256k1
      scalar/point math, `register`-able as the SPI fallback so each primitive runs with no native provider
      (deepens `crypto-spi-blake2b`). Gate: bit-for-bit vs BouncyCastle/`@noble` over RFC vectors + random inputs.
      **ALL FOUR REFERENCES NOW EXIST:** Blake2b / RIPEMD-160 / secp256k1 (+ SHA-256/512, Ed25519) landed with
      `chains-backend-agnostic`; **Keccak-256 added 2026-07-05** (`Keccak256.scala` in `crypto-spi/shared`,
      pure Keccak-f[1600] sponge, Ethereum pad 0x01) — bit-for-bit vs BouncyCastle over rate-boundary + multi-
      block inputs, JVM+JS byte-identical. **P-256 added 2026-07-05** (`P256Group.scala` + `P256Ecdsa.scala`,
      a=-3 Jacobian curve + ECDSA) — byte-for-byte vs BouncyCastle. **REMAINING:** a `register`-able
      pure-reference `CryptoBackend` that wires them all as the SPI fallback so primitives run with no
      native provider.
- [ ] **chains-new-adapters** (epic) — a `ChainAdapter` per new chain: Aptos / Sui / Stellar / XRPL / Polkadot
      (Ed25519 or secp256k1 + tidy encoding). "Mostly another adapter" once the primitive is in the SPI.
      **Polkadot is BLOCKED on an sr25519 (Schnorrkel) reference** — `Curve.Sr25519` is enumerated but
      unimplemented. Gate: address derivation + a signed-tx fixture per chain.

**Track 2 — threshold & MPC (heavier, after `frost-secp256k1`):**
- [ ] **musig2** — Bitcoin n-of-n as a single on-chain key; MuSig2 2-round aggregation over the secp256k1
      Schnorr base from `frost-secp256k1`. Gate: aggregated sig verifies as an ordinary BIP-340 single-key sig;
      BIP-327 vectors.
- [ ] **threshold-ecdsa** (heaviest — genuinely multi-round MPC, NOT "implement a trait") — GG/Lindell
      threshold ECDSA (Paillier/OT) for legacy Bitcoin/Ethereum (ECDSA) addresses. Own module; reuses only the
      Shamir/Lagrange base. Gate: output verifies as standard ECDSA vs a reference for random t-of-n.
- [ ] **vrf-bls** — VRF (RFC 9381 ECVRF) for leader-election/lottery randomness; BLS aggregate signatures over
      **BLS12-381** (`Curve.Bls12_381` enumerated, unimplemented → **BLOCKED on a pairing-friendly-curve
      reference**). Gate: VRF + BLS aggregate verify and match RFC/IETF vectors.

**Track 3 — identity & token services (clusters):**
- [~] **webauthn-server-verify** — server-side passkey assertion verification (P-256/Ed25519 verify + CBOR
      attestation), closing the loop with our existing client-assertion path (ERC-4337 passkey owner). Gate:
      W3C WebAuthn vectors + round-trip with our own client assertions.
      **Assertion-verify core DONE 2026-07-05** (`WebAuthnVerify.scala` in `crypto-spi/shared`): COSE_Key
      parse (EC2/P-256 → ES256, OKP/Ed25519 → EdDSA) via `Cbor` + signature check over
      `authenticatorData ‖ SHA-256(clientDataJSON)` (ES256 DER via `P256Ecdsa`, EdDSA raw via `Ed25519`),
      round-trip + tamper/wrong-key rejection, JVM+JS. **REMAINING:** registration/attestation-statement
      verification (packed/tpm/…) and the caller-side policy checks (challenge/origin/rpIdHash/UP-UV/signCount).
- [~] **token-formats** — PASETO / JWT / COSE token sign+verify over the crypto SPI (COSE pairs with
      webauthn-server-verify). Gate: RFC 7519 (JWT) / PASETO / RFC 8152 (COSE) vectors.
      **JWS/JWT DONE 2026-07-05** (`Jws.scala` + `Jwt` in `crypto-spi/shared`): portable compact JWS
      (RFC 7515) sign+verify for **HS256** (HmacSha256) and **EdDSA** (Ed25519) on the portable crypto
      primitives — byte-exact vs RFC 7515 A.1 + RFC 8037 A.4, JVM+JS, with constant-time MAC compare and
      tamper/malformed-token rejection.
      **PASETO v4.public DONE 2026-07-05** (`PasetoV4.scala` in `crypto-spi/shared`): portable
      `v4.public` sign+verify (Ed25519 over PAE), footer + implicit-assertion binding, version/purpose/
      tamper rejection — PAE pinned to the PASETO spec vectors + verified against the official `v4.json`
      "4-S-1" public key, JVM+JS.
      **COSE_Sign1 EdDSA DONE 2026-07-05** (`Cbor.scala` + `CoseSign1.scala` in `crypto-spi/shared`):
      a minimal portable CBOR codec (gated by RFC 8949 Appendix A) + COSE_Sign1 (RFC 8152/9052) sign+verify
      with EdDSA (`alg -8`), external-AAD binding, alg/tamper rejection — round-tripped under the RFC 8037
      key, JVM+JS. Unblocks `webauthn-server-verify` (COSE structures now available).
      **JWS ES256K DONE 2026-07-05** (`Secp256k1Ecdsa.derToRaw`/`rawToDer` + `Jws.signES256K`/`verifyES256K`
      + `Jwt.es256k`): ECDSA secp256k1 + SHA-256 with the fixed 64-byte R‖S encoding — byte-for-byte equal
      to the BouncyCastle secp256k1 backend (both RFC-6979 + low-S), JVM+JS.
      **COSE ES256K DONE 2026-07-05** (`CoseSign1.signES256K`/`verifyES256K`, protected `{1:-47}`):
      COSE_Sign1 now covers EdDSA + ES256K over the same R‖S helper, with an authenticated alg guard
      (cross-alg confusion rejected), round-tripped JVM+JS.
      **Portable P-256 reference DONE 2026-07-05** (`P256Group.scala` + `P256Ecdsa.scala` in
      `crypto-spi/shared`): NIST P-256 group (a=-3 Jacobian doubling) + ECDSA (RFC-6979 + SHA-256, DER +
      64-byte R‖S) — byte-for-byte equal to the BouncyCastle P-256 backend (derivePublic + verify interop),
      JVM+JS. **Unblocks ES256** (`Curve.P256` no longer BouncyCastle-only) and `webauthn-server-verify`.
      **ES256 DONE 2026-07-05** (`Jws.signES256`/`verifyES256` + `Jwt.es256`; `CoseSign1.signES256`/
      `verifyES256`, COSE alg `-7` / protected `{1:-7}`): ECDSA P-256 + SHA-256, 64-byte R‖S — the JWS path
      **verifies the published RFC 7515 A.3 ES256 token**, COSE round-trips with the authenticated alg guard,
      JVM+JS. token-formats now covers JWS HS256/EdDSA/ES256K/ES256, PASETO v4.public, and COSE_Sign1
      EdDSA/ES256K/ES256.
      **COSE_Encrypt0 DONE 2026-07-05** (`CoseEncrypt0.scala`, RFC 8152 §5.2, alg 24 ChaCha20-Poly1305
      over `Cbor` + `ChaCha20Poly1305`): encrypt/decrypt with the `Enc_structure` AAD + `{5:iv}` header,
      round-trip + tamper/wrong-key/wrong-AAD rejection, JVM+JS — COSE now covers sign (COSE_Sign1) and
      encrypt (COSE_Encrypt0). **REMAINING:** PASETO **v4.local** (XChaCha20 + keyed BLAKE2b — extend
      `ChaCha20Poly1305` with HChaCha20 + add keyed `Blake2b`); multi-recipient COSE_Encrypt.
- [~] **noise-protocol** — Noise handshake patterns over the existing X25519 + ChaCha20-Poly1305 primitives
      (short hop — WalletConnect already uses them). Gate: Noise spec vectors (XX, IK).
      Primitives portable: `ChaCha20Poly1305.scala` (RFC 8439) + `X25519.scala` (RFC 7748) +
      `HkdfSha256.scala` (RFC 5869), byte-exact, JVM+JS.
      **Noise 11 patterns DONE 2026-07-05** (N/NN/NK/NX/XN/XX/XK/KK/IN/IK/IX; `Noise.scala`): a pattern-driven engine (CipherState +
      SymmetricState + HandshakeState, pre-message support + the `e s ee es se ss` tokens) over the
      25519/ChaChaPoly/SHA256 suite. Built-in `NN` (unauthenticated), `XX` (mutual auth), and `IK`
      (initiator pre-knows the responder static — WireGuard/Lightning style). Functional gate per pattern:
      a full handshake derives matching transport keys, the auth semantics hold (NN: no statics; XX/IK:
      mutual), encrypted transport round-trips both ways, and a tampered message fails auth — JVM+JS.
      **REMAINING:** more patterns (NK/XK/…) + a byte-exact check against the cacophony/snow Noise
      test-vectors. The same primitives still unblock `age-encryption`; PASETO **v4.local** additionally
      needs keyed BLAKE2b (the XChaCha20 extended-nonce variant now exists — `ChaCha20Poly1305.xseal`/
      `xopen` + `hchacha20`, draft-irtf-cfrg-xchacha, 2026-07-05).
- [~] **did-vc** (epic) — did:key / did:web resolvers + Verifiable Credential signing (JSON-LD or JWT) over the
      crypto SPI; a whole decentralized-identity stack. Gate: W3C DID/VC test suites.
      **did:key DONE 2026-07-05** (`DidKey.scala` + a portable `Base58` btc codec in `crypto-spi/shared`):
      encode + resolve for Ed25519 (multicodec `0xed01` → `did:key:z6Mk…`) and compressed P-256 (`0x1200`
      → `did:key:zDn…`), matching the W3C did:key registry prefixes; base58 hand-vectors + round-trip, JVM+JS.
      With the JWS layer, JWT-VC issuance is now within reach. **REMAINING:** did:web resolver; VC data
      model (JWT-VC / JSON-LD) signing + verification; W3C DID/VC test-suite conformance.
- [ ] **age-encryption** — encrypt-to-public-key: age (X25519 + ChaCha20) first, PGP interop only if demanded.
      Gate: age reference vectors; round-trip with the `age` CLI.

**Track 4 — "invent our own" products:**
- [x] **threshold-custody-wallet** ✓ DONE 2026-06-24 — composed `cryptoFrost` (FROST-Ed25519) +
      `walletVaultMpcFrost` + an HTTP transport into a working distributed threshold-custody wallet.
      `FrostParticipantServer` (JDK `HttpServer`, holds ONE share, exposes `/round1` `/round2` `/health`) +
      `DistributedFrostSigningClient` (a `RemoteSigningClient` coordinator holding the group key + participant
      URLs but **no shares**, runs the 2-round protocol over HTTP/JSON and aggregates a standard Ed25519
      signature). **Gate MET:** a multi-host test (each share on its own localhost port = its own "host") signs
      with no co-located shares and the sig verifies under standard Ed25519 (2-of-3, 3-of-5); it drops straight
      into `McpVault` (unlock→getSigner→sign) — the threshold-custody-wallet end to end; `health()` is false when
      `<t` participants are reachable. walletVaultMpcFrost 8/0. Transport is HTTP/JSON; a WS or actor-cluster
      transport is the same protocol over a different pipe (bodies unchanged). No new deps (JDK http + ujson).
- [x] **micropayment-own-scheme** ✓ DONE 2026-06-24 — **PayWord hash-chain** scheme (`payments/micropayment/
      hashchain`, `ChannelKind.HashChain`): a from-scratch off-chain scheme over the portable crypto — one
      Ed25519-signed commitment at open (payer authorizes the chain tip `wₙ = SHA256ⁿ(seed)`), then **signature-free
      per-payment preimage reveals** (`w₍ₙ₋ᵤ₎`, verified with one SHA-256, no round-trip). `HashChain` (crypto) +
      `HashChainChannel` (MicropaymentChannel: pay reveals, receive verifies vs tip / incrementally, settle redeems
      the deepest reveal) + `HashChainProvider` (ChannelProvider). **Gate MET:** open→pay→receive→settle lifecycle +
      signed-commitment verify, forged-preimage / replay / over-capacity / non-multiple rejection, and the
      deepest-reveal-proves-cumulative property (payee may skip intermediates). 7/7; all other micropayment
      consumers recompile clean (ChannelKind addition safe). Settlement is off-chain accounting + the redemption
      proof (deepest preimage + signed commitment) — parity with the probabilistic provider's deferred on-chain
      claim.
- [ ] **distributed-infra** (speculative) — reference-first oracle/attestation, content-addressed storage, and
      gossip/CRDT layers over the actor/cluster substrate + crypto SPI. Gate: per-component correctness + a
      cluster integration test.

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`specs/blockchain-spi.md`](specs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`specs/wallet-spi.md`](specs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

### Phase 5 — blockchain-bitcoin ✓ Landed (2026-05-27)

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

### Phase 7 — blockchain-cosmos ✓ Landed (2026-05-27)

---

## Wallet SPI — Scala.js cross-compile ✓ Sprint complete (2026-05-20)

Spec in [`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`specs/wallet-spi.md`](specs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

### Stage 3 — Strategy + connector cross-compile ✓ Landed (2026-05-20)

### Stage 4 — `wallet-strategy-erc4337` cross-compile ✓ Landed (2026-05-20)

### Stage 5 — `wallet-vault-encrypted` cross-compile ✓ Landed (2026-05-20)

Stage 5a — light up the deferred KDF + AEAD primitives in
`crypto-noble-js`:

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6a — extend `CryptoBackend` SPI with the primitives WC needs
(additive only — no existing-method breakage):

- [ ] **Real browser-WebSocket integration testing** (BLOCKED: real browser + WalletConnect relay/project) — JS tests
      currently mock `BrowserWebSocket` (Node has no native
      `WebSocket` in the test runtime).  Live integration against
      `wss://relay.walletconnect.com` lands in the future PWA-wallet
      sprint that surfaces WC v2 in an actual browser.

Sprint closure: every wallet-spi-track module that has a JS-relevant
surface now cross-compiles JVM + Scala.js.  All future
`CryptoBackend` implementations are mandated to implement
ChaCha20-Poly1305, X25519, and the Stage 5 AEAD / KDF set in
addition to the original signing / hash / KDF surface — see
[`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md) §6 for
the full SPI checklist a new backend has to cover.

---

## Wallet SPI — key management + dApp connectivity

Spec in [`specs/wallet-spi.md`](specs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

### Phase 2 — Encrypted Vault ✓ Landed JVM + Scala.js core (2026-05-20)

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [ ] **WC project-ID open question** (DEFERRED deployment config) — still pending; the transport
      accepts a `projectId` argument on both platforms but CI does
      not yet provision one. To resolve before first production
      deployment.

### Phase 5 — Solana DappConnector ✓ Landed (2026-05-27)

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`specs/wallet-spi.md`](specs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

### Phase 8 — MPC Vault

- **FROST-Ed25519** → MOVED TO SPRINT 2026-06-23 (Sergiy "внеси в спринт"; active queue). Threshold Ed25519
      (FROST) signing as a `walletVaultMpcFrost` variant is now active work. (Other future MPC variants stay
      deferred until a concrete use case/partner.)

---
