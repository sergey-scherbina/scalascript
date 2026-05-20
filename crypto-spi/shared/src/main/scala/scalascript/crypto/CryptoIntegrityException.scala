package scalascript.crypto

/** Cross-platform AEAD-tag-failure exception.
 *
 *  [[CryptoBackend.chacha20Poly1305Decrypt]] must wrap its underlying
 *  authentication-tag-mismatch exception in this type so callers can
 *  pattern-match without depending on a platform-specific exception
 *  (JVM: `javax.crypto.AEADBadTagException`; Scala.js / @noble:
 *  `Error("invalid tag")` etc.).
 *
 *  The Stage 5 `aesGcmDecrypt` predates this exception type and keeps
 *  rethrowing the underlying platform exception (`AEADBadTagException`
 *  on JVM, noble's `Error` on JS); existing callers / tests use
 *  `intercept[Exception]` for that path.  Future SPI-additions should
 *  wrap in [[CryptoIntegrityException]] for parity with chacha.
 *
 *  Lives in `crypto-spi/shared/` so both `crypto-bouncycastle` and
 *  `crypto-noble-js` can rethrow it. */
final class CryptoIntegrityException(message: String, cause: Throwable)
  extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)
