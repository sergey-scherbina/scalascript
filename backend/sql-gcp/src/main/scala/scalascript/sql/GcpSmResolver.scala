package scalascript.sql

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.api.gax.rpc.{NotFoundException, PermissionDeniedException, UnavailableException}

/** Google Cloud Secret Manager secret resolver.
 *
 *  Reference format:
 *  - Full resource name: `${gcp-secret:projects/my-project/secrets/db-password/versions/latest}`
 *  - Shorthand (project from env): `${gcp-secret:db-password}`
 *  - Shorthand with version: `${gcp-secret:db-password/versions/3}`
 *
 *  Configuration:
 *  - `GOOGLE_CLOUD_PROJECT` — project ID for shorthand refs (required unless using full names)
 *  - `GOOGLE_APPLICATION_CREDENTIALS` — path to service account JSON (optional; ADC chain)
 *
 *  ServiceLoader registration:
 *  `META-INF/services/scalascript.sql.SecretResolver`
 *  → `scalascript.sql.GcpSmResolver`
 */
class GcpSmResolver extends SecretResolver:
  val scheme = "gcp-secret"

  protected def gcpProject: Option[String] = sys.env.get("GOOGLE_CLOUD_PROJECT")

  protected def fetchPayload(resourceName: String): String =
    val client = SecretManagerServiceClient.create()
    try client.accessSecretVersion(resourceName).getPayload.getData.toStringUtf8
    finally client.close()

  def resolve(ref: String): String =
    val name = expandRef(ref)
    try fetchPayload(name)
    catch
      case e: NotFoundException =>
        throw RuntimeException(
          s"gcp-secret: secret version '$name' not found — verify the secret name, project, and version",
          e
        )
      case e: PermissionDeniedException =>
        throw RuntimeException(
          s"gcp-secret: permission denied accessing '$name'" +
          " — ensure the service account has roles/secretmanager.secretAccessor",
          e
        )
      case e: UnavailableException =>
        throw RuntimeException(
          s"gcp-secret: GCP Secret Manager unavailable fetching '$name': ${e.getMessage}",
          e
        )

  /** Expand a shorthand ref to a full resource name.
   *
   *  Rules:
   *  - Already full (`projects/...`) → returned as-is
   *  - `secret-name` → `projects/$project/secrets/secret-name/versions/latest`
   *  - `secret-name/versions/N` → `projects/$project/secrets/secret-name/versions/N`
   */
  private def expandRef(ref: String): String =
    if ref.startsWith("projects/") then ref
    else
      val project = gcpProject.getOrElse(
        throw RuntimeException(
          "gcp-secret: GOOGLE_CLOUD_PROJECT is not set" +
          " — export GOOGLE_CLOUD_PROJECT=<project-id> or use a full resource name" +
          " (projects/P/secrets/S/versions/V)"
        )
      )
      if ref.contains("/versions/") then
        val slashIdx = ref.indexOf('/')
        val secretName = ref.substring(0, slashIdx)
        val versionPart = ref.substring(slashIdx + 1)
        s"projects/$project/secrets/$secretName/$versionPart"
      else
        s"projects/$project/secrets/$ref/versions/latest"
