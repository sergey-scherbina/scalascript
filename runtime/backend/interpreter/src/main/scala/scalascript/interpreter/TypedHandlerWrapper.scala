package scalascript.interpreter

/** v1.30 Phase 7 — typed handler auto-deserialization / serialization.
 *
 *  Detects handler functions with non-Request, non-Map parameters and wraps
 *  them in a NativeFnV that:
 *    1. Deserializes each typed param from path params → query params → JSON body.
 *    2. Calls the original handler with the deserialized values.
 *    3. Serializes the result to a JSON Response (or passes a Response through).
 *
 *  Special types (pass-through, no deser): "Request", "Map", "Map[String,Any]",
 *  "Map[String, Any]", "" (empty = untyped).
 *  Primitive types: "String", "Int", "Long", "Double", "Float", "Boolean".
 *  Case class types: anything else — look up in globalsView for FunV metadata.
 */
object TypedHandlerWrapper:

  private val specialTypes = Set("Request", "Map", "Map[String,Any]", "Map[String, Any]")
  private val primitiveTypes = Set("String", "Int", "Long", "Double", "Float", "Boolean")

  /** Wrap `handler` if it has any typed (non-special) parameters; return it unchanged
   *  if all params are special (raw Request / Map) or if it is not a FunV.
   *
   *  @param mountedPath  The mount path (e.g. "/hello/:name"); reserved for future
   *                      path-aware deserialization hints. Currently unused at runtime
   *                      because the server fills `req.params` before dispatch. */
  def wrapIfTyped(
    handler:      Value,
    invoke:       (Value, List[Value]) => Value,
    globalsView:  collection.Map[String, Value],
    mountedPath:  String,                         // see scaladoc above
    errorDetails: Boolean = true,
  ): Value =
    val _ = mountedPath   // reserved for future use
    handler match
      case f: Value.FunV =>
        val (dataParams, trailingReq, trailingCtx) = splitParams(f)
        if dataParams.isEmpty then handler  // all special → raw handler
        else buildWrapper(f, dataParams, trailingReq, trailingCtx, invoke, globalsView, errorDetails)
      case _ => handler

  // ── param splitting ───────────────────────────────────────────────────────

  /** Split params into (dataParams: List[(name,type)], hasTrailingRequest, hasTrailingMap).
   *  Trailing Map (context) and Request are peeled off the end before analysis. */
  private def splitParams(f: Value.FunV): (List[(String, String)], Boolean, Boolean) =
    val pairs = f.params.zip(f.paramTypes.padTo(f.params.length, ""))
    val data = scala.collection.mutable.ListBuffer.from(pairs)
    var trailingCtx = false
    var trailingReq = false
    // Strip trailing Map[...] / Map
    if data.nonEmpty && isMapType(data.last._2) then
      data.remove(data.length - 1)
      trailingCtx = true
    // Strip trailing Request
    if data.nonEmpty && data.last._2 == "Request" then
      data.remove(data.length - 1)
      trailingReq = true
    // All-special check: every remaining param must be a special type
    val dataList = data.toList
    val allSpecial = dataList.forall { case (_, t) => specialTypes.contains(t) || t == "" }
    if allSpecial then (Nil, trailingReq, trailingCtx)
    else (dataList, trailingReq, trailingCtx)

  private def isMapType(t: String): Boolean =
    t == "Map" || t == "Map[String,Any]" || t == "Map[String, Any]" || t.startsWith("Map[")

  // ── wrapper construction ──────────────────────────────────────────────────

  private def buildWrapper(
    f:            Value.FunV,
    dataParams:   List[(String, String)],
    trailingReq:  Boolean,
    trailingCtx:  Boolean,
    invoke:       (Value, List[Value]) => Value,
    globalsView:  collection.Map[String, Value],
    errorDetails: Boolean,
  ): Value =
    Value.NativeFnV("typed-handler", Computation.pureFn { args =>
      val reqV = args.head.asInstanceOf[Value.InstanceV]
      val pathParams  = extractStringMap(reqV.fields.get("params"))
      val queryParams = extractStringMap(reqV.fields.get("query"))
      val bodyStr     = reqV.fields.get("body").collect { case Value.StringV(s) if s.nonEmpty => s }
      val jsonBody: Map[String, Value] = bodyStr.flatMap(s =>
        try Some(parseJsonObject(s)) catch case _: Throwable => None
      ).getOrElse(Map.empty)

      // Check for Either[Request, Input] first param shape
      // typeToString renders it as "Either[Request, CreateInput]"
      dataParams match
        case List((paramName, typeName)) if isEitherRequest(typeName) =>
          // Either[Request, Input] — try deserialization; on failure return Left(req)
          val innerType = extractEitherRight(typeName)
          deserializeParam(paramName, innerType, pathParams, queryParams, jsonBody, globalsView) match
            case Right(v) =>
              // Pass Right(v) as InstanceV("Right", ...) — interpreter's convention
              val eitherRight = Value.InstanceV("Right", Map("value" -> v))
              val result = invoke(f, List(eitherRight) ++ trailingArgs(reqV, trailingReq, trailingCtx))
              serializeOutput(result)
            case Left(_) =>
              val eitherLeft = Value.InstanceV("Left", Map("value" -> reqV))
              val result = invoke(f, List(eitherLeft) ++ trailingArgs(reqV, trailingReq, trailingCtx))
              serializeOutput(result)
        case _ =>
          // Normal typed params — deserialize each one
          val desered = dataParams.map { case (name, typeName) =>
            deserializeParam(name, typeName, pathParams, queryParams, jsonBody, globalsView)
          }
          val errors = desered.collect { case Left(err) => err }
          if errors.nonEmpty then
            val body = if errorDetails then s"""{"error":"${errors.head}"}""" else "{}"
            httpErrorResponse(400, body)
          else
            val dataArgs = desered.collect { case Right(v) => v }
            val result = invoke(f, dataArgs ++ trailingArgs(reqV, trailingReq, trailingCtx))
            serializeOutput(result)
    })

  private def trailingArgs(reqV: Value.InstanceV, trailingReq: Boolean, trailingCtx: Boolean): List[Value] =
    (if trailingReq then List(reqV) else Nil) ++
    (if trailingCtx then List(Value.EmptyMap) else Nil)

  // ── deserialization ───────────────────────────────────────────────────────

  private def deserializeParam(
    name:        String,
    typeName:    String,
    pathParams:  Map[String, String],
    queryParams: Map[String, String],
    jsonBody:    Map[String, Value],
    globalsView: collection.Map[String, Value],
  ): Either[String, Value] =
    if specialTypes.contains(typeName) || typeName == "" then
      // Should not reach here for true special types, but guard anyway
      Left(s"cannot deserialize special type: $typeName")
    else if primitiveTypes.contains(typeName) then
      // Named lookup: path wins over query wins over body
      val raw = pathParams.get(name)
        .orElse(queryParams.get(name))
        .orElse(jsonBody.get(name).map(valueToString))
      raw match
        case None    => Left(s"missing field: $name")
        case Some(s) => Right(coercePrimitive(s, typeName))
    else
      // Case class: look up FunV metadata in globalsView
      globalsView.get(typeName) match
        case Some(ctor: Value.FunV) =>
          // ctor.params = field names, ctor.paramTypes = field types
          val fieldTypes = ctor.paramTypes.padTo(ctor.params.length, "String")
          val fieldResults = ctor.params.zip(fieldTypes).map { case (fname, ftype) =>
            deserializeParam(fname, ftype, pathParams, queryParams, jsonBody, globalsView)
          }
          val errs = fieldResults.collect { case Left(e) => e }
          if errs.nonEmpty then Left(errs.head)
          else
            val fieldValues = ctor.params.zip(fieldResults.collect { case Right(v) => v }).toMap
            Right(Value.InstanceV(typeName, fieldValues))
        case _ => Left(s"unknown type: $typeName")

  private def coercePrimitive(s: String, typeName: String): Value = typeName match
    case "Int" | "Long"     => Value.intV(s.toLongOption.getOrElse(0L))
    case "Double" | "Float" => Value.doubleV(s.toDoubleOption.getOrElse(0.0))
    case "Boolean"          => Value.boolV(s == "true" || s == "1")
    case _                  => Value.StringV(s)

  private def valueToString(v: Value): String = v match
    case Value.StringV(s) => s
    case Value.IntV(n)    => n.toString
    case Value.DoubleV(d) => d.toString
    case Value.BoolV(b)   => b.toString
    case other            => Value.show(other)

  // ── output serialization ──────────────────────────────────────────────────

  private def serializeOutput(v: Value): Value = v match
    case r: Value.InstanceV if r.typeName == "Response"       => r
    case r: Value.InstanceV if r.typeName == "StreamResponse" => r
    // Left/Right are InstanceV("Left", Map("value" -> ...)) / InstanceV("Right", Map("value" -> ...))
    case Value.InstanceV("Right", fields) =>
      serializeOutput(fields.getOrElse("value", Value.UnitV))
    case Value.InstanceV("Left", fields) =>
      serializeOutput(fields.getOrElse("value", Value.UnitV))
    case other =>
      httpJsonResponse(200, jsonToJson(other))

  // ── JSON body parsing ─────────────────────────────────────────────────────

  /** Parse a JSON object string into Map[String, Value].
   *  Only handles top-level objects (the common case for request bodies).
   *  Uses the existing jsonToJson-friendly Value representation. */
  private def parseJsonObject(s: String): Map[String, Value] =
    // Use upickle's ujson which is available transitively from mcpCommon → core
    // We avoid direct ujson import by using a Java-level approach.
    // Simple recursive descent over the string.
    val trimmed = s.trim
    if !trimmed.startsWith("{") then return Map.empty
    parseJsonValue(trimmed, 0)._1 match
      case Value.MapV(entries) =>
        entries.collect { case (Value.StringV(k), v) => k -> v }
      case _ => Map.empty

  /** Minimalistic JSON parser returning (Value, nextIndex).
   *  Handles: string, number, boolean, null, object, array. */
  private def parseJsonValue(s: String, start: Int): (Value, Int) =
    var i = start
    while i < s.length && s.charAt(i).isWhitespace do i += 1
    if i >= s.length then return (Value.NullV, i)
    s.charAt(i) match
      case '"' =>
        val (str, end) = parseJsonString(s, i)
        (Value.StringV(str), end)
      case '{' =>
        parseJsonObjectFrom(s, i)
      case '[' =>
        parseJsonArrayFrom(s, i)
      case 't' if s.startsWith("true", i)  => (Value.True,  i + 4)
      case 'f' if s.startsWith("false", i) => (Value.False, i + 5)
      case 'n' if s.startsWith("null", i)  => (Value.NullV,        i + 4)
      case c if c == '-' || c.isDigit =>
        var j = i
        if s.charAt(j) == '-' then j += 1
        while j < s.length && s.charAt(j).isDigit do j += 1
        val isFloat = j < s.length && (s.charAt(j) == '.' || s.charAt(j) == 'e' || s.charAt(j) == 'E')
        if isFloat then
          if j < s.length && s.charAt(j) == '.' then
            j += 1; while j < s.length && s.charAt(j).isDigit do j += 1
          if j < s.length && (s.charAt(j) == 'e' || s.charAt(j) == 'E') then
            j += 1
            if j < s.length && (s.charAt(j) == '+' || s.charAt(j) == '-') then j += 1
            while j < s.length && s.charAt(j).isDigit do j += 1
          (Value.doubleV(s.substring(i, j).toDouble), j)
        else
          (Value.intV(s.substring(i, j).toLong), j)
      case _ => (Value.NullV, i + 1)

  private def parseJsonString(s: String, start: Int): (String, Int) =
    val sb = new StringBuilder
    var i = start + 1  // skip opening quote
    while i < s.length && s.charAt(i) != '"' do
      if s.charAt(i) == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case '"'  => sb.append('"');  i += 2
          case '\\' => sb.append('\\'); i += 2
          case '/'  => sb.append('/');  i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case 'b'  => sb.append('\b'); i += 2
          case 'f'  => sb.append('\f'); i += 2
          case 'u' if i + 5 < s.length =>
            val hex = s.substring(i + 2, i + 6)
            sb.append(Integer.parseInt(hex, 16).toChar)
            i += 6
          case c => sb.append(c); i += 2
      else
        sb.append(s.charAt(i)); i += 1
    (sb.toString, i + 1)  // skip closing quote

  private def parseJsonObjectFrom(s: String, start: Int): (Value, Int) =
    val entries = scala.collection.mutable.Map.empty[Value, Value]
    var i = start + 1  // skip '{'
    while i < s.length && { var j = i; while j < s.length && s.charAt(j).isWhitespace do j += 1; i = j; i < s.length && s.charAt(i) != '}' } do
      if s.charAt(i) == ',' then i += 1
      while i < s.length && s.charAt(i).isWhitespace do i += 1
      if i < s.length && s.charAt(i) == '"' then
        val (key, afterKey) = parseJsonString(s, i)
        i = afterKey
        while i < s.length && s.charAt(i).isWhitespace do i += 1
        if i < s.length && s.charAt(i) == ':' then i += 1
        while i < s.length && s.charAt(i).isWhitespace do i += 1
        val (value, afterValue) = parseJsonValue(s, i)
        entries(Value.StringV(key)) = value
        i = afterValue
        while i < s.length && s.charAt(i).isWhitespace do i += 1
    if i < s.length && s.charAt(i) == '}' then i += 1
    (Value.MapV(entries.toMap), i)

  private def parseJsonArrayFrom(s: String, start: Int): (Value, Int) =
    val items = scala.collection.mutable.ListBuffer.empty[Value]
    var i = start + 1  // skip '['
    while i < s.length && { var j = i; while j < s.length && s.charAt(j).isWhitespace do j += 1; i = j; i < s.length && s.charAt(i) != ']' } do
      if s.charAt(i) == ',' then i += 1
      while i < s.length && s.charAt(i).isWhitespace do i += 1
      if i < s.length && s.charAt(i) != ']' then
        val (v, afterV) = parseJsonValue(s, i)
        items += v
        i = afterV
        while i < s.length && s.charAt(i).isWhitespace do i += 1
    if i < s.length && s.charAt(i) == ']' then i += 1
    (Value.ListV(items.toList), i)

  // ── helpers ───────────────────────────────────────────────────────────────

  private def isEitherRequest(typeName: String): Boolean =
    // Matches "Either[Request, X]" or "Either[Request,X]"
    typeName.startsWith("Either[Request,")

  private def extractStringMap(v: Option[Value]): Map[String, String] = v match
    case Some(Value.MapV(m)) =>
      m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }
    case Some(Value.InstanceV(_, fields)) =>
      fields.collect { case (k, Value.StringV(v)) => k -> v }
    case _ => Map.empty

  private def extractEitherRight(typeName: String): String =
    // "Either[Request, FooBar]" → "FooBar"
    val comma = typeName.indexOf(',')
    if comma < 0 then typeName
    else typeName.substring(comma + 1).stripSuffix("]").trim

  private def httpErrorResponse(status: Int, body: String): Value =
    Value.InstanceV("Response", Map(
      "status"  -> Value.intV(status),
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("application/json"))),
    ))

  private def httpJsonResponse(status: Int, body: String): Value =
    Value.InstanceV("Response", Map(
      "status"  -> Value.intV(status),
      "body"    -> Value.StringV(body),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("application/json"))),
    ))
