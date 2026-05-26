# Intrinsics Migration — All In-Tree Families to `.sscpkg`

Status: **COMPLETE** (2026-05-21) — All 11 intrinsic families migrated to
`.sscpkg` plugins; interpreter core ships with zero domain-specific
intrinsics. See `MILESTONES.md` for the landed entry.

Companion to [`docs/plugin-architecture.md`](plugin-architecture.md).
That document answers *how* a single new intrinsic family ships as a
plugin.  This document answers *how we migrate every existing
family out of the interpreter*.

---

## 1. Why migrate

The interpreter today loads every intrinsic family at startup via a
single concatenated map
([`InterpreterCapabilities.scala:52-79`](../backend-interpreter/src/main/scala/scalascript/interpreter/InterpreterCapabilities.scala)):

```scala
val InterpreterIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  Map( /* nowMillis, Console.* */ )
    ++ HttpIntrinsics ++ WsIntrinsics ++ AuthIntrinsics ++ CoreIntrinsics
    ++ JsonIntrinsics ++ RequestIntrinsics ++ McpIntrinsics ++ OAuthIntrinsics
    ++ OAuthClientIntrinsics ++ JdbcIntrinsics ++ FrontendIntrinsics
    ++ ToolkitDslIntrinsics
```

Every new domain or bug-fix in any of those families requires
recompiling the interpreter.  A script that only does JSON parsing
ships with the full MCP server runtime.  And a third-party developer
who wants to add, say, a Redis intrinsic family has no path that
doesn't fork the repo.

The agreed design decision (documented in `plugin-architecture.md`
§3.2) is **lean core + explicit `pkg:` imports**: only
`CoreIntrinsics` stays bundled.  Every other family ships as an
independent `.sscpkg` archive and is pulled in by a `[…](pkg:…)` line
in the script that needs it.

Consequences for users:

- **Scripts that currently call `http.get`, `json.parse`, `auth.signJwt`,
  etc., must add a `pkg:` import line** at the top.  This is a
  deliberate breaking change: it makes dependencies visible and
  versioned.
- **The interpreter binary shrinks**: only `CoreIntrinsics` (201 lines,
  22 entries) is bundled.
- **Test suites move with their plugin** — each `.sscpkg` is a
  self-contained module with its own `sbt test` target.

---

## 2. What stays bundled vs. what migrates

### Stays in core

| Component | Why |
|---|---|
| `CoreIntrinsics` (201 LOC, 22 entries) | `assert`, `require`, arithmetic, math, `escape`, `doc`, `render`, `scope` — every script depends on these; they are evaluation semantics, not domain features |
| `Console.println` / `Console.print` / `nowMillis` inline in `InterpreterIntrinsics` | Part of the language surface; removing them breaks every `println()` call |

**Rule**: a family stays bundled if and only if (a) every `.ssc` script
depends on it, and (b) removing it would change the language semantics
rather than just the available library.

### Migrates to `.sscpkg`

All other 11 families.  See §4 for per-family profiles.

---

## 3. Pre-migration work (four blocking items)

These must land **before any family migrates**.  They are
implementation PRs, not doc — listed here as hard dependencies so
migration PRs do not open prematurely.

### 3.1 BuiltinsRuntime merges plugin intrinsics

**Status: LANDED (2026-05-21)**

**File**: `backend-interpreter/src/main/scala/scalascript/interpreter/BuiltinsRuntime.scala:17`

`BuiltinsRuntime.initBuiltins` now calls `BackendRegistry.inProcess` (ServiceLoader
discovery) and merges `NativeImpl` entries from all discovered plugins before
calling `installNativeIntrinsics`.  Only `NativeImpl` entries are accepted —
`RuntimeCall`/`InlineCode` entries from code-generating backends are silently
skipped so they cannot shadow a bundled entry.

This is the wire that lets all `std/*-plugin` intrinsics reach the interpreter
automatically when their JARs are on the classpath (in tests / dev).  In
production, a `pkg:` import line triggers `BackendRegistry.loadAndExtract`
before the script starts so the plugin JAR is registered.

### 3.2 `NativeContext` state bag

**Status: DEFERRED** — Http (§4.8) migrated using the 21 existing named
`NativeContext` methods rather than the generic bag.  The bag
(`featureGet[A]`/`featureSet[A]`) is a future cleanup that would let *new*
plugins store feature state without amending the SPI trait each time.

