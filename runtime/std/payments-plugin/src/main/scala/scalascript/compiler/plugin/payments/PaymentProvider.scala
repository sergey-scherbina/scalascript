package scalascript.compiler.plugin.payments

import scalascript.payments.money.Money
import scalascript.payments.webhook.WebhookReceiver
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

// ── Opaque ID types ──────────────────────────────────────────────────────────

opaque type IntentId       = String
opaque type CustomerId     = String
opaque type VaultId        = String
opaque type PlanId         = String
opaque type SubscriptionId = String
opaque type RefundId       = String
opaque type DisputeId      = String
opaque type ChargeId       = String
opaque type MandateId      = String

object IntentId       { def apply(s: String): IntentId       = s; extension (id: IntentId)       def value: String = id }
object CustomerId     { def apply(s: String): CustomerId     = s; extension (id: CustomerId)     def value: String = id }
object VaultId        { def apply(s: String): VaultId        = s; extension (id: VaultId)        def value: String = id }
object PlanId         { def apply(s: String): PlanId         = s; extension (id: PlanId)         def value: String = id }
object SubscriptionId { def apply(s: String): SubscriptionId = s; extension (id: SubscriptionId) def value: String = id }
object RefundId       { def apply(s: String): RefundId       = s; extension (id: RefundId)       def value: String = id }
object DisputeId      { def apply(s: String): DisputeId      = s; extension (id: DisputeId)      def value: String = id }
object ChargeId       { def apply(s: String): ChargeId       = s; extension (id: ChargeId)       def value: String = id }
object MandateId      { def apply(s: String): MandateId      = s; extension (id: MandateId)      def value: String = id }

// ── Mode + Capabilities ──────────────────────────────────────────────────────

enum PaymentMode:
  case Test, Live

case class PaymentCapabilities(
  supportsSubscriptions:     Boolean = false,
  supportsSCA:               Boolean = false,
  supports3DS2:              Boolean = false,
  supportsACH:               Boolean = false,
  supportsSEPA:              Boolean = false,
  supportsApplePay:          Boolean = false,
  supportsGooglePay:         Boolean = false,
  supportsRefunds:           Boolean = false,
  supportsPartialRefunds:    Boolean = false,
  supportsDisputes:          Boolean = false,
  supportsConnectedAccounts: Boolean = false,
  supportsMultiCurrency:     Boolean = false,
  supportsMandates:          Boolean = false,
)

// ── PaymentMethod ─────────────────────────────────────────────────────────────

enum PaymentMethod:
  case Card(token: String)
  case ApplePayCard(token: String)
  case GooglePayCard(token: String)
  case BankAccount(accountId: String)
  case Wallet(provider: String, externalId: String)
  case SavedMethod(vaultId: VaultId)
  case Fingerprint(value: String)

// ── SCAChallenge ──────────────────────────────────────────────────────────────

case class SCAChallenge(
  provider:    String,
  redirectUrl: String,
  returnUrl:   String,
  fingerprint: String,
)

// ── Charge + Capture ─────────────────────────────────────────────────────────

case class Charge(
  id:                    ChargeId,
  intentId:              IntentId,
  amount:                Money,
  paid:                  Boolean,
  receiptUrl:            Option[String] = None,
  balanceTransactionId:  Option[String] = None,
)

enum CaptureMethod:
  case Automatic, Manual

enum SetupFutureUsage:
  case OnSession, OffSession

// ── PaymentIntent ─────────────────────────────────────────────────────────────

enum CancelReason:
  case Duplicate, Fraudulent, RequestedByCustomer, Abandoned, Automatic
  case Other(desc: String)

enum PaymentIntent:
  case RequiresPaymentMethod(intentId: IntentId, amount: Money, metadata: Map[String, String] = Map.empty)
  case RequiresConfirmation(intentId: IntentId, amount: Money, method: PaymentMethod)
  case RequiresAction(intentId: IntentId, amount: Money, action: SCAChallenge)
  case Processing(intentId: IntentId, amount: Money)
  case Succeeded(intentId: IntentId, amount: Money, charge: Charge)
  case Canceled(intentId: IntentId, reason: CancelReason)
  case Failed(intentId: IntentId, error: PaymentError, retryable: Boolean)

object PaymentIntent:
  extension (pi: PaymentIntent)
    def id: IntentId = pi match
      case RequiresPaymentMethod(id, _, _) => id
      case RequiresConfirmation(id, _, _)  => id
      case RequiresAction(id, _, _)        => id
      case Processing(id, _)               => id
      case Succeeded(id, _, _)             => id
      case Canceled(id, _)                 => id
      case Failed(id, _, _)                => id

