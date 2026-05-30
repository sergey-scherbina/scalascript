package scalascript.wallet.vault.mpc.lit

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scalascript.crypto.{Curve, HashAlgo}

class LitRemoteSigningClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val pkpPublicKey = "0x04aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677"
  private val authSig      = """{"sig":"0xdeadbeef","derivedVia":"web3.eth.personal.sign","signedMessage":"I am an authorized user","address":"0xabc"}"""
  private val cannedSig    = Array[Byte](0xab.toByte, 0xcd.toByte, 0xef.toByte, 0x01.toByte)
  private val cannedSigHex = "0x" + cannedSig.map(b => f"${b & 0xff}%02x").mkString

  @volatile private var lastBody     = ""
  @volatile private var serverMode   = "ok"

  private var server: HttpServer = scala.compiletime.uninitialized
  private var baseUrl: String    = ""

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  private def handle(ex: HttpExchange): Unit =
    val raw = ex.getRequestBody.readAllBytes()
    lastBody = new String(raw, "UTF-8")
    ex.getRequestURI.getPath match
      case "/health" =>
        if serverMode == "health-down" then writeJson(ex, 503, ujson.Obj("status" -> "down"))
        else writeJson(ex, 200, ujson.Obj("status" -> "ok"))
      case "/web3/pkp/list" =>
        writeJson(ex, 200, ujson.Obj(
          "pkps" -> ujson.Arr(
            ujson.Obj("publicKey" -> pkpPublicKey, "name" -> "primary"),
          )
        ))
      case "/web3/pkp/sign" =>
        if serverMode == "sign-error" then
          writeJson(ex, 500, ujson.Obj("error" -> "node error"))
        else
          writeJson(ex, 200, ujson.Obj(
            "signatures" -> ujson.Obj(
              "sig1" -> ujson.Obj(
                "r"         -> "aabb",
                "s"         -> "ccdd",
                "signature" -> cannedSigHex,
              )
            )
          ))
      case _ =>
        writeJson(ex, 404, ujson.Obj("error" -> "not found"))

  override def beforeAll(): Unit =
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/", ex => handle(ex))
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    server.stop(0)

  private def client(mode: String = "ok"): LitRemoteSigningClient =
    serverMode = mode
    LitRemoteSigningClient(
      baseUrl = baseUrl,
      options = LitOptions(
        pkpPublicKey   = pkpPublicKey,
        authSig        = authSig,
        sigName        = "sig1",
        pollIntervalMs = 50L,
        timeoutMs      = 5_000L,
      ),
    )

  test("LitPlugin.provider returns 'lit'"):
    LitPlugin().provider shouldBe "lit"

  test("LitVault creates McpVault with correct id"):
    val vault = LitVault(baseUrl, pkpPublicKey, authSig,
      LitOptions(pkpPublicKey = pkpPublicKey, authSig = authSig))
    vault.id should startWith("lit:")
    vault.id should include(pkpPublicKey)

  test("health returns true when server is up"):
    Await.result(client().health(), 5.seconds) shouldBe true

  test("health returns false when server is down"):
    Await.result(client("health-down").health(), 5.seconds) shouldBe false

  test("listAccounts returns PKP from server"):
    val accounts = Await.result(client().listAccounts(), 5.seconds)
    accounts should have size 1
    accounts.head.id shouldBe pkpPublicKey
    accounts.head.label shouldBe "primary"

  test("sign calls /web3/pkp/sign and returns signature bytes"):
    val result = Await.result(
      client().sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1, 2, 3), HashAlgo.Keccak256),
      5.seconds,
    )
    result shouldBe cannedSig

  test("sign request body contains pkpPublicKey"):
    Await.result(
      client().sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1, 2, 3), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include(pkpPublicKey)

  test("sign request body contains toSign bytes"):
    Await.result(
      client().sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](7, 8, 9), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include("7")
    lastBody should include("8")
    lastBody should include("9")

  test("sign request body contains authSig"):
    Await.result(
      client().sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include("authorized user")

  test("sign request body contains sigName"):
    Await.result(
      client().sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256),
      5.seconds,
    )
    lastBody should include("sig1")

  test("sign fails when server returns error"):
    val f = client("sign-error").sign(pkpPublicKey, Curve.Secp256k1, "m/44'/60'/0'/0/0", Array[Byte](1), HashAlgo.Sha256)
    intercept[RuntimeException] { Await.result(f, 5.seconds) }.getMessage should include("500")

  test("LitWire.litCurve maps Secp256k1 to K256"):
    LitWire.litCurve(Curve.Secp256k1) shouldBe "K256"

  test("LitWire.litCurve maps Ed25519"):
    LitWire.litCurve(Curve.Ed25519) shouldBe "ed25519"

  test("LitWire.litCurve maps P256"):
    LitWire.litCurve(Curve.P256) shouldBe "P256"

  test("LitWire.parseSignature parses signature from nested signatures map"):
    val response = ujson.Obj(
      "signatures" -> ujson.Obj(
        "sig1" -> ujson.Obj("signature" -> "0xdeadbeef", "r" -> "de", "s" -> "ad")
      )
    )
    val result = LitWire.parseSignature(response, "sig1")
    result shouldBe Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)

  test("LitWire.parsePkpList returns fallback account when list empty"):
    val response = ujson.Obj("pkps" -> ujson.Arr())
    val result   = LitWire.parsePkpList(response, pkpPublicKey)
    result should have size 1
    result.head.id shouldBe pkpPublicKey
