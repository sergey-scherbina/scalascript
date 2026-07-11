package ssc.plugin.crypto

private[crypto] object NativeTotp:
  enum Algorithm:
    case Sha1, Sha256, Sha512

  def algorithm(value: String): Algorithm = value.toUpperCase match
    case "SHA1" | "SHA-1" => Algorithm.Sha1
    case "SHA256" | "SHA-256" => Algorithm.Sha256
    case "SHA512" | "SHA-512" => Algorithm.Sha512
    case other => throw new RuntimeException(
      s"unknown OTP algo: $other (use SHA1/SHA256/SHA512)")

  private def hmac(algorithm: Algorithm, key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val name = algorithm match
      case Algorithm.Sha1 => "HmacSHA1"
      case Algorithm.Sha256 => "HmacSHA256"
      case Algorithm.Sha512 => "HmacSHA512"
    val mac = javax.crypto.Mac.getInstance(name)
    mac.init(javax.crypto.spec.SecretKeySpec(key, name))
    mac.doFinal(data)

  def hotp(key: Array[Byte], counter: Long, digits: Int, algorithm: Algorithm): String =
    require(digits >= 1 && digits <= 9, "digits must be 1..9")
    val message = new Array[Byte](8)
    var remaining = counter
    var index = 7
    while index >= 0 do
      message(index) = (remaining & 0xff).toByte
      remaining >>>= 8
      index -= 1
    val digest = hmac(algorithm, key, message)
    val offset = digest.last & 0x0f
    val binary =
      ((digest(offset) & 0x7f) << 24) |
        ((digest(offset + 1) & 0xff) << 16) |
        ((digest(offset + 2) & 0xff) << 8) |
        (digest(offset + 3) & 0xff)
    val code = binary % power10(digits)
    val raw = code.toString
    "0" * (digits - raw.length) + raw

  def totp(
      key: Array[Byte],
      timeSeconds: Long,
      period: Int,
      digits: Int,
      algorithm: Algorithm): String =
    require(period >= 1, "period must be >= 1")
    hotp(key, Math.floorDiv(timeSeconds, period.toLong), digits, algorithm)

  def validate(
      key: Array[Byte],
      code: String,
      timeSeconds: Long,
      period: Int,
      digits: Int,
      algorithm: Algorithm,
      window: Int): Boolean =
    val step = Math.floorDiv(timeSeconds, period.toLong)
    var offset = -window
    var valid = false
    while offset <= window do
      if totp(key, (step + offset) * period, period, digits, algorithm) == code then
        valid = true
      offset += 1
    valid

  private def power10(exponent: Int): Int =
    var result = 1
    var index = 0
    while index < exponent do
      result *= 10
      index += 1
    result
