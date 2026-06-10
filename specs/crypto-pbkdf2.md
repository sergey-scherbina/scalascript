# `pbkdf2` + `secureRandomBytesB64` — standards-grade password hashing

**Status**: LANDED 2026-06-10. Requested by busi (auth-admin password hashing, rozum).

## 1  Motivation

busi needs to store hashed passwords for its auth-admin tool. Hand-rolling
salted-iterated HMAC-SHA256 over the existing `hmacSha256` extern (a) is not
standards-grade and (b) tripped a narrow cross-module String-return marshalling
bug (a `while`-loop accumulator returned across an import boundary came back
truncated). Single global externs — like `sha256` / `hmacSha256` — marshal
correctly everywhere and give the right primitive for the job.

## 2  Surface (`std.crypto`, JVM / interpreter)

```scalascript
// PBKDF2-HMAC-SHA256 (RFC 8018). saltB64 = Base64 salt; iterations = work factor
// (>= 100_000 recommended); dkLen = derived-key length in BYTES. Returns Base64.
// Deterministic — store (saltB64, iterations, result), re-derive to verify.
extern def pbkdf2(password: String, saltB64: String, iterations: Int, dkLen: Int): String

// n cryptographically secure random bytes (SecureRandom) as Base64 — e.g. a salt.
extern def secureRandomBytesB64(n: Int): String
```

Backed by `javax.crypto.SecretKeyFactory("PBKDF2WithHmacSHA256")` /
`java.security.SecureRandom`. The `PBEKeySpec` key length is in bits, so `dkLen`
(bytes) is `* 8` internally; the password char[] is cleared after derivation.

Typical use:

```scalascript
val salt   = secureRandomBytesB64(16)
val hash   = pbkdf2(plain, salt, 100000, 32)      // store (salt, 100000, hash)
val ok     = pbkdf2(attempt, salt, 100000, 32) == hash   // verify
```

## 3  Verify (`CryptoPluginTest`)

- [x] `pbkdf2` matches a direct `PBKDF2WithHmacSHA256` JCE reference (correct
  algorithm + `dkLen` interpreted as bytes).
- [x] Deterministic; salt- and iteration-sensitive; `dkLen` honoured.
- [x] Verify round-trip: correct password re-derives the stored key, wrong does not.
- [x] `secureRandomBytesB64(n)` decodes to `n` bytes, two draws differ, `0` → empty.