Not blocking any current migration.  When it eventually lands, Http and Mcp
can migrate their NativeContext surface to the bag as a non-breaking
refactor.  See §11 for the open-item entry.

### 3.3 `pkg:` URI in `ImportResolver`

**Status: LANDED (2026-05-21)**

**File**: `core/src/main/scala/scalascript/imports/ImportResolver.scala`

The resolver now handles `pkg:` as the first scheme branch before `dep:`.
Resolution steps:

1. Parse `pkg:org/name:ver` (or `pkg:name:ver` or `pkg:name`) to extract
   the coordinate.
2. Call `BackendRegistry.findInstalledPkg(coord)` — searches
   `~/.scalascript/compiler/plugins/` and any dirs added via `--plugin-dir`.
3. If found: call `BackendRegistry.loadAndExtract(path)` — loads intrinsics
   via `loadSscpkg` and extracts `sources/*.ssc` to a temp dir.
4. If not found locally: look up `coord` in `LocalRegistry`; if a URL is
   found, download + install to the plugins dir, then load.
5. If still not found: throw a helpful error:
   ```
   plugin 'scalascript/http:1.0' is not installed.
   Run: ssc install scalascript/http:1.0
   ```
6. Return the entry-point `.ssc` path from the extracted sources dir
   (`index.ssc` if present, otherwise the lexicographically first `.ssc`).

New helpers added:
- `SscpkgLoader.extractSources(pkg: os.Path): os.Path` — extracts
  `sources/*.ssc` from a ZIP archive to a fresh temp dir.
- `BackendRegistry.findInstalledPkg(coord: String): Option[os.Path]` —
  searches all plugin dirs for a matching `.sscpkg` by name and version.
- `BackendRegistry.loadAndExtract(pkg: os.Path): os.Path` — idempotent
  load + extract; cached so repeated imports don't re-extract.

### 3.4 Registry seeding and `ssc install`

**Status: LANDED (2026-05-21)**

`LocalRegistry` (`core/src/main/scala/scalascript/compiler/plugin/LocalRegistry.scala`)
stores registry entries under `~/.scalascript/registry.yaml`.

- **`ssc install <pkg>`** — top-level command shortcut (delegates to
  `ssc plugin install`).  Copies a `.sscpkg` archive from a local path,
  HTTPS URL, or short registry name into `~/.scalascript/compiler/plugins/`.
- **Auto-install on first use**: when a `pkg:` import references a package
  that is not installed but has a URL in `LocalRegistry`, `ImportResolver`
  downloads and installs it automatically (respects `SSC_NO_NETWORK=1`).
- **Error UX**: when a script references
  `pkg:scalascript/http:1.0` and the package is not in the registry,
  the interpreter prints:
  ```
  plugin 'scalascript/http:1.0' is not installed.
  Run: ssc install scalascript/http:1.0
  ```
  rather than a `NoSuchElementException` from the registry lookup.

---

## 4. Migration order and per-family profiles

### 4.1 Json — pilot

| | |
|---|---|
| Source | `intrinsics/Json.scala` |
| LOC | 194 |
| QualifiedName entries | 7 (`jsonStringify`, `jsonParse`, `jsonRead`, `lookup`, `lookupOpt`, + 2 variants) |
| NativeContext methods | **none** |
| JVM deps | none beyond stdlib |
| `.ssc` surface today | none — must be created |
| In-tree tests | `JsonSpec.scala` |

**Why first**: zero NativeContext, zero JVM deps beyond stdlib.  It is
a pure function library.  The plugin writes itself.

**`.sscpkg` shape**:

```
json-plugin/
  sscpkg.yaml             # id: scalascript/json, version: 1.0.0
  sources/
    json.ssc              # extern def jsonStringify(v: Any): String
                          # extern def jsonParse(s: String): Any
                          # extern def lookup(obj: Any, key: String): Any
                          # … (7 total)
  intrinsics/
    json-intrinsics.jar   # scala object JsonPlugin extends Backend
                          #   override def intrinsics = JsonIntrinsics
```

