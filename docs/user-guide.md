# ScalaScript User Guide

A practical reference for ScalaScript v1.27+.

---

## 1. Installation

### Prerequisites

- **Java 21+** (for JVM backend and interpreter)
- **[scala-cli](https://scala-cli.virtuslab.org)** (required)
- **[Node.js](https://nodejs.org) 18+** (for JS backend only)

### Install

```bash
git clone https://github.com/sergey-scherbina/scalascript
cd scalascript

# Optional: install scala-cli
./setup.sh

# Build the ssc launcher and helper scripts into bin/
./install.sh
```

After installation:

```bash
ssc examples/hello.ssc     # interpreter
jssc examples/hello.ssc    # JS backend
sscc examples/hello.ssc    # JVM backend
```

---

## 2. First Script

Create `hello.ssc`:

````ssc
---
name: hello
version: 1.0.0
---

# Hello

```scalascript
def greet(name: String): String = s"Hello, $name!"
println(greet("World"))
```
````

Run it four ways:

```bash
ssc hello.ssc           # interpreter — instant, no compilation
ssc run-jvm hello.ssc   # compile via JvmGen + run with scala-cli
ssc run-js  hello.ssc   # compile via JsGen  + run with Node.js
jssc hello.ssc          # alias for run-js via bin/ wrapper
sscc hello.ssc          # alias for run-jvm via bin/ wrapper
```

All five produce `Hello, World!` with byte-identical output.

### Watch Mode

```bash
ssc watch hello.ssc     # re-runs on every file save
```

`ssc watch` supports **hot reload** for both plain scripts and HTTP servers:

- **Scripts**: on each file save the script is re-run from scratch; any
  error is printed and the watcher continues.
- **HTTP servers** (files that call `serve(port)`): the server starts on
  the first run and keeps its port bound.  On subsequent saves, only the
  route table is cleared and rebuilt — no port rebind, no downtime.
  Use `ssc serve file.ssc` as a more intent-revealing alias:

```bash
ssc serve server.ssc    # starts server, hot-reloads routes on every save
```

Each reload prints a timestamp (`[HH:mm:ss] Reloading ...`) and clears
the terminal so you always see fresh output at the top.

### REPL

```bash
ssc repl
> def f(x: Int) = x * 2
> f(21)
42
```

### `run-jvm` and `run-js` — compile and run in one step

`ssc run` interprets the script with the tree-walking interpreter — no
compilation, instant startup.  When you need true JVM or Node.js
execution semantics (or want to benchmark performance) use `run-jvm` and
`run-js`:

```bash
ssc run-jvm hello.ssc   # JvmGen → temp .sc → scala-cli run
ssc run-js  hello.ssc   # JsGen  → temp .js → node
```

Both commands:
- compile the `.ssc` file through the respective backend codegen
- write the output to a temporary file (deleted after the run)
- execute it and forward stdout/stderr transparently
- leave **no artifacts on disk** — use `ssc compile-jvm` / `ssc compile-js`
  if you want reusable `.scjvm` / `.scjs` artifacts

When to use each:

| Command | Runtime | When to use |
|---------|---------|-------------|
| `ssc run` | Interpreter | Day-to-day scripting, fast iteration, REPL-style |
| `ssc run-jvm` | JVM via scala-cli | Production logic, JDBC, JVM libraries, benchmarking |
| `ssc run-js` | Node.js | Browser-API testing, npm interop, JS-target verification |

**Requirements:** `ssc run-jvm` requires `scala-cli` on PATH; `ssc run-js`
requires `node` on PATH.

```bash
# Example: same file, three runtimes
ssc run     examples/recursion.ssc    # interpreter
ssc run-jvm examples/recursion.ssc    # JVM bytecode
ssc run-js  examples/recursion.ssc    # Node.js

# HTTP server on JVM (real threads, JDBC available)
ssc run-jvm myapp.ssc

# Verify JS output matches interpreter output
ssc run     examples/hello.ssc > out-int.txt
ssc run-js  examples/hello.ssc > out-js.txt
diff out-int.txt out-js.txt   # should be empty
```

---

## 3. Language Basics

### Values and Variables

```scalascript
val x = 42          // immutable
var n = 0           // mutable
val pi = 3.14159
val name = "Alice"
```

### Functions

```scalascript
def add(a: Int, b: Int): Int = a + b

// Default parameters
def greet(name: String, prefix: String = "Hello"): String =
  s"$prefix, $name!"

// Lambdas
val double = (x: Int) => x * 2
val sum = (xs: List[Int]) => xs.foldLeft(0)(_ + _)

// Higher-order
def apply(f: Int => Int, x: Int): Int = f(x)
apply(double, 21)    // 42
```

### Case Classes and Sealed Traits

```scalascript
case class Point(x: Double, y: Double)
case class Person(name: String, age: Int)

val p = Point(3.0, 4.0)
val dist = math.sqrt(p.x * p.x + p.y * p.y)

// Copy with updates
val older = Person("Alice", 30).copy(age = 31)

// Sealed trait hierarchy
sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape

def area(s: Shape): Double = s match
  case Circle(r) => math.Pi * r * r
  case Rect(w, h) => w * h
```

### Enums

```scalascript
enum Color:
  case Red, Green, Blue

enum Result[+A]:
  case Ok(value: A)
  case Err(msg: String)

val r: Result[Int] = Result.Ok(42)
r match
  case Result.Ok(n)  => println(s"Got $n")
  case Result.Err(m) => println(s"Error: $m")
```

### Pattern Matching

```scalascript
val x = 5
x match
  case 0         => "zero"
  case n if n > 0 => s"positive: $n"
  case _         => "negative"

// Destructuring
case class Point(x: Int, y: Int)
val pt = Point(3, 4)
pt match
  case Point(0, _) => "on y-axis"
  case Point(x, y) => s"at $x, $y"

// List patterns
val xs = List(1, 2, 3)
xs match
  case Nil         => "empty"
  case head :: Nil => s"singleton: $head"
  case head :: tail => s"head=$head, rest=${tail.length}"
```

### Collections

```scalascript
val nums = List(1, 2, 3, 4, 5)
nums.map(_ * 2)             // List(2, 4, 6, 8, 10)
nums.filter(_ % 2 == 0)     // List(2, 4)
nums.foldLeft(0)(_ + _)     // 15
nums.take(3)                // List(1, 2, 3)
nums.drop(3)                // List(4, 5)

val words = List("foo", "bar", "baz")
words.sorted                // List(bar, baz, foo)
words.mkString(", ")        // "foo, bar, baz"

// Map
val scores = Map("Alice" -> 95, "Bob" -> 87)
scores("Alice")             // 95
scores.getOrElse("Charlie", 0)  // 0
scores + ("Charlie" -> 72)

// Option
val maybeUser: Option[String] = Some("alice")
maybeUser.map(_.toUpperCase)        // Some(ALICE)
maybeUser.getOrElse("anonymous")    // "alice"
```

### For Comprehensions

```scalascript
val result = for
  x <- List(1, 2, 3)
  y <- List(10, 20)
  if x + y < 30
yield x * y

// result = List(10, 20, 20, 40)

// With Option
val parsed = for
  x <- parseInt("42")
  y <- parseInt("10")
yield x + y
// parsed = Some(52)
```

### Typeclasses

```scalascript
trait Show[A]:
  def show(a: A): String

given Show[Int] with
  def show(n: Int): String = n.toString

given [A: Show] => Show[List[A]] with
  def show(xs: List[A]): String =
    xs.map(summon[Show[A]].show).mkString("[", ", "]", "]")

def display[A: Show](a: A): String = summon[Show[A]].show(a)

println(display(42))              // "42"
println(display(List(1, 2, 3)))   // "[1, 2, 3]"
```

### Extension Methods

```scalascript
extension (n: Int)
  def squared: Int = n * n
  def times(f: => Unit): Unit = (0 until n).foreach(_ => f)

4.squared      // 16
3.times { println("hello") }
```

### List and Map Literals

In `.ssc` code blocks `[…]` is compact syntax sugar — no import needed:

```scalascript
val nums   = [1, 2, 3]             // List(1, 2, 3)
val empty  = []                     // List()
val words  = ["hello", "world"]

val scores = ["Alice" -> 95, "Bob" -> 87]   // Map("Alice" -> 95, "Bob" -> 87)
val cfg    = ["host" -> "db", "port" -> 5432]
```

The rule is simple: `[k -> v, ...]` (arrow present) expands to `Map(...)`,
anything else to `List(...)`.  Works everywhere an expression is expected —
method arguments, `val` initializers, nested literals:

```scalascript
val matrix  = [[1, 0], [0, 1]]              // List(List(1,0), List(0,1))
val headers = ["Content-Type" -> "application/json", "X-Token" -> token]

route("POST", "/api/todos") { req =>
  Db.execute("default", "INSERT INTO todos(text) VALUES (?)", [req.body.trim])
  Response.status(201, "created")
}
```

Type-parameter brackets are never affected — `def f[A](x: A)` is unchanged.

### Optics

```scalascript
case class Address(street: String, city: String)
case class Person(name: String, address: Address)

// Lens — focus on a field
val cityLens = Focus[Person](_.address.city)
val alice = Person("Alice", Address("Main St", "Boston"))

cityLens.get(alice)               // "Boston"
cityLens.set(alice, "NYC")        // Person(Alice, Address(Main St, NYC))
cityLens.modify(alice, _.upper)   // Person(Alice, Address(Main St, BOSTON))

// Prism — focus on an enum case
enum Shape:
  case Circle(r: Int)
  case Rect(w: Int, h: Int)

val circlePrism = Prism[Shape, Shape.Circle]
circlePrism.getOption(Shape.Circle(5))   // Some(Circle(5))
circlePrism.getOption(Shape.Rect(3, 4))  // None

// Traversal — focus on all elements
case class Team(members: List[Person])
val nameTrav = Focus[Team](_.members.each.name)
nameTrav.getAll(team)                // List("Alice", "Bob")
nameTrav.modify(team, _.toUpperCase) // all names uppercased
```

---

## 4. Effects and Async

### Algebraic Effects

Effects are named interfaces; handlers intercept operations and may resume continuations.

```scalascript
effect Logger:
  def info(msg: String): Unit

effect Random:
  def nextInt(n: Int): Int

def program(): String =
  Logger.info("Starting...")
  val n = Random.nextInt(10)
  Logger.info(s"Got $n")
  s"Result: $n"

// Run with a concrete handler
handle(program()) {
  case Logger.info(msg, resume) =>
    println(s"[LOG] $msg")
    resume(())
  case Random.nextInt(n, resume) =>
    resume(scala.util.Random.nextInt(n))
}
```

### Standard Effects

Pre-built effects ship in `std/effects/`:

```scalascript
// State effect
def counter(): Int =
  State.modify[Int](_ + 1)
  State.get[Int]

runState(0)(counter())    // (1, ())

// Logger effect
def business(): Unit =
  Logger.info("Doing work")
  Logger.warn("Something odd")

runConsoleLogger(business())          // prints to console
val logs = runTestLogger(business())  // List[LogEntry]

// Random with seed (deterministic)
def roll(): Int = Random.nextInt(6) + 1
runSeededRandom(42)(roll())           // always the same
```

### Direct Syntax (Do-Notation)

Write monadic code without `<-` arrows:

```scalascript
// Explicit direct block
val result = direct[Option] {
  x = parseInt("42")     // Option[Int] — binds or short-circuits
  y = parseInt("10")
  x + y
}
// result = Some(52)

// Postfix .! for inline bind
direct[Option] {
  val total = parseInt("42").! + parseInt("10").!
  Some(total)
}

// Type-directed (implicit direct block when return type is known)
def loadUser(id: String): Option[User] =
  raw  = usersMap.get(id)        // Option[User] — auto-bind
  name = raw.name
  User(name.trim)
```

### Async

```scalascript
// Single-threaded deterministic
runAsync {
  val a = Async.async(() => heavyCompute(1))
  val b = Async.async(() => heavyCompute(2))
  val results = Async.parallel(List(() => 1, () => 2, () => 3))
  Async.delay(100)
  Async.await(a) + Async.await(b)
}

// Real parallel threads (JVM only)
runAsyncParallel {
  Async.parallel(List(
    () => { Async.delay(100); "a" },
    () => { Async.delay(100); "b" }
  ))
  // finishes in ~100ms instead of ~200ms
}
```

### Reactive Signals

```scalascript
val count   = Signal(0)
val doubled = computed { count.get * 2 }
val squared = computed { count.get * count.get }

effect {
  println(s"count=${count.get} doubled=${doubled.get}")
}

count.set(3)   // → "count=3 doubled=6"
count.set(5)   // → "count=5 doubled=10"
```

---

## 5. Web Server

### Basic Routes

```scalascript
route("GET", "/") { req =>
  Response.html("<h1>Hello!</h1>")
}

route("GET", "/users/:id") { req =>
  val id = req.params("id")
  Response.json(Map("id" -> id, "name" -> "Alice"))
}

route("POST", "/users") { req =>
  val user = jsonRead[User](req.body)
  Response.json(user).withStatus(201)
}

route("DELETE", "/users/:id") { req =>
  users.remove(req.params("id"))
  Response.status(204)
}

serve(8080)
```

### Request and Response

```scalascript
// Request fields
req.method                  // "GET", "POST", ...
req.path                    // "/users/42"
req.params("id")            // path captures
req.query.getOrElse("q", "")  // ?q=...
req.headers("Content-Type")
req.body                    // raw body string
req.form("username")        // form fields
req.session                 // Map[String, String] from signed cookie
req.json                    // JsonValue
req.bearerToken             // Option[String]
req.jwtClaims               // Option[Map[String, String]]

// Response builders
Response.html(body)
Response.text(body)
Response.json(value)         // auto-serialized
Response.redirect("/login")
Response.notFound("Oops")
Response.status(422, "Unprocessable")
```

### Middleware

```scalascript
useCors("https://myapp.com")   // or "*" for dev
useGzip()
useCacheHeaders(maxAge = 3600)
useSessionStore(ttlSeconds = 1800)  // server-side sessions
```

### Authentication

```scalascript
// Password hashing
val hash = hashPassword(req.form("password"))
verifyPassword(req.form("password"), storedHash)

// Sessions
route("POST", "/login") { req =>
  if !csrfValid(req) then Response.status(403)
  else if verifyPassword(req.form("pass"), getHash(req.form("user"))) then
    Response.redirect("/dashboard")
      .withSession(Map("user" -> req.form("user")))
  else
    Response.status(401, "Bad credentials")
}

route("POST", "/logout") { req =>
  Response.redirect("/").clearSession()
}

// JWT for APIs
route("GET", "/api/profile") { req =>
  req.jwtClaims match
    case Some(claims) => Response.json(claims)
    case None         => Response.status(401)
}

val token = jwtSign(Map("sub" -> userId, "role" -> "admin"))
```

### Validation

```scalascript
route("POST", "/register") { req =>
  validate {
    val name  = requireString(req.json, "name")
    val email = requireString(req.json, "email")
    val age   = requireRange(req.json, "age", 18, 120)
    User(name, email, age)
  } match
    case Right(user) => Response.json(user)
    case Left(errs)  => Response.status(422, errs.map(_.message).mkString(", "))
}
```

### Streaming and SSE

```scalascript
// Server-Sent Events
route("GET", "/events") { req =>
  sse(req) { sink =>
    var i = 0
    while i < 10 do
      sink.send(s"data: event $i\n\n")
      Async.delay(500)
      i += 1
  }
}

// Streaming response body
route("GET", "/download") { req =>
  streamResponse { sink =>
    for chunk <- readChunks("large-file.bin") do
      sink.write(chunk)
  }
}
```

### TLS

```scalascript
val tlsCfg = tls("server.crt", "server.key")
serve(443, tls = tlsCfg)
```

### Static Build

```bash
ssc render server.ssc /about   # print response body for GET /about
ssc build src/ dist/           # full static site generator
```

### Development Server with Hot Reload

```bash
ssc serve server.ssc   # start server + watch for changes; routes reload live
ssc watch server.ssc   # identical — use whichever reads more naturally
```

On each save the route table is cleared and rebuilt from the new source; the
TCP port stays bound so in-flight requests are not dropped.

---

## 6. SQL Databases

### Declaring connections

Add a `databases:` map to front-matter.  Each entry is a named JDBC connection:

````ssc
---
name: myapp
databases:
  default:
    url: "jdbc:sqlite:./app.db"
  analytics:
    url:      "jdbc:postgresql://db:5432/analytics"
    user:     "${env:DB_USER}"
    password: "${env:DB_PASSWORD}"
    driver:   "org.postgresql.Driver"   # optional — auto-detected for bundled drivers
---
````

Bundled drivers: **SQLite** (`jdbc:sqlite:`) and **H2** (`jdbc:h2:`).  Any other driver can be added as a `dep:` import or placed on the classpath.

Connection strings support `${scheme:ref}` secret references (see §6.2).

### `sql` fenced blocks

A ` ```sql ``` ` fenced block executes against the `default` database (or the one named in `@database` front-matter of the block).  Return value is the row count for DML, void for DDL:

````ssc
```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```
````

Bind parameters use `?`:

````ssc
```sql
INSERT INTO users(name, email) VALUES (?, ?)
```
````

Parameters are passed from the surrounding `scalascript` block via `Db.execute`.

### Programmatic access

`Db.query` and `Db.execute` are available in `scalascript` blocks:

```scalascript
// Returns List[Map[String, Any]] — one map per row, keyed by column name
val rows = Db.query("default", "SELECT id, text FROM todos ORDER BY id", [])

// Returns update count (Int)
Db.execute("default", "INSERT INTO todos(text) VALUES (?)", ["Buy milk"])
Db.execute("default", "DELETE FROM todos WHERE id = ?", [id.toInt])
```

The first argument is the connection name from `databases:`.  Bind parameters are passed as a list — use `[]` for no parameters.

### `transaction` fenced blocks

A ` ```transaction ``` ` fenced block runs multiple `;`-separated SQL statements
atomically in one JDBC transaction.  If any statement throws, all prior statements
are rolled back.

````ssc
```transaction
UPDATE accounts SET balance = balance - ${amount} WHERE id = ${fromId};
UPDATE accounts SET balance = balance + ${amount} WHERE id = ${toId}
```
````

- Statements are separated by `;`.  A trailing `;` is ignored.
- `${expr}` bind parameters work exactly like in ` ```sql ``` ` blocks — safe,
  `PreparedStatement`-based, no string substitution.
- `$$` is an escaped literal `$`.
- The block's return value is the result of the **last** statement: an `Int`
  (affected-row count) for DML, or `List[Map[String, Any]]` for a SELECT.
- Target the block at a named database with `@db=name`:

````ssc
```transaction @db=payments
INSERT INTO events (type, amount) VALUES ('debit', ${amount});
UPDATE balances SET total = total - ${amount} WHERE user_id = ${userId}
```
````

Connections come from `databases:` front-matter; the default name is `"default"`.

### REST API + SQLite example

A complete todo list with SQLite persistence and a JSON REST API:

````ssc
---
name: todo-api
databases:
  default:
    url: "jdbc:sqlite:./todos.db"
---

# Todo API

```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```

```scalascript
route("GET", "/api/todos") { req =>
  val rows = Db.query("default", "SELECT id, text FROM todos ORDER BY id", [])
  val body = "[" + rows.map(r => s"""{"id":${r("id")},"text":"${r("text")}"}""").mkString(",") + "]"
  Response.text(body)
}

route("POST", "/api/todos") { req =>
  val text = req.body.trim
  if text.isEmpty then Response.status(400, "empty body")
  else
    Db.execute("default", "INSERT INTO todos(text) VALUES (?)", [text])
    Response.status(201, "created")
}

route("POST", "/api/todos/delete") { req =>
  Db.execute("default", "DELETE FROM todos WHERE id = ?", [req.body.trim.toInt])
  Response.status(204)
}

serve(8080)
```
````

The database file `todos.db` is created on first run and survives restarts.

---

## 6.2. Secret Management

### Built-in `${scheme:ref}` resolution

Secret references in `databases:` connection fields are expanded at connection-open time — never stored in plaintext:

| Scheme | Reference | Resolved from |
|--------|-----------|--------------|
| `${env:NAME}` | `${env:DB_PASSWORD}` | process environment variable |
| `${file:PATH}` | `${file:/run/secrets/db_pw}` | file contents, whitespace-trimmed (Docker / K8s secret volumes) |
| `${sops:key.path}` | `${sops:db.prod.password}` | YAML piped to stdin via `sops -d` |

Multiple references in one string are all expanded:

```yaml
url: "jdbc:postgresql://${env:DB_HOST}:${env:DB_PORT}/myapp"
```

### Piping secrets via sops

```bash
sops -d secrets.enc.yaml | ssc myapp.ssc
```

`ssc` detects a piped stdin at startup, parses the decrypted YAML, and flattens nested keys to dot-separated paths:

```yaml
# secrets.enc.yaml (decrypted output)
db:
  prod:
    password: "s3cr3t"
TOP_SECRET: "value"
```

Accessible as `${sops:db.prod.password}` and `${sops:TOP_SECRET}` in any `databases:` field.  List elements are keyed by index (`hosts.0`, `hosts.1`, …).

```yaml
databases:
  prod:
    url:      "jdbc:postgresql://db:5432/myapp"
    user:     "${sops:db.prod.user}"
    password: "${sops:db.prod.password}"
```

### Docker / Kubernetes secret files

```yaml
databases:
  prod:
    password: "${file:/run/secrets/db_password}"
```

The file is read and whitespace-stripped at connection time.  The path is typically a tmpfs mount injected by Docker Compose `secrets:` or a K8s volume.

### `SecretResolver` SPI

Custom backends (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager, Doppler, …) are implemented as `SecretResolver` plugins loaded via `java.util.ServiceLoader`:

```scala
// In a plugin JAR:
class VaultResolver extends scalascript.sql.SecretResolver:
  val scheme = "vault"
  def resolve(ref: String): String =
    val (path, field) = ref.span(_ != '#')
    VaultClient.read(path).data(field.drop(1))

// META-INF/services/scalascript.sql.SecretResolver:
// com.example.VaultResolver
```

Usage in front-matter: `${vault:secret/myapp/db#password}`

See [`secret-resolvers.md`](../secret-resolvers.md) for the full spec — Vault, AWS SM, GCP SM, Azure KV, Doppler, 1Password, pass.

---

## 7. WebSocket

### Server

```scalascript
onWebSocket("/chat") { ws =>
  println(s"Connected: ${ws.id}")
  while !ws.isClosed do
    ws.recv() match
      case Some(msg) =>
        println(s"Received: $msg")
        ws.send(s"echo: $msg")
      case None => ()
  println(s"Disconnected: ${ws.id}")
}

serve(8080)
```

WebSocket options:

```scalascript
onWebSocket("/game", maxConnections = 100, rateLimit = 10) { ws => ... }
```

Rate limiting: `rateLimit` = max messages per second per connection.

### Broadcasting to Rooms

```scalascript
val rooms = mutable.Map[String, List[WebSocket]]()

onWebSocket("/room/:name") { ws =>
  val room = ws.params("name")
  rooms.updateWith(room)(_.map(ws :: _).orElse(Some(List(ws))))

  while !ws.isClosed do
    ws.recv() match
      case Some(msg) =>
        rooms.getOrElse(room, Nil)
          .filter(_ != ws)
          .foreach(_.send(msg))
      case None => ()

  rooms.updateWith(room)(_.map(_.filter(_ != ws)))
}
```

### Client

```scalascript
wsConnect("ws://localhost:8080/chat") { ws =>
  ws.send("hello")
  ws.recv() match
    case Some(reply) => println(s"Server said: $reply")
    case None        => println("Disconnected")
}

// TLS
wsConnect("wss://secure.example.com/chat") { ws => ... }
```

### Async WebSocket Receive

```scalascript
runAsync {
  val msg = Async.recvFrom(ws)
  process(msg)
}
```

---

## 8. Actors

### Basic Actors

```scalascript
runActors {
  val counter = spawn {
    var count = 0
    while true do
      receive {
        case "increment" => count += 1
        case "get"       => self() ! count
        case "stop"      => exit(self(), "normal")
      }
  }

  counter ! "increment"
  counter ! "increment"
  counter ! "get"

  receive {
    case n: Int => println(s"Count: $n")   // 2
  }
}
```

### Supervision

```scalascript
runActors {
  val worker = spawn {
    receive {
      case "crash" => throw new RuntimeException("boom")
      case msg     => println(s"Working on: $msg")
    }
  }

  // Supervisor links to the worker
  val supervisor = spawn {
    link(worker)
    trapExit(true)
    receive {
      case ("EXIT", pid, reason) =>
        println(s"Worker $pid died: $reason")
        // restart or give up
    }
  }

  worker ! "crash"
}
```

### Distributed Actors

```scalascript
// Node A — start cluster node
clusterStart("node-a", port = 4242)

// Node B — connect and discover
val cluster = clusterConnect("ws://node-a:4242")

// Spawn on a remote node
val remote = cluster.spawn("node-a") {
  receive {
    case msg => println(s"Remote got: $msg")
  }
}

remote ! "hello from node-b"

// List cluster members
cluster.members.foreach(m => println(m.id))
```

---

## 9. Data Processing

### Dataset API

```scalascript
val ds = Dataset.from(List(
  ("Alice", 85), ("Bob", 92), ("Alice", 78), ("Bob", 88)
))

// Transformations (lazy)
val byName = ds.groupBy(_._1)
val avgScore = byName.map { case (name, records) =>
  (name, records.map(_._2).sum.toDouble / records.length)
}

// Execute
avgScore.runLocal().foreach(println)
// (Alice, 81.5)
// (Bob, 90.0)
```

### Aggregations

```scalascript
val nums = Dataset.from(1 to 1000)
nums.top(5)          // List(1000, 999, 998, 997, 996)
nums.count()         // 1000
nums.sum()           // 500500
nums.avg()           // 500.5
nums.min()           // 1
nums.max()           // 1000
nums.countByValue()  // Map(1 -> 1, 2 -> 1, ...)
```

### MapReduce

```scalascript
val words = Dataset.fromFile("corpus.txt")
  .flatMap(line => line.split("\\s+").toList)
  .map(_.toLowerCase)

val wordCounts = words
  .reduceByKey(identity)((a, b) => a + b)
  .sortBy(_._2)
  .collect()
```

### Parallel and Distributed

```scalascript
// Local multi-core
val result = bigDataset.map(expensiveTransform).runParallel()

// Distributed (requires running actor cluster)
val result = bigDataset.runDistributed(clusterNodes,
  handler = "worker",
  failurePolicy = FailurePolicy.RetryOnce
)
```

### File I/O

```scalascript
val ds = Dataset.fromFile("data.csv")    // one item per line
ds.saveToFile("output.csv")
ds.toList
ds.toMap(_.split(",").head, _.split(",").last)
```

---

## 10. DSL Authoring

### Parser Combinators

```scalascript
// Primitive parsers
val digit  = Parser.satisfy(_.isDigit)
val letter = Parser.satisfy(_.isLetter)
val ws     = Parser.whitespace

// Build up
val integer: Parser[Int] =
  digit.rep1.map(_.mkString.toInt)

val ident: Parser[String] =
  (letter ~ (letter | digit | char('_')).rep)
    .map { case (h, t) => (h +: t).mkString }

// Combinators
val pair   = integer <~ ws ~ integer   // both, skip whitespace
val choice = integer | ident           // first match wins
val items  = integer.sep(char(','))    // comma-separated list

// Sequencing operators
val a ~> b   // keep right
val a <~ b   // keep left
val a ~ b    // keep both as pair
val a >> b   // sequence, keep right (flatMap)
```

### Error Recovery

```scalascript
// Recover: skip until delimiter found
val stmt = expr.recoverUntil(char(';'))

// Error node: produce a placeholder, continue
val safeExpr = expr.errorNode(ErrorExpr("bad input"))

// Parse all: accumulate errors, don't stop at first failure
parseAll(stmts, input) match
  case ParseResult.Ok(asts, warnings) => ...
  case ParseResult.Err(errors)        => ...
```

### Indentation-Aware Parsing

```scalascript
val block: Parser[List[Stmt]] = withIndent {
  sameIndent(stmt).rep
}

val ifStmt = for
  _     <- keyword("if")
  cond  <- expr
  body  <- block
yield IfStmt(cond, body)
```

### Multi-Pass Compilation

```scalascript
// Define passes
val parse: Pass[String, Ast]          = Pass.of(parseSource)
val typecheck: Pass[Ast, TypedAst]    = Pass.of(checkTypes)
val optimize: Pass[TypedAst, TypedAst] = Pass.of(optimize)
val emit: Pass[TypedAst, String]       = Pass.of(generateCode)

// Chain
val compiler = parse andThen typecheck andThen optimize andThen emit
compiler.run(sourceCode) match
  case Right(code) => writeFile(code)
  case Left(errs)  => errs.foreach(println)

// Parallel passes (both run, results combined)
val analysis = typecheck parallel scopeAnalysis

// Recovery
val lenient = typecheck.recover { errs =>
  println(s"Type errors: ${errs.length}")
  partiallyTyped
}
```

### Tree Walkers

```scalascript
// Catamorphism — bottom-up fold
val eval: Ast => Value = cata[Ast, Value] {
  case Lit(n)        => Value.Int(n)
  case Add(l, r)     => Value.Int(l.asInt + r.asInt)
  case Mul(l, r)     => Value.Int(l.asInt * r.asInt)
  case If(c, t, e)   => if c.asBool then t else e
}

// Visitor
object Printer extends Visitor[Ast]:
  def visit(node: Ast): Unit = node match
    case Lit(n)    => print(n)
    case Add(l, r) => print("("); visit(l); print("+"); visit(r); print(")")
```

---

## 11. Module System

### Basic Imports

```markdown
<!-- Import all definitions from another file -->
[geometry](./geometry.ssc)

<!-- Selective import -->
[Circle, Rectangle](./shapes.ssc)

<!-- Aliased import -->
[Card as UICard](./ui/card.ssc)
[Card as ChartCard](./charts/card.ssc)
```

### Package Namespaces

```yaml
---
package: org.example.ui
---
```

```scalascript
// consumers access via: org.example.ui.Button.render(...)
object Button:
  def render(label: String): String = html"<button>$label</button>"
```

### URL Imports

```markdown
[utils](https://raw.github.com/org/lib/v2.0/utils.ssc)
```

Cached at `~/.cache/ssc/`. Set `SSC_NO_NETWORK=1` to disable fetching.

### Dependency Imports

```yaml
---
dependencies:
  mylib: https://cdn.example.com/mylib/v1.2
---
```

```markdown
[Widget](mylib://widget.ssc)
```

`ssc.lock` pins exact hashes for reproducible builds.

### Plugin System

```bash
ssc plugin install ./my-plugin.sscpkg   # install from file
ssc plugin install org.example/mylib   # install from registry
ssc plugin list
ssc plugin uninstall mylib
ssc plugin pack src/                    # create .sscpkg from source
```

### Separate Compilation

```bash
ssc emit-interface lib.ssc             # .scim — interface for consumers
ssc compile-jvm lib.ssc               # .scjvm — compiled JVM artifact
ssc compile-js lib.ssc                # .scjs — compiled JS artifact
ssc link --backend jvm artifacts/     # link into executable
ssc build --incremental src/          # build whole project
ssc deps app.ssc                      # show import closure
ssc info lib.scjvm                    # inspect artifact
```

---

## 12. Testing

### Writing Tests

```scalascript
// ssc test file.ssc
test("addition works") {
  assert(1 + 1 == 2)
  assert(List(1, 2, 3).sum == 6)
}

test("pattern matching") {
  val x: Option[Int] = Some(42)
  x match
    case Some(n) => assert(n == 42)
    case None    => fail("expected Some")
}
```

### Testing Effects

Use test handlers to control effects deterministically:

```scalascript
test("logger captures output") {
  val logs = runTestLogger {
    Logger.info("hello")
    Logger.warn("uh oh")
  }
  assert(logs.map(_.message) == List("hello", "uh oh"))
}

test("random is deterministic") {
  val a = runSeededRandom(42)(Random.nextInt(100))
  val b = runSeededRandom(42)(Random.nextInt(100))
  assert(a == b)
}

test("state effect") {
  val (finalState, result) = runState(0) {
    State.modify[Int](_ + 1)
    State.modify[Int](_ + 1)
    State.get[Int]
  }
  assert(finalState == 2)
  assert(result == 2)
}

test("mock HTTP") {
  val result = runMockHttp(Map(
    "GET /api/user" -> """{"name":"Alice"}"""
  )) {
    Http.get("/api/user")
  }
  assert(result.contains("Alice"))
}
```

### Conformance Tests

Run the cross-backend conformance suite:

```bash
scala-cli conformance/run.sc
```

Each test runs on all three backends and compares output. Add new tests under `conformance/` with an expected output in `conformance/expected/`.

---

## 13. Formatting (`ssc fmt`)

`ssc fmt` is the canonical formatter for `.ssc` files. It normalises
front-matter key order, heading style, blank lines around code blocks,
and trailing whitespace — all without touching code block contents.

### Basic usage

```bash
ssc fmt file.ssc              # format in-place
ssc fmt src/*.ssc             # format multiple files
ssc fmt --check file.ssc      # exit non-zero if file needs formatting (CI)
ssc fmt --stdout file.ssc     # print formatted output to stdout
```

### What is normalised

**Front-matter** (YAML block between `---` delimiters):

- Key order: `name`, `version`, `description`, `main`, `package`, `exports`,
  `dependencies`, `routes`, then remaining keys alphabetically.
- Trailing spaces removed from every line.
- Exactly one blank line after the closing `---`.

**Markdown body**:

- Heading style: `##Title` becomes `## Title` (exactly one space after `#`s).
- Exactly one blank line before and after each fenced code block.
- Exactly one blank line before each heading (except the first line of the body).
- Trailing whitespace stripped from all lines.
- LF line endings; file ends with exactly one newline.

**Code block contents** are never touched — they are preserved verbatim.

**Shebang** (`#!/usr/bin/env ssc`) is kept at position 0 unchanged.

### CI integration

Add `ssc fmt --check` to your CI pipeline to enforce consistent formatting:

```yaml
- name: Check .ssc formatting
  run: find src -name '*.ssc' -exec ssc fmt --check {} +
```

The command exits 0 if every file is already formatted, non-zero otherwise
(with a message per offending file on stderr).

### Idempotency

`ssc fmt` is idempotent: running it twice produces the same result as running
it once. A file that passes `--check` will always pass `--check` again after
being formatted.

---

## 14. Apache Spark

The `spark` backend (`bin/ssc-spark` or `ssc run --backend spark`) compiles a `.ssc`
program to a Scala 3.7.1 `.sc` script with `//> using` directives and runs it
via `scala-cli` against Apache Spark 4.0.0. Spark JARs are resolved on demand
by Coursier — no `sbt`/`maven` setup needed.

### 13.1 Quick start

```ssc
---
name: spark-quick
backend: spark
spark-master: local[*]
---

# Spark quick start

```scalascript
case class User(id: Int, name: String, active: Boolean)

val users = Dataset.fromList(List(
  User(1, "Alice", true),
  User(2, "Bob",   false),
  User(3, "Carol", true)
))

users.filter(_.active).show()
println(s"active: ${users.filter(_.active).count()}")
```
```

Run:

```bash
bin/ssc-spark spark-quick.ssc
```

The first run downloads Spark 4 + transitive deps (~200 MB) through Coursier;
subsequent runs are instant.

### 13.2 Front-matter keys

| Key | Type | Purpose |
|-----|------|---------|
| `backend: spark` | string | Select the Spark backend |
| `spark-version` | string | Spark release (default `4.0.0`) |
| `spark-master` | string | `local[*]` / `local[N]` / `spark://...` / `yarn` / `k8s://...` |
| `spark-app-name` | string | Visible in Spark UI / history server / driver logs |
| `spark-config` | map | Ad-hoc `key: value` entries — each emits one `.config(k, v)` line |
| `spark-hive-metastore` | string | Thrift URI; triggers `.enableHiveSupport()` + `spark-hive_2.13` dep |
| `spark-warehouse` | string | Warehouse path; triggers `.enableHiveSupport()` + `spark-hive_2.13` dep |

CLI overrides take precedence over front-matter:

```bash
ssc-spark file.ssc --spark-master spark://prod:7077 --spark-version 4.0.0
```

### 13.3 SQL fenced blocks

A `sql` fenced block becomes a `val _sqlBlock_<N>: DataFrame` in the generated
`@main` scope. The enclosing section's identifier also produces a friendly
alias.

````markdown
## Active Users

```sql
SELECT id, name FROM users WHERE active = ${true} ORDER BY name
```

```scalascript
ActiveUsers.sql.show()        // section alias
_sqlBlock_0.show()            // C.1 internal name still works
```
````

`${expr}` interpolation lifts to `:bind<N>` parameters — the value is evaluated
as Scala in the surrounding scope and passed via Spark SQL's
parameterised `sql(text, args)`.

### 13.4 Case-class encoders (Phase E)

`Dataset[CaseClass]` works natively on Scala 3 + Spark `_2.13` thanks to a
mirror-based encoder shim emitted at the top of every Spark source. Supports:

- Primitive fields (`String`, `Int`, `Long`, `Double`, `Boolean`, …)
- `Option[T]`
- Nested case classes
- Collections — `Seq[T]`, `List[T]`, `Vector[T]`, `Set[T]`, `Array[T]`, `Map[K, V]`
- Tuples (`Tuple2`, `Tuple3`, …)
- `o.a.s.ml.linalg.Vector` (when MLlib is present — see §13.9)

```scalascript
case class Address(city: String, zip: String)
case class Person(id: Int, name: String, address: Option[Address], tags: Seq[String])

val people: Dataset[Person] = Dataset.fromList(...)
people.printSchema()    // case-class structure preserved
```

### 13.5 `@SqlFn` UDFs

A `def` annotated with `@SqlFn` inside a `scalascript` block becomes a Spark
UDF registered on the session catalog, callable from subsequent `sql` blocks.

```scalascript
@SqlFn
def upper(s: String): String = s.toUpperCase
```

```sql
SELECT upper(name) FROM users
```

The codegen strips the annotation and emits a Java `UDFN` wrapper with the
return DataType resolved from `SparkGen.SqlFnDataType` — TypeTag-free, so it
runs cleanly on Scala 3 + Spark `_2.13`.

### 13.6 Structured Streaming (Phase F)

Detection of `spark.readStream` / `.writeStream` auto-emits the streaming
imports and appends `spark.streams.active.headOption.foreach(_.awaitTermination())`
before `spark.stop()` when the user code doesn't already call `awaitTermination`.
`.format("kafka")` auto-emits the `spark-sql-kafka-0-10_2.13` dep.

```scalascript
val stream = spark.readStream.format("rate").option("rowsPerSecond", 1).load()
stream.writeStream.format("console").start()
// awaitTermination shim auto-emitted
```

Examples: `spark-streaming-rate-console.ssc`, `spark-streaming-file-parquet.ssc`,
`spark-streaming-kafka.ssc`. Full spec: [`docs/spark-streaming.md`](spark-streaming.md).

### 13.7 Delta Lake (Lakehouse L.2)

`.format("delta")` in user code triggers auto-emit of
`io.delta:delta-spark_2.13:3.2.0` plus the required Spark SQL extensions and
catalog configs.

```scalascript
df.write.format("delta").save("/tmp/users-delta")
val back = spark.read.format("delta").load("/tmp/users-delta")
```

Iceberg / Hudi are deferred until upstream publishes Spark 4 + `_2.13`
artifacts. Full spec: [`docs/spark-lakehouse.md`](spark-lakehouse.md).

### 13.8 Hive metastore + `@TempView` (Phase G)

Set `spark-hive-metastore:` and/or `spark-warehouse:` in front-matter to
enable Hive support — `.enableHiveSupport()` is added to the builder and the
`spark-hive_2.13` dep auto-emitted.

`@TempView("name")` on a `val` registers the Dataset as a temp view for
subsequent `sql` blocks:

```scalascript
@TempView("users")
val users = Dataset.fromParquetAs[User]("/data/users.parquet")
```

`Dataset.fromTable[T]("name")` is a typed reader over `spark.table(name).as[T]`
that composes with both temp views and Hive tables.

Full spec: [`docs/spark-catalog.md`](spark-catalog.md).

### 13.9 MLlib (Phase M)

Any `import org.apache.spark.ml.*` triggers auto-emit of
`spark-mllib_2.13:<sparkVersion>` and the `Vector` encoder shim
(`o.a.s.ml.linalg.Vector` via the public `SQLDataTypes.VectorType` singleton).

```scalascript
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.feature.{HashingTF, Tokenizer}
import org.apache.spark.ml.Pipeline

val pipeline = new Pipeline().setStages(Array(
  new Tokenizer().setInputCol("text").setOutputCol("words"),
  new HashingTF().setInputCol("words").setOutputCol("features"),
  new LogisticRegression()
))
val model = pipeline.fit(training)
model.save("/tmp/model")
val loaded = PipelineModel.load("/tmp/model")
```

Full spec: [`docs/spark-mllib.md`](spark-mllib.md).

### 13.10 Cluster submission (`ssc submit`)

For non-local clusters use `ssc submit` (fat JAR via `scala-cli package` +
`spark-submit`):

```bash
ssc submit file.ssc                                  # local
ssc submit file.ssc --spark-master spark://prod:7077 # standalone
ssc submit file.ssc --spark-master yarn -- --num-executors 8 --executor-memory 4g
ssc submit file.ssc --spark-master k8s://https://...  # Kubernetes
ssc submit file.ssc --dry-run                         # print the spark-submit invocation
```

### 13.11 Caveats

- **Scala 3.7.1 pin** — Scala 3.8.x has a TASTy-bridge regression that breaks
  Spark `_2.13` runtime reflection in `ExpressionEncoder`. The shim pins
  `//> using scala 3.7.1` automatically; if you override it, things break.
- **JDK 17+ `--add-opens`** — Spark needs reflective access to `sun.nio.ch`,
  `java.lang.reflect`, etc. The shim emits these as `//> using javaOpt`
  directives — `scala-cli run` works without extra args.
- **No TypeTag path** — `import spark.implicits._` is dropped from emit (its
  TypeTag-bound `newProductEncoder` poisons implicit search). Use
  `import SscSparkEncoders.given` instead — already injected.
- **Iceberg / Hudi blocked upstream** — see § 13.7. Watch tasks for
  `iceberg-spark-runtime-4.0_2.13` and `hudi-spark4.0-bundle_2.13`.

---

## 15. WebAssembly backend

`ssc emit-wasm file.ssc` lowers `scalascript` blocks to a WebAssembly module
(no Node.js / JVM at runtime). `sql` fenced blocks are supported via the
cross-backend SQL runtime (v1.27 Phase 5). Cross-backend semantics: identical
output to the interpreter and the JS / JVM backends for the same source.

```bash
ssc emit-wasm examples/wasm-fibonacci.ssc -o out.wasm
examples/run-wasm.sh out.wasm
```

Examples: `wasm-fibonacci.ssc`, `wasm-sorting.ssc`, `wasm-matrix.ssc`,
`wasm-primes.ssc`, `wasm-collections.ssc`.

---

## 16. Frontend Framework SPI (v1.18 A)

Same `.ssc` UI source, four targets. Pick the backend per build; the source
stays identical.

| Backend id | Emits | Reactive primitive |
|-----------|-------|-------------------|
| `frontend-react` | React JSX + hooks | `useState` / `useEffect` |
| `frontend-vue` | Vue 3 SFC-equivalent | `ref` + render fn |
| `frontend-solid` | Solid components | `createSignal` / `createEffect` |
| `frontend-custom` | Minimal hand-written runtime | Built-in `Signal[T]` |

Shared reactive abstractions emit cleanly on all four:

- `Signal[T]` — observable value
- `ShowSignal(cond) { … }` — conditional render
- `ToggleSignal` — boolean toggle binding
- `ForSignal(items) { item => … }` — list render with keyed diff

```scalascript
val count = Signal(0)
button(onClick = () => count.set(count.get + 1)) { text("inc") }
ShowSignal(count.get > 5) { text("big") }
ForSignal(items) { item => li { text(item.title) } }
```

See [`docs/frontend-framework-spi-plan.md`](frontend-framework-spi-plan.md) and
[`docs/frontend-abstract-model.md`](frontend-abstract-model.md). Examples
under `examples/frontend/`.

---

## 17. Frontend Toolkit — `std/ui`

The `std/ui` toolkit lets you build reactive browser SPAs directly from a
`.ssc` script — no separate build step, no npm, no webpack.

- **One file** — routes, backend logic, and frontend UI in the same `.ssc`
- **React target** — emitted as React 18 hooks + JSX; `frontend: react` in front-matter
- **Theme-aware** — all widgets read design tokens from a `Theme` value
- **Fully reactive** — `Signal[T]` binds two-way to inputs, conditionals,
  text nodes, and REST fetch loops
- **Hot-reload** — `ssc serve myapp.ssc` hot-reloads on save

---

### 17.1 Architecture

Three layers, each with a single responsibility:

```
Widget constructors          lower()              serve() / emit()
(layout, input, display…)  ──────────►  View IR  ────────────────►  React SPA
return TkNode                            (pure)                       or bundle
```

1. **`TkNode`** — sealed ADT of case classes; pure data, no DOM, no extern.
   Widget constructors (`vstack`, `textField`, `badge`, …) build this tree.
2. **`lower(tree: TkNode, theme: Theme): View`** — converts the tree to the
   backend-agnostic `View` IR, threading in design tokens from the theme.
3. **`serve(view: View, port: Int)`** — starts the HTTP server; the React emitter
   compiles `View` to React hooks + JSX, served at `/`.
   **`emit(view: View, outDir: String)`** — writes a self-contained `index.html`
   + `app.js` bundle to `outDir` for static hosting.

---

### 17.2 Front-matter

```yaml
frontend: react
```

This key activates the React emitter.  It is required for any script that
calls `serve(lower(...), port)` or `emit(lower(...), dir)`.

---

### 17.3 Import pattern

Import selectively from each `std/ui` sub-module:

```markdown
[signal, serve, fetchUrlSignal, fetchAction, fetchActionClear, incSignal](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack, hstack, divider, spacer](std/ui/layout.ssc)
[heading, text](std/ui/typography.ssc)
[textField, checkbox, signalButton, actionButton](std/ui/input.ssc)
[showWhen, signalText_, fragment_, rawText](std/ui/reactive.ssc)
[badge, spinner, signalPre, fetchTable](std/ui/display.ssc)
[card, cardWithHeader, modal](std/ui/containers.ssc)
[tableCol, tableRow, table, sortableTable](std/ui/data.ssc)
[route, router, link, hashRouter](std/ui/routing.ssc)
```

Only import what you use — unused imports do not affect bundle size.

---

### 17.4 Signals

Signals are the reactive primitive.  Each signal has a stable string `name`
used as the JS variable name in the emitted code.

```scalascript
val count   = signal[Int]("count",   0)
val name    = signal[String]("name", "")
val open    = signal[Boolean]("open", false)
val refresh = signal[Int]("refresh", 0)
```

The React emitter turns each `signal(name, default)` call into:
```js
const [count, setCount] = useState(0)
```

---

### 17.5 Widget catalog

#### Layout

| Constructor | Description |
|-------------|-------------|
| `vstack(gap = 0)(children*)` | Vertical flex column; `gap` is pixel spacing between children |
| `hstack(gap = 0)(children*)` | Horizontal flex row |
| `divider()` | Horizontal rule styled with `theme.colors.muted` |
| `spacer(grow = false)` | Fixed 8 px gap; `grow = true` fills remaining space (use inside `hstack` to right-align) |

```scalascript
hstack(gap = 12)(
  heading(2, "My App"),
  spacer(grow = true),
  badge("v1.0", "success")
)
```

#### Typography

| Constructor | Description |
|-------------|-------------|
| `heading(level: Int, text: String)` | `h1`–`h6`; font size from theme |
| `text(content: String)` | `<p>` paragraph |

#### Input

| Constructor | Description |
|-------------|-------------|
| `textField(value, label, disabled, required)` | Labeled text input; `value` must be `Signal[String]`; two-way bound |
| `checkbox(checked, label, disabled)` | Checkbox; `checked` must be `Signal[Boolean]`; two-way bound |
| `signalButton(signal, value, label, disabled)` | Button that sets `signal` to `value` on click |
| `actionButton(handler, label, disabled)` | Button wired to an `EventHandler` (`fetchAction`, `incSignal`, …) |

```scalascript
val name    = signal[String]("name", "")
val agreed  = signal[Boolean]("agreed", false)
val submitted = signal[Boolean]("submitted", false)

vstack(gap = 12)(
  textField(value = name, label = "Your name"),
  checkbox(checked = agreed, label = "I accept the terms"),
  signalButton(submitted, true, "Submit", disabled = !agreed)
)
```

#### Reactive helpers

| Constructor | Description |
|-------------|-------------|
| `showWhen(signal, whenTrue, whenFalse)` | Conditional render; `signal` must be `Signal[Boolean]` |
| `signalText_(signal)` | Inline reactive text node; re-renders when signal changes |
| `fragment_(children*)` | Group children without a wrapper `<div>` |
| `rawText(text: String)` | Literal text inline (no element, no binding) |

```scalascript
showWhen(submitted,
  hstack(gap = 4)(rawText("Welcome, "), signalText_(name), rawText("!")),
  text("Please fill out the form above.")
)
```

#### Display

| Constructor | Description |
|-------------|-------------|
| `badge(content, variant)` | Colored pill badge; variants: `"success"` `"warning"` `"danger"` `"notification"` `"default"` |
| `spinner()` | CSS spinning loader |
| `signalPre(signal)` | `<pre>` block showing a `Signal[String]`; preserves newlines |
| `fetchTable(fetchUrl, deleteUrl, tick)` | Reactive table — GETs `fetchUrl` on mount and on `tick` change; renders rows with a Delete button per row that POSTs the row's `id` to `deleteUrl` |

```scalascript
val logs = signal[String]("logs", "")
// ...
signalPre(logs)

badge("3 new",  "notification")
badge("saved",  "success")
badge("error",  "danger")
```

#### Containers

| Constructor | Description |
|-------------|-------------|
| `card(body*)` | Bordered, rounded card |
| `cardWithHeader(header)(body*)` | Card with a bold header bar |
| `cardWithFooter(footer)(body*)` | Card with a footer bar |
| `cardFull(header, footer)(body*)` | Card with both |
| `modal(open, title)(body*)` | Full-screen overlay dialog; shown when `open` (`Signal[Boolean]`) is `true` |

```scalascript
val showModal = signal[Boolean]("showModal", false)

cardWithHeader(heading(4, "Settings"))(
  text("Manage your account"),
  actionButton(setSignal(showModal, true), "Open settings")
)

modal(showModal, "Account Settings")(
  text("Settings form goes here"),
  actionButton(setSignal(showModal, false), "Close")
)
```

#### Data

| Constructor | Description |
|-------------|-------------|
| `tableCol(label, key)` | Column definition — `label` shown in header, `key` used for sort |
| `tableRow(cells*)` | Row; each cell is a `TkNode` |
| `table(cols, rows)` | Plain table |
| `sortableTable(sortCol, cols, rows)` | Clicking a header sets `sortCol` (`Signal[String]`) to that column's key; caller sorts `rows` accordingly |

```scalascript
val cols = [tableCol("Name", "name"), tableCol("Score", "score")]
val rows = users.map(u => tableRow(text(u.name), text(u.score.toString)))
table(cols, rows)
```

---

### 17.6 Fetch primitives

These event handlers and signals bridge the browser UI to the server REST API.

#### `fetchUrlSignal` — live data binding

```scalascript
// GETs /api/todos on mount; re-GETs whenever refresh changes.
val todosJson = fetchUrlSignal("todos", "/api/todos", refresh)
signalPre(todosJson)  // display raw JSON, or parse and render
```

#### `fetchAction` — POST / PUT / DELETE on click

```scalascript
val text = signal[String]("text", "")
val refresh = signal[Int]("refresh", 0)

// On click: POST /api/todos with body = text.value, then refresh += 1
actionButton(fetchAction("POST", "/api/todos", text, refresh), "Add")
```

#### `fetchActionClear` — submit and clear input

```scalascript
// Same as fetchAction, but also resets text to "" after success.
actionButton(fetchActionClear("POST", "/api/todos", text, refresh), "Add")
```

#### `incSignal` — manual refresh

```scalascript
// Bump refresh by 1 — any fetchUrlSignal watching it will re-fetch.
actionButton(incSignal(refresh), "Reload")
```

#### `fetchTable` — reactive REST table

```scalascript
val refresh = signal[Int]("refresh", 0)

// Fetches GET /api/todos → renders rows; each row has a Delete button
// that POSTs {id} to /api/todos/delete, then bumps refresh.
fetchTable("/api/todos", "/api/todos/delete", refresh)
```

`fetchTable` expects the server to return a JSON array:
```json
[{"id": 1, "text": "Buy milk"}, {"id": 2, "text": "Write tests"}]
```

---

### 17.7 Client-side routing

```scalascript
val page = signal[String]("page", "/")

val tree = vstack(gap = 0)(
  hstack(gap = 16)(
    link("/",       "Home",  page),
    link("/about",  "About", page),
    link("/todos",  "Todos", page)
  ),
  divider(),
  router(page, [
    route("/",       [heading(1, "Home"),  text("Welcome!")]),
    route("/about",  [heading(1, "About"), text("Built with std/ui.")]),
    route("/todos",  [heading(1, "Todos"), todosPanel])
  ])
)
```

`link` renders `<a href=path>` and sets `page` signal on click (no page
reload — SPA navigation).  `router` shows exactly one route at a time via
`eqSignal` guards compiled to React conditionals.

#### Hash-based routing

`hashRouter` uses `window.location.hash` as the current path — works with
static hosting (no server-side routing needed):

```scalascript
hashRouter([
  route("#/",      [heading(1, "Home")]),
  route("#/about", [heading(1, "About")])
])
```

---

### 17.8 Themes

```scalascript
// Built-in themes
serve(lower(tree, defaultTheme), 8080)   // light
serve(lower(tree, darkTheme),    8080)   // dark

// Custom theme
val myTheme = Theme(
  ColorPalette(
    primary    = "#0f766e",   // teal
    onPrimary  = "#ffffff",
    secondary  = "#6d28d9",
    surface    = "#f0fdfa",
    onSurface  = "#134e4a",
    background = "#ffffff",
    muted      = "#94a3b8",
    danger     = "#dc2626",
    success    = "#16a34a",
    warning    = "#d97706"
  ),
  SpacingScale(xs = 4, sm = 8, md = 16, lg = 24, xl = 32, xxl = 48),
  TypographyScale(
    body    = TypographyItem(16, "Inter, system-ui, sans-serif"),
    heading = TypographyItem(24, "Inter, system-ui, sans-serif")
  ),
  RadiusScale(sm = 4, md = 8, lg = 16, full = 9999)
)
```

All widget constructors are theme-unaware — themes are applied exclusively
inside `lower`.  Switching themes is a single argument change.

---

### 17.9 Serve vs emit

| | `serve(lower(tree, theme), port)` | `emit(lower(tree, theme), dir)` |
|---|---|---|
| **Use case** | Development / production server | Static hosting (S3, GitHub Pages, CDN) |
| **Registers `route()` handlers** | Yes | No |
| **Output** | HTTP on `port` | `dir/index.html` + `dir/app.js` |
| **Hot reload** | `ssc serve myapp.ssc` | Re-run `ssc myapp.ssc` |

```bash
ssc serve myapp.ssc               # dev server with hot reload
ssc myapp.ssc                     # emit bundle to ./dist/
```

---

### 17.10 Full example

A minimal but complete SPA: sign-up form + modal confirmation.

````ssc
#!/usr/bin/env ssc
---
name: signup
version: 1.0.0
frontend: react
---

# Sign-up demo

[signal, serve](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack, hstack, divider, spacer](std/ui/layout.ssc)
[heading, text](std/ui/typography.ssc)
[textField, checkbox, signalButton, actionButton](std/ui/input.ssc)
[showWhen, signalText_, fragment_, rawText](std/ui/reactive.ssc)
[badge](std/ui/display.ssc)
[cardWithHeader, modal](std/ui/containers.ssc)

```scalascript
val name      = signal[String]("name",      "")
val email     = signal[String]("email",     "")
val agreed    = signal[Boolean]("agreed",   false)
val submitted = signal[Boolean]("submitted", false)
val showInfo  = signal[Boolean]("showInfo",  false)

val form = cardWithHeader(heading(3, "Create account"))(
  vstack(gap = 12)(
    textField(value = name,  label = "Display name"),
    textField(value = email, label = "Email address"),
    checkbox(checked = agreed, label = "I accept the terms of service"),
    hstack(gap = 8)(
      signalButton(submitted, true, "Sign up", disabled = !agreed),
      actionButton(setSignal(showInfo, true), "?")
    )
  )
)

val confirmModal = modal(showInfo, "What happens next?")(
  text("We will send a confirmation email to the address you entered."),
  actionButton(setSignal(showInfo, false), "Got it")
)

val tree = vstack(gap = 24)(
  heading(1, "Welcome"),
  showWhen(submitted,
    hstack(gap = 4)(
      badge("Done", "success"),
      rawText(" Signed up as "),
      signalText_(name)
    ),
    form
  ),
  confirmModal
)

serve(lower(tree, defaultTheme), 8080)
```
````

---

### 17.11 React emitter internals

How the React emitter translates `View` IR to React code:

| `View` / `EventHandler` | Emitted React code |
|-------------------------|-------------------|
| `signal("x", 0)` | `const [x, setX] = useState(0)` |
| `inputChange(sig)` | `onChange={e => setSig(e.target.value)}` |
| `toggleSignal(sig)` | `onChange={e => setSig(e.target.checked)}` |
| `setSignal(sig, v)` | `onClick={() => setSig(v)}` |
| `showSignal(cond, t, f)` | `{cond ? t : f}` |
| `signalText(sig)` | `{sig}` inline JSX |
| `eqSignal(sig, v)` | `sig === v` computed inline |
| `fetchUrlSignal(name, url, tick)` | `useState("")` + `useEffect([tick], () => fetch(url).then(r=>r.text()).then(setName))` |
| `fetchAction(m, url, body, tick)` | `() => fetch(url,{method:m,body:getBody()}).then(()=>setTick(t=>t+1))` |
| `fetchActionClear(m, url, body, tick)` | Same + `setBody("")` on success |
| `hashSignal()` | `useState(location.hash)` + `hashchange` listener |

The emitter produces a single self-contained `app.js` with no external
runtime dependencies beyond React 18 (loaded from CDN in `index.html`).

---

## 18. Frontend Toolkit — Scala API (v1.18 B / B+ / B++ / C)

Higher-level declarative UI on top of the framework SPI.  Lives in
the `frontend-toolkit` sbt module; user code reaches for the `Tk`
facade.  Every widget lowers to the backend-agnostic `View` AST
through `Toolkit.lower(node, theme)`, so the same widget tree
compiles through React / Vue / Solid / Custom.

**Widget catalog (as of Phase B++)**

- *Layout*: Stack (`vstack`/`hstack`), Box, Spacer, Divider, Card
- *Typography*: Heading, Text, Code
- *Inputs*: Button, TextField, Checkbox, Slider, Select, RadioGroup,
  Textarea, DatePicker, NumberInput
- *Display*: Alert (a.k.a. `notice`), Badge, Avatar, Icon, Spinner,
  Progress, Tooltip
- *Containers*: Modal, Drawer
- *Navigation*: Tabs, Router + Link
- *Data*: Table with click-to-sort + ARIA
- *Form*: validation pipeline + `FormContext`

```scala
import scalascript.frontend.*
import scalascript.frontend.toolkit.*
import scalascript.frontend.toolkit.Tk

val name   = new ReactiveSignal[String]("name", "")
val agree  = new ReactiveSignal[Boolean]("agree", false)

val tree: ToolkitNode = Tk.vstack(gap = 16)(
  Tk.heading(1, "Sign up"),
  Tk.card()(
    Tk.vstack(gap = 12)(
      Tk.textField(name, label = Some("Display name"), required = true),
      Tk.checkbox (agree, label = "I accept the terms.")
    )
  ),
  Tk.button("Submit", onClick = () => submit(name(), agree()),
            kind = ButtonKind.Primary),
  Tk.notice(AlertSeverity.Success, title = Some("OK")) {
    Tk.text("Submitted.")
  }
)

// Lower once; every backend consumes the resulting View identically.
val view: View = Toolkit.lower(tree, Theme.default)
```

**Forms with validation**

```scala
Tk.form(onSubmit = ctx => api.createUser(ctx.values())) { ctx =>
  val email = ctx.field[String]("email", "",
    Validators.and(Validators.required, Validators.email))
  val pwd   = ctx.field[String]("password", "",
    Validators.and(Validators.required, Validators.minLength(8)))
  Tk.vstack(gap = 8)(
    Tk.textField(email.value, label = Some("Email"), error = Some(email.error)),
    Tk.textField(pwd.value,   label = Some("Password"),
                 inputType = "password", error = Some(pwd.error)),
    Tk.button("Create", onClick = () => (), formSubmit = true)
  )
}
```

**Routing**

```scala
val currentPath = new ReactiveSignal[String]("path", "/")
Tk.router(currentPath, notFound = Tk.text("404"))(
  Tk.route("/")          (_ => homePage()),
  Tk.route("/users/:id") (params => userProfile(params("id")))
)
```

**Run the toolkit demo**

A complete reference SPA ships with the project under
[`frontend-examples`](../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala):

```bash
# 1. Compile + test (217 toolkit + 41 demo = 258 tests)
sbt frontendToolkit/test frontendExamples/test

# 2. Emit 16 static bundles (4 demos x 4 backends)
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"
# → target/frontend-examples/toolkit-demo/{custom,react,solid,vue}/

# 3. Serve via the bundled scalascript HTTP server (no Python/Node)
ssc serve 8000 target/frontend-examples/toolkit-demo/react
# open http://localhost:8000/
```

The Custom backend currently renders the toolkit demo as static
HTML (signal-binding through JVM lambdas is Phase D work); React /
Vue / Solid run fully reactive.

**SSR**

`Ssr.renderToHtml(viewOrNode, theme)` snapshots the toolkit tree to
a static HTML string with no JS subscriptions — useful for SEO,
static-site generation, email templates, or snapshot tests:

```scala
import scalascript.frontend.toolkit.{Ssr, Theme}
val html = Ssr.renderToHtml(tree, Theme.default)
val doc  = Ssr.renderDocument(tree, title = "Demo", theme = Theme.dark)
```

Spec: [`docs/frontend-toolkit-spec.md`](frontend-toolkit-spec.md).
Cross-backend integration: [`docs/frontend-usage.md`](frontend-usage.md).

---

## 19. Cluster management

Distributed actors + cluster primitives baked in across all server backends:

| Primitive | Notes |
|-----------|-------|
| Bully leader election | `cluster.leader()` returns the current bully-elected leader (Signal) |
| Phi-accrual failure detector | Tunable phi threshold per cluster; heartbeat-driven liveness |
| Self-health | `cluster.self.health` — driver-side health metric stream |
| Federation | Multi-cluster gossip + cross-cluster routing |
| Raft consensus | Strongly-consistent state machine — `RaftStateMachine[S]` |
| ZooKeeper client | `zkClient { … }` for legacy coordinator integration |
| Operational routes | `GET /_ssc-cluster/status`, `/members`, `/leader` — built-in across server backends |

Specs: [`docs/cluster-management.md`](cluster-management.md),
[`docs/cluster-raft.md`](cluster-raft.md),
[`docs/cluster-federation.md`](cluster-federation.md),
[`docs/client-zookeeper.md`](client-zookeeper.md).

---

## 20. x402 micropayments

HTTP 402 → typed payment challenge / settlement. Same `.ssc` source describes
both client and server; the protocol layer wires the payment family
(Ethereum / Cardano) automatically.

```scalascript
x402Server {
  route("/quote") { req => requirePayment(Payment.usdc(0.10)) { _ =>
    Response.json(getQuote(req.params("symbol")))
  }}
}
```

```scalascript
x402Client(wallet) {
  val quote = httpGet("https://api/quote?symbol=AAPL").settle()
  println(quote)
}
```

Specs:
- [`docs/x402.md`](x402.md) — protocol + flows
- [`docs/blockchain-spi.md`](blockchain-spi.md) — pluggable backends (EVM, Bitcoin, Solana, Cardano)
- [`docs/micropayment-spi.md`](micropayment-spi.md) — payment family abstraction
- [`docs/mcp-x402-wallet.md`](mcp-x402-wallet.md) — MCP × x402 paid LLM tools

Examples: `x402-server.ssc`, `x402-client.ssc`, `x402-cardano.ssc` (end-to-end
Cardano flow with CIP-8 wallet + Scalus escrow validator).

---

## 21. Compiler Plugins with Intrinsics

ScalaScript's backend is open to extension: any capability not built into
the language — cryptographic hashing, ML inference, GPU kernels, custom
IO — can be packaged as a **plugin** and distributed as a single
`.sscpkg` file.

### 21.1 What an intrinsic is

An **intrinsic** is an `extern def` in a `.ssc` source file whose body
is supplied by a plugin rather than written in ScalaScript.  From the
caller's perspective it looks like an ordinary function:

```scala
import [Crypto](crypto)

val digest = sha256("hello, world")
val token  = base64Encode(digest)
```

The `extern def` declaration lives in the plugin's bundled `.ssc` source
(e.g. `sources/crypto.ssc`) and is imported like any other module:

```scala
// sources/crypto.ssc  (shipped inside the .sscpkg)
extern def sha256(input: String): String
extern def base64Encode(s: String): String
extern def base64Decode(s: String): String
extern def hmacSha256(key: String, data: String): String
```

The compiler knows the type signature; the actual implementation is
provided by the plugin's `IntrinsicImpl` entries.

### 21.2 The `IntrinsicImpl` variants

Each `extern def` maps to one of four strategies in
`scalascript.backend.spi.IntrinsicImpl`:

| Variant | When to use |
|---------|------------|
| `NativeImpl(fn)` | Interpreter backend — `fn` is called directly with evaluated args; no code generation needed |
| `RuntimeCall(sym)` | Code-generating backends (JVM, JS) — the call site becomes `sym(args…)`; the function is defined in `runtime/jvm.scala` or `runtime/js.js` bundled with the plugin |
| `InlineCode(emit)` | Inline target-source at each call site when `RuntimeCall` is too coarse-grained |
| `HostCallback(name)` | Out-of-process backends — routes the call back through the host wire protocol |

**Typical pattern:** supply both `RuntimeCall` and `NativeImpl` for the same
symbol, registered in separate `Backend` classes — one for JVM/JS code
generation, one for the interpreter.

```scala
// For JVM/JS backends — emitted as a call to a runtime helper
QualifiedName("std.crypto.sha256") -> RuntimeCall("_cryptoSha256")

// For the interpreter — direct JVM call, no code emission
QualifiedName("std.crypto.sha256") -> NativeImpl { (_, args) =>
  val md = java.security.MessageDigest.getInstance("SHA-256")
  md.digest(args.head.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
}
```

### 21.3 Runtime helpers

For `RuntimeCall`, the function `_cryptoSha256` must exist in the
generated output.  The plugin ships platform-specific helpers that
`BackendRegistry` prepends to every compiled file:

```scala
// runtime/jvm.scala  — injected before user code for JVM output
private def _cryptoSha256(input: Any): String =
  val md = java.security.MessageDigest.getInstance("SHA-256")
  md.digest(input.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
```

```javascript
// runtime/js.js  — injected for JS/Node output
const _cryptoSha256 = (input) => {
  const crypto = require('crypto');
  return crypto.createHash('sha256').update(String(input), 'utf8').digest('hex');
};
```

### 21.4 The `Backend` SPI

A plugin exposes its intrinsic table by implementing `scalascript.backend.spi.Backend`:

```scala
class CryptoBackendPlugin extends Backend:
  def id          = "crypto-intrinsics-jvm"
  def displayName = "Crypto Intrinsics (JVM/JS)"
  def spiVersion  = SpiVersion.Current

  def capabilities    = Capabilities(features = Set.empty, outputs = Set.empty,
                                     options  = Set.empty,
                                     spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics      = CryptoIntrinsics.table   // Map[QualifiedName, IntrinsicImpl]
  def acceptedSources = Set.empty

  // Pure intrinsic-provider plugin — does not compile standalone programs.
  def compile(ir, opts) =
    CompileResult.Failed(List("use jvm or js backend"))
```

Register it with Java's `ServiceLoader` by creating:

```
META-INF/services/scalascript.backend.spi.Backend
```

containing the fully-qualified class name.

### 21.5 The `.sscpkg` format

A plugin is distributed as a ZIP file with a `.sscpkg` extension and the
following layout:

```
manifest.yaml          # package metadata
sources/               # .ssc files with extern def declarations
  crypto.ssc
runtime/               # target-platform helpers
  jvm.scala
  js.js
intrinsics/            # JAR(s) with Backend SPI implementations
  crypto-plugin-1.0.0.jar
```

**`manifest.yaml`** example:

```yaml
id:         org.example.crypto
version:    1.0.0
spiVersion: "0.1.0"
kind:       [library, plugin]
targets:    [jvm, interpreter]

capabilities:
  features:   []
  declares:   [CryptoUtils]

exports:
  externDefs:
    - std.crypto.sha256
    - std.crypto.base64Encode
    - std.crypto.base64Decode
    - std.crypto.hmacSha256
```

Build the `.sscpkg` with:

```bash
# Compile the plugin JAR (scala-cli or sbt)
scala-cli package . --assembly -o crypto-plugin-1.0.0.jar

# Pack
mkdir -p _pkg/intrinsics _pkg/sources _pkg/runtime
cp manifest.yaml _pkg/
cp -r sources/ _pkg/sources/
cp -r runtime/ _pkg/runtime/
cp crypto-plugin-1.0.0.jar _pkg/intrinsics/
ssc plugin pack _pkg -o org.example.crypto-1.0.0.sscpkg
```

### 21.6 Installing and using plugins

```bash
# Install permanently in ~/.scalascript/plugins/
ssc plugin install ./org.example.crypto-1.0.0.sscpkg

# Ad-hoc for a single run
ssc --plugin ./org.example.crypto-1.0.0.sscpkg run my-script.ssc

# Inspect installed plugins
ssc plugin list

# Check compatibility without running
ssc plugin check ./org.example.crypto-1.0.0.sscpkg

# Uninstall
ssc plugin uninstall org.example.crypto
```

The compiler discovers plugins via a registry file at
`~/.scalascript/registry.yaml`.  Plugins are loaded lazily on first
import — an unused plugin adds zero overhead.

### 21.7 Built-in plugins

All standard capabilities beyond the core language are shipped as
`.sscpkg` plugins bundled with `ssc`:

| Plugin | Intrinsics it provides |
|--------|----------------------|
| `std/json-plugin` | `jsonStringify`, `jsonParse`, `jsonRead`, `lookup`, `lookupOpt` |
| `std/http-plugin` | `serve`, `route`, `httpGet`, `httpPost`, `Response.*` |
| `std/sql-plugin` | `Db.query`, `Db.execute`, `Db.transaction` |
| `std/ws-plugin` | `wsRoute`, `wsBroadcast`, `WsSession.*` |
| `std/frontend-plugin` | `lower`, `serve` (UI), `emit` |
| `std/fetch-plugin` | `fetchAction`, `fetchUrlSignal`, `incSignal` |
| `std/auth-plugin` | `session`, `jwt`, `oauth2` |
| `std/mcp-plugin` | `mcpServer`, `mcpTool` |

Third-party plugins follow the same `.sscpkg` format.  See
`examples/plugins/crypto-plugin/` for a complete worked example and
`examples/plugins/hello-backend/` for a minimal skeleton.

---

## 22. Config System (v1.28)

ScalaScript v1.28 introduces first-class configuration: YAML, JSON, and HOCON config
sources that merge into a single typed tree, with uniform secret substitution and typed
bindings in ScalaScript, Scala, and JavaScript.

### 22.1 Config sources

Three sources can be used simultaneously — they merge according to a priority order
(highest to lowest by default: fenced blocks > external files > front-matter).

**Front-matter** — the entire front-matter is the config root.  Existing keys
(`databases:`, `frontend:`, `dep:`) remain fully backward-compatible:

```yaml
---
frontend: react
config:
  server:
    port: 8080
    host: "0.0.0.0"
  files: [app.yaml, "${env:ENV}.hocon"]   # external files
---
```

**Fenced config blocks** — inline config in the `.ssc` file:

````
```yaml config "server"
port: ${env:PORT | 8080}
host: "0.0.0.0"
tls:
  cert: ${file:/run/secrets/cert.pem}
```

```json config "features"
{"dark_mode": true, "beta": false}
```
````

Named blocks (e.g. `"server"`) are scoped to `config.server.*`.
Unnamed blocks (` ```yaml config `) merge at the root.

**External files** — referenced from front-matter:

```yaml
---
config:
  files:
    - path: defaults.yaml
    - path: "${env:ENV}.hocon"
      optional: true
---
```

Or shorthand: `config: [app.yaml, prod.hocon]`

### 22.2 Substitution syntax

All config formats support the unified `${scheme:ref}` syntax plus HOCON-style variables:

| Pattern | Meaning |
|---------|---------|
| `${env:PORT}` | Required environment variable |
| `${env:PORT \| 8080}` | Env var with default |
| `${file:/run/secrets/pw}` | File contents (trimmed) |
| `${sops:db.password}` | sops-decrypted YAML key |
| `${vault:secret/app#field}` | Vault plugin |
| `${config:server.port}` | Cross-reference another config key |
| `${?VAR}` | HOCON optional — empty string if missing |
| `${VAR}` | HOCON required env var |

### 22.3 Using config in ScalaScript code

The `config` global is always available (no import needed):

```scala
// Dynamic path accessor (no type annotation needed)
val port = config.server.port.getInt("port")    // or:
val port = config.getInt("server.port")          // Option[Int]
val host = config.getString("server.host")       // Option[String]
val host = config.requireString("server.host")   // String (throws if missing)

// Section accessor
val srv = config.section("server")
val port: Int = srv.requireInt("port")
```

**Typed binding with `derives Config`:**

```scala
case class ServerConfig(port: Int, host: String, tls: Boolean) derives Config
case class AppConfig(server: ServerConfig, debug: Boolean) derives Config

val app = config.as[AppConfig]               // Either[ConfigError, AppConfig]
val srv = config[ServerConfig]("server")     // Either[ConfigError, ServerConfig]
```

### 22.4 Priority override

Default order: fenced blocks > external files > front-matter.

Override in front-matter:
```yaml
---
config:
  priority: [frontmatter, files, blocks]   # frontmatter wins
---
```

Override in code:
```scala
val loader = ConfigLoader.fromFrontmatter(yaml)
  .withPriority(List(Priority.Frontmatter, Priority.Files, Priority.Blocks))
```

### 22.5 JavaScript and Scala binding

**JavaScript** — select strategy via `config.js-binding`:

```yaml
config:
  js-binding: bake         # embed as const __ssc_config = {...}  (default)
  js-binding: process-env  # use process.env.KEY  (Node.js)
  js-binding: runtime      # load window.__SSC_CONFIG || config.json
```

**Scala** — select strategy via `config.scala-output`:

```yaml
config:
  scala-output: embedded          # val __ssc_config: Map[String, Any] = Map(...)  (default)
  scala-output: application.conf  # write TypesafeConfig application.conf alongside .sc
  scala-output: object            # generate object AppConfig { val port: Int = 8080 }
```

### 22.6 Hot reload

In `ssc watch` mode, changes to any external config file referenced in front-matter
automatically trigger a reload — no restart needed.

### 22.7 Full example

```ssc
---
databases:
  prod:
    url:      "${env:DB_URL | jdbc:sqlite:./dev.db}"
    password: "${sops:db.password}"
config:
  server:
    port: 8080
    host: "0.0.0.0"
  files: [app.yaml]
  js-binding: bake
  priority: [blocks, files, frontmatter]
---

```yaml config "feature-flags"
dark-mode: true
beta-users: [alice, bob]
```

```scala
case class ServerConfig(port: Int, host: String) derives Config
case class Flags(darkMode: Boolean) derives Config

val srv   = config[ServerConfig]("server").fold(throw _, identity)
val flags = config[Flags]("feature-flags").fold(throw _, identity)

println(s"Serving on ${srv.host}:${srv.port}")
println(s"Dark mode: ${flags.darkMode}")
```
```

---

## Quick Reference

### CLI

```bash
ssc run file.ssc          # interpret
ssc watch file.ssc        # watch mode
ssc repl                  # REPL
ssc test file.ssc         # run tests
ssc fmt file.ssc          # format .ssc files
ssc emit-js file.ssc      # transpile to JS
ssc emit-spa file.ssc     # SPA bundle
ssc emit-wc file.ssc      # Web Components
ssc build src/            # static site / project build
ssc plugin install X      # install plugin
```

### Key Environment Variables

| Variable | Purpose |
|----------|---------|
| `SSC_SESSION_SECRET` | HMAC secret for signed cookie sessions |
| `SSC_JWT_SECRET` | JWT signing secret (falls back to session secret) |
| `SSC_STORAGE_PATH` | Default path for Storage effect JSON file |
| `SSC_NO_NETWORK` | Set to `1` to disable URL imports |

### Feature Quick-Links

- Algebraic effects: §4, `docs/architecture.md`
- Direct syntax: [docs/direct-syntax.md](direct-syntax.md)
- Coroutines + generators: [docs/coroutines.md](coroutines.md)
- DSL authoring: [docs/dsl.md](dsl.md)
- Dataset / MapReduce: [docs/mapreduce.md](mapreduce.md)
- **SQL databases + secret management: §6, §6.2, [secret-resolvers.md](../secret-resolvers.md)**
- Apache Spark: §14 above, [docs/spark-streaming.md](spark-streaming.md), [docs/spark-lakehouse.md](spark-lakehouse.md), [docs/spark-catalog.md](spark-catalog.md), [docs/spark-mllib.md](spark-mllib.md)
- Actors + cluster: [docs/actors-dist.md](actors-dist.md), [docs/cluster-management.md](cluster-management.md)
- Frontend toolkit + framework SPI: [docs/frontend-toolkit-spec.md](frontend-toolkit-spec.md), [docs/frontend-framework-spi-plan.md](frontend-framework-spi-plan.md)
- x402 micropayments + wallet SPI: [docs/x402.md](x402.md), [docs/blockchain-spi.md](blockchain-spi.md), [docs/micropayment-spi.md](micropayment-spi.md)
- MCP: [docs/mcp.md](mcp.md)
- Metaprogramming: [docs/metaprogramming.md](metaprogramming.md)
- Error handling: [docs/error-handling.md](error-handling.md)
- Backend SPI: [docs/backend-spi.md](backend-spi.md)
- Compiler plugins with intrinsics: §21 above, `examples/plugins/crypto-plugin/`
- Config system (YAML/HOCON/JSON + typed binding): §22 above, [docs/config-system.md](config-system.md)
