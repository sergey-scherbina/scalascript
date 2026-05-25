package scalascript.typeddata

/** Shared emitted-runtime snippets for the typed JSON codec boundary.
  *
  * The snippets deliberately keep today's minimal JSON behavior, but the
  * function names are the stable ABI that typed route clients call. Future
  * `JsonCodec[A]` derivation can replace these bodies without changing the
  * generated client method shape.
  */
object TypedJsonCodecRuntime:

  val encodeFunctionName = "_ssc_typed_json_encode"
  val decodeResponseFunctionName = "_ssc_typed_json_decode_response"

  val jsFacade: String =
    s"""|// Shared typed JSON codec facade. Phase 4 keeps the implementation in
        |// emitted runtime code, but typed route clients call this stable codec
        |// boundary instead of embedding transport-specific JSON operations.
        |function $encodeFunctionName(value) {
        |  return JSON.stringify(value);
        |}
        |
        |function $decodeResponseFunctionName(text, contentType) {
        |  if (text === "") return undefined;
        |  if (String(contentType || "").includes("application/json")) return JSON.parse(text);
        |  try { return JSON.parse(text); } catch (_) { return text; }
        |}
        |
        |""".stripMargin

  val jvmFacade: String =
    s"""|// Shared typed JSON codec facade. Phase 4 keeps the implementation in
        |// emitted runtime code, but JVM typed route clients now route typed
        |// request/response values through scalascript.typeddata.JsonCodec.
        |private def _ssc_json_plain(value: scalascript.typeddata.JsonValue): Any =
        |  value match
        |    case scalascript.typeddata.JsonValue.Null => null
        |    case scalascript.typeddata.JsonValue.Bool(v) => v
        |    case scalascript.typeddata.JsonValue.Num(v) =>
        |      if v.isValidInt then v.toInt else if v.isValidLong then v.toLong else v.toDouble
        |    case scalascript.typeddata.JsonValue.Str(v) => v
        |    case scalascript.typeddata.JsonValue.Arr(values) => values.map(_ssc_json_plain).toList
        |    case scalascript.typeddata.JsonValue.Obj(fields) => fields.view.mapValues(_ssc_json_plain).toMap
        |
        |private def _ssc_json_value(value: Any): scalascript.typeddata.JsonValue =
        |  value match
        |    case null => scalascript.typeddata.JsonValue.Null
        |    case v: Boolean => scalascript.typeddata.JsonValue.Bool(v)
        |    case v: Int => scalascript.typeddata.JsonValue.Num(BigDecimal(v))
        |    case v: Long => scalascript.typeddata.JsonValue.Num(BigDecimal(v))
        |    case v: Double => scalascript.typeddata.JsonValue.Num(BigDecimal(v))
        |    case v: BigDecimal => scalascript.typeddata.JsonValue.Num(v)
        |    case v: String => scalascript.typeddata.JsonValue.Str(v)
        |    case values: List[?] => scalascript.typeddata.JsonValue.Arr(values.map(_ssc_json_value).toVector)
        |    case values: Vector[?] => scalascript.typeddata.JsonValue.Arr(values.map(_ssc_json_value))
        |    case fields: Map[?, ?] =>
        |      scalascript.typeddata.JsonValue.Obj(fields.iterator.map((k, v) => k.toString -> _ssc_json_value(v)).toMap)
        |    case other => throw RuntimeException("typed route client: unsupported JSON value " + _show(other))
        |
        |private inline def $encodeFunctionName[T](value: T): String =
        |  _toJsonValue(_ssc_json_plain(summonInline[scalascript.typeddata.JsonCodec[T]].encode(value)))
        |
        |private inline def $decodeResponseFunctionName[T](response: scalascript.backend.spi.BackendResponse): T =
        |  val body = String(response.body, java.nio.charset.StandardCharsets.UTF_8)
        |  inline erasedValue[T] match
        |    case _: Unit => ().asInstanceOf[T]
        |    case _: Response =>
        |      Response(response.status, response.headers, body).asInstanceOf[T]
        |    case _ =>
        |      summonInline[scalascript.typeddata.JsonCodec[T]].decode(_ssc_json_value(_fromJson(body))) match
        |        case Right(value) => value
        |        case Left(error) => throw RuntimeException("typed route client: " + error.render)
        |
        |""".stripMargin
