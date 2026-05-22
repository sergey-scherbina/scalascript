package scalascript.codegen.jvm

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Payment Request API intrinsics for the JVM backend.
 *
 *  Browser-side PaymentRequest calls throw PaymentNotSupportedError at
 *  runtime on JVM (§8 target compatibility).  Server-side Apple Pay and
 *  Google Pay calls emit to scalascript.payment.ApplePayClient /
 *  scalascript.payment.GooglePayDecryptor in payments/payment-request/. */
val JvmPaymentIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // ── PaymentRequest (browser-only — throws on JVM) ────────────────────────
  QualifiedName("PaymentRequest")                    -> RuntimeCall("scalascript.payment.PaymentRequestStubs.create"),
  QualifiedName("PaymentRequest.canMakePayment")     -> RuntimeCall("scalascript.payment.PaymentRequestStubs.canMakePayment"),
  QualifiedName("PaymentRequest.show")               -> RuntimeCall("scalascript.payment.PaymentRequestStubs.show"),
  QualifiedName("PaymentRequest.abort")              -> RuntimeCall("scalascript.payment.PaymentRequestStubs.abort"),
  QualifiedName("PaymentResponse.complete")          -> RuntimeCall("scalascript.payment.PaymentRequestStubs.complete"),
  QualifiedName("PaymentRequest.onMerchantValidation")    -> RuntimeCall("scalascript.payment.PaymentRequestStubs.onMerchantValidation"),
  QualifiedName("PaymentRequest.onShippingAddressChange") -> RuntimeCall("scalascript.payment.PaymentRequestStubs.onShippingAddressChange"),
  QualifiedName("PaymentRequest.onShippingOptionChange")  -> RuntimeCall("scalascript.payment.PaymentRequestStubs.onShippingOptionChange"),
  // ── Apple Pay server intrinsics ───────────────────────────────────────────
  QualifiedName("ApplePay.validateMerchant")         -> RuntimeCall("scalascript.payment.ApplePayClient.validateMerchant"),
  QualifiedName("ApplePay.decryptToken")             -> RuntimeCall("scalascript.payment.ApplePayClient.decryptToken"),
  // ── Google Pay server intrinsics ──────────────────────────────────────────
  QualifiedName("GooglePay.decryptToken")            -> RuntimeCall("scalascript.payment.GooglePayDecryptor.decrypt"),
  // ── Type constructors ─────────────────────────────────────────────────────
  QualifiedName("PaymentMethod.Card")                -> RuntimeCall("scalascript.payment.PaymentMethodStubs.card"),
  QualifiedName("PaymentMethod.ApplePay")            -> RuntimeCall("scalascript.payment.PaymentMethodStubs.applePay"),
  QualifiedName("PaymentMethod.GooglePay")           -> RuntimeCall("scalascript.payment.PaymentMethodStubs.googlePay"),
  QualifiedName("Amount")                            -> RuntimeCall("scalascript.payment.Amount.apply"),
  QualifiedName("PaymentItem")                       -> RuntimeCall("scalascript.payment.PaymentItem.apply"),
)
