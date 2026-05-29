# GraphQL contract platform - spec

**Status:** Phases 1, 2 (partial), 4, 5, 6, 8, 10, 11 implemented on `main` (2026-05-29).
94 graphql-plugin tests total. Phase 3 (WebSocket subscriptions) is next.

**External references:** as of 2026-05-29, `https://spec.graphql.org/` lists
GraphQL **September 2025** as the latest released GraphQL specification and a
November 2025 working draft. `https://graphql.github.io/graphql-over-http/`
lists a GraphQL-over-HTTP prerelease working draft dated 2026-05-28. The HTTP
draft extends GraphQL for transport interoperability; it does not replace the
core GraphQL language, type-system, validation, execution, or response
semantics.

**Companions:**
- [`docs/future-protocols.md §3`](future-protocols.md)
- [`docs/openapi.md`](openapi.md)
- Future AsyncAPI/realtime transport spec for WebSocket/SSE event streams

---

## 1. Goals

ScalaScript should treat GraphQL as a first-class typed contract layer, not
only as a convenience endpoint. A ScalaScript app should be able to publish,
validate, consume, and test a GraphQL contract from the same `.ssc` source that
defines the application logic.

The desired developer experience starts simple:

~~~ssc
---
name: my-api
---

# Schema

```graphql
type Query {
  user(id: ID!): User
  users: [User!]!
}

type Mutation {
  createUser(input: CreateUserInput!): User!
}

type Subscription {
  userCreated: User!
}

input CreateUserInput {
  name: String!
  email: String!
}

type User {
  id: ID!
  name: String!
  email: String!
}
```

# Resolvers

```scalascript
val resolvers = GraphQL.resolvers(
  query = Map(
    "Query.user" -> ((ctx: GraphQLContext, args: Map[String, Any]) =>
      db.findUser(args("id").toString)),
    "Query.users" -> ((ctx: GraphQLContext, args: Map[String, Any]) =>
      db.allUsers())
  ),
  mutation = Map(
    "Mutation.createUser" -> ((ctx: GraphQLContext, args: Map[String, Any]) =>
      db.createUser(args("input").as[CreateUserInput]))
  ),
  subscription = Map(
    "Subscription.userCreated" -> ((ctx: GraphQLContext, args: Map[String, Any]) =>
      userCreatedSource)
  )
)

serveGraphQL(4000, resolvers)
```
~~~

Longer term, the same contract should support:

- schema-first SDL in `graphql` fenced blocks and external `.graphql` files;
- typed resolver bindings keyed by GraphQL schema coordinates such as
  `Query.user` rather than ambiguous field names;
- GraphQL-over-HTTP compliant server and client behavior;
- typed ScalaScript request/response mapping for arguments, input objects,
  output objects, custom scalars, enums, interfaces, unions, and errors;
- generated typed clients for frontend/backends that consume a GraphQL service;
- compile-time validation of schema and client operations;
- persisted operations / APQ-style manifests for production clients;
- query limits, timeouts, auth context, introspection policy, and observability;
- subscription/realtime transports over WebSocket first, with SSE or multipart
  incremental delivery as optional later transports;
- schema diffing and contract tests so GraphQL API changes can be reviewed
  with the same discipline as OpenAPI changes.

The source of truth is the GraphQL schema plus typed ScalaScript resolver and
operation metadata. Runtime libraries execute GraphQL; ScalaScript owns the
developer-facing DSL, type mapping, code generation, plugin packaging, and
contract workflow.

---

## 2. Non-goals

- **Building a GraphQL engine from scratch.** ScalaScript wraps mature engines:
  `graphql-java` on JVM/interpreter/JVM-generated paths and `graphql-js` for
  Node output. We do not implement GraphQL parsing, validation, execution, or
  introspection ourselves unless a small adapter is unavoidable.
- **Replacing REST/OpenAPI.** GraphQL and REST solve different API-shape
  problems. ScalaScript should support both, and mixed servers must be normal.
- **Making GraphQL core depend on a specific database.** Resolvers call user
  code, typed mapping helpers, SQL, Spark, streams, actors, or any other
  ScalaScript capability; the GraphQL layer must not own persistence.
- **Federation in the core plugin.** Federation, schema stitching, and gateways
  are important but should land as separate optional plugins after the base
  contract is solid.
- **All experimental transport work in Phase 1.** WebSocket subscriptions,
  SSE, multipart incremental responses, file uploads, and `@defer`/`@stream`
  style delivery need explicit opt-in phases and engine support checks.
- **Silent best-effort typing.** If ScalaScript cannot prove a resolver or
  client operation matches the schema, the API should either fall back to
  explicit dynamic mode or report diagnostics. It must not pretend dynamic
  `Any` values are statically safe.
- **A custom GraphQL dialect.** ScalaScript may add annotations/directives for
  local tooling, but GraphQL SDL and executable documents must remain standard
  GraphQL documents.

---

## 3. Standards And Compatibility

### 3.1 Core GraphQL Version

The first implementation should target the latest stable GraphQL release
supported by the chosen engine version. As of 2026-05-29, the public GraphQL
version index lists **September 2025** as the latest release and a later working
draft. ScalaScript should expose the intended language target explicitly:

```bash
ssc check-graphql api.ssc --graphql-spec september-2025
ssc check-graphql api.ssc --graphql-spec draft
```

Planned behavior:

