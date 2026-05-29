# OpenAPI 3.1 — spec

**Status:** Phase 1 landed (interpreter `/_openapi.json` + `/_swagger`).
Phase 2 landed for shared generation and JVM route emission. Phases 3–5 planned.

**Companion:** [`docs/future-protocols.md §4`](future-protocols.md)

---

## 1. Goals

Every ScalaScript HTTP server automatically exposes a machine-readable
description of its API surface — no hand-maintenance required.
The same description drives:

- **Swagger UI** at `/_swagger` for interactive exploration during development.
- **Client code generation** via `ssc emit-openapi` (standalone JSON/YAML export).
- **API gateway import** (AWS API Gateway, Kong, Traefik — all accept OpenAPI).
- **Contract testing** (Dredd, Schemathesis — send random valid requests and assert the server agrees).

The source of truth is always the live route table; the document is
derived, never hand-written.

---

## 2. Non-goals

- **OpenAPI 2.0 (Swagger 2).** Only OpenAPI 3.1 is targeted. Tooling has
  converged on 3.x; 3.1 aligns with JSON Schema 2020-12.
- **AsyncAPI for WebSocket / SSE routes.** Out of scope for this milestone;
  tracked as a Phase 6 candidate.
- **GraphQL introspection.** Separate milestone — `docs/graphql.md`.
- **gRPC / Protobuf reflection.** Separate, gated on HTTP/2.
- **Manual schema overrides** via hand-written YAML alongside the code.
  We derive; we don't blend.

---

## 3. Architecture

### 3.1 What already exists (Phase 1 — landed)

`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/OpenApiRuntime.scala`

- **`OpenApiRuntime.registerOpenApiDefaults(interp)`** — called by
  `HttpIntrinsics` when `serve()` / `serveAsync()` is invoked. Registers:
  - `GET /_openapi.json` — generates live OpenAPI 3.1 JSON from `RouteRegistry.all`.
  - `GET /_swagger` — serves Swagger UI (CDN-linked assets, no bundled files).
- **`OpenApiRuntime.generateOpenApiJson(registry)`** — public so tests call it directly.
  Produces valid JSON: `openapi: 3.1.0`, `info`, `paths` with per-method `parameters`
  (path + query), `requestBody` (POST/PUT/PATCH), and a stub `responses: { 200: OK }`.
- Path parameter conversion: `:id` → `{id}` (OpenAPI convention).
- Internal routes (`/_*`) excluded from the document.
- Handler introspection: `Value.FunV` param names + types drive `parameters` and
  `requestBody.properties`.
- **16 tests** in `OpenApiRuntimeTest` covering empty registry, path params, internal
  exclusion, query params, POST body, multi-method path, Swagger HTML shape.

### 3.2 Gaps

| # | Gap | Affects |
|---|-----|---------|
| G1 | JVM codegen — `RestRuntime.scala` / `JvmGen` emits no `/_openapi.json` route | Landed in Phase 2 |
| G2 | Response schema — registries only pass schema when a type is known | All |
| G3 | Auth declarations — no way to mark a route as requiring bearer/api-key | All |
| G4 | Route metadata — no description, summary, tags, deprecation per route | All |
| G5 | `ssc emit-openapi` CLI command — standalone export without serving | CLI |
| G6 | `@openapi(…)` annotation for per-route metadata | Parser + Typer |

### 3.3 Key types

```scalascript
// Route annotation (Phase 3)
@openapi(
  summary:     "Get user by ID",
  description: "Returns a single user. 404 if not found.",
  tags:        List("users"),
  deprecated:  false
)
route("GET", "/users/:id") { req =>
  ...
}

// Auth declaration (Phase 4)
@openapi(security: List("bearerAuth"))
route("DELETE", "/users/:id") { req =>
  ...
}
```

The annotation is optional; unannotated routes get auto-derived summary
(`"GET /users/:id"`) and no tags.

### 3.4 Schema derivation pipeline

```
                    Interpreter path                JVM codegen path
                    ────────────────                ────────────────
route() call   →  RouteRegistry.all            →  RestRuntime._routes
                        │                                   │
                        ▼                                   ▼
             OpenApiRuntime.generateOpenApiJson   _generateOpenApiJson()
               (Value.FunV introspection)         (path params only; no
               ← path params ✓                     handler introspection)
               ← query/body params ✓               ← path params ✓
               ← @openapi metadata (Phase 3)       ← @openapi metadata (Phase 3)
               ← response schema (Phase 2)         ← stub 200 OK only
                        │                                   │
                        └──────────────┬─────────────────────┘
                                       ▼
                            JSON string (OpenAPI 3.1)
                             served at /_openapi.json
                             exported by ssc emit-openapi

Note: The interpreter path has richer handler introspection because it
inspects Value.FunV objects at runtime. The JVM path generates static
Scala code — handler types are erased to (Request => Any) so param
inference requires the @openapi annotation (Phase 3) for full schema.
```

### 3.5 Module layout

