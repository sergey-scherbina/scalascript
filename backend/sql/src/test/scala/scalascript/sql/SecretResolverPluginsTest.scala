package scalascript.sql

import com.sun.net.httpserver.{HttpServer, HttpExchange}
import java.net.InetSocketAddress
import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for the four JDK-based SecretResolver plugins.
 *
 *  Vault + Doppler: uses `com.sun.net.httpserver.HttpServer` (JDK built-in)
 *  as a local mock — no external infrastructure required.
 *
 *  Op + Pass: exercises error paths via subprocess hooks without requiring
 *  the actual CLI tools to be installed. */
class SecretResolverPluginsTest extends AnyFunSuite:

  // ── Mock HTTP server helper ────────────────────────────────────────────

  /** Runs `f(port)` with a local HTTP server that replies with `status`/`body`. */
  private def withMockHttp(status: Int, body: String)(f: Int => Unit): Unit =
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    server.createContext("/", (ex: HttpExchange) =>
      val bytes = body.getBytes("UTF-8")
      ex.sendResponseHeaders(status, bytes.length)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    )
    server.start()
    try f(server.getAddress.getPort)
    finally server.stop(0)

  // ── VaultSecretResolver ─────────────────────────────────────────────────

  private val kvV2Body = """{"data":{"data":{"db_password":"s3cr3t","api_key":"k123"}}}"""
  private val kvV1Body = """{"data":{"db_password":"s3cr3t","api_key":"k123"}}"""

  private def vaultAt(port: Int, tok: String = "test-token"): VaultSecretResolver =
    new VaultSecretResolver:
      override protected def vaultAddr      = s"http://127.0.0.1:$port"
      override protected def vaultToken     = tok
      override protected def vaultNamespace = None

  test("vault: scheme is 'vault'") {
    assert(new VaultSecretResolver().scheme == "vault")
  }

  test("vault: KV v2 response — extracts field from data.data") {
    withMockHttp(200, kvV2Body) { port =>
      assert(vaultAt(port).resolve("secret/data/myapp#db_password") == "s3cr3t")
    }
  }

  test("vault: KV v1 response — extracts field from data directly") {
    withMockHttp(200, kvV1Body) { port =>
      assert(vaultAt(port).resolve("secret/myapp#api_key") == "k123")
    }
  }

  test("vault: 404 → RuntimeException mentioning path not found") {
    withMockHttp(404, """{"errors":[]}""") { port =>
      val ex = intercept[RuntimeException](vaultAt(port).resolve("secret/data/gone#key"))
      assert(ex.getMessage.contains("not found"))
      assert(ex.getMessage.contains("secret/data/gone"))
    }
  }

  test("vault: 403 → RuntimeException mentioning permission denied") {
    withMockHttp(403, """{"errors":["permission denied"]}""") { port =>
      val ex = intercept[RuntimeException](vaultAt(port).resolve("secret/data/x#key"))
      assert(ex.getMessage.contains("permission denied"))
    }
  }

  test("vault: non-200/403/404 status → RuntimeException with status code") {
    withMockHttp(503, "service unavailable") { port =>
      val ex = intercept[RuntimeException](vaultAt(port).resolve("secret/data/x#key"))
      assert(ex.getMessage.contains("503"))
    }
  }

  test("vault: missing # separator in ref → error mentioning #field") {
    withMockHttp(200, kvV2Body) { port =>
      val ex = intercept[RuntimeException](vaultAt(port).resolve("secret/data/myapp"))
      assert(ex.getMessage.contains("#field"))
    }
  }

  test("vault: field absent in secret → error listing available fields") {
    withMockHttp(200, kvV2Body) { port =>
      val ex = intercept[RuntimeException](vaultAt(port).resolve("secret/data/myapp#missing"))
      assert(ex.getMessage.contains("missing"))
      assert(ex.getMessage.contains("db_password") || ex.getMessage.contains("api_key"))
    }
  }

  test("vault: missing VAULT_TOKEN → descriptive RuntimeException") {
    val r = new VaultSecretResolver:
      override protected def vaultToken = throw RuntimeException(
        "VAULT_TOKEN is not set — export VAULT_TOKEN=<your-token> or run `vault login`"
      )
    val ex = intercept[RuntimeException](r.resolve("secret/data/x#key"))
    assert(ex.getMessage.contains("VAULT_TOKEN"))
  }

  // ── DopplerSecretResolver ───────────────────────────────────────────────

  private def dopplerAt(port: Int, tok: String = "dp.st.test"): DopplerSecretResolver =
    new DopplerSecretResolver:
      override protected def dopplerToken   = tok
      override protected def dopplerApiBase = s"http://127.0.0.1:$port"

  private val dopplerBody = """{"DB_PASSWORD":"s3cr3t","API_KEY":"k123"}"""

  test("doppler: scheme is 'doppler'") {
    assert(new DopplerSecretResolver().scheme == "doppler")
  }

  test("doppler: 1-part ref fetches secret by name") {
    withMockHttp(200, dopplerBody) { port =>
      assert(dopplerAt(port).resolve("DB_PASSWORD") == "s3cr3t")
    }
  }

  test("doppler: 3-part ref PROJECT/CONFIG/SECRET fetches secret") {
    withMockHttp(200, dopplerBody) { port =>
      assert(dopplerAt(port).resolve("myapp/production/DB_PASSWORD") == "s3cr3t")
    }
  }

  test("doppler: 2-part ref → descriptive error about expected format") {
    withMockHttp(200, dopplerBody) { port =>
      val ex = intercept[RuntimeException](dopplerAt(port).resolve("project/secret"))
      assert(ex.getMessage.contains("PROJECT/CONFIG/SECRET"))
    }
  }

  test("doppler: 401 response → authentication error message") {
    withMockHttp(401, "") { port =>
      val ex = intercept[RuntimeException](dopplerAt(port).resolve("DB_PASSWORD"))
      assert(ex.getMessage.contains("authentication failed"))
    }
  }

  test("doppler: non-200/non-401 response → error with status code") {
    withMockHttp(503, "unavailable") { port =>
      val ex = intercept[RuntimeException](dopplerAt(port).resolve("DB_PASSWORD"))
      assert(ex.getMessage.contains("503"))
    }
  }

  test("doppler: secret missing from response → error naming the secret") {
    withMockHttp(200, """{"OTHER":"val"}""") { port =>
      val ex = intercept[RuntimeException](dopplerAt(port).resolve("DB_PASSWORD"))
      assert(ex.getMessage.contains("DB_PASSWORD"))
      assert(ex.getMessage.contains("not found"))
    }
  }

  test("doppler: missing DOPPLER_TOKEN → descriptive RuntimeException") {
    val r = new DopplerSecretResolver:
      override protected def dopplerToken = throw RuntimeException(
        "DOPPLER_TOKEN is not set — export DOPPLER_TOKEN=<your-token>"
      )
    val ex = intercept[RuntimeException](r.resolve("X"))
    assert(ex.getMessage.contains("DOPPLER_TOKEN"))
  }

  // ── OpSecretResolver ────────────────────────────────────────────────────

  test("op: scheme is 'op'") {
    assert(new OpSecretResolver().scheme == "op")
  }

  test("op: successful subprocess returns output") {
    val r = new OpSecretResolver:
      override protected def command(ref: String) = Seq("sh", "-c", "printf 'mysecret'")
    assert(r.resolve("Personal/Login/password") == "mysecret")
  }

  test("op: failing subprocess → RuntimeException with op:// ref in message") {
    val r = new OpSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "echo 'not found' >&2; exit 1")
    val ex = intercept[RuntimeException](r.resolve("Personal/Login/password"))
    assert(ex.getMessage.contains("op://Personal/Login/password"))
    assert(ex.getMessage.contains("not found"))
  }

  test("op: exit 127 (command not found) → hint to install CLI") {
    val r = new OpSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "exit 127")
    val ex = intercept[RuntimeException](r.resolve("Vault/Item/field"))
    assert(ex.getMessage.contains("brew install 1password-cli") || ex.getMessage.contains("install"))
  }

  test("op: whitespace is stripped from output") {
    val r = new OpSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "printf '  trimmed  '")
    assert(r.resolve("v/i/f") == "trimmed")
  }

  // ── PassSecretResolver ─────────────────────────────────────────────────

  test("pass: scheme is 'pass'") {
    assert(new PassSecretResolver().scheme == "pass")
  }

  test("pass: returns first line of multi-line output") {
    val r = new PassSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "printf 'secret-password\\nmeta: foo\\nurl: example.com\\n'")
    assert(r.resolve("databases/prod/password") == "secret-password")
  }

  test("pass: failing subprocess → RuntimeException mentioning the ref") {
    val r = new PassSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "echo 'not in the password store' >&2; exit 1")
    val ex = intercept[RuntimeException](r.resolve("databases/prod/password"))
    assert(ex.getMessage.contains("databases/prod/password"))
    assert(ex.getMessage.contains("~/.password-store"))
  }

  test("pass: exit 127 (command not found) → hint to install pass") {
    val r = new PassSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "exit 127")
    val ex = intercept[RuntimeException](r.resolve("db/pw"))
    assert(ex.getMessage.contains("install pass") || ex.getMessage.contains("apt install") || ex.getMessage.contains("brew"))
  }

  test("pass: empty output → descriptive error") {
    val r = new PassSecretResolver:
      override protected def command(ref: String) = Seq("sh", "-c", "true")
    val ex = intercept[RuntimeException](r.resolve("db/pw"))
    assert(ex.getMessage.contains("empty output"))
    assert(ex.getMessage.contains("db/pw"))
  }

  test("pass: GPG decryption failure → hint about keyring") {
    val r = new PassSecretResolver:
      override protected def command(ref: String) =
        Seq("sh", "-c", "echo 'gpg: decryption failed: No secret key' >&2; exit 2")
    val ex = intercept[RuntimeException](r.resolve("db/pw"))
    assert(ex.getMessage.contains("GPG") || ex.getMessage.contains("decryption") || ex.getMessage.contains("keyring"))
  }
