# Contract Validation Platform - spec

**Status:** Planned. This document defines the shared validation model for
OpenAPI and GraphQL. No `ssc check-contract` command is implemented yet.

**Companions:**
- [`docs/openapi.md`](openapi.md)
- [`docs/graphql.md`](graphql.md)
- [`docs/typed-route-clients.md`](typed-route-clients.md)
- [`docs/data-mapping.md`](data-mapping.md)
- [`docs/typer-real-types-roadmap.md`](typer-real-types-roadmap.md)

---

## 1. Goals

ScalaScript should treat API contracts as build-time checked artifacts, not as
best-effort documentation. The same `.ssc` source can already define REST
routes, OpenAPI metadata, GraphQL SDL, GraphQL resolvers, typed route clients,
JSON codecs, and typed data mappings. Contract validation makes those surfaces
agree before a generated artifact, published schema, or client SDK is trusted.

The shared platform should:

- validate OpenAPI and GraphQL with one diagnostic model and one compatibility
  policy vocabulary;
- compare route and resolver signatures against the emitted or imported
  contract;
- validate request bodies, response bodies, path/query/header/cookie params,
  status codes, media types, GraphQL arguments, GraphQL return nullability, and
  typed errors where metadata is available;
- make dynamic or unknown shapes explicit instead of silently treating `Any` as
  type-safe;
- support publication profiles such as `public`, `internal`, `admin`, and
  project-defined names;
- allow external contracts and overlays, but only as checked inputs that cannot
  contradict source-derived types unless an explicit waiver is present;
- feed CI and review workflows with stable machine-readable diagnostics,
  compatibility diffs, and later contract tests.

The first implementation must stay practical: it should reuse the existing
OpenAPI generator, GraphQL plugin, typed route client metadata, and typed data
mapping codecs before adding new abstractions.
The planned compiler-side feed for those checks is the structured type evidence
roadmap in [`docs/typer-real-types-roadmap.md`](typer-real-types-roadmap.md).

## 2. Non-goals

- **Replacing protocol validators.** ScalaScript should reuse OpenAPI schema
  validators, GraphQL SDL/operation validators, and GraphQL engines where
  possible. The shared layer adds ScalaScript source-consistency checks.
- **Inferring every dynamic shape.** Raw `Request => Any`, dynamic GraphQL
  resolver maps, untyped JSON maps, and external service calls remain allowed.
  The validator reports unknowns according to the configured strictness level.
- **Network fetch during normal validation.** Remote OpenAPI documents,
  GraphQL introspection JSON, and overlays must be explicitly fetched or passed
  by path. CI should be reproducible without hidden network access.
- **A single contract format for all protocols.** OpenAPI and GraphQL keep their
  native formats. The shared IR is an internal validation and diff model.
- **Schema evolution guarantees before v1.0 stability.** This spec defines the
  compatibility vocabulary now; long-term evolution rules can tighten before
  the first stable ScalaScript release.

## 3. Architecture

### 3.1 Source And Contract Inputs

Validation consumes four kinds of input:

| Input | Examples | Role |
|---|---|---|
| Source surface | `route(...)`, `mount(...)`, `routes:` front matter, typed handlers, `apiClients:`, `@remote`, GraphQL resolvers | What the program actually exposes or calls |
| Type surface | case classes, enums, sealed ADTs, `JsonCodec[A]`, `SchemaNode`, `GraphQL.scalar[A]`, typed data mapping metadata | Request/response/argument/result shapes |
| Contract document | emitted OpenAPI, imported OpenAPI, GraphQL SDL, introspection JSON, operation documents | What clients and gateways see |
| Publication policy | profile, visibility, overlay, external docs, security redaction rules | Which subset is being validated |

The validator should prefer static extraction when available. Interpreter
dry-run remains an acceptable fallback for existing OpenAPI route registration,
but the long-term target is a side-effect-minimized source extractor.

