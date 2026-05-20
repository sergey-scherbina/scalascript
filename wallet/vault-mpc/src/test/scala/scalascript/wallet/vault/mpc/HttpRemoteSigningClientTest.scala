package scalascript.wallet.vault.mpc

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.atomic.AtomicInteger
import scalascript.crypto.{Curve, HashAlgo}

/** Spins up a real `com.sun.net.httpserver.HttpServer` on `localhost:0`
 *  and points `HttpRemoteSigningClient` at it. Verifies wire-level
 *  behaviour: JSON serialisation, sync 200 path, async 202+poll path,
 *  HTTP error handling, polling timeout. */
class HttpRemoteSigningClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private var server: HttpServer = scala.compiletime.uninitialized
  private var baseUrl: String    = ""

  // Per-test mutable state — reset between tests via the `nextScenario`
  // hook in beforeEach is overkill; just use atomic counters and the
  // currentScenario var to switch behaviour.
  @volatile private var pollAttemptsBeforeReady: Int    = 0
  private val pollAttemptCounter                       = new AtomicInteger(0)
  @volatile private var lastSignBody: String           = ""
  @volatile private var lastAuthHeader: String         = ""

  // The signature the server claims to have produced.
  private val cannedSig: Array[Byte] = Array[Byte](0x30, 0x44, 0x02, 0x20, 0x01, 0x02, 0x03)

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    server.createContext("/v1/accounts", (ex: HttpExchange) =>
      val body =
        ujson.Obj(
          "accounts" -> ujson.Arr(
            ujson.Obj(
              "id"    -> "vault-1",
              "label" -> "Test",
              "publicKeys" -> ujson.Obj(
                "secp256k1" -> MpcSerialization.b64encode(Array.fill[Byte](33)(0x02))
              )
            )
          )
        )
      writeJson(ex, 200, body)
    )

    server.createContext("/v1/accounts/vault-1/sign", (ex: HttpExchange) =>
      // Capture the request for assertions.
      lastAuthHeader = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      lastSignBody   = new String(ex.getRequestBody.readAllBytes())
      // 5xx-trigger via X-Test-Fail header (used in error test).
      val testFail = Option(ex.getRequestHeaders.getFirst("X-Test-Fail")).getOrElse("")
      val testMode = Option(ex.getRequestHeaders.getFirst("X-Test-Mode")).getOrElse("sync")
      if testFail == "true" then
        writeJson(ex, 500, ujson.Obj("error" -> "internal server error"))
      else if testMode == "async-fail" then
        writeJson(ex, 202, ujson.Obj("operationId" -> "op-fail"))
      else if testMode == "async" then
        pollAttemptCounter.set(0)
        writeJson(ex, 202, ujson.Obj("operationId" -> "op-42"))
      else
        writeJson(ex, 200, ujson.Obj(
          "status"    -> "completed",
          "signature" -> MpcSerialization.b64encode(cannedSig),
        ))
    )

    server.createContext("/v1/operations/op-42", (ex: HttpExchange) =>
      val n = pollAttemptCounter.incrementAndGet()
      if n >= pollAttemptsBeforeReady then
        writeJson(ex, 200, ujson.Obj(
          "status"    -> "completed",
          "signature" -> MpcSerialization.b64encode(cannedSig),
        ))
      else
        writeJson(ex, 200, ujson.Obj("status" -> "pending"))
    )

    server.createContext("/v1/operations/op-fail", (ex: HttpExchange) =>
      writeJson(ex, 200, ujson.Obj(
        "status" -> "failed",
        "error"  -> "quorum unreachable",
      ))
    )

    server.createContext("/health", (ex: HttpExchange) =>
      writeJson(ex, 200, ujson.Obj("ok" -> true))
    )

    server.setExecutor(null)
    server.start()
    val port = server.getAddress.getPort
    baseUrl  = s"http://127.0.0.1:$port"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def writeJson(ex: HttpExchange, status: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(status, bytes.length.toLong)
    val os = ex.getResponseBody
    os.write(bytes)
    os.close()

  // ─── tests ──────────────────────────────────────────────────────────

  test("health returns true when server returns 200"):
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1")
    Await.result(client.health(), 2.seconds) shouldBe true

  test("listAccounts decodes JSON to McpAccount with curve mapping"):
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1")
    val accts  = Await.result(client.listAccounts(), 2.seconds)
    accts.size                                  shouldBe 1
    accts.head.id                               shouldBe "vault-1"
    accts.head.label                            shouldBe "Test"
    accts.head.publicKeys.keys                  should contain (Curve.Secp256k1)
    accts.head.publicKeys(Curve.Secp256k1).length shouldBe 33

  test("synchronous sign — 200 with completed signature"):
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1")
    val sig    = Await.result(
      client.sign("vault-1", Curve.Secp256k1, "m/44'/60'/0'/0/0",
        Array[Byte](0x11, 0x22, 0x33), HashAlgo.Keccak256),
      2.seconds,
    )
    sig shouldBe cannedSig

    // Verify request shape.
    val body = ujson.read(lastSignBody)
    body("curve").str          shouldBe "secp256k1"
    body("derivationPath").str shouldBe "m/44'/60'/0'/0/0"
    body("hashAlgo").str       shouldBe "keccak256"
    MpcSerialization.b64decode(body("payload").str) shouldBe Array[Byte](0x11, 0x22, 0x33)
    lastAuthHeader shouldBe "Bearer tok-abc"

  test("async sign — 202 → poll → 200 completed signature"):
    // Use the same /sign endpoint with X-Test-Mode: async; intercept by
    // building the client subclass that injects the header.
    pollAttemptsBeforeReady = 3
    val client = new HttpRemoteSigningClient(
      baseUrl, "tok-abc", "vault-1",
      HttpRemoteSigningOptions(pollIntervalMs = 20L, pollMaxAttempts = 30),
    ) {
      override protected def decorateRequest(b: java.net.http.HttpRequest.Builder) =
        super.decorateRequest(b).header("X-Test-Mode", "async")
    }
    val sig = Await.result(
      client.sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
      5.seconds,
    )
    sig shouldBe cannedSig
    pollAttemptCounter.get should be >= 3

  test("async sign — poll says failed → Future fails"):
    // The test stub returns 202→op-fail when X-Test-Mode is "async-fail",
    // and /v1/operations/op-fail always reports status=failed.
    val client = new HttpRemoteSigningClient(
      baseUrl, "tok-abc", "vault-1",
      HttpRemoteSigningOptions(pollIntervalMs = 5L, pollMaxAttempts = 10),
    ) {
      override protected def decorateRequest(b: java.net.http.HttpRequest.Builder) =
        super.decorateRequest(b).header("X-Test-Mode", "async-fail")
    }
    val ex = intercept[RuntimeException] {
      Await.result(
        client.sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
        5.seconds,
      )
    }
    ex.getMessage should include ("quorum unreachable")

  test("HTTP 5xx surfaces as a failed Future"):
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1") {
      override protected def decorateRequest(b: java.net.http.HttpRequest.Builder) =
        super.decorateRequest(b).header("X-Test-Fail", "true")
    }
    val ex = intercept[RuntimeException] {
      Await.result(
        client.sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
        3.seconds,
      )
    }
    ex.getMessage should include ("HTTP 500")

  test("polling timeout — pollMaxAttempts exceeded fails the Future"):
    // Server says "not ready" forever; client gives up after maxAttempts.
    pollAttemptsBeforeReady = Int.MaxValue
    val client = new HttpRemoteSigningClient(
      baseUrl, "tok-abc", "vault-1",
      HttpRemoteSigningOptions(pollIntervalMs = 5L, pollMaxAttempts = 3),
    ) {
      override protected def decorateRequest(b: java.net.http.HttpRequest.Builder) =
        super.decorateRequest(b).header("X-Test-Mode", "async")
    }
    val ex = intercept[RuntimeException] {
      Await.result(
        client.sign("vault-1", Curve.Secp256k1, "m", Array[Byte](1), HashAlgo.None),
        5.seconds,
      )
    }
    ex.getMessage should include ("did not complete within 3 polls")

  test("forgetToken clears the Authorization header"):
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1")
    client.forgetToken()
    // After token is cleared, the sign request should still go but with
    // no Authorization header; capture and assert.
    lastAuthHeader = "untouched"
    Await.result(
      client.sign("vault-1", Curve.Secp256k1, "m", Array[Byte](7), HashAlgo.None),
      3.seconds,
    )
    lastAuthHeader shouldBe ""

  test("McpVault driven by a real HttpRemoteSigningClient — end-to-end"):
    given ExecutionContext = scala.concurrent.ExecutionContext.global
    val client = new HttpRemoteSigningClient(baseUrl, "tok-abc", "vault-1")
    val vault  = new McpVault("e2e", client)
    Await.result(vault.unlock(scalascript.wallet.spi.UnlockCredential.None), 3.seconds)
    vault.isLocked shouldBe false
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 3.seconds)
    val sig    = Await.result(signer.sign(Array[Byte](0xa, 0xb), HashAlgo.Keccak256), 3.seconds)
    sig shouldBe cannedSig
