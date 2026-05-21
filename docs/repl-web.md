# REPL Web-Aware Mode

**Status:** spec — not yet implemented  
**Milestone:** v1.30  
**Commands added to:** `ssc repl`

---

## Goals

Make the ScalaScript REPL a first-class interactive HTTP development environment.
After `ssc repl` you can mount route handlers (inline or from `.ssc` files),
start a real HTTP server in the background, inspect the live route table, and
fire test requests — all without leaving the REPL or writing a full
`route(...) / serve(port)` program.

## Non-goals

- Debugger integration in the REPL (`:debug on`) — separate feature.
- Tab-completion / readline history — separate feature.
- Prefix-mounting of a whole file (`/api-prefix` + file) — deferred, needs
  more design around how `route()` calls inside the file interact with the
  external prefix.
- Hot-file-watch inside the REPL (use `ssc watch` for that).

---

## Route table changes (`Routes`)

The backing store changes from `ArrayBuffer[Entry]` to
`LinkedHashMap[(String, String), Entry]` where the key is `(method, rawPath)`,
e.g. `("GET", "/users/:id")`.

**Consequences:**

- `Routes.register` automatically **replaces** an existing entry with the same
  method + path — no duplicates possible.
- `:mount` is idempotent: calling it twice on the same method + path updates
  the handler in place.
- Iteration order (for `:routes` display) is stable (insertion order).
- Matching still requires iterating entries and pattern-matching path segments
  because parameterised paths (`/users/:id`) cannot be resolved by a direct
  map lookup.

A new field `source: Option[String]` is added to `Routes.Entry`:
- `None` — registered by a running `.ssc` program (existing behaviour).
- `Some(path)` — registered by a REPL `:load` or `:mount … file.ssc`; `path`
  is the canonical absolute path of the file on disk.

This field lets `:load` and `:reload` remove the previous routes from a
specific file before re-running it, so deleted routes don't linger.

---

## REPL commands

All REPL commands start with `:`.  Unknown commands print a short error;
`:help` lists them.

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
  immediately (the server reads `Routes` on every incoming request).
- If a server is already running, prints `Already serving on :<port>` and
  does nothing.

### `:stop`

Stops the running HTTP server **and clears all registered routes**.

```
ssc> :stop
Server stopped. Routes cleared.
```

If no server is running: `No server running.`

### `:mount METHOD /path { handler }`

Registers an inline handler in the REPL. Replaces any existing handler for
the same method + path.

```
ssc> :mount GET /ping { _ => Response.text("pong") }
Mounted: GET /ping

ssc> :mount GET /hello/:name { req =>
   |   val name = req.params("name")
   |   Response.text(s"Hello, $name!")
   | }
Mounted: GET /hello/:name
```

Handler must be a ScalaScript expression of type `Request => Response`.
The existing `{ req => ... }` multiline REPL syntax (blank line to submit)
works unchanged.

### `:mount METHOD /path functionName`

Mounts a function that is already defined in the REPL's global environment.

```
ssc> def greet(req: Request): Response =
   |   Response.text(s"Hi ${req.params("name")}!")
   |
ssc> :mount GET /hi/:name greet
Mounted: GET /hi/:name  (greet)
```

### `:mount METHOD /path file.ssc`

Runs `file.ssc` once, takes its last expression as the handler, and
registers it for the given method + path.

```
ssc> :mount GET /ping ping.ssc
Mounted: GET /ping  (ping.ssc)

ssc> :mount GET /hello/:name hello.ssc
Mounted: GET /hello/:name  (hello.ssc)
```

**File semantics:**

- The file is read from disk and evaluated exactly once at mount time.
- The last expression of the file must be either:
  - `Request => Response` — used directly as the handler, or
  - a `Response` value — automatically wrapped as `_ => <response>` (useful
    for static responses).
- Path parameters (`:name`), query params (`req.query`), body (`req.body`),
  etc. are all available inside the handler via the `req` argument — same
  field set as in `route(...)` handlers.
- `source` is set to the canonical absolute path of the file so `:reload`
  can find and replace it.

**Example files:**

```scala
// ping.ssc — static response, auto-wrapped
Response.text("pong")
```

```scala
// hello-path.ssc — path parameter
req => {
  val name = req.params("name")
  Response.text(s"Hello, $name!")
}
```

```scala
// hello-query.ssc — query parameter  GET /hello?name=alice
req => {
  val name = if req.query.contains("name") then req.query("name") else "world"
  Response.text(s"Hello, $name!")
}
```

```scala
// hello-post.ssc — POST with JSON body  {"name": "alice"}
req => {
  val name = req.json match
    case Some(data) => data("name").toString
    case None       => "stranger"
  Response.json(s"""{"greeting": "Hello, $name!"}""")
}
```

### `:load file.ssc`

Runs `file.ssc` as a normal ScalaScript program (same as `interp.runSnippet`
on its contents). Any `route(method, path) { ... }` calls inside the file
register routes in the usual way.

Before running, all routes with `source == Some(canonicalPath)` are removed
so a repeated `:load` hot-reloads the file's routes cleanly (including
removed routes).

```
ssc> :load api/users.ssc
Loaded api/users.ssc:
  GET  /users
  POST /users
  GET  /users/:id
```

