package scalascript.payments.mock

import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Configurable mock per effect group:
 *  Each mode is set independently so test suites can force failures
 *  for only the operations they care about. */
enum MockMode:
  case Succeed
  case Fail(error: PaymentError)
  case RequireSCA(redirectUrl: String = "https://mock-3ds.example.com/challenge")

/** Fully in-memory PaymentProvider — no network, no I/O.
 *  All state lives in ConcurrentHashMaps so instances are thread-safe.
 *
 *  Usage:
 *    val mock = MockProvider()
 *    mock.chargeMode = MockMode.Fail(CardDeclined("declined", "card declined", RetryPolicy.DoNotRetry))
 *    val result = mock.createIntent(CreateIntentRequest(Money(1000L, Currency.USD)))
 */
class MockProvider(
    override val mode: PaymentMode = PaymentMode.Test,
) extends PaymentProvider:

  var chargeMode:    MockMode = MockMode.Succeed
  var refundMode:    MockMode = MockMode.Succeed
  var disputeMode:   MockMode = MockMode.Succeed
  var subscribeMode: MockMode = MockMode.Succeed
  var vaultMode:     MockMode = MockMode.Succeed

  private val intents:       ConcurrentHashMap[String, PaymentIntent]  = new ConcurrentHashMap()
  private val customers:     ConcurrentHashMap[String, Customer]       = new ConcurrentHashMap()
  private val storedMethods: ConcurrentHashMap[String, StoredMethod]   = new ConcurrentHashMap()
  private val plans:         ConcurrentHashMap[String, Plan]           = new ConcurrentHashMap()
  private val subscriptions: ConcurrentHashMap[String, Subscription]   = new ConcurrentHashMap()
  private val mandates:      ConcurrentHashMap[String, Mandate]        = new ConcurrentHashMap()
  private val refunds:       ConcurrentHashMap[String, Refund]         = new ConcurrentHashMap()
  private var counter:       Long                                       = 0L

  private def nextId(prefix: String): String =
    counter += 1; s"mock_${prefix}_$counter"

  def id:          String = "mock"
  def displayName: String = "Mock"
  def spiVersion:  String = "1.53.6"

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
    chargeMode match
      case MockMode.Fail(err) =>
        val id = IntentId(nextId("pi"))
        val pi = PaymentIntent.Failed(id, err, retryable = false)
        intents.put(id.value, pi)
        pi
      case MockMode.RequireSCA(url) =>
        val id = IntentId(nextId("pi"))
        val pi = PaymentIntent.RequiresAction(id, req.amount, SCAChallenge("mock", url, "", id.value))
        intents.put(id.value, pi)
        pi
      case MockMode.Succeed =>
        val id     = IntentId(nextId("pi"))
        val charge = Charge(ChargeId(nextId("ch")), id, req.amount, paid = true)
        val pi = if req.captureMethod == CaptureMethod.Manual then
          PaymentIntent.Processing(id, req.amount)
        else
          PaymentIntent.Succeeded(id, req.amount, charge)
        intents.put(id.value, pi)
        pi

  def confirmIntent(id: IntentId, method: PaymentMethod): PaymentIntent =
    chargeMode match
      case MockMode.Fail(err) =>
        PaymentIntent.Failed(id, err, retryable = false)
      case _ =>
        val existing = Option(intents.get(id.value))
        val amount   = existing.flatMap {
          case PaymentIntent.Processing(_, a)     => Some(a)
          case PaymentIntent.RequiresAction(_, a, _) => Some(a)
          case _                                  => None
        }.getOrElse(Money.zero(Currency.USD))
        val charge = Charge(ChargeId(nextId("ch")), id, amount, paid = true)
        val pi     = PaymentIntent.Succeeded(id, amount, charge)
        intents.put(id.value, pi)
        pi

  def captureIntent(id: IntentId, amount: Option[Money] = None): PaymentIntent =
    chargeMode match
      case MockMode.Fail(err) => PaymentIntent.Failed(id, err, retryable = false)
      case _ =>
        val base   = Option(intents.get(id.value))
        val money  = amount.orElse(base.flatMap {
          case PaymentIntent.Processing(_, a) => Some(a)
          case _                              => None
        }).getOrElse(Money.zero(Currency.USD))
        val charge = Charge(ChargeId(nextId("ch")), id, money, paid = true)
        val pi     = PaymentIntent.Succeeded(id, money, charge)
        intents.put(id.value, pi)
        pi

  def voidIntent(id: IntentId): Unit =
    intents.put(id.value, PaymentIntent.Canceled(id, CancelReason.RequestedByCustomer))

  // ── Group 2: Customer + Vault ──────────────────────────────────────────────

  def createCustomer(req: CreateCustomerRequest): Customer =
    vaultMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val c = Customer(
          id       = CustomerId(nextId("cus")),
          email    = req.email,
          name     = req.name,
          metadata = req.metadata,
        )
        customers.put(c.id.value, c)
        c

  def attachMethod(customerId: CustomerId, method: PaymentMethod): StoredMethod =
    vaultMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val sm = StoredMethod(
          vaultId  = VaultId(nextId("pm")),
          last4    = "4242",
          brand    = "visa",
          expMonth = "12",
          expYear  = "2028",
          funding  = "credit",
        )
        storedMethods.put(sm.vaultId.value, sm)
        sm

  def detachMethod(vaultId: VaultId): Unit =
    storedMethods.remove(vaultId.value)

  def listMethods(customerId: CustomerId): List[StoredMethod] =
    storedMethods.values().asScala.toList

  // ── Group 2b: Mandates ─────────────────────────────────────────────────────

  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate =
    vaultMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val m = Mandate(
          id          = MandateId(nextId("mandate")),
          status      = MandateStatus.Active,
          mandateType = mandateType,
          customerId  = Some(customerId),
          vaultId     = Some(vaultId),
          providerRef = None,
        )
        mandates.put(m.id.value, m)
        m

  def getMandate(id: MandateId): Mandate =
    Option(mandates.get(id.value)).getOrElse(
      Mandate(id, MandateStatus.Active, MandateType.MultiUse)
    )

  // ── Group 3: Subscriptions ─────────────────────────────────────────────────

  def createPlan(req: CreatePlanRequest): Plan =
    subscribeMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val p = Plan(
          id              = PlanId(nextId("plan")),
          amount          = req.amount,
          interval        = req.interval,
          trialPeriodDays = req.trialPeriodDays,
          metadata        = req.metadata,
        )
        plans.put(p.id.value, p)
        p

  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription =
    subscribeMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val s = Subscription(
          id               = SubscriptionId(nextId("sub")),
          customerId       = customerId,
          planId           = planId,
          status           = SubscriptionStatus.Active,
          currentPeriodEnd = Instant.now().plusSeconds(2592000),
          cancelAtPeriodEnd = false,
          trialEnd         = opts.trialPeriodDays.map(d => Instant.now().plusSeconds(d.toLong * 86400)),
        )
        subscriptions.put(s.id.value, s)
        s

  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription =
    val existing = Option(subscriptions.get(id.value))
    val updated  = Subscription(
      id               = id,
      customerId       = existing.map(_.customerId).getOrElse(CustomerId("")),
      planId           = newPlanId,
      status           = SubscriptionStatus.Active,
      currentPeriodEnd = Instant.now().plusSeconds(2592000),
      cancelAtPeriodEnd = false,
      trialEnd         = None,
    )
    subscriptions.put(id.value, updated)
    updated

  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription =
    val existing = Option(subscriptions.get(id.value))
    val canceled = Subscription(
      id               = id,
      customerId       = existing.map(_.customerId).getOrElse(CustomerId("")),
      planId           = existing.map(_.planId).getOrElse(PlanId("")),
      status           = SubscriptionStatus.Canceled,
      currentPeriodEnd = Instant.now(),
      cancelAtPeriodEnd = atPeriodEnd,
      trialEnd         = None,
    )
    subscriptions.put(id.value, canceled)
    canceled

  // ── Group 4: Refunds + Disputes ────────────────────────────────────────────

  def refund(req: RefundRequest): Refund =
    refundMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        val currency = req.amount.map(_.currency).getOrElse(Currency.USD)
        val r = Refund(
          id       = RefundId(nextId("re")),
          intentId = req.intentId,
          amount   = req.amount.getOrElse(Money.zero(currency)),
          reason   = req.reason,
          status   = RefundStatus.Succeeded,
        )
        refunds.put(r.id.value, r)
        r

  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute =
    disputeMode match
      case MockMode.Fail(err) => throw err
      case _ =>
        Dispute(
          id       = disputeId,
          intentId = IntentId(""),
          amount   = Money.zero(Currency.USD),
          reason   = DisputeReason.General,
          status   = DisputeStatus.UnderReview,
          dueDate  = Instant.now().plusSeconds(604800),
          evidence = Some(evidence),
        )

  // ── Group 5: Webhooks ──────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[PaymentEvent] = MockWebhookReceiver()

  // ── Inspection helpers ─────────────────────────────────────────────────────

  def recordedIntents:       Map[String, PaymentIntent] = intents.asScala.toMap
  def recordedCustomers:     Map[String, Customer]      = customers.asScala.toMap
  def recordedStoredMethods: Map[String, StoredMethod]  = storedMethods.asScala.toMap
  def recordedPlans:         Map[String, Plan]          = plans.asScala.toMap
  def recordedSubscriptions: Map[String, Subscription]  = subscriptions.asScala.toMap
  def recordedMandates:      Map[String, Mandate]       = mandates.asScala.toMap
  def recordedRefunds:       Map[String, Refund]        = refunds.asScala.toMap

  def reset(): Unit =
    intents.clear(); customers.clear(); storedMethods.clear()
    plans.clear(); subscriptions.clear(); mandates.clear(); refunds.clear()
    counter = 0L
    chargeMode = MockMode.Succeed; refundMode = MockMode.Succeed
    disputeMode = MockMode.Succeed; subscribeMode = MockMode.Succeed; vaultMode = MockMode.Succeed
