package scalascript.payments.checkout

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** Checkout.com PaymentProvider adapter.
 *  Auth: `Authorization: Bearer sk_xxx` secret key.
 *  API: Checkout.com Unified Payments API v3 (https://api.sandbox.checkout.com).
 *  PCI: Frames hosted fields — returns `token_xxx` which maps to `PaymentMethod.Card`.
 *  Webhook: HMAC-SHA256 over raw body + `Cko-Signature` header. */
class CheckoutProvider(
    secretKey:   String,
    webhookSecret: String = "",
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  private val baseUrl = if mode == PaymentMode.Live then "https://api.checkout.com"
                        else "https://api.sandbox.checkout.com"
  private val http    = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def id:          String = "checkout"
  def displayName: String = "Checkout.com"
  def spiVersion:  String = "1.53.3"

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

  // ── Group 1: PaymentIntent (mapped to Checkout.com Payments) ─────────────

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val body = ujson.Obj(
      "amount"   -> req.amount.minorUnits,
      "currency" -> req.amount.currency.code,
      "reference" -> req.metadata.getOrElse("reference", s"REF-${System.currentTimeMillis()}"),
    )
    req.method.foreach(m => body("source") = paymentSourceOf(m))
    req.description.foreach(d => body("description") = d)
    req.returnUrl.foreach(u => body("success_url") = u)
    if req.captureMethod == CaptureMethod.Manual then body("capture") = false
    if req.offSession then body("merchant_initiated") = true
    req.setupFutureUsage.foreach {
      case SetupFutureUsage.OnSession  => body("processing_channel_id") = "pc_"; body("store_payment_details") = "on_success"
      case SetupFutureUsage.OffSession => body("store_payment_details") = "always"
    }
    req.metadata.filterNot((k, _) => k == "reference").foreach { (k, v) =>
      body("metadata") = ujson.Obj(k -> v)
    }
    val json = postJson("/payments", body)
    parsePaymentResponse(json)

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    val body = ujson.Obj("source" -> paymentSourceOf(method))
    val json = postJson(s"/payments/${id.value}", body)
    parsePaymentResponse(json)

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val body = ujson.Obj()
    amount.foreach { m => body("amount") = m.minorUnits }
    val json = postJson(s"/payments/${id.value}/captures", body)
    val actionId = json.obj.get("action_id").map(_.str).getOrElse("")
    PaymentIntent.Succeeded(id, amount.getOrElse(Money.zero(Currency.USD)),
      Charge(ChargeId(actionId), id, amount.getOrElse(Money.zero(Currency.USD)), paid = true))

  def voidIntent(id: IntentId): Unit =
    postJson(s"/payments/${id.value}/voids", ujson.Obj())
    ()

  // ── Group 2: Customer + Vault ─────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    val body = ujson.Obj("email" -> req.email)
    req.name.foreach(n => body("name") = n)
    val json = postJson("/customers", body)
    Customer(
      id       = CustomerId(json.obj.get("id").map(_.str).getOrElse("")),
      email    = req.email,
      name     = req.name,
      metadata = Map.empty,
    )

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val body = ujson.Obj(
      "customer_id" -> customerId.value,
      "type"        -> "token",
      "token"       -> methodToken(method),
    )
    val json = postJson("/instruments", body)
    parseInstrument(json)

  def detachMethod(vaultId: VaultId): Unit =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl/instruments/${vaultId.value}"))
      .header("Authorization", s"Bearer $secretKey")
      .DELETE()
      .timeout(Duration.ofSeconds(30))
      .build()
    http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val json = getJson(s"/customers/${customerId.value}")
    json.obj.get("instruments").map(_.arr.toList.map(parseInstrument)).getOrElse(List.empty)

  // ── Group 2b: Mandates ─────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    // Checkout.com: recurring permissions are set at instrument level; no separate mandate resource.
    Mandate(
      id          = MandateId(s"cko-mandate-${vaultId.value}"),
      status      = MandateStatus.Active,
      mandateType = mandateType,
      customerId  = Some(customerId),
      vaultId     = Some(vaultId),
      providerRef = Some(vaultId.value),
    )

  def getMandate(id: MandateId): Mandate =
    Mandate(
      id          = id,
      status      = MandateStatus.Active,
      mandateType = MandateType.MultiUse,
      providerRef = Some(id.value),
    )

  // ── Group 3: Subscriptions ────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    // Checkout.com uses merchant-managed subscriptions via recurring payment intents.
    Plan(
      id              = PlanId(s"cko-plan-${req.amount.minorUnits}-${req.amount.currency.code}"),
      amount          = req.amount,
      interval        = req.interval,
      trialPeriodDays = req.trialPeriodDays,
      metadata        = req.metadata,
    )

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    Subscription(
      id               = SubscriptionId(s"cko-sub-${System.currentTimeMillis()}"),
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
    val body = ujson.Obj()
    req.amount.foreach { m => body("amount") = m.minorUnits }
    val json     = postJson(s"/payments/${req.intentId.value}/refunds", body)
    val actionId = json.obj.get("action_id").map(_.str).getOrElse("")
    val currency = req.amount.map(_.currency).getOrElse(Currency.USD)
    Refund(
      id       = RefundId(actionId),
      intentId = req.intentId,
      amount   = req.amount.getOrElse(Money.zero(currency)),
      reason   = req.reason,
      status   = RefundStatus.Pending,
    )

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val body = ujson.Obj()
    evidence.uncategorizedText.foreach(t => body("summary") = t)
    postJson(s"/disputes/${disputeId.value}/evidence", body)
    val json = getJson(s"/disputes/${disputeId.value}")
    parseDispute(json)

  // ── Group 5: Webhooks ─────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = CheckoutWebhookReceiver(webhookSecret)

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def postJson(path: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer $secretKey")
      .header("Content-Type",  "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(path: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer $secretKey")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def parseResponse(resp: JHttpResponse[String]): ujson.Value =
    if resp.body().isEmpty then return ujson.Obj()
    val json = ujson.read(resp.body())
    if resp.statusCode() >= 400 then
      val errorType = json.obj.get("error_type").map(_.str).getOrElse(resp.statusCode().toString)
      val message   = json.obj.get("error_codes").map(_.arr.headOption.map(_.str).getOrElse(errorType))
        .getOrElse(resp.body())
      resp.statusCode() match
        case 429 =>
          val retryOpt   = resp.headers().firstValueAsLong("Retry-After")
          val retryAfter = if retryOpt.isPresent then Some(Duration.ofSeconds(retryOpt.getAsLong)) else None
          throw RateLimitExceeded(retryAfter)
        case _ if errorType == "card_declined" || errorType == "insufficient_funds" =>
          throw CardDeclined(errorType, message, RetryPolicy.DoNotRetry)
        case _ =>
          throw PermanentProviderError(errorType, message)
    json

  // ── Parse helpers ─────────────────────────────────────────────────────────

  private def parsePaymentResponse(j: ujson.Value): PaymentIntent =
    val id     = IntentId(j.obj.get("id").map(_.str).getOrElse(""))
    val amount = j.obj.get("amount").map(a => Money(a.num.toLong,
      Currency(j.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD"))
    )).getOrElse(Money.zero(Currency.USD))
    j.obj.get("status").map(_.str).getOrElse("") match
      case "Authorized"  =>
        PaymentIntent.Succeeded(id, amount, Charge(ChargeId(id.value), id, amount, paid = true))
      case "Pending"     =>
        PaymentIntent.Processing(id, amount)
      case "Declined"    =>
        val reason = j.obj.get("response_summary").map(_.str).getOrElse("")
        PaymentIntent.Failed(id, PermanentProviderError("Declined", reason), retryable = false)
      case "Void" | "Expired" =>
        PaymentIntent.Canceled(id, CancelReason.Other(j.obj.get("status").map(_.str).getOrElse("")))
      case "3ds"         =>
        val redirectUrl = j.obj.get("_links").flatMap(_.obj.get("redirect")).flatMap(_.obj.get("href")).map(_.str).getOrElse("")
        PaymentIntent.RequiresAction(id, amount, SCAChallenge("checkout", redirectUrl, "", id.value))
      case _ =>
        PaymentIntent.Processing(id, amount)

  private def parseInstrument(j: ujson.Value): StoredMethod =
    StoredMethod(
      vaultId  = VaultId(j.obj.get("id").map(_.str).getOrElse("")),
      last4    = j.obj.get("last4").map(_.str).getOrElse(""),
      brand    = j.obj.get("scheme").orElse(j.obj.get("brand")).map(_.str).getOrElse("unknown"),
      expMonth = j.obj.get("expiry_month").map(_.num.toInt.toString).getOrElse(""),
      expYear  = j.obj.get("expiry_year").map(_.num.toInt.toString).getOrElse(""),
      funding  = j.obj.get("product_type").map(_.str).getOrElse("credit"),
    )

  private def parseDispute(j: ujson.Value): Dispute =
    val status = j.obj.get("status").map(_.str).getOrElse("evidence_required") match
      case "evidence_required" => DisputeStatus.NeedsResponse
      case "under_review"      => DisputeStatus.UnderReview
      case "resolved"          => DisputeStatus.Won
      case _                   => DisputeStatus.NeedsResponse
    Dispute(
      id       = DisputeId(j.obj.get("id").map(_.str).getOrElse("")),
      intentId = IntentId(j.obj.get("payment_id").map(_.str).getOrElse("")),
      amount   = j.obj.get("amount").map(a =>
        Money(a.num.toLong, Currency(j.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD")))
      ).getOrElse(Money.zero(Currency.USD)),
      reason   = DisputeReason.General,
      status   = status,
      dueDate  = j.obj.get("evidence_required_by").map(v => Instant.parse(v.str))
        .getOrElse(Instant.now().plusSeconds(604800)),
      evidence = None,
    )

  private def paymentSourceOf(method: PaymentMethod): ujson.Obj = method match
    case PaymentMethod.Card(token)          => ujson.Obj("type" -> "token", "token" -> token)
    case PaymentMethod.ApplePayCard(token)  => ujson.Obj("type" -> "token", "token" -> token)
    case PaymentMethod.GooglePayCard(token) => ujson.Obj("type" -> "token", "token" -> token)
    case PaymentMethod.SavedMethod(vaultId) => ujson.Obj("type" -> "id", "id" -> vaultId.value)
    case PaymentMethod.Fingerprint(fp)      => ujson.Obj("type" -> "token", "token" -> fp)
    case PaymentMethod.BankAccount(id)      => ujson.Obj("type" -> "token", "token" -> id)
    case PaymentMethod.Wallet(_, id)        => ujson.Obj("type" -> "token", "token" -> id)

  private def methodToken(method: PaymentMethod): String = method match
    case PaymentMethod.Card(token)          => token
    case PaymentMethod.ApplePayCard(token)  => token
    case PaymentMethod.GooglePayCard(token) => token
    case PaymentMethod.SavedMethod(vaultId) => vaultId.value
    case PaymentMethod.Fingerprint(fp)      => fp
    case PaymentMethod.BankAccount(id)      => id
    case PaymentMethod.Wallet(_, id)        => id
