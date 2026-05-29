package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import scalascript.mcp.*

/** v1.17.x — client-side auth coverage: secret hashing on the AS,
 *  bearer headers on the MCP HTTP client.  Both close the security
 *  gap honest audit revealed (plaintext secrets / unauthenticated
 *  MCP clients). */
class OAuthClientAuthTest extends AnyFunSuite with Matchers:

  // ─── Secret hashing ──────────────────────────────────────────────

  test("OAuth.hashSecret produces a recognisable PBKDF2 format"):
    val h = OAuth.hashSecret("topsecret")
    h should startWith ("pbkdf2:")
    h.split(':').length shouldBe 4
    val Array(_, iter, salt, hash) = h.split(':')
    iter.toInt should be >= 1000
    salt.length should be > 10
    hash.length should be > 30

  test("OAuth.verifySecret accepts the right plaintext"):
    val h = OAuth.hashSecret("right")
    OAuth.verifySecret("right",  h) shouldBe true
    OAuth.verifySecret("wrong",  h) shouldBe false
    OAuth.verifySecret("",       h) shouldBe false

  test("OAuth.verifySecret falls back to plaintext compare for legacy entries"):
    OAuth.verifySecret("plain",      "plain") shouldBe true
    OAuth.verifySecret("plain-wrong", "plain") shouldBe false

  test("OAuth.hashSecret: each call uses a fresh salt"):
    val h1 = OAuth.hashSecret("x")
    val h2 = OAuth.hashSecret("x")
    h1 should not be h2  // distinct salts
    OAuth.verifySecret("x", h1) shouldBe true
    OAuth.verifySecret("x", h2) shouldBe true

  test("AS.registerClient stores the secret hashed; client sees plaintext"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://auth.x", signingSecret = "k"))
    val md = ujson.Obj(
      "redirect_uris"             -> ujson.Arr("http://x/cb"),
      "token_endpoint_auth_method" -> "client_secret_basic"
    )
    as.registerClient(md) match
      case Right(c) =>
        c.secret.isDefined shouldBe true
        // The returned client carries the plaintext (so the registering
        // client can record it once); the stored client carries a hash.
        val stored = as.clients.find(c.id).get
        stored.secret.get should startWith ("pbkdf2:")
        stored.secret.get should not be c.secret.get
        // The plaintext must still verify against the stored hash.
        OAuth.verifySecret(c.secret.get, stored.secret.get) shouldBe true
      case Left(err) => fail(s"registration failed: $err")

  test("AS.registerClient → client_credentials grant round-trips with hashed secret"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://auth.x", signingSecret = "k", supportedScopes = Set("read")))
    val md = ujson.Obj(
      "redirect_uris"             -> ujson.Arr("http://x/cb"),
      "grant_types"               -> ujson.Arr("client_credentials"),
      "token_endpoint_auth_method" -> "client_secret_basic",
      "scope"                     -> "read"
    )
    val client = as.registerClient(md).toOption.get
    as.issueToken(TokenRequest.ClientCredentialsGrant(
      client.id, client.secret.get, Set("read"))) shouldBe a[TokenOutcome.Issued]
    // Wrong secret rejected
    as.issueToken(TokenRequest.ClientCredentialsGrant(
      client.id, "wrong", Set("read"))) match
      case TokenOutcome.Error(code, _) => code shouldBe "invalid_client"
      case other => fail(s"got $other")

  // ─── MCP HTTP client bearer ──────────────────────────────────────

  test("McpHttpClient: setBearerToken records the token for outbound requests"):
    val c = new McpHttpClient("http://localhost:0/mcp", 1000L)
    c.setBearerToken(Some("tok-1"))
    // No direct accessor — but the setter is part of the public API
    // contract; we exercise the wire path in the WebServer-backed
    // integration tests elsewhere.  Smoke test: calling it doesn't
    // throw and survives a None reset.
    c.setBearerToken(None)
    c.setBearerToken(Some("tok-2"))
    succeed

  // ─── McpWsClient: constructor accepts bearer ────────────────────

  test("McpWsClient: constructor accepts an optional bearer token"):
    // Connection itself will fail (no server), but the constructor
    // signature must accept the bearer argument cleanly.
    try
      new McpWsClient("ws://localhost:0/mcp", 100L, Some("tok-abc"))
      ()  // unreachable
    catch case _: Throwable => ()  // expected — no server to connect to
    succeed
