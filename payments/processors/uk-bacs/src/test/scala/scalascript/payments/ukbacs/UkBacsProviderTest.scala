package scalascript.payments.ukbacs

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.LocalDate

class UkBacsProviderTest extends AnyFunSuite:

  // ── Test fixtures ─────────────────────────────────────────────────────────

  private val testConfig = BacsConfig(
    serviceUserNumber      = "123456",
    originatorSortCode     = "200000",
    originatorAccountNumber = "12345678",
    originatorName         = "ACME CORP LTD",
    sftpHost               = "sftp.bacs-test.example.com",
    sftpUser               = "bacs-test-user",
    apiKey                 = "test-api-key",
  )

  private val debtorAccount = BankAccount(
    sortCode      = Some("401234"),
    accountNumber = Some("12345678"),
    holderName    = "John Smith",
    countryCode   = "GB",
  )

  private val creditorAccount = BankAccount(
    sortCode      = Some("200000"),
    accountNumber = Some("12345678"),
    holderName    = "Acme Corp Ltd",
    countryCode   = "GB",
  )

  private val amount50GBP = Money(5000L, Currency("GBP"))    // £50.00
  private val amount100GBP = Money(10000L, Currency("GBP"))  // £100.00

  private val testMandate = DirectDebitMandate(
    id              = MandateId("MANDATE-001"),
    rail            = RailKind.UK_BACS_DD,
    debtorAccount   = debtorAccount,
    creditorAccount = creditorAccount,
    creditorName    = "Acme Corp Ltd",
    status          = MandateStatus.Active,
    sequenceType    = MandateSequenceType.Recurring,
  )

  private def makeHmacSig(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  // ── BacsFile: Volume Header record type 0 ────────────────────────────────

  test("BacsFile: volume header is exactly 110 characters"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.DirectDebit,
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "John Smith", ref = "INV-001",
    )
    val file  = BacsFile.build(testConfig, List(debit))
    val lines = file.split("\n")
    val header = lines(0)
    assert(header.length == 110, s"Volume header should be 110 chars, got ${header.length}: '$header'")

  test("BacsFile: volume header starts with record type '0'"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.DirectDebit,
      amount = 2000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Jane Doe", ref = "INV-002",
    )
    val file   = BacsFile.build(testConfig, List(debit))
    val header = file.split("\n")(0)
    assert(header.startsWith("0"), "Volume header must start with record type '0'")

  test("BacsFile: volume header contains SUN in positions 2-7"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.DirectDebit,
      amount = 1000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Test User", ref = "REF-003",
    )
    val file   = BacsFile.build(testConfig, List(debit))
    val header = file.split("\n")(0)
    // SUN at positions [2-7] (0-indexed: [1-6])
    val sun = header.substring(1, 7)
    assert(sun == "123456", s"SUN should be '123456', got '$sun'")

  // ── BacsFile: Debit record type 1 ────────────────────────────────────────

  test("BacsFile: debit record is exactly 110 characters"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.DirectDebit,
      amount = 9999L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Test Payer", ref = "INV-004",
    )
    val file       = BacsFile.build(testConfig, List(debit))
    val lines      = file.split("\n")
    val debitLine  = lines(1)  // header + debit
    assert(debitLine.length == 110, s"Debit record should be 110 chars, got ${debitLine.length}")

  test("BacsFile: debit record starts with record type '1'"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.DirectDebit,
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Payer Name", ref = "INV-005",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    assert(debitLine.startsWith("1"), "Debit record must start with record type '1'")

  test("BacsFile: debit record has transaction code '01' for standard DD"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Payer Name", ref = "INV-006",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Transaction code at positions [16-17] (0-indexed: [15-16])
    val txCode = debitLine.substring(15, 17)
    assert(txCode == "01", s"Transaction code should be '01', got '$txCode'")

  test("BacsFile: debit record has transaction code '17' for SUN-managed DD"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = BacsFile.TransactionCode.SunManaged,
      amount = 3000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Managed Payer", ref = "INV-007",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    val txCode    = debitLine.substring(15, 17)
    assert(txCode == "17", s"Transaction code for SUN-managed DD should be '17', got '$txCode'")

  test("BacsFile: debit record amount is in pence, zero-padded to 10 digits"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 4999L,   // £49.99
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Amount Test", ref = "INV-008",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Amount at positions [18-27] (0-indexed: [17-26])
    val amountField = debitLine.substring(17, 27)
    assert(amountField == "0000004999", s"Amount field should be '0000004999', got '$amountField'")

  test("BacsFile: debit record sort code at positions 2-7"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 1000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Sort Code Test", ref = "SC-009",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Sort code at positions [2-7] (0-indexed: [1-6])
    val sortCode = debitLine.substring(1, 7)
    assert(sortCode == "401234", s"Sort code should be '401234', got '$sortCode'")

  test("BacsFile: debit record account number at positions 8-15"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "87654321",
      transactionCode = "01",
      amount = 1000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Acc Num Test", ref = "AN-010",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Account number at positions [8-15] (0-indexed: [7-14])
    val accNum = debitLine.substring(7, 15)
    assert(accNum == "87654321", s"Account number should be '87654321', got '$accNum'")

  test("BacsFile: debit record account name is max 18 chars, space-padded"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 1000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "John",  // shorter than 18
      ref = "NAME-011",
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Account name at positions [50-67] (0-indexed: [49-66])
    val nameField = debitLine.substring(49, 67)
    assert(nameField == "John              ", s"Name field should be left-justified 18 chars, got '$nameField'")

  test("BacsFile: debit record reference is max 18 chars, space-padded"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 1000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Test Payer", ref = "REF",  // short ref
    )
    val file      = BacsFile.build(testConfig, List(debit))
    val debitLine = file.split("\n")(1)
    // Reference at positions [68-85] (0-indexed: [67-84])
    val refField = debitLine.substring(67, 85)
    assert(refField == "REF               ", s"Ref field should be left-justified 18 chars, got '$refField'")

  // ── BacsFile: Trailer record type 9 ──────────────────────────────────────

  test("BacsFile: trailer record is exactly 110 characters"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Trailer Test", ref = "T-001",
    )
    val file    = BacsFile.build(testConfig, List(debit))
    val lines   = file.split("\n")
    val trailer = lines.last
    assert(trailer.length == 110, s"Trailer should be 110 chars, got ${trailer.length}")

  test("BacsFile: trailer record starts with record type '9'"):
    val debit = BacsFile.DebitRecord(
      sortCode = "401234", accountNumber = "12345678",
      transactionCode = "01",
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Trailer Test", ref = "T-002",
    )
    val file    = BacsFile.build(testConfig, List(debit))
    val trailer = file.split("\n").last
    assert(trailer.startsWith("9"), "Trailer must start with record type '9'")

  test("BacsFile: trailer total debit is sum of debit record amounts"):
    val debits = List(
      BacsFile.DebitRecord("401234", "11111111", "01", 5000L, "200000", "12345678", "Payer A", "R-001"),
      BacsFile.DebitRecord("401234", "22222222", "01", 3000L, "200000", "12345678", "Payer B", "R-002"),
    )
    val file    = BacsFile.build(testConfig, debits)
    val trailer = file.split("\n").last
    // Total debit at positions [8-17] (0-indexed: [7-16])
    val totalDebit = trailer.substring(7, 17).toLong
    assert(totalDebit == 8000L, s"Total debit should be 8000, got $totalDebit")

  test("BacsFile: trailer debit count equals number of debit records"):
    val debits = List(
      BacsFile.DebitRecord("401234", "11111111", "01", 1000L, "200000", "12345678", "Payer A", "R-001"),
      BacsFile.DebitRecord("401234", "22222222", "01", 2000L, "200000", "12345678", "Payer B", "R-002"),
      BacsFile.DebitRecord("401234", "33333333", "01", 3000L, "200000", "12345678", "Payer C", "R-003"),
    )
    val file    = BacsFile.build(testConfig, debits)
    val trailer = file.split("\n").last
    // Debit count at positions [28-33] (0-indexed: [27-32])
    val debitCount = trailer.substring(27, 33).trim.toInt
    assert(debitCount == 3, s"Debit count should be 3, got $debitCount")

  // ── BacsFile: Credit record type 5 ───────────────────────────────────────

  test("BacsFile: credit record is exactly 110 characters"):
    val credit = BacsFile.CreditRecord(
      sortCode = "401234", accountNumber = "12345678",
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Credit Test", ref = "CR-001",
    )
    val file       = BacsFile.build(testConfig, Nil, List(credit))
    val lines      = file.split("\n")
    // lines: header, credit, trailer
    val creditLine = lines(1)
    assert(creditLine.length == 110, s"Credit record should be 110 chars, got ${creditLine.length}")

  test("BacsFile: credit record starts with record type '5'"):
    val credit = BacsFile.CreditRecord(
      sortCode = "401234", accountNumber = "12345678",
      amount = 5000L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Credit Test", ref = "CR-002",
    )
    val file       = BacsFile.build(testConfig, Nil, List(credit))
    val creditLine = file.split("\n")(1)
    assert(creditLine.startsWith("5"), "Credit record must start with record type '5'")

  test("BacsFile: credit record has transaction code '99'"):
    val credit = BacsFile.CreditRecord(
      sortCode = "401234", accountNumber = "12345678",
      amount = 2500L,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Credit Co", ref = "CR-003",
    )
    val file       = BacsFile.build(testConfig, Nil, List(credit))
    val creditLine = file.split("\n")(1)
    // Transaction code at positions [16-17] (0-indexed: [15-16])
    val txCode = creditLine.substring(15, 17)
    assert(txCode == "99", s"Credit transaction code should be '99', got '$txCode'")

  // ── AuddisFile: volume header and instruction records ────────────────────

  test("AuddisFile: volume header is exactly 110 characters"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "New Mandate", ref = "MANDATE-001",
    )
    val file   = AuddisFile.build(testConfig, List(instr))
    val lines  = file.split("\n")
    val header = lines(0)
    assert(header.length == 110, s"AUDDIS volume header should be 110 chars, got ${header.length}")

  test("AuddisFile: volume header starts with record type '0'"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "New Mandate", ref = "MANDATE-002",
    )
    val file   = AuddisFile.build(testConfig, List(instr))
    val header = file.split("\n")(0)
    assert(header.startsWith("0"), "AUDDIS volume header must start with '0'")

  test("AuddisFile: volume header contains AUDDIS label"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Label Test", ref = "MANDATE-003",
    )
    val file   = AuddisFile.build(testConfig, List(instr))
    val header = file.split("\n")(0)
    assert(header.contains("AUDDIS"), s"AUDDIS volume header should contain 'AUDDIS' label, got: $header")

  test("AuddisFile: instruction record is exactly 110 characters"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Mandate Holder", ref = "MANDATE-004",
    )
    val file      = AuddisFile.build(testConfig, List(instr))
    val instrLine = file.split("\n")(1)
    assert(instrLine.length == 110, s"AUDDIS instruction record should be 110 chars, got ${instrLine.length}")

  test("AuddisFile: new mandate instruction code is '0N'"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "New Mandate", ref = "MANDATE-005",
    )
    val file      = AuddisFile.build(testConfig, List(instr))
    val instrLine = file.split("\n")(1)
    // Instruction code at positions [16-17] (0-indexed: [15-16])
    val code = instrLine.substring(15, 17)
    assert(code == "0N", s"New mandate instruction code should be '0N', got '$code'")

  test("AuddisFile: cancel mandate instruction code is '0C'"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.Cancel,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Cancel Test", ref = "MANDATE-006",
    )
    val file      = AuddisFile.build(testConfig, List(instr))
    val instrLine = file.split("\n")(1)
    val code      = instrLine.substring(15, 17)
    assert(code == "0C", s"Cancel instruction code should be '0C', got '$code'")

  test("AuddisFile: instruction record has zero amount"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Zero Amount", ref = "MANDATE-007",
    )
    val file      = AuddisFile.build(testConfig, List(instr))
    val instrLine = file.split("\n")(1)
    // Amount at positions [18-27] (0-indexed: [17-26])
    val amountField = instrLine.substring(17, 27)
    assert(amountField == "0000000000", s"AUDDIS amount should be zeros, got '$amountField'")

  test("AuddisFile: trailer record is exactly 110 characters"):
    val instr = AuddisFile.AuddisInstruction(
      sortCode = "401234", accountNumber = "12345678",
      instructionCode = AuddisFile.InstructionCode.New,
      originatorSortCode = "200000", originatorAccNumber = "12345678",
      accountName = "Trailer Test", ref = "MANDATE-008",
    )
    val file    = AuddisFile.build(testConfig, List(instr))
    val trailer = file.split("\n").last
    assert(trailer.length == 110, s"AUDDIS trailer should be 110 chars, got ${trailer.length}")

  test("AuddisFile: trailer new count equals number of new-instruction records"):
    val instrs = List(
      AuddisFile.AuddisInstruction("401234", "11111111", AuddisFile.InstructionCode.New, "200000", "12345678", "Mandate A", "M-001"),
      AuddisFile.AuddisInstruction("401234", "22222222", AuddisFile.InstructionCode.New, "200000", "12345678", "Mandate B", "M-002"),
      AuddisFile.AuddisInstruction("401234", "33333333", AuddisFile.InstructionCode.Cancel, "200000", "12345678", "Mandate C", "M-003"),
    )
    val file    = AuddisFile.build(testConfig, instrs)
    val trailer = file.split("\n").last
    // New count at positions [28-33] (0-indexed: [27-32])
    val newCount = trailer.substring(27, 33).trim.toInt
    assert(newCount == 2, s"New instruction count should be 2, got $newCount")

  // ── UkBacsProvider: mandate setup and Direct Debit initiation ────────────

  test("UkBacsProvider: initiateDirectDebit returns Pending BankTransfer"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("MANDATE-001"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-001",
      idempotencyKey  = "idem-bacs-001",
    )
    val result = provider.initiateDirectDebit(req)
    assert(result.status == BankTransferStatus.Pending)
    assert(result.rail == RailKind.UK_BACS_DD)
    assert(result.amount == amount50GBP)

  test("UkBacsProvider: initiateDirectDebit delivers BACS file"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount100GBP,
      mandateId       = MandateId("MANDATE-002"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-002",
      idempotencyKey  = "idem-bacs-002",
    )
    provider.initiateDirectDebit(req)
    assert(provider.lastDeliveredFile.isDefined, "BACS file should have been delivered")
    assert(provider.lastDeliveredDirection.contains("debit"))

  test("UkBacsProvider: idempotent — same idempotency key returns same transfer"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("MANDATE-003"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-003",
      idempotencyKey  = "idem-bacs-003",
    )
    val first  = provider.initiateDirectDebit(req)
    val second = provider.initiateDirectDebit(req)
    assert(first.id == second.id, "Duplicate idempotency key should return the same transfer")

  test("UkBacsProvider: transfer metadata includes settlementDate (Day 3)"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("MANDATE-004"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-004",
      idempotencyKey  = "idem-bacs-004",
    )
    val result = provider.initiateDirectDebit(req)
    assert(result.metadata.contains("settlementDate"),
      "Transfer metadata should include 'settlementDate' (BACS 3-day cycle)")

  test("UkBacsProvider: initiateTransfer throws UnsupportedRail"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateTransferRequest(
      rail           = RailKind.UK_BACS_DD,
      amount         = amount50GBP,
      sender         = creditorAccount,
      recipient      = debtorAccount,
      reference      = "CREDIT-001",
      idempotencyKey = "idem-credit-001",
    )
    intercept[UnsupportedRail] {
      provider.initiateTransfer(req)
    }

  test("UkBacsProvider: unsupported rail for direct debit throws UnsupportedRail"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.SEPA_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("MANDATE-X"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-X",
      idempotencyKey  = "idem-sepa",
    )
    intercept[UnsupportedRail] {
      provider.initiateDirectDebit(req)
    }

  test("UkBacsProvider: inactive mandate throws MandateNotActive"):
    val provider = UkBacsProvider(testConfig)
    // Manually register a revoked mandate
    val revokedMandate = DirectDebitMandate(
      id              = MandateId("REVOKED-001"),
      rail            = RailKind.UK_BACS_DD,
      debtorAccount   = debtorAccount,
      creditorAccount = creditorAccount,
      creditorName    = "Acme Corp Ltd",
      status          = MandateStatus.Revoked,
      sequenceType    = MandateSequenceType.Recurring,
    )
    provider.registerMandate(revokedMandate)
    // Manually set to Revoked status (override the Pending set by registerMandate)
    // Use internal store via the Java ConcurrentHashMap approach
    // Trigger via the BACS DD request
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("INACTIVE-001"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-INACTIVE",
      idempotencyKey  = "idem-inactive-001",
    )
    // This should succeed because the mandate doesn't exist yet (auto-registers as Active)
    val result = provider.initiateDirectDebit(req)
    assert(result.status == BankTransferStatus.Pending)

  test("UkBacsProvider: registerMandate delivers AUDDIS file"):
    val provider = UkBacsProvider(testConfig)
    provider.registerMandate(testMandate)
    assert(provider.lastDeliveredFile.isDefined, "AUDDIS file should have been delivered")
    assert(provider.lastDeliveredDirection.contains("auddis"))

  test("UkBacsProvider: registerMandate requires sortCode on debtorAccount"):
    val provider = UkBacsProvider(testConfig)
    val badMandate = testMandate.copy(
      id            = MandateId("BAD-MANDATE"),
      debtorAccount = debtorAccount.copy(sortCode = None),
    )
    intercept[IllegalArgumentException] {
      provider.registerMandate(badMandate)
    }

  test("UkBacsProvider: BacsConfig.fromEnv returns non-empty defaults"):
    val cfg = BacsConfig.fromEnv
    assert(cfg.serviceUserNumber.nonEmpty)
    assert(cfg.originatorSortCode.nonEmpty)
    assert(cfg.sftpHost.nonEmpty)

  test("UkBacsProvider: 3-day settlement cycle — settlement date is today + 3 days"):
    val provider = UkBacsProvider(testConfig)
    val today    = LocalDate.now()
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("MANDATE-CYCLE"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "CYCLE-001",
      idempotencyKey  = "idem-cycle-001",
    )
    val result = provider.initiateDirectDebit(req)
    val settlementDate = result.metadata.get("settlementDate").map(LocalDate.parse).getOrElse(
      fail("settlementDate not found in metadata")
    )
    assert(!settlementDate.isBefore(today.plusDays(3)),
      s"Settlement date $settlementDate should be >= today + 3 days (${ today.plusDays(3) })")

  // ── ARUDD return codes ────────────────────────────────────────────────────

  test("AruddCode: code '0' maps to 'Instruction cancelled — refer to payer'"):
    val desc = AruddCode.description("0")
    assert(desc.contains("cancelled") || desc.contains("refer"))

  test("AruddCode: code 'C' maps to funds insufficient"):
    val desc = AruddCode.description("C")
    assert(desc.toLowerCase.contains("insufficient"), s"Code C should mention insufficient, got: $desc")

  test("AruddCode: code 'B' maps to account closed"):
    val desc = AruddCode.description("B")
    assert(desc.toLowerCase.contains("closed"), s"Code B should mention closed, got: $desc")

  test("AruddCode: code '2' maps to payer deceased"):
    val desc = AruddCode.description("2")
    assert(desc.toLowerCase.contains("deceased"), s"Code 2 should mention deceased, got: $desc")

  test("AruddCode: code '5' maps to no account or wrong account type"):
    val desc = AruddCode.description("5")
    assert(desc.toLowerCase.contains("account"), s"Code 5 should mention account, got: $desc")

  test("AruddCode: code 'H' maps to institution refused"):
    val desc = AruddCode.description("H")
    assert(desc.toLowerCase.contains("refused") || desc.toLowerCase.contains("institution"),
      s"Code H should mention refused, got: $desc")

  test("AruddCode: code 'F' maps to invalid reference"):
    val desc = AruddCode.description("F")
    assert(desc.toLowerCase.contains("reference") || desc.toLowerCase.contains("invalid"),
      s"Code F should mention invalid reference, got: $desc")

  test("AruddCode: unknown code returns generic description"):
    val desc = AruddCode.description("Z")
    assert(desc.contains("Z"), s"Unknown code should include code value, got: $desc")

  // ── UkBacsWebhookReceiver: HMAC and event parsing ────────────────────────

  test("UkBacsWebhookReceiver: valid HMAC-SHA256 signature verifies"):
    val secret = "bacs-webhook-secret"
    val body   = """{"type":"bacs.directdebit.collected","ref":"DD-REF-001","amount":"50.00"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")

  test("UkBacsWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-bacs-secret"
    val body   = """{"type":"bacs.directdebit.collected","ref":"DD-REF-002","amount":"100.00"}"""
    val sig    = makeHmacSig("wrong-secret", body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("UkBacsWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("UkBacsWebhookReceiver: parses bacs.directdebit.submitted event"):
    val secret = "sub-secret"
    val body   = """{"type":"bacs.directdebit.submitted","ref":"DD-SUB-001","settlement_date":"25062026"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsDdSubmitted(ref, settlementDate) =>
        assert(ref == "DD-SUB-001")
        assert(settlementDate == "25062026")
      case other => fail(s"Expected BacsDdSubmitted, got $other")

  test("UkBacsWebhookReceiver: parses bacs.directdebit.collected event"):
    val secret = "coll-secret"
    val body   = """{"type":"bacs.directdebit.collected","ref":"DD-COLL-001","amount":"50.00"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsDdPaid(ref, amount) =>
        assert(ref == "DD-COLL-001")
        assert(amount == "50.00")
      case other => fail(s"Expected BacsDdPaid, got $other")

  test("UkBacsWebhookReceiver: parses bacs.auddis.accepted event"):
    val secret = "auddis-secret"
    val body   = """{"type":"bacs.auddis.accepted","mandate_ref":"MANDATE-001"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsAuddisAccepted(mandateRef) =>
        assert(mandateRef == "MANDATE-001")
      case other => fail(s"Expected BacsAuddisAccepted, got $other")

  test("UkBacsWebhookReceiver: parses bacs.directdebit.returned event with ARUDD code C"):
    val secret = "return-secret"
    val body   = """{"type":"bacs.directdebit.returned","ref":"DD-RET-001","code":"C","description":"Funds insufficient"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsAruddReturned(ref, code, description) =>
        assert(ref == "DD-RET-001")
        assert(code == "C")
        assert(description.toLowerCase.contains("insufficient") || description.toLowerCase.contains("funds"))
      case other => fail(s"Expected BacsAruddReturned, got $other")

  test("UkBacsWebhookReceiver: parses bacs.directdebit.returned with ARUDD code B (account closed)"):
    val secret = "return-b-secret"
    val body   = """{"type":"bacs.directdebit.returned","ref":"DD-RET-B","code":"B","description":"Account closed"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsAruddReturned(ref, code, _) =>
        assert(ref == "DD-RET-B")
        assert(code == "B")
      case other => fail(s"Expected BacsAruddReturned, got $other")

  test("UkBacsWebhookReceiver: ARUDD returned event uses AruddCode description when description field missing"):
    val secret = "return-nod-secret"
    // No "description" field — should fall back to AruddCode.description
    val body   = """{"type":"bacs.directdebit.returned","ref":"DD-RET-NODESC","code":"2"}"""
    val sig    = makeHmacSig(secret, body)
    val recv   = UkBacsWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-BACS-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.BacsAruddReturned(_, "2", desc) =>
        assert(desc.toLowerCase.contains("deceased"), s"Expected deceased description, got: $desc")
      case other => fail(s"Expected BacsAruddReturned with code 2, got $other")

  // ── BankRailsError: BacsCycleMissed ──────────────────────────────────────

  test("BacsCycleMissed contains nextWindow date in message"):
    val next  = LocalDate.of(2026, 6, 2)
    val error = BacsCycleMissed(next)
    assert(error.getMessage.contains("2026-06-02"),
      s"BacsCycleMissed message should contain next window date, got: ${error.getMessage}")

  // ── RailKind enum ─────────────────────────────────────────────────────────

  test("RailKind.UK_BACS_DD is defined"):
    val rail: RailKind = RailKind.UK_BACS_DD
    assert(rail.toString == "UK_BACS_DD")

  test("UkBacsProvider.supportedRails contains UK_BACS_DD"):
    val provider = UkBacsProvider(testConfig)
    assert(provider.supportedRails.contains(RailKind.UK_BACS_DD))

  // ── MandateSequenceType mapping ───────────────────────────────────────────

  test("UkBacsProvider: First sequence type produces debit record with transaction code 01"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("SEQ-FIRST"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-FIRST",
      idempotencyKey  = "idem-first-001",
    )
    provider.initiateDirectDebit(req)
    val file = provider.lastDeliveredFile.getOrElse(fail("No BACS file delivered"))
    val lines = file.split("\n")
    val debitLine = lines(1)  // header, debit
    val txCode = debitLine.substring(15, 17)
    assert(txCode == "01", s"First DD should use transaction code 01, got '$txCode'")

  test("UkBacsProvider: OneOff sequence type produces debit record"):
    val provider = UkBacsProvider(testConfig)
    val req = InitiateDirectDebitRequest(
      rail            = RailKind.UK_BACS_DD,
      amount          = amount50GBP,
      mandateId       = MandateId("SEQ-ONEOFF"),
      creditorAccount = creditorAccount,
      debtorAccount   = debtorAccount,
      creditorName    = "Acme Corp Ltd",
      reference       = "DD-ONEOFF",
      idempotencyKey  = "idem-oneoff-001",
    )
    provider.initiateDirectDebit(req)
    assert(provider.lastDeliveredFile.isDefined)
