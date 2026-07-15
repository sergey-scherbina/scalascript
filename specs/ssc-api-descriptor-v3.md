# SSC API descriptor v3

Status: **slice A implemented and verified; slice B compatibility-producer fourth
pre-integration review rejected with three P1 corrections specified but not yet
implemented; self-hosted and consumer slices queued** (2026-07-15).

This specification refines the structured-descriptor contract in
[`control-interoperability.md`](control-interoperability.md) without changing its
control semantics. It defines the target-neutral records exchanged by interface
extractors, body compilers, linkers, generated host facades, and admission code.
The first independently shippable slice freezes the data model, canonical form,
identities, validation, and an additive `.scim` carrier. Compiler extraction and
backend population are later slices and remain visibly queued in `SPRINT.md`.

## Overview

Managed interoperability has three phase-specific records:

1. `ApiDescriptor` is produced from declared public interfaces before bodies
   compile. It owns canonical public types, generic binders, effect rows, callback
   policy, prompt/control metadata, and stable symbol/overload identity.
2. `ControlSummary` is produced after bodies compile. It owns managed and foreign
   call edges, proper-tail edges, save sites, frame schemas, and capture barriers.
3. `ArtifactManifest` is produced after linking. It binds an API and one or more
   control summaries to concrete target entrypoints, program/artifact bytes,
   runtime identity, and an exact dependency profile.

The records describe semantics; they do not implement effects, `shift`/`reset`,
save/run, type inference, or target execution. No descriptor type is a new CoreIR
node or a second typechecker.

## Architecture and module boundary

The canonical Scala implementation lives in the new target-neutral leaf
`v2/interop/descriptor`, in package `scalascript.interop.descriptor`. The sbt
project id is `v2InteropDescriptor`; its artifact name is
`scalascript-interop-descriptor_3` under the repository's existing
`io.scalascript` organization.

The leaf may depend on a deterministic JSON library and the Scala/JVM standard
cryptographic implementation, but its **public model contains no** `SType`, AST,
CoreIR term/value, interpreter value, VM frame, plugin runtime value, Scala control
runtime value, reflection handle, classloader, or platform object. Its canonical
JSON and SHA-256 formulas are language-neutral and reproducible by JavaScript,
Rust, Swift, or WASM tooling. It is aggregated by the repository root but is not a
dependency of `v2Core`, UniML, the seed, the self-hosted compiler, or any backend.

The legacy `v1/lang/ir` `.scim` case class remains the compatibility integration
point only. Slice A adds one defaulted field:

```scala
apiDescriptorV3: Option[String] = None
```

The string is the canonical UTF-8 JSON text defined here. The v1 module does not
own, duplicate, or interpret descriptor semantics. Existing `.scim` fields keep
their exact meanings:

- `tpe` remains the legacy best-effort rendered type;
- `evidence` remains the existing optional `TypeEvidenceWire` explanation;
- `sourceHash` remains the hash of source bytes;
- `exports`, `externDefs`, facade names, spans, section hashes, capabilities, and
  dependency aliases are unchanged;
- `ArtifactVersion.current` remains `2.0`.

An old artifact decodes with `apiDescriptorV3 = None`. A new artifact round-trips
the payload, while a reader that ignores unknown additive fields retains its old
behavior. A producer must not fabricate descriptor v3 by parsing legacy `tpe`
strings: only a structured pre-body producer may populate it.

### Decisions

- **Use `v2/interop/descriptor`.** This makes common ABI ownership explicit and
  keeps the feature out of the rejected legacy `v1/lang` control/API home.
  Rejected: `v1/lang/ir` as semantic owner (wrong generation and bootstrap
  boundary); `v2/host/scala/control` (Scala-host-specific); vague `v2/common`
  (does not communicate interop ownership).
- **Keep the `.scim` bridge as canonical JSON text.** It avoids making the legacy
  IR artifact model depend on the new public descriptor library and gives other
  languages one inspectable payload. Rejected: duplicating every descriptor ADT
  as v1 wire classes (two schemas would drift), or reinterpreting existing `tpe`
  and `evidence` fields (changes old meanings).
- **Keep compiler `SType` authoritative for checking.** `AbiType` is a normalized
  public-ABI projection with no unification or assignability operations. Rejected:
  sharing `SType` directly (compiler implementation details and best-effort
  fallbacks would leak into the host ABI).

## Slice B pre-body producer contract

Slice B adds the compatibility producer in `v1/lang/core`, which depends on the
canonical `v2InteropDescriptor` leaf. The dependency direction is deliberately
one-way: the producer may know the parsed ScalaScript declaration AST, while the
descriptor leaf remains unaware of AST, `SType`, compiler phases, source files,
or legacy artifacts.

The public compatibility entrypoints are:

```scala
object PreBodyApiDescriptorProducer:
  val ControlAbiVersion: String = "ssc-control-v1"
  def descriptor(module: scalascript.ast.Module):
    Either[DescriptorError, ApiDescriptor]
  def canonicalJson(module: scalascript.ast.Module):
    Either[DescriptorError, String]

object InterfaceExtractor:
  def extract(module: ast.Module, sourceBytes: Array[Byte]): ModuleInterface
  def extractManaged(module: ast.Module, sourceBytes: Array[Byte]):
    Either[DescriptorError, ModuleInterface]
```

`extractManaged` invokes the strict pre-body producer first and invokes the
legacy body-aware extractor only after projection succeeds. It stores exactly
the bytes returned by `DescriptorCodec.encodeApi`, decoded as UTF-8, in
`apiDescriptorV3`. The ordinary `extract` method retains its old behavior and
leaves `apiDescriptorV3 = None`; it neither fails nor fabricates a descriptor
when a legacy module relies on inference or dynamic `Any`. Slice D will choose
the strict managed path at the actual managed build/admission boundary.

The source of truth is the parsed declaration header:

- `def` parameter clauses, type-parameter clauses, declared result/effect row,
  modes, and default presence are read structurally; the body is ignored;
- explicitly typed immutable `val`, constructors, nominal types, aliases, objects,
  enums, effects, and effect operations project from their declaration nodes.
  A selected public/exported `var` rejects with
  `UNSUPPORTED_PUBLIC_DECLARATION`: schema `3.0` has no mutability field, so
  projecting it as `ApiSymbolKind.Value` would make a mutable and immutable API
  descriptor-identical. Adding mutability is a future additive schema decision,
  not a producer-local reinterpretation of the frozen Slice A model;
