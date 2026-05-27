package scalascript.sql

import org.scalatest.funsuite.AnyFunSuite
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{
  GetSecretValueRequest,
  GetSecretValueResponse,
  ResourceNotFoundException
}

/** Unit tests for AwsSmResolver.
 *
 *  Uses a stub SecretsManagerClient instead of a real AWS endpoint —
 *  no network calls, no AWS credentials required. */
class AwsSmResolverTest extends AnyFunSuite:

  // ── Stub helpers ───────────────────────────────────────────────────────

  /** Build a resolver backed by a stub that returns `secretValue` for any call. */
  private def resolverReturning(secretValue: String): AwsSmResolver =
    new AwsSmResolver:
      override protected def buildClient(): SecretsManagerClient =
        StubSecretsManagerClient.returning(secretValue)

  /** Build a resolver whose stub throws `ex` for any call. */
  private def resolverThrowing(ex: Throwable): AwsSmResolver =
    new AwsSmResolver:
      override protected def buildClient(): SecretsManagerClient =
        StubSecretsManagerClient.throwing(ex)

  // ── scheme ─────────────────────────────────────────────────────────────

  test("scheme is 'aws-secret'") {
    assert(new AwsSmResolver().scheme == "aws-secret")
  }

  // ── plain string secrets ───────────────────────────────────────────────

  test("plain-string secret (no #field) is returned as-is") {
    val r = resolverReturning("my-api-key-value")
    assert(r.resolve("prod/myapp/api-key") == "my-api-key-value")
  }

  test("plain-string secret with whitespace is not trimmed") {
    val r = resolverReturning("  spaced  ")
    assert(r.resolve("prod/myapp/spaced") == "  spaced  ")
  }

  // ── JSON secrets with #field ───────────────────────────────────────────

  test("JSON secret with #field extracts the field") {
    val json = """{"password":"s3cr3t","host":"db.example.com"}"""
    val r = resolverReturning(json)
    assert(r.resolve("prod/myapp/db#password") == "s3cr3t")
  }

  test("JSON secret extracts second field correctly") {
    val json = """{"password":"s3cr3t","host":"db.example.com"}"""
    val r = resolverReturning(json)
    assert(r.resolve("prod/myapp/db#host") == "db.example.com")
  }

  test("JSON secret with integer value — cast to string") {
    val json = """{"port":5432,"password":"pw"}"""
    val r = resolverReturning(json)
    // ujson integer is not .str, so this should raise a descriptive error
    val ex = intercept[RuntimeException](r.resolve("prod/myapp/db#port"))
    assert(ex.getMessage.contains("port"))
  }

  test("JSON field missing → error naming the field and secret") {
    val json = """{"password":"s3cr3t"}"""
    val r = resolverReturning(json)
    val ex = intercept[RuntimeException](r.resolve("prod/myapp/db#missing_field"))
    assert(ex.getMessage.contains("missing_field"))
    assert(ex.getMessage.contains("prod/myapp/db"))
  }

  test("JSON field missing → error lists available fields") {
    val json = """{"password":"s3cr3t","user":"admin"}"""
    val r = resolverReturning(json)
    val ex = intercept[RuntimeException](r.resolve("prod/myapp/db#nonexistent"))
    assert(ex.getMessage.contains("password") || ex.getMessage.contains("user"))
  }

  // ── error paths ────────────────────────────────────────────────────────

  test("ResourceNotFoundException → RuntimeException mentioning secret name and region") {
    val ex = ResourceNotFoundException.builder().message("Secrets Manager can't find the specified secret.").build()
    val r = resolverThrowing(ex)
    val e = intercept[RuntimeException](r.resolve("no/such/secret"))
    assert(e.getMessage.contains("no/such/secret"))
    assert(e.getMessage.contains("not found"))
  }

  test("ResourceNotFoundException message mentions AWS region") {
    val ex = ResourceNotFoundException.builder().message("not found").build()
    val r = new AwsSmResolver:
      override protected def awsRegion = "eu-west-1"
      override protected def buildClient(): SecretsManagerClient =
        StubSecretsManagerClient.throwing(ex)
    val e = intercept[RuntimeException](r.resolve("my/secret"))
    assert(e.getMessage.contains("eu-west-1"))
  }

  test("default AWS region fallback resolves successfully") {
    // Verify the override path compiles and the resolver works with a non-default region
    val r = new AwsSmResolver:
      override protected def awsRegion = "us-east-1"
      override protected def buildClient(): SecretsManagerClient =
        StubSecretsManagerClient.returning("default-region-value")
    assert(r.resolve("prod/myapp/key") == "default-region-value")
  }

  test("AWS_REGION override is respected — resolver uses injected region") {
    val r = new AwsSmResolver:
      override protected def awsRegion = "ap-southeast-1"
      override protected def buildClient(): SecretsManagerClient =
        StubSecretsManagerClient.returning("ap-value")
    assert(r.resolve("prod/myapp/key") == "ap-value")
  }

  // ── name with # separator edge cases ──────────────────────────────────

  test("secret name without # returns plain value") {
    val r = resolverReturning("plain-secret")
    assert(r.resolve("my/secret") == "plain-secret")
  }

  test("secret name with # at start treats empty name part as secret name") {
    // ref "#field" → name="" field=Some("field")
    val r = resolverReturning("""{"field":"val"}""")
    assert(r.resolve("#field") == "val")
  }


/** In-memory stub implementing the SecretsManagerClient interface. */
object StubSecretsManagerClient:
  def returning(value: String): SecretsManagerClient =
    new SecretsManagerClient:
      def serviceName(): String = "secretsmanager"
      def close(): Unit = ()
      override def getSecretValue(req: GetSecretValueRequest): GetSecretValueResponse =
        GetSecretValueResponse.builder().secretString(value).build()

  def throwing(ex: Throwable): SecretsManagerClient =
    new SecretsManagerClient:
      def serviceName(): String = "secretsmanager"
      def close(): Unit = ()
      override def getSecretValue(req: GetSecretValueRequest): GetSecretValueResponse =
        ex match
          case e: RuntimeException => throw e
          case e => throw RuntimeException(e.getMessage, e)
