package scalascript.payments.stripe

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** Stripe PaymentProvider adapter. Implements all 14 SPI methods via Stripe REST API v1.
 *  API version pinned to 2024-11-20. */
class StripeProvider(secretKey: String, override val mode: PaymentMode = PaymentMode.Test)
    extends PaymentProvider:

  private val baseUrl     = "https://api.stripe.com/v1"
  private val apiVersion  = "2024-11-20"
  private val http        = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  init()

  def id:          String = "stripe"
  def displayName: String = "Stripe"
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

  // ── Group 1: PaymentIntent ─────────────────────────────────────────────────

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "amount"   -> req.amount.minorUnits.toString
    params += "currency" -> req.amount.currency.code.toLowerCase
    if req.confirm then params += "confirm" -> "true"
    req.customer.foreach(c => params += "customer" -> c.value)
    req.method.foreach(m => addMethodParams(params, m))
    req.description.foreach(d => params += "description" -> d)
    req.returnUrl.foreach(u => params += "return_url" -> u)
    params += "capture_method" -> (if req.captureMethod == CaptureMethod.Manual then "manual" else "automatic")
    req.setupFutureUsage.foreach {
      case SetupFutureUsage.OnSession  => params += "setup_future_usage" -> "on_session"
      case SetupFutureUsage.OffSession => params += "setup_future_usage" -> "off_session"
    }
    if req.offSession then params += "off_session" -> "true"
    req.mandateId.foreach(m => params += "mandate" -> m.value)
    if req.scaExemptions.nonEmpty then
      val exemption = req.scaExemptions.head match
        case ScaExemption.LowValue              => "none"
        case ScaExemption.TrustedListing        => "any"
        case ScaExemption.TransactionRiskAnalysis => "challenge"
        case ScaExemption.Recurring             => "none"
        case ScaExemption.MerchantInitiated     => "none"
      params += "payment_method_options[card][request_three_d_secure]" -> exemption
    req.metadata.foreach { (k, v) => params += s"metadata[$k]" -> v }
    val json = post("/payment_intents", params.toList)
    parseIntent(json)

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    addMethodParams(params, method)
    val json = post(s"/payment_intents/${id.value}/confirm", params.toList)
    parseIntent(json)

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val params = amount.toList.map(m => "amount_to_capture" -> m.minorUnits.toString)
    val json = post(s"/payment_intents/${id.value}/capture", params)
    parseIntent(json)

  def voidIntent(id: IntentId): Unit =
    post(s"/payment_intents/${id.value}/cancel", List.empty)
    ()

  // ── Group 2: Customer + Vault ──────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "email" -> req.email
    req.name.foreach(n => params += "name" -> n)
    req.metadata.foreach { (k, v) => params += s"metadata[$k]" -> v }
    val json = post("/customers", params.toList)
    parseCustomer(json)

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    addMethodParams(params, method)
    val json = post(s"/customers/${customerId.value}/payment_methods", params.toList)
    parseStoredMethod(json)

  def detachMethod(vaultId: VaultId): Unit =
    post(s"/payment_methods/${vaultId.value}/detach", List.empty)
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val json = get(s"/customers/${customerId.value}/payment_methods?type=card&limit=100")
    json("data").arr.toList.map(parseStoredMethod)

  // ── Group 3: Subscriptions ─────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "amount"   -> req.amount.minorUnits.toString
    params += "currency" -> req.amount.currency.code.toLowerCase
    addIntervalParams(params, req.interval)
    req.trialPeriodDays.foreach(d => params += "trial_period_days" -> d.toString)
    req.metadata.foreach { (k, v) => params += s"metadata[$k]" -> v }
    val json = post("/plans", params.toList)
    parsePlan(json)

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "customer"                   -> customerId.value
    params += "items[0][plan]"             -> planId.value
    opts.trialPeriodDays.foreach(d => params += "trial_period_days" -> d.toString)
    opts.defaultMethod.foreach(v => params += "default_payment_method" -> v.value)
    opts.metadata.foreach { (k, v) => params += s"metadata[$k]" -> v }
    val json = post("/subscriptions", params.toList)
    parseSubscription(json)

  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription =
    val proration = mode match
      case ProrationMode.CreateProration => "create_prorations"
      case ProrationMode.AlwaysInvoice   => "always_invoice"
      case ProrationMode.None            => "none"
    val params = List(
      "items[0][plan]"   -> newPlanId.value,
      "proration_behavior" -> proration,
    )
    val json = post(s"/subscriptions/${id.value}", params)
    parseSubscription(json)

  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription =
    val params = if atPeriodEnd then List("cancel_at_period_end" -> "true") else List.empty
    val json   = if atPeriodEnd then post(s"/subscriptions/${id.value}", params)
                 else delete(s"/subscriptions/${id.value}")
    parseSubscription(json)

  // ── Group 4: Refunds + Disputes ────────────────────────────────────────────

  def refund(req: RefundRequest): Refund =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "payment_intent" -> req.intentId.value
    req.amount.foreach(m => params += "amount" -> m.minorUnits.toString)
    params += "reason" -> encodeRefundReason(req.reason)
    val json = post("/refunds", params.toList)
    parseRefund(json)

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    evidence.customerCommunication.foreach(v => params += "evidence[customer_communication]" -> v)
    evidence.receipt.foreach(v => params += "evidence[receipt]" -> v)
    evidence.shippingDocumentation.foreach(v => params += "evidence[shipping_documentation]" -> v)
    evidence.uncategorizedText.foreach(v => params += "evidence[uncategorized_text]" -> v)
    evidence.serviceDocumentation.foreach(v => params += "evidence[service_documentation]" -> v)
    val json = post(s"/disputes/${disputeId.value}", params.toList)
    parseDispute(json)

  // ── Group 2b: Mandates ─────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    val params = scala.collection.mutable.ListBuffer[(String, String)]()
    params += "customer"           -> customerId.value
    params += "payment_method"     -> vaultId.value
    params += "usage"              -> (if mandateType == MandateType.SingleUse then "on_session" else "off_session")
    params += "confirm"            -> "true"
    params += "automatic_payment_methods[enabled]" -> "true"
    params += "automatic_payment_methods[allow_redirects]" -> "never"
    val json      = post("/setup_intents", params.toList)
    val mandateId = json.obj.get("mandate").flatMap {
      case ujson.Str(s) => Some(s)
      case obj          => obj.obj.get("id").map(_.str)
    }.getOrElse("")
    Mandate(
      id          = MandateId(mandateId),
      status      = MandateStatus.Pending,
      mandateType = mandateType,
      customerId  = Some(customerId),
      vaultId     = Some(vaultId),
      providerRef = json.obj.get("id").map(_.str),
    )

  def getMandate(id: MandateId): Mandate =
    val json   = get(s"/mandates/${id.value}")
    val status = json.obj.get("status").map(_.str).getOrElse("active") match
      case "active"   => MandateStatus.Active
      case "inactive" => MandateStatus.Inactive
      case _          => MandateStatus.Pending
    val mType = json.obj.get("type").map(_.str).getOrElse("multi_use") match
      case "single_use" => MandateType.SingleUse
      case _            => MandateType.MultiUse
    val pmId   = json.obj.get("payment_method").flatMap {
      case ujson.Str(s) => Some(s)
      case obj          => obj.obj.get("id").map(_.str)
    }
    Mandate(
      id          = id,
      status      = status,
      mandateType = mType,
      vaultId     = pmId.map(VaultId(_)),
      providerRef = json.obj.get("id").map(_.str),
    )

  // ── Group 5: Webhooks ──────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = StripeWebhookReceiver()

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private def post(path: String, params: List[(String, String)]): ujson.Value =
    val body = formEncode(params)
    val req  = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",     s"Bearer $secretKey")
      .header("Stripe-Version",    apiVersion)
      .header("Content-Type",      "application/x-www-form-urlencoded")
      .POST(JHttpRequest.BodyPublishers.ofString(body))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def get(path: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",  s"Bearer $secretKey")
      .header("Stripe-Version", apiVersion)
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def delete(path: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",  s"Bearer $secretKey")
      .header("Stripe-Version", apiVersion)
      .DELETE()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def parseResponse(resp: JHttpResponse[String]): ujson.Value =
    val json = ujson.read(resp.body())
    if resp.statusCode() >= 400 then
      val err = json.obj.get("error").getOrElse(json)
      val code = err.obj.get("code").map(_.str).getOrElse("unknown")
      val msg  = err.obj.get("message").map(_.str).getOrElse(resp.body())
      resp.statusCode() match
        case 429 =>
          val retryOpt = resp.headers().firstValueAsLong("Retry-After")
          val retryAfter: Option[Duration] =
            if retryOpt.isPresent then Some(Duration.ofSeconds(retryOpt.getAsLong)) else None
          throw RateLimitExceeded(retryAfter)
        case _ if code == "card_declined" =>
          val declineCode = err.obj.get("decline_code").map(_.str).getOrElse(code)
          val policy = if declineCode.contains("do_not_retry") then RetryPolicy.DoNotRetry
                       else RetryPolicy.RetryNow
          throw CardDeclined(declineCode, msg, policy)
        case _ if code == "insufficient_funds" =>
          throw InsufficientFunds(msg)
        case _ if code == "payment_intent_authentication_failure" || code == "authentication_required" =>
          val challenge = SCAChallenge("stripe", "", "", "")
          throw AuthenticationRequired(challenge)
        case _ =>
          throw PermanentProviderError(code, msg)
    json

  private def formEncode(params: List[(String, String)]): String =
    params.map { (k, v) =>
      URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
    }.mkString("&")

  // ── Parse helpers ──────────────────────────────────────────────────────────

  private def parseIntent(j: ujson.Value): PaymentIntent =
    val id     = IntentId(j("id").str)
    val amount = parseMoney(j)
    j("status").str match
      case "requires_payment_method" => PaymentIntent.RequiresPaymentMethod(id, amount)
      case "requires_confirmation"   => PaymentIntent.RequiresConfirmation(id, amount, PaymentMethod.Card(""))
      case "requires_action"         =>
        val nextAction = j.obj.get("next_action").getOrElse(ujson.Null)
        val url = nextAction.obj.get("redirect_to_url").flatMap(_.obj.get("url")).map(_.str).getOrElse("")
        val ret = nextAction.obj.get("redirect_to_url").flatMap(_.obj.get("return_url")).map(_.str).getOrElse("")
        val fp  = j.obj.get("client_secret").map(_.str).getOrElse("")
        PaymentIntent.RequiresAction(id, amount, SCAChallenge("stripe", url, ret, fp))
      case "processing"              => PaymentIntent.Processing(id, amount)
      case "succeeded"               =>
        val chargeId = j.obj.get("latest_charge").flatMap {
          case ujson.Str(s) => Some(ChargeId(s))
          case obj          => obj.obj.get("id").map(v => ChargeId(v.str))
        }.getOrElse(ChargeId(""))
        val receiptUrl = j.obj.get("latest_charge").flatMap {
          case ujson.Obj(o) => o.get("receipt_url").map(_.str)
          case _            => None
        }
        val charge = Charge(chargeId, id, amount, paid = true, receiptUrl)
        PaymentIntent.Succeeded(id, amount, charge)
      case "canceled"                =>
        PaymentIntent.Canceled(id, CancelReason.Other(j.obj.get("cancellation_reason").map(_.str).getOrElse("")))
      case _                         =>
        PaymentIntent.Failed(id, PermanentProviderError("unknown_status", j("status").str), false)

  private def parseMoney(j: ujson.Value): Money =
    val amount   = j("amount").num.toLong
    val currency = Currency(j("currency").str.toUpperCase)
    Money(amount, currency)

  private def parseCustomer(j: ujson.Value): Customer =
    Customer(
      id       = CustomerId(j("id").str),
      email    = j("email").str,
      name     = j.obj.get("name").flatMap { case ujson.Str(s) => Some(s); case _ => None },
      metadata = j.obj.get("metadata").map(m => m.obj.toMap.view.mapValues(_.str).toMap).getOrElse(Map.empty),
    )

  private def parseStoredMethod(j: ujson.Value): StoredMethod =
    val card = j.obj.get("card").getOrElse(j)
    val networkToken = card.obj.get("networks").flatMap(_.obj.get("preferred")).flatMap {
      case ujson.Str(s) if s.nonEmpty => Some(s)
      case _                          => None
    }.orElse(card.obj.get("tokenization_method").flatMap {
      case ujson.Str(s) if s.nonEmpty && s != "null" => Some(s)
      case _                                          => None
    })
    StoredMethod(
      vaultId      = VaultId(j("id").str),
      last4        = card("last4").str,
      brand        = card("brand").str,
      expMonth     = card("exp_month").num.toInt.toString,
      expYear      = card("exp_year").num.toInt.toString,
      funding      = card.obj.get("funding").map(_.str).getOrElse("unknown"),
      networkToken = networkToken,
    )

  private def parsePlan(j: ujson.Value): Plan =
    val amount   = j("amount").num.toLong
    val currency = Currency(j("currency").str.toUpperCase)
    Plan(
      id              = PlanId(j("id").str),
      amount          = Money(amount, currency),
      interval        = parseInterval(j("interval").str, j.obj.get("interval_count").map(_.num.toInt).getOrElse(1)),
      trialPeriodDays = j.obj.get("trial_period_days").flatMap { case ujson.Num(n) => Some(n.toInt); case _ => None },
      metadata        = j.obj.get("metadata").map(m => m.obj.toMap.view.mapValues(_.str).toMap).getOrElse(Map.empty),
    )

  private def parseSubscription(j: ujson.Value): Subscription =
    val planId = j.obj.get("items").flatMap(_.obj.get("data"))
      .flatMap(_.arr.headOption)
      .flatMap(v => v.obj.get("plan").orElse(v.obj.get("price")))
      .flatMap(_.obj.get("id"))
      .map(v => PlanId(v.str))
      .getOrElse(PlanId(""))
    Subscription(
      id               = SubscriptionId(j("id").str),
      customerId       = CustomerId(j("customer").str),
      planId           = planId,
      status           = parseSubStatus(j("status").str),
      currentPeriodEnd = Instant.ofEpochSecond(j("current_period_end").num.toLong),
      cancelAtPeriodEnd = j.obj.get("cancel_at_period_end").exists(_.bool),
      trialEnd         = j.obj.get("trial_end").flatMap {
        case ujson.Num(n) => Some(Instant.ofEpochSecond(n.toLong))
        case _            => None
      },
    )

  private def parseRefund(j: ujson.Value): Refund =
    val currency = Currency(j.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD"))
    Refund(
      id       = RefundId(j("id").str),
      intentId = IntentId(j("payment_intent").str),
      amount   = Money(j("amount").num.toLong, currency),
      reason   = decodeRefundReason(j.obj.get("reason").map(_.str).getOrElse("")),
      status   = decodeRefundStatus(j("status").str),
    )

  private def parseDispute(j: ujson.Value): Dispute =
    val currency = Currency(j.obj.get("currency").map(_.str.toUpperCase).getOrElse("USD"))
    Dispute(
      id       = DisputeId(j("id").str),
      intentId = IntentId(j.obj.get("payment_intent").map(_.str).getOrElse("")),
      amount   = Money(j("amount").num.toLong, currency),
      reason   = decodeDisputeReason(j.obj.get("reason").map(_.str).getOrElse("")),
      status   = decodeDisputeStatus(j("status").str),
      dueDate  = Instant.ofEpochSecond(j.obj.get("evidence_due_by").map(_.num.toLong).getOrElse(0L)),
      evidence = None,
    )

  private def parseInterval(s: String, count: Int): BillingInterval = s match
    case "day"   => BillingInterval.Daily(count)
    case "week"  => BillingInterval.Weekly(count)
    case "month" => BillingInterval.Monthly(count)
    case "year"  => BillingInterval.Yearly(count)
    case _       => BillingInterval.Monthly(count)

  private def addIntervalParams(
      params: scala.collection.mutable.ListBuffer[(String, String)],
      interval: BillingInterval
  ): Unit =
    val (name, count) = interval match
      case BillingInterval.Daily(c)   => ("day", c)
      case BillingInterval.Weekly(c)  => ("week", c)
      case BillingInterval.Monthly(c) => ("month", c)
      case BillingInterval.Yearly(c)  => ("year", c)
    params += "interval"       -> name
    params += "interval_count" -> count.toString

  private def addMethodParams(
      params: scala.collection.mutable.ListBuffer[(String, String)],
      method: PaymentMethod
  ): Unit = method match
    case PaymentMethod.Card(token)         => params += "payment_method" -> token
    case PaymentMethod.ApplePayCard(token) => params += "payment_method" -> token
    case PaymentMethod.GooglePayCard(token)=> params += "payment_method" -> token
    case PaymentMethod.SavedMethod(vaultId)=> params += "payment_method" -> vaultId.value
    case PaymentMethod.Fingerprint(fp)     => params += "payment_method" -> fp
    case PaymentMethod.BankAccount(id)     => params += "payment_method" -> id
    case PaymentMethod.Wallet(_, id)       => params += "payment_method" -> id

  private def parseSubStatus(s: String): SubscriptionStatus = s match
    case "trialing"  => SubscriptionStatus.Trialing
    case "active"    => SubscriptionStatus.Active
    case "past_due"  => SubscriptionStatus.PastDue
    case "canceled"  => SubscriptionStatus.Canceled
    case "unpaid"    => SubscriptionStatus.Unpaid
    case "paused"    => SubscriptionStatus.Paused
    case _           => SubscriptionStatus.Active

  private def encodeRefundReason(r: RefundReason): String = r match
    case RefundReason.Duplicate              => "duplicate"
    case RefundReason.Fraudulent             => "fraudulent"
    case RefundReason.RequestedByCustomer    => "requested_by_customer"
    case RefundReason.Other(_)               => "requested_by_customer"

  private def decodeRefundReason(s: String): RefundReason = s match
    case "duplicate"             => RefundReason.Duplicate
    case "fraudulent"            => RefundReason.Fraudulent
    case "requested_by_customer" => RefundReason.RequestedByCustomer
    case _                       => RefundReason.RequestedByCustomer

  private def decodeRefundStatus(s: String): RefundStatus = s match
    case "pending"   => RefundStatus.Pending
    case "succeeded" => RefundStatus.Succeeded
    case "failed"    => RefundStatus.Failed
    case "canceled"  => RefundStatus.Canceled
    case _           => RefundStatus.Pending

  private def decodeDisputeReason(s: String): DisputeReason = s match
    case "fraudulent"              => DisputeReason.Fraudulent
    case "duplicate"               => DisputeReason.Duplicate
    case "subscription_canceled"   => DisputeReason.SubscriptionCanceled
    case "product_not_received"    => DisputeReason.ProductNotReceived
    case "product_unacceptable"    => DisputeReason.ProductUnacceptable
    case "unrecognized"            => DisputeReason.Unrecognized
    case "credit_not_processed"    => DisputeReason.CreditNotProcessed
    case _                         => DisputeReason.General

  private def decodeDisputeStatus(s: String): DisputeStatus = s match
    case "needs_response" => DisputeStatus.NeedsResponse
    case "under_review"   => DisputeStatus.UnderReview
    case "won"            => DisputeStatus.Won
    case "lost"           => DisputeStatus.Lost
    case "warning_closed" => DisputeStatus.WarningClosed
    case _                => DisputeStatus.NeedsResponse

  private def init(): Unit =
    val expectedPrefix = if mode == PaymentMode.Live then "sk_live_" else "sk_test_"
    if !secretKey.startsWith(expectedPrefix) && !secretKey.startsWith("sk_") then
      throw IllegalArgumentException(
        s"PaymentMode mismatch: mode=$mode but key prefix '${secretKey.take(8)}...' is unexpected"
      )
