# Type Evidence Inventory - spec

**Status:** P1-P4 (a/b/c) landed 2026-06-04; P4d α landed 2026-06-04; β+γ in progress.
**Queue items:** `type-evidence-inventory-p1`, `type-evidence-interface-p2`,
`type-evidence-routes-p3`, `type-evidence-schema-p4a`, `type-evidence-openapi-p4b`,
`type-evidence-check-cmd-p4c`, `type-evidence-graphql-p4d-alpha`,
`type-evidence-graphql-p4d-beta`, `type-evidence-graphql-p4d-gamma`.
**Parent roadmap:** [`docs/typer-real-types-roadmap.md`](typer-real-types-roadmap.md).

This is the first implementation slice of the real-types roadmap. The goal is
not to make every exported symbol precise immediately. The goal is to stop
treating every `Any` as the same fact.

`Any` is still valid in ScalaScript, but exported `Any` should carry a reason:

- declared by the source author;
- intentionally dynamic at a boundary;
- imported from an interface;
- provided by a plugin/intrinsic;
- inferred by the compiler;
- unknown because the compiler has no supported evidence yet.

That classification gives later phases a stable baseline for interface
evidence, strict modes, route/client metadata, OpenAPI, GraphQL, and contract
validation.

## Goals

- Add a shared `TypeEvidenceKind` / `TypeEvidence` model around existing `SType`
  values.
- Provide small constructors/helpers for common evidence cases so callers do
  not open-code classification strings.
- Add an inventory helper that counts `Any` evidence by reason on exported
  symbol summaries.
- Keep all existing public artifact formats and generated code behavior
  unchanged in this slice.
- Add focused tests for evidence classification and inventory counting.
- Record enough baseline data that later phases can reduce unknown exported
  `Any` without re-discovering where it comes from.

## Non-goals

- No `.scim` / `.sscc` artifact format change in this slice.
- No route/client/OpenAPI/GraphQL migration yet.
- No broad inference expansion beyond what already landed in `Typer.scala`.
- No strict-type build failures.
- No attempt to remove dynamic `Any` at JSON, GraphQL, plugin, or foreign-code
  boundaries.

## Architecture

`SType` remains the compiler type model. Evidence is metadata about how the
compiler obtained that type.

```scala
enum TypeEvidenceKind:
  case Declared
  case Inferred
  case Derived
  case Imported
  case PluginProvided
  case Dynamic
  case Unknown

case class TypeEvidence(
  tpe: SType,
  kind: TypeEvidenceKind,
  source: Option[SourceSpan] = None,
  reason: Option[String] = None
)
```

The exact file layout can change during implementation, but the preferred home
is `lang/core/src/main/scala/scalascript/typer/Types.scala` or a nearby
`TypeEvidence.scala` in the same package. Keeping it in `lang/core` lets typer,
interface extraction, route derivation, and schema consumers depend on the same
model without creating backend coupling.

Rules:

- Explicit source annotations produce `Declared` evidence, including explicit
  `: Any`.
- Supported local inference produces `Inferred` evidence.
- Case-class, enum, codec, or schema derivation can later produce `Derived`
  evidence.
- Recovered interface evidence will later use `Imported`.
- Plugin and source-language metadata will later use `PluginProvided`.
- Intentional runtime/foreign boundaries use `Dynamic`.
- Unsupported inference paths use `Unknown`; when the type is also `Any`, this
  is the debt later strict modes should highlight.

## Inventory Model

The inventory helper should be deliberately small and deterministic:

```scala
case class AnyEvidenceCounts(
  declared: Int = 0,
  inferred: Int = 0,
  derived: Int = 0,
  imported: Int = 0,
  pluginProvided: Int = 0,
  dynamic: Int = 0,
  unknown: Int = 0
)
```

It should count only exported summaries whose evidence type contains `SType.Any`
directly or structurally, such as `Any`, `List[Any]`, or `() => Any`. This
avoids confusing legitimate non-`Any` inferred types with the main baseline this
slice is meant to expose while still catching function signatures that hide an
unknown return or parameter under an otherwise useful shape.

The first implementation can operate directly on `List[DefSummary]`. Later
phases can extend it to `.scim` interfaces, route metadata, remote handlers,
and plugin manifests.

## Migration

1. Add the evidence model in `lang/core`.
2. Add optional `evidence: Option[TypeEvidence]` to `DefSummary`, defaulting to
   `None` for binary/source compatibility at call sites.
3. Populate evidence in the local typer summary path where the source of the
   type is already obvious:
   - annotations -> `Declared`;
   - existing supported inference -> `Inferred`;
   - current fallback `Any` -> `Unknown`.
