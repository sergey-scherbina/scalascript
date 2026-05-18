# MCP — Model Context Protocol support

Status: **design / planning**.  Implementation tracked as
**v1.17 — MCP support** in MILESTONES.md.  Separate namespace
(`std/mcp/*`), separate module set, no overlap with the
existing REST stack.

Companion to [`docs/backend-spi.md`](backend-spi.md) §8
(platform intrinsic mechanism), [`docs/error-handling.md`](error-handling.md)
§2.5.6 (typed throw / try-catch in direct-syntax — used for
MCP errors), and [`docs/final-tagless.md`](final-tagless.md)
(v1.13 prerequisite — typeclass UX for MCP server callbacks).

## 1. What MCP is, briefly

MCP (Model Context Protocol) is Anthropic's JSON-RPC 2.0-based
protocol for connecting LLM applications to external tools,
resources, and prompts.  Two participants:

- **Server**: exposes Tools (functions the LLM can call),
  Resources (data the LLM can read), and Prompts (templates
  the LLM can substitute).
- **Client**: usually an LLM app; consumes Server-exposed
  primitives during a conversation.

Three transport modes: **stdio** (most common; spawned
subprocess), **HTTP+SSE** (for remote/network servers), and
**WebSocket** (newer, less common).

Standard SDKs exist for: TypeScript/JS (`@modelcontextprotocol/sdk`),
Python (`mcp`), Java (`io.modelcontextprotocol:sdk`), C#,
Kotlin, Swift, Rust, Go.  ScalaScript leans on the JS and
Java SDKs as platform intrinsics; the interpreter ships
without MCP support in v1 (deferred — see §7).

## 2. Motivation

LLM tooling is becoming a real workload.  Real `.ssc` apps
that want to:

- **Expose data to an LLM** (a CRM, a knowledge base, a
  documentation site) need a server that the LLM client
  connects to.
- **Consume tools from other servers** (web search,
  filesystem, git, database) need a client.
- **Build agents** (chains of LLM calls that use multiple
  tools) need both.

Today none of these are possible from `.ssc` without shelling
out to a Python/TypeScript MCP server and IPC'ing into it.
That's awkward and breaks the cross-platform story.

This milestone closes the gap by shipping `std/mcp/server` and
`std/mcp/client` as first-class library modules, with backend
intrinsics that wrap the platform-native SDK on JVM and JS.

## 3. Decided design — REST-like API shape

MCP server APIs follow the **same shape as the existing REST
surface** (`route("GET", "/path")(handler)`).  Users already
know this pattern; JSON-RPC 2.0 is RPC-shaped which fits
handler-per-method naturally; the cross-cutting concerns
(transport, lifecycle, error handling) translate cleanly.

```scala
// Server
mcpServer { srv =>
  srv.tool("get_weather") { args =>
    val city = requireString(args, "city")
    Tool.text(s"Weather in $city: sunny, 22°C")
  }

  srv.resource("file:///readme.md") { uri =>
    Resource.text(loadFile("./README.md"), mimeType = "text/markdown")
  }

  srv.prompt("greet") { args =>
    val name = requireString(args, "name")
    Prompt.messages(
      Message.user(s"Hello, please greet $name warmly.")
    )
  }
}

println("MCP server listening on stdio")
serveMcp(transport = Transport.stdio)
```

```scala
// Client
val client = mcpConnect(Transport.spawn("node", "weather-server.js"))
val tools  = client.listTools()
val result = client.callTool("get_weather", Map("city" -> "Berlin"))
println(result.content.text)
client.close()
```

### Why REST-shaped, not typeclass-shaped

Alternative considered: type-class / Final-Tagless API where
each tool is a `given` instance of `McpTool[Args, Result]`.

Rejected for v1 because:

- **Consistency**: users already use `route` / `onWebSocket` /
  `serve` — `srv.tool` / `srv.resource` / `serveMcp` mirror
  this exactly.  Two APIs for "register handler, run server"
  would be inconsistent.
