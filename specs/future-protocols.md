# Future web-services protocols

Status: **planning / not committed**.  This document collects the
four protocols / API-description formats that came up during the
v1.1 milestone review and were *deliberately* set aside, with the
reasoning so the question doesn't have to be re-litigated every
time someone asks "does ScalaScript do X?".

Each section answers four questions:

1. **What it is** (one paragraph).
2. **Why it isn't in scope today** — the concrete blocker.
3. **What would unblock it** — the prerequisite work tracked
   elsewhere in MILESTONES.md or the design step still missing.
4. **Rough effort estimate** when the blocker is gone.

None of these are committed milestones.  None block the work that
*is* tracked (v1.0 WebSocket hardening, v1.5 transport layer, v1.6
actors, etc.).  Each becomes a real milestone the moment a
concrete consumer surfaces.

---

## 1. HTTP/2 (and HTTP/3)

### What it is

HTTP/2 multiplexes many request/response streams over a single TCP
connection with binary framing, header compression (HPACK), and
server push.  HTTP/3 layers the same model on top of QUIC (UDP +
TLS 1.3) for ~2× tail-latency improvement on lossy mobile networks.
For browser-facing APIs, HTTP/2 has been universal client support
since 2018; HTTP/3 since ~2022 in evergreen browsers.

### Why it isn't in scope today