- Default to the latest stable GraphQL release supported by the vendored engine.
- Allow `draft` only behind an explicit flag, because draft behavior may change.
- Record the selected spec target in emitted schema metadata/manifests.
- Keep engine upgrades as visible changelog items, because GraphQL engine major
  versions may change validation, introspection, or resolver APIs.

### 3.2 GraphQL-over-HTTP

The server and client should align with the GraphQL-over-HTTP working draft:

- `POST /graphql` is required.
- `GET /graphql` is allowed only for query operations; attempting to execute a
  mutation over `GET` must return `405 Method Not Allowed`.
- Request JSON uses `query`, `operationName`, `variables`, and `extensions`.
- The client should send `Accept: application/graphql-response+json,
  application/json;q=0.9`.
- The server should prefer `application/graphql-response+json` and retain
  `application/json` compatibility while the ecosystem transitions.
- `Content-Type: application/json` over UTF-8 is the baseline request encoding.
- Response status codes must follow the selected media type semantics. The
  legacy `application/json` path commonly returns `200` for well-formed GraphQL
  responses with GraphQL errors; the `application/graphql-response+json` path
  can use HTTP status codes more precisely for request-level failures.

The official `graphql-http` audit suite should be the long-term compliance
target for ScalaScript's HTTP adapter where practical.

### 3.3 Realtime Transports

The core GraphQL spec defines subscription execution as source and response
streams, but it intentionally does not mandate a wire transport. ScalaScript
should treat each realtime protocol as an adapter:

| Transport | Role | Status |
|---|---|---|
| `graphql-ws` / `graphql-transport-ws` | Primary WebSocket subscription path | Planned Phase 3 |
| SSE | Server-to-client stream for subscriptions/incremental responses | Planned later |
| Multipart HTTP | Incremental response / subscription compatibility path | Planned later |
| Custom actor/stream transport | Internal ScalaScript distributed runtime bridge | Future |

The base API should expose subscriptions as `Source[A]` so transport adapters
can reuse the same resolver contract.

---

## 4. Architecture

### 4.1 Approach: Wrap Existing Libraries

| Backend | Library | Role |
|---|---|---|
| JVM / interpreter | `graphql-java` | Schema parsing, validation, execution, introspection |
| JVM generated | `graphql-java` | Same semantics as interpreter |
| JS / Node | `graphql-js` | Node server output and client-side validation/codegen helpers |
| Browser SPA | GraphQL client only | Query/mutation/subscription clients; no server execution |
| WASM | Host-dependent | Use JVM/JS host adapter until a WASM engine is justified |

The GraphQL plugin lives under `runtime/std/graphql-plugin/` and is loaded only
when the user imports it. Core interpreter/compiler changes are limited to SPI
hooks needed by plugins, such as fenced-block runners.

### 4.2 Schema Sources

ScalaScript should support these schema sources:

1. **Inline SDL block** (Phase 1):

   ~~~ssc
   ```graphql
   type Query { hello: String! }
   ```
   ~~~

2. **External `.graphql` files**:

   ```ssc
   //> using file schema.graphql
   val schema = GraphQL.schemaFile("schema.graphql")
   ```

3. **Generated SDL from ScalaScript types** (later):

   ```scalascript
   val schema = GraphQL.deriveSchema[QueryApi]()
   ```

4. **Imported introspection JSON** for client codegen and schema diffing.

5. **Schema extensions/overlays** for local additions, deprecations, auth
   metadata, or gateway composition. These must remain valid GraphQL SDL.

Merge rules must be deterministic: explicit `GraphQL.schema(...)` wins over an
implicit last-seen block; multiple inline blocks are concatenated in document
order unless the user names a schema.

### 4.3 SDL Block Runtime Path

The `graphql` fenced block is registered as a `SourceLanguage` plugin. For the
interpreter, `SectionRuntime` still sees `ast.Content.CodeBlock`, so Phase 1
uses the same SPI shape as SQL block runners:

```scala
trait GraphQLBlockRunner {
  def registerSdl(source: String, origin: Option[String] = None): Unit
}
```

Flow:

1. Parser sees ` ```graphql `.
2. `SectionRuntime.runSection` detects `Lang.isGraphql(cb.lang)`.
3. The interpreter calls the plugin-provided `GraphQLBlockRunner`.
4. The runner registers SDL in interpreter-scoped state.
5. `serveGraphQL` or `GraphQL.schemaFromRegisteredBlocks()` reads the registered
   SDL and passes it to the engine.

This is an interpreter convenience only. Compile-time codegen should read SDL
from `BlockArtifact(ir.Content.EmbeddedBlock(...))` through the
`SourceLanguage` plugin path.

### 4.4 Resolver Coordinates

Resolver keys should use GraphQL schema coordinates, not plain field names:

```scalascript
GraphQL.resolvers(
  query = Map(
    "Query.user" -> userResolver,
    "User.posts" -> userPostsResolver
  )
)
```

Why:

- `user` alone is ambiguous across `Query.user`, `Organization.user`, and
  nested object fields.
- Schema coordinates map naturally to compiler diagnostics and schema diff
  reports.
- They make later typed APIs and field-level auth policies stable.

Dynamic map resolvers are the Phase 1 fallback. The long-term API should offer
typed bindings:

```scalascript
val resolvers =
  GraphQL.resolverBuilder(schema)
    .field[UserArgs, Option[User]]("Query.user") { (ctx, args) =>
      db.findUser(args.id)
    }
    .field[User, Unit, List[Post]]("User.posts") { (ctx, user, _) =>
      db.postsByUser(user.id)
    }
    .build()
