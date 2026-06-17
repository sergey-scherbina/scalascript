package scalascript.payments.mock

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import scalascript.compiler.plugin.payments.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*

class MockProviderTest extends AnyFunSuite with BeforeAndAfterEach:

  private val mock = MockProvider()

  override def afterEach(): Unit = mock.reset()

  // ── Basic identity ─────────────────────────────────────────────────────────

  test("MockProvider: id is mock"):
    assert(mock.id == "mock")

  test("MockProvider: displayName is Mock"):
    assert(mock.displayName == "Mock")

  test("MockProvider: mode defaults to Test"):
    assert(mock.mode == PaymentMode.Test)

  test("MockProvider: spiVersion is 1.53.6"):
    assert(mock.spiVersion == "1.53.6")

  test("MockProvider: all capabilities true"):
    val c = mock.capabilities
    assert(c.supportsSubscriptions)
    assert(c.supportsSCA)
    assert(c.supports3DS2)
    assert(c.supportsApplePay)
    assert(c.supportsGooglePay)
    assert(c.supportsRefunds)
    assert(c.supportsPartialRefunds)
    assert(c.supportsDisputes)
    assert(c.supportsMultiCurrency)
    assert(c.supportsMandates)

  // ── createIntent: Succeed mode ─────────────────────────────────────────────

  test("createIntent: Succeed returns PaymentIntent.Succeeded"):
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    assert(pi.isInstanceOf[PaymentIntent.Succeeded])

  test("createIntent: Succeed stores intent in recordedIntents"):
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    assert(mock.recordedIntents.contains(pi.id.value))

  test("createIntent: Manual capture returns Processing"):
    val pi = mock.createIntent(CreateIntentRequest(Money(1000L, Currency.USD), captureMethod = CaptureMethod.Manual))
    assert(pi.isInstanceOf[PaymentIntent.Processing])

  test("createIntent: Fail mode returns PaymentIntent.Failed"):
    mock.chargeMode = MockMode.Fail(CardDeclined("declined", "card declined", RetryPolicy.DoNotRetry))
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    assert(pi.isInstanceOf[PaymentIntent.Failed])

  test("createIntent: RequireSCA mode returns RequiresAction"):
    mock.chargeMode = MockMode.RequireSCA()
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    assert(pi.isInstanceOf[PaymentIntent.RequiresAction])

  // ── confirmIntent ──────────────────────────────────────────────────────────

  test("confirmIntent: confirms existing Processing intent"):
    mock.chargeMode = MockMode.Succeed
    val pi = mock.createIntent(CreateIntentRequest(Money(800L, Currency.USD), captureMethod = CaptureMethod.Manual))
    val confirmed = mock.confirmIntent(pi.id, PaymentMethod.Card("tok_test"))
    assert(confirmed.isInstanceOf[PaymentIntent.Succeeded])

  test("confirmIntent: Fail mode returns Failed"):
    val id = IntentId("mock_pi_test")
    mock.chargeMode = MockMode.Fail(CardDeclined("declined", "declined", RetryPolicy.DoNotRetry))
    val result = mock.confirmIntent(id, PaymentMethod.Card("tok"))
    assert(result.isInstanceOf[PaymentIntent.Failed])

  // ── captureIntent ──────────────────────────────────────────────────────────

  test("captureIntent: captures Processing intent with explicit amount"):
    val pi = mock.createIntent(CreateIntentRequest(Money(1000L, Currency.USD), captureMethod = CaptureMethod.Manual))
    val captured = mock.captureIntent(pi.id, Some(Money(900L, Currency.USD)))
    assert(captured.isInstanceOf[PaymentIntent.Succeeded])
    assert(captured.asInstanceOf[PaymentIntent.Succeeded].amount == Money(900L, Currency.USD))

  test("captureIntent: Fail mode returns Failed"):
    val id = IntentId("mock_pi_x")
    mock.chargeMode = MockMode.Fail(InsufficientFunds("no funds"))
    val result = mock.captureIntent(id)
    assert(result.isInstanceOf[PaymentIntent.Failed])

  // ── voidIntent ─────────────────────────────────────────────────────────────

  test("voidIntent: stores Canceled intent"):
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD), captureMethod = CaptureMethod.Manual))
    mock.voidIntent(pi.id)
    val stored = mock.recordedIntents(pi.id.value)
    assert(stored.isInstanceOf[PaymentIntent.Canceled])

  // ── Customer + Vault ───────────────────────────────────────────────────────

  test("createCustomer: Succeed returns Customer with email"):
    val c = mock.createCustomer(CreateCustomerRequest(email = "test@example.com"))
    assert(c.email == "test@example.com")
    assert(mock.recordedCustomers.contains(c.id.value))

  test("createCustomer: Fail throws PaymentError"):
    mock.vaultMode = MockMode.Fail(InvalidPaymentMethod("bad"))
    assertThrows[PaymentError]:
      mock.createCustomer(CreateCustomerRequest(email = "x@y.com"))

  test("attachMethod: returns StoredMethod with last4=4242"):
    val c  = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val sm = mock.attachMethod(c.id, PaymentMethod.Card("tok_test"))
    assert(sm.last4 == "4242")
    assert(mock.recordedStoredMethods.contains(sm.vaultId.value))

  test("detachMethod: removes stored method"):
    val c  = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val sm = mock.attachMethod(c.id, PaymentMethod.Card("tok_test"))
    mock.detachMethod(sm.vaultId)
    assert(!mock.recordedStoredMethods.contains(sm.vaultId.value))

  test("listMethods: returns all stored methods"):
    val c = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    mock.attachMethod(c.id, PaymentMethod.Card("tok1"))
    mock.attachMethod(c.id, PaymentMethod.Card("tok2"))
    assert(mock.listMethods(c.id).size == 2)

  // ── Mandates ───────────────────────────────────────────────────────────────

  test("createMandate: returns active mandate"):
    val c  = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val sm = mock.attachMethod(c.id, PaymentMethod.Card("tok"))
    val m  = mock.createMandate(c.id, sm.vaultId, MandateType.MultiUse)
    assert(m.status == MandateStatus.Active)
    assert(m.mandateType == MandateType.MultiUse)
    assert(mock.recordedMandates.contains(m.id.value))

  test("getMandate: returns stored mandate"):
    val c  = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val sm = mock.attachMethod(c.id, PaymentMethod.Card("tok"))
    val m  = mock.createMandate(c.id, sm.vaultId, MandateType.SingleUse)
    val got = mock.getMandate(m.id)
    assert(got.id == m.id)

  // ── Subscriptions ──────────────────────────────────────────────────────────

  test("createPlan: returns Plan with correct amount"):
    val p = mock.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
    assert(p.amount == Money(999L, Currency.USD))
    assert(mock.recordedPlans.contains(p.id.value))

  test("subscribe: returns Active subscription"):
    val c   = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val p   = mock.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
    val sub = mock.subscribe(c.id, p.id, SubscribeOpts())
    assert(sub.status == SubscriptionStatus.Active)
    assert(mock.recordedSubscriptions.contains(sub.id.value))

  test("subscribe: Fail throws PaymentError"):
    val c = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val p = mock.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
    mock.subscribeMode = MockMode.Fail(PermanentProviderError("err", "fail"))
    assertThrows[PaymentError]:
      mock.subscribe(c.id, p.id, SubscribeOpts())

  test("changeSubscription: updates planId"):
    val c    = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val p1   = mock.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
    val p2   = mock.createPlan(CreatePlanRequest(Money(1999L, Currency.USD), BillingInterval.Yearly()))
    val sub  = mock.subscribe(c.id, p1.id, SubscribeOpts())
    val upd  = mock.changeSubscription(sub.id, p2.id, ProrationMode.AlwaysInvoice)
    assert(upd.planId == p2.id)

  test("cancelSubscription: sets status to Canceled"):
    val c   = mock.createCustomer(CreateCustomerRequest("a@b.com"))
    val p   = mock.createPlan(CreatePlanRequest(Money(999L, Currency.USD), BillingInterval.Monthly()))
    val sub = mock.subscribe(c.id, p.id, SubscribeOpts())
    val can = mock.cancelSubscription(sub.id)
    assert(can.status == SubscriptionStatus.Canceled)

  // ── Refunds ────────────────────────────────────────────────────────────────

  test("refund: returns Succeeded refund"):
    val pi  = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    val ref = mock.refund(RefundRequest(pi.id, Some(Money(200L, Currency.USD))))
    assert(ref.status == RefundStatus.Succeeded)
    assert(ref.amount == Money(200L, Currency.USD))
    assert(mock.recordedRefunds.contains(ref.id.value))

  test("refund: Fail mode throws PaymentError"):
    mock.refundMode = MockMode.Fail(PermanentProviderError("err", "no refund"))
    val pi = mock.createIntent(CreateIntentRequest(Money(500L, Currency.USD)))
    assertThrows[PaymentError]:
      mock.refund(RefundRequest(pi.id))

  // ── Disputes ───────────────────────────────────────────────────────────────

  test("submitDisputeEvidence: returns Dispute under review"):
    val d = mock.submitDisputeEvidence(
      DisputeId("dp_test"),
      DisputeEvidence(uncategorizedText = Some("I have proof")),
    )
    assert(d.status == DisputeStatus.UnderReview)

  test("submitDisputeEvidence: Fail throws PaymentError"):
    mock.disputeMode = MockMode.Fail(PermanentProviderError("err", "dispute failed"))
    assertThrows[PaymentError]:
      mock.submitDisputeEvidence(DisputeId("dp_x"), DisputeEvidence())

  // ── Webhook ────────────────────────────────────────────────────────────────

  test("webhookReceiver: returns MockWebhookReceiver"):
    assert(mock.webhookReceiver.isInstanceOf[MockWebhookReceiver])

  test("MockWebhookReceiver: accepts any secret and parses payment_intent.succeeded"):
    val receiver = MockWebhookReceiver()
    val body = """{"type":"payment_intent.succeeded","id":"pi_123","amount":500}"""
    val req  = WebhookRequest(Map.empty, body)
    val result = receiver.verify(req, "any-secret")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.PaymentIntentSucceeded])

  test("MockWebhookReceiver: empty body returns Left"):
    val receiver = MockWebhookReceiver()
    val result = receiver.verify(WebhookRequest(Map.empty, ""), "secret")
    assert(result.isLeft)

  test("MockWebhookReceiver: unknown event type returns ManualReviewRequired"):
    val receiver = MockWebhookReceiver()
    val body = """{"type":"unknown.event","id":"x"}"""
    val result = receiver.verify(WebhookRequest(Map.empty, body), "any")
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[PaymentEvent.ManualReviewRequired])

  test("MockWebhookReceiver: handle deduplicates events"):
    val receiver = MockWebhookReceiver()
    val body = """{"type":"payment_intent.succeeded","id":"pi_dedup","amount":100}"""
    val req  = WebhookRequest(Map.empty, body)
    var count = 0
    receiver.handle(req, "secret") { case _ => count += 1 }
    receiver.handle(req, "secret") { case _ => count += 1 }
    assert(count == 1)

  test("MockWebhookReceiver: recorded captures events"):
    val receiver = MockWebhookReceiver()
    val body = """{"type":"payment_intent.succeeded","id":"pi_rec","amount":200}"""
    receiver.verify(WebhookRequest(Map.empty, body), "any")
    assert(receiver.recorded.size == 1)

  // ── reset() ────────────────────────────────────────────────────────────────

  test("reset: clears all state and resets modes"):
    mock.chargeMode = MockMode.Fail(CardDeclined("x", "x", RetryPolicy.DoNotRetry))
    mock.createIntent(CreateIntentRequest(Money(100L, Currency.USD)))
    mock.reset()
    assert(mock.recordedIntents.isEmpty)
    assert(mock.chargeMode == MockMode.Succeed)

  test("reset: counter resets so IDs restart from mock_pi_1"):
    mock.reset()
    val pi = mock.createIntent(CreateIntentRequest(Money(100L, Currency.USD)))
    assert(pi.id.value == "mock_pi_1")

  // ── MockMode variants ──────────────────────────────────────────────────────

  test("MockMode.RequireSCA: default redirectUrl contains mock-3ds"):
    val mode = MockMode.RequireSCA()
    mode match
      case MockMode.RequireSCA(u) => assert(u.contains("mock-3ds"))
      case _                      => fail("expected RequireSCA")

  test("MockMode.RequireSCA: custom redirectUrl preserved"):
    val mode = MockMode.RequireSCA("https://custom.example.com/3ds")
    mode match
      case MockMode.RequireSCA(u) => assert(u == "https://custom.example.com/3ds")
      case _                      => fail("expected RequireSCA")
