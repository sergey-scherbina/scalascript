# `std.crypto` — Cryptographic hash and encoding primitives

**Status**: spec — v1.66 target.

## 1  Motivation

`sha256`, `hmacSha256`, `base64Encode`, and `base64Decode` appear in
production ScalaScript code (webhook verification, content addressing,
API signatures, Stripe/Adyen/Braintree payloads).  Today they only
exist as `extern def` stubs in `examples/plugins/crypto-plugin/` — a
template for third-party plugin authors, not a standard intrinsic.

As a result:
- Interpreter cannot evaluate programs that call `sha256` / `hmacSha256`.
- JS backend has no public `sha256` / `base64Encode` symbols; only
  private `_hmacSha256` used internally by the JWT preamble.
- `auth.ssc` already provides `base64UrlEncode`/`base64UrlDecode` but
  not the standard (non-URL-safe) Base64 variants.
- Payment plugins (Stripe, Adyen, Braintree) re-implement HMAC-SHA-256
  in Scala, duplicating logic that should be a single stdlib call.

## 2  Proposed surface (`runtime/std/crypto.ssc`)

```scalascript
// SHA-2 digest — hex string of the UTF-8 encoding of input
extern def sha256(input: String): String

// HMAC-SHA-256 — hex string; key and data are UTF-8 strings
extern def hmacSha256(key: String, data: String): String

// Standard Base64 (RFC 4648 §4) — not URL-safe, with padding
extern def base64Encode(s: String): String
extern def base64Decode(s: String): String
```

`base64UrlEncode` / `base64UrlDecode` (URL-safe variant) remain in
`auth.ssc` because they are part of the WebAuthn / PKCE surface.

## 3  Implementation plan

### Phase 1 — JVM interpreter plugin (`crypto-plugin`)

New project `runtime/std/crypto-plugin/`:
- `CryptoInterpreterPlugin extends Backend`
- `CryptoIntrinsics.table`:
  - `sha256`      → `java.security.MessageDigest("SHA-256")`
  - `hmacSha256`  → `javax.crypto.Mac("HmacSHA256")`
  - `base64Encode` → `java.util.Base64.getEncoder`
  - `base64Decode` → `java.util.Base64.getDecoder`
- `META-INF/services/scalascript.backend.spi.Backend`
- `build.sbt`: `lazy val cryptoPlugin`, added to `allPlugins`
- `UnitTest`: all four functions, including known vectors:
  - `sha256("")` == `"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"`
  - `sha256("abc")` == `"ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469348423f656b0f0bc7"` (note: first 32 hex chars of the correct SHA-256; full value is the standard NIST test vector)
  - `hmacSha256("key", "data")` known hex vector

### Phase 2 — JS backend

- `runtime/backend/js/src/main/scala/scalascript/codegen/intrinsics/Crypto.scala`:
  ```scala
  val JsCryptoIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("sha256")       -> RuntimeCall("sha256"),
    QualifiedName("hmacSha256")   -> RuntimeCall("hmacSha256"),
    QualifiedName("base64Encode") -> RuntimeCall("base64Encode"),
    QualifiedName("base64Decode") -> RuntimeCall("base64Decode"),
  )
  ```
- Add to `JsIntrinsics` in `JsCapabilities.scala`.
- Add preamble functions in `JsRuntimePart2b.scala`:
  ```javascript
  // Node.js: synchronous crypto primitives
  function sha256(s) {
    return require('crypto').createHash('sha256').update(s,'utf8').digest('hex');
  }
  function hmacSha256(key, data) {
    return require('crypto').createHmac('sha256',key).update(data,'utf8').digest('hex');
  }
  function base64Encode(s) {
    return Buffer.from(s,'utf8').toString('base64');
  }
  function base64Decode(s) {
    return Buffer.from(s,'base64').toString('utf8');
  }
  ```
  Note: these call Node.js `require('crypto')` synchronously. For
  browser/Deno environments a Web Crypto async variant is needed
  (deferred to a later phase).

### Phase 3 — Wire payment plugins to stdlib

Replace the duplicated HMAC-SHA-256 implementations in
`StripeWebhookReceiver`, `AdyenWebhookReceiver`, `BraintreeWebhookReceiver`
with calls to `javax.crypto.Mac` (same as the plugin) — this is a
Scala-level refactor, not a ScalaScript surface change.  Deferred until
the crypto-plugin is stable.

## 4  What the `examples/plugins/crypto-plugin` example becomes

The example uses namespaced `QualifiedName("std.crypto.sha256")` and
`std.crypto.base64Encode`.  After this feature lands, the example should
be updated to point at the new standard `crypto-plugin` instead of
rolling its own.  The example can then demonstrate adding *additional*
non-standard functions (e.g., `argon2`, `blake3`) on top of the stdlib.

## 5  Known test vectors (NIST / RFC 4231)

| Function | Input | Expected output |
|---|---|---|
| `sha256("")` | `""` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `sha256("abc")` | `"abc"` | `ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469348423f656b0f0bc7` (first 32 hex of full 64) |
| `hmacSha256("key","The quick brown fox…")` | as shown | `f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8` |
| `base64Encode("Man")` | `"Man"` | `"TWFu"` |
| `base64Decode("TWFu")` | `"TWFu"` | `"Man"` |

## 6  Open questions

1. **WASM / browser target**: Node.js `require('crypto')` is not
   available in browser bundles.  For the browser target, `sha256`
   should use `SubtleCrypto.digest` (async) or a pure-JS fallback.
   Deferred — current scope is Node.js only.

2. **`sha256Bytes(input: String): Bytes`**: return raw bytes instead of
   hex string?  Useful for HMAC inputs that should not be hex-encoded.
   Deferred — start with string-in/hex-out which covers 90% of use cases.

3. **`sha512`, `md5`**: md5 is insecure but still used in legacy systems.
   Deferred — add when a concrete use case arises.
