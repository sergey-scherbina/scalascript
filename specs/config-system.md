# Config System — Specification

**Branch:** `feature/config-system`
**Milestone:** v1.28
**Status:** open

---

## 1. Overview and Motivation

ScalaScript applications routinely need external configuration: database URLs, feature flags,
server ports, TLS certificates, API keys. Before v1.28 this was done ad-hoc — hard-coded
values in `.ssc` files, environment-variable reads scattered through code, or manual HOCON
wiring. The result is fragile code that cannot be promoted between environments without edits.

The v1.28 config system introduces **first-class configuration** for `.ssc` files:

- Config lives in the same `.ssc` file (front-matter or fenced blocks) or in external files.
- All sources are merged into a single typed tree.
- Bindings are available in ScalaScript code, generated Scala code, and generated JavaScript.
- Secret substitution (`${env:VAR}`, `${file:…}`, `${sops:…}`, `${vault:…}`) is handled uniformly.
- Hot reload in `ssc watch` mode re-evaluates whenever any config source changes.

The system is a **superset** of the existing front-matter mechanism. All existing keys
(`frontend:`, `databases:`, `dep:`) remain fully backward-compatible; they simply become
first-class config paths accessible via the `config` accessor.

---

## 2. Config Sources

Three sources can be used simultaneously. They are resolved and merged according to the
priority order described in §3.

### 2.1 Front-Matter Section

The entire front-matter is the config root. Existing keys continue to work unchanged. A new
`config:` key within front-matter provides additional settings and lists external files.

```yaml
---
frontend: react
databases:
  prod:
    url: "${env:DB_URL}"
    pool-size: 20
dep:
  scalatags: true
config:
  server:
    port: 8080
    host: "0.0.0.0"
  features:
    dark-mode: true
  files: [app.yaml, "${env:ENV}.hocon"]
---
```

All top-level front-matter keys (`frontend`, `databases`, `dep`, `config`) are accessible
as config paths:

```scala
config.getString("frontend")          // "react"
config.get[String]("databases.prod.url")
config.get[Boolean]("dep.scalatags")
config.get[Int]("config.server.port") // or config.server.port via structural accessor
```

### 2.2 Fenced Config Blocks

Config blocks are fenced code blocks with the `config` language tag. They can be YAML, JSON,
or HOCON, and optionally carry a **name** that scopes their content to a subtree.

#### Named block — scoped to `config.<name>.*`

````
```yaml config "server"
port: ${env:PORT | 8080}
host: "0.0.0.0"
tls:
  cert: ${file:/run/secrets/cert.pem}
  key:  ${file:/run/secrets/key.pem}
```
````

````
```hocon config "database"
url = ${?DB_URL} "jdbc:h2:mem:test"
pool-size = 10
pool-size = ${?DB_POOL_SIZE}
```
````

````
```json config "features"
{
  "dark_mode": true,
  "beta_users": ["alice", "bob"]
}
```
````

#### Unnamed block — values at the config root

````
```yaml config
app-name: my-app
version: "1.0.0"
```
````

#### Ordering

Multiple fenced blocks may appear anywhere in the `.ssc` file. Document order applies:
a block appearing later in the file wins over an earlier block at the same path (within
the same priority tier — see §3).

### 2.4 JVM System Properties

When running on the JVM (interpreter, `ssc run`, `ssc build --target jvm`, or a packaged JAR),
all system properties with the prefix `scalascript.` or the alias `ssc.` are automatically
injected as the **highest-priority** config layer — always winning over every file-based source.

```bash
# Override the frontend framework at runtime
java -Dscalascript.frontend=vue -jar myapp.jar

# Set any config path (dotted sub-keys become nested maps)
java -Dscalascript.server.port=9090 -jar myapp.jar
java -Dssc.features.darkMode=true -jar myapp.jar

# Both aliases are equivalent — scalascript.* wins over ssc.* on key conflict
java -Dssc.frontend=vue -Dscalascript.frontend=react -jar myapp.jar
# → frontend = "react"  (scalascript.* has higher intra-prefix priority)
```

No configuration is required to enable this behaviour — system properties are always read.
The `ssc.` prefix is a short alias for `scalascript.`; the long form wins on conflict.

