package scalascript.interop.descriptor


object DescriptorVersions:
  val CanonicalJsonProfile: String = "ssc-descriptor-json/1"
  val ApiDescriptor: String = "3.0"
  val ControlSummary: String = "1.0"
  val ArtifactManifest: String = "1.0"

final case class ApiHash(value: String)
final case class ControlSummaryDigest(value: String)
final case class DependencyProfileDigest(value: String)
final case class ProgramDigest(value: String)
final case class ArtifactDigest(value: String)
final case class ImplementationDigest(value: String)

final case class StableSymbolId(value: String)
final case class OverloadId(value: String)
final case class EntrypointId(value: String)

enum AbiPrimitive:
  case Unit, Boolean, I32, I64, BigInt, F64, String, Bytes, Char

enum Variance:
  case Invariant, Covariant, Contravariant

final case class TypeParameterRef(
    depth: Int,
    index: Int,
    kindArity: Int = 0
)

final case class AbiTypeParameter(
    index: Int,
    name: String,
    variance: Variance = Variance.Invariant,
    kindArity: Int = 0,
    lowerBound: Option[AbiType] = None,
    upperBound: Option[AbiType] = None
)

final case class EffectRef(
    stableEffectId: String,
    typeArguments: Vector[AbiType] = Vector.empty
)

final case class EffectRow(
    members: Vector[EffectRef] = Vector.empty,
    openTail: Option[TypeParameterRef] = None
)

object EffectRow:
  val Pure: EffectRow = EffectRow()

enum AbiType:
  case Primitive(value: AbiPrimitive)
  case Named(stableTypeId: String, arguments: Vector[AbiType] = Vector.empty)
  case TypeParameter(reference: TypeParameterRef)
  case Tuple(elements: Vector[AbiType])
  case Function(
      parameterLists: Vector[Vector[AbiType]],
      result: AbiType,
      effects: EffectRow = EffectRow.Pure
  )
  case Union(alternatives: Vector[AbiType])
  case Intersection(parts: Vector[AbiType])
  case TypeLambda(parameters: Vector[AbiTypeParameter], body: AbiType)

enum ApiSymbolKind:
  case Value, Function, Constructor, Type, Effect, Operation, Prompt

  def isCallable: Boolean = this match
    case Function | Constructor | Operation => true
    case _                                  => false

enum ParameterMode:
  case Value, Contextual, Implicit, ByName, Repeated

final case class ApiParameter(
    name: String,
    tpe: AbiType,
    mode: ParameterMode = ParameterMode.Value,
    hasDefault: Boolean = false
)

final case class ApiParameterList(
    parameters: Vector[ApiParameter]
)

final case class CallbackParameterPath(
    parameterListIndex: Int,
    parameterIndex: Int
)

enum CallingConvention:
  case PureDirect, Effectful, ManagedControl, ForeignBarrier

enum InvocationMultiplicity:
  case AtMostOnce, Many, Unknown

enum CallbackEscape:
  case NoEscape, MayEscape

enum CallbackReentrancy:
  case NonReentrant, Reentrant, Unknown

enum CallbackConcurrency:
  case Serial, Concurrent, Unknown

enum CallbackCancellation:
  case NotCancellable, Cancellable, Unknown

enum ThreadAffinity:
  case AnyThread
  case CallingThread
  case EventLoop(profile: String)
  case Actor(profile: String)
  case Named(profile: String)

final case class CallbackPolicy(
    parameter: CallbackParameterPath,
    callingConvention: CallingConvention,
    invocationMultiplicity: InvocationMultiplicity,
    escape: CallbackEscape,
    reentrancy: CallbackReentrancy,
    concurrency: CallbackConcurrency,
    cancellation: CallbackCancellation,
    threadAffinity: ThreadAffinity
)

enum PromptRole:
  case Binder, Parameter, Result, Shift, Reset

enum PromptGenerativity:
  case Fresh, External

enum PromptPortability:
  case ClosedRegionOnly, DurableCapability

final case class PromptMetadata(
    stablePromptId: String,
    role: PromptRole,
    answerType: AbiType,
    generativity: PromptGenerativity,
    portability: PromptPortability
)

final case class PromptAndControlMetadata(
    prompts: Vector[PromptMetadata] = Vector.empty,
    capturesContinuation: Boolean = false,
    exposesContinuation: Boolean = false,
    answerTypeModification: Boolean = false
)

enum ResumeMultiplicity:
  case OneShot, Reusable

final case class ApiSymbolDefinition(
    qualifiedName: String,
    kind: ApiSymbolKind,
    typeParameters: Vector[AbiTypeParameter] = Vector.empty,
    parameterLists: Vector[ApiParameterList] = Vector.empty,
    resultType: AbiType,
    effectRow: EffectRow = EffectRow.Pure,
    operationResumeMultiplicity: Option[ResumeMultiplicity] = None,
    callbackPolicies: Vector[CallbackPolicy] = Vector.empty,
    promptAndControlMetadata: PromptAndControlMetadata = PromptAndControlMetadata(),
    requiredCapabilities: Vector[String] = Vector.empty,
    requiredTargets: Vector[String] = Vector.empty
)

