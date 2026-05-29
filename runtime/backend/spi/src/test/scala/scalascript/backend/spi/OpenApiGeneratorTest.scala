package scalascript.backend.spi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.OpenApiGenerator.{OpenApiMetadata, OpenApiParam, OpenApiRoute, OpenApiSecurityScheme, ParamLocation, SchemaNode}

class OpenApiGeneratorTest extends AnyFunSuite with Matchers:

  test("generate groups methods, path params, query params, and body params"):
    val doc = OpenApiGenerator.generate(List(
      OpenApiRoute("GET", "/users/:id", List(OpenApiParam("verbose", "Boolean", ParamLocation.Query))),
      OpenApiRoute("POST", "/users", List(OpenApiParam("name", "String", ParamLocation.Body)))
    ))

    doc should include (""""openapi": "3.1.0"""")
    doc should include (""""/users/{id}"""")
    doc should include (""""/users"""")
    doc should include (""""name": "id"""")
    doc should include (""""name": "verbose"""")
    doc should include (""""type":"boolean"""")
    doc should include (""""requestBody"""")
    doc should include (""""name": {"type":"string"}""")

  test("generate excludes internal /_ routes"):
    val doc = OpenApiGenerator.generate(List(
      OpenApiRoute("GET", "/_health"),
      OpenApiRoute("GET", "/public")
    ))
    doc should include (""""/public"""")
    doc should not include (""""/_health"""")

  test("responseType emits JSON content schema when known"):
    val doc = OpenApiGenerator.generate(List(
      OpenApiRoute("GET", "/count", responseType = Some("Int"))
    ))
    doc should include (""""content": { "application/json": { "schema": {"type":"integer"} } }""")

  test("route metadata emits summary description tags and deprecated"):
    val doc = OpenApiGenerator.generate(List(
      OpenApiRoute(
        "GET",
        "/users/:id",
        metadata = OpenApiMetadata(
          summary = Some("Get user"),
          description = Some("Returns a user by id."),
          tags = List("users", "admin"),
          deprecated = true
        )
      )
    ))
    doc should include (""""summary": "Get user"""")
    doc should include (""""description": "Returns a user by id."""")
    doc should include (""""tags": ["users", "admin"]""")
    doc should include (""""deprecated": true""")

  test("security schemes and per-route requirements are emitted"):
    val doc = OpenApiGenerator.generate(
      List(OpenApiRoute("DELETE", "/users/:id", metadata = OpenApiMetadata(security = List("bearerAuth")))),
      List(OpenApiSecurityScheme("bearerAuth", "bearer", "JWT"))
    )
    doc should include (""""security": [{ "bearerAuth": [] }]""")
    doc should include (""""securitySchemes"""")
    doc should include (""""bearerAuth": { "type": "http", "scheme": "bearer", "bearerFormat": "JWT" }""")

  test("apiKey security scheme emits header name from format"):
    val doc = OpenApiGenerator.generate(
      List(OpenApiRoute("GET", "/admin", metadata = OpenApiMetadata(security = List("apiKeyAuth")))),
      List(OpenApiSecurityScheme("apiKeyAuth", "apiKey", "X-API-Key"))
    )
    doc should include (""""apiKeyAuth": { "type": "apiKey", "in": "header", "name": "X-API-Key" }""")

  test("swaggerUiHtml points at the OpenAPI endpoint"):
    val html = OpenApiGenerator.swaggerUiHtml()
    html should include ("swagger-ui")
    html should include ("/_openapi.json")

  // ── Phase 6: SchemaNode rendering ─────────────────────────────────────────

  test("schemaNodeToJson renders primitive nodes"):
    OpenApiGenerator.schemaNodeToJson(SchemaNode.StrNode)  shouldBe "{\"type\":\"string\"}"
    OpenApiGenerator.schemaNodeToJson(SchemaNode.IntNode)  shouldBe "{\"type\":\"integer\"}"
    OpenApiGenerator.schemaNodeToJson(SchemaNode.NumNode)  shouldBe "{\"type\":\"number\"}"
    OpenApiGenerator.schemaNodeToJson(SchemaNode.BoolNode) shouldBe "{\"type\":\"boolean\"}"
    OpenApiGenerator.schemaNodeToJson(SchemaNode.NullNode) shouldBe "{\"type\":\"null\"}"

  test("schemaNodeToJson renders RefNode as $ref"):
    OpenApiGenerator.schemaNodeToJson(SchemaNode.RefNode("User")) shouldBe
      "{\"$ref\":\"#/components/schemas/User\"}"

  test("schemaNodeToJson renders NullableNode as oneOf with null"):
    OpenApiGenerator.schemaNodeToJson(SchemaNode.NullableNode(SchemaNode.StrNode)) shouldBe
      "{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}"

  test("schemaNodeToJson renders NullableNode of RefNode as oneOf with null"):
    OpenApiGenerator.schemaNodeToJson(SchemaNode.NullableNode(SchemaNode.RefNode("Tag"))) shouldBe
      "{\"oneOf\":[{\"$ref\":\"#/components/schemas/Tag\"},{\"type\":\"null\"}]}"

  test("schemaNodeToJson renders ArrNode"):
    OpenApiGenerator.schemaNodeToJson(SchemaNode.ArrNode(SchemaNode.StrNode)) shouldBe
      "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"

  test("schemaNodeToJson renders ObjNode with properties and required"):
    val node = SchemaNode.ObjNode(
      props    = Map("id" -> SchemaNode.IntNode, "name" -> SchemaNode.StrNode),
      required = List("id", "name")
    )
    val json = OpenApiGenerator.schemaNodeToJson(node)
    json should include ("\"type\":\"object\"")
    json should include ("\"id\":{\"type\":\"integer\"}")
    json should include ("\"name\":{\"type\":\"string\"}")
    json should include ("\"required\":[\"id\",\"name\"]")

  test("schemaNodeToJson renders OneOfNode"):
    val node = SchemaNode.OneOfNode(List(SchemaNode.StrNode, SchemaNode.IntNode))
    OpenApiGenerator.schemaNodeToJson(node) shouldBe
      "{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}"

  test("SchemaNode.fromTypeName derives nodes from type-name strings"):
    SchemaNode.fromTypeName("String")         shouldBe SchemaNode.StrNode
    SchemaNode.fromTypeName("Int")            shouldBe SchemaNode.IntNode
    SchemaNode.fromTypeName("Boolean")        shouldBe SchemaNode.BoolNode
    SchemaNode.fromTypeName("Option[String]") shouldBe SchemaNode.NullableNode(SchemaNode.StrNode)
    SchemaNode.fromTypeName("List[Int]")      shouldBe SchemaNode.ArrNode(SchemaNode.IntNode)
    SchemaNode.fromTypeName("User")           shouldBe SchemaNode.RefNode("User")
    SchemaNode.fromTypeName("")               shouldBe SchemaNode.ObjNode()

  // ── Phase 6: components.schemas emission ──────────────────────────────────

  test("generate emits components.schemas when schemaComponents provided"):
    val schemas = Map(
      "User" -> SchemaNode.ObjNode(
        props    = Map("id" -> SchemaNode.IntNode, "name" -> SchemaNode.StrNode),
        required = List("id")
      )
    )
    val doc = OpenApiGenerator.generate(
      List(OpenApiRoute("GET", "/users")),
      schemaComponents = schemas
    )
    doc should include ("\"components\"")
    doc should include ("\"schemas\"")
    doc should include ("\"User\"")
    doc should include ("\"id\":{\"type\":\"integer\"}")
    doc should include ("\"name\":{\"type\":\"string\"}")

  test("jsonSchema uses $ref when type name is in schemaComponents"):
    val schemas = Map("Order" -> SchemaNode.ObjNode())
    val schema  = OpenApiGenerator.jsonSchema("Order", schemas)
    schema shouldBe "{\"$ref\":\"#/components/schemas/Order\"}"

  test("responseSchema takes precedence over responseType"):
    val doc = OpenApiGenerator.generate(List(
      OpenApiRoute(
        "GET", "/user",
        responseType   = Some("String"),
        responseSchema = Some(SchemaNode.RefNode("User"))
      )
    ))
    doc should include ("\"$ref\":\"#/components/schemas/User\"")
    doc should not include ("\"type\":\"string\"")

  test("responseSchema with RefNode emits $ref in responses content"):
    val schemas = Map("Product" -> SchemaNode.ObjNode(props = Map("sku" -> SchemaNode.StrNode)))
    val doc = OpenApiGenerator.generate(
      List(OpenApiRoute("GET", "/products/:id",
        responseSchema = Some(SchemaNode.RefNode("Product")))),
      schemaComponents = schemas
    )
    doc should include ("\"$ref\":\"#/components/schemas/Product\"")
    doc should include ("\"Product\"")

  test("generate YAML emits components.schemas section"):
    val schemas = Map("Tag" -> SchemaNode.ObjNode(props = Map("label" -> SchemaNode.StrNode)))
    val yaml = OpenApiGenerator.generateYaml(
      List(OpenApiRoute("GET", "/tags")),
      schemaComponents = schemas
    )
    yaml should include ("components:")
    yaml should include ("schemas:")
    yaml should include ("Tag:")
