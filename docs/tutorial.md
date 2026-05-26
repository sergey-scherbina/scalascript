# Tutorial 1: Collaborative Todo API

Build a real-time collaborative todo list API in ScalaScript — step by step.

The finished application has:
- A REST API for todo CRUD
- Password-based authentication with sessions
- WebSocket broadcast so all connected clients see changes instantly
- TLS for production deployment
- An MCP tool interface for LLM integration

Each step builds on the previous. All code is runnable with `ssc run todo.ssc`.

---

## Step 1: Project Setup

Create `todo.ssc`:

````ssc
---
name: todo-api
version: 1.0.0
---

# Todo API

```scalascript
println("Hello from the todo API!")
```
````

Run it:

```bash
ssc todo.ssc
# Hello from the todo API!
```

Try the JS and JVM backends to verify cross-backend compatibility:

```bash
jssc todo.ssc
sscc todo.ssc
```

---

## Step 2: Data Model

Replace the placeholder content with the data model.

````ssc
---
name: todo-api
version: 1.0.0
---

# Data Model

```scalascript
import java.time.Instant

// The core data types
case class Todo(
  id:        Int,
  title:     String,
  done:      Boolean,
  createdAt: String,
  owner:     String
)

case class User(
  username:     String,
  passwordHash: String
)

enum Priority:
  case Low, Medium, High

// API request shapes (parsed from JSON)
case class CreateTodoReq(title: String, priority: Priority = Priority.Medium)
case class UpdateTodoReq(title: Option[String], done: Option[Boolean])
```
````

### Pattern Matching on Priority

```scalascript
def priorityLabel(p: Priority): String = p match
  case Priority.Low    => "low"
  case Priority.Medium => "medium"
  case Priority.High   => "high"

println(priorityLabel(Priority.High))   // "high"
```

Run: `ssc todo.ssc` — should print "high" from our test.

---

## Step 3: In-Memory Storage with the State Effect

We'll use the `State` effect for our in-memory database. This makes it easy to
replace with a real DB later by swapping the handler.

```scalascript
// In-memory store
case class AppState(
  todos:   Map[Int, Todo],
  users:   Map[String, User],
  nextId:  Int
):
  def addTodo(todo: Todo): AppState =
    copy(todos = todos + (todo.id -> todo), nextId = nextId + 1)

  def removeTodo(id: Int): AppState =
    copy(todos = todos - id)

  def updateTodo(id: Int, f: Todo => Todo): AppState =
    copy(todos = todos.updatedWith(id)(_.map(f)))

val emptyState = AppState(Map.empty, Map.empty, 1)

// Effect operations wrap State
def createTodo(title: String, owner: String): Todo =
  val s = State.get[AppState]
  val todo = Todo(s.nextId, title, done = false,
    createdAt = java.time.Instant.now().toString, owner)
  State.set(s.addTodo(todo))
  todo

def listTodos(owner: String): List[Todo] =
  State.get[AppState].todos.values
    .filter(_.owner == owner)
    .toList
    .sortBy(_.id)

def getTodo(id: Int): Option[Todo] =
  State.get[AppState].todos.get(id)

def deleteTodo(id: Int): Boolean =
  val s = State.get[AppState]
  if s.todos.contains(id) then
    State.set(s.removeTodo(id))
    true
  else false

def markDone(id: Int, done: Boolean): Option[Todo] =
  val s = State.get[AppState]
  s.todos.get(id).map { _ =>
    State.set(s.updateTodo(id, _.copy(done = done)))
    State.get[AppState].todos(id)
  }
```

Test the state operations in isolation:

```scalascript
// Quick smoke test
val (finalState, _) = runState(emptyState) {
  val t1 = createTodo("Buy milk", "alice")
  val t2 = createTodo("Write code", "alice")
  markDone(t1.id, true)
  val todos = listTodos("alice")
  assert(todos.length == 2)
  assert(todos.find(_.id == t1.id).get.done)
  println("State tests passed!")
}
```

### Aside: Direct Syntax

The state operations above read like regular imperative code — that's
`direct[State[AppState]]` do-notation at work.  Instead of chaining
`flatMap` calls or writing `for`-comprehensions, you assign with `=`
and the compiler desugars each assignment into a monadic bind.

The same functions written explicitly with `.!` postfix bind:

```scalascript
def createTodo(title: String, owner: String): Todo =
  direct[State[AppState]] {
    val s    = State.get[AppState].!
    val todo = Todo(s.nextId, title, done = false,
      createdAt = java.time.Instant.now().toString, owner)
    State.set(s.addTodo(todo)).!
    todo
  }
```

Effect-row unions let you sequence across multiple effects in one block:

```scalascript
def auditedCreate(title: String, owner: String): Todo =
  direct[State[AppState] | Logger] {
    Logger.info(s"Creating todo for $owner").!
    val todo = State.get[AppState].!
    // ...
  }
```

See [docs/direct-syntax.md](direct-syntax.md) for the full reference.

---

## Step 4: REST API

Add routes using `route(method, path)(handler)` and a shared mutable state reference.

