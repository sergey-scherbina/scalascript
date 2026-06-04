# Mount handlers

**Status:** implemented (v1.30)  
**Milestone:** v1.30  
**See also:** [`docs/specs/repl-web.md`](repl-web.md) — REPL commands that use this mechanic

---

## Overview

`mount()` is a language-level intrinsic (available in any `.ssc` program)
that registers a `.ssc` file as an HTTP request handler for a given method
and path. The REPL command `:mount METHOD /path file.ssc` is a thin shell
over the same operation.

A handler file is a `.ssc` file whose last expression is a function
(or `Response` value) that processes incoming requests. The runtime evaluates
the file once at mount time and registers the result in the shared `Routes`
table.

---

## `mount()` — signature and semantics

```scala
mount(method: String, path: String, file: String): Unit
mount(method: String, path: String, file: String, ctx: Map[String, Any]): Unit
```

```scala
// Basic
mount("GET", "/ping", "handlers/ping.ssc")

// With context passed to the handler
mount("GET",    "/users/:id",    "handlers/entity.ssc", Map("coll" -> "users"))
mount("GET",    "/products/:id", "handlers/entity.ssc", Map("coll" -> "products"))

// Named function from a multi-function file (file#fnName syntax)
mount("GET", "/greet/:name",   "handlers.ssc#greet")
mount("GET", "/farewell/:name", "handlers.ssc#farewell")

serve(8080)
```

**Semantics:**

- `file` is resolved relative to the directory of the calling `.ssc` file
  (same as `import`). In the REPL, relative to CWD.
- The file is read from disk and evaluated **once** at mount time — not per
  request.
- The resulting handler is registered in `Routes`, replacing any existing
  entry for the same `(method, path)` key.
- `ctx` is passed as the second argument to handlers that declare a
  `Map[String, Any]` context parameter (see §Special trailing parameters).
  If the handler doesn't declare one, `ctx` is silently ignored.
- `errorDetails` controls deserialization error verbosity (see §Error mode).

**Mounting a specific function from a multi-function file (`file#fnName`):**

If the `file` path contains `#`, the part after it is the name of a function
to look up in the file's globals after evaluation. The file is still evaluated
once in full (all definitions run); only the named function is used as the handler.

```scala
// handlers.ssc — multiple handlers in one file
case class GreetInput(name: String)
case class GreetOutput(greeting: String)

def greet(input: GreetInput)    = GreetOutput(s"Hello, ${input.name}!")
def farewell(input: GreetInput) = GreetOutput(s"Goodbye, ${input.name}!")
```

```scala
// server.ssc
mount("GET", "/greet/:name",    "handlers.ssc#greet")
mount("GET", "/farewell/:name", "handlers.ssc#farewell")
serve(8080)
```

If the named function is not found after evaluation: mount-time error.

**REPL equivalence:**

```
ssc> :mount GET /ping handlers/ping.ssc
ssc> :mount GET /greet/:name handlers.ssc#greet
```

is identical to calling `mount("GET", "/ping", "handlers/ping.ssc")` (or
`mount("GET", "/greet/:name", "handlers.ssc#greet")`) in a running script.

---

## Handler file contract

The last expression of a handler file must be one of:

| Last expression | Interpretation |
|---|---|
| `Request => Response` | used directly as the handler |
| `(Request, Map[String, Any]) => Response` | handler with mount context |
| Typed handler (see §Typed handlers) | auto-deser/ser wrapper applied |
| `Response` value | auto-wrapped as `_ => <response>` |

The `req: Request` argument gives access to:

| Field | Type | Description |
|---|---|---|
| `req.method` | `String` | `"GET"`, `"POST"`, … |
| `req.path` | `String` | `/hello/alice` |
| `req.params` | `Map[String,String]` | path params (`:name` segments) |
| `req.query` | `Map[String,String]` | parsed query string |
| `req.headers` | `Map[String,String]` | request headers |
| `req.body` | `Option[String]` | raw body |
| `req.json` | `Option[Map[String,Value]]` | body parsed as JSON object |

**Example files:**

```scala
// ping.ssc — static response, auto-wrapped
Response.text("pong")
```

```scala
// hello.ssc — path parameter  GET /hello/:name
req => {
  val name = req.params("name")
  Response.text(s"Hello, $name!")
}
```

```scala
// entity.ssc — reused for /users/:id and /products/:id via ctx
(req, ctx) => {
  val coll = ctx("coll").toString
  val id   = req.params("id")
  Response.json(s"""{"collection":"$coll","id":"$id"}""")
}
```