- manifest `exports:` and `@internal`/private visibility select the public set;
- lexical declaration environments are collected before the manifest export
  filter. Every local type/effect/transparent-alias identity is indexed together
  with effective visibility inherited through all enclosing owners. A non-exported
  **public** local declaration may therefore still supply the stable identity or
  callback shape used by an exported signature, without itself becoming an
  exported symbol. A reference that resolves to a known private, protected, or
  `@internal` local declaration/owner rejects before qualified-external fallback;
  it must never become an external `AbiType.Named` or hide a function-shaped alias
  from callback policy. Only a qualified non-platform name that is not any known
  local identity may remain an external stable id. Lexical local type declarations
  shadow the frozen standard constructors; relative selections such as
  `Domain.Value` normalize to their fully qualified local id;
- explicit imports participate in the lexical declaration environment in source
  order. A frozen bare spelling is eligible for builtin projection only when the
  active import environment proves that no explicit import binds or may bind that
  spelling. A direct import and a rename-to-name carry their exact qualified target:
  the producer resolves that target as a qualified local/external identity, applying
  normal visibility and platform-root checks, instead of selecting a same-spelled
  builtin. A wildcard makes every non-excluded bare name potentially imported and
  therefore rejects with `AMBIGUOUS_NAMED_TYPE`; conflicting exact bindings reject
  the same way. A rename-away or explicit unimport excludes the source name from that
  importer, including its wildcard, so the builtin remains eligible only if no other
  active importer can supply it. Given-only selectors do not bind ordinary type
  names. These rules apply before **every** bare primitive, numeric, `Bytes`, frozen
  collection, and `Array[Byte]` mapping, not only before `Byte`; an exact import from
  `scala`, `java`, or `javax` rejects with `PLATFORM_TYPE_FORBIDDEN` rather than
  laundering a platform type through a builtin spelling;
- `Int` maps directly to `I32`, `Long` directly to `I64`, `Double` to `F64`,
  and no width is inferred from a literal, body result, lowered value, `SType.Any`,
  legacy `ExportedSymbol.tpe`, or `TypeEvidenceWire`;
- generic references use lexical binder coordinates. Parameter-list boundaries,
  parameter names/modes, and default presence remain intact;
- a function-typed parameter without an explicit policy syntax receives the only
  conservative declaration-only policy: `ForeignBarrier`, `Unknown` invocation,
  `MayEscape`, unknown reentrancy/concurrency/cancellation, and `AnyThread`. It is
  never upgraded to `ManagedControl` by inspecting a body. Local transparent type
  aliases are expanded only far enough, with arity and cycle checks, to prevent an
  alias from hiding a function-shaped parameter from this policy;
- plain and `multi effect` operation markers produced by the existing parser map
  to `OneShot` and `Reusable`, respectively. Operation rows name their owning
  stable effect id. Because the compatibility parser erases generic/parent effect
  syntax, original effect headers are read only from their executable code-block
  declaration source. The scan is Scala-lexical for this purpose: line/block
  comments, character literals, ordinary strings, and triple-quoted strings are
  blanked while line boundaries are retained, so text inside them cannot create
  evidence. Each header binds inside the same code block: a preserved source
  position is exact evidence; when deterministic preprocessing has inserted
  lines, a structurally marked effect object is selected by declaration order.
  An empty effect has no operation marker and therefore binds without a preserved
  position only when the remaining same-name candidate is unique. The selected
  object's AST owner supplies the stable lexical owner. A global bare-name queue
  is forbidden: an ordinary unmarked same-name object cannot consume a later
  marked effect's header. Prose and non-Scala fences are never evidence. A missing
  or ambiguous binding, an owner/multiplicity mismatch, or an AST whose package
  wrapper retained only the erased object fails closed instead of guessing.
  Before one retained carrier is chosen as lexical evidence, every carrier also
  receives an ordered, line-offset-independent raw effect-header witness aligned to
  its declaration witness. Each object-shaped candidate records its lexical owner,
  name/order, whether the source declared an ordinary `object` or an `effect`, plain
  versus `multi` multiplicity, and whether generic or parent/header syntax was
  present. Unsupported generic/parent effect shape rejects even when both carriers
  repeat it. CodeBlock and Document witnesses must agree exactly; preprocessing an
  `effect` into an object never erases an effect/object or multiplicity disagreement.
  A documentless CodeBlock still validates its raw evidence against the stored
  marked AST before production;

  Package wrapping currently replaces `CodeBlock.source` with the already
  preprocessed, package-nested Scala source. For a documentless packaged **empty**
  effect, neither that source nor the ordinary ScalaMeta object node can otherwise
  distinguish `effect Empty:` from `object Empty:`. The parser therefore preserves
  exactly this erased origin evidence as reserved private type sentinels inside the
  synthetic object: `private type __effectDecl__ = true` for every effect and
  `private type __effectUnsupportedShape__ = true` when a generic/parent header was
  erased. These are parser-internal compatibility evidence, not ScalaScript source
  declarations, value fields, CoreIR terms, descriptor symbols, or effect/runtime
  operations. The producer recognizes and filters them before nominal/member
  projection; effect analysis and target backends continue to inspect only the
  existing `__effectOp__` and `__multiShot__` contracts. A user declaration that
  collides with either reserved sentinel fails strict managed production instead of
  impersonating an effect: with a raw carrier it fails effect/object correspondence;
  in a packaged documentless block the sentinel alone is never declaration-header
  evidence, so missing raw `effect` evidence rejects at the symbol path. Rejected:
  adding a
  new original-source field to `Content.CodeBlock` and every serialized/consumer
  shape solely for this compatibility producer (a broader carrier/schema change);
  using synthetic `val` sentinels (they would become observable object fields in the
  interpreter);
- nominal projection is deliberately lossless rather than optimistic: a public
  class/trait/enum with explicit parents or a self type, a trait constructor
  clause, a non-empty derives clause or early-initializer clause, a public instance
  member, a template `export`, or a secondary constructor rejects until receiver/
  supertype/member/derivation metadata exists. Class and case-class primary
  parameters carrying `val`/`var` accessor semantics reject for the same reason; a
  plain non-accessor class constructor remains representable. This derives/early
  gate applies to every class, trait, enum, or object form accepted by the parser.
  Objects remain namespace containers whose public nested declarations are
  projected explicitly. Abstract classes and classes with private/protected
  primary constructors emit only their `Type` symbol. Repeated enum cases and the
  difference between `case A` and `case A()` are retained; generic or explicitly
  specialized enum cases reject;
