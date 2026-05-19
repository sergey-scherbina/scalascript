package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import scalascript.oauth.*

/** v1.17.x — wire the standalone OAuth Authorization Server (separate
 *  module, generic OAuth) into the MCP server's Resource Server role
 *  via `srv.useAuthServer(as)`.  End-to-end: AS issues a token, MCP
 *  validates it on the way in. */
class McpOAuthBridgeTest extends AnyFunSuite with Matchers:

  test("useAuthServer: tokens issued by the AS are accepted by MCP"):
    val as = new AuthServer(AuthServerConfig(
      issuer        = "https://auth.local",
      signingSecret = "shared-secret",
      supportedScopes = Set("read")
    ))
    as.clients.register(Client(
      id           = "svc",
      secret       = Some("svc-secret"),
      redirectUris = Set.empty,
      scopes       = Set("read"),
      grantTypes   = Set("client_credentials"),
      clientType   = ClientType.Confidential
    ))
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "svc-secret", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken

    val builder = new McpServerBuilder
    builder.useAuthServer(as)

    McpServerCore.authorizeHttp(builder, Right(token)) match
      case McpServerCore.AuthOutcome.Allowed(Some(claims)) =>
        claims.subject shouldBe "svc"
        claims.hasScope("read") shouldBe true
      case other => fail(s"expected Allowed(Some), got $other")

  test("useAuthServer: garbage tokens are rejected with invalid_token"):
    val as = new AuthServer(AuthServerConfig("https://auth.local", "sec"))
    val builder = new McpServerBuilder
    builder.useAuthServer(as)
    McpServerCore.authorizeHttp(builder, Right("not-a-real-token")) match
      case McpServerCore.AuthOutcome.Reject(code, _) => code shouldBe "invalid_token"
      case other => fail(s"expected Reject, got $other")

  test("useAuthServer: tokens signed with a different secret are rejected"):
    val asOurs    = new AuthServer(AuthServerConfig("https://auth.us",   "ours-secret"))
    val asTheirs  = new AuthServer(AuthServerConfig("https://auth.them", "theirs-secret"))
    asTheirs.clients.register(Client(
      id = "x", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    val foreignToken = asTheirs.issueToken(TokenRequest.ClientCredentialsGrant(
      "x", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken

    val builder = new McpServerBuilder
    builder.useAuthServer(asOurs)
    McpServerCore.authorizeHttp(builder, Right(foreignToken)) match
      case McpServerCore.AuthOutcome.Reject(_, _) => succeed
      case other => fail(s"expected Reject, got $other")

  test("useAuthServer auto-populates protected-resource metadata"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://auth.local",
      signingSecret = "s",
      supportedScopes = Set("a", "b")
    ))
    val builder = new McpServerBuilder
    builder.useAuthServer(as)
    builder.protectedResourceMetadata.isDefined shouldBe true
    val m = builder.protectedResourceMetadata.get
    m.resource             shouldBe "https://auth.local"
    m.authorizationServers shouldBe List("https://auth.local")
    m.scopesSupported      shouldBe List("a", "b")

  test("useAuthServer does NOT overwrite an explicitly-set metadata"):
    val as = new AuthServer(AuthServerConfig("https://auth.local", "s"))
    val explicit = McpAuth.ProtectedResourceMetadata(
      resource              = "https://api.example.com/mcp",
      authorizationServers  = List("https://other.as"),
      resourceDocumentation = Some("https://docs.example.com")
    )
    val builder = new McpServerBuilder
    builder.setProtectedResourceMetadata(explicit)
    builder.useAuthServer(as)
    builder.protectedResourceMetadata.get shouldBe explicit  // preserved