### 3.2 Shared Contract IR

The shared validator should lower protocol-specific inputs into a small internal
model. Names are illustrative; the implementation can choose exact package and
case-class names.

```scala
enum ContractProtocol:
  case OpenAPI, GraphQL

enum ContractSeverity:
  case Info, Warning, Error

enum ContractVisibility:
  case Public, Internal, Admin, Private, Named(name: String)

case class ContractSurface(
  protocol: ContractProtocol,
  source: ContractSource,
  operations: List[OperationContract],
  types: Map[String, TypeShape],
  security: Map[String, SecurityShape],
  profiles: Set[String]
)

case class OperationContract(
  id: String,
  displayName: String,
  coordinates: OperationCoordinates,
  request: RequestShape,
  responses: List[ResponseShape],
  errors: List[ErrorShape],
  visibility: ContractVisibility,
  sourceSpan: Option[SourceSpan]
)
```

OpenAPI coordinates are `(method, path, operationId)`. GraphQL coordinates are
schema coordinates such as `Query.user`, `Mutation.createUser`, or a named
operation document such as `operation:GetUser`.

`TypeShape` should be shared with the typed data mapping direction where
possible. It needs primitives, objects, arrays, maps, enums, one-of/sealed ADTs,
nullable/optional wrappers, refs, constraints, examples, and an explicit
`Unknown(reason)` node.

### 3.3 Diagnostic Model

Diagnostics must be stable enough for CI and IDEs:

```scala
case class ContractDiagnostic(
  code: String,
  severity: ContractSeverity,
  message: String,
  protocol: ContractProtocol,
  operation: Option[String],
  path: List[String],
  sourceSpan: Option[SourceSpan],
  suggestion: Option[String]
)
```

Examples:

| Code | Meaning |
|---|---|
| `contract.openapi.missing-path-param` | `GET /users/:id` has no matching path parameter metadata |
| `contract.openapi.response-mismatch` | Handler/result type is incompatible with declared response schema |
| `contract.graphql.missing-resolver` | SDL field has no resolver or default property mapping |
| `contract.graphql.arg-mismatch` | Resolver argument type does not match GraphQL argument shape |
| `contract.profile.leak` | Public profile references an internal/private schema |
| `contract.dynamic.unknown` | Validator reached an untyped dynamic boundary |
| `contract.overlay.conflict` | Overlay changes a source-owned type/operation incompatibly |

The text format should be concise for humans. The JSON format should be stable
for CI annotations and later IDE integrations.

### 3.4 OpenAPI Checks

OpenAPI validation should reuse `OpenApiGenerator` output and add source
consistency checks:

- path templates and declared path parameters match exactly;
- query/header/cookie/body locations are declared or derivable;
- request body schema is compatible with typed handler input or explicit
  request metadata;
- response status codes and media types match typed result metadata,
  `Response.*` helpers, and declared `@openapiResponses`-style metadata once it
  exists;
- typed error ADTs map to status codes and `application/problem+json` where
  configured;
- `operationId` values are unique within the selected profile;
- security requirements reference declared schemes;
- schemas referenced from public operations are also public or explicitly
  redacted;
- overlays may add descriptions, vendor extensions, examples, external docs,
  and gateway metadata, but changing source-owned request/response shapes is an
  error unless the user enables override mode for that operation.

### 3.5 GraphQL Checks

GraphQL validation should reuse the GraphQL engine for SDL and operation
validation, then add ScalaScript resolver/type checks:

- resolver coordinates must point at real fields in the selected SDL;
- every root field that cannot be resolved by default property mapping must have
  a resolver in strict mode;
- resolver argument/input type must be compatible with GraphQL argument shapes,
  required fields, enum values, custom scalars, and `@oneOf` inputs;
- resolver result type must be compatible with GraphQL return type, including
  non-null and list nesting;