- every parseable executable section code block has two mandatory retained
  components: its `CodeBlock.source` and its stored AST. A `DocumentContent`
  executable source is an optional second source carrier, not a replacement for
  the code-block source. If the document snapshot exists, its executable sources
  must pair one-to-one and in order with the section blocks. **Correspondence is
  semantic, not count-only, and no source carrier silently wins.** The producer
  canonically preprocesses and reparses the code-block source and, when present,
  the document source; both ordered declaration-header witnesses must equal the
  stored AST witness and therefore each other. The check is mandatory even when
  `module.document = None`.

  A packaged stored AST must contain exactly the synthetic manifest namespace
  chain before it is unwrapped. Every wrapper is one plain `Defn.Object` with the
  exact expected name, no modifiers, parents/inits, derives, self type, or any
  other non-body template/header state, and exactly one next wrapper until the
  namespace leaf. A package-wrapped code-block source is reparsed and subjected to
  the same exact-wrapper check before witness comparison; an already-unwrapped
  document source is compared directly. A name-only wrapper match is forbidden.

  A witness retains owner nesting, declaration kind/name, ABI-relevant
  modifiers (including `val` versus `var`, visibility, accessors, and effect
  multiplicity/operation markers), type/constructor/parameter clauses, declared
  types/effect rows, parent/self/derives/early/export/member surface, and default
  presence. It
  deliberately erases method/value bodies, initializer/default expressions, source
  positions, comments, and formatting, so body-only edits remain descriptor/hash
  invariant. A parse failure or witness mismatch, a non-empty block without an
  AST, a retained document block whose section container was lost, a missing or
  non-plain package wrapper, two source carriers with different headers, or a
  manifest export without a local declaration header rejects with
  `UNSUPPORTED_PUBLIC_DECLARATION`. After source witnesses agree, document source
  is preferred for original lexical effect-header evidence; without a document,
  the code-block source is used and safely rejects if package preprocessing erased
  evidence that cannot be reconstructed. Managed production never trusts stale
  declaration AST or turns structural source/AST loss into a valid descriptor;
- module `targets:` may populate `requiredTargets`; capabilities, prompt capture,
  and managed-control claims stay empty/false unless a future declared syntax
  supplies them. Body-derived facts belong only to Slice C.

Bare named types are accepted only when they are lexical type parameters, local
public type declarations, exact explicit-import bindings, or frozen standard
constructors. Qualified non-platform names retain their dotted id. The frozen
standard ids in this producer are
`std.List`, `std.Vector`, `std.Seq`, `std.Set`, `std.Map`, `std.Option`, and
`std.Either`; unshadowed `Array[Byte]` and `Bytes` normalize to primitive `Bytes`.
The `Array[Byte]` shortcut applies only when **both** `Array` and `Byte` are neither
lexical binders nor known local type identities. Binder or public-local shadowing
takes the ordinary type-application path (which may reject if that application has
no closed v3 representation); private/protected/`@internal` shadowing rejects with
the ordinary stable visibility error before the shortcut. A bare unresolved name
or a name potentially supplied by a wildcard/conflicting import is ambiguous rather
than guessed from imports or a body.
`_root_` is removed before stable-id and platform-root checks, so
`_root_.java.*` remains forbidden.

Strict projection failures reuse `DescriptorError` and stable producer codes:

| Code | Meaning |
|---|---|
| `MISSING_MODULE_ID` | managed production requires a non-empty manifest `name:` |
| `MISSING_PUBLIC_TYPE` | an exported value, parameter, or result relies on inference |
| `DYNAMIC_PUBLIC_TYPE` | `Any`, `AnyRef`, `Object`, `Dynamic`, or a wildcard reaches the public ABI |
| `UNSUPPORTED_NUMERIC_WIDTH` | a source numeric type has no frozen v3 width (`Byte`, `Short`, `Float`, decimal) |
| `AMBIGUOUS_NAMED_TYPE` | a bare name is unresolved or may have conflicting/wildcard import bindings |
| `PLATFORM_TYPE_FORBIDDEN` | a public type directly, qualifiedly, or through an exact import names `scala.*`, `java.*`, or `javax.*` |
| `UNSUPPORTED_PUBLIC_TYPE` | the closed v3 algebra cannot represent the declared type without loss |
| `UNSUPPORTED_PUBLIC_DECLARATION` | the producer cannot represent the declaration kind without guessing |
| `INVALID_EFFECT_ROW` | an effect row has an unsupported or ambiguous member/tail shape |

Producer paths are deterministic declaration paths such as
`$.symbols[demo.f].resultType` and
`$.symbols[demo.f].parameterLists[0].parameters[1].tpe`; they do not depend on
legacy export ordering. Factory/codec validation errors retain their canonical
descriptor paths and codes.

The self-hosted frontend already has the target-neutral structured destination:
`ApiSymbolDefinition` plus `DescriptorFactory.api`. Slice B does not change
`NativeCompilation`, CoreIR, the structural lowerer, the seed, or compiler-image
bytes to make that frontend emit the declarations: those changes remain forbidden
before the P6.5 X1 fixed-point gate. After X1, the self-hosted declaration pass
must feed the same factory and pass the same producer vectors; it must not acquire
a second wire model or route through legacy `tpe`.

### Slice B behavior

- [x] Strict managed extraction populates canonical `apiDescriptorV3`; ordinary
      legacy extraction leaves it absent and remains compatible with inferred or
      dynamic legacy interfaces.
- [x] Descriptor construction completes from declaration headers before the
      legacy body-aware typer runs; body-only edits keep canonical descriptor
      bytes and `apiHash` unchanged.
- [x] `Int` and `Long` project to distinct `I32`/`I64` types and identities,
      including inside parameters, results, type arguments, and effect arguments.
- [x] Generic binder coordinates, multiple parameter-list boundaries, modes,
      defaults, unions/intersections, tuples, functions, and effect rows survive
      projection without rendered-type parsing.
- [x] Nominal types/constructors, typed values, aliases, effects, and plain/multi
      operations receive structured symbol kinds and resume multiplicity.
- [x] Function-typed parameters receive conservative `ForeignBarrier` callback
      policy; no pre-body producer claims `ManagedControl` from implementation
      behavior.
- [x] Missing/dynamic/ambiguous/unsupported public types fail the strict path with
      stable producer codes and paths, while the same legacy source still extracts
      with `apiDescriptorV3 = None`.
