package scalascript.payments.upi

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.security.{KeyFactory, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.{Duration, Instant}
import java.util.Base64

/** India UPI (Unified Payments Interface) BankRailsProvider adapter.
 *
 *  Communicates with an NPCI-licensed aggregator (Razorpay, PayU, JusPay, Cashfree,
 *  PhonePe Business API) via REST JSON over HTTPS.
 *
 *  Two flows:
 *  - Push (UPI Pay):    `initiateTransfer`     — payer initiates; returns Pending;
 *                       UpiApproved arrives via webhook with UTR number.
 *  - Pull (UPI Collect): `initiateDirectDebit` — payee sends a collect request to
 *                        payer's VPA; payer approves on their UPI app;
 *                        UpiCollectInitiated event fires immediately on collect request creation;
 *                        UpiApproved or UpiDeclined arrives when payer responds.
 *
 *  Auth: API key (X-Api-Key header) + optional RSA-SHA256 request signing using merchant
 *  private key (PKCS#8 PEM format, required by Razorpay / JusPay).  Signing is enabled
 *  when `UpiConfig.merchantPrivateKeyPem` is non-empty.
 *
 *  Wire fields:
 *    txnId       = idempotencyKey (max 50 chars)
 *    amount      = paise (integer × 100 of INR major units)
 *    payeeVpa    = BankAccount.upiVpa of creditorAccount
 *    payerVpa    = BankAccount.upiVpa of debtorAccount (Collect only)
 *    remarks     = reference (max 50 chars)
 *    purpose     = "00" (merchant payment) or "14" (subscription)
 *    callbackUrl = UpiConfig.callbackUrl (optional, required for collect notifications)
 *
 *  UPI transaction limits: Rs.2,00,000 per transaction by default (Rs.5,00,000 with enhanced KYC).
 *  Aggregator enforces limits; the adapter will receive an HTTP 4xx error if exceeded.
 *
 *  Settlement: T+0 instant, 24x7x365.  See specs/international-bank-rails.md section 5.
 *
 *  AML/KYC note: RBI regulations require full KYC for merchant on-boarding.
 *  A future ComplianceProvider SPI will handle per-transaction checks.
 *
 *  See specs/international-bank-rails.md section v1.55.6.
 */
class UpiProvider(config: UpiConfig) extends BankRailsProvider:

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String        = "india-upi"
  def displayName:    String        = "India UPI (Unified Payments Interface) — NPCI aggregator"
  def spiVersion:     String        = "1.55.6"
  def supportedRails: Set[RailKind] = Set(RailKind.IN_UPI)

  // ── Push: UPI Pay (payer-initiated) ──────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    val payeeVpa = req.recipient.upiVpa.getOrElse(
      throw new IllegalArgumentException("BankAccount.upiVpa is required for IN_UPI transfers (set on recipient)")
    )

    validateVpa(payeeVpa)

    val amountPaise = req.amount.minorUnits
    val txnId       = req.idempotencyKey.take(50)
    val remarks     = req.reference.take(50)

    val json = buildPayRequest(
      txnId       = txnId,
      amountPaise = amountPaise,
      payeeVpa    = payeeVpa,
      payerVpa    = req.sender.upiVpa,
      remarks     = remarks,
      purpose     = config.defaultPurposeCode,
    )

    val respBody   = postJson("/upi/pay", json)
    val transferId = extractField(respBody, "txnId")
                       .orElse(extractField(respBody, "id"))
                       .getOrElse(txnId)

    BankTransfer(
      id        = TransferId(transferId),
      rail      = req.rail,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )

  def getTransfer(id: TransferId): BankTransfer =
    val body = getJson(s"/upi/transactions/${id.value}")
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    // UPI does not support cancellation after initiation;
    // the transaction will either be approved, declined, or expire automatically
    throw BankRailsCancelError(id, "UPI transactions cannot be cancelled after initiation — transaction expires automatically if payer does not respond")

  // ── Pull: UPI Collect (payee-initiated collect request) ──────────────────

  /** Initiates a UPI Collect request: the creditor requests payment from the debtor's VPA.
   *  The debtor (payer) receives a notification on their UPI app and must approve by
   *  entering their UPI PIN on their registered mobile device.
   *
   *  NOTE: The UPI PIN is entered on the payer's registered device and is never visible
   *  to the payee or the merchant — this is UPI's mandatory two-factor security mechanism.
   *
   *  VPA routing:
   *   - `creditorAccount.upiVpa` = payee VPA (who is requesting payment)
   *   - `debtorAccount.upiVpa`   = payer VPA (who will be asked to pay)
   *   - Alternatively, set `metadata("upiPayerVpa")` on the request.
   *
   *  Returns Pending; UpiCollectInitiated webhook event fires immediately on creation.
   *  UpiApproved or UpiDeclined arrives when payer responds (or UpiTwoFactorTimeout if expired).
   */
  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    val payeeVpa = req.creditorAccount.upiVpa.getOrElse(
      throw new IllegalArgumentException("creditorAccount.upiVpa (payee VPA) is required for UPI Collect")
    )
    val payerVpa = req.debtorAccount.upiVpa
                     .orElse(req.metadata.get("upiPayerVpa"))
                     .getOrElse(
                       throw new IllegalArgumentException(
                         "debtorAccount.upiVpa (payer VPA) is required for UPI Collect — " +
                         "set upiVpa on debtorAccount or pass metadata(\"upiPayerVpa\")"
                       )
                     )

    validateVpa(payeeVpa)
    validateVpa(payerVpa)

    val amountPaise = req.amount.minorUnits
    val txnId       = req.idempotencyKey.take(50)
    val remarks     = req.reference.take(50)

    val json = buildPayRequest(
      txnId       = txnId,
      amountPaise = amountPaise,
      payeeVpa    = payeeVpa,
      payerVpa    = Some(payerVpa),
      remarks     = remarks,
      purpose     = config.defaultPurposeCode,
    )

    val respBody   = postJson("/upi/collect", json)
    val transferId = extractField(respBody, "txnId")
                       .orElse(extractField(respBody, "id"))
                       .getOrElse(txnId)

    BankTransfer(
      id        = TransferId(transferId),
      rail      = req.rail,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )

  def getDirectDebit(id: TransferId): BankTransfer =
    val body = getJson(s"/upi/transactions/${id.value}")
    parseTransferFromJson(body, id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] =
    UpiWebhookReceiver(config.webhookPublicKeyPem)

  // ── VPA validation (Diagnostic D12) ──────────────────────────────────────

  /** Validates that a VPA conforms to the `localPart@bankHandle` format.
   *  UPI spec: localPart ≤ 100 chars; bankHandle must be present.
   *  Throws IllegalArgumentException if invalid. */
  def validateVpa(vpa: String): Unit =
    val atIdx = vpa.lastIndexOf('@')
    if atIdx < 1 then
      throw new IllegalArgumentException(s"Invalid UPI VPA format: '$vpa' — expected 'localPart@bankHandle'")
    val localPart  = vpa.substring(0, atIdx)
    val bankHandle = vpa.substring(atIdx + 1)
    if localPart.length > 100 then
      throw new IllegalArgumentException(s"UPI VPA localPart too long (${localPart.length} chars, max 100): '$vpa'")
    if bankHandle.isEmpty then
      throw new IllegalArgumentException(s"UPI VPA bankHandle is empty: '$vpa'")

  // ── JSON request builder ──────────────────────────────────────────────────

  private[upi] def buildPayRequest(
      txnId:       String,
      amountPaise: Long,
      payeeVpa:    String,
      payerVpa:    Option[String],
      remarks:     String,
      purpose:     String,
  ): String =
    val payerField = payerVpa.fold("") { vpa =>
      s""",
         |  "payerVpa": "${escJson(vpa)}"""".stripMargin
    }
    val callbackField = config.callbackUrl.fold("") { url =>
      s""",
         |  "callbackUrl": "${escJson(url)}"""".stripMargin
    }
    s"""{
       |  "txnId": "${escJson(txnId)}",
       |  "amount": $amountPaise,
       |  "payeeVpa": "${escJson(payeeVpa)}",
       |  "payeeName": "${escJson(config.merchantName)}",
       |  "remarks": "${escJson(remarks)}",
       |  "purpose": "${escJson(purpose)}"$payerField$callbackField
       |}""".stripMargin

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private def postJson(path: String, json: String): String =
    val builder = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("X-Api-Key", config.apiKey)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")

    // RSA-SHA256 request signing (required by Razorpay / JusPay)
    val signedBuilder = if config.merchantPrivateKeyPem.nonEmpty then
      val sig = rsaSign(config.merchantPrivateKeyPem, json)
      builder.header("X-Signature", sig)
    else builder

    val req = signedBuilder
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("X-Api-Key", config.apiKey)
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"UPI aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  /** RSA-SHA256 sign the given payload using the merchant private key (PKCS#8 PEM). */
  private[upi] def rsaSign(privateKeyPem: String, payload: String): String =
    val pemContent = privateKeyPem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replace("-----BEGIN RSA PRIVATE KEY-----", "")
      .replace("-----END RSA PRIVATE KEY-----", "")
      .replaceAll("\\s+", "")
    val keyBytes   = Base64.getDecoder.decode(pemContent)
    val keySpec    = PKCS8EncodedKeySpec(keyBytes)
    val keyFactory = KeyFactory.getInstance("RSA")
    val privateKey = keyFactory.generatePrivate(keySpec)
    val signer     = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(payload.getBytes(StandardCharsets.UTF_8))
    Base64.getEncoder.encodeToString(signer.sign())

  // ── JSON helpers ───────────────────────────────────────────────────────────

  private[upi] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val id        = extractField(body, "txnId")
                      .orElse(extractField(body, "id"))
                      .getOrElse(fallbackId.value)
    val statusStr = extractField(body, "status").getOrElse("pending")
    val status    = statusStr.toLowerCase match
      case "success" | "settled" | "approved" | "completed" => BankTransferStatus.Settled
      case "failed" | "declined" | "rejected"               =>
        val reason = extractField(body, "reason").getOrElse("declined")
        BankTransferStatus.Rejected(RejectCode(reason), reason)
      case "expired"                                         =>
        BankTransferStatus.Rejected(RejectCode("EXPIRED"), "UPI collect request expired")
      case _                                                 => BankTransferStatus.Pending
    val dummy = BankAccount(holderName = "", countryCode = "IN")
    BankTransfer(
      id        = TransferId(id),
      rail      = RailKind.IN_UPI,
      amount    = parseMoney(extractField(body, "amount").getOrElse("0")),
      sender    = dummy,
      recipient = dummy,
      reference = extractField(body, "remarks").getOrElse(""),
      status    = status,
      createdAt = Instant.now(),
    )

  private def parseMoney(amountStr: String): Money =
    val paise = scala.util.Try(amountStr.toLong).getOrElse(0L)
    Money(paise, Currency("INR"))

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")