**Examples sweep**: audit `examples/*.ssc` for `jsonParse`, `jsonStringify`,
`lookup`, `lookupOpt`.  Files affected: `dsl-json-parser.ssc` and any
that use inline JSON.  Each gets:

```scalascript
[jsonParse, jsonStringify, lookup, lookupOpt](pkg:scalascript/json:1.0)
```

**Exit criteria**:

1. `sbt json-plugin/test` passes in the extracted module
2. `JsonSpec` (moved into plugin) covers all 7 entries
3. The interpreter binary does **not** contain `JsonIntrinsics` in its
   global table when started without the plugin
4. `examples/dsl-json-parser.ssc` runs with the `pkg:` import line

---

### 4.2 Frontend

| | |
|---|---|
| Source | `intrinsics/Frontend.scala` |
| LOC | 30 |
| QualifiedName entries | 3 (`setFrontendFramework` + 2 variants) |
| NativeContext methods | **none** |
| JVM deps | `frontend-core` module only (pure ADTs, no runtime) |
| `.ssc` surface today | none — must be created |
| In-tree tests | `FrontendSpec.scala` |

Migrated before Request because it has no NativeContext at all and is
trivially small — a good second proof-of-concept before we touch anything
more complex.

**`.sscpkg` shape**: same pattern as Json.  The `frontend-core` JAR
ships inside the `.sscpkg` archive or is declared as a resolved
dependency in `sscpkg.yaml`.

---

### 4.3 Request

| | |
|---|---|
| Source | `intrinsics/Request.scala` |
| LOC | 176 |
| QualifiedName entries | 13 (`requireString`, `optionalString`, `requireInt`, `optionalInt`, `requireDouble`, `optionalDouble`, `requireBool`, `optionalBool`, `requireRange`, `requireRangeDouble`, `requireOneOf`, + 2) |
| NativeContext methods | **`ctx.validationRecord`** only (11 call sites) |
| JVM deps | none beyond stdlib |
| `.ssc` surface today | none |
| In-tree tests | inline within RequestSpec (small) |

The single `ctx.validationRecord` call means Request is the first
plugin to exercise a NativeContext method.  The state-bag (§3.2)
does not help here — `validationRecord` is stateful in a
session-scoped validate-block sense, not a per-plugin-feature sense.
It should stay as a named method on `NativeContext` but its default
implementation (throws) is already there.  No NativeContext changes
needed for this migration.

**Examples sweep**: validate-block patterns in server examples.  Any
`requireString(params, "foo")` call in an HTTP handler.

---

### 4.4 Auth

| | |
|---|---|
| Source | `intrinsics/Auth.scala` |
| LOC | 352 |
| QualifiedName entries | 34 (CSRF, Base64-url, WebAuthn, rate-limit, TOTP, password, cookie, session, JWT HS256+RS256, OAuth2 helpers) |
| NativeContext methods | **none** — calls `scalascript.server.*` directly |
| JVM deps | `runtime-server-common`: `WebAuthn`, `Totp`, `Password`, `RateLimit`, `Jwt`, `JwtRsa`, `SessionStore`, `SessionCookie`, `BasicAuth` |
| `.ssc` surface today | none |
| In-tree tests | `AuthSpec.scala` |

Auth uses `scalascript.server.{WebAuthn, Totp, Password, RateLimit}`
from `runtime-server-common` (a separate sbt subproject) rather than
calling them through `NativeContext`.  This means:

1. The `runtime-server-common` JAR must be declared in
   `sscpkg.yaml`'s `jvmDeps` list and shipped in
   `intrinsics/runtime-server-common.jar`.
2. No state-bag changes are needed.

**Important**: `runtime-server-common` is shared with the Http and Ws
plugins.  The three plugins should declare a common Maven/sbt
coordinate for it rather than each bundling a copy.  In v1 (local
registry only), the simplest approach is to include the JAR in each
plugin archive — deduplification can be deferred.

**Examples sweep**: `auth-demo.ssc`, `auth-full.ssc`, `webauthn-demo.ssc`.

---

### 4.5 OAuthClient

| | |
|---|---|
| Source | `intrinsics/OAuthClientIntrinsics.scala` |
| LOC | 240 |
| QualifiedName entries | 12 |
| NativeContext methods | **none** |
| JVM deps | `java.net.http` (stdlib; no extra JAR) |
| `.ssc` surface today | none |
| In-tree tests | inline within OAuthClientSpec |

