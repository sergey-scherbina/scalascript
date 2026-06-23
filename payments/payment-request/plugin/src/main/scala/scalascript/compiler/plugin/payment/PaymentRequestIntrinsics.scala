package scalascript.compiler.plugin.payment

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError}

/** Payment Request API intrinsics for the tree-walking interpreter.
 *
 *  Browser-side functions (show, canMakePayment, abort) return mock results
 *  suitable for automated testing without a real browser or payment network.
 *  Server-side functions (ApplePay.validateMerchant, GooglePay.decryptToken)
 *  are also mocked — real implementations live in payments/payment-request/. */
object PaymentRequestIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── PaymentRequest construction ───────────────────────────────────────

    QualifiedName("PaymentRequest") -> PluginNative.evalLegacy { (_, args) =>
      // Store methods + details in an instance for show() to read
      val fields: Map[String, PluginValue] = args match
        case List(methods, total, items, options, shippingOpts) =>
          Map("methods" -> methods.asInstanceOf[PluginValue], "total" -> total.asInstanceOf[PluginValue],
              "items" -> items.asInstanceOf[PluginValue], "options" -> options.asInstanceOf[PluginValue],
              "shippingOptions" -> shippingOpts.asInstanceOf[PluginValue])
        case List(methods, total, items) =>
          Map("methods" -> methods.asInstanceOf[PluginValue], "total" -> total.asInstanceOf[PluginValue],
              "items" -> items.asInstanceOf[PluginValue])
        case List(methods, total) =>
          Map("methods" -> methods.asInstanceOf[PluginValue], "total" -> total.asInstanceOf[PluginValue])
        case _ => PluginError.raise("PaymentRequest(methods, total, ...)")
      PluginValue.instance("PaymentRequest", fields)
    },

    // ── canMakePayment ────────────────────────────────────────────────────

    QualifiedName("PaymentRequest.canMakePayment") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("Future", Map("value" -> PluginValue.bool(true)))
    },

    // ── show — returns a mock PaymentResponse ─────────────────────────────

    QualifiedName("PaymentRequest.show") -> PluginNative.evalLegacy { (_, _) =>
      val response = PluginValue.instance("PaymentResponse", Map(
        "methodName"      -> PluginValue.string("basic-card"),
        "payerName"       -> PluginValue.instance("Some", Map("value" -> PluginValue.string("Test User"))),
        "payerEmail"      -> PluginValue.instance("Some", Map("value" -> PluginValue.string("test@example.com"))),
        "payerPhone"      -> PluginValue.instance("None", Map.empty),
        "shippingAddress" -> PluginValue.instance("None", Map.empty),
        "shippingOption"  -> PluginValue.instance("None", Map.empty),
        "details"         -> PluginValue.instance("Map", Map.empty)
      ))
      PluginValue.instance("Future", Map("value" -> response))
    },

    // ── complete ──────────────────────────────────────────────────────────

    QualifiedName("PaymentResponse.complete") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("Future", Map("value" -> PluginValue.unit))
    },

    // ── abort ─────────────────────────────────────────────────────────────

    QualifiedName("PaymentRequest.abort") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.unit
    },

    // ── event hooks (no-ops in interpreter) ───────────────────────────────

    QualifiedName("PaymentRequest.onMerchantValidation")    -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},
    QualifiedName("PaymentRequest.onShippingAddressChange") -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},
    QualifiedName("PaymentRequest.onShippingOptionChange")  -> PluginNative.evalLegacy { (_, _) => PluginValue.unit},

    // ── ApplePay server intrinsics (mocked) ───────────────────────────────

    QualifiedName("ApplePay.validateMerchant") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(_), PluginValue.Str(id), PluginValue.Str(name), PluginValue.Str(domain), _, _) =>
          val mockSession = s"""{"merchantSessionIdentifier":"MOCK_SESSION","merchantIdentifier":"$id","domainName":"$domain","displayName":"$name","nonce":"mock","signature":"mock"}"""
          PluginValue.string(mockSession)
        case _ => PluginError.raise("ApplePay.validateMerchant(url, merchantId, merchantName, domain, certPath, keyPath)")
    },

    QualifiedName("ApplePay.decryptToken") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("ApplePayDecryptedToken", Map(
        "applicationPrimaryAccountNumber" -> PluginValue.string("4111111111111111"),
        "expirationDate"                  -> PluginValue.string("2512"),
        "currencyCode"                    -> PluginValue.instance("Some", Map("value" -> PluginValue.string("USD"))),
        "transactionAmount"               -> PluginValue.instance("Some", Map("value" -> PluginValue.int(4999L))),
        "cardholderName"                  -> PluginValue.instance("Some", Map("value" -> PluginValue.string("Test User"))),
        "onlinePaymentCryptogram"         -> PluginValue.instance("Some", Map("value" -> PluginValue.string("MOCK_CRYPTOGRAM")))
      ))
    },

    // ── GooglePay server intrinsics (mocked) ──────────────────────────────

    QualifiedName("GooglePay.decryptToken") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.instance("GooglePayDecryptedCard", Map(
        "pan"          -> PluginValue.string("4111111111111111"),
        "expiryMonth"  -> PluginValue.string("12"),
        "expiryYear"   -> PluginValue.string("2025"),
        "authMethod"   -> PluginValue.string("PAN_ONLY"),
        "cryptogram"   -> PluginValue.instance("None", Map.empty),
        "eciIndicator" -> PluginValue.instance("None", Map.empty)
      ))
    },

    // ── PaymentMethod constructors ────────────────────────────────────────

    QualifiedName("PaymentMethod.Card") -> PluginNative.evalLegacy { (_, args) =>
      val networks = args.headOption.getOrElse(PluginValue.list(Nil)).asInstanceOf[PluginValue]
      val types    = args.lift(1).getOrElse(PluginValue.list(Nil)).asInstanceOf[PluginValue]
      PluginValue.instance("PaymentMethod.Card", Map("networks" -> networks, "types" -> types))
    },

    QualifiedName("PaymentMethod.ApplePay") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(id), PluginValue.Str(name)) =>
          PluginValue.instance("PaymentMethod.ApplePay",
            Map("merchantId" -> PluginValue.string(id), "merchantName" -> PluginValue.string(name),
                "countryCode" -> PluginValue.string("US")))
        case List(PluginValue.Str(id), PluginValue.Str(name), PluginValue.Str(cc)) =>
          PluginValue.instance("PaymentMethod.ApplePay",
            Map("merchantId" -> PluginValue.string(id), "merchantName" -> PluginValue.string(name),
                "countryCode" -> PluginValue.string(cc)))
        case _ => PluginError.raise("PaymentMethod.ApplePay(merchantId, merchantName[, countryCode])")
    },

    QualifiedName("PaymentMethod.GooglePay") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(id), PluginValue.Str(name)) =>
          PluginValue.instance("PaymentMethod.GooglePay",
            Map("merchantId" -> PluginValue.string(id), "merchantName" -> PluginValue.string(name),
                "environment" -> PluginValue.string("TEST")))
        case List(PluginValue.Str(id), PluginValue.Str(name), env) =>
          PluginValue.instance("PaymentMethod.GooglePay",
            Map("merchantId" -> PluginValue.string(id), "merchantName" -> PluginValue.string(name),
                "environment" -> env.asInstanceOf[PluginValue]))
        case _ => PluginError.raise("PaymentMethod.GooglePay(merchantId, merchantName[, environment])")
    },

    // ── Amount constructor ────────────────────────────────────────────────

    QualifiedName("Amount") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(currency), PluginValue.Str(value)) =>
          PluginValue.instance("Amount", Map("currency" -> PluginValue.string(currency), "value" -> PluginValue.string(value)))
        case _ => PluginError.raise("Amount(currency, value)")
    },

    // ── PaymentItem constructor ───────────────────────────────────────────

    QualifiedName("PaymentItem") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(label), amount) =>
          PluginValue.instance("PaymentItem", Map("label" -> PluginValue.string(label), "amount" -> amount.asInstanceOf[PluginValue],
                                             "pending" -> PluginValue.bool(false)))
        case List(PluginValue.Str(label), amount, PluginValue.Bool(pending)) =>
          PluginValue.instance("PaymentItem", Map("label" -> PluginValue.string(label), "amount" -> amount.asInstanceOf[PluginValue],
                                             "pending" -> PluginValue.bool(pending)))
        case _ => PluginError.raise("PaymentItem(label, amount[, pending])")
    },
  )