```
lang/core/                               (no changes — annotation parsing only)
runtime/http-server/jvm/…/server/jvm/
  RestRuntime.scala                      ← Phase 2: add _registerOpenApiDefaults()
                                           _generateOpenApiJson() (path-only)
runtime/backend/
  interpreter/…/interpreter/
    OpenApiRuntime.scala                 ← Phase 1 ✓; Phase 2 uses shared generator
  jvm/…/codegen/
    JvmGen.scala                         ← Phase 2 ✓: emit /_openapi.json route
  spi/…/spi/
    OpenApiGenerator.scala               ← Phase 2 ✓: shared generation model + logic
    RouteAnnotation.scala                ← Phase 3: @openapi annotation type (NEW)
tools/cli/…/cli/
  Main.scala                             ← Phase 5: ssc emit-openapi subcommand
runtime/std/
  openapi.ssc                            ← Phase 3: @openapi extern annotation (NEW)
```

---

## 4. Migration

Phase 1 is already running in production — no migration needed for existing
apps. Phases 2–5 are additive; no existing route definitions change.

The `@openapi` annotation (Phase 3) is purely optional. An app without any
`@openapi` annotations continues to work; the document just lacks human-readable
summaries and tags.

---

## 5. Phases

### Phase 1 — Interpreter `/_openapi.json` + `/_swagger` ✓ Landed

Files: `OpenApiRuntime.scala`, `OpenApiRuntimeTest.scala` (12 tests).

- Auto-registered when `serve()` / `serveAsync()` is called.
- `/_openapi.json`: live OpenAPI 3.1 JSON from route table.
- `/_swagger`: Swagger UI CDN page.
- Interpreter only.

### Phase 2 — JVM codegen + shared generator + response schema foundation ✓ Landed

**Goal**: `ssc link --backend jvm --bytecode` produces `/_openapi.json` and
`/_swagger`; interpreter path gets richer response schema.

Tasks:
- `OpenApiGenerator` lives in `runtime/backend/spi/` and owns the shared route
  model, path conversion, parameter/body schema emission, response schema
  emission when a response type is supplied, JSON escaping, and Swagger UI HTML.
- The interpreter adapts `RouteRegistry.all` into `OpenApiGenerator.OpenApiRoute`
  and preserves the existing typed `Value.FunV.paramTypes` query/body inference.
- JVM generated servers register `GET /_openapi.json` and `GET /_swagger` from
  the same `_routes` table when `serve()` / `serveAsync()` starts. The generated
  script uses an inlined equivalent helper so standalone scala-cli output does
  not depend on a newly published `backend-spi` artifact.
- Response schema plumbing is in place via `OpenApiRoute.responseType`. Automatic
  handler return-type extraction for raw route registries remains a follow-up
  because the current JVM route closure type is `Request => Any`.
- Front-matter/generated route registration now matches `apiClients:` endpoint
  metadata by `(method, path)` and passes non-`Any` response type names into the
  generated JVM route table. Raw `route(...) { req => ... }` handlers stay on
  the safe generic `200 OK` fallback.
- Tests: `OpenApiGeneratorTest`, existing `OpenApiRuntimeTest`, JvmGen code-shape
  coverage, and a scala-cli JVM e2e probe for `/_openapi.json`.

Follow-up split from Phase 2:

- **openapi-p2b** ✓ Landed 2026-05-29 — response type metadata propagation:
  typed front-matter/generated route declarations now carry response type
  metadata into `OpenApiRoute.responseType`; raw `route(...) { req => ... }`
  handlers remain on the safe `{ "200": { "description": "OK" } }` fallback.

### Phase 3 — `@openapi` per-route annotation

**Goal**: route authors can add human-readable metadata without any runtime cost.

```scalascript
//> using dep "std.openapi"

@openapi(summary = "List all users", tags = List("users"))
route("GET", "/users") { req => Response.json(users) }

@openapi(summary = "Get user", deprecated = true)
route("GET", "/users/:id") { req => ... }
```

Tasks:
- `runtime/std/openapi.ssc`: `extern def openapi(summary, description, tags, deprecated)`.
  This is a no-op at runtime — the annotation lives in the route's
  `RouteEntry` metadata.
- `RouteEntry` in `RouteRegistry`: add `metadata: RouteMetadata` field
  (summary, description, tags, deprecated).
- `HttpIntrinsics`: when `@openapi(…)` precedes a `route()` call, merge
  metadata into `RouteEntry`.
- `OpenApiGenerator`: use metadata when present, fallback to auto-derived.
- `OpenApiRuntimeTest`: 6+ new tests for annotated routes.

Effort: ~2 days. Spec: `docs/openapi.md §5 Phase 3`.

### Phase 4 — Security schemes + auth declarations

**Goal**: routes protected by `authMw { … }` (or `@openapi(security = …)`)
appear with correct `securityRequirements` in the document. Swagger UI's
"Authorize" button works.

