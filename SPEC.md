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

This specification uses EBNF notation. See [grammar/scalascript.ebnf](grammar/scalascript.ebnf) for the complete formal grammar.

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
| `backend` | String | Preferred backend id for `ssc run` when no `--backend` flag is supplied (`int` / `jvm` / `js` / `node` / `scalajs-spa` / `wasm` / `spark`). § 9.2. |
| `spark-version` | String | Apache Spark version pinned for the Spark backend.  Resolution order: CLI `--spark-version` flag → this key → `SparkGen.DefaultVersion`. § 9.5. |
| `spark-master` | String | Spark master URL passed to `SparkSession.builder().master(...)` (`local[*]` / `local[N]` / `spark://...` / `yarn` / `k8s://...`).  Resolution order: CLI `--spark-master` flag → this key → `SparkGen.DefaultMaster` (= `local[*]`). § 9.5. |

`routes:` entries are equivalent to writing `route(method, path) { req => handler(req) }` inline.

See [schemas/frontmatter.yaml](schemas/frontmatter.yaml) for the complete schema.

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
```

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

A `.ssc` document may freely mix `scala` and `scalascript` blocks.
Definitions are visible across blocks within the same file.
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

**Drivers.**  The `backend-sql-runtime` module bundles H2 and SQLite
(both embedded, no network) so that `jdbc:h2:mem:test` and
`jdbc:sqlite:./data.db` work with zero configuration.  Any other
driver — PostgreSQL, MySQL, Oracle, MSSQL — is brought in via a
standard `dep:` link from the front-matter:

```markdown
[postgres](dep:org.postgresql/postgresql:42.7.4)
```

**Target support.**  `sql` is JVM-only: the interpreter and JVM
backend execute the block via JDBC; the JS, Node, and WASM backends
emit `UnknownBlockLanguage` (the source is preserved in the IR so
future browser-side or Wasm-side SQL engines can pick it up without a
spec change).

### 3.4 Inline Interpolation

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
case head :: tail => head
```

### 5.5 Functional Updates and Lenses

Case classes carry an auto-generated `copy` method:

```scalascript
case class Person(name: String, age: Int)
val alice = Person("Alice", 30)
val older = alice.copy(age = 31)
```

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

### 5.6 String Interpolators

```scalascript
s"Hello, $name"                   // standard interpolation
md"# $title\n$body"               // markdown (strips indent)
html"<p>Hello, $userInput</p>"    // HTML-escaped
css".root { color: $color; }"     // CSS
```

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
```

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

## 7. Semantics

### 7.1 Evaluation Order

- Strict evaluation (not lazy by default)
- Left-to-right argument evaluation
- Short-circuit for `&&` and `||`

### 7.2 Algebraic Effects

ScalaScript supports algebraic effects and handlers — a structured mechanism
for defining, performing, and intercepting side effects.

Implementation: a **trampolined Free Monad** (`Pure | Perform | FlatMap`) with
constant-time `flatMap`. `resume(v)` invokes the captured continuation directly;
multi-shot handlers interpret each `resume` branch independently.

Supported on all three backends.

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
    resume(scala.io.StdIn.readLine())
}
```

#### 7.2.2 Multi-shot Handlers (Nondeterminism)

