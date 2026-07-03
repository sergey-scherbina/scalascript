
// ── v1.38 Payment Request API preamble ───────────────────────────────────────

// ── Type constructors ─────────────────────────────────────────────────────────

function _Amount(currency, value) {
  return { currency, value };
}

function _PaymentItem(label, amount, pending) {
  return { label, amount, pending: !!pending };
}

function _PaymentMethodCard(networks, types) {
  return { _type: "card", networks: networks || [], types: types || [] };
}

function _PaymentMethodApplePay(merchantId, merchantName, countryCode) {
  return { _type: "apple-pay", merchantId, merchantName, countryCode: countryCode || "US" };
}

function _PaymentMethodGooglePay(merchantId, merchantName, environment) {
  return { _type: "google-pay", merchantId, merchantName, environment: environment || "TEST" };
}

// ── W3C JSON helpers ──────────────────────────────────────────────────────────

function _prMethodData(methods) {
  return methods.map(function(m) {
    if (m._type === "card") {
      const data = {};
      if (m.networks && m.networks.length) data.supportedNetworks = m.networks.map(function(n) { return n.toLowerCase(); });
      if (m.types && m.types.length) data.supportedTypes = m.types.map(function(t) { return t.toLowerCase(); });
      return { supportedMethods: "basic-card", data };
    } else if (m._type === "apple-pay") {
      return {
        supportedMethods: "https://apple.com/apple-pay",
        data: {
          version: 3,
          merchantIdentifier: m.merchantId,
          merchantCapabilities: ["supports3DS"],
          supportedNetworks: ["visa", "masterCard", "amex"],
          countryCode: m.countryCode
        }
      };
    } else if (m._type === "google-pay") {
      return {
        supportedMethods: "https://google.com/pay",
        data: {
          environment: m.environment,
          apiVersion: 2,
          apiVersionMinor: 0,
          merchantInfo: { merchantId: m.merchantId, merchantName: m.merchantName },
          allowedPaymentMethods: [{
            type: "CARD",
            parameters: { allowedAuthMethods: ["PAN_ONLY", "CRYPTOGRAM_3DS"], allowedCardNetworks: ["AMEX", "DISCOVER", "MASTERCARD", "VISA"] },
            tokenizationSpecification: { type: "PAYMENT_GATEWAY", parameters: { gateway: "example" } }
          }]
        }
      };
    }
    return { supportedMethods: m._type };
  });
}

function _prDetails(total, items, shippingOptions) {
  const details = { total: { label: total.label, amount: { currency: total.amount.currency, value: total.amount.value } } };
  if (items && items.length) {
    details.displayItems = items.map(function(i) {
      return { label: i.label, amount: { currency: i.amount.currency, value: i.amount.value }, pending: !!i.pending };
    });
  }
  if (shippingOptions && shippingOptions.length) {
    details.shippingOptions = shippingOptions.map(function(s) {
      return { id: s.id, label: s.label, amount: { currency: s.amount.currency, value: s.amount.value }, selected: !!s.selected };
    });
  }
  return details;
}

function _prOptions(opts) {
  if (!opts) return undefined;
  return {
    requestPayerName:  !!opts.requestPayerName,
    requestPayerEmail: !!opts.requestPayerEmail,
    requestPayerPhone: !!opts.requestPayerPhone,
    requestShipping:   !!opts.requestShipping,
    shippingType:      (opts.shippingType || "shipping").toLowerCase()
  };
}

// ── PaymentRequest wrapper ────────────────────────────────────────────────────

function _PaymentRequest(methods, total, items, opts, shippingOpts) {
  const methodData = _prMethodData(methods);
  const details    = _prDetails(total, items, shippingOpts);
  const options    = _prOptions(opts);
  const pr         = options ? new PaymentRequest(methodData, details, options)
                              : new PaymentRequest(methodData, details);
  pr._sscMethods = methods;
  return pr;
}

function _pr_canMakePayment(pr) {
  return pr.canMakePayment();
}

function _pr_show(pr) {
  return pr.show().then(function(resp) {
    resp._sscMethodName = resp.methodName;
    return resp;
  });
}

function _pr_abort(pr) {
  pr.abort();
}

function _pr_complete(resp, result) {
  const status = result === "Success" ? "success" : result === "Fail" ? "fail" : "unknown";
  return resp.complete(status);
}

function _pr_onMerchantValidation(pr, handler) {
  pr.addEventListener("merchantvalidation", function(e) {
    const p = handler(e);
    if (p && typeof p.then === "function") {
      p.then(function(session) { e.complete(session); });
    }
  });
}

function _pr_onShippingAddressChange(pr, handler) {
  pr.addEventListener("shippingaddresschange", function(e) {
    e.updateWith(handler(pr.shippingAddress));
  });
}

function _pr_onShippingOptionChange(pr, handler) {
  pr.addEventListener("shippingoptionchange", function(e) {
    e.updateWith(handler(pr.shippingOption));
  });
}
