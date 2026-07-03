package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Payment Request API intrinsics for the JS (browser/Node) backend.
 *
 *  Browser targets use the native `window.PaymentRequest` — the JS preamble
 *  emits `_prMethodData`, `_prDetails`, `_prOptions` helpers that map
 *  ScalaScript types to the W3C JSON format.  Node targets (server routes)
 *  emit calls to the JVM-equivalent runtime stubs so the same .ssc file
 *  compiles for both. */
val JsPaymentIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // ── PaymentRequest lifecycle ────────────────────────────────────────────
  QualifiedName("PaymentRequest")                    -> RuntimeCall("_PaymentRequest"),
  QualifiedName("PaymentRequest.canMakePayment")     -> RuntimeCall("_pr_canMakePayment"),
  QualifiedName("PaymentRequest.show")               -> RuntimeCall("_pr_show"),
  QualifiedName("PaymentRequest.abort")              -> RuntimeCall("_pr_abort"),
  // ── PaymentResponse ──────────────────────────────────────────────────────
  QualifiedName("PaymentResponse.complete")          -> RuntimeCall("_pr_complete"),
  // ── Event hooks ──────────────────────────────────────────────────────────
  QualifiedName("PaymentRequest.onMerchantValidation")    -> RuntimeCall("_pr_onMerchantValidation"),
  QualifiedName("PaymentRequest.onShippingAddressChange") -> RuntimeCall("_pr_onShippingAddressChange"),
  QualifiedName("PaymentRequest.onShippingOptionChange")  -> RuntimeCall("_pr_onShippingOptionChange"),
  // ── Type constructors (mapped to plain JS objects) ───────────────────────
  QualifiedName("PaymentMethod.Card")                -> RuntimeCall("_PaymentMethodCard"),
  QualifiedName("PaymentMethod.ApplePay")            -> RuntimeCall("_PaymentMethodApplePay"),
  QualifiedName("PaymentMethod.GooglePay")           -> RuntimeCall("_PaymentMethodGooglePay"),
  QualifiedName("Amount")                            -> RuntimeCall("_Amount"),
  QualifiedName("PaymentItem")                       -> RuntimeCall("_PaymentItem"),
)