4. Add the inventory helper and tests.
5. Do not change interface serialization, code generation, runtime behavior, or
   CLI output in this slice.

## Phases

### P1 - Evidence Model And Summary Inventory

Queue slug: `type-evidence-inventory-p1`.

Landed 2026-06-04 in commit `600d3523`.

Add `TypeEvidenceKind`, `TypeEvidence`, optional summary evidence, and
`AnyEvidenceInventory` for exported `DefSummary` values. Populate only the
typer-created summaries where evidence can be classified without changing
artifact formats.

Verification:

- `core / Test / testOnly scalascript.typer.TypeEvidenceTest`
- `core / Test / testOnly scalascript.typer.TyperRealTypesTest`
- `core / Test / compile`

### P2 - Interface Evidence Serialization

Queue slug: `type-evidence-interface-p2`.

Landed 2026-06-04 in commit `24c0803d`.

Add structured evidence fields beside legacy rendered type strings in interface
artifacts. Keep old readers/writers working and keep `InterfaceScope` resolving
from the legacy `tpe` string for this slice.

Target additive wire shape:

```scala
case class TypeEvidenceWire(
  tpe: String,
  kind: String,
  reason: Option[String] = None
)

case class ExportedSymbol(
  ...,
  evidence: Option[TypeEvidenceWire] = None
)
```

Rules:

- `InterfaceExtractor` writes `DefSummary.evidence` to `ExportedSymbol.evidence`
  when present.
- Legacy synthesized package-inner exports with no evidence may omit the field
  or mark it as `Unknown`, whichever is less invasive for current tests.
- `ArtifactIO.readInterface` must continue reading old `.scim` JSON/MessagePack
  because the new field has a default.
- `InterfaceScope` continues to parse `sym.tpe`; consuming structured evidence
  belongs to a later strict/types phase.

Verification:

- interface extraction test proving declared/inferred/unknown evidence survives
  `ArtifactIO.writeInterface` / `readInterface`;
- legacy JSON fixture without evidence still reads;
- `core / Test / compile`.

### P3 - Route And Remote Evidence

Queue slug: `type-evidence-routes-p3`.

Landed 2026-06-04 in commit `347fe6f3`.

Attach structured request/response evidence to normalized route/client metadata
while preserving the legacy `requestType` / `responseType` strings.

Target additive shape in `lang/ir`:

```scala
case class ApiEndpointTypeEvidenceWire(
  request: Option[TypeEvidenceWire] = None,
  response: Option[TypeEvidenceWire] = None,
  errors: List[TypeEvidenceWire] = Nil,
  streamElement: Option[TypeEvidenceWire] = None
)

case class ApiEndpointDecl(
  ...,
  typeEvidence: Option[ApiEndpointTypeEvidenceWire] = None
)

case class RemoteHandlerDecl(
  ...,
  typeEvidence: Option[ApiEndpointTypeEvidenceWire] = None
)
```

Scope for this slice:

- store the new field only on `scalascript.ir.ApiEndpointDecl` and
  `scalascript.ir.RemoteHandlerDecl`; the AST/parser/SsccFormat surface stays
  unchanged for this slice;
- derive evidence during `Normalize` from the legacy strings and let
  `Denormalize` drop it when rebuilding the AST compatibility view;
- parse existing `requestType` / `responseType` strings into `SType` where the
  shape is supported;
- reuse the interface type-string parser (`InterfaceScope.parseSType`) instead
  of duplicating the grammar;
- classify parsed concrete strings as `Declared` when they came from
  manifest/frontmatter or existing typed route metadata;
- classify parsed shapes containing `Any` as `Unknown`, because they are the
  remaining route/client type debt this roadmap needs to expose;
- classify missing/unsupported request/response evidence as `Unknown`;
- keep all existing string fields intact and keep generators reading those
  strings for now;
- add round-trip tests proving the optional evidence survives artifact I/O and
  old metadata without the field still reads.

Out of scope:

- no OpenAPI/GraphQL schema generation migration yet;
- no strict failures for unknown route evidence;
- no behavioral change in JS/JVM/client generation.

Verification:

- `core / Test / testOnly scalascript.transform.RouteTypeEvidenceTest`
  (4 tests);
- `core / Test / testOnly scalascript.artifact.ArtifactIOTest` (17 tests);
- `core / Test / testOnly scalascript.parser.ApiClientsFrontmatterTest`
  (8 tests);
- `core / Test / testOnly scalascript.parser.ClusterFrontmatterTest`
  (9 tests);
- `core / Test / compile`.

### P4 - Schema Consumers

P4 has three independent sub-slices (a, b, c) that landed 2026-06-04, plus P4d
(GraphQL evidence) which was initially deferred and is now in progress (α landed
2026-06-04).