OAuthClient makes outbound OAuth token requests using `java.net.http`.
No server runtime dependency.  Clean extraction.

---

### 4.6 Ws

| | |
|---|---|
| Source | `intrinsics/Ws.scala` |
| LOC | 87 |
| QualifiedName entries | 6 (`metrics`, `setMaxWsConnections`, `WsRoom.*`) |
| NativeContext methods | **none** — calls `scalascript.server.Metrics` and `scalascript.server.WsConnection` directly |
| JVM deps | `runtime-server-common` (`Metrics`, `WsConnection`, `WsRateLimiter`) + interpreter-server (`WsRoutes`, `WsClientSession`) |
| `.ssc` surface today | none |
| In-tree tests | `WsSpec.scala` |

Ws can migrate independently of Http because it has no NativeContext
usage.  However, the `onWebSocket` / `onWebSocketAuth` route
registration lives in `Http.scala` (using `ctx.registerWsRoute`).
The relationship is:

- `Ws.scala` = intrinsics for room/metrics/connection-cap — migrates here
- `Http.scala` = route registration — migrates in §4.8

**Interpreter-server dependency**: `Ws.scala` uses classes in
`backend-interpreter/src/main/scala/scalascript/server/`
(`WsConnection`, `WsProxy`, `WsRoutes`).  These must either move to a
`runtime-server-interpreter` submodule that the plugin can depend on,
or be copied into the plugin archive.  This is the same problem as
Http (§4.8).  The recommended fix is to extract
`backend-interpreter/src/main/scala/scalascript/server/` into a new
`runtime-server-interpreter` sbt subproject *before* Ws or Http migrate.

---

### 4.7 OAuth (server-side)

| | |
|---|---|
| Source | `intrinsics/OAuth.scala` + helpers: `OAuthHttp.scala` (157 LOC), `Oidc.scala` (102 LOC), `OidcHttp.scala` (99 LOC) |
| LOC | 432 + 358 helpers = ~790 total |
| QualifiedName entries | 12 (`OAuthIntrinsics` map) |
| NativeContext methods | **`ctx.invokeCallback`** only (handler dispatch) |
| JVM deps | `runtime-server-common` (`server.OAuth`) + `java.net.http` |
| `.ssc` surface today | partial: `std/` stubs exist for some entries |
| In-tree tests | `OAuthSpec.scala` |

The three helper files (`OAuthHttp`, `Oidc`, `OidcHttp`) must move with
`OAuth.scala` into the same `.sscpkg`.  They have 0 `QualifiedName`
entries of their own — they are called by `OAuthIntrinsics`.

The only NativeContext usage is `ctx.invokeCallback` which has a
default no-op.  No state-bag changes needed.

**Examples sweep**: `oauth-as-standalone.ssc`, `oauth-mcp-full-stack.ssc`,
`oauth-rs-guard.ssc`, `oauth-rsa-jwks.ssc`, `oidc-idp.ssc`,
`oidc-login-flow.ssc`.

---

### 4.8 Http (+ Ws route registration)

| | |
|---|---|
| Source | `intrinsics/Http.scala` |
| LOC | 568 |
| QualifiedName entries | 35 |
| NativeContext methods | **21** (see list below) |
| JVM deps | `runtime-server-common` + interpreter-server (`WebServer`, `Routes`, `TlsProxy`, etc.) |
| `.ssc` surface today | partial |
| In-tree tests | `HttpSpec.scala`, `RoutingSpec.scala`, others |

**This is the hardest migration**.  Http uses every NativeContext method
that exists:

```
ctx.registerRoute    ctx.registerHealthDefaults  ctx.startTlsServer
ctx.registerWsRoute  ctx.registerWsAuthRoute     ctx.wsConnectSync
ctx.registerMiddleware  ctx.configureCors        ctx.enableGzip
ctx.setMaxBodySize   ctx.setSpoolThreshold       ctx.setUploadDir
ctx.invokeCallback   ctx.httpBaseUrl             ctx.httpTimeoutMs
ctx.httpMaxRetries   ctx.httpRetryDelayMs        ctx.setHttpTimeout
ctx.setHttpRetry     ctx.headless                ctx.out
```

