package scalascript.wire.dstream

import scalascript.wire.{WireCodec, WireDecodeError, WireEnvelope, WireValue}

/** Wire protocol messages for the native DStream runner.
 *
 *  All message kinds flow through `WireEnvelope` with `protocol = "dstream"`.
 *  The external Spark/Kafka/Flink/Beam engine protocols are untouched — this
 *  layer covers only the ScalaScript-native distributed runner.
 *
 *  Spec: docs/distributed-wire-protocol.md §Phase 5 */
sealed trait DStreamMsg

object DStreamMsg:

  /** A batch of encoded stream elements from one pipeline stage. */
  case class ElementBatch(
    pipelineId: String,
    stageId:    String,
    elements:   Vector[WireValue],
    isFinal:    Boolean,
  ) extends DStreamMsg

  /** Event-time watermark — all elements with `timestampMs < value` have arrived. */
  case class Watermark(
    pipelineId:   String,
    stageId:      String,
    timestampMs:  Long,
    sideInputTag: Option[String],
  ) extends DStreamMsg

  /** Window trigger signal — fire a window computation. */
  case class Trigger(
    pipelineId: String,
    windowId:   String,
    kind:       TriggerKind,
    data:       Option[WireValue],
  ) extends DStreamMsg

  /** Broadcast side input data delivered to a stage. */
  case class SideInput(
    pipelineId: String,
    stageId:    String,
    tag:        String,
    elements:   Vector[WireValue],
  ) extends DStreamMsg

  /** Tagged side output elements emitted by a stage. */
  case class SideOutput(
    pipelineId: String,
    stageId:    String,
    tag:        String,
    elements:   Vector[WireValue],
  ) extends DStreamMsg

  /** Checkpoint metadata for state recovery. */
  case class CheckpointMetadata(
    pipelineId:    String,
    checkpointId:  String,
    sequenceNum:   Long,
    stateKeys:     Map[String, String],
    offsetsJson:   String,
  ) extends DStreamMsg

  /** Failure report from a pipeline stage. */
  case class DStreamError(
    pipelineId: String,
    stageId:    String,
    code:       String,
    message:    String,
    cause:      Option[String],
  ) extends DStreamMsg

// ── Trigger kinds ─────────────────────────────────────────────────────────

enum TriggerKind:
  case EventTime, ProcessingTime, CountBased, AfterWatermark

object TriggerKind:
  def fromString(s: String): Either[String, TriggerKind] = s match
    case "EventTime"      => Right(EventTime)
    case "ProcessingTime" => Right(ProcessingTime)
    case "CountBased"     => Right(CountBased)
    case "AfterWatermark" => Right(AfterWatermark)
    case other            => Left(s"Unknown TriggerKind: $other")

// ── WireCodec instances ───────────────────────────────────────────────────

