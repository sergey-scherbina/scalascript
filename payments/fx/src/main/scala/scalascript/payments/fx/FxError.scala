package scalascript.payments.fx

import scalascript.payments.money.Currency

/** Base type for all FX-provider errors. */
sealed abstract class FxError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object FxError:

  /** The requested currency pair is not available from this provider.
   *
   *  @param from  source currency that was requested
   *  @param to    target currency that was requested
   */
  final case class RateUnavailable(from: Currency, to: Currency)
      extends FxError(s"FX rate unavailable for ${from.code}/${to.code}")

  /** A provider-level error (network failure, API error, parse error, etc.).
   *
   *  @param message human-readable description
   *  @param cause   underlying exception, if any
   */
  final case class FxProviderError(override val getMessage: String, override val getCause: Throwable = null)
      extends FxError(getMessage, getCause)