- **Dynamic registration**: MCP servers commonly register
  tools at runtime (based on config, plugins, environment).
  Type-class approach requires compile-time `given`
  declarations, which doesn't fit the dynamic case.
- **Args are JSON-shaped**: MCP tool args arrive as
  JSON-Schema-validated JSON.  Map-based access
  (`requireString(args, "city")`) lines up with the v1.15
  `req.json` / `req.require[T]` pattern from REST.

Type-class can be a v1.17.1 add-on (define an `McpTool[A, R]`
typeclass and have `srv.tool[A, R](impl)` derive from
`given`), but not the v1 surface.

### Why a separate namespace (`std/mcp/*`)

Per user direction.  Three reasons:

1. **Optional**: not every `.ssc` app needs MCP.  Keeping it
   in `std/mcp/` keeps the default import set lean.
2. **Backend feature flag**: MCP is `Feature.McpServer` /
   `Feature.McpClient` per SPI §8.  Backends that don't
   support it (interpreter, browser-SPA) reject the import
   with an actionable message rather than silently breaking
   at runtime.
3. **Versioning**: MCP itself is a protocol that's evolving.
   Isolating in a namespace lets us track protocol versions
   (`std/mcp/v1`, `std/mcp/v2` later) without renaming
   primary std modules.

## 4. Server-side API

Full `std/mcp/server.ssc` surface (decided portion):

```scala
// Three primitives, all built from the same handler shape
trait McpServer:
  def tool(name: String, description: String = "")
          (handler: Map[String, Any] => ToolResult): Unit

  def resource(uri: String, name: String = "", mimeType: String = "")
              (handler: String => ResourceResult): Unit

  def prompt(name: String, description: String = "")
            (handler: Map[String, Any] => PromptResult): Unit

  // Lifecycle
  def onConnected(handler: () => Unit): Unit
  def onDisconnected(handler: () => Unit): Unit

// Construction
def mcpServer(setup: McpServer => Unit): Unit
def serveMcp(transport: Transport): Unit

// Transport variants
enum Transport:
  case stdio
  case http(port: Int, path: String = "/mcp")
  case ws(port: Int, path: String = "/mcp")
  case spawn(cmd: String, args: List[String])  // client side; here for symmetry

// Result types
case class ToolResult(content: List[Content], isError: Boolean = false)
case class ResourceResult(uri: String, contents: List[Content])
case class PromptResult(messages: List[Message])

enum Content:
  case Text(text: String)
  case Image(data: Array[Byte], mimeType: String)
  case Resource(uri: String)

case class Message(role: Role, content: Content)
enum Role: case User, Assistant, System

object Tool:
  // Convenience constructors
  def text(s: String): ToolResult = ToolResult(List(Content.Text(s)))
  def error(msg: String): ToolResult = ToolResult(List(Content.Text(msg)), isError = true)

object Resource:
  def text(s: String, mimeType: String = "text/plain"): ResourceResult = ...

object Prompt:
  def messages(msgs: Message*): PromptResult = PromptResult(msgs.toList)
```

### Tool argument validation

Reuses v1.15's `req.require[T]` pattern adapted to `Map[String, Any]`:

```scala
srv.tool("calculate") { args =>
  val a = requireDouble(args, "a")    // throws McpError if missing/invalid
  val b = requireDouble(args, "b")
  val op = requireString(args, "op")
  Tool.text(s"${op match { case "+" => a+b; case "-" => a-b }}")
}
```

`requireString` / `requireInt` / `requireDouble` /
`requireBool` accept `Map[String, Any]` as their first
argument (overloaded from the v1.15 `Request`-flavoured
versions).  Missing or wrong-type values surface as
`McpError("missing field: ...")` automatically converted to
a tool error response (`isError = true`) by the runtime.

### Server with direct-syntax

