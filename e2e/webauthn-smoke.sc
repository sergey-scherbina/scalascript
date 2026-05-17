#!/usr/bin/env scala-cli
//> using toolkit latest

// WebAuthn cross-backend e2e smoke harness.
//
// Boots `examples/webauthn-demo.ssc` on http://localhost:8781 through
// the interpreter, mocks an authenticator with an in-process ECDSA P-256
// keypair, and walks:
//
//   1. enrol  → build a `none`-attestation `attestationObject` with our
//                public key, POST it; expect 200 + the server records
//                the credential.
//   2. signin → fetch a challenge, sign authData||SHA256(clientDataJSON)
//                with our private key, POST; expect 200 + signCount=1.
//   3. replay → re-sign with the SAME signCount; expect 401 because the
//                stored counter is now 1 and ≤ 1 is rejected.
//
// No real authenticator hardware needed; the CBOR encoder + ECDSA
// signing are inline below.  Targets the interpreter only — same
// `serve(...)` flow, no codegen variance to worry about.

import java.net.{URI, http as jhttp}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.{KeyPairGenerator, MessageDigest, Signature}
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.Base64
import scala.concurrent.duration.*

val PORT   = 8781
val ORIGIN = s"http://localhost:$PORT"

val root    = os.pwd
val example = root / "examples" / "webauthn-demo.ssc"
val binSsc  = root / "bin" / "ssc"

// ── tiny CBOR encoder (subset attestationObject + COSE keys need) ──────