// ── SCA Exemptions ────────────────────────────────────────────────────────────

enum ScaExemption:
  case LowValue
  case TrustedListing
  case TransactionRiskAnalysis
  case Recurring
  case MerchantInitiated

// ── CreateIntentRequest ───────────────────────────────────────────────────────

case class CreateIntentRequest(
  amount:           Money,
  method:           Option[PaymentMethod] = None,
  confirm:          Boolean               = false,
  customer:         Option[CustomerId]    = None,
  captureMethod:    CaptureMethod         = CaptureMethod.Automatic,
  setupFutureUsage: Option[SetupFutureUsage] = None,
  offSession:       Boolean               = false,
  mandateId:        Option[MandateId]     = None,
  scaExemptions:    List[ScaExemption]    = List.empty,
  metadata:         Map[String, String]   = Map.empty,
  description:      Option[String]        = None,
  returnUrl:        Option[String]        = None,
)

// ── Customer + Vault ──────────────────────────────────────────────────────────

case class CreateCustomerRequest(
  email:    String,
  name:     Option[String]        = None,
  metadata: Map[String, String]   = Map.empty,
)

case class Customer(
  id:       CustomerId,
  email:    String,
  name:     Option[String],
  metadata: Map[String, String],
)

case class StoredMethod(
  vaultId:      VaultId,
  last4:        String,
  brand:        String,
  expMonth:     String,
  expYear:      String,
  funding:      String,
  isDefault:    Boolean         = false,
  networkToken: Option[String]  = None,
  mandateId:    Option[MandateId] = None,
)

// ── Plans + Subscriptions ─────────────────────────────────────────────────────

enum BillingInterval:
  case Daily(count: Int = 1)
  case Weekly(count: Int = 1)
  case Monthly(count: Int = 1)
  case Yearly(count: Int = 1)

case class CreatePlanRequest(
  amount:           Money,
  interval:         BillingInterval,
  trialPeriodDays:  Option[Int]           = None,
  metadata:         Map[String, String]   = Map.empty,
)

case class Plan(
  id:              PlanId,
  amount:          Money,
  interval:        BillingInterval,
  trialPeriodDays: Option[Int],
  metadata:        Map[String, String],
)

case class SubscribeOpts(
  trialPeriodDays: Option[Int]         = None,
  defaultMethod:   Option[VaultId]     = None,
  metadata:        Map[String, String] = Map.empty,
)

enum SubscriptionStatus:
  case Trialing, Active, PastDue, Canceled, Unpaid, Paused

case class Subscription(
  id:               SubscriptionId,
  customerId:       CustomerId,
  planId:           PlanId,
  status:           SubscriptionStatus,
  currentPeriodEnd: java.time.Instant,
  cancelAtPeriodEnd: Boolean,
  trialEnd:         Option[java.time.Instant],
)

enum ProrationMode:
  case CreateProration, AlwaysInvoice, None

// ── Refunds + Disputes ────────────────────────────────────────────────────────

enum RefundReason:
  case Duplicate, Fraudulent, RequestedByCustomer
  case Other(description: String)

enum RefundStatus:
  case Pending, Succeeded, Failed, Canceled

case class RefundRequest(
  intentId: IntentId,
  amount:   Option[Money]  = None,
  reason:   RefundReason   = RefundReason.RequestedByCustomer,
)

case class Refund(
  id:       RefundId,
  intentId: IntentId,
  amount:   Money,
  reason:   RefundReason,
  status:   RefundStatus,
)

enum DisputeReason:
  case Fraudulent, Duplicate, SubscriptionCanceled, ProductNotReceived, ProductUnacceptable
  case Unrecognized, CreditNotProcessed, General

enum DisputeStatus:
  case NeedsResponse, UnderReview, Won, Lost, WarningClosed

case class DisputeEvidence(
  customerCommunication: Option[String] = None,
  receipt:               Option[String] = None,
  shippingDocumentation: Option[String] = None,
  uncategorizedText:     Option[String] = None,
  serviceDocumentation:  Option[String] = None,
)

case class Dispute(
  id:        DisputeId,
  intentId:  IntentId,
  amount:    Money,
  reason:    DisputeReason,
  status:    DisputeStatus,
  dueDate:   java.time.Instant,
  evidence:  Option[DisputeEvidence],
)

// ── Mandate ───────────────────────────────────────────────────────────────────

