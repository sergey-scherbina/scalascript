package scalascript.payments.square

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** Square PaymentProvider adapter.
 *  Auth: Bearer <access_token> header.
 *  API: Square Payments API v2 (https://connect.squareup.com/v2).
 *  PCI: Web Payments SDK nonce (source_id token starting with `nonce_xxx`).
 *  Webhook: HMAC-SHA1 over notification_url + raw_body, base64 comparison.
 *  No SCA / 3DS2 / mandates. Subscriptions supported via `/v2/subscriptions`. */
class SquareProvider(
    accessToken:     String,
    locationId:      String,
    webhookSignatureKey: String = "",
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  private val baseUrl = if mode == PaymentMode.Live then "https://connect.squareup.com/v2"
                        else "https://connect.squareupsandbox.com/v2"
  private val http    = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def id:          String = "square"
  def displayName: String = "Square"
  def spiVersion:  String = "1.53.4"

  def capabilities: PaymentCapabilities = PaymentCapabilities(
    supportsSubscriptions  = true,
    supportsSCA            = false,
    supports3DS2           = false,
    supportsApplePay       = true,
    supportsGooglePay      = true,
    supportsRefunds        = true,
    supportsPartialRefunds = true,
    supportsDisputes       = true,
    supportsMultiCurrency  = false,
    supportsMandates       = false,
  )

  // ── Group 1: PaymentIntent ────────────────────────────────────────────────

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-${System.currentTimeMillis()}",
      "source_id"       -> req.method.map(methodToken).getOrElse("nonce_placeholder"),
      "amount_money"    -> ujson.Obj("amount" -> req.amount.minorUnits, "currency" -> req.amount.currency.code),
      "location_id"     -> locationId,
    )
    req.description.foreach(d => body("note") = d)
    if req.captureMethod == CaptureMethod.Manual then body("autocomplete") = false
    val json = postJson(s"$baseUrl/payments", body)
    parsePaymentResult(json.obj.get("payment").getOrElse(json))

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-confirm-${System.currentTimeMillis()}",
      "source_id"       -> methodToken(method),
    )
    val json = patchJson(s"$baseUrl/payments/${URLEncoder.encode(id.value, "UTF-8")}/complete", body)
    parsePaymentResult(json.obj.get("payment").getOrElse(json))

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val body = ujson.Obj()
    amount.foreach { m =>
      body("amount_money") = ujson.Obj("amount" -> m.minorUnits, "currency" -> m.currency.code)
    }
    val json     = postJson(s"$baseUrl/payments/${URLEncoder.encode(id.value, "UTF-8")}/complete", body)
    val payment  = json.obj.get("payment").getOrElse(json)
    val status   = payment.obj.get("status").map(_.str).getOrElse("")
    val captured = payment.obj.get("amount_money").map { a =>
      Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
    }.orElse(amount).getOrElse(Money.zero(Currency.USD))
    if status == "COMPLETED" then
      PaymentIntent.Succeeded(id, captured, Charge(ChargeId(id.value), id, captured, paid = true))
    else
      PaymentIntent.Processing(id, captured)

  def voidIntent(id: IntentId): Unit =
    postJson(s"$baseUrl/payments/${URLEncoder.encode(id.value, "UTF-8")}/cancel", ujson.Obj())
    ()

  // ── Group 2: Customer + Vault ─────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-cust-${System.currentTimeMillis()}",
      "email_address"   -> req.email,
    )
    req.name.foreach { n =>
      val parts = n.split(" ", 2)
      body("given_name")  = parts.headOption.getOrElse(n)
      body("family_name") = if parts.length > 1 then parts(1) else ""
    }
    val json = postJson(s"$baseUrl/customers", body)
    val cust = json.obj.get("customer").getOrElse(json)
    Customer(
      id       = CustomerId(cust.obj.get("id").map(_.str).getOrElse("")),
      email    = req.email,
      name     = req.name,
      metadata = req.metadata,
    )

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-card-${System.currentTimeMillis()}",
      "source_id"       -> methodToken(method),
      "card"            -> ujson.Obj("customer_id" -> customerId.value),
    )
    val json = postJson(s"$baseUrl/cards", body)
    parseStoredMethod(json.obj.get("card").getOrElse(json))

  def detachMethod(vaultId: VaultId): Unit =
    postJson(s"$baseUrl/cards/${URLEncoder.encode(vaultId.value, "UTF-8")}/disable", ujson.Obj())
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val json = getJson(s"$baseUrl/cards?customer_id=${URLEncoder.encode(customerId.value, "UTF-8")}")
    json.obj.get("cards").map(_.arr.toList.map(parseStoredMethod)).getOrElse(List.empty)

  // ── Group 3: Subscriptions ────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    val cadence = intervalCadence(req.interval)
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-plan-${System.currentTimeMillis()}",
      "object" -> ujson.Obj(
        "type" -> "SUBSCRIPTION_PLAN",
        "id"   -> s"#plan-${req.amount.minorUnits}-${req.amount.currency.code}",
        "subscription_plan_data" -> ujson.Obj(
          "name" -> s"Plan ${req.amount.minorUnits} ${req.amount.currency.code}",
          "phases" -> ujson.Arr(ujson.Obj(
            "cadence"  -> cadence,
            "recurring_price_money" -> ujson.Obj(
              "amount" -> req.amount.minorUnits, "currency" -> req.amount.currency.code,
            ),
          )),
        ),
      ),
    )
    val json   = postJson(s"$baseUrl/catalog/object", body)
    val planId = json.obj.get("catalog_object").flatMap(_.obj.get("id")).map(_.str)
      .getOrElse(s"square-plan-${req.amount.minorUnits}-${req.amount.currency.code}")
    Plan(
      id              = PlanId(planId),
      amount          = req.amount,
      interval        = req.interval,
      trialPeriodDays = req.trialPeriodDays,
      metadata        = req.metadata,
    )

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    val body = ujson.Obj(
      "idempotency_key"   -> s"ik-sub-${System.currentTimeMillis()}",
      "location_id"       -> locationId,
      "customer_id"       -> customerId.value,
      "plan_variation_id" -> planId.value,
    )
    opts.defaultMethod.foreach(v => body("card_id") = v.value)
    val json  = postJson(s"$baseUrl/subscriptions", body)
    val sub   = json.obj.get("subscription").getOrElse(json)
    val subId = sub.obj.get("id").map(_.str).getOrElse(s"sq-sub-${System.currentTimeMillis()}")
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
    val body = ujson.Obj(
      "subscription" -> ujson.Obj("plan_variation_id" -> newPlanId.value),
    )
    putJson(s"$baseUrl/subscriptions/${URLEncoder.encode(id.value, "UTF-8")}", body)
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
    postJson(s"$baseUrl/subscriptions/${URLEncoder.encode(id.value, "UTF-8")}/cancel", ujson.Obj())
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
    val currency = req.amount.map(_.currency).getOrElse(Currency.USD)
    val body = ujson.Obj(
      "idempotency_key" -> s"ik-refund-${System.currentTimeMillis()}",
      "payment_id"      -> req.intentId.value,
    )
    req.amount.foreach { m =>
      body("amount_money") = ujson.Obj("amount" -> m.minorUnits, "currency" -> m.currency.code)
    }
    val json     = postJson(s"$baseUrl/refunds", body)
    val refund   = json.obj.get("refund").getOrElse(json)
    val refundId = refund.obj.get("id").map(_.str).getOrElse("")
    Refund(
      id       = RefundId(refundId),
      intentId = req.intentId,
      amount   = req.amount.getOrElse(Money.zero(currency)),
      reason   = req.reason,
      status   = RefundStatus.Pending,
    )

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val body = ujson.Obj()
    evidence.uncategorizedText.foreach(t => body("evidence_text") = t)
    postJson(s"$baseUrl/disputes/${URLEncoder.encode(disputeId.value, "UTF-8")}/evidence-text", body)
    val json = getJson(s"$baseUrl/disputes/${URLEncoder.encode(disputeId.value, "UTF-8")}")
    parseDispute(json.obj.get("dispute").getOrElse(json), disputeId)

  // ── Group 5: Webhooks ─────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = SquareWebhookReceiver(webhookSignatureKey)

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def postJson(url: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("Authorization", s"Bearer $accessToken")
      .header("Content-Type",  "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def patchJson(url: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("Authorization", s"Bearer $accessToken")
      .header("Content-Type",  "application/json")
      .method("PATCH", JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def putJson(url: String, body: ujson.Value): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("Authorization", s"Bearer $accessToken")
      .header("Content-Type",  "application/json")
      .PUT(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getJson(url: String): ujson.Value =
    val req = JHttpRequest.newBuilder(URI.create(url))
      .header("Authorization", s"Bearer $accessToken")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def parseResponse(resp: JHttpResponse[String]): ujson.Value =
    if resp.body().isEmpty then return ujson.Obj()
    val json = ujson.read(resp.body())
    if resp.statusCode() >= 400 then
      val errors  = json.obj.get("errors").map(_.arr.toList).getOrElse(List.empty)
      val code    = errors.headOption.flatMap(_.obj.get("code")).map(_.str).getOrElse(resp.statusCode().toString)
      val message = errors.headOption.flatMap(_.obj.get("detail")).map(_.str).getOrElse(resp.body())
      resp.statusCode() match
        case 429 =>
          throw RateLimitExceeded(None)
        case _ if code == "CARD_DECLINED" || code == "INVALID_CARD" =>
          throw CardDeclined(code, message, RetryPolicy.DoNotRetry)
        case _ =>
          throw PermanentProviderError(code, message)
    json

  // ── Parse helpers ─────────────────────────────────────────────────────────

  private def parsePaymentResult(j: ujson.Value): PaymentIntent =
    val id     = IntentId(j.obj.get("id").map(_.str).getOrElse(""))
    val amount = j.obj.get("amount_money").map { a =>
      Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
    }.getOrElse(Money.zero(Currency.USD))
    j.obj.get("status").map(_.str).getOrElse("") match
      case "COMPLETED" | "APPROVED" =>
        PaymentIntent.Succeeded(id, amount, Charge(ChargeId(id.value), id, amount, paid = true))
      case "PENDING" =>
        PaymentIntent.Processing(id, amount)
      case "CANCELED" =>
        PaymentIntent.Canceled(id, CancelReason.Other("Canceled"))
      case "FAILED" =>
        val reason = j.obj.get("processing_fee").map(_.toString).getOrElse("payment failed")
        PaymentIntent.Failed(id, PermanentProviderError("FAILED", reason), retryable = false)
      case _ =>
        PaymentIntent.Processing(id, amount)

  private def parseStoredMethod(j: ujson.Value): StoredMethod =
    StoredMethod(
      vaultId  = VaultId(j.obj.get("id").map(_.str).getOrElse("")),
      last4    = j.obj.get("last_4").map(_.str).getOrElse(""),
      brand    = j.obj.get("card_brand").map(_.str).getOrElse("unknown"),
      expMonth = j.obj.get("exp_month").map(_.num.toInt.toString).getOrElse(""),
      expYear  = j.obj.get("exp_year").map(_.num.toInt.toString).getOrElse(""),
      funding  = j.obj.get("prepaid_type").map(_.str.toLowerCase).getOrElse("credit"),
    )

  private def parseDispute(j: ujson.Value, fallbackId: DisputeId): Dispute =
    val id = j.obj.get("id").map(_.str).getOrElse(fallbackId.value)
    Dispute(
      id       = DisputeId(id),
      intentId = IntentId(j.obj.get("payment_id").map(_.str).getOrElse("")),
      amount   = j.obj.get("amount_money").map { a =>
        Money(a("amount").num.toLong, Currency(a("currency").str.toUpperCase))
      }.getOrElse(Money.zero(Currency.USD)),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = j.obj.get("due_at").map(v => Instant.parse(v.str))
        .getOrElse(Instant.now().plusSeconds(604800)),
      evidence = None,
    )

  private def methodToken(method: PaymentMethod): String = method match
    case PaymentMethod.Card(token)          => token
    case PaymentMethod.ApplePayCard(token)  => token
    case PaymentMethod.GooglePayCard(token) => token
    case PaymentMethod.SavedMethod(vaultId) => vaultId.value
    case PaymentMethod.Fingerprint(fp)      => fp
    case PaymentMethod.BankAccount(id)      => id
    case PaymentMethod.Wallet(_, id)        => id

  private def intervalCadence(interval: BillingInterval): String = interval match
    case BillingInterval.Daily(_)   => "DAILY"
    case BillingInterval.Weekly(_)  => "WEEKLY"
    case BillingInterval.Monthly(_) => "MONTHLY"
    case BillingInterval.Yearly(_)  => "ANNUAL"
