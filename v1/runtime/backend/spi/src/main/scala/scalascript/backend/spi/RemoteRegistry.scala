package scalascript.backend.spi

/** Metadata for a named remotely callable ScalaScript handler.
 *
 *  `name` is the stable operation id (`users.get`), while `function` is the
 *  local ScalaScript definition that implements it.  `path` is an optional
 *  transport route used by HTTP/JSON fallback; it is not part of the stable
 *  operation identity.
 */
final case class RemoteHandlerInfo(
    name:         String,
    function:     String,
    path:         Option[String] = None,
    requestType:  Option[String] = None,
    responseType: Option[String] = None,
    transports:   Set[String] = Set("in-process")
)

enum RemoteCallError:
  case Unavailable(node: String)
  case Timeout(operation: String, durationMs: Long)
  case Decode(message: String)
  case HandlerNotFound(name: String)
  case CodeMismatch(localHash: String, remoteHash: String)
  case Unauthorized
  case Cancelled
  case RemoteFailed(code: String, message: String)
  case NetworkError(message: String)

trait RemoteHandlerRegistry:
  def describe(): List[RemoteHandlerInfo]
  def invoke(name: String, payload: Any): Either[RemoteCallError, Any]
