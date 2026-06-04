package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class RouteTypeEvidenceTest extends AnyFunSuite:

  private def parse(source: String) =
    Parser.parse(source.stripMargin)

  test("Normalize records declared api client endpoint type evidence"):
    val module = parse(
      """|---
         |apiClients:
         |  Events:
         |    endpoints:
         |      - name: subscribe
         |        method: GET
         |        path: /api/events
         |        request: Unit
         |        response: Event
         |        stream: sse
         |---
         |
         |# Events
         |""")

    val endpoint = Normalize(module).manifest.get.apiClients.head.endpoints.head
    val evidence = endpoint.typeEvidence.get

    assert(evidence.request.map(e => (e.tpe, e.kind)) == Some(("Unit", "Declared")))
    assert(evidence.response.map(e => (e.tpe, e.kind)) == Some(("Event", "Declared")))
    assert(evidence.streamElement.map(e => (e.tpe, e.kind)) == Some(("Event", "Declared")))

  test("Normalize marks derived route Any fallback evidence as unknown"):
    val module = parse(
      """|---
         |name: demo
         |---
         |
         |# API
         |
         |```scalascript
         |route("POST", "/api/users") { req => Response.ok() }
         |```
         |""")

    val endpoint = Normalize(module).manifest.get.apiClients.head.endpoints.head
    val evidence = endpoint.typeEvidence.get

    assert(evidence.request.map(e => (e.tpe, e.kind)) == Some(("Any", "Unknown")))
    assert(evidence.response.map(e => (e.tpe, e.kind)) == Some(("Any", "Unknown")))

  test("Normalize records remote handler request and response type evidence"):
    val module = parse(
      """|---
         |remoteHandlers:
         |  users.get:
         |    function: getUser
         |    path: /api/users/:id
         |    request: UserId
         |    response: User
         |---
         |
         |# Remote
         |
         |```scala
         |case class UserId(value: String)
         |case class User(name: String)
         |def getUser(id: UserId): User = User(id.value)
         |```
         |""")

    val handler = Normalize(module).manifest.get.remoteHandlers.head
    val evidence = handler.typeEvidence.get

    assert(evidence.request.map(e => (e.tpe, e.kind)) == Some(("UserId", "Declared")))
    assert(evidence.response.map(e => (e.tpe, e.kind)) == Some(("User", "Declared")))

  test("Normalize marks remote handler missing type metadata as unknown"):
    val module = parse(
      """|---
         |remoteHandlers:
         |  ping:
         |    function: ping
         |---
         |
         |# Remote
         |
         |```scala
         |def ping(): Unit = ()
         |```
         |""")

    val handler = Normalize(module).manifest.get.remoteHandlers.head
    val evidence = handler.typeEvidence.get

    assert(evidence.request.map(e => (e.tpe, e.kind)) == Some(("Any", "Unknown")))
    assert(evidence.response.map(e => (e.tpe, e.kind)) == Some(("Any", "Unknown")))