```

### 4.5 Context And Execution

Every resolver should receive a `GraphQLContext` with:

- request id, operation name, selected operation type;
- authenticated principal and authorization claims;
- HTTP headers/cookies and remote address when served over HTTP;
- typed service dependencies and per-request resources;
- DataLoader registry;
- selected fields / lookahead where the engine exposes it;
- cancellation token, deadline, and timeout budget;
- tracing/metrics sink.

The dynamic Phase 1 resolver signature may remain `Map[String, Any] => Any`,
but the SPI should not prevent the richer context:

```scalascript
extern type GraphQLContext

extern def GraphQL.resolvers(
  query:        Map[String, (GraphQLContext, Map[String, Any]) => Any] = Map.empty,
  mutation:     Map[String, (GraphQLContext, Map[String, Any]) => Any] = Map.empty,
  subscription: Map[String, (GraphQLContext, Map[String, Any]) => Source[Any]] = Map.empty
): GraphQLResolvers
```

If we keep a one-argument resolver overload for ergonomics, it should be sugar
that ignores `GraphQLContext`.

### 4.6 Type Mapping

GraphQL is strongly typed; ScalaScript should use that instead of stopping at
`Any`.

| GraphQL type | ScalaScript mapping |
|---|---|
| `String!` | `String` |
| `String` | `Option[String]` in typed mode |
| `ID!` | `String` initially; later `GraphQLID` newtype |
| `Int!` | `Int` with 32-bit GraphQL range validation |
| `Float!` | `Double` |
| `Boolean!` | `Boolean` |
| `[A!]!` | `List[A]` |
| `[A]` | `Option[List[Option[A]]]` in fully faithful mode |
| `enum` | ScalaScript enum |
| `input` | case class or map-backed input object |
| `type` | case class, record, or object resolver source |
| `interface` | sealed trait plus type resolver |
| `union` | sealed trait / ADT plus type resolver |
| custom scalar | `GraphQL.scalar[A](parse, serialize, specifiedBy)` |
| `@oneOf` input | sealed ADT or generated one-of wrapper |

Typed mode should reject missing required fields, wrong nullability, invalid
enum values, custom scalar parse failures, and GraphQL `Int` values outside the
allowed range before invoking user business logic when possible.

Dynamic mode stays available:

```scalascript
GraphQL.dynamicResolvers(...)
```

but docs and examples should steer users toward typed mappings once Phase 6
lands.

### 4.7 Custom Scalars And Directives

Custom scalars need explicit codecs:

```scalascript
val DateTimeScalar =
  GraphQL.scalar[Instant](
    name = "DateTime",
    specifiedBy = "https://scalars.graphql.org/andimarek/date-time.html",
    parse = Instant.parse,
    serialize = _.toString
  )
```

Directives should be supported in three layers:

- standard directives (`@skip`, `@include`, `@deprecated`, `@specifiedBy`,
  `@oneOf`) pass through the engine;
- ScalaScript tooling directives under an `@ssc_*` naming convention, or a
  project-defined namespace, can drive auth, caching, visibility, and codegen;
- unknown directives are allowed only if declared in SDL or explicitly marked
  as external.

### 4.8 Runtime Surface

Phase 1 can expose a minimal API, but the planned surface should leave room for
typed contracts:

```scalascript
extern type GraphQLSchema
extern type GraphQLResolvers
extern type GraphQLExecutable
extern type GraphQLContext
extern type GraphQLRequest
extern type GraphQLResponse[A]
extern type GraphQLError
extern type GraphQLServeOptions
extern type GraphQLClientOptions

extern def GraphQL.schema(sdl: String): GraphQLSchema
extern def GraphQL.schemaFile(path: String): GraphQLSchema
extern def GraphQL.schemaFromRegisteredBlocks(): GraphQLSchema

extern def GraphQL.resolvers(...): GraphQLResolvers
extern def GraphQL.dynamicResolvers(...): GraphQLResolvers
extern def GraphQL.executable(schema: GraphQLSchema,
                              resolvers: GraphQLResolvers): GraphQLExecutable

extern def serveGraphQL(port: Int, resolvers: GraphQLResolvers): Unit
extern def serveGraphQL(port: Int, schema: GraphQLSchema,
                        resolvers: GraphQLResolvers): Unit
extern def serveGraphQL(port: Int, executable: GraphQLExecutable,
                        options: GraphQLServeOptions): Unit

extern def graphqlMount(resolvers: GraphQLResolvers): Unit
extern def graphqlMount(path: String, executable: GraphQLExecutable,
                        options: GraphQLServeOptions): Unit
extern def graphqlHandler(executable: GraphQLExecutable): Request => Response

extern def graphqlQuery(url: String, query: String): Map[String, Any]
extern def graphqlQuery[A](url: String, operation: GraphQLOperation[A],
                           variables: Any): GraphQLResponse[A]
extern def graphqlSubscribe[A](url: String, operation: GraphQLOperation[A],
                               variables: Any): Source[A]
```

The overloads are intentionally staged: Phase 1 can implement the dynamic
subset while keeping names compatible with later typed helpers.

### 4.9 HTTP Server Integration

GraphQL should mount onto the existing HTTP plugin rather than own a separate
server:

```scalascript
graphqlMount("/graphql", executable, GraphQLServeOptions(
  introspection = GraphQLIntrospection.DevOnly,
  maxDepth = 12,
  maxComplexity = 5000,
  timeoutMs = 5000,
  playground = GraphQLPlayground.DevOnly
))