object DStreamWireCodec:

  given WireCodec[TriggerKind] with
    def encode(v: TriggerKind): WireValue = WireValue.Str(v.toString)
    def decode(w: WireValue): Either[WireDecodeError, TriggerKind] = w match
      case WireValue.Str(s) =>
        TriggerKind.fromString(s).left.map(WireDecodeError.MalformedInput(_))
      case other =>
        Left(WireDecodeError.TypeMismatch("string", WireValue.kindOf(other)))
    val schemaId = "dstream.TriggerKind:0"

  given WireCodec[DStreamMsg.ElementBatch] with
    def encode(v: DStreamMsg.ElementBatch): WireValue =
      WireValue.Object("ElementBatch", Vector(
        "pipelineId" -> WireValue.Str(v.pipelineId),
        "stageId"    -> WireValue.Str(v.stageId),
        "elements"   -> WireValue.Lst(v.elements),
        "isFinal"    -> WireValue.Bool(v.isFinal),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.ElementBatch] =
      w.asObject("ElementBatch") { fields =>
        for
          pid   <- fields.str("pipelineId")
          sid   <- fields.str("stageId")
          elems <- fields.lst("elements")
          fin   <- fields.bool("isFinal")
        yield DStreamMsg.ElementBatch(pid, sid, elems, fin)
      }
    val schemaId = "dstream.ElementBatch:0"

  given WireCodec[DStreamMsg.Watermark] with
    def encode(v: DStreamMsg.Watermark): WireValue =
      WireValue.Object("Watermark", Vector(
        "pipelineId"   -> WireValue.Str(v.pipelineId),
        "stageId"      -> WireValue.Str(v.stageId),
        "timestampMs"  -> WireValue.Int64(v.timestampMs),
        "sideInputTag" -> v.sideInputTag.fold[WireValue](WireValue.Null)(WireValue.Str(_)),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.Watermark] =
      w.asObject("Watermark") { fields =>
        for
          pid  <- fields.str("pipelineId")
          sid  <- fields.str("stageId")
          ts   <- fields.int64("timestampMs")
          tag  <- fields.optStr("sideInputTag")
        yield DStreamMsg.Watermark(pid, sid, ts, tag)
      }
    val schemaId = "dstream.Watermark:0"

  given WireCodec[DStreamMsg.Trigger] with
    val kindCodec = summon[WireCodec[TriggerKind]]
    def encode(v: DStreamMsg.Trigger): WireValue =
      WireValue.Object("Trigger", Vector(
        "pipelineId" -> WireValue.Str(v.pipelineId),
        "windowId"   -> WireValue.Str(v.windowId),
        "kind"       -> kindCodec.encode(v.kind),
        "data"       -> v.data.fold[WireValue](WireValue.Null)(identity),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.Trigger] =
      w.asObject("Trigger") { fields =>
        for
          pid  <- fields.str("pipelineId")
          wid  <- fields.str("windowId")
          kind <- fields.field("kind").flatMap(kindCodec.decode)
          data <- fields.optField("data")
        yield DStreamMsg.Trigger(pid, wid, kind, data)
      }
    val schemaId = "dstream.Trigger:0"

  given WireCodec[DStreamMsg.SideInput] with
    def encode(v: DStreamMsg.SideInput): WireValue =
      WireValue.Object("SideInput", Vector(
        "pipelineId" -> WireValue.Str(v.pipelineId),
        "stageId"    -> WireValue.Str(v.stageId),
        "tag"        -> WireValue.Str(v.tag),
        "elements"   -> WireValue.Lst(v.elements),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.SideInput] =
      w.asObject("SideInput") { fields =>
        for
          pid   <- fields.str("pipelineId")
          sid   <- fields.str("stageId")
          tag   <- fields.str("tag")
          elems <- fields.lst("elements")
        yield DStreamMsg.SideInput(pid, sid, tag, elems)
      }
    val schemaId = "dstream.SideInput:0"

  given WireCodec[DStreamMsg.SideOutput] with
    def encode(v: DStreamMsg.SideOutput): WireValue =
      WireValue.Object("SideOutput", Vector(
        "pipelineId" -> WireValue.Str(v.pipelineId),
        "stageId"    -> WireValue.Str(v.stageId),
        "tag"        -> WireValue.Str(v.tag),
        "elements"   -> WireValue.Lst(v.elements),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.SideOutput] =
      w.asObject("SideOutput") { fields =>
        for
          pid   <- fields.str("pipelineId")
          sid   <- fields.str("stageId")
          tag   <- fields.str("tag")
          elems <- fields.lst("elements")
        yield DStreamMsg.SideOutput(pid, sid, tag, elems)
      }
    val schemaId = "dstream.SideOutput:0"

  given WireCodec[DStreamMsg.CheckpointMetadata] with
    def encode(v: DStreamMsg.CheckpointMetadata): WireValue =
      WireValue.Object("CheckpointMetadata", Vector(
        "pipelineId"   -> WireValue.Str(v.pipelineId),
        "checkpointId" -> WireValue.Str(v.checkpointId),
        "sequenceNum"  -> WireValue.Int64(v.sequenceNum),
        "stateKeys"    -> WireValue.Map(
          v.stateKeys.map { case (k, v) => WireValue.Str(k) -> WireValue.Str(v) }.toVector
        ),
        "offsetsJson"  -> WireValue.Str(v.offsetsJson),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.CheckpointMetadata] =
      w.asObject("CheckpointMetadata") { fields =>
        for
          pid    <- fields.str("pipelineId")
          cid    <- fields.str("checkpointId")
          seq    <- fields.int64("sequenceNum")
          keys   <- fields.strMap("stateKeys")
          offsets <- fields.str("offsetsJson")
        yield DStreamMsg.CheckpointMetadata(pid, cid, seq, keys, offsets)
      }
    val schemaId = "dstream.CheckpointMetadata:0"

  given WireCodec[DStreamMsg.DStreamError] with
    def encode(v: DStreamMsg.DStreamError): WireValue =
      WireValue.Object("DStreamError", Vector(
        "pipelineId" -> WireValue.Str(v.pipelineId),
        "stageId"    -> WireValue.Str(v.stageId),
        "code"       -> WireValue.Str(v.code),
        "message"    -> WireValue.Str(v.message),
        "cause"      -> v.cause.fold[WireValue](WireValue.Null)(WireValue.Str(_)),
      ))
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg.DStreamError] =
      w.asObject("DStreamError") { fields =>
        for
          pid   <- fields.str("pipelineId")
          sid   <- fields.str("stageId")
          code  <- fields.str("code")
          msg   <- fields.str("message")
          cause <- fields.optStr("cause")
        yield DStreamMsg.DStreamError(pid, sid, code, msg, cause)
      }
    val schemaId = "dstream.DStreamError:0"

  given WireCodec[DStreamMsg] with
    def encode(v: DStreamMsg): WireValue = v match
      case m: DStreamMsg.ElementBatch      => summon[WireCodec[DStreamMsg.ElementBatch]].encode(m)
      case m: DStreamMsg.Watermark         => summon[WireCodec[DStreamMsg.Watermark]].encode(m)
      case m: DStreamMsg.Trigger           => summon[WireCodec[DStreamMsg.Trigger]].encode(m)
      case m: DStreamMsg.SideInput         => summon[WireCodec[DStreamMsg.SideInput]].encode(m)
      case m: DStreamMsg.SideOutput        => summon[WireCodec[DStreamMsg.SideOutput]].encode(m)
      case m: DStreamMsg.CheckpointMetadata => summon[WireCodec[DStreamMsg.CheckpointMetadata]].encode(m)
      case m: DStreamMsg.DStreamError      => summon[WireCodec[DStreamMsg.DStreamError]].encode(m)
    def decode(w: WireValue): Either[WireDecodeError, DStreamMsg] =
      w match
        case WireValue.Object(typeName, _) => typeName match
          case "ElementBatch"       => summon[WireCodec[DStreamMsg.ElementBatch]].decode(w)
          case "Watermark"          => summon[WireCodec[DStreamMsg.Watermark]].decode(w)
          case "Trigger"            => summon[WireCodec[DStreamMsg.Trigger]].decode(w)
          case "SideInput"          => summon[WireCodec[DStreamMsg.SideInput]].decode(w)
          case "SideOutput"         => summon[WireCodec[DStreamMsg.SideOutput]].decode(w)
          case "CheckpointMetadata" => summon[WireCodec[DStreamMsg.CheckpointMetadata]].decode(w)
          case "DStreamError"       => summon[WireCodec[DStreamMsg.DStreamError]].decode(w)
          case other                => Left(WireDecodeError.MalformedInput(s"Unknown DStreamMsg type: $other"))
        case other => Left(WireDecodeError.TypeMismatch("object", WireValue.kindOf(other)))
    val schemaId = "dstream.DStreamMsg:0"

// ── WireEnvelope helpers ──────────────────────────────────────────────────

object DStreamEnvelope:

  val Protocol    = "dstream"
  val ProtocolVer = 1

  private def kindOf(msg: DStreamMsg): String = msg match
    case _: DStreamMsg.ElementBatch       => "element-batch"
    case _: DStreamMsg.Watermark          => "watermark"
    case _: DStreamMsg.Trigger            => "trigger"
    case _: DStreamMsg.SideInput          => "side-input"
    case _: DStreamMsg.SideOutput         => "side-output"
    case _: DStreamMsg.CheckpointMetadata => "checkpoint"
    case _: DStreamMsg.DStreamError       => "error"

  def apply(msg: DStreamMsg, format: String): WireEnvelope =
    import DStreamWireCodec.given
    val codec = summon[WireCodec[DStreamMsg]]
    WireEnvelope(
      protocol      = Protocol,
      protocolVer   = ProtocolVer,
      format        = format,
      kind          = kindOf(msg),
      correlationId = None,
      schemaId      = Some(codec.schemaId),
      flags         = Set.empty,
      headers       = Map.empty,
      payload       = codec.encode(msg),
    )

  def decode(env: WireEnvelope): Either[WireDecodeError, DStreamMsg] =
    if env.protocol != Protocol then
      Left(WireDecodeError.MalformedInput(s"Expected protocol '$Protocol', got '${env.protocol}'"))
    else
      import DStreamWireCodec.given
      summon[WireCodec[DStreamMsg]].decode(env.payload)

// ── WireValue field-access helpers (package-private) ──────────────────────

extension (wv: WireValue)
  private[dstream] def asObject[A](expectedType: String)(
    f: FieldMap => Either[WireDecodeError, A]
  ): Either[WireDecodeError, A] =
    wv match
      case WireValue.Object(t, fields) =>
        if t != expectedType then
          Left(WireDecodeError.MalformedInput(s"Expected '$expectedType', got '$t'"))
        else
          f(FieldMap(fields.toMap))
      case other =>
        Left(WireDecodeError.TypeMismatch("object", WireValue.kindOf(other)))

private[dstream] class FieldMap(fields: Map[String, WireValue]):
  def field(name: String): Either[WireDecodeError, WireValue] =
    fields.get(name).toRight(WireDecodeError.MissingField(name, "DStreamMsg"))

  def optField(name: String): Either[WireDecodeError, Option[WireValue]] =
    Right(fields.get(name).filterNot(_ == WireValue.Null))

  def str(name: String): Either[WireDecodeError, String] =
    field(name).flatMap:
      case WireValue.Str(s) => Right(s)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:string", WireValue.kindOf(other)))

  def optStr(name: String): Either[WireDecodeError, Option[String]] =
    fields.get(name) match
      case None | Some(WireValue.Null) => Right(None)
      case Some(WireValue.Str(s))      => Right(Some(s))
      case Some(other) => Left(WireDecodeError.TypeMismatch(s"$name:string", WireValue.kindOf(other)))

  def bool(name: String): Either[WireDecodeError, Boolean] =
    field(name).flatMap:
      case WireValue.Bool(b) => Right(b)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:bool", WireValue.kindOf(other)))

  def int64(name: String): Either[WireDecodeError, Long] =
    field(name).flatMap:
      case WireValue.Int64(n)   => Right(n)
      case WireValue.Float64(d) => Right(d.toLong)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:int64", WireValue.kindOf(other)))

  def lst(name: String): Either[WireDecodeError, Vector[WireValue]] =
    field(name).flatMap:
      case WireValue.Lst(vs) => Right(vs)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:list", WireValue.kindOf(other)))

  def strMap(name: String): Either[WireDecodeError, Map[String, String]] =
    field(name).flatMap:
      case WireValue.Map(entries) =>
        entries.foldLeft[Either[WireDecodeError, Vector[(String, String)]]](Right(Vector.empty)) {
          case (acc, (WireValue.Str(k), WireValue.Str(v))) =>
            acc.map(_ :+ (k -> v))
          case _ =>
            Left(WireDecodeError.MalformedInput(s"Expected string->string map in '$name'"))
        }.map(_.toMap)
      case other => Left(WireDecodeError.TypeMismatch(s"$name:map", WireValue.kindOf(other)))