object Cbor:
  private def header(major: Int, n: Long): Array[Byte] =
    val mt = (major & 7) << 5
    if n < 24L then Array((mt | n.toInt).toByte)
    else if n < 0x100L then Array((mt | 24).toByte, n.toByte)
    else if n < 0x10000L then
      Array((mt | 25).toByte, (n >> 8).toByte, n.toByte)
    else if n < 0x100000000L then
      Array((mt | 26).toByte,
        (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)
    else
      val o = (mt | 27).toByte
      (0 to 7).map(i => (n >>> (56 - i * 8)).toByte).toArray.+:(o)

  def uint(n: Long): Array[Byte]     = header(0, n)
  def negint(n: Long): Array[Byte]   = header(1, -1L - n)         // n is the negative
  def bytes(b: Array[Byte]): Array[Byte] = header(2, b.length.toLong) ++ b
  def text(s: String): Array[Byte]   = { val b = s.getBytes("UTF-8"); header(3, b.length.toLong) ++ b }
  def map(pairs: Seq[(Array[Byte], Array[Byte])]): Array[Byte] =
    pairs.foldLeft(header(5, pairs.length.toLong)) { (acc, kv) => acc ++ kv._1 ++ kv._2 }

// ── helpers ──────────────────────────────────────────────────────────

val b64u = Base64.getUrlEncoder.withoutPadding
val b64d = Base64.getUrlDecoder

def sha256(b: Array[Byte]): Array[Byte] =
  MessageDigest.getInstance("SHA-256").digest(b)

def jsonField(json: String, key: String): Option[String] =
  val needle = "\"" + key + "\""
  val ki = json.indexOf(needle)
  if ki < 0 then None
  else
    val rest = json.substring(ki + needle.length).dropWhile(c => c.isWhitespace || c == ':')
    if rest.isEmpty || rest.charAt(0) != '"' then None
    else
      val body = rest.drop(1).takeWhile(_ != '"')
      Some(body)

// Build the rfc-shape `authData` blob.
//   rpIdHash(32) || flags(1) || signCount(4) [ || AAGUID(16) || credIdLen(2)
//                                                || credentialId(L) || cosePubKey ]
def buildAuthData(
    rpId:       String,
    signCount:  Long,
    attested:   Option[(Array[Byte], Array[Byte])]  // (credentialId, cosePubKey)
): Array[Byte] =
  val rpHash = sha256(rpId.getBytes("UTF-8"))
  val flagUP = 0x01.toByte
  val flagAT = 0x40.toByte
  val flags  = if attested.isDefined then (flagUP | flagAT).toByte else flagUP
  val countB = Array(
    ((signCount >> 24) & 0xff).toByte,
    ((signCount >> 16) & 0xff).toByte,
    ((signCount >>  8) & 0xff).toByte,
    (signCount         & 0xff).toByte,
  )
  attested match
    case None => rpHash ++ Array(flags) ++ countB
    case Some((credId, pubKey)) =>
      val aaguid = new Array[Byte](16)
      val cidLen = credId.length
      val cidLenB = Array(((cidLen >> 8) & 0xff).toByte, (cidLen & 0xff).toByte)
      rpHash ++ Array(flags) ++ countB ++ aaguid ++ cidLenB ++ credId ++ pubKey

def cosePublicKey(pub: ECPublicKey): Array[Byte] =
  // ECDSA P-256: 32-byte big-endian X, Y.  java may emit a leading 0x00
  // sign byte for positive values; strip it / left-pad as needed.
  def coord(bi: java.math.BigInteger): Array[Byte] =
    val raw = bi.toByteArray
    val out = new Array[Byte](32)
    if raw.length == 32 then java.lang.System.arraycopy(raw, 0, out, 0, 32)
    else if raw.length == 33 then java.lang.System.arraycopy(raw, 1, out, 0, 32)
    else java.lang.System.arraycopy(raw, 0, out, 32 - raw.length, raw.length)
    out
  val xb = coord(pub.getW.getAffineX)
  val yb = coord(pub.getW.getAffineY)
  Cbor.map(Seq(
    Cbor.uint(1)    -> Cbor.uint(2),       // kty = EC2
    Cbor.uint(3)    -> Cbor.negint(-7),    // alg = ES256
    Cbor.negint(-1) -> Cbor.uint(1),       // crv = P-256
    Cbor.negint(-2) -> Cbor.bytes(xb),     // x
    Cbor.negint(-3) -> Cbor.bytes(yb),     // y
  ))

// ── HTTP client ──────────────────────────────────────────────────────

val httpClient = HttpClient.newHttpClient()

def httpPost(path: String, body: String): (Int, String) =
  val req = HttpRequest.newBuilder(URI.create(s"$ORIGIN$path"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build()
  val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString)
  (resp.statusCode, resp.body)

def httpPostEmpty(path: String): (Int, String) =
  val req = HttpRequest.newBuilder(URI.create(s"$ORIGIN$path"))
    .POST(HttpRequest.BodyPublishers.noBody)
    .build()
  val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString)
  (resp.statusCode, resp.body)

def waitForServer(maxMs: Long): Boolean =
  val deadline = java.lang.System.currentTimeMillis() + maxMs
  while java.lang.System.currentTimeMillis() < deadline do
    try
      val req = HttpRequest.newBuilder(URI.create(s"$ORIGIN/")).GET().build()
      val r = httpClient.send(req, HttpResponse.BodyHandlers.discarding)
      if r.statusCode >= 200 && r.statusCode < 500 then return true
    catch case _: Throwable => Thread.sleep(150)
  false

// ── orchestration ────────────────────────────────────────────────────

println("=" * 60)
println("  WebAuthn passkey e2e smoke — INT backend")
println("=" * 60)
println()

if !os.exists(binSsc) then
  System.err.println(s"FATAL: $binSsc not found.  Build it first: scripts/install.sh ./bin/ssc")
  sys.exit(2)

val sessionSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
val proc = os.proc(binSsc, example)
  .spawn(stdout = os.Pipe, stderr = os.Pipe, env = Map("SSC_SESSION_SECRET" -> sessionSecret))

try
  if !waitForServer(15_000) then
    System.err.println("FATAL: server didn't come up on " + ORIGIN)
    sys.exit(2)
  println("  [BOOT] server on " + ORIGIN)

  // ── 1. enrol ────────────────────────────────────────────────────────
  val (beginStatus, beginBody) = httpPostEmpty("/webauthn/enrol/begin")
  assert(beginStatus == 200, s"begin enrol failed: $beginStatus $beginBody")
  val challenge1 = jsonField(beginBody, "challenge")
                    .getOrElse(sys.error("no challenge in begin body: " + beginBody))
  println("  [ENROL]  challenge=" + challenge1.take(12) + "…")

  // Generate the authenticator keypair (one for the whole test).
  val gen = KeyPairGenerator.getInstance("EC")
  gen.initialize(ECGenParameterSpec("secp256r1"))
  val kp     = gen.generateKeyPair()
  val pubKey = kp.getPublic.asInstanceOf[ECPublicKey]
  val priv   = kp.getPrivate

  // Random 16-byte credentialId — opaque to the server, we just round-trip it.
  val credId = new Array[Byte](16)
  java.security.SecureRandom().nextBytes(credId)

  val cosePub = cosePublicKey(pubKey)
  val authData0 = buildAuthData("localhost", signCount = 0, attested = Some((credId, cosePub)))
  val attObj = Cbor.map(Seq(
    Cbor.text("fmt")     -> Cbor.text("none"),
    Cbor.text("attStmt") -> Cbor.map(Seq.empty),
    Cbor.text("authData")-> Cbor.bytes(authData0),
  ))

  val cd1 = s"""{"type":"webauthn.create","challenge":"$challenge1","origin":"$ORIGIN"}"""
  val payload1 = s"""{
    "clientDataJSON":    "${b64u.encodeToString(cd1.getBytes("UTF-8"))}",
    "attestationObject": "${b64u.encodeToString(attObj)}"
  }"""
  val (completeStatus, completeBody) = httpPost("/webauthn/enrol/complete", payload1)
  assert(completeStatus == 200,
    s"complete enrol failed: $completeStatus $completeBody")
  assert(completeBody.contains("enrolled credentialId="),
    s"unexpected enrol response body: $completeBody")
  println("  [ENROL]  registered credentialId=" + b64u.encodeToString(credId).take(12) + "…")

  // ── 2. sign-in ──────────────────────────────────────────────────────
  val (siBeginStatus, siBeginBody) = httpPostEmpty("/webauthn/signin/begin")
  assert(siBeginStatus == 200, s"begin signin failed: $siBeginStatus $siBeginBody")
  val challenge2 = jsonField(siBeginBody, "challenge")
                    .getOrElse(sys.error("no challenge in signin body: " + siBeginBody))
  println("  [SIGNIN] challenge=" + challenge2.take(12) + "…")

  def signAssertion(challenge: String, signCount: Long): (String, String, String) =
    val authData = buildAuthData("localhost", signCount, attested = None)
    val cd       = s"""{"type":"webauthn.get","challenge":"$challenge","origin":"$ORIGIN"}"""
    val cdHash   = sha256(cd.getBytes("UTF-8"))
    val signed   = authData ++ cdHash
    val sigEng   = Signature.getInstance("SHA256withECDSA")
    sigEng.initSign(priv)
    sigEng.update(signed)
    val sig = sigEng.sign()
    (b64u.encodeToString(cd.getBytes("UTF-8")),
     b64u.encodeToString(authData),
     b64u.encodeToString(sig))

  val (cd2, ad2, sig2) = signAssertion(challenge2, signCount = 1L)
  val payload2 = s"""{
    "clientDataJSON":    "$cd2",
    "authenticatorData": "$ad2",
    "signature":         "$sig2",
    "credentialId":      "${b64u.encodeToString(credId)}"
  }"""
  val (siStatus, siBody) = httpPost("/webauthn/signin/complete", payload2)
  assert(siStatus == 200, s"signin failed: $siStatus $siBody")
  assert(siBody.contains("signed in (signCount=1)"),
    s"unexpected signin body: $siBody")
  println("  [SIGNIN] verified (signCount=1)")

  // ── 3. replay rejection ────────────────────────────────────────────
  val (repBeginStatus, repBeginBody) = httpPostEmpty("/webauthn/signin/begin")
  assert(repBeginStatus == 200, s"begin signin (replay) failed: $repBeginStatus")
  val challenge3 = jsonField(repBeginBody, "challenge")
                    .getOrElse(sys.error("no challenge: " + repBeginBody))
  val (cd3, ad3, sig3) = signAssertion(challenge3, signCount = 1L) // SAME as before
  val payload3 = s"""{
    "clientDataJSON":    "$cd3",
    "authenticatorData": "$ad3",
    "signature":         "$sig3",
    "credentialId":      "${b64u.encodeToString(credId)}"
  }"""
  val (rStatus, rBody) = httpPost("/webauthn/signin/complete", payload3)
  assert(rStatus == 401,
    s"expected 401 on signCount replay, got $rStatus body=$rBody")
  println("  [REPLAY] rejected (status=401 signCount<=stored)")

  println()
  println("WebAuthn passkey flow verified end-to-end.")
  println("  enrol  → 200 (credential stored)")
  println("  signin → 200 (signature verified, counter bumped to 1)")
  println("  replay → 401 (counter monotonicity guard kicked in)")
finally
  proc.destroyForcibly()
  // Drain pipes so the process actually shuts down cleanly.
  try proc.stdout.readAllBytes() catch case _: Throwable => ()
  try proc.stderr.readAllBytes() catch case _: Throwable => ()
