# Plugin capability profile v1

Status: **specified / implementation pending** (2026-07-15).

This specification supplies the plugin dependency data required by
[`control-interoperability.md`](control-interoperability.md) and the
`ArtifactManifest.dependencyManifest` defined by
[`ssc-api-descriptor-v3.md`](ssc-api-descriptor-v3.md). It extends the current
ScalaScript 2 native plugin SPI, whose semantic surface is only `id` plus
`install`, without making a runtime plugin the owner of language semantics.

## Outcome

A plugin capability declaration identifies the stable semantic contracts and
schemas that a plugin claims to implement. A target implementation binds that
declaration to exact implementation bytes and target ABI. Linkers and admission
code can then compare a required dependency profile with an available plugin
inventory before installing a plugin or executing user code.

The identities deliberately answer different questions:

- a service `semanticAbiId` names externally specified behavior;
- a service `schemaId` names an externally specified value/wire schema;
- an aggregate plugin semantic ABI id commits to the normalized service and
  dependency declaration;
- an aggregate plugin schema id commits to the normalized schema declaration;
- an implementation digest identifies exact target implementation bytes;
- `version`, `spiVersion`, filenames, class names, and load order are human/tooling
  metadata and are never semantic or implementation identity.

Runtime plugins assert that they implement named contracts. They do not define
the meaning of effects, handlers, prompts, CoreIR primitives, codecs, or host
interop. Those meanings remain owned by their normative language/library/interop
specifications and conformance vectors.

## Architecture and module boundary

The target-neutral model lives in a new leaf module:

```text
v2/interop/plugin-profile
package scalascript.interop.plugin
sbt project: v2PluginCapabilityProfile
artifact: io.scalascript:scalascript-plugin-profile_3
```

The module depends only on `v2InteropDescriptor`, so it can project a validated
profile to the canonical `DependencyBinding` record. It has no dependency on v1,
`v2Core`, UniML, a frontend, a backend, a runtime value, a plugin host, or a host
control API. Its public model contains only immutable values, strings, vectors,
options, bytes at the digest boundary, and descriptor wrappers.

`v2NativePluginSpi` depends on this leaf and additively exposes an optional
declaration. Existing ServiceLoader providers and their installation order remain
unchanged:

```scala
trait NativePlugin:
  def id: String
  def capabilityDeclaration: Option[PluginCapabilityDeclaration] = None
  def install(context: NativePluginContext): Unit
```

`None` means a legacy/unqualified provider. It remains runnable through the
ordinary local compatibility path, but it cannot satisfy a profiled dependency
binding. There is no inferred declaration from `id`, registration names, package
version, class bytes, or the effects observed while `install` runs.

The profile leaf is the semantic owner of this data model. The method on
`NativePlugin` is only an additive JVM ServiceLoader adapter. Other host/plugin
systems reproduce the same target-neutral records without depending on the JVM
SPI.

## Public model

```scala
object PluginProfileVersions:
  val Schema: String = "1.0"
  val CanonicalFraming: String = "ssc-plugin-profile-framing/1"

final case class SemanticProvision(
  logicalId: String,
  semanticAbiId: String,
  schemaId: Option[String] = None,
  capabilities: Vector[String] = Vector.empty
)

final case class PluginRequirement(
  pluginId: String,
  semanticAbiId: String,
  schemaId: Option[String] = None,
  requiredCapabilities: Vector[String] = Vector.empty
)

final case class PluginCapabilityDeclaration(
  schemaVersion: String,
  pluginId: String,
  provisions: Vector[SemanticProvision],
  providedCapabilities: Vector[String] = Vector.empty,
  dependencies: Vector[PluginRequirement] = Vector.empty
)

final case class PluginTargetImplementation(
  target: String,
  targetAbi: String,
  implementationDigest: ImplementationDigest,
  requiredCapabilities: Vector[String] = Vector.empty
)

final case class PluginCapabilityProfile(
  declaration: PluginCapabilityDeclaration,
  aggregateSemanticAbiId: String,
  aggregateSchemaId: Option[String],
  implementation: PluginTargetImplementation
)

final case class PluginProfileError(code: String, path: String, message: String)
```

`SemanticProvision.logicalId` names a service within the plugin, for example an
extern family, primitive, codec, resolver, or effect runner. Its
`semanticAbiId` is the versioned contract id assigned by the specification that
owns that service, for example `effect.perform.oneshot@1`; it is not chosen from
the implementation class name. `capabilities` is the set of stable capability
ids that provision supplies.

