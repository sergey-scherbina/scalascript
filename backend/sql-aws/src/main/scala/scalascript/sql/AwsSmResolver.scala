package scalascript.sql

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{
  DecryptionFailureException,
  InternalServiceErrorException,
  InvalidParameterException,
  InvalidRequestException,
  ResourceNotFoundException
}
import scala.util.Try

/** AWS Secrets Manager secret resolver.
 *
 *  Reference format:
 *  - `${aws-secret:prod/myapp/db#password}` — JSON secret, extract field
 *  - `${aws-secret:prod/myapp/api-key}` — plain-string secret
 *
 *  Configuration (environment):
 *  - `AWS_REGION` — defaults to `us-east-1`
 *  - Standard AWS credential chain: env vars → `~/.aws/credentials` →
 *    ECS task role → EC2 instance profile → Lambda execution role
 *
 *  ServiceLoader registration:
 *  `META-INF/services/scalascript.sql.SecretResolver`
 *  → `scalascript.sql.AwsSmResolver`
 */
class AwsSmResolver extends SecretResolver:
  val scheme = "aws-secret"

  protected def awsRegion: String = sys.env.getOrElse("AWS_REGION", "us-east-1")

  protected def buildClient(): SecretsManagerClient =
    SecretsManagerClient.builder()
      .region(Region.of(awsRegion))
      .build()

  def resolve(ref: String): String =
    val (name, fieldOpt) = splitRef(ref)
    val client = buildClient()
    try
      val secretValue =
        try
          client.getSecretValue(r => r.secretId(name)).secretString()
        catch
          case e: ResourceNotFoundException =>
            throw RuntimeException(
              s"aws-secret: secret '$name' not found — verify the name and AWS region ($awsRegion)",
              e
            )
          case e: DecryptionFailureException =>
            throw RuntimeException(
              s"aws-secret: decryption failed for '$name' — check KMS key policies",
              e
            )
          case e: InternalServiceErrorException =>
            throw RuntimeException(
              s"aws-secret: internal AWS error fetching '$name': ${e.getMessage}",
              e
            )
          case e: InvalidRequestException =>
            throw RuntimeException(
              s"aws-secret: invalid request for '$name': ${e.getMessage}",
              e
            )
          case e: InvalidParameterException =>
            throw RuntimeException(
              s"aws-secret: invalid parameter for '$name': ${e.getMessage}",
              e
            )
      if secretValue == null then
        throw RuntimeException(
          s"aws-secret: secret '$name' has no string value — binary secrets are not supported"
        )
      fieldOpt match
        case None => secretValue
        case Some(field) =>
          Try(ujson.read(secretValue)(field).str).getOrElse(
            throw RuntimeException(
              s"aws-secret: field '$field' not found in secret '$name'" +
              s" — available: ${Try(ujson.read(secretValue).obj.keys.mkString(", ")).getOrElse("(not a JSON object)")}"
            )
          )
    finally
      client.close()

  /** Split `name` from optional `#field` suffix.
   *  Returns `(name, None)` for plain refs, `(name, Some(field))` for JSON refs. */
  private def splitRef(ref: String): (String, Option[String]) =
    val i = ref.lastIndexOf('#')
    if i < 0 then (ref, None)
    else (ref.substring(0, i), Some(ref.substring(i + 1)))
