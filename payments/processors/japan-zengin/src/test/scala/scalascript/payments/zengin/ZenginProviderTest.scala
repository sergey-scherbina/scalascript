package scalascript.payments.zengin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.{ZoneId, ZonedDateTime, LocalDate}

class ZenginProviderTest extends AnyFunSuite:

  // ── Test fixtures ─────────────────────────────────────────────────────────

  private val testConfig = ZenginConfig(
    clientId            = "test-client",
    apiKey              = "test-api-key-xyz",
    baseUrl             = "https://api.zengin-test.example.com",
    senderBankCode      = "0001",
    senderBranchCode    = "001",
    senderAccountNumber = "1234567",
    senderName          = "ｱｸﾒ ｶ-ｼｬ",
    senderBankName      = "ﾐｽﾞﾎBK",
    senderBranchName    = "ｼﾝｼﾞｭｸ",
  )

  private val senderAccount = BankAccount(
    zenginBankCode   = Some("0001"),
    zenginBranchCode = Some("001"),
    accountNumber    = Some("1234567"),
    holderName       = "ｶ)ｱｸﾒ",
    countryCode      = "JP",
  )

  private val recipientAccount = BankAccount(
    zenginBankCode   = Some("0009"),
    zenginBranchCode = Some("123"),
    accountNumber    = Some("7654321"),
    holderName       = "ﾔﾏﾀﾞﾀﾛｳ",
    countryCode      = "JP",
  )

  private val amount10000JPY = Money(10000L, Currency("JPY"))

  private val JstZone = ZoneId.of("Asia/Tokyo")

  private def makeHmacSig(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  // Helper: a weekday at 10:00 JST (inside window)
  private val weekdayInWindow: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 25, 10, 0, 0, 0, JstZone)  // Monday

  // Helper: a weekday after 15:30 JST (outside window)
  private val weekdayAfterWindow: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 25, 16, 0, 0, 0, JstZone)  // Monday 16:00

  // Helper: Saturday
  private val weekend: ZonedDateTime =
    ZonedDateTime.of(2026, 5, 23, 10, 0, 0, 0, JstZone)  // Saturday

  // Provider with frozen clock inside the settlement window
  private def providerInWindow: ZenginProvider =
    ZenginProvider(testConfig, () => weekdayInWindow)

  // ── ZenginFile: record lengths exactly 120 chars ──────────────────────────

  test("ZenginFile: header record is exactly 120 characters"):
    val header = ZenginFile.headerRecord(testConfig, LocalDate.of(2026, 5, 25), 1)
    assert(header.length == 120, s"Header record should be 120 chars, got ${header.length}")

  test("ZenginFile: data record is exactly 120 characters"):
    val record = ZenginDataRecord(
      bankCode      = "0009",
      bankName      = "ﾔﾏﾀﾞBK",
      branchCode    = "123",
      branchName    = "ｼﾝｼﾞｭｸ",
      accountType   = ZenginFile.AccountType.Ordinary,
      accountNumber = "7654321",
      accountName   = "ﾔﾏﾀﾞﾀﾛｳ",
      amountYen     = 10000L,
      customerId    = "IDEM-001",
    )
    val dataRec = ZenginFile.dataRecord(record)
    assert(dataRec.length == 120, s"Data record should be 120 chars, got ${dataRec.length}")

  test("ZenginFile: trailer record is exactly 120 characters"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val trailer = ZenginFile.trailerRecord(List(record))
    assert(trailer.length == 120, s"Trailer record should be 120 chars, got ${trailer.length}")

  test("ZenginFile: end record is exactly 120 characters"):
    val end = ZenginFile.endRecord()
    assert(end.length == 120, s"End record should be 120 chars, got ${end.length}")

  // ── ZenginFile: type-1 header record format ───────────────────────────────

  test("ZenginFile: header record starts with type code '1'"):
    val header = ZenginFile.headerRecord(testConfig, LocalDate.of(2026, 5, 25), 1)
    assert(header.charAt(0) == '1', s"Header record must start with '1', got '${header.charAt(0)}'")

  test("ZenginFile: header record positions [1-2] contain file type '21'"):
    val header = ZenginFile.headerRecord(testConfig, LocalDate.of(2026, 5, 25), 1)
    val fileType = header.substring(1, 3)
    assert(fileType == "21", s"File type field should be '21', got '$fileType'")

  test("ZenginFile: header record contains sender bank code in positions [4-7]"):
    val header = ZenginFile.headerRecord(testConfig, LocalDate.of(2026, 5, 25), 1)
    val bankCode = header.substring(4, 8)
    assert(bankCode == "0001", s"Sender bank code should be '0001', got '$bankCode'")

  test("ZenginFile: header record contains sender branch code in positions [23-25]"):
    val header = ZenginFile.headerRecord(testConfig, LocalDate.of(2026, 5, 25), 1)
    val branchCode = header.substring(23, 26)
    assert(branchCode == "001", s"Sender branch code should be '001', got '$branchCode'")

  test("ZenginFile: header record total data records count is correct"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val file = ZenginFile.build(testConfig, List(record))
    val header = file.split("\n")(0)
    // total data records at positions [95-100] (0-indexed: 95-100)
    val count = header.substring(95, 101).trim.toInt
    assert(count == 1, s"Header total records should be 1, got $count")

  test("ZenginFile: header with 3 data records shows count 3"):
    val records = List.fill(3)(ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 1000L, "X"))
    val file = ZenginFile.build(testConfig, records)
    val header = file.split("\n")(0)
    val count = header.substring(95, 101).trim.toInt
    assert(count == 3, s"Header total records should be 3, got $count")

  // ── ZenginFile: type-2 data record format ─────────────────────────────────

  test("ZenginFile: data record starts with type code '2'"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    assert(dataRec.charAt(0) == '2', s"Data record must start with '2', got '${dataRec.charAt(0)}'")

  test("ZenginFile: data record contains receiving bank code in positions [1-4]"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    val bankCode = dataRec.substring(1, 5)
    assert(bankCode == "0009", s"Receiving bank code should be '0009', got '$bankCode'")

  test("ZenginFile: data record contains receiving branch code in positions [20-22]"):
    val record = ZenginDataRecord("0009", "BK", "456", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    val branchCode = dataRec.substring(20, 23)
    assert(branchCode == "456", s"Receiving branch code should be '456', got '$branchCode'")

  test("ZenginFile: data record contains account type at position [40]"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", ZenginFile.AccountType.Current, "7654321", "ﾔﾏﾀﾞ", 5000L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    val accountType = dataRec.charAt(40).toString
    assert(accountType == "2", s"Account type for current should be '2', got '$accountType'")

  test("ZenginFile: data record amount field is integer yen with no decimal"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 123456L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    // Amount at positions [78-87] (0-indexed)
    val amountField = dataRec.substring(78, 88)
    assert(amountField == "0000123456", s"Amount field should be '0000123456', got '$amountField'")

  test("ZenginFile: data record kana account name at positions [48-77]"):
    val record = ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞﾀﾛｳ", 5000L, "X")
    val dataRec = ZenginFile.dataRecord(record)
    val accountName = dataRec.substring(48, 78).stripTrailing()
    assert(accountName == "ﾔﾏﾀﾞﾀﾛｳ", s"Account name field should be 'ﾔﾏﾀﾞﾀﾛｳ', got '$accountName'")

  // ── ZenginFile: type-8 trailer record ────────────────────────────────────

  test("ZenginFile: trailer record starts with type code '8'"):
    val records = List(ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X"))
    val trailer = ZenginFile.trailerRecord(records)
    assert(trailer.charAt(0) == '8', s"Trailer record must start with '8', got '${trailer.charAt(0)}'")

  test("ZenginFile: trailer record total amount is sum of all data records"):
    val records = List(
      ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 10000L, "X1"),
      ZenginDataRecord("0009", "BK", "456", "BR", "1", "1111111", "ﾀﾅｶ", 25000L, "X2"),
    )
    val trailer = ZenginFile.trailerRecord(records)
    // Total amount at positions [7-18] (0-indexed)
    val totalAmount = trailer.substring(7, 19).trim.toLong
    assert(totalAmount == 35000L, s"Trailer total amount should be 35000, got $totalAmount")

  test("ZenginFile: trailer record total count is number of data records"):
    val records = List.fill(5)(ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 1000L, "X"))
    val trailer = ZenginFile.trailerRecord(records)
    // Total count at positions [1-6] (0-indexed)
    val totalCount = trailer.substring(1, 7).trim.toInt
    assert(totalCount == 5, s"Trailer count should be 5, got $totalCount")

  // ── ZenginFile: type-9 end record ─────────────────────────────────────────

  test("ZenginFile: end record starts with type code '9'"):
    val end = ZenginFile.endRecord()
    assert(end.charAt(0) == '9', s"End record must start with '9', got '${end.charAt(0)}'")

  test("ZenginFile: end record remaining 119 chars are zeros"):
    val end = ZenginFile.endRecord()
    val rest = end.substring(1)
    assert(rest.forall(_ == '0'), s"End record positions [2-120] should all be zeros")

  // ── ZenginFile: complete file structure ───────────────────────────────────

  test("ZenginFile: build produces header + data + trailer + end records"):
    val records = List(
      ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X"),
    )
    val file = ZenginFile.build(testConfig, records)
    val lines = file.split("\n")
    assert(lines.length == 4, s"File should have 4 lines (header+data+trailer+end), got ${lines.length}")
    assert(lines(0).charAt(0) == '1', "Line 0 should be header (type 1)")
    assert(lines(1).charAt(0) == '2', "Line 1 should be data (type 2)")
    assert(lines(2).charAt(0) == '8', "Line 2 should be trailer (type 8)")
    assert(lines(3).charAt(0) == '9', "Line 3 should be end (type 9)")

  test("ZenginFile: all records in a built file are exactly 120 chars"):
    val records = List(
      ZenginDataRecord("0009", "BK", "123", "BR", "1", "7654321", "ﾔﾏﾀﾞ", 5000L, "X"),
      ZenginDataRecord("0001", "BK2", "456", "BR2", "2", "2222222", "ｻﾄｳ", 12000L, "Y"),
    )
    val file = ZenginFile.build(testConfig, records)
    val lines = file.split("\n")
    lines.zipWithIndex.foreach { (line, i) =>
      assert(line.length == 120, s"Line $i should be 120 chars, got ${line.length}: '$line'")
    }

  // ── KatakanaValidator ──────────────────────────────────────────────────────

  test("KatakanaValidator: valid half-width kana name passes"):
    val name = "ﾔﾏﾀﾞﾀﾛｳ"
    assert(KatakanaValidator.isValid(name), s"'$name' should be valid half-width kana")

  test("KatakanaValidator: validates correct kana name returns Right"):
    val name = "ｶ-ｱｸﾒ ｺｰﾎﾟﾚｰｼｮﾝ"   // ｶ-ｱｸﾒ (half-width kana, hyphen, space are all allowed)
    val result = KatakanaValidator.validate(name)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("KatakanaValidator: kanji in name returns Left with invalid chars"):
    val name = "山田太郎"
    val result = KatakanaValidator.validate(name)
    assert(result.isLeft, "Kanji characters should fail kana validation")
    val invalid = result.left.toOption.get
    assert(invalid.nonEmpty, "Should report invalid characters")

  test("KatakanaValidator: ASCII letters in name returns Left"):
    val name = "YAMADA TARO"
    val result = KatakanaValidator.validate(name)
    assert(result.isLeft, "ASCII letters should fail kana validation")

  test("KatakanaValidator: space (U+0020) is allowed"):
    val name = "ﾔﾏﾀﾞ ﾀﾛｳ"
    assert(KatakanaValidator.isValid(name), "Space should be allowed in kana names")

  test("KatakanaValidator: hyphen '-' is allowed"):
    val name = "ﾔﾏﾀﾞ-ﾀﾛｳ"
    assert(KatakanaValidator.isValid(name), "Hyphen should be allowed in kana names")

  test("KatakanaValidator: full-width katakana (U+30A0) fails validation"):
    val name = "ヤマダタロウ"  // full-width katakana
    val result = KatakanaValidator.validate(name)
    assert(result.isLeft, "Full-width katakana should fail half-width kana validation")

  test("KatakanaValidator: hiragana fails validation"):
    val name = "やまだたろう"
    val result = KatakanaValidator.validate(name)
    assert(result.isLeft, "Hiragana should fail kana validation")

  test("KatakanaValidator: boundary character ｦ (U+FF66) is valid"):
    assert(KatakanaValidator.isAllowed('ｦ'), "ｦ (U+FF66) should be allowed")

  test("KatakanaValidator: boundary character ﾟ (U+FF9F) is valid"):
    assert(KatakanaValidator.isAllowed('ﾟ'), "ﾟ (U+FF9F) should be allowed")

  test("KatakanaValidator: character just before ｦ (U+FF65) is not allowed"):
    assert(!KatakanaValidator.isAllowed('･'), "U+FF65 (halfwidth kana middle dot) should not be in the valid range")

  // ── ZenginProvider: initiateTransfer ─────────────────────────────────────

  test("ZenginProvider: initiateTransfer returns Pending BankTransfer"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-JPY-001",
      idempotencyKey = "idem-zengin-001",
    )
    val result = provider.initiateTransfer(req)
    assert(result.status == BankTransferStatus.Pending)
    assert(result.rail == RailKind.JP_ZENGIN)
    assert(result.amount == amount10000JPY)

  test("ZenginProvider: initiateTransfer delivers Zengin file"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-JPY-002",
      idempotencyKey = "idem-zengin-002",
    )
    provider.initiateTransfer(req)
    assert(provider.lastDeliveredFile.isDefined, "Zengin file should have been delivered")

  test("ZenginProvider: delivered file contains type-2 data record"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-JPY-003",
      idempotencyKey = "idem-zengin-003",
    )
    provider.initiateTransfer(req)
    val content = provider.lastDeliveredFile.get
    assert(content.split("\n").exists(_.startsWith("2")), "Zengin file should have a type-2 data record")

  test("ZenginProvider: idempotent — same key returns same transfer"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-JPY-004",
      idempotencyKey = "idem-zengin-004",
    )
    val first  = provider.initiateTransfer(req)
    val second = provider.initiateTransfer(req)
    assert(first.id == second.id, "Duplicate idempotency key should return the same transfer")

  test("ZenginProvider: unsupported rail throws UnsupportedRail"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.ACH_CREDIT,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-ACH",
      idempotencyKey = "idem-ach-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("ZenginProvider: missing zenginBankCode throws IllegalArgumentException"):
    val provider = providerInWindow
    val badRecipient = recipientAccount.copy(zenginBankCode = None)
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = badRecipient,
      reference      = "PAY-JPY-005",
      idempotencyKey = "idem-zengin-005",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  test("ZenginProvider: missing zenginBranchCode throws IllegalArgumentException"):
    val provider = providerInWindow
    val badRecipient = recipientAccount.copy(zenginBranchCode = None)
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = badRecipient,
      reference      = "PAY-JPY-006",
      idempotencyKey = "idem-zengin-006",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  test("ZenginProvider: non-kana account holder name throws IllegalArgumentException"):
    val provider = providerInWindow
    val badRecipient = recipientAccount.copy(holderName = "Yamada Taro")  // ASCII
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = amount10000JPY,
      sender         = senderAccount,
      recipient      = badRecipient,
      reference      = "PAY-JPY-007",
      idempotencyKey = "idem-zengin-007",
    )
    intercept[IllegalArgumentException] {
      provider.initiateTransfer(req)
    }

  // ── Settlement window guard ───────────────────────────────────────────────

  test("ZenginProvider: checkSettlementWindow passes on weekday at 10:00 JST"):
    val provider = ZenginProvider(testConfig)
    // Should not throw
    provider.checkSettlementWindow(weekdayInWindow)

  test("ZenginProvider: checkSettlementWindow throws ZenginOutsideWindow after 15:30 JST"):
    val provider = ZenginProvider(testConfig)
    intercept[ZenginOutsideWindow] {
      provider.checkSettlementWindow(weekdayAfterWindow)
    }

  test("ZenginProvider: checkSettlementWindow throws ZenginOutsideWindow on Saturday"):
    val provider = ZenginProvider(testConfig)
    intercept[ZenginOutsideWindow] {
      provider.checkSettlementWindow(weekend)
    }

  test("ZenginProvider: checkSettlementWindow throws ZenginOutsideWindow before 08:30 JST"):
    val provider = ZenginProvider(testConfig)
    val beforeWindow = ZonedDateTime.of(2026, 5, 25, 8, 0, 0, 0, JstZone)
    intercept[ZenginOutsideWindow] {
      provider.checkSettlementWindow(beforeWindow)
    }

  test("ZenginOutsideWindow includes next open time"):
    val provider = ZenginProvider(testConfig)
    val err = intercept[ZenginOutsideWindow] {
      provider.checkSettlementWindow(weekdayAfterWindow)
    }
    assert(err.nextOpen != null, "ZenginOutsideWindow should include nextOpen time")

  // ── JPY amount: minorUnits = yen integer ──────────────────────────────────

  test("ZenginProvider: JPY amount uses minorUnits directly (no decimal scaling)"):
    val provider = providerInWindow
    val req = InitiateTransferRequest(
      rail           = RailKind.JP_ZENGIN,
      amount         = Money(50000L, Currency("JPY")),
      sender         = senderAccount,
      recipient      = recipientAccount,
      reference      = "PAY-JPY-AMT",
      idempotencyKey = "idem-zengin-amt",
    )
    provider.initiateTransfer(req)
    val content = provider.lastDeliveredFile.get
    val dataLine = content.split("\n").find(_.startsWith("2")).get
    // Amount at positions [78-87] (0-indexed)
    val amountField = dataLine.substring(78, 88)
    assert(amountField == "0000050000", s"JPY amount 50000 should be '0000050000', got '$amountField'")

  // ── RailKind routing ──────────────────────────────────────────────────────

  test("ZenginProvider: supportedRails contains JP_ZENGIN"):
    val provider = providerInWindow
    assert(provider.supportedRails.contains(RailKind.JP_ZENGIN))

  test("ZenginProvider: supportedRails does not contain ACH_CREDIT"):
    val provider = providerInWindow
    assert(!provider.supportedRails.contains(RailKind.ACH_CREDIT))

  test("ZenginConfig.fromEnv returns non-empty defaults"):
    val cfg = ZenginConfig.fromEnv
    assert(cfg.baseUrl.nonEmpty)
    assert(cfg.senderBankCode.nonEmpty)
    assert(cfg.senderBranchCode.nonEmpty)

  // ── ZenginWebhookReceiver ─────────────────────────────────────────────────

  test("ZenginWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "zengin-webhook-secret-xyz"
    val body   = """{"type":"zengin.transfer.completed","transfer_id":"zen-001","amount":"10000"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("ZenginWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val body   = """{"type":"zengin.transfer.completed","transfer_id":"zen-002","amount":"5000"}"""
    val sig    = makeHmacSig("wrong-secret", body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("ZenginWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("ZenginWebhookReceiver: parses zengin.transfer.completed event"):
    val secret = "test-secret"
    val body   = """{"type":"zengin.transfer.completed","transfer_id":"zen-003","amount":"10000"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.ZenginSettled(transferId, amount) =>
        assert(transferId == "zen-003")
        assert(amount == "10000")
      case other => fail(s"Expected ZenginSettled, got $other")

  test("ZenginWebhookReceiver: parses zengin.transfer.failed event"):
    val secret = "fail-secret"
    val body   = """{"type":"zengin.transfer.failed","transfer_id":"zen-004","reason":"kana mismatch"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.ZenginRejected(transferId, reason) =>
        assert(transferId == "zen-004")
        assert(reason == "kana mismatch")
      case other => fail(s"Expected ZenginRejected, got $other")

  test("ZenginWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret-2"
    val body   = """{"type":"zengin.unknown.event","transfer_id":"zen-005"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("ZenginWebhookReceiver: signature in lowercase header is accepted"):
    val secret = "test-secret-3"
    val body   = """{"type":"zengin.transfer.completed","transfer_id":"zen-006","amount":"999"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("x-zengin-signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Lowercase header should also be accepted; got ${result.left.toOption}")

  test("ZenginWebhookReceiver: signature without sha256= prefix returns InvalidSignature"):
    val recv   = ZenginWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Zengin-Signature" -> "abcdef"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])
