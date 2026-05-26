# Typed Route Clients

Status: **planned / partially implemented** — May 2026. Phase 1 landed the
front-matter metadata MVP: `apiClients:` / `api-clients:` declarations parse
into AST metadata and JVM codegen preserves endpoint method/path/type metadata.
Phase 2 landed callable JVM/Swing in-process clients. HTTP clients for
Electron, browser, split-process, and distributed modes remain planned.

This document defines generated typed clients for ScalaScript backend routes.
The first target is JVM/Swing in-process full-stack mode: frontend code should
call a typed API function, while the generated client uses the same backend
route boundary as `fetchAction` and `fetchTable`. Later phases reuse the same
contract for HTTP clients in browser, Electron, split-process, and distributed
modes.

## Goals

- Let frontend code call backend APIs without hand-writing URL strings, request
  JSON, response parsing, or `fetchAction` glue for every operation.
- Preserve route/API boundaries. Client code must not call server-only
  databases, secrets, mutable backend state, or arbitrary functions directly.
- Reuse existing typed handler semantics where possible: case class input,
  case class output, path/query/body binding by field name, JSON response
  serialization, and explicit error handling.
- Compile the same source for multiple transports:
  - `in-process` for compatible monolithic runtimes such as JVM/Swing;
  - `http` for browser, Electron, split-process, and distributed modes.
- Make the MVP small enough to ship incrementally: unary request, unary
  response, JSON payloads, explicit route metadata.
- Keep compatibility with raw `route(method, path) { req => ... }`,
  `fetchAction`, and `fetchTable`.

## Non-Goals

- Do not replace `route()` or `mount()`. Typed clients are a client-side layer
  over the same route registry.
- Do not expose direct same-process function calls as the public model. Even
  when implementation can optimize in-process dispatch, semantics remain
  request/response across a backend boundary.
- Do not design a full RPC framework with streaming, subscriptions,
  bidirectional channels, retries, auth refresh, or schema evolution in the
  first phase.
- Do not solve typed data mapping across SQL/ObjectStore/graphs/Spark here.
  That remains owned by [`data-mapping.md`](data-mapping.md).
- Do not require browser clients to support `in-process`; browser-to-JVM
  remains HTTP.

## Architecture

Typed route clients sit above `BackendTransport` and below frontend widgets:

```text
frontend code
  -> generated typed client method
      -> encode request DTO to BackendRequest
          -> BackendTransport
              -> in-process route registry
              -> HTTP fetch / Electron JVM REST / remote server
          -> decode BackendResponse to response DTO
```

The generated client should use the existing SPI-level transport shape:

```scala
trait BackendTransport:
  def request(req: BackendRequest): Future[BackendResponse]
```

The exact async type may differ per backend during implementation, but the
observable contract is request/response with method, path, headers, body,
status, headers, and response body.

### User Model

The target user-facing declaration should be explicit and metadata-driven:

```scalascript
case class CreateMessage(text: String)
case class Message(id: Int, text: String)

apiClient object Messages:
  post "/api/messages" def create(input: CreateMessage): Message
  get  "/api/messages/:id" def get(id: Int): Message
```

Phase 1 uses front-matter metadata as the implementation MVP:

```yaml
apiClients:
  Messages:
    endpoints:
      - name: create
        method: POST
        path: /api/messages
        request: CreateMessage
        response: Message
      - name: get
        method: GET
        path: /api/messages/:id
        request: Int
        response: Message
```

The parser also accepts the list-shaped spelling:

```yaml
api-clients:
  - name: Messages
    endpoints:
      - name: delete
        method: POST
        path: /api/messages/delete
        request-type: DeleteMessage
        response-type: Unit
```

A later phase may use an ordinary library-style declaration if it fits better:

```scalascript
val Messages = apiClient("Messages")(
  endpoint[CreateMessage, Message]("POST", "/api/messages", "create"),
  endpoint[Int, Message]("GET", "/api/messages/:id", "get")
)
```

The important semantic shape is stable:

| Field | Meaning |
|---|---|
| method | HTTP method / route method |
| path template | Route path, including `:param` segments |
| request type | Case class, tuple, primitive, or `Unit` |
| response type | Case class, ADT subset, primitive, `Unit`, or `Response` |
| name | Generated client method name |