### 2.3 External Config Files

External files are listed in the front-matter `config:` key. Two shorthand forms and one
expanded form are supported.

**Shorthand list:**
```yaml
---
config: [app.yaml, prod.hocon, overrides.json]
---
```

**Nested under `config.files`:**
```yaml
---
config:
  files: [defaults.yaml, app.yaml, "${env:ENV}.hocon"]
---
```

**Per-file options:**
```yaml
---
config:
  files:
    - path: defaults.yaml
      priority: fallback        # lower priority than front-matter
    - path: app.yaml
    - path: "${env:ENV}.hocon"
      optional: true            # silently skip if missing
    - path: secrets.yaml
      optional: false           # error if missing (default)
---
```

Supported per-file options:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `path` | string | required | File path, absolute or relative to the `.ssc` file |
| `optional` | bool | `false` | If `true`, silently skip when the file does not exist |
| `priority` | `fallback` \| `normal` | `normal` | `fallback` places the file below front-matter in the merge order |
| `format` | `yaml` \| `json` \| `hocon` \| `auto` | `auto` | Override format detection from extension |

---

## 3. Priority and Merge Semantics

### 3.1 Default Priority Order (highest → lowest)

1. **JVM system properties** (`-Dscalascript.*` / `-Dssc.*`) — always the highest priority; cannot be overridden by any file-based source. Only available on JVM targets; silently ignored on JS targets.
2. **Environment variables** — substitutions resolved from the process environment at load time.
3. **Fenced blocks** — evaluated in document order; later block wins over earlier block.
4. **External files** — evaluated in listed order; later file wins over earlier file.
5. **Front-matter `config:` section** — lowest priority by default.

"Wins" means: when the same path exists in two sources at the same tier, the higher-priority
source's value is used. Merge is **deep** for objects; scalar values are replaced entirely.

### 3.2 Overriding the Priority Order

**JVM system properties are always first and cannot be moved.** The declarative override
applies only to the four file-based tiers below them.

**Declaratively in front-matter:**

```yaml
---
config:
  priority: [env, frontmatter, files, blocks]
---
```

Valid tokens: `env`, `frontmatter`, `files`, `blocks`. All four must be listed; the compiler
rejects partial lists. The `props` tier (JVM system properties) is implicit and always highest.

**In ScalaScript code:**

```scala
config.withPriority(Priority.EnvFirst.thenFrontmatter.thenFiles.thenBlocks)
```

`Priority` is a sealed hierarchy with a fluent builder. The code-level override overrides
the front-matter declaration for that scope.

### 3.3 Merge Rules

- **Object + Object**: recursive deep merge.
- **Scalar + Scalar**: higher-priority value wins; lower-priority value is discarded.
- **Array + Array**: higher-priority value wins; arrays are not concatenated by default.
  Use `config.mergeArrays: true` in front-matter to enable concatenation.
- **Object + Scalar** (type conflict): compile error unless `config.on-conflict: warn` or
  `config.on-conflict: use-higher` is set.

---

## 4. Format Support

All three formats can be used in fenced blocks and external files. The format is detected
from the block language tag or file extension; use the `format:` per-file option to override.

### 4.1 YAML

Standard YAML 1.2. Anchors and aliases are supported within a single document.
Multi-document YAML files (separated by `---`) are not supported.

```yaml
server:
  port: 8080
  host: "0.0.0.0"
  tags: [api, public]
```

### 4.2 JSON

Standard JSON. No comments. Trailing commas are rejected.

```json
{
  "server": {
    "port": 8080,
    "host": "0.0.0.0",
    "tags": ["api", "public"]
  }
}
```

### 4.3 HOCON

Human-Optimised Config Object Notation (Lightbend Config syntax). Supported features:

- `=` and `:` assignment (both accepted)
- Optional-substitution `${?VAR}` (silently absent if undefined)
- Required-substitution `${VAR}` (error if undefined)
- `include "other.conf"` — relative to the containing file; `include url(...)` not supported
- Object merging via re-opening the same key
- Multiline strings (triple-quoted)
- `#` and `//` comments