```scalascript
// Shared state — mutable in the interpreter session
var appState = emptyState

// Helper: run a State[AppState, A] against the shared var
def withState[A](f: => A): A =
  val (newState, result) = runState(appState)(f)
  appState = newState
  result

// --- Routes ---

route("GET", "/todos") { req =>
  req.session.get("user") match
    case None       => Response.status(401, "Not logged in")
    case Some(user) =>
      val todos = withState(listTodos(user))
      Response.json(todos)
}

route("POST", "/todos") { req =>
  req.session.get("user") match
    case None       => Response.status(401)
    case Some(user) =>
      validate {
        val title = requireString(req.json, "title")
        title
      } match
        case Left(errs) => Response.status(400, errs.map(_.message).mkString)
        case Right(title) =>
          val todo = withState(createTodo(title, user))
          Response.json(todo).withStatus(201)
}

route("GET", "/todos/:id") { req =>
  val id = req.params("id").toInt
  withState(getTodo(id)) match
    case None    => Response.notFound(s"Todo $id not found")
    case Some(t) => Response.json(t)
}

route("PATCH", "/todos/:id") { req =>
  val id = req.params("id").toInt
  val done = req.json.field("done").asBool
  withState(markDone(id, done)) match
    case None    => Response.notFound(s"Todo $id not found")
    case Some(t) => Response.json(t)
}

route("DELETE", "/todos/:id") { req =>
  val id = req.params("id").toInt
  if withState(deleteTodo(id)) then Response.status(204)
  else Response.notFound(s"Todo $id not found")
}
```

Test the API:

```bash
ssc todo.ssc &    # start server in background

curl http://localhost:8080/todos
# 401 Not logged in (not authenticated yet)
```

### Aside: File-based handlers with `mount()`

For larger APIs it helps to split each handler into its own `.ssc` file.
`mount()` evaluates a file once at startup and registers its last expression
as the handler:

```scala
// server.ssc — thin orchestrator
mount("GET",  "/todos",     "handlers/list-todos.ssc")
mount("POST", "/todos",     "handlers/create-todo.ssc")
mount("GET",  "/todos/:id", "handlers/get-todo.ssc")
serve(8080)
```

```scala
// handlers/list-todos.ssc
req =>
  req.session.get("user") match
    case None       => Response.status(401, "Not logged in")
    case Some(user) => Response.json(db.listTodos(user))
```

Each file receives the same `req: Request` (path params, query, headers,
body) as a regular `route()` handler. A second `mount()` for the same
`(method, path)` silently replaces the previous one — useful for hot-reload
patterns.

Pass shared dependencies via the `ctx` map so handler files stay stateless:

```scala
mount("GET",  "/users/:id",    "handlers/entity.ssc", Map("coll" -> "users"))
mount("GET",  "/products/:id", "handlers/entity.ssc", Map("coll" -> "products"))
```

```scala
// handlers/entity.ssc — reused for two routes
(req, ctx) =>
  val id   = req.params("id")
  val coll = ctx("coll").toString
  Response.json(db.findIn(coll, id))
```

Handler files can also use **typed handlers**: if the last expression is `CaseClass1 => CaseClass2`, the runtime automatically deserializes the input from path params, query params, or JSON body, and serializes the output to JSON 200:

```scala
// handlers/greet.ssc
case class GreetInput(name: String)
case class GreetOutput(greeting: String)

(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
```

Mounted as `mount("GET", "/greet/:name", "handlers/greet.ssc")`, a `GET /greet/alice` request automatically fills `GreetInput(name = "alice")` from the path param and returns `{"greeting": "Hello, alice!"}`.

See [`docs/mount-handlers.md`](../docs/mount-handlers.md) for the full typed handler spec.

See [`examples/mount-demo/`](../examples/mount-demo/) for a runnable example.

---

## Step 5: Authentication

Add user registration, login, and logout routes.

```scalascript
// User management helpers
def registerUser(username: String, password: String): Boolean =
  val s = State.get[AppState]
  if s.users.contains(username) then false
  else
    val user = User(username, hashPassword(password))
    State.set(s.copy(users = s.users + (username -> user)))
    true

def authenticateUser(username: String, password: String): Boolean =
  State.get[AppState].users.get(username) match
    case None       => false
    case Some(user) => verifyPassword(password, user.passwordHash)

// Auth routes
route("POST", "/register") { req =>
  validate {
    val user = requireString(req.json, "username")
    val pass = requireString(req.json, "password")
    if pass.length < 8 then Left(List(ValidationError("password too short")))
    else Right((user, pass))
  }.flatten match
    case Left(errs) => Response.status(400, errs.map(_.message).mkString)
    case Right((user, pass)) =>
      if withState(registerUser(user, pass)) then
        Response.json(Map("ok" -> true)).withStatus(201)
      else
        Response.status(409, "Username taken")
}

route("POST", "/login") { req =>
  val user = req.json.field("username").asString
  val pass = req.json.field("password").asString
  if withState(authenticateUser(user, pass)) then
    Response.json(Map("ok" -> true))
      .withSession(Map("user" -> user))
  else
    Response.status(401, "Invalid credentials")
}

route("POST", "/logout") { req =>
  Response.json(Map("ok" -> true)).clearSession()
}

route("GET", "/me") { req =>
  req.session.get("user") match
    case None    => Response.status(401)
    case Some(u) => Response.json(Map("username" -> u))
}
```

Test authentication:

```bash
# Register
curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}'

# Login (save the session cookie)
curl -c cookies.txt -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"secret123"}'

# Create a todo using the session
curl -b cookies.txt -X POST http://localhost:8080/todos \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk"}'

# List todos
curl -b cookies.txt http://localhost:8080/todos
```