serve(4000)
```

Default behavior:

- `serveGraphQL(port, ...)` is convenience sugar for mount + `serve(port)`.
- `graphqlMount` composes with REST routes, static assets, auth middleware,
  OpenAPI routes, and frontend dev servers.
- Development may enable GraphiQL/Apollo Sandbox-compatible landing behavior.
- Production disables interactive explorers unless explicitly enabled.

### 4.10 Client Operations

Client code should support raw dynamic calls first:

```scalascript
val result = graphqlQuery(
  "https://api.example.com/graphql",
  "query GetUser($id: ID!) { user(id: $id) { id name } }",
  Map("id" -> "u1")
)
```

Then typed operations:

~~~ssc
```graphql operation GetUser
query GetUser($id: ID!) {
  user(id: $id) {
    id
    name
  }
}
```

```scalascript
val getUser = GraphQL.operation[GetUserVars, GetUserData]("GetUser")
val data = graphqlQuery(client, getUser, GetUserVars(id = "u1")).data
```
~~~

The compiler should validate operation documents against either:

- a local SDL block/file;
- an imported introspection schema;
- a remote schema fetched explicitly by a CLI command and committed as a
  generated artifact.

Implicit network fetch during normal compilation is a non-goal.

### 4.11 Persisted Operations

Production clients should be able to use persisted operations:

```bash
ssc emit-graphql-operations app.ssc --out graphql-operations.json
ssc run server.ssc --graphql-persisted graphql-operations.json
```

Planned behavior:

- Hash operation text with a deterministic algorithm.
- Emit manifest entries with operation name, hash, type, variables type, data
  type, and optional profile.
- Server can run in `persistedOnly = true` mode to reject arbitrary documents.
- APQ-style negotiation may be added as a compatibility mode.
- Client codegen can send only operation ids/hashes in production builds.

### 4.12 Security And Limits

GraphQL support is incomplete without production controls:

- auth principal injection into `GraphQLContext`;
- field-level auth helpers keyed by schema coordinates;
- introspection policy: `always`, `devOnly`, `disabled`, or custom predicate;
- query depth limit, complexity limit, alias count limit, token count limit;
- variable size and request body size limits;
- resolver timeout, cancellation, and backpressure for subscriptions;
- CSRF/CORS guidance for browser clients;
- error redaction with structured `extensions.code` in production;
- persisted-only mode for public clients;
- audit logging with operation hash and schema version.

These controls should be normal options, not ad hoc middleware examples.

### 4.13 DataLoader And Batching

N+1 fetch behavior is a common GraphQL failure mode. ScalaScript should expose
DataLoader-like batching as a first-class ergonomic API:

```scalascript
val usersById = GraphQL.dataLoader[String, User]("usersById") { ids =>
  db.usersByIds(ids)
}

GraphQL.resolverBuilder(schema)
  .field[Post, Unit, User]("Post.author") { (ctx, post, _) =>
    ctx.loader(usersById).load(post.authorId)
  }
```

Requirements:

- loaders are request-scoped by default;
- cache keys are typed;
- batch functions may be sync, async, actor-backed, stream-backed, SQL-backed,
  or Spark-backed through existing ScalaScript mapping abstractions;
- metrics report hit/miss/batch size/latency;
- failures preserve GraphQL partial-response semantics.

### 4.14 Observability

GraphQL should emit structured events:

- request started/completed/failed;
- operation name/type/hash/schema version;
- parse, validate, execute durations;
- per-field resolver timings in debug/profile mode;
- DataLoader batch metrics;
- subscription lifecycle events;
- GraphQL errors with redaction policy;
- HTTP media type and status-code decisions.

This integrates with existing logging/metrics/tracing work rather than adding a
separate observability stack.

### 4.15 Schema Diff And Contract Tests

ScalaScript should provide GraphQL contract commands:

```bash
ssc emit-graphql-schema api.ssc --format sdl --out schema.graphql
ssc emit-graphql-schema api.ssc --format introspection --out schema.json
ssc check-graphql api.ssc
ssc diff-graphql old.graphql new.graphql
ssc test-graphql api.ssc --operations src/graphql/**/*.graphql
```

Diff behavior:

- detect breaking changes such as removed types/fields/enum values, stricter
  argument requirements, return type incompatibility, changed nullability, and
  removed directives;
- classify dangerous changes such as added enum values for clients with
  exhaustive matching;
- allow additive changes by default;
- support profile-aware diffing for public/internal/admin schemas.

Contract tests should execute declared operations against local handlers using
fixtures before requiring a real network server.

---

## 5. Module Layout

```
runtime/std/
  graphql-plugin/
    src/main/scala/scalascript/compiler/plugin/graphql/
      GraphQLInterpreterPlugin.scala      # SPI entry (Backend + graphqlBlockRunner)
      GraphQLIntrinsics.scala             # Intrinsic table + JVM engine building
      GraphQLJvmBlockRunner.scala         # SDL registration (implements GraphQLBlockRunner)
      GraphQLResolvers.scala              # Resolver container (query/mutation/subscription maps)
      GraphQLSourceLanguage.scala         # SourceLanguage SPI — graphql fenced blocks
      GraphQLSchemaValidator.scala        # Phase 4 — compile-time SDL validation
      GraphQLHttpAdapter.scala            # Phase 5 — media-type negotiation, strict HTTP
      GraphQLDataLoaders.scala            # Phase 9 — DataLoader/batching
      GraphQLClientRuntime.scala          # Phase 7+ — typed client operations
      GraphQLJsRuntime.scala              # Phase 2+ — Node/JS codegen
    src/main/resources/META-INF/services/
      scalascript.backend.spi.Backend
      scalascript.backend.spi.SourceLanguage
  graphql.ssc

