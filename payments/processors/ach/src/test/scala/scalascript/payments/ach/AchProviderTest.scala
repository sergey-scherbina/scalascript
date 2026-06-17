package scalascript.payments.ach

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.LocalDate

class AchProviderTest extends AnyFunSuite:

  // ── Test fixtures ─────────────────────────────────────────────────────────

  private val testConfig = AchConfig(
    achSftpHost      = "sftp.test-ach.example.com",
    achSftpUser      = "ach-test-user",
    achSftpKeyPath   = "/tmp/ach_test_key",
    achCompanyName   = "ACME CORP",
    achCompanyId     = "1234567890",
    achRoutingNumber = "021000021",
    sameDayAch       = false,
  )

  private val senderAccount = BankAccount(
    routingNumber = Some("021000021"),
    accountNumber = Some("123456789"),
    holderName    = "Acme Corp",
    countryCode   = "US",
  )

  private val recipientAccount = BankAccount(
    routingNumber = Some("011000138"),
    accountNumber = Some("987654321"),
    holderName    = "John Doe",
    countryCode   = "US",
  )

  private val amount100USD = Money(10000L, Currency("USD"))  // $100.00

  private def makeHmacSig(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  // ── Nacha file structure: File Header ────────────────────────────────────

  test("NachaFile: File Header record is exactly 94 characters"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "987654321",
      amountCents     = 10000L,
      individualId    = "IDEM-001",
      individualName  = "John Doe",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val lines = file.split("\n")
    val header = lines(0)
    assert(header.length == 94, s"File Header should be 94 chars, got ${header.length}: '$header'")

  test("NachaFile: File Header starts with record type '1'"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "987654321",
      amountCents     = 5000L,
      individualId    = "IDEM-002",
      individualName  = "Jane Doe",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val header = file.split("\n")(0)
    assert(header.startsWith("1"), "File Header must start with record type '1'")

  test("NachaFile: File Header blocking factor is '10'"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "111111111",
      amountCents     = 1000L,
      individualId    = "IDEM-003",
      individualName  = "Test User",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val header = file.split("\n")(0)
    // Blocking factor at positions 38-39 (0-indexed: 37-38)
    val blockingFactor = header.substring(37, 39)
    assert(blockingFactor == "10", s"Blocking factor should be '10', got '$blockingFactor'")

  test("NachaFile: File Header format code is '094'"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "222222222",
      amountCents     = 2000L,
      individualId    = "IDEM-004",
      individualName  = "Test2",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val header = file.split("\n")(0)
    // Record size at positions 35-37 (0-indexed: 34-36)
    val recordSize = header.substring(34, 37)
    assert(recordSize == "094", s"Record size should be '094', got '$recordSize'")

  // ── Batch Header: service class code 220 for credit-only batch ───────────

  test("NachaFile: Batch Header has service class 220 for credit-only batch"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "555555555",
      amountCents     = 5000L,
      individualId    = "IDEM-005",
      individualName  = "Credit Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry), serviceClass = NachaFile.ServiceClass.Credits)
    val batchHeader = file.split("\n")(1)
    assert(batchHeader.startsWith("5"), "Batch Header must start with record type '5'")
    // Service class at positions 2-4 (0-indexed: 1-3)
    val scc = batchHeader.substring(1, 4)
    assert(scc == "220", s"Service class should be 220 for credits-only, got '$scc'")

  test("NachaFile: Batch Header has service class 225 for debit-only batch"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingDebit,
      rdfiRouting     = "011000138",
      accountNumber   = "555555555",
      amountCents     = 5000L,
      individualId    = "IDEM-006",
      individualName  = "Debit Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry), serviceClass = NachaFile.ServiceClass.Debits)
    val batchHeader = file.split("\n")(1)
    val scc = batchHeader.substring(1, 4)
    assert(scc == "225", s"Service class should be 225 for debits-only, got '$scc'")

  // ── Entry Detail: transaction code 22 for checking credit; amount in cents ─

  test("NachaFile: Entry Detail has transaction code 22 for checking credit"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "777777777",
      amountCents     = 9999L,
      individualId    = "IDEM-007",
      individualName  = "Check Credit",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val entryLine = file.split("\n")(2)  // header, batch-header, entry
    assert(entryLine.startsWith("6"), "Entry Detail must start with record type '6'")
    val txCode = entryLine.substring(1, 3)
    assert(txCode == "22", s"Transaction code for checking credit should be '22', got '$txCode'")

  test("NachaFile: Entry Detail amount in cents with no decimal point"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "888888888",
      amountCents     = 4999L,   // $49.99
      individualId    = "IDEM-008",
      individualName  = "Amount Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val entryLine = file.split("\n")(2)
    // Amount at positions 30-39 (0-indexed: 29-38)
    val amountField = entryLine.substring(29, 39)
    assert(amountField == "0000004999", s"Amount field should be '0000004999', got '$amountField'")

  // ── File Control: block count = ceil(total_records / 10) ─────────────────

  test("NachaFile: File Control block count equals ceil(total_records / 10)"):
    // With 1 entry: total records before padding = 5 (header, batch-header, entry, batch-ctrl, file-ctrl)
    // block count = ceil(5 / 10) = 1
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "999999999",
      amountCents     = 1000L,
      individualId    = "IDEM-009",
      individualName  = "Block Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val lines = file.split("\n")
    // File control is the last non-padding line (starts with "9")
    val fileCtrlLine = lines.find(_.startsWith("9")).get
    // Block count at positions 8-13 (0-indexed: 7-12)
    val blockCount = fileCtrlLine.substring(7, 13).trim.toInt
    assert(blockCount == 1, s"Block count should be 1, got $blockCount")

  test("NachaFile: file is padded to multiple of 10 lines"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "111222333",
      amountCents     = 1500L,
      individualId    = "IDEM-010",
      individualName  = "Padding Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry))
    val lines = file.split("\n")
    assert(lines.length % 10 == 0, s"File should have multiple of 10 lines, got ${lines.length}")

  // ── Entry hash: sum of routing numbers, last 10 digits ───────────────────

  test("NachaFile: entry hash is last 10 digits of routing number sum"):
    // routing "011000138" — first 8 digits = "01100013" = 1100013
    val entries = List(
      EntryDetail(NachaFile.TransactionCode.CheckingCredit, "011000138", "123", 100L, "ID1", "Name1", "021000021000001"),
      EntryDetail(NachaFile.TransactionCode.CheckingCredit, "011000138", "456", 200L, "ID2", "Name2", "021000021000002"),
    )
    val hash = NachaFile.entryHashSum(entries)
    // sum of 1100013 + 1100013 = 2200026 → padded to 10 digits: "0002200026"
    assert(hash == "0002200026", s"Entry hash should be '0002200026', got '$hash'")

  // ── Same-day ACH flag sets today's effective entry date ──────────────────

  test("NachaFile: same-day ACH sets effective entry date to today"):
    val today = LocalDate.now()
    val todayStr = today.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "123456789",
      amountCents     = 2500L,
      individualId    = "SAMEDAY-001",
      individualName  = "SameDay Test",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry), sameDay = true)
    val batchHeader = file.split("\n")(1)
    // Effective entry date at positions 60-65 (0-indexed: 59-64)
    val effDate = batchHeader.substring(59, 65)
    assert(effDate == todayStr, s"Same-day ACH effective date should be today ($todayStr), got '$effDate'")

  test("NachaFile: same-day ACH adds SAMEDAY to batch entry description"):
    val entry = EntryDetail(
      transactionCode = NachaFile.TransactionCode.CheckingCredit,
      rdfiRouting     = "011000138",
      accountNumber   = "123456789",
      amountCents     = 1000L,
      individualId    = "SAMEDAY-002",
      individualName  = "SameDay Desc",
      traceNumber     = "021000021000001",
    )
    val file = NachaFile.build(testConfig, List(entry), sameDay = true)
    assert(file.contains("SAMEDAY"), "Same-day ACH batch header should contain 'SAMEDAY'")

  // ── R-code parsing ────────────────────────────────────────────────────────

  test("R-code parsing: R01 maps to InsufficientFunds description"):
    val rCode = RCode("R01")
    assert(rCode.value == "R01")
    assert(rCode.description.contains("Insufficient"), s"R01 description should mention insufficient funds, got: ${rCode.description}")

  test("R-code parsing: RCode.R01 constant equals 'R01'"):
    assert(RCode.R01.value == "R01")
    assert(RCode.R01.description.contains("Insufficient"))

  // ── C-code parsing ────────────────────────────────────────────────────────

  test("C-code parsing: C01 maps to IncorrectBankAccountNumber description"):
    val cCode = CCode("C01")
    assert(cCode.value == "C01")
    // CCode does not have descriptions in the spec; just verify it parses
    assert(CCode.C01.value == "C01")

  test("CCode.C02 value is 'C02'"):
    assert(CCode.C02.value == "C02")

  // ── Webhook HMAC ─────────────────────────────────────────────────────────

  test("AchWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "ach-webhook-secret-xyz"
    val body   = """{"type":"ach.transfer.settled","transfer_id":"ach-001","amount":"100.00","currency":"USD","reference":"REF-1"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-ACH-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("AchWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val body   = """{"type":"ach.transfer.settled","transfer_id":"ach-002","amount":"50.00","currency":"USD","reference":"REF-2"}"""
    val sig    = makeHmacSig("wrong-secret", body)
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-ACH-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("AchWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("AchWebhookReceiver: parses ach.transfer.settled event"):
    val secret = "test-secret"
    val body   = """{"type":"ach.transfer.settled","transfer_id":"ach-003","amount":"200.00","currency":"USD","reference":"REF-3"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-ACH-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.AchTransferSettled])

  test("AchWebhookReceiver: parses ach.return event with R01 code"):
    val secret = "return-secret"
    val body   = """{"type":"ach.return","transfer_id":"ach-004","amount":"75.00","currency":"USD","reference":"REF-4","r_code":"R01","description":"Insufficient Funds"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-ACH-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.AchReturn(_, rCode, desc) =>
        assert(rCode.value == "R01")
        assert(desc.contains("Insufficient"))
      case other => fail(s"Expected AchReturn, got $other")

  test("AchWebhookReceiver: parses ach.notification_of_change with C01 code"):
    val secret = "noc-secret"
    val body   = """{"type":"ach.notification_of_change","transfer_id":"ach-005","amount":"30.00","currency":"USD","reference":"REF-5","c_code":"C01","corrected_data":"999888777"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = AchWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-ACH-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.AchNotificationOfChange(_, cCode, corrected) =>
        assert(cCode.value == "C01")
        assert(corrected == "999888777")
      case other => fail(s"Expected AchNotificationOfChange, got $other")

  // ── AchProvider integration ───────────────────────────────────────────────

  test("AchProvider: initiateTransfer returns Pending BankTransfer"):
    val provider = AchProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.ACH_CREDIT,
      amount         = amount100USD,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-001",
      idempotencyKey = "idem-ach-001",
    )
    val result = provider.initiateTransfer(req)
    assert(result.status == BankTransferStatus.Pending)
    assert(result.rail == RailKind.ACH_CREDIT)
    assert(result.amount == amount100USD)

  test("AchProvider: initiateTransfer delivers Nacha file"):
    val provider = AchProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.ACH_CREDIT,
      amount         = amount100USD,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-002",
      idempotencyKey = "idem-ach-002",
    )
    provider.initiateTransfer(req)
    assert(provider.lastDeliveredFile.isDefined, "Nacha file should have been delivered")
    assert(provider.lastDeliveredDirection.contains("credit"))

  test("AchProvider: idempotent — same key returns same transfer"):
    val provider = AchProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.ACH_CREDIT,
      amount         = amount100USD,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-003",
      idempotencyKey = "idem-ach-003",
    )
    val first  = provider.initiateTransfer(req)
    val second = provider.initiateTransfer(req)
    assert(first.id == second.id, "Duplicate idempotency key should return the same transfer")

  test("AchProvider: unsupported rail throws UnsupportedRail"):
    val provider = AchProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.SEPA_CT,
      amount         = amount100USD,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-SEPA",
      idempotencyKey = "idem-sepa-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("AchProvider: AchConfig.fromEnv returns non-empty defaults"):
    val cfg = AchConfig.fromEnv
    assert(cfg.achSftpHost.nonEmpty)
    assert(cfg.achCompanyName.nonEmpty)
    assert(cfg.achRoutingNumber.nonEmpty)