- mutation resolvers must not be mounted as query-only HTTP GET operations;
- subscription resolvers must return `Source[A]`, `Publisher[A]`, or an accepted
  async stream bridge for the selected transport;
- client operation documents must validate against the selected SDL or imported
  introspection JSON;
- persisted-operation manifests must match the operation text, hash, variables
  type, response type, and profile.

Dynamic resolver maps remain supported. In default mode they produce warnings
for unknown argument/result typing; in strict mode they fail unless explicit
dynamic waivers are attached.

### 3.6 Profiles, Visibility, And Overlays

The validation engine should treat profile filtering as part of the contract,
not a post-processing afterthought.

Rules:

- route, resolver, type, security, and example visibility must be checked before
  export;
- public operations cannot reference private/internal schemas unless the schema
  has a profile-safe projection;
- GraphQL profile filtering should use schema coordinates and directives or
  ScalaScript metadata, then validate the resulting SDL as a complete schema;
- OpenAPI overlays are applied after deriving the canonical source contract but
  before publication validation;
- GraphQL SDL extensions are merged before validation, with deterministic
  ordering and duplicate diagnostics.

### 3.7 Configuration And CLI

Planned CLI surface:

```bash
ssc check-contract api.ssc
ssc check-contract api.ssc --protocol openapi
ssc check-contract api.ssc --protocol graphql
ssc check-contract api.ssc --profile public --strict
ssc check-contract api.ssc --format json -o contract-diagnostics.json

ssc emit-openapi api.ssc --validate
ssc check-openapi api.ssc --profile public
ssc diff-openapi old.yaml new.yaml --fail-on breaking

ssc check-graphql api.ssc --profile public
ssc diff-graphql old.graphql new.graphql --fail-on breaking
```

Front matter should allow the same policy, because CI and local commands should
not require long flag lists:

```yaml
contracts:
  defaultProfile: public
  strict: false
  failOn: error
  dynamic: warn
  protocols: [openapi, graphql]
  baselines:
    - contracts/baseline.yaml
  overlays:
    openapi:
      public: overlays/public.openapi.yaml
    graphql:
      public: overlays/public.graphql
```

CLI flags override front matter. The first implementation can support only the
flags it needs; the config shape is the target contract.

### 3.8 Warning, Error, And Baseline Policy

Default local behavior should be useful without blocking exploratory scripts:

- malformed protocol documents: error;
- source/contract contradictions for known typed shapes: error;
- dynamic unknowns: warning;
- missing examples: info or warning depending on command;
- profile leaks: error for publication profiles;
- compatibility-breaking diffs: error only under `--fail-on breaking` or a
  configured release policy.

Large existing apps may need a baseline file for known warnings. Baselines must
match by diagnostic code, operation, path, and a stable source identity so new
warnings are still visible.

## 4. Migration

This is additive. Existing OpenAPI and GraphQL commands continue to work.

Expected migration path:

1. Add `ssc check-contract` as a non-blocking command that reports diagnostics.
2. Wire `emit-openapi --validate` and `check-graphql` to the shared diagnostic
   engine while keeping their existing outputs.
3. Let projects opt into `--strict` or `contracts.strict: true` after dynamic
   boundaries are annotated or replaced with typed metadata.
4. Add profile and overlay checks before encouraging published public contracts.
5. Add compatibility diffs and baselines for release workflows.

No existing `route(...)`, GraphQL resolver, `apiClients:`, or typed data mapping
syntax should be removed by this work.

## 5. Phases

### Phase 0 - Spec

Land this document, queue the implementation phases, and cross-link it from the
OpenAPI, GraphQL, README, and user guide documentation.

### Phase 1 - Shared IR And Diagnostics

Add the internal contract model, diagnostic data type, severity policy, and a
small extractor facade. Include unit tests for diagnostic rendering and JSON
output. No protocol-specific behavior changes yet.

### Phase 2 - OpenAPI Validation