```scala
srv.tool("user_summary") { args =>
  direct[Either[McpError, ToolResult]] {
    id      = requireInt(args, "user_id")          // monadic bind on throws
    user    = throws_attempt { loadUser(id) }      // External call wrapped in throws
    orders  = throws_attempt { loadOrders(id) }
    Tool.text(s"${user.name}: ${orders.size} orders")
  } match
    case Right(r) => r
    case Left(e)  => Tool.error(e.message)
}
```

`throws_attempt { … }` is a hypothetical helper that lifts
v1.15 `throws`-typed calls into the surrounding `direct[Either[McpError, *]]`.
Cross-monad lift; depends on v1.15 + v1.13.

## 5. Client-side API

Full `std/mcp/client.ssc` surface:

```scala
trait McpClient:
  // Discovery
  def listTools(): List[ToolDescriptor]
  def listResources(): List[ResourceDescriptor]
  def listPrompts(): List[PromptDescriptor]

  // Invocation
  def callTool(name: String, args: Map[String, Any]): ToolResult
  def readResource(uri: String): ResourceResult
  def getPrompt(name: String, args: Map[String, Any]): PromptResult

  // Async variants (for direct-syntax over Async)
  def callToolAsync(name: String, args: Map[String, Any]): Async[ToolResult]
  def readResourceAsync(uri: String): Async[ResourceResult]
  def getPromptAsync(name: String, args: Map[String, Any]): Async[PromptResult]

  // Lifecycle
  def close(): Unit
  def isClosed: Boolean

// Construction
def mcpConnect(transport: Transport): McpClient
def mcpConnect(transport: Transport, timeout: Long): McpClient  // ms

// Descriptors (what the server advertised)
case class ToolDescriptor(name: String, description: String, schema: Map[String, Any])
case class ResourceDescriptor(uri: String, name: String, mimeType: String)
case class PromptDescriptor(name: String, description: String, args: List[ArgSpec])
case class ArgSpec(name: String, type_ : String, required: Boolean)
```

### Client with direct-syntax

```scala
def fetchWeather(city: String): Async[String] = direct[Async] {
  client = mcpConnect(Transport.spawn("node", "weather-server.js"))
  result = client.callToolAsync("get_weather", Map("city" -> city))
  _      = client.close()
  result.content.head match
    case Content.Text(t) => t
    case _               => "no text"
}
```

Async variants integrate with the existing `Async` effect
(v1.11 post-rewrite) so a client call inside an HTTP handler
doesn't block the request thread.

## 6. Implementation strategy

**Intrinsic-first**, per user direction.  Each platform that
has a standard MCP SDK gets an intrinsic that wraps it.
Platforms without a standard SDK defer to a v1.17.x own-
implementation milestone.

### 6.1 JS (Node) backend — intrinsic

Wraps `@modelcontextprotocol/sdk` (npm package).  The
JsGen-emitted code does `const { Server } = require('@modelcontextprotocol/sdk/server/index.js')`
and registers handlers through the SDK's API.

User-side `srv.tool("name") { args => … }` lowers to:

```js
server.setRequestHandler(CallToolRequestSchema, async (req) => {
  if (req.params.name === "name") {
    const args = req.params.arguments;
    const result = handlerFn(args);
    return { content: result.content.map(toMcpContent) };
  }
});
```

Transport selection drives the SDK constructor:

| `Transport.stdio` | `StdioServerTransport()` |
| `Transport.http(p, path)` | `SSEServerTransport(...)` |
| `Transport.ws(p, path)` | `WebSocketServerTransport(...)` (newer SDK) |

Estimate: ~1 week (JS-codegen plumbing + handler-lowering +
two transports + tests).

### 6.2 JVM backend — intrinsic

Wraps the official Java MCP SDK (`io.modelcontextprotocol:sdk`).
JvmGen-emitted Scala uses the Java SDK's builder pattern:

```scala
val server = McpServer.create()
  .withTool("name", schema, args => handlerFn(args))
  .withResource(uri, name, mimeType, uri => handlerFn(uri))
  .withPrompt(name, args => handlerFn(args))
  .build()
server.start(transport)
```

