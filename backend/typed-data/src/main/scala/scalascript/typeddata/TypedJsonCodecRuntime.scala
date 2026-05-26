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
        |var _ssc_typed_json_codecs = globalThis.__sscTypedJsonCodecs || (globalThis.__sscTypedJsonCodecs = new Map());
        |
        |function _ssc_typed_json_register_product(typeName, fields, ctor) {
        |  _ssc_typed_json_codecs.set(String(typeName), {
        |    kind: "product",
        |    fields: Array.from(fields || []),
        |    ctor: typeof ctor === "function" ? ctor : undefined
        |  });
        |}
        |
        |function _ssc_typed_json_inner(typeName) {
        |  const s = String(typeName || "");
        |  if (s.startsWith("List[") && s.endsWith("]")) return s.slice(5, -1);
        |  if (s.startsWith("Option[") && s.endsWith("]")) return s.slice(7, -1);
        |  return "";
        |}
        |
        |function _ssc_typed_json_plain(value, typeName) {
        |  if (value === undefined) return undefined;
        |  if (value === null) return null;
        |  const tpe = String(typeName || "");
        |  if (tpe === "Unit") return undefined;
        |  if (tpe.startsWith("List[") && Array.isArray(value)) {
        |    const inner = _ssc_typed_json_inner(tpe);
        |    return value.map(v => _ssc_typed_json_plain(v, inner));
        |  }
        |  if (tpe.startsWith("Option[")) {
        |    if (value && value._type === "_None") return null;
        |    const inner = _ssc_typed_json_inner(tpe);
        |    return value && value._type === "_Some" ? _ssc_typed_json_plain(value.value, inner) : _ssc_typed_json_plain(value, inner);
        |  }
        |  if (value && value._type === "_None") return null;
        |  if (value && value._type === "_Some") return _ssc_typed_json_plain(value.value, _ssc_typed_json_inner(tpe));
        |  const codec = _ssc_typed_json_codecs.get(tpe);
        |  if (codec && codec.kind === "product") {
        |    const out = {};
        |    for (const field of codec.fields) out[field] = _ssc_typed_json_plain(value[field], "");
        |    return out;
        |  }
        |  if (value && typeof value === "object" && value._type && _ssc_typed_json_codecs.has(value._type)) {
        |    const variant = _ssc_typed_json_codecs.get(value._type);
        |    const payload = {};
        |    for (const field of variant.fields) payload[field] = _ssc_typed_json_plain(value[field], "");
        |    return tpe && tpe !== value._type ? {"$$type": value._type, value: payload} : payload;
        |  }
        |  return value;
        |}
        |
        |function $encodeFunctionName(value, typeName) {
        |  return JSON.stringify(_ssc_typed_json_plain(value, typeName));
        |}
        |
        |function _ssc_typed_json_decode_value(value, typeName) {
        |  const tpe = String(typeName || "");
        |  if (tpe === "" || tpe === "Any") return value;
        |  if (tpe === "Unit") return undefined;
        |  if (tpe === "String") return value == null ? "" : String(value);
        |  if (tpe === "Int" || tpe === "Long" || tpe === "Double" || tpe === "Float") return Number(value);
        |  if (tpe === "Boolean") return Boolean(value);
        |  if (tpe.startsWith("List[")) {
        |    const inner = _ssc_typed_json_inner(tpe);
        |    return Array.isArray(value) ? value.map(v => _ssc_typed_json_decode_value(v, inner)) : [];
        |  }
        |  if (tpe.startsWith("Option[")) {
        |    if (value === null || value === undefined) return {_type: "_None"};
        |    return {_type: "_Some", value: _ssc_typed_json_decode_value(value, _ssc_typed_json_inner(tpe))};
        |  }
        |  if (value && typeof value === "object" && value["$$type"]) {
        |    return _ssc_typed_json_decode_value(value.value || {}, String(value["$$type"]));
        |  }
        |  const codec = _ssc_typed_json_codecs.get(tpe);
        |  if (codec && codec.kind === "product") {
        |    const args = codec.fields.map(field => _ssc_typed_json_decode_value(value == null ? undefined : value[field], ""));
        |    if (codec.ctor) return codec.ctor(...args);
        |    const out = {_type: tpe};
        |    codec.fields.forEach((field, idx) => { out[field] = args[idx]; });
        |    return out;
        |  }
        |  return value;
        |}
        |
        |function $decodeResponseFunctionName(text, contentType, typeName) {
        |  if (text === "") return _ssc_typed_json_decode_value(undefined, typeName);
        |  let parsed;
        |  if (String(contentType || "").includes("application/json")) parsed = JSON.parse(text);
        |  else {
        |    try { parsed = JSON.parse(text); } catch (_) { parsed = text; }
        |  }
        |  return _ssc_typed_json_decode_value(parsed, typeName);
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
