package scalascript.compiler.plugin.oauth

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class OAuthPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(OAuthInterpreterPlugin()))

  test("OAuth plugin exposes client PKCE and state helpers in isolation"):
    val result = interp.eval(
      """
      val p = oauth.client.freshPkce()
      val state = oauth.client.freshState()
      List(
        p("verifier").length > 40,
        p("method"),
        p("challenge") == oauth.pkceChallenge(p("verifier")),
        state.length > 20,
        oauth.client.verifyState(state, state),
        oauth.client.verifyState(state, "wrong")
      )
      """
    )

    assert(result == List(true, "S256", true, true, true, false))

  test("OAuth plugin builds authorization URLs in isolation"):
    val result = interp.eval(
      """
      val url = oauth.client.authorizationUrl(
        "https://auth.x/authorize", "c1", "http://app/cb",
        List("read", "write"), "state-xyz", "challenge-abc", "S256")
      List(
        url.startsWith("https://auth.x/authorize?"),
        url.contains("response_type=code"),
        url.contains("client_id=c1"),
        url.contains("code_challenge=challenge-abc"),
        url.contains("code_challenge_method=S256"),
        url.contains("state=state-xyz")
      )
      """
    )

    assert(result == List(true, true, true, true, true, true))

  test("OAuth plugin validates HMAC bearer tokens in isolation"):
    val valid = interp.eval(
      """
      val validator = oauth.hmacValidator("secret")
      val token = oauth.issueHmacToken("secret", "alice", List("read"), 60)
      val result = validator(token)
      List(result.subject, result.scopes)
      """
    )

    assert(valid == List("alice", List("read")))

    val invalid = interp.eval(
      """
      val validator = oauth.hmacValidator("secret")
      validator("garbage").code
      """
    )

    assert(invalid == "invalid_token")

  test("OAuth plugin creates AuthServer, OIDC, and token holder handles in isolation"):
    val result = interp.eval(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://idp.local",
        "signingSecret" -> "secret",
        "scopes" -> List("openid", "profile")
      ))
      val idp = oidc.server(as)
      val metadata = idp.discovery()

      val holder = oauth.client.tokenHolder("http://unused", "client-1", 60L)
      val before = holder.current().isEmpty
      holder.seed(Map(
        "accessToken" -> "tok-1",
        "tokenType" -> "Bearer",
        "expiresIn" -> 3600L,
        "scope" -> List("read")
      ))
      val current = holder.current().get
      holder.clear()

      List(idp.issuer, metadata("userinfo_endpoint"), before, current, holder.current().isEmpty)
      """
    )

    assert(result == List("https://idp.local", "https://idp.local/userinfo", true, "tok-1", true))