Estimate: ~4 days.  Java SDK is heavier than the JS one
(more setup, schema validation), but the wrapping pattern is
straightforward.

### 6.3 Interpreter backend — **deferred**

The interpreter doesn't host a standard MCP SDK and writing
our own JSON-RPC 2.0 stack adds ~1500 LOC.  Per user
direction, defer to v1.17.x.

For v1: importing `std/mcp/server` on the interpreter
backend raises a Feature-not-supported error at typecheck
time (per SPI §8 capabilities).  Users see:

```
error: std/mcp/server requires Feature.McpServer.
  Available on: jvm, js
  Not available on: interpreter, scalajs-spa
```

This is the standard SPI feature-flag failure mode — not a
runtime surprise.

### 6.4 Browser-SPA (scalajs) backend

Same as interpreter — Feature.McpServer not supported.
Client side (`std/mcp/client`) might work in-browser over
HTTP+SSE transport in a future iteration, but server-side
inside a browser doesn't make sense.

## 7. Backend feature flags (per SPI §8)

Two new flags, declared by each backend:

```scala
enum Feature:
  case McpServer       // can host an MCP server
  case McpClient       // can connect as an MCP client
```

| Backend | McpServer | McpClient |
|---------|-----------|-----------|
| jvm     | ✅ (intrinsic) | ✅ (intrinsic) |
| js (Node) | ✅ (intrinsic) | ✅ (intrinsic) |
| interpreter | ❌ (deferred) | ❌ (deferred) |
| scalajs-spa | ❌ | ❌ (HTTP+SSE in browser plausible later) |

Capability sub-flags for partial coverage:

- `Feature.McpServer.Stdio`
- `Feature.McpServer.HttpSse`
- `Feature.McpServer.WebSocket`

A backend that supports only stdio (e.g., a constrained CI
environment) declares `Feature.McpServer.Stdio` without the
other two.

## 8. Coexistence with the rest of the stack

| ScalaScript feature | Relationship |
|---------------------|--------------|
| **REST stack** (`route`, `serve`) | Parallel — same shape (`tool` ↔ `route`, `serveMcp` ↔ `serve`).  MCP server can run *alongside* a REST server in the same process; different transports, different protocols |
| **WebSocket stack** | One MCP transport option is `Transport.ws(...)`, which shares the existing `onWebSocket` infrastructure on JVM/JS backends |
| **v1.15 `throws[A, E]`** | MCP errors lift to `McpError`; `throws[A, McpError]` is the natural typed channel for tool/resource/prompt handlers |
| **v1.8 direct-syntax** | Async client calls + `throws[A, McpError]` compose inside `direct[Async]` blocks — see §4 and §5 worked examples |
| **v1.13 Final Tagless** | Optional v1.17.1 follow-up: a typeclass-style API (`McpTool[A, R]` derivable from case classes via v1.14 `derives`) layered on top of the REST-shaped surface from §4 |
| **v1.14 `inline` / `derives`** | Once `derives Codec` lands, MCP tool args become typed: `tool[WeatherArgs, WeatherResult]("get_weather") { ... }` automatically derives the JSON-Schema from `WeatherArgs` |

## 9. Implementation phases (v1.17)

Seven phases over ~3 weeks.  JS and JVM intrinsics can be
worktrees in parallel after Phase 1.

### Phase 1 — Types + namespace (~2 days)

- New `std/mcp/types.ssc` with `ToolResult`, `ResourceResult`,
  `PromptResult`, `Content`, `Message`, `Role`, `Transport`,
  `McpError`.
- New `std/mcp/server.ssc` with the `McpServer` trait + factory
  signatures (`mcpServer`, `serveMcp`) — no impl yet.
- New `std/mcp/client.ssc` with the `McpClient` trait + factory
  signatures.
- Pure-types — no runtime dependency.  All three backends can
  see this file.

