package scalascript.interpreter

import scalascript.backend.spi.OpenApiGenerator
import scalascript.backend.spi.OpenApiGenerator.{OpenApiParam, OpenApiRoute, ParamLocation}
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
private[interpreter] object OpenApiRuntime:

  private val SpecialParamTypes =
    Set("Request", "Map", "Map[String,Any]", "Map[String, Any]", "")

  def registerOpenApiDefaults(interp: Interpreter): Unit =
    val registry = interp.routeRegistry

    def notYetRegistered(path: String): Boolean =
      !registry.all.exists(e => e.method == "GET" && e.path == path)

    val openapiHandler = Value.NativeFnV(
      "_openapi.json",
      Computation.pureFn { _ =>
        val json = generateOpenApiJson(registry)
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
    OpenApiGenerator.generate(registry.all.map { entry =>
      val pathParams = OpenApiGenerator.extractPathParams(entry.path)
      val (queryParams, bodyParams) = extractHandlerParams(entry.handler, pathParams, entry.method)
      val params =
        queryParams.map { case (n, t) => OpenApiParam(n, t, ParamLocation.Query) } ++
        bodyParams.map { case (n, t) => OpenApiParam(n, t, ParamLocation.Body) }
      OpenApiRoute(entry.method, entry.path, params, metadata = entry.metadata)
    })

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
