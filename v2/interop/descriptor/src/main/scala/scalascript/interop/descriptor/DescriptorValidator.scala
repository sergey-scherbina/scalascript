package scalascript.interop.descriptor

object DescriptorValidator:
  private val Hex64 = "[0-9a-f]{64}".r

  private[descriptor] def apiVersion(value: ApiDescriptor): Either[DescriptorError, Unit] =
    exactVersion("$.schemaVersion", value.schemaVersion, DescriptorVersions.ApiDescriptor)

  private[descriptor] def controlSummaryVersion(value: ControlSummary): Either[DescriptorError, Unit] =
    exactVersion("$.schemaVersion", value.schemaVersion, DescriptorVersions.ControlSummary)

  private[descriptor] def artifactVersion(value: ArtifactManifest): Either[DescriptorError, Unit] =
    exactVersion(
      "$.artifactManifestVersion",
      value.artifactManifestVersion,
      DescriptorVersions.ArtifactManifest
    )

  private[descriptor] def definitionRaw(
      value: ApiSymbolDefinition,
      path: String = "$.definition"
  ): Either[DescriptorError, Unit] =
    DescriptorPreflight.definition(value, path).flatMap(_ => validateRawDefinition(value, path))

  private[descriptor] def dependenciesRaw(
      values: Vector[DependencyBinding],
      path: String = "$.dependencyManifest"
  ): Either[DescriptorError, Unit] =
    for
      _ <- DescriptorPreflight.dependencies(values, path)
      _ <- uniqueValues(
        path,
        values.map(binding => (binding.kind, binding.logicalId, binding.target)),
        "DUPLICATE_DEPENDENCY"
      )
      _ <- validateAll(values.zipWithIndex) { case (binding, index) =>
        for
          _ <- unique(
            s"$path[$index].requiredCapabilities",
            binding.requiredCapabilities,
            "DUPLICATE_REQUIRED_CAPABILITY"
          )
          _ <- validateDependency(binding, s"$path[$index]")
        yield ()
      }
    yield ()

  private[descriptor] def bridgeFlagsRaw(
      values: Vector[String],
      path: String = "$.bridgeFlags"
  ): Either[DescriptorError, Unit] =
    for
      _ <- DescriptorPreflight.stringVector(path, values)
      _ <- unique(path, values, "DUPLICATE_BRIDGE_FLAG")
      _ <- validateAll(values.zipWithIndex) { case (flag, index) =>
        nonEmpty(s"$path[$index]", flag)
      }
    yield ()

  private[descriptor] def targetEntrypoint(
      value: TargetEntrypoint,
      path: String = "$"
  ): Either[DescriptorError, TargetEntrypoint] =
    for
      _ <- value match
        case TargetEntrypoint.Jvm(entry) =>
          bridgeFlagsRaw(entry.bridgeFlags, s"$path.value.bridgeFlags")
        case TargetEntrypoint.Named(_) => Right(())
      normalized = DescriptorNormalization.targetEntrypoint(value)
      _ <- validateEntrypoint(normalized, path)
    yield normalized

  def api(value: ApiDescriptor): Either[DescriptorError, ApiDescriptor] =
    for
      _ <- apiVersion(value)
      _ <- apiRaw(value)
      normalized = DescriptorNormalization.api(value)
      _ <- nonEmpty("$.controlAbiVersion", normalized.controlAbiVersion)
      _ <- nonEmpty("$.moduleId", normalized.moduleId)
      _ <- digest("$.apiHash", normalized.apiHash.value)
      _ <- unique(
        "$.symbols",
        normalized.symbols.map(_.stableSymbolId.value),
        "DUPLICATE_SYMBOL_ID"
      )
      _ <- validateAll(normalized.symbols.zipWithIndex) { case (symbol, index) =>
        validateSymbol(normalized.moduleId, symbol, s"$$.symbols[$index]")
      }
      expected <- DescriptorHashes.apiHash(normalized)
      _ <- equal(
        "$.apiHash",
        normalized.apiHash.value,
        expected.value,
        "API_HASH_MISMATCH"
      )
    yield normalized

  def controlSummary(value: ControlSummary): Either[DescriptorError, ControlSummary] =
    for
      _ <- controlSummaryVersion(value)
      _ <- controlSummaryRaw(value)
      normalized = DescriptorNormalization.control(value)
      _ <- nonEmpty("$.controlAbiVersion", normalized.controlAbiVersion)
      _ <- nonEmpty("$.moduleId", normalized.moduleId)
      _ <- digest("$.apiHash", normalized.apiHash.value)
      _ <- digest("$.summaryDigest", normalized.summaryDigest.value)
      _ <- unique("$.frameSchemas", normalized.frameSchemas.map(_.schemaId), "DUPLICATE_FRAME_SCHEMA")
      _ <- unique("$.saveSites", normalized.saveSites.map(_.siteId), "DUPLICATE_SAVE_SITE")
      _ <- uniqueValues(
        "$.managedCallEdges",
        normalized.managedCallEdges.map(edge => edge.caller -> edge.callee),
        "DUPLICATE_MANAGED_EDGE"
      )
      _ <- uniqueValues(
        "$.foreignCallEdges",
        normalized.foreignCallEdges.map(edge => edge.caller -> edge.target),
        "DUPLICATE_FOREIGN_EDGE"
      )
      _ <- uniqueValues(
        "$.tailEdges",
        normalized.tailEdges.map(edge => edge.caller -> edge.callee),
        "DUPLICATE_TAIL_EDGE"
      )
      _ <- validateAll(normalized.managedCallEdges.zipWithIndex) { case (edge, index) =>
        for
          _ <- symbolId(s"$$.managedCallEdges[$index].caller", edge.caller)
          _ <- symbolId(s"$$.managedCallEdges[$index].callee", edge.callee)
        yield ()
      }
      _ <- validateAll(normalized.foreignCallEdges.zipWithIndex) { case (edge, index) =>
        for
          _ <- symbolId(s"$$.foreignCallEdges[$index].caller", edge.caller)
          _ <- nonEmpty(s"$$.foreignCallEdges[$index].target.profile", edge.target.profile)
          _ <- nonEmpty(s"$$.foreignCallEdges[$index].target.owner", edge.target.owner)
          _ <- nonEmpty(s"$$.foreignCallEdges[$index].target.name", edge.target.name)
          _ <- edge.target.descriptor
            .map(nonEmpty(s"$$.foreignCallEdges[$index].target.descriptor", _))
            .getOrElse(Right(()))
        yield ()
      }
      _ <- validateAll(normalized.tailEdges.zipWithIndex) { case (edge, index) =>
        for
          _ <- symbolId(s"$$.tailEdges[$index].caller", edge.caller)
          _ <- symbolId(s"$$.tailEdges[$index].callee", edge.callee)
          _ <- edge.disposition match
            case TailDisposition.Eligible      => Right(())
            case TailDisposition.Barrier(code) => nonEmpty(s"$$.tailEdges[$index].disposition.code", code)
        yield ()
      }
      _ <- validateAll(normalized.frameSchemas.zipWithIndex) { case (schema, index) =>
        validateFrameSchema(schema, s"$$.frameSchemas[$index]")
      }
      _ <- validateAll(normalized.saveSites.zipWithIndex) { case (site, index) =>
        validateSaveSite(site, s"$$.saveSites[$index]")
      }
      _ <- validateAll(normalized.captureBarriers.zipWithIndex) { case (barrier, index) =>
        validateBarrier(barrier, s"$$.captureBarriers[$index]")
      }
      expected <- DescriptorHashes.controlSummaryDigest(normalized)
      _ <- equal(
        "$.summaryDigest",
        normalized.summaryDigest.value,
        expected.value,
        "CONTROL_SUMMARY_DIGEST_MISMATCH"
      )
    yield normalized

  def artifact(value: ArtifactManifest): Either[DescriptorError, ArtifactManifest] =
    for
      _ <- artifactVersion(value)
      _ <- artifactRaw(value)
      normalized = DescriptorNormalization.artifact(value)
      _ <- nonEmpty("$.controlAbiVersion", normalized.controlAbiVersion)
      _ <- digest("$.apiHash", normalized.apiHash.value)
      _ <- nonEmpty("$.target.id", normalized.target.id)
      _ <- nonEmpty("$.target.abi", normalized.target.abi)
      _ <- validateAll(normalized.target.features.zipWithIndex) { case (feature, index) =>
        nonEmpty(s"$$.target.features[$index]", feature)
      }
      _ <- nonEmpty("$.runtimeVersion", normalized.runtimeVersion)
      _ <- optionalDigest("$.programDigest", normalized.programDigest.map(_.value))
      _ <- optionalDigest("$.artifactDigest", normalized.artifactDigest.map(_.value))
      _ <- digest("$.dependencyProfileDigest", normalized.dependencyProfileDigest.value)
      _ <- validateAll(normalized.controlSummaryDigests.zipWithIndex) { case (value, index) =>
        digest(s"$$.controlSummaryDigests[$index]", value.value)
      }
      _ <- unique(
        "$.targetEntrypoints",
        normalized.targetEntrypoints.map(entrypointIdOf),
        "DUPLICATE_ENTRYPOINT"
      )
      _ <- validateAll(normalized.targetEntrypoints.zipWithIndex) { case (entrypoint, index) =>
        validateEntrypoint(entrypoint, s"$$.targetEntrypoints[$index]")
      }
      _ <- uniqueValues(
        "$.dependencyManifest",
        normalized.dependencyManifest.map(binding => (binding.kind, binding.logicalId, binding.target)),
        "DUPLICATE_DEPENDENCY"
      )
      _ <- validateAll(normalized.dependencyManifest.zipWithIndex) { case (dependency, index) =>
        validateDependency(dependency, s"$$.dependencyManifest[$index]")
      }
      expected <- DescriptorHashes.dependencyProfileDigest(normalized.dependencyManifest)
      _ <- equal(
        "$.dependencyProfileDigest",
        normalized.dependencyProfileDigest.value,
        expected.value,
        "DEPENDENCY_PROFILE_DIGEST_MISMATCH"
      )
    yield normalized

  private[descriptor] def apiRaw(value: ApiDescriptor): Either[DescriptorError, Unit] =
    for
      _ <- DescriptorPreflight.api(value)
      _ <- unique(
        "$.symbols",
        value.symbols.map(_.stableSymbolId.value),
        "DUPLICATE_SYMBOL_ID"
      )
      _ <- validateAll(value.symbols.zipWithIndex) { case (symbol, symbolIndex) =>
        validateRawDefinition(symbol.definition, s"$$.symbols[$symbolIndex].definition")
      }
    yield ()

  private[descriptor] def controlSummaryRaw(value: ControlSummary): Either[DescriptorError, Unit] =
    val frameIds = value.frameSchemas.map(_.schemaId)
    for
      _ <- DescriptorPreflight.control(value)
      _ <- unique("$.frameSchemas", frameIds, "DUPLICATE_FRAME_SCHEMA")
      _ <- unique("$.saveSites", value.saveSites.map(_.siteId), "DUPLICATE_SAVE_SITE")
      _ <- uniqueValues(
        "$.managedCallEdges",
        value.managedCallEdges.map(edge => edge.caller -> edge.callee),
        "DUPLICATE_MANAGED_EDGE"
      )
      _ <- uniqueValues(
        "$.foreignCallEdges",
        value.foreignCallEdges.map(edge => edge.caller -> edge.target),
        "DUPLICATE_FOREIGN_EDGE"
      )
      _ <- uniqueValues(
        "$.tailEdges",
        value.tailEdges.map(edge => edge.caller -> edge.callee),
        "DUPLICATE_TAIL_EDGE"
      )
      _ <- uniqueValues(
        "$.captureBarriers",
        value.captureBarriers.map(barrier => barrier.siteId -> barrier.owner),
        "DUPLICATE_CAPTURE_BARRIER"
      )
      _ <- validateAll(value.frameSchemas.zipWithIndex) { case (schema, schemaIndex) =>
        validateFrameSchema(schema, s"$$.frameSchemas[$schemaIndex]")
      }
      _ <- validateAll(value.saveSites.zipWithIndex) { case (site, siteIndex) =>
        for
          _ <- unique(
            s"$$.saveSites[$siteIndex].stablePromptIds",
            site.stablePromptIds,
            "DUPLICATE_PROMPT_ID"
          )
          _ <- if frameIds.contains(site.frameSchemaId) then Right(())
          else Left(DescriptorError(
            "UNKNOWN_FRAME_SCHEMA",
            s"$$.saveSites[$siteIndex].frameSchemaId",
            s"no frame schema ${site.frameSchemaId} exists in this summary"
          ))
        yield ()
      }
    yield ()

  private[descriptor] def artifactRaw(value: ArtifactManifest): Either[DescriptorError, Unit] =
    for
      _ <- DescriptorPreflight.artifact(value)
      _ <- unique("$.target.features", value.target.features, "DUPLICATE_TARGET_FEATURE")
      _ <- unique(
        "$.targetEntrypoints",
        value.targetEntrypoints.map(entrypointIdOf),
        "DUPLICATE_ENTRYPOINT"
      )
      _ <- uniqueValues(
        "$.dependencyManifest",
        value.dependencyManifest.map(binding => (binding.kind, binding.logicalId, binding.target)),
        "DUPLICATE_DEPENDENCY"
      )
      _ <- unique(
        "$.controlSummaryDigests",
        value.controlSummaryDigests.map(_.value),
        "DUPLICATE_CONTROL_SUMMARY_DIGEST"
      )
      _ <- validateAll(value.targetEntrypoints.zipWithIndex) { case (entrypoint, index) =>
        entrypoint match
          case TargetEntrypoint.Jvm(jvm) =>
            unique(
              s"$$.targetEntrypoints[$index].value.bridgeFlags",
              jvm.bridgeFlags,
              "DUPLICATE_BRIDGE_FLAG"
            )
          case TargetEntrypoint.Named(_) => Right(())
      }
      _ <- validateAll(value.dependencyManifest.zipWithIndex) { case (binding, index) =>
        unique(
          s"$$.dependencyManifest[$index].requiredCapabilities",
          binding.requiredCapabilities,
          "DUPLICATE_REQUIRED_CAPABILITY"
        )
      }
    yield ()

  private def validateRawDefinition(
      definition: ApiSymbolDefinition,
      path: String
  ): Either[DescriptorError, Unit] =
    val binders = Vector(definition.typeParameters)
    for
      _ <- validateTypeParameters(definition.typeParameters, s"$path.typeParameters", Vector.empty)
      _ <- validateAll(definition.parameterLists.zipWithIndex) { case (list, listIndex) =>
        validateAll(list.parameters.zipWithIndex) { case (parameter, parameterIndex) =>
          validateType(
            parameter.tpe,
            s"$path.parameterLists[$listIndex].parameters[$parameterIndex].tpe",
            binders
          )
        }
      }
      _ <- validateType(definition.resultType, s"$path.resultType", binders)
      _ <- validateEffectRow(definition.effectRow, s"$path.effectRow", binders)
      _ <- uniqueValues(
        s"$path.callbackPolicies",
        definition.callbackPolicies.map(_.parameter),
        "DUPLICATE_CALLBACK_POLICY"
      )
      _ <- validateAll(definition.callbackPolicies.zipWithIndex) { case (policy, index) =>
        validateCallbackPolicy(definition, policy, s"$path.callbackPolicies[$index]")
      }
      _ <- uniqueValues(
        s"$path.promptAndControlMetadata.prompts",
        definition.promptAndControlMetadata.prompts.map(prompt => prompt.stablePromptId -> prompt.role),
        "DUPLICATE_PROMPT_METADATA"
      )
      _ <- validateAll(definition.promptAndControlMetadata.prompts.zipWithIndex) { case (prompt, index) =>
        validateType(
          prompt.answerType,
          s"$path.promptAndControlMetadata.prompts[$index].answerType",
          binders
        )
      }
      _ <- unique(
        s"$path.requiredCapabilities",
        definition.requiredCapabilities,
        "DUPLICATE_REQUIRED_CAPABILITY"
      )
      _ <- unique(s"$path.requiredTargets", definition.requiredTargets, "DUPLICATE_REQUIRED_TARGET")
    yield ()

  private def validateSymbol(
      moduleId: String,
      symbol: ApiSymbol,
      path: String
  ): Either[DescriptorError, Unit] =
    val definition = symbol.definition
    for
      _ <- symbolId(s"$path.stableSymbolId", symbol.stableSymbolId)
      _ <- nonEmpty(s"$path.definition.qualifiedName", definition.qualifiedName)
      _ <- validateTypeParameters(
        definition.typeParameters,
        s"$path.definition.typeParameters",
        Vector.empty
      )
      binders = Vector(definition.typeParameters)
      _ <- validateAll(definition.parameterLists.zipWithIndex) { case (list, listIndex) =>
        validateAll(list.parameters.zipWithIndex) { case (parameter, parameterIndex) =>
          for
            _ <- nonEmpty(
              s"$path.definition.parameterLists[$listIndex].parameters[$parameterIndex].name",
              parameter.name
            )
            _ <- validateType(
              parameter.tpe,
              s"$path.definition.parameterLists[$listIndex].parameters[$parameterIndex].tpe",
              binders
            )
          yield ()
        }
      }
      _ <- validateType(definition.resultType, s"$path.definition.resultType", binders)
      _ <- validateEffectRow(definition.effectRow, s"$path.definition.effectRow", binders)
      _ <- validateAll(definition.requiredCapabilities.zipWithIndex) { case (capability, index) =>
        nonEmpty(s"$path.definition.requiredCapabilities[$index]", capability)
      }
      _ <- validateAll(definition.requiredTargets.zipWithIndex) { case (target, index) =>
        nonEmpty(s"$path.definition.requiredTargets[$index]", target)
      }
      _ <- definition.kind match
        case ApiSymbolKind.Operation if definition.operationResumeMultiplicity.isEmpty =>
          Left(DescriptorError(
            "MISSING_RESUME_MULTIPLICITY",
            s"$path.definition.operationResumeMultiplicity",
            "operation symbols must declare resume multiplicity"
          ))
        case ApiSymbolKind.Operation => Right(())
        case _ if definition.operationResumeMultiplicity.nonEmpty =>
          Left(DescriptorError(
            "UNEXPECTED_RESUME_MULTIPLICITY",
            s"$path.definition.operationResumeMultiplicity",
            "only operation symbols may declare resume multiplicity"
          ))
        case _ => Right(())
      _ <- uniqueValues(
        s"$path.definition.callbackPolicies",
        definition.callbackPolicies.map(_.parameter),
        "DUPLICATE_CALLBACK_POLICY"
      )
      _ <- validateAll(definition.callbackPolicies.zipWithIndex) { case (policy, index) =>
        validateCallbackPolicy(definition, policy, s"$path.definition.callbackPolicies[$index]")
      }
      _ <- if definition.promptAndControlMetadata.answerTypeModification then
        Left(DescriptorError(
          "ANSWER_TYPE_MODIFICATION_UNSUPPORTED",
          s"$path.definition.promptAndControlMetadata.answerTypeModification",
          "descriptor v3 is answer-type preserving"
        ))
      else Right(())
      _ <- uniqueValues(
        s"$path.definition.promptAndControlMetadata.prompts",
        definition.promptAndControlMetadata.prompts.map(prompt => prompt.stablePromptId -> prompt.role),
        "DUPLICATE_PROMPT_METADATA"
      )
      _ <- validateAll(definition.promptAndControlMetadata.prompts.zipWithIndex) { case (prompt, index) =>
        for
          _ <- nonEmpty(
            s"$path.definition.promptAndControlMetadata.prompts[$index].stablePromptId",
            prompt.stablePromptId
          )
          _ <- validateType(
            prompt.answerType,
            s"$path.definition.promptAndControlMetadata.prompts[$index].answerType",
            binders
          )
        yield ()
      }
      expectedSymbol <- DescriptorHashes.stableSymbolId(moduleId, definition)
      _ <- equal(
        s"$path.stableSymbolId",
        symbol.stableSymbolId.value,
        expectedSymbol.value,
        "SYMBOL_ID_MISMATCH"
      )
      expectedOverload <- DescriptorHashes.overloadId(moduleId, definition)
      _ <- if symbol.overloadId == expectedOverload then Right(())
      else Left(DescriptorError(
        "OVERLOAD_ID_MISMATCH",
        s"$path.overloadId",
        s"expected ${expectedOverload.map(_.value).getOrElse("no overload id")}"
      ))
    yield ()

  private def validateTypeParameters(
      parameters: Vector[AbiTypeParameter],
      path: String,
      outerBinders: Vector[Vector[AbiTypeParameter]]
  ): Either[DescriptorError, Unit] =
    val binders = parameters +: outerBinders
    validateAll(parameters.zipWithIndex) { case (parameter, position) =>
      for
        _ <- if parameter.index == position then Right(())
        else Left(DescriptorError(
          "NON_CANONICAL_TYPE_PARAMETER_INDEX",
          s"$path[$position].index",
          s"expected declaration-order index $position"
        ))
        _ <- nonEmpty(s"$path[$position].name", parameter.name)
        _ <- nonNegative(s"$path[$position].kindArity", parameter.kindArity)
        _ <- parameter.lowerBound
          .map(validateType(_, s"$path[$position].lowerBound", binders))
          .getOrElse(Right(()))
        _ <- parameter.upperBound
          .map(validateType(_, s"$path[$position].upperBound", binders))
          .getOrElse(Right(()))
      yield ()
    }

  private def validateType(
      value: AbiType,
      path: String,
      binders: Vector[Vector[AbiTypeParameter]]
  ): Either[DescriptorError, Unit] = value match
    case AbiType.Primitive(_) => Right(())
    case AbiType.Named(stableTypeId, arguments) =>
      for
        _ <- nonEmpty(s"$path.stableTypeId", stableTypeId)
        _ <- validateAll(arguments.zipWithIndex) { case (argument, index) =>
          validateType(argument, s"$path.arguments[$index]", binders)
        }
      yield ()
    case AbiType.TypeParameter(reference) =>
      validateTypeParameterRef(reference, s"$path.reference", binders)
    case AbiType.Tuple(elements) =>
      validateAll(elements.zipWithIndex) { case (element, index) =>
        validateType(element, s"$path.elements[$index]", binders)
      }
    case AbiType.Function(parameterLists, result, effects) =>
      for
        _ <- validateAll(parameterLists.zipWithIndex) { case (list, listIndex) =>
          validateAll(list.zipWithIndex) { case (parameter, parameterIndex) =>
            validateType(parameter, s"$path.parameterLists[$listIndex][$parameterIndex]", binders)
          }
        }
        _ <- validateType(result, s"$path.result", binders)
        _ <- validateEffectRow(effects, s"$path.effects", binders)
      yield ()
    case AbiType.Union(alternatives) =>
      if alternatives.size < 2 then
        Left(DescriptorError("INVALID_UNION", path, "a canonical union needs at least two alternatives"))
      else
        for
          _ <- uniqueValues(
            s"$path.alternatives",
            alternatives.map(DescriptorNormalization.abiType),
            "DUPLICATE_UNION_ALTERNATIVE"
          )
          _ <- validateAll(alternatives.zipWithIndex) { case (alternative, index) =>
            validateType(alternative, s"$path.alternatives[$index]", binders)
          }
        yield ()
    case AbiType.Intersection(parts) =>
      if parts.size < 2 then
        Left(DescriptorError("INVALID_INTERSECTION", path, "a canonical intersection needs at least two parts"))
      else
        for
          _ <- uniqueValues(
            s"$path.parts",
            parts.map(DescriptorNormalization.abiType),
            "DUPLICATE_INTERSECTION_PART"
          )
          _ <- validateAll(parts.zipWithIndex) { case (part, index) =>
            validateType(part, s"$path.parts[$index]", binders)
          }
        yield ()
    case AbiType.TypeLambda(parameters, body) =>
      if parameters.isEmpty then
        Left(DescriptorError("INVALID_TYPE_LAMBDA", path, "a type lambda needs at least one binder"))
      else
        for
          _ <- validateTypeParameters(parameters, s"$path.parameters", binders)
          _ <- validateType(body, s"$path.body", parameters +: binders)
        yield ()

  private def validateEffectRow(
      value: EffectRow,
      path: String,
      binders: Vector[Vector[AbiTypeParameter]]
  ): Either[DescriptorError, Unit] =
    val identities = value.members.map { member =>
      member.copy(typeArguments = member.typeArguments.map(DescriptorNormalization.abiType))
    }
    for
      _ <- uniqueValues(path, identities, "DUPLICATE_EFFECT_ROW_MEMBER")
      _ <- validateAll(value.members.zipWithIndex) { case (member, index) =>
        for
          _ <- nonEmpty(s"$path.members[$index].stableEffectId", member.stableEffectId)
          _ <- validateAll(member.typeArguments.zipWithIndex) { case (argument, argumentIndex) =>
            validateType(argument, s"$path.members[$index].typeArguments[$argumentIndex]", binders)
          }
        yield ()
      }
      _ <- value.openTail
        .map(validateTypeParameterRef(_, s"$path.openTail", binders))
        .getOrElse(Right(()))
    yield ()

  private def validateTypeParameterRef(
      value: TypeParameterRef,
      path: String,
      binders: Vector[Vector[AbiTypeParameter]]
  ): Either[DescriptorError, Unit] =
    for
      _ <- nonNegative(s"$path.depth", value.depth)
      _ <- nonNegative(s"$path.index", value.index)
      _ <- nonNegative(s"$path.kindArity", value.kindArity)
      binder <- binders.lift(value.depth).toRight(DescriptorError(
        "UNBOUND_TYPE_PARAMETER",
        path,
        s"no binder group at depth ${value.depth}"
      ))
      parameter <- binder.lift(value.index).toRight(DescriptorError(
        "UNBOUND_TYPE_PARAMETER",
        path,
        s"no type parameter ${value.index} at depth ${value.depth}"
      ))
      _ <- if parameter.kindArity == value.kindArity then Right(())
      else Left(DescriptorError(
        "TYPE_PARAMETER_KIND_ARITY_MISMATCH",
        s"$path.kindArity",
        s"expected ${parameter.kindArity}, got ${value.kindArity}"
      ))
    yield ()

  private def validateCallbackPolicy(
      definition: ApiSymbolDefinition,
      value: CallbackPolicy,
      path: String
  ): Either[DescriptorError, Unit] =
    val listIndex = value.parameter.parameterListIndex
    val parameterIndex = value.parameter.parameterIndex
    for
      _ <- nonNegative(s"$path.parameter.parameterListIndex", listIndex)
      _ <- nonNegative(s"$path.parameter.parameterIndex", parameterIndex)
      _ <- if listIndex < definition.parameterLists.size &&
          parameterIndex < definition.parameterLists(listIndex).parameters.size then Right(())
        else Left(DescriptorError(
          "INVALID_CALLBACK_PARAMETER_PATH",
          s"$path.parameter",
          "callback policy does not address an existing parameter"
        ))
      _ <- value.threadAffinity match
        case ThreadAffinity.EventLoop(profile) => nonEmpty(s"$path.threadAffinity.profile", profile)
        case ThreadAffinity.Actor(profile)     => nonEmpty(s"$path.threadAffinity.profile", profile)
        case ThreadAffinity.Named(profile)     => nonEmpty(s"$path.threadAffinity.profile", profile)
        case _                                 => Right(())
    yield ()

  private def validateFrameSchema(value: FrameSchema, path: String): Either[DescriptorError, Unit] =
    for
      _ <- nonEmpty(s"$path.schemaId", value.schemaId)
      _ <- validateTypeParameters(value.typeParameters, s"$path.typeParameters", Vector.empty)
      binders = Vector(value.typeParameters)
      _ <- unique(s"$path.slots", value.slots.map(_.slotId), "DUPLICATE_FRAME_SLOT")
      _ <- validateAll(value.slots.zipWithIndex) { case (slot, index) =>
        for
          _ <- nonEmpty(s"$path.slots[$index].slotId", slot.slotId)
          _ <- validateType(slot.tpe, s"$path.slots[$index].tpe", binders)
          _ <- slot.durability match
            case FrameSlotDurability.Durable => Right(())
            case FrameSlotDurability.DurableRef(providerId) =>
              nonEmpty(s"$path.slots[$index].durability.providerId", providerId)
            case FrameSlotDurability.Unsavable(code) =>
              nonEmpty(s"$path.slots[$index].durability.code", code)
        yield ()
      }
    yield ()

  private def validateSaveSite(value: SaveSiteSummary, path: String): Either[DescriptorError, Unit] =
    for
      _ <- nonEmpty(s"$path.siteId", value.siteId)
      _ <- symbolId(s"$path.owner", value.owner)
      _ <- nonEmpty(s"$path.frameSchemaId", value.frameSchemaId)
      _ <- validateAll(value.stablePromptIds.zipWithIndex) { case (promptId, index) =>
        nonEmpty(s"$path.stablePromptIds[$index]", promptId)
      }
      _ <- value.firstBarrier.map(validateBarrier(_, s"$path.firstBarrier")).getOrElse(Right(()))
    yield ()

  private def validateBarrier(
      value: CaptureBarrierSummary,
      path: String
  ): Either[DescriptorError, Unit] =
    for
      _ <- nonEmpty(s"$path.siteId", value.siteId)
      _ <- symbolId(s"$path.owner", value.owner)
      _ <- nonEmpty(s"$path.category", value.category)
      _ <- nonEmpty(s"$path.detail", value.detail)
    yield ()

  private def validateEntrypoint(
      value: TargetEntrypoint,
      path: String
  ): Either[DescriptorError, Unit] = value match
    case TargetEntrypoint.Jvm(entry) =>
      for
        _ <- entrypointId(s"$path.value.entrypointId", entry.entrypointId)
        _ <- symbolId(s"$path.value.stableSymbolId", entry.stableSymbolId)
        _ <- nonEmpty(s"$path.value.ownerInternalName", entry.ownerInternalName)
        _ <- nonEmpty(s"$path.value.methodName", entry.methodName)
        _ <- nonEmpty(s"$path.value.methodDescriptor", entry.methodDescriptor)
        _ <- nonEmpty(s"$path.value.classLoaderProfile", entry.classLoaderProfile)
        _ <- validateAll(entry.bridgeFlags.zipWithIndex) { case (flag, index) =>
          nonEmpty(s"$path.value.bridgeFlags[$index]", flag)
        }
        _ <- digest(s"$path.value.implementationDigest", entry.implementationDigest.value)
        expected <- DescriptorHashes.jvmEntrypointId(
          entry.stableSymbolId,
          entry.ownerInternalName,
          entry.methodName,
          entry.methodDescriptor,
          entry.invocationKind,
          entry.bridgeFlags,
          entry.classLoaderProfile
        )
        _ <- equal(
          s"$path.value.entrypointId",
          entry.entrypointId.value,
          expected.value,
          "ENTRYPOINT_ID_MISMATCH"
        )
      yield ()
    case TargetEntrypoint.Named(entry) =>
      for
        _ <- entrypointId(s"$path.value.entrypointId", entry.entrypointId)
        _ <- symbolId(s"$path.value.stableSymbolId", entry.stableSymbolId)
        _ <- nonEmpty(s"$path.value.externalName", entry.externalName)
        _ <- nonEmpty(s"$path.value.targetAbi", entry.targetAbi)
        _ <- digest(s"$path.value.implementationDigest", entry.implementationDigest.value)
        expected <- DescriptorHashes.namedEntrypointId(
          entry.stableSymbolId,
          entry.externalName,
          entry.targetAbi
        )
        _ <- equal(
          s"$path.value.entrypointId",
          entry.entrypointId.value,
          expected.value,
          "ENTRYPOINT_ID_MISMATCH"
        )
      yield ()

  private def validateDependency(
      value: DependencyBinding,
      path: String
  ): Either[DescriptorError, Unit] =
    for
      _ <- nonEmpty(s"$path.logicalId", value.logicalId)
      _ <- nonEmpty(s"$path.semanticAbiId", value.semanticAbiId)
      _ <- value.schemaId.map(nonEmpty(s"$path.schemaId", _)).getOrElse(Right(()))
      _ <- digest(s"$path.implementationDigest", value.implementationDigest.value)
      _ <- value.target.map(nonEmpty(s"$path.target", _)).getOrElse(Right(()))
      _ <- validateAll(value.requiredCapabilities.zipWithIndex) { case (capability, index) =>
        nonEmpty(s"$path.requiredCapabilities[$index]", capability)
      }
    yield ()

  private def symbolId(path: String, value: StableSymbolId): Either[DescriptorError, Unit] =
    prefixedHash(path, value.value, "ssc:symbol:v1:", "INVALID_SYMBOL_ID")

  private def entrypointId(path: String, value: EntrypointId): Either[DescriptorError, Unit] =
    val prefixes = Vector("ssc:jvm-entrypoint:v1:", "ssc:target-entrypoint:v1:")
    prefixes.find(value.value.startsWith) match
      case Some(prefix) => prefixedHash(path, value.value, prefix, "INVALID_ENTRYPOINT_ID")
      case None => Left(DescriptorError(
        "INVALID_ENTRYPOINT_ID",
        path,
        "entrypoint id has an unsupported domain prefix"
      ))

  private def prefixedHash(
      path: String,
      value: String,
      prefix: String,
      code: String
  ): Either[DescriptorError, Unit] =
    if value.startsWith(prefix) && isHex64(value.drop(prefix.length)) then Right(())
    else Left(DescriptorError(code, path, s"expected $prefix followed by 64 lowercase hex digits"))

  private def optionalDigest(
      path: String,
      value: Option[String]
  ): Either[DescriptorError, Unit] = value.map(digest(path, _)).getOrElse(Right(()))

  private def digest(path: String, value: String): Either[DescriptorError, Unit] =
    if isHex64(value) then Right(())
    else Left(DescriptorError("INVALID_SHA256", path, "expected 64 lowercase hex digits"))

  private def isHex64(value: String): Boolean = Hex64.matches(value)

  private def exactVersion(
      path: String,
      actual: String,
      expected: String
  ): Either[DescriptorError, Unit] =
    if actual == expected then Right(())
    else Left(DescriptorError("UNSUPPORTED_VERSION", path, s"expected $expected, got $actual"))

  private def nonEmpty(path: String, value: String): Either[DescriptorError, Unit] =
    if value.nonEmpty then Right(())
    else Left(DescriptorError("EMPTY_FIELD", path, "field must not be empty"))

  private def nonNegative(path: String, value: Int): Either[DescriptorError, Unit] =
    if value >= 0 then Right(())
    else Left(DescriptorError("INVALID_INDEX", path, "index must be in 0..2147483647"))

  private def equal(
      path: String,
      actual: String,
      expected: String,
      code: String
  ): Either[DescriptorError, Unit] =
    if actual == expected then Right(())
    else Left(DescriptorError(code, path, s"expected $expected, got $actual"))

  private def unique(
      path: String,
      values: Vector[String],
      code: String
  ): Either[DescriptorError, Unit] =
    val duplicate = values.groupBy(identity).collectFirst { case (value, occurrences) if occurrences.size > 1 => value }
    duplicate match
      case None => Right(())
      case Some(value) => Left(DescriptorError(code, path, s"duplicate value: $value"))

  private def uniqueValues[A](
      path: String,
      values: Vector[A],
      code: String
  ): Either[DescriptorError, Unit] =
    values.groupBy(identity).collectFirst { case (value, occurrences) if occurrences.size > 1 => value } match
      case None        => Right(())
      case Some(value) => Left(DescriptorError(code, path, s"duplicate value: $value"))

  private def validateAll[A](
      values: Iterable[A]
  )(f: A => Either[DescriptorError, Unit]): Either[DescriptorError, Unit] =
    values.iterator.foldLeft[Either[DescriptorError, Unit]](Right(())) { (acc, value) =>
      acc.flatMap(_ => f(value))
    }

  private def entrypointIdOf(value: TargetEntrypoint): String = value match
    case TargetEntrypoint.Jvm(entry)   => entry.entrypointId.value
    case TargetEntrypoint.Named(entry) => entry.entrypointId.value
