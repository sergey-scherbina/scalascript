package scalascript.sql

import org.scalatest.funsuite.AnyFunSuite
import com.azure.core.exception.{ResourceNotFoundException, HttpResponseException}
import com.azure.core.http.{HttpHeaders, HttpResponse}

/** Unit tests for AzureKvResolver.
 *
 *  Overrides `fetchSecretValue` to avoid creating a real Azure client — no
 *  credentials or network required. */
class AzureKvResolverTest extends AnyFunSuite:

  // ── Stub helpers ───────────────────────────────────────────────────────

  private def resolverReturning(value: String): AzureKvResolver =
    new AzureKvResolver:
      override protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String = value

  private def resolverThrowing(ex: Throwable): AzureKvResolver =
    new AzureKvResolver:
      override protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String = throw ex

  /** Build an HttpResponseException with the given status code. */
  private def httpEx(status: Int, message: String): HttpResponseException =
    val fakeResponse = new HttpResponse(null):
      override def getStatusCode: Int = status
      override def getHeaderValue(name: String): String = null
      override def getHeaders: HttpHeaders = new HttpHeaders()
      override def getBody: reactor.core.publisher.Flux[java.nio.ByteBuffer] = null
      override def getBodyAsBinaryData(): com.azure.core.util.BinaryData = null
      override def getBodyAsByteArray(): reactor.core.publisher.Mono[Array[Byte]] =
        reactor.core.publisher.Mono.empty()
      override def getBodyAsString(): reactor.core.publisher.Mono[String] =
        reactor.core.publisher.Mono.just(message)
      override def getBodyAsString(charset: java.nio.charset.Charset): reactor.core.publisher.Mono[String] =
        reactor.core.publisher.Mono.just(message)
    new HttpResponseException(message, fakeResponse)

  // ── scheme ─────────────────────────────────────────────────────────────

  test("scheme is 'azure-kv'") {
    assert(new AzureKvResolver().scheme == "azure-kv")
  }

  // ── happy path ─────────────────────────────────────────────────────────

  test("basic ref 'host/secret-name' returns secret value") {
    val r = resolverReturning("my-db-password")
    assert(r.resolve("myvault.vault.azure.net/db-password") == "my-db-password")
  }

  test("versioned ref 'host/secret-name/version' returns secret value") {
    val r = resolverReturning("versioned-value")
    assert(r.resolve("myvault.vault.azure.net/db-password/abc123def456") == "versioned-value")
  }

  test("vault URL is constructed with https:// prefix") {
    var capturedUrl = ""
    val r = new AzureKvResolver:
      override protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String =
        capturedUrl = vaultUrl; "val"
    r.resolve("myvault.vault.azure.net/mysecret")
    assert(capturedUrl == "https://myvault.vault.azure.net")
  }

  test("version is passed correctly to fetchSecretValue") {
    var capturedVersion: Option[String] = None
    val r = new AzureKvResolver:
      override protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String =
        capturedVersion = versionOpt; "val"
    r.resolve("vault.example.com/secret/v1abc")
    assert(capturedVersion == Some("v1abc"))
  }

  test("unversioned ref passes None as version") {
    var capturedVersion: Option[String] = Some("unexpected")
    val r = new AzureKvResolver:
      override protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String =
        capturedVersion = versionOpt; "val"
    r.resolve("vault.example.com/secret")
    assert(capturedVersion == None)
  }

  // ── ref format errors ──────────────────────────────────────────────────

  test("ref without '/' → descriptive error with example") {
    val r = resolverReturning("v")
    val ex = intercept[RuntimeException](r.resolve("no-slash-ref"))
    assert(ex.getMessage.contains("vaultHost/secret-name"))
    assert(ex.getMessage.contains("no-slash-ref"))
  }

  test("ref with empty secret name → descriptive error") {
    val r = resolverReturning("v")
    val ex = intercept[RuntimeException](r.resolve("vault.example.com/"))
    assert(ex.getMessage.contains("empty"))
  }

  // ── error paths ────────────────────────────────────────────────────────

  test("ResourceNotFoundException → RuntimeException mentioning secret name") {
    val ex = new ResourceNotFoundException("Secret not found", null)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("vault.example.com/missing-secret"))
    assert(e.getMessage.contains("missing-secret"))
    assert(e.getMessage.contains("not found"))
  }

  test("ResourceNotFoundException → error mentions vault host") {
    val ex = new ResourceNotFoundException("Secret not found", null)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("myvault.vault.azure.net/some-secret"))
    assert(e.getMessage.contains("myvault.vault.azure.net"))
  }

  test("versioned not-found error includes version info") {
    val ex = new ResourceNotFoundException("Not found", null)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("vault.example.com/mysecret/v1abc"))
    assert(e.getMessage.contains("v1abc") || e.getMessage.contains("mysecret"))
  }

  test("403 response → error mentions 'Key Vault Secrets User' role") {
    val r = resolverThrowing(httpEx(403, "Forbidden"))
    val e = intercept[RuntimeException](r.resolve("vault.example.com/locked"))
    assert(e.getMessage.contains("access denied") || e.getMessage.contains("Access"))
    assert(e.getMessage.contains("Secrets User") || e.getMessage.contains("AZURE_CLIENT_ID"))
  }

  test("non-200/403/404 HTTP error → RuntimeException with status code") {
    val r = resolverThrowing(httpEx(503, "Service Unavailable"))
    val e = intercept[RuntimeException](r.resolve("vault.example.com/any"))
    assert(e.getMessage.contains("503"))
  }
