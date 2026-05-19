package scalascript.mcp.wallet

import scala.concurrent.Future
import scalascript.blockchain.spi.ChainId

/** What the host sees before approving a signing operation. The
 *  details map carries decoded fields per tool — e.g. for `payX402`
 *  it's `{amount, asset, payTo, chainId, validBefore}` — so the host
 *  UI can render a structured confirmation without re-parsing the
 *  raw payload. */
case class ElicitationRequest(
  tool:     String,
  chainId:  Option[ChainId],
  summary:  String,
  details:  Map[String, String] = Map.empty,
)

/** Host-supplied approval flow. Implementations:
 *
 *  - Desktop / mobile app: render a confirmation dialog, return
 *    Future[true] on OK / Future[false] on cancel.
 *
 *  - MCP host: send `elicitation/create` via mcp-common's notify
 *    channel, await the client's response.
 *
 *  - CLI: read approval from stdin (only for trusted automation).
 *
 *  - Tests: return Future.successful(...) immediately. */
trait ElicitationHandler:
  def confirm(request: ElicitationRequest): Future[Boolean]

object ElicitationHandler:
  /** Always approve — useful for stub Policy.Implicit deployments
   *  and tests. */
  def alwaysApprove: ElicitationHandler = new ElicitationHandler:
    def confirm(request: ElicitationRequest): Future[Boolean] = Future.successful(true)

  /** Always reject. */
  def alwaysReject: ElicitationHandler = new ElicitationHandler:
    def confirm(request: ElicitationRequest): Future[Boolean] = Future.successful(false)
