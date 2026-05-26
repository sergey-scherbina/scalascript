package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.transform.{Denormalize, Normalize}

class ApiClientsFrontmatterTest extends AnyFunSuite:

  private def withFrontmatter(yaml: String) =
    Parser.parse(
      s"""|---
          |$yaml
          |---
          |
          |# Test
          |""".stripMargin
    )

  test("apiClients parses map-shaped typed endpoint metadata") {
    val mod = withFrontmatter(
      """apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: post
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message
        |      - name: get
        |        method: GET
        |        path: /api/messages/:id
        |        request: Int
        |        response: Message""".stripMargin
    )

    val clients = mod.manifest.get.apiClients
    assert(clients.map(_.name) == List("Messages"))
    assert(clients.head.endpoints.map(e => (e.name, e.method, e.path, e.requestType, e.responseType)) == List(
      ("create", "POST", "/api/messages", "CreateMessage", "Message"),
      ("get", "GET", "/api/messages/:id", "Int", "Message")
    ))
  }

  test("api-clients parses list-shaped typed endpoint metadata") {
    val mod = withFrontmatter(
      """api-clients:
        |  - name: Messages
        |    endpoints:
        |      - name: delete
        |        method: POST
        |        path: /api/messages/delete
        |        request-type: DeleteMessage
        |        response-type: Unit""".stripMargin
    )

    val endpoint = mod.manifest.get.apiClients.head.endpoints.head
    assert(endpoint.name == "delete")
    assert(endpoint.requestType == "DeleteMessage")
    assert(endpoint.responseType == "Unit")
  }

  test("apiClients parses stream: sse on an endpoint") {
    val mod = withFrontmatter(
      """apiClients:
        |  Events:
        |    endpoints:
        |      - name: subscribe
        |        method: GET
        |        path: /api/events/stream
        |        request: Unit
        |        response: Event
        |        stream: sse""".stripMargin
    )

    val endpoint = mod.manifest.get.apiClients.head.endpoints.head
    assert(endpoint.name == "subscribe")
    assert(endpoint.stream == Some("sse"))
    assert(scalascript.ast.ApiEndpointDecl.isSse(endpoint))
  }

  test("apiClients parses stream: true as SSE") {
    val mod = withFrontmatter(
      """apiClients:
        |  Events:
        |    endpoints:
        |      - name: live
        |        method: GET
        |        path: /api/live
        |        request: Unit
        |        response: Update
        |        stream: "true"""".stripMargin
    )

    val endpoint = mod.manifest.get.apiClients.head.endpoints.head
    assert(scalascript.ast.ApiEndpointDecl.isSse(endpoint))
  }

  test("apiClients without stream field has stream = None") {
    val mod = withFrontmatter(
      """apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: POST
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message""".stripMargin
    )

    val endpoint = mod.manifest.get.apiClients.head.endpoints.head
    assert(endpoint.stream == None)
    assert(!scalascript.ast.ApiEndpointDecl.isSse(endpoint))
  }

  test("apiClients survive Normalize and Denormalize") {
    val mod = withFrontmatter(
      """apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: POST
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message""".stripMargin
    )

    val roundTripped = Denormalize(Normalize(mod))
    val endpoint = roundTripped.manifest.get.apiClients.head.endpoints.head
    assert(endpoint.name == "create")
    assert(endpoint.method == "POST")
    assert(endpoint.path == "/api/messages")
    assert(endpoint.requestType == "CreateMessage")
    assert(endpoint.responseType == "Message")
  }
