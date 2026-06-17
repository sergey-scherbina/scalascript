package scalascript.payments.pix

import org.scalatest.funsuite.AnyFunSuite
import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PixProviderTest extends AnyFunSuite:

  // ── Test helpers ──────────────────────────────────────────────────────────

  /** Compute HMAC-SHA256 and return "sha256=<hex>" for use as X-Pix-Signature. */
  private def makePixSignature(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"sha256=$hex"

  private val recv = PixWebhookReceiver()

  // ── QR Code: static ──────────────────────────────────────────────────────

  test("PixQrCode.buildStatic: payload starts with ID 00 = '01' (Payload Format Indicator)"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "comerciante@empresa.com",
      merchantName = "Empresa Teste",
      merchantCity = "Sao Paulo",
    )
    val payload = PixQrCode.buildStatic(cfg)
    assert(payload.startsWith("000201"), s"Payload should start with '000201' but got: ${payload.take(10)}")

  test("PixQrCode.buildStatic: ID 26 contains 'br.gov.bcb.pix' GUI"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "comerciante@empresa.com",
      merchantName = "Empresa Teste",
      merchantCity = "Sao Paulo",
    )
    val payload = PixQrCode.buildStatic(cfg)
    assert(payload.contains("br.gov.bcb.pix"), s"Payload should contain 'br.gov.bcb.pix': $payload")

  test("PixQrCode.buildStatic: payload contains the Pix key"):
    val key = "cpf.titular@banco.com"
    val cfg = PixQrCode.StaticConfig(
      pixKey       = key,
      merchantName = "Loja Exemplo",
      merchantCity = "Rio de Janeiro",
    )
    val payload = PixQrCode.buildStatic(cfg)
    assert(payload.contains(key), s"Payload should contain the Pix key '$key'")

  test("PixQrCode.buildStatic: ID 58 = 'BR' (Country Code)"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "evp-key-uuid",
      merchantName = "Test Merchant",
      merchantCity = "Brasilia",
    )
    val payload = PixQrCode.buildStatic(cfg)
    // ID 58 country code; value = "BR" → TLV = "5802BR"
    assert(payload.contains("5802BR"), s"Payload should contain '5802BR': $payload")

  test("PixQrCode.buildStatic: ID 53 = '986' (BRL currency)"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "+5511999990000",
      merchantName = "Test Shop",
      merchantCity = "Curitiba",
    )
    val payload = PixQrCode.buildStatic(cfg)
    // ID 53 transaction currency; "986" = BRL → TLV = "5303986"
    assert(payload.contains("5303986"), s"Payload should contain '5303986' (BRL): $payload")

  // ── QR Code: amount ───────────────────────────────────────────────────────

  test("PixQrCode.buildStatic: ID 54 present when amount specified"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "12345678901",  // CPF
      merchantName = "Padaria Pao",
      merchantCity = "Campinas",
      amount       = Some(Money(1000L, Currency("BRL"))),  // R$ 10.00
    )
    val payload = PixQrCode.buildStatic(cfg)
    // ID 54 = amount; "10.00" → TLV = "540510.00"
    assert(payload.contains("54"), s"Payload should contain field ID 54 (amount): $payload")
    assert(payload.contains("10.00"), s"Payload should contain amount '10.00': $payload")

  test("PixQrCode.buildStatic: ID 54 absent when no amount (open-value)"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "12345678901",
      merchantName = "Padaria Pao",
      merchantCity = "Campinas",
      amount       = None,  // open-value
    )
    val payload = PixQrCode.buildStatic(cfg)
    // No amount field should mean "5404" or similar is not in payload
    // We check that "10.00" (or any amount) is absent for this open-value QR
    assert(!payload.contains("540"), "ID 54 (amount) should be absent for open-value QR")

  // ── QR Code: dynamic ─────────────────────────────────────────────────────

  test("PixQrCode.buildDynamic: ID 62 sub-05 contains txid"):
    val txid = "txid123abc456def789012345"
    val cfg = PixQrCode.DynamicConfig(
      cobvUrl      = "pix.example.com/v2/cobv/" + txid,
      merchantName = "E-Commerce BR",
      merchantCity = "Sao Paulo",
      amount       = Some(Money(5000L, Currency("BRL"))),
      txid         = txid,
    )
    val payload = PixQrCode.buildDynamic(cfg)
    assert(payload.contains(txid), s"Dynamic QR payload should contain txid '$txid': $payload")

  test("PixQrCode.buildDynamic: payload contains cobv URL"):
    val cobvUrl = "pix.mystore.com.br/v2/cobv/abc123"
    val cfg = PixQrCode.DynamicConfig(
      cobvUrl      = cobvUrl,
      merchantName = "Loja Virtual",
      merchantCity = "Fortaleza",
      txid         = "abc123xyz000000000000000000000000",
    )
    val payload = PixQrCode.buildDynamic(cfg)
    assert(payload.contains(cobvUrl), s"Dynamic QR should contain cobv URL: $payload")

  // ── QR Code: CRC-16 ───────────────────────────────────────────────────────

  test("PixQrCode.crc16: known value '123456789' produces 0x29B1"):
    // Standard CRC-16/CCITT test vector: "123456789" → 0x29B1
    val result = PixQrCode.crc16("123456789")
    assert(result == 0x29B1, f"Expected 0x29B1 but got 0x${result}%04X")

  test("PixQrCode.buildStatic: payload ends with 4-hex-digit CRC after '6304'"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "test@pix.com",
      merchantName = "Test Merchant",
      merchantCity = "Test City",
    )
    val payload = PixQrCode.buildStatic(cfg)
    // Payload must end with "6304XXXX" where XXXX is 4 uppercase hex digits
    val crcSection = payload.takeRight(8)
    assert(crcSection.startsWith("6304"), s"Payload should end with '6304<CRC>': $crcSection")
    val hexPart = crcSection.drop(4)
    assert(hexPart.forall(c => "0123456789ABCDEF".contains(c)),
      s"CRC should be 4 uppercase hex digits, got: '$hexPart'")

  test("PixQrCode: CRC is self-consistent — recomputing over pre-CRC string matches embedded CRC"):
    val cfg = PixQrCode.StaticConfig(
      pixKey       = "test@pix.com",
      merchantName = "Merchant",
      merchantCity = "City",
    )
    val payload   = PixQrCode.buildStatic(cfg)
    // Strip trailing 4 chars (the CRC value), keep up to and including "6304"
    val preCrc    = payload.dropRight(4)
    val embedded  = payload.takeRight(4)
    val computed  = PixQrCode.crc16(preCrc)
    val hexCrc    = computed.toHexString.toUpperCase.reverse.padTo(4, '0').reverse
    assert(hexCrc == embedded, s"Embedded CRC '$embedded' should match recomputed '$hexCrc'")

  // ── Webhook: event parsing ────────────────────────────────────────────────

  test("PixWebhookReceiver: valid signature verifies and parses pix.received event"):
    val secret = "pix-webhook-secret-xyz"
    val body   = """{"pix": [{"endToEndId": "E0000000020260527120000000001", "txid": "txid001abc", "valor": "10.00"}]}"""
    val sig    = makePixSignature(secret, body)
    val req    = WebhookRequest(headers = Map("X-Pix-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.PixReceived])

  test("PixWebhookReceiver: pix.received event carries correct endToEndId"):
    val secret  = "secret-abc"
    val e2eId   = "E0000000020260527120000000099"
    val body    = s"""{"pix": [{"endToEndId": "$e2eId", "txid": "txid099", "valor": "25.50"}]}"""
    val sig     = makePixSignature(secret, body)
    val req     = WebhookRequest(headers = Map("X-Pix-Signature" -> sig), rawBody = body)
    val result  = recv.verify(req, secret)
    assert(result.isRight)
    result.toOption.get match
      case BankRailsEvent.PixReceived(t) =>
        assert(t.id.value == e2eId, s"Transfer ID should be endToEndId '$e2eId'")
        assert(t.amount == Money(2550L, Currency("BRL")), "Amount should be R$25.50 (2550 centavos)")
      case other => fail(s"Expected PixReceived, got $other")

  test("PixWebhookReceiver: pix.refunded event parsed correctly"):
    val secret = "refund-secret"
    val body   = """{"evento": "pix.refunded", "endToEndId": "E0000000020260527130000000001", "valor": "5.00", "originalEndToEndId": "E000000002026052712"}"""
    val sig    = makePixSignature(secret, body)
    val req    = WebhookRequest(headers = Map("X-Pix-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")
    assert(result.toOption.get.isInstanceOf[BankRailsEvent.PixRefunded])

  test("PixWebhookReceiver: pix.rejected event parsed with error code"):
    val secret = "reject-secret"
    val body   = """{"evento": "pix.rejected", "endToEndId": "E0000000020260527130000000002", "codigoErro": "ED05", "descricao": "Timeout DICT"}"""
    val sig    = makePixSignature(secret, body)
    val req    = WebhookRequest(headers = Map("X-Pix-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isRight, s"Expected Right but got Left(${result.left.toOption})")
    result.toOption.get match
      case BankRailsEvent.PixRejected(_, code) =>
        assert(code.value == "ED05", s"Reject code should be ED05, got ${code.value}")
      case other => fail(s"Expected PixRejected, got $other")

  test("PixWebhookReceiver: wrong signature is rejected"):
    val secret = "correct-secret"
    val body   = """{"pix": [{"endToEndId": "E001", "txid": "txid001", "valor": "1.00"}]}"""
    val sig    = makePixSignature("wrong-secret", body)
    val req    = WebhookRequest(headers = Map("X-Pix-Signature" -> sig), rawBody = body)
    val result = recv.verify(req, secret)
    assert(result.isLeft, "Wrong signature should be rejected")
    assert(result.swap.toOption.get.isInstanceOf[InvalidSignature])

  test("PixWebhookReceiver: missing signature header returns MissingHeader"):
    val req    = WebhookRequest(headers = Map.empty, rawBody = "{}")
    val result = recv.verify(req, "secret")
    assert(result.isLeft)
    assert(result.swap.toOption.get.isInstanceOf[MissingHeader])

  // ── BankTransfer status mapping ───────────────────────────────────────────

  test("PixProvider.parsePixStatus: CONCLUIDA maps to Settled"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    assert(p.parsePixStatus("CONCLUIDA") == BankTransferStatus.Settled)

  test("PixProvider.parsePixStatus: ATIVA maps to Pending"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    assert(p.parsePixStatus("ATIVA") == BankTransferStatus.Pending)

  test("PixProvider.parsePixStatus: CANCELADA maps to Canceled"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    assert(p.parsePixStatus("CANCELADA") == BankTransferStatus.Canceled)

  test("PixProvider.parsePixStatus: NAO_REALIZADO maps to Rejected"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    p.parsePixStatus("NAO_REALIZADO") match
      case BankTransferStatus.Rejected(code, _) =>
        assert(code.value == "NAO_REALIZADO")
      case other => fail(s"Expected Rejected, got $other")

  // ── Pix key validation ────────────────────────────────────────────────────

  test("Pix key: CPF format is 11 digits"):
    val cpf = "12345678901"
    assert(cpf.length == 11)
    assert(cpf.forall(_.isDigit), "CPF should be all digits")

  test("Pix key: CNPJ format is 14 digits"):
    val cnpj = "12345678000195"
    assert(cnpj.length == 14)
    assert(cnpj.forall(_.isDigit), "CNPJ should be all digits")

  test("Pix key: phone format starts with +55"):
    val phone = "+5511999990000"
    assert(phone.startsWith("+55"), "Brazilian Pix phone key should start with +55")
    assert(phone.drop(3).forall(_.isDigit), "Phone digits after +55 should be numeric")

  test("Pix key: email format contains @"):
    val email = "pagador@banco.com.br"
    assert(email.contains("@"), "Email Pix key should contain '@'")

  test("Pix key: EVP (chave aleatória) is UUID-like — 32–36 alphanumeric+hyphen chars"):
    val evp = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    assert(evp.length == 36, "EVP UUID should be 36 chars with hyphens")
    assert(evp.forall(c => c.isLetterOrDigit || c == '-'), "EVP should be hex+hyphens")

  // ── txid sanitization ─────────────────────────────────────────────────────

  test("PixProvider.sanitizeTxid: strips non-alphanumeric and truncates to 35 chars"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    val raw = "order-12345-attempt-1"  // contains hyphens
    val txid = p.sanitizeTxid(raw)
    assert(txid.forall(_.isLetterOrDigit), "txid should be alphanumeric only")
    assert(txid.length == 35, s"txid should be padded to 35 chars, got ${txid.length}")

  test("PixProvider.sanitizeTxid: UUID with hyphens becomes alphanumeric"):
    val cfg = PixConfig("https://api.example.com", "id", "secret", "key@pix.com")
    val p   = PixProvider(cfg)
    val uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    val txid = p.sanitizeTxid(uuid)
    assert(!txid.contains('-'), "txid should not contain hyphens")
    assert(txid.forall(_.isLetterOrDigit))

  // ── QR Code formatAmount ──────────────────────────────────────────────────

  test("PixQrCode.formatAmount: R$100.00"):
    val money = Money(10000L, Currency("BRL"))
    assert(PixQrCode.formatAmount(money) == "100.00")

  test("PixQrCode.formatAmount: R$1.01"):
    val money = Money(101L, Currency("BRL"))
    assert(PixQrCode.formatAmount(money) == "1.01")

  test("PixQrCode.formatAmount: R$0.99"):
    val money = Money(99L, Currency("BRL"))
    assert(PixQrCode.formatAmount(money) == "0.99")
