# OpenAPI contract platform — spec

**Status:** Phases 1-6 (foundation) landed on 2026-05-29: interpreter/JVM
OpenAPI defaults, shared generator, route metadata, security schemes,
`ssc emit-openapi` JSON/YAML export, and `components.schemas` with `SchemaNode`
model and `openApiRegisterSchema` intrinsic.

**External reference:** as of 2026-05-29, the OpenAPI Initiative authoritative
specification site (`https://spec.openapis.org/oas/latest`) lists OpenAPI 3.2.0
as the latest published OAS version, while the version index
(`https://spec.openapis.org/oas/`) also lists OpenAPI 3.1.2 and current 3.1
schema iterations. ScalaScript should keep OpenAPI 3.1 as the default
compatibility target for now, while planning selectable 3.2 output.

**Companions:**
- [`docs/future-protocols.md §4`](future-protocols.md)
- [`docs/graphql.md`](graphql.md)
- Future AsyncAPI spec for WebSocket/SSE routes

---

## 1. Goals

ScalaScript should treat OpenAPI as a first-class HTTP contract layer, not just
as generated documentation. Every ScalaScript HTTP server can expose and export
a machine-readable API description that is:

- **derived from source and typed route metadata** rather than hand-maintained;
- **stable enough for client generation** through deterministic `operationId`
  and schema names;
- **precise enough for contract tests** with status codes, content types,
  request bodies, response bodies, headers, cookies, and typed errors;
- **publishable** through public/internal/admin profiles and visibility rules;
- **portable** across interpreter, JVM generated servers, and future backends;
- **compatible with ecosystem tools** such as Swagger UI, OpenAPI Generator,
  API gateway importers, Schemathesis/Dredd-style test runners, and OAI
  ecosystem specs such as Overlays and Arazzo.

The source of truth is still the route surface plus typed ScalaScript metadata.
Hand-written OpenAPI may be imported or overlaid, but it must not silently drift
from the route contracts that ScalaScript can verify.

---

## 2. Non-goals

- **OpenAPI 2.0 / Swagger 2.0 output.** Not planned. If needed later, it should
  be a down-conversion tool, not the canonical contract format.
- **Replacing GraphQL introspection.** GraphQL has its own schema and
  introspection model; see [`docs/graphql.md`](graphql.md).
- **Modeling WebSocket/SSE as OpenAPI-only.** OpenAPI can describe the HTTP
  upgrade/handshake surface, but message streams need AsyncAPI or a dedicated
  companion document.
- **Silent best-effort documents.** Exporting an incomplete or guessed contract
  as if it were authoritative is worse than omitting fields. Unknown request or
  response shapes must remain explicit as `Any`/generic schemas unless the user
  supplies metadata.
- **Unbounded side effects during contract export.** The current dry-run path is
  useful, but long-term pure/static export is the target for production CI.

---

## 3. Contract Model

### 3.1 OpenAPI Versions

ScalaScript should support an explicit OpenAPI target version:

```bash
ssc emit-openapi api.ssc --oas-version 3.1
ssc emit-openapi api.ssc --oas-version 3.2
```

Planned behavior:

- Default: `3.1` / latest supported `3.1.x` dialect for broad tooling
  compatibility.
- Optional: `3.2` once generator and validator coverage exists.
- The `openapi` field is a format version, not the application version; app
  version stays in `info.version`.
- Generator tests must validate both JSON and YAML output against the relevant
  OAS schema where practical.

### 3.2 Route Contract

Each HTTP operation lowers to:

```scala
OpenApiOperation(
  method:        "GET",
  path:          "/users/:id",
  operationId:   "getUser",
  tags:          List("users"),
  visibility:    Public,
  request:       RequestContract(...),
  responses:     List(ResponseContract(...)),
  security:      List(SecurityRequirement(...)),
  metadata:      OpenApiMetadata(...)
)
```

The current implementation has `OpenApiRoute`; the long-term model should split
route identity, request contract, response contract, and publication metadata so
schema generation and compatibility checks are not tied to string rendering.

### 3.3 User-Facing Metadata

`@openapi(...)` remains the lightweight route annotation. The supported surface
should grow conservatively:

```scalascript
@openapi(
  operationId = "getUser",
  summary = "Get user by ID",
  description = "Returns a single user. 404 if not found.",
  tags = List("users"),
  deprecated = false,
  security = List("bearerAuth"),
  visibility = "public",
  externalDocs = "https://docs.example.com/users#get"
)
route("GET", "/users/:id") { req => ... }
```

Rules:

- `operationId` must be unique in the exported profile.
- Missing `operationId` is derived from typed route client metadata, handler
  name, or method/path fallback, in that priority order.
- `visibility` controls profile filtering: `public`, `internal`, `admin`,
  `private`, or project-defined profile names.
- Route annotations override derived metadata only for presentation, not for
  type safety. A route cannot claim a schema that contradicts its typed handler
  unless explicit override mode is enabled.

---

## 4. Schema Derivation

### 4.1 Components

The generator should emit stable `components.schemas` for ScalaScript types:

| ScalaScript type | OpenAPI schema |
|---|---|
| `String` | `{ type: "string" }` |
| `Int`, `Long`, `Short`, `Byte` | `{ type: "integer", format: ... }` where known |
| `Double`, `Float`, `BigDecimal` | `{ type: "number", format: ... }` where known |
| `Boolean` | `{ type: "boolean" }` |
| `Unit` | no body or `{ type: "null" }`, depending on context |
| `Option[A]` | not required and/or nullable, depending on field context |
| `List[A]`, `Seq[A]`, `Array[A]` | `{ type: "array", items: schema(A) }` |
| `Map[String, A]` | object with `additionalProperties: schema(A)` |
| `case class` | object with named properties and required fields |
| `enum` | string enum when cases are value-like; `oneOf` when cases carry data |
| `sealed trait` hierarchy | `oneOf` plus discriminator when a stable tag exists |

Schema names must be deterministic and collision-safe. Prefer fully-qualified
type names internally, with short names in output when there is no ambiguity.

### 4.2 Constraints And Examples

ScalaScript should support schema-relevant annotations on types and fields:

```scalascript
case class CreateUser(
  @minLength(1) @maxLength(80)
  name: String,

  @format("email")
  email: String,

  @minimum(0)
  age: Option[Int] = None
)
```

Planned annotations:

- numeric: `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`,
  `multipleOf`;
- string: `minLength`, `maxLength`, `pattern`, `format`;
- arrays: `minItems`, `maxItems`, `uniqueItems`;
- objects: `minProperties`, `maxProperties`;
- documentation: `description`, `example`, `externalDocs`;
- compatibility: `deprecated`, `experimental`, `internal`.

### 4.3 Recursive Types

Recursive schemas must use `$ref` through `components.schemas`, not inline
expansion. The generator must detect cycles and keep output finite.

---

## 5. Requests And Responses

### 5.1 Request Sources

OpenAPI generation should distinguish:

- path parameters;
- query parameters;
- headers;
- cookies;
- JSON body;
- form URL-encoded body;
- multipart body and uploaded files;
- raw binary body;
- text body.

Typed handlers should be able to declare this directly:

```scalascript
case class SearchUsers(
  q: String,
  page: Option[Int] = Some(1)
)

route("GET", "/users") { input: SearchUsers =>
  ...
}
```

For ambiguous handlers, `@openapi` may specify parameter location explicitly:

```scalascript
@openapiParam("X-Request-Id", in = "header", required = false)
```

### 5.2 Response Contracts

Responses must support multiple status codes and content types:

```scalascript
@openapiResponses(
  200 -> schema[User],
  404 -> schema[Problem],
  422 -> schema[ValidationProblem]
)
route("GET", "/users/:id") { req => ... }
```

Long-term preferred path is typed result ADTs:

```scalascript
enum GetUserResult:
  case Ok(user: User)              @status(200)
  case NotFound(problem: Problem)  @status(404)

route("GET", "/users/:id") { req => getUser(req.params("id")) }
```

The generator should map:

- `Response.json[A]` / typed route clients to JSON schema;
- `Response.text` to `text/plain`;
- HTML responses to `text/html`;
- file/binary responses to `application/octet-stream` or declared media type;
- redirects to 3xx responses with `Location` header;
- no-content responses to `204` without body.

### 5.3 Error Model

ScalaScript should define a standard Problem Details model, compatible with
RFC 9457-style `application/problem+json`, while still allowing project-specific
error ADTs. Default framework errors should be documented when enabled:

- validation failure: `400` or `422`;
- auth required: `401`;
- forbidden: `403`;
- not found: `404`;
- conflict: `409`;
- unhandled server error: `500`.

---

## 6. Security

Current explicit schemes stay valid:

```scalascript
openApiSecurity("bearerAuth", "bearer", "JWT")

@openapi(security = List("bearerAuth"))
route("DELETE", "/users/:id") { req => ... }
```

The contract platform should add:

- Basic auth;
- API key in header/query/cookie;
- HTTP bearer with format;
- OAuth2 flows: authorization code, client credentials, password, device code
  where applicable;
- OpenID Connect discovery URL;
- scopes and per-operation scope requirements;
- global security defaults with per-route override;
- profile-aware redaction of private/internal schemes.

Middleware auto-detection remains a best-effort helper, not the authority.
The authoritative source should be explicit route/security metadata or typed
middleware that exposes a contract hook.

---

## 7. Export, Profiles, And Overlays

### 7.1 CLI

Current:

```bash
ssc emit-openapi api.ssc
ssc emit-openapi api.ssc --format yaml -o openapi.yaml
ssc emit-openapi api.ssc --title "Users API" --version 2.0.0 --server https://api.example.com
```

Planned:

```bash
ssc emit-openapi api.ssc --oas-version 3.2
ssc emit-openapi api.ssc --profile public
ssc emit-openapi api.ssc --profile internal
ssc emit-openapi api.ssc --validate
ssc emit-openapi api.ssc --overlay overlays/public.yaml
```

### 7.2 Profiles

Profiles filter routes, schemas, tags, servers, security schemes, and examples.
The same source can produce:

- public partner API;
- internal service API;
- admin/debug API;
- local development API.

Filtering must be conservative: if a public route references an internal schema,
the export should fail unless the schema is explicitly public or redacted.

### 7.3 Overlays

OAI Overlays are a good fit for publication-specific changes that should not be
encoded in source annotations: vendor extensions, externally hosted docs,
gateway-specific extensions, or partner-specific descriptions. ScalaScript
should support applying overlays after deriving the canonical contract.

---

## 8. Validation, Diff, And Contract Tests

### 8.1 Validation

Planned commands:

```bash
ssc check-openapi api.ssc
ssc emit-openapi api.ssc --validate
```

Validation levels:

- structural OAS schema validation;
- semantic checks: duplicate `operationId`, missing schema refs, invalid
  security scheme references, invalid path parameter names;
- ScalaScript consistency checks: declared route response schema matches typed
  handler/result where known;
- profile checks: no internal references leak into public output.

### 8.2 Compatibility Diff

Planned command:

```bash
ssc diff-openapi old.yaml new.yaml
ssc diff-openapi --fail-on breaking old.yaml new.yaml
```

Breaking changes include:

- removed path/method;
- changed `operationId`;
- removed or newly required request field;
- narrowed enum;
- changed response status/content type/schema incompatibly;
- removed auth scheme or changed security requirement;
- removed public schema/tag used by clients.

Non-breaking changes include:

- added optional field;
- added route;
- added response status when existing statuses remain;
- added enum value only when the API compatibility policy allows it.

### 8.3 Contract Tests

OpenAPI should feed test generation:

```bash
ssc test-openapi api.ssc --server http://localhost:8080
ssc test-openapi api.ssc --profile public
```

Initial tests can be smoke-level: each operation has a valid example request,
server status/content-type matches the spec, and JSON responses validate
against declared schemas. Later phases can integrate property-based request
generation.

---

## 9. Import Existing OpenAPI

ScalaScript should eventually support OpenAPI as input:

```bash
ssc import-openapi openapi.yaml --out src/generated/api.ssc
ssc import-openapi openapi.yaml --client js
ssc import-openapi openapi.yaml --server-stubs
```

Generated artifacts:

- DTO case classes/enums;
- typed route client declarations;
- route stubs for server implementation;
- contract tests from examples;
- schema compatibility metadata for future diffs.

Import must preserve unknown vendor extensions in metadata so round-tripping does
not destroy publication-specific information.

---

## 10. Arazzo, AsyncAPI, And Adjacent Specs

- **Arazzo** describes multi-call workflows. ScalaScript can derive Arazzo from
  tests/tutorial flows or explicit workflow declarations later.
- **AsyncAPI** should describe WebSocket, SSE, DStream, and actor-message
  streaming protocols. OpenAPI can link to the AsyncAPI document but should not
  pretend to model message streams fully.