```hocon
database {
  url = "jdbc:h2:mem:test"
  url = ${?DB_URL}
  pool-size = 10
  pool-size = ${?DB_POOL_SIZE}
}

include "tls.conf"
```

**HOCON `${VAR}` and `${?VAR}` in fenced blocks** — within a `hocon config` fenced block,
bare `${VAR}` is treated as an env-variable substitution (equivalent to `${env:VAR}`).
`${?VAR}` is treated as optional env-variable substitution (equivalent to `${env:VAR | <absent>}`).

---

## 5. Substitution

All config formats support a unified substitution syntax using `${scheme:ref}`. HOCON blocks
additionally support their native `${VAR}` / `${?VAR}` forms.

### 5.1 Substitution Schemes

| Syntax | Description |
|--------|-------------|
| `${env:VAR}` | Environment variable `VAR`; error if missing |
| `${env:VAR \| default}` | Environment variable with fallback value |
| `${file:/path/to/file}` | Contents of a file (trimmed); error if missing |
| `${file:/path/to/file \| ""}` | File contents with fallback |
| `${sops:key.path}` | Decrypt value from sops-encrypted YAML in the same directory |
| `${vault:secret/app#field}` | HashiCorp Vault (requires vault plugin) |
| `${config:server.port}` | Cross-reference another config key (resolved after merge) |

### 5.2 Substitution Timing

- `${env:…}` — resolved at **load time** (startup for JVM/Node.js; build time for SPA baking).
- `${file:…}` — resolved at **load time**.
- `${sops:…}` and `${vault:…}` — resolved at **load time** via their respective plugins.
- `${config:…}` — resolved after all sources are merged (post-merge pass).

### 5.3 Substitution in Path Values

The `path` value in `config.files` entries may itself contain substitutions:

```yaml
config:
  files:
    - path: "${env:CONFIG_DIR}/app.yaml"
    - path: "${env:ENV}.hocon"
      optional: true
```

These are resolved before file loading begins.

### 5.4 HOCON-Native Substitutions in Fenced Blocks

Inside a `hocon config` block, the HOCON-native forms map as follows:

| HOCON form | Equivalent unified form |
|------------|------------------------|
| `${PORT}` | `${env:PORT}` |
| `${?PORT}` | `${env:PORT \| <absent>}` |

Both forms may coexist within the same HOCON block. Outside HOCON blocks (in YAML or JSON),
only the unified `${scheme:ref}` form is recognised.

---

## 6. Typed Binding

Three binding modes are available. They can be combined freely within a single `.ssc` file.

### 6.1 Explicit Case Class with `derives Config`

Define a case class and derive the `Config` typeclass. The typeclass generates a decoder that
reads the named config section (or the root) into an instance of the class.

```scala
case class TlsConfig(cert: String, key: String) derives Config

case class ServerConfig(
  port: Int,
  host: String,
  tls: Option[TlsConfig]
) derives Config

// Decode the "server" section
val server: ServerConfig = config[ServerConfig]("server")

// Decode the entire config root as T
val root: AppConfig = config.as[AppConfig]
```

Field names in the case class correspond to YAML/HOCON/JSON keys. Kebab-case keys
(`pool-size`) are mapped to camelCase fields (`poolSize`) automatically. Override with
`@ConfigKey("pool-size")` if the default mapping is incorrect.

Supported field types: `Int`, `Long`, `Double`, `Boolean`, `String`, `Option[T]`, `List[T]`,
`Map[String, T]`, and any type that itself `derives Config`.

Validation: the derived decoder reports all missing and type-mismatch errors at once (accumulating
errors, not fail-fast), producing a `ConfigError` with the full list of problems.

### 6.2 Auto-Generated Structural Types from Named Blocks

When a named fenced block exists, the compiler synthesises a structural type for it — no
manual case class is needed.

Given:
````
```yaml config "server"
port: 8080
host: "0.0.0.0"
tls:
  cert: /tmp/cert.pem
  key:  /tmp/key.pem
```
````

The compiler generates (conceptually):

```scala
// synthesised — not written by the user
type server = { val port: Int; val host: String; val tls: { val cert: String; val key: String } }
```

Usage:

```scala
val port: Int    = config.server.port
val host: String = config.server.host
val cert: String = config.server.tls.cert
```

