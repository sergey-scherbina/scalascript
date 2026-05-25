# Electron JVM REST Backend

> Status: design spec - May 2026. Phase 1a landed: explicit local dev mode
> `ssc run --frontend electron --backend jvm-rest app.ssc` starts a JVM backend
> and an Electron client wired through `__sscBackendBaseUrl`. Phase 1b landed:
> plain `ssc run app.ssc` uses the same split mode when the source declares
> `frontend: electron`, backend routes, and `serve(...)`. Server-only/client-only
> modes, distributed launch, non-Electron frontend supervision, and packaging
> remain planned. `ssc run --target desktop-jvm app.ssc` is also supported as a
> shorthand for the local Electron + JVM REST dev supervisor. Phase 2a landed:
> `ssc run --mode server --backend jvm app.ssc` starts only the JVM
> backend/server side. Phase 2b landed: `ssc run --mode client --frontend
> electron --server-url <url> app.ssc` starts only the Electron client pointed
> at an existing backend server. Phase 2c landed: web frontends can use
> `emit-spa --server-url <url>` or `ssc run --mode client --frontend
> react|solid|vue|custom --server-url <url> app.ssc`, which starts a local
> preview server, prints local and LAN frontend URLs plus the backend URL, and
> opens the local URL in the system browser.

URL logging policy: CLI-owned frontend/backend components print their local URL
and detected LAN URL candidates to stdout. Client components also print the
backend URL they are configured to call. This keeps the common "run on one
machine, open from another machine or phone" workflow visible without extra
commands.

This spec defines a split-process client/server mode. The first local-dev shape
is Electron rendering the frontend client while a JVM ScalaScript backend server
runs beside it over loopback HTTP. The same contract also supports distributed
deployment: the backend-only JVM server can run on one machine, and a
frontend-only client generated from the same `.ssc` file can run on another
machine with any supported frontend target.

The target outcome: `ssc run --frontend electron --backend jvm-rest app.ssc`
starts both processes, opens a desktop window, routes UI `fetch(...)` calls to
the JVM REST server, and shuts the JVM server down when Electron exits.

A default-run target outcome: when a single `.ssc` file contains both frontend
UI and backend routes, plain `ssc run app.ssc` should start the local full-stack
app on one machine. It launches the backend server and the selected/default
frontend client together, supervises both, and wires the frontend to the local
server automatically. Users opt out with explicit server-only, client-only, or
self-contained Electron modes.

A second target outcome:

```bash
ssc run --mode server --backend jvm app.ssc
ssc run --mode client --frontend react --server-url http://server:8080 app.ssc
ssc run --mode client --frontend electron --server-url http://server:8080 app.ssc
```

The first command starts only the backend server. The second and third commands
generate/start only frontend clients. All are derived from the same source file
and communicate through the same REST surface.

## Goals

- Support Electron as a frontend client for a JVM backend server.
- Support backend-only and frontend-only launches from the same `.ssc` source.
- Allow the backend server and frontend client to run on different machines.
- Keep the frontend target generic: React, Vue, Solid, custom web, Electron, and
  future native clients should share the same server URL contract.
- Preserve existing `.ssc` user-facing APIs:
  - `route(method, path) { ... }`;
  - `Request` / `Response`;
  - `Db.query` / `Db.execute`;
  - frontend toolkit helpers such as `fetchAction` and `fetchTable`.
- Support hybrid data apps with both server databases and client-local
  databases declared in the same `.ssc` source. Example: authoritative data on
  the JVM server, plus a local frontend SQLite/OPFS/Electron store for cache,
  offline state, drafts, or UI preferences.
- Let backend routes use the JVM runtime, including JDBC-backed databases and
  JVM-only libraries.
- Start both processes from one command in local dev mode.
- Also support separately-started server/client processes for distributed dev
  and deployment.
- Keep Electron renderer sandboxing: `nodeIntegration = false`,
  `contextIsolation = true`.
- Avoid forcing every backend exchange into raw JSON. REST responses keep their
  normal content type and body semantics; JSON is just one response format.

## Non-Goals

- Do not replace the self-contained Electron persistence bridge. That mode
  remains useful for local-first apps and offline demos.
- Do not require users to manually start the JVM server for the common dev flow.
- Do not expose arbitrary JVM objects directly to the renderer.
- Do not add a binary RPC protocol in the first implementation.
- Do not solve production authentication, code signing, or TLS for loopback in
  the initial phase.
