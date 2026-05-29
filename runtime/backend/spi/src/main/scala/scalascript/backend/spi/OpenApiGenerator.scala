package scalascript.backend.spi

/** Shared OpenAPI 3.1 generator used by interpreter and generated JVM servers.
 *
 *  Route registries stay backend-local; they adapt their entries into
 *  [[OpenApiRoute]] so the document shape, escaping, parameter inference, and
 *  Swagger UI stay identical across runtimes.
 */
object OpenApiGenerator:

  final case class OpenApiRoute(
      method:       String,
      path:         String,
      params:       List[OpenApiParam] = Nil,
      responseType: Option[String]     = None,
      metadata:     OpenApiMetadata    = OpenApiMetadata()
  )

  final case class OpenApiParam(name: String, typeName: String, location: ParamLocation)

  final case class OpenApiMetadata(
      summary:     Option[String] = None,
      description: Option[String] = None,
      tags:        List[String]   = Nil,
      deprecated:  Boolean        = false
  )

  enum ParamLocation:
    case Query, Body

  def generate(routes: Iterable[OpenApiRoute]): String =
    val userRoutes = routes.filterNot(_.path.startsWith("/_")).toList
    val byPath = userRoutes
      .groupBy(route => toOpenApiPath(route.path))
      .toList
      .sortBy(_._1)

    val sb = new StringBuilder()
    sb.append("{\n")
    sb.append("  \"openapi\": \"3.1.0\",\n")
    sb.append("  \"info\": { \"title\": \"ScalaScript API\", \"version\": \"1.0.0\" },\n")
    sb.append("  \"paths\": {")

    if byPath.isEmpty then sb.append("}\n}\n")
    else
      sb.append("\n")
      var firstPath = true
      for (openApiPath, entries) <- byPath do
        if !firstPath then sb.append(",\n")
        firstPath = false
        sb.append(s"    ${jsonStr(openApiPath)}: {\n")

        var firstMethod = true
        for route <- entries.sortBy(_.method) do
          if !firstMethod then sb.append(",\n")
          firstMethod = false
          val method = route.method.toLowerCase
          val pathParams = extractPathParams(route.path)
          val queryParams = route.params.filter(_.location == ParamLocation.Query)
          val bodyParams = route.params.filter(_.location == ParamLocation.Body)

          sb.append(s"      ${jsonStr(method)}: {\n")
          val metadata = route.metadata
          val summary = metadata.summary.filter(_.nonEmpty).getOrElse(route.method.toUpperCase + " " + route.path)
          sb.append(s"        \"summary\": ${jsonStr(summary)},\n")
          metadata.description.filter(_.nonEmpty).foreach { description =>
            sb.append(s"        \"description\": ${jsonStr(description)},\n")
          }
          if metadata.tags.nonEmpty then
            sb.append(s"        \"tags\": [${metadata.tags.map(jsonStr).mkString(", ")}],\n")
          if metadata.deprecated then
            sb.append("        \"deprecated\": true,\n")

          val allParams =
            pathParams.map(n => paramEntry(n, "path", required = true)) ++
            queryParams.map(p => paramEntry(p.name, "query", required = false, schemaType = jsonSchema(p.typeName)))
          if allParams.nonEmpty then
            sb.append("        \"parameters\": [\n")
            sb.append(allParams.mkString(",\n"))
            sb.append("\n        ],\n")

          if bodyParams.nonEmpty then
            val props = bodyParams.map { p =>
              s"          ${jsonStr(p.name)}: ${jsonSchema(p.typeName)}"
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

          sb.append("        \"responses\": ")
          sb.append(responseSchema(route.responseType))
          sb.append("\n")
          sb.append("      }")

        sb.append("\n    }")

      sb.append("\n  }")
      sb.append("\n}\n")

    sb.toString

  def swaggerUiHtml(openApiPath: String = "/_openapi.json"): String =
    s"""<!DOCTYPE html>
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
      url: "${escapeHtmlAttr(openApiPath)}",
      dom_id: "#swagger-ui",
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
      layout: "BaseLayout",
      deepLinking: true
    });
  </script>
</body>
</html>"""

  def toOpenApiPath(path: String): String =
    path.split('/').map { seg =>
      if seg.startsWith(":") then s"{${seg.tail}}" else seg
    }.mkString("/")

  def extractPathParams(path: String): List[String] =
    path.split('/').collect {
      case seg if seg.startsWith(":") => seg.tail
    }.toList

  def jsonSchema(typeName: String): String = typeName match
    case "String"                                  => "{\"type\":\"string\"}"
    case "Int" | "Long" | "Short" | "Byte"         => "{\"type\":\"integer\"}"
    case "Double" | "Float" | "BigDecimal"         => "{\"type\":\"number\"}"
    case "Boolean"                                 => "{\"type\":\"boolean\"}"
    case "Unit"                                    => "{\"type\":\"null\"}"
    case t if t.startsWith("List[") || t.startsWith("Seq[") || t.startsWith("Array[") =>
      "{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"
    case _                                         => "{\"type\":\"object\"}"

  private def responseSchema(responseType: Option[String]): String =
    responseType.filter(t => t.nonEmpty && t != "Response" && t != "Any").map { t =>
      s"""{ "200": { "description": "OK", "content": { "application/json": { "schema": ${jsonSchema(t)} } } } }"""
    }.getOrElse("""{ "200": { "description": "OK" } }""")

  private def paramEntry(
      name:       String,
      in:         String,
      required:   Boolean,
      schemaType: String = "{\"type\":\"string\"}"
  ): String =
    s"""          { "name": ${jsonStr(name)}, "in": "$in", "required": $required, "schema": $schemaType }"""

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

  private def escapeHtmlAttr(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '"'  => "&quot;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case c    => c.toString
    }
