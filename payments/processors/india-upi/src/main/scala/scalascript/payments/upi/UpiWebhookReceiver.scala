package scalascript.payments.upi

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.*
import java.security.{KeyFactory, PublicKey, Signature}
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** UPI webhook receiver: verifies `X-UPI-Signature` header (RSA-SHA256 over raw body)
 *  using the NPCI / aggregator public key delivered at on-boarding, then parses the
 *  JSON payload into typed `BankRailsEvent` values.
 *
 *  Supported event types (JSON `"event"` field):
 *    upi.payment.success  — UpiApproved   (payer approved; UTR in `utrNumber` field)
 *    upi.payment.failed   — UpiDeclined   (payer declined or transaction failed)
 *    upi.collect.expired  — UpiDeclined   (collect request expired without payer response)
 *    upi.collect.initiated — UpiCollectInitiated (collect request created; payer has not responded yet)
 *
 *  Webhook auth: RSA-SHA256 signature over raw HTTP body (Base64-encoded).
 *  Header: `X-UPI-Signature: <base64-encoded-signature>`.
 *
 *  When publicKeyPem is empty (e.g. in tests with no key configured), signature
 *  verification is skipped — callers should only do this in test environments.
 *
 *  See specs/international-bank-rails.md section 7 (India UPI).
 */
class UpiWebhookReceiver(
    publicKeyPem:          String         = "",
    override val config:   WebhookConfig  = WebhookConfig(),
    override val seenKeys: SeenKeyStore   = InMemorySeenKeyStore(),
) extends WebhookReceiver[BankRailsEvent]:

  val SignatureHeader = "X-UPI-Signature"

  def verify(req: WebhookRequest, signingSecret: String): Either[WebhookError, BankRailsEvent] =
    // If a public key is configured, verify RSA-SHA256 signature
    if publicKeyPem.nonEmpty then
      val headerOpt = req.headers.get(SignatureHeader)
                        .orElse(req.headers.get(SignatureHeader.toLowerCase))
      if headerOpt.isEmpty then return Left(MissingHeader(SignatureHeader))

      val sigB64  = headerOpt.get
      val valid   = scala.util.Try {
        val pubKey    = loadPublicKey(publicKeyPem)
        val sigBytes  = Base64.getDecoder.decode(sigB64)
        val verifier  = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pubKey)
        verifier.update(req.rawBody.getBytes("UTF-8"))
        verifier.verify(sigBytes)
      }.getOrElse(false)

      if !valid then return Left(InvalidSignature(s"$SignatureHeader RSA-SHA256 signature mismatch"))

    scala.util.Try(parseEvent(req.rawBody)) match
      case scala.util.Success(event) => Right(event)
      case scala.util.Failure(e)     => Left(MalformedPayload(s"UPI event parse error: ${e.getMessage}"))

  def idempotencyKey(event: BankRailsEvent): String = event match
    case BankRailsEvent.UpiCollectInitiated(txnId, _, _) => s"upi.collect.initiated.$txnId"
    case BankRailsEvent.UpiApproved(txnId, utr)          => s"upi.approved.$txnId.$utr"
    case BankRailsEvent.UpiDeclined(txnId, _)            => s"upi.declined.$txnId"
    case other                                           => s"upi.event.${other.getClass.getSimpleName}"

  // ── JSON event parsing ─────────────────────────────────────────────────────

  private[upi] def parseEvent(body: String): BankRailsEvent =
    val eventType = extractJsonString(body, "event").orElse(extractJsonString(body, "type")).getOrElse(
      throw new IllegalArgumentException("Missing 'event' or 'type' field in UPI webhook payload")
    )
    eventType match
      case "upi.payment.success" =>
        val txnId     = extractJsonString(body, "txnId").getOrElse("unknown")
        val utrNumber = extractJsonString(body, "utrNumber")
                          .orElse(extractJsonString(body, "utr"))
                          .getOrElse("unknown")
        BankRailsEvent.UpiApproved(txnId, utrNumber)

      case "upi.payment.failed" =>
        val txnId  = extractJsonString(body, "txnId").getOrElse("unknown")
        val reason = extractJsonString(body, "reason")
                       .orElse(extractJsonString(body, "errorDescription"))
                       .getOrElse("payment failed")
        BankRailsEvent.UpiDeclined(txnId, reason)

      case "upi.collect.expired" =>
        val txnId = extractJsonString(body, "txnId").getOrElse("unknown")
        BankRailsEvent.UpiDeclined(txnId, "collect request expired — payer did not respond in time")

      case "upi.collect.initiated" =>
        val txnId  = extractJsonString(body, "txnId").getOrElse("unknown")
        val vpa    = extractJsonString(body, "payerVpa")
                       .orElse(extractJsonString(body, "vpa"))
                       .getOrElse("unknown@upi")
        val amount = extractJsonString(body, "amount").getOrElse("0")
        BankRailsEvent.UpiCollectInitiated(txnId, vpa, amount)

      case other =>
        throw new IllegalArgumentException(s"Unknown UPI event type: $other")

  // ── RSA public key loading ─────────────────────────────────────────────────

  private[upi] def loadPublicKey(pem: String): PublicKey =
    val pemContent = pem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replace("-----BEGIN RSA PUBLIC KEY-----", "")
      .replace("-----END RSA PUBLIC KEY-----", "")
      .replaceAll("\\s+", "")
    val keyBytes   = Base64.getDecoder.decode(pemContent)
    val keySpec    = X509EncodedKeySpec(keyBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(keySpec)

  // ── Minimal JSON field extraction ──────────────────────────────────────────

  private[upi] def extractJsonString(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))