### Phase 2 — JS server intrinsic (~5 days)

- Codegen: `mcpServer { … } / serveMcp(...)` calls emit
  `@modelcontextprotocol/sdk`-based JS.
- Handler lowering: `srv.tool("x") { args => ... }` becomes
  `server.setRequestHandler(...)`.
- All three transports (stdio, http+sse, ws).
- Conformance: spawn a Node MCP server from `.ssc`, connect
  with the canonical MCP CLI client, exercise tool / resource
  / prompt round-trips.

### Phase 3 — JVM server intrinsic (~4 days)

Same shape as Phase 2 but for the Java SDK.  Uses
`io.modelcontextprotocol:sdk` via `//> using dep` directive
on the JvmGen-emitted script.

### Phase 4 — JS client intrinsic (~3 days)

- `mcpConnect(Transport.spawn(...))` returns an `McpClient`
  backed by `@modelcontextprotocol/sdk/client`.
- Sync + Async variants (Async wraps the SDK promises).
- Conformance: connect to a known MCP server, list tools,
  invoke one, assert response.

### Phase 5 — JVM client intrinsic (~3 days)

Same shape as Phase 4 but for the Java SDK.

### Phase 6 — Feature flags + interpreter rejection (~1 day)

- Declare `Feature.McpServer` / `Feature.McpClient` in the SPI.
- JVM + JS backends advertise.
- Interpreter + scalajs-spa raise actionable typecheck error
  on import.

### Phase 7 — Examples + docs (~2 days)

- `examples/mcp-server-tools.ssc` — three-tool demo on stdio
- `examples/mcp-client-discover.ssc` — connects to a server,
  lists capabilities
- `examples/mcp-agent.ssc` — uses both client and server in
  the same script (a tool that itself calls another MCP
  server)
- `docs/mcp.md` updated with usage walkthroughs

## 10. Hard-no list (locked by design)

| Feature | Reason |
|---------|--------|
| **Own MCP implementation in v1** | Defer to v1.17.x; intrinsic-first per user direction.  Re-evaluate when a backend without an SDK becomes a real target |
| **Type-class API as the primary** (`given McpTool[A, R]`) | REST-shaped is the v1 default; type-class can be a v1.17.1 add-on |
| **Custom transports** (Unix socket, named pipe) | Stick to stdio / HTTP+SSE / WS — those cover the realistic deployment landscape; bespoke transports are an SDK-extension concern |
| **Schema validation in the std layer** | The MCP SDK handles JSON-Schema validation; user-facing args arrive validated.  Std doesn't re-validate |
| **MCP-versioned namespaces** in v1.17 (`std/mcp/v1` vs `std/mcp/v2`) | Single `std/mcp/*` for now; introduce versioned namespaces when MCP protocol versions diverge |
| **Bidirectional sampling** (server → client → LLM → server) | MCP supports it as an advanced feature; defer to v1.17.x once a real consumer needs it |

## 11. Open questions

### 11.1 API shape — REST vs typeclass vs declarative

Decided: REST-shaped for v1 (§3 rationale).  But:

- **Should the type-class layer ship at v1.17.1, v1.18, or never?**
  Depends on whether real apps want typed tool args.  Defer
  the call until v1.17 ships and we see usage.
- **Declarative (front-matter) tool registration**?
  `tools:` in YAML front-matter, auto-wired at startup.
  Cute but inconsistent with the REST-shaped runtime API.
  Probably skip.

### 11.2 Tool argument types — `Map[String, Any]` vs typed

Decided for v1: `Map[String, Any]` with `requireString` /
`requireInt` etc.  Same as v1.15 REST validation.  Open:

- **`tool[WeatherArgs, WeatherResult]("get_weather") { args
  => … }`** typed form via v1.14 `derives Codec`.  Add when
  v1.14 lands.
- **JSON Schema generation from typed args** — derives a
  `JsonSchema` instance, sends it to the SDK at registration.
  Powerful but couples MCP layer to `derives`; consider once
  v1.14 stabilises.