- [x] A regression fixture proves that even when legacy body typing renders a
      useful `ExportedSymbol.tpe`, the strict producer rejects a missing header
      type instead of parsing that string to invent v3.
- [x] Scala package/export fixtures agree on qualified public names and exclude
      helpers omitted by manifest `exports:`.
- [x] Retained executable document blocks and parsed section blocks are checked
      for one-to-one completeness; deleting `sections` from an otherwise intact
      parsed module rejects instead of emitting an empty API. Documentless modules
      still verify every retained `CodeBlock.source` against its AST, and dual
      source carriers must agree semantically.
- [x] Effect-header evidence ignores comments and string/character literals and
      binds within the same code block to the exact lexical owner; an ordinary
      same-name object cannot steal a later effect header.
- [x] Trait constructor clauses/self types, template exports, and constructor
      `val`/`var` accessors reject with stable paths until descriptor v3 has
      receiver/member metadata.
- [x] Retained source and stored section AST have exact declaration-header
      correspondence, not merely equal block counts; changing retained
      `effect Real` to remove an operation while preserving the old AST rejects,
      while a body-only change does not affect descriptor bytes or `apiHash`.
      Every manifest package wrapper is exact/plain before unwrapping; a wrapper
      parent, modifier, derive, self type, or other header state rejects.
- [x] A signature that resolves to a private/internal local owner or alias rejects
      with `UNSUPPORTED_PUBLIC_TYPE` before external-name fallback. A non-public
      callback alias cannot bypass the conservative callback-policy rule.
- [x] `Array[Byte]` becomes primitive `Bytes` only when both component names are
      unbound and have no local identity. Generic binders and public local
      `Array`/`Byte` take ordinary projection; a non-public local component rejects
      before the shortcut.
- [x] Selected public/exported `var` rejects until mutability is represented by an
      additive schema; the equivalent explicitly typed `val` remains projectable,
      and Slice A's model/canonical wire shape is unchanged.
- [ ] Explicit direct and renamed imports resolve before every bare builtin mapping;
      wildcard/conflicting imports fail closed, while rename-away/unimport exclusions
      preserve a builtin only when absence is provable. Array/Byte, Int/List, exact
      qualified positives, and platform-root imports have stable paths and codes.
- [ ] CodeBlock and Document carriers have equal raw semantic effect-header evidence
      before either supplies effect metadata. Empty effect versus object, plain versus
      multi, name/order, and unsupported generic/parent shape are observable while
      line offsets are not; documentless source remains independently checked.
- [ ] Reserved private type effect sentinels preserve only parser-erased origin
      evidence: they never become descriptor/runtime value members or alter
      EffectAnalysis/backend behavior, and a user collision fails strict managed
      production with or without a Document carrier instead of fabricating an effect.
- [ ] Non-empty derives and early-initializer clauses participate in exact retained
      header correspondence and reject on real public nominal declarations until the
      descriptor represents them, across every parseable class/trait/enum/object form.

## Versions

The three records are separately versioned:

```text
ApiDescriptor.schemaVersion             = "3.0"
ControlSummary.schemaVersion            = "1.0"
ArtifactManifest.artifactManifestVersion = "1.0"
descriptor canonical JSON profile       = "ssc-descriptor-json/1"
```

`controlAbiVersion` is explicit in every record and is not inferred from any of
the schema versions. A schema version describes fields and canonical encoding; the
control ABI version describes effect/control behavior. A reader rejects an
unsupported version before using the record.

The task name “descriptor v3” does **not** change the already frozen initial API
hash domain. Normative `apiHash` remains:

```text
SHA-256("ssc-api-v1\0" || canonical(ApiDescriptor without apiHash))
```

The domain version and record schema version are independent axes. Any future hash
profile requires an explicit new domain and compatibility rule; it is never inferred
from `schemaVersion`.

## Canonical public types

`AbiType` is a closed structured algebra:

```text
Primitive(Unit | Boolean | I32 | I64 | BigInt | F64 | String | Bytes | Char)
Named(stableTypeId, arguments[])
TypeParameter(depth, index, kindArity)
Tuple(elements[])
Function(parameterLists[][], result, effectRow)
Union(alternatives[])
Intersection(parts[])
TypeLambda(parameters[], body)
```

Public numeric widths are always `I32` and `I64`, never the strings `Int` and
`Long` and never an unqualified machine integer. The later producer slice must use
retained source width evidence and reject ambiguous legacy exports; descriptor
mapping alone does not close `numeric-width-reconciliation`.

Generic references use binder coordinates, not names. `depth = 0` names the
nearest binder and `index` is declaration order, so alpha-renaming a generic does
not change overload identity. A binder separately records its display name,
variance, higher-kinded arity, and optional lower/upper bounds; those source-facing
fields remain part of `apiHash`.

An `EffectRow` contains sorted, duplicate-free `EffectRef(stableEffectId,
typeArguments)` members and an optional open-tail type-parameter reference. Empty
closed rows are pure. Function types and exported callable results carry the row
structurally; a string such as `"A ! Foo"` is never the v3 contract.

Union and intersection members are set-like: canonicalization recursively
normalizes, deduplicates, and sorts them by canonical encoded value. Tuple,
argument, parameter-list, type-argument, and type-binder order remains significant.

## API descriptor

The phase-A record has this logical shape:

```text
ApiDescriptor(
  schemaVersion,
  controlAbiVersion,
  moduleId,
  apiHash,
  symbols[]
)

ApiSymbol(
  stableSymbolId,
  overloadId?,
  definition = ApiSymbolDefinition(
    qualifiedName,
    kind,
    typeParameters[],
    parameterLists[],
    resultType,
    effectRow,
    operationResumeMultiplicity?,
    callbackPolicies[],
    promptAndControlMetadata,
    requiredCapabilities[],
    requiredTargets[]
  )
)
```

Parameter lists preserve list boundaries, parameter names, type, value/contextual/
implicit/by-name/repeated mode, and default presence. Default expressions and
bodies are not part of the pre-body descriptor.

Callable definitions receive an `OverloadId`. Stable symbol and overload ids are
lowercase SHA-256 identities with distinct domains:

```text
stableSymbolId =
  "ssc:symbol:v1:" + hex(SHA-256(
    "ssc-symbol-v1\0" || canonical(moduleId, canonical ABI identity)
  ))

overloadId =
  "ssc:overload:v1:" + hex(SHA-256(
    "ssc-overload-v1\0" || canonical(moduleId, canonical callable identity)
  ))
```

