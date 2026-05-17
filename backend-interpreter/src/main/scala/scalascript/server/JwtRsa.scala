package scalascript.server

import java.security.{KeyFactory, MessageDigest, Signature, SecureRandom, PrivateKey, PublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64

/** RS256-signed JWTs (asymmetric, RFC 7518 §3.3).
 *
 *  Use this when verifiers are distinct from signers — multiple
 *  microservices verifying the same token without sharing a secret,
 *  or third-party consumers (mobile clients) that should only hold a
 *  public key.  When everything fits in one process, prefer the HS256
 *  surface in [[Jwt]] — half the code and the same security model.
 *
 *  Key resolution:
 *    - `SSC_JWT_PRIVATE_KEY` env var — PKCS#8 PEM-encoded RSA private
 *      key (used by `sign`).
 *    - `SSC_JWT_PUBLIC_KEY` env var — X.509 SubjectPublicKeyInfo PEM
 *      (used by `verify`).
 *
 *  Generate a fresh pair with:
 *    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out priv.pem
 *    openssl rsa -in priv.pem -pubout -out pub.pem
 */
object JwtRsa:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  private val Header    = """{"alg":"RS256","typ":"JWT"}"""
  private val HeaderB64 = b64Enc.encodeToString(Header.getBytes("UTF-8"))

  private lazy val privateKey: Option[PrivateKey] =
    sys.env.get("SSC_JWT_PRIVATE_KEY").filter(_.nonEmpty).map(parsePrivate)

  private lazy val publicKey: Option[PublicKey] =
    sys.env.get("SSC_JWT_PUBLIC_KEY").filter(_.nonEmpty).map(parsePublic)

  /** Strip PEM armor and decode the base64 body to raw DER bytes. */
  private def pemBytes(pem: String): Array[Byte] =
    val cleaned = pem
      .replaceAll("-----BEGIN [^-]+-----", "")
      .replaceAll("-----END [^-]+-----", "")
      .replaceAll("\\s+", "")
    Base64.getDecoder.decode(cleaned)

  private def parsePrivate(pem: String): PrivateKey =
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pemBytes(pem)))

  private def parsePublic(pem: String): PublicKey =
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pemBytes(pem)))

  /** Encode a `Map[String, String]` payload as a compact JSON object. */
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

  /** Sign claims with the RSA private key read from
   *  `SSC_JWT_PRIVATE_KEY`.  Throws when the env var is unset / not
   *  PEM — calling code should validate config at startup. */
  def sign(claims: Map[String, String]): String =
    val pk         = privateKey.getOrElse(throw RuntimeException(
      "SSC_JWT_PRIVATE_KEY is not set (expected PKCS#8 RSA PEM)"))
    val payloadB64 = b64Enc.encodeToString(jsonOf(claims).getBytes("UTF-8"))
    val signingIn  = (HeaderB64 + "." + payloadB64).getBytes("UTF-8")
    val sig        = Signature.getInstance("SHA256withRSA")
    sig.initSign(pk)
    sig.update(signingIn)
    val sigB64 = b64Enc.encodeToString(sig.sign())
    s"$HeaderB64.$payloadB64.$sigB64"

  /** Verify a JWT against the RSA public key from `SSC_JWT_PUBLIC_KEY`.
   *  Returns `None` for malformed tokens, signature mismatches, or
   *  expired tokens (present `exp` claim in the past). */
  def verify(token: String): Option[Map[String, String]] =
    val pub = publicKey match
      case Some(k) => k
      case None    => return None
    val parts = token.split('.')
    if parts.length != 3 then return None
    val Array(h, p, s) = parts
    try
      val headerJson = String(b64Dec.decode(h), "UTF-8")
      if !headerJson.contains("\"alg\":\"RS256\"") then return None
      val sig = Signature.getInstance("SHA256withRSA")
      sig.initVerify(pub)
      sig.update((h + "." + p).getBytes("UTF-8"))
      if !sig.verify(b64Dec.decode(s)) then return None
      parseJsonStringMap(String(b64Dec.decode(p), "UTF-8")) match
        case None         => None
        case Some(claims) =>
          claims.get("exp") match
            case Some(expStr) =>
              val now = java.lang.System.currentTimeMillis() / 1000L
              try
                if expStr.toLong < now then None else Some(claims)
              catch case _: Throwable => None
            case None => Some(claims)
    catch case _: Throwable => None

  private def parseJsonStringMap(s: String): Option[Map[String, String]] =
    val trimmed = s.trim
    if !trimmed.startsWith("{") || !trimmed.endsWith("}") then return None
    val inner = trimmed.substring(1, trimmed.length - 1).trim
    if inner.isEmpty then return Some(Map.empty)
    try
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
      var i = 0
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
              case _    => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
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