**Blocker: state-bag must land first** (§3.2).  Without it, the Http
plugin would need to declare all 21 methods on `NativeContext` at
compile time — defeating the purpose of a plugin.  After the state-bag,
Http stores its config like `ctx.featureSet("http.router", router)` and
retrieves it like `ctx.featureGet[Router]("http.router")`.

**Blocker: interpreter-server extraction**.  The 8 files in
`backend-interpreter/src/main/scala/scalascript/server/`
(`WebServer`, `Routes`, `TlsProxy`, `WsClientSession`, `WsConnection`,
`WsProxy`, `WsRoutes`, `InterpreterHttpHandler`) must be extracted to a
`runtime-server-interpreter` sbt subproject.  The Http plugin then
declares it as a JAR dependency.  This extraction is a refactor, not a
migration PR — it should be done atomically before Http moves.

**Exit criteria**: the Http plugin is the only migration that *requires*
both the state-bag amendment (§3.2) and the interpreter-server
extraction.  It should be the last non-blocked migration attempted.

---

### 4.9 Mcp

| | |
|---|---|
| Source | `intrinsics/Mcp.scala` |
| LOC | 1499 |
| QualifiedName entries | 5 (`mcpServer`, `serveMcp`, `mcpConnect`, `mcpDisconnect`, `mcpListTools`) |
| NativeContext methods | **5** (`ctx.invokeCallback`, `ctx.registerRoute`, `ctx.registerWsRoute`, `ctx.out`, `ctx.headless`) |
| JVM deps | `scalascript.mcp.*` (own submodule) + `runtime-server-common` |
| `.ssc` surface today | partial: `std/mcp/server.ssc`, `std/mcp/client.ssc` |
| In-tree tests | 20 test files, ~1500 LOC |

Mcp is the largest family but has the fewest `QualifiedName` entries —
the bulk of the code is the callback dispatch machinery inside the 5
handlers.  The 5 ctx methods overlap with Http's set, so Mcp should
migrate **after** Http to reuse the state-bag pattern Http establishes.

The `scalascript.mcp.*` submodule is already a separate sbt project —
it ships straightforwardly as a JAR dependency in `sscpkg.yaml`.

**Test migration**: 1500+ LOC of tests across 20 files is the largest
single test-migration effort.  Plan for a dedicated PR that does
nothing but move + rewrite the test harness before the code migration
PR.

**Examples sweep**: `mcp-server-protected.ssc`, `mcp-agent.ssc`,
`mcp-client-discover.ssc`, `mcp-filesystem-server.ssc`,
`mcp-keyvalue-server.ssc`, `mcp-search-server.ssc`.

---

### 4.10 ToolkitDsl — delete, not migrate

| | |
|---|---|
| Source | `intrinsics/ToolkitDsl.scala` |
| LOC | 292 |
| QualifiedName entries | 2 (`tk.serve`, `tk.emit`) + `buildTkNamespace` |
| NativeContext methods | **none** |
| JVM deps | `frontend-toolkit` module |

`ToolkitDsl.scala` is not migrated — it is **deleted** when the toolkit
port (deferred; see `docs/frontend-toolkit-spec.md` Phase 7a) resumes
and ships `pkg:scalascript/ui-toolkit:1.0`.  At that point the 25+ `tk.*`
widgets become `.ssc` library code on top of the 9 frontend extern defs,
and `ToolkitDsl.scala` is replaced by `ui-toolkit.sscpkg`.

Until then it stays in-tree unchanged.

---

### 4.11 Jdbc — last, blocked

| | |
|---|---|
| Source | `intrinsics/Jdbc.scala` |
| LOC | 48 |
| QualifiedName entries | 3 (`DriverManager.getConnection` — 2 arity overloads + error) |
| NativeContext methods | **none** |
| JVM deps | `java.sql` (stdlib); JDBC driver JARs are user-supplied |
| `.ssc` surface today | none |
| In-tree tests | `JdbcSpec.scala` |
| **Blocker** | `Interpreter.runSqlBlock` reads `globals.get("Connection")` directly |

`Jdbc.scala` itself is tiny (48 lines, 1 function).  The blocker is not
in it — it is in the interpreter's `sql` block handler
(`Interpreter.runSqlBlock`), which resolves `Connection` from the
interpreter's internal global map rather than through any SPI.  A
plugin cannot hook into this path.

