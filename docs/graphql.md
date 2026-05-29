# GraphQL — spec

**Status:** Planning. No implementation yet.

**Companion:** [`docs/future-protocols.md §3`](future-protocols.md)

---

## 1. Goals

ScalaScript apps can serve a GraphQL API from a single `.ssc` file.
The developer experience matches the existing REST surface: define a
schema in a `graphql` fenced block, write resolver functions in
ScalaScript, call `serveGraphQL(port)`.

```ssc
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
  createUser(name: String!, email: String!): User!
}

type Subscription {
  userCreated: User!
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
    "user"  -> ((args: Map[String, Any]) => db.findUser(args("id").toString)),
    "users" -> ((_: Map[String, Any]) => db.allUsers())
  ),
  mutation = Map(
    "createUser" -> ((args: Map[String, Any]) =>
      db.createUser(args("name").toString, args("email").toString))
  ),
  subscription = Map(
    "userCreated" -> ((_: Map[String, Any]) => userCreatedSource)
  )
)

serveGraphQL(4000, resolvers)
```

---

## 2. Non-goals

- **Building a GraphQL engine from scratch.** The implementation wires
  to `graphql-java` (JVM) and `graphql-js` (JS backend). We own the
  ScalaScript DSL surface and the glue; we do not own the execution engine.
- **GraphQL federation** (Apollo Federation, schema stitching). Out of scope;
  may be a downstream library concern.
- **Persisted queries / APQ.** Deferred to a future phase.
- **DataLoader / N+1 batching.** Phase 3 candidate; complex enough to defer.
- **Code-first schema generation** (derive SDL from Scala types). Phase 4+.
  Phase 1–3 are schema-first (SDL in a `graphql` fenced block).
- **Subscriptions over HTTP multipart** (the newer spec). Only `graphql-ws`
  protocol over WebSocket is targeted.

---

## 3. Architecture

### 3.1 Approach: wrap existing libraries

| Backend | Library | License |
|---------|---------|---------|
| JVM / interpreter | `graphql-java` 22.x | MIT |
| JS (Node target) | `graphql-js` 16.x | MIT |
| JS (browser SPA) | Not applicable — SPAs are clients, not servers |
| WASM | JVM path (WASM runs on JVM host) | — |

The JVM path works for both the interpreter (which runs on the JVM) and
the JVM codegen target. The JS path covers `ssc emit-js --target node`.

### 3.2 SDL source

Schema SDL can come from two places:

1. **`graphql` fenced block** in a `.ssc` file (primary path):

   ~~~ssc
   ```graphql
   type Query { hello: String! }
   ```
   ~~~

   The block content is extracted as a `String` constant and passed to
   `GraphQL.schema(sdl)` at runtime.

2. **External `.graphql` file** via `//> using file schema.graphql`
   (Phase 2). Loaded at startup via `readFile("schema.graphql")`.

### 3.3 Resolver surface

Resolvers are plain ScalaScript functions with signature
`Map[String, Any] => Any`. The `Any` return type is by design:
GraphQL execution is dynamically typed at the resolver boundary —
the execution engine validates that the returned value matches the
schema type, not the ScalaScript typer.

```scalascript
// Minimal resolver: args → value
"user" -> ((args: Map[String, Any]) => db.findUser(args("id").toString))

// Async resolver: args → Future[value] (Phase 2)
"user" -> ((args: Map[String, Any]) => db.findUserAsync(args("id").toString))

// Subscription resolver: args → Source[value] (Phase 3)
"userCreated" -> ((_: Map[String, Any]) => userCreatedSource)
```

### 3.4 Runtime surface

```scalascript
// Schema construction
extern def GraphQL.schema(sdl: String): GraphQLSchema
extern def GraphQL.resolvers(
  query:        Map[String, Map[String, Any] => Any]        = Map.empty,
  mutation:     Map[String, Map[String, Any] => Any]        = Map.empty,
  subscription: Map[String, Map[String, Any] => Source[Any]]= Map.empty
): GraphQLResolvers

// Convenience: registers POST /graphql + (Phase 3) WS /graphql/ws, then calls serve(port).
// Equivalent to: graphqlMount(resolvers); serve(port)
extern def serveGraphQL(port: Int, resolvers: GraphQLResolvers): Unit
extern def serveGraphQL(port: Int, resolvers: GraphQLResolvers,
                        tlsCtx: TlsContext): Unit

// Mount GraphQL onto an existing route() server WITHOUT calling serve().
// Registers POST /graphql (and Phase 3: WS /graphql/ws) on the current server.
// Use this when mixing GraphQL with REST in one server.
extern def graphqlMount(resolvers: GraphQLResolvers): Unit

// Handler for use with route() directly (advanced; prefer graphqlMount).
extern def graphqlHandler(schema: GraphQLSchema,
                          resolvers: GraphQLResolvers): Request => Response

// Client (Phase 2)
extern def graphqlQuery(url: String, query: String): Map[String, Any]
extern def graphqlQuery(url: String, query: String,
                        variables: Map[String, Any]): Map[String, Any]
```

