package scalascript.backend.spi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.OpenApiGenerator.{OpenApiMetadata, OpenApiParam, OpenApiRoute, ParamLocation}

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

  test("swaggerUiHtml points at the OpenAPI endpoint"):
    val html = OpenApiGenerator.swaggerUiHtml()
    html should include ("swagger-ui")
    html should include ("/_openapi.json")
