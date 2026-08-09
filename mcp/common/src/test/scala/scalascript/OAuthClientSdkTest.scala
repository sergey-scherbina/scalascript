package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — pure-function coverage for the OAuth client SDK.
 *  Network-touching paths (discovery, token endpoint) are exercised
 *  through end-to-end tests that boot a real WebServer; here we cover
 *  the URL-building + state-machine pieces. */
class OAuthClientSdkTest extends AnyFunSuite with Matchers:

  // ─── PKCE pair ───────────────────────────────────────────────────

  test("freshPkce: verifier + S256 challenge"):
    val p = OAuthClient.freshPkce()
    p.method shouldBe "S256"
    p.verifier.length should be >= 40
    OAuth.pkceS256(p.verifier) shouldBe p.challenge

  test("freshPkce: each call returns a unique verifier"):
    val a = OAuthClient.freshPkce()
    val b = OAuthClient.freshPkce()
    a.verifier should not be b.verifier

  // ─── authorizationUrl builder ────────────────────────────────────

  test("authorizationUrl: standard params present + URL-encoded"):
    val url = OAuthClient.authorizationUrl(
      authorizationEndpoint = "https://auth.x/authorize",
      clientId              = "c1",
      redirectUri           = "http://app/cb",
      scopes                = Set("read", "write"),
      state                 = "xyz",
      pkce                  = OAuthClient.PkcePair("v", "ch", "S256")
    )
    url should startWith ("https://auth.x/authorize?")
    url should include ("response_type=code")
    url should include ("client_id=c1")
    url should include ("redirect_uri=http%3A%2F%2Fapp%2Fcb")
    url should include ("scope=read+write")  // URL-encoded space
    url should include ("state=xyz")
    url should include ("code_challenge=ch")
    url should include ("code_challenge_method=S256")

  test("authorizationUrl: appends to existing query string with &"):
    val url = OAuthClient.authorizationUrl(
      "https://auth.x/authorize?foo=bar", "c", "http://x", Set.empty, "s",
      OAuthClient.PkcePair("v", "c"))
    url should startWith ("https://auth.x/authorize?foo=bar&")

  // ─── TokenHolder lifecycle (no network — seeds + reads) ─────────

  test("TokenHolder: seeded tokens are current while fresh"):
    val h = new OAuthClient.TokenHolder(
      tokenEndpoint = "http://unused", clientId = "c",
      refreshLeadSeconds = 60L)
    h.current() shouldBe None  // not seeded yet
    h.seed(OAuthClient.Tokens(
      accessToken  = "tok-1",
      tokenType    = "Bearer",
      expiresIn    = 3600L,
      refreshToken = Some("ref-1"),
      idToken      = None,
      scope        = Set("read")))
    h.current() shouldBe Some("tok-1")  // still fresh

  test("TokenHolder.clear removes the cached token"):
    val h = new OAuthClient.TokenHolder("http://unused", "c")
    h.seed(OAuthClient.Tokens("t", "Bearer", 3600L, None, None, Set.empty))
    h.current() shouldBe Some("t")
    h.clear()
    h.current() shouldBe None

  test("TokenHolder: expired-soon token without refresh returns None"):
    val h = new OAuthClient.TokenHolder("http://unused", "c",
      refreshLeadSeconds = 1000L)  // 1000s lead → always "expired"
    h.seed(OAuthClient.Tokens(
      accessToken  = "tok-1",
      tokenType    = "Bearer",
      expiresIn    = 60L,           // ttl < refreshLeadSeconds
      refreshToken = None,           // no refresh token
      idToken      = None,
      scope        = Set.empty))
    h.current() shouldBe None  // refresh impossible