`PluginRequirement` names another plugin's aggregate semantic/schema identity.
The dependency is semantic and target-neutral; the concrete target digest is
selected and pinned when the complete artifact dependency manifest is assembled.
Self-dependencies and two requirements for the same `pluginId` reject.

`PluginTargetImplementation.implementationDigest` is SHA-256 of the exact,
already packaged target implementation byte sequence selected by the linker or
installer. The public factory accepts exact bytes or a precomputed lowercase
64-hex SHA-256 value for streaming/package pipelines. Source text, a class name,
Maven coordinates, a package semver, and `spiVersion` are not substitutes. The
digest does not participate in aggregate semantic/schema identity: rebuilding a
semantically identical implementation changes only implementation identity.

## Construction, normalization, and identities

Callers construct profiles through checked functions, not by trusting a plugin's
derived fields:

```scala
object PluginCapabilityProfiles:
  def bind(
    declaration: PluginCapabilityDeclaration,
    implementation: PluginTargetImplementation
  ): Either[PluginProfileError, PluginCapabilityProfile]

  def implementationFromBytes(
    target: String,
    targetAbi: String,
    exactArtifactBytes: Array[Byte],
    requiredCapabilities: Vector[String] = Vector.empty
  ): Either[PluginProfileError, PluginTargetImplementation]

  def implementationFromDigest(
    target: String,
    targetAbi: String,
    implementationDigest: ImplementationDigest,
    requiredCapabilities: Vector[String] = Vector.empty
  ): Either[PluginProfileError, PluginTargetImplementation]

  def dependencyBinding(
    profile: PluginCapabilityProfile
  ): Either[PluginProfileError, DependencyBinding]
```

All capability vectors, provisions, and dependencies are set-like. Validation
first rejects duplicate capability ids, duplicate provision logical ids, duplicate
dependency plugin ids, empty ids, unsupported versions, malformed aggregate ids,
malformed implementation digests, and a provision capability absent from
`providedCapabilities`. It then normalizes recursively and sorts by unsigned
lexicographic comparison of the canonical framed bytes. Input order cannot change
the aggregate ids.

The hash framing is deliberately small and language-neutral. An unsigned 32-bit
big-endian count prefixes every vector. An unsigned 32-bit big-endian UTF-8 byte
length prefixes every string. An option is one byte (`0x00` or `0x01`) followed,
for `Some`, by the framed string. Integers are not otherwise present. All text must
be valid Unicode scalar text. A vector with more than 100,000 elements, an encoded
profile string larger than 4,194,304 bytes, or a complete aggregate-identity
preimage larger than 4,194,304 bytes rejects before hashing. These bounds apply to
profile data, not to target artifact bytes: `implementationFromBytes` hashes the
entire supplied artifact, and streaming/package pipelines may hash arbitrarily large
artifacts out of band and pass the checked `ImplementationDigest` through
`implementationFromDigest`.

The canonical element layouts below use `s(x)` for a framed string, `o(x)` for
an optional framed string, and `v(xs)` for a framed vector whose elements are
concatenated. Fields appear in exactly the listed order; there are no field names,
padding bytes, host integers, or implicit values in a preimage:

```text
provision(p) =
  s(p.logicalId) || s(p.semanticAbiId) || o(p.schemaId) ||
  v(p.capabilities.map(s))

requirement(r) =
  s(r.pluginId) || s(r.semanticAbiId) || o(r.schemaId) ||
  v(r.requiredCapabilities.map(s))

canonicalSemanticDeclaration =
  s(declaration.pluginId) ||
  v(declaration.provisions.map(provision)) ||
  v(declaration.providedCapabilities.map(s)) ||
  v(declaration.dependencies.map(requirement))

schemaProvision(p) = s(p.logicalId) || s(p.schemaId.get)
schemaRequirement(r) = s(r.pluginId) || s(r.schemaId.get)

canonicalSchemaDeclaration =
  s(declaration.pluginId) ||
  v(declaration.provisions.filter(_.schemaId.nonEmpty).map(schemaProvision)) ||
  v(declaration.dependencies.filter(_.schemaId.nonEmpty).map(schemaRequirement))
```

Every set-like vector is validated for raw duplicates and then sorted by unsigned
lexicographic order of its fully framed element bytes before it enters a parent
layout. `schemaVersion` is validated as exactly `1.0` but is omitted from the
preimage because the domain strings below freeze both schema and framing version.

