package scalascript.interop.descriptor

private[descriptor] object DescriptorNormalization:

  def abiType(value: AbiType): AbiType = value match
    case AbiType.Primitive(_) | AbiType.TypeParameter(_) => value
    case AbiType.Named(id, arguments) =>
      AbiType.Named(id, arguments.map(abiType))
    case AbiType.Tuple(elements) =>
      AbiType.Tuple(elements.map(abiType))
    case AbiType.Function(parameterLists, result, effects) =>
      AbiType.Function(
        parameterLists.map(_.map(abiType)),
        abiType(result),
        effectRow(effects)
      )
    case AbiType.Union(alternatives) =>
      AbiType.Union(setLike(alternatives.map(abiType))(TypeWire.writeType))
    case AbiType.Intersection(parts) =>
      AbiType.Intersection(setLike(parts.map(abiType))(TypeWire.writeType))
    case AbiType.TypeLambda(parameters, body) =>
      AbiType.TypeLambda(parameters.map(typeParameter), abiType(body))

  def typeParameter(value: AbiTypeParameter): AbiTypeParameter =
    value.copy(
      lowerBound = value.lowerBound.map(abiType),
      upperBound = value.upperBound.map(abiType)
    )

  def effectRow(value: EffectRow): EffectRow =
    value.copy(members = setLike(value.members.map { member =>
      member.copy(typeArguments = member.typeArguments.map(abiType))
    })(TypeWire.writeEffectRef))

  def symbolDefinition(value: ApiSymbolDefinition): ApiSymbolDefinition =
    value.copy(
      typeParameters = value.typeParameters.map(typeParameter),
      parameterLists = value.parameterLists.map { list =>
        list.copy(parameters = list.parameters.map(p => p.copy(tpe = abiType(p.tpe))))
      },
      resultType = abiType(value.resultType),
      effectRow = effectRow(value.effectRow),
      callbackPolicies = setLike(value.callbackPolicies)(ApiWire.writeCallbackPolicy),
      promptAndControlMetadata = value.promptAndControlMetadata.copy(
        prompts = setLike(value.promptAndControlMetadata.prompts.map { prompt =>
          prompt.copy(answerType = abiType(prompt.answerType))
        })(ApiWire.writePromptMetadata)
      ),
      requiredCapabilities = strings(value.requiredCapabilities),
      requiredTargets = strings(value.requiredTargets)
    )

  def api(value: ApiDescriptor): ApiDescriptor =
    value.copy(symbols = setLike(value.symbols.map { symbol =>
      symbol.copy(definition = symbolDefinition(symbol.definition))
    })(ApiWire.writeSymbol))

  def frameSchema(value: FrameSchema): FrameSchema =
    value.copy(
      typeParameters = value.typeParameters.map(typeParameter),
      slots = value.slots.map(slot => slot.copy(tpe = abiType(slot.tpe)))
    )

  def control(value: ControlSummary): ControlSummary =
    value.copy(
      managedCallEdges = setLike(value.managedCallEdges)(ControlWire.writeManagedEdge),
      foreignCallEdges = setLike(value.foreignCallEdges)(ControlWire.writeForeignEdge),
      tailEdges = setLike(value.tailEdges)(ControlWire.writeTailEdge),
      saveSites = setLike(value.saveSites.map { site =>
        site.copy(stablePromptIds = strings(site.stablePromptIds))
      })(ControlWire.writeSaveSite),
      frameSchemas = setLike(value.frameSchemas.map(frameSchema))(ControlWire.writeFrameSchema),
      captureBarriers = setLike(value.captureBarriers)(ControlWire.writeBarrier)
    )

  def dependency(value: DependencyBinding): DependencyBinding =
    value.copy(requiredCapabilities = strings(value.requiredCapabilities))

  def dependencies(values: Vector[DependencyBinding]): Vector[DependencyBinding] =
    setLike(values.map(dependency))(ArtifactWire.writeDependency)

  def targetEntrypoint(value: TargetEntrypoint): TargetEntrypoint = value match
    case TargetEntrypoint.Jvm(entry) =>
      TargetEntrypoint.Jvm(entry.copy(bridgeFlags = strings(entry.bridgeFlags)))
    case TargetEntrypoint.Named(_) => value

  def artifact(value: ArtifactManifest): ArtifactManifest =
    value.copy(
      target = value.target.copy(features = strings(value.target.features)),
      targetEntrypoints = setLike(value.targetEntrypoints.map(targetEntrypoint))(
        ArtifactWire.writeTargetEntrypoint
      ),
      dependencyManifest = dependencies(value.dependencyManifest),
      controlSummaryDigests = setLike(value.controlSummaryDigests)(ControlWire.writeSummaryDigest)
    )

  def strings(values: Vector[String]): Vector[String] =
    setLike(values)(ujson.Str.apply)

  private def setLike[A](values: Vector[A])(encode: A => ujson.Value): Vector[A] =
    values.distinctBy(value => wireKey(encode(value))).sortBy(value => wireKey(encode(value)))

  /**
   * Hex preserves unsigned UTF-8 byte order while remaining comparable with the
   * same Scala `String` ordering on every host. The fallback is deterministic
   * only to let normalization reach validation; public encoding rejects the
   * invalid Unicode which made restricted-JCS rendering fail.
   */
  private def wireKey(value: ujson.Value): String =
    Jcs.bytes(value) match
      case Right(bytes) => "0:" + bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString
      case Left(_)      => "1:" + value.render()