- Do not support multiple backend processes in one app in the first phase.
- Do not require Electron for the distributed client/server model; Electron is
  only one frontend target.
- Do not automatically synchronize client-local databases with server
  databases in the first implementation. Sync/merge policy is application code
  or a later library.

## Relationship To Existing Modes

Today there are two Electron paths:

1. **Self-contained Electron bundle.** The generated `app.js` contains the
   browser-style ScalaScript runtime and same-app route dispatch. SQLite can be
   handled by the Electron persistence bridge in the main process.
2. **Future split JVM REST mode.** Electron contains the frontend client only.
   Backend routes run in a JVM process and are reached through
   `http://127.0.0.1:<port>/...`.

The split mode should be opt-in. Existing commands keep their behavior.

The generalized client/server mode adds a third shape that is not
Electron-specific:

3. **Distributed frontend client + JVM REST server.** The JVM server can run as
   a backend-only process on one machine. Any generated frontend client can run
   elsewhere and use `--server-url` / generated config to call the backend.

## User-Facing Commands

Initial command surface:

```bash
ssc run app.ssc
ssc run --frontend electron --backend jvm-rest app.ssc
ssc run --target desktop-jvm app.ssc
```

Implemented local dev commands:

```bash
ssc run app.ssc                         # if frontend: electron + routes + serve(...)
ssc run --frontend electron --backend jvm-rest app.ssc
ssc run --target desktop-jvm app.ssc
ssc run --mode server --backend jvm app.ssc
ssc run --mode client --frontend electron --server-url http://server:8080 app.ssc
ssc run --mode client --frontend react --server-url http://server:8080 app.ssc
ssc emit-spa --frontend react --server-url http://server:8080 app.ssc
```

Potential aliases:

```bash
ssc run --target desktop --backend jvm-rest app.ssc
ssc build --target desktop-jvm app.ssc
```

The first implementation should focus on `ssc run`; packaging can follow once
the process contract is stable.

Default behavior for `ssc run app.ssc`:

| Source shape | Default run behavior |
| --- | --- |
| backend routes only | run backend server/interpreter as today |
| frontend UI only | run frontend preview/client as today |
| frontend UI + backend routes | run supervised local full-stack mode |
| `frontend: electron` + backend routes + `serve(...)` | implemented: run Electron client + local JVM REST backend |
| explicit `--self-contained` / local-first flag | run current self-contained Electron/browser mode |

The exact source-shape detection can be conservative at first: a module that
contains `route(...)` or `routes:` and also calls frontend `serve(view, ...)` is
full-stack.

Distributed command surface:

```bash
# Machine A
ssc run --mode server --backend jvm --host 0.0.0.0 --port 8080 app.ssc

# Machine B
ssc run --mode client --frontend react --server-url http://machine-a:8080 app.ssc
ssc run --mode client --frontend electron --server-url http://machine-a:8080 app.ssc

# Static/package outputs
ssc build --mode client --frontend react --server-url https://api.example.com app.ssc
ssc build --mode client --target desktop --server-url https://api.example.com app.ssc
```

Open naming question: `--mode server/client` is explicit, but `ssc serve` and
`ssc emit-spa --server-url ...` may fit existing CLI habits better.

## Architecture

### Process Model

```text
ssc CLI
  ├─ JVM backend server process
  │    ├─ binds 127.0.0.1:<free-port>
  │    ├─ registers route(...) handlers
  │    └─ owns Db/JDBC/runtime state
  └─ Electron process
       ├─ main.js creates BrowserWindow
       ├─ preload exposes minimal config if needed
       └─ renderer app fetches http://127.0.0.1:<free-port>/api/...
```

The CLI is the supervisor in dev mode:

1. Compile or run the `.ssc` backend with the JVM target.
2. Bind the server to an ephemeral loopback port.
3. Generate the Electron frontend bundle with the backend base URL injected.
4. Launch Electron.
5. Forward logs from both processes with clear prefixes.
6. Terminate the JVM backend when Electron exits.

Plain `ssc run app.ssc` uses this same supervisor when the source is detected as
full-stack. The selected frontend comes from front-matter (`frontend:`), CLI
flags, or the existing default frontend policy. Electron is one possible client;
React/custom web preview should use the same backend base URL injection.

### Distributed Process Model