### 11.3 Error handling

Decided: tool/resource/prompt handlers can return
`Tool.error(msg)` for soft errors; throw `McpError(msg)` for
hard errors (lifted to `isError = true` by the runtime).
Open:

- **`throws[ToolResult, McpError]`** as the canonical handler
  return type (v1.15 integration).  Cleaner than the current
  `Tool.error` factory.  Wait until v1.15 stabilises.

### 11.4 Initialization handshake exposure

Decided: hide the MCP init handshake (capabilities exchange,
client info) from user code.  Open:

- **`srv.onInitialize { clientInfo => … }`** hook for apps
  that want to log / restrict / customise per-client?
  Probably yes but defer to v1.17.1 — needs a concrete use
  case to design the right shape.

### 11.5 Streaming responses for large resources

MCP supports streaming resource content for large payloads.
v1 ships **non-streaming only** (one response = one full
payload).  Open:

- **Generator-based streaming** via v1.10 `Generator[T]` —
  `srv.resource("largeData://...") { uri => Generator.of(...).toMcpStream }`.
  Natural fit; tackle in v1.17.x once Generators land.

### 11.6 Connection lifecycle for clients

Decided: `mcpConnect(...)` returns an `McpClient`; user
calls `.close()` explicitly.  Open:

- **`using mcpConnect(...) { client => … }`** RAII-style?
  Cleaner but requires a `using`-resource construct that
  doesn't exist today (separate language feature).
- **Auto-reconnect on transport failure** for long-lived
  clients.  Useful but specific; add when a real consumer
  needs it.

### 11.7 Authentication

MCP itself defines no auth — auth is transport-level (HTTP
Bearer, mTLS, etc.).  For HTTP+SSE transport, we lean on
the existing REST auth stack (`req.headers["authorization"]`).
For stdio, auth doesn't apply (parent process implicitly
trusted).  Open:

- **Server-side per-tool authorisation** (`srv.tool("admin/op") {
  args => if !isAdmin(...) then McpError.forbidden(...) }`) —
  user-implementable today; do we ship a helper?

### 11.8 Logging / observability

Decided: MCP request / response logging hooks into existing
`std/middleware.ssc` via a `withMcpLog` middleware analogue.
Open:

- **`mcpServer` `onRequest` / `onResponse` hooks** for
  structured logging?  Probably; defer specifics to first
  user request.

## 12. Conformance plan

Six tests under `conformance/`, conditional on
`Feature.McpServer` / `Feature.McpClient` (skipped on
interpreter / scalajs-spa):

| Test | Exercises |
|------|-----------|
| `mcp-server-tool.ssc` | Single tool, stdio transport, round-trip via spawned client |
| `mcp-server-resource.ssc` | Resource registration, URI dispatch, content types |
| `mcp-server-prompt.ssc` | Prompt with args, message templating |
| `mcp-server-transports.ssc` | Stdio + HTTP+SSE on the same server (port conflict checks) |
| `mcp-client-discover.ssc` | listTools / listResources / listPrompts against a known server |
| `mcp-client-invoke.ssc` | callTool / readResource / getPrompt round-trip, sync + Async variants |

Each test asserts cross-backend identical observable output
(within the backends that support MCP — JVM and JS).

## 13. v1.17.x — deferred follow-ups

Carry into v1.17.x as separate mini-milestones when needs
emerge:

- **Own implementation for INT / scalajs-spa** — ~1500 LOC
  of JSON-RPC 2.0 stack.  Decide via "real consumer asks"
- **Type-class layer** (`given McpTool[A, R]`, `derives
  McpSchema`) — depends on v1.14
- **Streaming resources** — depends on v1.10 Generators
- **Bidirectional sampling** — MCP advanced feature
- **`using mcpConnect(...) { client => … }` RAII** — needs
  a `using`-resource language feature
- **MCP protocol version negotiation** when v2 emerges
