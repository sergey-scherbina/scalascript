package scalascript.interop.descriptor

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

final class DescriptorValidationSuite extends AnyFunSuite:
  private val Utf8 = StandardCharsets.UTF_8
  private val I32 = AbiType.Primitive(AbiPrimitive.I32)
  private val I64 = AbiType.Primitive(AbiPrimitive.I64)
  private val ZeroHash = "0" * 64

  private def must[A](value: Either[DescriptorError, A]): A =
    value.fold(error => fail(s"${error.code} at ${error.path}: ${error.message}"), identity)

  private def error[A](value: Either[DescriptorError, A]): DescriptorError =
    value.swap.getOrElse(fail("expected descriptor error"))

  private def code[A](value: Either[DescriptorError, A]): String = error(value).code

  private def definition(name: String, result: AbiType = I32): ApiSymbolDefinition =
    ApiSymbolDefinition(name, ApiSymbolKind.Function, resultType = result)

  private def symbolId(name: String): StableSymbolId =
    must(DescriptorHashes.stableSymbolId("demo", definition(name)))

  private def implementation(value: String): ImplementationDigest =
    DescriptorHashes.implementation(value.getBytes(Utf8))

  private def controlFixture(apiHash: ApiHash = ApiHash("a" * 64)): ControlSummary =
    val ids = Vector.tabulate(24)(index => symbolId(s"demo.symbol$index"))
    val managedKinds = Vector(
      ManagedCallKind.Pure,
      ManagedCallKind.Effectful,
      ManagedCallKind.ManagedControl
    )
    val foreignBarriers = Vector(
      ForeignBarrierKind.Precompiled,
      ForeignBarrierKind.Virtual,
      ForeignBarrierKind.Reflective,
      ForeignBarrierKind.Native,
      ForeignBarrierKind.Resource,
      ForeignBarrierKind.Async,
      ForeignBarrierKind.Callback,
      ForeignBarrierKind.Affinity,
      ForeignBarrierKind.Unknown
    )
    val frameTypeParameters = Vector(AbiTypeParameter(0, "F", kindArity = 1))
    val nestedSlotType = AbiType.TypeLambda(
      Vector(AbiTypeParameter(0, "A")),
      AbiType.Tuple(Vector(
        AbiType.TypeParameter(TypeParameterRef(0, 0)),
        AbiType.TypeParameter(TypeParameterRef(1, 0, kindArity = 1))
      ))
    )
    val frame = FrameSchema(
      "frame:generic",
      frameTypeParameters,
      Vector(
        FrameSlot(
          "local",
          AbiType.TypeParameter(TypeParameterRef(0, 0, kindArity = 1)),
          FrameSlotDurability.Durable
        ),
        FrameSlot("nested", nestedSlotType, FrameSlotDurability.DurableRef("heap-v1")),
        FrameSlot("blocked", I64, FrameSlotDurability.Unsavable("native-resource"))
      )
    )
    val barrier = CaptureBarrierSummary("barrier:1", ids(20), "foreign", "native frame")

    must(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      apiHash,
      managedCallEdges = managedKinds.zipWithIndex.map { case (kind, index) =>
        ManagedCallEdge(ids(index), ids(index + 1), kind)
      },
      foreignCallEdges = foreignBarriers.zipWithIndex.map { case (kind, index) =>
        ForeignCallEdge(
          ids(4),
          ForeignTarget("jvm", s"demo/Owner$index", "call", Some(s"(I)I#$index")),
          kind
        )
      },
      tailEdges = Vector(
        TailEdge(ids(5), ids(6), TailDisposition.Eligible),
        TailEdge(ids(7), ids(8), TailDisposition.Barrier("foreign-call"))
      ),
      saveSites = Vector(
        SaveSiteSummary(
          "save:portable",
          ids(9),
          RequestedCodeMode.Portable,
          frame.schemaId,
          Vector("prompt:z", "prompt:a")
        ),
        SaveSiteSummary(
          "save:exact",
          ids(10),
          RequestedCodeMode.ExactArtifact,
          frame.schemaId,
          firstBarrier = Some(barrier)
        )
      ),
      frameSchemas = Vector(frame),
      captureBarriers = Vector(barrier)
    ))

  private def artifactFixture(
      apiHash: ApiHash = ApiHash("a" * 64),
      control: ControlSummary = controlFixture()
  ): ArtifactManifest =
    val owner = symbolId("demo.entry")
    val invocationKinds = Vector(
      JvmInvocationKind.Static,
      JvmInvocationKind.Virtual,
      JvmInvocationKind.Interface,
      JvmInvocationKind.Special
    )
    val jvmEntrypoints = invocationKinds.zipWithIndex.map { case (kind, index) =>
      TargetEntrypoint.Jvm(must(DescriptorFactory.jvmEntrypoint(
        owner,
        s"demo/Main$index",
        s"entry$index",
        "()I",
        kind,
        Vector(s"bridge-$index"),
        "application",
        implementation(s"jvm-$index")
      )))
    }
    val named = TargetEntrypoint.Named(must(DescriptorFactory.namedEntrypoint(
      owner,
      "entry",
      "js-es2024",
      implementation("js")
    )))
    val dependencyKinds = Vector(
      DependencyKind.Primitive,
      DependencyKind.Plugin,
      DependencyKind.Resolver,
      DependencyKind.Codec,
      DependencyKind.Capability,
      DependencyKind.Runtime,
      DependencyKind.Artifact
    )
    val dependencies = dependencyKinds.zipWithIndex.map { case (kind, index) =>
      DependencyBinding(
        kind,
        s"dependency-$index",
        s"semantic-abi-$index",
        if index % 2 == 0 then Some(s"schema-$index") else None,
        implementation(s"dependency-$index"),
        if index % 2 == 0 then Some("jvm") else None,
        Vector(s"capability-$index")
      )
    }

    must(DescriptorFactory.artifactManifest(
      "ssc-control-v1",
      apiHash,
      TargetProfile("jvm", "jvm-21", Vector("threads", "tail-calls")),
      jvmEntrypoints :+ named,
      Some(DescriptorHashes.program("program".getBytes(Utf8))),
      Some(DescriptorHashes.artifact("artifact".getBytes(Utf8))),
      "runtime-1",
      dependencies,
      Vector(control.summaryDigest)
    ))

  test("ControlSummary and ArtifactManifest round-trip every target/control tagged variant"):
    val control = controlFixture()
    val controlBytes = must(DescriptorCodec.encodeControlSummary(control))
    assert(must(DescriptorCodec.decodeControlSummary(controlBytes)) == control)
    val controlText = new String(controlBytes, Utf8)
    Vector(
      "Pure", "Effectful", "ManagedControl",
      "Precompiled", "Virtual", "Reflective", "Native", "Resource", "Async",
      "Callback", "Affinity", "Unknown", "Eligible", "Barrier", "Portable",
      "ExactArtifact", "Durable", "DurableRef", "Unsavable"
    ).foreach(tag => assert(controlText.contains(s"\"tag\":\"$tag\""), tag))

    val artifact = artifactFixture(control = control)
    val artifactBytes = must(DescriptorCodec.encodeArtifactManifest(artifact))
    assert(must(DescriptorCodec.decodeArtifactManifest(artifactBytes)) == artifact)
    val artifactText = new String(artifactBytes, Utf8)
    Vector("Static", "Virtual", "Interface", "Special", "Jvm", "Named")
      .foreach(tag => assert(artifactText.contains(s"\"tag\":\"$tag\""), tag))
    Vector("Primitive", "Plugin", "Resolver", "Codec", "Capability", "Runtime", "Artifact")
      .foreach(tag => assert(artifactText.contains(s"\"tag\":\"$tag\""), tag))
    assert(artifactText.contains("\"programDigest\":[{"))
    assert(artifactText.contains("\"schemaId\":[]"))
    assert(artifactText.contains("\"schemaId\":[\""))

  test("ApiDescriptor round-trips every primitive, type, symbol, parameter, callback, and prompt variant"):
    val primitives = Vector(
      AbiPrimitive.Unit,
      AbiPrimitive.Boolean,
      AbiPrimitive.I32,
      AbiPrimitive.I64,
      AbiPrimitive.BigInt,
      AbiPrimitive.F64,
      AbiPrimitive.String,
      AbiPrimitive.Bytes,
      AbiPrimitive.Char
    ).map(AbiType.Primitive.apply)
    val topReference = AbiType.TypeParameter(TypeParameterRef(0, 0))
    val nestedLambda = AbiType.TypeLambda(
      Vector(AbiTypeParameter(0, "U")),
      AbiType.Tuple(Vector(
        AbiType.TypeParameter(TypeParameterRef(0, 0)),
        AbiType.TypeParameter(TypeParameterRef(1, 0))
      ))
    )
    val allTypes = primitives ++ Vector(
      AbiType.Named("demo.Box", Vector(topReference)),
      topReference,
      AbiType.Tuple(Vector(I32, I64)),
      AbiType.Function(
        Vector(Vector(I32), Vector(I64)),
        I32,
        EffectRow(Vector(EffectRef("demo.Closed")))
      ),
      AbiType.Union(Vector(I32, I64)),
      AbiType.Intersection(Vector(
        AbiType.Named("demo.Left"),
        AbiType.Named("demo.Right")
      )),
      nestedLambda
    )
    val parameterModes = Vector(
      ParameterMode.Value,
      ParameterMode.Contextual,
      ParameterMode.Implicit,
      ParameterMode.ByName,
      ParameterMode.Repeated
    )
    val parameters = parameterModes.zipWithIndex.map { case (mode, index) =>
      ApiParameter(
        s"callback$index",
        AbiType.Function(Vector(Vector(I32)), I64),
        mode,
        hasDefault = index % 2 == 0
      )
    }
    val callbacks = Vector(
      CallbackPolicy(
        CallbackParameterPath(0, 0),
        CallingConvention.PureDirect,
        InvocationMultiplicity.AtMostOnce,
        CallbackEscape.NoEscape,
        CallbackReentrancy.NonReentrant,
        CallbackConcurrency.Serial,
        CallbackCancellation.NotCancellable,
        ThreadAffinity.AnyThread
      ),
      CallbackPolicy(
        CallbackParameterPath(0, 1),
        CallingConvention.Effectful,
        InvocationMultiplicity.Many,
        CallbackEscape.MayEscape,
        CallbackReentrancy.Reentrant,
        CallbackConcurrency.Concurrent,
        CallbackCancellation.Cancellable,
        ThreadAffinity.CallingThread
      ),
      CallbackPolicy(
        CallbackParameterPath(0, 2),
        CallingConvention.ManagedControl,
        InvocationMultiplicity.Unknown,
        CallbackEscape.NoEscape,
        CallbackReentrancy.Unknown,
        CallbackConcurrency.Unknown,
        CallbackCancellation.Unknown,
        ThreadAffinity.EventLoop("browser-main")
      ),
      CallbackPolicy(
        CallbackParameterPath(0, 3),
        CallingConvention.ForeignBarrier,
        InvocationMultiplicity.AtMostOnce,
        CallbackEscape.MayEscape,
        CallbackReentrancy.NonReentrant,
        CallbackConcurrency.Serial,
        CallbackCancellation.NotCancellable,
        ThreadAffinity.Actor("actor-main")
      ),
      CallbackPolicy(
        CallbackParameterPath(0, 4),
        CallingConvention.PureDirect,
        InvocationMultiplicity.Many,
        CallbackEscape.NoEscape,
        CallbackReentrancy.Reentrant,
        CallbackConcurrency.Concurrent,
        CallbackCancellation.Cancellable,
        ThreadAffinity.Named("ui-thread")
      )
    )
    val promptRoles = Vector(
      PromptRole.Binder,
      PromptRole.Parameter,
      PromptRole.Result,
      PromptRole.Shift,
      PromptRole.Reset
    )
    val prompts = promptRoles.zipWithIndex.map { case (role, index) =>
      PromptMetadata(
        s"prompt:$index",
        role,
        primitives(index),
        if index % 2 == 0 then PromptGenerativity.Fresh else PromptGenerativity.External,
        if index % 2 == 0 then PromptPortability.ClosedRegionOnly
        else PromptPortability.DurableCapability
      )
    }
    val complex = ApiSymbolDefinition(
      "demo.all",
      ApiSymbolKind.Function,
      typeParameters = Vector(AbiTypeParameter(0, "T")),
      parameterLists = Vector(ApiParameterList(parameters)),
      resultType = AbiType.Tuple(allTypes),
      effectRow = EffectRow(
        Vector(EffectRef("demo.Open", Vector(topReference))),
        openTail = Some(TypeParameterRef(0, 0))
      ),
      callbackPolicies = callbacks,
      promptAndControlMetadata = PromptAndControlMetadata(
        prompts,
        capturesContinuation = true,
        exposesContinuation = true
      )
    )
    val definitions = Vector(
      ApiSymbolDefinition("demo.value", ApiSymbolKind.Value, resultType = I32),
      complex,
      ApiSymbolDefinition("demo.constructor", ApiSymbolKind.Constructor, resultType = I32),
      ApiSymbolDefinition("demo.type", ApiSymbolKind.Type, resultType = I32),
      ApiSymbolDefinition("demo.effect", ApiSymbolKind.Effect, resultType = I32),
      ApiSymbolDefinition(
        "demo.operationOneShot",
        ApiSymbolKind.Operation,
        resultType = I32,
        operationResumeMultiplicity = Some(ResumeMultiplicity.OneShot)
      ),
      ApiSymbolDefinition(
        "demo.operationReusable",
        ApiSymbolKind.Operation,
        resultType = I32,
        operationResumeMultiplicity = Some(ResumeMultiplicity.Reusable)
      ),
      ApiSymbolDefinition("demo.prompt", ApiSymbolKind.Prompt, resultType = I32)
    )
    val api = must(DescriptorFactory.api("ssc-control-v1", "demo", definitions))
    val bytes = must(DescriptorCodec.encodeApi(api))
    assert(must(DescriptorCodec.decodeApi(bytes)) == api)
    val text = new String(bytes, Utf8)

    Vector(
      "Unit", "Boolean", "I32", "I64", "BigInt", "F64", "String", "Bytes", "Char",
      "Named", "TypeParameter", "Tuple", "Function", "Union", "Intersection", "TypeLambda",
      "Value", "Constructor", "Type", "Effect", "Operation", "Prompt",
      "Contextual", "Implicit", "ByName", "Repeated",
      "PureDirect", "Effectful", "ManagedControl", "ForeignBarrier",
      "AtMostOnce", "Many", "Unknown", "NoEscape", "MayEscape",
      "NonReentrant", "Reentrant", "Serial", "Concurrent",
      "NotCancellable", "Cancellable", "AnyThread", "CallingThread", "EventLoop", "Actor",
      "Binder", "Parameter", "Result", "Shift", "Reset", "Fresh", "External",
      "ClosedRegionOnly", "DurableCapability", "OneShot", "Reusable"
    ).foreach(tag => assert(text.contains(s"\"tag\":\"$tag\""), tag))
    assert(text.contains("\"profile\":\"ui-thread\",\"tag\":\"Named\""))
    assert(text.contains("\"openTail\":[]"))
    assert(text.contains("\"openTail\":[{"))

  test("API raw set-like duplicates reject before normalization while effect type arguments remain identity"):
    val callback = CallbackPolicy(
      CallbackParameterPath(0, 0),
      CallingConvention.ManagedControl,
      InvocationMultiplicity.Many,
      CallbackEscape.MayEscape,
      CallbackReentrancy.Reentrant,
      CallbackConcurrency.Concurrent,
      CallbackCancellation.Cancellable,
      ThreadAffinity.AnyThread
    )
    val prompt = PromptMetadata(
      "prompt:demo",
      PromptRole.Reset,
      I32,
      PromptGenerativity.Fresh,
      PromptPortability.ClosedRegionOnly
    )
    val validDefinition = ApiSymbolDefinition(
      "demo.raw",
      ApiSymbolKind.Function,
      parameterLists = Vector(ApiParameterList(Vector(ApiParameter(
        "callback",
        AbiType.Function(Vector.empty, I32)
      )))),
      resultType = AbiType.Union(Vector(I32, I64)),
      effectRow = EffectRow(Vector(
        EffectRef("demo.Effect", Vector(I32)),
        EffectRef("demo.Effect", Vector(I64))
      )),
      callbackPolicies = Vector(callback),
      promptAndControlMetadata = PromptAndControlMetadata(prompts = Vector(prompt)),
      requiredCapabilities = Vector("console"),
      requiredTargets = Vector("jvm")
    )
    val valid = must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(validDefinition)))
    assert(valid.symbols.head.definition.effectRow.members.size == 2)
    val symbol = valid.symbols.head
    def changed(next: ApiSymbolDefinition): ApiDescriptor =
      valid.copy(symbols = Vector(symbol.copy(definition = next)))

    val failures = Vector(
      "DUPLICATE_SYMBOL_ID" -> valid.copy(symbols = Vector(symbol, symbol)),
      "DUPLICATE_UNION_ALTERNATIVE" -> changed(validDefinition.copy(
        resultType = AbiType.Union(Vector(I32, I32))
      )),
      "DUPLICATE_INTERSECTION_PART" -> changed(validDefinition.copy(
        resultType = AbiType.Intersection(Vector(I64, I64))
      )),
      "DUPLICATE_EFFECT_ROW_MEMBER" -> changed(validDefinition.copy(
        effectRow = EffectRow(Vector(EffectRef("demo.Effect"), EffectRef("demo.Effect")))
      )),
      "DUPLICATE_CALLBACK_POLICY" -> changed(validDefinition.copy(
        callbackPolicies = Vector(callback, callback)
      )),
      "DUPLICATE_PROMPT_METADATA" -> changed(validDefinition.copy(
        promptAndControlMetadata = PromptAndControlMetadata(prompts = Vector(prompt, prompt))
      )),
      "DUPLICATE_REQUIRED_CAPABILITY" -> changed(validDefinition.copy(
        requiredCapabilities = Vector("console", "console")
      )),
      "DUPLICATE_REQUIRED_TARGET" -> changed(validDefinition.copy(
        requiredTargets = Vector("jvm", "jvm")
      ))
    )
    failures.foreach { case (expected, value) =>
      assert(code(DescriptorValidator.api(value)) == expected, expected)
    }

  test("control raw identities include the full foreign target and reject every duplicate family"):
    val valid = controlFixture()
    val sameTarget = valid.foreignCallEdges.head
    val failures = Vector(
      "DUPLICATE_MANAGED_EDGE" -> valid.copy(
        managedCallEdges = valid.managedCallEdges :+ valid.managedCallEdges.head
      ),
      "DUPLICATE_FOREIGN_EDGE" -> valid.copy(
        foreignCallEdges = valid.foreignCallEdges :+ sameTarget.copy(barrier = ForeignBarrierKind.Native)
      ),
      "DUPLICATE_TAIL_EDGE" -> valid.copy(
        tailEdges = valid.tailEdges :+ valid.tailEdges.head
      ),
      "DUPLICATE_SAVE_SITE" -> valid.copy(
        saveSites = valid.saveSites :+ valid.saveSites.head
      ),
      "DUPLICATE_FRAME_SCHEMA" -> valid.copy(
        frameSchemas = valid.frameSchemas :+ valid.frameSchemas.head
      ),
      "DUPLICATE_CAPTURE_BARRIER" -> valid.copy(
        captureBarriers = valid.captureBarriers :+ valid.captureBarriers.head
      ),
      "DUPLICATE_PROMPT_ID" -> valid.copy(saveSites = valid.saveSites.updated(
        0,
        valid.saveSites.head.copy(stablePromptIds = Vector("prompt", "prompt"))
      )),
      "UNKNOWN_FRAME_SCHEMA" -> valid.copy(saveSites = valid.saveSites.updated(
        0,
        valid.saveSites.head.copy(frameSchemaId = "missing")
      ))
    )
    failures.foreach { case (expected, value) =>
      assert(code(DescriptorValidator.controlSummary(value)) == expected, expected)
    }

    val caller = symbolId("demo.foreign")
    val firstTarget = ForeignTarget("jvm", "demo/Foreign", "call", Some("()I"))
    val descriptorSensitive = DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      ApiHash("b" * 64),
      foreignCallEdges = Vector(
        ForeignCallEdge(caller, firstTarget, ForeignBarrierKind.Precompiled),
        ForeignCallEdge(caller, firstTarget.copy(descriptor = Some("(I)I")), ForeignBarrierKind.Native)
      )
    )
    assert(descriptorSensitive.isRight)
    assert(code(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      ApiHash("b" * 64),
      foreignCallEdges = Vector(
        ForeignCallEdge(caller, firstTarget, ForeignBarrierKind.Precompiled),
        ForeignCallEdge(caller, firstTarget, ForeignBarrierKind.Native)
      )
    )) == "DUPLICATE_FOREIGN_EDGE")

  test("artifact raw identities reject ambiguous dependencies and every duplicate family"):
    val valid = artifactFixture()
    val firstEntrypoint = valid.targetEntrypoints.collectFirst {
      case TargetEntrypoint.Jvm(value) => value
    }.getOrElse(fail("missing JVM entrypoint"))
    val firstDependency = valid.dependencyManifest.head
    val duplicateBridge = TargetEntrypoint.Jvm(firstEntrypoint.copy(
      bridgeFlags = firstEntrypoint.bridgeFlags :+ firstEntrypoint.bridgeFlags.head
    ))
    val duplicateCapability = firstDependency.copy(
      requiredCapabilities = firstDependency.requiredCapabilities :+
        firstDependency.requiredCapabilities.head
    )
    val failures = Vector(
      "DUPLICATE_TARGET_FEATURE" -> valid.copy(
        target = valid.target.copy(features = valid.target.features :+ valid.target.features.head)
      ),
      "DUPLICATE_ENTRYPOINT" -> valid.copy(
        targetEntrypoints = valid.targetEntrypoints :+ valid.targetEntrypoints.head
      ),
      "DUPLICATE_DEPENDENCY" -> valid.copy(
        dependencyManifest = valid.dependencyManifest :+
          firstDependency.copy(implementationDigest = implementation("other"))
      ),
      "DUPLICATE_CONTROL_SUMMARY_DIGEST" -> valid.copy(
        controlSummaryDigests = valid.controlSummaryDigests :+ valid.controlSummaryDigests.head
      ),
      "DUPLICATE_BRIDGE_FLAG" -> valid.copy(
        targetEntrypoints = duplicateBridge +: valid.targetEntrypoints.tail
      ),
      "DUPLICATE_REQUIRED_CAPABILITY" -> valid.copy(
        dependencyManifest = duplicateCapability +: valid.dependencyManifest.tail
      )
    )
    failures.foreach { case (expected, value) =>
      assert(code(DescriptorValidator.artifact(value)) == expected, expected)
    }

    val originalDigest = must(DescriptorHashes.dependencyProfileDigest(valid.dependencyManifest))
    val reorderedDigest = must(DescriptorHashes.dependencyProfileDigest(valid.dependencyManifest.reverse))
    assert(originalDigest == reorderedDigest)
    val changedImplementation = valid.dependencyManifest.updated(
      0,
      firstDependency.copy(implementationDigest = implementation("changed"))
    )
    assert(must(DescriptorHashes.dependencyProfileDigest(changedImplementation)) != originalDigest)

  test("lexical binder stacks validate nested type lambdas and frame-local parameters"):
    val outer = AbiTypeParameter(0, "F", kindArity = 1)
    val nested = AbiType.TypeLambda(
      Vector(AbiTypeParameter(0, "A")),
      AbiType.Tuple(Vector(
        AbiType.TypeParameter(TypeParameterRef(0, 0)),
        AbiType.TypeParameter(TypeParameterRef(1, 0, kindArity = 1))
      ))
    )
    val valid = definition("demo.binders", nested).copy(typeParameters = Vector(outer))
    assert(DescriptorFactory.api("ssc-control-v1", "demo", Vector(valid)).isRight)

    val unbound = valid.copy(resultType = AbiType.TypeParameter(TypeParameterRef(1, 0, 1)))
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(unbound))) ==
      "UNBOUND_TYPE_PARAMETER")
    val wrongArity = valid.copy(resultType = AbiType.TypeParameter(TypeParameterRef(0, 0, 0)))
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(wrongArity))) ==
      "TYPE_PARAMETER_KIND_ARITY_MISMATCH")

    val owner = symbolId("demo.frame")
    val validFrame = FrameSchema(
      "frame:local",
      Vector(AbiTypeParameter(0, "T", kindArity = 1)),
      Vector(FrameSlot(
        "value",
        AbiType.TypeParameter(TypeParameterRef(0, 0, kindArity = 1)),
        FrameSlotDurability.Durable
      ))
    )
    assert(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      ApiHash("c" * 64),
      saveSites = Vector(SaveSiteSummary(
        "save",
        owner,
        RequestedCodeMode.Portable,
        validFrame.schemaId
      )),
      frameSchemas = Vector(validFrame)
    ).isRight)

    val unboundFrame = validFrame.copy(typeParameters = Vector.empty)
    assert(code(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      ApiHash("c" * 64),
      frameSchemas = Vector(unboundFrame)
    )) == "UNBOUND_TYPE_PARAMETER")
    val wrongFrameArity = validFrame.copy(slots = Vector(validFrame.slots.head.copy(
      tpe = AbiType.TypeParameter(TypeParameterRef(0, 0, kindArity = 0))
    )))
    assert(code(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      ApiHash("c" * 64),
      frameSchemas = Vector(wrongFrameArity)
    )) == "TYPE_PARAMETER_KIND_ARITY_MISMATCH")

  test("callback paths, answer-type modification, and operation multiplicity are explicit errors"):
    val parameter = ApiParameterList(Vector(ApiParameter("callback", AbiType.Function(Vector.empty, I32))))
    val invalidCallback = definition("demo.callback").copy(
      parameterLists = Vector(parameter),
      callbackPolicies = Vector(CallbackPolicy(
        CallbackParameterPath(0, 1),
        CallingConvention.ManagedControl,
        InvocationMultiplicity.Unknown,
        CallbackEscape.MayEscape,
        CallbackReentrancy.Unknown,
        CallbackConcurrency.Unknown,
        CallbackCancellation.Unknown,
        ThreadAffinity.CallingThread
      ))
    )
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(invalidCallback))) ==
      "INVALID_CALLBACK_PARAMETER_PATH")

    val answerChanging = definition("demo.answer").copy(
      promptAndControlMetadata = PromptAndControlMetadata(answerTypeModification = true)
    )
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(answerChanging))) ==
      "ANSWER_TYPE_MODIFICATION_UNSUPPORTED")

    val operation = ApiSymbolDefinition("demo.op", ApiSymbolKind.Operation, resultType = I32)
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(operation))) ==
      "MISSING_RESUME_MULTIPLICITY")
    val unexpected = definition("demo.function").copy(
      operationResumeMultiplicity = Some(ResumeMultiplicity.Reusable)
    )
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(unexpected))) ==
      "UNEXPECTED_RESUME_MULTIPLICITY")

  test("body-only changes preserve apiHash but change control and artifact bytes"):
    val api = must(DescriptorFactory.api(
      "ssc-control-v1",
      "demo",
      Vector(definition("demo.body"))
    ))
    val owner = api.symbols.head.stableSymbolId
    val first = must(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      api.apiHash
    ))
    val second = must(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      api.apiHash,
      tailEdges = Vector(TailEdge(owner, owner, TailDisposition.Eligible))
    ))
    assert(first.apiHash == second.apiHash)
    assert(first.summaryDigest != second.summaryDigest)
    assert(code(DescriptorValidator.controlSummary(second.copy(
      summaryDigest = first.summaryDigest
    ))) == "CONTROL_SUMMARY_DIGEST_MISMATCH")

    val artifact = artifactFixture(api.apiHash, second)
    assert(code(DescriptorValidator.artifact(artifact.copy(
      dependencyProfileDigest = DependencyProfileDigest(ZeroHash)
    ))) == "DEPENDENCY_PROFILE_DIGEST_MISMATCH")
    val rebuilt = artifact.copy(artifactDigest = Some(DescriptorHashes.artifact("other".getBytes(Utf8))))
    assert(rebuilt.apiHash == artifact.apiHash)
    assert(rebuilt.artifactDigest != artifact.artifactDigest)
    assert(!java.util.Arrays.equals(
      must(DescriptorCodec.encodeArtifactManifest(rebuilt)),
      must(DescriptorCodec.encodeArtifactManifest(artifact))
    ))

  test("set-like reordering is invisible while declared parameter/type-argument order is observable"):
    val unordered = definition(
      "demo.order",
      AbiType.Union(Vector(I64, I32))
    ).copy(
      effectRow = EffectRow(Vector(EffectRef("z"), EffectRef("a"))),
      requiredCapabilities = Vector("z", "a"),
      requiredTargets = Vector("js", "jvm")
    )
    val reordered = unordered.copy(
      resultType = AbiType.Union(Vector(I32, I64)),
      effectRow = EffectRow(unordered.effectRow.members.reverse),
      requiredCapabilities = unordered.requiredCapabilities.reverse,
      requiredTargets = unordered.requiredTargets.reverse
    )
    val firstApi = must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(unordered)))
    val secondApi = must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(reordered)))
    assert(firstApi.apiHash == secondApi.apiHash)
    assert(java.util.Arrays.equals(
      must(DescriptorCodec.encodeApi(firstApi)),
      must(DescriptorCodec.encodeApi(secondApi))
    ))

    def orderedResult(arguments: Vector[AbiType]): ApiSymbolDefinition =
      definition("demo.declared", AbiType.Named("demo.Pair", arguments)).copy(
        parameterLists = Vector(ApiParameterList(arguments.zipWithIndex.map { case (tpe, index) =>
          ApiParameter(s"p$index", tpe)
        }))
      )
    val declaredFirst = must(DescriptorFactory.api(
      "ssc-control-v1",
      "demo",
      Vector(orderedResult(Vector(I32, I64)))
    ))
    val declaredSecond = must(DescriptorFactory.api(
      "ssc-control-v1",
      "demo",
      Vector(orderedResult(Vector(I64, I32)))
    ))
    assert(declaredFirst.apiHash != declaredSecond.apiHash)
    assert(declaredFirst.symbols.head.stableSymbolId != declaredSecond.symbols.head.stableSymbolId)

    val control = controlFixture()
    val reorderedControl = must(DescriptorFactory.controlSummary(
      control.controlAbiVersion,
      control.moduleId,
      control.apiHash,
      control.managedCallEdges.reverse,
      control.foreignCallEdges.reverse,
      control.tailEdges.reverse,
      control.saveSites.reverse,
      control.frameSchemas.reverse,
      control.captureBarriers.reverse
    ))
    assert(control.summaryDigest == reorderedControl.summaryDigest)

    val artifact = artifactFixture(control = control)
    val reorderedArtifact = must(DescriptorFactory.artifactManifest(
      artifact.controlAbiVersion,
      artifact.apiHash,
      artifact.target.copy(features = artifact.target.features.reverse),
      artifact.targetEntrypoints.reverse,
      artifact.programDigest,
      artifact.artifactDigest,
      artifact.runtimeVersion,
      artifact.dependencyManifest.reverse,
      artifact.controlSummaryDigests.reverse
    ))
    assert(artifact == reorderedArtifact)

  test("unsupported versions reject before hostile raw traversal and malformed identities reject stably"):
    var deep: AbiType = I32
    var index = 0
    while index < 10000 do
      deep = AbiType.Named("N", Vector(deep))
      index += 1
    val hostile = ApiDescriptor(
      "future",
      "ssc-control-v1",
      "demo",
      ApiHash(ZeroHash),
      Vector(ApiSymbol(
        StableSymbolId("ssc:symbol:v1:" + ZeroHash),
        None,
        ApiSymbolDefinition("demo.deep", ApiSymbolKind.Type, resultType = deep)
      ))
    )
    assert(code(DescriptorValidator.api(hostile)) == "UNSUPPORTED_VERSION")

    val artifact = artifactFixture()
    val invalidImplementation = artifact.targetEntrypoints.collectFirst {
      case TargetEntrypoint.Jvm(value) => TargetEntrypoint.Jvm(value.copy(
        implementationDigest = ImplementationDigest("ABC")
      ))
    }.getOrElse(fail("missing JVM entrypoint"))
    assert(code(DescriptorValidator.artifact(artifact.copy(
      targetEntrypoints = invalidImplementation +: artifact.targetEntrypoints.tail
    ))) == "INVALID_SHA256")
