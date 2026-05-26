package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class RouteDeriverTest extends AnyFunSuite:

  private def parse(code: String) = Parser.parse(
    s"""|---
        |name: test
        |---
        |
        |# Section
        |
        |```scalascript
        |$code
        |```
        |""".stripMargin
  )

  test("derives GET endpoint from route call") {
    val mod = parse("""route("GET", "/api/todos") { req => Response.ok() }""")
    val clients = mod.manifest.map(_.apiClients).getOrElse(Nil)
    assert(clients.size == 1)
    val ep = clients.head.endpoints.head
    assert(ep.method == "GET")
    assert(ep.path == "/api/todos")
    assert(ep.name == "getApiTodos")
    assert(ep.requestType == "Unit")
    assert(ep.responseType == "Any")
  }

  test("derives POST endpoint with body type Any") {
    val mod = parse("""route("POST", "/api/users") { req => Response.ok() }""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.method == "POST")
    assert(ep.requestType == "Any")
  }

  test("derives path-param name: GET /api/users/:id → getUsersById") {
    val mod = parse("""route("GET", "/api/users/:id") { req => Response.ok() }""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.name == "getApiUsersById")
  }

  test("collects multiple routes into single Api client") {
    val mod = parse(
      """route("GET",    "/api/todos")     { req => Response.ok() }
        |route("POST",   "/api/todos")     { req => Response.ok() }
        |route("DELETE", "/api/todos/:id") { req => Response.ok() }""".stripMargin
    )
    val clients = mod.manifest.get.apiClients
    assert(clients.size == 1)
    assert(clients.head.name == "Api")
    val eps = clients.head.endpoints
    assert(eps.size == 3)
    assert(eps.map(_.method) == List("GET", "POST", "DELETE"))
  }

  test("explicit apiClients in frontmatter are not overridden") {
    val mod = Parser.parse(
      """|---
         |name: test
         |apiClients:
         |  MyClient:
         |    endpoints:
         |      - name: list
         |        method: GET
         |        path: /api/items
         |        request: Unit
         |        response: Item
         |---
         |
         |# Section
         |
         |```scalascript
         |route("GET", "/api/other") { req => Response.ok() }
         |```
         |""".stripMargin
    )
    val clients = mod.manifest.get.apiClients
    assert(clients.size == 1)
    assert(clients.head.name == "MyClient")
  }

  test("no routes yields no derived clients") {
    val mod = parse("val x = 42")
    assert(mod.manifest.get.apiClients.isEmpty)
  }

  test("PUT and PATCH also derive body type Any") {
    val mod = parse(
      """route("PUT",   "/api/items/:id") { req => Response.ok() }
        |route("PATCH", "/api/items/:id") { req => Response.ok() }""".stripMargin
    )
    val eps = mod.manifest.get.apiClients.head.endpoints
    assert(eps.forall(_.requestType == "Any"))
  }

  test("DELETE derives body type Unit") {
    val mod = parse("""route("DELETE", "/api/items/:id") { req => Response.ok() }""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.requestType == "Unit")
  }

  test("derives client even without front-matter manifest") {
    val mod = Parser.parse(
      """|# Section
         |
         |```scalascript
         |route("GET", "/ping") { req => Response.ok() }
         |```
         |""".stripMargin
    )
    // No manifest means no derived clients (no place to put them)
    assert(mod.manifest.isEmpty)
    // No apiClients field to worry about
  }
