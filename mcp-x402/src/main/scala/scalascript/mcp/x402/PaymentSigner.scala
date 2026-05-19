package scalascript.mcp.x402

import scala.concurrent.Future
import scalascript.x402.PaymentRequirements

/** Signs an x402 payment for the auto-pay middleware. Implementations:
 *
 *  - Local wallet: wraps `x402-client.Wallet` directly — used when
 *    the client process holds the signing key.
 *
 *  - MCP-wallet relay: calls the remote `mcp-wallet-server`'s
 *    `wallet.payX402` tool over a separate MCP connection — used
 *    when the wallet lives in another process (the typical PWA /
 *    embedded-wallet setup). */
trait PaymentSigner:
  /** Build the base64-encoded `_meta.x402.payment` header for a
   *  given requirements payload. Returns Future[None] on rejection
   *  (user declined / over budget / etc.) — the caller treats this
   *  the same as any other auto-pay refusal. */
  def signRequirements(req: PaymentRequirements): Future[Option[String]]