```text
Machine A
  JVM backend server
    ├─ binds configured host/port
    ├─ registers route(...) handlers
    ├─ owns Db/JDBC/runtime state
    └─ exposes HTTP REST API

Machine B / C / ...
  generated frontend client
    ├─ React/Vue/Solid/custom web OR Electron
    ├─ has __sscBackendBaseUrl = "https://api.example.com"
    └─ fetches backend REST routes
```

In this mode the CLI is not necessarily the supervisor for both sides. It can:

- start only the server;
- start only a client against an existing server URL;
- build static frontend assets with a baked-in server URL;
- build an Electron client with a baked-in or runtime-configurable server URL.

The server must be able to run without emitting or launching any frontend.
The client must be able to run without evaluating server-owned side effects.

### Source Partitioning

Phase 1 should support the existing single-file app shape:

- `route(...)` blocks/functions are backend-owned.
- frontend `serve(view, ...)` / toolkit UI is renderer-owned.
- shared pure definitions can be emitted to both sides if they are needed by
  both route handlers and renderer code.

The first implementation can be conservative:

- Generate the JVM server from the full module, as today's JVM target already
  understands `route`, `Db`, `Response`, and SQL.
- Generate the Electron renderer from the same module, but route all same-app
  `fetch("/...")` calls to the backend base URL instead of the in-renderer SPA
  route table.

Later phases can introduce explicit `@side=client` / `@side=server` partitioning
if duplicate top-level evaluation becomes a real issue.

Distributed mode makes source partitioning more important:

- **Server-only run** should evaluate backend declarations, routes, database
  setup, migrations, and server startup.
- **Client-only run/build** should evaluate frontend declarations and shared pure
  definitions, but must not execute backend startup or database mutations.
- Shared model types, encoders, validation helpers, and URL constants can be
  emitted to both sides.

Phase 1 can still use conservative heuristics for the local Electron+JVM case,
but distributed server/client should move toward explicit ownership markers:

```text
@side=server
@side=client
@side=shared
```

or an equivalent front-matter/module-level convention. The design should avoid
making users split one small app into multiple files just to run it in
client/server mode.

### Hybrid Server And Client Databases

Full-stack apps often need two distinct data stores:

- **server database**: authoritative state owned by the JVM backend, accessed by
  route handlers through JDBC or server-supported database drivers;
- **client-local database**: frontend-owned cache/offline store, accessed from
  React/Vue/Solid/custom browser code or Electron renderer/main bridge.

Both should be declared in the same `.ssc` module and selected by name:

```yaml
---
databases:
  server:
    side: server
    url: jdbc:postgresql://db.internal/app
    user: ${env:DB_USER}
    password: ${env:DB_PASSWORD}
  localCache:
    side: client
    url: sqlite:./cache.db
---
```

```scalascript
// Server route handler: authoritative data.
route("GET", "/api/todos") { _ =>
  val rows = Db.query("server", "SELECT id, text FROM todos ORDER BY id", [])
  Response.json(rows)
}

// Client code / client SQL block: local cache.
Db.execute("localCache", "INSERT INTO cached_todos(id, text) VALUES (?, ?)", [id, text])
```

Rules:

- `side: server` databases are available only in the JVM backend process.
- `side: client` databases are available only in frontend/client bundles.
- If `side` is omitted, existing behavior remains: SQL defaults to server side
  in full-stack mode and to the current target in single-target mode.
- A client bundle may contain multiple client-local databases.
- A server process may contain multiple server databases.
- A client-side reference to a server-only database is a build-time diagnostic.
- A server-side reference to a client-only database is a build-time diagnostic.

Client-local storage by frontend target:

| Frontend target | Client-local SQL backend |
| --- | --- |
| Electron | Electron persistence bridge (`sqlite:` under app data) |
| React/Vue/Solid/custom browser | JS SQL runtime: OPFS/sql.js/localStorage fallback depending on URL scheme and browser capabilities |
| Future native clients | Target-specific client-local provider |

This gives applications a clear shape: use REST to synchronize with the server,
and use client-local SQL for cache/offline state where needed. ScalaScript
should not hide the difference between authoritative server writes and local
cache writes.

### Browser Client Storage APIs

Client-local SQL is not the only useful client-side storage. Browser-based
frontends, including Electron renderers and React/Vue/Solid/custom web bundles,
should also be able to use the standard browser storage APIs directly when their
data shape fits better than a relational database.

Planned storage surface:

| API | Best fit | Notes |
| --- | --- | --- |
| `localStorage` | Small string key/value settings, flags, last-used values | Synchronous, origin-scoped, small quota; not for large datasets or secrets |
| `sessionStorage` | Per-tab temporary string key/value state | Cleared when the tab/window session ends |
| `IndexedDB` | Structured local app data, offline queues, drafts, blobs, indexes | Asynchronous, transactional object stores; primary browser-native persistent store |
| Cache API | HTTP response/app-shell caching | Best paired with Service Workers/PWA mode; not a general object database |
| OPFS | Origin-private files, SQLite/Wasm backing files, large local artifacts | Capability-dependent; useful for durable browser-local SQL/file storage |

ScalaScript should expose these as client-only standard-library APIs rather than
forcing every frontend feature through SQL:

```scalascript
LocalStorage.set("theme", "dark")
val draft = IndexedDb.get("drafts", draftId)
```

Rules:

- These APIs are frontend/client side only. Server-side references are build-time
  diagnostics.
- Electron renderers use Chromium's browser storage for these APIs; the Electron
  SQLite persistence bridge remains specific to SQL/file-backed databases.
- Browser SQL providers may use OPFS when available, sql.js memory/storage
  providers where appropriate, and localStorage only as a constrained fallback.
- Applications choose storage by data shape: SQL for relational/queryable local
  data, IndexedDB for structured offline state and queues, localStorage for tiny
  preferences, Cache API for response caching, and OPFS for files/Wasm-backed
  persistence.

### Server Object Store And Sync

For apps that need an IndexedDB-like model on both sides, ScalaScript should
provide a paired client/server object-store contract rather than pretending that
the server literally runs IndexedDB.

- Client side: browser/Electron clients store objects in IndexedDB.
- Server side: the JVM backend stores JSON objects in an `ObjectStore` backed
  first by a portable JDBC table, with PostgreSQL JSONB or CouchDB-compatible
  storage as later backend options.
- Sync: generated REST endpoints expose pull/push operations with cursors,
  optimistic versions, tombstones, and explicit conflict handling.

Example front matter:

```yaml
---
objectStores:
  todos:
    sync: client-server
    key: id
    server:
      backend: jdbc-json
      database: server
      table: ssc_object_store
    client:
      backend: indexeddb
      database: app
      store: todos
---
```

This is the recommended low-effort/high-value path: use IndexedDB where the
browser already provides it, use a simple JDBC JSON object table on the JVM
server, and add a narrow REST sync protocol between them. Full replication
systems such as CouchDB/PouchDB are established solutions and should remain an
optional future backend, but they are too heavy as the required bootstrap path.

See [`client-server-object-store.md`](client-server-object-store.md) for the
full draft contract.

### Graph Storage

Some full-stack apps need graph-shaped persistence rather than relational tables
or document/object stores. The planned graph layer is server-first:

- property graphs for vertices/edges/properties and traversal-heavy domains;
- RDF graphs for triples/quads, ontologies, linked data, and SPARQL;
- embedded JVM graph backends for local/dev/test apps;
- server/remote graph databases for production.

The first practical path is to add embedded JVM graph support without requiring
an external graph server: TinkerGraph/TinkerPop for property graphs and RDF4J
for RDF/SPARQL. Production adapters can follow for Neo4j/Cypher, JanusGraph or
other TinkerPop providers, and RDF4J-compatible servers.

Frontend clients should normally access graphs through JVM REST routes and cache
selected results locally in IndexedDB, client-local SQL, or the object-store sync
layer. Browser-local graph engines are a separate future capability.

See [`graph-storage.md`](graph-storage.md) for the full draft contract.

### Backend Base URL Injection

The frontend bundle needs a runtime constant:

```javascript
globalThis.__sscBackendBaseUrl = 'http://127.0.0.1:49152'
```

The browser/Electron SPA fetch patch should use it:

- `fetch("/api/todos")` -> `fetch(__sscBackendBaseUrl + "/api/todos")`;
- absolute external URLs remain unchanged;
- route navigation for frontend pages remains local.

This keeps toolkit helpers unchanged because they already call `fetch(url, ...)`.

For distributed clients, `__sscBackendBaseUrl` comes from `--server-url`, an
environment variable, generated config, or a small runtime config file. Static
web builds may bake it into `app.js`; Electron builds can read it from a
generated JSON file next to `main.js` if runtime reconfiguration is needed.

### HTTP Semantics

