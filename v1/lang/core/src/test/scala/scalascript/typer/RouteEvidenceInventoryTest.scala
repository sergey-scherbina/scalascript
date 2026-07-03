package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir
import scalascript.ir.{ApiClientDecl, ApiEndpointDecl, ApiEndpointTypeEvidenceWire,
  RemoteHandlerDecl, TypeEvidenceWire, Manifest}

class RouteEvidenceInventoryTest extends AnyFunSuite:

  private val declared = TypeEvidenceWire("UserId", "Declared", Some("test"))
  private val unknown  = TypeEvidenceWire("Any",    "Unknown",  Some("test"))

  private def endpoint(req: ir.TypeEvidenceWire, resp: ir.TypeEvidenceWire): ApiEndpointDecl =
    ApiEndpointDecl(
      name         = "test",
      method       = "GET",
      path         = "/test",
      requestType  = req.tpe,
      responseType = resp.tpe,
      typeEvidence = Some(ApiEndpointTypeEvidenceWire(
        request  = Some(req),
        response = Some(resp)
      ))
    )

  private def handler(req: ir.TypeEvidenceWire, resp: ir.TypeEvidenceWire): RemoteHandlerDecl =
    RemoteHandlerDecl(
      name         = "test",
      function     = "testFn",
      requestType  = Some(req.tpe),
      responseType = Some(resp.tpe),
      typeEvidence = Some(ApiEndpointTypeEvidenceWire(
        request  = Some(req),
        response = Some(resp)
      ))
    )

  private def manifestWith(
      endpoints: List[ApiEndpointDecl],
      handlers:  List[RemoteHandlerDecl]
  ): ir.Manifest =
    ir.Manifest(
      name         = Some("test"),
      version      = None,
      description  = None,
      dependencies = Map.empty,
      exports      = Nil,
      targets      = Nil,
      routes       = Nil,
      pkg          = None,
      apiClients     = if endpoints.isEmpty then Nil
                       else List(ApiClientDecl("TestClient", endpoints)),
      remoteHandlers = handlers
    )

  test("empty manifest → all zeros, allDeclared=true"):
    val counts = RouteEvidenceInventory.count(manifestWith(Nil, Nil))
    assert(counts == RouteEvidenceCounts())
    assert(counts.allDeclared)

  test("all declared endpoints and handlers → allDeclared=true"):
    val m = manifestWith(
      List(endpoint(declared, declared), endpoint(declared, declared)),
      List(handler(declared, declared))
    )
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.endpointsDeclared == 2)
    assert(counts.endpointsUnknown  == 0)
    assert(counts.handlersDeclared  == 1)
    assert(counts.handlersUnknown   == 0)
    assert(counts.allDeclared)

  test("one unknown request evidence → endpoint counted as unknown"):
    val m = manifestWith(
      List(endpoint(unknown, declared)),
      Nil
    )
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.endpointsDeclared == 0)
    assert(counts.endpointsUnknown  == 1)
    assert(!counts.allDeclared)

  test("one unknown response evidence → endpoint counted as unknown"):
    val m = manifestWith(
      List(endpoint(declared, unknown)),
      Nil
    )
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.endpointsUnknown  == 1)
    assert(!counts.allDeclared)

  test("handler with unknown evidence → counted as unknown"):
    val m = manifestWith(
      Nil,
      List(handler(declared, unknown))
    )
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.handlersDeclared == 0)
    assert(counts.handlersUnknown  == 1)
    assert(!counts.allDeclared)

  test("missing typeEvidence (legacy artifact) → unknown"):
    val legacyEndpoint = ApiEndpointDecl("e", "GET", "/e", "Any", "Any")
    val legacyHandler  = RemoteHandlerDecl("h", "hFn")
    val m = manifestWith(List(legacyEndpoint), List(legacyHandler))
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.endpointsUnknown == 1)
    assert(counts.handlersUnknown  == 1)
    assert(!counts.allDeclared)

  test("mixed declared and unknown → correct totals"):
    val m = manifestWith(
      List(
        endpoint(declared, declared),  // declared
        endpoint(unknown, declared),   // unknown (request unknown)
        endpoint(declared, unknown)    // unknown (response unknown)
      ),
      List(
        handler(declared, declared),   // declared
        handler(unknown, unknown)      // unknown
      )
    )
    val counts = RouteEvidenceInventory.count(m)
    assert(counts.endpointsDeclared == 1)
    assert(counts.endpointsUnknown  == 2)
    assert(counts.handlersDeclared  == 1)
    assert(counts.handlersUnknown   == 1)
    assert(counts.totalEndpoints    == 3)
    assert(counts.totalHandlers     == 2)
    assert(!counts.allDeclared)
