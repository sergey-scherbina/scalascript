package scalascript.crypto

/** HOTP (RFC 4226, counter-based) and TOTP (RFC 6238, time-based) one-time passwords — portable, over [[Hmac]].
 *  Identical on JVM + Scala.js, no platform crypto dependency. Verified against the RFC 4226 / 6238 reference
 *  vectors. */
object Totp:

  /** The HMAC hash family an authenticator uses (TOTP allows SHA-1/256/512; HOTP is SHA-1). */
  enum Algo:
    case Sha1, Sha256, Sha512

  private def hmac(algo: Algo, key: Array[Byte], data: Array[Byte]): Array[Byte] = algo match
    case Algo.Sha1   => Hmac.sha1(key, data)
    case Algo.Sha256 => Hmac.sha256(key, data)
    case Algo.Sha512 => Hmac.sha512(key, data)

  /** HOTP value (RFC 4226) for `counter` as a zero-padded `digits`-length decimal string. */
  def hotp(key: Array[Byte], counter: Long, digits: Int = 6, algo: Algo = Algo.Sha1): String =
    require(digits >= 1 && digits <= 9, "digits must be 1..9")
    val msg = new Array[Byte](8)
    var i = 7
    var c = counter
    while i >= 0 do { msg(i) = (c & 0xff).toByte; c >>>= 8; i -= 1 }   // 8-byte big-endian counter
    val mac = hmac(algo, key, msg)
    val offset = mac(mac.length - 1) & 0x0f                            // dynamic truncation
    val bin =
      ((mac(offset) & 0x7f) << 24) | ((mac(offset + 1) & 0xff) << 16) |
      ((mac(offset + 2) & 0xff) << 8) | (mac(offset + 3) & 0xff)
    val mod = bin % pow10(digits)
    val s = mod.toString
    "0" * (digits - s.length) + s

  /** TOTP value (RFC 6238) for the given unix `timeSeconds`, `period`-second step (default 30s, epoch T0=0). */
  def totp(key: Array[Byte], timeSeconds: Long, period: Int = 30, digits: Int = 6, algo: Algo = Algo.Sha1): String =
    require(period >= 1, "period must be >= 1")
    hotp(key, Math.floorDiv(timeSeconds, period.toLong), digits, algo)

  /** Constant-time-ish validation of a candidate code at `timeSeconds`, allowing `window` steps of clock skew
   *  on each side (default ±1). */
  def validate(key: Array[Byte], code: String, timeSeconds: Long, period: Int = 30, digits: Int = 6,
               algo: Algo = Algo.Sha1, window: Int = 1): Boolean =
    val step = Math.floorDiv(timeSeconds, period.toLong)
    var w = -window
    var ok = false
    while w <= window do
      if totp(key, (step + w) * period, period, digits, algo) == code then ok = true
      w += 1
    ok

  private def pow10(n: Int): Int =
    var r = 1; var i = 0
    while i < n do { r *= 10; i += 1 }
    r
