# REPL Web-Aware Mode + `mount()` intrinsic

**Status:** spec — not yet implemented  
**Milestone:** v1.30  
**Spec:** this document  

---

## Goals

Two orthogonal but related additions:

1. **REPL web mode** — `ssc repl` gains HTTP commands: mount handlers, start/stop
   a background server, inspect the route table, fire test requests.
2. **`mount()` language intrinsic** — the same file-mounting mechanic is available
   in any `.ssc` program, not just the REPL. The REPL commands are a thin shell
   over the same underlying operation.

Both use the same `Routes` infrastructure and the same handler-file contract,
so anything you learn in the REPL translates directly to code and vice versa.

## Non-goals

- Debugger integration in the REPL (`:debug on`) — separate feature.
- Tab-completion / readline history — separate feature.
- Prefix-mounting of a whole directory (`/api-prefix` + directory) — deferred.
- Hot-file-watch inside the REPL (use `ssc watch` for that).

---

## Route table changes (`Routes`)

The backing store changes from `ArrayBuffer[Entry]` to
`LinkedHashMap[(String, String), Entry]` where the key is `(method, rawPath)`,
e.g. `("GET", "/users/:id")`.

**Consequences:**

- `Routes.register` automatically **replaces** an existing entry for the same
  method + path — no duplicates possible by construction.
- `mount()` and `:mount` are idempotent: repeating with the same method + path
  updates the handler in place.
- Iteration order for `:routes` is stable (insertion order).
- Matching still iterates entries and pattern-matches path segments because
  parameterised paths cannot be resolved by direct map lookup.

### `Routes.Entry` additions

```scala
final case class Entry(
  method:      String,
  path:        String,
  pathPattern: List[Segment],
  handler:     Value,
  interpreter: Interpreter,
  source:      Option[String] = None,   // NEW: canonical abs path of handler file
  mountCtx:    Map[String, Value] = Map.empty,  // NEW: extra context from mount()
)
```

- `source` — `None` for `route()`-registered handlers (existing behaviour);
  `Some(absPath)` for handlers from `:mount file.ssc` / `mount("M","/p","f.ssc")`.
  Used by `:reload` and `:load` to remove a file's previous routes before
  re-running.
- `mountCtx` — extra key-value context supplied at mount time (see below).
  Empty for inline handlers and `route()` registrations.

---

## `mount()` — language intrinsic

Available in any `.ssc` program alongside `route()` and `serve()`.

```scala
// Basic form
mount("GET", "/ping", "handlers/ping.ssc")

// With extra context passed to the handler
mount("GET", "/users/:id", "handlers/get-entity.ssc",
  Map("collection" -> "users", "db" -> "main"))

mount("GET",    "/products/:id", "handlers/get-entity.ssc",
  Map("collection" -> "products"))

serve(8080)
```

### Signature

```scala
mount(method: String, path: String, file: String): Unit
mount(method: String, path: String, file: String, ctx: Map[String, Any]): Unit
```

### Semantics

1. `file` is resolved relative to the directory of the calling `.ssc` file
   (same resolution as `import` links). In the REPL, relative to CWD.
2. The file is read from disk and evaluated **once** at call time.
3. The last expression of the file is taken as the handler. It must be one of:
   - `Request => Response` — called with the request on every incoming hit.
   - `(Request, Map[String, Any]) => Response` — called with request + the
     `ctx` map from `mount()`. If no `ctx` was passed, an empty map is used.
   - A bare `Response` value — automatically wrapped as `_ => <response>`
     (useful for static or fixed responses).
4. `source` and `mountCtx` are stored in the `Routes.Entry`.

### Context in handler files

When `mount()` is called with a `ctx` map, the handler file can declare a
two-argument function to receive it:

```scala
// handlers/get-entity.ssc
(req, ctx) => {
  val collection = ctx("collection").toString
  val id         = req.params("id")
  // query the right collection…
  Response.json(s"""{"collection":"$collection","id":"$id"}""")
}
```

If the file only declares `req =>`, the context is silently ignored — backward
compatible.

### Full example