/** India UPI adapter configuration. */
case class UpiConfig(
  apiKey:                String,               // X-Api-Key for the aggregator API
  merchantVpa:           String,               // merchant's UPI VPA (e.g. "merchant@razorpay")
  baseUrl:               String,               // aggregator base URL
  merchantName:          String    = "",        // merchant display name shown to payer
  merchantPrivateKeyPem: String    = "",        // PKCS#8 PEM private key for RSA-SHA256 request signing; empty = disabled
  webhookPublicKeyPem:   String    = "",        // PEM public key from aggregator for webhook RSA-SHA256 verification
  callbackUrl:           Option[String] = None, // webhook callback URL for collect approval/decline notifications
  defaultPurposeCode:    String    = "00",      // "00" = merchant payment, "14" = subscription
)

object UpiConfig:
  /** Load config from environment variables.
   *  UPI_API_KEY, UPI_MERCHANT_VPA, UPI_BASE_URL, UPI_MERCHANT_NAME,
   *  UPI_PRIVATE_KEY_PEM, UPI_WEBHOOK_PUBLIC_KEY_PEM, UPI_CALLBACK_URL, UPI_PURPOSE_CODE */
  def fromEnv: UpiConfig =
    UpiConfig(
      apiKey                = sys.env.getOrElse("UPI_API_KEY",                ""),
      merchantVpa           = sys.env.getOrElse("UPI_MERCHANT_VPA",           "merchant@razorpay"),
      baseUrl               = sys.env.getOrElse("UPI_BASE_URL",               "https://api.razorpay.com/v1"),
      merchantName          = sys.env.getOrElse("UPI_MERCHANT_NAME",          ""),
      merchantPrivateKeyPem = sys.env.getOrElse("UPI_PRIVATE_KEY_PEM",        ""),
      webhookPublicKeyPem   = sys.env.getOrElse("UPI_WEBHOOK_PUBLIC_KEY_PEM", ""),
      callbackUrl           = sys.env.get("UPI_CALLBACK_URL"),
      defaultPurposeCode    = sys.env.getOrElse("UPI_PURPOSE_CODE",           "00"),
    )


/** Models a UPI Collect request sent to a payer's VPA.
 *
 *  The collect request initiates the Pull flow:
 *  1. Payee creates this request — UpiCollectInitiated event fires.
 *  2. Payer sees a notification on their UPI app and enters their UPI PIN.
 *  3. If approved before expiresAt — UpiApproved event fires with UTR number.
 *  4. If declined or expired — UpiDeclined fires; UpiTwoFactorTimeout if PIN entry timed out.
 *
 *  Amount is in paise (1 INR = 100 paise). Default per-transaction limit: Rs.2,00,000.
 */
case class UpiCollectRequest(
  txnId:       String,               // idempotency key / NPCI transaction ID (max 50 chars)
  payeeVpa:    String,               // payee's VPA (requesting payment; format: name@bank)
  payerVpa:    String,               // payer's VPA (asked to pay; format: name@bank)
  amountPaise: Long,                 // amount in paise (e.g. 10000 = Rs.100.00)
  remarks:     String,               // description shown to payer (max 50 chars)
  expiresAt:   java.time.Instant,    // when collect request expires (typically 30-60 minutes)
  purposeCode: String = "00",        // "00" = merchant payment, "14" = subscription
)
