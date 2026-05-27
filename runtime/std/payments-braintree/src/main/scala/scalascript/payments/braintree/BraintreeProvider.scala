package scalascript.payments.braintree

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import ujson.*

/** Braintree PaymentProvider adapter.
 *  Auth: HTTP Basic over `public_key:private_key`; `merchant_id` in URL path.
 *  API: Braintree GraphQL (https://payments.braintree-api.com/graphql).
 *  PCI: Hosted Fields nonce token.
 *  Webhook: HMAC-SHA1 over bt-payload, verified against bt-signature. */
class BraintreeProvider(
    merchantId: String,
    publicKey:  String,
    privateKey: String,
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  private val baseUrl = if mode == PaymentMode.Live then "https://api.braintreegateway.com"
                        else "https://api.sandbox.braintreegateway.com"
  private val gqlUrl  = if mode == PaymentMode.Live then "https://payments.braintree-api.com/graphql"
                        else "https://payments.sandbox.braintree-api.com/graphql"
  private val http    = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  private val basicAuth = java.util.Base64.getEncoder.encodeToString(s"$publicKey:$privateKey".getBytes("UTF-8"))

  def id:          String = "braintree"
  def displayName: String = "Braintree"
  def spiVersion:  String = "1.53.2"

  def capabilities: PaymentCapabilities = PaymentCapabilities(
    supportsSubscriptions  = true,
    supportsSCA            = false,
    supports3DS2           = false,
    supportsApplePay       = true,
    supportsGooglePay      = true,
    supportsRefunds        = true,
    supportsPartialRefunds = true,
    supportsDisputes       = true,
    supportsMultiCurrency  = true,
    supportsMandates       = false,
  )

  // ── Group 1: PaymentIntent (mapped to Braintree Transactions) ────────────

  def createIntent(req: CreateIntentRequest): PaymentIntent =
    val nonce = req.method.map(methodToNonce).getOrElse("")
    val gql = s"""mutation {
      chargePaymentMethod(input: {
        paymentMethodId: "${nonce}",
        transaction: {
          amount: "${req.amount.toDecimal}",
          orderId: null
        }
      }) {
        transaction { id legacyId status amount { value currencyIsoCode }
          statusHistory { status source timestamp }
        }
      }
    }"""
    val json = graphql(gql)
    parseTx(json("data")("chargePaymentMethod")("transaction"))

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    // Braintree transactions don't require a separate confirm step
    val gql = s"""{ transaction(id: "${id.value}") { id legacyId status amount { value currencyIsoCode } } }"""
    val json = graphql(gql)
    parseTx(json("data")("transaction"))

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    val amountStr = amount.map(m => s"""amount: "${m.toDecimal}",""").getOrElse("")
    val gql = s"""mutation {
      captureTransaction(input: { transactionId: "${id.value}" $amountStr }) {
        transaction { id legacyId status amount { value currencyIsoCode } }
      }
    }"""
    val json = graphql(gql)
    parseTx(json("data")("captureTransaction")("transaction"))

  def voidIntent(id: IntentId): Unit =
    val gql = s"""mutation {
      reverseTransaction(input: { transactionId: "${id.value}" }) {
        reversal { ... on Transaction { id } }
      }
    }"""
    graphql(gql)
    ()

  // ── Group 2: Customer + Vault ─────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    val gql = s"""mutation {
      createCustomer(input: { customer: { email: "${escape(req.email)}" ${req.name.map(n => s""", company: "${escape(n)}"""").getOrElse("")} } }) {
        customer { id email company }
      }
    }"""
    val json = graphql(gql)
    val c    = json("data")("createCustomer")("customer")
    Customer(
      id       = CustomerId(c("id").str),
      email    = c("email").str,
      name     = c.obj.get("company").flatMap { case ujson.Str(s) if s.nonEmpty => Some(s); case _ => None },
      metadata = Map.empty,
    )

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    val nonce = methodToNonce(method)
    val gql = s"""mutation {
      vaultPaymentMethod(input: { paymentMethodId: "${nonce}", customerId: "${customerId.value}" }) {
        paymentMethod { id details { ... on CreditCardDetails { last4 brandCode expirationMonth expirationYear } } }
      }
    }"""
    val json  = graphql(gql)
    val pm    = json("data")("vaultPaymentMethod")("paymentMethod")
    parseStoredMethod(pm)

  def detachMethod(vaultId: VaultId): Unit =
    val gql = s"""mutation { deletePaymentMethod(input: { paymentMethodId: "${vaultId.value}" }) { clientMutationId } }"""
    graphql(gql)
    ()

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    val gql = s"""{ customer(id: "${customerId.value}") { paymentMethods { edges { node { id details { ... on CreditCardDetails { last4 brandCode expirationMonth expirationYear } } } } } } }"""
    val json = graphql(gql)
    json("data")("customer")("paymentMethods")("edges").arr.toList.map(e => parseStoredMethod(e("node")))

  // ── Group 2b: Mandates ─────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    // Braintree mandates are implicit — a vaulted payment method with recurring enabled is a mandate.
    Mandate(
      id          = MandateId(s"bt-mandate-${vaultId.value}"),
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
    // Braintree plans are REST, not GraphQL
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
<plan>
  <billing-frequency>${intervalMonths(req.interval)}</billing-frequency>
  <currency-iso-code>${req.amount.currency.code}</currency-iso-code>
  <number-of-billing-cycles>0</number-of-billing-cycles>
  <price>${req.amount.toDecimal}</price>
  <trial-period>false</trial-period>
</plan>"""
    val resp  = postXml(s"/merchants/$merchantId/plans", xml)
    val planId = extractXmlValue(resp, "id")
    Plan(
      id              = PlanId(planId),
      amount          = req.amount,
      interval        = req.interval,
      trialPeriodDays = req.trialPeriodDays,
      metadata        = req.metadata,
    )

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    val methodPart = opts.defaultMethod.map(v => s"<payment-method-nonce>${v.value}</payment-method-nonce>").getOrElse("")
    val trialPart  = opts.trialPeriodDays.map(d =>
      s"<trial-period>true</trial-period><trial-duration>$d</trial-duration><trial-duration-unit>day</trial-duration-unit>"
    ).getOrElse("")
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
<subscription>
  <plan-id>${planId.value}</plan-id>
  <customer-id>${customerId.value}</customer-id>
  $methodPart
  $trialPart
</subscription>"""
    val resp = postXml(s"/merchants/$merchantId/subscriptions", xml)
    parseXmlSubscription(resp, planId)

  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription =
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
<subscription><plan-id>${newPlanId.value}</plan-id></subscription>"""
    val resp = putXml(s"/merchants/$merchantId/subscriptions/${id.value}", xml)
    parseXmlSubscription(resp, newPlanId)

  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription =
    val resp = putXml(s"/merchants/$merchantId/subscriptions/${id.value}/cancel", "")
    parseXmlSubscription(resp, PlanId(""))

  // ── Group 4: Refunds + Disputes ───────────────────────────────────────────

  def refund(req: RefundRequest): Refund =
    val amountPart = req.amount.map(m => s"<amount>${m.toDecimal}</amount>").getOrElse("")
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
<transaction>$amountPart</transaction>"""
    val resp = postXml(s"/merchants/$merchantId/transactions/${req.intentId.value}/refund", xml)
    val currency = Currency(extractXmlValue(resp, "currency-iso-code", default = "USD").toUpperCase)
    Refund(
      id       = RefundId(extractXmlValue(resp, "id")),
      intentId = req.intentId,
      amount   = req.amount.getOrElse(Money.zero(currency)),
      reason   = req.reason,
      status   = RefundStatus.Succeeded,
    )

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    val notePart = evidence.uncategorizedText.map(t => s"<text>$t</text>").getOrElse("")
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
<evidence>$notePart</evidence>"""
    postXml(s"/merchants/$merchantId/disputes/${disputeId.value}/evidence", xml)
    val resp = getXml(s"/merchants/$merchantId/disputes/${disputeId.value}")
    parseXmlDispute(resp)

  // ── Group 5: Webhooks ─────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = BraintreeWebhookReceiver(privateKey)

  // ── HTTP + GraphQL helpers ────────────────────────────────────────────────

  private def graphql(query: String): ujson.Value =
    val body = ujson.Obj("query" -> query)
    val req  = JHttpRequest.newBuilder(URI.create(gqlUrl))
      .header("Authorization",    s"Basic $basicAuth")
      .header("Content-Type",     "application/json")
      .header("Braintree-Version","2019-01-01")
      .POST(JHttpRequest.BodyPublishers.ofString(body.render()))
      .timeout(Duration.ofSeconds(30))
      .build()
    val resp = http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    val json = ujson.read(resp.body())
    json.obj.get("errors").foreach { errs =>
      val msg = errs.arr.headOption.flatMap(_.obj.get("message")).map(_.str).getOrElse("GraphQL error")
      throw PermanentProviderError("graphql_error", msg)
    }
    json

  private def postXml(path: String, xml: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",  s"Basic $basicAuth")
      .header("Content-Type",   "application/xml")
      .header("Accept",         "application/xml")
      .POST(JHttpRequest.BodyPublishers.ofString(xml))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseXmlResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def putXml(path: String, xml: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization",  s"Basic $basicAuth")
      .header("Content-Type",   "application/xml")
      .header("Accept",         "application/xml")
      .method("PUT", JHttpRequest.BodyPublishers.ofString(xml))
      .timeout(Duration.ofSeconds(30))
      .build()
    parseXmlResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def getXml(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .header("Authorization", s"Basic $basicAuth")
      .header("Accept",        "application/xml")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    parseXmlResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def parseXmlResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      val msg = extractXmlValue(resp.body(), "message", default = resp.body())
      resp.statusCode() match
        case 429 =>
          val retryOpt = resp.headers().firstValueAsLong("Retry-After")
          val retryAfter = if retryOpt.isPresent then Some(Duration.ofSeconds(retryOpt.getAsLong)) else None
          throw RateLimitExceeded(retryAfter)
        case _ =>
          throw PermanentProviderError("api_error", msg)
    resp.body()

  // ── Parse helpers ─────────────────────────────────────────────────────────

  private def parseTx(j: ujson.Value): PaymentIntent =
    val id     = IntentId(j("id").str)
    val amount = j("amount") match
      case ujson.Obj(o) =>
        val code = o.get("currencyIsoCode").map(_.str.toUpperCase).getOrElse("USD")
        val value = o.get("value").map(v => BigDecimal(v.str)).getOrElse(BigDecimal(0))
        Money(value, Currency(code))
      case _ => Money.zero(Currency.USD)
    j("status").str match
      case "AUTHORIZED"                   => PaymentIntent.RequiresConfirmation(id, amount, PaymentMethod.Card(""))
      case "SUBMITTED_FOR_SETTLEMENT" | "SETTLING" | "SETTLEMENT_PENDING" =>
        PaymentIntent.Processing(id, amount)
      case "SETTLED"                      =>
        PaymentIntent.Succeeded(id, amount, Charge(ChargeId(j.obj.get("legacyId").map(_.str).getOrElse("")), id, amount, paid = true))
      case "VOIDED" | "AUTHORIZATION_EXPIRED" =>
        PaymentIntent.Canceled(id, CancelReason.Other(""))
      case "PROCESSOR_DECLINED" | "SETTLEMENT_DECLINED" | "FAILED" =>
        PaymentIntent.Failed(id, PermanentProviderError(j("status").str, ""), retryable = false)
      case _ =>
        PaymentIntent.Processing(id, amount)

  private def parseStoredMethod(j: ujson.Value): StoredMethod =
    val details = j.obj.get("details").getOrElse(j)
    StoredMethod(
      vaultId  = VaultId(j("id").str),
      last4    = details.obj.get("last4").map(_.str).getOrElse(""),
      brand    = details.obj.get("brandCode").map(_.str).getOrElse("unknown"),
      expMonth = details.obj.get("expirationMonth").map(_.str).getOrElse(""),
      expYear  = details.obj.get("expirationYear").map(_.str).getOrElse(""),
      funding  = "credit",
    )

  private def parseXmlSubscription(xml: String, planId: PlanId): Subscription =
    val status = extractXmlValue(xml, "status", default = "Active") match
      case "Active"    => SubscriptionStatus.Active
      case "Past Due"  => SubscriptionStatus.PastDue
      case "Canceled"  => SubscriptionStatus.Canceled
      case "Expired"   => SubscriptionStatus.Unpaid
      case _           => SubscriptionStatus.Active
    Subscription(
      id               = SubscriptionId(extractXmlValue(xml, "id")),
      customerId       = CustomerId(extractXmlValue(xml, "customer-id", default = "")),
      planId           = planId,
      status           = status,
      currentPeriodEnd = Instant.now().plusSeconds(2592000),
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )

  private def parseXmlDispute(xml: String): Dispute =
    val amountStr = extractXmlValue(xml, "amount-disputed", default = "0")
    Dispute(
      id       = DisputeId(extractXmlValue(xml, "id")),
      intentId = IntentId(extractXmlValue(xml, "transaction-id", default = "")),
      amount   = Money(BigDecimal(amountStr), Currency.USD),
      reason   = DisputeReason.General,
      status   = DisputeStatus.NeedsResponse,
      dueDate  = Instant.now().plusSeconds(604800),
      evidence = None,
    )

  private def intervalMonths(i: BillingInterval): Int = i match
    case BillingInterval.Daily(_)   => 1
    case BillingInterval.Weekly(_)  => 1
    case BillingInterval.Monthly(c) => c
    case BillingInterval.Yearly(c)  => c * 12

  private def methodToNonce(method: PaymentMethod): String = method match
    case PaymentMethod.Card(token)          => token
    case PaymentMethod.ApplePayCard(token)  => token
    case PaymentMethod.GooglePayCard(token) => token
    case PaymentMethod.SavedMethod(vaultId) => vaultId.value
    case PaymentMethod.Fingerprint(fp)      => fp
    case PaymentMethod.BankAccount(id)      => id
    case PaymentMethod.Wallet(_, id)        => id

  // Simple XML value extractor (no external XML library needed for these simple responses)
  private def extractXmlValue(xml: String, tag: String, default: String = ""): String =
    val start = xml.indexOf(s"<$tag>")
    val end   = xml.indexOf(s"</$tag>")
    if start < 0 || end < 0 then default
    else xml.substring(start + tag.length + 2, end)

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
