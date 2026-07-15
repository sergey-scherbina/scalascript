package scalascript.interop.descriptor

private[descriptor] object ArtifactWire:
  import WireSupport.*

  private val invocationKinds = Map(
    "Static" -> JvmInvocationKind.Static,
    "Virtual" -> JvmInvocationKind.Virtual,
    "Interface" -> JvmInvocationKind.Interface,
    "Special" -> JvmInvocationKind.Special
  )
  private val dependencyKinds = Map(
    "Primitive" -> DependencyKind.Primitive,
    "Plugin" -> DependencyKind.Plugin,
    "Resolver" -> DependencyKind.Resolver,
    "Codec" -> DependencyKind.Codec,
    "Capability" -> DependencyKind.Capability,
    "Runtime" -> DependencyKind.Runtime,
    "Artifact" -> DependencyKind.Artifact
  )

  def writeEntrypointId(value: EntrypointId): ujson.Value = writeWrappedString(value.value)
  def readEntrypointId(value: ujson.Value, path: String): Result[EntrypointId] =
    wrappedString(value, path)(EntrypointId.apply)

  def writeDependencyDigest(value: DependencyProfileDigest): ujson.Value = writeWrappedString(value.value)
  def readDependencyDigest(value: ujson.Value, path: String): Result[DependencyProfileDigest] =
    wrappedString(value, path)(DependencyProfileDigest.apply)

  def writeProgramDigest(value: ProgramDigest): ujson.Value = writeWrappedString(value.value)
  def readProgramDigest(value: ujson.Value, path: String): Result[ProgramDigest] =
    wrappedString(value, path)(ProgramDigest.apply)

  def writeArtifactDigest(value: ArtifactDigest): ujson.Value = writeWrappedString(value.value)
  def readArtifactDigest(value: ujson.Value, path: String): Result[ArtifactDigest] =
    wrappedString(value, path)(ArtifactDigest.apply)

  def writeImplementationDigest(value: ImplementationDigest): ujson.Value = writeWrappedString(value.value)
  def readImplementationDigest(value: ujson.Value, path: String): Result[ImplementationDigest] =
    wrappedString(value, path)(ImplementationDigest.apply)

  def writeTargetProfile(value: TargetProfile): ujson.Value = obj(
    "id" -> str(value.id),
    "abi" -> str(value.abi),
    "features" -> arr(value.features.map(str))
  )

  def readTargetProfile(value: ujson.Value, path: String): Result[TargetProfile] =
    for
      fields <- exactObject(value, path, "id", "abi", "features")
      id <- string(field(fields, "id"), s"$path.id")
      abi <- string(field(fields, "abi"), s"$path.abi")
      features <- vector(field(fields, "features"), s"$path.features")(
        (item, itemPath) => string(item, itemPath)
      )
    yield TargetProfile(id, abi, features)

  def writeInvocationKind(value: JvmInvocationKind): ujson.Value = tagged(value.toString)
  def readInvocationKind(value: ujson.Value, path: String): Result[JvmInvocationKind] =
    enumValue(value, path, invocationKinds)

  def writeJvmEntrypoint(value: JvmEntrypoint): ujson.Value = obj(
    "entrypointId" -> writeEntrypointId(value.entrypointId),
    "stableSymbolId" -> ApiWire.writeStableSymbolId(value.stableSymbolId),
    "ownerInternalName" -> str(value.ownerInternalName),
    "methodName" -> str(value.methodName),
    "methodDescriptor" -> str(value.methodDescriptor),
    "invocationKind" -> writeInvocationKind(value.invocationKind),
    "bridgeFlags" -> arr(value.bridgeFlags.map(str)),
    "classLoaderProfile" -> str(value.classLoaderProfile),
    "implementationDigest" -> writeImplementationDigest(value.implementationDigest)
  )

  def readJvmEntrypoint(value: ujson.Value, path: String): Result[JvmEntrypoint] =
    for
      fields <- exactObject(
        value,
        path,
        "entrypointId",
        "stableSymbolId",
        "ownerInternalName",
        "methodName",
        "methodDescriptor",
        "invocationKind",
        "bridgeFlags",
        "classLoaderProfile",
        "implementationDigest"
      )
      entrypointId <- readEntrypointId(field(fields, "entrypointId"), s"$path.entrypointId")
      symbolId <- ApiWire.readStableSymbolId(field(fields, "stableSymbolId"), s"$path.stableSymbolId")
      owner <- string(field(fields, "ownerInternalName"), s"$path.ownerInternalName")
      methodName <- string(field(fields, "methodName"), s"$path.methodName")
      descriptor <- string(field(fields, "methodDescriptor"), s"$path.methodDescriptor")
      invocationKind <- readInvocationKind(field(fields, "invocationKind"), s"$path.invocationKind")
      bridgeFlags <- vector(field(fields, "bridgeFlags"), s"$path.bridgeFlags")(
        (item, itemPath) => string(item, itemPath)
      )
      classLoaderProfile <- string(field(fields, "classLoaderProfile"), s"$path.classLoaderProfile")
      implementationDigest <- readImplementationDigest(
        field(fields, "implementationDigest"),
        s"$path.implementationDigest"
      )
    yield JvmEntrypoint(
      entrypointId,
      symbolId,
      owner,
      methodName,
      descriptor,
      invocationKind,
      bridgeFlags,
      classLoaderProfile,
      implementationDigest
    )

  def writeNamedEntrypoint(value: NamedTargetEntrypoint): ujson.Value = obj(
    "entrypointId" -> writeEntrypointId(value.entrypointId),
    "stableSymbolId" -> ApiWire.writeStableSymbolId(value.stableSymbolId),
    "externalName" -> str(value.externalName),
    "targetAbi" -> str(value.targetAbi),
    "implementationDigest" -> writeImplementationDigest(value.implementationDigest)
  )

  def readNamedEntrypoint(value: ujson.Value, path: String): Result[NamedTargetEntrypoint] =
    for
      fields <- exactObject(
        value,
        path,
        "entrypointId",
        "stableSymbolId",
        "externalName",
        "targetAbi",
        "implementationDigest"
      )
      entrypointId <- readEntrypointId(field(fields, "entrypointId"), s"$path.entrypointId")
      symbolId <- ApiWire.readStableSymbolId(field(fields, "stableSymbolId"), s"$path.stableSymbolId")
      externalName <- string(field(fields, "externalName"), s"$path.externalName")
      targetAbi <- string(field(fields, "targetAbi"), s"$path.targetAbi")
      implementationDigest <- readImplementationDigest(
        field(fields, "implementationDigest"),
        s"$path.implementationDigest"
      )
    yield NamedTargetEntrypoint(entrypointId, symbolId, externalName, targetAbi, implementationDigest)

  def writeTargetEntrypoint(value: TargetEntrypoint): ujson.Value = value match
    case TargetEntrypoint.Jvm(entrypoint) =>
      tagged("Jvm", "value" -> writeJvmEntrypoint(entrypoint))
    case TargetEntrypoint.Named(entrypoint) =>
      tagged("Named", "value" -> writeNamedEntrypoint(entrypoint))

  def readTargetEntrypoint(value: ujson.Value, path: String): Result[TargetEntrypoint] = value match
    case objectValue: ujson.Obj => objectValue.value.get("tag") match
      case Some(ujson.Str("Jvm")) =>
        tag(value, path, "value").flatMap { case (fields, _) =>
          readJvmEntrypoint(field(fields, "value"), s"$path.value").map(TargetEntrypoint.Jvm.apply)
        }
      case Some(ujson.Str("Named")) =>
        tag(value, path, "value").flatMap { case (fields, _) =>
          readNamedEntrypoint(field(fields, "value"), s"$path.value").map(TargetEntrypoint.Named.apply)
        }
      case Some(ujson.Str(other)) => schema(s"$path.tag", s"unsupported tag $other")
      case Some(_)                => schema(s"$path.tag", "expected JSON string")
      case None                   => schema(path, "missing tag field")
    case _ => schema(path, "expected tagged target-entrypoint object")

  def writeDependencyKind(value: DependencyKind): ujson.Value = tagged(value.toString)
  def readDependencyKind(value: ujson.Value, path: String): Result[DependencyKind] =
    enumValue(value, path, dependencyKinds)

  def writeDependency(value: DependencyBinding): ujson.Value = obj(
    "kind" -> writeDependencyKind(value.kind),
    "logicalId" -> str(value.logicalId),
    "semanticAbiId" -> str(value.semanticAbiId),
    "schemaId" -> optional(value.schemaId)(str),
    "implementationDigest" -> writeImplementationDigest(value.implementationDigest),
    "target" -> optional(value.target)(str),
    "requiredCapabilities" -> arr(value.requiredCapabilities.map(str))
  )

  def readDependency(value: ujson.Value, path: String): Result[DependencyBinding] =
    for
      fields <- exactObject(
        value,
        path,
        "kind",
        "logicalId",
        "semanticAbiId",
        "schemaId",
        "implementationDigest",
        "target",
        "requiredCapabilities"
      )
      kind <- readDependencyKind(field(fields, "kind"), s"$path.kind")
      logicalId <- string(field(fields, "logicalId"), s"$path.logicalId")
      semanticAbiId <- string(field(fields, "semanticAbiId"), s"$path.semanticAbiId")
      schemaId <- option(field(fields, "schemaId"), s"$path.schemaId") {
        (item, itemPath) => string(item, itemPath)
      }
      implementationDigest <- readImplementationDigest(
        field(fields, "implementationDigest"),
        s"$path.implementationDigest"
      )
      target <- option(field(fields, "target"), s"$path.target") {
        (item, itemPath) => string(item, itemPath)
      }
      capabilities <- vector(field(fields, "requiredCapabilities"), s"$path.requiredCapabilities")(
        (item, itemPath) => string(item, itemPath)
      )
    yield DependencyBinding(
      kind,
      logicalId,
      semanticAbiId,
      schemaId,
      implementationDigest,
      target,
      capabilities
    )

  def writeArtifact(value: ArtifactManifest): ujson.Value = obj(
    "artifactManifestVersion" -> str(value.artifactManifestVersion),
    "controlAbiVersion" -> str(value.controlAbiVersion),
    "apiHash" -> ApiWire.writeApiHash(value.apiHash),
    "target" -> writeTargetProfile(value.target),
    "targetEntrypoints" -> arr(value.targetEntrypoints.map(writeTargetEntrypoint)),
    "programDigest" -> optional(value.programDigest)(writeProgramDigest),
    "artifactDigest" -> optional(value.artifactDigest)(writeArtifactDigest),
    "runtimeVersion" -> str(value.runtimeVersion),
    "dependencyManifest" -> arr(value.dependencyManifest.map(writeDependency)),
    "dependencyProfileDigest" -> writeDependencyDigest(value.dependencyProfileDigest),
    "controlSummaryDigests" -> arr(value.controlSummaryDigests.map(ControlWire.writeSummaryDigest))
  )

  def readArtifact(value: ujson.Value, path: String = "$" ): Result[ArtifactManifest] =
    for
      fields <- exactObject(
        value,
        path,
        "artifactManifestVersion",
        "controlAbiVersion",
        "apiHash",
        "target",
        "targetEntrypoints",
        "programDigest",
        "artifactDigest",
        "runtimeVersion",
        "dependencyManifest",
        "dependencyProfileDigest",
        "controlSummaryDigests"
      )
      manifestVersion <- string(
        field(fields, "artifactManifestVersion"),
        s"$path.artifactManifestVersion"
      )
      controlAbiVersion <- string(field(fields, "controlAbiVersion"), s"$path.controlAbiVersion")
      apiHash <- ApiWire.readApiHash(field(fields, "apiHash"), s"$path.apiHash")
      target <- readTargetProfile(field(fields, "target"), s"$path.target")
      entrypoints <- vector(field(fields, "targetEntrypoints"), s"$path.targetEntrypoints")(
        readTargetEntrypoint
      )
      programDigest <- option(field(fields, "programDigest"), s"$path.programDigest")(
        readProgramDigest
      )
      artifactDigest <- option(field(fields, "artifactDigest"), s"$path.artifactDigest")(
        readArtifactDigest
      )
      runtimeVersion <- string(field(fields, "runtimeVersion"), s"$path.runtimeVersion")
      dependencies <- vector(field(fields, "dependencyManifest"), s"$path.dependencyManifest")(
        readDependency
      )
      dependencyDigest <- readDependencyDigest(
        field(fields, "dependencyProfileDigest"),
        s"$path.dependencyProfileDigest"
      )
      summaryDigests <- vector(
        field(fields, "controlSummaryDigests"),
        s"$path.controlSummaryDigests"
      )(ControlWire.readSummaryDigest)
    yield ArtifactManifest(
      manifestVersion,
      controlAbiVersion,
      apiHash,
      target,
      entrypoints,
      programDigest,
      artifactDigest,
      runtimeVersion,
      dependencies,
      dependencyDigest,
      summaryDigests
    )

  def writeJvmIdentity(
      stableSymbolId: StableSymbolId,
      ownerInternalName: String,
      methodName: String,
      methodDescriptor: String,
      invocationKind: JvmInvocationKind,
      bridgeFlags: Vector[String],
      classLoaderProfile: String
  ): ujson.Value = obj(
    "stableSymbolId" -> ApiWire.writeStableSymbolId(stableSymbolId),
    "ownerInternalName" -> str(ownerInternalName),
    "methodName" -> str(methodName),
    "methodDescriptor" -> str(methodDescriptor),
    "invocationKind" -> writeInvocationKind(invocationKind),
    "bridgeFlags" -> arr(bridgeFlags.map(str)),
    "classLoaderProfile" -> str(classLoaderProfile)
  )

  def writeNamedIdentity(
      stableSymbolId: StableSymbolId,
      externalName: String,
      targetAbi: String
  ): ujson.Value = obj(
    "stableSymbolId" -> ApiWire.writeStableSymbolId(stableSymbolId),
    "externalName" -> str(externalName),
    "targetAbi" -> str(targetAbi)
  )
