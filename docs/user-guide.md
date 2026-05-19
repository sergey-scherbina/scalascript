# ScalaScript User Guide

A practical reference for ScalaScript v1.23+.

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
scripts/setup.sh

# Build the ssc launcher and helper scripts into bin/
./scripts/install.sh bin/ssc

# Or install system-wide to /usr/local/bin
scripts/install.sh
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

Run it three ways:

```bash
ssc hello.ssc           # interpreter — instant
jssc hello.ssc          # transpile to JS, run via Node
sscc hello.ssc          # compile to JVM bytecode, run via scala-cli
```

All three print `Hello, World!` with byte-identical output.

### Watch Mode

```bash
ssc watch hello.ssc     # re-runs on every file save
```

### REPL

```bash
ssc repl
> def f(x: Int) = x * 2
> f(21)
42
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

---

## 6. WebSocket

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

## 7. Actors

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

## 8. Data Processing

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

## 9. DSL Authoring

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

## 10. Module System

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

## 11. Testing

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

## Quick Reference

### CLI

```bash
ssc run file.ssc          # interpret
ssc watch file.ssc        # watch mode
ssc repl                  # REPL
ssc test file.ssc         # run tests
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
- Actors + cluster: [docs/actors-dist.md](actors-dist.md)
- MCP: [docs/mcp.md](mcp.md)
- Metaprogramming: [docs/metaprogramming.md](metaprogramming.md)
- Error handling: [docs/error-handling.md](error-handling.md)
- Backend SPI: [docs/backend-spi.md](backend-spi.md)