```scala
# API Server

```scala
// server.ssc
mount("GET",    "/ping",          "handlers/ping.ssc")
mount("GET",    "/hello/:name",   "handlers/hello.ssc")
mount("POST",   "/hello",         "handlers/hello-post.ssc")
mount("GET",    "/users/:id",     "handlers/entity.ssc", Map("coll" -> "users"))
mount("GET",    "/products/:id",  "handlers/entity.ssc", Map("coll" -> "products"))
serve(8080)
```
```

```scala
// handlers/ping.ssc
Response.text("pong")
```

```scala
// handlers/hello.ssc  — GET /hello/:name
req => {
  val name = req.params("name")
  Response.text(s"Hello, $name!")
}
```

```scala
// handlers/hello-post.ssc  — POST /hello  body: {"name":"alice"}
req => {
  val name = req.json match
    case Some(data) => data("name").toString
    case None       => "stranger"
  Response.json(s"""{"greeting":"Hello, $name!"}""")
}
```

```scala
// handlers/entity.ssc  — reused for /users/:id and /products/:id
(req, ctx) => {
  val coll = ctx("coll").toString
  val id   = req.params("id")
  Response.json(s"""{"collection":"$coll","id":"$id"}""")
}
```

---

## REPL commands

All commands start with `:`. Unknown commands print a short error; `:help`
lists all of them.

### `:serve [port]`

Starts an HTTP server in a background virtual thread (non-blocking).
Default port: `8080`.

```
ssc> :serve
Listening on :8080
ssc> :serve 9000
Listening on :9000
```

- Returns immediately; the REPL prompt stays interactive.
- Routes registered **before or after** `:serve` are visible to the server
  immediately — the server reads `Routes` on every incoming request.
- If a server is already running: `Already serving on :<port>.`

### `:stop [--keep-routes]`

Stops the running HTTP server.

```
ssc> :stop
Server stopped. Routes cleared.

ssc> :stop --keep-routes
Server stopped. Routes kept (8 routes).
```

Default: clears all routes.  
`--keep-routes`: stops the server but leaves `Routes` intact so you can
`:serve` again on a different port with the same routes.

### `:clear`

Clears all registered routes **without** stopping the server. Useful for
resetting the route table while the server keeps listening.

```
ssc> :clear
Routes cleared (was: 5 routes).
```

If a server is running and all routes are cleared, it returns 404 for
every request until new routes are registered.

### `:mount METHOD /path { handler }`

Registers an inline handler. Replaces any existing handler for the same
method + path.

```
ssc> :mount GET /ping { _ => Response.text("pong") }
Mounted: GET /ping

ssc> :mount GET /hello/:name { req =>
   |   val name = req.params("name")
   |   Response.text(s"Hello, $name!")
   | }
Mounted: GET /hello/:name
```

### `:mount METHOD /path functionName`

Mounts a function already defined in the REPL's global environment.

```
ssc> def greet(req: Request): Response =
   |   Response.text(s"Hi ${req.params("name")}!")
   |
ssc> :mount GET /hi/:name greet
Mounted: GET /hi/:name  (greet)
```

### `:mount METHOD /path file.ssc [key=value ...]`

Runs `file.ssc` once, takes its last expression as the handler, registers
it. Optional `key=value` pairs become the `ctx` map passed to the handler.

```
ssc> :mount GET /ping handlers/ping.ssc
Mounted: GET /ping  (handlers/ping.ssc)

ssc> :mount GET /hello/:name handlers/hello.ssc
Mounted: GET /hello/:name  (handlers/hello.ssc)

ssc> :mount GET /users/:id handlers/entity.ssc coll=users
Mounted: GET /users/:id  (handlers/entity.ssc, ctx: {coll=users})

ssc> :mount GET /products/:id handlers/entity.ssc coll=products
Mounted: GET /products/:id  (handlers/entity.ssc, ctx: {coll=products})
```

Same file can be mounted on multiple paths with different contexts.

### `:load file.ssc`

Runs `file.ssc` as a normal ScalaScript program. Any `route(...)` calls
inside the file register routes in the usual way.

Before running, all routes with `source == Some(canonicalPath)` are removed
so a repeated `:load` performs a clean hot-reload (including routes that
were deleted from the file).

```
ssc> :load api/users.ssc
Loaded api/users.ssc:
  GET    /users
  POST   /users
  GET    /users/:id
```

Difference from `:mount … file.ssc`:

| | `:load file.ssc` | `:mount METHOD /path file.ssc` |
|--|--|--|
| File contains | `route(...)` calls | last expr = handler |
| Path defined | inside the file | in the REPL command |
| Same file, multiple paths | no | yes (with different `ctx`) |

### `:reload file.ssc`

Re-runs the file without repeating the method and path.

- File was `:load`-ed: same as `:load file.ssc`.
- File was `:mount`-ed: re-reads from disk, re-evaluates, replaces the
  handler — method, path, and `ctx` unchanged.
  The `Routes.Entry` carries `source` + `mountCtx`, so `:reload` has
  everything it needs.

```
ssc> :reload handlers/hello.ssc
Reloaded: GET /hello/:name  (handlers/hello.ssc)

ssc> :reload api/users.ssc
Reloaded api/users.ssc:
  GET    /users
  POST   /users  (updated)
  GET    /users/:id
  DELETE /users/:id  (new)
```

If the file is unknown: `Unknown file: hello.ssc — use :mount or :load first.`

### `:unmount METHOD /path`

Removes a specific route.

```
ssc> :unmount GET /ping
Unmounted: GET /ping
```

### `:routes`

Lists all registered routes with method, path, and source.

```
ssc> :routes
  GET    /ping           <inline>
  GET    /hello/:name    handlers/hello.ssc
  POST   /hello          handlers/hello-post.ssc
  GET    /users/:id      handlers/entity.ssc  {coll=users}
  GET    /products/:id   handlers/entity.ssc  {coll=products}
  GET    /users          api/users.ssc
  POST   /users          api/users.ssc
```

### `:http METHOD /path [body] [-H "Name: Value" ...]`

Sends a real HTTP/1.1 request to `localhost:<port>` and prints the response.
Requires a server started with `:serve`.

```
ssc> :http GET /hello/alice
→ 200 OK  text/plain
Hello, alice!

ssc> :http POST /hello {"name":"bob"}
→ 200 OK  application/json
{"greeting": "Hello, bob!"}

ssc> :http GET /users -H "Authorization: Bearer tok123"
→ 200 OK  application/json
["alice","bob"]

ssc> :http GET /hello?name=carol
→ 200 OK  text/plain
Hello, carol!
```

Multiple `-H` flags are allowed. Query string is appended directly to the path.

If no server is running: `No server running. Use :serve [port] first.`

### `:call METHOD /path [body] [-H "Name: Value" ...]`

In-process request: dispatches through `Routes.matchRequest` directly, no
network connection. Works without `:serve` — useful for testing handlers in
isolation.

```
ssc> :call GET /ping
→ 200 OK
pong

ssc> :call GET /users/42
→ 200 OK
{"collection":"users","id":"42"}

ssc> :call GET /hello?name=dave
→ 200 OK
Hello, dave!

ssc> :call POST /hello {"name":"eve"}
→ 200 OK
{"greeting":"Hello, eve!"}

ssc> :call GET /protected -H "Authorization: Bearer tok"
→ 200 OK
{"user":"alice"}
```

`req.body` = body argument; `req.query` parsed from `?` suffix;
`req.headers` from `-H` flags.

---

## Handler file contract

A handler file (used by `:mount METHOD /path file.ssc` or `mount()` in code)
must end with one of these forms:

| Last expression | Interpretation |
|---|---|
| `Request => Response` | used directly as the handler |
| `(Request, Map[String, Value]) => Response` | handler with mount context |
| Typed handler (see §Typed handlers) | auto-deser/ser wrapper applied |
| `Response` | auto-wrapped as `_ => <response>` |

The `req` argument gives access to:

| Field | Type | Description |
|---|---|---|
| `req.method` | `String` | `"GET"`, `"POST"`, … |
| `req.path` | `String` | `/hello/alice` |
| `req.params` | `Map[String,String]` | path params (`:name` segments) |
| `req.query` | `Map[String,String]` | parsed query string |
| `req.headers` | `Map[String,String]` | request headers |
| `req.body` | `Option[String]` | raw body |
| `req.json` | `Option[Map[String,Value]]` | body parsed as JSON |

---

## `mount()` as a language intrinsic

`mount()` is available as a regular function in any `.ssc` program, not
just in the REPL.  It has exactly the same semantics as `:mount METHOD /path
file.ssc` but can be called from code, enabling server programs to compose
routes from separate handler files:

```scala
// server.ssc
mount("GET",  "/ping",        "handlers/ping.ssc")
mount("GET",  "/hello/:name", "handlers/hello.ssc")
mount("POST", "/users",       "handlers/create-user.ssc", Map("db" -> db))

serve(8080)
```

**Signature:**

```scala
def mount(
  method:       String,
  path:         String,
  handlerFile:  String,
  ctx:          Map[String, Value] = Map.empty,
  errorDetails: Boolean = true,    // see §Error mode
): Unit
```

**Semantics:**

- `handlerFile` is resolved relative to the calling file's directory (same
  as `import`).
- The file is evaluated **once** at call time — not per request.
- The resulting handler is registered in the shared `Routes` table, replacing
  any existing entry for the same `(method, path)` key.
- `ctx` is passed as the second argument to handlers declared as
  `(Request, Map[String, Value]) => Response` or `(Input, Map[String, Value]) => Output`.
  Useful for threading config, database handles, or other dependencies into
  handler files without global state.

**REPL equivalence:**

```
ssc> :mount GET /ping handlers/ping.ssc
```

is identical to calling `mount("GET", "/ping", "handlers/ping.ssc")` in a
running script.

---

## Typed handlers

A handler file may end with a **typed handler** — any function or lambda
whose parameters are not `Request`. The runtime detects the shape of the last
expression at mount time and wraps it automatically. Named functions and
lambdas follow identical rules.

### Input types

Three kinds of input type are supported:

**Case class / named tuple** — fields bound by name:

```scala
case class GreetInput(name: String, age: Int)
(name: String, age: Int)   // named tuple — identical binding
```

**Unnamed tuple** — fields bound positionally as `_1`, `_2`, …:

```scala
(String, Int)   // _1 = String, _2 = Int
```

**Multi-parameter function** — equivalent to the product of all
non-special parameters' fields (see §Special trailing parameters):

```scala
(name: String, age: Int) => ...
// same binding as case class Foo(name: String, age: Int)

(user: UserInput, addr: AddressInput) => ...
// product: all fields of UserInput + all fields of AddressInput,
// deserialized from the same path/query/body sources
```

Field name collision in a product → mount-time error.

### Deserialization — named binding

For case classes, named tuples, and individual named parameters, field
values are filled in this priority order (first match wins):

1. **Path params** — `:name` segment in the mounted path.
2. **Query params** — `?name=alice` in the URL.
3. **JSON body** — field with the same name in a JSON object body.

### Deserialization — positional binding (unnamed tuples)

For unnamed tuples, values are collected from all sources in order:

```
path params (URL segment order)
  → query params
    → JSON body (array elements, or object values in declaration order)
```

Then `_1` ← first collected value, `_2` ← second, and so on, with
type coercion (`"42"` → `Int`, `"true"` → `Boolean`).

**Count mismatch:**
- More source values than tuple positions → extras are ignored.
- Fewer source values than positions → unbound positions go through
  standard error handling (400 / `Either Left`) — no special case.

### Special trailing parameters

The last 0, 1, or 2 parameters of a function may be `Request` and/or
`Map[String, Any]` (in that order) — these are never deserialized:

```scala
(name: String, req: Request)                    // deser name; req = raw
(name: String, ctx: Map[String, Any])           // deser name; ctx = mount context
(name: String, req: Request, ctx: Map[String, Any])  // all three
```

The same applies to multi-parameter functions:

```scala
(user: UserInput, addr: AddressInput, req: Request) =>
  // deser product of UserInput + AddressInput; req = raw request
```

### Full input signatures

| Parameter type | Behaviour |
|---|---|
| `Request` | raw request, no deserialization |
| `Input` | case class or named tuple, bound by name |
| `(String, Int, …)` | unnamed tuple, bound positionally |
| `(p1: T1, p2: T2, …)` | multi-param function = product of fields |
| `(Input, Request)` | deserialized Input **+** raw Request |
| `(Input, Map[String, Any])` | deserialized Input **+** mount context |
| `(Input, Request, Map[String, Any])` | all three |
| `Either[Request, Input]` | `Left(req)` on deser failure, `Right(input)` on success |