#### P4a — Route Evidence Inventory Helper

Queue slug: `type-evidence-schema-p4a`.

Add a `RouteEvidenceCounts` + `RouteEvidenceInventory` helper in `lang/core` that
counts declared/unknown evidence across route and handler metadata in a compiled
`ir.Manifest`. This is the route analogue to the `AnyEvidenceInventory` added in P1
for exported symbols.

Target shape in `lang/core/src/main/scala/scalascript/typer/TypeEvidence.scala`
(or a new `RouteEvidence.scala` nearby):

```scala
case class RouteEvidenceCounts(
  endpointsDeclared: Int = 0,
  endpointsUnknown:  Int = 0,
  handlersDeclared:  Int = 0,
  handlersUnknown:   Int = 0
):
  def allDeclared: Boolean =
    endpointsUnknown == 0 && handlersUnknown == 0

object RouteEvidenceInventory:
  def count(manifest: ir.Manifest): RouteEvidenceCounts
```

Rules:
- Count each `ApiEndpointDecl` as declared if **both** `request.kind` and
  `response.kind` are `"Declared"` in `typeEvidence`; otherwise unknown.
- Count each `RemoteHandlerDecl` the same way.
- Missing `typeEvidence` → unknown (backward-compatible with legacy artifacts).

Verification:
- `core / Test / testOnly scalascript.typer.RouteEvidenceInventoryTest`
- `core / Test / compile`

#### P4b — OpenAPI Evidence Diagnostics

Queue slug: `type-evidence-openapi-p4b`.

Add evidence-aware warnings and a `--require-declared` gate to `ssc emit-openapi`.

Changes:
- `EmitCommands.openApiEvidenceDiagnostics(module)` — returns a `List[String]`
  of human-readable warnings for each endpoint whose request or response evidence
  is `Unknown`. Uses `ApiEndpointTypeEvidenceWire` from `ApiEndpointDecl.typeEvidence`.
- `ssc emit-openapi --require-declared` flag: after generating the spec, check
  evidence diagnostics; if any, print them to stderr and exit 1.
- Without `--require-declared`, warnings are suppressed (backward-compatible).
- Diagnostics read `typeEvidence` only; the OpenAPI document itself continues to
  use the existing `responseType` string path — no schema generation change.

Verification:
- `cli / Test / testOnly scalascript.cli.EmitOpenapiCliTest` (add 2 cases: one
  that passes --require-declared with Declared types and one that fails with Unknown)
- `cli / Test / compile`

#### P4c — `ssc check-types` CLI Command

Queue slug: `type-evidence-check-cmd-p4c`.

New CLI command `ssc check-types <file.ssc>` that compiles a module (or reads a
cached `.scir`) and prints a two-section evidence inventory table:

```
Route evidence:
  api endpoints:    3 declared, 0 unknown
  remote handlers:  1 declared, 1 unknown

Symbol evidence (Any-typed exports):
  declared:  0
  inferred:  2
  unknown:   5
```

Exit code: `0` when `allDeclared` for routes, `1` otherwise
(allows `ssc check-types` to gate CI on route type coverage).

The command should:
- Parse and normalize the `.ssc` file using the standard compiler pipeline
- Read `ir.Manifest.apiClients` + `remoteHandlers` for route evidence via `RouteEvidenceInventory`
- Read `ir.NormalizedModule` exported symbols via the existing `AnyEvidenceInventory`
  (requires an `InterfaceExtractor` pass — can use the existing `extractInterface` path)
- Print the table to stdout
- Print "All routes have declared types." / "N routes have unknown types." as a summary line

Verification:
- `cli / Test / testOnly scalascript.cli.CheckTypesCliTest`
- `cli / Test / compile`

#### P4d — GraphQL SDL Evidence

P4d was initially deferred ("GraphQL uses SDL blocks with its own type model").
Reconsidered: the SDL parser already runs at compile time — keeping the
`TypeDefinitionRegistry` instead of discarding it gives a free typed surface to
classify. Implemented as three independent sub-slices (α/β/γ).

##### P4d-α — IR wire + plugin populates evidence

Queue slug: `type-evidence-graphql-p4d-alpha`.

Landed 2026-06-04 in commit `1b23a2e9`.

Added to `lang/ir/src/main/scala/scalascript/ir/Ir.scala`:
- `GraphQLFieldEvidenceWire(name, typeName, kind)` — per-field evidence
- `GraphQLTypeEvidenceWire(name, kind, fields)` — per-type (Object/Interface/Input/Union/Enum/Scalar)
- `GraphQLBlockEvidenceWire(types)` — block-level summary