---

## Typed handlers

A handler file may end with a **typed handler** — any function or lambda
whose parameters are not `Request`. The runtime detects the shape at mount
time and wraps it automatically. Named functions and lambdas follow identical
rules.

### Input types

**Case class / named tuple** — fields bound by name:

```scala
case class GreetInput(name: String, age: Int)
(name: String, age: Int)   // named tuple — identical binding
```

**Unnamed tuple** — fields bound positionally as `_1`, `_2`, …:

```scala
(String, Int)   // _1 = String, _2 = Int
```

**Multi-parameter function** — equivalent to the product of all non-special
parameters' fields:

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

Values are collected from all sources in order:

```
path params (URL segment order)
  → query params
    → JSON body (array elements, or object values in declaration order)
```

Then `_1` ← first collected value, `_2` ← second, and so on, with type
coercion (`"42"` → `Int`, `"true"` → `Boolean`).

**Count mismatch:**
- More source values than tuple positions → extras are ignored.
- Fewer source values than positions → unbound positions go through standard
  error handling (400 / `Either Left`) — no special case.

### Special trailing parameters

The last 0, 1, or 2 parameters of a function may be `Request` and/or
`Map[String, Any]` (in that order) — these are never deserialized:

```scala
(name: String, req: Request)                         // deser name; req = raw
(name: String, ctx: Map[String, Any])                // deser name; ctx = mount ctx
(name: String, req: Request, ctx: Map[String, Any])  // all three
```

Applies to multi-parameter functions too:

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
| `(p1: T1, p2: T2, …)` | multi-param = product of all non-special fields |
| `(Input, Request)` | deserialized Input **+** raw Request |
| `(Input, Map[String, Any])` | deserialized Input **+** mount context |
| `(Input, Request, Map[String, Any])` | all three |
| `Either[Request, Input]` | `Left(req)` on deser failure, `Right(input)` on success |

`Input` stands for any deserializable type (case class, named tuple, unnamed
tuple, or multi-param product). Named functions and lambdas are interchangeable.

### Output types

| Return type | Behaviour |
|---|---|
| `Response` | used as-is |
| `Output` (case class / named tuple) | serialized to JSON object, status 200 |
| `(String, Int, …)` (unnamed tuple) | serialized to JSON array, status 200 |
| `Either[Response, Output]` | `Left(resp)` → used as-is; `Right(output)` → JSON 200 |

### Examples

```scala
// greet.ssc — case class in, case class out  GET /hello/:name
case class GreetInput(name: String)
case class GreetOutput(greeting: String)

(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
// {"greeting": "Hello, alice!"}
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
// create.ssc — multi-param named function  POST /users
// body: {"name":"alice","email":"a@b.com"}
case class UserCreated(id: Int, name: String)

def create(name: String, email: String): UserCreated =
  UserCreated(db.insert(name, email), name)
```

```scala
// safe-create.ssc — explicit deser error handling  POST /users
case class CreateInput(name: String, email: String)
case class UserCreated(id: Int, name: String)

(req: Either[Request, CreateInput]) =>
  req match
    case Left(raw)   => Response.json("""{"error":"invalid body"}""", status = 400)
    case Right(input) => UserCreated(db.insert(input.name, input.email), input.name)
```

`Either[Request, Input]` — caller handles deser failure explicitly;
`errorDetails` is not consulted.

```scala
// get-user.ssc — typed input + raw request  GET /users/:id
case class GetInput(id: Int)
case class User(id: Int, name: String)

(input: GetInput, req: Request) =>
  if req.headers.get("Authorization").isEmpty then
    Response.text("Unauthorized", status = 401)
  else
    User(input.id, db.findById(input.id).name)
```

---

## Error mode (`errorDetails`)

Controls whether deserialization errors include a description in the 400 body.

| `errorDetails` | 400 response body |
|---|---|
| `true` (default) | `{"error": "missing field: email"}` |
| `false` | `{}` (empty JSON object) |

**4-level priority (highest wins):**

1. **Global REPL setting** — `:set errorDetails false` (persists for session).
2. **Front-matter** — `# errorDetails: false` at the top of the handler file.
3. **Per-`mount()` param** — `mount("POST", "/users", "f.ssc", errorDetails = false)`.
4. **Default** — `true`.

When using `:mount METHOD /path file.ssc` from the REPL there is no
per-mount param; global setting and front-matter apply.
