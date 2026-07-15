package scalascript.interop.descriptor

private[descriptor] object ControlWire:
  import WireSupport.*

  private val managedKinds = Map(
    "Pure" -> ManagedCallKind.Pure,
    "Effectful" -> ManagedCallKind.Effectful,
    "ManagedControl" -> ManagedCallKind.ManagedControl
  )
  private val foreignBarriers = Map(
    "Precompiled" -> ForeignBarrierKind.Precompiled,
    "Virtual" -> ForeignBarrierKind.Virtual,
    "Reflective" -> ForeignBarrierKind.Reflective,
    "Native" -> ForeignBarrierKind.Native,
    "Resource" -> ForeignBarrierKind.Resource,
    "Async" -> ForeignBarrierKind.Async,
    "Callback" -> ForeignBarrierKind.Callback,
    "Affinity" -> ForeignBarrierKind.Affinity,
    "Unknown" -> ForeignBarrierKind.Unknown
  )
  private val requestedModes = Map(
    "Portable" -> RequestedCodeMode.Portable,
    "ExactArtifact" -> RequestedCodeMode.ExactArtifact
  )

  def writeSummaryDigest(value: ControlSummaryDigest): ujson.Value = writeWrappedString(value.value)
  def readSummaryDigest(value: ujson.Value, path: String): Result[ControlSummaryDigest] =
    wrappedString(value, path)(ControlSummaryDigest.apply)

  def writeManagedKind(value: ManagedCallKind): ujson.Value = tagged(value.toString)
  def readManagedKind(value: ujson.Value, path: String): Result[ManagedCallKind] =
    enumValue(value, path, managedKinds)

  def writeManagedEdge(value: ManagedCallEdge): ujson.Value = obj(
    "caller" -> ApiWire.writeStableSymbolId(value.caller),
    "callee" -> ApiWire.writeStableSymbolId(value.callee),
    "kind" -> writeManagedKind(value.kind)
  )

  def readManagedEdge(value: ujson.Value, path: String): Result[ManagedCallEdge] =
    for
      fields <- exactObject(value, path, "caller", "callee", "kind")
      caller <- ApiWire.readStableSymbolId(field(fields, "caller"), s"$path.caller")
      callee <- ApiWire.readStableSymbolId(field(fields, "callee"), s"$path.callee")
      kind <- readManagedKind(field(fields, "kind"), s"$path.kind")
    yield ManagedCallEdge(caller, callee, kind)

  def writeForeignBarrier(value: ForeignBarrierKind): ujson.Value = tagged(value.toString)
  def readForeignBarrier(value: ujson.Value, path: String): Result[ForeignBarrierKind] =
    enumValue(value, path, foreignBarriers)

  def writeForeignTarget(value: ForeignTarget): ujson.Value = obj(
    "profile" -> str(value.profile),
    "owner" -> str(value.owner),
    "name" -> str(value.name),
    "descriptor" -> optional(value.descriptor)(str)
  )

  def readForeignTarget(value: ujson.Value, path: String): Result[ForeignTarget] =
    for
      fields <- exactObject(value, path, "profile", "owner", "name", "descriptor")
      profile <- string(field(fields, "profile"), s"$path.profile")
      owner <- string(field(fields, "owner"), s"$path.owner")
      name <- string(field(fields, "name"), s"$path.name")
      descriptor <- option(field(fields, "descriptor"), s"$path.descriptor") {
        (item, itemPath) => string(item, itemPath)
      }
    yield ForeignTarget(profile, owner, name, descriptor)

  def writeForeignEdge(value: ForeignCallEdge): ujson.Value = obj(
    "caller" -> ApiWire.writeStableSymbolId(value.caller),
    "target" -> writeForeignTarget(value.target),
    "barrier" -> writeForeignBarrier(value.barrier)
  )

  def readForeignEdge(value: ujson.Value, path: String): Result[ForeignCallEdge] =
    for
      fields <- exactObject(value, path, "caller", "target", "barrier")
      caller <- ApiWire.readStableSymbolId(field(fields, "caller"), s"$path.caller")
      target <- readForeignTarget(field(fields, "target"), s"$path.target")
      barrier <- readForeignBarrier(field(fields, "barrier"), s"$path.barrier")
    yield ForeignCallEdge(caller, target, barrier)

  def writeTailDisposition(value: TailDisposition): ujson.Value = value match
    case TailDisposition.Eligible      => tagged("Eligible")
    case TailDisposition.Barrier(code) => tagged("Barrier", "code" -> str(code))

  def readTailDisposition(value: ujson.Value, path: String): Result[TailDisposition] = value match
    case objectValue: ujson.Obj => objectValue.value.get("tag") match
      case Some(ujson.Str("Eligible")) => tag(value, path).map(_ => TailDisposition.Eligible)
      case Some(ujson.Str("Barrier")) =>
        for
          taggedValue <- tag(value, path, "code")
          (fields, _) = taggedValue
          code <- string(field(fields, "code"), s"$path.code")
        yield TailDisposition.Barrier(code)
      case Some(ujson.Str(other)) => schema(s"$path.tag", s"unsupported tag $other")
      case Some(_)                => schema(s"$path.tag", "expected JSON string")
      case None                   => schema(path, "missing tag field")
    case _ => schema(path, "expected tagged tail-disposition object")

  def writeTailEdge(value: TailEdge): ujson.Value = obj(
    "caller" -> ApiWire.writeStableSymbolId(value.caller),
    "callee" -> ApiWire.writeStableSymbolId(value.callee),
    "disposition" -> writeTailDisposition(value.disposition)
  )

  def readTailEdge(value: ujson.Value, path: String): Result[TailEdge] =
    for
      fields <- exactObject(value, path, "caller", "callee", "disposition")
      caller <- ApiWire.readStableSymbolId(field(fields, "caller"), s"$path.caller")
      callee <- ApiWire.readStableSymbolId(field(fields, "callee"), s"$path.callee")
      disposition <- readTailDisposition(field(fields, "disposition"), s"$path.disposition")
    yield TailEdge(caller, callee, disposition)

  def writeRequestedMode(value: RequestedCodeMode): ujson.Value = tagged(value.toString)
  def readRequestedMode(value: ujson.Value, path: String): Result[RequestedCodeMode] =
    enumValue(value, path, requestedModes)

  def writeFrameDurability(value: FrameSlotDurability): ujson.Value = value match
    case FrameSlotDurability.Durable => tagged("Durable")
    case FrameSlotDurability.DurableRef(providerId) =>
      tagged("DurableRef", "providerId" -> str(providerId))
    case FrameSlotDurability.Unsavable(code) =>
      tagged("Unsavable", "code" -> str(code))

  def readFrameDurability(value: ujson.Value, path: String): Result[FrameSlotDurability] = value match
    case objectValue: ujson.Obj => objectValue.value.get("tag") match
      case Some(ujson.Str("Durable")) => tag(value, path).map(_ => FrameSlotDurability.Durable)
      case Some(ujson.Str(caseName @ ("DurableRef" | "Unsavable"))) =>
        val payloadName = if caseName == "DurableRef" then "providerId" else "code"
        for
          taggedValue <- tag(value, path, payloadName)
          (fields, _) = taggedValue
          payload <- string(field(fields, payloadName), s"$path.$payloadName")
        yield if caseName == "DurableRef" then FrameSlotDurability.DurableRef(payload)
        else FrameSlotDurability.Unsavable(payload)
      case Some(ujson.Str(other)) => schema(s"$path.tag", s"unsupported tag $other")
      case Some(_)                => schema(s"$path.tag", "expected JSON string")
      case None                   => schema(path, "missing tag field")
    case _ => schema(path, "expected tagged frame-durability object")

  def writeFrameSlot(value: FrameSlot): ujson.Value = obj(
    "slotId" -> str(value.slotId),
    "tpe" -> TypeWire.writeType(value.tpe),
    "durability" -> writeFrameDurability(value.durability)
  )

  def readFrameSlot(value: ujson.Value, path: String): Result[FrameSlot] =
    for
      fields <- exactObject(value, path, "slotId", "tpe", "durability")
      slotId <- string(field(fields, "slotId"), s"$path.slotId")
      tpe <- TypeWire.readType(field(fields, "tpe"), s"$path.tpe")
      durability <- readFrameDurability(field(fields, "durability"), s"$path.durability")
    yield FrameSlot(slotId, tpe, durability)

  def writeFrameSchema(value: FrameSchema): ujson.Value = obj(
    "schemaId" -> str(value.schemaId),
    "typeParameters" -> arr(value.typeParameters.map(TypeWire.writeTypeParameter)),
    "slots" -> arr(value.slots.map(writeFrameSlot))
  )

  def readFrameSchema(value: ujson.Value, path: String): Result[FrameSchema] =
    for
      fields <- exactObject(value, path, "schemaId", "typeParameters", "slots")
      schemaId <- string(field(fields, "schemaId"), s"$path.schemaId")
      typeParameters <- vector(field(fields, "typeParameters"), s"$path.typeParameters")(
        TypeWire.readTypeParameter
      )
      slots <- vector(field(fields, "slots"), s"$path.slots")(readFrameSlot)
    yield FrameSchema(schemaId, typeParameters, slots)

  def writeBarrier(value: CaptureBarrierSummary): ujson.Value = obj(
    "siteId" -> str(value.siteId),
    "owner" -> ApiWire.writeStableSymbolId(value.owner),
    "category" -> str(value.category),
    "detail" -> str(value.detail)
  )

  def readBarrier(value: ujson.Value, path: String): Result[CaptureBarrierSummary] =
    for
      fields <- exactObject(value, path, "siteId", "owner", "category", "detail")
      siteId <- string(field(fields, "siteId"), s"$path.siteId")
      owner <- ApiWire.readStableSymbolId(field(fields, "owner"), s"$path.owner")
      category <- string(field(fields, "category"), s"$path.category")
      detail <- string(field(fields, "detail"), s"$path.detail")
    yield CaptureBarrierSummary(siteId, owner, category, detail)

  def writeSaveSite(value: SaveSiteSummary): ujson.Value = obj(
    "siteId" -> str(value.siteId),
    "owner" -> ApiWire.writeStableSymbolId(value.owner),
    "requestedCodeMode" -> writeRequestedMode(value.requestedCodeMode),
    "frameSchemaId" -> str(value.frameSchemaId),
    "stablePromptIds" -> arr(value.stablePromptIds.map(str)),
    "firstBarrier" -> optional(value.firstBarrier)(writeBarrier)
  )

  def readSaveSite(value: ujson.Value, path: String): Result[SaveSiteSummary] =
    for
      fields <- exactObject(
        value,
        path,
        "siteId",
        "owner",
        "requestedCodeMode",
        "frameSchemaId",
        "stablePromptIds",
        "firstBarrier"
      )
      siteId <- string(field(fields, "siteId"), s"$path.siteId")
      owner <- ApiWire.readStableSymbolId(field(fields, "owner"), s"$path.owner")
      mode <- readRequestedMode(field(fields, "requestedCodeMode"), s"$path.requestedCodeMode")
      frameSchemaId <- string(field(fields, "frameSchemaId"), s"$path.frameSchemaId")
      promptIds <- vector(field(fields, "stablePromptIds"), s"$path.stablePromptIds")(
        (item, itemPath) => string(item, itemPath)
      )
      firstBarrier <- option(field(fields, "firstBarrier"), s"$path.firstBarrier")(readBarrier)
    yield SaveSiteSummary(siteId, owner, mode, frameSchemaId, promptIds, firstBarrier)

  def writeControl(value: ControlSummary, includeDigest: Boolean = true): ujson.Value =
    val fields = Vector(
      "schemaVersion" -> str(value.schemaVersion),
      "controlAbiVersion" -> str(value.controlAbiVersion),
      "moduleId" -> str(value.moduleId),
      "apiHash" -> ApiWire.writeApiHash(value.apiHash),
      "managedCallEdges" -> arr(value.managedCallEdges.map(writeManagedEdge)),
      "foreignCallEdges" -> arr(value.foreignCallEdges.map(writeForeignEdge)),
      "tailEdges" -> arr(value.tailEdges.map(writeTailEdge)),
      "saveSites" -> arr(value.saveSites.map(writeSaveSite)),
      "frameSchemas" -> arr(value.frameSchemas.map(writeFrameSchema)),
      "captureBarriers" -> arr(value.captureBarriers.map(writeBarrier))
    )
    if includeDigest then obj((fields :+ ("summaryDigest" -> writeSummaryDigest(value.summaryDigest)))* )
    else obj(fields*)

  def readControl(value: ujson.Value, path: String = "$" ): Result[ControlSummary] =
    for
      fields <- exactObject(
        value,
        path,
        "schemaVersion",
        "controlAbiVersion",
        "moduleId",
        "apiHash",
        "summaryDigest",
        "managedCallEdges",
        "foreignCallEdges",
        "tailEdges",
        "saveSites",
        "frameSchemas",
        "captureBarriers"
      )
      schemaVersion <- string(field(fields, "schemaVersion"), s"$path.schemaVersion")
      controlAbiVersion <- string(field(fields, "controlAbiVersion"), s"$path.controlAbiVersion")
      moduleId <- string(field(fields, "moduleId"), s"$path.moduleId")
      apiHash <- ApiWire.readApiHash(field(fields, "apiHash"), s"$path.apiHash")
      summaryDigest <- readSummaryDigest(field(fields, "summaryDigest"), s"$path.summaryDigest")
      managedEdges <- vector(field(fields, "managedCallEdges"), s"$path.managedCallEdges")(
        readManagedEdge
      )
      foreignEdges <- vector(field(fields, "foreignCallEdges"), s"$path.foreignCallEdges")(
        readForeignEdge
      )
      tailEdges <- vector(field(fields, "tailEdges"), s"$path.tailEdges")(readTailEdge)
      saveSites <- vector(field(fields, "saveSites"), s"$path.saveSites")(readSaveSite)
      frameSchemas <- vector(field(fields, "frameSchemas"), s"$path.frameSchemas")(readFrameSchema)
      barriers <- vector(field(fields, "captureBarriers"), s"$path.captureBarriers")(readBarrier)
    yield ControlSummary(
      schemaVersion,
      controlAbiVersion,
      moduleId,
      apiHash,
      summaryDigest,
      managedEdges,
      foreignEdges,
      tailEdges,
      saveSites,
      frameSchemas,
      barriers
    )
