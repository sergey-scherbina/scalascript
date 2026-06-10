# `std.crypto` — public-key signature verification (Ed25519 / RSA)

**Status**: ✓ Landed 2026-06-10 (JVM/interpreter via `crypto-plugin`; JS WebCrypto deferred). Requested by busi (Phase 87 federation/market), 2026-06-09.
**Extends**: [`crypto.md`](crypto.md) (sha256 / hmac / base64).
**Priority**: medium-high. Blocks busi from verifying public identity signatures;
public signature-bearing traffic is currently quarantined as `signature.unsupported`.

## 1  Motivation

busi's peer-exchange and public-market protocols (Phase 86/87) carry signed
descriptors and message envelopes. Today busi can:
- classify a signature's declared algorithm, and
- verify **HMAC** when an explicit shared secret is supplied.

It **cannot** verify asymmetric public-key signatures (Ed25519, RSA) from `.ssc`,
because no script-level verifier exists. So every public-key-signed message is
quarantined with `signature.unsupported` and the busi TODO marker
`TODO(scalascript-signatures)`. This blocks trustless federation: a peer's public
identity signature cannot be checked without a pre-shared secret.

## 2  Proposed surface (`runtime/std/crypto.ssc`)

```scalascript
// Ed25519 — verify a detached signature over message bytes.
//   publicKey: base64 (raw 32-byte Ed25519 public key, or SPKI DER base64)
//   message:   the signed payload as a UTF-8 string
//   signature: base64 (raw 64-byte signature)
// Returns true iff the signature is valid for (publicKey, message).
extern def verifyEd25519(publicKey: String, message: String, signature: String): Boolean

// RSA (RSASSA-PKCS1-v1_5 and PSS) over SHA-256.
//   publicKey: base64 SPKI DER (X.509 SubjectPublicKeyInfo)
//   scheme:    "PKCS1" | "PSS"
extern def verifyRsaSha256(publicKey: String, message: String, signature: String, scheme: String): Boolean

// Convenience: verify base64url inputs (federation envelopes use base64url).
extern def verifyEd25519Url(publicKeyB64Url: String, message: String, signatureB64Url: String): Boolean
```

All verifiers are **total**: malformed key / signature / scheme returns `false`
(never throws), so a hostile peer cannot crash the verifier. A separate
classification helper may still report *why* it failed for audit, but the core
predicate is boolean.

## 3  Implementation plan

- JVM backend: `java.security.Signature` with `Ed25519` (JDK 15+) and
  `SHA256withRSA` / `SHA256withRSA/PSS`. Decode SPKI via `KeyFactory` +
  `X509EncodedKeySpec`; raw Ed25519 keys via `EdECPublicKeySpec` or a small
  SPKI-wrap helper.
- JS backend: WebCrypto `crypto.subtle.verify` (`Ed25519`, `RSASSA-PKCS1-v1_5`,
  `RSA-PSS`). Note WebCrypto is async — expose a sync-looking intrinsic backed by
  the runtime's existing extern bridging, or document the async lowering.
- Interpreter: route through the JVM implementation (same path as `sha256`).

## 4  Behavior checklist

- [x] `verifyEd25519` returns true for RFC 8032 test vectors #1 (empty) and #2.
- [x] Returns false for a tampered message / wrong key / truncated signature.
- [x] `verifyRsaSha256` true for a PKCS1 vector and a PSS vector (JCE keypair).
- [x] Malformed base64 / wrong-length key returns false, does not throw.
- [x] base64url variant decodes `-_` alphabet correctly.
- [x] Interpreter (via `crypto-plugin`) agrees with the JCE-signed vectors.

**Deviations:** implemented by extending the existing `crypto-plugin` (no new
module — JDK `java.security`, JVM only); JS WebCrypto deferred (busi's federation
path is server-side).

## 5  Verification

`PublicKeyVerifyTest` with RFC 8032 Ed25519 vectors and NIST RSA vectors, plus
negative cases. Re-point busi Phase 87g `PublicSignatureCheck` to call these and
remove the `signature.unsupported` quarantine for verifiable traffic.

## 6  busi context

busi marker `TODO(scalascript-signatures)` in the Phase 87g signature path.
Once shipped, busi can verify peer identity signatures end-to-end and lift the
quarantine.