This mode is REST over loopback HTTP, not "JSON for everything".

Supported response forms should mirror the existing JVM server:

- `Response.text(...)`;
- `Response.html(...)`;
- `Response.json(...)`;
- `Response.status(...)`;
- redirects and headers where the browser fetch model supports them.

JSON remains the natural format for data APIs like `/api/todos`, but HTML,
plain text, status-only responses, and future streaming endpoints are not
excluded by the architecture.

The same semantics apply whether the client is local Electron, remote React, or
another frontend. The contract is HTTP, not a Scala object bridge.

### Lifecycle And Readiness

The CLI must not launch Electron before the JVM server is ready. Use one of:

1. JVM process prints a structured readiness line:
   `SSC_SERVER_READY port=<port>`.
2. CLI probes `/__ssc/health` on the selected port.

Preferred Phase 1: add a generated health route and probe it. This avoids
depending on log text.

Shutdown:

- when Electron exits, CLI sends JVM process termination;
- if JVM exits first, CLI closes Electron or surfaces a clear error;
- Ctrl-C terminates both children.

In distributed mode:

- server-only process owns its lifecycle and does not exit when a client exits;
- client-only process treats backend unavailability as a connection error and
  should show/report it clearly;
- readiness can be checked by clients through `/__ssc/health` before first API
  use, but clients must not require process supervision.

### Port Selection

The CLI should bind a free loopback port, then pass it to the JVM server.

Open question for implementation: whether the JVM server accepts `serve(0)` and
reports the actual port, or whether the CLI reserves a port before launch. The
preferred path is `serve(0)` plus readiness reporting because pre-reserving a
port can race.

For server-only distributed mode, explicit `--host` and `--port` flags are
required unless the `.ssc` source already calls `serve(port)`. Binding
`0.0.0.0` should require an explicit flag or command-line value; loopback stays
the default.

### Development Packaging

`ssc run` should work without `npm` for the same reason the self-contained
Electron mode now does: generated bundles include vendored runtime assets when
needed.

For split JVM REST mode, SQL usually lives on the JVM backend, so the Electron
SQLite bridge and vendored `sql.js` assets are not needed unless the same app
also declares client-side databases.

## Security

- Bind only to `127.0.0.1`, never `0.0.0.0`, by default for local supervised
  mode.
- Generate an unguessable per-run token and require it on backend requests from
  Electron, for example `X-ScalaScript-Desktop-Token`.
- Inject the token into the Electron renderer through generated config, not
  through command-line arguments visible to unrelated processes.
- Keep CORS disabled or restricted to the Electron file origin plus the token.
- Do not expose backend administrative endpoints without the token.

Phase 1 may start without the token only if the server binds to loopback and the
test/demo scope is explicit, but the token should be part of the production
contract before packaging support lands.

Distributed mode has a stricter baseline:

- binding to non-loopback requires an explicit `--host` or config setting;
- CORS must be configurable and default-deny for browser clients from other
  origins;
- authentication/authorization is app-level, but ScalaScript should provide a
  clear hook for bearer tokens or session cookies;
- generated clients should not embed long-lived production secrets.

## Migration

No existing `.ssc` source should need to change.

Existing behavior:

```bash
ssc run --frontend electron app.ssc
```

continues to launch the self-contained Electron app only when the app is
frontend-only or an explicit self-contained/local-first mode is requested. For
full-stack apps, plain `ssc run app.ssc` and `ssc run --frontend electron
app.ssc` should prefer supervised local full-stack mode.

New behavior:

```bash
ssc run app.ssc
ssc run --frontend electron --backend jvm-rest app.ssc
```

launches the split frontend + JVM server app when the source is full-stack.

Distributed behavior:

```bash
ssc run --mode server --backend jvm app.ssc
ssc run --mode client --frontend react --server-url http://127.0.0.1:8080 app.ssc
```

launches each side separately from the same source.

## Phases

### Phase 0 - Spec And CLI Contract

- Land this spec.
- Document the command surface and runtime responsibilities.
- Add the milestone plan.

### Phase 1 - Dev Run Supervisor

- **Phase 1a landed (2026-05-25):** explicit Electron mode only. The CLI starts
  the JVM backend via scala-cli, waits for the source's `serve(...)` port, writes
  an Electron bundle with `__sscBackendBaseUrl`, forwards relative renderer
  `fetch("/...")` calls to the JVM backend, and tears down the backend when
  Electron exits.
