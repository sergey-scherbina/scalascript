package scalascript.sql

import org.scalatest.funsuite.AnyFunSuite
import com.google.api.gax.rpc.{NotFoundException, PermissionDeniedException, UnavailableException}

/** Unit tests for GcpSmResolver.
 *
 *  Overrides `fetchPayload` to avoid creating a real GCP client — no
 *  credentials or network required. */
class GcpSmResolverTest extends AnyFunSuite:

  private def resolverReturning(value: String, project: Option[String] = Some("my-project")): GcpSmResolver =
    new GcpSmResolver:
      override protected def gcpProject: Option[String] = project
      override protected def fetchPayload(name: String): String = value

  private def resolverThrowing(ex: Throwable, project: Option[String] = Some("my-project")): GcpSmResolver =
    new GcpSmResolver:
      override protected def gcpProject: Option[String] = project
      override protected def fetchPayload(name: String): String = throw ex

  test("scheme is 'gcp-secret'") {
    assert(new GcpSmResolver().scheme == "gcp-secret")
  }

  test("shorthand ref resolves using GOOGLE_CLOUD_PROJECT") {
    val r = resolverReturning("secret-value")
    assert(r.resolve("my-secret") == "secret-value")
  }

  test("shorthand ref with version expands correctly") {
    val r = resolverReturning("versioned-value")
    assert(r.resolve("my-secret/versions/3") == "versioned-value")
  }

  test("full resource name passed through as-is") {
    val r = new GcpSmResolver:
      override protected def gcpProject: Option[String] = None
      override protected def fetchPayload(name: String): String = "direct-value"
    assert(r.resolve("projects/my-project/secrets/my-secret/versions/latest") == "direct-value")
  }

  test("shorthand expands to correct full resource name") {
    var capturedName = ""
    val r = new GcpSmResolver:
      override protected def gcpProject: Option[String] = Some("test-proj")
      override protected def fetchPayload(name: String): String = { capturedName = name; "val" }
    r.resolve("my-secret")
    assert(capturedName == "projects/test-proj/secrets/my-secret/versions/latest")
  }

  test("shorthand with version expands to correct full resource name") {
    var capturedName = ""
    val r = new GcpSmResolver:
      override protected def gcpProject: Option[String] = Some("test-proj")
      override protected def fetchPayload(name: String): String = { capturedName = name; "val" }
    r.resolve("my-secret/versions/5")
    assert(capturedName == "projects/test-proj/secrets/my-secret/versions/5")
  }

  test("shorthand ref without GOOGLE_CLOUD_PROJECT → error naming the env var") {
    val r = resolverReturning("v", project = None)
    val ex = intercept[RuntimeException](r.resolve("my-secret"))
    assert(ex.getMessage.contains("GOOGLE_CLOUD_PROJECT"))
    assert(ex.getMessage.contains("not set"))
  }

  test("missing project error suggests full resource name format") {
    val r = resolverReturning("v", project = None)
    val ex = intercept[RuntimeException](r.resolve("my-secret"))
    assert(ex.getMessage.contains("projects/P/secrets/S/versions/V"))
  }

  test("NotFoundException → RuntimeException mentioning not found") {
    val ex = new NotFoundException(new RuntimeException("NOT_FOUND"), null, false)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("missing-secret"))
    assert(e.getMessage.contains("not found"))
  }

  test("NotFoundException error contains the secret ref") {
    val ex = new NotFoundException(new RuntimeException("NOT_FOUND"), null, false)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("missing-secret"))
    assert(e.getMessage.contains("missing-secret") || e.getMessage.contains("my-project"))
  }

  test("PermissionDeniedException → error mentions secretmanager.secretAccessor") {
    val ex = new PermissionDeniedException(new RuntimeException("PERMISSION_DENIED"), null, false)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("locked-secret"))
    assert(e.getMessage.contains("permission denied") || e.getMessage.contains("Permission"))
    assert(e.getMessage.contains("secretAccessor") || e.getMessage.contains("secretmanager"))
  }

  test("UnavailableException → error mentions unavailable") {
    val ex = new UnavailableException(new RuntimeException("UNAVAILABLE"), null, false)
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("some-secret"))
    assert(e.getMessage.contains("unavailable") || e.getMessage.contains("Unavailable"))
  }

  test("UTF-8 string value is returned verbatim") {
    val r = resolverReturning("unicode-value-é")
    assert(r.resolve("projects/p/secrets/s/versions/latest") == "unicode-value-é")
  }

  test("plain ASCII secret is returned as-is") {
    val r = resolverReturning("plain ASCII secret")
    assert(r.resolve("projects/p/secrets/s/versions/1") == "plain ASCII secret")
  }
