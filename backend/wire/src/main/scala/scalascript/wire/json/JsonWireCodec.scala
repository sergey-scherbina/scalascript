package scalascript.wire.json

import scalascript.wire.*
import ujson.*

/** JSON encoder/decoder for `WireValue` using ujson.
 *
 *  Mapping:
 *  - `Null`            → JSON `null`
 *  - `Unit`            → JSON object `{"$unit": true}`
 *  - `Bool`            → JSON `true` / `false`
 *  - `Int64`           → JSON number (preserves full Long range — consumers
 *                        must treat large JSON numbers as int64)
 *  - `Float64`         → JSON object `{"$f64": <number>}` (finite), or
 *                        `{"$f64": "NaN"/"Infinity"/"-Infinity"}` (special)
 *  - `Str`             → JSON string
 *  - `Bytes`           → JSON object `{"$bytes": "<base64>"}`
 *  - `Lst`             → JSON array
 *  - `Map`             → JSON object `{"$map": [[key, value], ...]}`
 *  - `Object`          → JSON object `{"$type": "<name>", <fields>...}`
 *  - `Tuple`           → JSON object `{"$tuple": [...]}`
 *  - `Enum`            → JSON object `{"$type": "<name>", "$case": "<case>", "$val": ...}`
 *  - `Pid`             → JSON object `{"$pid": {"nodeId": "...", "localId": ...}}`
 *  - `Error`           → JSON object `{"$error": {"code": "...", "message": "...", ...}}`
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Phase 1 */
object JsonWireCodec:

  def encodeToValue(v: WireValue): ujson.Value = v match
    case WireValue.Null       => ujson.Null
    case WireValue.Unit       => ujson.Obj("$unit" -> ujson.True)
    case WireValue.Bool(b)    => ujson.Bool(b)
    case WireValue.Int64(n)   => ujson.Num(n.toDouble)
    case WireValue.Float64(d) =>
      if d.isNaN              then ujson.Obj("$f64" -> ujson.Str("NaN"))
      else if d.isPosInfinity then ujson.Obj("$f64" -> ujson.Str("Infinity"))
      else if d.isNegInfinity then ujson.Obj("$f64" -> ujson.Str("-Infinity"))
      else                         ujson.Obj("$f64" -> ujson.Num(d))
    case WireValue.Str(s)     => ujson.Str(s)
    case WireValue.Bytes(bs)  =>
      ujson.Obj("$bytes" -> ujson.Str(java.util.Base64.getEncoder.encodeToString(bs)))
    case WireValue.Lst(vs)    =>
      ujson.Arr(vs.map(encodeToValue)*)
    case WireValue.Map(entries) =>
      ujson.Obj("$map" -> ujson.Arr(entries.map { case (k, v) => ujson.Arr(encodeToValue(k), encodeToValue(v)) }*))
    case WireValue.Object(typeName, fields) =>
      val obj = ujson.Obj()
      obj("$type") = ujson.Str(typeName)
      for (name, value) <- fields do obj(name) = encodeToValue(value)
      obj
    case WireValue.Tuple(values) =>
      ujson.Obj("$tuple" -> ujson.Arr(values.map(encodeToValue)*))
    case WireValue.Enum(typeName, caseName, value) =>
      val obj = ujson.Obj()
      obj("$type")  = ujson.Str(typeName)
      obj("$case")  = ujson.Str(caseName)
      value.foreach(v => obj("$val") = encodeToValue(v))
      obj
    case WireValue.Pid(nodeId, localId) =>
      ujson.Obj("$pid" -> ujson.Obj(
        "nodeId"  -> ujson.Str(nodeId),
        "localId" -> ujson.Num(localId.toDouble),
      ))
    case WireValue.Error(code, message, details) =>
      val inner = ujson.Obj()
      inner("code")    = ujson.Str(code)
      inner("message") = ujson.Str(message)
      details.foreach(d => inner("details") = encodeToValue(d))
      ujson.Obj("$error" -> inner)

  def encodeToString(v: WireValue): String =
    ujson.write(encodeToValue(v))

  def encodeToBytes(v: WireValue): Array[Byte] =
    encodeToString(v).getBytes(java.nio.charset.StandardCharsets.UTF_8)

  def decodeFromValue(j: ujson.Value): Either[WireDecodeError, WireValue] = j match
    case ujson.Null       => Right(WireValue.Null)
    case ujson.True       => Right(WireValue.Bool(true))
    case ujson.False      => Right(WireValue.Bool(false))
    case ujson.Num(d)     =>
      val n = d.toLong
      if n.toDouble == d then Right(WireValue.Int64(n))
      else Right(WireValue.Float64(d))
    case ujson.Str(s)     => Right(WireValue.Str(s))
    case ujson.Arr(vs)    =>
      vs.foldLeft[Either[WireDecodeError, Vector[WireValue]]](Right(Vector.empty)) {
        (acc, v) => acc.flatMap(xs => decodeFromValue(v).map(xs :+ _))
      }.map(WireValue.Lst(_))
    case ujson.Obj(fields) =>
      if fields.contains("$unit") then
        Right(WireValue.Unit)
      else if fields.contains("$f64") then
        fields("$f64") match
          case ujson.Num(d)          => Right(WireValue.Float64(d))
          case ujson.Str("NaN")      => Right(WireValue.Float64(Double.NaN))
          case ujson.Str("Infinity") => Right(WireValue.Float64(Double.PositiveInfinity))
          case ujson.Str("-Infinity")=> Right(WireValue.Float64(Double.NegativeInfinity))
          case _                     => Left(WireDecodeError.MalformedInput("$f64 must be a number or special string"))
      else if fields.contains("$map") then
        fields("$map") match
          case ujson.Arr(vs) =>
            vs.foldLeft[Either[WireDecodeError, Vector[(WireValue, WireValue)]]](Right(Vector.empty)) {
              (acc, item) =>
                item match
                  case ujson.Arr(pair) if pair.length == 2 =>
                    for xs <- acc; k <- decodeFromValue(pair(0)); v <- decodeFromValue(pair(1))
                    yield xs :+ (k -> v)
                  case _ => Left(WireDecodeError.MalformedInput("$map entry must be [key, value]"))
            }.map(WireValue.Map(_))
          case _ => Left(WireDecodeError.MalformedInput("$map must be an array"))
      else if fields.contains("$bytes") then
        fields("$bytes") match
          case ujson.Str(b64) =>
            try Right(WireValue.Bytes(java.util.Base64.getDecoder.decode(b64)))
            catch case _: Exception =>
              Left(WireDecodeError.MalformedInput("invalid base64 in $bytes"))
          case _ => Left(WireDecodeError.MalformedInput("$bytes must be a string"))
      else if fields.contains("$tuple") then
        fields("$tuple") match
          case ujson.Arr(vs) =>
            vs.foldLeft[Either[WireDecodeError, Vector[WireValue]]](Right(Vector.empty)) {
              (acc, v) => acc.flatMap(xs => decodeFromValue(v).map(xs :+ _))
            }.map(WireValue.Tuple(_))
          case _ => Left(WireDecodeError.MalformedInput("$tuple must be an array"))
      else if fields.contains("$pid") then
        fields("$pid") match
          case ujson.Obj(pf) =>
            val nodeId  = pf.get("nodeId").collect { case ujson.Str(s) => s }.getOrElse("")
            val localId = pf.get("localId").collect { case ujson.Num(n) => n.toLong }.getOrElse(0L)
            Right(WireValue.Pid(nodeId, localId))
          case _ => Left(WireDecodeError.MalformedInput("$pid must be an object"))
      else if fields.contains("$error") then
        fields("$error") match
          case ujson.Obj(ef) =>
            val code    = ef.get("code").collect { case ujson.Str(s) => s }.getOrElse("UNKNOWN")
            val msg     = ef.get("message").collect { case ujson.Str(s) => s }.getOrElse("")
            val details = ef.get("details").map(decodeFromValue).flatMap(_.toOption)
            Right(WireValue.Error(code, msg, details))
          case _ => Left(WireDecodeError.MalformedInput("$error must be an object"))
      else if fields.contains("$case") then
        val typeName = fields.get("$type").collect { case ujson.Str(s) => s }.getOrElse("")
        val caseName = fields.get("$case").collect { case ujson.Str(s) => s }.getOrElse("")
        val valOpt   = fields.get("$val")
        valOpt match
          case None    => Right(WireValue.Enum(typeName, caseName, None))
          case Some(v) => decodeFromValue(v).map(w => WireValue.Enum(typeName, caseName, Some(w)))
      else if fields.contains("$type") then
        val typeName = fields.get("$type").collect { case ujson.Str(s) => s }.getOrElse("")
        val rest     = fields.filterNot { case ("$type", _) => true; case _ => false }
        val fieldSeq = rest.toVector
        fieldSeq.foldLeft[Either[WireDecodeError, Vector[(String, WireValue)]]](Right(Vector.empty)) {
          case (acc, (k, v)) => acc.flatMap(xs => decodeFromValue(v).map(w => xs :+ (k -> w)))
        }.map(fs => WireValue.Object(typeName, fs))
      else
        val fieldSeq = fields.toVector
        fieldSeq.foldLeft[Either[WireDecodeError, Vector[(String, WireValue)]]](Right(Vector.empty)) {
          case (acc, (k, v)) => acc.flatMap(xs => decodeFromValue(v).map(w => xs :+ (k -> w)))
        }.map(fs => WireValue.Object("", fs))

  def decodeFromString(json: String): Either[WireDecodeError, WireValue] =
    try decodeFromValue(ujson.read(json))
    catch case ex: Exception =>
      Left(WireDecodeError.MalformedInput(s"JSON parse error: ${ex.getMessage}"))

  def decodeFromBytes(bytes: Array[Byte]): Either[WireDecodeError, WireValue] =
    decodeFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))

  // ── WireEnvelope JSON encode/decode ──────────────────────────────────────

  def encodeEnvelope(env: WireEnvelope): String =
    val obj = ujson.Obj()
    obj("protocol")    = ujson.Str(env.protocol)
    obj("protocolVer") = ujson.Num(env.protocolVer.toDouble)
    obj("format")      = ujson.Str(env.format)
    obj("kind")        = ujson.Str(env.kind)
    env.correlationId.foreach(id => obj("correlationId") = ujson.Str(id))
    env.schemaId.foreach(id => obj("schemaId") = ujson.Str(id))
    if env.flags.nonEmpty then
      obj("flags") = ujson.Arr(env.flags.map(ujson.Str(_)).toSeq*)
    if env.headers.nonEmpty then
      val h = ujson.Obj()
      env.headers.foreach { case (k, v) => h(k) = ujson.Str(v) }
      obj("headers") = h
    obj("payload") = encodeToValue(env.payload)
    ujson.write(obj)

  def decodeEnvelope(json: String): Either[WireDecodeError, WireEnvelope] =
    try
      val j = ujson.read(json)
      j match
        case ujson.Obj(f) =>
          val protocol    = f.get("protocol").collect { case ujson.Str(s) => s }.getOrElse("")
          val protocolVer = f.get("protocolVer").collect { case ujson.Num(n) => n.toInt }.getOrElse(1)
          val format      = f.get("format").collect { case ujson.Str(s) => s }.getOrElse("json")
          val kind        = f.get("kind").collect { case ujson.Str(s) => s }.getOrElse("")
          val corrId      = f.get("correlationId").collect { case ujson.Str(s) => s }
          val schemaId    = f.get("schemaId").collect { case ujson.Str(s) => s }
          val flags       = f.get("flags") match
            case Some(ujson.Arr(fs)) => fs.collect { case ujson.Str(s) => s }.toSet
            case _ => Set.empty[String]
          val headers     = f.get("headers") match
            case Some(ujson.Obj(hf)) => hf.collect { case (k, ujson.Str(v)) => k -> v }.toMap
            case _ => Map.empty[String, String]
          val payloadJ    = f.getOrElse("payload", ujson.Null)
          decodeFromValue(payloadJ).map { payload =>
            WireEnvelope(protocol, protocolVer, format, kind, corrId, schemaId, flags, headers, payload)
          }
        case _ => Left(WireDecodeError.MalformedInput("envelope must be a JSON object"))
    catch case ex: Exception =>
      Left(WireDecodeError.MalformedInput(s"JSON parse error: ${ex.getMessage}"))
