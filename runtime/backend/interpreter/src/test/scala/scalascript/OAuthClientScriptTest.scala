package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.17.x — Iter OO: script-side OAuth client SDK (`oauth.client.*`).
 *  Covers the pure-function paths (PKCE, state, URL builder,
 *  TokenHolder).  Network-touching paths (discovery, token endpoints)
 *  use the AS-side intrinsics indirectly through their pure
 *  decision logic; full HTTP round-trips are exercised by JVM-side
 *  tests in OAuthClientSdkTest. */
class OAuthClientScriptTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("oauth.client.freshPkce returns a verifier + S256 challenge"):
    val out = captured(
      """
      val p = oauth.client.freshPkce()
      println(p("verifier").length > 40)
      println(p("method"))
      // The challenge MUST be the S256 hash of the verifier
      val recomputed = oauth.pkceChallenge(p("verifier"))
      println(p("challenge") == recomputed)
      """
    )
    out.split("\n").toList shouldBe List("true", "S256", "true")

  test("oauth.client.freshState + verifyState round trip"):
    val out = captured(
      """
      val s = oauth.client.freshState()
      println(s.length > 20)
      println(oauth.client.verifyState(s, s))
      println(oauth.client.verifyState(s, "wrong"))
      """
    )
    out.split("\n").toList shouldBe List("true", "true", "false")

  test("oauth.client.authorizationUrl builds the correct query string"):
    val out = captured(
      """
      val url = oauth.client.authorizationUrl(
        "https://auth.x/authorize", "c1", "http://app/cb",
        List("read", "write"), "state-xyz", "challenge-abc", "S256")
      // The URL must start with the endpoint
      println(url.startsWith("https://auth.x/authorize?"))
      // And include the required params (URL-encoded)
      println(url.contains("response_type=code"))
      println(url.contains("client_id=c1"))
      println(url.contains("code_challenge=challenge-abc"))
      println(url.contains("code_challenge_method=S256"))
      println(url.contains("state=state-xyz"))
      """
    )
    out.split("\n").toList shouldBe List("true", "true", "true", "true", "true", "true")

  test("oauth.client.tokenHolder: seed + current + clear lifecycle"):
    val out = captured(
      """
      val h = oauth.client.tokenHolder("http://unused", "c1", 60L)
      // current() before seed → None
      val before = h.current()
      println(before)  // None
      h.seed(Map(
        "accessToken"  -> "tok-1",
        "tokenType"    -> "Bearer",
        "expiresIn"    -> 3600L,
        "refreshToken" -> "ref-1",
        "scope"        -> List("read")
      ))
      val cur = h.current()
      println(cur.get)
      h.clear()
      println(h.current())
      """
    )
    val lines = out.split("\n").toList
    lines.head shouldBe "None"
    lines(1) shouldBe "tok-1"
    lines(2) shouldBe "None"

  test("oauth.client namespace is reachable from a nested binding"):
    // Bind the namespace through an intermediate val + reach the
    // helpers via field access — verifies the InstanceV companion
    // pattern works the same as `math.sqrt(...)`.
    val out = captured(
      """
      val c = oauth.client
      val s = c.freshState()
      println(s.length > 20)
      val p = c.freshPkce()
      println(p("method"))
      """
    )
    out.split("\n").toList shouldBe List("true", "S256")