### Request Encoding

MVP encoding follows typed handler binding rules in reverse:

1. Fields matching `:path` segments are encoded into the path.
2. For `GET` requests, remaining primitive fields are query parameters.
3. For non-`GET` requests, remaining fields are encoded as JSON body.
4. Headers are reserved for a later phase, except generated
   `Content-Type: application/json` for JSON bodies.

If request type is a primitive and the path has exactly one path parameter, the
primitive fills that parameter. Otherwise primitive non-`GET` requests use the
raw JSON value body.

### Response Decoding

MVP response decoding:

- 2xx with `Unit` response type discards the body.
- 2xx with typed response decodes JSON into the expected type.
- Non-2xx returns a typed client error rather than throwing raw transport
  internals.
- A response type of `Response` preserves the raw status, headers, and body.

The first implementation may use existing JSON helpers and generated codec
code. Later phases should share the planned typed data mapping codecs once
that layer exists.

### Error Model

Generated clients should expose a small error algebra:

```scala
enum ApiClientError:
  case Transport(message: String)
  case Status(status: Int, body: String)
  case Decode(message: String, body: String)
```

The public return shape is an open implementation decision:

- `Result[A, ApiClientError]` if the language has a stable result type;
- `Either[ApiClientError, A]`;
- direct `A` with thrown/failed errors only as a temporary codegen shortcut.

Phase 1 should pick the least invasive option that works across JVM and JS
backends.

### Transport Selection

Typed clients do not choose transport at call sites. They ask the current
runtime for the configured backend transport:

| Mode | Transport |
|---|---|
| JVM/Swing monolithic | Generated JVM route-registry dispatcher, later unified with `InProcessBackendTransport` |
| Electron + JVM REST | Implemented in bundle/dev-run smoke tests: Promise-returning HTTP clients over `fetch` to the injected local JVM backend URL |
| Browser React/Vue/Solid + JVM | Implemented for JS codegen: Promise-returning HTTP clients over `fetch`, using configured `server-url` / runtime base URL when present |
| Server/client split | HTTP, partly implemented through the JS/browser client generation path |
| Interpreter tests | `InProcessBackendTransport` where supported |

Unsupported target pairs must fail with diagnostics that explain which
transport is missing.

## Migration

Existing code keeps working:

- raw `route()` handlers remain valid;
- `mount()` typed handlers remain valid;
- `fetchAction` and `fetchTable` remain supported for simple UI workflows;
- `examples/frontend/swing-fullstack/` remains the string/JSON baseline.

Typed clients add an opt-in route declaration layer. Phase 1 stores explicit
front-matter declarations as metadata only. A later migration may derive typed
clients automatically from typed route declarations, but the MVP keeps explicit
client declarations so implementation can ship without a global route analyzer.

## Phases

### Phase 0 — Spec And Backlog

Land this document, reference it from README/user guide, and add the milestone.
No runtime behavior changes.

### Phase 1 — Minimal API Client IR

Add a parser/AST/codegen representation for typed endpoint declarations. The
MVP may lower to generated helper functions rather than a permanent public AST
node if that is cheaper, but tests must prove the declared method/path/types
survive lowering.

Landed 2026-05-25: front-matter `apiClients:` / `api-clients:` parse into
`ApiClientDecl` / `ApiEndpointDecl` AST metadata. JVM codegen emits
`_TypedRouteClientEndpoint` metadata in generated Scala so Phase 2 can add
callable clients without re-parsing YAML. Runtime client calls are still
planned.

### Phase 2 — JVM/Swing In-Process Client

Generate JVM client methods that call the existing same-process route registry
dispatcher used by Swing `fetchAction` and `fetchTable`. Add a Swing example
that creates, reads, and deletes typed `Message` values without manual JSON.

Landed 2026-05-25: when the effective frontend is `swing`, JVM codegen now
emits callable client objects from `apiClients:` metadata. Generated methods
encode request values, substitute primitive path parameters, send the request
through the generated JVM `BackendTransport`, reject non-2xx responses with a
runtime error, and decode JSON responses into primitive, `List[T]`, `Option[T]`,
and case-class product shapes using Scala 3 mirrors. Non-Swing JVM codegen
still emits metadata only until Phase 3 HTTP transport lands.

