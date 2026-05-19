package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — Token revocation (RFC 7009).  Verifies both the typed
 *  AuthServer API and the wire-layer /revoke handler. */
class OAuthRevocationTest extends AnyFunSuite with Matchers:

  private def cfg = AuthServerConfig("https://auth.local", "test-secret",
    supportedScopes = Set("read"))

  private def newWithSvcClient: AuthServer =
    val as = new AuthServer(cfg)
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential
    ))
    as

  // ─── typed API ────────────────────────────────────────────────────

  test("revokeToken accepts an access token + introspection then reports inactive"):
    val as    = newWithSvcClient
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken
    // Active before revocation.
    as.introspect(token).active shouldBe true
    as.revokeToken(token) shouldBe RevocationOutcome.Revoked
    // Inactive after revocation.
    as.introspect(token).active shouldBe false

  test("revoked access tokens fail tokenValidator with invalid_token"):
    val as    = newWithSvcClient
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken
    as.tokenValidator(token) shouldBe a[OAuth.AuthResult.Valid]
    as.revokeToken(token)
    as.tokenValidator(token) match
      case OAuth.AuthResult.Invalid(code, _) => code shouldBe "invalid_token"
      case other => fail(s"expected Invalid, got $other")

  test("revokeToken on a refresh token rejects subsequent refresh attempts"):
    val as = new AuthServer(cfg)
    as.clients.register(Client(
      id = "c1", secret = None, redirectUris = Set("http://x/cb"),
      scopes = Set("read"), clientType = ClientType.Public
    ))
    val ch = OAuth.pkceS256("v" * 50)
    val redir = as.issueAuthorizationCode(
      AuthorizationRequest("code", "c1", "http://x/cb", Set("read"),
        codeChallenge = Some(ch), codeChallengeMethod = Some("S256")),
      "alice"
    ).asInstanceOf[AuthorizationOutcome.CodeRedirect]
    val first = as.issueToken(TokenRequest.AuthorizationCodeGrant(
      redir.code, "http://x/cb", "c1", codeVerifier = Some("v" * 50)
    )).asInstanceOf[TokenOutcome.Issued].response
    as.revokeToken(first.refreshToken.get) shouldBe RevocationOutcome.Revoked
    as.issueToken(TokenRequest.RefreshTokenGrant(
      refreshToken = first.refreshToken.get, clientId = "c1"
    )) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_grant"
      case other => fail(s"expected Error, got $other")

  test("revokeToken on unknown token returns Unknown (per RFC §2.2 still success on wire)"):
    val as = new AuthServer(cfg)
    as.revokeToken("not-a-real-token") shouldBe RevocationOutcome.Unknown

  test("token_type_hint is just a hint — fallback still finds the token"):
    val as    = newWithSvcClient
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken
    // Wrong hint — should still revoke via fallback.
    as.revokeToken(token, TokenTypeHint.RefreshToken) shouldBe RevocationOutcome.Revoked
    as.introspect(token).active shouldBe false

  // ─── /revoke endpoint ────────────────────────────────────────────

  test("/revoke: revoking an access token returns 200 Empty"):
    val as    = newWithSvcClient
    val token = as.issueToken(TokenRequest.ClientCredentialsGrant(
      "svc", "s", Set("read")
    )).asInstanceOf[TokenOutcome.Issued].response.accessToken
    OAuthRoutes.handleRevoke(as, s"token=$token", Map.empty) match
      case OAuthRoutes.RouteOutcome.Empty(200) => succeed
      case other => fail(s"got $other")
    as.introspect(token).active shouldBe false

  test("/revoke: unknown token still returns 200 Empty (RFC 7009 §2.2)"):
    val as = new AuthServer(cfg)
    OAuthRoutes.handleRevoke(as, "token=garbage", Map.empty) match
      case OAuthRoutes.RouteOutcome.Empty(200) => succeed
      case other => fail(s"got $other")

  test("/revoke: missing 'token' param → 400 invalid_request"):
    val as = new AuthServer(cfg)
    OAuthRoutes.handleRevoke(as, "", Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(400, js, _) => js("error").str shouldBe "invalid_request"
      case other => fail(s"got $other")

  // ─── metadata advertises revocation endpoint ─────────────────────

  test("metadataJson advertises the revocation_endpoint + auth methods"):
    val as = new AuthServer(cfg)
    val js = as.metadataJson()
    js("revocation_endpoint").str should endWith ("/revoke")
    js("revocation_endpoint_auth_methods_supported").arr.length should be > 0
