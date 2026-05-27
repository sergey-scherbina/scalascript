package scalascript.sql

import com.azure.security.keyvault.secrets.SecretClientBuilder
import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.core.exception.{ResourceNotFoundException, HttpResponseException}

/** Azure Key Vault secret resolver.
 *
 *  Reference format:
 *  - `${azure-kv:myvault.vault.azure.net/secret-name}` — latest version
 *  - `${azure-kv:myvault.vault.azure.net/secret-name/abc123def}` — pinned version
 *
 *  Configuration (environment):
 *  - `AZURE_CLIENT_ID`     — service principal application ID
 *  - `AZURE_CLIENT_SECRET` — service principal secret
 *  - `AZURE_TENANT_ID`     — AAD tenant ID
 *  (All consumed automatically by `DefaultAzureCredential`.)
 *
 *  Auth chain (DefaultAzureCredential order):
 *  1. Environment variables above
 *  2. Managed identity
 *  3. Azure CLI (`az login`)
 *  4. Azure Developer CLI, IntelliJ, VS Code credentials
 *
 *  ServiceLoader registration:
 *  `META-INF/services/scalascript.sql.SecretResolver`
 *  → `scalascript.sql.AzureKvResolver`
 */
class AzureKvResolver extends SecretResolver:
  val scheme = "azure-kv"

  /** Fetch a secret value from Azure Key Vault.
   *  Override in tests to avoid real Azure credentials. */
  protected def fetchSecretValue(vaultUrl: String, name: String, versionOpt: Option[String]): String =
    val client = new SecretClientBuilder()
      .vaultUrl(vaultUrl)
      .credential(new DefaultAzureCredentialBuilder().build())
      .buildClient()
    versionOpt match
      case Some(v) => client.getSecret(name, v).getValue
      case None    => client.getSecret(name).getValue

  def resolve(ref: String): String =
    val slashIdx = ref.indexOf('/')
    if slashIdx < 0 then
      throw RuntimeException(
        s"azure-kv: ref must be 'vaultHost/secret-name' or 'vaultHost/secret-name/version'" +
        s" — got: '$ref'" +
        s" — example: myvault.vault.azure.net/my-secret"
      )
    val host = ref.substring(0, slashIdx)
    val rest = ref.substring(slashIdx + 1)
    val (secretName, versionOpt) =
      val i = rest.indexOf('/')
      if i < 0 then (rest, None)
      else (rest.substring(0, i), Some(rest.substring(i + 1)))

    if secretName.isEmpty then
      throw RuntimeException(
        s"azure-kv: secret name is empty in ref '$ref'" +
        s" — example: myvault.vault.azure.net/my-secret"
      )

    val vaultUrl = s"https://$host"
    try fetchSecretValue(vaultUrl, secretName, versionOpt)
    catch
      case e: ResourceNotFoundException =>
        val versionInfo = versionOpt.map(v => s" (version $v)").getOrElse("")
        throw RuntimeException(
          s"azure-kv: secret '$secretName'$versionInfo not found in vault '$host'" +
          s" — verify the secret name and vault URL",
          e
        )
      case e: HttpResponseException if e.getResponse.getStatusCode == 403 =>
        throw RuntimeException(
          s"azure-kv: access denied for secret '$secretName' in vault '$host'" +
          s" — ensure the identity has 'Key Vault Secrets User' role" +
          s" (or check AZURE_CLIENT_ID / AZURE_CLIENT_SECRET / AZURE_TENANT_ID env vars)",
          e
        )
      case e: HttpResponseException =>
        throw RuntimeException(
          s"azure-kv: HTTP ${e.getResponse.getStatusCode} fetching secret" +
          s" '$secretName' from '$host': ${e.getMessage}",
          e
        )
      case e: com.azure.core.exception.ClientAuthenticationException =>
        throw RuntimeException(
          s"azure-kv: authentication failed for vault '$host'" +
          s" — set AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID" +
          s" or run `az login`",
          e
        )
