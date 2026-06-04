package scalascript.payments.ukbacs

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** BACS webhook receiver.
 *
 *  Verification: HMAC-SHA256 over raw body.
 *  Signature header: `X-BACS-Signature: sha256=<hex>`.
 *  Uses the same raw-body HMAC-SHA256 convention as ACH, FPS, and other adapters.
 *
 *  Supported event types (JSON `"type"` field):
 *
 *    bacs.directdebit.submitted   → BacsDdSubmitted
 *      JSON: { "type": "...", "ref": "...", "settlement_date": "..." }
 *
 *    bacs.directdebit.collected   → BacsDdPaid
 *      JSON: { "type": "...", "ref": "...", "amount": "..." }
 *
 *    bacs.auddis.accepted         → BacsAuddisAccepted
 *      JSON: { "type": "...", "mandate_ref": "..." }
 *
 *    bacs.directdebit.returned    → BacsAruddReturned
 *      JSON: { "type": "...", "ref": "...", "code": "...", "description": "..." }
 *      The "code" field carries the ARUDD/ADDACS reason code (0, 1, 2, 3, 5, 6, B, C, F, G, H).
 *
 *  ARUDD return codes (from docs/specs/international-bank-rails.md §v1.55.4):
 *    0  — Instruction cancelled — refer to payer
 *    1  — Instruction cancelled — new instruction due
 *    2  — Payer deceased
 *    3  — Account transferred to new bank / branch
 *    5  — No account, wrong account type
 *    6  — No instruction
 *    B  — Account closed
 *    C  — Funds insufficient
 *    F  — Invalid reference
 *    G  — Bank account closed on customer instructions
 *    H  — Institution refused to accept direct debit
 *
 *  See docs/specs/international-bank-rails.md §7 (BACS DD) for full webhook taxonomy.
 */
class UkBacsWebhookReceiver(
    override val config:   WebhookConfig = WebhookConfig(),
    override val seenKeys: SeenKeyStore  = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-BACS-Signature"

  def verify(req: WebhookRequest, secret: String): Either[WebhookError, BankRailsEvent] =
    val headerOpt = req.headers.get(SignatureHeader)
                      .orElse(req.headers.get(SignatureHeader.toLowerCase))
    if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

    val header = headerOpt.get
    if !header.startsWith("sha256=") then
      return Left(InvalidSignature(s"Unexpected BACS signature format: $header"))

    val providedSig = header.drop("sha256=".length)
    val expectedSig = hmacSha256Hex(secret, req.rawBody)
    if !constantTimeEquals(expectedSig, providedSig) then
      return Left(InvalidSignature("X-BACS-Signature HMAC-SHA256 mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"BACS event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.BacsDdSubmitted(ref, _)           => s"bacs.submitted.$ref"
    case BankRailsEvent.BacsDdPaid(ref, _)                => s"bacs.paid.$ref"
    case BankRailsEvent.BacsAuddisAccepted(mandateRef)    => s"bacs.auddis.$mandateRef"
    case BankRailsEvent.BacsAruddReturned(ref, code, _)   => s"bacs.returned.$ref.$code"
    case other                                             => s"bacs.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ──────────────────────────────────────────────────

  private def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "type").getOrElse(
      throw new IllegalArgumentException("Missing 'type' field in BACS webhook event")
    )
    eventType match
      case "bacs.directdebit.submitted" =>
        val ref            = extractJsonString(body, "ref").getOrElse("unknown")
        val settlementDate = extractJsonString(body, "settlement_date").getOrElse("")
        BankRailsEvent.BacsDdSubmitted(ref, settlementDate)

      case "bacs.directdebit.collected" =>
        val ref    = extractJsonString(body, "ref").getOrElse("unknown")
        val amount = extractJsonString(body, "amount").getOrElse("0")
        BankRailsEvent.BacsDdPaid(ref, amount)

      case "bacs.auddis.accepted" =>
        val mandateRef = extractJsonString(body, "mandate_ref").getOrElse("unknown")
        BankRailsEvent.BacsAuddisAccepted(mandateRef)

      case "bacs.directdebit.returned" =>
        val ref         = extractJsonString(body, "ref").getOrElse("unknown")
        val code        = extractJsonString(body, "code").getOrElse("0")
        val description = extractJsonString(body, "description")
                            .getOrElse(AruddCode.description(code))
        BankRailsEvent.BacsAruddReturned(ref, code, description)

      case other =>
        throw new IllegalArgumentException(s"Unknown BACS event type: $other")

  // ── Minimal JSON field extraction ────────────────────────────────────────

  private[ukbacs] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  // ── Crypto helpers ───────────────────────────────────────────────────────

  private[ukbacs] def hmacSha256Hex(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then return false
    var diff = 0
    for i <- a.indices do diff |= (a.charAt(i) ^ b.charAt(i))
    diff == 0

/** ARUDD/ADDACS return reason codes.
 *  See docs/specs/international-bank-rails.md §v1.55.4 for the full table.
 */
object AruddCode:
  /** Return a human-readable description for a given ARUDD reason code. */
  def description(code: String): String = code match
    case "0" => "Instruction cancelled — refer to payer"
    case "1" => "Instruction cancelled — new instruction due"
    case "2" => "Payer deceased"
    case "3" => "Account transferred to new bank or branch"
    case "5" => "No account or wrong account type"
    case "6" => "No instruction"
    case "B" => "Account closed"
    case "C" => "Funds insufficient"
    case "F" => "Invalid reference"
    case "G" => "Bank account closed on customer instructions"
    case "H" => "Institution refused to accept direct debit"
    case other => s"ARUDD return code $other"
