package scalascript.payments.compliance

/** Base type for all compliance provider errors. */
sealed abstract class ComplianceError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object ComplianceError:

  /** The compliance check failed at the API level (HTTP error, timeout, parse error). */
  final case class CheckFailed(
      override val getMessage: String,
      override val getCause:   Throwable = null
  ) extends ComplianceError(getMessage, getCause)

  /** The entity was rejected by the compliance provider (sanctions hit, AML match). */
  final case class EntityRejected(entity: ComplianceEntity, reason: String)
      extends ComplianceError(s"Compliance check rejected entity '${entity.name}': $reason")

  /** The provider does not support this type of check for this entity/country. */
  final case class UnsupportedCheck(checkType: String, reason: String)
      extends ComplianceError(s"Compliance check '$checkType' not supported: $reason")

  /** Rate limit or quota exceeded. */
  final case class RateLimitExceeded(retryAfterSeconds: Option[Int] = None)
      extends ComplianceError(
        retryAfterSeconds.fold("Rate limit exceeded")(s => s"Rate limit exceeded; retry after ${s}s")
      )

  /** Generic provider error (auth, configuration, etc.). */
  final case class ProviderError(
      override val getMessage: String,
      override val getCause:   Throwable = null
  ) extends ComplianceError(getMessage, getCause)
