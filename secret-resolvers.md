# Secret Resolvers — Spec

Secrets in `databases:` front-matter are referenced as `${scheme:ref}`.
Built-in schemes: `env`, `file`, `sops`.
Everything else is provided by a `SecretResolver` plugin JAR on the classpath.

---

## Schemes overview

| Scheme | Reference format | Auth source | Requires |
|--------|-----------------|-------------|---------|
| `env` | `${env:VAR}` | process env | nothing (built-in) |
| `file` | `${file:/run/secrets/pw}` | filesystem | nothing (built-in) |
| `sops` | `${sops:key.path}` | piped stdin YAML | `sops` CLI (external) |
| `vault` | `${vault:secret/path#field}` | `VAULT_TOKEN` env | HTTP client (built-in in JDK 11+) |
| `aws-secret` | `${aws-secret:name#field}` | AWS default creds chain | `software.amazon.awssdk:secretsmanager` |
| `gcp-secret` | `${gcp-secret:projects/P/secrets/S/versions/V}` | Application Default Creds | `com.google.cloud:google-cloud-secretmanager` |
| `azure-kv` | `${azure-kv:vaultHost/secretName}` | `DefaultAzureCredential` | `com.azure:azure-security-keyvault-secrets` |
| `doppler` | `${doppler:PROJECT/CONFIG/SECRET}` | `DOPPLER_TOKEN` env | nothing (JDK HTTP client) |
| `op` | `${op:vault/item/field}` | 1Password desktop app | `op` CLI on PATH |
| `pass` | `${pass:category/name}` | GPG keyring | `pass` CLI on PATH |

---

## Built-in: `sops`

Already implemented.

```bash
sops -d secrets.enc.yaml | ssc myapp.ssc
```

The decrypted YAML is read from stdin at startup, flattened to dotted keys,
and stored in `SopsSecrets`.  Nested maps use `.` as separator; list
elements are keyed by index (`hosts.0`, `hosts.1`, …).

```yaml
# secrets.enc.yaml
db:
  password: "s3cr3t"
TOP_LEVEL: "value"
```

```yaml
# front-matter
databases:
  prod:
    url:      "jdbc:postgresql://db:5432/app"
    password: "${sops:db.password}"
```

**Error:** `MissingSops` — printed with a hint to pipe the file.

**Limitations:** stdin is consumed once at startup; cannot be used together
with `lsp` or `repl` commands.

---

## Plugin: `vault`

HashiCorp Vault via the HTTP API.  No official Java SDK needed — uses
`java.net.http.HttpClient` (JDK 11+).

### Reference format

```
${vault:secret/data/myapp#db_password}
       └────────────────┘ └──────────┘
         KV v2 path         JSON field
```

For KV v1 omit the `data/` segment; the resolver auto-detects by trying
both paths.

### Configuration (environment variables)

| Variable | Default | Notes |
|----------|---------|-------|
| `VAULT_ADDR` | `http://127.0.0.1:8200` | Vault server URL |
| `VAULT_TOKEN` | — | Required. Rotate via `vault login`. |
| `VAULT_NAMESPACE` | — | Enterprise namespaces |
| `VAULT_CACERT` | — | Path to PEM CA bundle for TLS |
| `VAULT_SKIP_VERIFY` | — | Set to `true` to disable TLS verification (dev only) |

### Implementation sketch

```scala
class VaultResolver extends SecretResolver:
  val scheme = "vault"

  private val addr  = sys.env.getOrElse("VAULT_ADDR", "http://127.0.0.1:8200")
  private val token = sys.env.getOrElse("VAULT_TOKEN",
    throw RuntimeException("VAULT_TOKEN is not set"))

  def resolve(ref: String): String =
    val (path, field) = splitField(ref)            // "secret/data/myapp" + "db_password"
    val url  = s"$addr/v1/$path"
    val resp = HttpClient.newHttpClient().send(
      HttpRequest.newBuilder(URI.create(url))
        .header("X-Vault-Token", token)
        .GET().build(),
      BodyHandlers.ofString())
    if resp.statusCode() == 404 then
      throw RuntimeException(s"Vault path not found: $path")
    // KV v2 wraps payload under data.data; v1 under data
    val json = ujson.read(resp.body())
    val data = Try(json("data")("data")).getOrElse(json("data"))
    data(field).str

private def splitField(ref: String): (String, String) =
  val i = ref.lastIndexOf('#')
  if i < 0 then throw RuntimeException(s"vault ref must contain #field: $ref")
  (ref.substring(0, i), ref.substring(i + 1))
```

### Packaging

```
META-INF/services/scalascript.sql.SecretResolver
  → com.example.VaultResolver
```