**Required refactor before Jdbc migrates**:

1. Introduce a `SqlBlockRunner` SPI method on `NativeContext` (or
   use the state-bag to store a `Connection → sql-executor` hook).
2. Move the `runSqlBlock` logic into the Jdbc plugin so the connection
   the user bound with `DriverManager.getConnection(…)` is threaded
   through the plugin's executor, not the interpreter's global map.
3. The `sql { ... }` fenced-block language feature (`blockLanguages =
   Set(Lang.Sql)` in `InterpreterCapabilities`) can then become a
   generic block-dispatch mechanism that routes to the registered
   Jdbc executor.

This is a non-trivial refactor.  Jdbc stays in-tree until it is done.

---

## 5. The bundled-stdlib question

The lean-core decision means there is no bundled stdlib for Http, Json,
Auth, etc.  Users must install them.  The distribution chain in v1:

```
Release build → plugins/ directory (one .sscpkg per family)
                      ↓
          ssc install scalascript/json:1.0
                      ↓
          ~/.scalascript/registry/scalascript/json/1.0/json-plugin.sscpkg
                      ↓
          [jsonParse](pkg:scalascript/json:1.0) in script → LocalRegistry lookup
```

**Meta-plugin proposal** — `pkg:scalascript/std:1.0` is a meta-plugin
that depends on all migrated families except Mcp and Jdbc:

```yaml
# std.sscpkg manifest
id: scalascript/std
version: 1.0.0
dependencies:
  - scalascript/json:1.0
  - scalascript/request:1.0
  - scalascript/frontend:1.0
  - scalascript/auth:1.0
  - scalascript/oauth-client:1.0
  - scalascript/ws:1.0
  - scalascript/oauth:1.0
  - scalascript/http:1.0
```

Installing `ssc install scalascript/std:1.0` restores the current
behavior for users who want the full battery.  This is proposed but
flagged as open: it introduces a version-synchronization problem (bumping
one family may require cutting a new std meta-package).

---

## 6. Test migration strategy

**Status: PARTIAL** — `runtime/backend/test-utils` now provides
`TestInterpreter(plugins = List(...))`, installs only the explicit
`Backend` instances supplied by a test, and has a self-test with a fake
intrinsic plugin. `std/*-plugin` directories still have no `src/test/` trees;
plugin correctness is still primarily covered by the main conformance suite
(which loads all plugins via ServiceLoader).  See §11 for the remaining
per-plugin migration entry.

Each `.sscpkg` is a self-contained sbt subproject with its own test suite.

**Harness pattern**:

```scala
// json-plugin/src/test/scala/JsonPluginSpec.scala
class JsonPluginSpec extends AnyFunSuite:
  val plugin = JsonInterpreterPlugin()  // Backend impl from the plugin JAR
  val interp  = TestInterpreter(plugins = List(plugin))

  test("roundtrip") {
    val result = interp.eval("""jsonParse(jsonStringify(List(1,2,3)))""")
    assertEquals(result, List(1L, 2L, 3L))
  }
```

`TestInterpreter` is a thin wrapper around the main interpreter that
accepts a `plugins` list, merges their `NativeImpl` intrinsics, disables
fallback ServiceLoader loading for that interpreter instance, and returns
result values unwrapped to plain Scala values where practical. It lives in
the `test-utils` submodule shared by future plugin test suites.

**What happens to in-tree tests**:

| Phase | Action |
|---|---|
| Plugin migration PR lands | Move `*Spec.scala` into the plugin's `src/test/` |
| Tests that import interpreter internals directly | Rewrite against `TestInterpreter` SPI |
| In-tree test file | Delete after migration PR is merged; `grep` for residual references in CI |

**No double-run**: once a family migrates, its in-tree test file is
deleted.  CI runs `sbt test` on the in-tree module and `sbt <plugin>/test`
on the plugin — not both on the same family.

---

## 7. Examples sweep

**Status: OPEN** — examples and conformance tests currently rely on
ServiceLoader classpath-level discovery (all plugins on classpath at test
time).  No `pkg:` import lines have been added to any example yet.  See §11
for the open-item entry.

