package scalascript.payments.fednow

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FedNowProviderTest extends AnyFunSuite:

  // ── Test helpers ───────────────────────────────────────────────────────────

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val senderAcct = BankAccount(
    accountNumber = Some("123456789"),
    routingNumber = Some("021000021"),
    holderName    = "Alice Corp",
    countryCode   = "US",
  )
  private val recipientAcct = BankAccount(
    accountNumber = Some("987654321"),
    bankCode      = Some("026009593"),
    holderName    = "Bob LLC",
    countryCode   = "US",
  )
  private val amount1050USD = Money(1050L, Currency("USD"))  // $10.50

  private val testConfig = FedNowConfig(
    fednowApiUrl        = "https://fednow-connect.example.com/v1",
    fednowCertPath      = "/etc/ssl/fednow-client.crt",
    fednowKeyPath       = "/etc/ssl/fednow-client.key",
    fednowRoutingNumber = "021000021",
    fednowParticipantId = "TESTFI01",
  )

  // ── pacs.008 XML structure tests ──────────────────────────────────────────

  test("Iso20022Xml.buildPacs008: document has pacs.008.001.08 namespace"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "ORDER-001",
      idempotencyKey = "idem-key-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"),
      "pacs.008 namespace must be present")

  test("Iso20022Xml.buildPacs008: MsgId is present and non-empty"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "msg-id-test-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<MsgId>msg-id-test-001</MsgId>"), "MsgId should be set from idempotency key")

  test("Iso20022Xml.buildPacs008: NbOfTxs equals 1"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "idem-nbtx-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<NbOfTxs>1</NbOfTxs>"), "NbOfTxs must be 1 for single-transaction messages")

  test("Iso20022Xml.buildPacs008: IntrBkSttlmAmt has Ccy=USD"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "idem-ccy-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("""Ccy="USD""""), "IntrBkSttlmAmt must have Ccy=\"USD\"")
    assert(xml.contains("<IntrBkSttlmAmt"), "IntrBkSttlmAmt element must be present")

  test("Iso20022Xml.buildPacs008: Money(1050, USD) formats as 10.50 in IntrBkSttlmAmt"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "idem-amt-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("""Ccy="USD">10.50</IntrBkSttlmAmt"""), "1050 USD minor units should render as 10.50")

  test("Iso20022Xml.buildPacs008: EndToEndId matches idempotency key"):
    val idemKey = "e2e-fednow-test-xyz"
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = idemKey,
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains(s"<EndToEndId>$idemKey</EndToEndId>"), "EndToEndId must match idempotency key")

  test("Iso20022Xml.buildPacs008: DbtrAgt MmbId matches config routing number"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct.copy(bankCode = None, routingNumber = None),  // no per-request routing
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "idem-routing-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<MmbId>021000021</MmbId>"), "DbtrAgt MmbId must match config routing number")

  test("Iso20022Xml.buildPacs008: SttlmMtd is CLRG"):
    val req = InitiateTransferRequest(
      rail           = RailKind.FEDNOW,
      amount         = amount1050USD,
      sender         = senderAcct,
      recipient      = recipientAcct,
      reference      = "REF",
      idempotencyKey = "idem-sttlm-001",
    )
    val xml = Iso20022Xml.buildPacs008(req, "021000021")
    assert(xml.contains("<SttlmMtd>CLRG</SttlmMtd>"), "SttlmMtd must be CLRG (clearing) for FedNow")

  // ── pacs.002 status parsing tests ─────────────────────────────────────────

  test("Iso20022Xml.parsePacs002Status: ACCP maps to Pending"):
    val pacs002 = """<Document><FIToFIPmtStsRpt><TxInfAndSts><TxSts>ACCP</TxSts></TxInfAndSts></FIToFIPmtStsRpt></Document>"""
    val status = Iso20022Xml.parsePacs002Status(pacs002)
    assert(status == BankTransferStatus.Pending, "ACCP (accepted, pending settlement) should map to Pending")

  test("Iso20022Xml.parsePacs002Status: ACSC maps to Settled"):
    val pacs002 = """<Document><FIToFIPmtStsRpt><TxInfAndSts><TxSts>ACSC</TxSts></TxInfAndSts></FIToFIPmtStsRpt></Document>"""
    val status = Iso20022Xml.parsePacs002Status(pacs002)
    assert(status == BankTransferStatus.Settled, "ACSC (accepted settlement completed) should map to Settled")

  test("Iso20022Xml.parsePacs002Status: RJCT maps to Rejected"):
    val pacs002 = """<Document><FIToFIPmtStsRpt><TxInfAndSts><TxSts>RJCT</TxSts><StsRsnInf><Rsn>AC01</Rsn><AddtlInf>Invalid account</AddtlInf></StsRsnInf></TxInfAndSts></FIToFIPmtStsRpt></Document>"""
    val status = Iso20022Xml.parsePacs002Status(pacs002)
    status match
      case BankTransferStatus.Rejected(code, _) =>
        assert(code.value == "AC01", "Reject code should be AC01")
      case _ => fail(s"Expected Rejected but got $status")

  test("Iso20022Xml.parsePacs002Status: PDNG maps to Pending"):
    val pacs002 = """<TxSts>PDNG</TxSts>"""
    val status = Iso20022Xml.parsePacs002Status(pacs002)
    assert(status == BankTransferStatus.Pending, "PDNG should map to Pending")

  // ── FedNowProvider: unsupported operations ─────────────────────────────────

  test("FedNowProvider.cancelTransfer fails with descriptive message"):
    val provider = FedNowProvider(testConfig)
    val ex = intercept[BankRailsCancelError]:
      provider.cancelTransfer(TransferId("txn-001"))
    assert(ex.getMessage.contains("cannot be cancelled"), s"Expected 'cannot be cancelled' in: ${ex.getMessage}")

  test("FedNowProvider.initiateDirectDebit fails with descriptive message"):
    val provider = FedNowProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.FEDNOW,
      amount          = amount1050USD,
      mandateId       = MandateId("MND-001"),
      creditorAccount = recipientAcct,
      debtorAccount   = senderAcct,
      creditorName    = "Bob LLC",
      reference       = "DD-REF",
      idempotencyKey  = "idem-dd-001",
    )
    val ex = intercept[UnsupportedRail]:
      provider.initiateDirectDebit(req)
    assert(ex.getMessage.contains("direct debit"), s"Expected 'direct debit' in: ${ex.getMessage}")

  // ── Webhook HMAC verify tests ─────────────────────────────────────────────

  test("FedNowWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "fednow-webhook-secret-abc"
    val body   = """{"event":"fednow.credit.received","instr_id":"fn-001","amount":"10.50","end_to_end_id":"e2e-001"}"""
    val sig    = makeSignature(secret, body)
    val recv   = FedNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FedNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("FedNowWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val body   = """{"event":"fednow.credit.received","instr_id":"fn-002","amount":"5.00"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = FedNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FedNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("FedNowWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = FedNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("FedNowWebhookReceiver: fednow.credit.received event parses correctly"):
    val secret = "test-secret"
    val body   = """{"event":"fednow.credit.received","instr_id":"fn-recv-001","amount":"100.00","end_to_end_id":"e2e-recv-001"}"""
    val sig    = makeSignature(secret, body)
    val recv   = FedNowWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-FedNow-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right(FedNowCreditReceived) but got $result")
    result.toOption.get match
      case BankRailsEvent.FedNowCreditReceived(transfer) =>
        assert(transfer.id.value == "fn-recv-001", s"instrId should be fn-recv-001, got ${transfer.id.value}")
        assert(transfer.amount == Money(10000L, Currency("USD")), s"amount should be USD 100.00, got ${transfer.amount}")
      case other => fail(s"Expected FedNowCreditReceived, got $other")

  // ── FedNowConfig from env vars tests ──────────────────────────────────────

  test("FedNowConfig.fromEnv uses fallback defaults when env vars absent"):
    val cfg = FedNowConfig.fromEnv
    assert(cfg.fednowApiUrl.nonEmpty, "Default fednowApiUrl should be non-empty")

  test("FedNowConfig fields accessible"):
    val cfg = FedNowConfig(
      fednowApiUrl        = "https://fednow-connect.frbservices.org/v1",
      fednowCertPath      = "/certs/client.crt",
      fednowKeyPath       = "/certs/client.key",
      fednowRoutingNumber = "021000021",
      fednowParticipantId = "TESTFI01",
    )
    assert(cfg.fednowApiUrl == "https://fednow-connect.frbservices.org/v1")
    assert(cfg.fednowRoutingNumber == "021000021")
    assert(cfg.fednowParticipantId == "TESTFI01")

  // ── formatAmount helper tests ─────────────────────────────────────────────

  test("Iso20022Xml.formatAmount: USD 10.50"):
    val money = Money(1050L, Currency("USD"))
    assert(Iso20022Xml.formatAmount(money) == "10.50")

  test("Iso20022Xml.formatAmount: USD 500000.00 (FedNow limit)"):
    val money = Money(50_000_000L, Currency("USD"))
    assert(Iso20022Xml.formatAmount(money) == "500000.00")

  test("Iso20022Xml.formatAmount: USD 0.01"):
    val money = Money(1L, Currency("USD"))
    assert(Iso20022Xml.formatAmount(money) == "0.01")
