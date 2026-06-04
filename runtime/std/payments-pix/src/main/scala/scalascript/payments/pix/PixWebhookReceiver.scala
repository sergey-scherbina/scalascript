package scalascript.payments.pix

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant

/** Pix webhook receiver.
 *
 *  BCB Pix webhook delivery uses mTLS on the PSP → merchant callback channel.
 *  For aggregator APIs (Sicoob, Itaú, Gerencianet/EFÍ, etc.) the transport is
 *  HTTPS with HMAC-SHA256 signing over the raw body.
 *
 *  Verification: header `X-Pix-Signature: sha256=<hex>` (HMAC-SHA256 over raw body).
 *  The signing secret is the `pixClientSecret` from PixConfig.
 *
 *  Supported event types (JSON `"evento"` field or `pix[*]` array presence):
 *    pix.received   — credit confirmed by DICT (normal Pix receipt)
 *    pix.refunded   — devolution (devolução) processed
 *    pix.rejected   — DICT error or timeout (codes ED05/AB09/AGNT)
 *
 *  Payload shape (BCB standard — aggregators may vary slightly):
 *  {{{
 *    {
 *      "pix": [
 *        {
 *          "endToEndId":  "E...",   // e2e ID assigned by DICT
 *          "txid":        "...",    // merchant txid
 *          "valor":       "10.00",  // amount in BRL
 *          "horario":     "2026-05-27T12:34:56Z",
 *          "infoPagador": "optional payer note"
 *        }
 *      ]
 *    }
 *  }}}
 *
 *  Refund payload adds `devolucoes` array inside each pix entry; rejected payload
 *  has a top-level `"evento"` = `"pix.rejected"` and a `"codigoErro"` field.
 *
 *  See docs/specs/bank-rails.md §7.3 and §8 v1.54.3.
 */
class PixWebhookReceiver(
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-Pix-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
                      .orElse(req.headers.get("x-pix-signature"))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected signature format in $SignatureHeader: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256Hex(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature(s"$SignatureHeader HMAC-SHA256 mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"Pix event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.PixReceived(t)         => s"pix.received.${t.id.value}"
    case BankRailsEvent.PixRefunded(t, orig)   => s"pix.refunded.${t.id.value}.${orig.value}"
    case BankRailsEvent.PixRejected(t, _)      => s"pix.rejected.${t.id.value}"
    case other                                 => s"pix.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    // Determine event type from "evento" field or structure
    val eventoOpt = extractJsonString(body, "evento")

    // Check for pix array (normal received / refunded)
    val pixArray = extractJsonArray(body, "pix")

    eventoOpt match
      case Some("pix.rejected") =>
        val e2eId  = extractJsonString(body, "endToEndId").getOrElse("unknown")
        val code   = extractJsonString(body, "codigoErro").getOrElse("ED05")
        val desc   = extractJsonString(body, "descricao").getOrElse("")
        val transfer = makeMinimalTransfer(e2eId, "0", RailKind.PIX,
          BankTransferStatus.Rejected(RejectCode(code), desc))
        BankRailsEvent.PixRejected(transfer, RejectCode(code))

      case Some("pix.refunded") =>
        val e2eId     = extractJsonString(body, "endToEndId").getOrElse("unknown")
        val valorStr  = extractJsonString(body, "valor").getOrElse("0")
        val originalId = extractJsonString(body, "originalEndToEndId")
                           .getOrElse(e2eId)
        val transfer = makeMinimalTransfer(e2eId, valorStr, RailKind.PIX, BankTransferStatus.Settled)
        BankRailsEvent.PixRefunded(transfer, TransferId(originalId))

      case _ =>
        // Normal pix.received — pix[] array present
        if pixArray.nonEmpty then
          // Parse first entry in pix array
          val entry    = pixArray.head
          val e2eId    = extractJsonString(entry, "endToEndId").getOrElse("unknown")
          val valorStr = extractJsonString(entry, "valor").getOrElse("0")

          // Check for devolucoes (refund) inside pix entry
          val hasDevolucao = entry.contains("\"devolucoes\"")
          if hasDevolucao then
            val originalId = e2eId
            val transfer = makeMinimalTransfer(e2eId, valorStr, RailKind.PIX, BankTransferStatus.Settled)
            BankRailsEvent.PixRefunded(transfer, TransferId(originalId))
          else
            val transfer = makeMinimalTransfer(e2eId, valorStr, RailKind.PIX, BankTransferStatus.Settled)
            BankRailsEvent.PixReceived(transfer)
        else
          // Fall back to direct top-level fields
          val e2eId    = extractJsonString(body, "endToEndId").getOrElse("unknown")
          val valorStr = extractJsonString(body, "valor").getOrElse("0")
          val transfer = makeMinimalTransfer(e2eId, valorStr, RailKind.PIX, BankTransferStatus.Settled)
          BankRailsEvent.PixReceived(transfer)

  private def makeMinimalTransfer(
      e2eId:    String,
      valorStr: String,
      rail:     RailKind,
      status:   BankTransferStatus,
  ): BankTransfer =
    val amount = parseMoneyFromDecimal(valorStr, "BRL")
    val dummy  = BankAccount(holderName = "", countryCode = "BR")
    BankTransfer(
      id        = TransferId(e2eId),
      rail      = rail,
      amount    = amount,
      sender    = dummy,
      recipient = dummy,
      reference = e2eId,
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoneyFromDecimal(decimalStr: String, currencyCode: String): Money =
    val ccy   = Currency(currencyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = scala.util.Try(BigDecimal(decimalStr)).getOrElse(BigDecimal(0))
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

  // ── Minimal JSON helpers (no external dependency) ─────────────────────────

  /** Extract a JSON string field value from a flat JSON object. */
  private[pix] def extractJsonString(json: String, key: String): Option[String] =
    val pattern = s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))

  /** Extract a JSON array as list of raw object strings (simplified parser). */
  private def extractJsonArray(json: String, key: String): List[String] =
    // Find the start of the array for `key`
    val arrayStart = s""""$key"\\s*:\\s*\\[""".r
    arrayStart.findFirstMatchIn(json) match
      case None => List.empty
      case Some(m) =>
        val rest    = json.substring(m.end)
        val objects = extractJsonObjects(rest)
        objects

  /** Extract top-level JSON objects from a string starting after the '['. */
  private def extractJsonObjects(str: String): List[String] =
    val results = List.newBuilder[String]
    var depth   = 0
    var start   = -1
    var i       = 0
    while i < str.length do
      str.charAt(i) match
        case '{' =>
          if depth == 0 then start = i
          depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 && start >= 0 then
            results += str.substring(start, i + 1)
            start = -1
        case ']' if depth == 0 =>
          i = str.length // stop
        case _ => ()
      i += 1
    results.result()

  // ── Crypto helpers ─────────────────────────────────────────────────────────

  /** HMAC-SHA256 over the payload bytes, returns lowercase hex string. */
  private[pix] def hmacSha256Hex(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0
