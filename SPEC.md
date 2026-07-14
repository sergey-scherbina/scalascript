# ScalaScript Language Specification

**Version**: 1.26
**Status**: Normative

## 1. Introduction

ScalaScript is a hybrid language that unifies Markdown document structure with Scala-style typed expressions. This specification defines the syntax, type system, and semantics of the language.

### 1.1 Design Goals

1. Markdown constructs are first-class syntax, not comments
2. Type safety with Scala-style type system
3. Target-independent semantics
4. Human-readable source that is also machine-parseable

### 1.2 Notation

This specification uses EBNF notation. See
[v1/lang/grammar/scalascript.ebnf](v1/lang/grammar/scalascript.ebnf) for the complete
formal grammar.

## 2. Lexical Structure

### 2.1 Source Files

- File extension: `.ssc`
- Encoding: UTF-8
- Line endings: LF or CRLF (normalized to LF)

### 2.2 Document Structure

A ScalaScript file consists of:

1. **Optional YAML front-matter** (module manifest)
2. **Markdown body** with embedded code regions

```text
[front-matter]
[markdown-body]
```

### 2.3 Front-Matter

YAML block delimited by `---` lines at the start of the file:

```yaml
---
name: module-name
version: 1.0.0
package: org.example.ui
dependencies:
  - other-module: ^2.0.0
exports:
  - functionName
  - TypeName
translations:
  en:
    greeting: "Hello"
  fr:
    greeting: "Bonjour"
routes:
  - method: GET
    path: /api/todos
    handler: listTodos
  - method: POST
    path: /api/todos
    handler: addTodo
---
```

Recognized front-matter keys:

| Key | Type | Description |
|-----|------|-------------|
| `name` | String | Module identifier |
| `version` | SemVer | Module version |
| `package` | String | Dotted namespace prefix for all exports |
| `dependencies` | Map | Name → version or URL |
| `exports` | List[String] | Explicitly exported names (default: all top-level) |
| `translations` | Map | Locale → key → string for `t(key)` |
| `routes` | List | Declarative HTTP route table |
| `databases` | Map | JDBC connection registry consumed by `sql` blocks (§ 3.3.1) |
| `schemas` | Map | Optional type-to-storage mapping metadata for typed data codecs (`fields`, aliases, defaults, keys, unknown-field policy) |
| `backend` | String | Preferred backend id for `ssc run` when no `--backend` flag is supplied (`int` / `ssc` / `jvm` / `js` / `node` / `scalajs-spa` / `wasm` / `swift` / `spark`). § 9.2. |
| `spark-version` | String | Apache Spark version pinned for the Spark backend.  Resolution order: CLI `--spark-version` flag → this key → `SparkGen.DefaultVersion`. § 9.5. |
| `spark-master` | String | Spark master URL passed to `SparkSession.builder().master(...)` (`local[*]` / `local[N]` / `spark://...` / `yarn` / `k8s://...`).  Resolution order: CLI `--spark-master` flag → this key → `SparkGen.DefaultMaster` (= `local[*]`). § 9.5. |
| `spark-config` | Map[String, String] | Ad-hoc Spark configuration entries.  Each pair emits one `.config(key, value)` line on `SparkSession.builder()` in sorted-key order, between the fixed defaults and `.getOrCreate()`.  User keys that collide with fixed defaults win (Spark's builder is last-write).  Values are coerced via `toString` so YAML scalars (`200`, `true`) survive intact. § 9.5. |
| `spark-app-name` | String | Overrides `SparkSession.builder().appName(...)`.  Default: `SparkGen.DefaultAppName` (= `"scalascript-job"`).  Shows up verbatim in the Spark UI, history server, and driver / executor log lines, so a per-job human-readable name is worth setting for any non-trivial deployment.  Special characters are escaped for the emitted Scala string literal. § 9.5. |
| `spark-hive-metastore` | String | Thrift URI of the Hive metastore service (e.g. `thrift://metastore.example.com:9083`).  When set, the generated session enables Hive support — emits `.config("spark.sql.catalogImplementation", "hive")`, `.config("spark.hadoop.hive.metastore.uris", "<uri>")`, and `.enableHiveSupport()` on the builder, plus auto-adds the `org.apache.spark:spark-hive_2.13:<sparkVersion>` runtime dep so `scala-cli` resolves the hive shim via Coursier.  Composable with `spark-warehouse:` for a fully managed Hive setup.  § 9.5 Phase G. |
| `spark-warehouse` | String | Spark warehouse directory passed as `.config("spark.sql.warehouse.dir", "<path>")` — local path or `hdfs://...` / `s3a://...`.  Independently enables Hive support (catalogImplementation = hive + `.enableHiveSupport()` + `spark-hive_2.13` dep) so a warehouse-only deployment with the embedded derby metastore works without a separate Thrift URI.  § 9.5 Phase G. |

`routes:` entries are equivalent to writing `route(method, path) { req => handler(req) }` inline.

See [v1/lang/schemas/frontmatter.yaml](v1/lang/schemas/frontmatter.yaml) for the
complete schema.

### 2.4 Comments

- Markdown: HTML comments `<!-- comment -->`
- Code blocks: Scala-style `//` and `/* */`

## 3. Markdown as Syntax

### 3.1 Headings → Namespaces/Scopes

Headings define hierarchical scopes:

```markdown
# Module          → top-level namespace
## Section        → nested scope
### Subsection    → further nested
```

Scope rules:
- Definitions in inner scopes shadow outer scopes
- Siblings do not see each other's private definitions
- Heading level determines nesting depth

### 3.2 Links → Imports/References

Markdown links serve as imports and cross-references:

```markdown
[collections](./std/collections.ssc)    → import local module
[List, Map](./std/collections.ssc)      → selective import
[Card as UICard](./ui/card.ssc)         → aliased import
[X](https://raw.github.com/.../x.ssc)   → URL import (cached)
[X](dep:org/lib:1.2)                    → dependency import
[A](./a.ssc) [B](./b.ssc)               → two imports in one pure paragraph
```

A Markdown paragraph is an import declaration when every non-whitespace inline
node is an import link. Multiple import links in that pure paragraph lower in
source order. A paragraph that mixes prose text with links stays prose, and
links whose destination starts with `#` are internal cross-references rather
than imports. The label of an import link may wrap across physical source lines;
line wrapping does not split the paragraph or change the import target.

### 3.3 Fenced Code Blocks → Typed Expressions

Code blocks carry a language annotation that determines how they are
processed by each backend.

| Tag | Language | Description |
|-----|----------|-------------|
| `scalascript` | ScalaScript | Full ScalaScript dialect: effects, handlers, tail-call optimisation, content helpers, module imports. |
| `ssc` | ScalaScript | Legacy alias for `scalascript`. |
| `scala` | Standard Scala 3 | No ScalaScript-specific extensions. JS backend compiles via Scala.js. |
| `html` | HTML | String-valued block with `${expr}` interpolation; values are HTML-escaped. Not parsed. |
| `css` | CSS | String-valued block with `${expr}` interpolation. Not parsed. |
| `javascript` (alias `js`) | JavaScript | String-valued block with `${expr}` interpolation. Not parsed, not type-checked. The JS backend may splice the value directly into its output; other backends treat it as a plain `String` value. |
| `node.js` (alias `node`) | Executable JavaScript for Node target | Linked verbatim into the bundle emitted by the `node` backend, alongside the JS produced from `scalascript` / `scala` blocks. Cross-language interop is by name via `extern def` declarations resolving against `globalThis`. Not type-checked. Other backends reject `node.js` blocks with a `UnknownBlockLanguage` diagnostic. |
| `sql` | SQL / JDBC | Parameterised SQL executed via JDBC.  Every `${expr}` becomes a single positional `?` bind parameter; string substitution is never performed.  Block evaluates to `Seq[Row]` (SELECT) or `Int` update count (DDL/DML).  JVM-only target; other backends emit `UnknownBlockLanguage`. |

A `.ssc` document may contain both `scala` and `scalascript` blocks. When a
document uses only standard `scala` fences, those fences are executable units and
run in document order. In a mixed document, `scalascript` fences are the runnable
default and `scala` fences are illustrative unless the YAML front-matter opts in
with `runScalaFences: true`, `run-scala-fences: true`, or
`scalaFences: runnable` / `scala-fences: runnable`. With that opt-in,
definitions are visible across runnable blocks within the same file.
Other tags (`json`, `yaml`, `text`, etc.) are treated as inert prose.

**String blocks** (`html`, `css`, `javascript`) carry no semantics of
their own — the source is captured as a `String` after `${expr}`
interpolation against the surrounding ScalaScript scope.  No JS parser
or HTML/CSS validator is invoked at compile time.

**Executable JS blocks** (`node.js`) are *opaque* to the front-end: the
source is preserved verbatim and passed to the `node` backend, which
concatenates all such blocks with the `JsGen` output of the module into
a single bundle and runs it under Node.  ScalaScript code calls into
JS-defined symbols through `extern def` declarations whose names resolve
to `globalThis.<name>` at runtime; type signatures on the ScalaScript
side are a contract, not a derivation — mismatches surface at runtime.
No JS parser is part of the toolchain.

### 3.3.1 SQL / JDBC blocks

A `sql` block is *parameterised executable*: opaque to the front-end
parser (no SQL grammar is part of the toolchain), but with one
mandatory front-end pass — **bind-parameter rewriting**.

**Binding rule (the entire safety contract).**  Inside a `sql` block,
every `${expr}` is rewritten to a single positional `?` placeholder,
and `expr` is appended to an ordered bind list.  The rewriter runs
before any backend sees the source.  String substitution into SQL is
**not available** — there is no `$!{...}` escape, no `raw"..."`
variant, no opt-out.  The contract: a `sql` block can never produce a
SQL injection, no matter what the surrounding ScalaScript code does.

This rules out dynamic identifiers (table names, column names, `ORDER
BY` columns).  Build those with separate `scalascript` blocks that
emit a fully-formed `sql` block via composition — never by string
concatenation inside the SQL.

```sql
SELECT id, name, email
FROM users
WHERE tenant_id = ${tenantId}
  AND status   = ${status}
LIMIT ${pageSize}
```
↓ rewritten by the front-end to:
```
PreparedStatement(
  "SELECT id, name, email FROM users WHERE tenant_id = ? AND status = ? LIMIT ?",
  bind = [tenantId, status, pageSize])
```

`${...}` inside SQL string literals (`'...'`, `"..."`) still binds —
the rewriter is lexer-level, not string-aware.  This is intentional:
binding inside a literal is what the user wants 100% of the time
(`WHERE name = '${name}'` is the natural way to write it but is
trivially injectable; rewriting to `WHERE name = ?` makes it safe).
A literal `$` that should not bind is escaped as `$$`.

**Result type.**  A `sql` block is an expression.  Its value depends
on the first statement keyword:

| Leading keyword | Result type |
|-----------------|-------------|
| `SELECT`, `WITH`, `VALUES`, `SHOW`, `EXPLAIN` | `Seq[Row]` |
| `INSERT`, `UPDATE`, `DELETE`, `MERGE` | `Int` (affected rows) |
| `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `GRANT`, `REVOKE` | `Int` (typically `0`) |

`Row` is a positional + named tuple: `row(0)`, `row("name")`,
`row.as[User]`.  `.as[T]` projects into a case class by field name;
mismatched columns or types raise at runtime with a clear diagnostic.

```scalascript
case class User(id: Long, name: String, email: String)

val users: Seq[User] = (```sql
  SELECT id, name, email FROM users WHERE active = ${true}
```).as[User]
```

A `sql` block contains exactly one statement.  Multi-statement scripts
go in several blocks, or use the `runScript` helper from the runtime
which takes a `String` (no `${}`-binding).

**Connection resolution.**  In priority order:

1. If a `given Connection` (or `given DataSource`) is in scope at the
   block site, it is used.  This is the override path — typically for
   tests with in-memory H2.
2. Otherwise, the module's front-matter `databases:` entry named by
   the block's `@db=name` attribute (default: `default`).  Connections
   declared in front-matter are opened lazily and cached for the
   module's lifetime.

```yaml
---
databases:
  default:
    url: jdbc:postgresql://localhost:5432/app
    user: ${env:DB_USER}
    password: ${env:DB_PASSWORD}
  reports:
    url: jdbc:postgresql://reports.internal:5432/warehouse
    user: ${env:RPT_USER}
    password: ${env:RPT_PASSWORD}
---
```

```sql @db=reports
SELECT count(*) FROM events WHERE day = ${today}
```

`${env:NAME}` references in front-matter resolve from process
environment at module load.  Missing env vars are an immediate fatal
diagnostic — no silent fallback to empty string.

On the 2.1 standard JVM path, explicit root-module `databases:` maps are parsed
by the standalone YAML parser and handed to the core-free native SQL provider;
the v1 AST/manifest and `PluginBridge` are not involved. Multiple explicit roots
may repeat an identical named declaration, but conflicting declarations for the
same name fail before any JDBC connection is opened.

The self-hosted document projection retains server-side `sql` fences in source
order, applies the same mandatory `${expr}` → `?` bind rewrite, assigns stable
`_sqlBlock_N` values, and exposes the first result under `<Section>.sql`.
Standard VM, direct ASM, and `build-jvm` execute those calls through the
core-free native SQL provider. The bounded native typed surface retains
`Db.query[A]` nominal product metadata and supports identifier-validated
`Db.insert/update` for registered portable products. Client-only/transaction
fences and advanced annotation-driven RowCodec policy remain explicit tools or
future provider surfaces; they never trigger transparent fallback.

**Drivers.**  The `backend-sql-runtime` module bundles H2 and SQLite
(both embedded, no network) so that `jdbc:h2:mem:test` and
`jdbc:sqlite:./data.db` work with zero configuration.  Any other
driver — PostgreSQL, MySQL, Oracle, MSSQL — is brought in via a
standard `dep:` link from the front-matter:

```markdown
[postgres](dep:org.postgresql/postgresql:42.7.4)
```

**Target support.**  `sql` blocks run on all backends that declare
`Lang.Sql` in their `Capabilities.blockLanguages`:

| Backend | Engine | Notes |
|---------|--------|-------|
| JVM / interpreter | JDBC (`backend-sql-runtime`) | All canonical URL schemes; `sqlite:` → `jdbc:sqlite:`, etc. |
| JS / Node | sql.js (`sqlite:`) or DuckDB-Wasm (`duckdb:`) | Async by construction |
| Wasm | sql.js / DuckDB-Wasm | Same as JS/Node |

`jdbc:*` URLs on JS-family targets produce a build-time
`UnsupportedJdbcUrl` diagnostic.  Use the canonical scheme (e.g.
`sqlite:` instead of `jdbc:sqlite:`) to target all backends from one
front-matter entry — each backend translates at connect time.

**`@side` attribute — client vs. server SQL (v1.30).**
In modules that compile to both a server and a browser bundle
(`frontend: react` / `frontend: solid` / etc.), a `sql` block may
carry `@side=server` (default) or `@side=client`:

```sql @db=local @side=client
SELECT * FROM cache WHERE key = ${k}
```

| `@side` | Where the block runs | Allowed URL schemes |
|---------|---------------------|---------------------|
| `server` (default) | Server/JVM route handler | Any scheme |
| `client` | Browser bundle | `sqlite:`, `sqlite-opfs:`, `duckdb:` |

A `@side=client` block may only reference a `databases:` entry whose
URL scheme is JS-supported; a non-JS-supported scheme on `@side=client`
is a build-time `UnsupportedDbUrl` diagnostic.

`@side=server` is the default everywhere; existing code without the
attribute is unaffected.

**Codegen behaviour (v1.30 Phase 4).**

| Target | `@side=server` | `@side=client` |
|--------|---------------|----------------|
| JvmGen (full-stack) | Emitted as server JDBC `_sqlBlock_N` | Inlined into `app.js` as async IIFE; result exposed on `window._ssc_client[sectionAlias]` |
| JsGen / NodeBackend | Skipped (not relevant in JS-only target) | Emitted as `_sqlBlock_N` (normal JS path) |
| Interpreter | Executed server-side (client attr ignored) | — |

JvmGen appends the `@side=client` bundle to `app.js` via an async IIFE
wrapping the inlined `sql-runtime.mjs`, a per-module `ConnectionRegistry`,
and one `await SqlRuntimeJs.execute(...)` per block.  The section alias
(e.g. `CacheSetup`) is exposed as `window._ssc_client['CacheSetup'].sql`
for the React/Solid/Vue app to consume.

### 3.4 Markdown Frontend From Content

ScalaScript treats Markdown-hosted content as source syntax, not as comments.
The first content milestone is frontend from Markdown: the parsed document
lowers to frontend toolkit nodes so authors can define pages and screens
without hand-writing markup generation. The same typed, immutable snapshot is
available to code blocks through `contentDocument()` and later powers broader
metadata helpers. It includes Markdown prose and structure, YAML/front-matter,
and fenced embedded language blocks.

MVP API:

```scalascript
[contentDocument](std/content.ssc)

[contentView](std/ui/content.ssc)

val doc = contentDocument()
println(doc.title.getOrElse(""))
val page = contentView(doc)
```

Markdown headings, prose, lists, links, images, code fences, YAML/JSON/TOML data
blocks, and GitHub-Flavored Markdown pipe tables lower into a `DocumentContent`
tree.
Front-matter remains the module manifest, and the same YAML is exposed as typed
`ContentValue` data under `DocumentContent.manifest`; `content:` inside
front-matter carries content-rendering defaults. Lightweight metadata can be
attached to Markdown nodes:

```markdown
# Pricing {#pricing route=/pricing layout=marketing}

<!-- @meta component=PlanList source=plans -->
## Plans
```

Every fenced block enters the content tree as an embedded language node.
Structured languages such as `yaml`, `json`, and `toml` may also expose parsed
`ContentValue` data; executable or plugin-defined languages preserve their
exact source and language tag for backend/plugin handling.
Pipe tables enter the content tree as `ContentBlock.Table` with inline headers,
inline cell content, column alignments, and attrs from preceding metadata
directives.

The content snapshot is parse-time data. Inline `${expr}` inside prose is stored
as expression source until an explicit renderer evaluates it, so reading
`contentDocument()` does not execute user code or cause side effects.

On the ScalaScript 2.1 standard native path, the self-hosted tower projects the
canonical Markdown/YAML ADTs to `DocumentContent` for every linked module and
passes normalized direct-import edges structurally through the permanent Scala
3 seed. The seed and native provider never reopen or reparse source text;
`build-jvm` embeds the same immutable values in its bootstrap artifact format.
The core-free boundary and exact supported helper set are normative in
[`specs/v2.1-native-content.md`](specs/v2.1-native-content.md).

Frontend lowering is the first public target. `std/ui/content.ssc` provides
`contentView(...)`, `contentViewSection(...)`, and `contentViewBlock(...)`
helpers that render the same content tree through the existing backend-agnostic
UI model. The toolkit bridge can render the whole document with
`contentToolkitNode()` or select explicit Markdown-authored regions with
`contentToolkitBlock(id)` and `contentToolkitSection(id)`, so one `.ssc`
document can hold multiple independent UI fragments defined in Markdown/YAML.
Simple toolkit controls may also be declared as ordinary Markdown links with a
`toolkit:` destination, for example
`[Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)`;
the toolkit renderer consumes those links as controls while non-toolkit
Markdown renderers still see valid links.
Sections or blocks annotated with `component=<name>` may opt into an explicit
`ContentToolkitOptions.components` registry; registered callbacks receive the
selected content metadata and return replacement toolkit nodes, while
unregistered component names fall back to the default Markdown lowering.
Metadata `data=<id>` on a selected section or block binds the component context
to the parsed structured data from a fenced YAML/JSON/TOML block with matching
`@id`; the same lookup is exposed to code as `contentData(id)`. Lower-level
interpreter helpers also expose `contentSection(id)`, `contentBlock(id)`,
`contentPlainText(value)`, and `contentToMarkdown(value)` for code that needs
to inspect, reuse, or serialize Markdown-authored regions without rendering the
whole document.
`contentMetadata(path)` reads `content:` front-matter metadata by dot path for
code that needs renderer/content defaults without unpacking the whole manifest.
`contentCurrentSection()` returns the currently executing code block's
`SectionContent` when the block runs inside a real Markdown heading section;
outside such a section it reports an interpreter error rather than fabricating
synthetic content.
Direct imported modules expose their Markdown snapshots through
`contentModules()`, `contentModule(namespace)`, and namespace-scoped lookup
helpers. The namespace is the imported module's `name:` front-matter value, or
the imported path stem when `name:` is absent. Imported content namespaces are
kept outside `DocumentContent` so the current-module content ABI remains stable.
Imports used only to bring `std/content.ssc` or `std/ui/content.ssc` helpers
into scope do not appear as content modules.
`contentToMarkdown(value)` accepts `DocumentContent`, `SectionContent`, or any
current `ContentBlock` variant and returns deterministic semantic Markdown. It
preserves embedded fenced source text and metadata, but does not promise
byte-for-byte source preservation of whitespace, quote style, or equivalent
Markdown spellings.
The remaining `std/content` metadata API follows from the same snapshot instead
of being the first goal. See
[`specs/markdown-content-introspection.md`](specs/markdown-content-introspection.md)
for the full contract and remaining implementation phases.

### 3.5 Inline Interpolation

Inline code with `${}` is evaluated and interpolated:

```markdown
The sum is `${add(2, 3)}`.
```

## 4. Type System

### 4.1 Primitive Types

| Type | Description | Literals |
|------|-------------|----------|
| `Unit` | No value | `()` |
| `Boolean` | Truth value | `true`, `false` |
| `Int` | 32-bit integer | `42`, `-1`, `0xFF` |
| `Long` | 64-bit integer | `42L` |
| `Double` | 64-bit float | `3.14`, `1e10` |
| `String` | Unicode text | `"hello"`, `"""multi"""` |
| `Char` | Unicode char | `'a'` |

### 4.2 Compound Types

```scalascript
// Tuples
(Int, String)
(1, "hello")

// Functions
Int => String
(Int, Int) => Int

// Generics
List[Int]
Map[String, Int]
Option[A]
Either[E, A]
```

### 4.3 Algebraic Data Types

```scalascript
enum Color:
  case Red, Green, Blue

enum Option[+A]:
  case Some(value: A)
  case None
```

Singleton objects may use either significant indentation or explicit braces.
The two forms define the same member scope: every member belongs to the object,
selectors resolve through that owner, and unqualified sibling-member calls stay
inside the object. A trailing `:` opens layout only in a declaration header;
colons used for type ascriptions and return types do not open blocks.

```scalascript
object Parser:
  def char(c: Int): Int = c + 1
  def twice(c: Int): Int = char(char(c))

Parser.twice(1)
```

### 4.4 Type Inference

Types are inferred where possible:

```scalascript
val x = 42          // x: Int
val f = (x: Int) => x + 1  // f: Int => Int
```

Explicit annotations required for recursive functions and public API.

### 4.5 Subtyping

- `Nothing` is bottom type (subtype of all)
- `Any` is top type (supertype of all)
- Variance annotations: `+` covariant, `-` contravariant
- Union types: `A | B`

### 4.6 Higher-Kinded Types

```scalascript
trait Functor[F[_]]:
  def map[A, B](fa: F[A])(f: A => B): F[B]

given Functor[List] with
  def map[A, B](fa: List[A])(f: A => B): List[B] = fa.map(f)
```

A named `given` is a first-class immutable dictionary value. Its concrete
members survive ordinary value flow: it may be passed as an explicit function
argument, returned, aliased, imported, or stored in a collection, and member
selection is owned by that value rather than by the source spelling of the
given. A zero-parameter member behaves as a property; a parameterized member
behaves as a callable closure. Static direct and `summon[...]` access are
observationally equivalent to dispatch through the first-class dictionary.

### 4.7 Checked Errors — `throws`

```scalascript
// throws[A, E] = Either[E, A]  (infix alias)
def parseInt(s: String): Int throws ParseError =
  if validInt(s) then s.toInt          // auto-converted to Right(...)
  else ParseError(s"bad: $s")          // auto-converted to Left(...)

// Raw union variant
def divide(a: Int, b: Int): Int throwsRaw DivByZero =
  if b == 0 then DivByZero else a / b

// Interop helpers
attemptCatch { parseInt("42") }        // wraps JVM exceptions into Left
HasStackTrace                          // typeclass for stack-trace support
```

`throws[A, E]` is sugar for `Either[E, A]`. It integrates with direct-syntax
(§7.6) automatically — monadic bind short-circuits on `Left`.

## 5. Expressions

### 5.1 Literals

```scalascript
42                  // Int
3.14                // Double
"hello"             // String
true                // Boolean
()                  // Unit
```

### 5.2 Definitions

```scalascript
val x: Int = 42                       // immutable value
var y: Int = 0                        // mutable variable
def f(x: Int): Int = x+1              // function
def g(x: Int, step: Int = 1): Int = x + step  // default parameter
type Alias = List[Int]                // type alias
inline def double(x: Int): Int = x * 2        // inline (compile-time)
```

Assignment to an existing `var` is an expression of type `Unit`. It is valid in
branch and loop bodies (for example `if ok then total = total + 1` and
`for x <- xs do total = total + x`). Named call arguments remain call syntax,
and `==` remains equality rather than assignment.

### 5.3 Control Flow

```scalascript
if condition then expr1 else expr2

expr match
  case pattern1 => result1
  case pattern2 => result2

for x <- collection yield transform(x)

while condition do expr
```

### 5.4 Pattern Matching

```scalascript
x match
  case 0 => "zero"
  case n if n > 0 => "positive"
  case _ => "negative"

// Destructuring
case (a, b) => a + b
case Some(x) => x
case whole @ Some(x) => (whole, x)
case head :: tail => head
```

A constructor bind pattern `name @ Constructor(fields...)` binds `name` to the
whole matched value while ordinary field binders remain available inside the
arm. Constructor mismatch continues with the next arm and the scrutinee is
evaluated once.

A typed binder's annotation ends before a depth-zero guard or arm arrow.
Nested type delimiters remain part of the annotation:

```scalascript
value match
  case item: Wrapper[Entry] if item.enabled => item.value
  case item: Entry                         => item.value
  case _                                   => 0
```

The `if` and `=>` tokens above belong to the match arm, not to the pattern
type. Function arrows remain valid in general type annotations; this boundary
rule is specific to typed match patterns.

A typed binder is a runtime nominal test, not an erased binder annotation. For
a portable case-class, case-object, or enum type head `T`, `case value: T`
matches only a value carrying the nominal runtime tag `T`; a different tag or
a non-data value falls through to the next arm. On success the complete
scrutinee is bound to `value`. Type arguments are erased after their balanced
syntax has been checked, but the outer nominal type head is retained in CoreIR.

Literal and typed patterns retain the same semantics when nested in a
constructor or tuple field. Fields are checked left-to-right at their exact
positions before a guard or arm body runs; any failed literal equality,
portable nominal type test, or nested constructor continues with the next
source arm without exposing rejected-arm bindings.

A `case object` is one stable nullary constructor value. It can be imported,
referenced directly, compared structurally, and matched by name:

```scalascript
trait ParserContext
case object NoContext extends ParserContext

NoContext match
  case NoContext => "default"
  case _         => "custom"
```

Two-element tuples have one source-level identity regardless of their internal
producer. Tuple literals, `key -> value`, collection operations such as `zip`
and `zipWithIndex`, map entries, and imported values all support `._1`/`._2`
and `(left, right)` patterns. Backend-internal `Pair/2` versus `Tuple2/2` tags
are not observable at the ScalaScript source level.

For `List.map` and `List.flatMap`, an arity-two callback may consume one
two-element tuple: `(left, right) => body` receives the tuple fields in order
whether the producer used portable `Pair/2` or runtime `Tuple2/2`. An arity-one
callback receives the complete tuple, while ordinary direct arity-two calls,
folds, and binary comparators retain their existing argument contracts.

### 5.5 Functional Updates and Lenses

Case classes carry an auto-generated `copy` method:

```scalascript
case class Person(name: String, age: Int)
val alice = Person("Alice", 30)
val older = alice.copy(age = 31)
```

Named copy arguments select fields by their declared labels, independent of
call-site order; fields without an override retain the receiver value. The
receiver and override expressions evaluate once from left to right. Positional
copy arguments retain declaration-order prefix replacement. The ScalaScript
2.1 compiler-free standard path carries this contract through portable case
field metadata and never falls back to the compatibility frontend.

`Focus[T](_.field.subfield)` builds a `Lens`:

```scalascript
val ageLens = Focus[Person](_.age)
ageLens.get(alice)              // 30
ageLens.set(alice, 99)          // Person(Alice, 99)
ageLens.modify(alice, _ + 10)   // Person(Alice, 40)
```

`.some` in a Focus path produces an `Optional`; `.each` produces a `Traversal`.
`Prism[Outer, Variant]` focuses on a single enum case.
All optics compose via `.andThen`.

On the ScalaScript 2.1 compiler-free standard path, Focus accepts structural
field chains plus `.some` and `.each`, lowering them to portable immutable step
data consumed by a core-free optics provider. Prism retains the exact variant
type argument as a portable nominal tag. Arbitrary getter lambdas are outside
this bounded native contract and fail explicitly; neither construct triggers a
compatibility frontend fallback.

### 5.6 Extension Methods

An extension declaration uses significant indentation to delimit its members.
Each indented definition receives the declared receiver; dedent closes the
extension before the next top-level declaration. A Markdown fenced-code
boundary also returns to the enclosing indentation and therefore closes an
open extension body.

```scalascript
extension [A](xs: List[A])
  def second: Option[A] = xs.drop(1).headOption
  def twiceSize(): Int = xs.length * 2

def ordinary(x: Int): Int = x + 1 // not an extension member
```

Nested delimiters in receiver types and parameter types do not affect the
layout boundary.

Nested layout inside one member also does not close the surrounding extension.
Every later member still receives the declared receiver, even when it has no
explicit parameter list; only the extension's own dedent or closing brace ends
receiver ownership:

```scalascript
extension (p: Parser)
  def guarded: Parser =
    readContext { ctx =>
      ctx match
        case Active => p
        case _      => fail
    }
  def repeat: Parser = p.many()
```

An in-scope symbolic extension is selected for non-primitive operands. Built-in
numeric semantics remain authoritative for their primitive operand types, so a
module may use an ADT extension named `|` and integer bitwise OR together:

```scalascript
val parserChoice = identifier | integer
val permissionMask = 6 | 3 // 7
```

Imported extensions follow the same rule as local declarations.

### 5.7 String Interpolators

```scalascript
s"Hello, $name"                   // standard interpolation
s"Items: ${items.mkString(", ")}" // complete braced expression; nested quotes are balanced
md"# $title\n$body"               // markdown (strips indent)
html"<p>Hello, $userInput</p>"    // HTML-escaped
css".root { color: $color; }"     // CSS
```

The expression inside `${...}` uses the ordinary ScalaScript expression
grammar. Its nested parentheses, braces, and quoted strings are balanced before
the outer interpolated string resumes.

User-defined interpolators are extension methods on `StringContext`.

## 6. Module System

### 6.1 Module Identity

Defined in front-matter:

```yaml
---
name: my-module
version: 1.0.0
---
```

### 6.2 Exports

Explicit exports in front-matter, or everything at top scope is public by default:

```yaml
exports:
  - publicFunction
  - PublicType
```

### 6.3 Imports

Via Markdown links in the prose:

```markdown
[collections](./std/collections.ssc)

```scalascript
val xs = List(1, 2, 3)
\```
```

Selective and aliased:

```markdown
[List, Map](./std/collections.ssc)
[Card as UICard](./ui/card.ssc)
[Card as ChartCard](./chart/card.ssc)
[contentDocument](std/content.ssc) [contentToolkitNode](std/ui/content.ssc)
```

Several import links may appear in one pure Markdown paragraph, separated only
by whitespace or Markdown line breaks. The parser lowers each link to one
`Content.Import` in source order. Links embedded in prose sentences are normal
Markdown links, and `#...` destinations are internal references, not module
imports.

### 6.4 Package Prefix

A module's `package:` field puts all top-level declarations under a dotted namespace:

```yaml
---
package: org.example.ui
---
```

Importers access through the full path: `org.example.ui.Card.render(...)`.

### 6.5 URL Imports

```markdown
[Card](https://raw.githubusercontent.com/u/r/v1.0/card.ssc)
```

Files are fetched once and cached at `~/.cache/ssc/<scheme>/<authority>/<path>`.
Set `SSC_NO_NETWORK=1` to disallow outbound fetches.

### 6.6 Dependency Imports

```yaml
---
dependencies:
  cards: https://raw.githubusercontent.com/u/cards/v1.0
---
```

```markdown
[Card](cards://card.ssc)     → https://.../v1.0/card.ssc
```

`ssc.lock` pins exact revisions for reproducible builds.

### 6.7 Visibility

- Top-level in `# Heading` scope: public by default
- `## Private` section: private to parent scope
- Explicit `private` modifier in code blocks

### 6.8 Plugin System

`.sscpkg` archives bundle `.ssc` files for distribution:

```bash
ssc plugin install ./my-plugin.sscpkg
ssc plugin list
ssc plugin uninstall my-plugin
ssc plugin pack src/   # create .sscpkg
```

The global registry is at `~/.scalascript/registry.yaml`.

### 6.9 Separate Compilation (v2.0)

```bash
ssc emit-interface file.ssc    # → .scim (interface)
ssc emit-ir file.ssc           # → .scir (normalized IR)
ssc compile-jvm file.ssc       # → .scjvm (JVM artifact)
ssc compile-js file.ssc        # → .scjs (JS artifact)
ssc link --backend jvm dir/    # link artifacts → executable
ssc build --incremental dir/   # incremental build of a directory
ssc deps file.ssc              # print import closure
ssc info artifact.scjvm        # inspect artifact metadata
```

Artifact formats: `.scim` (interface), `.scir` (IR), `.scjvm` (JVM), `.scjs` (JS).

Markdown-hosted content is part of the artifact contract when a module carries
a content snapshot. `.scir` preserves `NormalizedModule.document`, and `.sscc`
v3 preserves `Module.document` through an optional trailing content payload
after the executable token stream. Older or manually constructed artifacts may
still have no snapshot; content helpers then use the existing missing-content
diagnostic rather than reconstructing partial content from execution sections.

## 7. Semantics

### 7.1 Evaluation Order

- Strict evaluation (not lazy by default)
- Left-to-right argument evaluation
- Short-circuit for `&&` and `||`

### 7.2 Algebraic Effects

ScalaScript supports algebraic effects and handlers — a structured mechanism
for defining, performing, and intercepting side effects.

Reference semantics use the stack-safe outer protocol `Pure | Op(operation, k)`;
an implementation may retain `FlatMap` or another private iterative form for
performance. `resume(v)` invokes the captured semantic continuation directly;
multi-shot handlers interpret each `resume` branch independently.

The raw `Pure | Op` library substrate is reusable. Typed `.ssc` declarations
refine that substrate with explicit multiplicity: plain `effect E` is one-shot
(zero or one resume per performed operation), while `multi effect E` is reusable
(zero, one, or many resumes). A second or concurrent losing one-shot attempt is
the structured `ResumeRejected.AlreadyResumed(OperationId)` failure. The semantic
operation is `tryResume(value): Either[ResumeRejected, Next]`. The source-compatible
`.ssc resume(value): R` projection maps `Left` to the non-user-catchable runner
outcome `ControlRunFailure(AlreadyResumed(operation))`, rendered with stable code
`ONESHOT_VIOLATION` and message `One-shot violation: <Effect>.<op> resumed more
than once`. Multiplicity is preserved by the continuation itself through deep
handling and forwarding; persistence and backend lowering never upgrade
`OneShot` to `Reusable`.

`effect.perform` and `effect.handle` are outer-language operations/lowering
markers, not dedicated CoreIR term nodes. A lane may lower them completely to
ordinary constructor data and lambdas, or emit calls through the kernel's generic
`Prim("effect.perform"/"effect.perform.oneshot"/"effect.handle", ...)` node;
those primitives must produce and fold the same three-field `Pure | Op`
data/lambda protocol. Raw `effect.perform(label, args...)` remains reusable for
CoreIR/Mira library compatibility; typed plain `.ssc effect` lowers through
`effect.perform.oneshot(effectId, operationName, args...)`, carrying structured
identity without parsing the legacy display/dispatch label. The additive semantic
ABI id is `effect.perform.oneshot@1`; generic `Prim` encoding and the CoreIR codec
do not change, while runtime/AOT primitive tables and durable dependency manifests
must name the implementation explicitly. An unhandled operation remains an explicit computation
value until the nearest enclosing handler can consume it; top-level reporting
must not pre-empt that handler. Operation argument packing, deep resume, `Return`
handling, multiplicity, and diagnostics are identical on every capability-admitted
lane. In the current v2 delivery that means portable JVM VM/direct ASM and Swift
AOT; new v2 JS/Rust/WASM remain unsupported for the whole `effect.*` primitive
family. CoreIR itself remains effect-node-free.

Supported by every backend that advertises the algebraic-effects capability;
claiming the capability requires the shared semantic vectors to pass.

#### 7.2.1 Effect Declaration and Use

```scalascript
effect Console:
  def log(msg: String): Unit
  def readLine(): String

def program(): Unit =
  Console.log("Enter name:")
  val name = Console.readLine()
  Console.log(s"Hello, $name!")

handle(program()) {
  case Console.log(msg, resume) =>
    println(msg)
    resume(())
  case Console.readLine(resume) =>
    resume("Ada")
}
```

#### 7.2.2 Multi-shot Handlers (Nondeterminism)

```scalascript
multi effect Choose:
  def pick[A](xs: List[A]): A

handle(program()) {
  case Choose.pick(xs, resume) =>
    xs.flatMap(x => resume(x))
}
```

#### 7.2.3 Standard Effects

Pre-built effect modules in `std/effects/`:

| Effect | Key operations | Default handler | Test handler |
|--------|----------------|-----------------|--------------|
| `Logger` | `Logger.info/warn/error(msg)` | `runConsoleLogger` | `runTestLogger` (captures) |
| `Random` | `Random.nextInt(n)`, `nextDouble()` | `runSystemRandom` | `runSeededRandom(seed)` |
| `Clock` | `Clock.now()`, `Clock.millis()` | `runSystemClock` | `runFixedClock(t)` |
| `State[S]` | `State.get`, `State.set(v)`, `State.modify(f)` | `runState(init)` | same |
| `Env` | `Env.get(key)` | `runSystemEnv` | `runEnv(map)` |
| `Http` | `Http.get(url)`, `Http.post(url, body)` | `runHttpClient` | `runMockHttp(routes)` |
| `Retry` | `Retry.attempt(n)(body)` | `runRetry(policy)` | same |
| `Cache` | `Cache.getOrSet(key)(body)` | `runCache` | `runMockCache` |
| `Tx` | `Tx.begin/commit/rollback` | `runTx` | `runTestTx` |
| `Auth` | `Auth.check(claims)` | `runAuth(verifier)` | `runTestAuth` |

#### 7.2.4 Delimited Control and Saved Continuations

Typed generative prompts provide multi-prompt `shift`/`reset`. A matching reset
captures only the delimited continuation up to the nearest reset for that prompt;
the continuation is reusable and deep handlers are reinstalled around every
resume. `callCC` is not part of the language contract.

A reusable continuation may be frozen with `save` and executed with `run`.
`SavedContinuation.run` may be called repeatedly, including after transfer to a
different process or machine. Each call begins at the capture point: the prefix is
never replayed, and one suffix execution starts per explicit call; effects inside
that suffix follow its own loops and nested multi-shot control flow.
Portable saved code is a typed envelope around a closed ordinary CoreIR program;
mixed Scala/JVM frames use an exact-artifact fallback.

The target-neutral laws, capsule, capture barriers, security model, and common
conformance contract are specified in
[`specs/control-interoperability.md`](specs/control-interoperability.md). Concrete
Scala/JVM API and artifact mappings are the
[`Scala 3 host profile`](specs/scala3-bidirectional-control.md); JS/TypeScript,
Rust, Swift, and WASM/WASI profiles refine the same contract without redefining it.

### 7.3 Direct Syntax (Do-Notation)

Direct syntax desugars to for-comprehensions over any `Monad[M]`.

#### 7.3.1 Explicit Block

```scalascript
val result = direct[Async] {
  raw    = fetchRaw()          // Async[String] — monadic bind
  parsed = parse(raw)          // pure — no bind
  count  = lookupCount(parsed) // Async[Int]
  count * 2
}
```

#### 7.3.2 Implicit (Type-Directed) Block

When the expected return type is `M[A]` and the block contains a bind-form,
the block is automatically treated as a direct block:

```scalascript
route("GET", "/user/:id") { req =>
  // Return type is Async[Response] → implicit direct block
  user   = Async.delay(loadUser(req.params("id")))
  orders = Async.delay(loadOrders(user.id))
  Response.json(user, orders)
}
```

#### 7.3.3 Postfix Bind Operator (`.!`)

```scalascript
direct[Option] {
  x = Some(42).!             // bind in-place, returns unwrapped value
  Some(x * 2)
}
```

#### 7.3.4 Bind Rules

| Form | Lowering |
|------|----------|
| `val x = expr` | Pure local binding — no bind |
| `x = expr` (no `val`) | Monadic bind; pure `expr` auto-lifts via `Monad.pure` |
| Bare `expr: M[*]` | Bind-and-discard (`_ <- expr`) |
| `var v = expr` | Mutable-var semantics — never monadic |
| Last expression | Yield clause; pure values auto-lift |

#### 7.3.5 Effect-Row Unions

```scalascript
direct[Async | Random] {
  n = Random.nextInt(100)
  result = Async.delay(compute(n))
  result
}
```

The leftmost type drives `flatMap` dispatch.

On the ScalaScript 2.1 compiler-free standard path, explicit `direct[Option]`
and `direct[List]` blocks have a bounded self-hosted lowering: fresh
assignments become ordered `flatMap` binds, pure `val` stays lexical, declared
`var` names retain mutable assignment, and nested explicit blocks lower
independently. The final expression must already inhabit the selected monad.
Implicit direct inference, other monads, and pure auto-lift remain outside this
bounded native contract and never trigger a compatibility fallback.

### 7.4 Built-in Async Effect

`Async` is pre-registered on all three backends — no `effect Async:` declaration needed.

```scalascript
runAsync {
  val a = Async.async(() => 1)
  val b = Async.async(() => 2)
  Async.await(a) + Async.await(b)          // 3
}
```

| Operation | Effect |
|-----------|--------|
| `Async.delay(ms)` | Pause the calling thread for `ms` milliseconds |
| `Async.async(thunk)` | Execute `thunk`, wrap result in `Future[A]` |
| `Async.await(fut)` | Extract the value from a `Future[A]` |
| `Async.parallel(thunks)` | Run each thunk, collect results in declared order |
| `Async.recvFrom(ws)` | Receive next WS message as async operation |

`runAsync` is deterministic and single-threaded; byte-identical output across all backends.
`runAsyncParallel` uses real JVM threads (`ExecutorService` + `CompletableFuture`).

### 7.5 Coroutines

Three primitive operations and one ADT:

```scalascript
enum Step[+Y, +T]:
  case Yielded(value: Y)
  case Returned(value: T)
  case Cancelled

extern def coroutineCreate[Y, R, T](body: => T): Coroutine[Y, R, T]
extern def coroutineResume[Y, R, T](co: Coroutine[Y, R, T], in: R): Step[Y, T]
extern def suspend[Y, R](out: Y): R
extern def coroutineCancel[Y, R, T](co: Coroutine[Y, R, T]): Unit
```

`coroutineCreate` is lazy — the body does not start until the first `coroutineResume`.
`suspend(y)` is dynamically scoped to the innermost active coroutine.
Cancelling a coroutine invalidates the handle; subsequent `coroutineResume` calls throw.

#### 7.5.1 Generators

```scalascript
val gen = generator[Int] {
  yield(1)
  yield(2)
  yield(3)
}
// gen.next()     → Some(1), Some(2), Some(3), None
// gen.toList     → List(1, 2, 3)
// gen.map(f)     → lazy-mapped generator
```

`fromGenerator(gen)` converts to a `Dataset[T]` source.

### 7.6 Reactive Signals

```scalascript
val count   = Signal(0)
val doubled = computed { count.get * 2 }
effect { println("c=" + count.get + " d=" + doubled.get) }
count.set(5)     // → prints "c=5 d=10"
```

| Primitive | Effect |
|-----------|--------|
| `Signal[A](initial)` | Mutable reactive cell |
| `computed[A] { expr }` | Read-only derived Signal, auto-tracks dependencies |
| `effect { expr }` | Reactive side-effect; reruns on dependency changes |

Diamond-dedup flush: each effect reruns at most once per synchronous transaction.

### 7.7 Tail-Call Optimisation

Self-recursive and mutual tail calls are stack-safe without `@tailrec`:

| Backend | Self-TCO | Mutual TCO | Mechanism |
|---------|----------|------------|-----------|
| JVM interpreter | ✅ | ✅ | Trampoline catching `TailCall` signals |
| JS transpiler | ✅ | ✅ | `while`-loop reassignment + `_trampoline` |
| JVM backend | ✅ | ✅ | Scala 3 native + SCC-based mutual rewrite |

### 7.8 Actors

```scalascript
runActors {
  val server = spawn {
    receive {
      case "ping" => self() ! "pong"
      case "stop" => exit(self(), "normal")
    }
  }

  server ! "ping"

  receive {
    case "pong" => println("got pong")
  }
}
```

| Primitive | Description |
|-----------|-------------|
| `spawn { body }` | Create an actor, return its `Pid` |
| `self()` | Current actor's `Pid` |
| `pid ! msg` | Send message to `pid` |
| `receive { case ... }` | Block until a matching message arrives |
| `receive(timeout = N) { case ... }` | Receive with timeout, returns `Option[A]` |
| `link(pid)` | Link to another actor — mutual death notification |
| `exit(pid, reason)` | Terminate an actor |

Supervision: `link`ed actors receive EXIT signals; `trapExit = true` converts signals
to messages. Actors are backed by virtual threads (JVM) or microtasks (JS/INT).

#### 7.8.1 Distributed Actors

```scalascript
// Connect to the cluster (WS transport)
val cluster = clusterConnect("ws://node1:4242")

// Spawn an actor on a remote node
val remotePid = cluster.spawn("node2") { ... }
remotePid ! MyMessage("hello")
```

Cluster features: bully leader election, Phi-accrual failure detector, gossip protocol,
configuration distribution, membership events.

### 7.9 HTTP Server

```scalascript
route("GET", "/hello") { req =>
  Response.text("Hello, World!")
}

route("GET", "/users/:id") { req =>
  val id = req.params("id")
  Response.json(lookupUser(id))
}

serve(8080)
```

#### 7.9.1 Request

```scalascript
case class Request(
  method:  String,
  path:    String,
  params:  Map[String, String],   // :name path captures
  query:   Map[String, String],   // ?k=v
  headers: Map[String, String],
  body:    String,
  form:    Map[String, String],   // application/x-www-form-urlencoded
  session: Map[String, String]    // signed cookie session
)
```

Extension properties: `req.json`, `req.bearerToken`, `req.jwtClaims`, `req.basicAuth`.

#### 7.9.2 Response

```scalascript
Response.html(body)
Response.text(body)
Response.json(value)              // structural JSON encoder
Response.redirect(url)
Response.notFound(body)
Response.status(code, body)
response.withSession(Map(...))    // attach signed cookie session
response.clearSession()           // Max-Age=0
```

#### 7.9.3 Streaming and SSE

```scalascript
route("GET", "/events") { req =>
  sse(req) { sink =>
    sink.send("data: hello\n\n")
    // ...
  }
}

route("GET", "/large-file") { req =>
  streamResponse { sink =>
    sink.write(chunk)
    sink.flush()
  }
}
```

#### 7.9.4 Middleware

```scalascript
useCors("*")                          // CORS headers
useGzip()                             // response compression
useCacheHeaders(maxAge = 3600)        // Cache-Control
```

Built-in routes: `/_health` (liveness), `/_ready` (readiness).

#### 7.9.5 Interpreter execution boundary

Network backends may parse requests and write responses concurrently. For an
interpreter-backed server, safe HTTP methods (`GET`, `HEAD`, `OPTIONS`) share a
per-`Interpreter` read section; mutating methods and WebSocket callbacks use its
fair exclusive section. This boundary is reentrant (`middleware -> next ->
route` remains valid), prevents a stream of reads from starving a queued write,
and does not serialize unrelated interpreters, unmatched static-file fallbacks,
or socket I/O. Explicit Async/actor facilities remain the way to express
parallel mutation.

### 7.10 WebSocket Server

```scalascript
onWebSocket("/chat") { ws =>
  while ws.isClosed.not do
    ws.recv() match
      case Some(msg) => ws.send(s"echo: $msg")
      case None      => ()
}

serve(8080)
```

| Property / Method | Description |
|-------------------|-------------|
| `ws.id` | Unique connection identifier |
| `ws.subprotocol` | Negotiated subprotocol |
| `ws.user` | Authenticated user (if using auth middleware) |
| `ws.send(msg)` | Send a text frame |
| `ws.recv()` | Receive next frame (synchronous, `Option[String]`) |
| `ws.close()` | Close the connection |
| `ws.ping()` | Send a ping frame |

Route options: `maxConnections`, `rateLimit`. TLS: prefix path with `wss://` and call `tls(cert, key)`.

### 7.11 TLS

```scalascript
val tlsConfig = tls("server.crt", "server.key")
serve(443, tls = tlsConfig)         // HTTPS
onWebSocket("/chat")  { ws => ... }  // automatically WSS over port 443
```

### 7.12 HTTP Client

```scalascript
val body = httpGet("https://api.example.com/users")
val resp = httpPost("https://api.example.com/users", """{"name":"Alice"}""")

// Configured client
httpClient {
  baseUrl("https://api.example.com")
  header("Authorization", s"Bearer $token")
} { client =>
  val users = client.get("/users")
  val user  = client.post("/users", body)
}

// Streaming (SSE / LLM)
httpGetStream("https://api.example.com/sse") { chunk =>
  print(chunk)
}
```

### 7.13 Authentication

#### Password Hashing

```scalascript
val hash = hashPassword("secret123")        // PBKDF2-SHA256
verifyPassword("secret123", hash)           // true
```

#### JWT

```scalascript
val token = jwtSign(Map("sub" -> "alice", "role" -> "admin"))
jwtVerify(token) match
  case Some(claims) => ...     // HS256 (default) or RS256
  case None         => ...     // missing / tampered / expired
```

Secret resolution: `SSC_JWT_SECRET` → `SSC_SESSION_SECRET`.

#### TOTP 2FA

```scalascript
val secret = totpGenerateSecret()
val qrUrl  = totpQrUrl(secret, "alice@example.com", "MyApp")
val ok = totpVerify(secret, userCode)   // 6-digit TOTP code
```

#### WebAuthn / Passkeys

```scalascript
val challenge = webAuthnRegisterChallenge(userId, username)
// ... send to browser, get credential response ...
val cred = webAuthnVerify(challenge, credentialResponse)
```

#### OAuth2

```scalascript
val authUrl = oauthAuthorizeUrl("google", clientId, redirectUri, state)
val tokens  = oauthExchangeCode("google", code, clientId, secret, redirectUri)
val profile = oauthUserinfo("google", tokens("access_token"))
```

Built-in providers: `google`, `github`.

#### Session Cookie

Wire format: `session=<b64url(json(payload))>.<b64url(hmac_sha256)>`.
Secret: `SSC_SESSION_SECRET`. Server-side store: `useSessionStore(ttlSeconds = 1800)`.

#### CSRF

```scalascript
val token = csrfToken()                // fresh url-safe random string
csrfValid(req)                         // checks form "csrf" or X-CSRF-Token header
```

### 7.14 REST Ergonomics

```scalascript
// JSON parsing
val v: JsonValue = jsonParse(req.body)
val name = v.field("name").asString
req.json.field("count").asInt

// JSON serialization
jsonStringify(Map("ok" -> true, "count" -> 42))
jsonRead[User](req.body)                // auto-decode via derives

// Validation
validate {
  val name  = requireString(args, "name")
  val age   = requireRange(args, "age", 0, 150)
  val color = requireOneOf(args, "color", List("red", "green", "blue"))
  MyModel(name, age, color)
}
// returns Either[List[ValidationError], MyModel]
```

### 7.15 MCP — Model Context Protocol

```scalascript
mcpServer { srv =>
  srv.tool("get_weather") { args =>
    val city = requireString(args, "city")
    Tool.text(s"Weather in $city: sunny, 22°C")
  }

  srv.resource("file:///readme.md") { uri =>
    Resource.text(loadFile("./README.md"), mimeType = "text/markdown")
  }

  srv.prompt("greet") { args =>
    val name = requireString(args, "name")
    Prompt.messages(Message.user(s"Greet $name warmly."))
  }
}

serveMcp(Transport.stdio)          // or Transport.Http(port) / Transport.Ws(port)
```

MCP client:

```scalascript
mcpConnect("ws://localhost:3001") { client =>
  val result = client.callTool("get_weather", Map("city" -> "London"))
  println(result.text)
}
```

Supported on JS and JVM backends (wraps platform-native MCP SDK).

### 7.16 Dataset / MapReduce

```scalascript
val ds = Dataset.from(List(1, 2, 3, 4, 5))
val result = ds
  .filter(_ % 2 == 0)
  .map(_ * 10)
  .collect()                       // List(20, 40)

// Aggregations
ds.top(3)                          // 3 largest
ds.countByValue()                  // Map[T, Long]
ds.partition(3)                    // List[Dataset[T]] (3 partitions)
ds.mkString(", ")                  // "1, 2, 3, 4, 5"

// Execution modes
ds.runLocal()                      // sequential, single-threaded
ds.runParallel()                   // local multi-core
ds.runDistributed(clusterNodes)    // actor-based distributed

// Key-based (triggers shuffle in distributed mode)
wordCounts = words.groupBy(identity).mapValues(_.size)
wordCounts = words.reduceByKey(identity)((a, b) => a + b)

// Input
Dataset.fromFile("data.csv")       // line-by-line
Dataset.fromLines(lines)
```

`Dataset.saveToFile(path)` writes results; `toMap`, `toSet` convert terminal ops.

#### Distributed Execution

```scalascript
ds.runDistributed(nodes,
  handler = "myWorker",            // named actor handler
  failurePolicy = FailurePolicy.RetryOnce
)
```

Distributed shuffle is actor-based (v1.6 Phase 3). Workers partitioned by key hash.

### 7.17 DSL Authoring

#### Parser Combinators — `std/parsing/*`

```scalascript
// Primitive parsers
val digit: Parser[Char]   = Parser.satisfy(_.isDigit)
val letter: Parser[Char]  = Parser.satisfy(_.isLetter)
val ws: Parser[Unit]      = Parser.whitespace

// Combinators
val integer: Parser[Int] = digit.rep1.map(_.mkString.toInt)
val ident: Parser[String] = (letter ~ (letter | digit).rep).map { case (h, t) => h +: t }.map(_.mkString)

// Sequencing
val pair = integer ~> ws ~> integer   // keeps right
val both = integer <~ ws              // keeps left
val seq  = integer ~ integer          // keeps both as pair

// Choice and repetition
val expr  = integer | ident
val items = integer.rep               // zero or more
val list  = integer.sep(char(','))    // separated list

// Error recovery
val stmt = expr.recoverUntil(char(';'))   // skip bad tokens until ';'

// Indentation-aware
val block = withIndent {
  line(stmt).rep
}
```

Collection folds accept a curried second parameter list or trailing block.
`xs.foldLeft(z) { (acc, value) => next }` first produces an arity-one partial
method closure from `foldLeft(z)`, then applies the fold function. Native VM and
direct ASM must delegate completion to the same effect-aware collection fold
semantics.

Parser nodes remain ordinary portable algebraic data across module imports.
For example, an imported `runParser` evaluator matches
`PMapped(PSucceed(value), f)` by the `PMapped/2` constructor, applies `f` to the
successful value, and returns `ParseOk` with the unchanged remaining input and
position. Native VM and direct ASM implementations must preserve this behavior
without a host-side parser special case.

Successful parser composition must also preserve the mapped value itself.
Lists accumulated by `many`/`foldLeft`, tuple-shaped parser results, and user
ADTs built from them remain ordinary portable values through later `map`,
pattern matching, rendering, and lookup. A `Stub` placeholder is not a valid
successful parser value even when VM and direct ASM output agree.

#### Multi-Pass Pipelines — `std/dsl/*`

```scalascript
// Pass[A, B] — a compilation/transformation stage
val parse:   Pass[String, Ast]   = Pass.of(parseSource)
val typecheck: Pass[Ast, TypedAst] = Pass.of(checkTypes)
val codegen: Pass[TypedAst, Code] = Pass.of(generate)

val pipeline = parse andThen typecheck andThen codegen
val result   = pipeline.run(source)

// Parallel passes (run independently, combine)
val analysis = typecheck parallel sideEffectAnalysis

// Walkers / catamorphisms
val eval: Ast => Value = cata[Ast, Value] {
  case Lit(n)      => Value.Int(n)
  case Add(l, r)   => l + r
  case If(c, t, e) => if c then t else e
}
```

### 7.18 Metaprogramming

```scalascript
// Inline definitions — erased at compile time
inline val MaxSize = 1024
inline def debug(msg: String): Unit = compiletime.error("debug not for prod")

inline def ifElse[A](cond: Boolean)(thenExpr: => A)(elseExpr: => A): A =
  inline if cond then thenExpr else elseExpr

// Compile-time reflection
compiletime.constValue[42]           // Int = 42
compiletime.summonInline[Show[Int]]  // resolves given at compile time
compiletime.error("message")         // compile-time error

// Derives
case class Point(x: Double, y: Double) derives Eq, Show, Hash, Order

// Available derives
derives Eq           // structural equality
derives Show         // string representation
derives Hash         // hash code
derives Order        // total ordering
derives Foldable     // fold over fields
derives Traversable  // traverse with effect
derives Functor      // map over type parameter
```

On the ScalaScript 2.1 standard native path, a product case class also exposes
portable `Mirror.Of[T]` / `Mirror.ProductOf[T]` metadata with its source label,
ordered element labels, and ordered portable element type spellings. A custom
`derives TC` clause evaluates the known `TC.derived(mirror)` companion method
once and registers the immutable result as exact `TC[T]` evidence. This is
CoreIR language data and does not require compiler reflection or compatibility
fallback. Sum Mirrors and compile-time tuple operations remain outside this
bounded native contract.

## 8. Standard Library

### 8.1 Core Types

- `Option[A]` — optional values
- `Either[E, A]` — error handling; `mapRight`, `flatMapRight`, `foldEither`
- `List[A]` — immutable list with full combinators; `xs :+ x` returns a new
  `List[A]` with `x` appended after every element of `xs`
- `Map[K, V]` — immutable map
- `Set[A]` — immutable set
- `Tuple2[A, B]` through `Tuple22`
- `Free[F[_], A]` — Free monad (`liftF`, `foldMap`, `runM`)

### 8.2 Core Functions

```scalascript
def println(x: Any): Unit
def print(x: Any): Unit
def assert(cond: Boolean, msg: String): Unit
def require(cond: Boolean, msg: String): Unit
def getenv(key: String): String
def getenv(key: String, default: String): String
def doc(parts: Any*): Doc
def render(value: Any): Unit
```

`doc(parts...)` preserves the supplied values in source order as an opaque,
target-independent document value. `render(docValue)` writes each document
part using the same deterministic display semantics as `println`, joined by a
single LF, and terminates the output with the ordinary single trailing LF.
For a non-document value, `render(value)` is equivalent to `println(value)`.
These helpers do not parse Markdown or depend on the structural-content API.
Their ScalaScript 2.1 native ownership and zero-compatibility-dependency
requirements are normative in
[`specs/v2.1-native-doc-render.md`](specs/v2.1-native-doc-render.md).

### 8.3 Standard Typeclasses

Defined in `std/`:

- `Eq[A]` — `===`, `=!=`
- `Show[A]` — `show`
- `Hash[A]` — `hash`
- `Order[A]` — `compare`, `<`, `>`, `<=`, `>=`
- `Semigroup[A]` — `combine`, `|+|`
- `Monoid[A]` — `empty`, `combineAll`
- `Functor[F[_]]` — `map`
- `Applicative[F[_]]` — `pure`, `ap`
- `Monad[F[_]]` — `flatMap`, `flatten`, `pure`
- `Foldable[F[_]]` — `fold`, `foldLeft`, `foldRight`, `toList`
- `Traversable[F[_]]` — `traverse`, `sequence`
- `Either[E, A]` — `Monad[Either[E, *]]`
- `MonadError[F[_], E]` — `raise`, `handleError`, `attempt`
- `Bifunctor[F[_, _]]` — `bimap`
- `Selective[F[_]]` — `select`

### 8.4 i18n

```scalascript
// In front-matter:
// translations:
//   en: { greeting: "Hello" }
//   fr: { greeting: "Bonjour" }

println(t("greeting"))            // "Hello" (uses current locale)
setLocale("fr")
println(t("greeting"))            // "Bonjour"
```

### 8.5 SSR and Web Components

```scalascript
// Server-side rendering with hydration
val component = wc("my-counter", Counter, initialValue = 0)
// Renders to <my-counter> with shadow DOM and hydration script

// Emit as Web Component bundle
// ssc emit-wc file.ssc
```

### 8.6 Component Library

Pre-built UI components in `std/ui/`:
Button, Input, Select, Spinner, Modal, Card, Switch, Alert, Tag, Stats, Empty, Toolbar, Tree,
Stepper, Lightbox, FileUpload, DateInput, DatePicker, DateTimePicker, TimeInput, Combobox,
RangeSlider, Carousel.

Usage:

```scalascript
[Button](std/ui/button.ssc)

```scalascript
val page = html(
  body(
    Button.render("Click me", onClick = "doThing()"),
    Card.render("Title", "Body content here")
  )
)
\```
```

#### 8.6.1 Managed browser GET bindings

`fetchUrlSignal` and `fetchUrlSignalTo` are persistent browser bindings: they
fetch on mount and again when their refresh tick (or reactive URL) changes. A
fulfilled response writes its text body using the existing HTTP-status
semantics. If the transport or response-body read rejects, the generated SPA
runtime consumes that rejection, retains the signal's last-good value, and
keeps the binding subscribed for a later refresh. Expected offline operation
must not produce an unhandled browser promise rejection. The detailed runtime
contract is normative in
[`specs/ui-fetch-get-offline-rejection.md`](specs/ui-fetch-get-offline-rejection.md).

### 8.7 Web Primitives (REST/HTTP)

See §7.9–7.14 for the full HTTP/WS/auth surface. Static-rendering variants:

```bash
ssc render file.ssc /path    # headless GET — prints response body
ssc build src/ dist/         # static site generator
ssc bundle file.ssc          # pack into .sscpkg
```

## 9. Backends

### 9.1 Backend SPI

Every backend implements `scalascript.backend.spi.Backend`:

```scala
trait Backend:
  def id: String
  def displayName: String
  def spiVersion: String
  def capabilities: Capabilities
  def intrinsics: Map[ir.QualifiedName, IntrinsicImpl]
  def acceptedSources: Set[String]
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult
```

Discovery via `ServiceLoader` (in-process JARs) or `plugin.yaml` (subprocess).

### 9.2 Bundled Backends

| id | Display name | Default invocation | Output | Notes |
|----|--------------|--------------------|--------|-------|
| `v2` | Native CoreIR VM / direct ASM | `ssc run`, `ssc run --bytecode`, `ssc build-jvm` | Executed in-process or deterministic JVM JAR | The ScalaScript 2.1 standard/default tier; native frontend and checker, with no compiler or Scalameta fallback. |
| `int` | Legacy interpreter (tree-walking) | `ssc-tools run --v1` / `ssc-tools run --compat-frontend` | Executed in-process | Explicit tools/compatibility tier only after the 2.1 launcher cutover. |
| `jvm` | JVM (Scala 3 source) | `bin/sscc`, `ssc compile-jvm` | Scala 3 source → compiled via scala-cli | In-process emitter; scala-cli runs the produced `.scala` file. |
| `js` | JavaScript (Node / SPA) | `bin/jssc`, `ssc emit-js` | JavaScript source (one-shot or segmented) | Same `JsGen` powers Node and browser builds; the latter pairs with `ssc emit-spa`. |
| `node` | Node.js | `ssc run --backend node`, `bin/ssc-node` | Self-contained `.cjs` bundle for `node` | Extends JsGen with verbatim linking of `node.js` opaque-exec blocks (§ 3.3) and a Node-side `_output` flush epilogue. v1.25 Phase 3. |
| `scalajs-spa` | Scala.js SPA bundle | `ssc emit-spa` | Self-contained HTML + JS bundle | Cross-compiles `scala` blocks via Scala.js for browser execution. |
| `wasm` | WebAssembly (Scala.js) | `ssc emit-wasm` | `.wasm` module + JS glue | Re-uses Scala.js's WASM emission path. |
| `swift` | Swift / Apple native | `ssc emit-swift`, `ssc build --target macos\|ios` | Swift Package (`AppCore` plus SwiftUI app target when UI is present) | Consumes checked v2 CoreIR, applies portable Decimal/Money/effect/UI lowering, and never falls back to v1 JvmGen. macOS and iOS share source semantics. Apple UI commands accept `--server-url <absolute-http(s)-url>` as the sole generated base for relative native HTTP requests. See `specs/v2-swift-swiftui-native.md`. |
| `spark` | Apache Spark | `ssc run --backend spark`, `bin/ssc-spark` | Scala 3 + Spark source → `scala-cli run --dep org.apache.spark::spark-{core,sql}:<v>` | Out-of-process — Spark JARs resolved at runtime by Coursier. v1.21 `Dataset[T]` API maps 1-to-1. § 9.5. |

`ssc --list-backends` enumerates in-process bundled backends discovered via `ServiceLoader`.
`spark` is special-cased in `Main.runCommand` until § 9.5 Phase A wraps it in a regular `Backend`.

### 9.2.1 ScalaScript 2.1 toolchain-independent JVM path

The standard ScalaScript 2.1 JVM path is `.ssc -> native frontend -> native
checker -> CoreIR -> v2 VM/direct ASM`. A prebuilt standard installation MUST
run this path without Scalameta, Scala CLI, the Scala compiler, javac, or the
`java.compiler`/`jdk.compiler` modules. It MAY contain the Scala runtime and ASM;
building ScalaScript itself from source remains a separate bootstrap concern.

Plain `ssc run` uses the native frontend and v2 VM after the 2.1 cutover;
`ssc run --bytecode` changes only the execution backend to direct ASM.
`ssc build-jvm ... -o app.jar` is the compiler-free direct-ASM artifact path.
Compiler-backed backends and the v1/Scalameta frontend remain available only as
explicit tools/compatibility-tier choices and never as transparent fallbacks.

The complete dependency tiers, migration flags, artifact requirements, current
baselines, and acceptance gates are normative in
[`specs/v2.1-toolchain-independence.md`](specs/v2.1-toolchain-independence.md).

All normative parsers above the permanent Scala 3 bootstrap seed are
self-hosted in ScalaScript. The seed may decode only its frozen bootstrap
`ssc0`/CoreIR/image formats; JSON, YAML/front-matter, Markdown, portable regex,
and ordinary `.ssc` syntax belong to the self-hosted core or pure standard
library. Standard seed/core modules have no third-party parser dependency;
external libraries are reachable only through explicit backend/plugin choices
or build/test tooling. The complete boundary and migration gates are normative
in [`specs/v2.1-self-hosted-core.md`](specs/v2.1-self-hosted-core.md).

### 9.3 Custom Backends

Two distribution shapes:

- **In-process JAR**: implement `Backend`, drop `META-INF/services/scalascript.backend.spi.Backend`, attach via `ssc --plugin jar`.
- **Subprocess**: any language; `plugin.yaml` + stdio JSON/msgpack protocol.

See [`docs/writing-a-backend.md`](docs/writing-a-backend.md).

### 9.4 Block Language Handling

Each backend chooses how to process every fenced-block lang tag it
encounters in a module's IR.  The contract is per-language:

| Lang class (§ 3.3) | Treatment by backends |
|--------------------|----------------------|
| `scalascript` / `ssc` | Custom transpilation per backend (`JsGen`, `JvmGen`, `Interpreter`, `SparkGen`, …). |
| `scala` | Passed through to scala-cli (JVM target) or Scala.js (JS / WASM / SPA targets); interpreter runs the supported Scala 3 subset. |
| String blocks (`html`, `css`, `javascript`) | Rendered to a `String` value with `${expr}` interpolation; bound to `<sectionId>.<lang>`. Universally supported — every backend renders or stores the value. |
| Opaque-executable blocks (`node.js`, `sql`, …) | Recognised only by the backend(s) that declare the tag in `Capabilities.blockLanguages`.  Any other backend emits `Diagnostic.UnknownBlockLanguage(<tag>)` via `CapabilityCheck`. |
| Inert tags (`python`, `yaml`, `text`, …) | Stored in the IR verbatim; ignored by every bundled backend. |

The JS backend additionally compiles `scala` blocks via Scala.js, and
the JVM backend includes `scala` blocks as-is alongside its
`scalascript`-derived output.

### 9.5 Apache Spark backend

**Status:** Phase A landed (local Spark session via `scala-cli`); Phases B + C open.  See the "Speculative — Apache Spark backend" entry in `MILESTONES.md` for the full plan and rationale.

The Spark target sits at the high-volume end of the same `Dataset[T]`
abstraction (v1.21) that drives the interpreter's in-process map-reduce.
A module with `backend: spark` in its front-matter compiles to Scala 3
- Spark source that `scala-cli` then runs with the right
`org.apache.spark::spark-{core,sql}:<version>` dependencies — no Spark
JARs on the ScalaScript sbt classpath, no compile-time coupling.

```yaml
---
name: word-count
backend: spark
spark-version: 4.0.0     # optional; default is SparkGen.DefaultVersion
---
```

```scalascript
val words = Dataset.fromPath[String]("/data/*.txt")
                   .flatMap(_.split("\\s+").toList)
                   .map(w => (w.toLowerCase, 1))
                   .groupBy(_._1, _._2)
                   .reduce(_ + _)
                   .top(100)

words.foreach { case (w, n) => println(s"$w: $n") }
```

Identical source runs locally (`ssc run word-count.ssc`, interpreter
backend, in-process) and at scale (`ssc-spark word-count.ssc`,
Spark backend).  The user does not rewrite the pipeline to switch.

#### Open phases

| Phase | What | Why | Status |
|-------|------|-----|--------|
| **A — SPI integration** | Wrap the existing `SparkGen` invocation in a proper `Backend extends Backend` with `META-INF/services` registration; remove the `runViaSparkBackend` special case in `Main.runCommand`. | Today Spark is the only bundled target reached through a side-path instead of the SPI; this blocks Capabilities-driven block-language gating (§ 9.4) and `--describe-backend spark`. | open (~½ day) |
| **B.1 — Master URL parameterisation** | `--spark-master <url>` CLI flag + `spark-master:` front-matter key threaded through `BackendOptions.extra("sparkMaster")` into `SparkGen`.  Same source compiles to `local[*]` (default), `local[N]`, `spark://...`, `yarn`, `k8s://...`. | Unblocks running against an existing Spark cluster from the same source that runs locally. | landed |
| **B.2 — `spark-submit` packaging** | `ssc submit file.ssc [--spark-master <url>] [--spark-version <v>] [--dry-run] [-- <extra spark-submit args>]` builds a fat JAR via `scala-cli --power package --assembly` and shells out to `spark-submit --master <url> --class runSparkJob <jar>`.  Pure command builders live in `SparkSubmit.{packageCommand, submitCommand}` so the exact argv is unit-test pinnable; orchestration in `Main.submitCommand` adds file I/O and shell-out.  Args after a literal `--` flow through to `spark-submit` verbatim for cluster-specific tuning (`--executor-memory`, `--num-executors`, `--deploy-mode cluster`, …). | B.1 ships the driver via `scala-cli` which works for Spark Standalone but not for YARN/K8s production deployments. | landed |
| **C.1 — `sql` block → `spark.sql(...)`** | `sql` declared in `SparkCapabilities.blockLanguages`; the shared `SqlBindRewriter` produces `:bind<N>` placeholders consumed by Spark SQL 3.4+'s parameterised `sql(text, args)`.  Each `sql` block binds to a sequential `val _sqlBlock_<n>: org.apache.spark.sql.DataFrame` in the `@main` scope, accessible from subsequent `scalascript` blocks. | Reuses the same `sql` surface as the JDBC target (§ 3.3.1) — same source, different runtime. | landed |
| **C.2 — Section-based binding** | Each `sql` block whose enclosing section has a usable identifier (`# Users`, `# Active Users`, …) ALSO emits `object <sectionId>: lazy val sql: org.apache.spark.sql.DataFrame = _sqlBlock_<n>`.  Friendly access: `Users.sql.show()` instead of `_sqlBlock_0.show()`. | Mirrors the existing `html`/`css` → `<sectionId>.html/css` convention so authoring rules are uniform across opaque blocks. | landed |
| **C.3 — DataFrame ergonomics + schema bridge** | All nine slices landed: (1) `>10` binds via `Map.ofEntries[String, Object]` once `SparkGen.MapOfMaxPairs = 10` is exceeded; (2) widen `sparkImports` with `Row`, `DataFrame`, `types._`; (3) `spark-config:` front-matter → sorted `.config(k, v)` lines on `SparkSession.builder()`; (4) `spark-app-name:` overrides `.appName(...)`; (5)+(6) typed reader shims `Dataset.{fromParquet,fromJson,fromCsv}(path, options*): DataFrame`; (7) writer extensions `ds.{toParquet,toJson,toCsv}(path, opts*): Unit`; (8) adaptive defaults (`spark.ui.enabled=false`, `spark.sql.shuffle.partitions=4`, log4j WARN) emitted ONLY on `local*` masters; (9) schema bridge — `Dataset.schemaOf[T : Encoder]: StructType` + typed reader cousins `Dataset.{fromParquetAs,fromJsonAs,fromCsvAs}[T : Encoder](path, opts*): Dataset[T]` chain `spark.read.schema(schemaOf[T]).options(opts.toMap).X(path).as[T]` so a case-class declaration IS the schema specification.  For CSV the typed reader is the only path to typed columns (Spark's bare `.csv(path)` returns every column as `String`); for JSON it skips the inference scan; for Parquet it acts as column projection. | C.1+C.2 ship the binding/runtime contract; C.3 polishes the typed surface. | landed |
| **D — UDF bridge** | `@SqlFn` marker on a `def` inside a `scalascript` block makes it visible to subsequent `sql` blocks as a Spark UDF: `SparkGen.extractSqlFns` strips the annotation and parses the `def`'s name + param types + return type out of the signature; `genModule` emits a `spark.udf.register("name", new UDF<N>[T1, …, RT] { def call(...) = name(...) }, returnDataType)` call immediately after the cleaned declaration.  Registration happens INSIDE the `@main def runSparkJob` scope so the UDF is on the session catalog before any later sql block tries to call it.  Phase E revival (2026-05-20) routes through Spark's Java `UDFN` functional-interface form rather than the typed `register[RT : TypeTag, ...]` overload — TypeTag-free, so it works under Scala 3 + Spark `_2.13`.  Return DataType is resolved via `SparkGen.SqlFnDataType` (the common primitive map); unmapped types degrade to `StringType` + a `// TODO` comment.  Only `def` is recognised (no `val`-with-function-literal). | Without this, `scalascript` and `sql` blocks could share data (via `_sqlBlock_<N>` / section aliases) but not behaviour — a scalascript helper couldn't be referenced inside a sql query, forcing users back into `df.selectExpr(...)` chains. | landed |
| **E — Scala 3 native Spark Encoder derivation** | Inline `given derived[T <: Product]` in `SscSparkEncoders` (emitted at top of every Spark source) builds `AgnosticEncoders.ProductEncoder[T]` from a Scala 3 `Mirror.ProductOf[T]` and wraps it via `ExpressionEncoder(...)`.  Bypasses Scala 2.13's `scala.reflect.runtime.universe.TypeTag` machinery entirely — no macros, no `scala-reflect` consumption, no third-party libraries.  Primitive encoders are surfaced as plain `given Encoder[String/Int/Long/...]` instances that wrap `Encoders.STRING`/`scalaInt`/etc.; `import spark.implicits._` is dropped from the emit (its TypeTag-bound `newProductEncoder` poisons implicit search) and replaced by `import SscSparkEncoders.given`.  Generated source pins `//> using scala 3.7.1` because Scala 3.8.x has a TASTy-bridge regression that breaks Spark `_2.13` runtime reflection in `ExpressionEncoder` — 3.7.1 is the latest series that works end-to-end.  JVM `--add-opens` flags Spark needs on JDK 17+ are baked in as `//> using javaOpt` directives so `scala-cli run <file>` works without extra args.  Result: `Dataset[CaseClass]` runs end-to-end on Scala 3 + Spark 4 + JVM 21 — `printSchema`, `count`, `filter`, `collect` all preserve the case-class structure.  See `examples/spark-encoder-demo.ssc` for the canonical demo. | The encoder gap was the single biggest constraint on the Spark backend's day-to-day usability; Phase E unblocks the "case-class = schema" story C.3 half-shipped.  Next steps (open follow-ups): `Option[T]` field support, nested case classes, `Seq[T]`/`Array[T]` collection fields, and `@SqlFn` auto-emit revival via Java `UDF1`/`UDF2`/... wrapping. | landed |
| **F — Structured Streaming** | Detect `spark.readStream` / `.writeStream` in user code; emit streaming imports (`Trigger`, `StreamingQuery`, `OutputMode`); auto-append `spark.streams.active.headOption.foreach(_.awaitTermination())` before `spark.stop()` when the user code doesn't already call `awaitTermination`; auto-emit `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"` when `.format("kafka")` is present; emit a `// NOTE Phase F.3` checkpoint-location reminder when a streaming + file format (`parquet`/`csv`/`json`/`orc`/`text`) module forgets to set the option.  Trigger / watermark / window syntax flows through unchanged.  Examples: `spark-streaming-rate-console.ssc`, `spark-streaming-file-parquet.ssc`, `spark-streaming-kafka.ssc`.  See `specs/spark-streaming.md` for the full spec. | The batch surface (Phases A–E) covers the bounded-input half of Spark; streaming closes the unbounded-input half so `.ssc` programs aren't restricted to micro-batch shapes.  Phase E's Scala 3 Encoder shim works untouched for streaming `Dataset[T]` because Structured Streaming reuses the same Catalyst machinery. | landed (F.1–F.4) |
| **G — Catalog / Hive metastore DSL** | New front-matter keys `spark-hive-metastore:` (Thrift URI) and `spark-warehouse:` (path) emit `.enableHiveSupport()` + `.config("spark.sql.catalogImplementation", "hive")` + `.config("spark.hadoop.hive.metastore.uris", ...)` + `.config("spark.sql.warehouse.dir", ...)` on `SparkSession.builder()` and auto-add `org.apache.spark:spark-hive_2.13:<v>` as a `//> using dep`.  `@TempView("name")` annotation on a `val` declaration strips the annotation line and emits `<varName>.createOrReplaceTempView("<viewName>")` after the declaration — same shape as `@SqlFn` (Phase D), so subsequent `sql` blocks can `SELECT * FROM <viewName>` without manual catalog wiring.  `Dataset.fromTable[T](name)` shim wraps `spark.table(name).as[T]` for typed reads from temp views or Hive-managed tables.  See `specs/spark-catalog.md` for the full spec. | The Spark Catalog is already reachable via `spark.catalog.*` calls in scalascript blocks; G surfaces the *registration* (temp views) and *configuration* (Hive metastore + warehouse) side as front-matter / annotation sugar so the common patterns drop boilerplate.  Composes naturally with C.1-C.3 (sql blocks), E (encoders), and L.2 (Delta tables in the Hive metastore). | G.1–G.4 landed (2026-05-20); G.5 deferred |
| **M — MLlib (machine learning)** | Detect `import org.apache.spark.ml.` / `o.a.s.ml.` in user code; auto-emit `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"` so MLlib classes (`Pipeline`, `Tokenizer`, `HashingTF`, `LogisticRegression`, ...) resolve via Coursier.  Extend the Phase E `SscSparkEncoders` shim with an explicit `aenc_Vector: AgnosticEncoder[org.apache.spark.ml.linalg.Vector]` given that wraps `UDTEncoder(VectorUDT)` — `Vector` is a sealed trait (not a `Product`), so the existing `aenc_Product[T <: Product]` Mirror walk can't handle it; an explicit given via Spark's own `VectorUDT` user-defined type is the correct bridge.  Result: `case class Sample(label: Double, features: Vector)` lifts cleanly into `Dataset[Sample]` and feeds into MLlib operators without manual encoder workarounds.  Examples: `spark-mllib-pipeline.ssc`, `spark-mllib-model-save-load.ssc`.  See `specs/spark-mllib.md` for the full spec. | The Spark backend covered batch (A–E), streaming (F), and lakehouse formats (L) but not the machine-learning half of Spark's surface.  Phase M closes that gap with the same regex-detect-then-emit shape — purely additive over Phase E + F + Lakehouse L.2, no breakage to existing examples or tests. | open (M.1 landed) |

#### Spark vs JDBC `sql` blocks

The `sql` fenced tag (§ 3.3.1) compiles to one of two runtime shapes
depending on which backend consumes it:

| Backend | Rewrite | Dispatch | Result type |
|---------|---------|----------|-------------|
| JVM (v1.26 follow-up) | `${expr}` → `?` (positional JDBC) | `JdbcRuntime.execute(conn, sqlWithQ, binds)` | `Seq[Row]` (SELECT-family) or `Int` (DML/DDL) |
| Spark (Phase C.1, landed) | `${expr}` → `:bind<N>` (named Spark SQL parameter) | `spark.sql(sqlText, java.util.Map.of("bind0", e0, ...))` | `org.apache.spark.sql.DataFrame` (= `Dataset[Row]`) |

The shared `SqlBindRewriter` in `core/transform/` walks the block
source once, producing `(rewrittenSqlText, binds: List[String])`.  The
two backends differ only in the placeholder format passed to the
rewriter and in the dispatcher invoked on the rewritten output — the
contract that **every `${expr}` is a bind, never a string splice** is
identical across both targets.

Phase C.1 emits each `sql` block as a sequential
`val _sqlBlock_<n>: DataFrame = spark.sql(...)` in the generated
`@main def runSparkJob` scope.  Phase C.2 layers on top: whenever
the enclosing section has a usable identifier, the same value is
ALSO re-bound under an `object <sectionId>` alias mirroring the
`html` / `css` convention from `JvmGen`:

```scalascript
# Active Users

```sql
SELECT id, name FROM users WHERE active = ${true}
```

# Page

```scalascript
ActiveUsers.sql.show()             // friendly Phase C.2 alias
_sqlBlock_0.show()                 // internal C.1 name — still works
```

The section identifier follows the standard ScalaScript rule
(camelCased alphanumeric runs, first word's casing preserved).
Multiple `sql` blocks in the same section get only the first one
aliased — a second `lazy val sql` inside the same `object` would be
a Scala compile error.  Punctuation-only headings produce no alias;
the internal name remains the only handle.

Lexer-level behaviour pinned by the rewriter:

```sql
SELECT * FROM users WHERE name = '${name}'
```

rewrites to (Spark SQL flavour) `SELECT * FROM users WHERE name = ':bind0'`
— the quotes are preserved verbatim (the rewriter is not SQL-aware),
so Spark treats `:bind0` as a literal string inside the quoted SQL
literal.  Users wanting parameter binding should drop the quotes:
`WHERE name = ${name}` → `WHERE name = :bind0` (a real bind).

The bind map handed to `spark.sql(text, args)` uses two different JDK
factories depending on bind count, because `java.util.Map.of` only has
overloads for 0..10 key/value pairs:

| Binds | Emitted factory | Why |
|-------|-----------------|-----|
| 0 | none — single-arg `spark.sql(text)` | no map needed |
| 1..10 | `java.util.Map.of("bind0", e0, …, "bind<n−1>", e<n−1>)` | matches an existing overload |
| 11..∞ | `java.util.Map.ofEntries[String, Object](java.util.Map.entry("bind0", e0), …)` | varargs, no upper bound |

The threshold is pinned by `SparkGen.MapOfMaxPairs = 10` (Phase C.3,
landed).  Crossing it is a code-generation detail — call sites and
`:bind<N>` placeholders are unchanged.

#### Spark configuration resolution

Both `spark-version` and `spark-master` follow the same three-level
priority order:

1. CLI flag (`--spark-version <v>` / `--spark-master <url>`) — highest
   priority, for ad-hoc overrides.
2. Front-matter key (`spark-version:` / `spark-master:`) — declared
   per-module, checked into version control.
3. SparkGen default (`SparkGen.DefaultVersion` = `4.0.0`,
   `SparkGen.DefaultMaster` = `local[*]`).

The CLI reads the front-matter from `ast.Module.manifest.raw` *before*
`Normalize` strips it, then threads the resolved strings into
`BackendOptions.extra("sparkVersion")` / `("sparkMaster")`.
`SparkBackend.compile` reads the same keys and falls back to
`SparkGen` defaults when no caller supplied a value.

```bash
# All three resolve to local[*] @ 4.0.0:
ssc-spark file.ssc
ssc --backend spark file.ssc
ssc run --backend spark file.ssc

# CLI overrides:
ssc-spark --spark-version 3.5.1 --spark-master local[4] file.ssc

# Front-matter:
# ---
# backend: spark
# spark-version: 3.5.1
# spark-master: spark://prod.example.com:7077
# ---
ssc-spark file.ssc                                # uses front-matter
ssc-spark --spark-master local[*] file.ssc        # CLI wins
```

#### Ad-hoc Spark configuration (`spark-config:`, Phase C.3)

`spark-config:` carries a YAML map of arbitrary `key: value` entries
that compile to `.config(key, value)` lines on `SparkSession.builder()`,
sorted alphabetically by key, between the fixed defaults
(`spark.ui.enabled`, `spark.sql.shuffle.partitions`) and the closing
`.getOrCreate()`:

```yaml
---
backend: spark
spark-config:
  spark.executor.memory: 4g
  spark.executor.cores: 2
  spark.sql.shuffle.partitions: 200
  spark.dynamicAllocation.enabled: true
---
```

The map travels through `BackendOptions.extra` as a single newline-
separated `key=value` string under the `sparkConfig` entry
(`SparkBackend.{encode,decode}SparkConfig` is the codec) and is
visible to `--describe-backend spark` under `capabilities.options`.
Values are coerced via `toString` so numeric and boolean YAML
scalars survive intact.  User keys that collide with the fixed
defaults override them — Spark's builder is last-write-wins.

The configs are baked into the generated Scala source itself rather
than passed as CLI flags, so the same configuration applies whether
the user runs `ssc run --backend spark`, `ssc emit-spark`, or
packages a fat JAR via `ssc submit` (Phase B.2).  This is the
preferred path for cluster tuning that needs to round-trip with the
source — `--` pass-through to `spark-submit` is still the right
escape hatch for one-off driver-side tuning.

#### Cluster submission (`ssc submit`, Phase B.2)

`ssc run --backend spark` ships the driver via `scala-cli run` — fine
for Spark Standalone with a thin classpath, but YARN, Kubernetes, and
production clusters expect a pre-built fat JAR submitted through
`spark-submit`.  `ssc submit` closes that gap:

```bash
# Local development against a cluster master (still uses scala-cli):
ssc run --backend spark --spark-master spark://prod:7077 job.ssc

# Production deployment (fat JAR + spark-submit):
ssc submit job.ssc --spark-master yarn -- \
    --executor-memory 4g --num-executors 8 --deploy-mode cluster

# Dry-run — print the argv that would be invoked, but don't shell out:
ssc submit job.ssc --spark-master k8s://cluster.local:6443 --dry-run
```

Pipeline:

1. Parse the `.ssc`, resolve `sparkVersion` / `sparkMaster` per the
   standard three-level priority above.
2. Generate the same Scala 3 + Spark source `ssc run --backend spark`
   would produce, write it to `/tmp/ssc-spark-<hash>.scala`.
3. `scala-cli --power package <src> --assembly -o /tmp/ssc-spark-<hash>.jar
   --dep org.apache.spark::spark-core:<v> --dep org.apache.spark::spark-sql:<v> --scala 3`.
4. `spark-submit --master <url> --class runSparkJob <extras> <jar>`.

Anything after a literal `--` on the command line flows through to
`spark-submit` verbatim (e.g. `--executor-memory 4g`,
`--num-executors 8`, `--deploy-mode cluster`).  ScalaScript does not
re-model individual Spark tuning flags — they vary per cluster type
and the existing `spark-submit` documentation already covers them.

The fat JAR includes every transitive Spark and ScalaScript dependency,
so YARN / K8s executors only need the same Spark version as the
driver on their image — no ScalaScript or `scala-cli` install
required on the cluster side.

#### UDF bridge (`@SqlFn`, Phase D)

`scalascript` and `sql` blocks share the same `@main` scope (so they
can already share data via `_sqlBlock_<N>` aliases from Phase C.1/C.2),
but `sql` block bodies are opaque strings — a plain `def` inside a
`scalascript` block can be called from other Scala code but not from
SQL.  The `@SqlFn` annotation closes that gap:

```scalascript
@SqlFn
def normalize(s: String): String =
  if s == null then "" else s.trim.toLowerCase

@SqlFn
val nameLength = (s: String) => if s == null then 0 else s.length
```

```sql
SELECT normalize(name) AS name, nameLength(name) AS len
FROM users
WHERE normalize(status) = 'active'
```

`SparkGen.extractSqlFns` strips the `@SqlFn` line and emits an
automatic `spark.udf.register("name", name)` call immediately after
each annotated declaration.  Because registration lives inside
`@main def runSparkJob`, it executes before any subsequent `sql`
block dispatches against the session — so name resolution in the
SQL parser finds the UDF in the catalog.

The annotation works on both `def` and `val` (function-literal)
forms.  Scala 3's automatic eta-expansion of method references in
function context means a plain `def` reference suffices — no
`def.tupled`, no manual lambda wrapping, no Spark `Encoder` (UDF
argument and return types are derived by Spark from the function's
own type at registration time).

Arity 0 functions are not in scope: `spark.udf.register("foo", foo)`
where `foo` is `def foo(): String` would be interpreted as `foo()`
call rather than function reference.  Users needing a no-arg UDF
write the val form explicitly: `@SqlFn val foo = () => "..."`.

False-positive risk: an `@SqlFn` string literal that happens to
precede a `def` would match the regex.  Acceptable — the marker is
a deliberate ScalaScript convention; collisions with prose are
vanishingly unlikely, and a triple-quoted literal sidesteps any
match.

#### Scala 3 / Spark 2.13 interop (Phase E landed)

Apache Spark is still a Scala 2.13 codebase (its own Scala 3
cross-build is on the rumoured Spark Connect path but not in the
mainline `_2.13` JARs).  Scala 3 reads those `_2.13` JARs via
the TASTy bridge, so `--scala 3.7.1` plus
`--dep org.apache.spark:spark-core_2.13:<v>` is the working
combination — note the explicit `_2.13` suffix instead of
scala-cli's `::` shortcut (which would expand to `_3:` and fail
Coursier resolution).  Both directives are baked into the emitted
source as `//> using` so `scala-cli run <file>` works without
command-line arguments.

**Scala version pin.**  Scala 3.8.x has a regression in its
TASTy-bridge to Scala 2.13 that breaks `ExpressionEncoder` at
runtime — `scala.reflect.internal.FatalError: class Array does
not have a member apply` from inside Spark's codegen.  Scala
3.7.1 is the latest series that works end-to-end and is the
`SparkGen.DefaultScalaVersion`.

The TASTy bridge handles bytecode interop fine, but it does NOT
synthesise `scala.reflect.runtime.universe.TypeTag` — that's a
Scala 2 macro from `scala-reflect` with no Scala 3 equivalent.
Several Spark APIs depend on `TypeTag` at *compile* time:

| API                                          | Needs `TypeTag`? | Works under Scala 3 + Spark 2.13? |
|----------------------------------------------|------------------|-----------------------------------|
| `spark.implicits.newProductEncoder[T]`       | yes (`T : TypeTag`) | ✗ — dropped from emit; Phase E `SscSparkEncoders.derived[T]` covers it |
| `Encoders.product[T]`                        | yes              | ✗ — Phase E uses `AgnosticEncoders.ProductEncoder` directly |
| `spark.udf.register[RT, A1, …](name, func)`  | yes (each `: TypeTag`) | ✗ — use the Java `UDF1`/... form (see `examples/spark-udf-demo.ssc`) |
| `Encoders.product[T]` (any product)          | yes              | ✗ — same; routed through Phase E |
| `Encoders.STRING`, `Encoders.INT`, …         | no (pre-baked)   | ✓ — exposed as `given` in `SscSparkEncoders` |
| `spark.createDataset(List[String])`          | no               | ✓ |
| **`spark.createDataset(List[CaseClass])`**   | yes (via encoder) | **✓ — Phase E `SscSparkEncoders.derived[T]` synthesises an encoder from `Mirror.ProductOf[T]`** |
| `spark.udf.register(name, UDF1, returnType)` | no (Java interface) | ✓ |
| `spark.sql("...")`, `spark.read.X(path)`     | no               | ✓ |
| `df.toDF("col")`, `df.createOrReplaceTempView` | no             | ✓ |
| `df.write.X(path)`                           | no               | ✓ |

**Phase E — Scala 3 native `Encoder` derivation (landed).**
Emitted at top of every Spark source as `object SscSparkEncoders`:

```scala
object SscSparkEncoders:
  import scala.deriving.Mirror
  import scala.compiletime.{erasedValue, constValueTuple}
  import scala.reflect.ClassTag
  import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
  import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*
  import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
  import org.apache.spark.sql.types.Metadata

  // Primitive givens — bypass `spark.implicits.newProductEncoder`.
  given Encoder[String]  = Encoders.STRING
  given Encoder[Int]     = Encoders.scalaInt
  // ... (Long, Double, Boolean, Float, Short, Byte)

  // Case-class encoder via Scala 3 `Mirror` — no TypeTag.
  inline given derived[T <: Product](using m: Mirror.ProductOf[T], ct: ClassTag[T]): Encoder[T] =
    val labels = constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String])
    val encs   = summonEncoders [m.MirroredElemTypes]
    val nulls  = summonNullables[m.MirroredElemTypes]
    val fields = labels.zip(encs).zip(nulls).map { case ((n, e), nl) =>
      EncoderField(n, e, nl, Metadata.empty)
    }
    ExpressionEncoder(ProductEncoder[T](ct, fields, None))
```

User code:

```scalascript
case class User(id: Int, name: String, active: Boolean)
val users = List(User(1, "Alice", true), User(2, "Bob", false))
val ds = spark.createDataset(users)        // <- Phase E derived encoder
ds.printSchema()                            // schema mirrors case class
ds.filter(_.active).collect().foreach(...)
```

The `@main def runSparkJob` scope imports `SscSparkEncoders.given`
instead of `spark.implicits._` — the latter brings in a
TypeTag-bound `newProductEncoder` that poisons implicit search.

**Phase E follow-ups landed (2026-05-20):**
- ✓ `Option[T]` field support via `AgnosticEncoders.OptionEncoder` —
  Spark sees the column as nullable of the inner type.
- ✓ Nested case classes — recursive AgnosticEncoder lookup through
  `summonInline[AgnosticEncoder[t]]` for each field type; nested
  products land as Spark `struct` columns, primitives as flat fields,
  `Option[U]` as nullable of `U`.

Both work via the same recursive structure: top-level
`derived[T <: Product]` summons an `AgnosticEncoder[T]` which is
produced either by `aenc_Product[T]` (recursive Mirror walk), by
`aenc_Option[U]` (wraps the inner), or by a primitive `aenc_*` given.
Resolution is unambiguous because `Option` is a sealed sum (only
`Mirror.SumOf`, not `Mirror.ProductOf`), so the Product path can't
accidentally absorb `Option` types.

User-facing recipe:

```scalascript
case class Address(city: String, zip: Int)
case class Person(id: Int, name: String, age: Option[Int], addr: Address)

val people = List(
  Person(1, "Alice", Some(30), Address("Kyiv", 1000)),
  Person(2, "Bob",   None,     Address("Lviv", 7900))
)
val ds = spark.createDataset(people)
ds.printSchema()    // root
                    //  |-- id: integer (nullable = false)
                    //  |-- name: string (nullable = true)
                    //  |-- age: integer (nullable = true)
                    //  |-- addr: struct (nullable = true)
                    //  |    |-- city: string (nullable = true)
                    //  |    |-- zip: integer (nullable = false)
```

See `examples/spark-nested-demo.ssc` for the canonical demo.

**Phase E follow-ups landed (cont., 2026-05-20):**
- ✓ Collection fields — `Seq[E]`, `List[E]`, `Vector[E]`, `Set[E]`
  go through `AgnosticEncoders.IterableEncoder[C, E]` (same
  `array<E>` wire shape, different runtime container class);
  `Array[E]` through `AgnosticEncoders.ArrayEncoder[E]`;
  `Map[K, V]` through `AgnosticEncoders.MapEncoder[Map[K, V], K, V]`.
  `containsNull` / `valueContainsNull` are read directly off the
  inner encoder's `nullable` flag, so `Seq[Option[String]]` lands
  with `containsNull = true` automatically.  Example column shapes:

  ```text
  tags:   array<string>           (nullable = true)
  scores: array<integer>          (nullable = true)
  meta:   map<string, string>     (nullable = true)
  ```

  See `examples/spark-collections-demo.ssc`.

**Phase E follow-ups landed (cont., 2026-05-20, batch 3):**
- ✓ `@SqlFn` auto-emit revival.  `extractSqlFns` now parses param
  types and return type from the `def` signature; the emit wraps the
  user's function in Spark's Java `UDFN` functional-interface form
  with an explicit `DataType` looked up via `SparkGen.SqlFnDataType`.
  TypeTag-free, so it compiles cleanly under Scala 3 + Spark `_2.13`.
  Authoring:

  ```scalascript
  @SqlFn
  def normalize(s: String): String =
    if s == null then "" else s.trim.toLowerCase

  @SqlFn
  def nameLength(s: String): Int =
    if s == null then 0 else s.length
  ```

  Codegen emits the equivalent of:

  ```scala
  spark.udf.register("normalize",
    new org.apache.spark.sql.api.java.UDF1[String, String] {
      def call(a1: String): String = normalize(a1)
    },
    org.apache.spark.sql.types.StringType
  )
  // ... and likewise for nameLength → UDF1[String, Int] + IntegerType.
  ```

  Limitations: only `def` form supported (no `val`-with-function-
  literal; the return type isn't extractable from a function literal
  without parsing the body).  Generic return types
  (`Option[String]`, `Map[K, V]`) fall outside `SqlFnDataType` —
  codegen degrades to `StringType` + a `// TODO` comment rather
  than refusing the compile.  See `examples/spark-udf-demo.ssc`.

- ✓ Tuple types as case-class fields.  Scala 3 synthesises
  `Mirror.ProductOf[(A, B)]` automatically (tuples are products),
  so the existing `aenc_Product[T <: Product]` given handles them
  with no extra code.  Spark emits the tuple as a `struct` column
  with the standard tuple member names `_1`, `_2`, …  See
  `examples/spark-tuple-demo.ssc`.

**Phase E status:** all formerly-open follow-ups landed.  Spark
milestone is closed end-to-end for case classes with primitive,
`Option`, nested, collection, tuple, and UDF features.  Future
extensions (custom encoders for non-Mirror types, Spark Connect
support when the Scala 3 client lands, etc.) are out of scope for
v1.25 § 9.5.

## Appendix A: Reserved Words

```text
abstract case catch class def do else enum
extends false final finally for given if
implicit import inline lazy match new null object
override package private protected return
sealed super then this throw trait true try
type using val var while with yield
```

## Appendix B: Operator Precedence

From lowest to highest:
1. Assignment operators
2. `||`
3. `&&`
4. `|`
5. `^`
6. `&`
7. `==`, `!=`
8. `<`, `>`, `<=`, `>=`, and user-defined operators beginning with `<`
9. `++`, `:+`
10. `+`, `-`
11. `*`, `/`, `%`
12. Other symbolic infix operators (for example parser combinators `~` and
    `~>`)
13. Unary `+`, `-`, `!`, `~`
14. Postfix operators (including `.!`)

User-defined symbolic infix precedence is selected by the operator's first
character. In particular, `<~` uses the comparison tier while `~` and `~>` use
the highest symbolic-infix tier below unary operators.

## Appendix C: Grammar Summary

See [v1/lang/grammar/scalascript.ebnf](v1/lang/grammar/scalascript.ebnf) for the
complete EBNF grammar.

## Appendix D: CLI Reference

```text
ssc run file.ssc                Interpret a .ssc file
ssc watch file.ssc              Watch mode — re-run on change
ssc repl                        Interactive REPL
ssc test file.ssc               Run embedded tests
ssc preview file.ssc            Preview component variants
ssc emit-js file.ssc            Transpile to JavaScript
ssc emit-spa file.ssc           Emit SPA HTML bundle
ssc emit-wasm file.ssc          Emit WebAssembly module via Scala.js
ssc emit-swift [--server-url URL] file.ssc
                                 Emit checked-v2 CoreIR as a Swift Package
ssc run-swift [--server-url URL] file.ssc
                                 Build and run the generated AppCore product on macOS
ssc emit-wc file.ssc            Emit Web Components bundle
ssc emit-spark file.ssc         Emit Scala 3 + Spark source
ssc submit file.ssc             Build fat JAR + invoke spark-submit (§ 9.5 Phase B.2)
                                 Flags: --spark-master <url>, --spark-version <v>,
                                        --dry-run, -- <extra spark-submit args>
ssc compile-jvm file.ssc        Compile to .scjvm artifact
ssc compile-js file.ssc         Compile to .scjs artifact
ssc emit-interface file.ssc     Emit .scim interface
ssc emit-ir file.ssc            Emit .scir normalized IR
ssc build-jvm file.ssc -o app.jar
                                 Native frontend + CoreIR → direct ASM executable JAR (§ 9.2.1)
ssc build --target macos [--server-url URL] file.ssc
                                 Native frontend + CoreIR → Swift/SwiftUI macOS package
ssc build --target ios [--server-url URL] file.ssc
                                 Native frontend + CoreIR → Swift/SwiftUI iOS package
ssc run --target macos|ios [--server-url URL] file.ssc
                                 Build and launch the generated Apple application
ssc package --target macos|ios [--server-url URL] file.ssc
                                 Package the generated Apple application product
ssc publish --target macos|ios [--server-url URL] file.ssc
                                 Publish the generated Apple application product
ssc link [--backend B] dir/     Link artifacts
ssc build [--incremental] dir/  Incremental project build
ssc deps file.ssc               Print import closure
ssc info artifact               Inspect artifact metadata
ssc render file.ssc [path]      Static-render a GET route
ssc plugin install/list/uninstall/check/pack/registry
ssc --list-backends
ssc --describe-backend <id>
ssc --backend <id> run file.ssc
ssc --spark-version <v> ...     Override Spark version for the `spark` backend (§ 9.5)
ssc --spark-master <url> ...    Override Spark master URL (`local[*]`, `local[N]`, `spark://...`, `yarn`, `k8s://...`) (§ 9.5)
jssc file.ssc                   JS runner
sscc file.ssc                   JVM runner
ssc-spark file.ssc              Apache Spark runner (delegates to `ssc run --backend spark`)
```