`GraphQLSchema` and `GraphQLResolvers` are opaque types — thin wrappers
over the underlying library objects.

### 3.5 HTTP transport

GraphQL runs over HTTP POST at `/graphql` by default:
- Request: `Content-Type: application/json`, body `{ "query": "...", "variables": {...} }`.
- Response: `{ "data": {...}, "errors": [...] }`.
- Introspection (`__schema`) enabled in development, disabled in production
  (controlled by `ssc run --production` flag, Phase 2).

Subscriptions run over WebSocket at `/graphql/ws` using the
`graphql-ws` protocol (current standard, supersedes `subscriptions-transport-ws`).

### 3.6 Module layout

```
runtime/std/
  graphql-plugin/
    src/main/scala/scalascript/compiler/plugin/graphql/
      GraphQLPlugin.scala                ← Backend SPI registration
      GraphQLIntrinsics.scala            ← NativeImpl table: schema, resolvers, serveGraphQL, graphqlHandler
      GraphQLJvmRuntime.scala            ← JVM: graphql-java wiring
      GraphQLJsRuntime.scala             ← JS: graphql-js wiring (Phase 2)
    src/main/resources/META-INF/services/
      scalascript.backend.spi.Backend        ← ServiceLoader entry for intrinsics
      scalascript.backend.spi.SourceLanguage ← ServiceLoader entry for fenced-block dialect
  graphql.ssc                            ← extern declarations + opaque types

runtime/backend/
  js/…/codegen/intrinsics/
    Graphql.scala                        ← Phase 2: graphqlHandler JS codegen (not JsGen.scala)
  node/…/codegen/
    NodeBackend.scala                    ← Phase 2: require('graphql') preamble + package.json dep

lang/core/…/
  ast/Lang.scala                         ← Phase 1: add Lang.isGraphql + "graphql" constant
  (SourceLanguageRegistry.scala: no hand-edit — registration is via META-INF ServiceLoader entry)

tools/cli/…/cli/Main.scala              ← Phase 3: ssc serve --graphql flag
```

### 3.7 How `graphql` fenced blocks work

#### Compile-time path (JVM / JS codegen)

The `graphql` fenced block is registered as a `SourceLanguage` plugin —
the same mechanism as `javascript`, `xml`, `sql`, `transaction` in
`runtime/backend/scala-source/…/BuiltinSourceLanguages.scala` (note: `html`
and `css` live in their own subprojects `runtime/backend/html` and
`runtime/backend/css` and use the same separate-subproject pattern that
`graphql-plugin` follows). Registration is purely via the plugin's
`META-INF/services/scalascript.backend.spi.SourceLanguage` entry — no
hand-edit to `SourceLanguageRegistry.scala` is required.

