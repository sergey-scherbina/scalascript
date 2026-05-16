package scalascript.server

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.security.MessageDigest

/** HMAC-signed cookie sessions for the interpreter's HTTP runtime.
 *
 *  Cookie value format:
 *    `<b64url(json(payload))>.<b64url(hmac_sha256(payload_b64))>`
 *
 *  Payload is a `Map[String, String]` serialised as a tiny JSON object
 *  (we don't need full JSON power here — keys are identifiers, values
 *  are short strings).  Both halves use the URL-safe base64 alphabet so
 *  the cookie value is itself safe to drop into a `Set-Cookie` header
 *  without further escaping.
 *
 *  Secret resolution:
 *    1. Environment variable `SSC_SESSION_SECRET` if set.
 *    2. Otherwise a process-local random secret, generated once and
 *       reused for the process lifetime.  Sessions don't survive a
 *       restart in that mode — fine for dev, surfaced via stderr.
 */
object SessionCookie:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  /** Lazily resolved per-process secret. */
  private lazy val secret: Array[Byte] = sys.env.get("SSC_SESSION_SECRET") match
    case Some(s) if s.nonEmpty => s.getBytes("UTF-8")
    case _ =>
      val bytes = new Array[Byte](32)
      java.security.SecureRandom().nextBytes(bytes)
      System.err.println(
        "[ssc] SSC_SESSION_SECRET not set; using a process-local random key. " +
        "Sessions will not survive a server restart."
      )
      bytes

  private def hmacSha256(payload: Array[Byte], key: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(payload)

  /** Constant-time equality so signature comparison doesn't leak timing. */
  private def constEq(a: Array[Byte], b: Array[Byte]): Boolean =
    MessageDigest.isEqual(a, b)

  /** Encode a `Map[String, String]` as a compact JSON object string. */
  private def jsonOf(m: Map[String, String]): String =
    def esc(s: String): String =
      val sb = StringBuilder().append('"')
      var i = 0
      while i < s.length do
        s.charAt(i) match
          case '"'  => sb.append("\\\"")
          case '\\' => sb.append("\\\\")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
          case c    => sb.append(c)
        i += 1
      sb.append('"').toString
    m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")

  /** Parse a JSON object string `{"k":"v", ...}` whose values are all
   *  strings, into a `Map[String, String]`.  Returns `None` on any
   *  structural problem — we never throw on a malformed cookie value. */
  private def parseJsonStringMap(s: String): Option[Map[String, String]] =
    val trimmed = s.trim
    if !trimmed.startsWith("{") || !trimmed.endsWith("}") then return None
    val inner = trimmed.substring(1, trimmed.length - 1).trim
    if inner.isEmpty then return Some(Map.empty)
    try
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
      var i  = 0
      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
      def readStr(): String =
        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
        i += 1
        val sb = StringBuilder()
        while i < inner.length && inner.charAt(i) != '"' do
          val c = inner.charAt(i)
          if c == '\\' && i + 1 < inner.length then
            inner.charAt(i + 1) match
              case '"'  => sb.append('"');  i += 2
              case '\\' => sb.append('\\'); i += 2
              case 'n'  => sb.append('\n'); i += 2
              case 'r'  => sb.append('\r'); i += 2
              case 't'  => sb.append('\t'); i += 2
              case 'u' if i + 5 < inner.length =>
                val hex = inner.substring(i + 2, i + 6)
                sb.append(Integer.parseInt(hex, 16).toChar); i += 6
              case _    => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        if i >= inner.length then throw RuntimeException("unterminated string")
        i += 1
        sb.toString
      while i < inner.length do
        skipWs()
        val k = readStr()
        skipWs()
        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
        i += 1
        skipWs()
        val v = readStr()
        out(k) = v
        skipWs()
        if i < inner.length then
          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
          i += 1
      Some(out.toMap)
    catch case _: Throwable => None

  /** Sign and encode a session map into a cookie value. */
  def pack(payload: Map[String, String]): String =
    val jsonBytes = jsonOf(payload).getBytes("UTF-8")
    val body      = b64Enc.encodeToString(jsonBytes)
    val sig       = b64Enc.encodeToString(hmacSha256(body.getBytes("UTF-8"), secret))
    s"$body.$sig"

  /** Verify and decode a cookie value back into a session map.
   *  Returns `None` on any tampering / malformed input — never throws. */
  def unpack(cookieValue: String): Option[Map[String, String]] =
    val idx = cookieValue.indexOf('.')
    if idx <= 0 || idx == cookieValue.length - 1 then None
    else
      val body   = cookieValue.substring(0, idx)
      val sigStr = cookieValue.substring(idx + 1)
      try
        val expected = b64Enc.encodeToString(hmacSha256(body.getBytes("UTF-8"), secret))
        if !constEq(expected.getBytes("UTF-8"), sigStr.getBytes("UTF-8")) then None
        else
          val json = String(b64Dec.decode(body), "UTF-8")
          parseJsonStringMap(json)
      catch case _: Throwable => None

  /** Extract a `session=<value>` from a raw `Cookie:` header value. */
  def fromHeader(cookieHeader: String): Option[Map[String, String]] =
    val sessionPair = cookieHeader.split(';').iterator
      .map(_.trim)
      .find(_.startsWith("session="))
    sessionPair.flatMap(pair => unpack(pair.substring("session=".length)))

  /** Build a `Set-Cookie` header value for a session payload.
   *  Empty payload → cookie cleared (Max-Age=0). */
  def toSetCookie(payload: Map[String, String], secureFlag: Boolean): String =
    val base = "Path=/; HttpOnly; SameSite=Lax" + (if secureFlag then "; Secure" else "")
    if payload.isEmpty then s"session=; $base; Max-Age=0"
    else s"session=${pack(payload)}; $base"
