package scalascript.interop.descriptor

object DescriptorFactory:

  def api(
      controlAbiVersion: String,
      moduleId: String,
      definitions: Vector[ApiSymbolDefinition]
  ): Either[DescriptorError, ApiDescriptor] =
    DescriptorPreflight.definitions(definitions).flatMap { _ =>
      traverse(definitions) { definition =>
        for
          symbolId <- DescriptorHashes.stableSymbolId(moduleId, definition)
          overload <- DescriptorHashes.overloadId(moduleId, definition)
        yield ApiSymbol(symbolId, overload, definition)
      }.flatMap { symbols =>
        val unhashed = ApiDescriptor(
          schemaVersion = DescriptorVersions.ApiDescriptor,
          controlAbiVersion = controlAbiVersion,
          moduleId = moduleId,
          apiHash = ApiHash("0" * 64),
          symbols = symbols
        )
        DescriptorHashes.apiHash(unhashed).map(hash => unhashed.copy(apiHash = hash))
      }.flatMap(DescriptorValidator.api)
        .flatMap(value => bounded(value, item => ApiWire.writeApi(item)))
    }

  def controlSummary(
      controlAbiVersion: String,
      moduleId: String,
      apiHash: ApiHash,
      managedCallEdges: Vector[ManagedCallEdge] = Vector.empty,
      foreignCallEdges: Vector[ForeignCallEdge] = Vector.empty,
      tailEdges: Vector[TailEdge] = Vector.empty,
      saveSites: Vector[SaveSiteSummary] = Vector.empty,
      frameSchemas: Vector[FrameSchema] = Vector.empty,
      captureBarriers: Vector[CaptureBarrierSummary] = Vector.empty
  ): Either[DescriptorError, ControlSummary] =
    val unhashed = ControlSummary(
      schemaVersion = DescriptorVersions.ControlSummary,
      controlAbiVersion = controlAbiVersion,
      moduleId = moduleId,
      apiHash = apiHash,
      summaryDigest = ControlSummaryDigest("0" * 64),
      managedCallEdges = managedCallEdges,
      foreignCallEdges = foreignCallEdges,
      tailEdges = tailEdges,
      saveSites = saveSites,
      frameSchemas = frameSchemas,
      captureBarriers = captureBarriers
    )
    DescriptorPreflight.control(unhashed)
      .flatMap(_ => DescriptorHashes.controlSummaryDigest(unhashed))
      .map(digest => unhashed.copy(summaryDigest = digest))
      .flatMap(DescriptorValidator.controlSummary)
      .flatMap(value => bounded(value, item => ControlWire.writeControl(item)))

  def jvmEntrypoint(
      stableSymbolId: StableSymbolId,
      ownerInternalName: String,
      methodName: String,
      methodDescriptor: String,
      invocationKind: JvmInvocationKind,
      bridgeFlags: Vector[String],
      classLoaderProfile: String,
      implementationDigest: ImplementationDigest
  ): Either[DescriptorError, JvmEntrypoint] =
    DescriptorValidator.bridgeFlagsRaw(bridgeFlags).flatMap { _ =>
      DescriptorHashes.jvmEntrypointId(
        stableSymbolId,
        ownerInternalName,
        methodName,
        methodDescriptor,
        invocationKind,
        bridgeFlags,
        classLoaderProfile
      ).flatMap { id =>
        val entrypoint = JvmEntrypoint(
          id,
          stableSymbolId,
          ownerInternalName,
          methodName,
          methodDescriptor,
          invocationKind,
          DescriptorNormalization.strings(bridgeFlags),
          classLoaderProfile,
          implementationDigest
        )
        DescriptorValidator.targetEntrypoint(TargetEntrypoint.Jvm(entrypoint)).flatMap {
          case TargetEntrypoint.Jvm(validated) =>
            bounded(validated, ArtifactWire.writeJvmEntrypoint)
          case TargetEntrypoint.Named(_) =>
            Left(DescriptorError("INTERNAL_ERROR", "$", "JVM entrypoint changed variant"))
        }
      }
    }

  def namedEntrypoint(
      stableSymbolId: StableSymbolId,
      externalName: String,
      targetAbi: String,
      implementationDigest: ImplementationDigest
  ): Either[DescriptorError, NamedTargetEntrypoint] =
    DescriptorHashes.namedEntrypointId(stableSymbolId, externalName, targetAbi).flatMap { id =>
      val entrypoint = NamedTargetEntrypoint(
        id,
        stableSymbolId,
        externalName,
        targetAbi,
        implementationDigest
      )
      DescriptorValidator.targetEntrypoint(TargetEntrypoint.Named(entrypoint)).flatMap {
        case TargetEntrypoint.Named(validated) =>
          bounded(validated, ArtifactWire.writeNamedEntrypoint)
        case TargetEntrypoint.Jvm(_) =>
          Left(DescriptorError("INTERNAL_ERROR", "$", "named entrypoint changed variant"))
      }
    }

  def artifactManifest(
      controlAbiVersion: String,
      apiHash: ApiHash,
      target: TargetProfile,
      targetEntrypoints: Vector[TargetEntrypoint],
      programDigest: Option[ProgramDigest],
      artifactDigest: Option[ArtifactDigest],
      runtimeVersion: String,
      dependencyManifest: Vector[DependencyBinding],
      controlSummaryDigests: Vector[ControlSummaryDigest]
  ): Either[DescriptorError, ArtifactManifest] =
    val unhashed = ArtifactManifest(
      artifactManifestVersion = DescriptorVersions.ArtifactManifest,
      controlAbiVersion = controlAbiVersion,
      apiHash = apiHash,
      target = target,
      targetEntrypoints = targetEntrypoints,
      programDigest = programDigest,
      artifactDigest = artifactDigest,
      runtimeVersion = runtimeVersion,
      dependencyManifest = dependencyManifest,
      dependencyProfileDigest = DependencyProfileDigest("0" * 64),
      controlSummaryDigests = controlSummaryDigests
    )
    DescriptorPreflight.artifact(unhashed)
      .flatMap(_ => DescriptorHashes.dependencyProfileDigest(dependencyManifest))
      .flatMap { dependencyDigest =>
        DescriptorValidator.artifact(unhashed.copy(dependencyProfileDigest = dependencyDigest))
      }
      .flatMap(value => bounded(value, ArtifactWire.writeArtifact))

  private def bounded[A](
      value: A,
      write: A => ujson.Value
  ): Either[DescriptorError, A] =
    CanonicalJson.bytes(write(value)).map(_ => value)

  private def traverse[A, B](
      values: Vector[A]
  )(f: A => Either[DescriptorError, B]): Either[DescriptorError, Vector[B]] =
    values.foldLeft[Either[DescriptorError, Vector[B]]](Right(Vector.empty)) { (acc, value) =>
      for
        done <- acc
        next <- f(value)
      yield done :+ next
    }
