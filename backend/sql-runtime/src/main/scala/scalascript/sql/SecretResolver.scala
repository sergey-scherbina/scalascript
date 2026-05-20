package scalascript.sql

/** Plugin SPI for custom secret backends.
 *
 *  Register an implementation via the standard Java `ServiceLoader`
 *  mechanism: create a file
 *  `META-INF/services/scalascript.sql.SecretResolver` in your JAR
 *  containing the fully-qualified class name of your implementation.
 *  Any JAR on the classpath that declares this service is picked up
 *  automatically — no SSC configuration needed.
 *
 *  Once registered, reference secrets in `databases:` front-matter
 *  using `${"<scheme>:<ref>}`, where `<scheme>` matches [[scheme]]:
 *
 *  {{{
 *  databases:
 *    prod:
 *      url:      "jdbc:postgresql://db:5432/myapp"
 *      user:     "${env:DB_USER}"
 *      password: "${vault:secret/myapp/db#password}"
 *  }}}
 *
 *  The built-in schemes `env` and `file` are reserved and cannot be
 *  overridden by plugins.
 *
 *  === Implementing a HashiCorp Vault resolver ===
 *
 *  {{{
 *  // in your vault-plugin JAR:
 *  class VaultResolver extends SecretResolver:
 *    val scheme = "vault"
 *    def resolve(ref: String): String =
 *      // ref = "secret/myapp/db#password"
 *      val (path, field) = ref.span(_ != '#')
 *      VaultClient.read(path).data(field.drop(1))
 *
 *  // META-INF/services/scalascript.sql.SecretResolver:
 *  //   com.example.VaultResolver
 *  }}}
 *
 *  === Implementing an AWS Secrets Manager resolver ===
 *
 *  {{{
 *  class AwsSmResolver extends SecretResolver:
 *    val scheme = "aws-secret"
 *    def resolve(ref: String): String =
 *      // ref = "prod/myapp/db#password"
 *      val (name, field) = ref.span(_ != '#')
 *      val client = SecretsManagerClient.create()
 *      val json   = client.getSecretValue(r => r.secretId(name)).secretString()
 *      ujson.read(json)(field.drop(1)).str
 *  }}}
 */
trait SecretResolver:
  /** Scheme prefix this resolver handles (e.g. `"vault"`, `"aws-secret"`).
   *  Must be unique across all registered resolvers.
   *  The built-in values `"env"` and `"file"` are reserved. */
  def scheme: String

  /** Resolve `ref` (the part after the colon in `${scheme:ref}`) to
   *  a secret value.  Called only when `scheme` matches.
   *  Throw a descriptive exception on failure — returning an empty
   *  string is almost never the right behaviour for a missing secret. */
  def resolve(ref: String): String
