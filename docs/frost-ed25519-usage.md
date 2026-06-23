# FROST-Ed25519 — usage guide

`FROST` (Flexible Round-Optimized Schnorr Threshold signatures) lets a key be split among `n` parties so that
any `t` of them collaborate to produce **one standard Ed25519 signature** — the key is never reconstructed in
one place, and a verifier can't tell the signature came from a quorum. Module: `cryptoFrost`
(`payments/crypto/frost`), package `scalascript.crypto.frost`. Spec: [`specs/frost-ed25519.md`](../specs/frost-ed25519.md).

## When to use it

- **Shared custody / no single point of compromise** — no party ever holds the whole key; an attacker must
  compromise `t` parties at once.
- **Resilience** — lose up to `n − t` shares and you can still sign.
- **Compact + private on-chain** — the output is an ordinary 64-byte Ed25519 signature for one public key
  (unlike multisig, which exposes every signer and costs more).
- It produces **standard Ed25519** — so it works anywhere Ed25519 is accepted (Solana, Cardano, Cosmos
  Ed25519 accounts, generic signing) with a normal verifier.

## The flow

```
keygen (once)            →  shares + group public key
round 1 (per signer)     →  secret nonces  + public commitments
round 2 (per signer)     →  partial signature   (needs everyone's commitments)
aggregate                →  one 64-byte Ed25519 signature
verify                   →  any standard Ed25519 verifier
```

## Example — 2-of-3 signing

```scala
import scalascript.crypto.frost.{FrostKeygen, FrostSign}

// 1. Key generation (trusted dealer here; a real DKG is a later refinement).
//    `ks.shares` are the secret shares (one per participant); `ks.groupPublicKey` is the
//    Ed25519 public key everyone verifies against; `ks.commitments` are Feldman VSS commitments.
val ks = FrostKeygen.generate(threshold = 2, total = 3)

// (optional) each participant can verify its share against the public commitments:
ks.shares.foreach(s => assert(FrostKeygen.verifyShare(s, ks.commitments)))

val msg = "transfer 10 to alice".getBytes("UTF-8")

// 2. Pick a signing quorum of `t` participants and run round 1 — each draws fresh nonces and
//    publishes its commitments. Keep the Nonce secret; broadcast the Commitment.
val signers = ks.shares.take(2)
val round1  = signers.map(s => s.id -> FrostSign.round1(s.id)).toMap
val commitments = signers.map(s => round1(s.id)._2)   // the public set, shared with all signers

// 3. Round 2 — each signer produces a partial signature over the full commitment set.
val partials = signers.map { s =>
  FrostSign.partialSign(round1(s.id)._1, s, msg, commitments, ks.groupPublicKey)
}

// 4. Aggregate into a standard 64-byte Ed25519 signature.
val sig: Array[Byte] = FrostSign.aggregate(msg, commitments, partials)

// 5. Verify with ANY Ed25519 verifier — e.g. the project's CryptoBackend:
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo}
val ok = CryptoBackend.get().verify(Curve.Ed25519, ks.groupPublicKey, msg, sig, HashAlgo.None)
assert(ok)   // true — indistinguishable from a single-signer Ed25519 signature
```

`generateFrom(coeffs, total)` is the deterministic variant (explicit polynomial coefficients) for tests and as
the building block a real distributed key generation would sum per-party polynomials with.
`FrostKeygen.reconstruct(shares)` Lagrange-interpolates the secret from any `t` shares (for recovery), and
`lagrangeAtZero` exposes the interpolation coefficient.

## Swapping the crypto backend (provider-independence)

FROST never calls curve/hash/RNG primitives directly — it goes through the `Ed25519Ops` seam (the same
pattern as `CryptoBackend`). The default is `Ed25519Ops.Reference`: a pure-`BigInteger` Ed25519 + our own
`Sha512`, with **no external dependency**, that compiles to every backend. To use a platform-native
implementation (e.g. BouncyCastle on the JVM, `@noble` on JS, a Rust crate), register it once and FROST uses
it transparently:

```scala
import scalascript.crypto.frost.Ed25519Ops

Ed25519Ops.register(myNativeOps)   // every subsequent FROST call uses it
// ... keygen / sign as above ...
Ed25519Ops.reset()                 // restore the pure reference
```

`myNativeOps` implements the `Ed25519Ops` trait (point ops, scalar field mod L, `sha512`, `randomBytes`). The
reference stays the correctness fallback; the native one is the fast path. Randomness is part of the seam too,
so a deterministic test backend can supply fixed bytes, and on a platform without `SecureRandom` the backend
supplies the platform CSPRNG.

## As a wallet vault (`walletVaultMpcFrost`)

FROST also plugs into the wallet stack as a threshold signer. The `McpVault` (kind `Mpc`) delegates every
signing call to a `RemoteSigningClient` — the same seam the external MPC providers (Fireblocks, Coinbase, …)
use. `FrostSigningClient` is the **in-house** implementation of that seam: instead of calling an external TSS
service, it runs the FROST protocol locally over a `FrostQuorum`. So a threshold wallet is just an `McpVault`
wired to a `FrostSigningClient` — no new vault type:

```scala
import scalascript.crypto.frost.FrostKeygen
import scalascript.wallet.vault.mpc.McpVault
import scalascript.wallet.vault.mpc.frost.{FrostQuorum, FrostSigningClient}

val ks     = FrostKeygen.generate(threshold = 2, total = 3)
val quorum = new FrostQuorum("treasury", "Treasury", ks, signerIds = List(1, 2))
val vault: Vault = new McpVault("frost-treasury", new FrostSigningClient(Seq(quorum)))

vault.unlock(UnlockCredential.None)
val signer = vault.getSigner(Curve.Ed25519, "mpc/treasury").value.get.get
val sig    = signer.sign("transfer 100 to alice".getBytes("UTF-8")).value.get.get  // 64-byte Ed25519 sig
```

`FrostQuorum` is the trusted-coordinator / single-node form (it holds the signing subset's shares in-process); a
distributed deployment keeps each share on its own host and gathers the round-1 commitments + round-2 partials
over a transport — the production counterpart of `HttpRemoteSigningClient`. Either way `sk` is never
reconstructed. Module `walletVaultMpcFrost` (`payments/wallet/wallet-vault-mpc-frost`).

## Notes / limits

- The reference is **correctness-first, not constant-time** (it uses `BigInteger`). For a side-channel-hardened
  deployment, register a constant-time native backend.
- Key generation is **trusted-dealer** today; a full DKG (no trusted party) is a planned refinement.
- Verified end-to-end against BouncyCastle's reference Ed25519 (public keys bit-for-bit; signatures verify for
  every `t`-of-`n` subset).