---

## Step 6: WebSocket Real-Time Updates

When any todo changes, broadcast the event to all connected WebSocket clients.

```scalascript
// Track active WebSocket connections
var wsClients: List[WebSocket] = List.empty

def broadcast(event: String, data: Any): Unit =
  val msg = jsonStringify(Map("event" -> event, "data" -> data))
  wsClients = wsClients.filter { ws =>
    if ws.isClosed then false
    else
      ws.send(msg)
      true
  }

// WebSocket endpoint
onWebSocket("/ws") { ws =>
  // Add to broadcast list
  wsClients = ws :: wsClients
  println(s"Client connected: ${ws.id} (total: ${wsClients.length})")

  // Send current todos on connect (if authenticated via query param)
  ws.query.get("token").flatMap(jwtVerify) match
    case Some(claims) =>
      val user = claims("sub")
      val todos = withState(listTodos(user))
      ws.send(jsonStringify(Map("event" -> "init", "data" -> todos)))
    case None =>
      ws.send(jsonStringify(Map("event" -> "error", "data" -> "Not authenticated")))

  // Handle incoming messages (ping-pong keepalive)
  while !ws.isClosed do
    ws.recv() match
      case Some("ping") => ws.send("pong")
      case Some(msg)    => println(s"WS message from ${ws.id}: $msg")
      case None         => ()

  println(s"Client disconnected: ${ws.id}")
}
```

Now update the todo routes to broadcast after mutations:

```scalascript
// Updated POST /todos route (replace the earlier one)
route("POST", "/todos") { req =>
  req.session.get("user") match
    case None       => Response.status(401)
    case Some(user) =>
      validate {
        requireString(req.json, "title")
      } match
        case Left(errs)   => Response.status(400, errs.map(_.message).mkString)
        case Right(title) =>
          val todo = withState(createTodo(title, user))
          broadcast("todo:created", todo)           // notify WS clients
          Response.json(todo).withStatus(201)

// Updated DELETE /todos/:id (replace the earlier one)
route("DELETE", "/todos/:id") { req =>
  val id = req.params("id").toInt
  if withState(deleteTodo(id)) then
    broadcast("todo:deleted", Map("id" -> id))     // notify WS clients
    Response.status(204)
  else Response.notFound(s"Todo $id not found")
}
```

Test WebSocket in a browser console:

```javascript
const ws = new WebSocket("ws://localhost:8080/ws?token=" + jwtToken);
ws.onmessage = (e) => console.log(JSON.parse(e.data));
ws.send("ping");   // → "pong"

// In another tab: create a todo
// → {event: "todo:created", data: {id: 1, title: "Buy milk", ...}}
```

---

## Step 7: Deploy with TLS

Add TLS to serve HTTPS and WSS in production.

```scalascript
// TLS configuration (read cert paths from environment)
val certFile = getenv("TLS_CERT", "server.crt")
val keyFile  = getenv("TLS_KEY", "server.key")
val port     = getenv("PORT", "443").toInt

// Start server
if certFile.nonEmpty && java.io.File(certFile).exists() then
  val tlsCfg = tls(certFile, keyFile)
  println(s"Starting HTTPS server on port $port")
  serve(port, tls = tlsCfg)
else
  println("Warning: No TLS cert found, starting HTTP on port 8080")
  serve(8080)
```

Generate a self-signed cert for development:

```bash
openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt \
  -days 365 -nodes -subj '/CN=localhost'

TLS_CERT=server.crt TLS_KEY=server.key PORT=8443 ssc todo.ssc
```

For production, use Let's Encrypt or your cloud provider's managed TLS.

The WebSocket endpoint is now automatically available as `wss://your-host:8443/ws`.

---

## Step 8: MCP Tool Interface

Add an MCP server so LLMs can use the todo API as a tool.

```scalascript
// MCP server — exposes todo operations as LLM-callable tools
mcpServer { srv =>
  srv.tool("list_todos") { args =>
    // In a real app, extract user context from the MCP session
    val user = requireString(args, "user")
    val todos = withState(listTodos(user))
    Tool.text(todos.map(t => s"[${if t.done then "x" else " "}] ${t.id}: ${t.title}").mkString("\n"))
  }

  srv.tool("create_todo") { args =>
    val user  = requireString(args, "user")
    val title = requireString(args, "title")
    val todo  = withState(createTodo(title, user))
    broadcast("todo:created", todo)
    Tool.text(s"Created todo #${todo.id}: ${todo.title}")
  }

  srv.tool("complete_todo") { args =>
    val user = requireString(args, "user")
    val id   = requireString(args, "id").toInt
    withState(markDone(id, true)) match
      case Some(todo) =>
        broadcast("todo:updated", todo)
        Tool.text(s"Marked #${todo.id} as done: ${todo.title}")
      case None =>
        Tool.error(s"Todo #$id not found")
  }

  srv.resource("todos://summary") { _ =>
    val allTodos = withState(State.get[AppState]).todos.values.toList
    val done     = allTodos.count(_.done)
    Resource.text(
      s"Total: ${allTodos.length}, Done: $done, Pending: ${allTodos.length - done}",
      mimeType = "text/plain"
    )
  }

  srv.prompt("summarize_todos") { args =>
    val user = requireString(args, "user")
    Prompt.messages(
      Message.user(s"Please summarize the todo list for user $user and suggest what to work on next.")
    )
  }
}

// Serve MCP over stdio (for Claude Desktop) or HTTP
val mcpTransport = getenv("MCP_TRANSPORT", "stdio") match
  case "http" => Transport.Http(getenv("MCP_PORT", "3001").toInt)
  case "ws"   => Transport.Ws(getenv("MCP_PORT", "3001").toInt)
  case _      => Transport.stdio

serveMcp(mcpTransport)
```

