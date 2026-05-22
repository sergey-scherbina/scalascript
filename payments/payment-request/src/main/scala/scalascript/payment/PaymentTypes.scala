package scalascript.payment

/** Shared types for Payment Request API — used by both the JVM server
 *  implementation and the interpreter mock. */

case class Amount(currency: String, value: String)

case class PaymentItem(label: String, amount: Amount, pending: Boolean = false)

enum CardNetwork:
  case Visa, Mastercard, Amex, Discover, JCB, UnionPay, MIR

enum CardType:
  case Credit, Debit, Prepaid

enum GooglePayEnv:
  case Test, Production

enum ShippingType:
  case Shipping, Delivery, Pickup

enum PaymentComplete:
  case Success, Fail, Unknown

case class ShippingOption(
  id:       String,
  label:    String,
  amount:   Amount,
  selected: Boolean = false
)

case class PaymentOptions(
  requestPayerName:  Boolean      = false,
  requestPayerEmail: Boolean      = false,
  requestPayerPhone: Boolean      = false,
  requestShipping:   Boolean      = false,
  shippingType:      ShippingType = ShippingType.Shipping
)

case class ShippingAddress(
  country:     String,
  addressLine: List[String],
  region:      String,
  city:        String,
  postalCode:  String,
  recipient:   String,
  phone:       String
)

case class CardDetails(
  cardholderName:    String,
  cardNumber:        String,
  expiryMonth:       String,
  expiryYear:        String,
  cardSecurityCode:  String,
  billingAddress:    Option[ShippingAddress] = None
)

case class ApplePayHeader(
  ephemeralPublicKey: String,
  publicKeyHash:      String,
  transactionId:      String
)

case class ApplePayToken(
  version:   String,
  data:      String,
  signature: String,
  header:    ApplePayHeader
)

case class GooglePaySigningKey(
  signatures: List[String],
  signedKey:  String
)

case class GooglePayToken(
  signature:              String,
  intermediateSigningKey: GooglePaySigningKey,
  protocolVersion:        String,
  signedMessage:          String
)

case class GooglePayDecryptedCard(
  pan:          String,
  expiryMonth:  String,
  expiryYear:   String,
  authMethod:   String,
  cryptogram:   Option[String] = None,
  eciIndicator: Option[String] = None
)

case class ApplePayDecryptedToken(
  applicationPrimaryAccountNumber: String,
  expirationDate:                  String,
  currencyCode:                    Option[String],
  transactionAmount:               Option[Long],
  cardholderName:                  Option[String],
  onlinePaymentCryptogram:         Option[String]
)

// Error hierarchy
sealed class PaymentError(msg: String) extends RuntimeException(msg)
class PaymentNotSupportedError extends PaymentError("PaymentRequest is not supported in this context")
class PaymentAbortError         extends PaymentError("Payment was aborted by the user")
class PaymentCancelledError     extends PaymentError("Payment was cancelled")
class MerchantValidationError(msg: String) extends PaymentError(s"Merchant validation failed: $msg")
class TokenDecryptionError(msg: String)    extends PaymentError(s"Token decryption failed: $msg")