Deliverable: `vault-secret-resolver.sscpkg` (or plain JAR on classpath).

---

## Plugin: `aws-secret`

AWS Secrets Manager.  Retrieves a secret by name; supports JSON secrets
(field extraction with `#`) and plain strings.

### Reference format

```
${aws-secret:prod/myapp/db#password}
             └─────────────┘ └──────┘
               secret name    JSON field (optional)
```

### Configuration

Uses the AWS SDK default credentials chain in order:
1. Environment variables `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
2. `~/.aws/credentials` profile
3. ECS task role / EC2 instance profile / Lambda execution role

Optional: `AWS_REGION` (defaults to `us-east-1` if unset).

### Implementation sketch

```scala
class AwsSmResolver extends SecretResolver:
  val scheme = "aws-secret"

  private lazy val client =
    SecretsManagerClient.builder()
      .region(Region.of(sys.env.getOrElse("AWS_REGION", "us-east-1")))
      .build()

  def resolve(ref: String): String =
    val (name, fieldOpt) = splitField(ref)
    val value = client.getSecretValue(r => r.secretId(name)).secretString()
    fieldOpt match
      case Some(f) => ujson.read(value)(f).str
      case None    => value
```

### Dependency

```scala
"software.amazon.awssdk" % "secretsmanager" % "2.x"
```

---

## Plugin: `gcp-secret`

Google Cloud Secret Manager.

### Reference format

```
${gcp-secret:projects/my-project/secrets/db-password/versions/latest}
```

Shorthand (project inferred from `GOOGLE_CLOUD_PROJECT` env or ADC metadata):

```
${gcp-secret:db-password}                 → latest version
${gcp-secret:db-password/versions/3}      → specific version
```

### Configuration

Application Default Credentials (ADC):
1. `GOOGLE_APPLICATION_CREDENTIALS` env pointing to a service account JSON
2. `gcloud auth application-default login`
3. GKE Workload Identity / Cloud Run service account

### Implementation sketch

```scala
class GcpSmResolver extends SecretResolver:
  val scheme = "gcp-secret"

  private lazy val client = SecretManagerServiceClient.create()

  def resolve(ref: String): String =
    val name =
      if ref.startsWith("projects/") then ref
      else
        val project = sys.env.getOrElse("GOOGLE_CLOUD_PROJECT",
          throw RuntimeException("GOOGLE_CLOUD_PROJECT not set"))
        val (secret, ver) = ref.span(_ != '/')
        val version = if ver.isEmpty then "/versions/latest" else ver
        s"projects/$project/secrets/$secret$version"
    client.accessSecretVersion(name).getPayload.getData.toStringUtf8
```

### Dependency

```scala
"com.google.cloud" % "google-cloud-secretmanager" % "2.x"
```

---

## Plugin: `azure-kv`

Azure Key Vault secrets.

### Reference format

```
${azure-kv:myvault.vault.azure.net/db-password}
           └─────────────────────┘ └──────────┘
               vault host            secret name
```

Version pin: `${azure-kv:myvault.vault.azure.net/db-password/abc123def}`

### Configuration

`DefaultAzureCredential` in order: env vars → managed identity → Azure CLI.

| Variable | Purpose |
|----------|---------|
| `AZURE_CLIENT_ID` | Service principal app ID |
| `AZURE_CLIENT_SECRET` | Service principal secret |
| `AZURE_TENANT_ID` | AAD tenant |

### Implementation sketch

```scala
class AzureKvResolver extends SecretResolver:
  val scheme = "azure-kv"

  def resolve(ref: String): String =
    val slashIdx = ref.indexOf('/')
    val (host, rest) = ref.splitAt(slashIdx)
    val parts  = rest.stripPrefix("/").split('/')
    val name   = parts(0)
    val version = parts.lift(1)
    val vaultUrl = s"https://$host"
    val client = new SecretClientBuilder()
      .vaultUrl(vaultUrl)
      .credential(new DefaultAzureCredentialBuilder().build())
      .buildClient()
    val secret = version match
      case Some(v) => client.getSecret(name, v)
      case None    => client.getSecret(name)
    secret.getValue
