package scalascript.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** WebAuthn / FIDO2 passkey building blocks (RFC 8809 / W3C Web
 *  Authentication Level 2).
 *
 *  This file covers the parts that don't touch CBOR or ECDSA yet:
 *
 *    - [[challenge]]      : mint a fresh 32-byte challenge (base64url)
 *                            and stash it with a TTL so the matching
 *                            attestation / assertion call can consume it.
 *    - [[consumeChallenge]] : look up + remove a pending challenge.
 *                              Returns `false` if it's missing or expired.
 *    - [[CredentialStore]] : in-memory store of `(userId → List[Credential])`.
 *                            Each credential carries a base64url credentialId
 *                            and a base64url-encoded COSE public key plus a
 *                            monotonic signCount.
 *
 *  The actual attestation / assertion verification lives in a follow-up
 *  commit — needs a small CBOR reader and ECDSA P-256 verification.
 *  Splitting the challenge / store layer out lets the example wire up
 *  the JS-side `navigator.credentials.create / get` glue first and gives
 *  the verifier something concrete to target. */
object WebAuthn:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val rng    = SecureRandom()

  // ── Pending challenges ────────────────────────────────────────────
  // Keyed by challenge string.  Carrying the userId lets a verifier
  // confirm the challenge was issued for the right account.  A fixed
  // 5-minute TTL — long enough for the user to interact with their
  // authenticator, short enough that a leaked challenge expires
  // before it's useful.
  private case class Pending(userId: String, issuedMs: Long)
  private val pending = ConcurrentHashMap[String, Pending]()
  private val TtlMs   = 5L * 60L * 1000L

  /** Mint a fresh challenge for `userId` and return it as a base64url
   *  string the client can pass straight into the WebAuthn API. */
  def challenge(userId: String): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    val s = b64Enc.encodeToString(bytes)
    pending.put(s, Pending(userId, java.lang.System.currentTimeMillis()))
    maybeSweep()
    s

  /** Consume a previously-issued challenge.  Returns `Some(userId)` if
   *  the challenge was outstanding and not expired; `None` otherwise.
   *  The challenge is removed from the store in both cases so it can't
   *  be replayed. */
  def consumeChallenge(s: String): Option[String] =
    Option(pending.remove(s)).filter { p =>
      java.lang.System.currentTimeMillis() - p.issuedMs <= TtlMs
    }.map(_.userId)

  private val sweepCounter = java.util.concurrent.atomic.AtomicLong(0L)
  private def maybeSweep(): Unit =
    if (sweepCounter.incrementAndGet() & 0xFF) == 0L then
      val cutoff = java.lang.System.currentTimeMillis() - TtlMs
      val it = pending.entrySet().iterator()
      while it.hasNext do
        val e = it.next()
        if e.getValue.issuedMs < cutoff then it.remove()

  // ── Credential store ──────────────────────────────────────────────
  // Process-local Map[userId → List[Credential]].  Per credential we
  // hold the credentialId (base64url, opaque to us), the COSE public
  // key (base64url-encoded raw CBOR bytes — verifier will decode), and
  // the latest signCount we've seen.  Authenticators bump signCount on
  // every assertion; a request whose count is ≤ the stored value is
  // either a replay or a cloned authenticator and must be rejected.
  final case class Credential(
      credentialId: String,
      publicKey:    String,
      signCount:    Long,
  )

  private val store = ConcurrentHashMap[String, java.util.List[Credential]]()

  // ── Optional disk persistence ──────────────────────────────────────
  // Without this, `store` is wiped by every server restart (deploy, crash,
  // reboot) — every enrolled passkey is lost and every device falls back to
  // whatever bootstrap mechanism guards enrolment. `configureStore` is a no-op
  // (in-memory only, unchanged default) until a caller opts in with a path.
  // Format: one line per credential, tab-separated
  // `userId\tcredentialId\tpublicKey\tsignCount` — no JSON dependency, mirrors
  // the plain-text-store convention already used elsewhere in this codebase
  // (e.g. busi's own TSV identity/session files).
  private val storeLock = Object()
  private var storePath: Option[java.nio.file.Path] = None

  /** Opt into disk persistence at `path`, loading any existing entries from it
   *  immediately (e.g. on server startup, before any request is served). Safe
   *  to call more than once (e.g. from a test); each call reloads from `path`. */
  def configureStore(path: String): Unit =
    val p = java.nio.file.Paths.get(path)
    storeLock.synchronized {
      storePath = Some(p)
      store.clear()
      if java.nio.file.Files.exists(p) then
        val lines = java.nio.file.Files.readAllLines(p)
        val it = lines.iterator()
        while it.hasNext do
          val line = it.next()
          if line.nonEmpty then
            line.split("\t", -1) match
              case Array(userId, credentialId, publicKey, signCountStr) =>
                val list = store.computeIfAbsent(userId, _ => java.util.ArrayList[Credential]())
                list.add(Credential(credentialId, publicKey, signCountStr.toLongOption.getOrElse(0L)))
              case _ => () // skip a malformed/partial line rather than fail startup
    }

  /** Rewrite the whole store file from the in-memory map (small store, simple
   *  full-rewrite; tmp-file + atomic move avoids a torn write on crash). No-op
   *  if `configureStore` was never called. Must be invoked under `storeLock`. */
  private def persist(): Unit =
    storePath.foreach { p =>
      val tmp = p.resolveSibling(p.getFileName.toString + ".tmp")
      val sb = StringBuilder()
      val entries = scala.jdk.CollectionConverters.MapHasAsScala(store).asScala
      entries.foreach { (userId, list) =>
        list.synchronized {
          val it = list.iterator()
          while it.hasNext do
            val c = it.next()
            sb.append(userId).append('\t').append(c.credentialId).append('\t')
              .append(c.publicKey).append('\t').append(c.signCount).append('\n')
        }
      }
      java.nio.file.Files.createDirectories(p.getParent)
      java.nio.file.Files.write(tmp, sb.toString.getBytes("UTF-8"))
      java.nio.file.Files.move(tmp, p,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

  def storePut(userId: String, c: Credential): Unit =
    val list = store.computeIfAbsent(userId, _ => java.util.ArrayList[Credential]())
    list.synchronized {
      // Replace any prior entry with the same credentialId so a re-
      // enrolment overwrites instead of duplicating.
      val it = list.iterator()
      while it.hasNext do
        if it.next().credentialId == c.credentialId then it.remove()
      list.add(c)
    }
    storeLock.synchronized { persist() }

  def storeGet(userId: String): List[Credential] =
    Option(store.get(userId))
      .map(l => l.synchronized(java.util.ArrayList(l)))
      .map(scala.jdk.CollectionConverters.ListHasAsScala(_).asScala.toList)
      .getOrElse(Nil)

  def storeFind(userId: String, credentialId: String): Option[Credential] =
    storeGet(userId).find(_.credentialId == credentialId)

  /** Update signCount after a successful assertion.  Returns true if
   *  the new count is strictly greater than the stored one (the
   *  authenticator counter advanced as expected); false on cloned /
   *  replayed signatures. */
  def storeUpdateSignCount(userId: String, credentialId: String, newCount: Long): Boolean =
    val ok = Option(store.get(userId)) match
      case None       => false
      case Some(list) =>
        list.synchronized {
          var ok = false
          val n = list.size
          var i = 0
          while i < n do
            val c = list.get(i)
            if c.credentialId == credentialId then
              if newCount > c.signCount then
                list.set(i, c.copy(signCount = newCount))
                ok = true
              i = n
            else i += 1
          ok
        }
    if ok then storeLock.synchronized { persist() }
    ok

  /** Wipe everything — tests. Also clears any configured store path (the next
   *  `configureStore` call starts fresh rather than reloading stale data). */
  def reset(): Unit =
    pending.clear()
    store.clear()
    storeLock.synchronized { storePath = None }

  // ── Registration verification ─────────────────────────────────────
  // Parses a `navigator.credentials.create()` response (clientDataJSON
  // + attestationObject, both base64url-encoded) and extracts the
  // credentialId + COSE public key that we need to remember.
  //
  // Scope deliberately narrow:
  //   - `none` attestation format only (the default Apple / 1Password
  //     / iOS passkeys use; `packed` and `fido-u2f` mean the
  //     authenticator vouches for its provenance, which is useful for
  //     enterprise but optional for typical sites).
  //   - No attestation-signature verification.  We trust on first use:
  //     the assertion flow's signature check binds future logins to
  //     the public key we extract here, so a malicious enrolment
  //     can't impersonate someone else's account.
  //
  // Returns Some((credentialIdB64, publicKeyB64, signCount, userId))
  // on success — `userId` comes from the consumed challenge so the
  // caller doesn't have to thread it through separately.
  final case class Registration(
      userId:       String,
      credentialId: String,
      publicKey:    String,
      signCount:    Long,
  )

  @annotation.nowarn("msg=Non local returns")
  def verifyRegistration(
      clientDataJSONB64:   String,
      attestationObjectB64: String,
      expectedOrigin:      String,
  ): Option[Registration] =
    try
      val b64 = Base64.getUrlDecoder
      val cd  = String(b64.decode(clientDataJSONB64), "UTF-8")
      // Parse the small clientDataJSON ourselves — only need 3 fields.
      val challenge = jsonStringField(cd, "challenge").getOrElse(return None)
      val origin    = jsonStringField(cd, "origin").getOrElse(return None)
      val ctype     = jsonStringField(cd, "type").getOrElse(return None)
      if ctype != "webauthn.create" then return None
      if origin != expectedOrigin then return None
      val userId = consumeChallenge(challenge).getOrElse(return None)
      // attestationObject is a CBOR map { fmt, attStmt, authData }.
      val attObj = Cbor.read(b64.decode(attestationObjectB64))
      val attMap = attObj match { case Cbor.MapV(m) => m; case _ => return None }
      val fmt = attMap.get(Cbor.StrV("fmt")) match
        case Some(Cbor.StrV(s)) => s
        case _                  => return None
      if fmt != "none" then return None  // future stages handle other fmts
      val authData = attMap.get(Cbor.StrV("authData")) match
        case Some(Cbor.BytesV(b)) => b
        case _                    => return None
      val parsed = parseAuthData(authData).getOrElse(return None)
      Some(Registration(userId, parsed.credentialIdB64, parsed.publicKeyB64, parsed.signCount))
    catch case _: Throwable => None

  /** Layout of authData (raw bytes from authenticator):
   *    [0..32)   rpIdHash      SHA-256(rpId)
   *    [32]      flags         UP / UV / AT / ED bits
   *    [33..37)  signCount     uint32 big-endian
   *    if flags & 0x40 (AT, attested credential data present):
   *      [37..53)         AAGUID                16 bytes
   *      [53..55)         credentialIdLength    uint16 big-endian
   *      [55..55+L)       credentialId          L bytes
   *      [55+L..end)      COSE public key       remaining CBOR
   */
  private final case class ParsedAuthData(
      credentialIdB64: String,
      publicKeyB64:    String,
      signCount:       Long,
  )

  private def parseAuthData(b: Array[Byte]): Option[ParsedAuthData] =
    if b.length < 37 then None
    else
      val flags     = b(32) & 0xff
      val signCount =
        ((b(33) & 0xffL) << 24) |
        ((b(34) & 0xffL) << 16) |
        ((b(35) & 0xffL) <<  8) |
         (b(36) & 0xffL)
      val attestedCredFlag = (flags & 0x40) != 0
      if !attestedCredFlag then None
      else if b.length < 55 then None
      else
        val credIdLen =
          ((b(53) & 0xff) << 8) | (b(54) & 0xff)
        if b.length < 55 + credIdLen then None
        else
          val credId  = b.slice(55, 55 + credIdLen)
          val pubKey  = b.slice(55 + credIdLen, b.length)
          val enc     = Base64.getUrlEncoder.withoutPadding
          Some(ParsedAuthData(
            enc.encodeToString(credId),
            enc.encodeToString(pubKey),
            signCount,
          ))

  // ── Authentication verification ───────────────────────────────────
  // Counterpart to verifyRegistration.  Takes the browser's
  // `navigator.credentials.get()` response and confirms the assertion
  // was actually signed by the stored credential's private key.
  //
  // Inputs (all base64url):
  //   clientDataJSONb64    : the JSON the client signed over
  //   authenticatorDataB64 : binary authData (rpIdHash + flags + signCount)
  //   signatureB64         : ASN.1 DER ECDSA signature
  //   credentialIdB64      : which credential the client claims to be
  //
  // Returns Some((userId, newSignCount)) on success — userId from the
  // consumed challenge, signCount already validated against the stored
  // monotonic counter and persisted via `storeUpdateSignCount`.
  final case class Assertion(userId: String, signCount: Long)

  @annotation.nowarn("msg=Non local returns")
  def verifyAssertion(
      clientDataJSONb64:    String,
      authenticatorDataB64: String,
      signatureB64:         String,
      credentialIdB64:      String,
      expectedOrigin:       String,
  ): Option[Assertion] =
    try
      val b64 = Base64.getUrlDecoder
      val cd  = String(b64.decode(clientDataJSONb64), "UTF-8")
      val challenge = jsonStringField(cd, "challenge").getOrElse(return None)
      val origin    = jsonStringField(cd, "origin").getOrElse(return None)
      val ctype     = jsonStringField(cd, "type").getOrElse(return None)
      if ctype != "webauthn.get" then return None
      if origin != expectedOrigin then return None
      val userId = consumeChallenge(challenge).getOrElse(return None)
      val cred   = storeFind(userId, credentialIdB64).getOrElse(return None)

      val authData = b64.decode(authenticatorDataB64)
      if authData.length < 37 then return None
      val newCount =
        ((authData(33) & 0xffL) << 24) |
        ((authData(34) & 0xffL) << 16) |
        ((authData(35) & 0xffL) <<  8) |
         (authData(36) & 0xffL)

      // Signed payload = authenticatorData || SHA-256(clientDataJSON).
      val sha     = java.security.MessageDigest.getInstance("SHA-256")
      val cdHash  = sha.digest(b64.decode(clientDataJSONb64))
      val signed  = authData ++ cdHash

      val pubKey  = decodeCosePublicKey(b64.decode(cred.publicKey)).getOrElse(return None)
      val sig     = java.security.Signature.getInstance("SHA256withECDSA")
      sig.initVerify(pubKey)
      sig.update(signed)
      if !sig.verify(b64.decode(signatureB64)) then return None

      // Monotonic signCount — protects against cloned authenticators
      // and replayed assertions.  Authenticators that don't implement
      // counters always send 0; permit that as a no-op (don't bump).
      if newCount > 0 then
        if !storeUpdateSignCount(userId, credentialIdB64, newCount) then return None
      Some(Assertion(userId, newCount))
    catch case _: Throwable => None

  /** Decode a COSE EC2 public key (RFC 8152 §13.1) into a JCA
   *  ECPublicKey.  The key is a CBOR map with negative-int keys:
   *      1 (kty) = 2 (EC2)
   *      3 (alg) = -7 (ES256)
   *     -1 (crv) = 1 (P-256)
   *     -2 (x)   = 32-byte big-endian X coordinate
   *     -3 (y)   = 32-byte big-endian Y coordinate */
  @annotation.nowarn("msg=Non local returns")
  private def decodeCosePublicKey(bytes: Array[Byte]): Option[java.security.PublicKey] =
    try
      val m = Cbor.read(bytes) match
        case Cbor.MapV(m) => m
        case _            => return None
      def getInt(k: Long): Option[Long] = m.collectFirst {
        case (Cbor.UIntV(v), Cbor.UIntV(x))                 if v == k => x
        case (Cbor.NegV(v),  Cbor.UIntV(x))                 if v == k => x
        case (Cbor.UIntV(v), Cbor.NegV(x))                  if v == k => x
      }
      def getBytes(k: Long): Option[Array[Byte]] = m.collectFirst {
        case (Cbor.UIntV(v), Cbor.BytesV(b)) if v == k => b
        case (Cbor.NegV(v),  Cbor.BytesV(b)) if v == k => b
      }
      val kty = getInt(1).getOrElse(return None)
      val alg = getInt(3).getOrElse(return None)
      val crv = getInt(-1).getOrElse(return None)
      if kty != 2 || alg != -7L || crv != 1 then return None
      val x = getBytes(-2).getOrElse(return None)
      val y = getBytes(-3).getOrElse(return None)
      val params = java.security.AlgorithmParameters.getInstance("EC")
      params.init(java.security.spec.ECGenParameterSpec("secp256r1"))
      val ecSpec = params.getParameterSpec(classOf[java.security.spec.ECParameterSpec])
      val point  = java.security.spec.ECPoint(
        java.math.BigInteger(1, x),
        java.math.BigInteger(1, y),
      )
      val spec = java.security.spec.ECPublicKeySpec(point, ecSpec)
      Some(java.security.KeyFactory.getInstance("EC").generatePublic(spec))
    catch case _: Throwable => None

  /** Extract a top-level string field from a flat JSON object —
   *  enough for clientDataJSON which is always `{type, challenge,
   *  origin, ...}` with no nested objects we care about. */
  private def jsonStringField(json: String, key: String): Option[String] =
    val needle = "\"" + key + "\""
    val ki = json.indexOf(needle)
    if ki < 0 then None
    else
      var i = ki + needle.length
      while i < json.length && json.charAt(i).isWhitespace do i += 1
      if i >= json.length || json.charAt(i) != ':' then None
      else
        i += 1
        while i < json.length && json.charAt(i).isWhitespace do i += 1
        if i >= json.length || json.charAt(i) != '"' then None
        else
          i += 1
          val sb = StringBuilder()
          while i < json.length && json.charAt(i) != '"' do
            val c = json.charAt(i)
            if c == '\\' && i + 1 < json.length then
              json.charAt(i + 1) match
                case '"'  => sb.append('"');  i += 2
                case '\\' => sb.append('\\'); i += 2
                case 'n'  => sb.append('\n'); i += 2
                case 'r'  => sb.append('\r'); i += 2
                case 't'  => sb.append('\t'); i += 2
                case '/'  => sb.append('/');  i += 2
                case _    => sb.append(c); i += 1
            else { sb.append(c); i += 1 }
          Some(sb.toString)

// ── Minimal CBOR reader ────────────────────────────────────────────
// Covers the subset attestationObject + COSE keys use: unsigned ints
// (major 0), negative ints (major 1), byte strings (major 2), text
// strings (major 3), arrays (major 4), maps (major 5), simple values
// (major 7).  No tags, no indefinite lengths, no big-int — these
// aren't generated by real-world authenticators.

private[server] object Cbor:
  sealed trait Value
  case class UIntV(v: Long)              extends Value
  case class NegV(v: Long)               extends Value
  case class BytesV(b: Array[Byte])      extends Value
  case class StrV(s: String)             extends Value
  case class ArrV(items: List[Value])    extends Value
  case class MapV(m: Map[Value, Value])  extends Value
  case object NullV                      extends Value
  case object UndefV                     extends Value
  case class BoolV(b: Boolean)           extends Value
  case class FloatV(d: Double)           extends Value

  def read(bytes: Array[Byte]): Value =
    val (v, _) = readAt(bytes, 0)
    v

  private def readAt(b: Array[Byte], start: Int): (Value, Int) =
    val ib   = b(start) & 0xff
    val mt   = ib >>> 5
    val info = ib & 0x1f
    val (v, after) = readArg(b, start + 1, info)
    mt match
      case 0 => (UIntV(v), after)
      case 1 => (NegV(-1L - v), after)
      case 2 =>
        val len = v.toInt
        (BytesV(b.slice(after, after + len)), after + len)
      case 3 =>
        val len = v.toInt
        (StrV(String(b.slice(after, after + len), "UTF-8")), after + len)
      case 4 =>
        var i  = after
        val n  = v.toInt
        val xs = scala.collection.mutable.ArrayBuffer.empty[Value]
        var k  = 0
        while k < n do { val (it, ni) = readAt(b, i); xs += it; i = ni; k += 1 }
        (ArrV(xs.toList), i)
      case 5 =>
        var i  = after
        val n  = v.toInt
        val xs = scala.collection.mutable.LinkedHashMap.empty[Value, Value]
        var k  = 0
        while k < n do
          val (kk, ni) = readAt(b, i)
          val (vv, mi) = readAt(b, ni)
          xs(kk) = vv
          i = mi
          k += 1
        (MapV(xs.toMap), i)
      case 7 =>
        info match
          case 20 => (BoolV(false), after)
          case 21 => (BoolV(true),  after)
          case 22 => (NullV,        after)
          case 23 => (UndefV,       after)
          // floats: skip the v bytes already in `after`
          case _  => (FloatV(java.lang.Double.longBitsToDouble(v)), after)
      case _ =>
        throw RuntimeException(s"unsupported CBOR major type $mt")

  /** Decode the additional-info / length argument that follows the
   *  initial byte.  `info < 24` is its own value; 24/25/26/27 mean
   *  1/2/4/8 more bytes carry an unsigned big-endian integer. */
  private def readArg(b: Array[Byte], start: Int, info: Int): (Long, Int) =
    info match
      case n if n < 24 => (n.toLong, start)
      case 24 => ((b(start) & 0xffL), start + 1)
      case 25 =>
        ((b(start) & 0xffL) << 8 | (b(start + 1) & 0xffL), start + 2)
      case 26 =>
        var v = 0L
        var i = 0
        while i < 4 do { v = (v << 8) | (b(start + i) & 0xffL); i += 1 }
        (v, start + 4)
      case 27 =>
        var v = 0L
        var i = 0
        while i < 8 do { v = (v << 8) | (b(start + i) & 0xffL); i += 1 }
        (v, start + 8)
      case _  => throw RuntimeException(s"unsupported CBOR length info $info")
