package scalascript.interop.descriptor

private[descriptor] object ApiWire:
  import WireSupport.*

  private val symbolKinds = Map(
    "Value" -> ApiSymbolKind.Value,
    "Function" -> ApiSymbolKind.Function,
    "Constructor" -> ApiSymbolKind.Constructor,
    "Type" -> ApiSymbolKind.Type,
    "Effect" -> ApiSymbolKind.Effect,
    "Operation" -> ApiSymbolKind.Operation,
    "Prompt" -> ApiSymbolKind.Prompt
  )
  private val parameterModes = Map(
    "Value" -> ParameterMode.Value,
    "Contextual" -> ParameterMode.Contextual,
    "Implicit" -> ParameterMode.Implicit,
    "ByName" -> ParameterMode.ByName,
    "Repeated" -> ParameterMode.Repeated
  )
  private val callingConventions = Map(
    "PureDirect" -> CallingConvention.PureDirect,
    "Effectful" -> CallingConvention.Effectful,
    "ManagedControl" -> CallingConvention.ManagedControl,
    "ForeignBarrier" -> CallingConvention.ForeignBarrier
  )
  private val invocationMultiplicities = Map(
    "AtMostOnce" -> InvocationMultiplicity.AtMostOnce,
    "Many" -> InvocationMultiplicity.Many,
    "Unknown" -> InvocationMultiplicity.Unknown
  )
  private val callbackEscapes = Map(
    "NoEscape" -> CallbackEscape.NoEscape,
    "MayEscape" -> CallbackEscape.MayEscape
  )
  private val callbackReentrancies = Map(
    "NonReentrant" -> CallbackReentrancy.NonReentrant,
    "Reentrant" -> CallbackReentrancy.Reentrant,
    "Unknown" -> CallbackReentrancy.Unknown
  )
  private val callbackConcurrencies = Map(
    "Serial" -> CallbackConcurrency.Serial,
    "Concurrent" -> CallbackConcurrency.Concurrent,
    "Unknown" -> CallbackConcurrency.Unknown
  )
  private val callbackCancellations = Map(
    "NotCancellable" -> CallbackCancellation.NotCancellable,
    "Cancellable" -> CallbackCancellation.Cancellable,
    "Unknown" -> CallbackCancellation.Unknown
  )
  private val promptRoles = Map(
    "Binder" -> PromptRole.Binder,
    "Parameter" -> PromptRole.Parameter,
    "Result" -> PromptRole.Result,
    "Shift" -> PromptRole.Shift,
    "Reset" -> PromptRole.Reset
  )
  private val promptGenerativities = Map(
    "Fresh" -> PromptGenerativity.Fresh,
    "External" -> PromptGenerativity.External
  )
  private val promptPortabilities = Map(
    "ClosedRegionOnly" -> PromptPortability.ClosedRegionOnly,
    "DurableCapability" -> PromptPortability.DurableCapability
  )
  private val resumeMultiplicities = Map(
    "OneShot" -> ResumeMultiplicity.OneShot,
    "Reusable" -> ResumeMultiplicity.Reusable
  )

  def writeApiHash(value: ApiHash): ujson.Value = writeWrappedString(value.value)
  def readApiHash(value: ujson.Value, path: String): Result[ApiHash] =
    wrappedString(value, path)(ApiHash.apply)

  def writeStableSymbolId(value: StableSymbolId): ujson.Value = writeWrappedString(value.value)
  def readStableSymbolId(value: ujson.Value, path: String): Result[StableSymbolId] =
    wrappedString(value, path)(StableSymbolId.apply)

  def writeOverloadId(value: OverloadId): ujson.Value = writeWrappedString(value.value)
  def readOverloadId(value: ujson.Value, path: String): Result[OverloadId] =
    wrappedString(value, path)(OverloadId.apply)

  def writeSymbolKind(value: ApiSymbolKind): ujson.Value = tagged(value.toString)
  def readSymbolKind(value: ujson.Value, path: String): Result[ApiSymbolKind] =
    enumValue(value, path, symbolKinds)

  def writeParameterMode(value: ParameterMode): ujson.Value = tagged(value.toString)
  def readParameterMode(value: ujson.Value, path: String): Result[ParameterMode] =
    enumValue(value, path, parameterModes)

  def writeParameter(value: ApiParameter): ujson.Value = obj(
    "name" -> str(value.name),
    "tpe" -> TypeWire.writeType(value.tpe),
    "mode" -> writeParameterMode(value.mode),
    "hasDefault" -> bool(value.hasDefault)
  )

  def readParameter(value: ujson.Value, path: String): Result[ApiParameter] =
    for
      fields <- exactObject(value, path, "name", "tpe", "mode", "hasDefault")
      name <- string(field(fields, "name"), s"$path.name")
      tpe <- TypeWire.readType(field(fields, "tpe"), s"$path.tpe")
      mode <- readParameterMode(field(fields, "mode"), s"$path.mode")
      hasDefault <- boolean(field(fields, "hasDefault"), s"$path.hasDefault")
    yield ApiParameter(name, tpe, mode, hasDefault)

  def writeParameterList(value: ApiParameterList): ujson.Value =
    obj("parameters" -> arr(value.parameters.map(writeParameter)))

  def readParameterList(value: ujson.Value, path: String): Result[ApiParameterList] =
    exactObject(value, path, "parameters").flatMap { fields =>
      vector(field(fields, "parameters"), s"$path.parameters")(readParameter)
        .map(ApiParameterList.apply)
    }

  def writeCallbackPath(value: CallbackParameterPath): ujson.Value = obj(
    "parameterListIndex" -> index(value.parameterListIndex),
    "parameterIndex" -> index(value.parameterIndex)
  )

  def readCallbackPath(value: ujson.Value, path: String): Result[CallbackParameterPath] =
    for
      fields <- exactObject(value, path, "parameterListIndex", "parameterIndex")
      listIndex <- int(field(fields, "parameterListIndex"), s"$path.parameterListIndex")
      parameterIndex <- int(field(fields, "parameterIndex"), s"$path.parameterIndex")
    yield CallbackParameterPath(listIndex, parameterIndex)

  def writeCallingConvention(value: CallingConvention): ujson.Value = tagged(value.toString)
  def readCallingConvention(value: ujson.Value, path: String): Result[CallingConvention] =
    enumValue(value, path, callingConventions)

  def writeInvocationMultiplicity(value: InvocationMultiplicity): ujson.Value = tagged(value.toString)
  def readInvocationMultiplicity(value: ujson.Value, path: String): Result[InvocationMultiplicity] =
    enumValue(value, path, invocationMultiplicities)

  def writeCallbackEscape(value: CallbackEscape): ujson.Value = tagged(value.toString)
  def readCallbackEscape(value: ujson.Value, path: String): Result[CallbackEscape] =
    enumValue(value, path, callbackEscapes)

  def writeCallbackReentrancy(value: CallbackReentrancy): ujson.Value = tagged(value.toString)
  def readCallbackReentrancy(value: ujson.Value, path: String): Result[CallbackReentrancy] =
    enumValue(value, path, callbackReentrancies)

  def writeCallbackConcurrency(value: CallbackConcurrency): ujson.Value = tagged(value.toString)
  def readCallbackConcurrency(value: ujson.Value, path: String): Result[CallbackConcurrency] =
    enumValue(value, path, callbackConcurrencies)

  def writeCallbackCancellation(value: CallbackCancellation): ujson.Value = tagged(value.toString)
  def readCallbackCancellation(value: ujson.Value, path: String): Result[CallbackCancellation] =
    enumValue(value, path, callbackCancellations)

  def writeThreadAffinity(value: ThreadAffinity): ujson.Value = value match
    case ThreadAffinity.AnyThread       => tagged("AnyThread")
    case ThreadAffinity.CallingThread   => tagged("CallingThread")
    case ThreadAffinity.EventLoop(name) => tagged("EventLoop", "profile" -> str(name))
    case ThreadAffinity.Actor(name)     => tagged("Actor", "profile" -> str(name))
    case ThreadAffinity.Named(name)     => tagged("Named", "profile" -> str(name))

  def readThreadAffinity(value: ujson.Value, path: String): Result[ThreadAffinity] = value match
    case objectValue: ujson.Obj => objectValue.value.get("tag") match
      case Some(ujson.Str("AnyThread")) => tag(value, path).map(_ => ThreadAffinity.AnyThread)
      case Some(ujson.Str("CallingThread")) => tag(value, path).map(_ => ThreadAffinity.CallingThread)
      case Some(ujson.Str(caseName @ ("EventLoop" | "Actor" | "Named"))) =>
        for
          taggedValue <- tag(value, path, "profile")
          (fields, _) = taggedValue
          profile <- string(field(fields, "profile"), s"$path.profile")
        yield caseName match
          case "EventLoop" => ThreadAffinity.EventLoop(profile)
          case "Actor"     => ThreadAffinity.Actor(profile)
          case _           => ThreadAffinity.Named(profile)
      case Some(ujson.Str(other)) => schema(s"$path.tag", s"unsupported tag $other")
      case Some(_)                => schema(s"$path.tag", "expected JSON string")
      case None                   => schema(path, "missing tag field")
    case _ => schema(path, "expected tagged thread-affinity object")

  def writeCallbackPolicy(value: CallbackPolicy): ujson.Value = obj(
    "parameter" -> writeCallbackPath(value.parameter),
    "callingConvention" -> writeCallingConvention(value.callingConvention),
    "invocationMultiplicity" -> writeInvocationMultiplicity(value.invocationMultiplicity),
    "escape" -> writeCallbackEscape(value.escape),
    "reentrancy" -> writeCallbackReentrancy(value.reentrancy),
    "concurrency" -> writeCallbackConcurrency(value.concurrency),
    "cancellation" -> writeCallbackCancellation(value.cancellation),
    "threadAffinity" -> writeThreadAffinity(value.threadAffinity)
  )

  def readCallbackPolicy(value: ujson.Value, path: String): Result[CallbackPolicy] =
    for
      fields <- exactObject(
        value,
        path,
        "parameter",
        "callingConvention",
        "invocationMultiplicity",
        "escape",
        "reentrancy",
        "concurrency",
        "cancellation",
        "threadAffinity"
      )
      parameter <- readCallbackPath(field(fields, "parameter"), s"$path.parameter")
      convention <- readCallingConvention(field(fields, "callingConvention"), s"$path.callingConvention")
      multiplicity <- readInvocationMultiplicity(
        field(fields, "invocationMultiplicity"),
        s"$path.invocationMultiplicity"
      )
      escape <- readCallbackEscape(field(fields, "escape"), s"$path.escape")
      reentrancy <- readCallbackReentrancy(field(fields, "reentrancy"), s"$path.reentrancy")
      concurrency <- readCallbackConcurrency(field(fields, "concurrency"), s"$path.concurrency")
      cancellation <- readCallbackCancellation(field(fields, "cancellation"), s"$path.cancellation")
      affinity <- readThreadAffinity(field(fields, "threadAffinity"), s"$path.threadAffinity")
    yield CallbackPolicy(
      parameter,
      convention,
      multiplicity,
      escape,
      reentrancy,
      concurrency,
      cancellation,
      affinity
    )

  def writePromptRole(value: PromptRole): ujson.Value = tagged(value.toString)
  def readPromptRole(value: ujson.Value, path: String): Result[PromptRole] =
    enumValue(value, path, promptRoles)

  def writePromptGenerativity(value: PromptGenerativity): ujson.Value = tagged(value.toString)
  def readPromptGenerativity(value: ujson.Value, path: String): Result[PromptGenerativity] =
    enumValue(value, path, promptGenerativities)

  def writePromptPortability(value: PromptPortability): ujson.Value = tagged(value.toString)
  def readPromptPortability(value: ujson.Value, path: String): Result[PromptPortability] =
    enumValue(value, path, promptPortabilities)

  def writePromptMetadata(value: PromptMetadata): ujson.Value = obj(
    "stablePromptId" -> str(value.stablePromptId),
    "role" -> writePromptRole(value.role),
    "answerType" -> TypeWire.writeType(value.answerType),
    "generativity" -> writePromptGenerativity(value.generativity),
    "portability" -> writePromptPortability(value.portability)
  )

  def readPromptMetadata(value: ujson.Value, path: String): Result[PromptMetadata] =
    for
      fields <- exactObject(
        value,
        path,
        "stablePromptId",
        "role",
        "answerType",
        "generativity",
        "portability"
      )
      stablePromptId <- string(field(fields, "stablePromptId"), s"$path.stablePromptId")
      role <- readPromptRole(field(fields, "role"), s"$path.role")
      answerType <- TypeWire.readType(field(fields, "answerType"), s"$path.answerType")
      generativity <- readPromptGenerativity(field(fields, "generativity"), s"$path.generativity")
      portability <- readPromptPortability(field(fields, "portability"), s"$path.portability")
    yield PromptMetadata(stablePromptId, role, answerType, generativity, portability)

  def writePromptAndControl(value: PromptAndControlMetadata): ujson.Value = obj(
    "prompts" -> arr(value.prompts.map(writePromptMetadata)),
    "capturesContinuation" -> bool(value.capturesContinuation),
    "exposesContinuation" -> bool(value.exposesContinuation),
    "answerTypeModification" -> bool(value.answerTypeModification)
  )

  def readPromptAndControl(value: ujson.Value, path: String): Result[PromptAndControlMetadata] =
    for
      fields <- exactObject(
        value,
        path,
        "prompts",
        "capturesContinuation",
        "exposesContinuation",
        "answerTypeModification"
      )
      prompts <- vector(field(fields, "prompts"), s"$path.prompts")(readPromptMetadata)
      captures <- boolean(field(fields, "capturesContinuation"), s"$path.capturesContinuation")
      exposes <- boolean(field(fields, "exposesContinuation"), s"$path.exposesContinuation")
      answerModification <- boolean(
        field(fields, "answerTypeModification"),
        s"$path.answerTypeModification"
      )
    yield PromptAndControlMetadata(prompts, captures, exposes, answerModification)

  def writeResumeMultiplicity(value: ResumeMultiplicity): ujson.Value = tagged(value.toString)
  def readResumeMultiplicity(value: ujson.Value, path: String): Result[ResumeMultiplicity] =
    enumValue(value, path, resumeMultiplicities)

  def writeDefinition(value: ApiSymbolDefinition): ujson.Value = obj(
    "qualifiedName" -> str(value.qualifiedName),
    "kind" -> writeSymbolKind(value.kind),
    "typeParameters" -> arr(value.typeParameters.map(TypeWire.writeTypeParameter)),
    "parameterLists" -> arr(value.parameterLists.map(writeParameterList)),
    "resultType" -> TypeWire.writeType(value.resultType),
    "effectRow" -> TypeWire.writeEffectRow(value.effectRow),
    "operationResumeMultiplicity" -> optional(value.operationResumeMultiplicity)(writeResumeMultiplicity),
    "callbackPolicies" -> arr(value.callbackPolicies.map(writeCallbackPolicy)),
    "promptAndControlMetadata" -> writePromptAndControl(value.promptAndControlMetadata),
    "requiredCapabilities" -> arr(value.requiredCapabilities.map(str)),
    "requiredTargets" -> arr(value.requiredTargets.map(str))
  )

  def readDefinition(value: ujson.Value, path: String): Result[ApiSymbolDefinition] =
    for
      fields <- exactObject(
        value,
        path,
        "qualifiedName",
        "kind",
        "typeParameters",
        "parameterLists",
        "resultType",
        "effectRow",
        "operationResumeMultiplicity",
        "callbackPolicies",
        "promptAndControlMetadata",
        "requiredCapabilities",
        "requiredTargets"
      )
      qualifiedName <- string(field(fields, "qualifiedName"), s"$path.qualifiedName")
      kind <- readSymbolKind(field(fields, "kind"), s"$path.kind")
      typeParameters <- vector(field(fields, "typeParameters"), s"$path.typeParameters")(
        TypeWire.readTypeParameter
      )
      parameterLists <- vector(field(fields, "parameterLists"), s"$path.parameterLists")(
        readParameterList
      )
      resultType <- TypeWire.readType(field(fields, "resultType"), s"$path.resultType")
      effectRow <- TypeWire.readEffectRow(field(fields, "effectRow"), s"$path.effectRow")
      resumeMultiplicity <- option(
        field(fields, "operationResumeMultiplicity"),
        s"$path.operationResumeMultiplicity"
      )(readResumeMultiplicity)
      callbacks <- vector(field(fields, "callbackPolicies"), s"$path.callbackPolicies")(
        readCallbackPolicy
      )
      promptAndControl <- readPromptAndControl(
        field(fields, "promptAndControlMetadata"),
        s"$path.promptAndControlMetadata"
      )
      capabilities <- vector(field(fields, "requiredCapabilities"), s"$path.requiredCapabilities")(
        (item, itemPath) => string(item, itemPath)
      )
      targets <- vector(field(fields, "requiredTargets"), s"$path.requiredTargets")(
        (item, itemPath) => string(item, itemPath)
      )
    yield ApiSymbolDefinition(
      qualifiedName,
      kind,
      typeParameters,
      parameterLists,
      resultType,
      effectRow,
      resumeMultiplicity,
      callbacks,
      promptAndControl,
      capabilities,
      targets
    )

  def writeSymbol(value: ApiSymbol): ujson.Value = obj(
    "stableSymbolId" -> writeStableSymbolId(value.stableSymbolId),
    "overloadId" -> optional(value.overloadId)(writeOverloadId),
    "definition" -> writeDefinition(value.definition)
  )

  def readSymbol(value: ujson.Value, path: String): Result[ApiSymbol] =
    for
      fields <- exactObject(value, path, "stableSymbolId", "overloadId", "definition")
      symbolId <- readStableSymbolId(field(fields, "stableSymbolId"), s"$path.stableSymbolId")
      overloadId <- option(field(fields, "overloadId"), s"$path.overloadId")(readOverloadId)
      definition <- readDefinition(field(fields, "definition"), s"$path.definition")
    yield ApiSymbol(symbolId, overloadId, definition)

  def writeApi(value: ApiDescriptor, includeHash: Boolean = true): ujson.Value =
    val fields = Vector(
      "schemaVersion" -> str(value.schemaVersion),
      "controlAbiVersion" -> str(value.controlAbiVersion),
      "moduleId" -> str(value.moduleId),
      "symbols" -> arr(value.symbols.map(writeSymbol))
    )
    if includeHash then obj((fields :+ ("apiHash" -> writeApiHash(value.apiHash)))* )
    else obj(fields*)

  def readApi(value: ujson.Value, path: String = "$" ): Result[ApiDescriptor] =
    for
      fields <- exactObject(
        value,
        path,
        "schemaVersion",
        "controlAbiVersion",
        "moduleId",
        "apiHash",
        "symbols"
      )
      schemaVersion <- string(field(fields, "schemaVersion"), s"$path.schemaVersion")
      controlAbiVersion <- string(field(fields, "controlAbiVersion"), s"$path.controlAbiVersion")
      moduleId <- string(field(fields, "moduleId"), s"$path.moduleId")
      apiHash <- readApiHash(field(fields, "apiHash"), s"$path.apiHash")
      symbols <- vector(field(fields, "symbols"), s"$path.symbols")(readSymbol)
    yield ApiDescriptor(schemaVersion, controlAbiVersion, moduleId, apiHash, symbols)

  def writeSymbolIdentity(moduleId: String, definition: ApiSymbolDefinition): ujson.Value =
    obj(
      "moduleId" -> str(moduleId),
      "callable" -> writeCallableIdentity(definition)
    )

  def identityDefinition(value: ApiSymbolDefinition): ApiSymbolDefinition =
    value.copy(
      typeParameters = value.typeParameters.map { parameter =>
        parameter.copy(
          lowerBound = parameter.lowerBound.map(TypeWire.identityType),
          upperBound = parameter.upperBound.map(TypeWire.identityType)
        )
      },
      parameterLists = value.parameterLists.map { list =>
        list.copy(parameters = list.parameters.map { parameter =>
          parameter.copy(tpe = TypeWire.identityType(parameter.tpe))
        })
      },
      resultType = TypeWire.identityType(value.resultType),
      effectRow = value.effectRow.copy(members = value.effectRow.members.map { member =>
        member.copy(typeArguments = member.typeArguments.map(TypeWire.identityType))
      }),
      promptAndControlMetadata = value.promptAndControlMetadata.copy(
        prompts = value.promptAndControlMetadata.prompts.map { prompt =>
          prompt.copy(answerType = TypeWire.identityType(prompt.answerType))
        }
      )
    )

  private def writeCallableIdentity(value: ApiSymbolDefinition): ujson.Value = obj(
    "qualifiedName" -> str(value.qualifiedName),
    "kind" -> writeSymbolKind(value.kind),
    "typeParameters" -> arr(value.typeParameters.map { parameter =>
      obj(
        "index" -> index(parameter.index),
        "variance" -> TypeWire.writeVariance(parameter.variance),
        "kindArity" -> index(parameter.kindArity),
        "lowerBound" -> optional(parameter.lowerBound)(TypeWire.writeIdentityType),
        "upperBound" -> optional(parameter.upperBound)(TypeWire.writeIdentityType)
      )
    }),
    "parameterLists" -> arr(value.parameterLists.map { list =>
      arr(list.parameters.map { parameter =>
        obj(
          "tpe" -> TypeWire.writeIdentityType(parameter.tpe),
          "mode" -> writeParameterMode(parameter.mode)
        )
      })
    }),
    "resultType" -> TypeWire.writeIdentityType(value.resultType),
    "effectRow" -> TypeWire.writeIdentityEffectRow(value.effectRow),
    "operationResumeMultiplicity" -> optional(value.operationResumeMultiplicity)(writeResumeMultiplicity),
    "callbackPolicies" -> arr(value.callbackPolicies.map(writeCallbackPolicy)),
    "promptAndControlMetadata" -> writePromptAndControl(value.promptAndControlMetadata)
  )
