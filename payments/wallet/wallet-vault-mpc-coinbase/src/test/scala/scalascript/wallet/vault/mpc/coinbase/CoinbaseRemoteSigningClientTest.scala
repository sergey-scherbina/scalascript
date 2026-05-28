package scalascript.wallet.vault.mpc.coinbase

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.spec.ECGenParameterSpec
import java.util.{Base64, ServiceLoader}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.*
import java.util.concurrent.atomic.AtomicInteger
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.UnlockCredential

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

  private val pollCounter = AtomicInteger(0)
  @volatile private var mode               = "complete"
  @volatile private var lastSignBody       = ""
  @volatile private var lastApiKeyHeader   = ""
  @volatile private var lastTimestamp      = ""
  @volatile private var lastSig            = ""

  private val portfolioId = "port-42"
  private val cannedSig: Array[Byte] = Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)

  private def completed: ujson.Value =
    ujson.Obj("status" -> "SIGNED", "signature" -> ujson.Obj("value" -> CoinbaseWire.hex(cannedSig)))

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  private def handle(ex: HttpExchange): Unit =
    val path   = ex.getRequestURI.getPath
    val method = ex.getRequestMethod
    lastApiKeyHeader = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-KEY")).getOrElse("")
    lastTimestamp    = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-TIMESTAMP")).getOrElse("")
    lastSig          = Option(ex.getRequestHeaders.getFirst("X-CB-ACCESS-SIGNATURE")).getOrElse("")
    val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
    if method == "POST" then lastSignBody = body

    (method, path) match
      case ("GET", p) if p == s"/v1/portfolios/$portfolioId" =>
        writeJson(ex, 200, ujson.Obj("id" -> portfolioId, "name" -> "Test Portfolio"))
      case ("GET", p) if p.endsWith("/wallets") =>
        writeJson(ex, 200, ujson.Obj(
          "wallets" -> ujson.Arr(
            ujson.Obj("id" -> "wallet-1", "name" -> "Primary Wallet"),
            ujson.Obj("id" -> "wallet-2", "name" -> "Secondary Wallet"),
          )
        ))
      case ("POST", p) if p.endsWith("/signing_requests") =>
        mode match
          case "http-500" => writeJson(ex, 500, ujson.Obj("error" -> "boom"))
          case _          => pollCounter.set(0); writeJson(ex, 200, ujson.Obj("signing_request_id" -> "req-1"))
      case ("GET", p) if p.endsWith("/signing_requests/req-1") =>
        val n = pollCounter.incrementAndGet()
        mode match
          case "complete" => writeJson(ex, 200, completed)
          case "pending-then-complete" =>
            if n < 3 then writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
            else writeJson(ex, 200, completed)
          case "failed" =>
            writeJson(ex, 200, ujson.Obj("status" -> "FAILED", "reason" -> "POLICY_DENIED"))
          case "timeout" => writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
          case _         => writeJson(ex, 404, ujson.Obj("message" -> "not found"))
      case _ =>
        writeJson(ex, 404, ujson.Obj("message" -> "not found"))

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", new HttpHandler:
      override def handle(ex: HttpExchange): Unit =
        CoinbaseRemoteSigningClientTest.this.handle(ex)
    )
    server.setExecutor(null)
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def client(opts: CoinbaseOptions = CoinbaseOptions(portfolioId = portfolioId, pollIntervalMs = 5, pollMaxAttempts = 5)) =
    CoinbaseRemoteSigningClient(baseUrl, "test-api-key", testPrivKeyPem, opts)

  // ─── tests ──────────────────────────────────────────────────────────

  test("health returns true when server returns 200"):
    Await.result(client().health(), 2.seconds) shouldBe true

  test("listAccounts decodes wallet list into McpAccount sequence"):
    val accts = Await.result(client().listAccounts(), 2.seconds)
    accts.size     shouldBe 2
    accts(0).id    shouldBe "wallet-1"
    accts(0).label shouldBe "Primary Wallet"
    accts(1).id    shouldBe "wallet-2"
    accts(1).label shouldBe "Secondary Wallet"

  test("sign — immediate SIGNED response returns signature bytes"):
    mode = "complete"
    val sig = Await.result(
      client().sign("wallet-1", Curve.Secp256k1, "m/44'/60'/0'/0/0",
        Array[Byte](0x11, 0x22, 0x33), HashAlgo.Keccak256),
      3.seconds,
    )
    sig shouldBe cannedSig

  test("sign — request body contains correct wallet_id, algorithm and payload"):
    mode = "complete"
    Await.result(client().sign("wallet-1", Curve.Secp256k1, "m/44'/60'/0'/0/0",
      Array[Byte](0xaa.toByte, 0xbb.toByte), HashAlgo.None), 3.seconds)
    val body = ujson.read(lastSignBody)
    body("wallet_id").str                         shouldBe "wallet-1"
    body("signing_target")("algorithm").str       shouldBe "SECP256K1"
    body("signing_target")("payload").str         shouldBe "aabb"
    body("signing_target")("derivation_path").str shouldBe "m/44'/60'/0'/0/0"
    body("signing_target")("hash_algorithm").str  shouldBe "none"

  test("sign — request carries X-CB-ACCESS-KEY, TIMESTAMP and SIGNATURE headers"):
    mode = "complete"
    Await.result(client().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 3.seconds)
    lastApiKeyHeader shouldBe "test-api-key"
    lastTimestamp    should not be empty
    lastSig          should not be empty
    noException shouldBe thrownBy:
      Base64.getDecoder.decode(lastSig)

  test("ECDSA signature over (timestamp + method + path + body) is verifiable"):
    mode = "complete"
    Await.result(client().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](2), HashAlgo.None), 3.seconds)
    val sigPath = s"/v1/portfolios/$portfolioId/signing_requests"
    val message = s"${lastTimestamp}POST$sigPath$lastSignBody"
    val jSig    = java.security.Signature.getInstance("SHA256withECDSA")
    jSig.initVerify(testKeyPair.getPublic)
    jSig.update(message.getBytes("UTF-8"))
    jSig.verify(Base64.getDecoder.decode(lastSig)) shouldBe true

  test("pending request polls until completed"):
    mode = "pending-then-complete"
    pollCounter.set(0)
    Await.result(client().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 3.seconds)
    pollCounter.get() should be >= 3

  test("failed signing request surfaces reason"):
    mode = "failed"
    val ex = intercept[RuntimeException]:
      Await.result(client().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 3.seconds)
    ex.getMessage should include ("POLICY_DENIED")

  test("poll timeout fails after maxAttempts"):
    mode = "timeout"
    val ex = intercept[RuntimeException]:
      Await.result(
        client(CoinbaseOptions(portfolioId = portfolioId, pollIntervalMs = 5, pollMaxAttempts = 2))
          .sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
        2.seconds,
      )
    ex.getMessage should include ("did not complete within 2 polls")

  test("HTTP 500 on create signing request fails"):
    mode = "http-500"
    val ex = intercept[RuntimeException]:
      Await.result(client().sign("wallet-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    ex.getMessage should include ("HTTP 500")

  test("CoinbaseVault named constructor returns an unlockable McpVault"):
    mode = "complete"
    val vault = CoinbaseVault("test-api-key", testPrivKeyPem, baseUrl,
      CoinbaseOptions(portfolioId = portfolioId, pollIntervalMs = 5))
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked shouldBe false

  test("CoinbasePlugin loads via ServiceLoader"):
    val plugins = ServiceLoader.load(classOf[CoinbasePlugin]).iterator().asScala.toSeq
    plugins.map(_.provider) should contain ("coinbase")

  test("CoinbasePlugin can construct a vault"):
    mode = "complete"
    val vault = CoinbasePlugin().vault("test-api-key", testPrivKeyPem, baseUrl,
      CoinbaseOptions(portfolioId = portfolioId, pollIntervalMs = 5))
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked shouldBe false

  test("CoinbaseAuth.privateKeyFromPem parses PKCS8 PEM"):
    CoinbaseAuth.privateKeyFromPem(testPrivKeyPem).getAlgorithm shouldBe "EC"

  test("CoinbaseWire.parseSigningRequestStatus — SIGNED with signature"):
    CoinbaseWire.parseSigningRequestStatus(ujson.Obj(
      "status"    -> "SIGNED",
      "signature" -> ujson.Obj("value" -> "deadbeef"),
    )) shouldBe Right(Some(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)))

  test("CoinbaseWire.parseSigningRequestStatus — PENDING returns None"):
    CoinbaseWire.parseSigningRequestStatus(ujson.Obj("status" -> "PENDING")) shouldBe Right(None)

  test("CoinbaseWire.parseSigningRequestStatus — FAILED returns Left reason"):
    CoinbaseWire.parseSigningRequestStatus(ujson.Obj(
      "status" -> "FAILED", "reason" -> "quorum not reached",
    )) shouldBe Left("quorum not reached")

  test("CoinbaseWire.parseSigningRequestId fallback to id field"):
    CoinbaseWire.parseSigningRequestId(ujson.Obj("id" -> "req-alt")) shouldBe "req-alt"