Additive `evidence: Option[GraphQLBlockEvidenceWire] = None` field on
`Content.EmbeddedBlock`. Default `None` is backward-compatible with legacy `.scir`.

`GraphQLSourceLanguage.compileBlock` now retains the parsed `TypeDefinitionRegistry`
and builds `GraphQLBlockEvidenceWire`. Classification: field type names that are SDL
built-in scalars (`Int`/`Float`/`String`/`Boolean`/`ID`), defined in the same block,
or resolvable via `ScopeContext.resolve` → `"Declared"`. Everything else → `"Unknown"`.
List/NonNull wrappers unwrap to the base type name. Invalid SDL → `evidence = None`.

4 consumer pattern-matches updated to 4-arg (CapabilityCheck, Denormalize, NodeBackend,
SourceLanguageDispatchTest). 8 new tests in `GraphQLEvidenceTest`.

Verification:
- `graphqlPlugin / Test / testOnly scalascript.compiler.plugin.graphql.GraphQLEvidenceTest`
- `core / Test / testOnly scalascript.artifact.ArtifactIOTest`
- `core / Test / compile`

##### P4d-β — `GraphQLEvidenceInventory` helper

Queue slug: `type-evidence-graphql-p4d-beta`.

Add `GraphQLEvidenceCounts` + `GraphQLEvidenceInventory.count(manifest)` in
`lang/core/src/main/scala/scalascript/typer/TypeEvidence.scala`. Tallies
object/interface/input types and fields by `Declared`/`Unknown` across all
`EmbeddedBlock("graphql", _, _, Some(ev))` blocks in the module sections.
Missing `evidence` → 1 unknown type per block (backward-compat with legacy artifacts).

Verification:
- `core / Test / testOnly scalascript.typer.GraphQLEvidenceInventoryTest`
- `core / Test / compile`

##### P4d-γ — `ssc check-types` third section

Queue slug: `type-evidence-graphql-p4d-gamma`.

Extend `CheckTypesCmd` to add a third table section after routes + symbols:
```
GraphQL evidence:
  object/interface/input types:  N declared, M unknown
  fields:                        N declared, M unknown
```
Exit code gates on `routeCounts.allDeclared && graphqlCounts.allDeclared`.
Update `CheckTypesCliTest` with 2 new test cases.

Deferred: `ssc emit-graphql --require-declared` — no `ssc emit-graphql` command
exists yet; record as BACKLOG item.

Verification:
- `cli / Test / testOnly scalascript.cli.CheckTypesCliTest`
- `cli / Test / compile`

## Testing Strategy

- Unit-test `TypeEvidence` constructors and `isAny` classification.
- Unit-test inventory counts for each evidence kind.
- Add typer tests showing declared `: Any`, inferred non-`Any`, and unknown
  fallback are distinguishable in `DefSummary.evidence`.
- Keep existing real-type inference tests green to prove the new metadata does
  not regress `SType` output.

## Open Questions

1. Should exported explicit `: Any` be acceptable in future strict modes, or
   should strict API boundaries require a separate `@dynamic` marker?
2. Should inventory count `SType.Dynamic` separately if that type is added later,
   or should dynamic remain evidence over `SType.Any`?
3. Should `TypeEvidence.reason` use free-form strings for now or a small ADT of
   stable reason codes before CLI diagnostics depend on it?

## Critical Files

| File | Role |
|---|---|
| `lang/core/src/main/scala/scalascript/typer/TypeEvidence.scala` | Evidence model, `AnyEvidenceInventory`, `RouteEvidenceInventory` (P4a), `GraphQLEvidenceInventory` (P4d-β) |
| `lang/core/src/main/scala/scalascript/typer/Types.scala` | `SType` |
| `lang/core/src/main/scala/scalascript/typer/Typer.scala` | `DefSummary` creation |
| `lang/ir/src/main/scala/scalascript/ir/Ir.scala` | `ApiEndpointTypeEvidenceWire`, `ApiEndpointDecl`, `RemoteHandlerDecl`, `GraphQL{Field,Type,Block}EvidenceWire`, `EmbeddedBlock.evidence` (P4d) |
| `lang/core/src/main/scala/scalascript/transform/Normalize.scala` | Evidence population from AST |
| `tools/cli/src/main/scala/scalascript/cli/EmitCommands.scala` | OpenAPI emission (P4b) |
| `tools/cli/src/main/scala/scalascript/cli/CheckTypesCmd.scala` | New check-types command (P4c) |
| `lang/core/src/test/scala/scalascript/typer/` | Evidence tests |
| `docs/typer-real-types-roadmap.md` | Parent roadmap |
| `WORK_QUEUE.md` | Implementation queue |
| `BACKLOG.md` | Durable milestone plan |
