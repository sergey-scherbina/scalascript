package scalascript.backend.spi

/** Shared OpenAPI 3.1 generator used by interpreter and generated JVM servers.
 *
 *  Route registries stay backend-local; they adapt their entries into
 *  [[OpenApiRoute]] so the document shape, escaping, parameter inference, and
 *  Swagger UI stay identical across runtimes.
 *
 *  Phase 6 adds [[SchemaNode]] — a structured schema representation —
 *  and `components.schemas` emission.  Existing code that uses [[OpenApiRoute]]
 *  with a plain `responseType: Option[String]` continues to work unchanged;
 *  the new `responseSchema: Option[SchemaNode]` field takes precedence when set.
 */
object OpenApiGenerator:

  // ── Phase 6: structured schema model ────────────────────────────────────────

  /** Structured representation of an OpenAPI 3.1 JSON Schema node.
   *
   *  Render with [[schemaNodeToJson]] / [[schemaNodeToYaml]].
   *  Use [[SchemaNode.fromTypeName]] to derive a node from a ScalaScript type-name string.
   */
  sealed trait SchemaNode
  object SchemaNode:
    case object StrNode                                                              extends SchemaNode
    case object IntNode                                                              extends SchemaNode
    case object NumNode                                                              extends SchemaNode
    case object BoolNode                                                             extends SchemaNode
    case object NullNode                                                             extends SchemaNode
    case class  ArrNode(items: SchemaNode = ObjNode())                              extends SchemaNode
    case class  ObjNode(
        props:    Map[String, SchemaNode] = Map.empty,
        required: List[String]            = Nil
    )                                                                                extends SchemaNode
    case class  RefNode(name: String)                                                extends SchemaNode
    case class  NullableNode(inner: SchemaNode)                                      extends SchemaNode
    case class  OneOfNode(options: List[SchemaNode])                                 extends SchemaNode
    /** A string enum (`{"type":"string","enum":[...]}`), e.g. a ScalaScript enum
     *  whose cases are all parameterless. */
    case class  EnumNode(values: List[String])                                       extends SchemaNode

    /** Derive a [[SchemaNode]] from a ScalaScript / Scala type-name string.
     *
     *  - Primitives map to the matching leaf node.
     *  - `Option[T]` wraps the inner node in [[NullableNode]].
     *  - `List[T]` / `Seq[T]` / `Array[T]` become [[ArrNode]].
     *  - Any other name is treated as a named component type: [[RefNode]].
     *    The caller is responsible for registering the corresponding schema
     *    in the `schemaComponents` map passed to [[generate]].
     */
    def fromTypeName(typeName: String): SchemaNode = typeName.trim match
      case "String"                                          => StrNode
      case "Int" | "Long" | "Short" | "Byte"                => IntNode
      case "Double" | "Float" | "BigDecimal"                 => NumNode
      case "Boolean"                                         => BoolNode
      case "Unit"                                            => NullNode
      case t if t.startsWith("Option[") && t.endsWith("]")  =>
        NullableNode(fromTypeName(t.substring(7, t.length - 1)))
      case t if (t.startsWith("List[") || t.startsWith("Seq[") || t.startsWith("Array["))
               && t.endsWith("]")                            =>
        val inner = t.indexOf('[')
        ArrNode(fromTypeName(t.substring(inner + 1, t.length - 1)))
      case ""                                                => ObjNode()
      case name                                              => RefNode(name)

  // ── Route model ─────────────────────────────────────────────────────────────

  final case class OpenApiRoute(
      method:         String,
      path:           String,
      params:         List[OpenApiParam]          = Nil,
      responseType:   Option[String]              = None,
      responseSchema: Option[SchemaNode]          = None,
      metadata:       OpenApiMetadata             = OpenApiMetadata()
  )

  final case class OpenApiParam(
      name:     String,
      typeName: String,
      location: ParamLocation,
      schema:   Option[SchemaNode] = None
  )

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

  // ── JSON generation ──────────────────────────────────────────────────────────

  def generate(
      routes:           Iterable[OpenApiRoute],
      securitySchemes:  Iterable[OpenApiSecurityScheme] = Nil,
      options:          OpenApiOptions                   = OpenApiOptions(),
      schemaComponents: Map[String, SchemaNode]          = Map.empty
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
            queryParams.map(p => paramEntry(
              p.name, "query", required = false,
              schemaType = p.schema.map(schemaNodeToJson).getOrElse(jsonSchema(p.typeName, schemaComponents))
            ))
          if allParams.nonEmpty then
            sb.append("        \"parameters\": [\n")
            sb.append(allParams.mkString(",\n"))
            sb.append("\n        ],\n")

          if bodyParams.nonEmpty then
            val props = bodyParams.map { p =>
              val s = p.schema.map(schemaNodeToJson).getOrElse(jsonSchema(p.typeName, schemaComponents))
              s"          ${jsonStr(p.name)}: $s"
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
          // responseSchema (Phase 6) takes precedence over responseType (Phase 2 legacy)
          val respJson = route.responseSchema
            .map(node => responseSchemaFromNode(node))
            .orElse(route.responseType.map(t => responseSchemaFromTypeName(t, schemaComponents)))
            .getOrElse("""{ "200": { "description": "OK" } }""")
          sb.append(respJson)
          sb.append("\n")
          sb.append("      }")

        sb.append("\n    }")

      sb.append("\n  }")

    val schemes = securitySchemes.toList.filter(_.name.nonEmpty)
    val hasComponents = schemes.nonEmpty || schemaComponents.nonEmpty
    if hasComponents then
      sb.append(",\n")
      sb.append("  \"components\": {")
      var firstSection = true
      if schemaComponents.nonEmpty then
        firstSection = false
        sb.append("\n    \"schemas\": {\n")
        var first = true
        schemaComponents.toList.sortBy(_._1).foreach { (name, node) =>
          if !first then sb.append(",\n")
          first = false
          sb.append(s"      ${jsonStr(name)}: ${schemaNodeToJson(node)}")
        }
        sb.append("\n    }")
      if schemes.nonEmpty then
        if !firstSection then sb.append(",\n") else sb.append("\n")
        sb.append("    \"securitySchemes\": {\n")
        sb.append(schemes.map(securitySchemeEntry).mkString(",\n"))
        sb.append("\n    }")
      sb.append("\n  }")

    sb.append("\n}\n")
    sb.toString

  // ── YAML generation ──────────────────────────────────────────────────────────

  def generateYaml(
      routes:           Iterable[OpenApiRoute],
      securitySchemes:  Iterable[OpenApiSecurityScheme] = Nil,
      options:          OpenApiOptions                   = OpenApiOptions(),
      schemaComponents: Map[String, SchemaNode]          = Map.empty
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
            queryParams.map(p => (
              p.name, "query", false,
              p.schema.map(schemaNodeToJson).getOrElse(jsonSchema(p.typeName, schemaComponents))
            ))
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
              val s = p.schema.map(schemaNodeToJson).getOrElse(jsonSchema(p.typeName, schemaComponents))
              sb.append(s"                ${yamlStr(p.name)}:\n")
              appendYamlSchema(sb, "                  ", s, includeKey = false)
            }
          // responseSchema takes precedence
          route.responseSchema match
            case Some(node) => appendYamlResponseFromNode(sb, node)
            case None       => appendYamlResponses(sb, route.responseType, schemaComponents)

    val hasComponents = schemaComponents.nonEmpty || schemes.nonEmpty
    if hasComponents then
      sb.append("components:\n")
      if schemaComponents.nonEmpty then
        sb.append("  schemas:\n")
        schemaComponents.toList.sortBy(_._1).foreach { (name, node) =>
          sb.append(s"    ${yamlStr(name)}:\n")
          appendYamlSchemaNode(sb, "      ", node)
        }
      if schemes.nonEmpty then
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

  // ── Schema rendering helpers ─────────────────────────────────────────────────

  def schemaNodeToJson(node: SchemaNode): String = node match
    case SchemaNode.StrNode             => "{\"type\":\"string\"}"
    case SchemaNode.IntNode             => "{\"type\":\"integer\"}"
    case SchemaNode.NumNode             => "{\"type\":\"number\"}"
    case SchemaNode.BoolNode            => "{\"type\":\"boolean\"}"
    case SchemaNode.NullNode            => "{\"type\":\"null\"}"
    case SchemaNode.RefNode(name)       =>
      "{\"$ref\":\"#/components/schemas/" + jsonEscape(name) + "\"}"
    case SchemaNode.NullableNode(inner) =>
      // OAS 3.1: use oneOf with null for all nullable types (works for both $ref and inline)
      "{\"oneOf\":[" + schemaNodeToJson(inner) + ",{\"type\":\"null\"}]}"
    case SchemaNode.ArrNode(items)      =>
      "{\"type\":\"array\",\"items\":" + schemaNodeToJson(items) + "}"
    case SchemaNode.ObjNode(props, required) =>
      val sb = new StringBuilder("{\"type\":\"object\"")
      if props.nonEmpty then
        sb.append(",\"properties\":{")
        var first = true
        props.toList.sortBy(_._1).foreach { (k, v) =>
          if !first then sb.append(",")
          first = false
          sb.append(jsonStr(k)).append(":").append(schemaNodeToJson(v))
        }
        sb.append("}")
      if required.nonEmpty then
        sb.append(",\"required\":[")
        sb.append(required.map(jsonStr).mkString(","))
        sb.append("]")
      sb.append("}").toString
    case SchemaNode.OneOfNode(options)  =>
      "{\"oneOf\":[" + options.map(schemaNodeToJson).mkString(",") + "]}"
    case SchemaNode.EnumNode(values)    =>
      "{\"type\":\"string\",\"enum\":[" + values.map(jsonStr).mkString(",") + "]}"

  // ── Utility ──────────────────────────────────────────────────────────────────

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

  /** Legacy type-name → inline JSON schema.
   *
   *  If `typeName` is a known primitive, returns an inline schema.
   *  If `typeName` is in `schemaComponents`, returns a `$ref`.
   *  Otherwise returns `{"type":"object"}` (backward-compat fallback).
   */
  def jsonSchema(typeName: String, schemaComponents: Map[String, SchemaNode] = Map.empty): String =
    typeName match
      case "String"                                          => "{\"type\":\"string\"}"
      case "Int" | "Long" | "Short" | "Byte"                => "{\"type\":\"integer\"}"
      case "Double" | "Float" | "BigDecimal"                 => "{\"type\":\"number\"}"
      case "Boolean"                                         => "{\"type\":\"boolean\"}"
      case "Unit"                                            => "{\"type\":\"null\"}"
      case t if t.startsWith("List[") || t.startsWith("Seq[") || t.startsWith("Array[") =>
        "{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"
      case name if schemaComponents.contains(name)           =>
        schemaNodeToJson(SchemaNode.RefNode(name))
      case _                                                 => "{\"type\":\"object\"}"

  // ── Private helpers ───────────────────────────────────────────────────────────

  private def responseSchemaFromNode(node: SchemaNode): String =
    s"""{ "200": { "description": "OK", "content": { "application/json": { "schema": ${schemaNodeToJson(node)} } } } }"""

  private def responseSchemaFromTypeName(
      responseType:     String,
      schemaComponents: Map[String, SchemaNode]
  ): String =
    if responseType.nonEmpty && responseType != "Response" && responseType != "Any" then
      s"""{ "200": { "description": "OK", "content": { "application/json": { "schema": ${jsonSchema(responseType, schemaComponents)} } } } }"""
    else
      """{ "200": { "description": "OK" } }"""

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

  def jsonStr(s: String): String =
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

  private def jsonEscape(s: String): String =
    // Reuse the full escaper (adds \n\r\t control-char escaping) minus the outer
    // quotes jsonStr appends — the previous "/\ -only escaper produced invalid
    // JSON for a schema/$ref name containing a newline.
    val q = jsonStr(s)
    q.substring(1, q.length - 1)

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
    if schemaJson.contains("\"$ref\"") then
      // $ref schema: emit as YAML ref
      val refName = schemaJson.replaceAll(""".*"#/components/schemas/([^"]+)".*""", "$1")
      if includeKey then sb.append(s"${indent}schema:\n")
      sb.append(s"${indent}  $$ref: '#/components/schemas/${refName}'\n")
    else
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

  private def appendYamlSchemaNode(sb: StringBuilder, indent: String, node: SchemaNode): Unit =
    node match
      case SchemaNode.StrNode    => sb.append(s"${indent}type: string\n")
      case SchemaNode.IntNode    => sb.append(s"${indent}type: integer\n")
      case SchemaNode.NumNode    => sb.append(s"${indent}type: number\n")
      case SchemaNode.BoolNode   => sb.append(s"${indent}type: boolean\n")
      case SchemaNode.NullNode   => sb.append(s"${indent}type: 'null'\n")
      case SchemaNode.RefNode(n) => sb.append(s"${indent}$$ref: '#/components/schemas/${n}'\n")
      case SchemaNode.ArrNode(items) =>
        sb.append(s"${indent}type: array\n")
        sb.append(s"${indent}items:\n")
        appendYamlSchemaNode(sb, indent + "  ", items)
      case SchemaNode.ObjNode(props, required) =>
        sb.append(s"${indent}type: object\n")
        if props.nonEmpty then
          sb.append(s"${indent}properties:\n")
          props.toList.sortBy(_._1).foreach { (k, v) =>
            sb.append(s"${indent}  ${yamlStr(k)}:\n")
            appendYamlSchemaNode(sb, indent + "    ", v)
          }
        if required.nonEmpty then
          sb.append(s"${indent}required:\n")
          required.foreach(r => sb.append(s"${indent}  - ${yamlStr(r)}\n"))
      case SchemaNode.NullableNode(inner) =>
        sb.append(s"${indent}oneOf:\n")
        sb.append(s"${indent}  - ")
        appendYamlSchemaNode(sb, "", inner)
        sb.append(s"${indent}  - type: 'null'\n")
      case SchemaNode.OneOfNode(options) =>
        sb.append(s"${indent}oneOf:\n")
        options.foreach { opt =>
          sb.append(s"${indent}  - ")
          appendYamlSchemaNode(sb, "", opt)
        }
      case SchemaNode.EnumNode(values) =>
        sb.append(s"${indent}type: string\n")
        sb.append(s"${indent}enum:\n")
        values.foreach(v => sb.append(s"${indent}  - ${yamlStr(v)}\n"))

  private def appendYamlResponseFromNode(sb: StringBuilder, node: SchemaNode): Unit =
    sb.append("      responses:\n")
    sb.append("        \"200\":\n")
    sb.append("          description: OK\n")
    sb.append("          content:\n")
    sb.append("            application/json:\n")
    sb.append("              schema:\n")
    appendYamlSchemaNode(sb, "                ", node)

  private def appendYamlResponses(
      sb:               StringBuilder,
      responseType:     Option[String],
      schemaComponents: Map[String, SchemaNode]
  ): Unit =
    sb.append("      responses:\n")
    sb.append("        \"200\":\n")
    sb.append("          description: OK\n")
    responseType.filter(t => t.nonEmpty && t != "Response" && t != "Any").foreach { t =>
      sb.append("          content:\n")
      sb.append("            application/json:\n")
      sb.append("              schema:\n")
      appendYamlSchema(sb, "                ", jsonSchema(t, schemaComponents), includeKey = false)
    }

  private def escapeHtmlAttr(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '"'  => "&quot;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case c    => c.toString
    }
