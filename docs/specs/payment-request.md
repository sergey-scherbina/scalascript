# Payment Request API — Specification

**Status:** Draft v0.1
**Milestone:** v1.35
**Module:** `payments/payment-request`, `runtime/std/payment-request-plugin`

---

## 1. Overview

ScalaScript provides first-class support for the
[W3C Payment Request API](https://www.w3.org/TR/payment-request/) together with
server-side counterparts for Apple Pay merchant validation and Google Pay token
decryption. The same `.ssc` file can contain both the browser-side payment sheet
trigger and the server-side verification routes.

Supported payment methods:

| Method | Browser | Server verification |
|--------|---------|---------------------|
| Basic Card | ✓ | n/a |
| Apple Pay | ✓ | ✓ merchant validation + token |
| Google Pay | ✓ | ✓ token decryption |

---

## 2. Import

```scala
import payment
```

All types (`PaymentRequest`, `PaymentMethod`, `Amount`, …) become available
without further qualification.

---

## 3. Core Types

### 3.1 Amount

```scala
case class Amount(currency: String, value: String)
// currency: ISO 4217 code ("USD", "EUR", "GBP")
// value: decimal string ("49.99")
```

### 3.2 PaymentItem

```scala
case class PaymentItem(
  label:   String,
  amount:  Amount,
  pending: Boolean = false   // true = estimated, shown greyed out
)
```

### 3.3 PaymentMethod

```scala
enum PaymentMethod:
  case Card(
    networks: List[CardNetwork] = Nil,   // empty = all networks
    types:    List[CardType]    = Nil    // empty = all types
  )
  case ApplePay(
    merchantId:   String,                // "merchant.com.example"
    merchantName: String,
    countryCode:  String = "US"
  )
  case GooglePay(
    merchantId:   String,                // numeric Google merchant ID
    merchantName: String,
    environment:  GooglePayEnv = GooglePayEnv.Test
  )

enum CardNetwork: case Visa, Mastercard, Amex, Discover, JCB, UnionPay, MIR
enum CardType:    case Credit, Debit, Prepaid
enum GooglePayEnv: case Test, Production
```

### 3.4 ShippingOption

```scala
case class ShippingOption(
  id:       String,
  label:    String,
  amount:   Amount,
  selected: Boolean = false
)
```

### 3.5 PaymentOptions

```scala
case class PaymentOptions(
  requestPayerName:  Boolean      = false,
  requestPayerEmail: Boolean      = false,
  requestPayerPhone: Boolean      = false,
  requestShipping:   Boolean      = false,
  shippingType:      ShippingType = ShippingType.Shipping
)

enum ShippingType: case Shipping, Delivery, Pickup
```

### 3.6 ShippingAddress

```scala
case class ShippingAddress(
  country:     String,
  addressLine: List[String],
  region:      String,
  city:        String,
  postalCode:  String,
  recipient:   String,
  phone:       String
)
```

### 3.7 PaymentResponse

```scala
case class PaymentResponse(
  methodName:      String,                      // "basic-card", "https://apple.com/apple-pay", …
  details:         Map[String, String],         // raw method-specific details
  payerName:       Option[String],
  payerEmail:      Option[String],
  payerPhone:      Option[String],
  shippingAddress: Option[ShippingAddress],
  shippingOption:  Option[String]
):
  def complete(result: PaymentComplete): Future[Unit]
  def cardDetails:  Option[CardDetails]       // if methodName == "basic-card"
  def appleToken:   Option[ApplePayToken]     // if Apple Pay
  def googleToken:  Option[GooglePayToken]    // if Google Pay

enum PaymentComplete: case Success, Fail, Unknown
```

### 3.8 CardDetails

```scala
case class CardDetails(
  cardholderName:  String,
  cardNumber:      String,
  expiryMonth:     String,
  expiryYear:      String,
  cardSecurityCode: String,
  billingAddress:  Option[ShippingAddress]
)
```

### 3.9 Token types

```scala
case class ApplePayToken(
  version:          String,
  data:             String,    // Base64 encrypted payment data
  signature:        String,
  header:           ApplePayHeader
)
case class ApplePayHeader(
  ephemeralPublicKey: String,
  publicKeyHash:      String,
  transactionId:      String
)

case class GooglePayToken(
  signature:         String,
  intermediateSigningKey: GooglePaySigningKey,
  protocolVersion:   String,       // "ECv2"
  signedMessage:     String
)
case class GooglePaySigningKey(
  signatures:        List[String],
  signedKey:         String
)
```

---

## 4. PaymentRequest DSL

### 4.1 Creating a request

```scala
val request = PaymentRequest(
  methods = List(
    PaymentMethod.Card(networks = List(CardNetwork.Visa, CardNetwork.Mastercard)),
    PaymentMethod.ApplePay(merchantId = "merchant.com.example", merchantName = "My Shop"),
    PaymentMethod.GooglePay(merchantId = "123456789", merchantName = "My Shop",
                            environment = GooglePayEnv.Production)
  ),
  total = PaymentItem("Total", Amount("USD", "59.98")),
  items = List(
    PaymentItem("Widget",   Amount("USD", "49.99")),
    PaymentItem("Shipping", Amount("USD", "9.99"))
  ),
  options = PaymentOptions(
    requestPayerEmail = true,
    requestPayerName  = true,
    requestShipping   = true,
    shippingType      = ShippingType.Shipping
  ),
  shippingOptions = List(
    ShippingOption("standard", "Standard (5–7 days)", Amount("USD", "9.99"),  selected = true),
    ShippingOption("express",  "Express (1–2 days)",  Amount("USD", "19.99"))
  )
)
```

### 4.2 Checking availability

```scala
val available: Future[Boolean] = request.canMakePayment()
```

Returns `false` when no supported payment method is available or when called
outside a browser context (Node.js / JVM server targets return `false` without
throwing).

### 4.3 Showing the payment sheet

```scala
val response: Future[PaymentResponse] = request.show()

response
  .map { r =>
    // send token to server
    val ok = httpPost("/payment/verify", r.appleToken.orElse(r.googleToken).getOrElse(r.details))
    r.complete(if ok then PaymentComplete.Success else PaymentComplete.Fail)
  }
  .recover { case e =>
    println(s"Payment cancelled or failed: ${e.getMessage}")
  }
```

### 4.4 Aborting

```scala
request.abort()   // call before show() returns to dismiss the sheet
```

### 4.5 Updating details on shipping-address change

```scala
request.onShippingAddressChange { address =>
  // return updated PaymentDetails
  PaymentDetails(
    total = recalculateTotal(address),
    shippingOptions = shippingOptionsFor(address)
  )
}

request.onShippingOptionChange { optionId =>
  PaymentDetails(total = totalForOption(optionId))
}
```

---

## 5. Server-Side Integration

Server routes are written in the same `.ssc` file (or a companion server file)
and use JVM-only intrinsics from the `payment` import.

### 5.1 Apple Pay — Merchant Validation

Apple Pay requires a server endpoint that contacts Apple's validation URL and
returns a merchant session object to the browser.

```scala
route("POST", "/payment/apple/validate-merchant") { req =>
  val validationUrl = req.body[String]
  val session = ApplePay.validateMerchant(
    validationUrl = validationUrl,
    merchantId    = config.getString("apple-pay.merchant-id"),
    merchantName  = config.getString("apple-pay.merchant-name"),
    domainName    = config.getString("apple-pay.domain"),
    certPath      = config.getString("apple-pay.cert-pem"),
    keyPath       = config.getString("apple-pay.key-pem")
  )
  Response.json(session)
}
```

`ApplePay.validateMerchant` makes an mTLS HTTPS POST to `validationUrl` using
the merchant certificate and returns the opaque session JSON that the browser
passes to `completeMerchantValidation`.

The browser-side hooks into the `onMerchantValidation` callback:

```scala
request.onMerchantValidation { event =>
  httpPost("/payment/apple/validate-merchant", event.validationURL)
    .map(session => event.complete(session))
}
```

### 5.2 Apple Pay — Payment Token Processing

```scala
route("POST", "/payment/apple/process") { req =>
  val token = req.body[ApplePayToken]
  val decrypted = ApplePay.decryptToken(
    token      = token,
    certPath   = config.getString("apple-pay.cert-pem"),
    keyPath    = config.getString("apple-pay.key-pem")
  )
  // decrypted.applicationPrimaryAccountNumber — actual PAN
  // decrypted.expirationDate
  // decrypted.paymentData.onlinePaymentCryptogram
  val result = chargeCard(decrypted)
  Response.json(Map("success" -> result))
}
```

### 5.3 Google Pay — Token Decryption

```scala
route("POST", "/payment/google/process") { req =>
  val token = req.body[GooglePayToken]
  val card = GooglePay.decryptToken(
    token        = token,
    privateKeyPem = config.getString("google-pay.private-key-pem"),
    recipientId  = s"merchant:${config.getString("google-pay.merchant-id")}"
  )
  // card.pan, card.expiryMonth, card.expiryYear, card.authMethod
  // card.cryptogram (for CRYPTOGRAM_3DS), card.eciIndicator
  val result = chargeCard(card)
  Response.json(Map("success" -> result))
}
```

`GooglePay.decryptToken` verifies the signature chain, decrypts the
`signedMessage` using the merchant's ECDH private key (ECv2 protocol), and
returns a `GooglePayDecryptedCard`.

```scala
case class GooglePayDecryptedCard(
  pan:             String,
  expiryMonth:     String,
  expiryYear:      String,
  authMethod:      String,           // "PAN_ONLY" or "CRYPTOGRAM_3DS"
  cryptogram:      Option[String],
  eciIndicator:    Option[String]
)
```

---

## 6. Full Example

```scala
---
frontend: react
databases:
  payments:
    url: "${env:PAYMENT_DB_URL}"
config:
  files: [apple-pay.yaml, google-pay.yaml]
---

import payment

// ── Server routes ────────────────────────────────────────────────────────────

route("POST", "/payment/apple/validate-merchant") { req =>
  val session = ApplePay.validateMerchant(
    validationUrl = req.body[String],
    merchantId    = config.getString("apple-pay.merchant-id"),
    merchantName  = "ScalaScript Shop",
    domainName    = "shop.example.com",
    certPath      = config.getString("apple-pay.cert-pem"),
    keyPath       = config.getString("apple-pay.key-pem")
  )
  Response.json(session)
}

route("POST", "/payment/process") { req =>
  val body = req.body[Map[String, Any]]
  val result = body.get("appleToken") match
    case Some(t) =>
      val card = ApplePay.decryptToken(ApplePayToken.fromMap(t),
        config.getString("apple-pay.cert-pem"),
        config.getString("apple-pay.key-pem"))
      chargeApplePay(card)
    case None =>
      val token = GooglePayToken.fromMap(body("googleToken"))
      val card  = GooglePay.decryptToken(token,
        config.getString("google-pay.private-key-pem"),
        s"merchant:${config.getString("google-pay.merchant-id")}")
      chargeGooglePay(card)
  Response.json(Map("success" -> result))
}

// ── Frontend ─────────────────────────────────────────────────────────────────

val checkout = Button("Pay $59.98") {
  val request = PaymentRequest(
    methods = List(
      PaymentMethod.Card(),
      PaymentMethod.ApplePay(
        merchantId   = config.getString("apple-pay.merchant-id"),
        merchantName = "ScalaScript Shop"
      ),
      PaymentMethod.GooglePay(
        merchantId   = config.getString("google-pay.merchant-id"),
        merchantName = "ScalaScript Shop",
        environment  = GooglePayEnv.Production
      )
    ),
    total = PaymentItem("Total", Amount("USD", "59.98"))
  )

  request.onMerchantValidation { e =>
    httpPost("/payment/apple/validate-merchant", e.validationURL)
      .map(e.complete)
  }

  request.show().map { r =>
    val payload = r.appleToken
      .map(t => Map("appleToken" -> t))
      .orElse(r.googleToken.map(t => Map("googleToken" -> t)))
      .getOrElse(Map("card" -> r.cardDetails.get))
    httpPost("/payment/process", payload).map { resp =>
      r.complete(if resp.ok then PaymentComplete.Success else PaymentComplete.Fail)
    }
  }
}

serve(checkout, 8080)
```

---

## 7. Error Handling

| Exception | Meaning |
|-----------|---------|
| `PaymentNotSupportedError` | No payment method is supported on this device/browser |
| `PaymentAbortError` | User dismissed the payment sheet |
| `PaymentCancelledError` | `request.abort()` was called |
| `MerchantValidationError(msg)` | Apple Pay merchant session fetch failed |
| `TokenDecryptionError(msg)` | Server-side decryption of Apple/Google token failed |

All are subtypes of `PaymentError extends RuntimeException`.

---

## 8. Target Compatibility

| Intrinsic | Interpreter | JVM (server) | JS/Node | Browser SPA |
|-----------|------------|--------------|---------|-------------|
| `PaymentRequest.show` | mock | throws `PaymentNotSupportedError` | throws | ✓ native |
| `PaymentRequest.canMakePayment` | `Future(false)` | `Future(false)` | `Future(false)` | ✓ native |
| `ApplePay.validateMerchant` | mock OK | ✓ real mTLS | n/a | n/a |
| `ApplePay.decryptToken` | mock | ✓ | n/a | n/a |
| `GooglePay.decryptToken` | mock | ✓ | n/a | n/a |

---

## 9. Configuration (apple-pay.yaml example)

```yaml
apple-pay:
  merchant-id:   "merchant.com.example"
  merchant-name: "My Shop"
  domain:        "shop.example.com"
  cert-pem:      "${file:/run/secrets/apple-pay.crt}"
  key-pem:       "${file:/run/secrets/apple-pay.key}"

google-pay:
  merchant-id:      "123456789"
  private-key-pem:  "${file:/run/secrets/google-pay.pem}"
```

---

## 10. Architecture

```
runtime/std/payment-request-plugin/
  PaymentRequestIntrinsics.scala   ← interpreter mock + type registration
  PaymentRequestPlugin.scala       ← Backend/plugin wiring

runtime/backend/js/intrinsics/Payment.scala   ← browser JS emit
runtime/backend/jvm/intrinsics/Payment.scala  ← JVM RuntimeCall table

payments/payment-request/
  ApplePayClient.scala             ← mTLS merchant validation (JVM)
  ApplePayDecryptor.scala          ← ECDH token decryption (JVM)
  GooglePayDecryptor.scala         ← ECv2 token decryption (JVM)
  PaymentTypes.scala               ← shared Scala types
```

### JS emit

For `PaymentRequest(methods, total, items, options)` the JS backend emits:

```javascript
const _pr = new PaymentRequest(_prMethodData(methods), _prDetails(total, items), _prOptions(options));
```

Helper functions `_prMethodData`, `_prDetails`, `_prOptions` are emitted in the
payment preamble and handle the W3C-spec JSON mapping for each payment method
(including Apple Pay's `ApplePayRequest` and Google Pay's `PaymentDataRequest`).

### JVM emit

`ApplePay.validateMerchant(...)` and `GooglePay.decryptToken(...)` emit calls
to `scalascript.payment.ApplePayClient.validateMerchant(...)` and
`scalascript.payment.GooglePayDecryptor.decrypt(...)` — JVM classes in
`payments/payment-request/`.
