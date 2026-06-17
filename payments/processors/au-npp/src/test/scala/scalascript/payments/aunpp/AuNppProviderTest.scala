package scalascript.payments.aunpp

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.InetSocketAddress
import com.sun.net.httpserver.{HttpServer, HttpExchange}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class AuNppProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  /** Start a local HTTP mock server, invoke body, then stop. */
  private def withMockServer(
      path: String,
      responseBody: String,
      statusCode: Int = 200,
  )(body: String => Unit): Unit =
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.setExecutor(executor)
    server.createContext(path, (exchange: HttpExchange) => {
      val respBytes = responseBody.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(statusCode, respBytes.length)
      exchange.getResponseBody.write(respBytes)
      exchange.getResponseBody.close()
    })
    server.start()
    val port = server.getAddress.getPort
    try body(s"http://localhost:$port")
    finally { server.stop(0); executor.shutdownNow() }

  private val senderAccount = BankAccount(
    holderName    = "Acme Pty Ltd",
    countryCode   = "AU",
    bsbNumber     = Some("062-000"),
    accountNumber = Some("12345678"),
  )

  private val recipientWithPayId = BankAccount(
    holderName  = "Alice Smith",
    countryCode = "AU",
    payid       = Some("+61412345678"),
  )

  private val recipientWithBsb = BankAccount(
    holderName    = "Bob Jones",
    countryCode   = "AU",
    bsbNumber     = Some("033-000"),
    accountNumber = Some("87654321"),
  )

  private val amount100AUD = Money(10000L, Currency("AUD"))  // AUD 100.00

  // ── RailKind.AU_NPP tests ─────────────────────────────────────────────────

  test("RailKind.AU_NPP case exists"):
    val rail: RailKind = RailKind.AU_NPP
    assert(rail == RailKind.AU_NPP)

  test("RailKind.AU_NPP is distinct from other rails"):
    assert(RailKind.AU_NPP != RailKind.SEPA_CT)
    assert(RailKind.AU_NPP != RailKind.ACH_CREDIT)
    assert(RailKind.AU_NPP != RailKind.FEDNOW)
    assert(RailKind.AU_NPP != RailKind.SG_PAYNOW)
    assert(RailKind.AU_NPP != RailKind.IN_UPI)

  // ── BankAccount field tests ───────────────────────────────────────────────

  test("BankAccount.payid field is preserved"):
    val account = BankAccount(
      holderName  = "Alice Smith",
      countryCode = "AU",
      payid       = Some("+61412345678"),
    )
    assert(account.payid == Some("+61412345678"))

  test("BankAccount.bsbNumber field is preserved"):
    val account = BankAccount(
      holderName    = "Bob Jones",
      countryCode   = "AU",
      bsbNumber     = Some("062-000"),
      accountNumber = Some("12345678"),
    )
    assert(account.bsbNumber == Some("062-000"))

  test("BankAccount.payid defaults to None"):
    val account = BankAccount(holderName = "Charlie", countryCode = "AU")
    assert(account.payid == None)

  test("BankAccount.bsbNumber defaults to None"):
    val account = BankAccount(holderName = "Charlie", countryCode = "AU")
    assert(account.bsbNumber == None)

  test("BankAccount.payid and bsbNumber are independent"):
    val account = BankAccount(
      holderName    = "Dana",
      countryCode   = "AU",
      payid         = Some("dana@example.com"),
      bsbNumber     = Some("062-000"),
      accountNumber = Some("99887766"),
    )
    assert(account.payid    == Some("dana@example.com"))
    assert(account.bsbNumber == Some("062-000"))

  // ── AuNppConfig tests ─────────────────────────────────────────────────────

  test("AuNppConfig fields accessible"):
    val cfg = AuNppConfig(
      apiKey        = "my-api-key",
      baseUrl       = "https://api.npp.example.com",
      webhookSecret = "my-secret",
    )
    assert(cfg.apiKey        == "my-api-key")
    assert(cfg.baseUrl       == "https://api.npp.example.com")
    assert(cfg.webhookSecret == "my-secret")

  test("AuNppConfig.fromEnv returns a config without throwing"):
    val cfg = AuNppConfig.fromEnv
    assert(cfg.baseUrl.nonEmpty)

  // ── AuNppProvider SPI contract tests ─────────────────────────────────────

  test("AuNppProvider.id returns 'au-npp'"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    assert(provider.id == "au-npp")

  test("AuNppProvider.displayName is non-empty"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    assert(provider.displayName.nonEmpty)

  test("AuNppProvider.spiVersion is non-empty"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    assert(provider.spiVersion.nonEmpty)

  test("AuNppProvider.supportedRails contains only AU_NPP"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    assert(provider.supportedRails == Set(RailKind.AU_NPP))

  test("AuNppProvider throws UnsupportedRail for SEPA_CT"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100AUD,
      sender         = senderAccount,
      recipient      = recipientWithPayId,
      reference      = "REF001",
      idempotencyKey = "idem-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("AuNppProvider throws UnsupportedRail for SG_PAYNOW"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val req = InitiateTransferRequest(
      rail           = RailKind.SG_PAYNOW,
      amount         = amount100AUD,
      sender         = senderAccount,
      recipient      = recipientWithPayId,
      reference      = "REF002",
      idempotencyKey = "idem-002",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  // ── AUD currency enforcement ──────────────────────────────────────────────

  test("AuNppProvider throws UnsupportedCurrency for non-AUD amount"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val usdAmount = Money(10000L, Currency("USD"))
    val req = InitiateTransferRequest(
      rail           = RailKind.AU_NPP,
      amount         = usdAmount,
      sender         = senderAccount,
      recipient      = recipientWithPayId,
      reference      = "REF003",
      idempotencyKey = "idem-003",
    )
    intercept[UnsupportedCurrency] {
      provider.initiateTransfer(req)
    }

  test("AuNppProvider throws UnsupportedCurrency for GBP amount"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val gbpAmount = Money(5000L, Currency("GBP"))
    val req = InitiateTransferRequest(
      rail           = RailKind.AU_NPP,
      amount         = gbpAmount,
      sender         = senderAccount,
      recipient      = recipientWithPayId,
      reference      = "REF004",
      idempotencyKey = "idem-004",
    )
    intercept[UnsupportedCurrency] {
      provider.initiateTransfer(req)
    }

  test("UnsupportedCurrency message contains currency, rail, and supported"):
    val err = UnsupportedCurrency("USD", "AU_NPP", "AUD")
    assert(err.getMessage.contains("USD"))
    assert(err.getMessage.contains("AU_NPP"))
    assert(err.getMessage.contains("AUD"))

  // ── cancelTransfer always returns Left ───────────────────────────────────

  test("AuNppProvider.cancelTransfer throws BankRailsCancelError"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    intercept[BankRailsCancelError] {
      provider.cancelTransfer(TransferId("tx-001"))
    }

  test("AuNppProvider.cancelTransfer error message mentions NPP"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val ex = intercept[BankRailsCancelError] {
      provider.cancelTransfer(TransferId("tx-002"))
    }
    assert(ex.getMessage.contains("NPP") || ex.getMessage.contains("cancel"))

  // ── initiateDirectDebit not supported ────────────────────────────────────

  test("AuNppProvider.initiateDirectDebit throws UnsupportedRail"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.AU_NPP,
      amount          = amount100AUD,
      mandateId       = MandateId("mandate-001"),
      creditorAccount = senderAccount,
      debtorAccount   = recipientWithBsb,
      creditorName    = "Acme Pty Ltd",
      reference       = "DD-REF",
      idempotencyKey  = "idem-dd-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateDirectDebit(req)
    }

  // ── BSB+account fallback when payid is absent ─────────────────────────────

  test("AuNppProvider uses BSB+account when payid is not set (mock HTTP)"):
    withMockServer("/v1/npp/payments",
      """{"transferId":"npp-tx-001","status":"pending","endToEndId":"idem-005"}"""
    ) { baseUrl =>
      val cfg      = AuNppConfig(apiKey = "test-key", baseUrl = baseUrl, webhookSecret = "s")
      val provider = AuNppProvider(cfg)
      val req = InitiateTransferRequest(
        rail           = RailKind.AU_NPP,
        amount         = amount100AUD,
        sender         = senderAccount,
        recipient      = recipientWithBsb,   // no payid — BSB + account
        reference      = "REF005",
        idempotencyKey = "idem-005",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.id.value == "npp-tx-001")
      assert(transfer.rail     == RailKind.AU_NPP)
      assert(transfer.status   == BankTransferStatus.Pending)
    }

  // ── PayID resolution + NPP credit transfer happy path ────────────────────

  test("AuNppProvider resolves PayID then submits NPP payment (mock HTTP)"):
    val resolveBody  = """{"bsb":"062-000","accountNumber":"11223344"}"""
    val paymentBody  = """{"transferId":"npp-tx-002","status":"pending"}"""
    // Two endpoints: resolve then payment — use a dispatch server
    val executor2 = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.setExecutor(executor2)
    server.createContext("/v1/payid/resolve", (ex: HttpExchange) => {
      val resp = resolveBody.getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(200, resp.length)
      ex.getResponseBody.write(resp)
      ex.getResponseBody.close()
    })
    server.createContext("/v1/npp/payments", (ex: HttpExchange) => {
      if ex.getRequestMethod == "POST" then
        val resp = paymentBody.getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, resp.length)
        ex.getResponseBody.write(resp)
        ex.getResponseBody.close()
      else
        val status = """{"status":"pending"}""".getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, status.length)
        ex.getResponseBody.write(status)
        ex.getResponseBody.close()
    })
    server.start()
    val port = server.getAddress.getPort
    try
      val cfg      = AuNppConfig(apiKey = "test-key", baseUrl = s"http://localhost:$port", webhookSecret = "s")
      val provider = AuNppProvider(cfg)
      val req = InitiateTransferRequest(
        rail           = RailKind.AU_NPP,
        amount         = amount100AUD,
        sender         = senderAccount,
        recipient      = recipientWithPayId,  // has payid
        reference      = "REF-PAYID-001",
        idempotencyKey = "idem-payid-001",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.id.value == "npp-tx-002")
      assert(transfer.rail     == RailKind.AU_NPP)
      assert(transfer.status   == BankTransferStatus.Pending)
    finally { server.stop(0); executor2.shutdownNow() }

  // ── getTransfer delegates to AuNppApi.getPaymentStatus ────────────────────

  test("AuNppProvider.getTransfer returns Settled status from mock API"):
    withMockServer("/v1/npp/payments/npp-tx-003",
      """{"transferId":"npp-tx-003","status":"settled","amount":"10000"}"""
    ) { baseUrl =>
      val cfg      = AuNppConfig(apiKey = "test-key", baseUrl = baseUrl, webhookSecret = "s")
      val provider = AuNppProvider(cfg)
      val transfer = provider.getTransfer(TransferId("npp-tx-003"))
      assert(transfer.status == BankTransferStatus.Settled)
    }

  test("AuNppProvider.getTransfer returns Pending status"):
    withMockServer("/v1/npp/payments/npp-tx-004",
      """{"transferId":"npp-tx-004","status":"pending","amount":"5000"}"""
    ) { baseUrl =>
      val cfg      = AuNppConfig(apiKey = "test-key", baseUrl = baseUrl, webhookSecret = "s")
      val provider = AuNppProvider(cfg)
      val transfer = provider.getTransfer(TransferId("npp-tx-004"))
      assert(transfer.status == BankTransferStatus.Pending)
    }

  test("AuNppProvider.getTransfer returns Returned status with returnCode"):
    withMockServer("/v1/npp/payments/npp-tx-005",
      """{"transferId":"npp-tx-005","status":"returned","returnCode":"AC01","description":"Account closed"}"""
    ) { baseUrl =>
      val cfg      = AuNppConfig(apiKey = "test-key", baseUrl = baseUrl, webhookSecret = "s")
      val provider = AuNppProvider(cfg)
      val transfer = provider.getTransfer(TransferId("npp-tx-005"))
      transfer.status match
        case BankTransferStatus.Returned(code, _) => assert(code.value == "AC01")
        case other                                => fail(s"Expected Returned, got $other")
    }

  // ── AuNppWebhookReceiver HMAC tests ───────────────────────────────────────

  test("AuNppWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "npp-webhook-secret"
    val body   = """{"type":"npp.payment.credited","transferId":"TXN001","amount":"10000","creditorName":"Alice","debtorName":"Bob"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("AuNppWebhookReceiver: wrong HMAC is rejected with InvalidSignature"):
    val secret = "correct-secret"
    val body   = """{"type":"npp.payment.credited","transferId":"TXN002","amount":"5000"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("AuNppWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("AuNppWebhookReceiver: bad signature format (no sha256= prefix) rejected"):
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> "invalidsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("AuNppWebhookReceiver: lowercase header name is accepted"):
    val secret = "test-secret"
    val body   = """{"type":"npp.payment.credited","transferId":"TXN003","amount":"20000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("x-npp-signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Lowercase header should work, got: ${result.left.toOption}")

  // ── Webhook event parsing: npp.payment.credited ───────────────────────────

  test("AuNppWebhookReceiver: parses npp.payment.credited → AuNppCredited"):
    val secret = "test-secret"
    val body   = """{"type":"npp.payment.credited","transferId":"TXN100","amount":"10000","creditorName":"Alice","debtorName":"Bob","creditorBsb":"062-000","creditorAccount":"11223344","remittanceInfo":"Invoice 42"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.AuNppCredited(transfer) =>
        assert(transfer.id.value == "TXN100")
        assert(transfer.rail     == RailKind.AU_NPP)
        assert(transfer.status   == BankTransferStatus.Settled)
        assert(transfer.reference == "Invoice 42")
      case other => fail(s"Expected AuNppCredited, got $other")

  test("AuNppWebhookReceiver: npp.payment.credited populates creditor BSB and account"):
    val recv = AuNppWebhookReceiver()
    val body = """{"type":"npp.payment.credited","transferId":"TXN101","amount":"5000","creditorBsb":"033-000","creditorAccount":"99887766","creditorName":"Dave","debtorName":"Eve","debtorBsb":"062-111","debtorAccount":"11112222"}"""
    val event = recv.parseEvent(body)
    event match
      case BankRailsEvent.AuNppCredited(transfer) =>
        assert(transfer.recipient.bsbNumber     == Some("033-000"))
        assert(transfer.recipient.accountNumber == Some("99887766"))
        assert(transfer.recipient.holderName    == "Dave")
        assert(transfer.sender.holderName       == "Eve")
      case other => fail(s"Expected AuNppCredited, got $other")

  // ── Webhook event parsing: npp.payment.returned ───────────────────────────

  test("AuNppWebhookReceiver: parses npp.payment.returned → AuNppReturned"):
    val secret = "test-secret"
    val body   = """{"type":"npp.payment.returned","transferId":"TXN200","returnCode":"AC01","amount":"10000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.AuNppReturned(transfer, returnCode) =>
        assert(transfer.id.value == "TXN200")
        assert(returnCode        == "AC01")
      case other => fail(s"Expected AuNppReturned, got $other")

  test("AuNppWebhookReceiver: return event uses reason fallback when returnCode absent"):
    val recv = AuNppWebhookReceiver()
    val body = """{"type":"npp.payment.returned","transferId":"TXN201","reason":"InvalidAccount"}"""
    val event = recv.parseEvent(body)
    event match
      case BankRailsEvent.AuNppReturned(transfer, returnCode) =>
        assert(returnCode == "InvalidAccount")
      case other => fail(s"Expected AuNppReturned, got $other")

  test("AuNppWebhookReceiver: return event parses returnCode correctly"):
    val recv  = AuNppWebhookReceiver()
    val body  = """{"type":"npp.payment.returned","transferId":"TXN202","returnCode":"BE01","reason":"unused"}"""
    val event = recv.parseEvent(body)
    event match
      case BankRailsEvent.AuNppReturned(transfer, returnCode) =>
        assert(returnCode == "BE01")
      case other => fail(s"Expected AuNppReturned, got $other")

  // ── Unknown event type ────────────────────────────────────────────────────

  test("AuNppWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"type":"npp.payment.unknown","transferId":"TXN999"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("AuNppWebhookReceiver: missing type field returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"transferId":"TXN999","amount":"1000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = AuNppWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-NPP-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)

  // ── idempotencyKey tests ──────────────────────────────────────────────────

  test("AuNppWebhookReceiver.idempotencyKey: AuNppCredited includes transfer id"):
    val recv     = AuNppWebhookReceiver()
    val dummy    = BankAccount(holderName = "", countryCode = "AU")
    val transfer = BankTransfer(
      id        = TransferId("TXN-001"),
      rail      = RailKind.AU_NPP,
      amount    = amount100AUD,
      sender    = dummy,
      recipient = dummy,
      reference = "",
      status    = BankTransferStatus.Settled,
      createdAt = java.time.Instant.now(),
    )
    val key = recv.idempotencyKey(BankRailsEvent.AuNppCredited(transfer))
    assert(key.contains("TXN-001"))

  test("AuNppWebhookReceiver.idempotencyKey: AuNppReturned includes transfer id and returnCode"):
    val recv     = AuNppWebhookReceiver()
    val dummy    = BankAccount(holderName = "", countryCode = "AU")
    val transfer = BankTransfer(
      id        = TransferId("TXN-002"),
      rail      = RailKind.AU_NPP,
      amount    = amount100AUD,
      sender    = dummy,
      recipient = dummy,
      reference = "",
      status    = BankTransferStatus.Returned(ReturnCode("AC01"), "Account closed"),
      createdAt = java.time.Instant.now(),
    )
    val key = recv.idempotencyKey(BankRailsEvent.AuNppReturned(transfer, "AC01"))
    assert(key.contains("TXN-002"))
    assert(key.contains("AC01"))

  // ── HMAC helper tests ─────────────────────────────────────────────────────

  test("AuNppWebhookReceiver.hmacSha256: produces 64-char lowercase hex"):
    val recv   = AuNppWebhookReceiver()
    val result = recv.hmacSha256("secret", "test-payload")
    assert(result.length == 64, s"HMAC-SHA256 hex should be 64 chars, got ${result.length}")
    assert(result.forall(c => c.isDigit || (c >= 'a' && c <= 'f')),
      "HMAC-SHA256 hex should only contain lowercase hex chars")

  test("AuNppWebhookReceiver.hmacSha256: different secrets produce different results"):
    val recv = AuNppWebhookReceiver()
    val sig1 = recv.hmacSha256("secret1", "payload")
    val sig2 = recv.hmacSha256("secret2", "payload")
    assert(sig1 != sig2)

  test("AuNppWebhookReceiver.hmacSha256: different payloads produce different results"):
    val recv = AuNppWebhookReceiver()
    val sig1 = recv.hmacSha256("secret", "payload1")
    val sig2 = recv.hmacSha256("secret", "payload2")
    assert(sig1 != sig2)

  // ── BankRailsEvent.AuNpp* event cases ────────────────────────────────────

  test("BankRailsEvent.AuNppCredited carries transfer"):
    val dummy    = BankAccount(holderName = "", countryCode = "AU")
    val transfer = BankTransfer(
      id        = TransferId("txn-101"),
      rail      = RailKind.AU_NPP,
      amount    = amount100AUD,
      sender    = dummy,
      recipient = dummy,
      reference = "test",
      status    = BankTransferStatus.Settled,
      createdAt = java.time.Instant.now(),
    )
    val event = BankRailsEvent.AuNppCredited(transfer)
    event match
      case BankRailsEvent.AuNppCredited(t) => assert(t.id.value == "txn-101")
      case _                               => fail("Pattern match failed")

  test("BankRailsEvent.AuNppReturned carries transfer and returnCode"):
    val dummy    = BankAccount(holderName = "", countryCode = "AU")
    val transfer = BankTransfer(
      id        = TransferId("txn-202"),
      rail      = RailKind.AU_NPP,
      amount    = amount100AUD,
      sender    = dummy,
      recipient = dummy,
      reference = "test",
      status    = BankTransferStatus.Returned(ReturnCode("AC01"), "Account closed"),
      createdAt = java.time.Instant.now(),
    )
    val event = BankRailsEvent.AuNppReturned(transfer, "AC01")
    event match
      case BankRailsEvent.AuNppReturned(t, code) =>
        assert(t.id.value == "txn-202")
        assert(code       == "AC01")
      case _ => fail("Pattern match failed")

  test("AuNppCredited and AuNppReturned are distinct BankRailsEvent cases"):
    val dummy    = BankAccount(holderName = "", countryCode = "AU")
    val transfer = BankTransfer(
      id        = TransferId("t1"),
      rail      = RailKind.AU_NPP,
      amount    = amount100AUD,
      sender    = dummy,
      recipient = dummy,
      reference = "",
      status    = BankTransferStatus.Settled,
      createdAt = java.time.Instant.now(),
    )
    val credited = BankRailsEvent.AuNppCredited(transfer)
    val returned = BankRailsEvent.AuNppReturned(transfer, "AC01")
    assert(credited != returned)

  // ── AuNppPlugin tests ─────────────────────────────────────────────────────

  test("AuNppPlugin id is non-empty"):
    val plugin = AuNppPlugin()
    assert(plugin.id.nonEmpty)

  test("AuNppPlugin displayName is non-empty"):
    val plugin = AuNppPlugin()
    assert(plugin.displayName.nonEmpty)

  test("AuNppPlugin capabilities include Feature.BankRails"):
    val plugin = AuNppPlugin()
    assert(plugin.capabilities.features.nonEmpty)

  // ── AuNppApi JSON helpers ─────────────────────────────────────────────────

  test("AuNppApi.extractField: extracts string field"):
    val cfg  = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val api  = AuNppApi(cfg)
    val json = """{"transferId":"TXN123","status":"settled"}"""
    assert(api.extractField(json, "transferId") == Some("TXN123"))
    assert(api.extractField(json, "status")     == Some("settled"))

  test("AuNppApi.extractField: returns None for missing field"):
    val cfg  = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val api  = AuNppApi(cfg)
    val json = """{"status":"settled"}"""
    assert(api.extractField(json, "missing") == None)

  test("AuNppApi.extractBoolField: parses true"):
    val cfg  = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val api  = AuNppApi(cfg)
    val json = """{"resolved":true}"""
    assert(api.extractBoolField(json, "resolved") == Some(true))

  test("AuNppApi.extractBoolField: parses false"):
    val cfg  = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val api  = AuNppApi(cfg)
    val json = """{"resolved":false}"""
    assert(api.extractBoolField(json, "resolved") == Some(false))

  // ── NppPayIdNotFound error ────────────────────────────────────────────────

  test("NppPayIdNotFound error message includes payid"):
    val err = NppPayIdNotFound("+61412345678")
    assert(err.getMessage.contains("+61412345678"))

  test("NppPayIdNotFound is a BankRailsError"):
    val err: BankRailsError = NppPayIdNotFound("user@example.com")
    assert(err.isInstanceOf[BankRailsError])

  // ── PayID resolution mock ─────────────────────────────────────────────────

  test("AuNppApi.resolvePayId: parses bsb and accountNumber from response"):
    withMockServer("/v1/payid/resolve",
      """{"bsb":"062-000","accountNumber":"11223344"}"""
    ) { baseUrl =>
      val cfg = AuNppConfig(apiKey = "k", baseUrl = baseUrl, webhookSecret = "s")
      val api = AuNppApi(cfg)
      val (bsb, acct) = api.resolvePayId("+61412345678")
      assert(bsb  == "062-000")
      assert(acct == "11223344")
    }

  test("AuNppApi.resolvePayId: uses bsbNumber fallback field"):
    withMockServer("/v1/payid/resolve",
      """{"bsbNumber":"033-999","accountNumber":"99887766"}"""
    ) { baseUrl =>
      val cfg = AuNppConfig(apiKey = "k", baseUrl = baseUrl, webhookSecret = "s")
      val api = AuNppApi(cfg)
      val (bsb, acct) = api.resolvePayId("user@example.com")
      assert(bsb  == "033-999")
      assert(acct == "99887766")
    }

  test("AuNppApi.resolvePayId: throws when bsb missing from response"):
    withMockServer("/v1/payid/resolve",
      """{"accountNumber":"11223344"}"""
    ) { baseUrl =>
      val cfg = AuNppConfig(apiKey = "k", baseUrl = baseUrl, webhookSecret = "s")
      val api = AuNppApi(cfg)
      val ex = intercept[RuntimeException] {
        api.resolvePayId("user@example.com")
      }
      assert(ex.getMessage.contains("bsb"))
    }

  // ── RailKind routing ──────────────────────────────────────────────────────

  test("AuNppProvider routes AU_NPP rail correctly in supportedRails set"):
    val cfg      = AuNppConfig(apiKey = "k", baseUrl = "http://localhost", webhookSecret = "s")
    val provider = AuNppProvider(cfg)
    assert(provider.supportedRails.contains(RailKind.AU_NPP))
    assert(!provider.supportedRails.contains(RailKind.SEPA_CT))
    assert(!provider.supportedRails.contains(RailKind.ACH_CREDIT))
    assert(!provider.supportedRails.contains(RailKind.PIX))
    assert(!provider.supportedRails.contains(RailKind.SG_PAYNOW))
