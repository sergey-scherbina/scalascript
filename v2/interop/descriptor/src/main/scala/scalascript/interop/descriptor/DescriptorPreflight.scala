package scalascript.interop.descriptor

import scala.util.boundary
import scala.util.boundary.break

private[descriptor] object DescriptorPreflight:

  def definitions(
      values: Vector[ApiSymbolDefinition],
      path: String = "$.definitions"
  ): Either[DescriptorError, Unit] =
    for
      _ <- container(path, values.size)
      _ <- all(values.zipWithIndex) { case (value, index) =>
        definition(value, s"$path[$index]")
      }
    yield ()

  def api(value: ApiDescriptor): Either[DescriptorError, Unit] =
    for
      _ <- container("$.symbols", value.symbols.size)
      _ <- all(value.symbols.zipWithIndex) { case (symbol, index) =>
        definition(symbol.definition, s"$$.symbols[$index].definition")
      }
    yield ()

  def definition(
      value: ApiSymbolDefinition,
      path: String = "$.definition"
  ): Either[DescriptorError, Unit] =
    for
      _ <- typeParameters(value.typeParameters, s"$path.typeParameters")
      _ <- container(s"$path.parameterLists", value.parameterLists.size)
      _ <- all(value.parameterLists.zipWithIndex) { case (list, listIndex) =>
        for
          _ <- container(s"$path.parameterLists[$listIndex].parameters", list.parameters.size)
          _ <- all(list.parameters.zipWithIndex) { case (parameter, parameterIndex) =>
            abiType(parameter.tpe, s"$path.parameterLists[$listIndex].parameters[$parameterIndex].tpe")
          }
        yield ()
      }
      _ <- abiType(value.resultType, s"$path.resultType")
      _ <- effectRow(value.effectRow, s"$path.effectRow")
      _ <- container(s"$path.callbackPolicies", value.callbackPolicies.size)
      _ <- container(
        s"$path.promptAndControlMetadata.prompts",
        value.promptAndControlMetadata.prompts.size
      )
      _ <- all(value.promptAndControlMetadata.prompts.zipWithIndex) { case (prompt, index) =>
        abiType(prompt.answerType, s"$path.promptAndControlMetadata.prompts[$index].answerType")
      }
      _ <- container(s"$path.requiredCapabilities", value.requiredCapabilities.size)
      _ <- container(s"$path.requiredTargets", value.requiredTargets.size)
    yield ()

  def control(value: ControlSummary): Either[DescriptorError, Unit] =
    for
      _ <- container("$.managedCallEdges", value.managedCallEdges.size)
      _ <- container("$.foreignCallEdges", value.foreignCallEdges.size)
      _ <- container("$.tailEdges", value.tailEdges.size)
      _ <- container("$.saveSites", value.saveSites.size)
      _ <- container("$.frameSchemas", value.frameSchemas.size)
      _ <- container("$.captureBarriers", value.captureBarriers.size)
      _ <- all(value.saveSites.zipWithIndex) { case (site, index) =>
        container(s"$$.saveSites[$index].stablePromptIds", site.stablePromptIds.size)
      }
      _ <- all(value.frameSchemas.zipWithIndex) { case (schema, schemaIndex) =>
        for
          _ <- typeParameters(schema.typeParameters, s"$$.frameSchemas[$schemaIndex].typeParameters")
          _ <- container(s"$$.frameSchemas[$schemaIndex].slots", schema.slots.size)
          _ <- all(schema.slots.zipWithIndex) { case (slot, slotIndex) =>
            abiType(slot.tpe, s"$$.frameSchemas[$schemaIndex].slots[$slotIndex].tpe")
          }
        yield ()
      }
    yield ()

  def artifact(value: ArtifactManifest): Either[DescriptorError, Unit] =
    for
      _ <- container("$.target.features", value.target.features.size)
      _ <- container("$.targetEntrypoints", value.targetEntrypoints.size)
      _ <- all(value.targetEntrypoints.zipWithIndex) { case (entrypoint, index) =>
        entrypoint match
          case TargetEntrypoint.Jvm(jvm) =>
            container(s"$$.targetEntrypoints[$index].value.bridgeFlags", jvm.bridgeFlags.size)
          case TargetEntrypoint.Named(_) => Right(())
      }
      _ <- dependencies(value.dependencyManifest, "$.dependencyManifest")
      _ <- container("$.controlSummaryDigests", value.controlSummaryDigests.size)
    yield ()

  def dependencies(
      values: Vector[DependencyBinding],
      path: String = "$.dependencyManifest"
  ): Either[DescriptorError, Unit] =
    for
      _ <- container(path, values.size)
      _ <- all(values.zipWithIndex) { case (binding, index) =>
        container(s"$path[$index].requiredCapabilities", binding.requiredCapabilities.size)
      }
    yield ()

  def stringVector(path: String, values: Vector[String]): Either[DescriptorError, Unit] =
    container(path, values.size)

  private def typeParameters(
      values: Vector[AbiTypeParameter],
      path: String
  ): Either[DescriptorError, Unit] =
    for
      _ <- container(path, values.size)
      _ <- all(values.zipWithIndex) { case (parameter, index) =>
        for
          _ <- parameter.lowerBound
            .map(abiType(_, s"$path[$index].lowerBound"))
            .getOrElse(Right(()))
          _ <- parameter.upperBound
            .map(abiType(_, s"$path[$index].upperBound"))
            .getOrElse(Right(()))
        yield ()
      }
    yield ()

  private def effectRow(value: EffectRow, path: String): Either[DescriptorError, Unit] =
    for
      _ <- container(s"$path.members", value.members.size)
      _ <- all(value.members.zipWithIndex) { case (member, index) =>
        for
          _ <- container(s"$path.members[$index].typeArguments", member.typeArguments.size)
          _ <- all(member.typeArguments.zipWithIndex) { case (argument, argumentIndex) =>
            abiType(argument, s"$path.members[$index].typeArguments[$argumentIndex]")
          }
        yield ()
      }
    yield ()

  private def abiType(root: AbiType, rootPath: String): Either[DescriptorError, Unit] = boundary:
    var pending = List((root, 1, rootPath))
    while pending.nonEmpty do
      val (value, depth, path) = pending.head
      pending = pending.tail
      if depth > DescriptorCodec.MaxDepth then
        break(Left(DescriptorError(
          "TYPE_DEPTH_LIMIT",
          path,
          s"ABI type nesting exceeds ${DescriptorCodec.MaxDepth}"
        )))

      def push(child: AbiType, childPath: String): Unit =
        pending = (child, depth + 1, childPath) :: pending

      value match
        case AbiType.Primitive(_) | AbiType.TypeParameter(_) => ()
        case AbiType.Named(_, arguments) =>
          container(s"$path.arguments", arguments.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          arguments.zipWithIndex.reverseIterator.foreach { case (argument, index) =>
            push(argument, s"$path.arguments[$index]")
          }
        case AbiType.Tuple(elements) =>
          container(s"$path.elements", elements.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          elements.zipWithIndex.reverseIterator.foreach { case (element, index) =>
            push(element, s"$path.elements[$index]")
          }
        case AbiType.Function(parameterLists, result, effects) =>
          container(s"$path.parameterLists", parameterLists.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          parameterLists.zipWithIndex.reverseIterator.foreach { case (parameters, listIndex) =>
            container(s"$path.parameterLists[$listIndex]", parameters.size) match
              case Left(error) => break(Left(error))
              case Right(())   => ()
            parameters.zipWithIndex.reverseIterator.foreach { case (parameter, parameterIndex) =>
              push(parameter, s"$path.parameterLists[$listIndex][$parameterIndex]")
            }
          }
          push(result, s"$path.result")
          container(s"$path.effects.members", effects.members.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          effects.members.zipWithIndex.reverseIterator.foreach { case (member, memberIndex) =>
            container(s"$path.effects.members[$memberIndex].typeArguments", member.typeArguments.size) match
              case Left(error) => break(Left(error))
              case Right(())   => ()
            member.typeArguments.zipWithIndex.reverseIterator.foreach { case (argument, argumentIndex) =>
              push(argument, s"$path.effects.members[$memberIndex].typeArguments[$argumentIndex]")
            }
          }
        case AbiType.Union(alternatives) =>
          container(s"$path.alternatives", alternatives.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          alternatives.zipWithIndex.reverseIterator.foreach { case (alternative, index) =>
            push(alternative, s"$path.alternatives[$index]")
          }
        case AbiType.Intersection(parts) =>
          container(s"$path.parts", parts.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          parts.zipWithIndex.reverseIterator.foreach { case (part, index) =>
            push(part, s"$path.parts[$index]")
          }
        case AbiType.TypeLambda(parameters, body) =>
          container(s"$path.parameters", parameters.size) match
            case Left(error) => break(Left(error))
            case Right(())   => ()
          parameters.zipWithIndex.reverseIterator.foreach { case (parameter, index) =>
            parameter.upperBound.foreach(push(_, s"$path.parameters[$index].upperBound"))
            parameter.lowerBound.foreach(push(_, s"$path.parameters[$index].lowerBound"))
          }
          push(body, s"$path.body")
    Right(())

  private def container(path: String, size: Int): Either[DescriptorError, Unit] =
    if size <= DescriptorCodec.MaxContainerItems then Right(())
    else Left(DescriptorError(
      "MODEL_CONTAINER_LIMIT",
      path,
      s"container exceeds ${DescriptorCodec.MaxContainerItems} items"
    ))

  private def all[A](
      values: Iterable[A]
  )(validate: A => Either[DescriptorError, Unit]): Either[DescriptorError, Unit] =
    values.iterator.foldLeft[Either[DescriptorError, Unit]](Right(())) { (acc, value) =>
      acc.flatMap(_ => validate(value))
    }
