# FROST-Ed25519 — threshold Ed25519 signatures

Status: **IN PROGRESS** (slices 1-4 landed 2026-06-23 — FROST is functionally complete; only wallet-vault integration, slice 5, remains). SPRINT task `FROST-Ed25519`.

## 1. Goal

A self-contained `t`-of-`n` **FROST** (Flexible Round-Optimized Schnorr Threshold) signing scheme over
**Ed25519**, producing **standard Ed25519 signatures** (verifiable by any RFC 8032 verifier) — a
`walletVaultMpcFrost` variant of the wallet vault stack. Unlike the existing `walletVaultMpc*` variants
(Fireblocks / Coinbase / Lit / Zengo — *remote, external-provider* signing clients), FROST is **in-house**
threshold crypto: the signing key is split across `n` parties and any `t` collaborate to sign, with no single
party ever holding the key.

## 2. Decision: from-scratch curve math (Sergiy, 2026-06-23)

FROST needs low-level Ed25519 **group operations** (scalar arithmetic mod `L`, point add, base/arbitrary
scalar mult, encode/decode) — the codebase exposes **none** (`payments/crypto/bouncycastle/Ed25519.scala` is
only high-level sign/verify). Rather than add a new crypto dependency to the wallet stack, the group ops are
implemented **from scratch** (the RFC 8032 reference algorithm, `BigInteger`-backed), with correctness gated by
cross-checking against a reference Ed25519 (BouncyCastle, test-only).

## 3. Module + slices

New module **`cryptoFrost`** (`payments/crypto/frost`): pure main (no dependency), BouncyCastle is **test-only**
(reference cross-check). Slice plan (each independently green; correctness gate throughout = a FROST signature
verifies under the standard `Ed25519.verify` against the group public key):

- **Slice 1 — group arithmetic (DONE 2026-06-23).** `Ed25519Group` (`Ed25519Group.scala`): field mod
  `p = 2^255-19`; twisted-Edwards points in extended coords with unified `add`; `mul`/`mulBase`
  (double-and-add); `encode`/`decode` (RFC 8032 compression); base point `B`; order `L`; scalar field
  (`scalarAdd`/`Mul`/`Inv`/`Reduce`); `secretScalar` (clamp(SHA-512(seed)[0:32])). **Verified** by
  `Ed25519GroupTest` (6): base-point encoding = RFC value; `L·B = identity`; encode/decode round-trip; group
  homomorphism; scalar inverse; **and the gate — generated public keys match BouncyCastle Ed25519 bit-for-bit
  for 25 random seeds.** NOT constant-time (BigInteger) — correctness-first reference; harden later.
- **Slice 2 — key generation (DONE 2026-06-23).** `FrostKeygen` — trusted-dealer Shamir + Feldman VSS: Shamir-split a signing
  scalar into `t`-of-`n` shares over the scalar field + the group public key `B·sk`; per-participant
  verification shares. Verify: any `t` shares Lagrange-interpolate to `sk`; `< t` do not.
- **Slice 3 — signing rounds (DONE 2026-06-23, `FrostSign`).** Per-signer nonces `(d,e)` + commitments `(D,E)`; binding factors
  `ρ_i = H(i, msg, B)`; group commitment `R = Σ(D_i + ρ_i·E_i)`; challenge `c = H(R, Y, msg)` (Ed25519 SHA-512
  form); partial signatures `z_i = d_i + ρ_i·e_i + λ_i·c·s_i`.
- **Slice 4 — aggregate + verify (DONE 2026-06-23).** GATE PASSED — FROST sigs verify under BouncyCastle Ed25519 (2-of-3 + every 3-of-5). `z = Σ z_i`; signature `(R, z)` **must verify under the standard
  `Ed25519.verify`** against the group public key. Edge cases: insufficient shares, malformed share/commitment,
  abort. THE correctness milestone.
- **Slice 5 — ops seam (the substitution mechanism).** Give FROST the same pluggable-backend model as
  `CryptoBackend`: an `Ed25519Ops` trait (scalar field + point ops + `sha512`) that `FrostKeygen`/`FrostSign`
  call through; the pure-BigInteger `Ed25519Group` is the DEFAULT reference; a registry (`default` + `register`)
  lets a platform-native impl substitute transparently at runtime. SHA-512 routes through the seam (not
  `java.security`).
- **Slice 6 — cross-build.** `cryptoFrost` as `crossProject(JVM, JS)` so the reference compiles to JS too
  (BigInteger works in Scala.js; SHA-512 via the seam → `CryptoBackend.hash` = Noble/JS, BC/JVM). "One
  reference, every platform."
- **Slice 7 — native backend.** A JVM `Ed25519Ops` backend delegating substitutable primitives (SHA-512,
  final-signature verify) to BouncyCastle — proving transparent native substitution; reference stays the
  fallback for what BC doesn't expose (the group ops).
- **Slice 8 — vault integration.** Wire as `walletVaultMpcFrost` via the `walletSpi`/MPC-vault seam.

## 3b. Architecture: portable reference + pluggable native substitution

FROST follows the core ScalaScript design (and mirrors the existing `CryptoBackend` SPI): a **single portable
reference implementation** (pure `Ed25519Group` + `FrostKeygen`/`FrostSign`, BigInteger — compiles to every
backend) PLUS a **registry-based SPI seam** (`Ed25519Ops.register`/`default`) so the same calls resolve to a
**platform-native implementation when one exists** (BouncyCastle on JVM, `@noble` on JS, a Rust crate on Rust),
selected at runtime — exactly like `CryptoBackend` (BC auto-loaded via ServiceLoader on JVM; Noble `register`ed
on JS). The reference is always the correctness fallback; native backends are the fast path. This is what lets
"our own FROST" run anywhere AND transparently use a better platform implementation where available.

## 4. Non-goals

- Constant-time / side-channel hardening (reference-first; swap a hardened field backend later).
- DKG with dispute resolution (start with trusted-dealer keygen; full DKG is a later refinement).
- JS parity (the wallet stack's JS side uses `@noble/curves`; a JS FROST mirror is a separate effort).
