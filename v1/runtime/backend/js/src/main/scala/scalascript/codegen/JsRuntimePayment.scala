package scalascript.codegen

/** Payment Request API JS runtime preamble — emitted when payment usage is detected.
 *
 *  Helpers translate ScalaScript type instances to the W3C JSON format expected
 *  by `new PaymentRequest(methodData, details, options)`. */
val JsRuntimePayment: String = JsRuntimeResource.load("payment.mjs")
