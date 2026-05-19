# Tutorial: Collaborative Todo API

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

See the [User Guide](user-guide.md) and [SPEC.md](../SPEC.md) for full API reference.