The canonical ABI identity includes the qualified name, kind, alpha-stable generic
shape, parameter types/modes/list boundaries, result, effect row, resume
multiplicity, callback convention, and prompt/control metadata. It excludes its own
derived ids. Parameter and type-parameter display names/default presence remain in
the full descriptor and therefore affect `apiHash`, but do not create a second
overload solely through renaming.

The private symbol/overload preimage is nevertheless a frozen wire record, not a
Scala implementation detail:

```text
SymbolIdentity(moduleId, callable)
CallableIdentity(
  qualifiedName, kind,
  typeParameters[index, variance, kindArity, lowerBound, upperBound],
  parameterLists[[tpe, mode]],
  resultType, effectRow, operationResumeMultiplicity,
  callbackPolicies, promptAndControlMetadata
)
```

It uses the same product/tag/wrapper/option rules as
`ssc-descriptor-json/1`. The normative zero-argument example has canonical
preimage and results:

Every `AbiType` nested anywhere in this private preimage uses an alpha-stable
identity projection before encoding: each nested `TypeLambda.parameters[*].name`
is replaced by the empty string, recursively through its bounds, body, function
rows, union/intersection members, effect type arguments, parameter/result types,
and prompt answer types. Top-level callable type-parameter names remain omitted by
`TypeParameterIdentity`. The full `ApiDescriptor` keeps every real display name,
so renaming changes `apiHash` but not stable symbol/overload identity. After names
are blanked, the whole projected definition is normalized again: union,
intersection, effect, callback, and prompt set-like members are deduplicated and
sorted from the projected canonical bytes, never from the pre-erasure name order.

```text
{"callable":{"callbackPolicies":[],"effectRow":{"members":[],"openTail":[]},"kind":{"tag":"Function"},"operationResumeMultiplicity":[],"parameterLists":[],"promptAndControlMetadata":{"answerTypeModification":false,"capturesContinuation":false,"exposesContinuation":false,"prompts":[]},"qualifiedName":"demo.zero","resultType":{"tag":"Primitive","value":{"tag":"I32"}},"typeParameters":[]},"moduleId":"demo"}

stableSymbolId = ssc:symbol:v1:453bfef37e9c434783110ab89039214b9f8a7a998665c1125074c0d44d82faaf
overloadId     = ssc:overload:v1:a4daead86b456de1fe3ab86a936448c1546f09ed2ea812c95a689591e4d69fc9
```

## Callback and prompt policy

Every callback parameter is addressed by `(parameterListIndex, parameterIndex)`
and carries all common policy dimensions:

```text
callingConvention = PureDirect | Effectful | ManagedControl | ForeignBarrier
invocationMultiplicity = AtMostOnce | Many | Unknown
escape = NoEscape | MayEscape
reentrancy = NonReentrant | Reentrant | Unknown
concurrency = Serial | Concurrent | Unknown
cancellation = NotCancellable | Cancellable | Unknown
threadAffinity = AnyThread | CallingThread | EventLoop(profile) |
                 Actor(profile) | Named(profile)
```

`Unknown` is explicit policy, never absence interpreted optimistically.
`ManagedControl` is admission-worthy only when a later `ControlSummary` proves all
invocation edges managed.

Prompt/control metadata records stable prompt declaration ids, role
(`Binder`, `Parameter`, `Result`, `Shift`, or `Reset`), answer type, generativity,
portable scope (`ClosedRegionOnly` or `DurableCapability`), and whether the symbol
captures or exposes a continuation. Answer-type modification is `false` in v3 and
validation rejects `true` until a later ABI version defines it.

## Post-body control summary

`ControlSummary` contains:

```text
schemaVersion, controlAbiVersion, moduleId, apiHash, summaryDigest
managedCallEdges[]
foreignCallEdges[]
tailEdges[]
saveSites[]
frameSchemas[]
captureBarriers[]
```

Managed call edges name stable caller/callee ids and whether the edge participates
in the explicit effect/control protocol. Foreign edges name a structured foreign
target and barrier category. Tail edges record eligibility or the first stable
barrier code. Save sites name their owning symbol, requested code mode, live frame
schema, prompt set, and first barrier if unsavable. Frame slots carry `AbiType` and
an explicit `Durable`, `DurableRef`, or `Unsavable(code)` classification. A frame
schema has a stable `schemaId` and its ordered slots, but slice A has no separately
hashed `FrameSchemaDigest`: the containing `ControlSummary.summaryDigest` already
binds the schema bytes. The `frameDigest` over schema plus live payload belongs to
the saved-capsule format and is deliberately deferred rather than assigned an
unreviewed hash domain here.

A frame schema also declares its own ordered `typeParameters[]` binder group.
Every slot type is validated under that schema-local group; a nested `TypeLambda`
pushes another group in the usual way. This keeps a standalone `ControlSummary`
self-describing for polymorphic code without consulting an `ApiDescriptor`.
Concrete type instantiation at a save site belongs to the later capsule format.

Every save site's `frameSchemaId` must resolve to exactly one `frameSchemas` entry
in the same summary. Admission rejects a missing reference.

The digest is:

```text
SHA-256("ssc-control-summary-v1\0" ||
        canonical(ControlSummary without summaryDigest))
```

A body-only edit may leave `apiHash` unchanged while changing this digest.

## Post-link artifact manifest

`ArtifactManifest` contains the target-neutral common fields plus tagged target
bindings:

```text
artifactManifestVersion
controlAbiVersion
apiHash
target(id, abi, features[])
targetEntrypoints[]
programDigest?
artifactDigest?
runtimeVersion
dependencyManifest[]
dependencyProfileDigest
controlSummaryDigests[]
```

Dependency bindings distinguish primitive, plugin, resolver, codec, capability,
runtime, and artifact identities. Each binding names a semantic ABI id, optional
schema id, exact implementation digest, applicable target, and required
capabilities. The next `plugin-capability-profile-v1` slice supplies real plugin
producer data; this descriptor only defines the binding container.

`DependencyKind.Plugin` is an aggregate, atomic selection: a manifest contains
exactly one binding per stable `(pluginId, target)`, with `logicalId = pluginId`,
an aggregate `semanticAbiId` (and optional aggregate `schemaId`) that commits to
the plugin profile's normalized per-service provisions, and the exact target
implementation digest. Per-service provisions are not repeated as competing
`Plugin` bindings. A separately resolved primitive, codec, resolver, or capability
may instead receive its own binding under that dependency kind and stable logical
id. This preserves the frozen `(kind, logicalId, target)` ambiguity check while
letting the plugin profile evolve its internal service inventory atomically.

