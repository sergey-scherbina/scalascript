package scalascript.typeddata

import scalascript.wire.*
import scalascript.wire.cbor.CborWireCodec
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec

import java.nio.charset.StandardCharsets

/** Binary wire bridge for distributed Dataset / MapReduce partitions.
 *
 *  `DatasetCodec[A]` still owns typed element serialization. This object wraps
 *  the existing `DatasetWirePartition` payload in the shared v1.62
 *  `WireEnvelope` so the same logical partition can move as JSON, MsgPack, or
 *  CBOR and can be chunked before actor/HTTP/WebSocket transport framing.
 */
object DatasetWire:
  val Protocol = "dataset"
  val KindPartition = "partition"
  val KindPartitionChunk = "partition-chunk"
  val SchemaId = "dataset-wire-partition:v1"
  val DefaultMaxFrameBytes: Int = 16 * 1024 * 1024

  def toWireValue(partition: DatasetWirePartition): WireValue =
    WireValue.Object("DatasetWirePartition", Vector(
      "partitionId" -> WireValue.Int64(partition.partitionId.toLong),
      "values"      -> WireValue.Lst(partition.values.map(jsonToWire))
    ))

  def fromWireValue(value: WireValue): Either[WireDecodeError, DatasetWirePartition] =
    value match
      case WireValue.Object("DatasetWirePartition", fields) =>
        val m = fields.toMap
        for
          partitionId <- m.get("partitionId") match
            case Some(WireValue.Int64(n)) => Right(n.toInt)
            case Some(other) => Left(WireDecodeError.TypeMismatch("partitionId int64", WireValue.kindOf(other)))
            case None => Left(WireDecodeError.MissingField("partitionId", "DatasetWirePartition"))
          values <- m.get("values") match
            case Some(WireValue.Lst(items)) =>
              items.foldLeft[Either[WireDecodeError, Vector[JsonValue]]](Right(Vector.empty)) { (acc, item) =>
                acc.flatMap(xs => wireToJson(item).map(xs :+ _))
              }
            case Some(other) => Left(WireDecodeError.TypeMismatch("values list", WireValue.kindOf(other)))
            case None => Left(WireDecodeError.MissingField("values", "DatasetWirePartition"))
        yield DatasetWirePartition(partitionId, values)
      case other => Left(WireDecodeError.TypeMismatch("DatasetWirePartition", WireValue.kindOf(other)))

  def envelope(
      partition:     DatasetWirePartition,
      format:        String,
      correlationId: Option[String] = None,
      chunkHeaders:  Map[String, String] = Map.empty,
      chunked:       Boolean = false
  ): WireEnvelope =
    WireEnvelope(
      protocol      = Protocol,
      protocolVer   = 1,
      format        = format,
      kind          = if chunked then KindPartitionChunk else KindPartition,
      correlationId = correlationId,
      schemaId      = Some(SchemaId),
      flags         = if chunked then Set("chunked") else Set.empty,
      headers       = chunkHeaders ++ Map(
        "partition-id" -> partition.partitionId.toString,
        "value-count"  -> partition.values.size.toString
      ),
      payload       = toWireValue(partition),
    )

  def encodePartition(
      partition:     DatasetWirePartition,
      format:        String = WireFormat.Json,
      maxFrameBytes: Int = DefaultMaxFrameBytes,
      correlationId: Option[String] = None
  ): Either[WireDecodeError, Array[Byte]] =
    encodeEnvelope(envelope(partition, format, correlationId), maxFrameBytes)

  def decodePartition(
      bytes:         Array[Byte],
      format:        String,
      maxFrameBytes: Int = DefaultMaxFrameBytes
  ): Either[WireDecodeError, DatasetWirePartition] =
    decodeEnvelope(bytes, format, maxFrameBytes).flatMap { env =>
      if env.protocol != Protocol then Left(WireDecodeError.MalformedInput(s"expected dataset protocol, got '${env.protocol}'"))
      else if env.kind != KindPartition && env.kind != KindPartitionChunk then Left(WireDecodeError.MalformedInput(s"expected dataset partition, got '${env.kind}'"))
      else fromWireValue(env.payload)
    }

  def encodePartitionChunks(
      partition:     DatasetWirePartition,
      format:        String,
      maxFrameBytes: Int,
      chunkId:       String,
      correlationId: Option[String] = None
  ): Either[WireDecodeError, Vector[Array[Byte]]] =
    encodePartition(partition, format, maxFrameBytes, correlationId) match
      case Right(bytes) => Right(Vector(bytes))
      case Left(_: WireDecodeError.SizeExceeded) =>
        splitValues(partition, format, maxFrameBytes, chunkId, correlationId)
      case Left(err) => Left(err)

  def decodePartitionChunks(
      chunks:        Iterable[Array[Byte]],
      format:        String,
      maxFrameBytes: Int = DefaultMaxFrameBytes
  ): Either[WireDecodeError, DatasetWirePartition] =
    val decoded = chunks.iterator.foldLeft[Either[WireDecodeError, Vector[(Int, Int, DatasetWirePartition)]]](Right(Vector.empty)) {
      case (Right(acc), bytes) =>
        decodeEnvelope(bytes, format, maxFrameBytes).flatMap { env =>
          for
            partition <- decodePartition(bytes, format, maxFrameBytes)
            index <- env.headers.get("chunk-index").flatMap(_.toIntOption)
              .toRight(WireDecodeError.MalformedInput("missing chunk-index"))
            count <- env.headers.get("chunk-count").flatMap(_.toIntOption)
              .toRight(WireDecodeError.MalformedInput("missing chunk-count"))
          yield acc :+ (index, count, partition)
        }
      case (left @ Left(_), _) => left
    }
    decoded.flatMap { parts =>
      if parts.isEmpty then Left(WireDecodeError.MalformedInput("no dataset partition chunks"))
      else
        val count = parts.head._2
        val ids = parts.map(_._3.partitionId).distinct
        if parts.exists(_._2 != count) then Left(WireDecodeError.MalformedInput("inconsistent chunk-count"))
        else if parts.size != count then Left(WireDecodeError.MalformedInput(s"expected $count chunks, got ${parts.size}"))
        else if ids.size != 1 then Left(WireDecodeError.MalformedInput("chunks contain multiple partition ids"))
        else
          val values = parts.sortBy(_._1).flatMap(_._3.values)
          Right(DatasetWirePartition(ids.head, values))
    }

  def encodeEnvelope(env: WireEnvelope, maxFrameBytes: Int = DefaultMaxFrameBytes): Either[WireDecodeError, Array[Byte]] =
    if maxFrameBytes <= 0 then Left(WireDecodeError.SizeExceeded(maxFrameBytes.toLong, 0L))
    else
      val bytes = env.format match
        case WireFormat.Json    => JsonWireCodec.encodeEnvelope(env).getBytes(StandardCharsets.UTF_8)
        case WireFormat.MsgPack => MsgPackWireCodec.encodeEnvelope(env)
        case WireFormat.Cbor    => CborWireCodec.encodeEnvelope(env)
        case other => return Left(WireDecodeError.MalformedInput(s"unsupported dataset wire format '$other'"))
      if bytes.length > maxFrameBytes then Left(WireDecodeError.SizeExceeded(maxFrameBytes.toLong, bytes.length.toLong))
      else Right(bytes)

  def decodeEnvelope(bytes: Array[Byte], format: String, maxFrameBytes: Int = DefaultMaxFrameBytes): Either[WireDecodeError, WireEnvelope] =
    if bytes.length > maxFrameBytes then Left(WireDecodeError.SizeExceeded(maxFrameBytes.toLong, bytes.length.toLong))
    else format match
      case WireFormat.Json    => JsonWireCodec.decodeEnvelope(new String(bytes, StandardCharsets.UTF_8))
      case WireFormat.MsgPack => MsgPackWireCodec.decodeEnvelope(bytes)
      case WireFormat.Cbor    => CborWireCodec.decodeEnvelope(bytes)
      case other => Left(WireDecodeError.MalformedInput(s"unsupported dataset wire format '$other'"))

  private def splitValues(
      partition:     DatasetWirePartition,
      format:        String,
      maxFrameBytes: Int,
      chunkId:       String,
      correlationId: Option[String]
  ): Either[WireDecodeError, Vector[Array[Byte]]] =
    val groups = Vector.newBuilder[Vector[JsonValue]]
    var current = Vector.empty[JsonValue]
    def encodedSize(values: Vector[JsonValue], count: Int = 0): Either[WireDecodeError, Int] =
      val part = DatasetWirePartition(partition.partitionId, values)
      val env = envelope(
        part,
        format,
        correlationId,
        WireEnvelope.chunkHeaders(chunkId, 0, count) + ("partition-id" -> partition.partitionId.toString),
        chunked = true
      )
      encodeEnvelope(env, Int.MaxValue).map(_.length)
    partition.values.foreach { value =>
      val candidate = current :+ value
      encodedSize(candidate) match
        case Right(size) if size <= maxFrameBytes => current = candidate
        case _ =>
          if current.isEmpty then groups += Vector(value)
          else
            groups += current
            current = Vector(value)
    }
    if current.nonEmpty then groups += current
    val grouped = groups.result()
    val count = grouped.size
    grouped.zipWithIndex.foldLeft[Either[WireDecodeError, Vector[Array[Byte]]]](Right(Vector.empty)) {
      case (Right(acc), (values, index)) =>
        val part = DatasetWirePartition(partition.partitionId, values)
        val headers = WireEnvelope.chunkHeaders(chunkId, index, count) + ("partition-id" -> partition.partitionId.toString)
        encodeEnvelope(envelope(part, format, correlationId, headers, chunked = true), maxFrameBytes).map(acc :+ _)
      case (left @ Left(_), _) => left
    }

  private def jsonToWire(value: JsonValue): WireValue = value match
    case JsonValue.Null       => WireValue.Null
    case JsonValue.Bool(v)    => WireValue.Bool(v)
    case JsonValue.Num(v)     => WireValue.Object("JsonNumber", Vector("value" -> WireValue.Str(v.toString)))
    case JsonValue.Str(v)     => WireValue.Str(v)
    case JsonValue.Arr(vs)    => WireValue.Lst(vs.map(jsonToWire))
    case JsonValue.Obj(fs)    => WireValue.Object("JsonObject", fs.toVector.sortBy(_._1).map((k, v) => k -> jsonToWire(v)))

  private def wireToJson(value: WireValue): Either[WireDecodeError, JsonValue] = value match
    case WireValue.Null       => Right(JsonValue.Null)
    case WireValue.Bool(v)    => Right(JsonValue.Bool(v))
    case WireValue.Str(v)     => Right(JsonValue.Str(v))
    case WireValue.Lst(vs)    =>
      vs.foldLeft[Either[WireDecodeError, Vector[JsonValue]]](Right(Vector.empty)) { (acc, item) =>
        acc.flatMap(xs => wireToJson(item).map(xs :+ _))
      }.map(JsonValue.Arr(_))
    case WireValue.Object("JsonNumber", fields) =>
      fields.toMap.get("value") match
        case Some(WireValue.Str(raw)) =>
          try Right(JsonValue.Num(BigDecimal(raw)))
          catch case _: NumberFormatException => Left(WireDecodeError.MalformedInput(s"invalid JSON number '$raw'"))
        case Some(other) => Left(WireDecodeError.TypeMismatch("JsonNumber.value string", WireValue.kindOf(other)))
        case None => Left(WireDecodeError.MissingField("value", "JsonNumber"))
    case WireValue.Object("JsonObject", fields) =>
      fields.foldLeft[Either[WireDecodeError, Map[String, JsonValue]]](Right(Map.empty)) {
        case (Right(acc), (name, item)) => wireToJson(item).map(v => acc + (name -> v))
        case (left @ Left(_), _) => left
      }.map(JsonValue.Obj(_))
    case other => Left(WireDecodeError.TypeMismatch("JsonValue", WireValue.kindOf(other)))