1. Parser sees ` ```graphql `.
2. `SourceLanguageRegistry.lookup("graphql")` returns `GraphQLSourceLanguage`.
3. `GraphQLSourceLanguage.compileBlock(content, …)` returns
   `BlockArtifact(ir.Content.EmbeddedBlock(language = "graphql", source = sdlString))`.
   Passthrough — no SDL parsing at compile time.
4. JVM codegen (`GraphQLJvmRuntime`) reads the SDL from `EmbeddedBlock.source`
   directly during code generation.
5. JS codegen (`runtime/backend/js/…/codegen/intrinsics/Graphql.scala`) emits
   a `buildSchema(sdl)` call; `NodeBackend.scala` adds `require('graphql')` to
   the preamble and `graphql-js` to the generated `package.json`.

Phase 4 compile-time SDL validation overrides `compileBlock` to run the SDL
through `graphql-java`'s `SchemaParser` and populate `BlockArtifact.diagnostics`
(the `diagnostics: List[Diagnostic]` field already exists on `BlockArtifact`).
The `SourceLanguageRegistry` framework forwards these diagnostics to the user.
This is a single method change in `GraphQLSourceLanguage.compileBlock` — no
separate transform pass needed (unlike `MarkupInterpolatorCheck`, which targets
string interpolators, not fenced blocks).

#### Interpreter runtime path

The interpreter works on `ast.Content.CodeBlock` (AST level), not on
`ir.Content.EmbeddedBlock` (IR level). `SectionRuntime.runSection` currently
dispatches on language tags (`isParseable`, `isStringBlock`, `isSql`,
`isTransaction`, `isXml`). A `graphql` block would otherwise hit `case _ => ()`
and be silently dropped.

Phase 1 therefore follows the **`sqlBlockRunner` pattern** already used by the
SQL plugin:

1. `Lang.scala` gains `val Graphql = "graphql"` and `def isGraphql(lang: String)`.
2. `Backend` SPI gains `def graphqlBlockRunner: Option[GraphQLBlockRunner] = None`.
3. `Interpreter` installs `graphqlBlockRunner` from loaded plugins (same as
   `installSqlBlockRunners`).
4. `SectionRuntime.runSection` gains:
   ```scala
   case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
     interp.graphqlBlockRunner.getOrElse(
       throw InterpretError("No GraphQL block runner — add the graphql-plugin")
     ).registerSdl(cb.source)
   ```
5. `GraphQLPlugin` implements `graphqlBlockRunner` with a runner that stores the
   SDL in a thread-local or interpreter-scoped slot readable by `serveGraphQL`.

`serveGraphQL(port, resolvers)` reads the registered SDL at call time and passes
it to `graphql-java`'s `SchemaParser.parse(sdl)`.

### 3.8 JVM dependency

`graphql-java` is pulled in as a `% Runtime` dependency of the
`graphql-plugin` sbt subproject, not as a core dependency. It is
loaded only when the user imports the GraphQL plugin:

```ssc
//> using dep "pkg:graphql"
```

or in `build.sbt` (via the sbt plugin):

```scala
sscPlugins += "io.scalascript::scalascript-graphql:1.0.0"
```

This keeps the base `ssc` binary free of a 10 MB graphql-java jar.

---

## 4. Migration

There is no migration — GraphQL is a new feature. Existing REST apps
are unaffected. The `graphql` fenced-block tag is new; it does not
conflict with any existing syntax.

---

## 5. Phases

### Phase 1 — Schema + resolvers + `serveGraphQL` (JVM/interpreter only)

**Goal**: `ssc run myapi.ssc` starts a GraphQL server on JVM.

Tasks:
- `runtime/std/graphql-plugin/` sbt subproject with `graphql-java` 22.x.
- `GraphQLIntrinsics`: `GraphQL.schema(sdl)`, `GraphQL.resolvers(…)`,
  `serveGraphQL(port, resolvers)`, `graphqlHandler(schema, resolvers)`.
- `GraphQLJvmRuntime`: build `graphql.GraphQL` instance from SDL +
  `DataFetchingEnvironment`-backed resolver wrappers.
- `graphql.ssc`: `extern` declarations + opaque type aliases.
- `Lang.scala`: add `val Graphql = "graphql"` + `def isGraphql`.
- `Backend` SPI: add `def graphqlBlockRunner: Option[GraphQLBlockRunner] = None`.
- `SectionRuntime`: handle `Lang.isGraphql` blocks via `interp.graphqlBlockRunner`
  (same pattern as `sqlBlockRunner` / `runSqlBlock`).
- `META-INF/services/scalascript.backend.spi.SourceLanguage`: register
  `GraphQLSourceLanguage` for the compile-time IR path (JVM/JS codegen Phase 2+).
  No hand-edit to `SourceLanguageRegistry.scala` needed — ServiceLoader handles it.
- `serveGraphQL` wires to the existing `serve()` / `route()` HTTP server:
  registers `POST /graphql` + `GET /graphql` (GET for browser form queries).
- `GraphQLPlugin` ServiceLoader.
- `examples/graphql-hello.ssc` — minimal Hello World query.
- `GraphQLIntrinsicsTest`: 10+ tests covering schema construction,
  resolver dispatch, simple query, variable substitution, error response,
  null field, list field, introspection query.

Effort: ~4 days. Spec: `docs/graphql.md §5 Phase 1`.

### Phase 2 — Async resolvers + GraphQL client + JS backend

**Goal**: resolvers can return `Future[A]` or `A ! Async`; `graphqlQuery`
client works; `--backend js --target node` serves GraphQL.

Tasks:
- Async resolver support in `GraphQLJvmRuntime`: detect `Source`/`Future`
  return and use `graphql-java`'s `DataFetcher` with `CompletableFuture`.
- `graphqlQuery(url, query[, variables])` extern: HTTP POST client.
- `GraphQLJsRuntime` + `runtime/backend/js/…/codegen/intrinsics/Graphql.scala`:
  JS codegen for `ssc emit-js --target node`. Per-intrinsic JS codegen lives in
  `runtime/backend/js/.../codegen/intrinsics/` (alongside `Http.scala`,
  `Ws.scala`), NOT in `JsGen.scala` itself.
- `runtime/backend/node/.../codegen/NodeBackend.scala`: adds
  `const {graphql, buildSchema} = require('graphql')` to the Node preamble and
  `"graphql": "^16.x"` to the generated `package.json`. This file owns all
  `require()` and `package.json` emission — not `JsGen`.
- Registers `POST /graphql` + `GET /graphql` in the Node.js HTTP handler via
  emitted `graphqlHandler` (JS `async` function wrapping
  `graphql({ schema, source, variableValues })`).
- `Feature.GraphQL` in `SpiCapabilities`.
- `examples/graphql-client.ssc` — query a public GraphQL API.
- Tests: async resolver round-trip (interpreter), JS codegen shape.

Effort: ~3 days. Spec: `docs/graphql.md §5 Phase 2`.

### Phase 3 — Subscriptions over WebSocket (`graphql-ws`)

**Goal**: `subscription` resolvers backed by `Source[A]` push events to
connected clients via the `graphql-ws` protocol.

```scalascript
subscription = Map(
  "userCreated" -> ((_: Map[String, Any]) => userCreatedSource)
)
```

Tasks:
- `serveGraphQL` registers a second WebSocket endpoint `GET /graphql/ws`
  using the **existing** `onWebSocket("/graphql/ws") { ws => … }` from
  `HttpIntrinsics` — no new WS primitives needed.
- Server-side `graphql-ws` protocol state machine (connection_init, subscribe,
  next, error, complete, ping, pong messages + flow control). The full message
  set makes this ~6 days, not 4 — the state machine is substantive even with
  `onWebSocket` available.
- `GraphQLJvmRuntime` bridges `Source[A]` → `graphql-java` `Publisher[A]`
  (Reactive Streams adapter; graphql-java 22.x supports it natively).
- Client-side: `graphqlSubscribe(url, query)(handler)` extern.
- Production mode flag: `serveGraphQL(port, resolvers, production = true)`
  disables introspection.
- `examples/graphql-subscriptions.ssc` — live-update feed.
- Tests: subscription lifecycle (subscribe → push N events → complete),
  error propagation, multiple concurrent subscribers.

Effort: ~6 days. Spec: `docs/graphql.md §5 Phase 3`.

### Phase 4 — Compile-time SDL validation + schema-aware completion

**Goal**: schema syntax errors are caught at `ssc build` time (not runtime).

Tasks:
- `GraphQLSourceLanguage.compileBlock(content, attrs)` runs SDL through
  `graphql-java`'s `SchemaParser` at compile time; reports errors by
  populating `BlockArtifact.diagnostics: List[Diagnostic]` (the field already
  exists on `BlockArtifact`). The `SourceLanguageRegistry` framework forwards
  diagnostics to the compiler and LSP.
  Note: `MarkupInterpolatorCheck` (a transform pass) is **not** the right
  pattern here — it handles string-interpolator sites like `html"…"`, not
  fenced blocks. Fenced-block errors belong in `compileBlock` itself.
- `SourceLanguageRegistry` already invokes `compileBlock` and collects
  `BlockArtifact.diagnostics` — no new framework wiring needed.
- LSP integration: `ssc lsp` reports SDL errors inline in the editor.
- `GraphQLSchemaCheckTest`: 6+ tests for valid SDL, field type error,
  undefined type reference, duplicate type, syntax error.

Effort: ~2 days. Spec: `docs/graphql.md §5 Phase 4`.

---

## 6. Testing strategy

| Phase | Tests | Kind |
|-------|-------|------|
| 1 | `GraphQLIntrinsicsTest` (10+) | Interpreter unit |
| 2 | Async resolver test, JS codegen shape (6+) | Interpreter + codegen |
| 3 | Subscription lifecycle (5+) | Interpreter integration |
| 4 | `GraphQLSchemaCheckTest` (6+) | Compiler unit |

No end-to-end browser tests in CI. Manual smoke with Apollo Sandbox
(`https://studio.apollographql.com/sandbox`) against a running `ssc run`
server is sufficient for Phase 1–3 acceptance.

