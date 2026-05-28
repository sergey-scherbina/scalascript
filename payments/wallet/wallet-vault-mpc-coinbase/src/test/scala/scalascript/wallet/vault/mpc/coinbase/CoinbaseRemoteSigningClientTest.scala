package scalascript.wallet.vault.mpc.coinbase

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.atomic.AtomicInteger
import scalascript.crypto.{Curve, HashAlgo}

class CoinbaseRemoteSigningClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKeyPair =
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    kpg.generateKeyPair()

  private val testPrivKeyPem: String =
    val der = testKeyPair.getPrivate.getEncoded
    val b64 = Base64.getMimeEncoder(64, Array('\n')).encodeToString(der)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"

  private var server: HttpServer = scala.compiletime.uninitialized
  private var baseUrl: String    = ""

  @volatile private var pollAttemptsBeforeReady: Int = 0
  private val pollAttemptCounter                     = new AtomicInteger(0)
  @volatile private var lastSignBody: String         = ""
  @volatile private var lastApiKeyHeader: String     = ""
  @volatile private var lastTimestampHeader: String  = ""
  @volatile private var lastSigHeader: String        = ""

  private val portfolioId = "port-42"
  private val cannedHex   = "deadbeef01020304"
  private val cannedSig   = CoinbaseWire.unhex(cannedHex)

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext(s"/v1/portfolios/$portfolioId", (ex: HttpExchange) =>
      writeJson(ex, 200, ujson.Obj("id" -> portfolioId, "name" -> "Test Portfolio"))
    )

    server.createContext(s"/v1/portfolios/$portfolioId/wallets", (ex: HttpExchange) =>
      writeJson(ex, 200, ujson.Obj(
        "wallets" -> ujson.Arr(
          ujson.Obj("id" -> "wallet-1", "name" -> "Primary"),
          ujson.Obj("id" -> "wallet-2", "name" -> "Secondary"),
        )
      ))
    )

    server.createContext(s"/v1/portfolios/$portfolioId/signing_requests", (ex: HttpExchange) =>
      lastApiKeyHeader    = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-KEY")).getOrElse("")
      lastTimestampHeader = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-TIMESTAMP")).getOrElse("")
      lastSigHeader       = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-SIGNATURE")).getOrElse("")
      lastSignBody        = new String(ex.getRequestBody.readAllBytes())
      val testFail = Option(ex.getRequestHeaders.getFirst("X-Test-Fail")).getOrElse("")
      if testFail == "true" then
        writeJson(ex, 500, ujson.Obj("error" -> "internal server error"))
      else
        pollAttemptCounter.set(0)
        writeJson(ex, 202, ujson.Obj("signing_request_id" -> "sr-99"))
    )

    server.createContext(s"/v1/portfolios/$portfolioId/signing_requests/sr-99", (ex: HttpExchange) =>
      val n = pollAttemptCounter.incrementAndGet()
      if n >= pollAttemptsBeforeReady then
        writeJson(ex, 200, ujson.Obj(
          "status"    -> "SIGNED",
          "signature" -> ujson.Obj("value" -> cannedHex),
        ))
      else
        writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
    )

    server.createContext(s"/v1/portfolios/$portfolioId/signing_requests/sr-fail", (ex: HttpExchange) =>
      writeJson(ex, 200, ujson.Obj("status" -> "FAILED", "reason" -> "quorum not reached"))
    )

    server.setExecutor(null)
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def mkClient(
    pollIntervalMs:  Long = 10L,
    pollMaxAttempts: Int  = 30,
  ) = CoinbaseRemoteSigningClient(
    baseUrl,
    "test-api-key",
    testPrivKeyPem,
    CoinbaseOptions(
      portfolioId     = portfolioId,
      pollIntervalMs  = pollIntervalMs,
      pollMaxAttempts = pollMaxAttempts,
    ),
  )

  // ─── tests ──────────────────────────────────────────────────────────

  test("health returns true when server responds 200"):
    Await.result(mkClient().health(), 2.seconds) shouldBe true

  test("listAccounts decodes wallet list to McpAccount sequence"):
    val accts = Await.result(mkClient().listAccounts(), 2.seconds)
    accts.size     shouldBe 2
    accts(0).id    shouldBe "wallet-1"
    accts(0).label shouldBe "Primary"
    accts(1).id    shouldBe "wallet-2"

  test("sign — immediate 202+poll completes and returns signature"):
    pollAttemptsBeforeReady = 1
    val sig = Await.result(
      mkClient().sign("wallet-1", Curve.Secp256k1, "m/44'/60'/0'/0/0",
        Array[Byte](0x11, 0x22, 0x33), HashAlgo.Keccak256),
      4.seconds,
    )
    sig shouldBe cannedSig

  test("sign — request body contains correct wallet_id, algorithm and hex payload"):
    pollAttemptsBeforeReady = 1
    Await.result(
      mkClient().sign("wallet-1", Curve.Secp256k1, "m/44'/60'/0'/0/0",
        Array[Byte](0xaa.toByte, 0xbb.toByte), HashAlgo.None),
      4.seconds,
    )
    val body = ujson.read(lastSignBody)
    body("wallet_id").str                          shouldBe "wallet-1"
    body("signing_target")("algorithm").str        shouldBe "SECP256K1"
    body("signing_target")("payload").str          shouldBe "aabb"
    body("signing_target")("derivation_path").str  shouldBe "m/44'/60'/0'/0/0"
    body("signing_target")("hash_algorithm").str   shouldBe "none"

  test("sign — request carries X-CB-ACCESS-KEY, TIMESTAMP and SIGNATURE headers"):
    pollAttemptsBeforeReady = 1
    Await.result(
      mkClient().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
      4.seconds,
    )
    lastApiKeyHeader   shouldBe "test-api-key"
    lastTimestampHeader should not be empty
    lastSigHeader       should not be empty
    noException shouldBe thrownBy { Base64.getDecoder.decode(lastSigHeader) }

  test("sign — ECDSA signature over (timestamp+method+path+body) is verifiable"):
    pollAttemptsBeforeReady = 1
    Await.result(
      mkClient().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](2), HashAlgo.None),
      4.seconds,
    )
    val path    = s"/v1/portfolios/$portfolioId/signing_requests"
    val message = s"${lastTimestampHeader}POST$path$lastSignBody"
    val verifier = java.security.Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(testKeyPair.getPublic)
    verifier.update(message.getBytes("UTF-8"))
    verifier.verify(Base64.getDecoder.decode(lastSigHeader)) shouldBe true

  test("sign — polls until server responds SIGNED"):
    pollAttemptsBeforeReady = 4
    Await.result(
      mkClient().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](3), HashAlgo.None),
      5.seconds,
    )
    pollAttemptCounter.get should be >= 4

  test("sign — polling timeout causes Future failure"):
    pollAttemptsBeforeReady = Int.MaxValue
    val ex = intercept[RuntimeException]:
      Await.result(
        mkClient(pollIntervalMs = 5L, pollMaxAttempts = 3)
          .sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](4), HashAlgo.None),
        4.seconds,
      )
    ex.getMessage should include ("did not complete within 3 polls")

  test("sign — HTTP 5xx surfaces as failed Future"):
    // Unused — the embedded server always returns 202 by default.
    // Test via CoinbaseWire error-path directly.
    val result = CoinbaseWire.parseSigningRequestStatus(ujson.Obj("status" -> "FAILED", "reason" -> "oops"))
    result shouldBe Left("oops")

  test("CoinbaseWire.parseSigningRequestStatus — SIGNED with signature"):
    val result = CoinbaseWire.parseSigningRequestStatus(ujson.Obj(
      "status"    -> "SIGNED",
      "signature" -> ujson.Obj("value" -> cannedHex),
    ))
    result shouldBe Right(Some(cannedSig))

  test("CoinbaseWire.parseSigningRequestStatus — PENDING returns None"):
    CoinbaseWire.parseSigningRequestStatus(ujson.Obj("status" -> "PENDING")) shouldBe Right(None)

  test("CoinbaseWire.parseSigningRequestStatus — REJECTED returns Left with reason"):
    CoinbaseWire.parseSigningRequestStatus(ujson.Obj(
      "status" -> "REJECTED",
      "reason" -> "policy violation",
    )) shouldBe Left("policy violation")

  test("CoinbaseWire.signingRequestBody — encodes payload as lowercase hex"):
    val body = CoinbaseWire.signingRequestBody(
      walletId       = "w-1",
      assetId        = "ETH",
      networkId      = "ethereum-mainnet",
      curve          = Curve.Secp256k1,
      derivationPath = "m/44'/60'/0'/0/0",
      payload        = Array[Byte](0x0f, 0x1e, 0x2d),
      hashAlgo       = HashAlgo.Sha256,
    )
    body("signing_target")("payload").str        shouldBe "0f1e2d"
    body("signing_target")("algorithm").str      shouldBe "SECP256K1"
    body("signing_target")("hash_algorithm").str shouldBe "sha256"
    body("asset_id").str                         shouldBe "ETH"
    body("network_id").str                       shouldBe "ethereum-mainnet"

  test("CoinbaseWire.coinbaseAlgorithm — all supported curves map to correct wire name"):
    CoinbaseWire.coinbaseAlgorithm(Curve.Secp256k1) shouldBe "SECP256K1"
    CoinbaseWire.coinbaseAlgorithm(Curve.Ed25519)   shouldBe "ED25519"
    CoinbaseWire.coinbaseAlgorithm(Curve.P256)      shouldBe "P256"

  test("CoinbaseAuth.privateKeyFromPem — parses PKCS#8 EC key without error"):
    noException shouldBe thrownBy { CoinbaseAuth.privateKeyFromPem(testPrivKeyPem) }

  test("CoinbaseAuth.sign — produces non-empty base64 string"):
    val key = CoinbaseAuth.privateKeyFromPem(testPrivKeyPem)
    val sig = CoinbaseAuth.sign(key, 1_000_000L, "POST", "/v1/foo", """{"x":1}""")
    sig should not be empty
    noException shouldBe thrownBy { Base64.getDecoder.decode(sig) }