```

### Dependency

```scala
"com.azure" % "azure-security-keyvault-secrets" % "4.x"
```

---

## Plugin: `doppler`

Doppler secrets manager.  Zero heavy dependencies — uses the Doppler
REST API with JDK `HttpClient`.

### Reference format

```
${doppler:PROJECT/CONFIG/SECRET}
```

Example: `${doppler:myapp/production/DB_PASSWORD}`

### Configuration

| Variable | Notes |
|----------|-------|
| `DOPPLER_TOKEN` | Service token or personal token. Required. |

Doppler tokens are scoped to a project/config, so the `PROJECT/CONFIG`
segment is optional when the token already constrains the scope:

```
${doppler:DB_PASSWORD}     ← token-scoped, no project/config needed
${doppler:myapp/prd/DB_PASSWORD}   ← explicit
```

### Implementation sketch

```scala
class DopplerResolver extends SecretResolver:
  val scheme = "doppler"

  private val token = sys.env.getOrElse("DOPPLER_TOKEN",
    throw RuntimeException("DOPPLER_TOKEN is not set"))

  def resolve(ref: String): String =
    val parts = ref.split('/')
    val (project, config, secret) = parts.length match
      case 1 => (None, None, parts(0))
      case 3 => (Some(parts(0)), Some(parts(1)), parts(2))
      case _ => throw RuntimeException(s"doppler ref must be SECRET or PROJECT/CONFIG/SECRET: $ref")
    val query = Seq(
      Some(s"secrets%5B%5D=$secret"),
      project.map(p => s"project=$p"),
      config.map(c => s"config=$c")
    ).flatten.mkString("&")
    val resp = HttpClient.newHttpClient().send(
      HttpRequest.newBuilder(URI.create(s"https://api.doppler.com/v3/configs/config/secrets/download?$query"))
        .header("Authorization", s"Bearer $token")
        .GET().build(),
      BodyHandlers.ofString())
    ujson.read(resp.body())(secret).str
```

No external dependencies beyond JDK 11 and a JSON library.

---

## Plugin: `op`

1Password via the `op` CLI.  No SDK — shells out to the 1Password CLI
which handles all biometrics, SSH agent, and service account auth.

### Reference format

Uses the native `op://` URI format:

```
${op:vault/item/field}
```

Resolves by calling: `op read "op://vault/item/field" --no-newline`

### Configuration

Auth is handled entirely by the `op` CLI:
- **Desktop integration:** unlocks via system keychain / Touch ID
- **Service accounts:** `OP_SERVICE_ACCOUNT_TOKEN` env
- **Connect server:** `OP_CONNECT_HOST` + `OP_CONNECT_TOKEN` env

`op` must be on `PATH`.

### Implementation sketch

```scala
class OpResolver extends SecretResolver:
  val scheme = "op"

  def resolve(ref: String): String =
    val result = os.proc("op", "read", s"op://$ref", "--no-newline").call(
      check = false,
      stderr = os.Pipe
    )
    if result.exitCode != 0 then
      throw RuntimeException(s"op read failed for op://$ref: ${result.err.trim()}")
    result.out.trim()
```

### Dependency

`op` CLI — install via Homebrew (`brew install 1password-cli`) or
1Password.com.

---

## Plugin: `pass`

Unix `pass` (password-store) via subprocess.  Decrypts with GPG.

### Reference format

```
${pass:category/name}
```

Maps directly to `pass show category/name`.  Retrieves the first line
of output (the password); multi-line entries expose the first line only.

```
${pass:databases/prod/password}   → first line of ~/.password-store/databases/prod/password.gpg
```

### Configuration

Relies on the user's GPG keyring and `~/.password-store`.  No env vars
required.  `pass` and `gpg` must be on `PATH`.

### Implementation sketch

```scala
class PassResolver extends SecretResolver:
  val scheme = "pass"

  def resolve(ref: String): String =
    val result = os.proc("pass", "show", ref).call(
      check = false,
      stderr = os.Pipe
    )
    if result.exitCode != 0 then
      throw RuntimeException(s"pass show $ref failed: ${result.err.trim()}")
    result.out.linesIterator.next()
```

No dependencies beyond `pass` and `gpg` on `PATH`.

---

## Packaging plugins

Each plugin is a standard JAR with one entry in:

```
META-INF/services/scalascript.sql.SecretResolver
```

Drop the JAR into `lib/compiler/plugins/` (as a `.sscpkg` or plain `.jar`)
or pass `--plugin path/to/resolver.jar` to `ssc`.

Plugins are discovered lazily on first `${scheme:ref}` use, so unused
plugins add zero overhead.

---

## Priority and conflict resolution

Built-in schemes (`env`, `file`, `sops`) always take precedence.
If two plugin JARs register the same scheme, the first one found by
`ServiceLoader` wins (classpath order).  A `WARN` is printed for
duplicate registrations.

---

## Error messages

Every resolver should throw a `RuntimeException` whose message:

1. Names the scheme and ref that failed.
2. Says **why** it failed (missing token, network error, key not found).
3. Gives a **fix hint** (`export VAULT_TOKEN=…`, `brew install op`, etc.).

The `ConnectionRegistry` wraps this in context: which database and config
key triggered the resolution.
