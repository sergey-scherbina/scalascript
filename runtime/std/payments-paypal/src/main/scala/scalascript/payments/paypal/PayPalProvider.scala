package scalascript.payments.paypal

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** PayPal Checkout PaymentProvider adapter.
 *  Auth: OAuth2 client-credentials (client_id + secret → bearer, 8h TTL).
 *  API: PayPal Orders v2 (https://api-m.paypal.com/v2/checkout/orders).
 *  Webhook: RSA-SHA256 over `transmission_id + timestamp + webhook_id + body_crc32`. */
class PayPalProvider(
    clientId:  String,
    secret:    String,
    webhookId: String = "",
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  private val baseUrl = if mode == PaymentMode.Live then "https://api-m.paypal.com"
                        else "https://api-m.sandbox.paypal.com"
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  @volatile private var tokenCache: Option[(String, Long)] = None

  def id:          String = "paypal"
  def displayName: String = "PayPal"
  def spiVersion:  String = "1.53.5"

  def capabilities: PaymentCapabilities = PaymentCapabilities(
    supportsSubscriptions  = true,
    supportsSCA            = false,
    supports3DS2           = false,
    supportsApplePay       = false,
    supportsGooglePay      = true,
    supportsRefunds        = true,
    supportsPartialRefunds = true,
    supportsDisputes       = true,
    supportsMultiCurrency  = true,
    supportsMandates       = true,
  )

  // ── Group 1: PaymentIntent (mapped to PayPal Orders) ─────────────────────

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val intent = if req.captureMethod == CaptureMethod.Manual then "AUTHORIZE" else "CAPTURE"
    val body = ujson.Obj(
      "intent" -> intent,
      "purchase_units" -> ujson.Arr(ujson.Obj(
        "amount" -> ujson.Obj(
          "currency_code" -> req.amount.currency.code,
          "value"         -> formatAmount(req.amount),
        )
      )),
    )
    req.description.foreach(d => body("purchase_units").arr(0)("description") = d)
    if req.offSession then
      body("payment_source") = ujson.Obj(
        "card" -> ujson.Obj("stored_credential" -> ujson.Obj(
          "payment_initiator" -> "MERCHANT",
          "usage" -> "SUBSEQUENT",
          "previous_network_transaction_reference" -> req.mandateId.map(_.value).getOrElse(""),
        ))
      )
    val json = postJson("/v2/checkout/orders", body)
    parseOrder(json)

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    val json = postJson(s"/v2/checkout/orders/${id.value}/confirm-payment-source",
      ujson.Obj("payment_source" -> paymentSourceOf(method)))
    parseOrder(json)

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val json = postJson(s"/v2/checkout/orders/${id.value}/capture", ujson.Obj())
    parseOrder(json)

  def voidIntent(id: IntentId): Unit =
    postJson(s"/v2/checkout/orders/${id.value}/cancel", ujson.Obj())
    ()

  // ── Group 2: Customer + Vault ─────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    val body = ujson.Obj("email_addresses" -> ujson.Arr(ujson.Obj("email_address" -> req.email)))
    req.name.foreach(n => body("name") = ujson.Obj("full_name" -> n))
    val json = postJson("/v3/vault/payment-tokens", body)
    Customer(
      id       = CustomerId(json.obj.get("id").map(_.str).getOrElse("")),
      email    = req.email,
      name     = req.name,
      metadata = Map.empty,
    )

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val body = ujson.Obj(
      "customer" -> ujson.Obj("id" -> customerId.value),
      "payment_source" -> paymentSourceOf(method),
    )
    val json = postJson("/v3/vault/payment-tokens", body)
    parseVaultToken(json)

  def detachMethod(vaultId: VaultId): Unit =
    deleteReq(s"/v3/vault/payment-tokens/${vaultId.value}")
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val json = getReq(s"/v3/vault/payment-tokens?customer_id=${customerId.value}")
    json.obj.get("payment_tokens").map(_.arr.toList.map(parseVaultToken)).getOrElse(List.empty)

  // ── Group 2b: Mandates ─────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    // PayPal mandates are created as setup tokens then vaulted
    val body = ujson.Obj(
      "customer"       -> ujson.Obj("id" -> customerId.value),
      "payment_source" -> ujson.Obj(
        "token" -> ujson.Obj("id" -> vaultId.value, "type" -> "PAYMENT_METHOD_TOKEN")
      ),
    )
    val json      = postJson("/v3/vault/setup-tokens", body)
    val mandateId = json.obj.get("id").map(_.str).getOrElse(s"paypal-mandate-${System.currentTimeMillis()}")
    Mandate(
      id          = MandateId(mandateId),
      status      = MandateStatus.Pending,
      mandateType = mandateType,
      customerId  = Some(customerId),
      vaultId     = Some(vaultId),
      providerRef = Some(mandateId),
    )

  def getMandate(id: MandateId): Mandate =
    val json   = getReq(s"/v3/vault/setup-tokens/${id.value}")
    val status = json.obj.get("status").map(_.str).getOrElse("CREATED") match
      case "APPROVED" | "VAULTED" => MandateStatus.Active
      case "PAYER_ACTION_REQUIRED" | "CREATED" => MandateStatus.Pending
      case _ => MandateStatus.Inactive
    Mandate(
      id          = id,
      status      = status,
      mandateType = MandateType.MultiUse,
      providerRef = json.obj.get("id").map(_.str),
    )

  // ── Group 3: Subscriptions ────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    val freq = req.interval match
      case BillingInterval.Daily(c)   => ujson.Obj("interval_unit" -> "DAY",   "interval_count" -> c)
      case BillingInterval.Weekly(c)  => ujson.Obj("interval_unit" -> "WEEK",  "interval_count" -> c)
      case BillingInterval.Monthly(c) => ujson.Obj("interval_unit" -> "MONTH", "interval_count" -> c)
      case BillingInterval.Yearly(c)  => ujson.Obj("interval_unit" -> "YEAR",  "interval_count" -> c)
    val body = ujson.Obj(
      "product_id"   -> "PROD-DEFAULT",
      "name"         -> req.metadata.getOrElse("name", s"Plan ${req.amount.toDecimal} ${req.amount.currency.code}"),
      "billing_cycles" -> ujson.Arr(ujson.Obj(
        "frequency"      -> freq,
        "tenure_type"    -> "REGULAR",
        "sequence"       -> 1,
        "pricing_scheme" -> ujson.Obj(
          "fixed_price" -> ujson.Obj(
            "value"         -> formatAmount(req.amount),
            "currency_code" -> req.amount.currency.code,
          )
        ),
      )),
      "payment_preferences" -> ujson.Obj("auto_bill_outstanding" -> true),
    )
    req.trialPeriodDays.foreach { d =>
      val trialCycle = ujson.Obj(
        "frequency"   -> ujson.Obj("interval_unit" -> "DAY", "interval_count" -> d),
        "tenure_type" -> "TRIAL",
        "sequence"    -> 0,
        "pricing_scheme" -> ujson.Obj(
          "fixed_price" -> ujson.Obj("value" -> "0", "currency_code" -> req.amount.currency.code)
        ),
      )
      body("billing_cycles").arr.prepend(trialCycle)
    }
    val json = postJson("/v1/billing/plans", body)
    Plan(
      id              = PlanId(json("id").str),
      amount          = req.amount,
      interval        = req.interval,
      trialPeriodDays = req.trialPeriodDays,
      metadata        = req.metadata,
    )

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    val body = ujson.Obj(
      "plan_id"    -> planId.value,
      "subscriber" -> ujson.Obj("payer_id" -> customerId.value),
    )
    opts.trialPeriodDays.foreach(d => body("start_time") =
      Instant.now().plusSeconds(d.toLong * 86400).toString)
    val json = postJson("/v1/billing/subscriptions", body)
    parseSubscription(json, planId)

  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription =
    val body = ujson.Obj("plan_id" -> newPlanId.value)
    postJson(s"/v1/billing/subscriptions/${id.value}/revise", body)
    val json = getReq(s"/v1/billing/subscriptions/${id.value}")
    parseSubscription(json, newPlanId)

  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription =
    val body = ujson.Obj("reason" -> "Canceled by customer")
    postJson(s"/v1/billing/subscriptions/${id.value}/cancel", body)
    val json = getReq(s"/v1/billing/subscriptions/${id.value}")
    parseSubscription(json, PlanId(""))

  // ── Group 4: Refunds + Disputes ───────────────────────────────────────────

  def refund(req: RefundRequest): Refund =
    val captureId = req.intentId.value
    val body = ujson.Obj("note_to_payer" -> "Refund")
    req.amount.foreach { m =>
      body("amount") = ujson.Obj("value" -> formatAmount(m), "currency_code" -> m.currency.code)
    }
    val json = postJson(s"/v2/payments/captures/$captureId/refund", body)
    val currency = json.obj.get("amount").flatMap(_.obj.get("currency_code"))
      .map(v => Currency(v.str.toUpperCase)).getOrElse(Currency.USD)
    val amount = json.obj.get("amount").flatMap(_.obj.get("value"))
      .map(v => Money(BigDecimal(v.str), currency)).getOrElse(req.amount.getOrElse(Money.zero(currency)))
    Refund(
      id       = RefundId(json("id").str),
      intentId = req.intentId,
      amount   = amount,
      reason   = req.reason,
      status   = RefundStatus.Succeeded,
    )

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val body = ujson.Obj()
    evidence.uncategorizedText.foreach(t => body("note") = t)
    postJson(s"/v1/customer/disputes/${disputeId.value}/provide-evidence", body)
    val json = getReq(s"/v1/customer/disputes/${disputeId.value}")
    parseDispute(json)

  // ── Group 5: Webhooks ─────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = PayPalWebhookReceiver(webhookId)

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def accessToken: String =
    val now = System.currentTimeMillis() / 1000
    tokenCache match
      case Some((tok, exp)) if exp > now + 60 => tok
      case _ =>
        val creds = java.util.Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
        val req   = JHttpRequest.newBuilder(URI.create(s"$baseUrl/v1/oauth2/token"))
          .header("Authorization",  s"Basic $creds")
          .header("Content-Type",   "application/x-www-form-urlencoded")
          .POST(JHttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
          .timeout(Duration.ofSeconds(30))
          .build()
        val resp  = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
        val json  = ujson.read(resp.body())
        if resp.statusCode() >= 400 then throw PermanentProviderError("oauth_failed", resp.body())
        val tok   = json("access_token").str
        val expiresIn = json.obj.get("expires_in").map(_.num.toLong).getOrElse(28800L)
        tokenCache = Some((tok, now + expiresIn))
        tok

  private def postJson(path: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",  s"Bearer ${accessToken}")
      .header("Content-Type",   "application/json")
      .header("PayPal-Request-Id", java.util.UUID.randomUUID().toString)
      .POST(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getReq(path: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer ${accessToken}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def deleteReq(path: String): Unit =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Bearer ${accessToken}")
      .DELETE()
      .timeout(Duration.ofSeconds(30))
      .build()
    http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    ()

  private def parseResponse(resp: JHttpResponse[String]): ujson.Value =
    if resp.body().isEmpty then return ujson.Obj()
    val json = ujson.read(resp.body())
    if resp.statusCode() >= 400 then
      val name    = json.obj.get("name").map(_.str).getOrElse("UNKNOWN")
      val message = json.obj.get("message").map(_.str).getOrElse(resp.body())
      resp.statusCode() match
        case 429 =>
          val retryOpt = resp.headers().firstValueAsLong("Retry-After")
          val retryAfter = if retryOpt.isPresent then Some(Duration.ofSeconds(retryOpt.getAsLong)) else None
          throw RateLimitExceeded(retryAfter)
        case _ if name == "INSTRUMENT_DECLINED" || name == "CARD_DECLINED" =>
          throw CardDeclined(name, message, RetryPolicy.DoNotRetry)
        case _ if name == "PAYER_ACTION_REQUIRED" =>
          throw AuthenticationRequired(SCAChallenge("paypal", "", "", ""))
        case _ =>
          throw PermanentProviderError(name, message)
    json

  // ── Parse helpers ─────────────────────────────────────────────────────────

  private def parseOrder(j: ujson.Value): PaymentIntent =
    val id      = IntentId(j("id").str)
    val units   = j.obj.get("purchase_units").flatMap(_.arr.headOption)
    val amount  = units.flatMap(u => u.obj.get("amount")).map { a =>
      val code = a("currency_code").str.toUpperCase
      Money(BigDecimal(a("value").str), Currency(code))
    }.getOrElse(Money.zero(Currency.USD))
    j("status").str match
      case "CREATED" | "SAVED"   => PaymentIntent.RequiresPaymentMethod(id, amount)
      case "APPROVED"            => PaymentIntent.RequiresConfirmation(id, amount, PaymentMethod.Wallet("paypal", ""))
      case "VOIDED"              => PaymentIntent.Canceled(id, CancelReason.Other("VOIDED"))
      case "COMPLETED"           =>
        val captureId = units.flatMap(_.obj.get("payments"))
          .flatMap(_.obj.get("captures")).flatMap(_.arr.headOption)
          .flatMap(_.obj.get("id")).map(v => ChargeId(v.str)).getOrElse(ChargeId(""))
        PaymentIntent.Succeeded(id, amount, Charge(captureId, id, amount, paid = true))
      case "PAYER_ACTION_REQUIRED" =>
        val link = j.obj.get("links").flatMap(_.arr.find(l => l("rel").str == "payer-action"))
          .flatMap(_.obj.get("href")).map(_.str).getOrElse("")
        PaymentIntent.RequiresAction(id, amount, SCAChallenge("paypal", link, "", id.value))
      case _ =>
        PaymentIntent.Processing(id, amount)

  private def parseSubscription(j: ujson.Value, planId: PlanId): Subscription =
    val status = j.obj.get("status").map(_.str).getOrElse("ACTIVE") match
      case "APPROVAL_PENDING" | "APPROVED" => SubscriptionStatus.Active
      case "ACTIVE"           => SubscriptionStatus.Active
      case "SUSPENDED"        => SubscriptionStatus.PastDue
      case "CANCELLED"        => SubscriptionStatus.Canceled
      case "EXPIRED"          => SubscriptionStatus.Unpaid
      case _                  => SubscriptionStatus.Active
    val periodEnd = j.obj.get("billing_info").flatMap(_.obj.get("next_billing_time"))
      .map(v => Instant.parse(v.str)).getOrElse(Instant.now().plusSeconds(2592000))
    Subscription(
      id               = SubscriptionId(j("id").str),
      customerId       = CustomerId(j.obj.get("subscriber").flatMap(_.obj.get("payer_id")).map(_.str).getOrElse("")),
      planId           = planId,
      status           = status,
      currentPeriodEnd = periodEnd,
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )

  private def parseDispute(j: ujson.Value): Dispute =
    val disputeId = j.obj.get("dispute_id").orElse(j.obj.get("id")).map(_.str).getOrElse("")
    val amount    = j.obj.get("dispute_amount").map { a =>
      val code = a("currency_code").str.toUpperCase
      Money(BigDecimal(a("value").str), Currency(code))
    }.getOrElse(Money.zero(Currency.USD))
    val status = j.obj.get("status").map(_.str).getOrElse("WAITING_FOR_SELLER_RESPONSE") match
      case "WAITING_FOR_SELLER_RESPONSE" => DisputeStatus.NeedsResponse
      case "UNDER_REVIEW"                => DisputeStatus.UnderReview
      case "RESOLVED"                    => DisputeStatus.Won
      case _                             => DisputeStatus.NeedsResponse
    Dispute(
      id       = DisputeId(disputeId),
      intentId = IntentId(j.obj.get("disputed_transactions").flatMap(_.arr.headOption)
        .flatMap(_.obj.get("order_id")).map(_.str).getOrElse("")),
      amount   = amount,
      reason   = DisputeReason.General,
      status   = status,
      dueDate  = Instant.now().plusSeconds(604800),
      evidence = None,
    )

  private def parseVaultToken(j: ujson.Value): StoredMethod =
    val card = j.obj.get("payment_source").flatMap(_.obj.get("card")).getOrElse(j)
    val networkToken = card.obj.get("network_token").flatMap {
      case ujson.Str(s) if s.nonEmpty => Some(s)
      case _                          => None
    }.orElse(card.obj.get("network_transaction_reference").flatMap(_.obj.get("id")).flatMap {
      case ujson.Str(s) if s.nonEmpty => Some(s)
      case _                          => None
    })
    StoredMethod(
      vaultId      = VaultId(j.obj.get("id").map(_.str).getOrElse("")),
      last4        = card.obj.get("last_digits").map(_.str).getOrElse(""),
      brand        = card.obj.get("brand").map(_.str).getOrElse("unknown"),
      expMonth     = card.obj.get("expiry").map(_.str.take(7).drop(5)).getOrElse(""),
      expYear      = card.obj.get("expiry").map(_.str.take(4)).getOrElse(""),
      funding      = "credit",
      networkToken = networkToken,
    )

  private def paymentSourceOf(method: PaymentMethod): ujson.Obj = method match
    case PaymentMethod.Card(token)          => ujson.Obj("card" -> ujson.Obj("single_use_token" -> token))
    case PaymentMethod.ApplePayCard(token)  => ujson.Obj("apple_pay" -> ujson.Obj("token" -> token))
    case PaymentMethod.GooglePayCard(token) => ujson.Obj("google_pay" -> ujson.Obj("token" -> token))
    case PaymentMethod.SavedMethod(vaultId) => ujson.Obj("token" -> ujson.Obj("id" -> vaultId.value, "type" -> "PAYMENT_METHOD_TOKEN"))
    case PaymentMethod.Fingerprint(fp)      => ujson.Obj("card" -> ujson.Obj("single_use_token" -> fp))
    case PaymentMethod.BankAccount(id)      => ujson.Obj("bank_account" -> ujson.Obj("token" -> id))
    case PaymentMethod.Wallet(_, id)        => ujson.Obj("token" -> ujson.Obj("id" -> id, "type" -> "PAYMENT_METHOD_TOKEN"))

  private def formatAmount(m: Money): String =
    m.toDecimal.bigDecimal.toPlainString