Types are inferred from the concrete values in the block. Arrays become `List[T]` where `T`
is the inferred element type. Mixed-type arrays become `List[Any]`. If the inferred type is
wrong, the `@ConfigType` annotation overrides it on the accessor call site, or use explicit
typed decode (`config[ServerConfig]("server")`).

Named blocks from external files do **not** generate structural types (the file content is
not available at compile time in general). Only fenced blocks in the same `.ssc` file are
eligible.

### 6.3 Dynamic Access

The `config` accessor provides a runtime API for path-based access:

```scala
// Typed get — ConfigError if missing or wrong type
val port: Int = config.get[Int]("server.port")

// Typed get with default
val port: Int = config.get[Int]("server.port", 8080)

// Optional typed get — None if missing, ConfigError if wrong type
val portOpt: Option[Int] = config.getOpt[Int]("server.port")

// Untyped string access
val host: String = config.getString("server.host")
val hostOpt: Option[String] = config.getStringOpt("server.host")

// Raw Any (avoid if possible)
val raw: Any = config.getRaw("server")

// Check existence
val hasTls: Boolean = config.has("server.tls")

// List all keys at a path
val keys: List[String] = config.keys("database")
```

All path arguments use dot notation. Array indices are accessed as `"list.0"`, `"list.1"`, etc.

---

## 7. The `config` Accessor

### 7.1 Global Identifier

`config` is a built-in global identifier available in every `.ssc` file without an import,
similar to `println`. It holds the fully-merged, substitution-resolved config tree for the
current file.

```scala
val port = config.get[Int]("server.port")
```

### 7.2 Import Form

```scala
import Config

val srv = Config.load[ServerConfig]("server")
val port = Config.get[Int]("server.port")
```

`Config` (capital C) is the module; `config` (lower c) is the pre-bound instance equivalent
to `Config.current`.

### 7.3 Full API Surface

```scala
object Config:
  // Current file's merged config (same as global `config`)
  def current: Config

  // Load and decode a section as T
  def load[T: ConfigDecoder](section: String): T
  def load[T: ConfigDecoder]: T  // root

  // Override priority for this load
  def loadWith[T: ConfigDecoder](section: String, priority: Priority): T

trait Config:
  // Structural accessor (synthesised paths for named blocks)
  // e.g. config.server.port

  // Typed path access
  def get[T: ConfigDecoder](path: String): T
  def get[T: ConfigDecoder](path: String, default: T): T
  def getOpt[T: ConfigDecoder](path: String): Option[T]

  // String shortcuts
  def getString(path: String): String
  def getStringOpt(path: String): Option[String]
  def getInt(path: String): Int
  def getIntOpt(path: String): Option[Int]
  def getBool(path: String): Boolean
  def getBoolOpt(path: String): Option[Boolean]

  // Raw access
  def getRaw(path: String): Any
  def has(path: String): Boolean
  def keys(path: String): List[String]

  // Decode section as T
  def apply[T: ConfigDecoder](section: String): T
  def as[T: ConfigDecoder]: T

  // Priority override
  def withPriority(p: Priority): Config

  // Reload (for testing / dynamic reload)
  def reload(): Config
```

### 7.4 `ConfigDecoder` Typeclass

```scala
trait ConfigDecoder[T]:
  def decode(node: ConfigNode, path: String): Either[List[ConfigError], T]

object ConfigDecoder:
  given ConfigDecoder[Int]     = ...
  given ConfigDecoder[Long]    = ...
  given ConfigDecoder[Double]  = ...
  given ConfigDecoder[Boolean] = ...
  given ConfigDecoder[String]  = ...
  given [T: ConfigDecoder]: ConfigDecoder[Option[T]] = ...
  given [T: ConfigDecoder]: ConfigDecoder[List[T]]   = ...
  given [K, V: ConfigDecoder]: ConfigDecoder[Map[K, V]] = ...
  // derived instances via `derives Config`
```

### 7.5 Error Handling

Config errors are surfaced as `ConfigError`, which is a sealed hierarchy:

```scala
sealed trait ConfigError:
  def path: String
  def message: String

case class MissingKey(path: String) extends ConfigError
case class TypeMismatch(path: String, expected: String, actual: String) extends ConfigError
case class SubstitutionError(path: String, ref: String, cause: String) extends ConfigError
case class ParseError(source: String, line: Int, col: Int, message: String) extends ConfigError
case class FileNotFound(path: String) extends ConfigError
```

By default, config errors at startup terminate the program with a descriptive message. Use
`config.getOpt` or `config.get(..., default)` to handle absence gracefully.

---

## 8. Front-Matter as Config Root

### 8.1 Backward Compatibility

All existing front-matter keys continue to work exactly as before. The compiler treats the
entire front-matter document as the config root. No existing `.ssc` files need changes.

```yaml
---
frontend: react                  # config.getString("frontend") == "react"
databases:
  prod:
    url: "${env:DB_URL}"
dep:
  scalatags: true
---
```

The `databases:` section is now `config.databases` under the hood. The existing `databases`
feature (connection pools, type-safe query generation) reads its settings from this path.
All behaviour is identical to pre-v1.28.

### 8.2 Existing Front-Matter Keys as Config Paths

| Front-matter key | Config path | Notes |
|-----------------|-------------|-------|
| `frontend` | `config.frontend` | String scalar |
| `databases` | `config.databases.*` | Subtree |
| `dep` | `config.dep.*` | Subtree |
| `config` | `config.*` (merged) | The new key |

Any key unknown to the compiler is forwarded to the config tree verbatim — custom keys are
accessible via `config.get[T]("my-custom-key")`.

### 8.3 Accessing `databases` via the Config API

```scala
// Old approach (still works):
databases.prod.query(sql"SELECT 1")

// New approach (also works):
val url: String = config.getString("databases.prod.url")
val pool: Int   = config.get[Int]("databases.prod.pool-size")
```

---

## 9. JavaScript Binding

### 9.1 Strategy Selection

Choose the JS binding strategy in front-matter:

```yaml
---
config:
  js-binding: bake        # default for SPA targets
  js-binding: process-env # Node.js apps
  js-binding: runtime     # load config.json at startup
  js-binding: window      # browser SPAs without a bundler
---
```

### 9.2 `bake` (Default for SPA)

All statically-known config values are inlined into the generated JS bundle as constants.

```javascript
// generated
const __ssc_config_server_port = 8080;
const __ssc_config_server_host = "0.0.0.0";
```

- `${env:VAR}` substitutions that cannot be resolved at build time emit a **build warning**
  and are replaced by `null` (or a user-specified placeholder via `config.missing-placeholder`
  in front-matter).
- The config tree is not present at runtime — values are baked in as literals.

### 9.3 `process-env`

`${env:VAR}` substitutions emit `process.env.VAR` in the generated JS instead of inlining
the value. Suitable for Node.js applications where env vars are available at runtime.

```javascript
// generated
const __ssc_config_db_url = process.env.DB_URL;
```

Static (non-env) values are still baked in as constants.

### 9.4 `runtime`

A `config.json` file is written alongside the output `.js`. A generated preamble loads it
synchronously at startup:

```javascript
// generated preamble (Node.js)
const __ssc_config = JSON.parse(
  require('fs').readFileSync(__dirname + '/config.json', 'utf8')
);
```

The `config.json` contains all resolved config values. Secrets present in `${file:…}` or
`${sops:…}` substitutions are resolved at build time and written to `config.json` — ensure
the file is not committed to version control.

### 9.5 `window` (Browser SPAs without a Bundler)

Config is serialised to a `<script>` tag injected into the HTML entry point:

```html
<script>
window.__SSC_CONFIG = {"server":{"port":8080,"host":"0.0.0.0"}};
</script>
```

The generated JS reads from `window.__SSC_CONFIG`. Suitable for browser SPAs where a
separate server can inject environment-specific config into the HTML at serve time.

### 9.6 Mixed Strategies

Strategies can be combined per-key using `js-binding-override`:

```yaml
---
config:
  js-binding: bake
  js-binding-override:
    database.url: process-env   # DB_URL stays as process.env.DB_URL
    features: bake              # features subtree is baked
---
```

---

## 10. Scala Binding