Configure in Claude Desktop (`~/.claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "todo-api": {
      "command": "ssc",
      "args": ["run", "/path/to/todo.ssc"],
      "env": {
        "MCP_TRANSPORT": "stdio"
      }
    }
  }
}
```

Test the MCP tools from the command line:

```bash
# Run with stdio transport
echo '{"jsonrpc":"2.0","method":"tools/list","id":1}' | ssc todo.ssc

# Or connect via HTTP
MCP_TRANSPORT=http MCP_PORT=3001 ssc todo.ssc &
mcpConnect("http://localhost:3001") { client =>
  client.callTool("list_todos", Map("user" -> "alice"))
}
```

---

## Complete File

Here is the complete `todo.ssc` putting all steps together:

````ssc
---
name: todo-api
version: 1.0.0
---

# Todo API

A collaborative todo list with REST API, WebSocket real-time updates, and MCP support.

## Data Model

```scalascript
case class Todo(id: Int, title: String, done: Boolean,
                createdAt: String, owner: String)
case class User(username: String, passwordHash: String)
case class AppState(todos: Map[Int, Todo], users: Map[String, User], nextId: Int):
  def addTodo(t: Todo): AppState = copy(todos = todos + (t.id -> t), nextId = nextId + 1)
  def removeTodo(id: Int): AppState = copy(todos = todos - id)
  def updateTodo(id: Int, f: Todo => Todo): AppState =
    copy(todos = todos.updatedWith(id)(_.map(f)))

val emptyState = AppState(Map.empty, Map.empty, 1)
```

## State Operations

```scalascript
def createTodo(title: String, owner: String): Todo =
  val s = State.get[AppState]
  val todo = Todo(s.nextId, title, false, java.time.Instant.now().toString, owner)
  State.set(s.addTodo(todo)); todo

def listTodos(owner: String): List[Todo] =
  State.get[AppState].todos.values.filter(_.owner == owner).toList.sortBy(_.id)

def getTodo(id: Int): Option[Todo] = State.get[AppState].todos.get(id)

def deleteTodo(id: Int): Boolean =
  val s = State.get[AppState]
  if s.todos.contains(id) then { State.set(s.removeTodo(id)); true } else false

def markDone(id: Int, done: Boolean): Option[Todo] =
  val s = State.get[AppState]
  s.todos.get(id).map { _ =>
    State.set(s.updateTodo(id, _.copy(done = done)))
    State.get[AppState].todos(id)
  }

def registerUser(username: String, password: String): Boolean =
  val s = State.get[AppState]
  if s.users.contains(username) then false
  else { State.set(s.copy(users = s.users + (username -> User(username, hashPassword(password))))); true }

def authenticateUser(username: String, password: String): Boolean =
  State.get[AppState].users.get(username).exists(u => verifyPassword(password, u.passwordHash))
```

## Server Setup

```scalascript
var appState = emptyState
var wsClients: List[WebSocket] = List.empty

def withState[A](f: => A): A =
  val (newState, result) = runState(appState)(f)
  appState = newState; result

def broadcast(event: String, data: Any): Unit =
  val msg = jsonStringify(Map("event" -> event, "data" -> data))
  wsClients = wsClients.filter(ws => !ws.isClosed && { ws.send(msg); true })
```

## Auth Routes

```scalascript
route("POST", "/register") { req =>
  val user = req.json.field("username").asString
  val pass = req.json.field("password").asString
  if pass.length < 8 then Response.status(400, "Password too short")
  else if withState(registerUser(user, pass)) then Response.json(Map("ok" -> true)).withStatus(201)
  else Response.status(409, "Username taken")
}

route("POST", "/login") { req =>
  val user = req.json.field("username").asString
  val pass = req.json.field("password").asString
  if withState(authenticateUser(user, pass)) then
    Response.json(Map("ok" -> true)).withSession(Map("user" -> user))
  else Response.status(401, "Invalid credentials")
}

route("POST", "/logout") { req => Response.json(Map("ok" -> true)).clearSession() }
route("GET",  "/me")     { req =>
  req.session.get("user") match
    case None    => Response.status(401)
    case Some(u) => Response.json(Map("username" -> u))
}
```

## Todo Routes

```scalascript
def requireAuth(req: Request)(f: String => Response): Response =
  req.session.get("user") match
    case None    => Response.status(401, "Not logged in")
    case Some(u) => f(u)

route("GET",    "/todos")     { req => requireAuth(req) { user =>
  Response.json(withState(listTodos(user))) }}

route("POST",   "/todos")     { req => requireAuth(req) { user =>
  val title = req.json.field("title").asString
  if title.isEmpty then Response.status(400, "Title required")
  else
    val todo = withState(createTodo(title, user))
    broadcast("todo:created", todo)
    Response.json(todo).withStatus(201)
}}

route("GET",    "/todos/:id") { req =>
  withState(getTodo(req.params("id").toInt)) match
    case None    => Response.notFound("Not found")
    case Some(t) => Response.json(t)
}

route("PATCH",  "/todos/:id") { req => requireAuth(req) { _ =>
  val id = req.params("id").toInt
  val done = req.json.field("done").asBool
  withState(markDone(id, done)) match
    case None    => Response.notFound("Not found")
    case Some(t) => { broadcast("todo:updated", t); Response.json(t) }
}}

route("DELETE", "/todos/:id") { req => requireAuth(req) { _ =>
  val id = req.params("id").toInt
  if withState(deleteTodo(id)) then { broadcast("todo:deleted", Map("id" -> id)); Response.status(204) }
  else Response.notFound("Not found")
}}
```

## WebSocket

```scalascript
onWebSocket("/ws") { ws =>
  wsClients = ws :: wsClients
  while !ws.isClosed do
    ws.recv() match
      case Some("ping") => ws.send("pong")
      case _            => ()
}
```

## MCP

```scalascript
mcpServer { srv =>
  srv.tool("list_todos") { args =>
    val todos = withState(listTodos(requireString(args, "user")))
    Tool.text(todos.map(t => s"[${if t.done then "x" else " "}] ${t.id}: ${t.title}").mkString("\n"))
  }
  srv.tool("create_todo") { args =>
    val todo = withState(createTodo(requireString(args, "title"), requireString(args, "user")))
    broadcast("todo:created", todo)
    Tool.text(s"Created #${todo.id}: ${todo.title}")
  }
  srv.tool("complete_todo") { args =>
    withState(markDone(requireString(args, "id").toInt, true)) match
      case Some(t) => { broadcast("todo:updated", t); Tool.text(s"Done: ${t.title}") }
      case None    => Tool.error("Not found")
  }
}
```

## Start

```scalascript
val port = getenv("PORT", "8080").toInt
val certFile = getenv("TLS_CERT", "")

if certFile.nonEmpty then
  serve(port, tls = tls(certFile, getenv("TLS_KEY", "server.key")))
else
  serve(port)
```
````

---

## What's Next

- Add TOTP 2FA with `totpGenerateSecret` / `totpVerify` (see §7.13 in SPEC.md)
- Replace in-memory storage with a file-backed `Storage` effect for persistence
- Add OAuth2 login via GitHub or Google (`oauthAuthorizeUrl` / `oauthExchangeCode`)
- Use `Dataset.fromFile` + `runParallel` for bulk import of existing todos
- Write a DSL parser for a natural-language todo syntax ("every day at 9am remind me to...")
- Deploy with `ssc build` to generate a static dashboard alongside the API

---

# Tutorial 2: ETL pipeline with Apache Spark

Build an end-to-end ETL pipeline on the Spark backend — case-class
`Dataset[T]`, `sql` blocks, `@SqlFn` UDFs, Delta Lake output, and a
`@TempView` bridge. The finished pipeline:

- Reads JSON input as a typed `Dataset[RawEvent]`
- Cleans + enriches via a `@SqlFn` UDF
- Aggregates with a `sql` block
- Writes the result as Delta and reads it back

All code runs with `bin/ssc-spark events.ssc` — Spark 4 JARs are resolved
by Coursier on first launch.

---

## Step 1: Project setup

Create `events.ssc`:

````ssc
---
name: events-etl
backend: spark
spark-version: 4.0.0
spark-master: local[*]
spark-app-name: events-etl
---

# Events ETL

```scalascript
println("Spark backend up.")
spark.sparkContext.setLogLevel("WARN")
```
````

Run it:

```bash
bin/ssc-spark events.ssc
```

The first launch downloads Spark 4. Look for the heading line, the
`Spark backend up.` print, and a clean exit.

---

## Step 2: Typed input — `Dataset[RawEvent]`

Replace the body with a small inline dataset (in production this would
come from `Dataset.fromJsonAs[RawEvent](...)` or `fromParquetAs`).

````ssc
# Input

```scalascript
case class RawEvent(userId: Int, kind: String, ts: Long, payload: Option[String])

val raw = Dataset.fromList(List(
  RawEvent(1, "login",  1700000000L, None),
  RawEvent(1, "view",   1700000060L, Some("page=/home")),
  RawEvent(2, "login",  1700000120L, None),
  RawEvent(2, "buy",    1700000200L, Some("sku=A-42;qty=3")),
  RawEvent(1, "logout", 1700000300L, None)
))

raw.printSchema()
raw.show(false)
```
````

The Phase E encoder shim derives the schema directly from the case class
(no `import spark.implicits._`, no `TypeTag`).

---

## Step 3: A `@SqlFn` UDF

Extract `qty=N` from the free-form `payload` and expose it as a Spark
UDF callable from SQL.

````ssc
# UDF

```scalascript
@SqlFn
def extractQty(payload: String): Int =
  if payload == null then 0
  else
    val parts = payload.split(";").toList
    parts.find(_.startsWith("qty=")).map(_.drop(4).toInt).getOrElse(0)
```
````

`extractQty` is registered on the session catalog before the first
`sql` block runs.

---

## Step 4: `@TempView` + aggregation

Register `raw` as a temp view so SQL can reference it, then aggregate
purchase quantities per user.

````ssc
# Aggregate

```scalascript
@TempView("events")
val events = raw    // same Dataset, now also visible to SQL as `events`
```

## Per-user purchases

```sql
SELECT
  userId,
  SUM(extractQty(payload)) AS total_qty
FROM events
WHERE kind = 'buy'
GROUP BY userId
ORDER BY userId
```

```scalascript
PerUserPurchases.sql.show()
```
````

The section heading `## Per-user purchases` produces a `PerUserPurchases.sql`
alias (a `DataFrame` lazy val) — friendlier than the always-emitted
`_sqlBlock_0`.

---

## Step 5: Delta Lake output

Write the aggregate to a Delta table and read it back. The codegen
detects `.format("delta")` and auto-emits the Delta dep + extension /
catalog configs.

````ssc
# Persist

```scalascript
PerUserPurchases.sql
  .write
  .format("delta")
  .mode("overwrite")
  .save("/tmp/events-delta")

val back = spark.read.format("delta").load("/tmp/events-delta")
back.show()

println(s"rows persisted: ${back.count()}")
```
````

Run again:

```bash
bin/ssc-spark events.ssc
```

You should see the schema, the inline events, the per-user aggregate,
and the round-tripped Delta read. The pipeline is now Coursier-resolved,
Scala-3-native, encoder-derived, UDF-bridged, SQL-aggregated, and
Delta-persisted — all from a single `.ssc` file.

---

## What's Next

- Replace `Dataset.fromList` with `Dataset.fromJsonAs[RawEvent]("s3://...")` for real input.
- Switch from `mode("overwrite")` to `mode("append")` + a Delta `MERGE INTO` for incremental loads.
- Set `spark-hive-metastore:` in front-matter to register the output as a managed table — see User Guide §13.8.
- Move to Structured Streaming with `spark.readStream.format("kafka")` — see [`docs/spark-streaming.md`](spark-streaming.md).
- Fit a classification pipeline on the aggregated features — see [`docs/spark-mllib.md`](spark-mllib.md).
- For non-local clusters, swap `bin/ssc-spark` for `ssc submit ... --spark-master spark://...` (fat JAR via `spark-submit`).

See the [User Guide](user-guide.md) and [SPEC.md](../SPEC.md) for full API reference.

---

# Tutorial 3: Frontend Toolkit demo — Scala API

Build a small but real SPA through the high-level Frontend Toolkit
(`Tk` facade) accessed from the sbt API, compile it to React / Vue /
Solid / Custom, and serve it via the bundled scalascript HTTP server.
No JS, no Python, no Node.  Everything end-to-end in ScalaScript / Scala 3.

> **Tip:** For a simpler scripting-first approach that doesn't require sbt,
> see [Tutorial 4](tutorial.md#tutorial-4-full-stack-ssc--sqlite-todo-app-with-reactive-ui)
> and [User Guide §17](user-guide.md#17-frontend-toolkit--stdui).

The finished demo has:
- A heading + paragraph
- A signup card with a name field + an accept-terms checkbox + a submit button + a status badge
- A "Status" alert
- A loading indicator strip (spinner + badge)
- Reactive signals on every input
- Static-HTML SSR rendering for SEO / snapshot tests

You'll see the same toolkit tree compile to four different
framework-idiomatic JS bundles, plus a static HTML version.

---

## Step 1: Already shipped

The demo lives in the `frontend-examples` sbt module — no new
sources needed for this walkthrough:

- IR: [`frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala`](../frontend-examples/src/main/scala/scalascript/frontend/examples/ToolkitDemo.scala)
- Cross-backend test: [`frontend-examples/src/test/scala/scalascript/frontend/examples/ToolkitCrossBackendTest.scala`](../frontend-examples/src/test/scala/scalascript/frontend/examples/ToolkitCrossBackendTest.scala)

Browse `ToolkitDemo.scala` to see how the entire UI is built through
`Tk.vstack`, `Tk.card`, `Tk.textField`, etc. — there is no raw `View`
construction in user code.

## Step 2: Compile

```bash
sbt frontendToolkit/compile
sbt frontendExamples/compile
```

You can also run the test suites to verify everything is wired up:

```bash
sbt frontendToolkit/test     # 217 tests across 8 suites
sbt frontendExamples/test    # 41 tests including the cross-backend
                             # ToolkitCrossBackendTest (10 cases)
```

## Step 3: Generate static bundles

The `EmitAll` runner lowers every (demo × backend) pair to an
HTML + JS bundle:

```bash
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"
```

After it finishes, you should see:

```
target/frontend-examples/
  counter/      custom/ react/ solid/ vue/
  show-hide/    custom/ react/ solid/ vue/
  todo/         custom/ react/ solid/ vue/
  toolkit-demo/ custom/ react/ solid/ vue/      # ← the new demo
```

Each leaf directory has an `index.html` + `app.js` pair ready to
serve.  Total: 16 demo × backend pairs.

Pick an explicit out-dir if you want:

```bash
sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll /tmp/ssc-spa"
```

## Step 4: Serve via `ssc serve`

The Vue / Solid / Custom backends emit ES modules, so they can't be
opened via `file://` — modules require an HTTP origin.  The
scalascript CLI ships its own static file server:

```bash
# Serve the React build
ssc serve 8000 target/frontend-examples/toolkit-demo/react

# Then open http://localhost:8000/ in any browser.
```

Same pattern for the other three backends:

```bash
ssc serve 8000 target/frontend-examples/toolkit-demo/vue
ssc serve 8000 target/frontend-examples/toolkit-demo/solid
ssc serve 8000 target/frontend-examples/toolkit-demo/custom
```

`ssc serve [port] [dir]` is built-in — no Python, no Node, no extra
install.  Defaults: port `8080`, dir `.`.

## Step 5: Try every backend

Pick three browser tabs at:
- `http://localhost:8000/` against `…/toolkit-demo/react`
- `http://localhost:8000/` against `…/toolkit-demo/solid`
- `http://localhost:8000/` against `…/toolkit-demo/vue`

You should see identical UIs — same heading, same card, same
buttons.  Type into the name field and tick the checkbox: under
React / Solid / Vue the inputs are fully reactive (the underlying
signal is wired to `useState` / `createSignal` / `ref(...)`).

The **Custom** backend currently renders the toolkit demo as
**static** HTML (it shows the layout, but typing into the input
doesn't update the signal yet — the Custom emitter's "lambda → JS"
translation is Phase D follow-up work; the framework backends route
JVM closures through native JS render paths and don't have this
limitation).

## Step 6: Render to static HTML via SSR

For SEO, static-site generation, email templates, or snapshot
tests, the toolkit ships a pure `View → HTML` stringifier with no
JS runtime:

```scala
import scalascript.frontend.toolkit.{Tk, Ssr, Theme}

val tree = Tk.vstack(gap = 16)(
  Tk.heading(1, "Static page"),
  Tk.text("Rendered without any JS runtime.")
)

// Just the body:
val html = Ssr.renderToHtml(tree, Theme.default)

// Or a full HTML5 document with theme-coloured body:
val doc = Ssr.renderDocument(tree, title = "Demo", theme = Theme.default)
```

`Ssr.renderDocument` produces a `<!DOCTYPE html>` shell complete
with a viewport meta, a theme-coloured body, and a `<title>` —
ready to drop into a static-site pipeline.

---

## What's Next

- Build your own widget on top of `ToolkitNode` + your own
  `Toolkit.lower` `case` — the toolkit's `trait ToolkitNode` is
  open precisely so widget packs can live in separate modules.
- Wire a form with validators — see
  [`frontend-toolkit/src/main/scala/scalascript/frontend/toolkit/Form.scala`](../frontend-toolkit/src/main/scala/scalascript/frontend/toolkit/Form.scala)
  and `Tk.form { ctx => ... }` + `Validators.email` /
  `Validators.minLength(8)` / `Validators.and(...)`.
- Add routing with `Tk.router(currentPath, notFound)(routes*)` and
  `Tk.route("/users/:id") { params => ... }`.
- Use the `Table[T]` widget for sortable typed data — see
  [`Table.scala`](../frontend-toolkit/src/main/scala/scalascript/frontend/toolkit/Table.scala)
  and `Tk.sortableColumn`.
- Browse the full widget catalog in
  [`docs/frontend-toolkit-spec.md`](frontend-toolkit-spec.md).

---

# Tutorial 4: Full-stack .ssc — SQLite todo app with reactive UI

Build a self-contained full-stack app in a single `.ssc` file: SQLite database,
REST API, and a reactive browser UI — all wired together without leaving
ScalaScript.

The finished app:
- Persists todos to a SQLite file (survives restarts)
- Exposes a JSON REST API (`GET / POST / DELETE /api/todos`)
- Renders a reactive SPA using `std/ui` widgets
- Runs with a single `ssc serve` command

---

## Step 1: Front-matter and database

Create `todos.ssc`:

````ssc
#!/usr/bin/env ssc
---
name: todos
version: 1.0.0
frontend: react
databases:
  default:
    url: "jdbc:sqlite:./todos.db"
---

# Todos
````

The `databases:` block declares a named SQLite connection.  The file
`todos.db` is created on first run.  `frontend: react` tells `ssc` to
emit the UI as a React bundle.

---

## Step 2: Create the table

A `sql` fenced block runs DDL against the `default` connection at startup:

````ssc
## Database setup

```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```
````

Running `ssc todos.ssc` after this step creates the table (no error if it
already exists).

**Tip — atomic multi-statement changes with `transaction`:**

When you need multiple SQL statements to succeed or fail together, use a
` ```transaction ``` ` block instead of separate ` ```sql ``` ` blocks:

````ssc
```transaction
INSERT INTO audit_log (event, ts) VALUES ('todo_created', ${now});
INSERT INTO todos (text) VALUES (${text})
```
````

Both inserts run in one JDBC transaction — if the second fails, the first
is rolled back automatically.  Statements are separated by `;`; `${expr}`
bind parameters work exactly like in regular `sql` blocks.

**Alternative — document-shaped server data:**

For JSON/document-shaped records where you do not need SQL joins, JVM apps can
store typed values in the JDBC-backed server object store:

````ssc
```scala
import scalascript.typeddata.{ObjectCodec, key}

case class Draft(@key id: String, title: String, done: Boolean = false) derives ObjectCodec

ObjectStore.put("default", "drafts", Draft("d1", "Plan"))
val draft = ObjectStore.get[Draft]("default", "drafts", "d1")
```
````

Use SQL tables for relational queries and constraints; use `ObjectStore` for
document-style server state that will later sync with client IndexedDB.

If the same app should expose server sync endpoints, declare the store in
front-matter:

```yaml
objectStores:
  drafts:
    type: Draft
    sync: client-server
    database: default
    key: id
```

The JVM backend then generates `GET /__ssc/sync/drafts/changes` and
`POST /__ssc/sync/drafts/push`. Browser/Electron clients can call:

```scalascript
awaitClient(Sync.push[Draft]("drafts", "app"))
awaitClient(Sync.pull[Draft]("drafts", "app"))
```

The first helper syncs current local entries and applies server changes.
Durable offline mutation queues and richer conflict handling remain planned.

---

## Step 3: REST API

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
```

Test the API directly:

```bash
ssc todos.ssc &

curl http://localhost:8080/api/todos          # []
curl -X POST http://localhost:8080/api/todos --data 'Buy milk'
curl http://localhost:8080/api/todos          # [{"id":1,"text":"Buy milk"}]
```

---

## Step 4: Reactive UI via `std/ui`

Import the widget library and create reactive signals:

````ssc
## Imports and signals

[signal, serve, fetchUrlSignal, fetchAction, fetchActionClear, incSignal](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[vstack, hstack, divider, spacer](std/ui/layout.ssc)
[heading, text](std/ui/typography.ssc)
[textField, actionButton](std/ui/input.ssc)
[showWhen, fragment_](std/ui/reactive.ssc)
[badge, fetchTable](std/ui/display.ssc)

```scalascript
val refreshTick = signal[Int]("refreshTick", 0)
val newItem     = signal[String]("newItem",   "")
```
````

`fetchTable` renders the todo list, fetching from `/api/todos` and
re-fetching whenever `refreshTick` increments.  `fetchActionClear` posts
to `/api/todos`, then clears `newItem` and bumps `refreshTick`:

````ssc
## Todo panel

```scalascript
val todosTable = fetchTable("/api/todos", "/api/todos/delete", refreshTick)

val todoPanel = vstack(gap = 12)(
  heading(2, "Todos"),
  todosTable,
  textField(value = newItem, label = "New item"),
  actionButton(fetchActionClear("POST", "/api/todos", newItem, refreshTick), "Add")
)
```
````

---

## Step 5: Serve the UI

`lower` converts the widget tree to the React View IR; `serve` starts the
HTTP server and also serves the compiled React bundle at `/`:

````ssc
## Serve

```scalascript
serve(lower(todoPanel, defaultTheme), 8080)
```
````

```bash
ssc serve todos.ssc   # hot-reloads the route table on every save
# open http://localhost:8080/
```

Type a todo, click **Add** — the item appears instantly.  Stop and restart
`ssc`; the items are still there (SQLite file persisted on disk).

---

## Step 6: Secure the database password

If you switch to PostgreSQL, keep the password out of the file:

```yaml
databases:
  default:
    url:      "jdbc:postgresql://db:5432/todos"
    user:     "${env:DB_USER}"
    password: "${env:DB_PASSWORD}"
```

Or use sops for encrypted secrets:

```yaml
    password: "${sops:databases.todos.password}"
```

```bash
sops -d secrets.enc.yaml | ssc serve todos.ssc
```

The decrypted YAML is read from stdin, flattened to dotted keys, and
resolved when the connection opens.  See [User Guide §6.2](user-guide.md#62-secret-management)
and [`secret-resolvers.md`](../secret-resolvers.md) for more secret
backends (Vault, AWS SM, Doppler, 1Password).

---

## Complete file

````ssc
#!/usr/bin/env ssc
---
name: todos
version: 1.0.0
frontend: react
databases:
  default:
    url: "jdbc:sqlite:./todos.db"
---

# Todos

## Database setup

```sql
CREATE TABLE IF NOT EXISTS todos (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT    NOT NULL
)
```

## REST API

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
```

## Imports and signals

[signal, serve, fetchUrlSignal, fetchAction, fetchActionClear, incSignal](std/ui/primitives.ssc)

[lower](std/ui/lower.ssc)

[defaultTheme](std/ui/theme.ssc)

[vstack, hstack, divider](std/ui/layout.ssc)

[heading](std/ui/typography.ssc)

[textField, actionButton](std/ui/input.ssc)

[fetchTable](std/ui/display.ssc)

```scalascript
val refreshTick = signal[Int]("refreshTick", 0)
val newItem     = signal[String]("newItem",   "")
```

## UI

```scalascript
val todosTable = fetchTable("/api/todos", "/api/todos/delete", refreshTick)

val tree = vstack(gap = 16)(
  heading(1, "Todos"),
  divider(),
  todosTable,
  textField(value = newItem, label = "New item"),
  actionButton(fetchActionClear("POST", "/api/todos", newItem, refreshTick), "Add")
)
```

## Serve

```scalascript
serve(lower(tree, defaultTheme), 8080)
```
````

---

## What's Next

- Add authentication — a `route("POST", "/login")` route that sets a session
  cookie, then guard the REST routes with `req.session.get("user")`.
- Switch from `Response.text(body)` to `Response.json(rows)` and use
  `fetchTable` with typed columns.
- Add PostgreSQL + sops-encrypted credentials for a production deploy.
- Replace the hand-crafted JSON string with `jsonStringify` from `std/json`.
- Add a search box: `signal[String]("filter", "")`, pass it to
  `fetchUrlSignal("/api/todos?q=...", refreshTick)`, and filter server-side.
