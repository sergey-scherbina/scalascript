# `std.crypto` — symmetric (AES) + public-key (RSA) encryption

**Status**: ✓ Landed 2026-06-10 (JVM/interpreter via `crypto-plugin`; JS WebCrypto deferred). Requested by busi (real testbed), 2026-06-09.
**Extends**: [`crypto.md`](crypto.md) (sha256 / hmac / base64) and complements
[`crypto-pubkey-verify.md`](crypto-pubkey-verify.md) (signature *verify*).
**Priority**: medium — blocks **real** (live-credential) KSeF 2.0 e-invoicing for
busi. Not needed for busi's CI/simulator path (which uses a stubbed
`EncryptionInfo`), so it gates only operator/live use, not day-to-day development.

## 1  Motivation

Poland's **KSeF 2.0** e-invoicing API (mandatory for large payers 2026-02-01,
others 2026-04-01) opens an interactive session with an `EncryptionInfo` object:

- a per-session **symmetric AES key** the client generates,
- that key **RSA-encrypted** with the KSeF server's `PublicKeyCertificate`,
- and invoice payloads **AES-encrypted** with that session key.

busi must do this from `.ssc` to send a real invoice. `std.crypto` today exposes
only hashing/HMAC/base64 — there is no symmetric or public-key **encryption**.
Reference: the official MF KSeF Java client
`https://github.com/CIRFMF/ksef-client-java` and busi's
`docs/external-apis/ksef2-migration-map.md`.

This capability is also generally useful (encrypting secrets at rest, hybrid
encryption for any "encrypt to a public key" protocol).

## 2  Proposed surface (`runtime/std/crypto.ssc`)

```scalascript
// --- Symmetric AES-256-GCM ---
// Generate a random 256-bit key, base64.
extern def aesGenKey(): String
// Encrypt: returns base64 of (iv ++ ciphertext ++ tag), or a documented framing.
extern def aesGcmEncrypt(keyB64: String, plaintext: String): String
extern def aesGcmDecrypt(keyB64: String, payloadB64: String): String
// Byte-oriented variants for binary payloads (invoice XML is UTF-8 text, but
// KSeF encrypts bytes): operate on base64-encoded input/output.
extern def aesGcmEncryptBytes(keyB64: String, plaintextB64: String): String
extern def aesGcmDecryptBytes(keyB64: String, payloadB64: String): String

// --- Symmetric AES-256-CBC (external IV, PKCS#7) ---
// KSeF 2.0 encrypts the invoice CONTENT with AES-256-CBC + PKCS#7 (NOT GCM), and
// carries the 16-byte IV in its own EncryptionInfo.initializationVector field.
// So the IV is a separate argument and the ciphertext is returned alone.
extern def aesGenIv(): String                                              // 16 random bytes, base64
extern def aesCbcEncrypt(keyB64: String, ivB64: String, plaintextB64: String): String
extern def aesCbcDecrypt(keyB64: String, ivB64: String, ciphertextB64: String): String

// --- RSA public-key encryption (RSA-OAEP, SHA-256) ---
//   publicKey: base64 SPKI DER (X.509 SubjectPublicKeyInfo), e.g. from a cert.
extern def rsaOaepEncrypt(publicKeyB64: String, plaintextB64: String): String
// Optional (decrypt only needed if busi ever holds a private key; KSeF flow does
// not require client-side RSA decrypt — keep this out of the first slice).

// --- X.509 cert → public key (KSeF returns a PublicKeyCertificate) ---
// Extract the SPKI public key (base64) from a PEM/DER X.509 certificate.
extern def x509PublicKey(certB64OrPem: String): String
```

Framing (IV length, tag placement) must be **documented** and match a widely
interoperable convention (e.g. 12-byte GCM IV prepended, 16-byte tag appended).
Where KSeF mandates a specific framing, busi adapts in `.ssc`; the externs expose
the primitive correctly.

## 3  Implementation plan