94 `.ssc` files live under `examples/`.  Each family PR must audit which
examples use its entry points and add explicit `pkg:` import lines.

**Template** (Json pilot):

Before:
```scalascript
val obj = jsonParse("""{"name":"Alice"}""")
```

After:
```scalascript
[jsonParse](pkg:scalascript/json:1.0)

val obj = jsonParse("""{"name":"Alice"}""")
```

**Known examples per family** (non-exhaustive; full audit deferred to
each migration PR):

| Family | Examples to update |
|---|---|
| Json | `dsl-json-parser.ssc`, any script with inline `jsonParse` |
| Auth | `auth-demo.ssc`, `auth-full.ssc`, `webauthn-demo.ssc` |
| Http | `health-defaults.ssc`, all server examples |
| OAuth | `oauth-as-standalone.ssc`, `oauth-mcp-full-stack.ssc`, `oauth-rs-guard.ssc`, `oauth-rsa-jwks.ssc`, `oidc-idp.ssc`, `oidc-login-flow.ssc` |
| Mcp | `mcp-server-protected.ssc`, `mcp-agent.ssc`, `mcp-client-discover.ssc`, `mcp-filesystem-server.ssc`, `mcp-keyvalue-server.ssc`, `mcp-search-server.ssc` |

---

## 8. Rollback story

Migration PRs are atomic: source removal + plugin archive + examples
update + test deletion in one commit (or squashed series).  Rollback
procedure:

1. Revert the migration PR.
2. The `++ FamilyIntrinsics` line reappears in
   `InterpreterCapabilities.scala`.
3. `sbt compile` rebuilds the interpreter with the in-tree family
   re-included.
4. The `pkg:` import lines added to examples become harmless no-ops
   (the `pkg:` resolver logs a warning but falls back to the bundled
   intrinsic).  Or they can be reverted too.

---

## 9. Migration sequence and dependencies

```
Phase 0 (blocking):  ✅ ALL LANDED (2026-05-21)
  ✅ BuiltinsRuntime plugin-merge patch (§3.1)
  ✅ pkg: URI in ImportResolver (§3.3)
  ✅ LocalRegistry seeding + ssc install (§3.4)
  ⬜ test-utils TestInterpreter harness (§6)  ← still open (see §11)

Phase 1 (no NativeContext, no JVM deps):  ✅ LANDED (2026-05-21)
  ✅ Json (§4.1) → ✅ Frontend (§4.2) → ✅ Request (§4.3)

Phase 2 (JVM deps, no NativeContext):  ✅ LANDED (2026-05-21)
  ✅ Auth (§4.4) → ✅ OAuthClient (§4.5) → ✅ Ws (§4.6)
  Note: interpreter-server extraction deferred; Ws depends on
  backend/interpreter/src/main/scala/scalascript/server/ via classpath.

Phase 3 (ctx.invokeCallback only):  ✅ LANDED (2026-05-21)
  ✅ OAuth (§4.7)

Phase 4 (Http + Mcp):  ✅ LANDED (2026-05-21)
  Note: state-bag (§3.2) was NOT required; Http migrated via existing
  named NativeContext methods.
  ✅ Http (§4.8)
  ✅ Mcp (§4.9)

Phase 5 (deferred):
  ✅ ToolkitDsl: deleted (§4.10)
  ⬜ Jdbc: blocked on Interpreter.runSqlBlock refactor (§4.11, see §11)
```

Effort estimates (actual):

| Phase | Status | Notes |
|---|---|---|
| Phase 0 (3 of 4 items) | ✅ Landed | TestInterpreter harness open |
| Phase 1 (Json + Frontend + Request) | ✅ Landed | — |
| Phase 2 (Auth + OAuthClient + Ws) | ✅ Landed | interpreter-server not extracted |
| Phase 3 (OAuth) | ✅ Landed | — |
| Phase 4 (Http + Mcp) | ✅ Landed | state-bag not needed |
| Phase 5 (ToolkitDsl delete) | ✅ Landed | — |
| Phase 5 (Jdbc) | ⬜ Blocked | runSqlBlock refactor required |

---

## 10. Open questions

1. **`pkg:scalascript/std:1.0` meta-plugin**: real package or
   convenience doc concept?  If real, who maintains version
   synchronization across the 8 member packages?  Still open.

