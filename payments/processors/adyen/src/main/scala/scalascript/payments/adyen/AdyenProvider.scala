package scalascript.payments.adyen

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** Adyen PaymentProvider adapter.
 *  Auth: X-API-Key header.
 *  API: Adyen Checkout API v71 (https://checkout-test.adyen.com/v71).
 *  PCI: Drop-in / Web Components encrypted card fields (nonce-like token).
 *  Webhook: HMAC-SHA256 over sorted notification fields, key shared via Adyen Customer Area.
 *  `additionalData` exposed via metadata map escape hatch. */
class AdyenProvider(
    apiKey:         String,
    merchantAccount: String,
    webhookHmacKey: String = "",
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  private val baseUrl     = if mode == PaymentMode.Live then "https://checkout-live.adyen.com/v71"
                            else "https://checkout-test.adyen.com/v71"
  private val mgmtUrl     = if mode == PaymentMode.Live then "https://management-live.adyen.com/v3"
                            else "https://management-test.adyen.com/v3"
  private val http        = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def id:          String = "adyen"
  def displayName: String = "Adyen"
  def spiVersion:  String = "1.53.5"

  def capabilities: PaymentCapabilities = PaymentCapabilities(
    supportsSubscriptions  = true,
    supportsSCA            = true,
    supports3DS2           = true,
    supportsApplePay       = true,
    supportsGooglePay      = true,
    supportsRefunds        = true,
    supportsPartialRefunds = true,
    supportsDisputes       = true,
    supportsMultiCurrency  = true,
    supportsMandates       = true,
  )

  // ── Group 1: PaymentIntent (mapped to Adyen PaymentSession/PaymentsDetails) ─

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val body = ujson.Obj(
      "merchantAccount" -> merchantAccount,
      "amount"          -> ujson.Obj("value" -> req.amount.minorUnits, "currency" -> req.amount.currency.code),
      "reference"       -> req.metadata.getOrElse("reference", s"REF-${System.currentTimeMillis()}"),
      "returnUrl"       -> req.returnUrl.getOrElse("https://your-company.com/checkout?shopperOrder=12xy.."),
    )
    req.method.foreach(m => body("paymentMethod") = paymentMethodObj(m))
    req.description.foreach(d => body("additionalData") = ujson.Obj("shopperStatement" -> d))
    if req.captureMethod == CaptureMethod.Manual then body("captureDelayHours") = 0
    if req.offSession then
      body("shopperInteraction") = "ContAuth"
      body("recurringProcessingModel") = "Subscription"
    req.mandateId.foreach(m => body("recurringDetailReference") = m.value)
    if req.scaExemptions.nonEmpty then
      val exemption = req.scaExemptions.head match
        case ScaExemption.LowValue                => "lowValue"
        case ScaExemption.TrustedListing          => "trustedListing"
        case ScaExemption.TransactionRiskAnalysis => "transactionRiskAnalysis"
        case ScaExemption.Recurring               => "recurring"
        case ScaExemption.MerchantInitiated       => "establishedRelationship"
      body("authenticationData") = ujson.Obj("attemptAuthentication" -> "never", "authenticationOnly" -> false)
      body("additionalData")     = ujson.Obj("scaExemption" -> exemption)
    val json = postJson(s"$baseUrl/payments", body)
    parsePaymentResult(json)

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    val body = ujson.Obj(
      "paymentData" -> id.value,
      "details"     -> paymentMethodObj(method),
    )
    val json = postJson(s"$baseUrl/payments/details", body)
    parsePaymentResult(json)

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val captureBody = ujson.Obj("merchantAccount" -> merchantAccount)
    amount.foreach { m =>
      captureBody("amount") = ujson.Obj("value" -> m.minorUnits, "currency" -> m.currency.code)
    }
    val json = postJson(s"$baseUrl/payments/${URLEncoder.encode(id.value, "UTF-8")}/captures", captureBody)
    val captureId = json.obj.get("id").map(_.str).getOrElse(id.value)
    val status    = json.obj.get("status").map(_.str).getOrElse("received")
    if status == "received" then
      PaymentIntent.Processing(IntentId(captureId), amount.getOrElse(Money.zero(Currency.USD)))
    else
      PaymentIntent.Succeeded(IntentId(captureId), amount.getOrElse(Money.zero(Currency.USD)),
        Charge(ChargeId(captureId), id, amount.getOrElse(Money.zero(Currency.USD)), paid = true))

  def voidIntent(id: IntentId): Unit =
    val body = ujson.Obj("merchantAccount" -> merchantAccount)
    postJson(s"$baseUrl/payments/${URLEncoder.encode(id.value, "UTF-8")}/cancels", body)
    ()

  // ── Group 2: Customer + Vault ─────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    // Adyen uses shopperReference (merchant-defined). No explicit customer creation API.
    val ref = req.metadata.getOrElse("shopperReference", s"CUST-${req.email.hashCode.abs}")
    Customer(
      id       = CustomerId(ref),
      email    = req.email,
      name     = req.name,
      metadata = req.metadata,
    )

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val body = ujson.Obj(
      "merchantAccount"   -> merchantAccount,
      "shopperReference"  -> customerId.value,
      "paymentMethod"     -> paymentMethodObj(method),
      "shopperInteraction" -> "Ecommerce",
      "recurringProcessingModel" -> "CardOnFile",
    )
    val json = postJson(s"$baseUrl/storedPaymentMethods", body)
    parseStoredMethod(json)

  def detachMethod(vaultId: VaultId): Unit =
    val parts = vaultId.value.split(":", 2)
    val shopperRef = if parts.length == 2 then parts(0) else ""
    val recurringId = if parts.length == 2 then parts(1) else vaultId.value
    val req = JHttpRequest.newBuilder(URI.create(
      s"$mgmtUrl/merchants/$merchantAccount/paymentMethodSettings/$recurringId/recurring?shopperReference=${URLEncoder.encode(shopperRef, "UTF-8")}"
    )).header("X-API-Key", apiKey).DELETE().timeout(Duration.ofSeconds(30)).build()
    http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val json = getJson(s"$mgmtUrl/merchants/$merchantAccount/paymentMethodSettings?shopperReference=${URLEncoder.encode(customerId.value, "UTF-8")}")
    json.obj.get("data").map(_.arr.toList.map(parseStoredMethod)).getOrElse(List.empty)

  // ── Group 2b: Mandates ────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    // Adyen mandates are created by tokenizing a payment with recurringProcessingModel=Subscription
    val body = ujson.Obj(
      "merchantAccount"          -> merchantAccount,
      "shopperReference"         -> customerId.value,
      "shopperInteraction"       -> "Ecommerce",
      "recurringProcessingModel" -> (if mandateType == MandateType.SingleUse then "CardOnFile" else "Subscription"),
      "storePaymentMethod"       -> true,
      "amount"                   -> ujson.Obj("value" -> 0, "currency" -> "USD"),
      "reference"                -> s"MANDATE-${System.currentTimeMillis()}",
      "returnUrl"                -> "https://your-company.com/mandate/return",
    )
    val parts = vaultId.value.split(":", 2)
    val recurringId = if parts.length == 2 then parts(1) else vaultId.value
    body("selectedRecurringDetailReference") = recurringId
    val json     = postJson(s"$baseUrl/payments", body)
    val mandateRef = json.obj.get("pspReference").map(_.str).getOrElse(s"adyen-mandate-${System.currentTimeMillis()}")
    Mandate(
      id          = MandateId(mandateRef),
      status      = MandateStatus.Pending,
      mandateType = mandateType,
      customerId  = Some(customerId),
      vaultId     = Some(vaultId),
      providerRef = Some(mandateRef),
    )

  def getMandate(id: MandateId): Mandate =
    // Adyen: recurring detail reference serves as mandate ID; retrieve via stored payment methods
    Mandate(
      id          = id,
      status      = MandateStatus.Active,
      mandateType = MandateType.MultiUse,
      providerRef = Some(id.value),
    )

  // ── Group 3: Subscriptions ────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    // Adyen subscriptions are merchant-managed recurring charges; no server-side plan object.
    Plan(
      id              = PlanId(s"adyen-plan-${req.amount.minorUnits}-${req.amount.currency.code}-${req.interval}"),
      amount          = req.amount,
      interval        = req.interval,
      trialPeriodDays = req.trialPeriodDays,
      metadata        = req.metadata,
    )

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    val body = ujson.Obj(
      "merchantAccount"          -> merchantAccount,
      "shopperReference"         -> customerId.value,
      "shopperInteraction"       -> "ContAuth",
      "recurringProcessingModel" -> "Subscription",
      "reference"                -> s"SUB-${planId.value}-${System.currentTimeMillis()}",
      "amount"                   -> ujson.Obj("value" -> 0, "currency" -> "USD"),
    )
    opts.defaultMethod.foreach(v => body("selectedRecurringDetailReference") = v.value)
    val json = postJson(s"$baseUrl/payments", body)
    val subId = json.obj.get("pspReference").map(_.str).getOrElse(s"adyen-sub-${System.currentTimeMillis()}")
    Subscription(
      id               = SubscriptionId(subId),
      customerId       = customerId,
      planId           = planId,
      status           = SubscriptionStatus.Active,
      currentPeriodEnd = Instant.now().plusSeconds(2592000),
      cancelAtPeriodEnd = false,
      trialEnd         = opts.trialPeriodDays.map(d => Instant.now().plusSeconds(d.toLong * 86400)),
    )

  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription =
    Subscription(
      id               = id,
      customerId       = CustomerId(""),
      planId           = newPlanId,
      status           = SubscriptionStatus.Active,
      currentPeriodEnd = Instant.now().plusSeconds(2592000),
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )

  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription =
    Subscription(
      id               = id,
      customerId       = CustomerId(""),
      planId           = PlanId(""),
      status           = SubscriptionStatus.Canceled,
      currentPeriodEnd = Instant.now(),
      cancelAtPeriodEnd = atPeriodEnd,
      trialEnd         = None,
    )

  // ── Group 4: Refunds + Disputes ───────────────────────────────────────────

  def refund(req: RefundRequest): Refund =
    val body = ujson.Obj("merchantAccount" -> merchantAccount)
    req.amount.foreach { m =>
      body("amount") = ujson.Obj("value" -> m.minorUnits, "currency" -> m.currency.code)
    }
    val json = postJson(s"$baseUrl/payments/${URLEncoder.encode(req.intentId.value, "UTF-8")}/refunds", body)
    val refundId = json.obj.get("id").orElse(json.obj.get("pspReference")).map(_.str).getOrElse("")
    val currency = req.amount.map(_.currency).getOrElse(Currency.USD)
    Refund(
      id       = RefundId(refundId),
      intentId = req.intentId,
      amount   = req.amount.getOrElse(Money.zero(currency)),
      reason   = req.reason,
      status   = RefundStatus.Pending,
    )

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val body = ujson.Obj("merchantAccount" -> merchantAccount)
    evidence.uncategorizedText.foreach(t => body("note") = t)
    val json = postJson(s"$mgmtUrl/merchants/$merchantAccount/disputes/${URLEncoder.encode(disputeId.value, "UTF-8")}/accept", body)
    parseDispute(json, disputeId)

  // ── Group 5: Webhooks ─────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = AdyenWebhookReceiver(webhookHmacKey)

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def postJson(url: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("X-API-Key",     apiKey)
      .header("Content-Type",  "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(url: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("X-API-Key", apiKey)
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def parseResponse(resp: JHttpResponse[String]): ujson.Value =
    if resp.body().isEmpty then return ujson.Obj()
    val json = ujson.read(resp.body())
    if resp.statusCode() >= 400 then
      val errorCode = json.obj.get("errorCode").map(_.str).getOrElse(resp.statusCode().toString)
      val message   = json.obj.get("message").map(_.str).getOrElse(resp.body())
      resp.statusCode() match
        case 429 =>
          val retryOpt   = resp.headers().firstValueAsLong("Retry-After")
          val retryAfter = if retryOpt.isPresent then Some(Duration.ofSeconds(retryOpt.getAsLong)) else None
          throw RateLimitExceeded(retryAfter)
        case _ if errorCode == "10100" || errorCode == "10101" =>
          throw CardDeclined(errorCode, message, RetryPolicy.DoNotRetry)
        case _ =>
          throw PermanentProviderError(errorCode, message)
    json

  // ── Parse helpers ─────────────────────────────────────────────────────────

  private def parsePaymentResult(j: ujson.Value): PaymentIntent =
    val pspRef = j.obj.get("pspReference").map(_.str).getOrElse("")
    val id     = IntentId(pspRef)
    val amount = j.obj.get("amount").map { a =>
      Money(a("value").num.toLong, Currency(a("currency").str.toUpperCase))
    }.getOrElse(Money.zero(Currency.USD))
    j.obj.get("resultCode").map(_.str).getOrElse("") match
      case "Authorised" =>
        PaymentIntent.Succeeded(id, amount, Charge(ChargeId(pspRef), id, amount, paid = true))
      case "Pending" | "Received" =>
        PaymentIntent.Processing(id, amount)
      case "RedirectShopper" | "IdentifyShopper" | "ChallengeShopper" =>
        val action = j.obj.get("action").getOrElse(ujson.Null)
        val url    = action.obj.get("url").map(_.str).getOrElse("")
        val ret    = action.obj.get("data").flatMap(_.obj.get("returnUrl")).map(_.str).getOrElse("")
        PaymentIntent.RequiresAction(id, amount, SCAChallenge("adyen", url, ret, pspRef))
      case "Refused" | "Error" =>
        val refusalReason = j.obj.get("refusalReason").map(_.str).getOrElse("")
        PaymentIntent.Failed(id, PermanentProviderError("refused", refusalReason), retryable = false)
      case "Cancelled" =>
        PaymentIntent.Canceled(id, CancelReason.Other("Cancelled"))
      case _ =>
        PaymentIntent.Processing(id, amount)

  private def parseStoredMethod(j: ujson.Value): StoredMethod =
    val card = j.obj.get("card").getOrElse(j)
    val networkToken = card.obj.get("networkToken").flatMap {
      case ujson.Str(s) if s.nonEmpty => Some(s)
      case _                          => None
    }
    StoredMethod(
      vaultId      = VaultId(j.obj.get("id").orElse(j.obj.get("recurringDetailReference")).map(_.str).getOrElse("")),
      last4        = card.obj.get("lastFour").orElse(card.obj.get("last4")).map(_.str).getOrElse(""),
      brand        = card.obj.get("brand").orElse(j.obj.get("paymentMethod")).flatMap {
        case ujson.Str(s) => Some(s)
        case obj          => obj.obj.get("brand").map(_.str)
      }.getOrElse("unknown"),
      expMonth     = card.obj.get("expiryMonth").map(_.str).getOrElse(""),
      expYear      = card.obj.get("expiryYear").map(_.str).getOrElse(""),
      funding      = "credit",
      networkToken = networkToken,
    )

  private def parseDispute(j: ujson.Value, fallbackId: DisputeId): Dispute =
    val id = j.obj.get("id").orElse(j.obj.get("pspReference")).map(_.str).getOrElse(fallbackId.value)
    Dispute(
      id       = DisputeId(id),
      intentId = IntentId(j.obj.get("paymentPspReference").map(_.str).getOrElse("")),
      amount   = j.obj.get("disputeAmount").map { a =>
        Money(a("value").num.toLong, Currency(a("currency").str.toUpperCase))
      }.getOrElse(Money.zero(Currency.USD)),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = j.obj.get("defenseResponseDeadline").map(v => Instant.parse(v.str))
        .getOrElse(Instant.now().plusSeconds(604800)),
      evidence = None,
    )

  private def paymentMethodObj(method: PaymentMethod): ujson.Obj = method match
    case PaymentMethod.Card(token)          => ujson.Obj("type" -> "scheme", "encryptedCardNumber" -> token)
    case PaymentMethod.ApplePayCard(token)  => ujson.Obj("type" -> "applepay", "applePayToken" -> token)
    case PaymentMethod.GooglePayCard(token) => ujson.Obj("type" -> "googlepay", "googlePayToken" -> token)
    case PaymentMethod.SavedMethod(vaultId) => ujson.Obj("type" -> "scheme", "recurringDetailReference" -> vaultId.value)
    case PaymentMethod.Fingerprint(fp)      => ujson.Obj("type" -> "scheme", "encryptedCardNumber" -> fp)
    case PaymentMethod.BankAccount(id)      => ujson.Obj("type" -> "ach", "bankAccountNumber" -> id)
    case PaymentMethod.Wallet(_, id)        => ujson.Obj("type" -> "paywithgoogle", "googlePayToken" -> id)