enum MandateStatus { case Active, Inactive, Pending }
enum MandateType   { case MultiUse, SingleUse }
case class Mandate(
  id:           MandateId,
  status:       MandateStatus,
  mandateType:  MandateType,
  customerId:   Option[CustomerId] = None,
  vaultId:      Option[VaultId]   = None,
  providerRef:  Option[String]    = None,
)

// ── Idempotency ───────────────────────────────────────────────────────────────

opaque type IdempotencyKey = String
object IdempotencyKey:
  def apply(key: String): IdempotencyKey = key
  def fromString(s: String): IdempotencyKey = s
  extension (k: IdempotencyKey) def value: String = k

// ── Retry + Errors ────────────────────────────────────────────────────────────

enum RetryPolicy:
  case RetryNow, RetryAfterAction, DoNotRetry

sealed class PaymentError(msg: String) extends RuntimeException(msg)
case class CardDeclined(code: String, message: String, retryPolicy: RetryPolicy)
    extends PaymentError(message)
case class InsufficientFunds(message: String)
    extends PaymentError(message)
case class AuthenticationRequired(challenge: SCAChallenge)
    extends PaymentError("3DS2 challenge required")
case class InvalidPaymentMethod(message: String)
    extends PaymentError(message)
case class RateLimitExceeded(retryAfter: Option[java.time.Duration])
    extends PaymentError("rate limited")
case class ProviderUnreachable(retryAfter: Option[java.time.Duration])
    extends PaymentError("provider unreachable")
case class PermanentProviderError(code: String, body: String)
    extends PaymentError(s"provider error: $code")
case class DuplicateRequest(originalId: String)
    extends PaymentError("duplicate idempotency key with different body")

// ── PaymentEvent (webhook event union) ────────────────────────────────────────

enum PaymentEvent:
  case PaymentIntentSucceeded(intent: PaymentIntent)
  case PaymentIntentFailed(intent: PaymentIntent, error: PaymentError)
  case ChargeRefunded(refund: Refund)
  case InvoicePaymentSucceeded(invoiceId: String, amount: Money, subscriptionId: SubscriptionId)
  case InvoicePaymentFailed(invoiceId: String, subscriptionId: SubscriptionId, attemptsRemaining: Int)
  case SubscriptionUpdated(subscription: Subscription)
  case SubscriptionCanceled(subscription: Subscription)
  case DisputeCreated(dispute: Dispute)
  case DisputeEvidenceRequired(dispute: Dispute)
  case DisputeUpdated(dispute: Dispute)
  case ManualReviewRequired(intentId: IntentId, reason: String)

// ── PaymentProvider SPI ───────────────────────────────────────────────────────

trait PaymentProvider:
  def id:           String
  def displayName:  String
  def spiVersion:   String
  def capabilities: PaymentCapabilities
  def mode:         PaymentMode

  // Group 1: PaymentIntent — one-time charges
  def createIntent(req: CreateIntentRequest):                              PaymentIntent
  def confirmIntent(id: IntentId, method: PaymentMethod):                  PaymentIntent
  def captureIntent(id: IntentId, amount: Option[Money] = None):           PaymentIntent
  def voidIntent(id: IntentId):                                            Unit

  // Group 2: Customer + Vault
  def createCustomer(req: CreateCustomerRequest):                          Customer
  def attachMethod(customerId: CustomerId, method: PaymentMethod):         StoredMethod
  def detachMethod(vaultId: VaultId):                                      Unit
  def listMethods(customerId: CustomerId):                                 List[StoredMethod]

  // Group 2b: Mandates
  def createMandate(customerId: CustomerId, vaultId: VaultId, mandateType: MandateType): Mandate
  def getMandate(id: MandateId):                                           Mandate

  // Group 3: Subscriptions
  def createPlan(req: CreatePlanRequest):                                  Plan
  def subscribe(customerId: CustomerId, planId: PlanId, opts: SubscribeOpts): Subscription
  def changeSubscription(id: SubscriptionId, newPlanId: PlanId, mode: ProrationMode): Subscription
  def cancelSubscription(id: SubscriptionId, atPeriodEnd: Boolean = true): Subscription

  // Group 4: Refunds + Disputes
  def refund(req: RefundRequest):                                          Refund
  def submitDisputeEvidence(disputeId: DisputeId, evidence: DisputeEvidence): Dispute

  // Group 5: Webhook handling
  def webhookReceiver: WebhookReceiver[PaymentEvent]

object PaymentProvider:
  def named(id: String): PaymentProvider =
    ServiceLoader.load(classOf[PaymentProvider]).asScala
      .find(_.id == id)
      .getOrElse(throw IllegalArgumentException(s"No PaymentProvider registered with id: $id"))
