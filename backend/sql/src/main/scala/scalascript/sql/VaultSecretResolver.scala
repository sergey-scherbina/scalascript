package scalascript.sql

import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import scala.util.Try

/** HashiCorp Vault KV secret resolver.
 *
 *  Reference format: `${vault:secret/data/myapp#field}` (KV v2) or
 *  `${vault:secret/myapp#field}` (KV v1 — auto-detected from response shape).
 *
 *  Environment:
 *  - `VAULT_ADDR`      — default `http://127.0.0.1:8200`
 *  - `VAULT_TOKEN`     — required; service token or root token
 *  - `VAULT_NAMESPACE` — optional; enterprise namespace header
 */
class VaultSecretResolver extends SecretResolver:
  val scheme = "vault"

  protected def vaultAddr:      String         = sys.env.getOrElse("VAULT_ADDR", "http://127.0.0.1:8200").stripSuffix("/")
  protected def vaultToken:     String         = sys.env.getOrElse("VAULT_TOKEN",
    throw RuntimeException(
      "VAULT_TOKEN is not set — export VAULT_TOKEN=<your-token> or run `vault login`"
    )
  )
  protected def vaultNamespace: Option[String] = sys.env.get("VAULT_NAMESPACE")

  def resolve(ref: String): String =
    val (path, field) = splitField(ref)
    val json = fetchPath(path)
    val data = Try(json("data")("data")).getOrElse(
      Try(json("data")).getOrElse(
        throw RuntimeException(s"vault: unexpected response shape for path $path")
      )
    )
    Try(data(field).str).getOrElse(
      throw RuntimeException(
        s"vault: field '$field' not found in secret at path $path" +
        s" — available: ${Try(data.obj.keys.mkString(", ")).getOrElse("(unknown)")}"
      )
    )

  private def fetchPath(path: String): ujson.Value =
    val client     = HttpClient.newHttpClient()
    val reqBuilder = HttpRequest.newBuilder(URI.create(s"${vaultAddr}/v1/$path"))
      .header("X-Vault-Token", vaultToken)
      .GET()
    vaultNamespace.foreach(ns => reqBuilder.header("X-Vault-Namespace", ns))
    val resp = client.send(reqBuilder.build(), BodyHandlers.ofString())
    resp.statusCode() match
      case 200 => ujson.read(resp.body())
      case 403 => throw RuntimeException(
          s"vault: permission denied reading path $path — check token policies"
        )
      case 404 => throw RuntimeException(
          s"vault: path not found: $path — verify the secret path and Vault mount"
        )
      case n   => throw RuntimeException(
          s"vault: HTTP $n reading path $path: ${resp.body().take(200)}"
        )

  private def splitField(ref: String): (String, String) =
    val i = ref.lastIndexOf('#')
    if i < 0 then throw RuntimeException(
      s"vault ref must contain #field: '$ref' — example: vault:secret/data/myapp#db_password"
    )
    (ref.substring(0, i), ref.substring(i + 1))