The exact normalized dependency vector is bound by the already specified domain:

```text
dependencyProfileDigest =
  SHA-256("ssc-dependencies-v1\0" || canonical(dependencyManifest))
```

`programDigest` identifies linked semantic program bytes; `artifactDigest`
identifies concrete target artifact bytes. They use distinct wrapper types and are
never substituted for `apiHash`, a control summary digest, or a dependency profile
digest.

The JVM binding is pure descriptor data:

```text
JvmEntrypoint(
  entrypointId,
  stableSymbolId,
  ownerInternalName,
  methodName,
  methodDescriptor,
  invocationKind,
  bridgeFlags[],
  classLoaderProfile,
  implementationDigest
)
```

`entrypointId` excludes `implementationDigest`, so a body-only rebuild can retain
the binding identity while changing the implementation/artifact digest:

```text
"ssc:jvm-entrypoint:v1:" +
hex(SHA-256("ssc-jvm-entrypoint-v1\0" || canonical(binding identity)))
```

No class is loaded and no application initializer runs while decoding or validating
the binding.

Other targets use a common pure-data `NamedTargetEntrypoint(entrypointId,
stableSymbolId, externalName, targetAbi, implementationDigest)`. Its identity
excludes `implementationDigest` and is frozen as:

```text
"ssc:target-entrypoint:v1:" +
hex(SHA-256("ssc-target-entrypoint-v1\0" ||
  canonical(NamedEntrypointIdentity(stableSymbolId, externalName, targetAbi))))
```

Entrypoint identity preimages use the exact field lists shown above: the JVM
preimage is `(stableSymbolId, ownerInternalName, methodName, methodDescriptor,
invocationKind, bridgeFlags, classLoaderProfile)`; the named preimage is
`(stableSymbolId, externalName, targetAbi)`. Normative vectors based on the symbol
example above are:

```text
JVM preimage = {"bridgeFlags":[],"classLoaderProfile":"application","invocationKind":{"tag":"Static"},"methodDescriptor":"()I","methodName":"zero","ownerInternalName":"demo/Main","stableSymbolId":{"value":"ssc:symbol:v1:453bfef37e9c434783110ab89039214b9f8a7a998665c1125074c0d44d82faaf"}}
JVM entrypointId = ssc:jvm-entrypoint:v1:20859d2db58ee7508193d092b2e3933c9273de8d0341f79900a7cdcd49cfae21

named preimage = {"externalName":"zero","stableSymbolId":{"value":"ssc:symbol:v1:453bfef37e9c434783110ab89039214b9f8a7a998665c1125074c0d44d82faaf"},"targetAbi":"js-es2024"}
named entrypointId = ssc:target-entrypoint:v1:ec9ea38732c339e777dc59f6e5b4a09adf4ed9f4d31de0f350c97e352bbe9222
```

## Canonical JSON and validation

The canonical codec is the JSON Canonicalization Scheme from
[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785), restricted to the descriptor
domain. The exact profile is:

- UTF-8 output with no BOM and no insignificant whitespace;
- explicit tagged variants and no duplicate object keys;
- object property names sorted by raw UTF-16 code units as required by JCS;
- strings contain valid Unicode scalar values (lone surrogates reject), escape `"`
  and `\`, use the JCS short escapes for backspace/tab/LF/form-feed/CR, use lowercase
  `\u00xx` for every other U+0000..U+001F control, and leave all other Unicode
  characters unescaped;
- descriptor JSON contains no floating-point numbers. Every numeric token is a
  non-negative integral index in `0..2147483647`; widths, digests, versions, and
  numeric ABI identities are strings or tagged variants;
- semantic normalization happens before JCS: set-like collections are recursively
  deduplicated and sorted by unsigned lexicographic comparison of their canonical
  UTF-8 bytes, while parameter,
  parameter-list, type-argument, tuple, binder, and other declared source order is
  retained;
- digest strings are lowercase 64-hex SHA-256 values;
- a self-hash field is omitted, not blanked, while that digest is computed.

Public admission bounds are constants of profile `ssc-descriptor-json/1`:

```text
MaxBytes          = 4_194_304 UTF-8 bytes
MaxDepth          = 256 object/array levels
MaxContainerItems = 100_000 members in any one object or array
```

The byte limit is checked before UTF-8 decoding. A string-aware structural pre-scan
checks depth and container counts before invoking the generic JSON parser, so a
deeply nested hostile input cannot exhaust its call stack. The renderer enforces
the same depth and item limits. Limit failures are structured descriptor errors,
never parser stack overflows or incidental exceptions.

The same limits apply to in-memory model values. Every public encoder, descriptor
factory, and structured hash/identity helper performs a non-recursive raw preflight
before recursive validation, alpha projection, normalization, hashing, or wire
encoding. It rejects an ABI type nesting depth above `MaxDepth` and any vector above
`MaxContainerItems` with a structured error. Thus a caller cannot bypass resource
bounds by constructing a Scala model directly, and no public path turns such input
into `StackOverflowError`. Raw program/artifact byte SHA-256 helpers are not model
decoders and retain their ordinary streaming-byte semantics.

### Frozen descriptor JSON shape

JCS freezes spelling and ordering but does not by itself define the model-to-JSON
schema. Profile `ssc-descriptor-json/1` therefore also freezes this shape:

- every product record is a JSON object with exactly the case-sensitive field
  names declared in this specification/model; all fields are present, including
  fields whose value is empty or optional;
- every enum/union alternative is an object whose mandatory discriminator is
  `"tag"` with the exact case name shown in this specification. Payload fields use
  the exact declared case-parameter names. A zero-payload case has only `"tag"`;
- typed string wrappers (`ApiHash`, `StableSymbolId`, `OverloadId`, digest and
  entrypoint wrappers) are objects with the sole field `"value"`;
- vectors are JSON arrays. An `Option[A]` is `[]` for `None` and `[a]` for
  `Some(a)`; no `null`, missing-field, or bare-value spelling is admitted;
- integers occur only in the index/arity fields already bounded by this profile;
  booleans and strings use their ordinary JSON forms;
- unknown fields, missing fields, alternate discriminators, alternate enum tags,
  and any other shape are schema errors. Even if a library parser tolerates an
  unknown field, canonical re-emission drops it and canonical-byte admission
  rejects the input.

Normative canonical fragments are:

```json
{"tag":"Primitive","value":{"tag":"I32"}}
{"arguments":[],"stableTypeId":"std.String","tag":"Named"}
{"apiHash":{"value":"0000000000000000000000000000000000000000000000000000000000000000"},"controlAbiVersion":"ssc-control-v1","moduleId":"example","schemaVersion":"3.0","symbols":[]}
```

The implementation keeps all JSON readers/writers package-internal. Public model
types do not expose a permissive generic-library `ReadWriter`; the only supported
wire entrypoints are the bounded canonical-admission methods for
`ApiDescriptor`, `ControlSummary`, and `ArtifactManifest`.

The public byte API is exactly:

```scala
object DescriptorCodec:
  val MaxBytes: Int = 4_194_304
  val MaxDepth: Int = 256
  val MaxContainerItems: Int = 100_000

  def encodeApi(value: ApiDescriptor): Either[DescriptorError, Array[Byte]]
  def decodeApi(bytes: Array[Byte]): Either[DescriptorError, ApiDescriptor]

  def encodeControlSummary(value: ControlSummary): Either[DescriptorError, Array[Byte]]
  def decodeControlSummary(bytes: Array[Byte]): Either[DescriptorError, ControlSummary]

  def encodeArtifactManifest(value: ArtifactManifest): Either[DescriptorError, Array[Byte]]
  def decodeArtifactManifest(bytes: Array[Byte]): Either[DescriptorError, ArtifactManifest]