Example: [`examples/frontend/swing-typed-client/`](../examples/frontend/swing-typed-client/)
creates, lists, deletes, and recreates `Message` values through a generated
`Messages` client object without hand-written frontend JSON or URL dispatch.

### Phase 3 — HTTP Client Transport

Generate equivalent HTTP clients for browser/Electron/split modes. The same
typed client source should select HTTP when `--server-url`, Electron JVM REST,
or browser frontend mode is active.

Partially landed 2026-05-25: JS codegen now emits front-matter
`apiClients:` as callable HTTP client objects plus `_ssc_typedRouteClients`
metadata. Generated methods return Promises, build path parameters and GET
query strings from primitive values or case-class/plain-object fields, send
JSON request bodies with `fetch`, reject non-2xx responses, and parse JSON
responses. In browser SPA output, `emit-spa --server-url` / client mode can
inject `globalThis.__sscBackendBaseUrl`, and the existing browser fetch patch
forwards relative calls to that JVM backend URL.

Landed 2026-05-25 follow-up: the Electron JVM REST dev path now has a smoke
test that starts a fake JVM backend process, builds the Electron bundle with
the injected backend URL, launches fake Electron, and verifies that the renderer
bundle contains the generated typed HTTP client runtime and `Messages` methods.

Landed 2026-05-25 follow-up:
[`examples/frontend/typed-client-distributed/`](../examples/frontend/typed-client-distributed/)
uses one `.ssc` file for both `ssc run --mode server --backend jvm` and
`ssc run --mode client --frontend react|electron --server-url ...`. The example
also exercises raw JavaScript blocks in SPA/client output so browser UI glue
can call the generated Promise-returning `Messages` client.

Landed 2026-05-25 follow-up: JS codegen recognizes
`awaitClient(promise)` in client-side ScalaScript and lowers it to JavaScript
`await promise`, automatically enabling the async top-level wrapper. This lets
client code write `val rows = awaitClient(Messages.list())` instead of dropping
to `.then(...)` for simple typed route calls. Blocks marked
`@side=client` are skipped by JVM codegen, and blocks marked `@side=server`
are skipped by JS codegen, so the same `.ssc` source can contain client-only
awaits and server-only backend code.

Landed 2026-05-26 follow-up: broader async syntax/type-system integration.
JS codegen now auto-detects `def` functions whose bodies contain `awaitClient`
and emits `async function` for them, so `def loadAll() = awaitClient(Messages.list())`
generates a valid `async function loadAll()` rather than a `function` with a
bare `await` inside. `def` functions detected as async are excluded from the
while-loop TCO and mutual-TCO trampolining paths, which are incompatible with
`async`. For-comprehensions where all generators use `awaitClient(...)` are
lowered to sequential-await async IIFEs —
`for { msgs <- awaitClient(A); drafts <- awaitClient(B) } yield msgs ++ drafts`
generates `(async () => { const _t1 = await A; ...; return ...; })()` rather
than the previously-broken nested-`flatMap` form that placed bare `await`
inside non-async lambdas. The result of such an expression is a Promise; wrap
with `awaitClient(for { ... } yield ...)` to obtain the resolved value in an
async context.

### Phase 4 ✓ Landed (2026-05-26) — Shared Codecs

Replace ad hoc generated JSON encoding/decoding with the shared typed mapping
codec layer once it exists. Keep compatibility wrappers so Phase 2/3 examples
continue to run.

Partially landed 2026-05-25: generated JVM/Swing and JS HTTP clients now call a
stable typed JSON codec facade:

- `_ssc_typed_json_encode(value, typeName)`
- `_ssc_typed_json_decode_response(text, contentType, typeName)`

Partially landed follow-up 2026-05-25: the facade source moved into the shared
`backend/typed-data` runtime module and JVM/JS codegen now imports the same
contract snippets. This is still not the final derives-based mapping layer, but
request call sites no longer embed `_toJsonValue`, `_fromJson`, or
`JSON.stringify` directly, and future data-mapping work can replace the facade
body without changing generated client method shape.

