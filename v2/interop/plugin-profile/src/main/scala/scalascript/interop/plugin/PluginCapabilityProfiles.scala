package scalascript.interop.plugin

import scalascript.interop.descriptor.*

object PluginCapabilityProfiles:

  def bind(
      declaration: PluginCapabilityDeclaration,
      implementation: PluginTargetImplementation
  ): Either[PluginProfileError, PluginCapabilityProfile] =
    for
      normalizedDeclaration <- CanonicalProfile.declaration(declaration)
      normalizedImplementation <- validateImplementation(implementation)
      semanticId = CanonicalProfile.semanticId(normalizedDeclaration.semanticPreimage)
      schemaId = normalizedDeclaration.schemaPreimage.map(CanonicalProfile.schemaId)
    yield PluginCapabilityProfile(
      normalizedDeclaration.value,
      semanticId,
      schemaId,
      normalizedImplementation)

  def implementationFromBytes(
      target: String,
      targetAbi: String,
      exactArtifactBytes: Array[Byte],
      requiredCapabilities: Vector[String] = Vector.empty
  ): Either[PluginProfileError, PluginTargetImplementation] =
    if exactArtifactBytes == null then
      Left(PluginProfileError(
        "INVALID_ARTIFACT_BYTES",
        "$.implementation.exactArtifactBytes",
        "exact artifact bytes must not be null"))
    else
      implementationFromDigest(
        target,
        targetAbi,
        DescriptorHashes.implementation(exactArtifactBytes),
        requiredCapabilities)

  def implementationFromDigest(
      target: String,
      targetAbi: String,
      implementationDigest: ImplementationDigest,
      requiredCapabilities: Vector[String] = Vector.empty
  ): Either[PluginProfileError, PluginTargetImplementation] =
    validateImplementation(PluginTargetImplementation(
      target,
      targetAbi,
      implementationDigest,
      requiredCapabilities))

  def dependencyBinding(
      profile: PluginCapabilityProfile
  ): Either[PluginProfileError, DependencyBinding] =
    for
      checked <- validated(profile)
    yield DependencyBinding(
      kind = DependencyKind.Plugin,
      logicalId = checked.declaration.pluginId,
      semanticAbiId = checked.aggregateSemanticAbiId,
      schemaId = checked.aggregateSchemaId,
      implementationDigest = checked.implementation.implementationDigest,
      target = Some(checked.implementation.target),
      requiredCapabilities = checked.implementation.requiredCapabilities)

  private[plugin] def validated(
      profile: PluginCapabilityProfile
  ): Either[PluginProfileError, PluginCapabilityProfile] =
    for
      _ <- CanonicalProfile.aggregateId(
        "$.aggregateSemanticAbiId",
        profile.aggregateSemanticAbiId,
        "ssc:plugin-abi:v1:")
      _ <- profile.aggregateSchemaId
        .map(CanonicalProfile.aggregateId(
          "$.aggregateSchemaId",
          _,
          "ssc:plugin-schema:v1:"))
        .getOrElse(Right(()))
      checked <- bind(profile.declaration, profile.implementation)
      _ <- equal(
        "$.aggregateSemanticAbiId",
        profile.aggregateSemanticAbiId,
        checked.aggregateSemanticAbiId,
        "SEMANTIC_ABI_MISMATCH")
      _ <-
        if profile.aggregateSchemaId == checked.aggregateSchemaId then Right(())
        else Left(PluginProfileError(
          "SCHEMA_MISMATCH",
          "$.aggregateSchemaId",
          "stored aggregate schema id does not match the declaration"))
    yield checked

  private def validateImplementation(
      value: PluginTargetImplementation
  ): Either[PluginProfileError, PluginTargetImplementation] =
    for
      _ <- CanonicalProfile.nonEmpty("$.implementation.target", value.target)
      _ <- CanonicalProfile.nonEmpty("$.implementation.targetAbi", value.targetAbi)
      _ <- CanonicalProfile.digest(
        "$.implementation.implementationDigest",
        value.implementationDigest.value)
      required <- CanonicalProfile.capabilities(
        value.requiredCapabilities,
        "$.implementation.requiredCapabilities")
    yield value.copy(requiredCapabilities = required)

  private def equal(
      path: String,
      actual: String,
      expected: String,
      code: String
  ): Either[PluginProfileError, Unit] =
    if actual == expected then Right(())
    else Left(PluginProfileError(code, path, s"expected '$expected', found '$actual'"))
