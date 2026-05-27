package scalascript.payments.pix

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** Pix instant payments adapter (Brazil).
 *
 *  Implements [[BankRailsProvider]] for the Pix rail.  Communicates with a
 *  PSP's DICT-compliant REST API (BACEN BCB standard).
 *
 *  Auth: OAuth2 client-credentials Bearer JWT.  The adapter obtains a token via
 *  POST to `pixApiUrl/oauth/token` before each request (no caching in this
 *  implementation; production code should cache until expiry).
 *
 *  Settlement: Pix is T+0 — credit arrives in < 10 seconds (BACEN SLA).
 *  `initiateTransfer` returns `BankTransfer(status = Pending)`.  The PSP delivers
 *  a `pix.received` webhook or the caller polls `getTransfer` to confirm settlement.
 *
 *  `initiateDirectDebit` uses Pix Automático / cobv (cobrança com vencimento) — a
 *  recurring-consent scheduled debit (POST `/v2/cobv`).
 *
 *  See docs/bank-rails.md §8 v1.54.3.
 */
class PixProvider(config: PixConfig) extends BankRailsProvider:

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def id:             String        = "pix"
  def displayName:    String        = "Pix (Brazil instant payments)"
  def spiVersion:     String        = "1.54.3"
  def supportedRails: Set[RailKind] = Set(RailKind.PIX)

  // ── Push: Pix Credit Transfer ──────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then throw UnsupportedRail(req.rail, id)

    val pixKey  = req.recipient.pixKey
                    .getOrElse(throw PixKeyNotFound("<missing>"))
    val txid    = sanitizeTxid(req.idempotencyKey)
    val token   = obtainToken()

    val body = buildPixPayload(req, pixKey, txid)
    val resp = postJson(s"/v2/pix", body, token)

    val e2eId      = extractField(resp, "endToEndId").getOrElse(txid)
    val statusStr  = extractField(resp, "status").getOrElse("ATIVA")

    BankTransfer(
      id        = TransferId(e2eId),
      rail      = RailKind.PIX,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = parsePixStatus(statusStr),
      createdAt = Instant.now(),
      metadata  = req.metadata + ("txid" -> txid),
    )

  def getTransfer(id: TransferId): BankTransfer =
    val token = obtainToken()
    val body  = getJson(s"/v2/pix/${id.value}", token)
    parseTransferFromJson(body, id)

  def cancelTransfer(id: TransferId): Unit =
    // Pix transfers settle in < 10 seconds; cancellation is only meaningful
    // for cobv (scheduled) payments.  For instant Pix, throw if already settled.
    val token = obtainToken()
    val body  = getJson(s"/v2/pix/${id.value}", token)
    val status = extractField(body, "status").getOrElse("CONCLUIDA")
    if status == "CONCLUIDA" then
      throw BankRailsCancelError(id, "Pix transfer already settled (T+0 rail)")
    // For cobv / pending entries, DELETE the cob
    val txid = extractField(body, "txid").getOrElse(id.value)
    deleteRequest(s"/v2/cob/$txid", token)

  // ── Pull: Pix Automático / cobv ────────────────────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    if req.rail != RailKind.PIX then throw UnsupportedRail(req.rail, id)
    val txid  = sanitizeTxid(req.idempotencyKey)
    val token = obtainToken()

    // BACEN cobv (cobrança com vencimento) — scheduled Pix debit
    val expiracao = req.scheduledDate
      .map(d => s""""calendario":{"dataDeVencimento":"$d","validadeAposVencimento":30}""")
      .getOrElse(s""""calendario":{"expiracao":3600}""")

    val pixKeyOfRecipient = req.creditorAccount.pixKey
                              .getOrElse(throw PixKeyNotFound("<missing>"))
    val amountStr = PixQrCode.formatAmount(req.amount)

    val body =
      s"""{
         |  "txid": "$txid",
         |  "valor": {"original": "$amountStr"},
         |  $expiracao,
         |  "chave": "$pixKeyOfRecipient",
         |  "solicitacaoPagador": "${jsonEscape(req.reference)}"
         |}""".stripMargin

    val resp = postJson(s"/v2/cobv/$txid", body, token)

    val e2eId     = extractField(resp, "endToEndId").getOrElse(txid)
    val statusStr = extractField(resp, "status").getOrElse("ATIVA")

    BankTransfer(
      id        = TransferId(e2eId),
      rail      = RailKind.PIX,
      amount    = req.amount,
      sender    = req.debtorAccount,
      recipient = req.creditorAccount,
      reference = req.reference,
      status    = parsePixStatus(statusStr),
      createdAt = Instant.now(),
      metadata  = req.metadata + ("txid" -> txid),
    )

  def getDirectDebit(id: TransferId): BankTransfer =
    val token = obtainToken()
    val body  = getJson(s"/v2/cobv/${id.value}", token)
    parseTransferFromJson(body, id)

  // ── Webhook ────────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = PixWebhookReceiver()

  // ── OAuth2 token acquisition ───────────────────────────────────────────────

  /** Obtain a Bearer token via OAuth2 client-credentials flow.
   *
   *  Production adapters should cache the token until its `expires_in` deadline.
   *  This implementation fetches a fresh token per call (safe for low-volume use).
   */
  private[pix] def obtainToken(): String =
    val credentials = s"${config.pixClientId}:${config.pixClientSecret}"
    val encoded     = java.util.Base64.getEncoder.encodeToString(credentials.getBytes("UTF-8"))
    val req = JHttpRequest.newBuilder(URI.create(s"${config.pixApiUrl}/oauth/token"))
      .header("Authorization", s"Basic $encoded")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(JHttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=cob.write cob.read pix.write pix.read"))
      .timeout(Duration.ofSeconds(15))
      .build()
    val resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"Pix OAuth2 token error ${resp.statusCode()}: ${resp.body()}")
    extractField(resp.body(), "access_token").getOrElse(
      throw new RuntimeException("Pix OAuth2 response missing access_token")
    )

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private def postJson(path: String, body: String, token: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.pixApiUrl}$path"))
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String, token: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.pixApiUrl}$path"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def deleteRequest(path: String, token: String): Unit =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.pixApiUrl}$path"))
      .header("Authorization", s"Bearer $token")
      .DELETE()
      .timeout(Duration.ofSeconds(30))
      .build()
    http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    ()

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      val code = extractField(resp.body(), "erro").orElse(extractField(resp.body(), "type"))
                   .getOrElse(resp.statusCode().toString)
      val msg  = extractField(resp.body(), "detail").orElse(extractField(resp.body(), "mensagem"))
                   .getOrElse(resp.body())
      resp.statusCode() match
        case 404 =>
          val key = extractField(resp.body(), "chave").getOrElse("unknown")
          throw PixKeyNotFound(key)
        case _ =>
          throw new RuntimeException(s"Pix API error $code: $msg")
    resp.body()

  // ── Parse helpers ──────────────────────────────────────────────────────────

  private def buildPixPayload(req: InitiateTransferRequest, pixKey: String, txid: String): String =
    val amountStr = PixQrCode.formatAmount(req.amount)
    s"""{
       |  "chave": "$pixKey",
       |  "valor": "$amountStr",
       |  "txid": "$txid",
       |  "descricao": "${jsonEscape(req.reference)}"
       |}""".stripMargin

  private def parseTransferFromJson(body: String, fallbackId: TransferId): BankTransfer =
    val e2eId     = extractField(body, "endToEndId").getOrElse(fallbackId.value)
    val statusStr = extractField(body, "status").getOrElse("ATIVA")
    val valorStr  = extractField(body, "valor").getOrElse("0")
    val dummy     = BankAccount(holderName = "", countryCode = "BR")
    BankTransfer(
      id        = TransferId(e2eId),
      rail      = RailKind.PIX,
      amount    = parseMoneyDecimal(valorStr, "BRL"),
      sender    = dummy,
      recipient = dummy,
      reference = extractField(body, "txid").getOrElse(e2eId),
      status    = parsePixStatus(statusStr),
      createdAt = Instant.now(),
    )

  /** Map BACEN Pix status strings to BankTransferStatus. */
  private[pix] def parsePixStatus(status: String): BankTransferStatus =
    status.toUpperCase match
      case "CONCLUIDA" | "LIQUIDADA"          => BankTransferStatus.Settled
      case "DEVOLVIDA"                         => BankTransferStatus.Returned(ReturnCode("DEVOLVIDA"), "Pix devolution")
      case "EM_PROCESSAMENTO" | "ATIVA"       => BankTransferStatus.Pending
      case "CANCELADA"                         => BankTransferStatus.Canceled
      case "NAO_REALIZADO"                     =>
        BankTransferStatus.Rejected(RejectCode("NAO_REALIZADO"), "Payment not completed")
      case _                                   => BankTransferStatus.Pending

  private def parseMoneyDecimal(decStr: String, ccyCode: String): Money =
    val ccy   = Currency(ccyCode.toUpperCase)
    val power = Currency.minorUnitsPower(ccy)
    val bd    = scala.util.Try(BigDecimal(decStr)).getOrElse(BigDecimal(0))
    val minor = (bd * BigDecimal(math.pow(10, power).toLong)).toLong
    Money(minor, ccy)

  /** Sanitize an idempotency key to a valid Pix txid (max 35 alphanumeric chars). */
  private[pix] def sanitizeTxid(key: String): String =
    key.filter(_.isLetterOrDigit).take(35).padTo(35, '0').take(35)

  private[pix] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")


