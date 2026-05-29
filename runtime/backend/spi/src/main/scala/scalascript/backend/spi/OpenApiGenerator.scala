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
      deprecated:  Boolean        = false,
      security:    List[String]   = Nil
  )

  final case class OpenApiSecurityScheme(
      name:   String,
      scheme: String,
      format: String = ""
  )

  final case class OpenApiOptions(
      title:   String       = "ScalaScript API",
      version: String       = "1.0.0",
      servers: List[String] = Nil
  )

  enum ParamLocation:
    case Query, Body

  def generate(
      routes:          Iterable[OpenApiRoute],
      securitySchemes: Iterable[OpenApiSecurityScheme] = Nil,
      options:         OpenApiOptions                  = OpenApiOptions()
  ): String =
    val userRoutes = routes.filterNot(_.path.startsWith("/_")).toList
    val byPath = userRoutes
      .groupBy(route => toOpenApiPath(route.path))
      .toList
      .sortBy(_._1)

    val sb = new StringBuilder()
    sb.append("{\n")
    sb.append("  \"openapi\": \"3.1.0\",\n")
    sb.append(s"  \"info\": { \"title\": ${jsonStr(options.title)}, \"version\": ${jsonStr(options.version)} }")
    val servers = options.servers.map(_.trim).filter(_.nonEmpty)
    if servers.nonEmpty then
      sb.append(",\n")
      sb.append("  \"servers\": [")
      sb.append(servers.map(url => s"{ \"url\": ${jsonStr(url)} }").mkString(", "))
      sb.append("]")
    sb.append(",\n")
    sb.append("  \"paths\": {")

    if byPath.isEmpty then sb.append("}")
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
          if metadata.security.nonEmpty then
            sb.append(s"        \"security\": [${metadata.security.map(n => s"{ ${jsonStr(n)}: [] }").mkString(", ")}],\n")

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

    val schemes = securitySchemes.toList.filter(_.name.nonEmpty)
    if schemes.nonEmpty then
      sb.append(",\n")
      sb.append("  \"components\": {\n")
      sb.append("    \"securitySchemes\": {\n")
      sb.append(schemes.map(securitySchemeEntry).mkString(",\n"))
      sb.append("\n    }\n")
      sb.append("  }")

    sb.append("\n}\n")
    sb.toString

  def generateYaml(
      routes:          Iterable[OpenApiRoute],
      securitySchemes: Iterable[OpenApiSecurityScheme] = Nil,
      options:         OpenApiOptions                  = OpenApiOptions()
  ): String =
    val userRoutes = routes.filterNot(_.path.startsWith("/_")).toList
    val byPath = userRoutes
      .groupBy(route => toOpenApiPath(route.path))
      .toList
      .sortBy(_._1)
    val schemes = securitySchemes.toList.filter(_.name.nonEmpty)

    val sb = new StringBuilder()
    sb.append("openapi: 3.1.0\n")
    sb.append("info:\n")
    sb.append(s"  title: ${yamlStr(options.title)}\n")
    sb.append(s"  version: ${yamlStr(options.version)}\n")
    val servers = options.servers.map(_.trim).filter(_.nonEmpty)
    if servers.nonEmpty then
      sb.append("servers:\n")
      servers.foreach(url => sb.append(s"  - url: ${yamlStr(url)}\n"))
    sb.append("paths:\n")
    if byPath.isEmpty then
      sb.append("  {}\n")
    else
      for (openApiPath, entries) <- byPath do
        sb.append(s"  ${yamlStr(openApiPath)}:\n")
        for route <- entries.sortBy(_.method) do
          val method = route.method.toLowerCase
          val pathParams = extractPathParams(route.path)
          val queryParams = route.params.filter(_.location == ParamLocation.Query)
          val bodyParams = route.params.filter(_.location == ParamLocation.Body)
          val metadata = route.metadata
          val summary = metadata.summary.filter(_.nonEmpty).getOrElse(route.method.toUpperCase + " " + route.path)
          sb.append(s"    $method:\n")
          sb.append(s"      summary: ${yamlStr(summary)}\n")
          metadata.description.filter(_.nonEmpty).foreach { description =>
            sb.append(s"      description: ${yamlStr(description)}\n")
          }
          if metadata.tags.nonEmpty then
            sb.append("      tags:\n")
            metadata.tags.foreach(tag => sb.append(s"        - ${yamlStr(tag)}\n"))
          if metadata.deprecated then
            sb.append("      deprecated: true\n")
          if metadata.security.nonEmpty then
            sb.append("      security:\n")
            metadata.security.foreach(name => sb.append(s"        - ${yamlStr(name)}: []\n"))

          val allParams =
            pathParams.map(n => (n, "path", true, "{\"type\":\"string\"}")) ++
            queryParams.map(p => (p.name, "query", false, jsonSchema(p.typeName)))
          if allParams.nonEmpty then
            sb.append("      parameters:\n")
            allParams.foreach { case (name, in, required, schema) =>
              sb.append(s"        - name: ${yamlStr(name)}\n")
              sb.append(s"          in: $in\n")
              sb.append(s"          required: $required\n")
              appendYamlSchema(sb, "          ", schema)
            }
          if bodyParams.nonEmpty then
            sb.append("      requestBody:\n")
            sb.append("        required: true\n")
            sb.append("        content:\n")
            sb.append("          application/json:\n")
            sb.append("            schema:\n")
            sb.append("              type: object\n")
            sb.append("              properties:\n")
            bodyParams.foreach { p =>
              sb.append(s"                ${yamlStr(p.name)}:\n")
              appendYamlSchema(sb, "                  ", jsonSchema(p.typeName), includeKey = false)
            }
          appendYamlResponses(sb, route.responseType)

    if schemes.nonEmpty then
      sb.append("components:\n")
      sb.append("  securitySchemes:\n")
      schemes.foreach { scheme =>
        sb.append(s"    ${yamlStr(scheme.name)}:\n")
        if scheme.scheme.equalsIgnoreCase("apiKey") || scheme.scheme.equalsIgnoreCase("api-key") then
          val headerName = Option(scheme.format).map(_.trim).filter(_.nonEmpty).getOrElse("X-API-Key")
          sb.append("      type: apiKey\n")
          sb.append("      in: header\n")
          sb.append(s"      name: ${yamlStr(headerName)}\n")
        else
          sb.append("      type: http\n")
          sb.append(s"      scheme: ${yamlStr(scheme.scheme.toLowerCase)}\n")
          Option(scheme.format).map(_.trim).filter(_.nonEmpty).foreach { format =>
            sb.append(s"      bearerFormat: ${yamlStr(format)}\n")
          }
      }

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

  private def securitySchemeEntry(scheme: OpenApiSecurityScheme): String =
    val body =
      if scheme.scheme.equalsIgnoreCase("apiKey") || scheme.scheme.equalsIgnoreCase("api-key") then
        val headerName = Option(scheme.format).map(_.trim).filter(_.nonEmpty).getOrElse("X-API-Key")
        s"""{ "type": "apiKey", "in": "header", "name": ${jsonStr(headerName)} }"""
      else
        val fmt = Option(scheme.format).map(_.trim).filter(_.nonEmpty)
          .map(f => s""", "bearerFormat": ${jsonStr(f)}""").getOrElse("")
        s"""{ "type": "http", "scheme": ${jsonStr(scheme.scheme.toLowerCase)}$fmt }"""
    s"      ${jsonStr(scheme.name)}: $body"

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

  private def yamlStr(s: String): String =
    val needsQuotes =
      s.isEmpty ||
        s.exists(c => c.isWhitespace || ":{}[]&,*#?|<>=!%@`\"'".contains(c)) ||
        Set("true", "false", "null", "~").contains(s.toLowerCase)
    if !needsQuotes then s
    else jsonStr(s)

  private def appendYamlSchema(
      sb:         StringBuilder,
      indent:     String,
      schemaJson: String,
      includeKey: Boolean = true
  ): Unit =
    val schema =
      if schemaJson.contains("\"type\":\"string\"") then "string"
      else if schemaJson.contains("\"type\":\"integer\"") then "integer"
      else if schemaJson.contains("\"type\":\"number\"") then "number"
      else if schemaJson.contains("\"type\":\"boolean\"") then "boolean"
      else if schemaJson.contains("\"type\":\"null\"") then "null"
      else if schemaJson.contains("\"type\":\"array\"") then "array"
      else "object"
    if includeKey then sb.append(s"${indent}schema:\n")
    sb.append(s"${indent}  type: $schema\n")
    if schema == "array" then
      sb.append(s"${indent}  items:\n")
      sb.append(s"${indent}    type: object\n")

  private def appendYamlResponses(sb: StringBuilder, responseType: Option[String]): Unit =
    sb.append("      responses:\n")
    sb.append("        \"200\":\n")
    sb.append("          description: OK\n")
    responseType.filter(t => t.nonEmpty && t != "Response" && t != "Any").foreach { t =>
      sb.append("          content:\n")
      sb.append("            application/json:\n")
      sb.append("              schema:\n")
      appendYamlSchema(sb, "                ", jsonSchema(t), includeKey = false)
    }

  private def escapeHtmlAttr(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '"'  => "&quot;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case c    => c.toString
    }
