package scalascript.wallet.vault.mpc.fireblocks

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.mpc.MpcSerialization

class FireblocksRemoteSigningClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
  keyPairGenerator.initialize(2048)
  private val keyPair = keyPairGenerator.generateKeyPair()
  private val privatePem = pem(keyPair.getPrivate.getEncoded)
  private val apiKey = "api-key-123"
  private val sigHex = "0x" + "ab" * 65

  private var server: HttpServer = scala.compiletime.uninitialized
  private var baseUrl = ""
  private val pollCounter = AtomicInteger(0)

  @volatile private var mode = "complete"
  @volatile private var lastPath = ""
  @volatile private var lastBody = ""
  @volatile private var lastPostBody = ""
  @volatile private var lastApiKey = ""
  @volatile private var lastAuth = ""

  override def beforeAll(): Unit =
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", new HttpHandler:
      override def handle(ex: HttpExchange): Unit =
        FireblocksRemoteSigningClientTest.this.handle(ex)
    )
    server.setExecutor(null)
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def handle(ex: HttpExchange): Unit =
    lastPath = ex.getRequestURI.toString
    lastApiKey = Option(ex.getRequestHeaders.getFirst("X-API-Key")).getOrElse("")
    lastAuth = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
    lastBody = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
    if ex.getRequestMethod == "POST" then lastPostBody = lastBody
    (ex.getRequestMethod, ex.getRequestURI.getPath) match
      case ("GET", "/v1/vault/accounts_paged") =>
        writeJson(ex, 200, ujson.Obj("accounts" -> ujson.Arr()))
      case ("GET", "/v1/vault/accounts/vault-1") =>
        writeJson(ex, 200, ujson.Obj(
          "id" -> "vault-1",
          "name" -> "Treasury MPC",
          "publicKeys" -> ujson.Obj(
            "secp256k1" -> MpcSerialization.b64encode(Array.fill[Byte](33)(0x02)),
            "ed25519"   -> MpcSerialization.b64encode(Array.fill[Byte](32)(0x07)),
            "unknown"   -> MpcSerialization.b64encode(Array[Byte](1, 2, 3)),
          ),
        ))
      case ("POST", "/v1/transactions") =>
        mode match
          case "http-500" => writeJson(ex, 500, ujson.Obj("message" -> "boom"))
          case _          => pollCounter.set(0); writeJson(ex, 200, ujson.Obj("id" -> "tx-1"))
      case ("GET", "/v1/transactions/tx-1") =>
        val n = pollCounter.incrementAndGet()
        mode match
          case "complete" =>
            writeJson(ex, 200, completed)
          case "pending-then-complete" =>
            if n < 3 then writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
            else writeJson(ex, 200, completed)
          case "failed" =>
            writeJson(ex, 200, ujson.Obj("status" -> "FAILED", "subStatus" -> "POLICY_DENIED"))
          case "timeout" =>
            writeJson(ex, 200, ujson.Obj("status" -> "PENDING"))
          case _ =>
            writeJson(ex, 404, ujson.Obj("message" -> "not found"))
      case _ =>
        writeJson(ex, 404, ujson.Obj("message" -> "not found"))

  private def completed: ujson.Value =
    ujson.Obj(
      "status" -> "COMPLETED",
      "signedMessages" -> ujson.Arr(ujson.Obj(
        "signature" -> ujson.Obj("fullSig" -> sigHex)
      )),
    )

  private def client(opts: FireblocksOptions = FireblocksOptions(vaultAccountId = "vault-1", pollIntervalMs = 5, pollMaxAttempts = 5)) =
    FireblocksRemoteSigningClient(baseUrl, apiKey, privatePem, opts)

  test("health signs a Fireblocks JWT and sends X-API-Key"):
    Await.result(client().health(), 2.seconds) shouldBe true
    lastPath should startWith ("/v1/vault/accounts_paged")
    lastApiKey shouldBe apiKey
    lastAuth should startWith ("Bearer ")
    FireblocksJwt.decodePayload(lastAuth.stripPrefix("Bearer "))("sub").str shouldBe apiKey

  test("JWT payload contains uri and SHA-256 bodyHash"):
    mode = "complete"
    Await.result(client().sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1, 2), HashAlgo.None), 2.seconds)
    val jwt = lastAuth.stripPrefix("Bearer ")
    val payload = FireblocksJwt.decodePayload(jwt)
    payload("uri").str shouldBe "/v1/transactions/tx-1"
    payload("bodyHash").str shouldBe FireblocksJwt.sha256Hex(lastBody.getBytes("UTF-8"))

  test("listAccounts maps Fireblocks account public keys"):
    val accounts = Await.result(client().listAccounts(), 2.seconds)
    accounts.size shouldBe 1
    accounts.head.id shouldBe "vault-1"
    accounts.head.label shouldBe "Treasury MPC"
    accounts.head.publicKeys(Curve.Secp256k1).length shouldBe 33
    accounts.head.publicKeys(Curve.Ed25519).length shouldBe 32
    accounts.head.publicKeys.keySet should not contain Curve.P256

  test("sign builds RAW transaction request"):
    mode = "complete"
    val sig = Await.result(client().sign("vault-1", Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](0x11, 0x22), HashAlgo.Keccak256), 2.seconds)
    sig shouldBe FireblocksWire.unhex(sigHex)
    val body = ujson.read(lastPostBody)
    body("operation").str shouldBe "RAW"
    body("assetId").str shouldBe "ETH"
    body("source")("type").str shouldBe "VAULT_ACCOUNT"
    val msg = body("extraParameters")("rawMessageData")("messages")(0)
    msg("content").str shouldBe "1122"
    msg("algorithm").str shouldBe "MPC_ECDSA_SECP256K1"
    msg("hashAlgorithm").str shouldBe "keccak256"

  test("sign supports Ed25519 algorithm mapping"):
    mode = "complete"
    Await.result(client().sign("vault-1", Curve.Ed25519, "m/1852'/1815'/0'/0/0", Array[Byte](1), HashAlgo.None), 2.seconds)
    val msg = ujson.read(lastPostBody)("extraParameters")("rawMessageData")("messages")(0)
    msg("algorithm").str shouldBe "MPC_EDDSA_ED25519"

  test("unsupported curves fail before HTTP"):
    val ex = intercept[UnsupportedOperationException] {
      Await.result(client().sign("vault-1", Curve.P256, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    }
    ex.getMessage should include ("does not support P256")

  test("pending transaction polls until completed"):
    mode = "pending-then-complete"
    pollCounter.set(0)
    Await.result(client().sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    pollCounter.get() should be >= 3

  test("failed transaction surfaces subStatus"):
    mode = "failed"
    val ex = intercept[RuntimeException] {
      Await.result(client().sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    }
    ex.getMessage should include ("POLICY_DENIED")

  test("poll timeout fails"):
    mode = "timeout"
    val ex = intercept[RuntimeException] {
      Await.result(client(FireblocksOptions(vaultAccountId = "vault-1", pollIntervalMs = 5, pollMaxAttempts = 2)).sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    }
    ex.getMessage should include ("did not complete within 2 polls")

  test("HTTP error on create transaction fails"):
    mode = "http-500"
    val ex = intercept[RuntimeException] {
      Await.result(client().sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None), 2.seconds)
    }
    ex.getMessage should include ("HTTP 500")

  test("FireblocksVault named constructor returns an MPC vault"):
    val vault = FireblocksVault(apiKey, privatePem, baseUrl, FireblocksOptions(vaultAccountId = "vault-1", pollIntervalMs = 5))
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked shouldBe false

  test("FireblocksPlugin loads via ServiceLoader"):
    val plugins = ServiceLoader.load(classOf[FireblocksPlugin]).iterator().asScala.toSeq
    plugins.map(_.provider) should contain ("fireblocks")

  test("FireblocksPlugin can construct a vault"):
    val plugin = FireblocksPlugin()
    val vault = plugin.vault(apiKey, privatePem, baseUrl, FireblocksOptions(vaultAccountId = "vault-1", pollIntervalMs = 5))
    Await.result(vault.unlock(UnlockCredential.None), 2.seconds)
    vault.isLocked shouldBe false

  test("privateKeyFromPem parses PKCS8 PEM"):
    FireblocksJwt.privateKeyFromPem(privatePem).getAlgorithm shouldBe "RSA"

  test("bodyHash for empty body matches SHA-256 empty vector"):
    FireblocksJwt.sha256Hex(Array.emptyByteArray) shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  test("FireblocksWire parses transaction id from txId fallback"):
    FireblocksWire.parseTransactionId(ujson.Obj("txId" -> "tx-alt")) shouldBe "tx-alt"

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  private def pem(bytes: Array[Byte]): String =
    val b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes("US-ASCII")).encodeToString(bytes)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
