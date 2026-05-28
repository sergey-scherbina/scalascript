package scalascript.gov

import java.time.Instant

enum SubmissionStatus:
  case Accepted
  case Pending(ticketId: String)
  case Processing
  case Rejected(errors: List[GovError])
  case RequiresCorrection(errors: List[GovError])

case class SubmissionResult(
  submissionId: String,
  status:       SubmissionStatus,
  timestamp:    Instant,
  reference:    Option[String]      = None,
  warnings:     List[GovWarning]   = Nil,
  metadata:     Map[String, String] = Map.empty
)

case class GovError(code: String, message: String, field: Option[String] = None)
case class GovWarning(code: String, message: String)