Differences from `:mount … file.ssc`:

| | `:load file.ssc` | `:mount METHOD /path file.ssc` |
|--|--|--|
| File contains | `route(...)` calls | last expr = `Request => Response` |
| Path defined | inside the file | externally in the REPL command |
| Reusable on multiple paths | no | yes (mount same file twice) |

### `:reload file.ssc`

Re-runs `file.ssc` without having to repeat the method and path.

For files loaded via `:load`: same as `:load file.ssc` (clears the file's
previous routes, re-runs).

For files mounted via `:mount METHOD /path file.ssc`: re-reads the file from
disk, re-evaluates, replaces the handler — path and method unchanged.

```
ssc> :reload hello.ssc
Reloaded: GET /hello/:name  (hello.ssc)
```

If the file has not been loaded or mounted before: `Unknown file: hello.ssc`

### `:unmount METHOD /path`

Removes a specific route.

```
ssc> :unmount GET /ping
Unmounted: GET /ping
```

### `:routes`

Lists all registered routes with their sources.

```
ssc> :routes
  GET  /ping          <inline>
  GET  /hello/:name   hello.ssc
  POST /hello         hello-post.ssc
  GET  /users         api/users.ssc
  POST /users         api/users.ssc
  GET  /users/:id     api/users.ssc
```

### `:http METHOD /path [body]`

Sends a real HTTP request to `localhost:<port>` and prints the response.
Requires a server started with `:serve`.

```
ssc> :http GET /hello/alice
→ 200 OK  text/plain
Hello, alice!

ssc> :http POST /hello {"name":"bob"}
→ 200 OK  application/json
{"greeting": "Hello, bob!"}
```

If no server is running: `No server running. Use :serve [port] first.`

### `:call METHOD /path [body]`

In-process request: dispatches through `Routes.matchRequest` directly without
a network connection. Works without `:serve`. Useful for testing handlers in
isolation.

```
ssc> :call GET /ping
→ 200 OK
pong

ssc> :call GET /users/42
→ 200 OK
{"id": "42", "name": "alice"}
```

Body (if any) is passed as `req.body`; `req.query` is parsed from a `?`
suffix: `:call GET /hello?name=alice`.

---

## Full session example

```
ssc> :load api/users.ssc
Loaded api/users.ssc:
  GET  /users
  POST /users
  GET  /users/:id

ssc> :mount GET /ping { _ => Response.text("pong") }
Mounted: GET /ping

ssc> :serve 8080
Listening on :8080

ssc> :http GET /ping
→ 200 OK  text/plain
pong

ssc> :http GET /users
→ 200 OK  application/json
["alice","bob"]

ssc> :routes
  GET  /ping      <inline>
  GET  /users     api/users.ssc
  POST /users     api/users.ssc
  GET  /users/:id api/users.ssc

# edit api/users.ssc — add DELETE /users/:id ...
ssc> :reload api/users.ssc
Reloaded api/users.ssc:
  GET  /users
  POST /users
  GET  /users/:id
  DELETE /users/:id  (new)

ssc> :call DELETE /users/0
→ 204 No Content

ssc> :stop
Server stopped. Routes cleared.
```

---

## Implementation phases

### Phase 1 — Routes refactor

- Change `Routes` backing store from `ArrayBuffer` to `LinkedHashMap[(String,String), Entry]`.
- Add `source: Option[String]` to `Entry`.
- Update `Routes.register`, `Routes.clear`, `Routes.all`, `Routes.matchRequest`.
- All existing tests green.

### Phase 2 — `:serve` / `:stop`

- Start `WebServer` in a background virtual thread; keep `serverRef`.
- `:stop` closes the server + calls `Routes.clear()`.
- `:serve` while already running: warn, no-op.

### Phase 3 — `:mount` (inline + function name + file)

- Inline handler: parse `{ req => ... }` as a ScalaScript snippet, evaluate to `Value.FunV`, register.
- Function name: look up in `interp.globals`, validate it's callable, register.
- File: read from disk, run via `runSnippet`, take last `Value` as handler; auto-wrap `Response` → `FunV`.

### Phase 4 — `:load` / `:reload` / `:unmount`

- `:load`: remove routes by source, set `interp.currentSource`, run file.
- `:reload`: detect whether file was loaded or mounted, dispatch accordingly.
- `:unmount`: `Routes.remove((method, path))`.

### Phase 5 — `:routes` / `:http` / `:call`

- `:routes`: iterate `Routes.all`, format with source.
- `:http`: open `Socket("localhost", port)`, write raw HTTP/1.1 request, read response.
- `:call`: build a synthetic `Request`, call `Routes.matchRequest`, invoke handler in-process.

### Phase 6 — `:help` + polish

- `:help` lists all commands with one-line descriptions.
- Error messages standardised (unknown command, missing server, file not found, etc.).
- Tests: `ReplWebTest` integration suite covering all commands.

---

## Open questions

- `:mount GET /ping ./ping.ssc` vs `:mount GET /ping ping.ssc` — relative to
  CWD or to some base dir? Decision: relative to CWD (same as `ssc run`).
- Should `:call` support request headers? (`-H "Authorization: Bearer tok"`)
  Deferred to Phase 6 polish.
- Should `:stop` have a `--keep-routes` flag? Deferred.