/** Pix adapter configuration.
 *  Load from environment variables or supply directly.
 *
 *  For mTLS (direct BACEN connectivity), set `pixCertPath` + `pixKeyPath`
 *  to PEM files for the BACEN ICP-Brasil client certificate.  Aggregator
 *  (Gerencianet/EFÍ, PagSeguro, etc.) uses client-credentials OAuth2 only.
 */
case class PixConfig(
  pixApiUrl:         String,   // e.g. "https://api-pix.gerencianet.com.br"
  pixClientId:       String,   // OAuth2 client ID
  pixClientSecret:   String,   // OAuth2 client secret
  pixPixKey:         String,   // merchant's own Pix key (CPF/CNPJ/phone/email/EVP)
  pixCertPath:       String = "", // path to mTLS client cert PEM (empty = skip mTLS)
  pixKeyPath:        String = "", // path to mTLS client private key PEM
)

object PixConfig:
  /** Load config from environment variables.
   *  PIX_API_URL, PIX_CLIENT_ID, PIX_CLIENT_SECRET, PIX_PIX_KEY,
   *  PIX_CERT_PATH, PIX_KEY_PATH */
  def fromEnv: PixConfig =
    PixConfig(
      pixApiUrl       = sys.env.getOrElse("PIX_API_URL",       "https://api-pix.example.com"),
      pixClientId     = sys.env.getOrElse("PIX_CLIENT_ID",     ""),
      pixClientSecret = sys.env.getOrElse("PIX_CLIENT_SECRET", ""),
      pixPixKey       = sys.env.getOrElse("PIX_PIX_KEY",       ""),
      pixCertPath     = sys.env.getOrElse("PIX_CERT_PATH",     ""),
      pixKeyPath      = sys.env.getOrElse("PIX_KEY_PATH",      ""),
    )