---

## 7. Open questions

1. **`graphql-java` version pinning**: pin to the latest stable LTS at
   vendor time (22.x as of this writing; verify before Phase 1 starts).
   Major versions break resolver API; pin explicitly and document upgrade path.

2. **Introspection in production**: disable by default with
   `production = true` flag, or require explicit opt-out?
   Recommendation: disabled when `SSC_ENV=production` or `production = true`
   flag; enabled otherwise. Matches industry convention.

3. **Schema-first vs code-first long-term**: Phase 1–4 are schema-first
   (SDL in `graphql` block). A future code-first DSL
   (`object QueryType extends GraphQLObject { val user = field[User]("user") { ... } }`)
   would derive SDL from ScalaScript types. Deferred — schema-first covers
   the majority case and is easier to onboard.

4. **DataLoader (N+1 batching)**: graphql-java has built-in DataLoader support.
   Should we expose it in the resolver surface?
   Recommendation: defer to a Phase 5 library concern. Phase 1–3 resolvers
   execute per-field synchronously; N+1 is the user's responsibility for now.
   Add `GraphQL.dataLoader(batchFn)` in Phase 5 when demand surfaces.

5. **Federation**: Apollo Federation v2 schema directives + subgraph protocol.
   Explicitly out of scope for this milestone. A downstream
   `scalascript-graphql-federation` plugin can add it later.

