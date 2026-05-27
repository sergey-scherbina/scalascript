package scalascript.payments.tax

/** Base type for all tax-provider errors. */
sealed abstract class TaxError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object TaxError:

  /** The tax calculation failed (API error, bad request, etc.). */
  final case class TaxCalculationFailed(
      override val getMessage: String,
      override val getCause:   Throwable = null
  ) extends TaxError(getMessage, getCause)

  /** Tax ID validation request failed at the provider level. */
  final case class TaxIdValidationFailed(
      taxId:                   String,
      country:                 String,
      override val getMessage: String
  ) extends TaxError(getMessage)

  /** The provider does not support the requested jurisdiction. */
  final case class UnsupportedJurisdiction(jurisdiction: String)
      extends TaxError(s"Tax provider does not support jurisdiction: $jurisdiction")

  /** Generic provider-level error (network failure, auth, parse error, etc.). */
  final case class TaxProviderError(
      override val getMessage: String,
      override val getCause:   Throwable = null
  ) extends TaxError(getMessage, getCause)