- JVM/interpreter: `javax.crypto.Cipher` — `AES/GCM/NoPadding` and
  `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`; `KeyGenerator`/`SecureRandom` for keys;
  `CertificateFactory` for X.509. Ship as a plugin `crypto-encrypt-plugin.sscpkg`
  (opt-in, parallels `crypto-plugin`) so the dependency isn't forced on programs
  that don't encrypt.
- JS backend: WebCrypto `subtle.encrypt` (`AES-GCM`, `RSA-OAEP`) + `importKey`.
  May lower async; document.
- Totality: malformed key/cert/payload returns a documented error (or throws a
  typed crypto error), never a silent wrong result.

## 4  Behavior checklist

- [x] `aesGcmDecrypt(k, aesGcmEncrypt(k, m)) == m` round-trips for ASCII + UTF-8.
- [x] A tampered ciphertext/tag fails decryption (GCM auth), not silent garbage.
- [x] `aesGenKey()` returns a fresh 256-bit key each call.
- [x] `aesGenIv()` returns a fresh 16-byte IV each call.
- [x] `aesCbcDecrypt(k, iv, aesCbcEncrypt(k, iv, m)) == m` round-trips binary
      payloads; ciphertext is deterministic for a fixed `(key, iv)`.
- [x] `aesCbcEncrypt` output decrypts with a direct JCE `AES/CBC/PKCS5Padding`
      cipher (PKCS#7 interop verified); a non-16-byte IV is rejected; a wrong-key
      decrypt never recovers the plaintext (padding error, not silent garbage).
- [x] `rsaOaepEncrypt` output decrypts with the matching private key (verified
      against a JCE-generated keypair in `CryptoEncryptTest`).
- [x] `x509PublicKey` extracts the SPKI key from an X.509 cert (openssl-generated
      test vector; SPKI matches `openssl pkey -pubin -outform DER | base64`).
- [x] Malformed inputs throw a clear error (`aesGcm*`/`rsaOaep*`/`x509*: <reason>`),
      surfaced as an interpreter error, not a JVM crash.

**Deviations from §2/§3:** (1) implemented by **extending the existing
`crypto-plugin`** rather than a new `crypto-encrypt-plugin` — `javax.crypto` is
JDK-builtin, so there is no external dependency to make opt-in, and the surface
already lives in `std.crypto`. (2) **JVM only** for this slice; JS WebCrypto
(async) deferred — busi's KSeF flow is server-side. (3) RSA-OAEP uses SHA-256 for
**both** the digest and MGF1; if KSeF mandates MGF1-SHA-1, that is a one-line
`OAEPParameterSpec` change — busi to confirm against `ksef-client-java`.
(4) **AES-256-CBC added 2026-06-10** (`aesGenIv`/`aesCbcEncrypt`/`aesCbcDecrypt`):
busi confirmed (rozum seq 66, vs official KSeF OpenAPI §5222) that KSeF 2.0
invoice **content** is AES-256-**CBC** + PKCS#7 with a separate 16-byte IV — the
GCM `iv12||ct||tag16` framing does not fit. CBC takes the IV as its own argument
and returns the ciphertext alone so it maps onto
`EncryptionInfo.initializationVector`. GCM variants stay for general-purpose
authenticated encryption.

## 5  Verification

`CryptoPluginTest` (in `crypto-plugin`): AES-GCM round-trip + tamper, AES-CBC
round-trip + JCE PKCS#7 interop + IV-length/wrong-key negatives, RSA-OAEP against
a known keypair, X.509 extraction, and negative cases. 41 tests, JVM/interpreter.

## 6  busi context

Unblocks busi `busi-ksef2-api-reconcile` **slice 2** (real `EncryptionInfo`):
generate an AES session key → `rsaOaepEncrypt` it with the KSeF
`x509PublicKey(cert)` → `aesGcmEncrypt` the FA(3) invoice. Slice 1 (path/flow
reconcile) ships with a stubbed `EncryptionInfo` and does **not** need this, so
this spec gates only the operator-gated phase85 live rehearsal.
