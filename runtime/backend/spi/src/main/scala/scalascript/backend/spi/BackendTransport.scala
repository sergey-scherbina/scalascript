package scalascript.backend.spi

import scala.concurrent.Future

/** Logical transport used by full-stack frontend helpers to call backend
 *  routes. HTTP and in-process implementations should preserve the same
 *  request/response semantics even when no socket is opened. */
trait BackendTransport:
  def request(req: BackendRequest): Future[BackendResponse]

final case class BackendRequest(
    method:  String,
    path:    String,
    headers: Map[String, String] = Map.empty,
    body:    Array[Byte] = Array.emptyByteArray
)

final case class BackendResponse(
    status:  Int,
    headers: Map[String, String] = Map.empty,
    body:    Array[Byte] = Array.emptyByteArray
)

enum BackendTransportKind(val cliName: String):
  case Http      extends BackendTransportKind("http")
  case InProcess extends BackendTransportKind("in-process")

object BackendTransportKind:
  def parse(raw: String): Option[BackendTransportKind] =
    raw.trim.toLowerCase match
      case "http" | "rest" => Some(Http)
      case "in-process" | "inprocess" | "monolith" | "monolithic" => Some(InProcess)
      case _ => None
