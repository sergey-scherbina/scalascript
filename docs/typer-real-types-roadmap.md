# Typer Real Types Roadmap - spec

**Status:** Planned. Some narrow real-type inference has already landed for
top-level summaries (`TyperRealTypesTest`), exact numerics, typed route clients,
OpenAPI schema components, and typed data codecs. This document defines the
next tightening pass. It does not claim that full real-type propagation is
implemented today.

**Companions:**
- [`docs/architecture.md`](architecture.md)
- [`docs/data-mapping.md`](data-mapping.md)
- [`docs/typed-route-clients.md`](typed-route-clients.md)
- [`docs/openapi.md`](openapi.md)
- [`docs/graphql.md`](graphql.md)
- [`docs/contract-validation.md`](contract-validation.md)
- [`docs/type-evidence-inventory.md`](type-evidence-inventory.md)

---

## 1. Goals

ScalaScript should stop treating `Any` as the default answer whenever a type is
useful outside the immediate expression. `Any` is still a valid top type and a
necessary dynamic boundary, but it should be explicit and explainable.

The next real-types pass should:

- carry real `SType` values through exported symbols, `.scim` interfaces, and
  cross-module imports;
- infer more unannotated top-level `val` and `def` result types when the body is
  local, pure enough, and cheap to inspect;
- preserve case-class fields, enum cases, sealed ADT shapes, generic type
  arguments, function signatures, and effect rows in exported metadata;
- replace stringly typed route metadata with structured type evidence while
  keeping existing `requestType` / `responseType` strings for compatibility;
- feed OpenAPI, GraphQL, contract validation, typed route clients, Dataset,
  MapReduce, Spark, and typed data mapping from the same type evidence;
- let plugins and source-language blocks publish typed signatures without
  forcing every plugin to depend on interpreter internals;
- expose diagnostics and counters for remaining `Any` so future work can reduce
  it intentionally.

The practical target is not a full Scala 3 typer. It is a reliable, shared type
evidence pipeline for the parts of the language that are exported, serialized,
called remotely, persisted, or used to generate schemas.

## 2. Non-goals

- **Full Scala 3 semantic parity.** The JVM backend can still delegate standard
  Scala details to Scala 3 / scala-cli. ScalaScript needs enough typed metadata
  for its own IR and backends.
- **Speculative inference across arbitrary calls.** Unknown function calls,
  reflection, opaque foreign code, raw JSON maps, and plugin-provided dynamic
  values should remain `Unknown` / `Any` unless a signature is available.
- **Removing `Any`.** `Any` remains the top type, the dynamic boundary type, and
  the escape hatch for incremental migration.
- **Breaking existing `.sscc` / `.scim` readers immediately.** Structured type
  evidence must be additive first; legacy string fields remain until a
  compatibility window is defined.
- **Runtime reflection as the main solution.** Type evidence should be produced
  by the parser/typer/interface extractor/code generators, not by depending on
  target runtime reflection.

## 3. Current State

The project already has several pieces of the target shape:

- `SType` models primitives, named types, functions, unions, intersections,
  effect rows, opaque types, `BigInt`, and `Decimal`.
- Top-level `DefSummary` inference now records declared annotations and a narrow
  set of unannotated literals, arithmetic, `if`, blocks, `new Foo(...)`, typed
  lambdas, same-typed `match` arms, tuple literals, and known
  `List`/`Vector`/`Set`/`Seq`/`Array`/`Map`/`Some`/`Option`/`Left`/`Right`
  constructor calls.
- The predeclaration pass preserves known annotations for forward references
  and records later class / enum constructors with real signatures instead of
  seeding every top-level name as `Any`.
- Tuple, typed, `Some`, and local case-class extractor patterns now bind
  pattern variables with available expected types, so destructuring summaries
  and simple match bodies no longer collapse to `Any`.
- `.scim` interface parsing uses `SType.show` / `parseSType`, but malformed or
  unsupported shapes collapse to `SType.Any`.
- `apiClients:` and `remoteHandlers:` carry `requestType` / `responseType` as
  strings, with many fallback values still set to `Any`.
- `RouteDeriver` can extract the request type from some typed `mount()` handler
  lambdas, but it usually cannot extract the response type.
- OpenAPI `SchemaNode` can derive simple schemas from type-name strings and now
  has a structured component model.
- `backend/typed-data` provides `JsonCodec`, `RowCodec`, `ObjectCodec`, graph
  codecs, `DatasetCodec`, `DatasetWire`, and `SparkSchemaCodec`.
