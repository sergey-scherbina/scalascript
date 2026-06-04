package scalascript.payments.pix

import scalascript.payments.money.Money

/** EMV Merchant-Presented QR Code (MPM) builder for Pix.
 *
 *  Pix uses the EMV QR Code Merchant-Presented Mode specification.
 *  Each field is encoded as TLV (tag-length-value) where tag and length
 *  are each two decimal digits, and value is a UTF-8 string.
 *
 *  Field layout (required by BACEN specification):
 *
 *    ID 00 — Payload Format Indicator:   "01"
 *    ID 26 — Merchant Account Information:
 *              00 = GUI  "br.gov.bcb.pix"
 *              01 = Pix key (CPF/CNPJ/phone/email/EVP)
 *              25 = URL for dynamic QR (/v2/cobv/{txid}) [dynamic only]
 *    ID 52 — Merchant Category Code:     "0000"
 *    ID 53 — Transaction Currency:       "986" (BRL ISO 4217)
 *    ID 54 — Transaction Amount:         decimal string (omitted for open-value)
 *    ID 58 — Country Code:               "BR"
 *    ID 59 — Merchant Name:              max 25 chars
 *    ID 60 — Merchant City:              max 15 chars
 *    ID 62 — Additional Data Field Template:
 *              05 = Reference Label (txid, max 25 chars)
 *    ID 63 — CRC-16/CCITT:               4 hex digits appended to the full string
 *
 *  See docs/specs/bank-rails.md §v1.54.3 and BACEN Manual de Padrões para Iniciação do Pix.
 */
object PixQrCode:

  /** Configuration for a static QR code (no per-transaction txid). */
  case class StaticConfig(
    pixKey:       String,          // merchant's Pix key (CPF/CNPJ/phone/email/EVP)
    merchantName: String,          // max 25 chars
    merchantCity: String,          // max 15 chars
    amount:       Option[Money] = None,  // None = open-value (recipient enters amount)
    txid:         String = "***",        // static QR: "***" means no specific txid
  )

  /** Configuration for a dynamic QR code (per-transaction, contains URL). */
  case class DynamicConfig(
    cobvUrl:      String,          // URL to /v2/cobv/{txid} on merchant's PSP
    merchantName: String,          // max 25 chars
    merchantCity: String,          // max 15 chars
    amount:       Option[Money] = None,
    txid:         String,          // 26–35 alphanumeric chars (UUID v4 style)
  )

  /** Build a static QR code payload string (without image encoding).
   *
   *  Static QR codes are reusable and can be printed as a fixed QR image.
   *  The Pix key is embedded directly in ID 26.
   */
  def buildStatic(cfg: StaticConfig): String =
    val name = cfg.merchantName.take(25)
    val city = cfg.merchantCity.take(15)
    val txid = cfg.txid.take(25)

    val merchantAccountInfo = buildMerchantAccountInfoStatic(cfg.pixKey)

    val sb = new StringBuilder
    sb.append(tlv("00", "01"))
    sb.append(tlv("26", merchantAccountInfo))
    sb.append(tlv("52", "0000"))
    sb.append(tlv("53", "986"))
    cfg.amount.foreach { m =>
      sb.append(tlv("54", formatAmount(m)))
    }
    sb.append(tlv("58", "BR"))
    sb.append(tlv("59", name))
    sb.append(tlv("60", city))
    sb.append(tlv("62", tlv("05", txid)))
    // CRC placeholder: "6304" + 4-char CRC
    val withCrcPlaceholder = sb.toString() + "6304"
    withCrcPlaceholder + crc16(withCrcPlaceholder).toHexString.toUpperCase.reverse.padTo(4, '0').reverse

  /** Build a dynamic QR code payload string.
   *
   *  Dynamic QR codes are one-time-use and encode a URL (sub-ID 25) that points
   *  to the PSP's `/v2/cobv/{txid}` endpoint.  The PSP returns the full payment
   *  details (amount, beneficiary) when the payer's app fetches the URL.
   */
  def buildDynamic(cfg: DynamicConfig): String =
    val name = cfg.merchantName.take(25)
    val city = cfg.merchantCity.take(15)
    val txid = cfg.txid.take(25)

    val merchantAccountInfo = buildMerchantAccountInfoDynamic(cfg.cobvUrl)

    val sb = new StringBuilder
    sb.append(tlv("00", "01"))
    sb.append(tlv("26", merchantAccountInfo))
    sb.append(tlv("52", "0000"))
    sb.append(tlv("53", "986"))
    cfg.amount.foreach { m =>
      sb.append(tlv("54", formatAmount(m)))
    }
    sb.append(tlv("58", "BR"))
    sb.append(tlv("59", name))
    sb.append(tlv("60", city))
    sb.append(tlv("62", tlv("05", txid)))
    val withCrcPlaceholder = sb.toString() + "6304"
    withCrcPlaceholder + crc16(withCrcPlaceholder).toHexString.toUpperCase.reverse.padTo(4, '0').reverse

  // ── EMV TLV helpers ────────────────────────────────────────────────────────

  /** Build a single TLV field: ID (2 digits) + length (2 digits) + value. */
  def tlv(id: String, value: String): String =
    val len = value.length
    s"$id${"%02d".format(len)}$value"

  /** Build ID 26 Merchant Account Information for a static QR.
   *  Sub-IDs: 00 = GUI ("br.gov.bcb.pix"), 01 = Pix key. */
  private def buildMerchantAccountInfoStatic(pixKey: String): String =
    tlv("00", "br.gov.bcb.pix") + tlv("01", pixKey)

  /** Build ID 26 Merchant Account Information for a dynamic QR.
   *  Sub-IDs: 00 = GUI ("br.gov.bcb.pix"), 25 = cobv URL. */
  private def buildMerchantAccountInfoDynamic(cobvUrl: String): String =
    tlv("00", "br.gov.bcb.pix") + tlv("25", cobvUrl)

  /** Format a Money amount as a decimal string with 2 decimal places.
   *  BRL uses 2 minor units (centavos). */
  def formatAmount(money: Money): String =
    val major = money.minorUnits / 100L
    val minor = math.abs(money.minorUnits % 100L)
    f"$major.$minor%02d"

  // ── CRC-16/CCITT ──────────────────────────────────────────────────────────

  /** Compute CRC-16/CCITT (polynomial 0x1021, init 0xFFFF) over a UTF-8 string.
   *
   *  This is the algorithm mandated by BACEN for Pix QR Code payload integrity.
   *  The input string includes the "6304" suffix (the CRC field ID + length) but
   *  not the 4-char CRC value itself.
   *
   *  Returns an integer in [0, 0xFFFF].  Callers convert to 4-hex-digit uppercase.
   */
  def crc16(data: String): Int =
    val bytes = data.getBytes("UTF-8")
    var crc   = 0xFFFF
    for byte <- bytes do
      crc ^= (byte & 0xFF) << 8
      for _ <- 0 until 8 do
        if (crc & 0x8000) != 0 then crc = ((crc << 1) ^ 0x1021) & 0xFFFF
        else crc = (crc << 1) & 0xFFFF
    crc