The target-neutral aggregate ids are:

```text
aggregateSemanticAbiId =
  "ssc:plugin-abi:v1:" + lowercaseHex(SHA-256(
    "ssc-plugin-abi-v1\0" || canonicalSemanticDeclaration
  ))

aggregateSchemaId =
  None, when no provision or dependency declares a schema id
  otherwise Some("ssc:plugin-schema:v1:" + lowercaseHex(SHA-256(
    "ssc-plugin-schema-v1\0" || canonicalSchemaDeclaration
  )))
```

The semantic declaration contains `pluginId`, normalized provisions including
their external semantic/schema ids and capabilities, normalized aggregate
provided capabilities, and normalized plugin requirements. The schema declaration
contains `pluginId` and every schema-bearing provision/requirement paired with its
logical id. Neither preimage contains target, target ABI, implementation digest,
human version, SPI version, filename, class name, path, source order, or load order.

## Descriptor projection

Exactly one aggregate `DependencyKind.Plugin` binding is emitted for one stable
`(pluginId, target)` selection:

`PluginCapabilityProfiles.dependencyBinding` revalidates the declaration and
implementation, recomputes both aggregate identities, and rejects a forged or
stale `PluginCapabilityProfile` before emitting the binding. On success it emits:

```scala
DependencyBinding(
  kind = DependencyKind.Plugin,
  logicalId = checked.declaration.pluginId,
  semanticAbiId = checked.aggregateSemanticAbiId,
  schemaId = checked.aggregateSchemaId,
  implementationDigest = checked.implementation.implementationDigest,
  target = Some(checked.implementation.target),
  requiredCapabilities =
    normalized(checked.implementation.requiredCapabilities)
)
```

There is not one descriptor plugin binding per service. Per-service provisions
remain inside the profile and are transitively committed by the aggregate ids.
Separate descriptor bindings are emitted only for genuinely independently
resolved primitives, codecs, resolvers, capabilities, runtimes, or artifacts under
their own `DependencyKind` and stable logical id.

The descriptor's frozen uniqueness key `(kind, logicalId, target)` means two
implementations for the same plugin and target are ambiguous and reject even if
their ABI or digest differs. A profile's `targetAbi` must equal the enclosing
`ArtifactManifest.target.abi`; `target` must equal its target id.

## Inventory validation

```scala
object PluginProfileInventory:
  def validate(
    dependencyManifest: Vector[DependencyBinding],
    availableProfiles: Vector[PluginCapabilityProfile],
    target: TargetProfile,
    admittedCapabilities: Set[String]
  ): Either[PluginProfileError, Vector[PluginCapabilityProfile]]
```

Validation is pure and runs before any `NativePlugin.install`, class initialization,
resource opening, or user code. It performs these checks:

1. raw duplicate plugin bindings and duplicate available `(pluginId, target)`
   profiles reject;
2. every `DependencyKind.Plugin` binding has exactly one matching available
   profile for the enclosing target id/ABI;
3. semantic ABI id, optional schema id, implementation digest, and normalized
   required capabilities match exactly;
4. every provision capability is contained in its declaration's aggregate
   `providedCapabilities`; every `PluginRequirement.requiredCapabilities` is
   contained in the dependency profile's `providedCapabilities`; and every target
   implementation requirement is contained in the union of the enclosing target
   features and explicitly admitted capabilities before installation;
5. every transitive `PluginRequirement` resolves to a matching available profile
   on the same target and also has an exact binding in the artifact dependency
   manifest, so a plugin cannot introduce a hidden dependency after admission;
6. the returned vector is deterministic topological order (dependency before
   consumer, plugin id ordered by unsigned lexicographic comparison of its
   canonical framed UTF-8 bytes as the tie-break); a cycle is a structured error.

Cycle detection uses the normalized `pluginId` edges after hidden-binding checks
but before per-edge aggregate ABI/schema comparison. Aggregate ids commit the
declared dependency ids; they are not recursively solved to a cryptographic fixed
point. Cycles are invalid, so admission reports `PLUGIN_DEPENDENCY_CYCLE` directly
instead of first reporting an arbitrary edge hash mismatch. Acyclic graphs are then
checked edge-by-edge against the dependency profiles and bindings.

Non-plugin descriptor bindings remain the responsibility of their own primitive,
codec, resolver, capability, runtime, or artifact validators. This validator never
loads or invokes a plugin to discover missing facts.

## Compatibility and failure policy

