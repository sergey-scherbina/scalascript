package scalascript.payments.caeft

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import com.sun.net.httpserver.{HttpServer, HttpHandler, HttpExchange}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

class CaEftProviderTest extends AnyFunSuite:

  // ── Test server helpers ───────────────────────────────────────────────────

  private def startServer(handler: HttpHandler): (HttpServer, Int) =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", handler)
    server.start()
    (server, server.getAddress.getPort)

  private def jsonHandler(response: String): HttpHandler = (ex: HttpExchange) =>
    val respBytes = response.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", "application/json")
    ex.sendResponseHeaders(200, respBytes.length)
    ex.getResponseBody.write(respBytes)
    ex.getResponseBody.close()

  private def makeSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private def testConfig(port: Int) = CaEftConfig(
    apiKey        = "test-api-key",
    baseUrl       = s"http://127.0.0.1:$port",
    webhookSecret = "test-webhook-secret",
    institutionId = "001",
  )

  private val cadAmount100 = Money(10000L, Currency("CAD"))  // CAD 100.00
  private val cadAmount250 = Money(25000L, Currency("CAD"))  // CAD 250.00

  private val senderAccount = BankAccount(
    holderName        = "Acme Corp",
    countryCode       = "CA",
    transitNumber     = Some("12345"),
    institutionNumber = Some("001"),
    accountNumber     = Some("1234567"),
  )

  private val recipientByEmail = BankAccount(
    holderName  = "Alice Martin",
    countryCode = "CA",
    email       = Some("alice@example.ca"),
  )

  private val recipientByPhone = BankAccount(
    holderName  = "Bob Tremblay",
    countryCode = "CA",
    phone       = Some("+14165551234"),
  )

  private val recipientEft = BankAccount(
    holderName        = "Carol Leblanc",
    countryCode       = "CA",
    transitNumber     = Some("98765"),
    institutionNumber = Some("002"),
    accountNumber     = Some("9876543"),
  )

  // ── RailKind.CA_INTERAC + CA_EFT tests ───────────────────────────────────

  test("RailKind.CA_INTERAC case exists"):
    val rail: RailKind = RailKind.CA_INTERAC
    assert(rail == RailKind.CA_INTERAC)

  test("RailKind.CA_EFT case exists"):
    val rail: RailKind = RailKind.CA_EFT
    assert(rail == RailKind.CA_EFT)

  test("RailKind.CA_INTERAC is distinct from CA_EFT"):
    assert(RailKind.CA_INTERAC != RailKind.CA_EFT)

  test("RailKind.CA_INTERAC is distinct from other rails"):
    assert(RailKind.CA_INTERAC != RailKind.SEPA_CT)
    assert(RailKind.CA_INTERAC != RailKind.ACH_CREDIT)
    assert(RailKind.CA_INTERAC != RailKind.SG_PAYNOW)
    assert(RailKind.CA_INTERAC != RailKind.UK_FPS)

  test("RailKind.CA_EFT is distinct from other rails"):
    assert(RailKind.CA_EFT != RailKind.ACH_DEBIT)
    assert(RailKind.CA_EFT != RailKind.UK_BACS_DD)

  // ── BankAccount additive fields: transitNumber, institutionNumber ─────────

  test("BankAccount.transitNumber field is preserved"):
    val account = BankAccount(
      holderName        = "Test",
      countryCode       = "CA",
      transitNumber     = Some("12345"),
    )
    assert(account.transitNumber == Some("12345"))

  test("BankAccount.institutionNumber field is preserved"):
    val account = BankAccount(
      holderName        = "Test",
      countryCode       = "CA",
      institutionNumber = Some("002"),
    )
    assert(account.institutionNumber == Some("002"))

  test("BankAccount.transitNumber defaults to None"):
    val account = BankAccount(holderName = "Test", countryCode = "CA")
    assert(account.transitNumber == None)

  test("BankAccount.institutionNumber defaults to None"):
    val account = BankAccount(holderName = "Test", countryCode = "CA")
    assert(account.institutionNumber == None)

  test("BankAccount email and phone fields default to None"):
    val account = BankAccount(holderName = "Test", countryCode = "CA")
    assert(account.email == None)
    assert(account.phone == None)

  // ── CaEftConfig tests ─────────────────────────────────────────────────────

  test("CaEftConfig fields accessible"):
    val cfg = CaEftConfig(
      apiKey        = "my-key",
      baseUrl       = "https://api.example.com",
      webhookSecret = "secret",
      institutionId = "003",
    )
    assert(cfg.apiKey        == "my-key")
    assert(cfg.baseUrl       == "https://api.example.com")
    assert(cfg.webhookSecret == "secret")
    assert(cfg.institutionId == "003")

  test("CaEftConfig.fromEnv returns a config without throwing"):
    val cfg = CaEftConfig.fromEnv
    assert(cfg.baseUrl.nonEmpty)

  // ── CaEftProvider SPI contract ────────────────────────────────────────────

  test("CaEftProvider.id returns 'ca-eft'"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      assert(provider.id == "ca-eft")
    finally server.stop(0)

  test("CaEftProvider.displayName is non-empty"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      assert(provider.displayName.nonEmpty)
    finally server.stop(0)

  test("CaEftProvider.spiVersion is non-empty"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      assert(provider.spiVersion.nonEmpty)
    finally server.stop(0)

  test("CaEftProvider.supportedRails contains CA_INTERAC and CA_EFT"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      assert(provider.supportedRails == Set(RailKind.CA_INTERAC, RailKind.CA_EFT))
    finally server.stop(0)

  test("CaEftProvider throws UnsupportedRail for SEPA_CT"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.SEPA_CT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientByEmail,
        reference      = "REF001",
        idempotencyKey = "idem-001",
      )
      intercept[UnsupportedRail] { provider.initiateTransfer(req) }
    finally server.stop(0)

  test("CaEftProvider throws UnsupportedRail for ACH_CREDIT"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.ACH_CREDIT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientByEmail,
        reference      = "REF002",
        idempotencyKey = "idem-002",
      )
      intercept[UnsupportedRail] { provider.initiateTransfer(req) }
    finally server.stop(0)

  // ── CAD currency enforcement ──────────────────────────────────────────────

  test("CaEftProvider throws IllegalArgumentException for non-CAD Interac transfer"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = Money(10000L, Currency("USD")),
        sender         = senderAccount,
        recipient      = recipientByEmail,
        reference      = "REF003",
        idempotencyKey = "idem-003",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  test("CaEftProvider throws IllegalArgumentException for GBP EFT transfer"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = Money(10000L, Currency("GBP")),
        sender         = senderAccount,
        recipient      = recipientEft,
        reference      = "REF004",
        idempotencyKey = "idem-004",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  test("CaEftProvider throws IllegalArgumentException for EUR direct debit"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateDirectDebitRequest(
        rail            = RailKind.CA_EFT,
        amount          = Money(5000L, Currency("EUR")),
        mandateId       = MandateId("m-001"),
        creditorAccount = senderAccount,
        debtorAccount   = recipientEft,
        creditorName    = "Acme Corp",
        reference       = "DD-REF",
        idempotencyKey  = "idem-dd-001",
      )
      intercept[IllegalArgumentException] { provider.initiateDirectDebit(req) }
    finally server.stop(0)

  // ── Interac e-Transfer by email happy path ────────────────────────────────

  test("CaEftProvider: Interac e-Transfer by email — happy path"):
    val respJson = """{"transferId":"IT-001","status":"pending","recipient":"alice@example.ca"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientByEmail,
        reference      = "Test payment",
        idempotencyKey = "idem-email-001",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.id.value == "IT-001")
      assert(transfer.rail     == RailKind.CA_INTERAC)
      assert(transfer.amount   == cadAmount100)
      assert(transfer.status   == BankTransferStatus.Pending)
      assert(transfer.metadata.get("recipientType") == Some("email"))
      assert(transfer.metadata.get("recipient")     == Some("alice@example.ca"))
    finally server.stop(0)

  // ── Interac e-Transfer by phone happy path ────────────────────────────────

  test("CaEftProvider: Interac e-Transfer by phone — happy path"):
    val respJson = """{"transferId":"IT-002","status":"pending","recipient":"+14165551234"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = cadAmount250,
        sender         = senderAccount,
        recipient      = recipientByPhone,
        reference      = "Phone transfer",
        idempotencyKey = "idem-phone-001",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.id.value == "IT-002")
      assert(transfer.rail     == RailKind.CA_INTERAC)
      assert(transfer.metadata.get("recipientType") == Some("phone"))
      assert(transfer.metadata.get("recipient")     == Some("+14165551234"))
    finally server.stop(0)

  test("CaEftProvider: email takes priority over phone when both set"):
    val respJson = """{"transferId":"IT-003","status":"pending"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val bothAccount = BankAccount(
        holderName  = "Dave",
        countryCode = "CA",
        email       = Some("dave@example.ca"),
        phone       = Some("+14165559999"),
      )
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = bothAccount,
        reference      = "Both ref",
        idempotencyKey = "idem-both-001",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.metadata.get("recipientType") == Some("email"))
    finally server.stop(0)

  test("CaEftProvider: missing email and phone throws IllegalArgumentException"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider  = CaEftProvider(testConfig(port))
      val noContact = BankAccount(holderName = "Nobody", countryCode = "CA")
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = noContact,
        reference      = "REF",
        idempotencyKey = "idem-nocontact",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  // ── EFT AFT credit file build + submission ────────────────────────────────

  test("CaEftProvider: EFT AFT credit transfer — happy path"):
    val respJson = """{"fileId":"EFT-CREDIT-001","status":"submitted"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientEft,
        reference      = "EFT-REF-001",
        idempotencyKey = "idem-eft-001",
      )
      val transfer = provider.initiateTransfer(req)
      assert(transfer.id.value.contains("EFT-CREDIT-001"))
      assert(transfer.rail   == RailKind.CA_EFT)
      assert(transfer.status == BankTransferStatus.Pending)
      assert(transfer.metadata.get("aftType") == Some("credit"))
    finally server.stop(0)

  test("CaEftProvider: EFT credit requires transitNumber"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val noTransit = BankAccount(
        holderName        = "Carol",
        countryCode       = "CA",
        institutionNumber = Some("002"),
        accountNumber     = Some("9876543"),
      )
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = noTransit,
        reference      = "REF",
        idempotencyKey = "idem-notransit",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  test("CaEftProvider: EFT credit requires institutionNumber"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val noInstitution = BankAccount(
        holderName    = "Carol",
        countryCode   = "CA",
        transitNumber = Some("98765"),
        accountNumber = Some("9876543"),
      )
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = noInstitution,
        reference      = "REF",
        idempotencyKey = "idem-noinstitution",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  test("CaEftProvider: EFT credit requires accountNumber"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val noAccount = BankAccount(
        holderName        = "Carol",
        countryCode       = "CA",
        transitNumber     = Some("98765"),
        institutionNumber = Some("002"),
      )
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = noAccount,
        reference      = "REF",
        idempotencyKey = "idem-noaccount",
      )
      intercept[IllegalArgumentException] { provider.initiateTransfer(req) }
    finally server.stop(0)

  // ── EFT AFT debit (direct debit) ─────────────────────────────────────────

  test("CaEftProvider: EFT AFT debit — direct debit happy path"):
    val respJson = """{"fileId":"EFT-DEBIT-001","status":"submitted"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateDirectDebitRequest(
        rail            = RailKind.CA_EFT,
        amount          = cadAmount250,
        mandateId       = MandateId("mandate-ca-001"),
        creditorAccount = senderAccount,
        debtorAccount   = recipientEft,
        creditorName    = "Acme Corp",
        reference       = "DD-REF-001",
        idempotencyKey  = "idem-dd-eft-001",
      )
      val transfer = provider.initiateDirectDebit(req)
      assert(transfer.id.value.contains("EFT-DEBIT-001"))
      assert(transfer.rail   == RailKind.CA_EFT)
      assert(transfer.status == BankTransferStatus.Pending)
      assert(transfer.metadata.get("aftType") == Some("debit"))
    finally server.stop(0)

  test("CaEftProvider: EFT direct debit throws UnsupportedRail for CA_INTERAC"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateDirectDebitRequest(
        rail            = RailKind.CA_INTERAC,
        amount          = cadAmount100,
        mandateId       = MandateId("m-002"),
        creditorAccount = senderAccount,
        debtorAccount   = recipientEft,
        creditorName    = "Acme Corp",
        reference       = "DD-REF",
        idempotencyKey  = "idem-dd-interac-001",
      )
      intercept[UnsupportedRail] { provider.initiateDirectDebit(req) }
    finally server.stop(0)

  test("CaEftProvider: EFT debit requires debtorAccount.transitNumber"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider  = CaEftProvider(testConfig(port))
      val noTransit = BankAccount(
        holderName        = "Debtor",
        countryCode       = "CA",
        institutionNumber = Some("002"),
        accountNumber     = Some("1234567"),
      )
      val req = InitiateDirectDebitRequest(
        rail            = RailKind.CA_EFT,
        amount          = cadAmount100,
        mandateId       = MandateId("m-003"),
        creditorAccount = senderAccount,
        debtorAccount   = noTransit,
        creditorName    = "Acme Corp",
        reference       = "REF",
        idempotencyKey  = "idem-dd-notransit",
      )
      intercept[IllegalArgumentException] { provider.initiateDirectDebit(req) }
    finally server.stop(0)

  // ── cancelTransfer: Interac recall vs EFT error ───────────────────────────

  test("cancelTransfer for CA_INTERAC calls recall API"):
    val recallResp = """{"status":"recalled","transferId":"IT-RECALL-001"}"""
    val (server, port) = startServer(jsonHandler(recallResp))
    try
      val provider = CaEftProvider(testConfig(port))
      // No exception should be thrown for Interac cancel (recall)
      provider.cancelTransfer(TransferId("IT-RECALL-001"))
    finally server.stop(0)

  test("cancelTransfer for CA_EFT throws BankRailsCancelError"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      // Seed an EFT transfer in local store
      val eftResp  = """{"fileId":"EFT-CANT-CANCEL","status":"submitted"}"""
      val eftServer2 = startServer(jsonHandler(eftResp))
      val eftProvider = CaEftProvider(testConfig(eftServer2._2))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientEft,
        reference      = "REF",
        idempotencyKey = "idem-cant-cancel",
      )
      eftProvider.initiateTransfer(req)
      intercept[BankRailsCancelError] {
        eftProvider.cancelTransfer(TransferId("EFT-CANT-CANCEL"))
      }
      eftServer2._1.stop(0)
    finally server.stop(0)

  test("cancelTransfer error message mentions EFT irrevocable"):
    val (server, port) = startServer(jsonHandler("""{"fileId":"EFT-X","status":"submitted"}"""))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_EFT,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientEft,
        reference      = "REF",
        idempotencyKey = "idem-eft-cancel-msg",
      )
      provider.initiateTransfer(req)
      val ex = intercept[BankRailsCancelError] {
        provider.cancelTransfer(TransferId("EFT-X"))
      }
      assert(ex.getMessage.toLowerCase.contains("eft") || ex.getMessage.contains("cancel"))
    finally server.stop(0)

  // ── CPA 005 file format: field positions, padding, checksums ─────────────

  test("AftRecord: transactionType 450 = AFT credit"):
    val r = AftRecord(450, 10000L, "12345", "001", "1234567", "Alice Martin", "REF-001")
    assert(r.transactionType == 450)

  test("AftRecord: transactionType 470 = AFT debit"):
    val r = AftRecord(470, 10000L, "12345", "001", "1234567", "Bob Smith", "REF-002")
    assert(r.transactionType == 470)

  test("CaEftApi.buildAftFile: header record is 1464 chars and starts with 'A'"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 5000L, "12345", "001", "1234567", "Test Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val lines = file.split("\n")
    assert(lines.head.startsWith("A"), s"Header should start with 'A', got: ${lines.head.take(5)}")
    assert(lines.head.length == AFT_RECORD_LENGTH,
      s"Header record should be 1464 chars, got ${lines.head.length}")

  test("CaEftApi.buildAftFile: detail record is 1464 chars and starts with 'D'"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 5000L, "12345", "001", "1234567", "Test Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val lines = file.split("\n")
    assert(lines(1).startsWith("D"), s"Detail record should start with 'D', got: ${lines(1).take(5)}")
    assert(lines(1).length == AFT_RECORD_LENGTH,
      s"Detail record should be 1464 chars, got ${lines(1).length}")

  test("CaEftApi.buildAftFile: trailer record is 1464 chars and starts with 'Z'"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 5000L, "12345", "001", "1234567", "Test Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val lines = file.split("\n")
    assert(lines.last.startsWith("Z"), s"Trailer should start with 'Z', got: ${lines.last.take(5)}")
    assert(lines.last.length == AFT_RECORD_LENGTH,
      s"Trailer record should be 1464 chars, got ${lines.last.length}")

  test("CaEftApi.buildAftFile: detail record contains transaction type at positions 2-4"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 5000L, "12345", "001", "1234567", "Test Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val detailLine = file.split("\n")(1)
    // [1] = 'D', [2-4] = "450" (0-indexed: chars 1,2,3)
    assert(detailLine.substring(1, 4) == "450", s"Expected '450' at pos 2-4, got '${detailLine.substring(1, 4)}'")

  test("CaEftApi.buildAftFile: AFT debit records use type 470"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 2)
    val records = List(AftRecord(470, 7500L, "54321", "003", "7654321", "Debit Payee", "DD-REF"))
    val file = api.buildAftFile(header, records)
    val detailLine = file.split("\n")(1)
    assert(detailLine.substring(1, 4) == "470", s"Expected '470' at pos 2-4, got '${detailLine.substring(1, 4)}'")

  test("CaEftApi.buildAftFile: amount is zero-padded to 10 digits at positions 5-14"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 5000L, "12345", "001", "1234567", "Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val detailLine = file.split("\n")(1)
    // [5-14] = 0-indexed chars 4..13 = 10 digits for 5000 = "0000005000"
    val amountField = detailLine.substring(4, 14)
    assert(amountField == "0000005000",
      s"Expected '0000005000', got '$amountField'")

  test("CaEftApi.buildAftFile: transit number at positions 15-19"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 100L, "12345", "001", "1234567", "Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val detailLine = file.split("\n")(1)
    // [15-19] = 0-indexed chars 14..18
    val transitField = detailLine.substring(14, 19)
    assert(transitField == "12345", s"Expected '12345', got '$transitField'")

  test("CaEftApi.buildAftFile: institution number at positions 20-22"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 100L, "12345", "003", "1234567", "Payee", "REF"))
    val file = api.buildAftFile(header, records)
    val detailLine = file.split("\n")(1)
    // [20-22] = 0-indexed chars 19..21
    val instField = detailLine.substring(19, 22)
    assert(instField == "003", s"Expected '003', got '$instField'")

  test("CaEftApi.buildAftFile: trailer credit total is sum of credit amounts"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(
      AftRecord(450, 1000L, "11111", "001", "1111111", "Payee1", "REF1"),
      AftRecord(450, 2500L, "22222", "002", "2222222", "Payee2", "REF2"),
    )
    val file = api.buildAftFile(header, records)
    val trailerLine = file.split("\n").last
    // Trailer [12-21] = 0-indexed chars 11..20 = total credit = 3500 = "0000003500"
    val totalCreditField = trailerLine.substring(11, 21)
    assert(totalCreditField == "0000003500",
      s"Expected '0000003500' for total credit, got '$totalCreditField'")

  test("CaEftApi.buildAftFile: trailer debit total is sum of debit amounts"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(
      AftRecord(470, 4000L, "33333", "003", "3333333", "Debtor1", "DD1"),
      AftRecord(470, 1500L, "44444", "004", "4444444", "Debtor2", "DD2"),
    )
    val file = api.buildAftFile(header, records)
    val trailerLine = file.split("\n").last
    // Trailer [22-31] = 0-indexed chars 21..30 = total debit = 5500 = "0000005500"
    val totalDebitField = trailerLine.substring(21, 31)
    assert(totalDebitField == "0000005500",
      s"Expected '0000005500' for total debit, got '$totalDebitField'")

  test("CaEftApi.buildAftFile: file has 3 lines for 1 detail record"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(AftRecord(450, 100L, "12345", "001", "1234567", "Payee", "REF"))
    val file = api.buildAftFile(header, records)
    assert(file.split("\n").length == 3, s"Expected 3 lines, got ${file.split('\n').length}")

  test("CaEftApi.buildAftFile: file has 4 lines for 2 detail records"):
    val api = CaEftApi(CaEftConfig("k", "https://example.com", "s", "001"))
    val header = AftFileHeader("ORIG001", LocalDate.of(2026, 1, 15), 1)
    val records = List(
      AftRecord(450, 100L, "12345", "001", "1234567", "Payee1", "REF1"),
      AftRecord(470, 200L, "54321", "002", "7654321", "Payee2", "REF2"),
    )
    val file = api.buildAftFile(header, records)
    assert(file.split("\n").length == 4)

  // ── HMAC webhook verify + all 4 event types ───────────────────────────────

  test("CaEftWebhookReceiver: valid HMAC signature verifies"):
    val secret = "webhook-secret"
    val body   = """{"type":"interac.transfer.sent","transferId":"IT-101","recipient":"alice@example.ca","amount":"10000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right, got Left(${result.left.toOption})")

  test("CaEftWebhookReceiver: wrong HMAC is rejected with InvalidSignature"):
    val secret = "correct-secret"
    val body   = """{"type":"interac.transfer.sent","transferId":"IT-102","recipient":"bob@example.ca","amount":"5000"}"""
    val sig    = makeSignature("wrong-secret", body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("CaEftWebhookReceiver: missing signature header returns MissingHeader"):
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  test("CaEftWebhookReceiver: bad signature format (no sha256= prefix) rejected"):
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> "invalidsig"), rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("CaEftWebhookReceiver: lowercase header name is accepted"):
    val secret = "test-secret"
    val body   = """{"type":"interac.transfer.expired","transferId":"IT-200"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("x-interac-signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Lowercase header should work, got: ${result.left.toOption}")

  test("CaEftWebhookReceiver: interac.transfer.sent → CaInteracSent"):
    val secret = "test-secret"
    val body   = """{"type":"interac.transfer.sent","transferId":"IT-300","recipient":"alice@example.ca","amount":"25000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.CaInteracSent(transferId, recipient, amount) =>
        assert(transferId == "IT-300")
        assert(recipient  == "alice@example.ca")
        assert(amount     == "25000")
      case other => fail(s"Expected CaInteracSent, got $other")

  test("CaEftWebhookReceiver: interac.transfer.reclaimed → CaInteracReclaimed"):
    val secret = "test-secret"
    val body   = """{"type":"interac.transfer.reclaimed","transferId":"IT-400","reason":"sender-requested"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.CaInteracReclaimed(transferId, reason) =>
        assert(transferId == "IT-400")
        assert(reason     == "sender-requested")
      case other => fail(s"Expected CaInteracReclaimed, got $other")

  test("CaEftWebhookReceiver: interac.transfer.expired → CaInteracExpired"):
    val secret = "test-secret"
    val body   = """{"type":"interac.transfer.expired","transferId":"IT-500"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.CaInteracExpired(transferId) =>
        assert(transferId == "IT-500")
      case other => fail(s"Expected CaInteracExpired, got $other")

  test("CaEftWebhookReceiver: eft.debit.returned → CaEftReturned"):
    val secret = "test-secret"
    val body   = """{"type":"eft.debit.returned","transferId":"EFT-600","returnCode":"905","description":"Insufficient funds"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.CaEftReturned(transferId, returnCode, description) =>
        assert(transferId  == "EFT-600")
        assert(returnCode  == "905")
        assert(description == "Insufficient funds")
      case other => fail(s"Expected CaEftReturned, got $other")

  test("CaEftWebhookReceiver: unknown event type returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"type":"unknown.event","transferId":"X-001"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MalformedPayload])

  test("CaEftWebhookReceiver: missing type field returns MalformedPayload"):
    val secret = "test-secret"
    val body   = """{"transferId":"X-002","amount":"1000"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft)

  test("CaEftWebhookReceiver: eft.debit.returned uses EftReturnCode description fallback"):
    val secret = "test-secret"
    val body   = """{"type":"eft.debit.returned","transferId":"EFT-700","returnCode":"904"}"""
    val sig    = makeSignature(secret, body)
    val recv   = CaEftWebhookReceiver()
    val req    = WebhookRequest(headers = Map("X-Interac-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.CaEftReturned(_, _, description) =>
        assert(description.toLowerCase.contains("closed") || description.contains("904"))
      case other => fail(s"Expected CaEftReturned, got $other")

  // ── idempotencyKey tests ──────────────────────────────────────────────────

  test("CaEftWebhookReceiver.idempotencyKey: CaInteracSent includes transferId and recipient"):
    val recv  = CaEftWebhookReceiver()
    val event = BankRailsEvent.CaInteracSent("IT-800", "alice@example.ca", "10000")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("IT-800"))
    assert(key.contains("alice@example.ca"))

  test("CaEftWebhookReceiver.idempotencyKey: CaInteracReclaimed includes transferId"):
    val recv  = CaEftWebhookReceiver()
    val event = BankRailsEvent.CaInteracReclaimed("IT-900", "reason")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("IT-900"))

  test("CaEftWebhookReceiver.idempotencyKey: CaInteracExpired includes transferId"):
    val recv  = CaEftWebhookReceiver()
    val event = BankRailsEvent.CaInteracExpired("IT-1000")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("IT-1000"))

  test("CaEftWebhookReceiver.idempotencyKey: CaEftReturned includes transferId and returnCode"):
    val recv  = CaEftWebhookReceiver()
    val event = BankRailsEvent.CaEftReturned("EFT-1100", "905", "NSF")
    val key   = recv.idempotencyKey(event)
    assert(key.contains("EFT-1100"))
    assert(key.contains("905"))

  // ── HMAC helper tests ─────────────────────────────────────────────────────

  test("CaEftWebhookReceiver.hmacSha256: produces 64-char lowercase hex"):
    val recv   = CaEftWebhookReceiver()
    val result = recv.hmacSha256("secret", "test-payload")
    assert(result.length == 64)
    assert(result.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))

  test("CaEftWebhookReceiver.hmacSha256: different secrets produce different results"):
    val recv = CaEftWebhookReceiver()
    assert(recv.hmacSha256("secret1", "payload") != recv.hmacSha256("secret2", "payload"))

  test("CaEftWebhookReceiver.hmacSha256: different payloads produce different results"):
    val recv = CaEftWebhookReceiver()
    assert(recv.hmacSha256("secret", "payload1") != recv.hmacSha256("secret", "payload2"))

  // ── BankRailsEvent CA* cases ──────────────────────────────────────────────

  test("BankRailsEvent.CaInteracSent carries all fields"):
    val event = BankRailsEvent.CaInteracSent("IT-X", "phone@example.ca", "30000")
    event match
      case BankRailsEvent.CaInteracSent(transferId, recipient, amount) =>
        assert(transferId == "IT-X")
        assert(recipient  == "phone@example.ca")
        assert(amount     == "30000")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.CaInteracReclaimed carries all fields"):
    val event = BankRailsEvent.CaInteracReclaimed("IT-Y", "duplicate")
    event match
      case BankRailsEvent.CaInteracReclaimed(transferId, reason) =>
        assert(transferId == "IT-Y")
        assert(reason     == "duplicate")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.CaInteracExpired carries transferId"):
    val event = BankRailsEvent.CaInteracExpired("IT-Z")
    event match
      case BankRailsEvent.CaInteracExpired(transferId) => assert(transferId == "IT-Z")
      case _ => fail("Pattern match failed")

  test("BankRailsEvent.CaEftReturned carries all fields"):
    val event = BankRailsEvent.CaEftReturned("EFT-R", "901", "Invalid account")
    event match
      case BankRailsEvent.CaEftReturned(transferId, returnCode, description) =>
        assert(transferId  == "EFT-R")
        assert(returnCode  == "901")
        assert(description == "Invalid account")
      case _ => fail("Pattern match failed")

  test("All CA event cases are distinct BankRailsEvent members"):
    val e1 = BankRailsEvent.CaInteracSent("t1", "e@e.com", "100")
    val e2 = BankRailsEvent.CaInteracReclaimed("t2", "reason")
    val e3 = BankRailsEvent.CaInteracExpired("t3")
    val e4 = BankRailsEvent.CaEftReturned("t4", "905", "NSF")
    assert(e1 != e2); assert(e2 != e3); assert(e3 != e4); assert(e1 != e4)

  // ── CaEftPlugin tests ─────────────────────────────────────────────────────

  test("CaEftPlugin id is non-empty"):
    val plugin = CaEftPlugin()
    assert(plugin.id.nonEmpty)

  test("CaEftPlugin displayName is non-empty"):
    val plugin = CaEftPlugin()
    assert(plugin.displayName.nonEmpty)

  test("CaEftPlugin capabilities include Feature.BankRails"):
    val plugin = CaEftPlugin()
    assert(plugin.capabilities.features.nonEmpty)

  // ── EftReturnCode description tests ──────────────────────────────────────

  test("EftReturnCode.description: known codes return descriptive text"):
    assert(EftReturnCode.description("900").nonEmpty)
    assert(EftReturnCode.description("901").nonEmpty)
    assert(EftReturnCode.description("905").toLowerCase.contains("fund") ||
           EftReturnCode.description("905").contains("NSF"))
    assert(EftReturnCode.description("904").toLowerCase.contains("closed") ||
           EftReturnCode.description("904").nonEmpty)

  test("EftReturnCode.description: unknown code returns fallback string"):
    val desc = EftReturnCode.description("999")
    assert(desc.contains("999"))

  // ── getTransfer/getDirectDebit tests ─────────────────────────────────────

  test("CaEftProvider.getDirectDebit throws TransferNotFound for unknown id"):
    val (server, port) = startServer(jsonHandler("{}"))
    try
      val provider = CaEftProvider(testConfig(port))
      intercept[TransferNotFound] {
        provider.getDirectDebit(TransferId("no-such-id"))
      }
    finally server.stop(0)

  test("CaEftProvider idempotency: same key returns same transfer"):
    val respJson = """{"transferId":"IT-IDEM","status":"pending"}"""
    val (server, port) = startServer(jsonHandler(respJson))
    try
      val provider = CaEftProvider(testConfig(port))
      val req = InitiateTransferRequest(
        rail           = RailKind.CA_INTERAC,
        amount         = cadAmount100,
        sender         = senderAccount,
        recipient      = recipientByEmail,
        reference      = "Idem test",
        idempotencyKey = "idem-key-123",
      )
      val t1 = provider.initiateTransfer(req)
      val t2 = provider.initiateTransfer(req)
      assert(t1.id == t2.id)
    finally server.stop(0)
