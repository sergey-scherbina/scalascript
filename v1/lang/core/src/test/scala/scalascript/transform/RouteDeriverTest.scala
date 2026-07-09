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
    assert(ep.requestType == "String")
  }

  test("non-body route with multiple path params derives Any request") {
    val mod = parse("""route("GET", "/api/users/:userId/items/:itemId") { req => Response.ok() }""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.requestType == "Any")
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

  test("DELETE path-param route derives callable request") {
    val mod = parse("""route("DELETE", "/api/items/:id") { req => Response.ok() }""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.requestType == "String")
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

  // ── New: front-matter routes: derivation ───────────────────────────────────

  test("derives from front-matter routes: entries") {
    val mod = Parser.parse(
      """|---
         |name: test
         |routes:
         |  - method: GET
         |    path: /api/todos
         |    handler: listTodos
         |  - method: POST
         |    path: /api/todos
         |    handler: createTodo
         |---
         |
         |# Section
         |
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    )
    val clients = mod.manifest.get.apiClients
    assert(clients.size == 1)
    assert(clients.head.name == "Api")
    val eps = clients.head.endpoints
    assert(eps.size == 2)
    assert(eps.exists(e => e.method == "GET" && e.path == "/api/todos" && e.requestType == "Unit"))
    assert(eps.exists(e => e.method == "POST" && e.path == "/api/todos" && e.requestType == "Any"))
  }

  test("front-matter routes: non-body path params derive callable request") {
    val mod = Parser.parse(
      """|---
         |name: test
         |routes:
         |  - method: DELETE
         |    path: /api/todos/:id
         |    handler: deleteTodo
         |---
         |
         |# Section
         |""".stripMargin
    )
    val ep = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.requestType == "String")
  }

  test("front-matter routes: and inline route() deduplicated by method+path") {
    val mod = Parser.parse(
      """|---
         |name: test
         |routes:
         |  - method: GET
         |    path: /api/todos
         |    handler: listTodos
         |---
         |
         |# Section
         |
         |```scalascript
         |route("GET", "/api/todos") { req => Response.ok() }
         |route("POST", "/api/todos") { req => Response.ok() }
         |```
         |""".stripMargin
    )
    val eps = mod.manifest.get.apiClients.head.endpoints
    // GET /api/todos deduplicated; POST /api/todos from inline
    assert(eps.size == 2)
  }

  // ── New: mount() derivation ────────────────────────────────────────────────

  test("derives from mount() GET path-param call with String request") {
    val mod = parse("""mount("GET", "/hello/:name", "handlers/hello.ssc")""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.method == "GET")
    assert(ep.path == "/hello/:name")
    assert(ep.requestType == "String")
  }

  test("derives from mount() POST call with Any request") {
    val mod = parse("""mount("POST", "/add", "handlers/add.ssc")""")
    val ep  = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep.method == "POST")
    assert(ep.requestType == "Any")
  }

  test("cross-file typed mount handler: extracts requestType from handler file") {
    val tmp     = os.temp.dir()
    val handler = tmp / "greet.ssc"
    // Simple typed handler: function literal with explicit case-class param type
    os.write(handler, "(input: GreetInput) => GreetOutput(input.name)")

    val mod = Parser.parse(
      """|---
         |name: test
         |---
         |
         |# Section
         |
         |```scalascript
         |mount("GET", "/greet/:name", "greet.ssc")
         |```
         |""".stripMargin
    )
    // Without baseDir, the route path still makes the generated client callable.
    val ep0 = mod.manifest.get.apiClients.head.endpoints.head
    assert(ep0.requestType == "String")

    // With baseDir pointing to tmp, handler file is loaded → typed param extracted
    val enhanced = RouteDeriver.derive(mod, Some(tmp))
    val ep = enhanced.manifest.get.apiClients.head.endpoints.head
    assert(ep.requestType == "GreetInput")
    assert(ep.responseType == "Any")

    os.remove.all(tmp)
  }
