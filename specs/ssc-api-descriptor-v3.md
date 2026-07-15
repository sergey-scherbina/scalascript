# SSC API descriptor v3

Status: **slice A specified; implementation in progress** (2026-07-15).

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
slots; an unbound reference is invalid. A frame-slot type starts with an empty
external binder stack because `ControlSummary` carries no declaration binders: it
must be closed, except for references bound inside a `TypeLambda` contained in that
slot type. Generic source locals are projected to such a self-contained ABI shape
by the later post-body producer rather than implicitly referring back into an
`ApiDescriptor`.

After canonical-byte equality, semantic validation also rejects unsupported
versions, malformed ids or digests, duplicate symbols/effects/edges/entrypoints,
derived-id mismatch, `apiHash` mismatch, control-summary mismatch,
dependency-profile mismatch, an unresolved save-site frame schema, invalid
callback parameter paths, and forbidden answer-type modification. It returns
structured `(code, path, message)` errors and does not execute user code.

## Slice A behavior

- [ ] The new v2 descriptor leaf builds independently and has no dependency on
      v1 compiler/runtime, CoreIR, UniML, a backend, or the Scala control API.
- [ ] `AbiType` round-trips canonical primitives (including `I32`/`I64`), named
      generics, alpha-stable type parameters/type lambdas, functions, tuples,
      unions/intersections, and open/closed effect rows.
- [ ] Callback policies and prompt/control metadata round-trip every common field
      without host object types or string-only signatures.
- [ ] Stable symbol/overload/JVM-entrypoint ids are deterministic, domain-separated,
      order-independent where specified, and change when their semantic ABI input
      changes.
- [ ] `ApiDescriptor`, `ControlSummary`, and `ArtifactManifest` canonical bytes and
      digests are deterministic; tampering or a wrong self/dependency digest is
      rejected before use.
- [ ] RFC 8785 restricted JCS admission pins UTF-8, escaping, UTF-16 key ordering,
      bounded integral indices, and the non-ASCII/control-character golden vector;
      every non-canonical equivalent JSON text rejects.
- [ ] The exact `ssc-descriptor-json/1` object fields, `tag` alternatives, wrapper,
      vector, and option shapes are golden-tested; public models expose no
      permissive generic-library decoder that bypasses canonical admission.
- [ ] Reordering symbols, set-like type members, capabilities, dependencies, edges,
      or target features does not change canonical bytes; parameter/type-argument/
      parameter-list order remains observable.
- [ ] A body/control/artifact-only change can keep `apiHash` stable while changing
      the appropriate control or artifact digest.
- [ ] Raw duplicates, an ambiguous dependency implementation, an unbound or
      wrong-arity type-parameter reference, and a missing save-site frame schema
      reject with stable structured errors before normalization can erase them.
- [ ] Legacy `.scim` JSON and MessagePack payloads without `apiDescriptorV3` decode
      to `None`; a v3 payload round-trips, stripping it restores `None`, and every
      existing field retains its previous value and meaning.
- [ ] `ArtifactVersion.current` remains `2.0`; no existing artifact field is renamed,
      removed, retyped, reordered, or repurposed.

## Deferred slices (resume-cold)

1. **Pre-body producers.** Add declaration-only projections from the self-hosted
   frontend and compatibility `SType` pipeline. Populate `apiDescriptorV3` before
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

Fill after verification with commit ids, focused test counts, dependency graph
evidence, and the affected conformance command.