Partially landed follow-up 2026-05-25: JVM/Swing generated clients now route
typed request encoding and typed response decoding through
`scalascript.typeddata.JsonCodec[T]`. Case-class and sealed-ADT
request/response values use the current `derives JsonCodec` support
automatically through Scala 3 `Mirror`; ADTs use the `"$type"` / `"value"`
envelope from `data-mapping.md`.
Follow-up landed 2026-05-26: JS/browser/Electron generated clients now pass
request/response type names into the same facade. JS codegen registers
case-class and enum-case constructor metadata in a lightweight runtime codec
registry, so HTTP responses decode back into generated JS case-class values
instead of plain objects where the shape is known.

### Phase 5 — Route Derivation And Validation

Optionally derive client declarations from typed route declarations or typed
`mount()` handlers. Add static diagnostics for mismatched route paths,
unfilled path params, unsupported request fields, and response decode gaps.

Partially landed 2026-05-26: JsGen and JvmGen now perform static path-param
validation during code generation. For each declared `apiClients:` endpoint,
the generator extracts `:param` names from the path template and checks them
against the declared request type:

- `Unit` request type with any path params → warning (Unit has no fields).
- Primitive request type (`Int`, `Long`, `String`, etc.) with more than one
  path param → warning (a single primitive can fill at most one param).
- User-defined case class request type: the generator scans case class
  declarations in the same module; any path param without a matching field
  name → warning. Types not locally defined are skipped (no cross-file
  analysis yet).

Warnings are emitted on stderr as `[ssc warning] ...` and also written as
`// [ssc warning] ...` comments in the generated JS or Scala output so they
are visible when inspecting the artifact.

Still planned for Phase 5: route derivation from existing `route()` /
`mount()` handlers, cross-file type analysis, and integration with a
structured diagnostic API rather than stderr + comments.

### Phase 6 — Advanced Shapes

Add auth/header parameters, streaming responses, SSE/WebSocket subscriptions,
pagination helpers, retries, and cancellation only after unary JSON clients are
stable across JVM and JS.

Partially landed 2026-05-26: auth and custom header injection. Both the JS
and JVM/Swing typed route client runtimes now include a module-global extra
headers map and two public helpers:

- `_ssc_api_set_headers(headers)` — replaces the full extra-headers map with
  the given key-value pairs (JS: plain object; JVM: `Map[String, String]`).
  Call with an empty map to clear all extra headers.
- `_ssc_set_auth_token(token)` — convenience wrapper: sets (or clears, if the
  token is null/empty) the `Authorization: Bearer <token>` header in the
  extra-headers map, leaving all other custom headers unchanged.

Every subsequent call to a generated typed route client method merges these
extra headers into the outgoing request. In JS, `Object.assign({}, _ssc_api_extra_headers)`
is copied into `fetch` `init.headers` before the per-request `Content-Type` is
applied. In JVM/Swing, the headers are merged into `BackendRequest.headers`
alongside the per-request `Content-Type` before the in-process transport
dispatch.

Still planned for Phase 6: streaming responses, SSE/WebSocket subscriptions,
pagination helpers, per-endpoint header overrides, retries, and cancellation.

## Testing Strategy

- Parser/AST or lowering tests for endpoint declarations.
- JVM codegen tests that assert method/path/type metadata is emitted and that
  Swing mode emits callable client methods.
- In-process dispatch tests for path params, query fields, JSON body, status
  errors, and decode errors.
- Swing no-socket example test that verifies the generated code contains typed
  client dispatch and no nested `scala-cli`.
- HTTP client emission tests for browser mode, Electron JVM REST bundle smoke
  tests, and distributed same-source server/client example tests.
- Compatibility tests proving raw `fetchAction`/`fetchTable` examples still
  work while typed clients are present.

## Open Questions

- Should the public spelling be `apiClient object`, `client object`,
  `endpoint[...]`, or generated from existing typed routes?
- What result type should clients return before the standard library has a
  single canonical `Result`? Phase 2 uses direct return values with runtime
  exceptions on non-2xx/decode failures as a temporary JVM/Swing shortcut.
- Should the JVM/Swing generated `BackendTransport` later share a concrete
  implementation with the interpreter `InProcessBackendTransport` class? The
  generated path now uses the same SPI request/response contract, but still has
  a codegen-local adapter over the generated JVM route registry.
- How much of typed handler deserialization can be reused immediately for
  request encoding/response decoding?
- Should auth headers be part of Phase 1 metadata or deferred until there is a
  real secured client example?
