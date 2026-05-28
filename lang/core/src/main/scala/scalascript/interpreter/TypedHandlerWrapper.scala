package scalascript.interpreter

/** Auto-deserialization / serialization wrapper for typed route handlers.
 *
 *  A handler is "typed" if any of its non-trailing parameters has a type
 *  annotation that is not a special pass-through type (`Request`, `Map`,
 *  `Map[String,Any]`, `Map[String, Any]`).  When detected, [[wrapIfTyped]]
 *  wraps it in a `NativeFnV(List(reqValue) => responseValue)` that:
 *
 *  1. Extracts path/query/body from the incoming `Request`.
 *  2. Deserializes each typed input parameter from those sources (path wins
 *     over query wins over body).
 *  3. Invokes the original handler with the deserialized arguments plus the
 *     trailing `Request` / `Map` if the handler declared them.
 *  4. Serializes the result to a `Response` (case class → JSON 200, etc.).
 *
 *  Invocation and constructor lookup are decoupled from `Interpreter`
 *  to avoid a circular build dependency (`httpPlugin → backendInterpreter →
 *  httpPlugin`).  Both are provided as callbacks by the wiring site
 *  (either `Interpreter.mountFileAsRoute` or `NativeContext.registerMountedRoute`). */
object TypedHandlerWrapper:

  // ── Public API ─────────────────────────────────────────────────────────────

  /** If `handler` is a typed handler (has non-special typed params), wrap it.
   *  Otherwise return `handler` unchanged (raw `Request => Response`).
   *
   *  @param handler      the value produced by the handler file
   *  @param invoke       function to call the handler: `(fn, args) => result`
   *  @param globalsView  map to look up case-class constructors by type name;
   *                      typically the handler file's interpreter globals or the
   *                      handler function's own closure env
   *  @param mountedPath  the path pattern, e.g. `"/hello/:name"`
   *  @param errorDetails when true, include field name in 400 error body */
  def wrapIfTyped(
    handler:      Value,
    invoke:       (Value, List[Value]) => Value,
    globalsView:  collection.Map[String, Value],
    @annotation.unused mountedPath:  String,
    errorDetails: Boolean = true,
  ): Value =
    handler match
      case f: Value.FunV if isTypedHandler(f) =>
        buildWrapper(f, invoke, globalsView, errorDetails)
      case _ => handler

  // ── Special / primitive type sets ─────────────────────────────────────────

  private val specialTypes   = Set("Request", "Map", "Map[String,Any]", "Map[String, Any]")
  private val primitiveTypes = Set("String", "Int", "Long", "Double", "Float", "Boolean")

  private def isTypedHandler(f: Value.FunV): Boolean =
    val (dataParams, _) = splitParams(f)
    dataParams.nonEmpty && dataParams.exists { case (_, t) => t.nonEmpty && !specialTypes.contains(t) }

  // ── Parameter splitting ────────────────────────────────────────────────────

  /** Returns `(dataParams, (trailingRequest, trailingCtx))`.
   *
   *  Peels special trailing params from the right:
   *  - last param typed `Map` / `Map[String,Any]` → trailingCtx
   *  - next-to-last (or last if no Map) typed `Request` → trailingRequest */
  private def splitParams(f: Value.FunV): (List[(String, String)], (Boolean, Boolean)) =
    val names  = f.params
    val types  = f.paramTypes.padTo(names.length, "")
    var paired = names.zip(types)

    var trailingCtx     = false
    var trailingRequest = false

    paired.lastOption match
      case Some((_, t)) if t == "Map" || t == "Map[String,Any]" || t == "Map[String, Any]" =>
        trailingCtx = true
        paired = paired.dropRight(1)
      case _ =>

    paired.lastOption match
      case Some((_, t)) if t == "Request" =>
        trailingRequest = true
        paired = paired.dropRight(1)
      case _ =>

    (paired, (trailingRequest, trailingCtx))

  // ── Wrapper construction ────────────────────────────────────────────────────

  private def buildWrapper(
    f:            Value.FunV,
    invoke:       (Value, List[Value]) => Value,
    globalsView:  collection.Map[String, Value],
    errorDetails: Boolean,
  ): Value =
    val (dataParams, (trailingRequest, trailingCtx)) = splitParams(f)

    // Either[Request, Input] single-param special case
    val isEitherInput =
      dataParams.length == 1 && dataParams.head._2.startsWith("Either[")

    // Constructor lookup: prefer the handler's own closure (captures the
    // handler file's globals at definition time), then the provided globalsView.
    // Merge into a plain Map so no abstract method issues arise.
    val ctorLookup: collection.Map[String, Value] =
      if f.closure.nonEmpty then
        // closure takes priority; globalsView fills in anything missing
        val base = scala.collection.mutable.Map.from(globalsView)
        f.closure.foreach { case (k, v) => base(k) = v }
        base
      else globalsView

    Value.NativeFnV("typed-handler", Computation.pureFn { args =>
      val reqValue    = args.head.asInstanceOf[Value.InstanceV]
      val pathParams  = extractMap(reqValue.fields.get("params"))
      val queryParams = extractMap(reqValue.fields.get("query"))
      val bodyStr     = extractBodyStr(reqValue.fields.get("body"))
      val jsonBody    = bodyStr.flatMap(s => JsonParser.parseOption(s))

      if isEitherInput then
        handleEitherInput(f, dataParams.head._2, reqValue,
          pathParams, queryParams, jsonBody, ctorLookup, invoke)
      else
        handleNormalInput(f, dataParams, trailingRequest, trailingCtx,
          reqValue, pathParams, queryParams, jsonBody, ctorLookup, invoke, errorDetails)
    })

  // ── Either[Request, Input] dispatch ────────────────────────────────────────

  private def handleEitherInput(
    f:          Value.FunV,
    paramType:  String,
    reqValue:   Value.InstanceV,
    path:       Map[String, String],
    query:      Map[String, String],
    body:       Option[Value],
    ctorLookup: collection.Map[String, Value],
    invoke:     (Value, List[Value]) => Value,
  ): Value =
    val innerType = extractEitherRight(paramType)
    val paramName = f.params.headOption.getOrElse("input")
    val inputOpt  = tryDeserialize(innerType, paramName, path, query, body, ctorLookup)
    val arg = inputOpt match
      case None        => Value.InstanceV("Left",  Map("value" -> reqValue))
      case Some(input) => Value.InstanceV("Right", Map("value" -> input))
    serializeOutput(invoke(f, List(arg)))

  // ── Normal typed input dispatch ────────────────────────────────────────────

  private def handleNormalInput(
    f:               Value.FunV,
    dataParams:      List[(String, String)],
    trailingRequest: Boolean,
    trailingCtx:     Boolean,
    reqValue:        Value.InstanceV,
    path:            Map[String, String],
    query:           Map[String, String],
    body:            Option[Value],
    ctorLookup:      collection.Map[String, Value],
    invoke:          (Value, List[Value]) => Value,
    errorDetails:    Boolean,
  ): Value =
    var dataArgs = List.empty[Value]
    var failMsg: String | Null = null

    val iter = dataParams.iterator
    while iter.hasNext && failMsg == null do
      val (pName, pType) = iter.next()
      val v: Option[Value] =
        if primitiveTypes.contains(pType) then
          lookupScalar(pName, pType, path, query, body)
        else if pType.nonEmpty then
          deserializeCaseClass(pType, path, query, body, ctorLookup)
        else
          Some(reqValue)

      v match
        case None     =>
          failMsg = if errorDetails then s"missing field: $pName" else ""
        case Some(dv) =>
          dataArgs = dataArgs :+ dv

    if failMsg != null then
      val body0 = if failMsg.isEmpty then "{}" else s"""{"error":"${failMsg.nn}"}"""
      mkResponse(400, body0, "application/json")
    else
      val ctxMap = Value.EmptyMap
      val trailingArgs =
        (if trailingRequest then List(reqValue) else Nil) ++
        (if trailingCtx     then List(ctxMap)   else Nil)
      serializeOutput(invoke(f, dataArgs ++ trailingArgs))

  // ── Deserialization helpers ────────────────────────────────────────────────

  private def lookupScalar(
    name:  String,
    tpe:   String,
    path:  Map[String, String],
    query: Map[String, String],
    body:  Option[Value],
  ): Option[Value] =
    val rawOpt: Option[String] =
      path.get(name)
        .orElse(query.get(name))
        .orElse {
          body match
            case Some(Value.MapV(m)) =>
              m.get(Value.StringV(name)).map {
                case Value.StringV(s) => s
                case other            => Value.show(other)
              }
            case _ => None
        }
    rawOpt.map(raw => coercePrimitive(tpe, raw))

  private def coercePrimitive(tpe: String, raw: String): Value = tpe match
    case "Int" | "Long"     => Value.intV(raw.trim.toLong)
    case "Double" | "Float" => Value.doubleV(raw.trim.toDouble)
    case "Boolean"          => Value.boolV(raw.trim.toLowerCase == "true")
    case _                  => Value.StringV(raw)

  private def deserializeCaseClass(
    typeName:   String,
    path:       Map[String, String],
    query:      Map[String, String],
    body:       Option[Value],
    ctorLookup: collection.Map[String, Value],
  ): Option[Value] =
    ctorLookup.get(typeName) match
      case Some(ctor: Value.FunV) =>
        val fieldNames = ctor.params
        val fieldTypes = ctor.paramTypes.padTo(fieldNames.length, "String")
        val fields     = scala.collection.mutable.Map.empty[String, Value]
        var ok         = true
        for (fName, fType) <- fieldNames.zip(fieldTypes) do
          if ok then
            lookupScalar(fName, fType, path, query, body) match
              case None     => ok = false
              case Some(dv) => fields(fName) = dv
        if ok then Some(Value.InstanceV(typeName, fields.toMap)) else None
      case _ =>
        // Unknown type — pass raw params as a map
        Some(Value.MapV(
          (path ++ query).map((k, v) =>
            (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap
        ))

  private def tryDeserialize(
    typeName:   String,
    paramName:  String,
    path:       Map[String, String],
    query:      Map[String, String],
    body:       Option[Value],
    ctorLookup: collection.Map[String, Value],
  ): Option[Value] =
    if primitiveTypes.contains(typeName) then lookupScalar(paramName, typeName, path, query, body)
    else deserializeCaseClass(typeName, path, query, body, ctorLookup)

  // ── Output serialization ───────────────────────────────────────────────────

  private def serializeOutput(v: Value): Value = v match
    case r @ Value.InstanceV("Response", _) => r
    case Value.InstanceV("Left",  fields)   =>
      fields.get("value").map(serializeOutput).getOrElse(mkResponse(200, "null", "application/json"))
    case Value.InstanceV("Right", fields)   =>
      fields.get("value").map(serializeOutput).getOrElse(mkResponse(200, "null", "application/json"))
    case _                                  =>
      mkResponse(200, jsonToJson(v), "application/json")

  // ── Utility ────────────────────────────────────────────────────────────────

  private def mkResponse(status: Int, body: String, ct: String): Value =
    val hdrs: Map[Value, Value] =
      if ct.nonEmpty then
        Map((Value.StringV("Content-Type"): Value) -> (Value.StringV(ct): Value))
      else Map.empty
    Value.InstanceV("Response", Map(
      "status"  -> Value.intV(status),
      "headers" -> Value.MapV(hdrs),
      "body"    -> Value.StringV(body),
    ))

  private def extractMap(v: Option[Value]): Map[String, String] = v match
    case Some(Value.MapV(m)) => m.collect {
      case (Value.StringV(k), Value.StringV(sv)) => k -> sv
    }.toMap
    case _ => Map.empty

  private def extractBodyStr(v: Option[Value]): Option[String] = v match
    case Some(Value.StringV(s)) if s.nonEmpty                    => Some(s)
    case Some(Value.InstanceV("Some", f))                        =>
      f.get("value").collect { case Value.StringV(s) if s.nonEmpty => s }
    case Some(Value.OptionV(Some(Value.StringV(s)))) if s.nonEmpty => Some(s)
    case _                                                        => None

  private def extractEitherRight(t: String): String =
    val inner = t.stripPrefix("Either[").stripSuffix("]")
    val comma = inner.indexOf(',')
    if comma >= 0 then inner.substring(comma + 1).trim else inner
