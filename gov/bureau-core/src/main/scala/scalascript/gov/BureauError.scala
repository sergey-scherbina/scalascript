package scalascript.gov

sealed abstract class BureauError(message: String, cause: Throwable = null)
  extends Exception(message, cause)

object BureauError:

  case class ApiError(
    message:    String,
    code:       Option[String] = None,
    httpStatus: Option[Int]    = None
  ) extends BureauError(message)

  case class AuthenticationError(message: String)
    extends BureauError(message)

  case class SignatureError(message: String, override val getCause: Throwable = null)
    extends BureauError(message, getCause)

  case class ValidationError(message: String, fields: List[GovError] = Nil)
    extends BureauError(message)

  case class MissingTaxId(idType: TaxIdType, country: CountryCode)
    extends BureauError(s"$country entity missing required tax ID: $idType")

  case class UnsupportedOperation(country: CountryCode, domain: GovDomain, op: String)
    extends BureauError(s"$country/$domain: '$op' not supported")

  case class RateLimitError(retryAfterSeconds: Option[Int] = None)
    extends BureauError("rate limit exceeded")

  case class ServiceUnavailable(message: String)
    extends BureauError(message)

  case class SubmissionRejected(result: SubmissionResult)
    extends BureauError(s"submission ${result.submissionId} rejected")
