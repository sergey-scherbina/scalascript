package scalascript.server

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.security.MessageDigest

/** HS256-signed JWTs for ScalaScript's HTTP runtime.
 *
 *  Wire format (RFC 7519):
 *    `<b64url(header)>.<b64url(payload)>.<b64url(hmac_sha256(header_b64.payload_b64))>`
 *  Header is fixed to `{"alg":"HS256","typ":"JWT"}`.  Payload is a
 *  `Map[String, String]` serialised as JSON object — keeps the API
 *  symmetrical with [[SessionCookie]] without dragging in a full JSON
 *  encoder for the small surface a typical app needs (`sub`, `exp`,
 *  `role`, …).  Callers who want richer claims can stash a hand-built
 *  JSON string under a single key.
 *
 *  Secret resolution:
 *    1. `SSC_JWT_SECRET` env var (preferred — separate from session secret).
 *    2. `SSC_SESSION_SECRET` env var as a fallback so a tiny deployment
 *       only needs one secret in its env.
 *    3. Per-process random key with a one-line stderr warning.
 *
 *  `verify` rejects tampered signatures (constant-time compare) and
 *  tokens whose `exp` claim is in the past.  Other claim semantics
 *  (`nbf`, `iss`, `aud`) are left to the caller. */
object Jwt:
  private val _log = org.slf4j.LoggerFactory.getLogger("scalascript.server")
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  private val Header    = """{"alg":"HS256","typ":"JWT"}"""
  private val HeaderB64 = b64Enc.encodeToString(Header.getBytes("UTF-8"))

  /** Lazily resolved per-process secret. */
  private lazy val secret: Array[Byte] =
    sys.env.get("SSC_JWT_SECRET").filter(_.nonEmpty)
      .orElse(sys.env.get("SSC_SESSION_SECRET").filter(_.nonEmpty)) match
      case Some(s) => s.getBytes("UTF-8")
      case None    =>
        val bytes = new Array[Byte](32)
        java.security.SecureRandom().nextBytes(bytes)
        _log.warn(
          "[ssc] SSC_JWT_SECRET / SSC_SESSION_SECRET not set; JWTs signed " +
          "with a process-local random key.  Tokens will not survive a restart."
        )
        bytes

  private def hmacSha256(payload: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(payload)

  /** JSON-encode a `Map[String, String]` claim set. */
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

  /** Parse a JSON object whose values are all strings.  Returns `None`
   *  on any structural problem — `verify` must never throw. */
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
                sb.append(Integer.parseInt(inner.substring(i + 2, i + 6), 16).toChar)
                i += 6
              case _ => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        if i >= inner.length then throw RuntimeException("unterminated string")
        i += 1
        sb.toString
      while i < inner.length do
        skipWs(); val k = readStr()
        skipWs()
        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
        i += 1
        skipWs(); val v = readStr()
        out(k) = v
        skipWs()
        if i < inner.length then
          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
          i += 1
      Some(out.toMap)
    catch case _: Throwable => None

  /** Sign a claim map into a compact JWT.  The caller decides whether
   *  to set `exp` etc.; `sign` does not touch the map. */
  def sign(claims: Map[String, String]): String =
    val payloadB64  = b64Enc.encodeToString(jsonOf(claims).getBytes("UTF-8"))
    val signingIn   = (HeaderB64 + "." + payloadB64).getBytes("UTF-8")
    val sigB64      = b64Enc.encodeToString(hmacSha256(signingIn))
    s"$HeaderB64.$payloadB64.$sigB64"

  /** Verify a JWT and return its claims.  Returns `None` for any of:
   *    - malformed token,
   *    - signature mismatch,
   *    - unsupported `alg`,
   *    - present `exp` claim that's not a non-negative integer or that
   *      lies in the past (Unix seconds, UTC). */
  def verify(token: String): Option[Map[String, String]] =
    val parts = token.split('.')
    if parts.length != 3 then return None
    val Array(h, p, s) = parts
    try
      val expected = b64Enc.encodeToString(hmacSha256((h + "." + p).getBytes("UTF-8")))
      if !MessageDigest.isEqual(expected.getBytes("UTF-8"), s.getBytes("UTF-8")) then return None
      val headerJson = String(b64Dec.decode(h), "UTF-8")
      if !headerJson.contains("\"alg\":\"HS256\"") then return None
      val claims = parseJsonStringMap(String(b64Dec.decode(p), "UTF-8")) match
        case Some(c) => c
        case None    => return None
      claims.get("exp") match
        case Some(expStr) =>
          val now = java.lang.System.currentTimeMillis() / 1000L
          try
            val exp = expStr.toLong
            if exp < now then None else Some(claims)
          catch case _: Throwable => None
        case None => Some(claims)
    catch case _: Throwable => None

  /** Extract a bearer token from an `Authorization: Bearer <token>` header. */
  def fromAuthHeader(authHeader: String): Option[String] =
    val trimmed = Option(authHeader).map(_.trim).getOrElse("")
    if trimmed.regionMatches(true, 0, "Bearer ", 0, 7) then Some(trimmed.substring(7).trim)
    else None