ScalaScript's REST server runs on the JDK's
`com.sun.net.httpserver.HttpServer` (and a NIO HTTP/1.1 path on the
interpreter).  Both speak **HTTP/1.1 only**.  Adding HTTP/2 means
replacing the wire-protocol layer entirely — not flag-flipping
something existing — and HTTP/3 additionally requires a QUIC
implementation (the JDK doesn't ship one as of JDK 24).

### What would unblock it

Sprint 5 #16 in v1.0 — **Full NIO HTTP on JvmGen** — replaces the
JDK `HttpServer` + WS-proxy pair with a single NIO selector loop
owning both HTTP and WS state machines.  Once that lands, the same
NIO loop can be extended to HTTP/2 framing (HTTP/2 is essentially
"HTTP/1.1 messages over a binary multiplexed transport"; the
state machine is well-specified in RFC 7540).  A working ALPN
negotiation is needed too, which the NIO migration would surface
anyway for TLS support (v1.5 Tier 1).

For HTTP/3: blocked behind HTTP/2.  Additionally needs a QUIC
implementation — viable candidates are pulling in
`io.netty.incubator.quic` (Netty-based, requires a native lib for
BoringSSL) or shelling out to a separate HTTP/3 termination
process.  Either is a significant commitment.

### Effort estimate

- **HTTP/2 server, post-NIO migration:** ~1500-2000 LOC for the
  framing layer, HPACK, stream multiplexer, flow control.  ~2-3
  weeks once Sprint 5.16 lands.
- **HTTP/3 server:** open-ended; depends on whether we accept a
  native dependency or stand up a separate process.  Not seriously
  scoped until ≥1 user asks.

### Posture

Wait.  HTTP/2 buys real-world performance gains, but the JDK
`HttpServer` is acceptable behind any reverse proxy (Nginx /
Caddy / Cloudflare) that already terminates HTTP/2 on the public
side.  Standalone HTTP/2 only matters when someone deploys
ScalaScript directly to the internet *and* has the throughput
profile (many concurrent connections, slow clients, header-heavy
traffic) where HTTP/1.1's connection-per-request hurts.  We have
zero such users today.

---

## 2. gRPC

### What it is

gRPC is HTTP/2-transported, protobuf-encoded RPC.  Strongly typed
service contracts in `.proto` files generate stubs in 10+
languages; bidirectional streaming over multiplexed streams; tight
ecosystem with load balancers, deadlines, retries, circuit breakers.
The de-facto choice for internal microservice RPC at >1k-engineer
companies.

### Why it isn't in scope today

Two compounding blockers:

1. **HTTP/2 transport.**  gRPC mandates HTTP/2 (gRPC-over-HTTP/1.1
   does not exist; gRPC-Web is a different protocol that's
   one-shot, not streaming).  We don't have HTTP/2 — see §1.

2. **Protobuf code generation.**  `.proto` files must be compiled
   to ScalaScript stubs.  That means a `.proto` parser + AST +
   type-checker + codegen back to `.ssc` source — or a runtime
   reflection-based message reader (slower, possible).  Both
   represent net-new toolchain work outside ScalaScript's current
   "Markdown + Scala 3 dialect" remit.

### What would unblock it

In order:

1. HTTP/2 (see §1).
2. A protobuf binding strategy.  Two viable shapes:
   - **Codegen via `protoc` plugin** — write a `protoc` plugin that
     emits `.ssc` modules from `.proto` definitions.  Heavy
     up-front, zero runtime overhead, type-safe message handling.
     Same approach Scala's ScalaPB takes.
   - **Runtime-only descriptors** — load `.proto` descriptors at
     startup, use generic `Map[String, Any]` for messages.  Lighter
     to ship, but loses typing — half the point of gRPC.
3. A streaming primitive.  Server streaming, client streaming, and
   bidi streaming map naturally onto actors (v1.6) once those
   land — each stream is an actor with a typed mailbox.  Pre-actors,
   would need a one-off streaming abstraction.

### Effort estimate

Optimistically **~6-8 weeks of focused work** once HTTP/2 lands:
- ~1 week — protoc plugin emitting `.ssc` stubs (or alternative).
- ~2 weeks — runtime: gRPC framing on HTTP/2, deadlines, metadata,
  status codes.
- ~2 weeks — client and server APIs with all four streaming modes.
- ~1 week — interop testing against grpcurl / grpc-go reference.
- ~1 week — docs, examples, conformance.

### Posture

Defer until a concrete user shows up.  REST + WebSockets cover the
vast majority of what gRPC offers (bidi streaming via WS, typed
contracts via shared `case class` definitions or JSON Schema).
The unique gRPC win is the polyglot-stub ecosystem — interop with
Go / Python / Rust services that already speak gRPC.  When the
first ScalaScript app needs to call into such a service, revisit.

A useful **interim** is gRPC-Web with `text/event-stream` framing
over HTTP/1.1 — works without HTTP/2 and covers ~70% of gRPC use
cases for browser clients.  Worth a closer look if a partial story
is requested before HTTP/2 is real.

---

## 3. GraphQL

### What it is

GraphQL is a query language for typed APIs: clients send a single
query specifying exactly which fields of which types they want,
the server runs resolvers per field and returns a tree-shaped
JSON response.  Strong typing (schema in SDL or code-first),
introspection, single endpoint, no over- or under-fetching.
Heaviest adoption: React/Apollo SPAs talking to backend-for-
frontend gateways.

### Why it isn't in scope today

Not blocked by missing infrastructure — blocked by **design
volume**.  Implementing GraphQL properly means:

1. **Schema definition.**  Either parse `.graphql` SDL files, or
   provide a code-first DSL (`schema { query("user", ...) }`).
   Both are real surface area.
2. **Query parser + validator.**  Full GraphQL spec is ~70 pages
   (operations, fragments, variables, directives, subscriptions,
   introspection).  Sub-spec parsers usually fail at the edges
   real apps actually hit.
3. **Execution engine.**  Field-by-field resolver dispatch with
   batched data-loader semantics (DataLoader is the de-facto
   solution to N+1; reimplementing it correctly is non-trivial).
4. **Subscriptions** over WebSocket (graphql-ws protocol or the
   older subscriptions-transport-ws).

The runtime side could ride on existing WebSocket + REST
infrastructure once the parser and execution engine exist; the
parser/executor is the bulk of the work.

### What would unblock it

Nothing in our stack.  GraphQL is a self-contained ecosystem;
it doesn't need HTTP/2 (HTTP/1.1 + WS is fine), it doesn't need
actors (resolvers are usually fine as plain functions with
Promise/Async), it doesn't need new typer features.

The real question is whether we want to own a GraphQL
implementation at all, or wire to an existing one (e.g.,
`graphql-java` on JVM, `graphql-js` on Node).  **Wiring** is
cheaper but creates a per-backend dependency surface — JVM gets
Java's library, JS gets Node's, interpreter gets one of them
through embedding.  **Owning** is ~3-6 months of dedicated work
to match the maturity of established implementations.

### Effort estimate

- **Wire to existing implementations:** ~2-3 weeks for the
  bindings + DSL + WS subscription proxy, per backend.
- **Native implementation:** months.

### Posture

Defer.  The honest take is GraphQL is best layered on top of a
runtime by an opinionated library team that picks all the trade-
offs (code-first vs schema-first, federation, subscriptions
protocol, batched-resolver caching, persisted queries).  That
shape doesn't fit "core ScalaScript feature" — it fits "first
external library that earns its keep".  When somebody ships
`scalascript-graphql` as a downstream package on top of the v0.7
import / registry surface (once that lands), they can make the
opinionated decisions.

---

## 4. OpenAPI schema export

### What it is

OpenAPI (formerly Swagger) is a JSON / YAML format describing
HTTP APIs: paths, methods, request/response schemas (JSON Schema),
auth requirements, examples.  De-facto standard for API
documentation; consumed by Swagger UI, Postman, openapi-generator
(client codegen in 50+ languages), and AWS / Azure / GCP API
gateways.

### Why it isn't in scope today

Not blocked, but requires concrete decisions:

1. **Where the schema comes from.**  Two options:
   - **Hand-written** alongside the code — duplicate truth that
     drifts on every change.  Common in lightweight frameworks;
     low value.
   - **Derived from code** — walk the registered `route()` calls
     plus their handlers' input/output shapes (`req.json[T]`,
     `req.require[T]`, returned `case class`es).  Requires the
     typer to know enough about each handler to extract schema —
     possible today since the typer already sees them, but the
     full derivation is non-trivial.