Route `ssc emit-openapi --validate` and a new `ssc check-openapi` command
through the shared diagnostic engine. Start with structure, duplicate
`operationId`, path parameter matching, security refs, schema refs, response
schema compatibility where current metadata is known, and profile leak checks
for explicit visibility metadata.

### Phase 3 - GraphQL Validation

Route `ssc check-graphql` through the shared engine. Validate SDL, resolver
coordinates, missing resolvers in strict mode, argument/result compatibility for
typed resolver builders and known dynamic signatures, operation documents, and
persisted-operation metadata where present.

### Phase 4 - Profiles, Imports, And Overlays

Implement profile filtering for both protocols, OpenAPI overlay validation,
GraphQL SDL extension validation, imported contract checks, and diagnostics for
source-owned fields changed by overlays.

### Phase 5 - Compatibility Diff

Add `diff-openapi`, `diff-graphql`, and a shared compatibility classifier for
breaking, dangerous, and additive changes. Support `--fail-on` and baseline
files.

### Phase 6 - Contract Tests

Generate smoke tests from contracts and run them against either an in-process
handler runtime or a live server. Start with examples and status/content-type /
schema validation before adding property-based generation.

### Phase 7 - CI And IDE Integration

Document CI recipes, add JSON output suitable for annotations, expose the same
diagnostics through the language server or editor tooling once that surface
exists, and keep no-network validation as the default.

## 6. Testing Strategy

| Area | Tests |
|---|---|
| Shared IR | deterministic lowering, equality, stable ids, profile filtering |
| Diagnostics | text rendering, JSON rendering, baseline matching, source spans |
| OpenAPI | path params, request locations, response schemas, security refs, overlays, profile leaks |
| GraphQL | SDL errors, resolver coordinates, arg/result compatibility, operations, subscriptions |
| Dynamic boundaries | warning vs error policy, explicit waivers, strict mode |
| Compatibility | removed/changed operations, enum changes, nullability changes, response/status changes |
| CLI | exit codes, `--format json`, `--profile`, `--strict`, no-network behavior |
| Contract tests | in-process smoke, live-server smoke, example request validation |

Docs-only spec changes use `git diff --check`. Implementation phases should run
the focused CLI/plugin/backend tests they touch, then broader suites only when a
shared extractor or typed-data surface changes.

## 7. Open Questions

1. Where should the shared IR live: `runtime/backend/spi`, a new
   `backend/contract` module, or `backend/typed-data` plus protocol adapters?
2. Should `ssc check-contract` be the primary command, or should users mostly
   see protocol-specific `check-openapi` and `check-graphql` wrappers?
3. What is the exact waiver syntax for intentional dynamic boundaries and
   source/overlay conflicts?
4. Should profile names be fixed enough for tooling (`public`, `internal`,
   `admin`) or fully arbitrary from day one?
5. How far can OpenAPI export move toward pure static extraction before route
   registration semantics require a dry-run fallback?
6. Should GraphQL schema profile filtering happen through standard directives,
   ScalaScript metadata, external overlays, or all three?
7. Which compatibility policy should be the default for added enum values:
   additive, dangerous, or breaking for exhaustive clients?

## 8. Critical Files

Likely implementation touch points:

| File | Role |
|---|---|
| `runtime/backend/spi/src/main/scala/scalascript/backend/spi/OpenApiGenerator.scala` | Existing OpenAPI route/schema generation model |
| `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/OpenApiRuntime.scala` | Interpreter OpenAPI collection path |
| `runtime/std/graphql-plugin/src/main/scala/scalascript/compiler/plugin/graphql/GraphQLIntrinsics.scala` | GraphQL runtime, SDL, resolver, and HTTP behavior |
| `runtime/std/graphql.ssc` | User-facing GraphQL extern surface |
| `runtime/backend/typed-data/` | Shared JSON/type-shape metadata that should back validation |
| `tools/cli/src/main/scala/scalascript/cli/` | Planned check/diff command entry points |