- **OpenAPI Links / callbacks / webhooks** should be supported for callback-style
  HTTP workflows before reaching for AsyncAPI.

---

## 11. Current Implementation Snapshot

### Phase 1 — Interpreter `/_openapi.json` + `/_swagger` ✓ Landed

- Auto-registered when `serve()` / `serveAsync()` is called.
- `/_openapi.json`: live OpenAPI document from route table.
- `/_swagger`: Swagger UI CDN page.
- Interpreter handler introspection for typed `Value.FunV` parameters.

### Phase 2 — JVM codegen + shared generator + response schema foundation ✓ Landed

- `OpenApiGenerator` in backend SPI owns shared route model and rendering.
- JVM generated servers register `GET /_openapi.json` and `GET /_swagger`.
- Front-matter/generated route registration can propagate non-`Any`
  `apiClients:` response metadata.
- Raw `route(...) { req => ... }` handlers still use generic `200 OK` unless
  additional typed metadata exists.

### Phase 3 — `@openapi` per-route metadata ✓ Landed

- Parser rewrites `@openapi(...)` before `route(...)` into a marker call.
- `Routes.Entry` carries `OpenApiMetadata`.
- Interpreter and JVM generated runtimes consume pending metadata on the next
  route registration.
- Supported: `summary`, `description`, `tags`, `deprecated`, `security`.

### Phase 4 — Security schemes ✓ Landed

- `openApiSecurity(name, scheme, format)` declares reusable schemes.
- Supported output: bearer/http and API key header schemes.
- Per-route `@openapi(security = List(...))` requirements are emitted.
- `authMw` heuristic remains deferred; explicit metadata is authoritative.

### Phase 5 — `ssc emit-openapi` CLI + YAML output ✓ Landed

- JSON stdout by default.
- YAML via `--format yaml` or `-o *.yaml`.
- `--title`, `--version`, repeatable `--server`.
- Abort-at-first-serve interpreter dry-run: routes before the first `serve(...)`
  are collected, no socket is opened, and later top-level code does not run.

### Phase 6 — `components.schemas` foundation ✓ Landed (foundation)

- `SchemaNode` sealed trait hierarchy: `StrNode`, `IntNode`, `NumNode`,
  `BoolNode`, `NullNode`, `ArrNode`, `ObjNode`, `RefNode`, `NullableNode`,
  `OneOfNode`.
- `SchemaNode.fromTypeName(typeName)` derives a node from a ScalaScript type
  string; `Option[T]` → `NullableNode` using OAS 3.1 `oneOf + null`.
- `OpenApiRoute.responseSchema: Option[SchemaNode]` — preferred over the legacy
  `responseType: Option[String]` when set.
- `generate()` and `generateYaml()` emit `components.schemas` when
  `schemaComponents: Map[String, SchemaNode]` is non-empty.
- `jsonSchema(typeName, schemaComponents)` returns `$ref` when the type name is
  registered in `schemaComponents`.
- `openApiRegisterSchema(name, properties, required)` — user-callable intrinsic
  that registers a named `ObjNode` schema in the session's schema component map.
- `NativeContextFeatureKeys.OpenApiSchemaComponents` feature key.
- `OpenApiRuntime` and `ssc emit-openapi` pass schema components through end
  to end.
- 20 `OpenApiGeneratorTest` + 21 `OpenApiRuntimeTest` = 41 tests.

Remaining for Phase 6: automatic derivation of `SchemaNode` from ScalaScript
`case class`, `enum`, `sealed trait`, and field-level constraint annotations.
Manual `openApiRegisterSchema` is the supported path until auto-derivation lands.

---

## 12. Planned Phases

### Phase 6 — Rich `components.schemas` (auto-derivation)

Automatically derive named schemas for case classes, enums, sealed traits;
support field-level constraint annotations (`@minLength`, `@format`, etc.),
recursive types via `$ref`, and schema examples/docs.

### Phase 7 — Typed request/response contracts

Model request locations, status codes, response headers, content types, typed
error ADTs, binary/form/multipart bodies, redirects, and no-content responses.

### Phase 8 — Operation identity, tags, and profiles

Add stable `operationId`, global tag metadata, visibility profiles, public vs
internal filtering, and leak checks.

### Phase 9 — Validation and compatibility diff