6. **Error extensions**: graphql-java propagates Java exceptions as
   `{ "message": "...", "locations": [...], "path": [...] }`.
   Should we add `extensions: { "code": "..." }` for typed errors?
   Recommendation: expose `GraphQLError(message, code)` as a throw-able type
   in Phase 2.

7. **Plugin-loading order**: when the user loads both `graphql-plugin` and
   `http-plugin`, ServiceLoader order is classpath-dependent. The
   `graphqlBlockRunner` slot and `graphqlMount` (which calls the existing
   `route()` server) must be set up before `serveGraphQL` is called. Phase 1
   must verify that `Interpreter.installPlugins` sequences these correctly and
   document any ordering constraint in `GraphQLPlugin.description`.

8. **Plugin jar size budget**: `graphql-java` + Reactive Streams adapter +
   a JSON library totals ≥ 12 MB. The base `ssc` binary stays clean (graphql
   is loaded only via `//> using dep "pkg:graphql"`), but users loading it in a
   serverless / GraalVM native-image context should expect a size increase.
   Document prominently in `graphql.ssc` header and Phase 1 example.

---

## 8. Critical files

| File | Role |
|------|------|
| `runtime/std/graphql-plugin/…/GraphQLPlugin.scala` | Phase 1 NEW — SPI entry |
| `runtime/std/graphql-plugin/…/GraphQLIntrinsics.scala` | Phase 1 NEW — intrinsic table |
| `runtime/std/graphql-plugin/…/GraphQLJvmRuntime.scala` | Phase 1 NEW — graphql-java wiring |
| `runtime/std/graphql-plugin/…/GraphQLJsRuntime.scala` | Phase 2 NEW — graphql-js wiring |
| `runtime/std/graphql.ssc` | Phase 1 NEW — extern declarations |
| `lang/core/src/main/scala/scalascript/ast/Lang.scala` | Phase 1 — add `val Graphql` + `isGraphql` |
| `runtime/backend/spi/…/Backend.scala` | Phase 1 — add `graphqlBlockRunner` SPI method |
| `runtime/backend/interpreter/…/Interpreter.scala` | Phase 1 — install graphqlBlockRunner from plugins |
| `runtime/backend/interpreter/…/SectionRuntime.scala` | Phase 1 — handle `Lang.isGraphql` blocks |
| `lang/core/src/main/scala/scalascript/compiler/plugin/SourceLanguageRegistry.scala` | no change — ServiceLoader only |
| `runtime/backend/js/…/codegen/intrinsics/Graphql.scala` | Phase 2 NEW — JS graphqlHandler codegen |
| `runtime/backend/node/…/codegen/NodeBackend.scala` | Phase 2 — require('graphql') preamble + package.json |
| `build.sbt` | Phase 1 — new `graphqlPlugin` subproject |
| `examples/graphql-hello.ssc` | Phase 1 NEW |
| `examples/graphql-client.ssc` | Phase 2 NEW |
| `examples/graphql-subscriptions.ssc` | Phase 3 NEW |