```

Every encoder semantically validates, normalizes, and returns fresh canonical UTF-8
bytes. Every decoder enforces bounds, strict UTF-8, schema shape, exact canonical
byte equality, then semantic validation. There is no permissive public text or JSON
AST overload. A caller that stores canonical text (including the `.scim` carrier)
decodes encoder bytes as UTF-8; admission re-encodes its received UTF-8 bytes and
still goes through the byte API.

`DescriptorFactory` is the checked construction surface for the three records and
target bindings. It exposes `api`, `controlSummary`, `artifactManifest`,
`jvmEntrypoint`, and `namedEntrypoint`; both entrypoint factories derive their ids
and exclude `implementationDigest` from identity exactly as specified above. A
successful factory call semantically validates and bounded-renders the complete
returned wire record, including its derived digest/id fields. Therefore any value
returned as `Right` is immediately encodable under `MaxBytes`; fitting only the
smaller self-hash or entrypoint-identity preimage is insufficient.

The golden non-ASCII/control-character JCS vector is:

```text
logical entries (deliberately out of order):
  "😀" -> "雪"
  "z"  -> "line<LF>é"
  "a"  -> U+0001

canonical UTF-8 text:
{"a":"\u0001","z":"line\né","😀":"雪"}
```

Every public descriptor decode path is **canonical admission**, not a permissive
JSON parse: parse and schema-decode the bounded input, normalize the value, re-emit
JCS, and require `inputBytes == canonical(decoded)`. A different property order,
extra whitespace, alternate escaping, duplicate/set-like ordering, or any other
second textual representation fails with `NON_CANONICAL_JSON`, even if a generic
JSON parser could construct the same value. Internal tests may use a permissive
parser only to create negative fixtures; it is not a wire/admission API.

Structural duplicate and reference validation runs on the decoded value **before**
set-like normalization, so normalization cannot silently erase an invalid duplicate.
Effect-row identity is the complete normalized `EffectRef(stableEffectId,
typeArguments)`, not only its effect id. A foreign-call edge identity includes
caller plus the full foreign target, including its optional descriptor; a second
edge for the same identity with another barrier is ambiguous and rejects. A
dependency identity is `(kind, logicalId, target)`; two implementations for that
identity reject instead of depending on input order.

Every `TypeParameterRef` is validated against its lexical binder stack: `depth`
selects an existing enclosing binder group, `index` selects an existing binder,
and `kindArity` must equal that binder's arity. This applies to bounds, function
types, type lambdas, effect arguments/open tails, callbacks/prompts, and frame
slots; an unbound reference is invalid. A frame-slot type starts with its
`FrameSchema.typeParameters` as depth zero, not with an implicit reference to an
API symbol. A frame schema with no type parameters therefore requires closed slot
types except for references bound inside a nested `TypeLambda`.

After canonical-byte equality, semantic validation also rejects unsupported
versions, malformed ids or digests, duplicate symbols/effects/edges/entrypoints,
derived-id mismatch, `apiHash` mismatch, control-summary mismatch,
dependency-profile mismatch, an unresolved save-site frame schema, invalid
callback parameter paths, and forbidden answer-type modification. It returns
structured `(code, path, message)` errors and does not execute user code.

## Slice A behavior

- [x] The new v2 descriptor leaf builds independently and has no dependency on
      v1 compiler/runtime, CoreIR, UniML, a backend, or the Scala control API.
- [x] `AbiType` round-trips canonical primitives (including `I32`/`I64`), named
      generics, alpha-stable type parameters/type lambdas, functions, tuples,
      unions/intersections, and open/closed effect rows.
- [x] Callback policies and prompt/control metadata round-trip every common field
      without host object types or string-only signatures.
- [x] Stable symbol/overload/JVM-entrypoint ids are deterministic, domain-separated,
      order-independent where specified, and change when their semantic ABI input
      changes.
- [x] `ApiDescriptor`, `ControlSummary`, and `ArtifactManifest` canonical bytes and
      digests are deterministic; tampering or a wrong self/dependency digest is
      rejected before use.
- [x] RFC 8785 restricted JCS admission pins UTF-8, escaping, UTF-16 key ordering,
      bounded integral indices, and the non-ASCII/control-character golden vector;
      every non-canonical equivalent JSON text rejects.
- [x] The exact `ssc-descriptor-json/1` object fields, `tag` alternatives, wrapper,
      vector, and option shapes are golden-tested; public models expose no
      permissive generic-library decoder that bypasses canonical admission.
- [x] Reordering symbols, set-like type members, capabilities, dependencies, edges,
      or target features does not change canonical bytes; parameter/type-argument/
      parameter-list order remains observable.
- [x] A body/control/artifact-only change can keep `apiHash` stable while changing
      the appropriate control or artifact digest.
- [x] Raw duplicates, an ambiguous dependency implementation, an unbound or
      wrong-arity type-parameter reference, and a missing save-site frame schema
      reject with stable structured errors before normalization can erase them.
- [x] Legacy `.scim` JSON and MessagePack payloads without `apiDescriptorV3` decode
      to `None`; a v3 payload round-trips, stripping it restores `None`, and every
      existing field retains its previous value and meaning.
- [x] `ArtifactVersion.current` remains `2.0`; no existing artifact field is renamed,
      removed, retyped, reordered, or repurposed.

## Deferred slices (resume-cold)

1. **Pre-body producers.** Add declaration-only projections from the self-hosted
   frontend and compatibility declaration-header AST pipeline. Populate
   `apiDescriptorV3` before
   body compilation, reject unknown/ambiguous public numeric widths and unsupported
   dynamic ABI shapes, and add Scala import/export fixtures. Never derive v3 by
   parsing legacy `tpe`.
2. **Post-body summaries.** Extract managed/foreign call edges, tail eligibility,
   save sites, frame schemas, and barriers from typed/lowered bodies. Prove that
   callback `ManagedControl` claims agree with all call edges.
3. **Post-link manifests.** Populate program/artifact bytes and target entrypoints
   in JVM/JS/Rust/Swift/WASM linkers, bind the plugin capability profile, and expose
   inspect commands without loading user code.
4. **Consumers and compatibility.** Make facade generation, mixed-build admission,
   saved-capsule admission, and host runners consume v3 first; retain explicit
   legacy fallback only for ordinary non-managed interop. Add checked fixtures from
   the last pre-v3 compiler and golden cross-language canonical JSON vectors.

These slices remain part of the parent `ssc-api-descriptor-v3` milestone. Landing
slice A must not mark that milestone complete.

## Out of scope

- changing effect, handler, prompt, shift/reset, save/run, or tail-call semantics;
- adding any CoreIR term/value or changing the canonical CoreIR codec;
- frontend/lowering or seed/image byte changes before P6.5 X1;
- inferring `I32`/`I64` from an ambiguous lowered integer carrier;
- defining plugin semantic ABI contents (owned by
  `plugin-capability-profile-v1`);
- populating real compiler/linker summaries in slice A;
- changing `.scim` legacy fields, envelope version, or fallback behavior;
- loading classes/plugins or executing user code during descriptor decode.

## Results

A fresh independent review rejected exact frozen checkpoint `4cd2a4aaa` (rebased
as `05e498a72`) with no P0 and three P1 fail-open classes. Imports were ignored
before bare builtin projection; dual source carriers could disagree on raw
effect/object or multiplicity evidence after preprocessing; and derives/early
template headers were absent from both correspondence and nominal losslessness
checks. The reviewer explicitly confirmed the previous exact-package-wrapper,
mandatory CodeBlock-source, and Array/Byte binder/local corrections. All twelve
Slice B `descriptor-v3-*` entries remain `open`; the new behavior items above are
unchecked until faithful red vectors and implementation gates pass.

The third Slice B correction is implementation commit `72e6a2897`, rebased on
`origin/main@790366a9d`. It closes the three P1 gaps from the fresh rereview:
package wrappers must be exact and plain, every `CodeBlock.source` participates in
source/AST correspondence even without `DocumentContent`, and the primitive
`Array[Byte]` shortcut resolves both component identities first. The faithful red
baseline was `39/46` at original regression commit `387a10384` (rebased as
`af7094249`): one package-wrapper, two retained-source, and four Array/Byte
shadowing repros returned `Right`, while all prior 38 regressions and the new
unshadowed built-in positive stayed green.

The correction is locally green after rebase but is not independently approved or
landed. All nine Slice B `descriptor-v3-*` bug entries remain `open`, and the Slice
B sprint item remains unchecked until a fresh read-only review approves this clean
checkpoint and it lands on `origin/main`.

- `scripts/sbtc "core/testOnly
  scalascript.artifact.PreBodyApiDescriptorProducerTest"` passes 46/46 focused
  producer regressions.