final case class ApiSymbol(
    stableSymbolId: StableSymbolId,
    overloadId: Option[OverloadId],
    definition: ApiSymbolDefinition
)

final case class ApiDescriptor(
    schemaVersion: String,
    controlAbiVersion: String,
    moduleId: String,
    apiHash: ApiHash,
    symbols: Vector[ApiSymbol]
)

enum ManagedCallKind:
  case Pure, Effectful, ManagedControl

final case class ManagedCallEdge(
    caller: StableSymbolId,
    callee: StableSymbolId,
    kind: ManagedCallKind
)

enum ForeignBarrierKind:
  case Precompiled, Virtual, Reflective, Native, Resource, Async, Callback, Affinity, Unknown

final case class ForeignTarget(
    profile: String,
    owner: String,
    name: String,
    descriptor: Option[String] = None
)

final case class ForeignCallEdge(
    caller: StableSymbolId,
    target: ForeignTarget,
    barrier: ForeignBarrierKind
)

enum TailDisposition:
  case Eligible
  case Barrier(code: String)

final case class TailEdge(
    caller: StableSymbolId,
    callee: StableSymbolId,
    disposition: TailDisposition
)

enum RequestedCodeMode:
  case Portable, ExactArtifact

enum FrameSlotDurability:
  case Durable
  case DurableRef(providerId: String)
  case Unsavable(code: String)

final case class FrameSlot(
    slotId: String,
    tpe: AbiType,
    durability: FrameSlotDurability
)

final case class FrameSchema(
    schemaId: String,
    typeParameters: Vector[AbiTypeParameter] = Vector.empty,
    slots: Vector[FrameSlot]
)

final case class CaptureBarrierSummary(
    siteId: String,
    owner: StableSymbolId,
    category: String,
    detail: String
)

final case class SaveSiteSummary(
    siteId: String,
    owner: StableSymbolId,
    requestedCodeMode: RequestedCodeMode,
    frameSchemaId: String,
    stablePromptIds: Vector[String] = Vector.empty,
    firstBarrier: Option[CaptureBarrierSummary] = None
)

final case class ControlSummary(
    schemaVersion: String,
    controlAbiVersion: String,
    moduleId: String,
    apiHash: ApiHash,
    summaryDigest: ControlSummaryDigest,
    managedCallEdges: Vector[ManagedCallEdge] = Vector.empty,
    foreignCallEdges: Vector[ForeignCallEdge] = Vector.empty,
    tailEdges: Vector[TailEdge] = Vector.empty,
    saveSites: Vector[SaveSiteSummary] = Vector.empty,
    frameSchemas: Vector[FrameSchema] = Vector.empty,
    captureBarriers: Vector[CaptureBarrierSummary] = Vector.empty
)

final case class TargetProfile(
    id: String,
    abi: String,
    features: Vector[String] = Vector.empty
)

enum JvmInvocationKind:
  case Static, Virtual, Interface, Special

final case class JvmEntrypoint(
    entrypointId: EntrypointId,
    stableSymbolId: StableSymbolId,
    ownerInternalName: String,
    methodName: String,
    methodDescriptor: String,
    invocationKind: JvmInvocationKind,
    bridgeFlags: Vector[String],
    classLoaderProfile: String,
    implementationDigest: ImplementationDigest
)

final case class NamedTargetEntrypoint(
    entrypointId: EntrypointId,
    stableSymbolId: StableSymbolId,
    externalName: String,
    targetAbi: String,
    implementationDigest: ImplementationDigest
)

enum TargetEntrypoint:
  case Jvm(value: JvmEntrypoint)
  case Named(value: NamedTargetEntrypoint)

enum DependencyKind:
  case Primitive, Plugin, Resolver, Codec, Capability, Runtime, Artifact

final case class DependencyBinding(
    kind: DependencyKind,
    logicalId: String,
    semanticAbiId: String,
    schemaId: Option[String],
    implementationDigest: ImplementationDigest,
    target: Option[String],
    requiredCapabilities: Vector[String] = Vector.empty
)

final case class ArtifactManifest(
    artifactManifestVersion: String,
    controlAbiVersion: String,
    apiHash: ApiHash,
    target: TargetProfile,
    targetEntrypoints: Vector[TargetEntrypoint],
    programDigest: Option[ProgramDigest],
    artifactDigest: Option[ArtifactDigest],
    runtimeVersion: String,
    dependencyManifest: Vector[DependencyBinding],
    dependencyProfileDigest: DependencyProfileDigest,
    controlSummaryDigests: Vector[ControlSummaryDigest]
)

final case class DescriptorError(
    code: String,
    path: String,
    message: String
)
