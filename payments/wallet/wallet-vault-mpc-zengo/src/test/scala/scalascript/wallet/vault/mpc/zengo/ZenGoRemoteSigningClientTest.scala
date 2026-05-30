package scalascript.wallet.vault.mpc.zengo

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scalascript.crypto.{Curve, HashAlgo}

class ZenGoRemoteSigningClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testApiKey    = "zg-api-key-test"
  private val testSecretKey = "zg-secret-key-test"
  private val cannedSig     = Array[Byte](0x11.toByte, 0x22.toByte, 0x33.toByte, 0x44.toByte)
  private val cannedSigHex  = cannedSig.map(b => f"${b & 0xff}%02x").mkString

  @volatile private var lastBody     = ""
  @volatile private var lastKey      = ""
  @volatile private var lastTimestamp = ""
  @volatile private var lastSig      = ""
  @volatile private var serverMode   = "ok"
  private val pollCounter            = AtomicInteger(0)

  private var server: HttpServer = scala.compiletime.uninitialized
  private var baseUrl: String    = ""

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  private def captureAuth(ex: HttpExchange): Unit =
    lastKey       = Option(ex.getRequestHeaders.getFirst("X-ZENGO-KEY")).getOrElse("")
    lastTimestamp = Option(ex.getRequestHeaders.getFirst("X-ZENGO-TIMESTAMP")).getOrElse("")
    lastSig       = Option(ex.getRequestHeaders.getFirst("X-ZENGO-SIGNATURE")).getOrElse("")
    val raw = ex.getRequestBody.readAllBytes()
    if raw.nonEmpty then lastBody = new String(raw, "UTF-8")

  private def handle(ex: HttpExchange): Unit =
    captureAuth(ex)
    ex.getRequestURI.getPath match
      case "/v1/health" =>
        if serverMode == "health-down" then writeJson(ex, 503, ujson.Obj("status" -> "down"))
        else writeJson(ex, 200, ujson.Obj("status" -> "ok"))
      case "/v1/accounts" =>
        writeJson(ex, 200, ujson.Obj(
          "accounts" -> ujson.Arr(
            ujson.Obj("id" -> "acct-1", "name" -> "Primary Wallet"),
            ujson.Obj("id" -> "acct-2", "name" -> "Secondary Wallet"),
          )
        ))
      case "/v1/signing/requests" =>
        if serverMode == "sign-error" then writeJson(ex, 500, ujson.Obj("error" -> "signing failed"))
        else
          pollCounter.set(0)
          writeJson(ex, 200, ujson.Obj("request_id" -> "req-zengo-1"))
      case "/v1/signing/requests/req-zengo-1" =>
        val n = pollCounter.incrementAndGet()
        serverMode match
          case "poll-pending" if n < 3 =>
            writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
          case "poll-fail" =>
            writeJson(ex, 200, ujson.Obj("status" -> "FAILED", "reason" -> "policy rejected"))
          case _ =>
            writeJson(ex, 200, ujson.Obj("status" -> "SIGNED", "signature" -> cannedSigHex))
      case _ =>
        writeJson(ex, 404, ujson.Obj("error" -> "not found"))

  override def beforeAll(): Unit =
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/", ex => handle(ex))
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    server.stop(0)

  private def client(mode: String = "ok"): ZenGoRemoteSigningClient =
    serverMode = mode
    ZenGoRemoteSigningClient(
      baseUrl   = baseUrl,
      apiKey    = testApiKey,
      secretKey = testSecretKey,
      options   = ZenGoOptions(pollIntervalMs = 50L, timeoutMs = 5_000L),
    )

  test("ZenGoPlugin.provider returns 'zengo'"):
    ZenGoPlugin().provider shouldBe "zengo"

  test("ZenGoVault creates McpVault with correct id"):
    val vault = ZenGoVault(testApiKey, testSecretKey, baseUrl)
    vault.id should startWith("zengo:")
    vault.id should include(testApiKey)

  test("health returns true when server is up"):
    Await.result(client().health(), 5.seconds) shouldBe true

  test("health returns false when server returns 503"):
    Await.result(client("health-down").health(), 5.seconds) shouldBe false

  test("listAccounts returns accounts from server"):
    val accounts = Await.result(client().listAccounts(), 5.seconds)
    accounts should have size 2
    accounts.map(_.id) should contain("acct-1")
    accounts.map(_.label) should contain("Primary Wallet")

  test("sign creates request and polls for result"):
    val result = Await.result(
      client().sign("acct-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1, 2, 3), HashAlgo.Keccak256),
      5.seconds,
    )
    result shouldBe cannedSig

  test("sign request body contains account_id"):
    Await.result(
      client().sign("acct-42", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include("acct-42")

  test("sign request body contains payload hex"):
    Await.result(
      client().sign("acct-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](0xde.toByte, 0xad.toByte), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include("dead")

  test("auth headers are present on every request"):
    Await.result(client().health(), 5.seconds)
    lastKey       shouldBe testApiKey
    lastTimestamp should not be empty
    lastSig       should not be empty

  test("HMAC signature is base64-encoded"):
    Await.result(client().health(), 5.seconds)
    val decoded = java.util.Base64.getDecoder.decode(lastSig)
    decoded.length shouldBe 32

  test("sign polls until SIGNED when initially PENDING"):
    val result = Await.result(
      client("poll-pending").sign("acct-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256),
      5.seconds,
    )
    result shouldBe cannedSig
    pollCounter.get() should be >= 3

  test("sign fails when request is rejected"):
    val f = client("poll-fail").sign("acct-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256)
    val ex = intercept[RuntimeException] { Await.result(f, 5.seconds) }
    ex.getMessage should include("policy rejected")

  test("sign fails on HTTP error creating request"):
    val f = client("sign-error").sign("acct-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256)
    val ex = intercept[RuntimeException] { Await.result(f, 5.seconds) }
    ex.getMessage should include("500")

  test("ZenGoAuth.sign produces deterministic HMAC for same inputs"):
    val sig1 = ZenGoAuth.sign("secret", 1000L, "POST", "/v1/signing/requests", "body")
    val sig2 = ZenGoAuth.sign("secret", 1000L, "POST", "/v1/signing/requests", "body")
    sig1 shouldBe sig2

  test("ZenGoAuth.sign differs when timestamp changes"):
    val sig1 = ZenGoAuth.sign("secret", 1000L, "GET", "/v1/health", "")
    val sig2 = ZenGoAuth.sign("secret", 1001L, "GET", "/v1/health", "")
    sig1 should not equal sig2

  test("ZenGoWire.zenGoAlgorithm maps curves correctly"):
    ZenGoWire.zenGoAlgorithm(Curve.Secp256k1) shouldBe "ECDSA_SECP256K1"
    ZenGoWire.zenGoAlgorithm(Curve.Ed25519)   shouldBe "EDDSA_ED25519"
    ZenGoWire.zenGoAlgorithm(Curve.P256)      shouldBe "ECDSA_P256"