- **Still planned:** plain `ssc run app.ssc` full-stack autodetection,
  non-Electron frontend supervision, and end-to-end smoke coverage for default
  run semantics.

- Detect full-stack source shape for default `ssc run app.ssc`.
- Add CLI support for `--backend jvm-rest` with `--frontend electron`.
- Start the JVM backend server on loopback.
- Wait for readiness.
- Generate Electron renderer bundle with `__sscBackendBaseUrl`.
- Launch Electron and terminate the JVM backend on exit.
- Add a smoke test using `examples/frontend/toolkit-demo/toolkit-demo.ssc`.
- Add a smoke that uses plain `ssc run` semantics, not only explicit
  `--backend jvm-rest`.

### Phase 2 - Client/Server Split Commands

- Add backend-only server launch from the same `.ssc` source.
- Add frontend-only client launch/build from the same `.ssc` source.
- Support `--server-url` injection for React/custom web and Electron clients.
- Add a smoke where server and client are started by separate CLI invocations.

### Phase 3 - Fetch Routing And Mixed Content

- Route same-origin API fetches to the backend base URL.
- Preserve local frontend navigation.
- Cover `Response.text`, `Response.json`, `Response.status`, and non-2xx
  responses in tests.

### Phase 4 - Security Token And CORS

- Generate a per-run desktop token.
- Inject it into Electron config.
- Require it on backend requests.
- Add negative tests for missing/incorrect token.
- Add CORS configuration for distributed browser clients.

### Phase 5 - Desktop Build Packaging

- Add `ssc build --target desktop-jvm`.
- Package the JVM backend artifact with the Electron app.
- Ensure packaged app starts/stops the backend process.
- Document platform-specific signing/notarization implications.

### Phase 6 - Source Partitioning

- Add explicit client/server partitioning only if needed.
- Avoid duplicate side effects from top-level code.
- Keep shared pure definitions available to both sides.

### Phase 7 - Hybrid Client-Local SQL

- Extend `databases:` schema with a side/placement marker for server vs client
  databases.
- Validate that server code cannot use client-only databases and client code
  cannot use server-only databases.
- Ensure Electron client-local SQL continues to use the Electron persistence
  bridge.
- Ensure browser clients can use JS-supported local SQL providers for cache and
  offline state.
- Add an example with server `server` DB and client `localCache` DB from one
  `.ssc` source.

## Testing Strategy

- Unit:
  - full-stack source-shape detection for default `ssc run`;
  - CLI mode parsing for `--frontend electron --backend jvm-rest`;
  - CLI mode parsing for server-only and client-only commands;
  - backend URL injection;
  - fetch URL rewriting;
  - child-process lifecycle helpers.
- Smoke:
  - toolkit demo starts JVM server + Electron;
  - Add flow posts to JVM `/api/todos`;
  - UI refresh fetches rows from JVM backend.
  - backend-only JVM server and frontend-only React client run as separate
    processes and communicate through `--server-url`.
  - hybrid data app writes authoritative data on the JVM backend and caches a
    copy in a client-local database.
- Regression:
  - plain `ssc run` starts supervised local full-stack mode for mixed
    frontend+route apps;
  - explicit self-contained Electron mode still works for local-first apps;
  - Electron persistence bridge still works for local-first apps;
  - JVM backend exits when Electron exits.

## Open Questions

- Should the canonical spelling be `--backend jvm-rest`, `--server jvm`, or
  `--target desktop-jvm`?
- What is the explicit opt-out spelling for self-contained Electron when a file
  contains both frontend and routes: `--self-contained`, `--local-first`, or a
  front-matter key?
- Should distributed launch use `--mode server/client`, new subcommands, or
  existing `serve` / `emit-spa` commands?
- Should `serve(0)` report its bound port through a generated health endpoint or
  a structured stdout readiness line?
- Should security token enforcement land in Phase 1 or Phase 3?
- How much source partitioning is required before packaging support?
- Should packaged apps embed a JRE, require system Java, or use a GraalVM native
  backend artifact later?
- For browser clients on another machine, should ScalaScript generate a default
  CORS policy from front-matter?
- Should the `databases:` placement key be named `side`, `scope`, `placement`,
  or should placement be expressed through URL schemes such as
  `client-sqlite:`?
- What minimum sync helper should the standard library provide, if any, for
  client-local cache invalidation and server reconciliation?