2. **`ssc install` network access**: v1 is local-only (registry
   pre-seeded by `setup.sh`).  When does it gain the ability to pull
   from a remote registry, and what is the registry protocol?

3. **QualifiedName conflicts**: Auth and OAuth share some entry-point
   names (e.g., `signJwt`, `verifyJwt`).  If both are installed, the
   last one registered wins.  Defers to `plugin-architecture.md §8`.

4. **Version contract**: are plugin versions semver, calver, or
   content-hash pinned?  This affects the `pkg:` URI syntax accepted
   by the import resolver.

5. **`runtime-server-common` in multiple plugin archives**: Auth, Ws,
   OAuth, Http, and Mcp each bundle their own copy.  Works for v1 but
   risks classloader isolation issues with differing versions.  A
   shared-dependency mechanism is deferred.

6. **`backend/interpreter/src/main/scala/scalascript/server/`
   extraction**: Ws and Http plugins depend on these classes via classpath
   (interpreter subproject), not via a clean `dependsOn`.  Moving them to
   `runtime-server-interpreter` is cleanup deferred to §11.  Name, sbt
   coordinates, and scope (interpreter-only vs. shared with JVM backends)
   are not yet decided.

---

## 11. Post-migration open items

The Phase 0–4 + Phase 5 (ToolkitDsl) migration is complete.  The following
items were explicitly not in scope and remain open.  They are tracked here and
in `MILESTONES.md` (post-migration follow-ons block).

### 11.1 Plugin test harness (`test-utils` + per-plugin `src/test/`)

`runtime/backend/test-utils` now provides:

```scala
class TestInterpreter(plugins: List[Backend]):
  def eval(snippet: String): Any   // runs snippet, returns unwrapped Value
```

Each plugin then gets a `src/test/` with a `*PluginSpec` that tests its
intrinsics in isolation, without depending on the full conformance suite.
The old `backendInterpreter / Test -> std-plugin` project dependency has been
split out into `backendInterpreterPluginTests`, a test-only module that owns the
legacy plugin-backed interpreter tests.  `backendInterpreter` now stays free of
std-plugin project references, so individual std plugins may add
`src/test/` suites via `testUtils % Test` without creating an sbt cycle.
`graphPlugin` and `jsonPlugin` are the first migrated plugin suites.

**Effort remaining**: migrate the remaining plugin families one at a time.

### 11.2 Examples `pkg:` import sweep

No `pkg:scalascript/...` import lines exist in any example or conformance
test yet.  They rely on ServiceLoader auto-discovery.  For production
distribution hygiene (minimal-interpreter deployments), ~20–30 affected
`.ssc` files under `examples/` should declare explicit imports.

**Effort**: S (mechanical — grep + sed per family, one PR).

### 11.3 Jdbc `runSqlBlock` refactor (§4.11)

`Interpreter.scala` still wires `sql { }` blocks directly through
`ConnectionRegistry` / `sqlRegistry`.  Required before Jdbc can be a true
plugin:

1. Add `SqlBlockRunner` SPI method to `NativeContext` (or use state-bag).
2. Move `runSqlBlock` logic into the Jdbc plugin so the plugin owns the
   connection and the executor.
3. Make `sql { }` block dispatch generic (routes to registered executor).

**Effort**: M (non-trivial; touches interpreter block-execution path).

### 11.4 `NativeContext` state-bag

Add `featureGet[A](key: String): Option[A]` and `featureSet[A](key: String,
value: A): Unit` to `NativeContext`.  This lets future plugins store feature
state without amending the SPI trait.  Http and Mcp can then migrate their
21 / 5 named methods to the bag as a follow-on cleanup.

**Effort**: S (trait change) + M (migration of Http + Mcp surfaces).

### 11.5 `interpreter-server` extraction

Move `backend/interpreter/src/main/scala/scalascript/server/` (`WebServer`,
`WsConnection`, `WsProxy`, `WsRoutes`, `TlsProxy`, `WsClientSession`,
`WsRateLimiter`, `InterpreterHttpHandler`) into a new
`runtime-server-interpreter` sbt subproject.  Ws and Http plugins then
declare it as a proper sbt `dependsOn` rather than relying on being bundled
with the interpreter on the classpath.

**Effort**: M (refactor; no behavior change).