When targeting JVM (`ssc run-jvm` / `ssc compile-jvm`), three output modes are available.

### 10.1 Embedded Map (Default)

The config tree is embedded as a Scala `Map[String, Any]` read at JVM startup. No extra file
is produced. The `config.load[T]()` call reads from this embedded map.

```scala
// generated (simplified)
private val __ssc_config: Map[String, Any] = Map(
  "server.port" -> 8080,
  "server.host" -> "0.0.0.0"
)
```

### 10.2 `application.conf` Output

Set `config: scala-output: application.conf` in front-matter:

```yaml
---
config:
  scala-output: application.conf
---
```

An `application.conf` (TypesafeConfig / Lightbend Config compatible HOCON) is written
alongside the generated `.sc`. The generated Scala code uses
`com.typesafe.config.ConfigFactory.load()` to read it.

This mode is useful when the generated Scala code is a library consumed by other Scala
projects that already use TypesafeConfig.

### 10.3 Companion Object

Set `config: scala-output: object` in front-matter:

```yaml
---
config:
  scala-output: object
---
```

A Scala companion `object AppConfig` is generated with typed `val` fields:

```scala
// generated
object AppConfig:
  val serverPort: Int    = 8080
  val serverHost: String = "0.0.0.0"
  object database:
    val url: String      = sys.env.getOrElse("DB_URL", "jdbc:h2:mem:test")
    val poolSize: Int    = sys.env.get("DB_POOL_SIZE").map(_.toInt).getOrElse(10)
```

`${env:VAR}` substitutions become `sys.env` lookups in the generated object so they are
resolved at JVM startup.

---

## 11. Priority Override

### 11.1 Front-Matter Declaration

```yaml
---
config:
  priority: [env, frontmatter, files, blocks]
---
```

All four tokens (`env`, `frontmatter`, `files`, `blocks`) must be present. Duplicates and
unknown tokens are compile errors. JVM system properties (`props`) are always the implicit
highest tier and are not part of the list.

### 11.2 Code-Level Override

```scala
// Override for the entire config instance
val myCfg = config.withPriority(
  Priority.EnvFirst
    .thenFrontmatter
    .thenFiles
    .thenBlocks
)

// Named constructors
Priority.default           // props > env > blocks > files > frontmatter
Priority.EnvFirst          // shorthand for env-first (props still above env)
Priority.FrontmatterFirst  // props > env > frontmatter > blocks > files
```

Code-level priority overrides the front-matter declaration. The `config` global always uses
the priority from front-matter (or the default). Use `config.withPriority(…)` to obtain a
view with a different priority.

---

## 12. Hot Reload

In `ssc watch` mode, the config system participates in the watch graph:

- Changes to **external config files** listed in `config.files` trigger a full reload of the
  config tree and re-evaluation of any code that depends on it.
- Changes to **fenced config blocks** inside a `.ssc` file are detected as part of the normal
  `.ssc` change event and are re-parsed with the file.
- Changes to **front-matter** are likewise detected as part of the `.ssc` change event.
- `${env:VAR}` substitutions are re-resolved on each reload (picks up updated env vars
  when the file changes, not on env-only changes).

The watch graph tracks which output files depend on which config keys (for JS baking). Only
affected outputs are recompiled.

Hot reload does **not** restart the JVM process in `ssc run-jvm` mode. JVM code that uses the
embedded map will reflect new values only after a process restart. For JVM hot-config, use the
`application.conf` output mode and pair with a TypesafeConfig reload mechanism.

---

## 13. Complete Example `.ssc` File

The following `.ssc` file exercises all features of the config system simultaneously.

```
---
frontend: react
databases:
  prod:
    url: "${env:DB_URL}"
    pool-size: 20
dep:
  scalatags: true
config:
  priority: [env, blocks, files, frontmatter]
  js-binding: process-env
  scala-output: object
  files:
    - path: defaults.yaml
      priority: fallback
    - path: "${env:ENV | dev}.hocon"
      optional: true
    - path: secrets.yaml
      optional: false
---

# My App

Some documentation prose here.

```yaml config "server"
port: ${env:PORT | 8080}
host: "0.0.0.0"
tls:
  cert: ${file:/run/secrets/tls.crt}
  key:  ${file:/run/secrets/tls.key}
