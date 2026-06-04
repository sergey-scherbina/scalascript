package scalascript.wire.msgpack

import scalascript.wire.*
import upack.*
import upickle.core.LinkedHashMap

/** MsgPack encoder/decoder for `WireValue` using upickle's `upack`.
 *
 *  Spec: specs/distributed-wire-protocol.md §Phase 1 */
object MsgPackWireCodec:

  private def mkObj(pairs: (Msg, Msg)*): Obj =
    val m = LinkedHashMap[Msg, Msg]()
    pairs.foreach { case (k, v) => m.put(k, v) }
    Obj(m)

  def encodeToMsg(v: WireValue): Msg = v match
    case WireValue.Null       => Null
    case WireValue.Unit       => mkObj(Str("$unit") -> True)
    case WireValue.Bool(b)    => if b then True else False
    case WireValue.Int64(n)   => Int64(n)
    case WireValue.Float64(d) => Float64(d)
    case WireValue.Str(s)     => Str(s)
    case WireValue.Bytes(bs)  => Binary(bs)
    case WireValue.Lst(vs)    => Arr(vs.map(encodeToMsg)*)
    case WireValue.Map(entries) =>
      mkObj(Str("$map") -> Arr(entries.map { case (k, v) => Arr(encodeToMsg(k), encodeToMsg(v)) }*))
    case WireValue.Object(typeName, fields) =>
      val m = LinkedHashMap[Msg, Msg]()
      m.put(Str("$type"), Str(typeName))
      fields.foreach { case (name, value) => m.put(Str(name), encodeToMsg(value)) }
      Obj(m)
    case WireValue.Tuple(values) =>
      mkObj(Str("$tuple") -> Arr(values.map(encodeToMsg)*))
    case WireValue.Enum(typeName, caseName, value) =>
      val m = LinkedHashMap[Msg, Msg]()
      m.put(Str("$type"), Str(typeName))
      m.put(Str("$case"), Str(caseName))
      value.foreach(v => m.put(Str("$val"), encodeToMsg(v)))
      Obj(m)
    case WireValue.Pid(nodeId, localId) =>
      mkObj(Str("$pid") -> mkObj(
        Str("nodeId")  -> Str(nodeId),
        Str("localId") -> Int64(localId),
      ))
    case WireValue.Error(code, message, details) =>
      val inner = LinkedHashMap[Msg, Msg]()
      inner.put(Str("code"), Str(code))
      inner.put(Str("message"), Str(message))
      details.foreach(d => inner.put(Str("details"), encodeToMsg(d)))
      mkObj(Str("$error") -> Obj(inner))

  def encodeToBytes(v: WireValue): Array[Byte] =
    upack.write(encodeToMsg(v))

  def decodeFromMsg(msg: Msg): Either[WireDecodeError, WireValue] = msg match
    case Null        => Right(WireValue.Null)
    case True        => Right(WireValue.Bool(true))
    case False       => Right(WireValue.Bool(false))
    case Int32(n)    => Right(WireValue.Int64(n.toLong))
    case Int64(n)    => Right(WireValue.Int64(n))
    case Float32(d)  => Right(WireValue.Float64(d.toDouble))
    case Float64(d)  => Right(WireValue.Float64(d))
    case Str(s)      => Right(WireValue.Str(s))
    case Binary(bs)  => Right(WireValue.Bytes(bs))
    case UInt64(n)   => Right(WireValue.Int64(n.toLong))
    case Ext(_, _)   => Left(WireDecodeError.MalformedInput("unsupported MsgPack Ext type"))
    case Arr(vs)     =>
      vs.foldLeft[Either[WireDecodeError, Vector[WireValue]]](Right(Vector.empty)) {
        (acc, v) => acc.flatMap(xs => decodeFromMsg(v).map(xs :+ _))
      }.map(WireValue.Lst(_))
    case Obj(entries) =>
      val fields = entries.toVector
      fields.headOption match
        case Some((Str("$unit"), _)) =>
          Right(WireValue.Unit)
        case Some((Str("$pid"), _)) =>
          entries.get(Str("$pid")) match
            case Some(Obj(pf)) =>
              val nodeId  = pf.get(Str("nodeId")).collect { case Str(s) => s }.getOrElse("")
              val localId = pf.get(Str("localId")).collect {
                case Int64(n) => n; case Int32(n) => n.toLong
              }.getOrElse(0L)
              Right(WireValue.Pid(nodeId, localId))
            case _ => Left(WireDecodeError.MalformedInput("$pid must be an object"))
        case Some((Str("$error"), _)) =>
          entries.get(Str("$error")) match
            case Some(Obj(ef)) =>
              val code = ef.get(Str("code")).collect { case Str(s) => s }.getOrElse("UNKNOWN")
              val msg  = ef.get(Str("message")).collect { case Str(s) => s }.getOrElse("")
              val details = ef.get(Str("details")) match
                case None    => Right(None)
                case Some(v) => decodeFromMsg(v).map(Some(_))
              details.map(d => WireValue.Error(code, msg, d))
            case _ => Left(WireDecodeError.MalformedInput("$error must be an object"))
        case Some((Str("$tuple"), _)) =>
          entries.get(Str("$tuple")) match
            case Some(Arr(vs)) =>
              vs.foldLeft[Either[WireDecodeError, Vector[WireValue]]](Right(Vector.empty)) {
                (acc, v) => acc.flatMap(xs => decodeFromMsg(v).map(xs :+ _))
              }.map(WireValue.Tuple(_))
            case _ => Left(WireDecodeError.MalformedInput("$tuple must be an array"))
        case Some((Str("$type"), typeMsg)) if entries.contains(Str("$case")) =>
          val typeName = typeMsg match { case Str(s) => s; case _ => "" }
          val caseName = entries.get(Str("$case")).collect { case Str(s) => s }.getOrElse("")
          entries.get(Str("$val")) match
            case None    => Right(WireValue.Enum(typeName, caseName, None))
            case Some(v) => decodeFromMsg(v).map(w => WireValue.Enum(typeName, caseName, Some(w)))
        case Some((Str("$map"), _)) =>
          entries.get(Str("$map")) match
            case Some(Arr(vs)) =>
              vs.foldLeft[Either[WireDecodeError, Vector[(WireValue, WireValue)]]](Right(Vector.empty)) {
                (acc, item) =>
                  item match
                    case Arr(pair) if pair.length == 2 =>
                      for xs <- acc; k <- decodeFromMsg(pair(0)); v <- decodeFromMsg(pair(1))
                      yield xs :+ (k -> v)
                    case _ => Left(WireDecodeError.MalformedInput("$map entry must be [key, value]"))
              }.map(WireValue.Map(_))
            case _ => Left(WireDecodeError.MalformedInput("$map must be an array"))
        case Some((Str("$type"), typeMsg)) =>
          val typeName = typeMsg match { case Str(s) => s; case _ => "" }
          fields.filterNot { case (Str("$type"), _) => true; case _ => false }
            .foldLeft[Either[WireDecodeError, Vector[(String, WireValue)]]](Right(Vector.empty)) {
              case (acc, (Str(k), v)) => acc.flatMap(xs => decodeFromMsg(v).map(w => xs :+ (k -> w)))
              case (acc, _)           => acc
            }.map(fs => WireValue.Object(typeName, fs))
        case _ =>
          // Plain object with string keys → WireValue.Object("")
          val hasNonStringKey = fields.exists { case (Str(_), _) => false; case _ => true }
          if hasNonStringKey then
            fields.foldLeft[Either[WireDecodeError, Vector[(WireValue, WireValue)]]](Right(Vector.empty)) {
              case (acc, (k, v)) =>
                for xs <- acc; kw <- decodeFromMsg(k); vw <- decodeFromMsg(v)
                yield xs :+ (kw -> vw)
            }.map(WireValue.Map(_))
          else
            fields.foldLeft[Either[WireDecodeError, Vector[(String, WireValue)]]](Right(Vector.empty)) {
              case (acc, (Str(k), v)) => acc.flatMap(xs => decodeFromMsg(v).map(w => xs :+ (k -> w)))
              case (acc, _)           => acc
            }.map(fs => WireValue.Object("", fs))

  def decodeFromBytes(bytes: Array[Byte]): Either[WireDecodeError, WireValue] =
    try decodeFromMsg(upack.read(bytes))
    catch case ex: Exception =>
      Left(WireDecodeError.MalformedInput(s"MsgPack parse error: ${ex.getMessage}"))

  // ── WireEnvelope encode/decode ────────────────────────────────────────────

  def encodeEnvelope(env: WireEnvelope): Array[Byte] =
    val m = LinkedHashMap[Msg, Msg]()
    m.put(Str("protocol"),    Str(env.protocol))
    m.put(Str("protocolVer"), Int32(env.protocolVer))
    m.put(Str("format"),      Str(env.format))
    m.put(Str("kind"),        Str(env.kind))
    env.correlationId.foreach(id => m.put(Str("correlationId"), Str(id)))
    env.schemaId.foreach(id => m.put(Str("schemaId"), Str(id)))
    if env.flags.nonEmpty then
      m.put(Str("flags"), Arr(env.flags.map(Str(_)).toSeq*))
    if env.headers.nonEmpty then
      val hm = LinkedHashMap[Msg, Msg]()
      env.headers.foreach { case (k, v) => hm.put(Str(k), Str(v)) }
      m.put(Str("headers"), Obj(hm))
    m.put(Str("payload"), encodeToMsg(env.payload))
    upack.write(Obj(m))

  def decodeEnvelope(bytes: Array[Byte]): Either[WireDecodeError, WireEnvelope] =
    try
      upack.read(bytes) match
        case Obj(f) =>
          val protocol    = f.get(Str("protocol")).collect { case Str(s) => s }.getOrElse("")
          val protocolVer = f.get(Str("protocolVer")).collect {
            case Int32(n) => n; case Int64(n) => n.toInt
          }.getOrElse(1)
          val format      = f.get(Str("format")).collect { case Str(s) => s }.getOrElse("json")
          val kind        = f.get(Str("kind")).collect { case Str(s) => s }.getOrElse("")
          val corrId      = f.get(Str("correlationId")).collect { case Str(s) => s }
          val schemaId    = f.get(Str("schemaId")).collect { case Str(s) => s }
          val flags       = f.get(Str("flags")) match
            case Some(Arr(fs)) => fs.collect { case Str(s) => s }.toSet
            case _ => Set.empty[String]
          val headers     = f.get(Str("headers")) match
            case Some(Obj(hf)) => hf.toMap.collect { case (Str(k), Str(v)) => k -> v }
            case _ => Map.empty[String, String]
          val payloadMsg  = f.getOrElse(Str("payload"), Null)
          decodeFromMsg(payloadMsg).map { payload =>
            WireEnvelope(protocol, protocolVer, format, kind, corrId, schemaId, flags, headers, payload)
          }
        case _ => Left(WireDecodeError.MalformedInput("envelope must be a MsgPack map"))
    catch case ex: Exception =>
      Left(WireDecodeError.MalformedInput(s"MsgPack parse error: ${ex.getMessage}"))