```scalascript
// Declare global scheme once
openApiSecurity("bearerAuth", scheme = "bearer", format = "JWT")

// Per-route (explicit)
@openapi(security = List("bearerAuth"))
route("DELETE", "/users/:id") { req => ... }

// Auto-detected from middleware (heuristic)
authMw {
  route("GET", "/admin/stats") { req => ... }
}
```

Tasks:
- `openApiSecurity(name, scheme, format)` extern in `openapi.ssc`.
- `OpenApiGenerator`: emit `components.securitySchemes` + per-operation
  `security` array.
- Middleware heuristic: if `authMw` wraps a `route()`, infer `bearerAuth`
  requirement automatically (best-effort; explicit `@openapi` wins).
- Swagger UI renders the "Authorize" padlock and allows pasting a token.
- Tests: 4+ scenarios (no auth, bearer, api-key, mixed).

Effort: ~2 days. Spec: `docs/openapi.md §5 Phase 4`.

### Phase 5 — `ssc emit-openapi` CLI + YAML output

**Goal**: export the OpenAPI document without starting the server.

```bash
ssc emit-openapi api.ssc                  # JSON to stdout
ssc emit-openapi api.ssc -o api.yaml      # YAML to file
ssc emit-openapi api.ssc --format yaml    # explicit format
ssc emit-openapi api.ssc --title "My API" --version 2.0.0
```

Tasks:
- `emitOpenapiCommand` in `tools/cli/Main.scala`.
- `--format json|yaml` flag. YAML serialisation via simple string transform
  (no extra library — OpenAPI structure is shallow enough for a manual
  YAML builder that outputs valid YAML 1.2).
- `--title`, `--version`, `--server` flags override `info` / `servers`.
- Runs the interpreter in "dry" mode (no `serve()` side-effects) to collect
  registered routes, then calls `OpenApiGenerator.generate()`.
- `EmitOpenapiCliTest`: 4+ tests (JSON output shape, YAML output, flag overrides).

Effort: ~2 days. Spec: `docs/openapi.md §5 Phase 5`.

---

## 6. Testing strategy

| Phase | Tests | Kind |
|-------|-------|------|
| 1 ✓ | `OpenApiRuntimeTest` (12) | Interpreter unit |
| 2 ✓ | `OpenApiGeneratorTest`, `OpenApiRuntimeTest`, JvmGen code-shape + JVM e2e | SPI + interpreter + JVM codegen |
| 3 | `OpenApiRuntimeTest` annotation cases (6+) | Interpreter unit |
| 4 | `OpenApiRuntimeTest` security cases (4+) | Interpreter unit |
| 5 | `EmitOpenapiCliTest` (4+) | CLI subprocess |

No integration tests against Swagger UI / Postman in CI — those require a
browser; manual smoke is sufficient.

---

## 7. Open questions

1. **YAML serialisation library**: roll a minimal builder (avoids a dep) or
   pull `circe-yaml` / `snakeyaml`? Recommendation: minimal builder for Phase 5
   (structure is shallow), revisit if AsyncAPI (Phase 6) requires deep YAML.

2. **Middleware heuristic for auth detection** (Phase 4): `authMw` is a user
   function, not a first-class keyword. Can we detect it reliably from the
   AST? Alternative: require explicit `@openapi(security = …)`. Recommendation:
   explicit annotation for correctness; middleware heuristic as best-effort
   opt-in via `useOpenApiAuth()`.

3. **`/ssc emit-openapi` dry-run mode**: running the interpreter to collect
   routes means top-level side effects (DB connections, etc.) fire.
   Alternative: static analysis of `route()` calls from the AST without
   running the interpreter. Recommendation: interpreter dry-run for Phase 5
   (simpler); static analysis as a Phase 6 enhancement.

4. **AsyncAPI for SSE / WS** (Phase 6): AsyncAPI 3.0 describes WebSocket and
   SSE channels. Worth shipping? Depends on demand. Track separately.

---

## 8. Critical files

| File | Role |
|------|------|
| `runtime/backend/interpreter/…/OpenApiRuntime.scala` | Phase 1 ✓; Phase 2 shared-generator adapter |
| `runtime/backend/interpreter/src/test/scala/scalascript/OpenApiRuntimeTest.scala` | Phase 1/2 tests (16) |
| `runtime/backend/spi/…/OpenApiGenerator.scala` | Phase 2 ✓ — shared route model and generator |
| `runtime/backend/spi/…/RouteAnnotation.scala` | Phase 3 NEW — @openapi metadata |
| `runtime/backend/jvm/…/codegen/JvmGen.scala` | Phase 2 ✓ — emit /_openapi.json route |
| `runtime/http-server/jvm/…/server/jvm/RestRuntime.scala` | Phase 2 ✓ — generated-server OpenAPI defaults |
| `runtime/std/http-plugin/…/HttpIntrinsics.scala` | Phase 3 — merge @openapi into RouteEntry |
| `tools/cli/…/cli/Main.scala` | Phase 5 — `ssc emit-openapi` subcommand |
| `runtime/std/openapi.ssc` | Phase 3 NEW — extern annotation declaration |