`Input` in the table above stands for any deserializable type (case class,
named tuple, unnamed tuple, or multi-param product). Named functions and
lambdas are interchangeable.

### Output types

| Return type | Behaviour |
|---|---|
| `Response` | used as-is |
| `Output` (case class / named tuple) | serialized to JSON object, status 200 |
| `(String, Int, …)` (unnamed tuple) | serialized to JSON array, status 200 |
| `Either[Response, Output]` | `Left(resp)` → used as-is; `Right(output)` → JSON 200 |

### Examples

```scala
// greet.ssc — case class in, case class out
case class GreetInput(name: String)
case class GreetOutput(greeting: String)

(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
// GET /hello/:name  →  {"greeting": "Hello, alice!"}
```

```scala
// greet-named-tuple.ssc — named tuple, equivalent to above
(input: (name: String)) => (greeting: s"Hello, ${input.name}!")
```

```scala
// pair.ssc — unnamed tuple in, unnamed tuple out  POST /add  body: [3, 4]
(a: Int, b: Int) => (a + b, a * b)
// → [7, 12]
```

```scala
// multi.ssc — multi-param function = product binding
// mounted as: POST /users
def create(name: String, email: String): UserCreated =
  UserCreated(db.insert(name, email), name)
// body: {"name":"alice","email":"a@b.com"}  →  {"id":1,"name":"alice"}
```

```scala
// create-user.ssc — POST with typed input + explicit error handling
case class CreateUserInput(name: String, email: String)
case class UserCreated(id: Int, name: String)

(req: Either[Request, CreateUserInput]) =>
  req match
    case Left(raw) =>
      Response.json("""{"error":"invalid body"}""", status = 400)
    case Right(input) =>
      UserCreated(db.insert(input.name, input.email), input.name)
```

With `Either[Request, Input]` the caller handles deser failure explicitly;
`errorDetails` is not consulted for the body.

```scala
// get-user.ssc — typed input + raw request (for auth header check)
case class GetUserInput(id: Int)
case class User(id: Int, name: String)

(input: GetUserInput, req: Request) =>
  if req.headers.get("Authorization").isEmpty then
    Response.text("Unauthorized", status = 401)
  else
    User(input.id, db.findById(input.id).name)
```

---

### Error mode (`errorDetails`)

Controls whether deserialization errors include a description in the response
body.

| `errorDetails` | 400 response body |
|---|---|
| `true` (default) | `{"error": "missing field: email"}` |
| `false` | `{}` (empty JSON object) |

**4-level priority (highest wins):**

1. **Global REPL setting** — `:set errorDetails false` (persists for the
   session).
2. **Front-matter** — `# errorDetails: false` at the top of the handler file.
3. **Per-`mount()` param** — `mount("POST", "/users", "create-user.ssc", errorDetails = false)`.
4. **Default** — `true`.

Same priority applies when using `:mount METHOD /path file.ssc` from the REPL
(no per-mount param there; global setting and front-matter apply).

---

## Full session example

```
ssc> :load api/users.ssc
Loaded api/users.ssc:
  GET    /users
  POST   /users
  GET    /users/:id

ssc> :mount GET /ping { _ => Response.text("pong") }
Mounted: GET /ping

ssc> :mount GET /items/:id handlers/entity.ssc coll=items
Mounted: GET /items/:id  (handlers/entity.ssc, ctx: {coll=items})

ssc> :serve 8080
Listening on :8080

ssc> :http GET /ping
→ 200 OK  text/plain
pong

ssc> :call GET /items/99
→ 200 OK
{"collection":"items","id":"99"}

ssc> :routes
  GET    /users        api/users.ssc
  POST   /users        api/users.ssc
  GET    /users/:id    api/users.ssc
  GET    /ping         <inline>
  GET    /items/:id    handlers/entity.ssc  {coll=items}

# edit api/users.ssc to add DELETE…
ssc> :reload api/users.ssc
Reloaded api/users.ssc:
  GET    /users
  POST   /users
  GET    /users/:id
  DELETE /users/:id  (new)

ssc> :call DELETE /users/0
→ 204 No Content

ssc> :stop --keep-routes
Server stopped. Routes kept (5 routes).

ssc> :serve 9000
Listening on :9000
```

---

## Implementation phases

### Phase 1 — Routes refactor