runtime/backend/
  spi/src/main/scala/scalascript/backend/spi/
    GraphQLBlockRunner.scala
  js/.../codegen/intrinsics/
    Graphql.scala                         # Phase 2+
  node/.../codegen/
    NodeBackend.scala                      # Phase 2+

lang/core/.../
  ast/Lang.scala                           # Graphql language tag helper

tools/cli/.../cli/Main.scala
```

GraphQL intrinsics belong in `runtime/std/graphql-plugin/`, not in the
interpreter core. Only SPI hooks belong in `runtime/backend/spi` or interpreter
installation code.

---

## 6. Migration

GraphQL is a new feature. Existing REST/OpenAPI apps are unaffected.

Potential migration after Phase 1:

- dynamic resolver maps continue to work;
- typed resolver builders are additive;
- `serveGraphQL(port, resolvers)` remains as ergonomic sugar;
- future default changes such as stricter HTTP compliance or disabled
  production introspection must be feature-flagged before becoming defaults.

---

## 7. Phases

### Phase 1 - Schema + Resolvers + `serveGraphQL` (JVM/interpreter) ✅

**Goal:** `ssc run myapi.ssc` starts a JVM GraphQL server from inline SDL and
dynamic resolvers.

**Status:** Implemented 2026-05-29. 20 tests pass.

**Implementation notes:**

- JVM runtime (engine building, DataFetcher wiring) lives in `GraphQLIntrinsics`
  rather than a separate `GraphQLJvmRuntime.scala`; the file can be split in a
  later refactor if it grows.
- HTTP handling is inline in `GraphQLIntrinsics.handleRequest` rather than a
  separate `GraphQLHttpAdapter.scala`; again, split-out is a Phase 5 candidate.
- `GraphQLInterpreterPlugin.scala` is the SPI entry point (rather than
  `GraphQLPlugin.scala` — the spec had a placeholder name).
- Resolver keys support both plain field names (`"hello"`) and GraphQL schema
  coordinates (`"Query.hello"`, `"User.posts"`). Plain keys in the `query` map
  default to the `Query` type; plain keys in the `mutation` map default to
  `Mutation`. Schema coordinates select any type name explicitly.
- `GraphQL.resolvers()` accepts a `subscription` parameter (stored, not yet
  wired); Phase 3 connects it to WebSocket.
- GET /graphql rejects mutation documents with `405 Method Not Allowed`.

Tasks (done):

- `runtime/std/graphql-plugin/` sbt subproject with `graphql-java 22.3`.
- `GraphQLIntrinsics`: `GraphQL.schema(sdl)`, `GraphQL.resolvers(query, mutation, subscription)`,
  `serveGraphQL(port, resolvers[, tls])`, `graphqlMount(resolvers)`,
  `graphqlHandler(schema, resolvers)`.
- `runtime/std/graphql.ssc`: extern declarations + opaque type aliases.
- `Lang.scala`: `val Graphql = "graphql"`, `def isGraphql`.
- `Backend` SPI: `graphqlBlockRunner`.
- `SectionRuntime`: handle `Lang.isGraphql` blocks via the plugin runner.
- `GraphQLSourceLanguage`: registered via ServiceLoader (no hand edit to registry).
- Dual META-INF: `scalascript.backend.spi.Backend` and `scalascript.backend.spi.SourceLanguage`.
- GraphQL-over-HTTP: `POST /graphql`, query-only `GET /graphql`, mutation-over-GET → 405.
- `examples/graphql-hello.ssc`.
- `GraphQLIntrinsicsTest` (20 tests): schema, block registration, schema coordinates,
  nested type resolvers, plain keys, mutations, variables, lists, booleans,
  introspection, Content-Type, GET mutation rejection.

Effort: ~4 days.

### Phase 2 - Async Resolvers + GraphQL Client + Node Backend

**Goal:** resolvers can return `Future[A]` or `A ! Async`, the dynamic client
works, and Node output can serve GraphQL.

**Status:** `graphqlQuery` dynamic client landed 2026-05-29 (24 total tests). Async resolvers and JS/Node codegen remain.

Tasks:

- [x] `graphqlQuery(url, query[, variables])` dynamic client — JVM, uses `java.net.http.HttpClient`.
  Appends `/graphql` if missing; throws on GraphQL `errors`; returns `data` map.
- [ ] Async resolver support through `CompletableFuture` / engine-native async execution.
- [ ] `GraphQLJsRuntime` and JS codegen intrinsic support for `graphqlHandler`.
- [ ] Node backend preamble and generated `package.json` dependency for `graphql`.
- [ ] `Feature.GraphQL` in backend capabilities.
- [x] `examples/graphql-client.ssc`.
- [ ] Tests for async round-trip and JS codegen shape.

Effort: ~3 days remaining.

### Phase 3 - Subscriptions Over WebSocket

**Goal:** `subscription` resolvers backed by `Source[A]` push events to clients
over a WebSocket GraphQL transport.

Tasks:

- `serveGraphQL` / `graphqlMount` register `GET /graphql/ws`.
- Implement `graphql-transport-ws` message lifecycle: connection init, ack,
  subscribe, next, error, complete, ping, pong, close handling.
- Bridge `Source[A]` to the engine subscription publisher model.
- `graphqlSubscribe(url, query)(handler)` dynamic client.
- Production introspection policy option.
- `examples/graphql-subscriptions.ssc`.
- Tests for lifecycle, multiple subscribers, cancellation, and errors.

Effort: ~6 days.

### Phase 4 - Compile-Time SDL Validation + LSP Diagnostics ✅

**Status:** Implemented 2026-05-29. 12 `GraphQLSchemaCheckTest` tests pass; 36
total graphql-plugin tests.

**Goal:** SDL errors are reported by `ssc build` and editor diagnostics before
runtime.

Tasks:

- [x] `GraphQLSourceLanguage.compileBlock` validates SDL via graphql-java's
  `SchemaParser`; syntax errors (invalid tokens, unclosed braces, duplicate type
  definitions) are caught and returned as `Diagnostic.GraphQLSdlError(message,
  line, col)` entries in `BlockArtifact.diagnostics`.
- [x] `Diagnostic.GraphQLSdlError` added to `backend-spi` enum (analogous to
  `Diagnostic.XmlParseError`).
- [x] `Normalize.scala` propagates non-empty diagnostics from `compileBlock`
  as a `RuntimeException` so `ssc build` fails with a clear message on invalid SDL.
- [x] `GraphQLSchemaCheckTest` (12 tests): valid SDL varieties, syntax errors,
  empty SDL, unclosed braces, duplicate types, error message non-empty,
  fragment still returned on error.
- [ ] Semantic validation (unknown type references, missing Query type) —
  requires `SchemaGenerator` integration; deferred to Phase 4b.
- [ ] LSP inline diagnostics — depends on LSP server integration; deferred.

Effort: ~2 days (syntax validation done; semantic + LSP are follow-ups).

### Phase 5 - GraphQL-over-HTTP Compliance ✅ Landed (2026-05-29)

**Goal:** server and client behavior are intentionally aligned with the
GraphQL-over-HTTP draft.

Implemented in `GraphQLIntrinsics.handleRequest`:

- [x] Media-type negotiation: respond with `application/graphql-response+json`
  when the `Accept` header requests it; fall back to `application/json`.
- [x] Status codes: return `400` for request errors (missing/blank query,
  null data) under `application/graphql-response+json`; always `200` under
  `application/json`.
- [x] GET query-string precedence: `?query=`, `?variables=` (JSON-encoded),
  `?operationName=`; falls back to JSON body when no query string present.
- [x] Mutation-over-GET rejection with `405` (§6.2.2).
- [x] `operationName` passed to `ExecutionInput` from both POST body and GET
  query string.
- [x] `errors` key omitted from response body when the errors list is empty.
- [x] `extensions` passthrough when the engine returns non-null extensions.
- [x] Error objects include `locations` (line + column) when available.
- [x] `GraphQLHttpComplianceTest` (12 tests).
- [ ] Official `graphql-http` audit suite — deferred (requires running
  a live HTTP server in CI).

Effort: ~1 day (implemented).

### Phase 6 - Typed Resolver Mapping ✅ Landed (2026-05-29)

**Goal:** custom scalar codecs, typed argument access patterns, nested object
output types, and the `examples/graphql-typed-resolvers.ssc` example.

Implemented:

- [x] `GraphQL.scalar(name, serialize, coerce)` — custom scalar codec that
  wires `Coercing` into graphql-java's `RuntimeWiring`. Codec functions are
  ScalaScript closures invoked via `PluginNative.evalLegacy`.
- [x] `GraphQL.resolvers(query, mutation, subscription, scalars)` — 4th
  positional arg `scalars: Map[String, GraphQLScalar]` accepted; scalars map is
  stored in `GraphQLResolvers.scalars` and registered with `RuntimeWiring`.
- [x] `ScalarCodec` case class (`GraphQLResolvers.scala`) — `serialize: Value => Any`
  and `coerce: Any => Value` pair, decoupled from closure capture.
- [x] Schema coordinate resolver keys (`"Query.user"`, `"User.name"`) — already
  landed in Phase 1; documented in example.
- [x] Nested object output types via `Value.MapV` — graphql-java reflects field
  names from the map; no extra wiring needed.
- [x] List-of-objects return type via `Value.ListV`.
- [x] `GraphQLTypedResolversTest` (10 tests): scalar codec lifecycle, resolvers
  with scalars map, end-to-end custom scalar as output and input, multiple
  scalars, nested objects, list of objects, backwards compat.
- [x] `examples/graphql-typed-resolvers.ssc` — full example with `Date` scalar,
  in-memory store, typed arg extraction helpers, query + mutation resolvers.
- [ ] Enum mapping (SDL `enum Role { ADMIN MEMBER }` → `String` in ScalaScript) —
  already works via graphql-java default enum coercion; no extra wiring needed.
- [ ] Interface/union type resolution (`__resolveType`) — deferred to Phase 6b.
- [ ] `@oneOf` input mapping — deferred to Phase 6b.
- [ ] Typed diagnostics (resolver signature vs SDL mismatch) — requires
  compiler integration; deferred to Phase 6b.

Effort: ~1 day (core scalar + patterns done; advanced type-system features deferred).

### Phase 7 - Typed Client Operations And Codegen

**Goal:** frontend and backend clients can call GraphQL with typed variables
and typed response data.

Tasks:

- `graphql` operation fenced blocks.
- Operation validation against local SDL or committed introspection schema.
- Generated operation models for variables/data.
- Typed `graphqlQuery[A]` and `graphqlSubscribe[A]`.
- Browser/React/Electron client support through the existing frontend build
  paths.
- `examples/graphql-typed-client/`.

Effort: ~5 days.

### Phase 8 - Persisted Operations / APQ ✅ Landed (2026-05-29)

**Goal:** production clients can ship operation hashes/manifests instead of
arbitrary query text.

Implemented in `GraphQLOpts` and `handleRequest`:

- [x] `GraphQL.options(... persistedOps = Map(hash -> operationText), persistedOnly = true)` —
  5th and 6th positional args to `GraphQL.options`.
- [x] APQ negotiation — hash-only request (`extensions.persistedQuery.sha256Hash`):
  if hash is in `persistedOps` map, execute the stored operation; otherwise return
  `PersistedQueryNotFound` with `code: PERSISTED_QUERY_NOT_FOUND`.
- [x] APQ hash+query request — executes the query directly (client-side registration).
- [x] `persistedOnly` mode — compute SHA-256 of the incoming query text and reject
  if the hash is not in the `persistedOps` manifest.
- [x] Variables passed through correctly for APQ hash-only requests.
- [x] `GraphQLPersistedOpsTest` (10 tests): options acceptance, hash-only not-found,
  hash-only found, hash+query, persistedOnly rejection, persistedOnly allow, with variables.
- [ ] `emit-graphql-operations` CLI command — deferred (requires AST scanning of .ssc files).
- [ ] Manifest file format (JSON) with name/type/variables/data model — deferred.

Effort: ~0.5 day (server-side APQ done; CLI emit command deferred).

### Phase 9 - DataLoader And Batching

**Goal:** N+1 mitigation is built into the GraphQL ergonomics.

Tasks:

- Request-scoped typed DataLoader registry.
- Sync/async batch functions.
- Integration with resolver context.
- Metrics for batch size, hit/miss, latency.
- Tests for batching, cache isolation, failures, and partial responses.

Effort: ~4 days.

### Phase 10 - Security, Limits, And Observability ✅ Landed (2026-05-29)

**Goal:** GraphQL servers have production controls by default.

Implemented via `GraphQL.options(...)` intrinsic and `GraphQLOpts` data class:

- [x] `GraphQL.options(maxDepth, maxComplexity, maxQueryLength, disableIntrospection)` —
  positional params, all optional.  Returns `Value.Foreign("GraphQLOptions", GraphQLOpts)`.
- [x] `maxDepth: Int` — `MaxQueryDepthInstrumentation(n)` wired into graphql-java engine.
- [x] `maxComplexity: Int` — `MaxQueryComplexityInstrumentation(n)` wired into graphql-java engine.
- [x] `maxQueryLength: Int` — body-length check before parsing; returns error (400 under
  `application/graphql-response+json`, 200 under `application/json`).
- [x] `disableIntrospection: Boolean` — rejects queries containing `__schema` or `__type`.
- [x] `graphqlHandler(schema, resolvers, opts)` — accepts optional 3rd arg.
- [x] `graphqlMount(resolvers, opts)` and `serveGraphQL(port, resolvers, opts)` — likewise.
- [x] `GraphQLSecurityTest` (12 tests): options intrinsic, limit enforcement, backwards compat.
- [ ] Alias / token / variable count limits — deferred to Phase 10b.
- [ ] Resolver timeout and cancellation — requires async runtime changes; deferred.
- [ ] Auth principal injection, field-level auth helpers — deferred to Phase 10b.
- [ ] Error redaction / structured tracing — deferred to Phase 10b.

Effort: ~1 day (core instrumentation limits done; advanced security deferred).

### Phase 11 - Schema Export, Import, Diff, And Contract Tests

**Status:** ✅ Landed 2026-05-29 (14 tests).

**Intrinsics added:**

- `GraphQL.printSchema(schema: GraphQLSchema): String` — serialises a
  `GraphQLSchema` foreign value to normalised SDL via `SchemaPrinter`.
- `GraphQL.introspectionJson(schema: GraphQLSchema): String` — runs the
  standard introspection query against the schema and returns the JSON result
  (`__schema`, `queryType`, `types`, `fields`).
- `GraphQL.diffSchemas(sdlA: String, sdlB: String): List[Map]` — structural
  diff; each entry has `kind` (`TYPE_ADDED`, `TYPE_REMOVED`, `FIELD_ADDED`,
  `FIELD_REMOVED`) and `description`.

**Tests:** `GraphQLSchemaExportTest` (14 tests) — printSchema non-empty, type/field
names present, whitespace normalisation, multi-type schema, introspection JSON
structure, queryType, type list, field list, diff identical/added-type/removed-type/
added-field/removed-field, change map keys.

Effort: ~4 days.

### Phase 12 - Federation, Stitching, And Gateway Plugins

**Goal:** larger deployments can compose multiple GraphQL services without
putting federation into the base plugin.

Tasks:

- `runtime/std/graphql-federation-plugin/` optional plugin.
- Federation v2 directive passthrough and subgraph SDL export.
- Gateway/stitching exploration document.
- Compatibility with typed resolver coordinates and schema diffing.

Effort: ~6 days.

### Phase 13 - Additional Realtime And Incremental Delivery

**Goal:** support non-WebSocket realtime delivery where it is useful and
well-supported by engines.

Tasks:

- SSE subscription adapter.
- Multipart incremental response adapter.
- Engine feature checks for incremental delivery directives.
- Backpressure/cancellation tests.
- AsyncAPI companion export for realtime transport surfaces where appropriate.

Effort: ~5 days.

---

## 8. Testing Strategy

| Phase | Tests | Kind |
|---|---|---|
| 1 | `GraphQLIntrinsicsTest` (20) ✅ | Interpreter + HTTP unit |
| 2 | `graphqlQuery` client (4 tests) ✅; async + JS codegen pending | Interpreter + codegen |
| 3 | Subscription lifecycle and cancellation | Integration |
| 4 | `GraphQLSchemaCheckTest` (12) ✅; semantic validation pending | Compiler diagnostics |
| 5 | `GraphQLHttpComplianceTest` (12) ✅; official audit suite pending | Protocol |
| 6 | `GraphQLTypedResolversTest` (10) ✅; interface/union/oneOf deferred | Type mapping |
| 7 | Operation validation and typed client round-trips | Compiler + runtime |
| 8 | `GraphQLPersistedOpsTest` (10) ✅; emit-operations CLI deferred | CLI + runtime |
| 9 | DataLoader batch/cache/failure tests | Runtime |
| 10 | `GraphQLSecurityTest` (12) ✅; auth/redaction/tracing deferred | Runtime + security |
| 11 | Schema export/import/diff/fixture tests | CLI + contract |
| 12 | Federation plugin smoke tests | Plugin integration |
| 13 | SSE/multipart lifecycle tests | Integration |

Manual smoke with Apollo Sandbox or GraphiQL is useful, but it is not a
substitute for protocol and contract tests.

---

## 9. Open Questions

1. **Engine versions.** Which `graphql-java` and `graphql-js` versions best
   match the latest stable GraphQL spec target at implementation time?

2. **Typed nullability mode.** Should nullable GraphQL values always map to
   `Option[A]`, or should dynamic/client modes allow nullable fields as
   nullable platform values for ergonomic interop?

3. **Default production posture.** Should `ssc run --production` force
   `introspection = disabled`, `playground = disabled`, and nonzero depth/
   complexity limits, or only warn when they are missing?

4. **Operation document location.** Should client operations live in
   `graphql` fenced blocks, `.graphql` files, or both from Phase 7?

5. **Schema derivation direction.** Should code-first schema derivation be a
   core GraphQL plugin feature or a separate `graphql-derive-plugin` once typed
   mapping exists?

6. **Federation choice.** Apollo Federation v2 is the practical ecosystem
   target, but it is not part of the core GraphQL spec. Confirm whether the
   first composition plugin should target Federation, schema stitching, or both.

7. **Uploads.** GraphQL file uploads rely on community multipart conventions and
   have security tradeoffs. Keep as a separate plugin unless a concrete use case
   appears.

8. **Distributed runtime integration.** For internal ScalaScript clusters,
   decide whether GraphQL resolvers can call distributed actors/functions
   directly with typed mapping, or whether a gateway boundary is required.

---

## 10. Critical Files

| File | Role |
|---|---|
| `runtime/std/graphql-plugin/.../GraphQLPlugin.scala` | SPI entry |
| `runtime/std/graphql-plugin/.../GraphQLIntrinsics.scala` | Intrinsic table |
| `runtime/std/graphql-plugin/.../GraphQLJvmRuntime.scala` | JVM engine wiring |
| `runtime/std/graphql-plugin/.../GraphQLSourceLanguage.scala` | SDL block plugin |
| `runtime/std/graphql-plugin/.../GraphQLHttpAdapter.scala` | HTTP protocol behavior |
| `runtime/std/graphql-plugin/.../GraphQLSchemaValidator.scala` | SDL and operation diagnostics |
| `runtime/std/graphql-plugin/.../GraphQLDataLoaders.scala` | Phase 9 batching |
| `runtime/std/graphql-plugin/.../GraphQLJsRuntime.scala` | Node engine wiring |
| `runtime/std/graphql.ssc` | User-facing extern declarations |
| `runtime/backend/spi/.../GraphQLBlockRunner.scala` | Interpreter block SPI |
| `runtime/backend/spi/.../Backend.scala` | Backend SPI extension |
| `runtime/backend/interpreter/.../Interpreter.scala` | Plugin installation |
| `runtime/backend/interpreter/.../SectionRuntime.scala` | `graphql` block dispatch |
| `lang/core/src/main/scala/scalascript/ast/Lang.scala` | Language tag helper |
| `runtime/backend/js/.../codegen/intrinsics/Graphql.scala` | JS intrinsic codegen |
| `runtime/backend/node/.../codegen/NodeBackend.scala` | Node dependency/preamble |
| `tools/cli/.../cli/Main.scala` | CLI commands |
| `build.sbt` | Plugin subprojects |
| `examples/graphql-hello.ssc` | Phase 1 example |
| `examples/graphql-client.ssc` | Phase 2 example |
| `examples/graphql-subscriptions.ssc` | Phase 3 example |
| `examples/graphql-typed-resolvers.ssc` | Phase 6 example |
| `examples/graphql-typed-client/` | Phase 7 example |
| `examples/graphql-contract-tests/` | Phase 11 example |