- `scripts/sbtc "v2InteropDescriptor/test"` passes 27/27 descriptor tests;
  `scripts/sbtc "core/test"` passes 1092/1092; the combined
  `scripts/sbtc "interop/test; ir/test; core/testOnly
  scalascript.artifact.ArtifactAbiCompatibilityTest"` gate passes interop 36/36,
  the zero-test IR project, and artifact ABI 73/73.
- `tests/conformance/run.sh --only 'modules*,import-dir*'` passes 2/2 affected
  cases (memoized green on the unchanged conformance corpus).

The historical second Slice B compatibility-producer checkpoint was implementation
commit `abf6d909a` (rebased as `ddb4c6b0f`) and frozen as `8a8886557` (rebased as
`28535c87d`). A fresh independent read-only rereview rejected it with no P0 and
the three P1 gaps above; the green numbers below are rejected-checkpoint history,
not approval or landing evidence.

- `scripts/sbtc "core/testOnly
  scalascript.artifact.PreBodyApiDescriptorProducerTest"` passes 38/38 focused
  producer regressions after rebasing onto `origin/main@b1e93d0f9`.
- `scripts/sbtc "v2InteropDescriptor/test; core/test; interop/test; ir/test"`
  passes descriptor 27/27, core 1084/1084, interop 36/36, and the zero-test IR
  project gate; a separate `scripts/sbtc "ir/test"` succeeds as well.
- `tests/conformance/run.sh --only 'modules*,import-dir*'` passes 2/2 affected
  cases (memoized green on this unchanged conformance corpus).

Slice A implementation is commit `286de7cee`; the matching public documentation
is commit `68a470a64`. An independent descriptor audit approved the implementation
without blockers.

- `scripts/sbtc "v2InteropDescriptor/test; core/testOnly
  scalascript.artifact.ArtifactAbiCompatibilityTest"` passes 27/27 descriptor
  tests and 73/73 legacy artifact ABI tests.
- `scripts/sbtc "show v2InteropDescriptor/projectDependencies; ir/test; core/test;
  interop/test"` prints only `*` for the leaf dependency graph and passes the
  affected integration radius: `ir/test` succeeds, `core/test` passes 1046/1046,
  and `interop/test` passes 36/36.
- `tests/conformance/run.sh --only 'modules*,import-dir*'` passes 2/2 cases on
  interpreter, JavaScript, and JVM lanes.
- The legacy carrier remains opaque and defaulted; no Slice B/C/D producer,
  linker, facade, admission, or runner behavior is claimed by these results.