2. **JSON Schema dialect.**  OpenAPI 3.0 uses an older JSON
   Schema dialect; OpenAPI 3.1 uses JSON Schema 2020-12.  Pick
   one.  3.1 is current; 3.0 has wider tooling support today.
3. **Auth annotations.**  Today auth is invoked inside handler
   bodies (`if !validate(req) then return Response.status(401)`).
   The schema needs to know "this route requires bearer token"
   without analyzing handler bodies — likely a per-route
   annotation or a middleware-shape (Tier 5 #18 in v1.5).
4. **Streaming + WebSocket coverage.**  OpenAPI 3.1 has weak
   support for SSE; AsyncAPI is the parallel format for
   event-driven / WS APIs.  We'd ship OpenAPI for REST and
   maybe AsyncAPI for WS in a second cut.

### What would unblock it

The Tier 5 milestone (REST server ergonomics, v1.5) lands two of
the prerequisites:

- **Typed request validation** (#20) — `req.require[T]("field")`
  carries enough type info to derive request schema entries.
- **Typed JSON read** (#17 follow-on `req.jsonAs[T]`) — same for
  request body.

The third prerequisite is the **middleware-shape auth
annotation** (Tier 5 #18) — once `authMw { route(...) }` is
conventional, the schema extractor walks the middleware stack
to find required-auth tags.

### Effort estimate

- **Hand-written schema served from a static route:** ~10 LOC
  per backend, no real work.
- **Auto-derived from registered routes + handlers:** ~600 LOC
  one-time + ~100 LOC × 3 for per-backend serialization.  ~1
  week once Tier 5 #17 + #20 land.
- **Swagger UI bundled and served at `/_swagger`:** ~1 day, just
  unpack the static assets behind a built-in route.

### Posture

Most achievable of the four.  Once v1.5 Tier 5 lands and brings
the typed request surface, the schema generator is a natural
follow-up — every typed API ships with self-documenting JSON,
and Swagger UI at `/_swagger` is genuinely useful for development.
If somebody wants to push this, it's the smallest commitment of
the four and pays back the fastest.

---

## Cross-cutting decision: ScalaScript's "lane"

The recurring theme across all four: ScalaScript is positioned as
a **lightweight Markdown-in-a-Scala-dialect for rapid web apps**,
not as a heavyweight enterprise RPC platform.  Each of these
protocols — HTTP/2, gRPC, GraphQL, OpenAPI — fits a "big
backend" world that doesn't yet have ScalaScript users.  When
those users show up, the prerequisites are tracked (NIO
migration, Tier 5 typing), and the work becomes concrete instead
of speculative.

Until then: **REST + WebSockets + auth (v1.0 - v1.5) + actors
(v1.6) cover the realistic surface area of what a ScalaScript app
needs.**  These four documents exist so the question is
asked-and-answered, not so the work starts.