The ordinary local compatibility path may continue loading a legacy provider whose
`capabilityDeclaration` is `None`. A profiled artifact or saved capsule cannot use
it to satisfy a plugin dependency and receives `MISSING_PLUGIN_PROFILE` before
execution. A declaration returned by an instantiated provider may be compared with
already admitted metadata as a defense-in-depth assertion, but it is not the
pre-execution source of truth.

Failures are structured `PluginProfileError` values. Stable codes include:

```text
UNSUPPORTED_PROFILE_VERSION
INVALID_ID
INVALID_SHA256
DUPLICATE_PROVISION
DUPLICATE_DEPENDENCY
DUPLICATE_CAPABILITY
SELF_DEPENDENCY
UNDECLARED_PROVISION_CAPABILITY
AMBIGUOUS_PLUGIN_IMPLEMENTATION
MISSING_PLUGIN_PROFILE
TARGET_MISMATCH
TARGET_ABI_MISMATCH
SEMANTIC_ABI_MISMATCH
SCHEMA_MISMATCH
IMPLEMENTATION_DIGEST_MISMATCH
CAPABILITY_DENIED
HIDDEN_PLUGIN_DEPENDENCY
PLUGIN_DEPENDENCY_CYCLE
RESOURCE_LIMIT
```

No mismatch falls back to package version comparison, load order, last-wins map
merge, or best-effort execution.

## Behavior

- [ ] The profile leaf builds independently of v1, CoreIR, UniML, every backend,
      runtime values, plugin installation, and host control libraries.
- [ ] Equivalent declarations in different input order produce byte-identical
      aggregate semantic/schema ids and descriptor plugin bindings.
- [ ] Semantic ABI, schema, dependency, or declared-capability changes alter the
      appropriate aggregate identity; target implementation bytes change only the
      implementation digest/binding.
- [ ] Human package version, `spiVersion`, class name, path, and load order are not
      present in either aggregate identity preimage.
- [ ] Exact artifact bytes have a deterministic SHA-256 implementation digest;
      malformed precomputed digests reject.
- [ ] Duplicate/empty ids, duplicate capabilities, self/duplicate dependencies,
      undeclared provision capabilities, malformed hashes, and oversized preimages
      reject with stable structured errors before hashing or normalization erases
      evidence.
- [ ] Projection emits one exact `DependencyKind.Plugin` binding per
      `(pluginId,target)` and preserves aggregate required capabilities.
- [ ] Inventory validation rejects target/ABI/semantic/schema/digest/capability
      mismatch, ambiguous implementations, a missing exact binding, hidden
      transitive dependencies, and dependency cycles before plugin installation.
- [ ] A legacy `NativePlugin` that implements only `id` and `install` still loads
      through ServiceLoader unchanged; it cannot satisfy a profiled dependency.
- [ ] A profiled `NativePlugin` declaration round-trips through the additive SPI
      method without changing ownership/conflict behavior in `NativePluginHost`.

## Out of scope

- assigning semantics from plugin code, runtime observations, or implementation
  names;
- using semver or SPI compatibility as semantic/admission identity;
- changing `NativePlugin.install`, `NativePluginContext`, ServiceLoader discovery,
  backend compilation, intrinsic dispatch, or conflict resolution;
- adding CoreIR terms/values, compiler lowering, UniML syntax, or seed/image bytes;
- a `.sscpkg`/registry carrier and automatic linker population of all existing
  plugins; those consumers use this model in later descriptor producer/linker
  slices;
- migrating every legacy v1 Backend or v2 NativePlugin in this slice;
- plugin trust/sandbox policy, signing, download, or package governance;
- weakening the requirement that new intrinsics live in `runtime/std/*-plugin`.

## Verification plan

1. `v2PluginCapabilityProfile/test` covers identity vectors, normalization,
   implementation-byte hashing, descriptor projection, dependency closure, and all
   stable negative categories.
2. `v2NativePluginSpi/test` covers an unchanged legacy provider and an additive
   profiled provider through the real deterministic ServiceLoader host path.
3. `v2PluginCapabilityProfile/dependencyTree` proves the leaf depends only on the
   descriptor leaf plus Scala/JDK libraries.
4. Run the affected plugin conformance slice through
   `tests/conformance/run.sh --only 'plugin-*'`; if no matching corpus cases exist,
   record the explicit zero-match result and run the native plugin host tests as the
   affected gate.

## Results

Fill after implementation with commit ids, exact test counts, dependency graph
evidence, and conformance output.