```scalascript
effect Choose:
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

## 8. Standard Library

### 8.1 Core Types

- `Option[A]` — optional values
- `Either[E, A]` — error handling; `mapRight`, `flatMapRight`, `foldEither`
- `List[A]` — immutable list with full combinators
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
```

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
| `int` | Interpreter (tree-walking) | `ssc run` / `bin/ssc` | Executed in-process | Default when no `--backend` is supplied and front-matter has no `backend:` key. |
| `jvm` | JVM (Scala 3 source) | `bin/sscc`, `ssc compile-jvm` | Scala 3 source → compiled via scala-cli | In-process emitter; scala-cli runs the produced `.scala` file. |
| `js` | JavaScript (Node / SPA) | `bin/jssc`, `ssc emit-js` | JavaScript source (one-shot or segmented) | Same `JsGen` powers Node and browser builds; the latter pairs with `ssc emit-spa`. |
| `node` | Node.js | `ssc run --backend node`, `bin/ssc-node` | Self-contained `.cjs` bundle for `node` | Extends JsGen with verbatim linking of `node.js` opaque-exec blocks (§ 3.3) and a Node-side `_output` flush epilogue. v1.25 Phase 3. |
| `scalajs-spa` | Scala.js SPA bundle | `ssc emit-spa` | Self-contained HTML + JS bundle | Cross-compiles `scala` blocks via Scala.js for browser execution. |
| `wasm` | WebAssembly (Scala.js) | `ssc emit-wasm` | `.wasm` module + JS glue | Re-uses Scala.js's WASM emission path. |
| `spark` | Apache Spark | `ssc run --backend spark`, `bin/ssc-spark` | Scala 3 + Spark source → `scala-cli run --dep org.apache.spark::spark-{core,sql}:<v>` | Out-of-process — Spark JARs resolved at runtime by Coursier. v1.21 `Dataset[T]` API maps 1-to-1. § 9.5. |

`ssc --list-backends` enumerates in-process bundled backends discovered via `ServiceLoader`.
`spark` is special-cased in `Main.runCommand` until § 9.5 Phase A wraps it in a regular `Backend`.

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
+ Spark source that `scala-cli` then runs with the right
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
| **B.2 — `spark-submit` packaging** | `ssc submit file.ssc --spark-master spark://...` packages a fat JAR and shells out to `spark-submit`. | B.1 ships the driver via `scala-cli` which works for Spark Standalone but not for YARN/K8s production deployments. | open (~1 week) |
| **C.1 — `sql` block → `spark.sql(...)`** | `sql` declared in `SparkCapabilities.blockLanguages`; the shared `SqlBindRewriter` produces `:bind<N>` placeholders consumed by Spark SQL 3.4+'s parameterised `sql(text, args)`.  Each `sql` block binds to a sequential `val _sqlBlock_<n>: org.apache.spark.sql.DataFrame` in the `@main` scope, accessible from subsequent `scalascript` blocks. | Reuses the same `sql` surface as the JDBC target (§ 3.3.1) — same source, different runtime. | landed |
| **C.2 — DataFrame ergonomics + schema bridge** | Expose `DataFrame` as `Dataset[Row]` more thoroughly; map `std/parsing` schemas to Spark `StructType`; section-based naming (`<sectionId>.sql` instead of `_sqlBlock_<n>`); `>10` binds via `Map.ofEntries`. | C.1 ships the runtime contract; C.2 polishes the ergonomics. | open |

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
`@main def runSparkJob` scope.  Subsequent `scalascript` blocks in the
same module reference the DataFrame by name (`_sqlBlock_0.show()`,
`val rows = _sqlBlock_0.collect().toList`, etc.).  Section-based
binding (e.g. `<sectionId>.sql`) is Phase C.2 polish.

Lexer-level behaviour pinned by the rewriter:

```sql
SELECT * FROM users WHERE name = '${name}'
```

rewrites to (Spark SQL flavour) `SELECT * FROM users WHERE name = ':bind0'`
— the quotes are preserved verbatim (the rewriter is not SQL-aware),
so Spark treats `:bind0` as a literal string inside the quoted SQL
literal.  Users wanting parameter binding should drop the quotes:
`WHERE name = ${name}` → `WHERE name = :bind0` (a real bind).

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
8. `<`, `>`, `<=`, `>=`
9. `+`, `-`
10. `*`, `/`, `%`
11. Unary `+`, `-`, `!`, `~`
12. Postfix operators (including `.!`)

## Appendix C: Grammar Summary

See [grammar/scalascript.ebnf](grammar/scalascript.ebnf) for the complete EBNF grammar.

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
ssc emit-wc file.ssc            Emit Web Components bundle
ssc emit-spark file.ssc         Emit Scala 3 + Spark source
ssc compile-jvm file.ssc        Compile to .scjvm artifact
ssc compile-js file.ssc         Compile to .scjs artifact
ssc emit-interface file.ssc     Emit .scim interface
ssc emit-ir file.ssc            Emit .scir normalized IR
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
