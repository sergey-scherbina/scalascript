# Electron JVM REST Backend

> Status: design spec - May 2026.

This spec defines a split-process desktop mode: Electron renders the frontend
client, while the backend runs as a JVM ScalaScript server and is reached over
loopback HTTP.

The target outcome: `ssc run --frontend electron --backend jvm-rest app.ssc`
starts both processes, opens a desktop window, routes UI `fetch(...)` calls to
the JVM REST server, and shuts the JVM server down when Electron exits.

## Goals

- Support Electron as a frontend client for a JVM backend server.
- Preserve existing `.ssc` user-facing APIs:
  - `route(method, path) { ... }`;
  - `Request` / `Response`;
  - `Db.query` / `Db.execute`;
  - frontend toolkit helpers such as `fetchAction` and `fetchTable`.
- Let backend routes use the JVM runtime, including JDBC-backed databases and
  JVM-only libraries.
- Start both processes from one command in dev mode.
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

## Relationship To Existing Electron Modes

Today there are two Electron paths:

1. **Self-contained Electron bundle.** The generated `app.js` contains the
   browser-style ScalaScript runtime and same-app route dispatch. SQLite can be
   handled by the Electron persistence bridge in the main process.
2. **Future split JVM REST mode.** Electron contains the frontend client only.
   Backend routes run in a JVM process and are reached through
   `http://127.0.0.1:<port>/...`.

The split mode should be opt-in. Existing commands keep their behavior.

## User-Facing Commands

Initial command surface:

```bash
ssc run --frontend electron --backend jvm-rest app.ssc
ssc run --target desktop-jvm app.ssc
```

Potential aliases:

```bash
ssc run --target desktop --backend jvm-rest app.ssc
ssc build --target desktop-jvm app.ssc
```

The first implementation should focus on `ssc run`; packaging can follow once
the process contract is stable.

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

### Backend Base URL Injection

The Electron bundle needs a runtime constant:

```javascript
globalThis.__sscBackendBaseUrl = 'http://127.0.0.1:49152'
```

The browser/Electron SPA fetch patch should use it:

- `fetch("/api/todos")` -> `fetch(__sscBackendBaseUrl + "/api/todos")`;
- absolute external URLs remain unchanged;
- route navigation for frontend pages remains local.

This keeps toolkit helpers unchanged because they already call `fetch(url, ...)`.

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

### Port Selection

The CLI should bind a free loopback port, then pass it to the JVM server.

Open question for implementation: whether the JVM server accepts `serve(0)` and
reports the actual port, or whether the CLI reserves a port before launch. The
preferred path is `serve(0)` plus readiness reporting because pre-reserving a
port can race.

### Development Packaging

`ssc run` should work without `npm` for the same reason the self-contained
Electron mode now does: generated bundles include vendored runtime assets when
needed.

For split JVM REST mode, SQL usually lives on the JVM backend, so the Electron
SQLite bridge and vendored `sql.js` assets are not needed unless the same app
also declares client-side databases.

## Security

- Bind only to `127.0.0.1`, never `0.0.0.0`, by default.
- Generate an unguessable per-run token and require it on backend requests from
  Electron, for example `X-ScalaScript-Desktop-Token`.
- Inject the token into the Electron renderer through generated config, not
  through command-line arguments visible to unrelated processes.
- Keep CORS disabled or restricted to the Electron file origin plus the token.
- Do not expose backend administrative endpoints without the token.

Phase 1 may start without the token only if the server binds to loopback and the
test/demo scope is explicit, but the token should be part of the production
contract before packaging support lands.

## Migration

No existing `.ssc` source should need to change.

Existing behavior:

```bash
ssc run --frontend electron app.ssc
```

continues to launch the self-contained Electron app.

New behavior:

```bash
ssc run --frontend electron --backend jvm-rest app.ssc
```

launches the split Electron + JVM server app.

## Phases

### Phase 0 - Spec And CLI Contract

- Land this spec.
- Document the command surface and runtime responsibilities.
- Add the milestone plan.

### Phase 1 - Dev Run Supervisor

- Add CLI support for `--backend jvm-rest` with `--frontend electron`.
- Start the JVM backend server on loopback.
- Wait for readiness.
- Generate Electron renderer bundle with `__sscBackendBaseUrl`.
- Launch Electron and terminate the JVM backend on exit.
- Add a smoke test using `examples/frontend/toolkit-demo/toolkit-demo.ssc`.

### Phase 2 - Fetch Routing And Mixed Content

- Route same-origin API fetches to the backend base URL.
- Preserve local frontend navigation.
- Cover `Response.text`, `Response.json`, `Response.status`, and non-2xx
  responses in tests.

### Phase 3 - Security Token

- Generate a per-run desktop token.
- Inject it into Electron config.
- Require it on backend requests.
- Add negative tests for missing/incorrect token.

### Phase 4 - Desktop Build Packaging

- Add `ssc build --target desktop-jvm`.
- Package the JVM backend artifact with the Electron app.
- Ensure packaged app starts/stops the backend process.
- Document platform-specific signing/notarization implications.

### Phase 5 - Source Partitioning

- Add explicit client/server partitioning only if needed.
- Avoid duplicate side effects from top-level code.
- Keep shared pure definitions available to both sides.

## Testing Strategy

- Unit:
  - CLI mode parsing for `--frontend electron --backend jvm-rest`;
  - backend URL injection;
  - fetch URL rewriting;
  - child-process lifecycle helpers.
- Smoke:
  - toolkit demo starts JVM server + Electron;
  - Add flow posts to JVM `/api/todos`;
  - UI refresh fetches rows from JVM backend.
- Regression:
  - plain `ssc run --frontend electron` still uses self-contained mode;
  - Electron persistence bridge still works for local-first apps;
  - JVM backend exits when Electron exits.

## Open Questions

- Should the canonical spelling be `--backend jvm-rest`, `--server jvm`, or
  `--target desktop-jvm`?
- Should `serve(0)` report its bound port through a generated health endpoint or
  a structured stdout readiness line?
- Should security token enforcement land in Phase 1 or Phase 3?
- How much source partitioning is required before packaging support?
- Should packaged apps embed a JRE, require system Java, or use a GraalVM native
  backend artifact later?
