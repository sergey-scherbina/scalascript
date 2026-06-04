package scalascript.payments.swift

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SWIFT webhook receiver: verifies `X-SWIFT-Signature: sha256=<hex>` header
 *  (HMAC-SHA256 over raw body) and parses the payload into typed BankRailsEvent values.
 *
 *  Supported event types (JSON "event" field):
 *    gpi.v4.credits.ValueDateChanged   → SwiftGpiAdvanced
 *    gpi.v4.credits.Completed          → SwiftMt103Booked / SwiftPacs008Settled
 *    gpi.v4.credits.CancellationCompleted → SwiftRejected
 *    gpi.v4.credits.Rejected           → SwiftRejected
 *
 *  See docs/specs/international-bank-rails.md §7 and §8 v1.55.1.
 */
class SwiftWebhookReceiver(
    railKind:            RailKind        = RailKind.SWIFT_PACS008,
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-SWIFT-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected signature format in $SignatureHeader: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature(s"$SignatureHeader HMAC-SHA256 mismatch"))

    GpiTracker.parseEvent(req.rawBody, railKind) match
      case Right(event) => Right(event)
      case Left(err)    => Left(MalformedPayload(s"GPI event parse error: $err"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.SwiftMt103Booked(uetr, _, _)      => s"swift.mt103.booked.$uetr"
    case BankRailsEvent.SwiftPacs008Settled(uetr, _, _)   => s"swift.pacs008.settled.$uetr"
    case BankRailsEvent.SwiftGpiAdvanced(uetr, hop)        => s"swift.gpi.advanced.$uetr.${hop.agentBic}.${hop.updatedAt.toEpochMilli}"
    case BankRailsEvent.SwiftRejected(uetr, code, _)       => s"swift.rejected.$uetr.$code"
    case other                                              => s"swift.event.${other.getClass.getSimpleName}"

  // ── Crypto helpers ──────────────────────────────────────────────────────────

  private[swift] def hmacSha256(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