```

```hocon config "database"
url = "jdbc:h2:mem:test"
url = ${?DB_URL}
pool-size = 10
pool-size = ${?DB_POOL_SIZE}
dialect = "h2"
```

```json config "features"
{
  "dark_mode": true,
  "beta_users": ["alice", "bob"],
  "max_upload_mb": 50
}
```

```yaml config
app-name: my-app
version: "1.0.0"
```

```scala
// --- Structural type accessors (auto-generated from named blocks) ---
val port: Int    = config.server.port
val host: String = config.server.host
val certPath: String = config.server.tls.cert

// --- Explicit case class with derives Config ---
case class TlsConfig(cert: String, key: String) derives Config
case class ServerConfig(port: Int, host: String, tls: Option[TlsConfig]) derives Config

val srv: ServerConfig = config[ServerConfig]("server")

// --- Dynamic access ---
val dbUrl: String   = config.getString("database.url")
val poolSize: Int   = config.get[Int]("database.pool-size", 10)
val darkMode: Boolean = config.getBool("features.dark_mode")
val betaUsers: List[String] = config.get[List[String]]("features.beta_users")

// --- Front-matter keys as config ---
val frontendFramework: String = config.getString("frontend")
val prodDbUrl: String         = config.getString("databases.prod.url")

// --- Cross-reference substitution (resolved post-merge) ---
// In a config block: other-port: ${config:server.port}
// Access:
val otherPort: Int = config.get[Int]("other-port")

// --- Priority override for a specific load ---
val devCfg = config.withPriority(Priority.FrontmatterFirst)
val devPort: Int = devCfg.get[Int]("server.port")

println(s"Starting $appName on $host:$port")
```
```

---

## 14. Implementation Notes

The following modules require changes or additions for v1.28.

### 14.1 New Modules

| Module | Description |
|--------|-------------|
| `core/config/ConfigParser` | Parses YAML, JSON, and HOCON into a unified `ConfigNode` AST |
| `core/config/MergeEngine` | Deep-merges `ConfigNode` trees according to the priority order |
| `core/config/SubstitutionEngine` | Resolves `${scheme:ref}` substitutions; plugin architecture for `sops:` and `vault:` |
| `core/config/ConfigDecoder` | Typeclass + derived instances; accumulating error model |
| `core/config/StructuralTypeSynthesiser` | Analyses named fenced blocks; generates structural types for the typer |
| `core/config/PriorityParser` | Parses the `priority:` front-matter key; validates token set |
| `core/config/HotReloadWatcher` | Registers external config file paths with the watch graph |

### 14.2 Modified Modules

| Module | Change |
|--------|--------|
| `core/FrontMatterParser` | Expose the full front-matter document as a `ConfigNode` root; existing keys unchanged |
| `core/Typer` | Introduce the `config` global identifier; resolve structural type accessors for named blocks |
| `core/CodeGen (JVM)` | Emit embedded config map, `application.conf`, or companion object per `scala-output` setting |
| `core/CodeGen (JS)` | Emit baked constants, `process.env` references, `config.json` + preamble, or `window.__SSC_CONFIG` per `js-binding` setting |
| `ssc/WatchMode` | Add external config files to the watch graph; trigger reload on change |
| `std/Config.ssc` | Expose the `Config` module and `config` global binding for import form |

### 14.3 Phase Sequencing

The implementation follows the phase breakdown in the v1.28 milestone (see `MILESTONES.md`).
Each phase produces a working, tested increment. Phases 2–5 (infrastructure and parsing) are
prerequisites for Phases 6–10 (binding modes). Phase 11 (hot reload) is independent and can
be developed in parallel with Phase 9–10.

### 14.4 Testing Strategy

- Unit tests for `ConfigParser` covering all three formats and error cases.
- Unit tests for `MergeEngine` covering all merge-rule combinations.
- Unit tests for `SubstitutionEngine` covering all schemes, defaults, missing values.
- Integration tests: end-to-end `.ssc` files with fenced blocks, external files, and typed
  binding, compiled and run through `ssc`.
- Regression tests: all existing `.ssc` files with front-matter must compile and run unchanged.
