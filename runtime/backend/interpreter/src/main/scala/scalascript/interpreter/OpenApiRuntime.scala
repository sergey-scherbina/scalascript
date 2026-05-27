package scalascript.interpreter

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
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("text/html; charset=utf-8")
          )),
          "body" -> Value.StringV(swaggerUiHtml())
        ))
      }
    )

    if notYetRegistered("/_openapi.json") then
      registry.register("GET", "/_openapi.json", openapiHandler, interp)
    if notYetRegistered("/_swagger") then
      registry.register("GET", "/_swagger", swaggerHandler, interp)

  // ── OpenAPI 3.1 JSON generation ───────────────────────────────────────

  def generateOpenApiJson(registry: RouteRegistry): String =
    val userRoutes = registry.all.filterNot(e => e.path.startsWith("/_"))

    // Group by path (OpenAPI path item can have multiple methods)
    val byPath = userRoutes
      .groupBy(e => toOpenApiPath(e.path))
      .toList.sortBy(_._1)

    val sb = new StringBuilder()
    sb.append("{\n")
    sb.append("  \"openapi\": \"3.1.0\",\n")
    sb.append("  \"info\": { \"title\": \"ScalaScript API\", \"version\": \"1.0.0\" },\n")
    sb.append("  \"paths\": {")

    if byPath.isEmpty then
      sb.append("}\n")
    else
      sb.append("\n")
      var firstPath = true
      for (openApiPath, entries) <- byPath do
        if !firstPath then sb.append(",\n")
        firstPath = false
        sb.append(s"    ${jsonStr(openApiPath)}: {\n")

        var firstMethod = true
        for entry <- entries.sortBy(_.method) do
          if !firstMethod then sb.append(",\n")
          firstMethod = false
          val method = entry.method.toLowerCase
          val pathParams = extractPathParams(entry.path)
          val (queryParams, bodyParams) = extractHandlerParams(
            entry.handler, pathParams, entry.method
          )
          sb.append(s"      ${jsonStr(method)}: {\n")
          sb.append(s"        \"summary\": ${jsonStr(entry.method + " " + entry.path)},\n")

          // Parameters (path + query)
          val allParams =
            pathParams.map(n => paramEntry(n, "path", required = true)) ++
            queryParams.map { case (n, t) => paramEntry(n, "query", required = false, schemaType = jsonSchema(t)) }
          if allParams.nonEmpty then
            sb.append("        \"parameters\": [\n")
            sb.append(allParams.mkString(",\n"))
            sb.append("\n        ],\n")

          // Request body (POST/PUT/PATCH)
          if bodyParams.nonEmpty then
            val props = bodyParams.map { case (n, t) =>
              s"          ${jsonStr(n)}: ${jsonSchema(t)}"
            }.mkString(",\n")
            sb.append("        \"requestBody\": {\n")
            sb.append("          \"required\": true,\n")
            sb.append("          \"content\": {\n")
            sb.append("            \"application/json\": {\n")
            sb.append("              \"schema\": {\n")
            sb.append("                \"type\": \"object\",\n")
            sb.append("                \"properties\": {\n")
            sb.append(props).append("\n")
            sb.append("                }\n")
            sb.append("              }\n")
            sb.append("            }\n")
            sb.append("          }\n")
            sb.append("        },\n")

          sb.append("        \"responses\": { \"200\": { \"description\": \"OK\" } }\n")
          sb.append("      }")

        sb.append("\n    }")

      sb.append("\n  }")
      sb.append("\n}\n")

    sb.toString

  // ── Path helpers ──────────────────────────────────────────────────────

  private def toOpenApiPath(path: String): String =
    path.split('/').map { seg =>
      if seg.startsWith(":") then s"{${seg.tail}}" else seg
    }.mkString("/")

  private def extractPathParams(path: String): List[String] =
    path.split('/').collect {
      case seg if seg.startsWith(":") => seg.tail
    }.toList

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

  // ── JSON schema ───────────────────────────────────────────────────────

  private def jsonSchema(typeName: String): String = typeName match
    case "String"          => "{\"type\":\"string\"}"
    case "Int" | "Long"    => "{\"type\":\"integer\"}"
    case "Double" | "Float"=> "{\"type\":\"number\"}"
    case "Boolean"         => "{\"type\":\"boolean\"}"
    case _                 => "{\"type\":\"object\"}"

  private def paramEntry(
      name:       String,
      in:         String,
      required:   Boolean,
      schemaType: String = "{\"type\":\"string\"}"
  ): String =
    s"""          { "name": ${jsonStr(name)}, "in": "$in", "required": $required, "schema": $schemaType }"""

  // ── JSON utilities ────────────────────────────────────────────────────

  private def jsonStr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.append("\"").toString

  private def jsonResponse(body: String): Value =
    Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type")                -> Value.StringV("application/json"),
        Value.StringV("Access-Control-Allow-Origin") -> Value.StringV("*")
      )),
      "body" -> Value.StringV(body)
    ))

  // ── Swagger UI ────────────────────────────────────────────────────────

  private def swaggerUiHtml(): String =
    """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>API Docs — ScalaScript</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css"/>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    SwaggerUIBundle({
      url: "/_openapi.json",
      dom_id: "#swagger-ui",
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
      layout: "BaseLayout",
      deepLinking: true
    });
  </script>
</body>
</html>"""
