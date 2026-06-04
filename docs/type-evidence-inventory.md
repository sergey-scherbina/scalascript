# Type Evidence Inventory - spec

**Status:** P1-P2 landed 2026-06-04; P3+ planned.
**Queue item:** `type-evidence-inventory-p1`.
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

### P4 - Schema Consumers

Feed OpenAPI, GraphQL, and contract validation from the shared evidence and
shape model.

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
| `lang/core/src/main/scala/scalascript/typer/Types.scala` | `SType`, likely evidence model home |
| `lang/core/src/main/scala/scalascript/typer/Typer.scala` | `DefSummary` creation and fallback classification |
| `lang/core/src/test/scala/scalascript/typer/` | Evidence and typer regression tests |
| `docs/typer-real-types-roadmap.md` | Parent roadmap |
| `WORK_QUEUE.md` | Implementation queue |
| `BACKLOG.md` | Durable milestone plan |
