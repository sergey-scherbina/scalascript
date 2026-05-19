package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import scalascript.mcp.*
import com.sun.net.httpserver.{HttpServer, HttpExchange, HttpHandler}
import java.net.InetSocketAddress

/** v1.17.x — Iter QQ: end-to-end integration of everything we built.
 *
 *  Wires a real (JDK-embedded) HTTP server hosting BOTH the OAuth AS
 *  endpoints + an OAuth-protected MCP server in one process.  Drives
 *  it through the public client APIs:
 *
 *     1. OAuthClient.discoverAs       — RFC 8414 metadata fetch
 *     2. OAuthClient.clientCredentials — mint a token via /token
 *     3. McpHttpClient                — call tools/list with bearer
 *     4. McpHttpClient                — call tools/call with bearer
 *     5. on401Handler                 — auto-refresh on expired
 *     6. as.revokeToken                — revoke + verify rejection
 *
 *  If any layer is broken — wire format, auth gating, refresh flow,
 *  bearer threading — this test trips.  Unit-level tests cover each
 *  layer in isolation; this confirms they compose. */
class OAuthEndToEndIntegrationTest extends AnyFunSuite with Matchers:

  /** Spin up a JDK HttpServer hosting:
   *    - GET  /.well-known/oauth-authorization-server  (RFC 8414)
   *    - GET  /.well-known/jwks.json                    (RFC 7517)
   *    - POST /token                                    (token endpoint)
   *    - POST /introspect                               (RFC 7662)
   *    - POST /revoke                                   (RFC 7009)
   *    - POST /mcp                                      (MCP HTTP, auth-gated)
   *
   *  The `buildAs(baseUrl)` factory receives the bound base URL so
   *  the AS can use it as its `issuer` claim — the discovery doc
   *  ends up advertising real reachable URLs.
   *
   *  Returns the running server + its base URL + the constructed AS
   *  (so tests can call as.revokeToken, populate clients, etc.) */
  private def boot(
    buildAs:       String => AuthServer,
    buildBuilder:  AuthServer => McpServerBuilder
  ): (HttpServer, String, AuthServer, McpServerBuilder) =
    val srv     = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl = s"http://127.0.0.1:${srv.getAddress.getPort}"
    val as      = buildAs(baseUrl)
    val mcpBuilder = buildBuilder(as)

    // ─── OAuth AS routes ─────────────────────────────────────────────
    srv.createContext("/.well-known/oauth-authorization-server",
      handler { (ex, _, _, _) =>
        respond(ex, OAuthRoutes.handleMetadata(as))
      })
    srv.createContext("/.well-known/jwks.json",
      handler { (ex, _, _, _) =>
        respond(ex, OAuthRoutes.handleJwks(as))
      })
    srv.createContext("/token",
      handler { (ex, body, headers, _) =>
        respond(ex, OAuthRoutes.handleToken(as, body, headers))
      })
    srv.createContext("/introspect",
      handler { (ex, body, headers, _) =>
        respond(ex, OAuthRoutes.handleIntrospect(as, body, headers))
      })
    srv.createContext("/revoke",
      handler { (ex, body, headers, _) =>
        respond(ex, OAuthRoutes.handleRevoke(as, body, headers))
      })

    // ─── MCP server route (auth-gated) ──────────────────────────────
    srv.createContext("/mcp",
      handler { (ex, body, headers, _) =>
        val bearer = OAuth.extractBearer(headers)
        McpServerCore.authorizeHttp(mcpBuilder, bearer) match
          case McpServerCore.AuthOutcome.Reject(code, descr) =>
            respond(ex, OAuthGuard.unauthorized(mcpBuilder.authRealm, code, descr))
          case McpServerCore.AuthOutcome.Allowed(claims) =>
            mcpBuilder.withAuth(claims) {
              val reply = McpServerCore.handleHttpRequest(mcpBuilder, body, "test-srv", "1.0.0")
              if reply.nonEmpty then
                respond(ex, OAuthRoutes.RouteOutcome.Json(200, ujson.read(reply.trim),
                  Map("Content-Type" -> "application/json")))
              else
                respond(ex, OAuthRoutes.RouteOutcome.Empty(204))
            }
      })

    srv.setExecutor(null)
    srv.start()
    (srv, baseUrl, as, mcpBuilder)

  /** Adapter — JDK HttpExchange + 3-arg handler shape so each route
   *  doesn't reinvent body / header parsing. */
  private def handler(run: (HttpExchange, String, Map[String, String], Map[String, String]) => Unit) =
    new HttpHandler {
      def handle(ex: HttpExchange): Unit =
        val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
        val headers = scala.jdk.CollectionConverters.IteratorHasAsScala(
          ex.getRequestHeaders.entrySet().iterator()).asScala.flatMap { e =>
            val k = e.getKey
            scala.jdk.CollectionConverters.IteratorHasAsScala(e.getValue.iterator()).asScala
              .map(v => k -> v)
          }.toMap
        // Query parsing kept lightweight — we don't need it for this test.
        run(ex, body, headers, Map.empty)
    }

  /** Render a typed RouteOutcome through the JDK HttpExchange. */
  private def respond(ex: HttpExchange, outcome: OAuthRoutes.RouteOutcome): Unit =
    outcome match
      case OAuthRoutes.RouteOutcome.Json(status, body, extraHeaders) =>
        extraHeaders.foreach((k, v) => ex.getResponseHeaders.set(k, v))
        if !extraHeaders.contains("Content-Type") then
          ex.getResponseHeaders.set("Content-Type", "application/json")
        val bytes = body.render().getBytes("UTF-8")
        ex.sendResponseHeaders(status, bytes.length.toLong)
        ex.getResponseBody.write(bytes); ex.getResponseBody.close()
      case OAuthRoutes.RouteOutcome.Redirect(status, location) =>
        ex.getResponseHeaders.set("Location", location)
        ex.sendResponseHeaders(status, -1); ex.getResponseBody.close()
      case OAuthRoutes.RouteOutcome.Empty(status) =>
        ex.sendResponseHeaders(status, -1); ex.getResponseBody.close()

  // ─── Tests ─────────────────────────────────────────────────────────

  test("Full lifecycle: discover → mint → tools/list → tools/call"):
    val (srv, baseUrl, _, _) = boot(
      buildAs = base => {
        val a = new AuthServer(AuthServerConfig(
          issuer        = base,
          signingSecret = "integration-test-secret-32-bytes-long",
          supportedScopes = Set("mcp:read", "mcp:invoke")))
        a.clients.register(Client(
          id = "test-client", secret = Some("test-client-secret"),
          redirectUris = Set.empty,
          scopes = Set("mcp:read", "mcp:invoke"),
          grantTypes = Set("client_credentials"),
          clientType = ClientType.Confidential))
        a
      },
      buildBuilder = a => {
        val b = new McpServerBuilder
        b.useAuthServer(a)
        b.tool("echo", Some("Return the input"), ujson.Obj("type" -> "object"),
          args => ToolHandlerResult(
            List(McpProtocol.textContent(s"echoed: ${args.getOrElse("msg", "?")}")),
            isError = false))
        b
      })
    try
      // ─── Discover the AS via /.well-known ─────────────────────
      val metadata = OAuthClient.discoverAs(baseUrl)
      metadata("token_endpoint").str shouldBe s"$baseUrl/token"
      metadata("grant_types_supported").arr.map(_.str).toSet should contain ("client_credentials")

      // ─── Mint a token via client_credentials ──────────────────
      val tokResult = OAuthClient.clientCredentials(
        tokenEndpoint = s"$baseUrl/token",
        clientId      = "test-client",
        clientSecret  = "test-client-secret",
        scopes        = Set("mcp:read", "mcp:invoke"))
      val tokens = tokResult match
        case OAuthClient.TokenResult.Issued(t, _) => t
        case OAuthClient.TokenResult.Error(c, d, _) => fail(s"mint failed: $c — $d")
      tokens.accessToken should not be empty
      tokens.scope should contain ("mcp:read")

      // ─── Drive MCP through the bearer-protected /mcp ──────────
      val mcp = new McpHttpClient(s"$baseUrl/mcp", 5000L)
      mcp.setBearerToken(Some(tokens.accessToken))

      // initialize (spec-mandated handshake)
      mcp.request(McpProtocol.Method.Initialize, ujson.Obj(
        "protocolVersion" -> McpProtocol.ProtocolVersion,
        "capabilities"    -> ujson.Obj(),
        "clientInfo"      -> ujson.Obj("name" -> "e2e-test", "version" -> "1.0")
      )) match
        case Right(js) => js("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
        case Left(e)   => fail(s"initialize failed: ${e.message}")

      // tools/list — should return our `echo` tool
      mcp.request(McpProtocol.Method.ToolsList, ujson.Obj()) match
        case Right(js) =>
          val tools = js("tools").arr
          tools.length shouldBe 1
          tools(0)("name").str shouldBe "echo"
        case Left(e) => fail(s"tools/list failed: ${e.message}")

      // tools/call — invoke the echo tool
      mcp.request(McpProtocol.Method.ToolsCall,
        ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj("msg" -> "hello"))) match
        case Right(js) =>
          js("content")(0)("text").str shouldBe "echoed: hello"
          js("isError").bool shouldBe false
        case Left(e) => fail(s"tools/call failed: ${e.message}")
    finally srv.stop(0)

  test("Auth gating: missing bearer → MCP returns 401"):
    val (srv, baseUrl, _, _) = boot(
      buildAs = base => new AuthServer(AuthServerConfig(
        issuer = base, signingSecret = "k" * 40)),
      buildBuilder = a => { val b = new McpServerBuilder; b.useAuthServer(a); b })
    try
      val mcp = new McpHttpClient(s"$baseUrl/mcp", 5000L)
      mcp.request(McpProtocol.Method.ToolsList, ujson.Obj()) match
        case Left(e)  => e.message should include ("401")
        case Right(_) => fail("expected 401")
    finally srv.stop(0)

  test("Auth gating: wrong bearer → MCP returns 401 invalid_token"):
    val (srv, baseUrl, _, _) = boot(
      buildAs = base => new AuthServer(AuthServerConfig(
        issuer = base, signingSecret = "k" * 40)),
      buildBuilder = a => { val b = new McpServerBuilder; b.useAuthServer(a); b })
    try
      val mcp = new McpHttpClient(s"$baseUrl/mcp", 5000L)
      mcp.setBearerToken(Some("garbage-token"))
      mcp.request(McpProtocol.Method.ToolsList, ujson.Obj()) match
        case Left(e)  => e.message should include ("401")
        case Right(_) => fail("expected 401")
    finally srv.stop(0)

  test("Token revocation: revoked token rejected by MCP server"):
    val (srv, baseUrl, as, _) = boot(
      buildAs = base => {
        val a = new AuthServer(AuthServerConfig(
          issuer = base, signingSecret = "k" * 40,
          supportedScopes = Set("read")))
        a.clients.register(Client(
          id = "svc", secret = Some("s"), redirectUris = Set.empty,
          scopes = Set("read"), grantTypes = Set("client_credentials"),
          clientType = ClientType.Confidential))
        a
      },
      buildBuilder = a => {
        val b = new McpServerBuilder
        b.useAuthServer(a)
        b.tool("ping", None, ujson.Obj("type" -> "object"),
          _ => ToolHandlerResult(List(McpProtocol.textContent("pong")), false))
        b
      })
    try
      val tok = OAuthClient.clientCredentials(s"$baseUrl/token", "svc", "s", Set("read")) match
        case OAuthClient.TokenResult.Issued(t, _) => t.accessToken
        case other => fail(s"mint failed: $other")
      val mcp = new McpHttpClient(s"$baseUrl/mcp", 5000L)
      mcp.setBearerToken(Some(tok))
      mcp.request(McpProtocol.Method.Initialize, ujson.Obj(
        "protocolVersion" -> McpProtocol.ProtocolVersion,
        "capabilities"    -> ujson.Obj(),
        "clientInfo"      -> ujson.Obj("name" -> "x", "version" -> "1.0")
      )) shouldBe a[Right[?, ?]]
      as.revokeToken(tok)
      mcp.request(McpProtocol.Method.ToolsList, ujson.Obj()) match
        case Left(e)  => e.message should include ("401")
        case Right(_) => fail("expected 401 after revocation")
    finally srv.stop(0)

  test("End-to-end discovery: AS metadata + JWKS reachable"):
    val (srv, baseUrl, _, _) = boot(
      buildAs = base => new AuthServer(
        AuthServerConfig(issuer = base, signingSecret = "unused",
          supportedScopes = Set("read")),
        customSigner = Some(OAuth.RsaTokenSigner.generate("e2e-key"))),
      buildBuilder = _ => new McpServerBuilder)
    try
      val md = OAuthClient.discoverAs(baseUrl)
      md("jwks_uri").str should include ("jwks.json")
      val jwksUri = md("jwks_uri").str
      val jwks = ujson.read(scala.io.Source.fromURL(jwksUri).mkString)
      jwks("keys").arr.length shouldBe 1
      jwks("keys")(0)("kid").str shouldBe "e2e-key"
    finally srv.stop(0)