- `Routes`: `ArrayBuffer` → `LinkedHashMap[(String,String), Entry]`.
- Add `source: Option[String]` and `mountCtx: Map[String, Value]` to `Entry`.
- `Routes.register` replaces in place; add `Routes.removeBySource(path)`.
- All existing tests green.

### Phase 2 — `mount()` intrinsic

- New `mount(method, path, file)` and `mount(method, path, file, ctx)` intrinsics
  in `HttpIntrinsics` / http-plugin.
- File resolved relative to caller's directory (`interp.baseDir`).
- Evaluate file via `runSnippet`; detect handler shape (1-arg, 2-arg, or bare
  `Response`); auto-wrap; register with `source` + `mountCtx`.

### Phase 3 — `:serve` / `:stop` / `:clear`

- `:serve [port]` — starts `WebServer` in background virtual thread.
- `:stop [--keep-routes]` — closes server; conditionally clears routes.
- `:clear` — `Routes.clear()` without stopping server.

### Phase 4 — `:mount` (all three forms)

- Inline `{ handler }` — evaluate snippet → `Value.FunV` → register.
- Function name — look up in `interp.globals` → register.
- File `[key=value ...]` — parse ctx from trailing tokens; run via `mount()` logic.

### Phase 5 — `:load` / `:reload` / `:unmount`

- `:load` — `removeBySource` then run file with `currentSource` set.
- `:reload` — look up `source` in existing entries, dispatch to load or
  re-mount path.
- `:unmount` — `Routes.remove((method, path))`.

### Phase 6 — `:routes` / `:http` / `:call`

- `:routes` — tabular display with source + ctx columns.
- `:http` — raw HTTP/1.1 over `Socket`; parse `-H` flags and body.
- `:call` — synthetic `Request` from path + body + headers; dispatch in-process.

### Phase 7 — Typed handlers

- At mount time, inspect the last expression's type annotation via scalameta.
- Detect typed input forms (`Input`, `(Input, Request)`, `(Input, Map)`,
  `(Input, Request, Map)`, `Either[Request, Input]`).
- Detect typed output forms (`Output`, `Either[Response, Output]`).
- Generate a wrapping `Request => Response` that:
  - Deserializes fields from path params → query params → JSON body.
  - Calls the typed function.
  - Serializes the result to JSON 200, or passes through a `Response`/`Left`.
  - Honors `errorDetails` priority rules for 400 bodies.
- Tests: `TypedHandlerTest` — all 6 × 3 combinations, deser priority, error modes.

### Phase 8 — `:help` + tests + polish

- `:help` — list all commands with one-line descriptions.
- `:set errorDetails true|false` REPL command.
- Error messages standardised.
- `ReplWebTest` integration suite (all commands, error paths, ctx forwarding).
- Update `docs/user-guide.md` + `README.md`.

---

## Open questions

- **Handler shape detection**: distinguish `req => ...` from `(req, ctx) => ...`
  at runtime — check arity of the `Value.FunV`. If arity == 1: `handler(req)`;
  if arity == 2: `handler(req, ctx)`; if it's a non-function value: auto-wrap.
  Confirmed: `f.params.length` is always available on `FunV`.
- **`:call` query string with spaces** — URL-encode automatically or require
  the user to encode? Deferred to Phase 8 polish.
- **`ctx` value types** — `:mount` context values come in as strings
  (`key=value` tokens); `mount()` in code uses any `Map[String, Any]`.
  Document this asymmetry in Phase 8.
- **Prefix-mounting** — `:mount /api-prefix file.ssc` where the file contains
  `route()` calls: how do inner `route()` calls interact with the external
  prefix? Deferred; needs more design.
- **Well-known root resolution** for handler file paths (e.g. `@std/hello.ssc`)
  — same open question as for `import`. Deferred.

**Resolved:**

- Relative path resolution: relative to CWD (same as `ssc run`). ✓
- `:stop --keep-routes`: added. ✓
- `:clear` (routes without stop): added. ✓
- `:reload` knows method+path: stored in `Routes.Entry.source`. ✓
- `mount()` as language intrinsic: Phase 2. ✓
- Typed handlers (`CaseClass1 => CaseClass2`): Phase 7. ✓
- `errorDetails` 4-level priority: global > front-matter > per-mount param > default `true`. ✓