- GraphQL has dynamic resolver maps and a typed resolver direction, but many
  examples and APIs still use `Map[String, Any] => Any`.
- `SourceLanguage.SymbolExport.signature` is a string, so non-ScalaScript block
  plugins can publish signatures but not structured type evidence.

The next pass should connect these pieces rather than invent a separate schema
system for each feature.

## 4. Architecture

### 4.1 Canonical Type Evidence

`SType` should remain the canonical compiler-level type model. A new evidence
wrapper can describe where a type came from and how trustworthy it is:

```scala
enum TypeEvidenceKind:
  case Declared       // source annotation or manifest type
  case Inferred       // inferred from a supported local expression
  case Derived        // case-class/enum/codec/schema derivation
  case Imported       // .scim/.ssclib/interface/imported contract
  case PluginProvided // SourceLanguage/plugin/intrinsic metadata
  case Dynamic        // explicit dynamic boundary
  case Unknown        // missing or unsupported evidence

case class TypeEvidence(
  tpe: SType,
  kind: TypeEvidenceKind,
  source: Option[SourceSpan],
  reason: Option[String]
)
```

Rules:

- `TypeEvidence(SType.Any, Dynamic, ...)` means the source intentionally uses a
  dynamic boundary.
- `TypeEvidence(SType.Any, Unknown, ...)` means the compiler could not prove the
  shape. Strict modes may reject this at exported/contract boundaries.
- Declared evidence wins over inference.
- Inferred evidence should be narrow and deterministic; when unsure, produce
  `Unknown` rather than a guessed concrete type.

### 4.2 Type Shapes For Schemas

`SType` is a compiler type. Schema generators also need structural detail such
as object fields, enum cases, defaults, external field names, nullability, and
constraints. That should be represented as a derived shape, not by parsing JSON
schema or GraphQL SDL back into compiler types.

```scala
enum TypeShape:
  case Primitive(name: String)
  case Product(name: String, fields: List[FieldShape])
  case Sum(name: String, variants: List[VariantShape])
  case Enum(name: String, values: List[String])
  case Collection(kind: String, element: TypeShape)
  case MapShape(key: TypeShape, value: TypeShape)
  case OptionShape(inner: TypeShape)
  case Ref(name: String)
  case FunctionShape(args: List[TypeShape], result: TypeShape)
  case Unknown(reason: String)
```

`TypeShape` should be derived from `SType` plus declaration metadata and typed
data mapping annotations. It feeds:

- OpenAPI `SchemaNode`;
- GraphQL typed resolver and operation validation;
- `JsonCodec` / `RowCodec` / `ObjectCodec` / graph codec derivation metadata;
- Dataset and MapReduce wire payload checks;
- Spark schema generation;
- contract validation diagnostics.

### 4.3 Exported Symbols And Interfaces

`DefSummary` and interface artifacts should carry structured evidence beside
the existing rendered type string:

```scala
case class DefSummary(
  name: String,
  kind: SymbolKind,
  tpe: SType,
  annotations: List[AnnotationSummary],
  evidence: Option[TypeEvidence] = None,
  shape: Option[TypeShape] = None
)
```

The exact API can differ, but the migration rule is important: existing readers
that know only `tpe.show` keep working, while new readers can use structured
fields.

The interface extractor should distinguish three cases that are currently easy
to blur:

- no annotation and unsupported body: `Any` with `Unknown` evidence;
- explicit `: Any`: `Any` with `Declared` evidence;
- intentional dynamic API marker: `Any` with `Dynamic` evidence.

### 4.4 Local Inference Expansion

Inference should expand only where the result is stable and cheap:

- literal collections with homogeneous elements (`List[Int]`, `Map[String, A]`);
- constructor and companion `apply` calls when the class/object is in scope;
- simple method calls when the receiver type and member signature are known;
- enum values and parameterized enum cases;
- case-class `.copy` and field selects;
- typed `Some`, `None`, `Right`, `Left`, `Option`, `Either`, `List`, `Map`, and
  `Set` constructors;
- `match` with all arms converging to the same type or a declared union;
- for-comprehension desugaring only after collection/monad signatures are
  available.

Unsupported cases remain `Unknown` / `Any`. The typer should not chase arbitrary
runtime plugin calls unless the plugin publishes a typed signature.

### 4.5 Routes, Remote Handlers, And Clients

The route/client metadata layer should keep legacy strings but add parsed type
evidence:

```scala
case class ApiEndpointTypeInfo(
  request: TypeEvidence,
  response: TypeEvidence,
  errors: List[TypeEvidence],
  streamElement: Option[TypeEvidence]
)
```

Targets:

- `routes:` front matter can parse `request` / `response` into `SType`.
- Inline `route(...)` can inspect typed handler parameter and return evidence
  when the handler is a local function literal or named function with a known
  signature.
- `mount(...)` can read the mounted handler interface and capture both request
  and response evidence.
- `remoteHandlers:` and `@remote` source sugar should carry request, response,
  async/error, and wire-codec evidence.
- Generated JS/JVM/Swing clients should consume the structured evidence first
  and fall back to legacy strings only when structured data is absent.

### 4.6 OpenAPI And GraphQL

OpenAPI and GraphQL should not each rediscover type shapes independently.

OpenAPI:

- use `TypeShape -> SchemaNode` for components;
- use request/response/error evidence for operation contracts;
- preserve unknown dynamic shapes as generic schemas with diagnostics;
- let `@openapi` presentation metadata override docs/tags/security, not the
  source-owned type shape unless override mode is explicit.

GraphQL:

- validate resolver coordinates against SDL and `TypeEvidence`;
- map `Option[A]` and non-null SDL fields deliberately;
- map products, enums, sealed ADTs/interfaces/unions, custom scalars, and lists
  from shared shapes where possible;
- keep dynamic resolver maps, but classify them as `Dynamic` or `Unknown` for
  contract validation.

### 4.7 Dataset, MapReduce, Spark, And Typed Data

Typed data mapping already owns much of the structural metadata. The real-types
pass should make it the consumer of compiler evidence, not a parallel source of
truth.

Rules:

- `JsonCodec[A]` and `DatasetCodec[A]` derive from the same product/sum field
  metadata used by route clients and contracts.
- `RowCodec[A]` and `SparkSchemaCodec[A]` share tabular field metadata, aliases,
  defaults, key fields, and nullability.
- Dataset/MapReduce wire boundaries should carry element `TypeEvidence` so
  worker payloads can be checked before serialization.
- Spark readers should prefer `SparkSchemaCodec[A]` shapes when present and
  report a typed diagnostic when an external column cannot map back to a Scala
  field.

### 4.8 Plugin And Source-Language Metadata

Plugins should be able to publish type evidence without importing interpreter
runtime classes.

Planned direction:

```scala
case class TypedSymbolExport(
  name: QualifiedName,
  kind: SymbolKind,
  signature: String,
  tpe: Option[SType],
  shape: Option[TypeShape]
)
```

`SourceLanguage.signatures` can keep returning legacy `SymbolExport` first;
a new optional method or adapter can expose typed signatures later.
Intrinsic/plugin manifests should publish argument/result/effect signatures for
externs that are otherwise currently predeclared as `Any`.

## 5. Migration

This roadmap is additive and diagnostics-first.

1. Keep all existing public strings (`requestType`, `responseType`,
   `SymbolExport.signature`, `.scim` rendered types) readable and writable.
2. Add structured fields beside strings in new artifact versions and bridge
   code paths.
3. Prefer structured type evidence in generators/validators when present;
   fall back to strings and then to `Unknown`.
4. Add warnings/counters for exported `Unknown` evidence, but do not fail normal
   builds by default.
5. Let `--strict-types` or feature-specific strict modes reject unknown exported
   contracts after examples and docs have been updated.
6. Only after a compatibility window, consider making selected strict checks
   defaults for published packages or contract-validation commands.

## 6. Phases

### Phase 0 - Spec

Land this roadmap and link it from README, docs index, architecture, typed data,
typed route clients, OpenAPI, GraphQL, and contract validation docs.

### Phase 1 - Any Inventory And Evidence Model

Add a small inventory tool/test helper that counts exported `Any` by reason:
declared, dynamic, unknown, prelude, imported, plugin-provided. Add the
`TypeEvidence` model and focused unit tests, without changing codegen output.
The first implementation slice is specified in
[`docs/type-evidence-inventory.md`](type-evidence-inventory.md).

### Phase 2 - Interface And Artifact Evidence

Extend `DefSummary`, interface extraction, `.scim` / `.sscc` metadata, and
`parseSType` round-trips so structured evidence survives separate compilation.
Keep legacy strings intact.

### Phase 3 - Local Inference Expansion

Broaden `Typer.inferType` for homogeneous collections, constructor/apply calls,
field selects, enum values, case-class copy, simple known member calls, and
convergent matches. Add diagnostics for unsupported exported bodies in strict
mode.

