package scalascript.compiler.plugin.payment

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError}

/** Payment Request API intrinsics for the tree-walking interpreter.
 *
 *  Browser-side functions (show, canMakePayment, abort) return mock results
 *  suitable for automated testing without a real browser or payment network.
 *  Server-side functions (ApplePay.validateMerchant, GooglePay.decryptToken)
 *  are also mocked — real implementations live in payments/payment-request/. */
object PaymentRequestIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── PaymentRequest construction ───────────────────────────────────────

    QualifiedName("PaymentRequest") -> NativeImpl((_, args) =>
      // Store methods + details in an instance for show() to read
      val fields: Map[String, Value] = args match
        case List(methods, total, items, options, shippingOpts) =>
          Map("methods" -> methods.asInstanceOf[Value], "total" -> total.asInstanceOf[Value],
              "items" -> items.asInstanceOf[Value], "options" -> options.asInstanceOf[Value],
              "shippingOptions" -> shippingOpts.asInstanceOf[Value])
        case List(methods, total, items) =>
          Map("methods" -> methods.asInstanceOf[Value], "total" -> total.asInstanceOf[Value],
              "items" -> items.asInstanceOf[Value])
        case List(methods, total) =>
          Map("methods" -> methods.asInstanceOf[Value], "total" -> total.asInstanceOf[Value])
        case _ => throw InterpretError("PaymentRequest(methods, total, ...)")
      Value.InstanceV("PaymentRequest", fields)
    ),

    // ── canMakePayment ────────────────────────────────────────────────────

    QualifiedName("PaymentRequest.canMakePayment") -> NativeImpl((_, _) =>
      Value.InstanceV("Future", Map("value" -> Value.BoolV(true)))
    ),

    // ── show — returns a mock PaymentResponse ─────────────────────────────

    QualifiedName("PaymentRequest.show") -> NativeImpl((_, _) =>
      val response = Value.InstanceV("PaymentResponse", Map(
        "methodName"      -> Value.StringV("basic-card"),
        "payerName"       -> Value.InstanceV("Some", Map("value" -> Value.StringV("Test User"))),
        "payerEmail"      -> Value.InstanceV("Some", Map("value" -> Value.StringV("test@example.com"))),
        "payerPhone"      -> Value.InstanceV("None", Map.empty),
        "shippingAddress" -> Value.InstanceV("None", Map.empty),
        "shippingOption"  -> Value.InstanceV("None", Map.empty),
        "details"         -> Value.InstanceV("Map", Map.empty)
      ))
      Value.InstanceV("Future", Map("value" -> response))
    ),

    // ── complete ──────────────────────────────────────────────────────────

    QualifiedName("PaymentResponse.complete") -> NativeImpl((_, _) =>
      Value.InstanceV("Future", Map("value" -> Value.UnitV))
    ),

    // ── abort ─────────────────────────────────────────────────────────────

    QualifiedName("PaymentRequest.abort") -> NativeImpl((_, _) =>
      Value.UnitV
    ),

    // ── event hooks (no-ops in interpreter) ───────────────────────────────

    QualifiedName("PaymentRequest.onMerchantValidation")    -> NativeImpl((_, _) => Value.UnitV),
    QualifiedName("PaymentRequest.onShippingAddressChange") -> NativeImpl((_, _) => Value.UnitV),
    QualifiedName("PaymentRequest.onShippingOptionChange")  -> NativeImpl((_, _) => Value.UnitV),

    // ── ApplePay server intrinsics (mocked) ───────────────────────────────

    QualifiedName("ApplePay.validateMerchant") -> NativeImpl((_, args) =>
      args match
        case List(Value.StringV(_), Value.StringV(id), Value.StringV(name), Value.StringV(domain), _, _) =>
          val mockSession = s"""{"merchantSessionIdentifier":"MOCK_SESSION","merchantIdentifier":"$id","domainName":"$domain","displayName":"$name","nonce":"mock","signature":"mock"}"""
          Value.StringV(mockSession)
        case _ => throw InterpretError("ApplePay.validateMerchant(url, merchantId, merchantName, domain, certPath, keyPath)")
    ),

    QualifiedName("ApplePay.decryptToken") -> NativeImpl((_, _) =>
      Value.InstanceV("ApplePayDecryptedToken", Map(
        "applicationPrimaryAccountNumber" -> Value.StringV("4111111111111111"),
        "expirationDate"                  -> Value.StringV("2512"),
        "currencyCode"                    -> Value.InstanceV("Some", Map("value" -> Value.StringV("USD"))),
        "transactionAmount"               -> Value.InstanceV("Some", Map("value" -> Value.IntV(4999L))),
        "cardholderName"                  -> Value.InstanceV("Some", Map("value" -> Value.StringV("Test User"))),
        "onlinePaymentCryptogram"         -> Value.InstanceV("Some", Map("value" -> Value.StringV("MOCK_CRYPTOGRAM")))
      ))
    ),

    // ── GooglePay server intrinsics (mocked) ──────────────────────────────

    QualifiedName("GooglePay.decryptToken") -> NativeImpl((_, _) =>
      Value.InstanceV("GooglePayDecryptedCard", Map(
        "pan"          -> Value.StringV("4111111111111111"),
        "expiryMonth"  -> Value.StringV("12"),
        "expiryYear"   -> Value.StringV("2025"),
        "authMethod"   -> Value.StringV("PAN_ONLY"),
        "cryptogram"   -> Value.InstanceV("None", Map.empty),
        "eciIndicator" -> Value.InstanceV("None", Map.empty)
      ))
    ),

    // ── PaymentMethod constructors ────────────────────────────────────────

    QualifiedName("PaymentMethod.Card") -> NativeImpl((_, args) =>
      val networks = args.headOption.getOrElse(Value.ListV(Nil)).asInstanceOf[Value]
      val types    = args.lift(1).getOrElse(Value.ListV(Nil)).asInstanceOf[Value]
      Value.InstanceV("PaymentMethod.Card", Map("networks" -> networks, "types" -> types))
    ),

    QualifiedName("PaymentMethod.ApplePay") -> NativeImpl((_, args) =>
      args match
        case List(Value.StringV(id), Value.StringV(name)) =>
          Value.InstanceV("PaymentMethod.ApplePay",
            Map("merchantId" -> Value.StringV(id), "merchantName" -> Value.StringV(name),
                "countryCode" -> Value.StringV("US")))
        case List(Value.StringV(id), Value.StringV(name), Value.StringV(cc)) =>
          Value.InstanceV("PaymentMethod.ApplePay",
            Map("merchantId" -> Value.StringV(id), "merchantName" -> Value.StringV(name),
                "countryCode" -> Value.StringV(cc)))
        case _ => throw InterpretError("PaymentMethod.ApplePay(merchantId, merchantName[, countryCode])")
    ),

    QualifiedName("PaymentMethod.GooglePay") -> NativeImpl((_, args) =>
      args match
        case List(Value.StringV(id), Value.StringV(name)) =>
          Value.InstanceV("PaymentMethod.GooglePay",
            Map("merchantId" -> Value.StringV(id), "merchantName" -> Value.StringV(name),
                "environment" -> Value.StringV("TEST")))
        case List(Value.StringV(id), Value.StringV(name), env) =>
          Value.InstanceV("PaymentMethod.GooglePay",
            Map("merchantId" -> Value.StringV(id), "merchantName" -> Value.StringV(name),
                "environment" -> env.asInstanceOf[Value]))
        case _ => throw InterpretError("PaymentMethod.GooglePay(merchantId, merchantName[, environment])")
    ),

    // ── Amount constructor ────────────────────────────────────────────────

    QualifiedName("Amount") -> NativeImpl((_, args) =>
      args match
        case List(Value.StringV(currency), Value.StringV(value)) =>
          Value.InstanceV("Amount", Map("currency" -> Value.StringV(currency), "value" -> Value.StringV(value)))
        case _ => throw InterpretError("Amount(currency, value)")
    ),

    // ── PaymentItem constructor ───────────────────────────────────────────

    QualifiedName("PaymentItem") -> NativeImpl((_, args) =>
      args match
        case List(Value.StringV(label), amount) =>
          Value.InstanceV("PaymentItem", Map("label" -> Value.StringV(label), "amount" -> amount.asInstanceOf[Value],
                                             "pending" -> Value.BoolV(false)))
        case List(Value.StringV(label), amount, Value.BoolV(pending)) =>
          Value.InstanceV("PaymentItem", Map("label" -> Value.StringV(label), "amount" -> amount.asInstanceOf[Value],
                                             "pending" -> Value.BoolV(pending)))
        case _ => throw InterpretError("PaymentItem(label, amount[, pending])")
    ),
  )