Add `check-openapi`, `emit-openapi --validate`, and `diff-openapi` with
breaking/non-breaking classification.

### Phase 10 — `import-openapi`

Generate DTOs, typed clients, server stubs, and tests from existing OpenAPI
documents while preserving vendor extensions.

### Phase 11 — Contract testing

Generate and run smoke/property contract tests from the derived OpenAPI
document against a live server or in-process handler runtime.

### Phase 12 — OAS 3.2, Overlays, Arazzo

Add selectable OAS 3.2 output, OAI Overlay application, and Arazzo workflow
export/import once the core HTTP contract is stable.

### Phase 13 — AsyncAPI companion

Track separately for WebSocket/SSE/DStream/actor streams. OpenAPI may link to
AsyncAPI but should not replace it.

---

## 13. Testing Strategy

| Area | Tests |
|------|-------|
| Rendering | JSON/YAML golden tests, escaping, deterministic ordering |
| Validation | OAS schema validation for every generated fixture |
| Schema derivation | primitives, options, collections, case classes, enums, sealed traits, recursion |
| Routes | path/query/header/cookie/body extraction; method grouping; internal route exclusion |
| Responses | status codes, content types, headers, typed errors, no-content, redirects |
| Security | bearer, basic, API key, OAuth2/OIDC, route/global requirements |
| Profiles | public/internal filtering, no leaked private schemas |
| Compatibility | breaking and non-breaking diffs |
| Import | round-trip DTO/client/stub generation from fixture specs |
| Contract tests | live and in-process smoke tests generated from examples |

Existing landed tests remain part of the regression baseline:

- `OpenApiGeneratorTest`
- `OpenApiRuntimeTest`
- `JvmGenEffectsRuntimeTest` OpenAPI cases
- `HttpPluginInterpreterTest`
- `EmitOpenapiCliTest`

---

## 14. Design Notes And Open Questions

Resolved:

- YAML output currently uses a lightweight generator. A full YAML library is not
  required until overlays/import/round-trip fidelity need comment/order
  preservation.
- `emit-openapi` currently uses abort-at-first-serve dry-run. This limits
  side effects after `serve(...)`, but effects before `serve(...)` still run.

Open:

1. **Pure static export.** Can route registrations, mounted handlers, security
   declarations, and type metadata be collected without evaluating top-level
   user code?
2. **Schema override policy.** Should users be able to override derived schemas,
   and if yes, should overrides be checked for compatibility with source types?
3. **OpenAPI 3.2 default timing.** When should the default move from 3.1 to
   3.2, given ecosystem tooling lag?
4. **Publication profiles.** Should profile names be fixed (`public/internal`)
   or arbitrary per project?
5. **Contract compatibility policy.** Should enum value additions and optional
   response fields be considered non-breaking by default?

---

## 15. Critical Files

| File | Role |
|------|------|
| `runtime/backend/spi/src/main/scala/scalascript/backend/spi/OpenApiGenerator.scala` | Shared route model and JSON/YAML generator |
| `runtime/backend/spi/src/main/scala/scalascript/backend/spi/IntrinsicImpl.scala` | OpenAPI dry-run sentinel and native-context hooks |
| `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/OpenApiRuntime.scala` | Interpreter adapter from route registry to generator |
| `runtime/backend/interpreter/src/main/scala/scalascript/server/Routes.scala` | Runtime route table carrying OpenAPI metadata |
| `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGen.scala` | JVM route/OpenAPI metadata emission |
| `runtime/http-server/jvm/src/main/scala/scalascript/server/jvm/RestRuntime.scala` | Generated-server OpenAPI defaults |
| `runtime/std/http-plugin/src/main/scala/scalascript/compiler/plugin/http/HttpIntrinsics.scala` | `route`, `openapi`, `openApiSecurity`, `serveAsync` dry-run behavior |
| `runtime/std/frontend-plugin/src/main/scala/scalascript/compiler/plugin/frontend/FrontendIntrinsics.scala` | `serve` dry-run behavior |
| `runtime/std/openapi.ssc` | User-facing OpenAPI extern declarations |
| `tools/cli/src/main/scala/scalascript/cli/Main.scala` | `ssc emit-openapi` command |
| `tools/cli/src/test/scala/scalascript/cli/EmitOpenapiCliTest.scala` | CLI export regression tests |