### Phase 4 - Route And Remote Type Evidence

Attach structured request/response/error/stream evidence to `ApiEndpointDecl`,
`RemoteHandlerDecl`, `RouteDeriver`, `RemoteClientDeriver`, and generated typed
clients. Preserve current string fields and warnings.

### Phase 5 - Schema Consumers

Route OpenAPI `SchemaNode`, GraphQL resolver/operation checks, and contract
validation through `TypeShape`. Keep existing manual schema registration as an
override path.

### Phase 6 - Typed Data, Dataset, And Spark Convergence

Make `JsonCodec`, `RowCodec`, `ObjectCodec`, graph/RDF codecs,
`DatasetCodec`, and `SparkSchemaCodec` consume the same field/shape metadata
where possible. Add cross-store golden examples for one shared domain model.

### Phase 7 - Plugin Metadata

Add typed signature publication for SourceLanguage plugins, intrinsic manifests,
and stable plugin API metadata. Migrate representative plugins before requiring
the new method.

### Phase 8 - Strict Modes And Contract Integration

Add `--strict-types` or feature-specific strict options, expose diagnostics to
contract validation, and document the remaining intentional dynamic boundaries.

## 7. Testing Strategy

| Area | Tests |
|---|---|
| Evidence model | declared vs inferred vs dynamic vs unknown classification |
| `SType` round-trip | `show` / `parseSType`, generics, effects, unions, intersections, opaque types |
| Typer inference | literals, collections, constructors, enums, selects, copy, matches, known calls |
| Interfaces | `.scim` / `.sscc` structured evidence, legacy compatibility, malformed fallback |
| Routes/remotes | inline route, mounted handler, front matter, remote handlers, client derivation |
| Schemas | `TypeShape -> OpenAPI SchemaNode`, GraphQL resolver compatibility, unknown diagnostics |
| Typed data | shared field metadata across JSON, row, object, graph, Dataset, Spark codecs |
| Plugins | typed SourceLanguage exports, intrinsic signature metadata, legacy fallback |
| CLI/strict | warning counts, `--strict-types` failures, no behavior change in default mode |

Docs-only spec work uses `git diff --check`. Implementation phases should add
focused tests in the touched modules first, then cross-backend conformance only
when generated artifacts or runtime behavior changes.

## 8. Open Questions

1. Should `TypeShape` live in `lang/core`, `backend/typed-data`, or a small
   shared module below both? Keeping schema consumers independent from codegen
   argues for a lightweight shared module.
2. Should explicit `: Any` be considered safe declared evidence, or should
   exported APIs need an additional `@dynamic` marker to avoid accidental
   contract holes?
3. How much type evidence should `.ssclib` compatibility checks compare before
   v1.0: exact equality, source compatibility, or best-effort warnings?
4. Should GraphQL nullable values map to `Option[A]` always, or should a
   convenience mode allow nullable-but-unwrapped dynamic values?
5. How should effect rows appear in OpenAPI/GraphQL/Dataset metadata: ignored,
   surfaced as async/error information, or kept only for compiler diagnostics?
6. Which plugin metadata format is stable enough for third-party plugins before
   the plugin API reaches a stable version?

## 9. Critical Files

Likely implementation touch points:

| File | Role |
|---|---|
| `lang/core/src/main/scala/scalascript/typer/Types.scala` | `SType` model, compatibility, rendering |
| `lang/core/src/main/scala/scalascript/typer/Typer.scala` | local inference, summaries, diagnostics |
| `lang/core/src/main/scala/scalascript/artifact/InterfaceScope.scala` | `parseSType`, imported interface type recovery |
| `lang/core/src/main/scala/scalascript/artifact/InterfaceExtractor.scala` | `.scim` / summary extraction fallback points |
| `lang/core/src/main/scala/scalascript/ast/AST.scala` | `ApiEndpointDecl` / `RemoteHandlerDecl` metadata |
| `lang/core/src/main/scala/scalascript/transform/RouteDeriver.scala` | route and mount handler type extraction |
| `lang/core/src/main/scala/scalascript/transform/RemoteClientDeriver.scala` | remote handler -> typed client metadata |
| `runtime/backend/spi/src/main/scala/scalascript/backend/spi/OpenApiGenerator.scala` | schema consumer of type shapes |
| `runtime/backend/typed-data/` | shared codec and schema metadata consumers |
| `runtime/std/graphql-plugin/` | GraphQL resolver/client type checks |
| `runtime/scalascript-plugin-api/` | stable plugin-facing signature metadata |
