package scalascript.interpreter

import scalascript.backend.spi.OpenApiGenerator
import scalascript.backend.spi.NativeContextFeatureKeys
import scalascript.backend.spi.OpenApiGenerator.{OpenApiOptions, OpenApiParam, OpenApiRoute, OpenApiSecurityScheme, ParamLocation, SchemaNode}
import scalascript.server.RouteRegistry

/** Registers built-in `/_openapi.json` and `/_swagger` routes.
 *
 *  `/_openapi.json` — auto-derived OpenAPI 3.1 document from the current
 *  `RouteRegistry`; generated on every request so it stays in sync with
 *  hot-reloaded route changes.  Internal routes (paths starting with `/_`)
 *  are excluded from the document.
 *
 *  `/_swagger` — Swagger UI HTML page pointing at `/_openapi.json`
 *  (CDN-linked; no bundled assets).
 *
 *  Called from `Interpreter.registerOpenApiDefaults()`, which is invoked
 *  by `HttpIntrinsics` alongside `registerHealthDefaults()` when the user
 *  calls `serve()` or `serveAsync()`. */
object OpenApiRuntime:

  private val SpecialParamTypes =
    Set("Request", "Map", "Map[String,Any]", "Map[String, Any]", "")

  def registerOpenApiDefaults(interp: Interpreter): Unit =
    val registry = interp.routeRegistry

    def notYetRegistered(path: String): Boolean =
      !registry.all.exists(e => e.method == "GET" && e.path == path)

    val openapiHandler = Value.NativeFnV(
      "_openapi.json",
      Computation.pureFn { _ =>
        val json = generateOpenApiJson(registry, openApiSecuritySchemes(interp), OpenApiOptions(),
                                      Map.empty, deriveSchemaComponents(interp))
        jsonResponse(json)
      }
    )

    val swaggerHandler = Value.NativeFnV(
      "_swagger",
      Computation.pureFn { _ =>
        Value.InstanceV("Response", Map(
          "status"  -> Value.intV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("text/html; charset=utf-8")
          )),
          "body" -> Value.StringV(OpenApiGenerator.swaggerUiHtml())
        ))
      }
    )

    if notYetRegistered("/_openapi.json") then
      registry.register("GET", "/_openapi.json", openapiHandler, interp)
    if notYetRegistered("/_swagger") then
      registry.register("GET", "/_swagger", swaggerHandler, interp)

  // ── OpenAPI 3.1 JSON generation ───────────────────────────────────────

  def generateOpenApiJson(registry: RouteRegistry): String =
    generateOpenApiJson(registry, Nil)

  def generateOpenApiJson(
      registry:        RouteRegistry,
      securitySchemes: Iterable[OpenApiSecurityScheme]
  ): String =
    generateOpenApiJson(registry, securitySchemes, OpenApiOptions(), Map.empty)

  def generateOpenApiJson(
      registry:         RouteRegistry,
      securitySchemes:  Iterable[OpenApiSecurityScheme],
      options:          OpenApiOptions,
      responseTypes:    Map[(String, String), String],
      schemaComponents: Map[String, SchemaNode]          = Map.empty
  ): String =
    OpenApiGenerator.generate(openApiRoutes(registry, responseTypes), securitySchemes, options, schemaComponents)

  def generateOpenApiYaml(
      registry:         RouteRegistry,
      securitySchemes:  Iterable[OpenApiSecurityScheme],
      options:          OpenApiOptions,
      responseTypes:    Map[(String, String), String],
      schemaComponents: Map[String, SchemaNode]          = Map.empty
  ): String =
    OpenApiGenerator.generateYaml(openApiRoutes(registry, responseTypes), securitySchemes, options, schemaComponents)

  def openApiSecuritySchemes(interp: Interpreter): List[OpenApiSecurityScheme] =
    interp.nativeFeatureGet(NativeContextFeatureKeys.OpenApiSecuritySchemes)
      .collect { case xs: List[?] => xs.collect { case s: OpenApiSecurityScheme => s } }
      .getOrElse(Nil)

  def openApiSchemaComponents(interp: Interpreter): Map[String, SchemaNode] =
    interp.nativeFeatureGet(NativeContextFeatureKeys.OpenApiSchemaComponents)
      .collect { case m: Map[?, ?] =>
        m.collect { case (k: String, v: SchemaNode) => k -> v }.toMap[String, SchemaNode]
      }
      .getOrElse(Map.empty[String, SchemaNode])

  /** Phase 6 — auto-derive `components.schemas` from program type declarations.
   *
   *  Seeds from the type names referenced by registered routes (handler param
   *  types) and by manually-registered schemas, then walks the interpreter's
   *  type-declaration maps to build a [[SchemaNode]] for each, following nested
   *  `$ref`s to a fixpoint.  Manually-registered schemas (via
   *  `openApiRegisterSchema`) take precedence over derived ones.
   *
   *   - case class           → `ObjNode` (fields as properties; non-Option,
   *                            no-default fields are `required`)
   *   - enum, all cases bare → `EnumNode` (string enum of case names)
   *   - sealed parent / enum
   *     with payload cases    → `OneOfNode` of `RefNode` per case
   */
  def deriveSchemaComponents(interp: Interpreter): Map[String, SchemaNode] =
    val manual = openApiSchemaComponents(interp)

    val seeds = scala.collection.mutable.LinkedHashSet.empty[String]
    interp.routeRegistry.all.foreach { entry =>
      val pathParams = OpenApiGenerator.extractPathParams(entry.path)
      val (queryParams, bodyParams) = extractHandlerParams(entry.handler, pathParams, entry.method)
      (queryParams ++ bodyParams).foreach { case (_, t) =>
        collectNodeRefs(SchemaNode.fromTypeName(t), seeds)
      }
    }
    manual.values.foreach(collectNodeRefs(_, seeds))

    val derived = scala.collection.mutable.Map.empty[String, SchemaNode]
    val queue   = scala.collection.mutable.Queue.from(seeds)
    while queue.nonEmpty do
      val name = queue.dequeue()
      if !derived.contains(name) && !manual.contains(name) then
        deriveType(name, interp).foreach { node =>
          derived(name) = node
          val refs = scala.collection.mutable.LinkedHashSet.empty[String]
          collectNodeRefs(node, refs)
          refs.foreach(queue.enqueue)
        }

    derived.toMap ++ manual

  /** Collect every component name referenced by a [[SchemaNode]] (recursively). */
  private def collectNodeRefs(node: SchemaNode, acc: scala.collection.mutable.Set[String]): Unit =
    node match
      case SchemaNode.RefNode(name)       => acc += name
      case SchemaNode.NullableNode(inner) => collectNodeRefs(inner, acc)
      case SchemaNode.ArrNode(items)      => collectNodeRefs(items, acc)
      case SchemaNode.OneOfNode(options)  => options.foreach(collectNodeRefs(_, acc))
      case SchemaNode.ObjNode(props, _)   => props.values.foreach(collectNodeRefs(_, acc))
      case _                              => ()

  /** Derive a single named type from the interpreter's type-declaration maps.
   *  Returns `None` for unknown types (the `$ref` is then left dangling, as
   *  before this phase). */
  private def deriveType(name: String, interp: Interpreter): Option[SchemaNode] =
    val children = interp.parentTypes.collect { case (c, p) if p == name => c }.toList.sorted
    if children.nonEmpty then
      val payloadCases = children.filter(interp.typeFieldOrder.contains)
      if payloadCases.isEmpty then Some(SchemaNode.EnumNode(children))
      else Some(SchemaNode.OneOfNode(children.map(SchemaNode.RefNode(_))))
    else interp.typeFieldOrder.get(name).map { fields =>
      val fieldTypes = interp.typeFieldTypes.getOrElse(name, fields.map(_ => ""))
      val schemas    = interp.typeFieldSchemas.getOrElse(name, Nil)
      val props = fields.zip(fieldTypes.padTo(fields.length, "")).map {
        case (f, t) => f -> SchemaNode.fromTypeName(t)
      }.toMap
      val required = fields.zip(fieldTypes.padTo(fields.length, "")).collect {
        case (f, t)
          if !t.trim.startsWith("Option[")
          && schemas.find(_.fieldName == f).flatMap(_.default).isEmpty => f
      }
      SchemaNode.ObjNode(props, required)
    }

  private def openApiRoutes(
      registry:      RouteRegistry,
      responseTypes: Map[(String, String), String]
  ): Iterable[OpenApiRoute] =
    registry.all.map { entry =>
      val pathParams = OpenApiGenerator.extractPathParams(entry.path)
      val (queryParams, bodyParams) = extractHandlerParams(entry.handler, pathParams, entry.method)
      val params =
        queryParams.map { case (n, t) => OpenApiParam(n, t, ParamLocation.Query) } ++
        bodyParams.map { case (n, t) => OpenApiParam(n, t, ParamLocation.Body) }
      val responseType = responseTypes.get(entry.method.toUpperCase -> entry.path)
      OpenApiRoute(entry.method, entry.path, params, responseType = responseType, metadata = entry.metadata)
    }

  // ── Handler type extraction ───────────────────────────────────────────

  private def extractHandlerParams(
      handler:    Value,
      pathParams: List[String],
      method:     String
  ): (List[(String, String)], List[(String, String)]) =
    val isBodyMethod = Set("POST", "PUT", "PATCH").contains(method.toUpperCase)
    handler match
      case f: Value.FunV =>
        val pairs = f.params.zip(f.paramTypes.padTo(f.params.length, ""))
        val typed = pairs.filterNot { case (_, t) => SpecialParamTypes.contains(t) }
        val nonPath = typed.filterNot { case (n, _) => pathParams.contains(n) }
        if isBodyMethod then (Nil, nonPath)
        else (nonPath, Nil)
      case _ => (Nil, Nil)

  private def jsonResponse(body: String): Value =
    Value.InstanceV("Response", Map(
      "status"  -> Value.intV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type")                -> Value.StringV("application/json"),
        Value.StringV("Access-Control-Allow-Origin") -> Value.StringV("*")
      )),
      "body" -> Value.StringV(body)
    ))
